package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;

import static org.junit.jupiter.api.Assertions.*;

public class StvnTemporalFacetGovernanceTest {

  @Test
  @DisplayName("TC-TEMP-01: Scale facet #us applied to :DateTime emits ERR_INVALID_METADATA_FACET pinned to #us")
  void testScaleFacetOnDateTimeRejected() {
    String stvn = """
        {
          :defs {
            :TradeTimestamp { #audited #us } :DateTime
          }
          :type :TradeTimestamp
          :body "2026-10-29T12:00:00.000000Z"
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertFalse(result.isSuccess());
    var diagOpt = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_INVALID_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst();
    assertTrue(diagOpt.isPresent(), "Expected ERR_INVALID_METADATA_FACET diagnostic");
    var diag = diagOpt.get();
    assertTrue(diag.message().contains("facet '#us' is not permitted on :DateTime"));
    int expectedStart = stvn.indexOf("#us");
    int expectedEnd = expectedStart + "#us".length();
    assertEquals(expectedStart, diag.startOffset(), "startOffset must pin strictly to #us");
    assertEquals(expectedEnd, diag.endOffset(), "endOffset must pin strictly to #us");
  }

  @Test
  @DisplayName("TC-TEMP-02: Mode facet #audited applied to :TimeEpoch emits ERR_INVALID_METADATA_FACET pinned to #audited")
  void testModeFacetOnTimeEpochRejected() {
    String stvn = """
        {
          :defs {
            :EpochTrade { #s #audited } :TimeEpoch
          }
          :type :EpochTrade
          :body 1700000000
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertFalse(result.isSuccess());
    var diagOpt = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_INVALID_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst();
    assertTrue(diagOpt.isPresent(), "Expected ERR_INVALID_METADATA_FACET diagnostic");
    var diag = diagOpt.get();
    assertTrue(diag.message().contains("facet '#audited' is not permitted on :TimeEpoch"));
    int expectedStart = stvn.indexOf("#audited");
    int expectedEnd = expectedStart + "#audited".length();
    assertEquals(expectedStart, diag.startOffset(), "startOffset must pin strictly to #audited");
    assertEquals(expectedEnd, diag.endOffset(), "endOffset must pin strictly to #audited");
  }

  @Test
  @DisplayName("TC-TEMP-03: Scale facet #s applied to :Int emits ERR_INVALID_METADATA_FACET pinned to #s")
  void testScaleFacetOnIntRejected() {
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
    var diagOpt = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_INVALID_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst();
    assertTrue(diagOpt.isPresent(), "Expected ERR_INVALID_METADATA_FACET diagnostic");
    var diag = diagOpt.get();
    assertTrue(diag.message().contains("is not permitted on"));
    int expectedStart = stvn.indexOf("#s");
    int expectedEnd = expectedStart + "#s".length();
    assertEquals(expectedStart, diag.startOffset(), "startOffset must pin strictly to #s");
    assertEquals(expectedEnd, diag.endOffset(), "endOffset must pin strictly to #s");
  }

  @Test
  @DisplayName("TC-TEMP-04: Constant definition :TimeEpoch without scale facet emits ERR_MISSING_TEMPORAL_FACET")
  void testConstantMissingScaleFacetRejected() {
    String stvn = """
        {
          :defs {
            #DEFAULT_EPOCH :TimeEpoch 1700000000
          }
          :type :Int
          :body 1
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertFalse(result.isSuccess());
    var diagOpt = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_MISSING_TEMPORAL_FACET.equals(d.errorCode().orElse(null)))
        .findFirst();
    assertTrue(diagOpt.isPresent(), "Expected ERR_MISSING_TEMPORAL_FACET diagnostic on constant");
    var diag = diagOpt.get();
    int expectedStart = stvn.indexOf(":TimeEpoch");
    int expectedEnd = expectedStart + ":TimeEpoch".length();
    assertEquals(expectedStart, diag.startOffset(), "startOffset must pin strictly to :TimeEpoch");
    assertEquals(expectedEnd, diag.endOffset(), "endOffset must pin strictly to :TimeEpoch");
  }

  @Test
  @DisplayName("TC-TEMP-05: Missing mode facet on :DateTime pins strictly to atomicType token")
  void testMissingModeFacetCoordinatesPinning() {
    String stvn = """
        {
          :defs {
            :MissingMode :DateTime
          }
          :type :MissingMode
          :body "2026-10-29T12:00:00Z"
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertFalse(result.isSuccess());
    var diagOpt = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_MISSING_TEMPORAL_FACET.equals(d.errorCode().orElse(null)))
        .findFirst();
    assertTrue(diagOpt.isPresent(), "Expected ERR_MISSING_TEMPORAL_FACET diagnostic");
    var diag = diagOpt.get();
    int expectedStart = stvn.indexOf(":DateTime");
    int expectedEnd = expectedStart + ":DateTime".length();
    assertEquals(expectedStart, diag.startOffset(), "startOffset must pin strictly to :DateTime");
    assertEquals(expectedEnd, diag.endOffset(), "endOffset must pin strictly to :DateTime");
  }

  @Test
  @DisplayName("TC-TEMP-06: Scale facet #us preceding mode facet #audited on :DateTime emits ERR_INVALID_METADATA_FACET")
  void testScaleFacetPrecedingModeFacetOnDateTimeRejected() {
    String stvn = """
        {
          :defs {
            :TradeTimestamp { #us #audited } :DateTime
          }
          :type :TradeTimestamp
          :body "2026-10-29T12:00:00.000000Z"
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertFalse(result.isSuccess());
    var diagOpt = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_INVALID_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst();
    assertTrue(diagOpt.isPresent(), "Expected ERR_INVALID_METADATA_FACET diagnostic");
    var diag = diagOpt.get();
    assertTrue(diag.message().contains("facet '#us' is not permitted on :DateTime"));
    int expectedStart = stvn.indexOf("#us");
    int expectedEnd = expectedStart + "#us".length();
    assertEquals(expectedStart, diag.startOffset(), "startOffset must pin strictly to #us");
    assertEquals(expectedEnd, diag.endOffset(), "endOffset must pin strictly to #us");
    // Assert conforming sibling #audited emits zero false order violation errors
    long orderViolations = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_FACET_ORDER_VIOLATION.equals(d.errorCode().orElse(null)))
        .count();
    assertEquals(0, orderViolations, "Conforming sibling #audited must not produce secondary ERR_FACET_ORDER_VIOLATION");
  }
}
