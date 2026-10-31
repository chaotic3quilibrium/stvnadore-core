package org.stvnadore.core.ir;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ir.StvnValue.EitherBranch;
import org.stvnadore.core.ir.StvnValue.StvnEither;
import org.stvnadore.core.ir.StvnValue.StvnFloat;
import org.stvnadore.core.ir.StvnValue.StvnInt;
import org.stvnadore.core.ir.StvnValue.StvnOption;
import org.stvnadore.core.ir.StvnValue.StvnSeq;
import org.stvnadore.core.ir.StvnValue.StvnString;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

/**
 * Regression test suite verifying pure-IR sum-type resolution across explicit long/short tags
 * and implied sum-type lowering (§ 8.1 Inference Rules A–J).
 *
 * @since 1.0.0
 */
@NullMarked
class StvnVariantTrajectoryTest {

  @Test
  void testExplicitOptionLongAndShortTagsNotInferred() {
    // 1. Long form: #Some 42
    var longFormInput = """
        {
          :type :Option(:Int)
          :body #Some 42
        }
        """;
    var longVal = StvnCompiler.compilePayload(longFormInput).orElseThrow();
    Assertions.assertInstanceOf(StvnOption.class, longVal);
    var longOpt = (StvnOption) longVal;
    Assertions.assertTrue(longOpt.value().isPresent());
    Assertions.assertEquals(BigInteger.valueOf(42), ((StvnInt) longOpt.value().get()).value());

    // 2. Short form: #S 42
    var shortFormInput = """
        {
          :type :Option(:Int)
          :body #S 42
        }
        """;
    var shortVal = StvnCompiler.compilePayload(shortFormInput).orElseThrow();
    Assertions.assertInstanceOf(StvnOption.class, shortVal);
    var shortOpt = (StvnOption) shortVal;
    Assertions.assertTrue(shortOpt.value().isPresent());
    Assertions.assertEquals(BigInteger.valueOf(42), ((StvnInt) shortOpt.value().get()).value());

    // 3. Long form None: #None
    var noneLongInput = """
        {
          :type :Option(:Int)
          :body #None
        }
        """;
    var noneLongVal = StvnCompiler.compilePayload(noneLongInput).orElseThrow();
    Assertions.assertInstanceOf(StvnOption.class, noneLongVal);
    var noneLongOpt = (StvnOption) noneLongVal;
    Assertions.assertTrue(noneLongOpt.value().isEmpty());

    // 4. Short form None: #N
    var noneShortInput = """
        {
          :type :Option(:Int)
          :body #N
        }
        """;
    var noneShortVal = StvnCompiler.compilePayload(noneShortInput).orElseThrow();
    Assertions.assertInstanceOf(StvnOption.class, noneShortVal);
    var noneShortOpt = (StvnOption) noneShortVal;
    Assertions.assertTrue(noneShortOpt.value().isEmpty());
  }

  @Test
  void testImpliedOptionTagIsInferred() {
    var input = """
        {
          :type :Option(:Int)
          :body 42
        }
        """;
    var val = StvnCompiler.compilePayload(input).orElseThrow();
    Assertions.assertInstanceOf(StvnOption.class, val);
    var opt = (StvnOption) val;
    Assertions.assertTrue(opt.value().isPresent());
    Assertions.assertEquals(BigInteger.valueOf(42), ((StvnInt) opt.value().get()).value());
  }

  @Test
  void testExplicitEitherLongAndShortTagsNotInferred() {
    // 1. Long form Right: #Right 42
    var rightLongInput = """
        {
          :type :Either(:String :Int)
          :body #Right 42
        }
        """;
    var rightLongVal = StvnCompiler.compilePayload(rightLongInput).orElseThrow();
    Assertions.assertInstanceOf(StvnEither.class, rightLongVal);
    var rightLongEither = (StvnEither) rightLongVal;
    Assertions.assertEquals(EitherBranch.RIGHT, rightLongEither.branch());
    Assertions.assertEquals(BigInteger.valueOf(42), ((StvnInt) rightLongEither.value()).value());

    // 2. Short form Right: #R 42
    var rightShortInput = """
        {
          :type :Either(:String :Int)
          :body #R 42
        }
        """;
    var rightShortVal = StvnCompiler.compilePayload(rightShortInput).orElseThrow();
    Assertions.assertInstanceOf(StvnEither.class, rightShortVal);
    var rightShortEither = (StvnEither) rightShortVal;
    Assertions.assertEquals(EitherBranch.RIGHT, rightShortEither.branch());
    Assertions.assertEquals(BigInteger.valueOf(42), ((StvnInt) rightShortEither.value()).value());

    // 3. Long form Left: #Left "err"
    var leftLongInput = """
        {
          :type :Either(:String :Int)
          :body #Left "err"
        }
        """;
    var leftLongVal = StvnCompiler.compilePayload(leftLongInput).orElseThrow();
    Assertions.assertInstanceOf(StvnEither.class, leftLongVal);
    var leftLongEither = (StvnEither) leftLongVal;
    Assertions.assertEquals(EitherBranch.LEFT, leftLongEither.branch());
    Assertions.assertEquals("err", ((StvnString) leftLongEither.value()).value());

    // 4. Short form Left: #L "err"
    var leftShortInput = """
        {
          :type :Either(:String :Int)
          :body #L "err"
        }
        """;
    var leftShortVal = StvnCompiler.compilePayload(leftShortInput).orElseThrow();
    Assertions.assertInstanceOf(StvnEither.class, leftShortVal);
    var leftShortEither = (StvnEither) leftShortVal;
    Assertions.assertEquals(EitherBranch.LEFT, leftShortEither.branch());
    Assertions.assertEquals("err", ((StvnString) leftShortEither.value()).value());
  }

  @Test
  void testImpliedEitherTagIsInferred() {
    var input = """
        {
          :type :Either(:String :Int)
          :body 42
        }
        """;
    var val = StvnCompiler.compilePayload(input).orElseThrow();
    Assertions.assertInstanceOf(StvnEither.class, val);
    var either = (StvnEither) val;
    Assertions.assertEquals(EitherBranch.RIGHT, either.branch());
    Assertions.assertEquals(BigInteger.valueOf(42), ((StvnInt) either.value()).value());
  }

  @Test
  void testDeepNestedSumTypeTrajectoryProvenance() {
    var fixture = """
        {
          :type :Seq(:Option(:Either(:String :Option(:Either(:String :Float)))))
          :body [
            #Some #Right #Some #Right 1.234
            #Right #Some #Right 1.234
            #Some #Right 1.234
            #Right 1.234
            1.234
          ]
        }
        """;

    var compiled = StvnCompiler.compilePayload(fixture).orElseThrow();
    Assertions.assertInstanceOf(StvnSeq.class, compiled);
    var seq = (StvnSeq) compiled;
    List<StvnValue> elements = seq.elements();
    Assertions.assertEquals(5, elements.size(), "Fixture should contain exactly 5 elements");

    for (int i = 0; i < 5; i++) {
      var optOuter = (StvnOption) elements.get(i);
      var eitherOuter = (StvnEither) optOuter.value().orElseThrow();
      Assertions.assertEquals(EitherBranch.RIGHT, eitherOuter.branch());
      var optInner = (StvnOption) eitherOuter.value();
      var eitherInner = (StvnEither) optInner.value().orElseThrow();
      Assertions.assertEquals(EitherBranch.RIGHT, eitherInner.branch());
      var floatVal = (StvnFloat) eitherInner.value();
      Assertions.assertEquals(new BigDecimal("1.234"), floatVal.decimalValue());
    }
  }
}
