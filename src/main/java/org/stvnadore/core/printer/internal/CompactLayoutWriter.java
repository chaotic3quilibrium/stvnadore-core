package org.stvnadore.core.printer.internal;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.ir.StvnValue.FloatPrecision;
import org.stvnadore.core.printer.PrinterOptions;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * A layout writer that emits compact STVN tokens.
 * <p>
 * This layout writer formats STVN values into a stream without inserting unnecessary
 * whitespace (such as carriage returns, indentation spaces, or empty lines). It separates
 * adjacent literal tokens using exactly a single space.
 * </p>
 */
@NullMarked
public final class CompactLayoutWriter implements LayoutWriter {
  private final Writer writer;

  /**
   * Constructs a new {@code CompactLayoutWriter} wrapping the specified writer target.
   *
   * @param writer the destination character stream writer
   */
  public CompactLayoutWriter(Writer writer) {
    this.writer = writer;
  }

  @Override
  public void writeLiteral(String val) throws IOException {
    writer.write(val);
  }

  @Override
  public void writeBoolean(boolean val, PrinterOptions.SymbolStyle style) throws IOException {
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
    writer.write(val.toString());
  }

  @Override
  public void writeFloat(BigDecimal val, FloatPrecision precision) throws IOException {
    writer.write(formatFloat(val, precision));
  }

  @Override
  public void writeEnumKeyword(String keyword) throws IOException {
    writer.write(keyword);
  }

  @Override
  public void writeSimpleString(String s) throws IOException {
    writer.write("\"");
    writer.write(escapeString(s));
    writer.write("\"");
  }

  @Override
  public void writeBlockString(String s) throws IOException {
    writer.write("\"\"\"\n");
    writer.write(s);
    writer.write("\"\"\"");
  }

  @Override
  public void openGroup(String delimiter) throws IOException {
    writer.write(delimiter);
  }

  @Override
  public void closeGroup(String delimiter) throws IOException {
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

  @SuppressWarnings("RedundantThrows")
  @Override
  public void newline() throws IOException {
    // no-op
  }

  @Override
  public void indent() {
    // no-op
  }

  @Override
  public void outdent() {
    // no-op
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
