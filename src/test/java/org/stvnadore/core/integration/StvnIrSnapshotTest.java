package org.stvnadore.core.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.utils.IrGeneratorUtility;
import org.stvnadore.core.binary.StvnBinaryEncoder;
import org.stvnadore.core.binary.SchemaIdentityStrategy;

/**
 * Parameterized integration test verifying live compiler IR structures match committed .stvn_ir snapshots.
 */
public class StvnIrSnapshotTest {

  private static final Path FIXTURES_DIR = Paths.get("shared-fixtures/valid-syntax");

  @ParameterizedTest(name = "Snapshot Test - {0}")
  @MethodSource("provideFixtureFiles")
  public void testIrSnapshot(String fileName, Path stvnFile) throws IOException {
    var stvnContent = Files.readString(stvnFile).replace("\r\n", "\n");
    
    // Compile live STVN content to IR
    var liveIr = StvnCompiler.compile(stvnContent, stvnFile.toString())
        .orElseThrow(() -> new AssertionError("Failed to compile valid fixture: " + stvnFile));

    // Serialize to Option B layout
    var liveSnapshot = IrGeneratorUtility.serializeIr(liveIr);

    var snapshotPath = stvnFile.resolveSibling(stvnFile.getFileName().toString() + "_ir");
    var binSnapshotPath = stvnFile.resolveSibling(stvnFile.getFileName().toString() + "_bin");

    var cleanLive = liveSnapshot.replace("\r\n", "\n").trim();
    boolean isChecksummed = fileName.contains("crc32c");

    if (Boolean.getBoolean("updateSnapshots")) {
      Files.writeString(snapshotPath, cleanLive);
      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault(), isChecksummed);
      var buf = encoder.encode(liveIr);
      var liveBin = new byte[buf.remaining()];
      buf.get(liveBin);
      Files.write(binSnapshotPath, liveBin);
      return;
    }

    Assertions.assertTrue(Files.exists(snapshotPath), "Missing expected snapshot file: " + snapshotPath);

    var expectedSnapshot = Files.readString(snapshotPath);

    // Normalize line endings for robust cross-platform comparison
    var cleanExpected = expectedSnapshot.replace("\r\n", "\n").trim();

    Assertions.assertEquals(cleanExpected, cleanLive, "Snapshot mismatch for fixture: " + stvnFile);

    // Binary verification check
    Assertions.assertTrue(Files.exists(binSnapshotPath), "Missing expected binary snapshot file: " + binSnapshotPath);

    var expectedBin = Files.readAllBytes(binSnapshotPath);

    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault(), isChecksummed);
    var buf = encoder.encode(liveIr);
    var liveBin = new byte[buf.remaining()];
    buf.get(liveBin);

    Assertions.assertArrayEquals(expectedBin, liveBin, "Binary snapshot mismatch for fixture: " + stvnFile);
  }

  @Test
  @DisplayName("Rule STR-04 Invariant: Modern and legacy arrow fenced strings produce identical IR and expected diagnostics")
  public void testFencedStringArrowDeprecationParity() throws IOException {
    Path modernPath = FIXTURES_DIR.resolve("fenced_string_nested.stvn");
    Path legacyPath = FIXTURES_DIR.resolve("fenced_string_deprecated_arrow.stvn");

    Assertions.assertTrue(Files.exists(modernPath), "Missing modern fixture: " + modernPath);
    Assertions.assertTrue(Files.exists(legacyPath), "Missing legacy fixture: " + legacyPath);

    String modernContent = Files.readString(modernPath).replace("\r\n", "\n");
    String legacyContent = Files.readString(legacyPath).replace("\r\n", "\n");

    var modernResult = StvnCompiler.compileToResult(modernContent, modernPath.toString());
    var legacyResult = StvnCompiler.compileToResult(legacyContent, legacyPath.toString());

    Assertions.assertTrue(modernResult.isSuccess(), "Modern fenced string compilation must succeed cleanly");
    Assertions.assertTrue(legacyResult.isSuccess(), "Legacy arrow fenced string compilation must succeed");

    // Assert zero diagnostics on canonical syntax
    Assertions.assertEquals(0, modernResult.diagnostics().size(), "Modern syntax must emit 0 diagnostics");

    // Assert exactly 1 warning on legacy syntax
    Assertions.assertTrue(legacyResult.hasWarnings(), "Legacy syntax must record warning diagnostic");
    Assertions.assertEquals(1, legacyResult.diagnostics().size(), "Expected exactly 1 diagnostic for legacy arrow");
    var diag = legacyResult.diagnostics().getFirst();
    Assertions.assertEquals(org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity.WARNING, diag.severity());
    Assertions.assertTrue(diag.message().contains("Rule STR-04 deprecation: The '->' arrow delimiter in fenced strings is deprecated; use '\"\"\"[TAG]' instead."));

    // Assert mathematical AST identity
    Assertions.assertEquals(modernResult.document().get(), legacyResult.document().get(), "Modern and legacy AST representations must be identical");
  }

  private static Stream<Arguments> provideFixtureFiles() throws IOException {
    if (!Files.exists(FIXTURES_DIR)) {
      return Stream.empty();
    }
    try (var paths = Files.walk(FIXTURES_DIR)) {
      var files = paths.filter(p -> p.toString().endsWith(".stvn")).toList();
      return files.stream().map(p -> Arguments.of(p.getFileName().toString(), p));
    }
  }
}
