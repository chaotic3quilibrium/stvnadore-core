package org.stvnadore.core.mapper;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.Objects;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.ir.StvnValue.StvnEither;
import org.stvnadore.core.ir.StvnValue.StvnUnion;
import org.stvnadore.core.validation.MalformedPayloadException;
import org.stvnadore.core.validation.StvnTypeResolver;
import org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema;
import org.stvnadore.core.parser.StvnParser.StvnDocumentContext;

/**
 * A utility mapper responsible for translating Java {@code sealed} interfaces and classes
 * to STVN algebraic sum types ({@link StvnEither} and {@link StvnUnion}).
 * <p>
 * <b>Subclass Mapping Conventions:</b>
 * <ul>
 *   <li><b>2-Way Either Convention:</b> If a sealed interface defines exactly two permitted subclasses,
 *       one named {@code Left} and one named {@code Right} (case-insensitive), it is mapped to an
 *       STVN {@code :Either} sum type. The {@code Left} class represents the left variant and the
 *       {@code Right} class represents the right variant.</li>
 *   <li><b>N-Way Union Convention:</b> Otherwise, the permitted subclasses are sorted strictly in
 *       alphabetical order by their fully qualified names (using {@link Class#getName()}) and mapped to a
 *       multi-way STVN {@code :Union} layout. The tag index corresponds directly to the alphabetical sorting position.</li>
 * </ul>
 * <p>
 * <b>Null Safety:</b>
 * Compliant with the Tier 1 null-safety and optionality contracts. All parameters must be non-null.
 *
 * @since 1.0.0
 */
public final class SealedInterfaceMapper {

  private SealedInterfaceMapper() {}

  /**
   * Checks if the given class represents a sealed interface.
   * <p>
   * <b>Preconditions:</b>
   * <ul>
   *   <li>{@code type} must not be {@code null}.</li>
   * </ul>
   *
   * @param type the class to inspect
   * @return {@code true} if the type is a sealed interface, otherwise {@code false}
   * @throws NullPointerException if {@code type} is {@code null}
   */
  public static boolean isSealedInterface(Class<?> type) {
    Objects.requireNonNull(type);
    return type.isInterface() && type.isSealed();
  }

  /**
   * Translates an instance of a permitted subclass of a sealed interface into its corresponding
   * STVN algebraic representation using the provided resolved schema context.
   * <p>
   * <b>Preconditions:</b>
   * <ul>
   *   <li>{@code declaredType} must be a sealed interface.</li>
   *   <li>{@code instance} must be a non-null instance of one of the permitted subclasses.</li>
   *   <li>{@code schema} must be non-null.</li>
   * </ul>
   * <p>
   * <b>Postconditions:</b>
   * <ul>
   *   <li>Returns a non-null {@link Optional} containing the constructed {@link StvnValue} node.</li>
   * </ul>
   *
   * @param instance     the runtime instance to map
   * @param declaredType the declared sealed interface class type
   * @param schema       the resolved schema definition
   * @return an {@link Optional} containing the mapped {@link StvnValue}
   * @throws NullPointerException      if any parameter is {@code null}
   * @throws MalformedPayloadException if the subclass constraints or schema configurations are violated
   */
  public static Optional<StvnValue> toValue(Object instance, Class<?> declaredType, ResolvedSchema schema) {
    Objects.requireNonNull(declaredType);
    Objects.requireNonNull(schema);
    return toValue(instance, declaredType, Optional.of(schema), StvnMapper.getDocumentContext(schema));
  }

  /**
   * Instantiates a permitted subclass of a sealed interface from its corresponding STVN algebraic AST node.
   * <p>
   * <b>Preconditions:</b>
   * <ul>
   *   <li>{@code targetClass} must be a sealed interface.</li>
   *   <li>{@code ast} must be a non-null {@link StvnEither} or {@link StvnUnion} node.</li>
   *   <li>{@code schema} must be non-null.</li>
   * </ul>
   * <p>
   * <b>Postconditions:</b>
   * <ul>
   *   <li>Returns a non-null {@link Optional} enclosing the deserialized instance, cast to the target type.</li>
   * </ul>
   *
   * @param <T>         the target sealed interface type
   * @param ast         the STVN sum type value node (Either or Union)
   * @param targetClass the target sealed interface class
   * @param schema      the resolved schema definition
   * @return an {@link Optional} containing the deserialized instance
   * @throws NullPointerException      if any parameter is {@code null}
   * @throws MalformedPayloadException if the STVN node does not match the sealed interface's layout conventions
   */
  public static <T> Optional<T> fromValue(StvnValue ast, Class<T> targetClass, ResolvedSchema schema) {
    Objects.requireNonNull(ast);
    Objects.requireNonNull(targetClass);
    Objects.requireNonNull(schema);
    return fromValue(ast, targetClass, Optional.of(schema), StvnMapper.getDocumentContext(schema));
  }

  /**
   * Translates an instance of a permitted subclass of a sealed interface into its corresponding
   * STVN algebraic representation, allowing optional schemas and custom document context.
   * <p>
   * <b>Preconditions:</b>
   * <ul>
   *   <li>{@code declaredType} must be a sealed interface.</li>
   *   <li>{@code instance} must be a non-null instance of one of the permitted subclasses.</li>
   * </ul>
   *
   * @param instance        the runtime instance to map
   * @param declaredType    the declared sealed interface class type
   * @param schemaOpt       optional resolved schema mapping this node
   * @param documentContext the active STVN document compilation context
   * @return an {@link Optional} enclosing the mapped {@link StvnValue}
   * @throws NullPointerException      if any parameter is {@code null}
   * @throws MalformedPayloadException if the schema is missing or invalid
   */
  public static Optional<StvnValue> toValue(Object instance, Class<?> declaredType, Optional<ResolvedSchema> schemaOpt, StvnDocumentContext documentContext) {
    Objects.requireNonNull(instance);
    Objects.requireNonNull(declaredType);
    Objects.requireNonNull(schemaOpt);
    Objects.requireNonNull(documentContext);

    var schema = schemaOpt.orElseThrow(() -> new MalformedPayloadException("Cannot map sealed interface without a schema: " + declaredType.getName()));

    Class<?> runtimeClass = instance.getClass();
    Class<?>[] permittedClasses = declaredType.getPermittedSubclasses();
    if (permittedClasses == null || permittedClasses.length == 0) {
      throw new MalformedPayloadException("Sealed interface " + declaredType.getName() + " has no permitted subclasses");
    }

    // 1. Check for 2-way Either convention
    if (permittedClasses.length == 2) {
      Class<?> p0 = permittedClasses[0];
      Class<?> p1 = permittedClasses[1];
      boolean p0Left = p0.getSimpleName().equalsIgnoreCase("Left");
      boolean p0Right = p0.getSimpleName().equalsIgnoreCase("Right");
      boolean p1Left = p1.getSimpleName().equalsIgnoreCase("Left");
      boolean p1Right = p1.getSimpleName().equalsIgnoreCase("Right");

      if ((p0Left && p1Right) || (p0Right && p1Left)) {
        Class<?> leftClass = p0Left ? p0 : p1;
        Class<?> rightClass = p0Right ? p0 : p1;

        boolean isRight = runtimeClass.equals(rightClass);
        if (!runtimeClass.equals(leftClass) && !isRight) {
          throw new MalformedPayloadException("Instance class " + runtimeClass.getName() + " is not a permitted subclass of " + declaredType.getName());
        }

        var innerSchemaOpt = schemaOpt.flatMap(s -> Optional.ofNullable(s.node()))
            .flatMap(node -> {
              var inners = StvnTypeResolver.getInnerSchemas(node);
              if (inners.size() == 2) {
                var selectedNode = isRight ? inners.get(1) : inners.get(0);
                return StvnTypeResolver.resolvePrimitiveSchema(documentContext, selectedNode, new HashSet<>());
              }
              return Optional.empty();
            });

        return StvnMapper.toValue(instance, innerSchemaOpt, documentContext)
            .map(innerValue -> new StvnEither(schema, innerValue, isRight, false));
      }
    }

    // 2. Fall back to N-way Union
    // Sort subclasses strictly by fully qualified class name using Class::getName
    Class<?>[] sortedPermitted = permittedClasses.clone();
    Arrays.sort(sortedPermitted, Comparator.comparing(Class::getName));

    int tagIndex = -1;
    for (int i = 0; i < sortedPermitted.length; i++) {
      if (sortedPermitted[i].equals(runtimeClass)) {
        tagIndex = i;
        break;
      }
    }

    if (tagIndex == -1) {
      throw new MalformedPayloadException("Instance class " + runtimeClass.getName() + " is not a permitted subclass of " + declaredType.getName());
    }

    final int finalTagIndex = tagIndex;
    var innerSchemaOpt = schemaOpt.flatMap(s -> Optional.ofNullable(s.node()))
        .flatMap(node -> {
          var inners = StvnTypeResolver.getInnerSchemas(node);
          if (finalTagIndex < inners.size()) {
            return StvnTypeResolver.resolvePrimitiveSchema(documentContext, inners.get(finalTagIndex), new HashSet<>());
          }
          return Optional.empty();
        });

    return StvnMapper.toValue(instance, innerSchemaOpt, documentContext)
        .map(innerValue -> new StvnUnion(schema, innerValue, finalTagIndex));
  }

  /**
   * Instantiates a permitted subclass of a sealed interface from its corresponding STVN algebraic AST node,
   * allowing optional schemas and custom document context.
   * <p>
   * <b>Preconditions:</b>
   * <ul>
   *   <li>{@code targetClass} must be a sealed interface.</li>
   * </ul>
   *
   * @param <T>             the target sealed interface type
   * @param ast             the STVN sum type value node
   * @param targetClass     the target sealed interface class
   * @param schemaOpt       optional resolved schema mapping this node
   * @param documentContext the active STVN document compilation context
   * @return an {@link Optional} enclosing the deserialized instance
   * @throws NullPointerException      if any parameter is {@code null}
   * @throws MalformedPayloadException if layout structures are mismatched or index thresholds exceeded
   */
  public static <T> Optional<T> fromValue(StvnValue ast, Class<T> targetClass, Optional<ResolvedSchema> schemaOpt, StvnDocumentContext documentContext) {
    Objects.requireNonNull(ast);
    Objects.requireNonNull(targetClass);
    Objects.requireNonNull(schemaOpt);
    Objects.requireNonNull(documentContext);

    var schema = schemaOpt.orElseThrow(() -> new MalformedPayloadException("Cannot map sealed interface without a schema: " + targetClass.getName()));

    Class<?>[] permittedClasses = targetClass.getPermittedSubclasses();
    if (permittedClasses == null || permittedClasses.length == 0) {
      throw new MalformedPayloadException("Sealed interface " + targetClass.getName() + " has no permitted subclasses");
    }

    if (ast instanceof StvnEither eitherNode) {
      if (permittedClasses.length != 2) {
        throw new MalformedPayloadException("Expected a 2-way sealed interface matching Either structure but got: " + targetClass.getName());
      }
      Class<?> p0 = permittedClasses[0];
      Class<?> p1 = permittedClasses[1];
      boolean p0Left = p0.getSimpleName().equalsIgnoreCase("Left");
      boolean p0Right = p0.getSimpleName().equalsIgnoreCase("Right");
      boolean p1Left = p1.getSimpleName().equalsIgnoreCase("Left");
      boolean p1Right = p1.getSimpleName().equalsIgnoreCase("Right");

      if (!((p0Left && p1Right) || (p0Right && p1Left))) {
        throw new MalformedPayloadException("Sealed interface " + targetClass.getName() + " permitted subclass names do not match Either convention (Left/Right)");
      }

      Class<?> leftClass = p0Left ? p0 : p1;
      Class<?> rightClass = p0Right ? p0 : p1;
      Class<?> targetSubclass = eitherNode.isRight() ? rightClass : leftClass;

      var innerSchemaOpt = schemaOpt.flatMap(s -> Optional.ofNullable(s.node()))
          .flatMap(node -> {
            var inners = StvnTypeResolver.getInnerSchemas(node);
            if (inners.size() == 2) {
              var selectedNode = eitherNode.isRight() ? inners.get(1) : inners.get(0);
              return StvnTypeResolver.resolvePrimitiveSchema(documentContext, selectedNode, new HashSet<>());
            }
            return Optional.empty();
          });

      return StvnMapper.fromValue(eitherNode.value(), targetSubclass, innerSchemaOpt, documentContext)
          .map(targetClass::cast);
    }

    if (ast instanceof StvnUnion unionNode) {
      Class<?>[] sortedPermitted = permittedClasses.clone();
      Arrays.sort(sortedPermitted, Comparator.comparing(Class::getName));

      int idx = unionNode.tagIndex();
      if (idx < 0 || idx >= sortedPermitted.length) {
        throw new MalformedPayloadException("Union tagIndex " + idx + " out of bounds for " + targetClass.getName());
      }

      Class<?> targetSubclass = sortedPermitted[idx];

      var innerSchemaOpt = schemaOpt.flatMap(s -> Optional.ofNullable(s.node()))
          .flatMap(node -> {
            var inners = StvnTypeResolver.getInnerSchemas(node);
            if (idx < inners.size()) {
              return StvnTypeResolver.resolvePrimitiveSchema(documentContext, inners.get(idx), new HashSet<>());
            }
            return Optional.empty();
          });

      return StvnMapper.fromValue(unionNode.value(), targetSubclass, innerSchemaOpt, documentContext)
          .map(targetClass::cast);
    }

    throw new MalformedPayloadException("Unsupported STVN value type for sealed interface " + targetClass.getName() + ": " + ast.getClass().getSimpleName());
  }
}
