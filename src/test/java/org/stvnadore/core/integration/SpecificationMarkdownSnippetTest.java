package org.stvnadore.core.integration;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity;
import org.stvnadore.core.StvnParserConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Automated doc-testing harness verifying that all STVN code examples in
 * {@code docs/architecture/STVN_LANGUAGE_SPEC.md} strictly adhere to compiler grammar
 * rules, diagnostic codes, and document kind invariants.
 *
 * @since 2.0.0
 */
@NullMarked
public class SpecificationMarkdownSnippetTest {

  private static final Path SPEC_PATH = Paths.get("docs", "architecture", "STVN_LANGUAGE_SPEC.md");
  private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```stvn\\s*\\n(.*?)```", Pattern.DOTALL);
  private static final Pattern FILENAME_HINT_PATTERN = Pattern.compile("//\\s*(?:[A-Za-z0-9_ ]+:\\s*)?([a-zA-Z0-9_.-]+\\.stvn[a-zA-Z0-9_.]*)");
  private static final Pattern NEGATIVE_MARKER_PATTERN = Pattern.compile("(?i)(//\\s*FATAL:|//\\s*INVALID:|TRAP\\s+\\d+|FATAL\\s+REJECTION)");

  record SnippetRecord(
      int index,
      int line,
      String rawText,
      String virtualFilename,
      boolean isNegative,
      boolean isRootEnclosed
  ) {}

  private static final List<SnippetRecord> snippets = new ArrayList<>();

  @TempDir
  static Path sharedTempDir;

  @BeforeAll
  static void loadAndStageSnippets() throws IOException {
    assertTrue(Files.exists(SPEC_PATH), "Specification markdown must exist at: " + SPEC_PATH.toAbsolutePath());
    String markdown = Files.readString(SPEC_PATH);
    Matcher matcher = CODE_BLOCK_PATTERN.matcher(markdown);

    // Stage mock modules for illustrative include snippets
    Path typesDir = sharedTempDir.resolve("types");
    Files.createDirectories(typesDir);
    Files.writeString(typesDir.resolve("network.types.stvn_di"),
        "{\n  :meta { #name \"network\" #domain \"types\" #kind #DEFS_INCLUDE }\n  :defs {\n    :org/example/Endpoint :String\n    #org/example/TIMEOUT { #unsigned #size 8 } :Int 30\n  }\n}");
    Files.writeString(sharedTempDir.resolve("shared_models.shared.stvn_di"),
        "{\n  :meta { #name \"shared_models\" #domain \"shared\" #kind #DEFS_INCLUDE }\n  :defs {\n    :HostName :String\n    :Port { #unsigned #size 16 } :Int\n  }\n}");
    Files.writeString(sharedTempDir.resolve("networking.net.stvn_di"),
        "{\n  :meta { #name \"networking\" #domain \"net\" #kind #DEFS_INCLUDE }\n  :defs {\n    :net/http/Status :Int\n  }\n}");

    int idx = 0;
    while (matcher.find()) {
      String raw = matcher.group(1);
      int startOffset = matcher.start();
      int line = (int) markdown.substring(0, startOffset).lines().count() + 1;

      String trimmed = raw.trim();
      boolean isRoot = trimmed.startsWith("{") && trimmed.endsWith("}");
      boolean isNeg = NEGATIVE_MARKER_PATTERN.matcher(trimmed).find();

      Matcher fnMatcher = FILENAME_HINT_PATTERN.matcher(trimmed);
      String virtualFilename;
      if (fnMatcher.find()) {
        virtualFilename = fnMatcher.group(1);
      } else if (trimmed.contains("#kind #DEFS_INCLUDE")) {
        virtualFilename = "snippet_" + idx + ".spec.stvn_di";
      } else if (trimmed.contains("#kind #DEFS")) {
        virtualFilename = "snippet_" + idx + ".spec.stvn_d";
      } else if (trimmed.contains("#kind #BODY_INCLUDE")) {
        virtualFilename = "snippet_" + idx + ".spec.stvn_i";
      } else if (trimmed.contains("#kind #BODY")) {
        virtualFilename = "snippet_" + idx + ".stvn";
      } else if (trimmed.startsWith(":defs") || (trimmed.contains(":defs") && !trimmed.contains(":body"))) {
        virtualFilename = "snippet_" + idx + ".spec.stvn_di";
      } else if (!isRoot && (trimmed.startsWith(":") || trimmed.startsWith("#") || trimmed.startsWith("//"))) {
        virtualFilename = "snippet_" + idx + ".spec.stvn_d";
      } else {
        virtualFilename = "snippet_" + idx + ".stvn";
      }

      var record = new SnippetRecord(idx, line, raw, virtualFilename, isNeg, isRoot);
      snippets.add(record);

      // Pre-stage schema definitions (.stvn_d, .stvn_di) in the temporary workspace for include resolution
      if (!isNeg && isRoot && (virtualFilename.endsWith(".stvn_d") || virtualFilename.endsWith(".stvn_di"))) {
        Files.writeString(sharedTempDir.resolve(virtualFilename), raw);
      }

      idx++;
    }

    assertFalse(snippets.isEmpty(), "Specification must contain at least one ```stvn code block");
  }

  @Test
  @DisplayName("TC-SPEC-01: All positive specification snippets compile cleanly under StvnParserConfig.STRICT")
  void testPositiveSpecificationSnippetsCompileCleanly() {
    List<String> failures = new ArrayList<>();

    for (SnippetRecord rec : snippets) {
      if (rec.isNegative()) {
        continue;
      }

      // Handle split documents or standalone instructional fragments
      String toCompile = rec.rawText().trim();
      String effectiveFilename = rec.virtualFilename();
      if (!rec.isRootEnclosed()) {
        if (toCompile.contains(":defs") && (toCompile.contains(":type") || toCompile.contains(":body"))) {
          toCompile = "{\n" + toCompile + "\n}";
          effectiveFilename = "snippet_" + rec.index() + ".stvn";
        } else if (toCompile.startsWith(":defs")) {
          toCompile = "{\n  :meta {\n    #name \"snippet_" + rec.index() + "\"\n    #domain \"spec\"\n    #kind #DEFS_INCLUDE\n  }\n" + toCompile + "\n}";
          effectiveFilename = "snippet_" + rec.index() + ".spec.stvn_di";
        } else if (toCompile.startsWith(":") || toCompile.startsWith("#") || toCompile.startsWith("//")) {
          toCompile = "{\n  :meta {\n    #name \"snippet_" + rec.index() + "\"\n    #domain \"spec\"\n    #kind #DEFS\n  }\n  :defs {\n    " + toCompile + "\n  }\n}";
          effectiveFilename = "snippet_" + rec.index() + ".spec.stvn_d";
        }
      }

      Path docPath = sharedTempDir.resolve(effectiveFilename);
      boolean isSystem = effectiveFilename.contains(".stvn_core.")
          || effectiveFilename.contains(".stvn_ir.")
          || effectiveFilename.contains(".stvn_cas.");
      var config = isSystem ? StvnParserConfig.SYSTEM_STRICT : StvnParserConfig.STRICT;

      var result = StvnCompiler.compileToResult(toCompile, docPath.toString(), config);
      boolean hasUnexpectedWarnings = result.diagnostics().stream()
          .anyMatch(d -> d.severity() == DiagnosticSeverity.WARNING
              && !d.errorCode().orElse("").equals("WARN_REDUNDANT_METADATA_FACET"));
      if (!result.isSuccess() || result.hasErrors() || hasUnexpectedWarnings) {
        failures.add(String.format("Snippet #%d at line %d (%s) failed compilation:%n%s",
            rec.index(), rec.line(), effectiveFilename, result.diagnostics()));
      }
    }

    assertTrue(failures.isEmpty(),
        "Detected compilation failures in specification positive examples:\n" + String.join("\n", failures));
  }

  @Test
  @DisplayName("TC-SPEC-02: All negative specification anti-patterns fail compilation under StvnParserConfig.STRICT")
  void testNegativeSpecificationSnippetsFailCompilation() {
    List<String> unexpectedSuccesses = new ArrayList<>();

    for (SnippetRecord rec : snippets) {
      if (!rec.isNegative()) {
        continue;
      }

      String toCompile = rec.rawText().trim();
      Path docPath = sharedTempDir.resolve(rec.virtualFilename());
      var result = StvnCompiler.compileToResult(toCompile, docPath.toString(), StvnParserConfig.STRICT);

      if (result.isSuccess() && !result.hasErrors()) {
        unexpectedSuccesses.add(String.format("Negative Snippet #%d at line %d (%s) succeeded unexpectedly",
            rec.index(), rec.line(), rec.virtualFilename()));
      }
    }

    assertTrue(unexpectedSuccesses.isEmpty(),
        "Detected negative specification anti-patterns that compiled successfully:\n" + String.join("\n", unexpectedSuccesses));
  }
}
