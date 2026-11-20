package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.ir.StvnSchema;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conformance regression suite for Gauntlet v9 Batch 2 ({@code CA-01}, {@code CA-02}, and {@code CA-03}):
 * surgical contiguous bound-pair {@code E009} coordinate pinning, depth-aware mismatched closing fence
 * recovery without {@code :body} blackout, and Pass 2 tripartite {@code :DateTime} bound mode parity.
 */
@NullMarked
class StvnGauntletV9Batch2ParityTest {

  // ===========================================================================
  // Target CA-01 — Surgical E009 Bound-Pair Coordinate Pinning
  // ===========================================================================

  @Test
  @DisplayName("CA-01a: Contiguous inverted bounds pin [#minIncl .. #maxIncl) while excluding '{', sibling #maxSize 8, and '}'")
  void testContiguousBoundsExcludeBracesAndSiblingFacets() {
    String source = """
        {
          :defs {
            :TrapInvertedStringBounds { #maxSize 8 #minIncl "Z" #maxIncl "A" } :String
            :TrapInvertedSeqSize { #minSize 12 #maxSize 4 } :Seq( :Int )
            :TrapInvertedFloat { #size 32 #minExcl 9.5 #maxIncl 1.5 } :Float
            :TrapInvertedEpoch { #s #minIncl 200 #maxExcl 100 } :TimeEpoch
            :TrapEmptyDateTime { #offset #minIncl "2026-05-01T00:00:00Z" #maxExcl "2026-05-01T00:00:00Z" } :DateTime
          }
          :type :Int
          :body 1
        }
        """;

    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors());
    assertEquals(5, result.diagnostics().size(), () -> "Expected 5 E009 diagnostics, got: " + result.diagnostics());

    assertExactSlice(source, result.diagnostics().get(0), "#minIncl \"Z\" #maxIncl \"A\"");
    assertExactSlice(source, result.diagnostics().get(1), "#minSize 12 #maxSize 4");
    assertExactSlice(source, result.diagnostics().get(2), "#minExcl 9.5 #maxIncl 1.5");
    assertExactSlice(source, result.diagnostics().get(3), "#minIncl 200 #maxExcl 100");
    assertExactSlice(source, result.diagnostics().get(4), "#minIncl \"2026-05-01T00:00:00Z\" #maxExcl \"2026-05-01T00:00:00Z\"");
  }

  @Test
  @DisplayName("CA-01b: Non-adjacent inverted bounds with intervening facet pin strictly to later bound entry and cross-hop overrides pin strictly to child bound")
  void testNonAdjacentAndCrossHopBoundsPinSurgicalSingleEntry() {
    String source = """
        {
          :defs {
            :NonAdj { #maxIncl 5 #size 16 #minIncl 10 } :Int
            :ParentPort { #unsigned #size 16 #minIncl 100 #maxExcl 500 } :Int
            :ChildInverted { #minIncl 900 } :ParentPort
          }
          :type :Int
          :body 1
        }
        """;

    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors());

    var nonAdjRangeDiag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_INVERTED_RANGE.equals(d.symbolicId().orElse(null))
            && d.message().contains(":NonAdj"))
        .findFirst()
        .orElseThrow();
    assertExactSlice(source, nonAdjRangeDiag, "#minIncl 10");

    var crossHopDiag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_INVERTED_RANGE.equals(d.symbolicId().orElse(null))
            && d.message().contains(":ChildInverted"))
        .findFirst()
        .orElseThrow();
    assertExactSlice(source, crossHopDiag, "#minIncl 900");
  }

  // ===========================================================================
  // Target CA-02 — Stage 4 :body Recovery & Mismatched Closing Fence Isolation
  // ===========================================================================

  @Test
  @DisplayName("CA-02a: Stage 4 12-slot :body preserves E012/E013 on sibling slots alongside untagged E025, mismatched [SQL]..[JSON] E025, and bracketed SQL array syntax")
  void testStage4BodyPreservesAllSiblingDiagnosticsAcrossMalformedFences() {
    String source = """
        {
          :defs {
            :FixedCode { #minSize 4 #maxSize 6 #regex "^[A-Z0-9]+$" } :String
            :BrokenRegex { #regex "[unclosed" } :String
            :Stage4StringMatrix :Tuple(
              :FixedCode
              :FixedCode
              :FixedCode
              :FixedCode
              :String
              :String
              :BrokenRegex
            )
          }
          :type :Stage4StringMatrix
          :body (
            "B0"
            "ZZ99_TOO_LONG"
            "bad!"
            "OK99"
            \"\"\"
              SELECT 1 FROM missing_tag;
            \"\"\"
            \"\"\"[SQL]
              SELECT arr[0] FROM mismatched_close;
            arr[1] [JSON]\"\"\"
            "suspended-broken-regex-slot"
          )
        }
        """;

    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors());
    assertTrue(result.document().isPresent(), "Pass 3 must run and produce partial document with StvnErrorValue slots");

    // Verify zero E012/E013 blackout on healthy sibling slots
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        "E012".equals(d.canonicalCode().orElse(null)) && d.startOffset() == source.indexOf("\"B0\"")));
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        "E012".equals(d.canonicalCode().orElse(null)) && d.startOffset() == source.indexOf("\"ZZ99_TOO_LONG\"")));
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        "E013".equals(d.canonicalCode().orElse(null)) && d.startOffset() == source.indexOf("\"bad!\"")));

    // Verify Slot 10 (missing tag) and Slot 11 (mismatched [JSON]""" with preceding arr[0] / arr[1]) each emit exactly one E025
    long e025Count = result.diagnostics().stream()
        .filter(d -> "E025".equals(d.canonicalCode().orElse(null)))
        .count();
    assertEquals(2L, e025Count, () -> "Expected exactly 2 E025 diagnostics without duplicates, got: " + result.diagnostics());

    var mismatchDiag = result.diagnostics().stream()
        .filter(d -> d.message().contains("found mismatched closing tag '[JSON]'"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Expected mismatched [JSON] diagnostic, got: " + result.diagnostics()));
    assertExactSlice(source, mismatchDiag, "\"\"\"[SQL]\n");

    // Verify Slot 12 governed by poisoned :BrokenRegex emits zero :body diagnostics
    int suspendedStart = source.indexOf("\"suspended-broken-regex-slot\"");
    assertFalse(result.diagnostics().stream().anyMatch(d -> d.startOffset() == suspendedStart));
  }

  @Test
  @DisplayName("CA-02b: Nested polyglot fences remain 100% valid when outer closing tag exists, and nested inner fence inside mismatched outer fence pops at outer mismatch")
  void testNestedPolyglotFencesAndTabAfterMismatchedClose() {
    String validNested = """
        {
          :type :String
          :body \"\"\"[OUTER]
            raw [UNBALANCED]\"\"\" text
            \"\"\"[INNER]
            \tverbatim_tab_ok
            [INNER]\"\"\"
          [OUTER]\"\"\"
        }
        """;
    var validRes = StvnCompiler.compileToResult(validNested, null, StvnParserConfig.DEFAULT);
    assertTrue(validRes.isSuccess(), () -> "Nested polyglot fence with verbatim tab must succeed: " + validRes.diagnostics());

    String mismatchedOuterWithNestedInnerAndLaterTab = "{\n"
        + "  :type :Tuple( :String :Int )\n"
        + "  :body (\n"
        + "    \"\"\"[OUTER]\n"
        + "      \"\"\"[INNER]\n"
        + "      nested body\n"
        + "      [INNER]\"\"\"\n"
        + "    [WRONG_OUTER]\"\"\"\n"
        + "    \t\"not_an_int\"\n"
        + "  )\n"
        + "}\n";
    var misRes = StvnCompiler.compileToResult(mismatchedOuterWithNestedInnerAndLaterTab, null, StvnParserConfig.DEFAULT);
    assertTrue(misRes.hasErrors());
    assertTrue(misRes.diagnostics().stream().anyMatch(d ->
        d.message().contains("expected closing delimiter '[OUTER]\"\"\"', found mismatched closing tag '[WRONG_OUTER]'")));
    assertTrue(misRes.diagnostics().stream().anyMatch(d ->
        DiagnosticBag.ERR_TAB_CHARACTER_FORBIDDEN.equals(d.symbolicId().orElse(null))),
        "Tab after [WRONG_OUTER]\"\"\" must still be sanitized and reported as E001");
    assertTrue(misRes.diagnostics().stream().anyMatch(d ->
        DiagnosticBag.ERR_TYPE_MISMATCH.equals(d.symbolicId().orElse(null))),
        "Sibling slot 1 after [WRONG_OUTER]\"\"\" must still validate in Pass 3");
  }

  // ===========================================================================
  // Target CA-03 — Stage 5 :TimeEpoch & :DateTime Schema Bound Mode Parity
  // ===========================================================================

  @Test
  @DisplayName("CA-03a: Stage 5 :ZonedDispatchTs rejects offset-bearing bounds in :defs with E019, poisons :ZonedDispatchTs, and suspends :body slots")
  void testZonedDispatchTsPoisonsInDefsWithE019AndSuspendsBody() {
    String source = """
        {
          :defs {
            :MissionEpochSec32 { #size 32 #s #minIncl 1704067200 #maxExcl 2147483647 } :TimeEpoch
            :OffsetWindow { #ms #offset #minIncl "2026-01-01T00:00:00.000Z" #maxExcl "2027-01-01T00:00:00.000Z" } :DateTime
            :ZonedDispatchTs {
              #zoned
              #minIncl "2026-01-01T00:00:00-06:00[America/Chicago]"
              #maxExcl "2027-01-01T00:00:00-06:00[America/Chicago]"
            } :DateTime
          }
          :type :Tuple( :MissionEpochSec32 :OffsetWindow :ZonedDispatchTs :ZonedDispatchTs )
          :body (
            1763596800
            "2026-07-04T12:00:00Z"
            "2026-07-04T12:00:00-05:00[America/Chicago]"
            "2026-07-04T12:00:00-05:00"
          )
        }
        """;

    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors());
    assertTrue(result.document().isPresent());

    var catalog = result.document().get().defs();
    assertInstanceOf(StvnSchema.PoisonedSchema.class, catalog.findType(":MissionEpochSec32").orElseThrow());
    assertInstanceOf(StvnSchema.PoisonedSchema.class, catalog.findType(":OffsetWindow").orElseThrow());
    assertInstanceOf(StvnSchema.PoisonedSchema.class, catalog.findType(":ZonedDispatchTs").orElseThrow());

    long zonedBoundE019Count = result.diagnostics().stream()
        .filter(d -> Optional.of("E019").equals(d.canonicalCode())
            && Optional.of(DiagnosticBag.ERR_TEMPORAL_OFFSET_IN_ZONED).equals(d.symbolicId())
            && d.message().contains(":ZonedDispatchTs"))
        .count();
    assertEquals(2L, zonedBoundE019Count, () -> "Both #minIncl and #maxExcl on :ZonedDispatchTs must emit E019: " + result.diagnostics());

    int bodyStart = source.indexOf(":body");
    assertFalse(result.diagnostics().stream().anyMatch(d -> d.startOffset() >= bodyStart),
        () -> "Slots governed by PoisonedSchema :ZonedDispatchTs must suspend :body errors, got: " + result.diagnostics());
  }

  @Test
  @DisplayName("CA-03b: :DateTime schema bounds enforce #offset, #zoned, #audited, #CONST mode compatibility, and fenced-string prohibition")
  void testDateTimeSchemaBoundsTripartiteAndFencedEnforcement() {
    String source = """
        {
          :defs {
            #OFFSET_CONST { #offset } :DateTime "2026-01-01T00:00:00Z"
            :BadZonedFromConst { #zoned #minIncl #OFFSET_CONST } :DateTime
            :BadOffsetWithZone { #offset #minIncl "2026-01-01T00:00:00Z[UTC]" } :DateTime
            :BadOffsetMissingOffset { #offset #minIncl "2026-01-01T00:00:00" } :DateTime
            :BadAuditedMissingZone { #audited #maxExcl "2027-01-01T00:00:00Z" } :DateTime
            :BadAuditedMissingOffset { #audited #minIncl "2026-01-01T00:00:00[America/Chicago]" } :DateTime
            :BadFencedBound {
              #offset
              #minIncl \"\"\"[ISO]
                2026-01-01T00:00:00Z
              [ISO]\"\"\"
            } :DateTime
          }
          :type :Int
          :body 1
        }
        """;

    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors());
    assertEquals(6, result.diagnostics().size(), () -> "Expected 6 tripartite schema-bound diagnostics, got: " + result.diagnostics());

    assertTrue(result.diagnostics().stream().anyMatch(d ->
        Optional.of(DiagnosticBag.ERR_TEMPORAL_OFFSET_IN_ZONED).equals(d.symbolicId()) && d.message().contains(":BadZonedFromConst")));
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        Optional.of(DiagnosticBag.ERR_TEMPORAL_ZONE_IN_OFFSET).equals(d.symbolicId()) && d.message().contains(":BadOffsetWithZone")));
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        Optional.of(DiagnosticBag.ERR_DATETIME_MISSING_OFFSET).equals(d.symbolicId()) && d.message().contains(":BadOffsetMissingOffset")));
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        Optional.of(DiagnosticBag.ERR_DATETIME_MISSING_ZONE).equals(d.symbolicId()) && d.message().contains(":BadAuditedMissingZone")));
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        Optional.of(DiagnosticBag.ERR_DATETIME_MISSING_OFFSET).equals(d.symbolicId()) && d.message().contains(":BadAuditedMissingOffset")));
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        Optional.of(DiagnosticBag.ERR_DATETIME_FENCED_STRING_PROHIBITED).equals(d.symbolicId()) && d.message().contains(":BadFencedBound")));
  }

  private static void assertExactSlice(String source, StvnDiagnostic diag, String expectedSlice) {
    String actualSlice = source.substring(diag.startOffset(), diag.endOffset());
    assertEquals(expectedSlice, actualSlice, () -> "Unexpected pinned slice for diagnostic: " + diag);
  }
}
