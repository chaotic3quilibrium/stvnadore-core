package org.stvnadore.core.ir;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A utility class responsible for extracting pure Java values from raw STVN literal strings.
 * <p>
 * This class handles radix conversions (e.g. hex {@code 0x}, octal {@code 0o}, binary {@code 0b},
 * and decimal) for integer literals, mapping raw string tokens into native Java boxed primitives
 * like {@link Long} and {@link BigInteger} without sacrificing sign integrity or precision boundaries.
 * <p>
 * Note: This class assumes the input text has already been parsed and is guaranteed to be
 * structurally valid.
 *
 * @since 1.0.0
 */
public final class StvnLiteralParser {

  private StvnLiteralParser() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  private record ParseInteger(
      boolean isNegative,
      String absoluteValueAsText,
      int radix
  ) {
    public static ParseInteger from(String rawText) {
      var isNegative = rawText.startsWith("-");
      var absoluteValueAsText = isNegative
          ? rawText.substring(1)
          : rawText;
      var radix = absoluteValueAsText.length() < 3
          ? 10
          : switch (absoluteValueAsText.substring(0, 2).toUpperCase()) {
            case "0X" -> 16;
            case "0B" -> 2;
            case "0O" -> 8;
            default -> 10;
          };

      return new ParseInteger(
          isNegative,
          radix == 10
              ? absoluteValueAsText
              : absoluteValueAsText.substring(2),
          radix);
    }

    public BigInteger toBigInteger() {
      var big = new BigInteger(absoluteValueAsText, radix);
      return isNegative
          ? big.negate()
          : big;
    }

    public long toLong() {
      var val = Long.parseLong(absoluteValueAsText, radix);
      return isNegative
          ? -val
          : val;
    }
  }

  /**
   * Parses an STVN integer literal (handling hex, octal, binary, or decimal radixes)
   * into a {@link BigInteger}.
   *
   * @param rawText the raw string representation of the integer literal
   * @return the resolved {@link BigInteger} value
   */
  public static BigInteger parseBigInteger(String rawText) {
    return ParseInteger.from(rawText).toBigInteger();
  }

  /**
   * Parses an STVN integer literal (handling hex, octal, binary, or decimal radixes)
   * into a {@code long} primitive.
   *
   * @param rawText the raw string representation of the integer literal
   * @return the resolved {@code long} value
   */
  public static long parseLong(String rawText) {
    return ParseInteger.from(rawText).toLong();
  }

  /**
   * Parses an STVN float literal into a {@link BigDecimal}.
   *
   * @param rawText the raw string representation of the float literal
   * @return the resolved {@link BigDecimal} value
   */
  public static BigDecimal parseFloat(String rawText) {
    return new BigDecimal(rawText);
  }

  /**
   * A container holding the successfully parsed string contents, styling information,
   * and optional fence tags.
   *
   * @param text               the unwrapped, formatted string value
   * @param style              the structural style (simple, block, or fenced) of the source literal
   * @param optionalFenceTag   the optional fence tag metadata associated with fenced strings
   */
  public record ParsedString(
      String text,
      StvnValue.StringStyle style,
      Optional<String> optionalFenceTag
  ) {
  }

  /**
   * Safely unwraps an STVN string literal, handling simple quotes, block strings,
   * and fenced strings, while optionally trimming the structural indent.
   *
   * @param rawText        the raw string representation of the STVN literal
   * @param preserveIndent if {@code true}, indentation is preserved; otherwise, common leading margin is trimmed
   * @return the resolved unwrapped string value
   */
  public static String parseString(String rawText, boolean preserveIndent) {
    return parseStringNew(rawText, preserveIndent).text();
  }

  /**
   * Safely parses an STVN string literal into a structured {@link ParsedString} record.
   *
   * @param rawText        the raw string representation of the STVN literal
   * @param preserveIndent if {@code true}, indentation is preserved; otherwise, common leading margin is trimmed
   * @return the resolved {@link ParsedString} record containing text, style, and tag details
   */
  public static ParsedString parseStringNew(String rawText, boolean preserveIndent) {
    if (rawText.startsWith("\"\"\"")) {
      boolean isFenced = rawText.startsWith("\"\"\"->[");
      int newlineIndex = rawText.indexOf('\n');

      // Failsafe: if somehow a single-line block string snuck past the validator
      if (newlineIndex == -1)
        return new ParsedString(
            "",
            StvnValue.StringStyle.SIMPLE,
            Optional.empty());

      String openingTail = rawText.substring(3 + (isFenced
          ? 2
          : 0), newlineIndex).trim();
      String payload;

      Optional<String> optionalOpeningTag = Optional.empty();
      if (isFenced) {
        String openingTag = openingTail.substring(1, openingTail.length() - 1);
        optionalOpeningTag = Optional.of(openingTag);
        String expectedClosing = "[" + openingTag + "]\"\"\"";
        payload = rawText.substring(newlineIndex + 1, rawText.length() - expectedClosing.length());
      } else {
        payload = rawText.substring(newlineIndex + 1, rawText.length() - 3);
      }
      var resolvedPayload = preserveIndent
          ? payload
          : trimIndent(payload);
      var resolvedStyle = isFenced
          ? StvnValue.StringStyle.FENCED
          : StvnValue.StringStyle.BLOCK;

      return new ParsedString(resolvedPayload, resolvedStyle, optionalOpeningTag);
    } else if (rawText.startsWith("\"") && rawText.length() >= 2) {
      return new ParsedString(
          rawText.substring(1, rawText.length() - 1),
          StvnValue.StringStyle.SIMPLE,
          Optional.empty());
    }

    return new ParsedString(
        rawText,
        StvnValue.StringStyle.SIMPLE,
        Optional.empty());
  }

  /**
   * Removes the common leading whitespace margin from a multi-line string block.
   *
   * @param text the raw multi-line string text block
   * @return the trimmed string block
   */
  public static String trimIndent(String text) {
    if (text.isEmpty()) return text;
    String[] lines = text.split("\n", -1);
    int minIndent = Integer.MAX_VALUE;
    boolean hasNonEmptyLine = false;

    for (String line : lines) {
      if (line.trim().isEmpty()) continue;
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

    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      sb.append(line.trim().isEmpty()
          ? ""
          : line.substring(minIndent));
      if (i < lines.length - 1) sb.append("\n");
    }
    return sb.toString();
  }

  // ============================================================================
  // TRIPARTITE TEMPORAL LITERAL PARSING
  // ============================================================================

  /**
   * Strict regex pattern for {@code :DateTimeOffset} literals:
   * ISO-8601 timestamp with explicit UTC offset ({@code Z} or {@code ±HH:mm}) and no zone brackets.
   */
  public static final Pattern DATETIME_OFFSET_PATTERN = Pattern.compile(
      "^\"([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]+)?)?)(Z|[+-][0-9]{2}:[0-9]{2})\"$"
  );

  /**
   * Strict regex pattern for {@code :DateTimeZoned} literals:
   * ISO-8601 local timestamp with bracketed IANA zone ID and no numerical offset.
   */
  public static final Pattern DATETIME_ZONED_PATTERN = Pattern.compile(
      "^\"([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]+)?)?)\\[([A-Za-z0-9_\\-+]+(/[A-Za-z0-9_\\-+]+)*)\\]\"$"
  );

  /**
   * Strict regex pattern for {@code :DateTimeAudited} literals:
   * ISO-8601 timestamp with explicit UTC offset ({@code Z} or {@code ±HH:mm}) AND bracketed IANA zone ID.
   */
  public static final Pattern DATETIME_AUDITED_PATTERN = Pattern.compile(
      "^\"([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]+)?)?)(Z|[+-][0-9]{2}:[0-9]{2})\\[([A-Za-z0-9_\\-+]+(/[A-Za-z0-9_\\-+]+)*)\\]\"$"
  );

  /**
   * Container for parsed {@code :DateTimeOffset} literal details.
   *
   * @param value   the parsed {@link OffsetDateTime}
   * @param rawText the unquoted raw literal text
   */
  public record ParsedDateTimeOffset(
      OffsetDateTime value,
      String rawText
  ) {
    /**
     * Canonical constructor validating non-null parameters.
     *
     * @param value the parsed offset date-time
     * @param rawText the unquoted raw literal text
     */
    public ParsedDateTimeOffset {
      java.util.Objects.requireNonNull(value);
      java.util.Objects.requireNonNull(rawText);
    }
  }

  /**
   * Container for parsed {@code :DateTimeZoned} literal details.
   *
   * @param localDateTime the parsed civil {@link LocalDateTime}
   * @param zoneId        the parsed IANA {@link ZoneId}
   * @param rawText       the unquoted raw literal text
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
     * @param zoneId the parsed IANA zone identifier
     * @param rawText the unquoted raw literal text
     */
    public ParsedDateTimeZoned {
      java.util.Objects.requireNonNull(localDateTime);
      java.util.Objects.requireNonNull(zoneId);
      java.util.Objects.requireNonNull(rawText);
    }
  }

  /**
   * Container for parsed {@code :DateTimeAudited} literal details.
   *
   * @param offsetDateTime the parsed {@link OffsetDateTime}
   * @param zoneId         the parsed IANA {@link ZoneId}
   * @param rawText        the unquoted raw literal text
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
     * @param zoneId the parsed IANA zone identifier
     * @param rawText the unquoted raw literal text
     */
    public ParsedDateTimeAudited {
      java.util.Objects.requireNonNull(offsetDateTime);
      java.util.Objects.requireNonNull(zoneId);
      java.util.Objects.requireNonNull(rawText);
    }
  }

  /**
   * Parses and validates a {@code :DateTimeOffset} literal string token.
   *
   * @param rawLiteral the double-quoted STVN literal text
   * @return the parsed {@link ParsedDateTimeOffset} container
   * @throws IllegalArgumentException if the literal does not match the strict offset pattern or cannot be parsed
   */
  public static ParsedDateTimeOffset parseDateTimeOffset(String rawLiteral) {
    var matcher = DATETIME_OFFSET_PATTERN.matcher(rawLiteral);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Literal does not match :DateTimeOffset format: " + rawLiteral);
    }
    var isoText = rawLiteral.substring(1, rawLiteral.length() - 1);
    var odt = OffsetDateTime.parse(isoText);
    return new ParsedDateTimeOffset(odt, isoText);
  }

  /**
   * Parses and validates a {@code :DateTimeZoned} literal string token.
   *
   * @param rawLiteral the double-quoted STVN literal text
   * @return the parsed {@link ParsedDateTimeZoned} container
   * @throws IllegalArgumentException if the literal does not match the strict zoned pattern or cannot be parsed
   */
  public static ParsedDateTimeZoned parseDateTimeZoned(String rawLiteral) {
    var matcher = DATETIME_ZONED_PATTERN.matcher(rawLiteral);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Literal does not match :DateTimeZoned format: " + rawLiteral);
    }
    var localDateTimeStr = matcher.group(1);
    var zoneIdStr = matcher.group(2);
    var ldt = LocalDateTime.parse(localDateTimeStr);
    var zoneId = ZoneId.of(zoneIdStr);
    return new ParsedDateTimeZoned(ldt, zoneId, rawLiteral.substring(1, rawLiteral.length() - 1));
  }

  /**
   * Parses and validates a {@code :DateTimeAudited} literal string token.
   *
   * @param rawLiteral the double-quoted STVN literal text
   * @return the parsed {@link ParsedDateTimeAudited} container
   * @throws IllegalArgumentException if the literal does not match the strict audited pattern or cannot be parsed
   */
  public static ParsedDateTimeAudited parseDateTimeAudited(String rawLiteral) {
    var matcher = DATETIME_AUDITED_PATTERN.matcher(rawLiteral);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Literal does not match :DateTimeAudited format: " + rawLiteral);
    }
    var localDateTimeStr = matcher.group(1);
    var offsetStr = matcher.group(2);
    var zoneIdStr = matcher.group(3);
    var odt = OffsetDateTime.parse(localDateTimeStr + offsetStr);
    var zoneId = ZoneId.of(zoneIdStr);
    return new ParsedDateTimeAudited(odt, zoneId, rawLiteral.substring(1, rawLiteral.length() - 1));
  }
}
