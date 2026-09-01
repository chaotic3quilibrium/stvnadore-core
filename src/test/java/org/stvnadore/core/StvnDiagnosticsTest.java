package org.stvnadore.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.ir.StvnValue.StvnEither;
import org.stvnadore.core.ir.StvnValue.StvnFloat;
import org.stvnadore.core.ir.StvnValue.StvnInteger;
import org.stvnadore.core.ir.StvnValue.StvnOption;
import org.stvnadore.core.ir.StvnValue.StvnSeq;
import org.stvnadore.core.ir.StvnValue.StvnString;
import org.stvnadore.core.ir.StvnValue.StvnTuple;
import org.stvnadore.core.validation.MalformedPayloadException;
import org.stvnadore.core.validation.StvnSyntaxCancellationException;

/**
 * Verification test suite for sanitized ANTLR syntax diagnostics and non-empty enum grammar enforcement.
 *
 * @since 1.4.0
 */
public class StvnDiagnosticsTest {

  @Test
  @DisplayName("Empty enum :Enum[] is rejected with sanitized diagnostic")
  void testEmptyEnumRejectedWithSanitizedDiagnostic() {
    String input = """
        {
          :type :Enum[]
          :body #Variant
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.hasErrors(), "Empty enum :Enum[] must fail compilation");
    Assertions.assertFalse(result.isSuccess());

    var diag = result.diagnostics().getFirst();
    Assertions.assertTrue(
        diag.message().contains("empty enum variant list: :Enum[] requires at least one value keyword variant") ||
        diag.message().contains("<value keyword>"),
        "Expected clean diagnostic message but got: " + diag.message()
    );
    Assertions.assertFalse(diag.message().contains("KW_"), "Diagnostic must not leak raw ANTLR token names: " + diag.message());
    Assertions.assertFalse(diag.message().contains("ATOM_"), "Diagnostic must not dump raw vocabulary: " + diag.message());
    Assertions.assertFalse(diag.message().contains("TYPE_KEYWORD_BASE"), "Diagnostic must not leak internal token names");
  }

  @Test
  @DisplayName("Strict compile immediately throws on empty enum :Enum[]")
  void testStrictCompileThrowsOnEmptyEnum() {
    String input = """
        {
          :type :Enum[]
          :body #Variant
        }
        """;

    var ex = Assertions.assertThrows(RuntimeException.class, () -> StvnCompiler.compile(input));
    Assertions.assertTrue(ex.getMessage().contains("STVN Syntax Error"));
    Assertions.assertTrue(
        ex.getMessage().contains("empty enum variant list") || ex.getMessage().contains("<value keyword>")
    );
  }

  @Test
  @DisplayName("Empty composite :Tuple() produces concise diagnostic")
  void testEmptyTupleProducesConciseDiagnostic() {
    String input = """
        {
          :type :Tuple()
          :body ( 42 )
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.hasErrors(), "Empty :Tuple() must produce error diagnostics");

    var diag = result.diagnostics().getFirst();
    Assertions.assertTrue(
        diag.message().contains("empty composite argument list: :Tuple() requires at least one schema type") ||
        diag.message().contains("<schema type>"),
        "Expected concise message but got: " + diag.message()
    );
    Assertions.assertFalse(diag.message().contains(":FloatExact"), "Must not dump full 30+ token vocabulary: " + diag.message());
    Assertions.assertFalse(diag.message().contains("ATOM_BOOLEAN"), "Must not dump raw token constants");
  }

  @Test
  @DisplayName("Empty composite :Union() produces concise diagnostic")
  void testEmptyUnionProducesConciseDiagnostic() {
    String input = """
        {
          :type :Union()
          :body #1 42
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.hasErrors(), "Empty :Union() must produce error diagnostics");

    var diag = result.diagnostics().getFirst();
    Assertions.assertTrue(
        diag.message().contains("empty composite argument list: :Union() requires at least one schema type") ||
        diag.message().contains("<schema type>"),
        "Expected concise message but got: " + diag.message()
    );
    Assertions.assertFalse(diag.message().contains(":DateTimeAudited"), "Must not dump full vocabulary: " + diag.message());
  }

  @Test
  @DisplayName("Nested empty enum in :Tuple(:Enum[]) fails with clean syntax error")
  void testNestedEmptyEnumInTupleFails() {
    String input = """
        {
          :type :Tuple(:Enum[])
          :body ( #V )
        }
        """;

    var ex = Assertions.assertThrows(RuntimeException.class, () -> StvnCompiler.compile(input));
    Assertions.assertTrue(ex.getMessage().contains("STVN Syntax Error"));
    Assertions.assertTrue(
        ex.getMessage().contains("empty enum variant list") || ex.getMessage().contains("<value keyword>")
    );
  }

  @Test
  @DisplayName("Nested empty enum in :Union(:Int32 :Enum[]) fails with clean syntax error")
  void testNestedEmptyEnumInUnionFails() {
    String input = """
        {
          :type :Union(:Int32 :Enum[])
          :body #1 42
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    Assertions.assertTrue(
        diag.message().contains("empty enum variant list") || diag.message().contains("<value keyword>")
    );
  }

  @Test
  @DisplayName("Empty enum declared in :defs fails eagerly during compilation")
  void testEmptyEnumInDefsFailsEagerly() {
    String input = """
        {
          :defs {
            :EmptyEnum :Enum[]
          }
          :type :String
          :body "valid string"
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.hasErrors(), "Empty enum in :defs must fail compilation");
    var diag = result.diagnostics().getFirst();
    Assertions.assertTrue(
        diag.message().contains("empty enum variant list") || diag.message().contains("<value keyword>")
    );
  }

  @Test
  @DisplayName("Valid enum with single variant compiles cleanly")
  void testSingleVariantEnumCompilesCleanly() {
    String input = """
        {
          :type :Enum[ #Solo ]
          :body #Solo
        }
        """;

    var res = StvnCompiler.compile(input);
    Assertions.assertTrue(res.isPresent());
  }

  @Test
  @DisplayName("Valid enum with multiple variants compiles cleanly")
  void testMultipleVariantEnumCompilesCleanly() {
    String input = """
        {
          :defs {
            :TrafficLight :Enum[ #Red #Yellow #Green ]
          }
          :type :TrafficLight
          :body #Green
        }
        """;

    var res = StvnCompiler.compile(input);
    Assertions.assertTrue(res.isPresent());
  }

  @Test
  @DisplayName("Empty composite :Set() produces canonical collection diagnostic")
  void testEmptySetProducesCanonicalDiagnostic() {
    String input = """
        {
          :type :Set()
          :body []
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.hasErrors(), "Empty :Set() must fail compilation");
    Assertions.assertFalse(result.isSuccess());

    var diag = result.diagnostics().getFirst();
    Assertions.assertEquals(
        "STVN Syntax Error: empty composite argument list: collection requires schema type argument",
        diag.message()
    );
    Assertions.assertFalse(diag.message().contains("COLL_SET"), "Must not leak internal token names");
    Assertions.assertFalse(diag.message().contains("TYPE_KEYWORD_BASE"), "Must not leak lexer rules");
  }

  @Test
  @DisplayName("Empty composite :SetNonEmpty() produces canonical collection diagnostic")
  void testEmptySetNonEmptyProducesCanonicalDiagnostic() {
    String input = """
        {
          :type :SetNonEmpty()
          :body [ 1 ]
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.hasErrors(), "Empty :SetNonEmpty() must fail compilation");

    var diag = result.diagnostics().getFirst();
    Assertions.assertEquals(
        "STVN Syntax Error: empty composite argument list: collection requires schema type argument",
        diag.message()
    );
  }

  @Test
  @DisplayName("Empty :Set() declared inside :defs produces canonical collection diagnostic")
  void testEmptySetInDefsProducesCanonicalDiagnostic() {
    String input = """
        {
          :defs {
            :Set0 :Set()
          }
          :type :Set0
          :body []
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.hasErrors(), "Empty :Set() inside :defs must fail compilation");

    var diag = result.diagnostics().getFirst();
    Assertions.assertEquals(
        "STVN Syntax Error: empty composite argument list: collection requires schema type argument",
        diag.message(),
        "Must produce canonical collection diagnostic instead of mismatched input ':Set'"
    );
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{ :type :Seq() :body [] }",
      "{ :type :SeqNonEmpty() :body [ 1 ] }",
      "{ :type :Set() :body [] }",
      "{ :type :SetNonEmpty() :body [ 1 ] }",
      "{ :type :Option() :body #None }"
  })
  @DisplayName("All single-argument collections emit uniform diagnostic on empty arguments")
  void testSingleArgumentCollectionsDiagnosticUniformity(String input) {
    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.hasErrors());

    var diag = result.diagnostics().getFirst();
    Assertions.assertEquals(
        "STVN Syntax Error: empty composite argument list: collection requires schema type argument",
        diag.message()
    );
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{ :type :Map() :body {} }",
      "{ :type :MapNonEmpty() :body { [ 1 2 ] } }",
      "{ :type :MapInv() :body {} }",
      "{ :type :MapInvNonEmpty() :body { [ 1 2 ] } }",
      "{ :type :Either() :body #Left 1 }"
  })
  @DisplayName("All two-argument collections emit uniform diagnostic on empty arguments")
  void testTwoArgumentCollectionsDiagnosticUniformity(String input) {
    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.hasErrors());

    var diag = result.diagnostics().getFirst();
    Assertions.assertEquals(
        "STVN Syntax Error: insufficient composite arguments: expected 2 schema type arguments",
        diag.message()
    );
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{ :type :Tuple() :body ( 1 ) }",
      "{ :type :Union() :body #1 1 }",
      "{ :type :Enum[] :body #A }",
      "{ :type :Tuple(:Int32 :) :body ( 1 ) }",
      "{ :type :Seq() :body [ 1 ] }",
      "{ :type :Set() :body [] }",
      "{ :type :SetNonEmpty() :body [ 1 ] }",
      "{ :type :Map() :body {} }",
      "{ :defs { :Set0 :Set() } :type :Set0 :body [] }"
  })
  @DisplayName("Soundness assertion: diagnostics never dump raw 30+ token vocabulary sets")
  void testNoRawVocabularyDumpsAcrossNegativeInputs(String input) {
    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.hasErrors());

    for (var diag : result.diagnostics()) {
      String msg = diag.message();
      // Ensure no raw ANTLR internal rule or token names are leaked
      Assertions.assertFalse(msg.contains("ATOM_"), "Diagnostic must not leak token ATOM_*: " + msg);
      Assertions.assertFalse(msg.contains("KW_"), "Diagnostic must not leak token KW_*: " + msg);
      Assertions.assertFalse(msg.contains("COLL_"), "Diagnostic must not leak token COLL_*: " + msg);
      Assertions.assertFalse(msg.contains("TYPE_KEYWORD_BASE"), "Diagnostic must not leak TYPE_KEYWORD_BASE: " + msg);
      Assertions.assertFalse(msg.contains("VALUE_KEYWORD_BASE"), "Diagnostic must not leak VALUE_KEYWORD_BASE: " + msg);

      // Ensure no massive multi-keyword lists (> 5 literal elements) exist in message
      if (msg.contains("expecting {")) {
        int start = msg.indexOf("expecting {");
        int end = msg.indexOf("}", start);
        if (end > start) {
          String setContent = msg.substring(start + "expecting {".length(), end);
          String[] items = setContent.split(",");
          Assertions.assertTrue(items.length <= 4, "Expected token set must be sanitized/truncated to <= 4 items: " + msg);
        }
      }
    }
  }

  @Test
  @DisplayName("TC-EITHER-AMBIG-01: Untagged integer matching both Left and Right branches of :Either fails compilation")
  void testEitherAmbiguityUntaggedIntegerOverlap() {
    String input = """
        {
          :defs {
            :EitherRepeat :Either( :Int32 :Uint32 )
          }
          :type :EitherRepeat
          :body 1
        }
        """;

    var result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.hasErrors(), "Untagged value matching both branches must fail compilation");
    var diag = result.diagnostics().getFirst();
    Assertions.assertTrue(
        diag.message().contains("Ambiguous implicit resolution: Value matches both Left and Right branches of :Either"),
        "Expected canonical ambiguity diagnostic, got: " + diag.message()
    );
  }

  @Test
  @DisplayName("TC-EITHER-EXPL-01 & 02: Explicit #Left and #Right on overlapping branches compile cleanly")
  void testEitherExplicitLeftAndRightResolution() {
    String leftInput = """
        {
          :defs {
            :EitherRepeat :Either( :Int32 :Uint32 )
          }
          :type :EitherRepeat
          :body #Left 1
        }
        """;
    var leftValOpt = StvnCompiler.compile(leftInput);
    Assertions.assertTrue(leftValOpt.isPresent());
    Assertions.assertInstanceOf(StvnEither.class, leftValOpt.get());
    var leftEither = (StvnEither) leftValOpt.get();
    Assertions.assertFalse(leftEither.isRight(), "Expected Left branch for #Left 1");
    Assertions.assertInstanceOf(StvnInteger.class, leftEither.value());
    Assertions.assertEquals(1L, ((StvnInteger) leftEither.value()).value().longValue());

    String rightInput = """
        {
          :defs {
            :EitherRepeat :Either( :Int32 :Uint32 )
          }
          :type :EitherRepeat
          :body #Right 1
        }
        """;
    var rightValOpt = StvnCompiler.compile(rightInput);
    Assertions.assertTrue(rightValOpt.isPresent());
    Assertions.assertInstanceOf(StvnEither.class, rightValOpt.get());
    var rightEither = (StvnEither) rightValOpt.get();
    Assertions.assertTrue(rightEither.isRight(), "Expected Right branch for #Right 1");
    Assertions.assertInstanceOf(StvnInteger.class, rightEither.value());
    Assertions.assertEquals(1L, ((StvnInteger) rightEither.value()).value().longValue());
  }

  @Test
  @DisplayName("TC-EITHER-RULE-E-01: Untagged literal matching Left branch fails compilation with Rule E violation")
  void testEitherRuleEViolationUntaggedLeftBranch() {
    String input = """
        {
          :type :Either( :Int32 :String )
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    Assertions.assertTrue(
        diag.message().contains("Rule E Violation: Untagged value matching Left branch of :Either is non-inferable; explicit #Left tag is required"),
        "Expected Rule E diagnostic message, got: " + diag.message()
    );
  }

  @Test
  @DisplayName("TC-EITHER-RULE-F-01: Section 8.1 Rule F normative negative example fails compilation")
  void testEitherRuleFNormativeNegativeExample() {
    String input = """
        {
          :type :Seq( :Option( :Either( :String :Float ) ) )
          :body [ "test" ]
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    Assertions.assertTrue(
        diag.message().contains("Rule E Violation") || diag.message().contains("non-inferable"),
        "Expected Rule E non-inferable diagnostic for [ \"test\" ], got: " + diag.message()
    );
  }

  @Test
  @DisplayName("TC-EITHER-RULE-F-02: Section 8.1 Rule F positive examples compile cleanly with explicit #Left and inferred #Right")
  void testEitherRuleFNormativePositiveExamples() {
    // 1. Explicit #Left in sequence
    String inputLeft = """
        {
          :type :Seq( :Option( :Either( :String :Float ) ) )
          :body [ #Left "test" ]
        }
        """;
    var resLeft = StvnCompiler.compile(inputLeft);
    Assertions.assertTrue(resLeft.isPresent());
    var seqLeft = (StvnSeq) resLeft.get();
    var optLeft = (StvnOption) seqLeft.elements().getFirst();
    Assertions.assertTrue(optLeft.value().isPresent());
    var eitherLeft = (StvnEither) optLeft.value().get();
    Assertions.assertFalse(eitherLeft.isRight(), "Expected #Left for explicitly tagged variant");
    Assertions.assertEquals("test", ((StvnString) eitherLeft.value()).value());

    // 2. Inferred #Right and #Some in sequence
    String inputRight = """
        {
          :type :Seq( :Option( :Either( :String :Float ) ) )
          :body [ 42.5 ]
        }
        """;
    var resRight = StvnCompiler.compile(inputRight);
    Assertions.assertTrue(resRight.isPresent());
    var seqRight = (StvnSeq) resRight.get();
    var optRight = (StvnOption) seqRight.elements().getFirst();
    Assertions.assertTrue(optRight.value().isPresent());
    var eitherRight = (StvnEither) optRight.value().get();
    Assertions.assertTrue(eitherRight.isRight(), "Expected implied #Right via Rule B");
    Assertions.assertEquals(42.5, ((StvnFloat) eitherRight.value()).value().doubleValue(), 0.001);
  }

  @Test
  @DisplayName("TC-EITHER-RULE-E-TUPLE: Tuple containing untagged Left branch fails compilation")
  void testEitherRuleEViolationInTuple() {
    String input = """
        {
          :type :Tuple( :Either( :Int32 :String ) :Either( :Int32 :String ) )
          :body ( 1 "a" )
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    Assertions.assertTrue(
        diag.message().contains("Rule E Violation"),
        "Expected Rule E rejection for element 0 (untagged 1 matching Left), got: " + diag.message()
    );
  }

  @Test
  @DisplayName("TC-EITHER-RULE-E-TUPLE-VALID: Tuple with explicit #Left and implied #Right succeeds")
  void testEitherExplicitLeftAndImpliedRightInTuple() {
    String input = """
        {
          :type :Tuple( :Either( :Int32 :String ) :Either( :Int32 :String ) )
          :body ( #Left 1 "a" )
        }
        """;
    var res = StvnCompiler.compile(input);
    Assertions.assertTrue(res.isPresent());
    var tuple = (StvnTuple) res.get();
    var el0 = (StvnEither) tuple.elements().get(0);
    var el1 = (StvnEither) tuple.elements().get(1);
    Assertions.assertFalse(el0.isRight());
    Assertions.assertEquals(1L, ((StvnInteger) el0.value()).value().longValue());
    Assertions.assertTrue(el1.isRight());
    Assertions.assertEquals("a", ((StvnString) el1.value()).value());
  }

  @Test
  @DisplayName("TC-EITHER-AMBIG-FLOAT: Overlapping float branches fail implicit resolution")
  void testEitherAmbiguityFloatOverlap() {
    String input = """
        {
          :type :Either( :Float32 :Float64 )
          :body 1.5
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    Assertions.assertTrue(
        diag.message().contains("Ambiguous implicit resolution: Value matches both Left and Right branches of :Either"),
        "Expected ambiguity message for float overlap, got: " + diag.message()
    );
  }

  @Test
  @DisplayName("TC-EITHER-AMBIG-NOMINAL: Overlapping nominal aliases fail implicit resolution")
  void testEitherAmbiguityNominalAliases() {
    String input = """
        {
          :defs {
            :UserId :Int32
            :AccountId :Uint32
          }
          :type :Either( :UserId :AccountId )
          :body 100
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    Assertions.assertTrue(
        diag.message().contains("Ambiguous implicit resolution: Value matches both Left and Right branches of :Either"),
        "Expected canonical ambiguity diagnostic for nominal alias overlap, got: " + diag.message()
    );
  }

  @Test
  @DisplayName("TC-EITHER-NESTED: Nested Either inside Option bubbles ambiguity error")
  void testEitherAmbiguityNestedInOption() {
    String input = """
        {
          :type :Option( :Either( :Int32 :Uint32 ) )
          :body 1
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    Assertions.assertTrue(
        diag.message().contains("Ambiguous implicit resolution: Value matches both Left and Right branches of :Either"),
        "Expected ambiguity message for nested Either in Option, got: " + diag.message()
    );
  }

  @Test
  @DisplayName("TC-EITHER-AMBIG-CONST: Untagged constant matching overlapping Either branches fails compilation")
  void testEitherAmbiguityUntaggedConstant() {
    String input = """
        {
          :defs {
            #CONST_VAL :Int32 100
          }
          :type :Either( :Int32 :Uint32 )
          :body #CONST_VAL
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    Assertions.assertTrue(
        diag.message().contains("Ambiguous implicit resolution: Value matches both Left and Right branches of :Either"),
        "Expected ambiguity message for constant matching overlapping Either, got: " + diag.message()
    );
  }
}
