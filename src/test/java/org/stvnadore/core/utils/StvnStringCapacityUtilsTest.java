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
  @DisplayName("isNominalStringType identifies pure 2.0.0 string types correctly")
  void testIsNominalStringType() {
    Assertions.assertTrue(StvnStringCapacityUtils.isNominalStringType(":String"));
    Assertions.assertTrue(StvnStringCapacityUtils.isNominalStringType(":org/stvnadore/prelude/Uuid"));
    Assertions.assertTrue(StvnStringCapacityUtils.isNominalStringType(":org/stvnadore/prelude/Ulid"));
    Assertions.assertTrue(StvnStringCapacityUtils.isNominalStringType(":org/stvnadore/prelude/Sha256"));
    Assertions.assertTrue(StvnStringCapacityUtils.isNominalStringType(":org/stvnadore/prelude/SemVer"));
    Assertions.assertTrue(StvnStringCapacityUtils.isNominalStringType(":org/stvnadore/prelude/Email"));
    Assertions.assertTrue(StvnStringCapacityUtils.isNominalStringType(":org/stvnadore/prelude/IPv4"));

    // 1.x compound string types are prohibited in 2.0.0
    Assertions.assertFalse(StvnStringCapacityUtils.isNominalStringType(":String64"));
    Assertions.assertFalse(StvnStringCapacityUtils.isNominalStringType(":String4096"));
    Assertions.assertFalse(StvnStringCapacityUtils.isNominalStringType(":String16777216"));
    Assertions.assertFalse(StvnStringCapacityUtils.isNominalStringType(":String33554432"));
    Assertions.assertFalse(StvnStringCapacityUtils.isNominalStringType(":StringNonEmpty"));
    Assertions.assertFalse(StvnStringCapacityUtils.isNominalStringType(":StringNonEmpty64"));
    Assertions.assertFalse(StvnStringCapacityUtils.isNominalStringType(":StringFixed16"));

    Assertions.assertFalse(StvnStringCapacityUtils.isNominalStringType(":Int"));
    Assertions.assertFalse(StvnStringCapacityUtils.isNominalStringType(":Boolean"));
    Assertions.assertFalse(StvnStringCapacityUtils.isNominalStringType(null));
  }

  @Test
  @DisplayName("parseCapacitySuffix returns empty under 2.0.0 pure metadata facet semantics")
  void testParseCapacitySuffix() {
    Assertions.assertEquals(OptionalInt.empty(), StvnStringCapacityUtils.parseCapacitySuffix(null));
    Assertions.assertEquals(OptionalInt.empty(), StvnStringCapacityUtils.parseCapacitySuffix(":String"));
    Assertions.assertEquals(OptionalInt.empty(), StvnStringCapacityUtils.parseCapacitySuffix(":String64"));
    Assertions.assertEquals(OptionalInt.empty(), StvnStringCapacityUtils.parseCapacitySuffix(":StringFixed16"));
    Assertions.assertEquals(OptionalInt.empty(), StvnStringCapacityUtils.parseCapacitySuffix(":StringNonEmpty"));
  }

  @Test
  @DisplayName("validateCapacity enforces strictly positive dimensions")
  void testValidateCapacity() {
    Assertions.assertEquals(16, StvnStringCapacityUtils.validateCapacity(16, ":String"));
    Assertions.assertThrows(MalformedSchemaException.class,
        () -> StvnStringCapacityUtils.validateCapacity(0, ":String"));
    Assertions.assertThrows(MalformedSchemaException.class,
        () -> StvnStringCapacityUtils.validateCapacity(-1, ":String"));
  }
}
