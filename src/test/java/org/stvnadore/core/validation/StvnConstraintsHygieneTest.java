package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ir.StvnSchema;

import java.lang.reflect.RecordComponent;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification hygiene tests for pure-IR {@link StvnSchema} constraint record components
 * and nominal bound consolidation.
 */
class StvnConstraintsHygieneTest {

  @Test
  @DisplayName("TC-CONS-01: Assert DateTimeSchema and TimeEpochSchema eliminate phantom minExcl/maxIncl bounds")
  void testTemporalSchemaRecordComponentsPurgePhantomBounds() {
    RecordComponent[] dtComponents = StvnSchema.DateTimeSchema.class.getRecordComponents();
    assertNotNull(dtComponents);
    List<String> dtNames = Arrays.stream(dtComponents)
        .map(RecordComponent::getName)
        .toList();

    assertFalse(dtNames.contains("dateMinExcl"), "dateMinExcl must be eliminated");
    assertFalse(dtNames.contains("dateMaxIncl"), "dateMaxIncl must be eliminated");
    assertFalse(dtNames.contains("explicitMinExclIso"), "explicitMinExclIso must not exist");
    assertFalse(dtNames.contains("explicitMaxInclIso"), "explicitMaxInclIso must not exist");
    assertTrue(dtNames.contains("explicitMinInclIso"), "explicitMinInclIso must remain present");
    assertTrue(dtNames.contains("explicitMaxExclIso"), "explicitMaxExclIso must remain present");

    RecordComponent[] teComponents = StvnSchema.TimeEpochSchema.class.getRecordComponents();
    assertNotNull(teComponents);
    List<String> teNames = Arrays.stream(teComponents)
        .map(RecordComponent::getName)
        .toList();

    assertFalse(teNames.contains("explicitMinExcl"), "explicitMinExcl must not exist");
    assertFalse(teNames.contains("explicitMaxIncl"), "explicitMaxIncl must not exist");
    assertTrue(teNames.contains("explicitMinIncl"), "explicitMinIncl must remain present");
    assertTrue(teNames.contains("explicitMaxExcl"), "explicitMaxExcl must remain present");
  }

  @Test
  @DisplayName("TC-CONS-02: Assert pure-IR lowering populates explicit half-open range constraints accurately")
  void testLoweredRangeConstraints() {
    String source = """
        {
          :defs {
            :BoundedInt { #minIncl 1 #maxExcl 100 } :Int
            :BoundedDt { #offset #minIncl "2020-01-01T00:00:00Z" #maxExcl "2030-01-01T00:00:00Z" } :DateTime
          }
          :type :Tuple( :BoundedInt :BoundedDt )
          :body ( 10 "2026-03-15T08:00:00Z" )
        }
        """;
    var doc = StvnCompiler.compile(source).document().orElseThrow();
    var intSchema = (StvnSchema.IntSchema) doc.defs().findType(":BoundedInt").orElseThrow();
    assertEquals(Optional.of(BigInteger.ONE), intSchema.explicitMinIncl());
    assertEquals(Optional.of(BigInteger.valueOf(100)), intSchema.explicitMaxExcl());

    var dtSchema = (StvnSchema.DateTimeSchema) doc.defs().findType(":BoundedDt").orElseThrow();
    assertEquals(Optional.of("2020-01-01T00:00:00Z"), dtSchema.explicitMinInclIso());
    assertEquals(Optional.of("2030-01-01T00:00:00Z"), dtSchema.explicitMaxExclIso());
  }

  @Test
  @DisplayName("TC-CONS-03: Assert nominal inheritance consolidates Tier-1 and Tier-3 constraints")
  void testNominalConstraintConsolidation() {
    String source = """
        {
          :defs {
            :U16Base { #unsigned #size 16 #minIncl 5 } :Int
            :U16Child { #maxExcl 51 } :U16Base
          }
          :type :U16Child
          :body 25
        }
        """;
    var doc = StvnCompiler.compile(source).document().orElseThrow();
    var merged = (StvnSchema.IntSchema) doc.defs().findType(":U16Child").orElseThrow();

    assertEquals(Optional.of(BigInteger.valueOf(5)), merged.explicitMinIncl());
    assertEquals(Optional.of(BigInteger.valueOf(51)), merged.explicitMaxExcl());
    assertEquals(BigInteger.valueOf(16), merged.bitWidth());
    assertTrue(merged.unsigned());
  }

  @Test
  @DisplayName("TC-CONS-04: Verify DateTimeSchema toString() stability does not mention phantom fields")
  void testToStringStability() {
    String source = """
        {
          :defs {
            :SimpleDt { #offset } :DateTime
          }
          :type :SimpleDt
          :body "2026-03-15T08:00:00Z"
        }
        """;
    var dtSchema = StvnCompiler.compile(source).document().orElseThrow().defs().findType(":SimpleDt").orElseThrow();
    String str = dtSchema.toString();
    assertNotNull(str);
    assertFalse(str.contains("dateMinExcl"));
    assertFalse(str.contains("dateMaxIncl"));
  }
}
