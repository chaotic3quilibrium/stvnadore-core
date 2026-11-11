package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.ir.StvnDocumentKind;
import org.stvnadore.core.ir.StvnDocumentMeta;
import org.stvnadore.core.binary.BinaryEncodingStrategy;
import org.stvnadore.core.binary.SchemaIdentityStrategy;
import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.binary.StvnBinaryEncoder;
import org.stvnadore.core.binary.StvnSchemaHasher;
import org.stvnadore.core.binary.exceptions.PoisonedRegistryPayloadException;
import org.stvnadore.core.binary.exceptions.StvnCorruptedBitPatternException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test suite for document identity validation, filename stem decomposition,
 * extension mapping, and diagnostic severity classification.
 *
 * @since 2.0.0
 */
public class StvnDocumentIdentityValidationTest {

  @Test
  @DisplayName("TC-ID-01: In-memory source omitting :meta allows default wildcard")
  void testMissingMetaAllowsDefaultWildcard() {
    String source = """
        {
          :type :Int
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    assertTrue(result.isSuccess(), "In-memory document without :meta must compile cleanly: " + result.diagnostics());
    assertTrue(result.document().isPresent());
    var doc = result.document().get();
    assertTrue(doc.meta().isEmpty(), "Omitted :meta yields empty Optional<StvnDocumentMeta>");
  }

  @Test
  @DisplayName("TC-ID-02: Physical .stvn document omitting :meta defaults cleanly to BODY")
  void testBareDocumentWithStvnExtensionAllowsOmittedMeta() {
    String source = """
        {
          :type :Int
          :body 100
        }
        """;
    var result = StvnCompiler.compileToResult(source, "main.stvn");
    assertTrue(result.isSuccess(), ".stvn omitting :meta defaults to unconstrained BODY");
    assertFalse(result.hasErrors());
    assertFalse(result.hasWarnings());
  }

  @Test
  @DisplayName("TC-ID-03: Physical .stvn_d definitions module requires mandatory :meta header")
  void testDefinitionsDocumentRequiresMetaWhenExtensionIsStvnD() {
    String source = """
        {
          :defs {
            :MyType :Int
          }
        }
        """;
    var result = StvnCompiler.compileToResult(source, "common.stvn_d");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("Mandatory :meta block missing"));
  }

  @Test
  @DisplayName("TC-ID-04: Physical .stvn_di modular definitions module requires mandatory :meta header")
  void testModularDefinitionsDocumentRequiresMetaWhenExtensionIsStvnDi() {
    String source = """
        {
          :defs {
            :MyType :Int
          }
        }
        """;
    var result = StvnCompiler.compileToResult(source, "common.stvn_di");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("Mandatory :meta block missing"));
  }

  @Test
  @DisplayName("TC-ID-05: Physical .stvn_i modular body document requires mandatory :meta header")
  void testModularBodyDocumentRequiresMetaWhenExtensionIsStvnI() {
    String source = """
        {
          :type :Int
          :body 50
        }
        """;
    var result = StvnCompiler.compileToResult(source, "payload.stvn_i");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("Mandatory :meta block missing"));
    assertTrue(diag.message().contains("#BODY_INCLUDE"));
  }

  @Test
  @DisplayName("TC-ID-06: Kind mismatch between declared #kind and physical file extension emits fatal error")
  void testKindMismatchBetweenHeaderAndExtension() {
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
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("Document kind '#DEFS' does not match file extension '.stvn'"));
  }

  @Test
  @DisplayName("TC-ID-07: Kind mismatch between #BODY_INCLUDE and .stvn extension emits fatal error")
  void testKindMismatchBetweenBodyIncludeAndStvn() {
    String source = """
        {
          :meta {
            #kind #BODY_INCLUDE
          }
          :type :Int
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(source, "payload.stvn");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH, diag.errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-ID-08: Bare dotfile lacking visible stem segment (.stvn_di) emits ERR_INVALID_FILENAME_STEM")
  void testNonEmptyStemInvariantBareDotfileRejected() {
    String source = """
        {
          :meta {
            #kind #DEFS_INCLUDE
          }
          :defs {
            :MyType :Int
          }
        }
        """;
    var result = StvnCompiler.compileToResult(source, ".stvn_di");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_INVALID_FILENAME_STEM, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("bare dotfiles are prohibited"));
  }

  @Test
  @DisplayName("TC-ID-09: Bare dotfile (.stvn) emits ERR_INVALID_FILENAME_STEM")
  void testNonEmptyStemInvariantBareStvnRejected() {
    String source = """
        {
          :type :Int
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(source, ".stvn");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_INVALID_FILENAME_STEM, diag.errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-ID-10: Stem dot splitting binds slot 0 strictly to #name")
  void testStemDotSplittingBindsNameToSlot0() {
    String source = """
        {
          :meta {
            #name "auth"
            #kind #DEFS
          }
          :defs {
            :Token :String
          }
        }
        """;
    var result = StvnCompiler.compileToResult(source, "auth.stvn_d");
    assertTrue(result.isSuccess(), "Stem slot 0 matching #name compiles cleanly");
    assertFalse(result.hasWarnings());
    var doc = result.document().orElseThrow();
    assertEquals("auth", doc.requireMeta().name().orElse(null));
    assertEquals(StvnDocumentKind.DEFS, doc.requireMeta().kind().orElseThrow());
  }

  @Test
  @DisplayName("TC-ID-11: Stem dot splitting binds slot 1 strictly to #domain")
  void testStemDotSplittingBindsDomainToSlot1() {
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
    var result = StvnCompiler.compileToResult(source, "auth.security.stvn_d");
    assertTrue(result.isSuccess());
    assertFalse(result.hasWarnings());
    var doc = result.document().orElseThrow();
    assertEquals("auth", doc.requireMeta().name().orElse(null));
    assertEquals("security", doc.requireMeta().domain().orElse(null));
  }

  @Test
  @DisplayName("TC-ID-12: Declared #name discrepancy against filename stem emits non-fatal warning")
  void testStemNameMismatchEmitsWarning() {
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
    assertTrue(result.isSuccess(), "Name mismatch must be a non-fatal warning");
    assertFalse(result.hasErrors());
    assertTrue(result.hasWarnings());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.WARN_DOCUMENT_NAME_MISMATCH, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("Declared #name 'login' does not match filename stem slot 0 'auth'"));
  }

  @Test
  @DisplayName("TC-ID-13: Declared #domain discrepancy against filename stem slot 1 emits non-fatal warning")
  void testStemDomainMismatchEmitsWarning() {
    String source = """
        {
          :meta {
            #name "auth"
            #domain "billing"
            #kind #DEFS
          }
          :defs {
            :Token :String
          }
        }
        """;
    var result = StvnCompiler.compileToResult(source, "auth.security.stvn_d");
    assertTrue(result.isSuccess());
    assertFalse(result.hasErrors());
    assertTrue(result.hasWarnings());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.WARN_DOCUMENT_NAME_MISMATCH, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("Declared #domain 'billing' does not match filename stem slot 1 'security'"));
  }

  @Test
  @DisplayName("TC-ID-14: Declared #domain omitted from filename stem emits WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME")
  void testStemDomainOmittedInFilenameEmitsWarning() {
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
    assertTrue(result.isSuccess());
    assertFalse(result.hasErrors());
    assertTrue(result.hasWarnings());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("Declared #domain 'security' is omitted from filename stem slot 1"));
  }

  @Test
  @DisplayName("TC-ID-15: Omitting #name and #domain on a 1-stem filename represents an unconstrained wildcard")
  void testStemWildcardOmissionPassesCleanly() {
    String source = """
        {
          :meta {
            #kind #DEFS
          }
          :defs {
            :Token :String
          }
        }
        """;
    var result = StvnCompiler.compileToResult(source, "arbitrary_stem_name.stvn_d");
    assertTrue(result.isSuccess(), "Wildcard metadata compiles cleanly without warnings");
    assertFalse(result.hasWarnings());
    var doc = result.document().orElseThrow();
    assertTrue(doc.requireMeta().name().isEmpty());
    assertTrue(doc.requireMeta().domain().isEmpty());
    assertEquals(StvnDocumentKind.DEFS, doc.requireMeta().kind().orElseThrow());
  }

  @Test
  @DisplayName("TC-ID-15a: Two-stem user filename omitting #domain in :meta emits W002 WARN_DOCUMENT_DOMAIN_OMITTED_IN_META pinned to :meta")
  void testTwoStemFilenameOmittingDomainInMetaEmitsW002() {
    String source = """
        {
          :meta {
            #name "use_nested"
            #kind #BODY_INCLUDE
          }
          :defs {
            :use [ :org/stvnadore/prelude { :Port :Port } ]
          }
          :type :Port
          :body 8080
        }
        """;
    var result = StvnCompiler.compileToResult(source, "use_nested.json.stvn_i");
    assertTrue(result.isSuccess());
    assertFalse(result.hasErrors());
    assertTrue(result.hasWarnings());
    assertEquals(1, result.diagnostics().size());
    var diag = result.diagnostics().getFirst();
    assertEquals("W002", diag.canonicalCode().orElse(null));
    assertEquals(DiagnosticBag.WARN_DOCUMENT_DOMAIN_OMITTED_IN_META, diag.symbolicId().orElse(null));
    assertEquals(
        "Filename stem slot 1 declares domain 'json', but :meta omits #domain \"json\"",
        diag.message()
    );
    int expectedStart = source.indexOf(":meta {");
    int expectedEnd = source.indexOf("}", expectedStart) + 1;
    assertEquals(expectedStart, diag.startOffset());
    assertEquals(expectedEnd, diag.endOffset());
  }

  @Test
  @DisplayName("TC-ID-15b: Two-stem binary .stvn_b and .stvn_bd frames with Byte 5 HAS_DOMAIN == 0 emit W002 pinned to Byte 5 [5, 6)")
  void testTwoStemBinaryFramesOmittingDomainEmitW002AtByte5() {
    String payloadSchema = "{ :type :Int :body 0 }";
    var ir = StvnCompiler.compilePayload("{ :type :Int :body 42 }").orElseThrow();
    var meta = new StvnDocumentMeta(Optional.of("use_nested"), Optional.empty(), Optional.of(StvnDocumentKind.BODY));
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.SelfDescribingSchema(payloadSchema));
    var buf = encoder.encode(ir, meta);
    byte[] binBytes = new byte[buf.remaining()];
    buf.get(binBytes);

    var binResult = StvnCompiler.compileToResult(binBytes, "use_nested.json.stvn_b");
    assertFalse(binResult.hasErrors());
    assertEquals(1, binResult.diagnostics().size());
    var binDiag = binResult.diagnostics().getFirst();
    assertEquals("W002", binDiag.canonicalCode().orElse(null));
    assertEquals(DiagnosticBag.WARN_DOCUMENT_DOMAIN_OMITTED_IN_META, binDiag.symbolicId().orElse(null));
    assertEquals(5, binDiag.startOffset());
    assertEquals(6, binDiag.endOffset());
    assertEquals(1, binDiag.line());
    assertEquals(5, binDiag.column());

    String defsSource = "{ :meta { #name \"schema\" #kind #DEFS } :defs { :Token { #minSize 1 #maxSize 32 } :String } }";
    var defsDoc = StvnCompiler.compile(defsSource, "schema.stvn_d", StvnParserConfig.DEFAULT).document().orElseThrow();
    var bdEncoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.SelfDescribingSchema(defsSource));
    var bdBuf = bdEncoder.encode(defsDoc);
    byte[] bdBytes = new byte[bdBuf.remaining()];
    bdBuf.get(bdBytes);

    var bdResult = StvnCompiler.compileToResult(bdBytes, "schema.json.stvn_bd");
    assertFalse(bdResult.hasErrors());
    assertEquals(1, bdResult.diagnostics().size(), "Must emit exactly one outer Byte 5 W002 warning without duplicate inner warning");
    var bdDiag = bdResult.diagnostics().getFirst();
    assertEquals("W002", bdDiag.canonicalCode().orElse(null));
    assertEquals(DiagnosticBag.WARN_DOCUMENT_DOMAIN_OMITTED_IN_META, bdDiag.symbolicId().orElse(null));
    assertEquals(5, bdDiag.startOffset());
    assertEquals(6, bdDiag.endOffset());
    assertEquals(1, bdDiag.line());
    assertEquals(5, bdDiag.column());
  }

  @Test
  @DisplayName("TC-ID-15c: Malformed or invalid #domain in a 2-stem file does not cascade WARN_DOCUMENT_DOMAIN_OMITTED_IN_META")
  void testMalformedOrInvalidDomainInTwoStemFileDoesNotCascadeW002() {
    String badEscapeSource = """
        {
          :meta {
            #name "use_nested"
            #domain "\\q"
            #kind #BODY_INCLUDE
          }
          :defs {
            :use [ :org/stvnadore/prelude { :Port :Port } ]
          }
          :type :Port
          :body 8080
        }
        """;
    var resEscape = StvnCompiler.compileToResult(badEscapeSource, "use_nested.json.stvn_i");
    assertTrue(resEscape.hasErrors());
    assertFalse(
        resEscape.diagnostics().stream()
            .anyMatch(d -> DiagnosticBag.WARN_DOCUMENT_DOMAIN_OMITTED_IN_META.equals(d.symbolicId().orElse(null))),
        "Syntax/escape error in #domain literal must not cascade WARN_DOCUMENT_DOMAIN_OMITTED_IN_META"
    );

    String badPosixSource = """
        {
          :meta {
            #name "use_nested"
            #domain "bad.domain"
            #kind #BODY_INCLUDE
          }
          :defs {
            :use [ :org/stvnadore/prelude { :Port :Port } ]
          }
          :type :Port
          :body 8080
        }
        """;
    var resPosix = StvnCompiler.compileToResult(badPosixSource, "use_nested.json.stvn_i");
    assertTrue(resPosix.hasErrors());
    assertFalse(
        resPosix.diagnostics().stream()
            .anyMatch(d -> DiagnosticBag.WARN_DOCUMENT_DOMAIN_OMITTED_IN_META.equals(d.symbolicId().orElse(null))),
        "Invalid POSIX #domain must not emit WARN_DOCUMENT_DOMAIN_OMITTED_IN_META"
    );
  }

  @Test
  @DisplayName("TC-ID-15d: Reserved system substrate 2-stem filename (.stvn_ir.) omitting #domain emits zero W002 warnings")
  void testReservedSubstrateTwoStemFilenameOmittingDomainEmitsZeroWarnings() {
    String source = """
        {
          :meta {
            #name "snap"
            #kind #DEFS
          }
          :defs {
            :IrNode :Int
          }
        }
        """;
    var result = StvnCompiler.compileToResult(source, "snap.stvn_ir.stvn_d", StvnParserConfig.SYSTEM);
    assertTrue(result.isSuccess());
    assertFalse(result.hasErrors());
    assertFalse(result.hasWarnings(), "Reserved stvn_* stem slot 1 must not trigger WARN_DOCUMENT_DOMAIN_OMITTED_IN_META");
  }

  @Test
  @DisplayName("TC-ID-16: Excised legacy extension .stvn_incl fails closed immediately")
  void testLegacyExtensionStvnInclRejected() {
    String source = "{ :defs { :T :Int } }";
    var result = StvnCompiler.compileToResult(source, "module.stvn_incl");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_LEGACY_FILE_EXTENSION_PURGED, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("permanently prohibited"));
  }

  @Test
  @DisplayName("TC-ID-17: Excised legacy extension .stvn_inclf fails closed immediately")
  void testLegacyExtensionStvnInclfRejected() {
    String source = "{ :defs { :T :Int } }";
    var result = StvnCompiler.compileToResult(source, "module.stvn_inclf");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_LEGACY_FILE_EXTENSION_PURGED, diag.errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-ID-18: Excised legacy extension .stvn_cas fails closed immediately")
  void testLegacyExtensionStvnCasRejected() {
    String source = "{ :defs { :T :Int } }";
    var result = StvnCompiler.compileToResult(source, "schema.stvn_cas");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_LEGACY_FILE_EXTENSION_PURGED, diag.errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-ID-19: Excised legacy extension .stvn_bin fails closed immediately")
  void testLegacyExtensionStvnBinRejected() {
    String source = "{ :type :Int :body 1 }";
    var result = StvnCompiler.compileToResult(source, "payload.stvn_bin");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_LEGACY_FILE_EXTENSION_PURGED, diag.errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-ID-19a: Excised legacy extension .stvn_f fails closed immediately")
  void testLegacyExtensionStvnFRejected() {
    String source = "{ :type :Int :body 1 }";
    var result = StvnCompiler.compileToResult(source, "payload.stvn_f");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_LEGACY_FILE_EXTENSION_PURGED, diag.errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-ID-19b: Excised legacy extension .stvn_df fails closed immediately")
  void testLegacyExtensionStvnDfRejected() {
    String source = "{ :meta { #kind #DEFS } :defs { :T :Int } }";
    var result = StvnCompiler.compileToResult(source, "module.stvn_df");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_LEGACY_FILE_EXTENSION_PURGED, diag.errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-ID-20: Empty metadata block :meta {} emits ERR_EMPTY_META_BLOCK")
  void testEmptyMetadataBlockRejected() {
    String source = """
        {
          :meta {}
          :defs {
            :T :Int
          }
        }
        """;
    var result = StvnCompiler.compileToResult(source, "common.stvn_d");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_EMPTY_META_BLOCK, diag.errorCode().orElse(null));
    assertEquals(
        "Empty ':meta {}' section is prohibited; declare document identity facets (#name, #domain, #kind)",
        diag.message()
    );
  }

  @Test
  @DisplayName("TC-ID-21: Duplicate metadata facet tag emits ERR_DUPLICATE_METADATA_FACET")
  void testDuplicateMetadataFacetTagRejected() {
    String source = """
        {
          :meta {
            #name "mod1"
            #name "mod2"
            #kind #DEFS
          }
          :defs {
            :T :Int
          }
        }
        """;
    var result = StvnCompiler.compileToResult(source, "mod1.stvn_d");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_DUPLICATE_METADATA_FACET, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("Duplicate metadata facet tag '#name'"));
  }

  @Test
  @DisplayName("TC-ID-22: Facet ordering violation emits ERR_FACET_ORDER_VIOLATION")
  void testFacetOrderMonotonicProgressionEnforced() {
    String source = """
        {
          :meta {
            #kind #DEFS
            #name "mod"
          }
          :defs {
            :T :Int
          }
        }
        """;
    var result = StvnCompiler.compileToResult(source, "mod.stvn_d");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_FACET_ORDER_VIOLATION, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("3-tier monotonic progression"));
  }

  @Test
  @DisplayName("TC-ID-23: Unrecognized facet tag in :meta emits ERR_INVALID_METADATA_FACET")
  void testInvalidMetadataFacetTagRejected() {
    String source = """
        {
          :meta {
            #author "Alice"
            #kind #DEFS
          }
          :defs {
            :T :Int
          }
        }
        """;
    var result = StvnCompiler.compileToResult(source, "mod.stvn_d");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_INVALID_METADATA_FACET, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("Invalid metadata facet tag '#author'"));
  }

  @Test
  @DisplayName("TC-ID-24: Non-POSIX character in #name emits ERR_INVALID_METADATA_FACET")
  void testInvalidPosixPatternInNameRejected() {
    String source = """
        {
          :meta {
            #name "invalid name!"
            #kind #DEFS
          }
          :defs {
            :T :Int
          }
        }
        """;
    var result = StvnCompiler.compileToResult(source, "mod.stvn_d");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_INVALID_METADATA_FACET, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("POSIX pattern"));
  }

  @Test
  @DisplayName("TC-ID-25: Misplaced :meta header declared after :defs emits ERR_META_POSITION_INVALID")
  void testMisplacedMetaAfterDefsRejected() {
    String source = """
        {
          :defs {
            :T :Int
          }
          :meta {
            #kind #DEFS
          }
        }
        """;
    var result = StvnCompiler.compileToResult(source, "mod.stvn_d");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_META_POSITION_INVALID, diag.errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-ID-26: Canonical .stvn document with explicit :meta compiles cleanly")
  void testCanonicalStvnWithExplicitMetaCompilesCleanly() {
    String source = """
        {
          :meta {
            #name "payload"
            #kind #BODY
          }
          :type :Int
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(source, "payload.stvn");
    assertTrue(result.isSuccess());
    assertFalse(result.hasErrors());
    assertFalse(result.hasWarnings());
    var doc = result.document().orElseThrow();
    assertEquals("payload", doc.requireMeta().name().orElse(null));
    assertEquals(StvnDocumentKind.BODY, doc.requireMeta().kind().orElseThrow());
  }

  @Test
  @DisplayName("TC-ID-27: Canonical .stvn_i document with explicit :meta compiles cleanly")
  void testCanonicalStvnIWithExplicitMetaCompilesCleanly() {
    String source = """
        {
          :meta {
            #name "modular_payload"
            #kind #BODY_INCLUDE
          }
          :type :Int
          :body 84
        }
        """;
    var result = StvnCompiler.compileToResult(source, "modular_payload.stvn_i");
    assertTrue(result.isSuccess());
    assertFalse(result.hasErrors());
    assertFalse(result.hasWarnings());
    var doc = result.document().orElseThrow();
    assertEquals("modular_payload", doc.requireMeta().name().orElse(null));
    assertEquals(StvnDocumentKind.BODY_INCLUDE, doc.requireMeta().kind().orElseThrow());
  }

  @Test
  @DisplayName("TC-ID-28: Canonical .stvn_b binary document requires mandatory :meta header")
  void testBinaryDocumentRequiresMetaWhenExtensionIsStvnB() {
    String source = """
        {
          :type :Int
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(source, "binary_payload.stvn_b");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("Mandatory :meta block missing"));
  }

  @Test
  @DisplayName("TC-ID-29: Canonical .stvn_bd binary definitions schema requires mandatory :meta header")
  void testBinaryDefinitionsDocumentRequiresMetaWhenExtensionIsStvnBd() {
    String source = """
        {
          :defs {
            :MyType :Int
          }
        }
        """;
    var result = StvnCompiler.compileToResult(source, "binary_schema.stvn_bd");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("Mandatory :meta block missing"));
  }

  @Test
  @DisplayName("TC-ID-30: Canonical .stvn_b with matching :meta header compiles cleanly")
  void testBinaryDocumentWithMatchingMetaCompilesCleanly() {
    String source = """
        {
          :meta {
            #name "binary_payload"
            #kind #BODY
          }
          :type :Int
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(source, "binary_payload.stvn_b");
    assertTrue(result.isSuccess());
    assertFalse(result.hasErrors());
    assertFalse(result.hasWarnings());
  }

  @Test
  @DisplayName("TC-ID-31: Kind mismatch between #DEFS and .stvn_b emits fatal error")
  void testKindMismatchBetweenDefsAndStvnB() {
    String source = """
        {
          :meta {
            #kind #DEFS
          }
          :type :Int
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(source, "payload.stvn_b");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    assertEquals(DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH, result.diagnostics().getFirst().errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-ID-32: Prohibited :include in flat .stvn payload emits ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT")
  void testIncludesProhibitedInFlatStvnDocument() {
    String source = """
        {
          :meta {
            #kind #BODY
          }
          :defs {
            :include [ "module.stvn_di" ]
          }
          :type :Int
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(source, "main.stvn");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    assertEquals(DiagnosticBag.ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT, result.diagnostics().getFirst().errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-ID-33: Prohibited :include in flat .stvn_d schema emits ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT")
  void testIncludesProhibitedInFlatStvnDSchema() {
    String source = """
        {
          :meta {
            #kind #DEFS
          }
          :defs {
            :include [ "module.stvn_di" ]
          }
        }
        """;
    var result = StvnCompiler.compileToResult(source, "schema.stvn_d");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    assertEquals(DiagnosticBag.ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT, result.diagnostics().getFirst().errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-ID-34: Prohibited :include in binary .stvn_b document emits ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT")
  void testIncludesProhibitedInBinaryStvnBDocument() {
    String source = """
        {
          :meta {
            #kind #BODY
          }
          :defs {
            :include [ "module.stvn_di" ]
          }
          :type :Int
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(source, "payload.stvn_b");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    assertEquals(DiagnosticBag.ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT, result.diagnostics().getFirst().errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-ID-35: Excised legacy extension .stvn_bf fails closed immediately")
  void testLegacyExtensionStvnBfRejected() {
    String source = "{ :type :Int :body 1 }";
    var result = StvnCompiler.compileToResult(source, "payload.stvn_bf");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    assertEquals(DiagnosticBag.ERR_LEGACY_FILE_EXTENSION_PURGED, result.diagnostics().getFirst().errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-ID-36: Excised legacy extension .stvn_bdf fails closed immediately")
  void testLegacyExtensionStvnBdfRejected() {
    String source = "{ :meta { #kind #DEFS } :defs { :T :Int } }";
    var result = StvnCompiler.compileToResult(source, "schema.stvn_bdf");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    assertEquals(DiagnosticBag.ERR_LEGACY_FILE_EXTENSION_PURGED, result.diagnostics().getFirst().errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-ID-BIN-01: Excised legacy extension .stvn_bin fails closed in binary ingress")
  void testBinaryLegacyExtensionStvnBinRejected() {
    var ir = StvnCompiler.compilePayload("{ :type :Int :body 42 }").orElseThrow();
    var meta = new StvnDocumentMeta(Optional.of("payload"), Optional.empty(), Optional.of(StvnDocumentKind.BODY));
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
    var buf = encoder.encode(ir, meta);
    byte[] bytes = new byte[buf.remaining()];
    buf.get(bytes);

    var result = StvnCompiler.compile(bytes, "payload.stvn_bin", StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    assertEquals(DiagnosticBag.ERR_LEGACY_FILE_EXTENSION_PURGED, result.diagnostics().getFirst().errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-ID-BIN-02: Excised legacy extension .stvn_bf fails closed in binary ingress")
  void testBinaryLegacyExtensionStvnBfRejected() {
    var ir = StvnCompiler.compilePayload("{ :type :Int :body 42 }").orElseThrow();
    var meta = new StvnDocumentMeta(Optional.of("payload"), Optional.empty(), Optional.of(StvnDocumentKind.BODY));
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
    var buf = encoder.encode(ir, meta);
    byte[] bytes = new byte[buf.remaining()];
    buf.get(bytes);

    var result = StvnCompiler.compile(bytes, "payload.stvn_bf", StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    assertEquals(DiagnosticBag.ERR_LEGACY_FILE_EXTENSION_PURGED, result.diagnostics().getFirst().errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-ID-BIN-03: Excised legacy extension .stvn_bdf fails closed in binary ingress")
  void testBinaryLegacyExtensionStvnBdfRejected() {
    var ir = StvnCompiler.compilePayload("{ :type :Int :body 42 }").orElseThrow();
    var meta = new StvnDocumentMeta(Optional.of("schema"), Optional.empty(), Optional.of(StvnDocumentKind.DEFS));
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
    var buf = encoder.encode(ir, meta);
    byte[] bytes = new byte[buf.remaining()];
    buf.get(bytes);

    var result = StvnCompiler.compile(bytes, "schema.stvn_bdf", StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    assertEquals(DiagnosticBag.ERR_LEGACY_FILE_EXTENSION_PURGED, result.diagnostics().getFirst().errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-ID-37: Non-POSIX character in #domain emits ERR_INVALID_METADATA_FACET")
  void testInvalidPosixPatternInDomainRejected() {
    String source = """
        {
          :meta {
            #domain "invalid domain!"
            #kind #DEFS
          }
          :defs {
            :T :Int
          }
        }
        """;
    var result = StvnCompiler.compileToResult(source, "mod.stvn_d");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    assertEquals(DiagnosticBag.ERR_INVALID_METADATA_FACET, result.diagnostics().getFirst().errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-ID-38: Bare dotfile (.stvn_b) emits ERR_INVALID_FILENAME_STEM")
  void testBareStvnBRejected() {
    String source = "{ :meta { #kind #BODY } :type :Int :body 1 }";
    var result = StvnCompiler.compileToResult(source, ".stvn_b");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    assertEquals(DiagnosticBag.ERR_INVALID_FILENAME_STEM, result.diagnostics().getFirst().errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-ID-38a: Tag subset variation (#domain, #kind) with slot 1 matching compiles cleanly")
  void testTagSubsetDomainAndKindCompilesCleanly() {
    String source = """
        {
          :meta {
            #domain "auth"
            #kind #DEFS
          }
          :defs {
            :T :Int
          }
        }
        """;
    var result = StvnCompiler.compileToResult(source, "wildcard.auth.stvn_d");
    assertTrue(result.isSuccess());
    assertFalse(result.hasErrors());
    assertFalse(result.hasWarnings());
    var doc = result.document().orElseThrow();
    assertTrue(doc.requireMeta().name().isEmpty());
    assertEquals("auth", doc.requireMeta().domain().orElse(null));
  }
  @Test
  @DisplayName("TC-ID-39: User document declaring #domain stvn_custom emits fatal ERR_RESERVED_DOMAIN_PROHIBITED")
  void testUserDocumentDeclaringStvnCustomDomainEmitsReservedError() {
    String source = """
        {
          :meta {
            #name "custom"
            #domain "stvn_custom"
            #kind #BODY
          }
          :type :Int
          :body 10
        }
        """;
    var result = StvnCompiler.compileToResult(source, "custom.stvn_custom.stvn");
    assertTrue(result.hasErrors(), "User compilation must reject domain with stvn_ prefix");
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("domains with prefix 'stvn_' are strictly reserved for system artifacts"));
  }

  @Test
  @DisplayName("TC-ID-40: System document (SYSTEM_STRICT) declaring #domain stvn_custom compiles cleanly")
  void testSystemDocumentDeclaringStvnCustomDomainCompilesCleanly() {
    String source = """
        {
          :meta {
            #name "custom"
            #domain "stvn_custom"
            #kind #BODY
          }
          :type :Int
          :body 10
        }
        """;
    var result = StvnCompiler.compileToResult(source, "custom.stvn_custom.stvn", StvnParserConfig.SYSTEM_STRICT);
    assertTrue(result.isSuccess(), "System document under SYSTEM_STRICT must compile cleanly: " + result.diagnostics());
    assertFalse(result.hasErrors());
  }

  @Test
  @DisplayName("TC-ID-41: Filename this.is.a.file.domain.stvn with 5 stem segments emits fatal ERR_INVALID_FILENAME_STEM")
  void testMultiStemFragmentationEmitsFatalStemError() {
    String source = """
        {
          :meta {
            #name "this"
            #domain "is"
            #kind #BODY
          }
          :type :Int
          :body 10
        }
        """;
    var result = StvnCompiler.compileToResult(source, "this.is.a.file.domain.stvn");
    assertTrue(result.hasErrors(), "Filename with 5 stems must fail closed");
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_INVALID_FILENAME_STEM, diag.errorCode().orElse(null));
    assertEquals("Invalid filename stem 'this.is.a.file.domain.stvn'; filename must contain exactly one or two dot-separated stem segments (<name>.<ext> or <name>.<domain>.<ext>)", diag.message());
  }

  @Test
  @DisplayName("TC-ID-42: Facet #name foo.bar with dot character emits fatal ERR_INVALID_METADATA_FACET")
  void testFacetNameWithDotEmitsInvalidMetadataFacet() {
    String source = """
        {
          :meta {
            #name "foo.bar"
            #kind #BODY
          }
          :type :Int
          :body 10
        }
        """;
    var result = StvnCompiler.compileToResult(source, "valid_file.stvn");
    assertTrue(result.hasErrors(), "Facet #name with dot must fail POSIX validation");
    var diag = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_INVALID_METADATA_FACET, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("does not match required atomic POSIX pattern"));
  }

  @Test
  @DisplayName("TC-ID-43: Filename this_is-a-file_01.telemetry.stvn compiles warning-free")
  void testPosixCompliantMultiWordTwoStemCompilesCleanly() {
    String source = """
        {
          :meta {
            #name "this_is-a-file_01"
            #domain "telemetry"
            #kind #BODY
          }
          :type :Int
          :body 100
        }
        """;
    var result = StvnCompiler.compileToResult(source, "this_is-a-file_01.telemetry.stvn");
    assertTrue(result.isSuccess(), "Compliant two-stem document must compile cleanly: " + result.diagnostics());
    assertFalse(result.hasErrors());
    assertFalse(result.hasWarnings());
  }

  // =========================================================================
  // TC-BIN-ID-01 .. TC-BIN-ID-15: Binary Wire Document Identity & Metadata
  // =========================================================================

  @Test
  @DisplayName("TC-BIN-ID-01: Binary .stvn_b with #BODY kind and matching #name compiles cleanly")
  void testBinaryStvnBWithBodyKindCompilesCleanly() {
    String schema = "{ :type :Int :body 0 }";
    var ir = StvnCompiler.compilePayload("{ :type :Int :body 42 }").orElseThrow();
    var meta = new StvnDocumentMeta(Optional.of("valid_name"), Optional.empty(), Optional.of(StvnDocumentKind.BODY));
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.SelfDescribingSchema(schema));
    var buf = encoder.encode(ir, meta);
    byte[] bytes = new byte[buf.remaining()];
    buf.get(bytes);

    var result = StvnCompiler.compileToResult(bytes, "valid_name.stvn_b");
    assertTrue(result.isSuccess(), "Binary compilation must succeed: " + result.diagnostics());
    assertFalse(result.hasErrors());
    var doc = result.document().orElseThrow();
    assertTrue(doc.meta().isPresent());
    var decodedMeta = doc.meta().get();
    assertEquals("valid_name", decodedMeta.name().orElse(null));
    assertEquals(Optional.of(StvnDocumentKind.BODY), decodedMeta.kind());
  }

  @Test
  @DisplayName("TC-BIN-ID-02: Binary .stvn_b with #DEFS kind fails closed with ERR_DOCUMENT_KIND_MISMATCH")
  void testBinaryStvnBWithDefsKindFailsClosed() {
    var ir = StvnCompiler.compilePayload("{ :type :Int :body 42 }").orElseThrow();
    var meta = new StvnDocumentMeta(Optional.of("valid_name"), Optional.empty(), Optional.of(StvnDocumentKind.DEFS));
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
    var buf = encoder.encode(ir, meta);
    byte[] bytes = new byte[buf.remaining()];
    buf.get(bytes);

    var result = StvnCompiler.compileToResult(bytes, "valid_name.stvn_b");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    assertEquals(DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH, result.diagnostics().getFirst().errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-BIN-ID-03: Binary .stvn_bd with #DEFS kind and matching stem compiles cleanly")
  void testBinaryStvnBdWithDefsKindCompilesCleanly() {
    String schema = "{ :meta { #name \"my_defs\" #kind #DEFS } :defs { :T :Int } }";
    var defsDoc = StvnCompiler.compile(schema, "my_defs.stvn_d", StvnParserConfig.DEFAULT).document().orElseThrow();
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.SelfDescribingSchema(schema));
    var buf = encoder.encode(defsDoc);
    byte[] bytes = new byte[buf.remaining()];
    buf.get(bytes);

    var result = StvnCompiler.compileToResult(bytes, "my_defs.stvn_bd");
    assertTrue(result.isSuccess(), "Binary .stvn_bd compilation must succeed: " + result.diagnostics());
    assertFalse(result.hasErrors());
    var doc = result.document().orElseThrow();
    assertTrue(doc.hasDefsSection());
    assertTrue(doc.meta().isPresent());
    var decodedMeta = doc.meta().get();
    assertEquals("my_defs", decodedMeta.name().orElse(null));
    assertEquals(Optional.of(StvnDocumentKind.DEFS), decodedMeta.kind());
  }

  @Test
  @DisplayName("TC-BIN-ID-04: Binary .stvn_bd with #BODY kind fails closed with ERR_DOCUMENT_KIND_MISMATCH")
  void testBinaryStvnBdWithBodyKindFailsClosed() {
    var ir = StvnCompiler.compilePayload("{ :type :Int :body 42 }").orElseThrow();
    var meta = new StvnDocumentMeta(Optional.of("my_defs"), Optional.empty(), Optional.of(StvnDocumentKind.BODY));
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
    var buf = encoder.encode(ir, meta);
    byte[] bytes = new byte[buf.remaining()];
    buf.get(bytes);

    var result = StvnCompiler.compileToResult(bytes, "my_defs.stvn_bd");
    assertFalse(result.isSuccess());
    assertTrue(result.hasErrors());
    assertEquals(DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH, result.diagnostics().getFirst().errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-BIN-ID-05: Permutation neither: HAS_NAME=0, HAS_DOMAIN=0 decodes 1-byte metadata frame")
  void testPermutationNeitherNameNorDomain() {
    var ir = StvnCompiler.compilePayload("{ :type :Int :body 42 }").orElseThrow();
    var meta = new StvnDocumentMeta(Optional.empty(), Optional.empty(), Optional.of(StvnDocumentKind.BODY));
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
    var buf = encoder.encode(ir, meta);

    assertEquals((byte) 0x00, buf.get(5), "Byte 5 must be 0x00 (KIND=#BODY, HAS_NAME=0, HAS_DOMAIN=0)");
    var root = StvnBinaryDecoder.open(buf);
    assertTrue(root.documentMeta().isPresent());
    var decoded = root.documentMeta().get();
    assertTrue(decoded.name().isEmpty());
    assertTrue(decoded.domain().isEmpty());
    assertEquals(Optional.of(StvnDocumentKind.BODY), decoded.kind());
  }

  @Test
  @DisplayName("TC-BIN-ID-06: Permutation name only: HAS_NAME=1, HAS_DOMAIN=0 decodes #name, domain empty")
  void testPermutationNameOnly() {
    var ir = StvnCompiler.compilePayload("{ :type :Int :body 42 }").orElseThrow();
    var meta = new StvnDocumentMeta(Optional.of("test_payload"), Optional.empty(), Optional.of(StvnDocumentKind.BODY));
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
    var buf = encoder.encode(ir, meta);

    assertEquals((byte) 0x04, buf.get(5), "Byte 5 must be 0x04 (KIND=#BODY, HAS_NAME=1, HAS_DOMAIN=0)");
    var root = StvnBinaryDecoder.open(buf);
    assertTrue(root.documentMeta().isPresent());
    var decoded = root.documentMeta().get();
    assertEquals("test_payload", decoded.name().orElse(null));
    assertTrue(decoded.domain().isEmpty());
    assertEquals(Optional.of(StvnDocumentKind.BODY), decoded.kind());
  }

  @Test
  @DisplayName("TC-BIN-ID-07: Permutation domain only: HAS_NAME=0, HAS_DOMAIN=1 decodes #domain, name empty")
  void testPermutationDomainOnly() {
    var ir = StvnCompiler.compilePayload("{ :type :Int :body 42 }").orElseThrow();
    var meta = new StvnDocumentMeta(Optional.empty(), Optional.of("org_example"), Optional.of(StvnDocumentKind.BODY));
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
    var buf = encoder.encode(ir, meta);

    assertEquals((byte) 0x08, buf.get(5), "Byte 5 must be 0x08 (KIND=#BODY, HAS_NAME=0, HAS_DOMAIN=1)");
    var root = StvnBinaryDecoder.open(buf);
    assertTrue(root.documentMeta().isPresent());
    var decoded = root.documentMeta().get();
    assertTrue(decoded.name().isEmpty());
    assertEquals("org_example", decoded.domain().orElse(null));
    assertEquals(Optional.of(StvnDocumentKind.BODY), decoded.kind());
  }

  @Test
  @DisplayName("TC-BIN-ID-08: Permutation both: HAS_NAME=1, HAS_DOMAIN=1 decodes both #name and #domain")
  void testPermutationBothNameAndDomain() {
    var ir = StvnCompiler.compilePayload("{ :type :Int :body 42 }").orElseThrow();
    var meta = new StvnDocumentMeta(Optional.of("entity_cfg"), Optional.of("cluster_prod"), Optional.of(StvnDocumentKind.BODY));
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
    var buf = encoder.encode(ir, meta);

    assertEquals((byte) 0x0C, buf.get(5), "Byte 5 must be 0x0C (KIND=#BODY, HAS_NAME=1, HAS_DOMAIN=1)");
    var root = StvnBinaryDecoder.open(buf);
    assertTrue(root.documentMeta().isPresent());
    var decoded = root.documentMeta().get();
    assertEquals("entity_cfg", decoded.name().orElse(null));
    assertEquals("cluster_prod", decoded.domain().orElse(null));
    assertEquals(Optional.of(StvnDocumentKind.BODY), decoded.kind());
  }

  @ParameterizedTest
  @ValueSource(ints = {0x10, 0x20, 0x40, 0x80, 0xF0})
  @DisplayName("TC-BIN-ID-09: Reserved bits non-zero in Byte 5 throws StvnCorruptedBitPatternException")
  void testReservedBitsNonZeroThrowsStvnCorruptedBitPatternException(int corruptedByte5) {
    var ir = StvnCompiler.compilePayload("{ :type :Int :body 42 }").orElseThrow();
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
    var buf = encoder.encode(ir);

    ByteBuffer corrupted = buf.duplicate();
    corrupted.put(5, (byte) (corrupted.get(5) | corruptedByte5));

    assertThrows(
        StvnCorruptedBitPatternException.class,
        () -> StvnBinaryDecoder.open(corrupted)
    );
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 65, 128, 255})
  @DisplayName("TC-BIN-ID-10: Corrupted name_len out of bounds throws MalformedPayloadException")
  void testCorruptedNameLengthPrefixThrowsMalformedPayloadException(int invalidLen) {
    var ir = StvnCompiler.compilePayload("{ :type :Int :body 42 }").orElseThrow();
    var meta = new StvnDocumentMeta(Optional.of("valid_name"), Optional.empty(), Optional.of(StvnDocumentKind.BODY));
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
    var buf = encoder.encode(ir, meta);

    ByteBuffer corrupted = buf.duplicate();
    corrupted.put(6, (byte) invalidLen);

    var ex = assertThrows(
        MalformedPayloadException.class,
        () -> StvnBinaryDecoder.open(corrupted)
    );
    assertTrue(ex.getMessage().contains("Invalid metadata #name length prefix"));
  }

  @Test
  @DisplayName("TC-BIN-ID-11: Non-POSIX ASCII character in #name throws MalformedPayloadException")
  void testNonPosixAsciiInNameThrowsMalformedPayloadException() {
    var ir = StvnCompiler.compilePayload("{ :type :Int :body 42 }").orElseThrow();
    var meta = new StvnDocumentMeta(Optional.of("valid_name"), Optional.empty(), Optional.of(StvnDocumentKind.BODY));
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
    var buf = encoder.encode(ir, meta);

    ByteBuffer corrupted = buf.duplicate();
    corrupted.put(7, (byte) '$');

    var ex = assertThrows(
        MalformedPayloadException.class,
        () -> StvnBinaryDecoder.open(corrupted)
    );
    assertTrue(ex.getMessage().contains("Metadata #name does not match atomic POSIX pattern"));
  }

  @Test
  @DisplayName("TC-BIN-ID-12: Realigned CRC-32C trailer computes over entire header frame including Byte 5+")
  void testCrc32cTrailerComputesOverEntireHeaderFrame() {
    var ir = StvnCompiler.compilePayload("{ :type :Int :body 42 }").orElseThrow();
    var meta = new StvnDocumentMeta(Optional.of("crc_test"), Optional.of("domain_test"), Optional.of(StvnDocumentKind.BODY));
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault(), BinaryEncodingStrategy.ZERO_COPY_POST_ORDER, true);
    var buf = encoder.encode(ir, meta);

    byte controlByte = buf.get(4);
    assertTrue((controlByte & (byte) 0x80) != 0, "CRC-32C bit 7 must be set");

    var root = StvnBinaryDecoder.open(buf);
    assertNotNull(root);
    var decoded = root.documentMeta().orElseThrow();
    assertEquals("crc_test", decoded.name().orElse(null));
    assertEquals("domain_test", decoded.domain().orElse(null));
  }

  @Test
  @DisplayName("TC-BIN-ID-13: Tampering ExplicitSha256 hash at realigned offset 6 throws PoisonedRegistryPayloadException")
  void testTamperedExplicitSha256AtRealignedOffsetThrows() {
    var ir = StvnCompiler.compilePayload("{ :type :Int :body 42 }").orElseThrow();
    byte[] hash = StvnSchemaHasher.computeSha256(ir.schema());
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.ExplicitSha256(hash));
    var buf = encoder.encode(ir);

    ByteBuffer tampered = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    tampered.put(6, (byte) (tampered.get(6) ^ 0xFF));
    java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
    ByteBuffer view = tampered.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    view.position(0);
    view.limit(tampered.limit() - 4);
    crc.update(view);
    tampered.putInt(tampered.limit() - 4, (int) crc.getValue());

    assertThrows(
        PoisonedRegistryPayloadException.class,
        () -> {
          var root = StvnBinaryDecoder.open(tampered);
          StvnBinaryDecoder.unpack(root, Optional.of(ir.schema()));
        }
    );
  }

  @Test
  @DisplayName("TC-BIN-ID-14: Valid binary snapshots match encoder output bit-for-bit")
  void testValidBinarySnapshotsBitForBitEquivalence() throws java.io.IOException {
    java.nio.file.Path validFixtures = java.nio.file.Paths.get("shared-fixtures/syntax/valid");
    try (var paths = java.nio.file.Files.walk(validFixtures)) {
      var binFiles = paths.filter(java.nio.file.Files::isRegularFile).filter(p -> p.toString().endsWith(".stvn_b")).toList();
      assertFalse(binFiles.isEmpty(), "Valid binary snapshot fixtures must not be empty");
      for (var binPath : binFiles) {
        byte[] onDisk = java.nio.file.Files.readAllBytes(binPath);
        var root = StvnBinaryDecoder.open(ByteBuffer.wrap(onDisk));
        assertNotNull(root, "Must open binary snapshot cleanly: " + binPath);
      }
    }
  }

  @Test
  @DisplayName("TC-BIN-ID-15: Dynamic invalid binary test across all 3 invalid .stvn_b files traps contracts")
  void testInvalidBinaryFixturesTrapExpectedExceptions() throws java.io.IOException {
    java.nio.file.Path invalidFixtures = java.nio.file.Paths.get("shared-fixtures/syntax/invalid");
    try (var paths = java.nio.file.Files.walk(invalidFixtures)) {
      var binFiles = paths.filter(java.nio.file.Files::isRegularFile).filter(p -> p.toString().endsWith(".stvn_b")).toList();
      assertEquals(3, binFiles.size(), "Expected exactly 3 invalid binary fixtures");
      for (var binPath : binFiles) {
        byte[] bytes = java.nio.file.Files.readAllBytes(binPath);
        assertThrows(RuntimeException.class, () -> {
          var buf = ByteBuffer.wrap(bytes);
          var root = StvnBinaryDecoder.open(buf);
          StvnBinaryDecoder.unpack(root, Optional.empty());
        }, "Invalid fixture must throw exception: " + binPath);
      }
    }
  }

  // =========================================================================
  // UPSTREAM CORE ESCALATIONS #1 & #3: Binary E038, Header Byte Spans,
  // Mandatory :defs on #DEFS Units, and StvnDocumentMeta.sourceSpan()
  // =========================================================================

  @Test
  @DisplayName("TC-ESC1-01: Binary .stvn_b with reserved 'stvn_*' domain emits E038 pinned strictly to #domain byte slice")
  void testBinaryStvnBReservedDomainEmitsE038WithExactDomainByteSpan() {
    String inlineSchema = "{ :type :Int :body 0 }";
    var ir = StvnCompiler.compilePayload("{ :type :Int :body 42 }").orElseThrow();
    var meta = new StvnDocumentMeta(
        Optional.of("orders"),
        Optional.of("stvn_core"),
        Optional.of(StvnDocumentKind.BODY)
    );
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.SelfDescribingSchema(inlineSchema));
    var buf = encoder.encode(ir, meta);
    byte[] bytes = new byte[buf.remaining()];
    buf.get(bytes);

    // Byte 5 = MetaControlByte; Byte 6 = nameLen (6); Bytes [7, 13) = "orders";
    // Byte 13 = domainLen (9); Bytes [14, 23) = "stvn_core"
    var rejected = StvnCompiler.compile(bytes, "orders.stvn_core.stvn_b", StvnParserConfig.DEFAULT);
    assertFalse(rejected.isSuccess());
    assertTrue(rejected.hasErrors());
    var e038 = rejected.diagnostics().stream()
        .filter(d -> d.canonicalCode().filter("E038"::equals).isPresent())
        .findFirst()
        .orElseThrow(() -> new AssertionError("Expected E038 diagnostic, got: " + rejected.diagnostics()));
    assertEquals(DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED, e038.symbolicId().orElse(null));
    assertEquals(1, e038.line());
    assertEquals(14, e038.column());
    assertEquals(14, e038.startOffset());
    assertEquals(23, e038.endOffset());
    assertEquals("stvn_core", new String(bytes, e038.startOffset(), e038.endOffset() - e038.startOffset(), java.nio.charset.StandardCharsets.UTF_8));

    // Also enforced when docPath is null (in-memory binary frame)
    var inMemoryRejected = StvnCompiler.compile(bytes, null, StvnParserConfig.DEFAULT);
    assertTrue(inMemoryRejected.hasErrors());
    assertEquals("E038", inMemoryRejected.diagnostics().getFirst().canonicalCode().orElse(null));
    assertEquals(14, inMemoryRejected.diagnostics().getFirst().startOffset());
    assertEquals(23, inMemoryRejected.diagnostics().getFirst().endOffset());

    // Permitted under SYSTEM_DEFAULT (config.systemAuthority() == true)
    var systemAccepted = StvnCompiler.compile(bytes, "orders.stvn_core.stvn_b", StvnParserConfig.SYSTEM_DEFAULT);
    assertTrue(systemAccepted.isSuccess(), "SYSTEM_DEFAULT must permit reserved domain: " + systemAccepted.diagnostics());
    assertFalse(systemAccepted.hasErrors());
  }

  @Test
  @DisplayName("TC-ESC1-02: Binary .stvn_bd with reserved 'stvn_*' domain emits single E038 pinned to #domain header byte slice")
  void testBinaryStvnBdReservedDomainEmitsE038WithExactHeaderByteSpan() {
    String defsText = "{ :meta { #name \"cas_env\" #domain \"stvn_cas\" #kind #DEFS } :defs { :Hash :String } }";
    var sysDefsDoc = StvnCompiler.compile(defsText, "cas_env.stvn_cas.stvn_d", StvnParserConfig.SYSTEM_DEFAULT)
        .document()
        .orElseThrow();
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.SelfDescribingSchema(defsText), true);
    var buf = encoder.encode(sysDefsDoc);
    byte[] bytes = new byte[buf.remaining()];
    buf.get(bytes);

    // Byte 6 = nameLen (7); Bytes [7, 14) = "cas_env"; Byte 14 = domainLen (8); Bytes [15, 23) = "stvn_cas"
    var rejected = StvnCompiler.compile(bytes, "cas_env.stvn_cas.stvn_bd", StvnParserConfig.DEFAULT);
    assertFalse(rejected.isSuccess());
    assertTrue(rejected.hasErrors());
    var e038List = rejected.diagnostics().stream()
        .filter(d -> d.canonicalCode().filter("E038"::equals).isPresent())
        .toList();
    assertEquals(1, e038List.size(), "Must emit deduplicated header E038 without duplicate inner E038: " + rejected.diagnostics());
    var e038 = e038List.getFirst();
    assertEquals(DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED, e038.symbolicId().orElse(null));
    assertEquals(1, e038.line());
    assertEquals(15, e038.column());
    assertEquals(15, e038.startOffset());
    assertEquals(23, e038.endOffset());
    assertEquals("stvn_cas", new String(bytes, e038.startOffset(), e038.endOffset() - e038.startOffset(), java.nio.charset.StandardCharsets.UTF_8));

    var accepted = StvnCompiler.compile(bytes, "cas_env.stvn_cas.stvn_bd", StvnParserConfig.SYSTEM_DEFAULT);
    assertTrue(accepted.isSuccess(), "SYSTEM_DEFAULT must permit .stvn_bd reserved domain: " + accepted.diagnostics());
  }

  @Test
  @DisplayName("TC-ESC1-03: Binary header identity diagnostics (E031, E032, E034, W001, W002) pin to exact Byte 5 / #name / #domain byte spans")
  void testBinaryHeaderIdentityDiagnosticsPinToExactByteSpans() {
    String inlineSchema = "{ :type :Int :body 0 }";
    var ir = StvnCompiler.compilePayload("{ :type :Int :body 99 }").orElseThrow();
    var meta = new StvnDocumentMeta(
        Optional.of("alpha"),
        Optional.of("billing"),
        Optional.of(StvnDocumentKind.BODY)
    );
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.SelfDescribingSchema(inlineSchema));
    var buf = encoder.encode(ir, meta);
    byte[] bytes = new byte[buf.remaining()];
    buf.get(bytes);
    // Byte 5 = [5, 6); Byte 6 = 5; #name "alpha" = [7, 12); Byte 12 = 7; #domain "billing" = [13, 20)

    // E034 -> Byte 5 [5, 6)
    var e034Res = StvnCompiler.compile(bytes, "alpha.billing.stvn_bin", StvnParserConfig.DEFAULT);
    var e034 = e034Res.diagnostics().getFirst();
    assertEquals("E034", e034.canonicalCode().orElse(null));
    assertEquals(5, e034.startOffset());
    assertEquals(6, e034.endOffset());
    assertEquals(5, e034.column());

    // E032 -> Byte 5 [5, 6)
    var e032Res = StvnCompiler.compile(bytes, "a.b.c.stvn_b", StvnParserConfig.DEFAULT);
    var e032 = e032Res.diagnostics().getFirst();
    assertEquals("E032", e032.canonicalCode().orElse(null));
    assertEquals(5, e032.startOffset());
    assertEquals(6, e032.endOffset());

    // E031 (#BODY frame against .stvn_bd path) -> Byte 5 [5, 6)
    var e031Res = StvnCompiler.compile(bytes, "alpha.billing.stvn_bd", StvnParserConfig.DEFAULT);
    var e031 = e031Res.diagnostics().getFirst();
    assertEquals("E031", e031.canonicalCode().orElse(null));
    assertEquals(5, e031.startOffset());
    assertEquals(6, e031.endOffset());

    // W001 (#name mismatch) + W002 (#domain omitted in single-slot filename) -> [7, 12) and [13, 20)
    var warnRes = StvnCompiler.compile(bytes, "omega.stvn_b", StvnParserConfig.DEFAULT);
    assertTrue(warnRes.isSuccess());
    assertEquals(2, warnRes.diagnostics().size());
    var w001Name = warnRes.diagnostics().get(0);
    assertEquals("W001", w001Name.canonicalCode().orElse(null));
    assertEquals(7, w001Name.startOffset());
    assertEquals(12, w001Name.endOffset());
    assertEquals("alpha", new String(bytes, w001Name.startOffset(), w001Name.endOffset() - w001Name.startOffset(), java.nio.charset.StandardCharsets.UTF_8));

    var w002Domain = warnRes.diagnostics().get(1);
    assertEquals("W002", w002Domain.canonicalCode().orElse(null));
    assertEquals(13, w002Domain.startOffset());
    assertEquals(20, w002Domain.endOffset());
    assertEquals("billing", new String(bytes, w002Domain.startOffset(), w002Domain.endOffset() - w002Domain.startOffset(), java.nio.charset.StandardCharsets.UTF_8));

    // W001 (#domain mismatch against slot 1) -> [13, 20)
    var domainMismatchRes = StvnCompiler.compile(bytes, "alpha.crm.stvn_b", StvnParserConfig.DEFAULT);
    assertTrue(domainMismatchRes.isSuccess());
    assertEquals(1, domainMismatchRes.diagnostics().size());
    var w001Domain = domainMismatchRes.diagnostics().getFirst();
    assertEquals("W001", w001Domain.canonicalCode().orElse(null));
    assertEquals(13, w001Domain.startOffset());
    assertEquals(20, w001Domain.endOffset());
  }

  @Test
  @DisplayName("TC-ESC3-01: Definitions schema {:meta{#name \"Empty\"#domain \"crm\"#kind #DEFS}} missing :defs emits E031 pinned to root '{'")
  void testDefinitionsSchemaMissingMandatoryDefsSectionRejectedWithE031() {
    String emptyDefsUnit = "{:meta{#name \"Empty\"#domain \"crm\"#kind #DEFS}}";

    // In-memory compilation (#kind #DEFS without docPath)
    var inMemResult = StvnCompiler.compile(emptyDefsUnit, null, StvnParserConfig.DEFAULT);
    assertFalse(inMemResult.isSuccess());
    assertTrue(inMemResult.hasErrors());
    var diag = inMemResult.diagnostics().getFirst();
    assertEquals("E031", diag.canonicalCode().orElse(null));
    assertEquals(DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH, diag.symbolicId().orElse(null));
    assertEquals(1, diag.line());
    assertEquals(0, diag.column());
    assertEquals(0, diag.startOffset());
    assertEquals(1, diag.endOffset());
    assertTrue(diag.message().contains("Mandatory ':defs' section missing"), "Unexpected message: " + diag.message());

    // File-backed .stvn_d and .stvn_di compilation
    var fileResult = StvnCompiler.compile(emptyDefsUnit, "Empty.crm.stvn_d", StvnParserConfig.DEFAULT);
    assertFalse(fileResult.isSuccess());
    assertEquals("E031", fileResult.diagnostics().getFirst().canonicalCode().orElse(null));

    String emptyDefsIncludeUnit = "{:meta{#name \"EmptyIncl\"#domain \"crm\"#kind #DEFS_INCLUDE}}";
    var inclResult = StvnCompiler.compile(emptyDefsIncludeUnit, "EmptyIncl.crm.stvn_di", StvnParserConfig.DEFAULT);
    assertFalse(inclResult.isSuccess());
    assertEquals("E031", inclResult.diagnostics().getFirst().canonicalCode().orElse(null));
  }

  @Test
  @DisplayName("TC-ESC3-02: StvnDocumentMeta.sourceSpan() records exact :meta CST span and Byte 5 binary span while preserving 3-arg compatibility")
  void testStvnDocumentMetaSourceSpanPopulatedForTextAndBinary() {
    String textSource = "{\n  :meta {\n    #name \"user_schema\"\n    #domain \"crm\"\n    #kind #DEFS\n  }\n  :defs {\n    :UserId :Int\n  }\n}";
    int expectedStart = textSource.indexOf(":meta");
    int expectedEnd = textSource.indexOf("}\n  :defs") + 1;

    var textRes = StvnCompiler.compile(textSource, "user_schema.crm.stvn_d", StvnParserConfig.DEFAULT);
    assertTrue(textRes.isSuccess(), () -> "Text compile failed: " + textRes.diagnostics());
    var textMeta = textRes.document().orElseThrow().requireMeta();
    assertTrue(textMeta.sourceSpan().isPresent(), "Textual StvnDocumentMeta must populate sourceSpan");
    StvnDocumentMeta.SourceSpan cstSpan = textMeta.sourceSpan().get();
    assertEquals(2, cstSpan.line());
    assertEquals(2, cstSpan.column());
    assertEquals(expectedStart, cstSpan.startOffset());
    assertEquals(expectedEnd, cstSpan.endOffset());

    // Synthetic 3-arg constructor and factory methods default sourceSpan to Optional.empty() and compare semantically equal
    var syntheticMeta = new StvnDocumentMeta(
        Optional.of("user_schema"),
        Optional.of("crm"),
        Optional.of(StvnDocumentKind.DEFS)
    );
    assertTrue(syntheticMeta.sourceSpan().isEmpty());
    assertEquals(syntheticMeta, textMeta);
    assertEquals(syntheticMeta.hashCode(), textMeta.hashCode());

    // Binary frame populates Byte 5+ metadata frame span [5, 5 + 1 + (1+11) + (1+3)) = [5, 22)
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.SelfDescribingSchema(textSource), true);
    var binBuf = encoder.encode(textRes.document().orElseThrow());
    var binRes = StvnCompiler.compile(binBuf, "user_schema.crm.stvn_bd", StvnParserConfig.DEFAULT);
    assertTrue(binRes.isSuccess(), () -> "Binary compile failed: " + binRes.diagnostics());
    var binMeta = binRes.document().orElseThrow().requireMeta();
    assertTrue(binMeta.sourceSpan().isPresent(), "Binary StvnDocumentMeta must populate Byte 5 frame sourceSpan");
    StvnDocumentMeta.SourceSpan binSpan = binMeta.sourceSpan().get();
    assertEquals(1, binSpan.line());
    assertEquals(5, binSpan.column());
    assertEquals(5, binSpan.startOffset());
    assertEquals(22, binSpan.endOffset());
    assertEquals(textMeta, binMeta);
  }

  @Test
  @DisplayName("TC-BIN-ID-16: Negative .stvn_bd shared fixtures match deterministic encoder output and emit coordinate-pinned diagnostics (WT-05)")
  void testInvalidBinaryDefsFixturesBitwiseDeterministicAndCoordinatePinned() throws java.io.IOException {
    org.stvnadore.core.utils.IrGeneratorUtility.syncNegativeBinaryDefsFixtures(false);
    java.nio.file.Path invalidFixtures = java.nio.file.Paths.get("shared-fixtures/syntax/invalid");
    try (var paths = java.nio.file.Files.walk(invalidFixtures)) {
      var bdFiles = paths.filter(java.nio.file.Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".stvn_bd"))
          .toList();
      assertTrue(bdFiles.size() >= 5, "Expected >= 5 invalid .stvn_bd shared fixtures, found: " + bdFiles.size());
      for (var bdPath : bdFiles) {
        int byteLength = (int) java.nio.file.Files.size(bdPath);
        var res = StvnCompiler.compile(bdPath, StvnParserConfig.DEFAULT);
        assertTrue(res.hasErrors(), "Expected negative .stvn_bd fixture to produce error diagnostics: " + bdPath);
        assertFalse(res.diagnostics().isEmpty());
        for (var diag : res.diagnostics()) {
          assertFalse(diag.startOffset() == 0 && diag.endOffset() == byteLength,
              "Negative .stvn_bd diagnostic must never collapse to [0, byteLength) in " + bdPath + ": " + diag);
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0b10, 0b11})
  @DisplayName("TC-BIN-ID-17: Binary Byte 5 modular kinds (#BODY_INCLUDE 0b10, #DEFS_INCLUDE 0b11) emit E031 pinned to [5, 6) even when docPath is null")
  void testBinaryModularKindsRejectedAtByte5WithOrWithoutDocPath(int rawKindBits) {
    String embeddedDefs = "{\n  :meta {\n    #name \"hermetic_probe\"\n    #kind #DEFS\n  }\n  :defs {\n    :ValidPort { #unsigned #size 16 } :Int\n  }\n}\n";
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.SelfDescribingSchema(embeddedDefs), true);
    ByteBuffer validBuf = encoder.encodeDefinitionsText(
        embeddedDefs,
        StvnDocumentMeta.ofNameAndKind("hermetic_probe", StvnDocumentKind.DEFS)
    );
    byte[] patched = new byte[validBuf.remaining()];
    validBuf.get(patched);
    org.stvnadore.core.utils.IrGeneratorUtility.patchByte5KindAndRecomputeCrc32c(patched, rawKindBits);

    for (String docPath : new String[]{null, "hermetic_probe.stvn_bd", "hermetic_probe.stvn_di", "hermetic_probe.stvn_i"}) {
      var res = StvnCompiler.compile(ByteBuffer.wrap(patched), docPath, StvnParserConfig.DEFAULT);
      assertFalse(res.isSuccess(), "Expected failure for Byte 5 kindBits=" + rawKindBits + " with docPath=" + docPath);
      assertEquals(1, res.diagnostics().size(), () -> "Expected single Byte 5 E031 without cascade: " + res.diagnostics());
      var diag = res.diagnostics().getFirst();
      assertEquals("E031", diag.canonicalCode().orElse(null));
      assertEquals(DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH, diag.symbolicId().orElse(null));
      assertEquals(1, diag.line());
      assertEquals(5, diag.column());
      assertEquals(5, diag.startOffset());
      assertEquals(6, diag.endOffset());
    }

    assertThrows(
        org.stvnadore.core.validation.MalformedPayloadException.class,
        () -> StvnBinaryDecoder.decodeDocument(ByteBuffer.wrap(patched), null, StvnParserConfig.DEFAULT)
    );
  }

  @Test
  @DisplayName("TC-BIN-ID-18: Embedded .stvn_bd :include and external :use fail closed (E005, E035) even with null docPath while sibling :ValidPort lowers cleanly")
  void testEmbeddedBinaryDefsRejectsIncludeAndExternalUseWithSiblingIsolation() {
    String embeddedWithIncludeAndUse = """
        {
          :meta {
            #name "hermetic_unit"
            #kind #DEFS
          }
          :defs {
            :include [ "other.stvn_d" ]
            :use [ :external/pkg ]
            :ValidPort { #unsigned #size 16 } :Int
          }
        }
        """.replace("\r\n", "\n");
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.SelfDescribingSchema(embeddedWithIncludeAndUse), true);
    ByteBuffer buf = encoder.encodeDefinitionsText(
        embeddedWithIncludeAndUse,
        StvnDocumentMeta.ofNameAndKind("hermetic_unit", StvnDocumentKind.DEFS)
    );

    var inMemoryRes = StvnCompiler.compile(buf.duplicate(), null, StvnParserConfig.DEFAULT);
    assertFalse(inMemoryRes.isSuccess());
    assertEquals(2, inMemoryRes.diagnostics().size(), () -> "Expected exactly E005 and E035 with 0 errors on :ValidPort: " + inMemoryRes.diagnostics());
    assertEquals("E005", inMemoryRes.diagnostics().get(0).canonicalCode().orElse(null));
    assertEquals(DiagnosticBag.ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT, inMemoryRes.diagnostics().get(0).symbolicId().orElse(null));
    assertEquals("E035", inMemoryRes.diagnostics().get(1).canonicalCode().orElse(null));
    assertEquals(DiagnosticBag.ERR_UNRESOLVED_PACKAGE_IN_HERMETIC_DOCUMENT, inMemoryRes.diagnostics().get(1).symbolicId().orElse(null));

    var lexer = new org.stvnadore.core.parser.StvnLexer(org.antlr.v4.runtime.CharStreams.fromString(embeddedWithIncludeAndUse));
    var parser = new org.stvnadore.core.parser.StvnParser(new org.antlr.v4.runtime.CommonTokenStream(lexer));
    var bag = new DiagnosticBag(100, null);
    var lowered = org.stvnadore.core.lowering.StvnDefsLowerer.lowerEmbeddedBinaryDefs(
        parser.stvnDocument(), null, StvnParserConfig.DEFAULT, bag
    );
    var validPort = lowered.defs().nominalTypes().get(":ValidPort");
    assertTrue(validPort instanceof org.stvnadore.core.ir.StvnSchema.IntSchema intSchema
        && intSchema.unsigned()
        && intSchema.bitWidth().intValueExact() == 16,
        "Valid sibling :ValidPort must lower cleanly without PoisonedSchema contamination");

    assertThrows(
        org.stvnadore.core.validation.MalformedPayloadException.class,
        () -> StvnBinaryDecoder.decodeDocument(buf.duplicate(), null, StvnParserConfig.DEFAULT)
    );
  }

  @Test
  @DisplayName("TC-BIN-ID-19: StvnBinaryEncoder rejects #BODY_INCLUDE, #DEFS_INCLUDE, and modular StvnDocument with StvnSerializationException")
  void testBinaryEncoderRejectsModularKindsAndIncludeDocuments() {
    var ir = StvnCompiler.compilePayload("{ :type :Int :body 42 }").orElseThrow();
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault(), true);

    assertThrows(
        org.stvnadore.core.binary.exceptions.StvnSerializationException.class,
        () -> encoder.encode(ir, StvnDocumentMeta.ofKind(StvnDocumentKind.BODY_INCLUDE))
    );
    assertThrows(
        org.stvnadore.core.binary.exceptions.StvnSerializationException.class,
        () -> encoder.encode(ir, StvnDocumentMeta.ofKind(StvnDocumentKind.DEFS_INCLUDE))
    );
    assertThrows(
        org.stvnadore.core.binary.exceptions.StvnSerializationException.class,
        () -> encoder.encodeDefinitionsText(
            "{ :meta { #name \"m\" #kind #DEFS_INCLUDE } :defs { :T :Int } }",
            StvnDocumentMeta.ofNameAndKind("m", StvnDocumentKind.DEFS_INCLUDE)
        )
    );
    var modularDefsDoc = new org.stvnadore.core.ir.StvnDocument(
        Optional.of(StvnDocumentMeta.ofNameAndKind("m", StvnDocumentKind.DEFS)),
        org.stvnadore.core.ir.StvnDefsCatalog.EMPTY,
        Optional.empty(),
        Optional.empty(),
        true,
        true
    );
    assertThrows(org.stvnadore.core.binary.exceptions.StvnSerializationException.class, () -> encoder.encode(modularDefsDoc));
  }

  @Test
  @DisplayName("TC-ID-44: Valid first :meta followed by duplicate :meta {} before :defs emits E033 on second :meta without E039")
  void testDuplicateEmptyMetaAfterValidMetaEmitsE033OnSecondMetaKeyword() {
    String source = """
        {
          :meta {
            #name "valid_doc"
            #kind #BODY_INCLUDE
          }
          :meta {}
          :defs {
            :Port { #unsigned #size 16 } :Int
          }
          :type :Port
          :body 8080
        }
        """;
    var result = StvnCompiler.compileToResult(source, "valid_doc.stvn_i");
    assertTrue(result.hasErrors());
    assertEquals(1, result.diagnostics().size(), () -> "Expected strictly 1 E033 diagnostic: " + result.diagnostics());
    var diag = result.diagnostics().getFirst();
    assertEquals("E033", diag.canonicalCode().orElse(null));
    assertEquals(DiagnosticBag.ERR_META_POSITION_INVALID, diag.errorCode().orElse(null));
    assertEquals(
        "Duplicate ':meta' section prohibited: document may declare at most one ':meta' section (canonical order: :meta -> :defs -> :type -> :body)",
        diag.message()
    );
    int secondMetaOffset = source.lastIndexOf(":meta");
    assertEquals(secondMetaOffset, diag.startOffset());
    assertEquals(secondMetaOffset + ":meta".length(), diag.endOffset());
  }

  @Test
  @DisplayName("TC-ID-45: First :meta with facet errors followed by :meta {} and :meta { ... } before :defs emits all :meta errors, E033 on both duplicates, and validates :body")
  void testGauntletV5Stage1DuplicateMetaBlocksWithFirstMetaErrorsAndBodyValidation() {
    String source = """
        {
          :meta {
            #kind #BODY_INCLUDE
            #domain "stvn_reserved_sys"
            #name "Invalid.Dot.Name"
            #version "2.0.0"
          }
          :meta {}
          :meta { #name "gauntlet_v5_stage1" #kind #BODY_INCLUDE }
          :defs {
            :BoundedPort { #unsigned #size 16 #minIncl 1024 #maxExcl 4096 } :Int
          }
          :type :BoundedPort
          :body 5000
        }
        """;
    var result = StvnCompiler.compileToResult(source, "gauntlet_v5_stage1.core_test.stvn_i");
    assertTrue(result.hasErrors());

    var e033Diags = result.diagnostics().stream()
        .filter(d -> "E033".equals(d.canonicalCode().orElse(null)))
        .toList();
    assertEquals(2, e033Diags.size(), () -> "Expected 2 E033 diagnostics for the two duplicate :meta blocks: " + result.diagnostics());

    int firstMeta = source.indexOf(":meta");
    int secondMeta = source.indexOf(":meta", firstMeta + 1);
    int thirdMeta = source.indexOf(":meta", secondMeta + 1);

    assertEquals(secondMeta, e033Diags.get(0).startOffset());
    assertEquals(secondMeta + ":meta".length(), e033Diags.get(0).endOffset());
    assertEquals(thirdMeta, e033Diags.get(1).startOffset());
    assertEquals(thirdMeta + ":meta".length(), e033Diags.get(1).endOffset());

    assertTrue(
        result.diagnostics().stream().anyMatch(d -> "E012".equals(d.canonicalCode().orElse(null))),
        () -> "Expected Pass 3 :body validation to remain active and report E012 on 5000: " + result.diagnostics()
    );
  }

  @Test
  @DisplayName("TC-ID-46: Duplicate :defs, :type, and :body sections and out-of-order sections emit deterministic E002 pinned to offending section keyword")
  void testDuplicateAndOutOfOrderDefsTypeBodySectionsEmitE002PinnedToKeyword() {
    String dupDefs = """
        {
          :defs { :A :Int }
          :defs { :B :String }
          :type :A
          :body 1
        }
        """;
    var dupDefsRes = StvnCompiler.compileToResult(dupDefs, "doc.stvn");
    assertTrue(dupDefsRes.hasErrors());
    var dupDefsDiag = dupDefsRes.diagnostics().getFirst();
    assertEquals("E002", dupDefsDiag.canonicalCode().orElse(null));
    assertEquals(DiagnosticBag.ERR_DUPLICATE_ROOT_SECTION, dupDefsDiag.symbolicId().orElse(null));
    assertEquals(
        "Duplicate ':defs' section prohibited: document may declare at most one ':defs' section (canonical order: :meta -> :defs -> :type -> :body)",
        dupDefsDiag.message()
    );
    int secondDefs = dupDefs.lastIndexOf(":defs");
    assertEquals(secondDefs, dupDefsDiag.startOffset());
    assertEquals(secondDefs + ":defs".length(), dupDefsDiag.endOffset());

    String dupType = """
        {
          :type :Int
          :type :String
          :body 1
        }
        """;
    var dupTypeRes = StvnCompiler.compileToResult(dupType, "doc.stvn");
    assertTrue(dupTypeRes.hasErrors());
    var dupTypeDiag = dupTypeRes.diagnostics().getFirst();
    assertEquals("E002", dupTypeDiag.canonicalCode().orElse(null));
    assertEquals(DiagnosticBag.ERR_DUPLICATE_ROOT_SECTION, dupTypeDiag.symbolicId().orElse(null));
    assertEquals(
        "Duplicate ':type' section prohibited: document may declare at most one ':type' section (canonical order: :meta -> :defs -> :type -> :body)",
        dupTypeDiag.message()
    );
    int secondType = dupType.lastIndexOf(":type");
    assertEquals(secondType, dupTypeDiag.startOffset());
    assertEquals(secondType + ":type".length(), dupTypeDiag.endOffset());

    String dupBody = """
        {
          :type :Int
          :body 1
          :body 2
        }
        """;
    var dupBodyRes = StvnCompiler.compileToResult(dupBody, "doc.stvn");
    assertTrue(dupBodyRes.hasErrors());
    var dupBodyDiag = dupBodyRes.diagnostics().getFirst();
    assertEquals("E002", dupBodyDiag.canonicalCode().orElse(null));
    assertEquals(DiagnosticBag.ERR_DUPLICATE_ROOT_SECTION, dupBodyDiag.symbolicId().orElse(null));
    assertEquals(
        "Duplicate ':body' section prohibited: document may declare at most one ':body' section (canonical order: :meta -> :defs -> :type -> :body)",
        dupBodyDiag.message()
    );
    int secondBody = dupBody.lastIndexOf(":body");
    assertEquals(secondBody, dupBodyDiag.startOffset());
    assertEquals(secondBody + ":body".length(), dupBodyDiag.endOffset());

    String outOfOrderTypeAfterBody = """
        {
          :body 1
          :type :Int
        }
        """;
    var outOfOrderRes = StvnCompiler.compileToResult(outOfOrderTypeAfterBody, "doc.stvn");
    assertTrue(outOfOrderRes.hasErrors());
    var outOfOrderDiag = outOfOrderRes.diagnostics().getFirst();
    assertEquals("E002", outOfOrderRes.diagnostics().getFirst().canonicalCode().orElse(null));
    assertEquals(DiagnosticBag.ERR_SECTION_ORDER_VIOLATION, outOfOrderDiag.symbolicId().orElse(null));
    assertEquals(
        "Misplaced ':type' section prohibited: ':type' must appear before ':body' (canonical order: :meta -> :defs -> :type -> :body)",
        outOfOrderDiag.message()
    );
    int typeOffset = outOfOrderTypeAfterBody.indexOf(":type");
    assertEquals(typeOffset, outOfOrderDiag.startOffset());
    assertEquals(typeOffset + ":type".length(), outOfOrderDiag.endOffset());
  }

  @Test
  @DisplayName("TC-ID-47: Empty :meta {} distinguishes 1-stem optional (.stvn) vs 2-stem mandatory (<name>.<domain>.stvn) remedy text")
  void testEmptyMetaDistinguishesOneStemOptionalFromTwoStemMandatoryInStvn() {
    String source = """
        {
          :meta {}
          :type :Int
          :body 42
        }
        """;
    var oneStemRes = StvnCompiler.compileToResult(source, "simple_doc.stvn");
    assertTrue(oneStemRes.hasErrors());
    assertEquals(1, oneStemRes.diagnostics().size());
    assertEquals(
        "Empty ':meta {}' section is prohibited; omit ':meta' or declare document identity facets (#name, #domain, #kind)",
        oneStemRes.diagnostics().getFirst().message()
    );

    var twoStemRes = StvnCompiler.compileToResult(source, "simple_doc.billing.stvn");
    assertTrue(twoStemRes.hasErrors());
    assertEquals(1, twoStemRes.diagnostics().size());
    assertEquals(
        "Empty ':meta {}' section is prohibited; declare document identity facets (#name, #domain, #kind)",
        twoStemRes.diagnostics().getFirst().message()
    );
  }
}

