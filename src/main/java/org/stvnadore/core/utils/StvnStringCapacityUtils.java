package org.stvnadore.core.utils;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.validation.MalformedSchemaException;

import java.util.OptionalInt;

/**
 * Architectural string capacity governance constants and nominal type suffix parsing utilities.
 * <p>
 * STVN establishes deterministic string length boundaries across unbounded, max-bounded, and exact fixed-length
 * categories. This utility centralizes the default allocation limit for unadorned {@code :String} instances
 * (16 MiB) and provides deterministic parsing for nominal string suffixes (such as extracting capacity {@code 4096}
 * from {@code :String4096} or arbitrary dimensions up to {@link Integer#MAX_VALUE}).
 * </p>
 *
 * @since 1.3.0
 */
@NullMarked
public final class StvnStringCapacityUtils {

  /**
   * Default allocation capacity limit in characters for unadorned {@code :String} instances (16,777,216 characters / 16 MiB).
   */
  public static final int DEFAULT_UNBOUNDED_STRING_CAPACITY = 16_777_216;

  /**
   * Default inspection threshold in characters for schema and diagnostic analyzers (4,096 characters).
   */
  public static final int DEFAULT_INSPECTION_THRESHOLD = 4096;

  /**
   * Default indentation width in spaces for canonical text formatting (2 spaces).
   */
  public static final int DEFAULT_INDENT_WIDTH = 2;

  private StvnStringCapacityUtils() {
    // Non-instantiable utility class
  }

  /**
   * Inspects whether the specified type identifier represents an STVN nominal string type.
   *
   * @param typeName the type identifier to inspect (e.g. {@code :String})
   * @return {@code true} if the type identifier belongs to the STVN string taxonomy, {@code false} otherwise
   */
  public static boolean isNominalStringType(@Nullable String typeName) {
    if (typeName == null) {
      return false;
    }
    return typeName.equals(StvnVocabulary.TYPE_STRING)
        || typeName.equals(":org/stvnadore/prelude/Uuid")
        || typeName.equals(":org/stvnadore/prelude/Ulid")
        || typeName.equals(":org/stvnadore/prelude/Sha256")
        || typeName.equals(":org/stvnadore/prelude/SemVer")
        || typeName.equals(":org/stvnadore/prelude/Email")
        || typeName.equals(":org/stvnadore/prelude/IPv4");
  }

  /**
   * Extracts the numeric capacity suffix from a nominal string type token.
   * <p>
   * In STVN 2.0.0, string capacity is governed exclusively by metadata facets ({@code #minSize}, {@code #maxSize}).
   * Nominal suffix dimensions (e.g. {@code :String4096}, {@code :StringFixed16}) are prohibited, and this method
   * always returns {@link OptionalInt#empty()}.
   * </p>
   *
   * @param typeName the type token to inspect
   * @return {@link OptionalInt#empty()} in STVN 2.0.0
   */
  public static OptionalInt parseCapacitySuffix(@Nullable String typeName) {
    // Pure 2.0.0 semantics: String capacity is governed exclusively by metadata facets.
    // Type suffix dimensions (:String4096, :StringFixed16) are prohibited in 2.0.0.
    return OptionalInt.empty();
  }

  /**
   * Validates a parsed string capacity dimension.
   *
   * @param capacity the capacity dimension to validate
   * @param typeName the type identifier associated with the validation check
   * @return the validated capacity
   * @throws MalformedSchemaException if capacity is less than 1
   */
  public static int validateCapacity(int capacity, String typeName) {
    if (capacity < 1) {
      throw new MalformedSchemaException("Constraint violation: Type suffix dimensions must be strictly positive (N >= 1): " + typeName);
    }
    return capacity;
  }
}
