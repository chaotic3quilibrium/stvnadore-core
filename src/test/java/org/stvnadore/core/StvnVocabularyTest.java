package org.stvnadore.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification unit tests for {@link StvnVocabulary}.
 */
class StvnVocabularyTest {

  @Test
  @DisplayName("TC-VOCAB-01: Assert all 6 base scalar type constants match grammar tokens")
  void testBaseScalarTypeConstants() {
    assertEquals(":Boolean", StvnVocabulary.TYPE_BOOLEAN);
    assertEquals(":Int", StvnVocabulary.TYPE_INT);
    assertEquals(":Float", StvnVocabulary.TYPE_FLOAT);
    assertEquals(":String", StvnVocabulary.TYPE_STRING);
    assertEquals(":TimeEpoch", StvnVocabulary.TYPE_TIME_EPOCH);
    assertEquals(":DateTime", StvnVocabulary.TYPE_DATE_TIME);
  }

  @Test
  @DisplayName("TC-VOCAB-02: Assert all collection and composite type constants match grammar tokens")
  void testCompositeTypeConstants() {
    assertEquals(":Seq", StvnVocabulary.TYPE_SEQ);
    assertEquals(":Set", StvnVocabulary.TYPE_SET);
    assertEquals(":Map", StvnVocabulary.TYPE_MAP);
    assertEquals(":Tuple", StvnVocabulary.TYPE_TUPLE);
    assertEquals(":Option", StvnVocabulary.TYPE_OPTION);
    assertEquals(":Either", StvnVocabulary.TYPE_EITHER);
    assertEquals(":Union", StvnVocabulary.TYPE_UNION);
    assertEquals(":Enum", StvnVocabulary.TYPE_ENUM);
    assertEquals(":MapEntry", StvnVocabulary.TYPE_MAP_ENTRY);
  }

  @Test
  @DisplayName("TC-VOCAB-03: Assert all 7 tiers of facet keywords and bare facet names match grammar tokens")
  void testFacetKeywordsAndNames() {
    // Tier 1: Flags & Modes
    assertEquals("#unsigned", StvnVocabulary.FACET_KW_UNSIGNED);
    assertEquals("unsigned", StvnVocabulary.FACET_NAME_UNSIGNED);
    assertEquals("#exact", StvnVocabulary.FACET_KW_EXACT);
    assertEquals("exact", StvnVocabulary.FACET_NAME_EXACT);
    assertEquals("#invertible", StvnVocabulary.FACET_KW_INVERTIBLE);
    assertEquals("invertible", StvnVocabulary.FACET_NAME_INVERTIBLE);
    assertEquals("#preserveIndent", StvnVocabulary.FACET_KW_PRESERVE_INDENT);
    assertEquals("preserveIndent", StvnVocabulary.FACET_NAME_PRESERVE_INDENT);
    assertEquals("#offset", StvnVocabulary.FACET_KW_OFFSET);
    assertEquals("offset", StvnVocabulary.FACET_NAME_OFFSET);
    assertEquals("#zoned", StvnVocabulary.FACET_KW_ZONED);
    assertEquals("zoned", StvnVocabulary.FACET_NAME_ZONED);
    assertEquals("#audited", StvnVocabulary.FACET_KW_AUDITED);
    assertEquals("audited", StvnVocabulary.FACET_NAME_AUDITED);
    assertEquals("#equatable", StvnVocabulary.FACET_KW_EQUATABLE);
    assertEquals("equatable", StvnVocabulary.FACET_NAME_EQUATABLE);
    assertEquals("#comparable", StvnVocabulary.FACET_KW_COMPARABLE);
    assertEquals("comparable", StvnVocabulary.FACET_NAME_COMPARABLE);

    // Tier 2: Temporal Scales
    assertEquals("#s", StvnVocabulary.FACET_KW_SCALE_S);
    assertEquals("#ms", StvnVocabulary.FACET_KW_SCALE_MS);
    assertEquals("#us", StvnVocabulary.FACET_KW_SCALE_US);
    assertEquals("#ns", StvnVocabulary.FACET_KW_SCALE_NS);
    assertEquals("s", StvnVocabulary.SCALE_S);
    assertEquals("ms", StvnVocabulary.SCALE_MS);
    assertEquals("us", StvnVocabulary.SCALE_US);
    assertEquals("ns", StvnVocabulary.SCALE_NS);
    assertEquals("scale", StvnVocabulary.FACET_NAME_SCALE);

    // Tier 3: Dimensions & Capacity
    assertEquals("#size", StvnVocabulary.FACET_KW_SIZE);
    assertEquals("size", StvnVocabulary.FACET_NAME_SIZE);
    assertEquals("#minSize", StvnVocabulary.FACET_KW_MIN_SIZE);
    assertEquals("minSize", StvnVocabulary.FACET_NAME_MIN_SIZE);
    assertEquals("#maxSize", StvnVocabulary.FACET_KW_MAX_SIZE);
    assertEquals("maxSize", StvnVocabulary.FACET_NAME_MAX_SIZE);

    // Tier 4: Value Intervals
    assertEquals("#minIncl", StvnVocabulary.FACET_KW_MIN_INCL);
    assertEquals("minIncl", StvnVocabulary.FACET_NAME_MIN_INCL);
    assertEquals("#minExcl", StvnVocabulary.FACET_KW_MIN_EXCL);
    assertEquals("minExcl", StvnVocabulary.FACET_NAME_MIN_EXCL);
    assertEquals("#maxExcl", StvnVocabulary.FACET_KW_MAX_EXCL);
    assertEquals("maxExcl", StvnVocabulary.FACET_NAME_MAX_EXCL);
    assertEquals("#maxIncl", StvnVocabulary.FACET_KW_MAX_INCL);
    assertEquals("maxIncl", StvnVocabulary.FACET_NAME_MAX_INCL);

    // Tier 5: Validation Patterns
    assertEquals("#regex", StvnVocabulary.FACET_KW_REGEX);
    assertEquals("regex", StvnVocabulary.FACET_NAME_REGEX);

    // Tier 6: Domain Subsets
    assertEquals("#filterIncl", StvnVocabulary.FACET_KW_FILTER_INCL);
    assertEquals("filterIncl", StvnVocabulary.FACET_NAME_FILTER_INCL);
    assertEquals("#filterExcl", StvnVocabulary.FACET_KW_FILTER_EXCL);
    assertEquals("filterExcl", StvnVocabulary.FACET_NAME_FILTER_EXCL);

    // Tier 7: Directives
    assertEquals("#strip", StvnVocabulary.FACET_KW_STRIP);
    assertEquals("strip", StvnVocabulary.FACET_NAME_STRIP);
  }

  @Test
  @DisplayName("TC-VOCAB-04: Assert all structural keywords and sum/bool value tokens match grammar tokens")
  void testStructuralKeywordsAndValueTokens() {
    assertEquals(":defs", StvnVocabulary.KEYWORD_DEFS);
    assertEquals(":type", StvnVocabulary.KEYWORD_TYPE);
    assertEquals(":body", StvnVocabulary.KEYWORD_BODY);
    assertEquals(":include", StvnVocabulary.KEYWORD_INCLUDE);
    assertEquals(":package", StvnVocabulary.KEYWORD_PACKAGE);
    assertEquals(":use", StvnVocabulary.KEYWORD_USE);

    assertEquals("#TRUE", StvnVocabulary.VAL_TRUE);
    assertEquals("#FALSE", StvnVocabulary.VAL_FALSE);
    assertEquals("#T", StvnVocabulary.VAL_TRUE_SHORT);
    assertEquals("#F", StvnVocabulary.VAL_FALSE_SHORT);

    assertEquals("#Some", StvnVocabulary.VAL_SOME);
    assertEquals("#None", StvnVocabulary.VAL_NONE);
    assertEquals("#S", StvnVocabulary.VAL_SOME_SHORT);
    assertEquals("#N", StvnVocabulary.VAL_NONE_SHORT);

    assertEquals("#Left", StvnVocabulary.VAL_LEFT);
    assertEquals("#Right", StvnVocabulary.VAL_RIGHT);
    assertEquals("#L", StvnVocabulary.VAL_LEFT_SHORT);
    assertEquals("#R", StvnVocabulary.VAL_RIGHT_SHORT);
  }

  @Test
  @DisplayName("TC-VOCAB-05: Verify non-instantiability of StvnVocabulary via reflection")
  void testNonInstantiable() throws NoSuchMethodException {
    Constructor<StvnVocabulary> constructor = StvnVocabulary.class.getDeclaredConstructor();
    assertTrue(Modifier.isPrivate(constructor.getModifiers()), "Constructor must be private");
    constructor.setAccessible(true);
    assertDoesNotThrow(() -> {
      StvnVocabulary instance = constructor.newInstance();
      assertNotNull(instance);
    });
  }
}
