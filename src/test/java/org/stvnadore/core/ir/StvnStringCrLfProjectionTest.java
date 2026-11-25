package org.stvnadore.core.ir;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.binary.StvnBinaryEncoder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conformance suite for {@link StvnValue.StvnString#toUnvalidatedCrLfString()}.
 */
@NullMarked
class StvnStringCrLfProjectionTest {

  @Test
  @DisplayName("toUnvalidatedCrLfString returns identical reference when no CR or LF is present")
  void testZeroAllocationFastPath() {
    var payload = StvnCompiler.compilePayload("{\n  :type :String\n  :body \"single_line_ascii_payload\"\n}").orElseThrow();
    var strVal = (StvnValue.StvnString) payload;
    assertSame(strVal.value(), strVal.toUnvalidatedCrLfString());
  }

  @Test
  @DisplayName("toUnvalidatedCrLfString converts LF, standalone CR, and CRLF uniformly to CRLF without doubling")
  void testUniformCrLfConversionWithoutDoubling() {
    var source = """
        {
          :type :Tuple(:String :String)
          :body (
            ""\"[HTTP]
          GET /health HTTP/1.1
          Host: example.internal

          [HTTP]""\"
            "mixed\\nline1\\rline2\\r\\nline3\\r\\r\\nend"
          )
        }
        """;
    var payload = StvnCompiler.compilePayload(source).orElseThrow();
    var tuple = (StvnValue.StvnTuple) payload;
    var fencedStr = (StvnValue.StvnString) tuple.elements().get(0);
    var mixedStr = (StvnValue.StvnString) tuple.elements().get(1);

    assertEquals("GET /health HTTP/1.1\nHost: example.internal\n\n", fencedStr.value());
    assertEquals("GET /health HTTP/1.1\r\nHost: example.internal\r\n\r\n", fencedStr.toUnvalidatedCrLfString());

    assertEquals("mixed\r\nline1\r\nline2\r\nline3\r\n\r\nend", mixedStr.toUnvalidatedCrLfString());
  }

  @Test
  @DisplayName("toUnvalidatedCrLfString is an unvalidated consumer projection that preserves IR value, binary wire bytes, and CAS preimage")
  void testUnvalidatedProjectionDivergesFromSchemaBoundsWhilePreservingIrAndCas() {
    // 3 scalars "a\nb" satisfies #maxSize 3 and #regex "^[a-z\\n]{3}$", whereas projected "a\r\nb" has 4 scalars
    var source = """
        {
          :defs {
            :TightLfText { #minSize 3 #maxSize 3 #regex "^[a-z\\n]{3}$" } :String
          }
          :type :TightLfText
          :body ""\"[TXT]
        a
        b[TXT]""\"
        }
        """;
    var res = StvnCompiler.compileToResult(source);
    assertTrue(res.isSuccess(), () -> "Expected clean compile: " + res.diagnostics());
    var doc = res.orElseThrow();
    var casBefore = StvnCompiler.computeCasFingerprint(doc);

    var irStr = (StvnValue.StvnString) doc.requirePayload();
    assertEquals("a\nb", irStr.value());
    assertEquals(3, irStr.value().codePointCount(0, irStr.value().length()));

    var projectedCrLf = irStr.toUnvalidatedCrLfString();
    assertEquals("a\r\nb", projectedCrLf);
    assertEquals(4, projectedCrLf.codePointCount(0, projectedCrLf.length()));
    var schema = (StvnSchema.StringSchema) irStr.schema();
    assertFalse(schema.compiledRegex().orElseThrow().matcher(projectedCrLf).matches(),
        "Projected CRLF string intentionally bypasses LF-anchored #regex");

    // Underlying IR value, binary payload, and SHA-256 CAS fingerprint remain 100% LF-canonical
    assertEquals("a\nb", irStr.value());
    assertArrayEquals(casBefore, StvnCompiler.computeCasFingerprint(doc));
    var binary = new StvnBinaryEncoder(true, null).encode(doc);
    var roundTripped = (StvnValue.StvnString) StvnBinaryDecoder
        .decodeDocument(binary, doc.rootType().orElseThrow())
        .requirePayload();
    assertEquals("a\nb", roundTripped.value());
  }
}
