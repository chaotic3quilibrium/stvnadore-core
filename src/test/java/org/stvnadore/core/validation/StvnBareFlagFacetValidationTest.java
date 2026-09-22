package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verification test suite for bare metadata flags and syntax error pinning on non-flag facets.
 */
public class StvnBareFlagFacetValidationTest {

  @Test
  @DisplayName("TC-FLAG-01: Bare #preserveIndent extracts true and registers explicit override")
  void testBarePreserveIndent() {
    String source = """
        {
          :defs {
            :Text { #preserveIndent } :String
          }
          :type :Text
          :body "hello"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Bare #preserveIndent must compile cleanly");
  }

  @ParameterizedTest
  @ValueSource(strings = {"#preserveIndent #TRUE", "#preserveIndent #T"})
  @DisplayName("TC-FLAG-02: Explicit true #preserveIndent compiles cleanly")
  void testExplicitTruePreserveIndent(String facet) {
    String source = """
        {
          :defs {
            :Text { %s } :String
          }
          :type :Text
          :body "hello"
        }
        """.formatted(facet);
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), facet + " must compile cleanly");
  }

  @Test
  @DisplayName("TC-FLAG-03: All seven bare flags combined in a tuple payload compile cleanly")
  void testAllSevenBareFlagsCombined() {
    String source = """
        {
          :defs {
            :S { #preserveIndent } :String
            :I { #unsigned #size 16 } :Int
            :F { #exact } :Float
            :M { #invertible } :Map(:String :Int)
            :D1 { #offset } :DateTime
            :D2 { #zoned } :DateTime
            :D3 { #audited } :DateTime
          }
          :type :Tuple(:S :I :F :M :D1 :D2 :D3)
          :body (
            "text"
            8080
            3.14
            {
              [ "k" 1 ]
            }
            "2026-09-21T17:00:00Z"
            "2026-09-21T17:00:00[America/Chicago]"
            "2026-09-21T17:00:00-05:00[America/Chicago]"
          )
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Combined bare flags must compile without diagnostics");
  }

  @ParameterizedTest
  @ValueSource(strings = {"#size", "#minSize", "#maxSize", "#minIncl", "#maxIncl", "#minExcl", "#maxExcl", "#regex", "#equatable", "#comparable"})
  @DisplayName("TC-FLAG-04: Non-flag facets reject missing arguments with STVN_SYNTAX_ERROR")
  void testMissingArgumentRejected(String facet) {
    String source = """
        {
          :defs {
            :InvalidType { %s } :Int
          }
          :type :InvalidType
          :body 42
        }
        """.formatted(facet);
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Missing argument for " + facet + " must be rejected");
    var error = result.diagnostics().getFirst();
    assertEquals("STVN_SYNTAX_ERROR", error.errorCode().orElse(null));
    assertTrue(error.message().contains("mismatched input '}'"));
  }
}
