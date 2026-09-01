package org.stvnadore.core.validation;

import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Custom parser cancellation exception carrying precise source location metadata.
 *
 * @since 1.2.0
 */
@NullMarked
public final class StvnSyntaxCancellationException extends ParseCancellationException {
  private static final long serialVersionUID = 1L;

  /** The 1-based line number of the syntax error. */
  private final int line;
  /** The 0-based column number of the syntax error. */
  private final int column;
  /** The start character offset of the syntax error. */
  private final int startOffset;
  /** The end character offset of the syntax error. */
  private final int endOffset;

  /**
   * Constructs a new StvnSyntaxCancellationException with the specified details.
   *
   * @param message     the detail message describing the syntax error
   * @param line        the 1-based line number
   * @param column      the 0-based column number
   * @param startOffset the start character offset
   * @param endOffset   the end character offset
   * @param cause       the underlying cause of the syntax error
   */
  public StvnSyntaxCancellationException(
      String message,
      int line,
      int column,
      int startOffset,
      int endOffset,
      @Nullable Throwable cause
  ) {
    super(message, cause);
    this.line = line;
    this.column = column;
    this.startOffset = startOffset;
    this.endOffset = endOffset;
  }

  /**
   * Returns the 1-based line number of the syntax error.
   *
   * @return the line number
   */
  public int getLine() {
    return line;
  }

  /**
   * Returns the 0-based column number of the syntax error.
   *
   * @return the column number
   */
  public int getColumn() {
    return column;
  }

  /**
   * Returns the start character offset of the syntax error.
   *
   * @return the start character offset
   */
  public int getStartOffset() {
    return startOffset;
  }

  /**
   * Returns the end character offset of the syntax error.
   *
   * @return the end character offset
   */
  public int getEndOffset() {
    return endOffset;
  }
}
