package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test suite for Redundant Metadata Facet Validation and Tautology Elimination
 * (SPEC § 5.1.1, § 5.5.3, § 6.1, § 9.2, Diagnostic Code E036 / ERR_REDUNDANT_METADATA_FACET).
 * <p>
 * Enforces compile-time rejection of tautological and redundant facet declarations to eliminate
 * preimage divergence and prevent CAS hash bifurcation under Value-Oriented Programming (VOP).
 */
public class StvnRedundantFacetValidationTest {

  @Test
  @DisplayName("TC-RED-01: Prohibits #minIncl 0 on unsigned :Int domain (Unsigned Floor Tautology)")
  void testRejectUnsignedIntMinInclZero() {
    String source = """
        {
          :defs {
            :MyType { #unsigned #minIncl 0 } :Int
          }
          :type :MyType
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected compilation failure for #minIncl 0 on unsigned :Int");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Expected ERR_REDUNDANT_METADATA_FACET diagnostic"));

    String targetToken = "#minIncl 0";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset(), "Start offset must pinpoint '#minIncl 0'");
    assertEquals(expectedEnd, diag.endOffset(), "End offset must cover '#minIncl 0'");
    assertTrue(diag.message().contains("facet '#minIncl 0' is redundant; '#unsigned' already bounds the domain to [0, \\infty)"));
  }

  @Test
  @DisplayName("TC-RED-02: Permits #minIncl 0 on signed :Int domain (Valid domain narrowing)")
  void testAcceptSignedIntMinInclZero() {
    String source = """
        {
          :defs {
            :NonNegativeInt { #minIncl 0 } :Int
          }
          :type :NonNegativeInt
          :body 0
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Signed :Int with #minIncl 0 narrows domain from (-inf, inf) to [0, inf) and must compile cleanly");
    assertTrue(result.document().isPresent());
  }

  @Test
  @DisplayName("TC-RED-03: Permits #minIncl 1 on unsigned :Int domain (Valid domain narrowing)")
  void testAcceptUnsignedIntMinInclOne() {
    String source = """
        {
          :defs {
            :PositiveUInt { #unsigned #minIncl 1 } :Int
          }
          :type :PositiveUInt
          :body 1
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Unsigned :Int with #minIncl 1 narrows domain to [1, inf) and must compile cleanly");
    assertTrue(result.document().isPresent());
  }

  @Test
  @DisplayName("TC-RED-04: Prohibits #minSize 0 on :String domain (String Cardinality Floor Tautology)")
  void testRejectStringMinSizeZero() {
    String source = """
        {
          :defs {
            :BadString { #minSize 0 } :String
          }
          :type :BadString
          :body "test"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected compilation failure for #minSize 0 on :String");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#minSize 0";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset(), "Start offset must pinpoint '#minSize 0'");
    assertEquals(expectedEnd, diag.endOffset(), "End offset must cover '#minSize 0'");
    assertTrue(diag.message().contains("string cardinality is inherently non-negative"));
  }

  @Test
  @DisplayName("TC-RED-05: Permits #minSize 1 on :String domain (Valid non-empty bound)")
  void testAcceptStringMinSizeOne() {
    String source = """
        {
          :defs {
            :NonEmptyString { #minSize 1 } :String
          }
          :type :NonEmptyString
          :body "hello"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Non-empty string bound #minSize 1 must compile cleanly");
    assertTrue(result.document().isPresent());
  }

  @Test
  @DisplayName("TC-RED-06: Prohibits #minSize 0 on :Seq domain (Collection Cardinality Tautology)")
  void testRejectSeqMinSizeZero() {
    String source = """
        {
          :defs {
            :BadSeq { #minSize 0 } :Seq( :Int )
          }
          :type :BadSeq
          :body [ 1 2 3 ]
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected compilation failure for #minSize 0 on :Seq");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#minSize 0";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset(), "Start offset must pinpoint '#minSize 0'");
    assertEquals(expectedEnd, diag.endOffset(), "End offset must cover '#minSize 0'");
    assertTrue(diag.message().contains("container cardinality is inherently non-negative"));
  }

  @Test
  @DisplayName("TC-RED-07: Prohibits #minSize 0 on :Set domain (Collection Cardinality Tautology)")
  void testRejectSetMinSizeZero() {
    String source = """
        {
          :defs {
            :BadSet { #minSize 0 } :Set( :Int )
          }
          :type :BadSet
          :body [ 1 2 3 ]
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected compilation failure for #minSize 0 on :Set");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#minSize 0";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset());
    assertEquals(expectedEnd, diag.endOffset());
    assertTrue(diag.message().contains("container cardinality is inherently non-negative"));
  }

  @Test
  @DisplayName("TC-RED-08: Prohibits #minSize 0 on :Map domain (Collection Cardinality Tautology)")
  void testRejectMapMinSizeZero() {
    String source = """
        {
          :defs {
            :BadMap { #minSize 0 } :Map( :String :Int )
          }
          :type :BadMap
          :body {
            [ "a" 1 ]
          }
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected compilation failure for #minSize 0 on :Map");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#minSize 0";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset());
    assertEquals(expectedEnd, diag.endOffset());
    assertTrue(diag.message().contains("container cardinality is inherently non-negative"));
  }

  @Test
  @DisplayName("TC-RED-09: Permits #minSize 1 on :Seq domain (Valid non-empty container bound)")
  void testAcceptSeqMinSizeOne() {
    String source = """
        {
          :defs {
            :NonEmptySeq { #minSize 1 } :Seq( :Int )
          }
          :type :NonEmptySeq
          :body [ 42 ]
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Non-empty collection bound #minSize 1 must compile cleanly");
    assertTrue(result.document().isPresent());
  }

  @Test
  @DisplayName("TC-RED-10: Prohibits bare #equatable #TRUE on :Int domain (Primitive Default Trait Re-assertion)")
  void testRejectIntEquatableTrue() {
    String source = """
        {
          :defs {
            :MyInt { #equatable #TRUE } :Int
          }
          :type :MyInt
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected failure for #equatable #TRUE on scalar primitive :Int");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#equatable #TRUE";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset(), "Start offset must pinpoint '#equatable #TRUE'");
    assertEquals(expectedEnd, diag.endOffset(), "End offset must cover '#equatable #TRUE'");
    assertTrue(diag.message().contains("scalar primitive ':Int' is intrinsically equatable"));
  }

  @Test
  @DisplayName("TC-RED-11: Permits #equatable #FALSE on :Int domain (Valid semantic override / opt-out)")
  void testAcceptIntEquatableFalse() {
    String source = """
        {
          :defs {
            :NonEquatableInt { #equatable #FALSE } :Int
          }
          :type :NonEquatableInt
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Disabling equality with #equatable #FALSE is a valid semantic override and must compile cleanly");
    assertTrue(result.document().isPresent());
  }

  @Test
  @DisplayName("TC-RED-12: Prohibits bare #comparable #TRUE on :Int domain (Primitive Default Trait Re-assertion)")
  void testRejectIntComparableTrue() {
    String source = """
        {
          :defs {
            :MyInt { #comparable #TRUE } :Int
          }
          :type :MyInt
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected failure for #comparable #TRUE on scalar primitive :Int");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#comparable #TRUE";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset());
    assertEquals(expectedEnd, diag.endOffset());
    assertTrue(diag.message().contains("scalar primitive ':Int' is intrinsically comparable"));
  }

  @Test
  @DisplayName("TC-RED-13: Prohibits bare #equatable #TRUE on :Boolean domain (Primitive Default Trait Re-assertion)")
  void testRejectBooleanEquatableTrue() {
    String source = """
        {
          :defs {
            :MyBool { #equatable #TRUE } :Boolean
          }
          :type :MyBool
          :body #TRUE
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected failure for #equatable #TRUE on scalar primitive :Boolean");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#equatable #TRUE";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset());
    assertEquals(expectedEnd, diag.endOffset());
    assertTrue(diag.message().contains("scalar primitive ':Boolean' is intrinsically equatable"));
  }

  @Test
  @DisplayName("TC-RED-14: Prohibits bare #comparable #TRUE on :Boolean domain (Primitive Default Trait Re-assertion)")
  void testRejectBooleanComparableTrue() {
    String source = """
        {
          :defs {
            :MyBool { #comparable #TRUE } :Boolean
          }
          :type :MyBool
          :body #TRUE
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected failure for #comparable #TRUE on scalar primitive :Boolean");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#comparable #TRUE";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset());
    assertEquals(expectedEnd, diag.endOffset());
    assertTrue(diag.message().contains("scalar primitive ':Boolean' is intrinsically comparable"));
  }

  @Test
  @DisplayName("TC-RED-15: Prohibits bare #equatable #TRUE on :String domain (Primitive Default Trait Re-assertion)")
  void testRejectStringEquatableTrue() {
    String source = """
        {
          :defs {
            :MyStr { #equatable #TRUE } :String
          }
          :type :MyStr
          :body "abc"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected failure for #equatable #TRUE on scalar primitive :String");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#equatable #TRUE";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset());
    assertEquals(expectedEnd, diag.endOffset());
    assertTrue(diag.message().contains("scalar primitive ':String' is intrinsically equatable"));
  }

  @Test
  @DisplayName("TC-RED-16: Prohibits bare #comparable #TRUE on :String domain (Primitive Default Trait Re-assertion)")
  void testRejectStringComparableTrue() {
    String source = """
        {
          :defs {
            :MyStr { #comparable #TRUE } :String
          }
          :type :MyStr
          :body "abc"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected failure for #comparable #TRUE on scalar primitive :String");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#comparable #TRUE";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset());
    assertEquals(expectedEnd, diag.endOffset());
    assertTrue(diag.message().contains("scalar primitive ':String' is intrinsically comparable"));
  }

  @Test
  @DisplayName("TC-RED-17: Permits #equatable #TRUE on continuous :Float domain (Valid opt-in override)")
  void testAcceptContinuousFloatEquatableTrue() {
    String source = """
        {
          :defs {
            :EquatableFloat { #equatable #TRUE } :Float
          }
          :type :EquatableFloat
          :body 3.14
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Opt-in equality on continuous float is a valid non-redundant override and must compile cleanly");
    assertTrue(result.document().isPresent());
  }

  @Test
  @DisplayName("TC-RED-18: Prohibits #equatable #FALSE on continuous :Float domain (Float Default Trait Re-assertion)")
  void testRejectContinuousFloatEquatableFalse() {
    String source = """
        {
          :defs {
            :BadFloat { #equatable #FALSE } :Float
          }
          :type :BadFloat
          :body 3.14
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected failure for #equatable #FALSE on continuous :Float");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#equatable #FALSE";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset());
    assertEquals(expectedEnd, diag.endOffset());
    assertTrue(diag.message().contains("continuous ':Float' is non-equatable by default"));
  }

  @Test
  @DisplayName("TC-RED-19: Prohibits #comparable #TRUE on continuous :Float domain (Float Default Trait Re-assertion)")
  void testRejectContinuousFloatComparableTrue() {
    String source = """
        {
          :defs {
            :BadFloat { #comparable #TRUE } :Float
          }
          :type :BadFloat
          :body 3.14
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected failure for #comparable #TRUE on continuous :Float");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#comparable #TRUE";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset());
    assertEquals(expectedEnd, diag.endOffset());
    assertTrue(diag.message().contains("continuous ':Float' is intrinsically comparable"));
  }

  @Test
  @DisplayName("TC-RED-20: Prohibits #equatable #TRUE on { #exact } :Float domain (Exact Float Default Re-assertion)")
  void testRejectExactFloatEquatableTrue() {
    String source = """
        {
          :defs {
            :BadExactFloat { #exact #equatable #TRUE } :Float
          }
          :type :BadExactFloat
          :body 42.0
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected failure for #equatable #TRUE on exact decimal :Float");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#equatable #TRUE";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset());
    assertEquals(expectedEnd, diag.endOffset());
    assertTrue(diag.message().contains("exact decimal float is intrinsically equatable"));
  }

  @Test
  @DisplayName("TC-RED-21: Prohibits #minIncl 0 on subtype inheriting #unsigned (Transitive Unsigned Floor Tautology)")
  void testRejectTransitiveUnsignedFloorTautology() {
    String source = """
        {
          :defs {
            :ParentUnsigned { #unsigned } :Int
            :Child { #minIncl 0 } :ParentUnsigned
          }
          :type :Child
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected failure for #minIncl 0 on child of #unsigned parent");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#minIncl 0";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset());
    assertEquals(expectedEnd, diag.endOffset());
    assertTrue(diag.message().contains("ancestor ':ParentUnsigned' already establishes '#unsigned'"));
  }

  @Test
  @DisplayName("TC-RED-22: Prohibits #equatable #TRUE on subtype inheriting equatable parent (Transitive Trait Re-assertion)")
  void testRejectTransitiveTraitReassertion() {
    String source = """
        {
          :defs {
            :Parent :Int
            :Child { #equatable #TRUE } :Parent
          }
          :type :Child
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected failure for #equatable #TRUE on child of equatable parent");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#equatable #TRUE";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset());
    assertEquals(expectedEnd, diag.endOffset());
    assertTrue(diag.message().contains("ancestor ':Parent' already resolves to equatable #TRUE"));
  }

  @Test
  @DisplayName("TC-RED-ANON: Prohibits redundant facets in anonymous inner schemas inside tuple constructors")
  void testRejectRedundantFacetInAnonymousInnerSchema() {
    String source = """
        {
          :defs {
            :IrEnum :Tuple( :String { #unsigned #minIncl 0 } :Int )
          }
          :type :IrEnum
          :body ( "Tag" 0 )
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Anonymous inner schema in tuple constructor must be validated for redundant facets");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#minIncl 0";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset());
    assertEquals(expectedEnd, diag.endOffset());
    assertTrue(diag.message().contains("facet '#minIncl 0' is redundant"));
  }

  @Test
  @DisplayName("TC-RED-CONST: Prohibits #minIncl #ZERO where #ZERO resolves to constant 0 on unsigned :Int")
  void testRejectRedundantFacetConstantReference() {
    String source = """
        {
          :defs {
            #ZERO :Int 0
            :MyType { #unsigned #minIncl #ZERO } :Int
          }
          :type :MyType
          :body 10
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Constant reference resolving to 0 on unsigned :Int must be rejected as redundant");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#minIncl #ZERO";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset());
    assertEquals(expectedEnd, diag.endOffset());
  }

  @Test
  @DisplayName("TC-SIZE-RED-01: Prohibits #size 32 on signed :Int domain (Signed Integer Sizing Tautology)")
  void testRejectSignedIntSizeThirtyTwo() {
    String source = """
        {
          :defs {
            :MyInt { #size 32 } :Int
          }
          :type :MyInt
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected compilation failure for #size 32 on signed :Int");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#size 32";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset(), "Start offset must pinpoint '#size 32'");
    assertEquals(expectedEnd, diag.endOffset(), "End offset must cover '#size 32'");
    assertTrue(diag.message().contains("facet '#size 32' is redundant; signed integer ':Int' intrinsically defaults to 32-bit storage"));
  }

  @Test
  @DisplayName("TC-SIZE-RED-02: Permits #size 64, 16, and 8 on signed :Int domain (Valid widenings/narrowings)")
  void testAcceptNonDefaultSignedIntSizes() {
    String source = """
        {
          :defs {
            :I64 { #size 64 } :Int
            :I16 { #size 16 } :Int
            :I8  { #size 8 } :Int
          }
          :type :Tuple( :I64 :I16 :I8 )
          :body ( 1000000000000 30000 120 )
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Non-default integer bit-widths (64, 16, 8) must compile cleanly");
    assertTrue(result.document().isPresent());
  }

  @Test
  @DisplayName("TC-SIZE-RED-05: Prohibits #unsigned #size 32 on :Int domain (Unsigned Integer Sizing Tautology)")
  void testRejectUnsignedIntSizeThirtyTwo() {
    String source = """
        {
          :defs {
            :MyUInt { #unsigned #size 32 } :Int
          }
          :type :MyUInt
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected compilation failure for #size 32 on unsigned :Int");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#size 32";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset(), "Start offset must pinpoint '#size 32'");
    assertEquals(expectedEnd, diag.endOffset(), "End offset must cover '#size 32'");
    assertTrue(diag.message().contains("facet '#size 32' is redundant; unsigned integer '{ #unsigned } :Int' intrinsically defaults to 32-bit storage"));
  }

  @Test
  @DisplayName("TC-SIZE-RED-05B: Permits unadorned { #unsigned } :Int with intrinsic 32-bit storage width")
  void testAcceptBareUnsignedIntThirtyTwoBitBounds() {
    String source = """
        {
          :defs {
            :MyUInt { #unsigned } :Int
          }
          :type :MyUInt
          :body 4294967295
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Unadorned unsigned integer must compile cleanly up to 2^32 - 1");
    assertTrue(result.document().isPresent());
  }

  @Test
  @DisplayName("TC-SIZE-RED-05C: Permits valid non-default bit-widths on unsigned integers")
  void testAcceptNonDefaultUnsignedIntSizes() {
    String source = """
        {
          :defs {
            :U64 { #unsigned #size 64 } :Int
            :U16 { #unsigned #size 16 } :Int
            :U8  { #unsigned #size 8 } :Int
            :U7  { #unsigned #size 7 } :Int
          }
          :type :Tuple( :U64 :U16 :U8 :U7 )
          :body ( 1000000000000 65000 250 120 )
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Non-default unsigned bit-widths (64, 16, 8, 7) must compile cleanly");
    assertTrue(result.document().isPresent());
  }

  @Test
  @DisplayName("TC-SIZE-RED-05D: Prohibits #size 32 on subtype deriving from unsigned parent (Transitive unsigned sizing tautology)")
  void testRejectTransitiveUnsignedIntSizeThirtyTwo() {
    String source = """
        {
          :defs {
            :ParentUInt { #unsigned } :Int
            :ChildUInt  { #size 32 } :ParentUInt
          }
          :type :ChildUInt
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected compilation failure for #size 32 on child of unsigned parent");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#size 32";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset());
    assertEquals(expectedEnd, diag.endOffset());
    assertTrue(diag.message().contains("facet '#size 32' is redundant; unsigned integer '{ #unsigned } :Int' intrinsically defaults to 32-bit storage"));
  }

  @Test
  @DisplayName("TC-SIZE-RED-06: Prohibits #size 64 on continuous :Float domain (Continuous Float Sizing Tautology)")
  void testRejectContinuousFloatSizeSixtyFour() {
    String source = """
        {
          :defs {
            :MyFloat { #size 64 } :Float
          }
          :type :MyFloat
          :body 3.141592653589793
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected compilation failure for #size 64 on continuous :Float");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#size 64";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset(), "Start offset must pinpoint '#size 64'");
    assertEquals(expectedEnd, diag.endOffset(), "End offset must cover '#size 64'");
    assertTrue(diag.message().contains("facet '#size 64' is redundant; continuous ':Float' intrinsically defaults to 64-bit IEEE-754 double precision"));
  }

  @Test
  @DisplayName("TC-SIZE-RED-07: Permits #size 32 on continuous :Float domain (Valid single-precision narrowing)")
  void testAcceptContinuousFloatSizeThirtyTwo() {
    String source = """
        {
          :defs {
            :SingleFloat { #size 32 } :Float
          }
          :type :SingleFloat
          :body 3.14
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Single precision #size 32 on continuous float must compile cleanly");
    assertTrue(result.document().isPresent());
  }

  @Test
  @DisplayName("TC-SIZE-RED-08: Permits #maxSize 16777216000000 on :String (Unbounded facet parameter)")
  void testAcceptUnboundedStringMaxSize() {
    String source = """
        {
          :defs {
            :HugeString { #maxSize 16777216000000 } :String
          }
          :type :HugeString
          :body "unbounded capacity test"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Unbounded string maxSize parameter must compile cleanly");
    assertTrue(result.document().isPresent());
  }

  @Test
  @DisplayName("TC-SIZE-RED-09: Prohibits #size 32 on subtype deriving from bare :Int (Transitive sizing tautology)")
  void testRejectTransitiveSignedIntSizeThirtyTwo() {
    String source = """
        {
          :defs {
            :Parent :Int
            :Child { #size 32 } :Parent
          }
          :type :Child
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected compilation failure for #size 32 on child of bare :Int");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#size 32";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset());
    assertEquals(expectedEnd, diag.endOffset());
    assertTrue(diag.message().contains("facet '#size 32' is redundant; signed integer ':Int' intrinsically defaults to 32-bit storage"));
  }

  @Test
  @DisplayName("TC-SIZE-RED-10: Prohibits #size 64 on subtype deriving from bare :Float (Transitive float sizing tautology)")
  void testRejectTransitiveFloatSizeSixtyFour() {
    String source = """
        {
          :defs {
            :ParentFloat :Float
            :Child { #size 64 } :ParentFloat
          }
          :type :Child
          :body 3.141592653589793
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected compilation failure for #size 64 on child of bare :Float");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#size 64";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset());
    assertEquals(expectedEnd, diag.endOffset());
    assertTrue(diag.message().contains("facet '#size 64' is redundant; continuous ':Float' intrinsically defaults to 64-bit IEEE-754 double precision"));
  }

  @Test
  @DisplayName("TC-SIZE-RED-11: Prohibits #size #SZ32 where #SZ32 resolves to constant 32 on signed :Int")
  void testRejectRedundantSizeConstantReference() {
    String source = """
        {
          :defs {
            #SZ32 :Int 32
            :MyType { #size #SZ32 } :Int
          }
          :type :MyType
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Constant reference resolving to 32 on signed :Int must be rejected as redundant");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#size #SZ32";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset());
    assertEquals(expectedEnd, diag.endOffset());
    assertTrue(diag.message().contains("facet '#size 32' is redundant; signed integer ':Int' intrinsically defaults to 32-bit storage"));
  }

  @Test
  @DisplayName("TC-EXACT-RED-01: Prohibits #maxSize 16777216 on { #exact } :Float (Exact Float Sizing Tautology)")
  void testRejectExactFloatMaxSize16M() {
    String source = """
        {
          :defs {
            :BadExact { #exact #maxSize 16777216 } :Float
          }
          :type :BadExact
          :body 123.45
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected compilation failure for #maxSize 16777216 on { #exact } :Float");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Expected ERR_REDUNDANT_METADATA_FACET diagnostic"));

    String targetToken = "#maxSize 16777216";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset(), "Start offset must pinpoint '#maxSize 16777216'");
    assertEquals(expectedEnd, diag.endOffset(), "End offset must cover '#maxSize 16777216'");
    assertTrue(diag.message().contains("facet '#maxSize 16777216' is redundant"));
  }

  @Test
  @DisplayName("TC-EXACT-RED-02: Permits #maxSize 18 and #maxSize 38 on { #exact } :Float (Valid domain narrowings)")
  void testAcceptExactFloatMaxSizeNarrowings() {
    String source = """
        {
          :defs {
            :CurrencyAmount { #exact #maxSize 18 } :Float
            :DecimalPrecise { #exact #maxSize 38 } :Float
          }
          :type :Tuple( :CurrencyAmount :DecimalPrecise )
          :body ( 123.45 678.9012 )
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Exact float narrowings must compile cleanly");
    assertTrue(result.document().isPresent());
  }

  @Test
  @DisplayName("TC-EXACT-RED-03: Permits #maxSize 100000000 on { #exact } :Float (Valid domain widening)")
  void testAcceptExactFloatMaxSizeWidening() {
    String source = """
        {
          :defs {
            :SuperPrecise { #exact #maxSize 100000000 } :Float
          }
          :type :SuperPrecise
          :body 3.141592653589793238462643383279
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Exact float widening must compile cleanly");
    assertTrue(result.document().isPresent());
  }

  @Test
  @DisplayName("TC-EXACT-RED-04: Prohibits #maxSize on continuous :Float without #exact (E008)")
  void testRejectMaxSizeOnContinuousFloat() {
    String source = """
        {
          :defs {
            :BadFloat { #maxSize 18 } :Float
          }
          :type :BadFloat
          :body 1.5
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected compilation failure for #maxSize on continuous :Float");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_INVALID_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Expected ERR_INVALID_METADATA_FACET diagnostic"));

    String targetToken = "#maxSize 18";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset(), "Start offset must pinpoint '#maxSize 18'");
    assertEquals(expectedEnd, diag.endOffset(), "End offset must cover '#maxSize 18'");
  }
}
