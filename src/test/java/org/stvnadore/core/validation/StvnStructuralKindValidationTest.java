package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnParserConfig;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test suite for payload schema kind conformance and structural type enforcement.
 * <p>
 * Ensures that the compiler strictly validates structural kind compatibility between AST literal kinds
 * (scalar, collection list, map, product tuple) and schema constructor families (:Seq, :Set, :Map, :Tuple, scalars).
 */
public class StvnStructuralKindValidationTest {

  @Test
  @DisplayName("TC-KIND-01: Rejection of scalar literal in map entry collection value slot with exact coordinate pinning")
  void testMapEntryValueScalarRejectedForCollection() {
    String source = """
        {
          :defs {
            :String0 { #minSize 0 #maxSize 0 } :String
          }
          :type :Map( :String0 :Set( :String0 ) )
          :body {
            [ "" "" ]
          }
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Scalar literal in collection value slot must be rejected");

    var errors = result.diagnostics().stream()
        .filter(d -> d.severity() == StvnDiagnostic.DiagnosticSeverity.ERROR)
        .toList();
    assertFalse(errors.isEmpty(), "Expected at least one error diagnostic");

    var diag = errors.getFirst();
    assertEquals(DiagnosticBag.ERR_TYPE_MISMATCH, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("Type mismatch: expected collection constructor :Set, found scalar :String"),
        "Message should report structural mismatch: " + diag.message());

    int expectedStart = source.lastIndexOf("\"\"");
    int expectedEnd = expectedStart + 2;
    assertEquals(expectedStart, diag.startOffset(), "Diagnostic must pin strictly to the second string literal start");
    assertEquals(expectedEnd, diag.endOffset(), "Diagnostic must pin strictly to the second string literal end");
  }

  @Test
  @DisplayName("TC-KIND-02: Rejection of collection literal in scalar key slot of map entry")
  void testMapEntryKeyCollectionRejectedForScalar() {
    String source = """
        {
          :type :Map( :String :Int )
          :body {
            [ [ "nested" ] 42 ]
          }
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Collection literal in scalar key slot must be rejected");

    var diag = result.diagnostics().stream()
        .filter(d -> d.severity() == StvnDiagnostic.DiagnosticSeverity.ERROR)
        .findFirst()
        .orElseThrow();
    assertEquals(DiagnosticBag.ERR_TYPE_MISMATCH, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("Type mismatch: expected scalar :String, found collection constructor :Seq"),
        "Message should report scalar expected, found collection: " + diag.message());

    int expectedStart = source.indexOf("[ \"nested\" ]");
    int expectedEnd = expectedStart + "[ \"nested\" ]".length();
    assertEquals(expectedStart, diag.startOffset(), "Diagnostic must pin strictly to the key list literal start");
    assertEquals(expectedEnd, diag.endOffset(), "Diagnostic must pin strictly to the key list literal end");
  }

  @Test
  @DisplayName("TC-KIND-03: Rejection of scalar literal where nested sequence collection is expected")
  void testSequenceElementScalarRejectedForNestedCollection() {
    String source = """
        {
          :type :Seq( :Seq( :String ) )
          :body [
            [ "ok" ]
            "illegal-scalar"
          ]
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Scalar in nested sequence collection must be rejected");

    var diag = result.diagnostics().stream()
        .filter(d -> d.severity() == StvnDiagnostic.DiagnosticSeverity.ERROR)
        .findFirst()
        .orElseThrow();
    assertEquals(DiagnosticBag.ERR_TYPE_MISMATCH, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("Type mismatch: expected collection constructor :Seq, found scalar :String"),
        "Message should report collection expected, found scalar: " + diag.message());

    int expectedStart = source.indexOf("\"illegal-scalar\"");
    int expectedEnd = expectedStart + "\"illegal-scalar\"".length();
    assertEquals(expectedStart, diag.startOffset(), "Diagnostic must pin strictly to the offending scalar start");
    assertEquals(expectedEnd, diag.endOffset(), "Diagnostic must pin strictly to the offending scalar end");
  }

  @Test
  @DisplayName("TC-KIND-04: Rejection of scalar literal where tuple product is expected")
  void testTupleElementScalarRejectedForProduct() {
    String source = """
        {
          :type :Tuple( :String :Tuple( :Int :Int ) )
          :body (
            "ok"
            100
          )
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Scalar in tuple product slot must be rejected");

    var diag = result.diagnostics().stream()
        .filter(d -> d.severity() == StvnDiagnostic.DiagnosticSeverity.ERROR)
        .findFirst()
        .orElseThrow();
    assertEquals(DiagnosticBag.ERR_TYPE_MISMATCH, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("Type mismatch: expected product constructor :Tuple, found scalar :Int"),
        "Message should report tuple expected, found scalar: " + diag.message());

    int expectedStart = source.lastIndexOf("100");
    int expectedEnd = expectedStart + "100".length();
    assertEquals(expectedStart, diag.startOffset(), "Diagnostic must pin strictly to the offending scalar 100 start");
    assertEquals(expectedEnd, diag.endOffset(), "Diagnostic must pin strictly to the offending scalar 100 end");
  }

  @Test
  @DisplayName("TC-KIND-05: Rejection of collection literal in scalar root payload")
  void testScalarRootPayloadRejectsCollectionLiteral() {
    String source = """
        {
          :type :String
          :body [ "not-a-string" ]
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Collection in scalar root payload must be rejected");

    var diag = result.diagnostics().stream()
        .filter(d -> d.severity() == StvnDiagnostic.DiagnosticSeverity.ERROR)
        .findFirst()
        .orElseThrow();
    assertEquals(DiagnosticBag.ERR_TYPE_MISMATCH, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("Type mismatch: expected scalar :String, found collection constructor :Seq"),
        "Message should report scalar expected, found collection: " + diag.message());

    int expectedStart = source.indexOf("[ \"not-a-string\" ]");
    int expectedEnd = expectedStart + "[ \"not-a-string\" ]".length();
    assertEquals(expectedStart, diag.startOffset(), "Diagnostic must pin strictly to the list literal start");
    assertEquals(expectedEnd, diag.endOffset(), "Diagnostic must pin strictly to the list literal end");
  }

  @Test
  @DisplayName("TC-KIND-06: Resilient diagnostic accumulation across sibling map entries")
  void testResilientDiagnosticAccumulationInMap() {
    String source = """
        {
          :type :Map( :String :Set( :String ) )
          :body {
            [ "k1" "bad1" ]
            [ "k2" "bad2" ]
          }
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Invalid map entries must produce errors");

    var errors = result.diagnostics().stream()
        .filter(d -> d.severity() == StvnDiagnostic.DiagnosticSeverity.ERROR)
        .toList();
    assertEquals(2, errors.size(), "Both invalid entries must accumulate diagnostics independently");

    var diag1 = errors.get(0);
    var diag2 = errors.get(1);

    assertEquals(DiagnosticBag.ERR_TYPE_MISMATCH, diag1.errorCode().orElse(null));
    assertEquals(DiagnosticBag.ERR_TYPE_MISMATCH, diag2.errorCode().orElse(null));

    assertTrue(diag1.message().contains("Type mismatch: expected collection constructor :Set, found scalar :String"));
    assertTrue(diag2.message().contains("Type mismatch: expected collection constructor :Set, found scalar :String"));

    int expectedStart1 = source.indexOf("\"bad1\"");
    int expectedEnd1 = expectedStart1 + "\"bad1\"".length();
    int expectedStart2 = source.indexOf("\"bad2\"");
    int expectedEnd2 = expectedStart2 + "\"bad2\"".length();

    assertEquals(expectedStart1, diag1.startOffset(), "Diag 1 must pin strictly to bad1");
    assertEquals(expectedEnd1, diag1.endOffset(), "Diag 1 must pin strictly to bad1 end");
    assertEquals(expectedStart2, diag2.startOffset(), "Diag 2 must pin strictly to bad2");
    assertEquals(expectedEnd2, diag2.endOffset(), "Diag 2 must pin strictly to bad2 end");
  }

  @Test
  @DisplayName("TC-KIND-07: Constant definition-time rejection of scalar literal for collection constructor")
  void testConstantDefinitionRejectsScalarForCollection() {
    String source = """
        {
          :defs {
            #BAD_SEQ :Seq( :Int ) 42
          }
          :type :Int
          :body 1
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Constant scalar literal for collection constructor must be rejected");

    var diag = result.diagnostics().stream()
        .filter(d -> d.severity() == StvnDiagnostic.DiagnosticSeverity.ERROR)
        .findFirst()
        .orElseThrow();
    assertEquals(DiagnosticBag.ERR_TYPE_MISMATCH, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("Type mismatch (#BAD_SEQ): expected collection constructor :Seq, found scalar :Int"),
        "Message should report constant definition type mismatch: " + diag.message());
  }

  @Test
  @DisplayName("TC-KIND-08: Constant definition-time rejection of collection literal for scalar type")
  void testConstantDefinitionRejectsCollectionForScalar() {
    String source = """
        {
          :defs {
            #BAD_SCALAR :Int [ 1 2 ]
          }
          :type :Int
          :body 1
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Constant collection literal for scalar type must be rejected");

    var diag = result.diagnostics().stream()
        .filter(d -> d.severity() == StvnDiagnostic.DiagnosticSeverity.ERROR)
        .findFirst()
        .orElseThrow();
    assertEquals(DiagnosticBag.ERR_TYPE_MISMATCH, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("Type mismatch (#BAD_SCALAR): expected scalar :Int, found collection constructor :Seq"),
        "Message should report constant definition type mismatch: " + diag.message());
  }
}
