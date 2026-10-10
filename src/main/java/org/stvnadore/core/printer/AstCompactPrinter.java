package org.stvnadore.core.printer;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.ast.StvnDocument;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema;

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
  private final PrinterOptions options;

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
    this.options = java.util.Objects.requireNonNull(options, "options must not be null");
    this.delegate = new CompactTextPrinter(this.options);
  }

  /**
   * Serializes the specified STVN document using this printer's configuration.
   *
   * @param document the document to serialize
   * @return the compact STVN string representation
   * @throws NullPointerException if {@code document} is null
   */
  public String printToString(StvnDocument document) {
    java.util.Objects.requireNonNull(document, "document must not be null");
    return delegate.printToString(document);
  }

  /**
   * Serializes the specified STVN document to the destination writer using this printer's configuration.
   *
   * @param document the document to serialize
   * @param target the destination writer stream
   * @throws IOException if an I/O error occurs during serialization
   * @throws NullPointerException if {@code document} or {@code target} is null
   */
  public void printToWriter(StvnDocument document, Writer target) throws IOException {
    java.util.Objects.requireNonNull(document, "document must not be null");
    java.util.Objects.requireNonNull(target, "target must not be null");
    delegate.print(document, target);
  }

  /**
   * Serializes the specified STVN AST value tree using this printer's configuration.
   *
   * @param value the root AST node to serialize
   * @return the compact single-line STVN string with short-form keywords
   * @throws NullPointerException if {@code value} is null
   * @throws IllegalStateException if {@code HAPPY_PATH_INFERRED} is active without schema context
   */
  public String printToString(StvnValue value) {
    java.util.Objects.requireNonNull(value, "value must not be null");
    validateSchemaContext(value, null);
    return delegate.printToString(value);
  }

  /**
   * Serializes the specified STVN AST value tree using explicit schema context under this printer's configuration.
   *
   * @param value the root AST node to serialize
   * @param schema the explicit target schema context
   * @return the compact single-line STVN string
   * @throws NullPointerException if {@code value} or {@code schema} is null
   */
  public String printToString(StvnValue value, ResolvedSchema schema) {
    java.util.Objects.requireNonNull(value, "value must not be null");
    java.util.Objects.requireNonNull(schema, "schema must not be null");
    return delegate.printToString(value, schema);
  }

  /**
   * Serializes the specified STVN AST value tree to the destination writer using this printer's configuration.
   *
   * @param value the root AST node to serialize
   * @param target the destination writer stream
   * @throws IOException if an I/O error occurs during serialization
   * @throws NullPointerException if {@code value} or {@code target} is null
   * @throws IllegalStateException if {@code HAPPY_PATH_INFERRED} is active without schema context
   */
  public void printToWriter(StvnValue value, Writer target) throws IOException {
    java.util.Objects.requireNonNull(value, "value must not be null");
    java.util.Objects.requireNonNull(target, "target must not be null");
    validateSchemaContext(value, null);
    delegate.print(value, target);
  }

  /**
   * Serializes the specified STVN AST value tree to the destination writer using explicit schema context.
   *
   * @param value the root AST node to serialize
   * @param schema the explicit target schema context
   * @param target the destination writer stream
   * @throws IOException if an I/O error occurs during serialization
   * @throws NullPointerException if {@code value}, {@code schema}, or {@code target} is null
   */
  public void printToWriter(StvnValue value, ResolvedSchema schema, Writer target) throws IOException {
    java.util.Objects.requireNonNull(value, "value must not be null");
    java.util.Objects.requireNonNull(schema, "schema must not be null");
    java.util.Objects.requireNonNull(target, "target must not be null");
    delegate.print(value, schema, target);
  }

  private void validateSchemaContext(StvnValue value, @Nullable ResolvedSchema explicitSchema) {
    if (options.sumTypePolicy() == PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED) {
      boolean hasContext = (explicitSchema != null)
          || (value.schema() != null && !value.schema().isPoisonedSentinel());
      if (!hasContext) {
        throw new IllegalStateException(
            "Cannot apply HAPPY_PATH_INFERRED without schema context: target schema is required to verify variant elision legality under Rules A, B, and I."
        );
      }
    }
  }

  /**
   * Converts the specified STVN document into a canonical compact single-line text representation.
   *
   * @param document the document to serialize
   * @return the compact single-line STVN string
   * @throws NullPointerException if {@code document} is null
   */
  public static String print(StvnDocument document) {
    java.util.Objects.requireNonNull(document, "document must not be null");
    return new AstCompactPrinter().printToString(document);
  }

  /**
   * Converts the specified STVN document into a compact text representation using custom options.
   *
   * @param document the document to serialize
   * @param options the printer options to apply
   * @return the compact single-line STVN string
   * @throws NullPointerException if {@code document} or {@code options} is null
   */
  public static String print(StvnDocument document, PrinterOptions options) {
    java.util.Objects.requireNonNull(document, "document must not be null");
    java.util.Objects.requireNonNull(options, "options must not be null");
    return new AstCompactPrinter(options).printToString(document);
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
   * Converts the specified STVN AST value tree into a compact single-line text representation using custom options.
   *
   * @param value the root AST node to serialize
   * @param options the printer options to apply
   * @return the compact single-line STVN string
   * @throws NullPointerException if {@code value} or {@code options} is null
   */
  public static String print(StvnValue value, PrinterOptions options) {
    java.util.Objects.requireNonNull(value, "value must not be null");
    java.util.Objects.requireNonNull(options, "options must not be null");
    return new AstCompactPrinter(options).printToString(value);
  }

  /**
   * Converts the specified STVN AST value tree into a compact text representation using explicit schema and options.
   *
   * @param value the root AST node to serialize
   * @param schema the explicit target schema context
   * @param options the printer options to apply
   * @return the compact single-line STVN string
   * @throws NullPointerException if any argument is null
   */
  public static String print(StvnValue value, ResolvedSchema schema, PrinterOptions options) {
    java.util.Objects.requireNonNull(value, "value must not be null");
    java.util.Objects.requireNonNull(schema, "schema must not be null");
    java.util.Objects.requireNonNull(options, "options must not be null");
    return new AstCompactPrinter(options).printToString(value, schema);
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
    new AstCompactPrinter().printToWriter(value, target);
  }
}
