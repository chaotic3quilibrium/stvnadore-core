package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ast.StvnDocumentKind;

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
}
