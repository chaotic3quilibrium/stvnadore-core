package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;
import java.io.Serial;

/**
 * Exception thrown when a numeric payload's value overflows the bit-width constraint
 * specified in its STVN schema.
 *
 * @deprecated Prefer {@link StvnIntegerOverflowException} for full STVN Specification v1.0.0 compliance.
 * @since 1.0.0
 */
@Deprecated(since = "1.0.0", forRemoval = false)
@NullMarked
public final class BitWidthOverflowException extends StvnIntegerOverflowException {
  @Serial
  private static final long serialVersionUID = 6162946110918097390L;

  /**
   * Constructs a new BitWidthOverflowException with the specified detail message.
   *
   * @param message the detail message describing the bit-width constraint violation
   */
  public BitWidthOverflowException(String message) {
    super(message);
  }

  /**
   * Constructs a new BitWidthOverflowException with the specified detail message and coordinates.
   *
   * @param message     the detail message describing the bit-width constraint violation
   * @param startOffset the start character offset (inclusive)
   * @param endOffset   the end character offset (exclusive)
   */
  public BitWidthOverflowException(String message, int startOffset, int endOffset) {
    super(message, startOffset, endOffset);
  }
}

