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
}
