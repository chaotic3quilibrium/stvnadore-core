package org.stvnadore.core;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Token;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.ast.StvnDocument;
import org.stvnadore.core.ast.StvnDocumentMeta;
import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.binary.StvnBinaryDecoder.RootPointer;
import org.stvnadore.core.ir.StvnIrVisitor;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.parser.StvnErrorListener;
import org.stvnadore.core.parser.StvnLexer;
import org.stvnadore.core.parser.StvnParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The primary entry-point facade for compilation, canonical serialization,
 * and content-addressable storage (CAS) fingerprinting of Strongly Typed Value Notation (STVN) documents.
 * <p>
 * This class coordinates the lexer and parser stages with semantic verification, exposing a stateless,
 * thread-safe interface for working with STVN payload text and AST records.
 *
 * @since 1.0.0
 */
@NullMarked
public final class StvnCompiler {

  private StvnCompiler() {
    // Utility/Facade class
  }

  /**
   * Compiles a raw STVN text document into a monadic {@link StvnCompilationResult} containing an AST {@link StvnDocument}.
   *
   * @param input the raw STVN document text to compile
   * @return the monadic compilation result enclosing the AST document and/or accumulated diagnostics
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
   * @param docPath the optional URI or file path identifier for relative include resolution, or {@code null}
   * @return the monadic compilation result enclosing the AST document and/or accumulated diagnostics
   * @throws NullPointerException if {@code input} is {@code null}
   * @since 2.0.0
   */
  public static StvnCompilationResult<StvnDocument> compile(String input, @Nullable String docPath) {
    return compile(input, docPath, StvnParserConfig.DEFAULT);
  }

  /**
   * Compiles a raw STVN text document with a document path and custom parser configuration into a monadic result.
   *
   * @param input   the raw STVN document text to compile
   * @param docPath the optional URI or file path identifier for relative include resolution, or {@code null}
   * @param config  the parser configuration specifying diagnostics threshold and strictness
   * @return the monadic compilation result enclosing the AST document and/or accumulated diagnostics
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

    var diagnosticBag = new org.stvnadore.core.validation.DiagnosticBag(config.maxDiagnostics());

    var lexer = new StvnLexer(CharStreams.fromString(input));
    lexer.removeErrorListeners();

    var parser = new StvnParser(new CommonTokenStream(lexer));
    parser.removeErrorListeners();

    if (config.strict()) {
      parser.setErrorHandler(new org.antlr.v4.runtime.BailErrorStrategy());
      var errorListener = StvnErrorListener.strict();
      lexer.addErrorListener(errorListener);
      parser.addErrorListener(errorListener);
    } else {
      var listener = StvnErrorListener.accumulating(diagnosticBag);
      lexer.addErrorListener(listener);
      parser.addErrorListener(listener);
    }

    try {
      var docCtx = parser.stvnDocument();

      if (diagnosticBag.hasErrors()) {
        return StvnCompilationResult.failure(diagnosticBag.toList());
      }

      if (docPath != null) {
        org.stvnadore.core.validation.StvnTypeResolver.documentPaths.put(docCtx, docPath);
      }

      try {
        org.stvnadore.core.validation.StvnTypeResolver.validateDocumentConstraints(docCtx, diagnosticBag);
      } catch (Throwable t) {
        mapSemanticException(t, input, diagnosticBag);
      }

      if (docCtx.documentBody() == null || (docCtx.documentBody().metaEntry() == null && docCtx.documentBody().defsEntry() == null && docCtx.documentBody().bodyEntry() == null)) {
        return diagnosticBag.hasErrors()
            ? StvnCompilationResult.failure(diagnosticBag.toList())
            : StvnCompilationResult.empty(diagnosticBag.toList());
      }

      // Build StvnDocument AST
      Optional<StvnDocumentMeta> meta = StvnIrVisitor.extractMeta(docCtx.documentBody().metaEntry());

      Optional<StvnValue> payload = Optional.empty();
      if (docCtx.documentBody().bodyEntry() != null) {
        try {
          var visitor = new StvnIrVisitor(docCtx, diagnosticBag);
          StvnValue astValue = visitor.visit(docCtx.documentBody().bodyEntry().value());
          payload = Optional.ofNullable(astValue);
        } catch (Throwable t) {
          mapSemanticException(t, input, diagnosticBag);
        }
      }

      var document = new StvnDocument(meta, payload, Optional.of(docCtx));

      boolean hasErrors = diagnosticBag.hasErrors() || (payload.isPresent() && hasErrorNodes(payload.get()));
      if (hasErrors) {
        return StvnCompilationResult.partial(document, diagnosticBag.toList());
      } else {
        return StvnCompilationResult.success(document, diagnosticBag.toList());
      }
    } catch (org.stvnadore.core.validation.StvnSyntaxCancellationException e) {
      String msg = e.getMessage() != null ? e.getMessage() : "STVN Syntax Error";
      String errCode = "STVN_SYNTAX_CANCELLATION";
      if (msg.contains("':meta'")) {
        errCode = org.stvnadore.core.validation.DiagnosticBag.ERR_META_POSITION_INVALID;
      }
      diagnosticBag.add(new StvnDiagnostic(
          msg,
          StvnDiagnostic.DiagnosticSeverity.ERROR,
          e.getLine(),
          e.getColumn(),
          e.getStartOffset(),
          e.getEndOffset(),
          e.getCause(),
          Optional.of(errCode)
      ));
      return StvnCompilationResult.failure(diagnosticBag.toList());
    } catch (org.antlr.v4.runtime.misc.ParseCancellationException e) {
      int line = -1;
      int column = -1;
      int startOffset = -1;
      int endOffset = -1;
      String msg = "STVN Syntax Error";
      String errCode = "STVN_PARSE_CANCELLATION";
      Throwable cause = e.getCause();
      if (cause instanceof RecognitionException re) {
        msg = "STVN Syntax Error: " + getErrorMessage(parser, re);
        var token = re.getOffendingToken();
        if (token != null) {
          line = token.getLine();
          column = token.getCharPositionInLine();
          startOffset = token.getStartIndex();
          endOffset = token.getType() == Token.EOF
              ? startOffset
              : Math.max(startOffset + 1, token.getStopIndex() + 1);
          if (token.getText() != null && token.getText().equals(StvnVocabulary.KEYWORD_META)) {
            errCode = org.stvnadore.core.validation.DiagnosticBag.ERR_META_POSITION_INVALID;
          }
        }
      } else if (e.getMessage() != null) {
        msg = e.getMessage();
        if (msg.contains("':meta'")) {
          errCode = org.stvnadore.core.validation.DiagnosticBag.ERR_META_POSITION_INVALID;
        }
      }
      diagnosticBag.add(new StvnDiagnostic(
          msg,
          StvnDiagnostic.DiagnosticSeverity.ERROR,
          line,
          column,
          startOffset,
          endOffset,
          cause != null ? cause : e,
          Optional.of(errCode)
      ));
      return StvnCompilationResult.failure(diagnosticBag.toList());
    }
  }

  /**
   * Compiles an STVN document located at the given filesystem path.
   *
   * @param path the filesystem path of the STVN source file
   * @return the monadic compilation result enclosing the AST document
   * @throws IOException          if an I/O error occurs reading the file
   * @throws NullPointerException if {@code path} is {@code null}
   * @since 2.0.0
   */
  public static StvnCompilationResult<StvnDocument> compile(Path path) throws IOException {
    return compile(Files.readString(path), path.toString(), StvnParserConfig.DEFAULT);
  }

  /**
   * Compiles an STVN document located at the given filesystem path with custom parser configuration.
   *
   * @param path   the filesystem path of the STVN source file
   * @param config the parser configuration
   * @return the monadic compilation result enclosing the AST document
   * @throws IOException          if an I/O error occurs reading the file
   * @throws NullPointerException if {@code path} or {@code config} is {@code null}
   * @since 2.0.0
   */
  public static StvnCompilationResult<StvnDocument> compile(Path path, StvnParserConfig config) throws IOException {
    String fileName = path.getFileName().toString();
    if (fileName.endsWith(".stvn_b") || fileName.endsWith(".stvn_bd")) {
      byte[] bytes = Files.readAllBytes(path);
      return compile(bytes, path.toString(), config);
    }
    return compile(Files.readString(path), path.toString(), config);
  }

  /**
   * Compiles an STVN binary byte array payload into a monadic compilation result.
   *
   * @param bytes   the raw STVN binary bytes
   * @param docPath the optional URI or file path identifier
   * @param config  the parser configuration
   * @return the monadic compilation result enclosing the AST document and diagnostics
   */
  public static StvnCompilationResult<StvnDocument> compile(
      byte[] bytes,
      @Nullable String docPath,
      StvnParserConfig config
  ) {
    Objects.requireNonNull(bytes, "bytes must not be null");
    Objects.requireNonNull(config, "config must not be null");

    var diagnosticBag = new org.stvnadore.core.validation.DiagnosticBag(config.maxDiagnostics());
    try {
      var buffer = ByteBuffer.wrap(bytes);
      RootPointer root = StvnBinaryDecoder.open(buffer);
      Optional<StvnDocumentMeta> meta = root.meta();

      // Execute document identity validation against docPath
      org.stvnadore.core.validation.StvnTypeResolver.validateBinaryDocumentIdentity(
          meta.orElse(null), docPath, diagnosticBag);

      Optional<StvnValue> payload = Optional.empty();
      try {
        StvnValue unpacked = StvnBinaryDecoder.unpack(root, Optional.empty());
        payload = Optional.ofNullable(unpacked);
      } catch (Throwable t) {
        diagnosticBag.addError("Failed to unpack binary payload: " + t.getMessage(), 0, bytes.length, 1, 0, null, "MALFORMED_PAYLOAD");
      }

      var document = new StvnDocument(meta, payload, Optional.empty());
      return diagnosticBag.hasErrors()
          ? StvnCompilationResult.partial(document, diagnosticBag.toList())
          : StvnCompilationResult.success(document, diagnosticBag.toList());
    } catch (Throwable t) {
      diagnosticBag.addError(t.getMessage() != null ? t.getMessage() : t.toString(), 0, bytes.length, 1, 0, t, "STVN_BINARY_INGRESS_ERROR");
      return StvnCompilationResult.failure(diagnosticBag.toList());
    }
  }

  /**
   * Compiles an STVN binary ByteBuffer payload into a monadic compilation result.
   *
   * @param buffer  the raw STVN binary ByteBuffer
   * @param docPath the optional URI or file path identifier
   * @param config  the parser configuration
   * @return the monadic compilation result enclosing the AST document and diagnostics
   */
  public static StvnCompilationResult<StvnDocument> compile(
      ByteBuffer buffer,
      @Nullable String docPath,
      StvnParserConfig config
  ) {
    Objects.requireNonNull(buffer, "buffer must not be null");
    Objects.requireNonNull(config, "config must not be null");

    var diagnosticBag = new org.stvnadore.core.validation.DiagnosticBag(config.maxDiagnostics());
    try {
      RootPointer root = StvnBinaryDecoder.open(buffer);
      Optional<StvnDocumentMeta> meta = root.meta();

      // Execute document identity validation against docPath
      org.stvnadore.core.validation.StvnTypeResolver.validateBinaryDocumentIdentity(
          meta.orElse(null), docPath, diagnosticBag);

      Optional<StvnValue> payload = Optional.empty();
      try {
        StvnValue unpacked = StvnBinaryDecoder.unpack(root, Optional.empty());
        payload = Optional.ofNullable(unpacked);
      } catch (Throwable t) {
        diagnosticBag.addError("Failed to unpack binary payload: " + t.getMessage(), 0, buffer.remaining(), 1, 0, null, "MALFORMED_PAYLOAD");
      }

      var document = new StvnDocument(meta, payload, Optional.empty());
      return diagnosticBag.hasErrors()
          ? StvnCompilationResult.partial(document, diagnosticBag.toList())
          : StvnCompilationResult.success(document, diagnosticBag.toList());
    } catch (Throwable t) {
      diagnosticBag.addError(t.getMessage() != null ? t.getMessage() : t.toString(), 0, buffer.remaining(), 1, 0, t, "STVN_BINARY_INGRESS_ERROR");
      return StvnCompilationResult.failure(diagnosticBag.toList());
    }
  }

  /**
   * Compiles a raw STVN text document into its corresponding {@link StvnValue} payload Intermediate Representation.
   *
   * @param input the raw STVN document text to compile
   * @return an {@link Optional} enclosing the root {@link StvnValue} payload node, or empty if body is empty
   * @throws NullPointerException if {@code input} is {@code null}
   * @throws RuntimeException     if a syntax error or semantic validation error occurs during compilation
   * @since 2.0.0
   */
  public static Optional<StvnValue> compilePayload(String input) {
    return compilePayload(input, null, StvnParserConfig.STRICT);
  }

  /**
   * Compiles a raw STVN text document into its corresponding {@link StvnValue} payload with an optional document path.
   *
   * @param input   the raw STVN document text to compile
   * @param docPath the optional URI or file path identifier, or {@code null}
   * @return an {@link Optional} enclosing the root {@link StvnValue} payload node, or empty if body is empty
   * @throws NullPointerException if {@code input} is {@code null}
   * @throws RuntimeException     if a syntax error or semantic validation error occurs during compilation
   * @since 2.0.0
   */
  public static Optional<StvnValue> compilePayload(String input, @Nullable String docPath) {
    return compilePayload(input, docPath, StvnParserConfig.STRICT);
  }

  /**
   * Compiles a raw STVN text document into its corresponding {@link StvnValue} payload with custom configuration.
   *
   * @param input   the raw STVN document text to compile
   * @param docPath the optional URI or file path identifier, or {@code null}
   * @param config  the parser configuration defining error handling and strictness
   * @return an {@link Optional} enclosing the root {@link StvnValue} payload node, or empty if body is empty
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
      var first = result.diagnostics().getFirst();
      if (first.cause() instanceof org.stvnadore.core.validation.StvnSyntaxCancellationException sce) {
        throw new RuntimeException(sce.getMessage(), sce);
      }
      if (first.cause() instanceof RuntimeException re && !(re instanceof org.antlr.v4.runtime.RecognitionException) && !(re instanceof org.antlr.v4.runtime.misc.ParseCancellationException)) {
        throw re;
      }
      throw new RuntimeException(first.message(), first.cause());
    }
    return result.document().flatMap(StvnDocument::payload);
  }

  /**
   * Compiles an STVN document located at the given filesystem path into its {@link StvnValue} payload.
   *
   * @param path the filesystem path of the STVN source file
   * @return an {@link Optional} enclosing the root {@link StvnValue} payload node, or empty if body is empty
   * @throws IOException          if an I/O error occurs reading the file
   * @throws NullPointerException if {@code path} is {@code null}
   * @throws RuntimeException     if compilation fails
   * @since 2.0.0
   */
  public static Optional<StvnValue> compilePayload(Path path) throws IOException {
    return compilePayload(Files.readString(path), path.toString(), StvnParserConfig.STRICT);
  }

  private static String getErrorMessage(org.antlr.v4.runtime.Parser parser, RecognitionException re) {
    return StvnErrorListener.formatSanitizedMessage(parser, re.getOffendingToken(), re.getMessage(), re);
  }

  /**
   * Parses raw STVN text into an ANTLR parse tree root context using the specified parser configuration.
   *
   * @param input  the raw STVN document text to parse
   * @param config the parser configuration defining error handling and strictness
   * @return the parsed {@link StvnParser.StvnDocumentContext} parse tree root
   * @throws NullPointerException if {@code input} or {@code config} is {@code null}
   * @throws org.stvnadore.core.validation.StvnSyntaxCancellationException if {@code config.strict()} is true
   *         and a syntax error is encountered
   * @throws RuntimeException if a parsing error occurs during document recognition
   */
  public static StvnParser.StvnDocumentContext parse(String input, StvnParserConfig config) {
    var lexer = new StvnLexer(CharStreams.fromString(input));
    lexer.removeErrorListeners();

    var parser = new StvnParser(new CommonTokenStream(lexer));
    parser.removeErrorListeners();

    if (config.strict()) {
      parser.setErrorHandler(new org.antlr.v4.runtime.BailErrorStrategy());
      var errorListener = StvnErrorListener.strict();
      lexer.addErrorListener(errorListener);
      parser.addErrorListener(errorListener);
    }

    return parser.stvnDocument();
  }

  /**
   * Serializes the given {@link StvnValue} AST node into its canonical, whitespace-stripped text representation.
   *
   * @param value the {@link StvnValue} AST node to serialize
   * @return the canonical, whitespace-stripped STVN string representation of the value
   * @throws NullPointerException if {@code value} is {@code null}
   */
  public static String toCanonicalString(StvnValue value) {
    var writer = new org.stvnadore.core.io.CanonicalStvnWriter();
    return writer.printToString(value);
  }

  /**
   * Serializes the given {@link org.stvnadore.core.ast.StvnDocument} to compact STVN text using custom options.
   *
   * @param document the document to serialize
   * @param options  the printer options to apply
   * @return the compact STVN string representation
   */
  public static String compactPrint(org.stvnadore.core.ast.StvnDocument document, org.stvnadore.core.printer.PrinterOptions options) {
    return org.stvnadore.core.printer.AstCompactPrinter.print(document, options);
  }

  /**
   * Serializes the given {@link org.stvnadore.core.ast.StvnDocument} to compact STVN text using default compact options.
   *
   * @param document the document to serialize
   * @return the compact STVN string representation
   */
  public static String compactPrint(org.stvnadore.core.ast.StvnDocument document) {
    return org.stvnadore.core.printer.AstCompactPrinter.print(document);
  }

  /**
   * Serializes the given {@link StvnValue} payload with explicit schema to compact STVN text using custom options.
   *
   * @param payload the value payload to serialize
   * @param schema  the explicit target schema
   * @param options the printer options to apply
   * @return the compact STVN string representation
   */
  public static String compactPrint(StvnValue payload, org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema schema, org.stvnadore.core.printer.PrinterOptions options) {
    return org.stvnadore.core.printer.AstCompactPrinter.print(payload, schema, options);
  }

  /**
   * Serializes the given {@link StvnValue} payload to compact STVN text using custom options.
   *
   * @param payload the value payload to serialize
   * @param options the printer options to apply
   * @return the compact STVN string representation
   */
  public static String compactPrint(StvnValue payload, org.stvnadore.core.printer.PrinterOptions options) {
    return org.stvnadore.core.printer.AstCompactPrinter.print(payload, options);
  }

  /**
   * Serializes the given {@link StvnValue} payload to compact STVN text using default compact options.
   *
   * @param payload the value payload to serialize
   * @return the compact STVN string representation
   */
  public static String compactPrint(StvnValue payload) {
    return org.stvnadore.core.printer.AstCompactPrinter.print(payload);
  }

  /**
   * Generates a stable SHA-256 content-addressable storage (CAS) fingerprint of the given {@link StvnValue} AST node.
   *
   * @param value the {@link StvnValue} AST node to fingerprint
   * @return a 32-byte cryptographic SHA-256 hash array of the canonical representation
   * @throws NullPointerException if {@code value} is {@code null}
   * @throws RuntimeException     if the SHA-256 hashing algorithm is missing from the host environment
   */
  public static byte[] computeCasFingerprint(StvnValue value) {
    var canonical = toCanonicalString(value);
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      return digest.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 algorithm missing from environment", e);
    }
  }

  /**
   * Compiles an STVN document into a monadic {@link StvnCompilationResult}, accumulating all
   * syntax and semantic diagnostics while constructing AST representations.
   *
   * @param source the raw STVN source string
   * @return the monadic compilation result containing the AST and/or accumulated diagnostics
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
   * Checks if an AST node or any of its descendant nodes contains an unrecovered {@link StvnValue.StvnError}.
   *
   * @param val the AST value node to inspect
   * @return {@code true} if an error node is present in the subtree
   */
  public static boolean hasErrorNodes(StvnValue val) {
    if (val instanceof StvnValue.StvnError) return true;
    return switch (val) {
      case StvnValue.StvnSeq seq -> seq.elements().stream().anyMatch(StvnCompiler::hasErrorNodes);
      case StvnValue.StvnSet set -> set.elements().stream().anyMatch(StvnCompiler::hasErrorNodes);
      case StvnValue.StvnTuple tuple -> tuple.elements().stream().anyMatch(StvnCompiler::hasErrorNodes);
      case StvnValue.StvnMap map -> map.entries().entrySet().stream().anyMatch(e -> hasErrorNodes(e.getKey()) || hasErrorNodes(e.getValue()));
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
  public static StvnAnalysisResult<Optional<StvnDocument>, List<StvnDiagnostic>> analyze(String source, @Nullable String docPath) {
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

  private static void mapSemanticException(Throwable t, String source, org.stvnadore.core.validation.DiagnosticBag diagnosticBag) {
    int startOffset = -1;
    int endOffset = -1;
    String errorCode = null;
    if (t instanceof org.stvnadore.core.validation.MalformedSchemaException e) {
      startOffset = e.startOffset();
      endOffset = e.endOffset();
      String msg = e.getMessage() != null ? e.getMessage() : "";
      if (msg.contains("requires a scale facet")) {
        errorCode = org.stvnadore.core.validation.DiagnosticBag.ERR_TEMPORAL_SCALE_MISSING;
      } else if (msg.contains("requires exactly one mode facet")) {
        errorCode = org.stvnadore.core.validation.DiagnosticBag.ERR_DATETIME_MODE_INVALID;
      } else if (msg.contains("prelude") && msg.contains("purged")) {
        errorCode = org.stvnadore.core.validation.DiagnosticBag.ERR_PRELUDE_ALIAS_PURGED;
      } else if (msg.contains("Undefined type") || msg.contains("Unknown or undefined type")) {
        errorCode = org.stvnadore.core.validation.DiagnosticBag.ERR_UNDEFINED_TYPE;
      } else if (msg.contains("filter facets") || msg.contains("is not permitted on") || msg.contains("requires an explicit boolean value")) {
        errorCode = org.stvnadore.core.validation.DiagnosticBag.ERR_INVALID_METADATA_FACET;
      } else if (msg.contains("Facet '#size' is prohibited on :String") || msg.contains("#size' is prohibited on :String")) {
        errorCode = org.stvnadore.core.validation.DiagnosticBag.ERR_STRING_CARDINALITY_PROHIBITED;
      } else if (msg.contains("violates canonical 7-tier order")) {
        errorCode = org.stvnadore.core.validation.DiagnosticBag.ERR_FACET_ORDER_VIOLATION;
      } else if (msg.contains("defines an empty domain")) {
        errorCode = org.stvnadore.core.validation.DiagnosticBag.ERR_EMPTY_INTERVAL_DOMAIN;
      } else if (msg.contains("require types to be #equatable #TRUE")) {
        errorCode = org.stvnadore.core.validation.DiagnosticBag.ERR_TRAIT_VIOLATION;
      }
    } else if (t instanceof org.stvnadore.core.validation.StvnMalformedLiteralException e) {
      startOffset = e.startOffset();
      endOffset = e.endOffset();
    } else if (t instanceof org.stvnadore.core.validation.StvnCollectionCollisionException e) {
      startOffset = e.startOffset();
      endOffset = e.endOffset();
      if (e.getMessage() != null && (e.getMessage().contains("Ambiguous implicit resolution") || e.getMessage().contains("Ambiguous implicit either"))) {
        errorCode = org.stvnadore.core.validation.DiagnosticBag.ERR_AMBIGUOUS_SUM_INFERENCE;
      } else if (e.getMessage() != null && e.getMessage().contains("Duplicate inverted map value detected")) {
        errorCode = org.stvnadore.core.validation.DiagnosticBag.ERR_DUPLICATE_INVERTED_MAP_VALUE;
      } else if (e.getMessage() != null && e.getMessage().contains("Duplicate map key detected")) {
        errorCode = org.stvnadore.core.validation.DiagnosticBag.ERR_DUPLICATE_MAP_KEY;
      }
    } else if (t instanceof org.stvnadore.core.validation.StvnIntegerOverflowException e) {
      startOffset = e.startOffset();
      endOffset = e.endOffset();
      errorCode = org.stvnadore.core.validation.DiagnosticBag.ERR_INTEGER_OVERFLOW;
    } else if (t instanceof org.stvnadore.core.validation.MalformedPayloadException e) {
      startOffset = e.startOffset();
      endOffset = e.endOffset();
      if (e.getMessage() != null && (e.getMessage().contains("Ambiguous implicit resolution") || e.getMessage().contains("Ambiguous implicit either"))) {
        errorCode = org.stvnadore.core.validation.DiagnosticBag.ERR_AMBIGUOUS_SUM_INFERENCE;
      } else if (e.getMessage() != null && e.getMessage().startsWith("Type mismatch")) {
        errorCode = org.stvnadore.core.validation.DiagnosticBag.ERR_TYPE_MISMATCH;
      } else if (e.getMessage() != null && (e.getMessage().contains("outside allowable range")
          || e.getMessage().contains("violates #minSize constraint")
          || e.getMessage().contains("exceeds maximum length of")
          || e.getMessage().contains("Fixed string must be"))) {
        errorCode = org.stvnadore.core.validation.DiagnosticBag.ERR_CONSTRAINT_VIOLATION;
      }
    }
    int line = -1;
    int column = -1;
    if (startOffset >= 0 && startOffset <= source.length()) {
      int[] resolved = resolveLineColumn(source, startOffset);
      line = resolved[0];
      column = resolved[1];
    }
    diagnosticBag.add(new StvnDiagnostic(
        t.getMessage() != null ? t.getMessage() : t.toString(),
        StvnDiagnostic.DiagnosticSeverity.ERROR,
        line,
        column,
        startOffset,
        endOffset,
        t,
        Optional.ofNullable(errorCode)
    ));
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
