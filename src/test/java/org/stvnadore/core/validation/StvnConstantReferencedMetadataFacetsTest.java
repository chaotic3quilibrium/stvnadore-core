package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.stdlib.StvnPrelude;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test suite for constant-referenced metadata facets
 * and canonical prelude synchronization.
 *
 * Conforms to test cases TC-CONST-FACET-01 through TC-CONST-FACET-12.
 */
public class StvnConstantReferencedMetadataFacetsTest {

  @Test
  @DisplayName("TC-CONST-FACET-01: String constant passed to #regex compiles cleanly and enforces pattern")
  void testRegexConstantFacet() {
    String source = """
        {
          :defs {
            #POSIX_ID_REGEX :String "^[a-zA-Z0-9_-]+$"
            :PosixId { #minSize 1 #maxSize 255 #regex #POSIX_ID_REGEX } :String
          }
          :type :PosixId
          :body "valid_name-123"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.isSuccess(), "Valid POSIX identifier must compile cleanly: " + result.diagnostics());

    String invalidSource = """
        {
          :defs {
            #POSIX_ID_REGEX :String "^[a-zA-Z0-9_-]+$"
            :PosixId { #minSize 1 #maxSize 255 #regex #POSIX_ID_REGEX } :String
          }
          :type :PosixId
          :body "invalid name with spaces"
        }
        """;
    var invalidResult = StvnCompiler.compileToResult(invalidSource, null, StvnParserConfig.DEFAULT);
    assertFalse(invalidResult.isSuccess(), "Illegal characters must violate constant-referenced #regex");

    // Also verify built-in prelude :PosixId which uses #POSIX_ID_REGEX
    String preludeSource = """
        {
          :defs {
            :use [ :org/stvnadore/prelude { #strip } ]
          }
          :type :PosixId
          :body "valid-prelude-posix-id"
        }
        """;
    var preludeResult = StvnCompiler.compileToResult(preludeSource, null, StvnParserConfig.DEFAULT);
    assertTrue(preludeResult.isSuccess(), "Prelude :PosixId must compile cleanly: " + preludeResult.diagnostics());
  }

  @Test
  @DisplayName("TC-CONST-FACET-02: Integer constant passed to #size, #minSize, #maxSize compiles cleanly")
  void testSizeConstantFacets() {
    String source = """
        {
          :defs {
            #CUSTOM_SIZE :Int 16
            #MIN_LEN :Int 3
            #MAX_LEN :Int 10
            :CustomInt { #size #CUSTOM_SIZE } :Int
            :BoundedStr { #minSize #MIN_LEN #maxSize #MAX_LEN } :String
          }
          :type :BoundedStr
          :body "valid"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.isSuccess(), "Constant-referenced size facets must compile cleanly: " + result.diagnostics());

    String minViolationSource = """
        {
          :defs {
            #MIN_LEN :Int 3
            #MAX_LEN :Int 10
            :BoundedStr { #minSize #MIN_LEN #maxSize #MAX_LEN } :String
          }
          :type :BoundedStr
          :body "hi"
        }
        """;
    var minResult = StvnCompiler.compileToResult(minViolationSource, null, StvnParserConfig.DEFAULT);
    assertFalse(minResult.isSuccess(), "Length 2 must violate constant-referenced #minSize 3");

    String maxViolationSource = """
        {
          :defs {
            #MIN_LEN :Int 3
            #MAX_LEN :Int 10
            :BoundedStr { #minSize #MIN_LEN #maxSize #MAX_LEN } :String
          }
          :type :BoundedStr
          :body "this string is way too long"
        }
        """;
    var maxResult = StvnCompiler.compileToResult(maxViolationSource, null, StvnParserConfig.DEFAULT);
    assertFalse(maxResult.isSuccess(), "Length > 10 must violate constant-referenced #maxSize 10");
  }

  @Test
  @DisplayName("TC-CONST-FACET-03: Numeric constant passed to #minIncl, #maxExcl enforces interval bounds")
  void testNumericRangeConstantFacets() {
    String source = """
        {
          :defs {
            #PORT_MIN :Int 1024
            #PORT_MAX :Int 65536
            :CustomPort { #unsigned #size 16 #minIncl #PORT_MIN #maxExcl #PORT_MAX } :Int
          }
          :type :CustomPort
          :body 8080
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.isSuccess(), "Port 8080 within [1024, 65536) must compile cleanly: " + result.diagnostics());

    String minViolationSource = """
        {
          :defs {
            #PORT_MIN :Int 1024
            #PORT_MAX :Int 65536
            :CustomPort { #unsigned #size 16 #minIncl #PORT_MIN #maxExcl #PORT_MAX } :Int
          }
          :type :CustomPort
          :body 80
        }
        """;
    var minResult = StvnCompiler.compileToResult(minViolationSource, null, StvnParserConfig.DEFAULT);
    assertFalse(minResult.isSuccess(), "Port 80 must violate constant-referenced #minIncl 1024");

    String maxViolationSource = """
        {
          :defs {
            #PORT_MIN :Int 1024
            #PORT_MAX :Int 65536
            :CustomPort { #unsigned #size 16 #minIncl #PORT_MIN #maxExcl #PORT_MAX } :Int
          }
          :type :CustomPort
          :body 65536
        }
        """;
    var maxResult = StvnCompiler.compileToResult(maxViolationSource, null, StvnParserConfig.DEFAULT);
    assertFalse(maxResult.isSuccess(), "Port 65536 must violate constant-referenced #maxExcl 65536");

    // Float numeric range constants
    String floatSource = """
        {
          :defs {
            #LAT_MIN :Float -90.0
            #LAT_MAX :Float 90.0
            :CustomLat { #size 64 #minIncl #LAT_MIN #maxIncl #LAT_MAX } :Float
          }
          :type :CustomLat
          :body 45.5
        }
        """;
    var floatResult = StvnCompiler.compileToResult(floatSource, null, StvnParserConfig.DEFAULT);
    assertTrue(floatResult.isSuccess(), "Latitude 45.5 within [-90.0, 90.0] must compile cleanly: " + floatResult.diagnostics());
  }

  @Test
  @DisplayName("TC-CONST-FACET-04: Boolean constant passed to #equatable, #comparable, #preserveIndent")
  void testBooleanConstantFacets() {
    String source = """
        {
          :defs {
            #PRESERVE :Boolean #TRUE
            #CAN_COMPARE :Boolean #FALSE
            :CustomStr { #preserveIndent #PRESERVE #comparable #CAN_COMPARE } :String
          }
          :type :CustomStr
          :body "indented string"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.isSuccess(), "Boolean constant facets must compile cleanly: " + result.diagnostics());
  }

  @Test
  @DisplayName("TC-CONST-FACET-05: Undefined constant in facet argument emits ERR_UNDEFINED_CONSTANT")
  void testUndefinedConstantFacetDiagnostic() {
    String source = """
        {
          :defs {
            :BadType { #regex #UNDEFINED_CONST_REGEX } :String
          }
          :type :BadType
          :body "test"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess(), "Undefined constant reference must fail compilation");
    assertTrue(
        result.diagnostics().stream().anyMatch(d -> DiagnosticBag.ERR_UNDEFINED_CONSTANT.equals(d.errorCode().orElse(null))),
        "Must emit ERR_UNDEFINED_CONSTANT"
    );

    var diag = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_UNDEFINED_CONSTANT.equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow();
    assertTrue(diag.message().contains("UNDEFINED_CONST_REGEX"), "Diagnostic message must mention constant name");
    assertTrue(diag.startOffset() >= 0 && diag.endOffset() > diag.startOffset(), "Diagnostic coordinates must be valid span");
  }

  @Test
  @DisplayName("TC-CONST-FACET-06: Incompatible constant type emits ERR_INCOMPATIBLE_METADATA_TYPE")
  void testIncompatibleConstantTypeDiagnostic() {
    String source = """
        {
          :defs {
            #INT_CONST :Int 42
            :BadRegexType { #regex #INT_CONST } :String
          }
          :type :BadRegexType
          :body "test"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess(), "Passing :Int constant to #regex must fail compilation");
    assertTrue(
        result.diagnostics().stream().anyMatch(d -> DiagnosticBag.ERR_INCOMPATIBLE_METADATA_TYPE.equals(d.errorCode().orElse(null))),
        "Must emit ERR_INCOMPATIBLE_METADATA_TYPE"
    );

    String sizeMismatchSource = """
        {
          :defs {
            #STRING_CONST :String "hello"
            :BadSizeType { #size #STRING_CONST } :Int
          }
          :type :BadSizeType
          :body 10
        }
        """;
    var sizeResult = StvnCompiler.compileToResult(sizeMismatchSource, null, StvnParserConfig.DEFAULT);
    assertFalse(sizeResult.isSuccess(), "Passing :String constant to #size must fail compilation");
    assertTrue(
        sizeResult.diagnostics().stream().anyMatch(d -> DiagnosticBag.ERR_INCOMPATIBLE_METADATA_TYPE.equals(d.errorCode().orElse(null))),
        "Must emit ERR_INCOMPATIBLE_METADATA_TYPE"
    );
  }

  @Test
  @DisplayName("TC-CONST-FACET-07: Forward constant reference declared later in :defs resolves cleanly")
  void testForwardConstantReference() {
    String source = """
        {
          :defs {
            :ForwardType { #minSize 1 #maxSize 255 #regex #FORWARD_REGEX } :String
            #FORWARD_REGEX :String "^[A-Z0-9_]+$"
          }
          :type :ForwardType
          :body "VALID_FORWARD_123"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.isSuccess(), "Forward constant reference must compile cleanly: " + result.diagnostics());

    String invalidSource = """
        {
          :defs {
            :ForwardType { #minSize 1 #maxSize 255 #regex #FORWARD_REGEX } :String
            #FORWARD_REGEX :String "^[A-Z0-9_]+$"
          }
          :type :ForwardType
          :body "lowercase_invalid"
        }
        """;
    var invalidResult = StvnCompiler.compileToResult(invalidSource, null, StvnParserConfig.DEFAULT);
    assertFalse(invalidResult.isSuccess(), "Lowercase payload must violate forward constant regex");
  }

  @Test
  @DisplayName("TC-CONST-FACET-08: Constant imported via :use [ ... { #strip } ] resolves across packages")
  void testCrossPackageConstantUse() {
    String source = """
        {
          :defs {
            :package :org/example/common {
              #COMMON_REGEX :String "^[0-9]+$"
              #COMMON_MAX :Int 100
            }
            :package :org/example/client {
              :use [ :org/example/common { #strip } ]
              :NumericId { #maxSize #COMMON_MAX #regex #COMMON_REGEX } :String
            }
          }
          :type :org/example/client/NumericId
          :body "12345"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.isSuccess(), "Cross-package constant import via :use {#strip} must compile cleanly: " + result.diagnostics());

    // Verify prelude meta package using prelude constants
    String metaSource = """
        {
          :defs {
            :use [ :org/stvnadore/prelude/meta { #strip } ]
          }
          :type :StvnPosixId64
          :body "valid_posix_64"
        }
        """;
    var metaResult = StvnCompiler.compileToResult(metaSource, null, StvnParserConfig.DEFAULT);
    assertTrue(metaResult.isSuccess(), "Prelude meta :StvnPosixId64 using #POSIX_ID_REGEX must compile cleanly: " + metaResult.diagnostics());
  }

  @Test
  @DisplayName("TC-CONST-FACET-09: Circular constant chain (#A -> #B -> #A) emits ERR_CIRCULAR_TYPE")
  void testCircularConstantChain() {
    String source = """
        {
          :defs {
            #A :String #B
            #B :String #A
          }
          :type :String
          :body "test"
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess(), "Circular constant definition loop must fail compilation");
    assertTrue(
        result.diagnostics().stream().anyMatch(d -> DiagnosticBag.ERR_CIRCULAR_TYPE.equals(d.errorCode().orElse(null))),
        "Must emit ERR_CIRCULAR_TYPE for circular constant loop"
    );
  }

  @Test
  @DisplayName("TC-CONST-FACET-10: Inverted range from constants (min > max) emits ERR_INVERTED_RANGE")
  void testInvertedRangeFromConstants() {
    String source = """
        {
          :defs {
            #MIN_BOUND :Int 100
            #MAX_BOUND :Int 50
            :InvertedType { #minIncl #MIN_BOUND #maxIncl #MAX_BOUND } :Int
          }
          :type :InvertedType
          :body 75
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess(), "Inverted numeric range from constants must fail compilation");
    assertTrue(
        result.diagnostics().stream().anyMatch(d -> DiagnosticBag.ERR_INVERTED_RANGE.equals(d.errorCode().orElse(null))),
        "Must emit ERR_INVERTED_RANGE for min > max interval"
    );

    String invertedSizeSource = """
        {
          :defs {
            #MIN_LEN :Int 20
            #MAX_LEN :Int 10
            :InvertedStr { #minSize #MIN_LEN #maxSize #MAX_LEN } :String
          }
          :type :InvertedStr
          :body "test"
        }
        """;
    var sizeResult = StvnCompiler.compileToResult(invertedSizeSource, null, StvnParserConfig.DEFAULT);
    assertFalse(sizeResult.isSuccess(), "Inverted cardinality #minSize > #maxSize from constants must fail compilation");
    assertTrue(
        sizeResult.diagnostics().stream().anyMatch(d -> DiagnosticBag.ERR_INVERTED_RANGE.equals(d.errorCode().orElse(null))),
        "Must emit ERR_INVERTED_RANGE for #minSize > #maxSize"
    );
  }

  @Test
  @DisplayName("TC-CONST-FACET-11: Updated prelude compiles with 0 diagnostics under StvnCompiler.compileToResult")
  void testUpdatedPreludeCompilesCleanly() {
    var result = StvnCompiler.compileToResult(StvnPrelude.PRELUDE_STVN_D, null, StvnParserConfig.DEFAULT);
    assertTrue(result.isSuccess(), "StvnPrelude.PRELUDE_STVN_D must compile with zero diagnostics: " + result.diagnostics());
    assertTrue(result.diagnostics().isEmpty(), "Diagnostics list must be empty");
  }

  @Test
  @DisplayName("TC-CONST-FACET-12: Downstream fixture prelude.stvn_core.stvn_d compiles with 0 diagnostics")
  void testCanonicalPreludeFixtureCompilesCleanly() throws IOException {
    var path = Paths.get("shared-fixtures/syntax/valid/modules/prelude.stvn_core.stvn_d");
    assertTrue(Files.exists(path), "prelude.stvn_core.stvn_d fixture file must exist");
    var content = Files.readString(path);
    assertFalse(content.isBlank(), "prelude.stvn_core.stvn_d fixture must not be blank");
    var result = StvnCompiler.compileToResult(content, path.toString(), StvnParserConfig.DEFAULT);
    assertTrue(result.isSuccess(), "prelude.stvn_core.stvn_d must compile with zero diagnostics: " + result.diagnostics());
    assertTrue(result.diagnostics().isEmpty(), "Diagnostics list must be empty");
  }
}
