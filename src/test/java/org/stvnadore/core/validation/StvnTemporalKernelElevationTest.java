package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;

import java.util.Optional;

/**
 * Verification test suite for Pass 2 Temporal Kernel Elevation and Discrete Bounds.
 * <p>
 * Verifies kernel primitive elevation for {@code :TimeEpoch} and {@code :DateTime},
 * scale flag mutual exclusivity, elimination of {@code #unit}, and universal discrete
 * half-open interval governance.
 */
@NullMarked
public class StvnTemporalKernelElevationTest {

  @Test
  @DisplayName("TC-ELEV-01: Kernel primitive :TimeEpoch compiles cleanly with bare scale flag #s")
  void testTimeEpochWithSecondsScale() {
    String source = """
        {
          :defs {
            :Timestamp { #s } :TimeEpoch
          }
          :type :Timestamp
          :body 1710500000
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    Assertions.assertTrue(result.isSuccess(), "Kernel :TimeEpoch with #s must compile cleanly");
  }

  @Test
  @DisplayName("TC-ELEV-02: Kernel primitive :TimeEpoch compiles cleanly with bare scale flag #ms")
  void testTimeEpochWithMillisScale() {
    String source = """
        {
          :defs {
            :Timestamp { #ms } :TimeEpoch
          }
          :type :Timestamp
          :body 1710500000000
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    Assertions.assertTrue(result.isSuccess(), "Kernel :TimeEpoch with #ms must compile cleanly");
  }

  @Test
  @DisplayName("TC-ELEV-03: Kernel primitive :TimeEpoch compiles cleanly with bare scale flag #us")
  void testTimeEpochWithMicrosScale() {
    String source = """
        {
          :defs {
            :Timestamp { #us } :TimeEpoch
          }
          :type :Timestamp
          :body 1710500000000000
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    Assertions.assertTrue(result.isSuccess(), "Kernel :TimeEpoch with #us must compile cleanly");
  }

  @Test
  @DisplayName("TC-ELEV-04: Kernel primitive :TimeEpoch compiles cleanly with bare scale flag #ns")
  void testTimeEpochWithNanosScale() {
    String source = """
        {
          :defs {
            :Timestamp { #ns } :TimeEpoch
          }
          :type :Timestamp
          :body 1710500000000000000
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    Assertions.assertTrue(result.isSuccess(), "Kernel :TimeEpoch with #ns must compile cleanly");
  }

  @Test
  @DisplayName("TC-ELEV-05: Missing scale flag on :TimeEpoch emits ERR_MISSING_TEMPORAL_FACET")
  void testTimeEpochMissingScaleEmitsDiagnostic() {
    String source = """
        {
          :defs {
            :Timestamp :TimeEpoch
          }
          :type :Timestamp
          :body 1710500000
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    Assertions.assertFalse(result.isSuccess(), "Omission of scale on :TimeEpoch must fail compilation");
    Assertions.assertTrue(result.diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_MISSING_TEMPORAL_FACET.equals(d.errorCode().orElse(null))),
        "Must emit ERR_MISSING_TEMPORAL_FACET for unscaled :TimeEpoch");
  }

  @Test
  @DisplayName("TC-ELEV-06: Conflicting scale flags on :TimeEpoch emit ERR_MUTUALLY_EXCLUSIVE")
  void testTimeEpochConflictingScalesEmitDiagnostic() {
    String source = """
        {
          :defs {
            :Timestamp { #s #ms } :TimeEpoch
          }
          :type :Timestamp
          :body 1710500000
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    Assertions.assertFalse(result.isSuccess(), "Conflicting scales must fail compilation");
    Assertions.assertTrue(result.diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE.equals(d.errorCode().orElse(null))),
        "Must emit ERR_MUTUALLY_EXCLUSIVE for conflicting scale flags");
  }

  @Test
  @DisplayName("TC-ELEV-07: Legacy #unit keyword triggers STVN_SYNTAX_ERROR")
  void testLegacyUnitKeywordRejected() {
    String source = """
        {
          :defs {
            :Timestamp { #unit #s } :TimeEpoch
          }
          :type :Timestamp
          :body 1710500000
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    Assertions.assertFalse(result.isSuccess(), "Legacy #unit keyword must be rejected by grammar");
    Assertions.assertTrue(result.diagnostics().stream()
        .anyMatch(d -> "STVN_SYNTAX_ERROR".equals(d.errorCode().orElse(null))),
        "Must emit STVN_SYNTAX_ERROR when #unit keyword is encountered");
  }

  @Test
  @DisplayName("TC-ELEV-08: Discrete half-open intervals on :TimeEpoch accept #minIncl and #maxExcl")
  void testTimeEpochDiscreteHalfOpenIntervalSuccess() {
    String source = """
        {
          :defs {
            :WindowedEpoch { #s #minIncl 1710500000 #maxExcl 1710586400 } :TimeEpoch
          }
          :type :WindowedEpoch
          :body 1710550000
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    Assertions.assertTrue(result.isSuccess(), "Half-open [minIncl, maxExcl) on :TimeEpoch must compile cleanly");
  }

  @Test
  @DisplayName("TC-ELEV-09: Discrete bound violation (#maxIncl) on :TimeEpoch emits ERR_DISCRETE_BOUND_KIND_PROHIBITED")
  void testTimeEpochProhibitsMaxIncl() {
    String source = """
        {
          :defs {
            :BadEpoch { #s #minIncl 0 #maxIncl 1000 } :TimeEpoch
          }
          :type :BadEpoch
          :body 500
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    Assertions.assertFalse(result.isSuccess(), "#maxIncl on :TimeEpoch must be rejected");
    Assertions.assertTrue(result.diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_DISCRETE_BOUND_KIND_PROHIBITED.equals(d.errorCode().orElse(null))),
        "Must emit ERR_DISCRETE_BOUND_KIND_PROHIBITED for #maxIncl on :TimeEpoch");
  }

  @Test
  @DisplayName("TC-ELEV-10: Discrete bound violation (#minExcl) on :TimeEpoch emits ERR_DISCRETE_BOUND_KIND_PROHIBITED")
  void testTimeEpochProhibitsMinExcl() {
    String source = """
        {
          :defs {
            :BadEpoch { #s #minExcl 0 #maxExcl 1000 } :TimeEpoch
          }
          :type :BadEpoch
          :body 500
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    Assertions.assertFalse(result.isSuccess(), "#minExcl on :TimeEpoch must be rejected");
    Assertions.assertTrue(result.diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_DISCRETE_BOUND_KIND_PROHIBITED.equals(d.errorCode().orElse(null))),
        "Must emit ERR_DISCRETE_BOUND_KIND_PROHIBITED for #minExcl on :TimeEpoch");
  }

  @Test
  @DisplayName("TC-ELEV-11: Discrete half-open intervals on :DateTime accept string #minIncl and #maxExcl")
  void testDateTimeDiscreteHalfOpenIntervalSuccess() {
    String source = """
        {
          :defs {
            :WindowedDate {
              #offset
              #minIncl "2026-01-01T00:00:00Z"
              #maxExcl "2027-01-01T00:00:00Z"
            } :DateTime
          }
          :type :WindowedDate
          :body "2026-06-01T12:00:00Z"
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    Assertions.assertTrue(result.isSuccess(), "String half-open bounds on :DateTime must compile cleanly: " + result.diagnostics());
  }

  @Test
  @DisplayName("TC-ELEV-12: Discrete bound violation (#maxIncl) on :DateTime emits ERR_DISCRETE_BOUND_KIND_PROHIBITED")
  void testDateTimeProhibitsMaxIncl() {
    String source = """
        {
          :defs {
            :BadDate { #offset #maxIncl "2026-12-31T23:59:59Z" } :DateTime
          }
          :type :BadDate
          :body "2026-06-01T12:00:00Z"
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    Assertions.assertFalse(result.isSuccess(), "#maxIncl on :DateTime must be rejected");
    Assertions.assertTrue(result.diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_DISCRETE_BOUND_KIND_PROHIBITED.equals(d.errorCode().orElse(null))),
        "Must emit ERR_DISCRETE_BOUND_KIND_PROHIBITED for #maxIncl on :DateTime");
  }

  @Test
  @DisplayName("TC-ELEV-13: Discrete bound violation (#minExcl) on :DateTime emits ERR_DISCRETE_BOUND_KIND_PROHIBITED")
  void testDateTimeProhibitsMinExcl() {
    String source = """
        {
          :defs {
            :BadDate { #offset #minExcl "2026-01-01T00:00:00Z" #maxExcl "2027-01-01T00:00:00Z" } :DateTime
          }
          :type :BadDate
          :body "2026-06-01T12:00:00Z"
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    Assertions.assertFalse(result.isSuccess(), "#minExcl on :DateTime must be rejected");
    Assertions.assertTrue(result.diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_DISCRETE_BOUND_KIND_PROHIBITED.equals(d.errorCode().orElse(null))),
        "Must emit ERR_DISCRETE_BOUND_KIND_PROHIBITED for #minExcl on :DateTime");
  }

  @Test
  @DisplayName("TC-ELEV-14: Numeric bound literal on :DateTime emits ERR_INCOMPATIBLE_TYPE")
  void testDateTimeRejectsNumericBoundLiteral() {
    String source = """
        {
          :defs {
            :BadDate { #offset #minIncl 12345 } :DateTime
          }
          :type :BadDate
          :body "2026-06-01T12:00:00Z"
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    Assertions.assertFalse(result.isSuccess(), "Numeric bound on :DateTime must be rejected");
    Assertions.assertTrue(result.diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_INCOMPATIBLE_TYPE.equals(d.errorCode().orElse(null))),
        "Must emit ERR_INCOMPATIBLE_TYPE for numeric bound literal on :DateTime");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      ":TimeEpoch",
      ":DateTime"
  })
  @DisplayName("TC-ELEV-15: Direct kernel primitives without enclosing metadata fail validation")
  void testBarePrimitivesWithoutFacetsFail(String primitive) {
    String source = """
        {
          :type %s
          :body 0
        }
        """.formatted(primitive);
    var result = StvnCompiler.compileToResult(source);
    Assertions.assertFalse(result.isSuccess(), "Bare " + primitive + " must fail validation");
    Assertions.assertTrue(result.diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_MISSING_TEMPORAL_FACET.equals(d.errorCode().orElse(null))),
        "Must emit ERR_MISSING_TEMPORAL_FACET for bare " + primitive);
  }
}
