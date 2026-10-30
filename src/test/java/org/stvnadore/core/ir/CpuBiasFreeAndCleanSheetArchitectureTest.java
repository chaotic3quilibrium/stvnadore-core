package org.stvnadore.core.ir;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity;
import org.stvnadore.core.StvnVocabulary;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Architectural and mathematical verification gate ({@code GATE-08}) for the pure STVN 2.0.0
 * Canonical Intermediate Representation ({@code org.stvnadore.core.ir}).
 * <p>
 * Enforces zero ANTLR/parser/legacy-AST coupling, 100% CPU-bias-free {@link BigInteger} and
 * {@link BigDecimal} expression-space types, zero STVN 1.x identifier remnants, closed sealed
 * {@link StvnSchema} and {@link StvnValue} algebras, and {@code O(1)} symbolic bit-width capacity
 * verification on astronomical integer schemas.
 *
 * @since 2.0.0
 */
@NullMarked
class CpuBiasFreeAndCleanSheetArchitectureTest {

  private static final Path IR_SOURCE_DIR = Path.of("src", "main", "java", "org", "stvnadore", "core", "ir");

  private static final List<Class<?>> TOP_LEVEL_IR_CLASSES = List.of(
      StvnDocumentKind.class,
      StvnDocumentMeta.class,
      StvnCatalogMeta.class,
      StvnDocument.class,
      StvnDefsCatalog.class,
      StvnSchema.class,
      StvnValue.class
  );

  private static final Set<Class<?>> FORBIDDEN_HARDWARE_NUMERIC_TYPES = Set.of(
      byte.class,
      short.class,
      int.class,
      long.class,
      float.class,
      double.class,
      Byte.class,
      Short.class,
      Integer.class,
      Long.class,
      Float.class,
      Double.class
  );

  private static final List<String> FORBIDDEN_1_X_SUBSTRINGS = List.of(
      "Product",
      "MapEntry",
      "nonEmpty",
      "Inclf",
      "strict",
      "deprecated"
  );

  @Test
  @DisplayName("TC-IR-ARCH-01: Verify zero ANTLR runtime, generated parser, or legacy AST coupling in org.stvnadore.core.ir")
  void testZeroParserAndLegacyAstCoupling() throws IOException {
    assertTrue(Files.isDirectory(IR_SOURCE_DIR), "Canonical IR source directory must exist: " + IR_SOURCE_DIR);

    List<Path> sourceFiles;
    try (var stream = Files.walk(IR_SOURCE_DIR)) {
      sourceFiles = stream.filter(p -> p.toString().endsWith(".java")).sorted().toList();
    }
    assertEquals(8, sourceFiles.size(), "Expected all 8 Canonical IR source files in org.stvnadore.core.ir");

    List<String> sourceViolations = new ArrayList<>();
    for (Path file : sourceFiles) {
      List<String> lines = Files.readAllLines(file);
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i);
        if (line.contains("org.antlr")
            || line.contains("org.stvnadore.core.parser")
            || line.contains("org.stvnadore.core.ast")) {
          sourceViolations.add(file + ":" + (i + 1) + ": " + line.trim());
        }
      }
    }
    assertTrue(
        sourceViolations.isEmpty(),
        "Canonical IR source files must not reference ANTLR, parser, or legacy AST packages:\n"
            + String.join("\n", sourceViolations)
    );

    Set<Class<?>> allIrTypes = collectAllIrTypes();
    assertTrue(allIrTypes.size() >= 40, "Expected at least 40 top-level and nested IR types, found: " + allIrTypes.size());

    List<String> reflectiveViolations = new ArrayList<>();
    for (Class<?> irType : allIrTypes) {
      inspectTypeForForbiddenPackages(irType.getGenericSuperclass(), irType.getName() + " superclass", reflectiveViolations);
      for (Type iface : irType.getGenericInterfaces()) {
        inspectTypeForForbiddenPackages(iface, irType.getName() + " interface", reflectiveViolations);
      }
      if (irType.isRecord()) {
        for (RecordComponent rc : irType.getRecordComponents()) {
          inspectTypeForForbiddenPackages(
              rc.getGenericType(),
              irType.getName() + "." + rc.getName(),
              reflectiveViolations
          );
        }
      }
      for (Field field : irType.getDeclaredFields()) {
        if (!field.isSynthetic()) {
          inspectTypeForForbiddenPackages(
              field.getGenericType(),
              irType.getName() + "#" + field.getName(),
              reflectiveViolations
          );
        }
      }
      for (Method method : irType.getDeclaredMethods()) {
        if (!method.isSynthetic() && !method.isBridge()) {
          inspectTypeForForbiddenPackages(
              method.getGenericReturnType(),
              irType.getName() + "::" + method.getName() + " return",
              reflectiveViolations
          );
          for (Type paramType : method.getGenericParameterTypes()) {
            inspectTypeForForbiddenPackages(
                paramType,
                irType.getName() + "::" + method.getName() + " param",
                reflectiveViolations
            );
          }
        }
      }
    }

    assertTrue(
        reflectiveViolations.isEmpty(),
        "Reflective inspection detected forbidden parser/AST coupling in org.stvnadore.core.ir:\n"
            + String.join("\n", reflectiveViolations)
    );
  }

  @Test
  @DisplayName("TC-IR-ARCH-02: Verify CPU-Bias-Free Expression Space (BigInteger & BigDecimal exclusively; zero hardware numeric primitives)")
  void testCpuBiasFreeExpressionSpace() throws NoSuchMethodException {
    Set<Class<?>> allIrTypes = collectAllIrTypes();
    List<String> numericViolations = new ArrayList<>();

    for (Class<?> irType : allIrTypes) {
      if (irType.isRecord()) {
        for (RecordComponent rc : irType.getRecordComponents()) {
          inspectTypeForHardwareNumerics(
              rc.getGenericType(),
              irType.getSimpleName() + "." + rc.getName(),
              numericViolations
          );
        }
      }

      for (Field field : irType.getDeclaredFields()) {
        if (!field.isSynthetic()) {
          inspectTypeForHardwareNumerics(
              field.getGenericType(),
              irType.getSimpleName() + "#" + field.getName(),
              numericViolations
          );
        }
      }

      for (Constructor<?> ctor : irType.getDeclaredConstructors()) {
        if (ctor.isSynthetic()) {
          continue;
        }
        Type[] paramTypes = ctor.getGenericParameterTypes();
        int startIndex = irType.isEnum() ? 2 : 0;
        for (int i = startIndex; i < paramTypes.length; i++) {
          inspectTypeForHardwareNumerics(
              paramTypes[i],
              irType.getSimpleName() + "<init>[" + i + "]",
              numericViolations
          );
        }
      }

      for (Method method : irType.getDeclaredMethods()) {
        if (method.isSynthetic() || method.isBridge()) {
          continue;
        }
        if (method.getName().equals("hashCode") && method.getParameterCount() == 0) {
          continue;
        }
        inspectTypeForHardwareNumerics(
            method.getGenericReturnType(),
            irType.getSimpleName() + "::" + method.getName() + "()",
            numericViolations
        );
        for (Type paramType : method.getGenericParameterTypes()) {
          inspectTypeForHardwareNumerics(
              paramType,
              irType.getSimpleName() + "::" + method.getName() + " param",
              numericViolations
          );
        }
      }
    }

    assertTrue(
        numericViolations.isEmpty(),
        "Hardware-sized numeric primitives/wrappers are prohibited in org.stvnadore.core.ir:\n"
            + String.join("\n", numericViolations)
    );

    assertEquals(BigInteger.class, StvnSchema.IntSchema.class.getMethod("bitWidth").getReturnType());
    assertEquals(BigInteger.class, StvnSchema.FloatSchema.class.getMethod("exactMaxDigits").getReturnType());
    assertEquals(BigInteger.class, StvnSchema.StringSchema.class.getMethod("minSize").getReturnType());
    assertEquals(BigInteger.class, StvnSchema.StringSchema.class.getMethod("maxSize").getReturnType());
    assertEquals(BigInteger.class, StvnSchema.SeqSchema.class.getMethod("minSize").getReturnType());
    assertEquals(BigInteger.class, StvnSchema.SeqSchema.class.getMethod("maxSize").getReturnType());
    assertEquals(BigInteger.class, StvnSchema.SetSchema.class.getMethod("minSize").getReturnType());
    assertEquals(BigInteger.class, StvnSchema.SetSchema.class.getMethod("maxSize").getReturnType());
    assertEquals(BigInteger.class, StvnSchema.MapSchema.class.getMethod("minSize").getReturnType());
    assertEquals(BigInteger.class, StvnSchema.MapSchema.class.getMethod("maxSize").getReturnType());
    assertEquals(BigInteger.class, StvnSchema.TemporalScale.class.getMethod("bitWidth").getReturnType());
    assertEquals(BigInteger.class, StvnValue.StvnInt.class.getMethod("value").getReturnType());
    assertEquals(BigDecimal.class, StvnValue.StvnFloat.class.getMethod("decimalValue").getReturnType());
    assertEquals(BigInteger.class, StvnValue.StvnTimeEpoch.class.getMethod("epochTicks").getReturnType());
    assertEquals(BigInteger.class, StvnValue.StvnEnum.class.getMethod("rootOrdinal").getReturnType());
    assertEquals(BigInteger.class, StvnValue.StvnUnion.class.getMethod("oneBasedBranchIndex").getReturnType());
  }

  @Test
  @DisplayName("TC-IR-ARCH-03: Verify zero prohibited STVN 1.x identifiers across org.stvnadore.core.ir sources and symbols")
  void testZeroOneDotXIdentifierRemnants() throws IOException {
    List<String> violations = new ArrayList<>();

    try (var stream = Files.walk(IR_SOURCE_DIR)) {
      List<Path> files = stream.filter(p -> p.toString().endsWith(".java")).sorted().toList();
      for (Path file : files) {
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
          String line = lines.get(i);
          String lowerLine = line.toLowerCase(Locale.ROOT);
          for (String forbidden : FORBIDDEN_1_X_SUBSTRINGS) {
            if (lowerLine.contains(forbidden.toLowerCase(Locale.ROOT))) {
              violations.add(file + ":" + (i + 1) + " contains '" + forbidden + "': " + line.trim());
            }
          }
        }
      }
    }

    for (Class<?> irType : collectAllIrTypes()) {
      checkIdentifierForOneDotX(irType.getSimpleName(), irType.getName(), violations);
      for (Field field : irType.getDeclaredFields()) {
        if (!field.isSynthetic()) {
          checkIdentifierForOneDotX(field.getName(), irType.getName() + "#" + field.getName(), violations);
        }
      }
      for (Method method : irType.getDeclaredMethods()) {
        if (!method.isSynthetic() && !method.isBridge()) {
          checkIdentifierForOneDotX(method.getName(), irType.getName() + "::" + method.getName(), violations);
        }
      }
    }

    assertTrue(
        violations.isEmpty(),
        "Prohibited STVN 1.x remnants detected in org.stvnadore.core.ir:\n" + String.join("\n", violations)
    );
  }

  @Test
  @DisplayName("TC-IR-ARCH-04: Verify closed 16-variant StvnSchema and 15-variant StvnValue sealed algebras and deep immutability")
  void testSealedAlgebrasAndDeepImmutability() {
    assertTrue(StvnSchema.class.isSealed(), "StvnSchema must be a sealed interface");
    Class<?>[] schemaSubtypes = StvnSchema.class.getPermittedSubclasses();
    assertNotNull(schemaSubtypes);
    assertEquals(16, schemaSubtypes.length, "StvnSchema must permit strictly 16 normalized variants");
    for (Class<?> subtype : schemaSubtypes) {
      assertTrue(subtype.isRecord(), "Every StvnSchema variant must be an immutable Java record: " + subtype.getName());
      assertTrue(Modifier.isFinal(subtype.getModifiers()), "Record subtype must be final: " + subtype.getName());
    }

    assertTrue(StvnValue.class.isSealed(), "StvnValue must be a sealed interface");
    Class<?>[] valueSubtypes = StvnValue.class.getPermittedSubclasses();
    assertNotNull(valueSubtypes);
    assertEquals(15, valueSubtypes.length, "StvnValue must permit strictly 15 lowered value variants");
    for (Class<?> subtype : valueSubtypes) {
      assertTrue(subtype.isRecord(), "Every StvnValue variant must be an immutable Java record: " + subtype.getName());
      assertTrue(Modifier.isFinal(subtype.getModifiers()), "Record subtype must be final: " + subtype.getName());
    }

    var defaultTraits = StvnSchema.TraitProfile.ofDefault(true, true);
    var intSchema = new StvnSchema.IntSchema(
        Optional.of(":pkg/Port"),
        true,
        BigInteger.valueOf(16L),
        defaultTraits,
        Optional.of(BigInteger.ONE),
        Optional.empty()
    );
    var intVal = new StvnValue.StvnInt(intSchema, BigInteger.valueOf(443L));

    var mutableTypes = new LinkedHashMap<String, StvnSchema>();
    mutableTypes.put(":pkg/Port", intSchema);
    var mutableConstants = new LinkedHashMap<String, StvnDefsCatalog.StvnConstant>();
    mutableConstants.put(":pkg/#HTTPS", new StvnDefsCatalog.StvnConstant(":pkg/#HTTPS", intSchema, intVal));

    var catalog = new StvnDefsCatalog(mutableTypes, mutableConstants);
    mutableTypes.clear();
    mutableConstants.clear();

    assertEquals(Optional.of(intSchema), catalog.findType(":pkg/Port"));
    assertTrue(catalog.findConstant(":pkg/#HTTPS").isPresent());
    assertThrows(UnsupportedOperationException.class, () -> catalog.nominalTypes().clear());
    assertThrows(UnsupportedOperationException.class, () -> catalog.constants().clear());

    var mutableElements = new ArrayList<StvnValue>(List.of(intVal));
    var seqSchema = new StvnSchema.SeqSchema(
        Optional.empty(),
        BigInteger.ZERO,
        StvnVocabulary.DEFAULT_MAX_SIZE,
        intSchema,
        defaultTraits
    );
    var seqVal = new StvnValue.StvnSeq(seqSchema, mutableElements);
    mutableElements.clear();
    assertEquals(1, seqVal.elements().size());
    assertThrows(UnsupportedOperationException.class, () -> seqVal.elements().add(intVal));

    var mapSchema = new StvnSchema.MapSchema(
        Optional.empty(),
        false,
        BigInteger.ZERO,
        StvnVocabulary.DEFAULT_MAX_SIZE,
        intSchema,
        intSchema,
        defaultTraits
    );
    var mapVal = new StvnValue.StvnMap(mapSchema, List.of(new StvnValue.KeyValueEntry(intVal, intVal)));
    assertEquals(1, mapVal.entries().size());
    assertThrows(
        UnsupportedOperationException.class,
        () -> mapVal.entries().add(new StvnValue.KeyValueEntry(intVal, intVal))
    );
  }

  @Test
  @DisplayName("TC-IR-ARCH-05: Verify O(1) symbolic IntSchema.acceptsWidth on { #size 4294967296 } :Int and boundary bit-widths")
  void testSymbolicAcceptsWidthZeroMaterializationAndBoundaries() {
    var defaultTraits = StvnSchema.TraitProfile.ofDefault(true, true);
    BigInteger fourGigabits = BigInteger.valueOf(4_294_967_296L);
    BigInteger astronomicalWidth = BigInteger.ONE.shiftLeft(128);

    var signed4GbSchema = new StvnSchema.IntSchema(
        Optional.empty(),
        false,
        fourGigabits,
        defaultTraits,
        Optional.empty(),
        Optional.empty()
    );
    var unsigned4GbSchema = new StvnSchema.IntSchema(
        Optional.empty(),
        true,
        fourGigabits,
        defaultTraits,
        Optional.empty(),
        Optional.empty()
    );
    var signedAstronomicalSchema = new StvnSchema.IntSchema(
        Optional.empty(),
        false,
        astronomicalWidth,
        defaultTraits,
        Optional.empty(),
        Optional.empty()
    );

    assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
      assertTrue(signed4GbSchema.acceptsWidth(BigInteger.ZERO));
      assertTrue(signed4GbSchema.acceptsWidth(BigInteger.valueOf(-1L)));
      assertTrue(signed4GbSchema.acceptsWidth(BigInteger.ONE.shiftLeft(8192).negate()));
      assertTrue(signed4GbSchema.acceptsWidth(BigInteger.ONE.shiftLeft(8192)));

      assertTrue(unsigned4GbSchema.acceptsWidth(BigInteger.ZERO));
      assertTrue(unsigned4GbSchema.acceptsWidth(BigInteger.ONE.shiftLeft(8192)));
      assertFalse(unsigned4GbSchema.acceptsWidth(BigInteger.valueOf(-1L)));

      assertTrue(signedAstronomicalSchema.acceptsWidth(BigInteger.ONE.shiftLeft(4096)));
      assertTrue(signedAstronomicalSchema.acceptsWidth(BigInteger.ONE.shiftLeft(4096).negate()));
    });

    int[] exactTestWidths = {1, 7, 8, 32, 64, 128, 8192};
    for (int width : exactTestWidths) {
      BigInteger bw = BigInteger.valueOf(width);

      var unsignedSchema = new StvnSchema.IntSchema(
          Optional.empty(),
          true,
          bw,
          defaultTraits,
          Optional.empty(),
          Optional.empty()
      );
      BigInteger unsignedMax = BigInteger.ONE.shiftLeft(width).subtract(BigInteger.ONE);
      BigInteger unsignedOverflow = BigInteger.ONE.shiftLeft(width);

      assertFalse(unsignedSchema.acceptsWidth(BigInteger.valueOf(-1L)), "Unsigned width " + width + " must reject -1");
      assertTrue(unsignedSchema.acceptsWidth(BigInteger.ZERO), "Unsigned width " + width + " must accept 0");
      assertTrue(unsignedSchema.acceptsWidth(unsignedMax), "Unsigned width " + width + " must accept 2^s - 1");
      assertFalse(unsignedSchema.acceptsWidth(unsignedOverflow), "Unsigned width " + width + " must reject 2^s");

      var signedSchema = new StvnSchema.IntSchema(
          Optional.empty(),
          false,
          bw,
          defaultTraits,
          Optional.empty(),
          Optional.empty()
      );
      BigInteger signedMin = BigInteger.ONE.shiftLeft(width - 1).negate();
      BigInteger signedUnderflow = signedMin.subtract(BigInteger.ONE);
      BigInteger signedMax = BigInteger.ONE.shiftLeft(width - 1).subtract(BigInteger.ONE);
      BigInteger signedOverflow = BigInteger.ONE.shiftLeft(width - 1);

      assertFalse(signedSchema.acceptsWidth(signedUnderflow), "Signed width " + width + " must reject -2^(s-1) - 1");
      assertTrue(signedSchema.acceptsWidth(signedMin), "Signed width " + width + " must accept -2^(s-1)");
      assertTrue(signedSchema.acceptsWidth(BigInteger.ZERO), "Signed width " + width + " must accept 0");
      assertTrue(signedSchema.acceptsWidth(signedMax), "Signed width " + width + " must accept 2^(s-1) - 1");
      assertFalse(signedSchema.acceptsWidth(signedOverflow), "Signed width " + width + " must reject 2^(s-1)");
    }

    assertThrows(
        IllegalArgumentException.class,
        () -> new StvnSchema.IntSchema(
            Optional.empty(),
            false,
            BigInteger.ZERO,
            defaultTraits,
            Optional.empty(),
            Optional.empty()
        )
    );
  }

  @Test
  @DisplayName("TC-IR-ARCH-06: Verify StvnVocabulary BigInteger defaults and StvnDiagnostic dual-code representation")
  void testVocabularyDefaultsAndDiagnosticDualCodes() {
    assertEquals(BigInteger.valueOf(32L), StvnVocabulary.DEFAULT_INT_BIT_WIDTH);
    assertEquals(BigInteger.valueOf(64L), StvnVocabulary.DEFAULT_FLOAT_BIT_WIDTH);
    assertEquals(BigInteger.valueOf(16_777_216L), StvnVocabulary.DEFAULT_MAX_SIZE);
    assertEquals(67_108_864, StvnVocabulary.MAX_WIRE_UTF8_FRAME_BYTES);

    assertEquals(BigInteger.valueOf(128L), StvnSchema.TemporalScale.NS.bitWidth());
    assertEquals(BigInteger.valueOf(64L), StvnSchema.TemporalScale.US.bitWidth());
    assertEquals(BigInteger.valueOf(64L), StvnSchema.TemporalScale.MS.bitWidth());
    assertEquals(BigInteger.valueOf(64L), StvnSchema.TemporalScale.S.bitWidth());

    var dualDiag = new StvnDiagnostic(
        "Tier-1 definition facet '#size' cannot be overridden on nominal type ':Port'",
        DiagnosticSeverity.ERROR,
        6,
        14,
        72,
        80,
        null,
        Optional.of("E040"),
        Optional.of("ERR_NON_OVERRIDABLE_FACET")
    );
    assertEquals(Optional.of("E040"), dualDiag.canonicalCode());
    assertEquals(Optional.of("ERR_NON_OVERRIDABLE_FACET"), dualDiag.symbolicId());
    assertEquals(Optional.of("ERR_NON_OVERRIDABLE_FACET"), dualDiag.errorCode());

    var canonicalSingleDiag = new StvnDiagnostic(
        "Redundant default facet '#size 32'",
        DiagnosticSeverity.WARNING,
        4,
        10,
        40,
        48,
        null,
        Optional.of("W003")
    );
    assertEquals(Optional.of("W003"), canonicalSingleDiag.canonicalCode());
    assertEquals(Optional.empty(), canonicalSingleDiag.symbolicId());
    assertEquals(Optional.of("W003"), canonicalSingleDiag.errorCode());

    var symbolicSingleDiag = new StvnDiagnostic(
        "Unknown nominal type ':Missing'",
        DiagnosticSeverity.ERROR,
        5,
        10,
        50,
        58,
        null,
        Optional.of("ERR_UNKNOWN_TYPE")
    );
    assertEquals(Optional.empty(), symbolicSingleDiag.canonicalCode());
    assertEquals(Optional.of("ERR_UNKNOWN_TYPE"), symbolicSingleDiag.symbolicId());
    assertEquals(Optional.of("ERR_UNKNOWN_TYPE"), symbolicSingleDiag.errorCode());
  }

  private static Set<Class<?>> collectAllIrTypes() {
    Set<Class<?>> collected = new LinkedHashSet<>();
    for (Class<?> root : TOP_LEVEL_IR_CLASSES) {
      collectNestedTypes(root, collected);
    }
    return collected;
  }

  private static void collectNestedTypes(Class<?> clazz, Set<Class<?>> collected) {
    if (!collected.add(clazz)) {
      return;
    }
    for (Class<?> nested : clazz.getDeclaredClasses()) {
      collectNestedTypes(nested, collected);
    }
  }

  private static void inspectTypeForForbiddenPackages(Type type, String location, List<String> violations) {
    if (type == null) {
      return;
    }
    if (type instanceof Class<?> raw) {
      String name = raw.getName();
      if (name.startsWith("org.antlr.")
          || name.startsWith("org.stvnadore.core.parser.")
          || name.startsWith("org.stvnadore.core.ast.")) {
        violations.add(location + " references forbidden type " + name);
      }
      if (raw.isArray()) {
        inspectTypeForForbiddenPackages(raw.getComponentType(), location + "[]", violations);
      }
    } else if (type instanceof ParameterizedType pt) {
      inspectTypeForForbiddenPackages(pt.getRawType(), location, violations);
      for (Type arg : pt.getActualTypeArguments()) {
        inspectTypeForForbiddenPackages(arg, location + "<>", violations);
      }
    }
  }

  private static void inspectTypeForHardwareNumerics(Type type, String location, List<String> violations) {
    if (type == null) {
      return;
    }
    if (type instanceof Class<?> raw) {
      if (FORBIDDEN_HARDWARE_NUMERIC_TYPES.contains(raw)) {
        violations.add(location + " uses forbidden hardware-sized numeric type " + raw.getName());
      }
      if (raw.isArray()) {
        inspectTypeForHardwareNumerics(raw.getComponentType(), location + "[]", violations);
      }
    } else if (type instanceof ParameterizedType pt) {
      inspectTypeForHardwareNumerics(pt.getRawType(), location, violations);
      for (Type arg : pt.getActualTypeArguments()) {
        inspectTypeForHardwareNumerics(arg, location + "<>", violations);
      }
    }
  }

  private static void checkIdentifierForOneDotX(String identifier, String location, List<String> violations) {
    String lower = identifier.toLowerCase(Locale.ROOT);
    for (String forbidden : FORBIDDEN_1_X_SUBSTRINGS) {
      if (lower.contains(forbidden.toLowerCase(Locale.ROOT))) {
        violations.add(location + " contains prohibited 1.x identifier substring '" + forbidden + "'");
      }
    }
  }
}
