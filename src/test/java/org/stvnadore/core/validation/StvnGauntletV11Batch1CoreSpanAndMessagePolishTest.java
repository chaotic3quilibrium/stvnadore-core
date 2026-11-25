package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.utils.StvnProjectPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;

/**
 * Conformance regression suite for Gauntlet V11 Batch 1:
 * <ul>
 *   <li>Strict 8-character keyword-only coordinate pinning ({@code [kw.startOffset, kw.endOffset)})
 *       for {@code E011} ({@link DiagnosticBag#ERR_DISCRETE_BOUND_KIND_PROHIBITED}) across
 *       {@code :Int}, {@code { #exact } :Float}, {@code :TimeEpoch}, and {@code :DateTime} in
 *       nominal, derived, {@code #CONST}, {@code :type}, and inline composite slot schemas.</li>
 *   <li>Full-span preservation ({@code [earlierEntry.startOffset, laterEntry.endOffset)}) on
 *       co-occurring {@code E009} ({@link DiagnosticBag#ERR_INVERTED_RANGE}) inverted intervals.</li>
 *   <li>Plain-text Unicode infinity ({@code [0, ∞)}, {@code U+221E}) on {@code W003}
 *       ({@link DiagnosticBag#WARN_REDUNDANT_METADATA_FACET}) with zero raw {@code \infty}
 *       escapes in production Java sources.</li>
 *   <li>Strict token-level coordinate pinning and zero secondary cascades for bare un-sigiled
 *       boolean words ({@code true} / {@code false}, case-insensitive) via {@code E002}
 *       ({@link StvnVocabulary#ERR_BARE_BOOLEAN_PROHIBITED}).</li>
 * </ul>
 */
@NullMarked
class StvnGauntletV11Batch1CoreSpanAndMessagePolishTest {

  @Test
  @DisplayName("Target 1a: :PumpCurrentMa pins E011 strictly to '#maxIncl' disjoint from #MAX_PUMP_MA_EXCL constant argument")
  void testPumpCurrentMaConstBoundPinsE011StrictlyToKeywordDisjointFromConstArg() {
    var source = """
        {
          :defs {
            #MIN_PUMP_MA :Int 10
            #MAX_PUMP_MA_EXCL :Int 5000
            :PumpCurrentMa { #unsigned #size 16 #minIncl #MIN_PUMP_MA #maxIncl #MAX_PUMP_MA_EXCL } :Int
          }
          :type :PumpCurrentMa
          :body 420
        }
        """.replace("\r\n", "\n");

    var result = StvnCompiler.compile(source, "pump_current.stvn", StvnParserConfig.DEFAULT);
    var diags = result.diagnostics();
    Assertions.assertEquals(1, diags.size(), () -> "Expected strictly 1 E011 diagnostic: " + diags);

    var diag = diags.getFirst();
    assertExactDiagnostic(source, diag, "#maxIncl", "E011", DiagnosticBag.ERR_DISCRETE_BOUND_KIND_PROHIBITED);
    int constArgOffset = source.lastIndexOf("#MAX_PUMP_MA_EXCL");
    Assertions.assertTrue(
        diag.endOffset() <= constArgOffset,
        "E011 keyword span must remain disjoint from #MAX_PUMP_MA_EXCL argument"
    );
    Assertions.assertTrue(diag.message().contains("Discrete type ':Int' prohibits bound '#maxIncl'; use half-open bound '#maxExcl'"));
  }

  @Test
  @DisplayName("Target 1b: E011 pins strictly to 8-char '#maxIncl' or '#minExcl' across all 4 discrete types, derived overrides, #CONST, :type, inline composites, and zero-whitespace adjacency")
  void testE011KeywordPinningAcrossAllDiscreteFamiliesAndSchemaContexts() {
    var source = """
        {
          :defs {
            :RootInt { #maxIncl 100 } :Int
            :RootExact { #exact #minExcl 0.0 } :Float
            :RootEpoch { #ms #maxIncl 1000 } :TimeEpoch
            :RootDateTime { #offset #minExcl "2026-01-01T00:00:00Z" } :DateTime
            :ParentInt { #unsigned #size 16 } :Int
            :ChildInt { #maxIncl 500 } :ParentInt
            :ParentExact { #exact } :Float
            :ChildExact { #minExcl 1.5 } :ParentExact
            #BOUNDED_CONST { #maxIncl 10 } :Int 5
            :CompositeCarrier :Tuple(
              { #minExcl-5 } :Int
              :Option( { #offset #maxIncl"2026-01-01T00:00:00Z" } :DateTime )
              :Seq( { #exact #maxIncl 9.9 } :Float )
            )
          }
          :type { #minExcl 1 } :Int
          :body 5
        }
        """.replace("\r\n", "\n");

    var result = StvnCompiler.compile(source, "discrete_surfaces.stvn", StvnParserConfig.DEFAULT);
    var diags = result.diagnostics();
    Assertions.assertEquals(11, diags.size(), () -> "Expected 11 E011 diagnostics across all discrete surfaces: " + diags);

    var expectedKeywords = new String[]{
        "#maxIncl", "#minExcl", "#maxIncl", "#minExcl",
        "#maxIncl", "#minExcl", "#minExcl", "#maxIncl",
        "#maxIncl", "#maxIncl", "#minExcl"
    };
    for (int i = 0; i < expectedKeywords.length; i++) {
      var diag = diags.get(i);
      assertExactDiagnostic(source, diag, expectedKeywords[i], "E011", DiagnosticBag.ERR_DISCRETE_BOUND_KIND_PROHIBITED);
      Assertions.assertEquals(8, diag.endOffset() - diag.startOffset(), "Discrete bound keyword span must be 8 characters");
    }

    int adjacentIntLiteralStart = source.indexOf("-5 } :Int");
    Assertions.assertEquals(adjacentIntLiteralStart, diags.get(6).endOffset(), "Zero-whitespace #minExcl-5 must end exactly at '-5'");
    int adjacentStringStart = source.lastIndexOf("\"2026-01-01T00:00:00Z\" } :DateTime");
    Assertions.assertEquals(adjacentStringStart, diags.get(7).endOffset(), "Zero-whitespace #maxIncl\"...\" must end exactly at opening quote");
  }

  @Test
  @DisplayName("Target 1c: Co-occurring E011 pins to '#maxIncl' while E009 retains full '#minIncl 500 #maxIncl 50' span")
  void testCoOccurringE011KeywordSpanAndE009BoundPairSpanIsolation() {
    var source = """
        {
          :defs {
            :InvertedDiscrete { #minIncl 500 #maxIncl 50 } :Int
          }
          :type :InvertedDiscrete
          :body 100
        }
        """.replace("\r\n", "\n");

    var result = StvnCompiler.compile(source, "co_occurring_e011_e009.stvn", StvnParserConfig.DEFAULT);
    var diags = result.diagnostics();
    Assertions.assertEquals(2, diags.size(), () -> "Expected E011 + E009 without body cascade: " + diags);

    assertExactDiagnostic(source, diags.get(0), "#maxIncl", "E011", DiagnosticBag.ERR_DISCRETE_BOUND_KIND_PROHIBITED);
    assertExactDiagnostic(source, diags.get(1), "#minIncl 500 #maxIncl 50", "E009", DiagnosticBag.ERR_INVERTED_RANGE);
  }

  @Test
  @DisplayName("Target 2: Unsigned #minIncl 0 W003 emits Unicode '[0, ∞)' across full '#minIncl 0' entry and zero raw '\\infty' escapes exist in src/main/java")
  void testUnsignedRedundantMinInclZeroEmitsUnicodeInfinityWithZeroLatexEscapes() throws IOException {
    var source = """
        {
          :defs {
            :UnsignedHalfByte { #unsigned #size 8 #minIncl 0 #maxExcl 128 } :Int
          }
          :type :UnsignedHalfByte
          :body 42
        }
        """.replace("\r\n", "\n");

    var result = StvnCompiler.compile(source, "unsigned_w003.stvn", StvnParserConfig.DEFAULT);
    Assertions.assertFalse(result.hasErrors(), () -> "Expected warning-only compilation: " + result.diagnostics());
    Assertions.assertTrue(result.hasWarnings());
    Assertions.assertEquals(1, result.diagnostics().size());

    var diag = result.diagnostics().getFirst();
    assertExactDiagnostic(source, diag, "#minIncl 0", "W003", DiagnosticBag.WARN_REDUNDANT_METADATA_FACET);
    Assertions.assertTrue(
        diag.message().contains("facet '#minIncl 0' is redundant; '#unsigned' already bounds the domain to [0, \u221E)"),
        () -> "Expected Unicode infinity in W003 message, got: " + diag.message()
    );
    Assertions.assertFalse(diag.message().contains("\\infty"), "Raw LaTeX '\\infty' must not appear in diagnostic message");

    var mainRoot = StvnProjectPaths.resolve("src", "main", "java", "org", "stvnadore", "core");
    var offendingFiles = new ArrayList<String>();
    try (var stream = Files.walk(mainRoot)) {
      var javaFiles = stream.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java")).sorted().toList();
      for (Path file : javaFiles) {
        var text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.contains("\\infty")) {
          offendingFiles.add(mainRoot.relativize(file).toString());
        }
      }
    }
    Assertions.assertTrue(offendingFiles.isEmpty(), () -> "Found raw '\\infty' in production Java files: " + offendingFiles);
  }

  @Test
  @DisplayName("Target 3: Bare un-sigiled boolean literals ('true'/'false', case-insensitive) emit strictly 1 E002 ERR_BARE_BOOLEAN_PROHIBITED pinned to the bare word with zero secondary cascades")
  void testBareBooleanLiteralsPinStrictlyWithZeroCascadeAcrossBodyConstAndMetadata() {
    Assertions.assertEquals("ERR_BARE_BOOLEAN_PROHIBITED", StvnVocabulary.ERR_BARE_BOOLEAN_PROHIBITED);
    Assertions.assertEquals(StvnVocabulary.ERR_BARE_BOOLEAN_PROHIBITED, DiagnosticBag.ERR_BARE_BOOLEAN_PROHIBITED);
    Assertions.assertEquals(Optional.of("E002"), DiagnosticBag.resolveCanonicalCode(StvnVocabulary.ERR_BARE_BOOLEAN_PROHIBITED));

    assertSingleBareBooleanCase(
        """
            {
              :defs {
                #NOMINAL_GAIN_DB :Float 3.5
              }
              :type :Tuple( :Int :Int :Float :Boolean )
              :body ( 12 420 #NOMINAL_GAIN_DB true )
            }
            """,
        "true",
        "#TRUE",
        "#T"
    );

    assertSingleBareBooleanCase(
        """
            {
              :type :Seq( :Boolean )
              :body [ false ]
            }
            """,
        "false",
        "#FALSE",
        "#F"
    );

    assertSingleBareBooleanCase(
        """
            {
              :type :Boolean
              :body True
            }
            """,
        "True",
        "#TRUE",
        "#T"
    );

    assertSingleBareBooleanCase(
        """
            {
              :defs {
                #FLAG :Boolean FALSE
              }
              :type :Int
              :body 1
            }
            """,
        "FALSE",
        "#FALSE",
        "#F"
    );

    assertSingleBareBooleanCase(
        """
            {
              :defs {
                #PAIR :Tuple( :Int :Boolean ) ( 1 true )
              }
              :type :Int
              :body 1
            }
            """,
        "true",
        "#TRUE",
        "#T"
    );

    var preserveIndentSource = """
        {
          :defs {
            :Note { #preserveIndent true } :String
          }
          :type :Note
          :body "ok"
        }
        """.replace("\r\n", "\n");
    var preserveDiag = assertSingleBareBooleanCase(preserveIndentSource, "true", "#TRUE", "#T");
    Assertions.assertTrue(
        preserveDiag.startOffset() > preserveIndentSource.indexOf("#preserveIndent") + "#preserveIndent".length(),
        "Bare boolean diagnostic must not rewind onto #preserveIndent"
    );

    assertSingleBareBooleanCase(
        """
            {
              :defs {
                :Metric { #equatable false } :Int
              }
              :type :Metric
              :body 7
            }
            """,
        "false",
        "#FALSE",
        "#F"
    );

    assertSingleBareBooleanCase(
        """
            {
              :defs {
                :Tag { #comparable True } :String
              }
              :type :Tag
              :body "tag"
            }
            """,
        "True",
        "#TRUE",
        "#T"
    );

    var validSlashPaths = """
        {
          :defs {
            :package :org/true/demo {
              #GAIN :Float 1.5
            }
            :package :org/false/demo {
              :Flag :Boolean
            }
          }
          :type :Tuple( :Float :org/false/demo/Flag )
          :body ( #org/true/demo/GAIN #TRUE )
        }
        """.replace("\r\n", "\n");
    var slashResult = StvnCompiler.compile(validSlashPaths, "valid_slash_segments.stvn", StvnParserConfig.DEFAULT);
    Assertions.assertTrue(slashResult.isSuccess(), () -> "Slash path segments /true and /false must compile cleanly: " + slashResult.diagnostics());
    Assertions.assertTrue(slashResult.diagnostics().isEmpty());
  }

  private static StvnDiagnostic assertSingleBareBooleanCase(
      String rawSource,
      String expectedSlice,
      String expectedCanonical,
      String expectedShort
  ) {
    var source = rawSource.replace("\r\n", "\n");
    var result = StvnCompiler.compile(source, "bare_bool_case.stvn", StvnParserConfig.DEFAULT);
    var diags = result.diagnostics();
    Assertions.assertEquals(1, diags.size(), () -> "Expected strictly 1 diagnostic without secondary cascades: " + diags);
    var diag = diags.getFirst();
    assertExactDiagnostic(source, diag, expectedSlice, "E002", StvnVocabulary.ERR_BARE_BOOLEAN_PROHIBITED);
    var expectedMsg = "STVN Syntax Error: Bare boolean literal '" + expectedSlice
        + "' is prohibited in STVN syntax; use '" + expectedCanonical + "' (or '" + expectedShort + "')";
    Assertions.assertEquals(expectedMsg, diag.message());
    return diag;
  }

  private static void assertExactDiagnostic(
      String source,
      StvnDiagnostic diag,
      String expectedSlice,
      String expectedCanonicalCode,
      String expectedSymbolicId
  ) {
    Assertions.assertEquals(Optional.of(expectedCanonicalCode), diag.canonicalCode(), () -> "Unexpected canonical code: " + diag);
    Assertions.assertEquals(Optional.of(expectedSymbolicId), diag.symbolicId(), () -> "Unexpected symbolic ID: " + diag);
    var actualSlice = source.substring(diag.startOffset(), diag.endOffset());
    Assertions.assertEquals(expectedSlice, actualSlice, () -> "Unexpected pinned source slice for: " + diag);
  }
}
