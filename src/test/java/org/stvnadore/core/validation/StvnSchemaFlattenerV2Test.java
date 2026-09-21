package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnSchemaFlattener;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verification test suite for v2.0.0 schema flattener canonicalization and bare flag determinism.
 */
public class StvnSchemaFlattenerV2Test {

  @Test
  @DisplayName("TC-FLAT-01: #preserveIndent serializes as bare flag without #TRUE")
  void testPreserveIndentBareSerialization() {
    String source = """
        {
          :defs {
            :Text { #preserveIndent } :String
          }
          :type :Text
          :body "sample"
        }
        """;
    String flattened = StvnSchemaFlattener.flatten(Map.of("test.stvn", source), "test.stvn");
    String expected = "{ :defs { :Text { #preserveIndent } :String } }";
    assertEquals(expected, flattened);
  }

  @Test
  @DisplayName("TC-FLAT-02: #preserveIndent written as #preserveIndent #TRUE normalizes to bare #preserveIndent")
  void testPreserveIndentExplicitTrueNormalizesToBare() {
    String source = """
        {
          :defs {
            :Text { #preserveIndent #TRUE } :String
          }
          :type :Text
          :body "sample"
        }
        """;
    String flattened = StvnSchemaFlattener.flatten(Map.of("test.stvn", source), "test.stvn");
    String expected = "{ :defs { :Text { #preserveIndent } :String } }";
    assertEquals(expected, flattened);
  }

  @Test
  @DisplayName("TC-FLAT-03: All v2 facets serialize in canonical alphabetical order")
  void testAllFacetsAlphabeticalOrder() {
    String source = """
        {
          :defs {
            :ComplexInt { #audited #unsigned #size 32 #minIncl 10 } :Int
          }
          :type :ComplexInt
          :body 42
        }
        """;
    String flattened = StvnSchemaFlattener.flatten(Map.of("test.stvn", source), "test.stvn");
    // Alphabetical order: #audited < #minIncl < #size < #unsigned
    String expected = "{ :defs { :ComplexInt { #audited #minIncl 10 #size 32 #unsigned } :Int } }";
    assertEquals(expected, flattened);
  }

  @Test
  @DisplayName("TC-FLAT-04: Flattener is strictly idempotent across successive flattening passes")
  void testFlattenerIdempotency() {
    String source = """
        {
          :defs {
            :A { #unsigned #size 8 } :Int
            :B { #preserveIndent } :String
          }
          :type :Tuple(:A :B)
          :body ( 1 "text" )
        }
        """;
    String pass1 = StvnSchemaFlattener.flatten(Map.of("test.stvn", source), "test.stvn");
    String wrappedPass1 = "{ :defs " + pass1.substring(8, pass1.length() - 2) + " :type :Tuple(:A :B) :body ( 1 \"text\" ) }";
    String pass2 = StvnSchemaFlattener.flatten(Map.of("test.stvn", wrappedPass1), "test.stvn");
    assertEquals(pass1, pass2, "Flattener output must remain strictly idempotent");
  }
}
