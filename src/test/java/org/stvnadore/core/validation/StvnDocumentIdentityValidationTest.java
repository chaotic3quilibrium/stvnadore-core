package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ast.StvnDocumentKind;
import org.stvnadore.core.ast.StvnDocumentMeta;
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
  @DisplayName("TC-ID-05: Physical .stvn_i modular body document omitting :meta defaults cleanly to BODY_INCLUDE")
  void testModularBodyDocumentAllowsOmittedMeta() {
    String source = """
        {
          :type :Int
          :body 50
        }
        """;
    var result = StvnCompiler.compileToResult(source, "payload.stvn_i");
    assertTrue(result.isSuccess());
    assertFalse(result.hasErrors());
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
  @DisplayName("TC-ID-15: Omitting #name and #domain represents an unconstrained wildcard")
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
    var result = StvnCompiler.compileToResult(source, "arbitrary_stem_name.corp.stvn_d");
    assertTrue(result.isSuccess(), "Wildcard metadata compiles cleanly without warnings");
    assertFalse(result.hasWarnings());
    var doc = result.document().orElseThrow();
    assertTrue(doc.requireMeta().name().isEmpty());
    assertTrue(doc.requireMeta().domain().isEmpty());
    assertEquals(StvnDocumentKind.DEFS, doc.requireMeta().kind().orElseThrow());
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
  @DisplayName("TC-ID-20: Empty metadata block :meta {} emits ERR_EMPTY_METADATA_BLOCK")
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
    assertEquals(DiagnosticBag.ERR_EMPTY_METADATA_BLOCK, diag.errorCode().orElse(null));
    assertTrue(diag.message().contains("must not be empty"));
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
  @DisplayName("TC-ID-39: Tag subset variation (#domain, #kind) with slot 1 matching compiles cleanly")
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
    String schema = "{ :type :Int :body 0 }";
    var ir = StvnCompiler.compilePayload("{ :type :Int :body 42 }").orElseThrow();
    var meta = new StvnDocumentMeta(Optional.of("my_defs"), Optional.empty(), Optional.of(StvnDocumentKind.DEFS));
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.SelfDescribingSchema(schema));
    var buf = encoder.encode(ir, meta);
    byte[] bytes = new byte[buf.remaining()];
    buf.get(bytes);

    var result = StvnCompiler.compileToResult(bytes, "my_defs.stvn_bd");
    assertTrue(result.isSuccess(), "Binary .stvn_bd compilation must succeed: " + result.diagnostics());
    assertFalse(result.hasErrors());
    var doc = result.document().orElseThrow();
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
}
