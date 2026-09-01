package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;
import java.io.Serial;

/**
 * Exception thrown when the AST/Parse tree context contains structural anomalies or null leakages,
 * typically arising from parser recovery pathways operating on malformed syntax without fail-fast listeners.
 *
 * @since 1.0.0
 */
@NullMarked
public class MalformedAstContextException extends RuntimeException {
  @Serial
  private static final long serialVersionUID = 1L;

  /**
   * Constructs a new MalformedAstContextException with the specified detail message.
   *
   * @param message the detail message describing the structural error
   */
  public MalformedAstContextException(String message) {
    super(message);
  }

  /**
   * Constructs a new MalformedAstContextException with the specified detail message and cause.
   *
   * @param message the detail message describing the structural error
   * @param cause   the underlying cause of the failure
   */
  public MalformedAstContextException(String message, Throwable cause) {
    super(message, cause);
  }
}
