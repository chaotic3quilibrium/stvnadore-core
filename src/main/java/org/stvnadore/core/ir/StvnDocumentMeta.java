package org.stvnadore.core.ir;

import org.jspecify.annotations.NullMarked;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable Canonical IR record representing document identity metadata declared within a {@code :meta} block.
 *
 * @param name   the declared document name, or empty if omitted
 * @param domain the declared namespace domain, or empty if omitted
 * @param kind   the declared document structural kind, or empty if omitted
 * @since 2.0.0
 */
@NullMarked
public record StvnDocumentMeta(
    Optional<String> name,
    Optional<String> domain,
    Optional<StvnDocumentKind> kind
) {

  /**
   * Canonical constructor verifying that all {@link Optional} containers are non-null.
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
   * Constructs a complete metadata record with name, domain, and kind.
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
   * Constructs a metadata record with name and kind, leaving domain unconstrained.
   *
   * @param name the document name
   * @param kind the document structural kind
   * @return a new {@link StvnDocumentMeta} instance
   */
  public static StvnDocumentMeta ofNameAndKind(String name, StvnDocumentKind kind) {
    return new StvnDocumentMeta(Optional.of(name), Optional.empty(), Optional.of(kind));
  }

  /**
   * Constructs a metadata record with kind only, leaving name and domain unconstrained.
   *
   * @param kind the document structural kind
   * @return a new {@link StvnDocumentMeta} instance
   */
  public static StvnDocumentMeta ofKind(StvnDocumentKind kind) {
    return new StvnDocumentMeta(Optional.empty(), Optional.empty(), Optional.of(kind));
  }
}
