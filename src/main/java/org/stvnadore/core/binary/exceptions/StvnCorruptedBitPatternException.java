package org.stvnadore.core.binary.exceptions;

import org.jspecify.annotations.NullMarked;
import java.io.Serial;

/**
 * Exception thrown when a binary payload contains non-zero bits in the unused
 * high-bit mask region of an arbitrary bit-width integer container.
 *
 * @since 1.0.0
 */
@NullMarked
public class StvnCorruptedBitPatternException extends StvnSerializationException {
  @Serial
  private static final long serialVersionUID = 1002L;

  /**
   * Constructs a new StvnCorruptedBitPatternException with the specified detail message.
   *
   * @param message the detail message describing the corrupted bit pattern
   */
  public StvnCorruptedBitPatternException(String message) {
    super(message);
  }

  /**
   * Constructs a new StvnCorruptedBitPatternException with the specified detail message and underlying cause.
   *
   * @param message the detail message describing the corrupted bit pattern
   * @param cause   the underlying cause of the exception
   */
  public StvnCorruptedBitPatternException(String message, Throwable cause) {
    super(message, cause);
  }
}
