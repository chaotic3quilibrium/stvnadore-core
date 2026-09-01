package org.stvnadore.core.ir;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Represents the root interface for all nodes in the STVN (Strongly Typed Value Notation)
 * Intermediate Representation (IR) / Abstract Syntax Tree (AST).
 * <p>
 * This is a {@code sealed} interface that permits only three direct structural sub-interfaces:
 * <ul>
 *   <li>{@link StvnAtomic} - For scalar/primitive values (booleans, integers, floats, strings, timestamps).</li>
 *   <li>{@link StvnCollection} - For composite container structures (sequences, sets, maps, tuples).</li>
 *   <li>{@link StvnSum} - For algebraic variant structures (options, eithers, unions, enums).</li>
 * </ul>
 * <p>
 * Every {@code StvnValue} node holds a non-null reference to its resolved schema configuration,
 * capturing its nominal constraints, traits, and aliases.
 * <p>
 * <b>Thread Safety &amp; Immutability:</b>
 * All implementations of {@code StvnValue} are strict Java records and are completely immutable.
 * They are thread-safe and safe for concurrent sharing across multiple threads.
 *
 * @since 1.0.0
 */
@NullMarked
public sealed interface StvnValue {

  /**
   * Returns the resolved schema configuration associated with this AST value node.
   *
   * @return the non-null {@link ResolvedSchema} of this node
   */
  ResolvedSchema schema();

  /**
   * Sealed sub-interface representing scalar/atomic primitive values in the STVN AST.
   */
  sealed interface StvnAtomic extends StvnValue permits
      StvnBoolean, StvnInteger, StvnFloat, StvnString, StvnTime,
      StvnDateTimeOffset, StvnDateTimeZoned, StvnDateTimeAudited {
  }

  /**
   * Represents a boolean scalar literal payload.
   * <p>
   * Maps to the STVN {@code :Boolean} primitive, representing either {@code #TRUE} or {@code #FALSE}.
   *
   * @param schema the resolved schema mapping this node
   * @param value  the native boolean value
   */
  record StvnBoolean(ResolvedSchema schema, boolean value) implements StvnAtomic {
    /**
     * Canonical constructor validating that the schema is non-null.
     *
     * @param schema the resolved schema mapping this node
     * @param value  the native boolean value
     */
    public StvnBoolean {
      java.util.Objects.requireNonNull(schema);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof StvnBoolean that)) return false;
      return this.value == that.value &&
             java.util.Objects.equals(this.schema, that.schema);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(schema, value);
    }
  }

  /**
   * Represents a signed or unsigned arbitrary-precision integer scalar literal payload.
   * <p>
   * Maps to STVN integer typologies (e.g. {@code :Int8}, {@code :Int32}, {@code :Uint64}).
   * Handles custom constraints like bitwidth constraints and high-bit checks for unsigned integers.
   *
   * @param schema     the resolved schema mapping this node
   * @param value      the integer payload as a non-null {@link BigInteger}
   * @param bitWidth   the constraint bit-width (e.g. 8, 16, 32, 64)
   * @param isUnsigned true if the schema mandates unsigned validation
   */
  record StvnInteger(
      ResolvedSchema schema,
      BigInteger value,
      int bitWidth,
      boolean isUnsigned
  ) implements StvnAtomic {
    /**
     * Canonical constructor validating that the schema and value are non-null.
     *
     * @param schema     the resolved schema mapping this node
     * @param value      the integer payload
     * @param bitWidth   the constraint bit-width
     * @param isUnsigned true if the schema mandates unsigned validation
     */
    public StvnInteger {
      java.util.Objects.requireNonNull(schema);
      java.util.Objects.requireNonNull(value);
    }

    /**
     * Convenience constructor mapping a native byte to an 8-bit signed integer value node.
     *
     * @param schema the resolved schema mapping this node
     * @param value  the native byte payload
     */
    public StvnInteger(ResolvedSchema schema, byte value) {
      this(schema, BigInteger.valueOf(value), 8, false);
    }

    /**
     * Convenience constructor mapping a native short to a 16-bit signed integer value node.
     *
     * @param schema the resolved schema mapping this node
     * @param value  the native short payload
     */
    public StvnInteger(ResolvedSchema schema, short value) {
      this(schema, BigInteger.valueOf(value), 16, false);
    }

    /**
     * Convenience constructor mapping a native int to a 32-bit signed integer value node.
     *
     * @param schema the resolved schema mapping this node
     * @param value  the native int payload
     */
    public StvnInteger(ResolvedSchema schema, int value) {
      this(schema, BigInteger.valueOf(value), 32, false);
    }

    /**
     * Convenience constructor mapping a native long to a 64-bit signed integer value node.
     *
     * @param schema the resolved schema mapping this node
     * @param value  the native long payload
     */
    public StvnInteger(ResolvedSchema schema, long value) {
      this(schema, BigInteger.valueOf(value), 64, false);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof StvnInteger that)) return false;
      return this.bitWidth == that.bitWidth &&
             this.isUnsigned == that.isUnsigned &&
             java.util.Objects.equals(this.schema, that.schema) &&
             java.util.Objects.equals(this.value, that.value);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(schema, value, bitWidth, isUnsigned);
    }
  }

  /**
   * Represents a floating-point or arbitrary-precision decimal scalar literal payload.
   * <p>
   * Maps to STVN floating-point typologies ({@code :Float32}, {@code :Float64}, or the arbitrary precision {@code :FloatExact}).
   *
   * @param schema    the resolved schema mapping this node
   * @param value     the float payload represented as a non-null {@link BigDecimal}
   * @param precision the precision specification (FLOAT32, FLOAT64, or EXACT)
   */
  record StvnFloat(ResolvedSchema schema, BigDecimal value, FloatPrecision precision) implements StvnAtomic {
    /**
     * Canonical constructor validating that all parameters are non-null.
     *
     * @param schema    the resolved schema mapping this node
     * @param value     the float payload represented as a non-null {@link BigDecimal}
     * @param precision the precision specification
     */
    public StvnFloat {
      java.util.Objects.requireNonNull(schema);
      java.util.Objects.requireNonNull(value);
      java.util.Objects.requireNonNull(precision);
    }

    /**
     * Convenience constructor mapping a native float to a 32-bit float value node.
     *
     * @param schema the resolved schema mapping this node
     * @param value  the native float payload
     */
    public StvnFloat(ResolvedSchema schema, float value) {
      this(schema, new BigDecimal(Float.toString(value)), FloatPrecision.FLOAT32);
    }

    /**
     * Convenience constructor mapping a native double to a 64-bit float value node.
     *
     * @param schema the resolved schema mapping this node
     * @param value  the native double payload
     */
    public StvnFloat(ResolvedSchema schema, double value) {
      this(schema, BigDecimal.valueOf(value), FloatPrecision.FLOAT64);
    }

    /**
     * Convenience constructor mapping an exact BigDecimal value to a float value node.
     *
     * @param schema the resolved schema mapping this node
     * @param value  the arbitrary-precision decimal payload
     */
    public StvnFloat(ResolvedSchema schema, BigDecimal value) {
      this(schema, value, FloatPrecision.EXACT);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof StvnFloat that)) return false;
      if (!java.util.Objects.equals(this.schema, that.schema)) return false;
      if (this.precision != that.precision) return false;
      if (this.precision == FloatPrecision.FLOAT32) {
        return Float.floatToIntBits(this.value.floatValue()) == Float.floatToIntBits(that.value.floatValue());
      } else if (this.precision == FloatPrecision.FLOAT64) {
        return Double.doubleToLongBits(this.value.doubleValue()) == Double.doubleToLongBits(that.value.doubleValue());
      } else {
        return this.value.compareTo(that.value) == 0 || this.value.stripTrailingZeros().equals(that.value.stripTrailingZeros());
      }
    }

    @Override
    public int hashCode() {
      if (precision == FloatPrecision.FLOAT32) {
        return java.util.Objects.hash(schema, precision, Float.floatToIntBits(value.floatValue()));
      } else if (precision == FloatPrecision.FLOAT64) {
        return java.util.Objects.hash(schema, precision, Double.doubleToLongBits(value.doubleValue()));
      } else {
        return java.util.Objects.hash(schema, precision, value.stripTrailingZeros());
      }
    }
  }

  /**
   * Defines the layout style of string literals.
   */
  enum StringStyle {
    /** Single-line double-quoted string. */
    SIMPLE,
    /** Multi-line triple-quoted block string. */
    BLOCK,
    /** Custom tagged multi-line fenced block string. */
    FENCED
  }

  /**
   * Represents a character sequence literal payload.
   * <p>
   * Maps to STVN string typologies ({@code :String}, {@code :StringFixedN}, etc.), supporting
   * multiple presentation styles (simple, block, fenced).
   *
   * @param schema   the resolved schema mapping this node
   * @param value    the non-null string content
   * @param style    the formatting layout style (SIMPLE, BLOCK, FENCED)
   * @param fenceTag optional tag for fenced block styling (e.g. `markdown`)
   * @param trait    structural constraints (fixed length boundaries, non-emptiness checks)
   */
  record StvnString(
      ResolvedSchema schema,
      String value,
      StringStyle style,
      Optional<String> fenceTag,
      StringTrait trait
  ) implements StvnAtomic {
    /**
     * Canonical constructor validating that all parameters are non-null.
     *
     * @param schema   the resolved schema mapping this node
     * @param value    the non-null string content
     * @param style    the formatting layout style
     * @param fenceTag optional tag for fenced block styling
     * @param trait    structural constraints
     */
    public StvnString {
      java.util.Objects.requireNonNull(schema);
      java.util.Objects.requireNonNull(value);
      java.util.Objects.requireNonNull(style);
      java.util.Objects.requireNonNull(fenceTag);
      java.util.Objects.requireNonNull(trait);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof StvnString that)) return false;
      return this.style == that.style &&
             java.util.Objects.equals(this.schema, that.schema) &&
             java.util.Objects.equals(this.value, that.value) &&
             java.util.Objects.equals(this.fenceTag, that.fenceTag) &&
             java.util.Objects.equals(this.trait, that.trait);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(schema, value, style, fenceTag, trait);
    }
  }

  /**
   * Represents a temporal timestamp or date-time offset.
   * <p>
   * Supports standard unix epoch markers (seconds, milliseconds, nanoseconds) and ISO-8601 offset/zoned representations.
   *
   * @param schema the resolved schema mapping this node
   * @param value  the timestamp payload (either a numeric {@link BigInteger} representing unix epoch time, or an ISO-8601 {@link String})
   * @param kind   the specific time representation flavor (EPOCH_S, EPOCH_MS, EPOCH_NS, OFFSET, ZONED)
   */
  record StvnTime(ResolvedSchema schema, Object value, TimeKind kind) implements StvnAtomic {
    /**
     * Canonical constructor validating that all parameters are non-null.
     *
     * @param schema the resolved schema mapping this node
     * @param value  the timestamp payload
     * @param kind   the specific time representation flavor
     */
    public StvnTime {
      java.util.Objects.requireNonNull(schema);
      java.util.Objects.requireNonNull(value);
      java.util.Objects.requireNonNull(kind);
    }

    /**
     * Convenience constructor for epoch-based temporal values.
     *
     * @param schema     the resolved schema mapping this node
     * @param epochValue the numeric epoch value
     * @param kind       the epoch precision kind (EPOCH_S, EPOCH_MS, EPOCH_NS)
     * @throws IllegalArgumentException if kind is not one of the EPOCH options
     */
    public StvnTime(ResolvedSchema schema, long epochValue, TimeKind kind) {
      this(schema, BigInteger.valueOf(epochValue), kind);
      if (kind != TimeKind.EPOCH_S && kind != TimeKind.EPOCH_MS && kind != TimeKind.EPOCH_NS) {
        throw new IllegalArgumentException("Invalid State: Numeric payloads can only be paired with EPOCH TimeKinds. Received: " + kind);
      }
    }

    /**
     * Convenience constructor for ISO-8601 offset or zoned string temporal values.
     *
     * @param schema    the resolved schema mapping this node
     * @param isoString the ISO-8601 formatted text
     * @param kind      the offset/zoned precision kind (OFFSET, ZONED)
     * @throws IllegalArgumentException if kind is not OFFSET or ZONED
     */
    public StvnTime(ResolvedSchema schema, String isoString, TimeKind kind) {
      this(schema, (Object) isoString, kind);
      if (kind != TimeKind.OFFSET && kind != TimeKind.ZONED) {
        throw new IllegalArgumentException("Invalid State: String payloads can only be paired with ZONED or OFFSET TimeKinds. Received: " + kind);
      }
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof StvnTime that)) return false;
      return this.kind == that.kind &&
             java.util.Objects.equals(this.schema, that.schema) &&
             java.util.Objects.equals(this.value, that.value);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(schema, value, kind);
    }
  }

  /**
   * Represents an absolute physical instant with an explicit UTC offset.
   * Maps to the STVN {@code :DateTimeOffset} primitive.
   *
   * @param schema the resolved schema mapping this node
   * @param value  the native {@link OffsetDateTime} payload
   */
  record StvnDateTimeOffset(ResolvedSchema schema, OffsetDateTime value) implements StvnAtomic {
    /**
     * Canonical constructor validating non-null schema and value.
     *
     * @param schema the resolved schema mapping this node
     * @param value  the native {@link OffsetDateTime} payload
     */
    public StvnDateTimeOffset {
      java.util.Objects.requireNonNull(schema);
      java.util.Objects.requireNonNull(value);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof StvnDateTimeOffset that)) return false;
      return java.util.Objects.equals(this.schema, that.schema) &&
             this.value.isEqual(that.value) &&
             this.value.getOffset().equals(that.value.getOffset());
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(schema, value);
    }
  }

  /**
   * Represents a civil wall-clock schedule bound to an IANA time zone jurisdiction.
   * Maps to the STVN {@code :DateTimeZoned} primitive.
   *
   * @param schema        the resolved schema mapping this node
   * @param localDateTime the civil local date-time payload
   * @param zoneId        the IANA zone identifier
   */
  record StvnDateTimeZoned(ResolvedSchema schema, LocalDateTime localDateTime, ZoneId zoneId) implements StvnAtomic {
    /**
     * Canonical constructor validating non-null parameters.
     *
     * @param schema        the resolved schema mapping this node
     * @param localDateTime the civil local date-time payload
     * @param zoneId        the IANA zone identifier
     */
    public StvnDateTimeZoned {
      java.util.Objects.requireNonNull(schema);
      java.util.Objects.requireNonNull(localDateTime);
      java.util.Objects.requireNonNull(zoneId);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof StvnDateTimeZoned that)) return false;
      return java.util.Objects.equals(this.schema, that.schema) &&
             this.localDateTime.equals(that.localDateTime) &&
             this.zoneId.equals(that.zoneId);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(schema, localDateTime, zoneId);
    }
  }

  /**
   * Represents an immutable regulatory compliance record containing both observed UTC offset and IANA jurisdiction.
   * Maps to the STVN {@code :DateTimeAudited} primitive.
   *
   * @param schema         the resolved schema mapping this node
   * @param offsetDateTime the recorded offset date-time payload
   * @param zoneId         the IANA zone identifier
   */
  record StvnDateTimeAudited(ResolvedSchema schema, OffsetDateTime offsetDateTime, ZoneId zoneId) implements StvnAtomic {
    /**
     * Canonical constructor validating non-null parameters.
     *
     * @param schema         the resolved schema mapping this node
     * @param offsetDateTime the recorded offset date-time payload
     * @param zoneId         the IANA zone identifier
     */
    public StvnDateTimeAudited {
      java.util.Objects.requireNonNull(schema);
      java.util.Objects.requireNonNull(offsetDateTime);
      java.util.Objects.requireNonNull(zoneId);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof StvnDateTimeAudited that)) return false;
      return java.util.Objects.equals(this.schema, that.schema) &&
             this.offsetDateTime.isEqual(that.offsetDateTime) &&
             this.offsetDateTime.getOffset().equals(that.offsetDateTime.getOffset()) &&
             this.zoneId.equals(that.zoneId);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(schema, offsetDateTime, zoneId);
    }
  }

  /**
   * Defines the target binary width and precision boundaries for floating-point values.
   */
  enum FloatPrecision {
    /** 32-bit single precision IEEE 754 float. */
    FLOAT32,
    /** 64-bit double precision IEEE 754 float. */
    FLOAT64,
    /** Arbitrary precision decimal mapped to java.math.BigDecimal. */
    EXACT
  }

  /**
   * Describes the layout structure of temporal payloads.
   */
  enum TimeKind {
    /** Unix epoch tracking standard seconds (64-bit integer representation). */
    EPOCH_S,
    /** Unix epoch tracking standard milliseconds (64-bit integer representation). */
    EPOCH_MS,
    /** Unix epoch tracking arbitrary nanoseconds (BigInteger representation). */
    EPOCH_NS,
    /** ISO-8601 date-time with a direct numerical timezone offset (e.g. +05:00). */
    OFFSET,
    /** ISO-8601 date-time with a geographical zone identifier (e.g. [America/New_York]). */
    ZONED
  }

  /**
   * Holds metadata constraints on string lengths.
   *
   * @param fixedLength if positive, specifies the exact required length of the string (len == N)
   * @param maxLength   if positive, specifies the maximum allowed length of the string (len &lt;= N)
   * @param isNonEmpty  if true, mandates that the string has at least one character (len &gt;= 1)
   */
  record StringTrait(int fixedLength, int maxLength, boolean isNonEmpty) {
    /**
     * Backward-compatible convenience constructor.
     *
     * @param fixedLength exact length constraint
     * @param isNonEmpty  non-emptiness constraint
     */
    public StringTrait(int fixedLength, boolean isNonEmpty) {
      this(fixedLength, fixedLength > 0 ? fixedLength : 0, isNonEmpty || fixedLength > 0);
    }

    /**
     * Factory for unbounded standard strings ({@code :String}).
     *
     * @return an unbounded string trait instance
     */
    public static StringTrait unbounded() {
      return new StringTrait(0, 0, false);
    }

    /**
     * Factory for unbounded non-empty strings ({@code :StringNonEmpty}).
     *
     * @return an unbounded non-empty string trait instance
     */
    public static StringTrait unboundedNonEmpty() {
      return new StringTrait(0, 0, true);
    }

    /**
     * Factory for max-bounded strings ({@code :StringN}).
     *
     * @param maxLength the maximum allowed length
     * @return a max-bounded string trait instance
     */
    public static StringTrait maxBounded(int maxLength) {
      return new StringTrait(0, maxLength, false);
    }

    /**
     * Factory for max-bounded non-empty strings ({@code :StringNonEmptyN}).
     *
     * @param maxLength the maximum allowed length
     * @return a bounded non-empty string trait instance
     */
    public static StringTrait boundedNonEmpty(int maxLength) {
      return new StringTrait(0, maxLength, true);
    }

    /**
     * Factory for exact fixed-length strings ({@code :StringFixedN}).
     *
     * @param fixedLength the exact required length
     * @return an exact fixed-length string trait instance
     */
    public static StringTrait fixed(int fixedLength) {
      return new StringTrait(fixedLength, 0, true);
    }
  }

  /**
   * Sealed sub-interface representing composite collection types (sequences, sets, maps, tuples).
   * <p>
   * Collection implementations preserve positional insertion order and enforce absolute immutability.
   */
  sealed interface StvnCollection extends StvnValue permits
      StvnSeq, StvnSet, StvnMap, StvnTuple {
  }

  /**
   * Represents an ordered sequence array of values.
   * <p>
   * Maps to the STVN {@code :Seq} or {@code :SeqNonEmpty} collections.
   * Positional ordering is preserved, and elements are exposed as an unmodifiable list.
   *
   * @param schema     the resolved schema mapping this node
   * @param elements   the ordered list of child {@link StvnValue} nodes
   * @param isNonEmpty if true, validation mandates at least one element is present
   */
  record StvnSeq(ResolvedSchema schema, List<StvnValue> elements, boolean isNonEmpty) implements StvnCollection {
    /**
     * Canonical constructor validating that elements and schema are non-null.
     *
     * @param schema     the resolved schema mapping this node
     * @param elements   the ordered list of child nodes
     * @param isNonEmpty if true, validation mandates at least one element is present
     */
    public StvnSeq {
      java.util.Objects.requireNonNull(schema);
      java.util.Objects.requireNonNull(elements);
      elements = List.copyOf(elements);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof StvnSeq that)) return false;
      return this.isNonEmpty == that.isNonEmpty &&
             java.util.Objects.equals(this.schema, that.schema) &&
             java.util.Objects.equals(this.elements, that.elements);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(schema, elements, isNonEmpty);
    }
  }

  /**
   * Represents an insertion-ordered set enforcing unique elements.
   * <p>
   * Maps to the STVN {@code :Set} or {@code :SetNonEmpty} collections.
   * Demands that all elements satisfy {@code #equatable} traits. Internally backed by a sequenced set,
   * preserving deterministic encounter order.
   *
   * @param schema     the resolved schema mapping this node
   * @param elements   the unique set of {@link StvnValue} nodes, which must implement {@link java.util.SequencedSet}
   * @param isNonEmpty if true, validation mandates at least one element is present
   */
  record StvnSet(ResolvedSchema schema, java.util.Set<StvnValue> elements,
                 boolean isNonEmpty) implements StvnCollection {
    /**
     * Canonical constructor validating that elements and schema are non-null and that elements implements SequencedSet.
     *
     * @param schema     the resolved schema mapping this node
     * @param elements   the unique set of child nodes
     * @param isNonEmpty if true, validation mandates at least one element is present
     * @throws org.stvnadore.core.validation.MalformedPayloadException if the passed elements set does not implement {@link java.util.SequencedSet}
     */
    public StvnSet {
      java.util.Objects.requireNonNull(schema);
      java.util.Objects.requireNonNull(elements);
      if (!(elements instanceof java.util.SequencedSet)) {
        throw new org.stvnadore.core.validation.MalformedPayloadException("Non-deterministic set implementation detected: " + elements.getClass().getName() + ". Sets must implement java.util.SequencedSet.");
      }
      elements = java.util.Collections.unmodifiableSequencedSet(new java.util.LinkedHashSet<>(elements));
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof StvnSet that)) return false;
      if (!java.util.Objects.equals(this.schema, that.schema)) return false;
      if (this.isNonEmpty != that.isNonEmpty) return false;
      if (this.elements.size() != that.elements.size()) return false;
      var it1 = this.elements.iterator();
      var it2 = that.elements.iterator();
      while (it1.hasNext()) {
        if (!it1.next().equals(it2.next())) return false;
      }
      return true;
    }

    @Override
    public int hashCode() {
      int hash = 1;
      hash = 31 * hash + schema.hashCode();
      for (var element : elements) {
        hash = 31 * hash + (element == null ? 0 : element.hashCode());
      }
      hash = 31 * hash + Boolean.hashCode(isNonEmpty);
      return hash;
    }
  }

  /**
   * Represents an associative map of key-value pairs.
   * <p>
   * Maps to STVN {@code :Map}, {@code :MapNonEmpty}, or bidirectional {@code :MapInv} structures.
   * Key encounter order is preserved, and keys must satisfy the {@code #equatable} trait.
   * Backed by {@link java.util.SequencedMap} to guarantee determinism in text and binary representations.
   *
   * @param schema       the resolved schema mapping this node
   * @param entries      the associative mapping of key-value {@link StvnValue} pairs
   * @param isNonEmpty   if true, validation mandates at least one entry is present
   * @param isInvertible if true, mandates that all values are also unique (bidirectional map)
   */
  record StvnMap(ResolvedSchema schema, java.util.Map<StvnValue, StvnValue> entries, boolean isNonEmpty,
                 boolean isInvertible) implements StvnCollection {
    /**
     * Canonical constructor validating that entries and schema are non-null and that entries implements SequencedMap.
     *
     * @param schema       the resolved schema mapping this node
     * @param entries      the associative map entries
     * @param isNonEmpty   if true, validation mandates at least one entry is present
     * @param isInvertible if true, mandates that all values are also unique (bidirectional map)
     * @throws org.stvnadore.core.validation.MalformedPayloadException if the passed entries map does not implement {@link java.util.SequencedMap}
     */
    public StvnMap {
      java.util.Objects.requireNonNull(schema);
      java.util.Objects.requireNonNull(entries);
      if (!(entries instanceof java.util.SequencedMap)) {
        throw new org.stvnadore.core.validation.MalformedPayloadException("Non-deterministic map implementation detected: " + entries.getClass().getName() + ". Maps must implement java.util.SequencedMap.");
      }
      entries = java.util.Collections.unmodifiableSequencedMap(new java.util.LinkedHashMap<>(entries));
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof StvnMap that)) return false;
      if (!java.util.Objects.equals(this.schema, that.schema)) return false;
      if (this.isNonEmpty != that.isNonEmpty) return false;
      if (this.isInvertible != that.isInvertible) return false;
      if (this.entries.size() != that.entries.size()) return false;
      var it1 = this.entries.entrySet().iterator();
      var it2 = that.entries.entrySet().iterator();
      while (it1.hasNext()) {
        var e1 = it1.next();
        var e2 = it2.next();
        if (!e1.getKey().equals(e2.getKey())) return false;
        if (!e1.getValue().equals(e2.getValue())) return false;
      }
      return true;
    }

    @Override
    public int hashCode() {
      int hash = 1;
      hash = 31 * hash + schema.hashCode();
      for (var entry : entries.entrySet()) {
        hash = 31 * hash + (entry.getKey() == null ? 0 : entry.getKey().hashCode());
        hash = 31 * hash + (entry.getValue() == null ? 0 : entry.getValue().hashCode());
      }
      hash = 31 * hash + Boolean.hashCode(isNonEmpty);
      hash = 31 * hash + Boolean.hashCode(isInvertible);
      return hash;
    }
  }

  /**
   * Represents a fixed-size, heterogeneous sequence of values.
   * <p>
   * Maps to the STVN {@code :Tuple} type.
   *
   * @param schema   the resolved schema mapping this node
   * @param elements the positional list of child {@link StvnValue} elements
   */
  record StvnTuple(ResolvedSchema schema, List<StvnValue> elements) implements StvnCollection {
    /**
     * Canonical constructor validating that elements and schema are non-null.
     *
     * @param schema   the resolved schema mapping this node
     * @param elements the positional list of child elements
     */
    public StvnTuple {
      java.util.Objects.requireNonNull(schema);
      java.util.Objects.requireNonNull(elements);
      elements = List.copyOf(elements);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof StvnTuple that)) return false;
      return java.util.Objects.equals(this.schema, that.schema) &&
             java.util.Objects.equals(this.elements, that.elements);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(schema, elements);
    }
  }

  /**
   * Sealed sub-interface representing algebraic sum type variants (Option, Either, Union, Enum).
   */
  sealed interface StvnSum extends StvnValue permits
      StvnOption, StvnEither, StvnUnion, StvnEnum {
  }

  /**
   * Represents an optional container choice, wrapping an underlying value or indicating emptiness.
   * <p>
   * Maps to the STVN {@code :Option(T)} sum type, admitting either {@code #Some value} or {@code #None} tags.
   *
   * @param schema the resolved schema mapping this node
   * @param value  the optional inner {@link StvnValue} payload
   * @param trajectory the variant resolution trajectory metadata
   */
  record StvnOption(ResolvedSchema schema, Optional<StvnValue> value, List<VariantStep> trajectory) implements StvnSum {
    /**
     * Canonical constructor validating that schema, value and trajectory are non-null.
     *
     * @param schema     the resolved schema mapping this node
     * @param value      the optional inner payload
     * @param trajectory the variant resolution trajectory metadata
     */
    public StvnOption {
      java.util.Objects.requireNonNull(schema);
      java.util.Objects.requireNonNull(value);
      java.util.Objects.requireNonNull(trajectory);
      trajectory = List.copyOf(trajectory);
    }

    /**
     * Overloaded constructor defaulting trajectory to an empty list.
     *
     * @param schema the resolved schema mapping this node
     * @param value  the optional inner payload
     */
    public StvnOption(ResolvedSchema schema, Optional<StvnValue> value) {
      this(schema, value, List.of());
    }

    /**
     * Checks if this optional represents the empty state (#None).
     *
     * @return {@code true} if empty, otherwise {@code false}
     */
    public boolean isNone() {
      return value.isEmpty();
    }

    /**
     * Checks if this optional wraps a value (#Some).
     *
     * @return {@code true} if a value is present, otherwise {@code false}
     */
    public boolean isSome() {
      return value.isPresent();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof StvnOption that)) return false;
      return java.util.Objects.equals(this.schema, that.schema) &&
             java.util.Objects.equals(this.value, that.value);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(schema, value);
    }

    @Override
    public String toString() {
      return "StvnOption[schema=" + schema + ", value=" + value + "]";
    }
  }

  /**
   * Represents a two-way choice between a left or right variant value.
   * <p>
   * Maps to the STVN {@code :Either(L R)} sum type, utilizing {@code #Left value} or {@code #Right value} tags.
   * Supports implied tagging where a right-compatible value can be resolved without an explicit wrapper.
   *
   * @param schema      the resolved schema mapping this node
   * @param value       the wrapped value payload
   * @param isRight     true if the value represents the right variant, false for the left
   * @param isAmbiguous true if the value is structurally compatible with both left and right options,
   *                    requiring explicit tagging to avoid resolution failure
   * @param trajectory  the variant resolution trajectory metadata
   */
  record StvnEither(ResolvedSchema schema, StvnValue value, boolean isRight,
                    boolean isAmbiguous, List<VariantStep> trajectory) implements StvnSum {
    /**
     * Canonical constructor validating that schema, value and trajectory are non-null.
     *
     * @param schema      the resolved schema mapping this node
     * @param value       the wrapped value payload
     * @param isRight     true if the value represents the right variant
     * @param isAmbiguous true if the value is structurally compatible with both variants
     * @param trajectory  the variant resolution trajectory metadata
     */
    public StvnEither {
      java.util.Objects.requireNonNull(schema);
      java.util.Objects.requireNonNull(value);
      java.util.Objects.requireNonNull(trajectory);
      trajectory = List.copyOf(trajectory);
    }

    /**
     * Overloaded constructor defaulting trajectory to an empty list.
     *
     * @param schema      the resolved schema mapping this node
     * @param value       the wrapped value payload
     * @param isRight     true if the value represents the right variant
     * @param isAmbiguous true if the value is structurally compatible with both variants
     */
    public StvnEither(ResolvedSchema schema, StvnValue value, boolean isRight, boolean isAmbiguous) {
      this(schema, value, isRight, isAmbiguous, List.of());
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof StvnEither that)) return false;
      return this.isRight == that.isRight &&
             this.isAmbiguous == that.isAmbiguous &&
             java.util.Objects.equals(this.schema, that.schema) &&
             java.util.Objects.equals(this.value, that.value);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(schema, value, isRight, isAmbiguous);
    }

    @Override
    public String toString() {
      return "StvnEither[schema=" + schema + ", value=" + value + ", isRight=" + isRight + ", isAmbiguous=" + isAmbiguous + "]";
    }
  }

  /**
   * Represents an N-way variant selection plane where the matching option is resolved structurally.
   * <p>
   * Maps to the STVN {@code :Union} type.
   * To ensure single-pass deterministic matching, member variants must possess completely distinct
   * structural profiles (the Union Structural Distinctness Rule).
   *
   * @param schema   the resolved schema mapping this node
   * @param value    the underlying active payload value
   * @param tagIndex the resolved 0-based index of the matched variant within the union schema list
   */
  record StvnUnion(ResolvedSchema schema, StvnValue value, int tagIndex) implements StvnSum {
    /**
     * Canonical constructor validating that schema and value are non-null.
     *
     * @param schema   the resolved schema mapping this node
     * @param value    the underlying active payload value
     * @param tagIndex the resolved 0-based index of the matched variant
     */
    public StvnUnion {
      java.util.Objects.requireNonNull(schema);
      java.util.Objects.requireNonNull(value);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof StvnUnion that)) return false;
      return this.tagIndex == that.tagIndex &&
             java.util.Objects.equals(this.schema, that.schema) &&
             java.util.Objects.equals(this.value, that.value);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(schema, value, tagIndex);
    }
  }

  /**
   * Represents a nominal enum keyword constant.
   * <p>
   * Maps to the STVN {@code :Enum} type. Enum values are serialized as keyword identifiers prefixed
   * with a hash (e.g. {@code #RED}).
   *
   * @param schema          the resolved schema mapping this enum
   * @param keyword         the nominal string keyword identifier (excluding the hash character)
   * @param sequentialIndex the 0-based index position of the keyword in the enum declaration
   * @param variantCount    the total number of defined enum options in the parent schema
   */
  record StvnEnum(ResolvedSchema schema, String keyword, int sequentialIndex, int variantCount) implements StvnSum {
    /**
     * Canonical constructor validating that schema and keyword are non-null.
     *
     * @param schema          the resolved schema mapping this enum
     * @param keyword         the nominal string keyword identifier
     * @param sequentialIndex the 0-based index position
     * @param variantCount    the total number of enum options
     */
    public StvnEnum {
      java.util.Objects.requireNonNull(schema);
      java.util.Objects.requireNonNull(keyword);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof StvnEnum that)) return false;
      return this.sequentialIndex == that.sequentialIndex &&
             this.variantCount == that.variantCount &&
             java.util.Objects.equals(this.schema, that.schema) &&
             java.util.Objects.equals(this.keyword, that.keyword);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(schema, keyword, sequentialIndex, variantCount);
    }
  }

  /**
   * Represents an error-tolerant AST node constructed during semantic error recovery.
   * <p>
   * Allows partial AST construction and downstream inspection even when individual AST child
   * branches fail type unification, range constraint validation, or uniqueness checks.
   *
   * @param schema      the expected resolved schema that was being validated, or fallback schema
   * @param rawText     the raw source text fragment corresponding to the erroneous node
   * @param startOffset the 0-based absolute character start index
   * @param endOffset   the 0-based absolute character end index
   * @param diagnostics the diagnostics associated with this specific erroneous sub-tree
   * @since 1.3.0
   */
  record StvnError(
      ResolvedSchema schema,
      String rawText,
      int startOffset,
      int endOffset,
      List<org.stvnadore.core.StvnDiagnostic> diagnostics
  ) implements StvnValue {
    /**
     * Canonical constructor validating that schema, rawText, and diagnostics are non-null.
     *
     * @param schema      the expected resolved schema that was being validated
     * @param rawText     the raw source text fragment
     * @param startOffset the 0-based absolute start offset
     * @param endOffset   the 0-based absolute end offset
     * @param diagnostics the diagnostics associated with this node
     */
    public StvnError {
      java.util.Objects.requireNonNull(schema);
      java.util.Objects.requireNonNull(rawText);
      java.util.Objects.requireNonNull(diagnostics);
      diagnostics = List.copyOf(diagnostics);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof StvnError that)) return false;
      return this.startOffset == that.startOffset &&
             this.endOffset == that.endOffset &&
             java.util.Objects.equals(this.schema, that.schema) &&
             java.util.Objects.equals(this.rawText, that.rawText) &&
             java.util.Objects.equals(this.diagnostics, that.diagnostics);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(schema, rawText, startOffset, endOffset, diagnostics);
    }

    @Override
    public String toString() {
      return "StvnError[schema=" + (schema != null ? schema.aliasName().orElse("Anonymous") : "null") +
             ", span=[" + startOffset + ".." + endOffset + "], raw=\"" + rawText + "\"]";
    }
  }
}
