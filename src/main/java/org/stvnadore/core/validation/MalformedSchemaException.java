package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.io.Serial;

/**
 * Exception thrown when an STVN schema definition (such as within `:defs` or `:type` fields) is
 * structurally or logically malformed.
 * <p>
 * This exception is typically thrown during schema resolution or constraint validation (e.g., when a
 * nominal type is shadowed, when there are structural collisions in a `:Union`, or when a recursive
 * schema loop lacks a nominal definition anchor).
 *
 * @since 1.0.0
 */
@NullMarked
public class MalformedSchemaException extends RuntimeException {
  @Serial
  private static final long serialVersionUID = -7461646172931980633L;

  /** The start character offset of the error (inclusive). */
  private final int startOffset;
  /** The end character offset of the error (exclusive). */
  private final int endOffset;

  /**
   * Constructs a new MalformedSchemaException with the specified detail message.
   *
   * @param message the detail message describing the schema validation failure
   */
  public MalformedSchemaException(String message) {
    this(message, -1, -1, null);
  }

  /**
   * Constructs a new MalformedSchemaException with the specified detail message and cause.
   *
   * @param message the detail message describing the schema validation failure
   * @param cause   the underlying cause of the schema validation failure
   */
  public MalformedSchemaException(String message, @Nullable Throwable cause) {
    this(message, -1, -1, cause);
  }

  /**
   * Constructs a new MalformedSchemaException with the specified detail message and coordinates.
   *
   * @param message     the detail message describing the validation failure
   * @param startOffset the start character offset (inclusive)
   * @param endOffset   the end character offset (exclusive)
   */
  public MalformedSchemaException(String message, int startOffset, int endOffset) {
    this(message, startOffset, endOffset, null);
  }

  /**
   * Constructs a new MalformedSchemaException with the specified detail message, coordinates, and cause.
   *
   * @param message     the detail message describing the validation failure
   * @param startOffset the start character offset (inclusive)
   * @param endOffset   the end character offset (exclusive)
   * @param cause       the underlying cause of the validation failure
   */
  public MalformedSchemaException(String message, int startOffset, int endOffset, @Nullable Throwable cause) {
    super(message, cause);
    this.startOffset = startOffset;
    this.endOffset = endOffset;
  }

  /**
   * Returns the start character offset of the error.
   *
   * @return the start character offset
   */
  public int startOffset() {
    return startOffset;
  }

  /**
   * Returns the end character offset of the error.
   *
   * @return the end character offset
   */
  public int endOffset() {
    return endOffset;
  }
}

