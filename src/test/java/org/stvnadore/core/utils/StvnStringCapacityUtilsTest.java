package org.stvnadore.core.utils;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.validation.MalformedSchemaException;

import java.util.OptionalInt;

/**
 * Verification tests for StvnStringCapacityUtils constants and suffix parsing logic.
 */
class StvnStringCapacityUtilsTest {

  @Test
  @DisplayName("Verify architectural constants")
  void testArchitecturalConstants() {
    Assertions.assertEquals(16_777_216, StvnStringCapacityUtils.DEFAULT_UNBOUNDED_STRING_CAPACITY);
    Assertions.assertEquals(4096, StvnStringCapacityUtils.DEFAULT_INSPECTION_THRESHOLD);
    Assertions.assertEquals(2, StvnStringCapacityUtils.DEFAULT_INDENT_WIDTH);
  }

  @Test
  @DisplayName("isNominalStringType identifies string types correctly")
  void testIsNominalStringType() {
    Assertions.assertTrue(StvnStringCapacityUtils.isNominalStringType(":String"));
    Assertions.assertTrue(StvnStringCapacityUtils.isNominalStringType(":String64"));
    Assertions.assertTrue(StvnStringCapacityUtils.isNominalStringType(":String4096"));
    Assertions.assertTrue(StvnStringCapacityUtils.isNominalStringType(":String16777216"));
    Assertions.assertTrue(StvnStringCapacityUtils.isNominalStringType(":String33554432"));
    Assertions.assertTrue(StvnStringCapacityUtils.isNominalStringType(":StringNonEmpty"));
    Assertions.assertTrue(StvnStringCapacityUtils.isNominalStringType(":StringNonEmpty64"));
    Assertions.assertTrue(StvnStringCapacityUtils.isNominalStringType(":StringFixed16"));

    Assertions.assertFalse(StvnStringCapacityUtils.isNominalStringType(":Int32"));
    Assertions.assertFalse(StvnStringCapacityUtils.isNominalStringType(":Boolean"));
    Assertions.assertFalse(StvnStringCapacityUtils.isNominalStringType(null));
  }

  @Test
  @DisplayName("parseCapacitySuffix extracts valid dimensions including sizes beyond 16 MiB")
  void testParseValidSuffixes() {
    Assertions.assertEquals(OptionalInt.empty(), StvnStringCapacityUtils.parseCapacitySuffix(":String"));
    Assertions.assertEquals(OptionalInt.empty(), StvnStringCapacityUtils.parseCapacitySuffix(":StringNonEmpty"));

    Assertions.assertEquals(OptionalInt.of(64), StvnStringCapacityUtils.parseCapacitySuffix(":String64"));
    Assertions.assertEquals(OptionalInt.of(4096), StvnStringCapacityUtils.parseCapacitySuffix(":String4096"));
    Assertions.assertEquals(OptionalInt.of(16_777_216), StvnStringCapacityUtils.parseCapacitySuffix(":String16777216"));
    Assertions.assertEquals(OptionalInt.of(33_554_432), StvnStringCapacityUtils.parseCapacitySuffix(":String33554432"));
    Assertions.assertEquals(OptionalInt.of(Integer.MAX_VALUE), StvnStringCapacityUtils.parseCapacitySuffix(":String2147483647"));
    Assertions.assertEquals(OptionalInt.of(16), StvnStringCapacityUtils.parseCapacitySuffix(":StringFixed16"));
    Assertions.assertEquals(OptionalInt.of(128), StvnStringCapacityUtils.parseCapacitySuffix(":StringNonEmpty128"));
  }

  @Test
  @DisplayName("parseCapacitySuffix rejects invalid, non-positive, and overflowing suffixes")
  void testParseInvalidSuffixes() {
    // Zero capacity rejected
    Assertions.assertThrows(MalformedSchemaException.class,
        () -> StvnStringCapacityUtils.parseCapacitySuffix(":String0"));

    // Leading zeros rejected
    Assertions.assertThrows(MalformedSchemaException.class,
        () -> StvnStringCapacityUtils.parseCapacitySuffix(":String064"));

    // Signed symbols rejected
    Assertions.assertThrows(MalformedSchemaException.class,
        () -> StvnStringCapacityUtils.parseCapacitySuffix(":String-64"));

    // Overflow beyond signed 32-bit integer limit rejected
    Assertions.assertThrows(MalformedSchemaException.class,
        () -> StvnStringCapacityUtils.parseCapacitySuffix(":String2147483648"));
  }
}
