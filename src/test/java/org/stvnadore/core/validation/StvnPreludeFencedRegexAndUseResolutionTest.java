package org.stvnadore.core.validation;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.binary.SchemaIdentityStrategy;
import org.stvnadore.core.binary.StvnBinaryEncoder;
import org.stvnadore.core.binary.StvnSchemaHasher;
import org.stvnadore.core.ir.StvnDefsCatalog;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnDocumentKind;
import org.stvnadore.core.ir.StvnDocumentMeta;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.lowering.StvnDefsLowerer;
import org.stvnadore.core.lowering.StvnLiteralParser;
import org.stvnadore.core.lowering.StvnPayloadLowerer;
import org.stvnadore.core.parser.StvnLexer;
import org.stvnadore.core.parser.StvnParser;
import org.stvnadore.core.printer.AstCompactPrinter;
import org.stvnadore.core.printer.AstPrettyPrinter;
import org.stvnadore.core.printer.StvnSourceFormatter;
import org.stvnadore.core.stdlib.StvnPrelude;
import org.stvnadore.core.utils.StvnProjectPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression suite verifying the hybrid 4-fenced / 7-simple Standard Library Prelude
 * {@code [REGEX]} layout, Rule STR-04 ASCII-space-only indentation stripping, and
 * closed-domain {@code :use} package target validation ({@code E035} / {@code E030}).
 *
 * @since 2.0.0
 */
@NullMarked
class StvnPreludeFencedRegexAndUseResolutionTest {

  private static final Path PRELUDE_FIXTURE_PATH =
      StvnProjectPaths.resolve("shared-fixtures", "syntax", "valid", "modules", "prelude.stvn_core.stvn_d");

  private static final Pattern FENCED_REGEX_BLOCK_PATTERN =
      Pattern.compile("\"\"\"\\[REGEX]\\n[\\s\\S]*?\\[REGEX]\"\"\"");

  @Test
  @DisplayName("TC-PRELUDE-REGEX-01: Hybrid 4-fenced / 7-simple Prelude layout matches fixture and formatter fixed point")
  void testPreludeHybridFencedSimpleLayoutAndFormatterFixedPoint() throws IOException {
    var onDisk = Files.readString(PRELUDE_FIXTURE_PATH, StandardCharsets.UTF_8)
        .replace("\r\n", "\n")
        .stripTrailing();
    assertEquals(onDisk, StvnPrelude.PRELUDE_STVN_D,
        "StvnPrelude.PRELUDE_STVN_D must match prelude.stvn_core.stvn_d byte-for-byte");

    // Verify strict fixed-point idempotency under StvnSourceFormatter
    var formatted = StvnSourceFormatter.format(StvnPrelude.PRELUDE_STVN_D);
    assertEquals(StvnPrelude.PRELUDE_STVN_D, formatted,
        "StvnPrelude.PRELUDE_STVN_D must be a strict byte-for-byte fixed point of StvnSourceFormatter.format");

    // Verify strictly 4 fenced [REGEX] blocks exist in the Prelude source
    Matcher matcher = FENCED_REGEX_BLOCK_PATTERN.matcher(StvnPrelude.PRELUDE_STVN_D);
    int fencedCount = 0;
    while (matcher.find()) {
      fencedCount++;
    }
    assertEquals(4, fencedCount,
        "Standard Library Prelude must contain strictly 4 fenced [REGEX] string literals");

    // Verify the 4 backslash-bearing declarations use fenced [REGEX]
    assertTrue(StvnPrelude.PRELUDE_STVN_D.contains(":SemVer {\n        #minSize 5\n        #maxSize 128\n        #regex \"\"\"[REGEX]"),
        ":SemVer must use multi-line fenced [REGEX]");
    assertTrue(StvnPrelude.PRELUDE_STVN_D.contains("#EMAIL_LOWER_CASE_REGEX :String \"\"\"[REGEX]"),
        "#EMAIL_LOWER_CASE_REGEX must use fenced [REGEX]");
    assertTrue(StvnPrelude.PRELUDE_STVN_D.contains("#EMAIL_MIXED_CASE_REGEX :String \"\"\"[REGEX]"),
        "#EMAIL_MIXED_CASE_REGEX must use fenced [REGEX]");
    assertTrue(StvnPrelude.PRELUDE_STVN_D.contains(":IPv4 {\n        #minSize 7\n        #maxSize 15\n        #regex \"\"\"[REGEX]"),
        ":IPv4 must use multi-line fenced [REGEX]");

    // Verify the 7 escape-free regexes remain concise single-line simple strings
    assertTrue(StvnPrelude.PRELUDE_STVN_D.contains(
        "#UUID_LOWER_CASE_REGEX :String \"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$\""));
    assertTrue(StvnPrelude.PRELUDE_STVN_D.contains(
        "#UUID_MIXED_CASE_REGEX :String \"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$\""));
    assertTrue(StvnPrelude.PRELUDE_STVN_D.contains(
        ":Ulid { #minSize 26 #maxSize 26 #regex \"^[0-7][0-9A-HJKMNP-TV-Z]{25}$\" } :String"));
    assertTrue(StvnPrelude.PRELUDE_STVN_D.contains(
        "#SHA256_LOWER_CASE_REGEX :String \"^[0-9a-f]{64}$\""));
    assertTrue(StvnPrelude.PRELUDE_STVN_D.contains(
        "#SHA256_MIXED_CASE_REGEX :String \"^[0-9a-fA-F]{64}$\""));
    assertTrue(StvnPrelude.PRELUDE_STVN_D.contains(
        "#POSIX_ID_REGEX :String \"^[a-zA-Z0-9_-]+$\""));
    assertTrue(StvnPrelude.PRELUDE_STVN_D.contains(
        "#POLYGLOT_CODE_ID_REGEX :String \"^[a-zA-Z_][a-zA-Z0-9_]*$\""));
  }

  @Test
  @DisplayName("TC-PRELUDE-REGEX-02: All 8 Prelude regex constants and 12 regex nominal types preserve exact IR, printers, and CAS SHA-256")
  void testPreludeDecodedPatternsPrintersAndCasHashInvariance() {
    StvnDefsCatalog catalog = StvnPrelude.preludeCatalog();

    String expectedSemVer =
        "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-((?:0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$";
    String expectedEmailLower = "^[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}$";
    String expectedEmailMixed = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$";
    String expectedIpv4 = "^((25[0-5]|(2[0-4]|1[0-9]|[1-9]|)[0-9])\\.?\\b){4}$";
    String expectedUuidLower = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
    String expectedUuidMixed = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
    String expectedUlid = "^[0-7][0-9A-HJKMNP-TV-Z]{25}$";
    String expectedShaLower = "^[0-9a-f]{64}$";
    String expectedShaMixed = "^[0-9a-fA-F]{64}$";
    String expectedPosix = "^[a-zA-Z0-9_-]+$";
    String expectedPolyglot = "^[a-zA-Z_][a-zA-Z0-9_]*$";

    var expectedConstants = Map.of(
        "#org/stvnadore/prelude/UUID_LOWER_CASE_REGEX", expectedUuidLower,
        "#org/stvnadore/prelude/UUID_MIXED_CASE_REGEX", expectedUuidMixed,
        "#org/stvnadore/prelude/SHA256_LOWER_CASE_REGEX", expectedShaLower,
        "#org/stvnadore/prelude/SHA256_MIXED_CASE_REGEX", expectedShaMixed,
        "#org/stvnadore/prelude/EMAIL_LOWER_CASE_REGEX", expectedEmailLower,
        "#org/stvnadore/prelude/EMAIL_MIXED_CASE_REGEX", expectedEmailMixed,
        "#org/stvnadore/prelude/POSIX_ID_REGEX", expectedPosix,
        "#org/stvnadore/prelude/POLYGLOT_CODE_ID_REGEX", expectedPolyglot
    );

    assertEquals(8, catalog.constants().size(), "Prelude catalog must expose exactly 8 constants");
    for (var entry : expectedConstants.entrySet()) {
      var constant = catalog.findConstant(entry.getKey())
          .orElseThrow(() -> new AssertionError("Missing Prelude constant: " + entry.getKey()));
      var strVal = (StvnValue.StvnString) constant.value();
      assertEquals(entry.getValue(), strVal.value(),
          "Constant pattern mismatch for " + entry.getKey());
    }

    var expectedNominalRegexes = Map.ofEntries(
        Map.entry(":org/stvnadore/prelude/UuidLowerCase", expectedUuidLower),
        Map.entry(":org/stvnadore/prelude/UuidMixedCase", expectedUuidMixed),
        Map.entry(":org/stvnadore/prelude/Ulid", expectedUlid),
        Map.entry(":org/stvnadore/prelude/Sha256LowerCase", expectedShaLower),
        Map.entry(":org/stvnadore/prelude/Sha256MixedCase", expectedShaMixed),
        Map.entry(":org/stvnadore/prelude/SemVer", expectedSemVer),
        Map.entry(":org/stvnadore/prelude/EmailLowerCase", expectedEmailLower),
        Map.entry(":org/stvnadore/prelude/EmailMixedCase", expectedEmailMixed),
        Map.entry(":org/stvnadore/prelude/IPv4", expectedIpv4),
        Map.entry(":org/stvnadore/prelude/PosixId", expectedPosix),
        Map.entry(":org/stvnadore/prelude/PolyglotCodeId", expectedPolyglot),
        Map.entry(":org/stvnadore/prelude/meta/StvnPosixId64", expectedPosix)
    );

    for (var entry : expectedNominalRegexes.entrySet()) {
      var schema = (StvnSchema.StringSchema) catalog.findType(entry.getKey())
          .orElseThrow(() -> new AssertionError("Missing Prelude type: " + entry.getKey()));
      assertEquals(Optional.of(entry.getValue()), schema.rawRegexPattern(),
          "Decoded rawRegexPattern mismatch for " + entry.getKey());
    }

    // Compile equivalent simple-string-only Prelude and verify 100% IR, printer, canonical text, and CAS SHA-256 identity
    String legacySimpleStringPrelude = """
        {
          :meta {
            #name "prelude"
            #domain "stvn_core"
            #kind #DEFS
          }
          :defs {
            :package :org/stvnadore/prelude {
              #UUID_LOWER_CASE_REGEX :String "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
              #UUID_MIXED_CASE_REGEX :String "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
              :UuidLowerCase { #minSize 36 #maxSize 36 #regex #UUID_LOWER_CASE_REGEX } :String
              :UuidMixedCase { #minSize 36 #maxSize 36 #regex #UUID_MIXED_CASE_REGEX } :String
              :Ulid { #minSize 26 #maxSize 26 #regex "^[0-7][0-9A-HJKMNP-TV-Z]{25}$" } :String
              #SHA256_LOWER_CASE_REGEX :String "^[0-9a-f]{64}$"
              #SHA256_MIXED_CASE_REGEX :String "^[0-9a-fA-F]{64}$"
              :Sha256LowerCase { #minSize 64 #maxSize 64 #regex #SHA256_LOWER_CASE_REGEX } :String
              :Sha256MixedCase { #minSize 64 #maxSize 64 #regex #SHA256_MIXED_CASE_REGEX } :String
              :SemVer { #minSize 5 #maxSize 128 #regex "^(0|[1-9][0-9]*)\\\\.(0|[1-9][0-9]*)\\\\.(0|[1-9][0-9]*)(?:-((?:0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\\\.(?:0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\\\\+([0-9a-zA-Z-]+(?:\\\\.[0-9a-zA-Z-]+)*))?$" } :String
              #EMAIL_LOWER_CASE_REGEX :String "^[a-z0-9._%+-]+@[a-z0-9.-]+\\\\.[a-z]{2,}$"
              #EMAIL_MIXED_CASE_REGEX :String "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\\\.[a-zA-Z]{2,}$"
              :EmailLowerCase { #minSize 3 #maxSize 254 #regex #EMAIL_LOWER_CASE_REGEX } :String
              :EmailMixedCase { #minSize 3 #maxSize 254 #regex #EMAIL_MIXED_CASE_REGEX } :String
              :IPv4 { #minSize 7 #maxSize 15 #regex "^((25[0-5]|(2[0-4]|1[0-9]|[1-9]|)[0-9])\\\\.?\\\\b){4}$" } :String
              :Port { #unsigned #size 16 #minIncl 1 } :Int
              :Percentage { #minIncl 0.0 #maxIncl 100.0 } :Float
              :Probability { #minIncl 0.0 #maxIncl 1.0 } :Float
              :Currency { #exact } :Float
              :Latitude { #minIncl -90.0 #maxIncl 90.0 } :Float
              :Longitude { #minIncl -180.0 #maxIncl 180.0 } :Float
              #POSIX_ID_REGEX :String "^[a-zA-Z0-9_-]+$"
              :PosixId { #minSize 1 #maxSize 255 #regex #POSIX_ID_REGEX } :String
              #POLYGLOT_CODE_ID_REGEX :String "^[a-zA-Z_][a-zA-Z0-9_]*$"
              :PolyglotCodeId { #minSize 1 #maxSize 128 #regex #POLYGLOT_CODE_ID_REGEX } :String
            }
            :package :org/stvnadore/prelude/meta {
              :use [ :org/stvnadore/prelude { #strip } ]
              :StvnPosixId64 { #minSize 1 #maxSize 64 #regex #POSIX_ID_REGEX } :String
              :StvnDocumentKind :Enum[#BODY #DEFS #BODY_INCLUDE #DEFS_INCLUDE]
              :StvnDocumentMeta :Tuple(:Option(:StvnPosixId64) :Option(:StvnPosixId64) :StvnDocumentKind)
              :StvnCatalogMeta :Tuple(:StvnPosixId64 :StvnPosixId64 :StvnDocumentKind)
            }
          }
        }
        """;

    var legacyRes = StvnCompiler.compile(legacySimpleStringPrelude, PRELUDE_FIXTURE_PATH.toString(), StvnParserConfig.SYSTEM);
    assertFalse(legacyRes.hasErrors(), () -> "Legacy equivalent Prelude failed: " + legacyRes.diagnostics());
    StvnDocument legacyDoc = legacyRes.document().orElseThrow();

    var compiledHybridRes = StvnCompiler.compile(StvnPrelude.PRELUDE_STVN_D, PRELUDE_FIXTURE_PATH.toString(), StvnParserConfig.SYSTEM);
    assertFalse(compiledHybridRes.hasErrors(), () -> "Hybrid Prelude compilation failed: " + compiledHybridRes.diagnostics());
    StvnDocument compiledHybridDoc = compiledHybridRes.document().orElseThrow();

    assertEquals(AstPrettyPrinter.print(legacyDoc), AstPrettyPrinter.print(compiledHybridDoc));
    assertEquals(AstCompactPrinter.print(legacyDoc), AstCompactPrinter.print(compiledHybridDoc));
    assertEquals(StvnCompiler.toCanonicalString(legacyDoc), StvnCompiler.toCanonicalString(compiledHybridDoc));
    assertArrayEquals(StvnCompiler.computeCasFingerprint(legacyDoc), StvnCompiler.computeCasFingerprint(compiledHybridDoc));

    // Bootstrap-lower legacySimpleStringPrelude to compare all 21 Prelude nominal types 1:1
    var legacyBootstrapBag = new DiagnosticBag();
    var legacyParser = new StvnParser(new CommonTokenStream(new StvnLexer(CharStreams.fromString(legacySimpleStringPrelude))));
    var legacyBootstrap = StvnDefsLowerer.lowerPreludeBootstrap(legacyParser.stvnDocument(), legacyBootstrapBag);
    assertFalse(legacyBootstrapBag.hasErrors(), () -> "Bootstrap failed: " + legacyBootstrapBag.toList());

    StvnDocument hybridDoc = StvnPrelude.preludeDocument();
    assertEquals(legacyBootstrap.defs().nominalTypes().keySet(), hybridDoc.defs().nominalTypes().keySet());
    for (String fqni : hybridDoc.defs().nominalTypes().keySet()) {
      assertArrayEquals(
          StvnSchemaHasher.computeSha256(legacyBootstrap.defs().nominalTypes().get(fqni)),
          StvnSchemaHasher.computeSha256(hybridDoc.defs().nominalTypes().get(fqni)),
          "StvnSchemaHasher SHA-256 must match identically for " + fqni
      );
    }
  }

  @Test
  @DisplayName("TC-FENCED-DEDENT-01: Inline closing fence omits trailing LF whereas subsequent-line closing fence retains LF without capping minIndent")
  void testInlineVsSubsequentLineClosingFenceAndCrNormalization() {
    String inlineFence = "\"\"\"[REGEX]\n        ^[a-z]+\\.[0-9]+$[REGEX]\"\"\"";
    String nextLineFence = "\"\"\"[REGEX]\n        ^[a-z]+\\.[0-9]+$\n      [REGEX]\"\"\"";
    String bareCrInlineFence = "\"\"\"[REGEX]\r\n        ^[a-z]+\r        [0-9]+$[REGEX]\"\"\"";

    assertEquals("^[a-z]+\\.[0-9]+$", StvnLiteralParser.parseStringNew(inlineFence, false).text());
    assertEquals("^[a-z]+\\.[0-9]+$\n", StvnLiteralParser.parseStringNew(nextLineFence, false).text());
    assertEquals("^[a-z]+\n[0-9]+$", StvnLiteralParser.parseStringNew(bareCrInlineFence, false).text());

    var inlineRes = StvnCompiler.compileToResult("""
        {
          :defs {
            :InlinePat {
              #minSize 3
              #maxSize 32
              #regex \"\"\"[REGEX]
                ^[a-z]+\\.[0-9]+$[REGEX]\"\"\"
            } :String
          }
          :type :InlinePat
          :body "abc.42"
        }
        """, null, StvnParserConfig.DEFAULT);
    assertTrue(inlineRes.isSuccess(), () -> "Inline-closed fenced #regex must match single-line payload: " + inlineRes.diagnostics());

    var nextLineSchemaRes = StvnCompiler.compile("""
        {
          :meta { #name "next_line" #kind #DEFS }
          :defs {
            :BlockPat {
              #minSize 4
              #maxSize 32
              #regex \"\"\"[REGEX]
                ^[a-z]+\\.[0-9]+$
              [REGEX]\"\"\"
            } :String
          }
        }
        """, "next_line.stvn_d", StvnParserConfig.DEFAULT);
    assertFalse(nextLineSchemaRes.hasErrors(), () -> "Next-line fenced #regex must compile cleanly: " + nextLineSchemaRes.diagnostics());
    var blockPatSchema = (StvnSchema.StringSchema) nextLineSchemaRes.document().orElseThrow()
        .defs().findType(":BlockPat").orElseThrow();
    assertEquals(Optional.of("^[a-z]+\\.[0-9]+$\n"), blockPatSchema.rawRegexPattern(),
        "Subsequent-line closing fence must strip all 8 margin spaces without being capped by 6 spaces before [REGEX]\"\"\"");
  }

  @Test
  @DisplayName("TC-FENCED-DEDENT-02: #preserveIndent #TRUE preserves :body indentation while inline #regex strips ASCII margin")
  void testPreserveIndentTrueDoesNotPreventInlineFencedRegexDedent() {
    String source = """
        {
          :defs {
            :IndentedToken {
              #minSize 1
              #maxSize 64
              #preserveIndent #TRUE
              #regex \"\"\"[REGEX]
                ^[a-z ]+$[REGEX]\"\"\"
            } :String
          }
          :type :IndentedToken
          :body \"\"\"[RAW]
          abc[RAW]\"\"\"
        }
        """;
    var res = StvnCompiler.compile(source, null, StvnParserConfig.DEFAULT);
    assertFalse(res.hasErrors(), () -> "Compilation failed: " + res.diagnostics());
    var doc = res.document().orElseThrow();
    var schema = (StvnSchema.StringSchema) doc.defs().findType(":IndentedToken").orElseThrow();
    assertTrue(schema.preserveIndent());
    assertEquals(Optional.of("^[a-z ]+$"), schema.rawRegexPattern(),
        "Inline #regex must strip common ASCII-space margin even when #preserveIndent #TRUE is declared");
    var bodyVal = (StvnValue.StvnString) doc.payload().orElseThrow();
    assertEquals("  abc", bodyVal.value(),
        "#preserveIndent #TRUE must preserve the 2 leading spaces in the :body fenced string");
  }

  @Test
  @DisplayName("TC-FENCED-DEDENT-03: trimCommonIndent strips strictly ASCII spaces (0x20) and preserves verbatim tabs (0x09)")
  void testTrimCommonIndentPreservesVerbatimTabs() {
    assertEquals("\tcol1\tcol2", StvnPayloadLowerer.trimCommonIndent("    \tcol1\tcol2"));
    assertEquals("a\tb\tc\n\t\t", StvnPayloadLowerer.trimCommonIndent("    a\tb\tc\n    \t\t"));
    assertEquals("\t\t", StvnPayloadLowerer.trimCommonIndent("\t\t"));
    assertEquals("line1\n\nline2", StvnPayloadLowerer.trimCommonIndent("    line1\n      \n    line2"));
  }

  @Test
  @DisplayName("TC-HERMETIC-USE-01: Hermetic documents (.stvn, .stvn_d, .stvn_b, .stvn_bd) emit coordinate-pinned E035 on unresolved :use targets")
  void testHermeticUseTargetValidationAcrossAllHermeticExtensions(@TempDir Path tempDir) throws IOException {
    assertTrue(StvnPrelude.isPreludePackageTarget(":org/stvnadore/prelude"));
    assertTrue(StvnPrelude.isPreludePackageTarget(":org/stvnadore/prelude/meta"));
    assertFalse(StvnPrelude.isPreludePackageTarget(":org/stvnadore/prelude/nonexistent"));

    // 1. Positive hermetic resolution: Prelude packages + qualified root constant hierarchical prefix
    var positiveHermetic = StvnCompiler.compile("""
        {
          :defs {
            #acme/billing/TAX_RATE :Int 7
            :use [ :org/stvnadore/prelude { #strip } ]
            :use [ :org/stvnadore/prelude/meta { :StvnDocumentKind :Kind } ]
            :use [ :acme { #strip } ]
            :use [ :acme/billing { #strip } ]
          }
          :type :Tuple(:Port :Kind :Int)
          :body ( 443 #BODY #TAX_RATE )
        }
        """, "valid_hermetic.stvn", StvnParserConfig.DEFAULT);
    assertTrue(positiveHermetic.diagnostics().isEmpty(),
        () -> "Expected 0 diagnostics on valid hermetic :use targets, got: " + positiveHermetic.diagnostics());

    // 2. Negative hermetic .stvn (1-stem and 2-stem) and .stvn_d including reserved :package child scan
    String invalidHermeticDefs = """
        {
          :meta {
            #name "bad_hermetic"
            #domain "test"
            #kind #DEFS
          }
          :defs {
            :package :org/stvnadore/custom_hack {
              :package :acme/nested_inside_reserved {
                :Bad :Int
              }
              :use [ :external/inside/reserved { #strip } ]
            }
            :use [ :external/unloaded/billing { #strip } ]
            :use [ :external/unloaded/ledger { :Invoice :LocalInvoice } ]
            :use [ :external/unloaded/bare ]
            :use [ :org/stvnadore/prelude/nonexistent { #strip } ]
          }
        }
        """;
    var defsRes = StvnCompiler.compile(invalidHermeticDefs, "bad_hermetic.test.stvn_d", StvnParserConfig.DEFAULT);
    List<StvnDiagnostic> diags = defsRes.diagnostics();
    assertEquals(7, diags.size(), () -> "Expected E037 + E002 + five E035 diagnostics, got: " + diags);
    assertEquals("E037", diags.get(0).canonicalCode().orElse(""));
    assertEquals("E002", diags.get(1).canonicalCode().orElse(""));
    for (int i = 2; i < 7; i++) {
      assertEquals("E035", diags.get(i).canonicalCode().orElse(""), "Expected E035 at index " + i);
      assertEquals(DiagnosticBag.ERR_UNRESOLVED_PACKAGE_IN_HERMETIC_DOCUMENT, diags.get(i).errorCode().orElse(""));
    }

    // Verify exact coordinate pinning on :external/unloaded/billing across .stvn, .stvn_b, .stvn_d, and .stvn_bd
    String singleUseBody = "{\n  :defs {\n    :use [ :external/unloaded/billing { #strip } ]\n  }\n  :type :Int\n  :body 1\n}\n";
    int expectedStart = singleUseBody.indexOf(":external/unloaded/billing");
    int expectedEnd = expectedStart + ":external/unloaded/billing".length();
    for (String fileName : List.of("single.stvn", "single.domain.stvn", "single.stvn_b")) {
      var res = StvnCompiler.compile(singleUseBody, fileName, StvnParserConfig.DEFAULT);
      var e035 = res.diagnostics().stream()
          .filter(d -> "E035".equals(d.canonicalCode().orElse("")))
          .findFirst()
          .orElseThrow(() -> new AssertionError("Missing E035 for " + fileName + ": " + res.diagnostics()));
      assertEquals(3, e035.line());
      assertEquals(11, e035.column());
      assertEquals(expectedStart, e035.startOffset());
      assertEquals(expectedEnd, e035.endOffset());
    }

    String singleUseDefs = "{\n  :meta { #name \"single\" #kind #DEFS }\n  :defs {\n    :use [ :external/unloaded/billing { #strip } ]\n    :LocalInt :Int\n  }\n}\n";
    int defsStart = singleUseDefs.indexOf(":external/unloaded/billing");
    int defsEnd = defsStart + ":external/unloaded/billing".length();
    for (String fileName : List.of("single.stvn_d", "single.stvn_bd")) {
      var res = StvnCompiler.compile(singleUseDefs, fileName, StvnParserConfig.DEFAULT);
      var e035 = res.diagnostics().stream()
          .filter(d -> "E035".equals(d.canonicalCode().orElse("")))
          .findFirst()
          .orElseThrow(() -> new AssertionError("Missing E035 for " + fileName + ": " + res.diagnostics()));
      assertEquals(4, e035.line());
      assertEquals(11, e035.column());
      assertEquals(defsStart, e035.startOffset());
      assertEquals(defsEnd, e035.endOffset());
    }

    // Also verify real binary .stvn_bd round-trip compilation remains clean on valid Prelude :use
    var validDefsDoc = StvnCompiler.compile("""
        {
          :meta { #name "bin_ok" #kind #DEFS }
          :defs {
            :use [ :org/stvnadore/prelude { #strip } ]
            :ServicePort :Port
          }
        }
        """, "bin_ok.stvn_d", StvnParserConfig.DEFAULT).document().orElseThrow();
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault(), false);
    var buf = encoder.encode(validDefsDoc);
    var bytes = new byte[buf.remaining()];
    buf.get(bytes);
    Path bdPath = tempDir.resolve("bin_ok.stvn_bd");
    Files.write(bdPath, bytes);
    var bdRes = StvnCompiler.compile(bdPath, StvnParserConfig.DEFAULT);
    assertTrue(bdRes.diagnostics().isEmpty(), () -> "Valid .stvn_bd produced diagnostics: " + bdRes.diagnostics());

    // Verify real binary .stvn_b payload compiles cleanly
    var validBodyDoc = StvnCompiler.compile("""
        {
          :meta { #name "body_ok" #kind #BODY }
          :type :Int
          :body 99
        }
        """, "body_ok.stvn", StvnParserConfig.DEFAULT).document().orElseThrow();
    var bodyBuf = encoder.encode(
        validBodyDoc.payload().orElseThrow(),
        new StvnDocumentMeta(Optional.of("body_ok"), Optional.empty(), Optional.of(StvnDocumentKind.BODY))
    );
    var bodyBytes = new byte[bodyBuf.remaining()];
    bodyBuf.get(bodyBytes);
    Path bPath = tempDir.resolve("body_ok.stvn_b");
    Files.write(bPath, bodyBytes);
    assertTrue(Files.size(bPath) > 0);
  }

  @Test
  @DisplayName("TC-MODULAR-USE-01: Modular documents (.stvn_i, .stvn_di) emit coordinate-pinned E030 on unresolved :use targets and suppress cascade on failed :include")
  void testModularUseTargetValidationAndCascadeSuppression(@TempDir Path tempDir) throws IOException {
    Path modFile = tempDir.resolve("billing_defs.stvn_d");
    Files.writeString(modFile, """
        {
          :meta { #name "billing_defs" #kind #DEFS }
          :defs {
            :package :acme/billing/v1 {
              :InvoiceId { #unsigned #size 64 } :Int
            }
          }
        }
        """, StandardCharsets.UTF_8);

    // 1. Positive modular .stvn_i: imported package (exact and parent hierarchical prefix) + local qualified constant
    Path validModularPath = tempDir.resolve("valid_modular.stvn_i");
    String validModular = """
        {
          :meta { #name "valid_modular" #kind #BODY_INCLUDE }
          :defs {
            :include [ "billing_defs.stvn_d" ]
            #local/cfg/LIMIT :Int 10
            :use [ :acme/billing { #strip } ]
            :use [ :acme/billing/v1 { #strip } ]
            :use [ :local/cfg { #strip } ]
          }
          :type :Tuple(:InvoiceId :Int)
          :body ( 1001 #LIMIT )
        }
        """;
    Files.writeString(validModularPath, validModular, StandardCharsets.UTF_8);
    var posRes = StvnCompiler.compile(validModular, validModularPath.toString(), StvnParserConfig.DEFAULT);
    assertTrue(posRes.diagnostics().isEmpty(),
        () -> "Expected 0 diagnostics when :include exports package, got: " + posRes.diagnostics());

    // 2. Negative modular .stvn_i & .stvn_di: unresolved :use targets emit E030 pinned to useTarget
    Path invalidDiPath = tempDir.resolve("invalid_modular.stvn_di");
    String invalidDi = """
        {
          :meta { #name "invalid_modular" #kind #DEFS_INCLUDE }
          :defs {
            :include [ "billing_defs.stvn_d" ]
            :use [ :external/unloaded/billing { #strip } ]
            :use [ :org/stvnadore/prelude/nonexistent { :Port :Port } ]
            :LocalId :acme/billing/v1/InvoiceId
          }
        }
        """;
    Files.writeString(invalidDiPath, invalidDi, StandardCharsets.UTF_8);
    var negDiRes = StvnCompiler.compile(invalidDi, invalidDiPath.toString(), StvnParserConfig.DEFAULT);
    assertEquals(2, negDiRes.diagnostics().size(), () -> "Expected 2 E030 diagnostics, got: " + negDiRes.diagnostics());

    int billingStart = invalidDi.indexOf(":external/unloaded/billing");
    int billingEnd = billingStart + ":external/unloaded/billing".length();
    StvnDiagnostic d0 = negDiRes.diagnostics().get(0);
    assertEquals("E030", d0.canonicalCode().orElse(""));
    assertEquals(DiagnosticBag.ERR_UNDEFINED_TYPE, d0.errorCode().orElse(""));
    assertEquals(5, d0.line());
    assertEquals(11, d0.column());
    assertEquals(billingStart, d0.startOffset());
    assertEquals(billingEnd, d0.endOffset());

    int preludeBadStart = invalidDi.indexOf(":org/stvnadore/prelude/nonexistent");
    int preludeBadEnd = preludeBadStart + ":org/stvnadore/prelude/nonexistent".length();
    StvnDiagnostic d1 = negDiRes.diagnostics().get(1);
    assertEquals("E030", d1.canonicalCode().orElse(""));
    assertEquals(6, d1.line());
    assertEquals(11, d1.column());
    assertEquals(preludeBadStart, d1.startOffset());
    assertEquals(preludeBadEnd, d1.endOffset());

    // 3. Cascade suppression: failed :include (E005) suppresses secondary E030 on :use
    Path brokenIncludePath = tempDir.resolve("broken_include.stvn_i");
    String brokenInclude = """
        {
          :meta { #name "broken_include" #kind #BODY_INCLUDE }
          :defs {
            :include [ "does_not_exist.stvn_d" ]
            :use [ :acme/billing/v1 { #strip } ]
          }
          :type :Int
          :body 1
        }
        """;
    Files.writeString(brokenIncludePath, brokenInclude, StandardCharsets.UTF_8);
    var brokenRes = StvnCompiler.compile(brokenInclude, brokenIncludePath.toString(), StvnParserConfig.DEFAULT);
    assertTrue(brokenRes.diagnostics().stream().anyMatch(d -> "E005".equals(d.canonicalCode().orElse(""))),
        () -> "Expected E005 for missing include: " + brokenRes.diagnostics());
    assertTrue(brokenRes.diagnostics().stream().noneMatch(d -> "E030".equals(d.canonicalCode().orElse(""))),
        () -> "Failed :include must suppress cascading E030 on :use, got: " + brokenRes.diagnostics());
  }
}
