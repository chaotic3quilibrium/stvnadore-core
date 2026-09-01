package org.stvnadore.core.printer;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.printer.internal.CompactLayoutWriter;
import org.stvnadore.core.printer.internal.LayoutWriter;
import java.io.Writer;

/**
 * A printer that formats STVN value trees into a compact, single-line text representation.
 * <p>
 * This printer formats outputs with minimal whitespace (omitting indentations and newlines)
 * to optimize for network transit size, log files, or terminal printing where visual spacing
 * is not required.
 * </p>
 */
@NullMarked
public final class CompactTextPrinter extends AbstractStvnPrinter {

  /**
   * Constructs a {@code CompactTextPrinter} with the specified printer options.
   *
   * @param options the formatting options to use
   */
  public CompactTextPrinter(PrinterOptions options) {
    super(options);
  }

  /**
   * Constructs a {@code CompactTextPrinter} with default compact formatting options.
   * <p>
   * Default compact options use {@link PrinterOptions.SymbolStyle#SHORT_FORM} and no indentation.
   * </p>
   */
  public CompactTextPrinter() {
    super(new PrinterOptions(PrinterOptions.Coverage.ALL_SECTIONS, 0, PrinterOptions.SymbolStyle.SHORT_FORM, PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED));
  }

  @Override
  protected LayoutWriter createLayoutWriter(Writer target) {
    return new CompactLayoutWriter(target);
  }
}
