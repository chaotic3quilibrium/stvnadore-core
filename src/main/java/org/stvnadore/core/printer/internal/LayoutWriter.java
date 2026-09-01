package org.stvnadore.core.printer.internal;

import org.stvnadore.core.ir.StvnValue.FloatPrecision;
import org.stvnadore.core.printer.PrinterOptions;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Layout writer contract for formatting and printing STVN AST values to text writers.
 *
 * @since 1.0.0
 */
public interface LayoutWriter {

  /**
   * Writes a raw text literal directly to the target output.
   *
   * @param val the string literal to write
   * @throws IOException if a writing exception occurs
   */
  void writeLiteral(String val) throws IOException;

  /**
   * Writes a boolean value formatted according to the specified symbol style.
   *
   * @param val   the boolean value
   * @param style the symbol style (short or long form)
   * @throws IOException if a writing exception occurs
   */
  void writeBoolean(boolean val, PrinterOptions.SymbolStyle style) throws IOException;

  /**
   * Writes an integer value to the target output.
   *
   * @param val the integer value
   * @throws IOException if a writing exception occurs
   */
  void writeInteger(BigInteger val) throws IOException;

  /**
   * Writes a float value formatted according to the specified precision level.
   *
   * @param val       the float value
   * @param precision the float precision level
   * @throws IOException if a writing exception occurs
   */
  void writeFloat(BigDecimal val, FloatPrecision precision) throws IOException;

  /**
   * Writes an enum keyword variant value to the target output.
   *
   * @param keyword the enum keyword name
   * @throws IOException if a writing exception occurs
   */
  void writeEnumKeyword(String keyword) throws IOException;

  /**
   * Writes a simple string literal enclosed in double quotes.
   *
   * @param s the string to write
   * @throws IOException if a writing exception occurs
   */
  void writeSimpleString(String s) throws IOException;

  /**
   * Writes a multi-line block string literal.
   *
   * @param s the string to write
   * @throws IOException if a writing exception occurs
   */
  void writeBlockString(String s) throws IOException;

  /**
   * Writes an Option Some variant tag prefix.
   *
   * @param style the symbol style to use
   * @throws IOException if a writing exception occurs
   */
  void openOptionSomeTag(PrinterOptions.SymbolStyle style) throws IOException;

  /**
   * Writes the Option None variant tag.
   *
   * @param style the symbol style to use
   * @throws IOException if a writing exception occurs
   */
  void writeOptionNone(PrinterOptions.SymbolStyle style) throws IOException;

  /**
   * Writes an Either variant tag prefix.
   *
   * @param isRight {@code true} for Right variant, otherwise Left
   * @param style   the symbol style to use
   * @throws IOException if a writing exception occurs
   */
  void openEitherTag(boolean isRight, PrinterOptions.SymbolStyle style) throws IOException;

  /**
   * Opens a structural group (such as a map, seq, or tuple) using the specified delimiter.
   *
   * @param delimiter the opening delimiter symbol
   * @throws IOException if a writing exception occurs
   */
  void openGroup(String delimiter) throws IOException;

  /**
   * Closes a structural group (such as a map, seq, or tuple) using the specified delimiter.
   *
   * @param delimiter the closing delimiter symbol
   * @throws IOException if a writing exception occurs
   */
  void closeGroup(String delimiter) throws IOException;

  /**
   * Opens a tag block.
   *
   * @param tag the tag name
   * @throws IOException if a writing exception occurs
   */
  void openTag(String tag) throws IOException;

  /**
   * Closes the active tag block.
   *
   * @throws IOException if a writing exception occurs
   */
  void closeTag() throws IOException;

  /**
   * Appends an element separator (usually whitespace or comma/newline) to the output.
   *
   * @throws IOException if a writing exception occurs
   */
  void appendSeparator() throws IOException;

  /**
   * Appends a newline break to the output.
   *
   * @throws IOException if a writing exception occurs
   */
  void newline() throws IOException;

  /**
   * Increases the current indentation level.
   */
  void indent();

  /**
   * Decreases the current indentation level.
   */
  void outdent();

  /**
   * Flushes the underlying writer stream.
   *
   * @throws IOException if a writing exception occurs
   */
  void flush() throws IOException;

  /**
   * Returns a String representation of a {@link BigDecimal}, ensuring that the resulting string always contains a
   * decimal point.
   * <p>
   * The formatting behavior relies on the provided {@link FloatPrecision}:
   * <ul>
   * <li>{@link FloatPrecision#EXACT}: Utilizes {@link BigDecimal#toPlainString()}, disabling scientific notation.</li>
   * <li>Other precisions: Utilizes {@link BigDecimal#toString()}, which may include scientific notation.</li>
   * </ul>
   * If the initial string representation lacks a decimal point, this method will inject {@code ".0"} appropriately
   * (either before the exponent character or at the end of the string).
   *
   * @param value          the {@link BigDecimal} value to format
   * @param floatPrecision the precision strategy to use for the initial string conversion
   * @return a string representation of the decimal value, guaranteed to contain a decimal point
   */
  default String formatFloat(
      BigDecimal value,
      FloatPrecision floatPrecision
  ) {
    var s = (floatPrecision == FloatPrecision.EXACT)
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
}
