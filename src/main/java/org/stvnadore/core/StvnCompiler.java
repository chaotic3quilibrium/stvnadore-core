package org.stvnadore.core;

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.binary.StvnBinaryDecoder.RootPointer;
import org.stvnadore.core.io.CanonicalStvnWriter;
import org.stvnadore.core.ir.StvnDefsCatalog;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnDocumentKind;
import org.stvnadore.core.ir.StvnDocumentMeta;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.lowering.StvnDefsLowerer;
import org.stvnadore.core.lowering.StvnPayloadLowerer;
import org.stvnadore.core.lowering.StvnReachabilityAndCasVerifier;
import org.stvnadore.core.parser.StvnErrorListener;
import org.stvnadore.core.parser.StvnErrorStrategy;
import org.stvnadore.core.parser.StvnLexer;
import org.stvnadore.core.parser.StvnParser;
import org.stvnadore.core.parser.StvnSyntaxErrorSanitizer;
import org.stvnadore.core.printer.AstCompactPrinter;
import org.stvnadore.core.printer.PrinterOptions;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.core.validation.StvnSyntaxCancellationException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Primary stateless entry-point facade for compilation, canonical serialization,
 * and content-addressable storage (CAS) fingerprinting of Strongly Typed Value Notation (STVN) documents.
 * <p>
 * Coordinates the STVN 2.0.0 4-pass stateless lowering pipeline:
 * <ol>
 *   <li>Pass 1: Lexing, parsing, and deterministic syntax error sanitization ({@link StvnSyntaxErrorSanitizer})</li>
 *   <li>Pass 2: Metadata, definitions catalog, and tautology-purged schema lowering ({@link StvnDefsLowerer})</li>
 *   <li>Pass 3: CPU-bias-free payload lowering with container fault isolation ({@link StvnPayloadLowerer})</li>
 *   <li>Pass 4: Transitive reachability, alias desugaring, and topological catalog ordering ({@link StvnReachabilityAndCasVerifier})</li>
 * </ol>
 *
 * @since 1.0.0
 */
@NullMarked
public final class StvnCompiler {

  private StvnCompiler() {
    // Non-instantiable facade class
  }

  /**
   * Compiles a raw STVN text document into a monadic {@link StvnCompilationResult} containing a pure-IR {@link StvnDocument}.
   *
   * @param input the raw STVN document text to compile
   * @return the monadic compilation result enclosing the lowered IR document and accumulated diagnostics
   * @throws NullPointerException if {@code input} is {@code null}
   * @since 2.0.0
   */
  public static StvnCompilationResult<StvnDocument> compile(String input) {
    return compile(input, null, StvnParserConfig.DEFAULT);
  }

  /**
   * Compiles a raw STVN text document into a monadic {@link StvnCompilationResult} with an optional document path.
   *
   * @param input   the raw STVN document text to compile
   * @param docPath the optional URI or file path identifier for document identity and relative include resolution, or {@code null}
   * @return the monadic compilation result enclosing the lowered IR document and accumulated diagnostics
   * @throws NullPointerException if {@code input} is {@code null}
   * @since 2.0.0
   */
  public static StvnCompilationResult<StvnDocument> compile(String input, @Nullable String docPath) {
    return compile(input, docPath, StvnParserConfig.DEFAULT);
  }

  /**
   * Compiles a raw STVN text document with an optional document path and custom parser configuration
   * through the 4-pass stateless STVN 2.0.0 lowering pipeline.
   *
   * @param input   the raw STVN document text to compile
   * @param docPath the optional URI or file path identifier for document identity and relative include resolution, or {@code null}
   * @param config  the parser configuration specifying diagnostics threshold and strictness
   * @return the monadic compilation result enclosing the lowered IR document and accumulated diagnostics
   * @throws NullPointerException if {@code input} or {@code config} is {@code null}
   * @since 2.0.0
   */
  public static StvnCompilationResult<StvnDocument> compile(
      String input,
      @Nullable String docPath,
      StvnParserConfig config
  ) {
    Objects.requireNonNull(input, "input must not be null");
    Objects.requireNonNull(config, "config must not be null");

    var diagnosticBag = new DiagnosticBag(config.maxDiagnostics());

    // Pass 1: Lexing, parsing, and deterministic syntax error sanitization
    var lexer = new StvnLexer(CharStreams.fromString(input));
    lexer.removeErrorListeners();

    var parser = new StvnParser(new CommonTokenStream(lexer));
    parser.removeErrorListeners();

    if (config.strict()) {
      parser.setErrorHandler(new BailErrorStrategy());
      var errorListener = StvnErrorListener.strict();
      lexer.addErrorListener(errorListener);
      parser.addErrorListener(errorListener);
    } else {
      var listener = StvnErrorListener.accumulating(diagnosticBag);
      lexer.addErrorListener(listener);
      parser.addErrorListener(listener);
      parser.setErrorHandler(new StvnErrorStrategy());
    }

    try {
      var docCtx = parser.stvnDocument();
      var docBody = docCtx.documentBody();
      boolean hasNoClauses = docBody == null
          || (docBody.metaEntry() == null
              && docBody.defsEntry() == null
              && docBody.typeEntry() == null
              && docBody.bodyEntry() == null);

      if (hasNoClauses && parser.getNumberOfSyntaxErrors() > 0) {
        return StvnCompilationResult.failure(diagnosticBag.toList());
      }

      // Pass 2: Stateless metadata, definitions catalog, and root schema lowering
      StvnDefsLowerer.DefsLoweringResult defsRes;
      try {
        defsRes = StvnDefsLowerer.lowerDocumentHeaderAndDefs(docCtx, docPath, config, diagnosticBag);
      } catch (Throwable t) {
        mapUnexpectedLoweringException(t, input, diagnosticBag);
        return StvnCompilationResult.failure(diagnosticBag.toList());
      }

      if (hasNoClauses) {
        return diagnosticBag.hasErrors()
            ? StvnCompilationResult.failure(diagnosticBag.toList())
            : StvnCompilationResult.empty(diagnosticBag.toList());
      }

      boolean hasBodyClause = docBody != null && docBody.bodyEntry() != null;
      if (!hasBodyClause) {
        if (diagnosticBag.hasErrors()) {
          return StvnCompilationResult.failure(diagnosticBag.toList());
        }
        var rawDefsDoc = new StvnDocument(
            defsRes.meta(),
            defsRes.defs(),
            defsRes.rootType(),
            Optional.empty(),
            defsRes.hasDefsSection(),
            defsRes.hasIncludes()
        );
        var verifiedDefsDoc = StvnReachabilityAndCasVerifier.verifyAndOrder(rawDefsDoc, diagnosticBag);
        return StvnCompilationResult.success(verifiedDefsDoc, diagnosticBag.toList());
      }

      // Determine whether Pass 3 payload lowering is eligible under fault-containment rules
      boolean canValidateBody = parser.getNumberOfSyntaxErrors() == 0
          && defsRes.sectionsEligible()
          && defsRes.rootType().isPresent()
          && docBody.bodyEntry().value() != null;

      if (canValidateBody && diagnosticBag.hasErrors()) {
        int docStart = docCtx.getStart() != null ? docCtx.getStart().getStartIndex() : 0;
        int docStop = docCtx.getStop() != null ? docCtx.getStop().getStopIndex() + 1 : Integer.MAX_VALUE;

        var defsCtx = docBody.defsEntry();
        int defsStart = defsCtx != null && defsCtx.getStart() != null ? defsCtx.getStart().getStartIndex() : -1;
        int defsStop = defsCtx != null && defsCtx.getStop() != null ? defsCtx.getStop().getStopIndex() + 1 : -1;

        for (var diag : diagnosticBag.toList()) {
          if (diag.severity() != StvnDiagnostic.DiagnosticSeverity.ERROR) {
            continue;
          }
          String sym = diag.symbolicId().or(() -> diag.errorCode()).orElse("");
          if (sym.startsWith("ERR_DOCUMENT_")
              || sym.startsWith("ERR_SECTION_")
              || sym.equals(DiagnosticBag.ERR_EMPTY_DEFS_BLOCK)
              || sym.equals(DiagnosticBag.ERR_EMPTY_META_BLOCK)
              || sym.equals(DiagnosticBag.ERR_INVALID_FILENAME_STEM)
              || sym.equals(DiagnosticBag.ERR_LEGACY_FILE_EXTENSION_PURGED)
              || sym.equals(DiagnosticBag.ERR_META_POSITION_INVALID)
              || sym.equals(DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED)
              || sym.equals("STVN_SYNTAX_ERROR")
              || sym.startsWith("SYNTAX_")) {
            canValidateBody = false;
            break;
          }
          int offset = diag.startOffset();
          if (offset >= docStart && offset < docStop) {
            boolean inDefs = defsStart >= 0 && offset >= defsStart && offset < defsStop;
            if (!inDefs) {
              canValidateBody = false;
              break;
            }
          }
        }
      }

      if (!canValidateBody) {
        return StvnCompilationResult.failure(diagnosticBag.toList());
      }

      // Pass 3: Stateless payload lowering with container cardinality fault isolation
      Optional<StvnValue> payload = Optional.empty();
      try {
        StvnValue loweredValue = StvnPayloadLowerer.lowerValue(
            docBody.bodyEntry().value(),
            defsRes.rootType().get(),
            defsRes.rootConstantResolver(),
            defsRes.nominalTypeLookup(),
            diagnosticBag
        );
        payload = Optional.of(loweredValue);
      } catch (Throwable t) {
        mapUnexpectedLoweringException(t, input, diagnosticBag);
      }

      // Pass 4: Reachability verification, alias desugaring check, and topological DFS post-order catalog ordering
      var rawDocument = new StvnDocument(
          defsRes.meta(),
          defsRes.defs(),
          defsRes.rootType(),
          payload,
          defsRes.hasDefsSection(),
          defsRes.hasIncludes()
      );
      var verifiedDocument = hasCanonicalStrictHeader(input)
          ? StvnReachabilityAndCasVerifier.verifyAndPruneCanonical(rawDocument, true, defsRes.definitionSpans(), diagnosticBag)
          : StvnReachabilityAndCasVerifier.verifyAndOrder(rawDocument, diagnosticBag);

      boolean hasErrors = diagnosticBag.hasErrors()
          || (payload.isPresent() && hasErrorNodes(payload.get()));
      if (hasErrors) {
        return StvnCompilationResult.partial(verifiedDocument, diagnosticBag.toList());
      }
      return StvnCompilationResult.success(verifiedDocument, diagnosticBag.toList());
    } catch (StvnSyntaxCancellationException e) {
      String raw = e.getMessage() != null ? e.getMessage() : "STVN Syntax Error";
      String msg = StvnSyntaxErrorSanitizer.purgeResidualAntlrJargon(raw);
      String symbolicId = e.getSymbolicId();
      if (symbolicId == null) {
        symbolicId = msg.contains(StvnVocabulary.KEYWORD_META)
            ? DiagnosticBag.ERR_META_POSITION_INVALID
            : "STVN_SYNTAX_ERROR";
      }
      String canonicalCode = e.getCanonicalCode();
      if (canonicalCode == null) {
        canonicalCode = DiagnosticBag.resolveCanonicalCode(symbolicId).orElse("E002");
      }
      diagnosticBag.add(new StvnDiagnostic(
          msg,
          StvnDiagnostic.DiagnosticSeverity.ERROR,
          e.getLine(),
          e.getColumn(),
          e.getStartOffset(),
          e.getEndOffset(),
          e.getCause(),
          Optional.of(canonicalCode),
          Optional.of(symbolicId)
      ));
      return StvnCompilationResult.failure(diagnosticBag.toList());
    } catch (ParseCancellationException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RecognitionException re) {
        Token token = re.getOffendingToken();
        int line = token != null ? token.getLine() : 1;
        int column = token != null ? token.getCharPositionInLine() : 0;
        var sanitized = StvnSyntaxErrorSanitizer.sanitize(
            parser,
            token,
            line,
            column,
            re.getMessage() != null ? re.getMessage() : "",
            re
        );
        diagnosticBag.add(sanitized.toDiagnostic(re));
      } else {
        String raw = e.getMessage() != null ? e.getMessage() : "STVN Syntax Error";
        String msg = StvnSyntaxErrorSanitizer.purgeResidualAntlrJargon(raw);
        String symbolicId = msg.contains(StvnVocabulary.KEYWORD_META)
            ? DiagnosticBag.ERR_META_POSITION_INVALID
            : "STVN_SYNTAX_ERROR";
        String canonicalCode = DiagnosticBag.resolveCanonicalCode(symbolicId).orElse("E002");
        diagnosticBag.add(new StvnDiagnostic(
            msg,
            StvnDiagnostic.DiagnosticSeverity.ERROR,
            -1,
            -1,
            -1,
            -1,
            cause != null ? cause : e,
            Optional.of(canonicalCode),
            Optional.of(symbolicId)
        ));
      }
      return StvnCompilationResult.failure(diagnosticBag.toList());
    }
  }

  /**
   * Compiles an STVN document located at the given filesystem path using {@link StvnParserConfig#DEFAULT}.
   *
   * @param path the filesystem path of the STVN source or binary file
   * @return the monadic compilation result enclosing the lowered IR document
   * @throws IOException          if an I/O error occurs reading the file
   * @throws NullPointerException if {@code path} is {@code null}
   * @since 2.0.0
   */
  public static StvnCompilationResult<StvnDocument> compile(Path path) throws IOException {
    return compile(path, StvnParserConfig.DEFAULT);
  }

  /**
   * Compiles an STVN document located at the given filesystem path with custom parser configuration.
   *
   * @param path   the filesystem path of the STVN source or binary file
   * @param config the parser configuration
   * @return the monadic compilation result enclosing the lowered IR document
   * @throws IOException          if an I/O error occurs reading the file
   * @throws NullPointerException if {@code path} or {@code config} is {@code null}
   * @since 2.0.0
   */
  public static StvnCompilationResult<StvnDocument> compile(Path path, StvnParserConfig config) throws IOException {
    Objects.requireNonNull(path, "path must not be null");
    Objects.requireNonNull(config, "config must not be null");
    Path fileNamePath = path.getFileName();
    String fileName = fileNamePath != null ? fileNamePath.toString() : path.toString();
    if (fileName.endsWith(".stvn_b") || fileName.endsWith(".stvn_bd")) {
      byte[] bytes = Files.readAllBytes(path);
      return compile(bytes, path.toString(), config);
    }
    return compile(Files.readString(path), path.toString(), config);
  }

  /**
   * Compiles an STVN binary byte array payload ({@code .stvn_b} or {@code .stvn_bd}) into a monadic compilation result.
   *
   * @param bytes   the raw STVN binary bytes
   * @param docPath the optional URI or file path identifier
   * @param config  the parser configuration
   * @return the monadic compilation result enclosing the lowered IR document and diagnostics
   */
  public static StvnCompilationResult<StvnDocument> compile(
      byte[] bytes,
      @Nullable String docPath,
      StvnParserConfig config
  ) {
    Objects.requireNonNull(bytes, "bytes must not be null");
    Objects.requireNonNull(config, "config must not be null");
    return compile(ByteBuffer.wrap(bytes), docPath, config);
  }

  /**
   * Compiles an STVN binary {@link ByteBuffer} payload ({@code .stvn_b} or {@code .stvn_bd})
   * into a monadic compilation result.
   *
   * @param buffer  the raw STVN binary byte buffer
   * @param docPath the optional URI or file path identifier
   * @param config  the parser configuration
   * @return the monadic compilation result enclosing the lowered IR document and diagnostics
   */
  public static StvnCompilationResult<StvnDocument> compile(
      ByteBuffer buffer,
      @Nullable String docPath,
      StvnParserConfig config
  ) {
    Objects.requireNonNull(buffer, "buffer must not be null");
    Objects.requireNonNull(config, "config must not be null");

    int byteLength = buffer.remaining();
    var diagnosticBag = new DiagnosticBag(config.maxDiagnostics());
    try {
      RootPointer root = StvnBinaryDecoder.open(buffer.duplicate());
      Optional<StvnDocumentMeta> irMeta = root.meta();

      StvnDefsLowerer.validateBinaryDocumentIdentity(irMeta.orElse(null), docPath, diagnosticBag);

      StvnDocumentKind kind = irMeta.flatMap(StvnDocumentMeta::kind).orElse(StvnDocumentKind.BODY);
      if (kind == StvnDocumentKind.DEFS || kind == StvnDocumentKind.DEFS_INCLUDE) {
        try {
          StvnDocument defsDoc = StvnBinaryDecoder.decodeDocument(buffer.duplicate());
          return diagnosticBag.hasErrors()
              ? StvnCompilationResult.partial(defsDoc, diagnosticBag.toList())
              : StvnCompilationResult.success(defsDoc, diagnosticBag.toList());
        } catch (Throwable t) {
          diagnosticBag.addError(
              "Failed to unpack .stvn_bd definitions payload: " + t.getMessage(),
              0, byteLength, 1, 0, t,
              "MALFORMED_PAYLOAD"
          );
          var fallbackDoc = new StvnDocument(
              irMeta,
              StvnDefsCatalog.EMPTY,
              Optional.empty(),
              Optional.empty(),
              true,
              kind == StvnDocumentKind.DEFS_INCLUDE
          );
          return StvnCompilationResult.partial(fallbackDoc, diagnosticBag.toList());
        }
      }

      Optional<StvnValue> payload = Optional.empty();
      try {
        StvnValue unpacked = StvnBinaryDecoder.unpack(root, Optional.empty());
        payload = Optional.of(unpacked);
      } catch (Throwable t) {
        diagnosticBag.addError(
            "Failed to unpack binary payload: " + t.getMessage(),
            0, byteLength, 1, 0, t,
            "MALFORMED_PAYLOAD"
        );
      }

      Optional<StvnSchema> rootType = payload.map(StvnValue::schema);
      var document = new StvnDocument(
          irMeta,
          StvnDefsCatalog.EMPTY,
          rootType,
          payload,
          false,
          kind == StvnDocumentKind.BODY_INCLUDE
      );
      return diagnosticBag.hasErrors()
          ? StvnCompilationResult.partial(document, diagnosticBag.toList())
          : StvnCompilationResult.success(document, diagnosticBag.toList());
    } catch (Throwable t) {
      diagnosticBag.addError(
          t.getMessage() != null ? t.getMessage() : t.toString(),
          0, byteLength, 1, 0, t,
          "MALFORMED_PAYLOAD"
      );
      return StvnCompilationResult.failure(diagnosticBag.toList());
    }
  }

  /**
   * Compiles a raw STVN text document into its corresponding pure-IR {@link StvnValue} payload.
   *
   * @param input the raw STVN document text to compile
   * @return an {@link Optional} enclosing the root {@link StvnValue} payload node, or empty if no payload is present
   * @throws NullPointerException if {@code input} is {@code null}
   * @throws RuntimeException     if a syntax error or semantic validation error occurs during compilation
   * @since 2.0.0
   */
  public static Optional<StvnValue> compilePayload(String input) {
    return compilePayload(input, null, StvnParserConfig.STRICT);
  }

  /**
   * Compiles a raw STVN text document into its corresponding pure-IR {@link StvnValue} payload with an optional document path.
   *
   * @param input   the raw STVN document text to compile
   * @param docPath the optional URI or file path identifier, or {@code null}
   * @return an {@link Optional} enclosing the root {@link StvnValue} payload node, or empty if no payload is present
   * @throws NullPointerException if {@code input} is {@code null}
   * @throws RuntimeException     if a syntax error or semantic validation error occurs during compilation
   * @since 2.0.0
   */
  public static Optional<StvnValue> compilePayload(String input, @Nullable String docPath) {
    return compilePayload(input, docPath, StvnParserConfig.STRICT);
  }

  /**
   * Compiles a raw STVN text document into its corresponding pure-IR {@link StvnValue} payload with custom configuration.
   *
   * @param input   the raw STVN document text to compile
   * @param docPath the optional URI or file path identifier, or {@code null}
   * @param config  the parser configuration defining error handling and strictness
   * @return an {@link Optional} enclosing the root {@link StvnValue} payload node, or empty if no payload is present
   * @throws NullPointerException if {@code input} or {@code config} is {@code null}
   * @throws RuntimeException     if a syntax error or semantic validation error occurs during compilation
   * @since 2.0.0
   */
  public static Optional<StvnValue> compilePayload(
      String input,
      @Nullable String docPath,
      StvnParserConfig config
  ) {
    var result = compile(input, docPath, config);
    if (result.hasErrors()) {
      var first = result.diagnostics().stream()
          .filter(d -> d.severity() == StvnDiagnostic.DiagnosticSeverity.ERROR)
          .findFirst()
          .orElseGet(() -> result.diagnostics().getFirst());
      if (first.cause() instanceof StvnSyntaxCancellationException sce) {
        throw new RuntimeException(sce.getMessage(), sce);
      }
      if (first.cause() instanceof RuntimeException re
          && !(re instanceof RecognitionException)
          && !(re instanceof ParseCancellationException)) {
        throw re;
      }
      throw new RuntimeException(first.message(), first.cause());
    }
    return result.document().flatMap(StvnDocument::payload);
  }

  /**
   * Compiles an STVN document located at the given filesystem path into its pure-IR {@link StvnValue} payload.
   *
   * @param path the filesystem path of the STVN source file
   * @return an {@link Optional} enclosing the root {@link StvnValue} payload node, or empty if no payload is present
   * @throws IOException          if an I/O error occurs reading the file
   * @throws NullPointerException if {@code path} is {@code null}
   * @throws RuntimeException     if compilation fails
   * @since 2.0.0
   */
  public static Optional<StvnValue> compilePayload(Path path) throws IOException {
    Objects.requireNonNull(path, "path must not be null");
    return compilePayload(Files.readString(path), path.toString(), StvnParserConfig.STRICT);
  }

  /**
   * Parses raw STVN text into an ANTLR parse tree root context using the specified parser configuration.
   *
   * @param input  the raw STVN document text to parse
   * @param config the parser configuration defining error handling and strictness
   * @return the parsed {@link StvnParser.StvnDocumentContext} parse tree root
   * @throws NullPointerException            if {@code input} or {@code config} is {@code null}
   * @throws StvnSyntaxCancellationException if {@code config.strict()} is true and a syntax error is encountered
   */
  public static StvnParser.StvnDocumentContext parse(String input, StvnParserConfig config) {
    Objects.requireNonNull(input, "input must not be null");
    Objects.requireNonNull(config, "config must not be null");

    var lexer = new StvnLexer(CharStreams.fromString(input));
    lexer.removeErrorListeners();

    var parser = new StvnParser(new CommonTokenStream(lexer));
    parser.removeErrorListeners();

    if (config.strict()) {
      parser.setErrorHandler(new BailErrorStrategy());
      var errorListener = StvnErrorListener.strict();
      lexer.addErrorListener(errorListener);
      parser.addErrorListener(errorListener);
    } else {
      parser.setErrorHandler(new StvnErrorStrategy());
    }

    return parser.stvnDocument();
  }

  /**
   * Serializes the given pure-IR {@link StvnValue} into its canonical, whitespace-normalized STVN text representation.
   *
   * @param value the pure-IR {@link StvnValue} node to serialize
   * @return the canonical STVN string representation of the value
   * @throws NullPointerException if {@code value} is {@code null}
   * @since 2.0.0
   */
  public static String toCanonicalString(StvnValue value) {
    return new CanonicalStvnWriter().printToString(value);
  }

  /**
   * Serializes the given pure-IR {@link StvnDocument} into its canonical, whitespace-normalized STVN text representation.
   *
   * @param document the pure-IR {@link StvnDocument} to serialize
   * @return the canonical STVN string representation of the document
   * @throws NullPointerException if {@code document} is {@code null}
   * @since 2.0.0
   */
  public static String toCanonicalString(StvnDocument document) {
    return new CanonicalStvnWriter().printToString(document);
  }

  /**
   * Serializes the given pure-IR {@link StvnDocument} to compact STVN text using custom options.
   *
   * @param document the pure-IR document to serialize
   * @param options  the printer options to apply
   * @return the compact STVN string representation
   * @since 2.0.0
   */
  public static String compactPrint(StvnDocument document, PrinterOptions options) {
    return AstCompactPrinter.print(document, options);
  }

  /**
   * Serializes the given pure-IR {@link StvnDocument} to compact STVN text using default compact options.
   *
   * @param document the pure-IR document to serialize
   * @return the compact STVN string representation
   * @since 2.0.0
   */
  public static String compactPrint(StvnDocument document) {
    return AstCompactPrinter.print(document);
  }

  /**
   * Serializes the given pure-IR {@link StvnValue} payload with an explicit {@link StvnSchema} to compact STVN text.
   *
   * @param payload the pure-IR value payload to serialize
   * @param schema  the explicit pure-IR target schema
   * @param options the printer options to apply
   * @return the compact STVN string representation
   * @since 2.0.0
   */
  public static String compactPrint(StvnValue payload, StvnSchema schema, PrinterOptions options) {
    return AstCompactPrinter.print(payload, schema, options);
  }

  /**
   * Serializes the given pure-IR {@link StvnValue} payload to compact STVN text using custom options.
   *
   * @param payload the pure-IR value payload to serialize
   * @param options the printer options to apply
   * @return the compact STVN string representation
   * @since 2.0.0
   */
  public static String compactPrint(StvnValue payload, PrinterOptions options) {
    return AstCompactPrinter.print(payload, options);
  }

  /**
   * Serializes the given pure-IR {@link StvnValue} payload to compact STVN text using default compact options.
   *
   * @param payload the pure-IR value payload to serialize
   * @return the compact STVN string representation
   * @since 2.0.0
   */
  public static String compactPrint(StvnValue payload) {
    return AstCompactPrinter.print(payload);
  }

  /**
   * Generates a stable SHA-256 content-addressable storage (CAS) fingerprint of the given pure-IR {@link StvnValue}.
   *
   * @param value the pure-IR {@link StvnValue} node to fingerprint
   * @return a 32-byte cryptographic SHA-256 hash array of the canonical text preimage
   * @throws NullPointerException if {@code value} is {@code null}
   * @since 2.0.0
   */
  public static byte[] computeCasFingerprint(StvnValue value) {
    return sha256Utf8(toCanonicalString(value));
  }

  /**
   * Generates a stable SHA-256 content-addressable storage (CAS) fingerprint of the given pure-IR {@link StvnDocument}.
   *
   * @param document the pure-IR {@link StvnDocument} to fingerprint
   * @return a 32-byte cryptographic SHA-256 hash array of the canonical text preimage
   * @throws NullPointerException if {@code document} is {@code null}
   * @since 2.0.0
   */
  public static byte[] computeCasFingerprint(StvnDocument document) {
    return sha256Utf8(toCanonicalString(document));
  }

  private static byte[] sha256Utf8(String canonical) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      return digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm missing from environment", e);
    }
  }

  /**
   * Compiles an STVN document into a monadic {@link StvnCompilationResult}, accumulating all
   * syntax and semantic diagnostics while constructing pure-IR representations.
   *
   * @param source the raw STVN source string
   * @return the monadic compilation result containing the lowered IR document and accumulated diagnostics
   * @throws NullPointerException if {@code source} is {@code null}
   * @since 1.3.0
   */
  public static StvnCompilationResult<StvnDocument> compileToResult(String source) {
    return compile(source, null, StvnParserConfig.DEFAULT);
  }

  /**
   * Compiles an STVN document with a document path identifier into a monadic {@link StvnCompilationResult}.
   *
   * @param source  the raw STVN source string
   * @param docPath the optional URI or file path identifier for relative include resolution, or {@code null}
   * @return the monadic compilation result
   * @throws NullPointerException if {@code source} is {@code null}
   * @since 1.3.0
   */
  public static StvnCompilationResult<StvnDocument> compileToResult(String source, @Nullable String docPath) {
    return compile(source, docPath, StvnParserConfig.DEFAULT);
  }

  /**
   * Compiles an STVN document with a document path and custom parser configuration into a monadic {@link StvnCompilationResult}.
   *
   * @param source  the raw STVN source string
   * @param docPath the optional URI or file path identifier for relative include resolution, or {@code null}
   * @param config  the parser configuration specifying diagnostics threshold and strictness
   * @return the monadic compilation result
   * @throws NullPointerException if {@code source} or {@code config} is {@code null}
   * @since 1.3.0
   */
  public static StvnCompilationResult<StvnDocument> compileToResult(
      String source,
      @Nullable String docPath,
      StvnParserConfig config
  ) {
    return compile(source, docPath, config);
  }

  /**
   * Compiles an STVN binary payload with a document path identifier into a monadic {@link StvnCompilationResult}.
   *
   * @param bytes   the raw STVN binary bytes
   * @param docPath the optional URI or file path identifier, or {@code null}
   * @return the monadic compilation result
   * @throws NullPointerException if {@code bytes} is {@code null}
   * @since 2.0.0
   */
  public static StvnCompilationResult<StvnDocument> compileToResult(byte[] bytes, @Nullable String docPath) {
    return compile(bytes, docPath, StvnParserConfig.DEFAULT);
  }

  /**
   * Compiles an STVN binary payload with a document path and custom parser configuration into a monadic {@link StvnCompilationResult}.
   *
   * @param bytes   the raw STVN binary bytes
   * @param docPath the optional URI or file path identifier, or {@code null}
   * @param config  the parser configuration specifying diagnostics threshold and strictness
   * @return the monadic compilation result
   * @throws NullPointerException if {@code bytes} or {@code config} is {@code null}
   * @since 2.0.0
   */
  public static StvnCompilationResult<StvnDocument> compileToResult(
      byte[] bytes,
      @Nullable String docPath,
      StvnParserConfig config
  ) {
    return compile(bytes, docPath, config);
  }

  /**
   * Checks whether a pure-IR {@link StvnValue} node or any of its descendants contains an {@link StvnValue.StvnErrorValue}.
   *
   * @param val the pure-IR value node to inspect
   * @return {@code true} if an error sentinel node is present anywhere in the subtree
   * @since 2.0.0
   */
  public static boolean hasErrorNodes(StvnValue val) {
    Objects.requireNonNull(val, "val must not be null");
    return switch (val) {
      case StvnValue.StvnErrorValue ignored -> true;
      case StvnValue.StvnSeq seq -> seq.elements().stream().anyMatch(StvnCompiler::hasErrorNodes);
      case StvnValue.StvnSet set -> set.elements().stream().anyMatch(StvnCompiler::hasErrorNodes);
      case StvnValue.StvnTuple tuple -> tuple.elements().stream().anyMatch(StvnCompiler::hasErrorNodes);
      case StvnValue.StvnMap map -> map.entries().stream()
          .anyMatch(e -> hasErrorNodes(e.key()) || hasErrorNodes(e.value()));
      case StvnValue.StvnOption opt -> opt.value().map(StvnCompiler::hasErrorNodes).orElse(false);
      case StvnValue.StvnEither either -> hasErrorNodes(either.value());
      case StvnValue.StvnUnion union -> hasErrorNodes(union.value());
      default -> false;
    };
  }

  /**
   * Analyzes a raw STVN text document deterministically, collecting any syntax or semantic diagnostics.
   *
   * @param source the raw STVN document text to analyze
   * @return a {@link StvnAnalysisResult} containing either the compiled {@link StvnDocument} or a list of diagnostics
   */
  public static StvnAnalysisResult<Optional<StvnDocument>, List<StvnDiagnostic>> analyze(String source) {
    return analyze(source, null, StvnParserConfig.STRICT);
  }

  /**
   * Analyzes a raw STVN text document deterministically with a document path, collecting any syntax or semantic diagnostics.
   *
   * @param source  the raw STVN document text to analyze
   * @param docPath the optional URI or file path identifier for relative include resolution, or {@code null}
   * @return a {@link StvnAnalysisResult} containing either the compiled {@link StvnDocument} or a list of diagnostics
   * @throws NullPointerException if {@code source} is {@code null}
   */
  public static StvnAnalysisResult<Optional<StvnDocument>, List<StvnDiagnostic>> analyze(
      String source,
      @Nullable String docPath
  ) {
    return analyze(source, docPath, StvnParserConfig.STRICT);
  }

  /**
   * Analyzes a raw STVN text document deterministically using the specified parser configuration,
   * collecting any syntax or semantic diagnostics.
   *
   * @param source  the raw STVN document text to analyze
   * @param docPath the optional URI or file path identifier for relative include resolution, or {@code null}
   * @param config  the parser configuration defining error handling and strictness
   * @return a {@link StvnAnalysisResult} containing either the compiled {@link StvnDocument} or a list of diagnostics
   * @throws NullPointerException if {@code source} or {@code config} is {@code null}
   */
  public static StvnAnalysisResult<Optional<StvnDocument>, List<StvnDiagnostic>> analyze(
      String source,
      @Nullable String docPath,
      StvnParserConfig config
  ) {
    Objects.requireNonNull(source, "source must not be null");
    Objects.requireNonNull(config, "config must not be null");

    var result = compile(source, docPath, config);
    if (result.hasErrors() || !result.diagnostics().isEmpty()) {
      return new StvnAnalysisResult<>(Optional.empty(), result.diagnostics());
    }
    return new StvnAnalysisResult<>(result.document(), List.of());
  }

  private static void mapUnexpectedLoweringException(
      Throwable t,
      String source,
      DiagnosticBag diagnosticBag
  ) {
    int startOffset = -1;
    int endOffset = -1;
    String symbolicId = DiagnosticBag.ERR_MALFORMED_SCHEMA;
    if (t instanceof org.stvnadore.core.validation.MalformedSchemaException e) {
      startOffset = e.startOffset();
      endOffset = e.endOffset();
      symbolicId = DiagnosticBag.ERR_MALFORMED_SCHEMA;
    } else if (t instanceof org.stvnadore.core.validation.StvnMalformedLiteralException e) {
      startOffset = e.startOffset();
      endOffset = e.endOffset();
      symbolicId = DiagnosticBag.ERR_INVALID_NUMERIC_LITERAL;
    } else if (t instanceof org.stvnadore.core.validation.StvnCollectionCollisionException e) {
      startOffset = e.startOffset();
      endOffset = e.endOffset();
      symbolicId = DiagnosticBag.ERR_DUPLICATE_SET_ELEMENT;
    } else if (t instanceof org.stvnadore.core.validation.StvnIntegerOverflowException e) {
      startOffset = e.startOffset();
      endOffset = e.endOffset();
      symbolicId = DiagnosticBag.ERR_INTEGER_OVERFLOW;
    } else if (t instanceof org.stvnadore.core.validation.MalformedPayloadException e) {
      startOffset = e.startOffset();
      endOffset = e.endOffset();
      symbolicId = DiagnosticBag.ERR_CONSTRAINT_VIOLATION;
    }
    int line = -1;
    int column = -1;
    if (startOffset >= 0 && startOffset <= source.length()) {
      int[] resolved = resolveLineColumn(source, startOffset);
      line = resolved[0];
      column = resolved[1];
    }
    String rawMsg = t.getMessage() != null ? t.getMessage() : t.toString();
    String sanitizedMsg = StvnSyntaxErrorSanitizer.purgeResidualAntlrJargon(rawMsg);
    diagnosticBag.addError(sanitizedMsg, startOffset, endOffset, line, column, t, symbolicId);
  }

  private static boolean hasCanonicalStrictHeader(String source) {
    int limit = Math.min(source.length(), 512);
    int idx = 0;
    while (idx < limit) {
      while (idx < limit && (source.charAt(idx) == ' ' || source.charAt(idx) == '\r' || source.charAt(idx) == '\n')) {
        idx++;
      }
      if (idx + 1 < limit && source.charAt(idx) == '/' && source.charAt(idx + 1) == '/') {
        int lineEnd = source.indexOf('\n', idx);
        if (lineEnd < 0) {
          lineEnd = source.length();
        }
        String commentLine = source.substring(idx, lineEnd);
        if (commentLine.contains("@mode: CANONICAL_STRICT")) {
          return true;
        }
        idx = lineEnd + 1;
      } else {
        break;
      }
    }
    return false;
  }

  private static int[] resolveLineColumn(String source, int offset) {
    int line = 1;
    int column = 0;
    for (int i = 0; i < offset; i++) {
      char c = source.charAt(i);
      if (c == '\n') {
        line++;
        column = 0;
      } else if (c != '\r') {
        column++;
      }
    }
    return new int[]{line, column};
  }
}
