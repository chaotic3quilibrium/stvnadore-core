package org.stvnadore.core.matrix;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.validation.DiagnosticBag;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Normative test suite verifying nominal alias inheritance invariants:
 * <ol>
 *   <li>Non-Overridable Facet Invariant (Overridable: No) -> {@link DiagnosticBag#ERR_NON_OVERRIDABLE_FACET}</li>
 *   <li>Transitive Mutual Exclusivity Invariant (Mutually Exclusive to) -> {@link DiagnosticBag#ERR_MUTUALLY_EXCLUSIVE}</li>
 *   <li>Authoritative verification of the 9 nominal override cases from {@code uhoh.stvn}</li>
 * </ol>
 */
@NullMarked
public class StvnNominalAliasOverrideMatrixTest {

  private static String facetSyntax(MetadataMatrixRow row) {
    String tag = row.tagName();
    return switch (tag) {
      case "unsigned", "exact", "invertible", "offset", "zoned", "audited", "s", "ms", "us", "ns", "strip" -> "#" + tag;
      case "size" -> {
        String domain = row.domainQualifier();
        if ("exact".equals(domain)) yield "#exact";
        if ("64".equals(domain)) yield "#size 64";
        yield "#size 32";
      }
      case "minSize" -> "#minSize 2";
      case "maxSize" -> "#maxSize 10";
      case "minIncl" -> "#minIncl 0";
      case "minExcl" -> "#minExcl 0";
      case "maxIncl" -> "#maxIncl 100";
      case "maxExcl" -> "#maxExcl 100";
      case "equatable", "comparable", "preserveIndent" -> "#" + tag + " #TRUE";
      case "regex" -> "#regex \"^[a-z]+$\"";
      case "filterIncl" -> "#filterIncl [ #FOO ]";
      case "filterExcl" -> "#filterExcl [ #BAR ]";
      default -> "#" + tag;
    };
  }

  private static String targetTypeSyntax(MetadataMatrixRow row) {
    String base = row.cleanBaseType();
    return switch (base) {
      case ":Seq" -> ":Seq(:Int)";
      case ":Set" -> ":Set(:Int)";
      case ":Map" -> ":Map(:String :Int)";
      default -> base;
    };
  }

  static Stream<Arguments> provideNonOverridableRows() {
    List<MetadataMatrixRow> rows = MetadataMatrixReader.readDefaultMatrix();
    List<Arguments> testCases = new ArrayList<>();
    for (MetadataMatrixRow row : rows) {
      if (!row.overridable() && !"Directive".equalsIgnoreCase(row.category())) {
        String facet = facetSyntax(row);
        String targetType = targetTypeSyntax(row);
        testCases.add(Arguments.of(row.position(), row.modifying(), row.tagName(), facet, targetType));
      }
    }
    return testCases.stream();
  }

  @ParameterizedTest(name = "[{index}] Row {0} ({1} #{2}): 2-hop re-specification fails closed with ERR_NON_OVERRIDABLE_FACET")
  @MethodSource("provideNonOverridableRows")
  @DisplayName("NOM-OVERRIDE-01: Re-declaring non-overridable facets across nominal alias hops fails closed")
  void testNonOverridableFacetReDeclaration(String position, String modifying, String tagName, String facetSyntax, String targetType) {
    String source = """
        {
          :defs {
            :Parent { %s } %s
            :Child { %s } :Parent
          }
          :type :Int
          :body 0
        }
        """.formatted(facetSyntax, targetType, facetSyntax);

    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Re-declaration of non-overridable facet " + facetSyntax + " on " + modifying + " must fail");

    boolean matched = result.diagnostics().stream().anyMatch(d ->
        DiagnosticBag.ERR_NON_OVERRIDABLE_FACET.equals(d.errorCode().orElse(null)));

    assertTrue(matched, () -> "Expected ERR_NON_OVERRIDABLE_FACET for " + modifying + " #" + tagName + " but got: " +
        result.diagnostics().stream().map(d -> d.errorCode().orElse(null) + ": " + d.message()).toList());

    // Coordinate pinning check: Offending diagnostic must pin within Child metadata block
    StvnDiagnostic diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_NON_OVERRIDABLE_FACET.equals(d.errorCode().orElse(null)))
        .findFirst().orElseThrow();
    String snippet = source.substring(diag.startOffset(), diag.endOffset());
    assertTrue(snippet.contains("#" + tagName), "Pinned diagnostic token must contain offending facet #" + tagName + ", got: '" + snippet + "'");
  }

  private record MutexPair(String name, String parentFacet, String childFacet, String targetType) {}

  static Stream<Arguments> provideMutuallyExclusiveCrossHopPairs() {
    List<MutexPair> pairs = List.of(
        // Float storage width & representation collisions
        new MutexPair("Float: exact -> size 32", "#exact", "#size 32", ":Float"),
        new MutexPair("Float: exact -> size 64", "#exact", "#size 64", ":Float"),
        new MutexPair("Float: size 32 -> exact", "#size 32", "#exact", ":Float"),
        new MutexPair("Float: size 32 -> size 64", "#size 32", "#size 64", ":Float"),
        new MutexPair("Float: size 64 -> exact", "#size 64", "#exact", ":Float"),
        new MutexPair("Float: size 64 -> size 32", "#size 64", "#size 32", ":Float"),

        // TimeEpoch scale collisions
        new MutexPair("TimeEpoch: s -> ms", "#s", "#ms", ":TimeEpoch"),
        new MutexPair("TimeEpoch: s -> us", "#s", "#us", ":TimeEpoch"),
        new MutexPair("TimeEpoch: s -> ns", "#s", "#ns", ":TimeEpoch"),
        new MutexPair("TimeEpoch: ms -> s", "#ms", "#s", ":TimeEpoch"),
        new MutexPair("TimeEpoch: us -> s", "#us", "#s", ":TimeEpoch"),
        new MutexPair("TimeEpoch: ns -> s", "#ns", "#s", ":TimeEpoch"),

        // DateTime mode collisions
        new MutexPair("DateTime: offset -> zoned", "#offset", "#zoned", ":DateTime"),
        new MutexPair("DateTime: offset -> audited", "#offset", "#audited", ":DateTime"),
        new MutexPair("DateTime: zoned -> offset", "#zoned", "#offset", ":DateTime"),
        new MutexPair("DateTime: zoned -> audited", "#zoned", "#audited", ":DateTime"),
        new MutexPair("DateTime: audited -> offset", "#audited", "#offset", ":DateTime"),
        new MutexPair("DateTime: audited -> zoned", "#audited", "#zoned", ":DateTime")
    );
    return pairs.stream().map(p -> Arguments.of(p.name(), p.parentFacet(), p.childFacet(), p.targetType()));
  }

  @ParameterizedTest(name = "[{index}] {0}: Cross-hop collision fails closed with ERR_MUTUALLY_EXCLUSIVE")
  @MethodSource("provideMutuallyExclusiveCrossHopPairs")
  @DisplayName("NOM-MUTEX-01: Conflicting mutually exclusive facets across alias hops fail closed")
  void testCrossHopMutualExclusivity(String testName, String parentFacet, String childFacet, String targetType) {
    String source = """
        {
          :defs {
            :Parent { %s } %s
            :Child { %s } :Parent
          }
          :type :Int
          :body 0
        }
        """.formatted(parentFacet, targetType, childFacet);

    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Mutually exclusive collision " + testName + " must fail compilation");

    boolean matched = result.diagnostics().stream().anyMatch(d -> {
      String code = d.errorCode().orElse(null);
      return DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE.equals(code) || "MUTUALLY_EXCLUSIVE_BOUNDS".equals(code);
    });

    assertTrue(matched, () -> "Expected ERR_MUTUALLY_EXCLUSIVE for " + testName + " but got: " +
        result.diagnostics().stream().map(d -> d.errorCode().orElse(null) + ": " + d.message()).toList());

    // Coordinate pinning check: Pinned token must cover child facet
    StvnDiagnostic diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE.equals(d.errorCode().orElse(null)) || "MUTUALLY_EXCLUSIVE_BOUNDS".equals(d.errorCode().orElse(null)))
        .findFirst().orElseThrow();
    String snippet = source.substring(diag.startOffset(), diag.endOffset());
    String bareChildTag = childFacet.split(" ")[0];
    assertTrue(snippet.contains(bareChildTag), "Pinned diagnostic token must cover child facet " + bareChildTag + ", got: '" + snippet + "'");
  }

  private record UhohCase(String id, String source, String expectedDiagnostic, String targetToken) {}

  static Stream<Arguments> provideUhohNineCases() {
    List<UhohCase> cases = List.of(
        new UhohCase("TC-OVERRIDE-01", """
            {
              :defs {
                :Float32A { #size 32 } :Float
                :FloatExactAToExact { #exact } :Float32A
              }
              :type :Int :body 0
            }
            """, DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE, "#exact"),

        new UhohCase("TC-OVERRIDE-02", """
            {
              :defs {
                :FloatExactA { #exact } :Float
                :FloatExactATo32 { #size 32 } :FloatExactA
              }
              :type :Int :body 0
            }
            """, DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE, "#size 32"),

        new UhohCase("TC-OVERRIDE-03", """
            {
              :defs {
                :FloatExactA { #exact } :Float
                :FloatExactATo64 { #size 64 } :FloatExactA
              }
              :type :Int :body 0
            }
            """, DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE, "#size 64"),

        new UhohCase("TC-OVERRIDE-04", """
            {
              :defs {
                :Float32A { #size 32 } :Float
                :Float32AToExact { #exact } :Float32A
              }
              :type :Int :body 0
            }
            """, DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE, "#exact"),

        new UhohCase("TC-OVERRIDE-05", """
            {
              :defs {
                :Float32A { #size 32 } :Float
                :Float32ATo32 { #size 32 } :Float32A
              }
              :type :Int :body 0
            }
            """, DiagnosticBag.ERR_NON_OVERRIDABLE_FACET, "#size 32"),

        new UhohCase("TC-OVERRIDE-06", """
            {
              :defs {
                :Float32A { #size 32 } :Float
                :Float32ATo64 { #size 64 } :Float32A
              }
              :type :Int :body 0
            }
            """, DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE, "#size 64"),

        new UhohCase("TC-OVERRIDE-07", """
            {
              :defs {
                :Float64A { #size 64 } :Float
                :Float64AToExact { #exact } :Float64A
              }
              :type :Int :body 0
            }
            """, DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE, "#exact"),

        new UhohCase("TC-OVERRIDE-08", """
            {
              :defs {
                :Float64A { #size 64 } :Float
                :Float64ATo32 { #size 32 } :Float64A
              }
              :type :Int :body 0
            }
            """, DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE, "#size 32"),

        new UhohCase("TC-OVERRIDE-09", """
            {
              :defs {
                :Float64A { #size 64 } :Float
                :Float64ATo64 { #size 64 } :Float64A
              }
              :type :Int :body 0
            }
            """, DiagnosticBag.ERR_NON_OVERRIDABLE_FACET, "#size 64")
    );

    return cases.stream().map(c -> Arguments.of(c.id(), c.source(), c.expectedDiagnostic(), c.targetToken()));
  }

  @ParameterizedTest(name = "[{index}] {0} -> Expect: {2}")
  @MethodSource("provideUhohNineCases")
  @DisplayName("TC-UHOH-9: Authoritative verification of all 9 uhoh.stvn nominal override cases")
  void testUhohNineCases(String caseId, String source, String expectedDiagnostic, String targetToken) {
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Test case " + caseId + " must fail compilation");

    boolean matched = result.diagnostics().stream().anyMatch(d -> {
      String code = d.errorCode().orElse(null);
      if (expectedDiagnostic.equals(code)) return true;
      if ("MUTUALLY_EXCLUSIVE_BOUNDS".equals(expectedDiagnostic) && "ERR_MUTUALLY_EXCLUSIVE".equals(code)) return true;
      if ("ERR_MUTUALLY_EXCLUSIVE".equals(expectedDiagnostic) && "MUTUALLY_EXCLUSIVE_BOUNDS".equals(code)) return true;
      return false;
    });

    assertTrue(matched, () -> "Fixture " + caseId + " expected diagnostic '" + expectedDiagnostic + "' but got: " +
        result.diagnostics().stream().map(d -> d.errorCode().orElse(null) + ": " + d.message()).toList());

    // Coordinate pinning check
    StvnDiagnostic diag = result.diagnostics().stream()
        .filter(d -> {
          String code = d.errorCode().orElse(null);
          return expectedDiagnostic.equals(code) ||
              ("MUTUALLY_EXCLUSIVE_BOUNDS".equals(expectedDiagnostic) && "ERR_MUTUALLY_EXCLUSIVE".equals(code)) ||
              ("ERR_MUTUALLY_EXCLUSIVE".equals(expectedDiagnostic) && "MUTUALLY_EXCLUSIVE_BOUNDS".equals(code));
        })
        .findFirst().orElseThrow();

    String snippet = source.substring(diag.startOffset(), diag.endOffset());
    assertTrue(snippet.contains(targetToken.split(" ")[0]),
        "Offending diagnostic coordinate must span '" + targetToken + "', got: '" + snippet + "'");
  }
}
