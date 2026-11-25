package org.stvnadore.core;

import org.jspecify.annotations.NullMarked;

import java.util.Locale;
import java.util.Objects;

/**
 * Cross-language Unicode scalar hygiene, escape classification, line-ending normalization,
 * and verbatim fenced-string representability policy for STVN 2.0.0.
 * <p>
 * Every predicate in this class operates on portable Unicode code points or UTF-16 code units
 * with deterministic semantics shared across Java, Rust, TypeScript, and IDE tooling.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnStringCharacterPolicy {

  /**
   * Symbolic diagnostic identifier emitted when a forbidden raw control, bidirectional
   * override/isolate, byte-order mark, or invisible format/line-separator code point appears
   * unescaped anywhere in STVN source text ({@code E001}).
   */
  public static final String ERR_FORBIDDEN_RAW_CHARACTER = "ERR_FORBIDDEN_RAW_CHARACTER";

  /**
   * Symbolic diagnostic identifier emitted when an unpaired UTF-16 high or low surrogate code
   * unit or {@code \u005CuXXXX} escape sequence appears in STVN source text ({@code E001}).
   */
  public static final String ERR_UNPAIRED_SURROGATE = "ERR_UNPAIRED_SURROGATE";

  private static final String[] C0_NAMES = {
      "NULL",
      "START OF HEADING",
      "START OF TEXT",
      "END OF TEXT",
      "END OF TRANSMISSION",
      "ENQUIRY",
      "ACKNOWLEDGE",
      "BELL",
      "BACKSPACE",
      "CHARACTER TABULATION",
      "LINE FEED",
      "LINE TABULATION",
      "FORM FEED",
      "CARRIAGE RETURN",
      "SHIFT OUT",
      "SHIFT IN",
      "DATA LINK ESCAPE",
      "DEVICE CONTROL ONE",
      "DEVICE CONTROL TWO",
      "DEVICE CONTROL THREE",
      "DEVICE CONTROL FOUR",
      "NEGATIVE ACKNOWLEDGE",
      "SYNCHRONOUS IDLE",
      "END OF TRANSMISSION BLOCK",
      "CANCEL",
      "END OF MEDIUM",
      "SUBSTITUTE",
      "ESCAPE",
      "INFORMATION SEPARATOR FOUR",
      "INFORMATION SEPARATOR THREE",
      "INFORMATION SEPARATOR TWO",
      "INFORMATION SEPARATOR ONE"
  };

  private static final String[] C1_NAMES = {
      "PADDING CHARACTER",
      "HIGH OCTET PRESET",
      "BREAK PERMITTED HERE",
      "NO BREAK HERE",
      "INDEX",
      "NEXT LINE",
      "START OF SELECTED AREA",
      "END OF SELECTED AREA",
      "CHARACTER TABULATION SET",
      "CHARACTER TABULATION WITH JUSTIFICATION",
      "LINE TABULATION SET",
      "PARTIAL LINE FORWARD",
      "PARTIAL LINE BACKWARD",
      "REVERSE LINE FEED",
      "SINGLE SHIFT TWO",
      "SINGLE SHIFT THREE",
      "DEVICE CONTROL STRING",
      "PRIVATE USE ONE",
      "PRIVATE USE TWO",
      "SET TRANSMIT STATE",
      "CANCEL CHARACTER",
      "MESSAGE WAITING",
      "START OF GUARDED AREA",
      "END OF GUARDED AREA",
      "START OF STRING",
      "SINGLE GRAPHIC CHARACTER INTRODUCER",
      "SINGLE CHARACTER INTRODUCER",
      "CONTROL SEQUENCE INTRODUCER",
      "STRING TERMINATOR",
      "OPERATING SYSTEM COMMAND",
      "PRIVACY MESSAGE",
      "APPLICATION PROGRAM COMMAND"
  };

  private StvnStringCharacterPolicy() {
  }

  /**
   * Returns {@code true} if {@code codePoint} is a valid Unicode Scalar Value
   * ({@code U+0000}..{@code U+D7FF} or {@code U+E000}..{@code U+10FFFF}).
   *
   * @param codePoint candidate integer code point
   * @return {@code true} when {@code codePoint} is a valid Unicode Scalar Value
   */
  public static boolean isValidUnicodeScalarValue(int codePoint) {
    return codePoint >= 0x0000
        && codePoint <= 0x10FFFF
        && (codePoint < 0xD800 || codePoint > 0xDFFF);
  }

  /**
   * Returns {@code true} if {@code codePoint} is a UTF-16 surrogate code point
   * ({@code U+D800}..{@code U+DFFF}).
   *
   * @param codePoint candidate integer code point
   * @return {@code true} when {@code codePoint} lies in the UTF-16 surrogate range
   */
  public static boolean isUtf16SurrogateCodePoint(int codePoint) {
    return codePoint >= 0xD800 && codePoint <= 0xDFFF;
  }

  /**
   * Returns {@code true} if {@code codePoint} is an explicitly permitted emoji joiner format
   * character ({@code U+200C ZERO WIDTH NON-JOINER} or {@code U+200D ZERO WIDTH JOINER}).
   *
   * @param codePoint Unicode code point to inspect
   * @return {@code true} for {@code U+200C} or {@code U+200D}
   */
  public static boolean isPermittedEmojiJoiner(int codePoint) {
    return codePoint == 0x200C || codePoint == 0x200D;
  }

  /**
   * Returns {@code true} if {@code codePoint} is forbidden from appearing raw (unescaped)
   * anywhere in an STVN 2.0.0 source stream (structural whitespace/tokens, {@code //} comments,
   * {@code "..."} simple strings, and {@code """[TAG]...[TAG]"""} fenced string tags and bodies).
   * <p>
   * Note that {@code '\t'} ({@code U+0009}) is governed separately by Rule STR-04 tab rules,
   * {@code '\n'} ({@code U+000A}) and {@code '\r'} ({@code U+000D}) are governed by line-ending
   * rules, and emoji joiners {@code U+200C} / {@code U+200D} are permitted inside string literals
   * and comments.
   *
   * @param codePoint Unicode code point to inspect
   * @return {@code true} when raw source occurrences must be rejected with Pass 1 {@code E001}
   */
  public static boolean isForbiddenRawSourceCodePoint(int codePoint) {
    // 1. C0 controls excluding '\t' (U+0009), '\n' (U+000A), and '\r' (U+000D)
    if ((codePoint >= 0x0000 && codePoint <= 0x0008)
        || codePoint == 0x000B
        || codePoint == 0x000C
        || (codePoint >= 0x000E && codePoint <= 0x001F)) {
      return true;
    }
    // 2. DEL (U+007F) and C1 controls (U+0080..U+009F)
    if (codePoint >= 0x007F && codePoint <= 0x009F) {
      return true;
    }
    // 3. Bidirectional text-direction controls (Trojan Source, CVE-2021-42574)
    if (codePoint == 0x061C
        || (codePoint >= 0x200E && codePoint <= 0x200F)
        || (codePoint >= 0x202A && codePoint <= 0x202E)
        || (codePoint >= 0x2066 && codePoint <= 0x2069)) {
      return true;
    }
    // 4. Invisible format & ambiguous line-separator characters (ZWSP, WJ, LS, PS, ZWNBSP/BOM)
    return codePoint == 0x200B
        || codePoint == 0x2060
        || codePoint == 0x2028
        || codePoint == 0x2029
        || codePoint == 0xFEFF;
  }

  /**
   * Returns {@code true} if an escaped scalar inside a {@code "..."} simple string belongs to
   * the hazardous control, bidirectional override/isolate, or invisible format/line-separator set
   * warranting downstream linter/IDE scrutiny.
   * <p>
   * Excludes {@code '\t'} ({@code U+0009}), {@code '\n'} ({@code U+000A}), and {@code '\r'}
   * ({@code U+000D}); shorthand {@code \r} versus explicit {@code \u005Cu000D} scrutiny is configured
   * separately by IDE inspections.
   *
   * @param codePoint decoded Unicode scalar value from an escape sequence
   * @return {@code true} when the escaped scalar is hazardous or invisible
   */
  public static boolean isHazardousEscapedCodePoint(int codePoint) {
    return isForbiddenRawSourceCodePoint(codePoint);
  }

  /**
   * Returns {@code true} if {@code text} contains any unpaired UTF-16 high or low surrogate
   * code unit.
   *
   * @param text character sequence to inspect
   * @return {@code true} when {@code text} is not a well-formed UTF-16 Unicode Scalar Value sequence
   */
  public static boolean containsUnpairedSurrogate(CharSequence text) {
    Objects.requireNonNull(text, "text must not be null");
    int len = text.length();
    for (int i = 0; i < len; i++) {
      char c = text.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (i + 1 >= len || !Character.isLowSurrogate(text.charAt(i + 1))) {
          return true;
        }
        i++;
      } else if (Character.isLowSurrogate(c)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns {@code true} if and only if {@code value} can be emitted verbatim inside a Rule STR-04
   * fenced string ({@code """[TAG]\n...[TAG]"""}) under {@code preserveIndent} and round-trip
   * losslessly back to an identical {@code value} while passing Pass 1 character hygiene.
   * <p>
   * Returns {@code false} whenever {@code value} contains:
   * <ul>
   *   <li>{@code '\r'} ({@code U+000D}), because fenced strings normalize all line breaks to {@code '\n'},</li>
   *   <li>any {@link #isForbiddenRawSourceCodePoint(int)} scalar,</li>
   *   <li>any unpaired UTF-16 surrogate code unit, or</li>
   *   <li>leading ASCII spaces on all non-blank lines, pure-space content, or non-empty space-only
   *       blank lines when {@code !preserveIndent} would strip them under Rule STR-04.</li>
   * </ul>
   *
   * @param value          candidate decoded string value
   * @param preserveIndent {@code true} when governed by {@code #preserveIndent #TRUE}
   * @return {@code true} when {@code value} is losslessly representable in a fenced verbatim string
   */
  public static boolean canRepresentVerbatimInFencedString(String value, boolean preserveIndent) {
    Objects.requireNonNull(value, "value must not be null");
    int len = value.length();
    for (int i = 0; i < len; ) {
      char c = value.charAt(i);
      if (c == '\r') {
        return false;
      }
      if (Character.isHighSurrogate(c)) {
        if (i + 1 >= len || !Character.isLowSurrogate(value.charAt(i + 1))) {
          return false;
        }
        int cp = Character.toCodePoint(c, value.charAt(i + 1));
        if (isForbiddenRawSourceCodePoint(cp)) {
          return false;
        }
        i += 2;
      } else if (Character.isLowSurrogate(c)) {
        return false;
      } else {
        if (isForbiddenRawSourceCodePoint(c)) {
          return false;
        }
        i++;
      }
    }
    if (!preserveIndent && wouldStripSpacesUnderDefaultTrim(value)) {
      return false;
    }
    return true;
  }

  private static boolean wouldStripSpacesUnderDefaultTrim(String text) {
    if (text.isEmpty()) {
      return false;
    }
    int minIndent = Integer.MAX_VALUE;
    boolean sawNonBlankLine = false;
    int lineStart = 0;
    int n = text.length();
    while (lineStart <= n) {
      int nl = text.indexOf('\n', lineStart);
      int lineEnd = (nl < 0) ? n : nl;
      int p = lineStart;
      while (p < lineEnd && text.charAt(p) == ' ') {
        p++;
      }
      if (p == lineEnd) {
        if (lineEnd > lineStart) {
          return true;
        }
      } else {
        sawNonBlankLine = true;
        minIndent = Math.min(minIndent, p - lineStart);
      }
      if (nl < 0) {
        break;
      }
      lineStart = nl + 1;
    }
    return sawNonBlankLine && minIndent > 0;
  }

  /**
   * Normalizes all three line-ending sequences ({@code "\r\n"}, standalone {@code '\r'}, and
   * standalone {@code '\n'}) in {@code text} to a single {@code '\n'} ({@code U+000A}) in
   * {@code O(n)} time, returning {@code text} with zero allocation when no {@code '\r'} is present.
   *
   * @param text input character sequence
   * @return {@code LF}-normalized string
   */
  public static String normalizeLineEndingsToLf(String text) {
    Objects.requireNonNull(text, "text must not be null");
    int firstCr = text.indexOf('\r');
    if (firstCr < 0) {
      return text;
    }
    int len = text.length();
    StringBuilder sb = new StringBuilder(len);
    sb.append(text, 0, firstCr);
    for (int i = firstCr; i < len; i++) {
      char c = text.charAt(i);
      if (c == '\r') {
        sb.append('\n');
        if (i + 1 < len && text.charAt(i + 1) == '\n') {
          i++;
        }
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /**
   * Converts all three line-ending sequences ({@code "\r\n"}, standalone {@code '\r'}, and
   * standalone {@code '\n'}) in {@code text} uniformly to {@code "\r\n"} ({@code U+000D U+000A})
   * in {@code O(n)} time without doubling existing {@code "\r\n"} sequences, returning {@code text}
   * with zero allocation if neither {@code '\n'} nor {@code '\r'} is present.
   *
   * @param text input string
   * @return {@code CRLF}-projected string
   */
  public static String convertLineEndingsToCrLf(String text) {
    Objects.requireNonNull(text, "text must not be null");
    int len = text.length();
    int firstBreak = -1;
    for (int i = 0; i < len; i++) {
      char c = text.charAt(i);
      if (c == '\n' || c == '\r') {
        firstBreak = i;
        break;
      }
    }
    if (firstBreak < 0) {
      return text;
    }
    StringBuilder sb = new StringBuilder(len + 16);
    sb.append(text, 0, firstBreak);
    for (int i = firstBreak; i < len; i++) {
      char c = text.charAt(i);
      if (c == '\r') {
        sb.append("\r\n");
        if (i + 1 < len && text.charAt(i + 1) == '\n') {
          i++;
        }
      } else if (c == '\n') {
        sb.append("\r\n");
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /**
   * Escapes the interior content of a {@code "..."} simple string literal so every forbidden
   * raw source code point is emitted as a visible STVN escape ({@code \b}, {@code \f}, {@code \n},
   * {@code \r}, {@code \t}, {@code \"}, {@code \\}, or lowercase {@code \u005CuXXXX}), guaranteeing
   * 100% Pass 1 raw-character hygiene compliance on re-parse.
   *
   * @param value decoded Unicode Scalar Value string
   * @return escaped simple-string body (excluding enclosing double quotes)
   * @throws IllegalArgumentException if {@code value} contains an unpaired UTF-16 surrogate
   */
  public static String escapeSimpleStringBody(String value) {
    Objects.requireNonNull(value, "value must not be null");
    int len = value.length();
    StringBuilder sb = new StringBuilder(len + 8);
    for (int i = 0; i < len; ) {
      char c = value.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (i + 1 >= len || !Character.isLowSurrogate(value.charAt(i + 1))) {
          throw new IllegalArgumentException(
              "Cannot serialize unpaired high UTF-16 surrogate " + formatUnicodeScalarId(c) + " in STVN string"
          );
        }
        sb.append(c).append(value.charAt(i + 1));
        i += 2;
        continue;
      }
      if (Character.isLowSurrogate(c)) {
        throw new IllegalArgumentException(
            "Cannot serialize unpaired low UTF-16 surrogate " + formatUnicodeScalarId(c) + " in STVN string"
        );
      }
      switch (c) {
        case '\\' -> sb.append("\\\\");
        case '"' -> sb.append("\\\"");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        default -> {
          if (isForbiddenRawSourceCodePoint(c)) {
            sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
      i++;
    }
    return sb.toString();
  }

  /**
   * Formats a code point in standard uppercase Unicode scalar notation ({@code "U+000D"},
   * {@code "U+202E"}, {@code "U+1F680"}).
   *
   * @param codePoint Unicode code point ({@code 0x0000}..{@code 0x10FFFF})
   * @return formatted {@code U+XXXX} or {@code U+XXXXX}/{@code U+XXXXXX} identifier
   * @throws IllegalArgumentException if {@code codePoint} is outside {@code 0x0000..0x10FFFF}
   */
  public static String formatUnicodeScalarId(int codePoint) {
    if (codePoint < 0 || codePoint > 0x10FFFF) {
      throw new IllegalArgumentException("Code point out of Unicode range: " + codePoint);
    }
    if (codePoint <= 0xFFFF) {
      return String.format(Locale.ROOT, "U+%04X", codePoint);
    }
    return String.format(Locale.ROOT, "U+%X", codePoint);
  }

  /**
   * Resolves a deterministic, human-readable uppercase Unicode / ISO-6429 character name for
   * diagnostics and IDE hover cards, providing explicit aliases for C0 ({@code U+0000}..{@code U+001F}),
   * {@code DEL} ({@code U+007F}), and C1 ({@code U+0080}..{@code U+009F}) control characters.
   *
   * @param codePoint Unicode code point ({@code 0x0000}..{@code 0x10FFFF})
   * @return deterministic uppercase character name
   * @throws IllegalArgumentException if {@code codePoint} is outside {@code 0x0000..0x10FFFF}
   */
  public static String resolveUnicodeCharacterName(int codePoint) {
    if (codePoint < 0 || codePoint > 0x10FFFF) {
      throw new IllegalArgumentException("Code point out of Unicode range: " + codePoint);
    }
    if (codePoint <= 0x001F) {
      return C0_NAMES[codePoint];
    }
    if (codePoint == 0x007F) {
      return "DELETE";
    }
    if (codePoint >= 0x0080 && codePoint <= 0x009F) {
      return C1_NAMES[codePoint - 0x0080];
    }
    if (isUtf16SurrogateCodePoint(codePoint)) {
      return codePoint <= 0xDBFF ? "HIGH SURROGATE CODE UNIT" : "LOW SURROGATE CODE UNIT";
    }
    String jdkName = Character.getName(codePoint);
    if (jdkName != null && !jdkName.isBlank() && !"CONTROL".equalsIgnoreCase(jdkName)) {
      return jdkName;
    }
    return "UNASSIGNED";
  }

  /**
   * Formats the recommended STVN double-quoted escape representation for a BMP/supplementary
   * code point (for example, {@code "\\u202E"}, {@code "\\b"}, {@code "\\f"}, {@code "\\u0007"}).
   *
   * @param codePoint Unicode Scalar Value
   * @return escape representation suitable for STE remediation messages
   */
  public static String formatRecommendedSimpleEscape(int codePoint) {
    return switch (codePoint) {
      case '\b' -> "\\b";
      case '\f' -> "\\f";
      case '\n' -> "\\n";
      case '\r' -> "\\u000D";
      case '\t' -> "\\t";
      default -> {
        if (codePoint <= 0xFFFF) {
          yield String.format(Locale.ROOT, "\\u%04X", codePoint);
        }
        char hi = Character.highSurrogate(codePoint);
        char lo = Character.lowSurrogate(codePoint);
        yield String.format(Locale.ROOT, "\\u%04X\\u%04X", (int) hi, (int) lo);
      }
    };
  }
}
