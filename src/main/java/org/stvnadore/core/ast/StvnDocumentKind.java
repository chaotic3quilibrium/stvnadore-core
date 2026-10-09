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

  /** Textual document body containing payload and optional definitions (.stvn). */
  BODY(StvnVocabulary.KIND_KW_BODY, ".stvn"),

  /** Textual modular definitions schema module (.stvn_d). */
  DEFS(StvnVocabulary.KIND_KW_DEFS, ".stvn_d"),

  /** Textual flattened hermetic document body (.stvn_f) or binary body (.stvn_bf). */
  BODY_FLAT(StvnVocabulary.KIND_KW_BODY_FLAT, ".stvn_f"),

  /** Textual flattened definitions schema (.stvn_df) or binary definitions (.stvn_bdf). */
  DEFS_FLAT(StvnVocabulary.KIND_KW_DEFS_FLAT, ".stvn_df");

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
      case ".stvn" -> Optional.of(BODY);
      case ".stvn_d" -> Optional.of(DEFS);
      case ".stvn_f", ".stvn_bf" -> Optional.of(BODY_FLAT);
      case ".stvn_df", ".stvn_bdf" -> Optional.of(DEFS_FLAT);
      default -> Optional.empty();
    };
  }
}
