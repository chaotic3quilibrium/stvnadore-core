package org.stvnadore.core.validation;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.lowering.StvnPayloadLowerer;
import org.stvnadore.core.parser.StvnLexer;
import org.stvnadore.core.parser.StvnParser;
import org.stvnadore.core.utils.StvnProjectPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

/**
 * Conformance regression suite for Gauntlet V11 Batch 2:
 * <ul>
 *   <li>Full empty delimiter-pair coordinate pinning ({@code [openDelim.startOffset, closeDelim.endOffset)})
 *       when {@code M == 0} across {@code :Seq} ({@code []}), {@code :Set} ({@code []}), standard and
 *       {@code #invertible} {@code :Map} ({@code {}}), and {@code :Tuple} ({@code ()}), including
 *       interior whitespace, newlines, and comments.</li>
 *   <li>Strict closing-delimiter coordinate pinning ({@code [closeDelim.startOffset, closeDelim.endOffset)})
 *       when {@code 1 <= M < N} so valid parsed interior elements carry zero error diagnostics.</li>
 *   <li>Untagged and tagged {@code :Option}, {@code :Either}, and {@code :Union} container underflow
 *       pinning (including nested implied sum wrappers) and Pass 2 {@code #CONST} anti-cascade poisoning.</li>
 *   <li>Graceful coordinate fallback when closing delimiters are missing or conjured by ANTLR at {@code <EOF>}.</li>
 * </ul>
 */
@NullMarked
class StvnGauntletV11Batch2EmptyCollectionPairSpanTest {

  @Test
  @DisplayName("Vector 1: :PilotToneHistory empty '[]', '[   ]', and multi-line '[\\n]' pin across full bracket pair while partial '[ 192.10 ]' pins strictly to closing ']'")
  void testStage6PilotToneHistoryEmptyVsPartialSeqUnderflow() throws IOException {
    var compactEmpty = """
        {
          :defs {
            :FiberCoreId { #minIncl 0.0 } :Float
            :PilotToneHistory { #minSize 1 #maxSize 8 } :Seq( :FiberCoreId )
          }
          :type :PilotToneHistory
          :body []
        }
        """.replace("\r\n", "\n");
    var compactRes = StvnCompiler.compile(compactEmpty, "pilot_compact.stvn", StvnParserConfig.DEFAULT);
    Assertions.assertEquals(1, compactRes.diagnostics().size(), () -> "Expected strictly 1 E017 diagnostic: " + compactRes.diagnostics());
    assertExactDiagnostic(
        compactEmpty,
        compactRes.diagnostics().getFirst(),
        "[]",
        "E017",
        DiagnosticBag.ERR_COLLECTION_EMPTY_VIOLATION,
        7,
        8
    );

    var spacedEmpty = """
        {
          :defs {
            :FiberCoreId { #minIncl 0.0 } :Float
            :PilotToneHistory { #minSize 1 #maxSize 8 } :Seq( :FiberCoreId )
          }
          :type :PilotToneHistory
          :body [   ]
        }
        """.replace("\r\n", "\n");
    var spacedRes = StvnCompiler.compile(spacedEmpty, "pilot_spaced.stvn", StvnParserConfig.DEFAULT);
    Assertions.assertEquals(1, spacedRes.diagnostics().size());
    assertExactDiagnostic(
        spacedEmpty,
        spacedRes.diagnostics().getFirst(),
        "[   ]",
        "E017",
        DiagnosticBag.ERR_COLLECTION_EMPTY_VIOLATION,
        7,
        8
    );

    var multiLineEmpty = """
        {
          :defs {
            :FiberCoreId { #minIncl 0.0 } :Float
            :PilotToneHistory { #minSize 2 #maxSize 8 } :Seq( :FiberCoreId )
          }
          :type :PilotToneHistory
          :body [
            // empty interior comment
          ]
        }
        """.replace("\r\n", "\n");
    var multiRes = StvnCompiler.compile(multiLineEmpty, "pilot_multiline.stvn", StvnParserConfig.DEFAULT);
    Assertions.assertEquals(1, multiRes.diagnostics().size());
    assertExactDiagnostic(
        multiLineEmpty,
        multiRes.diagnostics().getFirst(),
        "[\n    // empty interior comment\n  ]",
        "E017",
        DiagnosticBag.ERR_COLLECTION_EMPTY_VIOLATION,
        7,
        8
    );

    var partialUnderflow = """
        {
          :defs {
            :FiberCoreId { #minIncl 0.0 } :Float
            :PilotTonePair { #minSize 2 #maxSize 8 } :Seq( :FiberCoreId )
          }
          :type :PilotTonePair
          :body [ 192.10 ]
        }
        """.replace("\r\n", "\n");
    var partialRes = StvnCompiler.compile(partialUnderflow, "pilot_partial.stvn", StvnParserConfig.DEFAULT);
    Assertions.assertEquals(1, partialRes.diagnostics().size(), () -> "Expected strictly 1 E012 diagnostic: " + partialRes.diagnostics());
    var partialDiag = partialRes.diagnostics().getFirst();
    assertExactDiagnostic(
        partialUnderflow,
        partialDiag,
        "]",
        "E012",
        DiagnosticBag.ERR_CONSTRAINT_VIOLATION,
        7,
        17
    );
    var elemEnd = partialUnderflow.indexOf("192.10") + "192.10".length();
    Assertions.assertTrue(
        partialDiag.startOffset() >= elemEnd,
        "Partial underflow span must remain strictly disjoint from valid interior element '192.10'"
    );

    var lfCheckedPaths = List.of(
        StvnProjectPaths.resolve("shared-fixtures", "syntax", "invalid", "composites", "seq_non_empty_empty_literal.contract.stvn_i"),
        StvnProjectPaths.resolve("shared-fixtures", "syntax", "invalid", "composites", "set_non_empty_empty_literal.contract.stvn_i"),
        StvnProjectPaths.resolve("shared-fixtures", "syntax", "invalid", "composites", "map_non_empty_empty_literal.contract.stvn_i"),
        StvnProjectPaths.resolve("shared-fixtures", "syntax", "invalid", "composites", "map_inv_non_empty_empty_literal.contract.stvn_i"),
        StvnProjectPaths.resolve("shared-fixtures", "syntax", "invalid", "containers", "empty_container_pair_underflow_all_constructors.stvn"),
        StvnProjectPaths.resolve("shared-fixtures", "syntax", "invalid", "containers", "empty_container_pair_underflow_all_constructors.contract.stvn_i")
    );
    for (var path : lfCheckedPaths) {
      var bytes = Files.readAllBytes(path);
      for (var b : bytes) {
        Assertions.assertNotEquals((byte) '\r', b, () -> "CR (0x0D) byte prohibited in shared fixture: " + path);
      }
    }
  }

  @Test
  @DisplayName("Vector 2: :Set, standard :Map, and #invertible :Map pin M == 0 across full [] / {} pairs and pin 1 <= M < minSize strictly to ] / }")
  void testSetStandardMapAndInvertibleMapEmptyPairVsPartialClosingDelimiter() {
    var emptyContainers = """
        {
          :defs {
            :ActiveSet { #minSize 1 #maxSize 8 } :Set( :String )
            :StdMap { #minSize 1 #maxSize 8 } :Map( :String :Int )
            :InvMap { #invertible #minSize 2 #maxSize 8 } :Map( :String :Int )
          }
          :type :Tuple( :ActiveSet :StdMap :InvMap )
          :body (
            [
            ]
            {}
            {
              // empty invertible map
            }
          )
        }
        """.replace("\r\n", "\n");
    var emptyRes = StvnCompiler.compile(emptyContainers, "set_and_maps_empty.stvn", StvnParserConfig.DEFAULT);
    var emptyDiags = emptyRes.diagnostics();
    Assertions.assertEquals(3, emptyDiags.size(), () -> "Expected 3 E017 diagnostics with 0 tuple cascades: " + emptyDiags);
    assertExactDiagnostic(emptyContainers, emptyDiags.get(0), "[\n    ]", "E017", DiagnosticBag.ERR_COLLECTION_EMPTY_VIOLATION, 9, 4);
    assertExactDiagnostic(emptyContainers, emptyDiags.get(1), "{}", "E017", DiagnosticBag.ERR_COLLECTION_EMPTY_VIOLATION, 11, 4);
    assertExactDiagnostic(
        emptyContainers,
        emptyDiags.get(2),
        "{\n      // empty invertible map\n    }",
        "E017",
        DiagnosticBag.ERR_COLLECTION_EMPTY_VIOLATION,
        12,
        4
    );

    var partialContainers = """
        {
          :defs {
            :PairSet { #minSize 2 #maxSize 8 } :Set( :String )
            :PairStdMap { #minSize 2 #maxSize 8 } :Map( :String :Int )
            :PairInvMap { #invertible #minSize 2 #maxSize 8 } :Map( :String :Int )
          }
          :type :Tuple( :PairSet :PairStdMap :PairInvMap )
          :body (
            [ "alpha" ]
            { [ "k1" 1 ] }
            { [ "k2" 2 ] }
          )
        }
        """.replace("\r\n", "\n");
    var partialRes = StvnCompiler.compile(partialContainers, "set_and_maps_partial.stvn", StvnParserConfig.DEFAULT);
    var partialDiags = partialRes.diagnostics();
    Assertions.assertEquals(3, partialDiags.size(), () -> "Expected 3 E012 closing-delimiter diagnostics: " + partialDiags);
    assertExactDiagnostic(partialContainers, partialDiags.get(0), "]", "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION, 9, 14);
    assertExactDiagnostic(partialContainers, partialDiags.get(1), "}", "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION, 10, 17);
    assertExactDiagnostic(partialContainers, partialDiags.get(2), "}", "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION, 11, 17);
  }

  @Test
  @DisplayName("Vector 3: :Tuple pins M == 0 ('()', '(  )', '(\\n)') across full [LPAREN, RPAREN), 1 <= M < N strictly to ')', and M > N across excess slice")
  void testTupleEmptyPairVsPartialClosingParenAndExcessSlice() {
    var source = """
        {
          :defs {
            :Pair :Tuple( :Int :String )
          }
          :type :Tuple( :Pair :Pair :Pair :Pair :Pair )
          :body (
            ()
            (   )
            (
            )
            ( 42 )
            ( 42 "ok" 99 100 )
          )
        }
        """.replace("\r\n", "\n");

    var result = StvnCompiler.compile(source, "tuple_spans.stvn", StvnParserConfig.DEFAULT);
    var diags = result.diagnostics();
    Assertions.assertEquals(5, diags.size(), () -> "Expected 5 isolated tuple arity diagnostics: " + diags);

    assertExactDiagnostic(source, diags.get(0), "()", "E028", DiagnosticBag.ERR_TUPLE_ARITY_MISMATCH, 7, 4);
    Assertions.assertTrue(diags.get(0).message().contains("Tuple arity mismatch: Expected 2 elements, got 0"));

    assertExactDiagnostic(source, diags.get(1), "(   )", "E028", DiagnosticBag.ERR_TUPLE_ARITY_MISMATCH, 8, 4);
    assertExactDiagnostic(source, diags.get(2), "(\n    )", "E028", DiagnosticBag.ERR_TUPLE_ARITY_MISMATCH, 9, 4);

    assertExactDiagnostic(source, diags.get(3), ")", "E028", DiagnosticBag.ERR_TUPLE_ARITY_MISMATCH, 11, 9);
    Assertions.assertTrue(diags.get(3).message().contains("Tuple arity mismatch: Expected 2 elements, got 1"));

    assertExactDiagnostic(source, diags.get(4), "99 100", "E028", DiagnosticBag.ERR_TUPLE_ARITY_MISMATCH, 12, 14);
    Assertions.assertTrue(diags.get(4).message().contains("Tuple arity mismatch: Expected 2 elements, got 4"));
  }

  @Test
  @DisplayName("Vector 4: Untagged/tagged :Option, :Either, :Union (including nested implied sums) and #CONST RHS empty containers pin across pair with zero :body cascade")
  void testSumBranchesAndConstRhsEmptyContainerPinningAndAntiCascade() {
    var sumSource = """
        {
          :defs {
            :PilotSeq { #minSize 1 #maxSize 8 } :Seq( :Float )
            :GainMap { #minSize 1 #maxSize 8 } :Map( :String :Int )
            :CoordPair :Tuple( :Int :Int )
          }
          :type :Tuple(
            :Option( :PilotSeq )
            :Option( :PilotSeq )
            :Either( :String :PilotSeq )
            :Either( :PilotSeq :String )
            :Either( :Int :GainMap )
            :Either( :String :Option( :PilotSeq ) )
            :Union( :String :PilotSeq )
            :Union( :String :GainMap :CoordPair )
            :Union( :String :GainMap :CoordPair )
            :Union( :Int :Option( :PilotSeq ) )
          )
          :body (
            []
            #Some []
            []
            #Left []
            #Right {}
            []
            []
            #2 {}
            #3 ()
            []
          )
        }
        """.replace("\r\n", "\n");

    var sumRes = StvnCompiler.compile(sumSource, "sum_empty_containers.stvn", StvnParserConfig.DEFAULT);
    var sumDiags = sumRes.diagnostics();
    Assertions.assertEquals(10, sumDiags.size(), () -> "Expected 10 empty-pair diagnostics across sum branches: " + sumDiags);

    var expectedSlices = new String[]{"[]", "[]", "[]", "[]", "{}", "[]", "[]", "{}", "()", "[]"};
    var expectedCanonicals = new String[]{
        "E017", "E017", "E017", "E017", "E017",
        "E017", "E017", "E017", "E028", "E017"
    };
    var expectedSymbolics = new String[]{
        DiagnosticBag.ERR_COLLECTION_EMPTY_VIOLATION,
        DiagnosticBag.ERR_COLLECTION_EMPTY_VIOLATION,
        DiagnosticBag.ERR_COLLECTION_EMPTY_VIOLATION,
        DiagnosticBag.ERR_COLLECTION_EMPTY_VIOLATION,
        DiagnosticBag.ERR_COLLECTION_EMPTY_VIOLATION,
        DiagnosticBag.ERR_COLLECTION_EMPTY_VIOLATION,
        DiagnosticBag.ERR_COLLECTION_EMPTY_VIOLATION,
        DiagnosticBag.ERR_COLLECTION_EMPTY_VIOLATION,
        DiagnosticBag.ERR_TUPLE_ARITY_MISMATCH,
        DiagnosticBag.ERR_COLLECTION_EMPTY_VIOLATION
    };
    for (int i = 0; i < expectedSlices.length; i++) {
      var diag = sumDiags.get(i);
      Assertions.assertEquals(Optional.of(expectedCanonicals[i]), diag.canonicalCode());
      Assertions.assertEquals(Optional.of(expectedSymbolics[i]), diag.symbolicId());
      Assertions.assertEquals(expectedSlices[i], sumSource.substring(diag.startOffset(), diag.endOffset()));
    }

    var ambiguousMultiListUnion = """
        {
          :defs {
            :PilotSeq { #minSize 1 #maxSize 8 } :Seq( :Float )
            :CoreSet { #minSize 1 #maxSize 8 } :Set( :Int )
          }
          :type :Union( :PilotSeq :CoreSet )
          :body []
        }
        """.replace("\r\n", "\n");
    var ambigRes = StvnCompiler.compile(ambiguousMultiListUnion, "ambig_union.stvn", StvnParserConfig.DEFAULT);
    Assertions.assertEquals(1, ambigRes.diagnostics().size());
    assertExactDiagnostic(
        ambiguousMultiListUnion,
        ambigRes.diagnostics().getFirst(),
        "[]",
        "E028",
        DiagnosticBag.ERR_TYPE_MISMATCH,
        7,
        8
    );

    var constSource = """
        {
          :defs {
            :RedundantBase { #minSize 0 } :Seq( :Int )
            :PilotSeq { #minSize 1 #maxSize 8 } :Seq( :Float )
            :GainMap { #minSize 1 #maxSize 8 } :Map( :String :Int )
            :CoordPair :Tuple( :Int :Int )
            #VALID_SEQ :PilotSeq [ 192.10 ]
            #EMPTY_SEQ :PilotSeq []
            #EMPTY_MAP :GainMap {}
            #EMPTY_TUPLE :CoordPair ()
          }
          :type :Tuple( :PilotSeq :PilotSeq :GainMap :CoordPair )
          :body ( #VALID_SEQ #EMPTY_SEQ #EMPTY_MAP #EMPTY_TUPLE )
        }
        """.replace("\r\n", "\n");
    var constRes = StvnCompiler.compile(constSource, "const_anti_cascade.stvn", StvnParserConfig.DEFAULT);
    var constDiags = constRes.diagnostics();
    Assertions.assertEquals(4, constDiags.size(), () -> "Expected 1 W003 warning + 3 #CONST RHS errors with 0 :body cascades: " + constDiags);

    assertExactDiagnostic(constSource, constDiags.get(0), "#minSize 0", "W003", DiagnosticBag.WARN_REDUNDANT_METADATA_FACET, 3, 21);
    assertExactDiagnostic(constSource, constDiags.get(1), "[]", "E017", DiagnosticBag.ERR_COLLECTION_EMPTY_VIOLATION, 8, 25);
    assertExactDiagnostic(constSource, constDiags.get(2), "{}", "E017", DiagnosticBag.ERR_COLLECTION_EMPTY_VIOLATION, 9, 24);
    assertExactDiagnostic(constSource, constDiags.get(3), "()", "E028", DiagnosticBag.ERR_TUPLE_ARITY_MISMATCH, 10, 28);
    Assertions.assertInstanceOf(MalformedSchemaException.class, constDiags.get(1).cause());
    Assertions.assertInstanceOf(MalformedSchemaException.class, constDiags.get(2).cause());
    Assertions.assertInstanceOf(MalformedSchemaException.class, constDiags.get(3).cause());
  }

  @Test
  @DisplayName("Vector 5: Unclosed and conjured <EOF> container delimiters fall back cleanly with valid startOffset < endOffset without rewinding to offset 0")
  void testUnclosedAndSyntheticEofContainerFallbackSafety() {
    var catalogDoc = """
        {
          :meta {
            #name "eof_guard_types"
            #kind #DEFS
          }
          :defs {
            :MinTwoSeq { #minSize 2 #maxSize 8 } :Seq( :Int )
            :MinTwoMap { #minSize 2 #maxSize 8 } :Map( :String :Int )
            :PairTuple :Tuple( :Int :Int )
          }
        }
        """.replace("\r\n", "\n");
    var catalog = StvnCompiler.compileDefsCatalog(catalogDoc, "eof_guard_types.stvn_d", StvnParserConfig.DEFAULT)
        .document()
        .orElseThrow();
    var seqSchema = catalog.findType(":MinTwoSeq").orElseThrow();
    var mapSchema = catalog.findType(":MinTwoMap").orElseThrow();
    var tupleSchema = catalog.findType(":PairTuple").orElseThrow();

    assertUnclosedFallback("      [", seqSchema, catalog, "[", "E017", DiagnosticBag.ERR_COLLECTION_EMPTY_VIOLATION, false);
    assertUnclosedFallback("      [", seqSchema, catalog, "[", "E017", DiagnosticBag.ERR_COLLECTION_EMPTY_VIOLATION, true);
    assertUnclosedFallback("      [ 10", seqSchema, catalog, "[ 10", "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION, false);

    assertUnclosedFallback("      {", mapSchema, catalog, "{", "E017", DiagnosticBag.ERR_COLLECTION_EMPTY_VIOLATION, false);
    assertUnclosedFallback("      {", mapSchema, catalog, "{", "E017", DiagnosticBag.ERR_COLLECTION_EMPTY_VIOLATION, true);
    assertUnclosedFallback("      { [ \"k\" 1 ]", mapSchema, catalog, "{ [ \"k\" 1 ]", "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION, false);

    assertUnclosedFallback("      (", tupleSchema, catalog, "(", "E028", DiagnosticBag.ERR_TUPLE_ARITY_MISMATCH, false);
    assertUnclosedFallback("      (", tupleSchema, catalog, "(", "E028", DiagnosticBag.ERR_TUPLE_ARITY_MISMATCH, true);
    assertUnclosedFallback("      ( 42", tupleSchema, catalog, "( 42", "E028", DiagnosticBag.ERR_TUPLE_ARITY_MISMATCH, false);
  }

  private static void assertUnclosedFallback(
      String rawValueFragment,
      org.stvnadore.core.ir.StvnSchema schema,
      org.stvnadore.core.ir.StvnDefsCatalog catalog,
      String expectedSlice,
      String expectedCanonical,
      String expectedSymbolic,
      boolean stripCloseChildToNull
  ) {
    var lexer = new StvnLexer(CharStreams.fromString(rawValueFragment));
    lexer.removeErrorListeners();
    var parser = new StvnParser(new CommonTokenStream(lexer));
    parser.removeErrorListeners();
    var valueCtx = parser.value();
    if (stripCloseChildToNull && valueCtx.collectionValue() != null) {
      var coll = valueCtx.collectionValue();
      var inner = coll.listLiteral() != null
          ? coll.listLiteral()
          : (coll.mapLiteral() != null ? coll.mapLiteral() : coll.tupleLiteral());
      if (inner != null && inner.children != null && inner.children.size() > 1) {
        inner.children.removeLast();
      }
    }

    var bag = new DiagnosticBag();
    StvnPayloadLowerer.lowerValue(valueCtx, schema, catalog, bag);
    Assertions.assertEquals(1, bag.size(), () -> "Expected 1 fallback diagnostic for '" + rawValueFragment + "': " + bag.toList());
    var diag = bag.toList().getFirst();
    Assertions.assertTrue(diag.startOffset() > 0, () -> "Unclosed span must not rewind to offset 0: " + diag);
    Assertions.assertTrue(diag.startOffset() < diag.endOffset(), () -> "Span must satisfy startOffset < endOffset: " + diag);
    Assertions.assertEquals(Optional.of(expectedCanonical), diag.canonicalCode());
    Assertions.assertEquals(Optional.of(expectedSymbolic), diag.symbolicId());
    Assertions.assertEquals(expectedSlice, rawValueFragment.substring(diag.startOffset(), diag.endOffset()));
  }

  private static void assertExactDiagnostic(
      String source,
      StvnDiagnostic diag,
      String expectedSlice,
      String expectedCanonicalCode,
      String expectedSymbolicId,
      int expectedLine,
      int expectedZeroBasedColumn
  ) {
    Assertions.assertEquals(Optional.of(expectedCanonicalCode), diag.canonicalCode(), () -> "Unexpected canonical code: " + diag);
    Assertions.assertEquals(Optional.of(expectedSymbolicId), diag.symbolicId(), () -> "Unexpected symbolic ID: " + diag);
    Assertions.assertEquals(expectedLine, diag.line(), () -> "Unexpected 1-based line: " + diag);
    Assertions.assertEquals(expectedZeroBasedColumn, diag.column(), () -> "Unexpected 0-based column: " + diag);
    var actualSlice = source.substring(diag.startOffset(), diag.endOffset());
    Assertions.assertEquals(expectedSlice, actualSlice, () -> "Unexpected pinned source slice for: " + diag);
  }
}
