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
import org.stvnadore.core.ir.StvnSchema;
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

  private static String parentFacetSyntax(MetadataMatrixRow row) {
    if ("maxSize".equals(row.tagName()) && "exact".equals(row.domainQualifier())) {
      return "#exact #maxSize 10";
    }
    return facetSyntax(row);
  }

  static Stream<Arguments> provideNonOverridableRows() {
    List<MetadataMatrixRow> rows = MetadataMatrixReader.readDefaultMatrix();
    List<Arguments> testCases = new ArrayList<>();
    for (MetadataMatrixRow row : rows) {
      if (!row.overridable() && !"Directive".equalsIgnoreCase(row.category())) {
        String parentFacet = parentFacetSyntax(row);
        String childFacet = facetSyntax(row);
        String targetType = targetTypeSyntax(row);
        testCases.add(Arguments.of(row.position(), row.modifying(), row.tagName(), parentFacet, childFacet, targetType));
      }
    }
    return testCases.stream();
  }

  @ParameterizedTest(name = "[{index}] Row {0} ({1} #{2}): 2-hop re-specification fails closed with ERR_NON_OVERRIDABLE_FACET")
  @MethodSource("provideNonOverridableRows")
  @DisplayName("NOM-OVERRIDE-01: Re-declaring non-overridable facets across nominal alias hops fails closed")
  void testNonOverridableFacetReDeclaration(
      String position, String modifying, String tagName, String parentFacetSyntax, String childFacetSyntax, String targetType) {
    String source = """
        {
          :defs {
            :Parent { %s } %s
            :Child { %s } :Parent
          }
          :type :Int
          :body 0
        }
        """.formatted(parentFacetSyntax, targetType, childFacetSyntax);

    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Re-declaration of non-overridable facet " + childFacetSyntax + " on " + modifying + " must fail");

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
        new MutexPair("Float: size 64 -> exact", "#size 64", "#exact", ":Float")
    );
    return pairs.stream().map(p -> Arguments.of(p.name(), p.parentFacet(), p.childFacet(), p.targetType()));
  }

  static Stream<Arguments> provideConflictingTemporalCrossHopPairs() {
    return Stream.of(
        // TimeEpoch scale vs different scale (E040)
        Arguments.of(":TimeEpoch", "#s", "#ms", "1000"),
        Arguments.of(":TimeEpoch", "#s", "#us", "1000"),
        Arguments.of(":TimeEpoch", "#s", "#ns", "1000"),
        Arguments.of(":TimeEpoch", "#ms", "#s", "1000"),
        Arguments.of(":TimeEpoch", "#us", "#s", "1000"),
        Arguments.of(":TimeEpoch", "#ns", "#s", "1000"),
        // DateTime mode vs different mode (E040)
        Arguments.of(":DateTime", "#offset", "#zoned", "\"2026-01-01T00:00:00Z\""),
        Arguments.of(":DateTime", "#offset", "#audited", "\"2026-01-01T00:00:00Z\""),
        Arguments.of(":DateTime", "#zoned", "#offset", "\"2026-01-01T00:00:00[UTC]\""),
        Arguments.of(":DateTime", "#zoned", "#audited", "\"2026-01-01T00:00:00[UTC]\""),
        Arguments.of(":DateTime", "#audited", "#offset", "\"2026-01-01T00:00:00Z[UTC]\""),
        Arguments.of(":DateTime", "#audited", "#zoned", "\"2026-01-01T00:00:00Z[UTC]\"")
    );
  }

  @ParameterizedTest(name = "Cross-Hop Temporal E040: {0} parent={1} child={2}")
  @MethodSource("provideConflictingTemporalCrossHopPairs")
  @DisplayName("TC-E040-TEMPORAL-CROSSHOP: Cross-hop conflicting temporal mode and scale overrides emit E040 (ERR_NON_OVERRIDABLE_FACET) across nominal types, #CONST, and inline slots")
  void testCrossHopConflictingTemporalModeAndScaleEmitsE040(
      String baseType,
      String parentFacet,
      String childFacet,
      String sampleBody
  ) {
    String source = """
        {
          :defs {
            :ParentType { %s } %s
            :ChildType { %s } :ParentType
            #CHILD_CONST { %s } :ParentType %s
            :InlineHolder :Tuple( { %s } :ParentType )
          }
          :type :ChildType
          :body %s
        }
        """.formatted(parentFacet, baseType, childFacet, childFacet, sampleBody, childFacet, sampleBody);

    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);

    assertTrue(result.hasErrors());
    assertEquals(3, result.diagnostics().size(), () -> "Expected 3 E040 diagnostics (nominal, #CONST, inline) and 0 :body cascade diagnostics, got: " + result.diagnostics());

    for (StvnDiagnostic d : result.diagnostics()) {
      assertEquals(DiagnosticBag.ERR_NON_OVERRIDABLE_FACET, d.errorCode().orElse(null));
      assertEquals("E040", d.canonicalCode().orElse(null));
      assertTrue(
          d.message().contains("is a non-overridable definition facet")
              && d.message().contains("already locked as '" + parentFacet + "' by ancestor :ParentType and cannot be overridden"),
          () -> "Expected locked ancestor message on E040 diagnostic, got: " + d.message()
      );
      assertEquals(childFacet, source.substring(d.startOffset(), d.endOffset()));
    }
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
            """, DiagnosticBag.ERR_NON_OVERRIDABLE_FACET, "#size 64"),

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
            """, DiagnosticBag.ERR_NON_OVERRIDABLE_FACET, "#size 32"),

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

  @Test
  @DisplayName("TC-E040-CONST-AND-DEEP: Emits exact per-facet E040 and W003 on #CONST, forward-referenced :defs, 3-hop poisoned nominal chain, and depth-3+ inline composite slots without secondary :body cascade")
  void testE040AndW003OnConstForwardRefDeepChainAndCompositeSlots() {
    String source = """
        {
          :defs {
            :ForwardDerivedPort { #unsigned #size 16 } :ServicePort
            :ServicePort { #unsigned #size 16 #minIncl 1 } :Int
            :MidPort { #unsigned #minIncl 1024 } :ServicePort
            :LeafPort { #unsigned #size 8 } :MidPort
            :DeepCompositeOverride :Tuple(
              :Seq(:Option({ #unsigned #size 16 } :ServicePort))
              :Map(:String :Either(:Boolean { #unsigned #size 8 } :LeafPort))
            )
            #FORWARD_PORT { #unsigned #size 16 } :ServicePort 8080
            #DEFAULT_PORT { #unsigned #size 16 #minIncl 1 } :ServicePort 8080
          }
          :type :LeafPort
          :body #DEFAULT_PORT
        }
        """;

    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Must fail compilation due to E040 non-overridable facet violations");

    List<StvnDiagnostic> e040s = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_NON_OVERRIDABLE_FACET.equals(d.symbolicId().orElse(null)))
        .toList();
    List<StvnDiagnostic> w003s = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.symbolicId().orElse(null)))
        .toList();

    assertEquals(13, e040s.size(), () -> "Expected 13 E040 diagnostics across forward ref, 3-hop chain, depth-3+ composite slots, and #CONST definitions, got: " + result.diagnostics());
    assertEquals(1, w003s.size(), () -> "Expected 1 W003 warning (#DEFAULT_PORT #minIncl 1), got: " + result.diagnostics());
    assertEquals(14, result.diagnostics().size(), () -> "Must not emit secondary :body cascade diagnostics when :type is poisoned, got: " + result.diagnostics());

    // Verify exact individual facet coordinate pinning on #DEFAULT_PORT (line 12)
    List<StvnDiagnostic> line12E040s = e040s.stream()
        .filter(d -> d.line() == 12)
        .toList();
    assertEquals(2, line12E040s.size(), "Line 12 (#DEFAULT_PORT) must emit 2 individual E040 diagnostics");
    assertEquals("#unsigned", source.substring(line12E040s.get(0).startOffset(), line12E040s.get(0).endOffset()));
    assertEquals("#size 16", source.substring(line12E040s.get(1).startOffset(), line12E040s.get(1).endOffset()));

    // Verify W003 coordinate on #DEFAULT_PORT (line 12)
    StvnDiagnostic w003 = w003s.get(0);
    assertEquals(12, w003.line());
    assertEquals("#minIncl 1", source.substring(w003.startOffset(), w003.endOffset()));
  }

  @Test
  @DisplayName("TC-UHOH-USER-REPRO: Rejects :Int16b { #size 16 } :Int32 with E040 when :Int32 relies on intrinsic default #size 32")
  void userReportedUhohReproductionRejectsInt16bWithE040() {
    String source = """
        {
          :meta {
            #name "uhoh"
            #kind #BODY
          }
          :defs {
            :Int32 :Int
            :Int16a { #size 16 } :Int
            :Int16b { #size 16 } :Int32
            :Values :Tuple(
              :Int32
              :Int16a
              :Int16b
            )
          }
          :type :Values
          :body (
            1
            2
            3
          )
        }
        """;

    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Document must fail compilation when derived nominal type :Int16b overrides intrinsic #size 32 on :Int32");

    List<StvnDiagnostic> e040s = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_NON_OVERRIDABLE_FACET.equals(d.symbolicId().orElse(null)))
        .toList();
    assertEquals(1, e040s.size(), () -> "Expected exactly 1 E040 diagnostic, got: " + result.diagnostics());

    StvnDiagnostic diag = e040s.get(0);
    assertEquals("E040", diag.canonicalCode().orElse(null));
    assertEquals(9, diag.line());
    assertEquals("#size 16", source.substring(diag.startOffset(), diag.endOffset()));
    assertTrue(diag.message().contains("non-overridable definition facet '#size' is already established by ancestor :Int32"));
  }

  @Test
  @DisplayName("NOM-INTRINSIC-LOCK-01: Locks all Tier-1 Overridable: No representation/identity facets at root nominal definition even when omitted")
  void intrinsicDefaultLockingAcrossAllRootTypes() {
    String source = """
        {
          :defs {
            :Int32 :Int
            :Int16b { #size 16 } :Int32
            :Int32b { #size 32 } :Int32
            :Uint32b { #unsigned } :Int32

            :Uint32a { #unsigned } :Int
            :Uint16b { #size 16 } :Uint32a

            :Int16a { #size 16 } :Int
            :Uint16c { #unsigned } :Int16a

            :Float64 :Float
            :Float32b { #size 32 } :Float64
            :Float64b { #size 64 } :Float64
            :ExactFloatB { #exact } :Float64

            :ExactFloatA { #exact } :Float
            :ExactFloatMaxB { #maxSize 18 } :ExactFloatA

            :ImplicitStr :String
            :DerivedStrMin { #minSize 1 } :ImplicitStr
            :DerivedStrMax { #maxSize 64 } :ImplicitStr

            :ImplicitSeq :Seq( :Int )
            :DerivedSeqMax { #maxSize 16 } :ImplicitSeq

            :ImplicitSet :Set( :Int )
            :DerivedSetMin { #minSize 1 } :ImplicitSet

            :PlainMap :Map( { #maxSize 16 } :String :Int )
            :InvMapB { #invertible } :PlainMap
            :DerivedMapMax { #maxSize 32 } :PlainMap
          }
          :type :Int32
          :body 1
        }
        """;

    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Derived nominal types overriding locked intrinsic defaults must fail compilation");

    List<StvnDiagnostic> e040s = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_NON_OVERRIDABLE_FACET.equals(d.symbolicId().orElse(null)))
        .toList();
    List<StvnDiagnostic> e008s = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE.equals(d.symbolicId().orElse(null)))
        .toList();

    assertEquals(14, e040s.size(), () -> "Expected 14 E040 diagnostics across all locked Tier-1 intrinsic defaults, got: " + result.diagnostics());
    assertEquals(1, e008s.size(), () -> "Expected 1 E008 diagnostic (:ExactFloatB), got: " + result.diagnostics());
    assertEquals(15, result.diagnostics().size(), () -> "Expected zero spurious W003 or cascade diagnostics, got: " + result.diagnostics());
  }

  @Test
  @DisplayName("NOM-BOUND-OVERRIDE-01: SPEC § 6.1 & § 9.1.1 Total Nominal Bound Override across :Int, :Float, :String, :TimeEpoch, and :DateTime")
  void testMultiHopTotalNominalBoundReplacementAcrossAllFiveBoundTypes() {
    String source = """
        {
          :defs {
            :BaseInt { #unsigned #size 16 #minIncl 100 #maxExcl 10000 } :Int
            :MidInt { #minIncl 500 #maxExcl 5000 } :BaseInt
            :WidenedInt { #minIncl 200 #maxExcl 8000 } :MidInt
            :StrictInt { #minIncl 1000 #maxExcl 2000 } :WidenedInt

            :BaseFloat { #minIncl 10.0 #maxExcl 50.0 } :Float
            :WidenedFloat { #minExcl 0.0 #maxIncl 100.0 } :BaseFloat

            :BaseStr { #minSize 1 #maxSize 16 #minIncl " d" #maxExcl "m" } :String
            :WidenedStr { #minExcl "a" #maxIncl "z" } :BaseStr

            :BaseEpoch { #s #minIncl 1000 #maxExcl 2000 } :TimeEpoch
            :WidenedEpoch { #minIncl 500 #maxExcl 5000 } :BaseEpoch

            :BaseDt { #offset #minIncl "2026-06-01T00:00:00Z" #maxExcl "2026-07-01T00:00:00Z" } :DateTime
            :WidenedDt { #minIncl "2026-01-01T00:00:00Z" #maxExcl "2026-12-31T00:00:00Z" } :BaseDt

            :AllOverriden :Tuple(
              :WidenedInt
              :StrictInt
              :WidenedFloat
              :WidenedStr
              :WidenedEpoch
              :WidenedDt
            )
          }
          :type :AllOverriden
          :body (
            250
            1500
            100.0
            "z"
            4500
            "2026-03-15T12:00:00Z"
          )
        }
        """;

    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), () -> "Valid § 9.1.1 Tier-3 bound replacements must compile with 0 errors: " + result.diagnostics());
    assertTrue(result.diagnostics().isEmpty(), () -> "Expected 0 warnings and 0 errors, got: " + result.diagnostics());
  }

  @Test
  @DisplayName("NOM-BOUND-OVERRIDE-02: Partial Tier-3 bound override conflicting with inherited opposite bound fails closed with E009")
  void testCrossHopPartialBoundOverrideConflictingWithInheritedOppositeBoundEmitsE009() {
    String source = """
        {
          :defs {
            :BaseInt { #unsigned #size 16 #minIncl 500 #maxExcl 5000 } :Int
            :InvertedCrossHopInt { #minIncl 6000 } :BaseInt
            :EmptyCrossHopInt { #maxExcl 500 } :BaseInt

            :BaseFloat { #minIncl 10.0 #maxExcl 50.0 } :Float
            :InvertedCrossHopFloat { #minIncl 60.0 } :BaseFloat

            :BaseStr { #minIncl "m" #maxExcl "t" } :String
            :InvertedCrossHopStr { #minIncl "z" } :BaseStr

            :BaseEpoch { #s #minIncl 1000 #maxExcl 2000 } :TimeEpoch
            :InvertedCrossHopEpoch { #minIncl 3000 } :BaseEpoch

            :BaseDt { #offset #minIncl "2026-06-01T00:00:00Z" #maxExcl "2026-07-01T00:00:00Z" } :DateTime
            :InvertedCrossHopDt { #minIncl "2026-09-01T00:00:00Z" } :BaseDt
          }
          :type :BaseInt
          :body 1000
        }
        """;

    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Cross-hop inverted or empty effective intervals must fail compilation");
    assertEquals(6, result.diagnostics().size(), () -> "Expected 6 E009 diagnostics across all 5 scalar/temporal types, got: " + result.diagnostics());
    for (StvnDiagnostic d : result.diagnostics()) {
      assertEquals("E009", d.canonicalCode().orElse(null), () -> "Expected E009 canonical code, got: " + d);
    }
  }

  @Test
  @DisplayName("NOM-BOUND-OVERRIDE-03: Stage 4 :ReplacedBoundsCluster and :StrictClusterId validate :body ( 300 750 2500 ) with E012 on 750 and 2500")
  void testStage4NominalRefinementChainAndBodyValidation() {
    String source = """
        {
          :defs {
            :BaseClusterId { #unsigned #size 16 #minIncl 100 #maxExcl 10000 } :Int
            :RegionClusterId { #minIncl 500 #maxExcl 5000 } :BaseClusterId
            :ReplacedBoundsCluster { #minIncl 200 #maxExcl 8000 } :RegionClusterId
            :StrictClusterId { #minIncl 1000 #maxExcl 2000 } :ReplacedBoundsCluster
            :Stage4V3Envelope :Tuple(
              :ReplacedBoundsCluster
              :StrictClusterId
              :StrictClusterId
            )
          }
          :type :Stage4V3Envelope
          :body (
            300
            750
            2500
          )
        }
        """;

    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Out-of-bounds payload values 750 and 2500 must fail :StrictClusterId [1000, 2000) validation");
    assertEquals(2, result.diagnostics().size(), () -> "Expected strictly 2 E012 payload diagnostics on 750 and 2500, got: " + result.diagnostics());

    StvnDiagnostic d0 = result.diagnostics().get(0);
    assertEquals("E012", d0.canonicalCode().orElse(null));
    assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, d0.symbolicId().orElse(null));
    assertEquals("750", source.substring(d0.startOffset(), d0.endOffset()));
    assertTrue(d0.message().contains("Constraint violation (:StrictClusterId): Value 750 is outside allowable range (must be >= 1000)"));

    StvnDiagnostic d1 = result.diagnostics().get(1);
    assertEquals("E012", d1.canonicalCode().orElse(null));
    assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, d1.symbolicId().orElse(null));
    assertEquals("2500", source.substring(d1.startOffset(), d1.endOffset()));
    assertTrue(d1.message().contains("Constraint violation (:StrictClusterId): Value 2500 is outside allowable range (must be < 2000)"));
  }

  @Test
  @DisplayName("VOP-POISONED-SCHEMA-01: SPEC § 7.3.5 Grounded vs. Ungrounded PoisonedSchema predicates and per-slot :body fault isolation")
  void testGroundedVsUngroundedPoisonedSchemaContractAndSlotFaultIsolation() {
    String source = """
        {
          :defs {
            :CycleAlpha :CycleBeta
            :CycleBeta :CycleAlpha
            :UnresolvedRef :NonExistentParent
            :BaseClusterId { #unsigned #size 16 #minIncl 100 #maxExcl 10000 } :Int
            :IllegalSizeOverride { #size 32 } :BaseClusterId
            :InvertedBounds { #minIncl 5000 #maxExcl 100 } :Int
            :EmptyMeta {} :Int
            :HealthyPort { #unsigned #size 16 #minIncl 1024 } :Int
            :TestEnvelope :Tuple(
              :CycleAlpha
              :UnresolvedRef
              :IllegalSizeOverride
              :InvertedBounds
              :HealthyPort
            )
          }
          :type :TestEnvelope
          :body (
            "suspended-ungrounded-cycle"
            "suspended-ungrounded-unresolved"
            "suspended-grounded-override"
            "suspended-grounded-inverted"
            80
          )
        }
        """;

    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors());
    var doc = result.document().orElseThrow();
    var catalog = doc.defs();

    // Case A: Ungrounded PoisonedSchema (isUngrounded() == true, isGrounded() == false)
    var cycleAlpha = assertInstanceOf(StvnSchema.PoisonedSchema.class, catalog.findType(":CycleAlpha").orElseThrow());
    assertTrue(cycleAlpha.isUngrounded());
    assertFalse(cycleAlpha.isGrounded());
    assertTrue(cycleAlpha.bestEffortFallback().isEmpty());

    var unresolvedRef = assertInstanceOf(StvnSchema.PoisonedSchema.class, catalog.findType(":UnresolvedRef").orElseThrow());
    assertTrue(unresolvedRef.isUngrounded());
    assertFalse(unresolvedRef.isGrounded());
    assertTrue(unresolvedRef.bestEffortFallback().isEmpty());

    // Case B: Grounded PoisonedSchema (isGrounded() == true, isUngrounded() == false)
    var illegalSize = assertInstanceOf(StvnSchema.PoisonedSchema.class, catalog.findType(":IllegalSizeOverride").orElseThrow());
    assertTrue(illegalSize.isGrounded());
    assertFalse(illegalSize.isUngrounded());
    assertInstanceOf(StvnSchema.IntSchema.class, illegalSize.bestEffortFallback().orElseThrow());

    var invertedBounds = assertInstanceOf(StvnSchema.PoisonedSchema.class, catalog.findType(":InvertedBounds").orElseThrow());
    assertTrue(invertedBounds.isGrounded());
    assertFalse(invertedBounds.isUngrounded());
    assertInstanceOf(StvnSchema.IntSchema.class, invertedBounds.bestEffortFallback().orElseThrow());

    var emptyMeta = assertInstanceOf(StvnSchema.PoisonedSchema.class, catalog.findType(":EmptyMeta").orElseThrow());
    assertTrue(emptyMeta.isGrounded());
    assertFalse(emptyMeta.isUngrounded());
    assertInstanceOf(StvnSchema.IntSchema.class, emptyMeta.bestEffortFallback().orElseThrow());

    // Verify Pass 3 :body fault isolation: slots 0..3 emit 0 errors; healthy slot 4 (80) emits E012
    List<StvnDiagnostic> bodyDiagnostics = result.diagnostics().stream()
        .filter(d -> d.line() >= 20)
        .toList();
    assertEquals(1, bodyDiagnostics.size(), () -> "Expected strictly 1 :body diagnostic on healthy slot 4 (80), got: " + bodyDiagnostics);
    assertEquals("E012", bodyDiagnostics.get(0).canonicalCode().orElse(null));
    assertEquals("80", source.substring(bodyDiagnostics.get(0).startOffset(), bodyDiagnostics.get(0).endOffset()));
  }
}
