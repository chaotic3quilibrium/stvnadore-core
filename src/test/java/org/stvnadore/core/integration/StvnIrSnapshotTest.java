package org.stvnadore.core.integration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.binary.SchemaIdentityStrategy;
import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.binary.StvnBinaryEncoder;
import org.stvnadore.core.ir.StvnDocumentKind;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.utils.IrGeneratorUtility;

/**
 * Parameterized integration test verifying live compiler IR structures match committed Option B2
 * {@code .stvn_ir.stvn_i} and {@code .stvn_b} / {@code .stvn_bd} snapshots across {@code shared-fixtures/syntax/valid/**}.
 */
public class StvnIrSnapshotTest {

  private static final Path FIXTURES_DIR = Paths.get("shared-fixtures/syntax/valid");
  private static final Path SUBSTRATE_SCHEMA_PATH = Paths.get("shared-fixtures/syntax/valid/modules/stvn_ir_substrate.stvn_ir.stvn_d");
  private static final Path NETWORK_PRIMITIVES_DEFS_PATH = Paths.get("shared-fixtures/syntax/valid/modules/network_primitives.stvn_d");
  private static final Path NETWORK_PRIMITIVES_BIN_PATH = Paths.get("shared-fixtures/syntax/valid/modules/network_primitives.stvn_bd");

  @ParameterizedTest(name = "Snapshot Test - {0}")
  @MethodSource("provideFixtureFiles")
  public void testIrSnapshot(String fileName, Path stvnFile) throws IOException {
    var stvnContent = Files.readString(stvnFile, StandardCharsets.UTF_8).replace("\r\n", "\n");

    // Compile live STVN content to IR
    var compilation = StvnCompiler.compile(stvnContent, stvnFile.toString(), StvnParserConfig.DEFAULT);
    Assertions.assertFalse(compilation.hasErrors(), "Valid fixture produced errors " + stvnFile + ": " + compilation.diagnostics());
    var liveDoc = compilation.document()
        .orElseThrow(() -> new AssertionError("Failed to compile valid fixture: " + stvnFile));
    var liveIr = liveDoc.payload()
        .orElseThrow(() -> new AssertionError("Failed to extract payload from fixture: " + stvnFile));
    var meta = liveDoc.meta().orElse(null);

    var baseName = stvnFile.getFileName().toString().replaceAll("\\.stvn(_i)?$", "");
    var snapshotPath = stvnFile.resolveSibling(baseName + ".stvn_ir.stvn_i");
    var binSnapshotPath = stvnFile.resolveSibling(baseName + ".stvn_b");

    var relativeSubstratePath = stvnFile.getParent().relativize(SUBSTRATE_SCHEMA_PATH).toString().replace('\\', '/');
    var liveSnapshot = IrGeneratorUtility.serializeSnapshotDocument(baseName, relativeSubstratePath, liveDoc);

    var cleanLive = liveSnapshot.replace("\r\n", "\n");
    boolean isChecksummed = fileName.contains("crc32c");

    if (Boolean.getBoolean("updateSnapshots")) {
      Files.writeString(snapshotPath, cleanLive, StandardCharsets.UTF_8);
      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault(), isChecksummed);
      var buf = encoder.encode(liveIr, meta);
      var liveBin = new byte[buf.remaining()];
      buf.get(liveBin);
      Files.write(binSnapshotPath, liveBin);
      return;
    }

    Assertions.assertTrue(Files.exists(snapshotPath), "Missing expected snapshot file: " + snapshotPath);

    var expectedSnapshot = Files.readString(snapshotPath, StandardCharsets.UTF_8);

    // Enforce LF determinism and exact Option B2 snapshot parity
    Assertions.assertFalse(expectedSnapshot.contains("\r"), "Snapshot file contains CR characters (expected pure LF): " + snapshotPath);
    Assertions.assertEquals(cleanLive.trim(), expectedSnapshot.trim(), "Snapshot mismatch for fixture: " + stvnFile);

    // Compiler verification: Compile the .stvn_ir.stvn_i snapshot directly against stvn_ir_substrate.stvn_ir.stvn_d
    var snapshotCompilation = StvnCompiler.compile(expectedSnapshot, snapshotPath.toString(), StvnParserConfig.SYSTEM);
    Assertions.assertTrue(
        snapshotCompilation.diagnostics().isEmpty(),
        "IR snapshot compilation produced unexpected diagnostics for " + snapshotPath + ": " + snapshotCompilation.diagnostics()
    );
    var snapshotDoc = snapshotCompilation.document().orElseThrow();
    Assertions.assertTrue(snapshotDoc.payload().isPresent(), "Compiled IR snapshot has no payload body");
    var snapshotPayload = (StvnValue.StvnTuple) snapshotDoc.payload().orElseThrow();
    Assertions.assertEquals(2, snapshotPayload.elements().size(), "IR snapshot root tuple must have arity 2 (IrTypeRegistry, IrValue)");

    // Binary .stvn_b snapshot & round-trip isomorphism check
    Assertions.assertTrue(Files.exists(binSnapshotPath), "Missing expected binary snapshot file: " + binSnapshotPath);

    var expectedBin = Files.readAllBytes(binSnapshotPath);

    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault(), isChecksummed);
    var buf = encoder.encode(liveIr, meta);
    var liveBin = new byte[buf.remaining()];
    buf.get(liveBin);

    Assertions.assertArrayEquals(expectedBin, liveBin, "Binary snapshot mismatch for fixture: " + stvnFile);

    // Decode .stvn_b back to IR and verify Option B2 IR value isomorphism
    StvnSchema rootSchema = liveDoc.rootType().orElse(liveIr.schema());
    var decodedRoot = StvnBinaryDecoder.open(ByteBuffer.wrap(expectedBin));
    var unpackedIr = StvnBinaryDecoder.unpack(decodedRoot, Optional.of(rootSchema));
    Assertions.assertEquals(
        IrGeneratorUtility.serializeIr(liveIr),
        IrGeneratorUtility.serializeIr(unpackedIr),
        "Binary .stvn_b round-trip value isomorphism mismatch for fixture: " + stvnFile
    );
  }

  @Test
  @DisplayName("Binary Definitions Snapshot (.stvn_bd): network_primitives.stvn_bd matches network_primitives.stvn_d and unpacks cleanly")
  public void testNetworkPrimitivesBinaryDefsSnapshot() throws IOException {
    String defsSource = Files.readString(NETWORK_PRIMITIVES_DEFS_PATH, StandardCharsets.UTF_8).replace("\r\n", "\n");
    var sourceRes = StvnCompiler.compile(defsSource, NETWORK_PRIMITIVES_DEFS_PATH.toString(), StvnParserConfig.DEFAULT);
    Assertions.assertTrue(sourceRes.diagnostics().isEmpty(), "network_primitives.stvn_d produced diagnostics: " + sourceRes.diagnostics());
    var sourceDoc = sourceRes.document().orElseThrow();

    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault(), false);
    var buf = encoder.encode(sourceDoc);
    var expectedBytes = new byte[buf.remaining()];
    buf.get(expectedBytes);

    if (Boolean.getBoolean("updateSnapshots")) {
      Files.write(NETWORK_PRIMITIVES_BIN_PATH, expectedBytes);
      return;
    }

    Assertions.assertTrue(Files.exists(NETWORK_PRIMITIVES_BIN_PATH), "Missing binary definitions fixture: " + NETWORK_PRIMITIVES_BIN_PATH);
    byte[] onDiskBytes = Files.readAllBytes(NETWORK_PRIMITIVES_BIN_PATH);
    Assertions.assertArrayEquals(expectedBytes, onDiskBytes, "Binary .stvn_bd mismatch for network_primitives.stvn_bd");

    var binRes = StvnCompiler.compile(NETWORK_PRIMITIVES_BIN_PATH, StvnParserConfig.DEFAULT);
    Assertions.assertTrue(binRes.diagnostics().isEmpty(), "network_primitives.stvn_bd compilation produced diagnostics: " + binRes.diagnostics());
    var decodedDoc = binRes.document().orElseThrow();
    Assertions.assertEquals(
        StvnDocumentKind.DEFS,
        decodedDoc.meta().flatMap(m -> m.kind()).orElse(null),
        "Decoded network_primitives.stvn_bd must carry #kind #DEFS"
    );
    Assertions.assertEquals(
        sourceDoc.defs().nominalTypes().keySet(),
        decodedDoc.defs().nominalTypes().keySet(),
        "Decoded network_primitives.stvn_bd nominal type set must match network_primitives.stvn_d"
    );
  }

  @Test
  @DisplayName("Zero-Deprecation Invariant: Zero legacy .stvn_ir, .stvn_i_ir, or .ir.stvn_i files remain in shared-fixtures")
  public void testZeroLegacyIrSnapshotsRemainOnDisk() throws IOException {
    Path fixturesRoot = Paths.get("shared-fixtures");
    if (!Files.exists(fixturesRoot)) {
      return;
    }
    try (Stream<Path> stream = Files.walk(fixturesRoot)) {
      List<Path> legacyFiles = stream
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".stvn_ir")
              || p.getFileName().toString().endsWith(".stvn_i_ir")
              || p.getFileName().toString().endsWith(".ir.stvn_i"))
          .toList();
      Assertions.assertTrue(legacyFiles.isEmpty(),
          "Found unmigrated legacy IR snapshot files: " + legacyFiles);
    }
  }

  @Test
  @DisplayName("Rule STR-04 Invariant: Arrow fenced strings (\"\"\"->[TAG]) are rejected with E026 / ERR_PROHIBITED_FENCE_ARROW")
  public void testFencedStringArrowFatalRejection() {
    String payload = """
        {
          :type :String
          :body \"\"\"->[XML]
          <note>prohibited arrow</note>
          [XML]\"\"\"
        }
        """;
    var result = StvnCompiler.compileToResult(payload);
    Assertions.assertTrue(result.hasErrors(), "Legacy arrow delimiter must be rejected as an error");
    Assertions.assertTrue(
        result.diagnostics().stream().anyMatch(d ->
            "E026".equals(d.canonicalCode().orElse(""))
                || d.symbolicId().map(c -> c.equals("ERR_PROHIBITED_FENCE_ARROW") || c.equals("FENCE_PROHIBITED_ARROW")).orElse(false)),
        () -> "Expected E026 / ERR_PROHIBITED_FENCE_ARROW diagnostic, got: " + result.diagnostics()
    );
  }

  private static Stream<Arguments> provideFixtureFiles() throws IOException {
    if (!Files.exists(FIXTURES_DIR)) {
      return Stream.empty();
    }
    try (var paths = Files.walk(FIXTURES_DIR)) {
      var files = paths.filter(Files::isRegularFile)
          .filter(p -> (p.toString().endsWith(".stvn") || p.toString().endsWith(".stvn_i"))
              && !p.getFileName().toString().endsWith(".stvn_ir.stvn_i"))
          .toList();
      return files.stream().map(p -> Arguments.of(p.getFileName().toString(), p));
    }
  }
}
