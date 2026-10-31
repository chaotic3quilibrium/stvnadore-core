package org.stvnadore.core.lowering;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnSchema.DateTimeMode;
import org.stvnadore.core.ir.StvnValue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Stateless utility for parsing and formatting pure STVN 2.0.0 scalar and temporal literals.
 * <p>
 * Supports decimal, hexadecimal ({@code 0x}), binary ({@code 0b}), and octal ({@code 0o}) integer
 * radixes into {@link BigInteger}, arbitrary-precision decimals into {@link BigDecimal},
 * Rule STR-04 fenced and simple strings, and tripartite ISO-8601 {@code :DateTime} literals with
 * mandatory {@code HH:mm:ss} seconds preservation and minimal non-zero fractional digits.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnLiteralParser {

  /**
   * Strict positive character class pattern for Rule STR-04 fenced string language tags.
   */
  public static final Pattern FENCE_TAG_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,256}$");

  /**
   * Strict regex pattern for {@code { #offset } :DateTime} literals:
   * ISO-8601 timestamp with explicit UTC offset ({@code Z} or {@code ±HH:mm}) and no zone brackets.
   */
  public static final Pattern DATETIME_OFFSET_PATTERN = Pattern.compile(
      "^\"([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]+)?)?)(Z|[+-][0-9]{2}:[0-9]{2})\"$"
  );

  /**
   * Strict regex pattern for {@code { #zoned } :DateTime} literals:
   * ISO-8601 local timestamp with bracketed IANA zone ID and no numerical offset.
   */
  public static final Pattern DATETIME_ZONED_PATTERN = Pattern.compile(
      "^\"([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]+)?)?)\\[([A-Za-z0-9_\\-+]+(/[A-Za-z0-9_\\-+]+)*)\\]\"$"
  );

  /**
   * Strict regex pattern for {@code { #audited } :DateTime} literals:
   * ISO-8601 timestamp with explicit UTC offset ({@code Z} or {@code ±HH:mm}) and bracketed IANA zone ID.
   */
  public static final Pattern DATETIME_AUDITED_PATTERN = Pattern.compile(
      "^\"([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]+)?)?)(Z|[+-][0-9]{2}:[0-9]{2})\\[([A-Za-z0-9_\\-+]+(/[A-Za-z0-9_\\-+]+)*)\\]\"$"
  );

  /**
   * Structural delimiter style of a parsed STVN string literal.
   *
   * @since 2.0.0
   */
  public enum StringStyle {
    /** Single-line double-quoted escaped string literal. */
    SIMPLE,
    /** Multi-line Rule STR-04 fenced verbatim string literal ({@code """[TAG]\n...\n[TAG]"""}). */
    FENCED
  }

  /**
   * Container holding parsed string contents, delimiter style, and optional Rule STR-04 fence tag.
   *
   * @param text             the unwrapped string value
   * @param style            the delimiter style ({@link StringStyle#SIMPLE} or {@link StringStyle#FENCED})
   * @param optionalFenceTag the optional Rule STR-04 fence tag discriminator
   * @since 2.0.0
   */
  public record ParsedString(
      String text,
      StringStyle style,
      Optional<String> optionalFenceTag
  ) {
    /**
     * Canonical constructor verifying non-null components.
     *
     * @param text             the unwrapped string value
     * @param style            the delimiter style
     * @param optionalFenceTag the optional fence tag
     */
    public ParsedString {
      Objects.requireNonNull(text, "text must not be null");
      Objects.requireNonNull(style, "style must not be null");
      Objects.requireNonNull(optionalFenceTag, "optionalFenceTag must not be null");
    }
  }

  /**
   * Container for parsed {@code { #offset } :DateTime} literal details.
   *
   * @param value   the parsed {@link OffsetDateTime}
   * @param rawText the canonical unquoted ISO-8601 literal text
   * @since 2.0.0
   */
  public record ParsedDateTimeOffset(
      OffsetDateTime value,
      String rawText
  ) {
    /**
     * Canonical constructor validating non-null parameters.
     *
     * @param value   the parsed offset date-time
     * @param rawText the canonical unquoted literal text
     */
    public ParsedDateTimeOffset {
      Objects.requireNonNull(value, "value must not be null");
      Objects.requireNonNull(rawText, "rawText must not be null");
    }
  }

  /**
   * Container for parsed {@code { #zoned } :DateTime} literal details.
   *
   * @param localDateTime the parsed civil {@link LocalDateTime}
   * @param zoneId        the parsed IANA {@link ZoneId}
   * @param rawText       the canonical unquoted ISO-8601 literal text
   * @since 2.0.0
   */
  public record ParsedDateTimeZoned(
      LocalDateTime localDateTime,
      ZoneId zoneId,
      String rawText
  ) {
    /**
     * Canonical constructor validating non-null parameters.
     *
     * @param localDateTime the parsed local civil date-time
     * @param zoneId        the parsed IANA zone identifier
     * @param rawText       the canonical unquoted literal text
     */
    public ParsedDateTimeZoned {
      Objects.requireNonNull(localDateTime, "localDateTime must not be null");
      Objects.requireNonNull(zoneId, "zoneId must not be null");
      Objects.requireNonNull(rawText, "rawText must not be null");
    }
  }

  /**
   * Container for parsed {@code { #audited } :DateTime} literal details.
   *
   * @param offsetDateTime the parsed {@link OffsetDateTime}
   * @param zoneId         the parsed IANA {@link ZoneId}
   * @param rawText        the canonical unquoted ISO-8601 literal text
   * @since 2.0.0
   */
  public record ParsedDateTimeAudited(
      OffsetDateTime offsetDateTime,
      ZoneId zoneId,
      String rawText
  ) {
    /**
     * Canonical constructor validating non-null parameters.
     *
     * @param offsetDateTime the parsed offset date-time
     * @param zoneId         the parsed IANA zone identifier
     * @param rawText        the canonical unquoted literal text
     */
    public ParsedDateTimeAudited {
      Objects.requireNonNull(offsetDateTime, "offsetDateTime must not be null");
      Objects.requireNonNull(zoneId, "zoneId must not be null");
      Objects.requireNonNull(rawText, "rawText must not be null");
    }
  }

  private StvnLiteralParser() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Parses an STVN union variant tag prefix (e.g. {@code "#1"}, {@code "#2"}) into its 1-based
   * numeric index, strictly enforcing Rule G lexical bounds.
   *
   * @param rawText the raw string representation of the union tag token
   * @return the 1-based integer index
   * @throws IllegalArgumentException if {@code rawText} does not match pattern {@code #[1-9][0-9]*}
   */
  public static int parseUnionTagIndex(@Nullable String rawText) {
    if (rawText == null || rawText.length() < 2 || !rawText.startsWith(StvnVocabulary.SIGIL_VALUE)) {
      throw new IllegalArgumentException("Rule G violation: Invalid union tag literal '" + rawText + "'");
    }
    int val = 0;
    for (int i = 1; i < rawText.length(); i++) {
      char c = rawText.charAt(i);
      if (c < '0' || c > '9') {
        throw new IllegalArgumentException("Rule G violation: Non-digit character in union tag '" + rawText + "'");
      }
      val = val * 10 + (c - '0');
    }
    if (val <= 0) {
      throw new IllegalArgumentException("Rule G violation: Union tag index must be positive (got #" + val + ")");
    }
    return val;
  }

  /**
   * Checks whether a raw integer literal token string represents negative zero.
   *
   * @param rawText the raw string representation of the integer literal
   * @return {@code true} if the literal represents negative zero; {@code false} otherwise
   */
  public static boolean isNegativeZero(@Nullable String rawText) {
    if (rawText == null || !rawText.startsWith("-")) {
      return false;
    }
    return StvnPayloadLowerer.isNegativeZeroInteger(rawText);
  }

  /**
   * Parses an STVN integer literal (decimal, hex, binary, or octal) into a {@link BigInteger}.
   *
   * @param rawText the raw integer literal text
   * @return the parsed {@link BigInteger}
   */
  public static BigInteger parseBigInteger(String rawText) {
    Objects.requireNonNull(rawText, "rawText must not be null");
    return StvnPayloadLowerer.parseIntegerLiteral(rawText);
  }

  /**
   * Parses an STVN integer literal into a 64-bit {@code long} primitive.
   *
   * @param rawText the raw integer literal text
   * @return the parsed {@code long} value
   */
  public static long parseLong(String rawText) {
    return parseBigInteger(rawText).longValue();
  }

  /**
   * Parses an STVN float or scientific literal into a {@link BigDecimal}.
   *
   * @param rawText the raw float literal text
   * @return the parsed {@link BigDecimal}
   */
  public static BigDecimal parseBigDecimal(String rawText) {
    Objects.requireNonNull(rawText, "rawText must not be null");
    return new BigDecimal(rawText);
  }

  /**
   * Unwraps an STVN string literal (simple quoted or Rule STR-04 fenced), applying margin normalization
   * on fenced strings when {@code preserveIndent} is {@code false}.
   *
   * @param rawText        the raw STVN string literal including quotes or fences
   * @param preserveIndent {@code true} to retain leading margin whitespace; {@code false} to strip common indent
   * @return the unwrapped UTF-8 string value
   */
  public static String parseString(String rawText, boolean preserveIndent) {
    return parseStringNew(rawText, preserveIndent).text();
  }

  /**
   * Parses an STVN string literal into a structured {@link ParsedString} record.
   *
   * @param rawText        the raw STVN string literal including quotes or fences
   * @param preserveIndent {@code true} to retain leading margin whitespace; {@code false} to strip common indent
   * @return the parsed {@link ParsedString}
   */
  public static ParsedString parseStringNew(String rawText, boolean preserveIndent) {
    Objects.requireNonNull(rawText, "rawText must not be null");
    if (rawText.startsWith("\"\"\"")) {
      if (rawText.startsWith("\"\"\"->[")) {
        throw new IllegalArgumentException("Rule STR-04 violation: Fenced string delimiter arrow '->' is prohibited");
      }
      if (!rawText.startsWith("\"\"\"[")) {
        throw new IllegalArgumentException(
            "Rule STR-04 violation: Unfenced block strings are prohibited; multi-line strings must use fenced syntax \"\"\"[TAG]"
        );
      }
      int newlineIndex = rawText.indexOf('\n');
      if (newlineIndex == -1) {
        return new ParsedString("", StringStyle.SIMPLE, Optional.empty());
      }
      int tagStart = rawText.indexOf('[');
      int tagEnd = rawText.indexOf(']', tagStart);
      if (tagStart == -1 || tagEnd == -1 || tagEnd <= tagStart) {
        throw new IllegalArgumentException("Rule STR-04 violation: Malformed opening fence delimiter");
      }
      String openingTag = rawText.substring(tagStart + 1, tagEnd);
      validateFenceTag(openingTag);
      String expectedClosing = "[" + openingTag + "]\"\"\"";
      if (!rawText.endsWith(expectedClosing) || newlineIndex + 1 > rawText.length() - expectedClosing.length()) {
        throw new IllegalArgumentException(
            "Rule STR-04 violation: Missing or mismatched closing fence delimiter '" + expectedClosing + "'"
        );
      }
      String payload = rawText.substring(newlineIndex + 1, rawText.length() - expectedClosing.length());
      String lfNormalized = payload.indexOf('\r') >= 0 ? payload.replace("\r\n", "\n") : payload;
      String resolvedPayload = preserveIndent ? lfNormalized : trimIndent(lfNormalized);
      return new ParsedString(resolvedPayload, StringStyle.FENCED, Optional.of(openingTag));
    }
    if (rawText.startsWith("\"") && rawText.endsWith("\"") && rawText.length() >= 2) {
      String decoded = StvnPayloadLowerer.decodeStringLiteral(
          rawText, true, new StvnPayloadLowerer.Span(0, rawText.length(), 1, 0)
      );
      return new ParsedString(decoded, StringStyle.SIMPLE, Optional.empty());
    }
    return new ParsedString(rawText, StringStyle.SIMPLE, Optional.empty());
  }

  /**
   * Validates that a fenced string language discriminator tag complies with Rule STR-04.
   *
   * @param tag the tag string to validate
   * @throws IllegalArgumentException if the tag violates Rule STR-04
   */
  public static void validateFenceTag(String tag) {
    Objects.requireNonNull(tag, "tag must not be null");
    if (tag.isEmpty()) {
      throw new IllegalArgumentException("Rule STR-04 violation: Fenced string delimiter tag must not be empty");
    }
    if (tag.contains(" ") || tag.contains("\t") || tag.contains("\r") || tag.contains("\n")) {
      throw new IllegalArgumentException(
          "Rule STR-04 violation: Fenced string delimiter tag must not contain whitespace, got '" + tag + "'"
      );
    }
    if (tag.length() > 256) {
      throw new IllegalArgumentException(
          "Rule STR-04 violation: Fenced string delimiter tag length exceeds maximum of 256 characters, got "
              + tag.length()
      );
    }
    if (!FENCE_TAG_PATTERN.matcher(tag).matches()) {
      throw new IllegalArgumentException(
          "Rule STR-04 violation: Fenced string delimiter tag '" + tag
              + "' contains invalid characters; must match positive character class ^[a-zA-Z0-9_-]{1,256}$"
      );
    }
  }

  /**
   * Removes the common leading whitespace margin from a multi-line string block.
   *
   * @param text the raw multi-line string text block
   * @return the baseline-trimmed string block
   */
  public static String trimIndent(String text) {
    Objects.requireNonNull(text, "text must not be null");
    return StvnPayloadLowerer.trimCommonIndent(text);
  }

  /**
   * Parses and validates a double-quoted {@code { #offset } :DateTime} literal token.
   *
   * @param rawLiteral the double-quoted STVN literal text
   * @return the parsed {@link ParsedDateTimeOffset}
   * @throws IllegalArgumentException if the literal does not match the strict offset pattern
   */
  public static ParsedDateTimeOffset parseDateTimeOffset(String rawLiteral) {
    Objects.requireNonNull(rawLiteral, "rawLiteral must not be null");
    var matcher = DATETIME_OFFSET_PATTERN.matcher(rawLiteral);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Literal does not match :DateTimeOffset format: " + rawLiteral);
    }
    String isoText = rawLiteral.substring(1, rawLiteral.length() - 1);
    OffsetDateTime odt = OffsetDateTime.parse(isoText);
    String canonical = formatCanonicalDateTime(
        DateTimeMode.OFFSET, odt.toLocalDateTime(), Optional.of(odt.getOffset()), Optional.empty()
    );
    return new ParsedDateTimeOffset(odt, canonical);
  }

  /**
   * Parses and validates a double-quoted {@code { #zoned } :DateTime} literal token.
   *
   * @param rawLiteral the double-quoted STVN literal text
   * @return the parsed {@link ParsedDateTimeZoned}
   * @throws IllegalArgumentException if the literal does not match the strict zoned pattern
   */
  public static ParsedDateTimeZoned parseDateTimeZoned(String rawLiteral) {
    Objects.requireNonNull(rawLiteral, "rawLiteral must not be null");
    var matcher = DATETIME_ZONED_PATTERN.matcher(rawLiteral);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Literal does not match :DateTimeZoned format: " + rawLiteral);
    }
    LocalDateTime ldt = LocalDateTime.parse(matcher.group(1));
    ZoneId zoneId = ZoneId.of(matcher.group(2));
    String canonical = formatCanonicalDateTime(
        DateTimeMode.ZONED, ldt, Optional.empty(), Optional.of(zoneId)
    );
    return new ParsedDateTimeZoned(ldt, zoneId, canonical);
  }

  /**
   * Parses and validates a double-quoted {@code { #audited } :DateTime} literal token.
   *
   * @param rawLiteral the double-quoted STVN literal text
   * @return the parsed {@link ParsedDateTimeAudited}
   * @throws IllegalArgumentException if the literal does not match the strict audited pattern
   */
  public static ParsedDateTimeAudited parseDateTimeAudited(String rawLiteral) {
    Objects.requireNonNull(rawLiteral, "rawLiteral must not be null");
    var matcher = DATETIME_AUDITED_PATTERN.matcher(rawLiteral);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Literal does not match :DateTimeAudited format: " + rawLiteral);
    }
    OffsetDateTime odt = OffsetDateTime.parse(matcher.group(1) + matcher.group(2));
    ZoneId zoneId = ZoneId.of(matcher.group(3));
    String canonical = formatCanonicalDateTime(
        DateTimeMode.AUDITED, odt.toLocalDateTime(), Optional.of(odt.getOffset()), Optional.of(zoneId)
    );
    return new ParsedDateTimeAudited(odt, zoneId, canonical);
  }

  /**
   * Formats a pure-IR {@link StvnValue.StvnDateTime} into its canonical ISO-8601 string,
   * always preserving {@code :00} seconds ({@code HH:mm:ss}) and emitting minimal non-zero
   * sub-second fractional digits.
   *
   * @param dt the lowered {@link StvnValue.StvnDateTime} value
   * @return the canonical unquoted ISO-8601 string representation
   */
  public static String formatCanonicalDateTime(StvnValue.StvnDateTime dt) {
    Objects.requireNonNull(dt, "dt must not be null");
    return formatCanonicalDateTime(dt.mode(), dt.localWallClock(), dt.offset(), dt.zone());
  }

  /**
   * Formats tripartite temporal components into canonical ISO-8601 text, always preserving
   * {@code HH:mm:ss} seconds (including {@code :00}) and appending minimal non-zero fractional digits.
   *
   * @param mode           the tripartite temporal mode ({@code OFFSET}, {@code ZONED}, or {@code AUDITED})
   * @param localWallClock the civil wall-clock {@link LocalDateTime}
   * @param offset         the UTC {@link ZoneOffset} when {@code mode} is {@code OFFSET} or {@code AUDITED}
   * @param zone           the IANA {@link ZoneId} when {@code mode} is {@code ZONED} or {@code AUDITED}
   * @return the canonical unquoted ISO-8601 string
   */
  public static String formatCanonicalDateTime(
      DateTimeMode mode,
      LocalDateTime localWallClock,
      Optional<ZoneOffset> offset,
      Optional<ZoneId> zone
  ) {
    Objects.requireNonNull(mode, "mode must not be null");
    Objects.requireNonNull(localWallClock, "localWallClock must not be null");
    Objects.requireNonNull(offset, "offset must not be null");
    Objects.requireNonNull(zone, "zone must not be null");

    StringBuilder sb = new StringBuilder(40);
    int year = localWallClock.getYear();
    if (year < 0 || year > 9999) {
      sb.append(String.format(Locale.ROOT, "%+05d", year));
    } else {
      sb.append(String.format(Locale.ROOT, "%04d", year));
    }
    sb.append(String.format(
        Locale.ROOT,
        "-%02d-%02dT%02d:%02d:%02d",
        localWallClock.getMonthValue(),
        localWallClock.getDayOfMonth(),
        localWallClock.getHour(),
        localWallClock.getMinute(),
        localWallClock.getSecond()
    ));

    int nano = localWallClock.getNano();
    if (nano != 0) {
      String frac = String.format(Locale.ROOT, "%09d", nano);
      int end = frac.length();
      while (end > 1 && frac.charAt(end - 1) == '0') {
        end--;
      }
      sb.append('.').append(frac, 0, end);
    }

    if (mode == DateTimeMode.OFFSET || mode == DateTimeMode.AUDITED) {
      ZoneOffset resolvedOffset = offset.orElse(ZoneOffset.UTC);
      sb.append(resolvedOffset.getTotalSeconds() == 0 ? "Z" : resolvedOffset.getId());
    }
    if (mode == DateTimeMode.ZONED || mode == DateTimeMode.AUDITED) {
      sb.append('[').append(zone.orElseThrow().getId()).append(']');
    }
    return sb.toString();
  }

  /**
   * Selects a collision-free Rule STR-04 fence tag ({@code RAW}, {@code RAW_1}, ...) that does not
   * appear inside {@code text} as a closing fence delimiter {@code [TAG]"""}.
   *
   * @param text the verbatim string body to inspect
   * @return a safe alphanumeric/underscore fence tag matching {@link #FENCE_TAG_PATTERN}
   */
  public static String chooseSafeFenceTag(String text) {
    Objects.requireNonNull(text, "text must not be null");
    String candidate = "RAW";
    int suffix = 0;
    while (text.contains("[" + candidate + "]\"\"\"")) {
      suffix++;
      candidate = "RAW_" + suffix;
    }
    return candidate;
  }
}
