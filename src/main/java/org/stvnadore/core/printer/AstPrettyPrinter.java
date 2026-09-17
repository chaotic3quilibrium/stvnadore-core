package org.stvnadore.core.printer;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.ir.StvnValue;

import java.io.IOException;
import java.io.Writer;

/**
 * Canonical AST pretty-printer formatting STVN value trees into a multi-line, indented representation.
 * <p>
 * This printer enforces the canonical 2-space indentation hierarchy and emits long-form
 * keywords ({@code #TRUE}, {@code #FALSE}, {@code #Some}, {@code #None}, {@code #Left}, {@code #Right}).
 * It strictly adheres to the Zero-Tab Invariant: standard ASCII spaces are used exclusively,
 * and tab characters ({@code \t}, {@code U+0009}) are never emitted.
 * </p>
 *
 * @since 1.3.0
 */
@NullMarked
public final class AstPrettyPrinter {

  /**
   * The canonical default indentation step width in spaces (2 spaces).
   */
  public static final int DEFAULT_INDENT_WIDTH = 2;

  private final PrettyTextPrinter delegate;

  /**
   * Constructs an {@code AstPrettyPrinter} with default 2-space indentation and long-form keywords.
   */
  public AstPrettyPrinter() {
    this(DEFAULT_INDENT_WIDTH);
  }

  /**
   * Constructs an {@code AstPrettyPrinter} with a configurable indentation width.
   *
   * @param indentWidth the indentation width in spaces (must be non-negative)
   * @throws IllegalArgumentException if {@code indentWidth} is negative
   */
  public AstPrettyPrinter(int indentWidth) {
    this(new PrinterOptions(
        PrinterOptions.Coverage.ALL_SECTIONS,
        validateIndentWidth(indentWidth),
        PrinterOptions.SymbolStyle.LONG_FORM,
        PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
    ));
  }

  /**
   * Constructs an {@code AstPrettyPrinter} with custom printer options.
   *
   * @param options the printer options to apply
   * @throws NullPointerException if {@code options} is null
   */
  public AstPrettyPrinter(PrinterOptions options) {
    this.delegate = new PrettyTextPrinter(java.util.Objects.requireNonNull(options, "options must not be null"));
  }

  private static int validateIndentWidth(int width) {
    if (width < 0) {
      throw new IllegalArgumentException("Indentation width must not be negative, got: " + width);
    }
    return width;
  }

  /**
   * Serializes the specified STVN AST value tree using this printer's configuration.
   *
   * @param value the root AST node to serialize
   * @return the formatted STVN string
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
   * Converts the specified STVN AST value tree into a canonical pretty-printed text representation.
   *
   * @param value the root AST node to serialize
   * @return the formatted, 2-space indented STVN string
   * @throws NullPointerException if {@code value} is null
   */
  public static String print(StvnValue value) {
    java.util.Objects.requireNonNull(value, "value must not be null");
    return new AstPrettyPrinter().printToString(value);
  }

  /**
   * Converts the specified STVN AST value tree into a pretty-printed text representation with custom indentation.
   *
   * @param value       the root AST node to serialize
   * @param indentWidth the indentation width in spaces (must be non-negative)
   * @return the formatted STVN string
   * @throws NullPointerException     if {@code value} is null
   * @throws IllegalArgumentException if {@code indentWidth} is negative
   */
  public static String print(StvnValue value, int indentWidth) {
    java.util.Objects.requireNonNull(value, "value must not be null");
    return new AstPrettyPrinter(indentWidth).printToString(value);
  }

  /**
   * Serializes the specified STVN AST value tree to the destination writer using canonical 2-space layout.
   *
   * @param value  the root AST node to serialize
   * @param target the destination writer stream
   * @throws IOException          if an I/O error occurs during serialization
   * @throws NullPointerException if {@code value} or {@code target} is null
   */
  public static void print(StvnValue value, Writer target) throws IOException {
    java.util.Objects.requireNonNull(value, "value must not be null");
    java.util.Objects.requireNonNull(target, "target must not be null");
    new AstPrettyPrinter().delegate.print(value, target);
  }

  /**
   * Serializes the specified STVN AST value tree to the destination writer with custom indentation.
   *
   * @param value       the root AST node to serialize
   * @param indentWidth the indentation width in spaces (must be non-negative)
   * @param target      the destination writer stream
   * @throws IOException              if an I/O error occurs during serialization
   * @throws NullPointerException     if {@code value} or {@code target} is null
   * @throws IllegalArgumentException if {@code indentWidth} is negative
   */
  public static void print(StvnValue value, int indentWidth, Writer target) throws IOException {
    java.util.Objects.requireNonNull(value, "value must not be null");
    java.util.Objects.requireNonNull(target, "target must not be null");
    new AstPrettyPrinter(indentWidth).delegate.print(value, target);
  }
}
