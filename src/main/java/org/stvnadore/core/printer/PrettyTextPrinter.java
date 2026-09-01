package org.stvnadore.core.printer;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.printer.internal.LayoutWriter;
import org.stvnadore.core.printer.internal.PrettyLayoutWriter;
import java.io.Writer;

/**
 * A printer that formats STVN value trees into a multi-line, indented pretty text representation.
 * <p>
 * This printer inserts line breaks and spaces according to {@link PrinterOptions#indentStep()}
 * to format structural blocks (tuples, lists, maps, etc.) in a human-readable layout.
 * </p>
 */
@NullMarked
public final class PrettyTextPrinter extends AbstractStvnPrinter {

  /**
   * Constructs a {@code PrettyTextPrinter} with the specified printer options.
   *
   * @param options the formatting options to use
   */
  public PrettyTextPrinter(PrinterOptions options) {
    super(options);
  }

  /**
   * Constructs a {@code PrettyTextPrinter} with default pretty printing options.
   */
  public PrettyTextPrinter() {
    super();
  }

  @Override
  protected LayoutWriter createLayoutWriter(Writer target) {
    return new PrettyLayoutWriter(target, options.indentStep());
  }
}
