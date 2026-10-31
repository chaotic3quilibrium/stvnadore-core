package org.stvnadore.core.ir;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable Canonical IR record representing document identity metadata declared within a {@code :meta} block
 * or binary Byte 5 metadata frame.
 *
 * @param name       the declared document name, or empty if omitted
 * @param domain     the declared namespace domain, or empty if omitted
 * @param kind       the declared document structural kind, or empty if omitted
 * @param sourceSpan the optional character or byte coordinate span of the metadata frame, or empty for synthetic instances
 * @since 2.0.0
 */
@NullMarked
public record StvnDocumentMeta(
    Optional<String> name,
    Optional<String> domain,
    Optional<StvnDocumentKind> kind,
    Optional<SourceSpan> sourceSpan
) {

  /**
   * Immutable source coordinate span identifying the declaration locus of a document metadata frame.
   * <p>
   * Coordinate conventions match {@link org.stvnadore.core.StvnDiagnostic}: 1-based {@code line},
   * 0-based {@code column}, 0-based inclusive {@code startOffset}, and 0-based exclusive {@code endOffset}.
   *
   * @param line        the 1-based source line index
   * @param column      the 0-based column or byte offset on the line
   * @param startOffset the 0-based inclusive start character or byte offset
   * @param endOffset   the 0-based exclusive end character or byte offset
   * @since 2.0.0
   */
  public record SourceSpan(
      int line,
      int column,
      int startOffset,
      int endOffset
  ) {
  }

  /**
   * Canonical constructor verifying that all {@link Optional} containers are non-null.
   *
   * @param name       the declared document name
   * @param domain     the declared namespace domain
   * @param kind       the declared document structural kind
   * @param sourceSpan the optional source coordinate span
   */
  public StvnDocumentMeta {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(domain, "domain must not be null");
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(sourceSpan, "sourceSpan must not be null");
  }

  /**
   * Convenience constructor defaulting {@code sourceSpan} to {@link Optional#empty()}.
   *
   * @param name   the declared document name
   * @param domain the declared namespace domain
   * @param kind   the declared document structural kind
   */
  public StvnDocumentMeta(
      Optional<String> name,
      Optional<String> domain,
      Optional<StvnDocumentKind> kind
  ) {
    this(name, domain, kind, Optional.empty());
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

  /**
   * Returns a copy of this metadata record associated with the given {@link SourceSpan}.
   *
   * @param span the source coordinate span to attach
   * @return a new {@link StvnDocumentMeta} instance carrying {@code span}
   */
  public StvnDocumentMeta withSourceSpan(SourceSpan span) {
    Objects.requireNonNull(span, "span must not be null");
    return new StvnDocumentMeta(name, domain, kind, Optional.of(span));
  }

  /**
   * Compares semantic document identity ({@code name}, {@code domain}, and {@code kind}) across
   * textual, canonical, and binary representations independent of physical {@code sourceSpan} offsets.
   *
   * @param obj the reference object with which to compare
   * @return {@code true} if {@code obj} is an {@link StvnDocumentMeta} with equal {@code name}, {@code domain}, and {@code kind}
   */
  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof StvnDocumentMeta other)) {
      return false;
    }
    return Objects.equals(this.name, other.name)
        && Objects.equals(this.domain, other.domain)
        && Objects.equals(this.kind, other.kind);
  }

  /**
   * Computes a hash code from semantic document identity ({@code name}, {@code domain}, and {@code kind}).
   *
   * @return the semantic identity hash code
   */
  @Override
  public int hashCode() {
    return Objects.hash(name, domain, kind);
  }
}
