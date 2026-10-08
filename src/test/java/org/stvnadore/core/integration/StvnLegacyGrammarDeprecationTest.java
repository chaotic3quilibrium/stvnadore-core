package org.stvnadore.core.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.validation.DiagnosticBag;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test suite for legacy grammar deprecation diagnostics.
 * <p>
 * Verifies that legacy 1.x compound tokens and syntax features emit clear migration directives
 * guiding users toward the modern STVN 2.0.0 canonical facet architecture.
 */
public class StvnLegacyGrammarDeprecationTest {

  @Test
  @DisplayName("TC-DEP-01: Undeclared legacy compound scalar :Int32 emits ERR_UNDEFINED_TYPE")
  void testLegacyInt32Deprecation() {
    String source = "{ :type :Int32 :body 42 }";
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Undeclared :Int32 must fail closed");
    var error = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_UNDEFINED_TYPE, error.errorCode().orElse(null));
    assertEquals("Undefined type: :Int32", error.message());
  }

  @Test
  @DisplayName("TC-DEP-02: Undeclared legacy compound scalar :Uint16 emits ERR_UNDEFINED_TYPE")
  void testLegacyUint16Deprecation() {
    String source = "{ :type :Uint16 :body 42 }";
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Undeclared :Uint16 must fail closed");
    var error = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_UNDEFINED_TYPE, error.errorCode().orElse(null));
    assertEquals("Undefined type: :Uint16", error.message());
  }

  @Test
  @DisplayName("TC-DEP-03: Undeclared legacy scalar :FloatExact emits ERR_UNDEFINED_TYPE")
  void testLegacyFloatExactDeprecation() {
    String source = "{ :type :FloatExact :body 42.0 }";
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Undeclared :FloatExact must fail closed");
    var error = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_UNDEFINED_TYPE, error.errorCode().orElse(null));
    assertEquals("Undefined type: :FloatExact", error.message());
  }

  @Test
  @DisplayName("TC-DEP-04: Undeclared legacy compound collection :SeqNonEmpty emits ERR_UNDEFINED_TYPE")
  void testLegacySeqNonEmptyDeprecation() {
    String source = "{ :type :SeqNonEmpty :body [ 1 ] }";
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Undeclared :SeqNonEmpty must fail closed");
    var error = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_UNDEFINED_TYPE, error.errorCode().orElse(null));
    assertEquals("Undefined type: :SeqNonEmpty", error.message());
  }

  @Test
  @DisplayName("TC-DEP-05: Undeclared legacy compound collection :MapInv emits ERR_UNDEFINED_TYPE")
  void testLegacyMapInvDeprecation() {
    String source = "{ :type :MapInv :body { [ \"a\" 1 ] } }";
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Undeclared :MapInv must fail closed");
    var error = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_UNDEFINED_TYPE, error.errorCode().orElse(null));
    assertEquals("Undefined type: :MapInv", error.message());
  }

  @Test
  @DisplayName("TC-DEP-06: Undeclared legacy datetime keywords emit ERR_UNDEFINED_TYPE")
  void testLegacyDateTimeKeywordsDeprecation() {
    for (String legacyType : new String[]{":DateTimeOffset", ":DateTimeZoned", ":DateTimeAudited"}) {
      String source = "{ :type " + legacyType + " :body \"2026-03-15T08:00:00Z\" }";
      var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
      assertTrue(result.hasErrors(), legacyType + " must be rejected");
      var error = result.diagnostics().getFirst();
      assertEquals(DiagnosticBag.ERR_UNDEFINED_TYPE, error.errorCode().orElse(null));
      assertEquals("Undefined type: " + legacyType, error.message());
    }
  }

  @Test
  @DisplayName("TC-DEP-07: Undeclared legacy epoch keywords emit ERR_UNDEFINED_TYPE")
  void testLegacyEpochKeywordsDeprecation() {
    for (String legacyType : new String[]{":TimeEpochS", ":TimeEpochMs", ":TimeEpochNs"}) {
      String source = "{ :type " + legacyType + " :body 100 }";
      var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
      assertTrue(result.hasErrors(), legacyType + " must be rejected");
      var error = result.diagnostics().getFirst();
      assertEquals(DiagnosticBag.ERR_UNDEFINED_TYPE, error.errorCode().orElse(null));
      assertEquals("Undefined type: " + legacyType, error.message());
    }
  }

  @Test
  @DisplayName("TC-DEP-08: Prohibited fenced string arrow delimiter emits ERR_PROHIBITED_FENCE_ARROW")
  void testLegacyFencedStringArrowProhibition() {
    String source = """
        {
          :type :String
          :body \"\"\"->[json]
          {"key": "value"}
          \"\"\"[json]
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Prohibited fenced arrow syntax must trigger fatal error");
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_PROHIBITED_FENCE_ARROW, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("Rule STR-04 violation: Fenced string delimiter arrow '->' is prohibited"));
  }
}
