package org.stvnadore.core.validation;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ir.StvnValue;

/**
 * Exhaustive regression verification suite for universal schemaType metadata elevation.
 */
public class StvnSchemaTypeMetadataTest {

  @Test
  @DisplayName("Either constructor accepts inline metadata block on child scalar")
  void testEitherInlineChildMetadata() {
    String source = """
        {
          :defs {
            :Choice :Either( :Float { #size 32 } :Int )
          }
          :type :Choice
          :body #Right 42
        }
        """;
    var res = StvnCompiler.compileToResult(source);
    Assertions.assertFalse(res.hasErrors(), "Compilation failed: " + res.diagnostics());
    var doc = res.document().orElseThrow().requirePayload();
    Assertions.assertInstanceOf(StvnValue.StvnEither.class, doc);
  }

  @Test
  @DisplayName("Metadata override nominal inheritance chain")
  void testMetadataOverride() {
    String source = """
        {
          :defs {
            :HumanAge { #minIncl 0 #maxExcl 121 } :Int
            :ParentAge { #comparable #F #maxExcl 99 } :HumanAge
            :StrictChildAge { #minIncl 18 #maxExcl 51 } :ParentAge
          }
          :type :Seq( :StrictChildAge )
          :body [ 20 32 ]
        }
        """;
    var res = StvnCompiler.compileToResult(source);
    Assertions.assertFalse(res.hasErrors(), "Failed: " + res.diagnostics());
  }

  @Test
  @DisplayName("Seq constructor accepts inline unsigned bitwidth metadata on child scalar")
  void testSeqInlineChildMetadata() {
    String source = """
        {
          :defs {
            :Queue :Seq( { #unsigned #size 32 } :Int )
          }
          :type :Queue
          :body [ 10 20 30 ]
        }
        """;
    var res = StvnCompiler.compileToResult(source);
    Assertions.assertFalse(res.hasErrors(), "Compilation failed: " + res.diagnostics());
  }

  @Test
  @DisplayName("Tuple constructor accepts multiple distinct inline metadata blocks")
  void testTupleMultipleInlineChildMetadata() {
    String source = """
        {
          :defs {
            :Triad :Tuple( { #size 16 } :Int { #size 32 } :Int { #unsigned #size 64 } :Int )
          }
          :type :Triad
          :body ( 100 200 400 )
        }
        """;
    var res = StvnCompiler.compileToResult(source);
    Assertions.assertFalse(res.hasErrors(), "Compilation failed: " + res.diagnostics());
  }

  @Test
  @DisplayName("Map constructor accepts inline constraints on key and value types")
  void testMapInlineChildMetadata() {
    String source = """
        {
          :defs {
            :PortMap :Map( { #minSize 1 #maxSize 10 } :String { #minIncl 1 #maxExcl 65536 } :Int )
          }
          :type :PortMap
          :body { [ "http" 80 ] [ "https" 443 ] }
        }
        """;
    var res = StvnCompiler.compileToResult(source);
    Assertions.assertFalse(res.hasErrors(), "Compilation failed: " + res.diagnostics());
  }

  @Test
  @DisplayName("Empty metadata block inside composite constructor emits ERR_EMPTY_METADATA_BLOCK")
  void testEmptyChildMetadataBlockRejection() {
    String source = """
        {
          :defs {
            :BadSeq :Seq( {} :Int )
          }
          :type :BadSeq
          :body [ 1 ]
        }
        """;
    var res = StvnCompiler.compileToResult(source);
    Assertions.assertTrue(res.hasErrors());
    var d = res.diagnostics().getFirst();
    Assertions.assertEquals(DiagnosticBag.ERR_EMPTY_METADATA_BLOCK, d.errorCode().orElse(null));
    int start = source.indexOf("{}");
    Assertions.assertEquals(start, d.startOffset());
    Assertions.assertEquals(start + 2, d.endOffset());
  }

  @Test
  @DisplayName("Incompatible facet on child scalar emits ERR_INVALID_METADATA_FACET")
  void testIncompatibleFacetOnChildScalarRejection() {
    String source = """
        {
          :defs {
            :BadEither :Either( :Float { #regex "abc" } :Int )
          }
          :type :BadEither
          :body #Right 10
        }
        """;
    var res = StvnCompiler.compileToResult(source);
    Assertions.assertTrue(res.hasErrors());
    var d = res.diagnostics().getFirst();
    Assertions.assertEquals(DiagnosticBag.ERR_INVALID_METADATA_FACET, d.errorCode().orElse(null));
  }

  @Test
  @DisplayName("Child integer payload overflow respects inline bitwidth constraint")
  void testChildIntegerPayloadOverflow() {
    String source = """
        {
          :defs {
            :ByteSeq :Seq( { #size 8 } :Int )
          }
          :type :ByteSeq
          :body [ 127 255 ]
        }
        """;
    var res = StvnCompiler.compileToResult(source);
    Assertions.assertTrue(res.hasErrors());
    var d = res.diagnostics().getFirst();
    Assertions.assertEquals(DiagnosticBag.ERR_INTEGER_OVERFLOW, d.errorCode().orElse(null));
  }
}
