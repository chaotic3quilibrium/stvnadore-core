package org.stvnadore.core.mapper;

import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.annotations.StvnBits;
import org.stvnadore.core.annotations.StvnInt;
import org.stvnadore.core.annotations.StvnString;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnSchema.FloatRepresentation;
import org.stvnadore.core.ir.StvnSchema.TraitProfile;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.ir.StvnValue.Ieee754SpecialKind;
import org.stvnadore.core.ir.StvnValue.KeyValueEntry;
import org.stvnadore.core.validation.MalformedPayloadException;
import org.stvnadore.core.validation.StvnIntegerOverflowException;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Pure-IR mapping engine for projecting Java 21 records, sealed interfaces, collections, and
 * scalars to and from STVN 2.0.0 Canonical Intermediate Representation nodes ({@link StvnValue}
 * and {@link StvnSchema}).
 * <p>
 * Supports {@link BigInteger} and {@link BigDecimal} record components natively, enforces exact
 * narrowing conversions ({@link BigInteger#intValueExact()}, {@link BigInteger#longValueExact()},
 * {@link BigInteger#shortValueExact()}, {@link BigInteger#byteValueExact()}) when projecting onto
 * hardware-sized Java primitives, rejects legacy mutable POJOs, and prevents {@code null} leakage
 * across {@link Optional} record components.
 *
 * @since 1.0.0
 */
public final class StvnMapper {

  private static final TraitProfile SCALAR_COMPARABLE_TRAITS = TraitProfile.ofDefault(true, true);
  private static final TraitProfile EQUATABLE_ONLY_TRAITS = TraitProfile.ofDefault(true, false);

  private static final StvnSchema.BooleanSchema DEFAULT_BOOLEAN_SCHEMA =
      new StvnSchema.BooleanSchema(Optional.empty(), SCALAR_COMPARABLE_TRAITS);

  private static final StvnSchema.IntSchema DEFAULT_INT8_SCHEMA =
      new StvnSchema.IntSchema(
          Optional.empty(), false, BigInteger.valueOf(8L), SCALAR_COMPARABLE_TRAITS, Optional.empty(), Optional.empty()
      );

  private static final StvnSchema.IntSchema DEFAULT_INT16_SCHEMA =
      new StvnSchema.IntSchema(
          Optional.empty(), false, BigInteger.valueOf(16L), SCALAR_COMPARABLE_TRAITS, Optional.empty(), Optional.empty()
      );

  private static final StvnSchema.IntSchema DEFAULT_INT32_SCHEMA =
      new StvnSchema.IntSchema(
          Optional.empty(), false, BigInteger.valueOf(32L), SCALAR_COMPARABLE_TRAITS, Optional.empty(), Optional.empty()
      );

  private static final StvnSchema.IntSchema DEFAULT_INT64_SCHEMA =
      new StvnSchema.IntSchema(
          Optional.empty(), false, BigInteger.valueOf(64L), SCALAR_COMPARABLE_TRAITS, Optional.empty(), Optional.empty()
      );

  private static final StvnSchema.FloatSchema DEFAULT_FLOAT32_SCHEMA =
      new StvnSchema.FloatSchema(
          Optional.empty(),
          FloatRepresentation.IEEE_32,
          StvnVocabulary.DEFAULT_MAX_SIZE,
          SCALAR_COMPARABLE_TRAITS,
          Optional.empty(),
          Optional.empty()
      );

  private static final StvnSchema.FloatSchema DEFAULT_FLOAT64_SCHEMA =
      new StvnSchema.FloatSchema(
          Optional.empty(),
          FloatRepresentation.IEEE_64,
          StvnVocabulary.DEFAULT_MAX_SIZE,
          SCALAR_COMPARABLE_TRAITS,
          Optional.empty(),
          Optional.empty()
      );

  private static final StvnSchema.FloatSchema DEFAULT_EXACT_DECIMAL_SCHEMA =
      new StvnSchema.FloatSchema(
          Optional.empty(),
          FloatRepresentation.EXACT_DECIMAL,
          StvnVocabulary.DEFAULT_MAX_SIZE,
          SCALAR_COMPARABLE_TRAITS,
          Optional.empty(),
          Optional.empty()
      );

  private static final StvnSchema.StringSchema DEFAULT_STRING_SCHEMA =
      new StvnSchema.StringSchema(
          Optional.empty(),
          BigInteger.ZERO,
          StvnVocabulary.DEFAULT_MAX_SIZE,
          SCALAR_COMPARABLE_TRAITS,
          Optional.empty(),
          Optional.empty(),
          false,
          Optional.empty(),
          Optional.empty()
      );

  private static final Map<Class<?>, StvnSchema> PRIMITIVE_SCHEMA_REGISTRY;

  static {
    var map = new HashMap<Class<?>, StvnSchema>();
    map.put(Boolean.class, DEFAULT_BOOLEAN_SCHEMA);
    map.put(boolean.class, DEFAULT_BOOLEAN_SCHEMA);

    map.put(Byte.class, DEFAULT_INT8_SCHEMA);
    map.put(byte.class, DEFAULT_INT8_SCHEMA);

    map.put(Short.class, DEFAULT_INT16_SCHEMA);
    map.put(short.class, DEFAULT_INT16_SCHEMA);

    map.put(Integer.class, DEFAULT_INT32_SCHEMA);
    map.put(int.class, DEFAULT_INT32_SCHEMA);

    map.put(Long.class, DEFAULT_INT64_SCHEMA);
    map.put(long.class, DEFAULT_INT64_SCHEMA);

    map.put(Float.class, DEFAULT_FLOAT32_SCHEMA);
    map.put(float.class, DEFAULT_FLOAT32_SCHEMA);

    map.put(Double.class, DEFAULT_FLOAT64_SCHEMA);
    map.put(double.class, DEFAULT_FLOAT64_SCHEMA);

    map.put(BigDecimal.class, DEFAULT_EXACT_DECIMAL_SCHEMA);
    map.put(String.class, DEFAULT_STRING_SCHEMA);

    PRIMITIVE_SCHEMA_REGISTRY = Collections.unmodifiableMap(map);
  }

  private StvnMapper() {}

  /**
   * Serializes a Java record, sealed interface, collection, or scalar instance into a pure-IR
   * {@link StvnValue} node governed by {@code schema}.
   *
   * @param recordInstance the Java value to serialize, or {@code null}
   * @param schema         the pure-IR schema definition
   * @return an {@link Optional} containing the serialized pure-IR {@link StvnValue}
   * @since 2.0.0
   */
  public static Optional<StvnValue> toValue(@Nullable Object recordInstance, StvnSchema schema) {
    Objects.requireNonNull(schema, "schema must not be null");
    return toValue(recordInstance, Optional.of(schema));
  }

  /**
   * Serializes a Java record, sealed interface, collection, or scalar instance into a pure-IR
   * {@link StvnValue} node using an optional pure-IR {@link StvnSchema}.
   *
   * @param recordInstance the Java value to serialize, or {@code null}
   * @param schemaOpt      the optional pure-IR schema definition
   * @return an {@link Optional} containing the serialized pure-IR {@link StvnValue}
   * @since 2.0.0
   */
  public static Optional<StvnValue> toValue(@Nullable Object recordInstance, Optional<StvnSchema> schemaOpt) {
    Objects.requireNonNull(schemaOpt, "schemaOpt must not be null");

    if (recordInstance == null) {
      if (schemaOpt.isPresent() && schemaOpt.get() instanceof StvnSchema.OptionSchema optSchema) {
        return Optional.of(new StvnValue.StvnOption(optSchema, Optional.empty()));
      }
      return Optional.empty();
    }

    if (recordInstance instanceof StvnValue irVal) {
      return Optional.of(irVal);
    }

    Class<?> runtimeClass = recordInstance.getClass();
    if (isPOJO(runtimeClass)) {
      throw new IllegalArgumentException("POJO types are rejected by the mapper: " + runtimeClass.getName());
    }

    if (recordInstance instanceof Optional<?> opt) {
      Optional<StvnSchema> innerSchemaOpt = getOptionInnerSchema(schemaOpt);
      Optional<StvnValue> mappedInner = opt.flatMap(inner -> toValue(inner, innerSchemaOpt));
      StvnSchema effectiveOptionSchema = resolveOptionSchema(schemaOpt, mappedInner.map(StvnValue::schema));
      return Optional.of(new StvnValue.StvnOption(effectiveOptionSchema, mappedInner));
    }
    if (recordInstance instanceof OptionalInt optInt) {
      Optional<StvnSchema> innerSchemaOpt = getOptionInnerSchema(schemaOpt);
      Optional<StvnValue> mappedInner = optInt.isPresent()
          ? toValue(optInt.getAsInt(), innerSchemaOpt)
          : Optional.empty();
      StvnSchema effectiveOptionSchema = resolveOptionSchema(
          schemaOpt, mappedInner.map(StvnValue::schema).or(() -> Optional.of(DEFAULT_INT32_SCHEMA))
      );
      return Optional.of(new StvnValue.StvnOption(effectiveOptionSchema, mappedInner));
    }
    if (recordInstance instanceof OptionalLong optLong) {
      Optional<StvnSchema> innerSchemaOpt = getOptionInnerSchema(schemaOpt);
      Optional<StvnValue> mappedInner = optLong.isPresent()
          ? toValue(optLong.getAsLong(), innerSchemaOpt)
          : Optional.empty();
      StvnSchema effectiveOptionSchema = resolveOptionSchema(
          schemaOpt, mappedInner.map(StvnValue::schema).or(() -> Optional.of(DEFAULT_INT64_SCHEMA))
      );
      return Optional.of(new StvnValue.StvnOption(effectiveOptionSchema, mappedInner));
    }
    if (recordInstance instanceof OptionalDouble optDouble) {
      Optional<StvnSchema> innerSchemaOpt = getOptionInnerSchema(schemaOpt);
      Optional<StvnValue> mappedInner = optDouble.isPresent()
          ? toValue(optDouble.getAsDouble(), innerSchemaOpt)
          : Optional.empty();
      StvnSchema effectiveOptionSchema = resolveOptionSchema(
          schemaOpt, mappedInner.map(StvnValue::schema).or(() -> Optional.of(DEFAULT_FLOAT64_SCHEMA))
      );
      return Optional.of(new StvnValue.StvnOption(effectiveOptionSchema, mappedInner));
    }

    if (SealedInterfaceMapper.isSealedInterface(runtimeClass)
        || (schemaOpt.isPresent() && isSealedSchema(schemaOpt.get()))) {
      Optional<Class<?>> sealedTypeOpt = findSealedInterface(runtimeClass);
      if (sealedTypeOpt.isPresent()) {
        return SealedInterfaceMapper.toValue(recordInstance, sealedTypeOpt.get(), schemaOpt);
      }
    }

    if (runtimeClass.isRecord()) {
      return serializeRecord(recordInstance, schemaOpt);
    }

    if (recordInstance instanceof Enum<?> enumConstant) {
      StvnSchema schema = schemaOpt.orElseThrow(
          () -> new MalformedPayloadException("Cannot map Java Enum without an EnumSchema: " + runtimeClass.getName())
      );
      String tag = "#" + enumConstant.name();
      List<String> rootVariants;
      List<String> activeVariants;
      if (schema instanceof StvnSchema.EnumSchema es) {
        rootVariants = es.rootVariants();
        activeVariants = es.rootVariants();
      } else if (schema instanceof StvnSchema.EnumSubsetSchema ess) {
        rootVariants = ess.rootEnum().rootVariants();
        activeVariants = ess.activeVariants();
      } else {
        throw new MalformedPayloadException(
            "Expected EnumSchema or EnumSubsetSchema for Java Enum " + runtimeClass.getName()
                + ", got: " + schema.getClass().getSimpleName()
        );
      }
      if (!activeVariants.contains(tag)) {
        throw new MalformedPayloadException("Enum variant " + tag + " is not permitted by schema " + activeVariants);
      }
      int rootIdx = rootVariants.indexOf(tag);
      return Optional.of(new StvnValue.StvnEnum(schema, tag, BigInteger.valueOf(rootIdx)));
    }

    if (recordInstance instanceof Boolean b) {
      StvnSchema boolSchema = (schemaOpt.isPresent() && schemaOpt.get() instanceof StvnSchema.BooleanSchema bs)
          ? bs
          : DEFAULT_BOOLEAN_SCHEMA;
      return Optional.of(new StvnValue.StvnBoolean(boolSchema, b));
    }

    if (recordInstance instanceof String s) {
      StvnSchema.StringSchema stringSchema = (schemaOpt.isPresent() && schemaOpt.get() instanceof StvnSchema.StringSchema ss)
          ? ss
          : DEFAULT_STRING_SCHEMA;
      validateStringAgainstSchema(s, stringSchema);
      return Optional.of(new StvnValue.StvnString(stringSchema, s));
    }

    if (recordInstance instanceof Number n) {
      return serializeNumber(n, schemaOpt);
    }

    if (recordInstance instanceof List<?> list) {
      Optional<StvnSchema> elemSchemaOpt = getCollectionInnerSchema(schemaOpt);
      var mapped = new ArrayList<StvnValue>(list.size());
      for (Object el : list) {
        StvnValue val = toValue(el, elemSchemaOpt)
            .orElseThrow(() -> new MalformedPayloadException("List elements cannot be null"));
        mapped.add(val);
      }
      StvnSchema seqSchema;
      if (schemaOpt.isPresent() && schemaOpt.get() instanceof StvnSchema.SeqSchema ss) {
        validateCardinality(mapped.size(), ss.minSize(), ss.maxSize(), "Seq");
        seqSchema = ss;
      } else {
        StvnSchema elemSchema = elemSchemaOpt
            .or(() -> mapped.isEmpty() ? Optional.empty() : Optional.of(mapped.get(0).schema()))
            .orElse(DEFAULT_STRING_SCHEMA);
        seqSchema = new StvnSchema.SeqSchema(
            Optional.empty(), BigInteger.ZERO, StvnVocabulary.DEFAULT_MAX_SIZE, elemSchema,
            TraitProfile.ofDefault(elemSchema.traits().equatable(), elemSchema.traits().comparable())
        );
      }
      return Optional.of(new StvnValue.StvnSeq(seqSchema, mapped));
    }

    if (recordInstance instanceof Set<?> set) {
      if (!(set instanceof SequencedSet)) {
        throw new MalformedPayloadException("Sets must implement SequencedSet. Found: " + runtimeClass.getName());
      }
      Optional<StvnSchema> elemSchemaOpt = getCollectionInnerSchema(schemaOpt);
      var unique = new LinkedHashSet<StvnValue>(set.size());
      for (Object el : set) {
        StvnValue val = toValue(el, elemSchemaOpt)
            .orElseThrow(() -> new MalformedPayloadException("Set elements cannot be null"));
        unique.add(val);
      }
      List<StvnValue> mapped = List.copyOf(unique);
      StvnSchema setSchema;
      if (schemaOpt.isPresent() && schemaOpt.get() instanceof StvnSchema.SetSchema ss) {
        validateCardinality(mapped.size(), ss.minSize(), ss.maxSize(), "Set");
        setSchema = ss;
      } else {
        StvnSchema elemSchema = elemSchemaOpt
            .or(() -> mapped.isEmpty() ? Optional.empty() : Optional.of(mapped.get(0).schema()))
            .orElse(DEFAULT_STRING_SCHEMA);
        setSchema = new StvnSchema.SetSchema(
            Optional.empty(), BigInteger.ZERO, StvnVocabulary.DEFAULT_MAX_SIZE, elemSchema, EQUATABLE_ONLY_TRAITS
        );
      }
      return Optional.of(new StvnValue.StvnSet(setSchema, mapped));
    }

    if (recordInstance instanceof Map<?, ?> map) {
      if (!(map instanceof SequencedMap)) {
        throw new MalformedPayloadException("Maps must implement SequencedMap. Found: " + runtimeClass.getName());
      }
      Optional<StvnSchema> keySchemaOpt = Optional.empty();
      Optional<StvnSchema> valSchemaOpt = Optional.empty();
      boolean invertible = false;
      if (schemaOpt.isPresent() && schemaOpt.get() instanceof StvnSchema.MapSchema ms) {
        keySchemaOpt = Optional.of(ms.keySchema());
        valSchemaOpt = Optional.of(ms.valueSchema());
        invertible = ms.invertible();
      }

      var entries = new ArrayList<KeyValueEntry>(map.size());
      var seenKeys = new HashSet<StvnValue>(map.size());
      var seenValues = invertible ? new HashSet<StvnValue>(map.size()) : null;
      for (var entry : map.entrySet()) {
        StvnValue k = toValue(entry.getKey(), keySchemaOpt)
            .orElseThrow(() -> new MalformedPayloadException("Map keys cannot be null"));
        StvnValue v = toValue(entry.getValue(), valSchemaOpt)
            .orElseThrow(() -> new MalformedPayloadException("Map values cannot be null"));
        if (!seenKeys.add(k)) {
          throw new MalformedPayloadException("Duplicate key detected in map: " + k);
        }
        if (seenValues != null && !seenValues.add(v)) {
          throw new MalformedPayloadException("Duplicate value detected in invertible map (MapInv): " + v);
        }
        entries.add(new KeyValueEntry(k, v));
      }

      StvnSchema mapSchema;
      if (schemaOpt.isPresent() && schemaOpt.get() instanceof StvnSchema.MapSchema ms) {
        validateCardinality(entries.size(), ms.minSize(), ms.maxSize(), "Map");
        mapSchema = ms;
      } else {
        StvnSchema kSchema = keySchemaOpt
            .or(() -> entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(0).key().schema()))
            .orElse(DEFAULT_STRING_SCHEMA);
        StvnSchema vSchema = valSchemaOpt
            .or(() -> entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(0).value().schema()))
            .orElse(DEFAULT_STRING_SCHEMA);
        mapSchema = new StvnSchema.MapSchema(
            Optional.empty(), false, BigInteger.ZERO, StvnVocabulary.DEFAULT_MAX_SIZE,
            kSchema, vSchema, EQUATABLE_ONLY_TRAITS
        );
      }
      return Optional.of(new StvnValue.StvnMap(mapSchema, entries));
    }

    throw new IllegalArgumentException("Unsupported type for mapping: " + runtimeClass.getName());
  }

  /**
   * Deserializes a pure-IR {@link StvnValue} node into an instance of {@code targetClass} governed
   * by {@code schema}.
   *
   * @param <T>         the target Java class type
   * @param irValue     the pure-IR STVN value tree to deserialize
   * @param targetClass the target Java class to instantiate
   * @param schema      the pure-IR schema definition
   * @return an {@link Optional} enclosing the deserialized instance
   * @since 2.0.0
   */
  public static <T> Optional<T> fromValue(StvnValue irValue, Class<T> targetClass, StvnSchema schema) {
    Objects.requireNonNull(irValue, "irValue must not be null");
    Objects.requireNonNull(targetClass, "targetClass must not be null");
    Objects.requireNonNull(schema, "schema must not be null");
    return fromValue(irValue, targetClass, Optional.of(schema));
  }

  /**
   * Deserializes a pure-IR {@link StvnValue} node into an instance of {@code targetClass} using
   * an optional pure-IR {@link StvnSchema}.
   *
   * @param <T>         the target Java class type
   * @param irValue     the pure-IR STVN value tree to deserialize
   * @param targetClass the target Java class to instantiate
   * @param schemaOpt   the optional pure-IR schema definition
   * @return an {@link Optional} enclosing the deserialized instance
   * @since 2.0.0
   */
  @SuppressWarnings("unchecked")
  public static <T> Optional<T> fromValue(StvnValue irValue, Class<T> targetClass, Optional<StvnSchema> schemaOpt) {
    Objects.requireNonNull(irValue, "irValue must not be null");
    Objects.requireNonNull(targetClass, "targetClass must not be null");
    Objects.requireNonNull(schemaOpt, "schemaOpt must not be null");

    if (isPOJO(targetClass)) {
      throw new IllegalArgumentException("POJO types are rejected by the mapper: " + targetClass.getName());
    }

    Object result = fromValueInternal(irValue, targetClass, targetClass, schemaOpt);
    if (result == null) {
      return Optional.empty();
    }
    if (targetClass.isPrimitive()) {
      return Optional.of((T) result);
    }
    return Optional.of(targetClass.cast(result));
  }

  private static Optional<StvnValue> serializeRecord(Object recordInstance, Optional<StvnSchema> schemaOpt) {
    var meta = StvnRecordCache.get(recordInstance.getClass());
    StvnSchema schema = schemaOpt.orElseThrow(
        () -> new MalformedPayloadException(
            "Cannot serialize record without a TupleSchema or MapSchema: " + recordInstance.getClass().getName()
        )
    );

    if (schema instanceof StvnSchema.TupleSchema tupleSchema) {
      List<StvnSchema> fieldSchemas = tupleSchema.fieldSchemas();
      var elements = new ArrayList<StvnValue>(meta.components().length);
      for (int i = 0; i < meta.components().length; i++) {
        var comp = meta.components()[i];
        Optional<StvnSchema> slotSchemaOpt = (i < fieldSchemas.size())
            ? Optional.of(fieldSchemas.get(i))
            : Optional.empty();
        Optional<StvnSchema> childSchemaOpt = resolveComponentSchemaForSerialization(comp, slotSchemaOpt, true);

        try {
          Object val = comp.accessorHandle().invoke(recordInstance);
          checkOptionalLoophole(comp, val);
          StvnValue mapped = toValue(val, childSchemaOpt)
              .orElseThrow(() -> new MalformedPayloadException("Missing value for component: " + comp.name()));
          mapped = validateAndEnrichComponentConstraints(comp, mapped);
          elements.add(mapped);
        } catch (RuntimeException re) {
          throw re;
        } catch (Throwable t) {
          throw new RuntimeException(t);
        }
      }
      return Optional.of(new StvnValue.StvnTuple(tupleSchema, elements));
    }

    // Default record projection to StvnMap
    Optional<StvnSchema> mapKeySchemaOpt = Optional.empty();
    Optional<StvnSchema> mapValSchemaOpt = Optional.empty();
    boolean invertible = false;
    if (schema instanceof StvnSchema.MapSchema ms) {
      mapKeySchemaOpt = Optional.of(ms.keySchema());
      mapValSchemaOpt = Optional.of(ms.valueSchema());
      invertible = ms.invertible();
    }

    StvnSchema keySchema = (mapKeySchemaOpt.isPresent() && mapKeySchemaOpt.get() instanceof StvnSchema.StringSchema ss)
        ? ss
        : DEFAULT_STRING_SCHEMA;

    var entries = new ArrayList<KeyValueEntry>(meta.components().length);
    var seenValues = invertible ? new HashSet<StvnValue>(meta.components().length) : null;

    for (var comp : meta.components()) {
      try {
        Object val = comp.accessorHandle().invoke(recordInstance);
        checkOptionalLoophole(comp, val);

        var keyVal = new StvnValue.StvnString(keySchema, comp.name());
        Optional<StvnSchema> childSchemaOpt = resolveComponentSchemaForSerialization(comp, mapValSchemaOpt, false);
        StvnValue mappedVal = toValue(val, childSchemaOpt)
            .orElseThrow(() -> new MalformedPayloadException("Missing value for component: " + comp.name()));
        mappedVal = validateAndEnrichComponentConstraints(comp, mappedVal);

        if (seenValues != null && !seenValues.add(mappedVal)) {
          throw new MalformedPayloadException("Duplicate value detected in invertible map (MapInv): " + mappedVal);
        }
        entries.add(new KeyValueEntry(keyVal, mappedVal));
      } catch (RuntimeException re) {
        throw re;
      } catch (Throwable t) {
        throw new RuntimeException(t);
      }
    }

    return Optional.of(new StvnValue.StvnMap(schema, entries));
  }

  private static Optional<StvnSchema> resolveComponentSchemaForSerialization(
      StvnRecordCache.RecordComponentProfile comp,
      Optional<StvnSchema> candidateSlotSchemaOpt,
      boolean positionalTupleSlot
  ) {
    StvnBits bits = comp.bitsAnnotation();
    if (bits != null) {
      if (bits.value() < 1) {
        throw new MalformedPayloadException(
            "Invalid @StvnBits bit-width " + bits.value() + " on component '" + comp.name() + "'"
        );
      }
      Optional<String> alias = candidateSlotSchemaOpt.flatMap(StvnSchema::aliasName);
      Optional<BigInteger> minIncl = (candidateSlotSchemaOpt.isPresent()
          && candidateSlotSchemaOpt.get() instanceof StvnSchema.IntSchema is)
          ? is.explicitMinIncl()
          : Optional.empty();
      Optional<BigInteger> maxExcl = (candidateSlotSchemaOpt.isPresent()
          && candidateSlotSchemaOpt.get() instanceof StvnSchema.IntSchema is)
          ? is.explicitMaxExcl()
          : Optional.empty();
      return Optional.of(new StvnSchema.IntSchema(
          alias, bits.unsigned(), BigInteger.valueOf(bits.value()), SCALAR_COMPARABLE_TRAITS, minIncl, maxExcl
      ));
    }

    if (candidateSlotSchemaOpt.isPresent()) {
      StvnSchema slot = candidateSlotSchemaOpt.get();
      if (positionalTupleSlot || isSchemaCompatibleWithJavaType(slot, comp.type())) {
        return Optional.of(slot);
      }
    }

    return Optional.ofNullable(PRIMITIVE_SCHEMA_REGISTRY.get(comp.type()));
  }

  private static boolean isSchemaCompatibleWithJavaType(StvnSchema schema, Class<?> javaType) {
    if (javaType == Boolean.class || javaType == boolean.class) {
      return schema instanceof StvnSchema.BooleanSchema;
    }
    if (javaType == Byte.class || javaType == byte.class
        || javaType == Short.class || javaType == short.class
        || javaType == Integer.class || javaType == int.class
        || javaType == Long.class || javaType == long.class
        || javaType == BigInteger.class) {
      return schema instanceof StvnSchema.IntSchema || schema instanceof StvnSchema.TimeEpochSchema;
    }
    if (javaType == Float.class || javaType == float.class
        || javaType == Double.class || javaType == double.class
        || javaType == BigDecimal.class) {
      return schema instanceof StvnSchema.FloatSchema;
    }
    if (javaType == String.class) {
      return schema instanceof StvnSchema.StringSchema || schema instanceof StvnSchema.DateTimeSchema;
    }
    if (javaType == Optional.class
        || javaType == OptionalInt.class
        || javaType == OptionalLong.class
        || javaType == OptionalDouble.class) {
      return schema instanceof StvnSchema.OptionSchema;
    }
    if (List.class.isAssignableFrom(javaType)) {
      return schema instanceof StvnSchema.SeqSchema;
    }
    if (Set.class.isAssignableFrom(javaType)) {
      return schema instanceof StvnSchema.SetSchema;
    }
    if (Map.class.isAssignableFrom(javaType) || javaType.isRecord()) {
      return schema instanceof StvnSchema.MapSchema || schema instanceof StvnSchema.TupleSchema;
    }
    if (javaType.isEnum()) {
      return schema instanceof StvnSchema.EnumSchema || schema instanceof StvnSchema.EnumSubsetSchema;
    }
    if (SealedInterfaceMapper.isSealedInterface(javaType) || findSealedInterface(javaType).isPresent()) {
      return schema instanceof StvnSchema.EitherSchema || schema instanceof StvnSchema.UnionSchema;
    }
    return false;
  }

  private static Optional<StvnValue> serializeNumber(Number n, Optional<StvnSchema> schemaOpt) {
    if (n instanceof Double d) {
      StvnSchema.FloatSchema floatSchema = (schemaOpt.isPresent() && schemaOpt.get() instanceof StvnSchema.FloatSchema fs)
          ? fs
          : DEFAULT_FLOAT64_SCHEMA;
      if (Double.isNaN(d)) {
        return Optional.of(new StvnValue.StvnFloat(floatSchema, BigDecimal.ZERO, false, Ieee754SpecialKind.NAN));
      }
      if (d == Double.POSITIVE_INFINITY) {
        return Optional.of(new StvnValue.StvnFloat(floatSchema, BigDecimal.ZERO, false, Ieee754SpecialKind.POSITIVE_INFINITY));
      }
      if (d == Double.NEGATIVE_INFINITY) {
        return Optional.of(new StvnValue.StvnFloat(floatSchema, BigDecimal.ZERO, false, Ieee754SpecialKind.NEGATIVE_INFINITY));
      }
      if (Double.doubleToRawLongBits(d) == Double.doubleToRawLongBits(-0.0d)) {
        return Optional.of(new StvnValue.StvnFloat(floatSchema, new BigDecimal("0.0"), true, Ieee754SpecialKind.FINITE));
      }
      BigDecimal bd = BigDecimal.valueOf(d);
      validateFloatBounds(bd, floatSchema);
      return Optional.of(new StvnValue.StvnFloat(floatSchema, bd, false, Ieee754SpecialKind.FINITE));
    }

    if (n instanceof Float fl) {
      StvnSchema.FloatSchema floatSchema = (schemaOpt.isPresent() && schemaOpt.get() instanceof StvnSchema.FloatSchema fs)
          ? fs
          : DEFAULT_FLOAT32_SCHEMA;
      if (Float.isNaN(fl)) {
        return Optional.of(new StvnValue.StvnFloat(floatSchema, BigDecimal.ZERO, false, Ieee754SpecialKind.NAN));
      }
      if (fl == Float.POSITIVE_INFINITY) {
        return Optional.of(new StvnValue.StvnFloat(floatSchema, BigDecimal.ZERO, false, Ieee754SpecialKind.POSITIVE_INFINITY));
      }
      if (fl == Float.NEGATIVE_INFINITY) {
        return Optional.of(new StvnValue.StvnFloat(floatSchema, BigDecimal.ZERO, false, Ieee754SpecialKind.NEGATIVE_INFINITY));
      }
      if (Float.floatToRawIntBits(fl) == Float.floatToRawIntBits(-0.0f)) {
        return Optional.of(new StvnValue.StvnFloat(floatSchema, new BigDecimal("0.0"), true, Ieee754SpecialKind.FINITE));
      }
      BigDecimal bd = new BigDecimal(Float.toString(fl));
      validateFloatBounds(bd, floatSchema);
      return Optional.of(new StvnValue.StvnFloat(floatSchema, bd, false, Ieee754SpecialKind.FINITE));
    }

    if (n instanceof BigDecimal bd) {
      StvnSchema.FloatSchema floatSchema = (schemaOpt.isPresent() && schemaOpt.get() instanceof StvnSchema.FloatSchema fs)
          ? fs
          : DEFAULT_EXACT_DECIMAL_SCHEMA;
      validateFloatBounds(bd, floatSchema);
      return Optional.of(new StvnValue.StvnFloat(floatSchema, bd, false, Ieee754SpecialKind.FINITE));
    }

    BigInteger bi = (n instanceof BigInteger big) ? big : BigInteger.valueOf(n.longValue());

    if (schemaOpt.isPresent() && schemaOpt.get() instanceof StvnSchema.TimeEpochSchema tes) {
      validateTimeEpochTicks(bi, tes);
      return Optional.of(new StvnValue.StvnTimeEpoch(tes, bi, tes.scale()));
    }

    StvnSchema resolved = schemaOpt
        .filter(s -> s instanceof StvnSchema.IntSchema)
        .or(() -> Optional.ofNullable(PRIMITIVE_SCHEMA_REGISTRY.get(n.getClass())))
        .orElseThrow(() -> new MalformedPayloadException(
            "No IntSchema or @StvnBits configuration provided for numeric type: " + n.getClass().getName()
        ));

    StvnSchema.IntSchema intSchema = (StvnSchema.IntSchema) resolved;
    if (!intSchema.acceptsWidth(bi)) {
      throw new StvnIntegerOverflowException(
          "Integer value " + bi + " overflows "
              + (intSchema.unsigned() ? "unsigned " : "signed ")
              + intSchema.bitWidth() + "-bit capacity"
      );
    }
    if (intSchema.explicitMinIncl().isPresent() && bi.compareTo(intSchema.explicitMinIncl().get()) < 0) {
      throw new MalformedPayloadException(
          "Integer value " + bi + " violates #minIncl bound " + intSchema.explicitMinIncl().get()
      );
    }
    if (intSchema.explicitMaxExcl().isPresent() && bi.compareTo(intSchema.explicitMaxExcl().get()) >= 0) {
      throw new MalformedPayloadException(
          "Integer value " + bi + " violates #maxExcl bound " + intSchema.explicitMaxExcl().get()
      );
    }

    return Optional.of(new StvnValue.StvnInt(intSchema, bi));
  }

  private static @Nullable Object fromValueInternal(
      StvnValue irValue,
      Class<?> targetClass,
      Type genericType,
      Optional<StvnSchema> schemaOpt
  ) {
    Objects.requireNonNull(irValue, "irValue must not be null");
    Objects.requireNonNull(targetClass, "targetClass must not be null");
    Objects.requireNonNull(genericType, "genericType must not be null");
    Objects.requireNonNull(schemaOpt, "schemaOpt must not be null");

    Optional<StvnSchema> effectiveSchemaOpt = schemaOpt.or(() -> Optional.of(irValue.schema()));

    if (targetClass == StvnValue.class || StvnValue.class.isAssignableFrom(targetClass)) {
      return irValue;
    }

    if (targetClass.isRecord()) {
      return deserializeRecord(irValue, targetClass, effectiveSchemaOpt);
    }

    if (SealedInterfaceMapper.isSealedInterface(targetClass)
        || (effectiveSchemaOpt.isPresent() && isSealedSchema(effectiveSchemaOpt.get())
            && findSealedInterface(targetClass).isPresent())) {
      return SealedInterfaceMapper.fromValue(irValue, targetClass, effectiveSchemaOpt)
          .orElseThrow(() -> new MalformedPayloadException("Failed to map sealed interface: " + targetClass.getName()));
    }

    if (targetClass == Optional.class) {
      Class<?> innerClass = getGenericTypeArgument(genericType, 0);
      Type innerGenType = (genericType instanceof ParameterizedType pt) ? pt.getActualTypeArguments()[0] : Object.class;
      Optional<StvnSchema> innerSchemaOpt = getOptionInnerSchema(effectiveSchemaOpt);
      if (irValue instanceof StvnValue.StvnOption opt) {
        return opt.value().map(val -> fromValueInternal(val, innerClass, innerGenType, innerSchemaOpt));
      }
      return Optional.ofNullable(fromValueInternal(irValue, innerClass, innerGenType, innerSchemaOpt));
    }
    if (targetClass == OptionalInt.class) {
      Optional<StvnSchema> innerSchemaOpt = getOptionInnerSchema(effectiveSchemaOpt);
      if (irValue instanceof StvnValue.StvnOption opt) {
        return opt.value()
            .map(val -> (Integer) fromValueInternal(val, Integer.class, Integer.class, innerSchemaOpt))
            .map(OptionalInt::of)
            .orElseGet(OptionalInt::empty);
      }
      Integer coerced = (Integer) fromValueInternal(irValue, Integer.class, Integer.class, innerSchemaOpt);
      return coerced != null ? OptionalInt.of(coerced) : OptionalInt.empty();
    }
    if (targetClass == OptionalLong.class) {
      Optional<StvnSchema> innerSchemaOpt = getOptionInnerSchema(effectiveSchemaOpt);
      if (irValue instanceof StvnValue.StvnOption opt) {
        return opt.value()
            .map(val -> (Long) fromValueInternal(val, Long.class, Long.class, innerSchemaOpt))
            .map(OptionalLong::of)
            .orElseGet(OptionalLong::empty);
      }
      Long coerced = (Long) fromValueInternal(irValue, Long.class, Long.class, innerSchemaOpt);
      return coerced != null ? OptionalLong.of(coerced) : OptionalLong.empty();
    }
    if (targetClass == OptionalDouble.class) {
      Optional<StvnSchema> innerSchemaOpt = getOptionInnerSchema(effectiveSchemaOpt);
      if (irValue instanceof StvnValue.StvnOption opt) {
        return opt.value()
            .map(val -> (Double) fromValueInternal(val, Double.class, Double.class, innerSchemaOpt))
            .map(OptionalDouble::of)
            .orElseGet(OptionalDouble::empty);
      }
      Double coerced = (Double) fromValueInternal(irValue, Double.class, Double.class, innerSchemaOpt);
      return coerced != null ? OptionalDouble.of(coerced) : OptionalDouble.empty();
    }

    if (List.class.isAssignableFrom(targetClass)) {
      if (!(irValue instanceof StvnValue.StvnSeq seq)) {
        throw new MalformedPayloadException("Expected StvnSeq for List, got " + irValue.getClass().getSimpleName());
      }
      Class<?> itemClass = getGenericTypeArgument(genericType, 0);
      Type itemGenType = (genericType instanceof ParameterizedType pt) ? pt.getActualTypeArguments()[0] : Object.class;
      Optional<StvnSchema> innerSchemaOpt = getCollectionInnerSchema(effectiveSchemaOpt);

      var list = new ArrayList<>(seq.elements().size());
      for (StvnValue el : seq.elements()) {
        list.add(fromValueInternal(el, itemClass, itemGenType, innerSchemaOpt));
      }
      return List.copyOf(list);
    }

    if (Set.class.isAssignableFrom(targetClass)) {
      if (!(irValue instanceof StvnValue.StvnSet setNode)) {
        throw new MalformedPayloadException("Expected StvnSet for Set, got " + irValue.getClass().getSimpleName());
      }
      Class<?> itemClass = getGenericTypeArgument(genericType, 0);
      Type itemGenType = (genericType instanceof ParameterizedType pt) ? pt.getActualTypeArguments()[0] : Object.class;
      Optional<StvnSchema> innerSchemaOpt = getCollectionInnerSchema(effectiveSchemaOpt);

      var set = new LinkedHashSet<>(setNode.elements().size());
      for (StvnValue el : setNode.elements()) {
        set.add(fromValueInternal(el, itemClass, itemGenType, innerSchemaOpt));
      }
      return Collections.unmodifiableSequencedSet(set);
    }

    if (Map.class.isAssignableFrom(targetClass)) {
      if (!(irValue instanceof StvnValue.StvnMap mapNode)) {
        throw new MalformedPayloadException("Expected StvnMap for Map, got " + irValue.getClass().getSimpleName());
      }
      Class<?> keyClass = getGenericTypeArgument(genericType, 0);
      Class<?> valClass = getGenericTypeArgument(genericType, 1);
      Type keyGenType = (genericType instanceof ParameterizedType pt) ? pt.getActualTypeArguments()[0] : Object.class;
      Type valGenType = (genericType instanceof ParameterizedType pt) ? pt.getActualTypeArguments()[1] : Object.class;

      Optional<StvnSchema> innerKeySchemaOpt = Optional.empty();
      Optional<StvnSchema> innerValSchemaOpt = Optional.empty();
      boolean invertible = false;
      if (effectiveSchemaOpt.isPresent() && effectiveSchemaOpt.get() instanceof StvnSchema.MapSchema ms) {
        innerKeySchemaOpt = Optional.of(ms.keySchema());
        innerValSchemaOpt = Optional.of(ms.valueSchema());
        invertible = ms.invertible();
      } else if (mapNode.schema() instanceof StvnSchema.MapSchema ms) {
        innerKeySchemaOpt = Optional.of(ms.keySchema());
        innerValSchemaOpt = Optional.of(ms.valueSchema());
        invertible = ms.invertible();
      }

      var map = new LinkedHashMap<>(mapNode.entries().size());
      for (KeyValueEntry entry : mapNode.entries()) {
        map.put(
            fromValueInternal(entry.key(), keyClass, keyGenType, innerKeySchemaOpt),
            fromValueInternal(entry.value(), valClass, valGenType, innerValSchemaOpt)
        );
      }

      if (invertible) {
        var seenValues = new HashSet<>(map.size());
        for (var entry : map.entrySet()) {
          if (!seenValues.add(entry.getValue())) {
            throw new MalformedPayloadException(
                "Duplicate value detected in invertible map (MapInv): " + entry.getValue()
            );
          }
        }
      }
      return Collections.unmodifiableSequencedMap(map);
    }

    if (targetClass.isEnum()) {
      if (!(irValue instanceof StvnValue.StvnEnum enumVal)) {
        throw new MalformedPayloadException(
            "Expected StvnEnum for " + targetClass.getName() + ", got " + irValue.getClass().getSimpleName()
        );
      }
      String rawName = enumVal.variantTag().startsWith("#")
          ? enumVal.variantTag().substring(1)
          : enumVal.variantTag();
      return resolveJavaEnumConstant(targetClass, rawName);
    }

    if (targetClass == String.class) {
      if (irValue instanceof StvnValue.StvnString s) {
        return s.value();
      }
      if (irValue instanceof StvnValue.StvnDateTime dt) {
        return dt.canonicalIsoText();
      }
      throw new MalformedPayloadException("Expected StvnString for String, got " + irValue.getClass().getSimpleName());
    }

    if (targetClass == Boolean.class || targetClass == boolean.class) {
      if (!(irValue instanceof StvnValue.StvnBoolean b)) {
        throw new MalformedPayloadException(
            "Expected StvnBoolean for boolean, got " + irValue.getClass().getSimpleName()
        );
      }
      return b.value();
    }

    if (targetClass == Integer.class || targetClass == int.class) {
      BigInteger bi = extractBigInteger(irValue, "int");
      try {
        return bi.intValueExact();
      } catch (ArithmeticException ex) {
        throw new StvnIntegerOverflowException(
            "STVN integer value " + bi + " overflows 32-bit signed Java int range", ex
        );
      }
    }

    if (targetClass == Long.class || targetClass == long.class) {
      BigInteger bi = extractBigInteger(irValue, "long");
      try {
        return bi.longValueExact();
      } catch (ArithmeticException ex) {
        throw new StvnIntegerOverflowException(
            "STVN integer value " + bi + " overflows 64-bit signed Java long range", ex
        );
      }
    }

    if (targetClass == Short.class || targetClass == short.class) {
      BigInteger bi = extractBigInteger(irValue, "short");
      try {
        return bi.shortValueExact();
      } catch (ArithmeticException ex) {
        throw new StvnIntegerOverflowException(
            "STVN integer value " + bi + " overflows 16-bit signed Java short range", ex
        );
      }
    }

    if (targetClass == Byte.class || targetClass == byte.class) {
      BigInteger bi = extractBigInteger(irValue, "byte");
      try {
        return bi.byteValueExact();
      } catch (ArithmeticException ex) {
        throw new StvnIntegerOverflowException(
            "STVN integer value " + bi + " overflows 8-bit signed Java byte range", ex
        );
      }
    }

    if (targetClass == BigInteger.class) {
      return extractBigInteger(irValue, "BigInteger");
    }

    if (targetClass == Double.class || targetClass == double.class) {
      if (!(irValue instanceof StvnValue.StvnFloat f)) {
        throw new MalformedPayloadException("Expected StvnFloat for double, got " + irValue.getClass().getSimpleName());
      }
      return switch (f.specialKind()) {
        case NAN -> Double.NaN;
        case POSITIVE_INFINITY -> Double.POSITIVE_INFINITY;
        case NEGATIVE_INFINITY -> Double.NEGATIVE_INFINITY;
        case FINITE -> f.negativeZero() ? -0.0d : f.decimalValue().doubleValue();
      };
    }

    if (targetClass == Float.class || targetClass == float.class) {
      if (!(irValue instanceof StvnValue.StvnFloat f)) {
        throw new MalformedPayloadException("Expected StvnFloat for float, got " + irValue.getClass().getSimpleName());
      }
      return switch (f.specialKind()) {
        case NAN -> Float.NaN;
        case POSITIVE_INFINITY -> Float.POSITIVE_INFINITY;
        case NEGATIVE_INFINITY -> Float.NEGATIVE_INFINITY;
        case FINITE -> f.negativeZero() ? -0.0f : f.decimalValue().floatValue();
      };
    }

    if (targetClass == BigDecimal.class) {
      if (!(irValue instanceof StvnValue.StvnFloat f)) {
        throw new MalformedPayloadException(
            "Expected StvnFloat for BigDecimal, got " + irValue.getClass().getSimpleName()
        );
      }
      if (f.specialKind() != Ieee754SpecialKind.FINITE) {
        throw new MalformedPayloadException(
            "Cannot map non-finite IEEE-754 float " + f.specialKind() + " to BigDecimal"
        );
      }
      return f.decimalValue();
    }

    throw new IllegalArgumentException("Unsupported target class for mapping: " + targetClass.getName());
  }

  private static Object deserializeRecord(
      StvnValue irValue,
      Class<?> targetClass,
      Optional<StvnSchema> schemaOpt
  ) {
    var meta = StvnRecordCache.get(targetClass);
    Object[] args = new Object[meta.components().length];

    for (int i = 0; i < meta.components().length; i++) {
      var comp = meta.components()[i];
      Optional<StvnValue> valNode = Optional.empty();
      if (irValue instanceof StvnValue.StvnTuple tup) {
        if (i < tup.elements().size()) {
          valNode = Optional.of(tup.elements().get(i));
        }
      } else if (irValue instanceof StvnValue.StvnMap m) {
        for (KeyValueEntry entry : m.entries()) {
          if (entry.key() instanceof StvnValue.StvnString s && s.value().equals(comp.name())) {
            valNode = Optional.of(entry.value());
            break;
          }
        }
      } else {
        throw new MalformedPayloadException(
            "Expected StvnTuple or StvnMap for record " + targetClass.getName()
                + ", got " + irValue.getClass().getSimpleName()
        );
      }

      Optional<StvnSchema> childSchemaOpt = Optional.empty();
      if (schemaOpt.isPresent()) {
        StvnSchema parent = schemaOpt.get();
        if (irValue instanceof StvnValue.StvnTuple && parent instanceof StvnSchema.TupleSchema ts) {
          if (i < ts.fieldSchemas().size()) {
            childSchemaOpt = Optional.of(ts.fieldSchemas().get(i));
          }
        } else if (parent instanceof StvnSchema.MapSchema ms) {
          if (isSchemaCompatibleWithJavaType(ms.valueSchema(), comp.type())) {
            childSchemaOpt = Optional.of(ms.valueSchema());
          }
        }
      }
      if (childSchemaOpt.isEmpty() && comp.bitsAnnotation() != null) {
        StvnBits bits = comp.bitsAnnotation();
        childSchemaOpt = Optional.of(new StvnSchema.IntSchema(
            Optional.empty(),
            bits.unsigned(),
            BigInteger.valueOf(bits.value()),
            SCALAR_COMPARABLE_TRAITS,
            Optional.empty(),
            Optional.empty()
        ));
      }

      if (valNode.isEmpty()) {
        if (comp.type() == Optional.class) {
          args[i] = Optional.empty();
        } else if (comp.type() == OptionalInt.class) {
          args[i] = OptionalInt.empty();
        } else if (comp.type() == OptionalLong.class) {
          args[i] = OptionalLong.empty();
        } else if (comp.type() == OptionalDouble.class) {
          args[i] = OptionalDouble.empty();
        } else {
          throw new MalformedPayloadException("Missing required record component: " + comp.name());
        }
      } else {
        StvnValue componentIr = valNode.get();
        validateAndEnrichComponentConstraints(comp, componentIr);
        Object converted = fromValueInternal(componentIr, comp.type(), comp.genericType(), childSchemaOpt);
        checkOptionalLoophole(comp, converted);
        args[i] = converted;
      }
    }

    try {
      return meta.canonicalConstructor().invokeWithArguments(args);
    } catch (RuntimeException re) {
      throw re;
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  private static BigInteger extractBigInteger(StvnValue irValue, String targetName) {
    if (irValue instanceof StvnValue.StvnInt i) {
      return i.value();
    }
    if (irValue instanceof StvnValue.StvnTimeEpoch te) {
      return te.epochTicks();
    }
    throw new MalformedPayloadException(
        "Expected StvnInt for " + targetName + ", got " + irValue.getClass().getSimpleName()
    );
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Object resolveJavaEnumConstant(Class<?> enumClass, String name) {
    try {
      return Enum.valueOf((Class<? extends Enum>) enumClass, name);
    } catch (IllegalArgumentException ex) {
      throw new MalformedPayloadException(
          "Unknown Java enum constant '" + name + "' for " + enumClass.getName(), ex
      );
    }
  }

  private static StvnValue validateAndEnrichComponentConstraints(
      StvnRecordCache.RecordComponentProfile comp,
      StvnValue mapped
  ) {
    Objects.requireNonNull(comp, "comp must not be null");
    Objects.requireNonNull(mapped, "mapped must not be null");

    if (comp.bitsAnnotation() != null && mapped instanceof StvnValue.StvnInt intVal) {
      StvnBits bits = comp.bitsAnnotation();
      if (bits.value() < 1) {
        throw new MalformedPayloadException(
            "Invalid @StvnBits bit-width " + bits.value() + " on component '" + comp.name() + "'"
        );
      }
      var bitSchema = new StvnSchema.IntSchema(
          intVal.schema().aliasName(),
          bits.unsigned(),
          BigInteger.valueOf(bits.value()),
          intVal.schema().traits(),
          (intVal.schema() instanceof StvnSchema.IntSchema is) ? is.explicitMinIncl() : Optional.empty(),
          (intVal.schema() instanceof StvnSchema.IntSchema is) ? is.explicitMaxExcl() : Optional.empty()
      );
      if (!bitSchema.acceptsWidth(intVal.value())) {
        throw new StvnIntegerOverflowException(
            "Field '" + comp.name() + "' integer value " + intVal.value()
                + " overflows @StvnBits(" + bits.value() + ", unsigned=" + bits.unsigned() + ")"
        );
      }
      mapped = new StvnValue.StvnInt(bitSchema, intVal.value());
    }

    if (comp.stringAnnotation() != null && mapped instanceof StvnValue.StvnString strVal) {
      StvnString ann = comp.stringAnnotation();
      int codePoints = strVal.value().codePointCount(0, strVal.value().length());
      if (codePoints < ann.minSize() || codePoints > ann.maxSize()) {
        throw new MalformedPayloadException(
            "Field '" + comp.name() + "' length " + codePoints
                + " violates @StvnString cardinality [" + ann.minSize() + ", " + ann.maxSize() + "]"
        );
      }

      Optional<Pattern> compiledRegex = Optional.empty();
      Optional<String> rawRegex = Optional.empty();
      if (!ann.regex().isEmpty()) {
        try {
          Pattern p = Pattern.compile(ann.regex());
          if (!p.matcher(strVal.value()).matches()) {
            throw new MalformedPayloadException(
                "Field '" + comp.name() + "' value does not match @StvnString(regex = \"" + ann.regex() + "\")"
            );
          }
          compiledRegex = Optional.of(p);
          rawRegex = Optional.of(ann.regex());
        } catch (PatternSyntaxException pse) {
          throw new MalformedPayloadException(
              "Invalid regular expression in @StvnString on field '" + comp.name() + "': " + ann.regex(), pse
          );
        }
      }

      StvnSchema orig = strVal.schema();
      StvnSchema.StringSchema base = (orig instanceof StvnSchema.StringSchema ss) ? ss : DEFAULT_STRING_SCHEMA;
      BigInteger effectiveMin = base.minSize().max(BigInteger.valueOf(ann.minSize()));
      BigInteger effectiveMax = base.maxSize().min(BigInteger.valueOf(ann.maxSize()));
      var enrichedSchema = new StvnSchema.StringSchema(
          base.aliasName(),
          effectiveMin,
          effectiveMax,
          base.traits(),
          base.lowerBound(),
          base.upperBound(),
          base.preserveIndent(),
          compiledRegex.isPresent() ? compiledRegex : base.compiledRegex(),
          rawRegex.isPresent() ? rawRegex : base.rawRegexPattern()
      );
      mapped = new StvnValue.StvnString(enrichedSchema, strVal.value());
    }

    if (comp.intAnnotation() != null && mapped instanceof StvnValue.StvnInt intVal) {
      StvnInt ann = comp.intAnnotation();
      BigInteger val = intVal.value();
      BigInteger min = BigInteger.valueOf(ann.minIncl());
      BigInteger max = BigInteger.valueOf(ann.maxIncl());

      if (ann.minIncl() != Long.MIN_VALUE && val.compareTo(min) < 0) {
        throw new MalformedPayloadException(
            "Field '" + comp.name() + "' value " + val
                + " violates @StvnInt range [" + ann.minIncl() + ", " + ann.maxIncl() + "]"
        );
      }
      if (ann.maxIncl() != Long.MAX_VALUE && val.compareTo(max) > 0) {
        throw new MalformedPayloadException(
            "Field '" + comp.name() + "' value " + val
                + " violates @StvnInt range [" + ann.minIncl() + ", " + ann.maxIncl() + "]"
        );
      }

      StvnSchema orig = intVal.schema();
      StvnSchema.IntSchema base = (orig instanceof StvnSchema.IntSchema is) ? is : DEFAULT_INT32_SCHEMA;
      Optional<BigInteger> newMinIncl = (ann.minIncl() != Long.MIN_VALUE)
          ? Optional.of(base.explicitMinIncl().map(m -> m.max(min)).orElse(min))
          : base.explicitMinIncl();
      BigInteger maxExclCandidate = max.add(BigInteger.ONE);
      Optional<BigInteger> newMaxExcl = (ann.maxIncl() != Long.MAX_VALUE)
          ? Optional.of(base.explicitMaxExcl().map(m -> m.min(maxExclCandidate)).orElse(maxExclCandidate))
          : base.explicitMaxExcl();

      var enrichedSchema = new StvnSchema.IntSchema(
          base.aliasName(),
          base.unsigned(),
          base.bitWidth(),
          base.traits(),
          newMinIncl,
          newMaxExcl
      );
      mapped = new StvnValue.StvnInt(enrichedSchema, val);
    }

    return mapped;
  }

  private static void validateStringAgainstSchema(String value, StvnSchema.StringSchema schema) {
    BigInteger len = BigInteger.valueOf(value.codePointCount(0, value.length()));
    if (len.compareTo(schema.minSize()) < 0 || len.compareTo(schema.maxSize()) > 0) {
      throw new MalformedPayloadException(
          "String length " + len + " outside schema cardinality [" + schema.minSize() + ", " + schema.maxSize() + "]"
      );
    }
    if (schema.compiledRegex().isPresent() && !schema.compiledRegex().get().matcher(value).matches()) {
      throw new MalformedPayloadException(
          "String value does not match schema #regex pattern: "
              + schema.rawRegexPattern().orElseGet(() -> schema.compiledRegex().get().pattern())
      );
    }
  }

  private static void validateFloatBounds(BigDecimal bd, StvnSchema.FloatSchema schema) {
    if (schema.representation() == FloatRepresentation.EXACT_DECIMAL) {
      if (BigInteger.valueOf(bd.precision()).compareTo(schema.exactMaxDigits()) > 0) {
        throw new MalformedPayloadException(
            "BigDecimal precision " + bd.precision() + " exceeds exactMaxDigits " + schema.exactMaxDigits()
        );
      }
    }
    if (schema.lowerBound().isPresent()) {
      var lb = schema.lowerBound().get();
      int cmp = bd.compareTo(lb.value());
      if ((lb.kind() == StvnSchema.BoundKind.INCLUSIVE && cmp < 0)
          || (lb.kind() == StvnSchema.BoundKind.EXCLUSIVE && cmp <= 0)) {
        throw new MalformedPayloadException("Float value " + bd + " violates lower bound " + lb.value());
      }
    }
    if (schema.upperBound().isPresent()) {
      var ub = schema.upperBound().get();
      int cmp = bd.compareTo(ub.value());
      if ((ub.kind() == StvnSchema.BoundKind.INCLUSIVE && cmp > 0)
          || (ub.kind() == StvnSchema.BoundKind.EXCLUSIVE && cmp >= 0)) {
        throw new MalformedPayloadException("Float value " + bd + " violates upper bound " + ub.value());
      }
    }
  }

  private static void validateTimeEpochTicks(BigInteger ticks, StvnSchema.TimeEpochSchema schema) {
    BigInteger bitWidth = schema.scale().bitWidth();
    var widthCheck = new StvnSchema.IntSchema(
        Optional.empty(), false, bitWidth, SCALAR_COMPARABLE_TRAITS, Optional.empty(), Optional.empty()
    );
    if (!widthCheck.acceptsWidth(ticks)) {
      throw new StvnIntegerOverflowException(
          "Epoch ticks " + ticks + " overflow signed " + bitWidth + "-bit capacity for scale " + schema.scale()
      );
    }
    if (schema.explicitMinIncl().isPresent() && ticks.compareTo(schema.explicitMinIncl().get()) < 0) {
      throw new MalformedPayloadException(
          "Epoch ticks " + ticks + " violate #minIncl bound " + schema.explicitMinIncl().get()
      );
    }
    if (schema.explicitMaxExcl().isPresent() && ticks.compareTo(schema.explicitMaxExcl().get()) >= 0) {
      throw new MalformedPayloadException(
          "Epoch ticks " + ticks + " violate #maxExcl bound " + schema.explicitMaxExcl().get()
      );
    }
  }

  private static void validateCardinality(int actualSize, BigInteger minSize, BigInteger maxSize, String kind) {
    BigInteger size = BigInteger.valueOf(actualSize);
    if (size.compareTo(minSize) < 0 || size.compareTo(maxSize) > 0) {
      throw new MalformedPayloadException(
          kind + " cardinality " + size + " outside schema bounds [" + minSize + ", " + maxSize + "]"
      );
    }
  }

  private static StvnSchema resolveOptionSchema(
      Optional<StvnSchema> schemaOpt,
      Optional<StvnSchema> innerValueSchemaOpt
  ) {
    if (schemaOpt.isPresent() && schemaOpt.get() instanceof StvnSchema.OptionSchema os) {
      return os;
    }
    StvnSchema inner = innerValueSchemaOpt.orElse(DEFAULT_STRING_SCHEMA);
    return new StvnSchema.OptionSchema(
        Optional.empty(),
        inner,
        TraitProfile.ofDefault(inner.traits().equatable(), inner.traits().comparable())
    );
  }

  private static Optional<StvnSchema> getOptionInnerSchema(Optional<StvnSchema> schemaOpt) {
    if (schemaOpt.isPresent() && schemaOpt.get() instanceof StvnSchema.OptionSchema os) {
      return Optional.of(os.valueSchema());
    }
    return Optional.empty();
  }

  private static Optional<StvnSchema> getCollectionInnerSchema(Optional<StvnSchema> schemaOpt) {
    if (schemaOpt.isPresent()) {
      StvnSchema s = schemaOpt.get();
      if (s instanceof StvnSchema.SeqSchema ss) {
        return Optional.of(ss.elementSchema());
      }
      if (s instanceof StvnSchema.SetSchema ss) {
        return Optional.of(ss.elementSchema());
      }
    }
    return Optional.empty();
  }

  private static boolean isSealedSchema(StvnSchema schema) {
    return schema instanceof StvnSchema.EitherSchema || schema instanceof StvnSchema.UnionSchema;
  }

  private static boolean isPOJO(Class<?> clazz) {
    Objects.requireNonNull(clazz, "clazz must not be null");
    if (clazz.isRecord() || clazz.isPrimitive() || clazz.isEnum()) {
      return false;
    }
    if (clazz == String.class || clazz == Boolean.class || clazz == Character.class) {
      return false;
    }
    if (Number.class.isAssignableFrom(clazz)) {
      return false;
    }
    if (Collection.class.isAssignableFrom(clazz) || Map.class.isAssignableFrom(clazz)) {
      return false;
    }
    if (clazz == Optional.class
        || clazz == OptionalInt.class
        || clazz == OptionalLong.class
        || clazz == OptionalDouble.class) {
      return false;
    }
    if (StvnValue.class.isAssignableFrom(clazz)) {
      return false;
    }
    if (clazz.isInterface() && clazz.isSealed()) {
      return false;
    }
    if (findSealedInterface(clazz).isPresent()) {
      return false;
    }
    return true;
  }

  private static Optional<Class<?>> findSealedInterface(Class<?> clazz) {
    Objects.requireNonNull(clazz, "clazz must not be null");
    for (Class<?> iface : clazz.getInterfaces()) {
      if (iface.isSealed()) {
        return Optional.of(iface);
      }
    }
    Class<?> superclass = clazz.getSuperclass();
    if (superclass != null && superclass != Object.class) {
      if (superclass.isSealed()) {
        return Optional.of(superclass);
      }
      return findSealedInterface(superclass);
    }
    return Optional.empty();
  }

  private static void checkOptionalLoophole(
      StvnRecordCache.RecordComponentProfile comp,
      @Nullable Object value
  ) {
    Objects.requireNonNull(comp, "comp must not be null");
    if (comp.type() == Optional.class
        || comp.type() == OptionalInt.class
        || comp.type() == OptionalLong.class
        || comp.type() == OptionalDouble.class) {
      if (value == null) {
        throw new MalformedPayloadException(
            "Optional Null Loophole detected: record component '" + comp.name() + "' is null, which is invalid."
        );
      }
    }
  }

  private static Class<?> getGenericTypeArgument(Type type, int index) {
    Objects.requireNonNull(type, "type must not be null");
    if (type instanceof ParameterizedType pt) {
      Type[] args = pt.getActualTypeArguments();
      if (index < args.length) {
        if (args[index] instanceof Class<?> clazz) {
          return clazz;
        }
        if (args[index] instanceof ParameterizedType nestedPt && nestedPt.getRawType() instanceof Class<?> raw) {
          return raw;
        }
      }
    }
    return Object.class;
  }
}
