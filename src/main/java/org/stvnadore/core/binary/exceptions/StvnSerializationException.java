package org.stvnadore.core.binary.exceptions;

import java.io.Serial;

/**
 * Exception thrown when a binary serialization or decoding operation fails.
 * <p>
 * This exception can occur if an encoder is unable to write standard offsets, if a payload overflows
 * static footprints, or if an invalid control byte is encountered while parsing a binary stream.
 *
 * @since 1.0.0
 */
public class StvnSerializationException extends RuntimeException {
  @Serial
  private static final long serialVersionUID = 1L;

  /**
   * Constructs a new StvnSerializationException with the specified detail message.
   *
   * @param message the detail message describing the serialization/decoding failure
   */
  public StvnSerializationException(String message) {
    super(message);
  }

  /**
   * Constructs a new StvnSerializationException with the specified detail message and cause.
   *
   * @param message the detail message describing the serialization/decoding failure
   * @param cause   the underlying cause of the serialization/decoding failure
   */
  public StvnSerializationException(String message, Throwable cause) {
    super(message, cause);
  }
}
