package org.stvnadore.core.validation;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.ir.StvnValue.StvnUnion;
import org.stvnadore.core.parser.StvnLexer;
import org.stvnadore.core.parser.StvnParser;

import java.util.Optional;

class StvnUnionValidationTest {

  @Test
  void testExplicitUnionTagParsing() {
    String input = """
        {
          :defs {
            :MyUnion :Union(:Int32 :String)
          }
          :type :MyUnion
          :body #1 42
        }
        """;
    var valueOpt = StvnCompiler.compile(input);
    Assertions.assertTrue(valueOpt.isPresent());
    StvnValue val = valueOpt.get();
    Assertions.assertTrue(val instanceof StvnUnion);
    StvnUnion unionVal = (StvnUnion) val;
    Assertions.assertEquals(0, unionVal.tagIndex());
  }

  @Test
  void testImplicitUnionDisjointParsing() {
    String input = """
        {
          :defs {
            :MyUnion :Union(:Int32 :String)
          }
          :type :MyUnion
          :body "hello"
        }
        """;
    var valueOpt = StvnCompiler.compile(input);
    Assertions.assertTrue(valueOpt.isPresent());
    StvnValue val = valueOpt.get();
    Assertions.assertTrue(val instanceof StvnUnion);
    StvnUnion unionVal = (StvnUnion) val;
    Assertions.assertEquals(1, unionVal.tagIndex());
  }

  @Test
  void testOutOfBoundsUnionTagThrowsStvnMalformedLiteralException() {
    String input = """
        {
          :defs {
            :MyUnion :Union(:Int32 :String)
          }
          :type :MyUnion
          :body #3 42
        }
        """;
    var exception = Assertions.assertThrows(StvnMalformedLiteralException.class, () -> {
      StvnCompiler.compile(input);
    });
    Assertions.assertTrue(exception.getMessage().contains("overflows union schema constraints. Maximum branch capacity is 2"));
  }

  @Test
  void testOverlappingUnionImplicitTagThrowsStvnCollectionCollisionException() {
    String input = """
        {
          :defs {
            :MyUnion :Union(:Int32 :Int)
          }
          :type :MyUnion
          :body 42
        }
        """;
    var exception = Assertions.assertThrows(StvnCollectionCollisionException.class, () -> {
      StvnCompiler.compile(input);
    });
    Assertions.assertTrue(exception.getMessage().contains("Ambiguous implicit resolution: Value matches multiple branches"));
  }

  @Test
  void testDuplicateNominalBranchThrowsMalformedSchemaException() {
    String input = """
        {
          :defs {
            :MyInt { #minIncl 1 } :Int32
            :MyUnion :Union(:MyInt :MyInt)
          }
          :type :MyUnion
          :body 42
        }
        """;
    var exception = Assertions.assertThrows(MalformedSchemaException.class, () -> {
      StvnCompiler.compile(input);
    });
    Assertions.assertTrue(exception.getMessage().contains("Two member branches within a single sum type share identical nominal type identities"));
  }

  @Test
  void testEitherDuplicateNominalBranchThrowsMalformedSchemaException() {
    String input = """
        {
          :defs {
            :MyInt { #minIncl 1 } :Int32
            :MyEither :Either(:MyInt :MyInt)
          }
          :type :MyEither
          :body #Left 42
        }
        """;
    var exception = Assertions.assertThrows(MalformedSchemaException.class, () -> {
      StvnCompiler.compile(input);
    });
    Assertions.assertTrue(exception.getMessage().contains("Two member branches within a single sum type share identical nominal type identities"));
  }

  @Test
  void testUnionTraitDerivationBubblingUnannotated() {
    String input = """
        {
          :defs {
            :MyUnion :Union(:Int32 :String)
          }
          :type :MyUnion
          :body #1 42
        }
        """;
    var lexer = new StvnLexer(CharStreams.fromString(input));
    lexer.removeErrorListeners();
    var parser = new StvnParser(new CommonTokenStream(lexer));
    var doc = parser.stvnDocument();

    var resolvedSchema = StvnTypeResolver.resolvePrimitiveSchema(doc, doc.documentBody().typeEntry().schemaType(), new java.util.HashSet<>()).orElseThrow();
    // Int32 and String are both equatable and comparable, so the union should also be
    Assertions.assertEquals(Optional.of(true), resolvedSchema.constraints().equatable());
    Assertions.assertEquals(Optional.of(true), resolvedSchema.constraints().comparable());
  }

  @Test
  void testUnionTraitDerivationBubblingNonEquatableBranch() {
    // Float32 is non-equatable by default
    String input = """
        {
          :defs {
            :MyUnion :Union(:Float32 :String)
          }
          :type :MyUnion
          :body #2 "test"
        }
        """;
    var lexer = new StvnLexer(CharStreams.fromString(input));
    lexer.removeErrorListeners();
    var parser = new StvnParser(new CommonTokenStream(lexer));
    var doc = parser.stvnDocument();

    var resolvedSchema = StvnTypeResolver.resolvePrimitiveSchema(doc, doc.documentBody().typeEntry().schemaType(), new java.util.HashSet<>()).orElseThrow();
    // One branch is non-equatable, so logical AND results in false
    Assertions.assertEquals(Optional.of(false), resolvedSchema.constraints().equatable());
    Assertions.assertEquals(Optional.of(true), resolvedSchema.constraints().comparable());
  }

  @Test
  void testUnionTraitDerivationHaltedByExplicitOverride() {
    // Explicitly annotated as non-equatable and non-comparable
    String input = """
        {
          :defs {
            :MyUnion { #equatable #FALSE #comparable #FALSE } :Union(:Int32 :String)
          }
          :type :MyUnion
          :body #1 42
        }
        """;
    var lexer = new StvnLexer(CharStreams.fromString(input));
    lexer.removeErrorListeners();
    var parser = new StvnParser(new CommonTokenStream(lexer));
    var doc = parser.stvnDocument();

    var resolvedSchema = StvnTypeResolver.resolvePrimitiveSchema(doc, doc.documentBody().typeEntry().schemaType(), new java.util.HashSet<>()).orElseThrow();
    Assertions.assertEquals(Optional.of(false), resolvedSchema.constraints().equatable());
    Assertions.assertEquals(Optional.of(false), resolvedSchema.constraints().comparable());
  }
}
