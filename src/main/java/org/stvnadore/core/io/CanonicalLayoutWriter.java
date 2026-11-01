package org.stvnadore.core.io;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnSchema.FloatRepresentation;
import org.stvnadore.core.printer.PrinterOptions;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * A low-level layout writer that enforces canonical STVN syntax formatting rules.
 * <p>
 * This writer intercepts structural print directives and forces them into their
 * canonical representation. It normalizes whitespace spacing between adjacent literals
 * and coerces tags or boolean flags to their strict short-form formats, ensuring
 * that formatting options set on higher-level printers do not compromise CAS hashing determinism.
 * </p>
 */
@NullMarked
public final class CanonicalLayoutWriter {
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

  /**
   * Writes a raw literal token using canonical spacing rules.
   *
   * @param val the literal token text
   * @throws IOException if an I/O error occurs
   */
  public void writeLiteral(String val) throws IOException {
    writeToken(val);
  }

  /**
   * Writes a boolean literal forced to canonical short form ({@code #T} or {@code #F}).
   *
   * @param val   the boolean value
   * @param style the requested symbol style (ignored in favor of canonical short form)
   * @throws IOException if an I/O error occurs
   */
  public void writeBoolean(boolean val, PrinterOptions.SymbolStyle style) throws IOException {
    // Enforce Frozen Canonical CAS Normalizer Profile: SHORT_FORM (#T / #F)
    writeToken(val
        ? StvnVocabulary.VAL_TRUE_SHORT
        : StvnVocabulary.VAL_FALSE_SHORT);
  }

  /**
   * Writes an arbitrary-precision integer token in base-10 canonical form.
   *
   * @param val the integer value
   * @throws IOException if an I/O error occurs
   */
  public void writeInteger(BigInteger val) throws IOException {
    writeToken(val.toString());
  }

  /**
   * Writes a floating-point or exact decimal token guaranteed to include a decimal point.
   *
   * @param val            the decimal value
   * @param representation the schema float representation mode
   * @throws IOException if an I/O error occurs
   */
  public void writeFloat(BigDecimal val, FloatRepresentation representation) throws IOException {
    writeToken(formatFloat(val, representation));
  }

  /**
   * Formats a {@link BigDecimal} value into a valid STVN floating-point literal guaranteed to
   * include a decimal point.
   *
   * @param value          the {@link BigDecimal} value to format
   * @param representation the schema float representation mode
   * @return a string representation guaranteed to contain a decimal point
   */
  public String formatFloat(BigDecimal value, FloatRepresentation representation) {
    var s = (representation == FloatRepresentation.EXACT_DECIMAL)
        ? value.toPlainString()
        : value.toString();
    if (!s.contains(".")) {
      var eIdx = s.indexOf('e');
      if (eIdx == -1) {
        eIdx = s.indexOf('E');
      }
      return (eIdx == -1)
          ? s + ".0"
          : s.substring(0, eIdx) + ".0" + s.substring(eIdx);
    }
    return s;
  }

  /**
   * Writes an enum variant tag token.
   *
   * @param keyword the enum variant keyword
   * @throws IOException if an I/O error occurs
   */
  public void writeEnumKeyword(String keyword) throws IOException {
    writeToken(keyword);
  }

  /**
   * Writes a double-quoted, canonically escaped UTF-8 string literal.
   *
   * @param s the raw string content
   * @throws IOException if an I/O error occurs
   */
  public void writeSimpleString(String s) throws IOException {
    writeToken("\"" + escapeString(s) + "\"");
  }

  /**
   * Writes an opening structural delimiter ({@code (}, {@code [}, or {@code &#123;}).
   *
   * @param delimiter the opening delimiter
   * @throws IOException if an I/O error occurs
   */
  public void openGroup(String delimiter) throws IOException {
    writeToken(delimiter);
  }

  /**
   * Writes a closing structural delimiter ({@code )}, {@code ]}, or {@code &#125;}).
   *
   * @param delimiter the closing delimiter
   * @throws IOException if an I/O error occurs
   */
  public void closeGroup(String delimiter) throws IOException {
    writeToken(delimiter);
  }

  /**
   * Writes the canonical short-form {@code :Option} present tag ({@code #S}).
   *
   * @param style the requested symbol style (ignored in favor of canonical short form)
   * @throws IOException if an I/O error occurs
   */
  public void openOptionSomeTag(PrinterOptions.SymbolStyle style) throws IOException {
    // Enforce Frozen Canonical CAS Normalizer Profile: SHORT_FORM (#S)
    writeToken(StvnVocabulary.VAL_SOME_SHORT);
  }

  /**
   * Writes the canonical short-form {@code :Option} absent tag ({@code #N}).
   *
   * @param style the requested symbol style (ignored in favor of canonical short form)
   * @throws IOException if an I/O error occurs
   */
  public void writeOptionNone(PrinterOptions.SymbolStyle style) throws IOException {
    // Enforce Frozen Canonical CAS Normalizer Profile: SHORT_FORM (#N)
    writeToken(StvnVocabulary.VAL_NONE_SHORT);
  }

  /**
   * Writes the canonical short-form {@code :Either} branch tag ({@code #L} or {@code #R}).
   *
   * @param isRight {@code true} for right branch ({@code #R}); {@code false} for left branch ({@code #L})
   * @param style   the requested symbol style (ignored in favor of canonical short form)
   * @throws IOException if an I/O error occurs
   */
  public void openEitherTag(boolean isRight, PrinterOptions.SymbolStyle style) throws IOException {
    // Enforce Frozen Canonical CAS Normalizer Profile: SHORT_FORM (#R / #L)
    writeToken(isRight
        ? StvnVocabulary.VAL_RIGHT_SHORT
        : StvnVocabulary.VAL_LEFT_SHORT);
  }

  /**
   * Writes an algebraic tag coerced to canonical short form when applicable.
   *
   * @param tag the algebraic or structural tag
   * @throws IOException if an I/O error occurs
   */
  public void openTag(String tag) throws IOException {
    // Defensively coerce any long-form tags to their canonical short-form counterparts.
    String canonicalTag;
    switch (tag) {
      case StvnVocabulary.VAL_SOME, StvnVocabulary.VAL_SOME_SHORT -> canonicalTag = StvnVocabulary.VAL_SOME_SHORT;
      case StvnVocabulary.VAL_NONE, StvnVocabulary.VAL_NONE_SHORT -> canonicalTag = StvnVocabulary.VAL_NONE_SHORT;
      case StvnVocabulary.VAL_LEFT, StvnVocabulary.VAL_LEFT_SHORT -> canonicalTag = StvnVocabulary.VAL_LEFT_SHORT;
      case StvnVocabulary.VAL_RIGHT, StvnVocabulary.VAL_RIGHT_SHORT -> canonicalTag = StvnVocabulary.VAL_RIGHT_SHORT;
      case StvnVocabulary.VAL_TRUE, StvnVocabulary.VAL_TRUE_SHORT -> canonicalTag = StvnVocabulary.VAL_TRUE_SHORT;
      case StvnVocabulary.VAL_FALSE, StvnVocabulary.VAL_FALSE_SHORT -> canonicalTag = StvnVocabulary.VAL_FALSE_SHORT;
      default -> canonicalTag = tag;
    }
    writeToken(canonicalTag);
  }

  /**
   * Closes an algebraic tag scope (no-op in canonical single-space layout).
   *
   * @throws IOException if an I/O error occurs
   */
  @SuppressWarnings("RedundantThrows")
  public void closeTag() throws IOException {
    // no-op
  }

  /**
   * Appends an element separator (no-op; spacing is governed by {@code writeToken}).
   *
   * @throws IOException if an I/O error occurs
   */
  @SuppressWarnings("RedundantThrows")
  public void appendSeparator() throws IOException {
    // no-op, handled by writeToken delimiter checking
  }

  /**
   * Emits a line break (no-op in single-line canonical layout).
   *
   * @throws IOException if an I/O error occurs
   */
  @SuppressWarnings("RedundantThrows")
  public void newline() throws IOException {
    // no-op
  }

  /** Increases indentation depth (no-op in single-line canonical layout). */
  public void indent() {
    // no-op
  }

  /** Decreases indentation depth (no-op in single-line canonical layout). */
  public void outdent() {
    // no-op
  }

  /**
   * Flushes the underlying target character stream.
   *
   * @throws IOException if an I/O error occurs
   */
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
