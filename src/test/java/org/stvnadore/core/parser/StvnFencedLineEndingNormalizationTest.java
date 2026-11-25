package org.stvnadore.core.parser;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.lowering.StvnLiteralParser;
import org.stvnadore.core.printer.StvnSourceFormatter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conformance suite verifying 3-case line-ending normalization ({@code \r\n}, standalone {@code \r},
 * and standalone {@code \n} -> {@code \n}) across both opening fence delimiter lines and fenced
 * verbatim bodies.
 */
@NullMarked
class StvnFencedLineEndingNormalizationTest {

  private static final List<String> LINE_BREAKS = List.of("\r\n", "\r", "\n");

  @Test
  @DisplayName("3x3 Cartesian matrix of opening fence and body line breaks normalizes identically to LF and identical CAS hash")
  void testCartesianFenceLineEndingNormalization() {
    byte[] baselineFingerprint = null;

    for (var openBreak : LINE_BREAKS) {
      for (var trailingOpenSpaces : List.of("", "   ")) {
        for (var bodyBreak : LINE_BREAKS) {
          var fencedLiteral = "\"\"\"[RAW]" + trailingOpenSpaces + openBreak
              + "  alpha" + bodyBreak
              + "    beta\tcol2" + bodyBreak
              + "[RAW]\"\"\"";
          var docSource = "{\n  :type :String\n  :body " + fencedLiteral + "\n}";

          var res = StvnCompiler.compileToResult(docSource);
          assertTrue(res.isSuccess(), () -> "Failed for open=" + debugBreak(openBreak)
              + ", body=" + debugBreak(bodyBreak) + ": " + res.diagnostics());

          var doc = res.orElseThrow();
          var lowered = ((StvnValue.StvnString) doc.requirePayload()).value();
          assertEquals("alpha\n  beta\tcol2\n", lowered);

          var literalParsed = StvnLiteralParser.parseString(fencedLiteral, false);
          assertEquals("alpha\n  beta\tcol2\n", literalParsed);

          var formatted = StvnSourceFormatter.format(docSource);
          var formattedRes = StvnCompiler.compileToResult(formatted);
          assertTrue(formattedRes.isSuccess());
          assertEquals("alpha\n  beta\tcol2\n",
              ((StvnValue.StvnString) formattedRes.orElseThrow().requirePayload()).value());

          var fp = StvnCompiler.computeCasFingerprint(doc);
          if (baselineFingerprint == null) {
            baselineFingerprint = fp;
          } else {
            assertArrayEquals(baselineFingerprint, fp);
          }
        }
      }
    }
  }

  @Test
  @DisplayName("Immediate double break after opening fence preserves leading blank line and tab-prefixed breaks preserve tab")
  void testLeadingBlankLineAndTabBeforeThreeLineBreaks() {
    // '\r' as mandatory opening break followed by '\r\n' leading blank line in body
    var crThenCrLf = "{\n  :defs { :Raw { #preserveIndent #TRUE } :String }\n  :type :Raw\n  :body \"\"\"[RAW]\r\r\nfirst\r[RAW]\"\"\"\n}";
    var res = StvnCompiler.compileToResult(crThenCrLf);
    assertTrue(res.isSuccess(), () -> "Unexpected failure: " + res.diagnostics());
    assertEquals("\nfirst\n", ((StvnValue.StvnString) res.orElseThrow().requirePayload()).value());

    // '\t\r\n', '\t\r', and '\t\n' preserve preceding '\t' (U+0009) intact followed by single '\n' (U+000A)
    var tabBreaks = "{\n  :defs { :Raw { #preserveIndent #TRUE } :String }\n  :type :Raw\n  :body \"\"\"[TSV]\rcolA\t\r\ncolB\t\rcolC\t\n[TSV]\"\"\"\n}";
    var tabRes = StvnCompiler.compileToResult(tabBreaks);
    assertTrue(tabRes.isSuccess(), () -> "Unexpected tab-break failure: " + tabRes.diagnostics());
    assertEquals("colA\t\ncolB\t\ncolC\t\n", ((StvnValue.StvnString) tabRes.orElseThrow().requirePayload()).value());
  }

  @Test
  @DisplayName("StvnSourceFormatter slices fenced strings accurately in code-point space when preceded by supplementary emojis")
  void testFormatterEmojiBeforeAndInsideStandaloneCrFencedString() {
    var source = "// Leading emoji 👨‍💻🚀 before fenced string\n{\n  :type :String\n  :body \"\"\"[RAW]\r  hello 👨‍💻\r  world 🚀\r[RAW]\"\"\"\n}";
    var formatted = StvnSourceFormatter.format(source);
    assertTrue(formatted.indexOf('\r') < 0, "Formatted output must normalize all CR line endings to LF");
    var compiled = StvnCompiler.compileToResult(formatted);
    assertTrue(compiled.isSuccess(), () -> "Recompilation failed: " + compiled.diagnostics());
    assertEquals("hello 👨‍💻\nworld 🚀\n",
        ((StvnValue.StvnString) compiled.orElseThrow().requirePayload()).value());
  }

  private static String debugBreak(String brk) {
    return brk.replace("\r", "\\r").replace("\n", "\\n");
  }
}
