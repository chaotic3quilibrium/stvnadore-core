package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.stdlib.StvnPrelude;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test suite for explicit casing types and regex constants in the standard library prelude.
 * <p>
 * Enforces test cases TC-EXPLICIT-CASE-01 through TC-EXPLICIT-CASE-24.
 */
public class StvnPreludeExplicitCasingTest {

  @Test
  @DisplayName("TC-EXPLICIT-CASE-01: Valid lowercase UUID compiles with 0 diagnostics")
  void testValidUuidLowerCase() {
    String source = """
        {
          :type :org/stvnadore/prelude/UuidLowerCase
          :body "123e4567-e89b-12d3-a456-426614174000"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.isSuccess(), "Lowercase UUID must compile cleanly: " + result.diagnostics());
    assertTrue(result.diagnostics().isEmpty(), "Diagnostics list must be empty");
  }

  @Test
  @DisplayName("TC-EXPLICIT-CASE-02: Uppercase hex character in UUID fails regex check under :UuidLowerCase")
  void testUppercaseUuidFailsLowerCaseRegex() {
    String source = """
        {
          :type :org/stvnadore/prelude/UuidLowerCase
          :body "123E4567-E89B-12D3-A456-426614174000"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess(), "Uppercase UUID must fail :UuidLowerCase regex");
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        DiagnosticBag.ERR_INVALID_REGEX.equals(d.errorCode().orElse(null))
        || d.message().contains("does not match required pattern")));
  }

  @Test
  @DisplayName("TC-EXPLICIT-CASE-03: Short UUID (35 chars) violates #minSize 36")
  void testShortUuidViolatesMinSize() {
    String source = """
        {
          :type :org/stvnadore/prelude/UuidLowerCase
          :body "123e4567-e89b-12d3-a456-42661417400"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess(), "35-character UUID must violate #minSize 36");
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        d.message().contains("36 characters long, got 35")
        || d.message().contains("violates #minSize constraint")));
  }

  @Test
  @DisplayName("TC-EXPLICIT-CASE-04: Long UUID (37 chars) violates #maxSize 36")
  void testLongUuidViolatesMaxSize() {
    String source = """
        {
          :type :org/stvnadore/prelude/UuidLowerCase
          :body "123e4567-e89b-12d3-a456-4266141740000"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess(), "37-character UUID must violate #maxSize 36");
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        d.message().contains("36 characters long, got 37")
        || d.message().contains("exceeds maximum length")));
  }

  @Test
  @DisplayName("TC-EXPLICIT-CASE-05: Valid mixed-case UUID compiles cleanly")
  void testValidUuidMixedCase() {
    String source = """
        {
          :type :org/stvnadore/prelude/UuidMixedCase
          :body "123E4567-e89b-12D3-a456-426614174000"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.isSuccess(), "Mixed-case UUID must compile cleanly: " + result.diagnostics());
  }

  @Test
  @DisplayName("TC-EXPLICIT-CASE-06: Non-hex character in UUID fails mixed-case regex")
  void testNonHexCharacterInUuidFails() {
    String source = """
        {
          :type :org/stvnadore/prelude/UuidMixedCase
          :body "123g4567-e89b-12d3-a456-426614174000"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess(), "Non-hex 'g' in UUID must fail regex");
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        DiagnosticBag.ERR_INVALID_REGEX.equals(d.errorCode().orElse(null))
        || d.message().contains("does not match required pattern")));
  }

  @Test
  @DisplayName("TC-EXPLICIT-CASE-07: Valid lowercase SHA-256 compiles with 0 diagnostics")
  void testValidSha256LowerCase() {
    String source = """
        {
          :type :org/stvnadore/prelude/Sha256LowerCase
          :body "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.isSuccess(), "Lowercase SHA-256 must compile cleanly: " + result.diagnostics());
  }

  @Test
  @DisplayName("TC-EXPLICIT-CASE-08: Uppercase character in SHA-256 fails regex check under :Sha256LowerCase")
  void testUppercaseSha256FailsLowerCaseRegex() {
    String source = """
        {
          :type :org/stvnadore/prelude/Sha256LowerCase
          :body "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess(), "Uppercase SHA-256 must fail :Sha256LowerCase regex");
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        DiagnosticBag.ERR_INVALID_REGEX.equals(d.errorCode().orElse(null))
        || d.message().contains("does not match required pattern")));
  }

  @Test
  @DisplayName("TC-EXPLICIT-CASE-09: Short digest (63 chars) violates #minSize 64")
  void testShortSha256ViolatesMinSize() {
    String source = """
        {
          :type :org/stvnadore/prelude/Sha256LowerCase
          :body "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015a"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess(), "63-character digest must violate #minSize 64");
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        d.message().contains("64 characters long, got 63")));
  }

  @Test
  @DisplayName("TC-EXPLICIT-CASE-10: Long digest (65 chars) violates #maxSize 64")
  void testLongSha256ViolatesMaxSize() {
    String source = """
        {
          :type :org/stvnadore/prelude/Sha256LowerCase
          :body "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad0"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess(), "65-character digest must violate #maxSize 64");
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        d.message().contains("64 characters long, got 65")));
  }

  @Test
  @DisplayName("TC-EXPLICIT-CASE-11: Valid mixed-case SHA-256 compiles cleanly")
  void testValidSha256MixedCase() {
    String source = """
        {
          :type :org/stvnadore/prelude/Sha256MixedCase
          :body "bA7816bF8f01CfEa414140De5dAE2223B00361A396177A9cB410Ff61F20015aD"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.isSuccess(), "Mixed-case SHA-256 must compile cleanly: " + result.diagnostics());
  }

  @Test
  @DisplayName("TC-EXPLICIT-CASE-12: Non-hex character in SHA-256 fails mixed-case regex")
  void testNonHexSha256FailsMixedCaseRegex() {
    String source = """
        {
          :type :org/stvnadore/prelude/Sha256MixedCase
          :body "ba7816bf8f01cfea414140de5dae2223-00361a396177a9cb410ff61f20015ad"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess(), "Non-hex character in SHA-256 must fail regex");
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        DiagnosticBag.ERR_INVALID_REGEX.equals(d.errorCode().orElse(null))
        || d.message().contains("does not match required pattern")));
  }

  @Test
  @DisplayName("TC-EXPLICIT-CASE-13: Valid lowercase email compiles with 0 diagnostics")
  void testValidEmailLowerCase() {
    String source = """
        {
          :type :org/stvnadore/prelude/EmailLowerCase
          :body "developer@example.com"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.isSuccess(), "Lowercase email must compile cleanly: " + result.diagnostics());
  }

  @Test
  @DisplayName("TC-EXPLICIT-CASE-14: Uppercase character in email fails lowercase regex")
  void testUppercaseEmailFailsLowerCaseRegex() {
    String source = """
        {
          :type :org/stvnadore/prelude/EmailLowerCase
          :body "Developer@Example.Com"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess(), "Mixed-case email must fail :EmailLowerCase regex");
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        DiagnosticBag.ERR_INVALID_REGEX.equals(d.errorCode().orElse(null))
        || d.message().contains("does not match required pattern")));
  }

  @Test
  @DisplayName("TC-EXPLICIT-CASE-15: 2-character email string violates #minSize 3")
  void testShortEmailViolatesMinSize() {
    String source = """
        {
          :type :org/stvnadore/prelude/EmailLowerCase
          :body "a@"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess(), "2-character email must violate #minSize 3");
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        d.message().contains("violates #minSize constraint (3)")));
  }

  @Test
  @DisplayName("TC-EXPLICIT-CASE-16: 255-character email string violates #maxSize 254")
  void testLongEmailViolatesMaxSize() {
    String longMail = "a".repeat(243) + "@example.com"; // 243 + 12 = 255
    String source = """
        {
          :type :org/stvnadore/prelude/EmailLowerCase
          :body "%s"
        }
        """.formatted(longMail);
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess(), "255-character email must violate #maxSize 254");
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        d.message().contains("exceeds maximum length of 254 characters")));
  }

  @Test
  @DisplayName("TC-EXPLICIT-CASE-17: Valid mixed-case email compiles cleanly")
  void testValidEmailMixedCase() {
    String source = """
        {
          :type :org/stvnadore/prelude/EmailMixedCase
          :body "Developer@Example.Com"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.isSuccess(), "Mixed-case email must compile cleanly: " + result.diagnostics());
  }

  @Test
  @DisplayName("TC-EXPLICIT-CASE-18: Missing @ symbol fails mixed-case email regex")
  void testInvalidEmailFailsRegex() {
    String source = """
        {
          :type :org/stvnadore/prelude/EmailMixedCase
          :body "invalid-email-address.com"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess(), "Email missing @ symbol must fail regex");
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        DiagnosticBag.ERR_INVALID_REGEX.equals(d.errorCode().orElse(null))
        || d.message().contains("does not match required pattern")));
  }

  @Test
  @DisplayName("TC-EXPLICIT-CASE-19: StvnVocabulary constants match expected values")
  void testVocabularyConstantsParity() {
    assertEquals(":org/stvnadore/prelude/UuidLowerCase", StvnVocabulary.TYPE_UUID_LOWER_CASE);
    assertEquals(":org/stvnadore/prelude/UuidMixedCase", StvnVocabulary.TYPE_UUID_MIXED_CASE);
    assertEquals(":org/stvnadore/prelude/Sha256LowerCase", StvnVocabulary.TYPE_SHA256_LOWER_CASE);
    assertEquals(":org/stvnadore/prelude/Sha256MixedCase", StvnVocabulary.TYPE_SHA256_MIXED_CASE);
    assertEquals(":org/stvnadore/prelude/EmailLowerCase", StvnVocabulary.TYPE_EMAIL_LOWER_CASE);
    assertEquals(":org/stvnadore/prelude/EmailMixedCase", StvnVocabulary.TYPE_EMAIL_MIXED_CASE);

    assertEquals(":org/stvnadore/prelude/#UUID_LOWER_CASE_REGEX", StvnVocabulary.CONST_UUID_LOWER_CASE_REGEX);
    assertEquals(":org/stvnadore/prelude/#UUID_MIXED_CASE_REGEX", StvnVocabulary.CONST_UUID_MIXED_CASE_REGEX);
    assertEquals(":org/stvnadore/prelude/#SHA256_LOWER_CASE_REGEX", StvnVocabulary.CONST_SHA256_LOWER_CASE_REGEX);
    assertEquals(":org/stvnadore/prelude/#SHA256_MIXED_CASE_REGEX", StvnVocabulary.CONST_SHA256_MIXED_CASE_REGEX);
    assertEquals(":org/stvnadore/prelude/#EMAIL_LOWER_CASE_REGEX", StvnVocabulary.CONST_EMAIL_LOWER_CASE_REGEX);
    assertEquals(":org/stvnadore/prelude/#EMAIL_MIXED_CASE_REGEX", StvnVocabulary.CONST_EMAIL_MIXED_CASE_REGEX);

    assertEquals("#org/stvnadore/prelude/UUID_LOWER_CASE_REGEX", StvnVocabulary.PRELUDE_CONST_UUID_LOWER_CASE_REGEX);
    assertEquals("#org/stvnadore/prelude/UUID_MIXED_CASE_REGEX", StvnVocabulary.PRELUDE_CONST_UUID_MIXED_CASE_REGEX);
    assertEquals("#org/stvnadore/prelude/SHA256_LOWER_CASE_REGEX", StvnVocabulary.PRELUDE_CONST_SHA256_LOWER_CASE_REGEX);
    assertEquals("#org/stvnadore/prelude/SHA256_MIXED_CASE_REGEX", StvnVocabulary.PRELUDE_CONST_SHA256_MIXED_CASE_REGEX);
    assertEquals("#org/stvnadore/prelude/EMAIL_LOWER_CASE_REGEX", StvnVocabulary.PRELUDE_CONST_EMAIL_LOWER_CASE_REGEX);
    assertEquals("#org/stvnadore/prelude/EMAIL_MIXED_CASE_REGEX", StvnVocabulary.PRELUDE_CONST_EMAIL_MIXED_CASE_REGEX);
  }

  @Test
  @DisplayName("TC-EXPLICIT-CASE-20: Updated prelude fixture compiles cleanly")
  void testPreludeFixtureCompilesCleanly() throws IOException {
    var path = Paths.get("shared-fixtures/syntax/valid/modules/prelude.stvn_core.stvn_d");
    assertTrue(Files.exists(path), "prelude.stvn_core.stvn_d fixture file must exist");
    var content = Files.readString(path);
    var compilation = StvnCompiler.compile(content, path.toString(), StvnParserConfig.SYSTEM);
    assertTrue(compilation.diagnostics().isEmpty(), "prelude fixture must compile with zero diagnostics: " + compilation.diagnostics());
  }

  @Test
  @DisplayName("TC-EXPLICIT-CASE-21: IR snapshot test compiles with 0 diagnostics")
  void testPreludeIrSnapshotCompilesCleanly() throws IOException {
    var path = Paths.get("shared-fixtures/syntax/valid/modules/prelude.stvn_ir.stvn_i");
    assertTrue(Files.exists(path), "prelude.stvn_ir.stvn_i snapshot file must exist");
    var content = Files.readString(path);
    var result = StvnCompiler.compileToResult(content, path.toString(), StvnParserConfig.SYSTEM);
    assertTrue(result.diagnostics().isEmpty(), "prelude IR snapshot must compile with zero diagnostics: " + result.diagnostics());
  }

  @Test
  @DisplayName("TC-EXPLICIT-CASE-22: Deprecated bare :Uuid emits ERR_UNDEFINED_TYPE")
  void testExcisedUuidEmitsUndefinedType() {
    String source = """
        {
          :type :org/stvnadore/prelude/Uuid
          :body "123e4567-e89b-12d3-a456-426614174000"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess(), "Excised :Uuid must fail compilation");
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        DiagnosticBag.ERR_UNDEFINED_TYPE.equals(d.errorCode().orElse(null))
        || d.message().contains("Undefined type: :org/stvnadore/prelude/Uuid")));
  }

  @Test
  @DisplayName("TC-EXPLICIT-CASE-23: Deprecated bare :Sha256 emits ERR_UNDEFINED_TYPE")
  void testExcisedSha256EmitsUndefinedType() {
    String source = """
        {
          :type :org/stvnadore/prelude/Sha256
          :body "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess(), "Excised :Sha256 must fail compilation");
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        DiagnosticBag.ERR_UNDEFINED_TYPE.equals(d.errorCode().orElse(null))
        || d.message().contains("Undefined type: :org/stvnadore/prelude/Sha256")));
  }

  @Test
  @DisplayName("TC-EXPLICIT-CASE-24: Deprecated bare :Email emits ERR_UNDEFINED_TYPE")
  void testExcisedEmailEmitsUndefinedType() {
    String source = """
        {
          :type :org/stvnadore/prelude/Email
          :body "developer@example.com"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess(), "Excised :Email must fail compilation");
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        DiagnosticBag.ERR_UNDEFINED_TYPE.equals(d.errorCode().orElse(null))
        || d.message().contains("Undefined type: :org/stvnadore/prelude/Email")));
  }
}
