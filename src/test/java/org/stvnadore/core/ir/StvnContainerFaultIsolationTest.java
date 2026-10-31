package org.stvnadore.core.ir;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;

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
}
