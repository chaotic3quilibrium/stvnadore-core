package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;

import java.io.Serial;

/**
 * Exception thrown when a literal value does not conform to the expected format
 * or casing constraints of its schema (e.g., malformed boolean literal casing).
 *
 * @since 1.0.0
 */
@NullMarked
public class StvnMalformedLiteralException extends RuntimeException {
  @Serial
  private static final long serialVersionUID = 1L;

  /** The start character offset of the malformed token. */
  private final int startOffset;
  /** The end character offset of the malformed token. */
  private final int endOffset;

  /**
   * Constructs a new StvnMalformedLiteralException with the specified details.
   *
   * @param message     the detail message describing the validation failure
   * @param startOffset the start character offset of the duplicate token (inclusive)
   * @param endOffset   the end character offset of the duplicate token (exclusive)
   */
  public StvnMalformedLiteralException(String message, int startOffset, int endOffset) {
    super(message);
    this.startOffset = startOffset;
    this.endOffset = endOffset;
  }

  /**
   * Returns the start character offset of the malformed token.
   *
   * @return the start character offset
   */
  public int startOffset() {
    return startOffset;
  }

  /**
   * Returns the end character offset of the malformed token.
   *
   * @return the end character offset
   */
  public int endOffset() {
    return endOffset;
  }

  @Override
  public String getMessage() {
    return super.getMessage();
  }
}
