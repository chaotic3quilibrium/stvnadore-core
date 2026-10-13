package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnParserConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verification test suite for ANTLR metadata facet recovery and fault isolation remediation.
 */
public class StvnMetadataFacetRecoveryTest {

  @Test
  @DisplayName("TC-REC-01: Unrecognized metadata facet parsed without syntax errors")
  void testUnrecognizedFacetParsedWithoutSyntaxError() {
    String source = """
        {
          :defs {
            :Int64 { #sizex 64 } :Int
          }
          :type :Int64
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Unrecognized facet should trigger semantic validation error");

    List<StvnDiagnostic> diagnostics = result.diagnostics();
    boolean hasSyntaxError = diagnostics.stream()
        .anyMatch(d -> "STVN_SYNTAX_ERROR".equals(d.errorCode().orElse(null)));
    assertFalse(hasSyntaxError, "Must produce zero STVN_SYNTAX_ERROR diagnostics when unrecognized facet is encountered");

    boolean hasUnrecognizedFacetError = diagnostics.stream()
        .anyMatch(d -> DiagnosticBag.ERR_UNRECOGNIZED_METADATA_FACET.equals(d.errorCode().orElse(null)));
    assertTrue(hasUnrecognizedFacetError, "Must emit ERR_UNRECOGNIZED_METADATA_FACET");
  }

  @Test
  @DisplayName("TC-REC-02: Unrecognized metadata facet diagnostic coordinate pinning")
  void testUnrecognizedFacetCoordinatePinning() {
    String source = """
        {
          :defs {
            :Int64 { #sizex 64 } :Int
          }
          :type :Int64
          :body 42
        }
        """;
    int expectedStart = source.indexOf("#sizex");
    int expectedEnd = expectedStart + "#sizex".length();

    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_UNRECOGNIZED_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing ERR_UNRECOGNIZED_METADATA_FACET diagnostic"));

    assertEquals(expectedStart, diag.startOffset(), "Diagnostic start offset must pin exactly to #sizex");
    assertEquals(expectedEnd, diag.endOffset(), "Diagnostic end offset must pin exactly to #sizex end");
    assertEquals("Metadata violation (:Int64): Unrecognized constraint facet '#sizex'", diag.message());
  }

  @Test
  @DisplayName("TC-REC-03: Empty metadata block diagnostic suppression")
  void testEmptyMetadataBlockSuppression() {
    String source = """
        {
          :defs {
            :Int64 { #sizex 64 } :Int
          }
          :type :Int64
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    boolean hasEmptyBlockError = result.diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_EMPTY_METADATA_BLOCK.equals(d.errorCode().orElse(null)));
    assertFalse(hasEmptyBlockError, "ERR_EMPTY_METADATA_BLOCK must be suppressed when unknown facets exist");
  }

  @Test
  @DisplayName("TC-REC-04: Sibling definitions survive in package scope despite poisoned leaf")
  void testSiblingDefinitionsSurviveInPackage() {
    String source = """
        {
          :defs {
            :package :com/nyse/primitives {
              :Int64   { #sizex 64 } :Int
              :MicCode { #minSize 4 #maxSize 4 #regex "^[A-Z]{4}$" } :String
              :OrderId { #minSize 1 #maxSize 32 } :String
            }
          }
          :type :com/nyse/primitives/MicCode
          :body "XNYS"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Should record semantic error for #sizex");

    boolean hasUndefinedType = result.diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_UNDEFINED_TYPE.equals(d.errorCode().orElse(null))
            || d.message().contains("Undefined type"));
    assertFalse(hasUndefinedType, "Sibling type :com/nyse/primitives/MicCode must not trigger Undefined type");

    boolean hasSyntaxError = result.diagnostics().stream()
        .anyMatch(d -> "STVN_SYNTAX_ERROR".equals(d.errorCode().orElse(null)));
    assertFalse(hasSyntaxError, "Parser must not desynchronize on sibling definitions");
  }

  @Test
  @DisplayName("TC-REC-05: Transitive modular inclusion with poisoned leaf resolves valid siblings")
  void testTransitiveModularInclusionWithPoisonedLeaf(@TempDir Path tempDir) throws Exception {
    Path primitivesFile = tempDir.resolve("primitives.stvn_d");
    String primitivesSource = """
        {
          :meta {
            #name "primitives"
            #kind #DEFS
          }
          :defs {
            :Int64   { #sizex 64 } :Int
            :MicCode { #minSize 4 #maxSize 4 #regex "^[A-Z]{4}$" } :String
            :OrderId { #minSize 1 #maxSize 32 } :String
          }
        }
        """;
    Files.writeString(primitivesFile, primitivesSource);

    Path eventsFile = tempDir.resolve("events.stvn_i");
    String eventsSource = """
        {
          :meta {
            #name "events"
            #kind #BODY_INCLUDE
          }
          :defs {
            :include [ "./primitives.stvn_d" ]
          }
          :type :Tuple(:MicCode :OrderId)
          :body ( "XNYS" "ORD123" )
        }
        """;
    Files.writeString(eventsFile, eventsSource);

    var result = StvnCompiler.compileToResult(eventsSource, eventsFile.toString(), StvnParserConfig.DEFAULT);

    boolean hasUndefinedSibling = result.diagnostics().stream()
        .anyMatch(d -> d.message().contains("Undefined type :MicCode") || d.message().contains("Undefined type :OrderId"));
    assertFalse(hasUndefinedSibling, "Valid sibling types from imported module must resolve cleanly without cascading undefined type errors");
  }
}
