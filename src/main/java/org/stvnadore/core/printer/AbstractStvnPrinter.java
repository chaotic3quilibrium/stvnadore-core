package org.stvnadore.core.printer;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.parser.StvnParser;
import org.stvnadore.core.printer.internal.LayoutWriter;
import org.stvnadore.core.printer.internal.PatternPrinterDispatcher;
import org.stvnadore.core.printer.internal.PrettyLayoutWriter;

import java.io.IOException;
import java.io.Writer;

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
      String mainAlias = schema != null ? schema.aliasName().orElse(null) : null;
      if (schema != null && mainAlias != null) {
        layout.writeLiteral(":defs");
        layout.appendSeparator();
        layout.openGroup("{");

        var current = schema;
        var chain = new java.util.ArrayList<org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema>();
        while (current != null) {
          var alias = current.aliasName().orElse(null);
          if (alias == null) break;
          chain.addFirst(current);
          current = current.underlyingSchema().orElse(null);
        }

        var firstDef = true;
        for (var s : chain) {
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

          layout.writeLiteral(s.aliasName().orElseThrow());

          var constraints = s.localConstraints().orElse(s.constraints());
          if (!isConstraintsEmpty(constraints)) {
            var count = countConstraints(constraints);
            var inline = (count <= 1) || !pretty;

            layout.appendSeparator();
            layout.openGroup("{");
            if (!inline) {
              layout.indent();
            }

            var firstC = true;
            
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
            }
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

            if (!inline) {
              layout.outdent();
              layout.newline();
            }
            layout.closeGroup("}");
          }

          layout.appendSeparator();
          var underlyingAlias = s.underlyingSchema().flatMap(org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema::aliasName).orElse(null);
          if (underlyingAlias != null) {
            layout.writeLiteral(underlyingAlias);
          } else {
            writeSchemaType(s.node(), layout);
          }
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
          writeSchemaType(schema.node(), layout);
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

  private void writeSchemaType(StvnParser.SchemaTypeContext node, LayoutWriter layout) throws IOException {
    if (node.typeKeyword() != null) {
      layout.writeLiteral(node.typeKeyword().getText());
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
          writeSchemaType(st, layout);
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
          layout.appendSeparator();
          writeSchemaType(sum.schemaType(1), layout);
          layout.closeGroup(")");
        } else if (sum.KW_UNION() != null) {
          layout.writeLiteral(":Union");
          layout.openGroup("(");
          var first = true;
          for (var st : sum.schemaType()) {
            if (!first) layout.appendSeparator();
            first = false;
            writeSchemaType(st, layout);
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
    if (c.minIncl().isPresent()) count++;
    if (c.minExcl().isPresent()) count++;
    if (c.maxIncl().isPresent()) count++;
    if (c.maxExcl().isPresent()) count++;
    if (c.regex().isPresent()) count++;
    if (c.preserveIndent() && c.explicitOverrides().contains("preserveIndent")) count++;
    if (c.equatable().isPresent() && c.explicitOverrides().contains("equatable")) count++;
    if (c.comparable().isPresent() && c.explicitOverrides().contains("comparable")) count++;
    return count;
  }
}
