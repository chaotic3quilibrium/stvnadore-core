package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test suite for string cardinality governance under MCT § 3.1.3.
 * <p>
 * Verifies that the physical storage bit-width facet {@code #size} is rejected on {@code :String},
 * and that string length constraints are governed through {@code #minSize} and {@code #maxSize}.
 */
public class StvnStringCardinalityValidationTest {

  @Test
  @DisplayName("TC-STR-01: Prohibits #size facet on :String domain")
  void testProhibitSizeOnString() {
    String source = """
        {
          :defs {
            :BadString { #size 32 } :String
          }
          :type :BadString
          :body "hello"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Expected compilation errors for #size on :String");
    var error = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_STRING_CARDINALITY_PROHIBITED, error.errorCode().orElse(null));
    assertTrue(error.message().contains("Facet '#size' is prohibited on :String"));
  }

  @Test
  @DisplayName("TC-STR-02: Enforces exact fixed-length string via #minSize and #maxSize")
  void testExactFixedString() {
    String validSource = """
        {
          :defs {
            :UuidString { #minSize 36 #maxSize 36 } :String
          }
          :type :UuidString
          :body "123e4567-e89b-12d3-a456-426614174000"
        }
        """;
    var validResult = StvnCompiler.compileToResult(validSource, null, StvnParserConfig.DEFAULT);
    assertFalse(validResult.hasErrors(), "Valid 36-character string must compile cleanly");

    String invalidSource = """
        {
          :defs {
            :UuidString { #minSize 36 #maxSize 36 } :String
          }
          :type :UuidString
          :body "too-short"
        }
        """;
    var invalidResult = StvnCompiler.compileToResult(invalidSource, null, StvnParserConfig.DEFAULT);
    assertTrue(invalidResult.hasErrors(), "Short string must fail cardinality constraint");
    var error = invalidResult.diagnostics().getFirst();
    assertTrue(error.message().contains("Constraint violation"));
  }

  @Test
  @DisplayName("TC-STR-03: Enforces non-empty string via #minSize 1")
  void testNonEmptyString() {
    String validSource = """
        {
          :defs {
            :Name { #minSize 1 } :String
          }
          :type :Name
          :body "Alice"
        }
        """;
    var validResult = StvnCompiler.compileToResult(validSource, null, StvnParserConfig.DEFAULT);
    assertFalse(validResult.hasErrors(), "Non-empty string must compile cleanly");

    String invalidSource = """
        {
          :defs {
            :Name { #minSize 1 } :String
          }
          :type :Name
          :body ""
        }
        """;
    var invalidResult = StvnCompiler.compileToResult(invalidSource, null, StvnParserConfig.DEFAULT);
    assertTrue(invalidResult.hasErrors(), "Empty string must violate #minSize 1");
  }

  @Test
  @DisplayName("TC-STR-04: Enforces max-bounded string via #maxSize")
  void testMaxBoundedString() {
    String validSource = """
        {
          :defs {
            :ShortName { #maxSize 5 } :String
          }
          :type :ShortName
          :body "Bob"
        }
        """;
    var validResult = StvnCompiler.compileToResult(validSource, null, StvnParserConfig.DEFAULT);
    assertFalse(validResult.hasErrors(), "Length 3 string must satisfy #maxSize 5");

    String invalidSource = """
        {
          :defs {
            :ShortName { #maxSize 5 } :String
          }
          :type :ShortName
          :body "Alexander"
        }
        """;
    var invalidResult = StvnCompiler.compileToResult(invalidSource, null, StvnParserConfig.DEFAULT);
    assertTrue(invalidResult.hasErrors(), "Length 9 string must violate #maxSize 5");
  }
}
