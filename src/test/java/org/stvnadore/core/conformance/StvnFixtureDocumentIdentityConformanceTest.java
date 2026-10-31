package org.stvnadore.core.conformance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.validation.DiagnosticBag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conformance test verifying that all canonical shared fixtures on disk compile cleanly
 * with their real filesystem names and produce zero diagnostic warnings or errors.
 */
public class StvnFixtureDocumentIdentityConformanceTest {

  private static final Path VALID_FIXTURES_DIR = Paths.get("shared-fixtures/syntax/valid");

  @Test
  @DisplayName("TC-CONF-01: Every valid fixture compiles with its real filesystem path with zero diagnostics")
  void testAllValidFixturesCompileWithZeroDiagnostics() throws IOException {
    assertTrue(Files.exists(VALID_FIXTURES_DIR), "Valid fixtures directory must exist");

    List<Path> fixtureFiles = new ArrayList<>();
    try (var stream = Files.walk(VALID_FIXTURES_DIR)) {
      stream.filter(Files::isRegularFile)
          .filter(p -> {
            String name = p.getFileName().toString();
            return name.endsWith(".stvn") || name.endsWith(".stvn_i") || name.endsWith(".stvn_d") || name.endsWith(".stvn_di");
          })
          .forEach(fixtureFiles::add);
    }

    assertTrue(!fixtureFiles.isEmpty(), "Must discover valid fixture files on disk");

    List<String> failures = new ArrayList<>();

    for (Path file : fixtureFiles) {
      boolean isSystem = file.getFileName().toString().contains(".stvn_ir.")
          || file.getFileName().toString().contains(".stvn_core.");
      var config = isSystem ? StvnParserConfig.SYSTEM : StvnParserConfig.DEFAULT;
      String content = Files.readString(file);
      var result = StvnCompiler.compile(content, file.toString(), config);
      List<StvnDiagnostic> diagnostics = result.diagnostics();

      boolean isWarningFixture = file.toString().contains("metadata_warnings");
      boolean isLegacyDefaultCapacityFixture = file.getFileName().toString().equals("canonical_scalars.stvn");
      List<StvnDiagnostic> unexpectedDiagnostics;
      if (isWarningFixture) {
        unexpectedDiagnostics = diagnostics.stream()
            .filter(d -> d.severity() == StvnDiagnostic.DiagnosticSeverity.ERROR)
            .toList();
      } else if (isLegacyDefaultCapacityFixture) {
        unexpectedDiagnostics = diagnostics.stream()
            .filter(d -> d.severity() == StvnDiagnostic.DiagnosticSeverity.ERROR
                || !DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(d.errorCode().orElse(null)))
            .toList();
      } else {
        unexpectedDiagnostics = diagnostics;
      }

      if (!unexpectedDiagnostics.isEmpty()) {
        StringBuilder sb = new StringBuilder();
        sb.append(file).append(" emitted ").append(unexpectedDiagnostics.size()).append(" unexpected diagnostic(s):\n");
        for (StvnDiagnostic d : unexpectedDiagnostics) {
          sb.append("  [").append(d.severity()).append("] ")
              .append(d.errorCode().orElse("NO_CODE")).append(": ")
              .append(d.message()).append(" (line ").append(d.line())
              .append(", col ").append(d.column()).append(")\n");
        }
        failures.add(sb.toString());
      }

      boolean isSourceWarningFixture = isWarningFixture && file.getFileName().toString().endsWith(".stvn");
      if (isSourceWarningFixture && diagnostics.stream().noneMatch(d -> d.severity() == StvnDiagnostic.DiagnosticSeverity.WARNING)) {
        failures.add(file + " expected warning diagnostics but found none\n");
      }
    }

    assertTrue(failures.isEmpty(),
        "Detected document identity or semantic diagnostic violations across valid fixtures:\n"
            + String.join("\n", failures));
  }
}
