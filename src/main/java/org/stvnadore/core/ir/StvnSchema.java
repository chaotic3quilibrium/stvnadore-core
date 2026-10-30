package org.stvnadore.core.ir;

import org.jspecify.annotations.NullMarked;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Closed 16-variant Canonical Intermediate Representation (IR) schema algebra for STVN 2.0.0.
 * <p>
 * Every schema node carries an optional opaque nominal Fully Qualified Nominal Identifier (FQNI)
 * brand ({@link #aliasName()}) and an effective {@link TraitProfile} ({@link #traits()}).
 * All tautological surface facets ({@code W003}) are normalized away prior to instantiation,
 * and all integer bit-widths, capacities, and discrete bounds use {@link BigInteger}.
 *
 * @since 2.0.0
 */
@NullMarked
public sealed interface StvnSchema permits
    StvnSchema.BooleanSchema,
    StvnSchema.IntSchema,
    StvnSchema.FloatSchema,
    StvnSchema.StringSchema,
    StvnSchema.TimeEpochSchema,
    StvnSchema.DateTimeSchema,
    StvnSchema.EnumSchema,
    StvnSchema.EnumSubsetSchema,
    StvnSchema.TupleSchema,
    StvnSchema.OptionSchema,
    StvnSchema.EitherSchema,
    StvnSchema.UnionSchema,
    StvnSchema.SeqSchema,
    StvnSchema.SetSchema,
    StvnSchema.MapSchema,
    StvnSchema.PoisonedSchema {

  /**
   * Returns the opaque nominal Fully Qualified Nominal Identifier brand, or empty if anonymous.
   *
   * @return the optional nominal FQNI brand
   */
  Optional<String> aliasName();

  /**
   * Returns the effective Tier-2 algebraic trait profile for equality and ordering.
   *
   * @return the effective {@link TraitProfile}
   */
  TraitProfile traits();

  /**
   * Immutable Tier-2 algebraic trait profile capturing effective {@code #equatable} and
   * {@code #comparable} capabilities alongside explicit non-tautological overrides.
   *
   * @param equatable                  effective equality capability
   * @param comparable                 effective total-order comparability capability
   * @param explicitEquatableOverride  explicit {@code #equatable} override when distinct from intrinsic default
   * @param explicitComparableOverride explicit {@code #comparable} override when distinct from intrinsic default
   * @since 2.0.0
   */
  record TraitProfile(
      boolean equatable,
      boolean comparable,
      Optional<Boolean> explicitEquatableOverride,
      Optional<Boolean> explicitComparableOverride
  ) {

    /**
     * Canonical constructor verifying non-null optional override containers.
     *
     * @param equatable                  effective equality capability
     * @param comparable                 effective total-order comparability capability
     * @param explicitEquatableOverride  explicit {@code #equatable} override container
     * @param explicitComparableOverride explicit {@code #comparable} override container
     */
    public TraitProfile {
      Objects.requireNonNull(explicitEquatableOverride, "explicitEquatableOverride must not be null");
      Objects.requireNonNull(explicitComparableOverride, "explicitComparableOverride must not be null");
    }

    /**
     * Creates a default trait profile with no explicit overrides.
     *
     * @param equatable  intrinsic equality capability
     * @param comparable intrinsic comparability capability
     * @return a new {@link TraitProfile} with empty override optionals
     */
    public static TraitProfile ofDefault(boolean equatable, boolean comparable) {
      return new TraitProfile(equatable, comparable, Optional.empty(), Optional.empty());
    }
  }

  /**
   * Physical tick duration scale for {@code :TimeEpoch} schemas.
   *
   * @since 2.0.0
   */
  enum TemporalScale {
    /** Nanosecond epoch ticks ({@code #ns}) backed by 128-bit signed capacity. */
    NS(new BigInteger("128")),
    /** Microsecond epoch ticks ({@code #us}) backed by 64-bit signed capacity. */
    US(new BigInteger("64")),
    /** Millisecond epoch ticks ({@code #ms}) backed by 64-bit signed capacity. */
    MS(new BigInteger("64")),
    /** Second epoch ticks ({@code #s}) backed by 64-bit signed capacity. */
    S(new BigInteger("64"));

    private final BigInteger bitWidth;

    TemporalScale(BigInteger bitWidth) {
      this.bitWidth = bitWidth;
    }

    /**
     * Returns the physical signed two's-complement bit-width allocated to this epoch scale.
     *
     * @return the storage bit-width as a {@link BigInteger}
     */
    public BigInteger bitWidth() {
      return bitWidth;
    }
  }

  /**
   * Tripartite civil and physical timestamp mode for {@code :DateTime} schemas.
   *
   * @since 2.0.0
   */
  enum DateTimeMode {
    /** Physical instant with explicit numerical UTC offset ({@code #offset}). */
    OFFSET,
    /** Civil wall-clock timestamp bound to an IANA jurisdiction ({@code #zoned}). */
    ZONED,
    /** Historical compliance timestamp carrying both UTC offset and IANA jurisdiction ({@code #audited}). */
    AUDITED
  }

  /**
   * Arithmetic representation mode for {@code :Float} schemas.
   *
   * @since 2.0.0
   */
  enum FloatRepresentation {
    /** IEEE-754 binary32 single-precision floating-point ({@code #size 32}). */
    IEEE_32,
    /** IEEE-754 binary64 double-precision floating-point (default 64-bit). */
    IEEE_64,
    /** Exact base-10 arbitrary-precision decimal ({@code #exact}). */
    EXACT_DECIMAL
  }

  /**
   * Endpoint inclusion classification for continuous interval bounds.
   *
   * @since 2.0.0
   */
  enum BoundKind {
    /** Closed inclusive endpoint ({@code #minIncl} or {@code #maxIncl}). */
    INCLUSIVE,
    /** Open exclusive endpoint ({@code #minExcl} or {@code #maxExcl}). */
    EXCLUSIVE
  }

  /**
   * Typed endpoint bound for continuous domains ({@code :Float} and {@code :String}).
   *
   * @param <T>   the domain value type ({@link BigDecimal} or {@link String})
   * @param value the bound threshold value
   * @param kind  whether the endpoint is {@link BoundKind#INCLUSIVE} or {@link BoundKind#EXCLUSIVE}
   * @since 2.0.0
   */
  record ContinuousBound<T>(T value, BoundKind kind) {

    /**
     * Canonical constructor verifying non-null bound value and endpoint kind.
     *
     * @param value the bound threshold value
     * @param kind  the endpoint inclusion kind
     */
    public ContinuousBound {
      Objects.requireNonNull(value, "value must not be null");
      Objects.requireNonNull(kind, "kind must not be null");
    }
  }

  /**
   * Normalized schema for {@code :Boolean} truth values.
   *
   * @param aliasName optional nominal FQNI brand
   * @param traits    effective trait profile
   * @since 2.0.0
   */
  record BooleanSchema(Optional<String> aliasName, TraitProfile traits) implements StvnSchema {

    /**
     * Canonical constructor verifying non-null components.
     *
     * @param aliasName optional nominal FQNI brand
     * @param traits    effective trait profile
     */
    public BooleanSchema {
      Objects.requireNonNull(aliasName, "aliasName must not be null");
      Objects.requireNonNull(traits, "traits must not be null");
    }
  }

  /**
   * Normalized schema for signed and unsigned arbitrary-bit-width {@code :Int} scalars.
   *
   * @param aliasName       optional nominal FQNI brand
   * @param unsigned        {@code true} when {@code #unsigned} is active; {@code false} for signed two's-complement
   * @param bitWidth        positive storage bit-width (&gt;= 1) in CPU-bias-free expression space
   * @param traits          effective trait profile
   * @param explicitMinIncl explicit non-tautological {@code #minIncl} lower bound, if declared
   * @param explicitMaxExcl explicit non-tautological {@code #maxExcl} upper bound, if declared
   * @since 2.0.0
   */
  record IntSchema(
      Optional<String> aliasName,
      boolean unsigned,
      BigInteger bitWidth,
      TraitProfile traits,
      Optional<BigInteger> explicitMinIncl,
      Optional<BigInteger> explicitMaxExcl
  ) implements StvnSchema {

    /**
     * Canonical constructor verifying non-null components and positive bit-width (&gt;= 1).
     *
     * @param aliasName       optional nominal FQNI brand
     * @param unsigned        whether unsigned domain rules apply
     * @param bitWidth        positive storage bit-width (&gt;= 1)
     * @param traits          effective trait profile
     * @param explicitMinIncl explicit {@code #minIncl} lower bound
     * @param explicitMaxExcl explicit {@code #maxExcl} upper bound
     */
    public IntSchema {
      Objects.requireNonNull(aliasName, "aliasName must not be null");
      Objects.requireNonNull(bitWidth, "bitWidth must not be null");
      Objects.requireNonNull(traits, "traits must not be null");
      Objects.requireNonNull(explicitMinIncl, "explicitMinIncl must not be null");
      Objects.requireNonNull(explicitMaxExcl, "explicitMaxExcl must not be null");
      if (bitWidth.compareTo(BigInteger.ONE) < 0) {
        throw new IllegalArgumentException("bitWidth must be >= 1, got: " + bitWidth);
      }
    }

    /**
     * Evaluates whether {@code candidate} fits within this schema's {@code bitWidth} capacity
     * in {@code O(1)} time and space using symbolic bit-length comparison without materializing
     * {@code 2^bitWidth} in memory.
     *
     * @param candidate the integer candidate to evaluate
     * @return {@code true} if {@code candidate} fits within {@code [0, 2^bitWidth)} when unsigned
     *         or {@code [-2^(bitWidth-1), 2^(bitWidth-1))} when signed; {@code false} otherwise
     */
    public boolean acceptsWidth(BigInteger candidate) {
      Objects.requireNonNull(candidate, "candidate must not be null");
      if (unsigned) {
        return candidate.signum() >= 0
            && BigInteger.valueOf(candidate.bitLength()).compareTo(bitWidth) <= 0;
      }
      if (candidate.signum() >= 0) {
        return BigInteger.valueOf(candidate.bitLength())
            .compareTo(bitWidth.subtract(BigInteger.ONE)) <= 0;
      }
      return BigInteger.valueOf(candidate.add(BigInteger.ONE).negate().bitLength())
          .compareTo(bitWidth.subtract(BigInteger.ONE)) <= 0;
    }
  }

  /**
   * Normalized schema for IEEE-754 machine floating-point and {@code #exact} arbitrary-precision
   * decimal {@code :Float} scalars.
   *
   * @param aliasName      optional nominal FQNI brand
   * @param representation IEEE-754 width or exact decimal representation mode
   * @param exactMaxDigits maximum significant decimal digits when {@link FloatRepresentation#EXACT_DECIMAL}
   * @param traits         effective trait profile
   * @param lowerBound     optional lower bound ({@code #minIncl} or {@code #minExcl})
   * @param upperBound     optional upper bound ({@code #maxIncl} or {@code #maxExcl})
   * @since 2.0.0
   */
  record FloatSchema(
      Optional<String> aliasName,
      FloatRepresentation representation,
      BigInteger exactMaxDigits,
      TraitProfile traits,
      Optional<ContinuousBound<BigDecimal>> lowerBound,
      Optional<ContinuousBound<BigDecimal>> upperBound
  ) implements StvnSchema {

    /**
     * Canonical constructor verifying non-null components.
     *
     * @param aliasName      optional nominal FQNI brand
     * @param representation floating-point representation mode
     * @param exactMaxDigits maximum significant digits for exact decimals
     * @param traits         effective trait profile
     * @param lowerBound     optional lower bound
     * @param upperBound     optional upper bound
     */
    public FloatSchema {
      Objects.requireNonNull(aliasName, "aliasName must not be null");
      Objects.requireNonNull(representation, "representation must not be null");
      Objects.requireNonNull(exactMaxDigits, "exactMaxDigits must not be null");
      Objects.requireNonNull(traits, "traits must not be null");
      Objects.requireNonNull(lowerBound, "lowerBound must not be null");
      Objects.requireNonNull(upperBound, "upperBound must not be null");
    }
  }

  /**
   * Normalized schema for UTF-8 {@code :String} character sequences.
   *
   * @param aliasName       optional nominal FQNI brand
   * @param minSize         minimum UTF-8 code-point count (&gt;= 0)
   * @param maxSize         maximum UTF-8 code-point count (&gt;= minSize)
   * @param traits          effective trait profile
   * @param lowerBound      optional UTF-8 lexicographical lower bound
   * @param upperBound      optional UTF-8 lexicographical upper bound
   * @param preserveIndent  whether fenced multi-line block strings retain common leading indentation
   * @param compiledRegex   compiled ECMA-262 / Java regular expression pattern, if declared
   * @param rawRegexPattern canonical unescaped regular expression text, if declared
   * @since 2.0.0
   */
  record StringSchema(
      Optional<String> aliasName,
      BigInteger minSize,
      BigInteger maxSize,
      TraitProfile traits,
      Optional<ContinuousBound<String>> lowerBound,
      Optional<ContinuousBound<String>> upperBound,
      boolean preserveIndent,
      Optional<Pattern> compiledRegex,
      Optional<String> rawRegexPattern
  ) implements StvnSchema {

    /**
     * Canonical constructor verifying non-null components.
     *
     * @param aliasName       optional nominal FQNI brand
     * @param minSize         minimum character count
     * @param maxSize         maximum character count
     * @param traits          effective trait profile
     * @param lowerBound      optional lexicographical lower bound
     * @param upperBound      optional lexicographical upper bound
     * @param preserveIndent  whether fenced block indentation is preserved
     * @param compiledRegex   optional compiled regex pattern
     * @param rawRegexPattern optional raw regex pattern string
     */
    public StringSchema {
      Objects.requireNonNull(aliasName, "aliasName must not be null");
      Objects.requireNonNull(minSize, "minSize must not be null");
      Objects.requireNonNull(maxSize, "maxSize must not be null");
      Objects.requireNonNull(traits, "traits must not be null");
      Objects.requireNonNull(lowerBound, "lowerBound must not be null");
      Objects.requireNonNull(upperBound, "upperBound must not be null");
      Objects.requireNonNull(compiledRegex, "compiledRegex must not be null");
      Objects.requireNonNull(rawRegexPattern, "rawRegexPattern must not be null");
    }
  }

  /**
   * Normalized schema for physical epoch tick {@code :TimeEpoch} scalars.
   *
   * @param aliasName       optional nominal FQNI brand
   * @param scale           mandatory physical tick duration scale ({@code #ns}, {@code #us}, {@code #ms}, {@code #s})
   * @param traits          effective trait profile
   * @param explicitMinIncl explicit non-tautological {@code #minIncl} tick bound, if declared
   * @param explicitMaxExcl explicit non-tautological {@code #maxExcl} tick bound, if declared
   * @since 2.0.0
   */
  record TimeEpochSchema(
      Optional<String> aliasName,
      TemporalScale scale,
      TraitProfile traits,
      Optional<BigInteger> explicitMinIncl,
      Optional<BigInteger> explicitMaxExcl
  ) implements StvnSchema {

    /**
     * Canonical constructor verifying non-null components.
     *
     * @param aliasName       optional nominal FQNI brand
     * @param scale           mandatory epoch tick scale
     * @param traits          effective trait profile
     * @param explicitMinIncl explicit {@code #minIncl} tick bound
     * @param explicitMaxExcl explicit {@code #maxExcl} tick bound
     */
    public TimeEpochSchema {
      Objects.requireNonNull(aliasName, "aliasName must not be null");
      Objects.requireNonNull(scale, "scale must not be null");
      Objects.requireNonNull(traits, "traits must not be null");
      Objects.requireNonNull(explicitMinIncl, "explicitMinIncl must not be null");
      Objects.requireNonNull(explicitMaxExcl, "explicitMaxExcl must not be null");
    }
  }

  /**
   * Normalized schema for tripartite {@code :DateTime} calendar and wall-clock timestamps.
   *
   * @param aliasName          optional nominal FQNI brand
   * @param mode               mandatory datetime mode ({@code #offset}, {@code #zoned}, {@code #audited})
   * @param traits             effective trait profile
   * @param explicitMinInclIso explicit {@code #minIncl} ISO-8601 timestamp bound, if declared
   * @param explicitMaxExclIso explicit {@code #maxExcl} ISO-8601 timestamp bound, if declared
   * @since 2.0.0
   */
  record DateTimeSchema(
      Optional<String> aliasName,
      DateTimeMode mode,
      TraitProfile traits,
      Optional<String> explicitMinInclIso,
      Optional<String> explicitMaxExclIso
  ) implements StvnSchema {

    /**
     * Canonical constructor verifying non-null components.
     *
     * @param aliasName          optional nominal FQNI brand
     * @param mode               mandatory datetime mode
     * @param traits             effective trait profile
     * @param explicitMinInclIso explicit {@code #minIncl} ISO-8601 bound
     * @param explicitMaxExclIso explicit {@code #maxExcl} ISO-8601 bound
     */
    public DateTimeSchema {
      Objects.requireNonNull(aliasName, "aliasName must not be null");
      Objects.requireNonNull(mode, "mode must not be null");
      Objects.requireNonNull(traits, "traits must not be null");
      Objects.requireNonNull(explicitMinInclIso, "explicitMinInclIso must not be null");
      Objects.requireNonNull(explicitMaxExclIso, "explicitMaxExclIso must not be null");
    }
  }

  /**
   * Normalized schema for root {@code :Enum} symbolic enumerations.
   *
   * @param aliasName    optional nominal FQNI brand
   * @param rootVariants ordered unmodifiable list of declared variant tags (for example, {@code #ACTIVE})
   * @param traits       effective trait profile
   * @since 2.0.0
   */
  record EnumSchema(
      Optional<String> aliasName,
      List<String> rootVariants,
      TraitProfile traits
  ) implements StvnSchema {

    /**
     * Canonical constructor verifying non-null components and defensively copying {@code rootVariants}.
     *
     * @param aliasName    optional nominal FQNI brand
     * @param rootVariants ordered list of declared variant tags
     * @param traits       effective trait profile
     */
    public EnumSchema {
      Objects.requireNonNull(aliasName, "aliasName must not be null");
      Objects.requireNonNull(rootVariants, "rootVariants must not be null");
      Objects.requireNonNull(traits, "traits must not be null");
      rootVariants = List.copyOf(rootVariants);
    }
  }

  /**
   * Normalized schema for filtered nominal {@code :Enum} subset projections
   * ({@code #filterIncl} or {@code #filterExcl}).
   *
   * @param aliasName          optional nominal FQNI brand
   * @param parentEnum         immediate parent {@link EnumSchema} or {@link EnumSubsetSchema}
   * @param rootEnum           terminal root {@link EnumSchema} defining wire ordinals
   * @param activeVariants     ordered unmodifiable list of permitted variants in root declaration order
   * @param inclusiveFilter    {@code true} for {@code #filterIncl}; {@code false} for {@code #filterExcl}
   * @param declaredFilterList ordered unmodifiable list of variants written in the filter facet
   * @param traits             effective trait profile
   * @since 2.0.0
   */
  record EnumSubsetSchema(
      Optional<String> aliasName,
      StvnSchema parentEnum,
      EnumSchema rootEnum,
      List<String> activeVariants,
      boolean inclusiveFilter,
      List<String> declaredFilterList,
      TraitProfile traits
  ) implements StvnSchema {

    /**
     * Canonical constructor verifying non-null components and defensively copying variant lists.
     *
     * @param aliasName          optional nominal FQNI brand
     * @param parentEnum         immediate parent enum or subset schema
     * @param rootEnum           terminal root enum schema
     * @param activeVariants     ordered list of permitted variants
     * @param inclusiveFilter    whether {@code #filterIncl} was declared
     * @param declaredFilterList ordered list of variants in the filter facet
     * @param traits             effective trait profile
     */
    public EnumSubsetSchema {
      Objects.requireNonNull(aliasName, "aliasName must not be null");
      Objects.requireNonNull(parentEnum, "parentEnum must not be null");
      Objects.requireNonNull(rootEnum, "rootEnum must not be null");
      Objects.requireNonNull(activeVariants, "activeVariants must not be null");
      Objects.requireNonNull(declaredFilterList, "declaredFilterList must not be null");
      Objects.requireNonNull(traits, "traits must not be null");
      activeVariants = List.copyOf(activeVariants);
      declaredFilterList = List.copyOf(declaredFilterList);
    }
  }

  /**
   * Normalized schema for heterogeneous positional {@code :Tuple} composite types.
   *
   * @param aliasName    optional nominal FQNI brand
   * @param fieldSchemas ordered unmodifiable list of positional member schemas (arity &gt;= 1)
   * @param traits       effective trait profile
   * @since 2.0.0
   */
  record TupleSchema(
      Optional<String> aliasName,
      List<StvnSchema> fieldSchemas,
      TraitProfile traits
  ) implements StvnSchema {

    /**
     * Canonical constructor verifying non-null components and defensively copying {@code fieldSchemas}.
     *
     * @param aliasName    optional nominal FQNI brand
     * @param fieldSchemas ordered list of positional member schemas
     * @param traits       effective trait profile
     */
    public TupleSchema {
      Objects.requireNonNull(aliasName, "aliasName must not be null");
      Objects.requireNonNull(fieldSchemas, "fieldSchemas must not be null");
      Objects.requireNonNull(traits, "traits must not be null");
      fieldSchemas = List.copyOf(fieldSchemas);
    }
  }

  /**
   * Normalized schema for optional {@code :Option} monadic sum types.
   *
   * @param aliasName   optional nominal FQNI brand
   * @param valueSchema wrapped inner payload schema
   * @param traits      effective trait profile
   * @since 2.0.0
   */
  record OptionSchema(
      Optional<String> aliasName,
      StvnSchema valueSchema,
      TraitProfile traits
  ) implements StvnSchema {

    /**
     * Canonical constructor verifying non-null components.
     *
     * @param aliasName   optional nominal FQNI brand
     * @param valueSchema wrapped inner payload schema
     * @param traits      effective trait profile
     */
    public OptionSchema {
      Objects.requireNonNull(aliasName, "aliasName must not be null");
      Objects.requireNonNull(valueSchema, "valueSchema must not be null");
      Objects.requireNonNull(traits, "traits must not be null");
    }
  }

  /**
   * Normalized schema for right-biased binary {@code :Either} disjoint sum types.
   *
   * @param aliasName   optional nominal FQNI brand
   * @param leftSchema  left ({@code #Left}) branch schema
   * @param rightSchema right ({@code #Right}) branch schema
   * @param traits      effective trait profile
   * @since 2.0.0
   */
  record EitherSchema(
      Optional<String> aliasName,
      StvnSchema leftSchema,
      StvnSchema rightSchema,
      TraitProfile traits
  ) implements StvnSchema {

    /**
     * Canonical constructor verifying non-null components.
     *
     * @param aliasName   optional nominal FQNI brand
     * @param leftSchema  left branch schema
     * @param rightSchema right branch schema
     * @param traits      effective trait profile
     */
    public EitherSchema {
      Objects.requireNonNull(aliasName, "aliasName must not be null");
      Objects.requireNonNull(leftSchema, "leftSchema must not be null");
      Objects.requireNonNull(rightSchema, "rightSchema must not be null");
      Objects.requireNonNull(traits, "traits must not be null");
    }
  }

  /**
   * Normalized schema for 1-based indexed {@code :Union} multi-branch sum types.
   *
   * @param aliasName     optional nominal FQNI brand
   * @param branchSchemas ordered unmodifiable list of branch schemas (arity &gt;= 2)
   * @param traits        effective trait profile
   * @since 2.0.0
   */
  record UnionSchema(
      Optional<String> aliasName,
      List<StvnSchema> branchSchemas,
      TraitProfile traits
  ) implements StvnSchema {

    /**
     * Canonical constructor verifying non-null components and defensively copying {@code branchSchemas}.
     *
     * @param aliasName     optional nominal FQNI brand
     * @param branchSchemas ordered list of branch schemas
     * @param traits        effective trait profile
     */
    public UnionSchema {
      Objects.requireNonNull(aliasName, "aliasName must not be null");
      Objects.requireNonNull(branchSchemas, "branchSchemas must not be null");
      Objects.requireNonNull(traits, "traits must not be null");
      branchSchemas = List.copyOf(branchSchemas);
    }
  }

  /**
   * Normalized schema for homogeneous ordered {@code :Seq} sequence collections.
   *
   * @param aliasName     optional nominal FQNI brand
   * @param minSize       minimum element cardinality (&gt;= 0)
   * @param maxSize       maximum element cardinality (&gt;= minSize)
   * @param elementSchema homogeneous member element schema
   * @param traits        effective trait profile
   * @since 2.0.0
   */
  record SeqSchema(
      Optional<String> aliasName,
      BigInteger minSize,
      BigInteger maxSize,
      StvnSchema elementSchema,
      TraitProfile traits
  ) implements StvnSchema {

    /**
     * Canonical constructor verifying non-null components.
     *
     * @param aliasName     optional nominal FQNI brand
     * @param minSize       minimum element cardinality
     * @param maxSize       maximum element cardinality
     * @param elementSchema homogeneous member element schema
     * @param traits        effective trait profile
     */
    public SeqSchema {
      Objects.requireNonNull(aliasName, "aliasName must not be null");
      Objects.requireNonNull(minSize, "minSize must not be null");
      Objects.requireNonNull(maxSize, "maxSize must not be null");
      Objects.requireNonNull(elementSchema, "elementSchema must not be null");
      Objects.requireNonNull(traits, "traits must not be null");
    }
  }

  /**
   * Normalized schema for insertion-ordered unique {@code :Set} collections.
   *
   * @param aliasName     optional nominal FQNI brand
   * @param minSize       minimum element cardinality (&gt;= 0)
   * @param maxSize       maximum element cardinality (&gt;= minSize)
   * @param elementSchema homogeneous equatable element schema
   * @param traits        effective trait profile
   * @since 2.0.0
   */
  record SetSchema(
      Optional<String> aliasName,
      BigInteger minSize,
      BigInteger maxSize,
      StvnSchema elementSchema,
      TraitProfile traits
  ) implements StvnSchema {

    /**
     * Canonical constructor verifying non-null components.
     *
     * @param aliasName     optional nominal FQNI brand
     * @param minSize       minimum element cardinality
     * @param maxSize       maximum element cardinality
     * @param elementSchema homogeneous equatable element schema
     * @param traits        effective trait profile
     */
    public SetSchema {
      Objects.requireNonNull(aliasName, "aliasName must not be null");
      Objects.requireNonNull(minSize, "minSize must not be null");
      Objects.requireNonNull(maxSize, "maxSize must not be null");
      Objects.requireNonNull(elementSchema, "elementSchema must not be null");
      Objects.requireNonNull(traits, "traits must not be null");
    }
  }

  /**
   * Normalized schema for insertion-ordered associative {@code :Map} collections.
   *
   * @param aliasName   optional nominal FQNI brand
   * @param invertible  {@code true} when {@code #invertible} enforces 1:1 bidirectional uniqueness
   * @param minSize     minimum entry cardinality (&gt;= 0)
   * @param maxSize     maximum entry cardinality (&gt;= minSize)
   * @param keySchema   equatable key schema
   * @param valueSchema mapped value schema (also equatable when {@code invertible} is {@code true})
   * @param traits      effective trait profile
   * @since 2.0.0
   */
  record MapSchema(
      Optional<String> aliasName,
      boolean invertible,
      BigInteger minSize,
      BigInteger maxSize,
      StvnSchema keySchema,
      StvnSchema valueSchema,
      TraitProfile traits
  ) implements StvnSchema {

    /**
     * Canonical constructor verifying non-null components.
     *
     * @param aliasName   optional nominal FQNI brand
     * @param invertible  whether bidirectional value uniqueness applies
     * @param minSize     minimum entry cardinality
     * @param maxSize     maximum entry cardinality
     * @param keySchema   equatable key schema
     * @param valueSchema mapped value schema
     * @param traits      effective trait profile
     */
    public MapSchema {
      Objects.requireNonNull(aliasName, "aliasName must not be null");
      Objects.requireNonNull(minSize, "minSize must not be null");
      Objects.requireNonNull(maxSize, "maxSize must not be null");
      Objects.requireNonNull(keySchema, "keySchema must not be null");
      Objects.requireNonNull(valueSchema, "valueSchema must not be null");
      Objects.requireNonNull(traits, "traits must not be null");
    }
  }

  /**
   * Degraded IDE error-recovery sentinel schema preventing cascading false-positive diagnostics
   * when a type definition fails semantic validation.
   *
   * @param aliasName             optional nominal FQNI brand
   * @param primaryDiagnosticCode canonical or symbolic diagnostic code that triggered poisoning
   * @param bestEffortFallback    optional best-effort structural fallback schema for partial inspection
   * @param traits                effective trait profile
   * @since 2.0.0
   */
  record PoisonedSchema(
      Optional<String> aliasName,
      String primaryDiagnosticCode,
      Optional<StvnSchema> bestEffortFallback,
      TraitProfile traits
  ) implements StvnSchema {

    /**
     * Canonical constructor verifying non-null components.
     *
     * @param aliasName             optional nominal FQNI brand
     * @param primaryDiagnosticCode diagnostic code that triggered poisoning
     * @param bestEffortFallback    optional best-effort structural fallback schema
     * @param traits                effective trait profile
     */
    public PoisonedSchema {
      Objects.requireNonNull(aliasName, "aliasName must not be null");
      Objects.requireNonNull(primaryDiagnosticCode, "primaryDiagnosticCode must not be null");
      Objects.requireNonNull(bestEffortFallback, "bestEffortFallback must not be null");
      Objects.requireNonNull(traits, "traits must not be null");
    }
  }
}
