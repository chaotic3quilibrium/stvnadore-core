package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.StvnStringCharacterPolicy;
import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.binary.StvnBinaryEncoder;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.lowering.StvnDefsLowerer;
import org.stvnadore.core.printer.StvnSourceFormatter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conformance suite verifying Pass 1 raw source character hygiene ({@code E001}),
 * permitted {@code U+200C}/{@code U+200D} emoji joiners, and UTF-8 scalar totality
 * ({@code E001} on unpaired UTF-16 surrogates).
 */
@NullMarked
class StvnRawSourceHygieneAndSurrogateValidationTest {

  @Test
  @DisplayName("Offset-0 UTF-8 BOM (U+FEFF) fails in Pass 1 with E001 pinned to [0, 1) and zero E002 cascade")
  void testOffsetZeroBomRejectedWithExactSpan() {
    var source = "\uFEFF{\n  :type :Int\n  :body 42\n}";
    var res = StvnCompiler.compileToResult(source);
    assertTrue(res.hasErrors());
    var errors = res.diagnostics().stream()
        .filter(d -> d.severity() == DiagnosticSeverity.ERROR)
        .toList();
    assertEquals(1, errors.size(), "Expected single E001 without cascading root-brace E002: " + errors);
    var diag = errors.getFirst();
    assertEquals("E001", diag.canonicalCode().orElseThrow());
    assertEquals(StvnStringCharacterPolicy.ERR_FORBIDDEN_RAW_CHARACTER, diag.symbolicId().orElseThrow());
    assertEquals(0, diag.startOffset());
    assertEquals(1, diag.endOffset());
    assertEquals(1, diag.line());
    assertEquals(0, diag.column());
    assertTrue(diag.message().contains("U+FEFF"));
    assertTrue(diag.message().contains("ZERO WIDTH NO-BREAK SPACE"));
  }

  @Test
  @DisplayName("Raw C0, DEL, C1, Bidi (Trojan Source), and invisible scalars fail with E001 across comments, structural whitespace, simple strings, and fenced strings")
  void testForbiddenRawScalarsAcrossAllContexts() {
    // 1. Trojan Source RLO (U+202E) inside '//' comment
    var commentSource = "{\n  // comment \u202E reversed\n  :type :String\n  :body \"ok\"\n}";
    var commentIdx = commentSource.indexOf('\u202E');
    var commentRes = StvnCompiler.compileToResult(commentSource);
    assertTrue(commentRes.hasErrors());
    var commentDiag = commentRes.diagnostics().getFirst();
    assertEquals("E001", commentDiag.canonicalCode().orElseThrow());
    assertEquals(DiagnosticBag.ERR_FORBIDDEN_RAW_CHARACTER, commentDiag.symbolicId().orElseThrow());
    assertEquals(commentIdx, commentDiag.startOffset());
    assertEquals(commentIdx + 1, commentDiag.endOffset());
    assertTrue(commentDiag.message().contains("U+202E (RIGHT-TO-LEFT OVERRIDE)"));
    assertTrue(commentDiag.message().contains("\\u202E"));

    // 2. Raw ZWSP (U+200B) inside structural whitespace
    var wsSource = "{\u200B :type :Int :body 7 }";
    var wsRes = StvnCompiler.compileToResult(wsSource);
    assertTrue(wsRes.hasErrors());
    assertEquals(1, wsRes.diagnostics().size());
    assertEquals(1, wsRes.diagnostics().getFirst().startOffset());
    assertEquals(2, wsRes.diagnostics().getFirst().endOffset());
    assertTrue(wsRes.diagnostics().getFirst().message().contains("U+200B (ZERO WIDTH SPACE)"));

    // 3. Raw forbidden character inside simple string suppresses secondary #regex/#minSize cascades
    var simpleSource = """
        {
          :defs {
            :StrictPin { #minSize 10 #maxSize 10 #regex "^[0-9]{10}$" } :String
          }
          :type :StrictPin
          :body "12\u202E34"
        }
        """;
    var simpleIdx = simpleSource.indexOf('\u202E');
    var simpleRes = StvnCompiler.compileToResult(simpleSource);
    assertTrue(simpleRes.hasErrors());
    var simpleErrors = simpleRes.diagnostics().stream()
        .filter(d -> d.severity() == DiagnosticSeverity.ERROR)
        .toList();
    assertEquals(1, simpleErrors.size(), "Expected single deduplicated E001 with zero E012/E013 cascade: " + simpleErrors);
    assertEquals("E001", simpleErrors.getFirst().canonicalCode().orElseThrow());
    assertEquals(DiagnosticBag.ERR_FORBIDDEN_RAW_CHARACTER, simpleErrors.getFirst().symbolicId().orElseThrow());
    assertEquals(simpleIdx, simpleErrors.getFirst().startOffset());
    assertEquals(simpleIdx + 1, simpleErrors.getFirst().endOffset());

    // 4. Raw forbidden character inside fenced verbatim string body
    var fencedSource = "{\n  :type :String\n  :body \"\"\"[RAW]\nalpha\nbeta\u0085gamma\n[RAW]\"\"\"\n}";
    var fencedIdx = fencedSource.indexOf('\u0085');
    var fencedRes = StvnCompiler.compileToResult(fencedSource);
    assertTrue(fencedRes.hasErrors());
    var fencedErrors = fencedRes.diagnostics().stream()
        .filter(d -> d.severity() == DiagnosticSeverity.ERROR)
        .toList();
    assertEquals(1, fencedErrors.size(), "Expected single deduplicated E001 on fenced body: " + fencedErrors);
    assertEquals("E001", fencedErrors.getFirst().canonicalCode().orElseThrow());
    assertEquals(fencedIdx, fencedErrors.getFirst().startOffset());
    assertEquals(fencedIdx + 1, fencedErrors.getFirst().endOffset());
    assertTrue(fencedErrors.getFirst().message().contains("U+0085 (NEXT LINE)"));
  }

  @Test
  @DisplayName("Secondary lexer entry points (:include virtual/disk, parseDocument, StvnSchemaFlattener, StvnSourceFormatter) enforce Pass 1 E001 hygiene")
  void testSecondaryEntryPointsEnforceHygiene(@TempDir Path tempDir) throws IOException {
    var poisonedInclude = "{\n  :meta { #name \"child\" #domain \"mod\" #kind #DEFS_INCLUDE }\n  :defs {\n    // hidden \u2066 bidi\n    :ChildStr :String\n  }\n}";
    var childPath = tempDir.resolve("child.mod.stvn_di");
    Files.writeString(childPath, poisonedInclude, StandardCharsets.UTF_8);

    var rootSource = """
        {
          :meta {
            #name "root"
            #domain "mod"
            #kind #BODY_INCLUDE
          }
          :defs {
            :include [ "child.mod.stvn_di" ]
          }
          :type :ChildStr
          :body "clean"
        }
        """;
    var rootPath = tempDir.resolve("root.mod.stvn_i");
    var diskRes = StvnCompiler.compileToResult(rootSource, rootPath.toString(), StvnParserConfig.DEFAULT);
    assertTrue(diskRes.hasErrors());
    assertTrue(diskRes.diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_FORBIDDEN_RAW_CHARACTER.equals(d.symbolicId().orElse(""))));

    // Flattener virtual workspace entry point
    assertThrows(MalformedSchemaException.class, () -> StvnSchemaFlattener.flatten(
        Map.of(
            "root.mod.stvn_di", "{\n  :meta { #name \"root\" #domain \"mod\" #kind #DEFS_INCLUDE }\n  :defs {\n    \u200B:T :Int\n  }\n}"
        ),
        "root.mod.stvn_di"
    ));

    // StvnDefsLowerer.parseDocument strict mode
    assertThrows(StvnSyntaxCancellationException.class,
        () -> StvnDefsLowerer.parseDocument("{\u200B :type :Int :body 1 }", StvnParserConfig.STRICT));

    // StvnSourceFormatter
    assertThrows(StvnSyntaxCancellationException.class,
        () -> StvnSourceFormatter.format("{\u202E :type :Int :body 1 }"));
  }

  @Test
  @DisplayName("Permitted emoji joiners U+200C (ZWNJ) and U+200D (ZWJ) compile and format cleanly across strings and comments")
  void testPermittedEmojiJoinersInStringsAndComments() {
    var source = """
        {
          // Technologist emoji 👨‍💻 and rainbow flag 🏳️‍🌈 in comment
          :defs {
            :EmojiStr { #minSize 3 #maxSize 3 } :String
          }
          :type :Tuple(:EmojiStr :String)
          :body (
            "👨‍💻"
            ""\"[RAW]
          👨‍💻\u200Cok
          [RAW]""\"
          )
        }
        """;
    var res = StvnCompiler.compileToResult(source);
    assertTrue(res.isSuccess(), () -> "Expected clean emoji compilation, got: " + res.diagnostics());
    assertFalse(res.hasErrors());

    var formatted = StvnSourceFormatter.format(source);
    var recompiled = StvnCompiler.compileToResult(formatted);
    assertTrue(recompiled.isSuccess(), () -> "Formatted emoji source failed: " + recompiled.diagnostics());
  }

  @Test
  @DisplayName("Paired UTF-16 surrogate escapes decode to supplementary USV, while unpaired escapes and raw lone surrogates fail with E001")
  void testSurrogatePairUsvValidationAndErrorPinning() {
    // 1. Valid surrogate pair \uD83D\uDE80 -> U+1F680 ("🚀", 1 Unicode scalar)
    var validPairSource = """
        {
          :defs {
            :SingleScalar { #minSize 1 #maxSize 1 } :String
          }
          :type :SingleScalar
          :body "\\uD83D\\uDE80"
        }
        """;
    var validRes = StvnCompiler.compileToResult(validPairSource);
    assertTrue(validRes.isSuccess(), () -> "Valid surrogate pair failed: " + validRes.diagnostics());
    var doc = validRes.orElseThrow();
    var strVal = (StvnValue.StvnString) doc.requirePayload();
    assertEquals("🚀", strVal.value());
    assertEquals(1, strVal.value().codePointCount(0, strVal.value().length()));

    var encoded = new StvnBinaryEncoder(true, null).encode(doc);
    var decoded = StvnBinaryDecoder.decodeDocument(encoded, doc.rootType().orElseThrow()).requirePayload();
    assertEquals("🚀", ((StvnValue.StvnString) decoded).value());

    // 2. Unpaired high surrogate followed by valid/invalid escapes in single left-to-right pass
    var multiErrSource = "{\n  :type :String\n  :body \"\\uD83D\\z\\uDE80\"\n}";
    var highIdx = multiErrSource.indexOf("\\uD83D");
    var badEscIdx = multiErrSource.indexOf("\\z");
    var lowIdx = multiErrSource.indexOf("\\uDE80");
    var multiRes = StvnCompiler.compileToResult(multiErrSource);
    assertTrue(multiRes.hasErrors());
    var errs = multiRes.diagnostics().stream()
        .filter(d -> d.severity() == DiagnosticSeverity.ERROR)
        .toList();
    assertEquals(3, errs.size(), "Expected high E001 + \\z E002 + low E001: " + errs);
    assertDiagnosticSpanAndCode(errs.get(0), "E001", DiagnosticBag.ERR_UNPAIRED_SURROGATE, highIdx, highIdx + 6);
    assertDiagnosticSpanAndCode(errs.get(1), "E002", "STVN_SYNTAX_ERROR", badEscIdx, badEscIdx + 2);
    assertDiagnosticSpanAndCode(errs.get(2), "E001", DiagnosticBag.ERR_UNPAIRED_SURROGATE, lowIdx, lowIdx + 6);

    // 3. Raw lone surrogate in in-memory Java String does not crash ANTLR CharStreams and emits single E001
    var rawLoneSource = "{\n  :meta {\n    #name \"bad\uD800name\"\n    #domain \"core\"\n    #kind #BODY\n  }\n  :type :Int\n  :body 1\n}";
    var rawLoneIdx = rawLoneSource.indexOf('\uD800');
    var rawLoneRes = StvnCompiler.compileToResult(rawLoneSource, "badname.core.stvn", StvnParserConfig.DEFAULT);
    assertTrue(rawLoneRes.hasErrors());
    var loneErrs = rawLoneRes.diagnostics().stream()
        .filter(d -> d.severity() == DiagnosticSeverity.ERROR)
        .toList();
    assertEquals(1, loneErrs.size(), "Expected single E001 without #name cascade: " + loneErrs);
    assertDiagnosticSpanAndCode(loneErrs.getFirst(), "E001", DiagnosticBag.ERR_UNPAIRED_SURROGATE, rawLoneIdx, rawLoneIdx + 1);

    // 4. Untagged :Union(:Int :String) replays E001 ERR_UNPAIRED_SURROGATE instead of E028
    var unionSource = "{\n  :type :Union(:Int :String)\n  :body \"\\uDEAD\"\n}";
    var unionEscIdx = unionSource.indexOf("\\uDEAD");
    var unionRes = StvnCompiler.compileToResult(unionSource);
    assertTrue(unionRes.hasErrors());
    assertDiagnosticSpanAndCode(
        unionRes.diagnostics().getFirst(),
        "E001",
        DiagnosticBag.ERR_UNPAIRED_SURROGATE,
        unionEscIdx,
        unionEscIdx + 6
    );
  }

  private static void assertDiagnosticSpanAndCode(
      StvnDiagnostic diag,
      String expectedCanonical,
      String expectedSymbolic,
      int expectedStart,
      int expectedEnd
  ) {
    assertEquals(expectedCanonical, diag.canonicalCode().orElseThrow(), () -> "Wrong canonical code: " + diag);
    assertEquals(expectedSymbolic, diag.symbolicId().orElseThrow(), () -> "Wrong symbolic ID: " + diag);
    assertEquals(expectedStart, diag.startOffset(), () -> "Wrong startOffset: " + diag);
    assertEquals(expectedEnd, diag.endOffset(), () -> "Wrong endOffset: " + diag);
  }
}
