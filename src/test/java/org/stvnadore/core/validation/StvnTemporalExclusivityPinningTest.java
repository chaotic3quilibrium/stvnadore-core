package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test suite for base-type gated temporal exclusivity and coordinate pinning (Target B & C).
 */
public class StvnTemporalExclusivityPinningTest {

  @Test
  @DisplayName("TC-TMP-PIN-01: Conflicting modes (#offset #zoned) emits exactly one ERR_MUTUALLY_EXCLUSIVE pinned to #zoned")
  void testDateTimeModeExclusivityExactPinning() {
    String stvn = """
        {
          :defs {
            :AuditMarker { #offset #zoned } :DateTime
          }
          :type :AuditMarker
          :body "2026-10-30T12:00:00Z"
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertFalse(result.isSuccess(), "Conflicting modes must fail compilation");
    var diags = result.diagnostics();
    assertEquals(1, diags.size(), "Must emit exactly one diagnostic without whole-map duplication");

    var diag = diags.getFirst();
    assertEquals(DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE, diag.errorCode().orElse(null));
    int expectedStart = stvn.indexOf("#zoned");
    int expectedEnd = expectedStart + "#zoned".length();
    assertEquals(expectedStart, diag.startOffset(), "Diagnostic must pin strictly to '#zoned'");
    assertEquals(expectedEnd, diag.endOffset(), "Diagnostic end must match '#zoned'");
    assertTrue(diag.message().contains("Temporal mode facets (#offset, #zoned, #audited) are mutually exclusive"));

    // Anti-tautological assertions: verify #offset and delimiters have zero diagnostics
    int offsetStart = stvn.indexOf("#offset");
    int lbrace = stvn.indexOf('{', stvn.indexOf(":AuditMarker"));
    int rbrace = stvn.indexOf('}', lbrace);
    assertNotEquals(offsetStart, diag.startOffset(), "#offset must not be blamed");
    assertNotEquals(lbrace, diag.startOffset(), "Opening brace must not be blamed");
    assertNotEquals(rbrace, diag.endOffset(), "Closing brace must not be blamed");
  }

  @Test
  @DisplayName("TC-TMP-PIN-02: Conflicting scale facets (#ms #s) on :TimeEpoch emits ERR_MUTUALLY_EXCLUSIVE pinned to #s")
  void testTimeEpochScaleExclusivityExactPinning() {
    String stvn = """
        {
          :defs {
            :Timestamp { #ms #s } :TimeEpoch
          }
          :type :Timestamp
          :body 1700000000
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertFalse(result.isSuccess());
    var diags = result.diagnostics();
    assertEquals(1, diags.size(), "Must emit exactly one diagnostic");

    var diag = diags.getFirst();
    assertEquals(DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE, diag.errorCode().orElse(null));
    int expectedStart = stvn.indexOf("#s");
    int expectedEnd = expectedStart + "#s".length();
    assertEquals(expectedStart, diag.startOffset(), "Diagnostic must pin strictly to '#s'");
    assertEquals(expectedEnd, diag.endOffset(), "Diagnostic end must match '#s'");
  }

  @Test
  @DisplayName("TC-TMP-PIN-03: Unrecognized facet (#milli) alongside conflicting modes (#offset #zoned) isolates both errors")
  void testUnrecognizedFacetRecoveryWithConflictingModes() {
    String stvn = """
        {
          :defs {
            :AuditMarker { #milli #offset #zoned } :DateTime
          }
          :type :AuditMarker
          :body "2026-10-30T12:00:00Z"
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertFalse(result.isSuccess());
    var diags = result.diagnostics();
    assertEquals(2, diags.size(), "Must emit exactly two diagnostics: unrecognized facet and mutex");

    var unrecOpt = diags.stream()
        .filter(d -> DiagnosticBag.ERR_UNRECOGNIZED_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst();
    assertTrue(unrecOpt.isPresent(), "Must contain ERR_UNRECOGNIZED_METADATA_FACET");
    var unrec = unrecOpt.get();
    int milliStart = stvn.indexOf("#milli");
    int milliEnd = milliStart + "#milli".length();
    assertEquals(milliStart, unrec.startOffset(), "Unrecognized facet must pin strictly to '#milli'");
    assertEquals(milliEnd, unrec.endOffset());

    var mutexOpt = diags.stream()
        .filter(d -> DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE.equals(d.errorCode().orElse(null)))
        .findFirst();
    assertTrue(mutexOpt.isPresent(), "Must contain ERR_MUTUALLY_EXCLUSIVE");
    var mutex = mutexOpt.get();
    int zonedStart = stvn.indexOf("#zoned");
    int zonedEnd = zonedStart + "#zoned".length();
    assertEquals(zonedStart, mutex.startOffset(), "Mutex diagnostic must pin strictly to '#zoned'");
    assertEquals(zonedEnd, mutex.endOffset());
  }

  @Test
  @DisplayName("TC-TMP-PIN-04: Scale facets on non-temporal type :Int emit ERR_INVALID_METADATA_FACET without false mutex")
  void testScaleFacetsOnNonTemporalTypeEmitTargetValidityErrors() {
    String stvn = """
        {
          :defs {
            :BadInt { #s } :Int
          }
          :type :BadInt
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertFalse(result.isSuccess());
    var diags = result.diagnostics();
    assertTrue(diags.stream().anyMatch(d -> DiagnosticBag.ERR_INVALID_METADATA_FACET.equals(d.errorCode().orElse(null))));
    assertFalse(diags.stream().anyMatch(d -> DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE.equals(d.errorCode().orElse(null))),
        "Scale on :Int must not emit temporal mutex");
  }
}
