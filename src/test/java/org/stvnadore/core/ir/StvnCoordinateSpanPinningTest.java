package org.stvnadore.core.ir;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.core.validation.MalformedPayloadException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test suite for precision coordinate span pinning [startOffset, endOffset).
 * <p>
 * Verifies that compiler diagnostics pinpoint exact offending token and delimiter spans.
 */
public class StvnCoordinateSpanPinningTest {

  @Test
  @DisplayName("TC-SPAN-01: Tuple arity underflow pins to closing parenthesis delimiter")
  void testTupleArityUnderflowPinning() {
    String source = """
        {
          :type :Tuple(:Int :Int)
          :body ( 42 )
        }
        """;
    var ex = assertThrows(MalformedPayloadException.class, () -> StvnCompiler.compilePayload(source));
    int rparen = source.lastIndexOf(')');
    assertEquals(rparen, ex.startOffset(), "Start offset must pin to closing delimiter");
    assertEquals(rparen + 1, ex.endOffset(), "End offset must cover closing delimiter length");
  }

  @Test
  @DisplayName("TC-SPAN-02: Tuple arity overflow pins to excess element span")
  void testTupleArityOverflowPinning() {
    String source = """
        {
          :type :Tuple(:Int)
          :body ( 42 84 126 )
        }
        """;
    var ex = assertThrows(MalformedPayloadException.class, () -> StvnCompiler.compilePayload(source));
    int startExcess = source.indexOf("84");
    int endExcess = source.indexOf("126") + "126".length();
    assertEquals(startExcess, ex.startOffset(), "Start offset must pin to first excess element");
    assertEquals(endExcess, ex.endOffset(), "End offset must cover all excess elements");
  }

  @Test
  @DisplayName("TC-SPAN-03: Prohibited metadata facet pins exact facet token coordinates")
  void testMetadataFacetCoordinatePinning() {
    String source = """
        {
          :defs {
            :Port { #minIncl 1 #maxIncl 65535 } :Int
          }
          :type :Port
          :body 8080
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_DISCRETE_BOUND_KIND_PROHIBITED, diag.errorCode().orElse(null));

    int expectedStart = source.indexOf("#maxIncl");
    int expectedEnd = source.indexOf("65535") + "65535".length();
    assertEquals(expectedStart, diag.startOffset(), "Start offset must match '#maxIncl'");
    assertEquals(expectedEnd, diag.endOffset(), "End offset must match end of '65535'");
  }

  @Test
  @DisplayName("TC-SPAN-04: Bare hash comment pins strictly to single character [offset, offset + 1)")
  void testBareHashCoordinatePinning() throws java.io.IOException {
    String source = java.nio.file.Files.readString(
        java.nio.file.Path.of("shared-fixtures/syntax/invalid/lexical/trap6_hash_comment.stvn")
    );
    var result = StvnCompiler.compileToResult(source);
    assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    int expectedStart = source.indexOf('#');
    assertEquals(expectedStart, diag.startOffset(), "Start offset must pinpoint bare hash character");
    assertEquals(expectedStart + 1, diag.endOffset(), "End offset must span exactly 1 character");
    assertEquals(DiagnosticBag.ERR_BARE_HASH_PROHIBITED, diag.errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-SPAN-05: Infix colon pins strictly to single character [offset, offset + 1)")
  void testInfixColonCoordinatePinning() throws java.io.IOException {
    String source = java.nio.file.Files.readString(
        java.nio.file.Path.of("shared-fixtures/syntax/invalid/lexical/trap7_infix_colon.stvn")
    );
    var result = StvnCompiler.compileToResult(source);
    assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    int colonOffset = source.indexOf(" : ") + 1; // Offset of bare infix colon
    assertEquals(colonOffset, diag.startOffset(), "Start offset must pinpoint bare colon character");
    assertEquals(colonOffset + 1, diag.endOffset(), "End offset must span exactly 1 character");
    assertEquals(DiagnosticBag.ERR_BARE_COLON_PROHIBITED, diag.errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-SPAN-06: ERR_DOCUMENT_KIND_MISMATCH pins exact #kind facet span")
  void testKindMismatchCoordinatePinning() {
    String source = """
        {
          :meta {
            #kind #DEFS
          }
          :type :Int
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(source, "main.stvn");
    assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH, diag.errorCode().orElse(null));
    int expectedStart = source.indexOf("#kind");
    int expectedEnd = source.indexOf("#DEFS") + "#DEFS".length();
    assertEquals(expectedStart, diag.startOffset());
    assertEquals(expectedEnd, diag.endOffset());
  }

  @Test
  @DisplayName("TC-SPAN-07: ERR_INVALID_FILENAME_STEM pins to root document opening token")
  void testBareDotfileCoordinatePinning() {
    String source = "{\n  :type :Int\n  :body 42\n}\n";
    var result = StvnCompiler.compileToResult(source, ".stvn");
    assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_INVALID_FILENAME_STEM, diag.errorCode().orElse(null));
    assertEquals(0, diag.startOffset());
    assertEquals(1, diag.endOffset());
  }

  @Test
  @DisplayName("TC-SPAN-08: ERR_META_POSITION_INVALID pins to misplaced :meta keyword token")
  void testMisplacedMetaCoordinatePinning() {
    String source = """
        {
          :defs { :T :Int }
          :meta { #kind #DEFS }
        }
        """;
    var result = StvnCompiler.compileToResult(source, "mod.stvn_d");
    assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_META_POSITION_INVALID, diag.errorCode().orElse(null));
    int metaStart = source.indexOf(":meta");
    assertEquals(metaStart, diag.startOffset());
    assertEquals(metaStart + ":meta".length(), diag.endOffset());
  }

  @Test
  @DisplayName("TC-SPAN-09: WARN_DOCUMENT_NAME_MISMATCH pins exact #name string literal span")
  void testNameMismatchCoordinatePinning() {
    String source = """
        {
          :meta {
            #name "login"
            #kind #DEFS
          }
          :defs {
            :Token :String
          }
        }
        """;
    var result = StvnCompiler.compileToResult(source, "auth.stvn_d");
    assertTrue(result.hasWarnings());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.WARN_DOCUMENT_NAME_MISMATCH, diag.errorCode().orElse(null));
    int strStart = source.indexOf("\"login\"");
    int strEnd = strStart + "\"login\"".length();
    assertEquals(strStart, diag.startOffset());
    assertEquals(strEnd, diag.endOffset());
  }

  @Test
  @DisplayName("TC-SPAN-10: WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME pins exact #domain string literal span")
  void testDomainOmittedCoordinatePinning() {
    String source = """
        {
          :meta {
            #name "auth"
            #domain "security"
            #kind #DEFS
          }
          :defs {
            :Token :String
          }
        }
        """;
    var result = StvnCompiler.compileToResult(source, "auth.stvn_d");
    assertTrue(result.hasWarnings());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME, diag.errorCode().orElse(null));
    int strStart = source.indexOf("\"security\"");
    int strEnd = strStart + "\"security\"".length();
    assertEquals(strStart, diag.startOffset());
    assertEquals(strEnd, diag.endOffset());
  }
}
