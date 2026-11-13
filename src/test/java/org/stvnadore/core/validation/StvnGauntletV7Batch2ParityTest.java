package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.printer.StvnSourceFormatter;

import java.math.BigInteger;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conformance and regression suite for Interactive Gauntlet v7 Batch 2:
 * Domain A (Incomplete Boolean Trait Facets &amp; Reserved Keyword Boundary),
 * Domain B (Malformed Fenced String Container Slot Isolation &amp; Spatial Fault Containment),
 * Domain C (Mutex Precedence Over Tier Ordering &amp; Single-Quoted Temporal STE Prose), and
 * Domain D ({@code #CONST} Nominal Type Branding &amp; Enum Filter Poisoned Fallback Integrity).
 */
@NullMarked
class StvnGauntletV7Batch2ParityTest {

  private static final List<String> ALL_27_RESERVED_FACET_KEYWORDS = List.of(
      "#size", "#minSize", "#maxSize",
      "#minIncl", "#maxIncl", "#minExcl", "#maxExcl",
      "#regex", "#unsigned", "#exact", "#invertible",
      "#equatable", "#comparable", "#preserveIndent", "#strip",
      "#ns", "#us", "#ms", "#s",
      "#offset", "#zoned", "#audited",
      "#filterIncl", "#filterExcl",
      "#name", "#domain", "#kind"
  );

  // =========================================================================
  // Domain A — Incomplete Boolean Trait Facets & Reserved Keyword Boundary
  // =========================================================================

  static Stream<Arguments> provideTraitAndReservedFollowerPairs() {
    List<String> traits = List.of("#preserveIndent", "#equatable", "#comparable");
    return traits.stream().flatMap(trait ->
        ALL_27_RESERVED_FACET_KEYWORDS.stream().map(follower -> Arguments.of(trait, follower))
    );
  }

  @ParameterizedTest(name = "TC-GV7-A01: {0} followed by reserved {1} never emits E030 / ERR_UNDEFINED_CONSTANT")
  @MethodSource("provideTraitAndReservedFollowerPairs")
  @DisplayName("TC-GV7-A01: Incomplete boolean trait followed by any of the 27 reserved facet keywords never emits ERR_UNDEFINED_CONSTANT")
  void testIncompleteTraitFollowedByAll27ReservedKeywordsNeverEmitsUndefinedConstant(
      String traitFacet,
      String reservedFollower
  ) {
    assertEquals(27, ALL_27_RESERVED_FACET_KEYWORDS.size(), "Must cover all 27 reserved facet keywords");
    String followerEntry = switch (reservedFollower) {
      case "#size", "#minSize", "#maxSize" -> reservedFollower + " 16";
      case "#minIncl", "#maxIncl", "#minExcl", "#maxExcl" -> reservedFollower + " \"a\"";
      case "#regex" -> reservedFollower + " \"^[a-z]+$\"";
      case "#equatable", "#comparable", "#preserveIndent" -> reservedFollower + " #FALSE";
      case "#filterIncl", "#filterExcl" -> reservedFollower + " [ #A ]";
      case "#name", "#domain" -> reservedFollower + " \"demo\"";
      case "#kind" -> reservedFollower + " #BODY";
      default -> reservedFollower;
    };
    String source = """
        {
          :defs {
            :TestType { %s %s } :String
          }
          :type :Int
          :body 1
        }
        """.formatted(traitFacet, followerEntry);

    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Incomplete trait facet must fail compilation");

    assertFalse(
        result.diagnostics().stream().anyMatch(d ->
            "E030".equals(d.canonicalCode().orElse(null))
                || DiagnosticBag.ERR_UNDEFINED_CONSTANT.equals(d.errorCode().orElse(null))),
        () -> "Must emit zero E030 / ERR_UNDEFINED_CONSTANT diagnostics for " + traitFacet + " " + followerEntry
            + ", got: " + result.diagnostics()
    );

    var incompleteDiagOpt = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_INCOMPLETE_TRAIT_FACET.equals(d.errorCode().orElse(null)))
        .findFirst();
    assertTrue(incompleteDiagOpt.isPresent(),
        () -> "Expected ERR_INCOMPLETE_TRAIT_FACET on " + traitFacet + ", got: " + result.diagnostics());
    StvnDiagnostic incompleteDiag = incompleteDiagOpt.get();
    assertEquals("E008", incompleteDiag.canonicalCode().orElse(null));
    int expectedStart = source.indexOf(traitFacet);
    assertEquals(expectedStart, incompleteDiag.startOffset());
    assertEquals(expectedStart + traitFacet.length(), incompleteDiag.endOffset());
    assertEquals(
        "Metadata facet '" + traitFacet + "' requires an explicit boolean value (#TRUE or #FALSE)",
        incompleteDiag.message()
    );
  }

  @Test
  @DisplayName("TC-GV7-A02: :TrapIncompletePreserve { #preserveIndent #maxSize 64 } :String lowers to Case B grounded PoisonedSchema with maxSize = 64")
  void testIncompletePreserveIndentGroundedPoisonedSchemaAndSiblingFacetValidation() {
    String source = """
        {
          :defs {
            :TrapIncompletePreserve { #preserveIndent #maxSize 64 } :String
          }
          :type :Tuple(:TrapIncompletePreserve :Int)
          :body (
            "suspended_slot"
            "not_an_int"
          )
        }
        """;

    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors());
    assertTrue(result.document().isPresent(), "Partial IR document must be recovered");
    StvnDocument doc = result.document().get();

    StvnSchema trapSchema = doc.defs().findType(":TrapIncompletePreserve").orElseThrow();
    StvnSchema.PoisonedSchema poisoned = assertInstanceOf(StvnSchema.PoisonedSchema.class, trapSchema);
    assertTrue(poisoned.isGrounded(), "Incomplete trait facet must lower to Case B Grounded PoisonedSchema");
    StvnSchema.StringSchema fallback = assertInstanceOf(
        StvnSchema.StringSchema.class,
        poisoned.bestEffortFallback().orElseThrow()
    );
    assertEquals(BigInteger.valueOf(64), fallback.maxSize(), "Trailing #maxSize 64 must be preserved on fallback");

    assertEquals(2, result.diagnostics().size(),
        () -> "Expected 1 E008 on #preserveIndent and 1 E028 on sibling :Int slot, got: " + result.diagnostics());
    assertEquals(DiagnosticBag.ERR_INCOMPLETE_TRAIT_FACET, result.diagnostics().get(0).errorCode().orElse(null));
    assertEquals(DiagnosticBag.ERR_TYPE_MISMATCH, result.diagnostics().get(1).errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-GV7-A03: Valid boolean #CONST references on #preserveIndent, #equatable, and #comparable compile cleanly")
  void testValidBooleanConstantFacetReferencesCompileCleanly() {
    String source = """
        {
          :defs {
            #KEEP_INDENT :Boolean #TRUE
            #NO_ORDER :Boolean #FALSE
            :RawCode { #maxSize 128 #comparable #NO_ORDER #preserveIndent #KEEP_INDENT } :String
          }
          :type :RawCode
          :body "  hello"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.isSuccess(), () -> "Expected clean compilation, got: " + result.diagnostics());
  }

  @Test
  @DisplayName("TC-GV7-A04: StvnSourceFormatter formats incomplete trait facets and structured metadataUnknownArg non-destructively (Safeguard B)")
  void testSourceFormatterFormatsIncompleteTraitAndStructuredMetadataUnknownArg() {
    String source = """
        {
          :defs {
            :BrokenTrait { #maxSize 64 #preserveIndent } :String
            :UnknownTagged { #customOpt #Some [ 1   2 ] } :String
            :UnknownCol { #customMap { [ "k"   42 ] } } :String
          }
          :type :Int
          :body 1
        }
        """;
    String formatted = StvnSourceFormatter.format(source);
    assertTrue(formatted.contains(":BrokenTrait { #maxSize 64 #preserveIndent } :String"),
        () -> "Formatter must handle optional metadataValue on incomplete trait: " + formatted);
    assertTrue(formatted.contains(":UnknownTagged { #customOpt #Some [1 2] } :String"),
        () -> "Formatter must delegate structured explicitOptionValue/collectionValue in metadataUnknownArg: " + formatted);
    assertTrue(formatted.contains(":UnknownCol { #customMap {\n      [\"k\" 42]\n    } } :String"),
        () -> "Formatter must delegate structured collectionValue in metadataUnknownArg: " + formatted);
  }

  // =========================================================================
  // Domain B — Malformed Fenced Strings & Spatial Fault Containment
  // =========================================================================

  @ParameterizedTest(name = "TC-GV7-B01: 14-slot tuple preserves M == N == 14 around malformed fence variant [{index}]")
  @ValueSource(strings = {
      "\"\"\"\n      untagged multi-line body\n      \"\"\"",
      "\"\"\"[]\n      empty tag body\n      []\"\"\"",
      "\"\"\"->[RAW]\n      prohibited arrow fence body\n      [RAW]\"\"\"",
      "\"\"\"inline malformed fence\"\"\""
  })
  @DisplayName("TC-GV7-B01: Malformed fenced string variants in Slot 5 preserve 14-slot tuple alignment and downstream validation (Safeguard A)")
  void testFourteenSlotTupleIsolatesMalformedFenceVariants(String slot5MalformedFence) {
    String source = """
        {
          :defs {
            :TrapIncompletePreserve { #preserveIndent #maxSize 64 } :String
            :IsolatedSyntaxBroken { #size } :Int
            :RouteKey { #minSize 4 #maxSize 16 #regex "^[a-z0-9-]+$" } :String
            :ShortCode { #minSize 4 #maxSize 4 } :String
          }
          :type :Tuple(
            :String
            :Int
            :Boolean
            :String
            :Int
            :String
            :TrapIncompletePreserve
            :String
            :Int
            :Boolean
            :Boolean
            :RouteKey
            :ShortCode
            :RouteKey
          )
          :body (
            "slot0-ok"
            101
            #TRUE
            "slot3-ok"
            202
            %s
            "slot6-suspended"
            "slot7-ok"
            303
            #FALSE
            "true"
            "rk"
            "ABCDE"
            "BAD-ROUTE-KEY"
          )
        }
        """.formatted(slot5MalformedFence);

    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors());
    assertTrue(result.document().isPresent(), "Spatial fault containment must preserve partial StvnDocument");

    StvnValue payload = result.document().get().payload().orElseThrow();
    StvnValue.StvnTuple tuple = assertInstanceOf(StvnValue.StvnTuple.class, payload);
    assertEquals(14, tuple.elements().size(), "Parent :Tuple must preserve exact M == N == 14 arity");
    assertInstanceOf(StvnValue.StvnErrorValue.class, tuple.elements().get(5),
        "Slot 5 malformed fence must lower to isolated StvnErrorValue sentinel");

    // Zero false arity diagnostics on parent tuple delimiters
    assertFalse(
        result.diagnostics().stream().anyMatch(d -> d.message().contains("Tuple arity mismatch")),
        () -> "Parent tuple must never report arity mismatch, got: " + result.diagnostics()
    );

    // Verify exact span of fence error stops cleanly at fence token without spilling onto neighbors
    int fenceStart = source.indexOf("\"\"\"");
    assertTrue(
        result.diagnostics().stream().anyMatch(d -> d.startOffset() == fenceStart),
        () -> "Malformed fence diagnostic must pin to opening fence offset " + fenceStart + ", got: " + result.diagnostics()
    );

    // Verify Slots 10..13 emit exact semantic diagnostics despite :defs Pass 1 syntax error + Slot 5 malformed fence
    int slot10Start = source.indexOf("\"true\"");
    int slot11Start = source.indexOf("\"rk\"");
    int slot12Start = source.indexOf("\"ABCDE\"");
    int slot13Start = source.indexOf("\"BAD-ROUTE-KEY\"");

    assertTrue(result.diagnostics().stream().anyMatch(d ->
        d.startOffset() == slot10Start && "E028".equals(d.canonicalCode().orElse(null))),
        () -> "Slot 10 ('\"true\"') must emit E028, got: " + result.diagnostics());
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        d.startOffset() == slot11Start && "E012".equals(d.canonicalCode().orElse(null))),
        () -> "Slot 11 ('\"rk\"') must emit E012, got: " + result.diagnostics());
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        d.startOffset() == slot12Start && "E012".equals(d.canonicalCode().orElse(null))),
        () -> "Slot 12 ('\"ABCDE\"') must emit E012, got: " + result.diagnostics());
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        d.startOffset() == slot13Start && "E013".equals(d.canonicalCode().orElse(null))),
        () -> "Slot 13 ('\"BAD-ROUTE-KEY\"') must emit E013, got: " + result.diagnostics());
  }

  @Test
  @DisplayName("TC-GV7-B02: StvnSourceFormatter.tryFormat returns Optional.empty() on malformed fenced strings")
  void testSourceFormatterRejectsMalformedFencedString() {
    String source = """
        {
          :type :String
          :body \"\"\"
            untagged body
          \"\"\"
        }
        """;
    assertTrue(StvnSourceFormatter.tryFormat(source).isEmpty(),
        "StvnSourceFormatter.tryFormat must fail closed on malformed fenced strings");
  }

  @Test
  @DisplayName("TC-GV7-B03: Syntax-damaged :defs declaration poisons its nominal type so :body slot suspends without cascading")
  void testSyntaxDamagedDefsNominalDeclarationPoisonsGracefully() {
    String source = """
        {
          :defs {
            :BrokenWidth { #size } :Int
          }
          :type :Tuple(:BrokenWidth :Boolean)
          :body (
            999999
            "not_bool"
          )
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors());
    assertTrue(result.document().isPresent());
    StvnSchema broken = result.document().get().defs().findType(":BrokenWidth").orElseThrow();
    assertInstanceOf(StvnSchema.PoisonedSchema.class, broken);

    int slot0Start = source.indexOf("999999");
    int slot1Start = source.indexOf("\"not_bool\"");
    assertFalse(result.diagnostics().stream().anyMatch(d -> d.startOffset() == slot0Start),
        "Slot 0 governed by syntax-damaged :BrokenWidth must suspend validation");
    assertTrue(result.diagnostics().stream().anyMatch(d -> d.startOffset() == slot1Start),
        "Healthy sibling Slot 1 must still validate");
  }

  @Test
  @DisplayName("TC-GV7-B04: Malformed fence after bare #preserveIndent pins to fence rather than shifting onto #preserveIndent")
  void testMalformedFenceAfterBarePreserveIndentDoesNotShiftOntoFacetKeyword() {
    String source = """
        {
          :defs {
            :BrokenMeta { #preserveIndent \"\"\"inline\"\"\" } :String
          }
          :type :Int
          :body 1
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    int fenceStart = source.indexOf("\"\"\"inline\"\"\"");
    int preserveStart = source.indexOf("#preserveIndent");
    var fenceDiag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_FENCE_DELIMITER_MISMATCH.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Expected FENCE_DELIMITER_MISMATCH, got: " + result.diagnostics()));
    assertEquals(fenceStart, fenceDiag.startOffset());
    assertNotEquals(preserveStart, fenceDiag.startOffset());
  }

  // =========================================================================
  // Domain C — Mutex Precedence Over Tier Ordering & Temporal STE Prose
  // =========================================================================

  @Test
  @DisplayName("TC-GV7-C01: All 6 mutex groups in forward and reverse sub-tier order emit strictly E008 with zero E032 across :defs, inline :type, and orphan blocks")
  void testAllSixMutexGroupsForwardAndReverseEmitZeroOrderViolations() {
    String defsAndInlineSource = """
        {
          :defs {
            :BaseEnum :Enum [ #A #B #C ]
            :FwdScale { #ms #s } :TimeEpoch
            :RevScale { #s #ms } :TimeEpoch
            :FwdMode { #offset #audited } :DateTime
            :RevMode { #audited #offset } :DateTime
            :FwdFloat { #size 32 #exact } :Float
            :RevFloat { #exact #size 64 } :Float
            :FwdMin { #minIncl 0.0 #minExcl 1.0 } :Float
            :RevMin { #minExcl 0.0 #minIncl 1.0 } :Float
            :FwdMax { #maxIncl 10.0 #maxExcl 5.0 } :Float
            :RevMax { #maxExcl 10.0 #maxIncl 5.0 } :Float
            :FwdFilter { #filterIncl [ #A #B ] #filterExcl [ #B ] } :BaseEnum
            :RevFilter { #filterExcl [ #B ] #filterIncl [ #A #B ] } :BaseEnum
          }
          :type { #s #ms } :TimeEpoch
          :body 1000
        }
        """;

    var result = StvnCompiler.compileToResult(defsAndInlineSource, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors());
    assertFalse(
        result.diagnostics().stream().anyMatch(d ->
            "E032".equals(d.canonicalCode().orElse(null))
                || DiagnosticBag.ERR_FACET_ORDER_VIOLATION.equals(d.errorCode().orElse(null))),
        () -> "Conflicting mutex facets must NEVER stack E032 / ERR_FACET_ORDER_VIOLATION, got: " + result.diagnostics()
    );
    assertEquals(13, result.diagnostics().size(),
        () -> "Expected exactly 13 E008 mutex diagnostics (12 in :defs + 1 inline :type), got: " + result.diagnostics());
    assertTrue(result.diagnostics().stream().allMatch(d -> "E008".equals(d.canonicalCode().orElse(null))));
  }

  @Test
  @DisplayName("TC-GV7-C02: Orphan metadata blocks check all 6 mutex groups before E032 tier ordering")
  void testOrphanMetadataMutexPrecedenceOverOrderViolation() {
    String orphanSource = """
        {
          :type { #s #ms }
          :body 0
        }
        """;
    var result = StvnCompiler.compileToResult(orphanSource, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors());
    assertFalse(
        result.diagnostics().stream().anyMatch(d ->
            DiagnosticBag.ERR_FACET_ORDER_VIOLATION.equals(d.errorCode().orElse(null))),
        () -> "Orphan metadata block with reverse-subtier mutex must not emit E032, got: " + result.diagnostics()
    );
    assertTrue(
        result.diagnostics().stream().anyMatch(d ->
            DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE.equals(d.errorCode().orElse(null))),
        () -> "Orphan metadata block must still report E008 mutex conflict, got: " + result.diagnostics()
    );
  }

  @Test
  @DisplayName("TC-GV7-C03: StvnSourceFormatter stabilizes authored relative order of conflicting mutex facets while sorting non-conflicting facets")
  void testSourceFormatterPreservesAuthoredRelativeOrderOfMutexPeers() {
    String source = """
        {
          :defs {
            :RevEpoch { #minIncl 0 #s #ms } :TimeEpoch
            :RevFloat { #maxIncl 9.0 #exact #size 64 #minExcl 1.0 #minIncl 0.0 } :Float
          }
          :type :Int
          :body 1
        }
        """;
    String formatted = StvnSourceFormatter.format(source);
    assertTrue(formatted.contains(":RevEpoch { #s #ms #minIncl 0 } :TimeEpoch"),
        () -> "Formatter must sort Tier 1 before Tier 3 while keeping '#s #ms' in authored order: " + formatted);
    assertTrue(formatted.contains(":RevFloat { #exact #size 64 #minExcl 1.0 #minIncl 0.0 #maxIncl 9.0 } :Float"),
        () -> "Formatter must preserve '#exact #size 64' and '#minExcl 1.0 #minIncl 0.0' relative order: " + formatted);
    assertEquals(formatted, StvnSourceFormatter.format(formatted), "Formatter must be strictly idempotent");
  }

  @Test
  @DisplayName("TC-GV7-C04: All 4 temporal error contracts emit E008 with exact token spans and single-quoted STE prose")
  void testFourTemporalErrorContractsSingleQuotedSteProse() {
    String source = """
        {
          :defs {
            :MissingScale :TimeEpoch
            :MissingMode :DateTime
            :ConflictScale { #ns #us } :TimeEpoch
            :ConflictMode { #zoned #audited } :DateTime
          }
          :type :Int
          :body 0
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertEquals(4, result.diagnostics().size(), () -> "Got: " + result.diagnostics());

    assertEquals(
        "Temporal primitive ':TimeEpoch' requires exactly one scale facet ('#ns', '#us', '#ms', or '#s')",
        result.diagnostics().get(0).message()
    );
    assertEquals(
        "Temporal primitive ':DateTime' requires exactly one mode facet ('#offset', '#zoned', or '#audited')",
        result.diagnostics().get(1).message()
    );
    assertEquals(
        "Temporal scale facets ('#ns', '#us', '#ms', '#s') are mutually exclusive",
        result.diagnostics().get(2).message()
    );
    assertEquals(
        "Temporal mode facets ('#offset', '#zoned', '#audited') are mutually exclusive",
        result.diagnostics().get(3).message()
    );
  }

  // =========================================================================
  // Domain D — #CONST Nominal Branding & Enum Filter Fallback Integrity
  // =========================================================================

  static Stream<Arguments> provideNominalConstBrandingCases() {
    return Stream.of(
        Arguments.of(
            ":State :Enum [ #CREATED #AUTHORIZED #SETTLED ]\n    :ActiveState { #filterIncl [ #AUTHORIZED #SETTLED ] } :State",
            "#BAD_CONST :ActiveState #CREATED",
            ":ActiveState",
            "#BAD_CONST"
        ),
        Arguments.of(
            ":Color :Enum [ #RED #GREEN ]",
            "#BAD_CONST :Color #BLUE",
            ":Color",
            "#BAD_CONST"
        ),
        Arguments.of(
            ":Port { #unsigned #size 16 #minIncl 1024 } :Int",
            "#BAD_CONST :Port 80",
            ":Port",
            "#BAD_CONST"
        ),
        Arguments.of(
            ":Ratio { #minIncl 0.0 #maxIncl 1.0 } :Float",
            "#BAD_CONST :Ratio 2.5",
            ":Ratio",
            "#BAD_CONST"
        ),
        Arguments.of(
            ":Slug { #minSize 4 #regex \"^[a-z]+$\" } :String",
            "#BAD_CONST :Slug \"ab\"",
            ":Slug",
            "#BAD_CONST"
        ),
        Arguments.of(
            ":PositiveEpoch { #ms #minIncl 1000 } :TimeEpoch",
            "#BAD_CONST :PositiveEpoch 5",
            ":PositiveEpoch",
            "#BAD_CONST"
        ),
        Arguments.of(
            ":RecentDate { #offset #minIncl \"2026-01-01T00:00:00Z\" } :DateTime",
            "#BAD_CONST :RecentDate \"2020-01-01T00:00:00Z\"",
            ":RecentDate",
            "#BAD_CONST"
        ),
        Arguments.of(
            ":PairSeq { #minSize 2 } :Seq(:Int)",
            "#BAD_CONST :PairSeq [ 1 ]",
            ":PairSeq",
            "#BAD_CONST"
        ),
        Arguments.of(
            ":PairSet { #minSize 2 } :Set(:Int)",
            "#BAD_CONST :PairSet [ 1 ]",
            ":PairSet",
            "#BAD_CONST"
        ),
        Arguments.of(
            ":BoundedMap { #maxSize 1 } :Map(:String :Int)",
            "#BAD_CONST :BoundedMap { [ \"a\" 1 ] [ \"b\" 2 ] }",
            ":BoundedMap",
            "#BAD_CONST"
        )
    );
  }

  @ParameterizedTest(name = "TC-GV7-D01: #CONST governed by {2} interpolates nominal type name and never {3}")
  @MethodSource("provideNominalConstBrandingCases")
  @DisplayName("TC-GV7-D01: #CONST declarations governed by nominal types interpolate :TypeName across all 10 schema families")
  void testNominalConstDiagnosticsInterpolateNominalTypeName(
      String typeDefs,
      String constDecl,
      String expectedNominalBrand,
      String prohibitedConstName
  ) {
    String source = """
        {
          :defs {
            %s
            %s
          }
          :type :Int
          :body 1
        }
        """.formatted(typeDefs, constDecl);

    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Invalid constant initializer must fail compilation");
    StvnDiagnostic diag = result.diagnostics().getFirst();
    assertTrue(
        diag.message().contains(expectedNominalBrand),
        () -> "Expected nominal brand '" + expectedNominalBrand + "' in diagnostic message, got: " + diag.message()
    );
    assertFalse(
        diag.message().contains(prohibitedConstName),
        () -> "Diagnostic message must never attribute schema label to '" + prohibitedConstName + "', got: " + diag.message()
    );
  }

  @Test
  @DisplayName("TC-GV7-D02: Anonymous inline #CONST schema continues to interpolate #CONST_NAME fallback label")
  void testAnonymousInlineConstContinuesToInterpolateConstName() {
    String source = """
        {
          :defs {
            #RESTRICTED_PORT { #minIncl 1024 } :Int 80
          }
          :type :Int
          :body 1
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors());
    assertTrue(
        result.diagnostics().getFirst().message().contains("#RESTRICTED_PORT"),
        () -> "Anonymous inline constant schema must retain #RESTRICTED_PORT fallback label, got: " + result.diagnostics()
    );
  }

  @ParameterizedTest(name = "TC-GV7-D03: Enum filter failure {0} preserves full 6-variant parent :SettlementState on PoisonedSchema.bestEffortFallback")
  @ValueSource(strings = {
      "#filterIncl [ #CREATED #AUTHORIZED ] #filterExcl [ #AUTHORIZED ]",
      "#filterIncl [ #CREATED #CREATED ]",
      "#filterIncl [ #CREATED #UNKNOWN_STATE ]",
      "#filterIncl [ #AUTHORIZED #CREATED ]",
      "#filterIncl []",
      "#filterExcl [ #CREATED #AUTHORIZED #CAPTURED #SETTLED #VOIDED #REFUNDED ]"
  })
  @DisplayName("TC-GV7-D03: All enum filter failures preserve uncorrupted parent :SettlementState on grounded PoisonedSchema fallback")
  void testEnumFilterFailuresPreserveUncorruptedParentEnumFallback(String invalidFilterEntry) {
    String source = """
        {
          :defs {
            :SettlementState :Enum [ #CREATED #AUTHORIZED #CAPTURED #SETTLED #VOIDED #REFUNDED ]
            :TrapBrokenFilter { %s } :SettlementState
          }
          :type :Tuple(:TrapBrokenFilter :SettlementState)
          :body (
            #CREATED
            #NOT_A_STATE
          )
        }
        """.formatted(invalidFilterEntry);

    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors());
    StvnDocument doc = result.document().orElseThrow();

    StvnSchema trapSchema = doc.defs().findType(":TrapBrokenFilter").orElseThrow();
    StvnSchema.PoisonedSchema poisoned = assertInstanceOf(StvnSchema.PoisonedSchema.class, trapSchema);
    assertTrue(poisoned.isGrounded(), "Failed enum subset must lower to Case B Grounded PoisonedSchema");

    StvnSchema.EnumSchema parentFallback = assertInstanceOf(
        StvnSchema.EnumSchema.class,
        poisoned.bestEffortFallback().orElseThrow()
    );
    assertEquals(
        List.of("#CREATED", "#AUTHORIZED", "#CAPTURED", "#SETTLED", "#VOIDED", "#REFUNDED"),
        parentFallback.rootVariants(),
        "Fallback must preserve all 6 parent variants without partial truncation"
    );

    // Slot 0 (#CREATED) governed by :TrapBrokenFilter must suspend with 0 errors; Slot 1 (#NOT_A_STATE) must report E028
    int slot0Offset = source.lastIndexOf("#CREATED");
    int slot1Offset = source.indexOf("#NOT_A_STATE");
    assertFalse(result.diagnostics().stream().anyMatch(d -> d.startOffset() == slot0Offset),
        "Body slot governed by :TrapBrokenFilter must suspend validation");
    var slot1Diag = result.diagnostics().stream()
        .filter(d -> d.startOffset() == slot1Offset)
        .findFirst()
        .orElseThrow(() -> new AssertionError("Expected E028 on #NOT_A_STATE, got: " + result.diagnostics()));
    assertEquals("E028", slot1Diag.canonicalCode().orElse(null));
    assertEquals(
        "Invalid enum value for ':SettlementState': variant '#NOT_A_STATE' is not declared; allowed variants: [#CREATED, #AUTHORIZED, #CAPTURED, #SETTLED, #VOIDED, #REFUNDED]",
        slot1Diag.message()
    );
  }

  @Test
  @DisplayName("TC-GV7-D04: :EnumSubset (E012) and :Enum (E028) payload diagnostics emit parallel single-quoted STE prose")
  void testEnumAndEnumSubsetParallelSingleQuotedSteProse() {
    String source = """
        {
          :defs {
            :SettlementState :Enum [ #CREATED #AUTHORIZED #CAPTURED #SETTLED #VOIDED #REFUNDED ]
            :FinalizableState { #filterIncl [ #AUTHORIZED #CAPTURED #SETTLED ] } :SettlementState
          }
          :type :Tuple(:SettlementState :FinalizableState)
          :body (
            #NOT_A_STATE
            #VOIDED
          )
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertEquals(2, result.diagnostics().size());
    assertEquals("E028", result.diagnostics().get(0).canonicalCode().orElse(null));
    assertEquals(
        "Invalid enum value for ':SettlementState': variant '#NOT_A_STATE' is not declared; allowed variants: [#CREATED, #AUTHORIZED, #CAPTURED, #SETTLED, #VOIDED, #REFUNDED]",
        result.diagnostics().get(0).message()
    );
    assertEquals("E012", result.diagnostics().get(1).canonicalCode().orElse(null));
    assertEquals(
        "Invalid enum value: variant '#VOIDED' is not permitted in enum subset ':FinalizableState'; allowed variants: [#AUTHORIZED, #CAPTURED, #SETTLED]",
        result.diagnostics().get(1).message()
    );
  }
}
