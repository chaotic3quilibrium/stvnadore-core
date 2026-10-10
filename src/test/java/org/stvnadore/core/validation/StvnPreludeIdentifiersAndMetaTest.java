package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ast.StvnCatalogMeta;
import org.stvnadore.core.ast.StvnDocumentKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test suite for Prelude Identifiers (:PosixId, :PolyglotCodeId)
 * and Meta-Circular Substrate (:org/stvnadore/prelude/meta/*).
 */
public class StvnPreludeIdentifiersAndMetaTest {

  @Test
  @DisplayName("TC-PRELUDE-ID-01: Valid POSIX identifiers pass compilation (:PosixId)")
  void testValidPosixIdentifiers() {
    String source = """
        {
          :defs {
            :use [ :org/stvnadore/prelude { #strip } ]
          }
          :type :PosixId
          :body "valid_filename-123"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.isSuccess(), "Valid POSIX identifier must compile cleanly: " + result.diagnostics());

    // Test with maximum allowable length (255 chars)
    String maxLenSource = String.format("""
        {
          :defs {
            :Id :org/stvnadore/prelude/PosixId
          }
          :type :Id
          :body "%s"
        }
        """, "a".repeat(255));
    var maxResult = StvnCompiler.compileToResult(maxLenSource, null, StvnParserConfig.DEFAULT);
    assertTrue(maxResult.isSuccess(), "255-character POSIX identifier must compile cleanly: " + maxResult.diagnostics());
  }

  @Test
  @DisplayName("TC-PRELUDE-ID-02: Empty string violates #minSize 1 (:PosixId)")
  void testEmptyPosixIdViolatesMinSize() {
    String source = """
        {
          :defs {
            :Id :org/stvnadore/prelude/PosixId
          }
          :type :Id
          :body ""
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess(), "Empty string must violate #minSize 1");
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        d.message().contains("String cannot be empty") || d.message().contains("violates #minSize constraint (1)")));
  }

  @Test
  @DisplayName("TC-PRELUDE-ID-03: 256-character string violates #maxSize 255 (:PosixId)")
  void testPosixIdExceedsMaxSize() {
    String source = String.format("""
        {
          :defs {
            :Id :org/stvnadore/prelude/PosixId
          }
          :type :Id
          :body "%s"
        }
        """, "x".repeat(256));
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess(), "256-character string must violate #maxSize 255");
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        d.message().contains("exceeds maximum length of 255 characters")));
  }

  @Test
  @DisplayName("TC-PRELUDE-ID-04: Illegal characters (spaces, slashes) fail regex (:PosixId)")
  void testPosixIdIllegalCharactersFailRegex() {
    String spaceSource = """
        {
          :defs {
            :Id :org/stvnadore/prelude/PosixId
          }
          :type :Id
          :body "bad identifier"
        }
        """;
    var spaceResult = StvnCompiler.compileToResult(spaceSource, null, StvnParserConfig.DEFAULT);
    assertFalse(spaceResult.isSuccess(), "Spaces in POSIX identifier must fail pattern matching");
    assertTrue(spaceResult.diagnostics().stream().anyMatch(d ->
        d.errorCode().map(c -> c.equals("ERR_INVALID_REGEX") || c.equals("ERR_CONSTRAINT_VIOLATION")).orElse(false)
        || d.message().contains("does not match required pattern")));

    String slashSource = """
        {
          :defs {
            :Id :org/stvnadore/prelude/PosixId
          }
          :type :Id
          :body "dir/file"
        }
        """;
    var slashResult = StvnCompiler.compileToResult(slashSource, null, StvnParserConfig.DEFAULT);
    assertFalse(slashResult.isSuccess(), "Slashes in POSIX identifier must fail pattern matching");
    assertTrue(slashResult.diagnostics().stream().anyMatch(d ->
        d.errorCode().map(c -> c.equals("ERR_INVALID_REGEX") || c.equals("ERR_CONSTRAINT_VIOLATION")).orElse(false)
        || d.message().contains("does not match required pattern")));
  }

  @Test
  @DisplayName("TC-PRELUDE-ID-05: Valid polyglot code identifiers pass compilation (:PolyglotCodeId)")
  void testValidPolyglotCodeIdentifiers() {
    String source = """
        {
          :defs {
            :use [ :org/stvnadore/prelude { #strip } ]
          }
          :type :PolyglotCodeId
          :body "_myIdentifier123"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.isSuccess(), "Valid polyglot code identifier must compile cleanly: " + result.diagnostics());

    // Test with maximum allowable length (128 chars)
    String maxLenSource = String.format("""
        {
          :defs {
            :CodeId :org/stvnadore/prelude/PolyglotCodeId
          }
          :type :CodeId
          :body "a%s"
        }
        """, "b".repeat(127));
    var maxResult = StvnCompiler.compileToResult(maxLenSource, null, StvnParserConfig.DEFAULT);
    assertTrue(maxResult.isSuccess(), "128-character polyglot code identifier must compile cleanly: " + maxResult.diagnostics());
  }

  @Test
  @DisplayName("TC-PRELUDE-ID-06: Leading digit fails polyglot regex (:PolyglotCodeId)")
  void testPolyglotCodeIdLeadingDigitFailsRegex() {
    String source = """
        {
          :defs {
            :CodeId :org/stvnadore/prelude/PolyglotCodeId
          }
          :type :CodeId
          :body "9variable"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess(), "Leading digit in PolyglotCodeId must fail pattern matching");
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        d.errorCode().map(c -> c.equals("ERR_INVALID_REGEX") || c.equals("ERR_CONSTRAINT_VIOLATION")).orElse(false)
        || d.message().contains("does not match required pattern")));
  }

  @Test
  @DisplayName("TC-PRELUDE-ID-07: Hyphen fails polyglot regex (:PolyglotCodeId)")
  void testPolyglotCodeIdHyphenFailsRegex() {
    String source = """
        {
          :defs {
            :CodeId :org/stvnadore/prelude/PolyglotCodeId
          }
          :type :CodeId
          :body "kebab-case"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess(), "Hyphen in PolyglotCodeId must fail pattern matching");
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        d.errorCode().map(c -> c.equals("ERR_INVALID_REGEX") || c.equals("ERR_CONSTRAINT_VIOLATION")).orElse(false)
        || d.message().contains("does not match required pattern")));
  }

  @Test
  @DisplayName("TC-PRELUDE-ID-08: 129-character identifier violates #maxSize 128 (:PolyglotCodeId)")
  void testPolyglotCodeIdExceedsMaxSize() {
    String source = String.format("""
        {
          :defs {
            :CodeId :org/stvnadore/prelude/PolyglotCodeId
          }
          :type :CodeId
          :body "a%s"
        }
        """, "b".repeat(128));
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess(), "129-character identifier must violate #maxSize 128");
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        d.message().contains("exceeds maximum length of 128 characters")));
  }

  @Test
  @DisplayName("TC-PRELUDE-META-01: Valid 64-character stem compiles cleanly and enforces overridden #maxSize 64 (:StvnPosixId64)")
  void testValidStvnPosixId64CompilesCleanly() {
    String source = String.format("""
        {
          :defs {
            :use [ :org/stvnadore/prelude/meta { #strip } ]
          }
          :type :StvnPosixId64
          :body "%s"
        }
        """, "s".repeat(64));
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.isSuccess(), "64-character StvnPosixId64 stem must compile cleanly: " + result.diagnostics());
  }

  @Test
  @DisplayName("TC-PRELUDE-META-02: 65-character stem violates overridden #maxSize 64; asserts total nominal isolation against :PosixId")
  void testStvnPosixId64ExceedsMaxSizeAndAssertsNominalIsolation() {
    // Sub-assertion 1: 65-character stem violates overridden #maxSize 64
    String tooLongSource = String.format("""
        {
          :defs {
            :use [ :org/stvnadore/prelude/meta { #strip } ]
          }
          :type :StvnPosixId64
          :body "%s"
        }
        """, "s".repeat(65));
    var tooLongResult = StvnCompiler.compileToResult(tooLongSource, null, StvnParserConfig.DEFAULT);
    assertFalse(tooLongResult.isSuccess(), "65-character stem must violate overridden #maxSize 64");
    assertTrue(tooLongResult.diagnostics().stream().anyMatch(d ->
        d.message().contains("exceeds maximum length of 64 characters")));

    // Sub-assertion 2: Total Nominal Isolation - Assigning :PosixId to :StvnPosixId64 triggers ERR_INCOMPATIBLE_NOMINAL_TYPE
    String nominalMismatchSource = """
        {
          :defs {
            :PosixBase :org/stvnadore/prelude/PosixId
            :StemBrand :org/stvnadore/prelude/meta/StvnPosixId64
            #ConstantPosix :PosixBase "valid-stem"
          }
          :type :StemBrand
          :body #ConstantPosix
        }
        """;
    var mismatchResult = StvnCompiler.compileToResult(nominalMismatchSource, null, StvnParserConfig.DEFAULT);
    assertTrue(mismatchResult.hasErrors(), "Assigning :PosixId to :StvnPosixId64 must fail closed with nominal mismatch");
    var error = mismatchResult.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_INCOMPATIBLE_NOMINAL_TYPE, error.errorCode().orElse(null));
  }

  @Test
  @DisplayName("TC-PRELUDE-META-03: Document kind enum accepts all four valid variants (:StvnDocumentKind)")
  void testDocumentKindEnumAcceptsValidVariants() {
    for (String variant : new String[]{"#BODY", "#DEFS", "#BODY_INCLUDE", "#DEFS_INCLUDE"}) {
      String source = String.format("""
          {
            :defs {
              :Kind :org/stvnadore/prelude/meta/StvnDocumentKind
            }
            :type :Kind
            :body %s
          }
          """, variant);
      var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
      assertTrue(result.isSuccess(), "Variant " + variant + " must compile cleanly: " + result.diagnostics());
    }
  }

  @Test
  @DisplayName("TC-PRELUDE-META-04: Document kind enum rejects invalid variant tokens (:StvnDocumentKind)")
  void testDocumentKindEnumRejectsInvalidVariants() {
    String source = """
        {
          :defs {
            :Kind :org/stvnadore/prelude/meta/StvnDocumentKind
          }
          :type :Kind
          :body #UNKNOWN_VARIANT
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess(), "Invalid variant #UNKNOWN_VARIANT must be rejected");
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        d.errorCode().map(c -> c.equals("ERR_TYPE_MISMATCH") || c.equals("ERR_CONSTRAINT_VIOLATION")).orElse(false)
        || d.message().contains("Invalid enum value") || d.message().contains("Type mismatch") || d.message().contains("variant")));
  }

  @Test
  @DisplayName("TC-PRELUDE-META-05: Document meta tuple compiles with #Some and #None (:StvnDocumentMeta)")
  void testDocumentMetaTupleCompilesWithSomeAndNone() {
    // 1. Explicit #Some and #None with strip
    String sourceWithStrip = """
        {
          :defs {
            :use [ :org/stvnadore/prelude/meta { #strip } ]
          }
          :type :StvnDocumentMeta
          :body ( #None #None #DEFS )
        }
        """;
    var resStrip = StvnCompiler.compileToResult(sourceWithStrip, null, StvnParserConfig.DEFAULT);
    assertTrue(resStrip.isSuccess(), "Document meta with #None wildcards must compile cleanly: " + resStrip.diagnostics());

    // 2. Full names with #Some
    String sourceWithSome = """
        {
          :defs {
            :DocMeta :org/stvnadore/prelude/meta/StvnDocumentMeta
          }
          :type :DocMeta
          :body ( #Some "prelude" #Some "stdlib" #DEFS )
        }
        """;
    var resSome = StvnCompiler.compileToResult(sourceWithSome, null, StvnParserConfig.DEFAULT);
    assertTrue(resSome.isSuccess(), "Document meta with explicit #Some must compile cleanly: " + resSome.diagnostics());
  }

  @Test
  @DisplayName("TC-PRELUDE-META-06: Catalog meta compiles with non-empty segments (:StvnCatalogMeta)")
  void testCatalogMetaCompilesWithNonEmptySegments() {
    String source = """
        {
          :defs {
            :use [ :org/stvnadore/prelude/meta { #strip } ]
          }
          :type :StvnCatalogMeta
          :body ( "prelude" "stdlib" #DEFS )
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.isSuccess(), "Valid catalog meta must compile cleanly: " + result.diagnostics());
  }

  @Test
  @DisplayName("TC-PRELUDE-META-07: Catalog meta rejects wildcard #None (:StvnCatalogMeta)")
  void testCatalogMetaRejectsWildcardNone() {
    String source = """
        {
          :defs {
            :use [ :org/stvnadore/prelude/meta { #strip } ]
          }
          :type :StvnCatalogMeta
          :body ( #None "stdlib" #DEFS )
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess(), "Catalog meta must reject #None wildcard");
    assertTrue(result.hasErrors());
  }

  @Test
  @DisplayName("TC-PRELUDE-JAVA-01: Java record StvnCatalogMeta contracts")
  void testJavaRecordStvnCatalogMetaContracts() {
    var cat = StvnCatalogMeta.of("core", "domain", StvnDocumentKind.DEFS);
    assertEquals("core", cat.name());
    assertEquals("domain", cat.domain());
    assertEquals(StvnDocumentKind.DEFS, cat.kind());

    // Null checks
    assertThrows(NullPointerException.class, () -> new StvnCatalogMeta(null, "d", StvnDocumentKind.BODY));
    assertThrows(NullPointerException.class, () -> new StvnCatalogMeta("n", null, StvnDocumentKind.BODY));
    assertThrows(NullPointerException.class, () -> new StvnCatalogMeta("n", "d", null));

    // Non-empty checks
    assertThrows(IllegalArgumentException.class, () -> new StvnCatalogMeta("", "d", StvnDocumentKind.BODY));
    assertThrows(IllegalArgumentException.class, () -> new StvnCatalogMeta("n", "", StvnDocumentKind.BODY));
  }

  @Test
  @DisplayName("TC-PRELUDE-FIXTURE-01: Canonical shared fixture prelude.stvn_d exists and is non-empty")
  void testCanonicalPreludeFixtureExistsAndIsNonEmpty() throws IOException {
    var path = Paths.get("shared-fixtures/syntax/valid/modules/prelude.stvn_d");
    assertTrue(Files.exists(path), "prelude.stvn_d fixture file must exist");
    var content = Files.readString(path);
    assertFalse(content.isBlank(), "prelude.stvn_d fixture must not be blank");
    assertTrue(content.contains(":PosixId"), "prelude.stvn_d must contain :PosixId");
    assertTrue(content.contains(":PolyglotCodeId"), "prelude.stvn_d must contain :PolyglotCodeId");
    assertTrue(content.contains(":StvnPosixId64"), "prelude.stvn_d must contain :StvnPosixId64");
    assertTrue(content.contains(":StvnDocumentKind"), "prelude.stvn_d must contain :StvnDocumentKind");
    assertTrue(content.contains(":StvnDocumentMeta"), "prelude.stvn_d must contain :StvnDocumentMeta");
    assertTrue(content.contains(":StvnCatalogMeta"), "prelude.stvn_d must contain :StvnCatalogMeta");
  }

  @Test
  @DisplayName("TC-PRELUDE-FIXTURE-02: Canonical IR snapshot prelude.ir.stvn_i compiles cleanly")
  void testCanonicalPreludeIrSnapshotCompilesCleanly() throws IOException {
    var path = Paths.get("shared-fixtures/syntax/valid/modules/prelude.ir.stvn_i");
    assertTrue(Files.exists(path), "prelude.ir.stvn_i snapshot file must exist");
    var content = Files.readString(path);
    var result = StvnCompiler.compileToResult(content, path.toString(), StvnParserConfig.DEFAULT);
    assertTrue(result.isSuccess(), "prelude.ir.stvn_i must compile cleanly: " + result.diagnostics());
  }

  @Test
  @DisplayName("TC-PRELUDE-CONST-01: StvnVocabulary canonical FQNI constants sanity")
  void testVocabularyConstants() {
    assertEquals(":org/stvnadore/prelude/PosixId", StvnVocabulary.PRELUDE_TYPE_POSIX_ID);
    assertEquals(":org/stvnadore/prelude/PolyglotCodeId", StvnVocabulary.PRELUDE_TYPE_POLYGLOT_CODE_ID);
    assertEquals(":org/stvnadore/prelude/meta/StvnPosixId64", StvnVocabulary.META_TYPE_POSIX_ID_64);
    assertEquals(":org/stvnadore/prelude/meta/StvnDocumentKind", StvnVocabulary.META_TYPE_DOCUMENT_KIND);
    assertEquals(":org/stvnadore/prelude/meta/StvnDocumentMeta", StvnVocabulary.META_TYPE_DOCUMENT_META);
    assertEquals(":org/stvnadore/prelude/meta/StvnCatalogMeta", StvnVocabulary.META_TYPE_CATALOG_META);
  }
}
