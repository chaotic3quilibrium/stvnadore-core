package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;

import java.io.Serial;

/**
 * Exception thrown when a duplicate map key or duplicate inverted map value is detected.
 *
 * @since 1.0.0
 */
@NullMarked
public class StvnCollectionCollisionException extends RuntimeException {
  @Serial
  private static final long serialVersionUID = 1L;

  /**
   * The start character offset of the duplicate token (inclusive).
   */
  private final int startOffset;

  /**
   * The end character offset of the duplicate token (exclusive).
   */
  private final int endOffset;

  /**
   * Constructs a new StvnCollectionCollisionException with the specified details.
   *
   * @param message     the detail message describing the validation failure
   * @param startOffset the start character offset of the duplicate token (inclusive)
   * @param endOffset   the end character offset of the duplicate token (exclusive)
   */
  public StvnCollectionCollisionException(String message, int startOffset, int endOffset) {
    super(message);
    this.startOffset = startOffset;
    this.endOffset = endOffset;
  }

  /**
   * Returns the start character offset of the duplicate token.
   *
   * @return the start character offset
   */
  public int startOffset() {
    return startOffset;
  }

  /**
   * Returns the end character offset of the duplicate token.
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
