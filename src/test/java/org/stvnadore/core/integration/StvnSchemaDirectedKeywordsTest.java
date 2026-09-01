package org.stvnadore.core.integration;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.binary.SchemaIdentityStrategy;
import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.binary.StvnBinaryEncoder;
import org.stvnadore.core.ir.StvnValue.*;
import org.stvnadore.core.validation.MalformedPayloadException;

import java.util.Optional;

/**
 * Verification test suite for Schema-Directed Value Keyword Resolution & Grammar Disambiguation.
 * <p>
 * Evaluates the 4-dimensional resolution hierarchy and eliminates greedy constructor swallowing
 * across Enum variants, Non-Sum Constants, Option/Either control tags, and Disjoint Union constructors
 * across test vectors TC-DIM-01 through TC-DIM-11.
 */
@NullMarked
public class StvnSchemaDirectedKeywordsTest {

  @Test
  @DisplayName("TC-DIM-01: Nominal Enum Reserved Names in 6-Tuple")
  public void testDim01_NominalEnumReservedNames() {
    String payload = """
        {
          :defs {
            :ControlCode :Enum [ #None #Some #Left #Right #TRUE #FALSE ]
          }
          :type :Tuple( :ControlCode :ControlCode :ControlCode :ControlCode :ControlCode :ControlCode )
          :body ( #None #Some #Left #Right #TRUE #FALSE )
        }
        """;

    var irOpt = StvnCompiler.compile(payload);
    Assertions.assertTrue(irOpt.isPresent());
    var ir = irOpt.get();
    Assertions.assertInstanceOf(StvnTuple.class, ir);
    var tuple = (StvnTuple) ir;

    Assertions.assertEquals(6, tuple.elements().size());
    String[] expectedKeywords = {"#None", "#Some", "#Left", "#Right", "#TRUE", "#FALSE"};
    for (int i = 0; i < 6; i++) {
      var elem = tuple.elements().get(i);
      Assertions.assertInstanceOf(StvnEnum.class, elem);
      var enumVal = (StvnEnum) elem;
      Assertions.assertEquals(expectedKeywords[i], enumVal.keyword());
      Assertions.assertEquals(i, enumVal.sequentialIndex());
    }

    assertBinaryRoundTrip(ir);
  }

  @Test
  @DisplayName("TC-DIM-02: Non-Sum Target with Constant Substitution in Tuple")
  public void testDim02_NonSumConstantSubstitutionInTuple() {
    String payload = """
        {
          :defs {
            #Some :Uint7 10
            #Left :Uint7 20
          }
          :type :Tuple( :Uint7 :Uint7 )
          :body ( #Some #Left )
        }
        """;

    var irOpt = StvnCompiler.compile(payload);
    Assertions.assertTrue(irOpt.isPresent());
    var ir = irOpt.get();
    Assertions.assertInstanceOf(StvnTuple.class, ir);
    var tuple = (StvnTuple) ir;

    Assertions.assertEquals(2, tuple.elements().size());
    Assertions.assertInstanceOf(StvnInteger.class, tuple.elements().get(0));
    Assertions.assertInstanceOf(StvnInteger.class, tuple.elements().get(1));

    Assertions.assertEquals(10L, ((StvnInteger) tuple.elements().get(0)).value().longValue());
    Assertions.assertEquals(20L, ((StvnInteger) tuple.elements().get(1)).value().longValue());

    assertBinaryRoundTrip(ir);
  }

  @Test
  @DisplayName("TC-DIM-03: Context-Directed Disambiguation with Same Token #Left")
  public void testDim03_ContextDirectedDisambiguationSameToken() {
    String payload = """
        {
          :defs {
            :Mode :Enum [ #Left #Right ]
            #Left :Uint7 99
          }
          :type :Tuple( :Mode :Uint7 )
          :body ( #Left #Left )
        }
        """;

    var irOpt = StvnCompiler.compile(payload);
    Assertions.assertTrue(irOpt.isPresent());
    var ir = irOpt.get();
    Assertions.assertInstanceOf(StvnTuple.class, ir);
    var tuple = (StvnTuple) ir;

    Assertions.assertEquals(2, tuple.elements().size());

    // Element 0: Dimension 1 (Target :Mode -> StvnEnum)
    Assertions.assertInstanceOf(StvnEnum.class, tuple.elements().get(0));
    var enumVal = (StvnEnum) tuple.elements().get(0);
    Assertions.assertEquals("#Left", enumVal.keyword());
    Assertions.assertEquals(0, enumVal.sequentialIndex());

    // Element 1: Dimension 2 (Target :Uint7 -> StvnInteger constant substitution)
    Assertions.assertInstanceOf(StvnInteger.class, tuple.elements().get(1));
    var intVal = (StvnInteger) tuple.elements().get(1);
    Assertions.assertEquals(99L, intVal.value().longValue());

    assertBinaryRoundTrip(ir);
  }

  @Test
  @DisplayName("TC-DIM-04: Unit Variant Tag #None Precedence over Constant Substitution")
  public void testDim04_UnitVariantNonePrecedence() {
    String payload = """
        {
          :defs {
            #None :Uint7 0
          }
          :type :Option( :Uint7 )
          :body #None
        }
        """;

    var irOpt = StvnCompiler.compile(payload);
    Assertions.assertTrue(irOpt.isPresent());
    var ir = irOpt.get();
    Assertions.assertInstanceOf(StvnOption.class, ir);
    var opt = (StvnOption) ir;
    Assertions.assertTrue(opt.value().isEmpty(), "Expected None variant of Option");

    assertBinaryRoundTrip(ir);
  }

  @Test
  @DisplayName("TC-DIM-05: Rule D Explicit Disambiguation (#Some #None)")
  public void testDim05_RuleDExplicitDisambiguation() {
    String payload = """
        {
          :defs {
            #None :Uint7 0
          }
          :type :Option( :Uint7 )
          :body #Some #None
        }
        """;

    var irOpt = StvnCompiler.compile(payload);
    Assertions.assertTrue(irOpt.isPresent());
    var ir = irOpt.get();
    Assertions.assertInstanceOf(StvnOption.class, ir);
    var opt = (StvnOption) ir;
    Assertions.assertTrue(opt.value().isPresent(), "Expected Some variant of Option");
    Assertions.assertInstanceOf(StvnInteger.class, opt.value().get());
    Assertions.assertEquals(0L, ((StvnInteger) opt.value().get()).value().longValue());

    assertBinaryRoundTrip(ir);
  }

  @Test
  @DisplayName("TC-DIM-06: Rule A Implied #Some on Constant")
  public void testDim06_RuleAImpliedSomeOnConstant() {
    String payload = """
        {
          :defs {
            #VAL :Uint7 5
          }
          :type :Option( :Uint7 )
          :body #VAL
        }
        """;

    var irOpt = StvnCompiler.compile(payload);
    Assertions.assertTrue(irOpt.isPresent());
    var ir = irOpt.get();
    Assertions.assertInstanceOf(StvnOption.class, ir);
    var opt = (StvnOption) ir;
    Assertions.assertTrue(opt.value().isPresent(), "Expected Implied Some on Constant");
    Assertions.assertInstanceOf(StvnInteger.class, opt.value().get());
    Assertions.assertEquals(5L, ((StvnInteger) opt.value().get()).value().longValue());

    assertBinaryRoundTrip(ir);
  }

  @Test
  @DisplayName("TC-DIM-07: Explicit #Left with Constant Payload")
  public void testDim07_ExplicitLeftWithConstantPayload() {
    String payload = """
        {
          :defs {
            #ERR :Uint16 404
          }
          :type :Either( :Uint16 :String )
          :body #Left #ERR
        }
        """;

    var irOpt = StvnCompiler.compile(payload);
    Assertions.assertTrue(irOpt.isPresent());
    var ir = irOpt.get();
    Assertions.assertInstanceOf(StvnEither.class, ir);
    var either = (StvnEither) ir;
    Assertions.assertFalse(either.isRight(), "Expected Left branch of Either");
    Assertions.assertInstanceOf(StvnInteger.class, either.value());
    Assertions.assertEquals(404L, ((StvnInteger) either.value()).value().longValue());

    assertBinaryRoundTrip(ir);
  }

  @Test
  @DisplayName("TC-DIM-08: Rule E Enforcement & Explicit #Left Resolution for Constant Matching Left Branch")
  public void testDim08_SymmetricImplicitLeftConstant() {
    // 1. Untagged #ERR matches Left branch -> must fail under Rule E
    String payloadUntagged = """
        {
          :defs {
            #ERR :Uint16 404
          }
          :type :Either( :Uint16 :String )
          :body #ERR
        }
        """;

    var result = StvnCompiler.compileToResult(payloadUntagged);
    Assertions.assertTrue(result.hasErrors(), "Untagged constant matching Left branch must fail compilation under Rule E");
    var diag = result.diagnostics().getFirst();
    Assertions.assertTrue(
        diag.message().contains("Rule E Violation") || diag.message().contains("non-inferable"),
        "Expected Rule E non-inferable diagnostic, got: " + diag.message()
    );

    // 2. Explicit #Left #ERR -> succeeds cleanly
    String payloadExplicit = """
        {
          :defs {
            #ERR :Uint16 404
          }
          :type :Either( :Uint16 :String )
          :body #Left #ERR
        }
        """;

    var irOpt = StvnCompiler.compile(payloadExplicit);
    Assertions.assertTrue(irOpt.isPresent());
    var ir = irOpt.get();
    Assertions.assertInstanceOf(StvnEither.class, ir);
    var either = (StvnEither) ir;
    Assertions.assertFalse(either.isRight(), "Expected Left branch of Either for #Left #ERR");
    Assertions.assertInstanceOf(StvnInteger.class, either.value());
    Assertions.assertEquals(404L, ((StvnInteger) either.value()).value().longValue());

    assertBinaryRoundTrip(ir);
  }

  @Test
  @DisplayName("TC-DIM-09: Rule B Implied #Right on Constant")
  public void testDim09_RuleBImpliedRightOnConstant() {
    String payload = """
        {
          :defs {
            #MSG :String "OK"
          }
          :type :Either( :Uint7 :String )
          :body #MSG
        }
        """;

    var irOpt = StvnCompiler.compile(payload);
    Assertions.assertTrue(irOpt.isPresent());
    var ir = irOpt.get();
    Assertions.assertInstanceOf(StvnEither.class, ir);
    var either = (StvnEither) ir;
    Assertions.assertTrue(either.isRight(), "Expected Right branch of Either");
    Assertions.assertInstanceOf(StvnString.class, either.value());
    Assertions.assertEquals("OK", ((StvnString) either.value()).value());

    assertBinaryRoundTrip(ir);
  }

  @Test
  @DisplayName("TC-DIM-10: Rule C Implied Union Branch Index on Constant")
  public void testDim10_RuleCImpliedUnionBranchOnConstant() {
    String payload = """
        {
          :defs {
            #FLAG :Boolean #TRUE
          }
          :type :Union( :Int32 :String :Boolean )
          :body #FLAG
        }
        """;

    var irOpt = StvnCompiler.compile(payload);
    Assertions.assertTrue(irOpt.isPresent());
    var ir = irOpt.get();
    Assertions.assertInstanceOf(StvnUnion.class, ir);
    var union = (StvnUnion) ir;
    Assertions.assertEquals(2, union.tagIndex(), "Expected branch index 2 (:Boolean)");
    Assertions.assertInstanceOf(StvnBoolean.class, union.value());
    Assertions.assertTrue(((StvnBoolean) union.value()).value());

    assertBinaryRoundTrip(ir);
  }

  @Test
  @DisplayName("TC-DIM-11: 8-Ary Collision Tuple Stress Test (Zero Swallowing)")
  public void testDim11_EightAryCollisionTupleStress() {
    String payload = """
        {
          :defs {
            #Some  :Uint5 1
            #None  :Uint5 2
            #Left  :Uint5 3
            #Right :Uint5 4
            #TRUE  :Uint5 5
            #FALSE :Uint5 6
            #True  :Uint5 7
            #False :Uint5 8
          }
          :type :Tuple( :Uint5 :Uint5 :Uint5 :Uint5 :Uint5 :Uint5 :Uint5 :Uint5 )
          :body ( #Some #None #Left #Right #TRUE #FALSE #True #False )
        }
        """;

    var irOpt = StvnCompiler.compile(payload);
    Assertions.assertTrue(irOpt.isPresent());
    var ir = irOpt.get();
    Assertions.assertInstanceOf(StvnTuple.class, ir);
    var tuple = (StvnTuple) ir;

    Assertions.assertEquals(8, tuple.elements().size());
    for (int i = 0; i < 8; i++) {
      var elem = tuple.elements().get(i);
      Assertions.assertInstanceOf(StvnInteger.class, elem);
      Assertions.assertEquals((long) (i + 1), ((StvnInteger) elem).value().longValue());
    }

    assertBinaryRoundTrip(ir);
  }

  private void assertBinaryRoundTrip(org.stvnadore.core.ir.StvnValue ir) {
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
    var encoded = encoder.encode(ir);
    var rootPointer = StvnBinaryDecoder.openStrict(encoded, new SchemaIdentityStrategy.UniversalDefault());
    var decodedIr = StvnBinaryDecoder.unpack(rootPointer, Optional.of(ir.schema()));
    Assertions.assertEquals(ir, decodedIr);
  }
}
