package org.stvnadore.core.validation;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.io.CanonicalStvnWriter;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.ir.StvnValue.EitherBranch;
import org.stvnadore.core.ir.StvnValue.StvnEither;
import org.stvnadore.core.ir.StvnValue.StvnInt;
import org.stvnadore.core.ir.StvnValue.StvnOption;
import org.stvnadore.core.printer.AstCompactPrinter;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

class StvnTypeResolverRegressionTest {

  @Test
  void testNominalAliasPrefixCollision_Floaty() {
    String input = """
        {
          :defs {
            :Floaty :Int
          }
          :type :Floaty
          :body 7
        }
        """;
    var schema = StvnCompiler.compile(input).document().orElseThrow()
        .defs().findType(":Floaty").orElseThrow();
    Assertions.assertTrue(schema.traits().equatable(), "Nominal alias :Floaty backed by :Int must remain equatable");
  }

  @Test
  void testNominalAliasPrefixCollision_Setting() {
    String input = """
        {
          :defs {
            :Setting :String
          }
          :type :Setting
          :body "enabled"
        }
        """;
    var schema = StvnCompiler.compile(input).document().orElseThrow()
        .defs().findType(":Setting").orElseThrow();
    Assertions.assertTrue(schema.traits().comparable(), "Nominal alias :Setting backed by :String must remain comparable");
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
          :body "my c\\n"
        }
        """;

    var res = StvnCompiler.compile(input);
    Assertions.assertTrue(res.isSuccess(), () -> "Expected clean compile: " + res.diagnostics());
    var doc = res.document().orElseThrow();
    var stringSchema = (StvnSchema.StringSchema) doc.defs().findType(":MyString").orElseThrow();
    Assertions.assertEquals(Optional.of("my [content]\n"), stringSchema.rawRegexPattern());
  }

  @Test
  void testMalformedListFailsCompilation() {
    var input = """
        {
          :type :Seq(:Option(:Tuple(:Int)))
          :body [ #Some ( ) ]
        }
        """;
    var res = StvnCompiler.compileToResult(input, null, StvnParserConfig.DEFAULT);
    Assertions.assertTrue(res.hasErrors());
    Assertions.assertThrows(RuntimeException.class, () -> StvnCompiler.compilePayload(input));
  }

  @Test
  void testMalformedMapFailsCompilation() {
    var input = """
        {
          :type :Map(:String :Int)
          :body { [ "key" ] }
        }
        """;
    var res = StvnCompiler.compileToResult(input, null, StvnParserConfig.DEFAULT);
    Assertions.assertTrue(res.hasErrors());
    Assertions.assertThrows(RuntimeException.class, () -> StvnCompiler.compilePayload(input));
  }

  @Test
  void testMalformedTupleFailsCompilation() {
    var input = """
        {
          :type :Tuple(:Option(:Tuple(:Int)))
          :body ( #Some ( ) )
        }
        """;
    var res = StvnCompiler.compileToResult(input, null, StvnParserConfig.DEFAULT);
    Assertions.assertTrue(res.hasErrors());
    Assertions.assertThrows(RuntimeException.class, () -> StvnCompiler.compilePayload(input));
  }

  @Test
  void testEmptySeqSchemaFailsCompilation() {
    var input = """
        {
          :defs {
            :MySeq :Seq()
          }
          :type :MySeq
          :body [1]
        }
        """;
    var res = StvnCompiler.compileToResult(input, null, StvnParserConfig.DEFAULT);
    Assertions.assertTrue(res.hasErrors());
    Assertions.assertThrows(RuntimeException.class, () -> StvnCompiler.compilePayload(input));
  }

  @Test
  void testDuplicateKeysMap() {
    var input = """
        {
          :type :Map(:String :Int)
          :body { ["a" 1] ["a" 2] }
        }
        """;
    var ex = Assertions.assertThrows(
        StvnCollectionCollisionException.class,
        () -> StvnCompiler.compilePayload(input)
    );
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
    var ex = Assertions.assertThrows(
        StvnCollectionCollisionException.class,
        () -> StvnCompiler.compilePayload(input)
    );
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
    var ex = Assertions.assertThrows(
        StvnCollectionCollisionException.class,
        () -> StvnCompiler.compilePayload(input)
    );
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
    var ex = Assertions.assertThrows(
        StvnCollectionCollisionException.class,
        () -> StvnCompiler.compilePayload(input)
    );
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
    var ex = Assertions.assertThrows(
        StvnCollectionCollisionException.class,
        () -> StvnCompiler.compilePayload(input)
    );
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
    var ex = Assertions.assertThrows(
        StvnCollectionCollisionException.class,
        () -> StvnCompiler.compilePayload(input)
    );
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
    var ex = Assertions.assertThrows(
        MalformedSchemaException.class,
        () -> StvnCompiler.compilePayload(input)
    );
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
    var ex = Assertions.assertThrows(
        MalformedSchemaException.class,
        () -> StvnCompiler.compilePayload(input)
    );
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
    var ex = Assertions.assertThrows(
        MalformedSchemaException.class,
        () -> StvnCompiler.compilePayload(input)
    );
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
    var val = StvnCompiler.compilePayload(input).orElseThrow();
    var outerOpt = Assertions.assertInstanceOf(StvnOption.class, val);
    Assertions.assertTrue(outerOpt.value().isPresent());
    var innerOpt = Assertions.assertInstanceOf(StvnOption.class, outerOpt.value().get());
    Assertions.assertTrue(innerOpt.value().isPresent());
    var innerVal = Assertions.assertInstanceOf(StvnInt.class, innerOpt.value().get());
    Assertions.assertEquals(BigInteger.valueOf(42), innerVal.value());
  }

  @Test
  void testDeeplyNestedImplicitInference() {
    var input = """
        {
          :type :Option(:Option(:Either(:Int :String)))
          :body #Left 42
        }
        """;
    var val = StvnCompiler.compilePayload(input).orElseThrow();
    var outerOpt = Assertions.assertInstanceOf(StvnOption.class, val);
    Assertions.assertTrue(outerOpt.value().isPresent());
    var innerOpt = Assertions.assertInstanceOf(StvnOption.class, outerOpt.value().get());
    Assertions.assertTrue(innerOpt.value().isPresent());
    var either = Assertions.assertInstanceOf(StvnEither.class, innerOpt.value().get());
    Assertions.assertEquals(EitherBranch.LEFT, either.branch());
    var innerInt = Assertions.assertInstanceOf(StvnInt.class, either.value());
    Assertions.assertEquals(BigInteger.valueOf(42), innerInt.value());
  }

  @Test
  void testAmbiguousInference_InferredOuterLeft_Fails() {
    var input = """
        {
          :type :Seq(:Option(:Either(:String :Option(:Either(:String :Float)))))
          :body [ "Inferred Outer Left" ]
        }
        """;
    Assertions.assertThrows(MalformedPayloadException.class, () -> StvnCompiler.compilePayload(input));
  }

  @Test
  void testRuleE_UntaggedInnerLeft_Fails() {
    var input = """
        {
          :type :Seq(:Option(:Either(:String :Option(:Either(:String :Float)))))
          :body [ #Right "Inferred Inner Left" ]
        }
        """;
    Assertions.assertThrows(MalformedPayloadException.class, () -> StvnCompiler.compilePayload(input));
  }

  @Test
  void testRuleE_ExplicitInnerLeft_Succeeds() {
    var input = """
        {
          :type :Seq(:Option(:Either(:String :Option(:Either(:String :Float)))))
          :body [ #Right #Left "Inferred Inner Left" ]
        }
        """;
    var val = StvnCompiler.compilePayload(input);
    Assertions.assertTrue(val.isPresent());
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
    Assertions.assertThrows(MalformedPayloadException.class, () -> StvnCompiler.compilePayload(input));
  }

  @Test
  void testAsymmetricInference_InferredNone_Fails() {
    var input = """
        {
          :type :Option(:Int)
          :body #Some
        }
        """;
    Assertions.assertThrows(RuntimeException.class, () -> StvnCompiler.compilePayload(input));
  }

  @Test
  void testVariantTrajectory_NestedImplicitExplicitOptionEither() {
    var input = """
        {
          :type :Option(:Either(:String :Int))
          :body 42
        }
        """;
    var doc = StvnCompiler.compile(input).document().orElseThrow();
    var val = doc.requirePayload();

    var outerOpt = Assertions.assertInstanceOf(StvnOption.class, val);
    var innerEither = Assertions.assertInstanceOf(StvnEither.class, outerOpt.value().orElseThrow());
    Assertions.assertEquals(EitherBranch.RIGHT, innerEither.branch());
    Assertions.assertEquals(BigInteger.valueOf(42), Assertions.assertInstanceOf(StvnInt.class, innerEither.value()).value());

    String compact = AstCompactPrinter.print(doc);
    Assertions.assertTrue(compact.contains(":body 42"), "HAPPY_PATH_INFERRED must elide #Some and #Right: " + compact);
    String canonical = StvnCompiler.toCanonicalString(doc);
    Assertions.assertTrue(canonical.contains(":body #S #R 42"), "Canonical CAS form must emit explicit #S #R: " + canonical);
    Assertions.assertEquals(val, StvnCompiler.compilePayload(compact).orElseThrow());
    Assertions.assertEquals(val, StvnCompiler.compilePayload(canonical).orElseThrow());
  }

  @Test
  void testVariantTrajectory_CollectionBoundaryIsolation() {
    var input = """
        {
          :type :Option(:Seq(:Option(:Int)))
          :body [ 42 ]
        }
        """;
    var doc = StvnCompiler.compile(input).document().orElseThrow();
    var val = doc.requirePayload();

    var outerOpt = Assertions.assertInstanceOf(StvnOption.class, val);
    var seq = Assertions.assertInstanceOf(StvnValue.StvnSeq.class, outerOpt.value().orElseThrow());
    var innerOpt = Assertions.assertInstanceOf(StvnOption.class, seq.elements().get(0));
    Assertions.assertEquals(BigInteger.valueOf(42), Assertions.assertInstanceOf(StvnInt.class, innerOpt.value().orElseThrow()).value());

    String compact = AstCompactPrinter.print(doc);
    Assertions.assertTrue(compact.contains(":body[42]"), "HAPPY_PATH_INFERRED must elide outer and inner #Some: " + compact);
    String canonical = StvnCompiler.toCanonicalString(doc);
    Assertions.assertTrue(canonical.contains(":body #S[#S 42]"), "Canonical CAS form must emit explicit #S[#S 42]: " + canonical);
    Assertions.assertEquals(val, StvnCompiler.compilePayload(compact).orElseThrow());
    Assertions.assertEquals(val, StvnCompiler.compilePayload(canonical).orElseThrow());
  }

  @Test
  void testValidBooleanLiterals() {
    var inputs = List.of(
        "{ :type :Boolean :body #TRUE }",
        "{ :type :Boolean :body #T }",
        "{ :type :Boolean :body #FALSE }",
        "{ :type :Boolean :body #F }"
    );
    for (var input : inputs) {
      var val = StvnCompiler.compilePayload(input);
      Assertions.assertTrue(val.isPresent());
      Assertions.assertInstanceOf(StvnValue.StvnBoolean.class, val.get());
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
    var ex = Assertions.assertThrows(
        StvnMalformedLiteralException.class,
        () -> StvnCompiler.compilePayload(input)
    );
    Assertions.assertEquals(
        "Invalid boolean literal casing: found '#False', expected exactly '#TRUE', '#T', '#FALSE', or '#F'.",
        ex.getMessage()
    );
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
    var ex = Assertions.assertThrows(
        StvnMalformedLiteralException.class,
        () -> StvnCompiler.compilePayload(input)
    );
    Assertions.assertEquals(
        "Invalid boolean literal casing: found '#t', expected exactly '#TRUE', '#T', '#FALSE', or '#F'.",
        ex.getMessage()
    );
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
    var ex = Assertions.assertThrows(
        StvnMalformedLiteralException.class,
        () -> StvnCompiler.compilePayload(input)
    );
    Assertions.assertEquals(
        "Invalid boolean literal casing: found '#true', expected exactly '#TRUE', '#T', '#FALSE', or '#F'.",
        ex.getMessage()
    );
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
    var ex = Assertions.assertThrows(
        MalformedPayloadException.class,
        () -> StvnCompiler.compilePayload(input)
    );
    Assertions.assertTrue(
        ex.getMessage().contains("Type mismatch") && ex.getMessage().contains(":Boolean") && ex.getMessage().contains(":Int"),
        "Unexpected message: " + ex.getMessage()
    );
  }

  @Test
  void testBooleanStructuralTypeMismatch_List() {
    var input = """
        {
          :type :Boolean
          :body [ #TRUE ]
        }
        """;
    Assertions.assertThrows(MalformedPayloadException.class, () -> StvnCompiler.compilePayload(input));
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
    Assertions.assertEquals(
        42L,
        Assertions.assertInstanceOf(StvnInt.class, result.document().orElseThrow().requirePayload()).value().longValue()
    );
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
    Assertions.assertInstanceOf(StvnInt.class, result.document().orElseThrow().requirePayload());
  }

  @Test
  @DisplayName("TC-PRELUDE-USE-01: Scoped :use import of :IPv4 compiles cleanly and enforces regex")
  void testScopedPreludeUseIPv4RegexConstraint() {
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
    Assertions.assertTrue(
        emptyResult.diagnostics().stream().anyMatch(d ->
            d.message().contains("String cannot be empty")
                || d.message().contains("minSize")
                || d.message().contains("Length")),
        "Must record constraint violation for empty string: " + emptyResult.diagnostics()
    );

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
    Assertions.assertTrue(
        patternResult.diagnostics().stream().anyMatch(d ->
            d.message().contains("pattern") || d.message().contains("regex")),
        "Must record regex violation: " + patternResult.diagnostics()
    );
  }

  @Test
  @DisplayName("TC-PRELUDE-USE-02: Scoped :use import of :Port compiles cleanly and enforces numeric bounds")
  void testScopedPreludeUsePortBoundsConstraint() {
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
    Assertions.assertTrue(
        overflowResult.diagnostics().stream().anyMatch(d ->
            DiagnosticBag.ERR_INTEGER_OVERFLOW.equals(d.errorCode().orElse(null))
                || d.message().contains("overflows")
                || d.message().contains("out of bounds")),
        "Must record integer overflow diagnostic: " + overflowResult.diagnostics()
    );
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
    Assertions.assertTrue(
        result.diagnostics().stream().anyMatch(d ->
            DiagnosticBag.ERR_UNRESOLVED_PACKAGE_IN_HERMETIC_DOCUMENT.equals(d.errorCode().orElse(null))),
        "Must record ERR_UNRESOLVED_PACKAGE_IN_HERMETIC_DOCUMENT: " + result.diagnostics()
    );
  }

  @Test
  @DisplayName("TC-REG-OFFSETS-01: Throwing compilePayload preserves MalformedSchemaException and pinned offsets")
  void testThrowingCompilePayloadPreservesMalformedSchemaExceptionAndOffsets() {
    String source = """
        {
          :defs {
            :BadNode { #equatable #FALSE } :Tuple( :Int )
          }
          :type :Set( :BadNode )
          :body [ ( 1 ) ]
        }
        """;
    var ex = Assertions.assertThrows(
        MalformedSchemaException.class,
        () -> StvnCompiler.compilePayload(source),
        "compilePayload(source) throwing entrypoint must throw MalformedSchemaException"
    );
    Assertions.assertEquals("Set elements require types to be #equatable #TRUE", ex.getMessage());
    Assertions.assertTrue(ex.startOffset() >= 0, "startOffset must be non-negative");
    Assertions.assertTrue(ex.endOffset() > ex.startOffset(), "endOffset must be greater than startOffset");
    Assertions.assertEquals(":BadNode", source.substring(ex.startOffset(), ex.endOffset()));
  }
}
