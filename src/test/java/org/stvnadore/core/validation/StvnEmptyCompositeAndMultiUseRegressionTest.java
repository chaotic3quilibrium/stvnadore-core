package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.printer.StvnSourceFormatter;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Regression suite verifying zero-cascade recovery for empty and wrong-arity parameterized
 * schema constructors ({@code EC-01}) and multi-element {@code :use [ ... ]} directive
 * grammar, left-to-right § 4.2 eviction, and source formatting ({@code EC-02}).
 */
@NullMarked
class StvnEmptyCompositeAndMultiUseRegressionTest {

  @Test
  @DisplayName("TC-EC01-01: Stage 7 :Tuple() in :defs closes cleanly without swallowing subsequent TypeDefinitions")
  void testStage7EmptyTupleZeroCascadeInDefs() {
    String source = """
        {
          :defs {
            :SensorId { #minSize 1 #maxSize 32 } :String
            :SampleReading :Float
            :RetryMillis { #unsigned #size 16 #minIncl 10 #maxExcl 5000 } :Int
            :TrapEmptyTuple :Tuple()
            :TrapInvertedSeqCapacity { #minSize 12 #maxSize 4 } :Seq(:SensorId)
            :TrapFloatInSet { #maxSize 8 } :Set(:SampleReading)
            :Stage7CompositesMatrix :Tuple(:SensorId :RetryMillis)
          }
          :type :Tuple(:TrapEmptyTuple :Stage7CompositesMatrix)
          :body ( "suspended-poisoned-slot" ( "sensor-a" 100 ) )
        }
        """;

    StvnCompilationResult<StvnDocument> res = StvnCompiler.compile(source);
    List<StvnDiagnostic> errors = res.diagnostics().stream()
        .filter(d -> d.severity() == DiagnosticSeverity.ERROR)
        .toList();

    Assertions.assertTrue(
        errors.stream().noneMatch(d -> "E030".equals(d.canonicalCode().orElse(null))),
        () -> "Zero E030 UNDEFINED_TYPE cascades allowed, got: " + errors
    );
    Assertions.assertEquals(3, errors.size(), () -> "Expected exactly E010, E009, and E008, got: " + errors);

    StvnDiagnostic tupleDiag = errors.get(0);
    int expectedStart = source.indexOf(":Tuple()");
    int expectedEnd = expectedStart + ":Tuple()".length();
    Assertions.assertEquals(Optional.of("E010"), tupleDiag.canonicalCode());
    Assertions.assertEquals(Optional.of(DiagnosticBag.ERR_MALFORMED_SCHEMA), tupleDiag.symbolicId());
    Assertions.assertEquals(expectedStart, tupleDiag.startOffset());
    Assertions.assertEquals(expectedEnd, tupleDiag.endOffset());
    Assertions.assertEquals(
        "STVN Syntax Error: empty composite argument list: :Tuple() requires at least one schema type",
        tupleDiag.message()
    );

    Assertions.assertEquals(Optional.of("E009"), errors.get(1).canonicalCode());
    Assertions.assertEquals(Optional.of("E008"), errors.get(2).canonicalCode());

    StvnDocument doc = res.document().orElseThrow();
    StvnSchema trapTuple = doc.defs().findType(":TrapEmptyTuple").orElseThrow();
    Assertions.assertInstanceOf(StvnSchema.PoisonedSchema.class, trapTuple);
    Assertions.assertTrue(((StvnSchema.PoisonedSchema) trapTuple).isUngrounded());
    Assertions.assertInstanceOf(
        StvnSchema.TupleSchema.class,
        doc.defs().findType(":Stage7CompositesMatrix").orElseThrow()
    );
  }

  @Test
  @DisplayName("TC-EC01-02: Stage 8 :Union() in :defs closes cleanly without crashing subsequent #CONST definitions")
  void testStage8EmptyUnionZeroCascadeBeforeConstantDefinition() {
    String source = """
        {
          :defs {
            :RetryMillis { #unsigned #size 16 #minIncl 10 #maxExcl 5000 } :Int
            :OuterDispatchEnvelope :Option(:Option(:RetryMillis))
            :TrapEmptyUnion :Union()
            :WarnSingleBranchUnion :Union(:RetryMillis)
            #IRREDUCIBLE_DEF_NONE :OuterDispatchEnvelope #Some #None
          }
          :type :Tuple(:TrapEmptyUnion :OuterDispatchEnvelope)
          :body ( "suspended-union" #IRREDUCIBLE_DEF_NONE )
        }
        """;

    StvnCompilationResult<StvnDocument> res = StvnCompiler.compile(source);
    List<StvnDiagnostic> errors = res.diagnostics().stream()
        .filter(d -> d.severity() == DiagnosticSeverity.ERROR)
        .toList();

    Assertions.assertEquals(2, errors.size(), () -> "Expected only 2 E010 union errors, got: " + errors);
    Assertions.assertTrue(
        errors.stream().noneMatch(d -> d.message().contains("#IRREDUCIBLE_DEF_NONE")),
        () -> "Constant #IRREDUCIBLE_DEF_NONE must not fail with syntax error: " + errors
    );

    int union0Start = source.indexOf(":Union()");
    Assertions.assertEquals(Optional.of("E010"), errors.get(0).canonicalCode());
    Assertions.assertEquals(union0Start, errors.get(0).startOffset());
    Assertions.assertEquals(union0Start + ":Union()".length(), errors.get(0).endOffset());
    Assertions.assertEquals(
        "STVN Syntax Error: empty composite argument list: :Union() requires at least one schema type",
        errors.get(0).message()
    );

    Assertions.assertEquals(Optional.of("E010"), errors.get(1).canonicalCode());
    Assertions.assertEquals("Union schema requires at least 2 branch types, got 1", errors.get(1).message());

    StvnDocument doc = res.document().orElseThrow();
    Assertions.assertTrue(doc.defs().constants().containsKey("#IRREDUCIBLE_DEF_NONE"));
  }

  static Stream<Arguments> provideMalformedConstructorMatrix() {
    return Stream.of(
        Arguments.of(":Enum[]", "E010", DiagnosticBag.ERR_MALFORMED_SCHEMA,
            "STVN Syntax Error: empty enum variant list: :Enum[] requires at least one value keyword variant"),
        Arguments.of(":Enum [   ]", "E010", DiagnosticBag.ERR_MALFORMED_SCHEMA,
            "STVN Syntax Error: empty enum variant list: :Enum[] requires at least one value keyword variant"),
        Arguments.of(":Tuple()", "E010", DiagnosticBag.ERR_MALFORMED_SCHEMA,
            "STVN Syntax Error: empty composite argument list: :Tuple() requires at least one schema type"),
        Arguments.of(":Tuple (   )", "E010", DiagnosticBag.ERR_MALFORMED_SCHEMA,
            "STVN Syntax Error: empty composite argument list: :Tuple() requires at least one schema type"),
        Arguments.of(":Union()", "E010", DiagnosticBag.ERR_MALFORMED_SCHEMA,
            "STVN Syntax Error: empty composite argument list: :Union() requires at least one schema type"),
        Arguments.of(":Union (   )", "E010", DiagnosticBag.ERR_MALFORMED_SCHEMA,
            "STVN Syntax Error: empty composite argument list: :Union() requires at least one schema type"),
        Arguments.of(":Option()", "E002", "STVN_SYNTAX_ERROR",
            "STVN Syntax Error: empty composite argument list: collection requires schema type argument"),
        Arguments.of(":Option(:Int :String)", "E002", "STVN_SYNTAX_ERROR",
            "STVN Syntax Error: excessive composite arguments: ':Option' expects exactly 1 schema type argument, got 2"),
        Arguments.of(":Seq()", "E002", "STVN_SYNTAX_ERROR",
            "STVN Syntax Error: empty composite argument list: collection requires schema type argument"),
        Arguments.of(":Seq(:Int :String)", "E002", "STVN_SYNTAX_ERROR",
            "STVN Syntax Error: excessive composite arguments: ':Seq' expects exactly 1 schema type argument, got 2"),
        Arguments.of(":Set()", "E002", "STVN_SYNTAX_ERROR",
            "STVN Syntax Error: empty composite argument list: collection requires schema type argument"),
        Arguments.of(":Set(:Int :String)", "E002", "STVN_SYNTAX_ERROR",
            "STVN Syntax Error: excessive composite arguments: ':Set' expects exactly 1 schema type argument, got 2"),
        Arguments.of(":Either()", "E002", "STVN_SYNTAX_ERROR",
            "STVN Syntax Error: insufficient composite arguments: expected 2 schema type arguments"),
        Arguments.of(":Either(:Int)", "E002", "STVN_SYNTAX_ERROR",
            "STVN Syntax Error: insufficient composite arguments: expected 2 schema type arguments"),
        Arguments.of(":Either(:Int :String :Boolean)", "E002", "STVN_SYNTAX_ERROR",
            "STVN Syntax Error: excessive composite arguments: ':Either' expects exactly 2 schema type arguments, got 3"),
        Arguments.of(":Map()", "E002", "STVN_SYNTAX_ERROR",
            "STVN Syntax Error: insufficient composite arguments: expected 2 schema type arguments"),
        Arguments.of(":Map(:String)", "E002", "STVN_SYNTAX_ERROR",
            "STVN Syntax Error: insufficient composite arguments: expected 2 schema type arguments"),
        Arguments.of(":Map(:String :Int :Boolean)", "E002", "STVN_SYNTAX_ERROR",
            "STVN Syntax Error: excessive composite arguments: ':Map' expects exactly 2 schema type arguments, got 3")
    );
  }

  @ParameterizedTest(name = "TC-EC01-03 [{index}] {0} -> {1}")
  @MethodSource("provideMalformedConstructorMatrix")
  @DisplayName("TC-EC01-03: All 8 parameterized constructors isolate empty, under-arity, and over-arity errors")
  void testAllEightConstructorsZeroCascadeInDefs(
      String constructorExpr,
      String expectedCanonical,
      String expectedSymbolic,
      String expectedMessage
  ) {
    String source = """
        {
          :defs {
            :BrokenType %s
            :HealthyPort { #unsigned #size 16 #minIncl 1 } :Int
            #HEALTHY_CONST :HealthyPort 8080
          }
          :type :Tuple(:BrokenType :HealthyPort :HealthyPort)
          :body ( "ignored-broken-slot" #HEALTHY_CONST 0 )
        }
        """.formatted(constructorExpr);

    StvnCompilationResult<StvnDocument> res = StvnCompiler.compile(source);
    List<StvnDiagnostic> errors = res.diagnostics().stream()
        .filter(d -> d.severity() == DiagnosticSeverity.ERROR)
        .toList();

    Assertions.assertEquals(
        2,
        errors.size(),
        () -> "Expected 1 constructor diagnostic + 1 healthy sibling E012 body diagnostic, got: " + errors
    );

    StvnDiagnostic ctorDiag = errors.get(0);
    int start = source.indexOf(constructorExpr);
    int end = start + constructorExpr.length();
    Assertions.assertEquals(Optional.of(expectedCanonical), ctorDiag.canonicalCode());
    Assertions.assertEquals(Optional.of(expectedSymbolic), ctorDiag.symbolicId());
    Assertions.assertEquals(start, ctorDiag.startOffset());
    Assertions.assertEquals(end, ctorDiag.endOffset());
    Assertions.assertEquals(expectedMessage, ctorDiag.message());

    // Healthy sibling slot 2 (0 < 1) still validates in Pass 3!
    Assertions.assertEquals(Optional.of("E012"), errors.get(1).canonicalCode());

    StvnDocument doc = res.document().orElseThrow();
    Assertions.assertInstanceOf(StvnSchema.PoisonedSchema.class, doc.defs().findType(":BrokenType").orElseThrow());
    Assertions.assertInstanceOf(StvnSchema.IntSchema.class, doc.defs().findType(":HealthyPort").orElseThrow());
    Assertions.assertTrue(doc.defs().constants().containsKey("#HEALTHY_CONST"));
  }

  @Test
  @DisplayName("TC-EC01-04: StvnErrorStrategy.singleTokenDeletion protects ')' and '}' and suppresses duplicate Pass 2 stacking")
  void testClosingDelimiterProtectionAndAntiStackingGuard() {
    String innerBareColon = """
        {
          :defs {
            :BadSeq :Seq( : )
            :GoodInt :Int
          }
          :type :Tuple(:BadSeq :GoodInt)
          :body ( "suspended" 42 )
        }
        """;
    StvnCompilationResult<StvnDocument> res1 = StvnCompiler.compile(innerBareColon);
    List<StvnDiagnostic> errors1 = res1.diagnostics().stream()
        .filter(d -> d.severity() == DiagnosticSeverity.ERROR)
        .toList();
    Assertions.assertEquals(1, errors1.size(), () -> "Must not stack empty-constructor error over bare colon: " + errors1);
    Assertions.assertEquals(Optional.of(DiagnosticBag.ERR_BARE_COLON_PROHIBITED), errors1.get(0).symbolicId());
    Assertions.assertInstanceOf(
        StvnSchema.IntSchema.class,
        res1.document().orElseThrow().defs().findType(":GoodInt").orElseThrow()
    );

    String nestedEmptyPayloadTuple = """
        {
          :type :Tuple(:Tuple(:Int) :Int)
          :body ( () 42 )
        }
        """;
    StvnCompilationResult<StvnDocument> res2 = StvnCompiler.compile(nestedEmptyPayloadTuple);
    Assertions.assertTrue(res2.hasErrors());
    Assertions.assertTrue(
        res2.diagnostics().stream().noneMatch(d -> d.message().contains("unexpected token '}'")),
        () -> "Protected ')' on inner () must not cascade to outer '}': " + res2.diagnostics()
    );
  }

  @Test
  @DisplayName("TC-EC01-05: StvnSourceFormatter formats empty and wrong-arity constructors without IndexOutOfBoundsException")
  void testSourceFormatterHandlesEmptyAndWrongArityConstructorsSafely() {
    String source = """
        {
          :defs {
            :E0 :Enum[ ]
            :T0 :Tuple( )
            :U0 :Union( )
            :O0 :Option( )
            :S0 :Seq( )
            :St0 :Set( )
            :Ei1 :Either( :Int )
            :M1 :Map( :String )
            :MultiEmpty :Tuple(
            )
            :Valid :Int
          }
          :type :Valid
          :body 7
        }""";

    String formatted = StvnSourceFormatter.format(source);
    Assertions.assertTrue(formatted.contains(":E0 :Enum[]"));
    Assertions.assertTrue(formatted.contains(":T0 :Tuple()"));
    Assertions.assertTrue(formatted.contains(":U0 :Union()"));
    Assertions.assertTrue(formatted.contains(":O0 :Option()"));
    Assertions.assertTrue(formatted.contains(":S0 :Seq()"));
    Assertions.assertTrue(formatted.contains(":St0 :Set()"));
    Assertions.assertTrue(formatted.contains(":Ei1 :Either(:Int)"));
    Assertions.assertTrue(formatted.contains(":M1 :Map(:String)"));
    Assertions.assertEquals(formatted, StvnSourceFormatter.format(formatted));
    Assertions.assertEquals(Optional.of(formatted), StvnSourceFormatter.tryFormat(source));
  }

  @Test
  @DisplayName("TC-EC02-01: Multi-element :use [ ... ] matches sequential :use statements across § 4.2 eviction and CAS hashes")
  void testMultiElementUseEquivalenceAndCollisionSemantics() {
    String multiElementSource = """
        {
          :defs {
            :package :propulsion/thruster {
              :ThrustMillinewtons { #unsigned #minIncl 1 #maxExcl 500000 } :Int
              #RCS_PULSE_THRUST :ThrustMillinewtons 250
            }
            :package :propulsion/attitude {
              :ThrustMillinewtons { #unsigned #size 64 #minIncl 1 #maxExcl 1000000 } :Int
              :BusStatus :Enum[#NOMINAL #STANDBY #FAULT]
              #RCS_PULSE_THRUST :ThrustMillinewtons 500
            }
            :use [
              :propulsion/thruster { #strip }
              :propulsion/attitude {
                :ThrustMillinewtons :AttitudeThrust
                :BusStatus :AttitudeBusStatus
                #RCS_PULSE_THRUST #ATTITUDE_PULSE
              }
            ]
            :Envelope :Tuple(:ThrustMillinewtons :AttitudeThrust :AttitudeBusStatus)
          }
          :type :Envelope
          :body ( #RCS_PULSE_THRUST #ATTITUDE_PULSE #NOMINAL )
        }
        """;

    String sequentialUseSource = """
        {
          :defs {
            :package :propulsion/thruster {
              :ThrustMillinewtons { #unsigned #minIncl 1 #maxExcl 500000 } :Int
              #RCS_PULSE_THRUST :ThrustMillinewtons 250
            }
            :package :propulsion/attitude {
              :ThrustMillinewtons { #unsigned #size 64 #minIncl 1 #maxExcl 1000000 } :Int
              :BusStatus :Enum[#NOMINAL #STANDBY #FAULT]
              #RCS_PULSE_THRUST :ThrustMillinewtons 500
            }
            :use [ :propulsion/thruster { #strip } ]
            :use [
              :propulsion/attitude {
                :ThrustMillinewtons :AttitudeThrust
                :BusStatus :AttitudeBusStatus
                #RCS_PULSE_THRUST #ATTITUDE_PULSE
              }
            ]
            :Envelope :Tuple(:ThrustMillinewtons :AttitudeThrust :AttitudeBusStatus)
          }
          :type :Envelope
          :body ( #RCS_PULSE_THRUST #ATTITUDE_PULSE #NOMINAL )
        }
        """;

    var multiRes = StvnCompiler.compile(multiElementSource);
    var seqRes = StvnCompiler.compile(sequentialUseSource);
    Assertions.assertTrue(multiRes.diagnostics().isEmpty(), () -> "Unexpected diagnostics: " + multiRes.diagnostics());
    Assertions.assertTrue(seqRes.diagnostics().isEmpty(), () -> "Unexpected diagnostics: " + seqRes.diagnostics());

    StvnDocument multiDoc = multiRes.document().orElseThrow();
    StvnDocument seqDoc = seqRes.document().orElseThrow();
    Assertions.assertEquals(seqDoc.payload(), multiDoc.payload());
    Assertions.assertEquals(StvnCompiler.toCanonicalString(seqDoc), StvnCompiler.toCanonicalString(multiDoc));
    Assertions.assertArrayEquals(
        StvnCompiler.computeCasFingerprint(seqDoc),
        StvnCompiler.computeCasFingerprint(multiDoc)
    );

    // Unmitigated raw #strip vs raw #strip collision inside a single :use [ ... ] must emit E006
    String unmitigatedCollision = """
        {
          :defs {
            :package :pkg/left { :Shared :Int }
            :package :pkg/right { :Shared :String }
            :use [ :pkg/left { #strip } :pkg/right { #strip } ]
          }
          :type :Shared
          :body 1
        }
        """;
    var collisionRes = StvnCompiler.compile(unmitigatedCollision);
    Assertions.assertTrue(
        collisionRes.diagnostics().stream().anyMatch(d -> "E006".equals(d.canonicalCode().orElse(null))),
        () -> "Expected E006 namespace collision on dual #strip inside single :use: " + collisionRes.diagnostics()
    );
  }

  @Test
  @DisplayName("TC-EC02-02: Multi-element :use [ ... ] isolates per-element E002, E004, E008, E030, and E035 errors")
  void testMultiElementUsePerElementDiagnosticIsolation() {
    String hermeticSource = """
        {
          :defs {
            :package :local/ok { :LocalId { #unsigned } :Int }
            :use [
              :local/ok/ { #strip }
              :local/ok {}
              :local/ok { #strip #FALSE }
              :external/missing { #strip }
              :local/ok { #strip }
            ]
          }
          :type :LocalId
          :body 99
        }
        """;

    var res = StvnCompiler.compile(hermeticSource, "multi_use_check.stvn", StvnParserConfig.DEFAULT);
    List<StvnDiagnostic> errors = res.diagnostics().stream()
        .filter(d -> d.severity() == DiagnosticSeverity.ERROR)
        .toList();

    Assertions.assertEquals(4, errors.size(), () -> "Expected 4 isolated :use errors and 0 E030 on :LocalId: " + errors);
    Assertions.assertEquals(Optional.of("E002"), errors.get(0).canonicalCode());
    Assertions.assertEquals(Optional.of(DiagnosticBag.ERR_TRAILING_SLASH_PROHIBITED), errors.get(0).symbolicId());
    Assertions.assertEquals(Optional.of("E004"), errors.get(1).canonicalCode());
    Assertions.assertEquals(Optional.of("E008"), errors.get(2).canonicalCode());
    Assertions.assertEquals(Optional.of("E035"), errors.get(3).canonicalCode());
  }

  @Test
  @DisplayName("TC-EC02-03: StvnSourceFormatter formats single-line and multi-line multi-element :use [ ... ] idempotently")
  void testSourceFormatterMultiElementUseIdempotency() {
    String raw = """
        {
          :defs {
            :package :pkg/a { :A :Int }
            :package :pkg/b { :B :String }
            :use  [  :pkg/a  {  #strip  }   :pkg/b  {  :B  :AliasB  }  ]
            :use [
              // First package
              :pkg/a { :A :AliasA }
              // Second package
              :pkg/b {
                :B :MultiLineB
              }
            ]
            :Combined :Tuple(:A :AliasA :AliasB :MultiLineB)
          }
          :type :Combined
          :body (1 2 "x" "y")
        }""";

    String expected = """
        {
          :defs {
            :package :pkg/a {
              :A :Int
            }
            :package :pkg/b {
              :B :String
            }
            :use [ :pkg/a { #strip } :pkg/b { :B :AliasB } ]
            :use [
              // First package
              :pkg/a { :A :AliasA }
              // Second package
              :pkg/b {
                :B :MultiLineB
              }
            ]
            :Combined :Tuple(:A :AliasA :AliasB :MultiLineB)
          }
          :type :Combined
          :body (1 2 "x" "y")
        }""";

    String formatted = StvnSourceFormatter.format(raw);
    Assertions.assertEquals(expected, formatted);
    Assertions.assertEquals(expected, StvnSourceFormatter.format(formatted));
  }
}
