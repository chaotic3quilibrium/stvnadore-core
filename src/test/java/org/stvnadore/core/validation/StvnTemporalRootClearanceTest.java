package org.stvnadore.core.validation;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.stvnadore.core.StvnCompiler;

class StvnTemporalRootClearanceTest {

  @ParameterizedTest
  @ValueSource(strings = {
      ":TimeEpochS",
      ":TimeEpochMs",
      ":TimeEpochNs",
      ":DateTimeOffset",
      ":DateTimeZoned",
      ":DateTimeAudited"
  })
  @DisplayName("Bare temporal type reference triggers ERR_UNKNOWN_TYPE")
  void testBareTemporalReferenceFailsWithUnknownType(String bareType) {
    String source = """
        {
          :type %s
          :body "placeholder"
        }
        """.formatted(bareType);

    var result = StvnCompiler.compileToResult(source);
    Assertions.assertFalse(result.isSuccess(), "Bare temporal type must be rejected: " + bareType);
    Assertions.assertTrue(result.diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_UNKNOWN_TYPE.equals(d.errorCode().orElse(null))
            || DiagnosticBag.ERR_TEMPORAL_SCALE_MISSING.equals(d.errorCode().orElse(null))
            || DiagnosticBag.ERR_DATETIME_MODE_INVALID.equals(d.errorCode().orElse(null))),
        "Must emit error code for " + bareType);
  }

  @Test
  @DisplayName("Purged prelude decoy paths trigger ERR_UNKNOWN_TYPE")
  void testPurgedPreludeDecoyPathsFail() {
    String source = """
        {
          :defs {
            :EventTime { #offset } :org/stvnadore/prelude/DateTime
          }
          :type :EventTime
          :body "2026-03-15T08:00:00-05:00"
        }
        """;

    var result = StvnCompiler.compileToResult(source);
    Assertions.assertFalse(result.isSuccess(), "Purged prelude path must be rejected");
    Assertions.assertTrue(result.diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_UNKNOWN_TYPE.equals(d.errorCode().orElse(null))
            || DiagnosticBag.ERR_PRELUDE_ALIAS_PURGED.equals(d.errorCode().orElse(null))),
        "Must emit ERR_UNKNOWN_TYPE for purged prelude DateTime");
  }

  @Test
  @DisplayName("Kernel primitive :DateTime with #offset facet compiles cleanly")
  void testKernelDateTimeOffset() {
    String source = """
        {
          :defs {
            :EventTime { #offset } :DateTime
          }
          :type :EventTime
          :body "2026-03-15T08:00:00-05:00"
        }
        """;

    var result = StvnCompiler.compileToResult(source);
    Assertions.assertTrue(result.isSuccess(), "Kernel :DateTime must resolve cleanly: " + result.diagnostics());
  }

  @Test
  @DisplayName("Local alias to kernel primitive temporal type compiles cleanly")
  void testLocalAliasTemporalType() {
    String source = """
        {
          :defs {
            :MyDateTime { #offset } :DateTime
          }
          :type :MyDateTime
          :body "2026-03-15T08:00:00-05:00"
        }
        """;

    var result = StvnCompiler.compileToResult(source);
    Assertions.assertTrue(result.isSuccess(), "Local alias must resolve cleanly: " + result.diagnostics());
  }

  @Test
  @DisplayName("Invalid regex format on DateTimeOffset triggers regex violation")
  void testInvalidRegexFormatOnCanonicalDateTimeOffset() {
    String source = """
        {
          :defs {
            :EventTime { #offset } :DateTime
          }
          :type :EventTime
          :body "not-a-valid-datetime"
        }
        """;

    var result = StvnCompiler.compileToResult(source);
    Assertions.assertFalse(result.isSuccess(), "Invalid date string must fail validation");
  }
}
