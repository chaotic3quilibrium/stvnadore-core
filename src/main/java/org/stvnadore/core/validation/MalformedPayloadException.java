package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;

import java.io.Serial;

/**
 * Exception thrown when an STVN payload contains a value that violates the types, constraints, or
 * structural validation rules defined by its schema.
 * <p>
 * This exception can occur during text parsing, runtime AST compilation, mapping, or binary decoding
 * (e.g., when a null value violates a non-null constraint, or a map/set is not sorted/sequenced).
 *
 * @since 1.0.0
 */
@NullMarked
public class MalformedPayloadException extends RuntimeException {
  @Serial
  private static final long serialVersionUID = -694183103400467077L;

  /** The start character offset of the error (inclusive). */
  private final int startOffset;
  /** The end character offset of the error (exclusive). */
  private final int endOffset;

  /**
   * Constructs a new MalformedPayloadException with the specified detail message.
   *
   * @param message the detail message describing the validation failure
   */
  public MalformedPayloadException(String message) {
    super(message);
    this.startOffset = -1;
    this.endOffset = -1;
  }

  /**
   * Constructs a new MalformedPayloadException with the specified detail message and cause.
   *
   * @param message the detail message describing the validation failure
   * @param cause   the underlying cause of the validation failure
   */
  public MalformedPayloadException(String message, Throwable cause) {
    super(message, cause);
    this.startOffset = -1;
    this.endOffset = -1;
  }

  /**
   * Constructs a new MalformedPayloadException with the specified detail message and coordinates.
   *
   * @param message     the detail message describing the validation failure
   * @param startOffset the start character offset (inclusive)
   * @param endOffset   the end character offset (exclusive)
   */
  public MalformedPayloadException(String message, int startOffset, int endOffset) {
    super(message);
    this.startOffset = startOffset;
    this.endOffset = endOffset;
  }

  /**
   * Constructs a new MalformedPayloadException with the specified detail message, cause, and coordinates.
   *
   * @param message     the detail message describing the validation failure
   * @param startOffset the start character offset (inclusive)
   * @param endOffset   the end character offset (exclusive)
   * @param cause       the underlying cause of the validation failure
   */
  public MalformedPayloadException(String message, int startOffset, int endOffset, Throwable cause) {
    super(message, cause);
    this.startOffset = startOffset;
    this.endOffset = endOffset;
  }

  /**
   * Returns the start character offset.
   *
   * @return the start character offset
   */
  public int startOffset() {
    return startOffset;
  }

  /**
   * Returns the end character offset.
   *
   * @return the end character offset
   */
  public int endOffset() {
    return endOffset;
  }
}
