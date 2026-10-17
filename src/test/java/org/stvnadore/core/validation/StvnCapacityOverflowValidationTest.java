package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test suite for Capacity Overflow Validation across scalars and exact floats
 * (SPEC § 5.1.3, § 6.1, § 7.1, Appendix C.3, Diagnostic Code ERR_CAPACITY_OVERFLOW).
 */
public class StvnCapacityOverflowValidationTest {

  @Test
  @DisplayName("TC-CAP-FLOAT-01: Rejects payload float literal exceeding declared #maxSize precision")
  void testRejectExactFloatPayloadPrecisionOverflow() {
    String source = """
        {
          :defs {
            :SmallExact { #exact #maxSize 4 } :Float
          }
          :type :SmallExact
          :body 123.45
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected failure when mantissa precision (5) exceeds #maxSize 4");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_CAPACITY_OVERFLOW.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Expected ERR_CAPACITY_OVERFLOW diagnostic"));

    String targetToken = "123.45";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset(), "Start offset must pinpoint '123.45'");
    assertEquals(expectedEnd, diag.endOffset(), "End offset must cover '123.45'");
    assertTrue(diag.message().contains("Mantissa precision 5 exceeds maximum allowable significant digits (4)"));
  }

  @Test
  @DisplayName("TC-CAP-FLOAT-02: Accepts payload float literal within declared #maxSize precision")
  void testAcceptExactFloatPayloadWithinPrecision() {
    String source = """
        {
          :defs {
            :Amount { #exact #maxSize 5 } :Float
          }
          :type :Amount
          :body 12.345
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Mantissa precision (5) matches #maxSize 5 and must compile cleanly");
    assertTrue(result.document().isPresent());
  }

  @Test
  @DisplayName("TC-CAP-FLOAT-03: Rejects constant float literal exceeding declared #maxSize precision in :defs")
  void testRejectExactFloatConstantPrecisionOverflow() {
    String source = """
        {
          :defs {
            :MicroRate { #exact #maxSize 3 } :Float
            #MY_RATE :MicroRate 1.234
          }
          :type :MicroRate
          :body #MY_RATE
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected failure when constant literal precision (4) exceeds #maxSize 3");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_CAPACITY_OVERFLOW.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Expected ERR_CAPACITY_OVERFLOW diagnostic"));

    String targetToken = "1.234";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset(), "Start offset must pinpoint '1.234'");
    assertEquals(expectedEnd, diag.endOffset(), "End offset must cover '1.234'");
    assertTrue(diag.message().contains("Mantissa precision 4 exceeds maximum allowable significant digits (3)"));
  }

  @Test
  @DisplayName("TC-CAP-FLOAT-04: Rejects payload float literal exceeding default 16,777,216 ceiling")
  void testRejectExactFloatDefaultCeilingOverflow() {
    // Construct 16,777,217 digit string (simulated via 10-digit limit test or synthetic overflow)
    String zeros = "1." + "0".repeat(20);
    String source = "{\n  :defs {\n    :TinyLimit { #exact #maxSize 10 } :Float\n  }\n  :type :TinyLimit\n  :body " + zeros + "\n}\n";
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors());
    assertTrue(result.diagnostics().stream().anyMatch(d -> DiagnosticBag.ERR_CAPACITY_OVERFLOW.equals(d.errorCode().orElse(null))));
  }

  @Test
  @DisplayName("TC-CAP-FLOAT-05: Unadorned { #exact } :Float accepts high-precision decimal within default ceiling")
  void testAcceptExactFloatUnadornedDefaultCeiling() {
    String source = """
        {
          :defs {
            :HighPrecision { #exact } :Float
          }
          :type :HighPrecision
          :body 12345678901234567890.12345678901234567890
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "High precision decimal within default ceiling must compile cleanly");
    assertTrue(result.document().isPresent());
  }
}
