package org.stvnadore.core.validation;

import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Custom parser cancellation exception carrying precise source location metadata
 * and STVN 2.0.0 dual diagnostic identifiers.
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
  /** The optional normative STVN 2.0.0 canonical code (E001-E040). */
  private final @Nullable String canonicalCode;
  /** The optional symbolic diagnostic identifier (ERR_*). */
  private final @Nullable String symbolicId;

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
    this(message, line, column, startOffset, endOffset, cause, null, null);
  }

  /**
   * Constructs a new StvnSyntaxCancellationException carrying dual STVN 2.0.0 diagnostic codes.
   *
   * @param message       the detail message describing the syntax error
   * @param line          the 1-based line number
   * @param column        the 0-based column number
   * @param startOffset   the start character offset
   * @param endOffset     the end character offset
   * @param cause         the underlying cause of the syntax error
   * @param canonicalCode the normative canonical code, or {@code null}
   * @param symbolicId    the symbolic diagnostic identifier, or {@code null}
   * @since 2.0.0
   */
  public StvnSyntaxCancellationException(
      String message,
      int line,
      int column,
      int startOffset,
      int endOffset,
      @Nullable Throwable cause,
      @Nullable String canonicalCode,
      @Nullable String symbolicId
  ) {
    super(message, cause);
    this.line = line;
    this.column = column;
    this.startOffset = startOffset;
    this.endOffset = endOffset;
    this.canonicalCode = canonicalCode;
    this.symbolicId = symbolicId;
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

  /**
   * Returns the normative STVN 2.0.0 canonical diagnostic code, if assigned.
   *
   * @return the canonical code ({@code E001}–{@code E040}), or {@code null}
   * @since 2.0.0
   */
  public @Nullable String getCanonicalCode() {
    return canonicalCode;
  }

  /**
   * Returns the symbolic diagnostic identifier, if assigned.
   *
   * @return the symbolic identifier ({@code ERR_*}), or {@code null}
   * @since 2.0.0
   */
  public @Nullable String getSymbolicId() {
    return symbolicId;
  }
}
