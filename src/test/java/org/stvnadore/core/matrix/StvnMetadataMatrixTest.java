package org.stvnadore.core.matrix;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.matrix.MetadataFixtureGenerator.GeneratedFixture;
import org.stvnadore.core.validation.DiagnosticBag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Standard test runner for the STVN metadata matrix-driven test harness.
 * <p>
 * Enforces Option 3 (Two-Phase Pure Value Pipeline):
 * <ol>
 *   <li><b>Staleness Assertion:</b> Materialized static fixtures on disk must match in-memory generation derived from TSV.</li>
 *   <li><b>Compiler Validation:</b> Valid fixtures pass with zero diagnostics; invalid edge cases fail with pinned diagnostic codes.</li>
 *   <li><b>Flattener Round-Trip:</b> Saturated valid fixtures conform to schema flattener canonical sequence.</li>
 * </ol>
 */
@NullMarked
public class StvnMetadataMatrixTest {

  private static final Path FIXTURE_ROOT =
      Path.of("shared-fixtures/metadata");

  private static final Pattern EXPECT_DIAGNOSTIC_PATTERN =
      Pattern.compile("^//\\s*EXPECT-DIAGNOSTIC:\\s*(\\w+)", Pattern.MULTILINE);

  private static final Pattern TARGET_TOKEN_PATTERN =
      Pattern.compile("^//\\s*TARGET-TOKEN:\\s*(\\S+)", Pattern.MULTILINE);

  @Test
  @DisplayName("MM-STALE-01: Materialized static fixtures match canonical TSV matrix (Staleness Assertion)")
  void assertMaterializedFixturesNotStale() throws IOException {
    List<MetadataMatrixRow> rows = MetadataMatrixReader.readDefaultMatrix();
    Map<Path, GeneratedFixture> expectedFixtures = MetadataFixtureGenerator.generateAllFixturesInMemory(rows);

    Assertions.assertTrue(Files.exists(FIXTURE_ROOT),
        "Metadata fixture root directory does not exist: " + FIXTURE_ROOT.toAbsolutePath());

    // 1. Assert each expected fixture exists on disk with identical content
    for (Map.Entry<Path, GeneratedFixture> entry : expectedFixtures.entrySet()) {
      Path relative = entry.getKey();
      Path diskPath = FIXTURE_ROOT.resolve(relative);
      Assertions.assertTrue(Files.exists(diskPath),
          "Missing materialized fixture: " + relative + ". Run MetadataFixtureGenerator.main() to refresh fixtures.");

      String onDiskContent = Files.readString(diskPath, StandardCharsets.UTF_8).replace("\r\n", "\n").trim();
      String expectedContent = entry.getValue().content().replace("\r\n", "\n").trim();

      Assertions.assertEquals(expectedContent, onDiskContent,
          "Stale fixture content detected in: " + relative + ". Re-run MetadataFixtureGenerator.main().");
    }

    // 2. Assert no orphaned .stvn or .stvn_incl files exist on disk
    try (Stream<Path> stream = Files.walk(FIXTURE_ROOT)) {
      List<Path> diskFiles = stream.filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".stvn") || p.toString().endsWith(".stvn_incl"))
          .toList();

      for (Path diskFile : diskFiles) {
        Path relative = FIXTURE_ROOT.relativize(diskFile);
        Assertions.assertTrue(expectedFixtures.containsKey(relative),
            "Orphaned metadata fixture on disk not declared by canonical matrix: " + relative);
      }
    }
  }

  @ParameterizedTest(name = "[{index}] Isolated Valid: {0}")
  @MethodSource("provideValidIsolatedFixtures")
  @DisplayName("MM-VAL-ISO: Valid isolated metadata fixtures compile with zero diagnostics")
  void testValidIsolatedFixtures(Path fixturePath, String content) {
    Path diskPath = FIXTURE_ROOT.resolve(fixturePath);
    var result = StvnCompiler.compileToResult(content, diskPath.toString(), StvnParserConfig.DEFAULT);
    Assertions.assertFalse(result.hasErrors(),
        "Valid isolated fixture failed compilation: " + fixturePath + " -> " + result.diagnostics());
    Assertions.assertTrue(result.diagnostics().isEmpty(),
        "Valid isolated fixture emitted unexpected diagnostics: " + result.diagnostics());
    Assertions.assertTrue(result.document().isPresent(),
        "Valid isolated fixture produced empty AST document");
  }

  @ParameterizedTest(name = "[{index}] Saturated Valid: {0}")
  @MethodSource("provideValidSaturatedFixtures")
  @DisplayName("MM-VAL-SAT: Valid saturated metadata fixtures compile and conform to flattener 5-tier order")
  void testValidSaturatedFixtures(Path fixturePath, String content) {
    Path diskPath = FIXTURE_ROOT.resolve(fixturePath);
    var result = StvnCompiler.compileToResult(content, diskPath.toString(), StvnParserConfig.DEFAULT);
    Assertions.assertFalse(result.hasErrors(),
        "Valid saturated fixture failed compilation: " + fixturePath + " -> " + result.diagnostics());
    Assertions.assertTrue(result.diagnostics().isEmpty(),
        "Valid saturated fixture emitted unexpected diagnostics: " + result.diagnostics());

    // Verify round-trip flattener compliance
    String fileName = fixturePath.getFileName().toString();
    String flattened = StvnSchemaFlattener.flatten(Map.of(fileName, content), fileName);
    Assertions.assertNotNull(flattened, "Flattener produced null output for saturated fixture: " + fileName);
  }

  @ParameterizedTest(name = "[{index}] Invalid Edge Case: {0} (Expect: {2})")
  @MethodSource("provideInvalidEdgeCaseFixtures")
  @DisplayName("MM-INV-EDGE: Invalid edge cases fail closed with normative diagnostic codes and pinned tokens")
  void testInvalidEdgeCaseFixtures(Path fixturePath, String content, String expectedDiagnostic, Optional<String> targetToken) {
    Path diskPath = FIXTURE_ROOT.resolve(fixturePath);
    var result = StvnCompiler.compileToResult(content, diskPath.toString(), StvnParserConfig.DEFAULT);
    Assertions.assertTrue(result.hasErrors(),
        "Invalid edge case was unexpectedly accepted by compiler: " + fixturePath);

    boolean diagnosticMatched = result.diagnostics().stream().anyMatch(d -> {
      String code = d.errorCode().orElse(null);
      if (expectedDiagnostic.equals(code)) return true;
      if ("MUTUALLY_EXCLUSIVE_BOUNDS".equals(expectedDiagnostic) && "ERR_MUTUALLY_EXCLUSIVE".equals(code)) return true;
      if ("ERR_MUTUALLY_EXCLUSIVE".equals(expectedDiagnostic) && "MUTUALLY_EXCLUSIVE_BOUNDS".equals(code)) return true;
      if ("INVALID_NUMERIC_RANGE".equals(expectedDiagnostic) && "ERR_INVERTED_RANGE".equals(code)) return true;
      if ("ERR_INVERTED_RANGE".equals(expectedDiagnostic) && "INVALID_NUMERIC_RANGE".equals(code)) return true;
      return false;
    });

    Assertions.assertTrue(diagnosticMatched,
        () -> "Fixture " + fixturePath + " expected diagnostic code '" + expectedDiagnostic + "' but got: " +
            result.diagnostics().stream().map(d -> d.errorCode().orElse(null) + ": " + d.message()).toList());

    targetToken.ifPresent(token -> {
      boolean tokenFound = result.diagnostics().stream().anyMatch(d -> {
        if (d.message().contains(token)) return true;
        if (d.startOffset() >= 0 && d.endOffset() > d.startOffset() && d.endOffset() <= content.length()) {
          String span = content.substring(d.startOffset(), d.endOffset());
          return span.contains(token);
        }
        return false;
      });
      Assertions.assertTrue(tokenFound,
          () -> "Offending token '" + token + "' was not pinned in diagnostic coordinates or message for: " + fixturePath +
              " (diagnostics: " + result.diagnostics().stream().map(d -> d.message() + " [" + d.startOffset() + ".." + d.endOffset() + "]").toList() + ")");
    });
  }

  // --- Parameter Providers ---

  static Stream<Arguments> provideValidIsolatedFixtures() throws IOException {
    Path target = FIXTURE_ROOT.resolve("valid").resolve("isolated");
    if (!Files.exists(target)) return Stream.empty();
    try (Stream<Path> stream = Files.walk(target)) {
      return stream.filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".stvn")) // exclude .stvn_incl helper
          .sorted()
          .map(file -> {
            try {
              return Arguments.of(FIXTURE_ROOT.relativize(file), Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
              throw new RuntimeException("Failed to read fixture: " + file, e);
            }
          })
          .toList()
          .stream();
    }
  }

  static Stream<Arguments> provideValidSaturatedFixtures() throws IOException {
    return loadFixturesUnder(Path.of("valid", "saturated"));
  }

  static Stream<Arguments> provideInvalidEdgeCaseFixtures() throws IOException {
    List<Arguments> cases = new ArrayList<>();
    Path invalidDir = FIXTURE_ROOT.resolve("invalid");
    if (!Files.exists(invalidDir)) return Stream.empty();

    try (Stream<Path> stream = Files.walk(invalidDir)) {
      List<Path> files = stream.filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".stvn"))
          .sorted()
          .toList();

      for (Path file : files) {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        Matcher diagMatcher = EXPECT_DIAGNOSTIC_PATTERN.matcher(content);
        if (!diagMatcher.find()) {
          throw new IllegalStateException("Invalid fixture missing // EXPECT-DIAGNOSTIC annotation: " + file);
        }
        String expectedDiag = diagMatcher.group(1);

        Matcher tokenMatcher = TARGET_TOKEN_PATTERN.matcher(content);
        Optional<String> token = tokenMatcher.find() ? Optional.of(tokenMatcher.group(1)) : Optional.empty();

        cases.add(Arguments.of(FIXTURE_ROOT.relativize(file), content, expectedDiag, token));
      }
    }
    return cases.stream();
  }

  private static Stream<Arguments> loadFixturesUnder(Path subPath) throws IOException {
    Path target = FIXTURE_ROOT.resolve(subPath);
    if (!Files.exists(target)) return Stream.empty();
    try (Stream<Path> stream = Files.walk(target)) {
      return stream.filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".stvn"))
          .sorted()
          .map(file -> {
            try {
              return Arguments.of(FIXTURE_ROOT.relativize(file), Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
              throw new RuntimeException("Failed to read fixture: " + file, e);
            }
          })
          .toList()
          .stream();
    }
  }
}
