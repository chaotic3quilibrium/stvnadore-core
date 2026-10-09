package org.stvnadore.core.ast;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;

import java.util.Optional;

/**
 * Enumeration of normative document kinds defining structural capabilities and extension mappings.
 *
 * @since 2.0.0
 */
@NullMarked
public enum StvnDocumentKind {

  /** Textual canonical hermetic document body (.stvn) or binary body (.stvn_b). */
  BODY(StvnVocabulary.KIND_KW_BODY, ".stvn"),

  /** Textual hermetic definitions schema (.stvn_d) or binary definitions (.stvn_bd). */
  DEFS(StvnVocabulary.KIND_KW_DEFS, ".stvn_d"),

  /** Textual modular document body with optional includes (.stvn_i). */
  BODY_INCLUDE(StvnVocabulary.KIND_KW_BODY_INCLUDE, ".stvn_i"),

  /** Textual modular definitions schema module with includes (.stvn_di). */
  DEFS_INCLUDE(StvnVocabulary.KIND_KW_DEFS_INCLUDE, ".stvn_di");

  private final String keyword;
  private final String primaryExtension;

  StvnDocumentKind(String keyword, String primaryExtension) {
    this.keyword = keyword;
    this.primaryExtension = primaryExtension;
  }

  /**
   * Returns the canonical STVN token keyword representing this document kind (e.g., {@code #BODY}).
   *
   * @return the STVN token keyword
   */
  public String keyword() {
    return keyword;
  }

  /**
   * Returns the primary physical file extension associated with this document kind (e.g., {@code .stvn}).
   *
   * @return the primary physical file extension
   */
  public String primaryExtension() {
    return primaryExtension;
  }

  /**
   * Resolves a document kind from its canonical keyword token.
   *
   * @param keyword the STVN keyword string to inspect
   * @return an {@link Optional} containing the matched {@link StvnDocumentKind}, or empty if not matched
   */
  public static Optional<StvnDocumentKind> fromKeyword(String keyword) {
    for (StvnDocumentKind kind : values()) {
      if (kind.keyword.equals(keyword)) {
        return Optional.of(kind);
      }
    }
    return Optional.empty();
  }

  /**
   * Resolves a document kind from its physical file extension.
   *
   * @param extension the physical file extension including leading dot
   * @return an {@link Optional} containing the matched {@link StvnDocumentKind}, or empty if not recognized
   */
  public static Optional<StvnDocumentKind> fromExtension(String extension) {
    return switch (extension) {
      case ".stvn", ".stvn_b" -> Optional.of(BODY);
      case ".stvn_d", ".stvn_bd" -> Optional.of(DEFS);
      case ".stvn_i" -> Optional.of(BODY_INCLUDE);
      case ".stvn_di" -> Optional.of(DEFS_INCLUDE);
      default -> Optional.empty();
    };
  }
}
