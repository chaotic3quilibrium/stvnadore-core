package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;

import java.io.Serial;

/**
 * Exception thrown when an unresolved namespace collision is detected at the end of the `:defs` block.
 */
@NullMarked
public class NamespaceCollisionException extends RuntimeException {
  @Serial
  private static final long serialVersionUID = 7291839201948291039L;

  /**
   * Constructs a new NamespaceCollisionException with the specified detail message.
   *
   * @param message the detail message listing the unresolved collisions
   */
  public NamespaceCollisionException(String message) {
    super(message);
  }

  /**
   * Constructs a new NamespaceCollisionException with the specified detail message and cause.
   *
   * @param message the detail message listing the unresolved collisions
   * @param cause   the underlying cause
   */
  public NamespaceCollisionException(String message, Throwable cause) {
    super(message, cause);
  }
}
