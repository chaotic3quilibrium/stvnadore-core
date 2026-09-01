package org.stvnadore.core;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import org.stvnadore.core.ir.StvnIrVisitor;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.parser.StvnErrorListener;
import org.stvnadore.core.parser.StvnLexer;
import org.stvnadore.core.parser.StvnParser;

/**
 * The primary entry-point facade for compilation, canonical serialization,
 * and content-addressable storage (CAS) fingerprinting of Strongly Typed Value Notation (STVN) documents.
 * <p>
 * This class coordinates the lexer and parser stages with semantic verification, exposing a stateless,
 * thread-safe interface for working with STVN payload text.
 *
 * @since 1.0.0
 */
@NullMarked
public final class StvnCompiler {

  private StvnCompiler() {
    // Utility/Facade class
  }

  /**
   * Compiles a raw STVN text document into its corresponding {@link StvnValue} Intermediate Representation (IR).
   * <p>
   * This method performs lexical analysis, structural parsing via ANTLR grammar validation, and initiates
   * AST/IR compilation. It automatically registers strict syntax error listeners to fail-fast on any malformed input.
   * <p>
   * <b>Preconditions:</b>
   * <ul>
   *   <li>The input string must not be {@code null}.</li>
   *   <li>The input document must obey the Root Wrapping Rule: it must contain exactly one root-level pair
   *       of curly braces {@code { ... }} wrapping the entire payload.</li>
   * </ul>
   * <p>
   * <b>Postconditions:</b>
   * <ul>
   *   <li>Returns a non-null {@link Optional} containing the root {@link StvnValue} AST node.</li>
   *   <li>If the STVN document has an empty body, {@link Optional#empty()} is returned.</li>
   * </ul>
   * <p>
   * <b>Example Usage:</b>
   * <pre>{@code
   * String rawStvn = "{ :type :Int32 :body 42 }";
   * Optional<StvnValue> ast = StvnCompiler.compile(rawStvn);
   * ast.ifPresent(val -> System.out.println("Parsed integer: " + val));
   * }</pre>
   *
   * @param input the raw STVN document text to compile
   * @return an {@link Optional} enclosing the root {@link StvnValue} node, or {@link Optional#empty()}
   *         if the document body is empty
   * @throws NullPointerException if {@code input} is {@code null}
   * @throws RuntimeException if a lexing or parsing syntax error is encountered during compilation
   */
  public static Optional<StvnValue> compile(String input) {
    return compile(input, null, StvnParserConfig.STRICT);
  }

  /**
   * Compiles a raw STVN text document into its corresponding {@link StvnValue} Intermediate Representation (IR),
   * associating the document context with an optional source document path for relative include resolution.
   * <p>
   * This overload uses strict fail-fast validation configuration ({@link StvnParserConfig#STRICT}).
   *
   * @param input the raw STVN document text to compile
   * @param docPath the optional URI or file path identifier for relative include resolution, or {@code null}
   * @return an {@link Optional} enclosing the root {@link StvnValue} node, or {@link Optional#empty()}
   *         if the document body is empty
   * @throws NullPointerException if {@code input} is {@code null}
   * @throws RuntimeException if a lexing or parsing syntax error is encountered during compilation
   */
  public static Optional<StvnValue> compile(String input, @Nullable String docPath) {
    return compile(input, docPath, StvnParserConfig.STRICT);
  }

  private static String getErrorMessage(org.antlr.v4.runtime.Parser parser, RecognitionException re) {
    return StvnErrorListener.formatSanitizedMessage(parser, re.getOffendingToken(), re.getMessage(), re);
  }

  private static String escapeWSAndQuote(String s) {
    if (s == null) return "null";
    s = s.replace("\n", "\\n");
    s = s.replace("\r", "\\r");
    s = s.replace("\t", "\\t");
    return "'" + s + "'";
  }

  private static String normalizeErrorMessage(String msg) {
    if (msg == null) return "STVN Syntax Error";
    if (msg.contains("no viable alternative at input")) {
      return msg.replace("no viable alternative at input", "mismatched input");
    }
    return msg;
  }

  /**
   * Parses raw STVN text into an ANTLR parse tree root context using the specified parser configuration.
   *
   * @param input the raw STVN document text to parse
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
   * Compiles raw STVN text into an AST {@link StvnValue} representation with custom document path and configuration.
   *
   * @param input the raw STVN document text to compile
   * @param docPath the optional URI or file path identifier for relative include resolution, or {@code null}
   * @param config the parser configuration defining error handling and strictness
   * @return an {@link Optional} enclosing the root {@link StvnValue} node, or {@link Optional#empty()}
   *         if the document body is empty
   * @throws NullPointerException if {@code input} or {@code config} is {@code null}
   * @throws RuntimeException if a syntax error or semantic validation error occurs during compilation
   */
  public static Optional<StvnValue> compile(String input, @Nullable String docPath, StvnParserConfig config) {
    var result = compileToResult(input, docPath, config);
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
    return result.document();
  }

  /**
   * Serializes the given {@link StvnValue} AST node into its canonical, whitespace-stripped
   * text representation.
   * <p>
   * The canonical serializer enforces a unique, repeatable string output by stripping redundant
   * whitespaces, resolving implicit trait annotations, and applying strict boundary spacing injection
   * to prevent token blending (e.g., separating adjacent alphanumeric sequences with exactly one space).
   * <p>
   * <b>Preconditions:</b>
   * <ul>
   *   <li>The {@code value} node must not be {@code null}.</li>
   * </ul>
   * <p>
   * <b>Postconditions:</b>
   * <ul>
   *   <li>Returns a non-null, deterministic STVN text representation of the value.</li>
   * </ul>
   *
   * @param value the {@link StvnValue} AST node to serialize
   * @return the canonical, whitespace-stripped STVN string representation of the value
   * @throws NullPointerException if {@code value} is {@code null}
   */
  public static String toCanonicalString(org.stvnadore.core.ir.StvnValue value) {
    var writer = new org.stvnadore.core.io.CanonicalStvnWriter();
    return writer.printToString(value);
  }

  /**
   * Generates a stable SHA-256 content-addressable storage (CAS) fingerprint of the given
   * {@link StvnValue} AST node.
   * <p>
   * The CAS fingerprint is computed by first generating the canonical STVN string representation
   * of the value (ensuring determinism) and hashing its UTF-8 encoded bytes using SHA-256.
   * <p>
   * <b>Preconditions:</b>
   * <ul>
   *   <li>The {@code value} node must not be {@code null}.</li>
   * </ul>
   * <p>
   * <b>Postconditions:</b>
   * <ul>
   *   <li>Returns a non-null 32-byte hash array representing the unique fingerprint of the value.</li>
   * </ul>
   *
   * @param value the {@link StvnValue} AST node to fingerprint
   * @return a 32-byte cryptographic SHA-256 hash array of the canonical representation
   * @throws NullPointerException if {@code value} is {@code null}
   * @throws RuntimeException if the SHA-256 hashing algorithm is missing from the host environment
   */
  public static byte[] computeCasFingerprint(org.stvnadore.core.ir.StvnValue value) {
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
   * syntax and semantic diagnostics while constructing full or partial IR AST representations.
   *
   * @param source the raw STVN source string
   * @return the monadic compilation result containing the AST and/or accumulated diagnostics
   * @throws NullPointerException if {@code source} is {@code null}
   * @since 1.3.0
   */
  public static StvnCompilationResult<StvnValue> compileToResult(String source) {
    return compileToResult(source, null, StvnParserConfig.DEFAULT);
  }

  /**
   * Compiles an STVN document with a document path identifier into a monadic {@link StvnCompilationResult}.
   *
   * @param source the raw STVN source string
   * @param docPath the optional URI or file path identifier for relative include resolution, or {@code null}
   * @return the monadic compilation result
   * @throws NullPointerException if {@code source} is {@code null}
   * @since 1.3.0
   */
  public static StvnCompilationResult<StvnValue> compileToResult(String source, @Nullable String docPath) {
    return compileToResult(source, docPath, StvnParserConfig.DEFAULT);
  }

  /**
   * Compiles an STVN document with a document path and custom parser configuration into a monadic {@link StvnCompilationResult}.
   *
   * @param source the raw STVN source string
   * @param docPath the optional URI or file path identifier for relative include resolution, or {@code null}
   * @param config the parser configuration specifying diagnostics threshold and strictness
   * @return the monadic compilation result
   * @throws NullPointerException if {@code source} or {@code config} is {@code null}
   * @since 1.3.0
   */
  public static StvnCompilationResult<StvnValue> compileToResult(
      String source,
      @Nullable String docPath,
      StvnParserConfig config
  ) {
    java.util.Objects.requireNonNull(source, "source must not be null");
    java.util.Objects.requireNonNull(config, "config must not be null");

    var diagnosticBag = new org.stvnadore.core.validation.DiagnosticBag(config.maxDiagnostics());

    var lexer = new StvnLexer(CharStreams.fromString(source));
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
        int startOffset = -1;
        int endOffset = -1;
        if (t instanceof org.stvnadore.core.validation.MalformedSchemaException e) {
          startOffset = e.startOffset();
          endOffset = e.endOffset();
        } else if (t instanceof org.stvnadore.core.validation.StvnMalformedLiteralException e) {
          startOffset = e.startOffset();
          endOffset = e.endOffset();
        } else if (t instanceof org.stvnadore.core.validation.StvnCollectionCollisionException e) {
          startOffset = e.startOffset();
          endOffset = e.endOffset();
        } else if (t instanceof org.stvnadore.core.validation.MalformedPayloadException e) {
          startOffset = e.startOffset();
          endOffset = e.endOffset();
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
            t
        ));
      }

      if (docCtx.documentBody() == null || docCtx.documentBody().bodyEntry() == null) {
        return diagnosticBag.hasErrors()
            ? StvnCompilationResult.failure(diagnosticBag.toList())
            : StvnCompilationResult.empty();
      }

      try {
        var visitor = new StvnIrVisitor(docCtx, diagnosticBag);
        StvnValue astValue = visitor.visit(docCtx.documentBody().bodyEntry().value());

        boolean hasErrors = diagnosticBag.hasErrors() || hasErrorNodes(astValue);
        if (hasErrors) {
          return StvnCompilationResult.partial(astValue, diagnosticBag.toList());
        } else {
          return StvnCompilationResult.success(astValue);
        }
      } catch (Throwable t) {
        int startOffset = -1;
        int endOffset = -1;
        if (t instanceof org.stvnadore.core.validation.MalformedSchemaException e) {
          startOffset = e.startOffset();
          endOffset = e.endOffset();
        } else if (t instanceof org.stvnadore.core.validation.StvnMalformedLiteralException e) {
          startOffset = e.startOffset();
          endOffset = e.endOffset();
        } else if (t instanceof org.stvnadore.core.validation.StvnCollectionCollisionException e) {
          startOffset = e.startOffset();
          endOffset = e.endOffset();
        } else if (t instanceof org.stvnadore.core.validation.MalformedPayloadException e) {
          startOffset = e.startOffset();
          endOffset = e.endOffset();
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
            t
        ));
        return StvnCompilationResult.failure(diagnosticBag.toList());
      }
    } catch (org.stvnadore.core.validation.StvnSyntaxCancellationException e) {
      diagnosticBag.add(new StvnDiagnostic(
          e.getMessage() != null ? e.getMessage() : "STVN Syntax Error",
          StvnDiagnostic.DiagnosticSeverity.ERROR,
          e.getLine(),
          e.getColumn(),
          e.getStartOffset(),
          e.getEndOffset(),
          e.getCause(),
          Optional.of("STVN_SYNTAX_CANCELLATION")
      ));
      return StvnCompilationResult.failure(diagnosticBag.toList());
    } catch (org.antlr.v4.runtime.misc.ParseCancellationException e) {
      int line = -1;
      int column = -1;
      int startOffset = -1;
      int endOffset = -1;
      String msg = "STVN Syntax Error";
      Throwable cause = e.getCause();
      if (cause instanceof RecognitionException re) {
        msg = "STVN Syntax Error: " + getErrorMessage(parser, re);
        var token = re.getOffendingToken();
        if (token != null) {
          line = token.getLine();
          column = token.getCharPositionInLine();
          startOffset = token.getStartIndex();
          endOffset = token.getStopIndex();
        }
      } else if (e.getMessage() != null) {
        msg = e.getMessage();
      }
      diagnosticBag.add(new StvnDiagnostic(
          msg,
          StvnDiagnostic.DiagnosticSeverity.ERROR,
          line,
          column,
          startOffset,
          endOffset,
          cause != null ? cause : e,
          Optional.of("STVN_PARSE_CANCELLATION")
      ));
      return StvnCompilationResult.failure(diagnosticBag.toList());
    }
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
   * @return a {@link StvnAnalysisResult} containing either the compiled {@link StvnValue} or a list of diagnostics
   */
  public static StvnAnalysisResult<Optional<StvnValue>, List<StvnDiagnostic>> analyze(String source) {
    return analyze(source, null, StvnParserConfig.STRICT);
  }

  /**
   * Analyzes a raw STVN text document deterministically with a document path, collecting any syntax or semantic diagnostics.
   * <p>
   * This overload uses strict fail-fast validation configuration ({@link StvnParserConfig#STRICT}).
   *
   * @param source the raw STVN document text to analyze
   * @param docPath the optional URI or file path identifier for relative include resolution, or {@code null}
   * @return a {@link StvnAnalysisResult} containing either the compiled {@link StvnValue} or a list of diagnostics
   * @throws NullPointerException if {@code source} is {@code null}
   */
  public static StvnAnalysisResult<Optional<StvnValue>, List<StvnDiagnostic>> analyze(String source, @Nullable String docPath) {
    return analyze(source, docPath, StvnParserConfig.STRICT);
  }

  /**
   * Analyzes a raw STVN text document deterministically using the specified parser configuration,
   * collecting any syntax or semantic diagnostics.
   *
   * @param source the raw STVN document text to analyze
   * @param docPath the optional URI or file path identifier for relative include resolution, or {@code null}
   * @param config the parser configuration defining error handling and strictness
   * @return a {@link StvnAnalysisResult} containing either the compiled {@link StvnValue} or a list of diagnostics
   * @throws NullPointerException if {@code source} or {@code config} is {@code null}
   */
  public static StvnAnalysisResult<Optional<StvnValue>, List<StvnDiagnostic>> analyze(
      String source,
      @Nullable String docPath,
      StvnParserConfig config
  ) {
    java.util.Objects.requireNonNull(source, "source must not be null");
    java.util.Objects.requireNonNull(config, "config must not be null");

    var result = compileToResult(source, docPath, config);
    if (result.hasErrors() || !result.diagnostics().isEmpty()) {
      return new StvnAnalysisResult<>(Optional.empty(), result.diagnostics());
    }
    return new StvnAnalysisResult<>(result.document(), List.of());
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
