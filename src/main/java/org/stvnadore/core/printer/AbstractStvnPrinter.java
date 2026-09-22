package org.stvnadore.core.printer;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.parser.StvnParser;
import org.stvnadore.core.printer.internal.LayoutWriter;
import org.stvnadore.core.printer.internal.PatternPrinterDispatcher;
import org.stvnadore.core.printer.internal.PrettyLayoutWriter;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import org.stvnadore.core.io.StvnCanonicalDefinitionsResolver;
import org.stvnadore.core.io.StvnCanonicalDefinitionsResolver.ResolvedCanonicalDefinition;

/**
 * Abstract base class for STVN printers, orchestrating formatting and serialization loops.
 * <p>
 * This class provides standard implementation logic for printing the {@code :defs},
 * {@code :type}, and {@code :body} blocks of an STVN document. It coordinates the printing options,
 * constraint visualization, and delegates layout-specific operations to a subclass-provided
 * {@link LayoutWriter}.
 * </p>
 */
@NullMarked
public abstract class AbstractStvnPrinter implements StvnTextPrinter {
  /**
   * The formatting options applied to this printer.
   */
  protected final PrinterOptions options;

  /**
   * Constructs an {@code AbstractStvnPrinter} with the specified printer options.
   *
   * @param options the formatting options to use
   */
  protected AbstractStvnPrinter(PrinterOptions options) {
    this.options = options;
  }

  /**
   * Constructs an {@code AbstractStvnPrinter} using default printer options.
   */
  protected AbstractStvnPrinter() {
    this(new PrinterOptions());
  }

  /**
   * Factory method to create a concrete {@link LayoutWriter} instance.
   *
   * @param target the target writer stream
   * @return the resolved layout writer for the target stream
   */
  protected abstract LayoutWriter createLayoutWriter(Writer target);

  @Override
  public void print(StvnValue value, Writer target) throws IOException {
    var layout = createLayoutWriter(target);
    if (options.coverage() == PrinterOptions.Coverage.BODY_ONLY) {
      PatternPrinterDispatcher.dispatch(value, layout, options);
    } else {
      layout.openGroup("{");
      var pretty = layout instanceof PrettyLayoutWriter;
      if (pretty) {
        layout.newline();
        layout.indent();
      }

      var schema = value.schema();
      List<ResolvedCanonicalDefinition> defs = StvnCanonicalDefinitionsResolver.resolveDefinitions(value);
      if (!defs.isEmpty()) {
        layout.writeLiteral(":defs");
        layout.appendSeparator();
        layout.openGroup("{");

        var firstDef = true;
        for (var s : defs) {
          if (!firstDef) {
            if (pretty) {
              layout.newline();
            } else {
              layout.appendSeparator();
            }
          }
          if (firstDef && pretty) {
            layout.newline();
            layout.indent();
          }
          firstDef = false;

          layout.writeLiteral(s.canonicalName());

          var constraints = s.constraints();
          if (!isConstraintsEmpty(constraints)) {
            var count = countConstraints(constraints);
            var inline = (count <= 1) || !pretty;

            layout.appendSeparator();
            layout.openGroup("{");
            if (!inline) {
              layout.indent();
            }

            var firstC = true;

            // Tier 1: Flags & Intrinsic Modes
            if (constraints.unsigned()) {
              if (inline) {
                if (!firstC) layout.appendSeparator();
              } else {
                layout.newline();
              }
              firstC = false;
              layout.writeLiteral("#unsigned");
            }
            if (constraints.exact()) {
              if (inline) {
                if (!firstC) layout.appendSeparator();
              } else {
                layout.newline();
              }
              firstC = false;
              layout.writeLiteral("#exact");
            }
            if (constraints.invertible()) {
              if (inline) {
                if (!firstC) layout.appendSeparator();
              } else {
                layout.newline();
              }
              firstC = false;
              layout.writeLiteral("#invertible");
            }
            if (constraints.preserveIndent() && constraints.explicitOverrides().contains("preserveIndent")) {
              if (inline) {
                if (!firstC) layout.appendSeparator();
              } else {
                layout.newline();
              }
              firstC = false;
              layout.writeLiteral("#preserveIndent");
              layout.appendSeparator();
              layout.writeBoolean(true, options.symbolStyle());
            }
            if (constraints.offset()) {
              if (inline) {
                if (!firstC) layout.appendSeparator();
              } else {
                layout.newline();
              }
              firstC = false;
              layout.writeLiteral("#offset");
            }
            if (constraints.zoned()) {
              if (inline) {
                if (!firstC) layout.appendSeparator();
              } else {
                layout.newline();
              }
              firstC = false;
              layout.writeLiteral("#zoned");
            }
            if (constraints.audited()) {
              if (inline) {
                if (!firstC) layout.appendSeparator();
              } else {
                layout.newline();
              }
              firstC = false;
              layout.writeLiteral("#audited");
            }
            var equatable = constraints.equatable().orElse(null);
            if (equatable != null && constraints.explicitOverrides().contains("equatable")) {
              if (inline) {
                if (!firstC) layout.appendSeparator();
              } else {
                layout.newline();
              }
              firstC = false;
              layout.writeLiteral("#equatable");
              layout.appendSeparator();
              layout.writeBoolean(equatable, options.symbolStyle());
            }
            var comparable = constraints.comparable().orElse(null);
            if (comparable != null && constraints.explicitOverrides().contains("comparable")) {
              if (inline) {
                if (!firstC) layout.appendSeparator();
              } else {
                layout.newline();
              }
              firstC = false;
              layout.writeLiteral("#comparable");
              layout.appendSeparator();
              layout.writeBoolean(comparable, options.symbolStyle());
            }

            // Tier 2: Temporal Scale
            var scale = constraints.scale().orElse(null);
            if (scale != null) {
              if (inline) {
                if (!firstC) layout.appendSeparator();
              } else {
                layout.newline();
              }
              firstC = false;
              String scaleKw = scale.startsWith("#") ? scale : "#" + scale;
              layout.writeLiteral(scaleKw);
            }

            // Tier 3: Dimensions & Capacity
            var size = constraints.size().orElse(null);
            if (size != null) {
              if (inline) {
                if (!firstC) layout.appendSeparator();
              } else {
                layout.newline();
              }
              firstC = false;
              layout.writeLiteral("#size");
              layout.appendSeparator();
              layout.writeLiteral(size.toString());
            }
            var minSize = constraints.minSize().orElse(null);
            if (minSize != null) {
              if (inline) {
                if (!firstC) layout.appendSeparator();
              } else {
                layout.newline();
              }
              firstC = false;
              layout.writeLiteral("#minSize");
              layout.appendSeparator();
              layout.writeLiteral(minSize.toString());
            }
            var maxSize = constraints.maxSize().orElse(null);
            if (maxSize != null) {
              if (inline) {
                if (!firstC) layout.appendSeparator();
              } else {
                layout.newline();
              }
              firstC = false;
              layout.writeLiteral("#maxSize");
              layout.appendSeparator();
              layout.writeLiteral(maxSize.toString());
            }

            // Tier 4: Value Intervals (Lower before Upper)
            var minIncl = constraints.minIncl().orElse(null);
            if (minIncl != null) {
              if (inline) {
                if (!firstC) layout.appendSeparator();
              } else {
                layout.newline();
              }
              firstC = false;
              layout.writeLiteral("#minIncl");
              layout.appendSeparator();
              layout.writeLiteral(minIncl.toString());
            } else if (constraints.dateMinIncl().isPresent()) {
              if (inline) {
                if (!firstC) layout.appendSeparator();
              } else {
                layout.newline();
              }
              firstC = false;
              layout.writeLiteral("#minIncl");
              layout.appendSeparator();
              layout.writeSimpleString(constraints.dateMinIncl().get());
            }
            var minExcl = constraints.minExcl().orElse(null);
            if (minExcl != null) {
              if (inline) {
                if (!firstC) layout.appendSeparator();
              } else {
                layout.newline();
              }
              firstC = false;
              layout.writeLiteral("#minExcl");
              layout.appendSeparator();
              layout.writeLiteral(minExcl.toString());
            } else if (constraints.dateMinExcl().isPresent()) {
              if (inline) {
                if (!firstC) layout.appendSeparator();
              } else {
                layout.newline();
              }
              firstC = false;
              layout.writeLiteral("#minExcl");
              layout.appendSeparator();
              layout.writeSimpleString(constraints.dateMinExcl().get());
            }
            var maxExcl = constraints.maxExcl().orElse(null);
            if (maxExcl != null) {
              if (inline) {
                if (!firstC) layout.appendSeparator();
              } else {
                layout.newline();
              }
              firstC = false;
              layout.writeLiteral("#maxExcl");
              layout.appendSeparator();
              layout.writeLiteral(maxExcl.toString());
            } else if (constraints.dateMaxExcl().isPresent()) {
              if (inline) {
                if (!firstC) layout.appendSeparator();
              } else {
                layout.newline();
              }
              firstC = false;
              layout.writeLiteral("#maxExcl");
              layout.appendSeparator();
              layout.writeSimpleString(constraints.dateMaxExcl().get());
            }
            var maxIncl = constraints.maxIncl().orElse(null);
            if (maxIncl != null) {
              if (inline) {
                if (!firstC) layout.appendSeparator();
              } else {
                layout.newline();
              }
              firstC = false;
              layout.writeLiteral("#maxIncl");
              layout.appendSeparator();
              layout.writeLiteral(maxIncl.toString());
            } else if (constraints.dateMaxIncl().isPresent()) {
              if (inline) {
                if (!firstC) layout.appendSeparator();
              } else {
                layout.newline();
              }
              firstC = false;
              layout.writeLiteral("#maxIncl");
              layout.appendSeparator();
              layout.writeSimpleString(constraints.dateMaxIncl().get());
            }

            // Tier 5: Pattern & Text Structure
            var regex = constraints.regex().orElse(null);
            if (regex != null) {
              if (inline) {
                if (!firstC) layout.appendSeparator();
              } else {
                layout.newline();
              }
              firstC = false;
              layout.writeLiteral("#regex");
              layout.appendSeparator();
              layout.writeSimpleString(regex);
            }

            // Tier 6: Set & Variant Membership
            var filterIncl = constraints.filterIncl().orElse(null);
            if (filterIncl != null) {
              if (inline) {
                if (!firstC) layout.appendSeparator();
              } else {
                layout.newline();
              }
              firstC = false;
              layout.writeLiteral("#filterIncl");
              layout.appendSeparator();
              layout.openGroup("[");
              var firstV = true;
              for (String v : filterIncl) {
                if (!firstV) layout.appendSeparator();
                firstV = false;
                layout.writeLiteral(v);
              }
              layout.closeGroup("]");
            }
            var filterExcl = constraints.filterExcl().orElse(null);
            if (filterExcl != null) {
              if (inline) {
                if (!firstC) layout.appendSeparator();
              } else {
                layout.newline();
              }
              firstC = false;
              layout.writeLiteral("#filterExcl");
              layout.appendSeparator();
              layout.openGroup("[");
              var firstV = true;
              for (String v : filterExcl) {
                if (!firstV) layout.appendSeparator();
                firstV = false;
                layout.writeLiteral(v);
              }
              layout.closeGroup("]");
            }

            if (!inline) {
              layout.outdent();
              layout.newline();
            }
            layout.closeGroup("}");
          }

          layout.appendSeparator();
          writeSchemaType(s.schemaNode(), layout, s.lexicalContext());
        }

        if (pretty) {
          layout.outdent();
          layout.newline();
        }
        layout.closeGroup("}");

        if (pretty) {
          layout.newline();
        } else {
          layout.appendSeparator();
        }
      }

      layout.writeLiteral(":type");
      layout.appendSeparator();
      if (schema != null) {
        var alias = schema.aliasName().orElse(null);
        if (alias != null) {
          layout.writeLiteral(alias);
        } else {
          writeSchemaType(schema.node(), layout, schema.node());
        }
      } else {
        throw new org.stvnadore.core.binary.exceptions.StvnSerializationException("Missing schema context");
      }

      if (pretty) {
        layout.newline();
      } else {
        layout.appendSeparator();
      }

      layout.writeLiteral(":body");
      layout.appendSeparator();
      PatternPrinterDispatcher.dispatch(value, layout, options);

      if (pretty) {
        layout.outdent();
        layout.newline();
      }
      layout.closeGroup("}");
    }
    layout.flush();
  }

  private boolean isConstraintsEmpty(org.stvnadore.core.validation.StvnTypeResolver.StvnConstraints c) {
    var hasMinIncl = c.minIncl().isPresent() || c.dateMinIncl().isPresent();
    var hasMinExcl = c.minExcl().isPresent() || c.dateMinExcl().isPresent();
    var hasMaxIncl = c.maxIncl().isPresent() || c.dateMaxIncl().isPresent();
    var hasMaxExcl = c.maxExcl().isPresent() || c.dateMaxExcl().isPresent();
    var hasRegex = c.regex().isPresent();
    var hasPreserveIndent = c.preserveIndent() && c.explicitOverrides().contains("preserveIndent");
    var hasEquatable = c.equatable().isPresent() && c.explicitOverrides().contains("equatable");
    var hasComparable = c.comparable().isPresent() && c.explicitOverrides().contains("comparable");

    return !hasMinIncl && !hasMinExcl && !hasMaxIncl && !hasMaxExcl
        && !hasRegex && !hasPreserveIndent && !hasEquatable && !hasComparable
        && c.filterIncl().isEmpty() && c.filterExcl().isEmpty()
        && c.size().isEmpty() && !c.unsigned() && !c.exact()
        && c.minSize().isEmpty() && c.maxSize().isEmpty() && !c.invertible()
        && c.scale().isEmpty() && !c.offset() && !c.zoned() && !c.audited();
  }

  private void writeSchemaType(StvnParser.SchemaTypeContext node, LayoutWriter layout, org.antlr.v4.runtime.ParserRuleContext lexicalContext) throws IOException {
    if (node.typeKeyword() != null) {
      String desugared = StvnCanonicalDefinitionsResolver.resolveCanonicalTypeKeyword(node.typeKeyword().getText(), lexicalContext);
      layout.writeLiteral(desugared);
    } else if (node.schemaConstructor() != null) {
      var ctor = node.schemaConstructor();
      if (ctor.atomicType() != null) {
        layout.writeLiteral(ctor.atomicType().getText());
      } else if (ctor.collectionType() != null) {
        var col = ctor.collectionType();
        layout.writeLiteral(resolveCollectionType(ctor.collectionType()));
        layout.openGroup("(");
        var first = true;
        for (var st : col.schemaType()) {
          if (!first) layout.appendSeparator();
          first = false;
          writeSchemaType(st, layout, lexicalContext);
        }
        layout.closeGroup(")");
      } else if (ctor.productType() != null) {
        var prod = ctor.productType();
        if (prod instanceof StvnParser.TupleTypeContext tt) {
          layout.writeLiteral(":Tuple");
          layout.openGroup("(");
          var first = true;
          for (var st : tt.schemaType()) {
            if (!first) layout.appendSeparator();
            first = false;
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
          layout.appendSeparator();
          writeSchemaType(sum.schemaType(1), layout, lexicalContext);
          layout.closeGroup(")");
        } else if (sum.KW_UNION() != null) {
          layout.writeLiteral(":Union");
          layout.openGroup("(");
          var first = true;
          for (var st : sum.schemaType()) {
            if (!first) layout.appendSeparator();
            first = false;
            writeSchemaType(st, layout, lexicalContext);
          }
          layout.closeGroup(")");
        } else if (sum.KW_ENUM() != null) {
          layout.writeLiteral(":Enum");
          layout.openGroup("[");
          var first = true;
          for (var kw : sum.enumDef().valueKeyword()) {
            if (!first) layout.appendSeparator();
            first = false;
            layout.writeLiteral(kw.getText());
          }
          layout.closeGroup("]");
        }
      }
    }
  }

  private int countConstraints(org.stvnadore.core.validation.StvnTypeResolver.StvnConstraints c) {
    var count = 0;
    if (c.minIncl().isPresent() || c.dateMinIncl().isPresent()) count++;
    if (c.minExcl().isPresent() || c.dateMinExcl().isPresent()) count++;
    if (c.maxIncl().isPresent() || c.dateMaxIncl().isPresent()) count++;
    if (c.maxExcl().isPresent() || c.dateMaxExcl().isPresent()) count++;
    if (c.regex().isPresent()) count++;
    if (c.preserveIndent() && c.explicitOverrides().contains("preserveIndent")) count++;
    if (c.equatable().isPresent() && c.explicitOverrides().contains("equatable")) count++;
    if (c.comparable().isPresent() && c.explicitOverrides().contains("comparable")) count++;
    if (c.filterIncl().isPresent()) count++;
    if (c.filterExcl().isPresent()) count++;
    if (c.unsigned()) count++;
    if (c.exact()) count++;
    if (c.size().isPresent()) count++;
    if (c.minSize().isPresent()) count++;
    if (c.maxSize().isPresent()) count++;
    if (c.invertible()) count++;
    if (c.scale().isPresent()) count++;
    if (c.offset()) count++;
    if (c.zoned()) count++;
    if (c.audited()) count++;
    return count;
  }
}
