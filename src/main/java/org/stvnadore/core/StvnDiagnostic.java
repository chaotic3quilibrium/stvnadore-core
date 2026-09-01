package org.stvnadore.core;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents a syntax or semantic diagnostic message discovered during compilation.
 *
 * @param message the descriptive error or diagnostic message
 * @param severity the diagnostic severity classification
 * @param line the 1-based source line index where the diagnostic occurred, or -1 if unlocated
 * @param column the 0-based character column offset in the source line, or -1 if unlocated
 * @param startOffset the 0-based absolute character start index in the document stream, or -1 if unlocated
 * @param endOffset the 0-based absolute character end index in the document stream, or -1 if unlocated
 * @param cause the underlying exception or parser failure that triggered the diagnostic, or {@code null}
 * @param errorCode the optional standardized diagnostic error code identifier
 * @since 1.1.0
 */
@NullMarked
public record StvnDiagnostic(
    String message,
    DiagnosticSeverity severity,
    int line,
    int column,
    int startOffset,
    int endOffset,
    @Nullable Throwable cause,
    Optional<String> errorCode
) {

  /**
   * Diagnostic severity classifications.
   *
   * @since 1.3.0
   */
  public enum DiagnosticSeverity {
    /** Fatal error that prevents compilation or validation from succeeding. */
    ERROR,
    /** Non-fatal warning indicating semantic discrepancies or deprecated constructs. */
    WARNING,
    /** Informational diagnostic conveying contextual AST insights. */
    INFO,
    /** Editor hint offering suggested quick-fixes or refactoring optimizations. */
    HINT
  }

  /**
   * Validates that the diagnostic message, severity, and errorCode are non-null.
   *
   * @param message the descriptive error or diagnostic message
   * @param severity the diagnostic severity
   * @param line the 1-based source line index
   * @param column the 0-based character column offset
   * @param startOffset the 0-based absolute start offset
   * @param endOffset the 0-based absolute end offset
   * @param cause the underlying exception or failure cause, or {@code null}
   * @param errorCode the optional error code identifier
   * @throws NullPointerException if {@code message}, {@code severity}, or {@code errorCode} is {@code null}
   */
  public StvnDiagnostic {
    Objects.requireNonNull(message, "message must not be null");
    Objects.requireNonNull(severity, "severity must not be null");
    Objects.requireNonNull(errorCode, "errorCode must not be null");
  }

  /**
   * Backwards-compatible constructor defaulting severity to ERROR and empty error code.
   *
   * @param message the descriptive error or diagnostic message
   * @param line the 1-based source line index
   * @param column the 0-based character column offset
   * @param startOffset the 0-based absolute start offset
   * @param endOffset the 0-based absolute end offset
   * @param cause the underlying exception or failure cause, or {@code null}
   */
  public StvnDiagnostic(
      String message,
      int line,
      int column,
      int startOffset,
      int endOffset,
      @Nullable Throwable cause
  ) {
    this(message, DiagnosticSeverity.ERROR, line, column, startOffset, endOffset, cause, Optional.empty());
  }

  /**
   * Backwards-compatible constructor with explicit severity and empty error code.
   *
   * @param message the descriptive error or diagnostic message
   * @param severity the diagnostic severity classification
   * @param line the 1-based source line index
   * @param column the 0-based character column offset
   * @param startOffset the 0-based absolute start offset
   * @param endOffset the 0-based absolute end offset
   * @param cause the underlying exception or failure cause, or {@code null}
   */
  public StvnDiagnostic(
      String message,
      DiagnosticSeverity severity,
      int line,
      int column,
      int startOffset,
      int endOffset,
      @Nullable Throwable cause
  ) {
    this(message, severity, line, column, startOffset, endOffset, cause, Optional.empty());
  }
}

