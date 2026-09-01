package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;

import java.io.Serial;

/**
 * Exception thrown when a duplicate module import is detected in a document's `:defs` block context.
 */
@NullMarked
public class DuplicateModuleImportException extends RuntimeException {
  @Serial
  private static final long serialVersionUID = 4829103982918392019L;

  /**
   * Constructs a new DuplicateModuleImportException with the specified detail message.
   *
   * @param message the detail message describing the duplicate import violation
   */
  public DuplicateModuleImportException(String message) {
    super(message);
  }

  /**
   * Constructs a new DuplicateModuleImportException with the specified detail message and cause.
   *
   * @param message the detail message describing the duplicate import violation
   * @param cause   the underlying cause
   */
  public DuplicateModuleImportException(String message, Throwable cause) {
    super(message, cause);
  }
}
