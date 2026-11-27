package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.printer.AstPrettyPrinter;
import org.stvnadore.core.printer.NestedContainerWrapPolicy;
import org.stvnadore.core.printer.StvnSourceFormatter;
import org.stvnadore.core.printer.StvnSourceFormatterOptions;
import org.stvnadore.core.utils.StvnProjectPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Conformance, coordinate-pinning, VOP rebranding, and formatter symmetry regression suite for
 * Gauntlet V11 Batch 3:
 * <ul>
 *   <li>Target 1: Coordinate-pinned {@code E006-NAMESPACE_COLLISION} diagnostics across
 *       {@code :use} ({@code UseElement} and {@code UseMapAlias} RHS spans) and {@code :include}
 *       ({@code IncludeElement} string literal spans) with context-accurate STE prose and zero
 *       {@code [0, EOF)} document-wide spans.</li>
 *   <li>Target 2: Same-scope duplicate {@code :use} package target and duplicate
 *       {@code (LHS, RHS)} alias mapping rejection under {@code E005-DUPLICATE_MODULE_IMPORT},
 *       preserving cross-file diamond {@code :include} convergence and 1-to-many VOP nominal
 *       rebranding.</li>
 *   <li>Target 3: Strict 4-slot bracket symmetry ({@code [}, {@code key}, {@code value},
 *       {@code ]}) on multi-line {@code mapEntry} constructs across {@link StvnSourceFormatter}
 *       and {@link AstPrettyPrinter}.</li>
 * </ul>
 */
@NullMarked
class StvnGauntletV11Batch3UseDiagnosticsAndMapEntryFormatTest {

  @Test
  @DisplayName("Vector 1: Stage 8 dual and 3-way '#strip' collisions pin E006 strictly to offending later UseElement spans with ':use' STE prose")
  void testStage8StripCollisionPinsStrictlyToLaterUseElementWithUseProse() {
    String source = """
        {
          :defs {
            :package :subsea/pump/primary {
              :LaserDriveMa { #unsigned } :Int
              :PumpMode :Enum[#IDLE #BOOST]
            }
            :package :subsea/pump/raman {
              :LaserDriveMa { #unsigned } :Int
              :PumpMode :Enum[#IDLE #BOOST]
            }
            :use [
              :subsea/pump/primary { #strip }
              :subsea/pump/raman { #strip }
            ]
          }
          :type :Int
          :body 42
        }
        """.replace("\r\n", "\n");

    var res = StvnCompiler.compile(source, "stage8_strip_collision.stvn", StvnParserConfig.DEFAULT);
    List<StvnDiagnostic> errors = res.diagnostics().stream()
        .filter(d -> d.severity() == DiagnosticSeverity.ERROR)
        .toList();
    Assertions.assertEquals(1, errors.size(), () -> "Expected strictly 1 grouped E006 diagnostic: " + errors);

    StvnDiagnostic diag = errors.getFirst();
    assertExactSpanAndCodes(
        source,
        diag,
        ":subsea/pump/raman { #strip }",
        "E006",
        DiagnosticBag.ERR_NAMESPACE_COLLISION
    );
    Assertions.assertEquals(
        "Namespace collision(s) in ':use': [:LaserDriveMa, :PumpMode]; replace '#strip' with explicit symbol aliases or rename colliding ':use' aliases",
        diag.message()
    );
    Assertions.assertInstanceOf(NamespaceCollisionException.class, diag.cause());
    Assertions.assertFalse(
        diag.startOffset() == 0 && diag.endOffset() >= source.length() - 2,
        "E006 must never pin across root document [0, EOF)"
    );

    // 3-way #strip collision must emit 1 grouped E006 on 2nd UseElement and 1 grouped E006 on 3rd UseElement
    String threeWay = """
        {
          :defs {
            :package :p/one { :Shared :Int }
            :package :p/two { :Shared :Int }
            :package :p/three { :Shared :Int }
            :use [
              :p/one { #strip }
              :p/two { #strip }
              :p/three { #strip }
            ]
          }
          :type :Int
          :body 1
        }
        """.replace("\r\n", "\n");
    var threeRes = StvnCompiler.compile(threeWay, "three_way_strip.stvn", StvnParserConfig.DEFAULT);
    List<StvnDiagnostic> threeErrors = threeRes.diagnostics().stream()
        .filter(d -> d.severity() == DiagnosticSeverity.ERROR)
        .toList();
    Assertions.assertEquals(2, threeErrors.size(), () -> "Expected 2 offending later UseElement diagnostics: " + threeErrors);
    assertExactSpanAndCodes(threeWay, threeErrors.get(0), ":p/two { #strip }", "E006", DiagnosticBag.ERR_NAMESPACE_COLLISION);
    assertExactSpanAndCodes(threeWay, threeErrors.get(1), ":p/three { #strip }", "E006", DiagnosticBag.ERR_NAMESPACE_COLLISION);
  }

  @Test
  @DisplayName("Vector 2: Explicit UseMapAlias RHS collisions (types, constants, #strip mix, local def clash, and unresolved LHS) pin E006 strictly to offending RHS token")
  void testExplicitUseAliasRhsCollisionsAcrossAndWithinUseElementsAndAgainstLocalDefs() {
    // 2A: Cross-element explicit RHS collision + same-element explicit RHS collision (types & constants)
    String aliasCollision = """
        {
          :defs {
            :package :pkg/alpha {
              :AlphaId :Int
              #ALPHA_LIMIT :Int 10
            }
            :package :pkg/beta {
              :BetaId :String
              :GammaId :Boolean
              #BETA_LIMIT :Int 20
            }
            :use [
              :pkg/alpha {
                :AlphaId :SharedType
                #ALPHA_LIMIT #SHARED_LIMIT
              }
              :pkg/beta {
                :BetaId :SharedType
                #BETA_LIMIT #SHARED_LIMIT
              }
            ]
            :package :pkg/consumer {
              :use [
                :pkg/beta {
                  :BetaId :DupLocal
                  :GammaId :DupLocal
                }
              ]
            }
          }
          :type :Int
          :body 7
        }
        """.replace("\r\n", "\n");

    var res = StvnCompiler.compile(aliasCollision, "rhs_alias_collision.stvn", StvnParserConfig.DEFAULT);
    List<StvnDiagnostic> errors = res.diagnostics().stream()
        .filter(d -> d.severity() == DiagnosticSeverity.ERROR)
        .toList();
    Assertions.assertEquals(3, errors.size(), () -> "Expected 3 RHS-pinned E006 diagnostics: " + errors);

    // Second ':SharedType' (on :BetaId :SharedType)
    int secondSharedTypeIdx = aliasCollision.lastIndexOf(":SharedType");
    Assertions.assertEquals(secondSharedTypeIdx, errors.get(0).startOffset());
    Assertions.assertEquals(secondSharedTypeIdx + ":SharedType".length(), errors.get(0).endOffset());
    Assertions.assertTrue(errors.get(0).message().contains("Namespace collision(s) in ':use': [:SharedType]"));

    // Second '#SHARED_LIMIT' (on #BETA_LIMIT #SHARED_LIMIT)
    int secondSharedConstIdx = aliasCollision.lastIndexOf("#SHARED_LIMIT");
    Assertions.assertEquals(secondSharedConstIdx, errors.get(1).startOffset());
    Assertions.assertEquals(secondSharedConstIdx + "#SHARED_LIMIT".length(), errors.get(1).endOffset());
    Assertions.assertTrue(errors.get(1).message().contains("Namespace collision(s) in ':use': [#SHARED_LIMIT]"));

    // Second ':DupLocal' inside :pkg/consumer
    int secondDupLocalIdx = aliasCollision.lastIndexOf(":DupLocal");
    Assertions.assertEquals(secondDupLocalIdx, errors.get(2).startOffset());
    Assertions.assertEquals(secondDupLocalIdx + ":DupLocal".length(), errors.get(2).endOffset());
    Assertions.assertTrue(errors.get(2).message().contains("Namespace collision(s) in ':use': [:DupLocal]"));

    // 2B: Local declaration colliding with explicit RHS alias pins E006 to UseMapAlias RHS token
    String localVsRhs = """
        {
          :defs {
            :package :pkg/remote { :RemoteMode :Int }
            :PumpMode :String
            :use [ :pkg/remote { :RemoteMode :PumpMode } ]
          }
          :type :PumpMode
          :body "ok"
        }
        """.replace("\r\n", "\n");
    var localRes = StvnCompiler.compile(localVsRhs, "local_vs_rhs.stvn", StvnParserConfig.DEFAULT);
    Assertions.assertEquals(1, localRes.diagnostics().size());
    int useRhsIdx = localVsRhs.indexOf(":PumpMode }", localVsRhs.indexOf(":use"));
    Assertions.assertEquals(useRhsIdx, localRes.diagnostics().getFirst().startOffset());
    Assertions.assertEquals(useRhsIdx + ":PumpMode".length(), localRes.diagnostics().getFirst().endOffset());

    // 2C: Unresolved LHS + valid LHS targeting the same RHS still catches duplicate RHS collision
    String unresolvedLhsDupRhs = """
        {
          :defs {
            :package :pkg/remote { :RealType :Int }
            :use [
              :pkg/remote {
                :MissingType :CollisionAlias
                :RealType :CollisionAlias
              }
            ]
          }
          :type :Int
          :body 1
        }
        """.replace("\r\n", "\n");
    var unresRes = StvnCompiler.compile(unresolvedLhsDupRhs, "unres_rhs_dup.stvn", StvnParserConfig.DEFAULT);
    Assertions.assertEquals(1, unresRes.diagnostics().size(), () -> "Expected E006 on duplicate RHS: " + unresRes.diagnostics());
    int secondCollisionAlias = unresolvedLhsDupRhs.lastIndexOf(":CollisionAlias");
    Assertions.assertEquals(secondCollisionAlias, unresRes.diagnostics().getFirst().startOffset());
    Assertions.assertEquals("E006", unresRes.diagnostics().getFirst().canonicalCode().orElse(null));
  }

  @Test
  @DisplayName("Vector 3: Unpackaged ':include' collisions pin E006 strictly to offending later IncludeElement string literal and preserve ':package' + ':use' prose")
  void testUnpackagedIncludeCollisionPinsStrictlyToLaterIncludeElementStringLiteral(@TempDir Path tempDir) throws IOException {
    String modA = """
        {
          :meta { #name "mod_a" #kind #DEFS }
          :defs {
            :ConflictOne :Int
            :ConflictTwo :String
          }
        }
        """.replace("\r\n", "\n");
    String modB = """
        {
          :meta { #name "mod_b" #kind #DEFS }
          :defs {
            :ConflictOne :Boolean
            :ConflictTwo :Float
          }
        }
        """.replace("\r\n", "\n");
    String mainDoc = """
        {
          :meta { #name "main_inc" #kind #BODY_INCLUDE }
          :defs {
            :include [
              "./mod_a.stvn_d"
              "./mod_b.stvn_d"
            ]
          }
          :type :Int
          :body 42
        }
        """.replace("\r\n", "\n");

    Files.writeString(tempDir.resolve("mod_a.stvn_d"), modA, StandardCharsets.UTF_8);
    Files.writeString(tempDir.resolve("mod_b.stvn_d"), modB, StandardCharsets.UTF_8);
    Path mainPath = tempDir.resolve("main_inc.stvn_i");
    Files.writeString(mainPath, mainDoc, StandardCharsets.UTF_8);

    var res = StvnCompiler.compile(mainDoc, mainPath.toString(), StvnParserConfig.DEFAULT);
    List<StvnDiagnostic> errors = res.diagnostics().stream()
        .filter(d -> d.severity() == DiagnosticSeverity.ERROR)
        .toList();
    Assertions.assertEquals(1, errors.size(), () -> "Expected strictly 1 grouped E006 on ./mod_b.stvn_d: " + errors);

    StvnDiagnostic diag = errors.getFirst();
    assertExactSpanAndCodes(mainDoc, diag, "\"./mod_b.stvn_d\"", "E006", DiagnosticBag.ERR_NAMESPACE_COLLISION);
    Assertions.assertEquals(
        "Namespace collision(s) detected: [:ConflictOne, :ConflictTwo]; enclose module declarations in ':package' and disambiguate local symbols with ':use'",
        diag.message()
    );
  }

  @Test
  @DisplayName("Vector 4: Same-scope duplicate ':use' targets (Case 2A) and duplicate (LHS, RHS) alias pairs (Case 2B-1) emit E005 with DuplicateModuleImportException")
  void testSameScopeDuplicateUseTargetAndDuplicateAliasPairEmitE005() {
    // 4A: Same-list and multi-statement duplicate :use targets in root :defs and :package
    String dupTargetSource = """
        {
          :defs {
            :package :subsea/pump/primary {
              :LaserDriveMa { #unsigned } :Int
            }
            :use [
              :subsea/pump/primary { #strip }
              :subsea/pump/primary { #strip }
            ]
            :use [ :subsea/pump/primary { :LaserDriveMa :AltDrive } ]
          }
          :type :LaserDriveMa
          :body 100
        }
        """.replace("\r\n", "\n");

    var res = StvnCompiler.compile(dupTargetSource, "dup_target.stvn", StvnParserConfig.DEFAULT);
    List<StvnDiagnostic> errors = res.diagnostics().stream()
        .filter(d -> d.severity() == DiagnosticSeverity.ERROR)
        .toList();
    Assertions.assertEquals(2, errors.size(), () -> "Expected 2 E005 diagnostics and 0 cascading E006/E030: " + errors);

    int firstStrip = dupTargetSource.indexOf(":subsea/pump/primary { #strip }");
    int secondStrip = dupTargetSource.indexOf(":subsea/pump/primary { #strip }", firstStrip + 1);
    Assertions.assertEquals(secondStrip, errors.get(0).startOffset());
    Assertions.assertEquals(secondStrip + ":subsea/pump/primary { #strip }".length(), errors.get(0).endOffset());
    Assertions.assertEquals(Optional.of("E005"), errors.get(0).canonicalCode());
    Assertions.assertEquals(Optional.of(DiagnosticBag.ERR_DUPLICATE_MODULE_IMPORT), errors.get(0).symbolicId());
    Assertions.assertInstanceOf(DuplicateModuleImportException.class, errors.get(0).cause());
    Assertions.assertEquals(
        "Duplicate ':use' target ':subsea/pump/primary' in the same scope; consolidate bindings into a single ':use' entry",
        errors.get(0).message()
    );

    assertExactSpanAndCodes(
        dupTargetSource,
        errors.get(1),
        ":subsea/pump/primary { :LaserDriveMa :AltDrive }",
        "E005",
        DiagnosticBag.ERR_DUPLICATE_MODULE_IMPORT
    );

    // Verify StvnSchemaFlattener surfaces DuplicateModuleImportException directly
    Assertions.assertThrows(
        DuplicateModuleImportException.class,
        () -> StvnSchemaFlattener.flatten(Map.of("dup_target.stvn", dupTargetSource), "dup_target.stvn")
    );

    // 4B: Exact duplicate (LHS, RHS) alias pairs (both relative x2 and qualified + relative, plus #CONST)
    String dupAliasPairs = """
        {
          :defs {
            :package :subsea/pump/primary {
              :LaserDriveMa { #unsigned } :Int
              :PumpMode :Enum[#IDLE #BOOST]
              #MAX_DRIVE :Int 500
            }
            :use [
              :subsea/pump/primary {
                :LaserDriveMa :RamanDriveMa
                :LaserDriveMa :RamanDriveMa
                :subsea/pump/primary/PumpMode :LocalMode
                :PumpMode :LocalMode
                #MAX_DRIVE #LOCAL_MAX
                #MAX_DRIVE #LOCAL_MAX
              }
            ]
          }
          :type :Tuple(:RamanDriveMa :LocalMode :Int)
          :body (250 #BOOST #LOCAL_MAX)
        }
        """.replace("\r\n", "\n");

    var pairRes = StvnCompiler.compile(dupAliasPairs, "dup_pairs.stvn", StvnParserConfig.DEFAULT);
    List<StvnDiagnostic> pairErrors = pairRes.diagnostics().stream()
        .filter(d -> d.severity() == DiagnosticSeverity.ERROR)
        .toList();
    Assertions.assertEquals(3, pairErrors.size(), () -> "Expected 3 E005 duplicate-pair errors with 0 E006/E030 cascades: " + pairErrors);
    for (StvnDiagnostic d : pairErrors) {
      Assertions.assertEquals(Optional.of("E005"), d.canonicalCode());
      Assertions.assertEquals(Optional.of(DiagnosticBag.ERR_DUPLICATE_MODULE_IMPORT), d.symbolicId());
      Assertions.assertInstanceOf(DuplicateModuleImportException.class, d.cause());
    }
    int secondRamanPair = dupAliasPairs.lastIndexOf(":LaserDriveMa :RamanDriveMa");
    Assertions.assertEquals(secondRamanPair, pairErrors.get(0).startOffset());
    Assertions.assertEquals(secondRamanPair + ":LaserDriveMa :RamanDriveMa".length(), pairErrors.get(0).endOffset());
    assertExactSpanAndCodes(dupAliasPairs, pairErrors.get(1), ":PumpMode :LocalMode", "E005", DiagnosticBag.ERR_DUPLICATE_MODULE_IMPORT);
    int secondConstPair = dupAliasPairs.lastIndexOf("#MAX_DRIVE #LOCAL_MAX");
    Assertions.assertEquals(secondConstPair, pairErrors.get(2).startOffset());
    Assertions.assertEquals(secondConstPair + "#MAX_DRIVE #LOCAL_MAX".length(), pairErrors.get(2).endOffset());
  }

  @Test
  @DisplayName("Vector 5: 1-to-Many VOP nominal rebranding (with and without #strip), cross-file diamond ':include', and sibling ':package' scopes compile with 0 errors")
  void testOneToManyVopRebrandingAndCrossFileDiamondIncludeConvergenceRemainValid(@TempDir Path tempDir) throws IOException {
    // 5A: 1-to-Many VOP rebranding combined with #strip (Safeguard A: raw :LaserDriveMa evicted while :PrimaryMa & :BackupMa resolve)
    // plus Safeguard B: two sibling :package blocks (including co-named :package :subsea/consumer) each with their own :use scope
    String vopRebrandSource = """
        {
          :defs {
            :package :subsea/pump/primary {
              :LaserDriveMa { #unsigned #size 16 } :Int
              :PumpMode :Enum[#IDLE #BOOST]
              #DEFAULT_MA :LaserDriveMa 300
            }
            :use [
              :subsea/pump/primary { #strip } {
                :LaserDriveMa :PrimaryDriveMa
                :LaserDriveMa :BackupDriveMa
                #DEFAULT_MA #PRIMARY_DEFAULT_MA
                #DEFAULT_MA #BACKUP_DEFAULT_MA
              }
            ]
            :package :subsea/consumer {
              :use [ :subsea/pump/primary { :LaserDriveMa :ConsumerDriveA } ]
              :SlotA :ConsumerDriveA
            }
            :package :subsea/consumer {
              :use [ :subsea/pump/primary { :LaserDriveMa :ConsumerDriveB } ]
              :SlotB :ConsumerDriveB
            }
            :Envelope :Tuple(:PrimaryDriveMa :BackupDriveMa :PumpMode :subsea/consumer/SlotA :subsea/consumer/SlotB)
          }
          :type :Envelope
          :body (#PRIMARY_DEFAULT_MA #BACKUP_DEFAULT_MA #BOOST 110 220)
        }
        """.replace("\r\n", "\n");

    var vopRes = StvnCompiler.compile(vopRebrandSource, "vop_rebrand.stvn", StvnParserConfig.DEFAULT);
    Assertions.assertTrue(vopRes.diagnostics().isEmpty(), () -> "Expected 0 diagnostics on 1-to-many VOP rebranding: " + vopRes.diagnostics());
    var vopDoc = vopRes.document().orElseThrow();
    String prettyVop = AstPrettyPrinter.print(vopDoc);
    Assertions.assertEquals(prettyVop, StvnSourceFormatter.format(prettyVop));

    // Verify Safeguard A: bare :LaserDriveMa was evicted from #strip by the 1-to-many explicit alias block
    String bareEvictedCheck = """
        {
          :defs {
            :package :subsea/pump/primary {
              :LaserDriveMa { #unsigned #size 16 } :Int
            }
            :use [
              :subsea/pump/primary { #strip } {
                :LaserDriveMa :PrimaryDriveMa
                :LaserDriveMa :BackupDriveMa
              }
            ]
          }
          :type :LaserDriveMa
          :body 10
        }
        """.replace("\r\n", "\n");
    var evictedRes = StvnCompiler.compile(bareEvictedCheck, "bare_evicted.stvn", StvnParserConfig.DEFAULT);
    Assertions.assertEquals(1, evictedRes.diagnostics().size());
    Assertions.assertEquals(Optional.of("E030"), evictedRes.diagnostics().getFirst().canonicalCode());

    // 5B: Cross-file diamond :include convergence (A -> B, C -> D) remains 100% valid
    Files.writeString(tempDir.resolve("base_d.stvn_d"), """
        {
          :meta { #name "base_d" #kind #DEFS }
          :defs {
            :package :subsea/shared {
              :TelemetryTick { #ms } :TimeEpoch
            }
            :UnpackagedBase :Int
          }
        }
        """.replace("\r\n", "\n"), StandardCharsets.UTF_8);
    Files.writeString(tempDir.resolve("branch_b.stvn_di"), """
        {
          :meta { #name "branch_b" #kind #DEFS_INCLUDE }
          :defs {
            :include [ "./base_d.stvn_d" ]
            :use [ :subsea/shared { #strip } ]
            :LeftReading :Tuple(:TelemetryTick :UnpackagedBase)
          }
        }
        """.replace("\r\n", "\n"), StandardCharsets.UTF_8);
    Files.writeString(tempDir.resolve("branch_c.stvn_di"), """
        {
          :meta { #name "branch_c" #kind #DEFS_INCLUDE }
          :defs {
            :include [ "./base_d.stvn_d" ]
            :use [ :subsea/shared { #strip } ]
            :RightReading :Tuple(:TelemetryTick :UnpackagedBase)
          }
        }
        """.replace("\r\n", "\n"), StandardCharsets.UTF_8);
    String topA = """
        {
          :meta { #name "top_a" #kind #BODY_INCLUDE }
          :defs {
            :include [
              "./branch_b.stvn_di"
              "./branch_c.stvn_di"
            ]
            :use [ :subsea/shared { #strip } ]
          }
          :type :Tuple(:LeftReading :RightReading :TelemetryTick)
          :body ((1000 1) (2000 2) 3000)
        }
        """.replace("\r\n", "\n");
    Path topPath = tempDir.resolve("top_a.stvn_i");
    Files.writeString(topPath, topA, StandardCharsets.UTF_8);

    var diamondRes = StvnCompiler.compile(topA, topPath.toString(), StvnParserConfig.DEFAULT);
    Assertions.assertTrue(diamondRes.diagnostics().isEmpty(), () -> "Diamond :include convergence must emit 0 diagnostics: " + diamondRes.diagnostics());
  }

  @Test
  @DisplayName("Vector 6: Symmetric multi-line mapEntry bracket wrapping, wrap-policy idempotency, AstPrettyPrinter fixed point, and shared-fixture LF hygiene")
  void testSymmetricMultiLineMapEntryFormattingAndIdempotencyMatrix() throws IOException {
    // 6A: Exact Stage 8 Ctrl+Alt+L bug reproduction & asymmetric single-pass repair
    String stage8Input = """
        {
          :type :Map( :String :String)
          :body {
            [
              "regex"
              \"\"\"[REGEX]
                ^((25[0-5]|(2[0-4]|1[0-9]|[1-9]|)[0-9])\\.?\\b){4}$[REGEX]\"\"\"
            ]
          }
        }""".replace("\r\n", "\n");

    String expectedStage8 = """
        {
          :type :Map(:String :String)
          :body {
            [
              "regex"
              \"\"\"[REGEX]
                ^((25[0-5]|(2[0-4]|1[0-9]|[1-9]|)[0-9])\\.?\\b){4}$[REGEX]\"\"\"
            ]
          }
        }""".replace("\r\n", "\n");

    Assertions.assertEquals(expectedStage8, StvnSourceFormatter.format(stage8Input));
    Assertions.assertEquals(expectedStage8, StvnSourceFormatter.format(expectedStage8));

    // Cramped single-line opening ["regex" """[REGEX]..."""] must also split symmetrically in 1 pass
    String crampedFenced = """
        {
          :type :Map(:String :String)
          :body {
            ["regex" \"\"\"[REGEX]
                ^((25[0-5]|(2[0-4]|1[0-9]|[1-9]|)[0-9])\\.?\\b){4}$[REGEX]\"\"\"]
          }
        }""".replace("\r\n", "\n");
    Assertions.assertEquals(expectedStage8, StvnSourceFormatter.format(crampedFenced));

    // 6B: Authored line breaks at all 3 positions (after '[', between key & val, before ']')
    List<String> authoredBreakVariants = List.of(
        "{\n  :type :Map(:String :Int)\n  :body {\n    [\n      \"alpha\" 1]\n  }\n}",
        "{\n  :type :Map(:String :Int)\n  :body {\n    [\"alpha\"\n      1]\n  }\n}",
        "{\n  :type :Map(:String :Int)\n  :body {\n    [\"alpha\" 1\n    ]\n  }\n}"
    );
    String expectedFourLineScalar = """
        {
          :type :Map(:String :Int)
          :body {
            [
              "alpha"
              1
            ]
          }
        }""".replace("\r\n", "\n");
    for (String variant : authoredBreakVariants) {
      String formatted = StvnSourceFormatter.format(variant);
      Assertions.assertEquals(expectedFourLineScalar, formatted);
      Assertions.assertEquals(expectedFourLineScalar, StvnSourceFormatter.format(formatted));
    }

    // 6C: EXPAND_NESTED wrap policy on nested-map value {["outer" {["inner" 1]}]} vs leaf-list {["m" [1 2]]}
    String singleLineNestedMap = """
        {
          :type :Tuple(:Map(:String :Map(:String :Int)) :Map(:String :Seq(:Int)))
          :body ({["outer" {["inner" 1]}]} {["m" [1 2]]})
        }""".replace("\r\n", "\n");
    var expandPayloadOpts = new StvnSourceFormatterOptions(
        NestedContainerWrapPolicy.PRESERVE_AUTHORED,
        NestedContainerWrapPolicy.EXPAND_NESTED
    );
    String expectedExpandedNestedMap = """
        {
          :type :Tuple(:Map(:String :Map(:String :Int)) :Map(:String :Seq(:Int)))
          :body (
            {
              [
                "outer"
                {
                  ["inner" 1]
                }
              ]
            }
            {
              ["m" [1 2]]
            }
          )
        }""".replace("\r\n", "\n");
    String actualExpanded = StvnSourceFormatter.format(singleLineNestedMap, expandPayloadOpts);
    Assertions.assertEquals(expectedExpandedNestedMap, actualExpanded);
    Assertions.assertEquals(expectedExpandedNestedMap, StvnSourceFormatter.format(actualExpanded, expandPayloadOpts));

    var doc = StvnCompiler.compile(singleLineNestedMap).document().orElseThrow();
    Assertions.assertEquals(expectedExpandedNestedMap, AstPrettyPrinter.print(doc));

    // 6D: Verify pure LF (zero 0x0D CR bytes) across all Batch 3 modified/created shared fixtures
    List<Path> checkedFixtures = List.of(
        StvnProjectPaths.resolve("shared-fixtures", "syntax", "invalid", "modules", "include_unmitigated_raw_collision.contract.stvn_i"),
        StvnProjectPaths.resolve("shared-fixtures", "syntax", "invalid", "modules", "include_unresolved_collision.contract.stvn_i"),
        StvnProjectPaths.resolve("shared-fixtures", "syntax", "invalid", "packages", "use_collision_and_duplicate_scope_errors.core_test.stvn"),
        StvnProjectPaths.resolve("shared-fixtures", "syntax", "invalid", "packages", "use_collision_and_duplicate_scope_errors.contract.stvn_i"),
        StvnProjectPaths.resolve("shared-fixtures", "formatting", "source_pretty", "10_deep_recursive_nesting.core_fmt.stvn_i"),
        StvnProjectPaths.resolve("shared-fixtures", "formatting", "pretty", "10_deep_recursive_nesting.core_fmt.stvn_i"),
        StvnProjectPaths.resolve("shared-fixtures", "formatting", "source_pretty", "12_multiline_parameterized_constructors_depth_fifteen.core_fmt.stvn_i"),
        StvnProjectPaths.resolve("shared-fixtures", "formatting", "pretty", "12_multiline_parameterized_constructors_depth_fifteen.core_fmt.stvn_i"),
        StvnProjectPaths.resolve("shared-fixtures", "formatting", "input", "24_multiline_map_entry_fenced_regex_and_comments.core_fmt.stvn"),
        StvnProjectPaths.resolve("shared-fixtures", "formatting", "source_pretty", "24_multiline_map_entry_fenced_regex_and_comments.core_fmt.stvn"),
        StvnProjectPaths.resolve("shared-fixtures", "formatting", "pretty", "24_multiline_map_entry_fenced_regex_and_comments.core_fmt.stvn"),
        StvnProjectPaths.resolve("shared-fixtures", "formatting", "compact", "24_multiline_map_entry_fenced_regex_and_comments.core_fmt.stvn")
    );
    for (Path path : checkedFixtures) {
      byte[] bytes = Files.readAllBytes(path);
      for (byte b : bytes) {
        Assertions.assertNotEquals((byte) '\r', b, () -> "CR (0x0D) byte prohibited in shared fixture: " + path);
      }
    }
  }

  private static void assertExactSpanAndCodes(
      String source,
      StvnDiagnostic diag,
      String expectedSlice,
      String expectedCanonical,
      String expectedSymbolic
  ) {
    int expectedStart = source.indexOf(expectedSlice);
    Assertions.assertTrue(expectedStart >= 0, "Expected slice not found in source: " + expectedSlice);
    int expectedEnd = expectedStart + expectedSlice.length();
    Assertions.assertEquals(Optional.of(expectedCanonical), diag.canonicalCode(), () -> "Unexpected canonical code: " + diag);
    Assertions.assertEquals(Optional.of(expectedSymbolic), diag.symbolicId(), () -> "Unexpected symbolic ID: " + diag);
    Assertions.assertEquals(expectedStart, diag.startOffset(), () -> "Unexpected startOffset for: " + diag);
    Assertions.assertEquals(expectedEnd, diag.endOffset(), () -> "Unexpected endOffset for: " + diag);
    Assertions.assertEquals(expectedSlice, source.substring(diag.startOffset(), diag.endOffset()));
  }
}
