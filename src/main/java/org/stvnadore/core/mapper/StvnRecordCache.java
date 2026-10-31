package org.stvnadore.core.mapper;

import org.jspecify.annotations.Nullable;
import org.stvnadore.core.annotations.StvnBits;
import org.stvnadore.core.annotations.StvnInt;
import org.stvnadore.core.annotations.StvnString;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Type;

/**
 * Internal reflection cache for introspecting Java record classes.
 * <p>
 * Caches structural metadata and {@link MethodHandle} accessors for native Java 21 record types
 * to accelerate pure-IR serialization and deserialization mapping.
 *
 * @since 1.0.0
 */
public final class StvnRecordCache {

  private StvnRecordCache() {
    throw new AssertionError("No StvnRecordCache instances allowed");
  }

  private static final ClassValue<RecordMetadata> CACHE = new ClassValue<>() {
    @Override
    protected RecordMetadata computeValue(Class<?> type) {
      if (!type.isRecord()) {
        throw new IllegalArgumentException("Type must be a native Java Record: " + type.getName());
      }
      return RecordMetadata.resolve(type);
    }
  };

  /**
   * Retrieves the cached record metadata for a given record class.
   *
   * @param type the Java record class to look up
   * @return the resolved record metadata containing component profiles and canonical constructor
   * @throws IllegalArgumentException if the class is not a record
   */
  public static RecordMetadata get(Class<?> type) {
    return CACHE.get(type);
  }

  /**
   * Introspected profile of a single Java record component.
   *
   * @param name             the name of the component
   * @param type             the raw class type of the component
   * @param genericType      the generic type of the component
   * @param accessorHandle   the cached method handle to read this component's value
   * @param stringAnnotation the {@link StvnString} constraint annotation, or {@code null} if absent
   * @param intAnnotation    the {@link StvnInt} constraint annotation, or {@code null} if absent
   * @param bitsAnnotation   the {@link StvnBits} constraint annotation, or {@code null} if absent
   */
  public record RecordComponentProfile(
      String name,
      Class<?> type,
      Type genericType,
      MethodHandle accessorHandle,
      @Nullable StvnString stringAnnotation,
      @Nullable StvnInt intAnnotation,
      @Nullable StvnBits bitsAnnotation
  ) {
  }

  /**
   * Introspected structural metadata of a Java record class.
   *
   * @param recordClass          the target record class
   * @param components           the profile details of each component
   * @param canonicalConstructor the cached method handle to invoke the record's canonical constructor
   */
  public record RecordMetadata(
      Class<?> recordClass,
      RecordComponentProfile[] components,
      MethodHandle canonicalConstructor
  ) {
    private static RecordMetadata resolve(Class<?> recordClass) {
      var components = recordClass.getRecordComponents();
      var profiles = new RecordComponentProfile[components.length];
      var paramTypes = new Class<?>[components.length];

      MethodHandles.Lookup lookup;
      try {
        lookup = MethodHandles.privateLookupIn(recordClass, MethodHandles.lookup());
      } catch (IllegalAccessException e) {
        lookup = MethodHandles.lookup();
      }

      for (int i = 0; i < components.length; i++) {
        var comp = components[i];
        paramTypes[i] = comp.getType();
        try {
          var accessor = comp.getAccessor();
          accessor.setAccessible(true);
          var accessorHandle = lookup.unreflect(accessor);

          profiles[i] = new RecordComponentProfile(
              comp.getName(),
              comp.getType(),
              comp.getGenericType(),
              accessorHandle,
              comp.getAnnotation(StvnString.class),
              comp.getAnnotation(StvnInt.class),
              comp.getAnnotation(StvnBits.class)
          );
        } catch (IllegalAccessException e) {
          throw new RuntimeException("Failed to unreflect record component accessor: " + comp.getName(), e);
        }
      }

      try {
        var ctor = recordClass.getDeclaredConstructor(paramTypes);
        ctor.setAccessible(true);
        var canonicalConstructor = lookup.unreflectConstructor(ctor);
        return new RecordMetadata(recordClass, profiles, canonicalConstructor);
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new RuntimeException("Failed to locate canonical constructor for " + recordClass.getName(), e);
      }
    }
  }
}
