package org.stvnadore.core.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
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

    if (Boolean.getBoolean("updateSnapshots")) {
      Files.writeString(snapshotPath, cleanLive);
      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
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

    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
    var buf = encoder.encode(liveIr);
    var liveBin = new byte[buf.remaining()];
    buf.get(liveBin);

    Assertions.assertArrayEquals(expectedBin, liveBin, "Binary snapshot mismatch for fixture: " + stvnFile);
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
