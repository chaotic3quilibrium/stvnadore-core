package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;
import java.io.Serial;

/**
 * Unchecked exception thrown when a nominal type alias or typed constant declaration forms an
 * unguarded circular reference cycle ({@code E007} / {@link DiagnosticBag#ERR_CIRCULAR_TYPE}).
 *
 * @since 2.0.0
 */
@NullMarked
public class CircularReferenceException extends RuntimeException {
  @Serial
  private static final long serialVersionUID = 2000L;

  /**
   * Constructs a new {@code CircularReferenceException} with the specified diagnostic message.
   *
   * @param message the detail message describing the circular reference chain
   */
  public CircularReferenceException(String message) {
    super(message);
  }

  /**
   * Constructs a new {@code CircularReferenceException} with the specified diagnostic message and cause.
   *
   * @param message the detail message describing the circular reference chain
   * @param cause   the underlying cause
   */
  public CircularReferenceException(String message, Throwable cause) {
    super(message, cause);
  }
}
