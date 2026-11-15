package org.stvnadore.core.printer.internal;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnSchema.FloatRepresentation;
import org.stvnadore.core.printer.PrinterOptions;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;

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
          ? StvnVocabulary.VAL_TRUE
          : StvnVocabulary.VAL_FALSE);
    } else {
      writer.write(val
          ? StvnVocabulary.VAL_TRUE_SHORT
          : StvnVocabulary.VAL_FALSE_SHORT);
    }
  }

  @Override
  public void writeInteger(BigInteger val) throws IOException {
    writeIndentIfNeeded();
    writer.write(val.toString());
  }

  @Override
  public void writeFloat(BigDecimal val, FloatRepresentation representation) throws IOException {
    writeIndentIfNeeded();
    writer.write(formatFloat(val, representation));
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

  /**
   * Writes a Rule STR-04 fenced verbatim string literal ({@code """[TAG]\n...[TAG]"""}) using
   * either verbatim column-0 preservation ({@code preserveIndent == true}) or canonical structural
   * body indentation ({@code preserveIndent == false}).
   *
   * @param text           the decoded string value to serialize
   * @param tag            the collision-safe fence tag
   * @param preserveIndent {@code true} to emit {@code text} verbatim at column 0; {@code false} to
   *                       indent non-empty lines by {@code (indentLevel + 1) * indentStep} spaces
   * @throws IOException if an I/O error occurs
   */
  public void writeFencedString(String text, String tag, boolean preserveIndent) throws IOException {
    writeIndentIfNeeded();
    writer.write("\"\"\"[");
    writer.write(tag);
    writer.write("]\n");
    if (preserveIndent) {
      writer.write(text);
    } else {
      String bodyIndent = " ".repeat((indentLevel + 1) * indentStep);
      String closingIndent = " ".repeat(indentLevel * indentStep);
      String[] lines = text.split("\n", -1);
      boolean trailingNewline = text.endsWith("\n");
      int contentLineLimit = trailingNewline ? lines.length - 1 : lines.length;
      for (int i = 0; i < contentLineLimit; i++) {
        String line = lines[i];
        if (!line.isEmpty()) {
          writer.write(bodyIndent);
          writer.write(line);
        }
        if (i < contentLineLimit - 1 || trailingNewline) {
          writer.write('\n');
        }
      }
      if (trailingNewline) {
        writer.write(closingIndent);
      }
    }
    writer.write("[");
    writer.write(tag);
    writer.write("]\"\"\"");
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

  static String escapeSimpleStringBody(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\' -> sb.append("\\\\");
        case '"' -> sb.append("\\\"");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        default -> {
          if (c < 0x20) {
            sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.toString();
  }

  private String escapeString(String s) {
    return escapeSimpleStringBody(s);
  }
}
