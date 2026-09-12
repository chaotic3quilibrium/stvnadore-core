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
        .anyMatch(d -> DiagnosticBag.ERR_UNKNOWN_TYPE.equals(d.errorCode().orElse(null))),
        "Must emit ERR_UNKNOWN_TYPE for " + bareType);
  }

  @Test
  @DisplayName("Canonical prelude path :org/stvnadore/prelude/DateTimeOffset compiles cleanly")
  void testCanonicalPreludeDateTimeOffset() {
    String source = """
        {
          :type :org/stvnadore/prelude/DateTimeOffset
          :body "2026-03-15T08:00:00-05:00"
        }
        """;

    var result = StvnCompiler.compileToResult(source);
    Assertions.assertTrue(result.isSuccess(), "Canonical prelude path must resolve cleanly: " + result.diagnostics());
  }

  @Test
  @DisplayName("Local alias to canonical prelude temporal type compiles cleanly")
  void testLocalAliasTemporalType() {
    String source = """
        {
          :defs {
            :DateTimeOffset :org/stvnadore/prelude/DateTimeOffset
          }
          :type :DateTimeOffset
          :body "2026-03-15T08:00:00-05:00"
        }
        """;

    var result = StvnCompiler.compileToResult(source);
    Assertions.assertTrue(result.isSuccess(), "Local alias must resolve cleanly: " + result.diagnostics());
  }

  @Test
  @DisplayName("Invalid regex format on canonical DateTimeOffset triggers regex violation")
  void testInvalidRegexFormatOnCanonicalDateTimeOffset() {
    String source = """
        {
          :type :org/stvnadore/prelude/DateTimeOffset
          :body "not-a-valid-datetime"
        }
        """;

    var result = StvnCompiler.compileToResult(source);
    Assertions.assertFalse(result.isSuccess(), "Invalid date string must fail validation");
  }
}
