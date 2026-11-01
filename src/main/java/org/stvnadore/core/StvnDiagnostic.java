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
 * span coordinates {@code [startOffset, endOffset)} and the POSIX-normalized originating compilation
 * unit path via {@link #sourcePath()}.
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
 * @param sourcePath    the optional POSIX-normalized file or virtual-workspace path of the compilation unit
 *                      that originated this diagnostic ({@link Optional#empty()} for anonymous in-memory inputs)
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
    Optional<String> symbolicId,
    Optional<String> sourcePath
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
   * {@code symbolicId}, and {@code sourcePath} are non-null, and normalizes non-empty {@code sourcePath}
   * values via {@link StvnSchemaFlattener#normalizePath(String)}.
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
   * @param sourcePath    the originating compilation unit path optional container
   * @throws NullPointerException if any non-nullable reference argument is {@code null}
   */
  public StvnDiagnostic {
    Objects.requireNonNull(message, "message must not be null");
    Objects.requireNonNull(severity, "severity must not be null");
    Objects.requireNonNull(errorCode, "errorCode must not be null");
    Objects.requireNonNull(canonicalCode, "canonicalCode must not be null");
    Objects.requireNonNull(symbolicId, "symbolicId must not be null");
    Objects.requireNonNull(sourcePath, "sourcePath must not be null");
    sourcePath = sourcePath
        .filter(p -> !p.isEmpty())
        .map(StvnSchemaFlattener::normalizePath)
        .filter(p -> !p.isEmpty());
  }

  /**
   * Constructs a diagnostic without an explicit originating {@code sourcePath}, defaulting
   * {@link #sourcePath()} to {@link Optional#empty()}.
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
  public StvnDiagnostic(
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
    this(
        message,
        severity,
        line,
        column,
        startOffset,
        endOffset,
        cause,
        errorCode,
        canonicalCode,
        symbolicId,
        Optional.empty()
    );
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
        isCanonicalDiagnosticCode(errorCode) ? Optional.empty() : errorCode,
        Optional.empty()
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
        symbolicId,
        Optional.empty()
    );
  }

  /**
   * Returns a copy of this diagnostic bound to the specified compilation unit {@code sourcePath}
   * (normalized via {@link StvnSchemaFlattener#normalizePath(String)}, or {@link Optional#empty()}
   * when {@code unitSourcePath} is {@code null} or empty).
   *
   * @param unitSourcePath the raw or normalized originating file path, or {@code null}
   * @return an immutable {@link StvnDiagnostic} carrying the normalized {@code sourcePath}
   * @since 2.0.0
   */
  public StvnDiagnostic withSourcePath(@Nullable String unitSourcePath) {
    Optional<String> normalized = (unitSourcePath == null || unitSourcePath.isEmpty())
        ? Optional.empty()
        : Optional.of(StvnSchemaFlattener.normalizePath(unitSourcePath)).filter(p -> !p.isEmpty());
    if (Objects.equals(this.sourcePath, normalized)) {
      return this;
    }
    return new StvnDiagnostic(
        message,
        severity,
        line,
        column,
        startOffset,
        endOffset,
        cause,
        errorCode,
        canonicalCode,
        symbolicId,
        normalized
    );
  }

  private static boolean isCanonicalDiagnosticCode(Optional<String> code) {
    return code.isPresent() && CANONICAL_CODE_PATTERN.matcher(code.get()).matches();
  }
}
