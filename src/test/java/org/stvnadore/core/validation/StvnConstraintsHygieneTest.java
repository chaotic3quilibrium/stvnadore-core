package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.validation.StvnTypeResolver.StvnConstraints;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification hygiene tests for {@link StvnConstraints}.
 */
class StvnConstraintsHygieneTest {

  @Test
  @DisplayName("TC-CONS-01: Assert StvnConstraints record component count is exactly 23")
  void testRecordComponentCount() {
    RecordComponent[] components = StvnConstraints.class.getRecordComponents();
    assertNotNull(components);
    assertEquals(23, components.length, "StvnConstraints must contain exactly 23 components after purging phantom date bounds");

    List<String> componentNames = Arrays.stream(components)
        .map(RecordComponent::getName)
        .toList();

    // Verify phantom bounds are eliminated
    assertFalse(componentNames.contains("dateMinExcl"), "dateMinExcl must be eliminated");
    assertFalse(componentNames.contains("dateMaxIncl"), "dateMaxIncl must be eliminated");

    // Verify legitimate temporal bounds remain
    assertTrue(componentNames.contains("dateMinIncl"), "dateMinIncl must remain present");
    assertTrue(componentNames.contains("dateMaxExcl"), "dateMaxExcl must remain present");
  }

  @Test
  @DisplayName("TC-CONS-02: Assert fluent builder range methods correctly populate constraints")
  void testFluentBuilders() {
    StvnConstraints c = StvnConstraints.empty()
        .withMinIncl(BigDecimal.ONE)
        .withMaxIncl(BigDecimal.TEN)
        .withMaxExcl(BigDecimal.valueOf(100));

    assertEquals(Optional.of(BigDecimal.ONE), c.minIncl());
    assertEquals(Optional.of(BigDecimal.TEN), c.maxIncl());
    assertEquals(Optional.of(BigDecimal.valueOf(100)), c.maxExcl());
    assertTrue(c.minExcl().isEmpty());
    assertTrue(c.dateMinIncl().isEmpty());
    assertTrue(c.dateMaxExcl().isEmpty());
  }

  @Test
  @DisplayName("TC-CONS-03: Assert merge() correctly consolidates constraints")
  void testMerge() {
    StvnConstraints c1 = StvnConstraints.empty()
        .withMinIncl(BigDecimal.valueOf(5))
        .withSize(16);

    StvnConstraints c2 = StvnConstraints.empty()
        .withMaxIncl(BigDecimal.valueOf(50))
        .withUnsigned(true);

    StvnConstraints merged = c1.merge(c2);

    assertEquals(Optional.of(BigDecimal.valueOf(5)), merged.minIncl());
    assertEquals(Optional.of(BigDecimal.valueOf(50)), merged.maxIncl());
    assertEquals(Optional.of(16), merged.size());
    assertTrue(merged.unsigned());
  }

  @Test
  @DisplayName("TC-CONS-04: Verify toString() stability does not mention phantom fields")
  void testToStringStability() {
    StvnConstraints c = StvnConstraints.empty();
    String str = c.toString();
    assertNotNull(str);
    assertFalse(str.contains("dateMinExcl"));
    assertFalse(str.contains("dateMaxIncl"));
  }
}
