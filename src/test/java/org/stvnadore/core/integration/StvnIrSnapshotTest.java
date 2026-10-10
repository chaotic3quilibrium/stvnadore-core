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
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.utils.IrGeneratorUtility;
import org.stvnadore.core.binary.StvnBinaryEncoder;
import org.stvnadore.core.binary.SchemaIdentityStrategy;

/**
 * Parameterized integration test verifying live compiler IR structures match committed .ir.stvn_i snapshots.
 */
public class StvnIrSnapshotTest {

  private static final Path FIXTURES_DIR = Paths.get("shared-fixtures/syntax/valid");
  private static final Path SUBSTRATE_SCHEMA_PATH = Paths.get("shared-fixtures/syntax/valid/modules/stvn_ir_substrate.stvn_d");

  @ParameterizedTest(name = "Snapshot Test - {0}")
  @MethodSource("provideFixtureFiles")
  public void testIrSnapshot(String fileName, Path stvnFile) throws IOException {
    var stvnContent = Files.readString(stvnFile).replace("\r\n", "\n");
    
    // Compile live STVN content to IR
    var compilation = StvnCompiler.compile(stvnContent, stvnFile.toString(), StvnParserConfig.DEFAULT);
    var liveDoc = compilation.document()
        .orElseThrow(() -> new AssertionError("Failed to compile valid fixture: " + stvnFile));
    var liveIr = liveDoc.payload()
        .orElseThrow(() -> new AssertionError("Failed to extract payload from fixture: " + stvnFile));
    var meta = liveDoc.meta().orElse(null);

    var baseName = stvnFile.getFileName().toString().replaceAll("\\.stvn(_i)?$", "");
    var snapshotPath = stvnFile.resolveSibling(baseName + ".ir.stvn_i");
    var binSnapshotPath = stvnFile.resolveSibling(baseName + ".stvn_b");

    var relativeSubstratePath = stvnFile.getParent().relativize(SUBSTRATE_SCHEMA_PATH).toString().replace('\\', '/');
    var liveSnapshot = IrGeneratorUtility.serializeSnapshotDocument(baseName, relativeSubstratePath, liveIr);

    var cleanLive = liveSnapshot.replace("\r\n", "\n").trim();
    boolean isChecksummed = fileName.contains("crc32c");

    if (Boolean.getBoolean("updateSnapshots")) {
      Files.writeString(snapshotPath, cleanLive);
      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault(), isChecksummed);
      var buf = encoder.encode(liveIr, meta);
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

    // Compiler verification: Compile the .ir.stvn_i snapshot directly
    var snapshotCompilation = StvnCompiler.compile(expectedSnapshot, snapshotPath.toString(), StvnParserConfig.DEFAULT);
    Assertions.assertFalse(snapshotCompilation.hasErrors(), "Failed to compile IR snapshot document: " + snapshotCompilation.diagnostics());
    var snapshotDoc = snapshotCompilation.document().orElseThrow();
    Assertions.assertTrue(snapshotDoc.hasPayload(), "Compiled IR snapshot has no payload body");
    var snapshotPayload = (StvnValue.StvnTuple) snapshotDoc.requirePayload();
    Assertions.assertEquals(2, snapshotPayload.elements().size(), "IR snapshot root tuple must have arity 2 (TypeRegistry, IrValue)");

    // Binary verification check
    Assertions.assertTrue(Files.exists(binSnapshotPath), "Missing expected binary snapshot file: " + binSnapshotPath);

    var expectedBin = Files.readAllBytes(binSnapshotPath);

    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault(), isChecksummed);
    var buf = encoder.encode(liveIr, meta);
    var liveBin = new byte[buf.remaining()];
    buf.get(liveBin);

    Assertions.assertArrayEquals(expectedBin, liveBin, "Binary snapshot mismatch for fixture: " + stvnFile);
  }

  @Test
  @DisplayName("Zero-Deprecation Invariant: Zero legacy .stvn_ir or .stvn_i_ir files remain in shared-fixtures")
  public void testZeroLegacyIrSnapshotsRemainOnDisk() throws IOException {
    Path fixturesRoot = Paths.get("shared-fixtures");
    if (!Files.exists(fixturesRoot)) {
      return;
    }
    try (Stream<Path> stream = Files.walk(fixturesRoot)) {
      List<Path> legacyFiles = stream
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".stvn_ir") || p.getFileName().toString().endsWith(".stvn_i_ir"))
          .toList();
      Assertions.assertTrue(legacyFiles.isEmpty(),
          "Found unmigrated legacy IR snapshot files: " + legacyFiles);
    }
  }

  @Test
  @DisplayName("Rule STR-04 Invariant: Arrow fenced strings (\"\"\"->[TAG]) are rejected with fatal syntax error")
  public void testFencedStringArrowFatalRejection() {
    String payload = """
        {
          :type :String
          :body \"\"\"->[XML]
          <note>prohibited arrow</note>
          \"\"\"[XML]
        }
        """;
    var result = StvnCompiler.compileToResult(payload);
    Assertions.assertTrue(result.hasErrors(), "Legacy arrow delimiter must be rejected as an error");
    Assertions.assertTrue(
        result.diagnostics().stream().anyMatch(d ->
            d.errorCode().map(c -> c.equals("STVN_SYNTAX_ERROR") || c.equals("SYNTAX_ERROR")).orElse(false)
            || d.message().contains("Syntax Error") || d.message().contains("syntax error")),
        "Expected syntax error diagnostic"
    );
  }

  private static Stream<Arguments> provideFixtureFiles() throws IOException {
    if (!Files.exists(FIXTURES_DIR)) {
      return Stream.empty();
    }
    try (var paths = Files.walk(FIXTURES_DIR)) {
      var files = paths.filter(Files::isRegularFile)
          .filter(p -> (p.toString().endsWith(".stvn") || p.toString().endsWith(".stvn_i")) && !p.getFileName().toString().endsWith(".ir.stvn_i"))
          .toList();
      return files.stream().map(p -> Arguments.of(p.getFileName().toString(), p));
    }
  }
}
