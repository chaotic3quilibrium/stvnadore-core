package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;

import java.util.HashSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit and calculus test suite validating inductive trait capability derivation
 * and facet validation for standard and invertible map types ({@code :Map})
 * according to STVN Specification § 6.2 and § 6.3.
 */
public class StvnMapTraitCalculusTest {

  @Test
  @DisplayName("TC-MAP-01: Standard :Map( :String :Float ) compiles cleanly with zero diagnostics (standard map allows non-equatable float values)")
  void testStandardMapStringFloatCompilesCleanly() {
    String source = """
        {
          :defs {
            :FloatMap :Map( :String :Float )
          }
          :type :FloatMap
          :body { [ "pi" 3.14 ] }
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Standard :Map(:String :Float) must compile cleanly with zero diagnostics: " + result.diagnostics());
    assertEquals(0, result.diagnostics().size(), "Diagnostics count must be zero");

    var doc = StvnCompiler.parse(source, StvnParserConfig.DEFAULT);
    var defs = StvnTypeResolver.getDocumentDefinitions(doc);
    var mapDef = defs.get(":FloatMap");
    var rsOpt = StvnTypeResolver.resolvePrimitiveSchema(doc, mapDef.defNode().schemaType(), new HashSet<>());
    assertTrue(rsOpt.isPresent());
    var rs = rsOpt.get();
    assertEquals(Optional.of(true), rs.constraints().equatable(), "Standard :Map(:String :Float) equatable trait must be true (derived strictly from key)");
    assertEquals(Optional.of(false), rs.constraints().comparable(), "Standard :Map(:String :Float) comparable trait must be false");
  }

  @Test
  @DisplayName("TC-MAP-02: { #invertible } :Map( :String :Float ) fails compilation with ERR_TRAIT_VIOLATION ('Inverted map values require types to be #equatable #TRUE')")
  void testInvertibleMapStringFloatFailsCompilation() {
    String source = """
        {
          :defs {
            :InvFloatMap { #invertible } :Map( :String :Float )
          }
          :type :InvFloatMap
          :body { [ "pi" 3.14 ] }
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Invertible map with continuous float values must fail compilation");
    assertTrue(
        result.diagnostics().stream().anyMatch(d ->
            DiagnosticBag.ERR_TRAIT_VIOLATION.equals(d.errorCode().orElse(null))
                && d.message().contains("Inverted map values require types to be #equatable #TRUE")),
        "Expected ERR_TRAIT_VIOLATION with message 'Inverted map values require types to be #equatable #TRUE' but got: " + result.diagnostics()
    );
  }

  @Test
  @DisplayName("TC-MAP-03: { #invertible } :Map( :String { #exact } :Float ) compiles cleanly (exact decimal float is equatable)")
  void testInvertibleMapStringExactFloatCompilesCleanly() {
    String source = """
        {
          :defs {
            :InvExactMap { #invertible } :Map( :String { #exact } :Float )
          }
          :type :InvExactMap
          :body { [ "pi" 3.14 ] }
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Invertible map with exact decimal float values must compile cleanly: " + result.diagnostics());
    assertEquals(0, result.diagnostics().size(), "Diagnostics count must be zero");

    var doc = StvnCompiler.parse(source, StvnParserConfig.DEFAULT);
    var defs = StvnTypeResolver.getDocumentDefinitions(doc);
    var mapDef = defs.get(":InvExactMap");
    var rsOpt = StvnTypeResolver.resolvePrimitiveSchema(doc, mapDef.defNode().schemaType(), new HashSet<>());
    assertTrue(rsOpt.isPresent());
    var rs = rsOpt.get();
    assertEquals(Optional.of(true), rs.constraints().equatable(), "Invertible map with exact float values equatable trait must be true");
    assertEquals(Optional.of(false), rs.constraints().comparable(), "Invertible map comparable trait must be false");
  }

  @Test
  @DisplayName("TC-MAP-04: { #invertible } :Map( :String :Int ) derives equatable == true and comparable == false")
  void testInvertibleMapStringIntDerivesEquatableAndNonComparable() {
    String source = """
        {
          :defs {
            :InvMap { #invertible } :Map( :String :Int )
          }
          :type :InvMap
          :body { [ "a" 1 ] }
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Invertible map with int values must compile cleanly: " + result.diagnostics());

    var doc = StvnCompiler.parse(source, StvnParserConfig.DEFAULT);
    var defs = StvnTypeResolver.getDocumentDefinitions(doc);
    var mapDef = defs.get(":InvMap");
    var rsOpt = StvnTypeResolver.resolvePrimitiveSchema(doc, mapDef.defNode().schemaType(), new HashSet<>());
    assertTrue(rsOpt.isPresent());
    var rs = rsOpt.get();
    assertEquals(Optional.of(true), rs.constraints().equatable(), "Invertible map equatable trait must be true");
    assertEquals(Optional.of(false), rs.constraints().comparable(), "Invertible map comparable trait must be false");
  }

  @Test
  @DisplayName("TC-MAP-05: Standard :Map( :Float :String ) fails with ERR_TRAIT_VIOLATION ('Map keys require types to be #equatable #TRUE') due to non-equatable float keys")
  void testStandardMapFloatKeyFailsCompilation() {
    String source = """
        {
          :defs {
            :BadFloatKeyMap :Map( :Float :String )
          }
          :type :BadFloatKeyMap
          :body { [ 3.14 "pi" ] }
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Map with continuous float keys must fail compilation");
    assertTrue(
        result.diagnostics().stream().anyMatch(d ->
            DiagnosticBag.ERR_TRAIT_VIOLATION.equals(d.errorCode().orElse(null))
                && d.message().contains("Map keys require types to be #equatable #TRUE")),
        "Expected ERR_TRAIT_VIOLATION with message 'Map keys require types to be #equatable #TRUE' but got: " + result.diagnostics()
    );
  }

  @Test
  @DisplayName("TC-MAP-06: Invertible map payload containing duplicate values fails payload lowering with ERR_DUPLICATE_INVERTED_MAP_VALUE")
  void testInvertibleMapDuplicateValuesFailsLowering() {
    String source = """
        {
          :defs {
            :InvMap { #invertible } :Map( :String :Int )
          }
          :type :InvMap
          :body {
            [ "a" 1 ]
            [ "b" 1 ]
          }
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Invertible map with duplicate values must fail payload lowering");
    assertTrue(
        result.diagnostics().stream().anyMatch(d ->
            DiagnosticBag.ERR_DUPLICATE_INVERTED_MAP_VALUE.equals(d.errorCode().orElse(null))),
        "Expected ERR_DUPLICATE_INVERTED_MAP_VALUE but got: " + result.diagnostics()
    );
  }

}
