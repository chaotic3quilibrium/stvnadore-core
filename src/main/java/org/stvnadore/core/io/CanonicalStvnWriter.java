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
 *       section are serialized in a strict lexicographical ordering (e.g., {@code #comparable},
 *       {@code #equatable}, {@code #maxExcl}, {@code #maxIncl}, {@code #minExcl}, {@code #minIncl},
 *       {@code #preserveIndent}, {@code #regex}) regardless of their definition order.</li>
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
    String mainAlias = schema != null ? schema.aliasName().orElse(null) : null;
    if (schema != null && mainAlias != null) {
      layout.writeLiteral(":defs");
      layout.openGroup("{");

      var current = schema;
      var chain = new ArrayList<ResolvedSchema>();
      while (current != null) {
        var alias = current.aliasName().orElse(null);
        if (alias == null) break;
        chain.addFirst(current);
        current = current.underlyingSchema().orElse(null);
      }

      for (var s : chain) {
        layout.writeLiteral(s.aliasName().orElseThrow());

        var constraints = s.localConstraints().orElse(s.constraints());
        if (!isConstraintsEmpty(constraints)) {
          layout.openGroup("{");

          var comparable = constraints.comparable().orElse(null);
          if (comparable != null && constraints.explicitOverrides().contains("comparable")) {
            layout.writeLiteral("#comparable");
            layout.writeBoolean(comparable, PrinterOptions.SymbolStyle.LONG_FORM);
          }
          var equatable = constraints.equatable().orElse(null);
          if (equatable != null && constraints.explicitOverrides().contains("equatable")) {
            layout.writeLiteral("#equatable");
            layout.writeBoolean(equatable, PrinterOptions.SymbolStyle.LONG_FORM);
          }
          var maxExcl = constraints.maxExcl().orElse(null);
          if (maxExcl != null) {
            layout.writeLiteral("#maxExcl");
            layout.writeLiteral(maxExcl.toString());
          }
          var maxIncl = constraints.maxIncl().orElse(null);
          if (maxIncl != null) {
            layout.writeLiteral("#maxIncl");
            layout.writeLiteral(maxIncl.toString());
          }
          var minExcl = constraints.minExcl().orElse(null);
          if (minExcl != null) {
            layout.writeLiteral("#minExcl");
            layout.writeLiteral(minExcl.toString());
          }
          var minIncl = constraints.minIncl().orElse(null);
          if (minIncl != null) {
            layout.writeLiteral("#minIncl");
            layout.writeLiteral(minIncl.toString());
          }
          if (constraints.preserveIndent() && constraints.explicitOverrides().contains("preserveIndent")) {
            layout.writeLiteral("#preserveIndent");
            layout.writeBoolean(true, PrinterOptions.SymbolStyle.LONG_FORM);
          }
          var regex = constraints.regex().orElse(null);
          if (regex != null) {
            layout.writeLiteral("#regex");
            layout.writeSimpleString(regex);
          }

          layout.closeGroup("}");
        }

        var underlyingAlias = s.underlyingSchema().flatMap(ResolvedSchema::aliasName).orElse(null);
        if (underlyingAlias != null) {
          layout.writeLiteral(underlyingAlias);
        } else {
          writeSchemaType(s.node(), layout);
        }
      }

      layout.closeGroup("}");
    }

    layout.writeLiteral(":type");
    if (schema != null) {
      var alias = schema.aliasName().orElse(null);
      if (alias != null) {
        layout.writeLiteral(alias);
      } else {
        writeSchemaType(schema.node(), layout);
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
    var hasMinIncl = c.minIncl().isPresent();
    var hasMinExcl = c.minExcl().isPresent();
    var hasMaxIncl = c.maxIncl().isPresent();
    var hasMaxExcl = c.maxExcl().isPresent();
    var hasRegex = c.regex().isPresent();
    var hasPreserveIndent = c.preserveIndent() && c.explicitOverrides().contains("preserveIndent");
    var hasEquatable = c.equatable().isPresent() && c.explicitOverrides().contains("equatable");
    var hasComparable = c.comparable().isPresent() && c.explicitOverrides().contains("comparable");

    return !hasMinIncl && !hasMinExcl && !hasMaxIncl && !hasMaxExcl
        && !hasRegex && !hasPreserveIndent && !hasEquatable && !hasComparable;
  }

  private void writeSchemaType(StvnParser.SchemaTypeContext node, CanonicalLayoutWriter layout) throws IOException {
    if (node.typeKeyword() != null) {
      layout.writeLiteral(node.typeKeyword().getText());
    } else if (node.schemaConstructor() != null) {
      var ctor = node.schemaConstructor();
      if (ctor.atomicType() != null) {
        layout.writeLiteral(ctor.atomicType().getText());
      } else if (ctor.collectionType() != null) {
        var col = ctor.collectionType();
        layout.writeLiteral(resolveCollectionType(col));
        layout.openGroup("(");
        for (var st : col.schemaType()) {
          writeSchemaType(st, layout);
        }
        layout.closeGroup(")");
      } else if (ctor.productType() != null) {
        var prod = ctor.productType();
        if (prod instanceof StvnParser.TupleTypeContext tt) {
          layout.writeLiteral(":Tuple");
          layout.openGroup("(");
          for (var st : tt.schemaType()) {
            writeSchemaType(st, layout);
          }
          layout.closeGroup(")");
        }
      } else if (ctor.sumType() != null) {
        var sum = ctor.sumType();
        if (sum.KW_OPTION() != null) {
          layout.writeLiteral(":Option");
          layout.openGroup("(");
          writeSchemaType(sum.schemaType(0), layout);
          layout.closeGroup(")");
        } else if (sum.KW_EITHER() != null) {
          layout.writeLiteral(":Either");
          layout.openGroup("(");
          writeSchemaType(sum.schemaType(0), layout);
          writeSchemaType(sum.schemaType(1), layout);
          layout.closeGroup(")");
        } else if (sum.KW_UNION() != null) {
          layout.writeLiteral(":Union");
          layout.openGroup("(");
          for (var st : sum.schemaType()) {
            writeSchemaType(st, layout);
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
            layout.writeLiteral("\"\"\"->[" + tag + "]\n");
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
