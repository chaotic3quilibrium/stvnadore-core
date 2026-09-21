package org.stvnadore.core.validation;

import java.util.Optional;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.parser.StvnLexer;
import org.stvnadore.core.parser.StvnParser;
import org.stvnadore.core.ir.StvnIrVisitor;
import org.stvnadore.core.ir.StvnValue.StvnOption;
import org.stvnadore.core.ir.StvnValue.StvnEither;
import org.stvnadore.core.ir.StvnValue.StvnInteger;
import org.stvnadore.core.ir.VariantStep;

class StvnTypeResolverRegressionTest {

  @Test
  void testNominalAliasPrefixCollision_Floaty() {
    var lexer = new StvnLexer(CharStreams.fromString(":Floaty"));
    lexer.removeErrorListeners();
    var parser = new StvnParser(new CommonTokenStream(lexer));
    parser.removeErrorListeners();
    var schemaCtx = parser.schemaType();

    var rs = new StvnTypeResolver.ResolvedSchema(schemaCtx, StvnTypeResolver.StvnConstraints.empty(), Optional.of("Floaty"));
    var updated = StvnTypeResolver.applyDefaults(rs);

    Assertions.assertNotEquals(Optional.of(false), updated.constraints().equatable());
  }

  @Test
  void testNominalAliasPrefixCollision_Setting() {
    var lexer = new StvnLexer(CharStreams.fromString(":Setting"));
    lexer.removeErrorListeners();
    var parser = new StvnParser(new CommonTokenStream(lexer));
    parser.removeErrorListeners();
    var schemaCtx = parser.schemaType();

    var rs = new StvnTypeResolver.ResolvedSchema(schemaCtx, StvnTypeResolver.StvnConstraints.empty(), Optional.of("Setting"));
    var updated = StvnTypeResolver.deriveAndApplyTraits(rs, java.util.List.of());

    Assertions.assertNotEquals(Optional.of(false), updated.constraints().comparable());
  }

  @Test
  void testBrittleStringSlicing_FalseFencedString() {
    var input = """
        {
          :defs {
            :MyString { #regex \"\"\"->hello
        my [content]
        \"\"\" } :String
          }
          :type :MyString
          :body "value"
        }
        """;

    var lexer = new StvnLexer(CharStreams.fromString(input));
    lexer.removeErrorListeners();
    var parser = new StvnParser(new CommonTokenStream(lexer));
    parser.removeErrorListeners();
    var doc = parser.stvnDocument();

    var defOpt = StvnTypeResolver.findTypeDefinition(doc, ":MyString");
    Assertions.assertTrue(defOpt.isPresent());

    var constraints = StvnTypeResolver.extractConstraints(defOpt.get().metadataMap());
    var regex = constraints.regex();

    Assertions.assertEquals("my [content]\n", regex.orElse(null));
  }

  @Test
  void testMalformedListThrowsMalformedAstContextException() {
    var input = """
        {
          :type :Seq(:Option(:Tuple(:Int)))
          :body [ #Some ( ) ]
        }
        """;
    var doc = org.stvnadore.core.StvnCompiler.parse(input, org.stvnadore.core.StvnParserConfig.DEFAULT);

    var bodyEntry = doc.documentBody().bodyEntry();
    Assertions.assertThrows(MalformedAstContextException.class, () -> {
      StvnIrVisitor.build(bodyEntry, doc);
    });
  }

  @Test
  void testMalformedMapThrowsMalformedAstContextException() {
    var input = """
        {
          :type :Map(:String :Int)
          :body { [ "key" ] }
        }
        """;
    var doc = org.stvnadore.core.StvnCompiler.parse(input, org.stvnadore.core.StvnParserConfig.DEFAULT);

    var bodyEntry = doc.documentBody().bodyEntry();
    Assertions.assertThrows(MalformedAstContextException.class, () -> {
      StvnIrVisitor.build(bodyEntry, doc);
    });
  }

  @Test
  void testMalformedTupleThrowsMalformedAstContextException() {
    var input = """
        {
          :type :Tuple(:Option(:Tuple(:Int)))
          :body ( #Some ( ) )
        }
        """;
    var doc = org.stvnadore.core.StvnCompiler.parse(input, org.stvnadore.core.StvnParserConfig.DEFAULT);

    var bodyEntry = doc.documentBody().bodyEntry();
    Assertions.assertThrows(MalformedAstContextException.class, () -> {
      StvnIrVisitor.build(bodyEntry, doc);
    });
  }

  @Test
  void testEmptySeqSchemaThrowsMalformedSchemaException() {
    var input = """
        {
          :defs {
            :MySeq :Seq()
          }
          :type :MySeq
          :body [1]
        }
        """;
    var doc = org.stvnadore.core.StvnCompiler.parse(input, org.stvnadore.core.StvnParserConfig.DEFAULT);

    Assertions.assertThrows(MalformedSchemaException.class, () -> {
      StvnTypeResolver.validateDocumentConstraints(doc);
    });
  }

  private StvnParser.StvnDocumentContext parseDocument(String input) {
    return org.stvnadore.core.StvnCompiler.parse(input, org.stvnadore.core.StvnParserConfig.STRICT);
  }

  @Test
  void testDuplicateKeysMap() {
    var input = """
        {
          :type :Map(:String :Int)
          :body { ["a" 1] ["a" 2] }
        }
        """;
    var doc = parseDocument(input);
    var bodyEntry = doc.documentBody().bodyEntry();
    var ex = Assertions.assertThrows(StvnCollectionCollisionException.class, () -> StvnIrVisitor.build(bodyEntry, doc));
    Assertions.assertEquals("Duplicate map key detected", ex.getMessage());
    int expectedStart = input.lastIndexOf("\"a\"");
    Assertions.assertEquals(expectedStart, ex.startOffset());
    Assertions.assertEquals(expectedStart + "\"a\"".length(), ex.endOffset());
  }

  @Test
  void testDuplicateKeysMapNonEmpty() {
    var input = """
        {
          :defs {
            :MapNE { #minSize 1 } :Map(:String :Int)
          }
          :type :MapNE
          :body { ["a" 1] ["a" 2] }
        }
        """;
    var doc = parseDocument(input);
    var bodyEntry = doc.documentBody().bodyEntry();
    var ex = Assertions.assertThrows(StvnCollectionCollisionException.class, () -> StvnIrVisitor.build(bodyEntry, doc));
    Assertions.assertEquals("Duplicate map key detected", ex.getMessage());
    int expectedStart = input.lastIndexOf("\"a\"");
    Assertions.assertEquals(expectedStart, ex.startOffset());
    Assertions.assertEquals(expectedStart + "\"a\"".length(), ex.endOffset());
  }

  @Test
  void testDuplicateKeysMapInv() {
    var input = """
        {
          :defs {
            :MapInv { #invertible } :Map(:String :Int)
          }
          :type :MapInv
          :body { ["a" 1] ["a" 2] }
        }
        """;
    var doc = parseDocument(input);
    var bodyEntry = doc.documentBody().bodyEntry();
    var ex = Assertions.assertThrows(StvnCollectionCollisionException.class, () -> StvnIrVisitor.build(bodyEntry, doc));
    Assertions.assertEquals("Duplicate map key detected", ex.getMessage());
    int expectedStart = input.lastIndexOf("\"a\"");
    Assertions.assertEquals(expectedStart, ex.startOffset());
    Assertions.assertEquals(expectedStart + "\"a\"".length(), ex.endOffset());
  }

  @Test
  void testDuplicateKeysMapInvNonEmpty() {
    var input = """
        {
          :defs {
            :MapInvNE { #invertible #minSize 1 } :Map(:String :Int)
          }
          :type :MapInvNE
          :body { ["a" 1] ["a" 2] }
        }
        """;
    var doc = parseDocument(input);
    var bodyEntry = doc.documentBody().bodyEntry();
    var ex = Assertions.assertThrows(StvnCollectionCollisionException.class, () -> StvnIrVisitor.build(bodyEntry, doc));
    Assertions.assertEquals("Duplicate map key detected", ex.getMessage());
    int expectedStart = input.lastIndexOf("\"a\"");
    Assertions.assertEquals(expectedStart, ex.startOffset());
    Assertions.assertEquals(expectedStart + "\"a\"".length(), ex.endOffset());
  }

  @Test
  void testDuplicateValuesMapInv() {
    var input = """
        {
          :defs {
            :StringFixed3 { #minSize 3 #maxSize 3 } :String
            :Int8 { #size 8 } :Int
            :MapInv { #invertible } :Map(:StringFixed3 :Int8)
          }
          :type :MapInv
          :body {
            ["ABC" 1]
            ["EFG" 2]
            ["HIJ" 1]
          }
        }
        """;
    var doc = parseDocument(input);
    var bodyEntry = doc.documentBody().bodyEntry();
    var ex = Assertions.assertThrows(StvnCollectionCollisionException.class, () -> StvnIrVisitor.build(bodyEntry, doc));
    Assertions.assertEquals("Duplicate inverted map value detected", ex.getMessage());
    int expectedStart = input.lastIndexOf(" 1]") + 1;
    Assertions.assertEquals(expectedStart, ex.startOffset());
    Assertions.assertEquals(expectedStart + 1, ex.endOffset());
  }

  @Test
  void testDuplicateValuesMapInvNonEmpty() {
    var input = """
        {
          :defs {
            :StringFixed3 { #minSize 3 #maxSize 3 } :String
            :Int8 { #size 8 } :Int
            :MapInvNE { #invertible #minSize 1 } :Map(:StringFixed3 :Int8)
          }
          :type :MapInvNE
          :body {
            ["ABC" 1]
            ["EFG" 2]
            ["HIJ" 1]
          }
        }
        """;
    var doc = parseDocument(input);
    var bodyEntry = doc.documentBody().bodyEntry();
    var ex = Assertions.assertThrows(StvnCollectionCollisionException.class, () -> StvnIrVisitor.build(bodyEntry, doc));
    Assertions.assertEquals("Duplicate inverted map value detected", ex.getMessage());
    int expectedStart = input.lastIndexOf(" 1]") + 1;
    Assertions.assertEquals(expectedStart, ex.startOffset());
    Assertions.assertEquals(expectedStart + 1, ex.endOffset());
  }

  @Test
  void testMapKeyNonEquatableThrows() {
    var input = """
        {
          :defs {
            :Float32 { #size 32 } :Float
            :MyMap :Map(:Float32 :Int)
          }
          :type :MyMap
          :body { [1.0 1] }
        }
        """;
    var doc = parseDocument(input);
    var ex = Assertions.assertThrows(MalformedSchemaException.class, () -> StvnTypeResolver.validateDocumentConstraints(doc));
    Assertions.assertEquals("Map keys require types to be #equatable #TRUE", ex.getMessage());
  }

  @Test
  void testInvertedMapValueNonEquatableThrows() {
    var input = """
        {
          :defs {
            :Float32 { #size 32 } :Float
            :MyMapInv { #invertible } :Map(:Int :Float32)
          }
          :type :MyMapInv
          :body { [1 1.0] }
        }
        """;
    var doc = parseDocument(input);
    var ex = Assertions.assertThrows(MalformedSchemaException.class, () -> StvnTypeResolver.validateDocumentConstraints(doc));
    Assertions.assertEquals("Inverted map values require types to be #equatable #TRUE", ex.getMessage());
  }

  @Test
  void testSetElementNonEquatableThrows() {
    var input = """
        {
          :defs {
            :Float32 { #size 32 } :Float
            :MySet :Set(:Float32)
          }
          :type :MySet
          :body [ 1.0 ]
        }
        """;
    var doc = parseDocument(input);
    var ex = Assertions.assertThrows(MalformedSchemaException.class, () -> StvnTypeResolver.validateDocumentConstraints(doc));
    Assertions.assertEquals("Set elements require types to be #equatable #TRUE", ex.getMessage());
  }

  @Test
  void testNestedExplicitOptionAlignment() {
    var input = """
        {
          :type :Option(:Option(:Int))
          :body #Some #Some 42
        }
        """;
    var doc = parseDocument(input);
    var bodyEntry = doc.documentBody().bodyEntry();
    var val = StvnIrVisitor.build(bodyEntry, doc).orElseThrow();
    Assertions.assertTrue(val instanceof StvnOption);
    var outerOpt = (StvnOption) val;
    Assertions.assertTrue(outerOpt.value().isPresent());
    var innerOptVal = outerOpt.value().get();
    Assertions.assertTrue(innerOptVal instanceof StvnOption);
    var innerOpt = (StvnOption) innerOptVal;
    Assertions.assertTrue(innerOpt.value().isPresent());
    var innerVal = innerOpt.value().get();
    Assertions.assertTrue(innerVal instanceof StvnInteger);
    Assertions.assertEquals(java.math.BigInteger.valueOf(42), ((StvnInteger) innerVal).value());
  }

  @Test
  void testDeeplyNestedImplicitInference() {
    var input = """
        {
          :type :Option(:Option(:Either(:Int :String)))
          :body #Left 42
        }
        """;
    var doc = parseDocument(input);
    var bodyEntry = doc.documentBody().bodyEntry();
    var val = StvnIrVisitor.build(bodyEntry, doc).orElseThrow();
    Assertions.assertTrue(val instanceof StvnOption);
    var outerOpt = (StvnOption) val;
    Assertions.assertTrue(outerOpt.value().isPresent());
    var innerOptVal = outerOpt.value().get();
    Assertions.assertTrue(innerOptVal instanceof StvnOption);
    var innerOpt = (StvnOption) innerOptVal;
    Assertions.assertTrue(innerOpt.value().isPresent());
    var innerVal = innerOpt.value().get();
    Assertions.assertTrue(innerVal instanceof StvnEither);
    var either = (StvnEither) innerVal;
    Assertions.assertFalse(either.isRight());
    Assertions.assertTrue(either.value() instanceof StvnInteger);
    Assertions.assertEquals(java.math.BigInteger.valueOf(42), ((StvnInteger) either.value()).value());
  }

  @Test
  void testAmbiguousInference_InferredOuterLeft_Fails() {
    var input = """
        {
          :type :Seq(:Option(:Either(:String :Option(:Either(:String :Float)))))
          :body [ "Inferred Outer Left" ]
        }
        """;
    var doc = parseDocument(input);
    var bodyEntry = doc.documentBody().bodyEntry();
    Assertions.assertThrows(MalformedPayloadException.class, () -> {
      StvnIrVisitor.build(bodyEntry, doc);
    });
  }

  @Test
  void testRuleE_UntaggedInnerLeft_Fails() {
    var input = """
        {
          :type :Seq(:Option(:Either(:String :Option(:Either(:String :Float)))))
          :body [ #Right "Inferred Inner Left" ]
        }
        """;
    var doc = parseDocument(input);
    var bodyEntry = doc.documentBody().bodyEntry();
    Assertions.assertThrows(MalformedPayloadException.class, () -> {
      StvnIrVisitor.build(bodyEntry, doc);
    });
  }

  @Test
  void testRuleE_ExplicitInnerLeft_Succeeds() {
    var input = """
        {
          :type :Seq(:Option(:Either(:String :Option(:Either(:String :Float)))))
          :body [ #Right #Left "Inferred Inner Left" ]
        }
        """;
    var doc = parseDocument(input);
    var bodyEntry = doc.documentBody().bodyEntry();
    var val = StvnIrVisitor.build(bodyEntry, doc);
    Assertions.assertNotNull(val);
  }

  @Test
  void testAmbiguousEitherInference_OverlappingBranches_Fails() {
    var input = """
        {
          :defs {
            :Int32 { #size 32 } :Int
            :Uint32 { #unsigned #size 32 } :Int
          }
          :type :Seq(:Either(:Int32 :Uint32))
          :body [ 1 ]
        }
        """;
    var doc = parseDocument(input);
    var bodyEntry = doc.documentBody().bodyEntry();
    Assertions.assertThrows(MalformedPayloadException.class, () -> {
      StvnIrVisitor.build(bodyEntry, doc);
    });
  }

  @Test
  void testAsymmetricInference_InferredNone_Fails() {
    var input = """
        {
          :type :Option(:Int)
          :body #Some
        }
        """;
    var doc = parseDocument(input);
    var bodyEntry = doc.documentBody().bodyEntry();
    Assertions.assertThrows(MalformedPayloadException.class, () -> {
      StvnIrVisitor.build(bodyEntry, doc);
    });
  }

  @Test
  void testVariantTrajectory_NestedImplicitExplicitOptionEither() {
    var input = """
        {
          :type :Option(:Either(:String :Int))
          :body 42
        }
        """;
    var doc = parseDocument(input);
    var bodyEntry = doc.documentBody().bodyEntry();
    var val = StvnIrVisitor.build(bodyEntry, doc).orElseThrow();
    
    Assertions.assertTrue(val instanceof StvnOption);
    var outerOpt = (StvnOption) val;
    var outerTrajectory = outerOpt.trajectory();
    Assertions.assertEquals(1, outerTrajectory.size());
    Assertions.assertEquals("#Some", outerTrajectory.get(0).tag());
    Assertions.assertTrue(outerTrajectory.get(0).isInferred());
    
    var innerEitherVal = outerOpt.value().orElseThrow();
    Assertions.assertTrue(innerEitherVal instanceof StvnEither);
    var innerEither = (StvnEither) innerEitherVal;
    var innerTrajectory = innerEither.trajectory();
    Assertions.assertEquals(2, innerTrajectory.size());
    Assertions.assertEquals("#Some", innerTrajectory.get(0).tag());
    Assertions.assertTrue(innerTrajectory.get(0).isInferred());
    Assertions.assertEquals("#Right", innerTrajectory.get(1).tag());
    Assertions.assertTrue(innerTrajectory.get(1).isInferred());
  }

  @Test
  void testVariantTrajectory_CollectionBoundaryIsolation() {
    var input = """
        {
          :type :Option(:Seq(:Option(:Int)))
          :body [ 42 ]
        }
        """;
    var doc = parseDocument(input);
    var bodyEntry = doc.documentBody().bodyEntry();
    var val = StvnIrVisitor.build(bodyEntry, doc).orElseThrow();

    Assertions.assertTrue(val instanceof StvnOption);
    var outerOpt = (StvnOption) val;
    var outerTrajectory = outerOpt.trajectory();
    Assertions.assertEquals(1, outerTrajectory.size());
    Assertions.assertEquals("#Some", outerTrajectory.get(0).tag());
    Assertions.assertTrue(outerTrajectory.get(0).isInferred());

    var seqVal = outerOpt.value().orElseThrow();
    Assertions.assertTrue(seqVal instanceof org.stvnadore.core.ir.StvnValue.StvnSeq);
    var seq = (org.stvnadore.core.ir.StvnValue.StvnSeq) seqVal;
    
    var innerVal = seq.elements().get(0);
    Assertions.assertTrue(innerVal instanceof StvnOption);
    var innerOpt = (StvnOption) innerVal;
    var innerTrajectory = innerOpt.trajectory();
    
    Assertions.assertEquals(1, innerTrajectory.size());
    Assertions.assertEquals("#Some", innerTrajectory.get(0).tag());
    Assertions.assertTrue(innerTrajectory.get(0).isInferred());
  }

  @Test
  void testValidBooleanLiterals() {
    var inputs = java.util.List.of(
        "{ :type :Boolean :body #TRUE }",
        "{ :type :Boolean :body #T }",
        "{ :type :Boolean :body #FALSE }",
        "{ :type :Boolean :body #F }"
    );
    for (var input : inputs) {
      var doc = parseDocument(input);
      var bodyEntry = doc.documentBody().bodyEntry();
      var val = StvnIrVisitor.build(bodyEntry, doc);
      Assertions.assertTrue(val.isPresent());
      Assertions.assertTrue(val.get() instanceof org.stvnadore.core.ir.StvnValue.StvnBoolean);
    }
  }

  @Test
  void testInvalidBooleanCasing_False() {
    var input = """
        {
          :type :Boolean
          :body #False
        }
        """;
    var doc = parseDocument(input);
    var bodyEntry = doc.documentBody().bodyEntry();
    var ex = Assertions.assertThrows(StvnMalformedLiteralException.class, () -> {
      StvnIrVisitor.build(bodyEntry, doc);
    });
    Assertions.assertEquals("Invalid boolean literal casing: found '#False', expected exactly '#TRUE', '#T', '#FALSE', or '#F'.", ex.getMessage());
    Assertions.assertEquals(input.indexOf("#False"), ex.startOffset());
    Assertions.assertEquals(input.indexOf("#False") + "#False".length(), ex.endOffset());
  }

  @Test
  void testInvalidBooleanCasing_t() {
    var input = """
        {
          :type :Boolean
          :body #t
        }
        """;
    var doc = parseDocument(input);
    var bodyEntry = doc.documentBody().bodyEntry();
    var ex = Assertions.assertThrows(StvnMalformedLiteralException.class, () -> {
      StvnIrVisitor.build(bodyEntry, doc);
    });
    Assertions.assertEquals("Invalid boolean literal casing: found '#t', expected exactly '#TRUE', '#T', '#FALSE', or '#F'.", ex.getMessage());
    Assertions.assertEquals(input.indexOf("#t"), ex.startOffset());
    Assertions.assertEquals(input.indexOf("#t") + "#t".length(), ex.endOffset());
  }

  @Test
  void testInvalidBooleanCasing_true() {
    var input = """
        {
          :type :Boolean
          :body #true
        }
        """;
    var doc = parseDocument(input);
    var bodyEntry = doc.documentBody().bodyEntry();
    var ex = Assertions.assertThrows(StvnMalformedLiteralException.class, () -> {
      StvnIrVisitor.build(bodyEntry, doc);
    });
    Assertions.assertEquals("Invalid boolean literal casing: found '#true', expected exactly '#TRUE', '#T', '#FALSE', or '#F'.", ex.getMessage());
    Assertions.assertEquals(input.indexOf("#true"), ex.startOffset());
    Assertions.assertEquals(input.indexOf("#true") + "#true".length(), ex.endOffset());
  }

  @Test
  void testBooleanStructuralTypeMismatch_Integer() {
    var input = """
        {
          :type :Boolean
          :body 42
        }
        """;
    var doc = parseDocument(input);
    var bodyEntry = doc.documentBody().bodyEntry();
    var ex = Assertions.assertThrows(MalformedPayloadException.class, () -> {
      StvnIrVisitor.build(bodyEntry, doc);
    });
    Assertions.assertTrue(ex.getMessage().contains("Type mismatch: Expected boolean, got integer"));
  }

  @Test
  void testBooleanStructuralTypeMismatch_List() {
    var input = """
        {
          :type :Boolean
          :body [ #TRUE ]
        }
        """;
    var doc = parseDocument(input);
    var bodyEntry = doc.documentBody().bodyEntry();
    Assertions.assertThrows(MalformedPayloadException.class, () -> {
      StvnIrVisitor.build(bodyEntry, doc);
    });
  }
}
