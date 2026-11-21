package org.stvnadore.core.utils;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * Deterministic {@code ij_stvnadore_core} module basedir resolver for snapshot generators,
 * metadata fixture emitters, and file-backed JUnit 5 conformance suites.
 * <p>
 * Resolves the canonical {@code ij_stvnadore_core} module directory in strict priority order:
 * <ol>
 *   <li>System property {@code -Dstvn.project.basedir} when set and non-blank.</li>
 *   <li>{@code <workingDirectory>} when {@code shared-fixtures/syntax} exists directly beneath it.</li>
 *   <li>{@code <workingDirectory>/ij_stvnadore_core} when {@code ij_stvnadore_core/shared-fixtures/syntax}
 *       exists beneath the multi-repository workspace root.</li>
 * </ol>
 * Every resolution path validates that {@code shared-fixtures/syntax} exists as a directory at the
 * resolved root and fails fast with an explicit STE {@link IllegalStateException} otherwise,
 * preventing any generator from creating a stray {@code shared-fixtures} directory in an ancestor
 * or unrelated working directory.
 */
@NullMarked
public final class StvnProjectPaths {

  /** System property injected by Maven {@code exec-maven-plugin} and {@code maven-surefire-plugin}. */
  public static final String BASEDIR_PROPERTY = "stvn.project.basedir";

  /** Canonical directory name of the core repository inside the multi-repository workspace root. */
  public static final String MODULE_DIRECTORY_NAME = "ij_stvnadore_core";

  private static final Path SHARED_FIXTURES_SUBPATH = Path.of("shared-fixtures");
  private static final Path SYNTAX_SENTINEL_SUBPATH = SHARED_FIXTURES_SUBPATH.resolve("syntax");

  private StvnProjectPaths() {
    // Non-instantiable utility class
  }

  /**
   * Resolves and validates the canonical {@code ij_stvnadore_core} module root directory using
   * {@link #BASEDIR_PROPERTY} and the current JVM working directory.
   *
   * @return normalized absolute path to {@code ij_stvnadore_core}
   * @throws IllegalStateException if no valid module root containing {@code shared-fixtures/syntax} is found
   */
  public static Path resolveProjectBasedir() {
    return resolveProjectBasedir(
        System.getProperty(BASEDIR_PROPERTY),
        Paths.get("").toAbsolutePath().normalize()
    );
  }

  /**
   * Pure, side-effect-free basedir resolution entry point parameterized for deterministic unit testing.
   *
   * @param configuredBasedir optional {@code stvn.project.basedir} property value
   * @param workingDirectory  fallback working directory to inspect when {@code configuredBasedir} is unset or blank
   * @return normalized absolute path to {@code ij_stvnadore_core}
   * @throws IllegalStateException if {@code shared-fixtures/syntax} does not exist at the resolved root
   */
  public static Path resolveProjectBasedir(@Nullable String configuredBasedir, Path workingDirectory) {
    Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
    Path normalizedWorkingDir = workingDirectory.toAbsolutePath().normalize();

    if (configuredBasedir != null && !configuredBasedir.isBlank()) {
      String trimmed = configuredBasedir.trim();
      Path candidateRoot = normalizedWorkingDir.resolve(Paths.get(trimmed)).toAbsolutePath().normalize();
      Path sentinelDir = candidateRoot.resolve(SYNTAX_SENTINEL_SUBPATH);
      if (!Files.isDirectory(sentinelDir)) {
        throw new IllegalStateException(
            "Invalid '" + MODULE_DIRECTORY_NAME + "' module root '" + candidateRoot
                + "' from system property '-D" + BASEDIR_PROPERTY + "=" + trimmed
                + "': mandatory directory 'shared-fixtures/syntax' does not exist at '" + sentinelDir + "'."
        );
      }
      return candidateRoot;
    }

    Path directSentinel = normalizedWorkingDir.resolve(SYNTAX_SENTINEL_SUBPATH);
    if (Files.isDirectory(directSentinel)) {
      return normalizedWorkingDir;
    }

    Path siblingModuleRoot = normalizedWorkingDir.resolve(MODULE_DIRECTORY_NAME).normalize();
    Path siblingSentinel = siblingModuleRoot.resolve(SYNTAX_SENTINEL_SUBPATH);
    if (Files.isDirectory(siblingSentinel)) {
      return siblingModuleRoot;
    }

    throw new IllegalStateException(
        "Cannot resolve '" + MODULE_DIRECTORY_NAME + "' module root from working directory '"
            + normalizedWorkingDir
            + "': mandatory directory 'shared-fixtures/syntax' does not exist at '"
            + directSentinel + "' or '" + siblingSentinel
            + "'; specify '-D" + BASEDIR_PROPERTY + "=<path-to-" + MODULE_DIRECTORY_NAME + ">'."
    );
  }

  /**
   * Resolves a module-relative path against the validated {@code ij_stvnadore_core} module root.
   *
   * @param first first relative path segment (or slash-delimited relative path)
   * @param more  additional relative path segments
   * @return normalized absolute path anchored at {@code ij_stvnadore_core}
   */
  public static Path resolve(String first, String... more) {
    Objects.requireNonNull(first, "first must not be null");
    Objects.requireNonNull(more, "more must not be null");
    return resolveProjectBasedir().resolve(Paths.get(first, more)).normalize();
  }

  /**
   * Resolves a module-relative {@link Path} against the validated {@code ij_stvnadore_core} module root.
   *
   * @param relativeSubpath module-relative path
   * @return normalized absolute path anchored at {@code ij_stvnadore_core}
   */
  public static Path resolve(Path relativeSubpath) {
    Objects.requireNonNull(relativeSubpath, "relativeSubpath must not be null");
    return resolveProjectBasedir().resolve(relativeSubpath).normalize();
  }

  /**
   * Returns the validated {@code ij_stvnadore_core/shared-fixtures} root directory.
   *
   * @return normalized absolute path to {@code shared-fixtures}
   */
  public static Path sharedFixturesRoot() {
    return resolveProjectBasedir().resolve(SHARED_FIXTURES_SUBPATH).normalize();
  }
}
