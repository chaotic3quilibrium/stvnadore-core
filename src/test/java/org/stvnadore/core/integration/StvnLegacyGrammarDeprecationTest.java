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
  @DisplayName("TC-DEP-01: Legacy compound scalar :Int32 emits migration directive")
  void testLegacyInt32Deprecation() {
    String source = "{ :type :Int32 :body 42 }";
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Legacy :Int32 must be rejected");
    var error = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_UNKNOWN_TYPE, error.errorCode().orElse(null));
    assertTrue(error.message().contains("Compound integer keyword ':Int32' is deprecated in 2.0.0"));
    assertTrue(error.message().contains("{ #size 32 } :Int"));
  }

  @Test
  @DisplayName("TC-DEP-02: Legacy compound scalar :Uint16 emits migration directive")
  void testLegacyUint16Deprecation() {
    String source = "{ :type :Uint16 :body 42 }";
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Legacy :Uint16 must be rejected");
    var error = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_UNKNOWN_TYPE, error.errorCode().orElse(null));
    assertTrue(error.message().contains("Compound unsigned integer keyword ':Uint16' is deprecated in 2.0.0"));
    assertTrue(error.message().contains("{ #unsigned #size 16 } :Int"));
  }

  @Test
  @DisplayName("TC-DEP-03: Legacy scalar :FloatExact emits migration directive")
  void testLegacyFloatExactDeprecation() {
    String source = "{ :type :FloatExact :body 42.0 }";
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Legacy :FloatExact must be rejected");
    var error = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_UNKNOWN_TYPE, error.errorCode().orElse(null));
    assertTrue(error.message().contains("{ #exact } :Float"));
  }

  @Test
  @DisplayName("TC-DEP-04: Legacy compound collection :SeqNonEmpty emits migration directive")
  void testLegacySeqNonEmptyDeprecation() {
    String source = "{ :type :SeqNonEmpty(:Int) :body [ 1 ] }";
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Legacy :SeqNonEmpty must be rejected");
    var error = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_UNKNOWN_TYPE, error.errorCode().orElse(null));
    assertTrue(error.message().contains("{ #minSize 1 } :Seq"));
  }

  @Test
  @DisplayName("TC-DEP-05: Legacy compound collection :MapInv emits migration directive")
  void testLegacyMapInvDeprecation() {
    String source = "{ :type :MapInv(:String :Int) :body { [ \"a\" 1 ] } }";
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Legacy :MapInv must be rejected");
    var error = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_UNKNOWN_TYPE, error.errorCode().orElse(null));
    assertTrue(error.message().contains("{ #invertible } :Map"));
  }

  @Test
  @DisplayName("TC-DEP-06: Legacy datetime keywords emit migration directives")
  void testLegacyDateTimeKeywordsDeprecation() {
    for (String legacyType : new String[]{":DateTimeOffset", ":DateTimeZoned", ":DateTimeAudited"}) {
      String source = "{ :type " + legacyType + " :body \"2026-03-15T08:00:00Z\" }";
      var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
      assertTrue(result.hasErrors(), legacyType + " must be rejected");
      var error = result.diagnostics().getFirst();
      assertEquals(DiagnosticBag.ERR_UNKNOWN_TYPE, error.errorCode().orElse(null));
      assertTrue(error.message().contains("use ':DateTime' with mode facet"));
    }
  }

  @Test
  @DisplayName("TC-DEP-07: Legacy epoch keywords emit migration directives")
  void testLegacyEpochKeywordsDeprecation() {
    for (String legacyType : new String[]{":TimeEpochS", ":TimeEpochMs", ":TimeEpochNs"}) {
      String source = "{ :type " + legacyType + " :body 100 }";
      var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
      assertTrue(result.hasErrors(), legacyType + " must be rejected");
      var error = result.diagnostics().getFirst();
      assertEquals(DiagnosticBag.ERR_UNKNOWN_TYPE, error.errorCode().orElse(null));
      assertTrue(error.message().contains("use ':TimeEpoch' with mandatory facet"));
    }
  }

  @Test
  @DisplayName("TC-DEP-08: Legacy fenced string arrow delimiter emits deprecation warning")
  void testLegacyFencedStringArrowDeprecation() {
    String source = """
        {
          :type :String
          :body \"\"\"->[json]
          {"key": "value"}
          [json]\"\"\"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Fenced arrow syntax must trigger deprecation error in strict parser");
    var diag = result.diagnostics().getFirst();
    assertTrue(diag.message().contains("Rule STR-04 deprecation"));
  }
}
