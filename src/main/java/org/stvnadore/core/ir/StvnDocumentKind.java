package org.stvnadore.core.ir;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;

import java.util.Objects;
import java.util.Optional;

/**
 * Enumeration of normative STVN 2.0.0 document kinds defining structural capabilities
 * and file extension mappings in the Canonical IR.
 *
 * @since 2.0.0
 */
@NullMarked
public enum StvnDocumentKind {

  /** Textual canonical hermetic document body ({@code .stvn}) or binary body ({@code .stvn_b}). */
  BODY(StvnVocabulary.KIND_KW_BODY, ".stvn"),

  /** Textual hermetic definitions schema ({@code .stvn_d}) or binary definitions ({@code .stvn_bd}). */
  DEFS(StvnVocabulary.KIND_KW_DEFS, ".stvn_d"),

  /** Textual modular document body with optional includes ({@code .stvn_i}). */
  BODY_INCLUDE(StvnVocabulary.KIND_KW_BODY_INCLUDE, ".stvn_i"),

  /** Textual modular definitions schema module with includes ({@code .stvn_di}). */
  DEFS_INCLUDE(StvnVocabulary.KIND_KW_DEFS_INCLUDE, ".stvn_di");

  private final String keyword;
  private final String primaryExtension;

  StvnDocumentKind(String keyword, String primaryExtension) {
    this.keyword = keyword;
    this.primaryExtension = primaryExtension;
  }

  /**
   * Returns the canonical STVN keyword token representing this document kind (for example, {@code #BODY}).
   *
   * @return the canonical STVN keyword token
   */
  public String keyword() {
    return keyword;
  }

  /**
   * Returns the primary physical file extension associated with this document kind (for example, {@code .stvn}).
   *
   * @return the primary physical file extension
   */
  public String primaryExtension() {
    return primaryExtension;
  }

  /**
   * Resolves a document kind from its canonical STVN keyword token.
   *
   * @param keyword the STVN keyword token to inspect
   * @return an {@link Optional} containing the matching {@link StvnDocumentKind}, or empty if unmatched
   */
  public static Optional<StvnDocumentKind> fromKeyword(String keyword) {
    Objects.requireNonNull(keyword, "keyword must not be null");
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
   * @param extension the physical file extension including the leading dot
   * @return an {@link Optional} containing the matching {@link StvnDocumentKind}, or empty if unrecognized
   */
  public static Optional<StvnDocumentKind> fromExtension(String extension) {
    Objects.requireNonNull(extension, "extension must not be null");
    return switch (extension) {
      case ".stvn", ".stvn_b" -> Optional.of(BODY);
      case ".stvn_d", ".stvn_bd" -> Optional.of(DEFS);
      case ".stvn_i" -> Optional.of(BODY_INCLUDE);
      case ".stvn_di" -> Optional.of(DEFS_INCLUDE);
      default -> Optional.empty();
    };
  }
}
