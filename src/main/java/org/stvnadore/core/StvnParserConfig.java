package org.stvnadore.core;

import org.jspecify.annotations.NullMarked;

/**
 * Configuration options for the STVN parser.
 *
 * @param strict whether strict fail-fast validation is enforced
 * @param maxDiagnostics the maximum number of diagnostics to accumulate before suppression
 * @since 1.2.0
 */
@NullMarked
public record StvnParserConfig(boolean strict, int maxDiagnostics) {

  public static final int DEFAULT_MAX_DIAGNOSTICS = 100;

  public static final StvnParserConfig DEFAULT = new StvnParserConfig(false, DEFAULT_MAX_DIAGNOSTICS);
  public static final StvnParserConfig STRICT = new StvnParserConfig(true, DEFAULT_MAX_DIAGNOSTICS);

  /**
   * Backwards-compatible constructor defaulting maxDiagnostics to 100.
   *
   * @param strict whether strict fail-fast validation is enforced
   */
  public StvnParserConfig(boolean strict) {
    this(strict, DEFAULT_MAX_DIAGNOSTICS);
  }
}

