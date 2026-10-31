package org.stvnadore.core.mapper;

import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.ir.StvnValue.EitherBranch;
import org.stvnadore.core.ir.StvnValue.StvnEither;
import org.stvnadore.core.ir.StvnValue.StvnUnion;
import org.stvnadore.core.validation.MalformedPayloadException;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure-IR mapper responsible for translating Java {@code sealed} interfaces and classes
 * to and from STVN 2.0.0 algebraic sum types ({@link StvnEither} and {@link StvnUnion}).
 * <p>
 * <b>Subclass Mapping Conventions:</b>
 * <ul>
 *   <li><b>2-Way Either Convention:</b> If a sealed interface defines two permitted subclasses,
 *       one named {@code Left} and one named {@code Right} (case-insensitive), it maps to an
 *       STVN {@code :Either} sum type ({@link EitherBranch#LEFT} and {@link EitherBranch#RIGHT}).</li>
 *   <li><b>N-Way Union Convention:</b> Otherwise, permitted subclasses are sorted in
 *       lexicographical order by their fully qualified names ({@link Class#getName()}) and mapped
 *       to a 1-based STVN {@code :Union} branch index ({@code #1}, {@code #2}, and so on).</li>
 * </ul>
 *
 * @since 1.0.0
 */
public final class SealedInterfaceMapper {

  private SealedInterfaceMapper() {}

  /**
   * Checks whether the given class represents a sealed interface.
   *
   * @param type the class to inspect
   * @return {@code true} if the type is a sealed interface; {@code false} otherwise
   * @throws NullPointerException if {@code type} is {@code null}
   */
  public static boolean isSealedInterface(Class<?> type) {
    Objects.requireNonNull(type, "type must not be null");
    return type.isInterface() && type.isSealed();
  }

  /**
   * Translates an instance of a permitted subclass of a sealed interface into its pure-IR
   * {@link StvnValue} representation using the provided {@link StvnSchema}.
   *
   * @param instance     the runtime instance to map
   * @param declaredType the declared sealed interface class
   * @param schema       the pure-IR schema governing the sum type
   * @return an {@link Optional} containing the mapped pure-IR {@link StvnValue}
   */
  public static Optional<StvnValue> toValue(Object instance, Class<?> declaredType, StvnSchema schema) {
    Objects.requireNonNull(instance, "instance must not be null");
    Objects.requireNonNull(declaredType, "declaredType must not be null");
    Objects.requireNonNull(schema, "schema must not be null");
    return toValue(instance, declaredType, Optional.of(schema));
  }

  /**
   * Translates an instance of a permitted subclass of a sealed interface into its pure-IR
   * {@link StvnValue} representation using an optional {@link StvnSchema}.
   *
   * @param instance     the runtime instance to map
   * @param declaredType the declared sealed interface class
   * @param schemaOpt    optional pure-IR schema governing the sum type
   * @return an {@link Optional} containing the mapped pure-IR {@link StvnValue}
   */
  public static Optional<StvnValue> toValue(Object instance, Class<?> declaredType, Optional<StvnSchema> schemaOpt) {
    Objects.requireNonNull(instance, "instance must not be null");
    Objects.requireNonNull(declaredType, "declaredType must not be null");
    Objects.requireNonNull(schemaOpt, "schemaOpt must not be null");

    StvnSchema schema = schemaOpt.orElseThrow(
        () -> new MalformedPayloadException("Cannot map sealed interface without a schema: " + declaredType.getName())
    );

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
          throw new MalformedPayloadException(
              "Instance class " + runtimeClass.getName() + " is not a permitted subclass of " + declaredType.getName()
          );
        }

        Optional<StvnSchema> innerSchemaOpt = Optional.empty();
        if (schema instanceof StvnSchema.EitherSchema es) {
          innerSchemaOpt = Optional.of(isRight ? es.rightSchema() : es.leftSchema());
        }

        EitherBranch branch = isRight ? EitherBranch.RIGHT : EitherBranch.LEFT;
        return StvnMapper.toValue(instance, innerSchemaOpt)
            .map(innerValue -> new StvnEither(schema, branch, innerValue));
      }
    }

    // 2. Fall back to N-way Union (1-based branch index in pure IR)
    Class<?>[] sortedPermitted = permittedClasses.clone();
    Arrays.sort(sortedPermitted, Comparator.comparing(Class::getName));

    int zeroBasedIndex = -1;
    for (int i = 0; i < sortedPermitted.length; i++) {
      if (sortedPermitted[i].equals(runtimeClass)) {
        zeroBasedIndex = i;
        break;
      }
    }

    if (zeroBasedIndex == -1) {
      throw new MalformedPayloadException(
          "Instance class " + runtimeClass.getName() + " is not a permitted subclass of " + declaredType.getName()
      );
    }

    Optional<StvnSchema> innerSchemaOpt = Optional.empty();
    if (schema instanceof StvnSchema.UnionSchema us && zeroBasedIndex < us.branchSchemas().size()) {
      innerSchemaOpt = Optional.of(us.branchSchemas().get(zeroBasedIndex));
    }

    BigInteger oneBasedBranchIndex = BigInteger.valueOf(zeroBasedIndex + 1L);
    return StvnMapper.toValue(instance, innerSchemaOpt)
        .map(innerValue -> new StvnUnion(schema, oneBasedBranchIndex, innerValue));
  }

  /**
   * Instantiates a permitted subclass of a sealed interface from its pure-IR {@link StvnValue} node.
   *
   * @param <T>         the target sealed interface type
   * @param irValue     the pure-IR sum-type value node ({@link StvnEither} or {@link StvnUnion})
   * @param targetClass the target sealed interface class
   * @param schema      the pure-IR schema governing the sum type
   * @return an {@link Optional} containing the deserialized instance
   */
  public static <T> Optional<T> fromValue(StvnValue irValue, Class<T> targetClass, StvnSchema schema) {
    Objects.requireNonNull(irValue, "irValue must not be null");
    Objects.requireNonNull(targetClass, "targetClass must not be null");
    Objects.requireNonNull(schema, "schema must not be null");
    return fromValue(irValue, targetClass, Optional.of(schema));
  }

  /**
   * Instantiates a permitted subclass of a sealed interface from its pure-IR {@link StvnValue} node
   * using an optional {@link StvnSchema}.
   *
   * @param <T>         the target sealed interface type
   * @param irValue     the pure-IR sum-type value node ({@link StvnEither} or {@link StvnUnion})
   * @param targetClass the target sealed interface class
   * @param schemaOpt   optional pure-IR schema governing the sum type
   * @return an {@link Optional} containing the deserialized instance
   */
  public static <T> Optional<T> fromValue(StvnValue irValue, Class<T> targetClass, Optional<StvnSchema> schemaOpt) {
    Objects.requireNonNull(irValue, "irValue must not be null");
    Objects.requireNonNull(targetClass, "targetClass must not be null");
    Objects.requireNonNull(schemaOpt, "schemaOpt must not be null");

    StvnSchema schema = schemaOpt.orElseThrow(
        () -> new MalformedPayloadException("Cannot map sealed interface without a schema: " + targetClass.getName())
    );

    Class<?>[] permittedClasses = targetClass.getPermittedSubclasses();
    if (permittedClasses == null || permittedClasses.length == 0) {
      throw new MalformedPayloadException("Sealed interface " + targetClass.getName() + " has no permitted subclasses");
    }

    if (irValue instanceof StvnEither eitherNode) {
      if (permittedClasses.length != 2) {
        throw new MalformedPayloadException(
            "Expected a 2-way sealed interface matching Either structure, got: " + targetClass.getName()
        );
      }
      Class<?> p0 = permittedClasses[0];
      Class<?> p1 = permittedClasses[1];
      boolean p0Left = p0.getSimpleName().equalsIgnoreCase("Left");
      boolean p0Right = p0.getSimpleName().equalsIgnoreCase("Right");
      boolean p1Left = p1.getSimpleName().equalsIgnoreCase("Left");
      boolean p1Right = p1.getSimpleName().equalsIgnoreCase("Right");

      if (!((p0Left && p1Right) || (p0Right && p1Left))) {
        throw new MalformedPayloadException(
            "Sealed interface " + targetClass.getName()
                + " permitted subclass names do not match Either convention (Left/Right)"
        );
      }

      Class<?> leftClass = p0Left ? p0 : p1;
      Class<?> rightClass = p0Right ? p0 : p1;
      boolean isRight = eitherNode.branch() == EitherBranch.RIGHT;
      Class<?> targetSubclass = isRight ? rightClass : leftClass;

      Optional<StvnSchema> innerSchemaOpt = Optional.empty();
      if (schema instanceof StvnSchema.EitherSchema es) {
        innerSchemaOpt = Optional.of(isRight ? es.rightSchema() : es.leftSchema());
      }

      return StvnMapper.fromValue(eitherNode.value(), targetSubclass, innerSchemaOpt)
          .map(targetClass::cast);
    }

    if (irValue instanceof StvnUnion unionNode) {
      Class<?>[] sortedPermitted = permittedClasses.clone();
      Arrays.sort(sortedPermitted, Comparator.comparing(Class::getName));

      BigInteger zeroBased = unionNode.oneBasedBranchIndex().subtract(BigInteger.ONE);
      if (zeroBased.signum() < 0 || zeroBased.compareTo(BigInteger.valueOf(sortedPermitted.length)) >= 0) {
        throw new MalformedPayloadException(
            "Union branch index #" + unionNode.oneBasedBranchIndex() + " out of bounds for " + targetClass.getName()
        );
      }

      int idx = zeroBased.intValueExact();
      Class<?> targetSubclass = sortedPermitted[idx];

      Optional<StvnSchema> innerSchemaOpt = Optional.empty();
      if (schema instanceof StvnSchema.UnionSchema us && idx < us.branchSchemas().size()) {
        innerSchemaOpt = Optional.of(us.branchSchemas().get(idx));
      }

      return StvnMapper.fromValue(unionNode.value(), targetSubclass, innerSchemaOpt)
          .map(targetClass::cast);
    }

    throw new MalformedPayloadException(
        "Unsupported STVN value type for sealed interface " + targetClass.getName()
            + ": " + irValue.getClass().getSimpleName()
    );
  }
}
