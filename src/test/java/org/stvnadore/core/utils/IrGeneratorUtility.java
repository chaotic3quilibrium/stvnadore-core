package org.stvnadore.core.utils;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema;
import org.stvnadore.core.validation.StvnTypeResolver.StvnConstraints;
import org.stvnadore.core.validation.StvnTypeResolver;
import org.stvnadore.core.parser.StvnParser;
import org.stvnadore.core.parser.StvnParser.StvnDocumentContext;

/**
 * Utility to process valid STVN test fixtures and output/verify normalized IR snapshots.
 */
public final class IrGeneratorUtility {

  private static final Path FIXTURES_DIR = Paths.get("shared-fixtures/valid-syntax");
  private static final boolean UPDATE_MODE = Boolean.getBoolean("updateSnapshots");

  private IrGeneratorUtility() {
    // Utility class
  }

  public static void main(String[] args) throws Exception {
    System.out.println("Starting IR Snapshot generation pipeline...");
    int processedCount = 0;

    try (Stream<Path> paths = Files.walk(FIXTURES_DIR)) {
      List<Path> files = paths.filter(p -> p.toString().endsWith(".stvn")).toList();
      for (Path file : files) {
        processFixture(file);
        processedCount++;
      }
    }

    System.out.printf("Pipeline run completed. Processed %d fixtures successfully.%n", processedCount);
  }

  private static void processFixture(Path stvnFile) throws Exception {
    String stvnContent = Files.readString(stvnFile);
    
    // Compile STVN input to IR
    StvnValue irNode = StvnCompiler.compile(stvnContent)
        .orElseThrow(() -> new IllegalStateException("Failed to compile valid fixture: " + stvnFile));

    // Convert the IR to the standardized snapshot text format
    String generatedSnapshot = serializeIr(irNode);

    Path irSnapshotPath = stvnFile.resolveSibling(stvnFile.getFileName().toString() + "_ir");
    Path binSnapshotPath = stvnFile.resolveSibling(stvnFile.getFileName().toString() + "_bin");

    if (UPDATE_MODE) {
      Files.writeString(irSnapshotPath, generatedSnapshot);
      
      var encoder = new org.stvnadore.core.binary.StvnBinaryEncoder(true, new org.stvnadore.core.binary.SchemaIdentityStrategy.UniversalDefault());
      java.nio.ByteBuffer buf = encoder.encode(irNode);
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
   * Translates the StvnValue graph into our standardized Indented Symbolic Property Tree text format.
   */
  public static String serializeIr(StvnValue value) {
    StringBuilder sb = new StringBuilder();
    StvnDocumentContext doc = findDocumentContext(value);
    
    // 1. Prepend TypeRegistry index
    sb.append("TypeRegistry:\n");
    if (doc != null && doc.documentBody() != null && doc.documentBody().defsEntry() != null) {
      var defsEntry = doc.documentBody().defsEntry();
      for (var typeDef : defsEntry.typeDefinition()) {
        String kw = typeDef.typeKeyword().getText();
        sb.append("  - ").append(kw).append("\n");
        var nominalSchemaOpt = resolveNominalSchema(doc, kw);
        if (nominalSchemaOpt.isPresent()) {
          serializeSchema(doc, nominalSchemaOpt.get(), 6, sb, true);
        }
      }
    }
    sb.append("  - <:type>\n");
    if (value.schema() != null) {
      serializeSchema(doc, value.schema(), 6, sb, true);
    }

    // 2. Prepend ValuePayload block
    sb.append("ValuePayload:\n");
    serializeIrNode(doc, value, 2, 2, sb);
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
      var meta = StvnTypeResolver.extractConstraints(typeDef.metadataMap());
      return StvnTypeResolver.resolvePrimitiveSchema(doc, typeDef.schemaType(), new java.util.HashSet<>())
          .map(resolvedSchema -> StvnTypeResolver.applyDefaults(new ResolvedSchema(
              resolvedSchema.node(),
              meta.merge(resolvedSchema.constraints()),
              Optional.of(kw),
              Optional.empty(),
              Optional.empty(),
              Optional.of(resolvedSchema),
              Optional.of(meta)
          )));
    });
  }

  private static void serializeIrNode(StvnDocumentContext doc, StvnValue value, int firstLineIndent, int subsequentIndent, StringBuilder sb) {
    // Check if the value is a primitive type to apply single-line inline serialization
    if (value instanceof StvnValue.StvnBoolean b) {
      String typeOrAlias = b.schema() != null ? b.schema().aliasName().orElse(b.schema().node().getText()) : ":Boolean";
      if (firstLineIndent >= 0) {
        sb.append(" ".repeat(firstLineIndent));
      }
      sb.append("StvnBoolean(").append(typeOrAlias).append("): ").append(b.value()).append("\n");
    } else if (value instanceof StvnValue.StvnInteger i) {
      String typeOrAlias = i.schema() != null ? i.schema().aliasName().orElse(i.schema().node().getText()) : ":Int32";
      if (firstLineIndent >= 0) {
        sb.append(" ".repeat(firstLineIndent));
      }
      sb.append("StvnInteger(").append(typeOrAlias).append("): ").append(i.value()).append("\n");
    } else if (value instanceof StvnValue.StvnFloat f) {
      String typeOrAlias = f.schema() != null ? f.schema().aliasName().orElse(f.schema().node().getText()) : ":Float64";
      if (firstLineIndent >= 0) {
        sb.append(" ".repeat(firstLineIndent));
      }
      sb.append("StvnFloat(").append(typeOrAlias).append("): ").append(f.value()).append("\n");
    } else if (value instanceof StvnValue.StvnString s) {
      String typeOrAlias = s.schema() != null ? s.schema().aliasName().orElse(s.schema().node().getText()) : ":String";
      if (firstLineIndent >= 0) {
        sb.append(" ".repeat(firstLineIndent));
      }
      sb.append("StvnString(").append(typeOrAlias).append("): \"").append(escapeString(s.value())).append("\"\n");
    } else if (value instanceof StvnValue.StvnTime t) {
      String typeOrAlias = t.schema() != null ? t.schema().aliasName().orElse(t.schema().node().getText()) : ":Time";
      if (firstLineIndent >= 0) {
        sb.append(" ".repeat(firstLineIndent));
      }
      sb.append("StvnTime(").append(typeOrAlias).append("): ").append(t.value().toString()).append("\n");
    } else if (value instanceof StvnValue.StvnDateTimeOffset dto) {
      String typeOrAlias = dto.schema() != null ? dto.schema().aliasName().orElse(dto.schema().node().getText()) : ":DateTimeOffset";
      if (firstLineIndent >= 0) {
        sb.append(" ".repeat(firstLineIndent));
      }
      sb.append("StvnDateTimeOffset(").append(typeOrAlias).append("): ").append(dto.value().toString()).append("\n");
    } else if (value instanceof StvnValue.StvnDateTimeZoned dtz) {
      String typeOrAlias = dtz.schema() != null ? dtz.schema().aliasName().orElse(dtz.schema().node().getText()) : ":DateTimeZoned";
      if (firstLineIndent >= 0) {
        sb.append(" ".repeat(firstLineIndent));
      }
      sb.append("StvnDateTimeZoned(").append(typeOrAlias).append("): ").append(dtz.localDateTime().toString()).append("[").append(dtz.zoneId().getId()).append("]\n");
    } else if (value instanceof StvnValue.StvnDateTimeAudited dta) {
      String typeOrAlias = dta.schema() != null ? dta.schema().aliasName().orElse(dta.schema().node().getText()) : ":DateTimeAudited";
      if (firstLineIndent >= 0) {
        sb.append(" ".repeat(firstLineIndent));
      }
      sb.append("StvnDateTimeAudited(").append(typeOrAlias).append("): ").append(dta.offsetDateTime().toString()).append("[").append(dta.zoneId().getId()).append("]\n");
    } else if (value instanceof StvnValue.StvnEnum e) {
      String typeOrAlias = e.schema() != null ? e.schema().aliasName().orElse(e.schema().node().getText()) : ":Enum";
      if (firstLineIndent >= 0) {
        sb.append(" ".repeat(firstLineIndent));
      }
      sb.append("StvnEnum(").append(typeOrAlias).append("): ").append(e.keyword()).append("\n");
    } else {
      // Container / composite types remain multi-line but omit schema serialization
      if (firstLineIndent >= 0) {
        sb.append(" ".repeat(firstLineIndent));
      }
      sb.append(value.getClass().getSimpleName()).append("\n");
      
      if (value instanceof StvnValue.StvnSeq seq) {
        if (seq.isNonEmpty()) {
          sb.append(" ".repeat(subsequentIndent)).append("  isNonEmpty: true\n");
        }
        sb.append(" ".repeat(subsequentIndent)).append("  elements:\n");
        for (StvnValue elem : seq.elements()) {
          sb.append(" ".repeat(subsequentIndent)).append("    - ");
          serializeIrNode(doc, elem, -1, subsequentIndent + 6, sb);
        }
      } else if (value instanceof StvnValue.StvnSet set) {
        if (set.isNonEmpty()) {
          sb.append(" ".repeat(subsequentIndent)).append("  isNonEmpty: true\n");
        }
        sb.append(" ".repeat(subsequentIndent)).append("  elements:\n");
        for (StvnValue elem : set.elements()) {
          sb.append(" ".repeat(subsequentIndent)).append("    - ");
          serializeIrNode(doc, elem, -1, subsequentIndent + 6, sb);
        }
      } else if (value instanceof StvnValue.StvnTuple tuple) {
        sb.append(" ".repeat(subsequentIndent)).append("  elements:\n");
        for (StvnValue elem : tuple.elements()) {
          sb.append(" ".repeat(subsequentIndent)).append("    - ");
          serializeIrNode(doc, elem, -1, subsequentIndent + 6, sb);
        }
      } else if (value instanceof StvnValue.StvnMap map) {
        if (map.isNonEmpty()) {
          sb.append(" ".repeat(subsequentIndent)).append("  isNonEmpty: true\n");
        }
        if (map.isInvertible()) {
          sb.append(" ".repeat(subsequentIndent)).append("  isInvertible: true\n");
        }
        sb.append(" ".repeat(subsequentIndent)).append("  entries:\n");
        for (var entry : map.entries().entrySet()) {
          sb.append(" ".repeat(subsequentIndent)).append("    - pair:\n");
          sb.append(" ".repeat(subsequentIndent)).append("        key: ");
          serializeIrNode(doc, entry.getKey(), -1, subsequentIndent + 13, sb);
          sb.append(" ".repeat(subsequentIndent)).append("        value: ");
          serializeIrNode(doc, entry.getValue(), -1, subsequentIndent + 15, sb);
        }
      } else if (value instanceof StvnValue.StvnOption opt) {
        if (opt.isNone()) {
          sb.append(" ".repeat(subsequentIndent)).append("  value: null\n");
        } else {
          sb.append(" ".repeat(subsequentIndent)).append("  value: ");
          serializeIrNode(doc, opt.value().get(), -1, subsequentIndent + 9, sb);
        }
      } else if (value instanceof StvnValue.StvnEither either) {
        sb.append(" ".repeat(subsequentIndent)).append("  isRight: ").append(either.isRight()).append("\n");
        if (either.isAmbiguous()) {
          sb.append(" ".repeat(subsequentIndent)).append("  isAmbiguous: true\n");
        }
        sb.append(" ".repeat(subsequentIndent)).append("  value: ");
        serializeIrNode(doc, either.value(), -1, subsequentIndent + 9, sb);
      } else if (value instanceof StvnValue.StvnUnion union) {
        sb.append(" ".repeat(subsequentIndent)).append("  tagIndex: ").append(union.tagIndex()).append("\n");
        sb.append(" ".repeat(subsequentIndent)).append("  value: ");
        serializeIrNode(doc, union.value(), -1, subsequentIndent + 9, sb);
      }
    }
  }

  private static void serializeSchema(StvnDocumentContext doc, ResolvedSchema schema, int indent, StringBuilder sb, boolean isRegistry) {
    String spaces = " ".repeat(indent);
    sb.append(spaces).append("schema:\n");
    sb.append(spaces).append("  node: ").append(schema.node().getText()).append("\n");
    if (schema.aliasName().isPresent()) {
      sb.append(spaces).append("  aliasName: ").append(schema.aliasName().get()).append("\n");
    }
    if (schema.implicitUnionTag().isPresent()) {
      sb.append(spaces).append("  implicitUnionTag: ").append(schema.implicitUnionTag().get()).append("\n");
    }
    
    serializeConstraints(doc, schema, indent + 2, sb, isRegistry);
    
    if (schema.underlyingSchema().isPresent()) {
      sb.append(spaces).append("  underlyingSchema:\n");
      ResolvedSchema underlying = schema.underlyingSchema().get();
      sb.append(spaces).append("    node: ").append(underlying.node().getText()).append("\n");
      if (underlying.aliasName().isPresent()) {
        sb.append(spaces).append("    aliasName: ").append(underlying.aliasName().get()).append("\n");
      }
    }
  }

  private static boolean isConstraintDefinedInLocal(StvnConstraints local, String name) {
    if (local == null) return false;
    switch (name) {
      case "minIncl": return local.minIncl().isPresent();
      case "minExcl": return local.minExcl().isPresent();
      case "maxIncl": return local.maxIncl().isPresent();
      case "maxExcl": return local.maxExcl().isPresent();
      case "regex": return local.regex().isPresent();
      case "preserveIndent": return local.preserveIndent();
      case "equatable": return local.equatable().isPresent();
      case "comparable": return local.comparable().isPresent();
      default: return false;
    }
  }

  private static boolean isConstraintActive(StvnConstraints cons, String name) {
    if (cons == null) return false;
    switch (name) {
      case "minIncl": return cons.minIncl().isPresent();
      case "minExcl": return cons.minExcl().isPresent();
      case "maxIncl": return cons.maxIncl().isPresent();
      case "maxExcl": return cons.maxExcl().isPresent();
      case "regex": return cons.regex().isPresent();
      case "preserveIndent": return cons.preserveIndent();
      case "equatable": return cons.equatable().isPresent();
      case "comparable": return cons.comparable().isPresent();
      default: return false;
    }
  }

  private static Optional<String> getDerivedProvenance(StvnDocumentContext doc, ResolvedSchema schema, String traitName) {
    if (doc == null || schema == null || schema.node() == null) {
      return Optional.empty();
    }
    String baseText = StvnTypeResolver.getPrimitiveBaseType(schema.node());
    if (baseText == null) return Optional.empty();

    boolean isSeq = baseText.equals(":Seq") || baseText.equals(":SeqNonEmpty");
    boolean isSet = baseText.equals(":Set") || baseText.equals(":SetNonEmpty");
    boolean isMap = baseText.equals(":Map") || baseText.equals(":MapNonEmpty") ||
                    baseText.equals(":MapInv") || baseText.equals(":MapInvNonEmpty");

    if (traitName.equals("comparable")) {
      if (isSeq || baseText.equals(":Option") ||
          baseText.equals(":Tuple") || baseText.equals(":Union") ||
          baseText.equals(":Either")) {
        List<org.stvnadore.core.parser.StvnParser.SchemaTypeContext> inner = StvnTypeResolver.getInnerSchemas(schema.node());
        for (var childNode : inner) {
          var childSchemaOpt = StvnTypeResolver.resolvePrimitiveSchema(doc, childNode, new java.util.HashSet<>());
          if (childSchemaOpt.isPresent()) {
            var childSchema = childSchemaOpt.get();
            if (childSchema.constraints().comparable().orElse(true) == false) {
              String elemName = childSchema.aliasName().orElse(childSchema.node().getText());
              return Optional.of("(derived from " + elemName + ")");
            }
          }
        }
      }
    } else if (traitName.equals("equatable")) {
      if (isSeq || isSet || baseText.equals(":Option") ||
          baseText.equals(":Tuple") || baseText.equals(":Union") ||
          baseText.equals(":Either") || isMap) {
        List<org.stvnadore.core.parser.StvnParser.SchemaTypeContext> inner = StvnTypeResolver.getInnerSchemas(schema.node());
        for (var childNode : inner) {
          var childSchemaOpt = StvnTypeResolver.resolvePrimitiveSchema(doc, childNode, new java.util.HashSet<>());
          if (childSchemaOpt.isPresent()) {
            var childSchema = childSchemaOpt.get();
            if (childSchema.constraints().equatable().orElse(true) == false) {
              String elemName = childSchema.aliasName().orElse(childSchema.node().getText());
              return Optional.of("(derived from " + elemName + ")");
            }
          }
        }
      }
    }
    return Optional.empty();
  }

  private static String getProvenance(
      StvnDocumentContext doc,
      ResolvedSchema schema,
      String name,
      boolean isRegistry,
      Optional<Object> valueOpt
  ) {
    if (valueOpt.isPresent() && (name.equals("comparable") || name.equals("equatable"))) {
      boolean val = (Boolean) valueOpt.get();
      if (!val) {
        var derived = getDerivedProvenance(doc, schema, name);
        if (derived.isPresent()) {
          return derived.get();
        }
      }
    }

    if (isRegistry) {
      ResolvedSchema curr = schema;
      int idx = 0;
      ResolvedSchema matchSchema = null;
      int matchIdx = -1;
      while (curr != null) {
        if (curr.localConstraints().isPresent() && isConstraintDefinedInLocal(curr.localConstraints().get(), name)) {
          matchSchema = curr;
          matchIdx = idx;
          break;
        }
        curr = curr.underlyingSchema().orElse(null);
        idx++;
      }

      if (matchSchema != null) {
        if (matchIdx == 0) {
          boolean mutates = false;
          if (schema.underlyingSchema().isPresent()) {
            var parentCons = schema.underlyingSchema().get().constraints();
            if (isConstraintActive(parentCons, name)) {
              mutates = true;
            }
          }
          if (name.equals("comparable") || name.equals("equatable")) {
            mutates = true;
          }
          return mutates ? "(explicit override)" : "(explicit)";
        } else {
          if (matchSchema.aliasName().isPresent()) {
            return "(inherited from " + matchSchema.aliasName().get() + ")";
          }
        }
      }
      return "(default)";
    } else {
      if (schema != null && schema.aliasName().isPresent()) {
        ResolvedSchema curr = schema;
        boolean definedInChain = false;
        while (curr != null) {
          if (curr.localConstraints().isPresent() && isConstraintDefinedInLocal(curr.localConstraints().get(), name)) {
            definedInChain = true;
            break;
          }
          curr = curr.underlyingSchema().orElse(null);
        }
        if (definedInChain) {
          return "(inherited from " + schema.aliasName().get() + ")";
        }
      }
      return "(default)";
    }
  }

  private static void serializeConstraints(StvnDocumentContext doc, ResolvedSchema schema, int indent, StringBuilder sb, boolean isRegistry) {
    String spaces = " ".repeat(indent);
    StvnConstraints constraints = schema.constraints();
    boolean hasConstraints = constraints.minIncl().isPresent()
        || constraints.minExcl().isPresent()
        || constraints.maxIncl().isPresent()
        || constraints.maxExcl().isPresent()
        || constraints.regex().isPresent()
        || constraints.preserveIndent()
        || constraints.equatable().isPresent()
        || constraints.comparable().isPresent();
        
    if (!hasConstraints) {
      return;
    }
    
    sb.append(spaces).append("constraints:\n");
    if (constraints.preserveIndent()) {
      String prov = getProvenance(doc, schema, "preserveIndent", isRegistry, Optional.of(true));
      sb.append(spaces).append("  preserveIndent: true ").append(prov).append("\n");
    }
    if (constraints.equatable().isPresent()) {
      boolean val = constraints.equatable().get();
      String prov = getProvenance(doc, schema, "equatable", isRegistry, Optional.of(val));
      sb.append(spaces).append("  equatable: ").append(val).append(" ").append(prov).append("\n");
    }
    if (constraints.comparable().isPresent()) {
      boolean val = constraints.comparable().get();
      String prov = getProvenance(doc, schema, "comparable", isRegistry, Optional.of(val));
      sb.append(spaces).append("  comparable: ").append(val).append(" ").append(prov).append("\n");
    }
    if (constraints.minIncl().isPresent()) {
      BigDecimal val = constraints.minIncl().get();
      String prov = getProvenance(doc, schema, "minIncl", isRegistry, Optional.empty());
      sb.append(spaces).append("  minIncl: ").append(val).append(" ").append(prov).append("\n");
    }
    if (constraints.minExcl().isPresent()) {
      BigDecimal val = constraints.minExcl().get();
      String prov = getProvenance(doc, schema, "minExcl", isRegistry, Optional.empty());
      sb.append(spaces).append("  minExcl: ").append(val).append(" ").append(prov).append("\n");
    }
    if (constraints.maxIncl().isPresent()) {
      BigDecimal val = constraints.maxIncl().get();
      String prov = getProvenance(doc, schema, "maxIncl", isRegistry, Optional.empty());
      sb.append(spaces).append("  maxIncl: ").append(val).append(" ").append(prov).append("\n");
    }
    if (constraints.maxExcl().isPresent()) {
      BigDecimal val = constraints.maxExcl().get();
      String prov = getProvenance(doc, schema, "maxExcl", isRegistry, Optional.empty());
      sb.append(spaces).append("  maxExcl: ").append(val).append(" ").append(prov).append("\n");
    }
    if (constraints.regex().isPresent()) {
      String val = constraints.regex().get();
      String prov = getProvenance(doc, schema, "regex", isRegistry, Optional.empty());
      sb.append(spaces).append("  regex: \"").append(escapeString(val)).append("\" ").append(prov).append("\n");
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
            .replace("\t", "\\t");
  }
}
