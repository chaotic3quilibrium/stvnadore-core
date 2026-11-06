package org.stvnadore.core.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.printer.AstCompactPrinter;
import org.stvnadore.core.printer.AstPrettyPrinter;
import org.stvnadore.core.validation.DiagnosticBag;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance test harness validating the Zero-Tab Invariant and the Fenced String Tab Exemption.
 */
class StvnFencedStringTabConformanceTest {

  @Test
  @DisplayName("TC-TAB-01: Fenced string containing Makefile tab recipe compiles cleanly under STRICT mode")
  void testFencedStringMakefileRecipeCompiles() {
    String doc = """
        {
          :type :String
          :body \"\"\"[MAKEFILE]
        all:
        \tgcc main.c -o app
        \t./app
        [MAKEFILE]\"\"\"
        }
        """;
    var result = StvnCompiler.compilePayload(doc);
    assertTrue(result.isPresent(), "Makefile payload with tabs must compile cleanly");
    var ast = result.get();
    assertTrue(ast instanceof StvnValue.StvnString, "AST node must be StvnString");
    assertTrue(((StvnValue.StvnString) ast).value().contains("\tgcc"), "Value must retain verbatim tab");
  }

  @Test
  @DisplayName("TC-TAB-02: Fenced string containing TSV table compiles cleanly under STRICT mode")
  void testFencedStringTsvTableCompiles() {
    String doc = """
        {
          :type :String
          :body \"\"\"[TSV]
        id\tname\tscore
        1\tAlice\t100
        [TSV]\"\"\"
        }
        """;
    var result = StvnCompiler.compilePayload(doc);
    assertTrue(result.isPresent(), "TSV table payload with tabs must compile cleanly");
  }

  @Test
  @DisplayName("TC-TAB-03: #preserveIndent #TRUE preserves raw tabs without modification")
  void testPreserveIndentTrueRetainsTabs() {
    String doc = """
        {
          :defs {
            :Code { #preserveIndent #TRUE } :String
          }
          :type :Code
          :body \"\"\"[GO]
        package main
        \tfunc main() {}
        [GO]\"\"\"
        }
        """;
    var result = StvnCompiler.compilePayload(doc);
    assertTrue(result.isPresent());
    var str = (StvnValue.StvnString) result.get();
    assertTrue(str.value().startsWith("package main\n\tfunc main()"));
  }

  @Test
  @DisplayName("TC-TAB-04: #preserveIndent #FALSE trims common margin while preserving inner tabs")
  void testPreserveIndentFalseTrimsMarginWithTabs() {
    String doc = """
        {
          :defs {
            :Code { #preserveIndent #FALSE } :String
          }
          :type :Code
          :body \"\"\"[TSV]
          colA\tcolB
          valA\tvalB
        [TSV]\"\"\"
        }
        """;
    var result = StvnCompiler.compilePayload(doc);
    assertTrue(result.isPresent());
    var str = (StvnValue.StvnString) result.get();
    assertTrue(str.value().contains("colA\tcolB"));
  }

  @Test
  @DisplayName("TC-TAB-05: AstPrettyPrinter round-trips fenced strings with tabs deterministically")
  void testPrettyPrinterRoundTrip() {
    String doc = """
        {
          :type :String
          :body \"\"\"[RAW]
        a\tb
        [RAW]\"\"\"
        }
        """;
    var ast = StvnCompiler.compilePayload(doc).orElseThrow();
    String printed = AstPrettyPrinter.print(ast);
    assertTrue(printed.contains("a\tb"), "Printed output must retain verbatim tab");
    var roundTrip = StvnCompiler.compilePayload(printed).orElseThrow();
    assertEquals(ast, roundTrip, "Round-trip AST must be identical");
  }

  @Test
  @DisplayName("TC-TAB-06: Raw tab in structural indentation triggers ERR_TAB_CHARACTER_FORBIDDEN (E001)")
  void testStructuralTabFails() {
    String doc = "{\n\t:type :Int\n\t:body 42\n}";
    var ex = assertThrows(RuntimeException.class, () -> StvnCompiler.compilePayload(doc));
    assertTrue(ex.getMessage().contains("ERR_TAB_CHARACTER_FORBIDDEN"));
    assertTrue(ex.getMessage().contains("U+0009"));
  }

  @Test
  @DisplayName("TC-TAB-07: Raw tab inside comment triggers ERR_TAB_CHARACTER_FORBIDDEN (E001)")
  void testCommentTabFails() {
    String doc = "{\n  //\tForbidden tab in comment\n  :type :Int\n  :body 42\n}";
    var ex = assertThrows(RuntimeException.class, () -> StvnCompiler.compilePayload(doc));
    assertTrue(ex.getMessage().contains("ERR_TAB_CHARACTER_FORBIDDEN"));
  }

  @Test
  @DisplayName("TC-TAB-08: Raw unescaped tab inside simple string triggers ERR_TAB_CHARACTER_FORBIDDEN (E001)")
  void testSimpleStringRawTabFails() {
    String doc = "{\n  :type :String\n  :body \"raw\ttab\"\n}";
    var ex = assertThrows(RuntimeException.class, () -> StvnCompiler.compilePayload(doc));
    assertTrue(ex.getMessage().contains("ERR_TAB_CHARACTER_FORBIDDEN"));
  }

  @Test
  @DisplayName("TC-TAB-09: Raw tab on opening fence delimiter line is rejected")
  void testOpeningFenceDelimiterWithTabFails() {
    String doc = "{\n  :type :String\n  :body \"\"\"[TAG]\t\ncontent\n[TAG]\"\"\"\n}";
    var ex = assertThrows(RuntimeException.class, () -> StvnCompiler.compilePayload(doc));
    assertTrue(ex.getMessage().contains("Rule STR-04 violation") || ex.getMessage().contains("ERR_TAB_CHARACTER_FORBIDDEN"));
  }

  @Test
  @DisplayName("TC-TAB-10: Raw tab trailing closing fence delimiter is rejected")
  void testTrailingClosingFenceDelimiterWithTabFails() {
    String doc = "{\n  :type :String\n  :body \"\"\"[TAG]\ncontent\n[TAG]\"\"\"\t\n}";
    var ex = assertThrows(RuntimeException.class, () -> StvnCompiler.compilePayload(doc));
    assertTrue(ex.getMessage().contains("ERR_TAB_CHARACTER_FORBIDDEN"));
  }

  @Test
  @DisplayName("TC-TAB-11: Non-destructive E001 tab handling across char 0, // comment, :type, :body, and tuple preserves Pass 2/3 validation")
  void testNonDestructiveTabHandlingAcrossAllContexts() {
    String doc = "\t{\n"
        + "  :meta {\n"
        + "    #name \"tab_non_destructive_all_contexts\"\n"
        + "    #kind #BODY_INCLUDE\n"
        + "  }\n"
        + "  :defs {\n"
        + "    //\tTab inside comment must not split comment words into code tokens\n"
        + "    :Stage3Port { #unsigned #size 16 #minIncl 1 #maxExcl 1000 } :Int\n"
        + "    :Stage3Envelope :Tuple( :Stage3Port :Stage3Port )\n"
        + "  }\n"
        + "  :type\t:Stage3Envelope\n"
        + "  :body\t( 3\t1500 )\n"
        + "}\n";
    var res = StvnCompiler.compile(doc, "tab_non_destructive_all_contexts.stvn_i", StvnParserConfig.DEFAULT);
    assertTrue(res.hasErrors());
    var diags = res.diagnostics();
    assertEquals(6, diags.size(), "Expected 5 E001 tab diagnostics + 1 E012 range violation with zero bogus syntax errors: " + diags);
    for (int i = 0; i < 5; i++) {
      assertEquals("E001", diags.get(i).canonicalCode().orElse(""));
      assertEquals(DiagnosticBag.ERR_TAB_CHARACTER_FORBIDDEN, diags.get(i).symbolicId().orElse(""));
    }
    var rangeDiag = diags.get(5);
    assertEquals("E012", rangeDiag.canonicalCode().orElse(""));
    assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, rangeDiag.symbolicId().orElse(""));
    int idx1500 = doc.indexOf("1500");
    assertEquals(idx1500, rangeDiag.startOffset());
    assertEquals(idx1500 + 4, rangeDiag.endOffset());
  }

  @Test
  @DisplayName("TC-TAB-12: Raw tab inside simple string emits E001 without corrupting subsequent tuple elements")
  void testSimpleStringRawTabDoesNotCorruptSubsequentTupleElements() {
    String doc = "{\n"
        + "  :defs {\n"
        + "    :Port { #unsigned #size 16 #minIncl 1 #maxExcl 1000 } :Int\n"
        + "  }\n"
        + "  :type :Tuple( :String :Port )\n"
        + "  :body ( \"raw\ttab\" 9999 )\n"
        + "}\n";
    var res = StvnCompiler.compileToResult(doc);
    assertTrue(res.hasErrors());
    var diags = res.diagnostics();
    assertEquals(2, diags.size(), "Expected E001 on raw tab in string + E012 on 9999: " + diags);
    assertEquals("E001", diags.get(0).canonicalCode().orElse(""));
    int tabIdx = doc.indexOf('\t');
    assertEquals(tabIdx, diags.get(0).startOffset());
    assertEquals(tabIdx + 1, diags.get(0).endOffset());
    assertEquals("E012", diags.get(1).canonicalCode().orElse(""));
    int idx9999 = doc.indexOf("9999");
    assertEquals(idx9999, diags.get(1).startOffset());
    assertEquals(idx9999 + 4, diags.get(1).endOffset());
  }
}
