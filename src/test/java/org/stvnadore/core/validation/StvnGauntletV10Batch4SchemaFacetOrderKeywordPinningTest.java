package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.ir.StvnValue;

import java.util.Optional;

/**
 * Conformance regression suite for Gauntlet v10 Batch 4:
 * <ul>
 *   <li>Strict keyword-only {@code ERR_FACET_ORDER_VIOLATION} ({@code E008}) coordinate pinning
 *       across all schema {@code metadataMap} ({@code { ... }}) facet alternatives (Stage 12
 *       {@code :DisorderedValveTag}, nominal aliases, {@code #CONST} declarations, inline
 *       composite constructors, root {@code :type}, and standalone orphan {@code :type { ... }}).</li>
 *   <li>Zero-whitespace lexical adjacency ({@code kw.endOffset() == val.startOffset()}) non-overlap
 *       and co-located {@code W003} ({@code WARN_REDUNDANT_METADATA_FACET}) span distinctness.</li>
 *   <li>Exact span invariance across all non-ordering metadata diagnostics ({@code ERR_DUPLICATE_METADATA_FACET},
 *       {@code ERR_INVALID_METADATA_FACET}, {@code ERR_NON_OVERRIDABLE_FACET}, {@code FLOAT_INVALID_SIZE},
 *       {@code ERR_STRING_CARDINALITY_PROHIBITED}, {@code ERR_DISCRETE_BOUND_KIND_PROHIBITED},
 *       {@code INVALID_NUMERIC_RANGE}, {@code ERR_EMPTY_INTERVAL_DOMAIN}, {@code ERR_MUTUALLY_EXCLUSIVE},
 *       and {@code WARN_REDUNDANT_METADATA_FACET}).</li>
 *   <li>Stage 11 circular {@code #CONST_PING <-> #CONST_PONG} {@code :body} error suppression,
 *       {@code StvnDefsCatalog} omission, and {@link StvnValue.StvnErrorValue} payload sentinel recording.</li>
 * </ul>
 */
@NullMarked
class StvnGauntletV10Batch4SchemaFacetOrderKeywordPinningTest {

  @Test
  @DisplayName("Batch 4 Stage 12 Reproduction: :DisorderedValveTag pins all 6 out-of-order facets strictly to keyword tokens and preserves Pass 3 :body E012")
  void testDisorderedValveTagExactKeywordPinningAndNonPoisoningBody() {
    var source = """
        {
          :defs {
            :DisorderedValveTag {
              #regex "^VLV-[A-Z0-9]{2,8}$"
              #preserveIndent #TRUE
              #maxIncl "VLV-ZZZZ"
              #minIncl "VLV-0000"
              #comparable #FALSE
              #maxSize 12
              #minSize 6
            } :String
          }
          :type :DisorderedValveTag
          :body "VLV"
        }
        """.replace("\r\n", "\n");

    var res = StvnCompiler.compile(source, "valve_tag.stvn", StvnParserConfig.DEFAULT);
    var diags = res.diagnostics();
    Assertions.assertEquals(7, diags.size(), () -> "Expected 6x E008 order errors + 1x Pass 3 E012: " + diags);

    assertExactSlice(source, diags.get(0), "#preserveIndent", "E008", DiagnosticBag.ERR_FACET_ORDER_VIOLATION);
    assertExactSlice(source, diags.get(1), "#maxIncl", "E008", DiagnosticBag.ERR_FACET_ORDER_VIOLATION);
    assertExactSlice(source, diags.get(2), "#minIncl", "E008", DiagnosticBag.ERR_FACET_ORDER_VIOLATION);
    assertExactSlice(source, diags.get(3), "#comparable", "E008", DiagnosticBag.ERR_FACET_ORDER_VIOLATION);
    assertExactSlice(source, diags.get(4), "#maxSize", "E008", DiagnosticBag.ERR_FACET_ORDER_VIOLATION);
    assertExactSlice(source, diags.get(5), "#minSize", "E008", DiagnosticBag.ERR_FACET_ORDER_VIOLATION);
    assertExactSlice(source, diags.get(6), "\"VLV\"", "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION);

    Assertions.assertTrue(diags.get(0).endOffset() < source.indexOf("#TRUE"));
    Assertions.assertTrue(diags.get(1).endOffset() < source.indexOf("\"VLV-ZZZZ\""));
    Assertions.assertTrue(diags.get(2).endOffset() < source.indexOf("\"VLV-0000\""));
    Assertions.assertTrue(diags.get(3).endOffset() < source.indexOf("#FALSE"));
    Assertions.assertTrue(diags.get(4).endOffset() < source.indexOf("12"));
    Assertions.assertTrue(diags.get(5).endOffset() < source.indexOf("6\n"));
  }

  @Test
  @DisplayName("Batch 4 Grammar Totality: Every metadataEntry alternative pins ERR_FACET_ORDER_VIOLATION strictly to its keyword across types, constants, inline constructors, and standalone :type")
  void testAllMetadataEntryAlternativesPinStrictlyToKeywordToken() {
    var multiSurfaceSource = """
        {
          :defs {
            :SensorGain {
              #maxExcl 100.0
              #minExcl 0.0
              #equatable #TRUE
              #size 32
            } :Float
            :ExactDecimal {
              #maxSize 18
              #exact
            } :Float
            :InvertibleLookup {
              #minSize 1
              #invertible
            } :Map( :String :String )
            :EpochNanos { #minIncl 0 #ns } :TimeEpoch
            :EpochMicros { #minIncl 0 #us } :TimeEpoch
            :EpochMillis { #minIncl 0 #ms } :TimeEpoch
            :EpochSeconds { #minIncl 0 #s } :TimeEpoch
            :DtOffset { #minIncl "2026-01-01T00:00:00Z" #offset } :DateTime
            :DtZoned { #minIncl "2026-01-01T00:00:00[UTC]" #zoned } :DateTime
            :DtAudited { #minIncl "2026-01-01T00:00:00Z[UTC]" #audited } :DateTime
            :InlineSeq :Seq( { #maxSize 4 #minSize 1 } :String )
            #BAD_CONST { #minIncl 1 #size 16 #unsigned } :Int 4
          }
          :type { #maxIncl "Z" #minIncl "A" } :String
          :body "M"
        }
        """.replace("\r\n", "\n");

    var res = StvnCompiler.compile(multiSurfaceSource, "multi_surface.stvn", StvnParserConfig.DEFAULT);
    var orderSlices = res.diagnostics().stream()
        .filter(d -> Optional.of(DiagnosticBag.ERR_FACET_ORDER_VIOLATION).equals(d.symbolicId()))
        .map(d -> multiSurfaceSource.substring(d.startOffset(), d.endOffset()))
        .toList();

    Assertions.assertEquals(
        java.util.List.of(
            "#minExcl",
            "#equatable",
            "#size",
            "#exact",
            "#invertible",
            "#ns",
            "#us",
            "#ms",
            "#s",
            "#offset",
            "#zoned",
            "#audited",
            "#minSize",
            "#size",
            "#unsigned",
            "#minIncl"
        ),
        orderSlices,
        () -> "Unexpected keyword slices across schema surfaces: " + res.diagnostics()
    );

    // Standalone orphan :type { ... } exercising #filterIncl and #filterExcl keyword-only pinning
    var orphanFilterIncl = "{ :type { #regex \"^VLV$\" #filterIncl [ #A ] } :body 0 }";
    var inclRes = StvnCompiler.compile(orphanFilterIncl, "orphan_incl.stvn", StvnParserConfig.DEFAULT);
    assertExactSlice(orphanFilterIncl, inclRes.diagnostics().getFirst(), "#filterIncl", "E008", DiagnosticBag.ERR_FACET_ORDER_VIOLATION);

    var orphanFilterExcl = "{ :type { #regex \"^VLV$\" #filterExcl [ #B ] } :body 0 }";
    var exclRes = StvnCompiler.compile(orphanFilterExcl, "orphan_excl.stvn", StvnParserConfig.DEFAULT);
    assertExactSlice(orphanFilterExcl, exclRes.diagnostics().getFirst(), "#filterExcl", "E008", DiagnosticBag.ERR_FACET_ORDER_VIOLATION);
  }

  @Test
  @DisplayName("Batch 4 Zero-Whitespace Adjacency & W003 Co-Existence: kw.endOffset() == val.startOffset() and E008 keyword span stays strictly narrower than W003 entry span")
  void testZeroWhitespaceAdjacencyAndW003SpanDistinctness() {
    var compactSource = "{:defs{:V{#regex\"^a$\"#preserveIndent#TRUE#maxIncl\"z\"#minIncl\"a\"#comparable#FALSE#maxSize 9#minSize 1}:String}:type :V :body\"a\"}";
    var compactRes = StvnCompiler.compile(compactSource, "compact.stvn", StvnParserConfig.DEFAULT);
    var compactDiags = compactRes.diagnostics();
    Assertions.assertEquals(6, compactDiags.size(), () -> "Expected 6x keyword-only order E008: " + compactDiags);

    assertExactSlice(compactSource, compactDiags.get(0), "#preserveIndent", "E008", DiagnosticBag.ERR_FACET_ORDER_VIOLATION);
    Assertions.assertEquals(compactSource.indexOf("#TRUE"), compactDiags.get(0).endOffset());

    assertExactSlice(compactSource, compactDiags.get(1), "#maxIncl", "E008", DiagnosticBag.ERR_FACET_ORDER_VIOLATION);
    Assertions.assertEquals(compactSource.indexOf("\"z\""), compactDiags.get(1).endOffset());

    assertExactSlice(compactSource, compactDiags.get(2), "#minIncl", "E008", DiagnosticBag.ERR_FACET_ORDER_VIOLATION);
    Assertions.assertEquals(compactSource.indexOf("\"a\""), compactDiags.get(2).endOffset());

    assertExactSlice(compactSource, compactDiags.get(3), "#comparable", "E008", DiagnosticBag.ERR_FACET_ORDER_VIOLATION);
    Assertions.assertEquals(compactSource.indexOf("#FALSE"), compactDiags.get(3).endOffset());

    assertExactSlice(compactSource, compactDiags.get(4), "#maxSize", "E008", DiagnosticBag.ERR_FACET_ORDER_VIOLATION);
    assertExactSlice(compactSource, compactDiags.get(5), "#minSize", "E008", DiagnosticBag.ERR_FACET_ORDER_VIOLATION);

    // Co-located E008 (#size) + W003 (#size 32): E008 ends strictly before W003 ends so IDE warnings are never swallowed
    var tautologySource = """
        {
          :defs {
            :RedundantDisorderedInt { #minIncl 10 #size 32 } :Int
          }
          :type :RedundantDisorderedInt
          :body 12
        }
        """.replace("\r\n", "\n");
    var tautologyRes = StvnCompiler.compile(tautologySource, "tautology.stvn", StvnParserConfig.DEFAULT);
    Assertions.assertEquals(2, tautologyRes.diagnostics().size(), () -> "Expected E008 + W003: " + tautologyRes.diagnostics());
    var orderDiag = tautologyRes.diagnostics().get(0);
    var w003Diag = tautologyRes.diagnostics().get(1);
    assertExactSlice(tautologySource, orderDiag, "#size", "E008", DiagnosticBag.ERR_FACET_ORDER_VIOLATION);
    assertExactSlice(tautologySource, w003Diag, "#size 32", "W003", DiagnosticBag.WARN_REDUNDANT_METADATA_FACET);
    Assertions.assertTrue(orderDiag.endOffset() < w003Diag.endOffset());
  }

  @Test
  @DisplayName("Batch 4 Non-Ordering Span Invariance: All non-ordering metadata diagnostics retain exact existing spans")
  void testNonOrderingMetadataDiagnosticsRetainExactExistingSpans() {
    var source = """
        {
          :defs {
            :BaseInt { #unsigned #size 16 } :Int
            :OverrideErr { #size 8 } :BaseInt
            :DupErr { #minSize 1 #minSize 2 } :String
            :StrSizeErr { #size 8 } :String
            :DiscreteErr { #maxIncl 10 } :Int
            :FloatSizeErr { #size 16 } :Float
            :MutexErr { #minIncl 0.0 #minExcl 1.0 } :Float
            :EmptyIntervalErr { #minIncl 5 #maxExcl 5 } :Int
            :StripOnTypeErr { #strip } :Int
            :IllegalStandaloneArgErr { #unsigned #TRUE } :Int
          }
          :type :Int
          :body 0
        }
        """.replace("\r\n", "\n");

    var res = StvnCompiler.compile(source, "span_invariance.stvn", StvnParserConfig.DEFAULT);
    var diags = res.diagnostics();
    Assertions.assertEquals(9, diags.size(), () -> "Unexpected diagnostic count: " + diags);

    assertExactSlice(source, diags.get(0), "#size 8", "E040", DiagnosticBag.ERR_NON_OVERRIDABLE_FACET);
    assertExactSlice(source, diags.get(1), "#minSize 2", "E008", DiagnosticBag.ERR_DUPLICATE_METADATA_FACET);
    assertExactSlice(source, diags.get(2), "#size 8", "E008", DiagnosticBag.ERR_STRING_CARDINALITY_PROHIBITED);
    assertExactSlice(source, diags.get(3), "#maxIncl 10", "E011", DiagnosticBag.ERR_DISCRETE_BOUND_KIND_PROHIBITED);
    assertExactSlice(source, diags.get(4), "#size 16", "E010", DiagnosticBag.ERR_FLOAT_INVALID_SIZE);
    assertExactSlice(source, diags.get(5), "#minExcl 1.0", "E008", DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE);
    assertExactSlice(source, diags.get(6), "#minIncl 5 #maxExcl 5", "E009", DiagnosticBag.ERR_EMPTY_INTERVAL_DOMAIN);
    assertExactSlice(source, diags.get(7), "#strip", "E008", DiagnosticBag.ERR_INVALID_METADATA_FACET);
    assertExactSlice(source, diags.get(8), "#TRUE", "E008", DiagnosticBag.ERR_INVALID_METADATA_FACET);
  }

  @Test
  @DisplayName("Batch 4 Stage 11 Contract: Circular #CONST_PING <-> #CONST_PONG suppresses :body errors, omits from StvnDefsCatalog, and records StvnErrorValue sentinel")
  void testStage11CircularConstBodySuppressionAndCatalogOmissionContract() {
    var source = """
        {
          :defs {
            #CONST_PING :Int #CONST_PONG
            #CONST_PONG :Int #CONST_PING
          }
          :type :Tuple( :Int { #minIncl 10 } :Int )
          :body ( #CONST_PING 3 )
        }
        """.replace("\r\n", "\n");

    var res = StvnCompiler.compile(source, "const_ping.stvn", StvnParserConfig.DEFAULT);
    var diags = res.diagnostics();
    Assertions.assertFalse(res.hasWarnings(), () -> "Core must emit 0 warnings on poisoned #CONST_PING: " + diags);
    Assertions.assertEquals(3, diags.size(), () -> "Expected 2x :defs E007 + 1x healthy sibling E012: " + diags);

    assertExactSlice(source, diags.get(0), "#CONST_PING :Int #CONST_PONG", "E007", DiagnosticBag.ERR_CIRCULAR_TYPE);
    assertExactSlice(source, diags.get(1), "#CONST_PONG :Int #CONST_PING", "E007", DiagnosticBag.ERR_CIRCULAR_TYPE);
    assertExactSlice(source, diags.get(2), "3", "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION);

    var doc = res.document().orElseThrow();
    Assertions.assertTrue(doc.defs().findConstant("#CONST_PING").isEmpty(), "Poisoned #CONST_PING must be omitted from StvnDefsCatalog");
    Assertions.assertTrue(doc.defs().findConstant("#CONST_PONG").isEmpty(), "Poisoned #CONST_PONG must be omitted from StvnDefsCatalog");

    var tuplePayload = Assertions.assertInstanceOf(StvnValue.StvnTuple.class, doc.requirePayload());
    Assertions.assertEquals(2, tuplePayload.elements().size());
    var pingSlot = Assertions.assertInstanceOf(StvnValue.StvnErrorValue.class, tuplePayload.elements().get(0));
    Assertions.assertEquals(Optional.of("E007"), pingSlot.diagnostic().canonicalCode());
    Assertions.assertEquals(Optional.of(DiagnosticBag.ERR_CIRCULAR_TYPE), pingSlot.diagnostic().symbolicId());
  }

  private static void assertExactSlice(
      String source,
      StvnDiagnostic diag,
      String expectedSlice,
      String expectedCanonical,
      String expectedSymbolic
  ) {
    Assertions.assertEquals(Optional.of(expectedCanonical), diag.canonicalCode(), () -> "Unexpected canonicalCode: " + diag);
    Assertions.assertEquals(Optional.of(expectedSymbolic), diag.symbolicId(), () -> "Unexpected symbolicId: " + diag);
    Assertions.assertEquals(
        expectedSlice,
        source.substring(diag.startOffset(), diag.endOffset()),
        () -> "Unexpected pinned slice for " + diag
    );
  }
}
