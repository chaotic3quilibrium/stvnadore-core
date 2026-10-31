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
 *   <li>Invalid states are unrepresentable at schema and payload lowering time.</li>
 *   <li>Degenerate zero-bound {@code #maxSize 0} is rejected in {@code :defs} ({@code n >= 1}, SPEC § 6.1).</li>
 *   <li>Interval folding across nominal alias chains computes interval intersections transitively.</li>
 *   <li>Precise token coordinate pinning isolates offending elements within collections.</li>
 * </ul>
 */
public class StvnTransitiveCapacityValidationTest {

  @Test
  @DisplayName("TC-TCAP-01: Bounded string capacity validation and rejection of #maxSize 0 in :defs")
  void testBoundedCapacityStringAndZeroMaxSizeRejection() {
    String zeroMaxSizeSource = """
        {
          :defs {
            :String0 { #maxSize 0 } :String
          }
          :type :String0
          :body ""
        }
        """;
    var zeroMaxResult = StvnCompiler.compileToResult(zeroMaxSizeSource, null, StvnParserConfig.DEFAULT);
    assertTrue(zeroMaxResult.hasErrors(), "Degenerate #maxSize 0 must be rejected at schema lowering time");
    var schemaErr = zeroMaxResult.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_CAPACITY_OVERFLOW, schemaErr.errorCode().orElse(null));
    assertTrue(schemaErr.message().contains("#maxSize must be >= 1"),
        "Message must report #maxSize >= 1 bound: " + schemaErr.message());

    String validSource = """
        {
          :defs {
            :String2 { #maxSize 2 } :String
          }
          :type :String2
          :body "ab"
        }
        """;
    var validResult = StvnCompiler.compileToResult(validSource, null, StvnParserConfig.DEFAULT);
    assertFalse(validResult.hasErrors(), "2-character string within #maxSize 2 must compile cleanly");
    assertNotNull(validResult.document().orElse(null));

    String invalidSource = """
        {
          :defs {
            :String2 { #maxSize 2 } :String
          }
          :type :String2
          :body "abc"
        }
        """;
    var invalidResult = StvnCompiler.compileToResult(invalidSource, null, StvnParserConfig.DEFAULT);
    assertTrue(invalidResult.hasErrors(), "3-character string must fail #maxSize 2 constraint");
    var error = invalidResult.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, error.errorCode().orElse(null));
    assertTrue(error.message().contains("Size 3 outside allowable range [0, 2]"),
        "Message must report size and range bounds: " + error.message());
  }

  @Test
  @DisplayName("TC-TCAP-02: Sequence of nominal bounded strings with coordinate pinning")
  void testSequenceOfNominalBoundedStrings() {
    String source = """
        {
          :defs {
            :String2 { #maxSize 2 } :String
          }
          :type :Seq( :String2 )
          :body [ "ab" "abc" "abcd" ]
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Sequence with offending elements must fail compilation");
    assertEquals(2, result.diagnostics().size(), "Must emit exactly 2 diagnostics for 'abc' and 'abcd'");

    var diag1 = result.diagnostics().get(0);
    assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, diag1.errorCode().orElse(null));
    assertTrue(diag1.message().contains("Size 3 outside allowable range [0, 2]"));
    String slice1 = source.substring(diag1.startOffset(), diag1.endOffset());
    assertEquals("\"abc\"", slice1, "Diagnostic 1 must pin precisely to offending token 'abc'");

    var diag2 = result.diagnostics().get(1);
    assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, diag2.errorCode().orElse(null));
    assertTrue(diag2.message().contains("Size 4 outside allowable range [0, 2]"));
    String slice2 = source.substring(diag2.startOffset(), diag2.endOffset());
    assertEquals("\"abcd\"", slice2, "Diagnostic 2 must pin precisely to offending token 'abcd'");
  }

  @Test
  @DisplayName("TC-TCAP-03: Sequence of bounded sequences with excess slice coordinate pinning")
  void testSequenceOfBoundedSequences() {
    String source = """
        {
          :defs {
            :PairSeq { #maxSize 2 } :Seq( :String )
          }
          :type :Seq( :PairSeq )
          :body [ [ "ok" ] [ "a" "b" "excess" ] ]
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Sequence containing overflowing child sequence must fail");
    assertEquals(1, result.diagnostics().size(), "Must emit 1 diagnostic for excess child element");

    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_CAPACITY_OVERFLOW, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("Size 3 outside allowable range [0, 2]"));
    String slice = source.substring(diag.startOffset(), diag.endOffset());
    assertEquals("\"excess\"", slice, "Diagnostic must pin precisely to excess element \"excess\"");
  }

  @Test
  @DisplayName("TC-TCAP-04: Set of nominal bounded strings with element coordinate pinning")
  void testSetOfNominalBoundedStrings() {
    String source = """
        {
          :defs {
            :String2 { #maxSize 2 } :String
          }
          :type :Set( :String2 )
          :body [ "xyz" ]
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Set containing overflowing element must fail");
    assertEquals(1, result.diagnostics().size());

    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("Size 3 outside allowable range [0, 2]"));
    String slice = source.substring(diag.startOffset(), diag.endOffset());
    assertEquals("\"xyz\"", slice, "Diagnostic must pin precisely to offending set element 'xyz'");
  }

  @Test
  @DisplayName("TC-TCAP-05: Map of nominal bounded strings with key and value coordinate pinning")
  void testMapOfNominalBoundedStrings() {
    String source = """
        {
          :defs {
            :String2 { #maxSize 2 } :String
          }
          :type :Map( :String2 :String2 )
          :body {
            [ "key1" "val1" ]
          }
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Map containing invalid key and value must fail");
    assertEquals(2, result.diagnostics().size(), "Must emit 2 diagnostics for key and value");

    var diagKey = result.diagnostics().get(0);
    assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, diagKey.errorCode().orElse(null));
    assertTrue(diagKey.message().contains("Size 4 outside allowable range [0, 2]"));
    String sliceKey = source.substring(diagKey.startOffset(), diagKey.endOffset());
    assertEquals("\"key1\"", sliceKey, "Diagnostic 1 must pin precisely to offending map key 'key1'");

    var diagVal = result.diagnostics().get(1);
    assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, diagVal.errorCode().orElse(null));
    assertTrue(diagVal.message().contains("Size 4 outside allowable range [0, 2]"));
    String sliceVal = source.substring(diagVal.startOffset(), diagVal.endOffset());
    assertEquals("\"val1\"", sliceVal, "Diagnostic 2 must pin precisely to offending map value 'val1'");
  }

  @Test
  @DisplayName("TC-TCAP-06: Transitive alias interval folding down nominal type chains")
  void testTransitiveAliasIntervalFolding() {
    String validSource = """
        {
          :defs {
            :BaseStr :String
            :StrictStr { #maxSize 2 } :BaseStr
          }
          :type :StrictStr
          :body "ab"
        }
        """;
    var validResult = StvnCompiler.compileToResult(validSource, null, StvnParserConfig.DEFAULT);
    assertFalse(validResult.hasErrors(), "2-character string must satisfy folded bounds [0, 2]");

    String invalidSource = """
        {
          :defs {
            :BaseStr :String
            :StrictStr { #maxSize 2 } :BaseStr
          }
          :type :StrictStr
          :body "hello"
        }
        """;
    var invalidResult = StvnCompiler.compileToResult(invalidSource, null, StvnParserConfig.DEFAULT);
    assertTrue(invalidResult.hasErrors(), "'hello' must violate folded upper bound 2");
    var error = invalidResult.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, error.errorCode().orElse(null));
    assertTrue(error.message().contains("Size 5 outside allowable range [0, 2]"));

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
    assertTrue(error.message().contains("#minSize (5) cannot exceed #maxSize (2)"));
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
    assertEquals(DiagnosticBag.ERR_CAPACITY_OVERFLOW, seqErr.errorCode().orElse(null));
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
    assertTrue(underErr.message().contains("Size 1 outside allowable range [3, 16777216]"));

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
    assertEquals(DiagnosticBag.ERR_CAPACITY_OVERFLOW, setErr.errorCode().orElse(null));
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
    assertEquals(DiagnosticBag.ERR_CAPACITY_OVERFLOW, mapErr.errorCode().orElse(null));
    assertTrue(mapErr.message().contains("Size 2 outside allowable range [0, 1]"));
  }
}
