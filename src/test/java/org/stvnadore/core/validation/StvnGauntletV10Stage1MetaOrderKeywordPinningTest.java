package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnParserConfig;

import java.util.Optional;

/**
 * Conformance regression suite for Gauntlet v10 Stage 1:
 * <ul>
 *   <li>Disjoint {@code :meta} 3-tier {@code ERR_FACET_ORDER_VIOLATION} ({@code E008}) keyword-token
 *       coordinate pinning ({@code #name}, {@code #domain}, {@code #kind}) versus value-literal
 *       diagnostics ({@code W001}, {@code W002}, {@code E038}, invalid-POSIX {@code E008}).</li>
 *   <li>Zero-whitespace lexical adjacency ({@code kw.endOffset() == val.startOffset()}) non-overlap.</li>
 *   <li>Duplicate {@code :meta} facet precedence ({@code ERR_DUPLICATE_METADATA_FACET} without
 *       stacking {@code ERR_FACET_ORDER_VIOLATION}) and non-poisoning Pass 3 {@code :body} execution.</li>
 *   <li>Preservation of schema {@code metadataMap} ({@code { ... }}) 5-tier {@code MetadataEntry} spans.</li>
 * </ul>
 */
@NullMarked
class StvnGauntletV10Stage1MetaOrderKeywordPinningTest {

  @Test
  @DisplayName("Stage 1 Smoke Reproduction: #domain -> #name -> #kind pins E008 strictly to #name keyword disjoint from W001 on stringLiteral and preserves Pass 3 :body E012")
  void testSmokeReproductionDomainBeforeNameDisjointPinningAndBodyIsolation() {
    var source = """
        {
          :meta {
            #domain "cryo_biobank"
            #name "vial_custody_manifest"
            #kind #BODY_INCLUDE
          }
          :defs {
            :CustodyTempKelvin { #minIncl 1 #maxExcl 300 } :Int
          }
          :type :CustodyTempKelvin
          :body 500
        }
        """.replace("\r\n", "\n");

    // 1. Mismatched stem[0]: E008 on #name + W001 on "vial_custody_manifest" + E012 on 500
    var mismatchRes = StvnCompiler.compile(
        source,
        "other_manifest.cryo_biobank.stvn_i",
        StvnParserConfig.DEFAULT
    );
    var diags = mismatchRes.diagnostics();
    Assertions.assertEquals(3, diags.size(), () -> "Expected E008 + W001 + Pass 3 E012, got: " + diags);

    var orderErr = diags.get(0);
    var stemWarn = diags.get(1);
    var bodyErr = diags.get(2);

    assertExactSlice(source, orderErr, "#name", "E008", DiagnosticBag.ERR_FACET_ORDER_VIOLATION);
    assertExactSlice(source, stemWarn, "\"vial_custody_manifest\"", "W001", DiagnosticBag.WARN_DOCUMENT_NAME_MISMATCH);
    assertExactSlice(source, bodyErr, "500", "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION);
    Assertions.assertTrue(
        orderErr.endOffset() < stemWarn.startOffset(),
        () -> "Expected separating whitespace between #name E008 and W001 stringLiteral: " + diags
    );

    // 2. Matching stem[0] & stem[1]: strictly E008 on #name + E012 on 500 (0 warnings)
    var matchingStemRes = StvnCompiler.compile(
        source,
        "vial_custody_manifest.cryo_biobank.stvn_i",
        StvnParserConfig.DEFAULT
    );
    Assertions.assertFalse(matchingStemRes.hasWarnings(), () -> "Expected 0 warnings when stems match: " + matchingStemRes.diagnostics());
    Assertions.assertEquals(2, matchingStemRes.diagnostics().size());
    assertExactSlice(source, matchingStemRes.diagnostics().get(0), "#name", "E008", DiagnosticBag.ERR_FACET_ORDER_VIOLATION);
    assertExactSlice(source, matchingStemRes.diagnostics().get(1), "500", "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION);
  }

  @Test
  @DisplayName("Stage 1 #domain & #name Matrix: Out-of-order #domain after #kind stays disjoint from W001, W002, E038, and invalid-POSIX E008")
  void testOutOfOrderDomainAndNameDisjointAcrossWarningAndErrorVariants() {
    var validNonReservedSource = """
        {
          :meta {
            #kind #BODY
            #domain "other_biobank"
            #name "vial_custody_manifest"
          }
          :defs {
            :CustodyTempKelvin { #minIncl 1 #maxExcl 300 } :Int
          }
          :type :CustodyTempKelvin
          :body 500
        }
        """.replace("\r\n", "\n");

    // 2-stem -> #domain E008 + #name E008 + #name W001 + #domain W001 + :body E012
    var twoStemRes = StvnCompiler.compile(
        validNonReservedSource,
        "expected_manifest.cryo_biobank.stvn",
        StvnParserConfig.DEFAULT
    );
    var twoStemDiags = twoStemRes.diagnostics();
    Assertions.assertEquals(5, twoStemDiags.size(), () -> "Unexpected 2-stem diagnostics: " + twoStemDiags);
    assertExactSlice(validNonReservedSource, twoStemDiags.get(0), "#domain", "E008", DiagnosticBag.ERR_FACET_ORDER_VIOLATION);
    assertExactSlice(validNonReservedSource, twoStemDiags.get(1), "#name", "E008", DiagnosticBag.ERR_FACET_ORDER_VIOLATION);
    assertExactSlice(validNonReservedSource, twoStemDiags.get(2), "\"vial_custody_manifest\"", "W001", DiagnosticBag.WARN_DOCUMENT_NAME_MISMATCH);
    assertExactSlice(validNonReservedSource, twoStemDiags.get(3), "\"other_biobank\"", "W001", DiagnosticBag.WARN_DOCUMENT_NAME_MISMATCH);
    assertExactSlice(validNonReservedSource, twoStemDiags.get(4), "500", "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION);
    Assertions.assertTrue(twoStemDiags.get(0).endOffset() <= twoStemDiags.get(3).startOffset());
    Assertions.assertTrue(twoStemDiags.get(1).endOffset() <= twoStemDiags.get(2).startOffset());

    // 1-stem -> #domain E008 + #name E008 + #name W001 + #domain W002 + :body E012
    var oneStemRes = StvnCompiler.compile(
        validNonReservedSource,
        "expected_manifest.stvn",
        StvnParserConfig.DEFAULT
    );
    var oneStemDiags = oneStemRes.diagnostics();
    Assertions.assertEquals(5, oneStemDiags.size(), () -> "Unexpected 1-stem diagnostics: " + oneStemDiags);
    assertExactSlice(validNonReservedSource, oneStemDiags.get(0), "#domain", "E008", DiagnosticBag.ERR_FACET_ORDER_VIOLATION);
    assertExactSlice(validNonReservedSource, oneStemDiags.get(1), "#name", "E008", DiagnosticBag.ERR_FACET_ORDER_VIOLATION);
    assertExactSlice(validNonReservedSource, oneStemDiags.get(2), "\"vial_custody_manifest\"", "W001", DiagnosticBag.WARN_DOCUMENT_NAME_MISMATCH);
    assertExactSlice(validNonReservedSource, oneStemDiags.get(3), "\"other_biobank\"", "W002", DiagnosticBag.WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME);
    assertExactSlice(validNonReservedSource, oneStemDiags.get(4), "500", "E012", DiagnosticBag.ERR_CONSTRAINT_VIOLATION);
    Assertions.assertTrue(oneStemDiags.get(0).endOffset() <= oneStemDiags.get(3).startOffset());

    // Invalid POSIX on out-of-order #domain and #name -> keyword E008 order + value E008 invalid POSIX, 0 warnings, 0 overlap
    var invalidPosixSource = """
        {
          :meta {
            #kind #BODY
            #domain "bad.domain"
            #name "bad/name"
          }
          :type :Int
          :body 42
        }
        """.replace("\r\n", "\n");
    var posixRes = StvnCompiler.compile(invalidPosixSource, "vial.cryo_biobank.stvn", StvnParserConfig.DEFAULT);
    Assertions.assertFalse(posixRes.hasWarnings(), () -> "Invalid POSIX must suppress W001/W002: " + posixRes.diagnostics());
    var posixDiags = posixRes.diagnostics();
    Assertions.assertEquals(4, posixDiags.size(), () -> "Expected 2x order E008 + 2x POSIX E008: " + posixDiags);
    assertExactSlice(invalidPosixSource, posixDiags.get(0), "#domain", "E008", DiagnosticBag.ERR_FACET_ORDER_VIOLATION);
    assertExactSlice(invalidPosixSource, posixDiags.get(1), "\"bad.domain\"", "E008", DiagnosticBag.ERR_INVALID_METADATA_FACET);
    assertExactSlice(invalidPosixSource, posixDiags.get(2), "#name", "E008", DiagnosticBag.ERR_FACET_ORDER_VIOLATION);
    assertExactSlice(invalidPosixSource, posixDiags.get(3), "\"bad/name\"", "E008", DiagnosticBag.ERR_INVALID_METADATA_FACET);
    Assertions.assertTrue(posixDiags.get(0).endOffset() <= posixDiags.get(1).startOffset());
    Assertions.assertTrue(posixDiags.get(2).endOffset() <= posixDiags.get(3).startOffset());
  }

  @Test
  @DisplayName("Stage 1 Zero-Whitespace Adjacency: #kind#BODY#domain\"b\"#name\"a\" maintains kw.end == val.start (0 overlap)")
  void testZeroWhitespaceAdjacencyMaintainsHalfOpenDisjointness() {
    var compactSource = "{:meta{#kind#BODY#domain\"other_domain\"#name\"other_name\"}:type :Int :body 7}";
    var res = StvnCompiler.compile(compactSource, "expected_name.expected_domain.stvn", StvnParserConfig.DEFAULT);
    var diags = res.diagnostics();
    Assertions.assertEquals(4, diags.size(), () -> "Expected 2x order E008 + 2x W001: " + diags);

    var domOrder = diags.get(0);
    var nameOrder = diags.get(1);
    var nameWarn = diags.get(2);
    var domWarn = diags.get(3);

    assertExactSlice(compactSource, domOrder, "#domain", "E008", DiagnosticBag.ERR_FACET_ORDER_VIOLATION);
    assertExactSlice(compactSource, domWarn, "\"other_domain\"", "W001", DiagnosticBag.WARN_DOCUMENT_NAME_MISMATCH);
    Assertions.assertEquals(domOrder.endOffset(), domWarn.startOffset(), "Adjacent #domain and stringLiteral must touch at boundary with 0 overlap");

    assertExactSlice(compactSource, nameOrder, "#name", "E008", DiagnosticBag.ERR_FACET_ORDER_VIOLATION);
    assertExactSlice(compactSource, nameWarn, "\"other_name\"", "W001", DiagnosticBag.WARN_DOCUMENT_NAME_MISMATCH);
    Assertions.assertEquals(nameOrder.endOffset(), nameWarn.startOffset(), "Adjacent #name and stringLiteral must touch at boundary with 0 overlap");
  }

  @Test
  @DisplayName("Stage 1 Duplicate Precedence & Scope Boundary: Duplicate :meta facets after higher tiers emit strictly ERR_DUPLICATE_METADATA_FACET and schema metadataMap retains MetadataEntry spans")
  void testDuplicateMetaPrecedenceAndSchemaMetadataMapBoundaryPreserved() {
    var dupMetaSource = """
        {
          :meta {
            #name "vial_custody_manifest"
            #kind #BODY
            #domain "cryo_biobank"
            #name "duplicate_late_name"
            #domain "duplicate_late_domain"
          }
          :defs {
            :BadFlagOrder { #minIncl 1 #unsigned } :Int
            :BadValuedOrder { #minIncl 1 #size 16 } :Int
          }
          :type :Int
          :body 1
        }
        """.replace("\r\n", "\n");

    var res = StvnCompiler.compile(dupMetaSource, "vial_custody_manifest.cryo_biobank.stvn", StvnParserConfig.DEFAULT);
    Assertions.assertFalse(res.hasWarnings(), () -> "Primary facets match stem and duplicates suppress W001: " + res.diagnostics());
    var diags = res.diagnostics();
    Assertions.assertEquals(5, diags.size(), () -> "Expected 1x :meta order + 2x :meta duplicate + 2x schema 5-tier order: " + diags);

    // 1. Primary #domain after #kind -> keyword-pinned ERR_FACET_ORDER_VIOLATION
    assertExactSlice(dupMetaSource, diags.get(0), "#domain", "E008", DiagnosticBag.ERR_FACET_ORDER_VIOLATION);
    // 2. Duplicate #name after #kind/#domain -> full-facet ERR_DUPLICATE_METADATA_FACET (0 order error)
    assertExactSlice(dupMetaSource, diags.get(1), "#name \"duplicate_late_name\"", "E008", DiagnosticBag.ERR_DUPLICATE_METADATA_FACET);
    // 3. Duplicate #domain after #kind -> full-facet ERR_DUPLICATE_METADATA_FACET (0 order error)
    assertExactSlice(dupMetaSource, diags.get(2), "#domain \"duplicate_late_domain\"", "E008", DiagnosticBag.ERR_DUPLICATE_METADATA_FACET);
    // 4 & 5. Schema metadataMap 5-tier ordering -> full MetadataEntryContext spans preserved
    assertExactSlice(dupMetaSource, diags.get(3), "#unsigned", "E008", DiagnosticBag.ERR_FACET_ORDER_VIOLATION);
    assertExactSlice(dupMetaSource, diags.get(4), "#size 16", "E008", DiagnosticBag.ERR_FACET_ORDER_VIOLATION);
  }

  private static void assertExactSlice(
      String source,
      StvnDiagnostic diag,
      String expectedSlice,
      String expectedCanonical,
      String expectedSymbolic
  ) {
    Assertions.assertEquals(Optional.of(expectedCanonical), diag.canonicalCode(), () -> "Unexpected canonicalCode: " + diag);
    Assertions.assertEquals(Optional.of(expectedSymbolic), diag.symbolicId(), () -> "Unexpected symbolicId: " + diag);
    Assertions.assertEquals(
        expectedSlice,
        source.substring(diag.startOffset(), diag.endOffset()),
        () -> "Unexpected pinned slice for " + diag
    );
  }
}
