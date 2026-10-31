package org.stvnadore.core.utils;

import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.binary.SchemaIdentityStrategy;
import org.stvnadore.core.binary.StvnBinaryEncoder;
import org.stvnadore.core.io.CanonicalLayoutWriter;
import org.stvnadore.core.io.CanonicalStvnWriter;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.lowering.StvnReachabilityAndCasVerifier;
import org.stvnadore.core.matrix.MetadataFixtureGenerator;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Pure-IR utility to process valid STVN test fixtures and output or verify Option B2 normalized IR snapshots.
 */
public final class IrGeneratorUtility {

  private static final Path SHARED_FIXTURES_ROOT = Paths.get("shared-fixtures");
  private static final Path FIXTURES_DIR = Paths.get("shared-fixtures/syntax/valid");
  private static final Path SUBSTRATE_SCHEMA_PATH = Paths.get("shared-fixtures/syntax/valid/modules/stvn_ir_substrate.stvn_ir.stvn_d");
  private static final Path NETWORK_PRIMITIVES_DEFS_PATH = Paths.get("shared-fixtures/syntax/valid/modules/network_primitives.stvn_d");
  private static final Path NETWORK_PRIMITIVES_BIN_PATH = Paths.get("shared-fixtures/syntax/valid/modules/network_primitives.stvn_bd");
  private static final boolean UPDATE_MODE = Boolean.getBoolean("updateSnapshots");
  private static final String PRELUDE_TYPE_PREFIX = StvnVocabulary.SIGIL_TYPIC + "org/stvnadore/prelude/";

  private IrGeneratorUtility() {
    // Utility class
  }

  public static void main(String[] args) throws Exception {
    System.out.println("Starting Option B2 pure-IR snapshot generation pipeline...");

    if (UPDATE_MODE) {
      MetadataFixtureGenerator.main(new String[0]);
      normalizeTextualFixturesToLf(SHARED_FIXTURES_ROOT);
      generateBinaryDefsFixture(NETWORK_PRIMITIVES_DEFS_PATH, NETWORK_PRIMITIVES_BIN_PATH);
    }

    int processedCount = 0;

    try (Stream<Path> paths = Files.walk(FIXTURES_DIR)) {
      List<Path> files = paths.filter(Files::isRegularFile)
          .filter(p -> (p.toString().endsWith(".stvn") || p.toString().endsWith(".stvn_i"))
              && !p.getFileName().toString().endsWith(".stvn_ir.stvn_i"))
          .toList();
      for (Path file : files) {
        processFixture(file);
        processedCount++;
      }
    }

    System.out.printf("Pipeline run completed. Processed %d fixtures successfully.%n", processedCount);
  }

  private static void normalizeTextualFixturesToLf(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(root)) {
      List<Path> textualFiles = walk.filter(Files::isRegularFile)
          .filter(IrGeneratorUtility::isTextualStvnFile)
          .toList();
      for (Path file : textualFiles) {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        if (raw.contains("\r")) {
          String lf = raw.replace("\r\n", "\n").replace("\r", "\n");
          Files.writeString(file, lf, StandardCharsets.UTF_8);
        }
      }
    }
  }

  private static boolean isTextualStvnFile(Path path) {
    String name = path.getFileName().toString();
    return name.endsWith(".stvn")
        || name.endsWith(".stvn_i")
        || name.endsWith(".stvn_d")
        || name.endsWith(".stvn_di");
  }

  private static void generateBinaryDefsFixture(Path sourceDefsPath, Path targetBinDefsPath) throws IOException {
    String source = Files.readString(sourceDefsPath, StandardCharsets.UTF_8).replace("\r\n", "\n");
    var compilation = StvnCompiler.compile(source, sourceDefsPath.toString(), StvnParserConfig.DEFAULT);
    if (compilation.hasErrors()) {
      throw new IllegalStateException("Failed to compile schema module " + sourceDefsPath + ": " + compilation.diagnostics());
    }
    var doc = compilation.document()
        .orElseThrow(() -> new IllegalStateException("Missing IR document for " + sourceDefsPath));
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault(), false);
    ByteBuffer buf = encoder.encode(doc);
    byte[] encoded = new byte[buf.remaining()];
    buf.get(encoded);
    Files.write(targetBinDefsPath, encoded);
    System.out.printf("  [UPDATED] %s (%d bytes)%n", targetBinDefsPath.getFileName(), encoded.length);
  }

  private static void processFixture(Path stvnFile) throws Exception {
    String stvnContent = Files.readString(stvnFile, StandardCharsets.UTF_8).replace("\r\n", "\n");

    var compilation = StvnCompiler.compile(stvnContent, stvnFile.toString(), StvnParserConfig.DEFAULT);
    if (compilation.hasErrors()) {
      throw new IllegalStateException("Valid fixture failed compilation " + stvnFile + ": " + compilation.diagnostics());
    }
    var liveDoc = compilation.document()
        .orElseThrow(() -> new IllegalStateException("Failed to compile valid fixture: " + stvnFile));
    var irNode = liveDoc.payload()
        .orElseThrow(() -> new IllegalStateException("Failed to extract payload from fixture: " + stvnFile));
    var meta = liveDoc.meta().orElse(null);

    String baseName = stvnFile.getFileName().toString().replaceAll("\\.stvn(_i)?$", "");
    Path irSnapshotPath = stvnFile.resolveSibling(baseName + ".stvn_ir.stvn_i");
    Path binSnapshotPath = stvnFile.resolveSibling(baseName + ".stvn_b");

    String relativeSubstratePath = stvnFile.getParent().relativize(SUBSTRATE_SCHEMA_PATH).toString().replace('\\', '/');
    String generatedSnapshot = serializeSnapshotDocument(baseName, relativeSubstratePath, liveDoc);

    if (UPDATE_MODE) {
      Files.writeString(irSnapshotPath, generatedSnapshot, StandardCharsets.UTF_8);

      var snapVerify = StvnCompiler.compile(generatedSnapshot, irSnapshotPath.toString(), StvnParserConfig.SYSTEM);
      if (!snapVerify.diagnostics().isEmpty()) {
        throw new IllegalStateException(
            "Generated Option B2 IR snapshot failed substrate verification for "
                + irSnapshotPath + ": " + snapVerify.diagnostics()
        );
      }

      boolean isCrc = stvnFile.getFileName().toString().contains("crc32c");
      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault(), isCrc);
      ByteBuffer buf = encoder.encode(irNode, meta);
      byte[] encoded = new byte[buf.remaining()];
      buf.get(encoded);
      Files.write(binSnapshotPath, encoded);

      System.out.printf("  [UPDATED] %s and %s%n", irSnapshotPath.getFileName(), binSnapshotPath.getFileName());
    } else {
      if (!Files.exists(irSnapshotPath)) {
        throw new RuntimeException(
            "Snapshot file " + irSnapshotPath + " does not exist. Run with -DupdateSnapshots=true to generate it."
        );
      }
      String existingSnapshot = Files.readString(irSnapshotPath, StandardCharsets.UTF_8);

      String cleanGenerated = generatedSnapshot.replace("\r\n", "\n").trim();
      String cleanExisting = existingSnapshot.replace("\r\n", "\n").trim();

      if (!cleanGenerated.equals(cleanExisting)) {
        throw new RuntimeException(
            "Snapshot mismatch for " + stvnFile + ". Difference detected in IR snapshot representation."
        );
      }
    }
  }

  /**
   * Pure-IR convenience serialization entry point.
   *
   * @param value the pure-IR value to serialize
   * @return the formatted STVN IR snapshot document
   */
  public static String serializeIr(StvnValue value) {
    return serializeSnapshotDocument("snapshot", "stvn_ir_substrate.stvn_ir.stvn_d", value);
  }

  /**
   * Translates a standalone pure-IR {@link StvnValue} graph into a valid Option B2 STVN snapshot document.
   *
   * @param baseName              the snapshot base name
   * @param relativeSubstratePath the relative path to {@code stvn_ir_substrate.stvn_ir.stvn_d}
   * @param rootValue             the pure-IR root value
   * @return the formatted STVN IR snapshot document
   */
  public static String serializeSnapshotDocument(
      String baseName,
      String relativeSubstratePath,
      StvnValue rootValue
  ) {
    StvnDocument synthesized = StvnReachabilityAndCasVerifier.synthesizeDocumentFromValue(rootValue);
    return serializeSnapshotDocument(baseName, relativeSubstratePath, synthesized);
  }

  /**
   * Translates a pure-IR {@link StvnDocument} into a valid Option B2 STVN snapshot document adhering to
   * {@code stvn_ir_substrate.stvn_ir.stvn_d}.
   *
   * @param baseName              the snapshot base name
   * @param relativeSubstratePath the relative path to {@code stvn_ir_substrate.stvn_ir.stvn_d}
   * @param doc                   the pure-IR document containing definitions and root payload
   * @return the formatted STVN IR snapshot document with LF line endings
   */
  public static String serializeSnapshotDocument(
      String baseName,
      String relativeSubstratePath,
      StvnDocument doc
  ) {
    StvnValue rootValue = doc.requirePayload();
    StringBuilder sb = new StringBuilder();
    sb.append("{\n");
    sb.append("  :meta {\n");
    sb.append("    #name \"").append(escapeString(baseName)).append("\"\n");
    sb.append("    #domain \"stvn_ir\"\n");
    sb.append("    #kind #BODY_INCLUDE\n");
    sb.append("  }\n");
    sb.append("  :defs {\n");
    sb.append("    :include [ \"").append(escapeString(relativeSubstratePath)).append("\" { #strip } ]\n");
    sb.append("  }\n");
    sb.append("  :type :IrSnapshotDocument\n");
    sb.append("  :body (\n");

    sb.append("    {\n");
    serializeTypeRegistry(doc, rootValue, sb);
    sb.append("    }\n");

    serializeIrValue(rootValue, 4, sb);
    sb.append("\n  )\n");
    sb.append("}\n");
    return sb.toString();
  }

  private static void serializeTypeRegistry(StvnDocument doc, StvnValue rootValue, StringBuilder sb) {
    Set<String> seenKeys = new LinkedHashSet<>();
    for (var entry : doc.defs().nominalTypes().entrySet()) {
      String fqni = entry.getKey();
      if (fqni.startsWith(PRELUDE_TYPE_PREFIX)) {
        continue;
      }
      if (seenKeys.add(fqni)) {
        emitRegistryEntry(fqni, entry.getValue(), sb);
      }
    }
    StvnSchema rootSchema = doc.rootType().orElse(rootValue.schema());
    if (seenKeys.add("<:type>")) {
      emitRegistryEntry("<:type>", rootSchema, sb);
    }
  }

  private static void emitRegistryEntry(String key, StvnSchema schema, StringBuilder sb) {
    String nodeText = formatCanonicalSchemaBody(schema);
    String aliasText = schema.aliasName().orElse("");
    boolean equatable = schema.traits().equatable();
    boolean comparable = schema.traits().comparable();
    sb.append("      [ \"").append(escapeString(key)).append("\" ( \"")
        .append(escapeString(nodeText)).append("\" \"")
        .append(escapeString(aliasText)).append("\" ( ")
        .append(equatable ? "#TRUE" : "#FALSE").append(" ")
        .append(comparable ? "#TRUE" : "#FALSE").append(" ) ) ]\n");
  }

  private static String formatCanonicalSchemaBody(StvnSchema schema) {
    try {
      StringWriter sw = new StringWriter();
      CanonicalLayoutWriter layout = new CanonicalLayoutWriter(sw);
      CanonicalStvnWriter.writeSchemaDefinitionBody(schema, layout);
      layout.flush();
      return sw.toString();
    } catch (IOException e) {
      throw new RuntimeException("Unreachable IOException in StringWriter", e);
    }
  }

  private static void serializeIrValue(StvnValue value, int indent, StringBuilder sb) {
    String spaces = " ".repeat(indent);
    switch (value) {
      case StvnValue.StvnBoolean b ->
          sb.append(spaces).append("#1 ").append(b.value() ? "#TRUE" : "#FALSE");
      case StvnValue.StvnInt i -> {
        BigInteger bitWidth = i.schema() instanceof StvnSchema.IntSchema is
            ? is.bitWidth()
            : StvnVocabulary.DEFAULT_INT_BIT_WIDTH;
        boolean unsigned = i.schema() instanceof StvnSchema.IntSchema is && is.unsigned();
        sb.append(spaces).append("#2 ( ").append(i.value()).append(" ")
            .append(bitWidth).append(" ")
            .append(unsigned ? "#TRUE" : "#FALSE").append(" )");
      }
      case StvnValue.StvnFloat f -> {
        String decText = formatFloatDecimal(f);
        if (f.schema() instanceof StvnSchema.FloatSchema fs
            && fs.representation() == StvnSchema.FloatRepresentation.EXACT_DECIMAL) {
          sb.append(spaces).append("#4 ( ").append(decText).append(" ")
              .append(fs.exactMaxDigits()).append(" )");
        } else {
          sb.append(spaces).append("#3 ").append(decText);
        }
      }
      case StvnValue.StvnString s ->
          sb.append(spaces).append("#5 \"").append(escapeString(s.value())).append("\"");
      case StvnValue.StvnTimeEpoch te -> {
        int scaleBranch = switch (te.scale()) {
          case NS -> 1;
          case US -> 2;
          case MS -> 3;
          case S -> 4;
        };
        sb.append(spaces).append("#6 #").append(scaleBranch).append(" ").append(te.epochTicks());
      }
      case StvnValue.StvnDateTime dt -> {
        int modeBranch = switch (dt.mode()) {
          case OFFSET -> 1;
          case ZONED -> 2;
          case AUDITED -> 3;
        };
        sb.append(spaces).append("#7 #").append(modeBranch)
            .append(" \"").append(escapeString(dt.canonicalIsoText())).append("\"");
      }
      case StvnValue.StvnEnum e ->
          sb.append(spaces).append("#8 ( \"").append(escapeString(e.variantTag())).append("\" ")
              .append(e.rootOrdinal()).append(" )");
      case StvnValue.StvnOption opt -> {
        if (opt.value().isEmpty()) {
          sb.append(spaces).append("#10 #NONE");
        } else {
          sb.append(spaces).append("#9 ( #SOME\n");
          serializeIrValue(opt.value().get(), indent + 2, sb);
          sb.append("\n").append(spaces).append(")");
        }
      }
      case StvnValue.StvnEither either -> {
        if (either.branch() == StvnValue.EitherBranch.RIGHT) {
          sb.append(spaces).append("#12 ( #RIGHT\n");
          serializeIrValue(either.value(), indent + 2, sb);
          sb.append("\n").append(spaces).append(")");
        } else {
          sb.append(spaces).append("#11 ( #LEFT\n");
          serializeIrValue(either.value(), indent + 2, sb);
          sb.append("\n").append(spaces).append(")");
        }
      }
      case StvnValue.StvnUnion union -> {
        sb.append(spaces).append("#13 ( ").append(union.oneBasedBranchIndex()).append("\n");
        serializeIrValue(union.value(), indent + 2, sb);
        sb.append("\n").append(spaces).append(")");
      }
      case StvnValue.StvnTuple tuple -> {
        sb.append(spaces).append("#14 [");
        for (StvnValue elem : tuple.elements()) {
          sb.append("\n");
          serializeIrValue(elem, indent + 2, sb);
        }
        if (!tuple.elements().isEmpty()) {
          sb.append("\n").append(spaces);
        }
        sb.append("]");
      }
      case StvnValue.StvnSeq seq -> {
        sb.append(spaces).append("#15 [");
        for (StvnValue elem : seq.elements()) {
          sb.append("\n");
          serializeIrValue(elem, indent + 2, sb);
        }
        if (!seq.elements().isEmpty()) {
          sb.append("\n").append(spaces);
        }
        sb.append("]");
      }
      case StvnValue.StvnSet set -> {
        sb.append(spaces).append("#16 [");
        for (StvnValue elem : set.elements()) {
          sb.append("\n");
          serializeIrValue(elem, indent + 2, sb);
        }
        if (!set.elements().isEmpty()) {
          sb.append("\n").append(spaces);
        }
        sb.append("]");
      }
      case StvnValue.StvnMap map -> {
        boolean invertible = map.schema() instanceof StvnSchema.MapSchema ms && ms.invertible();
        sb.append(spaces).append("#17 ( ").append(invertible ? "#TRUE" : "#FALSE").append(" [");
        for (var entry : map.entries()) {
          sb.append("\n").append(spaces).append("  (\n");
          serializeIrValue(entry.key(), indent + 4, sb);
          sb.append("\n");
          serializeIrValue(entry.value(), indent + 4, sb);
          sb.append("\n").append(spaces).append("  )");
        }
        if (!map.entries().isEmpty()) {
          sb.append("\n").append(spaces);
        }
        sb.append("] )");
      }
      case StvnValue.StvnErrorValue err ->
          sb.append(spaces).append("#5 \"").append(escapeString(err.diagnostic().message())).append("\"");
    }
  }

  private static String formatFloatDecimal(StvnValue.StvnFloat f) {
    boolean isExact = f.schema() instanceof StvnSchema.FloatSchema fs
        && fs.representation() == StvnSchema.FloatRepresentation.EXACT_DECIMAL;
    return switch (f.specialKind()) {
      case NAN, POSITIVE_INFINITY, NEGATIVE_INFINITY ->
          throw new IllegalStateException("Non-finite IEEE-754 sentinel cannot be serialized in STVN text IR: " + f.specialKind());
      case FINITE -> {
        if (f.negativeZero()) {
          yield "-0.0";
        }
        BigDecimal dec = isExact
            ? f.decimalValue()
            : (f.decimalValue().signum() == 0 ? BigDecimal.ZERO : f.decimalValue().stripTrailingZeros());
        String s = dec.toPlainString();
        if (!s.contains(".")) {
          s += ".0";
        }
        yield s;
      }
    };
  }

  private static String escapeString(String s) {
    return MetadataFixtureGenerator.escapeStvnString(s);
  }
}
