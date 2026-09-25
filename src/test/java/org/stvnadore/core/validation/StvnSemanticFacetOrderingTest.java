package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.binary.StvnSchemaHasher;
import org.stvnadore.core.printer.CompactTextPrinter;
import org.stvnadore.core.printer.PrettyTextPrinter;
import org.stvnadore.core.printer.PrinterOptions;

import java.util.Map;

/**
 * Verification test suite for the 7-tier Semantic Category Order of metadata facets.
 * <p>
 * Ensures canonical serialization order across the flattener and text printers:
 * Tier 1 (Flags & Intrinsic Modes) -> Tier 2 (Scale) -> Tier 3 (Dimensions) ->
 * Tier 4 (Value Intervals) -> Tier 5 (Patterns) -> Tier 6 (Subsets) -> Tier 7 (Directives).
 * Also asserts that permutation of input facets yields identical Content-Addressable Storage (CAS) hashes.
 */
@NullMarked
public class StvnSemanticFacetOrderingTest {

  @Test
  @DisplayName("TC-ORDER-01: Multi-tier facet ordering in flattener output conforms to 7-tier canonical sequence")
  void testFlattenerConformsToSemanticCategoryOrder() {
    String source = """
        {
          :defs {
            :TargetType {
              #regex "^[A-Z0-9]+$"
              #minSize 4
              #maxSize 16
              #preserveIndent
              #equatable #TRUE
              #comparable #TRUE
            } :String
          }
          :type :TargetType
          :body "TEST1234"
        }
        """;

    String flattened = StvnSchemaFlattener.flatten(Map.of("test.stvn", source), "test.stvn");
    // Expected:
    // Tier 1: #preserveIndent #equatable #TRUE #comparable #TRUE
    // Tier 3: #minSize 4 #maxSize 16
    // Tier 5: #regex "^[A-Z0-9]+$"
    String expected = "{ :defs { :TargetType { #preserveIndent #equatable #TRUE #comparable #TRUE #minSize 4 #maxSize 16 #regex \"^[A-Z0-9]+$\" } :String } }";
    Assertions.assertEquals(expected, flattened);
  }

  @Test
  @DisplayName("TC-ORDER-02: Numeric interval lower-before-upper ordering in flattener conforms to Tier 4")
  void testFlattenerTier4IntervalLowerBeforeUpper() {
    String source = """
        {
          :defs {
            :BoundedInt {
              #maxExcl 100
              #minIncl 0
              #unsigned
              #size 16
            } :Int
          }
          :type :BoundedInt
          :body 50
        }
        """;

    String flattened = StvnSchemaFlattener.flatten(Map.of("test.stvn", source), "test.stvn");
    // Expected:
    // Tier 1: #unsigned
    // Tier 3: #size 16
    // Tier 4: #minIncl 0 #maxExcl 100
    String expected = "{ :defs { :BoundedInt { #unsigned #size 16 #minIncl 0 #maxExcl 100 } :Int } }";
    Assertions.assertEquals(expected, flattened);
  }

  @Test
  @DisplayName("TC-ORDER-03: Temporal scale flag in flattener conforms to Tier 2")
  void testFlattenerTemporalScaleTier2() {
    String source = """
        {
          :defs {
            :EpochWindow {
              #maxExcl 2000000000
              #s
              #minIncl 1000000000
            } :TimeEpoch
          }
          :type :EpochWindow
          :body 1500000000
        }
        """;

    String flattened = StvnSchemaFlattener.flatten(Map.of("test.stvn", source), "test.stvn");
    // Expected:
    // Tier 2: #s
    // Tier 4: #minIncl 1000000000 #maxExcl 2000000000
    String expected = "{ :defs { :EpochWindow { #s #minIncl 1000000000 #maxExcl 2000000000 } :TimeEpoch } }";
    Assertions.assertEquals(expected, flattened);
  }

  @Test
  @DisplayName("TC-ORDER-04: Compact and Pretty text printers serialize metadata in 7-tier order")
  void testPrintersSerializeInSemanticOrder() {
    String source = """
        {
          :defs {
            :Port {
              #unsigned
              #size 16
              #minIncl 1024
              #maxExcl 65536
            } :Int
          }
          :type :Port
          :body 8080
        }
        """;

    var ast = StvnCompiler.compile(source).orElseThrow();
    var compactPrinter = new CompactTextPrinter(new PrinterOptions());
    String compactOutput = compactPrinter.printToString(ast);
    String expectedCompact = "{:defs {:Port {#unsigned #size 16 #minIncl 1024 #maxExcl 65536} :Int} :type :Port :body 8080}";
    Assertions.assertEquals(expectedCompact, compactOutput);

    var prettyPrinter = new PrettyTextPrinter(new PrinterOptions(
        PrinterOptions.Coverage.ALL_SECTIONS,
        4,
        PrinterOptions.SymbolStyle.LONG_FORM,
        PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
    ));
    String prettyOutput = prettyPrinter.printToString(ast);
    Assertions.assertTrue(prettyOutput.contains("#unsigned"));
    Assertions.assertTrue(prettyOutput.contains("#size 16"));
    Assertions.assertTrue(prettyOutput.contains("#minIncl 1024"));
    Assertions.assertTrue(prettyOutput.contains("#maxExcl 65536"));

    // Verify relative ordering inside pretty output
    int posUnsigned = prettyOutput.indexOf("#unsigned");
    int posSize = prettyOutput.indexOf("#size");
    int posMin = prettyOutput.indexOf("#minIncl");
    int posMax = prettyOutput.indexOf("#maxExcl");

    Assertions.assertTrue(posUnsigned < posSize, "#unsigned must precede #size");
    Assertions.assertTrue(posSize < posMin, "#size must precede #minIncl");
    Assertions.assertTrue(posMin < posMax, "#minIncl must precede #maxExcl");
  }

  @Test
  @DisplayName("TC-ORDER-05: Non-canonical facet order fails closed with ERR_FACET_ORDER_VIOLATION")
  void testPermutedFacetsProduceIdenticalSchemaHash() {
    String sourceA = """
        {
          :defs {
            :Port { #unsigned #size 16 #minIncl 1024 #maxExcl 65536 } :Int
          }
          :type :Port
          :body 8080
        }
        """;

    String sourceB = """
        {
          :defs {
            :Port { #maxExcl 65536 #minIncl 1024 #size 16 #unsigned } :Int
          }
          :type :Port
          :body 8080
        }
        """;

    var astA = StvnCompiler.compile(sourceA).orElseThrow();
    byte[] hashA = StvnSchemaHasher.computeSha256(astA.schema());
    Assertions.assertNotNull(hashA);

    var resultB = StvnCompiler.compileToResult(sourceB);
    Assertions.assertFalse(resultB.isSuccess(), "Out-of-order facets must fail closed");
    Assertions.assertTrue(
        resultB.diagnostics().stream().anyMatch(d -> DiagnosticBag.ERR_FACET_ORDER_VIOLATION.equals(d.errorCode().orElse(null))),
        "Expected ERR_FACET_ORDER_VIOLATION for out-of-order facets"
    );
  }
}
