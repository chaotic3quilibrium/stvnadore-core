package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.lowering.StvnDefsLowerer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression and fault-isolation suite verifying {@code ESC-CORE-01} ({@link StvnDiagnostic#sourcePath()}
 * provenance across multi-hop filesystem and virtual-workspace {@code :include} graphs) and
 * {@code ESC-CORE-02} (Pass 1 lexical/syntax truncation anti-cascade guard on {@code E031}).
 */
class StvnIncludeProvenanceAndCascadeGuardTest {

  @Test
  @DisplayName("TC-PROV-01: Anonymous in-memory compilation populates Optional.empty() sourcePath while preserving 6/7/8/9/10-arg constructor compatibility")
  void testAnonymousSourcePathAndConstructorOverloadCompatibility() {
    var result = StvnCompiler.compileToResult("{\n  :type :Int\n  :body \"bad\"\n}");
    assertTrue(result.hasErrors());
    assertEquals(1, result.diagnostics().size());
    assertEquals(Optional.empty(), result.diagnostics().getFirst().sourcePath());

    StvnDiagnostic d6 = new StvnDiagnostic("msg", 1, 0, 0, 1, null);
    StvnDiagnostic d7 = new StvnDiagnostic("msg", StvnDiagnostic.DiagnosticSeverity.WARNING, 1, 0, 0, 1, null);
    StvnDiagnostic d8 = new StvnDiagnostic("msg", StvnDiagnostic.DiagnosticSeverity.ERROR, 1, 0, 0, 1, null, Optional.of("E002"));
    StvnDiagnostic d9 = new StvnDiagnostic("msg", StvnDiagnostic.DiagnosticSeverity.ERROR, 1, 0, 0, 1, null, Optional.of("E002"), Optional.of("STVN_SYNTAX_ERROR"));
    StvnDiagnostic d10 = new StvnDiagnostic("msg", StvnDiagnostic.DiagnosticSeverity.ERROR, 1, 0, 0, 1, null, Optional.of("STVN_SYNTAX_ERROR"), Optional.of("E002"), Optional.of("STVN_SYNTAX_ERROR"));
    assertEquals(Optional.empty(), d6.sourcePath());
    assertEquals(Optional.empty(), d7.sourcePath());
    assertEquals(Optional.empty(), d8.sourcePath());
    assertEquals(Optional.empty(), d9.sourcePath());
    assertEquals(Optional.empty(), d10.sourcePath());

    StvnDiagnostic attributed = d10.withSourcePath("C:\\workspace\\sub\\..\\mod\\child.stvn_di");
    assertEquals(Optional.of("C:/workspace/mod/child.stvn_di"), attributed.sourcePath());
  }

  @Test
  @DisplayName("TC-PROV-02: Multi-hop A -> B -> C filesystem :include attributes grandchild, child, parent-directive, and Pass 3 body diagnostics to exact originating sourcePath")
  void testMultiHopIncludeProvenanceAndSiblingIsolation(@TempDir Path tempDir) throws IOException {
    Path grandchild = tempDir.resolve("grandchild.stvn_di");
    Files.writeString(grandchild, """
        {
          :meta {
            #name "grandchild"
            #kind #DEFS_INCLUDE
          }
          :defs {
            :GrandOk { #unsigned #size 16 } :Int
            :GrandBad { #minIncl 1 #unsigned #size 16 } :Int
          }
        }
        """);

    Path child = tempDir.resolve("child.stvn_di");
    Files.writeString(child, """
        {
          :meta {
            #name "child"
            #kind #DEFS_INCLUDE
          }
          :defs {
            :include [ "grandchild.stvn_di" ]
            :ChildOk :GrandOk
          }
        }
        """);

    Path parent = tempDir.resolve("parent.stvn_i");
    String parentSrc = """
        {
          :meta {
            #name "parent"
            #kind #BODY_INCLUDE
          }
          :defs {
            :include [ "child.stvn_di" ]
            :include [ "missing_module.stvn_di" ]
          }
          :type :ChildOk
          :body 70000
        }
        """;
    Files.writeString(parent, parentSrc);

    var res = StvnCompiler.compileToResult(parentSrc, parent.toString(), StvnParserConfig.DEFAULT);
    List<StvnDiagnostic> diags = res.diagnostics();
    assertEquals(3, diags.size(), () -> "Expected grandchild E008, parent missing-include E005, and parent overflow E011: " + diags);

    String normGrandchild = StvnSchemaFlattener.normalizePath(grandchild.toString());
    String normParent = StvnSchemaFlattener.normalizePath(parent.toString());

    StvnDiagnostic grandDiag = diags.get(0);
    assertEquals(Optional.of("E008"), grandDiag.canonicalCode());
    assertEquals(Optional.of(normGrandchild), grandDiag.sourcePath());

    StvnDiagnostic missingIncDiag = diags.get(1);
    assertEquals(Optional.of("E005"), missingIncDiag.canonicalCode());
    assertEquals(Optional.of(normParent), missingIncDiag.sourcePath());

    StvnDiagnostic bodyOverflowDiag = diags.get(2);
    assertEquals(Optional.of(DiagnosticBag.ERR_INTEGER_OVERFLOW), bodyOverflowDiag.symbolicId());
    assertEquals(Optional.of(normParent), bodyOverflowDiag.sourcePath());

    StvnValue payload = res.document().flatMap(StvnDocument::payload).orElseThrow();
    assertInstanceOf(StvnValue.StvnErrorValue.class, payload);
    assertEquals(Optional.of(normParent), ((StvnValue.StvnErrorValue) payload).diagnostic().sourcePath());
  }

  @Test
  @DisplayName("TC-PROV-03: Virtual workspace lowering stamps normalized virtual child sourcePath without filesystem I/O")
  void testVirtualWorkspaceProvenanceAttribution() {
    Map<String, String> workspace = new LinkedHashMap<>();
    String rootPath = "virtual/root/entry.stvn_di";
    String leafPath = "virtual/shared/leaf.stvn_di";
    workspace.put(rootPath, """
        {
          :meta {
            #name "entry"
            #kind #DEFS_INCLUDE
          }
          :defs {
            :include [ "../shared/leaf.stvn_di" ]
            :RootAlias :ValidLeaf
          }
        }
        """);
    workspace.put(leafPath, """
        {
          :meta {
            #name "leaf"
            #kind #DEFS_INCLUDE
          }
          :defs {
            :ValidLeaf :String
            :BrokenLeaf { #exact } :Int
          }
        }
        """);

    var docCtx = StvnCompiler.parse(workspace.get(rootPath), StvnParserConfig.DEFAULT);
    DiagnosticBag bag = new DiagnosticBag(32, rootPath);
    StvnDefsLowerer.lowerForFlattener(docCtx, rootPath, workspace, bag);

    assertEquals(1, bag.size());
    StvnDiagnostic diag = bag.toList().getFirst();
    assertEquals(Optional.of(leafPath), diag.sourcePath());
    assertEquals(Optional.of(DiagnosticBag.ERR_INVALID_METADATA_FACET), diag.symbolicId());
  }

  @Test
  @DisplayName("TC-CASC-01: Pass 1 tab and lexer-only syntax errors suppress missing-section E031 on .stvn and .stvn_d while preserving explicit prohibited-section E031")
  void testPass1SyntaxTruncationSuppressesOnlyMissingSectionCascade() {
    // 1. Tab after :type in .stvn -> strictly 1 E001, zero E031
    var tabBodyRes = StvnCompiler.compileToResult(
        "{\n  :type :String\t\n  :body \"sample\"\n}",
        "workspace/sample.stvn",
        StvnParserConfig.DEFAULT
    );
    assertEquals(1, tabBodyRes.diagnostics().size(), () -> "Unexpected cascade: " + tabBodyRes.diagnostics());
    assertEquals(Optional.of("E001"), tabBodyRes.diagnostics().getFirst().canonicalCode());
    assertEquals(Optional.of("workspace/sample.stvn"), tabBodyRes.diagnostics().getFirst().sourcePath());

    // 2. Lexer-only illegal character '@' inside braces in .stvn -> strictly 1 E002, zero E031
    var lexerOnlyRes = StvnCompiler.compileToResult(
        "{\n  @\n}",
        "workspace/lexer_only.stvn",
        StvnParserConfig.DEFAULT
    );
    assertEquals(1, lexerOnlyRes.diagnostics().size(), () -> "Unexpected cascade: " + lexerOnlyRes.diagnostics());
    assertEquals(Optional.of("E002"), lexerOnlyRes.diagnostics().getFirst().canonicalCode());

    // 3. Tab after :meta in .stvn_d -> strictly 1 E001, zero missing-:defs E031
    var defsTruncRes = StvnCompiler.compileToResult(
        "{\n  :meta {\n    #name \"schema_a\"\n    #kind #DEFS\n  }\t\n  :defs {\n    :Id :Int\n  }\n}",
        "workspace/schema_a.stvn_d",
        StvnParserConfig.DEFAULT
    );
    assertEquals(1, defsTruncRes.diagnostics().size(), () -> "Unexpected cascade: " + defsTruncRes.diagnostics());
    assertEquals(Optional.of("E001"), defsTruncRes.diagnostics().getFirst().canonicalCode());

    // 4. Explicitly authored prohibited :type inside .stvn_d WITH trailing tab -> emits BOTH E001 and prohibited-section E031
    var explicitProhibitedRes = StvnCompiler.compileToResult(
        "{\n  :meta {\n    #name \"schema_b\"\n    #kind #DEFS\n  }\n  :defs {\n    :Id :Int\n  }\n  :type :Id\t\n}",
        "workspace/schema_b.stvn_d",
        StvnParserConfig.DEFAULT
    );
    assertTrue(
        explicitProhibitedRes.diagnostics().stream().anyMatch(d -> Optional.of("E001").equals(d.canonicalCode())),
        "Must retain Pass 1 E001 diagnostic"
    );
    assertTrue(
        explicitProhibitedRes.diagnostics().stream().anyMatch(d ->
            Optional.of("E031").equals(d.canonicalCode()) && d.message().contains("cannot declare ':type' section")),
        "Must retain E031 on explicitly present prohibited ':type' section in #DEFS"
    );

    // 5. Syntactically clean empty braces '{}' in .stvn -> still emits genuine missing-section E031
    var cleanEmptyRes = StvnCompiler.compileToResult("{}", "workspace/clean_empty.stvn", StvnParserConfig.DEFAULT);
    assertEquals(1, cleanEmptyRes.diagnostics().size());
    assertEquals(Optional.of("E031"), cleanEmptyRes.diagnostics().getFirst().canonicalCode());
  }
}
