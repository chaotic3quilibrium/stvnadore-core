package org.stvnadore.core.io;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.ir.StvnValue.*;
import org.stvnadore.core.parser.StvnParser;
import org.stvnadore.core.printer.PrinterOptions;
import org.stvnadore.core.printer.StvnTextPrinter;
import org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema;
import org.stvnadore.core.validation.StvnTypeResolver.StvnConstraints;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import org.stvnadore.core.io.StvnCanonicalDefinitionsResolver.ResolvedCanonicalDefinition;

/**
 * Serializes STVN value trees into a deterministic, canonical text representation.
 * <p>
 * This class implements strict text canonicalization rules defined by the STVN
 * specification to guarantee that semantically identical value trees produce
 * byte-for-byte identical text streams. This mathematical determinism is a critical
 * prerequisite for Content Addressable Storage (CAS) systems and cryptographic fingerprinting.
 * </p>
 *
 * <h2>Canonicalization Invariants</h2>
 * <ul>
 *   <li><b>Map Entry Iteration:</b> Map entries are iterated and written in their exact sequenced
 *       order. Payloads must enforce key ordering deterministically (via sorted {@link java.util.SequencedMap}
 *       structures) to prevent semantic drift.</li>
 *   <li><b>Trait-Ordering Consistency:</b> Constraints on schema aliases within the {@code :defs}
 *       section are serialized in a strict 7-tier canonical sequence (Tier 1 Flags &amp; Modes,
 *       Tier 2 Temporal Scale, Tier 3 Dimensions &amp; Capacity, Tier 4 Value Intervals,
 *       Tier 5 Validation Patterns, Tier 6 Domain Subsets) adhering to Invariant 4.</li>
 *   <li><b>Boolean/Tag Standardization:</b> Boolean literals are coerced strictly to their long-form
 *       variants ({@code #TRUE} and {@code #FALSE}). Similarly, Option and Either tags are formatted
 *       using their explicit long-form symbols ({@code #Some}, {@code #None}, {@code #Left}, {@code #Right}).</li>
 *   <li><b>Whitespace Normalization:</b> Emits no indentation, trailing whitespace, or newlines (except
 *       where strictly dictated by block strings), separating adjacent word tokens with exactly a single
 *       space.</li>
 * </ul>
 */
@NullMarked
public final class CanonicalStvnWriter implements StvnTextPrinter {

  /**
   * Constructs a new {@code CanonicalStvnWriter}.
   */
  public CanonicalStvnWriter() {
  }

  /**
   * Serializes the given STVN value tree into the specified writer in its canonical form.
   *
   * @param value  the STVN value tree to serialize
   * @param target the destination writer
   * @throws IOException if an I/O error occurs during serialization
   * @throws NullPointerException if any argument is null
   */
  @Override
  public void print(StvnValue value, Writer target) throws IOException {
    var layout = new CanonicalLayoutWriter(target);
    layout.openGroup("{");

    var schema = value.schema();
    List<ResolvedCanonicalDefinition> defs = StvnCanonicalDefinitionsResolver.resolveDefinitions(value);
    if (!defs.isEmpty()) {
      layout.writeLiteral(":defs");
      layout.openGroup("{");

      for (var s : defs) {
        layout.writeLiteral(s.canonicalName());

        var constraints = s.constraints();
        if (!isConstraintsEmpty(constraints)) {
          layout.openGroup("{");

          // Tier 1: Flags & Intrinsic Modes (Bare flags without #TRUE)
          if (constraints.unsigned() && constraints.explicitOverrides().contains("unsigned")) {
            layout.writeLiteral("#unsigned");
          }
          if (constraints.exact() && constraints.explicitOverrides().contains("exact")) {
            layout.writeLiteral("#exact");
          }
          if (constraints.invertible() && constraints.explicitOverrides().contains("invertible")) {
            layout.writeLiteral("#invertible");
          }
          if (constraints.preserveIndent() && constraints.explicitOverrides().contains("preserveIndent")) {
            layout.writeLiteral("#preserveIndent");
          }
          if (constraints.offset() && constraints.explicitOverrides().contains("offset")) {
            layout.writeLiteral("#offset");
          }
          if (constraints.zoned() && constraints.explicitOverrides().contains("zoned")) {
            layout.writeLiteral("#zoned");
          }
          if (constraints.audited() && constraints.explicitOverrides().contains("audited")) {
            layout.writeLiteral("#audited");
          }
          var equatable = constraints.equatable().orElse(null);
          if (equatable != null && constraints.explicitOverrides().contains("equatable")) {
            layout.writeLiteral("#equatable");
            layout.writeBoolean(equatable, PrinterOptions.SymbolStyle.LONG_FORM);
          }
          var comparable = constraints.comparable().orElse(null);
          if (comparable != null && constraints.explicitOverrides().contains("comparable")) {
            layout.writeLiteral("#comparable");
            layout.writeBoolean(comparable, PrinterOptions.SymbolStyle.LONG_FORM);
          }

          // Tier 2: Temporal Scale
          var scale = constraints.scale().orElse(null);
          if (scale != null) {
            layout.writeLiteral(scale.startsWith("#") ? scale : ("#" + scale));
          }

          // Tier 3: Dimensions & Capacity
          var size = constraints.size().orElse(null);
          if (size != null) {
            layout.writeLiteral("#size");
            layout.writeLiteral(size.toString());
          }
          var minSize = constraints.minSize().orElse(null);
          if (minSize != null) {
            layout.writeLiteral("#minSize");
            layout.writeLiteral(minSize.toString());
          }
          var maxSize = constraints.maxSize().orElse(null);
          if (maxSize != null) {
            layout.writeLiteral("#maxSize");
            layout.writeLiteral(maxSize.toString());
          }

          // Tier 4: Value Intervals (Lower bounds strictly precede Upper bounds)
          if (constraints.minIncl().isPresent()) {
            layout.writeLiteral("#minIncl");
            layout.writeLiteral(constraints.minIncl().get().toString());
          } else if (constraints.dateMinIncl().isPresent()) {
            layout.writeLiteral("#minIncl");
            layout.writeSimpleString(constraints.dateMinIncl().get());
          }
          if (constraints.minExcl().isPresent()) {
            layout.writeLiteral("#minExcl");
            layout.writeLiteral(constraints.minExcl().get().toString());
          } else if (constraints.dateMinExcl().isPresent()) {
            layout.writeLiteral("#minExcl");
            layout.writeSimpleString(constraints.dateMinExcl().get());
          }
          if (constraints.maxExcl().isPresent()) {
            layout.writeLiteral("#maxExcl");
            layout.writeLiteral(constraints.maxExcl().get().toString());
          } else if (constraints.dateMaxExcl().isPresent()) {
            layout.writeLiteral("#maxExcl");
            layout.writeSimpleString(constraints.dateMaxExcl().get());
          }
          if (constraints.maxIncl().isPresent()) {
            layout.writeLiteral("#maxIncl");
            layout.writeLiteral(constraints.maxIncl().get().toString());
          } else if (constraints.dateMaxIncl().isPresent()) {
            layout.writeLiteral("#maxIncl");
            layout.writeSimpleString(constraints.dateMaxIncl().get());
          }

          // Tier 5: Validation Patterns
          var regex = constraints.regex().orElse(null);
          if (regex != null) {
            layout.writeLiteral("#regex");
            layout.writeSimpleString(regex);
          }

          // Tier 6: Domain Subsets
          var filterIncl = constraints.filterIncl().orElse(null);
          if (filterIncl != null) {
            layout.writeLiteral("#filterIncl");
            layout.openGroup("[");
            for (String v : filterIncl) {
              layout.writeLiteral(v);
            }
            layout.closeGroup("]");
          }
          var filterExcl = constraints.filterExcl().orElse(null);
          if (filterExcl != null) {
            layout.writeLiteral("#filterExcl");
            layout.openGroup("[");
            for (String v : filterExcl) {
              layout.writeLiteral(v);
            }
            layout.closeGroup("]");
          }

          layout.closeGroup("}");
        }

        writeSchemaType(s.schemaNode(), layout, s.lexicalContext());
      }

      layout.closeGroup("}");
    }

    layout.writeLiteral(":type");
    if (schema != null) {
      var alias = schema.aliasName().orElse(null);
      if (alias != null) {
        layout.writeLiteral(alias);
      } else {
        writeSchemaType(schema.node(), layout, schema.node());
      }
    } else {
      throw new IOException("Missing schema context for canonical serialization");
    }

    layout.writeLiteral(":body");
    writeValue(value, layout);

    layout.closeGroup("}");
    layout.flush();
  }

  private boolean isConstraintsEmpty(StvnConstraints c) {
    return c.minIncl().isEmpty() && c.dateMinIncl().isEmpty()
        && c.minExcl().isEmpty() && c.dateMinExcl().isEmpty()
        && c.maxIncl().isEmpty() && c.dateMaxIncl().isEmpty()
        && c.maxExcl().isEmpty() && c.dateMaxExcl().isEmpty()
        && c.regex().isEmpty()
        && !(c.preserveIndent() && c.explicitOverrides().contains("preserveIndent"))
        && !(c.equatable().isPresent() && c.explicitOverrides().contains("equatable"))
        && !(c.comparable().isPresent() && c.explicitOverrides().contains("comparable"))
        && !(c.audited() && c.explicitOverrides().contains("audited"))
        && !(c.exact() && c.explicitOverrides().contains("exact"))
        && !(c.invertible() && c.explicitOverrides().contains("invertible"))
        && !(c.offset() && c.explicitOverrides().contains("offset"))
        && !(c.unsigned() && c.explicitOverrides().contains("unsigned"))
        && !(c.zoned() && c.explicitOverrides().contains("zoned"))
        && c.filterIncl().isEmpty() && c.filterExcl().isEmpty()
        && c.size().isEmpty() && c.minSize().isEmpty() && c.maxSize().isEmpty()
        && c.scale().isEmpty();
  }

  private void writeSchemaType(StvnParser.SchemaTypeContext node, CanonicalLayoutWriter layout, org.antlr.v4.runtime.ParserRuleContext lexicalContext) throws IOException {
    if (node.typeKeyword() != null) {
      String desugared = StvnCanonicalDefinitionsResolver.resolveCanonicalTypeKeyword(node.typeKeyword().getText(), lexicalContext);
      layout.writeLiteral(desugared);
    } else if (node.schemaConstructor() != null) {
      var ctor = node.schemaConstructor();
      if (ctor.atomicType() != null) {
        layout.writeLiteral(ctor.atomicType().getText());
      } else if (ctor.collectionType() != null) {
        var col = ctor.collectionType();
        layout.writeLiteral(resolveCollectionType(col));
        layout.openGroup("(");
        for (var st : col.schemaType()) {
          writeSchemaType(st, layout, lexicalContext);
        }
        layout.closeGroup(")");
      } else if (ctor.productType() != null) {
        var prod = ctor.productType();
        if (prod instanceof StvnParser.TupleTypeContext tt) {
          layout.writeLiteral(":Tuple");
          layout.openGroup("(");
          for (var st : tt.schemaType()) {
            writeSchemaType(st, layout, lexicalContext);
          }
          layout.closeGroup(")");
        }
      } else if (ctor.sumType() != null) {
        var sum = ctor.sumType();
        if (sum.KW_OPTION() != null) {
          layout.writeLiteral(":Option");
          layout.openGroup("(");
          writeSchemaType(sum.schemaType(0), layout, lexicalContext);
          layout.closeGroup(")");
        } else if (sum.KW_EITHER() != null) {
          layout.writeLiteral(":Either");
          layout.openGroup("(");
          writeSchemaType(sum.schemaType(0), layout, lexicalContext);
          writeSchemaType(sum.schemaType(1), layout, lexicalContext);
          layout.closeGroup(")");
        } else if (sum.KW_UNION() != null) {
          layout.writeLiteral(":Union");
          layout.openGroup("(");
          for (var st : sum.schemaType()) {
            writeSchemaType(st, layout, lexicalContext);
          }
          layout.closeGroup(")");
        } else if (sum.KW_ENUM() != null) {
          layout.writeLiteral(":Enum");
          layout.openGroup("[");
          for (var kw : sum.enumDef().valueKeyword()) {
            layout.writeLiteral(kw.getText());
          }
          layout.closeGroup("]");
        }
      }
    }
  }

  @SuppressWarnings("unused")
  private void writeValue(StvnValue val, CanonicalLayoutWriter layout) throws IOException {
    switch (val) {
      case StvnBoolean(var schema, var value) -> layout.writeBoolean(value, PrinterOptions.SymbolStyle.LONG_FORM);

      case StvnInteger(var schema, var value, var bitWidth, var isUnsigned) -> layout.writeInteger(value);

      case StvnFloat(var schema, var value, var precision) -> layout.writeFloat(value, precision);

      case StvnString(var schema, var value, var style, var fenceTag, var trait) -> {
        var preserveIndent = (schema != null && schema.constraints().preserveIndent());
        if (preserveIndent) {
          if (style == StringStyle.BLOCK) {
            layout.writeBlockString(value);
          } else if (style == StringStyle.FENCED) {
            var tag = fenceTag.orElse("FENCE");
            layout.writeLiteral("\"\"\"[" + tag + "]\n");
            layout.writeLiteral(value);
            layout.writeLiteral("[" + tag + "]\"\"\"");
          } else {
            layout.writeSimpleString(value);
          }
        } else {
          layout.writeSimpleString(style == StringStyle.SIMPLE
              ? value
              : value.stripIndent());
        }
      }

      case StvnTime(var schema, var value, var kind) -> {
        if (value instanceof String str) {
          layout.writeSimpleString(str);
        } else {
          layout.writeLiteral(value.toString());
        }
      }

      case StvnDateTimeOffset dto -> layout.writeSimpleString(dto.value().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME));
      case StvnDateTimeZoned dtz -> layout.writeSimpleString(dtz.localDateTime().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "[" + dtz.zoneId().getId() + "]");
      case StvnDateTimeAudited dta -> layout.writeSimpleString(dta.offsetDateTime().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "[" + dta.zoneId().getId() + "]");

      case StvnEnum(var schema, var keyword, var seqIndex, var varCount) -> layout.writeEnumKeyword(keyword);

      case StvnSeq(var schema, var elements, var isNonEmpty) -> {
        layout.openGroup("[");
        for (var el : elements) {
          writeValue(el, layout);
        }
        layout.closeGroup("]");
      }

      case StvnSet(var schema, var elements, var isNonEmpty) -> {
        layout.openGroup("[");
        for (var el : elements) {
          writeValue(el, layout);
        }
        layout.closeGroup("]");
      }

      case StvnTuple(var schema, var elements) -> {
        layout.openGroup("(");
        for (var el : elements) {
          writeValue(el, layout);
        }
        layout.closeGroup(")");
      }

      case StvnMap(var schema, var entries, var isNonEmpty, var isInvertible) -> {
        layout.openGroup("{");
        for (var entry : entries.entrySet()) {
          layout.openGroup("[");
          writeValue(entry.getKey(), layout);
          writeValue(entry.getValue(), layout);
          layout.closeGroup("]");
        }
        layout.closeGroup("}");
      }

      case StvnOption(var schema, var valueOpt, var trajectory) -> {
        var value = valueOpt.orElse(null);
        if (value != null) {
          boolean isNoneColliding = false;
          if (value instanceof StvnString strVal) {
            isNoneColliding = isControlKeyword(strVal.value());
          } else if (value instanceof StvnEnum enumVal) {
            isNoneColliding = isControlKeyword(enumVal.keyword());
          }

          if (isNoneColliding) {
            layout.openOptionSomeTag(PrinterOptions.SymbolStyle.LONG_FORM);
            writeValue(value, layout);
            layout.closeTag();
          } else {
            writeValue(value, layout);
          }
        } else {
          layout.writeOptionNone(PrinterOptions.SymbolStyle.LONG_FORM);
        }
      }

      case StvnEither(var schema, var value, var isRight, var isAmbiguous, var trajectory) -> {
        if (value != null) {
          boolean isEitherColliding = false;
          if (value instanceof StvnString strVal) {
            isEitherColliding = isControlKeyword(strVal.value());
          } else if (value instanceof StvnEnum enumVal) {
            isEitherColliding = isControlKeyword(enumVal.keyword());
          }

          if (isRight && !isEitherColliding) {
            writeValue(value, layout);
          } else {
            layout.openEitherTag(isRight, PrinterOptions.SymbolStyle.LONG_FORM);
            writeValue(value, layout);
            layout.closeTag();
          }
        } else {
          layout.openEitherTag(isRight, PrinterOptions.SymbolStyle.LONG_FORM);
          layout.closeTag();
        }
      }

      case StvnUnion(var schema, var value, var tagIndex) -> {
        writeValue(value, layout);
      }

      case StvnError err -> {
        layout.writeLiteral(err.rawText());
      }
    }
  }

  private static final java.util.Set<String> CONTROL_KEYWORD = java.util.Set.of(
      "#None", "#N",
      "#Some", "#S",
      "#Left", "#L",
      "#Right", "#R",
      "#TRUE", "#T",
      "#FALSE", "#F");

  private static boolean isControlKeyword(String s) {
    return CONTROL_KEYWORD.contains(s);
  }
}
