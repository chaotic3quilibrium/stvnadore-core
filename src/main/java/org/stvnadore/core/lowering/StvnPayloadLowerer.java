package org.stvnadore.core.lowering;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnDefsCatalog;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnSchema.BoundKind;
import org.stvnadore.core.ir.StvnSchema.DateTimeMode;
import org.stvnadore.core.ir.StvnSchema.FloatRepresentation;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.ir.StvnValue.EitherBranch;
import org.stvnadore.core.ir.StvnValue.Ieee754SpecialKind;
import org.stvnadore.core.ir.StvnValue.KeyValueEntry;
import org.stvnadore.core.ir.StvnValue.StvnErrorValue;
import org.stvnadore.core.parser.StvnParser;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.core.validation.MalformedPayloadException;
import org.stvnadore.core.validation.MalformedSchemaException;
import org.stvnadore.core.validation.StvnCollectionCollisionException;
import org.stvnadore.core.validation.StvnIntegerOverflowException;
import org.stvnadore.core.validation.StvnMalformedLiteralException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pass 3 stateless payload lowerer for the STVN 2.0.0 4-pass lowering pipeline.
 * <p>
 * Lowers parsed {@link StvnParser.ValueContext} subtrees against normalized
 * {@link StvnSchema} IR definitions into immutable {@link StvnValue} trees while enforcing:
 * <ul>
 *   <li>CPU-bias-free {@link BigInteger} integer, tick, enum ordinal, and union index validation</li>
 *   <li>Arbitrary-precision {@link BigDecimal} floating-point and exact-decimal mantissa bounds</li>
 *   <li>RFC-compliant simple string escape decoding and verbatim Rule STR-04 fenced strings</li>
 *   <li>Tripartite temporal rules ({@code E019}–{@code E022})</li>
 *   <li>Payload inference Rules A through J ({@code E023}, {@code E024}) and opaque nominal branding ({@code E027})</li>
 *   <li>Container cardinality fault isolation ({@code M == N}) via {@link StvnErrorValue} sentinels</li>
 * </ul>
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnPayloadLowerer {

  /** Strict positive character class pattern for Rule STR-04 fenced string language tags. */
  public static final Pattern FENCE_TAG_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,256}$");

  /** Strict ISO-8601 timestamp pattern for {@code #offset} datetime literals. */
  public static final Pattern DATETIME_OFFSET_PATTERN = Pattern.compile(
      "^\"([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]+)?)?)(Z|[+-][0-9]{2}:[0-9]{2})\"$"
  );

  /** Strict ISO-8601 timestamp pattern for {@code #zoned} civil datetime literals. */
  public static final Pattern DATETIME_ZONED_PATTERN = Pattern.compile(
      "^\"([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]+)?)?)\\[([A-Za-z0-9_\\-+]+(/[A-Za-z0-9_\\-+]+)*)\\]\"$"
  );

  /** Strict ISO-8601 timestamp pattern for {@code #audited} historical compliance datetime literals. */
  public static final Pattern DATETIME_AUDITED_PATTERN = Pattern.compile(
      "^\"([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]+)?)?)(Z|[+-][0-9]{2}:[0-9]{2})\\[([A-Za-z0-9_\\-+]+(/[A-Za-z0-9_\\-+]+)*)\\]\"$"
  );

  private static final Pattern ZONED_WITH_ILLEGAL_OFFSET_PATTERN = Pattern.compile(
      "^\"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]+)?)?(Z|[+-][0-9]{2}:[0-9]{2})\\[.+\\]\"$"
  );

  /**
   * Internal sentinel code marking a guarded recursive nominal schema back-edge inside an immutable
   * {@link StvnSchema} record DAG; unfolded on demand from {@link StvnDefsCatalog} during payload lowering.
   */
  public static final String RECURSIVE_BACK_EDGE_SENTINEL = "INTERNAL_RECURSIVE_NOMINAL_REF";

  /**
   * Functional lookup hook resolving nominal schema FQNIs on demand when traversing guarded recursive types.
   *
   * @since 2.0.0
   */
  @FunctionalInterface
  public interface NominalSchemaResolver {
    /**
     * Resolves a canonical nominal type FQNI to its top-level {@link StvnSchema}.
     *
     * @param fqni the canonical nominal type FQNI
     * @return the resolved {@link StvnSchema}, or empty if not registered
     */
    Optional<StvnSchema> resolve(String fqni);
  }

  /**
   * Functional lookup hook resolving value-sigil constant tokens ({@code #CONST} or
   * {@code #pkg/CONST}) at a specific parse site.
   *
   * @since 2.0.0
   */
  @FunctionalInterface
  public interface ConstantResolver {
    /**
     * Resolves a constant token at the given parse tree site.
     *
     * @param tokenText the raw value-track token text starting with {@code #}
     * @param site      the parse tree context where the token was encountered, or {@code null}
     * @return the resolved {@link StvnDefsCatalog.StvnConstant}, or empty if not declared
     */
    Optional<StvnDefsCatalog.StvnConstant> resolve(String tokenText, @Nullable ParserRuleContext site);
  }

  private StvnPayloadLowerer() {
    // Non-instantiable stateless pass
  }

  /**
   * Lowers a root or standalone payload {@link StvnParser.ValueContext} against {@code targetSchema}
   * using a finalized {@link StvnDefsCatalog}.
   *
   * @param ctx           the parsed ANTLR value context
   * @param targetSchema  the governing normalized {@link StvnSchema}
   * @param catalog       the resolved definitions catalog
   * @param diagnosticBag the accumulator bag for semantic diagnostics
   * @return the lowered {@link StvnValue} tree (or {@link StvnErrorValue} on root failure)
   */
  public static StvnValue lowerValue(
      StvnParser.ValueContext ctx,
      StvnSchema targetSchema,
      StvnDefsCatalog catalog,
      DiagnosticBag diagnosticBag
  ) {
    Objects.requireNonNull(catalog, "catalog must not be null");
    return lowerValue(ctx, targetSchema, (token, site) -> lookupInCatalog(catalog, token), catalog::findType, diagnosticBag);
  }

  /**
   * Lowers a root or standalone payload {@link StvnParser.ValueContext} against {@code targetSchema}
   * using a custom {@link ConstantResolver}.
   *
   * @param ctx              the parsed ANTLR value context
   * @param targetSchema     the governing normalized {@link StvnSchema}
   * @param constantResolver the scoped constant lookup callback
   * @param diagnosticBag    the accumulator bag for semantic diagnostics
   * @return the lowered {@link StvnValue} tree (or {@link StvnErrorValue} on root failure)
   */
  public static StvnValue lowerValue(
      StvnParser.ValueContext ctx,
      StvnSchema targetSchema,
      ConstantResolver constantResolver,
      DiagnosticBag diagnosticBag
  ) {
    return lowerValue(ctx, targetSchema, constantResolver, fqni -> Optional.empty(), diagnosticBag);
  }

  /**
   * Lowers a root or standalone payload {@link StvnParser.ValueContext} against {@code targetSchema}
   * using custom {@link ConstantResolver} and {@link NominalSchemaResolver} hooks.
   *
   * @param ctx                   the parsed ANTLR value context
   * @param targetSchema          the governing normalized {@link StvnSchema}
   * @param constantResolver      the scoped constant lookup callback
   * @param nominalSchemaResolver the nominal schema lookup callback for guarded recursive types
   * @param diagnosticBag         the accumulator bag for semantic diagnostics
   * @return the lowered {@link StvnValue} tree (or {@link StvnErrorValue} on root failure)
   */
  public static StvnValue lowerValue(
      StvnParser.ValueContext ctx,
      StvnSchema targetSchema,
      ConstantResolver constantResolver,
      NominalSchemaResolver nominalSchemaResolver,
      DiagnosticBag diagnosticBag
  ) {
    Objects.requireNonNull(ctx, "ctx must not be null");
    Objects.requireNonNull(targetSchema, "targetSchema must not be null");
    Objects.requireNonNull(constantResolver, "constantResolver must not be null");
    Objects.requireNonNull(nominalSchemaResolver, "nominalSchemaResolver must not be null");
    Objects.requireNonNull(diagnosticBag, "diagnosticBag must not be null");
    return lowerInternal(ctx, targetSchema, constantResolver, nominalSchemaResolver, diagnosticBag, null, false);
  }

  /**
   * Lowers a typed constant definition value inside {@code :defs} against {@code targetSchema}.
   * <p>
   * Constraint violations emitted while lowering constant definitions wrap their cause in
   * {@link MalformedSchemaException} and label the violation with {@code constantDisplayName}.
   *
   * @param constantDisplayName the constant identifier label for diagnostic attribution
   * @param ctx                 the parsed ANTLR value context
   * @param targetSchema        the governing normalized {@link StvnSchema}
   * @param constantResolver    the scoped constant lookup callback
   * @param diagnosticBag       the accumulator bag for semantic diagnostics
   * @return the lowered {@link StvnValue} tree (or {@link StvnErrorValue} on failure)
   */
  public static StvnValue lowerConstantValue(
      String constantDisplayName,
      StvnParser.ValueContext ctx,
      StvnSchema targetSchema,
      ConstantResolver constantResolver,
      DiagnosticBag diagnosticBag
  ) {
    return lowerConstantValue(constantDisplayName, ctx, targetSchema, constantResolver, fqni -> Optional.empty(), diagnosticBag);
  }

  /**
   * Lowers a typed constant definition value inside {@code :defs} against {@code targetSchema}
   * with an explicit {@link NominalSchemaResolver}.
   *
   * @param constantDisplayName   the constant identifier label for diagnostic attribution
   * @param ctx                   the parsed ANTLR value context
   * @param targetSchema          the governing normalized {@link StvnSchema}
   * @param constantResolver      the scoped constant lookup callback
   * @param nominalSchemaResolver the nominal schema lookup callback for guarded recursive types
   * @param diagnosticBag         the accumulator bag for semantic diagnostics
   * @return the lowered {@link StvnValue} tree (or {@link StvnErrorValue} on failure)
   */
  public static StvnValue lowerConstantValue(
      String constantDisplayName,
      StvnParser.ValueContext ctx,
      StvnSchema targetSchema,
      ConstantResolver constantResolver,
      NominalSchemaResolver nominalSchemaResolver,
      DiagnosticBag diagnosticBag
  ) {
    Objects.requireNonNull(constantDisplayName, "constantDisplayName must not be null");
    Objects.requireNonNull(ctx, "ctx must not be null");
    Objects.requireNonNull(targetSchema, "targetSchema must not be null");
    Objects.requireNonNull(constantResolver, "constantResolver must not be null");
    Objects.requireNonNull(nominalSchemaResolver, "nominalSchemaResolver must not be null");
    Objects.requireNonNull(diagnosticBag, "diagnosticBag must not be null");
    return lowerInternal(ctx, targetSchema, constantResolver, nominalSchemaResolver, diagnosticBag, constantDisplayName, true);
  }

  private static Optional<StvnDefsCatalog.StvnConstant> lookupInCatalog(StvnDefsCatalog catalog, String token) {
    Optional<StvnDefsCatalog.StvnConstant> direct = catalog.findConstant(token);
    if (direct.isPresent()) {
      return direct;
    }
    if (token.startsWith(StvnVocabulary.SIGIL_VALUE) && token.contains("/")) {
      int lastSlash = token.lastIndexOf('/');
      String colonForm = StvnVocabulary.SIGIL_TYPIC + token.substring(1, lastSlash + 1)
          + StvnVocabulary.SIGIL_VALUE + token.substring(lastSlash + 1);
      return catalog.findConstant(colonForm);
    }
    return Optional.empty();
  }

  private static StvnSchema unwrapRecursiveBackEdge(StvnSchema schema, NominalSchemaResolver nominalSchemaResolver) {
    StvnSchema current = schema;
    for (int hop = 0; hop < 64; hop++) {
      if (current instanceof StvnSchema.PoisonedSchema poisoned
          && RECURSIVE_BACK_EDGE_SENTINEL.equals(poisoned.primaryDiagnosticCode())
          && poisoned.aliasName().isPresent()) {
        Optional<StvnSchema> resolved = nominalSchemaResolver.resolve(poisoned.aliasName().get());
        if (resolved.isPresent() && resolved.get() != current) {
          current = resolved.get();
          continue;
        }
      }
      break;
    }
    return current;
  }

  private static StvnValue lowerInternal(
      StvnParser.ValueContext ctx,
      StvnSchema targetSchema,
      ConstantResolver constantResolver,
      NominalSchemaResolver nominalSchemaResolver,
      DiagnosticBag diagnosticBag,
      @Nullable String overrideLabel,
      boolean schemaContextMode
  ) {
    StvnSchema effectiveTarget = unwrapRecursiveBackEdge(targetSchema, nominalSchemaResolver);
    if (effectiveTarget instanceof StvnSchema.PoisonedSchema poisoned) {
      return silentPoisonErrorValue(ctx, poisoned);
    }
    if (isMalformedSyntaxContext(ctx)) {
      Span span = Span.ofContext(ctx);
      RuntimeException cause = new MalformedPayloadException(
          "STVN Syntax Error: malformed value expression", span.start(), span.end());
      StvnDiagnostic diag = recordError(
          diagnosticBag, cause.getMessage(), span, cause, "E002", "STVN_SYNTAX_ERROR", schemaContextMode);
      return new StvnErrorValue(effectiveTarget, diag);
    }

    LoweringSession session = new LoweringSession(
        constantResolver, nominalSchemaResolver, diagnosticBag, overrideLabel, schemaContextMode);
    Deque<StreamItem> queue = new ArrayDeque<>();
    queue.addLast(toStreamItem(ctx));
    try {
      StvnValue result = session.evaluateNext(queue, effectiveTarget);
      if (!queue.isEmpty()) {
        StreamItem leftover = queue.removeFirst();
        Span span = leftover.itemSpan();
        String msg = "STVN Syntax Error: unexpected trailing value token '" + leftover.displayText() + "'";
        RuntimeException cause = new MalformedPayloadException(msg, span.start(), span.end());
        recordError(diagnosticBag, msg, span, cause, "E002", "STVN_SYNTAX_ERROR", schemaContextMode);
      }
      return result;
    } catch (LoweringAbortException abort) {
      return new StvnErrorValue(effectiveTarget, abort.diagnostic());
    }
  }

  /**
   * Unescapes a simple double-quoted STVN string literal or extracts and margin-normalizes a
   * Rule STR-04 fenced multi-line string literal.
   *
   * @param rawText        the raw literal token text including delimiters
   * @param preserveIndent {@code true} to retain common leading whitespace on fenced strings
   * @param literalSpan    the source span of the string literal for diagnostic pinning
   * @return the decoded UTF-8 string content
   * @throws LoweringLiteralException if an illegal escape sequence or Rule STR-04 violation is found
   */
  public static String decodeStringLiteral(String rawText, boolean preserveIndent, Span literalSpan) {
    if (rawText.startsWith("\"\"\"")) {
      if (rawText.startsWith("\"\"\"->[")) {
        throw new LoweringLiteralException(
            "Rule STR-04 violation: Fenced string delimiter arrow '->' is prohibited",
            literalSpan,
            "E026",
            DiagnosticBag.ERR_PROHIBITED_FENCE_ARROW,
            new StvnMalformedLiteralException(
                "Rule STR-04 violation: Fenced string delimiter arrow '->' is prohibited",
                literalSpan.start(), literalSpan.end())
        );
      }
      if (!rawText.startsWith("\"\"\"[")) {
        throw new LoweringLiteralException(
            "Rule STR-04 violation: Unfenced block strings are prohibited; multi-line strings must use fenced syntax \"\"\"[TAG]",
            literalSpan,
            "E025",
            DiagnosticBag.ERR_FENCE_DELIMITER_MISMATCH,
            new StvnMalformedLiteralException(
                "Rule STR-04 violation: Unfenced block strings are prohibited; multi-line strings must use fenced syntax \"\"\"[TAG]",
                literalSpan.start(), literalSpan.end())
        );
      }
      int newlineIndex = rawText.indexOf('\n');
      int tagStart = rawText.indexOf('[');
      int tagEnd = rawText.indexOf(']', tagStart);
      if (newlineIndex == -1 || tagStart == -1 || tagEnd == -1 || tagEnd <= tagStart || tagEnd > newlineIndex) {
        throw new LoweringLiteralException(
            "Rule STR-04 violation: Malformed opening fence delimiter",
            literalSpan,
            "E025",
            DiagnosticBag.ERR_FENCE_DELIMITER_MISMATCH,
            new StvnMalformedLiteralException(
                "Rule STR-04 violation: Malformed opening fence delimiter",
                literalSpan.start(), literalSpan.end())
        );
      }
      String openingTag = rawText.substring(tagStart + 1, tagEnd);
      if (!FENCE_TAG_PATTERN.matcher(openingTag).matches()) {
        String msg = "Rule STR-04 violation: Fenced string delimiter tag '" + openingTag
            + "' must match ^[a-zA-Z0-9_-]{1,256}$";
        throw new LoweringLiteralException(
            msg,
            literalSpan,
            "E025",
            DiagnosticBag.ERR_FENCE_DELIMITER_MISMATCH,
            new StvnMalformedLiteralException(msg, literalSpan.start(), literalSpan.end())
        );
      }
      String expectedClosing = "[" + openingTag + "]\"\"\"";
      if (!rawText.endsWith(expectedClosing) || newlineIndex + 1 > rawText.length() - expectedClosing.length()) {
        String msg = "Rule STR-04 violation: Missing or mismatched closing fence delimiter '" + expectedClosing + "'";
        throw new LoweringLiteralException(
            msg,
            literalSpan,
            "E025",
            DiagnosticBag.ERR_FENCE_DELIMITER_MISMATCH,
            new StvnMalformedLiteralException(msg, literalSpan.start(), literalSpan.end())
        );
      }
      String payload = rawText.substring(newlineIndex + 1, rawText.length() - expectedClosing.length());
      String lfNormalized = payload.indexOf('\r') >= 0 ? payload.replace("\r\n", "\n") : payload;
      return preserveIndent ? lfNormalized : trimCommonIndent(lfNormalized);
    }

    if (rawText.length() >= 2 && rawText.startsWith("\"") && rawText.endsWith("\"")) {
      return unescapeSimpleQuotedString(rawText, literalSpan);
    }
    return rawText;
  }

  private static String unescapeSimpleQuotedString(String rawText, Span literalSpan) {
    int end = rawText.length() - 1;
    if (rawText.indexOf('\\') < 0) {
      return rawText.substring(1, end);
    }
    StringBuilder sb = new StringBuilder(end - 1);
    int i = 1;
    while (i < end) {
      char c = rawText.charAt(i);
      if (c != '\\') {
        sb.append(c);
        i++;
        continue;
      }
      if (i + 1 >= end) {
        Span escSpan = literalSpan.subSpan(i, i + 1);
        String msg = "Illegal trailing backslash escape in string literal";
        throw new LoweringLiteralException(
            msg, escSpan, "E002", "STVN_SYNTAX_ERROR",
            new StvnMalformedLiteralException(msg, escSpan.start(), escSpan.end()));
      }
      char next = rawText.charAt(i + 1);
      switch (next) {
        case '"' -> {
          sb.append('"');
          i += 2;
        }
        case '\\' -> {
          sb.append('\\');
          i += 2;
        }
        case '/' -> {
          sb.append('/');
          i += 2;
        }
        case 'b' -> {
          sb.append('\b');
          i += 2;
        }
        case 'f' -> {
          sb.append('\f');
          i += 2;
        }
        case 'n' -> {
          sb.append('\n');
          i += 2;
        }
        case 'r' -> {
          sb.append('\r');
          i += 2;
        }
        case 't' -> {
          sb.append('\t');
          i += 2;
        }
        case 'u' -> {
          if (i + 6 > end) {
            Span escSpan = literalSpan.subSpan(i, Math.min(end, i + 6));
            String msg = "Incomplete Unicode escape sequence in string literal; expected '\\uXXXX'";
            throw new LoweringLiteralException(
                msg, escSpan, "E002", "STVN_SYNTAX_ERROR",
                new StvnMalformedLiteralException(msg, escSpan.start(), escSpan.end()));
          }
          String hex = rawText.substring(i + 2, i + 6);
          int codeUnit = parseFourHexDigits(hex);
          if (codeUnit < 0) {
            Span escSpan = literalSpan.subSpan(i, i + 6);
            String msg = "Invalid hexadecimal digits in Unicode escape '\\u" + hex + "'";
            throw new LoweringLiteralException(
                msg, escSpan, "E002", "STVN_SYNTAX_ERROR",
                new StvnMalformedLiteralException(msg, escSpan.start(), escSpan.end()));
          }
          sb.append((char) codeUnit);
          i += 6;
        }
        default -> {
          Span escSpan = literalSpan.subSpan(i, i + 2);
          String msg = "Illegal escape sequence '\\" + next + "' in simple string literal";
          throw new LoweringLiteralException(
              msg, escSpan, "E002", "STVN_SYNTAX_ERROR",
              new StvnMalformedLiteralException(msg, escSpan.start(), escSpan.end()));
        }
      }
    }
    return sb.toString();
  }

  private static int parseFourHexDigits(String hex) {
    if (hex.length() != 4) {
      return -1;
    }
    int acc = 0;
    for (int j = 0; j < 4; j++) {
      int digit = Character.digit(hex.charAt(j), 16);
      if (digit < 0) {
        return -1;
      }
      acc = (acc << 4) | digit;
    }
    return acc;
  }

  /**
   * Strips the common leading whitespace margin across all non-blank lines of a fenced string body.
   *
   * @param text the raw multi-line fenced string payload
   * @return the baseline-stripped string payload
   */
  public static String trimCommonIndent(String text) {
    if (text.isEmpty()) {
      return text;
    }
    String[] lines = text.split("\n", -1);
    int minIndent = Integer.MAX_VALUE;
    boolean hasNonEmptyLine = false;

    for (String line : lines) {
      if (line.trim().isEmpty()) {
        continue;
      }
      hasNonEmptyLine = true;
      int indent = 0;
      while (indent < line.length() && Character.isWhitespace(line.charAt(indent))) {
        indent++;
      }
      minIndent = Math.min(minIndent, indent);
    }

    if (!hasNonEmptyLine) {
      minIndent = 0;
    } else if (minIndent == 0) {
      return text;
    }

    StringBuilder sb = new StringBuilder(text.length());
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      sb.append(line.trim().isEmpty() ? "" : line.substring(minIndent));
      if (i < lines.length - 1) {
        sb.append('\n');
      }
    }
    return sb.toString();
  }

  /**
   * Checks whether a raw integer literal token represents a prohibited negative zero
   * ({@code -0}, {@code -0x0}, {@code -0b0}, {@code -0o0}, or multi-zero radix equivalents).
   *
   * @param rawText the raw integer literal token text
   * @return {@code true} if the literal represents negative zero
   */
  public static boolean isNegativeZeroInteger(String rawText) {
    if (!rawText.startsWith("-")) {
      return false;
    }
    String s = rawText.toLowerCase(Locale.ROOT);
    if (s.equals("-0") || s.equals("-0x0") || s.equals("-0b0") || s.equals("-0o0")) {
      return true;
    }
    if ((s.startsWith("-0x") || s.startsWith("-0b") || s.startsWith("-0o")) && s.length() > 3) {
      for (int i = 3; i < s.length(); i++) {
        if (s.charAt(i) != '0') {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  /**
   * Parses an STVN integer literal (decimal, {@code 0x} hex, {@code 0b} binary, or {@code 0o} octal)
   * into an arbitrary-precision {@link BigInteger}.
   *
   * @param rawText the raw integer literal token text
   * @return the parsed {@link BigInteger}
   */
  public static BigInteger parseIntegerLiteral(String rawText) {
    boolean negative = rawText.startsWith("-");
    String abs = negative ? rawText.substring(1) : rawText;
    int radix = 10;
    if (abs.length() >= 3 && abs.charAt(0) == '0') {
      char prefix = abs.charAt(1);
      radix = switch (prefix) {
        case 'x', 'X' -> 16;
        case 'b', 'B' -> 2;
        case 'o', 'O' -> 8;
        default -> 10;
      };
    }
    String digits = radix == 10 ? abs : abs.substring(2);
    BigInteger magnitude = new BigInteger(digits, radix);
    return negative ? magnitude.negate() : magnitude;
  }

  /**
   * Parses a 1-based union branch tag token (such as {@code #1} or {@code #12}) into a positive
   * {@link BigInteger}.
   *
   * @param rawTag the raw union tag token text
   * @return the 1-based branch index as a {@link BigInteger}
   */
  public static BigInteger parseUnionBranchIndex(String rawTag) {
    if (rawTag.length() < 2 || !rawTag.startsWith(StvnVocabulary.SIGIL_VALUE)) {
      throw new IllegalArgumentException("Invalid union branch tag: " + rawTag);
    }
    BigInteger index = new BigInteger(rawTag.substring(1), 10);
    if (index.compareTo(BigInteger.ONE) < 0) {
      throw new IllegalArgumentException("Union branch index must be >= 1: " + rawTag);
    }
    return index;
  }

  /**
   * Compares two UTF-8 strings using unsigned byte-lexicographical ordering.
   *
   * @param left  the left string operand
   * @param right the right string operand
   * @return negative if {@code left < right}, zero if equal, positive if {@code left > right}
   */
  public static int compareUtf8Lexicographical(String left, String right) {
    return Arrays.compareUnsigned(
        left.getBytes(StandardCharsets.UTF_8),
        right.getBytes(StandardCharsets.UTF_8)
    );
  }

  /**
   * Parses a double-quoted ISO-8601 timestamp bound literal into a comparable physical {@link Instant}
   * according to the governing {@link DateTimeMode}.
   *
   * @param rawQuotedLiteral the double-quoted timestamp literal text
   * @param mode             the governing {@link DateTimeMode}
   * @return the resolved physical {@link Instant}
   */
  public static Instant parseDateTimeBoundInstant(String rawQuotedLiteral, DateTimeMode mode) {
    return switch (mode) {
      case OFFSET -> {
        var matcher = DATETIME_OFFSET_PATTERN.matcher(rawQuotedLiteral);
        if (!matcher.matches()) {
          throw new IllegalArgumentException(
              "Invalid {#offset} " + StvnVocabulary.TYPE_DATE_TIME + " bound literal: " + rawQuotedLiteral);
        }
        String iso = rawQuotedLiteral.substring(1, rawQuotedLiteral.length() - 1);
        yield OffsetDateTime.parse(iso).toInstant();
      }
      case ZONED -> {
        if (ZONED_WITH_ILLEGAL_OFFSET_PATTERN.matcher(rawQuotedLiteral).matches()) {
          throw new IllegalArgumentException(
              "Explicit UTC offset is prohibited in {#zoned} " + StvnVocabulary.TYPE_DATE_TIME + " bound: "
                  + rawQuotedLiteral);
        }
        var matcher = DATETIME_ZONED_PATTERN.matcher(rawQuotedLiteral);
        if (!matcher.matches()) {
          throw new IllegalArgumentException(
              "Invalid {#zoned} " + StvnVocabulary.TYPE_DATE_TIME + " bound literal: " + rawQuotedLiteral);
        }
        LocalDateTime ldt = LocalDateTime.parse(matcher.group(1));
        ZoneId zoneId = ZoneId.of(matcher.group(2));
        if (zoneId.getRules().getValidOffsets(ldt).isEmpty()) {
          throw new IllegalArgumentException(
              "Civil time '" + ldt + "' falls in a DST spring-forward gap in zone '" + zoneId + "'");
        }
        yield ldt.atZone(zoneId).toInstant();
      }
      case AUDITED -> {
        var matcher = DATETIME_AUDITED_PATTERN.matcher(rawQuotedLiteral);
        if (!matcher.matches()) {
          throw new IllegalArgumentException(
              "Invalid {#audited} " + StvnVocabulary.TYPE_DATE_TIME + " bound literal: " + rawQuotedLiteral);
        }
        OffsetDateTime odt = OffsetDateTime.parse(matcher.group(1) + matcher.group(2));
        ZoneId zoneId = ZoneId.of(matcher.group(3));
        LocalDateTime ldt = odt.toLocalDateTime();
        var validOffsets = zoneId.getRules().getValidOffsets(ldt);
        if (validOffsets.isEmpty()) {
          throw new IllegalArgumentException(
              "Civil time '" + ldt + "' falls in a DST spring-forward gap in zone '" + zoneId + "'");
        }
        if (!validOffsets.contains(odt.getOffset())) {
          throw new IllegalArgumentException(
              "Audited offset '" + odt.getOffset() + "' disagrees with IANA rules " + validOffsets
                  + " for '" + ldt + "' in zone '" + zoneId + "'");
        }
        yield odt.toInstant();
      }
    };
  }

  /**
   * Evaluates whether a signed integer fits within a two's-complement signed {@code bitWidth}
   * in {@code O(1)} time without materializing {@code 2^bitWidth}.
   *
   * @param candidate the integer candidate
   * @param bitWidth  the positive signed bit-width (&gt;= 1)
   * @return {@code true} if {@code candidate} fits within {@code [-2^(bitWidth-1), 2^(bitWidth-1))}
   */
  public static boolean fitsSignedBitWidth(BigInteger candidate, BigInteger bitWidth) {
    BigInteger payloadBits = bitWidth.subtract(BigInteger.ONE);
    if (candidate.signum() >= 0) {
      return BigInteger.valueOf(candidate.bitLength()).compareTo(payloadBits) <= 0;
    }
    return BigInteger.valueOf(candidate.add(BigInteger.ONE).negate().bitLength()).compareTo(payloadBits) <= 0;
  }

  /**
   * Source coordinate span {@code [start, end)} paired with 1-based line and 0-based column.
   *
   * @param start  0-based inclusive start character offset
   * @param end    0-based exclusive end character offset
   * @param line   1-based line number
   * @param column 0-based column offset
   * @since 2.0.0
   */
  public record Span(int start, int end, int line, int column) {

    /**
     * Extracts a {@link Span} from an ANTLR {@link ParserRuleContext}.
     *
     * @param ctx the parse rule context, or {@code null}
     * @return the computed coordinate span
     */
    public static Span ofContext(@Nullable ParserRuleContext ctx) {
      if (ctx == null || ctx.getStart() == null) {
        return new Span(0, 0, 1, 0);
      }
      Token startTok = ctx.getStart();
      Token stopTok = ctx.getStop() != null ? ctx.getStop() : startTok;
      int s = Math.max(0, startTok.getStartIndex());
      int e = Math.max(s + 1, stopTok.getStopIndex() + 1);
      return new Span(s, e, startTok.getLine(), startTok.getCharPositionInLine());
    }

    /**
     * Extracts a {@link Span} from a single ANTLR {@link Token}.
     *
     * @param tok the token, or {@code null}
     * @return the computed coordinate span
     */
    public static Span ofToken(@Nullable Token tok) {
      if (tok == null) {
        return new Span(0, 0, 1, 0);
      }
      int s = Math.max(0, tok.getStartIndex());
      int e = tok.getType() == Token.EOF ? s : Math.max(s + 1, tok.getStopIndex() + 1);
      return new Span(s, e, tok.getLine(), tok.getCharPositionInLine());
    }

    /**
     * Computes a sub-span relative to {@link #start()} on the same starting line.
     *
     * @param relStart relative start offset from {@link #start()}
     * @param relEnd   relative end offset from {@link #start()}
     * @return the sub-span
     */
    public Span subSpan(int relStart, int relEnd) {
      int s = start + Math.max(0, relStart);
      int e = Math.max(s + 1, start + relEnd);
      return new Span(s, e, line, Math.max(0, column + relStart));
    }
  }

  /**
   * Unchecked carrier exception thrown by {@link #decodeStringLiteral} on lexical escape or fence errors.
   *
   * @since 2.0.0
   */
  public static final class LoweringLiteralException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient Span span;
    /** Canonical STVN 2.0.0 diagnostic code ({@code E001}–{@code E040}). */
    private final String canonicalCode;
    /** Symbolic STVN 2.0.0 diagnostic identifier. */
    private final String symbolicId;

    LoweringLiteralException(
        String message,
        Span span,
        String canonicalCode,
        String symbolicId,
        Throwable cause
    ) {
      super(message, cause);
      this.span = span;
      this.canonicalCode = canonicalCode;
      this.symbolicId = symbolicId;
    }

    /**
     * Returns the offending coordinate span.
     *
     * @return the coordinate span
     */
    public Span span() {
      return span;
    }

    /**
     * Returns the canonical diagnostic code ({@code E001}–{@code E040}).
     *
     * @return the canonical code
     */
    public String canonicalCode() {
      return canonicalCode;
    }

    /**
     * Returns the symbolic diagnostic identifier.
     *
     * @return the symbolic identifier
     */
    public String symbolicId() {
      return symbolicId;
    }
  }

  private static final class LoweringAbortException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient StvnDiagnostic diagnostic;

    LoweringAbortException(StvnDiagnostic diagnostic) {
      super(diagnostic.message(), diagnostic.cause());
      this.diagnostic = diagnostic;
    }

    StvnDiagnostic diagnostic() {
      return diagnostic;
    }
  }

  private sealed interface StreamItem permits
      OptionTagItem, EitherTagItem, UnionTagItem, KeywordTokenItem, BooleanLiteralItem, AtomicValueItem {
    @Nullable ParserRuleContext sourceCtx();

    Span itemSpan();

    Span tagSpan();

    String displayText();
  }

  private record OptionTagItem(
      Token tagToken,
      StvnParser.@Nullable ValueContext childValue,
      StvnParser.ExplicitOptionValueContext sourceCtx
  ) implements StreamItem {
    @Override
    public Span itemSpan() {
      return Span.ofContext(sourceCtx);
    }

    @Override
    public Span tagSpan() {
      return Span.ofToken(tagToken);
    }

    @Override
    public String displayText() {
      return tagToken.getText();
    }
  }

  private record EitherTagItem(
      Token tagToken,
      StvnParser.@Nullable ValueContext childValue,
      StvnParser.ExplicitEitherValueContext sourceCtx
  ) implements StreamItem {
    @Override
    public Span itemSpan() {
      return Span.ofContext(sourceCtx);
    }

    @Override
    public Span tagSpan() {
      return Span.ofToken(tagToken);
    }

    @Override
    public String displayText() {
      return tagToken.getText();
    }
  }

  private record UnionTagItem(
      Token tagToken,
      StvnParser.@Nullable ValueContext childValue,
      StvnParser.ExplicitUnionValueContext sourceCtx
  ) implements StreamItem {
    @Override
    public Span itemSpan() {
      return Span.ofContext(sourceCtx);
    }

    @Override
    public Span tagSpan() {
      return Span.ofToken(tagToken);
    }

    @Override
    public String displayText() {
      return tagToken.getText();
    }
  }

  private record KeywordTokenItem(
      String tokenText,
      StvnParser.ValueKeywordContext sourceCtx
  ) implements StreamItem {
    @Override
    public Span itemSpan() {
      return Span.ofContext(sourceCtx);
    }

    @Override
    public Span tagSpan() {
      return Span.ofContext(sourceCtx);
    }

    @Override
    public String displayText() {
      return tokenText;
    }
  }

  private record BooleanLiteralItem(
      String tokenText,
      StvnParser.BooleanLiteralContext sourceCtx
  ) implements StreamItem {
    @Override
    public Span itemSpan() {
      return Span.ofContext(sourceCtx);
    }

    @Override
    public Span tagSpan() {
      return Span.ofContext(sourceCtx);
    }

    @Override
    public String displayText() {
      return tokenText;
    }
  }

  private record AtomicValueItem(StvnParser.ValueContext ctx) implements StreamItem {
    @Override
    public ParserRuleContext sourceCtx() {
      return ctx;
    }

    @Override
    public Span itemSpan() {
      return Span.ofContext(ctx);
    }

    @Override
    public Span tagSpan() {
      return Span.ofContext(ctx);
    }

    @Override
    public String displayText() {
      return ctx.getText();
    }
  }

  private static StreamItem toStreamItem(StvnParser.ValueContext ctx) {
    if (ctx.explicitOptionValue() != null) {
      var opt = ctx.explicitOptionValue();
      return new OptionTagItem(opt.getStart(), opt.value(), opt);
    }
    if (ctx.explicitEitherValue() != null) {
      var either = ctx.explicitEitherValue();
      return new EitherTagItem(either.getStart(), either.value(), either);
    }
    if (ctx.explicitUnionValue() != null) {
      var union = ctx.explicitUnionValue();
      return new UnionTagItem(union.UNION_TAG_PREFIX().getSymbol(), union.value(), union);
    }
    if (ctx.valueKeyword() != null) {
      return new KeywordTokenItem(ctx.valueKeyword().getText(), ctx.valueKeyword());
    }
    if (ctx.booleanLiteral() != null) {
      return new BooleanLiteralItem(ctx.booleanLiteral().getText(), ctx.booleanLiteral());
    }
    return new AtomicValueItem(ctx);
  }

  private static boolean isMalformedSyntaxContext(@Nullable ParserRuleContext ctx) {
    if (ctx == null) {
      return true;
    }
    if (ctx.exception != null) {
      return true;
    }
    for (int i = 0; i < ctx.getChildCount(); i++) {
      var child = ctx.getChild(i);
      if (child instanceof org.antlr.v4.runtime.tree.ErrorNode) {
        return true;
      }
      if (child instanceof ParserRuleContext prc && !(prc instanceof StvnParser.ValueContext)) {
        if (prc.exception != null) {
          return true;
        }
      }
    }
    if (ctx instanceof StvnParser.ValueContext vc) {
      return vc.explicitOptionValue() == null
          && vc.explicitEitherValue() == null
          && vc.explicitUnionValue() == null
          && vc.booleanLiteral() == null
          && vc.integerLiteral() == null
          && vc.floatLiteral() == null
          && vc.stringLiteral() == null
          && vc.valueKeyword() == null
          && vc.collectionValue() == null;
    }
    return false;
  }

  private static StvnErrorValue silentPoisonErrorValue(ParserRuleContext ctx, StvnSchema.PoisonedSchema poisoned) {
    Span span = Span.ofContext(ctx);
    String code = poisoned.primaryDiagnosticCode();
    Optional<String> canonical = code.startsWith("E") ? Optional.of(code) : DiagnosticBag.resolveCanonicalCode(code);
    Optional<String> symbolic = code.startsWith("E") && code.length() == 4 ? Optional.empty() : Optional.of(code);
    StvnDiagnostic synthetic = new StvnDiagnostic(
        "Poisoned schema sentinel",
        DiagnosticSeverity.ERROR,
        span.line(),
        span.column(),
        span.start(),
        span.end(),
        null,
        canonical,
        symbolic
    );
    return new StvnErrorValue(poisoned, synthetic);
  }

  private static StvnDiagnostic recordError(
      DiagnosticBag bag,
      String message,
      Span span,
      @Nullable Throwable cause,
      String canonicalCode,
      String symbolicId,
      boolean schemaContextMode
  ) {
    Throwable effectiveCause = cause;
    if (schemaContextMode && (cause == null || cause instanceof MalformedPayloadException)
        && !(cause instanceof StvnIntegerOverflowException)) {
      effectiveCause = new MalformedSchemaException(message, span.start(), span.end(), cause);
    } else if (effectiveCause == null) {
      effectiveCause = new MalformedPayloadException(message, span.start(), span.end());
    }
    StvnDiagnostic diag = new StvnDiagnostic(
        message,
        DiagnosticSeverity.ERROR,
        span.line(),
        span.column(),
        span.start(),
        span.end(),
        effectiveCause,
        Optional.of(canonicalCode),
        Optional.of(symbolicId)
    );
    bag.add(diag);
    return diag;
  }

  private static String schemaLabel(StvnSchema schema, @Nullable String overrideLabel) {
    if (overrideLabel != null && !overrideLabel.isEmpty()) {
      return overrideLabel;
    }
    if (schema.aliasName().isPresent()) {
      return schema.aliasName().get();
    }
    return baseTypeName(schema);
  }

  static String describeSchemaStructuralKind(StvnSchema schema) {
    return switch (schema) {
      case StvnSchema.BooleanSchema ignored -> "scalar " + StvnVocabulary.TYPE_BOOLEAN;
      case StvnSchema.IntSchema ignored -> "scalar " + StvnVocabulary.TYPE_INT;
      case StvnSchema.FloatSchema ignored -> "scalar " + StvnVocabulary.TYPE_FLOAT;
      case StvnSchema.StringSchema ignored -> "scalar " + StvnVocabulary.TYPE_STRING;
      case StvnSchema.TimeEpochSchema ignored -> "scalar " + StvnVocabulary.TYPE_TIME_EPOCH;
      case StvnSchema.DateTimeSchema ignored -> "scalar " + StvnVocabulary.TYPE_DATE_TIME;
      case StvnSchema.EnumSchema ignored -> "enum " + StvnVocabulary.TYPE_ENUM;
      case StvnSchema.EnumSubsetSchema ignored -> "enum " + StvnVocabulary.TYPE_ENUM;
      case StvnSchema.TupleSchema ignored -> "product constructor " + StvnVocabulary.TYPE_TUPLE;
      case StvnSchema.OptionSchema ignored -> "sum constructor " + StvnVocabulary.TYPE_OPTION;
      case StvnSchema.EitherSchema ignored -> "sum constructor " + StvnVocabulary.TYPE_EITHER;
      case StvnSchema.UnionSchema ignored -> "sum constructor " + StvnVocabulary.TYPE_UNION;
      case StvnSchema.SeqSchema ignored -> "collection constructor " + StvnVocabulary.TYPE_SEQ;
      case StvnSchema.SetSchema ignored -> "collection constructor " + StvnVocabulary.TYPE_SET;
      case StvnSchema.MapSchema ignored -> "collection constructor " + StvnVocabulary.TYPE_MAP;
      case StvnSchema.PoisonedSchema p -> p.aliasName().orElse("<poisoned>");
    };
  }

  static String baseTypeName(StvnSchema schema) {
    return switch (schema) {
      case StvnSchema.BooleanSchema ignored -> StvnVocabulary.TYPE_BOOLEAN;
      case StvnSchema.IntSchema ignored -> StvnVocabulary.TYPE_INT;
      case StvnSchema.FloatSchema ignored -> StvnVocabulary.TYPE_FLOAT;
      case StvnSchema.StringSchema ignored -> StvnVocabulary.TYPE_STRING;
      case StvnSchema.TimeEpochSchema ignored -> StvnVocabulary.TYPE_TIME_EPOCH;
      case StvnSchema.DateTimeSchema ignored -> StvnVocabulary.TYPE_DATE_TIME;
      case StvnSchema.EnumSchema ignored -> StvnVocabulary.TYPE_ENUM;
      case StvnSchema.EnumSubsetSchema ignored -> StvnVocabulary.TYPE_ENUM;
      case StvnSchema.TupleSchema ignored -> StvnVocabulary.TYPE_TUPLE;
      case StvnSchema.OptionSchema ignored -> StvnVocabulary.TYPE_OPTION;
      case StvnSchema.EitherSchema ignored -> StvnVocabulary.TYPE_EITHER;
      case StvnSchema.UnionSchema ignored -> StvnVocabulary.TYPE_UNION;
      case StvnSchema.SeqSchema ignored -> StvnVocabulary.TYPE_SEQ;
      case StvnSchema.SetSchema ignored -> StvnVocabulary.TYPE_SET;
      case StvnSchema.MapSchema ignored -> StvnVocabulary.TYPE_MAP;
      case StvnSchema.PoisonedSchema p -> p.aliasName().orElse("<poisoned>");
    };
  }

  private static boolean containsErrorValue(StvnValue value) {
    return switch (value) {
      case StvnErrorValue ignored -> true;
      case StvnValue.StvnTuple t -> t.elements().stream().anyMatch(StvnPayloadLowerer::containsErrorValue);
      case StvnValue.StvnSeq s -> s.elements().stream().anyMatch(StvnPayloadLowerer::containsErrorValue);
      case StvnValue.StvnSet s -> s.elements().stream().anyMatch(StvnPayloadLowerer::containsErrorValue);
      case StvnValue.StvnMap m -> m.entries().stream()
          .anyMatch(e -> containsErrorValue(e.key()) || containsErrorValue(e.value()));
      case StvnValue.StvnOption o -> o.value().map(StvnPayloadLowerer::containsErrorValue).orElse(false);
      case StvnValue.StvnEither e -> containsErrorValue(e.value());
      case StvnValue.StvnUnion u -> containsErrorValue(u.value());
      default -> false;
    };
  }

  /**
   * Computes a normalized structural equality key for an {@link StvnValue} node in accordance with
   * STVN 2.0.0 set/map key uniqueness and invertible map value uniqueness semantics.
   *
   * @param value the lowered pure-IR value
   * @return an immutable value-based key implementing {@link Object#equals} and {@link Object#hashCode}
   */
  public static Object canonicalEqualityKey(StvnValue value) {
    return switch (value) {
      case StvnValue.StvnBoolean b -> Boolean.valueOf(b.value());
      case StvnValue.StvnInt i -> i.value();
      case StvnValue.StvnFloat f -> switch (f.specialKind()) {
        case FINITE -> f.negativeZero()
            ? List.of("NEG_ZERO")
            : List.of("FINITE", f.decimalValue().signum() == 0
                ? BigDecimal.ZERO
                : f.decimalValue().stripTrailingZeros());
        case POSITIVE_INFINITY -> List.of("POS_INF");
        case NEGATIVE_INFINITY -> List.of("NEG_INF");
        case NAN -> List.of("NAN");
      };
      case StvnValue.StvnString s -> s.value();
      case StvnValue.StvnTimeEpoch te -> List.of(te.scale(), te.epochTicks());
      case StvnValue.StvnDateTime dt -> switch (dt.mode()) {
        case OFFSET -> List.of(
            DateTimeMode.OFFSET,
            OffsetDateTime.of(dt.localWallClock(), dt.offset().orElse(ZoneOffset.UTC)).toInstant());
        case ZONED -> List.of(DateTimeMode.ZONED, dt.localWallClock(), dt.zone().orElseThrow().getId());
        case AUDITED -> List.of(
            DateTimeMode.AUDITED,
            OffsetDateTime.of(dt.localWallClock(), dt.offset().orElse(ZoneOffset.UTC)).toInstant(),
            dt.zone().orElseThrow().getId());
      };
      case StvnValue.StvnEnum en -> en.rootOrdinal();
      case StvnValue.StvnTuple t -> t.elements().stream().map(StvnPayloadLowerer::canonicalEqualityKey).toList();
      case StvnValue.StvnOption o -> o.value().map(v -> List.of("SOME", canonicalEqualityKey(v))).orElse(List.of("NONE"));
      case StvnValue.StvnEither e -> List.of(e.branch(), canonicalEqualityKey(e.value()));
      case StvnValue.StvnUnion u -> List.of(u.oneBasedBranchIndex(), canonicalEqualityKey(u.value()));
      case StvnValue.StvnSeq s -> s.elements().stream().map(StvnPayloadLowerer::canonicalEqualityKey).toList();
      case StvnValue.StvnSet s -> {
        Set<Object> setKeys = new LinkedHashSet<>();
        for (StvnValue el : s.elements()) {
          setKeys.add(canonicalEqualityKey(el));
        }
        yield setKeys;
      }
      case StvnValue.StvnMap m -> m.entries().stream()
          .map(e -> List.of(canonicalEqualityKey(e.key()), canonicalEqualityKey(e.value())))
          .toList();
      case StvnErrorValue err -> err;
    };
  }

  private static final class LoweringSession {
    private final ConstantResolver constantResolver;
    private final NominalSchemaResolver nominalSchemaResolver;
    private final DiagnosticBag diagnosticBag;
    private final @Nullable String overrideLabel;
    private final boolean schemaContextMode;

    LoweringSession(
        ConstantResolver constantResolver,
        NominalSchemaResolver nominalSchemaResolver,
        DiagnosticBag diagnosticBag,
        @Nullable String overrideLabel,
        boolean schemaContextMode
    ) {
      this.constantResolver = constantResolver;
      this.nominalSchemaResolver = nominalSchemaResolver;
      this.diagnosticBag = diagnosticBag;
      this.overrideLabel = overrideLabel;
      this.schemaContextMode = schemaContextMode;
    }

    LoweringSession withBag(DiagnosticBag probeBag) {
      return new LoweringSession(constantResolver, nominalSchemaResolver, probeBag, overrideLabel, schemaContextMode);
    }

    LoweringAbortException abort(
        String message,
        Span span,
        @Nullable Throwable cause,
        String canonicalCode,
        String symbolicId
    ) {
      StvnDiagnostic diag = recordError(
          diagnosticBag, message, span, cause, canonicalCode, symbolicId, schemaContextMode);
      return new LoweringAbortException(diag);
    }

    StvnValue evaluateNext(Deque<StreamItem> queue, StvnSchema rawSchema) {
      StvnSchema schema = unwrapRecursiveBackEdge(rawSchema, nominalSchemaResolver);
      if (schema instanceof StvnSchema.PoisonedSchema poisoned) {
        if (!queue.isEmpty()) {
          StreamItem item = queue.removeFirst();
          return silentPoisonErrorValue(item.sourceCtx() != null ? item.sourceCtx() : new ParserRuleContext(), poisoned);
        }
        return silentPoisonErrorValue(new ParserRuleContext(), poisoned);
      }

      if (queue.isEmpty()) {
        throw abort(
            "Unexpected end of value stream while lowering " + schemaLabel(schema, overrideLabel),
            new Span(0, 0, 1, 0),
            null,
            "E028",
            DiagnosticBag.ERR_TYPE_MISMATCH
        );
      }

      StreamItem peek = queue.getFirst();
      if (peek instanceof AtomicValueItem atom && isMalformedSyntaxContext(atom.ctx())) {
        queue.removeFirst();
        Span span = atom.itemSpan();
        throw abort(
            "STVN Syntax Error: malformed value expression",
            span,
            new MalformedPayloadException("STVN Syntax Error: malformed value expression", span.start(), span.end()),
            "E002",
            "STVN_SYNTAX_ERROR"
        );
      }

      // Rule C: Control Keyword Clash Check before evaluating Option / Either
      checkControlKeywordClash(peek, schema);

      // Dimension 1: Enum / EnumSubset takes precedence over Option/Either/Union control tokens
      if (schema instanceof StvnSchema.EnumSchema || schema instanceof StvnSchema.EnumSubsetSchema) {
        return lowerEnumFromQueue(queue, schema);
      }

      // Algebraic sum types (:Option, :Either, :Union)
      if (schema instanceof StvnSchema.OptionSchema optionSchema) {
        return lowerOptionFromQueue(queue, optionSchema);
      }
      if (schema instanceof StvnSchema.EitherSchema eitherSchema) {
        return lowerEitherFromQueue(queue, eitherSchema);
      }
      if (schema instanceof StvnSchema.UnionSchema unionSchema) {
        return lowerUnionFromQueue(queue, unionSchema);
      }

      // Non-sum target schemas (:Boolean, :Int, :Float, :String, :TimeEpoch, :DateTime, :Tuple, :Seq, :Set, :Map)
      StreamItem item = queue.removeFirst();

      // Check if an explicit Option/Either/Union tag token actually names a declared #CONST
      if (item instanceof OptionTagItem optTag) {
        var constOpt = constantResolver.resolve(optTag.tagToken().getText(), optTag.sourceCtx());
        if (constOpt.isPresent()) {
          if (optTag.childValue() != null) {
            queue.addFirst(toStreamItem(optTag.childValue()));
          }
          return adaptConstantToSchema(constOpt.get(), optTag.tagToken().getText(), schema, optTag.tagSpan());
        }
        throw abort(
            "Unexpected Option tag '" + optTag.tagToken().getText() + "'; target schema is "
                + schemaLabel(schema, overrideLabel),
            optTag.itemSpan(),
            new MalformedPayloadException(
                "Unexpected Option tag '" + optTag.tagToken().getText() + "'; target schema is "
                    + schemaLabel(schema, overrideLabel),
                optTag.itemSpan().start(), optTag.itemSpan().end()),
            "E028",
            DiagnosticBag.ERR_TYPE_MISMATCH
        );
      }
      if (item instanceof EitherTagItem eitherTag) {
        var constOpt = constantResolver.resolve(eitherTag.tagToken().getText(), eitherTag.sourceCtx());
        if (constOpt.isPresent()) {
          if (eitherTag.childValue() != null) {
            queue.addFirst(toStreamItem(eitherTag.childValue()));
          }
          return adaptConstantToSchema(constOpt.get(), eitherTag.tagToken().getText(), schema, eitherTag.tagSpan());
        }
        throw abort(
            "Unexpected Either tag '" + eitherTag.tagToken().getText() + "'; target schema is "
                + schemaLabel(schema, overrideLabel),
            eitherTag.itemSpan(),
            new MalformedPayloadException(
                "Unexpected Either tag '" + eitherTag.tagToken().getText() + "'; target schema is "
                    + schemaLabel(schema, overrideLabel),
                eitherTag.itemSpan().start(), eitherTag.itemSpan().end()),
            "E028",
            DiagnosticBag.ERR_TYPE_MISMATCH
        );
      }
      if (item instanceof UnionTagItem unionTag) {
        var constOpt = constantResolver.resolve(unionTag.tagToken().getText(), unionTag.sourceCtx());
        if (constOpt.isPresent()) {
          if (unionTag.childValue() != null) {
            queue.addFirst(toStreamItem(unionTag.childValue()));
          }
          return adaptConstantToSchema(constOpt.get(), unionTag.tagToken().getText(), schema, unionTag.tagSpan());
        }
        throw abort(
            "Unexpected Union tag '" + unionTag.tagToken().getText() + "'; target schema is "
                + schemaLabel(schema, overrideLabel),
            unionTag.itemSpan(),
            new MalformedPayloadException(
                "Unexpected Union tag '" + unionTag.tagToken().getText() + "'; target schema is "
                    + schemaLabel(schema, overrideLabel),
                unionTag.itemSpan().start(), unionTag.itemSpan().end()),
            "E028",
            DiagnosticBag.ERR_TYPE_MISMATCH
        );
      }

      if (item instanceof BooleanLiteralItem boolItem) {
        if (schema instanceof StvnSchema.BooleanSchema boolSchema) {
          boolean truth = boolItem.tokenText().equals(StvnVocabulary.VAL_TRUE)
              || boolItem.tokenText().equals(StvnVocabulary.VAL_TRUE_SHORT);
          return new StvnValue.StvnBoolean(boolSchema, truth);
        }
        return resolveConstantOrFail(boolItem.tokenText(), boolItem.sourceCtx(), schema, boolItem.itemSpan());
      }

      if (item instanceof KeywordTokenItem kwItem) {
        return resolveConstantOrFail(kwItem.tokenText(), kwItem.sourceCtx(), schema, kwItem.itemSpan());
      }

      AtomicValueItem atom = (AtomicValueItem) item;
      return lowerAtomicValue(atom.ctx(), schema);
    }

    private void checkControlKeywordClash(StreamItem peek, StvnSchema schema) {
      String bareControlToken = null;
      Span span = peek.tagSpan();
      if (peek instanceof OptionTagItem opt && opt.childValue() == null) {
        bareControlToken = opt.tagToken().getText();
      } else if (peek instanceof EitherTagItem either && either.childValue() == null) {
        bareControlToken = either.tagToken().getText();
      } else if (peek instanceof KeywordTokenItem kw) {
        bareControlToken = kw.tokenText();
      }
      if (bareControlToken == null) {
        return;
      }

      if (schema instanceof StvnSchema.OptionSchema optSchema
          && StvnVocabulary.OPTION_KEYWORDS.contains(bareControlToken)
          && acceptsEnumVariantTransitively(optSchema.valueSchema(), bareControlToken)) {
        String msg = "Rule C violation: Ambiguous keyword clash; '" + bareControlToken
            + "' is both an " + StvnVocabulary.TYPE_OPTION + " control tag and an active inner "
            + StvnVocabulary.TYPE_ENUM + " variant";
        throw abort(msg, span, new IllegalArgumentException(msg), "E024", DiagnosticBag.ERR_AMBIGUOUS_SUM_INFERENCE);
      }
      if (schema instanceof StvnSchema.EitherSchema eitherSchema
          && StvnVocabulary.EITHER_KEYWORDS.contains(bareControlToken)
          && (acceptsEnumVariantTransitively(eitherSchema.leftSchema(), bareControlToken)
              || acceptsEnumVariantTransitively(eitherSchema.rightSchema(), bareControlToken))) {
        String msg = "Rule C violation: Ambiguous keyword clash; '" + bareControlToken
            + "' is both an " + StvnVocabulary.TYPE_EITHER + " control tag and an active branch "
            + StvnVocabulary.TYPE_ENUM + " variant";
        throw abort(msg, span, new IllegalArgumentException(msg), "E024", DiagnosticBag.ERR_AMBIGUOUS_SUM_INFERENCE);
      }
    }

    private static boolean acceptsEnumVariantTransitively(StvnSchema schema, String token) {
      return switch (schema) {
        case StvnSchema.EnumSchema en -> en.rootVariants().contains(token);
        case StvnSchema.EnumSubsetSchema sub -> sub.activeVariants().contains(token);
        case StvnSchema.OptionSchema opt -> acceptsEnumVariantTransitively(opt.valueSchema(), token);
        case StvnSchema.EitherSchema e -> acceptsEnumVariantTransitively(e.leftSchema(), token)
            || acceptsEnumVariantTransitively(e.rightSchema(), token);
        case StvnSchema.UnionSchema u -> u.branchSchemas().stream()
            .anyMatch(b -> acceptsEnumVariantTransitively(b, token));
        default -> false;
      };
    }

    private StvnValue lowerEnumFromQueue(Deque<StreamItem> queue, StvnSchema schema) {
      StreamItem item = queue.removeFirst();
      String tokenText;
      Span span;
      ParserRuleContext site;
      switch (item) {
        case OptionTagItem optTag -> {
          if (optTag.childValue() != null) {
            queue.addFirst(toStreamItem(optTag.childValue()));
          }
          tokenText = optTag.tagToken().getText();
          span = optTag.tagSpan();
          site = optTag.sourceCtx();
        }
        case EitherTagItem eitherTag -> {
          if (eitherTag.childValue() != null) {
            queue.addFirst(toStreamItem(eitherTag.childValue()));
          }
          tokenText = eitherTag.tagToken().getText();
          span = eitherTag.tagSpan();
          site = eitherTag.sourceCtx();
        }
        case UnionTagItem unionTag -> {
          if (unionTag.childValue() != null) {
            queue.addFirst(toStreamItem(unionTag.childValue()));
          }
          tokenText = unionTag.tagToken().getText();
          span = unionTag.tagSpan();
          site = unionTag.sourceCtx();
        }
        case KeywordTokenItem kw -> {
          tokenText = kw.tokenText();
          span = kw.itemSpan();
          site = kw.sourceCtx();
        }
        case BooleanLiteralItem b -> {
          tokenText = b.tokenText();
          span = b.itemSpan();
          site = b.sourceCtx();
        }
        case AtomicValueItem atom -> {
          Span atomSpan = atom.itemSpan();
          String msg = "Type mismatch: expected " + schemaLabel(schema, overrideLabel)
              + " enum variant, found '" + atom.displayText() + "'";
          throw abort(
              msg, atomSpan,
              new MalformedPayloadException(msg, atomSpan.start(), atomSpan.end()),
              "E028", DiagnosticBag.ERR_TYPE_MISMATCH);
        }
      }

      List<String> rootVariants;
      List<String> activeVariants;
      if (schema instanceof StvnSchema.EnumSchema rootEnum) {
        rootVariants = rootEnum.rootVariants();
        activeVariants = rootVariants;
      } else {
        StvnSchema.EnumSubsetSchema subset = (StvnSchema.EnumSubsetSchema) schema;
        rootVariants = subset.rootEnum().rootVariants();
        activeVariants = subset.activeVariants();
      }

      // Dimension 1 first: active enum variant
      if (activeVariants.contains(tokenText)) {
        int ordinal = rootVariants.indexOf(tokenText);
        return new StvnValue.StvnEnum(schema, tokenText, BigInteger.valueOf(ordinal));
      }

      // Dimension 2: check if tokenText is a declared constant in :defs
      var constOpt = constantResolver.resolve(tokenText, site);
      if (constOpt.isPresent()) {
        return adaptConstantToSchema(constOpt.get(), tokenText, schema, span);
      }

      // Filtered-out variant vs completely unknown variant
      if (rootVariants.contains(tokenText)) {
        String msg = "Invalid enum value: variant " + tokenText + " is not permitted in enum subset "
            + schemaLabel(schema, overrideLabel) + "; allowed variants: " + activeVariants;
        throw abort(
            msg, span,
            new MalformedPayloadException(msg, span.start(), span.end()),
            "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION);
      }
      String msg = "Invalid enum value: expected one of " + activeVariants + ", got " + tokenText;
      throw abort(
          msg, span,
          new MalformedPayloadException(msg, span.start(), span.end()),
          "E028", DiagnosticBag.ERR_TYPE_MISMATCH);
    }

    private StvnValue lowerOptionFromQueue(Deque<StreamItem> queue, StvnSchema.OptionSchema optionSchema) {
      StreamItem peek = queue.getFirst();

      // Explicit #None / #N
      if (peek instanceof OptionTagItem optTag) {
        String tag = optTag.tagToken().getText();
        if (tag.equals(StvnVocabulary.VAL_NONE) || tag.equals(StvnVocabulary.VAL_NONE_SHORT)) {
          queue.removeFirst();
          if (optTag.childValue() != null) {
            queue.addFirst(toStreamItem(optTag.childValue()));
          }
          return new StvnValue.StvnOption(optionSchema, Optional.empty());
        }
        // Explicit #Some / #S
        queue.removeFirst();
        if (optTag.childValue() == null) {
          Span span = optTag.itemSpan();
          String msg = StvnVocabulary.TYPE_OPTION + " tag '" + tag + "' requires an inner value payload";
          throw abort(
              msg, span,
              new MalformedPayloadException(msg, span.start(), span.end()),
              "E028", DiagnosticBag.ERR_TYPE_MISMATCH);
        }
        Deque<StreamItem> innerQueue = new ArrayDeque<>();
        innerQueue.addLast(toStreamItem(optTag.childValue()));
        StvnValue innerVal;
        try {
          innerVal = evaluateNext(innerQueue, optionSchema.valueSchema());
        } catch (LoweringAbortException e) {
          innerVal = new StvnErrorValue(optionSchema.valueSchema(), e.diagnostic());
        } finally {
          while (!innerQueue.isEmpty()) {
            queue.addFirst(innerQueue.removeLast());
          }
        }
        return new StvnValue.StvnOption(optionSchema, Optional.of(innerVal));
      }

      // Direct #CONST matching the OptionSchema itself vs Rule A implied #Some
      if (peek instanceof KeywordTokenItem kw) {
        if (kw.tokenText().equals(StvnVocabulary.VAL_NONE) || kw.tokenText().equals(StvnVocabulary.VAL_NONE_SHORT)) {
          queue.removeFirst();
          return new StvnValue.StvnOption(optionSchema, Optional.empty());
        }
        var constOpt = constantResolver.resolve(kw.tokenText(), kw.sourceCtx());
        if (constOpt.isPresent()
            && (constOpt.get().schema() instanceof StvnSchema.OptionSchema
                || constOpt.get().schema() instanceof StvnSchema.PoisonedSchema
                || constOpt.get().value() instanceof StvnErrorValue)) {
          queue.removeFirst();
          return adaptConstantToSchema(constOpt.get(), kw.tokenText(), optionSchema, kw.itemSpan());
        }
      }

      // Rule A: Untagged value implicitly wraps in #Some
      StvnValue innerVal = evaluateNext(queue, optionSchema.valueSchema());
      return new StvnValue.StvnOption(optionSchema, Optional.of(innerVal));
    }

    private StvnValue lowerEitherFromQueue(Deque<StreamItem> queue, StvnSchema.EitherSchema eitherSchema) {
      StreamItem peek = queue.getFirst();

      // Explicit #Left / #L or #Right / #R
      if (peek instanceof EitherTagItem eitherTag) {
        queue.removeFirst();
        String tag = eitherTag.tagToken().getText();
        boolean isRight = tag.equals(StvnVocabulary.VAL_RIGHT) || tag.equals(StvnVocabulary.VAL_RIGHT_SHORT);
        if (eitherTag.childValue() == null) {
          Span span = eitherTag.itemSpan();
          String msg = StvnVocabulary.TYPE_EITHER + " tag '" + tag + "' requires a branch value payload";
          throw abort(
              msg, span,
              new MalformedPayloadException(msg, span.start(), span.end()),
              "E028", DiagnosticBag.ERR_TYPE_MISMATCH);
        }
        StvnSchema branchSchema = isRight ? eitherSchema.rightSchema() : eitherSchema.leftSchema();
        Deque<StreamItem> branchQueue = new ArrayDeque<>();
        branchQueue.addLast(toStreamItem(eitherTag.childValue()));
        StvnValue branchVal;
        try {
          branchVal = evaluateNext(branchQueue, branchSchema);
        } catch (LoweringAbortException e) {
          branchVal = new StvnErrorValue(branchSchema, e.diagnostic());
        } finally {
          while (!branchQueue.isEmpty()) {
            queue.addFirst(branchQueue.removeLast());
          }
        }
        return new StvnValue.StvnEither(
            eitherSchema,
            isRight ? EitherBranch.RIGHT : EitherBranch.LEFT,
            branchVal
        );
      }

      // Direct #CONST whose declared schema is EitherSchema (or poisoned sentinel)
      if (peek instanceof KeywordTokenItem kw) {
        var constOpt = constantResolver.resolve(kw.tokenText(), kw.sourceCtx());
        if (constOpt.isPresent()
            && (constOpt.get().schema() instanceof StvnSchema.EitherSchema
                || constOpt.get().schema() instanceof StvnSchema.PoisonedSchema
                || constOpt.get().value() instanceof StvnErrorValue)) {
          queue.removeFirst();
          return adaptConstantToSchema(constOpt.get(), kw.tokenText(), eitherSchema, kw.itemSpan());
        }
      }

      Span itemSpan = peek.itemSpan();
      StvnSchema leftSchema = eitherSchema.leftSchema();
      StvnSchema rightSchema = eitherSchema.rightSchema();

      // Identical branch schemas cannot be implicitly disambiguated without explicit #Left / #Right
      if (areIdenticalSchemas(leftSchema, rightSchema)) {
        queue.removeFirst();
        String msg = "Ambiguous implicit either: Both sides are identical (" + schemaLabel(leftSchema, null)
            + "), explicit #Left or #Right tag is required";
        throw abort(
            msg, itemSpan,
            new MalformedPayloadException(msg, itemSpan.start(), itemSpan.end()),
            "E024", DiagnosticBag.ERR_AMBIGUOUS_SUM_INFERENCE);
      }

      // Probe Right and Left branches independently (Rules D, E, H)
      BranchProbeResult rightProbe = probeBranch(queue, rightSchema);
      BranchProbeResult leftProbe = probeBranch(queue, leftSchema);

      if (rightProbe.succeeded() && leftProbe.succeeded()) {
        queue.removeFirst();
        String msg = "Ambiguous implicit resolution: Value matches both Left and Right branches of "
            + StvnVocabulary.TYPE_EITHER;
        throw abort(
            msg, itemSpan,
            new MalformedPayloadException(msg, itemSpan.start(), itemSpan.end()),
            "E024", DiagnosticBag.ERR_AMBIGUOUS_SUM_INFERENCE);
      }

      if (rightProbe.succeeded()) {
        commitProbe(queue, rightProbe);
        return new StvnValue.StvnEither(eitherSchema, EitherBranch.RIGHT, Objects.requireNonNull(rightProbe.value()));
      }

      if (leftProbe.succeeded()) {
        queue.removeFirst();
        String msg = "Rule E Violation: Untagged value matching Left branch of "
            + StvnVocabulary.TYPE_EITHER + " is non-inferable; explicit #Left tag is required";
        throw abort(
            msg, itemSpan,
            new MalformedPayloadException(msg, itemSpan.start(), itemSpan.end()),
            "E023", DiagnosticBag.ERR_EITHER_LEFT_MISSING_TAG);
      }

      // If Right uniquely matches a container literal syntax, commit Right for M == N child fault isolation
      if (uniquelyMatchesContainerKind(peek, rightSchema) && !uniquelyMatchesContainerKind(peek, leftSchema)) {
        StvnValue rightVal = evaluateNext(queue, rightSchema);
        return new StvnValue.StvnEither(eitherSchema, EitherBranch.RIGHT, rightVal);
      }

      queue.removeFirst();
      // Preserve specific Rule C / undefined-constant errors if raised on a branch
      StvnDiagnostic specific = pickSpecificBranchDiagnostic(rightProbe.bag(), leftProbe.bag());
      if (specific != null) {
        diagnosticBag.add(specific);
        throw new LoweringAbortException(specific);
      }
      String msg = "Type mismatch: Value matches neither Left nor Right branch of " + StvnVocabulary.TYPE_EITHER;
      throw abort(
          msg, itemSpan,
          new MalformedPayloadException(msg, itemSpan.start(), itemSpan.end()),
          "E028", DiagnosticBag.ERR_TYPE_MISMATCH);
    }

    private StvnValue lowerUnionFromQueue(Deque<StreamItem> queue, StvnSchema.UnionSchema unionSchema) {
      StreamItem peek = queue.getFirst();
      List<StvnSchema> branches = unionSchema.branchSchemas();

      // Explicit #<k> branch tag
      if (peek instanceof UnionTagItem unionTag) {
        queue.removeFirst();
        BigInteger oneBased = parseUnionBranchIndex(unionTag.tagToken().getText());
        BigInteger branchCount = BigInteger.valueOf(branches.size());
        if (oneBased.compareTo(BigInteger.ONE) < 0 || oneBased.compareTo(branchCount) > 0) {
          Span tagSpan = unionTag.tagSpan();
          String msg = "Union variant tag '#" + oneBased + "' exceeds branch count (" + branchCount + ")";
          throw abort(
              msg, tagSpan,
              new StvnMalformedLiteralException(msg, tagSpan.start(), tagSpan.end()),
              "E028", DiagnosticBag.ERR_UNION_BRANCH_OVERFLOW);
        }
        if (unionTag.childValue() == null) {
          Span span = unionTag.itemSpan();
          String msg = StvnVocabulary.TYPE_UNION + " tag '#" + oneBased + "' requires a branch value payload";
          throw abort(
              msg, span,
              new MalformedPayloadException(msg, span.start(), span.end()),
              "E028", DiagnosticBag.ERR_TYPE_MISMATCH);
        }
        StvnSchema selectedSchema = branches.get(oneBased.intValueExact() - 1);
        Deque<StreamItem> branchQueue = new ArrayDeque<>();
        branchQueue.addLast(toStreamItem(unionTag.childValue()));
        StvnValue branchVal;
        try {
          branchVal = evaluateNext(branchQueue, selectedSchema);
        } catch (LoweringAbortException e) {
          branchVal = new StvnErrorValue(selectedSchema, e.diagnostic());
        } finally {
          while (!branchQueue.isEmpty()) {
            queue.addFirst(branchQueue.removeLast());
          }
        }
        return new StvnValue.StvnUnion(unionSchema, oneBased, branchVal);
      }

      // Direct #CONST whose declared schema is UnionSchema (or poisoned sentinel)
      if (peek instanceof KeywordTokenItem kw) {
        var constOpt = constantResolver.resolve(kw.tokenText(), kw.sourceCtx());
        if (constOpt.isPresent()
            && (constOpt.get().schema() instanceof StvnSchema.UnionSchema
                || constOpt.get().schema() instanceof StvnSchema.PoisonedSchema
                || constOpt.get().value() instanceof StvnErrorValue)) {
          queue.removeFirst();
          return adaptConstantToSchema(constOpt.get(), kw.tokenText(), unionSchema, kw.itemSpan());
        }
      }

      Span itemSpan = peek.itemSpan();
      int matchCount = 0;
      int matchedIndex = -1;
      BranchProbeResult winningProbe = null;
      List<DiagnosticBag> failedBags = new ArrayList<>(branches.size());

      for (int i = 0; i < branches.size(); i++) {
        BranchProbeResult probe = probeBranch(queue, branches.get(i));
        if (probe.succeeded()) {
          matchCount++;
          matchedIndex = i;
          winningProbe = probe;
        } else {
          failedBags.add(probe.bag());
        }
      }

      if (matchCount > 1) {
        queue.removeFirst();
        String msg = "Ambiguous implicit resolution: Value matches multiple branches";
        throw abort(
            msg, itemSpan,
            new StvnCollectionCollisionException(msg, itemSpan.start(), itemSpan.end()),
            "E024", DiagnosticBag.ERR_AMBIGUOUS_SUM_INFERENCE);
      }

      if (matchCount == 1 && winningProbe != null) {
        commitProbe(queue, winningProbe);
        return new StvnValue.StvnUnion(
            unionSchema,
            BigInteger.valueOf(matchedIndex + 1L),
            Objects.requireNonNull(winningProbe.value())
        );
      }

      // Single container branch fallback for M == N child fault isolation
      int containerBranchIdx = -1;
      int containerBranchMatches = 0;
      for (int i = 0; i < branches.size(); i++) {
        if (uniquelyMatchesContainerKind(peek, branches.get(i))) {
          containerBranchMatches++;
          containerBranchIdx = i;
        }
      }
      if (containerBranchMatches == 1) {
        StvnValue branchVal = evaluateNext(queue, branches.get(containerBranchIdx));
        return new StvnValue.StvnUnion(
            unionSchema,
            BigInteger.valueOf(containerBranchIdx + 1L),
            branchVal
        );
      }

      queue.removeFirst();
      for (DiagnosticBag fb : failedBags) {
        for (StvnDiagnostic d : fb.toList()) {
          if (d.cause() instanceof IllegalArgumentException
              || DiagnosticBag.ERR_UNDEFINED_CONSTANT.equals(d.symbolicId().orElse(null))) {
            diagnosticBag.add(d);
            throw new LoweringAbortException(d);
          }
        }
      }
      String msg = "Type mismatch: Value does not match any branch of " + StvnVocabulary.TYPE_UNION;
      throw abort(
          msg, itemSpan,
          new MalformedPayloadException(msg, itemSpan.start(), itemSpan.end()),
          "E028", DiagnosticBag.ERR_TYPE_MISMATCH);
    }

    private record BranchProbeResult(
        boolean succeeded,
        @Nullable StvnValue value,
        Deque<StreamItem> remainingQueue,
        DiagnosticBag bag
    ) {}

    private BranchProbeResult probeBranch(Deque<StreamItem> originalQueue, StvnSchema rawBranchSchema) {
      StvnSchema branchSchema = unwrapRecursiveBackEdge(rawBranchSchema, nominalSchemaResolver);
      if (branchSchema instanceof StvnSchema.PoisonedSchema) {
        return new BranchProbeResult(false, null, new ArrayDeque<>(originalQueue), new DiagnosticBag());
      }
      DiagnosticBag probeBag = new DiagnosticBag();
      Deque<StreamItem> probeQueue = new ArrayDeque<>(originalQueue);
      LoweringSession probeSession = withBag(probeBag);
      try {
        StvnValue candidate = probeSession.evaluateNext(probeQueue, branchSchema);
        boolean ok = !probeBag.hasErrors() && !containsErrorValue(candidate);
        return new BranchProbeResult(ok, ok ? candidate : null, probeQueue, probeBag);
      } catch (LoweringAbortException e) {
        return new BranchProbeResult(false, null, probeQueue, probeBag);
      }
    }

    private void commitProbe(Deque<StreamItem> queue, BranchProbeResult probe) {
      queue.clear();
      queue.addAll(probe.remainingQueue());
      for (StvnDiagnostic d : probe.bag().toList()) {
        diagnosticBag.add(d);
      }
    }

    private static @Nullable StvnDiagnostic pickSpecificBranchDiagnostic(DiagnosticBag first, DiagnosticBag second) {
      for (DiagnosticBag bag : List.of(first, second)) {
        for (StvnDiagnostic d : bag.toList()) {
          if (d.cause() instanceof IllegalArgumentException) {
            return d;
          }
        }
      }
      return null;
    }

    private boolean uniquelyMatchesContainerKind(StreamItem peek, StvnSchema rawSchema) {
      StvnSchema schema = unwrapRecursiveBackEdge(rawSchema, nominalSchemaResolver);
      if (!(peek instanceof AtomicValueItem atom) || atom.ctx().collectionValue() == null) {
        return false;
      }
      var coll = atom.ctx().collectionValue();
      if (coll.listLiteral() != null) {
        return schema instanceof StvnSchema.SeqSchema || schema instanceof StvnSchema.SetSchema;
      }
      if (coll.tupleLiteral() != null) {
        return schema instanceof StvnSchema.TupleSchema;
      }
      if (coll.mapLiteral() != null) {
        return schema instanceof StvnSchema.MapSchema;
      }
      return false;
    }

    private static boolean areIdenticalSchemas(StvnSchema a, StvnSchema b) {
      if (a.aliasName().isPresent() || b.aliasName().isPresent()) {
        return a.aliasName().equals(b.aliasName());
      }
      return a.equals(b);
    }

    private StvnValue resolveConstantOrFail(
        String tokenText,
        @Nullable ParserRuleContext site,
        StvnSchema targetSchema,
        Span span
    ) {
      var constOpt = constantResolver.resolve(tokenText, site);
      if (constOpt.isPresent()) {
        return adaptConstantToSchema(constOpt.get(), tokenText, targetSchema, span);
      }
      if (targetSchema instanceof StvnSchema.BooleanSchema) {
        String upper = tokenText.toUpperCase(Locale.ROOT);
        if (upper.equals(StvnVocabulary.VAL_TRUE) || upper.equals(StvnVocabulary.VAL_FALSE)
            || upper.equals(StvnVocabulary.VAL_TRUE_SHORT) || upper.equals(StvnVocabulary.VAL_FALSE_SHORT)) {
          String msg = "Invalid boolean literal casing: found '" + tokenText
              + "', expected exactly '#TRUE', '#T', '#FALSE', or '#F'.";
          throw abort(
              msg, span,
              new StvnMalformedLiteralException(msg, span.start(), span.end()),
              "E002", "STVN_SYNTAX_ERROR");
        }
      }
      String msg = "Undeclared value keyword or constant: '" + tokenText + "'";
      throw abort(
          msg, span,
          new MalformedPayloadException(msg, span.start(), span.end()),
          "E030", DiagnosticBag.ERR_UNDEFINED_CONSTANT);
    }

    private StvnValue adaptConstantToSchema(
        StvnDefsCatalog.StvnConstant constant,
        String rawTokenText,
        StvnSchema targetSchema,
        Span span
    ) {
      StvnSchema sourceSchema = constant.schema();
      if (sourceSchema instanceof StvnSchema.PoisonedSchema poisoned) {
        return silentPoisonErrorValue(new ParserRuleContext(), poisoned);
      }
      if (constant.value() instanceof StvnErrorValue errVal) {
        return new StvnErrorValue(targetSchema, errVal.diagnostic());
      }

      // § 9.1 Opaque Nominal Branding check
      if (sourceSchema.aliasName().isPresent() || targetSchema.aliasName().isPresent()) {
        if (!sourceSchema.aliasName().equals(targetSchema.aliasName())) {
          String srcName = sourceSchema.aliasName().orElseGet(() -> baseTypeName(sourceSchema));
          String tgtName = targetSchema.aliasName().orElseGet(() -> baseTypeName(targetSchema));
          String msg = "Incompatible nominal type: constant '" + rawTokenText + "' of nominal type '"
              + srcName + "' cannot be assigned to target nominal type '" + tgtName + "'";
          throw abort(
              msg, span,
              new MalformedPayloadException(msg, span.start(), span.end()),
              "E027", DiagnosticBag.ERR_INCOMPATIBLE_NOMINAL_TYPE);
        }
        return revalidateAndRebrandValue(constant.value(), targetSchema, span);
      }

      // Both schemas are anonymous: verify structural & domain compatibility against targetSchema
      return revalidateAndRebrandValue(constant.value(), targetSchema, span);
    }

    private StvnValue revalidateAndRebrandValue(StvnValue value, StvnSchema targetSchema, Span span) {
      String label = schemaLabel(targetSchema, overrideLabel);
      return switch (targetSchema) {
        case StvnSchema.BooleanSchema boolSchema -> {
          if (value instanceof StvnValue.StvnBoolean b) {
            yield new StvnValue.StvnBoolean(boolSchema, b.value());
          }
          throw typeMismatch(targetSchema, value, span);
        }
        case StvnSchema.IntSchema intSchema -> {
          if (value instanceof StvnValue.StvnInt i) {
            validateIntValue(i.value(), i.value().toString(), intSchema, label, span);
            yield new StvnValue.StvnInt(intSchema, i.value());
          }
          throw typeMismatch(targetSchema, value, span);
        }
        case StvnSchema.FloatSchema floatSchema -> {
          if (value instanceof StvnValue.StvnFloat f) {
            validateFloatValue(f.decimalValue(), floatSchema, label, span);
            yield new StvnValue.StvnFloat(floatSchema, f.decimalValue(), f.negativeZero(), f.specialKind());
          }
          throw typeMismatch(targetSchema, value, span);
        }
        case StvnSchema.StringSchema stringSchema -> {
          if (value instanceof StvnValue.StvnString s) {
            validateStringValue(s.value(), stringSchema, label, span);
            yield new StvnValue.StvnString(stringSchema, s.value());
          }
          throw typeMismatch(targetSchema, value, span);
        }
        case StvnSchema.TimeEpochSchema epochSchema -> {
          if (value instanceof StvnValue.StvnTimeEpoch te && te.scale() == epochSchema.scale()) {
            validateEpochTicks(te.epochTicks(), te.epochTicks().toString(), epochSchema, label, span);
            yield new StvnValue.StvnTimeEpoch(epochSchema, te.epochTicks(), epochSchema.scale());
          }
          throw typeMismatch(targetSchema, value, span);
        }
        case StvnSchema.DateTimeSchema dtSchema -> {
          if (value instanceof StvnValue.StvnDateTime dt && dt.mode() == dtSchema.mode()) {
            Instant instant = toInstant(dt);
            validateDateTimeBounds(instant, dt.canonicalIsoText(), dtSchema, label, span);
            yield new StvnValue.StvnDateTime(
                dtSchema, dt.mode(), dt.localWallClock(), dt.offset(), dt.zone(), dt.canonicalIsoText());
          }
          throw typeMismatch(targetSchema, value, span);
        }
        case StvnSchema.EnumSchema enumSchema -> {
          if (value instanceof StvnValue.StvnEnum en && enumSchema.rootVariants().contains(en.variantTag())) {
            int ord = enumSchema.rootVariants().indexOf(en.variantTag());
            yield new StvnValue.StvnEnum(enumSchema, en.variantTag(), BigInteger.valueOf(ord));
          }
          throw typeMismatch(targetSchema, value, span);
        }
        case StvnSchema.EnumSubsetSchema subSchema -> {
          if (value instanceof StvnValue.StvnEnum en && subSchema.activeVariants().contains(en.variantTag())) {
            int ord = subSchema.rootEnum().rootVariants().indexOf(en.variantTag());
            yield new StvnValue.StvnEnum(subSchema, en.variantTag(), BigInteger.valueOf(ord));
          }
          throw typeMismatch(targetSchema, value, span);
        }
        case StvnSchema.TupleSchema tupleSchema -> {
          if (value instanceof StvnValue.StvnTuple t
              && t.elements().size() == tupleSchema.fieldSchemas().size()) {
            List<StvnValue> adapted = new ArrayList<>(t.elements().size());
            for (int idx = 0; idx < t.elements().size(); idx++) {
              adapted.add(revalidateAndRebrandValue(
                  t.elements().get(idx), tupleSchema.fieldSchemas().get(idx), span));
            }
            yield new StvnValue.StvnTuple(tupleSchema, adapted);
          }
          throw typeMismatch(targetSchema, value, span);
        }
        case StvnSchema.OptionSchema optSchema -> {
          if (value instanceof StvnValue.StvnOption o) {
            Optional<StvnValue> inner = o.value()
                .map(v -> revalidateAndRebrandValue(v, optSchema.valueSchema(), span));
            yield new StvnValue.StvnOption(optSchema, inner);
          }
          yield new StvnValue.StvnOption(
              optSchema, Optional.of(revalidateAndRebrandValue(value, optSchema.valueSchema(), span)));
        }
        case StvnSchema.EitherSchema eitherSchema -> {
          if (value instanceof StvnValue.StvnEither e) {
            StvnSchema branchTarget = e.branch() == EitherBranch.RIGHT
                ? eitherSchema.rightSchema()
                : eitherSchema.leftSchema();
            yield new StvnValue.StvnEither(
                eitherSchema, e.branch(), revalidateAndRebrandValue(e.value(), branchTarget, span));
          }
          throw typeMismatch(targetSchema, value, span);
        }
        case StvnSchema.UnionSchema unionSchema -> {
          if (value instanceof StvnValue.StvnUnion u
              && u.oneBasedBranchIndex().intValueExact() <= unionSchema.branchSchemas().size()) {
            StvnSchema branchTarget = unionSchema.branchSchemas().get(u.oneBasedBranchIndex().intValueExact() - 1);
            yield new StvnValue.StvnUnion(
                unionSchema, u.oneBasedBranchIndex(), revalidateAndRebrandValue(u.value(), branchTarget, span));
          }
          throw typeMismatch(targetSchema, value, span);
        }
        case StvnSchema.SeqSchema seqSchema -> {
          if (value instanceof StvnValue.StvnSeq s) {
            BigInteger count = BigInteger.valueOf(s.elements().size());
            if (count.compareTo(seqSchema.minSize()) < 0 || count.compareTo(seqSchema.maxSize()) > 0) {
              String msg = "Constraint violation (" + label + "): Size " + count
                  + " outside allowable range [" + seqSchema.minSize() + ", " + seqSchema.maxSize() + "]";
              throw abort(
                  msg, span,
                  new MalformedPayloadException(msg, span.start(), span.end()),
                  "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION);
            }
            List<StvnValue> adapted = new ArrayList<>(s.elements().size());
            for (StvnValue el : s.elements()) {
              adapted.add(revalidateAndRebrandValue(el, seqSchema.elementSchema(), span));
            }
            yield new StvnValue.StvnSeq(seqSchema, adapted);
          }
          throw typeMismatch(targetSchema, value, span);
        }
        case StvnSchema.SetSchema setSchema -> {
          if (value instanceof StvnValue.StvnSet s) {
            BigInteger count = BigInteger.valueOf(s.elements().size());
            if (count.compareTo(setSchema.minSize()) < 0 || count.compareTo(setSchema.maxSize()) > 0) {
              String msg = "Constraint violation (" + label + "): Size " + count
                  + " outside allowable range [" + setSchema.minSize() + ", " + setSchema.maxSize() + "]";
              throw abort(
                  msg, span,
                  new MalformedPayloadException(msg, span.start(), span.end()),
                  "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION);
            }
            List<StvnValue> adapted = new ArrayList<>(s.elements().size());
            for (StvnValue el : s.elements()) {
              adapted.add(revalidateAndRebrandValue(el, setSchema.elementSchema(), span));
            }
            yield new StvnValue.StvnSet(setSchema, adapted);
          }
          throw typeMismatch(targetSchema, value, span);
        }
        case StvnSchema.MapSchema mapSchema -> {
          if (value instanceof StvnValue.StvnMap m) {
            BigInteger count = BigInteger.valueOf(m.entries().size());
            if (count.compareTo(mapSchema.minSize()) < 0 || count.compareTo(mapSchema.maxSize()) > 0) {
              String msg = "Constraint violation (" + label + "): Size " + count
                  + " outside allowable range [" + mapSchema.minSize() + ", " + mapSchema.maxSize() + "]";
              throw abort(
                  msg, span,
                  new MalformedPayloadException(msg, span.start(), span.end()),
                  "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION);
            }
            List<KeyValueEntry> adapted = new ArrayList<>(m.entries().size());
            Set<Object> seenValues = new LinkedHashSet<>();
            for (KeyValueEntry entry : m.entries()) {
              StvnValue k = revalidateAndRebrandValue(entry.key(), mapSchema.keySchema(), span);
              StvnValue v = revalidateAndRebrandValue(entry.value(), mapSchema.valueSchema(), span);
              if (mapSchema.invertible() && !seenValues.add(canonicalEqualityKey(v))) {
                String msg = "Duplicate inverted map value detected";
                throw abort(
                    msg, span,
                    new StvnCollectionCollisionException(msg, span.start(), span.end()),
                    "E018", DiagnosticBag.ERR_DUPLICATE_INVERTED_MAP_VALUE);
              }
              adapted.add(new KeyValueEntry(k, v));
            }
            yield new StvnValue.StvnMap(mapSchema, adapted);
          }
          throw typeMismatch(targetSchema, value, span);
        }
        case StvnSchema.PoisonedSchema poisoned -> silentPoisonErrorValue(new ParserRuleContext(), poisoned);
      };
    }

    private LoweringAbortException typeMismatch(StvnSchema targetSchema, StvnValue value, Span span) {
      String msg = "Type mismatch: constant value of type " + schemaLabel(value.schema(), null)
          + " cannot be assigned to " + schemaLabel(targetSchema, overrideLabel);
      return abort(
          msg, span,
          new MalformedPayloadException(msg, span.start(), span.end()),
          "E028", DiagnosticBag.ERR_TYPE_MISMATCH);
    }

    private static Instant toInstant(StvnValue.StvnDateTime dt) {
      return switch (dt.mode()) {
        case OFFSET, AUDITED -> OffsetDateTime.of(
            dt.localWallClock(), dt.offset().orElse(ZoneOffset.UTC)).toInstant();
        case ZONED -> dt.localWallClock().atZone(dt.zone().orElseThrow()).toInstant();
      };
    }

    private StvnValue lowerAtomicValue(StvnParser.ValueContext ctx, StvnSchema schema) {
      Span span = Span.ofContext(ctx);
      String label = schemaLabel(schema, overrideLabel);
      String mismatchCtx = schemaContextMode && overrideLabel != null ? " (" + overrideLabel + ")" : "";
      String expectedKind = describeSchemaStructuralKind(schema);

      if (ctx.integerLiteral() != null) {
        String raw = ctx.integerLiteral().getText();
        if (schema instanceof StvnSchema.IntSchema intSchema) {
          if (isNegativeZeroInteger(raw)) {
            String msg = "Negative zero integer literal '" + raw + "' is prohibited on " + StvnVocabulary.TYPE_INT;
            throw abort(
                msg, span,
                new MalformedPayloadException(msg, span.start(), span.end()),
                "E009", DiagnosticBag.ERR_INVALID_NUMERIC_LITERAL);
          }
          BigInteger value = parseIntegerLiteral(raw);
          validateIntValue(value, raw, intSchema, label, span);
          return new StvnValue.StvnInt(intSchema, value);
        }
        if (schema instanceof StvnSchema.TimeEpochSchema epochSchema) {
          if (isNegativeZeroInteger(raw)) {
            String msg = "Negative zero integer literal '" + raw + "' is prohibited on "
                + StvnVocabulary.TYPE_TIME_EPOCH;
            throw abort(
                msg, span,
                new MalformedPayloadException(msg, span.start(), span.end()),
                "E009", DiagnosticBag.ERR_INVALID_NUMERIC_LITERAL);
          }
          BigInteger ticks = parseIntegerLiteral(raw);
          validateEpochTicks(ticks, raw, epochSchema, label, span);
          return new StvnValue.StvnTimeEpoch(epochSchema, ticks, epochSchema.scale());
        }
        String msg = "Type mismatch" + mismatchCtx + ": expected " + expectedKind
            + ", found scalar " + StvnVocabulary.TYPE_INT + " literal '" + raw + "'";
        throw abort(
            msg, span,
            new MalformedPayloadException(msg, span.start(), span.end()),
            "E028", DiagnosticBag.ERR_TYPE_MISMATCH);
      }

      if (ctx.floatLiteral() != null) {
        String raw = ctx.floatLiteral().getText();
        if (schema instanceof StvnSchema.FloatSchema floatSchema) {
          BigDecimal decimal = new BigDecimal(raw);
          boolean negativeZero = raw.startsWith("-") && decimal.signum() == 0;
          validateFloatValue(decimal, floatSchema, label, span);
          return new StvnValue.StvnFloat(floatSchema, decimal, negativeZero, Ieee754SpecialKind.FINITE);
        }
        String msg = "Type mismatch" + mismatchCtx + ": expected " + expectedKind
            + ", found scalar " + StvnVocabulary.TYPE_FLOAT + " literal '" + raw + "'";
        throw abort(
            msg, span,
            new MalformedPayloadException(msg, span.start(), span.end()),
            "E028", DiagnosticBag.ERR_TYPE_MISMATCH);
      }

      if (ctx.stringLiteral() != null) {
        String raw = ctx.stringLiteral().getText();
        if (schema instanceof StvnSchema.TimeEpochSchema) {
          if (raw.contains("[")) {
            String msg = "IANA time zone bracket is prohibited in " + StvnVocabulary.TYPE_TIME_EPOCH
                + " payload: " + raw;
            throw abort(
                msg, span,
                new MalformedPayloadException(msg, span.start(), span.end()),
                "E020", DiagnosticBag.ERR_TEMPORAL_ZONE_IN_EPOCH);
          }
          String msg = "Type mismatch" + mismatchCtx + ": expected " + expectedKind
              + ", found scalar " + StvnVocabulary.TYPE_STRING + " literal";
          throw abort(
              msg, span,
              new MalformedPayloadException(msg, span.start(), span.end()),
              "E028", DiagnosticBag.ERR_TYPE_MISMATCH);
        }
        if (schema instanceof StvnSchema.DateTimeSchema dtSchema) {
          return lowerDateTimeLiteral(raw, dtSchema, label, span);
        }
        if (schema instanceof StvnSchema.StringSchema stringSchema) {
          String decoded;
          try {
            decoded = decodeStringLiteral(raw, stringSchema.preserveIndent(), span);
          } catch (LoweringLiteralException lle) {
            throw abort(lle.getMessage(), lle.span(), lle.getCause(), lle.canonicalCode(), lle.symbolicId());
          }
          validateStringValue(decoded, stringSchema, label, span);
          return new StvnValue.StvnString(stringSchema, decoded);
        }
        String msg = "Type mismatch" + mismatchCtx + ": expected " + expectedKind
            + ", found scalar " + StvnVocabulary.TYPE_STRING + " literal";
        throw abort(
            msg, span,
            new MalformedPayloadException(msg, span.start(), span.end()),
            "E028", DiagnosticBag.ERR_TYPE_MISMATCH);
      }

      if (ctx.collectionValue() != null) {
        var coll = ctx.collectionValue();
        if (coll.listLiteral() != null) {
          if (schema instanceof StvnSchema.SeqSchema seqSchema) {
            return lowerListContainer(coll.listLiteral(), seqSchema, seqSchema.elementSchema(),
                seqSchema.minSize(), seqSchema.maxSize(), false, label);
          }
          if (schema instanceof StvnSchema.SetSchema setSchema) {
            return lowerListContainer(coll.listLiteral(), setSchema, setSchema.elementSchema(),
                setSchema.minSize(), setSchema.maxSize(), true, label);
          }
          String msg = "Type mismatch" + mismatchCtx + ": expected " + expectedKind
              + ", found collection constructor " + StvnVocabulary.TYPE_SEQ;
          throw abort(
              msg, span,
              new MalformedPayloadException(msg, span.start(), span.end()),
              "E028", DiagnosticBag.ERR_TYPE_MISMATCH);
        }
        if (coll.tupleLiteral() != null) {
          if (schema instanceof StvnSchema.TupleSchema tupleSchema) {
            return lowerTupleContainer(coll.tupleLiteral(), tupleSchema);
          }
          String msg = "Type mismatch" + mismatchCtx + ": expected " + expectedKind
              + ", found product constructor " + StvnVocabulary.TYPE_TUPLE;
          throw abort(
              msg, span,
              new MalformedPayloadException(msg, span.start(), span.end()),
              "E028", DiagnosticBag.ERR_TYPE_MISMATCH);
        }
        if (coll.mapLiteral() != null) {
          if (schema instanceof StvnSchema.MapSchema mapSchema) {
            return lowerMapContainer(coll.mapLiteral(), mapSchema, label);
          }
          String msg = "Type mismatch" + mismatchCtx + ": expected " + expectedKind
              + ", found collection constructor " + StvnVocabulary.TYPE_MAP;
          throw abort(
              msg, span,
              new MalformedPayloadException(msg, span.start(), span.end()),
              "E028", DiagnosticBag.ERR_TYPE_MISMATCH);
        }
      }

      String msg = "Type mismatch" + mismatchCtx + ": Value '" + ctx.getText() + "' does not conform to " + label;
      throw abort(
          msg, span,
          new MalformedPayloadException(msg, span.start(), span.end()),
          "E028", DiagnosticBag.ERR_TYPE_MISMATCH);
    }

    private void validateIntValue(
        BigInteger value,
        String rawText,
        StvnSchema.IntSchema intSchema,
        String label,
        Span span
    ) {
      if (!intSchema.acceptsWidth(value)) {
        String signedness = intSchema.unsigned() ? "unsigned " : "signed ";
        String msg = "Integer literal '" + rawText + "' overflows " + signedness
            + intSchema.bitWidth() + "-bit capacity for " + label;
        throw abort(
            msg, span,
            new StvnIntegerOverflowException(msg, span.start(), span.end()),
            "E009", DiagnosticBag.ERR_INTEGER_OVERFLOW);
      }
      if (intSchema.explicitMinIncl().isPresent() && value.compareTo(intSchema.explicitMinIncl().get()) < 0) {
        String msg = "Constraint violation (" + label + "): Value " + value
            + " is outside allowable range (must be >= " + intSchema.explicitMinIncl().get() + ")";
        throw abort(
            msg, span,
            new MalformedPayloadException(msg, span.start(), span.end()),
            "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION);
      }
      if (intSchema.explicitMaxExcl().isPresent() && value.compareTo(intSchema.explicitMaxExcl().get()) >= 0) {
        String msg = "Constraint violation (" + label + "): Value " + value
            + " is outside allowable range (must be < " + intSchema.explicitMaxExcl().get() + ")";
        throw abort(
            msg, span,
            new MalformedPayloadException(msg, span.start(), span.end()),
            "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION);
      }
    }

    private void validateEpochTicks(
        BigInteger ticks,
        String rawText,
        StvnSchema.TimeEpochSchema epochSchema,
        String label,
        Span span
    ) {
      BigInteger bitWidth = epochSchema.scale().bitWidth();
      if (!fitsSignedBitWidth(ticks, bitWidth)) {
        String msg = "Epoch tick literal '" + rawText + "' overflows signed "
            + bitWidth + "-bit capacity for " + label;
        throw abort(
            msg, span,
            new StvnIntegerOverflowException(msg, span.start(), span.end()),
            "E009", DiagnosticBag.ERR_INTEGER_OVERFLOW);
      }
      if (epochSchema.explicitMinIncl().isPresent() && ticks.compareTo(epochSchema.explicitMinIncl().get()) < 0) {
        String msg = "Constraint violation (" + label + "): Epoch ticks " + ticks
            + " is outside allowable range (must be >= " + epochSchema.explicitMinIncl().get() + ")";
        throw abort(
            msg, span,
            new MalformedPayloadException(msg, span.start(), span.end()),
            "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION);
      }
      if (epochSchema.explicitMaxExcl().isPresent() && ticks.compareTo(epochSchema.explicitMaxExcl().get()) >= 0) {
        String msg = "Constraint violation (" + label + "): Epoch ticks " + ticks
            + " is outside allowable range (must be < " + epochSchema.explicitMaxExcl().get() + ")";
        throw abort(
            msg, span,
            new MalformedPayloadException(msg, span.start(), span.end()),
            "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION);
      }
    }

    private void validateFloatValue(
        BigDecimal rawVal,
        StvnSchema.FloatSchema floatSchema,
        String label,
        Span span
    ) {
      if (floatSchema.representation() == FloatRepresentation.EXACT_DECIMAL) {
        BigInteger mantissaPrecision = BigInteger.valueOf(rawVal.precision());
        if (mantissaPrecision.compareTo(floatSchema.exactMaxDigits()) > 0) {
          String msg = "Constraint violation (" + label + "): Mantissa precision " + mantissaPrecision
              + " exceeds maximum allowable significant digits (" + floatSchema.exactMaxDigits() + ")";
          throw abort(
              msg, span,
              new MalformedPayloadException(msg, span.start(), span.end()),
              "E012", DiagnosticBag.ERR_CAPACITY_OVERFLOW);
        }
      } else if (floatSchema.representation() == FloatRepresentation.IEEE_32) {
        if (rawVal.compareTo(StvnVocabulary.FLOAT32_MAX) > 0
            || rawVal.compareTo(StvnVocabulary.FLOAT32_MIN) < 0) {
          String msg = "Value [" + rawVal + "] is out of bounds for " + label + " ("
              + StvnVocabulary.FLOAT32_MIN + " to " + StvnVocabulary.FLOAT32_MAX + ")";
          throw abort(
              msg, span,
              new MalformedPayloadException(msg, span.start(), span.end()),
              "E010", DiagnosticBag.ERR_FLOAT_OVERFLOW);
        }
      } else if (floatSchema.representation() == FloatRepresentation.IEEE_64) {
        if (rawVal.compareTo(StvnVocabulary.FLOAT64_MAX) > 0
            || rawVal.compareTo(StvnVocabulary.FLOAT64_MIN) < 0) {
          String msg = "Value [" + rawVal + "] is out of bounds for " + label + " ("
              + StvnVocabulary.FLOAT64_MIN + " to " + StvnVocabulary.FLOAT64_MAX + ")";
          throw abort(
              msg, span,
              new MalformedPayloadException(msg, span.start(), span.end()),
              "E010", DiagnosticBag.ERR_FLOAT_OVERFLOW);
        }
      }

      if (floatSchema.lowerBound().isPresent()) {
        var lb = floatSchema.lowerBound().get();
        int cmp = rawVal.compareTo(lb.value());
        if ((lb.kind() == BoundKind.INCLUSIVE && cmp < 0) || (lb.kind() == BoundKind.EXCLUSIVE && cmp <= 0)) {
          String op = lb.kind() == BoundKind.INCLUSIVE ? "greater than or equal to " : "strictly greater than ";
          String msg = "Constraint violation (" + label + "): Value must be " + op + lb.value();
          throw abort(
              msg, span,
              new MalformedPayloadException(msg, span.start(), span.end()),
              "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION);
        }
      }
      if (floatSchema.upperBound().isPresent()) {
        var ub = floatSchema.upperBound().get();
        int cmp = rawVal.compareTo(ub.value());
        if ((ub.kind() == BoundKind.INCLUSIVE && cmp > 0) || (ub.kind() == BoundKind.EXCLUSIVE && cmp >= 0)) {
          String op = ub.kind() == BoundKind.INCLUSIVE ? "less than or equal to " : "strictly less than ";
          String msg = "Constraint violation (" + label + "): Value must be " + op + ub.value();
          throw abort(
              msg, span,
              new MalformedPayloadException(msg, span.start(), span.end()),
              "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION);
        }
      }
    }

    private void validateStringValue(
        String text,
        StvnSchema.StringSchema stringSchema,
        String label,
        Span span
    ) {
      BigInteger cpLen = BigInteger.valueOf(text.codePointCount(0, text.length()));
      BigInteger min = stringSchema.minSize();
      BigInteger max = stringSchema.maxSize();

      if (min.compareTo(BigInteger.ONE) >= 0 && cpLen.signum() == 0 && !min.equals(max)) {
        String msg = "Constraint violation (" + label + "): String cannot be empty (Size 0 outside allowable range ["
            + min + ", " + max + "])";
        throw abort(
            msg, span,
            new MalformedPayloadException(msg, span.start(), span.end()),
            "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION);
      }
      if (min.equals(max) && !cpLen.equals(min)) {
        String msg = "Constraint violation (" + label + "): Fixed string must be exactly " + min
            + " characters long, got " + cpLen + " (Size " + cpLen + " outside allowable range ["
            + min + ", " + max + "])";
        throw abort(
            msg, span,
            new MalformedPayloadException(msg, span.start(), span.end()),
            "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION);
      }
      if (cpLen.compareTo(min) < 0) {
        String msg = "Constraint violation (" + label + "): String length " + cpLen
            + " violates #minSize constraint (" + min + ") (Size " + cpLen
            + " outside allowable range [" + min + ", " + max + "])";
        throw abort(
            msg, span,
            new MalformedPayloadException(msg, span.start(), span.end()),
            "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION);
      }
      if (cpLen.compareTo(max) > 0) {
        String msg = "Constraint violation (" + label + "): String length exceeds maximum length of " + max
            + " characters, got " + cpLen + " (Size " + cpLen
            + " outside allowable range [" + min + ", " + max + "])";
        throw abort(
            msg, span,
            new MalformedPayloadException(msg, span.start(), span.end()),
            "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION);
      }

      if (stringSchema.lowerBound().isPresent()) {
        var lb = stringSchema.lowerBound().get();
        int cmp = compareUtf8Lexicographical(text, lb.value());
        if ((lb.kind() == BoundKind.INCLUSIVE && cmp < 0) || (lb.kind() == BoundKind.EXCLUSIVE && cmp <= 0)) {
          String msg = "Constraint violation (" + label
              + "): String value falls below UTF-8 lexicographical lower bound";
          throw abort(
              msg, span,
              new MalformedPayloadException(msg, span.start(), span.end()),
              "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION);
        }
      }
      if (stringSchema.upperBound().isPresent()) {
        var ub = stringSchema.upperBound().get();
        int cmp = compareUtf8Lexicographical(text, ub.value());
        if ((ub.kind() == BoundKind.INCLUSIVE && cmp > 0) || (ub.kind() == BoundKind.EXCLUSIVE && cmp >= 0)) {
          String msg = "Constraint violation (" + label
              + "): String value exceeds UTF-8 lexicographical upper bound";
          throw abort(
              msg, span,
              new MalformedPayloadException(msg, span.start(), span.end()),
              "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION);
        }
      }

      if (stringSchema.compiledRegex().isPresent()
          && !stringSchema.compiledRegex().get().matcher(text).matches()) {
        String rawRegex = stringSchema.rawRegexPattern().orElseGet(() -> stringSchema.compiledRegex().get().pattern());
        String msg = "Constraint violation (" + label + "): String does not match required pattern: " + rawRegex;
        throw abort(
            msg, span,
            new MalformedPayloadException(msg, span.start(), span.end()),
            "E013", DiagnosticBag.ERR_REGEX_MISMATCH);
      }
    }

    private StvnValue lowerDateTimeLiteral(
        String rawText,
        StvnSchema.DateTimeSchema dtSchema,
        String label,
        Span span
    ) {
      if (rawText.startsWith("\"\"\"")) {
        String msg = "Fenced multi-line strings are prohibited for " + StvnVocabulary.TYPE_DATE_TIME + " literals";
        throw abort(
            msg, span,
            new MalformedPayloadException(msg, span.start(), span.end()),
            "E028", DiagnosticBag.ERR_TYPE_MISMATCH);
      }

      return switch (dtSchema.mode()) {
        case OFFSET -> {
          if (rawText.contains("[")) {
            String msg = "Time zone brackets [...] are prohibited in {#offset} " + StvnVocabulary.TYPE_DATE_TIME;
            throw abort(
                msg, span,
                new MalformedPayloadException(msg, span.start(), span.end()),
                "E028", DiagnosticBag.ERR_TYPE_MISMATCH);
          }
          var matcher = DATETIME_OFFSET_PATTERN.matcher(rawText);
          if (!matcher.matches()) {
            String msg = "Invalid {#offset} " + StvnVocabulary.TYPE_DATE_TIME
                + " format; expected \"YYYY-MM-DDTHH:mm:ss(Z|±HH:mm)\"";
            throw abort(
                msg, span,
                new MalformedPayloadException(msg, span.start(), span.end()),
                "E028", DiagnosticBag.ERR_TYPE_MISMATCH);
          }
          String isoText = rawText.substring(1, rawText.length() - 1);
          OffsetDateTime odt;
          try {
            odt = OffsetDateTime.parse(isoText);
          } catch (Exception e) {
            String msg = "Invalid {#offset} " + StvnVocabulary.TYPE_DATE_TIME + " calendar value: " + e.getMessage();
            throw abort(
                msg, span,
                new MalformedPayloadException(msg, span.start(), span.end(), e),
                "E028", DiagnosticBag.ERR_TYPE_MISMATCH);
          }
          String canonicalIso = StvnLiteralParser.formatCanonicalDateTime(
              DateTimeMode.OFFSET,
              odt.toLocalDateTime(),
              Optional.of(odt.getOffset()),
              Optional.empty()
          );
          validateDateTimeBounds(odt.toInstant(), canonicalIso, dtSchema, label, span);
          yield new StvnValue.StvnDateTime(
              dtSchema,
              DateTimeMode.OFFSET,
              odt.toLocalDateTime(),
              Optional.of(odt.getOffset()),
              Optional.empty(),
              canonicalIso
          );
        }
        case ZONED -> {
          if (ZONED_WITH_ILLEGAL_OFFSET_PATTERN.matcher(rawText).matches()
              || DATETIME_OFFSET_PATTERN.matcher(rawText).matches()) {
            String msg = "Explicit UTC offsets (+/-HH:mm or Z) are prohibited in {#zoned} "
                + StvnVocabulary.TYPE_DATE_TIME;
            throw abort(
                msg, span,
                new MalformedPayloadException(msg, span.start(), span.end()),
                "E019", DiagnosticBag.ERR_TEMPORAL_OFFSET_IN_ZONED);
          }
          var matcher = DATETIME_ZONED_PATTERN.matcher(rawText);
          if (!matcher.matches()) {
            String msg = "Invalid {#zoned} " + StvnVocabulary.TYPE_DATE_TIME
                + " format; expected \"YYYY-MM-DDTHH:mm:ss[Region/City]\"";
            throw abort(
                msg, span,
                new MalformedPayloadException(msg, span.start(), span.end()),
                "E028", DiagnosticBag.ERR_TYPE_MISMATCH);
          }
          LocalDateTime ldt;
          ZoneId zoneId;
          try {
            ldt = LocalDateTime.parse(matcher.group(1));
            zoneId = ZoneId.of(matcher.group(2));
          } catch (Exception e) {
            String msg = "Invalid {#zoned} " + StvnVocabulary.TYPE_DATE_TIME + " value: " + e.getMessage();
            throw abort(
                msg, span,
                new MalformedPayloadException(msg, span.start(), span.end(), e),
                "E028", DiagnosticBag.ERR_TYPE_MISMATCH);
          }
          var rules = zoneId.getRules();
          if (rules.getValidOffsets(ldt).isEmpty()) {
            var transition = rules.getTransition(ldt);
            String msg = "Invalid civil time: '" + ldt + "' falls into a DST spring-forward gap in zone '"
                + zoneId + "' (" + transition + ").";
            throw abort(
                msg, span,
                new MalformedPayloadException(msg, span.start(), span.end()),
                "E022", DiagnosticBag.ERR_TEMPORAL_DST_GAP_LITERAL);
          }
          String canonicalIso = StvnLiteralParser.formatCanonicalDateTime(
              DateTimeMode.ZONED,
              ldt,
              Optional.empty(),
              Optional.of(zoneId)
          );
          validateDateTimeBounds(ldt.atZone(zoneId).toInstant(), canonicalIso, dtSchema, label, span);
          yield new StvnValue.StvnDateTime(
              dtSchema,
              DateTimeMode.ZONED,
              ldt,
              Optional.empty(),
              Optional.of(zoneId),
              canonicalIso
          );
        }
        case AUDITED -> {
          var matcher = DATETIME_AUDITED_PATTERN.matcher(rawText);
          if (!matcher.matches()) {
            String msg = "Invalid {#audited} " + StvnVocabulary.TYPE_DATE_TIME
                + " format; expected \"YYYY-MM-DDTHH:mm:ss(Z|±HH:mm)[Region/City]\"";
            throw abort(
                msg, span,
                new MalformedPayloadException(msg, span.start(), span.end()),
                "E028", DiagnosticBag.ERR_TYPE_MISMATCH);
          }
          OffsetDateTime odt;
          ZoneId zoneId;
          try {
            odt = OffsetDateTime.parse(matcher.group(1) + matcher.group(2));
            zoneId = ZoneId.of(matcher.group(3));
          } catch (Exception e) {
            String msg = "Invalid {#audited} " + StvnVocabulary.TYPE_DATE_TIME + " value: " + e.getMessage();
            throw abort(
                msg, span,
                new MalformedPayloadException(msg, span.start(), span.end(), e),
                "E028", DiagnosticBag.ERR_TYPE_MISMATCH);
          }
          LocalDateTime ldt = odt.toLocalDateTime();
          var rules = zoneId.getRules();
          var validOffsets = rules.getValidOffsets(ldt);
          if (validOffsets.isEmpty()) {
            var transition = rules.getTransition(ldt);
            String msg = "Invalid civil time: '" + ldt + "' falls into a DST spring-forward gap in zone '"
                + zoneId + "' (" + transition + ").";
            throw abort(
                msg, span,
                new MalformedPayloadException(msg, span.start(), span.end()),
                "E022", DiagnosticBag.ERR_TEMPORAL_DST_GAP_LITERAL);
          }
          if (!validOffsets.contains(odt.getOffset())) {
            String msg = "Contradictory offset in {#audited} " + StvnVocabulary.TYPE_DATE_TIME
                + " literal: declared offset '" + odt.getOffset() + "' disagrees with valid IANA offset(s) "
                + validOffsets + " for '" + ldt + "' in zone '" + zoneId + "'.";
            throw abort(
                msg, span,
                new MalformedPayloadException(msg, span.start(), span.end()),
                "E021", DiagnosticBag.ERR_TEMPORAL_AUDITED_MISMATCH);
          }
          String canonicalIso = StvnLiteralParser.formatCanonicalDateTime(
              DateTimeMode.AUDITED,
              ldt,
              Optional.of(odt.getOffset()),
              Optional.of(zoneId)
          );
          validateDateTimeBounds(odt.toInstant(), canonicalIso, dtSchema, label, span);
          yield new StvnValue.StvnDateTime(
              dtSchema,
              DateTimeMode.AUDITED,
              ldt,
              Optional.of(odt.getOffset()),
              Optional.of(zoneId),
              canonicalIso
          );
        }
      };
    }

    private void validateDateTimeBounds(
        Instant instant,
        String isoText,
        StvnSchema.DateTimeSchema dtSchema,
        String label,
        Span span
    ) {
      if (dtSchema.explicitMinInclIso().isPresent()) {
        Instant minInst = parseDateTimeBoundInstant("\"" + dtSchema.explicitMinInclIso().get() + "\"", dtSchema.mode());
        if (instant.isBefore(minInst)) {
          String msg = "Constraint violation (" + label + "): Timestamp '" + isoText
              + "' is outside allowable range (before #minIncl '" + dtSchema.explicitMinInclIso().get() + "')";
          throw abort(
              msg, span,
              new MalformedPayloadException(msg, span.start(), span.end()),
              "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION);
        }
      }
      if (dtSchema.explicitMaxExclIso().isPresent()) {
        Instant maxInst = parseDateTimeBoundInstant("\"" + dtSchema.explicitMaxExclIso().get() + "\"", dtSchema.mode());
        if (!instant.isBefore(maxInst)) {
          String msg = "Constraint violation (" + label + "): Timestamp '" + isoText
              + "' is outside allowable range (must be strictly before #maxExcl '"
              + dtSchema.explicitMaxExclIso().get() + "')";
          throw abort(
              msg, span,
              new MalformedPayloadException(msg, span.start(), span.end()),
              "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION);
        }
      }
    }

    private StvnValue lowerListContainer(
        StvnParser.ListLiteralContext ctx,
        StvnSchema containerSchema,
        StvnSchema elementSchema,
        BigInteger minSize,
        BigInteger maxSize,
        boolean isSet,
        String label
    ) {
      Deque<StreamItem> queue = new ArrayDeque<>();
      for (StvnParser.ValueContext child : ctx.value()) {
        queue.addLast(toStreamItem(child));
      }

      List<StvnValue> elements = new ArrayList<>();
      List<Span> elementSpans = new ArrayList<>();
      Set<Object> seenSetKeys = isSet ? new LinkedHashSet<>() : Set.of();

      while (!queue.isEmpty()) {
        StreamItem head = queue.getFirst();
        Span headSpan = head.itemSpan();
        int beforeSize = queue.size();
        try {
          StvnValue val = evaluateNext(queue, elementSchema);
          if (isSet && !(val instanceof StvnErrorValue)) {
            if (!seenSetKeys.add(canonicalEqualityKey(val))) {
              String msg = "Duplicate set element detected";
              StvnDiagnostic dupDiag = recordError(
                  diagnosticBag,
                  msg,
                  headSpan,
                  new StvnCollectionCollisionException(msg, headSpan.start(), headSpan.end()),
                  "E017",
                  DiagnosticBag.ERR_DUPLICATE_SET_ELEMENT,
                  schemaContextMode
              );
              val = new StvnErrorValue(elementSchema, dupDiag);
            }
          }
          elements.add(val);
          elementSpans.add(headSpan);
        } catch (LoweringAbortException e) {
          if (queue.size() == beforeSize) {
            queue.removeFirst();
          }
          elements.add(new StvnErrorValue(elementSchema, e.diagnostic()));
          elementSpans.add(headSpan);
        }
      }

      BigInteger actualCount = BigInteger.valueOf(elements.size());
      if (actualCount.compareTo(minSize) < 0) {
        Span closeSpan = ctx.RBRACK() != null ? Span.ofToken(ctx.RBRACK().getSymbol()) : Span.ofContext(ctx);
        if (actualCount.signum() == 0 && minSize.compareTo(BigInteger.ONE) >= 0) {
          String msg = (isSet ? "Set" : "Sequence")
              + " is marked as non-empty (#minSize " + minSize + ") but contains no elements";
          recordError(
              diagnosticBag, msg, closeSpan,
              new MalformedPayloadException(msg, closeSpan.start(), closeSpan.end()),
              "E017", DiagnosticBag.ERR_COLLECTION_EMPTY_VIOLATION, schemaContextMode);
        } else {
          String msg = "Constraint violation (" + label + "): Size " + actualCount
              + " outside allowable range [" + minSize + ", " + maxSize + "]";
          recordError(
              diagnosticBag, msg, closeSpan,
              new MalformedPayloadException(msg, closeSpan.start(), closeSpan.end()),
              "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION, schemaContextMode);
        }
      } else if (actualCount.compareTo(maxSize) > 0) {
        int excessIdx = maxSize.compareTo(BigInteger.valueOf(elementSpans.size())) < 0
            ? maxSize.intValueExact()
            : 0;
        Span firstExcess = elementSpans.get(excessIdx);
        Span lastExcess = elementSpans.getLast();
        Span excessSlice = new Span(firstExcess.start(), lastExcess.end(), firstExcess.line(), firstExcess.column());
        String msg = "Constraint violation (" + label + "): Size " + actualCount
            + " outside allowable range [" + minSize + ", " + maxSize + "]";
        recordError(
            diagnosticBag, msg, excessSlice,
            new MalformedPayloadException(msg, excessSlice.start(), excessSlice.end()),
            "E012", DiagnosticBag.ERR_CAPACITY_OVERFLOW, schemaContextMode);
      }

      return isSet
          ? new StvnValue.StvnSet(containerSchema, elements)
          : new StvnValue.StvnSeq(containerSchema, elements);
    }

    private StvnValue lowerTupleContainer(
        StvnParser.TupleLiteralContext ctx,
        StvnSchema.TupleSchema tupleSchema
    ) {
      List<StvnSchema> fieldSchemas = tupleSchema.fieldSchemas();
      int expectedArity = fieldSchemas.size();

      Deque<StreamItem> queue = new ArrayDeque<>();
      for (StvnParser.ValueContext child : ctx.value()) {
        queue.addLast(toStreamItem(child));
      }

      List<StvnValue> elements = new ArrayList<>(expectedArity);
      List<Span> excessSpans = new ArrayList<>();
      int slot = 0;

      while (!queue.isEmpty()) {
        StreamItem head = queue.getFirst();
        Span headSpan = head.itemSpan();
        int beforeSize = queue.size();

        if (slot < expectedArity) {
          StvnSchema slotSchema = fieldSchemas.get(slot);
          try {
            StvnValue elVal = evaluateNext(queue, slotSchema);
            elements.add(elVal);
          } catch (LoweringAbortException e) {
            if (queue.size() == beforeSize) {
              queue.removeFirst();
            }
            elements.add(new StvnErrorValue(slotSchema, e.diagnostic()));
          }
        } else {
          // Excess slot (M > N): consume without emitting secondary per-element type errors
          queue.removeFirst();
          excessSpans.add(headSpan);
        }
        slot++;
      }

      if (slot < expectedArity) {
        Span closeSpan = ctx.RPAREN() != null ? Span.ofToken(ctx.RPAREN().getSymbol()) : Span.ofContext(ctx);
        String msg = "Tuple arity mismatch: Expected " + expectedArity + " elements, got " + slot;
        recordError(
            diagnosticBag, msg, closeSpan,
            new MalformedPayloadException(msg, closeSpan.start(), closeSpan.end()),
            "E028", DiagnosticBag.ERR_TUPLE_ARITY_MISMATCH, schemaContextMode);
      } else if (slot > expectedArity && !excessSpans.isEmpty()) {
        Span firstExcess = excessSpans.getFirst();
        Span lastExcess = excessSpans.getLast();
        Span excessSlice = new Span(firstExcess.start(), lastExcess.end(), firstExcess.line(), firstExcess.column());
        String msg = "Tuple arity mismatch: Expected " + expectedArity + " elements, got " + slot;
        recordError(
            diagnosticBag, msg, excessSlice,
            new MalformedPayloadException(msg, excessSlice.start(), excessSlice.end()),
            "E028", DiagnosticBag.ERR_TUPLE_ARITY_MISMATCH, schemaContextMode);
      }

      return new StvnValue.StvnTuple(tupleSchema, elements);
    }

    private StvnValue lowerMapContainer(
        StvnParser.MapLiteralContext ctx,
        StvnSchema.MapSchema mapSchema,
        String label
    ) {
      List<KeyValueEntry> entries = new ArrayList<>(ctx.mapEntry().size());
      List<Span> entrySpans = new ArrayList<>(ctx.mapEntry().size());
      Set<Object> seenKeys = new LinkedHashSet<>();
      Set<Object> seenValues = mapSchema.invertible() ? new LinkedHashSet<>() : Set.of();

      for (StvnParser.MapEntryContext entryCtx : ctx.mapEntry()) {
        Span entrySpan = Span.ofContext(entryCtx);
        entrySpans.add(entrySpan);

        if (entryCtx.exception != null || entryCtx.value().size() < 2
            || isMalformedSyntaxContext(entryCtx.value(0))
            || isMalformedSyntaxContext(entryCtx.value(1))) {
          String msg = "STVN Syntax Error: map entry requires '[ key value ]' pair";
          StvnDiagnostic diag = recordError(
              diagnosticBag, msg, entrySpan,
              new MalformedPayloadException(msg, entrySpan.start(), entrySpan.end()),
              "E002", "STVN_SYNTAX_ERROR", schemaContextMode);
          entries.add(new KeyValueEntry(
              new StvnErrorValue(mapSchema.keySchema(), diag),
              new StvnErrorValue(mapSchema.valueSchema(), diag)
          ));
          continue;
        }

        StvnParser.ValueContext keyCtx = entryCtx.value(0);
        StvnParser.ValueContext valCtx = entryCtx.value(1);

        StvnValue loweredKey;
        try {
          Deque<StreamItem> kq = new ArrayDeque<>();
          kq.addLast(toStreamItem(keyCtx));
          loweredKey = evaluateNext(kq, mapSchema.keySchema());
        } catch (LoweringAbortException e) {
          loweredKey = new StvnErrorValue(mapSchema.keySchema(), e.diagnostic());
        }

        StvnValue loweredVal;
        try {
          Deque<StreamItem> vq = new ArrayDeque<>();
          vq.addLast(toStreamItem(valCtx));
          loweredVal = evaluateNext(vq, mapSchema.valueSchema());
        } catch (LoweringAbortException e) {
          loweredVal = new StvnErrorValue(mapSchema.valueSchema(), e.diagnostic());
        }

        if (!(loweredKey instanceof StvnErrorValue)) {
          if (!seenKeys.add(canonicalEqualityKey(loweredKey))) {
            Span keySpan = Span.ofContext(keyCtx);
            String msg = "Duplicate map key detected";
            recordError(
                diagnosticBag, msg, keySpan,
                new StvnCollectionCollisionException(msg, keySpan.start(), keySpan.end()),
                "E017", DiagnosticBag.ERR_DUPLICATE_MAP_KEY, schemaContextMode);
          }
        }

        if (mapSchema.invertible() && !(loweredVal instanceof StvnErrorValue)) {
          if (!seenValues.add(canonicalEqualityKey(loweredVal))) {
            Span valSpan = Span.ofContext(valCtx);
            String msg = "Duplicate inverted map value detected";
            recordError(
                diagnosticBag, msg, valSpan,
                new StvnCollectionCollisionException(msg, valSpan.start(), valSpan.end()),
                "E018", DiagnosticBag.ERR_DUPLICATE_INVERTED_MAP_VALUE, schemaContextMode);
          }
        }

        entries.add(new KeyValueEntry(loweredKey, loweredVal));
      }

      BigInteger actualCount = BigInteger.valueOf(entries.size());
      BigInteger minSize = mapSchema.minSize();
      BigInteger maxSize = mapSchema.maxSize();

      if (actualCount.compareTo(minSize) < 0) {
        Span closeSpan = ctx.RBRACE() != null ? Span.ofToken(ctx.RBRACE().getSymbol()) : Span.ofContext(ctx);
        if (actualCount.signum() == 0 && minSize.compareTo(BigInteger.ONE) >= 0) {
          String msg = (mapSchema.invertible() ? "Invertible map" : "Map")
              + " is marked as non-empty (#minSize " + minSize + ") but contains no elements";
          recordError(
              diagnosticBag, msg, closeSpan,
              new MalformedPayloadException(msg, closeSpan.start(), closeSpan.end()),
              "E017", DiagnosticBag.ERR_COLLECTION_EMPTY_VIOLATION, schemaContextMode);
        } else {
          String msg = "Constraint violation (" + label + "): Size " + actualCount
              + " outside allowable range [" + minSize + ", " + maxSize + "]";
          recordError(
              diagnosticBag, msg, closeSpan,
              new MalformedPayloadException(msg, closeSpan.start(), closeSpan.end()),
              "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION, schemaContextMode);
        }
      } else if (actualCount.compareTo(maxSize) > 0 && !entrySpans.isEmpty()) {
        int excessIdx = maxSize.compareTo(BigInteger.valueOf(entrySpans.size())) < 0
            ? maxSize.intValueExact()
            : 0;
        Span firstExcess = entrySpans.get(excessIdx);
        Span lastExcess = entrySpans.getLast();
        Span excessSlice = new Span(firstExcess.start(), lastExcess.end(), firstExcess.line(), firstExcess.column());
        String msg = "Constraint violation (" + label + "): Size " + actualCount
            + " outside allowable range [" + minSize + ", " + maxSize + "]";
        recordError(
            diagnosticBag, msg, excessSlice,
            new MalformedPayloadException(msg, excessSlice.start(), excessSlice.end()),
            "E012", DiagnosticBag.ERR_CAPACITY_OVERFLOW, schemaContextMode);
      }

      return new StvnValue.StvnMap(mapSchema, entries);
    }
  }
}
