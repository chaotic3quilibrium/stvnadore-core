package org.stvnadore.core.io;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.ir.StvnValue.FloatPrecision;
import org.stvnadore.core.printer.PrinterOptions;
import org.stvnadore.core.printer.internal.LayoutWriter;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * A low-level layout writer that enforces canonical STVN syntax formatting rules.
 * <p>
 * This writer intercepts structural print directives and forces them into their
 * canonical representation. It normalizes whitespace spacing between adjacent literals
 * and coerces tags or boolean flags to their strict long-form formats, ensuring
 * that formatting options set on higher-level printers do not compromise CAS hashing determinism.
 * </p>
 */
@NullMarked
public final class CanonicalLayoutWriter implements LayoutWriter {
  private final Writer writer;
  private String lastToken = "";

  /**
   * Constructs a new {@code CanonicalLayoutWriter} wrapping the specified target writer.
   *
   * @param writer the destination character stream writer
   */
  public CanonicalLayoutWriter(Writer writer) {
    this.writer = writer;
  }

  private void writeToken(String token) throws IOException {
    if (token.isEmpty()) {
      return;
    }
    if (!lastToken.isEmpty()) {
      if (!isPunctuation(lastToken) && !isPunctuation(token)) {
        writer.write(' ');
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
    // Style parameter is intentionally ignored. Canonical layout writer must defensively
    // enforce LONG_FORM (#TRUE / #FALSE) representation to maintain absolute cryptographic
    // hash stability regardless of caller-side formatting configuration.
    writeToken(val
        ? "#TRUE"
        : "#FALSE");
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
    // Style parameter is intentionally ignored. Enforce LONG_FORM (#Some) variant for canonical consistency.
    writeToken("#Some");
  }

  @Override
  public void writeOptionNone(PrinterOptions.SymbolStyle style) throws IOException {
    // Style parameter is intentionally ignored. Enforce LONG_FORM (#None) variant for canonical consistency.
    writeToken("#None");
  }

  @Override
  public void openEitherTag(boolean isRight, PrinterOptions.SymbolStyle style) throws IOException {
    // Style parameter is intentionally ignored. Enforce LONG_FORM (#Right / #Left) variant for canonical consistency.
    writeToken(isRight
        ? "#Right"
        : "#Left");
  }

  @Override
  public void openTag(String tag) throws IOException {
    // Defensively coerce any short-form tags back to their canonical long-form counterparts.
    String canonicalTag;
    switch (tag) {
      case "#S", "#Some" -> canonicalTag = "#Some";
      case "#L", "#Left" -> canonicalTag = "#Left";
      case "#R", "#Right" -> canonicalTag = "#Right";
      default -> canonicalTag = tag;
    }
    writeToken(canonicalTag);
  }

  @SuppressWarnings("RedundantThrows")
  @Override
  public void closeTag() throws IOException {
    // no-op
  }

  @SuppressWarnings("RedundantThrows")
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
