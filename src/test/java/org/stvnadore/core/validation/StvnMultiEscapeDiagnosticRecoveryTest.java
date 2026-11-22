package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.lowering.StvnLiteralParser;
import org.stvnadore.core.lowering.StvnPayloadLowerer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression suite verifying non-aborting multi-escape {@code E002} recovery, delimiter-bounded
 * four-hex-digit Unicode escape span pinning, cross-pass cascade suppression across all five
 * {@code LITERAL_STRING_SIMPLE} loci, and nested tagged sum {@code probeBranch} diagnostic preservation.
 *
 * @since 2.0.0
 */
@NullMarked
class StvnMultiEscapeDiagnosticRecoveryTest {

  @Test
  @DisplayName("TC-ESC-01: Gauntlet v9 reproducer emits both E002 diagnostics, lowers to StvnErrorValue, and emits 0 secondary cascades")
  void testGauntletV9MultiEscapeReproducer() {
    String literal = "\"CORRUPT_ESCAPE_\\z_AND_HEX_\\u12G4\"";
    String source = "{\n"
        + "  :defs {\n"
        + "    :StrictCode { #minSize 40 #maxSize 128 #regex \"^[A-Z_]+$\" } :String\n"
        + "  }\n"
        + "  :type :StrictCode\n"
        + "  :body " + literal + "\n"
        + "}\n";

    int strStart = source.indexOf(literal);
    var res = StvnCompiler.compileToResult(source, "repro.stvn", StvnParserConfig.DEFAULT);
    assertTrue(res.hasErrors());
    List<StvnDiagnostic> diags = res.diagnostics();
    assertEquals(2, diags.size(), () -> "Expected exactly 2 E002 diagnostics, got: " + diags);

    StvnDiagnostic d0 = diags.get(0);
    assertEquals("E002", d0.canonicalCode().orElse(""));
    assertEquals("STVN_SYNTAX_ERROR", d0.symbolicId().orElse(""));
    assertEquals(strStart + 16, d0.startOffset());
    assertEquals(strStart + 18, d0.endOffset());
    assertEquals("Illegal escape sequence '\\z' in simple string literal", d0.message());

    StvnDiagnostic d1 = diags.get(1);
    assertEquals("E002", d1.canonicalCode().orElse(""));
    assertEquals("STVN_SYNTAX_ERROR", d1.symbolicId().orElse(""));
    assertEquals(strStart + 27, d1.startOffset());
    assertEquals(strStart + 33, d1.endOffset());
    assertEquals("Invalid hexadecimal digits in Unicode escape '\\u12G4'", d1.message());

    StvnDocument doc = res.document().orElseThrow();
    StvnValue payload = doc.payload().orElseThrow();
    assertInstanceOf(StvnValue.StvnErrorValue.class, payload);
  }

  @Test
  @DisplayName("TC-ESC-02: Unicode escape delimiter boundary matrix and backslash-tab E001+E002 interaction")
  void testUnicodeBoundaryMatrixAndBackslashTab() {
    // 1. Short Unicode escape followed by second escape -> [1, 5) + [5, 7)
    var out1 = StvnPayloadLowerer.scanSimpleQuotedString(
        "\"\\u12\\z\"",
        new StvnPayloadLowerer.Span(100, 108, 1, 0)
    );
    assertTrue(out1.hasErrors());
    assertEquals(2, out1.errors().size());
    assertEquals(101, out1.errors().get(0).span().start());
    assertEquals(105, out1.errors().get(0).span().end());
    assertEquals("Incomplete Unicode escape sequence in string literal; expected '\\uXXXX'", out1.errors().get(0).message());
    assertEquals(105, out1.errors().get(1).span().start());
    assertEquals(107, out1.errors().get(1).span().end());
    assertEquals("Illegal escape sequence '\\z' in simple string literal", out1.errors().get(1).message());

    // 2. Short Unicode escape at end of literal -> [5, 9) stopping strictly before closing '"'
    var out2 = StvnPayloadLowerer.scanSimpleQuotedString(
        "\"END_\\u12\"",
        new StvnPayloadLowerer.Span(0, 10, 1, 0)
    );
    assertEquals(1, out2.errors().size());
    assertEquals(5, out2.errors().get(0).span().start());
    assertEquals(9, out2.errors().get(0).span().end());

    // 3. Bare 2-char Unicode prefix at end of literal and before next backslash
    var out3 = StvnPayloadLowerer.scanSimpleQuotedString(
        "\"\\u\"",
        new StvnPayloadLowerer.Span(0, 4, 1, 0)
    );
    assertEquals(1, out3.errors().size());
    assertEquals(1, out3.errors().get(0).span().start());
    assertEquals(3, out3.errors().get(0).span().end());

    var out4 = StvnPayloadLowerer.scanSimpleQuotedString(
        "\"\\u\\z\"",
        new StvnPayloadLowerer.Span(0, 6, 1, 0)
    );
    assertEquals(2, out4.errors().size());
    assertEquals(1, out4.errors().get(0).span().start());
    assertEquals(3, out4.errors().get(0).span().end());
    assertEquals(3, out4.errors().get(1).span().start());
    assertEquals(5, out4.errors().get(1).span().end());

    // 4. Non-hex digit at first post-prefix slot -> [1, 7) 6-char invalid hex digits
    var out5 = StvnPayloadLowerer.scanSimpleQuotedString(
        "\"\\uG123\"",
        new StvnPayloadLowerer.Span(0, 8, 1, 0)
    );
    assertEquals(1, out5.errors().size());
    assertEquals(1, out5.errors().get(0).span().start());
    assertEquals(7, out5.errors().get(0).span().end());
    assertEquals("Invalid hexadecimal digits in Unicode escape '\\uG123'", out5.errors().get(0).message());

    // 5. Short Unicode escape before escaped quote -> preserves escaped quote and still catches trailing escape
    var out6 = StvnPayloadLowerer.scanSimpleQuotedString(
        "\"QUOTE_\\u12\\\"_NEXT_\\q\"",
        new StvnPayloadLowerer.Span(0, 22, 1, 0)
    );
    assertEquals(2, out6.errors().size());
    assertEquals(7, out6.errors().get(0).span().start());
    assertEquals(11, out6.errors().get(0).span().end());
    assertEquals(19, out6.errors().get(1).span().start());
    assertEquals(21, out6.errors().get(1).span().end());

    // 6. Valid 9 escapes decode cleanly
    var valid = StvnPayloadLowerer.scanSimpleQuotedString(
        "\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u005A\"",
        new StvnPayloadLowerer.Span(0, 24, 1, 0)
    );
    assertFalse(valid.hasErrors());
    assertEquals("\"\\/\b\f\n\r\tZ", valid.decoded());

    // 7. Raw tab after backslash inside simple string: "a\<TAB>b" -> E001 (tab) + E002 ('\ ')
    String tabDoc = "{\n  :type :String\n  :body \"a\\\tb\"\n}\n";
    var tabRes = StvnCompiler.compileToResult(tabDoc, "tab_escape.stvn", StvnParserConfig.DEFAULT);
    assertTrue(tabRes.hasErrors());
    assertTrue(
        tabRes.diagnostics().stream().anyMatch(d -> "E001".equals(d.canonicalCode().orElse(""))),
        () -> "Expected E001 tab diagnostic on raw tab after backslash: " + tabRes.diagnostics()
    );
    assertTrue(
        tabRes.diagnostics().stream().anyMatch(d -> "E002".equals(d.canonicalCode().orElse(""))
            && d.message().contains("Illegal escape sequence")),
        () -> "Expected E002 escape diagnostic after tab sanitization: " + tabRes.diagnostics()
    );
  }

  @Test
  @DisplayName("TC-ESC-03: Five-locus coverage across :meta, :include, :defs/:type facets, #CONST, and :body containers/DateTime")
  void testAllFiveLociAndCascadeSuppression() {
    // Locus 1 (:meta #name, #domain, #unknown) & Locus 3 (non-string, illegal-arg, unknown, prohibited facets)
    String metaAndFacets = "{\n"
        + "  :meta {\n"
        + "    #name \"bad_\\z_\\u12G4\"\n"
        + "    #domain \"stvn_bad_\\q\"\n"
        + "    #kind #BODY\n"
        + "    #author \"unk_\\m_\\u12\"\n"
        + "  }\n"
        + "  :defs {\n"
        + "    :BadSize { #size \"sz_\\a_\\u12\" } :Int\n"
        + "    :BadFlag { #unsigned \"fl_\\c_\\u99ZZ\" } :Int\n"
        + "    :BadUnk { #mystery \"uk_\\d_\\u12\" } :String\n"
        + "    :BadProhib { #regex \"pr_\\e_\\u12\" } :Int\n"
        + "  }\n"
        + "  :type :String\n"
        + "  :body \"ok\"\n"
        + "}\n";
    var res1 = StvnCompiler.compileToResult(metaAndFacets, "meta_facets.stvn", StvnParserConfig.DEFAULT);
    List<StvnDiagnostic> e002List = res1.diagnostics().stream()
        .filter(d -> "E002".equals(d.canonicalCode().orElse("")))
        .toList();
    // 2 (#name) + 1 (#domain) + 2 (#author) + 2 (:BadSize) + 2 (:BadFlag) + 2 (:BadUnk) + 2 (:BadProhib) = 13
    assertEquals(13, e002List.size(), () -> "Expected all 13 E002 escape errors, got: " + res1.diagnostics());
    assertTrue(
        res1.diagnostics().stream().noneMatch(d -> d.severity() == DiagnosticSeverity.WARNING),
        () -> "Expected 0 W001/W002/W003 warnings when :meta strings have E002 errors: " + res1.diagnostics()
    );
    assertTrue(
        res1.diagnostics().stream().noneMatch(d -> "E038".equals(d.canonicalCode().orElse(""))),
        () -> "Expected 0 E038 reserved-domain cascade on corrupt #domain: " + res1.diagnostics()
    );

    // Locus 2 (:include in hermetic-blocked .stvn mode still scans path & :use option escape errors)
    String hermeticInclude = "{\n"
        + "  :meta { #name \"herm\" #kind #BODY }\n"
        + "  :defs {\n"
        + "    :package :local/pkg { :T :Int }\n"
        + "    :include [ \"./mod_\\z_\\u12G4.stvn_di\" ]\n"
        + "    :use [ :local/pkg { #strip \"opt_\\q\" } ]\n"
        + "  }\n"
        + "  :type :String\n"
        + "  :body \"ok\"\n"
        + "}\n";
    var res2 = StvnCompiler.compileToResult(hermeticInclude, "herm.stvn", StvnParserConfig.DEFAULT);
    List<StvnDiagnostic> incE002 = res2.diagnostics().stream()
        .filter(d -> "E002".equals(d.canonicalCode().orElse("")))
        .toList();
    assertEquals(3, incE002.size(), () -> "Expected 3 E002 errors in hermetic-blocked :include and :use, got: " + res2.diagnostics());

    // Orphan :type metadata block with multi-escape string
    String orphanTypeMeta = "{\n"
        + "  :type { #regex \"orph_\\z_\\u12\" }\n"
        + "  :body \"ok\"\n"
        + "}\n";
    var resOrphan = StvnCompiler.compileToResult(orphanTypeMeta, "orphan.stvn", StvnParserConfig.DEFAULT);
    long orphanEscapeErrors = resOrphan.diagnostics().stream()
        .filter(d -> "STVN_SYNTAX_ERROR".equals(d.symbolicId().orElse("")))
        .count();
    assertEquals(2L, orphanEscapeErrors, () -> "Expected 2 E002 escape errors in orphan :type metadata: " + resOrphan.diagnostics());

    // Locus 5: Valid escaped :DateTime vs corrupt :Map keys/values
    String validEscapedDt = "{\n"
        + "  :type { #offset } :DateTime\n"
        + "  :body \"2026-03-15T08:00:00\\u005A\"\n"
        + "}\n";
    var dtValidRes = StvnCompiler.compileToResult(validEscapedDt, "valid_dt.stvn", StvnParserConfig.DEFAULT);
    assertFalse(dtValidRes.hasErrors(), () -> "Valid escaped DateTime must compile cleanly: " + dtValidRes.diagnostics());

    String mapCollisionSuppression = "{\n"
        + "  :type { #invertible } :Map(:String :String)\n"
        + "  :body {\n"
        + "    [ \"k_\\z\" \"v_\\q\" ]\n"
        + "    [ \"k_\\z\" \"v_\\q\" ]\n"
        + "  }\n"
        + "}\n";
    var mapRes = StvnCompiler.compileToResult(mapCollisionSuppression, "map_esc.stvn", StvnParserConfig.DEFAULT);
    assertEquals(4, mapRes.diagnostics().size(), () -> "Expected strictly 4 E002 errors with 0 E017/E018 cascades: " + mapRes.diagnostics());
    assertTrue(mapRes.diagnostics().stream().allMatch(d -> "E002".equals(d.canonicalCode().orElse(""))));
    StvnValue.StvnMap mapVal = assertInstanceOf(
        StvnValue.StvnMap.class,
        mapRes.document().orElseThrow().payload().orElseThrow()
    );
    assertEquals(2, mapVal.entries().size(), "Map cardinality M == N must be preserved");
  }

  @Test
  @DisplayName("Safeguard 1: Untagged outer :Either and :Union preserve multi-escape E002 diagnostics from nested tagged sums without E028 cascade or duplicates")
  void testNestedTaggedSumProbeBranchPreservesMultiEscapeDiagnostics() {
    String source = "{\n"
        + "  :defs {\n"
        + "    :StrictCode { #minSize 10 #maxSize 12 #regex \"^[A-Z_]+$\" } :String\n"
        + "  }\n"
        + "  :type :Tuple(\n"
        + "    :Either(:Int :Option(:StrictCode))\n"
        + "    :Either(:Int :Union(:Boolean :StrictCode))\n"
        + "    :Union(:Int :Option(:StrictCode))\n"
        + "    :Either(:Option(:String) :Option(:StrictCode))\n"
        + "    :Int\n"
        + "  )\n"
        + "  :body (\n"
        + "    #Some \"BAD_\\z_\\u12G4\"\n"
        + "    #2 \"BAD_\\q_\\u12\"\n"
        + "    #Some \"BAD_\\x_\\u00GG\"\n"
        + "    #Some \"DUP_\\k_\\u99ZZ\"\n"
        + "    42\n"
        + "  )\n"
        + "}\n";

    var res = StvnCompiler.compileToResult(source, "nested_tagged_sums.stvn", StvnParserConfig.DEFAULT);
    assertTrue(res.hasErrors());
    List<StvnDiagnostic> diags = res.diagnostics();
    assertEquals(8, diags.size(), () -> "Expected strictly 8 unique E002 diagnostics and 0 E028 cascades, got: " + diags);
    assertTrue(
        diags.stream().allMatch(d -> "E002".equals(d.canonicalCode().orElse(""))
            && "STVN_SYNTAX_ERROR".equals(d.symbolicId().orElse(""))),
        () -> "All 8 diagnostics must be E002 STVN_SYNTAX_ERROR: " + diags
    );

    StvnValue.StvnTuple tuple = assertInstanceOf(
        StvnValue.StvnTuple.class,
        res.document().orElseThrow().payload().orElseThrow()
    );
    assertEquals(5, tuple.elements().size());
    assertInstanceOf(StvnValue.StvnErrorValue.class, tuple.elements().get(0));
    assertInstanceOf(StvnValue.StvnErrorValue.class, tuple.elements().get(1));
    assertInstanceOf(StvnValue.StvnErrorValue.class, tuple.elements().get(2));
    assertInstanceOf(StvnValue.StvnErrorValue.class, tuple.elements().get(3));
    assertInstanceOf(StvnValue.StvnInt.class, tuple.elements().get(4));
  }

  @Test
  @DisplayName("TC-ESC-04: Suspended :body still reports lexical E002 in primary bodyEntry(0) while suppressing duplicate secondary :body")
  void testSuspendedBodyReportsPrimaryEscapeErrorsAndSuppressesDuplicateSecondaryBody() {
    String suspendedBody = "{\n"
        + "  :type :UndefinedRootSchema\n"
        + "  :body ( \"first_\\z_\\u12G4\" \"second_\\q\" )\n"
        + "}\n";
    var res1 = StvnCompiler.compileToResult(suspendedBody, "suspended.stvn", StvnParserConfig.DEFAULT);
    List<StvnDiagnostic> bodyEscapes = res1.diagnostics().stream()
        .filter(d -> "STVN_SYNTAX_ERROR".equals(d.symbolicId().orElse("")))
        .toList();
    assertEquals(3, bodyEscapes.size(), () -> "Suspended primary :body must report all 3 lexical E002 errors: " + res1.diagnostics());

    String duplicateBodies = "{\n"
        + "  :type :String\n"
        + "  :body \"primary_\\z\"\n"
        + "  :body \"secondary_\\q_\\u12G4\"\n"
        + "}\n";
    var res2 = StvnCompiler.compileToResult(duplicateBodies, "dup_body.stvn", StvnParserConfig.DEFAULT);
    List<StvnDiagnostic> dupBodyEscapes = res2.diagnostics().stream()
        .filter(d -> "STVN_SYNTAX_ERROR".equals(d.symbolicId().orElse("")))
        .toList();
    assertEquals(1, dupBodyEscapes.size(), () -> "Only primary bodyEntry(0) escape error should be emitted: " + res2.diagnostics());
    assertTrue(dupBodyEscapes.get(0).message().contains("\\z"));
  }

  @Test
  @DisplayName("TC-ESC-05: Strict-mode compilePayload and StvnLiteralParser.parseString/parseStringNew throw StvnMalformedLiteralException pinned to first escape")
  void testStrictModeExceptionCompatibility() {
    String source = "{\n"
        + "  :type :String\n"
        + "  :body \"PREFIX_\\z_AND_\\u12G4\"\n"
        + "}\n";
    int expectedStart = source.indexOf("\\z");
    StvnMalformedLiteralException ex1 = assertThrows(
        StvnMalformedLiteralException.class,
        () -> StvnCompiler.compilePayload(source)
    );
    assertEquals(expectedStart, ex1.startOffset());
    assertEquals(expectedStart + 2, ex1.endOffset());
    assertEquals("Illegal escape sequence '\\z' in simple string literal", ex1.getMessage());

    String rawLiteral = "\"HELLO_\\u12\\q\"";
    StvnMalformedLiteralException ex2 = assertThrows(
        StvnMalformedLiteralException.class,
        () -> StvnLiteralParser.parseString(rawLiteral, true)
    );
    assertEquals(7, ex2.startOffset());
    assertEquals(11, ex2.endOffset());
    assertEquals("Incomplete Unicode escape sequence in string literal; expected '\\uXXXX'", ex2.getMessage());

    StvnMalformedLiteralException ex3 = assertThrows(
        StvnMalformedLiteralException.class,
        () -> StvnLiteralParser.parseStringNew(rawLiteral, false)
    );
    assertEquals(7, ex3.startOffset());
    assertEquals(11, ex3.endOffset());
  }
}
