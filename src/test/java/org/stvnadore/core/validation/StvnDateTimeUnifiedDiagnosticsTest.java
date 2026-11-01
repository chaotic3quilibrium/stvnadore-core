package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnSchema;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Exhaustive verification suite for the STVN 2.0.0 unified three-part {@code :DateTime}
 * diagnostic contract (§ 5.6.2), domain-specific symbolic diagnostic identifiers, IANA TZDB
 * membership validation, DST spring-forward gap / fall-back overlap handling, and exported
 * {@link StvnCompiler#validateDateTimeLiteral(String, StvnSchema.DateTimeMode)} parity.
 */
@NullMarked
class StvnDateTimeUnifiedDiagnosticsTest {

  private static final Pattern FORBIDDEN_RAW_JARGON = Pattern.compile(
      "(?i)(token recognition error|LexerNoViableAltException|no viable alternative|mismatched input|extraneous input|DateTimeParseException|ZoneRulesException)"
  );

  record FailureCase(
      String scenarioName,
      StvnSchema.DateTimeMode mode,
      String facetToken,
      String rawLiteralText,
      String expectedCanonicalCode,
      String expectedSymbolicId,
      String expectedExactMessage
  ) {
  }

  static Stream<Arguments> provideUnifiedDateTimeFailureMatrix() {
    String offsetExpected = "expected \"YYYY-MM-DDTHH:mm:ss[.fraction](Z|±HH:mm)\"";
    String zonedExpected = "expected \"YYYY-MM-DDTHH:mm:ss[.fraction][Region/City]\"";
    String auditedExpected = "expected \"YYYY-MM-DDTHH:mm:ss[.fraction](Z|±HH:mm)[Region/City]\"";

    List<FailureCase> cases = List.of(
        // 1. Fenced string prohibition across all 3 modes
        new FailureCase(
            "Fenced string in #offset",
            StvnSchema.DateTimeMode.OFFSET,
            "#offset",
            "\"\"\"[RAW]\n2026-09-12T15:30:00-04:00\n[RAW]\"\"\"",
            "E028",
            DiagnosticBag.ERR_DATETIME_FENCED_STRING_PROHIBITED,
            "Invalid { #offset } :DateTime literal: fenced multi-line strings are prohibited; " + offsetExpected
        ),
        new FailureCase(
            "Fenced string in #zoned",
            StvnSchema.DateTimeMode.ZONED,
            "#zoned",
            "\"\"\"[RAW]\n2026-09-12T15:30:00[America/New_York]\n[RAW]\"\"\"",
            "E028",
            DiagnosticBag.ERR_DATETIME_FENCED_STRING_PROHIBITED,
            "Invalid { #zoned } :DateTime literal: fenced multi-line strings are prohibited; " + zonedExpected
        ),
        new FailureCase(
            "Fenced string in #audited",
            StvnSchema.DateTimeMode.AUDITED,
            "#audited",
            "\"\"\"[RAW]\n2026-09-12T15:30:00-04:00[America/New_York]\n[RAW]\"\"\"",
            "E028",
            DiagnosticBag.ERR_DATETIME_FENCED_STRING_PROHIBITED,
            "Invalid { #audited } :DateTime literal: fenced multi-line strings are prohibited; " + auditedExpected
        ),

        // 2. #offset mode violations
        new FailureCase(
            "#offset with prohibited zone and present offset",
            StvnSchema.DateTimeMode.OFFSET,
            "#offset",
            "\"2026-09-12T15:30:00.004-04:00[America/New_York]\"",
            "E028",
            DiagnosticBag.ERR_TEMPORAL_ZONE_IN_OFFSET,
            "Invalid { #offset } :DateTime literal: IANA time zone brackets '[...]' are prohibited; " + offsetExpected
        ),
        new FailureCase(
            "#offset with prohibited zone and missing offset",
            StvnSchema.DateTimeMode.OFFSET,
            "#offset",
            "\"2026-09-12T15:30:00.007[America/New_York]\"",
            "E028",
            DiagnosticBag.ERR_TEMPORAL_ZONE_IN_OFFSET,
            "Invalid { #offset } :DateTime literal: IANA time zone brackets '[...]' are prohibited and missing required UTC offset ('Z' or '±HH:mm'); " + offsetExpected
        ),
        new FailureCase(
            "#offset missing UTC offset",
            StvnSchema.DateTimeMode.OFFSET,
            "#offset",
            "\"2026-09-12T15:30:00.001\"",
            "E028",
            DiagnosticBag.ERR_DATETIME_MISSING_OFFSET,
            "Invalid { #offset } :DateTime literal: missing required UTC offset ('Z' or '±HH:mm'); " + offsetExpected
        ),

        // 3. #zoned mode violations (canonical E019 / TEMPORAL_OFFSET_IN_ZONED + E028)
        new FailureCase(
            "#zoned with prohibited offset and present zone",
            StvnSchema.DateTimeMode.ZONED,
            "#zoned",
            "\"2026-09-12T15:30:00.008-04:00[America/New_York]\"",
            "E019",
            DiagnosticBag.ERR_TEMPORAL_OFFSET_IN_ZONED,
            "Invalid { #zoned } :DateTime literal: UTC offset ('Z' or '±HH:mm') is prohibited; " + zonedExpected
        ),
        new FailureCase(
            "#zoned with prohibited offset and missing zone",
            StvnSchema.DateTimeMode.ZONED,
            "#zoned",
            "\"2026-09-12T15:30:00.005-04:00\"",
            "E019",
            DiagnosticBag.ERR_TEMPORAL_OFFSET_IN_ZONED,
            "Invalid { #zoned } :DateTime literal: UTC offset ('Z' or '±HH:mm') is prohibited and missing required IANA time zone '[Region/City]'; " + zonedExpected
        ),
        new FailureCase(
            "#zoned with prohibited Z offset and missing zone",
            StvnSchema.DateTimeMode.ZONED,
            "#zoned",
            "\"2026-09-12T15:30:00Z\"",
            "E019",
            DiagnosticBag.ERR_TEMPORAL_OFFSET_IN_ZONED,
            "Invalid { #zoned } :DateTime literal: UTC offset ('Z' or '±HH:mm') is prohibited and missing required IANA time zone '[Region/City]'; " + zonedExpected
        ),
        new FailureCase(
            "#zoned missing IANA zone",
            StvnSchema.DateTimeMode.ZONED,
            "#zoned",
            "\"2026-09-12T15:30:00.002\"",
            "E028",
            DiagnosticBag.ERR_DATETIME_MISSING_ZONE,
            "Invalid { #zoned } :DateTime literal: missing required IANA time zone '[Region/City]'; " + zonedExpected
        ),

        // 4. #audited mode structural violations
        new FailureCase(
            "#audited missing UTC offset",
            StvnSchema.DateTimeMode.AUDITED,
            "#audited",
            "\"2026-09-12T15:30:00.006[America/New_York]\"",
            "E028",
            DiagnosticBag.ERR_DATETIME_MISSING_OFFSET,
            "Invalid { #audited } :DateTime literal: missing required UTC offset ('Z' or '±HH:mm'); " + auditedExpected
        ),
        new FailureCase(
            "#audited missing IANA zone",
            StvnSchema.DateTimeMode.AUDITED,
            "#audited",
            "\"2026-09-12T15:30:00.009-04:00\"",
            "E028",
            DiagnosticBag.ERR_DATETIME_MISSING_ZONE,
            "Invalid { #audited } :DateTime literal: missing required IANA time zone '[Region/City]'; " + auditedExpected
        ),
        new FailureCase(
            "#audited missing both UTC offset and IANA zone",
            StvnSchema.DateTimeMode.AUDITED,
            "#audited",
            "\"2026-09-12T15:30:00\"",
            "E028",
            DiagnosticBag.ERR_DATETIME_INVALID_FORMAT,
            "Invalid { #audited } :DateTime literal: missing required UTC offset ('Z' or '±HH:mm') and IANA time zone '[Region/City]'; " + auditedExpected
        ),

        // 5. Zone bracket & IANA TZDB membership validation (including ZoneId.of("[-04]") bypass rejection)
        new FailureCase(
            "#zoned empty zone brackets",
            StvnSchema.DateTimeMode.ZONED,
            "#zoned",
            "\"2026-09-12T15:30:00[]\"",
            "E028",
            DiagnosticBag.ERR_DATETIME_INVALID_ZONE,
            "Invalid { #zoned } :DateTime literal: malformed IANA time zone bracket '[...]'; " + zonedExpected
        ),
        new FailureCase(
            "#zoned space inside zone brackets",
            StvnSchema.DateTimeMode.ZONED,
            "#zoned",
            "\"2026-09-12T15:30:00[America/New York]\"",
            "E028",
            DiagnosticBag.ERR_DATETIME_INVALID_ZONE,
            "Invalid { #zoned } :DateTime literal: malformed IANA time zone bracket '[...]'; " + zonedExpected
        ),
        new FailureCase(
            "#zoned unknown IANA zone ID",
            StvnSchema.DateTimeMode.ZONED,
            "#zoned",
            "\"2026-09-12T15:30:00[Invalid/Region]\"",
            "E028",
            DiagnosticBag.ERR_DATETIME_INVALID_ZONE,
            "Invalid { #zoned } :DateTime literal: unknown IANA time zone '[Invalid/Region]'; " + zonedExpected
        ),
        new FailureCase(
            "#zoned synthetic fixed-offset zone ID [-04] rejected by ZoneRulesProvider",
            StvnSchema.DateTimeMode.ZONED,
            "#zoned",
            "\"2026-09-12T15:30:00[-04]\"",
            "E028",
            DiagnosticBag.ERR_DATETIME_INVALID_ZONE,
            "Invalid { #zoned } :DateTime literal: unknown IANA time zone '[-04]'; " + zonedExpected
        ),
        new FailureCase(
            "#audited synthetic fixed-offset zone ID [+02] rejected by ZoneRulesProvider",
            StvnSchema.DateTimeMode.AUDITED,
            "#audited",
            "\"2026-09-12T15:30:00+02:00[+02]\"",
            "E028",
            DiagnosticBag.ERR_DATETIME_INVALID_ZONE,
            "Invalid { #audited } :DateTime literal: unknown IANA time zone '[+02]'; " + auditedExpected
        ),

        // 6. ISO-8601 syntax & calendar/clock/offset range validation
        new FailureCase(
            "#offset malformed ISO-8601 syntax (missing T separator)",
            StvnSchema.DateTimeMode.OFFSET,
            "#offset",
            "\"2026-09-12 15:30:00-04:00\"",
            "E028",
            DiagnosticBag.ERR_DATETIME_INVALID_FORMAT,
            "Invalid { #offset } :DateTime literal: malformed ISO-8601 date-time syntax; " + offsetExpected
        ),
        new FailureCase(
            "#offset invalid calendar day (Feb 30)",
            StvnSchema.DateTimeMode.OFFSET,
            "#offset",
            "\"2026-02-30T10:00:00Z\"",
            "E028",
            DiagnosticBag.ERR_DATETIME_INVALID_CALENDAR,
            "Invalid { #offset } :DateTime literal: invalid ISO-8601 calendar or clock value; " + offsetExpected
        ),
        new FailureCase(
            "#offset out-of-range UTC offset (+25:00)",
            StvnSchema.DateTimeMode.OFFSET,
            "#offset",
            "\"2026-09-12T15:30:00+25:00\"",
            "E028",
            DiagnosticBag.ERR_DATETIME_INVALID_CALENDAR,
            "Invalid { #offset } :DateTime literal: invalid ISO-8601 calendar or clock value; " + offsetExpected
        ),

        // 7. Civil DST spring-forward gap (E022 / TEMPORAL_DST_GAP_LITERAL)
        new FailureCase(
            "#zoned civil DST spring-forward gap",
            StvnSchema.DateTimeMode.ZONED,
            "#zoned",
            "\"2026-03-08T02:30:00[America/New_York]\"",
            "E022",
            DiagnosticBag.ERR_TEMPORAL_DST_GAP_LITERAL,
            "Invalid { #zoned } :DateTime literal: local timestamp '2026-03-08T02:30' falls within a Daylight Saving Time gap in zone '[America/New_York]'; " + zonedExpected
        ),
        new FailureCase(
            "#audited civil DST spring-forward gap takes precedence over E021",
            StvnSchema.DateTimeMode.AUDITED,
            "#audited",
            "\"2026-03-08T02:30:00-05:00[America/New_York]\"",
            "E022",
            DiagnosticBag.ERR_TEMPORAL_DST_GAP_LITERAL,
            "Invalid { #audited } :DateTime literal: local timestamp '2026-03-08T02:30' falls within a Daylight Saving Time gap in zone '[America/New_York]'; " + auditedExpected
        ),

        // 8. Audited offset vs IANA rule mismatch (E021 / TEMPORAL_AUDITED_MISMATCH)
        new FailureCase(
            "#audited offset mismatch during normal single-offset summer hour",
            StvnSchema.DateTimeMode.AUDITED,
            "#audited",
            "\"2026-09-12T15:30:00-05:00[America/New_York]\"",
            "E021",
            DiagnosticBag.ERR_TEMPORAL_AUDITED_MISMATCH,
            "Invalid { #audited } :DateTime literal: audited UTC offset '-05:00' does not match IANA zone '[America/New_York]' rule offset '-04:00' at instant '2026-09-12T15:30'; " + auditedExpected
        ),
        new FailureCase(
            "#audited offset mismatch during 2-offset DST fall-back overlap hour",
            StvnSchema.DateTimeMode.AUDITED,
            "#audited",
            "\"2026-11-01T01:30:00Z[America/New_York]\"",
            "E021",
            DiagnosticBag.ERR_TEMPORAL_AUDITED_MISMATCH,
            "Invalid { #audited } :DateTime literal: audited UTC offset 'Z' does not match IANA zone '[America/New_York]' rule offsets [-04:00, -05:00] at instant '2026-11-01T01:30'; " + auditedExpected
        )
    );

    return cases.stream().map(c -> Arguments.of(c.scenarioName(), c));
  }

  @ParameterizedTest(name = "Unified DateTime Failure Matrix -> {0}")
  @MethodSource("provideUnifiedDateTimeFailureMatrix")
  void testUnifiedDateTimeFailureParityBetweenCompilerAndPublicHelper(
      String scenarioName,
      FailureCase testCase
  ) {
    // 1. Verify exported StvnCompiler.validateDateTimeLiteral public helper
    Optional<StvnCompiler.DateTimeValidationFailure> helperFailureOpt =
        StvnCompiler.validateDateTimeLiteral(testCase.rawLiteralText(), testCase.mode());
    Assertions.assertTrue(
        helperFailureOpt.isPresent(),
        "Expected StvnCompiler.validateDateTimeLiteral to reject literal for: " + scenarioName
    );
    StvnCompiler.DateTimeValidationFailure helperFailure = helperFailureOpt.orElseThrow();
    Assertions.assertEquals(testCase.expectedCanonicalCode(), helperFailure.canonicalCode());
    Assertions.assertEquals(testCase.expectedSymbolicId(), helperFailure.symbolicId());
    Assertions.assertEquals(testCase.expectedExactMessage(), helperFailure.message());
    assertZeroRawJargon(helperFailure.message(), scenarioName);

    // 2. Verify full 4-pass StvnCompiler.compile pipeline parity
    String document = """
        {
          :type { %s } :DateTime
          :body %s
        }
        """.formatted(testCase.facetToken(), testCase.rawLiteralText());

    StvnCompilationResult<StvnDocument> result = StvnCompiler.compile(document);
    Assertions.assertTrue(result.hasErrors(), "Expected compilation error for: " + scenarioName);
    Assertions.assertEquals(1, result.diagnostics().size(), () -> "Expected exactly 1 diagnostic, got: " + result.diagnostics());

    StvnDiagnostic diagnostic = result.diagnostics().getFirst();
    Assertions.assertEquals(Optional.of(testCase.expectedCanonicalCode()), diagnostic.canonicalCode());
    Assertions.assertEquals(Optional.of(testCase.expectedSymbolicId()), diagnostic.symbolicId());
    Assertions.assertEquals(testCase.expectedExactMessage(), diagnostic.message());
    assertZeroRawJargon(diagnostic.message(), scenarioName);

    int expectedStart = document.indexOf(testCase.rawLiteralText());
    int expectedEnd = expectedStart + testCase.rawLiteralText().length();
    Assertions.assertEquals(expectedStart, diagnostic.startOffset(), "Exact startOffset must match literal token start");
    Assertions.assertEquals(expectedEnd, diagnostic.endOffset(), "Exact endOffset must match literal token end");
  }

  @Test
  @DisplayName("Valid tripartite :DateTime literals (including both DST fall-back overlap offsets) return Optional.empty() and 0 diagnostics")
  void testValidTripartiteLiteralsAndDstFallBackOverlapAcceptance() {
    Assertions.assertTrue(
        StvnCompiler.validateDateTimeLiteral("\"2026-09-12T15:30:00.001-04:00\"", StvnSchema.DateTimeMode.OFFSET).isEmpty()
    );
    Assertions.assertTrue(
        StvnCompiler.validateDateTimeLiteral("\"2026-09-12T19:30:00Z\"", StvnSchema.DateTimeMode.OFFSET).isEmpty()
    );
    Assertions.assertTrue(
        StvnCompiler.validateDateTimeLiteral("\"2026-09-12T15:30:00.002[America/New_York]\"", StvnSchema.DateTimeMode.ZONED).isEmpty()
    );
    Assertions.assertTrue(
        StvnCompiler.validateDateTimeLiteral("\"2026-09-12T15:30:00.003-04:00[America/New_York]\"", StvnSchema.DateTimeMode.AUDITED).isEmpty()
    );
    // Both -04:00 (EDT) and -05:00 (EST) are valid during the 01:00-02:00 fall-back overlap hour on 2026-11-01
    Assertions.assertTrue(
        StvnCompiler.validateDateTimeLiteral("\"2026-11-01T01:30:00-04:00[America/New_York]\"", StvnSchema.DateTimeMode.AUDITED).isEmpty()
    );
    Assertions.assertTrue(
        StvnCompiler.validateDateTimeLiteral("\"2026-11-01T01:30:00-05:00[America/New_York]\"", StvnSchema.DateTimeMode.AUDITED).isEmpty()
    );
  }

  @Test
  @DisplayName("Smoke-test date_time.stvn emits all 6 cross-mode diagnostics across Tuples 2 and 3 including E019 on lines 19 and 25")
  void testSmokeTestTripartiteTupleMatrixEmitsAllSixDiagnostics() {
    String smokeDoc = """
        {
          :defs {
            :DateTimes :Tuple (
              {#offset} :DateTime
              {#zoned} :DateTime
              {#audited} :DateTime
            )
          }
          :type { #maxSize 4096 } :Seq(:DateTimes)
          :body [
            (
              "2026-09-12T15:30:00.001-04:00"
              "2026-09-12T15:30:00.002[America/New_York]"
              "2026-09-12T15:30:00.003-04:00[America/New_York]"
            )
            (
              "2026-09-12T15:30:00.004-04:00[America/New_York]"
              "2026-09-12T15:30:00.005-04:00"
              "2026-09-12T15:30:00.006[America/New_York]"
            )
            (
              "2026-09-12T15:30:00.007[America/New_York]"
              "2026-09-12T15:30:00.008-04:00[America/New_York]"
              "2026-09-12T15:30:00.009-04:00"
            )
          ]
        }
        """;

    StvnCompilationResult<StvnDocument> result = StvnCompiler.compile(smokeDoc);
    Assertions.assertTrue(result.hasErrors());
    List<StvnDiagnostic> diags = result.diagnostics();
    Assertions.assertEquals(6, diags.size(), () -> "Expected 6 errors on Tuples 2 and 3, got: " + diags);

    Assertions.assertEquals(17, diags.get(0).line());
    Assertions.assertEquals(Optional.of("E028"), diags.get(0).canonicalCode());
    Assertions.assertEquals(Optional.of(DiagnosticBag.ERR_TEMPORAL_ZONE_IN_OFFSET), diags.get(0).symbolicId());

    Assertions.assertEquals(18, diags.get(1).line());
    Assertions.assertEquals(Optional.of("E019"), diags.get(1).canonicalCode());
    Assertions.assertEquals(Optional.of(DiagnosticBag.ERR_TEMPORAL_OFFSET_IN_ZONED), diags.get(1).symbolicId());

    Assertions.assertEquals(19, diags.get(2).line());
    Assertions.assertEquals(Optional.of("E028"), diags.get(2).canonicalCode());
    Assertions.assertEquals(Optional.of(DiagnosticBag.ERR_DATETIME_MISSING_OFFSET), diags.get(2).symbolicId());

    Assertions.assertEquals(22, diags.get(3).line());
    Assertions.assertEquals(Optional.of("E028"), diags.get(3).canonicalCode());
    Assertions.assertEquals(Optional.of(DiagnosticBag.ERR_TEMPORAL_ZONE_IN_OFFSET), diags.get(3).symbolicId());

    Assertions.assertEquals(23, diags.get(4).line());
    Assertions.assertEquals(Optional.of("E019"), diags.get(4).canonicalCode());
    Assertions.assertEquals(Optional.of(DiagnosticBag.ERR_TEMPORAL_OFFSET_IN_ZONED), diags.get(4).symbolicId());

    Assertions.assertEquals(24, diags.get(5).line());
    Assertions.assertEquals(Optional.of("E028"), diags.get(5).canonicalCode());
    Assertions.assertEquals(Optional.of(DiagnosticBag.ERR_DATETIME_MISSING_ZONE), diags.get(5).symbolicId());
  }

  @Test
  @DisplayName("StvnDefsLowerer schema bound validation enforces DST gap E022 precedence and ZoneRulesProvider IANA membership")
  void testSchemaBoundDstGapPrecedenceAndZoneMembership() {
    String dstGapBoundDoc = """
        {
          :defs {
            :Window { #audited #minIncl "2026-03-08T02:30:00-05:00[America/New_York]" } :DateTime
          }
          :type :Window
          :body "2026-09-12T15:30:00-04:00[America/New_York]"
        }
        """;
    StvnCompilationResult<StvnDocument> gapRes = StvnCompiler.compile(dstGapBoundDoc);
    Assertions.assertTrue(gapRes.hasErrors());
    Assertions.assertTrue(
        gapRes.diagnostics().stream().anyMatch(d ->
            Optional.of("E022").equals(d.canonicalCode())
                && Optional.of(DiagnosticBag.ERR_TEMPORAL_DST_GAP_LITERAL).equals(d.symbolicId())),
        () -> "Expected E022 TEMPORAL_DST_GAP_LITERAL on schema DST gap bound, got: " + gapRes.diagnostics()
    );

    String syntheticZoneBoundDoc = """
        {
          :defs {
            :Window { #audited #minIncl "2026-09-12T15:30:00-04:00[-04]" } :DateTime
          }
          :type :Window
          :body "2026-09-12T15:30:00-04:00[America/New_York]"
        }
        """;
    StvnCompilationResult<StvnDocument> synthRes = StvnCompiler.compile(syntheticZoneBoundDoc);
    Assertions.assertTrue(synthRes.hasErrors());
    Assertions.assertTrue(
        synthRes.diagnostics().stream().anyMatch(d ->
            Optional.of("E008").equals(d.canonicalCode())
                && Optional.of(DiagnosticBag.ERR_INCOMPATIBLE_METADATA_TYPE).equals(d.symbolicId())
                && d.message().contains("unknown IANA time zone '[-04]'")),
        () -> "Expected E008 INCOMPATIBLE_METADATA_TYPE rejection of synthetic [-04] zone on schema bound, got: " + synthRes.diagnostics()
    );
  }

  @Test
  @DisplayName("{ #ms } :TimeEpoch E020 diagnostic uses canonical facet spacing")
  void testTimeEpochZoneDiagnosticCanonicalFacetSpacing() {
    String epochWithStringDoc = """
        {
          :type { #ms } :TimeEpoch
          :body "2026-09-12T15:30:00[America/New_York]"
        }
        """;
    StvnCompilationResult<StvnDocument> res = StvnCompiler.compile(epochWithStringDoc);
    Assertions.assertTrue(res.hasErrors());
    Assertions.assertEquals(1, res.diagnostics().size());
    StvnDiagnostic diag = res.diagnostics().getFirst();
    Assertions.assertEquals(Optional.of("E020"), diag.canonicalCode());
    Assertions.assertEquals(Optional.of(DiagnosticBag.ERR_TEMPORAL_ZONE_IN_EPOCH), diag.symbolicId());
    Assertions.assertEquals(
        "Invalid { #ms } :TimeEpoch literal: IANA time zone brackets '[...]' are prohibited; expected integer epoch tick literal",
        diag.message()
    );
  }

  private static void assertZeroRawJargon(String message, String scenarioName) {
    Assertions.assertFalse(
        FORBIDDEN_RAW_JARGON.matcher(message).find(),
        () -> "Forbidden raw ANTLR or JDK exception jargon detected in " + scenarioName + ": " + message
    );
  }
}
