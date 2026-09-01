package org.stvnadore.core.mapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.lang.reflect.Type;
import java.lang.reflect.ParameterizedType;
import java.util.*;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.stvnadore.core.annotations.StvnInt;
import org.stvnadore.core.annotations.StvnString;
import org.stvnadore.core.annotations.StvnBits;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.parser.StvnLexer;
import org.stvnadore.core.parser.StvnParser;
import org.stvnadore.core.parser.StvnParser.SchemaTypeContext;
import org.stvnadore.core.parser.StvnParser.StvnDocumentContext;
import org.stvnadore.core.validation.MalformedPayloadException;
import org.stvnadore.core.validation.StvnIntegerOverflowException;
import org.stvnadore.core.validation.StvnTypeResolver;
import org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema;
import org.stvnadore.core.validation.StvnTypeResolver.StvnConstraints;

/**
 * Facade mapping engine for translating Java objects (specifically records) to and from the
 * STVN Intermediate Representation AST ({@link StvnValue}).
 * <p>
 * This class coordinates Java reflection, annotation checks, and standard type coercion routines,
 * exposing a thread-safe, stateless interface.
 *
 * <h2>Implied Tagging Rules (Sum Type Mechanics)</h2>
 * STVN supports implied (happy-path) tagging for sum type topologies when the raw payload value is unambiguous:
 * <ul>
 *   <li><b>Rule A (Implied Option {@code #Some}):</b> For a target schema of {@code :Option(T)}, if a raw value
 *       matching {@code T} is encountered without an explicit tag, the mapper implicitly wraps it as a valid
 *       {@code #Some value} node.</li>
 *   <li><b>Rule B (Implied Either {@code #Right}):</b> For a target schema of {@code :Either(L R)}, if a raw value
 *       matching {@code R} is encountered without an explicit tag, the mapper implicitly wraps it as a valid
 *       {@code #Right value} node.</li>
 *   <li><b>Rule C (Ambiguity Resolution):</b> If an untagged literal raw value is structurally type-compatible
 *       with BOTH the explicit tag syntax boundary (e.g. matching nominal string keywords like {@code "#None"} or
 *       {@code "#Left"}) AND the underlying implied scalar type ({@code T} or {@code R}), explicit tagging is <b>mandatory</b>.
 *       The mapper will fail-fast and throw a {@link MalformedPayloadException} if an untagged value creates a type resolution ambiguity.</li>
 * </ul>
 * <p>
 * <b>Example of Ambiguity Trapping:</b>
 * <pre>{@code
 * // For :Option(:String), if the payload contains exactly "#None" without tags,
 * // it could mean either a string value "#None" or the Option empty variant (#None).
 * // Explicit tagging is mandatory here: e.g. #Some "#None" or #None.
 * // An untagged "#None" will trigger a MalformedPayloadException.
 * }</pre>
 *
 * <h2>Optional Null Loophole</h2>
 * In STVN's Value-Oriented Programming (VOP) model, uninitialized or {@code null} states do not exist.
 * Optionals are strictly modeled as {@code Optional.of(value)} or {@code Optional.empty()}. To prevent
 * monadic backsliding, passing a literal {@code null} reference to a record component typed as an {@code Optional}
 * is prohibited. The mapper will intercept this check and throw a {@link MalformedPayloadException}.
 *
 * @since 1.0.0
 */
public final class StvnMapper {

  private static final Map<Class<?>, ResolvedSchema> PRIMITIVE_SCHEMA_REGISTRY;

  private static ResolvedSchema parsePrimitiveSchema(String typeKeyword, String bodyContent) {
    var node = new StvnParser(
        new CommonTokenStream(
            new StvnLexer(CharStreams.fromString("{ :type " + typeKeyword + " :body " + bodyContent + " }"))))
        .stvnDocument()
        .documentBody()
        .typeEntry()
        .schemaType();
    return new ResolvedSchema(
        node,
        StvnConstraints.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.of(StvnConstraints.empty())
    );
  }

  static {
    var map = new HashMap<Class<?>, ResolvedSchema>();
    var boolSchema = parsePrimitiveSchema(":Boolean", "#FALSE");
    map.put(Boolean.class, boolSchema);
    map.put(boolean.class, boolSchema);

    var int8Schema = parsePrimitiveSchema(":Int8", "0");
    map.put(Byte.class, int8Schema);
    map.put(byte.class, int8Schema);

    var int16Schema = parsePrimitiveSchema(":Int16", "0");
    map.put(Short.class, int16Schema);
    map.put(short.class, int16Schema);

    var int32Schema = parsePrimitiveSchema(":Int32", "0");
    map.put(Integer.class, int32Schema);
    map.put(int.class, int32Schema);

    var int64Schema = parsePrimitiveSchema(":Int64", "0");
    map.put(Long.class, int64Schema);
    map.put(long.class, int64Schema);

    var float32Schema = parsePrimitiveSchema(":Float32", "0.0");
    map.put(Float.class, float32Schema);
    map.put(float.class, float32Schema);

    var float64Schema = parsePrimitiveSchema(":Float64", "0.0");
    map.put(Double.class, float64Schema);
    map.put(double.class, float64Schema);

    map.put(BigDecimal.class, parsePrimitiveSchema(":FloatExact", "0.0"));
    map.put(String.class, parsePrimitiveSchema(":String", "\"\""));

    PRIMITIVE_SCHEMA_REGISTRY = Collections.unmodifiableMap(map);
  }

  private StvnMapper() {}

  /**
   * Resolves the {@link StvnDocumentContext} containing the parser node configuration for the given schema option.
   * <p>
   * If the schema has an associated document context, it is returned. Otherwise, this method falls back
   * to the implicitly loaded standard library prelude document context {@link org.stvnadore.core.stdlib.StvnPrelude#getPreludeDocument()}.
   * <p>
   * <b>Preconditions:</b>
   * <ul>
   *   <li>{@code schemaOpt} must not be {@code null}.</li>
   * </ul>
   *
   * @param schemaOpt the optional schema reference
   * @return the resolved non-null {@link StvnDocumentContext}
   */
  public static StvnDocumentContext getDocumentContext(Optional<ResolvedSchema> schemaOpt) {
    Objects.requireNonNull(schemaOpt);
    return schemaOpt.flatMap(schema -> {
      org.antlr.v4.runtime.tree.ParseTree current = schema.node();
      while (current != null && !(current instanceof StvnDocumentContext)) {
        current = current.getParent();
      }
      return Optional.ofNullable((StvnDocumentContext) current);
    }).orElseGet(org.stvnadore.core.stdlib.StvnPrelude::getPreludeDocument);
  }

  /**
   * Resolves the {@link StvnDocumentContext} containing the parser node configuration for the given schema.
   * <p>
   * <b>Preconditions:</b>
   * <ul>
   *   <li>{@code schema} must not be {@code null}.</li>
   * </ul>
   *
   * @param schema the schema reference
   * @return the resolved non-null {@link StvnDocumentContext}
   */
  public static StvnDocumentContext getDocumentContext(ResolvedSchema schema) {
    Objects.requireNonNull(schema);
    return getDocumentContext(Optional.of(schema));
  }

  /**
   * Serializes a native Java object/record instance to its corresponding {@link StvnValue} AST node
   * using the provided resolved schema context.
   * <p>
   * This is a convenience delegator that resolves the document context from the schema.
   * <p>
   * <b>Preconditions:</b>
   * <ul>
   *   <li>{@code schema} must not be {@code null}.</li>
   * </ul>
   *
   * @param recordInstance the native Java object or record to serialize
   * @param schema         the resolved schema definition
   * @return an {@link Optional} containing the serialized {@link StvnValue}
   * @throws NullPointerException      if {@code schema} is {@code null}
   * @throws MalformedPayloadException if mapping constraints are violated
   */
  public static Optional<StvnValue> toValue(Object recordInstance, ResolvedSchema schema) {
    Objects.requireNonNull(schema);
    return toValue(recordInstance, Optional.of(schema), getDocumentContext(schema));
  }

  /**
   * Deserializes an STVN AST node to its corresponding native Java object representation
   * using the provided resolved schema context.
   * <p>
   * This is a convenience delegator that resolves the document context from the schema.
   * <p>
   * <b>Preconditions:</b>
   * <ul>
   *   <li>{@code ast} must not be {@code null}.</li>
   *   <li>{@code targetClass} must not be {@code null}.</li>
   *   <li>{@code schema} must not be {@code null}.</li>
   * </ul>
   *
   * @param <T>         the target Java class type
   * @param ast         the STVN value tree to deserialize
   * @param targetClass the target Java class to instantiate
   * @param schema      the resolved schema definition
   * @return an {@link Optional} enclosing the deserialized instance, cast to the target class
   * @throws NullPointerException      if any parameter is {@code null}
   * @throws MalformedPayloadException if structural or validation constraints are violated
   */
  public static <T> Optional<T> fromValue(StvnValue ast, Class<T> targetClass, ResolvedSchema schema) {
    Objects.requireNonNull(ast);
    Objects.requireNonNull(targetClass);
    Objects.requireNonNull(schema);
    return fromValue(ast, targetClass, Optional.of(schema), getDocumentContext(schema));
  }

  /**
   * Serializes a native Java object/record instance to its corresponding {@link StvnValue} AST node,
   * allowing optional schemas and custom document context.
   * <p>
   * Coordinates reflection-based mapping, checks for annotations ({@link StvnBits}, {@link StvnInt},
   * {@link StvnString}), and applies Implied Tagging rules for options and eithers.
   * <p>
   * <b>Preconditions:</b>
   * <ul>
   *   <li>{@code schemaOpt} must not be {@code null}.</li>
   *   <li>{@code documentContext} must not be {@code null}.</li>
   * </ul>
   *
   * @param recordInstance  the native Java object or record to serialize
   * @param schemaOpt       the optional resolved schema definition
   * @param documentContext the active STVN document compilation context
   * @return an {@link Optional} containing the serialized {@link StvnValue}
   * @throws NullPointerException      if {@code schemaOpt} or {@code documentContext} is {@code null}
   * @throws MalformedPayloadException if mapping constraints are violated or POJO mapping is attempted
   */
  public static Optional<StvnValue> toValue(Object recordInstance, Optional<ResolvedSchema> schemaOpt, StvnDocumentContext documentContext) {
    Objects.requireNonNull(schemaOpt);
    Objects.requireNonNull(documentContext);

    if (recordInstance == null) {
      if (schemaOpt.isPresent() && schemaOpt.get().node() != null && ":Option".equals(StvnTypeResolver.getPrimitiveBaseType(schemaOpt.get().node()))) {
        return Optional.of(new StvnValue.StvnOption(schemaOpt.get(), Optional.empty()));
      }
      return Optional.empty();
    }

    var schema = schemaOpt.or(() -> Optional.ofNullable(PRIMITIVE_SCHEMA_REGISTRY.get(recordInstance.getClass())))
        .orElseThrow(() -> new MalformedPayloadException("No primitive schema mapping found for class: " + recordInstance.getClass().getName()));

    if (recordInstance instanceof StvnValue) {
      return Optional.of((StvnValue) recordInstance);
    }

    if (isPOJO(recordInstance.getClass())) {
      throw new IllegalArgumentException("POJO types are rejected by the mapper: " + recordInstance.getClass().getName());
    }

    if (recordInstance instanceof Optional<?> opt) {
      var innerSchemaOpt = getOptionInnerSchema(schemaOpt, documentContext);
      return Optional.of(new StvnValue.StvnOption(schema, opt.flatMap(inner -> toValue(inner, innerSchemaOpt, documentContext))));
    }

    if (SealedInterfaceMapper.isSealedInterface(recordInstance.getClass()) || isSealedSchema(schema)) {
      var sealedTypeOpt = findSealedInterface(recordInstance.getClass());
      if (sealedTypeOpt.isPresent()) {
        return SealedInterfaceMapper.toValue(recordInstance, sealedTypeOpt.get(), schemaOpt, documentContext);
      }
    }

    if (recordInstance.getClass().isRecord()) {
      var meta = StvnRecordCache.get(recordInstance.getClass());
      var baseText = Optional.ofNullable(schema.node())
          .map(StvnTypeResolver::getPrimitiveBaseType);

      if (baseText.filter(":Tuple"::equals).isPresent()) {
        var innerNodes = StvnTypeResolver.getInnerSchemas(schema.node());
        var elements = new ArrayList<StvnValue>();
        for (int i = 0; i < meta.components().length; i++) {
          var comp = meta.components()[i];
          final int index = i;
          var childSchemaOpt = Optional.of(index)
              .filter(idx -> idx < innerNodes.size())
              .flatMap(idx -> StvnTypeResolver.resolvePrimitiveSchema(documentContext, innerNodes.get(idx), new HashSet<>()))
              .or(() -> Optional.ofNullable(comp.bitsAnnotation())
                  .map(bits -> parsePrimitiveSchema((bits.unsigned() ? ":Uint" : ":Int") + bits.value(), "0")));

          try {
            var val = comp.accessorHandle().invoke(recordInstance);
            checkOptionalLoophole(comp, val);
            var mappedOpt = toValue(val, childSchemaOpt, documentContext);
            var mapped = mappedOpt.orElseThrow(() -> new MalformedPayloadException("Missing value for component: " + comp.name()));
            mapped = validateAndEnrichConstraints(comp, mapped);
            elements.add(mapped);
          } catch (Throwable t) {
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            throw new RuntimeException(t);
          }
        }
        return Optional.of(new StvnValue.StvnTuple(schema, elements));
      } else {
        // Default to StvnMap
        var innerNodes = Optional.ofNullable(schema.node())
            .map(StvnTypeResolver::getInnerSchemas)
            .orElse(List.of());

        var innerKeySchemaOpt = innerNodes.size() > 0
            ? StvnTypeResolver.resolvePrimitiveSchema(documentContext, innerNodes.get(0), new HashSet<>())
            : Optional.<ResolvedSchema>empty();

        var innerValSchemaOpt = innerNodes.size() > 1
            ? StvnTypeResolver.resolvePrimitiveSchema(documentContext, innerNodes.get(1), new HashSet<>())
            : Optional.<ResolvedSchema>empty();

        var entries = new LinkedHashMap<StvnValue, StvnValue>();
        for (var comp : meta.components()) {
          try {
            var val = comp.accessorHandle().invoke(recordInstance);
            checkOptionalLoophole(comp, val);

            var keyVal = new StvnValue.StvnString(
                innerKeySchemaOpt.or(() -> Optional.ofNullable(PRIMITIVE_SCHEMA_REGISTRY.get(String.class))).orElseThrow(),
                comp.name(),
                StvnValue.StringStyle.SIMPLE,
                Optional.empty(),
                new StvnValue.StringTrait(0, false)
            );
            var mappedValOpt = toValue(val, innerValSchemaOpt, documentContext);
            var mappedVal = mappedValOpt.orElseThrow(() -> new MalformedPayloadException("Missing value for component: " + comp.name()));
            mappedVal = validateAndEnrichConstraints(comp, mappedVal);
            entries.put(keyVal, mappedVal);
          } catch (Throwable t) {
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            throw new RuntimeException(t);
          }
        }

        boolean isInvertible = baseText.filter(text -> text.startsWith(":MapInv")).isPresent();

        if (isInvertible) {
          var seenValues = new HashSet<StvnValue>(entries.size());
          for (var entry : entries.entrySet()) {
            if (!seenValues.add(entry.getValue())) {
              throw new MalformedPayloadException("Duplicate value detected in invertible map (MapInv): " + entry.getValue());
            }
          }
        }

        return Optional.of(new StvnValue.StvnMap(schema, entries, !entries.isEmpty(), isInvertible));
      }
    }

    if (recordInstance instanceof Boolean b) {
      return Optional.of(new StvnValue.StvnBoolean(schema, b));
    }
    if (recordInstance instanceof String s) {
      return Optional.of(new StvnValue.StvnString(schema, s, StvnValue.StringStyle.SIMPLE, Optional.empty(), new StvnValue.StringTrait(0, false)));
    }
    if (recordInstance instanceof Number n) {
      if (recordInstance instanceof Double || recordInstance instanceof Float || recordInstance instanceof BigDecimal) {
        var bd = (recordInstance instanceof BigDecimal) ? (BigDecimal) recordInstance : new BigDecimal(recordInstance.toString());
        var precision = (recordInstance instanceof Float) ? StvnValue.FloatPrecision.FLOAT32 : StvnValue.FloatPrecision.FLOAT64;
        return Optional.of(new StvnValue.StvnFloat(schema, bd, precision));
      } else {
        var bi = (recordInstance instanceof BigInteger) ? (BigInteger) recordInstance : BigInteger.valueOf(n.longValue());
        int bitWidth = 32;
        boolean isUnsigned = false;
        var baseText = Optional.ofNullable(schema.node())
            .map(StvnTypeResolver::getPrimitiveBaseType);
        if (baseText.isPresent()) {
          var text = baseText.get();
          if (text.startsWith(":Uint")) isUnsigned = true;
          var digits = text.replaceAll("\\D+", "");
          if (!digits.isEmpty()) {
            bitWidth = Integer.parseInt(digits);
          }
        }

        BigInteger minLimit;
        BigInteger maxLimit;
        if (isUnsigned) {
          minLimit = BigInteger.ZERO;
          maxLimit = BigInteger.TWO.pow(bitWidth).subtract(BigInteger.ONE);
        } else {
          minLimit = BigInteger.TWO.pow(bitWidth - 1).negate();
          maxLimit = BigInteger.TWO.pow(bitWidth - 1).subtract(BigInteger.ONE);
        }

        if (bi.compareTo(minLimit) < 0 || bi.compareTo(maxLimit) > 0) {
          throw new StvnIntegerOverflowException(
              "Integer value " + bi + " overruns annotated bit configuration: minimum allowed is " + minLimit + ", maximum allowed is " + maxLimit
          );
        }

        return Optional.of(new StvnValue.StvnInteger(schema, bi, bitWidth, isUnsigned));
      }
    }
    if (recordInstance instanceof List<?> list) {
      var innerSchemaOpt = getCollectionInnerSchema(schemaOpt, documentContext);
      var mapped = new ArrayList<StvnValue>();
      for (var el : list) {
        var valOpt = toValue(el, innerSchemaOpt, documentContext);
        var val = valOpt.orElseThrow(() -> new MalformedPayloadException("List elements cannot be null"));
        mapped.add(val);
      }
      return Optional.of(new StvnValue.StvnSeq(schema, mapped, !mapped.isEmpty()));
    }
    if (recordInstance instanceof Set<?> set) {
      if (!(set instanceof SequencedSet)) {
        throw new MalformedPayloadException("Sets must implement SequencedSet. Found: " + set.getClass().getName());
      }
      var innerSchemaOpt = getCollectionInnerSchema(schemaOpt, documentContext);
      var mapped = new LinkedHashSet<StvnValue>();
      for (var el : set) {
        var valOpt = toValue(el, innerSchemaOpt, documentContext);
        var val = valOpt.orElseThrow(() -> new MalformedPayloadException("Set elements cannot be null"));
        mapped.add(val);
      }
      return Optional.of(new StvnValue.StvnSet(schema, mapped, !mapped.isEmpty()));
    }
    if (recordInstance instanceof Map<?, ?> map) {
      if (!(map instanceof SequencedMap)) {
        throw new MalformedPayloadException("Maps must implement SequencedMap. Found: " + map.getClass().getName());
      }
      var innerNodes = Optional.ofNullable(schema.node())
          .map(StvnTypeResolver::getInnerSchemas)
          .orElse(List.of());

      var innerKeySchemaOpt = innerNodes.size() > 0
          ? StvnTypeResolver.resolvePrimitiveSchema(documentContext, innerNodes.get(0), new HashSet<>())
          : Optional.<ResolvedSchema>empty();

      var innerValSchemaOpt = innerNodes.size() > 1
          ? StvnTypeResolver.resolvePrimitiveSchema(documentContext, innerNodes.get(1), new HashSet<>())
          : Optional.<ResolvedSchema>empty();

      var mapped = new LinkedHashMap<StvnValue, StvnValue>();
      for (var entry : map.entrySet()) {
        var kOpt = toValue(entry.getKey(), innerKeySchemaOpt, documentContext);
        var k = kOpt.orElseThrow(() -> new MalformedPayloadException("Map keys cannot be null"));
        var vOpt = toValue(entry.getValue(), innerValSchemaOpt, documentContext);
        var v = vOpt.orElseThrow(() -> new MalformedPayloadException("Map values cannot be null"));
        mapped.put(k, v);
      }

      boolean isInvertible = Optional.ofNullable(schema.node())
          .map(StvnTypeResolver::getPrimitiveBaseType)
          .filter(text -> text.startsWith(":MapInv"))
          .isPresent();

      if (isInvertible) {
        var seenValues = new HashSet<StvnValue>(mapped.size());
        for (var entry : mapped.entrySet()) {
          if (!seenValues.add(entry.getValue())) {
            throw new MalformedPayloadException("Duplicate value detected in invertible map (MapInv): " + entry.getValue());
          }
        }
      }

      return Optional.of(new StvnValue.StvnMap(schema, mapped, !mapped.isEmpty(), isInvertible));
    }

    throw new IllegalArgumentException("Unsupported type for mapping: " + recordInstance.getClass().getName());
  }

  /**
   * Deserializes an STVN AST node to its corresponding native Java object representation,
   * allowing optional schemas and custom document context.
   * <p>
   * Coordinates target instantiation (records and standard collections) and applies implied tagging
   * rules to map untagged option/either payloads to their correct targets.
   * <p>
   * <b>Preconditions:</b>
   * <ul>
   *   <li>{@code ast} must not be {@code null}.</li>
   *   <li>{@code targetClass} must not be {@code null}.</li>
   *   <li>{@code schemaOpt} must not be {@code null}.</li>
   *   <li>{@code documentContext} must not be {@code null}.</li>
   * </ul>
   *
   * @param <T>             the target Java class type
   * @param ast             the STVN value tree to deserialize
   * @param targetClass     the target Java class to instantiate
   * @param schemaOpt       the optional resolved schema definition
   * @param documentContext the active STVN document compilation context
   * @return an {@link Optional} enclosing the deserialized instance, cast to the target class
   * @throws NullPointerException      if any parameter is {@code null}
   * @throws MalformedPayloadException if structural or validation constraints are violated
   */
  public static <T> Optional<T> fromValue(StvnValue ast, Class<T> targetClass, Optional<ResolvedSchema> schemaOpt, StvnDocumentContext documentContext) {
    Objects.requireNonNull(ast);
    Objects.requireNonNull(targetClass);
    Objects.requireNonNull(schemaOpt);
    Objects.requireNonNull(documentContext);

    if (isPOJO(targetClass)) {
      throw new IllegalArgumentException("POJO types are rejected by the mapper: " + targetClass.getName());
    }
    return Optional.ofNullable(targetClass.cast(fromValueInternal(ast, targetClass, targetClass, schemaOpt, documentContext)));
  }

  private static Object fromValueInternal(StvnValue ast, Class<?> targetClass, Type genericType, Optional<ResolvedSchema> schemaOpt, StvnDocumentContext documentContext) {
    Objects.requireNonNull(ast);
    Objects.requireNonNull(targetClass);
    Objects.requireNonNull(genericType);
    Objects.requireNonNull(schemaOpt);
    Objects.requireNonNull(documentContext);

    var schema = schemaOpt.or(() -> Optional.ofNullable(PRIMITIVE_SCHEMA_REGISTRY.get(targetClass)))
        .orElseThrow(() -> new MalformedPayloadException("No primitive schema mapping found for class: " + targetClass.getName()));

    if (targetClass.isRecord()) {
      var meta = StvnRecordCache.get(targetClass);
      Object[] args = new Object[meta.components().length];

      for (int i = 0; i < meta.components().length; i++) {
        var comp = meta.components()[i];
        var valNode = Optional.<StvnValue>empty();
        if (ast instanceof StvnValue.StvnTuple tup) {
          if (i < tup.elements().size()) {
            valNode = Optional.of(tup.elements().get(i));
          }
        } else if (ast instanceof StvnValue.StvnMap m) {
          for (var entry : m.entries().entrySet()) {
            if (entry.getKey() instanceof StvnValue.StvnString s && s.value().equals(comp.name())) {
              valNode = Optional.of(entry.getValue());
              break;
            }
          }
        }

        final int index = i;
        var childSchemaOpt = Optional.ofNullable(schema.node())
            .map(StvnTypeResolver::getInnerSchemas)
            .flatMap(inners -> {
              if (ast instanceof StvnValue.StvnTuple) {
                if (index < inners.size()) {
                  return StvnTypeResolver.resolvePrimitiveSchema(documentContext, inners.get(index), new HashSet<>());
                }
              } else {
                if (inners.size() >= 2) {
                  return StvnTypeResolver.resolvePrimitiveSchema(documentContext, inners.get(1), new HashSet<>());
                }
              }
              return Optional.empty();
            })
            .or(() -> Optional.ofNullable(comp.bitsAnnotation())
                .map(bits -> parsePrimitiveSchema((bits.unsigned() ? ":Uint" : ":Int") + bits.value(), "0")));

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
          args[i] = fromValueInternal(valNode.get(), comp.type(), comp.genericType(), childSchemaOpt, documentContext);
          checkOptionalLoophole(comp, args[i]);
        }
      }

      try {
        return meta.canonicalConstructor().invokeWithArguments(args);
      } catch (Throwable t) {
        if (t instanceof RuntimeException) throw (RuntimeException) t;
        throw new RuntimeException(t);
      }
    }

    if (SealedInterfaceMapper.isSealedInterface(targetClass) || isSealedSchema(schema)) {
      return SealedInterfaceMapper.fromValue(ast, targetClass, schemaOpt, documentContext)
          .orElseThrow(() -> new MalformedPayloadException("Failed to map sealed interface: " + targetClass.getName()));
    }

    if (targetClass == Optional.class) {
      if (ast instanceof StvnValue.StvnOption opt) {
        Class<?> innerClass = getGenericTypeArgument(genericType, 0);
        Type innerGenType = (genericType instanceof ParameterizedType pt) ? pt.getActualTypeArguments()[0] : Object.class;
        return opt.value().map(val -> fromValueInternal(val, innerClass, innerGenType, getOptionInnerSchema(schemaOpt, documentContext), documentContext));
      } else {
        Class<?> innerClass = getGenericTypeArgument(genericType, 0);
        Type innerGenType = (genericType instanceof ParameterizedType pt) ? pt.getActualTypeArguments()[0] : Object.class;
        return Optional.of(fromValueInternal(ast, innerClass, innerGenType, getOptionInnerSchema(schemaOpt, documentContext), documentContext));
      }
    }
    if (targetClass == OptionalInt.class) {
      if (ast instanceof StvnValue.StvnOption opt) {
        return opt.value()
            .map(val -> (Number) fromValueInternal(val, Integer.class, Integer.class, getOptionInnerSchema(schemaOpt, documentContext), documentContext))
            .map(num -> OptionalInt.of(num.intValue()))
            .orElseGet(OptionalInt::empty);
      }
      return OptionalInt.empty();
    }
    if (targetClass == OptionalLong.class) {
      if (ast instanceof StvnValue.StvnOption opt) {
        return opt.value()
            .map(val -> (Number) fromValueInternal(val, Long.class, Long.class, getOptionInnerSchema(schemaOpt, documentContext), documentContext))
            .map(num -> OptionalLong.of(num.longValue()))
            .orElseGet(OptionalLong::empty);
      }
      return OptionalLong.empty();
    }
    if (targetClass == OptionalDouble.class) {
      if (ast instanceof StvnValue.StvnOption opt) {
        return opt.value()
            .map(val -> (Number) fromValueInternal(val, Double.class, Double.class, getOptionInnerSchema(schemaOpt, documentContext), documentContext))
            .map(num -> OptionalDouble.of(num.doubleValue()))
            .orElseGet(OptionalDouble::empty);
      }
      return OptionalDouble.empty();
    }

    if (List.class.isAssignableFrom(targetClass)) {
      if (!(ast instanceof StvnValue.StvnSeq seq)) {
        throw new MalformedPayloadException("Expected StvnSeq for List but got " + ast.getClass().getSimpleName());
      }
      Class<?> itemClass = getGenericTypeArgument(genericType, 0);
      Type itemGenType = (genericType instanceof ParameterizedType pt) ? pt.getActualTypeArguments()[0] : Object.class;
      var innerSchemaOpt = getCollectionInnerSchema(schemaOpt, documentContext);

      var list = new ArrayList<>();
      for (var el : seq.elements()) {
        list.add(fromValueInternal(el, itemClass, itemGenType, innerSchemaOpt, documentContext));
      }
      return List.copyOf(list);
    }

    if (Set.class.isAssignableFrom(targetClass)) {
      if (!(ast instanceof StvnValue.StvnSet setNode)) {
        throw new MalformedPayloadException("Expected StvnSet for Set but got " + ast.getClass().getSimpleName());
      }
      Class<?> itemClass = getGenericTypeArgument(genericType, 0);
      Type itemGenType = (genericType instanceof ParameterizedType pt) ? pt.getActualTypeArguments()[0] : Object.class;
      var innerSchemaOpt = getCollectionInnerSchema(schemaOpt, documentContext);

      var list = new LinkedHashSet<>();
      for (var el : setNode.elements()) {
        list.add(fromValueInternal(el, itemClass, itemGenType, innerSchemaOpt, documentContext));
      }
      return Collections.unmodifiableSequencedSet(list);
    }

    if (Map.class.isAssignableFrom(targetClass)) {
      if (!(ast instanceof StvnValue.StvnMap mapNode)) {
        throw new MalformedPayloadException("Expected StvnMap for Map but got " + ast.getClass().getSimpleName());
      }
      Class<?> keyClass = getGenericTypeArgument(genericType, 0);
      Class<?> valClass = getGenericTypeArgument(genericType, 1);

      Type keyGenType = (genericType instanceof ParameterizedType pt) ? pt.getActualTypeArguments()[0] : Object.class;
      Type valGenType = (genericType instanceof ParameterizedType pt) ? pt.getActualTypeArguments()[1] : Object.class;

      var innerNodes = Optional.ofNullable(schema.node())
          .map(StvnTypeResolver::getInnerSchemas)
          .orElse(List.of());

      var innerKeySchemaOpt = innerNodes.size() > 0
          ? StvnTypeResolver.resolvePrimitiveSchema(documentContext, innerNodes.get(0), new HashSet<>())
          : Optional.<ResolvedSchema>empty();

      var innerValSchemaOpt = innerNodes.size() > 1
          ? StvnTypeResolver.resolvePrimitiveSchema(documentContext, innerNodes.get(1), new HashSet<>())
          : Optional.<ResolvedSchema>empty();

      var map = new LinkedHashMap<>();
      for (var entry : mapNode.entries().entrySet()) {
        map.put(
            fromValueInternal(entry.getKey(), keyClass, keyGenType, innerKeySchemaOpt, documentContext),
            fromValueInternal(entry.getValue(), valClass, valGenType, innerValSchemaOpt, documentContext)
        );
      }

      if (mapNode.isInvertible()) {
        var seenValues = new HashSet<>(map.size());
        for (var entry : map.entrySet()) {
          if (!seenValues.add(entry.getValue())) {
            throw new MalformedPayloadException("Duplicate value detected in invertible map (MapInv): " + entry.getValue());
          }
        }
      }

      return Collections.unmodifiableSequencedMap(map);
    }

    if (targetClass == String.class) {
      if (!(ast instanceof StvnValue.StvnString s)) {
        throw new MalformedPayloadException("Expected StvnString for String but got " + ast.getClass().getSimpleName());
      }
      return s.value();
    }
    if (targetClass == Boolean.class || targetClass == boolean.class) {
      if (!(ast instanceof StvnValue.StvnBoolean b)) {
        throw new MalformedPayloadException("Expected StvnBoolean for boolean but got " + ast.getClass().getSimpleName());
      }
      return b.value();
    }
    if (targetClass == Integer.class || targetClass == int.class) {
      if (!(ast instanceof StvnValue.StvnInteger i)) {
        throw new MalformedPayloadException("Expected StvnInteger for int but got " + ast.getClass().getSimpleName());
      }
      return i.value().intValue();
    }
    if (targetClass == Long.class || targetClass == long.class) {
      if (!(ast instanceof StvnValue.StvnInteger i)) {
        throw new MalformedPayloadException("Expected StvnInteger for long but got " + ast.getClass().getSimpleName());
      }
      return i.value().longValue();
    }
    if (targetClass == Short.class || targetClass == short.class) {
      if (!(ast instanceof StvnValue.StvnInteger i)) {
        throw new MalformedPayloadException("Expected StvnInteger for short but got " + ast.getClass().getSimpleName());
      }
      return i.value().shortValue();
    }
    if (targetClass == Byte.class || targetClass == byte.class) {
      if (!(ast instanceof StvnValue.StvnInteger i)) {
        throw new MalformedPayloadException("Expected StvnInteger for byte but got " + ast.getClass().getSimpleName());
      }
      return i.value().byteValue();
    }
    if (targetClass == BigInteger.class) {
      if (!(ast instanceof StvnValue.StvnInteger i)) {
        throw new MalformedPayloadException("Expected StvnInteger for BigInteger but got " + ast.getClass().getSimpleName());
      }
      return i.value();
    }
    if (targetClass == Double.class || targetClass == double.class) {
      if (!(ast instanceof StvnValue.StvnFloat f)) {
        throw new MalformedPayloadException("Expected StvnFloat for double but got " + ast.getClass().getSimpleName());
      }
      return f.value().doubleValue();
    }
    if (targetClass == Float.class || targetClass == float.class) {
      if (!(ast instanceof StvnValue.StvnFloat f)) {
        throw new MalformedPayloadException("Expected StvnFloat for float but got " + ast.getClass().getSimpleName());
      }
      return f.value().floatValue();
    }
    if (targetClass == BigDecimal.class) {
      if (!(ast instanceof StvnValue.StvnFloat f)) {
        throw new MalformedPayloadException("Expected StvnFloat for BigDecimal but got " + ast.getClass().getSimpleName());
      }
      return f.value();
    }

    throw new IllegalArgumentException("Unsupported target class for mapping: " + targetClass.getName());
  }

  private static boolean isPOJO(Class<?> clazz) {
    Objects.requireNonNull(clazz);
    if (clazz.isRecord() || clazz.isPrimitive() || clazz.isEnum()) return false;
    if (clazz == String.class || clazz == Boolean.class || clazz == Character.class) return false;
    if (Number.class.isAssignableFrom(clazz)) return false;
    if (Collection.class.isAssignableFrom(clazz) || Map.class.isAssignableFrom(clazz)) return false;
    if (clazz == Optional.class || clazz == OptionalInt.class || clazz == OptionalLong.class || clazz == OptionalDouble.class) return false;
    if (clazz.isInterface() && clazz.isSealed()) return false;
    if (findSealedInterface(clazz).isPresent()) return false;
    return true;
  }

  private static Optional<Class<?>> findSealedInterface(Class<?> clazz) {
    Objects.requireNonNull(clazz);
    for (var iface : clazz.getInterfaces()) {
      if (iface.isSealed()) {
        return Optional.of(iface);
      }
    }
    var superclass = clazz.getSuperclass();
    if (superclass != null && superclass != Object.class) {
      if (superclass.isSealed()) {
        return Optional.of(superclass);
      }
      return findSealedInterface(superclass);
    }
    return Optional.empty();
  }

  private static void checkOptionalLoophole(StvnRecordCache.RecordComponentProfile comp, Object value) {
    Objects.requireNonNull(comp);
    if (comp.type() == Optional.class ||
        comp.type() == OptionalInt.class ||
        comp.type() == OptionalLong.class ||
        comp.type() == OptionalDouble.class) {
      if (value == null) {
        throw new MalformedPayloadException(
            "Optional Null Loophole detected: record component '" + comp.name() + "' is null, which is invalid."
        );
      }
    }
  }

  private static StvnValue validateAndEnrichConstraints(StvnRecordCache.RecordComponentProfile comp, StvnValue mapped) {
    Objects.requireNonNull(comp);
    Objects.requireNonNull(mapped);

    if (comp.stringAnnotation() != null && mapped instanceof StvnValue.StvnString strVal) {
      var ann = comp.stringAnnotation();
      if (ann.nonEmpty() && strVal.value().isEmpty()) {
        throw new MalformedPayloadException("Field '" + comp.name() + "' violates @StvnString(nonEmpty = true)");
      }
      var extra = new StvnConstraints(
          Optional.of(BigDecimal.ONE),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          false,
          Optional.empty(),
          Optional.empty(),
          List.of()
      );
      if (ann.nonEmpty()) {
        var updatedTrait = new StvnValue.StringTrait(strVal.trait().fixedLength(), strVal.trait().maxLength(), true);
        mapped = new StvnValue.StvnString(strVal.schema(), strVal.value(), strVal.style(), strVal.fenceTag(), updatedTrait);
      }
      mapped = attachConstraints(mapped, extra);
    }

    if (comp.intAnnotation() != null && mapped instanceof StvnValue.StvnInteger intVal) {
      var ann = comp.intAnnotation();
      long longVal = intVal.value().longValue();
      if (longVal < ann.minIncl() || longVal > ann.maxIncl()) {
        throw new MalformedPayloadException(
            "Field '" + comp.name() + "' value " + longVal + " violates @StvnInt range [" + ann.minIncl() + ", " + ann.maxIncl() + "]"
        );
      }
      var extra = new StvnConstraints(
          Optional.of(BigDecimal.valueOf(ann.minIncl())),
          Optional.empty(),
          Optional.of(BigDecimal.valueOf(ann.maxIncl())),
          Optional.empty(),
          Optional.empty(),
          false,
          Optional.empty(),
          Optional.empty(),
          List.of()
      );
      mapped = attachConstraints(mapped, extra);
    }

    return mapped;
  }

  private static StvnValue attachConstraints(StvnValue val, StvnConstraints extraConstraints) {
    Objects.requireNonNull(val);
    Objects.requireNonNull(extraConstraints);
    var origSchema = val.schema();
    var mergedLocal = origSchema.localConstraints()
        .map(local -> local.merge(extraConstraints))
        .orElse(extraConstraints);
    var mergedConstraints = origSchema.constraints().merge(extraConstraints);
    var newSchema = new ResolvedSchema(
        origSchema.node(),
        mergedConstraints,
        origSchema.aliasName(),
        origSchema.implicitUnionTag(),
        origSchema.sumTypeNode(),
        origSchema.underlyingSchema(),
        Optional.of(mergedLocal)
    );

    return switch (val) {
      case StvnValue.StvnBoolean b -> new StvnValue.StvnBoolean(newSchema, b.value());
      case StvnValue.StvnInteger i -> new StvnValue.StvnInteger(newSchema, i.value(), i.bitWidth(), i.isUnsigned());
      case StvnValue.StvnFloat f -> new StvnValue.StvnFloat(newSchema, f.value(), f.precision());
      case StvnValue.StvnString s -> new StvnValue.StvnString(newSchema, s.value(), s.style(), s.fenceTag(), s.trait());
      case StvnValue.StvnTime t -> new StvnValue.StvnTime(newSchema, t.value(), t.kind());
      case StvnValue.StvnDateTimeOffset dto -> new StvnValue.StvnDateTimeOffset(newSchema, dto.value());
      case StvnValue.StvnDateTimeZoned dtz -> new StvnValue.StvnDateTimeZoned(newSchema, dtz.localDateTime(), dtz.zoneId());
      case StvnValue.StvnDateTimeAudited dta -> new StvnValue.StvnDateTimeAudited(newSchema, dta.offsetDateTime(), dta.zoneId());
      case StvnValue.StvnSeq seq -> new StvnValue.StvnSeq(newSchema, seq.elements(), seq.isNonEmpty());
      case StvnValue.StvnSet set -> new StvnValue.StvnSet(newSchema, set.elements(), set.isNonEmpty());
      case StvnValue.StvnMap m -> new StvnValue.StvnMap(newSchema, m.entries(), m.isNonEmpty(), m.isInvertible());
      case StvnValue.StvnTuple tup -> new StvnValue.StvnTuple(newSchema, tup.elements());
      case StvnValue.StvnOption opt -> new StvnValue.StvnOption(newSchema, opt.value());
      case StvnValue.StvnEither e -> new StvnValue.StvnEither(newSchema, e.value(), e.isRight(), e.isAmbiguous());
      case StvnValue.StvnUnion u -> new StvnValue.StvnUnion(newSchema, u.value(), u.tagIndex());
      case StvnValue.StvnEnum en -> new StvnValue.StvnEnum(newSchema, en.keyword(), en.sequentialIndex(), en.variantCount());
      case StvnValue.StvnError err -> new StvnValue.StvnError(newSchema, err.rawText(), err.startOffset(), err.endOffset(), err.diagnostics());
    };
  }

  private static Optional<ResolvedSchema> getOptionInnerSchema(Optional<ResolvedSchema> schemaOpt, StvnDocumentContext documentContext) {
    Objects.requireNonNull(schemaOpt);
    Objects.requireNonNull(documentContext);
    return schemaOpt.flatMap(schema -> Optional.ofNullable(schema.node()))
        .map(StvnTypeResolver::getInnerSchemas)
        .filter(inners -> !inners.isEmpty())
        .flatMap(inners -> StvnTypeResolver.resolvePrimitiveSchema(documentContext, inners.get(0), new HashSet<>()));
  }

  private static Optional<ResolvedSchema> getCollectionInnerSchema(Optional<ResolvedSchema> schemaOpt, StvnDocumentContext documentContext) {
    Objects.requireNonNull(schemaOpt);
    Objects.requireNonNull(documentContext);
    return schemaOpt.flatMap(schema -> Optional.ofNullable(schema.node()))
        .map(StvnTypeResolver::getInnerSchemas)
        .filter(inners -> !inners.isEmpty())
        .flatMap(inners -> StvnTypeResolver.resolvePrimitiveSchema(documentContext, inners.get(0), new HashSet<>()));
  }

  private static boolean isSealedSchema(ResolvedSchema schema) {
    Objects.requireNonNull(schema);
    return Optional.ofNullable(schema.node())
        .map(StvnTypeResolver::getPrimitiveBaseType)
        .filter(base -> ":Either".equals(base) || ":Union".equals(base))
        .isPresent();
  }

  private static Class<?> getGenericTypeArgument(Type type, int index) {
    Objects.requireNonNull(type);
    if (type instanceof ParameterizedType pt) {
      var args = pt.getActualTypeArguments();
      if (index < args.length && args[index] instanceof Class<?> clazz) {
        return clazz;
      }
    }
    return Object.class;
  }
}
