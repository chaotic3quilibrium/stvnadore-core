package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test suite for the canonical 5-Tier Decimal Hierarchy (§ 6.1).
 */
public class StvnFiveTierOrderTest {

  @Test
  @DisplayName("TC-5TIER-01: Valid canonical 5-tier facet declaration compiles cleanly")
  void testValidCanonicalFiveTierOrder() {
    String stvn = """
        {
          :defs {
            :ValidInt { #unsigned #size 16 #minIncl 1 #maxExcl 100 } :Int
          }
          :type :ValidInt
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertTrue(result.isSuccess(), "Canonical 5-tier order must compile cleanly: " + result.diagnostics());
    assertEquals(0, result.diagnostics().size(), "Must contain zero diagnostics");
  }

  @Test
  @DisplayName("TC-5TIER-02: Intra-tier order violation emits ERR_FACET_ORDER_VIOLATION")
  void testIntraTierOrderViolation() {
    String stvn = """
        {
          :defs {
            :BadOrderInt { #size 16 #unsigned } :Int
          }
          :type :BadOrderInt
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertFalse(result.isSuccess(), "Intra-tier violation (#size before #unsigned) must fail compilation");
    var diagOpt = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_FACET_ORDER_VIOLATION.equals(d.errorCode().orElse(null)))
        .findFirst();
    assertTrue(diagOpt.isPresent(), "Expected ERR_FACET_ORDER_VIOLATION");
    var diag = diagOpt.get();
    assertTrue(diag.message().contains("violates canonical 5-tier order after '#size'"),
        "Error message must reference canonical 5-tier order: " + diag.message());
    int expectedStart = stvn.indexOf("#unsigned");
    int expectedEnd = expectedStart + "#unsigned".length();
    assertEquals(expectedStart, diag.startOffset(), "Diagnostic must pin strictly to '#unsigned'");
    assertEquals(expectedEnd, diag.endOffset(), "Diagnostic end must match '#unsigned' end");
  }

  @Test
  @DisplayName("TC-5TIER-03: Inter-tier order violation Tier 2 before Tier 1 emits ERR_FACET_ORDER_VIOLATION")
  void testInterTierOrderViolationTier2BeforeTier1() {
    String stvn = """
        {
          :defs {
            :BadTierInt { #equatable #TRUE #size 32 } :Int
          }
          :type :BadTierInt
          :body 10
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertFalse(result.isSuccess());
    var diagOpt = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_FACET_ORDER_VIOLATION.equals(d.errorCode().orElse(null)))
        .findFirst();
    assertTrue(diagOpt.isPresent(), "Expected ERR_FACET_ORDER_VIOLATION");
    var diag = diagOpt.get();
    assertTrue(diag.message().contains("violates canonical 5-tier order after '#equatable'"),
        "Message must specify 5-tier order violation after #equatable: " + diag.message());
    int expectedStart = stvn.indexOf("#size");
    int expectedEnd = expectedStart + "#size 32".length();
    assertEquals(expectedStart, diag.startOffset(), "Diagnostic must pin strictly to '#size 32'");
  }

  @Test
  @DisplayName("TC-5TIER-04: Inter-tier order violation Tier 3 before Tier 2 emits ERR_FACET_ORDER_VIOLATION")
  void testInterTierOrderViolationTier3BeforeTier2() {
    String stvn = """
        {
          :defs {
            :BadTierOrder { #minIncl 10 #equatable #TRUE } :Int
          }
          :type :BadTierOrder
          :body 25
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertFalse(result.isSuccess());
    var diagOpt = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_FACET_ORDER_VIOLATION.equals(d.errorCode().orElse(null)))
        .findFirst();
    assertTrue(diagOpt.isPresent(), "Expected ERR_FACET_ORDER_VIOLATION");
    var diag = diagOpt.get();
    assertTrue(diag.message().contains("violates canonical 5-tier order after '#minIncl'"),
        "Message must specify 5-tier order violation after #minIncl: " + diag.message());
  }

  @Test
  @DisplayName("TC-5TIER-05: Constraint Tier 4 preceding Definition Tier 1 emits ERR_FACET_ORDER_VIOLATION")
  void testConstraintBeforeDefinitionViolation() {
    String stvn = """
        {
          :defs {
            :BadString { #regex "^[A-Z]+$" #minSize 1 } :String
          }
          :type :BadString
          :body "ABC"
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertFalse(result.isSuccess());
    var diagOpt = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_FACET_ORDER_VIOLATION.equals(d.errorCode().orElse(null)))
        .findFirst();
    assertTrue(diagOpt.isPresent(), "Expected ERR_FACET_ORDER_VIOLATION");
    var diag = diagOpt.get();
    assertTrue(diag.message().contains("violates canonical 5-tier order after '#regex'"),
        "Message must specify 5-tier order violation after #regex: " + diag.message());
  }
}
