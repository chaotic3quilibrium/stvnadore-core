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
  @DisplayName("TC-FLAT-01: #preserveIndent serializes with explicit #T")
  void testPreserveIndentBareSerialization() {
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
    String expected = "{:meta{#kind #DEFS}:defs{:Text{#preserveIndent #T}:String}}";
    assertEquals(expected, flattened);
  }

  @Test
  @DisplayName("TC-FLAT-02: #preserveIndent serializes with explicit #F")
  void testPreserveIndentExplicitTrueNormalizesToBare() {
    String source = """
        {
          :defs {
            :Text { #preserveIndent #FALSE } :String
          }
          :type :Text
          :body "sample"
        }
        """;
    String flattened = StvnSchemaFlattener.flatten(Map.of("test.stvn", source), "test.stvn");
    String expected = "{:meta{#kind #DEFS}:defs{:Text{#preserveIndent #F}:String}}";
    assertEquals(expected, flattened);
  }

  @Test
  @DisplayName("TC-FLAT-03: All v2 facets serialize in canonical 5-tier Semantic Category Order")
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
    // 5-tier Semantic Category Order: Tier 1 (#unsigned #audited #size 32) < Tier 3 (#minIncl 10)
    String expected = "{:meta{#kind #DEFS}:defs{:ComplexInt{#unsigned #audited #size 32 #minIncl 10}:Int}}";
    assertEquals(expected, flattened);
  }

  @Test
  @DisplayName("TC-FLAT-04: Flattener is strictly idempotent across successive flattening passes")
  void testFlattenerIdempotency() {
    String source = """
        {
          :defs {
            :A { #unsigned #size 8 } :Int
            :B { #preserveIndent #TRUE } :String
          }
          :type :Tuple(:A :B)
          :body ( 1 "text" )
        }
        """;
    String pass1 = StvnSchemaFlattener.flatten(Map.of("test.stvn", source), "test.stvn");
    String inner = pass1.startsWith("{") ? pass1.substring(1, pass1.length() - 1) : pass1;
    String wrappedPass1 = "{" + inner + ":type :Tuple(:A :B):body(1 \"text\")}";
    String pass2 = StvnSchemaFlattener.flatten(Map.of("test.stvn", wrappedPass1), "test.stvn");
    assertEquals(pass1, pass2, "Flattener output must remain strictly idempotent");
  }
}
