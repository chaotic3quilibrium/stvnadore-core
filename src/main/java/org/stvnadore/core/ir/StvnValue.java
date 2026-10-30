package org.stvnadore.core.ir;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnDiagnostic;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Closed 15-variant Canonical Intermediate Representation (IR) value algebra for STVN 2.0.0.
 * <p>
 * Every value node is an immutable Java record paired with its governing {@link StvnSchema}.
 * All integer values, enum ordinals, union branch indices, and epoch ticks use {@link BigInteger},
 * and all floating-point and exact decimal values use {@link BigDecimal}.
 *
 * @since 2.0.0
 */
@NullMarked
public sealed interface StvnValue permits
    StvnValue.StvnBoolean,
    StvnValue.StvnInt,
    StvnValue.StvnFloat,
    StvnValue.StvnString,
    StvnValue.StvnTimeEpoch,
    StvnValue.StvnDateTime,
    StvnValue.StvnEnum,
    StvnValue.StvnTuple,
    StvnValue.StvnOption,
    StvnValue.StvnEither,
    StvnValue.StvnUnion,
    StvnValue.StvnSeq,
    StvnValue.StvnSet,
    StvnValue.StvnMap,
    StvnValue.StvnErrorValue {

  /**
   * Returns the governing normalized {@link StvnSchema} for this value node.
   *
   * @return the non-null {@link StvnSchema}
   */
  StvnSchema schema();

  /**
   * Lowered {@code :Boolean} truth value ({@code #TRUE} or {@code #FALSE}).
   *
   * @param schema governing normalized schema
   * @param value  boolean truth payload
   * @since 2.0.0
   */
  record StvnBoolean(StvnSchema schema, boolean value) implements StvnValue {

    /**
     * Canonical constructor verifying non-null schema.
     *
     * @param schema governing normalized schema
     * @param value  boolean truth payload
     */
    public StvnBoolean {
      Objects.requireNonNull(schema, "schema must not be null");
    }
  }

  /**
   * Lowered arbitrary-precision {@code :Int} integer value.
   *
   * @param schema governing normalized schema
   * @param value  arbitrary-precision integer payload
   * @since 2.0.0
   */
  record StvnInt(StvnSchema schema, BigInteger value) implements StvnValue {

    /**
     * Canonical constructor verifying non-null schema and value.
     *
     * @param schema governing normalized schema
     * @param value  arbitrary-precision integer payload
     */
    public StvnInt {
      Objects.requireNonNull(schema, "schema must not be null");
      Objects.requireNonNull(value, "value must not be null");
    }
  }

  /**
   * Classification of finite and non-finite IEEE-754 floating-point states.
   *
   * @since 2.0.0
   */
  enum Ieee754SpecialKind {
    /** Standard finite numeric value stored in {@link StvnFloat#decimalValue()}. */
    FINITE,
    /** IEEE-754 positive infinity ({@code +Infinity}). */
    POSITIVE_INFINITY,
    /** IEEE-754 negative infinity ({@code -Infinity}). */
    NEGATIVE_INFINITY,
    /** IEEE-754 quiet Not-a-Number ({@code NaN}). */
    NAN
  }

  /**
   * Lowered {@code :Float} machine floating-point or exact base-10 decimal value.
   *
   * @param schema       governing normalized schema
   * @param decimalValue arbitrary-precision base-10 decimal payload ({@link BigDecimal#ZERO} when non-finite)
   * @param negativeZero {@code true} if the value represents IEEE-754 signed negative zero ({@code -0.0})
   * @param specialKind  finite or IEEE-754 non-finite state classification
   * @since 2.0.0
   */
  record StvnFloat(
      StvnSchema schema,
      BigDecimal decimalValue,
      boolean negativeZero,
      Ieee754SpecialKind specialKind
  ) implements StvnValue {

    /**
     * Canonical constructor verifying non-null components.
     *
     * @param schema       governing normalized schema
     * @param decimalValue arbitrary-precision decimal payload
     * @param negativeZero whether the value is IEEE-754 {@code -0.0}
     * @param specialKind  finite or special IEEE-754 state
     */
    public StvnFloat {
      Objects.requireNonNull(schema, "schema must not be null");
      Objects.requireNonNull(decimalValue, "decimalValue must not be null");
      Objects.requireNonNull(specialKind, "specialKind must not be null");
    }
  }

  /**
   * Lowered UTF-8 {@code :String} character sequence value.
   *
   * @param schema governing normalized schema
   * @param value  unescaped and margin-normalized UTF-8 string payload
   * @since 2.0.0
   */
  record StvnString(StvnSchema schema, String value) implements StvnValue {

    /**
     * Canonical constructor verifying non-null schema and string payload.
     *
     * @param schema governing normalized schema
     * @param value  UTF-8 string payload
     */
    public StvnString {
      Objects.requireNonNull(schema, "schema must not be null");
      Objects.requireNonNull(value, "value must not be null");
    }
  }

  /**
   * Lowered {@code :TimeEpoch} physical tick timestamp value.
   *
   * @param schema     governing normalized schema
   * @param epochTicks arbitrary-precision elapsed ticks since {@code 1970-01-01T00:00:00Z}
   * @param scale      physical tick duration scale ({@code #ns}, {@code #us}, {@code #ms}, {@code #s})
   * @since 2.0.0
   */
  record StvnTimeEpoch(
      StvnSchema schema,
      BigInteger epochTicks,
      StvnSchema.TemporalScale scale
  ) implements StvnValue {

    /**
     * Canonical constructor verifying non-null components.
     *
     * @param schema     governing normalized schema
     * @param epochTicks elapsed epoch ticks
     * @param scale      physical tick duration scale
     */
    public StvnTimeEpoch {
      Objects.requireNonNull(schema, "schema must not be null");
      Objects.requireNonNull(epochTicks, "epochTicks must not be null");
      Objects.requireNonNull(scale, "scale must not be null");
    }
  }

  /**
   * Lowered tripartite {@code :DateTime} calendar and wall-clock timestamp value.
   *
   * @param schema           governing normalized schema
   * @param mode             tripartite datetime mode ({@code #offset}, {@code #zoned}, {@code #audited})
   * @param localWallClock   parsed civil local date-time component
   * @param offset           numerical UTC offset (present for {@code #offset} and {@code #audited})
   * @param zone             IANA time zone jurisdiction (present for {@code #zoned} and {@code #audited})
   * @param canonicalIsoText canonical unquoted ISO-8601 textual representation
   * @since 2.0.0
   */
  record StvnDateTime(
      StvnSchema schema,
      StvnSchema.DateTimeMode mode,
      LocalDateTime localWallClock,
      Optional<ZoneOffset> offset,
      Optional<ZoneId> zone,
      String canonicalIsoText
  ) implements StvnValue {

    /**
     * Canonical constructor verifying non-null components.
     *
     * @param schema           governing normalized schema
     * @param mode             tripartite datetime mode
     * @param localWallClock   civil local date-time component
     * @param offset           optional UTC offset
     * @param zone             optional IANA time zone jurisdiction
     * @param canonicalIsoText canonical unquoted ISO-8601 text
     */
    public StvnDateTime {
      Objects.requireNonNull(schema, "schema must not be null");
      Objects.requireNonNull(mode, "mode must not be null");
      Objects.requireNonNull(localWallClock, "localWallClock must not be null");
      Objects.requireNonNull(offset, "offset must not be null");
      Objects.requireNonNull(zone, "zone must not be null");
      Objects.requireNonNull(canonicalIsoText, "canonicalIsoText must not be null");
    }
  }

  /**
   * Lowered {@code :Enum} symbolic enumeration variant value.
   *
   * @param schema      governing normalized enum or enum-subset schema
   * @param variantTag  hash-prefixed symbolic variant identifier (for example, {@code #ACTIVE})
   * @param rootOrdinal 0-based declaration ordinal within the terminal root {@link StvnSchema.EnumSchema}
   * @since 2.0.0
   */
  record StvnEnum(
      StvnSchema schema,
      String variantTag,
      BigInteger rootOrdinal
  ) implements StvnValue {

    /**
     * Canonical constructor verifying non-null components and non-negative {@code rootOrdinal}.
     *
     * @param schema      governing normalized schema
     * @param variantTag  hash-prefixed variant tag
     * @param rootOrdinal 0-based root declaration ordinal (&gt;= 0)
     */
    public StvnEnum {
      Objects.requireNonNull(schema, "schema must not be null");
      Objects.requireNonNull(variantTag, "variantTag must not be null");
      Objects.requireNonNull(rootOrdinal, "rootOrdinal must not be null");
      if (rootOrdinal.compareTo(BigInteger.ZERO) < 0) {
        throw new IllegalArgumentException("rootOrdinal must be >= 0, got: " + rootOrdinal);
      }
    }
  }

  /**
   * Lowered heterogeneous positional {@code :Tuple} value.
   *
   * @param schema   governing normalized schema
   * @param elements ordered unmodifiable list of positional field values
   * @since 2.0.0
   */
  record StvnTuple(StvnSchema schema, List<StvnValue> elements) implements StvnValue {

    /**
     * Canonical constructor verifying non-null components and defensively copying {@code elements}.
     *
     * @param schema   governing normalized schema
     * @param elements ordered list of positional field values
     */
    public StvnTuple {
      Objects.requireNonNull(schema, "schema must not be null");
      Objects.requireNonNull(elements, "elements must not be null");
      elements = List.copyOf(elements);
    }
  }

  /**
   * Lowered {@code :Option} monadic choice value ({@code #Some} or {@code #None}).
   *
   * @param schema governing normalized schema
   * @param value  present wrapped {@link StvnValue} for {@code #Some}, or empty for {@code #None}
   * @since 2.0.0
   */
  record StvnOption(StvnSchema schema, Optional<StvnValue> value) implements StvnValue {

    /**
     * Canonical constructor verifying non-null schema and optional container.
     *
     * @param schema governing normalized schema
     * @param value  optional wrapped value
     */
    public StvnOption {
      Objects.requireNonNull(schema, "schema must not be null");
      Objects.requireNonNull(value, "value must not be null");
    }
  }

  /**
   * Branch discriminator for binary {@code :Either} disjoint sum values.
   *
   * @since 2.0.0
   */
  enum EitherBranch {
    /** Left branch ({@code #Left} / {@code #L}). */
    LEFT,
    /** Right branch ({@code #Right} / {@code #R}). */
    RIGHT
  }

  /**
   * Lowered binary {@code :Either} disjoint sum value.
   *
   * @param schema governing normalized schema
   * @param branch active branch ({@link EitherBranch#LEFT} or {@link EitherBranch#RIGHT})
   * @param value  wrapped branch payload value
   * @since 2.0.0
   */
  record StvnEither(StvnSchema schema, EitherBranch branch, StvnValue value) implements StvnValue {

    /**
     * Canonical constructor verifying non-null components.
     *
     * @param schema governing normalized schema
     * @param branch active branch discriminator
     * @param value  wrapped branch payload value
     */
    public StvnEither {
      Objects.requireNonNull(schema, "schema must not be null");
      Objects.requireNonNull(branch, "branch must not be null");
      Objects.requireNonNull(value, "value must not be null");
    }
  }

  /**
   * Lowered 1-based indexed {@code :Union} multi-branch sum value.
   *
   * @param schema              governing normalized schema
   * @param oneBasedBranchIndex 1-based branch index (&gt;= 1) corresponding to {@code #1}, {@code #2}, and so on
   * @param value               wrapped branch payload value
   * @since 2.0.0
   */
  record StvnUnion(
      StvnSchema schema,
      BigInteger oneBasedBranchIndex,
      StvnValue value
  ) implements StvnValue {

    /**
     * Canonical constructor verifying non-null components and 1-based positive branch index (&gt;= 1).
     *
     * @param schema              governing normalized schema
     * @param oneBasedBranchIndex 1-based branch index (&gt;= 1)
     * @param value               wrapped branch payload value
     */
    public StvnUnion {
      Objects.requireNonNull(schema, "schema must not be null");
      Objects.requireNonNull(oneBasedBranchIndex, "oneBasedBranchIndex must not be null");
      Objects.requireNonNull(value, "value must not be null");
      if (oneBasedBranchIndex.compareTo(BigInteger.ONE) < 0) {
        throw new IllegalArgumentException("oneBasedBranchIndex must be >= 1, got: " + oneBasedBranchIndex);
      }
    }
  }

  /**
   * Lowered homogeneous ordered {@code :Seq} sequence collection value.
   *
   * @param schema   governing normalized schema
   * @param elements ordered unmodifiable list of sequence elements
   * @since 2.0.0
   */
  record StvnSeq(StvnSchema schema, List<StvnValue> elements) implements StvnValue {

    /**
     * Canonical constructor verifying non-null components and defensively copying {@code elements}.
     *
     * @param schema   governing normalized schema
     * @param elements ordered list of sequence elements
     */
    public StvnSeq {
      Objects.requireNonNull(schema, "schema must not be null");
      Objects.requireNonNull(elements, "elements must not be null");
      elements = List.copyOf(elements);
    }
  }

  /**
   * Lowered insertion-ordered unique {@code :Set} collection value.
   *
   * @param schema   governing normalized schema
   * @param elements insertion-ordered unmodifiable list of unique set elements
   * @since 2.0.0
   */
  record StvnSet(StvnSchema schema, List<StvnValue> elements) implements StvnValue {

    /**
     * Canonical constructor verifying non-null components and defensively copying {@code elements}.
     *
     * @param schema   governing normalized schema
     * @param elements insertion-ordered list of unique set elements
     */
    public StvnSet {
      Objects.requireNonNull(schema, "schema must not be null");
      Objects.requireNonNull(elements, "elements must not be null");
      elements = List.copyOf(elements);
    }
  }

  /**
   * Immutable key-value pair entry within an associative {@link StvnMap}.
   *
   * @param key   lowered map key value
   * @param value lowered map target value
   * @since 2.0.0
   */
  record KeyValueEntry(StvnValue key, StvnValue value) {

    /**
     * Canonical constructor verifying non-null key and value.
     *
     * @param key   lowered map key value
     * @param value lowered map target value
     */
    public KeyValueEntry {
      Objects.requireNonNull(key, "key must not be null");
      Objects.requireNonNull(value, "value must not be null");
    }
  }

  /**
   * Lowered insertion-ordered associative {@code :Map} collection value.
   *
   * @param schema  governing normalized schema
   * @param entries insertion-ordered unmodifiable list of {@link KeyValueEntry} pairs
   * @since 2.0.0
   */
  record StvnMap(StvnSchema schema, List<KeyValueEntry> entries) implements StvnValue {

    /**
     * Canonical constructor verifying non-null components and defensively copying {@code entries}.
     *
     * @param schema  governing normalized schema
     * @param entries insertion-ordered list of key-value pairs
     */
    public StvnMap {
      Objects.requireNonNull(schema, "schema must not be null");
      Objects.requireNonNull(entries, "entries must not be null");
      entries = List.copyOf(entries);
    }
  }

  /**
   * Container fault-isolation sentinel value preserving {@code M == N} element cardinality
   * during semantic error recovery without corrupting adjacent valid siblings.
   *
   * @param schema     expected or poisoned {@link StvnSchema} at the failure site
   * @param diagnostic localized {@link StvnDiagnostic} describing the lowering failure
   * @since 2.0.0
   */
  record StvnErrorValue(
      StvnSchema schema,
      StvnDiagnostic diagnostic
  ) implements StvnValue {

    /**
     * Canonical constructor verifying non-null schema and diagnostic.
     *
     * @param schema     expected or poisoned schema
     * @param diagnostic localized failure diagnostic
     */
    public StvnErrorValue {
      Objects.requireNonNull(schema, "schema must not be null");
      Objects.requireNonNull(diagnostic, "diagnostic must not be null");
    }
  }
}
