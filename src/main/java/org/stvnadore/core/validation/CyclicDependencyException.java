package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;
import java.io.Serial;
import java.util.List;

/**
 * Exception thrown when a circular/cyclic module inclusion dependency is detected.
 */
@NullMarked
public class CyclicDependencyException extends RuntimeException {
  @Serial
  private static final long serialVersionUID = 729103982918392019L;

  private final transient List<String> offendingIncludePathsRaw;
  private final transient List<String> offendingIncludePathsCanonical;

  /**
   * Constructs a new CyclicDependencyException with the specified detail message.
   *
   * @param message the detail message describing the cyclic dependency trace
   */
  public CyclicDependencyException(String message) {
    super(message);
    this.offendingIncludePathsRaw = List.of();
    this.offendingIncludePathsCanonical = List.of();
  }

  /**
   * Constructs a new CyclicDependencyException with the specified detail message and cause.
   *
   * @param message the detail message describing the cyclic dependency trace
   * @param cause   the underlying cause
   */
  public CyclicDependencyException(String message, Throwable cause) {
    super(message, cause);
    this.offendingIncludePathsRaw = List.of();
    this.offendingIncludePathsCanonical = List.of();
  }

  /**
   * Constructs a new CyclicDependencyException with the specified detail message, raw include paths, and canonical include paths.
   *
   * @param message the detail message describing the cyclic dependency trace
   * @param offendingIncludePathsRaw the raw inclusion path strings
   * @param offendingIncludePathsCanonical the canonical resolved path strings
   */
  public CyclicDependencyException(
      String message,
      List<String> offendingIncludePathsRaw,
      List<String> offendingIncludePathsCanonical) {
    super(message);
    this.offendingIncludePathsRaw = List.copyOf(offendingIncludePathsRaw);
    this.offendingIncludePathsCanonical = List.copyOf(offendingIncludePathsCanonical);
  }

  /**
   * Constructs a new CyclicDependencyException with the specified detail message, cause, raw include paths, and canonical include paths.
   *
   * @param message the detail message describing the cyclic dependency trace
   * @param cause   the underlying cause
   * @param offendingIncludePathsRaw the raw inclusion path strings
   * @param offendingIncludePathsCanonical the canonical resolved path strings
   */
  public CyclicDependencyException(
      String message,
      Throwable cause,
      List<String> offendingIncludePathsRaw,
      List<String> offendingIncludePathsCanonical) {
    super(message, cause);
    this.offendingIncludePathsRaw = List.copyOf(offendingIncludePathsRaw);
    this.offendingIncludePathsCanonical = List.copyOf(offendingIncludePathsCanonical);
  }

  /**
   * Returns a read-only, immutable list of the literal un-parsed import strings exactly
   * as they appeared in the source code.
   * <p>
   * This design provides the consuming client the flexibility to echo back the user's
   * original text for local context reporting.
   *
   * @return a read-only, immutable list of raw inclusion path strings
   */
  public List<String> getOffendingIncludePathsRaw() {
    return offendingIncludePathsRaw;
  }

  /**
   * Returns a read-only, immutable list of fully normalized, unique absolute paths
   * used by the compiler environment to identify the dependency loop.
   * <p>
   * This design provides the consuming client the flexibility to surface exact
   * system-level file diagnostics.
   *
   * @return a read-only, immutable list of canonical resolved path strings
   */
  public List<String> getOffendingIncludePathsCanonical() {
    return offendingIncludePathsCanonical;
  }
}

