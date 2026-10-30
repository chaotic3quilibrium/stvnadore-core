package org.stvnadore.core.ir;

import org.jspecify.annotations.NullMarked;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * Root Canonical Intermediate Representation (IR) record for a compiled STVN 2.0.0 compilation unit.
 * <p>
 * Encapsulates validated document metadata, the topologically ordered catalog of desugared
 * nominal types and typed constants, the resolved root schema type, and the lowered value tree.
 *
 * @param meta           the validated {@code :meta} identity header, or empty if omitted
 * @param defs           the immutable catalog of desugared nominal schemas and constants
 * @param rootType       the resolved root {@code :type} schema, or empty for definition modules
 * @param payload        the validated root {@code :body} value tree, or empty for definition modules
 * @param hasDefsSection {@code true} if the source unit declared an explicit {@code :defs} section
 * @param hasIncludes    {@code true} if the source unit declared an explicit {@code :include} section
 * @since 2.0.0
 */
@NullMarked
public record StvnDocument(
    Optional<StvnDocumentMeta> meta,
    StvnDefsCatalog defs,
    Optional<StvnSchema> rootType,
    Optional<StvnValue> payload,
    boolean hasDefsSection,
    boolean hasIncludes
) {

  /**
   * Canonical constructor verifying that all reference components are non-null.
   *
   * @param meta           the validated {@code :meta} identity header
   * @param defs           the immutable definitions catalog
   * @param rootType       the resolved root {@code :type} schema
   * @param payload        the validated root {@code :body} value tree
   * @param hasDefsSection whether the source unit declared an explicit {@code :defs} section
   * @param hasIncludes    whether the source unit declared an explicit {@code :include} section
   */
  public StvnDocument {
    Objects.requireNonNull(meta, "meta must not be null");
    Objects.requireNonNull(defs, "defs must not be null");
    Objects.requireNonNull(rootType, "rootType must not be null");
    Objects.requireNonNull(payload, "payload must not be null");
  }

  /**
   * Indicates whether this document declares a {@code :meta} identity block.
   *
   * @return {@code true} if metadata is present, {@code false} otherwise
   */
  public boolean hasMeta() {
    return meta.isPresent();
  }

  /**
   * Indicates whether this document declares a resolved root {@code :type} schema.
   *
   * @return {@code true} if a root schema is present, {@code false} otherwise
   */
  public boolean hasRootType() {
    return rootType.isPresent();
  }

  /**
   * Indicates whether this document contains a lowered {@code :body} payload value.
   *
   * @return {@code true} if a payload value is present, {@code false} otherwise
   */
  public boolean hasPayload() {
    return payload.isPresent();
  }

  /**
   * Returns the document metadata, throwing {@link NoSuchElementException} if absent.
   *
   * @return the {@link StvnDocumentMeta} header
   * @throws NoSuchElementException if no {@code :meta} header is present
   */
  public StvnDocumentMeta requireMeta() {
    return meta.orElseThrow(() -> new NoSuchElementException("Document contains no :meta header"));
  }

  /**
   * Returns the resolved root schema, throwing {@link NoSuchElementException} if absent.
   *
   * @return the root {@link StvnSchema}
   * @throws NoSuchElementException if no root {@code :type} schema is present
   */
  public StvnSchema requireRootType() {
    return rootType.orElseThrow(() -> new NoSuchElementException("Document contains no root :type schema"));
  }

  /**
   * Returns the lowered payload value, throwing {@link NoSuchElementException} if absent.
   *
   * @return the root {@link StvnValue}
   * @throws NoSuchElementException if no {@code :body} payload is present
   */
  public StvnValue requirePayload() {
    return payload.orElseThrow(() -> new NoSuchElementException("Document contains no :body payload"));
  }
}
