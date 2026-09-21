package org.stvnadore.core.ir;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.core.validation.MalformedPayloadException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test suite for precision coordinate span pinning [startOffset, endOffset).
 * <p>
 * Verifies that compiler diagnostics pinpoint exact offending token and delimiter spans.
 */
public class StvnCoordinateSpanPinningTest {

  @Test
  @DisplayName("TC-SPAN-01: Tuple arity underflow pins to closing parenthesis delimiter")
  void testTupleArityUnderflowPinning() {
    String source = """
        {
          :type :Tuple(:Int :Int)
          :body ( 42 )
        }
        """;
    var ex = assertThrows(MalformedPayloadException.class, () -> StvnCompiler.compile(source));
    int rparen = source.lastIndexOf(')');
    assertEquals(rparen, ex.startOffset(), "Start offset must pin to closing delimiter");
    assertEquals(rparen + 1, ex.endOffset(), "End offset must cover closing delimiter length");
  }

  @Test
  @DisplayName("TC-SPAN-02: Tuple arity overflow pins to excess element span")
  void testTupleArityOverflowPinning() {
    String source = """
        {
          :type :Tuple(:Int)
          :body ( 42 84 126 )
        }
        """;
    var ex = assertThrows(MalformedPayloadException.class, () -> StvnCompiler.compile(source));
    int startExcess = source.indexOf("84");
    int endExcess = source.indexOf("126") + "126".length();
    assertEquals(startExcess, ex.startOffset(), "Start offset must pin to first excess element");
    assertEquals(endExcess, ex.endOffset(), "End offset must cover all excess elements");
  }

  @Test
  @DisplayName("TC-SPAN-03: Prohibited metadata facet pins exact facet token coordinates")
  void testMetadataFacetCoordinatePinning() {
    String source = """
        {
          :defs {
            :Port { #minIncl 1 #maxIncl 65535 } :Int
          }
          :type :Port
          :body 8080
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_DISCRETE_BOUND_KIND_PROHIBITED, diag.errorCode().orElse(null));

    int expectedStart = source.indexOf("#maxIncl");
    int expectedEnd = source.indexOf("65535") + "65535".length();
    assertEquals(expectedStart, diag.startOffset(), "Start offset must match '#maxIncl'");
    assertEquals(expectedEnd, diag.endOffset(), "End offset must match end of '65535'");
  }
}
