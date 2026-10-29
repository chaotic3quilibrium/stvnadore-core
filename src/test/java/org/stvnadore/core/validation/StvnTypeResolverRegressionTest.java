package org.stvnadore.core.validation;

import java.util.Optional;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.parser.StvnLexer;
import org.stvnadore.core.parser.StvnParser;
import org.stvnadore.core.ir.StvnIrVisitor;
import org.stvnadore.core.ir.StvnValue;
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
            :MyString { #regex \"\"\"[TAG]
        my [content]
        [TAG]\"\"\" } :String
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

    var metaMap = defOpt.get().schemaType() != null ? defOpt.get().schemaType().metadataMap() : null;
    var constraints = StvnTypeResolver.extractConstraints(metaMap);
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
            :MyMapInv { #invertible } :Map(:String :Int)
          }
          :type :MyMapInv
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
            :MyStringFixed3 { #minSize 3 #maxSize 3 } :String
            :MyInt8 { #size 8 } :Int
            :MyMapInv { #invertible } :Map(:MyStringFixed3 :MyInt8)
          }
          :type :MyMapInv
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
            :MyStringFixed3 { #minSize 3 #maxSize 3 } :String
            :MyInt8 { #size 8 } :Int
            :MapInvNE { #invertible #minSize 1 } :Map(:MyStringFixed3 :MyInt8)
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
            :MyFloat32 { #size 32 } :Float
            :MyMap :Map(:MyFloat32 :Int)
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
            :MyFloat32 { #size 32 } :Float
            :MyMapInv { #invertible } :Map(:Int :MyFloat32)
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
            :MyFloat32 { #size 32 } :Float
            :MySet :Set(:MyFloat32)
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
            :MyInt32 :Int
            :MyUint32 { #unsigned } :Int
          }
          :type :Seq(:Either(:MyInt32 :MyUint32))
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

  @Test
  @DisplayName("TC-TYPE-01: Nominal alias :IntCounter is never misclassified as :Int primitive")
  void testNominalTypePrefixCollision_ScalarInt() {
    String input = """
        {
          :defs {
            :IntCounter { #minIncl 0 } :Int
          }
          :type :IntCounter
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(input, null, StvnParserConfig.DEFAULT);
    Assertions.assertFalse(result.hasErrors(), "Valid nominal type :IntCounter must compile cleanly");
    Assertions.assertEquals(42L, Assertions.assertInstanceOf(StvnInteger.class, result.document().orElseThrow().requirePayload()).value().longValue());
  }

  @Test
  @DisplayName("TC-TYPE-02: Nominal alias :StringList is never misclassified as :String primitive")
  void testNominalTypePrefixCollision_ScalarString() {
    String input = """
        {
          :defs {
            :StringList :Seq(:String)
          }
          :type :StringList
          :body [ "alpha" "beta" ]
        }
        """;
    var result = StvnCompiler.compileToResult(input, null, StvnParserConfig.DEFAULT);
    Assertions.assertFalse(result.hasErrors(), "Valid nominal type :StringList must compile cleanly");
    Assertions.assertInstanceOf(StvnValue.StvnSeq.class, result.document().orElseThrow().requirePayload());
  }

  @Test
  @DisplayName("TC-TYPE-03: Nominal alias :SeqRecord is never misclassified as :Seq constructor")
  void testNominalTypePrefixCollision_CompositeSeq() {
    String input = """
        {
          :defs {
            :SeqRecord :Tuple(:Int :String)
          }
          :type :SeqRecord
          :body ( 100 "item" )
        }
        """;
    var result = StvnCompiler.compileToResult(input, null, StvnParserConfig.DEFAULT);
    Assertions.assertFalse(result.hasErrors(), "Valid nominal type :SeqRecord must compile cleanly");
    Assertions.assertInstanceOf(StvnValue.StvnTuple.class, result.document().orElseThrow().requirePayload());
  }

  @Test
  @DisplayName("TC-TYPE-04: Nominal alias :MapStore is never misclassified as :Map constructor")
  void testNominalTypePrefixCollision_CompositeMap() {
    String input = """
        {
          :defs {
            :MapStore :Tuple(:String :Int)
          }
          :type :MapStore
          :body ( "config" 8080 )
        }
        """;
    var result = StvnCompiler.compileToResult(input, null, StvnParserConfig.DEFAULT);
    Assertions.assertFalse(result.hasErrors(), "Valid nominal type :MapStore must compile cleanly");
    Assertions.assertInstanceOf(StvnValue.StvnTuple.class, result.document().orElseThrow().requirePayload());
  }

  @Test
  @DisplayName("TC-TYPE-05: Former compound type on LHS of type definition compiles cleanly as nominal definition")
  void testObsoleteCompoundOnTypeDefinitionLhsRejected() {
    String input = """
        {
          :defs {
            :Int32 :Int
          }
          :type :Int32
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(input, null, StvnParserConfig.DEFAULT);
    Assertions.assertFalse(result.hasErrors(), "Nominal :Int32 in :defs must compile cleanly");
    Assertions.assertTrue(result.isSuccess(), "Compilation must succeed");
    Assertions.assertInstanceOf(StvnValue.StvnInteger.class, result.document().orElseThrow().requirePayload());
  }

  @Test
  @DisplayName("TC-PRELUDE-USE-01: Scoped :use import of :IPv4 compiles cleanly and enforces regex")
  void testScopedPreludeUseIPv4RegexConstraint() {
    // Happy path: valid IPv4 string passes cleanly
    String validSource = """
        {
          :defs {
            :use [ :org/stvnadore/prelude { :IPv4 :IPv4 } ]
            :A :IPv4
          }
          :type :A
          :body "127.0.0.1"
        }
        """;
    var validResult = StvnCompiler.compileToResult(validSource, null, StvnParserConfig.DEFAULT);
    Assertions.assertTrue(validResult.isSuccess(), "Scoped :use import of :IPv4 must compile cleanly: " + validResult.diagnostics());

    // Negative path: empty string violates #minSize 7
    String emptySource = """
        {
          :defs {
            :use [ :org/stvnadore/prelude { :IPv4 :IPv4 } ]
            :A :IPv4
          }
          :type :A
          :body ""
        }
        """;
    var emptyResult = StvnCompiler.compileToResult(emptySource, null, StvnParserConfig.DEFAULT);
    Assertions.assertFalse(emptyResult.isSuccess(), "Empty string must violate #minSize constraint for :IPv4");
    Assertions.assertTrue(emptyResult.diagnostics().stream().anyMatch(d ->
        d.message().contains("String cannot be empty") || d.message().contains("minSize")),
        "Must record constraint violation for empty string: " + emptyResult.diagnostics());

    // Negative path: invalid IPv4 pattern violates #regex
    String invalidPatternSource = """
        {
          :defs {
            :use [ :org/stvnadore/prelude { :IPv4 :IPv4 } ]
            :A :IPv4
          }
          :type :A
          :body "999.999.999.999"
        }
        """;
    var patternResult = StvnCompiler.compileToResult(invalidPatternSource, null, StvnParserConfig.DEFAULT);
    Assertions.assertFalse(patternResult.isSuccess(), "Malformed IP address must violate regex pattern for :IPv4");
    Assertions.assertTrue(patternResult.diagnostics().stream().anyMatch(d ->
        d.message().contains("pattern") || d.message().contains("regex")),
        "Must record regex violation: " + patternResult.diagnostics());
  }

  @Test
  @DisplayName("TC-PRELUDE-USE-02: Scoped :use import of :Port compiles cleanly and enforces numeric bounds")
  void testScopedPreludeUsePortBoundsConstraint() {
    // Happy path: valid port integer passes cleanly
    String validSource = """
        {
          :defs {
            :use [ :org/stvnadore/prelude { :Port :Port } ]
            :A :Port
          }
          :type :A
          :body 8080
        }
        """;
    var validResult = StvnCompiler.compileToResult(validSource, null, StvnParserConfig.DEFAULT);
    Assertions.assertTrue(validResult.isSuccess(), "Scoped :use import of :Port must compile cleanly: " + validResult.diagnostics());

    // Negative path: 70000 exceeds 16-bit unsigned integer capacity (0 to 65535)
    String overflowSource = """
        {
          :defs {
            :use [ :org/stvnadore/prelude { :Port :Port } ]
            :A :Port
          }
          :type :A
          :body 70000
        }
        """;
    var overflowResult = StvnCompiler.compileToResult(overflowSource, null, StvnParserConfig.DEFAULT);
    Assertions.assertFalse(overflowResult.isSuccess(), "Port 70000 must violate bounds for 16-bit Port");
    Assertions.assertTrue(overflowResult.diagnostics().stream().anyMatch(d ->
        DiagnosticBag.ERR_INTEGER_OVERFLOW.equals(d.errorCode().orElse(null)) || d.message().contains("out of bounds")),
        "Must record integer overflow diagnostic: " + overflowResult.diagnostics());
  }

  @Test
  @DisplayName("TC-PRELUDE-USE-03: Scoped :use with #strip option resolves prelude symbols")
  void testScopedPreludeUseStripOption() {
    String source = """
        {
          :defs {
            :use [ :org/stvnadore/prelude { #strip } ]
            :MyAddress :IPv4
            :MyPort :Port
          }
          :type :Tuple( :MyAddress :MyPort )
          :body ( "10.0.0.1" 443 )
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    Assertions.assertTrue(result.isSuccess(), "Scoped :use with #strip must resolve :IPv4 and :Port: " + result.diagnostics());
  }

  @Test
  @DisplayName("TC-PRELUDE-USE-04: Explicit alias mapping renames prelude type cleanly")
  void testScopedPreludeUseRenamedAlias() {
    String source = """
        {
          :defs {
            :use [ :org/stvnadore/prelude { :IPv4 :LocalIPv4 } ]
            :A :LocalIPv4
          }
          :type :A
          :body "172.16.0.1"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    Assertions.assertTrue(result.isSuccess(), "Renamed alias :LocalIPv4 must compile cleanly: " + result.diagnostics());
  }

  @Test
  @DisplayName("TC-PRELUDE-USE-05: Subpackage import :org/stvnadore/prelude/meta resolves substrate types")
  void testScopedPreludeUseSubpackageMeta() {
    String source = """
        {
          :defs {
            :use [ :org/stvnadore/prelude/meta { #strip } ]
            :Kind :StvnDocumentKind
          }
          :type :Kind
          :body #BODY
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    Assertions.assertTrue(result.isSuccess(), "Subpackage import must resolve :StvnDocumentKind: " + result.diagnostics());
  }

  @Test
  @DisplayName("TC-PRELUDE-USE-06: Scoped :use in hermetic document cannot target external package")
  void testHermeticDocumentExternalPackageUseRejected() {
    String source = """
        {
          :defs {
            :use [ :org/example/external { :Widget :Widget } ]
            :A :Widget
          }
          :type :A
          :body "demo"
        }
        """;
    var result = StvnCompiler.compileToResult(source, "document.stvn", StvnParserConfig.DEFAULT);
    Assertions.assertFalse(result.isSuccess(), "Hermetic document targeting external package must be rejected");
    Assertions.assertTrue(result.diagnostics().stream().anyMatch(d ->
        DiagnosticBag.ERR_UNRESOLVED_PACKAGE_IN_HERMETIC_DOCUMENT.equals(d.errorCode().orElse(null))),
        "Must record ERR_UNRESOLVED_PACKAGE_IN_HERMETIC_DOCUMENT: " + result.diagnostics());
  }

  @Test
  @DisplayName("TC-REG-OFFSETS-01: Throwing validateDocumentConstraints preserves legacy exception and offsets")
  void testThrowingValidateDocumentConstraintsPreservesLegacyExceptionAndOffsets() {
    String source = """
        {
          :defs {
            :BadNode { #equatable #FALSE } :Tuple( :Int )
          }
          :type :Set( :BadNode )
          :body [ ( 1 ) ]
        }
        """;
    var doc = StvnCompiler.parse(source, StvnParserConfig.DEFAULT);
    var ex = Assertions.assertThrows(
        MalformedSchemaException.class,
        () -> StvnTypeResolver.validateDocumentConstraints(doc),
        "validateDocumentConstraints(doc) throwing entrypoint must throw MalformedSchemaException"
    );
    Assertions.assertEquals("Set elements require types to be #equatable #TRUE", ex.getMessage());
    Assertions.assertTrue(ex.startOffset() >= 0, "startOffset must be non-negative");
    Assertions.assertTrue(ex.endOffset() > ex.startOffset(), "endOffset must be greater than startOffset");
    Assertions.assertEquals(":BadNode", source.substring(ex.startOffset(), ex.endOffset()));
  }
}
