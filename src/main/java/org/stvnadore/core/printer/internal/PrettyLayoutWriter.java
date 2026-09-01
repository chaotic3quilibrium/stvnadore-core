package org.stvnadore.core.printer.internal;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.ir.StvnValue.FloatPrecision;
import org.stvnadore.core.printer.PrinterOptions;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * A layout writer that emits structured, indented pretty STVN tokens.
 * <p>
 * This layout writer tracks indentation state and prefixes newlines with
 * appropriate spacing. It organizes complex structures like sequences, maps,
 * and defs into multi-line blocks for human readability.
 * </p>
 */
@NullMarked
public final class PrettyLayoutWriter implements LayoutWriter {
  private final Writer writer;
  private final int indentStep;
  private int indentLevel = 0;
  private boolean pendingIndent = false;

  /**
   * Constructs a new {@code PrettyLayoutWriter} with the specified target writer
   * and indentation space step size.
   *
   * @param writer     the destination character stream writer
   * @param indentStep the number of spaces per indentation level
   */
  public PrettyLayoutWriter(Writer writer, int indentStep) {
    this.writer = writer;
    this.indentStep = indentStep;
  }

  private void writeIndentIfNeeded() throws IOException {
    if (pendingIndent) {
      pendingIndent = false;
      var spaces = " ".repeat(indentLevel * indentStep);
      writer.write(spaces);
    }
  }

  @Override
  public void writeLiteral(String val) throws IOException {
    writeIndentIfNeeded();
    writer.write(val);
  }

  @Override
  public void writeBoolean(boolean val, PrinterOptions.SymbolStyle style) throws IOException {
    writeIndentIfNeeded();
    if (style == PrinterOptions.SymbolStyle.LONG_FORM) {
      writer.write(val
          ? "#TRUE"
          : "#FALSE");
    } else {
      writer.write(val
          ? "#T"
          : "#F");
    }
  }

  @Override
  public void writeInteger(BigInteger val) throws IOException {
    writeIndentIfNeeded();
    writer.write(val.toString());
  }

  @Override
  public void writeFloat(BigDecimal val, FloatPrecision precision) throws IOException {
    writeIndentIfNeeded();
    writer.write(formatFloat(val, precision));
  }

  @Override
  public void writeEnumKeyword(String keyword) throws IOException {
    writeIndentIfNeeded();
    writer.write(keyword);
  }

  @Override
  public void writeSimpleString(String s) throws IOException {
    writeIndentIfNeeded();
    writer.write("\"");
    writer.write(escapeString(s));
    writer.write("\"");
  }

  @Override
  public void writeBlockString(String s) throws IOException {
    writeIndentIfNeeded();
    writer.write("\"\"\"\n");
    writer.write(s);
    writer.write("\"\"\"");
  }

  @Override
  public void openGroup(String delimiter) throws IOException {
    writeIndentIfNeeded();
    writer.write(delimiter);
  }

  @Override
  public void closeGroup(String delimiter) throws IOException {
    writeIndentIfNeeded();
    writer.write(delimiter);
  }

  @Override
  public void openOptionSomeTag(PrinterOptions.SymbolStyle style) throws IOException {
    openTag(style == PrinterOptions.SymbolStyle.LONG_FORM
        ? "#Some"
        : "#S");
  }

  @Override
  public void writeOptionNone(PrinterOptions.SymbolStyle style) throws IOException {
    writeEnumKeyword(style == PrinterOptions.SymbolStyle.LONG_FORM
        ? "#None"
        : "#N");
  }

  @Override
  public void openEitherTag(boolean isRight, PrinterOptions.SymbolStyle style) throws IOException {
    if (isRight) {
      openTag(style == PrinterOptions.SymbolStyle.LONG_FORM
          ? "#Right"
          : "#R");
    } else {
      openTag(style == PrinterOptions.SymbolStyle.LONG_FORM
          ? "#Left"
          : "#L");
    }
  }

  @Override
  public void openTag(String tag) throws IOException {
    writeIndentIfNeeded();
    writer.write(tag);
    writer.write(" ");
  }

  @Override
  @SuppressWarnings("RedundantThrows")
  public void closeTag() throws IOException {
    // no-op
  }

  @Override
  public void appendSeparator() throws IOException {
    writer.write(" ");
  }

  @Override
  public void newline() throws IOException {
    writer.write("\n");
    pendingIndent = true;
  }

  @Override
  public void indent() {
    indentLevel++;
  }

  @Override
  public void outdent() {
    if (indentLevel > 0) {
      indentLevel--;
    }
  }

  @Override
  public void flush() throws IOException {
    writer.flush();
  }

  private String escapeString(String s) {
    return s.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }
}
