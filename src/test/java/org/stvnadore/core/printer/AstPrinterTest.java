package org.stvnadore.core.printer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.binary.StvnSchemaHasher;
import org.stvnadore.core.io.CanonicalStvnWriter;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.stdlib.StvnPrelude;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Comprehensive verification tests for canonical pure-IR printers ({@link AstPrettyPrinter},
 * {@link AstCompactPrinter}, {@link CanonicalStvnWriter}, and {@link StvnSchemaFlattener}).
 */
class AstPrinterTest {

  @Test
  @DisplayName("AstPrettyPrinter formats with 2-space indentation and long-form keywords by default")
  void testAstPrettyPrinterDefault2Space() {
    String source = """
        {
          :type :Tuple(:Boolean :Option(:Boolean))
          :body (
            #TRUE
            #None
          )
        }
        """;
    StvnValue ast = StvnCompiler.compilePayload(source).orElseThrow();
    String printed = AstPrettyPrinter.print(ast);

    // Verify 2-space indentation standard
    Assertions.assertTrue(printed.contains("  :type :Tuple(:Boolean :Option(:Boolean))\n  :body (#TRUE #None)"),
        "Output must use 2-space indentation:\n" + printed);

    // Verify long-form keyword emission
    Assertions.assertTrue(printed.contains("#TRUE"), "Output must contain #TRUE");
    Assertions.assertTrue(printed.contains("#None"), "Output must contain #None");

    // Verify strict zero-tab invariant
    Assertions.assertFalse(printed.contains("\t"), "Output must not contain tab characters");

    // Verify round-trip compilation
    StvnValue roundTrip = StvnCompiler.compilePayload(printed).orElseThrow();
    Assertions.assertEquals(ast, roundTrip, "Round-trip AST must be mathematically identical");
  }

  @Test
  @DisplayName("AstPrettyPrinter accepts configurable indentation width")
  void testAstPrettyPrinterConfigurableIndent() {
    String source = "{ :type :Int :body 42 }";
    StvnValue ast = StvnCompiler.compilePayload(source).orElseThrow();

    String printed4 = AstPrettyPrinter.print(ast, 4);
    Assertions.assertTrue(printed4.contains("    :type :Int\n    :body 42"),
        "Output must use 4-space indentation when specified:\n" + printed4);

    String printed0 = AstPrettyPrinter.print(ast, 0);
    Assertions.assertFalse(printed0.contains("    "), "Output with 0 indent must have no leading indentation");

    // Negative indentation must throw IllegalArgumentException
    Assertions.assertThrows(IllegalArgumentException.class, () -> new AstPrettyPrinter(-1));
  }

  @Test
  @DisplayName("AstCompactPrinter emits single-line minimal layout with short-form keywords")
  void testAstCompactPrinter() {
    String source = """
        {
          :type :Tuple(:Boolean :Option(:Boolean))
          :body (
            #TRUE
            #None
          )
        }
        """;
    StvnValue ast = StvnCompiler.compilePayload(source).orElseThrow();
    String compact = AstCompactPrinter.print(ast);

    // Verify compact single-line layout
    Assertions.assertEquals("{:type :Tuple(:Boolean :Option(:Boolean)):body(#T #N)}", compact);

    // Verify short-form keywords
    Assertions.assertTrue(compact.contains("#T"), "Output must contain #T");
    Assertions.assertTrue(compact.contains("#N"), "Output must contain #N");

    // Verify zero-tab invariant
    Assertions.assertFalse(compact.contains("\t"), "Output must not contain tab characters");

    // Verify round-trip compilation
    StvnValue roundTrip = StvnCompiler.compilePayload(compact).orElseThrow();
    Assertions.assertEquals(ast, roundTrip, "Round-trip AST must be mathematically identical");
  }

  @Test
  @DisplayName("AstPrettyPrinter and AstCompactPrinter support Writer destinations")
  void testWriterDestinations() throws Exception {
    String source = "{ :type :String :body \"Test\" }";
    StvnValue ast = StvnCompiler.compilePayload(source).orElseThrow();

    var prettySw = new StringWriter();
    AstPrettyPrinter.print(ast, prettySw);
    Assertions.assertFalse(prettySw.toString().isEmpty());

    var compactSw = new StringWriter();
    AstCompactPrinter.print(ast, compactSw);
    Assertions.assertEquals("{:type :String:body \"Test\"}", compactSw.toString());
  }

  @Test
  @DisplayName("AstCompactPrinter fails closed on poisoned value without schema context under HAPPY_PATH_INFERRED")
  void testFailClosedPerimeter() {
    var printer = new AstCompactPrinter(new PrinterOptions(
        PrinterOptions.Coverage.ALL_SECTIONS,
        0,
        PrinterOptions.SymbolStyle.SHORT_FORM,
        PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
    ));

    var poisonedSchema = new StvnSchema.PoisonedSchema(
        Optional.empty(),
        "E030",
        Optional.empty(),
        StvnSchema.TraitProfile.ofDefault(true, true)
    );
    var poisonedValue = new StvnValue.StvnBoolean(poisonedSchema, true);

    var exPoisoned = Assertions.assertThrows(IllegalStateException.class, () -> printer.printToString(poisonedValue));
    Assertions.assertEquals(
        "Cannot apply HAPPY_PATH_INFERRED without schema context: target schema is required to verify variant elision legality under Rules A, B, and I.",
        exPoisoned.getMessage()
    );
  }

  @Test
  @DisplayName("AstCompactPrinter serializes StvnDocument")
  void testDocumentSerialization() {
    String source = """
        {
          :meta {
            #name "test-doc"
            #domain "org_stvnadore"
            #kind #BODY
          }
          :type :Int
          :body 42
        }
        """;
    var doc = StvnCompiler.compile(source).document().orElseThrow();
    String compact = AstCompactPrinter.print(doc);
    Assertions.assertTrue(compact.contains(":meta{"));
    Assertions.assertTrue(compact.contains("#name \"test-doc\""));
    Assertions.assertTrue(compact.contains(":type :Int:body 42"));
  }

  @Test
  @DisplayName("Stage 3A: Canonical 5-tier decimal metadata facet order recompiles with zero ERR_FACET_ORDER_VIOLATION and zero W003")
  void testFiveTierMetadataFacetOrderRoundTrip() {
    String source = """
        {
          :defs {
            :TieredInt { #unsigned #size 64 #comparable #FALSE #minIncl 10 #maxExcl 500 } :Int
            :TieredDecimal { #exact #maxSize 38 #minIncl 0.0 #maxExcl 1000.0 } :Float
            :TieredStr { #minSize 1 #maxSize 128 #minIncl "A" #maxIncl "z" #preserveIndent #TRUE #regex "^[A-Za-z\\\\t\\\\n ]+$" } :String
            :TieredMap { #invertible #minSize 1 #maxSize 16 } :Map( :String :TieredInt )
            :TieredEpoch { #ns #minIncl -1000 #maxExcl 1000000 } :TimeEpoch
            :TieredDate { #audited #minIncl "2020-01-01T00:00:00-05:00[America/New_York]" #maxExcl "2030-01-01T00:00:00-05:00[America/New_York]" } :DateTime
            :BaseState :Enum[ #IDLE #RUNNING #DONE ]
            :ActiveState { #filterIncl [ #IDLE #RUNNING ] } :BaseState
            :RootRecord :Tuple( :TieredInt :TieredDecimal :TieredStr :TieredMap :TieredEpoch :TieredDate :ActiveState )
          }
          :type :RootRecord
          :body (
            42
            123.45
            "Alpha"
            { [ "k1" 100 ] }
            5000
            "2026-03-15T08:00:00-04:00[America/New_York]"
            #RUNNING
          )
        }
        """;

    StvnCompilationResult<StvnDocument> initial = StvnCompiler.compile(source);
    Assertions.assertFalse(initial.hasErrors(), () -> "Initial compile errors: " + initial.diagnostics());
    Assertions.assertTrue(initial.diagnostics().isEmpty(), () -> "Initial diagnostics must be empty: " + initial.diagnostics());
    StvnDocument doc = initial.document().orElseThrow();

    String pretty = AstPrettyPrinter.print(doc);
    String compact = AstCompactPrinter.print(doc);
    String canonical = StvnCompiler.toCanonicalString(doc);

    for (String emitted : new String[]{pretty, compact, canonical}) {
      StvnCompilationResult<StvnDocument> recompiled = StvnCompiler.compile(emitted);
      Assertions.assertFalse(recompiled.hasErrors(), () -> "Recompile errors on:\n" + emitted + "\nDiags: " + recompiled.diagnostics());
      Assertions.assertTrue(
          recompiled.diagnostics().isEmpty(),
          () -> "Expected zero warnings (no W003) and zero errors on:\n" + emitted + "\nDiags: " + recompiled.diagnostics()
      );
      Assertions.assertEquals(doc.payload().orElseThrow(), recompiled.document().orElseThrow().payload().orElseThrow());
    }

    // Verify StvnSchemaFlattener 5-tier facet order on #DEFS_INCLUDE -> #DEFS
    String defsSource = """
        {
          :meta {
            #name "tiered_schema"
            #domain "org_stvnadore_test"
            #kind #DEFS_INCLUDE
          }
          :defs {
            :TieredInt { #unsigned #size 64 #comparable #FALSE #minIncl 10 #maxExcl 500 } :Int
            :TieredStr { #minSize 1 #maxSize 128 #preserveIndent #TRUE #regex "^[A-Za-z]+$" } :String
          }
        }
        """;
    String flattened = StvnSchemaFlattener.flatten(Map.of("tiered_schema.org_stvnadore_test.stvn_di", defsSource), "tiered_schema.org_stvnadore_test.stvn_di");
    StvnCompilationResult<StvnDocument> flattenedCompiled = StvnCompiler.compile(flattened, "tiered_schema.org_stvnadore_test.stvn_d");
    Assertions.assertFalse(flattenedCompiled.hasErrors(), () -> "Flattened errors: " + flattenedCompiled.diagnostics());
    Assertions.assertTrue(flattenedCompiled.diagnostics().isEmpty(), () -> "Flattened warnings: " + flattenedCompiled.diagnostics());
  }

  @Test
  @DisplayName("Stage 3A: CanonicalStvnWriter, AstPrettyPrinter, and AstCompactPrinter losslessly serialize #DEFS documents")
  void testLosslessDefsDocumentSerialization() {
    String defsDocSource = """
        {
          :meta {
            #name "core_defs"
            #domain "org_stvnadore"
            #kind #DEFS
          }
          :defs {
            :UserId { #unsigned #size 64 } :Int
            :Status :Enum[ #ACTIVE #SUSPENDED ]
          }
        }
        """;
    StvnCompilationResult<StvnDocument> compiled = StvnCompiler.compile(defsDocSource, "core_defs.stvn_d");
    Assertions.assertFalse(compiled.hasErrors(), () -> "Errors: " + compiled.diagnostics());
    StvnDocument defsDoc = compiled.document().orElseThrow();
    Assertions.assertTrue(defsDoc.payload().isEmpty(), "#DEFS document must have empty payload()");

    String canonical = StvnCompiler.toCanonicalString(defsDoc);
    String pretty = AstPrettyPrinter.print(defsDoc);
    String compact = AstCompactPrinter.print(defsDoc);

    for (String serialized : new String[]{canonical, pretty, compact}) {
      Assertions.assertFalse(serialized.isBlank(), "Serialized #DEFS document must never be empty");
      Assertions.assertTrue(serialized.contains("#DEFS"), "Serialized #DEFS must preserve #kind #DEFS:\n" + serialized);
      Assertions.assertTrue(serialized.contains(":UserId"), "Serialized #DEFS must preserve :UserId:\n" + serialized);

      StvnCompilationResult<StvnDocument> roundTrip = StvnCompiler.compile(serialized, "core_defs.stvn_d");
      Assertions.assertFalse(roundTrip.hasErrors(), () -> "Round-trip #DEFS errors: " + roundTrip.diagnostics());
      StvnDocument rtDoc = roundTrip.document().orElseThrow();
      Assertions.assertEquals(defsDoc.defs().nominalTypes().keySet(), rtDoc.defs().nominalTypes().keySet());
    }
  }

  @Test
  @DisplayName("Stage 3A: :DateTime preserves :00 seconds and minimal fractional digits; quoted and STR-04 fenced strings preserve tabs losslessly")
  void testDateTimeZeroSecondsAndTabStringPreservation() {
    String source = "{\n"
        + "  :defs {\n"
        + "    :DT { #offset } :DateTime\n"
        + "    :IndentedStr { #preserveIndent #TRUE } :String\n"
        + "    :Payload :Tuple( :DT :DT :String :IndentedStr )\n"
        + "  }\n"
        + "  :type :Payload\n"
        + "  :body (\n"
        + "    \"2026-03-15T08:00:00Z\"\n"
        + "    \"2026-03-15T08:00:00.123000Z\"\n"
        + "    \"col1\\tcol2\"\n"
        + "    \"\"\"[RAW]\n"
        + "line1\twith\ttabs\n"
        + "line2\tindented\n"
        + "[RAW]\"\"\"\n"
        + "  )\n"
        + "}\n";

    StvnDocument doc = StvnCompiler.compile(source).document().orElseThrow();
    StvnValue.StvnTuple tuple = (StvnValue.StvnTuple) doc.payload().orElseThrow();
    StvnValue.StvnDateTime dtZeroSec = (StvnValue.StvnDateTime) tuple.elements().get(0);
    StvnValue.StvnDateTime dtFracSec = (StvnValue.StvnDateTime) tuple.elements().get(1);
    StvnValue.StvnString quotedTabStr = (StvnValue.StvnString) tuple.elements().get(2);
    StvnValue.StvnString fencedTabStr = (StvnValue.StvnString) tuple.elements().get(3);

    Assertions.assertEquals("2026-03-15T08:00:00Z", dtZeroSec.canonicalIsoText());
    Assertions.assertEquals("2026-03-15T08:00:00.123Z", dtFracSec.canonicalIsoText());
    Assertions.assertEquals("col1\tcol2", quotedTabStr.value());
    Assertions.assertEquals("line1\twith\ttabs\nline2\tindented\n", fencedTabStr.value());

    String canonicalOut = StvnCompiler.toCanonicalString(doc);
    String prettyOut = AstPrettyPrinter.print(doc);
    String compactOut = AstCompactPrinter.print(doc);

    Assertions.assertTrue(canonicalOut.contains("\"col1\\tcol2\""),
        "Canonical writer must escape tab as \\t in simple string:\n" + canonicalOut);
    Assertions.assertTrue(compactOut.contains("\"col1\\tcol2\""),
        "Compact printer must escape tab as \\t in simple string:\n" + compactOut);
    Assertions.assertTrue(prettyOut.contains("\"\"\"[RAW]\n      col1\tcol2[RAW]\"\"\""),
        "Pretty printer must emit fenced [RAW] string with raw tab under Fenced-String Bias:\n" + prettyOut);

    for (String printed : new String[]{canonicalOut, prettyOut, compactOut}) {
      Assertions.assertTrue(printed.contains("\"2026-03-15T08:00:00Z\""), "Must preserve :00 seconds:\n" + printed);
      Assertions.assertTrue(printed.contains("\"2026-03-15T08:00:00.123Z\""), "Must trim trailing zero nanos:\n" + printed);

      StvnDocument rtDoc = StvnCompiler.compile(printed).document().orElseThrow();
      Assertions.assertEquals(doc.payload().orElseThrow(), rtDoc.payload().orElseThrow(),
          "Round-trip payload must preserve tabs and :00 seconds identically:\n" + printed);
    }
  }

  @Test
  @DisplayName("TC-BIAS-01: AstPrettyPrinter preserves Prelude 4-fenced / 7-simple regex idiom and StvnSourceFormatter fixed point")
  void testPreludeFencedRegexIdiomAndFixedPoint() {
    Assertions.assertEquals(
        StvnPrelude.PRELUDE_STVN_D,
        StvnSourceFormatter.format(StvnPrelude.PRELUDE_STVN_D),
        "StvnPrelude.PRELUDE_STVN_D must remain a strict fixed point of StvnSourceFormatter"
    );

    // Project StvnPrelude.PRELUDE_STVN_D into a non-reserved user package namespace so
    // StvnReachabilityAndCasVerifier retains all exported definitions instead of eliding implicit prelude FQNIs.
    String mirroredPreludeSource = StvnPrelude.PRELUDE_STVN_D
        .replace("#domain \"stvn_core\"", "#domain \"core_test\"")
        .replace(":org/stvnadore/prelude", ":org/example/prelude");

    StvnCompilationResult<StvnDocument> compiled = StvnCompiler.compile(
        mirroredPreludeSource,
        "prelude.core_test.stvn_d",
        StvnParserConfig.DEFAULT
    );
    Assertions.assertFalse(compiled.hasErrors(), () -> "Mirrored Prelude compile errors: " + compiled.diagnostics());
    StvnDocument preludeDoc = compiled.document().orElseThrow();

    String pretty = AstPrettyPrinter.print(preludeDoc);
    Assertions.assertEquals(pretty, StvnSourceFormatter.format(pretty),
        "AstPrettyPrinter output on Prelude schemas must be a strict fixed point of StvnSourceFormatter");

    Pattern fencedRegexBlock = Pattern.compile("\"\"\"\\[REGEX]\\n[\\s\\S]*?\\[REGEX]\"\"\"");
    int fencedCount = 0;
    Matcher m = fencedRegexBlock.matcher(pretty);
    while (m.find()) {
      fencedCount++;
    }
    // In lowered IR, :EmailLowerCase and :EmailMixedCase also carry their resolved StringSchema.rawRegexPattern()
    // alongside #EMAIL_LOWER_CASE_REGEX and #EMAIL_MIXED_CASE_REGEX constants (4 + 2 = 6 fenced [REGEX] blocks, 0 [RAW]).
    Assertions.assertEquals(6, fencedCount,
        "Expected 6 fenced [REGEX] blocks in lowered Prelude IR (:SemVer, :IPv4, 2 #EMAIL_*_REGEX constants, 2 :Email* types):\n" + pretty);
    Assertions.assertFalse(pretty.contains("\"\"\"[RAW]"),
        "Prelude pretty output must not emit [RAW] for regex constants or facets:\n" + pretty);

    // Escape-free regexes must stay concise single-line simple strings
    Assertions.assertTrue(pretty.contains("#org/example/prelude/SHA256_LOWER_CASE_REGEX :String \"^[0-9a-f]{64}$\""),
        "Escape-free SHA256 regex constant must remain a single-line simple string:\n" + pretty);
    Assertions.assertTrue(pretty.contains("#org/example/prelude/POSIX_ID_REGEX :String \"^[a-zA-Z0-9_-]+$\""),
        "Escape-free POSIX_ID regex constant must remain a single-line simple string:\n" + pretty);
    Assertions.assertTrue(pretty.contains(":org/example/prelude/Ulid { #minSize 26 #maxSize 26 #regex \"^[0-7][0-9A-HJKMNP-TV-Z]{25}$\" } :String"),
        "Escape-free :Ulid type must remain on a single line:\n" + pretty);

    StvnDocument roundTripDoc = StvnCompiler.compile(
        pretty,
        "prelude.core_test.stvn_d",
        StvnParserConfig.DEFAULT
    ).document().orElseThrow();
    Assertions.assertEquals(pretty, AstPrettyPrinter.print(roundTripDoc),
        "AstPrettyPrinter must be idempotent on Prelude IR");
    Assertions.assertEquals(
        StvnCompiler.toCanonicalString(preludeDoc),
        StvnCompiler.toCanonicalString(roundTripDoc),
        "Canonical preimage of Prelude schemas must remain invariant after pretty round-trip"
    );
  }

  @Test
  @DisplayName("TC-BIAS-02: Collision-safe [REGEX_k] and [RAW_k] tag escalation across #CONST, bounds, #regex, :Map, and sum types")
  void testFenceTagCollisionEscalationAcrossAllConstructs() {
    String defsSource = """
        {
          :meta {
            #name "fence_collision_defs"
            #domain "core_test"
            #kind #DEFS
          }
          :defs {
            #PLAIN_BANNER :String "alpha [RAW]\\"\\"\\" and [RAW_1]\\"\\"\\" omega"
            #COLLIDING_REGEX :String "^(\\\\[REGEX]\\"\\"\\"|\\\\[REGEX_1]\\"\\"\\")+$"
            #SUFFIXREGEX :String "^a\\\\.b$"
            :package :acme/sec {
              #TOKEN_REGEX :String "^tok\\\\.[0-9]+$"
            }
            :BoundedCollision {
              #minSize 1
              #maxSize 128
              #minIncl "a[RAW]\\"\\"\\"0"
              #maxExcl "z[RAW]\\"\\"\\"9"
              #regex "^.*\\\\[REGEX]\\"\\"\\".*$"
            } :String
          }
        }
        """;

    StvnDocument defsDoc = StvnCompiler.compile(defsSource, "fence_collision_defs.stvn_d").document().orElseThrow();
    String prettyDefs = AstPrettyPrinter.print(defsDoc);

    Assertions.assertTrue(prettyDefs.contains("#PLAIN_BANNER :String \"\"\"[RAW_2]\n"),
        "Expected [RAW_2] escalation on #PLAIN_BANNER:\n" + prettyDefs);
    Assertions.assertTrue(prettyDefs.contains("#COLLIDING_REGEX :String \"\"\"[REGEX_2]\n"),
        "Expected [REGEX_2] escalation on #COLLIDING_REGEX:\n" + prettyDefs);
    Assertions.assertTrue(prettyDefs.contains("#SUFFIXREGEX :String \"\"\"[REGEX]\n"),
        "Expected [REGEX] tag on #SUFFIXREGEX:\n" + prettyDefs);
    Assertions.assertTrue(prettyDefs.contains("#acme/sec/TOKEN_REGEX :String \"\"\"[REGEX]\n"),
        "Expected [REGEX] tag on namespaced #acme/sec/TOKEN_REGEX:\n" + prettyDefs);
    Assertions.assertTrue(prettyDefs.contains("#minIncl \"\"\"[RAW_1]\n"),
        "Expected [RAW_1] escalation on #minIncl bound:\n" + prettyDefs);
    Assertions.assertTrue(prettyDefs.contains("#maxExcl \"\"\"[RAW_1]\n"),
        "Expected [RAW_1] escalation on #maxExcl bound:\n" + prettyDefs);
    Assertions.assertTrue(prettyDefs.contains("#regex \"\"\"[REGEX_1]\n"),
        "Expected [REGEX_1] escalation on #regex facet:\n" + prettyDefs);
    Assertions.assertEquals(prettyDefs, StvnSourceFormatter.format(prettyDefs),
        "StvnSourceFormatter fixed point must hold on collision-escalated #DEFS");

    StvnDocument rtDefs = StvnCompiler.compile(prettyDefs, "fence_collision_defs.stvn_d").document().orElseThrow();
    Assertions.assertEquals(StvnCompiler.toCanonicalString(defsDoc), StvnCompiler.toCanonicalString(rtDefs));

    // Verify :Map fenced keys/values and :Option / :Either / :Union sum branches
    String payloadSource = """
        {
          :defs {
            :Composite :Tuple(
              :Map(:String :String)
              :Option(:String)
              :Either(:String :Int)
              :Union(:Int :String)
              :Set(:String)
            )
          }
          :type :Composite
          :body (
            {
              ["key\\\\one" "val\\"one\\""]
              ["plain_key" "has [RAW]\\"\\"\\" inside"]
            }
            "opt\\tvalue"
            #Left "left\\nbranch"
            "union\\\\branch"
            ["set\\\\elem" "plain_elem"]
          )
        }
        """;
    StvnDocument payloadDoc = StvnCompiler.compile(payloadSource).document().orElseThrow();
    String prettyPayload = AstPrettyPrinter.print(payloadDoc);
    Assertions.assertEquals(prettyPayload, StvnSourceFormatter.format(prettyPayload),
        "StvnSourceFormatter fixed point must hold on composite fenced payload:\n" + prettyPayload);
    StvnDocument rtPayload = StvnCompiler.compile(prettyPayload).document().orElseThrow();
    Assertions.assertEquals(payloadDoc.payload().orElseThrow(), rtPayload.payload().orElseThrow());
  }

  @Test
  @DisplayName("TC-BIAS-03: Exhaustive lossless round-trip and fallback soundness guard matrix across preserveIndent and indentWidths")
  void testExhaustiveRoundTripAndFallbackSoundnessMatrix() {
    List<String> decodedSamples = List.of(
        "",
        "   ",
        "\n",
        "\n\n",
        "a\\b",
        "say \"hi\"",
        "col1\tcol2",
        "\t  ",
        "  \t",
        "  foo\\bar",
        "  a\n  b",
        "a\n  \nb",
        "trailing\n",
        "multi\n\nblank\n",
        "[RAW]\"\"\"",
        "[RAW]\"\"\" and [RAW_1]\"\"\"",
        "cr\rhere",
        "crlf\r\nhere",
        "ctrl\b\f\u0000\u0007\u001fend"
    );

    StringBuilder sb = new StringBuilder();
    sb.append("{\n  :defs {\n    :KeepIndent { #preserveIndent #TRUE } :String\n");
    sb.append("    :Matrix :Tuple(:Seq(:String) :Seq(:KeepIndent))\n  }\n");
    sb.append("  :type :Matrix\n  :body (\n    [\n");
    for (String s : decodedSamples) {
      sb.append("      \"").append(PrettyLayoutWriterTestHelper.escapeForInput(s)).append("\"\n");
    }
    sb.append("    ]\n    [\n");
    for (String s : decodedSamples) {
      sb.append("      \"").append(PrettyLayoutWriterTestHelper.escapeForInput(s)).append("\"\n");
    }
    sb.append("    ]\n  )\n}\n");

    StvnDocument originalDoc = StvnCompiler.compile(sb.toString()).document().orElseThrow();
    String canonicalBaseline = StvnCompiler.toCanonicalString(originalDoc);

    for (int indentWidth : new int[]{0, 2, 4}) {
      AstPrettyPrinter printer = new AstPrettyPrinter(indentWidth);
      String pretty = printer.printToString(originalDoc);
      StvnCompilationResult<StvnDocument> rtRes = StvnCompiler.compile(pretty);
      Assertions.assertFalse(rtRes.hasErrors(),
          () -> "Round-trip compile failed at indentWidth=" + indentWidth + ": " + rtRes.diagnostics() + "\n" + pretty);
      StvnDocument rtDoc = rtRes.document().orElseThrow();

      Assertions.assertEquals(originalDoc.payload().orElseThrow(), rtDoc.payload().orElseThrow(),
          "Decoded IR payload must round-trip 100% losslessly at indentWidth=" + indentWidth);
      Assertions.assertEquals(pretty, printer.printToString(rtDoc),
          "AstPrettyPrinter must be idempotent at indentWidth=" + indentWidth);
      Assertions.assertEquals(canonicalBaseline, StvnCompiler.toCanonicalString(rtDoc),
          "Canonical preimage must remain invariant at indentWidth=" + indentWidth);
      if (indentWidth == 2) {
        Assertions.assertEquals(pretty, StvnSourceFormatter.format(pretty),
            "Default 2-space pretty output must be a strict fixed point of StvnSourceFormatter");
      }
    }

    String compact = AstCompactPrinter.print(originalDoc);
    StvnDocument rtCompact = StvnCompiler.compile(compact).document().orElseThrow();
    Assertions.assertEquals(originalDoc.payload().orElseThrow(), rtCompact.payload().orElseThrow(),
        "AstCompactPrinter must also round-trip all control-char fallback strings 100% losslessly");
  }

  @Test
  @DisplayName("TC-BIAS-04: Nested multi-line facet schema group wrapping parity and zero SHA-256 CAS hash drift")
  void testNestedMultiLineFacetSchemaWrappingAndCasInvariance() {
    String source = """
        {
          :defs {
            :NestedGroup :Tuple(:Seq({ #minSize 1 #maxSize 32 #regex "^a\\\\.b$" } :String) :Int)
            :NestedUnion :Union(:Option({ #minSize 1 #maxSize 32 #minIncl "a\\\\0" } :String) :Boolean)
          }
          :type :Tuple(:NestedGroup :NestedUnion)
          :body ((["a.b"] 7) #TRUE)
        }
        """;
    StvnDocument doc = StvnCompiler.compile(source).document().orElseThrow();
    byte[] casBefore = StvnCompiler.computeCasFingerprint(doc);
    byte[] schemaHashBefore = StvnSchemaHasher.computeSha256(doc.payload().orElseThrow().schema());

    String pretty = AstPrettyPrinter.print(doc);
    Assertions.assertTrue(pretty.contains(":NestedGroup :Tuple(\n"),
        ":NestedGroup must expand to multi-line :Tuple(...) when child :Seq contains multi-line metadataMap:\n" + pretty);
    Assertions.assertTrue(pretty.contains(":NestedUnion :Union(\n"),
        ":NestedUnion must expand to multi-line :Union(...) when child :Option contains multi-line metadataMap:\n" + pretty);
    Assertions.assertEquals(pretty, StvnSourceFormatter.format(pretty),
        "Nested multi-line facet schema group must be a strict fixed point of StvnSourceFormatter:\n" + pretty);

    StvnDocument rtDoc = StvnCompiler.compile(pretty).document().orElseThrow();
    Assertions.assertArrayEquals(casBefore, StvnCompiler.computeCasFingerprint(rtDoc),
        "Document SHA-256 CAS fingerprint must be bitwise identical");
    Assertions.assertArrayEquals(schemaHashBefore, StvnSchemaHasher.computeSha256(rtDoc.payload().orElseThrow().schema()),
        "StvnSchemaHasher SHA-256 digest must be bitwise identical");
  }

  private static final class PrettyLayoutWriterTestHelper {
    static String escapeForInput(String s) {
      StringBuilder out = new StringBuilder(s.length());
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        switch (c) {
          case '\\' -> out.append("\\\\");
          case '"' -> out.append("\\\"");
          case '\n' -> out.append("\\n");
          case '\r' -> out.append("\\r");
          case '\t' -> out.append("\\t");
          case '\b' -> out.append("\\b");
          case '\f' -> out.append("\\f");
          default -> {
            if (c < 0x20) {
              out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            } else {
              out.append(c);
            }
          }
        }
      }
      return out.toString();
    }
  }
}
