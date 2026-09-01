package org.stvnadore.core.binary.exceptions;

import org.jspecify.annotations.NullMarked;
import java.io.Serial;

/**
 * Exception thrown when a zero-trust schema hash verification fails against
 * the cryptographic hash declared in a binary header control byte (e.g. Control Byte 0x07).
 *
 * @since 1.0.0
 */
@NullMarked
public class PoisonedRegistryPayloadException extends StvnSerializationException {
  @Serial
  private static final long serialVersionUID = 1003L;

  /**
   * Constructs a new PoisonedRegistryPayloadException with the specified detail message.
   *
   * @param message the detail message describing the schema hash mismatch / poisoning
   */
  public PoisonedRegistryPayloadException(String message) {
    super(message);
  }

  /**
   * Constructs a new PoisonedRegistryPayloadException with the specified detail message and underlying cause.
   *
   * @param message the detail message describing the schema hash mismatch / poisoning
   * @param cause   the underlying cause of the exception
   */
  public PoisonedRegistryPayloadException(String message, Throwable cause) {
    super(message, cause);
  }
}
