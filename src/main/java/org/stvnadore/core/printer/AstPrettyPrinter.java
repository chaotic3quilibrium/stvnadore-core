package org.stvnadore.core.printer;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnValue;

import java.io.IOException;
import java.io.Writer;
import java.util.Objects;

/**
 * Canonical pure-IR pretty-printer formatting STVN documents and value trees into a multi-line,
 * indented representation.
 * <p>
 * This printer enforces the canonical 2-space indentation hierarchy and emits long-form
 * keywords ({@code #TRUE}, {@code #FALSE}, {@code #Some}, {@code #None}, {@code #Left}, {@code #Right}).
 * It strictly adheres to the Zero-Tab Invariant: standard ASCII spaces are used exclusively for
 * structural indentation, while tab characters ({@code \t}, {@code U+0009}) are escaped as {@code \t}
 * in simple quoted strings and preserved verbatim exclusively inside fenced string bodies per Rule STR-04.
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
   * @throws NullPointerException if {@code options} is {@code null}
   */
  public AstPrettyPrinter(PrinterOptions options) {
    this.delegate = new PrettyTextPrinter(Objects.requireNonNull(options, "options must not be null"));
  }

  private static int validateIndentWidth(int width) {
    if (width < 0) {
      throw new IllegalArgumentException("Indentation width must not be negative, got: " + width);
    }
    return width;
  }

  /**
   * Serializes the specified pure-IR STVN document using this printer's configuration.
   *
   * @param document the pure-IR document to serialize
   * @return the formatted STVN string
   * @throws NullPointerException if {@code document} is {@code null}
   * @since 2.0.0
   */
  public String printToString(StvnDocument document) {
    Objects.requireNonNull(document, "document must not be null");
    return delegate.printToString(document);
  }

  /**
   * Serializes the specified pure-IR STVN document to the destination writer using this printer's configuration.
   *
   * @param document the pure-IR document to serialize
   * @param target   the destination writer stream
   * @throws IOException          if an I/O error occurs during serialization
   * @throws NullPointerException if {@code document} or {@code target} is {@code null}
   * @since 2.0.0
   */
  public void printToWriter(StvnDocument document, Writer target) throws IOException {
    Objects.requireNonNull(document, "document must not be null");
    Objects.requireNonNull(target, "target must not be null");
    delegate.print(document, target);
  }

  /**
   * Serializes the specified pure-IR STVN value tree using this printer's configuration.
   *
   * @param value the root pure-IR value node to serialize
   * @return the formatted STVN string
   * @throws NullPointerException if {@code value} is {@code null}
   * @since 2.0.0
   */
  public String printToString(StvnValue value) {
    Objects.requireNonNull(value, "value must not be null");
    return delegate.printToString(value);
  }

  /**
   * Serializes the specified pure-IR STVN value tree to the destination writer using this printer's configuration.
   *
   * @param value  the root pure-IR value node to serialize
   * @param target the destination writer stream
   * @throws IOException          if an I/O error occurs during serialization
   * @throws NullPointerException if {@code value} or {@code target} is {@code null}
   * @since 2.0.0
   */
  public void printToWriter(StvnValue value, Writer target) throws IOException {
    Objects.requireNonNull(value, "value must not be null");
    Objects.requireNonNull(target, "target must not be null");
    delegate.print(value, target);
  }

  /**
   * Converts the specified pure-IR STVN document into a canonical pretty-printed text representation.
   *
   * @param document the pure-IR document to serialize
   * @return the formatted, 2-space indented STVN string
   * @throws NullPointerException if {@code document} is {@code null}
   * @since 2.0.0
   */
  public static String print(StvnDocument document) {
    Objects.requireNonNull(document, "document must not be null");
    return new AstPrettyPrinter().printToString(document);
  }

  /**
   * Converts the specified pure-IR STVN document into a pretty-printed text representation with custom indentation.
   *
   * @param document    the pure-IR document to serialize
   * @param indentWidth the indentation width in spaces (must be non-negative)
   * @return the formatted STVN string
   * @throws NullPointerException     if {@code document} is {@code null}
   * @throws IllegalArgumentException if {@code indentWidth} is negative
   * @since 2.0.0
   */
  public static String print(StvnDocument document, int indentWidth) {
    Objects.requireNonNull(document, "document must not be null");
    return new AstPrettyPrinter(indentWidth).printToString(document);
  }

  /**
   * Converts the specified pure-IR STVN document into a pretty-printed text representation with custom options.
   *
   * @param document the pure-IR document to serialize
   * @param options  the printer options to apply
   * @return the formatted STVN string
   * @throws NullPointerException if {@code document} or {@code options} is {@code null}
   * @since 2.0.0
   */
  public static String print(StvnDocument document, PrinterOptions options) {
    Objects.requireNonNull(document, "document must not be null");
    Objects.requireNonNull(options, "options must not be null");
    return new AstPrettyPrinter(options).printToString(document);
  }

  /**
   * Serializes the specified pure-IR STVN document to the destination writer using canonical 2-space layout.
   *
   * @param document the pure-IR document to serialize
   * @param target   the destination writer stream
   * @throws IOException          if an I/O error occurs during serialization
   * @throws NullPointerException if {@code document} or {@code target} is {@code null}
   * @since 2.0.0
   */
  public static void print(StvnDocument document, Writer target) throws IOException {
    Objects.requireNonNull(document, "document must not be null");
    Objects.requireNonNull(target, "target must not be null");
    new AstPrettyPrinter().printToWriter(document, target);
  }

  /**
   * Converts the specified pure-IR STVN value tree into a canonical pretty-printed text representation.
   *
   * @param value the root pure-IR value node to serialize
   * @return the formatted, 2-space indented STVN string
   * @throws NullPointerException if {@code value} is {@code null}
   * @since 2.0.0
   */
  public static String print(StvnValue value) {
    Objects.requireNonNull(value, "value must not be null");
    return new AstPrettyPrinter().printToString(value);
  }

  /**
   * Converts the specified pure-IR STVN value tree into a pretty-printed text representation with custom indentation.
   *
   * @param value       the root pure-IR value node to serialize
   * @param indentWidth the indentation width in spaces (must be non-negative)
   * @return the formatted STVN string
   * @throws NullPointerException     if {@code value} is {@code null}
   * @throws IllegalArgumentException if {@code indentWidth} is negative
   * @since 2.0.0
   */
  public static String print(StvnValue value, int indentWidth) {
    Objects.requireNonNull(value, "value must not be null");
    return new AstPrettyPrinter(indentWidth).printToString(value);
  }

  /**
   * Serializes the specified pure-IR STVN value tree to the destination writer using canonical 2-space layout.
   *
   * @param value  the root pure-IR value node to serialize
   * @param target the destination writer stream
   * @throws IOException          if an I/O error occurs during serialization
   * @throws NullPointerException if {@code value} or {@code target} is {@code null}
   * @since 2.0.0
   */
  public static void print(StvnValue value, Writer target) throws IOException {
    Objects.requireNonNull(value, "value must not be null");
    Objects.requireNonNull(target, "target must not be null");
    new AstPrettyPrinter().printToWriter(value, target);
  }

  /**
   * Serializes the specified pure-IR STVN value tree to the destination writer with custom indentation.
   *
   * @param value       the root pure-IR value node to serialize
   * @param indentWidth the indentation width in spaces (must be non-negative)
   * @param target      the destination writer stream
   * @throws IOException              if an I/O error occurs during serialization
   * @throws NullPointerException     if {@code value} or {@code target} is {@code null}
   * @throws IllegalArgumentException if {@code indentWidth} is negative
   * @since 2.0.0
   */
  public static void print(StvnValue value, int indentWidth, Writer target) throws IOException {
    Objects.requireNonNull(value, "value must not be null");
    Objects.requireNonNull(target, "target must not be null");
    new AstPrettyPrinter(indentWidth).printToWriter(value, target);
  }
}
