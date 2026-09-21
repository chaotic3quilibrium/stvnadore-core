package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test suite for consolidated temporal standard library types (:TimeEpoch and :DateTime).
 * <p>
 * Verifies unit facet governance on {@code :TimeEpoch} and mutually exclusive mode facets on {@code :DateTime}.
 */
public class StvnTemporalPreludeValidationTest {

  @Test
  @DisplayName("TC-TMP-01: Prohibits :TimeEpoch without mandatory #unit facet")
  void testEpochMissingUnit() {
    String source = """
        {
          :defs {
            :Timestamp :org/stvnadore/prelude/TimeEpoch
          }
          :type :Timestamp
          :body 1710500000
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected compilation error for missing #unit facet");
    var error = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_MISSING_TEMPORAL_FACET, error.errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-TMP-02: Accepts valid :TimeEpoch units (#s, #ms, #ns)")
  void testEpochValidUnits() {
    for (String unit : new String[]{"#s", "#ms", "#ns"}) {
      String source = """
          {
            :defs {
              :Timestamp { #unit %s } :org/stvnadore/prelude/TimeEpoch
            }
            :type :Timestamp
            :body 1710500000
          }
          """.formatted(unit);
      var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
      assertFalse(result.hasErrors(), "Unit " + unit + " must compile cleanly");
    }
  }

  @Test
  @DisplayName("TC-TMP-03: Rejects invalid unit facet on :TimeEpoch")
  void testEpochInvalidUnit() {
    String source = """
        {
          :defs {
            :Timestamp { #unit #hours } :org/stvnadore/prelude/TimeEpoch
          }
          :type :Timestamp
          :body 1710500000
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Invalid unit #hours must be rejected");
    var error = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_INVALID_METADATA_FACET, error.errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-TMP-04: Prohibits :DateTime without mode facet")
  void testDateTimeMissingMode() {
    String source = """
        {
          :defs {
            :EventTime :org/stvnadore/prelude/DateTime
          }
          :type :EventTime
          :body "2026-03-15T08:00:00Z"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected error for :DateTime lacking mode facet");
    var error = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_MISSING_TEMPORAL_FACET, error.errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-TMP-05: Rejects mutually exclusive mode facets on :DateTime")
  void testDateTimeMutuallyExclusiveModes() {
    String source = """
        {
          :defs {
            :EventTime { #offset #zoned } :org/stvnadore/prelude/DateTime
          }
          :type :EventTime
          :body "2026-03-15T08:00:00Z"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Conflicting modes #offset and #zoned must be rejected");
    var error = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE, error.errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-TMP-06: Accepts single mode facets on :DateTime")
  void testDateTimeValidModes() {
    String sourceOffset = """
        {
          :defs {
            :EventTime { #offset } :org/stvnadore/prelude/DateTime
          }
          :type :EventTime
          :body "2026-03-15T08:00:00-05:00"
        }
        """;
    assertFalse(StvnCompiler.compileToResult(sourceOffset, null, StvnParserConfig.DEFAULT).hasErrors());

    String sourceZoned = """
        {
          :defs {
            :EventTime { #zoned } :org/stvnadore/prelude/DateTime
          }
          :type :EventTime
          :body "2026-03-15T08:00:00[America/Chicago]"
        }
        """;
    assertFalse(StvnCompiler.compileToResult(sourceZoned, null, StvnParserConfig.DEFAULT).hasErrors());

    String sourceAudited = """
        {
          :defs {
            :EventTime { #audited } :org/stvnadore/prelude/DateTime
          }
          :type :EventTime
          :body "2026-03-15T08:00:00-05:00[America/Chicago]"
        }
        """;
    assertFalse(StvnCompiler.compileToResult(sourceAudited, null, StvnParserConfig.DEFAULT).hasErrors());
  }
}
