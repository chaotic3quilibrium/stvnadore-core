package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;
import java.io.Serial;

/**
 * Exception thrown when an integer literal or numeric payload overflows
 * the physical bit-width or value range mandated by its STVN schema.
 *
 * @since 1.0.0
 */
@NullMarked
public class StvnIntegerOverflowException extends MalformedPayloadException {
  @Serial
  private static final long serialVersionUID = 1001L;

  /**
   * Constructs a new StvnIntegerOverflowException with the specified detail message.
   *
   * @param message the detail message describing the integer overflow
   */
  public StvnIntegerOverflowException(String message) {
    super(message);
  }

  /**
   * Constructs a new StvnIntegerOverflowException with the specified detail message and source coordinates.
   *
   * @param message     the detail message describing the integer overflow
   * @param startOffset the start character offset (inclusive)
   * @param endOffset   the end character offset (exclusive)
   */
  public StvnIntegerOverflowException(String message, int startOffset, int endOffset) {
    super(message, startOffset, endOffset);
  }

  /**
   * Constructs a new StvnIntegerOverflowException with the specified detail message and underlying cause.
   *
   * @param message the detail message describing the integer overflow
   * @param cause   the underlying cause of the exception
   */
  public StvnIntegerOverflowException(String message, Throwable cause) {
    super(message, cause);
  }
}
