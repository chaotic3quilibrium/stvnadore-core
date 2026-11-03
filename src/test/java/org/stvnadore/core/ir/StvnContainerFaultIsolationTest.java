package org.stvnadore.core.ir;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.validation.DiagnosticBag;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test suite for container cardinality fault isolation ({@code M == N}).
 * <p>
 * Verifies that lowering failures on child elements produce {@link StvnValue.StvnErrorValue}
 * placeholder nodes within containers without collapsing container length or corrupting
 * adjacent valid siblings.
 */
public class StvnContainerFaultIsolationTest {

  @Test
  @DisplayName("TC-ISO-01: Sibling element error isolation in sequence (M == N)")
  void testSequenceFaultIsolation() {
    String source = """
        {
          :defs {
            :RangedInt { #minIncl 0 #maxExcl 100 } :Int
          }
          :type :Seq(:RangedInt)
          :body [ 10 300 20 400 30 ]
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Out-of-range elements must record diagnostics");

    var docOpt = result.document();
    assertTrue(docOpt.isPresent(), "IR document must be preserved under fault isolation");
    assertInstanceOf(StvnValue.StvnSeq.class, docOpt.get().payload().orElseThrow(), "Root must be StvnSeq");
    var seq = (StvnValue.StvnSeq) docOpt.get().payload().orElseThrow();

    assertEquals(5, seq.elements().size(), "Container length M must equal source cardinality N");

    assertInstanceOf(StvnValue.StvnInt.class, seq.elements().get(0), "Element 0 must be valid StvnInt");
    assertEquals(10L, ((StvnValue.StvnInt) seq.elements().get(0)).value().longValue());

    assertInstanceOf(StvnValue.StvnErrorValue.class, seq.elements().get(1), "Element 1 (300) must be StvnErrorValue");

    assertInstanceOf(StvnValue.StvnInt.class, seq.elements().get(2), "Element 2 must be valid StvnInt");
    assertEquals(20L, ((StvnValue.StvnInt) seq.elements().get(2)).value().longValue());

    assertInstanceOf(StvnValue.StvnErrorValue.class, seq.elements().get(3), "Element 3 (400) must be StvnErrorValue");

    assertInstanceOf(StvnValue.StvnInt.class, seq.elements().get(4), "Element 4 must be valid StvnInt");
    assertEquals(30L, ((StvnValue.StvnInt) seq.elements().get(4)).value().longValue());
  }

  @Test
  @DisplayName("TC-ISO-02: Sibling element error isolation in set (M == N)")
  void testSetFaultIsolation() {
    String source = """
        {
          :defs {
            :RangedInt { #minIncl 0 #maxExcl 100 } :Int
          }
          :type :Set(:RangedInt)
          :body [ 10 300 20 ]
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Out-of-range element must record diagnostics");

    var docOpt = result.document();
    assertTrue(docOpt.isPresent(), "IR document must be preserved under fault isolation");
    assertInstanceOf(StvnValue.StvnSet.class, docOpt.get().payload().orElseThrow(), "Root must be StvnSet");
    var set = (StvnValue.StvnSet) docOpt.get().payload().orElseThrow();

    assertEquals(3, set.elements().size(), "Set length M must equal source cardinality N");
    assertInstanceOf(StvnValue.StvnInt.class, set.elements().get(0));
    assertInstanceOf(StvnValue.StvnErrorValue.class, set.elements().get(1));
    assertInstanceOf(StvnValue.StvnInt.class, set.elements().get(2));
  }

  @Test
  @DisplayName("TC-ISO-03: Entry error isolation in map (M == N)")
  void testMapFaultIsolation() {
    String source = """
        {
          :defs {
            :RangedInt { #minIncl 0 #maxExcl 100 } :Int
          }
          :type :Map(:String :RangedInt)
          :body {
            [ "a" 10 ]
            [ "b" 300 ]
            [ "c" 20 ]
          }
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Out-of-range map value must record diagnostics");

    var docOpt = result.document();
    assertTrue(docOpt.isPresent(), "IR document must be preserved under fault isolation");
    assertInstanceOf(StvnValue.StvnMap.class, docOpt.get().payload().orElseThrow(), "Root must be StvnMap");
    var map = (StvnValue.StvnMap) docOpt.get().payload().orElseThrow();

    assertEquals(3, map.entries().size(), "Map entry count M must equal source cardinality N");

    assertInstanceOf(StvnValue.StvnInt.class, map.entries().get(0).value());
    assertEquals(10L, ((StvnValue.StvnInt) map.entries().get(0).value()).value().longValue());

    assertInstanceOf(StvnValue.StvnErrorValue.class, map.entries().get(1).value(), "Entry 'b' must be StvnErrorValue");

    assertInstanceOf(StvnValue.StvnInt.class, map.entries().get(2).value());
    assertEquals(20L, ((StvnValue.StvnInt) map.entries().get(2).value()).value().longValue());
  }

  @Test
  @DisplayName("TC-ISO-04: Component error isolation in tuple (M == N)")
  void testTupleFaultIsolation() {
    String source = """
        {
          :defs {
            :RangedInt { #minIncl 0 #maxExcl 100 } :Int
          }
          :type :Tuple( :RangedInt :RangedInt :RangedInt )
          :body ( 10 300 20 )
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertEquals(1, result.diagnostics().size(), "Exactly one diagnostic must be recorded for slot 1");

    var tuple = assertInstanceOf(StvnValue.StvnTuple.class, result.document().orElseThrow().payload().orElseThrow());
    assertEquals(3, tuple.elements().size(), "Tuple arity M must equal declared arity N");
    assertInstanceOf(StvnValue.StvnInt.class, tuple.elements().get(0));
    assertInstanceOf(StvnValue.StvnErrorValue.class, tuple.elements().get(1));
    assertInstanceOf(StvnValue.StvnInt.class, tuple.elements().get(2));
  }

  @Test
  @DisplayName("TC-ISO-05: Per-slot schema fault isolation in gauntlet_temporal_float_and_subsets (:Tuple inside :Seq)")
  void testGauntletTemporalFloatAndSubsetsPerSlotIsolation() {
    String source = """
        {
          :defs {
            :OffsetTs { #ms #offset } :DateTime
            :ZonedTs { #ms #zoned } :DateTime
            :AuditedDualTs { #us #offset #zoned #audited } :DateTime
            :SensorFloat32 { #size 32 #minIncl 0.0 #maxIncl 100.0 } :Float
            :ExactPrice { #exact #minIncl 0.01 #maxExcl 9999999999999999999999999999.99 } :Float
            :HttpMethod :Enum[#GET #POST #PUT #DELETE #PATCH]

            :Record :Tuple(
              :OffsetTs
              :ZonedTs
              :AuditedDualTs
              :SensorFloat32
              :ExactPrice
              :HttpMethod
            )
          }
          :type { #maxSize 8 } :Seq(:Record)
          :body [
            (
              "2026-03-29T14:30:00.123-05:00"
              "2026-03-29T14:30:00.123-05:00[America/Chicago]"
              ("2026-03-29T19:30:00.123456Z" "2026-03-29T14:30:00.123456-05:00[America/Chicago]")
              36.5
              100
              #POST
            )
            (
              "2026-03-29T14:30:00.123Z"
              "2026-03-29T14:30:00.123-05:00"
              ("2026-03-29T19:30:00.123456-05:00" "2026-03-29T19:30:00.123456Z")
              150.0
              0.001
              #CONNECT
            )
          ]
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Invalid temporal facets and invalid healthy slot values must emit diagnostics");

    var doc = result.document().orElseThrow();
    var rootSeqSchema = assertInstanceOf(
        StvnSchema.SeqSchema.class,
        doc.rootType().orElseThrow(),
        "Root :type must remain a concrete SeqSchema despite poisoned child slots inside :Record"
    );
    var recordTupleSchema = assertInstanceOf(
        StvnSchema.TupleSchema.class,
        rootSeqSchema.elementSchema(),
        ":Record must remain a concrete TupleSchema with per-slot fault isolation"
    );
    assertEquals(6, recordTupleSchema.fieldSchemas().size());
    assertInstanceOf(StvnSchema.PoisonedSchema.class, recordTupleSchema.fieldSchemas().get(0));
    assertInstanceOf(StvnSchema.PoisonedSchema.class, recordTupleSchema.fieldSchemas().get(1));
    assertInstanceOf(StvnSchema.PoisonedSchema.class, recordTupleSchema.fieldSchemas().get(2));
    assertInstanceOf(StvnSchema.FloatSchema.class, recordTupleSchema.fieldSchemas().get(3));
    assertInstanceOf(StvnSchema.FloatSchema.class, recordTupleSchema.fieldSchemas().get(4));
    assertInstanceOf(StvnSchema.EnumSchema.class, recordTupleSchema.fieldSchemas().get(5));

    // Verify :defs errors: 3 ERR_INVALID_METADATA_FACET (#ms, #ms, #us on :DateTime)
    // + 2 ERR_MUTUALLY_EXCLUSIVE (#zoned and #audited conflicting with #offset on :AuditedDualTs)
    long invalidFacetCount = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_INVALID_METADATA_FACET.equals(d.symbolicId().orElse(null)))
        .count();
    long modeConflictCount = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE.equals(d.symbolicId().orElse(null)))
        .count();
    assertEquals(3L, invalidFacetCount, "Must report ERR_INVALID_METADATA_FACET (E008) for each #ms/#us scale facet on :DateTime");
    assertEquals(2L, modeConflictCount, "Must report ERR_MUTUALLY_EXCLUSIVE (E008) for #zoned and #audited on :AuditedDualTs");

    // Verify :body errors on healthy slots (Slots 3, 4, 5) while Slots 0, 1, 2 remain silent (TC-03):
    // Row 1 Slot 4: 100 passed to :ExactPrice (:Float) -> E028 (ERR_TYPE_MISMATCH)
    // Row 2 Slot 3: 150.0 passed to :SensorFloat32 -> E012 (ERR_CONSTRAINT_VIOLATION)
    // Row 2 Slot 4: 0.001 passed to :ExactPrice -> E012 (ERR_CONSTRAINT_VIOLATION)
    // Row 2 Slot 5: #CONNECT passed to :HttpMethod -> E028 (ERR_TYPE_MISMATCH)
    long e028Count = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_TYPE_MISMATCH.equals(d.symbolicId().orElse(null)))
        .count();
    long e012Count = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_CONSTRAINT_VIOLATION.equals(d.symbolicId().orElse(null)))
        .count();
    assertEquals(2L, e028Count, "Healthy Slot 4 in Row 1 (integer 100 for :Float) and Slot 5 in Row 2 (#CONNECT) must emit E028");
    assertEquals(2L, e012Count, "Healthy Slots 3 and 4 in Row 2 (150.0 and 0.001) must emit E012");
    assertEquals(9, result.diagnostics().size(), "Zero secondary cascade diagnostics may be emitted on poisoned Slots 0..2");

    var seqVal = assertInstanceOf(StvnValue.StvnSeq.class, doc.payload().orElseThrow());
    assertEquals(2, seqVal.elements().size());
    var row1 = assertInstanceOf(StvnValue.StvnTuple.class, seqVal.elements().get(0));
    assertEquals(6, row1.elements().size());
    assertInstanceOf(StvnValue.StvnErrorValue.class, row1.elements().get(0));
    assertInstanceOf(StvnValue.StvnErrorValue.class, row1.elements().get(1));
    assertInstanceOf(StvnValue.StvnErrorValue.class, row1.elements().get(2));
    assertInstanceOf(StvnValue.StvnFloat.class, row1.elements().get(3));
    assertInstanceOf(StvnValue.StvnErrorValue.class, row1.elements().get(4));
    assertInstanceOf(StvnValue.StvnEnum.class, row1.elements().get(5));
  }

  @Test
  @DisplayName("TC-ISO-06: Per-slot schema fault isolation in :Set and :Map without false-positive duplicate diagnostics")
  void testSetAndMapPerSlotSchemaFaultIsolation() {
    String source = """
        {
          :defs {
            :BadTs { #ms #offset } :DateTime
            :BoundedInt { #minIncl 1 #maxExcl 11 } :Int
            :Pair :Tuple(:BadTs :BoundedInt)
          }
          :type :Tuple(
            :Set(:Pair)
            :Map(:String :Pair)
          )
          :body (
            [
              ("2026-03-29T14:30:00Z" 5)
              ("2026-03-29T15:30:00Z" 5)
              ("2026-03-29T16:30:00Z" 99)
            ]
            {
              [ "k1" ("2026-03-29T14:30:00Z" 5) ]
              [ "k2" ("2026-03-29T15:30:00Z" 99) ]
            }
          )
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors());

    // Expected: 1 ERR_INVALID_METADATA_FACET (:BadTs #ms) + 2 ERR_CONSTRAINT_VIOLATION (99 in Set, 99 in Map)
    // and 0 DUPLICATE_SET_ELEMENT errors
    long invalidFacetCount = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_INVALID_METADATA_FACET.equals(d.symbolicId().orElse(null)))
        .count();
    long constraintCount = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_CONSTRAINT_VIOLATION.equals(d.symbolicId().orElse(null)))
        .count();
    long duplicateSetCount = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_DUPLICATE_SET_ELEMENT.equals(d.symbolicId().orElse(null)))
        .count();
    assertEquals(1L, invalidFacetCount);
    assertEquals(2L, constraintCount, "Healthy slot 1 inside both :Set(:Pair) and :Map(:String :Pair) must validate bounds");
    assertEquals(0L, duplicateSetCount, "Tuples containing a poisoned slot must not trigger false-positive duplicate set errors");
  }

  @Test
  @DisplayName("TC-ISO-07: Per-slot schema fault isolation in :Option, :Either, and :Union")
  void testOptionEitherUnionPerSlotSchemaFaultIsolation() {
    String source = """
        {
          :defs {
            :BadTs { #ms #offset } :DateTime
            :BoundedInt { #minIncl 1 #maxExcl 11 } :Int
          }
          :type :Tuple(
            :Option(:Tuple(:BadTs :BoundedInt))
            :Either(:BadTs :BoundedInt)
            :Union(:BadTs :BoundedInt)
            :Either(:BadTs :BoundedInt)
            :Union(:BadTs :BoundedInt)
          )
          :body (
            #Some ("2026-03-29T14:30:00Z" 50)
            #Right 60
            #2 70
            "2026-03-29T14:30:00Z"
            "2026-03-29T14:30:00Z"
          )
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors());

    var rootTuple = assertInstanceOf(
        StvnSchema.TupleSchema.class,
        result.document().orElseThrow().rootType().orElseThrow()
    );
    assertInstanceOf(StvnSchema.OptionSchema.class, rootTuple.fieldSchemas().get(0));
    assertInstanceOf(StvnSchema.EitherSchema.class, rootTuple.fieldSchemas().get(1));
    assertInstanceOf(StvnSchema.UnionSchema.class, rootTuple.fieldSchemas().get(2));

    // 1 ERR_INVALID_METADATA_FACET (#ms on :BadTs) + 3 ERR_CONSTRAINT_VIOLATION (50 in :Option, 60 in :Either #Right, 70 in :Union #2)
    // and 0 secondary errors on untagged "2026-03-29T14:30:00Z" slots 3 and 4
    long invalidFacetCount = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_INVALID_METADATA_FACET.equals(d.symbolicId().orElse(null)))
        .count();
    long constraintCount = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_CONSTRAINT_VIOLATION.equals(d.symbolicId().orElse(null)))
        .count();
    assertEquals(1L, invalidFacetCount);
    assertEquals(3L, constraintCount, "Healthy branches in :Option, :Either, and :Union must validate bounds");
    assertEquals(4, result.diagnostics().size(), "Untagged values on partially poisoned :Either/:Union must not emit secondary errors");
  }
}
