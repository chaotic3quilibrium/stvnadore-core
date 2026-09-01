package org.stvnadore.core.printer.internal;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.ir.StvnValue.*;
import org.stvnadore.core.printer.PrinterOptions;

import java.io.IOException;
import java.util.Set;

/**
 * Dispatcher mapping STVN IR AST values to their corresponding layout writer serialization methods.
 *
 * @since 1.0.0
 */
public final class PatternPrinterDispatcher {

  private PatternPrinterDispatcher() {
    // Utility/Facade class
  }

  /**
   * Dispatches the printing of a {@link StvnValue} to the provided {@link LayoutWriter} based on its AST type.
   *
   * @param val     the value to print
   * @param layout  the layout writer to write to
   * @param options the formatting printer options
   * @throws IOException if a writing exception occurs
   */
  public static void dispatch(StvnValue val, LayoutWriter layout, PrinterOptions options) throws IOException {
    switch (val) {
      case StvnBoolean(var schema, var value) -> layout.writeBoolean(value, options.symbolStyle());

      case StvnInteger(var schema, var value, var bitWidth, var isUnsigned) -> layout.writeInteger(value);

      case StvnFloat(var schema, var value, var precision) -> layout.writeFloat(value, precision);

      case StvnString(var schema, var value, var style, var fenceTag, var trait) -> {
        var preserveIndent = (schema != null && schema.constraints().preserveIndent());
        var text = (preserveIndent || style == StringStyle.SIMPLE) ? value : value.stripIndent();
        switch (style) {
          case SIMPLE -> layout.writeSimpleString(text);
          case BLOCK -> layout.writeBlockString(text);
          case FENCED -> {
            var tag = fenceTag.orElse("FENCE");
            layout.writeLiteral("\"\"\"->[" + tag + "]\n");
            layout.writeLiteral(text);
            layout.writeLiteral("[" + tag + "]\"\"\"");
          }
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
        var first = true;
        for (var el : elements) {
          if (!first) {
            layout.appendSeparator();
          }
          first = false;
          dispatch(el, layout, options);
        }
        layout.closeGroup("]");
      }

      case StvnSet(var schema, var elements, var isNonEmpty) -> {
        layout.openGroup("[");
        var first = true;
        for (var el : elements) {
          if (!first) {
            layout.appendSeparator();
          }
          first = false;
          dispatch(el, layout, options);
        }
        layout.closeGroup("]");
      }

      case StvnTuple(var schema, var elements) -> {
        layout.openGroup("(");
        var first = true;
        for (var el : elements) {
          if (!first) {
            layout.appendSeparator();
          }
          first = false;
          dispatch(el, layout, options);
        }
        layout.closeGroup(")");
      }

      case StvnMap(var schema, var entries, var isNonEmpty, var isInvertible) -> {
        layout.openGroup("{");
        var pretty = layout instanceof PrettyLayoutWriter;
        if (pretty) {
          layout.newline();
          layout.indent();
        }
        var first = true;
        for (var entry : entries.entrySet()) {
          if (!first) {
            if (pretty) {
              layout.newline();
            } else {
              layout.appendSeparator();
            }
          }
          first = false;
          layout.openGroup("[");
          dispatch(entry.getKey(), layout, options);
          layout.appendSeparator();
          dispatch(entry.getValue(), layout, options);
          layout.closeGroup("]");
        }
        if (pretty) {
          layout.outdent();
          layout.newline();
        }
        layout.closeGroup("}");
      }

      case StvnOption(var schema, var valueOpt, var trajectory) -> {
        var value = valueOpt.orElse(null);
        var isSome = (value != null);
        var happyPath = (options.sumTypePolicy() == PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED && isSome);

        boolean isNoneColliding = false;
        if (value instanceof StvnString strVal) {
          isNoneColliding = isControlKeyword(strVal.value());
        } else if (value instanceof StvnEnum enumVal) {
          isNoneColliding = isControlKeyword(enumVal.keyword());
        }

        if (happyPath && isNoneColliding) {
          happyPath = false;
        }

        if (happyPath) {
          dispatch(value, layout, options);
        } else {
          if (isSome) {
            layout.openOptionSomeTag(options.symbolStyle());
            dispatch(value, layout, options);
            layout.closeTag();
          } else {
            layout.writeOptionNone(options.symbolStyle());
          }
        }
      }

      case StvnEither(var schema, var value, var isRight, var isAmbiguous, var trajectory) -> {
        var isSome = (value != null);
        var happyPath = (options.sumTypePolicy() == PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED && isRight && isSome);

        boolean isEitherColliding = false;
        if (value instanceof StvnString strVal) {
          isEitherColliding = isControlKeyword(strVal.value());
        } else if (value instanceof StvnEnum enumVal) {
          isEitherColliding = isControlKeyword(enumVal.keyword());
        }

        if (happyPath && isEitherColliding) {
          happyPath = false;
        }

        if (happyPath) {
          dispatch(value, layout, options);
        } else {
          layout.openEitherTag(isRight, options.symbolStyle());
          if (isSome) {
            dispatch(value, layout, options);
          }
          layout.closeTag();
        }
      }

      case StvnUnion(var schema, var value, var tagIndex) -> {
        dispatch(value, layout, options);
      }

      case StvnError err -> {
        layout.writeLiteral(err.rawText());
      }
    }
  }

  private static final Set<String> CONTROL_KEYWORD = Set.of(
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
