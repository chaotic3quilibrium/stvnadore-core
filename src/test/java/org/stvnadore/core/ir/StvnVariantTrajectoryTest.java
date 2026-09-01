package org.stvnadore.core.ir;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ir.StvnValue.StvnEither;
import org.stvnadore.core.ir.StvnValue.StvnFloat;
import org.stvnadore.core.ir.StvnValue.StvnOption;
import org.stvnadore.core.ir.StvnValue.StvnSeq;

import java.util.List;

/**
 * Regression test suite verifying variant resolution trajectory provenance
 * and {@link VariantStep#isInferred()} flag accuracy across explicit and implied sum-type combinations.
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
          :type :Option(:Int32)
          :body #Some 42
        }
        """;
    var longVal = StvnCompiler.compile(longFormInput).orElseThrow();
    Assertions.assertInstanceOf(StvnOption.class, longVal);
    var longOpt = (StvnOption) longVal;
    var longTrajectory = longOpt.trajectory();
    Assertions.assertEquals(1, longTrajectory.size());
    Assertions.assertEquals("#Some", longTrajectory.get(0).tag());
    Assertions.assertFalse(longTrajectory.get(0).isInferred(), "Explicit #Some must have isInferred == false");

    // 2. Short form: #S 42
    var shortFormInput = """
        {
          :type :Option(:Int32)
          :body #S 42
        }
        """;
    var shortVal = StvnCompiler.compile(shortFormInput).orElseThrow();
    Assertions.assertInstanceOf(StvnOption.class, shortVal);
    var shortOpt = (StvnOption) shortVal;
    var shortTrajectory = shortOpt.trajectory();
    Assertions.assertEquals(1, shortTrajectory.size());
    Assertions.assertEquals("#Some", shortTrajectory.get(0).tag());
    Assertions.assertFalse(shortTrajectory.get(0).isInferred(), "Explicit #S must have isInferred == false");

    // 3. Long form None: #None
    var noneLongInput = """
        {
          :type :Option(:Int32)
          :body #None
        }
        """;
    var noneLongVal = StvnCompiler.compile(noneLongInput).orElseThrow();
    Assertions.assertInstanceOf(StvnOption.class, noneLongVal);
    var noneLongOpt = (StvnOption) noneLongVal;
    var noneLongTrajectory = noneLongOpt.trajectory();
    Assertions.assertEquals(1, noneLongTrajectory.size());
    Assertions.assertEquals("#None", noneLongTrajectory.get(0).tag());
    Assertions.assertFalse(noneLongTrajectory.get(0).isInferred(), "Explicit #None must have isInferred == false");

    // 4. Short form None: #N
    var noneShortInput = """
        {
          :type :Option(:Int32)
          :body #N
        }
        """;
    var noneShortVal = StvnCompiler.compile(noneShortInput).orElseThrow();
    Assertions.assertInstanceOf(StvnOption.class, noneShortVal);
    var noneShortOpt = (StvnOption) noneShortVal;
    var noneShortTrajectory = noneShortOpt.trajectory();
    Assertions.assertEquals(1, noneShortTrajectory.size());
    Assertions.assertEquals("#None", noneShortTrajectory.get(0).tag());
    Assertions.assertFalse(noneShortTrajectory.get(0).isInferred(), "Explicit #N must have isInferred == false");
  }

  @Test
  void testImpliedOptionTagIsInferred() {
    var input = """
        {
          :type :Option(:Int32)
          :body 42
        }
        """;
    var val = StvnCompiler.compile(input).orElseThrow();
    Assertions.assertInstanceOf(StvnOption.class, val);
    var opt = (StvnOption) val;
    var trajectory = opt.trajectory();
    Assertions.assertEquals(1, trajectory.size());
    Assertions.assertEquals("#Some", trajectory.get(0).tag());
    Assertions.assertTrue(trajectory.get(0).isInferred(), "Implied Option literal must have isInferred == true");
  }

  @Test
  void testExplicitEitherLongAndShortTagsNotInferred() {
    // 1. Long form Right: #Right 42
    var rightLongInput = """
        {
          :type :Either(:String :Int32)
          :body #Right 42
        }
        """;
    var rightLongVal = StvnCompiler.compile(rightLongInput).orElseThrow();
    Assertions.assertInstanceOf(StvnEither.class, rightLongVal);
    var rightLongEither = (StvnEither) rightLongVal;
    var rightLongTraj = rightLongEither.trajectory();
    Assertions.assertEquals(1, rightLongTraj.size());
    Assertions.assertEquals("#Right", rightLongTraj.get(0).tag());
    Assertions.assertFalse(rightLongTraj.get(0).isInferred(), "Explicit #Right must have isInferred == false");

    // 2. Short form Right: #R 42
    var rightShortInput = """
        {
          :type :Either(:String :Int32)
          :body #R 42
        }
        """;
    var rightShortVal = StvnCompiler.compile(rightShortInput).orElseThrow();
    Assertions.assertInstanceOf(StvnEither.class, rightShortVal);
    var rightShortEither = (StvnEither) rightShortVal;
    var rightShortTraj = rightShortEither.trajectory();
    Assertions.assertEquals(1, rightShortTraj.size());
    Assertions.assertEquals("#Right", rightShortTraj.get(0).tag());
    Assertions.assertFalse(rightShortTraj.get(0).isInferred(), "Explicit #R must have isInferred == false");

    // 3. Long form Left: #Left "err"
    var leftLongInput = """
        {
          :type :Either(:String :Int32)
          :body #Left "err"
        }
        """;
    var leftLongVal = StvnCompiler.compile(leftLongInput).orElseThrow();
    Assertions.assertInstanceOf(StvnEither.class, leftLongVal);
    var leftLongEither = (StvnEither) leftLongVal;
    var leftLongTraj = leftLongEither.trajectory();
    Assertions.assertEquals(1, leftLongTraj.size());
    Assertions.assertEquals("#Left", leftLongTraj.get(0).tag());
    Assertions.assertFalse(leftLongTraj.get(0).isInferred(), "Explicit #Left must have isInferred == false");

    // 4. Short form Left: #L "err"
    var leftShortInput = """
        {
          :type :Either(:String :Int32)
          :body #L "err"
        }
        """;
    var leftShortVal = StvnCompiler.compile(leftShortInput).orElseThrow();
    Assertions.assertInstanceOf(StvnEither.class, leftShortVal);
    var leftShortEither = (StvnEither) leftShortVal;
    var leftShortTraj = leftShortEither.trajectory();
    Assertions.assertEquals(1, leftShortTraj.size());
    Assertions.assertEquals("#Left", leftShortTraj.get(0).tag());
    Assertions.assertFalse(leftShortTraj.get(0).isInferred(), "Explicit #L must have isInferred == false");
  }

  @Test
  void testImpliedEitherTagIsInferred() {
    var input = """
        {
          :type :Either(:String :Int32)
          :body 42
        }
        """;
    var val = StvnCompiler.compile(input).orElseThrow();
    Assertions.assertInstanceOf(StvnEither.class, val);
    var either = (StvnEither) val;
    var trajectory = either.trajectory();
    Assertions.assertEquals(1, trajectory.size());
    Assertions.assertEquals("#Right", trajectory.get(0).tag());
    Assertions.assertTrue(trajectory.get(0).isInferred(), "Implied Either literal must have isInferred == true");
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

    var compiled = StvnCompiler.compile(fixture).orElseThrow();
    Assertions.assertInstanceOf(StvnSeq.class, compiled);
    var seq = (StvnSeq) compiled;
    List<StvnValue> elements = seq.elements();
    Assertions.assertEquals(5, elements.size(), "Fixture should contain exactly 5 elements");

    // Element 0: #Some #Right #Some #Right 1.234
    // Trajectory: [#Some(false), #Right(false), #Some(false), #Right(false)]
    {
      var el0 = (StvnOption) elements.get(0);
      var e0L1 = (StvnEither) el0.value().orElseThrow();
      var e0L2 = (StvnOption) e0L1.value();
      var e0L3 = (StvnEither) e0L2.value().orElseThrow();
      var e0Val = (StvnFloat) e0L3.value();
      Assertions.assertEquals(new java.math.BigDecimal("1.234"), e0Val.value());

      var traj = e0L3.trajectory();
      Assertions.assertEquals(4, traj.size());
      Assertions.assertEquals(new VariantStep("#Some", false), traj.get(0));
      Assertions.assertEquals(new VariantStep("#Right", false), traj.get(1));
      Assertions.assertEquals(new VariantStep("#Some", false), traj.get(2));
      Assertions.assertEquals(new VariantStep("#Right", false), traj.get(3));
      Assertions.assertFalse(traj.get(0).isInferred());
      Assertions.assertFalse(traj.get(1).isInferred());
      Assertions.assertFalse(traj.get(2).isInferred());
      Assertions.assertFalse(traj.get(3).isInferred());
    }

    // Element 1: #Right #Some #Right 1.234
    // Trajectory: [#Some(true), #Right(false), #Some(false), #Right(false)]
    {
      var el1 = (StvnOption) elements.get(1);
      var e1L1 = (StvnEither) el1.value().orElseThrow();
      var e1L2 = (StvnOption) e1L1.value();
      var e1L3 = (StvnEither) e1L2.value().orElseThrow();
      var e1Val = (StvnFloat) e1L3.value();
      Assertions.assertEquals(new java.math.BigDecimal("1.234"), e1Val.value());

      var traj = e1L3.trajectory();
      Assertions.assertEquals(4, traj.size());
      Assertions.assertEquals(new VariantStep("#Some", true), traj.get(0));
      Assertions.assertEquals(new VariantStep("#Right", false), traj.get(1));
      Assertions.assertEquals(new VariantStep("#Some", false), traj.get(2));
      Assertions.assertEquals(new VariantStep("#Right", false), traj.get(3));
      Assertions.assertTrue(traj.get(0).isInferred());
      Assertions.assertFalse(traj.get(1).isInferred());
      Assertions.assertFalse(traj.get(2).isInferred());
      Assertions.assertFalse(traj.get(3).isInferred());
    }

    // Element 2: #Some #Right 1.234
    // Trajectory: [#Some(false), #Right(false), #Some(true), #Right(true)]
    {
      var el2 = (StvnOption) elements.get(2);
      var e2L1 = (StvnEither) el2.value().orElseThrow();
      var e2L2 = (StvnOption) e2L1.value();
      var e2L3 = (StvnEither) e2L2.value().orElseThrow();
      var e2Val = (StvnFloat) e2L3.value();
      Assertions.assertEquals(new java.math.BigDecimal("1.234"), e2Val.value());

      var traj = e2L3.trajectory();
      Assertions.assertEquals(4, traj.size());
      Assertions.assertEquals(new VariantStep("#Some", false), traj.get(0));
      Assertions.assertEquals(new VariantStep("#Right", false), traj.get(1));
      Assertions.assertEquals(new VariantStep("#Some", true), traj.get(2));
      Assertions.assertEquals(new VariantStep("#Right", true), traj.get(3));
      Assertions.assertFalse(traj.get(0).isInferred());
      Assertions.assertFalse(traj.get(1).isInferred());
      Assertions.assertTrue(traj.get(2).isInferred());
      Assertions.assertTrue(traj.get(3).isInferred());
    }

    // Element 3: #Right 1.234
    // Trajectory: [#Some(true), #Right(false), #Some(true), #Right(true)]
    {
      var el3 = (StvnOption) elements.get(3);
      var e3L1 = (StvnEither) el3.value().orElseThrow();
      var e3L2 = (StvnOption) e3L1.value();
      var e3L3 = (StvnEither) e3L2.value().orElseThrow();
      var e3Val = (StvnFloat) e3L3.value();
      Assertions.assertEquals(new java.math.BigDecimal("1.234"), e3Val.value());

      var traj = e3L3.trajectory();
      Assertions.assertEquals(4, traj.size());
      Assertions.assertEquals(new VariantStep("#Some", true), traj.get(0));
      Assertions.assertEquals(new VariantStep("#Right", false), traj.get(1));
      Assertions.assertEquals(new VariantStep("#Some", true), traj.get(2));
      Assertions.assertEquals(new VariantStep("#Right", true), traj.get(3));
      Assertions.assertTrue(traj.get(0).isInferred());
      Assertions.assertFalse(traj.get(1).isInferred());
      Assertions.assertTrue(traj.get(2).isInferred());
      Assertions.assertTrue(traj.get(3).isInferred());
    }

    // Element 4: 1.234
    // Trajectory: [#Some(true), #Right(true), #Some(true), #Right(true)]
    {
      var el4 = (StvnOption) elements.get(4);
      var e4L1 = (StvnEither) el4.value().orElseThrow();
      var e4L2 = (StvnOption) e4L1.value();
      var e4L3 = (StvnEither) e4L2.value().orElseThrow();
      var e4Val = (StvnFloat) e4L3.value();
      Assertions.assertEquals(new java.math.BigDecimal("1.234"), e4Val.value());

      var traj = e4L3.trajectory();
      Assertions.assertEquals(4, traj.size());
      Assertions.assertEquals(new VariantStep("#Some", true), traj.get(0));
      Assertions.assertEquals(new VariantStep("#Right", true), traj.get(1));
      Assertions.assertEquals(new VariantStep("#Some", true), traj.get(2));
      Assertions.assertEquals(new VariantStep("#Right", true), traj.get(3));
      Assertions.assertTrue(traj.get(0).isInferred());
      Assertions.assertTrue(traj.get(1).isInferred());
      Assertions.assertTrue(traj.get(2).isInferred());
      Assertions.assertTrue(traj.get(3).isInferred());
    }
  }
}
