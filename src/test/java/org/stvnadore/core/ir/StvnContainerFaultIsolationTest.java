package org.stvnadore.core.ir;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test suite for container cardinality fault isolation ($M == N$).
 * <p>
 * Verifies that lowering failures on child elements produce {@link StvnValue.StvnError}
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
    assertTrue(docOpt.isPresent(), "AST must be preserved under fault isolation");
    assertTrue(docOpt.get() instanceof StvnValue.StvnSeq, "Root must be StvnSeq");
    var seq = (StvnValue.StvnSeq) docOpt.get();

    assertEquals(5, seq.elements().size(), "Container length M must equal source cardinality N");

    assertTrue(seq.elements().get(0) instanceof StvnValue.StvnInteger, "Element 0 must be valid StvnInteger");
    assertEquals(10L, ((StvnValue.StvnInteger) seq.elements().get(0)).value().longValue());

    assertTrue(seq.elements().get(1) instanceof StvnValue.StvnError, "Element 1 (300) must be StvnError");

    assertTrue(seq.elements().get(2) instanceof StvnValue.StvnInteger, "Element 2 must be valid StvnInteger");
    assertEquals(20L, ((StvnValue.StvnInteger) seq.elements().get(2)).value().longValue());

    assertTrue(seq.elements().get(3) instanceof StvnValue.StvnError, "Element 3 (400) must be StvnError");

    assertTrue(seq.elements().get(4) instanceof StvnValue.StvnInteger, "Element 4 must be valid StvnInteger");
    assertEquals(30L, ((StvnValue.StvnInteger) seq.elements().get(4)).value().longValue());
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
    assertTrue(docOpt.isPresent(), "AST must be preserved under fault isolation");
    assertTrue(docOpt.get() instanceof StvnValue.StvnSet, "Root must be StvnSet");
    var set = (StvnValue.StvnSet) docOpt.get();

    assertEquals(3, set.elements().size(), "Set length M must equal source cardinality N");
    var elementsList = new java.util.ArrayList<>(set.elements());
    assertTrue(elementsList.get(0) instanceof StvnValue.StvnInteger);
    assertTrue(elementsList.get(1) instanceof StvnValue.StvnError);
    assertTrue(elementsList.get(2) instanceof StvnValue.StvnInteger);
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
    assertTrue(docOpt.isPresent(), "AST must be preserved under fault isolation");
    assertTrue(docOpt.get() instanceof StvnValue.StvnMap, "Root must be StvnMap");
    var map = (StvnValue.StvnMap) docOpt.get();

    assertEquals(3, map.entries().size(), "Map entry count M must equal source cardinality N");
    var values = new java.util.ArrayList<>(map.entries().values());
    assertEquals(3, values.size());

    assertTrue(values.get(0) instanceof StvnValue.StvnInteger);
    assertEquals(10L, ((StvnValue.StvnInteger) values.get(0)).value().longValue());

    assertTrue(values.get(1) instanceof StvnValue.StvnError, "Entry 'b' must be StvnError");

    assertTrue(values.get(2) instanceof StvnValue.StvnInteger);
    assertEquals(20L, ((StvnValue.StvnInteger) values.get(2)).value().longValue());
  }
}
