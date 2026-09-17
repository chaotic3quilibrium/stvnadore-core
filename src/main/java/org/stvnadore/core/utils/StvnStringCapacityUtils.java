package org.stvnadore.core.utils;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.validation.MalformedSchemaException;

import java.math.BigInteger;
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
   * @param typeName the type identifier to inspect (e.g. {@code :String}, {@code :String64}, {@code :StringFixed16})
   * @return {@code true} if the type identifier belongs to the STVN string taxonomy, {@code false} otherwise
   */
  public static boolean isNominalStringType(@Nullable String typeName) {
    if (typeName == null || !typeName.startsWith(":String")) {
      return false;
    }
    return typeName.equals(":String")
        || typeName.equals(":StringNonEmpty")
        || typeName.startsWith(":StringFixed")
        || typeName.startsWith(":StringNonEmpty")
        || (!typeName.startsWith(":StringFixed") && !typeName.startsWith(":StringNonEmpty"));
  }

  /**
   * Extracts the numeric capacity suffix from a nominal string type token.
   * <p>
   * Explicit capacity suffixes support arbitrary sizes up to signed 32-bit integer limits
   * ({@code 1 <= N <= 2,147,483,647}). Non-positive values, leading zeros, signs, and integer
   * overflows trigger a {@link MalformedSchemaException}.
   * </p>
   *
   * @param typeName the type token to inspect (e.g. {@code :String4096}, {@code :StringFixed16}, {@code :String33554432})
   * @return an {@link OptionalInt} containing the extracted capacity dimension, or empty if unbounded
   * @throws MalformedSchemaException if the numeric suffix contains leading zeros, sign symbols,
   *                                  overflows signed 32-bit integer limits, or is non-positive
   */
  public static OptionalInt parseCapacitySuffix(@Nullable String typeName) {
    if (typeName == null) {
      return OptionalInt.empty();
    }
    String suffix = null;
    if (typeName.startsWith(":StringFixed") && !typeName.equals(":StringFixed")) {
      suffix = typeName.substring(12);
    } else if (typeName.startsWith(":StringNonEmpty") && !typeName.equals(":StringNonEmpty")) {
      suffix = typeName.substring(15);
    } else if (typeName.startsWith(":String") && !typeName.startsWith(":StringFixed")
        && !typeName.startsWith(":StringNonEmpty") && !typeName.equals(":String")) {
      suffix = typeName.substring(7);
    }

    if (suffix == null || suffix.isEmpty()) {
      return OptionalInt.empty();
    }

    if (suffix.length() > 1 && suffix.startsWith("0")) {
      throw new MalformedSchemaException("Constraint violation: Leading zeros are forbidden in type suffix dimensions: " + typeName);
    }
    if (suffix.contains("-") || suffix.contains("+")) {
      throw new MalformedSchemaException("Constraint violation: Type suffix dimensions cannot contain negative symbols or sign specifiers: " + typeName);
    }

    try {
      var big = new BigInteger(suffix);
      if (big.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
        throw new MalformedSchemaException("Constraint violation: Type suffix dimension overflows signed 32-bit integer limit: " + typeName);
      }
      int parsed = big.intValue();
      if (parsed < 1) {
        throw new MalformedSchemaException("Constraint violation: Type suffix dimensions must be strictly positive (N >= 1): " + typeName);
      }
      return OptionalInt.of(parsed);
    } catch (NumberFormatException e) {
      throw new MalformedSchemaException("Constraint violation: Malformed numeric type suffix format: " + typeName, e);
    }
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
