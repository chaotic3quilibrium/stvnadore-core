package org.stvnadore.core.ast;

import org.jspecify.annotations.NullMarked;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable record representing document identity metadata declared within a {@code :meta} block.
 *
 * @param name   the declared document name, or empty if unconstrained wildcard
 * @param domain the declared namespace domain, or empty if unconstrained wildcard
 * @param kind   the declared document structural kind, or empty if unconstrained
 * @since 2.0.0
 */
@NullMarked
public record StvnDocumentMeta(
    Optional<String> name,
    Optional<String> domain,
    Optional<StvnDocumentKind> kind
) {

  /**
   * Canonical constructor validating that all Optional containers are non-null.
   *
   * @param name   the declared document name
   * @param domain the declared namespace domain
   * @param kind   the declared document structural kind
   */
  public StvnDocumentMeta {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(domain, "domain must not be null");
    Objects.requireNonNull(kind, "kind must not be null");
  }

  /**
   * Constructs a fully specified metadata record.
   *
   * @param name   the document name
   * @param domain the namespace domain
   * @param kind   the document structural kind
   * @return a new {@link StvnDocumentMeta} instance
   */
  public static StvnDocumentMeta of(String name, String domain, StvnDocumentKind kind) {
    return new StvnDocumentMeta(Optional.of(name), Optional.of(domain), Optional.of(kind));
  }

  /**
   * Constructs a metadata record with specified name and kind, leaving domain as an unconstrained wildcard.
   *
   * @param name the document name
   * @param kind the document structural kind
   * @return a new {@link StvnDocumentMeta} instance
   */
  public static StvnDocumentMeta ofNameAndKind(String name, StvnDocumentKind kind) {
    return new StvnDocumentMeta(Optional.of(name), Optional.empty(), Optional.of(kind));
  }

  /**
   * Constructs a metadata record with specified kind, leaving name and domain as unconstrained wildcards.
   *
   * @param kind the document structural kind
   * @return a new {@link StvnDocumentMeta} instance
   */
  public static StvnDocumentMeta ofKind(StvnDocumentKind kind) {
    return new StvnDocumentMeta(Optional.empty(), Optional.empty(), Optional.of(kind));
  }
}
