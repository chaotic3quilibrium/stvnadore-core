package org.stvnadore.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.stvnadore.core.ir.StvnValue.EitherBranch;
import org.stvnadore.core.ir.StvnValue.StvnEither;
import org.stvnadore.core.ir.StvnValue.StvnFloat;
import org.stvnadore.core.ir.StvnValue.StvnInt;
import org.stvnadore.core.ir.StvnValue.StvnOption;
import org.stvnadore.core.ir.StvnValue.StvnSeq;
import org.stvnadore.core.ir.StvnValue.StvnString;
import org.stvnadore.core.ir.StvnValue.StvnTuple;
import org.stvnadore.core.validation.DiagnosticBag;
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

    var result = StvnCompiler.compileToResult(input);
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

    var ex = Assertions.assertThrows(RuntimeException.class, () -> StvnCompiler.compilePayload(input));
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

    var result = StvnCompiler.compileToResult(input);
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

    var result = StvnCompiler.compileToResult(input);
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

    var ex = Assertions.assertThrows(RuntimeException.class, () -> StvnCompiler.compilePayload(input));
    Assertions.assertTrue(ex.getMessage().contains("STVN Syntax Error"));
    Assertions.assertTrue(
        ex.getMessage().contains("empty enum variant list") || ex.getMessage().contains("<value keyword>")
    );
  }

  @Test
  @DisplayName("Nested empty enum in :Union(:Int :Enum[]) fails with clean syntax error")
  void testNestedEmptyEnumInUnionFails() {
    String input = """
        {
          :type :Union(:Int :Enum[])
          :body #1 42
        }
        """;

    var result = StvnCompiler.compileToResult(input);
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

    var result = StvnCompiler.compileToResult(input);
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

    var res = StvnCompiler.compilePayload(input);
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

    var res = StvnCompiler.compilePayload(input);
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

    var result = StvnCompiler.compileToResult(input);
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
  @DisplayName("Undeclared compound collection :SetNonEmpty fails closed with ERR_UNDEFINED_TYPE")
  void testEmptySetNonEmptyProducesCanonicalDiagnostic() {
    String input = """
        {
          :type :SetNonEmpty
          :body [ 1 ]
        }
        """;

    var result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.hasErrors(), "Undeclared :SetNonEmpty must fail compilation");

    var diag = result.diagnostics().getFirst();
    Assertions.assertEquals(DiagnosticBag.ERR_UNDEFINED_TYPE, diag.errorCode().orElse(null));
    Assertions.assertEquals("Undefined type: :SetNonEmpty", diag.message());
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

    var result = StvnCompiler.compileToResult(input);
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
      "{ :type :Set() :body [] }",
      "{ :type :Option() :body #None }"
  })
  @DisplayName("All single-argument collections emit uniform diagnostic on empty arguments")
  void testSingleArgumentCollectionsDiagnosticUniformity(String input) {
    var result = StvnCompiler.compileToResult(input);
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
      "{ :type :Either() :body #Left 1 }"
  })
  @DisplayName("All two-argument collections emit uniform diagnostic on empty arguments")
  void testTwoArgumentCollectionsDiagnosticUniformity(String input) {
    var result = StvnCompiler.compileToResult(input);
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
      "{ :type :Tuple(:Int :) :body ( 1 ) }",
      "{ :type :Seq() :body [ 1 ] }",
      "{ :type :Set() :body [] }",
      "{ :type :SetNonEmpty() :body [ 1 ] }",
      "{ :type :Map() :body {} }",
      "{ :defs { :Set0 :Set() } :type :Set0 :body [] }"
  })
  @DisplayName("Soundness assertion: diagnostics never dump raw 30+ token vocabulary sets")
  void testNoRawVocabularyDumpsAcrossNegativeInputs(String input) {
    var result = StvnCompiler.compileToResult(input);
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
            :I32 :Int
            :U32 { #unsigned } :Int
            :EitherRepeat :Either( :I32 :U32 )
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
            :I32 :Int
            :U32 { #unsigned } :Int
            :EitherRepeat :Either( :I32 :U32 )
          }
          :type :EitherRepeat
          :body #Left 1
        }
        """;
    var leftValOpt = StvnCompiler.compilePayload(leftInput);
    Assertions.assertTrue(leftValOpt.isPresent());
    Assertions.assertInstanceOf(StvnEither.class, leftValOpt.get());
    var leftEither = (StvnEither) leftValOpt.get();
    Assertions.assertEquals(EitherBranch.LEFT, leftEither.branch(), "Expected Left branch for #Left 1");
    Assertions.assertInstanceOf(StvnInt.class, leftEither.value());
    Assertions.assertEquals(1L, ((StvnInt) leftEither.value()).value().longValue());

    String rightInput = """
        {
          :defs {
            :I32 :Int
            :U32 { #unsigned } :Int
            :EitherRepeat :Either( :I32 :U32 )
          }
          :type :EitherRepeat
          :body #Right 1
        }
        """;
    var rightValOpt = StvnCompiler.compilePayload(rightInput);
    Assertions.assertTrue(rightValOpt.isPresent());
    Assertions.assertInstanceOf(StvnEither.class, rightValOpt.get());
    var rightEither = (StvnEither) rightValOpt.get();
    Assertions.assertEquals(EitherBranch.RIGHT, rightEither.branch(), "Expected Right branch for #Right 1");
    Assertions.assertInstanceOf(StvnInt.class, rightEither.value());
    Assertions.assertEquals(1L, ((StvnInt) rightEither.value()).value().longValue());
  }

  @Test
  @DisplayName("TC-EITHER-RULE-E-01: Untagged literal matching Left branch fails compilation with Rule E violation")
  void testEitherRuleEViolationUntaggedLeftBranch() {
    String input = """
        {
          :type :Either( :Int :String )
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
    var resLeft = StvnCompiler.compilePayload(inputLeft);
    Assertions.assertTrue(resLeft.isPresent());
    var seqLeft = (StvnSeq) resLeft.get();
    var optLeft = (StvnOption) seqLeft.elements().getFirst();
    Assertions.assertTrue(optLeft.value().isPresent());
    var eitherLeft = (StvnEither) optLeft.value().get();
    Assertions.assertEquals(EitherBranch.LEFT, eitherLeft.branch(), "Expected #Left for explicitly tagged variant");
    Assertions.assertEquals("test", ((StvnString) eitherLeft.value()).value());

    // 2. Inferred #Right and #Some in sequence
    String inputRight = """
        {
          :type :Seq( :Option( :Either( :String :Float ) ) )
          :body [ 42.5 ]
        }
        """;
    var resRight = StvnCompiler.compilePayload(inputRight);
    Assertions.assertTrue(resRight.isPresent());
    var seqRight = (StvnSeq) resRight.get();
    var optRight = (StvnOption) seqRight.elements().getFirst();
    Assertions.assertTrue(optRight.value().isPresent());
    var eitherRight = (StvnEither) optRight.value().get();
    Assertions.assertEquals(EitherBranch.RIGHT, eitherRight.branch(), "Expected implied #Right via Rule B");
    Assertions.assertEquals(42.5, ((StvnFloat) eitherRight.value()).decimalValue().doubleValue(), 0.001);
  }

  @Test
  @DisplayName("TC-EITHER-RULE-E-TUPLE: Tuple containing untagged Left branch fails compilation")
  void testEitherRuleEViolationInTuple() {
    String input = """
        {
          :type :Tuple( :Either( :Int :String ) :Either( :Int :String ) )
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
          :type :Tuple( :Either( :Int :String ) :Either( :Int :String ) )
          :body ( #Left 1 "a" )
        }
        """;
    var res = StvnCompiler.compilePayload(input);
    Assertions.assertTrue(res.isPresent());
    var tuple = (StvnTuple) res.get();
    var el0 = (StvnEither) tuple.elements().get(0);
    var el1 = (StvnEither) tuple.elements().get(1);
    Assertions.assertEquals(EitherBranch.LEFT, el0.branch());
    Assertions.assertEquals(1L, ((StvnInt) el0.value()).value().longValue());
    Assertions.assertEquals(EitherBranch.RIGHT, el1.branch());
    Assertions.assertEquals("a", ((StvnString) el1.value()).value());
  }

  @Test
  @DisplayName("TC-EITHER-AMBIG-FLOAT: Overlapping float branches fail implicit resolution")
  void testEitherAmbiguityFloatOverlap() {
    String input = """
        {
          :defs {
            :F32 { #size 32 } :Float
            :F64 :Float
          }
          :type :Either( :F32 :F64 )
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
            :UserId :Int
            :AccountId { #unsigned } :Int
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
          :defs {
            :I32 :Int
            :U32 { #unsigned } :Int
          }
          :type :Option( :Either( :I32 :U32 ) )
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
    String anonymousInput = """
        {
          :defs {
            #CONST_VAL :Int 100
          }
          :type :Either( :Int { #unsigned } :Int )
          :body #CONST_VAL
        }
        """;
    var anonResult = StvnCompiler.compileToResult(anonymousInput);
    Assertions.assertTrue(anonResult.hasErrors());
    var anonDiag = anonResult.diagnostics().getFirst();
    Assertions.assertEquals(java.util.Optional.of("E024"), anonDiag.canonicalCode());
    Assertions.assertTrue(
        anonDiag.message().contains("Ambiguous implicit resolution: Value matches both Left and Right branches of :Either"),
        "Expected ambiguity message for anonymous constant matching overlapping Either, got: " + anonDiag.message()
    );

    String brandedInput = """
        {
          :defs {
            :I32 :Int
            :U32 { #unsigned } :Int
            #CONST_VAL :I32 100
          }
          :type :Either( :I32 :U32 )
          :body #CONST_VAL
        }
        """;
    var brandedResult = StvnCompiler.compileToResult(brandedInput);
    Assertions.assertTrue(brandedResult.hasErrors());
    var brandedDiag = brandedResult.diagnostics().getFirst();
    Assertions.assertEquals(java.util.Optional.of("E023"), brandedDiag.canonicalCode());
    Assertions.assertEquals(java.util.Optional.of(DiagnosticBag.ERR_EITHER_LEFT_MISSING_TAG), brandedDiag.symbolicId());
  }

  @Test
  @DisplayName("Rule STR-04: Empty tag brackets rejected with specific diagnostic and zero cascading errors")
  void testEmptyTagRejectedWithRuleStr04Diagnostic() {
    String input = """
        {
          :type :String
          :body \"\"\"[]
          SELECT * FROM users WHERE active = TRUE;
          []\"\"\"
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.hasErrors());
    Assertions.assertEquals(1, result.diagnostics().size(), "Must isolate body and produce exactly one diagnostic");
    var msg = result.diagnostics().getFirst().message();
    Assertions.assertTrue(msg.contains("Rule STR-04 violation: Fenced string delimiter tag must not be empty"), msg);
  }

  @Test
  @DisplayName("Rule STR-04: Whitespace in tag rejected with specific diagnostic")
  void testWhitespaceTagRejectedWithRuleStr04Diagnostic() {
    String input = """
        {
          :type :String
          :body ""\"[ ]
          content
          [ ]""\"
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.hasErrors());
    Assertions.assertEquals(1, result.diagnostics().size());
    var msg = result.diagnostics().getFirst().message();
    Assertions.assertTrue(msg.contains("Rule STR-04 violation: Fenced string delimiter tag must not contain whitespace"), msg);
  }

  @Test
  @DisplayName("Rule STR-04: Illegal symbols in tag rejected with specific diagnostic")
  void testIllegalSymbolsTagRejectedWithRuleStr04Diagnostic() {
    String input = """
        {
          :type :String
          :body ""\"[C++]
          int main() { return 0; }
          [C++]""\"
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.hasErrors());
    Assertions.assertEquals(1, result.diagnostics().size());
    var msg = result.diagnostics().getFirst().message();
    Assertions.assertTrue(msg.contains("Rule STR-04 violation: Fenced string delimiter tag 'C++' contains invalid characters"), msg);
  }

  @Test
  @DisplayName("Rule STR-04: Tag length exceeding 256 characters rejected with specific diagnostic")
  void testTagLengthExceeding256RejectedWithRuleStr04Diagnostic() {
    String tag257 = "A".repeat(257);
    String input = "{\n  :type :String\n  :body \"\"\"[" + tag257 + "]\n  content\n  [" + tag257 + "]\"\"\"\n}\n";
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.hasErrors());
    Assertions.assertEquals(1, result.diagnostics().size());
    var msg = result.diagnostics().getFirst().message();
    Assertions.assertTrue(msg.contains("Rule STR-04 violation: Fenced string delimiter tag length exceeds maximum of 256 characters"), msg);
  }

  @Test
  @DisplayName("Rule STR-04: Orphan closing delimiter in default mode rejected without triggering block string")
  void testOrphanClosingDelimiterRejectedWithRuleStr04Diagnostic() {
    String input = """
        {
          :type :String
          :body [SQL]""\"
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.hasErrors());
    var msg = result.diagnostics().getFirst().message();
    Assertions.assertTrue(msg.contains("Rule STR-04 violation: Orphan or unexpected closing fence delimiter"), msg);
  }

  @Test
  @DisplayName("Rule STR-04 Prohibition: Encountering '->' emits ERROR diagnostic and fails compilation")
  void testFencedStringArrowDelimiterEmitsProhibitionError() {
    String input = """
        {
          :type :String
          :body \"\"\"->[SQL]
          SELECT 1;
          \"\"\"[SQL]
        }
        """;

    var result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess(), "Compilation must fail when prohibited arrow delimiter is used");
    Assertions.assertTrue(result.hasErrors(), "Must contain ERROR diagnostics");
    Assertions.assertEquals(1, result.diagnostics().size());

    var diag = result.diagnostics().getFirst();
    Assertions.assertEquals(StvnDiagnostic.DiagnosticSeverity.ERROR, diag.severity());
    Assertions.assertEquals(java.util.Optional.of(DiagnosticBag.ERR_PROHIBITED_FENCE_ARROW), diag.errorCode());
    Assertions.assertTrue(diag.message().contains("Rule STR-04 violation: Fenced string delimiter arrow '->' is prohibited"),
        "Expected prohibition error message: " + diag);
  }

  @Test
  @DisplayName("Rule STR-04 Canonical: Fenced string without arrow emits zero diagnostics")
  void testFencedStringCanonicalWithoutArrowEmitsNoDiagnostics() {
    String input = """
        {
          :type :String
          :body ""\"[SQL]
          SELECT 1;
          [SQL]""\"
        }
        """;

    var result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.isSuccess());
    Assertions.assertFalse(result.hasWarnings());
    Assertions.assertEquals(0, result.diagnostics().size());
  }

  @Test
  @DisplayName("Tolerant parsing: Empty metadata block on type emits ERR_EMPTY_METADATA_BLOCK without syntax error")
  void testEmptyMetadataBlockOnTypeEmitsSemanticError() {
    String input = """
        {
          :defs {
            :Port {} :Int
          }
          :type :Port
          :body 8080
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertTrue(result.hasErrors());
    Assertions.assertEquals(1, result.diagnostics().size());
    var diag = result.diagnostics().getFirst();
    Assertions.assertEquals(DiagnosticBag.ERR_EMPTY_METADATA_BLOCK, diag.errorCode().orElse(""));
    Assertions.assertTrue(diag.message().contains("Empty metadata block is invalid; remove '{}' or specify valid facets"));
    Assertions.assertEquals(3, diag.line());
    int expectedStart = input.indexOf("{}");
    Assertions.assertEquals(expectedStart, diag.startOffset());
    Assertions.assertEquals(expectedStart + 2, diag.endOffset());
  }

  @Test
  @DisplayName("Tolerant parsing: Empty metadata block on constant emits ERR_EMPTY_METADATA_BLOCK")
  void testEmptyMetadataBlockOnConstantEmitsSemanticError() {
    String input = """
        {
          :defs {
            #DEFAULT_PORT {} :Int 8080
          }
          :type :Int
          :body #DEFAULT_PORT
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertEquals(1, result.diagnostics().size());
    var diag = result.diagnostics().getFirst();
    Assertions.assertEquals(DiagnosticBag.ERR_EMPTY_METADATA_BLOCK, diag.errorCode().orElse(""));
  }

  @Test
  @DisplayName("Tolerant parsing: Empty directive block in :use emits ERR_EMPTY_DIRECTIVE_BLOCK")
  void testEmptyDirectiveBlockInUseEmitsSemanticError() {
    String input = """
        {
          :defs {
            :package :Network { :Port :Int }
            :use [ :Network {} ]
          }
          :type :Network/Port
          :body 8080
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());
    var diag = result.diagnostics().getFirst();
    Assertions.assertEquals(DiagnosticBag.ERR_EMPTY_DIRECTIVE_BLOCK, diag.errorCode().orElse(""));
    Assertions.assertTrue(diag.message().contains("Empty directive block in :use or :include statement is invalid"));
  }

  @Test
  @DisplayName("Tolerant parsing: Empty directive block in :include emits ERR_EMPTY_DIRECTIVE_BLOCK")
  void testEmptyDirectiveBlockInIncludeEmitsSemanticError() {
    String input = """
        {
          :defs {
            :include [ "common.stvn_d" {} ]
          }
          :type :String
          :body "val"
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());
    var diag = result.diagnostics().getFirst();
    Assertions.assertEquals(DiagnosticBag.ERR_EMPTY_DIRECTIVE_BLOCK, diag.errorCode().orElse(""));
  }

  @Test
  @DisplayName("Coordinate span: Parser syntax error endOffset equals stopIndex + 1 (exclusive length)")
  void testSyntaxErrorCoordinateSpanAccuracy() {
    String input = """
        {
          :type :Boolean
          :body #False
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());
    var diag = result.diagnostics().getFirst();
    int start = input.indexOf("#False");
    int expectedEnd = start + "#False".length();
    Assertions.assertEquals(start, diag.startOffset());
    Assertions.assertEquals(expectedEnd, diag.endOffset(), "endOffset must cover the complete token");
    Assertions.assertEquals(3, diag.line());
    Assertions.assertEquals(8, diag.column());
  }

  @Test
  @DisplayName("TC-PIN-01: Incomplete :defs clause followed by :type pins blame strictly to :defs token")
  void testIncompleteDefsPinsToDefsKeyword() {
    String input = """
        {
          :defs
          :type :Int
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertEquals(1, result.diagnostics().size(), "Expected single diagnostic on :defs keyword with zero cascade errors");
    var diag = result.diagnostics().getFirst();
    int expectedStart = input.indexOf(":defs");
    int expectedEnd = expectedStart + ":defs".length();
    Assertions.assertEquals(expectedStart, diag.startOffset());
    Assertions.assertEquals(expectedEnd, diag.endOffset());
    Assertions.assertEquals(2, diag.line());
    Assertions.assertEquals(2, diag.column());
    Assertions.assertTrue(diag.message().contains("clause ':defs' requires definition block '{ ... }'"));
  }

  @Test
  @DisplayName("TC-PIN-02: Incomplete :type clause followed by :body pins blame strictly to :type token")
  void testIncompleteTypePinsToTypeKeyword() {
    String input = """
        {
          :defs {
            :Port :Int
          }
          :type
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertEquals(1, result.diagnostics().size(), "Expected single diagnostic on :type keyword with zero secondary errors");
    var diag = result.diagnostics().getFirst();
    int expectedStart = input.indexOf(":type");
    int expectedEnd = expectedStart + ":type".length();
    Assertions.assertEquals(expectedStart, diag.startOffset());
    Assertions.assertEquals(expectedEnd, diag.endOffset());
    Assertions.assertEquals(5, diag.line());
    Assertions.assertEquals(2, diag.column());
    Assertions.assertTrue(diag.message().contains("clause ':type' requires a schema type"));
  }

  @Test
  @DisplayName("TC-PIN-03: Incomplete :body clause followed by closing brace pins blame strictly to :body token")
  void testIncompleteBodyPinsToBodyKeyword() {
    String input = """
        {
          :type :Int
          :body
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertEquals(1, result.diagnostics().size(), "Expected single diagnostic on :body keyword with zero cascade on closing brace");
    var diag = result.diagnostics().getFirst();
    int expectedStart = input.indexOf(":body");
    int expectedEnd = expectedStart + ":body".length();
    Assertions.assertEquals(expectedStart, diag.startOffset());
    Assertions.assertEquals(expectedEnd, diag.endOffset());
    Assertions.assertEquals(3, diag.line());
    Assertions.assertEquals(2, diag.column());
    Assertions.assertTrue(diag.message().contains("clause ':body' requires a value payload"));
  }

  @Test
  @DisplayName("TC-PIN-04: Incomplete :defs clause followed by closing brace attributes blame to :defs without boundary cascade")
  void testIncompleteDefsAtClosingBrace() {
    String input = """
        {
          :defs
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertEquals(1, result.diagnostics().size(), "Expected single diagnostic on :defs with clean boundary recovery");
    var diag = result.diagnostics().getFirst();
    int expectedStart = input.indexOf(":defs");
    int expectedEnd = expectedStart + ":defs".length();
    Assertions.assertEquals(expectedStart, diag.startOffset());
    Assertions.assertEquals(expectedEnd, diag.endOffset());
    Assertions.assertEquals(2, diag.line());
    Assertions.assertEquals(2, diag.column());
    Assertions.assertTrue(diag.message().contains("clause ':defs' requires definition block '{ ... }'"));
  }

  @Test
  @DisplayName("TC-PIN-05: Empty :defs {} in definitions schema (.stvn_d) emits ERR_EMPTY_DEFS_BLOCK pinned to {} tokens")
  void testEmptyDefsInStvnDRejected() {
    String input = """
        {
          :meta {
            #name "test_empty_defs"
            #kind #DEFS
          }
          :defs {}
        }
        """;
    var result = StvnCompiler.compileToResult(input, "test_empty_defs.stvn_d");
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertEquals(1, result.diagnostics().size());
    var diag = result.diagnostics().getFirst();
    Assertions.assertEquals(DiagnosticBag.ERR_EMPTY_DEFS_BLOCK, diag.errorCode().orElse(""));
    int expectedStart = input.indexOf("{}");
    int expectedEnd = expectedStart + 2;
    Assertions.assertEquals(expectedStart, diag.startOffset());
    Assertions.assertEquals(expectedEnd, diag.endOffset());
    Assertions.assertTrue(diag.message().contains("requires at least one type definition inside ':defs { ... }'"));
  }

  @Test
  @DisplayName("TC-PIN-06: Missing :meta in definitions schema (.stvn_d) pins ERR_DOCUMENT_KIND_MISMATCH to root brace [0, 1)")
  void testMissingMetaInStvnDPinsToRootBrace() {
    String input = """
        {
          :defs {
            :Port :Int
          }
        }
        """;
    var result = StvnCompiler.compileToResult(input, "test_missing_meta.stvn_d");
    Assertions.assertFalse(result.isSuccess());
    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();
    Assertions.assertEquals(0, diag.startOffset());
    Assertions.assertEquals(1, diag.endOffset());
    Assertions.assertEquals(1, diag.line());
    Assertions.assertEquals(0, diag.column());
    Assertions.assertTrue(diag.message().contains("Mandatory :meta block missing"));
  }

  @Test
  @DisplayName("TC-PIN-07: Declaring prohibited :type on #kind #DEFS pins ERR_DOCUMENT_KIND_MISMATCH strictly to :type clause")
  void testProhibitedTypeSectionInDefinitionsSchemaPinsSpan() {
    String input = """
        {
          :meta {
            #name "defs_with_type"
            #kind #DEFS
          }
          :defs {
            :Port :Int
          }
          :type :Port
        }
        """;
    var result = StvnCompiler.compileToResult(input, "defs_with_type.stvn_d");
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertEquals(1, result.diagnostics().size(), "Expected single diagnostic on illegal :type section");
    var diag = result.diagnostics().getFirst();
    Assertions.assertEquals(DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH, diag.errorCode().orElse(null));
    int expectedStart = input.indexOf(":type :Port");
    int expectedEnd = expectedStart + ":type :Port".length();
    Assertions.assertEquals(expectedStart, diag.startOffset());
    Assertions.assertEquals(expectedEnd, diag.endOffset());
    Assertions.assertTrue(diag.message().contains("cannot declare ':type' section"));
  }

  @Test
  @DisplayName("TC-PIN-08: Declaring prohibited :body on #kind #DEFS pins ERR_DOCUMENT_KIND_MISMATCH strictly to :body clause")
  void testProhibitedBodySectionInDefinitionsSchemaPinsSpan() {
    String input = """
        {
          :meta {
            #name "defs_with_body"
            #kind #DEFS
          }
          :defs {
            :Port :Int
          }
          :body 8080
        }
        """;
    var result = StvnCompiler.compileToResult(input, "defs_with_body.stvn_d");
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertEquals(1, result.diagnostics().size(), "Expected single diagnostic on illegal :body section");
    var diag = result.diagnostics().getFirst();
    Assertions.assertEquals(DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH, diag.errorCode().orElse(null));
    int expectedStart = input.indexOf(":body 8080");
    int expectedEnd = expectedStart + ":body 8080".length();
    Assertions.assertEquals(expectedStart, diag.startOffset());
    Assertions.assertEquals(expectedEnd, diag.endOffset());
    Assertions.assertTrue(diag.message().contains("cannot declare ':body' section"));
  }

  @Test
  @DisplayName("TC-PIN-09: Declaring both :type and :body on #kind #DEFS emits exactly two independent E031 diagnostics")
  void testProhibitedTypeAndBodySectionsEmitTwoIndependentDiagnostics() {
    String input = """
        {
          :meta {
            #name "defs_with_type_and_body"
            #kind #DEFS
          }
          :defs {
            :Port :Int
          }
          :type :Port
          :body 8080
        }
        """;
    var result = StvnCompiler.compileToResult(input, "defs_with_type_and_body.stvn_d");
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertEquals(2, result.diagnostics().size(), "Must produce exactly 2 independent E031 diagnostics");
    var diags = result.diagnostics();
    Assertions.assertEquals(DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH, diags.get(0).errorCode().orElse(null));
    Assertions.assertEquals(input.indexOf(":type :Port"), diags.get(0).startOffset());
    Assertions.assertEquals(input.indexOf(":type :Port") + ":type :Port".length(), diags.get(0).endOffset());
    Assertions.assertTrue(diags.get(0).message().contains("cannot declare ':type' section"));

    Assertions.assertEquals(DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH, diags.get(1).errorCode().orElse(null));
    Assertions.assertEquals(input.indexOf(":body 8080"), diags.get(1).startOffset());
    Assertions.assertEquals(input.indexOf(":body 8080") + ":body 8080".length(), diags.get(1).endOffset());
    Assertions.assertTrue(diags.get(1).message().contains("cannot declare ':body' section"));
  }

  @Test
  @DisplayName("TC-PIN-10: Kind mismatch on definitions schema (#kind #BODY on .stvn_d) pins ERR_DOCUMENT_KIND_MISMATCH strictly to #kind facet")
  void testKindMismatchOnDefinitionsSchemaPinsKindFacet() {
    String input = """
        {
          :meta {
            #name "defs_mismatch"
            #kind #BODY
          }
          :defs {
            :Port :Int
          }
        }
        """;
    var result = StvnCompiler.compileToResult(input, "defs_mismatch.stvn_d");
    Assertions.assertFalse(result.isSuccess());
    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();
    int expectedStart = input.indexOf("#kind #BODY");
    int expectedEnd = expectedStart + "#kind #BODY".length();
    Assertions.assertEquals(expectedStart, diag.startOffset());
    Assertions.assertEquals(expectedEnd, diag.endOffset());
  }

  @Test
  @DisplayName("Vector 2 Acid Test: { :meta :defs :type :body } produces exactly 4 pinned errors and 0 boundary errors")
  void testAcidTestFourClauseErrorsPinned() {
    String input = """
        {
          :meta
          :defs
          :type
          :body
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertEquals(4, result.diagnostics().size(), "Must produce exactly 4 distinct clause errors");

    var diags = result.diagnostics();
    Assertions.assertEquals(input.indexOf(":meta"), diags.get(0).startOffset());
    Assertions.assertEquals(input.indexOf(":meta") + ":meta".length(), diags.get(0).endOffset());
    Assertions.assertTrue(diags.get(0).message().contains("clause ':meta' requires a metadata block '{ ... }'"));

    Assertions.assertEquals(input.indexOf(":defs"), diags.get(1).startOffset());
    Assertions.assertEquals(input.indexOf(":defs") + ":defs".length(), diags.get(1).endOffset());
    Assertions.assertTrue(diags.get(1).message().contains("clause ':defs' requires definition block '{ ... }'"));

    Assertions.assertEquals(input.indexOf(":type"), diags.get(2).startOffset());
    Assertions.assertEquals(input.indexOf(":type") + ":type".length(), diags.get(2).endOffset());
    Assertions.assertTrue(diags.get(2).message().contains("clause ':type' requires a schema type"));

    Assertions.assertEquals(input.indexOf(":body"), diags.get(3).startOffset());
    Assertions.assertEquals(input.indexOf(":body") + ":body".length(), diags.get(3).endOffset());
    Assertions.assertTrue(diags.get(3).message().contains("clause ':body' requires a value payload"));
  }

  @Test
  @DisplayName("Vector 1: Typo lookahead after clause pins blame strictly to preceding clause keyword")
  void testTypoLookaheadPinsToPrecedingClause() {
    String input = """
        {
          :meta
          :def
          :type
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());
    var firstDiag = result.diagnostics().getFirst();
    Assertions.assertEquals(input.indexOf(":meta"), firstDiag.startOffset(), "Blame must pin strictly to :meta");
    Assertions.assertEquals(input.indexOf(":meta") + ":meta".length(), firstDiag.endOffset());
    Assertions.assertTrue(firstDiag.message().contains("clause ':meta' requires a metadata block '{ ... }'"));
  }

  @Test
  @DisplayName("Vector 3: Empty blocks reported alongside downstream syntax errors")
  void testEmptyBlocksReportedAlongsideDownstreamSyntaxErrors() {
    String input = """
        {
          :meta {}
          :defs {}
          :type
          :body
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertEquals(4, result.diagnostics().size(), "Must report 2 empty block errors and 2 syntax errors simultaneously");
    var codes = result.diagnostics().stream().map(d -> d.errorCode().orElse("")).toList();
    Assertions.assertTrue(codes.contains(DiagnosticBag.ERR_EMPTY_META_BLOCK));
    Assertions.assertTrue(codes.contains(DiagnosticBag.ERR_EMPTY_DEFS_BLOCK));
    Assertions.assertTrue(codes.contains("STVN_SYNTAX_ERROR"));
  }

  @Test
  @DisplayName("Discrete bound underflow on :Int emits ERR_INTEGER_OVERFLOW pinned to #minIncl <val> with clean degraded sentinel")
  void testInt32DiscreteBoundUnderflowPinsCorrectly() {
    String input = """
        {
          :defs {
            :Int32 { #minIncl -2147483649 } :Int
          }
          :type :Int32
          :body 0
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertTrue(result.diagnostics().stream().anyMatch(d ->
        DiagnosticBag.ERR_INTEGER_OVERFLOW.equals(d.errorCode().orElse(""))));

    var overflowDiag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_INTEGER_OVERFLOW.equals(d.errorCode().orElse("")))
        .findFirst()
        .orElseThrow();

    String expectedFacet = "#minIncl -2147483649";
    int expectedStart = input.indexOf(expectedFacet);
    int expectedEnd = expectedStart + expectedFacet.length();

    Assertions.assertEquals(expectedStart, overflowDiag.startOffset(), "Diagnostic must pin strictly to #minIncl <val> start");
    Assertions.assertEquals(expectedEnd, overflowDiag.endOffset(), "Diagnostic must pin strictly to #minIncl <val> end");
    Assertions.assertTrue(overflowDiag.message().contains("out of bounds for physical capacity of :Int"));
  }

  @Test
  @DisplayName("Discrete bound overflow on :Int emits ERR_INTEGER_OVERFLOW pinned to #maxExcl <val>")
  void testInt32DiscreteBoundOverflowPinsCorrectly() {
    String input = """
        {
          :defs {
            :Int32 { #maxExcl 2147483649 } :Int
          }
          :type :Int32
          :body 0
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());

    var overflowDiag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_INTEGER_OVERFLOW.equals(d.errorCode().orElse("")))
        .findFirst()
        .orElseThrow();

    String expectedFacet = "#maxExcl 2147483649";
    int expectedStart = input.indexOf(expectedFacet);
    int expectedEnd = expectedStart + expectedFacet.length();

    Assertions.assertEquals(expectedStart, overflowDiag.startOffset());
    Assertions.assertEquals(expectedEnd, overflowDiag.endOffset());
  }

  @Test
  @DisplayName("Discrete bound overflow on 64-bit unsigned integer emits ERR_INTEGER_OVERFLOW")
  void testUInt64DiscreteBoundOverflowPinsCorrectly() {
    String input = """
        {
          :defs {
            :UInt64 { #unsigned #size 64 #maxExcl 18446744073709551617 } :Int
          }
          :type :UInt64
          :body 0
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());

    var overflowDiag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_INTEGER_OVERFLOW.equals(d.errorCode().orElse("")))
        .findFirst()
        .orElseThrow();

    String expectedFacet = "#maxExcl 18446744073709551617";
    int expectedStart = input.indexOf(expectedFacet);
    int expectedEnd = expectedStart + expectedFacet.length();

    Assertions.assertEquals(expectedStart, overflowDiag.startOffset());
    Assertions.assertEquals(expectedEnd, overflowDiag.endOffset());
  }

  @Test
  @DisplayName("Discrete bound underflow on 7-bit signed integer emits ERR_INTEGER_OVERFLOW")
  void testInt7DiscreteBoundUnderflowPinsCorrectly() {
    String input = """
        {
          :defs {
            :Int7 { #size 7 #minIncl -65 } :Int
          }
          :type :Int7
          :body 0
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());

    var overflowDiag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_INTEGER_OVERFLOW.equals(d.errorCode().orElse("")))
        .findFirst()
        .orElseThrow();

    String expectedFacet = "#minIncl -65";
    int expectedStart = input.indexOf(expectedFacet);
    int expectedEnd = expectedStart + expectedFacet.length();

    Assertions.assertEquals(expectedStart, overflowDiag.startOffset());
    Assertions.assertEquals(expectedEnd, overflowDiag.endOffset());
  }

  @Test
  @DisplayName("Discrete bound overflow on 49-bit unsigned integer emits ERR_INTEGER_OVERFLOW")
  void testUInt49DiscreteBoundOverflowPinsCorrectly() {
    String input = """
        {
          :defs {
            :UInt49 { #unsigned #size 49 #maxExcl 562949953421313 } :Int
          }
          :type :UInt49
          :body 0
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());

    var overflowDiag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_INTEGER_OVERFLOW.equals(d.errorCode().orElse("")))
        .findFirst()
        .orElseThrow();

    String expectedFacet = "#maxExcl 562949953421313";
    int expectedStart = input.indexOf(expectedFacet);
    int expectedEnd = expectedStart + expectedFacet.length();

    Assertions.assertEquals(expectedStart, overflowDiag.startOffset());
    Assertions.assertEquals(expectedEnd, overflowDiag.endOffset());
  }

  @Test
  @DisplayName("Discrete bound overflow on 128-bit nanosecond epoch timestamp emits ERR_INTEGER_OVERFLOW")
  void testTimeEpochNs128BitOverflowPinsCorrectly() {
    String input = """
        {
          :defs {
            :NanoEpoch { #ns #maxExcl 170141183460469231731687303715884105728 } :TimeEpoch
          }
          :type :NanoEpoch
          :body 0
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());

    var overflowDiag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_INTEGER_OVERFLOW.equals(d.errorCode().orElse("")))
        .findFirst()
        .orElseThrow();

    String expectedFacet = "#maxExcl 170141183460469231731687303715884105728";
    int expectedStart = input.indexOf(expectedFacet);
    int expectedEnd = expectedStart + expectedFacet.length();

    Assertions.assertEquals(expectedStart, overflowDiag.startOffset());
    Assertions.assertEquals(expectedEnd, overflowDiag.endOffset());
  }

  @Test
  @DisplayName("String cardinality bounds inversion emits ERR_INVERTED_RANGE")
  void testStringSizeInvertedBoundsPinsCorrectly() {
    String input = """
        {
          :defs {
            :InvertedString { #minSize 100 #maxSize 50 } :String
          }
          :type :InvertedString
          :body ""
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertTrue(result.diagnostics().stream().anyMatch(d ->
        DiagnosticBag.ERR_INVERTED_RANGE.equals(d.errorCode().orElse(""))));
  }

  @Test
  @DisplayName("Degraded schema clean fallback ensures invalid bounds do not leak into AST visitors")
  void testDegradedSchemaCleanFallback() {
    String input = """
        {
          :defs {
            :Int32 { #minIncl -2147483649 } :Int
          }
          :type :Int32
          :body 0
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertTrue(result.diagnostics().stream().anyMatch(d ->
        DiagnosticBag.ERR_INTEGER_OVERFLOW.equals(d.errorCode().orElse(""))));
    Assertions.assertEquals(1, result.diagnostics().size(), "Only the boundary overflow error must be reported");
  }

  @Test
  @DisplayName("Large cardinality size cap exceeds 32-bit integer capacity without overflow")
  void testLargeCardinalitySizeParsing() {
    String input = """
        {
          :defs {
            :LargeBlob { #maxSize 10995116277760 } :String
          }
          :type :LargeBlob
          :body "test"
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.isSuccess(), "Large cardinality size must parse successfully without overflow: " + result.diagnostics());
  }

  @Test
  @DisplayName("Stage 2D User Presentation Gate: Zero raw ANTLR recognition jargon across syntax and grammar error vectors")
  void testUserPresentationGateZeroRawAntlrJargonAcrossSyntaxErrorVectors() {
    java.util.List<String> errorVectors = java.util.List.of(
        "{ @ :type :Int :body 1 }",
        "{ :type :Int :body $42 }",
        "{ :type :Tuple() :body () }",
        "{ :type :Map(:Int) :body {} }",
        "{ :type :Union(:Int) :body 1 }",
        "{ :type :Either(:Int) :body 1 }",
        "{ :type :Seq() :body [] }",
        "{ :type { #unsigned #TRUE } :Int :body 1 }",
        "{ :type { #exact #TRUE } :Float :body 1.0 }",
        "{ :defs { :M { #invertible #TRUE } :Map(:Int :Int) } :type :M :body {} }",
        "{ :type { #ns 1 } :TimeEpoch :body 0 }",
        "{ :type { #offset \"Z\" } :DateTime :body \"2026-01-01T00:00:00Z\" }",
        "{ :meta {} :type :Int :body 1 }",
        "{ :type :Int :meta { #domain \"app\" } :body 1 }",
        "{ :type :MapEntry :body 1 }",
        "{ :unknown 1 :type :Int :body 1 }",
        "{ :type :Int :body [1 2 }",
        "{ :type :Int } extra"
    );

    java.util.List<String> forbiddenPhrases = java.util.List.of(
        "token recognition error",
        "no viable alternative",
        "mismatched input",
        "extraneous input"
    );

    for (String vector : errorVectors) {
      var result = StvnCompiler.compileToResult(vector);
      Assertions.assertTrue(result.hasErrors(), "Expected syntax/semantic error for vector: " + vector);
      for (var diag : result.diagnostics()) {
        String lower = diag.message().toLowerCase(java.util.Locale.ROOT);
        for (String forbidden : forbiddenPhrases) {
          Assertions.assertFalse(
              lower.contains(forbidden),
              "Forbidden raw ANTLR phrase '" + forbidden + "' leaked in diagnostic: " + diag.message()
                  + " for input: " + vector
          );
        }
        Assertions.assertFalse(
            lower.matches(".*\\bmissing\\s+.+\\s+at\\s+.*"),
            "Forbidden raw ANTLR 'missing ... at' phrase leaked in diagnostic: " + diag.message()
                + " for input: " + vector
        );
        Assertions.assertTrue(
            diag.canonicalCode().isPresent(),
            "Every diagnostic must carry a canonical code (E001-E040 / W001-W003): " + diag
        );
        Assertions.assertTrue(
            diag.symbolicId().isPresent(),
            "Every diagnostic must carry a symbolic ID: " + diag
        );
      }
    }
  }

  @Test
  @DisplayName("Stage 4/5 Target 2 & Target 4: Physical integer range formatting (exact <= 128 bits, symbolic > 128 bits) and W003 canonical code resolution")
  void testPhysicalIntRangeFormattingAndW003Resolution() {
    Assertions.assertEquals(
        "[-128, 127]",
        org.stvnadore.core.lowering.StvnDefsLowerer.formatPhysicalIntRange(java.math.BigInteger.valueOf(8), false, false)
    );
    Assertions.assertEquals(
        "[-127, 128]",
        org.stvnadore.core.lowering.StvnDefsLowerer.formatPhysicalIntRange(java.math.BigInteger.valueOf(8), false, true)
    );
    Assertions.assertEquals(
        "[0, 255]",
        org.stvnadore.core.lowering.StvnDefsLowerer.formatPhysicalIntRange(java.math.BigInteger.valueOf(8), true, false)
    );
    Assertions.assertEquals(
        "[-2^255, 2^255 - 1]",
        org.stvnadore.core.lowering.StvnDefsLowerer.formatPhysicalIntRange(java.math.BigInteger.valueOf(256), false, false)
    );
    Assertions.assertEquals(
        "[-2^255 + 1, 2^255]",
        org.stvnadore.core.lowering.StvnDefsLowerer.formatPhysicalIntRange(java.math.BigInteger.valueOf(256), false, true)
    );
    Assertions.assertEquals(
        "[0, 2^256 - 1]",
        org.stvnadore.core.lowering.StvnDefsLowerer.formatPhysicalIntRange(java.math.BigInteger.valueOf(256), true, false)
    );
    Assertions.assertEquals(
        "[1, 2^256]",
        org.stvnadore.core.lowering.StvnDefsLowerer.formatPhysicalIntRange(java.math.BigInteger.valueOf(256), true, true)
    );

    DiagnosticBag bag = new DiagnosticBag(1);
    bag.addError("First error", 0, 1, 1, 1, null, DiagnosticBag.ERR_TYPE_MISMATCH);
    bag.addError("Second error triggers limit warning", 2, 3, 2, 1, null, DiagnosticBag.ERR_TYPE_MISMATCH);

    var diags = bag.toList();
    Assertions.assertEquals(2, diags.size());
    var limitWarn = diags.get(1);
    Assertions.assertEquals("W003", limitWarn.canonicalCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.WARN_DIAG_LIMIT_EXCEEDED, limitWarn.symbolicId().orElse(""));

    StvnDiagnostic eightArgDiag = new StvnDiagnostic(
        "Test message", StvnDiagnostic.DiagnosticSeverity.ERROR, 1, 1, 0, 1,
        null, java.util.Optional.of(DiagnosticBag.ERR_DUPLICATE_MAP_KEY)
    );
    Assertions.assertEquals("E017", eightArgDiag.canonicalCode().orElse(""));
    Assertions.assertEquals("DUPLICATE_MAP_KEY", eightArgDiag.symbolicId().orElse(""));
  }

  @Test
  @DisplayName("TC-STAGE1-01: Gauntlet v3 Stage 1 empty metadata blocks, tautologies, and unbranded #CONST chain revalidation")
  void testGauntletV3Stage1EmptyBlocksAndConstChains() {
    String input = """
        {
          :meta {
            #name "gauntlet_v3_tautologies_empty_blocks_and_const_chains"
            #kind #DEFS_INCLUDE
          }
          :defs {
            // 2A: Empty metadata block {} is prohibited (Trap 11 -> E003)
            :EmptyMetaTrap {} :Int

            // 2B: Duplicate #size facet -> E008
            :DuplicateFacetTrap { #unsigned #size 16 #size 32 } :Int

            // 2C: Unrecognized facet '#bogusFacet 42' -> E036
            :UnknownFacetTrap { #unsigned #bogusFacet 42 #size 16 } :Int

            // 3A: Empty interval [500, 500) -> E009
            :EmptyIntervalTrap { #unsigned #size 16 #minIncl 500 #maxExcl 500 } :Int

            // 3B: Inverted bounds [2000, 1000) -> E009
            :InvertedBoundsTrap { #unsigned #size 16 #minIncl 2000 #maxExcl 1000 } :Int

            // 4. Compile-Time #CONST Bound References & Chained #CONST Validation
            #MIN_DYNAMIC_PORT { #unsigned #size 16 } :Int 1024
            #MAX_DYNAMIC_PORT { #unsigned #size 16 } :Int 4096

            :DynamicPortChannel {
              #unsigned
              #size 16
              #minIncl #MIN_DYNAMIC_PORT
              #maxExcl #MAX_DYNAMIC_PORT
            } :Int

            #DEFAULT_DYNAMIC_PORT :DynamicPortChannel #MIN_DYNAMIC_PORT
            #ILLEGAL_DYNAMIC_PORT :DynamicPortChannel 512
          }
        }
        """;
    var result = StvnCompiler.compile(
        input,
        "gauntlet_v3_tautologies_empty_blocks_and_const_chains.stvn_di",
        StvnParserConfig.DEFAULT
    );
    Assertions.assertTrue(result.hasErrors());
    var diags = result.diagnostics();
    Assertions.assertEquals(6, diags.size(), "Expected exactly 6 diagnostics (E003, E008, E036, E009, E009, E012): " + diags);
    Assertions.assertEquals("E003", diags.get(0).canonicalCode().orElse(""));
    Assertions.assertEquals("E008", diags.get(1).canonicalCode().orElse(""));
    Assertions.assertEquals("E036", diags.get(2).canonicalCode().orElse(""));
    Assertions.assertEquals("E009", diags.get(3).canonicalCode().orElse(""));
    Assertions.assertEquals("E009", diags.get(4).canonicalCode().orElse(""));
    Assertions.assertEquals("E012", diags.get(5).canonicalCode().orElse(""));
    int idx512 = input.indexOf("512");
    Assertions.assertEquals(idx512, diags.get(5).startOffset());
    Assertions.assertEquals(idx512 + 3, diags.get(5).endOffset());
  }

  @Test
  @DisplayName("TC-STAGE1-02: Empty metadata block {} preserves bestEffortFallback for child schemas and constants")
  void testEmptyMetadataBlockPreservesBestEffortFallback() {
    String input = """
        {
          :defs {
            :EmptyMetaTrap {} :Int
            :ChildFromEmpty { #minIncl 10 #maxExcl 5 } :EmptyMetaTrap
            #BAD_CONST {} :Int "not_an_int"
          }
          :type :Int
          :body 1
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.hasErrors());
    var codes = result.diagnostics().stream()
        .map(d -> d.canonicalCode().orElse(""))
        .toList();
    Assertions.assertEquals(
        java.util.List.of("E003", "E009", "E003", "E028"),
        codes,
        "Expected E003 + E009 (from child fallback) + E003 + E028 (from constant fallback): " + result.diagnostics()
    );
  }

  @Test
  @DisplayName("TC-STAGE4-01: Gauntlet v3 Stage 4 Total Nominal Bound Override in :defs and fault-isolated E012 validation in :body")
  void testStage4TotalNominalBoundOverrideAndBodyBounds() {
    String input = """
        {
          :meta {
            #name "inv_stage4_total_nominal_bound_override_and_body_bounds"
            #kind #BODY_INCLUDE
          }
          :defs {
            :BaseClusterId { #unsigned #size 16 #minIncl 100 #maxExcl 10000 } :Int
            :RegionClusterId { #minIncl 500 #maxExcl 5000 } :BaseClusterId
            :ReplacedBoundsCluster { #minIncl 200 #maxExcl 8000 } :RegionClusterId
            :StrictClusterId { #minIncl 1000 #maxExcl 2000 } :ReplacedBoundsCluster
            :CapacityOverflowBoundTrap { #minIncl 500 #maxExcl 70000 } :BaseClusterId
            :Stage4V3Envelope :Tuple(
              :ReplacedBoundsCluster
              :StrictClusterId
              :StrictClusterId
            )
          }
          :type :Stage4V3Envelope
          :body (
            300
            750
            2500
          )
        }
        """;
    var result = StvnCompiler.compile(
        input,
        "inv_stage4_total_nominal_bound_override_and_body_bounds.stvn_i",
        StvnParserConfig.DEFAULT
    );
    Assertions.assertTrue(result.hasErrors());
    var diags = result.diagnostics();
    Assertions.assertEquals(3, diags.size(), "Expected 1 E009 (:CapacityOverflowBoundTrap) + 2 E012 (750 and 2500): " + diags);
    Assertions.assertEquals("E009", diags.get(0).canonicalCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.ERR_INTEGER_OVERFLOW, diags.get(0).symbolicId().orElse(""));
    Assertions.assertEquals("E012", diags.get(1).canonicalCode().orElse(""));
    Assertions.assertEquals("750", input.substring(diags.get(1).startOffset(), diags.get(1).endOffset()));
    Assertions.assertEquals("E012", diags.get(2).canonicalCode().orElse(""));
    Assertions.assertEquals("2500", input.substring(diags.get(2).startOffset(), diags.get(2).endOffset()));
  }

  @Test
  @DisplayName("TC-STAGE5-01: Gauntlet v3 Stage 5 duplicate enum filter variant emits canonical E008 ERR_FILTER_DUPLICATE_VARIANT pinned strictly to second token")
  void testStage5DuplicateFilterVariantEmitsCanonicalE008PinnedToSecondToken() {
    String input = """
        {
          :defs {
            :LifecyclePhase :Enum [
              #ARCHIVED
              #CREATED
              #DEPLOYED
              #PENDING
              #VALIDATED
            ]
            :DuplicateFilterVariantTrap {
              #filterIncl [ #CREATED #CREATED #PENDING ]
            } :LifecyclePhase
          }
          :type :LifecyclePhase
          :body #CREATED
        }
        """;
    var result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.hasErrors());
    Assertions.assertEquals(1, result.diagnostics().size(), "Expected strictly 1 diagnostic on second #CREATED: " + result.diagnostics());
    var diag = result.diagnostics().getFirst();
    Assertions.assertEquals("E008", diag.canonicalCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.ERR_FILTER_DUPLICATE_VARIANT, diag.symbolicId().orElse(""));
    Assertions.assertEquals(
        "Enum subset violation (:DuplicateFilterVariantTrap): duplicate variant '#CREATED' in filter list",
        diag.message()
    );
    int filterIdx = input.indexOf("#filterIncl");
    int firstCreated = input.indexOf("#CREATED", filterIdx);
    int secondCreated = input.indexOf("#CREATED", firstCreated + "#CREATED".length());
    Assertions.assertEquals(secondCreated, diag.startOffset(), "Diagnostic startOffset must pin strictly to the second #CREATED");
    Assertions.assertEquals(secondCreated + "#CREATED".length(), diag.endOffset(), "Diagnostic endOffset must pin strictly to the second #CREATED");
  }

  @Test
  @DisplayName("TC-GAUNTLET-V4-STAGE1-01: Disordered :meta facets, reserved stvn_* domain, unknown #author facet, and Pass 3 :body error accumulate in single pass")
  void testGauntletV4Stage1MetaMultiErrorAndBodyIsolation() throws Exception {
    java.nio.file.Path fixturePath = java.nio.file.Path.of(
        "shared-fixtures/syntax/invalid/metadata/gauntlet_v4_stage1_meta_multi_error_and_body.core_test.stvn_i"
    );
    String input = java.nio.file.Files.readString(fixturePath, java.nio.charset.StandardCharsets.UTF_8)
        .replace("\r\n", "\n");
    var result = StvnCompiler.compile(input, fixturePath.toString(), StvnParserConfig.DEFAULT);
    Assertions.assertTrue(result.hasErrors());
    var diags = result.diagnostics();
    Assertions.assertEquals(7, diags.size(), "Expected 5 errors (E008, E038, E008, E008, E012) and 2 stem warnings (W001): " + diags);

    Assertions.assertEquals("E008", diags.get(0).canonicalCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.ERR_FACET_ORDER_VIOLATION, diags.get(0).symbolicId().orElse(""));
    Assertions.assertEquals("#domain \"stvn_reserved_trap\"", input.substring(diags.get(0).startOffset(), diags.get(0).endOffset()));

    Assertions.assertEquals("E038", diags.get(1).canonicalCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED, diags.get(1).symbolicId().orElse(""));
    Assertions.assertEquals("\"stvn_reserved_trap\"", input.substring(diags.get(1).startOffset(), diags.get(1).endOffset()));

    Assertions.assertEquals("E008", diags.get(2).canonicalCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.ERR_FACET_ORDER_VIOLATION, diags.get(2).symbolicId().orElse(""));
    Assertions.assertEquals("#name \"gauntlet_v4_stage1_wrong_stem\"", input.substring(diags.get(2).startOffset(), diags.get(2).endOffset()));

    Assertions.assertEquals("E008", diags.get(3).canonicalCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.ERR_INVALID_METADATA_FACET, diags.get(3).symbolicId().orElse(""));
    Assertions.assertEquals("#author \"rogue_architect\"", input.substring(diags.get(3).startOffset(), diags.get(3).endOffset()));

    Assertions.assertEquals("W001", diags.get(4).canonicalCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.WARN_DOCUMENT_NAME_MISMATCH, diags.get(4).symbolicId().orElse(""));
    Assertions.assertEquals("\"gauntlet_v4_stage1_wrong_stem\"", input.substring(diags.get(4).startOffset(), diags.get(4).endOffset()));

    Assertions.assertEquals("W001", diags.get(5).canonicalCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.WARN_DOCUMENT_NAME_MISMATCH, diags.get(5).symbolicId().orElse(""));
    Assertions.assertEquals("\"stvn_reserved_trap\"", input.substring(diags.get(5).startOffset(), diags.get(5).endOffset()));

    Assertions.assertEquals("E012", diags.get(6).canonicalCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, diags.get(6).symbolicId().orElse(""));
    Assertions.assertEquals("5000", input.substring(diags.get(6).startOffset(), diags.get(6).endOffset()));
  }

  @Test
  @DisplayName("TC-GAUNTLET-V4-STAGE1-02: Empty :meta {} emits E039 on {} without E031 and preserves Pass 3 :body validation")
  void testGauntletV4Stage1EmptyMetaBlockAndBodyIsolation() throws Exception {
    java.nio.file.Path fixturePath = java.nio.file.Path.of(
        "shared-fixtures/syntax/invalid/metadata/empty_meta_block_with_body_error.core_test.stvn_i"
    );
    String input = java.nio.file.Files.readString(fixturePath, java.nio.charset.StandardCharsets.UTF_8)
        .replace("\r\n", "\n");
    var result = StvnCompiler.compile(input, fixturePath.toString(), StvnParserConfig.DEFAULT);
    Assertions.assertTrue(result.hasErrors());
    var diags = result.diagnostics();
    Assertions.assertEquals(2, diags.size(), "Expected strictly E039 on :meta {} and E012 on :body 5000: " + diags);
    Assertions.assertEquals("E039", diags.get(0).canonicalCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.ERR_EMPTY_META_BLOCK, diags.get(0).symbolicId().orElse(""));
    Assertions.assertEquals("{}", input.substring(diags.get(0).startOffset(), diags.get(0).endOffset()));
    Assertions.assertEquals("E012", diags.get(1).canonicalCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, diags.get(1).symbolicId().orElse(""));
    Assertions.assertEquals("5000", input.substring(diags.get(1).startOffset(), diags.get(1).endOffset()));
  }

  @Test
  @DisplayName("TC-GAUNTLET-V4-STAGE1-03: Empty :defs {} in payload document emits E002 on {} and preserves Pass 3 :body validation")
  void testGauntletV4Stage1EmptyDefsBlockInPayloadAndBodyIsolation() throws Exception {
    java.nio.file.Path fixturePath = java.nio.file.Path.of(
        "shared-fixtures/syntax/invalid/metadata/empty_defs_block_in_payload.core_test.stvn_i"
    );
    String input = java.nio.file.Files.readString(fixturePath, java.nio.charset.StandardCharsets.UTF_8)
        .replace("\r\n", "\n");
    var result = StvnCompiler.compile(input, fixturePath.toString(), StvnParserConfig.DEFAULT);
    Assertions.assertTrue(result.hasErrors());
    var diags = result.diagnostics();
    Assertions.assertEquals(2, diags.size(), "Expected strictly E002 on :defs {} and E012 on :body 5000: " + diags);
    Assertions.assertEquals("E002", diags.get(0).canonicalCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.ERR_EMPTY_DEFS_BLOCK, diags.get(0).symbolicId().orElse(""));
    Assertions.assertEquals("{}", input.substring(diags.get(0).startOffset(), diags.get(0).endOffset()));
    Assertions.assertEquals("E012", diags.get(1).canonicalCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, diags.get(1).symbolicId().orElse(""));
    Assertions.assertEquals("5000", input.substring(diags.get(1).startOffset(), diags.get(1).endOffset()));
  }

  @Test
  @DisplayName("TC-GAUNTLET-V4-STAGE7-01: Single-branch :Union(:ErrorCode) emits E010 ERR_MALFORMED_SCHEMA, lowers to ungrounded PoisonedSchema, suspends Pass 3 on #1 404, and preserves sibling E012 validation")
  void testSingleBranchUnionEmitsE010AndPoisonsUngroundedWithSiblingIsolation() throws Exception {
    java.nio.file.Path fixturePath = java.nio.file.Path.of(
        "shared-fixtures/syntax/invalid/metadata/single_branch_union_poisoning.core_test.stvn_i"
    );
    String input = java.nio.file.Files.readString(fixturePath, java.nio.charset.StandardCharsets.UTF_8)
        .replace("\r\n", "\n");
    var result = StvnCompiler.compile(input, fixturePath.toString(), StvnParserConfig.DEFAULT);
    Assertions.assertTrue(result.hasErrors());
    var diags = result.diagnostics();
    Assertions.assertEquals(2, diags.size(), "Expected strictly E010 on :Union(:ErrorCode) and E012 on sibling 5000: " + diags);

    Assertions.assertEquals("E010", diags.get(0).canonicalCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.ERR_MALFORMED_SCHEMA, diags.get(0).symbolicId().orElse(""));
    Assertions.assertEquals(":Union(:ErrorCode)", input.substring(diags.get(0).startOffset(), diags.get(0).endOffset()));
    Assertions.assertEquals(9, diags.get(0).line());
    Assertions.assertEquals(27, diags.get(0).column());
    Assertions.assertEquals(212, diags.get(0).startOffset());
    Assertions.assertEquals(230, diags.get(0).endOffset());

    Assertions.assertEquals("E012", diags.get(1).canonicalCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, diags.get(1).symbolicId().orElse(""));
    Assertions.assertEquals("5000", input.substring(diags.get(1).startOffset(), diags.get(1).endOffset()));
    Assertions.assertEquals(19, diags.get(1).line());
    Assertions.assertEquals(4, diags.get(1).column());
    Assertions.assertEquals(451, diags.get(1).startOffset());
    Assertions.assertEquals(455, diags.get(1).endOffset());

    var doc = result.document().orElseThrow();
    var trapSchema = doc.defs().findType(":TrapSingleBranchUnion").orElseThrow();
    var poisoned = Assertions.assertInstanceOf(org.stvnadore.core.ir.StvnSchema.PoisonedSchema.class, trapSchema);
    Assertions.assertTrue(poisoned.isUngrounded(), "Single-branch :Union must lower to an ungrounded PoisonedSchema");
    Assertions.assertTrue(poisoned.bestEffortFallback().isEmpty(), "Ungrounded PoisonedSchema must have empty bestEffortFallback");

    var tupleVal = Assertions.assertInstanceOf(StvnTuple.class, doc.payload().orElseThrow());
    Assertions.assertEquals(2, tupleVal.elements().size());
    var slot0 = Assertions.assertInstanceOf(org.stvnadore.core.ir.StvnValue.StvnErrorValue.class, tupleVal.elements().get(0));
    Assertions.assertEquals("E010", slot0.diagnostic().canonicalCode().orElse(""));
  }

  @Test
  @DisplayName("TC-DIAG-GAUNTLET-V5-S1: Duplicate :meta blocks after invalid :meta emit E033 on each duplicate :meta keyword and preserve Pass 3 :body validation")
  void testGauntletV5Stage1DuplicateMetaAndBodyFaultIsolation() throws Exception {
    java.nio.file.Path fixturePath = java.nio.file.Path.of(
        "shared-fixtures/syntax/invalid/metadata/duplicate_meta_and_body_fault_isolation.core_test.stvn_i"
    );
    String input = java.nio.file.Files.readString(fixturePath, java.nio.charset.StandardCharsets.UTF_8)
        .replace("\r\n", "\n");
    var result = StvnCompiler.compile(input, fixturePath.toString(), StvnParserConfig.DEFAULT);
    Assertions.assertTrue(result.hasErrors());
    var diags = result.diagnostics();
    Assertions.assertEquals(
        9,
        diags.size(),
        "Expected 7 errors (E008, E038, E008, E008, E033, E033, E012) and 2 stem warnings (W001): " + diags
    );

    Assertions.assertEquals("E033", diags.get(6).canonicalCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.ERR_META_POSITION_INVALID, diags.get(6).symbolicId().orElse(""));
    Assertions.assertEquals(":meta", input.substring(diags.get(6).startOffset(), diags.get(6).endOffset()));
    Assertions.assertEquals(8, diags.get(6).line());
    Assertions.assertEquals(2, diags.get(6).column());
    Assertions.assertEquals(147, diags.get(6).startOffset());
    Assertions.assertEquals(152, diags.get(6).endOffset());

    Assertions.assertEquals("E033", diags.get(7).canonicalCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.ERR_META_POSITION_INVALID, diags.get(7).symbolicId().orElse(""));
    Assertions.assertEquals(":meta", input.substring(diags.get(7).startOffset(), diags.get(7).endOffset()));
    Assertions.assertEquals(9, diags.get(7).line());
    Assertions.assertEquals(2, diags.get(7).column());
    Assertions.assertEquals(158, diags.get(7).startOffset());
    Assertions.assertEquals(163, diags.get(7).endOffset());

    Assertions.assertEquals("E012", diags.get(8).canonicalCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, diags.get(8).symbolicId().orElse(""));
    Assertions.assertEquals("5000", input.substring(diags.get(8).startOffset(), diags.get(8).endOffset()));
  }

  @Test
  @DisplayName("TC-DIAG-GAUNTLET-V5-S2: Case B Grounded PoisonedSchema (:TrapIntMinExcl) unwraps in Pass 2 and suspends Pass 3 :body validation per § 7.3.5")
  void testGauntletV5Stage2GroundedPoisonedSchemaBodySuspension() throws Exception {
    java.nio.file.Path fixturePath = java.nio.file.Path.of(
        "shared-fixtures/syntax/invalid/metadata/grounded_poisoned_schema_body_suspension.core_test.stvn_i"
    );
    String input = java.nio.file.Files.readString(fixturePath, java.nio.charset.StandardCharsets.UTF_8)
        .replace("\r\n", "\n");
    var result = StvnCompiler.compile(input, fixturePath.toString(), StvnParserConfig.DEFAULT);
    Assertions.assertTrue(result.hasErrors());
    var diags = result.diagnostics();
    Assertions.assertEquals(
        3,
        diags.size(),
        "Expected strictly E011 on :TrapIntMinExcl, E040 on :ChildUnsignedTrap, and E012 on healthy sibling 5000: " + diags
    );

    Assertions.assertEquals("E011", diags.get(0).canonicalCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.ERR_DISCRETE_BOUND_KIND_PROHIBITED, diags.get(0).symbolicId().orElse(""));
    Assertions.assertEquals("#minExcl 10", input.substring(diags.get(0).startOffset(), diags.get(0).endOffset()));

    Assertions.assertEquals("E040", diags.get(1).canonicalCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.ERR_NON_OVERRIDABLE_FACET, diags.get(1).symbolicId().orElse(""));
    Assertions.assertEquals("#unsigned", input.substring(diags.get(1).startOffset(), diags.get(1).endOffset()));

    Assertions.assertEquals("E012", diags.get(2).canonicalCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, diags.get(2).symbolicId().orElse(""));
    Assertions.assertEquals("5000", input.substring(diags.get(2).startOffset(), diags.get(2).endOffset()));

    var doc = result.document().orElseThrow();
    var trapSchema = doc.defs().findType(":TrapIntMinExcl").orElseThrow();
    var poisoned = Assertions.assertInstanceOf(org.stvnadore.core.ir.StvnSchema.PoisonedSchema.class, trapSchema);
    Assertions.assertTrue(poisoned.isGrounded(), "Case B PoisonedSchema must report isGrounded() == true");
    Assertions.assertInstanceOf(org.stvnadore.core.ir.StvnSchema.IntSchema.class, poisoned.bestEffortFallback().orElseThrow());

    var tupleVal = Assertions.assertInstanceOf(StvnTuple.class, doc.payload().orElseThrow());
    Assertions.assertEquals(3, tupleVal.elements().size());
    Assertions.assertInstanceOf(org.stvnadore.core.ir.StvnValue.StvnErrorValue.class, tupleVal.elements().get(0));
    Assertions.assertInstanceOf(org.stvnadore.core.ir.StvnValue.StvnErrorValue.class, tupleVal.elements().get(1));
  }
}