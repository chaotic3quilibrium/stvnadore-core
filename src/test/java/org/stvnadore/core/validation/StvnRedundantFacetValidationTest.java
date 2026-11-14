package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test suite for Redundant Metadata Facet Validation and Tautology Elimination
 * (SPEC § 5.1.1, § 5.5.3, § 6.1, § 9.2, Diagnostic Code W003 / WARN_REDUNDANT_METADATA_FACET).
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
    assertFalse(result.hasErrors(), "Expected compilation failure for #minIncl 0 on unsigned :Int");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Expected WARN_REDUNDANT_METADATA_FACET diagnostic"));

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
    assertFalse(result.hasErrors(), "Expected compilation failure for #minSize 0 on :String");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
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
    assertFalse(result.hasErrors(), "Expected compilation failure for #minSize 0 on :Seq");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
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
    assertFalse(result.hasErrors(), "Expected compilation failure for #minSize 0 on :Set");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
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
    assertFalse(result.hasErrors(), "Expected compilation failure for #minSize 0 on :Map");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
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
    assertFalse(result.hasErrors(), "Expected failure for #equatable #TRUE on scalar primitive :Int");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
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
    assertFalse(result.hasErrors(), "Expected failure for #comparable #TRUE on scalar primitive :Int");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
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
    assertFalse(result.hasErrors(), "Expected failure for #equatable #TRUE on scalar primitive :Boolean");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
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
    assertFalse(result.hasErrors(), "Expected failure for #comparable #TRUE on scalar primitive :Boolean");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
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
    assertFalse(result.hasErrors(), "Expected failure for #equatable #TRUE on scalar primitive :String");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
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
    assertFalse(result.hasErrors(), "Expected failure for #comparable #TRUE on scalar primitive :String");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
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
    assertFalse(result.hasErrors(), "Expected failure for #equatable #FALSE on continuous :Float");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
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
    assertFalse(result.hasErrors(), "Expected failure for #comparable #TRUE on continuous :Float");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
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
    assertFalse(result.hasErrors(), "Expected failure for #equatable #TRUE on exact decimal :Float");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
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
    assertFalse(result.hasErrors(), "Expected failure for #minIncl 0 on child of #unsigned parent");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
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
    assertFalse(result.hasErrors(), "Expected failure for #equatable #TRUE on child of equatable parent");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#equatable #TRUE";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset());
    assertEquals(expectedEnd, diag.endOffset());
    assertTrue(diag.message().contains("ancestor ':Parent' already establishes '#equatable #TRUE'"));
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
    assertFalse(result.hasErrors(), "Anonymous inner schema in tuple constructor must be validated for redundant facets");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
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
    assertFalse(result.hasErrors(), "Constant reference resolving to 0 on unsigned :Int must be rejected as redundant");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
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
    assertFalse(result.hasErrors(), "Expected compilation failure for #size 32 on signed :Int");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
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
    assertFalse(result.hasErrors(), "Expected compilation failure for #size 32 on unsigned :Int");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
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
  @DisplayName("TC-SIZE-RED-05D: Prohibits #size 32 on subtype deriving from unsigned parent with E040 (Intrinsic default #size 32 locked on :ParentUInt)")
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
    assertTrue(result.hasErrors(), "Expected E040 compilation failure for #size 32 on child of unsigned nominal parent");
    assertFalse(result.hasWarnings(), "Must not emit W003 when E040 fires on non-overridable #size");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_NON_OVERRIDABLE_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#size 32";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset());
    assertEquals(expectedEnd, diag.endOffset());
    assertTrue(diag.message().contains("non-overridable definition facet '#size' is already established by ancestor :ParentUInt and cannot be re-specified"));
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
    assertFalse(result.hasErrors(), "Expected compilation failure for #size 64 on continuous :Float");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
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
  @DisplayName("TC-SIZE-RED-09: Prohibits #size 32 on subtype deriving from bare :Int nominal alias with E040 (Intrinsic default #size 32 locked on :Parent)")
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
    assertTrue(result.hasErrors(), "Expected E040 compilation failure for #size 32 on child of bare :Int nominal alias");
    assertFalse(result.hasWarnings(), "Must not emit W003 when E040 fires on non-overridable #size");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_NON_OVERRIDABLE_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#size 32";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset());
    assertEquals(expectedEnd, diag.endOffset());
    assertTrue(diag.message().contains("non-overridable definition facet '#size' is already established by ancestor :Parent and cannot be re-specified"));
  }

  @Test
  @DisplayName("TC-SIZE-RED-10: Prohibits #size 64 on subtype deriving from bare :Float nominal alias with E040 (Intrinsic default #size 64 locked on :ParentFloat)")
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
    assertTrue(result.hasErrors(), "Expected E040 compilation failure for #size 64 on child of bare :Float nominal alias");
    assertFalse(result.hasWarnings(), "Must not emit W003 when E040 fires on non-overridable #size");

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_NON_OVERRIDABLE_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#size 64";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset());
    assertEquals(expectedEnd, diag.endOffset());
    assertTrue(diag.message().contains("non-overridable definition facet '#size' is already established by ancestor :ParentFloat and cannot be re-specified"));
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
    assertFalse(result.hasErrors(), "Constant reference resolving to 32 on signed :Int must be rejected as redundant");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
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
    assertFalse(result.hasErrors(), "Expected compilation failure for #maxSize 16777216 on { #exact } :Float");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Expected WARN_REDUNDANT_METADATA_FACET diagnostic"));

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

  // ==============================================================================
  // DYNAMIC BIT-WIDTH BOUNDARY TAUTOLOGY TESTS (RULE W003)
  // ==============================================================================

  @Test
  @DisplayName("TC-DYN-RED-01: Prohibits #maxExcl 64 on #unsigned #size 6 :Int (6-bit Unsigned Upper Bound Tautology)")
  void testRejectUnsignedSize6MaxExcl64() {
    String source = """
        {
          :defs {
            :SquareIndex { #unsigned #size 6 #maxExcl 64 } :Int
          }
          :type :SquareIndex
          :body 10
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Explicit #maxExcl 64 on #unsigned #size 6 must be rejected as redundant");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#maxExcl 64";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset(), "Start offset must pinpoint '#maxExcl 64'");
    assertEquals(expectedEnd, diag.endOffset(), "End offset must cover '#maxExcl 64'");
    assertEquals("Constraint violation (:SquareIndex): facet '#maxExcl 64' is redundant; '#unsigned #size 6' already bounds the domain to [0, 64)", diag.message());
  }

  @Test
  @DisplayName("TC-DYN-RED-02: Permits #maxExcl 63 on #unsigned #size 6 :Int (Valid domain narrowing)")
  void testAcceptUnsignedSize6MaxExcl63() {
    String source = """
        {
          :defs {
            :SquareIndex { #unsigned #size 6 #maxExcl 63 } :Int
          }
          :type :SquareIndex
          :body 10
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Narrowing bound #maxExcl 63 on 6-bit unsigned integer must compile cleanly");
  }

  @Test
  @DisplayName("TC-DYN-RED-03: Prohibits #maxExcl 4294967296 on default { #unsigned } :Int (32-bit Unsigned Default Tautology)")
  void testRejectDefaultUnsignedMaxExcl2Pow32() {
    String source = """
        {
          :defs {
            :MyUInt { #unsigned #maxExcl 4294967296 } :Int
          }
          :type :MyUInt
          :body 10
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Explicit #maxExcl 4294967296 on default unsigned integer must be rejected as redundant");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    assertEquals("Constraint violation (:MyUInt): facet '#maxExcl 4294967296' is redundant; '#unsigned #size 32' already bounds the domain to [0, 4294967296)", diag.message());
  }

  @Test
  @DisplayName("TC-DYN-RED-04: Prohibits #minIncl -32768 on { #size 16 } :Int (16-bit Signed Lower Bound Tautology)")
  void testRejectSignedSize16MinInclMinus32768() {
    String source = """
        {
          :defs {
            :ShortInt { #size 16 #minIncl -32768 } :Int
          }
          :type :ShortInt
          :body 0
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Explicit #minIncl -32768 on 16-bit signed integer must be rejected as redundant");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#minIncl -32768";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset());
    assertEquals(expectedEnd, diag.endOffset());
    assertEquals("Constraint violation (:ShortInt): facet '#minIncl -32768' is redundant; '#size 16' already bounds the minimum value to -32768", diag.message());
  }

  @Test
  @DisplayName("TC-DYN-RED-05: Prohibits #maxExcl 32768 on { #size 16 } :Int (16-bit Signed Upper Bound Tautology)")
  void testRejectSignedSize16MaxExcl32768() {
    String source = """
        {
          :defs {
            :ShortInt { #size 16 #maxExcl 32768 } :Int
          }
          :type :ShortInt
          :body 0
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Explicit #maxExcl 32768 on 16-bit signed integer must be rejected as redundant");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    assertEquals("Constraint violation (:ShortInt): facet '#maxExcl 32768' is redundant; '#size 16' already bounds the maximum exclusive bound to 32768", diag.message());
  }

  @Test
  @DisplayName("TC-DYN-RED-06: Prohibits #minIncl -2147483648 on default signed :Int (32-bit Signed Default Lower Tautology)")
  void testRejectDefaultSignedMinInclMinus2Pow31() {
    String source = """
        {
          :defs {
            :MyInt { #minIncl -2147483648 } :Int
          }
          :type :MyInt
          :body 0
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Explicit #minIncl -2147483648 on default signed integer must be rejected as redundant");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    assertEquals("Constraint violation (:MyInt): facet '#minIncl -2147483648' is redundant; '#size 32' already bounds the minimum value to -2147483648", diag.message());
  }

  @Test
  @DisplayName("TC-DYN-RED-07: Prohibits #maxExcl 2147483648 on default signed :Int (32-bit Signed Default Upper Tautology)")
  void testRejectDefaultSignedMaxExcl2Pow31() {
    String source = """
        {
          :defs {
            :MyInt { #maxExcl 2147483648 } :Int
          }
          :type :MyInt
          :body 0
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Explicit #maxExcl 2147483648 on default signed integer must be rejected as redundant");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    assertEquals("Constraint violation (:MyInt): facet '#maxExcl 2147483648' is redundant; '#size 32' already bounds the maximum exclusive bound to 2147483648", diag.message());
  }

  @Test
  @DisplayName("TC-DYN-RED-08: Prohibits #maxExcl 18446744073709551616 on { #unsigned #size 64 } :Int (64-bit Unsigned Upper Bound Tautology exceeding Long.MAX_VALUE)")
  void testRejectUnsignedSize64MaxExcl2Pow64() {
    String source = """
        {
          :defs {
            :U64 { #unsigned #size 64 #maxExcl 18446744073709551616 } :Int
          }
          :type :U64
          :body 100
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "2^64 upper bound exceeding Long.MAX_VALUE must be rejected as redundant via BigInteger");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    assertEquals("Constraint violation (:U64): facet '#maxExcl 18446744073709551616' is redundant; '#unsigned #size 64' already bounds the domain to [0, 18446744073709551616)", diag.message());
  }

  @Test
  @DisplayName("TC-DYN-RED-09: Prohibits #maxExcl 2 and #minIncl -1 on 1-bit signed integer (Edge case N = 1)")
  void testRejectOneBitIntegerTautologies() {
    String signedSource = """
        {
          :defs {
            :SignedBitMin { #size 1 #minIncl -1 } :Int
            :SignedBitMax { #size 1 #maxExcl 1 } :Int
            :UnsignedBitMax { #unsigned #size 1 #maxExcl 2 } :Int
          }
          :type :Tuple( :SignedBitMin :SignedBitMax :UnsignedBitMax )
          :body ( 0 0 0 )
        }
        """;
    var result = StvnCompiler.compileToResult(signedSource, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "1-bit boundary tautologies must be rejected");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());
    assertEquals(3, result.diagnostics().stream().filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null))).count());
  }

  @Test
  @DisplayName("TC-DYN-RED-10: Prohibits constant-referenced upper bound matching 2^N")
  void testRejectConstantReferencedMaxExclTautology() {
    String source = """
        {
          :defs {
            #LIMIT :Int 256
            :ByteType { #unsigned #size 8 #maxExcl #LIMIT } :Int
          }
          :type :ByteType
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Constant-referenced upper bound 256 on 8-bit unsigned integer must be rejected as redundant");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    String targetToken = "#maxExcl #LIMIT";
    int expectedStart = source.indexOf(targetToken);
    int expectedEnd = expectedStart + targetToken.length();
    assertEquals(expectedStart, diag.startOffset());
    assertEquals(expectedEnd, diag.endOffset());
    assertEquals("Constraint violation (:ByteType): facet '#maxExcl 256' is redundant; '#unsigned #size 8' already bounds the domain to [0, 256)", diag.message());
  }

  @Test
  @DisplayName("TC-DYN-RED-11: Prohibits transitive inheritance upper bound matching parent bit-width 2^N")
  void testRejectTransitiveInheritedMaxExclTautology() {
    String source = """
        {
          :defs {
            :ParentPort { #unsigned #size 16 } :Int
            :ChildPort  { #maxExcl 65536 } :ParentPort
          }
          :type :ChildPort
          :body 80
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Subtype restating parent's 16-bit storage upper bound must be rejected as redundant");
    assertTrue(result.hasWarnings());
    assertTrue(result.isSuccess());

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();

    assertEquals("Constraint violation (:ChildPort): facet '#maxExcl 65536' is redundant; '#unsigned #size 16' already bounds the domain to [0, 65536)", diag.message());
  }

  @Test
  @DisplayName("TC-DYN-RED-12: Capacity overflow on bounds exceeding physical limit is not misclassified as redundant")
  void testCapacityOverflowNotMisclassifiedAsRedundant() {
    String source = """
        {
          :defs {
            :BadUInt { #unsigned #size 8 #maxExcl 257 } :Int
          }
          :type :BadUInt
          :body 10
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors());
    assertTrue(result.diagnostics().stream().anyMatch(d -> DiagnosticBag.ERR_INTEGER_OVERFLOW.equals(d.errorCode().orElse(null)) || DiagnosticBag.ERR_CAPACITY_OVERFLOW.equals(d.errorCode().orElse(null))),
        "Out of bounds value must emit ERR_CAPACITY_OVERFLOW, not WARN_REDUNDANT_METADATA_FACET");
    assertFalse(result.diagnostics().stream().anyMatch(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null))));
  }

  @Test
  @DisplayName("TC-DYN-RED-13: Valid narrowings on 16-bit signed integer compile cleanly")
  void testAcceptSignedSize16ValidNarrowings() {
    String source = """
        {
          :defs {
            :NarrowSigned { #size 16 #minIncl -32767 #maxExcl 32767 } :Int
          }
          :type :NarrowSigned
          :body 0
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Valid narrowings must compile cleanly with zero diagnostics");
  }
  @Test
  @DisplayName("TC-BIJECT-01: Proves :SquareIndexA and :SquareIndexB produce bitwise-identical CAS hashes")
  void testSquareIndexBijectivityCasEquivalence() throws Exception {
    String schemaA = """
        {
          :meta {
            #name "square_index"
            #domain "gaming"
            #kind #DEFS
          }
          :defs {
            :SquareIndex { #unsigned #size 6 } :Int
          }
        }
        """;

    String schemaB = """
        {
          :meta {
            #name "square_index"
            #domain "gaming"
            #kind #DEFS
          }
          :defs {
            :SquareIndex { #unsigned #size 6 #maxExcl 64 } :Int
          }
        }
        """;

    // 1. Compile schema A (clean)
    var resA = StvnCompiler.compileToResult(schemaA, "square_index.gaming.stvn_d", StvnParserConfig.DEFAULT);
    assertFalse(resA.hasErrors(), "Schema A must compile without errors");
    assertFalse(resA.hasWarnings(), "Schema A has no warnings");
    assertTrue(resA.isSuccess());

    // 2. Compile schema B (with redundant #maxExcl 64)
    var resB = StvnCompiler.compileToResult(schemaB, "square_index.gaming.stvn_d", StvnParserConfig.DEFAULT);
    assertFalse(resB.hasErrors(), "Schema B must compile without errors under W003");
    assertTrue(resB.hasWarnings(), "Schema B must report W003 warning");
    assertTrue(resB.isSuccess());

    var warnB = resB.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();
    assertEquals(org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity.WARNING, warnB.severity());

    // 3. Flatten both schemas
    String flatA = org.stvnadore.core.StvnSchemaFlattener.flatten(java.util.Map.of("square_index.gaming.stvn_d", schemaA), "square_index.gaming.stvn_d");
    String flatB = org.stvnadore.core.StvnSchemaFlattener.flatten(java.util.Map.of("square_index.gaming.stvn_d", schemaB), "square_index.gaming.stvn_d");

    // 4. Assert bitwise-identical canonical preimages
    assertEquals(flatA, flatB, "Preimages must be bitwise identical after IR normalization");

    // 5. Compute SHA-256 CAS addresses
    var digest = java.security.MessageDigest.getInstance("SHA-256");
    String hashA = java.util.HexFormat.of().formatHex(digest.digest(flatA.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    digest.reset();
    String hashB = java.util.HexFormat.of().formatHex(digest.digest(flatB.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

    assertEquals(hashA, hashB, "CAS hashes must be bitwise identical under Invariant 6");
  }

  @Test
  @DisplayName("TC-BIJECT-02: String #preserveIndent (#TRUE/#FALSE), continuous bounds, and #regex nominal overrides emit W003 and preserve CAS bijectivity")
  void testStringNominalOverridesAndPreserveIndentBijectivity() throws Exception {
    String cleanSchema = """
        {
          :meta {
            #name "str_biject"
            #domain "core"
            #kind #DEFS
          }
          :defs {
            :ParentHalfOpen { #minIncl "aaa" #maxExcl "zzz" #preserveIndent #TRUE #regex "^[a-z]+$" } :String
            :ChildHalfOpen :ParentHalfOpen
            :ParentOpenClosed { #minExcl "a" #maxIncl "z" } :String
            :ChildOpenClosed :ParentOpenClosed
          }
        }
        """;

    String redundantSchema = """
        {
          :meta {
            #name "str_biject"
            #domain "core"
            #kind #DEFS
          }
          :defs {
            :ParentHalfOpen { #minIncl "aaa" #maxExcl "zzz" #preserveIndent #TRUE #regex "^[a-z]+$" } :String
            :ChildHalfOpen { #minIncl "aaa" #maxExcl "zzz" #preserveIndent #TRUE #regex "^[a-z]+$" } :ParentHalfOpen
            :ParentOpenClosed { #minExcl "a" #maxIncl "z" } :String
            :ChildOpenClosed { #minExcl "a" #maxIncl "z" #preserveIndent #FALSE } :ParentOpenClosed
          }
        }
        """;

    var resClean = StvnCompiler.compileToResult(cleanSchema, "str_biject.core.stvn_d", StvnParserConfig.DEFAULT);
    assertFalse(resClean.hasErrors());
    assertFalse(resClean.hasWarnings());

    var resRedundant = StvnCompiler.compileToResult(redundantSchema, "str_biject.core.stvn_d", StvnParserConfig.DEFAULT);
    assertFalse(resRedundant.hasErrors());
    assertEquals(7, resRedundant.diagnostics().size());
    assertTrue(resRedundant.diagnostics().stream().allMatch(
        d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null))
    ));

    var preserveTrueDiag = resRedundant.diagnostics().get(2);
    String preserveTrueTok = "#preserveIndent #TRUE";
    int childHalfOpenIdx = redundantSchema.indexOf(":ChildHalfOpen");
    int expectedTrueStart = redundantSchema.indexOf(preserveTrueTok, childHalfOpenIdx);
    assertEquals(expectedTrueStart, preserveTrueDiag.startOffset());
    assertEquals(expectedTrueStart + preserveTrueTok.length(), preserveTrueDiag.endOffset());
    assertEquals(
        "Constraint violation (:ChildHalfOpen): facet '#preserveIndent #TRUE' is redundant; ancestor ':ParentHalfOpen' already establishes '#preserveIndent #TRUE'",
        preserveTrueDiag.message()
    );

    var preserveFalseDiag = resRedundant.diagnostics().get(6);
    String preserveFalseTok = "#preserveIndent #FALSE";
    int childOpenClosedIdx = redundantSchema.indexOf(":ChildOpenClosed");
    int expectedFalseStart = redundantSchema.indexOf(preserveFalseTok, childOpenClosedIdx);
    assertEquals(expectedFalseStart, preserveFalseDiag.startOffset());
    assertEquals(expectedFalseStart + preserveFalseTok.length(), preserveFalseDiag.endOffset());
    assertEquals(
        "Constraint violation (:ChildOpenClosed): facet '#preserveIndent #FALSE' is redundant; ancestor ':ParentOpenClosed' already establishes '#preserveIndent #FALSE'",
        preserveFalseDiag.message()
    );

    assertDocumentBijectivity(cleanSchema, redundantSchema, "str_biject.core.stvn_d");
  }

  @Test
  @DisplayName("TC-BIJECT-03: Float continuous bounds, exact decimal scale-distinct bounds (0.00 vs 0.0), and #exact #comparable #TRUE emit W003 and preserve CAS bijectivity")
  void testFloatNominalOverridesAndExactDecimalScaleBijectivity() throws Exception {
    String cleanSchema = """
        {
          :meta {
            #name "float_biject"
            #domain "core"
            #kind #DEFS
          }
          :defs {
            :RootExact { #exact } :Float
            :ParentIncl { #minIncl 0.0 #maxIncl 100.5 } :Float
            :ChildIncl :ParentIncl
            :ParentExcl { #minExcl -1.5 #maxExcl 9.5 } :Float
            :ChildExcl :ParentExcl
            :ParentExact { #exact #minIncl 0.0 #maxExcl 1000.0 } :Float
            :ChildExact :ParentExact
          }
        }
        """;

    String redundantSchema = """
        {
          :meta {
            #name "float_biject"
            #domain "core"
            #kind #DEFS
          }
          :defs {
            :RootExact { #exact #comparable #TRUE } :Float
            :ParentIncl { #minIncl 0.0 #maxIncl 100.5 } :Float
            :ChildIncl { #minIncl 0.0 #maxIncl 100.5 } :ParentIncl
            :ParentExcl { #minExcl -1.5 #maxExcl 9.5 } :Float
            :ChildExcl { #minExcl -1.5 #maxExcl 9.5 } :ParentExcl
            :ParentExact { #exact #minIncl 0.0 #maxExcl 1000.0 } :Float
            :ChildExact { #minIncl 0.00 #maxExcl 1000.0 } :ParentExact
          }
        }
        """;

    var resRedundant = StvnCompiler.compileToResult(redundantSchema, "float_biject.core.stvn_d", StvnParserConfig.DEFAULT);
    assertFalse(resRedundant.hasErrors());
    assertEquals(7, resRedundant.diagnostics().size());
    assertEquals(
        "Constraint violation (:RootExact): facet '#comparable #TRUE' is redundant; exact decimal float is intrinsically comparable",
        resRedundant.diagnostics().get(0).message()
    );
    assertEquals(
        "Constraint violation (:ChildExact): facet '#minIncl 0.00' is redundant; ancestor ':ParentExact' already establishes '#minIncl 0.0'",
        resRedundant.diagnostics().get(5).message()
    );

    assertDocumentBijectivity(cleanSchema, redundantSchema, "float_biject.core.stvn_d");
  }

  @Test
  @DisplayName("TC-BIJECT-04: TimeEpoch nominal #minIncl and #maxExcl overrides emit W003 and preserve CAS bijectivity")
  void testTimeEpochNominalOverridesBijectivity() throws Exception {
    String cleanSchema = """
        {
          :meta {
            #name "epoch_biject"
            #domain "core"
            #kind #DEFS
          }
          :defs {
            :ParentEpoch { #s #minIncl 1700000000 #maxExcl 1800000000 } :TimeEpoch
            :ChildEpoch :ParentEpoch
          }
        }
        """;

    String redundantSchema = """
        {
          :meta {
            #name "epoch_biject"
            #domain "core"
            #kind #DEFS
          }
          :defs {
            :ParentEpoch { #s #minIncl 1700000000 #maxExcl 1800000000 } :TimeEpoch
            :ChildEpoch { #minIncl 1700000000 #maxExcl 1800000000 } :ParentEpoch
          }
        }
        """;

    var resRedundant = StvnCompiler.compileToResult(redundantSchema, "epoch_biject.core.stvn_d", StvnParserConfig.DEFAULT);
    assertFalse(resRedundant.hasErrors());
    assertEquals(2, resRedundant.diagnostics().size());
    assertEquals(
        "Constraint violation (:ChildEpoch): facet '#minIncl 1700000000' is redundant; ancestor ':ParentEpoch' already establishes '#minIncl 1700000000'",
        resRedundant.diagnostics().get(0).message()
    );
    assertEquals(
        "Constraint violation (:ChildEpoch): facet '#maxExcl 1800000000' is redundant; ancestor ':ParentEpoch' already establishes '#maxExcl 1800000000'",
        resRedundant.diagnostics().get(1).message()
    );

    assertDocumentBijectivity(cleanSchema, redundantSchema, "epoch_biject.core.stvn_d");
  }

  @Test
  @DisplayName("TC-BIJECT-05: DateTime lexical and cross-offset (+00:00 vs Z) nominal overrides emit W003 and preserve CAS bijectivity")
  void testDateTimeNominalOverridesAndUtcOffsetEquivalenceBijectivity() throws Exception {
    String cleanSchema = """
        {
          :meta {
            #name "dt_biject"
            #domain "core"
            #kind #DEFS
          }
          :defs {
            :ParentDt { #offset #minIncl "2026-01-01T00:00:00Z" #maxExcl "2027-01-01T00:00:00Z" } :DateTime
            :ChildDt :ParentDt
          }
        }
        """;

    String redundantSchema = """
        {
          :meta {
            #name "dt_biject"
            #domain "core"
            #kind #DEFS
          }
          :defs {
            :ParentDt { #offset #minIncl "2026-01-01T00:00:00Z" #maxExcl "2027-01-01T00:00:00Z" } :DateTime
            :ChildDt { #minIncl "2026-01-01T00:00:00Z" #maxExcl "2027-01-01T00:00:00+00:00" } :ParentDt
          }
        }
        """;

    var resRedundant = StvnCompiler.compileToResult(redundantSchema, "dt_biject.core.stvn_d", StvnParserConfig.DEFAULT);
    assertFalse(resRedundant.hasErrors());
    assertEquals(2, resRedundant.diagnostics().size());
    assertEquals(
        "Constraint violation (:ChildDt): facet '#minIncl \"2026-01-01T00:00:00Z\"' is redundant; ancestor ':ParentDt' already establishes '#minIncl \"2026-01-01T00:00:00Z\"'",
        resRedundant.diagnostics().get(0).message()
    );
    assertEquals(
        "Constraint violation (:ChildDt): facet '#maxExcl \"2027-01-01T00:00:00+00:00\"' is redundant; ancestor ':ParentDt' already establishes '#maxExcl \"2027-01-01T00:00:00Z\"'",
        resRedundant.diagnostics().get(1).message()
    );

    assertDocumentBijectivity(cleanSchema, redundantSchema, "dt_biject.core.stvn_d");
  }

  @Test
  @DisplayName("TC-BIJECT-06: EnumSubset 1-hop, 2-hop, and non-equatable identity #filterIncl overrides emit canonical zero-bracket-padded W003 and preserve CAS bijectivity")
  void testEnumIdentityFilterInclOverridesAndTraitPreservationBijectivity() throws Exception {
    String cleanSchema = """
        {
          :meta {
            #name "enum_biject"
            #domain "core"
            #kind #DEFS
          }
          :defs {
            :ParentStatus :Enum [ #PENDING #ACTIVE #CLOSED ]
            :ChildStatusAll :ParentStatus
            :MidStatusOpen { #filterExcl [ #CLOSED ] } :ParentStatus
            :LeafStatusOpen :MidStatusOpen
            :NonEqParent { #equatable #FALSE } :Enum [ #ALPHA #BETA ]
            :NonEqChild :NonEqParent
          }
        }
        """;

    String redundantSchema = """
        {
          :meta {
            #name "enum_biject"
            #domain "core"
            #kind #DEFS
          }
          :defs {
            :ParentStatus :Enum [ #PENDING #ACTIVE #CLOSED ]
            :ChildStatusAll { #filterIncl [ #PENDING #ACTIVE #CLOSED ] } :ParentStatus
            :MidStatusOpen { #filterExcl [ #CLOSED ] } :ParentStatus
            :LeafStatusOpen { #filterIncl [ #PENDING #ACTIVE ] } :MidStatusOpen
            :NonEqParent { #equatable #FALSE } :Enum [ #ALPHA #BETA ]
            :NonEqChild { #filterIncl [ #ALPHA #BETA ] } :NonEqParent
          }
        }
        """;

    var resRedundant = StvnCompiler.compileToResult(redundantSchema, "enum_biject.core.stvn_d", StvnParserConfig.DEFAULT);
    assertFalse(resRedundant.hasErrors());
    assertEquals(3, resRedundant.diagnostics().size());
    assertEquals(
        "Constraint violation (:ChildStatusAll): facet '#filterIncl [#PENDING #ACTIVE #CLOSED]' is redundant; ancestor ':ParentStatus' already establishes active variants '[#PENDING #ACTIVE #CLOSED]'",
        resRedundant.diagnostics().get(0).message()
    );
    assertEquals(
        "Constraint violation (:LeafStatusOpen): facet '#filterIncl [#PENDING #ACTIVE]' is redundant; ancestor ':MidStatusOpen' already establishes active variants '[#PENDING #ACTIVE]'",
        resRedundant.diagnostics().get(1).message()
    );
    assertEquals(
        "Constraint violation (:NonEqChild): facet '#filterIncl [#ALPHA #BETA]' is redundant; ancestor ':NonEqParent' already establishes active variants '[#ALPHA #BETA]'",
        resRedundant.diagnostics().get(2).message()
    );

    var docRedundant = resRedundant.document().orElseThrow();
    var childAllSchema = docRedundant.defs().nominalTypes().get(":ChildStatusAll");
    var leafOpenSchema = docRedundant.defs().nominalTypes().get(":LeafStatusOpen");
    var nonEqChildSchema = docRedundant.defs().nominalTypes().get(":NonEqChild");
    assertInstanceOf(org.stvnadore.core.ir.StvnSchema.EnumSchema.class, childAllSchema);
    assertInstanceOf(org.stvnadore.core.ir.StvnSchema.EnumSubsetSchema.class, leafOpenSchema);
    assertInstanceOf(org.stvnadore.core.ir.StvnSchema.EnumSchema.class, nonEqChildSchema);
    assertFalse(nonEqChildSchema.traits().equatable(), "Purged identity #filterIncl must not silently resurrect equatable=#TRUE");
    assertFalse(nonEqChildSchema.traits().comparable());

    assertDocumentBijectivity(cleanSchema, redundantSchema, "enum_biject.core.stvn_d");
  }

  @Test
  @DisplayName("TC-BIJECT-07: Interval errors (E009/E018) suppress co-located W003 on the pinned MetadataEntry and Set/Map quote '#comparable #FALSE'")
  void testIntervalErrorSuppressionAndSetMapSingleQuotedTraits() {
    String emptyIntervalSource = """
        {
          :defs {
            :Parent { #minIncl 10 #maxExcl 20 } :Int
            :BadEmpty { #minIncl 20 #maxExcl 20 } :Parent
          }
          :type :BadEmpty
          :body 15
        }
        """;
    var emptyRes = StvnCompiler.compileToResult(emptyIntervalSource, null, StvnParserConfig.DEFAULT);
    assertTrue(emptyRes.hasErrors());
    assertEquals(1, emptyRes.diagnostics().size(), "Pinned #maxExcl 20 carrying E018 must not stack W003");
    assertEquals(DiagnosticBag.ERR_EMPTY_INTERVAL_DOMAIN, emptyRes.diagnostics().get(0).errorCode().orElse(null));

    String separateMinWarnMaxErrorSource = """
        {
          :defs {
            :Parent { #minIncl 20 #maxExcl 30 } :Int
            :BadInverted { #minIncl 20 #maxExcl 10 } :Parent
          }
          :type :BadInverted
          :body 20
        }
        """;
    var sepRes = StvnCompiler.compileToResult(separateMinWarnMaxErrorSource, null, StvnParserConfig.DEFAULT);
    assertTrue(sepRes.hasErrors());
    assertEquals(2, sepRes.diagnostics().size(), "Un-pinned #minIncl 20 keeps W003 while #maxExcl 10 receives E009");
    assertEquals(DiagnosticBag.WARN_REDUNDANT_METADATA_FACET, sepRes.diagnostics().get(0).errorCode().orElse(null));
    assertEquals(DiagnosticBag.ERR_INVERTED_RANGE, sepRes.diagnostics().get(1).errorCode().orElse(null));

    String setMapTraitsSource = """
        {
          :defs {
            :MySet { #comparable #FALSE } :Set( :Int )
            :MyMap { #comparable #FALSE } :Map( :String :Int )
          }
          :type :Tuple( :MySet :MyMap )
          :body ( [ 1 ] { [ "a" 1 ] } )
        }
        """;
    var setMapRes = StvnCompiler.compileToResult(setMapTraitsSource, null, StvnParserConfig.DEFAULT);
    assertFalse(setMapRes.hasErrors());
    assertEquals(2, setMapRes.diagnostics().size());
    assertEquals(
        "Constraint violation (:MySet): facet '#comparable #FALSE' is redundant; ':Set' already resolves to '#comparable #FALSE'",
        setMapRes.diagnostics().get(0).message()
    );
    assertEquals(
        "Constraint violation (:MyMap): facet '#comparable #FALSE' is redundant; ':Map' already resolves to '#comparable #FALSE'",
        setMapRes.diagnostics().get(1).message()
    );
  }

  private static void assertDocumentBijectivity(String cleanSchema, String redundantSchema, String virtualFileName) throws Exception {
    var cleanDoc = StvnCompiler.compileToResult(cleanSchema, virtualFileName, StvnParserConfig.DEFAULT)
        .document()
        .orElseThrow();
    var redundantDoc = StvnCompiler.compileToResult(redundantSchema, virtualFileName, StvnParserConfig.DEFAULT)
        .document()
        .orElseThrow();

    String flatClean = org.stvnadore.core.StvnSchemaFlattener.flatten(java.util.Map.of(virtualFileName, cleanSchema), virtualFileName);
    String flatRedundant = org.stvnadore.core.StvnSchemaFlattener.flatten(java.util.Map.of(virtualFileName, redundantSchema), virtualFileName);
    assertEquals(flatClean, flatRedundant, "Flattened canonical CAS preimage must match 1:1");

    var digest = java.security.MessageDigest.getInstance("SHA-256");
    String casClean = java.util.HexFormat.of().formatHex(digest.digest(flatClean.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    digest.reset();
    String casRedundant = java.util.HexFormat.of().formatHex(digest.digest(flatRedundant.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertEquals(casClean, casRedundant, "Document CAS SHA-256 must match 1:1");

    var prettyPrinter = new org.stvnadore.core.printer.AstPrettyPrinter();
    var compactPrinter = new org.stvnadore.core.printer.AstCompactPrinter();
    assertEquals(prettyPrinter.printToString(cleanDoc), prettyPrinter.printToString(redundantDoc), "AstPrettyPrinter output must match 1:1");
    assertEquals(compactPrinter.printToString(cleanDoc), compactPrinter.printToString(redundantDoc), "AstCompactPrinter output must match 1:1");

    var cleanTypes = cleanDoc.defs().nominalTypes();
    var redundantTypes = redundantDoc.defs().nominalTypes();
    assertEquals(cleanTypes.keySet(), redundantTypes.keySet());
    for (String typeName : cleanTypes.keySet()) {
      assertArrayEquals(
          org.stvnadore.core.binary.StvnSchemaHasher.computeSha256(cleanTypes.get(typeName)),
          org.stvnadore.core.binary.StvnSchemaHasher.computeSha256(redundantTypes.get(typeName)),
          "StvnSchemaHasher SHA-256 must match 1:1 for " + typeName
      );
      assertEquals(
          org.stvnadore.core.binary.StvnSchemaHasher.hashSchema(cleanTypes.get(typeName)),
          org.stvnadore.core.binary.StvnSchemaHasher.hashSchema(redundantTypes.get(typeName)),
          "StvnSchemaHasher UUIDv8 must match 1:1 for " + typeName
      );
    }
  }
}
