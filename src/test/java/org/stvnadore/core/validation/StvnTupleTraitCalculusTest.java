package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit and calculus test suite validating inductive trait capability derivation
 * and facet validation for product types ({@code :Tuple}) according to STVN Specification § 6.2 and § 6.3.
 */
public class StvnTupleTraitCalculusTest {

  @Test
  @DisplayName("TC-TUP-01: :Tuple( :Int :String ) derives equatable == true and comparable == true")
  void testTupleIntStringDerivesEquatableAndComparable() {
    String source = """
        {
          :defs {
            :Pair :Tuple( :Int :String )
            :PairSet :Set( :Pair )
          }
          :type :PairSet
          :body [ ( 42 "hello" ) ]
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Tuple(:Int :String) must derive equatable and comparable cleanly: " + result.diagnostics());

    var doc = StvnCompiler.parse(source, StvnParserConfig.DEFAULT);
    var defs = StvnTypeResolver.getDocumentDefinitions(doc);
    var pairDef = defs.get(":Pair");
    var rsOpt = StvnTypeResolver.resolvePrimitiveSchema(doc, pairDef.defNode().schemaType(), new java.util.HashSet<>());
    assertTrue(rsOpt.isPresent());
    var rs = rsOpt.get();
    assertEquals(java.util.Optional.of(true), rs.constraints().equatable(), "Tuple(:Int :String) equatable trait must be true");
    assertEquals(java.util.Optional.of(true), rs.constraints().comparable(), "Tuple(:Int :String) comparable trait must be true");
  }

  @Test
  @DisplayName("TC-TUP-02: :Tuple( :Int :Float ) derives equatable == false due to continuous :Float; enclosing in :Set triggers ERR_TRAIT_VIOLATION")
  void testTupleIntFloatDerivesNonEquatableAndFailsInSet() {
    String source = """
        {
          :defs {
            :FloatPair :Tuple( :Int :Float )
            :SetOfPairs :Set( :FloatPair )
          }
          :type :SetOfPairs
          :body [ ( 1 3.14 ) ]
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Set elements require #equatable #TRUE; continuous float tuple must fail");
    assertTrue(
        result.diagnostics().stream().anyMatch(d -> DiagnosticBag.ERR_TRAIT_VIOLATION.equals(d.errorCode().orElse(null))),
        "Expected ERR_TRAIT_VIOLATION but got: " + result.diagnostics()
    );

    var doc = StvnCompiler.parse(source, StvnParserConfig.DEFAULT);
    var defs = StvnTypeResolver.getDocumentDefinitions(doc);
    var floatPairDef = defs.get(":FloatPair");
    var rsOpt = StvnTypeResolver.resolvePrimitiveSchema(doc, floatPairDef.defNode().schemaType(), new java.util.HashSet<>());
    assertTrue(rsOpt.isPresent());
    var rs = rsOpt.get();
    assertEquals(java.util.Optional.of(false), rs.constraints().equatable(), "Tuple(:Int :Float) equatable trait must be false");
    assertEquals(java.util.Optional.of(true), rs.constraints().comparable(), "Tuple(:Int :Float) comparable trait must remain true");
  }

  @Test
  @DisplayName("TC-TUP-03: :Tuple( :Int { #exact } :Float ) derives equatable == true and passes in :Set")
  void testTupleIntExactFloatDerivesEquatableAndPassesInSet() {
    String source = """
        {
          :defs {
            :ExactPair :Tuple( :Int { #exact } :Float )
            :SetOfExactPairs :Set( :ExactPair )
          }
          :type :SetOfExactPairs
          :body [ ( 1 3.14 ) ]
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Exact decimal float is equatable; Set(:ExactPair) must compile cleanly: " + result.diagnostics());

    var doc = StvnCompiler.parse(source, StvnParserConfig.DEFAULT);
    var defs = StvnTypeResolver.getDocumentDefinitions(doc);
    var exactPairDef = defs.get(":ExactPair");
    var rsOpt = StvnTypeResolver.resolvePrimitiveSchema(doc, exactPairDef.defNode().schemaType(), new java.util.HashSet<>());
    assertTrue(rsOpt.isPresent());
    var rs = rsOpt.get();
    assertEquals(java.util.Optional.of(true), rs.constraints().equatable(), "Tuple(:Int {#exact} :Float) equatable trait must be true");
    assertEquals(java.util.Optional.of(true), rs.constraints().comparable(), "Tuple(:Int {#exact} :Float) comparable trait must be true");
  }

  @Test
  @DisplayName("TC-TUP-04: :Tuple( :Int :Float ) with explicit { #equatable #TRUE } override satisfies :Set requirements")
  void testTupleExplicitEquatableOverrideSucceedsInSet() {
    String source = """
        {
          :defs {
            :OverriddenPair { #equatable #TRUE } :Tuple( :Int :Float )
            :SetOfPairs :Set( :OverriddenPair )
          }
          :type :SetOfPairs
          :body [ ( 1 3.14 ) ]
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Explicit override #equatable #TRUE must satisfy Set element requirement: " + result.diagnostics());

    var doc = StvnCompiler.parse(source, StvnParserConfig.DEFAULT);
    var defs = StvnTypeResolver.getDocumentDefinitions(doc);
    var pairDef = defs.get(":OverriddenPair");
    var rsOpt = StvnTypeResolver.resolvePrimitiveSchema(doc, pairDef.defNode().schemaType(), new java.util.HashSet<>());
    assertTrue(rsOpt.isPresent());
    var rs = rsOpt.get();
    assertEquals(java.util.Optional.of(true), rs.constraints().equatable(), "Explicitly overridden tuple equatable trait must be true");
    assertTrue(rs.constraints().explicitOverrides().contains("equatable"), "explicitOverrides must contain 'equatable'");
  }

  @Test
  @DisplayName("TC-TUP-05: :Tuple with prohibited #size 32 facet fails compilation with ERR_INVALID_METADATA_FACET")
  void testTupleProhibitedSizeFailsClosed() {
    String source = """
        {
          :defs {
            :BadTuple { #size 32 } :Tuple( :Int :String )
          }
          :type :Int
          :body 0
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Tuple with #size must fail compilation");
    assertTrue(
        result.diagnostics().stream().anyMatch(d -> DiagnosticBag.ERR_INVALID_METADATA_FACET.equals(d.errorCode().orElse(null))),
        "Expected ERR_INVALID_METADATA_FACET but got: " + result.diagnostics()
    );
  }

  @Test
  @DisplayName("TC-TUP-06: Deeply nested tuple bubbles non-equatable trait up AST hierarchy")
  void testDeeplyNestedTupleBubblesNonEquatable() {
    String source = """
        {
          :defs {
            :NestedTuple :Tuple( :Int :Tuple( :String :Tuple( :Boolean :Float ) ) )
            :SetOfNested :Set( :NestedTuple )
          }
          :type :SetOfNested
          :body [ ]
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Deeply nested tuple containing continuous float must fail Set capability check");
    assertTrue(
        result.diagnostics().stream().anyMatch(d -> DiagnosticBag.ERR_TRAIT_VIOLATION.equals(d.errorCode().orElse(null))),
        "Expected ERR_TRAIT_VIOLATION but got: " + result.diagnostics()
    );

    var doc = StvnCompiler.parse(source, StvnParserConfig.DEFAULT);
    var defs = StvnTypeResolver.getDocumentDefinitions(doc);
    var nestedDef = defs.get(":NestedTuple");
    var rsOpt = StvnTypeResolver.resolvePrimitiveSchema(doc, nestedDef.defNode().schemaType(), new java.util.HashSet<>());
    assertTrue(rsOpt.isPresent());
    var rs = rsOpt.get();
    assertEquals(java.util.Optional.of(false), rs.constraints().equatable(), "Deeply nested tuple must derive equatable == false");
    assertEquals(java.util.Optional.of(true), rs.constraints().comparable(), "Deeply nested tuple must derive comparable == true");
  }

  @Test
  @DisplayName("TC-TUP-07: :Tuple containing unordered :Set derives comparable == false")
  void testTupleWithSetDerivesNonComparable() {
    String source = """
        {
          :defs {
            :TupleWithSet :Tuple( :Int :Set( :Int ) )
          }
          :type :Int
          :body 0
        }
        """;
    var doc = StvnCompiler.parse(source, StvnParserConfig.DEFAULT);
    var defs = StvnTypeResolver.getDocumentDefinitions(doc);
    var tupleDef = defs.get(":TupleWithSet");
    var rsOpt = StvnTypeResolver.resolvePrimitiveSchema(doc, tupleDef.defNode().schemaType(), new java.util.HashSet<>());
    assertTrue(rsOpt.isPresent());
    var rs = rsOpt.get();
    assertEquals(java.util.Optional.of(true), rs.constraints().equatable(), "Tuple with set is equatable");
    assertEquals(java.util.Optional.of(false), rs.constraints().comparable(), "Tuple with set must derive comparable == false");
  }
}
