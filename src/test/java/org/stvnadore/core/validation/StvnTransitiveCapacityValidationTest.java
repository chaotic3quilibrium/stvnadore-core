package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test suite for transitive capacity constraint validation and orthogonal collection
 * capacity enforcement across {@code :String}, {@code :Seq}, {@code :Set}, and {@code :Map}.
 * <p>
 * Verifies Value-Oriented Programming (VOP) invariants:
 * <ul>
 *   <li>Invalid states are unrepresentable at payload lowering time.</li>
 *   <li>Degenerate zero-bound {@code {#minSize 0 #maxSize 0}} defines a singleton unit domain.</li>
 *   <li>Interval folding across nominal alias chains computes interval intersections transitively.</li>
 *   <li>Precise token coordinate pinning isolates offending elements within collections.</li>
 * </ul>
 */
public class StvnTransitiveCapacityValidationTest {

  @Test
  @DisplayName("TC-TCAP-01: Direct zero-capacity string validation (\"\" vs \"a\")")
  void testDirectZeroCapacityString() {
    String validSource = """
        {
          :defs {
            :String0 { #minSize 0 #maxSize 0 } :String
          }
          :type :String0
          :body ""
        }
        """;
    var validResult = StvnCompiler.compileToResult(validSource, null, StvnParserConfig.DEFAULT);
    assertFalse(validResult.hasErrors(), "Empty string in singleton zero-capacity domain must compile cleanly");
    assertNotNull(validResult.document().orElse(null));

    String invalidSource = """
        {
          :defs {
            :String0 { #minSize 0 #maxSize 0 } :String
          }
          :type :String0
          :body "a"
        }
        """;
    var invalidResult = StvnCompiler.compileToResult(invalidSource, null, StvnParserConfig.DEFAULT);
    assertTrue(invalidResult.hasErrors(), "Non-empty string must fail zero-capacity constraint");
    var error = invalidResult.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, error.errorCode().orElse(null));
    assertTrue(error.message().contains("Size 1 outside allowable range [0, 0]"),
        "Message must report size and range bounds: " + error.message());
  }

  @Test
  @DisplayName("TC-TCAP-02: Sequence of nominal zero-capacity strings with coordinate pinning")
  void testSequenceOfNominalZeroCapacityStrings() {
    String source = """
        {
          :defs {
            :String0 { #minSize 0 #maxSize 0 } :String
          }
          :type :Seq( :String0 )
          :body [ "" "a" "ab" ]
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Sequence with offending elements must fail compilation");
    assertEquals(2, result.diagnostics().size(), "Must emit exactly 2 diagnostics for 'a' and 'ab'");

    var diag1 = result.diagnostics().get(0);
    assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, diag1.errorCode().orElse(null));
    assertTrue(diag1.message().contains("Size 1 outside allowable range [0, 0]"));
    String slice1 = source.substring(diag1.startOffset(), diag1.endOffset());
    assertEquals("\"a\"", slice1, "Diagnostic 1 must pin precisely to offending token 'a'");

    var diag2 = result.diagnostics().get(1);
    assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, diag2.errorCode().orElse(null));
    assertTrue(diag2.message().contains("Size 2 outside allowable range [0, 0]"));
    String slice2 = source.substring(diag2.startOffset(), diag2.endOffset());
    assertEquals("\"ab\"", slice2, "Diagnostic 2 must pin precisely to offending token 'ab'");
  }

  @Test
  @DisplayName("TC-TCAP-03: Sequence of zero-capacity sequences with container coordinate pinning")
  void testSequenceOfZeroCapacitySequences() {
    String source = """
        {
          :defs {
            :EmptySeq { #minSize 0 #maxSize 0 } :Seq( :String )
          }
          :type :Seq( :EmptySeq )
          :body [ [] [ "item" ] ]
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Sequence containing non-empty child sequence must fail");
    assertEquals(1, result.diagnostics().size(), "Must emit 1 diagnostic for [ \"item\" ]");

    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("Size 1 outside allowable range [0, 0]"));
    String slice = source.substring(diag.startOffset(), diag.endOffset());
    assertEquals("[ \"item\" ]", slice, "Diagnostic must pin precisely to nested list [ \"item\" ]");
  }

  @Test
  @DisplayName("TC-TCAP-04: Set of nominal zero-capacity strings with element coordinate pinning")
  void testSetOfNominalZeroCapacityStrings() {
    String source = """
        {
          :defs {
            :String0 { #minSize 0 #maxSize 0 } :String
          }
          :type :Set( :String0 )
          :body [ "x" ]
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Set containing non-empty element must fail");
    assertEquals(1, result.diagnostics().size());

    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("Size 1 outside allowable range [0, 0]"));
    String slice = source.substring(diag.startOffset(), diag.endOffset());
    assertEquals("\"x\"", slice, "Diagnostic must pin precisely to offending set element 'x'");
  }

  @Test
  @DisplayName("TC-TCAP-05: Map of nominal zero-capacity strings with key and value coordinate pinning")
  void testMapOfNominalZeroCapacityStrings() {
    String source = """
        {
          :defs {
            :String0 { #minSize 0 #maxSize 0 } :String
          }
          :type :Map( :String0 :String0 )
          :body {
            [ "k" "v" ]
          }
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Map containing invalid key and value must fail");
    assertEquals(2, result.diagnostics().size(), "Must emit 2 diagnostics for key and value");

    var diagKey = result.diagnostics().get(0);
    assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, diagKey.errorCode().orElse(null));
    assertTrue(diagKey.message().contains("Size 1 outside allowable range [0, 0]"));
    String sliceKey = source.substring(diagKey.startOffset(), diagKey.endOffset());
    assertEquals("\"k\"", sliceKey, "Diagnostic 1 must pin precisely to offending map key 'k'");

    var diagVal = result.diagnostics().get(1);
    assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, diagVal.errorCode().orElse(null));
    assertTrue(diagVal.message().contains("Size 1 outside allowable range [0, 0]"));
    String sliceVal = source.substring(diagVal.startOffset(), diagVal.endOffset());
    assertEquals("\"v\"", sliceVal, "Diagnostic 2 must pin precisely to offending map value 'v'");
  }

  @Test
  @DisplayName("TC-TCAP-06: Transitive alias interval folding down nominal type chains")
  void testTransitiveAliasIntervalFolding() {
    String validSource = """
        {
          :defs {
            :BaseStr { #minSize 0 } :String
            :StrictStr { #maxSize 0 } :BaseStr
          }
          :type :StrictStr
          :body ""
        }
        """;
    var validResult = StvnCompiler.compileToResult(validSource, null, StvnParserConfig.DEFAULT);
    assertFalse(validResult.hasErrors(), "Empty string must satisfy folded bounds [0, 0]");

    String invalidSource = """
        {
          :defs {
            :BaseStr { #minSize 0 } :String
            :StrictStr { #maxSize 0 } :BaseStr
          }
          :type :StrictStr
          :body "hello"
        }
        """;
    var invalidResult = StvnCompiler.compileToResult(invalidSource, null, StvnParserConfig.DEFAULT);
    assertTrue(invalidResult.hasErrors(), "'hello' must violate folded upper bound 0");
    var error = invalidResult.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, error.errorCode().orElse(null));
    assertTrue(error.message().contains("Size 5 outside allowable range [0, 0]"));

    // Multi-tier interval combination test: parent has minSize, child has maxSize
    String multiTierSource = """
        {
          :defs {
            :BaseMin { #minSize 5 } :String
            :NarrowRange { #maxSize 8 } :BaseMin
          }
          :type :NarrowRange
          :body "1234"
        }
        """;
    var multiResult = StvnCompiler.compileToResult(multiTierSource, null, StvnParserConfig.DEFAULT);
    assertTrue(multiResult.hasErrors(), "Length 4 must violate narrowed minSize 5");
    assertTrue(multiResult.diagnostics().getFirst().message().contains("violates #minSize constraint (5)"));
  }

  @Test
  @DisplayName("TC-TCAP-07: Conflicting alias bounds rejection at definition time")
  void testConflictingAliasBoundsRejection() {
    String source = """
        {
          :defs {
            :BaseStr { #maxSize 2 } :String
            :Bad { #minSize 5 } :BaseStr
          }
          :type :Bad
          :body "abcde"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Inverted range in alias definition must fail at definition time");
    var error = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_INVERTED_RANGE, error.errorCode().orElse(null));
    assertTrue(error.message().contains("cardinality range is invalid"));
  }

  @Test
  @DisplayName("TC-TCAP-08: Orthogonal capacity bounds validation on collection containers")
  void testOrthogonalCollectionCapacityBounds() {
    // Sequence exceeding maxSize
    String seqOverflow = """
        {
          :defs {
            :BoundedSeq { #maxSize 2 } :Seq( :Int )
          }
          :type :BoundedSeq
          :body [ 1 2 3 ]
        }
        """;
    var seqResult = StvnCompiler.compileToResult(seqOverflow, null, StvnParserConfig.DEFAULT);
    assertTrue(seqResult.hasErrors());
    var seqErr = seqResult.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, seqErr.errorCode().orElse(null));
    assertTrue(seqErr.message().contains("Size 3 outside allowable range [0, 2]"));

    // Sequence violating minSize > 1
    String seqUnderflow = """
        {
          :defs {
            :MinSeq { #minSize 3 } :Seq( :Int )
          }
          :type :MinSeq
          :body [ 1 ]
        }
        """;
    var underResult = StvnCompiler.compileToResult(seqUnderflow, null, StvnParserConfig.DEFAULT);
    assertTrue(underResult.hasErrors());
    var underErr = underResult.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, underErr.errorCode().orElse(null));
    assertTrue(underErr.message().contains("Size 1 outside allowable range [3, open]"));

    // Set exceeding maxSize
    String setOverflow = """
        {
          :defs {
            :BoundedSet { #maxSize 2 } :Set( :Int )
          }
          :type :BoundedSet
          :body [ 1 2 3 ]
        }
        """;
    var setResult = StvnCompiler.compileToResult(setOverflow, null, StvnParserConfig.DEFAULT);
    assertTrue(setResult.hasErrors());
    var setErr = setResult.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, setErr.errorCode().orElse(null));
    assertTrue(setErr.message().contains("Size 3 outside allowable range [0, 2]"));

    // Map exceeding maxSize
    String mapOverflow = """
        {
          :defs {
            :BoundedMap { #maxSize 1 } :Map( :String :Int )
          }
          :type :BoundedMap
          :body {
            [ "a" 1 ]
            [ "b" 2 ]
          }
        }
        """;
    var mapResult = StvnCompiler.compileToResult(mapOverflow, null, StvnParserConfig.DEFAULT);
    assertTrue(mapResult.hasErrors());
    var mapErr = mapResult.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, mapErr.errorCode().orElse(null));
    assertTrue(mapErr.message().contains("Size 2 outside allowable range [0, 1]"));
  }
}
