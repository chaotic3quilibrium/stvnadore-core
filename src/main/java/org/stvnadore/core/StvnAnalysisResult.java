package org.stvnadore.core;

import org.jspecify.annotations.NullMarked;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

/**
 * Encapsulates the results of compiling and analyzing an STVN document.
 *
 * @param <V> the type of the value payload (e.g., {@code Optional<StvnValue>})
 * @param <D> the type of the diagnostics payload (e.g., {@code List<StvnDiagnostic>})
 * @param value the compiled value payload
 * @param diagnostics the collection of diagnostics recorded during analysis
 * @since 1.1.0
 */
@NullMarked
public record StvnAnalysisResult<V, D>(
    V value,
    D diagnostics
) {
  /**
   * Validates non-null arguments, creates an immutable defensive copy of the diagnostics list,
   * and enforces the Value-Or-Diagnostics invariant.
   *
   * @param value the compiled value payload
   * @param diagnostics the diagnostics payload
   * @throws NullPointerException if {@code value} or {@code diagnostics} is {@code null}
   * @throws IllegalStateException if {@code diagnostics} is non-empty and {@code value} is present
   */
  @SuppressWarnings("unchecked")
  public StvnAnalysisResult {
    Objects.requireNonNull(value, "value must not be null");
    Objects.requireNonNull(diagnostics, "diagnostics must not be null");

    if (diagnostics instanceof List<?> list) {
      diagnostics = (D) List.copyOf(list);
    }

    if (value instanceof Optional<?> opt && diagnostics instanceof List<?> list) {
      if (!list.isEmpty() && !opt.isEmpty()) {
        throw new IllegalStateException("If diagnostics are not empty, the value must be strictly empty (VOP invariant).");
      }
    }
  }
}
