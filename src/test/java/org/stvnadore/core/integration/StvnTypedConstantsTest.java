package org.stvnadore.core.integration;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.binary.SchemaIdentityStrategy;
import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.binary.StvnBinaryEncoder;
import org.stvnadore.core.ir.StvnValue.*;
import org.stvnadore.core.validation.MalformedPayloadException;
import org.stvnadore.core.validation.MalformedSchemaException;
import org.stvnadore.core.validation.StvnTypeResolver.CircularReferenceException;

import java.util.Optional;

/**
 * Verification test suite for Iteration 1: Typed Constants with Value Sigils ({@code #}).
 */
@NullMarked
public class StvnTypedConstantsTest {

  @Test
  public void testScalarConstants() {
    String payload = """
        {
          :defs {
            #MAX_RETRY :Int8 3
            #APP_NAME :String "STVN Core"
            #PI :Float32 3.14
            #IS_ENABLED :Boolean #TRUE
          }
          :type :Tuple( :Int8 :String :Float32 :Boolean )
          :body ( #MAX_RETRY #APP_NAME #PI #IS_ENABLED )
        }
        """;

    var irOpt = StvnCompiler.compile(payload);
    Assertions.assertTrue(irOpt.isPresent());
    var ir = irOpt.get();
    Assertions.assertInstanceOf(StvnTuple.class, ir);
    var tuple = (StvnTuple) ir;
    Assertions.assertEquals(4, tuple.elements().size());
    Assertions.assertEquals(3L, ((StvnInteger) tuple.elements().get(0)).value().longValue());
    Assertions.assertEquals("STVN Core", ((StvnString) tuple.elements().get(1)).value());
    Assertions.assertEquals(3.14, ((StvnFloat) tuple.elements().get(2)).value().doubleValue(), 0.001);
    Assertions.assertTrue(((StvnBoolean) tuple.elements().get(3)).value());

    // Round-trip binary encoding verification
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
    var encoded = encoder.encode(ir);
    var rootPointer = StvnBinaryDecoder.openStrict(encoded, new SchemaIdentityStrategy.UniversalDefault());
    var decodedIr = StvnBinaryDecoder.unpack(rootPointer, Optional.of(ir.schema()));
    Assertions.assertEquals(ir, decodedIr);
  }

  @Test
  public void testCompoundAndCollectionConstants() {
    String payload = """
        {
          :defs {
            #DEFAULT_COORDINATES :Tuple( :Float32 :Float32 ) ( 12.34 56.78 )
            #ALLOWED_PORTS :Seq( :Int16 ) [ 80 443 8080 ]
          }
          :type :Tuple( :Tuple( :Float32 :Float32 ) :Seq( :Int16 ) )
          :body ( #DEFAULT_COORDINATES #ALLOWED_PORTS )
        }
        """;

    var irOpt = StvnCompiler.compile(payload);
    Assertions.assertTrue(irOpt.isPresent());
    var ir = irOpt.get();
    Assertions.assertInstanceOf(StvnTuple.class, ir);
  }

  @Test
  public void testConstantReferencingAnotherConstant() {
    String payload = """
        {
          :defs {
            #BASE_PORT :Int16 8000
            #SERVICE_PORT :Int16 #BASE_PORT
          }
          :type :Int16
          :body #SERVICE_PORT
        }
        """;

    var irOpt = StvnCompiler.compile(payload);
    Assertions.assertTrue(irOpt.isPresent());
    var ir = irOpt.get();
    Assertions.assertInstanceOf(StvnInteger.class, ir);
    Assertions.assertEquals(8000L, ((StvnInteger) ir).value().longValue());
  }

  @Test
  public void testConstantWithMetadataConstraintsSuccess() {
    String payload = """
        {
          :defs {
            #HTTP_PORT { #minIncl 1024 #maxIncl 65535 } :Int32 8080
          }
          :type :Int32
          :body #HTTP_PORT
        }
        """;

    var irOpt = StvnCompiler.compile(payload);
    Assertions.assertTrue(irOpt.isPresent());
    Assertions.assertEquals(8080L, ((StvnInteger) irOpt.get()).value().longValue());
  }

  @Test
  public void testConstantWithMetadataConstraintsOutOfBoundsFails() {
    String payload = """
        {
          :defs {
            #HTTP_PORT { #minIncl 1024 #maxIncl 65535 } :Int32 80
          }
          :type :Int32
          :body #HTTP_PORT
        }
        """;

    var ex = Assertions.assertThrows(MalformedSchemaException.class, () -> StvnCompiler.compile(payload));
    Assertions.assertTrue(ex.getMessage().contains("Constraint violation (#HTTP_PORT)"));
  }

  @Test
  public void testConstantWithRegexConstraintFails() {
    String payload = """
        {
          :defs {
            #SEMVER { #regex "^\\\\d+\\\\.\\\\d+\\\\.\\\\d+$" } :String "v1.0-beta"
          }
          :type :String
          :body #SEMVER
        }
        """;

    var ex = Assertions.assertThrows(MalformedSchemaException.class, () -> StvnCompiler.compile(payload));
    Assertions.assertTrue(ex.getMessage().contains("Constraint violation (#SEMVER)"));
  }

  @Test
  public void testCircularConstantDependencyDetected() {
    String payload = """
        {
          :defs {
            #CONST_A :Int32 #CONST_B
            #CONST_B :Int32 #CONST_A
          }
          :type :Int32
          :body #CONST_A
        }
        """;

    var ex = Assertions.assertThrows(CircularReferenceException.class, () -> StvnCompiler.compile(payload));
    Assertions.assertTrue(ex.getMessage().contains("Circular constant definition detected"));
  }

  @Test
  public void testDuplicateConstantDefinitionFailsZeroShadowing() {
    String payload = """
        {
          :defs {
            #MAX_LIMIT :Int32 100
            #MAX_LIMIT :Int32 200
          }
          :type :Int32
          :body #MAX_LIMIT
        }
        """;

    var ex = Assertions.assertThrows(IllegalStateException.class, () -> StvnCompiler.compile(payload));
    Assertions.assertTrue(ex.getMessage().contains("Zero-Shadowing constraint violated: #MAX_LIMIT"));
  }

  @Test
  public void testBareTypeKeywordInPayloadPositionFailsParsing() {
    String payload = """
        {
          :defs {
            :MyType :Int32
          }
          :type :Int32
          :body :MyType
        }
        """;

    var ex = Assertions.assertThrows(RuntimeException.class, () -> StvnCompiler.compile(payload));
    Assertions.assertTrue(ex.getMessage().contains("STVN Syntax Error") || ex.getMessage().contains("mismatched input"));
  }

  @Test
  public void testLegacyConstantWithColonPrefixFailsParsing() {
    String payload = """
        {
          :defs {
            :MAX_RETRY :Int8 3
          }
          :type :Int8
          :body :MAX_RETRY
        }
        """;

    var ex = Assertions.assertThrows(RuntimeException.class, () -> StvnCompiler.compile(payload));
    Assertions.assertTrue(ex.getMessage().contains("STVN Syntax Error") || ex.getMessage().contains("mismatched input"));
  }

  @Test
  public void testUndeclaredValueKeywordInPayloadPositionFails() {
    String payload = """
        {
          :type :Int32
          :body #UNDECLARED_CONST
        }
        """;

    var ex = Assertions.assertThrows(MalformedPayloadException.class, () -> StvnCompiler.compile(payload));
    Assertions.assertTrue(ex.getMessage().contains("Undeclared value keyword or constant: '#UNDECLARED_CONST'"));
  }
}
