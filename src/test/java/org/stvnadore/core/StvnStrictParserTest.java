package org.stvnadore.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import org.stvnadore.core.ir.StvnValue;

/**
 * Validates strict parser configuration, fail-fast behavior, and diagnostic extraction.
 *
 * @since 1.2.0
 */
class StvnStrictParserTest {

  @Test
  void testStrictParserRejectsIllegalFloatSuffixInCompile() {
    var input = """
        {
          :type :Float32
          :body 1.0f
        }
        """;
    
    // Default compile uses STRICT mode internally
    var ex = Assertions.assertThrows(RuntimeException.class, () -> {
      StvnCompiler.compile(input);
    });
    
    Assertions.assertTrue(ex.getMessage().contains("STVN Syntax Error") || ex.getMessage().contains("mismatched input"));
  }

  @Test
  void testStrictParserRejectsIllegalFloatSuffixInAnalyze() {
    var input = """
        {
          :type :Float32
          :body 1.0f
        }
        """;
    
    var result = StvnCompiler.analyze(input, null, StvnParserConfig.STRICT);
    
    Assertions.assertTrue(result.value().isEmpty());
    Assertions.assertFalse(result.diagnostics().isEmpty());
    
    var diagnostic = result.diagnostics().get(0);
    Assertions.assertTrue(diagnostic.message().contains("STVN Syntax Error") || diagnostic.message().contains("mismatched input"));
    Assertions.assertEquals(3, diagnostic.line());
    Assertions.assertEquals(11, diagnostic.column()); // Offset of 'f' character in "1.0f" (0-indexed)
    Assertions.assertTrue(diagnostic.startOffset() > 0);
  }

  @Test
  void testStrictParserAcceptsCompliantFloat() {
    var input = """
        {
          :type :Float32
          :body 1.0
        }
        """;
    
    var val = StvnCompiler.compile(input, null, StvnParserConfig.STRICT);
    Assertions.assertTrue(val.isPresent());
  }

  @Test
  void testDefaultNonStrictParserAllowsParsingRecoveredAst() {
    var input = """
        {
          :type :Seq(:Option(:Tuple(:Int32)))
          :body [ #Some ( ) ]
        }
        """;
    
    // Under non-strict, parser can recover
    var doc = StvnCompiler.parse(input, StvnParserConfig.DEFAULT);
    Assertions.assertNotNull(doc);
  }

  @Test
  void testStrictParserRejectsBrokenSyntaxImmediately() {
    var input = """
        {
          :type :Int32
          :body @
        }
        """;
    
    var result = StvnCompiler.analyze(input, null, StvnParserConfig.STRICT);
    Assertions.assertTrue(result.value().isEmpty());
    Assertions.assertFalse(result.diagnostics().isEmpty());
    
    var diagnostic = result.diagnostics().get(0);
    Assertions.assertTrue(diagnostic.message().contains("STVN Syntax Error") || diagnostic.message().contains("token recognition error"));
    Assertions.assertEquals(3, diagnostic.line());
  }

  @Test
  void testStrictParserRejectsAmbiguousEitherImplicitResolution() {
    var input = """
        {
          :defs {
            :EitherRepeat :Either( :Int32 :Uint32 )
          }
          :type :EitherRepeat
          :body 1
        }
        """;

    var result = StvnCompiler.analyze(input, null, StvnParserConfig.STRICT);
    Assertions.assertTrue(result.value().isEmpty());
    Assertions.assertFalse(result.diagnostics().isEmpty());

    var diagnostic = result.diagnostics().get(0);
    Assertions.assertTrue(
        diagnostic.message().contains("Ambiguous implicit resolution: Value matches both Left and Right branches of :Either"),
        "Expected canonical ambiguity diagnostic, got: " + diagnostic.message()
    );
  }

  @Test
  @DisplayName("TC-STRICT-RULE-E-01: Strict parser enforces Rule E rejection of untagged Left value")
  void testStrictParserRejectsRuleEViolation() {
    var input = """
        {
          :type :Either( :Int32 :String )
          :body 100
        }
        """;

    var result = StvnCompiler.analyze(input, null, StvnParserConfig.STRICT);
    Assertions.assertTrue(result.value().isEmpty());
    Assertions.assertFalse(result.diagnostics().isEmpty());

    var diagnostic = result.diagnostics().get(0);
    Assertions.assertTrue(
        diagnostic.message().contains("Rule E Violation: Untagged value matching Left branch of :Either is non-inferable; explicit #Left tag is required"),
        "Expected Rule E fatal diagnostic under STRICT mode, got: " + diagnostic.message()
    );
  }
}
