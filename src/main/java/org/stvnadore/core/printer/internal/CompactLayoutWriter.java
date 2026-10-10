package org.stvnadore.core.printer.internal;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
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
 * adjacent literal tokens using exactly a single space while eliminating bracket padding.
 * </p>
 */
@NullMarked
public final class CompactLayoutWriter implements LayoutWriter {
  private final Writer writer;
  private String lastToken = "";

  /**
   * Constructs a new {@code CompactLayoutWriter} wrapping the specified writer target.
   *
   * @param writer the destination character stream writer
   */
  public CompactLayoutWriter(Writer writer) {
    this.writer = writer;
  }

  private void writeToken(String token) throws IOException {
    if (token.isEmpty()) {
      return;
    }
    if (!lastToken.isEmpty()) {
      boolean omitSpace = isPunctuation(lastToken) || isPunctuation(token)
          || (token.equals(StvnVocabulary.KEYWORD_BODY) && lastToken.startsWith(":"));
      if (!omitSpace) {
        if (!lastToken.endsWith("\n")) {
          writer.write(' ');
        }
      }
    }
    writer.write(token);
    lastToken = token;
  }

  private static final String PUNCTUATION = "()[]{}";

  @SuppressWarnings("BooleanMethodIsAlwaysInverted")
  private boolean isPunctuation(String token) {
    return (token.length() == 1) && PUNCTUATION.contains(token);
  }

  @Override
  public void writeLiteral(String val) throws IOException {
    writeToken(val);
  }

  @Override
  public void writeBoolean(boolean val, PrinterOptions.SymbolStyle style) throws IOException {
    if (style == PrinterOptions.SymbolStyle.LONG_FORM) {
      writeToken(val
          ? StvnVocabulary.VAL_TRUE
          : StvnVocabulary.VAL_FALSE);
    } else {
      writeToken(val
          ? StvnVocabulary.VAL_TRUE_SHORT
          : StvnVocabulary.VAL_FALSE_SHORT);
    }
  }

  @Override
  public void writeInteger(BigInteger val) throws IOException {
    writeToken(val.toString());
  }

  @Override
  public void writeFloat(BigDecimal val, FloatPrecision precision) throws IOException {
    writeToken(formatFloat(val, precision));
  }

  @Override
  public void writeEnumKeyword(String keyword) throws IOException {
    writeToken(keyword);
  }

  @Override
  public void writeSimpleString(String s) throws IOException {
    writeToken("\"" + escapeString(s) + "\"");
  }

  @Override
  public void writeBlockString(String s) throws IOException {
    writeToken("\"\"\"\n" + s + "\"\"\"");
  }

  @Override
  public void openGroup(String delimiter) throws IOException {
    writeToken(delimiter);
  }

  @Override
  public void closeGroup(String delimiter) throws IOException {
    writeToken(delimiter);
  }

  @Override
  public void openOptionSomeTag(PrinterOptions.SymbolStyle style) throws IOException {
    openTag(style == PrinterOptions.SymbolStyle.LONG_FORM
        ? StvnVocabulary.VAL_SOME
        : StvnVocabulary.VAL_SOME_SHORT);
  }

  @Override
  public void writeOptionNone(PrinterOptions.SymbolStyle style) throws IOException {
    writeEnumKeyword(style == PrinterOptions.SymbolStyle.LONG_FORM
        ? StvnVocabulary.VAL_NONE
        : StvnVocabulary.VAL_NONE_SHORT);
  }

  @Override
  public void openEitherTag(boolean isRight, PrinterOptions.SymbolStyle style) throws IOException {
    if (isRight) {
      openTag(style == PrinterOptions.SymbolStyle.LONG_FORM
          ? StvnVocabulary.VAL_RIGHT
          : StvnVocabulary.VAL_RIGHT_SHORT);
    } else {
      openTag(style == PrinterOptions.SymbolStyle.LONG_FORM
          ? StvnVocabulary.VAL_LEFT
          : StvnVocabulary.VAL_LEFT_SHORT);
    }
  }

  @Override
  public void openTag(String tag) throws IOException {
    writeToken(tag);
  }

  @Override
  @SuppressWarnings("RedundantThrows")
  public void closeTag() throws IOException {
    // no-op
  }

  @Override
  public void appendSeparator() throws IOException {
    // no-op, handled by writeToken delimiter checking
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
