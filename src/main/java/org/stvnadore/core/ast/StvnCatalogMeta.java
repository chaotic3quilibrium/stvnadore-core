package org.stvnadore.core.ast;

import org.jspecify.annotations.NullMarked;
import java.util.Objects;

/**
 * Immutable AST record representing strict authoritative catalog document metadata.
 *
 * @param name   the mandatory document name
 * @param domain the mandatory namespace domain
 * @param kind   the mandatory document structural kind
 * @since 2.0.0
 */
@NullMarked
public record StvnCatalogMeta(
    String name,
    String domain,
    StvnDocumentKind kind
) {

  /**
   * Canonical constructor verifying non-null fields and non-empty identity segments.
   *
   * @param name   the mandatory document name
   * @param domain the mandatory namespace domain
   * @param kind   the mandatory document structural kind
   */
  public StvnCatalogMeta {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(domain, "domain must not be null");
    Objects.requireNonNull(kind, "kind must not be null");
    if (name.isEmpty()) {
      throw new IllegalArgumentException("name must not be empty in catalog metadata");
    }
    if (domain.isEmpty()) {
      throw new IllegalArgumentException("domain must not be empty in catalog metadata");
    }
  }

  /**
   * Constructs a catalog metadata record.
   *
   * @param name   the document name
   * @param domain the namespace domain
   * @param kind   the document structural kind
   * @return a new {@link StvnCatalogMeta} instance
   */
  public static StvnCatalogMeta of(String name, String domain, StvnDocumentKind kind) {
    return new StvnCatalogMeta(name, domain, kind);
  }
}
