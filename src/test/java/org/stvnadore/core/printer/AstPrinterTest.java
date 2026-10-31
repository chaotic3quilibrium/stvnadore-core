package org.stvnadore.core.printer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.io.CanonicalStvnWriter;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnValue;

import java.io.StringWriter;
import java.util.Map;
import java.util.Optional;

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

    for (String printed : new String[]{
        StvnCompiler.toCanonicalString(doc),
        AstPrettyPrinter.print(doc),
        AstCompactPrinter.print(doc)
    }) {
      Assertions.assertTrue(printed.contains("\"2026-03-15T08:00:00Z\""), "Must preserve :00 seconds:\n" + printed);
      Assertions.assertTrue(printed.contains("\"2026-03-15T08:00:00.123Z\""), "Must trim trailing zero nanos:\n" + printed);
      Assertions.assertTrue(printed.contains("\"col1\\tcol2\""), "Simple quoted string must escape tab as \\t:\n" + printed);

      StvnDocument rtDoc = StvnCompiler.compile(printed).document().orElseThrow();
      Assertions.assertEquals(doc.payload().orElseThrow(), rtDoc.payload().orElseThrow(),
          "Round-trip payload must preserve tabs and :00 seconds identically:\n" + printed);
    }
  }
}
