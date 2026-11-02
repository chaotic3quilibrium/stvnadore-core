package org.stvnadore.core.printer;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.ir.StvnDocument;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Cross-language Golden Formatter Conformance Suite (Spec § 3.3) verifying byte-for-byte
 * deterministic alignment between {@link StvnSourceFormatter} (Operation 1: Non-Destructive
 * Authoring Source Formatting), {@link AstPrettyPrinter} / {@link AstCompactPrinter}
 * (Operation 2: Canonical IR Projections), and the normative golden quartets under
 * {@code shared-fixtures/formatting/{input,source_pretty,pretty,compact}/}.
 */
@NullMarked
class StvnGoldenFormatterConformanceTest {

  private static final Path FORMATTING_ROOT = Paths.get("shared-fixtures", "formatting");
  private static final Path INPUT_DIR = FORMATTING_ROOT.resolve("input");
  private static final Path SOURCE_PRETTY_DIR = FORMATTING_ROOT.resolve("source_pretty");
  private static final Path PRETTY_DIR = FORMATTING_ROOT.resolve("pretty");
  private static final Path COMPACT_DIR = FORMATTING_ROOT.resolve("compact");

  private static final Pattern FENCED_STRING_PATTERN = Pattern.compile(
      "\"\"\"\\[([A-Za-z0-9_]+)]\\n[\\s\\S]*?\\[\\1]\"\"\""
  );

  @Test
  @DisplayName("Formatting directories contain matching file inventories across input/, source_pretty/, pretty/, and compact/")
  void testGoldenFormattingDirectoryInventory() throws IOException {
    Assertions.assertTrue(Files.isDirectory(INPUT_DIR), "Missing directory: " + INPUT_DIR);
    Assertions.assertTrue(Files.isDirectory(SOURCE_PRETTY_DIR), "Missing directory: " + SOURCE_PRETTY_DIR);
    Assertions.assertTrue(Files.isDirectory(PRETTY_DIR), "Missing directory: " + PRETTY_DIR);
    Assertions.assertTrue(Files.isDirectory(COMPACT_DIR), "Missing directory: " + COMPACT_DIR);

    Set<String> inputNames = listRegularFileNames(INPUT_DIR);
    Set<String> sourcePrettyNames = listRegularFileNames(SOURCE_PRETTY_DIR);
    Set<String> prettyNames = listRegularFileNames(PRETTY_DIR);
    Set<String> compactNames = listRegularFileNames(COMPACT_DIR);

    Assertions.assertFalse(inputNames.isEmpty(), "shared-fixtures/formatting/input must not be empty");
    Assertions.assertEquals(9, inputNames.size(), "Expected 9 golden formatter quartets");
    Assertions.assertEquals(inputNames, sourcePrettyNames, "input/ and source_pretty/ file inventories must match identically");
    Assertions.assertEquals(inputNames, prettyNames, "input/ and pretty/ file inventories must match identically");
    Assertions.assertEquals(inputNames, compactNames, "input/ and compact/ file inventories must match identically");
  }

  static Stream<Arguments> provideGoldenFormattingQuartets() throws IOException {
    List<Path> inputFiles;
    try (Stream<Path> stream = Files.list(INPUT_DIR)) {
      inputFiles = stream
          .filter(Files::isRegularFile)
          .sorted(Comparator.comparing(p -> p.getFileName().toString()))
          .toList();
    }
    return inputFiles.stream().map(inputPath -> {
      String fileName = inputPath.getFileName().toString();
      return Arguments.of(
          fileName,
          inputPath,
          SOURCE_PRETTY_DIR.resolve(fileName),
          PRETTY_DIR.resolve(fileName),
          COMPACT_DIR.resolve(fileName)
      );
    });
  }

  @ParameterizedTest(name = "Golden Formatter Quartet -> {0}")
  @MethodSource("provideGoldenFormattingQuartets")
  void testGoldenFormatterQuartetConformance(
      String fileName,
      Path inputPath,
      Path sourcePrettyPath,
      Path prettyPath,
      Path compactPath
  ) throws IOException {
    String inputSource = Files.readString(inputPath, StandardCharsets.UTF_8);
    String expectedSourcePretty = Files.readString(sourcePrettyPath, StandardCharsets.UTF_8);
    String expectedPretty = Files.readString(prettyPath, StandardCharsets.UTF_8);
    String expectedCompact = Files.readString(compactPath, StandardCharsets.UTF_8);

    // GATE-09: Strict LF (\n) line endings across all 36 golden files
    Assertions.assertFalse(inputSource.contains("\r"), "CRLF prohibited in " + inputPath);
    Assertions.assertFalse(expectedSourcePretty.contains("\r"), "CRLF prohibited in " + sourcePrettyPath);
    Assertions.assertFalse(expectedPretty.contains("\r"), "CRLF prohibited in " + prettyPath);
    Assertions.assertFalse(expectedCompact.contains("\r"), "CRLF prohibited in " + compactPath);

    // 1. Operation 1: Non-Destructive Authoring Source Formatting (input -> source_pretty)
    String formattedInput = StvnSourceFormatter.format(inputSource);
    String actualSourcePrettyFromInput = formattedInput + "\n";
    Assertions.assertEquals(
        expectedSourcePretty,
        actualSourcePrettyFromInput,
        () -> "StvnSourceFormatter mismatch against golden source_pretty/" + fileName
    );
    Assertions.assertEquals(
        Optional.of(formattedInput),
        StvnSourceFormatter.tryFormat(inputSource),
        () -> "StvnSourceFormatter.tryFormat(input) must equal Optional.of(format(input)) for " + fileName
    );

    // 2. Operation 1 Idempotency: format(source_pretty) == source_pretty
    String sourcePrettyIdempotent = StvnSourceFormatter.format(expectedSourcePretty) + "\n";
    Assertions.assertEquals(
        expectedSourcePretty,
        sourcePrettyIdempotent,
        () -> "StvnSourceFormatter must be idempotent on source_pretty/" + fileName
    );

    // 3. Fixed-Point Invariant: AstPrettyPrinter output (pretty) is a strict fixed point of StvnSourceFormatter
    String sourceFormattedPretty = StvnSourceFormatter.format(expectedPretty) + "\n";
    Assertions.assertEquals(
        expectedPretty,
        sourceFormattedPretty,
        () -> "Golden pretty/" + fileName + " must be a strict fixed point of StvnSourceFormatter"
    );

    // 4. Compile all 4 members of the quartet with 0 errors and 0 warnings
    StvnDocument inputDoc = compileCleanDocument(formattedInput, inputPath);
    StvnDocument sourcePrettyDoc = compileCleanDocument(expectedSourcePretty, sourcePrettyPath);
    StvnDocument prettyDoc = compileCleanDocument(expectedPretty, prettyPath);
    StvnDocument compactDoc = compileCleanDocument(expectedCompact, compactPath);

    // 5. Operation 2 Byte-for-byte golden equivalence (with trailing LF newline)
    String actualPrettyFromInput = AstPrettyPrinter.print(inputDoc) + "\n";
    String actualCompactFromInput = AstCompactPrinter.print(inputDoc) + "\n";
    String actualPrettyFromSourcePretty = AstPrettyPrinter.print(sourcePrettyDoc) + "\n";
    String actualCompactFromSourcePretty = AstCompactPrinter.print(sourcePrettyDoc) + "\n";

    Assertions.assertEquals(
        expectedPretty,
        actualPrettyFromInput,
        () -> "AstPrettyPrinter mismatch against golden pretty/" + fileName
    );
    Assertions.assertEquals(
        expectedCompact,
        actualCompactFromInput,
        () -> "AstCompactPrinter mismatch against golden compact/" + fileName
    );
    Assertions.assertEquals(
        expectedPretty,
        actualPrettyFromSourcePretty,
        () -> "AstPrettyPrinter(source_pretty) mismatch against golden pretty/" + fileName
    );
    Assertions.assertEquals(
        expectedCompact,
        actualCompactFromSourcePretty,
        () -> "AstCompactPrinter(source_pretty) mismatch against golden compact/" + fileName
    );

    // 6. Operation 2 Idempotency and cross-mode convergence
    String prettyIdempotent = AstPrettyPrinter.print(prettyDoc) + "\n";
    String compactIdempotent = AstCompactPrinter.print(compactDoc) + "\n";
    String prettyFromCompact = AstPrettyPrinter.print(compactDoc) + "\n";
    String compactFromPretty = AstCompactPrinter.print(prettyDoc) + "\n";

    Assertions.assertEquals(expectedPretty, prettyIdempotent, "Pretty printer must be idempotent: " + fileName);
    Assertions.assertEquals(expectedCompact, compactIdempotent, "Compact printer must be idempotent: " + fileName);
    Assertions.assertEquals(expectedPretty, prettyFromCompact, "Pretty(Compact(doc)) must converge to golden pretty: " + fileName);
    Assertions.assertEquals(expectedCompact, compactFromPretty, "Compact(Pretty(doc)) must converge to golden compact: " + fileName);

    // 7. Canonical text preimage and SHA-256 CAS fingerprint invariance across all 4 quartet files
    String canonicalInput = StvnCompiler.toCanonicalString(inputDoc);
    String canonicalSourcePretty = StvnCompiler.toCanonicalString(sourcePrettyDoc);
    String canonicalPretty = StvnCompiler.toCanonicalString(prettyDoc);
    String canonicalCompact = StvnCompiler.toCanonicalString(compactDoc);

    Assertions.assertEquals(canonicalInput, canonicalSourcePretty, "Canonical preimage drift between input and source_pretty: " + fileName);
    Assertions.assertEquals(canonicalInput, canonicalPretty, "Canonical preimage drift between input and pretty: " + fileName);
    Assertions.assertEquals(canonicalInput, canonicalCompact, "Canonical preimage drift between input and compact: " + fileName);

    byte[] casInput = StvnCompiler.computeCasFingerprint(inputDoc);
    byte[] casSourcePretty = StvnCompiler.computeCasFingerprint(sourcePrettyDoc);
    byte[] casPretty = StvnCompiler.computeCasFingerprint(prettyDoc);
    byte[] casCompact = StvnCompiler.computeCasFingerprint(compactDoc);

    Assertions.assertArrayEquals(casInput, casSourcePretty, "CAS SHA-256 drift between input and source_pretty: " + fileName);
    Assertions.assertArrayEquals(casInput, casPretty, "CAS SHA-256 drift between input and pretty: " + fileName);
    Assertions.assertArrayEquals(casInput, casCompact, "CAS SHA-256 drift between input and compact: " + fileName);

    // 8. Structural Zero-Tab Invariant outside fenced verbatim string bodies
    assertZeroStructuralTabsOutsideFencedBodies(actualSourcePrettyFromInput, "source_pretty/" + fileName);
    assertZeroStructuralTabsOutsideFencedBodies(actualPrettyFromInput, "pretty/" + fileName);
    assertZeroStructuralTabsOutsideFencedBodies(actualCompactFromInput, "compact/" + fileName);

    if ("07_fenced_verbatim_strings.stvn".equals(fileName)) {
      Assertions.assertTrue(
          actualSourcePrettyFromInput.contains("col1\tcol2\n  10\t20"),
          "SourcePretty output for 07_fenced_verbatim_strings.stvn must preserve raw tabs inside #preserveIndent #T fenced string"
      );
      Assertions.assertTrue(
          actualPrettyFromInput.contains("col1\tcol2\n  10\t20"),
          "Pretty output for 07_fenced_verbatim_strings.stvn must preserve raw tabs inside #preserveIndent #TRUE fenced string"
      );
      Assertions.assertTrue(
          actualCompactFromInput.contains("col1\tcol2\n  10\t20"),
          "Compact output for 07_fenced_verbatim_strings.stvn must preserve raw tabs inside #preserveIndent #T fenced string"
      );
    }
  }

  private static StvnDocument compileCleanDocument(String source, Path path) {
    StvnCompilationResult<StvnDocument> result = StvnCompiler.compile(
        source,
        path.toString(),
        StvnParserConfig.DEFAULT
    );
    Assertions.assertFalse(
        result.hasErrors(),
        () -> "Unexpected compilation errors in " + path + ": " + result.diagnostics()
    );
    Assertions.assertTrue(
        result.diagnostics().isEmpty(),
        () -> "Expected 0 warnings and 0 errors in " + path + ", found: " + result.diagnostics()
    );
    return result.document().orElseThrow();
  }

  private static Set<String> listRegularFileNames(Path directory) throws IOException {
    try (Stream<Path> stream = Files.list(directory)) {
      return stream
          .filter(Files::isRegularFile)
          .map(p -> p.getFileName().toString())
          .collect(Collectors.toSet());
    }
  }

  private static void assertZeroStructuralTabsOutsideFencedBodies(String text, String label) {
    String stripped = FENCED_STRING_PATTERN.matcher(text).replaceAll("\"\"\"[$1]\\n[$1]\"\"\"");
    Assertions.assertFalse(
        stripped.contains("\t"),
        () -> "Structural tab character ('\\t') detected outside fenced string body in " + label + ":\n" + stripped
    );
  }
}
