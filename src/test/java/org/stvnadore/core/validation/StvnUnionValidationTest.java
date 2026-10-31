package org.stvnadore.core.validation;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.ir.StvnValue.EitherBranch;
import org.stvnadore.core.ir.StvnValue.StvnUnion;
import org.stvnadore.core.parser.StvnLexer;
import org.stvnadore.core.parser.StvnParser;

import java.math.BigInteger;
import java.util.Optional;

class StvnUnionValidationTest {

  @Test
  void testExplicitUnionTagParsing() {
    String input = """
        {
          :defs {
            :MyUnion :Union(:Int :String)
          }
          :type :MyUnion
          :body #1 42
        }
        """;
    var valueOpt = StvnCompiler.compilePayload(input);
    Assertions.assertTrue(valueOpt.isPresent());
    StvnValue val = valueOpt.get();
    Assertions.assertTrue(val instanceof StvnUnion);
    StvnUnion unionVal = (StvnUnion) val;
    Assertions.assertEquals(BigInteger.ONE, unionVal.oneBasedBranchIndex());
  }

  @Test
  void testImplicitUnionDisjointParsing() {
    String input = """
        {
          :defs {
            :MyUnion :Union(:Int :String)
          }
          :type :MyUnion
          :body "hello"
        }
        """;
    var valueOpt = StvnCompiler.compilePayload(input);
    Assertions.assertTrue(valueOpt.isPresent());
    StvnValue val = valueOpt.get();
    Assertions.assertTrue(val instanceof StvnUnion);
    StvnUnion unionVal = (StvnUnion) val;
    Assertions.assertEquals(BigInteger.TWO, unionVal.oneBasedBranchIndex());
  }

  @Test
  void testOutOfBoundsUnionTagThrowsStvnMalformedLiteralException() {
    String input = """
        {
          :defs {
            :MyUnion :Union(:Int :String)
          }
          :type :MyUnion
          :body #3 42
        }
        """;
    var exception = Assertions.assertThrows(StvnMalformedLiteralException.class, () -> {
      StvnCompiler.compilePayload(input);
    });
    Assertions.assertTrue(exception.getMessage().contains("Union variant tag '#3' exceeds branch count (2)"));
    int tagStart = input.indexOf("#3");
    Assertions.assertEquals(tagStart, exception.startOffset(), "startOffset must match '#3'");
    Assertions.assertEquals(tagStart + 2, exception.endOffset(), "endOffset must match end of '#3'");
  }

  @Test
  void testOverlappingUnionImplicitTagThrowsStvnCollectionCollisionException() {
    String input = """
        {
          :defs {
            :MyUnion :Union(:Int :Int)
          }
          :type :MyUnion
          :body 42
        }
        """;
    var exception = Assertions.assertThrows(StvnCollectionCollisionException.class, () -> {
      StvnCompiler.compilePayload(input);
    });
    Assertions.assertTrue(exception.getMessage().contains("Ambiguous implicit resolution: Value matches multiple branches"));
  }

  @Test
  void testDuplicateNominalBranchThrowsMalformedSchemaException() {
    String input = """
        {
          :defs {
            :MyInt { #minIncl 1 } :Int
            :MyUnion :Union(:MyInt :MyInt)
          }
          :type :MyUnion
          :body 42
        }
        """;
    var exception = Assertions.assertThrows(StvnCollectionCollisionException.class, () -> {
      StvnCompiler.compilePayload(input);
    });
    Assertions.assertTrue(exception.getMessage().contains("Ambiguous implicit resolution: Value matches multiple branches"));
  }

  @Test
  void testEitherDuplicateNominalBranchExplicitTagSucceeds() {
    String input = """
        {
          :defs {
            :MyInt { #minIncl 1 } :Int
            :MyEither :Either(:MyInt :MyInt)
          }
          :type :MyEither
          :body #Left 42
        }
        """;
    var valOpt = StvnCompiler.compilePayload(input);
    Assertions.assertTrue(valOpt.isPresent());
    Assertions.assertInstanceOf(StvnValue.StvnEither.class, valOpt.get());
    var either = (StvnValue.StvnEither) valOpt.get();
    Assertions.assertEquals(EitherBranch.LEFT, either.branch());
  }

  @Test
  void testUnionTraitDerivationBubblingUnannotated() {
    String input = """
        {
          :defs {
            :MyUnion :Union(:Int :String)
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
    Assertions.assertEquals(Optional.of(true), resolvedSchema.constraints().equatable());
    Assertions.assertEquals(Optional.of(true), resolvedSchema.constraints().comparable());
  }

  @Test
  void testUnionTraitDerivationBubblingNonEquatableBranch() {
    String input = """
        {
          :defs {
            :MyUnion :Union(:Float :String)
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
    Assertions.assertEquals(Optional.of(false), resolvedSchema.constraints().equatable());
    Assertions.assertEquals(Optional.of(true), resolvedSchema.constraints().comparable());
  }

  @Test
  void testUnionTraitDerivationHaltedByExplicitOverride() {
    String input = """
        {
          :defs {
            :MyUnion { #equatable #FALSE #comparable #FALSE } :Union(:Int :String)
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
