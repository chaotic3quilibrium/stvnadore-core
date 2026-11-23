package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.binary.SchemaIdentityStrategy;
import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.binary.StvnBinaryEncoder;
import org.stvnadore.core.ir.StvnDocumentKind;
import org.stvnadore.core.ir.StvnDocumentMeta;
import org.stvnadore.core.lowering.StvnDefsLowerer;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Conformance regression suite for Gauntlet v10 Batch 1:
 * <ul>
 *   <li>Stage 1 {@code :meta} {@code E038} &amp; invalid-POSIX / lexical / duplicate stem-warning
 *       cascade suppression across textual and binary ({@code .stvn_b}, {@code .stvn_bd}) units,
 *       per-facet {@code #name} isolation, {@code W001} value-slice coordinate pinning, and Pass 3
 *       {@code :body} fault containment (§ 3.10.1 Rule 7).</li>
 *   <li>Stage 3 {@code :Float} invalid {@code #size} ({@code E010} / {@code FLOAT_INVALID_SIZE})
 *       and bidirectional {@code #exact} + {@code #size} mutual exclusivity ({@code E008} /
 *       {@code MUTUALLY_EXCLUSIVE_BOUNDS}) with zero secondary {@code ERR_FACET_ORDER_VIOLATION}
 *       or {@code W003} cascades.</li>
 * </ul>
 */
@NullMarked
class StvnGauntletV10Batch1MetaAndFloatParityTest {

  @Test
  @DisplayName("Stage 1 Textual: Reserved stvn_* #domain suppresses W001 and W002 across 1-stem and 2-stem files, isolates #name W001, and preserves Pass 3 :body and SYSTEM authority")
  void testStage1TextualReservedDomainStemWarningSuppressionAndPerFacetIsolation() {
    var sourceMatchingName = """
        {
          :meta {
            #name "vial_custody_manifest"
            #domain "stvn_cryo_vault"
            #kind #BODY
          }
          :defs {
            :CustodyTempKelvin { #minIncl 1 #maxExcl 300 } :Int
          }
          :type :CustodyTempKelvin
          :body 500
        }
        """.replace("\r\n", "\n");

    // 1. Two-stem user file: E038 on "stvn_cryo_vault" + Pass 3 E012 on 500; zero W001/W002
    var twoStemRes = StvnCompiler.compile(
        sourceMatchingName,
        "vial_custody_manifest.cryo_biobank.stvn",
        StvnParserConfig.DEFAULT
    );
    Assertions.assertTrue(twoStemRes.hasErrors());
    Assertions.assertFalse(twoStemRes.hasWarnings(), () -> "Expected 0 warnings on 2-stem E038 file, got: " + twoStemRes.diagnostics());
    Assertions.assertEquals(2, twoStemRes.diagnostics().size(), () -> "Expected E038 + Pass 3 E012, got: " + twoStemRes.diagnostics());

    var e038 = twoStemRes.diagnostics().get(0);
    int domainLitStart = sourceMatchingName.indexOf("\"stvn_cryo_vault\"");
    Assertions.assertEquals(Optional.of("E038"), e038.canonicalCode());
    Assertions.assertEquals(Optional.of(DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED), e038.symbolicId());
    Assertions.assertEquals(domainLitStart, e038.startOffset());
    Assertions.assertEquals(domainLitStart + "\"stvn_cryo_vault\"".length(), e038.endOffset());

    var bodyErr = twoStemRes.diagnostics().get(1);
    Assertions.assertEquals(Optional.of("E012"), bodyErr.canonicalCode());
    Assertions.assertEquals(Optional.of(DiagnosticBag.ERR_CONSTRAINT_VIOLATION), bodyErr.symbolicId());

    // 2. Single-stem user file: E038 on "stvn_cryo_vault" + Pass 3 E012; zero W002 (WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME)
    var oneStemRes = StvnCompiler.compile(
        sourceMatchingName,
        "vial_custody_manifest.stvn",
        StvnParserConfig.DEFAULT
    );
    Assertions.assertTrue(oneStemRes.hasErrors());
    Assertions.assertFalse(oneStemRes.hasWarnings(), () -> "Expected 0 warnings on 1-stem E038 file, got: " + oneStemRes.diagnostics());
    Assertions.assertEquals(2, oneStemRes.diagnostics().size());
    Assertions.assertEquals(Optional.of("E038"), oneStemRes.diagnostics().get(0).canonicalCode());
    Assertions.assertEquals(Optional.of("E012"), oneStemRes.diagnostics().get(1).canonicalCode());

    // 3. Per-facet isolation: mismatched valid #name alongside reserved #domain emits W001 strictly on #name string literal
    var sourceMismatchedName = """
        {
          :meta {
            #name "other_manifest_name"
            #domain "stvn_cryo_vault"
            #kind #BODY
          }
          :type :Int
          :body 42
        }
        """.replace("\r\n", "\n");
    var isolatedRes = StvnCompiler.compile(
        sourceMismatchedName,
        "vial_custody_manifest.cryo_biobank.stvn",
        StvnParserConfig.DEFAULT
    );
    Assertions.assertEquals(2, isolatedRes.diagnostics().size(), () -> "Expected E038 + #name W001, got: " + isolatedRes.diagnostics());
    Assertions.assertEquals(Optional.of("E038"), isolatedRes.diagnostics().get(0).canonicalCode());

    var nameWarn = isolatedRes.diagnostics().get(1);
    int nameLitStart = sourceMismatchedName.indexOf("\"other_manifest_name\"");
    Assertions.assertEquals(Optional.of("W001"), nameWarn.canonicalCode());
    Assertions.assertEquals(Optional.of(DiagnosticBag.WARN_DOCUMENT_NAME_MISMATCH), nameWarn.symbolicId());
    Assertions.assertEquals(nameLitStart, nameWarn.startOffset());
    Assertions.assertEquals(nameLitStart + "\"other_manifest_name\"".length(), nameWarn.endOffset());

    // 4. Under SYSTEM authority (systemAuthority == true), reserved domain is legal and participates normally in stem comparison
    var sysMismatchRes = StvnCompiler.compile(
        sourceMismatchedName,
        "other_manifest_name.cryo_biobank.stvn",
        StvnParserConfig.SYSTEM
    );
    Assertions.assertFalse(sysMismatchRes.hasErrors(), () -> "SYSTEM authority must permit stvn_* domain: " + sysMismatchRes.diagnostics());
    Assertions.assertEquals(1, sysMismatchRes.diagnostics().size());
    Assertions.assertEquals(Optional.of("W001"), sysMismatchRes.diagnostics().getFirst().canonicalCode());
    Assertions.assertEquals(Optional.of(DiagnosticBag.WARN_DOCUMENT_NAME_MISMATCH), sysMismatchRes.diagnostics().getFirst().symbolicId());
  }

  @Test
  @DisplayName("Stage 1 Binary: .stvn_b, .stvn_bd, and direct validateBinaryDocumentIdentity suppress W001/W002 on E038 and non-POSIX fields while preserving #name isolation and dual-header parity")
  void testStage1BinaryReservedDomainAndNonPosixGuards() {
    var inlineSchema = "{ :type :Int :body 0 }";
    var irPayload = StvnCompiler.compilePayload("{ :type :Int :body 42 }").orElseThrow();
    var binMeta = new StvnDocumentMeta(
        Optional.of("other_payload"),
        Optional.of("stvn_cryo_vault"),
        Optional.of(StvnDocumentKind.BODY)
    );
    var binEncoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.SelfDescribingSchema(inlineSchema));
    var binBuf = binEncoder.encode(irPayload, binMeta);
    var binBytes = new byte[binBuf.remaining()];
    binBuf.get(binBytes);

    // 1. 1-stem .stvn_b with matching #name and reserved #domain -> strictly 1x E038, 0x W002
    var oneStemBinRes = StvnCompiler.compile(binBytes, "other_payload.stvn_b", StvnParserConfig.DEFAULT);
    Assertions.assertEquals(1, oneStemBinRes.diagnostics().size(), () -> "Expected strictly E038, got: " + oneStemBinRes.diagnostics());
    Assertions.assertEquals(Optional.of("E038"), oneStemBinRes.diagnostics().getFirst().canonicalCode());

    // 2. 2-stem .stvn_b with mismatched #name and reserved #domain -> 1x E038 on #domain byte slice + 1x W001 on #name byte slice
    var twoStemBinRes = StvnCompiler.compile(binBytes, "expected_payload.cryo_biobank.stvn_b", StvnParserConfig.DEFAULT);
    Assertions.assertEquals(2, twoStemBinRes.diagnostics().size(), () -> "Expected E038 + #name W001, got: " + twoStemBinRes.diagnostics());
    var binE038 = twoStemBinRes.diagnostics().get(0);
    var binW001 = twoStemBinRes.diagnostics().get(1);
    Assertions.assertEquals(Optional.of("E038"), binE038.canonicalCode());
    Assertions.assertEquals("stvn_cryo_vault", new String(binBytes, binE038.startOffset(), binE038.endOffset() - binE038.startOffset(), StandardCharsets.UTF_8));
    Assertions.assertEquals(Optional.of("W001"), binW001.canonicalCode());
    Assertions.assertEquals("other_payload", new String(binBytes, binW001.startOffset(), binW001.endOffset() - binW001.startOffset(), StandardCharsets.UTF_8));

    // 3. 2-stem .stvn_bd dual-header consistency: matching outer & inner reserved domain emits 1x E038 + 1x #name W001 and 0x E031 contradiction
    var defsText = "{ :meta { #name \"other_defs\" #domain \"stvn_cryo_vault\" #kind #DEFS } :defs { :Temp :Int } }";
    var sysDefsDoc = StvnCompiler.compile(defsText, "other_defs.stvn_cryo_vault.stvn_d", StvnParserConfig.SYSTEM_DEFAULT)
        .document()
        .orElseThrow();
    var bdEncoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.SelfDescribingSchema(defsText), true);
    var bdBuf = bdEncoder.encode(sysDefsDoc);
    var bdBytes = new byte[bdBuf.remaining()];
    bdBuf.get(bdBytes);

    var bdRes = StvnCompiler.compile(bdBytes, "expected_defs.cryo_biobank.stvn_bd", StvnParserConfig.DEFAULT);
    Assertions.assertEquals(2, bdRes.diagnostics().size(), () -> "Expected 1x E038 + 1x #name W001 without E031 contradiction: " + bdRes.diagnostics());
    Assertions.assertEquals(Optional.of("E038"), bdRes.diagnostics().get(0).canonicalCode());
    Assertions.assertEquals(Optional.of("W001"), bdRes.diagnostics().get(1).canonicalCode());

    // 4. Direct programmatic validateBinaryDocumentIdentity guard with non-POSIX name/domain strings
    var nonPosixMeta = new StvnDocumentMeta(
        Optional.of("bad/name"),
        Optional.of("bad.domain"),
        Optional.of(StvnDocumentKind.BODY)
    );
    var oneStemBag = new DiagnosticBag(16, "clean.stvn_b");
    StvnDefsLowerer.validateBinaryDocumentIdentity(
        nonPosixMeta,
        StvnBinaryDecoder.BinaryHeaderSpans.DEFAULT,
        "clean.stvn_b",
        StvnParserConfig.DEFAULT,
        oneStemBag
    );
    Assertions.assertTrue(oneStemBag.toList().isEmpty(), () -> "Non-POSIX binary meta must not emit W001/W002: " + oneStemBag.toList());

    var twoStemBag = new DiagnosticBag(16, "clean.biobank.stvn_b");
    StvnDefsLowerer.validateBinaryDocumentIdentity(
        nonPosixMeta,
        StvnBinaryDecoder.BinaryHeaderSpans.DEFAULT,
        "clean.biobank.stvn_b",
        StvnParserConfig.DEFAULT,
        twoStemBag
    );
    Assertions.assertTrue(twoStemBag.toList().isEmpty(), () -> "Non-POSIX binary meta on 2-stem path must not emit W001/W002: " + twoStemBag.toList());
  }

  @Test
  @DisplayName("Stage 1 POSIX / Lexical / Non-String / Duplicate Guards: Zero secondary W001 or W002 across invalid :meta facet shapes")
  void testStage1InvalidPosixLexicalNonStringAndDuplicateMetaGuards() {
    var overlongId = "a".repeat(65);
    var invalidCases = new String[]{
        "{ :meta { #name \"\" #domain \"\" #kind #BODY } :type :Int :body 1 }",
        "{ :meta { #name \"bad/name\" #domain \"bad/domain\" #kind #BODY } :type :Int :body 1 }",
        "{ :meta { #name \"" + overlongId + "\" #domain \"" + overlongId + "\" #kind #BODY } :type :Int :body 1 }",
        "{ :meta { #name \"bad\\q\" #domain \"bad\\z\" #kind #BODY } :type :Int :body 1 }",
        "{ :meta { #name 42 #domain #TRUE #kind #BODY } :type :Int :body 1 }",
        "{ :meta { #name \"\"\"->[TAG]\nx\n\"\"\"[TAG] #domain \"\"\"->[TAG]\ny\n\"\"\"[TAG] #kind #BODY } :type :Int :body 1 }"
    };

    for (var src : invalidCases) {
      var res1 = StvnCompiler.compile(src, "vial_custody_manifest.stvn", StvnParserConfig.DEFAULT);
      Assertions.assertTrue(res1.hasErrors(), "Expected error for: " + src);
      Assertions.assertFalse(res1.hasWarnings(), () -> "1-stem invalid :meta must emit 0 warnings: " + res1.diagnostics());

      var res2 = StvnCompiler.compile(src, "vial_custody_manifest.cryo_biobank.stvn", StvnParserConfig.DEFAULT);
      Assertions.assertTrue(res2.hasErrors(), "Expected error for: " + src);
      Assertions.assertFalse(res2.hasWarnings(), () -> "2-stem invalid :meta must emit 0 warnings: " + res2.diagnostics());
    }

    // Duplicate #name and #domain where primary matches stem and duplicate differs -> strictly 2x ERR_DUPLICATE_METADATA_FACET, 0x W001
    var dupSource = """
        {
          :meta {
            #name "vial_custody_manifest"
            #name "rogue_duplicate_name"
            #domain "cryo_biobank"
            #domain "rogue_duplicate_domain"
            #kind #BODY
          }
          :type :Int
          :body 1
        }
        """;
    var dupRes = StvnCompiler.compile(dupSource, "vial_custody_manifest.cryo_biobank.stvn", StvnParserConfig.DEFAULT);
    Assertions.assertEquals(2, dupRes.diagnostics().size(), () -> "Expected strictly 2 duplicate-facet errors, got: " + dupRes.diagnostics());
    Assertions.assertFalse(dupRes.hasWarnings());
    Assertions.assertTrue(dupRes.diagnostics().stream().allMatch(
        d -> Optional.of(DiagnosticBag.ERR_DUPLICATE_METADATA_FACET).equals(d.symbolicId())
    ));
  }

  @Test
  @DisplayName("Stage 3 :Float Parity: Invalid #size (16, 128, 0) emits E010 on #size entry and #exact+#size in both orders emits strictly E008 without order or W003 cascades")
  void testStage3FloatInvalidSizeAndBidirectionalExactSizeMutex() {
    var source = """
        {
          :defs {
            :Half16 { #size 16 } :Float
            :Quad128 { #size 128 } :Float
            :ExactThenSize64 { #exact #size 64 } :Float
            :Size32ThenExact { #size 32 #exact } :Float
            :Size64ThenExact { #size 64 #exact } :Float
            :Size16ThenExact { #size 16 #exact } :Float
          }
          :type :Int
          :body 0
        }
        """.replace("\r\n", "\n");

    var res = StvnCompiler.compile(source, "stage3_float_parity.stvn", StvnParserConfig.DEFAULT);
    Assertions.assertTrue(res.hasErrors());
    Assertions.assertFalse(
        res.hasWarnings(),
        () -> "Conflicted #exact + #size 64 in either order must emit 0 W003 redundancy warnings: " + res.diagnostics()
    );
    Assertions.assertTrue(
        res.diagnostics().stream().noneMatch(
            d -> Optional.of(DiagnosticBag.ERR_FACET_ORDER_VIOLATION).equals(d.symbolicId())
        ),
        () -> "Reverse-order #size + #exact must emit 0 secondary ERR_FACET_ORDER_VIOLATION: " + res.diagnostics()
    );

    var errors = res.diagnostics().stream()
        .filter(d -> d.severity() == DiagnosticSeverity.ERROR)
        .toList();
    Assertions.assertEquals(7, errors.size(), () -> "Expected 7 exact :Float facet errors, got: " + errors);

    // 1. :Half16 -> E010 FLOAT_INVALID_SIZE pinned to "#size 16"
    assertSpanAndCode(source, errors.get(0), ":Half16 { ", "#size 16", "E010", DiagnosticBag.ERR_FLOAT_INVALID_SIZE);
    // 2. :Quad128 -> E010 FLOAT_INVALID_SIZE pinned to "#size 128"
    assertSpanAndCode(source, errors.get(1), ":Quad128 { ", "#size 128", "E010", DiagnosticBag.ERR_FLOAT_INVALID_SIZE);
    // 3. :ExactThenSize64 -> E008 MUTUALLY_EXCLUSIVE_BOUNDS pinned to "#size 64"
    assertSpanAndCode(source, errors.get(2), ":ExactThenSize64 { #exact ", "#size 64", "E008", DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE);
    // 4. :Size32ThenExact -> E008 MUTUALLY_EXCLUSIVE_BOUNDS pinned to "#exact"
    assertSpanAndCode(source, errors.get(3), ":Size32ThenExact { #size 32 ", "#exact", "E008", DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE);
    // 5. :Size64ThenExact -> E008 MUTUALLY_EXCLUSIVE_BOUNDS pinned to "#exact" (0 W003!)
    assertSpanAndCode(source, errors.get(4), ":Size64ThenExact { #size 64 ", "#exact", "E008", DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE);
    // 6 & 7. :Size16ThenExact -> E008 MUTUALLY_EXCLUSIVE_BOUNDS on "#exact" AND E010 FLOAT_INVALID_SIZE on "#size 16"
    assertSpanAndCode(source, errors.get(5), ":Size16ThenExact { #size 16 ", "#exact", "E008", DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE);
    assertSpanAndCode(source, errors.get(6), ":Size16ThenExact { ", "#size 16", "E010", DiagnosticBag.ERR_FLOAT_INVALID_SIZE);
  }

  private static void assertSpanAndCode(
      String source,
      StvnDiagnostic diag,
      String anchorPrefix,
      String expectedPinnedToken,
      String expectedCanonical,
      String expectedSymbolic
  ) {
    int prefixIdx = source.indexOf(anchorPrefix);
    Assertions.assertTrue(prefixIdx >= 0, "Missing anchor prefix: " + anchorPrefix);
    int expectedStart = prefixIdx + anchorPrefix.length();
    int expectedEnd = expectedStart + expectedPinnedToken.length();
    Assertions.assertEquals(Optional.of(expectedCanonical), diag.canonicalCode(), () -> "Unexpected canonical code: " + diag);
    Assertions.assertEquals(Optional.of(expectedSymbolic), diag.symbolicId(), () -> "Unexpected symbolic ID: " + diag);
    Assertions.assertEquals(expectedStart, diag.startOffset(), () -> "Unexpected startOffset: " + diag);
    Assertions.assertEquals(expectedEnd, diag.endOffset(), () -> "Unexpected endOffset: " + diag);
    Assertions.assertEquals(expectedPinnedToken, source.substring(diag.startOffset(), diag.endOffset()));
  }
}
