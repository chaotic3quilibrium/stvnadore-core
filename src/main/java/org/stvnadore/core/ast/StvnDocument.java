package org.stvnadore.core.ast;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.parser.StvnParser.StvnDocumentContext;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * High-level immutable Abstract Syntax Tree representation of a compiled STVN document.
 *
 * @param meta      the document identity metadata, or empty if omitted
 * @param payload   the compiled payload value, or empty if document has no body
 * @param parseTree the ANTLR parse tree context, or empty if constructed programmatically
 * @since 2.0.0
 */
@NullMarked
public record StvnDocument(
    Optional<StvnDocumentMeta> meta,
    Optional<StvnValue> payload,
    Optional<StvnDocumentContext> parseTree
) {

  /**
   * Canonical constructor validating that all Optional containers are non-null.
   *
   * @param meta      the document identity metadata
   * @param payload   the compiled payload value
   * @param parseTree the ANTLR parse tree context
   */
  public StvnDocument {
    Objects.requireNonNull(meta, "meta must not be null");
    Objects.requireNonNull(payload, "payload must not be null");
    Objects.requireNonNull(parseTree, "parseTree must not be null");
  }

  /**
   * Convenience constructor constructing a document without an associated parse tree context.
   *
   * @param meta    the document identity metadata
   * @param payload the compiled payload value
   */
  public StvnDocument(Optional<StvnDocumentMeta> meta, Optional<StvnValue> payload) {
    this(meta, payload, Optional.empty());
  }

  /**
   * Indicates whether this document contains a compiled payload body value.
   *
   * @return {@code true} if a payload is present, {@code false} otherwise
   */
  public boolean hasPayload() {
    return payload.isPresent();
  }

  /**
   * Indicates whether this document contains declared {@code :meta} metadata.
   *
   * @return {@code true} if metadata is present, {@code false} otherwise
   */
  public boolean hasMeta() {
    return meta.isPresent();
  }

  /**
   * Returns the compiled payload value, throwing an exception if no payload exists.
   *
   * @return the {@link StvnValue} payload
   * @throws NoSuchElementException if no payload is present in this document
   */
  public StvnValue requirePayload() {
    return payload.orElseThrow(() -> new NoSuchElementException("Document contains no payload body"));
  }

  /**
   * Returns the document identity metadata, throwing an exception if no metadata exists.
   *
   * @return the {@link StvnDocumentMeta} metadata
   * @throws NoSuchElementException if no {@code :meta} header is present in this document
   */
  public StvnDocumentMeta requireMeta() {
    return meta.orElseThrow(() -> new NoSuchElementException("Document contains no :meta header"));
  }

  /**
   * Returns the definitions entry context from the underlying parse tree, if present.
   *
   * @return optional definitions entry context
   */
  public Optional<org.stvnadore.core.parser.StvnParser.DefsEntryContext> defs() {
    return parseTree.flatMap(docCtx -> Optional.ofNullable(
        docCtx.documentBody() != null ? docCtx.documentBody().defsEntry() : null
    ));
  }

  /**
   * Indicates whether this document contains declared {@code :defs} definitions.
   *
   * @return {@code true} if definitions are present, {@code false} otherwise
   */
  public boolean hasDefs() {
    return defs().isPresent();
  }
}

