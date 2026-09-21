package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test suite for discrete interval bounds governance under Value-Oriented Programming (VOP).
 * <p>
 * Discrete types (such as {@code :Int} and {@code { #exact } :Float}) enforce half-open intervals
 * [minIncl, maxExcl). Declaring {@code #maxIncl} or {@code #minExcl} on discrete types is strictly prohibited.
 */
public class StvnDiscreteIntervalValidationTest {

  @Test
  @DisplayName("TC-DISC-01: Prohibits #maxIncl on :Int domain")
  void testProhibitMaxInclOnInt() {
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
    assertTrue(result.hasErrors(), "Expected error for #maxIncl on :Int");
    var error = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_DISCRETE_BOUND_KIND_PROHIBITED, error.errorCode().orElse(null));
    assertTrue(error.message().contains("prohibits bound '#maxIncl'"));
  }

  @Test
  @DisplayName("TC-DISC-02: Prohibits #minExcl on :Int domain")
  void testProhibitMinExclOnInt() {
    String source = """
        {
          :defs {
            :PositiveInt { #minExcl 0 } :Int
          }
          :type :PositiveInt
          :body 1
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected error for #minExcl on :Int");
    var error = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_DISCRETE_BOUND_KIND_PROHIBITED, error.errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-DISC-03: Prohibits #maxIncl and #minExcl on { #exact } :Float")
  void testProhibitDiscreteBoundsOnExactFloat() {
    String maxInclSource = """
        {
          :defs {
            :ExactRate { #exact #maxIncl 100.0 } :Float
          }
          :type :ExactRate
          :body 50.0
        }
        """;
    var maxInclResult = StvnCompiler.compileToResult(maxInclSource, null, StvnParserConfig.DEFAULT);
    assertTrue(maxInclResult.hasErrors(), "Expected error for #maxIncl on { #exact } :Float");
    assertEquals(DiagnosticBag.ERR_DISCRETE_BOUND_KIND_PROHIBITED, maxInclResult.diagnostics().getFirst().errorCode().orElse(null));

    String minExclSource = """
        {
          :defs {
            :ExactRate { #exact #minExcl 0.0 } :Float
          }
          :type :ExactRate
          :body 50.0
        }
        """;
    var minExclResult = StvnCompiler.compileToResult(minExclSource, null, StvnParserConfig.DEFAULT);
    assertTrue(minExclResult.hasErrors(), "Expected error for #minExcl on { #exact } :Float");
    assertEquals(DiagnosticBag.ERR_DISCRETE_BOUND_KIND_PROHIBITED, minExclResult.diagnostics().getFirst().errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-DISC-04: Permits #maxIncl and #minExcl on standard continuous :Float")
  void testPermitContinuousBoundsOnStandardFloat() {
    String source = """
        {
          :defs {
            :Probability { #minExcl 0.0 #maxIncl 1.0 } :Float
          }
          :type :Probability
          :body 0.75
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Continuous float must permit #minExcl and #maxIncl");
  }

  @Test
  @DisplayName("TC-DISC-05: Discrete half-open intervals [minIncl, maxExcl) succeed on :Int")
  void testDiscreteHalfOpenIntervalSuccess() {
    String source = """
        {
          :defs {
            :Port { #minIncl 1 #maxExcl 65536 } :Int
          }
          :type :Port
          :body 8080
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Discrete half-open interval must compile cleanly");
  }
}
