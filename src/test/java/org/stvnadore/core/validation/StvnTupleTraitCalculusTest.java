package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.ir.StvnSchema;

import java.util.Optional;

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

    StvnSchema pairSchema = result.document().orElseThrow().defs().findType(":Pair").orElseThrow();
    assertTrue(pairSchema.traits().equatable(), "Tuple(:Int :String) equatable trait must be true");
    assertTrue(pairSchema.traits().comparable(), "Tuple(:Int :String) comparable trait must be true");
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

    StvnSchema floatPairSchema = StvnCompiler.compile(
        "{ :defs { :FloatPair :Tuple( :Int :Float ) } :type :FloatPair :body ( 1 3.14 ) }"
    ).document().orElseThrow().defs().findType(":FloatPair").orElseThrow();
    assertFalse(floatPairSchema.traits().equatable(), "Tuple(:Int :Float) equatable trait must be false");
    assertTrue(floatPairSchema.traits().comparable(), "Tuple(:Int :Float) comparable trait must remain true");
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

    StvnSchema exactPairSchema = result.document().orElseThrow().defs().findType(":ExactPair").orElseThrow();
    assertTrue(exactPairSchema.traits().equatable(), "Tuple(:Int {#exact} :Float) equatable trait must be true");
    assertTrue(exactPairSchema.traits().comparable(), "Tuple(:Int {#exact} :Float) comparable trait must be true");
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

    StvnSchema pairSchema = result.document().orElseThrow().defs().findType(":OverriddenPair").orElseThrow();
    assertTrue(pairSchema.traits().equatable(), "Explicitly overridden tuple equatable trait must be true");
    assertEquals(Optional.of(true), pairSchema.traits().explicitEquatableOverride(), "explicitEquatableOverride must be present");
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

    StvnSchema nestedSchema = StvnCompiler.compile(
        "{ :defs { :NestedTuple :Tuple( :Int :Tuple( :String :Tuple( :Boolean :Float ) ) ) } :type :Int :body 0 }"
    ).document().orElseThrow().defs().findType(":NestedTuple").orElseThrow();
    assertFalse(nestedSchema.traits().equatable(), "Deeply nested tuple must derive equatable == false");
    assertTrue(nestedSchema.traits().comparable(), "Deeply nested tuple must derive comparable == true");
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
    StvnSchema tupleSchema = StvnCompiler.compile(source).document().orElseThrow()
        .defs().findType(":TupleWithSet").orElseThrow();
    assertTrue(tupleSchema.traits().equatable(), "Tuple with set is equatable");
    assertFalse(tupleSchema.traits().comparable(), "Tuple with set must derive comparable == false");
  }

  @Test
  @DisplayName("TC-TUP-08: :Tuple with multiple offending :Set children accumulates all diagnostics")
  void testTupleWithMultipleOffendingSetsAccumulatesDiagnostics() {
    String source = """
        {
          :defs {
            :Bad1 { #equatable #FALSE } :Tuple( :Int )
            :Bad2 :Tuple( :Float )
            :Bad3 :Tuple( :Float :Float )
          }
          :type :Tuple(
            :Set( :Bad1 )
            :Set( :Bad2 )
            :Set( :Bad3 )
          )
          :body ( [ ( 1 ) ] [ ( 1.0 ) ] [ ( 1.0 2.0 ) ] )
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Tuple with multiple non-equatable sets must fail");
    var diags = result.diagnostics();
    assertEquals(3, diags.size(), "Must accumulate all 3 sibling set trait violation diagnostics");
    for (var d : diags) {
      assertEquals(DiagnosticBag.ERR_TRAIT_VIOLATION, d.errorCode().orElse(null));
      assertEquals("Set elements require types to be #equatable #TRUE", d.message());
    }
    assertEquals(":Bad1", source.substring(diags.get(0).startOffset(), diags.get(0).endOffset()));
    assertEquals(":Bad2", source.substring(diags.get(1).startOffset(), diags.get(1).endOffset()));
    assertEquals(":Bad3", source.substring(diags.get(2).startOffset(), diags.get(2).endOffset()));
  }
}
