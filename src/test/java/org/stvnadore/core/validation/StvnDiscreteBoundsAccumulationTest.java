package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test suite for discrete interval bounds accumulation and empty domain detection (Target D).
 */
public class StvnDiscreteBoundsAccumulationTest {

  @Test
  @DisplayName("TC-DISC-01: Prohibited bound kind and inverted range accumulate simultaneously on :Int")
  void testDiscreteProhibitedBoundAndInvertedRangeAccumulation() {
    String stvn = """
        {
          :defs {
            :LatencyWindow { #minIncl 500 #maxIncl 50 } :Int
          }
          :type :LatencyWindow
          :body 100
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertFalse(result.isSuccess(), "Prohibited bound kind and inverted range must fail compilation");
    var diags = result.diagnostics();

    var boundOpt = diags.stream()
        .filter(d -> DiagnosticBag.ERR_DISCRETE_BOUND_KIND_PROHIBITED.equals(d.errorCode().orElse(null)))
        .findFirst();
    assertTrue(boundOpt.isPresent(), "Must accumulate ERR_DISCRETE_BOUND_KIND_PROHIBITED");
    var boundDiag = boundOpt.get();
    int maxInclStart = stvn.indexOf("#maxIncl");
    int maxInclEnd = maxInclStart + "#maxIncl 50".length();
    assertEquals(maxInclStart, boundDiag.startOffset(), "Prohibited bound must pin strictly to '#maxIncl 50'");
    assertEquals(maxInclEnd, boundDiag.endOffset());

    var rangeOpt = diags.stream()
        .filter(d -> DiagnosticBag.ERR_INVERTED_RANGE.equals(d.errorCode().orElse(null)))
        .findFirst();
    assertTrue(rangeOpt.isPresent(), "Must accumulate ERR_INVERTED_RANGE without short-circuiting");
    var rangeDiag = rangeOpt.get();
    assertTrue(rangeDiag.message().contains("minimum 500 is greater than maximum 50"),
        "Range error message must detail bounds: " + rangeDiag.message());
  }

  @Test
  @DisplayName("TC-DISC-02: Equal bounds [k, k) on discrete integer interval emit ERR_EMPTY_INTERVAL_DOMAIN without secondary inverted range")
  void testDiscreteIntegerEmptyIntervalDomain() {
    String stvn = """
        {
          :defs {
            :EmptyWindow { #minIncl 500 #maxExcl 500 } :Int
          }
          :type :EmptyWindow
          :body 500
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertFalse(result.isSuccess());
    var diags = result.diagnostics();

    var emptyOpt = diags.stream()
        .filter(d -> DiagnosticBag.ERR_EMPTY_INTERVAL_DOMAIN.equals(d.errorCode().orElse(null)))
        .findFirst();
    assertTrue(emptyOpt.isPresent(), "Must emit ERR_EMPTY_INTERVAL_DOMAIN");
    assertTrue(emptyOpt.get().message().contains("discrete integer interval [500, 500) defines an empty domain"));

    // Anti-tautological assertion: Must NOT emit secondary ERR_INVERTED_RANGE
    boolean hasInvertedRange = diags.stream()
        .anyMatch(d -> DiagnosticBag.ERR_INVERTED_RANGE.equals(d.errorCode().orElse(null)));
    assertFalse(hasInvertedRange, "Equal discrete bounds [k, k) must not emit secondary ERR_INVERTED_RANGE");
  }

  @Test
  @DisplayName("TC-DISC-03: Equal bounds [k, k) on discrete exact float emit ERR_EMPTY_INTERVAL_DOMAIN")
  void testDiscreteExactFloatEmptyIntervalDomain() {
    String stvn = """
        {
          :defs {
            :EmptyFloat { #exact #minIncl 50.0 #maxExcl 50.0 } :Float
          }
          :type :EmptyFloat
          :body 50.0
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertFalse(result.isSuccess());
    var diags = result.diagnostics();

    var emptyOpt = diags.stream()
        .filter(d -> DiagnosticBag.ERR_EMPTY_INTERVAL_DOMAIN.equals(d.errorCode().orElse(null)))
        .findFirst();
    assertTrue(emptyOpt.isPresent(), "Must emit ERR_EMPTY_INTERVAL_DOMAIN on discrete exact float");
    assertTrue(emptyOpt.get().message().contains("defines an empty domain"));
  }

  @Test
  @DisplayName("TC-DISC-04: Single-element habitable discrete interval [500, 501) compiles cleanly")
  void testHabitableSingleElementDiscreteInterval() {
    String stvn = """
        {
          :defs {
            :SingleElement { #minIncl 500 #maxExcl 501 } :Int
          }
          :type :SingleElement
          :body 500
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertTrue(result.isSuccess(), "Habitable single-element discrete domain must compile cleanly: " + result.diagnostics());
  }
}
