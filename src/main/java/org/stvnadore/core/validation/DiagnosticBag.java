package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Accumulator bag for recording semantic diagnostics during AST compilation and semantic validation.
 * <p>
 * Enforces memory safety by bounding total accumulated diagnostics to a configurable limit
 * to prevent heap exhaustion on massive corrupted documents.
 *
 * @since 1.3.0
 */
@NullMarked
public final class DiagnosticBag {

  /** Error code emitted when a regular expression pattern is syntactically invalid. */
  public static final String ERR_INVALID_REGEX = "INVALID_REGEX_PATTERN";
  /** Error code emitted when a numeric range has lower bound greater than upper bound. */
  public static final String ERR_INVERTED_RANGE = "INVALID_NUMERIC_RANGE";
  /** Error code emitted when collection element count exceeds declared capacity bounds. */
  public static final String ERR_CAPACITY_OVERFLOW = "CAPACITY_OVERFLOW";
  /** Error code emitted when metadata trait values are incompatible with the target type. */
  public static final String ERR_INCOMPATIBLE_TYPE = "INCOMPATIBLE_METADATA_TYPE";
  /** Error code emitted when mutually exclusive boundary constraints are simultaneously declared. */
  public static final String ERR_MUTUALLY_EXCLUSIVE = "MUTUALLY_EXCLUSIVE_BOUNDS";
  /** Error code emitted when recursive type aliases form a circular self-reference without disjunction. */
  public static final String ERR_CIRCULAR_TYPE = "CIRCULAR_TYPE_DEFINITION";
  /** Error code emitted when duplicate type identifiers are declared in the same scope. */
  public static final String ERR_DUPLICATE_DEF = "DUPLICATE_TYPE_DEFINITION";
  /** Error code emitted when a declared module include file cannot be located or read. */
  public static final String ERR_MODULE_IMPORT = "MODULE_IMPORT_FAILED";
  /** Error code emitted when cyclic module include dependencies are detected. */
  public static final String ERR_CYCLIC_MODULE = "CYCLIC_MODULE_INCLUDE";
  /** Error code emitted when namespace prefix collisions occur across included modules. */
  public static final String ERR_NAMESPACE_COLLISION = "NAMESPACE_COLLISION";
  /** Error code emitted when a referenced type identifier has not been defined. */
  public static final String ERR_UNDEFINED_TYPE = "UNDEFINED_TYPE";
  /** Error code emitted when algebraic sum type branches share duplicate tag identifiers. */
  public static final String ERR_SUM_TYPE_COLLISION = "SUM_TYPE_TAG_COLLISION";
  /** Error code emitted when structural trait constraints are violated. */
  public static final String ERR_TRAIT_VIOLATION = "TRAIT_VIOLATION";
  /** Error code emitted when a schema definition contains malformed syntax or illegal nesting. */
  public static final String ERR_MALFORMED_SCHEMA = "MALFORMED_SCHEMA";

  private final int maxDiagnostics;
  private final List<StvnDiagnostic> diagnostics;
  private boolean thresholdExceeded = false;

  /**
   * Constructs a DiagnosticBag with a custom maximum diagnostic threshold capacity.
   *
   * @param maxDiagnostics the maximum number of diagnostics to accumulate before suppression
   */
  public DiagnosticBag(int maxDiagnostics) {
    this.maxDiagnostics = Math.max(1, maxDiagnostics);
    this.diagnostics = new ArrayList<>();
  }

  /**
   * Constructs a DiagnosticBag with the default threshold capacity of 100 diagnostics.
   */
  public DiagnosticBag() {
    this(100);
  }

  /**
   * Records a diagnostic into the bag.
   * <p>
   * If the number of accumulated diagnostics reaches {@code maxDiagnostics}, further diagnostics
   * are suppressed and a single sentinel warning diagnostic is appended.
   *
   * @param diagnostic the diagnostic to record
   * @return {@code true} if the diagnostic was accepted, {@code false} if suppressed due to threshold
   */
  public boolean add(StvnDiagnostic diagnostic) {
    if (diagnostics.size() >= maxDiagnostics) {
      if (!thresholdExceeded) {
        thresholdExceeded = true;
        diagnostics.add(new StvnDiagnostic(
            "Diagnostic threshold limit (" + maxDiagnostics + ") reached. Further diagnostics suppressed.",
            DiagnosticSeverity.WARNING,
            diagnostic.line(),
            diagnostic.column(),
            diagnostic.startOffset(),
            diagnostic.endOffset(),
            null,
            Optional.of("STVN_DIAG_LIMIT_EXCEEDED")
        ));
      }
      return false;
    }
    diagnostics.add(diagnostic);
    return true;
  }

  /**
   * Helper method to record an error-level diagnostic with exact coordinates.
   *
   * @param message     the error message
   * @param startOffset the 0-based character start offset
   * @param endOffset   the 0-based character end offset
   * @param line        the 1-based source line index
   * @param column      the 0-based character column offset
   * @param cause       the underlying throwable cause, or {@code null}
   * @param errorCode   the optional standardized error code identifier, or {@code null}
   */
  public void addError(
      String message,
      int startOffset,
      int endOffset,
      int line,
      int column,
      @Nullable Throwable cause,
      @Nullable String errorCode
  ) {
    Throwable effectiveCause = cause;
    if (effectiveCause == null) {
      if (DiagnosticBag.ERR_DUPLICATE_DEF.equals(errorCode)) {
        effectiveCause = new IllegalStateException(message);
      } else if (DiagnosticBag.ERR_CIRCULAR_TYPE.equals(errorCode)) {
        effectiveCause = new StvnTypeResolver.CircularReferenceException(message);
      } else if (DiagnosticBag.ERR_CYCLIC_MODULE.equals(errorCode)) {
        effectiveCause = new CyclicDependencyException(message, java.util.List.of(), java.util.List.of());
      } else if (DiagnosticBag.ERR_NAMESPACE_COLLISION.equals(errorCode)) {
        effectiveCause = new NamespaceCollisionException(message);
      } else {
        effectiveCause = new MalformedSchemaException(message, startOffset, endOffset);
      }
    } else if (cause instanceof java.util.regex.PatternSyntaxException) {
      effectiveCause = new MalformedSchemaException(message, startOffset, endOffset, cause);
    }

    add(new StvnDiagnostic(
        message,
        DiagnosticSeverity.ERROR,
        line,
        column,
        startOffset,
        endOffset,
        effectiveCause,
        Optional.ofNullable(errorCode)
    ));
  }

  /**
   * Helper method to record an error-level diagnostic with offset span and error code.
   *
   * @param message     the error message
   * @param startOffset the 0-based character start offset
   * @param endOffset   the 0-based character end offset
   * @param cause       the underlying throwable cause, or {@code null}
   * @param errorCode   the optional standardized error code identifier, or {@code null}
   */
  public void addError(
      String message,
      int startOffset,
      int endOffset,
      @Nullable Throwable cause,
      @Nullable String errorCode
  ) {
    addError(message, startOffset, endOffset, -1, -1, cause, errorCode);
  }

  /**
   * Checks if any accumulated diagnostic has {@link DiagnosticSeverity#ERROR}.
   *
   * @return {@code true} if any error diagnostic is present
   */
  public boolean hasErrors() {
    return diagnostics.stream().anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR);
  }

  /**
   * Checks if any accumulated diagnostic has {@link DiagnosticSeverity#WARNING}.
   *
   * @return {@code true} if any warning diagnostic is present
   */
  public boolean hasWarnings() {
    return diagnostics.stream().anyMatch(d -> d.severity() == DiagnosticSeverity.WARNING);
  }

  /**
   * Returns an unmodifiable snapshot list of all accumulated diagnostics.
   *
   * @return the list of diagnostics; empty list if none were recorded
   */
  public List<StvnDiagnostic> toList() {
    return diagnostics.isEmpty() ? Collections.emptyList() : List.copyOf(diagnostics);
  }

  /**
   * Returns the count of accumulated diagnostics.
   *
   * @return total diagnostics count
   */
  public int size() {
    return diagnostics.size();
  }

  /**
   * Checks if no diagnostics were recorded.
   *
   * @return {@code true} if empty
   */
  public boolean isEmpty() {
    return diagnostics.isEmpty();
  }

  /**
   * Checks if the diagnostic threshold limit was exceeded.
   *
   * @return {@code true} if threshold was breached
   */
  public boolean isThresholdExceeded() {
    return thresholdExceeded;
  }
}
