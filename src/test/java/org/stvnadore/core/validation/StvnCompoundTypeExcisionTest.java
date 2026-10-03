package org.stvnadore.core.validation;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.ir.StvnValue;

/**
 * Anti-tautological test suite validating Pass 1 functional excision of legacy compound types.
 */
public class StvnCompoundTypeExcisionTest {

  @Test
  @DisplayName("CONTRACT-NEG-01: Undeclared :Uint64 fails closed with ERR_UNDEFINED_TYPE pinned to token")
  void testUndeclaredUint64FailsClosed() {
    String doc = """
        {
          :type :Uint64
          :body 18446744073709551615
        }
        """;
    var result = StvnCompiler.compileToResult(doc, null, StvnParserConfig.DEFAULT);
    Assertions.assertTrue(result.hasErrors(), "Undeclared :Uint64 must fail closed");
    Assertions.assertEquals(1, result.diagnostics().size());
    var diag = result.diagnostics().getFirst();
    Assertions.assertEquals(DiagnosticBag.ERR_UNDEFINED_TYPE, diag.errorCode().orElse(null));
    Assertions.assertEquals("Undefined type: :Uint64", diag.message());

    int expectedStart = doc.indexOf(":Uint64");
    int expectedEnd = expectedStart + ":Uint64".length();
    Assertions.assertEquals(expectedStart, diag.startOffset(), "Start offset must pinpoint :Uint64");
    Assertions.assertEquals(expectedEnd, diag.endOffset(), "End offset must pinpoint :Uint64");
  }

  @Test
  @DisplayName("CONTRACT-POS-01: Declaring :Uint64 { #unsigned #size 64 } :Int compiles cleanly with zero diagnostics")
  void testDeclaredUint64NominalDefinitionSucceeds() {
    String doc = """
        {
          :defs {
            :Uint64 { #unsigned #size 64 } :Int
          }
          :type :Uint64
          :body 1000
        }
        """;
    var result = StvnCompiler.compileToResult(doc, null, StvnParserConfig.DEFAULT);
    Assertions.assertFalse(result.hasErrors(), "Nominal :Uint64 must compile cleanly");
    Assertions.assertTrue(result.diagnostics().isEmpty(), "Must produce zero diagnostics");
    Assertions.assertInstanceOf(StvnValue.StvnInteger.class, result.document().orElseThrow());
  }

  @Test
  @DisplayName("CONTRACT-NEG-02: Undeclared :StringFixed4 fails closed with ERR_UNDEFINED_TYPE pinned to token")
  void testUndeclaredStringFixed4FailsClosed() {
    String doc = """
        {
          :type :StringFixed4
          :body "TEST"
        }
        """;
    var result = StvnCompiler.compileToResult(doc, null, StvnParserConfig.DEFAULT);
    Assertions.assertTrue(result.hasErrors(), "Undeclared :StringFixed4 must fail closed");
    var diag = result.diagnostics().getFirst();
    Assertions.assertEquals(DiagnosticBag.ERR_UNDEFINED_TYPE, diag.errorCode().orElse(null));
    Assertions.assertEquals("Undefined type: :StringFixed4", diag.message());

    int expectedStart = doc.indexOf(":StringFixed4");
    Assertions.assertEquals(expectedStart, diag.startOffset());
    Assertions.assertEquals(expectedStart + ":StringFixed4".length(), diag.endOffset());
  }

  @Test
  @DisplayName("CONTRACT-POS-02: Declaring :StringFixed4 { #minSize 4 #maxSize 4 } :String compiles cleanly")
  void testDeclaredStringFixed4NominalDefinitionSucceeds() {
    String doc = """
        {
          :defs {
            :StringFixed4 { #minSize 4 #maxSize 4 } :String
          }
          :type :StringFixed4
          :body "TEST"
        }
        """;
    var result = StvnCompiler.compileToResult(doc, null, StvnParserConfig.DEFAULT);
    Assertions.assertFalse(result.hasErrors(), "Nominal :StringFixed4 must compile cleanly");
    Assertions.assertTrue(result.diagnostics().isEmpty());
    Assertions.assertInstanceOf(StvnValue.StvnString.class, result.document().orElseThrow());
  }

  @Test
  @DisplayName("CONTRACT-NEG-03: Nested undeclared compound :Int32 inside :Seq(:Int32) pinpoints inner token")
  void testNestedUndeclaredInt32PinpointsInnerToken() {
    String doc = """
        {
          :type :Seq(:Int32)
          :body [ 1 2 3 ]
        }
        """;
    var result = StvnCompiler.compileToResult(doc, null, StvnParserConfig.DEFAULT);
    Assertions.assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    Assertions.assertEquals(DiagnosticBag.ERR_UNDEFINED_TYPE, diag.errorCode().orElse(null));
    Assertions.assertEquals("Undefined type: :Int32", diag.message());

    int expectedStart = doc.indexOf(":Int32");
    Assertions.assertEquals(expectedStart, diag.startOffset(), "Must pin inner :Int32 offset");
    Assertions.assertEquals(expectedStart + ":Int32".length(), diag.endOffset());
  }

  @Test
  @DisplayName("CONTRACT-REG-01: Standard nominal identifiers (:IntCounter, :StringList) remain unaffected")
  void testStandardNominalIdentifiersUnaffected() {
    String doc = """
        {
          :defs {
            :IntCounter { #minIncl 0 #maxExcl 100 } :Int
            :StringList :Seq(:String)
          }
          :type :Tuple(:IntCounter :StringList)
          :body ( 50 [ "a" "b" ] )
        }
        """;
    var result = StvnCompiler.compileToResult(doc, null, StvnParserConfig.DEFAULT);
    Assertions.assertFalse(result.hasErrors(), "Standard nominal types must compile cleanly");
    Assertions.assertTrue(result.isSuccess());
  }
}
