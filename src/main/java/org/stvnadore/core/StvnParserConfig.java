package org.stvnadore.core;

import org.jspecify.annotations.NullMarked;

/**
 * Configuration options for the STVN parser.
 *
 * @param strict whether strict fail-fast validation is enforced
 * @param maxDiagnostics the maximum number of diagnostics to accumulate before suppression
 * @param systemMode whether system-level authority is enabled to compile internal substrates and snapshots
 * @since 1.2.0
 */
@NullMarked
public record StvnParserConfig(boolean strict, int maxDiagnostics, boolean systemMode) {

  /** Default maximum number of diagnostics accumulated before threshold suppression. */
  public static final int DEFAULT_MAX_DIAGNOSTICS = 100;

  /** Default user-authority accumulating parser configuration. */
  public static final StvnParserConfig DEFAULT = new StvnParserConfig(false, DEFAULT_MAX_DIAGNOSTICS, false);
  /** Fail-fast user-authority parser configuration. */
  public static final StvnParserConfig STRICT = new StvnParserConfig(true, DEFAULT_MAX_DIAGNOSTICS, false);
  /** System-authority accumulating parser configuration for reserved namespaces and domains. */
  public static final StvnParserConfig SYSTEM = new StvnParserConfig(false, DEFAULT_MAX_DIAGNOSTICS, true);
  /** Alias for {@link #SYSTEM} enabling system authority with accumulating diagnostics. */
  public static final StvnParserConfig SYSTEM_DEFAULT = SYSTEM;
  /** System-authority fail-fast parser configuration. */
  public static final StvnParserConfig SYSTEM_STRICT = new StvnParserConfig(true, DEFAULT_MAX_DIAGNOSTICS, true);

  /**
   * Backwards-compatible constructor defaulting maxDiagnostics to 100 and systemMode to false.
   *
   * @param strict whether strict fail-fast validation is enforced
   */
  public StvnParserConfig(boolean strict) {
    this(strict, DEFAULT_MAX_DIAGNOSTICS, false);
  }

  /**
   * Backwards-compatible constructor defaulting systemMode to false.
   *
   * @param strict whether strict fail-fast validation is enforced
   * @param maxDiagnostics the maximum number of diagnostics to accumulate before suppression
   */
  public StvnParserConfig(boolean strict, int maxDiagnostics) {
    this(strict, maxDiagnostics, false);
  }

  /**
   * Returns whether system-level authority is enabled for reserved namespaces and domains.
   *
   * @return {@code true} when system authority is active
   * @since 2.0.0
   */
  public boolean systemAuthority() {
    return systemMode;
  }
}
