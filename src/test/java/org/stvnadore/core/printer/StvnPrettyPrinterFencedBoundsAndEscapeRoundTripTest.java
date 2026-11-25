package org.stvnadore.core.printer;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.binary.StvnBinaryEncoder;
import org.stvnadore.core.io.CanonicalStvnWriter;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnValue;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conformance suite verifying {@link AstPrettyPrinter}, {@link AstCompactPrinter}, and
 * {@link CanonicalStvnWriter} fenced-routing bounds, visible {@code \u005CuXXXX} escaping of
 * forbidden raw scalars, and {@code compile(print(doc))} round-trip closure.
 */
@NullMarked
class StvnPrettyPrinterFencedBoundsAndEscapeRoundTripTest {

  @Test
  @DisplayName("Strings containing CR, DEL, C1, Bidi, invisible format scalars, or stripped spaces always route to escaped simple strings")
  void testHazardousAndSpaceSensitiveStringsRouteToEscapedSimpleStrings() {
    var input = """
        {
          :defs {
            :Preserved { #preserveIndent #TRUE } :String
            :Payload :Tuple(:Preserved :Preserved :Preserved :Preserved :Preserved :Preserved :String :String :Preserved)
          }
          :type :Payload
          :body (
            "line1\\r\\nline2"
            "del\\u007fmulti\\nline"
            "c1\\u0085multi\\nline"
            "bidi\\u202emulti\\nline"
            "zwsp\\u200bbom\\ufeffls\\u2028ps\\u2029\\nline"
            "emoji👨‍💻\\nallowed_fenced\\n"
            "  unpreserved_leading_space\\n  second_line"
            "blank_space_line\\n   \\ntrailer"
            "  preserved_leading_space\\n\\tkept_tab\\n"
          )
        }
        """;

    var res = StvnCompiler.compileToResult(input);
    assertTrue(res.isSuccess(), () -> "Initial compile failed: " + res.diagnostics());
    var doc = res.orElseThrow();
    var baselineCas = StvnCompiler.computeCasFingerprint(doc);

    var prettyText = new AstPrettyPrinter().printToString(doc);
    var compactText = new AstCompactPrinter().printToString(doc);
    var canonicalText = new CanonicalStvnWriter().printToString(doc);

    // Slots 0..4 and 6..7 must NOT use fenced strings; slots 5 and 8 MUST use fenced strings in pretty mode
    assertTrue(prettyText.contains("\"line1\\r\\nline2\""), prettyText);
    assertTrue(prettyText.contains("\"del\\u007fmulti\\nline\""), prettyText);
    assertTrue(prettyText.contains("\"c1\\u0085multi\\nline\""), prettyText);
    assertTrue(prettyText.contains("\"bidi\\u202emulti\\nline\""), prettyText);
    assertTrue(prettyText.contains("\"zwsp\\u200bbom\\ufeffls\\u2028ps\\u2029\\nline\""), prettyText);
    assertTrue(prettyText.contains("\"  unpreserved_leading_space\\n  second_line\""), prettyText);
    assertTrue(prettyText.contains("\"blank_space_line\\n   \\ntrailer\""), prettyText);
    assertTrue(prettyText.contains("\"\"\"[RAW]\nemoji👨‍💻\nallowed_fenced\n[RAW]\"\"\""), prettyText);

    // Every serialized form must pass Pass 1 character hygiene and preserve exact IR + CAS hash
    for (var candidate : List.of(prettyText, compactText, canonicalText, StvnSourceFormatter.format(prettyText))) {
      var roundTripRes = StvnCompiler.compileToResult(candidate);
      assertTrue(roundTripRes.isSuccess(), () -> "Round-trip failed for:\n" + candidate + "\nDiagnostics: " + roundTripRes.diagnostics());
      assertFalse(roundTripRes.hasErrors());
      var roundTripDoc = roundTripRes.orElseThrow();
      assertEquals(
          ((StvnValue.StvnTuple) doc.requirePayload()).elements().stream()
              .map(v -> ((StvnValue.StvnString) v).value())
              .toList(),
          ((StvnValue.StvnTuple) roundTripDoc.requirePayload()).elements().stream()
              .map(v -> ((StvnValue.StvnString) v).value())
              .toList()
      );
      assertArrayEquals(baselineCas, StvnCompiler.computeCasFingerprint(roundTripDoc));
    }
  }

  @Test
  @DisplayName("Programmatic StvnString containing unpaired UTF-16 surrogate fails fast across printers and binary encoder")
  void testProgrammaticUnpairedSurrogateFailsFast() {
    var template = StvnCompiler.compileToResult("{\n  :type :String\n  :body \"ok\"\n}").orElseThrow();
    var strSchema = template.requireRootType();
    var badVal = new StvnValue.StvnString(strSchema, "ill_formed_\uD83D");
    var badDoc = new StvnDocument(
        template.meta(),
        template.defs(),
        Optional.of(strSchema),
        Optional.of(badVal),
        false,
        false
    );

    assertThrows(RuntimeException.class, () -> new AstPrettyPrinter().printToString(badDoc));
    assertThrows(RuntimeException.class, () -> new AstCompactPrinter().printToString(badDoc));
    assertThrows(RuntimeException.class, () -> new CanonicalStvnWriter().printToString(badDoc));
    assertThrows(IllegalArgumentException.class, () -> new StvnBinaryEncoder(true, null).encode(badDoc));
  }
}
