package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exhaustive Cartesian Combinatorial Facet Matrix Test Suite (MCT §5.1-§5.6.4).
 * <p>
 * Evaluates the full Cartesian product T x F across the 6 foundation base types
 * (:Int, :Float, :String, :Boolean, :TimeEpoch, :DateTime) and 24 canonical facets across 7 tiers.
 * Verifies that all 108 invalid pairings fail closed deterministically with normative diagnostic codes:
 * <ul>
 *   <li>{@link DiagnosticBag#ERR_STRING_CARDINALITY_PROHIBITED} for {@code #size} on {@code :String}</li>
 *   <li>{@link DiagnosticBag#ERR_DISCRETE_BOUND_KIND_PROHIBITED} for {@code #minExcl} or {@code #maxIncl} on discrete types</li>
 *   <li>{@link DiagnosticBag#ERR_INVALID_METADATA_FACET} for all other prohibited type-facet combinations</li>
 * </ul>
 */
public class StvnCartesianFacetMatrixTest {

  private static final List<String> BASE_TYPES = List.of(
      ":Int",
      ":Float",
      ":String",
      ":Boolean",
      ":TimeEpoch",
      ":DateTime"
  );

  private static final Map<String, Set<String>> PERMITTED_FACETS = Map.of(
      ":Int", Set.of("unsigned", "equatable", "comparable", "size", "minIncl", "maxExcl"),
      ":Float", Set.of("exact", "equatable", "comparable", "size", "minIncl", "minExcl", "maxExcl", "maxIncl"),
      ":String", Set.of("preserveIndent", "equatable", "comparable", "minSize", "maxSize", "regex"),
      ":Boolean", Set.of("equatable"),
      ":TimeEpoch", Set.of("s", "ms", "us", "ns", "equatable", "comparable", "minIncl", "maxExcl"),
      ":DateTime", Set.of("offset", "zoned", "audited", "equatable", "comparable", "minIncl", "maxExcl")
  );

  private record FacetSpec(String name, String syntax, String dateTimeSyntax) {}

  private static final List<FacetSpec> ALL_FACETS = List.of(
      // Tier 1: Flags & Intrinsic Modes
      new FacetSpec("unsigned", "#unsigned", "#unsigned"),
      new FacetSpec("exact", "#exact", "#exact"),
      new FacetSpec("invertible", "#invertible", "#invertible"),
      new FacetSpec("preserveIndent", "#preserveIndent", "#preserveIndent"),
      new FacetSpec("offset", "#offset", "#offset"),
      new FacetSpec("zoned", "#zoned", "#zoned"),
      new FacetSpec("audited", "#audited", "#audited"),
      new FacetSpec("equatable", "#equatable #TRUE", "#equatable #TRUE"),
      new FacetSpec("comparable", "#comparable #TRUE", "#comparable #TRUE"),

      // Tier 2: Temporal Scale
      new FacetSpec("s", "#s", "#s"),
      new FacetSpec("ms", "#ms", "#ms"),
      new FacetSpec("us", "#us", "#us"),
      new FacetSpec("ns", "#ns", "#ns"),

      // Tier 3: Dimensions & Capacity
      new FacetSpec("size", "#size 32", "#size 32"),
      new FacetSpec("minSize", "#minSize 1", "#minSize 1"),
      new FacetSpec("maxSize", "#maxSize 10", "#maxSize 10"),

      // Tier 4: Value Intervals
      new FacetSpec("minIncl", "#minIncl 0", "#minIncl \"2026-01-01T00:00:00Z\""),
      new FacetSpec("minExcl", "#minExcl 0", "#minExcl \"2026-01-01T00:00:00Z\""),
      new FacetSpec("maxExcl", "#maxExcl 100", "#maxExcl \"2026-01-02T00:00:00Z\""),
      new FacetSpec("maxIncl", "#maxIncl 100", "#maxIncl \"2026-01-02T00:00:00Z\""),

      // Tier 5: Validation Patterns
      new FacetSpec("regex", "#regex \"^[a-z]+$\"", "#regex \"^[a-z]+$\""),

      // Tier 6: Domain Subsets
      new FacetSpec("filterIncl", "#filterIncl [ #FOO ]", "#filterIncl [ #FOO ]"),
      new FacetSpec("filterExcl", "#filterExcl [ #BAR ]", "#filterExcl [ #BAR ]"),

      // Tier 7: Directives
      new FacetSpec("strip", "#strip", "#strip")
  );

  static Stream<Arguments> invalidFacetTypeCombinations() {
    List<Arguments> testCases = new ArrayList<>();
    for (String baseType : BASE_TYPES) {
      Set<String> permitted = PERMITTED_FACETS.get(baseType);
      for (FacetSpec facet : ALL_FACETS) {
        if (!permitted.contains(facet.name())) {
          String expectedErrorCode;
          if (":String".equals(baseType) && "size".equals(facet.name())) {
            expectedErrorCode = DiagnosticBag.ERR_STRING_CARDINALITY_PROHIBITED;
          } else if ((":Int".equals(baseType) || ":TimeEpoch".equals(baseType) || ":DateTime".equals(baseType))
              && ("minExcl".equals(facet.name()) || "maxIncl".equals(facet.name()))) {
            expectedErrorCode = DiagnosticBag.ERR_DISCRETE_BOUND_KIND_PROHIBITED;
          } else {
            expectedErrorCode = DiagnosticBag.ERR_INVALID_METADATA_FACET;
          }
          String syntax = ":DateTime".equals(baseType) ? facet.dateTimeSyntax() : facet.syntax();
          testCases.add(Arguments.of(baseType, facet.name(), syntax, expectedErrorCode));
        }
      }
    }
    return testCases.stream();
  }

  @ParameterizedTest(name = "[{index}] {0} with #{1} -> {3}")
  @MethodSource("invalidFacetTypeCombinations")
  @DisplayName("Cartesian Combinatorial Soundness: Invalid type-facet pairs must fail closed")
  void testInvalidCartesianFacetPair(String baseType, String facetName, String facetSyntax, String expectedErrorCode) {
    String source = """
        {
          :defs {
            :TargetType { %s } %s
          }
          :type :Int
          :body 0
        }
        """.formatted(facetSyntax, baseType);

    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Invalid combination " + baseType + " with #" + facetName + " must fail compilation");
    assertTrue(
        result.diagnostics().stream().anyMatch(d -> expectedErrorCode.equals(d.errorCode().orElse(null))),
        "Expected diagnostic code " + expectedErrorCode + " for " + baseType + " with #" + facetName +
            " but got: " + result.diagnostics().stream().map(d -> d.errorCode().orElse(null) + ": " + d.message()).toList()
    );
  }

  @Test
  @DisplayName("Cartesian Combinatorial Matrix: Total invalid pairs must equal exactly 108")
  void testCombinatorialMatrixSize() {
    long invalidCount = invalidFacetTypeCombinations().count();
    org.junit.jupiter.api.Assertions.assertEquals(108, invalidCount, "Matrix must evaluate exactly 108 invalid pairs (144 total - 36 valid)");
  }
}
