package org.stvnadore.core;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Monadic container representing the result of compiling an STVN source document.
 * <p>
 * Encapsulates the accumulated diagnostic collection alongside an optional parsed/validated AST.
 * Supports partial AST recovery where {@link #document()} is present even in the presence of recoverable
 * semantic errors.
 *
 * @param <T> the type of the compiled AST payload (typically {@link org.stvnadore.core.ir.StvnValue})
 * @param document              the compiled AST (clean or partial), or empty on unrecoverable syntax failure
 * @param diagnostics           the immutable list of all recorded diagnostics
 * @param isRecoveredPartialAst true if the document was generated via partial error recovery
 * @since 1.3.0
 */
@NullMarked
public record StvnCompilationResult<T>(
    Optional<T> document,
    List<StvnDiagnostic> diagnostics,
    boolean isRecoveredPartialAst
) {

  /**
   * Canonical constructor validating that document and diagnostics are non-null.
   *
   * @param document              the compiled AST payload
   * @param diagnostics           the list of diagnostics
   * @param isRecoveredPartialAst true if the AST was constructed via error recovery
   * @throws NullPointerException if {@code document} or {@code diagnostics} is {@code null}
   */
  public StvnCompilationResult {
    Objects.requireNonNull(document, "document must not be null");
    Objects.requireNonNull(diagnostics, "diagnostics must not be null");
    diagnostics = List.copyOf(diagnostics);
  }

  /**
   * Constructs a successful compilation result with zero diagnostics.
   *
   * @param value the successfully compiled AST value
   * @param <T> the value type
   * @return a successful compilation result
   */
  public static <T> StvnCompilationResult<T> success(T value) {
    return new StvnCompilationResult<>(Optional.of(value), List.of(), false);
  }

  /**
   * Constructs a successful compilation result for an empty document body with zero diagnostics.
   *
   * @param <T> the value type
   * @return an empty compilation result
   */
  public static <T> StvnCompilationResult<T> empty() {
    return new StvnCompilationResult<>(Optional.empty(), List.of(), false);
  }

  /**
   * Constructs a failed compilation result with unrecoverable errors and no AST payload.
   *
   * @param diagnostics the list of recorded diagnostics
   * @param <T> the value type
   * @return a failure compilation result
   */
  public static <T> StvnCompilationResult<T> failure(List<StvnDiagnostic> diagnostics) {
    return new StvnCompilationResult<>(Optional.empty(), diagnostics, false);
  }

  /**
   * Constructs a partial compilation result containing a recovered AST alongside recorded diagnostics.
   *
   * @param partialAst  the partially recovered AST node tree
   * @param diagnostics the list of accumulated diagnostics
   * @param <T> the value type
   * @return a partial recovery compilation result
   */
  public static <T> StvnCompilationResult<T> partial(T partialAst, List<StvnDiagnostic> diagnostics) {
    return new StvnCompilationResult<>(Optional.of(partialAst), diagnostics, true);
  }

  /**
   * Returns true if compilation succeeded with zero diagnostic errors and a present document.
   *
   * @return {@code true} if compilation succeeded cleanly
   */
  public boolean isSuccess() {
    return !hasErrors() && document.isPresent();
  }

  /**
   * Returns true if any accumulated diagnostic has severity {@link DiagnosticSeverity#ERROR}.
   *
   * @return {@code true} if any error exists
   */
  public boolean hasErrors() {
    return diagnostics.stream().anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR);
  }

  /**
   * Returns true if any accumulated diagnostic has severity {@link DiagnosticSeverity#WARNING}.
   *
   * @return {@code true} if any warning exists
   */
  public boolean hasWarnings() {
    return diagnostics.stream().anyMatch(d -> d.severity() == DiagnosticSeverity.WARNING);
  }

  /**
   * Unwraps the compiled AST document, or throws a {@link RuntimeException} encapsulating all diagnostics.
   *
   * @return the non-null compiled AST value
   * @throws RuntimeException if compilation failed or document is empty
   */
  public T orElseThrow() {
    if (hasErrors() || document.isEmpty()) {
      String summary = diagnostics.isEmpty() ? "Empty document body" : diagnostics.getFirst().message();
      Throwable cause = diagnostics.isEmpty() ? null : diagnostics.getFirst().cause();
      throw new RuntimeException("STVN Compilation failed (" + diagnostics.size() + " diagnostics): " + summary, cause);
    }
    return document.get();
  }

  /**
   * Monadic map transforming the inner AST payload if present.
   *
   * @param mapper mapping function
   * @param <U> target type
   * @return mapped compilation result
   */
  public <U> StvnCompilationResult<U> map(Function<? super T, ? extends U> mapper) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    return new StvnCompilationResult<>(
        document.map(mapper),
        diagnostics,
        isRecoveredPartialAst
    );
  }

  /**
   * Executes the consumer if compilation was successful with zero errors.
   *
   * @param action the consumer action
   */
  public void ifSuccess(Consumer<? super T> action) {
    Objects.requireNonNull(action, "action must not be null");
    if (isSuccess()) {
      document.ifPresent(action);
    }
  }
}
