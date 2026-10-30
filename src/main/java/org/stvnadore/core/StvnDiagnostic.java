package org.stvnadore.core;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Represents a syntax or semantic diagnostic message discovered during STVN 2.0.0 compilation.
 * <p>
 * Carries both the canonical alphanumeric specification code ({@code E001}–{@code E040},
 * {@code W001}–{@code W003}) via {@link #canonicalCode()} and the symbolic diagnostic identifier
 * ({@code ERR_*} / {@code WARN_*}) via {@link #symbolicId()}, alongside exact half-open character
 * span coordinates {@code [startOffset, endOffset)}.
 *
 * @param message       the descriptive diagnostic message free of parser-generator jargon
 * @param severity      the diagnostic severity classification
 * @param line          the 1-based source line index where the diagnostic occurred, or {@code -1} if unlocated
 * @param column        the 0-based character column offset in the source line, or {@code -1} if unlocated
 * @param startOffset   the 0-based inclusive character start offset in the source stream, or {@code -1} if unlocated
 * @param endOffset     the 0-based exclusive character end offset in the source stream, or {@code -1} if unlocated
 * @param cause         the underlying exception that triggered the diagnostic, or {@code null}
 * @param errorCode     the primary diagnostic code identifier (populated from {@code symbolicId} or {@code canonicalCode})
 * @param canonicalCode the optional canonical specification code ({@code E001}–{@code E040}, {@code W001}–{@code W003})
 * @param symbolicId    the optional symbolic diagnostic identifier ({@code ERR_*} or {@code WARN_*})
 * @since 2.0.0
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
    Optional<String> errorCode,
    Optional<String> canonicalCode,
    Optional<String> symbolicId
) {

  private static final Pattern CANONICAL_CODE_PATTERN = Pattern.compile("^[EW][0-9]{3}$");

  /**
   * Diagnostic severity classifications.
   *
   * @since 2.0.0
   */
  public enum DiagnosticSeverity {
    /** Fatal error that prevents compilation or validation from succeeding. */
    ERROR,
    /** Non-fatal warning indicating redundant metadata facets or structural advisories. */
    WARNING,
    /** Informational diagnostic conveying contextual analysis details. */
    INFO,
    /** Editor hint offering suggested quick-fixes or canonicalization actions. */
    HINT
  }

  /**
   * Validates that {@code message}, {@code severity}, {@code errorCode}, {@code canonicalCode},
   * and {@code symbolicId} are non-null.
   *
   * @param message       the descriptive diagnostic message
   * @param severity      the diagnostic severity classification
   * @param line          the 1-based source line index
   * @param column        the 0-based character column offset
   * @param startOffset   the 0-based inclusive character start offset
   * @param endOffset     the 0-based exclusive character end offset
   * @param cause         the underlying failure cause, or {@code null}
   * @param errorCode     the primary diagnostic code optional container
   * @param canonicalCode the canonical specification code optional container
   * @param symbolicId    the symbolic diagnostic identifier optional container
   * @throws NullPointerException if any non-nullable reference argument is {@code null}
   */
  public StvnDiagnostic {
    Objects.requireNonNull(message, "message must not be null");
    Objects.requireNonNull(severity, "severity must not be null");
    Objects.requireNonNull(errorCode, "errorCode must not be null");
    Objects.requireNonNull(canonicalCode, "canonicalCode must not be null");
    Objects.requireNonNull(symbolicId, "symbolicId must not be null");
  }

  /**
   * Constructs a fatal error diagnostic at the specified source coordinates with empty diagnostic codes.
   *
   * @param message     the descriptive diagnostic message
   * @param line        the 1-based source line index
   * @param column      the 0-based character column offset
   * @param startOffset the 0-based inclusive character start offset
   * @param endOffset   the 0-based exclusive character end offset
   * @param cause       the underlying failure cause, or {@code null}
   */
  public StvnDiagnostic(
      String message,
      int line,
      int column,
      int startOffset,
      int endOffset,
      @Nullable Throwable cause
  ) {
    this(
        message,
        DiagnosticSeverity.ERROR,
        line,
        column,
        startOffset,
        endOffset,
        cause,
        Optional.empty(),
        Optional.empty(),
        Optional.empty()
    );
  }

  /**
   * Constructs a diagnostic with explicit severity at the specified source coordinates and empty diagnostic codes.
   *
   * @param message     the descriptive diagnostic message
   * @param severity    the diagnostic severity classification
   * @param line        the 1-based source line index
   * @param column      the 0-based character column offset
   * @param startOffset the 0-based inclusive character start offset
   * @param endOffset   the 0-based exclusive character end offset
   * @param cause       the underlying failure cause, or {@code null}
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
    this(
        message,
        severity,
        line,
        column,
        startOffset,
        endOffset,
        cause,
        Optional.empty(),
        Optional.empty(),
        Optional.empty()
    );
  }

  /**
   * Constructs a diagnostic from a single optional code identifier, routing {@code E001}–{@code E040}
   * and {@code W001}–{@code W003} codes to {@link #canonicalCode()} and symbolic identifiers to
   * {@link #symbolicId()}.
   *
   * @param message     the descriptive diagnostic message
   * @param severity    the diagnostic severity classification
   * @param line        the 1-based source line index
   * @param column      the 0-based character column offset
   * @param startOffset the 0-based inclusive character start offset
   * @param endOffset   the 0-based exclusive character end offset
   * @param cause       the underlying failure cause, or {@code null}
   * @param errorCode   the optional single diagnostic code identifier
   */
  public StvnDiagnostic(
      String message,
      DiagnosticSeverity severity,
      int line,
      int column,
      int startOffset,
      int endOffset,
      @Nullable Throwable cause,
      Optional<String> errorCode
  ) {
    this(
        message,
        severity,
        line,
        column,
        startOffset,
        endOffset,
        cause,
        Objects.requireNonNull(errorCode, "errorCode must not be null"),
        isCanonicalDiagnosticCode(errorCode) ? errorCode : Optional.empty(),
        isCanonicalDiagnosticCode(errorCode) ? Optional.empty() : errorCode
    );
  }

  /**
   * Constructs a diagnostic carrying both a canonical specification code ({@code E001}–{@code E040},
   * {@code W001}–{@code W003}) and a symbolic identifier ({@code ERR_*} / {@code WARN_*}).
   *
   * @param message       the descriptive diagnostic message
   * @param severity      the diagnostic severity classification
   * @param line          the 1-based source line index
   * @param column        the 0-based character column offset
   * @param startOffset   the 0-based inclusive character start offset
   * @param endOffset     the 0-based exclusive character end offset
   * @param cause         the underlying failure cause, or {@code null}
   * @param canonicalCode the optional canonical specification code
   * @param symbolicId    the optional symbolic diagnostic identifier
   */
  public StvnDiagnostic(
      String message,
      DiagnosticSeverity severity,
      int line,
      int column,
      int startOffset,
      int endOffset,
      @Nullable Throwable cause,
      Optional<String> canonicalCode,
      Optional<String> symbolicId
  ) {
    this(
        message,
        severity,
        line,
        column,
        startOffset,
        endOffset,
        cause,
        Objects.requireNonNull(symbolicId, "symbolicId must not be null").isPresent()
            ? symbolicId
            : Objects.requireNonNull(canonicalCode, "canonicalCode must not be null"),
        Objects.requireNonNull(canonicalCode, "canonicalCode must not be null"),
        symbolicId
    );
  }

  private static boolean isCanonicalDiagnosticCode(Optional<String> code) {
    return code.isPresent() && CANONICAL_CODE_PATTERN.matcher(code.get()).matches();
  }
}
