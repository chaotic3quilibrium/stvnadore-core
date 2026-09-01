package org.stvnadore.core.validation;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.ir.StvnValue.StvnString;

/**
 * Verification test suite for eager schema definition constraint validation inside {@code :defs}.
 * Ensures that all nominal type definitions, constants, and metadata constraint blocks are validated
 * immediately upon parsing and symbol table construction, regardless of whether they are referenced.
 *
 * @since 1.3.0
 */
public class StvnSchemaValidationTest {

  @Test
  @DisplayName("Unreferenced nominal alias with invalid regex fails compilation eagerly")
  void testUnreferencedBrokenRegexFailsEagerly() {
    String input = """
        {
          :defs {
            :BrokenRegex { #regex "[" } :String
          }
          :type :String
          :body "valid string"
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess(), "Compilation must fail on broken regex in :defs");
    Assertions.assertTrue(result.hasErrors(), "Result must contain error diagnostics");

    var diag = result.diagnostics().getFirst();
    Assertions.assertTrue(
        diag.message().contains("Constraint violation (:BrokenRegex): Invalid regex pattern: ["),
        "Message should report invalid regex: " + diag.message()
    );
    Assertions.assertEquals(3, diag.line(), "Error coordinate must point to line 3");
  }

  @Test
  @DisplayName("Unreferenced nominal alias with inverted numeric range fails compilation eagerly")
  void testUnreferencedInvertedRangeFailsEagerly() {
    String input = """
        {
          :defs {
            :InvalidRange { #minIncl 100 #maxIncl 10 } :Int32
          }
          :type :String
          :body "valid string"
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess(), "Compilation must fail on inverted range in :defs");
    Assertions.assertTrue(result.hasErrors());

    var diag = result.diagnostics().getFirst();
    Assertions.assertTrue(
        diag.message().contains("effective range is invalid"),
        "Message should report invalid range: " + diag.message()
    );
    Assertions.assertEquals(3, diag.line(), "Error coordinate must point to line 3");
  }

  @Test
  @DisplayName("Unreferenced nominal alias with incompatible metadata type fails compilation eagerly")
  void testUnreferencedIncompatibleMetadataTypeFailsEagerly() {
    String input = """
        {
          :defs {
            :BadType { #minIncl "abc" } :Int32
          }
          :type :String
          :body "valid string"
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess(), "Compilation must fail on string bound on int in :defs");
    Assertions.assertTrue(result.hasErrors());

    var diag = result.diagnostics().getFirst();
    Assertions.assertTrue(
        diag.message().contains("#minIncl requires an integer literal, found string"),
        "Message should report type mismatch: " + diag.message()
    );
    Assertions.assertEquals(3, diag.line());
  }

  @Test
  @DisplayName("Unreferenced float literal on integer type fails compilation eagerly")
  void testUnreferencedFloatOnIntBoundsFailsEagerly() {
    String input = """
        {
          :defs {
            :BadInt { #minIncl 5.5 } :Int32
          }
          :type :String
          :body "hello"
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertTrue(result.hasErrors());

    var diag = result.diagnostics().getFirst();
    Assertions.assertTrue(
        diag.message().contains("minIncl for :Int32 requires an integer literal"),
        "Message: " + diag.message()
    );
  }

  @Test
  @DisplayName("Unreferenced integer literal on float type fails compilation eagerly")
  void testUnreferencedIntOnFloatBoundsFailsEagerly() {
    String input = """
        {
          :defs {
            :BadFloat { #minIncl 5 } :Float64
          }
          :type :String
          :body "hello"
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertTrue(result.hasErrors());

    var diag = result.diagnostics().getFirst();
    Assertions.assertTrue(
        diag.message().contains("minIncl for :Float64 requires a float literal"),
        "Message: " + diag.message()
    );
  }

  @Test
  @DisplayName("Unreferenced out-of-bit-capacity bound fails compilation eagerly")
  void testUnreferencedOutOfBitCapacityBoundsFailsEagerly() {
    String input = """
        {
          :defs {
            :BadPort { #minIncl 70000 } :Uint16
          }
          :type :String
          :body "hello"
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertTrue(result.hasErrors());

    var diag = result.diagnostics().getFirst();
    Assertions.assertTrue(
        diag.message().contains("out of bounds for physical capacity of :Uint16"),
        "Message: " + diag.message()
    );
  }

  @Test
  @DisplayName("Unreferenced exclusive range overlap fails compilation eagerly")
  void testExclusiveRangeCoincidingFailsEagerly() {
    String input = """
        {
          :defs {
            :Shifted { #minExcl 5 #maxExcl 6 } :Int32
          }
          :type :String
          :body "hello"
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertTrue(result.hasErrors());

    var diag = result.diagnostics().getFirst();
    Assertions.assertTrue(
        diag.message().contains("effective range is invalid"),
        "Message: " + diag.message()
    );
  }

  @Test
  @DisplayName("Standalone include header file with broken regex fails compilation eagerly")
  void testStandaloneIncludeHeaderWithBrokenRegexFailsEagerly() {
    String input = """
        {
          // primitives.stvn_inclf
          :defs {
            :BrokenPattern { #regex "(?i" } :String
          }
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input, "primitives.stvn_inclf");
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertTrue(result.hasErrors());

    var diag = result.diagnostics().getFirst();
    Assertions.assertTrue(
        diag.message().contains("Constraint violation (:BrokenPattern)"),
        "Message: " + diag.message()
    );
  }

  @Test
  @DisplayName("Valid regex patterns and escaped brackets pass cleanly")
  void testValidRegexAndEscapedBracketsPassCleanly() {
    String input = """
        {
          :defs {
            :BracketStr { #regex "^\\[[a-z]+\\]$" } :String
          }
          :type :BracketStr
          :body "[hello]"
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.isSuccess(), "Valid regex with escaped brackets must succeed");
    Assertions.assertFalse(result.hasErrors());
    Assertions.assertTrue(result.document().isPresent());

    StvnValue val = result.document().get();
    Assertions.assertInstanceOf(StvnString.class, val);
    Assertions.assertEquals("[hello]", ((StvnString) val).value());
  }

  @Test
  @DisplayName("Valid unreferenced schema constraints pass compilation cleanly")
  void testValidUnreferencedConstraintsPassCleanly() {
    String input = """
        {
          :defs {
            :UnusedType { #regex "^[0-9]+$" } :String
            :UnusedPort { #minIncl 1 #maxIncl 65535 } :Uint16
          }
          :type :String
          :body "active"
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.isSuccess(), "Valid unreferenced schemas must compile cleanly");
    Assertions.assertFalse(result.hasErrors());
  }

  @Test
  @DisplayName("TC-01: Multiple concurrent invalid regex definitions in :defs accumulate all diagnostics")
  void testMultipleConcurrentRegexErrors() {
    String input = """
        {
          :defs {
            :RegexA { #regex "[" } :String
            :RegexB { #regex "*abc" } :String
            :RegexC { #regex "(?i" } :String
          }
          :type :String
          :body "hello"
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertTrue(result.hasErrors());
    Assertions.assertEquals(3, result.diagnostics().size(), "Must accumulate all 3 regex errors in single pass");

    for (var diag : result.diagnostics()) {
      Assertions.assertTrue(diag.errorCode().isPresent());
      Assertions.assertEquals(DiagnosticBag.ERR_INVALID_REGEX, diag.errorCode().get());
      Assertions.assertTrue(diag.line() >= 3 && diag.line() <= 5);
      Assertions.assertTrue(diag.startOffset() >= 0);
      Assertions.assertTrue(diag.endOffset() > diag.startOffset());
    }
  }

  @Test
  @DisplayName("TC-02: Mixed constraint violations in :defs accumulate distinct diagnostics and error codes")
  void testMixedConstraintViolationsAccumulation() {
    String input = """
        {
          :defs {
            :InvertedRange { #minIncl 10 #maxIncl 2 } :Int32
            :CapOverflow   { #minIncl -1 } :Uint8
            :BadStringMeta { #minIncl 5 } :String
            :InvalidRegex  { #regex "[a-z" } :String
          }
          :type :String
          :body "test"
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertTrue(result.hasErrors());
    Assertions.assertEquals(4, result.diagnostics().size(), "Must accumulate all 4 mixed constraint violations");

    var codes = result.diagnostics().stream()
        .map(d -> d.errorCode().orElse(""))
        .toList();

    Assertions.assertTrue(codes.contains(DiagnosticBag.ERR_INVERTED_RANGE), "Missing ERR_INVERTED_RANGE");
    Assertions.assertTrue(codes.contains(DiagnosticBag.ERR_CAPACITY_OVERFLOW), "Missing ERR_CAPACITY_OVERFLOW");
    Assertions.assertTrue(codes.contains(DiagnosticBag.ERR_INCOMPATIBLE_TYPE), "Missing ERR_INCOMPATIBLE_TYPE");
    Assertions.assertTrue(codes.contains(DiagnosticBag.ERR_INVALID_REGEX), "Missing ERR_INVALID_REGEX");
  }

  @Test
  @DisplayName("TC-03: Downstream reference to invalid schema suppresses secondary cascade diagnostics")
  void testDownstreamCascadeSuppressionOnBrokenDef() {
    String input = """
        {
          :defs {
            :Broken { #regex "[" } :String
          }
          :type :Broken
          :body "test"
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertTrue(result.hasErrors());
    Assertions.assertEquals(1, result.diagnostics().size(), "Must report only the root regex error without cascade diagnostics");

    var diag = result.diagnostics().getFirst();
    Assertions.assertEquals(DiagnosticBag.ERR_INVALID_REGEX, diag.errorCode().orElse(null));
    Assertions.assertEquals(3, diag.line());
  }

  @Test
  @DisplayName("TC-04: Standalone include header with multiple errors accumulates all errors in single pass")
  void testMultiErrorModularHeaderValidation() {
    String input = """
        {
          :defs {
            :BrokenA { #minIncl 10 #maxIncl 1 } :Int32
            :BrokenB { #regex "[" } :String
            :BrokenC { #preserveIndent #TRUE } :Int32
          }
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input, "header.stvn_inclf");
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertTrue(result.hasErrors());
    Assertions.assertEquals(3, result.diagnostics().size(), "Must report all 3 header definition errors");
  }
}
