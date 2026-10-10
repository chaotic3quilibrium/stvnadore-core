package org.stvnadore.core.utils;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema;
import org.stvnadore.core.validation.StvnTypeResolver;
import org.stvnadore.core.parser.StvnParser.StvnDocumentContext;

/**
 * Utility to process valid STVN test fixtures and output/verify normalized IR snapshots.
 */
public final class IrGeneratorUtility {

  private static final Path FIXTURES_DIR = Paths.get("shared-fixtures/syntax/valid");
  private static final Path SUBSTRATE_SCHEMA_PATH = Paths.get("shared-fixtures/syntax/valid/modules/stvn_ir_substrate.stvn_d");
  private static final boolean UPDATE_MODE = Boolean.getBoolean("updateSnapshots");

  private IrGeneratorUtility() {
    // Utility class
  }

  public static void main(String[] args) throws Exception {
    System.out.println("Starting IR Snapshot generation pipeline...");
    int processedCount = 0;

    try (Stream<Path> paths = Files.walk(FIXTURES_DIR)) {
      List<Path> files = paths.filter(Files::isRegularFile)
          .filter(p -> (p.toString().endsWith(".stvn") || p.toString().endsWith(".stvn_i")) && !p.getFileName().toString().endsWith(".ir.stvn_i"))
          .toList();
      for (Path file : files) {
        processFixture(file);
        processedCount++;
      }
    }

    System.out.printf("Pipeline run completed. Processed %d fixtures successfully.%n", processedCount);
  }

  private static void processFixture(Path stvnFile) throws Exception {
    String stvnContent = Files.readString(stvnFile).replace("\r\n", "\n");
    
    // Compile STVN input to IR
    var compilation = StvnCompiler.compile(stvnContent, stvnFile.toString(), org.stvnadore.core.StvnParserConfig.DEFAULT);
    var liveDoc = compilation.document()
        .orElseThrow(() -> new IllegalStateException("Failed to compile valid fixture: " + stvnFile));
    StvnValue irNode = liveDoc.payload()
        .orElseThrow(() -> new IllegalStateException("Failed to extract payload from fixture: " + stvnFile));
    var meta = liveDoc.meta().orElse(null);

    String baseName = stvnFile.getFileName().toString().replaceAll("\\.stvn(_i)?$", "");
    Path irSnapshotPath = stvnFile.resolveSibling(baseName + ".ir.stvn_i");
    Path binSnapshotPath = stvnFile.resolveSibling(baseName + ".stvn_b");

    String relativeSubstratePath = stvnFile.getParent().relativize(SUBSTRATE_SCHEMA_PATH).toString().replace('\\', '/');
    String generatedSnapshot = serializeSnapshotDocument(baseName, relativeSubstratePath, irNode);

    if (UPDATE_MODE) {
      Files.writeString(irSnapshotPath, generatedSnapshot);
      
      boolean isCrc = stvnFile.getFileName().toString().contains("crc32c");
      var encoder = new org.stvnadore.core.binary.StvnBinaryEncoder(true, new org.stvnadore.core.binary.SchemaIdentityStrategy.UniversalDefault(), isCrc);
      java.nio.ByteBuffer buf = encoder.encode(irNode, meta);
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
      String existingSnapshot = Files.readString(irSnapshotPath);
      
      // Clean line endings for cross-OS comparison
      String cleanGenerated = generatedSnapshot.replace("\r\n", "\n").trim();
      String cleanExisting = existingSnapshot.replace("\r\n", "\n").trim();

      if (!cleanGenerated.equals(cleanExisting)) {
        throw new RuntimeException(
            "Snapshot mismatch for " + stvnFile + ". Difference detected in AST representation."
        );
      }
    }
  }

  /**
   * Backward-compatible convenience serialization entry point.
   */
  public static String serializeIr(StvnValue value) {
    return serializeSnapshotDocument("snapshot", "stvn_ir_substrate.stvn_d", value);
  }

  /**
   * Translates the StvnValue graph and TypeRegistry into a valid STVN document adhering to stvn_ir_substrate.stvn_d.
   */
  public static String serializeSnapshotDocument(String baseName, String relativeSubstratePath, StvnValue rootValue) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\n");
    sb.append("  :meta {\n");
    sb.append("    #name \"").append(escapeString(baseName)).append("\"\n");
    sb.append("    #domain \"ir\"\n");
    sb.append("    #kind #BODY_INCLUDE\n");
    sb.append("  }\n");
    sb.append("  :defs {\n");
    sb.append("    :include [ \"").append(escapeString(relativeSubstratePath)).append("\" { #strip } ]\n");
    sb.append("  }\n");
    sb.append("  :type :IrSnapshotDocument\n");
    sb.append("  :body (\n");

    // 1. TypeRegistry map
    StvnDocumentContext doc = findDocumentContext(rootValue);
    sb.append("    {\n");
    serializeTypeRegistry(doc, rootValue, sb);
    sb.append("    }\n");

    // 2. Lowered IrValue coproduct
    serializeIrValue(rootValue, 4, sb);
    sb.append("\n  )\n");
    sb.append("}\n");
    return sb.toString();
  }

  private static StvnDocumentContext findDocumentContext(StvnValue value) {
    if (value == null || value.schema() == null || value.schema().node() == null) {
      return null;
    }
    org.antlr.v4.runtime.ParserRuleContext current = value.schema().node();
    while (current != null) {
      if (current instanceof StvnDocumentContext) {
        return (StvnDocumentContext) current;
      }
      current = current.getParent();
    }
    return null;
  }

  private static Optional<ResolvedSchema> resolveNominalSchema(StvnDocumentContext doc, String kw) {
    return StvnTypeResolver.findTypeDefinition(doc, kw).flatMap(typeDef -> {
      var metaMap = typeDef.schemaType() != null ? typeDef.schemaType().metadataMap() : null;
      var meta = StvnTypeResolver.extractConstraints(metaMap);
      return StvnTypeResolver.resolvePrimitiveSchema(doc, typeDef.schemaType(), new java.util.HashSet<>())
          .map(resolvedSchema -> StvnTypeResolver.applyDefaults(new ResolvedSchema(
              resolvedSchema.node(),
              meta.merge(resolvedSchema.constraints()),
              Optional.of(kw),
              Optional.empty(),
              Optional.empty(),
              Optional.of(resolvedSchema),
              Optional.of(meta),
              false,
              Optional.empty()
          )));
    });
  }

  private static void serializeTypeRegistry(StvnDocumentContext doc, StvnValue rootValue, StringBuilder sb) {
    Set<String> seenKeys = new LinkedHashSet<>();
    if (doc != null && doc.documentBody() != null && doc.documentBody().defsEntry() != null) {
      var defsEntry = doc.documentBody().defsEntry();
      if (defsEntry.defsElement() != null) {
        for (var de : defsEntry.defsElement()) {
          if (de.typeDefinition() != null) {
            String kw = de.typeDefinition().typeDefTarget().getText();
            if (seenKeys.add(kw)) {
              var schemaOpt = resolveNominalSchema(doc, kw);
              if (schemaOpt.isPresent()) {
                emitRegistryEntry(kw, schemaOpt.get(), sb);
              } else {
                emitFallbackRegistryEntry(kw, sb);
              }
            }
          } else if (de.packageEnclosure() != null) {
            var pkgPath = de.packageEnclosure().packagePath().getText();
            if (de.packageEnclosure().packageElement() != null) {
              for (var pe : de.packageEnclosure().packageElement()) {
                if (pe.typeDefinition() != null) {
                  String localName = pe.typeDefinition().typeDefTarget().getText().substring(1);
                  String fqni = pkgPath + "/" + localName;
                  if (seenKeys.add(fqni)) {
                    var schemaOpt = resolveNominalSchema(doc, fqni);
                    if (schemaOpt.isPresent()) {
                      emitRegistryEntry(fqni, schemaOpt.get(), sb);
                    } else {
                      emitFallbackRegistryEntry(fqni, sb);
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
    if (rootValue.schema() != null && seenKeys.add("<:type>")) {
      emitRegistryEntry("<:type>", rootValue.schema(), sb);
    }
  }

  private static void emitRegistryEntry(String key, ResolvedSchema schema, StringBuilder sb) {
    String nodeText = schema.node() != null ? schema.node().getText() : ":Any";
    String aliasText = schema.aliasName().orElse("");
    boolean equatable = schema.constraints().equatable().orElse(true);
    boolean comparable = schema.constraints().comparable().orElse(true);
    sb.append("      [ \"").append(escapeString(key)).append("\" ( \"")
      .append(escapeString(nodeText)).append("\" \"")
      .append(escapeString(aliasText)).append("\" ( ")
      .append(equatable ? "#TRUE" : "#FALSE").append(" ")
      .append(comparable ? "#TRUE" : "#FALSE").append(" ) ) ]\n");
  }

  private static void emitFallbackRegistryEntry(String key, StringBuilder sb) {
    sb.append("      [ \"").append(escapeString(key)).append("\" ( \":Any\" \"\" ( #TRUE #TRUE ) ) ]\n");
  }

  private static void serializeIrValue(StvnValue value, int indent, StringBuilder sb) {
    String spaces = " ".repeat(indent);
    if (value instanceof StvnValue.StvnBoolean b) {
      sb.append(spaces).append("#1 ").append(b.value() ? "#TRUE" : "#FALSE");
    } else if (value instanceof StvnValue.StvnInteger i) {
      sb.append(spaces).append("#2 ( ").append(i.value()).append(" ")
        .append(i.bitWidth()).append(" ")
        .append(i.isUnsigned() ? "#TRUE" : "#FALSE").append(" )");
    } else if (value instanceof StvnValue.StvnFloat f) {
      sb.append(spaces).append("#3 ");
      if (f.isNaN()) {
        sb.append("#NaN");
      } else if (f.isPositiveInfinity()) {
        sb.append("#+Infinity");
      } else if (f.isNegativeInfinity()) {
        sb.append("#-Infinity");
      } else if (f.isNegativeZero()) {
        sb.append("-0.0");
      } else {
        String s = f.value().toPlainString();
        if (!s.contains(".")) {
          s += ".0";
        }
        sb.append(s);
      }
    } else if (value instanceof StvnValue.StvnString s) {
      sb.append(spaces).append("#4 \"").append(escapeString(s.value())).append("\"");
    } else if (value instanceof StvnValue.StvnEnum e) {
      sb.append(spaces).append("#5 ( \"").append(escapeString(e.keyword())).append("\" ").append(e.sequentialIndex()).append(" )");
    } else if (value instanceof StvnValue.StvnOption opt) {
      if (opt.isNone()) {
        sb.append(spaces).append("#7 #NONE");
      } else {
        sb.append(spaces).append("#6 ( #SOME\n");
        serializeIrValue(opt.value().get(), indent + 2, sb);
        sb.append("\n").append(spaces).append(")");
      }
    } else if (value instanceof StvnValue.StvnEither either) {
      if (either.isRight()) {
        sb.append(spaces).append("#9 ( #RIGHT\n");
        serializeIrValue(either.value(), indent + 2, sb);
        sb.append("\n").append(spaces).append(")");
      } else {
        sb.append(spaces).append("#8 ( #LEFT\n");
        serializeIrValue(either.value(), indent + 2, sb);
        sb.append("\n").append(spaces).append(")");
      }
    } else if (value instanceof StvnValue.StvnUnion union) {
      sb.append(spaces).append("#10 ( ").append(union.tagIndex() + 1).append("\n");
      serializeIrValue(union.value(), indent + 2, sb);
      sb.append("\n").append(spaces).append(")");
    } else if (value instanceof StvnValue.StvnTuple tuple) {
      sb.append(spaces).append("#11 [");
      for (StvnValue elem : tuple.elements()) {
        sb.append("\n");
        serializeIrValue(elem, indent + 2, sb);
      }
      if (!tuple.elements().isEmpty()) {
        sb.append("\n").append(spaces);
      }
      sb.append("]");
    } else if (value instanceof StvnValue.StvnSeq seq) {
      sb.append(spaces).append("#12 ( ").append(seq.isNonEmpty() ? "#TRUE" : "#FALSE").append(" [");
      for (StvnValue elem : seq.elements()) {
        sb.append("\n");
        serializeIrValue(elem, indent + 2, sb);
      }
      if (!seq.elements().isEmpty()) {
        sb.append("\n").append(spaces);
      }
      sb.append("] )");
    } else if (value instanceof StvnValue.StvnSet set) {
      sb.append(spaces).append("#13 [");
      for (StvnValue elem : set.elements()) {
        sb.append("\n");
        serializeIrValue(elem, indent + 2, sb);
      }
      if (!set.elements().isEmpty()) {
        sb.append("\n").append(spaces);
      }
      sb.append("]");
    } else if (value instanceof StvnValue.StvnMap map) {
      sb.append(spaces).append("#14 ( ").append(map.isInvertible() ? "#TRUE" : "#FALSE").append(" [");
      for (var entry : map.entries().entrySet()) {
        sb.append("\n").append(spaces).append("  (\n");
        serializeIrValue(entry.getKey(), indent + 4, sb);
        sb.append("\n");
        serializeIrValue(entry.getValue(), indent + 4, sb);
        sb.append("\n").append(spaces).append("  )");
      }
      if (!map.entries().isEmpty()) {
        sb.append("\n").append(spaces);
      }
      sb.append("] )");
    } else if (value instanceof StvnValue.StvnTime t) {
      if (t.value() instanceof BigInteger bi) {
        sb.append(spaces).append("#2 ( ").append(bi).append(" 64 #FALSE )");
      } else {
        sb.append(spaces).append("#4 \"").append(escapeString(t.value().toString())).append("\"");
      }
    } else if (value instanceof StvnValue.StvnDateTimeOffset dto) {
      sb.append(spaces).append("#4 \"").append(escapeString(dto.value().toString())).append("\"");
    } else if (value instanceof StvnValue.StvnDateTimeZoned dtz) {
      sb.append(spaces).append("#4 \"").append(escapeString(dtz.localDateTime().toString() + "[" + dtz.zoneId().getId() + "]")).append("\"");
    } else if (value instanceof StvnValue.StvnDateTimeAudited dta) {
      sb.append(spaces).append("#4 \"").append(escapeString(dta.offsetDateTime().toString() + "[" + dta.zoneId().getId() + "]")).append("\"");
    } else if (value != null) {
      sb.append(spaces).append("#4 \"").append(escapeString(value.toString())).append("\"");
    }
  }

  private static String escapeString(String s) {
    if (s == null) {
      return "";
    }
    return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "    ");
  }
}
