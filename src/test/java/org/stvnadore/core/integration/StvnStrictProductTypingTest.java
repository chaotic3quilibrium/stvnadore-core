package org.stvnadore.core.integration;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ir.StvnValue.*;
import org.stvnadore.core.validation.MalformedPayloadException;

import java.math.BigInteger;

class StvnStrictProductTypingTest {

  @Nested
  @DisplayName("Positive Tests: Strict Product & Sum Typing")
  class PositiveTests {

    @Test
    @DisplayName("Option: Bare scalar value matches :Option( :Uint32 )")
    void testScalarOptionSomeBareValue() {
      var input = """
          {
            :type :Option( :Uint32 )
            :body #Some 42
          }
          """;
      var val = StvnCompiler.compile(input).orElseThrow();
      Assertions.assertInstanceOf(StvnOption.class, val);
      var opt = (StvnOption) val;
      Assertions.assertTrue(opt.value().isPresent());
      Assertions.assertInstanceOf(StvnInteger.class, opt.value().get());
      Assertions.assertEquals(BigInteger.valueOf(42), ((StvnInteger) opt.value().get()).value());
    }

    @Test
    @DisplayName("Option: 1-element tuple matches :Option( :Tuple( :Uint32 ) )")
    void test1TupleOptionSomeProduct() {
      var input = """
          {
            :type :Option( :Tuple( :Uint32 ) )
            :body #Some ( 42 )
          }
          """;
      var val = StvnCompiler.compile(input).orElseThrow();
      Assertions.assertInstanceOf(StvnOption.class, val);
      var opt = (StvnOption) val;
      Assertions.assertTrue(opt.value().isPresent());
      Assertions.assertInstanceOf(StvnTuple.class, opt.value().get());
      var tuple = (StvnTuple) opt.value().get();
      Assertions.assertEquals(1, tuple.elements().size());
      Assertions.assertEquals(BigInteger.valueOf(42), ((StvnInteger) tuple.elements().get(0)).value());
    }

    @Test
    @DisplayName("Option: Multi-element tuple matches :Option( :Tuple( :Uint32 :Uint32 ) )")
    void testMultiTupleOptionSomeProduct() {
      var input = """
          {
            :type :Option( :Tuple( :Uint32 :Uint32 ) )
            :body #Some ( 10 20 )
          }
          """;
      var val = StvnCompiler.compile(input).orElseThrow();
      Assertions.assertInstanceOf(StvnOption.class, val);
      var opt = (StvnOption) val;
      Assertions.assertTrue(opt.value().isPresent());
      Assertions.assertInstanceOf(StvnTuple.class, opt.value().get());
      var tuple = (StvnTuple) opt.value().get();
      Assertions.assertEquals(2, tuple.elements().size());
      Assertions.assertEquals(BigInteger.valueOf(10), ((StvnInteger) tuple.elements().get(0)).value());
      Assertions.assertEquals(BigInteger.valueOf(20), ((StvnInteger) tuple.elements().get(1)).value());
    }

    @Test
    @DisplayName("Either: Bare scalar matches :Either( :Int32 :String )")
    void testScalarEitherRightBareValue() {
      var input = """
          {
            :type :Either( :Int32 :String )
            :body #Right "OK"
          }
          """;
      var val = StvnCompiler.compile(input).orElseThrow();
      Assertions.assertInstanceOf(StvnEither.class, val);
      var either = (StvnEither) val;
      Assertions.assertTrue(either.isRight());
      Assertions.assertInstanceOf(StvnString.class, either.value());
      Assertions.assertEquals("OK", ((StvnString) either.value()).value());
    }

    @Test
    @DisplayName("Either: 1-element tuple matches :Either( :Int32 :Tuple( :String ) )")
    void testProductEitherRightTuple() {
      var input = """
          {
            :type :Either( :Int32 :Tuple( :String ) )
            :body #Right ( "OK" )
          }
          """;
      var val = StvnCompiler.compile(input).orElseThrow();
      Assertions.assertInstanceOf(StvnEither.class, val);
      var either = (StvnEither) val;
      Assertions.assertTrue(either.isRight());
      Assertions.assertInstanceOf(StvnTuple.class, either.value());
      var tuple = (StvnTuple) either.value();
      Assertions.assertEquals(1, tuple.elements().size());
      Assertions.assertEquals("OK", ((StvnString) tuple.elements().get(0)).value());
    }

    @Test
    @DisplayName("Union: Bare scalar matches :Union( :Int32 :String )")
    void testScalarUnionBareValue() {
      var input = """
          {
            :type :Union( :Int32 :String )
            :body #1 1024
          }
          """;
      var val = StvnCompiler.compile(input).orElseThrow();
      Assertions.assertInstanceOf(StvnUnion.class, val);
      var union = (StvnUnion) val;
      Assertions.assertEquals(0, union.tagIndex());
      Assertions.assertInstanceOf(StvnInteger.class, union.value());
      Assertions.assertEquals(BigInteger.valueOf(1024), ((StvnInteger) union.value()).value());
    }
  }

  @Nested
  @DisplayName("Negative Tests: Rejecting Function-Call Coercion")
  class NegativeTests {

    @Test
    @DisplayName("Reject: #Some ( 42 ) targeting scalar :Option( :Uint32 )")
    void testScalarOptionRejectsParenthesizedTuple() {
      var input = """
          {
            :type :Option( :Uint32 )
            :body #Some ( 42 )
          }
          """;
      var ex = Assertions.assertThrows(MalformedPayloadException.class, () -> StvnCompiler.compile(input));
      Assertions.assertTrue(ex.getMessage().contains("Type mismatch") || ex.getMessage().contains("Tuple"));
    }

    @Test
    @DisplayName("Reject: #Right ( \"OK\" ) targeting scalar :Either( :Int32 :String )")
    void testScalarEitherRejectsParenthesizedTuple() {
      var input = """
          {
            :type :Either( :Int32 :String )
            :body #Right ( "OK" )
          }
          """;
      var ex = Assertions.assertThrows(MalformedPayloadException.class, () -> StvnCompiler.compile(input));
      Assertions.assertTrue(ex.getMessage().contains("Type mismatch") || ex.getMessage().contains("Tuple"));
    }

    @Test
    @DisplayName("Reject: #1 ( 1024 ) targeting scalar :Union( :Int32 :String )")
    void testScalarUnionRejectsParenthesizedTuple() {
      var input = """
          {
            :type :Union( :Int32 :String )
            :body #1 ( 1024 )
          }
          """;
      var ex = Assertions.assertThrows(MalformedPayloadException.class, () -> StvnCompiler.compile(input));
      Assertions.assertTrue(ex.getMessage().contains("Type mismatch") || ex.getMessage().contains("Tuple"));
    }

    @Test
    @DisplayName("Reject: Bare scalar 42 targeting product :Tuple( :Uint32 )")
    void testBareScalarRejectsProductSchema() {
      var input = """
          {
            :type :Tuple( :Uint32 )
            :body 42
          }
          """;
      var ex = Assertions.assertThrows(RuntimeException.class, () -> StvnCompiler.compile(input));
      Assertions.assertTrue(ex.getMessage().contains("Type mismatch"));
    }

    @Test
    @DisplayName("Reject: Short variant #S ( \"test\" ) targeting scalar :Option( :String )")
    void testShortVariantRejectsParenthesizedTuple() {
      var input = """
          {
            :type :Option( :String )
            :body #S ( "test" )
          }
          """;
      var ex = Assertions.assertThrows(MalformedPayloadException.class, () -> StvnCompiler.compile(input));
      Assertions.assertTrue(ex.getMessage().contains("Type mismatch") || ex.getMessage().contains("Tuple"));
    }
  }
}
