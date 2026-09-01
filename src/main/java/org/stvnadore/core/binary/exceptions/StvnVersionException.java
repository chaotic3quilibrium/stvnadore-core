package org.stvnadore.core.binary.exceptions;

import java.io.Serial;

import org.jspecify.annotations.Nullable;
import org.stvnadore.core.binary.SchemaIdentityStrategy;

/**
 * Exception thrown when the version or schema identity of a binary payload does not match
 * the expected schema configured at the decoder.
 * <p>
 * This exception is crucial for governance, ensuring that client applications fail-fast rather
 * than deserializing payloads using a mismatched, potentially incompatible schema version.
 *
 * @since 1.0.0
 */
public class StvnVersionException extends RuntimeException {
  @Serial
  private static final long serialVersionUID = -4509315525257507677L;

  private final transient SchemaIdentityStrategy expected;
  private final transient @Nullable SchemaIdentityStrategy actual;

  /**
   * Constructs a new StvnVersionException with the specified details.
   *
   * @param message  the detail message explaining the version mismatch
   * @param expected the expected schema identity strategy
   * @param actual   the actual schema identity strategy encountered, or {@code null} if unspecified
   */
  public StvnVersionException(String message, SchemaIdentityStrategy expected, @Nullable SchemaIdentityStrategy actual) {
    super(message);
    this.expected = expected;
    this.actual = actual;
  }

  /**
   * Returns the expected schema identity strategy configuration.
   *
   * @return the expected schema identity strategy
   */
  @SuppressWarnings("unused")
  public SchemaIdentityStrategy getExpected() {
    return expected;
  }

  /**
   * Returns the actual schema identity strategy encountered in the binary payload, if any.
   *
   * @return the actual schema identity strategy, or {@code null} if not specified in the payload
   */
  @SuppressWarnings("unused")
  public @Nullable SchemaIdentityStrategy getActual() {
    return actual;
  }
}
