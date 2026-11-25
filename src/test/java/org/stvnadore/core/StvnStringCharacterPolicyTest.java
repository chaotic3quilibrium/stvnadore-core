package org.stvnadore.core;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.validation.DiagnosticBag;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exhaustive unit test suite for {@link StvnStringCharacterPolicy}.
 */
@NullMarked
class StvnStringCharacterPolicyTest {

  private static final Set<Integer> EXPECTED_FORBIDDEN_BMP = Set.of(
      // C0 excluding \t (0x09), \n (0x0A), \r (0x0D)
      0x0000, 0x0001, 0x0002, 0x0003, 0x0004, 0x0005, 0x0006, 0x0007, 0x0008,
      0x000B, 0x000C,
      0x000E, 0x000F, 0x0010, 0x0011, 0x0012, 0x0013, 0x0014, 0x0015, 0x0016,
      0x0017, 0x0018, 0x0019, 0x001A, 0x001B, 0x001C, 0x001D, 0x001E, 0x001F,
      // DEL + C1
      0x007F,
      0x0080, 0x0081, 0x0082, 0x0083, 0x0084, 0x0085, 0x0086, 0x0087,
      0x0088, 0x0089, 0x008A, 0x008B, 0x008C, 0x008D, 0x008E, 0x008F,
      0x0090, 0x0091, 0x0092, 0x0093, 0x0094, 0x0095, 0x0096, 0x0097,
      0x0098, 0x0099, 0x009A, 0x009B, 0x009C, 0x009D, 0x009E, 0x009F,
      // Bidi controls (CVE-2021-42574)
      0x061C, 0x200E, 0x200F,
      0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
      0x2066, 0x2067, 0x2068, 0x2069,
      // Invisible format & ambiguous line separators
      0x200B, 0x2060, 0x2028, 0x2029, 0xFEFF
  );

  @Test
  @DisplayName("Symbolic constants align with DiagnosticBag and map to E001")
  void testSymbolicConstantsParity() {
    assertEquals(StvnStringCharacterPolicy.ERR_FORBIDDEN_RAW_CHARACTER, DiagnosticBag.ERR_FORBIDDEN_RAW_CHARACTER);
    assertEquals(StvnStringCharacterPolicy.ERR_UNPAIRED_SURROGATE, DiagnosticBag.ERR_UNPAIRED_SURROGATE);
    assertEquals("E001", DiagnosticBag.resolveCanonicalCode(StvnStringCharacterPolicy.ERR_FORBIDDEN_RAW_CHARACTER).orElseThrow());
    assertEquals("E001", DiagnosticBag.resolveCanonicalCode(StvnStringCharacterPolicy.ERR_UNPAIRED_SURROGATE).orElseThrow());
  }

  @Test
  @DisplayName("Exhaustive BMP and supplementary scalar sweep across character policy predicates")
  void testExhaustiveCodePointPredicates() {
    assertFalse(StvnStringCharacterPolicy.isValidUnicodeScalarValue(-1));
    assertFalse(StvnStringCharacterPolicy.isValidUnicodeScalarValue(0x110000));

    for (var cp = 0; cp <= 0xFFFF; cp++) {
      var isSurrogate = cp >= 0xD800 && cp <= 0xDFFF;
      assertEquals(isSurrogate, StvnStringCharacterPolicy.isUtf16SurrogateCodePoint(cp));
      assertEquals(!isSurrogate, StvnStringCharacterPolicy.isValidUnicodeScalarValue(cp));

      var expectedForbidden = EXPECTED_FORBIDDEN_BMP.contains(cp);
      assertEquals(expectedForbidden, StvnStringCharacterPolicy.isForbiddenRawSourceCodePoint(cp),
          "Mismatch at U+" + Integer.toHexString(cp));
      assertEquals(expectedForbidden, StvnStringCharacterPolicy.isHazardousEscapedCodePoint(cp),
          "Hazardous mismatch at U+" + Integer.toHexString(cp));

      var expectedJoiner = (cp == 0x200C || cp == 0x200D);
      assertEquals(expectedJoiner, StvnStringCharacterPolicy.isPermittedEmojiJoiner(cp));
    }

    assertFalse(StvnStringCharacterPolicy.isForbiddenRawSourceCodePoint('\t'));
    assertFalse(StvnStringCharacterPolicy.isForbiddenRawSourceCodePoint('\n'));
    assertFalse(StvnStringCharacterPolicy.isForbiddenRawSourceCodePoint('\r'));
    assertFalse(StvnStringCharacterPolicy.isHazardousEscapedCodePoint('\t'));
    assertFalse(StvnStringCharacterPolicy.isHazardousEscapedCodePoint('\n'));
    assertFalse(StvnStringCharacterPolicy.isHazardousEscapedCodePoint('\r'));

    assertTrue(StvnStringCharacterPolicy.isValidUnicodeScalarValue(0x1F680));
    assertTrue(StvnStringCharacterPolicy.isValidUnicodeScalarValue(0x10FFFF));
    assertFalse(StvnStringCharacterPolicy.isForbiddenRawSourceCodePoint(0x1F680));
    assertFalse(StvnStringCharacterPolicy.isForbiddenRawSourceCodePoint(0x200C));
    assertFalse(StvnStringCharacterPolicy.isForbiddenRawSourceCodePoint(0x200D));
  }

  @Test
  @DisplayName("UTF-16 unpaired surrogate detection handles pairs, lone high, and lone low surrogates")
  void testContainsUnpairedSurrogate() {
    assertFalse(StvnStringCharacterPolicy.containsUnpairedSurrogate(""));
    assertFalse(StvnStringCharacterPolicy.containsUnpairedSurrogate("ASCII text"));
    assertFalse(StvnStringCharacterPolicy.containsUnpairedSurrogate("🚀👨‍💻"));

    assertTrue(StvnStringCharacterPolicy.containsUnpairedSurrogate("\uD83D"));
    assertTrue(StvnStringCharacterPolicy.containsUnpairedSurrogate("\uD83Dabc"));
    assertTrue(StvnStringCharacterPolicy.containsUnpairedSurrogate("\uDE80"));
    assertTrue(StvnStringCharacterPolicy.containsUnpairedSurrogate("abc\uDEAD"));
    assertTrue(StvnStringCharacterPolicy.containsUnpairedSurrogate("\uD83D\uD83D\uDE80"));
  }

  @Test
  @DisplayName("canRepresentVerbatimInFencedString enforces CR, forbidden scalar, surrogate, and indent bounds")
  void testCanRepresentVerbatimInFencedString() {
    assertTrue(StvnStringCharacterPolicy.canRepresentVerbatimInFencedString("line1\nline2\n", false));
    assertTrue(StvnStringCharacterPolicy.canRepresentVerbatimInFencedString("col1\tcol2\n", false));
    assertTrue(StvnStringCharacterPolicy.canRepresentVerbatimInFencedString("👨‍💻\n🚀", false));

    // CR never representable verbatim
    assertFalse(StvnStringCharacterPolicy.canRepresentVerbatimInFencedString("a\rb", true));
    assertFalse(StvnStringCharacterPolicy.canRepresentVerbatimInFencedString("a\r\nb", true));

    // Forbidden raw scalars never representable verbatim even with preserveIndent = true
    for (var cp : EXPECTED_FORBIDDEN_BMP) {
      var sample = "line1\n" + Character.toString(cp) + "\nline2";
      assertFalse(StvnStringCharacterPolicy.canRepresentVerbatimInFencedString(sample, true),
          "Should reject U+" + Integer.toHexString(cp));
      assertFalse(StvnStringCharacterPolicy.canRepresentVerbatimInFencedString(sample, false));
    }

    // Unpaired surrogates never representable verbatim
    assertFalse(StvnStringCharacterPolicy.canRepresentVerbatimInFencedString("a\n\uD800", true));
    assertFalse(StvnStringCharacterPolicy.canRepresentVerbatimInFencedString("a\n\uDC00", true));

    // Indentation bounds under preserveIndent = false vs true
    assertFalse(StvnStringCharacterPolicy.canRepresentVerbatimInFencedString("  indented", false));
    assertTrue(StvnStringCharacterPolicy.canRepresentVerbatimInFencedString("  indented", true));

    assertFalse(StvnStringCharacterPolicy.canRepresentVerbatimInFencedString("   ", false));
    assertTrue(StvnStringCharacterPolicy.canRepresentVerbatimInFencedString("   ", true));

    assertFalse(StvnStringCharacterPolicy.canRepresentVerbatimInFencedString("line1\n  \nline2", false));
    assertTrue(StvnStringCharacterPolicy.canRepresentVerbatimInFencedString("line1\n  \nline2", true));
  }

  @Test
  @DisplayName("Line-ending normalizers handle zero-allocation fast paths and 3-case normalization without doubling")
  void testLineEndingNormalizers() {
    var cleanLf = "alpha\nbeta\n";
    assertSame(cleanLf, StvnStringCharacterPolicy.normalizeLineEndingsToLf(cleanLf));
    assertEquals("a\nb\nc\n\nd", StvnStringCharacterPolicy.normalizeLineEndingsToLf("a\r\nb\rc\r\rd"));
    assertEquals("\t\n\t\n\t\n", StvnStringCharacterPolicy.normalizeLineEndingsToLf("\t\r\n\t\r\t\n"));

    var noBreaks = "single line payload";
    assertSame(noBreaks, StvnStringCharacterPolicy.convertLineEndingsToCrLf(noBreaks));
    assertEquals("a\r\nb\r\nc\r\n\r\nd", StvnStringCharacterPolicy.convertLineEndingsToCrLf("a\nb\rc\r\n\nd"));
    assertEquals("already\r\ncrlf\r\n", StvnStringCharacterPolicy.convertLineEndingsToCrLf("already\r\ncrlf\r\n"));
  }

  @Test
  @DisplayName("Simple string escaping uses lowercase hex escapes and rejects unpaired surrogates")
  void testEscapeSimpleStringBody() {
    assertEquals("bell\\u0007del\\u007fc1\\u0085bidi\\u202ebom\\ufeffls\\u2028ps\\u2029",
        StvnStringCharacterPolicy.escapeSimpleStringBody("bell\u0007del\u007fc1\u0085bidi\u202ebom\ufeffls\u2028ps\u2029"));
    assertEquals("👨‍💻🚀", StvnStringCharacterPolicy.escapeSimpleStringBody("👨‍💻🚀"));
    assertThrows(IllegalArgumentException.class,
        () -> StvnStringCharacterPolicy.escapeSimpleStringBody("bad\uD83D"));
    assertThrows(IllegalArgumentException.class,
        () -> StvnStringCharacterPolicy.escapeSimpleStringBody("bad\uDE80"));
  }

  @Test
  @DisplayName("Scalar ID and Unicode character name resolution cover C0, DEL, C1, surrogates, BMP, and supplementary scalars")
  void testScalarFormattingAndCharacterNames() {
    assertEquals("U+0000", StvnStringCharacterPolicy.formatUnicodeScalarId(0x0000));
    assertEquals("U+000D", StvnStringCharacterPolicy.formatUnicodeScalarId(0x000D));
    assertEquals("U+202E", StvnStringCharacterPolicy.formatUnicodeScalarId(0x202E));
    assertEquals("U+1F680", StvnStringCharacterPolicy.formatUnicodeScalarId(0x1F680));
    assertThrows(IllegalArgumentException.class, () -> StvnStringCharacterPolicy.formatUnicodeScalarId(-1));
    assertThrows(IllegalArgumentException.class, () -> StvnStringCharacterPolicy.formatUnicodeScalarId(0x110000));

    for (var cp = 0x0000; cp <= 0x001F; cp++) {
      var name = StvnStringCharacterPolicy.resolveUnicodeCharacterName(cp);
      assertNotNull(name);
      assertFalse(name.isBlank());
      assertFalse("CONTROL".equalsIgnoreCase(name));
    }
    assertEquals("CARRIAGE RETURN", StvnStringCharacterPolicy.resolveUnicodeCharacterName(0x000D));
    assertEquals("DELETE", StvnStringCharacterPolicy.resolveUnicodeCharacterName(0x007F));
    assertEquals("NEXT LINE", StvnStringCharacterPolicy.resolveUnicodeCharacterName(0x0085));
    for (var cp = 0x0080; cp <= 0x009F; cp++) {
      var name = StvnStringCharacterPolicy.resolveUnicodeCharacterName(cp);
      assertNotNull(name);
      assertFalse(name.isBlank());
      assertFalse("CONTROL".equalsIgnoreCase(name));
    }
    assertEquals("RIGHT-TO-LEFT OVERRIDE", StvnStringCharacterPolicy.resolveUnicodeCharacterName(0x202E));
    assertEquals("ZERO WIDTH NO-BREAK SPACE", StvnStringCharacterPolicy.resolveUnicodeCharacterName(0xFEFF));
    assertEquals("ROCKET", StvnStringCharacterPolicy.resolveUnicodeCharacterName(0x1F680));
    assertEquals("HIGH SURROGATE CODE UNIT", StvnStringCharacterPolicy.resolveUnicodeCharacterName(0xD83D));
    assertEquals("LOW SURROGATE CODE UNIT", StvnStringCharacterPolicy.resolveUnicodeCharacterName(0xDE80));
  }
}
