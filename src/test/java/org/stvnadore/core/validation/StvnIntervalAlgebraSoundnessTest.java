package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Soundness test suite for Interval Algebra and Boundary Hardening (MCT §5.1.1, §5.4, §5.6).
 * <p>
 * Verifies non-decimal radices in interval bounds, storage bit-width limits,
 * collection and string cardinality bounds, compile-time datetime interval validation,
 * and runtime payload interval enforcement in AST lowering.
 */
public class StvnIntervalAlgebraSoundnessTest {

  // --------------------------------------------------------------------------
  // 1. Radix Literals in Interval Bounds & Bit-Widths
  // --------------------------------------------------------------------------

  @Test
  @DisplayName("TC-ALG-01: Hexadecimal radix literals in #minIncl and #maxExcl bounds")
  void testHexIntervalBoundsOnInteger() {
    String validSource = """
        {
          :defs {
            :HexRange { #minIncl 0x10 #maxExcl 0x20 } :Int
          }
          :type :HexRange
          :body 0x15
        }
        """;
    var validResult = StvnCompiler.compileToResult(validSource, null, StvnParserConfig.DEFAULT);
    assertFalse(validResult.hasErrors(), "Valid hex payload within [0x10, 0x20) must compile cleanly");

    String lowerViolSource = """
        {
          :defs {
            :HexRange { #minIncl 0x10 #maxExcl 0x20 } :Int
          }
          :type :HexRange
          :body 0x0F
        }
        """;
    var lowerResult = StvnCompiler.compileToResult(lowerViolSource, null, StvnParserConfig.DEFAULT);
    assertTrue(lowerResult.hasErrors(), "Payload 0x0F below #minIncl 0x10 must fail");

    String upperViolSource = """
        {
          :defs {
            :HexRange { #minIncl 0x10 #maxExcl 0x20 } :Int
          }
          :type :HexRange
          :body 0x20
        }
        """;
    var upperResult = StvnCompiler.compileToResult(upperViolSource, null, StvnParserConfig.DEFAULT);
    assertTrue(upperResult.hasErrors(), "Payload 0x20 at #maxExcl 0x20 must fail");
  }

  @Test
  @DisplayName("TC-ALG-02: Binary radix literals in #minIncl and #maxExcl bounds")
  void testBinaryIntervalBoundsOnInteger() {
    String validSource = """
        {
          :defs {
            :BinRange { #minIncl 0b0010 #maxExcl 0b1000 } :Int
          }
          :type :BinRange
          :body 0b0100
        }
        """;
    var validResult = StvnCompiler.compileToResult(validSource, null, StvnParserConfig.DEFAULT);
    assertFalse(validResult.hasErrors(), "Valid binary payload within [0b0010, 0b1000) must compile cleanly");

    String violSource = """
        {
          :defs {
            :BinRange { #minIncl 0b0010 #maxExcl 0b1000 } :Int
          }
          :type :BinRange
          :body 0b1000
        }
        """;
    var violResult = StvnCompiler.compileToResult(violSource, null, StvnParserConfig.DEFAULT);
    assertTrue(violResult.hasErrors(), "Payload 0b1000 at #maxExcl 0b1000 must fail");
  }

  @Test
  @DisplayName("TC-ALG-03: Octal radix literals in #minIncl and #maxExcl bounds")
  void testOctalIntervalBoundsOnInteger() {
    String validSource = """
        {
          :defs {
            :OctRange { #minIncl 0o10 #maxExcl 0o77 } :Int
          }
          :type :OctRange
          :body 0o20
        }
        """;
    var validResult = StvnCompiler.compileToResult(validSource, null, StvnParserConfig.DEFAULT);
    assertFalse(validResult.hasErrors(), "Valid octal payload within [0o10, 0o77) must compile cleanly");

    String violSource = """
        {
          :defs {
            :OctRange { #minIncl 0o10 #maxExcl 0o77 } :Int
          }
          :type :OctRange
          :body 0o05
        }
        """;
    var violResult = StvnCompiler.compileToResult(violSource, null, StvnParserConfig.DEFAULT);
    assertTrue(violResult.hasErrors(), "Payload 0o05 below #minIncl 0o10 must fail");
  }

  @Test
  @DisplayName("TC-ALG-04: Non-decimal radix literals in storage #size facet")
  void testRadixLiteralInSizeFacet() {
    String hexSizeSource = """
        {
          :defs {
            :HexInt { #size 0x20 } :Int
          }
          :type :HexInt
          :body 42
        }
        """;
    var hexResult = StvnCompiler.compileToResult(hexSizeSource, null, StvnParserConfig.DEFAULT);
    assertFalse(hexResult.hasErrors(), "Hex #size 0x20 (32 bits) must compile cleanly");

    String binSizeSource = """
        {
          :defs {
            :BinInt { #size 0b00100000 } :Int
          }
          :type :BinInt
          :body 42
        }
        """;
    var binResult = StvnCompiler.compileToResult(binSizeSource, null, StvnParserConfig.DEFAULT);
    assertFalse(binResult.hasErrors(), "Binary #size 0b00100000 (32 bits) must compile cleanly");
  }

  // --------------------------------------------------------------------------
  // 2. Inverted Interval & Inverted Cardinality Range Validation
  // --------------------------------------------------------------------------

  @Test
  @DisplayName("TC-ALG-05: Inverted discrete integer intervals emit ERR_INVERTED_RANGE")
  void testInvertedIntervalsOnDiscreteInt() {
    String invertedSource = """
        {
          :defs {
            :BadRange { #minIncl 100 #maxExcl 50 } :Int
          }
          :type :Int
          :body 0
        }
        """;
    var invResult = StvnCompiler.compileToResult(invertedSource, null, StvnParserConfig.DEFAULT);
    assertTrue(invResult.hasErrors(), "Inverted interval [100, 50) must fail compilation");
    assertTrue(invResult.diagnostics().stream().anyMatch(d -> DiagnosticBag.ERR_INVERTED_RANGE.equals(d.errorCode().orElse(null))));

    String emptyDomainSource = """
        {
          :defs {
            :EmptyRange { #minIncl 10 #maxExcl 10 } :Int
          }
          :type :Int
          :body 0
        }
        """;
    var emptyResult = StvnCompiler.compileToResult(emptyDomainSource, null, StvnParserConfig.DEFAULT);
    assertTrue(emptyResult.hasErrors(), "Zero-width discrete interval [10, 10) must fail compilation");
    assertTrue(emptyResult.diagnostics().stream().anyMatch(d -> DiagnosticBag.ERR_INVERTED_RANGE.equals(d.errorCode().orElse(null))));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      ":String",
      ":Seq(:Int)",
      ":Set(:Int)",
      ":Map(:String :Int)"
  })
  @DisplayName("TC-ALG-06: Inverted cardinality (#minSize > #maxSize) emits ERR_INVERTED_RANGE across types")
  void testInvertedCardinalityRange(String typeConstructor) {
    String source = """
        {
          :defs {
            :BadCardinality { #minSize 10 #maxSize 5 } %s
          }
          :type :Int
          :body 0
        }
        """.formatted(typeConstructor);
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Inverted cardinality #minSize 10 #maxSize 5 on " + typeConstructor + " must fail");
    assertTrue(
        result.diagnostics().stream().anyMatch(d -> DiagnosticBag.ERR_INVERTED_RANGE.equals(d.errorCode().orElse(null))),
        "Must emit ERR_INVERTED_RANGE for #minSize > #maxSize on " + typeConstructor
    );
  }

  @Test
  @DisplayName("TC-ALG-07: Negative cardinality bounds emit ERR_INVERTED_RANGE")
  void testNegativeCardinalityRejected() {
    String negMinSource = """
        {
          :defs {
            :BadString { #minSize -1 } :String
          }
          :type :Int
          :body 0
        }
        """;
    var negMinResult = StvnCompiler.compileToResult(negMinSource, null, StvnParserConfig.DEFAULT);
    assertTrue(negMinResult.hasErrors(), "#minSize -1 must fail compilation");
    assertTrue(negMinResult.diagnostics().stream().anyMatch(d -> DiagnosticBag.ERR_INVERTED_RANGE.equals(d.errorCode().orElse(null))));

    String negMaxSource = """
        {
          :defs {
            :BadString { #maxSize -5 } :String
          }
          :type :Int
          :body 0
        }
        """;
    var negMaxResult = StvnCompiler.compileToResult(negMaxSource, null, StvnParserConfig.DEFAULT);
    assertTrue(negMaxResult.hasErrors(), "#maxSize -5 must fail compilation");
    assertTrue(negMaxResult.diagnostics().stream().anyMatch(d -> DiagnosticBag.ERR_INVERTED_RANGE.equals(d.errorCode().orElse(null))));
  }

  // --------------------------------------------------------------------------
  // 3. Storage Bit-Width Bounds Validation
  // --------------------------------------------------------------------------

  @Test
  @DisplayName("TC-ALG-08: Integer bit-width boundaries [1, 1024]")
  void testBitWidthBoundariesOnInt() {
    String zeroSizeSource = """
        {
          :defs {
            :BadInt { #size 0 } :Int
          }
          :type :Int
          :body 0
        }
        """;
    var zeroResult = StvnCompiler.compileToResult(zeroSizeSource, null, StvnParserConfig.DEFAULT);
    assertTrue(zeroResult.hasErrors(), "#size 0 on :Int must fail");
    assertTrue(zeroResult.diagnostics().stream().anyMatch(d -> DiagnosticBag.ERR_CAPACITY_OVERFLOW.equals(d.errorCode().orElse(null))));

    String oversizeSource = """
        {
          :defs {
            :BadInt { #size 2048 } :Int
          }
          :type :Int
          :body 0
        }
        """;
    var overResult = StvnCompiler.compileToResult(oversizeSource, null, StvnParserConfig.DEFAULT);
    assertTrue(overResult.hasErrors(), "#size 2048 on :Int must fail");
    assertTrue(overResult.diagnostics().stream().anyMatch(d -> DiagnosticBag.ERR_CAPACITY_OVERFLOW.equals(d.errorCode().orElse(null))));

    String validMinSource = """
        {
          :defs {
            :MinInt { #size 1 } :Int
          }
          :type :MinInt
          :body 0
        }
        """;
    var minResult = StvnCompiler.compileToResult(validMinSource, null, StvnParserConfig.DEFAULT);
    assertFalse(minResult.hasErrors(), "#size 1 on :Int must compile cleanly");

    String validMaxSource = """
        {
          :defs {
            :MaxInt { #size 1024 } :Int
          }
          :type :MaxInt
          :body 0
        }
        """;
    var maxResult = StvnCompiler.compileToResult(validMaxSource, null, StvnParserConfig.DEFAULT);
    assertFalse(maxResult.hasErrors(), "#size 1024 on :Int must compile cleanly");
  }

  @Test
  @DisplayName("TC-ALG-09: Float bit-width restrictions (must be exactly 32 or 64)")
  void testBitWidthBoundariesOnFloat() {
    String badFloat16Source = """
        {
          :defs {
            :BadFloat { #size 16 } :Float
          }
          :type :Int
          :body 0
        }
        """;
    var f16Result = StvnCompiler.compileToResult(badFloat16Source, null, StvnParserConfig.DEFAULT);
    assertTrue(f16Result.hasErrors(), "#size 16 on :Float must fail");
    assertTrue(f16Result.diagnostics().stream().anyMatch(d -> DiagnosticBag.ERR_INVALID_METADATA_FACET.equals(d.errorCode().orElse(null))));

    String badFloat128Source = """
        {
          :defs {
            :BadFloat { #size 128 } :Float
          }
          :type :Int
          :body 0
        }
        """;
    var f128Result = StvnCompiler.compileToResult(badFloat128Source, null, StvnParserConfig.DEFAULT);
    assertTrue(f128Result.hasErrors(), "#size 128 on :Float must fail");
    assertTrue(f128Result.diagnostics().stream().anyMatch(d -> DiagnosticBag.ERR_INVALID_METADATA_FACET.equals(d.errorCode().orElse(null))));

    String validFloat32Source = """
        {
          :defs {
            :F32 { #size 32 } :Float
          }
          :type :F32
          :body 3.14
        }
        """;
    var f32Result = StvnCompiler.compileToResult(validFloat32Source, null, StvnParserConfig.DEFAULT);
    assertFalse(f32Result.hasErrors(), "#size 32 on :Float must compile cleanly");

    String validFloat64Source = """
        {
          :defs {
            :F64 { #size 64 } :Float
          }
          :type :F64
          :body 3.141592653589793
        }
        """;
    var f64Result = StvnCompiler.compileToResult(validFloat64Source, null, StvnParserConfig.DEFAULT);
    assertFalse(f64Result.hasErrors(), "#size 64 on :Float must compile cleanly");
  }

  // --------------------------------------------------------------------------
  // 4. Compile-Time :DateTime Interval Validation
  // --------------------------------------------------------------------------

  @Test
  @DisplayName("TC-ALG-10: Empty domain [k, k) on :DateTime emits ERR_EMPTY_INTERVAL_DOMAIN")
  void testDateTimeIntervalEmptyDomain() {
    String source = """
        {
          :defs {
            :EmptyDateRange {
              #offset
              #minIncl "2026-09-24T12:00:00Z"
              #maxExcl "2026-09-24T12:00:00Z"
            } :DateTime
          }
          :type :Int
          :body 0
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Equal bounds [k, k) on :DateTime must fail compilation");
    assertTrue(
        result.diagnostics().stream().anyMatch(d -> DiagnosticBag.ERR_EMPTY_INTERVAL_DOMAIN.equals(d.errorCode().orElse(null))),
        "Must emit ERR_EMPTY_INTERVAL_DOMAIN for zero-width datetime interval"
    );
  }

  @Test
  @DisplayName("TC-ALG-11: Inverted datetime interval (min > max) emits ERR_INVERTED_RANGE")
  void testDateTimeIntervalInvertedRange() {
    String source = """
        {
          :defs {
            :InvertedDateRange {
              #offset
              #minIncl "2026-09-25T12:00:00Z"
              #maxExcl "2026-09-24T12:00:00Z"
            } :DateTime
          }
          :type :Int
          :body 0
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Inverted datetime interval must fail compilation");
    assertTrue(
        result.diagnostics().stream().anyMatch(d -> DiagnosticBag.ERR_INVERTED_RANGE.equals(d.errorCode().orElse(null))),
        "Must emit ERR_INVERTED_RANGE for inverted datetime interval"
    );
  }

  @Test
  @DisplayName("TC-ALG-12: Datetime bound contradictory offset and DST gap validation")
  void testDateTimeBoundJurisdictionValidation() {
    String contradictorySource = """
        {
          :defs {
            :BadOffset {
              #audited
              #minIncl "2026-01-01T12:00:00+05:00[UTC]"
            } :DateTime
          }
          :type :Int
          :body 0
        }
        """;
    var contraResult = StvnCompiler.compileToResult(contradictorySource, null, StvnParserConfig.DEFAULT);
    assertTrue(contraResult.hasErrors(), "Contradictory offset in datetime bound must fail");
    assertTrue(contraResult.diagnostics().stream().anyMatch(d -> DiagnosticBag.ERR_INCOMPATIBLE_TYPE.equals(d.errorCode().orElse(null))));

    String gapSource = """
        {
          :defs {
            :BadGap {
              #zoned
              #minIncl "2026-03-08T02:30:00[America/New_York]"
            } :DateTime
          }
          :type :Int
          :body 0
        }
        """;
    var gapResult = StvnCompiler.compileToResult(gapSource, null, StvnParserConfig.DEFAULT);
    assertTrue(gapResult.hasErrors(), "DST spring-forward gap in datetime bound must fail");
    assertTrue(gapResult.diagnostics().stream().anyMatch(d -> DiagnosticBag.ERR_INCOMPATIBLE_TYPE.equals(d.errorCode().orElse(null))));
  }

  // --------------------------------------------------------------------------
  // 5. Runtime Payload Interval Verification in AST Lowering
  // --------------------------------------------------------------------------

  @Test
  @DisplayName("TC-ALG-13: Runtime payload interval verification for :TimeEpoch")
  void testTimeEpochPayloadBoundaryLoweringVerification() {
    String validSource = """
        {
          :defs {
            :EpochWindow { #s #minIncl 100 #maxExcl 200 } :TimeEpoch
          }
          :type :EpochWindow
          :body 150
        }
        """;
    var validResult = StvnCompiler.compileToResult(validSource, null, StvnParserConfig.DEFAULT);
    assertFalse(validResult.hasErrors(), "Payload 150 within [100, 200) must compile cleanly");

    String belowSource = """
        {
          :defs {
            :EpochWindow { #s #minIncl 100 #maxExcl 200 } :TimeEpoch
          }
          :type :EpochWindow
          :body 99
        }
        """;
    var belowResult = StvnCompiler.compileToResult(belowSource, null, StvnParserConfig.DEFAULT);
    assertTrue(belowResult.hasErrors(), "Payload 99 below #minIncl 100 must fail lowering");

    String atMaxSource = """
        {
          :defs {
            :EpochWindow { #s #minIncl 100 #maxExcl 200 } :TimeEpoch
          }
          :type :EpochWindow
          :body 200
        }
        """;
    var atMaxResult = StvnCompiler.compileToResult(atMaxSource, null, StvnParserConfig.DEFAULT);
    assertTrue(atMaxResult.hasErrors(), "Payload 200 at #maxExcl 200 must fail lowering");
  }

  @Test
  @DisplayName("TC-ALG-14: Runtime payload interval verification for :DateTime")
  void testDateTimePayloadBoundaryLoweringVerification() {
    String validSource = """
        {
          :defs {
            :DateWindow {
              #offset
              #minIncl "2026-01-01T00:00:00Z"
              #maxExcl "2026-01-02T00:00:00Z"
            } :DateTime
          }
          :type :DateWindow
          :body "2026-01-01T12:00:00Z"
        }
        """;
    var validResult = StvnCompiler.compileToResult(validSource, null, StvnParserConfig.DEFAULT);
    assertFalse(validResult.hasErrors(), "Payload within date window must compile cleanly");

    String belowSource = """
        {
          :defs {
            :DateWindow {
              #offset
              #minIncl "2026-01-01T00:00:00Z"
              #maxExcl "2026-01-02T00:00:00Z"
            } :DateTime
          }
          :type :DateWindow
          :body "2025-12-31T23:59:59Z"
        }
        """;
    var belowResult = StvnCompiler.compileToResult(belowSource, null, StvnParserConfig.DEFAULT);
    assertTrue(belowResult.hasErrors(), "Payload below datetime #minIncl must fail lowering");

    String atMaxSource = """
        {
          :defs {
            :DateWindow {
              #offset
              #minIncl "2026-01-01T00:00:00Z"
              #maxExcl "2026-01-02T00:00:00Z"
            } :DateTime
          }
          :type :DateWindow
          :body "2026-01-02T00:00:00Z"
        }
        """;
    var atMaxResult = StvnCompiler.compileToResult(atMaxSource, null, StvnParserConfig.DEFAULT);
    assertTrue(atMaxResult.hasErrors(), "Payload at datetime #maxExcl must fail lowering");
  }

  @Test
  @DisplayName("TC-ALG-15: Runtime payload cardinality verification for :String #minSize and #maxSize")
  void testStringPayloadCardinalityLoweringVerification() {
    String validSource = """
        {
          :defs {
            :BoundedStr { #minSize 3 #maxSize 10 } :String
          }
          :type :BoundedStr
          :body "hello"
        }
        """;
    var validResult = StvnCompiler.compileToResult(validSource, null, StvnParserConfig.DEFAULT);
    assertFalse(validResult.hasErrors(), "Payload 'hello' within [3, 10] must compile cleanly");

    String tooShortSource = """
        {
          :defs {
            :BoundedStr { #minSize 3 #maxSize 10 } :String
          }
          :type :BoundedStr
          :body "hi"
        }
        """;
    var shortResult = StvnCompiler.compileToResult(tooShortSource, null, StvnParserConfig.DEFAULT);
    assertTrue(shortResult.hasErrors(), "Payload 'hi' of length 2 must violate #minSize 3");

    String tooLongSource = """
        {
          :defs {
            :BoundedStr { #minSize 3 #maxSize 10 } :String
          }
          :type :BoundedStr
          :body "this string is far too long"
        }
        """;
    var longResult = StvnCompiler.compileToResult(tooLongSource, null, StvnParserConfig.DEFAULT);
    assertTrue(longResult.hasErrors(), "Payload exceeding length 10 must violate #maxSize 10");
  }
}
