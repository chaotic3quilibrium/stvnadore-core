package org.stvnadore.core.printer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.utils.StvnProjectPaths;

/**
 * Conformance suite verifying Laws 1–5 for {@link StvnSourceFormatter},
 * {@link StvnSourceFormatterOptions}, and {@link NestedContainerWrapPolicy}:
 * section-scoped container wrapping, 1-based target-column anchoring across all eight
 * alignable same-line gaps, bidirectional peer-aware trailing {@code //} comment alignment,
 * jammed single-line split safety, and verbatim zero-diff fixed-point acceptance probes.
 */
@NullMarked
class StvnSourceFormatterColumnAlignmentAndWrapPolicyTest {

  private static final String PROBE_1 = """
      {
        // Formatting Probe 1: Tabular Type-Alias Alignment in :defs and :package
        // Target Filename: fmt_probe_01_type_alias_alignment.core_test.stvn
        :meta {
          #name "fmt_probe_01_type_alias_alignment"
          #domain "core_test"
          #kind #BODY
        }

        :defs {
          // Group A: 2-Column Type-Alias Alignment (:Alias -> :TargetType at col 28)
          :Flag                  :Boolean
          :ChannelMode           :Enum[#IDLE #ARMED #ACTIVE]
          :OptionalFlag          :Option(:Flag)

          // Group B: 3-Column Constrained Alignment (:Alias -> '{' at col 28 -> :BaseType at col 69)
          :PosixCode             { #minSize 2 #maxSize 32 }               :String
          :RackSlot              { #unsigned #size 8 #maxExcl 64 }        :Int
          :RailVoltage           { #size 32 #minIncl 0.0 #maxIncl 48.0 }  :Float
          :SampleEpoch           { #ms }                                  :TimeEpoch

          // Group C: Mixed Constrained & Unconstrained Alignment Inside :package + Trailing Comments
          :package :rack/telemetry {
            :RawGain             { #size 32 }                   :Float              // 32-bit sensor gain
            :ReferenceGain                                      :Float              // 64-bit reference gain
            :BoundedGainHistory  { #minSize 1 #maxSize 16 }     :Seq(:RawGain)      // bounded sample window
          }

          :use [ :rack/telemetry { #strip } ]

          :ProbeSnapshot         :Tuple(:PosixCode :RackSlot :RailVoltage :SampleEpoch :ChannelMode :OptionalFlag :ReferenceGain :BoundedGainHistory)
        }

        :type :ProbeSnapshot
        :body ("RK-01" 7 12.5 1795456800123 #ACTIVE #TRUE 1.0 [0.5 0.75])
      }""";

  private static final String PROBE_2 = """
      {
        // Formatting Probe 2: :meta, :use Alias Pairs, #CONST Columns & :Map Entries
        // Target Filename: fmt_probe_02_constants_meta_use_and_maps.core_test.stvn
        :meta {
          #name    "fmt_probe_02_constants_meta_use_and_maps"
          #domain  "core_test"
          #kind    #BODY
        }

        :defs {
          :package :cooling/manifold {
            :CoolantValveMode    :Enum[#CLOSED #THROTTLED #OPEN]
            :ManifoldTempC       { #size 16 #minIncl -60 #maxExcl 150 }  :Int
            :FlowTargetLpm       { #unsigned #size 16 #maxExcl 5000 }    :Int

            // Group A: 3-Column #CONST Alignment (#CONST -> :NominalType -> Value + // Comments)
            #DEFAULT_VALVE_MODE  :CoolantValveMode  #THROTTLED    // startup valve state
            #IDLE_MANIFOLD_C     :ManifoldTempC     18            // standby loop temp
            #PEAK_FLOW_LPM       :FlowTargetLpm     1250          // max pump setpoint
          }

          // Group B: 2-Column :use Alias Alignment (:Orig / #ORIG -> :Local / #LOCAL)
          :use [
            :cooling/manifold {
              :CoolantValveMode    :ValveMode
              :ManifoldTempC       :LoopTempC
              :FlowTargetLpm       :LoopFlowLpm
              #DEFAULT_VALVE_MODE  #INIT_VALVE_MODE
              #IDLE_MANIFOLD_C     #STANDBY_TEMP_C
              #PEAK_FLOW_LPM       #MAX_FLOW_LPM
            }
          ]

          // Group C: 4-Column Mixed Constrained & Unconstrained #CONST Alignment
          #MIN_SAFE_PRESSURE_KPA  { #unsigned #size 16 }  :Int     100
          #MAX_SAFE_PRESSURE_KPA  { #unsigned #size 16 }  :Int     950
          #FALLBACK_RETRY_LIMIT                           :Int     3
          #LOOP_ENABLED_DEFAULT                           :Boolean #TRUE

          :ZoneFlowMap           :Map(:String :LoopFlowLpm)
          :ManifoldReport        :Tuple(:ValveMode :LoopTempC :Int :Boolean :ZoneFlowMap)
        }

        :type :ManifoldReport
        :body (
          #INIT_VALVE_MODE
          #STANDBY_TEMP_C
          #MAX_SAFE_PRESSURE_KPA
          #LOOP_ENABLED_DEFAULT
          // Group D: Multi-Line :Map Entries With Zero Bracket Padding & Aligned Values
          {
            ["zone_a"          120]
            ["zone_north_rack" 480]
            ["zone_cryo_bay"   #MAX_FLOW_LPM]
          }
        )
      }""";

  private static final String PROBE_3 = """
      {
        // Formatting Probe 3: Multi-Line Metadata Facets, Inline Tuple Slot Schemas & Tabular Payloads
        // Target Filename: fmt_probe_03_multiline_facets_and_tabular_body.core_test.stvn
        :meta {
          #name    "fmt_probe_03_multiline_facets_and_tabular_body"
          #domain  "core_test"
          #kind    #BODY
        }

        :defs {
          // Group A: Multi-Line MetadataMap Facet Argument Alignment (#facet -> arg -> // comment)
          :PacketHeaderTag {
            #minSize         4                  // lower length bound
            #maxSize         24                 // upper length bound
            #minIncl         "PKT-0000"         // lexical floor
            #maxIncl         "PKT-ZZZZ"         // lexical ceiling
            #preserveIndent  #TRUE              // verbatim margin
            #regex           "^PKT-[A-Z0-9-]+$" // identifier grammar
          } :String

          :ChannelStatus :Enum[#ONLINE #STANDBY #FAULT]

          // Group B: Multi-Line :Tuple Schema With Aligned Inline Metadata '{ ... } -> :Type' + Comments
          :TelemetryRow :Tuple(
            :PacketHeaderTag                                     // col 1: packet tag
            { #unsigned #size 16 #maxExcl 4096 }  :Int           // col 2: sequence id
            { #size 32 #minIncl 0.0 }             :Float         // col 3: signal snr
            :ChannelStatus                                       // col 4: link status
          )

          :IndexedOutcome :Union(:Int :String :Boolean :ChannelStatus :PacketHeaderTag :Float :Int :Int :Int :Int :Int :String)
          :BatchPayload   :Tuple(:Seq(:TelemetryRow) :Seq(:Either(:String :Int)) :Seq(:IndexedOutcome))
        }

        :type :BatchPayload
        :body (
          // Group C: Tabular :Seq of Single-Line :Tuple Rows in :body
          [
            ("PKT-A1"        7     18.5   #ONLINE)
            ("PKT-NORTH-02"  1024  9.25   #STANDBY)
            ("PKT-CRYO"      88    31.0   #FAULT)
          ]

          // Group D: Multi-Line Sum Variant Tag Alignment (#Left/#Right and #1..#12)
          [
            #Left   "LINK_DROP"
            #Right  200
          ]
          [
            #1   42
            #12  "TWELFTH_BRANCH"
          ]
        )
      }""";

  @Test
  @DisplayName("Law 5: Verbatim Acceptance Probes 1, 2, and 3 are exact zero-diff fixed points under DEFAULT and compile cleanly under STRICT")
  void testAcceptanceProbesZeroDiffFixedPointAndStrictCompilation() {
    record ProbeCase(String fileName, String content) {}

    var cases = List.of(
        new ProbeCase("fmt_probe_01_type_alias_alignment.core_test.stvn", PROBE_1),
        new ProbeCase("fmt_probe_02_constants_meta_use_and_maps.core_test.stvn", PROBE_2),
        new ProbeCase("fmt_probe_03_multiline_facets_and_tabular_body.core_test.stvn", PROBE_3)
    );

    for (var probeCase : cases) {
      Assertions.assertEquals(
          probeCase.content(),
          StvnSourceFormatter.format(probeCase.content()),
          () -> "Expected zero-diff fixed point via format(String) on " + probeCase.fileName()
      );
      Assertions.assertEquals(
          probeCase.content(),
          StvnSourceFormatter.format(probeCase.content(), StvnSourceFormatterOptions.DEFAULT),
          () -> "Expected zero-diff fixed point via format(String, DEFAULT) on " + probeCase.fileName()
      );
      Assertions.assertEquals(
          Optional.of(probeCase.content()),
          StvnSourceFormatter.tryFormat(probeCase.content(), StvnSourceFormatterOptions.DEFAULT),
          () -> "Expected Optional.of(probe) via tryFormat on " + probeCase.fileName()
      );

      var compileRes = StvnCompiler.compileToResult(
          probeCase.content(),
          probeCase.fileName(),
          StvnParserConfig.STRICT
      );
      Assertions.assertTrue(
          compileRes.isSuccess() && !compileRes.hasErrors() && compileRes.diagnostics().isEmpty(),
          () -> "Expected 0 errors and 0 warnings under STRICT for " + probeCase.fileName()
              + ", got: " + compileRes.diagnostics()
      );
    }
  }

  @Test
  @DisplayName("Law 1: Public StvnSourceFormatterOptions defaults, null guards, and overload delegation")
  @SuppressWarnings("NullAway")
  void testPublicOptionsNullGuardsAndOverloadDelegation() {
    Assertions.assertEquals(
        NestedContainerWrapPolicy.PRESERVE_AUTHORED,
        StvnSourceFormatterOptions.DEFAULT.schemaNestedWrapPolicy()
    );
    Assertions.assertEquals(
        NestedContainerWrapPolicy.PRESERVE_AUTHORED,
        StvnSourceFormatterOptions.DEFAULT.payloadNestedWrapPolicy()
    );
    Assertions.assertThrows(
        NullPointerException.class,
        () -> new StvnSourceFormatterOptions(null, NestedContainerWrapPolicy.PRESERVE_AUTHORED)
    );
    Assertions.assertThrows(
        NullPointerException.class,
        () -> new StvnSourceFormatterOptions(NestedContainerWrapPolicy.PRESERVE_AUTHORED, null)
    );
    Assertions.assertThrows(
        NullPointerException.class,
        () -> StvnSourceFormatter.format("{ :type :Int :body 1 }", null)
    );
    Assertions.assertThrows(
        NullPointerException.class,
        () -> StvnSourceFormatter.tryFormat("{ :type :Int :body 1 }", null)
    );
    Assertions.assertEquals(
        Optional.empty(),
        StvnSourceFormatter.tryFormat(null, StvnSourceFormatterOptions.DEFAULT)
    );
    Assertions.assertEquals(
        Optional.empty(),
        StvnSourceFormatter.tryFormat("{ :type :Int", StvnSourceFormatterOptions.DEFAULT)
    );
  }

  @Test
  @DisplayName("Law 2: Four-combination (schemaNestedWrapPolicy, payloadNestedWrapPolicy) matrix across schemas, constants, and payloads")
  void testFourOptionWrapPolicyMatrixOnSchemasAndPayloads() {
    var source = """
        {
          :defs {
            :PosixCode      { #minSize 2 #maxSize 32 }  :String
            :RawGain        { #size 32 }                :Float
            :Flag           :Boolean
            :QubitIndex     { #unsigned #size 8 }       :Int
            :SensorGainCode :Enum[#GAIN_LOW #GAIN_HIGH]
            :FaultTreeLabel :String
            :ExprAst        :Union(:SensorGainCode :Tuple(:FaultTreeLabel :ExprAst :ExprAst))
            :LeafWrappers   :Tuple(:Option(:Flag) :Seq(:RawGain) :Set(:QubitIndex))
            :AtomicMap      :Map(:String :RawGain)
            :NestedMap      :Map(:String :Seq(:RawGain))
            :NestedEither   :Either(:PosixCode :Tuple(:FaultTreeLabel :RawGain))
            #INLINE_ROW     :Tuple(:PosixCode :Seq(:RawGain)) ("RK-01" [0.5 0.75])
          }
          :type :Tuple(:ExprAst :LeafWrappers :AtomicMap :NestedMap :NestedEither)
          :body (#1 #GAIN_LOW (#TRUE [1.0] [0]) {["k" 1.0]} {["m" [1.0 2.0]]} #Right ("ok" 2.5))
        }""";

    var preserveBoth = StvnSourceFormatterOptions.DEFAULT;
    var expandPayloadOnly = new StvnSourceFormatterOptions(
        NestedContainerWrapPolicy.PRESERVE_AUTHORED,
        NestedContainerWrapPolicy.EXPAND_NESTED
    );
    var expandSchemaOnly = new StvnSourceFormatterOptions(
        NestedContainerWrapPolicy.EXPAND_NESTED,
        NestedContainerWrapPolicy.PRESERVE_AUTHORED
    );
    var expandBoth = new StvnSourceFormatterOptions(
        NestedContainerWrapPolicy.EXPAND_NESTED,
        NestedContainerWrapPolicy.EXPAND_NESTED
    );

    // 1. (PRESERVE_AUTHORED, PRESERVE_AUTHORED): exact fixed point
    Assertions.assertEquals(source, StvnSourceFormatter.format(source, preserveBoth));

    // 2. (PRESERVE_AUTHORED, EXPAND_NESTED): schemas stay single-line; #INLINE_ROW RHS and :body expand
    var expectedExpandPayload = """
        {
          :defs {
            :PosixCode      { #minSize 2 #maxSize 32 }  :String
            :RawGain        { #size 32 }                :Float
            :Flag           :Boolean
            :QubitIndex     { #unsigned #size 8 }       :Int
            :SensorGainCode :Enum[#GAIN_LOW #GAIN_HIGH]
            :FaultTreeLabel :String
            :ExprAst        :Union(:SensorGainCode :Tuple(:FaultTreeLabel :ExprAst :ExprAst))
            :LeafWrappers   :Tuple(:Option(:Flag) :Seq(:RawGain) :Set(:QubitIndex))
            :AtomicMap      :Map(:String :RawGain)
            :NestedMap      :Map(:String :Seq(:RawGain))
            :NestedEither   :Either(:PosixCode :Tuple(:FaultTreeLabel :RawGain))
            #INLINE_ROW     :Tuple(:PosixCode :Seq(:RawGain)) (
              "RK-01"
              [0.5 0.75]
            )
          }
          :type :Tuple(:ExprAst :LeafWrappers :AtomicMap :NestedMap :NestedEither)
          :body (
            #1 #GAIN_LOW
            (
              #TRUE
              [1.0]
              [0]
            )
            {
              ["k" 1.0]
            }
            {
              ["m" [1.0 2.0]]
            }
            #Right ("ok" 2.5)
          )
        }""";
    var actualExpandPayload = StvnSourceFormatter.format(source, expandPayloadOnly);
    Assertions.assertEquals(expectedExpandPayload, actualExpandPayload);
    Assertions.assertEquals(expectedExpandPayload, StvnSourceFormatter.format(actualExpandPayload, expandPayloadOnly));

    // 3. (EXPAND_NESTED, PRESERVE_AUTHORED): nested schemas expand while :AtomicMap, :Enum, leaf wrappers, and single-line payloads stay single-line
    var expectedExpandSchema = """
        {
          :defs {
            :PosixCode      { #minSize 2 #maxSize 32 }  :String
            :RawGain        { #size 32 }                :Float
            :Flag           :Boolean
            :QubitIndex     { #unsigned #size 8 }       :Int
            :SensorGainCode :Enum[#GAIN_LOW #GAIN_HIGH]
            :FaultTreeLabel :String
            :ExprAst        :Union(
              :SensorGainCode
              :Tuple(:FaultTreeLabel :ExprAst :ExprAst)
            )
            :LeafWrappers   :Tuple(
              :Option(:Flag)
              :Seq(:RawGain)
              :Set(:QubitIndex)
            )
            :AtomicMap      :Map(:String :RawGain)
            :NestedMap      :Map(
              :String
              :Seq(:RawGain)
            )
            :NestedEither   :Either(
              :PosixCode
              :Tuple(:FaultTreeLabel :RawGain)
            )
            #INLINE_ROW     :Tuple(
              :PosixCode
              :Seq(:RawGain)
            ) ("RK-01" [0.5 0.75])
          }
          :type :Tuple(:ExprAst :LeafWrappers :AtomicMap :NestedMap :NestedEither)
          :body (#1 #GAIN_LOW (#TRUE [1.0] [0]) {["k" 1.0]} {["m" [1.0 2.0]]} #Right ("ok" 2.5))
        }""";
    var actualExpandSchema = StvnSourceFormatter.format(source, expandSchemaOnly);
    Assertions.assertEquals(expectedExpandSchema, actualExpandSchema);
    Assertions.assertEquals(expectedExpandSchema, StvnSourceFormatter.format(actualExpandSchema, expandSchemaOnly));

    // 4. (EXPAND_NESTED, EXPAND_NESTED): both nested schemas and nested payloads expand idempotently
    var actualExpandBoth = StvnSourceFormatter.format(source, expandBoth);
    Assertions.assertEquals(actualExpandBoth, StvnSourceFormatter.format(actualExpandBoth, expandBoth));
    Assertions.assertTrue(actualExpandBoth.contains(":ExprAst        :Union(\n      :SensorGainCode\n      :Tuple(:FaultTreeLabel :ExprAst :ExprAst)\n    )"));
    Assertions.assertTrue(actualExpandBoth.contains("{\n      [\"m\" [1.0 2.0]]\n    }"));
  }

  @Test
  @DisplayName("Law 3 & Law 4: Prefix indentation repair (2 -> 4, 6 -> 4), interior {#unsigned  #size 8} normalization, and bidirectional widest-row 1-space peer // anchoring")
  void testPrefixRepairInteriorFacetNormalizationAndBidirectionalPeerComments() {
    var messyInput = """
        {
          :meta {
              #kind  #BODY          // doc kind
          #domain    "core_test"    // doc domain
            #name    "repair_probe" // doc name (1-space widest peer at col 28)
          }
          :defs {
          :UnderIndented      {#size  8  #unsigned}   :Int  // col 52 comment
              :OverIndented   { #unsigned #size 16 }  :Int  // col 52 comment
            :MultiEnum        :Enum[ // keep multi-line enum with comments
              #ALPHA // first variant
              #BETA  // aligned variant
            ]
          }
          :type   :Tuple(:UnderIndented    :OverIndented   :MultiEnum)
          :body   (7    512   #ALPHA)
        }""";

    var expected = """
        {
          :meta {
            #name    "repair_probe" // doc name (1-space widest peer at col 28)
            #domain  "core_test"    // doc domain
            #kind    #BODY          // doc kind
          }
          :defs {
            :UnderIndented    { #unsigned #size 8 }   :Int  // col 52 comment
            :OverIndented     { #unsigned #size 16 }  :Int  // col 52 comment
            :MultiEnum        :Enum[ // keep multi-line enum with comments
              #ALPHA // first variant
              #BETA  // aligned variant
            ]
          }
          :type   :Tuple(:UnderIndented    :OverIndented   :MultiEnum)
          :body   (7    512   #ALPHA)
        }""";

    var actual = StvnSourceFormatter.format(messyInput);
    Assertions.assertEquals(expected, actual);
    Assertions.assertEquals(expected, StvnSourceFormatter.format(actual));

    for (var line : actual.lines().toList()) {
      if (line.contains("// doc ")) {
        Assertions.assertEquals(28, line.indexOf("//"), "Expected :meta // anchored at col 28: " + line);
      }
      if (line.contains("// col 52 comment")) {
        Assertions.assertEquals(52, line.indexOf("//"), "Expected :defs // anchored at col 52: " + line);
        Assertions.assertEquals(46, line.indexOf(":Int"), "Expected :Int locked at col 46: " + line);
      }
    }
  }

  @Test
  @DisplayName("Law 3 Split-Line Guard & Law 2.2 Fenced-String Guard: Jammed one-liners do not inflate column gaps and fenced strings force multi-line containers")
  void testJammedSplitLineSafetyGuardAndFencedStringContainerGuard() throws IOException {
    var jammed = "{ :meta { #name    \"jam\" #kind    #BODY } :defs { :Flag    :Boolean } :type :Flag :body #TRUE }";
    var expectedJammed = """
        {
          :meta {
            #name "jam"
            #kind #BODY
          }
          :defs {
            :Flag :Boolean
          }
          :type :Flag
          :body #TRUE
        }""";
    Assertions.assertEquals(expectedJammed, StvnSourceFormatter.format(jammed));

    var fencedInTuple = """
        {
          :type :Tuple(:Int :String)
          :body (1 \"\"\"[RAW]
            hello
          [RAW]\"\"\")
        }""";
    var formattedFenced = StvnSourceFormatter.format(fencedInTuple, StvnSourceFormatterOptions.DEFAULT);
    Assertions.assertTrue(
        formattedFenced.contains(":body (\n    1\n    \"\"\"[RAW]"),
        () -> "Expected fenced string inside tupleLiteral to force multi-line container: " + formattedFenced
    );

    var preludePath = StvnProjectPaths.resolve("shared-fixtures", "syntax", "valid", "modules", "prelude.stvn_core.stvn_d");
    var preludeText = Files.readString(preludePath, StandardCharsets.UTF_8);
    Assertions.assertEquals(preludeText, StvnSourceFormatter.format(preludeText, StvnSourceFormatterOptions.DEFAULT) + "\n");
  }
}
