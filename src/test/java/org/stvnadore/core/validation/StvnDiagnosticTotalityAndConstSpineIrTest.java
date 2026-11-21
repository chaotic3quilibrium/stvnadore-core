package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.ir.StvnDefsCatalog;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.utils.StvnProjectPaths;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Architectural totality and pure-IR constant sum-spine verification suite ({@code DST-01} and {@code DST-02}).
 * <p>
 * Enforces 100% dual diagnostic attribution ({@link StvnDiagnostic#code()} and {@link StvnDiagnostic#symbolicId()})
 * across all negative and warning shared fixtures, source-level constructor hygiene, {@link DiagnosticBag}
 * fail-fast guardrails, bidirectional {@link StvnDefsCatalog#findConstant} lookup, and {@code :body}-isolated
 * {@link StvnCompiler#compileDefsCatalog} recovery.
 *
 * @since 2.0.0
 */
@NullMarked
class StvnDiagnosticTotalityAndConstSpineIrTest {

  private static final Pattern NORMATIVE_CODE_PATTERN =
      Pattern.compile("^(?:E0(?:0[1-9]|[1-3][0-9]|40)|W00[1-3])$");

  private static final Pattern SYMBOLIC_ID_PATTERN =
      Pattern.compile("^[A-Z][A-Z0-9_]+$");

  private static final Pattern NEW_DIAGNOSTIC_CALL_PATTERN =
      Pattern.compile("(?s)new\\s+(?:org\\.stvnadore\\.core\\.)?StvnDiagnostic\\s*\\((.*?)\\)\\s*(?:;|\\.)");

  @Test
  @DisplayName("TC-DIAG-TOTAL-01: 100% of negative and warning shared fixtures emit dual canonicalCode + symbolicId with zero suffix pollution")
  void testSharedFixturesDiagnosticDualCodeTotality() throws IOException {
    Path sharedFixturesDir = StvnProjectPaths.resolve("shared-fixtures");
    assertTrue(Files.isDirectory(sharedFixturesDir), "shared-fixtures directory must exist");

    List<Path> targetFixtures;
    try (var stream = Files.walk(sharedFixturesDir)) {
      targetFixtures = stream
          .filter(Files::isRegularFile)
          .filter(StvnDiagnosticTotalityAndConstSpineIrTest::isNegativeOrWarningFixture)
          .sorted()
          .toList();
    }
    assertTrue(targetFixtures.size() >= 150, "Expected at least 150 negative/warning fixtures, found: " + targetFixtures.size());

    int totalCheckedDiagnostics = 0;
    int totalCheckedErrorValues = 0;
    List<String> violations = new ArrayList<>();

    for (Path fixture : targetFixtures) {
      String fileName = fixture.getFileName().toString();
      StvnCompilationResult<StvnDocument> result;
      if (fileName.endsWith(".stvn_b") || fileName.endsWith(".stvn_bd")) {
        result = StvnCompiler.compile(Files.readAllBytes(fixture), fixture.toString(), StvnParserConfig.DEFAULT);
      } else {
        String text = Files.readString(fixture, StandardCharsets.UTF_8);
        result = StvnCompiler.compile(text, fixture.toString(), StvnParserConfig.DEFAULT);

        StvnCompilationResult<StvnDefsCatalog> catRes =
            StvnCompiler.compileDefsCatalog(text, fixture.toString(), StvnParserConfig.DEFAULT);
        for (StvnDiagnostic cd : catRes.diagnostics()) {
          verifyDiagnosticTotality(fixture, "compileDefsCatalog", cd, violations);
          totalCheckedDiagnostics++;
        }
        if (catRes.document().isPresent()) {
          for (StvnDefsCatalog.StvnConstant c : catRes.document().get().constants().values()) {
            totalCheckedErrorValues += verifyErrorValueSentinels(fixture, c.value(), violations);
          }
        }
      }

      for (StvnDiagnostic d : result.diagnostics()) {
        verifyDiagnosticTotality(fixture, "compile", d, violations);
        totalCheckedDiagnostics++;
      }
      if (result.document().isPresent()) {
        StvnDocument doc = result.document().get();
        for (StvnDefsCatalog.StvnConstant c : doc.defs().constants().values()) {
          totalCheckedErrorValues += verifyErrorValueSentinels(fixture, c.value(), violations);
        }
        if (doc.payload().isPresent()) {
          totalCheckedErrorValues += verifyErrorValueSentinels(fixture, doc.payload().get(), violations);
        }
      }
    }

    assertTrue(
        violations.isEmpty(),
        "Detected unattributed, half-attributed, or suffix-polluted StvnDiagnostic instances:\n"
            + String.join("\n", violations)
    );
    assertTrue(
        totalCheckedDiagnostics >= 600,
        "Expected at least 600 validated diagnostics across negative/warning fixtures, checked: " + totalCheckedDiagnostics
    );
    assertTrue(
        totalCheckedErrorValues >= 1,
        "Expected at least 1 validated StvnErrorValue sentinel across fixtures, checked: " + totalCheckedErrorValues
    );
  }

  @Test
  @DisplayName("TC-DIAG-TOTAL-02: DiagnosticBag lookup tables are total and src/main/java invokes zero partial StvnDiagnostic constructors")
  void testDiagnosticBagBijectiveTablesAndConstructorCallHygiene() throws Exception {
    Field[] fields = DiagnosticBag.class.getDeclaredFields();
    int checkedConstants = 0;
    for (Field field : fields) {
      int mods = field.getModifiers();
      if (Modifier.isPublic(mods) && Modifier.isStatic(mods) && Modifier.isFinal(mods)
          && field.getType() == String.class
          && (field.getName().startsWith("ERR_") || field.getName().startsWith("WARN_"))) {
        String symbolicVal = (String) field.get(null);
        Optional<String> canonical = DiagnosticBag.resolveCanonicalCode(symbolicVal);
        assertTrue(
            canonical.isPresent() && NORMATIVE_CODE_PATTERN.matcher(canonical.get()).matches(),
            "DiagnosticBag." + field.getName() + " (\"" + symbolicVal + "\") must map to a normative canonical code"
        );
        DiagnosticBag.DualCode dual = DiagnosticBag.resolveDualCodes(symbolicVal);
        assertEquals(canonical.get(), dual.canonicalCode());
        assertEquals(symbolicVal, dual.symbolicId());
        checkedConstants++;
      }
    }
    assertTrue(checkedConstants >= 90, "Expected at least 90 DiagnosticBag constants, checked: " + checkedConstants);

    List<String> allNormativeCodes = new ArrayList<>();
    for (int i = 1; i <= 40; i++) {
      allNormativeCodes.add(String.format("E%03d", i));
    }
    allNormativeCodes.add("W001");
    allNormativeCodes.add("W002");
    allNormativeCodes.add("W003");

    for (String code : allNormativeCodes) {
      Optional<String> defaultSym = DiagnosticBag.resolveDefaultSymbolicId(code);
      assertTrue(defaultSym.isPresent(), "Missing default symbolic ID for normative code " + code);
      assertEquals(
          Optional.of(code),
          DiagnosticBag.resolveCanonicalCode(defaultSym.get()),
          "Default symbolic ID '" + defaultSym.get() + "' for " + code + " must round-trip in resolveCanonicalCode"
      );
      DiagnosticBag.DualCode dual = DiagnosticBag.resolveDualCodes(code);
      assertEquals(code, dual.canonicalCode());
      assertEquals(defaultSym.get(), dual.symbolicId());
    }

    Path mainJavaDir = StvnProjectPaths.resolve("src", "main", "java");
    List<String> constructorViolations = new ArrayList<>();
    try (var stream = Files.walk(mainJavaDir)) {
      List<Path> javaFiles = stream
          .filter(p -> p.toString().endsWith(".java"))
          .filter(p -> !p.getFileName().toString().equals("StvnDiagnostic.java"))
          .sorted()
          .toList();
      for (Path file : javaFiles) {
        String src = Files.readString(file, StandardCharsets.UTF_8);
        Matcher matcher = NEW_DIAGNOSTIC_CALL_PATTERN.matcher(src);
        while (matcher.find()) {
          String argsList = matcher.group(1);
          int topLevelArgCount = countTopLevelArguments(argsList);
          if (topLevelArgCount < 9) {
            constructorViolations.add(file + " invokes partial " + topLevelArgCount + "-arg StvnDiagnostic constructor");
          }
        }
      }
    }
    assertTrue(
        constructorViolations.isEmpty(),
        "Partial StvnDiagnostic constructors (6/7/8-arg) are prohibited in src/main/java:\n"
            + String.join("\n", constructorViolations)
    );
  }

  @Test
  @DisplayName("TC-DIAG-TOTAL-03: DiagnosticBag and StvnDiagnostic.ofDual reject unmapped or half-attributed diagnostics")
  void testDiagnosticBagAndOfDualFailFastGuards() {
    DiagnosticBag bag = new DiagnosticBag(16, "guard_test.stvn");

    assertThrows(
        IllegalArgumentException.class,
        () -> bag.addError("Unmapped error", 0, 1, 1, 0, null, "UNMAPPED_BOGUS_SYMBOLIC_ID")
    );
    assertThrows(
        IllegalArgumentException.class,
        () -> bag.addWarning("Unmapped warning", 0, 1, 1, 0, "UNMAPPED_BOGUS_WARNING_ID")
    );
    assertThrows(
        IllegalArgumentException.class,
        () -> bag.add(new StvnDiagnostic("Unattributed", 1, 0, 0, 1, null))
    );
    assertThrows(
        IllegalArgumentException.class,
        () -> StvnDiagnostic.ofDual("Invalid code", DiagnosticSeverity.ERROR, 1, 0, 0, 1, null, "E999", "ERR_TYPE_MISMATCH")
    );
    assertThrows(
        IllegalArgumentException.class,
        () -> StvnDiagnostic.ofDual("Blank symbolic", DiagnosticSeverity.ERROR, 1, 0, 0, 1, null, "E028", "   ")
    );

    StvnDiagnostic validDual = StvnDiagnostic.ofDual(
        "Union variant tag '#3' exceeds branch count (2)",
        DiagnosticSeverity.ERROR,
        28,
        72,
        1218,
        1220,
        null,
        "E028",
        DiagnosticBag.ERR_UNION_BRANCH_OVERFLOW
    );
    assertEquals(Optional.of("E028"), validDual.code());
    assertEquals(Optional.of("E028"), validDual.canonicalCode());
    assertEquals(Optional.of(DiagnosticBag.ERR_UNION_BRANCH_OVERFLOW), validDual.symbolicId());

    assertTrue(bag.add(new StvnDiagnostic(
        "Auto-enriched canonical warning",
        DiagnosticSeverity.WARNING,
        1,
        0,
        0,
        4,
        null,
        Optional.of("W003")
    )));
    StvnDiagnostic enriched = bag.toList().getFirst();
    assertEquals(Optional.of("W003"), enriched.code());
    assertEquals(Optional.of(DiagnosticBag.WARN_REDUNDANT_METADATA_FACET), enriched.symbolicId());
  }

  @Test
  @DisplayName("TC-DIAG-TOTAL-04: E031 prohibited section diagnostics emit clean prose and Stage 8 #3 #TRUE pins E028-UNION_BRANCH_OVERFLOW strictly to '#3'")
  void testE031CleanProseAndStage8UnionBranchOverflowPinning() throws IOException {
    Path e031Fixture = StvnProjectPaths.resolve(
        "shared-fixtures", "syntax", "invalid", "structure", "defs_with_type_and_body.stvn_d"
    );
    String e031Text = Files.readString(e031Fixture, StandardCharsets.UTF_8);
    var e031Res = StvnCompiler.compile(e031Text, "defs_with_type_and_body.stvn_d", StvnParserConfig.DEFAULT);
    assertEquals(2, e031Res.diagnostics().size());
    for (StvnDiagnostic d : e031Res.diagnostics()) {
      assertEquals(Optional.of("E031"), d.code());
      assertEquals(Optional.of(DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH), d.symbolicId());
      assertFalse(d.message().contains("[stvn-core:"), "Core diagnostic message must not embed suffix: " + d.message());
    }

    Path stage8Fixture = StvnProjectPaths.resolve(
        "shared-fixtures", "syntax", "invalid", "composites", "stage8_union_branch_overflow_and_const_sum_spine.stvn"
    );
    byte[] rawBytes = Files.readAllBytes(stage8Fixture);
    for (byte b : rawBytes) {
      assertFalse(b == '\r', "Fixture must maintain pure LF line endings");
    }
    String stage8Text = new String(rawBytes, StandardCharsets.UTF_8);
    var stage8Res = StvnCompiler.compile(stage8Text, stage8Fixture.toString(), StvnParserConfig.DEFAULT);
    assertEquals(1, stage8Res.diagnostics().size(), () -> "Expected single E028 diagnostic: " + stage8Res.diagnostics());

    StvnDiagnostic overflowDiag = stage8Res.diagnostics().getFirst();
    assertEquals(Optional.of("E028"), overflowDiag.code());
    assertEquals(Optional.of(DiagnosticBag.ERR_UNION_BRANCH_OVERFLOW), overflowDiag.symbolicId());
    assertEquals("Union variant tag '#3' exceeds branch count (2)", overflowDiag.message());
    int expectedStart = stage8Text.lastIndexOf("#3 #TRUE");
    assertEquals(expectedStart, overflowDiag.startOffset());
    assertEquals(expectedStart + 2, overflowDiag.endOffset());
  }

  @Test
  @DisplayName("TC-CONST-SPINE-IR-01: #REDUCIBLE_DEF_LEAF lowers full 5-layer [#Some, #Right, #Some, #Right, #2] StvnValue sum spine")
  void testReducibleDefLeafFiveLayerSumSpineInCatalog() throws IOException {
    Path stage8Fixture = StvnProjectPaths.resolve(
        "shared-fixtures", "syntax", "invalid", "composites", "stage8_union_branch_overflow_and_const_sum_spine.stvn"
    );
    String stage8Text = Files.readString(stage8Fixture, StandardCharsets.UTF_8);
    StvnDefsCatalog catalog = StvnCompiler.compileDefsCatalog(stage8Text, stage8Fixture.toString()).document().orElseThrow();

    StvnDefsCatalog.StvnConstant reducible = catalog.findConstant("#REDUCIBLE_DEF_LEAF").orElseThrow();
    assertEquals("#REDUCIBLE_DEF_LEAF", reducible.fqni());
    assertEquals(Optional.of(":OuterDispatchEnvelope"), reducible.schema().aliasName());

    // Layer 1: :OuterDispatchEnvelope -> StvnOption(Some)
    assertTrue(reducible.value() instanceof StvnValue.StvnOption);
    StvnValue.StvnOption opt1 = (StvnValue.StvnOption) reducible.value();
    assertTrue(opt1.value().isPresent());

    // Layer 2: :Either(:RetryMillis :InnerOutcome) -> StvnEither(RIGHT)
    assertTrue(opt1.value().get() instanceof StvnValue.StvnEither);
    StvnValue.StvnEither either1 = (StvnValue.StvnEither) opt1.value().get();
    assertEquals(StvnValue.EitherBranch.RIGHT, either1.branch());

    // Layer 3: :InnerOutcome -> StvnOption(Some)
    assertTrue(either1.value() instanceof StvnValue.StvnOption);
    StvnValue.StvnOption opt2 = (StvnValue.StvnOption) either1.value();
    assertEquals(Optional.of(":InnerOutcome"), opt2.schema().aliasName());
    assertTrue(opt2.value().isPresent());

    // Layer 4: :Either(:SensorId :TelemetryBranchAlias) -> StvnEither(RIGHT)
    assertTrue(opt2.value().get() instanceof StvnValue.StvnEither);
    StvnValue.StvnEither either2 = (StvnValue.StvnEither) opt2.value().get();
    assertEquals(StvnValue.EitherBranch.RIGHT, either2.branch());

    // Layer 5: :TelemetryBranchAlias -> :TelemetryBranch -> StvnUnion(oneBasedBranchIndex = 2)
    assertTrue(either2.value() instanceof StvnValue.StvnUnion);
    StvnValue.StvnUnion unionVal = (StvnValue.StvnUnion) either2.value();
    assertEquals(BigInteger.TWO, unionVal.oneBasedBranchIndex());
    assertEquals(Optional.of(":TelemetryBranchAlias"), unionVal.schema().aliasName());

    // Leaf: :ConfidenceRatio -> StvnFloat(0.85)
    assertTrue(unionVal.value() instanceof StvnValue.StvnFloat);
    StvnValue.StvnFloat leafFloat = (StvnValue.StvnFloat) unionVal.value();
    assertEquals(0, new BigDecimal("0.85").compareTo(leafFloat.decimalValue()));
    assertEquals(Optional.of(":ConfidenceRatio"), leafFloat.schema().aliasName());
  }

  @Test
  @DisplayName("TC-CONST-SPINE-IR-02: #EXPLICIT_DEF_LEAF matches #REDUCIBLE_DEF_LEAF spine and #IRREDUCIBLE_DEF_NONE preserves [#Right] to inner #None")
  void testExplicitAndIrreducibleNoneConstantSpines() throws IOException {
    Path stage8Fixture = StvnProjectPaths.resolve(
        "shared-fixtures", "syntax", "invalid", "composites", "stage8_union_branch_overflow_and_const_sum_spine.stvn"
    );
    String stage8Text = Files.readString(stage8Fixture, StandardCharsets.UTF_8);
    StvnDefsCatalog catalog = StvnCompiler.compileDefsCatalog(stage8Text, stage8Fixture.toString()).document().orElseThrow();

    StvnDefsCatalog.StvnConstant reducible = catalog.findConstant("#REDUCIBLE_DEF_LEAF").orElseThrow();
    StvnDefsCatalog.StvnConstant explicit = catalog.findConstant("#EXPLICIT_DEF_LEAF").orElseThrow();
    assertEquals(reducible.value(), explicit.value(), "Implicit and explicit sum-tag forms must lower to identical StvnValue spines");

    StvnDefsCatalog.StvnConstant irreducibleNone = catalog.findConstant("#IRREDUCIBLE_DEF_NONE").orElseThrow();
    StvnValue.StvnOption outerSome = (StvnValue.StvnOption) irreducibleNone.value();
    assertTrue(outerSome.value().isPresent());
    StvnValue.StvnEither midRight = (StvnValue.StvnEither) outerSome.value().get();
    assertEquals(StvnValue.EitherBranch.RIGHT, midRight.branch());
    StvnValue.StvnOption innerNone = (StvnValue.StvnOption) midRight.value();
    assertTrue(innerNone.value().isEmpty(), "Inner :InnerOutcome option must be empty (#None)");
  }

  @Test
  @DisplayName("TC-CONST-SPINE-IR-03: StvnDefsCatalog.findConstant supports bidirectional #pkg/CONST <-> :pkg/#CONST and (packagePath, constantName)")
  void testBidirectionalPackagedConstantLookup() throws IOException {
    Path stage8Fixture = StvnProjectPaths.resolve(
        "shared-fixtures", "syntax", "invalid", "composites", "stage8_union_branch_overflow_and_const_sum_spine.stvn"
    );
    String stage8Text = Files.readString(stage8Fixture, StandardCharsets.UTF_8);
    StvnDefsCatalog catalog = StvnCompiler.compileDefsCatalog(stage8Text, stage8Fixture.toString()).document().orElseThrow();

    StvnDefsCatalog.StvnConstant byValueFqni = catalog.findConstant("#telemetry/PKG_REDUCIBLE_LEAF").orElseThrow();
    StvnDefsCatalog.StvnConstant byTypicFqni = catalog.findConstant(":telemetry/#PKG_REDUCIBLE_LEAF").orElseThrow();
    StvnDefsCatalog.StvnConstant byPairSigils = catalog.findConstant(":telemetry", "#PKG_REDUCIBLE_LEAF").orElseThrow();
    StvnDefsCatalog.StvnConstant byPairBare = catalog.findConstant("telemetry/", "PKG_REDUCIBLE_LEAF").orElseThrow();
    StvnDefsCatalog.StvnConstant rootNoSigil = catalog.findConstant(null, "REDUCIBLE_DEF_LEAF").orElseThrow();

    assertEquals(byValueFqni, byTypicFqni);
    assertEquals(byValueFqni, byPairSigils);
    assertEquals(byValueFqni, byPairBare);
    assertEquals("#REDUCIBLE_DEF_LEAF", rootNoSigil.fqni());

    // Verify reverse lookup when a synthetic catalog keys constants as :pkg/#CONST
    StvnSchema.IntSchema intSchema = new StvnSchema.IntSchema(
        Optional.empty(), true, BigInteger.valueOf(16L), StvnSchema.TraitProfile.ofDefault(true, true), Optional.empty(), Optional.empty()
    );
    StvnDefsCatalog.StvnConstant syntheticConst = new StvnDefsCatalog.StvnConstant(
        ":net/#HTTPS", intSchema, new StvnValue.StvnInt(intSchema, BigInteger.valueOf(443L))
    );
    StvnDefsCatalog syntheticCatalog = new StvnDefsCatalog(
        java.util.Map.of(),
        java.util.Map.of(":net/#HTTPS", syntheticConst)
    );
    assertEquals(Optional.of(syntheticConst), syntheticCatalog.findConstant(":net/#HTTPS"));
    assertEquals(Optional.of(syntheticConst), syntheticCatalog.findConstant("#net/HTTPS"));
    assertEquals(Optional.of(syntheticConst), syntheticCatalog.findConstant(":net", "#HTTPS"));
  }

  @Test
  @DisplayName("TC-CONST-SPINE-IR-04: compileDefsCatalog recovers healthy #CONST spines under sibling :defs traps and isolates in-flight :body syntax errors")
  void testCompileDefsCatalogSiblingIsolationAndBodySyntaxIsolation() {
    // 1. Sibling :defs traps (:TrapEmptyTuple, :TrapEmptyUnion) + :body error -> compile fails, compileDefsCatalog recovers
    String stage8WithDefsTraps = """
        {
          :meta {
            #name "stage8_defs_traps_recovery"
            #kind #BODY
          }
          :defs {
            :RetryMillis { #unsigned #size 16 } :Int
            :ConfidenceRatio { #minIncl 0.0 #maxExcl 1.0 } :Float
            :TelemetryBranch :Union( :Boolean :ConfidenceRatio )
            :OuterDispatchEnvelope :Option( :Either( :RetryMillis :TelemetryBranch ) )

            :TrapEmptyTuple :Tuple()
            :TrapEmptyUnion :Union()

            #IRREDUCIBLE_DEF_NONE :OuterDispatchEnvelope #None
            #REDUCIBLE_DEF_LEAF :OuterDispatchEnvelope 0.85
          }
          :type :Tuple( :TrapEmptyTuple :TrapEmptyUnion :TelemetryBranch )
          :body ( "bad-tuple" "bad-union" #3 #TRUE )
        }
        """;

    var fullRes = StvnCompiler.compile(stage8WithDefsTraps, "stage8_defs_traps_recovery.stvn");
    assertTrue(fullRes.hasErrors(), "Full document compilation must report errors when :defs and :body contain traps");

    var catRes = StvnCompiler.compileDefsCatalog(stage8WithDefsTraps, "stage8_defs_traps_recovery.stvn");
    assertTrue(catRes.document().isPresent(), "compileDefsCatalog must recover StvnDefsCatalog despite sibling :defs traps");
    assertTrue(catRes.isRecoveredPartialAst(), "Recovered catalog with :defs errors must mark isRecoveredPartialAst = true");
    assertTrue(catRes.hasErrors(), "Recovered catalog must preserve :defs E010 diagnostics");

    StvnDefsCatalog recovered = catRes.document().get();
    assertTrue(recovered.findConstant("#REDUCIBLE_DEF_LEAF").isPresent());
    assertTrue(recovered.findConstant("#IRREDUCIBLE_DEF_NONE").isPresent());

    // 1b. .stvn_d definitions schema with sibling :TrapEmptyTuple -> StvnCompiler.compile omits document(), compileDefsCatalog recovers
    String defsSchemaWithTrap = """
        {
          :meta {
            #name "stage8_defs_schema_trap"
            #kind #DEFS
          }
          :defs {
            :RetryMillis { #unsigned #size 16 } :Int
            :ConfidenceRatio { #minIncl 0.0 #maxExcl 1.0 } :Float
            :TelemetryBranch :Union( :Boolean :ConfidenceRatio )
            :OuterDispatchEnvelope :Option( :Either( :RetryMillis :TelemetryBranch ) )
            :TrapEmptyTuple :Tuple()
            #IRREDUCIBLE_DEF_NONE :OuterDispatchEnvelope #None
            #REDUCIBLE_DEF_LEAF :OuterDispatchEnvelope 0.85
          }
        }
        """;
    var fullDefsRes = StvnCompiler.compile(defsSchemaWithTrap, "stage8_defs_schema_trap.stvn_d");
    assertTrue(fullDefsRes.document().isEmpty(), "StvnCompiler.compile omits document() on .stvn_d with :defs errors");
    var recoveredDefsRes = StvnCompiler.compileDefsCatalog(defsSchemaWithTrap, "stage8_defs_schema_trap.stvn_d");
    assertTrue(recoveredDefsRes.document().isPresent(), "compileDefsCatalog must recover .stvn_d catalog despite sibling error");
    assertTrue(recoveredDefsRes.document().get().findConstant("#REDUCIBLE_DEF_LEAF").isPresent());
    assertTrue(recoveredDefsRes.document().get().findConstant("#IRREDUCIBLE_DEF_NONE").isPresent());

    // 2. Safeguard 3: In-flight :body syntax error ('#3 ' missing operand at closing ')') must NOT taint :meta/:defs catalog compilation
    String inFlightBodySyntaxDoc = """
        {
          :meta {
            #name "stage8_inflight_body_typing"
            #kind #BODY
          }
          :defs {
            :RetryMillis { #unsigned #size 16 } :Int
            :ConfidenceRatio { #minIncl 0.0 #maxExcl 1.0 } :Float
            :TelemetryBranch :Union( :Boolean :ConfidenceRatio )
            :OuterDispatchEnvelope :Option( :Either( :RetryMillis :TelemetryBranch ) )
            #REDUCIBLE_DEF_LEAF :OuterDispatchEnvelope 0.85
          }
          :type :Tuple( :OuterDispatchEnvelope :TelemetryBranch )
          :body ( #REDUCIBLE_DEF_LEAF #3 )
        }
        """;

    var inFlightFullRes = StvnCompiler.compile(inFlightBodySyntaxDoc, "stage8_inflight_body_typing.stvn");
    assertTrue(inFlightFullRes.hasErrors(), "Full compilation must report Pass 1 syntax error on incomplete '#3 )' in :body");

    var inFlightCatRes = StvnCompiler.compileDefsCatalog(inFlightBodySyntaxDoc, "stage8_inflight_body_typing.stvn");
    assertTrue(inFlightCatRes.isSuccess(), () -> "In-flight :body syntax error must not taint compileDefsCatalog: " + inFlightCatRes.diagnostics());
    assertFalse(inFlightCatRes.hasErrors());
    assertFalse(inFlightCatRes.isRecoveredPartialAst());
    assertTrue(inFlightCatRes.document().orElseThrow().findConstant("#REDUCIBLE_DEF_LEAF").isPresent());
  }

  private static boolean isNegativeOrWarningFixture(Path path) {
    String norm = path.toString().replace('\\', '/');
    if (norm.endsWith(".contract.stvn_i") || norm.endsWith(".stvn_ir.stvn_i")) {
      return false;
    }
    if (!(norm.endsWith(".stvn")
        || norm.endsWith(".stvn_i")
        || norm.endsWith(".stvn_d")
        || norm.endsWith(".stvn_di")
        || norm.endsWith(".stvn_b")
        || norm.endsWith(".stvn_bd"))) {
      return false;
    }
    return norm.contains("/invalid/") || norm.contains("/metadata_warnings/");
  }

  private static void verifyDiagnosticTotality(
      Path fixture,
      String phase,
      StvnDiagnostic d,
      List<String> violations
  ) {
    if (d.code().isEmpty() || d.canonicalCode().isEmpty()
        || !NORMATIVE_CODE_PATTERN.matcher(d.canonicalCode().get()).matches()) {
      violations.add(fixture + " [" + phase + "] missing/invalid canonicalCode on: " + d);
    }
    if (d.symbolicId().isEmpty() || !SYMBOLIC_ID_PATTERN.matcher(d.symbolicId().get()).matches()) {
      violations.add(fixture + " [" + phase + "] missing/invalid symbolicId on: " + d);
    }
    if (d.errorCode().isEmpty()) {
      violations.add(fixture + " [" + phase + "] missing errorCode on: " + d);
    }
    if (d.message().contains("[stvn-core:") || d.message().contains("[stvn-plugin:")) {
      violations.add(fixture + " [" + phase + "] raw Core message embeds IDE suffix: " + d.message());
    }
  }

  private static int verifyErrorValueSentinels(
      Path fixture,
      StvnValue value,
      List<String> violations
  ) {
    return switch (value) {
      case StvnValue.StvnErrorValue err -> {
        verifyDiagnosticTotality(fixture, "StvnErrorValue", err.diagnostic(), violations);
        yield 1;
      }
      case StvnValue.StvnOption opt -> opt.value().map(v -> verifyErrorValueSentinels(fixture, v, violations)).orElse(0);
      case StvnValue.StvnEither either -> verifyErrorValueSentinels(fixture, either.value(), violations);
      case StvnValue.StvnUnion union -> verifyErrorValueSentinels(fixture, union.value(), violations);
      case StvnValue.StvnTuple tuple -> tuple.elements().stream()
          .mapToInt(el -> verifyErrorValueSentinels(fixture, el, violations))
          .sum();
      case StvnValue.StvnSeq seq -> seq.elements().stream()
          .mapToInt(el -> verifyErrorValueSentinels(fixture, el, violations))
          .sum();
      case StvnValue.StvnSet set -> set.elements().stream()
          .mapToInt(el -> verifyErrorValueSentinels(fixture, el, violations))
          .sum();
      case StvnValue.StvnMap map -> map.entries().stream()
          .mapToInt(e -> verifyErrorValueSentinels(fixture, e.key(), violations)
              + verifyErrorValueSentinels(fixture, e.value(), violations))
          .sum();
      default -> 0;
    };
  }

  private static int countTopLevelArguments(String rawArgs) {
    String trimmed = rawArgs.trim();
    if (trimmed.isEmpty()) {
      return 0;
    }
    int depth = 0;
    boolean inString = false;
    int commas = 0;
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      if (c == '"' && (i == 0 || trimmed.charAt(i - 1) != '\\')) {
        inString = !inString;
      } else if (!inString) {
        if (c == '(') {
          depth++;
        } else if (c == ')') {
          depth--;
        } else if (c == ',' && depth == 0) {
          commas++;
        }
      }
    }
    return commas + 1;
  }
}
