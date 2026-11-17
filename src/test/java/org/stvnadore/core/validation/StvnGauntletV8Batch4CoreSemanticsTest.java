package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.lowering.StvnDefsLowerer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conformance and regression suite for Gauntlet v8 Batch 4 Pass 2 / Pass 3 core semantics:
 * <ul>
 *   <li>{@code C4-01}: Closed-domain {@code :org/stvnadore/*} {@code :use} validation in modular units when {@code anyIncludeFailed == true}</li>
 *   <li>{@code C4-02}: All-cyclic uninhabited coproduct ({@code :Union}, {@code :Either}) and non-empty collection stem {@code E007} detection vs. {@code TC-03} {@code :Tuple} preservation</li>
 *   <li>{@code C4-03}: Symmetric {@code E007} emission across mutual, multi-hop, inline-facet, packaged, and direct-stem {@code #CONST} cycles (with Safeguards 1 &amp; 2)</li>
 * </ul>
 */
@NullMarked
class StvnGauntletV8Batch4CoreSemanticsTest {

  // =========================================================================
  // Target C4-01 — Closed-Domain :org/stvnadore/* :use Under Failed :include
  // =========================================================================

  @Test
  @DisplayName("TC-GV8-B4-01: Modular .stvn_i and .stvn_di emit E030 on invalid :org/stvnadore/* :use targets even when :include fails, while suppressing non-reserved external targets")
  void testClosedDomainPreludeUseEmitsE030WhenIncludeMissing(@TempDir Path tempDir) {
    Path bodyIncludePath = tempDir.resolve("stage10_probe.core_test.stvn_i");
    String bodyIncludeSource = """
        {
          :meta {
            #name "stage10_probe"
            #domain "core_test"
            #kind #BODY_INCLUDE
          }
          :defs {
            :include [ "missing_10b.core_test.stvn_di" ]
            :use [ :external/unloaded/deep_space { #strip } ]
            :use [ :org/stvnadore_custom/unloaded { #strip } ]
            :use [ :org/stvnadore/prelude/nonexistent { #strip } ]
            :use [ :org/stvnadore { #strip } ]
            :use [ :org/stvnadore/custom { #strip } ]
            :use [ :org/stvnadore/prelude { #strip } ]
          }
          :type :Port
          :body 8080
        }
        """;

    StvnCompilationResult<StvnDocument> res = StvnCompiler.compile(
        bodyIncludeSource,
        bodyIncludePath.toString(),
        StvnParserConfig.DEFAULT
    );
    assertTrue(res.hasErrors(), "Expected E005 + closed-domain E030 errors");

    List<StvnDiagnostic> e030List = res.diagnostics().stream()
        .filter(d -> "E030".equals(d.canonicalCode().orElse(null)))
        .toList();
    assertEquals(3, e030List.size(), () -> "Expected exactly 3 closed-domain E030 diagnostics, got: " + res.diagnostics());

    String bad1 = ":org/stvnadore/prelude/nonexistent";
    int bad1Start = bodyIncludeSource.indexOf(bad1);
    assertEquals(bad1Start, e030List.get(0).startOffset());
    assertEquals(bad1Start + bad1.length(), e030List.get(0).endOffset());
    assertTrue(e030List.get(0).message().contains("'" + bad1 + "'"));

    String bad2 = ":org/stvnadore ";
    int bad2Start = bodyIncludeSource.indexOf(bad2);
    assertEquals(bad2Start, e030List.get(1).startOffset());
    assertEquals(bad2Start + ":org/stvnadore".length(), e030List.get(1).endOffset());

    String bad3 = ":org/stvnadore/custom";
    int bad3Start = bodyIncludeSource.indexOf(bad3);
    assertEquals(bad3Start, e030List.get(2).startOffset());
    assertEquals(bad3Start + bad3.length(), e030List.get(2).endOffset());

    assertFalse(
        res.diagnostics().stream().anyMatch(d -> d.message().contains(":external/unloaded/deep_space")
            || d.message().contains(":org/stvnadore_custom/unloaded")),
        () -> "Non-reserved external packages must stay cascade-suppressed when :include fails: " + res.diagnostics()
    );

    Path defsIncludePath = tempDir.resolve("stage10_defs.core_test.stvn_di");
    String defsIncludeSource = """
        {
          :meta {
            #name "stage10_defs"
            #domain "core_test"
            #kind #DEFS_INCLUDE
          }
          :defs {
            :include [ "missing_10b.core_test.stvn_di" ]
            :use [ :external/unloaded/deep_space { #strip } ]
            :use [ :org/stvnadore/prelude/nonexistent { #strip } ]
            :use [ :org/stvnadore/prelude/meta { #strip } ]
            :KindAlias :StvnDocumentKind
          }
        }
        """;

    StvnCompilationResult<StvnDocument> diRes = StvnCompiler.compile(
        defsIncludeSource,
        defsIncludePath.toString(),
        StvnParserConfig.DEFAULT
    );
    List<StvnDiagnostic> diE030 = diRes.diagnostics().stream()
        .filter(d -> "E030".equals(d.canonicalCode().orElse(null)))
        .toList();
    assertEquals(1, diE030.size(), () -> "Expected 1 closed-domain E030 in .stvn_di, got: " + diRes.diagnostics());
    assertTrue(diE030.get(0).message().contains("':org/stvnadore/prelude/nonexistent'"));
  }

  @Test
  @DisplayName("TC-GV8-B4-02: Duplicate, payload-kind, and cyclic :include failures still enforce closed-domain :org/stvnadore/* :use E030")
  void testClosedDomainPreludeUseAcrossDuplicatePayloadAndCyclicIncludes(@TempDir Path tempDir) throws IOException {
    Path validDefs = tempDir.resolve("net_defs.core_test.stvn_d");
    Files.writeString(validDefs, """
        {
          :meta {
            #name "net_defs"
            #domain "core_test"
            #kind #DEFS
          }
          :defs {
            :package :acme/net {
              :Endpoint :String
            }
          }
        }
        """);

    // 1. Duplicate :include (E005)
    Path dupPath = tempDir.resolve("dup_inc.core_test.stvn_i");
    String dupSource = """
        {
          :meta {
            #name "dup_inc"
            #domain "core_test"
            #kind #BODY_INCLUDE
          }
          :defs {
            :include [ "net_defs.core_test.stvn_d" "net_defs.core_test.stvn_d" ]
            :use [ :external/unloaded/deep_space { #strip } ]
            :use [ :org/stvnadore/prelude/nonexistent { #strip } ]
          }
          :type :Int
          :body 1
        }
        """;
    var dupRes = StvnCompiler.compile(dupSource, dupPath.toString(), StvnParserConfig.DEFAULT);
    assertEquals(1, dupRes.diagnostics().stream().filter(d -> "E030".equals(d.canonicalCode().orElse(null))).count());

    // 2. Payload-kind :include (E005)
    Path payloadDoc = tempDir.resolve("payload_only.core_test.stvn");
    Files.writeString(payloadDoc, """
        {
          :meta {
            #name "payload_only"
            #domain "core_test"
            #kind #BODY
          }
          :type :Int
          :body 42
        }
        """);
    Path importPayloadPath = tempDir.resolve("imp_payload.core_test.stvn_i");
    String importPayloadSource = """
        {
          :meta {
            #name "imp_payload"
            #domain "core_test"
            #kind #BODY_INCLUDE
          }
          :defs {
            :include [ "payload_only.core_test.stvn" ]
            :use [ :external/unloaded/deep_space { #strip } ]
            :use [ :org/stvnadore/prelude/nonexistent { #strip } ]
          }
          :type :Int
          :body 1
        }
        """;
    var payloadIncRes = StvnCompiler.compile(importPayloadSource, importPayloadPath.toString(), StvnParserConfig.DEFAULT);
    assertEquals(1, payloadIncRes.diagnostics().stream().filter(d -> "E030".equals(d.canonicalCode().orElse(null))).count());

    // 3. Cyclic :include (E007)
    Path cycA = tempDir.resolve("cyc_a.core_test.stvn_di");
    Path cycB = tempDir.resolve("cyc_b.core_test.stvn_di");
    String cycASource = """
        {
          :meta {
            #name "cyc_a"
            #domain "core_test"
            #kind #DEFS_INCLUDE
          }
          :defs {
            :include [ "cyc_b.core_test.stvn_di" ]
            :use [ :external/unloaded/deep_space { #strip } ]
            :use [ :org/stvnadore/prelude/nonexistent { #strip } ]
            :A :Int
          }
        }
        """;
    String cycBSource = """
        {
          :meta {
            #name "cyc_b"
            #domain "core_test"
            #kind #DEFS_INCLUDE
          }
          :defs {
            :include [ "cyc_a.core_test.stvn_di" ]
            :B :Int
          }
        }
        """;
    Files.writeString(cycA, cycASource);
    Files.writeString(cycB, cycBSource);
    var cycRes = StvnCompiler.compile(cycASource, cycA.toString(), StvnParserConfig.DEFAULT);
    assertEquals(1, cycRes.diagnostics().stream().filter(d -> "E030".equals(d.canonicalCode().orElse(null))).count());
  }

  @Test
  @DisplayName("TC-GV8-B4-03: Trailing-slash and Pass 1 malformed :use targets emit only E002 and never stack E030 or E035")
  void testSyntacticUseTargetGuardPreventsStackingE030OrE035(@TempDir Path tempDir) {
    Path modPath = tempDir.resolve("syntax_guard.core_test.stvn_i");
    String modSource = """
        {
          :meta {
            #name "syntax_guard"
            #domain "core_test"
            #kind #BODY_INCLUDE
          }
          :defs {
            :include [ "missing.core_test.stvn_di" ]
            :use [ :org/stvnadore/prelude/nonexistent/ { #strip } ]
            :use [ : { #strip } ]
          }
          :type :Int
          :body 7
        }
        """;
    var res = StvnCompiler.compile(modSource, modPath.toString(), StvnParserConfig.DEFAULT);
    assertFalse(
        res.diagnostics().stream().anyMatch(d ->
            "E030".equals(d.canonicalCode().orElse(null)) || "E035".equals(d.canonicalCode().orElse(null))),
        () -> "Trailing slash and bare-colon :use targets must never stack E030 or E035: " + res.diagnostics()
    );
    assertTrue(res.diagnostics().stream().anyMatch(d -> "E002".equals(d.canonicalCode().orElse(null))));
  }

  @Test
  @DisplayName("TC-GV8-B4-04: System authority suppresses cascading E030 on :org/stvnadore/ir under failed :include, while virtual workspace enforces C4-01")
  void testSystemAuthorityIrCascadeSuppressionAndVirtualWorkspaceC401(@TempDir Path tempDir) {
    Path sysPath = tempDir.resolve("sys_probe.stvn_ir.stvn_i");
    String sysSource = """
        {
          :meta {
            #name "sys_probe"
            #domain "stvn_ir"
            #kind #BODY_INCLUDE
          }
          :defs {
            :include [ "missing_substrate.stvn_ir.stvn_d" ]
            :use [ :org/stvnadore/ir { #strip } ]
          }
          :type :Int
          :body 1
        }
        """;
    var sysRes = StvnCompiler.compile(sysSource, sysPath.toString(), StvnParserConfig.SYSTEM);
    assertFalse(
        sysRes.diagnostics().stream().anyMatch(d -> "E030".equals(d.canonicalCode().orElse(null))),
        () -> "System authority must suppress cascading E030 on :org/stvnadore/ir when :include fails: " + sysRes.diagnostics()
    );

    String virtRoot = "/virtual/mod_root.core_test.stvn_di";
    String virtSource = """
        {
          :meta {
            #name "mod_root"
            #domain "core_test"
            #kind #DEFS_INCLUDE
          }
          :defs {
            :include [ "missing_virt.core_test.stvn_di" ]
            :use [ :external/unloaded/deep_space { #strip } ]
            :use [ :org/stvnadore/prelude/nonexistent { #strip } ]
            :Val :Int
          }
        }
        """;
    var docCtx = StvnDefsLowerer.parseDocument(virtSource, StvnParserConfig.DEFAULT);
    DiagnosticBag bag = new DiagnosticBag(32, virtRoot);
    StvnDefsLowerer.lowerForFlattener(docCtx, virtRoot, Map.of(virtRoot, virtSource), bag);
    List<StvnDiagnostic> virtE030 = bag.toList().stream()
        .filter(d -> "E030".equals(d.canonicalCode().orElse(null)))
        .toList();
    assertEquals(1, virtE030.size(), () -> "Virtual workspace must emit closed-domain E030: " + bag.toList());
    assertTrue(virtE030.get(0).message().contains("':org/stvnadore/prelude/nonexistent'"));
  }

  // =========================================================================
  // Target C4-02 — All-Cyclic Coproducts, Non-Empty Collection Stems & TC-03
  // =========================================================================

  @Test
  @DisplayName("TC-GV8-B4-05: All-cyclic :Union and :Either stems emit E007 and lower to Case A Ungrounded PoisonedSchema even when cyclicTypes is empty")
  void testAllCyclicUnionAndEitherStemsEmitE007() {
    String source = """
        {
          :defs {
            :CycleAlpha :CycleBeta
            :CycleBeta :CycleAlpha
            :AllCyclicEither :Either(:CycleAlpha :CycleBeta)
            :AllCyclicUnion :Union(:CycleAlpha :CycleBeta :AllCyclicEither)
          }
          :type :AllCyclicUnion
          :body "suspended-payload"
        }
        """;
    var res = StvnCompiler.compile(source);
    List<StvnDiagnostic> e007 = res.diagnostics().stream()
        .filter(d -> "E007".equals(d.canonicalCode().orElse(null)))
        .toList();
    assertEquals(4, e007.size(), () -> "Expected 4 E007 diagnostics (2 alias cycle + 2 coproduct stems), got: " + res.diagnostics());
    assertEquals(4, res.diagnostics().size(), () -> "Root :body must suspend cleanly on poisoned :AllCyclicUnion: " + res.diagnostics());
    assertTrue(e007.get(2).message().contains(":AllCyclicEither -> :CycleAlpha -> :CycleBeta -> :CycleAlpha"));
    assertTrue(e007.get(3).message().contains(":AllCyclicUnion -> :CycleAlpha -> :CycleBeta -> :CycleAlpha"));

    StvnDocument doc = res.document().orElseThrow();
    StvnSchema unionIr = doc.defs().findType(":AllCyclicUnion").orElseThrow();
    StvnSchema.PoisonedSchema ps = assertInstanceOf(StvnSchema.PoisonedSchema.class, unionIr);
    assertTrue(ps.isUngrounded(), "All-cyclic coproduct stem must lower to Case A Ungrounded PoisonedSchema");
    assertTrue(ps.bestEffortFallback().isEmpty());
  }

  @Test
  @DisplayName("TC-GV8-B4-06: Non-empty collection stems (minSize >= 1) over circular types emit E007 and lower to Case A Ungrounded PoisonedSchema")
  void testNonEmptyCollectionStemsOverCircularTypesEmitE007() {
    String source = """
        {
          :defs {
            :CycleAlpha :CycleBeta
            :CycleBeta :CycleAlpha
            :UnguardedTupleCycle :Tuple(:Int :UnguardedTupleCycle)
            :ReqSeq { #minSize 1 } :Seq(:CycleAlpha)
            :ReqSet { #minSize 2 } :Set(:UnguardedTupleCycle)
            :ReqMapVal { #minSize 1 } :Map(:String :CycleAlpha)
            :ReqMapKey { #minSize 1 } :Map(:CycleAlpha :String)
          }
          :type :Tuple(:ReqSeq :ReqSet :ReqMapVal :ReqMapKey :Int)
          :body ( "s1" "s2" "s3" "s4" "bad-int" )
        }
        """;
    var res = StvnCompiler.compile(source);
    List<StvnDiagnostic> e007 = res.diagnostics().stream()
        .filter(d -> "E007".equals(d.canonicalCode().orElse(null)))
        .toList();
    assertEquals(7, e007.size(), () -> "Expected 7 E007 diagnostics, got: " + res.diagnostics());

    List<StvnDiagnostic> e028 = res.diagnostics().stream()
        .filter(d -> "E028".equals(d.canonicalCode().orElse(null)))
        .toList();
    assertEquals(1, e028.size(), () -> "Only the 5th healthy :Int tuple slot should emit E028: " + res.diagnostics());

    StvnDocument doc = res.document().orElseThrow();
    for (String stemName : List.of(":ReqSeq", ":ReqSet", ":ReqMapVal", ":ReqMapKey")) {
      StvnSchema.PoisonedSchema ps = assertInstanceOf(
          StvnSchema.PoisonedSchema.class,
          doc.defs().findType(stemName).orElseThrow()
      );
      assertTrue(ps.isUngrounded(), stemName + " must be Case A Ungrounded PoisonedSchema");
    }
  }

  @Test
  @DisplayName("TC-GV8-B4-07: Forward-referenced all-cyclic coproducts and non-empty sets converge deterministically regardless of declaration order")
  void testForwardReferencedAllCyclicStemsDeclarationOrderIndependence() {
    String source = """
        {
          :defs {
            :DerivedStem :ForwardCyclicUnion
            :ForwardSet { #minSize 1 } :Set(:ForwardCyclicUnion)
            :ForwardCyclicUnion :Union(:CycleAlpha :UnguardedFloatTupleCycle)
            :UnguardedFloatTupleCycle :Tuple(:Float :UnguardedFloatTupleCycle)
            :CycleAlpha :CycleBeta
            :CycleBeta :CycleAlpha
          }
          :type :DerivedStem
          :body "suspended"
        }
        """;
    var res = StvnCompiler.compile(source);
    List<StvnDiagnostic> e007 = res.diagnostics().stream()
        .filter(d -> "E007".equals(d.canonicalCode().orElse(null)))
        .toList();
    assertEquals(6, e007.size(), () -> "All 6 circular types/stems must emit E007 in forward-ref order: " + res.diagnostics());
    assertFalse(
        res.diagnostics().stream().anyMatch(d -> "E011".equals(d.canonicalCode().orElse(null))),
        () -> "Forward-referenced :Set(:ForwardCyclicUnion) must not emit false E011 trait violation: " + res.diagnostics()
    );
  }

  @Test
  @DisplayName("TC-GV8-B4-08: Guarded :Option, minSize == 0 collections, partially inhabited sums, and non-circular errors never emit false E007")
  void testGuardedInhabitedAndNonCircularFailuresNeverEmitFalseE007() {
    String source = """
        {
          :defs {
            :CycleAlpha :CycleBeta
            :CycleBeta :CycleAlpha

            :OptCycle :Option(:CycleAlpha)
            :ZeroSeq :Seq(:CycleAlpha)
            :ZeroSet :Set(:CycleAlpha)
            :ZeroMap :Map(:CycleAlpha :CycleBeta)
            :HealthyUnion :Union(:Int :CycleAlpha)
            :HealthyEither :Either(:CycleAlpha :String)

            :BrokenUndef :NoSuchType
            :MixedErrorUnion :Union(:CycleAlpha :BrokenUndef)
            :MixedErrorEither :Either(:CycleAlpha :TimeEpoch)
            :NonEmptyUndefSeq { #minSize 1 } :Seq(:BrokenUndef)
            :OneBranchUnion :Union(:CycleAlpha)
            :EmptyEnum :Enum[]
          }
          :type :Tuple(:OptCycle :ZeroSeq :ZeroSet :ZeroMap :HealthyUnion :HealthyEither)
          :body ( #None [] [] {} 42 #Right "ok" )
        }
        """;
    var res = StvnCompiler.compile(source);
    List<StvnDiagnostic> e007 = res.diagnostics().stream()
        .filter(d -> "E007".equals(d.canonicalCode().orElse(null)))
        .toList();
    assertEquals(2, e007.size(), () -> "Strictly :CycleAlpha and :CycleBeta may emit E007, got: " + e007);
    assertTrue(res.diagnostics().stream().anyMatch(d -> "E030".equals(d.canonicalCode().orElse(null))));
    assertTrue(res.diagnostics().stream().anyMatch(d -> "E008".equals(d.canonicalCode().orElse(null))));
    assertEquals(2, res.diagnostics().stream().filter(d -> "E010".equals(d.canonicalCode().orElse(null))).count());
  }

  @Test
  @DisplayName("TC-GV8-B4-09: Acyclic :Tuple containers of circular types never emit E007 and preserve TupleSchema for TC-03 per-slot isolation")
  void testAcyclicTuplePreservesTupleSchemaAndPerSlotBodyIsolation() {
    String source = """
        {
          :defs {
            :MetricWeight { #unsigned #size 16 #minIncl 10 #maxExcl 100 } :Int
            :CycleAlpha :CycleBeta
            :CycleBeta :CycleAlpha
            :UnguardedTupleCycle :Tuple(:MetricWeight :UnguardedTupleCycle)
            :PairOfCycles :Tuple(:CycleAlpha :UnguardedTupleCycle)
            :Matrix :Tuple(:PairOfCycles :CycleAlpha :MetricWeight)
          }
          :type :Matrix
          :body ( ( "ignored-1" "ignored-2" ) "ignored-3" 5 )
        }
        """;
    var res = StvnCompiler.compile(source);
    List<StvnDiagnostic> e007 = res.diagnostics().stream()
        .filter(d -> "E007".equals(d.canonicalCode().orElse(null)))
        .toList();
    assertEquals(3, e007.size(), () -> "Only :CycleAlpha, :CycleBeta, and :UnguardedTupleCycle may emit E007: " + e007);

    StvnDocument doc = res.document().orElseThrow();
    assertInstanceOf(StvnSchema.TupleSchema.class, doc.defs().findType(":PairOfCycles").orElseThrow());
    assertInstanceOf(StvnSchema.TupleSchema.class, doc.defs().findType(":Matrix").orElseThrow());

    List<StvnDiagnostic> e012 = res.diagnostics().stream()
        .filter(d -> "E012".equals(d.canonicalCode().orElse(null)))
        .toList();
    assertEquals(1, e012.size(), () -> "Healthy sibling :MetricWeight slot must validate and emit E012 on 5: " + res.diagnostics());
  }

  // =========================================================================
  // Target C4-03 — Symmetric #CONST Cycles, Stems & Safeguards 1–2
  // =========================================================================

  @Test
  @DisplayName("TC-GV8-B4-10: Mutual 2-node and multi-hop 3-node #CONST cycles emit E007 symmetrically once per ring member with rotated traces")
  void testSymmetricMutualAndThreeWayConstantCycles() {
    String source = """
        {
          :defs {
            #SELF_CONST :Int #SELF_CONST
            #CONST_PING :Int #CONST_PONG
            #CONST_PONG :Int #CONST_PING
            #RING_A :Int #RING_B
            #RING_B :Int #RING_C
            #RING_C :Int #RING_A
          }
          :type :Tuple(:Int :Int :Int)
          :body ( #SELF_CONST #CONST_PONG #RING_C )
        }
        """;
    var res = StvnCompiler.compile(source);
    List<StvnDiagnostic> diags = res.diagnostics();
    assertEquals(6, diags.size(), () -> "Expected exactly 6 E007 diagnostics and 0 E028 cascades: " + diags);
    assertTrue(diags.stream().allMatch(d -> "E007".equals(d.canonicalCode().orElse(null))));

    assertTrue(diags.get(0).message().contains("#SELF_CONST -> #SELF_CONST"));
    assertTrue(diags.get(1).message().contains("#CONST_PING -> #CONST_PONG -> #CONST_PING"));
    assertTrue(diags.get(2).message().contains("#CONST_PONG -> #CONST_PING -> #CONST_PONG"));
    assertTrue(diags.get(3).message().contains("#RING_A -> #RING_B -> #RING_C -> #RING_A"));
    assertTrue(diags.get(4).message().contains("#RING_B -> #RING_C -> #RING_A -> #RING_B"));
    assertTrue(diags.get(5).message().contains("#RING_C -> #RING_A -> #RING_B -> #RING_C"));
  }

  @Test
  @DisplayName("TC-GV8-B4-11: Pre-ring and post-ring direct #STEM_CONST aliases emit E007 cleanly without polluting ring traces or cascading E028")
  void testPreAndPostRingDirectConstantStemsEmitE007WithoutPollutingRingTraces() {
    String source = """
        {
          :defs {
            #PRE_STEM :String #CONST_PING
            #CONST_PING :Int #CONST_PONG
            #CONST_PONG :Int #CONST_PING
            #POST_STEM :Boolean #CONST_PONG
            #POST_STEM_HOP2 :String #POST_STEM
          }
          :type :Tuple(:String :Boolean :String)
          :body ( #PRE_STEM #POST_STEM #POST_STEM_HOP2 )
        }
        """;
    var res = StvnCompiler.compile(source);
    List<StvnDiagnostic> diags = res.diagnostics();
    assertEquals(5, diags.size(), () -> "Expected 5 E007 diagnostics (2 ring + 3 stems) and 0 E028 errors: " + diags);
    assertTrue(diags.stream().allMatch(d -> "E007".equals(d.canonicalCode().orElse(null))));

    // Because #PRE_STEM triggers ring discovery first, ring diagnostics emit first, then #PRE_STEM, #POST_STEM, #POST_STEM_HOP2
    assertTrue(diags.stream().anyMatch(d -> d.message().equals(
        "Circular constant definition detected: #CONST_PING -> #CONST_PONG -> #CONST_PING")));
    assertTrue(diags.stream().anyMatch(d -> d.message().equals(
        "Circular constant definition detected: #CONST_PONG -> #CONST_PING -> #CONST_PONG")));
    assertTrue(diags.stream().anyMatch(d -> d.message().equals(
        "Circular constant definition detected: #PRE_STEM -> #CONST_PING -> #CONST_PONG -> #CONST_PING")));
    assertTrue(diags.stream().anyMatch(d -> d.message().equals(
        "Circular constant definition detected: #POST_STEM -> #CONST_PONG -> #CONST_PING -> #CONST_PONG")));
    assertTrue(diags.stream().anyMatch(d -> d.message().equals(
        "Circular constant definition detected: #POST_STEM_HOP2 -> #POST_STEM -> #CONST_PONG -> #CONST_PING -> #CONST_PONG")));
  }

  @Test
  @DisplayName("TC-GV8-B4-12: Inline metadata facet #CONST cycles, packaged #CONST cycles, and poisoned-schema #CONST cycles emit E007 symmetrically")
  void testInlineFacetPackagedAndPoisonedSchemaConstantCycles() {
    String source = """
        {
          :defs {
            :CycleAlpha :CycleBeta
            :CycleBeta :CycleAlpha

            #FACET_A { #minIncl #FACET_B } :Int 10
            #FACET_B { #minIncl #FACET_A } :Int 20

            #POISON_TYPE_X :CycleAlpha #POISON_TYPE_Y
            #POISON_TYPE_Y :CycleAlpha #POISON_TYPE_X

            :package :acme/cfg {
              #PKG_A :Int #PKG_B
              #PKG_B :Int #PKG_A
            }
          }
          :type :Int
          :body 1
        }
        """;
    var res = StvnCompiler.compile(source);
    List<StvnDiagnostic> diags = res.diagnostics();
    assertEquals(8, diags.size(), () -> "Expected 2 type E007 + 6 constant E007 diagnostics, got: " + diags);
    assertTrue(diags.stream().allMatch(d -> "E007".equals(d.canonicalCode().orElse(null))));

    assertTrue(diags.stream().anyMatch(d -> d.message().contains("#FACET_A -> #FACET_B -> #FACET_A")));
    assertTrue(diags.stream().anyMatch(d -> d.message().contains("#FACET_B -> #FACET_A -> #FACET_B")));
    assertTrue(diags.stream().anyMatch(d -> d.message().contains("#POISON_TYPE_X -> #POISON_TYPE_Y -> #POISON_TYPE_X")));
    assertTrue(diags.stream().anyMatch(d -> d.message().contains("#POISON_TYPE_Y -> #POISON_TYPE_X -> #POISON_TYPE_Y")));
    assertTrue(diags.stream().anyMatch(d -> d.message().contains("#acme/cfg/PKG_A -> #acme/cfg/PKG_B -> #acme/cfg/PKG_A")));
    assertTrue(diags.stream().anyMatch(d -> d.message().contains("#acme/cfg/PKG_B -> #acme/cfg/PKG_A -> #acme/cfg/PKG_B")));
  }

  @Test
  @DisplayName("TC-GV8-B4-13: Safeguard 1 — Undefined constant reference in #CONST emits E030 strictly once (and 0 E030 when constSchema is poisoned)")
  void testSafeguard1ZeroDuplicateE030OnUndefinedConstantInConstDef() {
    String source = """
        {
          :defs {
            :CycleAlpha :CycleBeta
            :CycleBeta :CycleAlpha
            #MY_CONST :Int #DOES_NOT_EXIST
            #POISONED_SCHEMA_CONST :CycleAlpha #ALSO_DOES_NOT_EXIST
          }
          :type :Int
          :body 1
        }
        """;
    var res = StvnCompiler.compile(source);
    List<StvnDiagnostic> e030 = res.diagnostics().stream()
        .filter(d -> "E030".equals(d.canonicalCode().orElse(null)))
        .toList();
    assertEquals(1, e030.size(), () -> "Safeguard 1: #DOES_NOT_EXIST must emit E030 strictly once, got: " + res.diagnostics());
    assertTrue(e030.get(0).message().contains("#DOES_NOT_EXIST"));
    assertFalse(
        res.diagnostics().stream().anyMatch(d -> d.message().contains("#ALSO_DOES_NOT_EXIST")),
        () -> "Poisoned-schema constant must not emit spurious E030 on #ALSO_DOES_NOT_EXIST: " + res.diagnostics()
    );
  }

  @Test
  @DisplayName("TC-GV8-B4-14: Repeated nominal types referencing a memoized poisoned #CONST (and composite poisoned #CONST) lower to Grounded PoisonedSchema")
  void testRepeatedNominalTypesReferencingPoisonedConstLowerToGroundedPoisonedSchema() {
    String source = """
        {
          :defs {
            #CONST_PING :Int #CONST_PONG
            #CONST_PONG :Int #CONST_PING
            #TUP_POISON :Tuple(:Int :Int) ( 1 #CONST_PING )
            :BoundedFirst { #minIncl #CONST_PING } :Int
            :BoundedSecond { #minIncl #CONST_PING } :Int
          }
          :type :Tuple(:BoundedFirst :BoundedSecond :Tuple(:Int :Int))
          :body ( "suspended-first" "suspended-second" #TUP_POISON )
        }
        """;
    var res = StvnCompiler.compile(source);
    assertEquals(2, res.diagnostics().size(), () -> "Only #CONST_PING and #CONST_PONG should emit E007: " + res.diagnostics());

    StvnDocument doc = res.document().orElseThrow();
    StvnSchema.PoisonedSchema p1 = assertInstanceOf(
        StvnSchema.PoisonedSchema.class,
        doc.defs().findType(":BoundedFirst").orElseThrow()
    );
    StvnSchema.PoisonedSchema p2 = assertInstanceOf(
        StvnSchema.PoisonedSchema.class,
        doc.defs().findType(":BoundedSecond").orElseThrow()
    );
    assertTrue(p1.isGrounded(), ":BoundedFirst must be Case B Grounded PoisonedSchema");
    assertTrue(p2.isGrounded(), ":BoundedSecond must also be Case B Grounded PoisonedSchema");
    assertFalse(doc.defs().constants().containsKey("#TUP_POISON"), "Composite constant holding StvnErrorValue must be excluded from healthy catalog");
  }
}
