package org.stvnadore.core.printer;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.ir.StvnValue;

import java.io.IOException;
import java.io.Writer;

/**
 * Canonical AST compact-printer formatting STVN value trees into a dense, single-line text representation.
 * <p>
 * This printer formats outputs with minimal whitespace (omitting indentations and newlines)
 * and emits short-form keywords ({@code #T}, {@code #F}, {@code #S}, {@code #N}, {@code #L}, {@code #R}).
 * It strictly adheres to the Zero-Tab Invariant: standard ASCII spaces are used exclusively,
 * and tab characters ({@code \t}, {@code U+0009}) are never emitted.
 * </p>
 *
 * @since 1.3.0
 */
@NullMarked
public final class AstCompactPrinter {

  private final CompactTextPrinter delegate;

  /**
   * Constructs an {@code AstCompactPrinter} with default compact options (short-form keywords, zero indent).
   */
  public AstCompactPrinter() {
    this(new PrinterOptions(
        PrinterOptions.Coverage.ALL_SECTIONS,
        0,
        PrinterOptions.SymbolStyle.SHORT_FORM,
        PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
    ));
  }

  /**
   * Constructs an {@code AstCompactPrinter} with custom printer options.
   *
   * @param options the printer options to apply
   * @throws NullPointerException if {@code options} is null
   */
  public AstCompactPrinter(PrinterOptions options) {
    this.delegate = new CompactTextPrinter(java.util.Objects.requireNonNull(options, "options must not be null"));
  }

  /**
   * Serializes the specified STVN AST value tree using this printer's configuration.
   *
   * @param value the root AST node to serialize
   * @return the compact single-line STVN string with short-form keywords
   * @throws NullPointerException if {@code value} is null
   */
  public String printToString(StvnValue value) {
    java.util.Objects.requireNonNull(value, "value must not be null");
    return delegate.printToString(value);
  }

  /**
   * Serializes the specified STVN AST value tree to the destination writer using this printer's configuration.
   *
   * @param value the root AST node to serialize
   * @param target the destination writer stream
   * @throws IOException if an I/O error occurs during serialization
   * @throws NullPointerException if {@code value} or {@code target} is null
   */
  public void printToWriter(StvnValue value, Writer target) throws IOException {
    java.util.Objects.requireNonNull(value, "value must not be null");
    java.util.Objects.requireNonNull(target, "target must not be null");
    delegate.print(value, target);
  }

  /**
   * Converts the specified STVN AST value tree into a canonical compact single-line text representation.
   *
   * @param value the root AST node to serialize
   * @return the compact single-line STVN string with short-form keywords
   * @throws NullPointerException if {@code value} is null
   */
  public static String print(StvnValue value) {
    java.util.Objects.requireNonNull(value, "value must not be null");
    return new AstCompactPrinter().printToString(value);
  }

  /**
   * Serializes the specified STVN AST value tree to the destination writer using compact single-line layout.
   *
   * @param value  the root AST node to serialize
   * @param target the destination writer stream
   * @throws IOException          if an I/O error occurs during serialization
   * @throws NullPointerException if {@code value} or {@code target} is null
   */
  public static void print(StvnValue value, Writer target) throws IOException {
    java.util.Objects.requireNonNull(value, "value must not be null");
    java.util.Objects.requireNonNull(target, "target must not be null");
    new AstCompactPrinter().delegate.print(value, target);
  }
}
