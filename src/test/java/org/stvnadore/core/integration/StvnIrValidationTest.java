package org.stvnadore.core.integration;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.ir.StvnValue.EitherBranch;
import org.stvnadore.core.ir.StvnValue.StvnEither;
import org.stvnadore.core.ir.StvnValue.StvnEnum;
import org.stvnadore.core.ir.StvnValue.StvnInt;
import org.stvnadore.core.ir.StvnValue.StvnOption;
import org.stvnadore.core.ir.StvnValue.StvnSeq;
import org.stvnadore.core.ir.StvnValue.StvnString;
import org.stvnadore.core.ir.StvnValue.StvnTuple;
import org.stvnadore.core.matrix.MetadataFixtureGenerator;
import org.stvnadore.core.validation.CyclicDependencyException;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.core.validation.MalformedPayloadException;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Unified GATE-07 &amp; GATE-09 verification runner discovering and executing 100% of
 * {@code shared-fixtures/syntax/valid/**}, {@code shared-fixtures/syntax/invalid/**},
 * {@code shared-fixtures/metadata/valid/**}, and {@code shared-fixtures/metadata/invalid/**}.
 */
@NullMarked
public class StvnIrValidationTest {

  private static final Path SHARED_FIXTURES_ROOT = Paths.get("shared-fixtures");
  private static final Path SYNTAX_VALID_ROOT = SHARED_FIXTURES_ROOT.resolve("syntax/valid");
  private static final Path SYNTAX_INVALID_ROOT = SHARED_FIXTURES_ROOT.resolve("syntax/invalid");
  private static final Path METADATA_VALID_ROOT = SHARED_FIXTURES_ROOT.resolve("metadata/valid");
  private static final Path METADATA_INVALID_ROOT = SHARED_FIXTURES_ROOT.resolve("metadata/invalid");
  private static final Path ERROR_CONTRACT_SCHEMA_PATH = SYNTAX_INVALID_ROOT.resolve("error_contract.stvn_d");

  private static final List<Path> POSITIVE_ROOTS = List.of(SYNTAX_VALID_ROOT, METADATA_VALID_ROOT);
  private static final List<Path> NEGATIVE_ROOTS = List.of(SYNTAX_INVALID_ROOT, METADATA_INVALID_ROOT);

  private static final Pattern FORBIDDEN_ANTLR_JARGON = Pattern.compile(
      "(?i)(token recognition error|no viable alternative|mismatched input|extraneous input)"
  );

  private static final boolean UPDATE_MODE = Boolean.getBoolean("updateSnapshots");

  public record ValidationTestCase(
      String name,
      String input,
      boolean isSuccess,
      @Nullable Consumer<StvnValue> irVerifier,
      @Nullable String expectedError
  ) {
    public static ValidationTestCase success(String name, String input, Consumer<StvnValue> irVerifier) {
      return new ValidationTestCase(name, input, true, irVerifier, null);
    }

    public static ValidationTestCase failure(String name, String input, String expectedError) {
      return new ValidationTestCase(name, input, false, null, expectedError);
    }
  }

  public record DynamicNegativeTestCase(
      String displayName,
      Path fixturePath,
      Path contractPath
  ) {}

  public record DiagnosticExpectation(
      String canonicalCode,
      String symbolicId,
      String severity,
      int line,
      int column,
      int startOffset,
      int endOffset,
      String messageSubstring
  ) {
    public static List<DiagnosticExpectation> fromErrorContractIr(StvnValue rootValue) {
      if (!(rootValue instanceof StvnSeq seq) || seq.elements().isEmpty()) {
        throw new IllegalArgumentException(":ErrorContract root must be a non-empty StvnSeq: " + rootValue);
      }
      List<DiagnosticExpectation> list = new ArrayList<>(seq.elements().size());
      for (StvnValue elem : seq.elements()) {
        if (!(elem instanceof StvnTuple tuple) || tuple.elements().size() != 8) {
          throw new IllegalArgumentException(":DiagnosticExpectation must be an 8-element StvnTuple: " + elem);
        }
        String canonicalCode = stripLeadingHash(((StvnEnum) tuple.elements().get(0)).variantTag());
        String symbolicId = stripLeadingHash(((StvnEnum) tuple.elements().get(1)).variantTag());
        String severity = stripLeadingHash(((StvnEnum) tuple.elements().get(2)).variantTag());
        int line = ((StvnInt) tuple.elements().get(3)).value().intValueExact();
        int column = ((StvnInt) tuple.elements().get(4)).value().intValueExact();
        int startOffset = ((StvnInt) tuple.elements().get(5)).value().intValueExact();
        int endOffset = ((StvnInt) tuple.elements().get(6)).value().intValueExact();
        String messageSubstring = ((StvnString) tuple.elements().get(7)).value();
        list.add(new DiagnosticExpectation(
            canonicalCode,
            symbolicId,
            severity,
            line,
            column,
            startOffset,
            endOffset,
            messageSubstring
        ));
      }
      return List.copyOf(list);
    }

    private static String stripLeadingHash(String tag) {
      return tag.startsWith("#") ? tag.substring(1) : tag;
    }
  }

  @Test
  @DisplayName("GATE-07 & GATE-09: shared-fixtures directory hygiene, deletion of invalid-syntax, zero .contract.stvn, and LF line endings")
  public void testSharedFixturesDirectoryCoverageAndHygiene() throws IOException {
    Assertions.assertTrue(Files.isDirectory(SHARED_FIXTURES_ROOT), "shared-fixtures root must exist");
    Assertions.assertTrue(
        Files.notExists(SHARED_FIXTURES_ROOT.resolve("invalid-syntax")),
        "Dead duplicate directory shared-fixtures/invalid-syntax must be permanently deleted"
    );

    try (Stream<Path> topDirs = Files.list(SHARED_FIXTURES_ROOT)) {
      Set<String> topNames = topDirs.filter(Files::isDirectory)
          .map(p -> p.getFileName().toString())
          .collect(Collectors.toSet());
      Assertions.assertEquals(
          Set.of("syntax", "metadata"),
          topNames,
          "Every top-level directory under shared-fixtures/ must be visited by StvnIrValidationTest"
      );
    }

    try (Stream<Path> syntaxDirs = Files.list(SHARED_FIXTURES_ROOT.resolve("syntax"))) {
      Set<String> syntaxSubNames = syntaxDirs.filter(Files::isDirectory)
          .map(p -> p.getFileName().toString())
          .collect(Collectors.toSet());
      Assertions.assertEquals(Set.of("valid", "invalid"), syntaxSubNames);
    }

    try (Stream<Path> metadataDirs = Files.list(SHARED_FIXTURES_ROOT.resolve("metadata"))) {
      Set<String> metadataSubNames = metadataDirs.filter(Files::isDirectory)
          .map(p -> p.getFileName().toString())
          .collect(Collectors.toSet());
      Assertions.assertEquals(Set.of("valid", "invalid"), metadataSubNames);
    }

    // Verify error_contract.stvn_d itself compiles cleanly with 0 errors and 0 warnings
    String errorContractSource = Files.readString(ERROR_CONTRACT_SCHEMA_PATH, StandardCharsets.UTF_8);
    Assertions.assertFalse(errorContractSource.contains("\r"), "error_contract.stvn_d must use LF line endings");
    var errorContractRes = StvnCompiler.compile(errorContractSource, ERROR_CONTRACT_SCHEMA_PATH.toString(), StvnParserConfig.DEFAULT);
    Assertions.assertTrue(
        errorContractRes.diagnostics().isEmpty(),
        "error_contract.stvn_d must compile with 0 diagnostics: " + errorContractRes.diagnostics()
    );

    if (!UPDATE_MODE) {
      try (Stream<Path> allFiles = Files.walk(SHARED_FIXTURES_ROOT)) {
        List<Path> legacyContracts = allFiles.filter(Files::isRegularFile)
            .filter(p -> p.getFileName().toString().endsWith(".contract.stvn"))
            .toList();
        Assertions.assertTrue(
            legacyContracts.isEmpty(),
            "Zero legacy .contract.stvn files may remain in shared-fixtures/: " + legacyContracts
        );
      }

      try (Stream<Path> allTextFiles = Files.walk(SHARED_FIXTURES_ROOT)) {
        List<Path> crlfFiles = allTextFiles.filter(Files::isRegularFile)
            .filter(StvnIrValidationTest::isTextualStvnFile)
            .filter(p -> {
              try {
                return Files.readString(p, StandardCharsets.UTF_8).contains("\r");
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            })
            .toList();
        Assertions.assertTrue(
            crlfFiles.isEmpty(),
            "GATE-09 violation: Textual .stvn* fixtures must use deterministic LF (\\n) line endings: " + crlfFiles
        );
      }
    }
  }

  @ParameterizedTest(name = "Positive Fixture - {0}")
  @MethodSource("providePositiveFixtures")
  public void testPositiveSharedFixtures(String displayName, Path fixturePath) throws IOException {
    String fileName = fixturePath.getFileName().toString();
    String normalizedRel = SHARED_FIXTURES_ROOT.relativize(fixturePath).toString().replace('\\', '/');

    if (fileName.endsWith(".stvn_b")) {
      byte[] bytes = Files.readAllBytes(fixturePath);
      var root = StvnBinaryDecoder.open(ByteBuffer.wrap(bytes));
      Optional<StvnSchema> companionSchema = resolveCompanionSchemaForBinarySnapshot(fixturePath);
      StvnValue unpacked = StvnBinaryDecoder.unpack(root, companionSchema);
      Assertions.assertNotNull(unpacked, "Unpacked binary fixture must not be null: " + fixturePath);
      return;
    }

    if (fileName.endsWith(".stvn_bd")) {
      var res = StvnCompiler.compile(fixturePath, StvnParserConfig.DEFAULT);
      assertZeroAntlrJargon(res.diagnostics(), fixturePath);
      Assertions.assertTrue(res.diagnostics().isEmpty(), "Binary .stvn_bd fixture produced diagnostics: " + res.diagnostics());
      Assertions.assertTrue(res.document().isPresent(), "Binary .stvn_bd fixture must decode into StvnDocument: " + fixturePath);
      return;
    }

    String source = Files.readString(fixturePath, StandardCharsets.UTF_8).replace("\r\n", "\n");
    boolean isSystemDomain = fileName.endsWith(".stvn_ir.stvn_i")
        || fileName.endsWith(".stvn_ir.stvn_d")
        || fileName.endsWith(".stvn_core.stvn_d");
    StvnParserConfig config = isSystemDomain ? StvnParserConfig.SYSTEM : StvnParserConfig.DEFAULT;

    StvnCompilationResult<StvnDocument> res = StvnCompiler.compile(source, fixturePath.toString(), config);
    assertZeroAntlrJargon(res.diagnostics(), fixturePath);
    Assertions.assertFalse(
        res.hasErrors(),
        () -> "Positive fixture failed compilation (" + normalizedRel + "): " + res.diagnostics()
    );
    Assertions.assertTrue(res.document().isPresent(), "Positive fixture must produce StvnDocument: " + normalizedRel);

    if (isSystemDomain || normalizedRel.startsWith("metadata/valid/")) {
      Assertions.assertTrue(
          res.diagnostics().isEmpty(),
          () -> "Fixture in " + normalizedRel + " must produce 0 errors and 0 warnings, got: " + res.diagnostics()
      );
    } else if (normalizedRel.startsWith("syntax/valid/metadata_warnings/")) {
      Assertions.assertFalse(
          res.diagnostics().isEmpty(),
          "metadata_warnings fixture must emit at least one W001-W003 warning: " + normalizedRel
      );
      Assertions.assertTrue(
          res.diagnostics().stream().allMatch(d -> d.severity() == DiagnosticSeverity.WARNING),
          () -> "metadata_warnings fixture emitted non-WARNING diagnostics: " + res.diagnostics()
      );
    }
  }

  @ParameterizedTest(name = "Negative Fixture & Contract - {0}")
  @MethodSource("provideNegativeFixtures")
  public void testNegativeSharedFixtures(String displayName, DynamicNegativeTestCase testCase) throws IOException {
    Path fixturePath = testCase.fixturePath();
    Path contractPath = testCase.contractPath();
    String fileName = fixturePath.getFileName().toString();
    boolean isBinary = fileName.endsWith(".stvn_b") || fileName.endsWith(".stvn_bd");

    StvnCompilationResult<StvnDocument> targetRes;
    String textualSource = "";
    if (isBinary) {
      targetRes = StvnCompiler.compile(fixturePath, StvnParserConfig.DEFAULT);
    } else {
      textualSource = Files.readString(fixturePath, StandardCharsets.UTF_8).replace("\r\n", "\n");
      targetRes = StvnCompiler.compile(textualSource, fixturePath.toString(), StvnParserConfig.DEFAULT);
    }

    Assertions.assertTrue(
        targetRes.hasErrors(),
        "Expected negative fixture to produce error diagnostics, but compilation succeeded: " + fixturePath
    );
    List<StvnDiagnostic> actualDiagnostics = targetRes.diagnostics();
    Assertions.assertFalse(actualDiagnostics.isEmpty(), "Negative fixture diagnostics list must not be empty: " + fixturePath);
    assertZeroAntlrJargon(actualDiagnostics, fixturePath);

    if (UPDATE_MODE) {
      String stem = deriveStemSlot0(fileName);
      String relErrorContractPath = contractPath.getParent()
          .relativize(ERROR_CONTRACT_SCHEMA_PATH)
          .toString()
          .replace('\\', '/');
      String generatedContract = MetadataFixtureGenerator.formatErrorContractStvnI(
          stem,
          relErrorContractPath,
          actualDiagnostics
      );
      Files.writeString(contractPath, generatedContract, StandardCharsets.UTF_8);

      // Delete any legacy .contract.stvn companion files for this fixture
      Path legacyFullStemContract = fixturePath.resolveSibling(
          fileName.substring(0, fileName.lastIndexOf('.')) + ".contract.stvn"
      );
      Path legacySlot0Contract = fixturePath.resolveSibling(stem + ".contract.stvn");
      Files.deleteIfExists(legacyFullStemContract);
      Files.deleteIfExists(legacySlot0Contract);

      // Immediately verify that the newly generated .contract.stvn_i compiles cleanly with 0 hacks
      var verifyContractRes = StvnCompiler.compileToResult(
          generatedContract,
          contractPath.toString(),
          StvnParserConfig.DEFAULT
      );
      Assertions.assertFalse(
          verifyContractRes.hasErrors(),
          () -> "Generated .contract.stvn_i failed compilation (" + contractPath + "): " + verifyContractRes.diagnostics()
      );
      Assertions.assertTrue(
          verifyContractRes.diagnostics().isEmpty(),
          () -> "Generated .contract.stvn_i emitted unexpected warnings (" + contractPath + "): " + verifyContractRes.diagnostics()
      );
      return;
    }

    Assertions.assertTrue(
        Files.exists(contractPath),
        "Missing coordinate-pinned .contract.stvn_i for negative fixture: " + contractPath
    );

    // Compile .contract.stvn_i strictly via StvnCompiler.compileToResult(...) with ZERO special-case hacks
    String contractContent = Files.readString(contractPath, StandardCharsets.UTF_8).replace("\r\n", "\n");
    StvnCompilationResult<StvnDocument> contractRes = StvnCompiler.compileToResult(
        contractContent,
        contractPath.toString(),
        StvnParserConfig.DEFAULT
    );
    Assertions.assertFalse(
        contractRes.hasErrors(),
        () -> "Contract file failed STVN 2.0.0 compilation (" + contractPath + "): " + contractRes.diagnostics()
    );
    Assertions.assertTrue(
        contractRes.diagnostics().isEmpty(),
        () -> "Contract file emitted unexpected warnings (" + contractPath + "): " + contractRes.diagnostics()
    );

    StvnValue contractRoot = contractRes.document()
        .flatMap(StvnDocument::payload)
        .orElseThrow(() -> new AssertionError("Contract payload missing: " + contractPath));
    List<DiagnosticExpectation> expectedList = DiagnosticExpectation.fromErrorContractIr(contractRoot);

    // Enforce M == N sibling and container delimiter fault isolation
    Assertions.assertEquals(
        expectedList.size(),
        actualDiagnostics.size(),
        () -> "Diagnostic count mismatch for " + fixturePath
            + " (expected " + expectedList.size() + ", got " + actualDiagnostics.size() + "): " + actualDiagnostics
    );

    for (int i = 0; i < expectedList.size(); i++) {
      DiagnosticExpectation exp = expectedList.get(i);
      StvnDiagnostic act = actualDiagnostics.get(i);

      String actCanonical = act.canonicalCode()
          .or(() -> act.symbolicId().flatMap(DiagnosticBag::resolveCanonicalCode))
          .or(() -> act.errorCode().flatMap(DiagnosticBag::resolveCanonicalCode))
          .orElse("");
      String actSymbolic = act.symbolicId()
          .or(act::errorCode)
          .orElse("");
      String actSeverity = act.severity().name();
      int actLine1 = Math.max(1, act.line());
      int actCol1 = act.column() >= 0 ? act.column() + 1 : 1;
      int actStartOff = Math.max(0, act.startOffset());
      int actEndOff = Math.max(actStartOff, act.endOffset());

      final int idx = i;
      Assertions.assertEquals(exp.canonicalCode(), actCanonical,
          () -> "canonicalCode mismatch at diagnostic[" + idx + "] in " + fixturePath + ": " + act);
      Assertions.assertEquals(exp.symbolicId(), actSymbolic,
          () -> "symbolicId mismatch at diagnostic[" + idx + "] in " + fixturePath + ": " + act);
      Assertions.assertEquals(exp.severity(), actSeverity,
          () -> "severity mismatch at diagnostic[" + idx + "] in " + fixturePath + ": " + act);
      Assertions.assertEquals(exp.line(), actLine1,
          () -> "1-based line mismatch at diagnostic[" + idx + "] in " + fixturePath + ": " + act);
      Assertions.assertEquals(exp.column(), actCol1,
          () -> "1-based column mismatch at diagnostic[" + idx + "] in " + fixturePath + ": " + act);
      Assertions.assertEquals(exp.startOffset(), actStartOff,
          () -> "0-based startOffset mismatch at diagnostic[" + idx + "] in " + fixturePath + ": " + act);
      Assertions.assertEquals(exp.endOffset(), actEndOff,
          () -> "0-based endOffset mismatch at diagnostic[" + idx + "] in " + fixturePath + ": " + act);
      Assertions.assertTrue(
          act.message().contains(exp.messageSubstring()),
          () -> "messageSubstring '" + exp.messageSubstring() + "' not found in actual message '"
              + act.message() + "' at diagnostic[" + idx + "] in " + fixturePath
      );
    }

    // Preserve CyclicDependencyException cycle-path integrity verification for cyclic include fixtures
    if (!isBinary && fileName.startsWith("include_cyclic_")) {
      final String src = textualSource;
      Throwable thrown = Assertions.assertThrows(
          Throwable.class,
          () -> StvnCompiler.compilePayload(src, fixturePath.toString())
      );
      if (thrown instanceof CyclicDependencyException cyclicEx) {
        verifyCyclicIncludePaths(cyclicEx);
      }
    }
  }

  private static void verifyCyclicIncludePaths(CyclicDependencyException cyclicEx) throws IOException {
    List<String> rawPaths = cyclicEx.getOffendingIncludePathsRaw();
    List<String> canonicalPaths = cyclicEx.getOffendingIncludePathsCanonical();

    Assertions.assertNotNull(rawPaths, "Raw include paths list must not be null");
    Assertions.assertNotNull(canonicalPaths, "Canonical include paths list must not be null");
    Assertions.assertEquals(rawPaths.size(), canonicalPaths.size(),
        "Raw and canonical path lists must have the same size");
    Assertions.assertFalse(rawPaths.isEmpty(), "Cycle path lists must not be empty");

    int n = rawPaths.size();
    for (int i = 0; i < n; i++) {
      String raw = rawPaths.get(i);
      String canonicalStr = canonicalPaths.get(i);
      Path canonicalPath = Paths.get(canonicalStr);

      Assertions.assertTrue(canonicalPath.isAbsolute(), "Canonical path must be absolute: " + canonicalStr);
      Assertions.assertTrue(Files.exists(canonicalPath), "Canonical path must exist on disk: " + canonicalStr);

      String parentCanonicalStr = canonicalPaths.get((i - 1 + n) % n);
      Path parentPath = Paths.get(parentCanonicalStr);
      String parentContent = Files.readString(parentPath, StandardCharsets.UTF_8);
      Assertions.assertTrue(parentContent.contains(raw),
          "Parent file " + parentCanonicalStr + " does not contain the raw include literal: " + raw);
    }
  }

  private static void assertZeroAntlrJargon(List<StvnDiagnostic> diagnostics, Path sourcePath) {
    for (StvnDiagnostic d : diagnostics) {
      Assertions.assertFalse(
          FORBIDDEN_ANTLR_JARGON.matcher(d.message()).find(),
          () -> "Forbidden raw ANTLR parser-generator jargon leaked in diagnostic for " + sourcePath + ": " + d.message()
      );
    }
  }

  private static Optional<StvnSchema> resolveCompanionSchemaForBinarySnapshot(Path binPath) throws IOException {
    String fileName = binPath.getFileName().toString();
    String baseName = fileName.substring(0, fileName.length() - ".stvn_b".length());
    Path stvnCompanion = binPath.resolveSibling(baseName + ".stvn");
    Path stvniCompanion = binPath.resolveSibling(baseName + ".stvn_i");
    Path companion = Files.exists(stvnCompanion) ? stvnCompanion : (Files.exists(stvniCompanion) ? stvniCompanion : null);
    if (companion == null) {
      return Optional.empty();
    }
    String src = Files.readString(companion, StandardCharsets.UTF_8).replace("\r\n", "\n");
    var res = StvnCompiler.compile(src, companion.toString(), StvnParserConfig.DEFAULT);
    StvnDocument doc = res.document()
        .orElseThrow(() -> new AssertionError("Failed to compile companion source for binary snapshot: " + companion));
    return doc.rootType().or(() -> doc.payload().map(StvnValue::schema));
  }

  private static Stream<Arguments> providePositiveFixtures() throws IOException {
    List<Path> discovered = new ArrayList<>();
    for (Path root : POSITIVE_ROOTS) {
      if (!Files.exists(root)) {
        continue;
      }
      try (Stream<Path> walk = Files.walk(root)) {
        walk.filter(Files::isRegularFile)
            .filter(StvnIrValidationTest::isAnyStvnArtifact)
            .forEach(discovered::add);
      }
    }
    discovered.sort(Comparator.comparing(Path::toString));
    return discovered.stream().map(p -> Arguments.of(
        SHARED_FIXTURES_ROOT.relativize(p).toString().replace('\\', '/'),
        p
    ));
  }

  private static Stream<Arguments> provideNegativeFixtures() throws IOException {
    List<DynamicNegativeTestCase> cases = new ArrayList<>();
    for (Path root : NEGATIVE_ROOTS) {
      if (!Files.exists(root)) {
        continue;
      }
      try (Stream<Path> walk = Files.walk(root)) {
        List<Path> files = walk.filter(Files::isRegularFile)
            .sorted(Comparator.comparing(Path::toString))
            .toList();
        for (Path p : files) {
          String fileName = p.getFileName().toString();
          if (fileName.equals("error_contract.stvn_d")
              || fileName.endsWith(".contract.stvn_i")
              || fileName.endsWith(".contract.stvn")
              || !isAnyStvnArtifact(p)) {
            continue;
          }

          String stem = deriveStemSlot0(fileName);
          Path contractPath = p.resolveSibling(stem + ".contract.stvn_i");
          Path legacyContractPath = p.resolveSibling(
              fileName.substring(0, fileName.lastIndexOf('.')) + ".contract.stvn"
          );

          // Valid helper .stvn_di include modules (e.g., module_a.stvn_di, helper_defs.stvn_di) do not carry error contracts;
          // verify they compile cleanly unless they are themselves negative fixtures.
          if (fileName.endsWith(".stvn_di")
              && !Files.exists(contractPath)
              && !Files.exists(legacyContractPath)) {
            String src = Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n");
            var helperRes = StvnCompiler.compile(src, p.toString(), StvnParserConfig.DEFAULT);
            if (!helperRes.hasErrors()) {
              assertZeroAntlrJargon(helperRes.diagnostics(), p);
              continue;
            }
          }

          String relDisplay = SHARED_FIXTURES_ROOT.relativize(p).toString().replace('\\', '/');
          cases.add(new DynamicNegativeTestCase(relDisplay, p, contractPath));
        }
      }
    }
    return cases.stream().map(tc -> Arguments.of(tc.displayName(), tc));
  }

  private static String deriveStemSlot0(String fileName) {
    int firstDot = fileName.indexOf('.');
    return firstDot > 0 ? fileName.substring(0, firstDot) : fileName;
  }

  private static boolean isAnyStvnArtifact(Path path) {
    String name = path.getFileName().toString();
    return name.endsWith(".stvn")
        || name.endsWith(".stvn_i")
        || name.endsWith(".stvn_d")
        || name.endsWith(".stvn_di")
        || name.endsWith(".stvn_b")
        || name.endsWith(".stvn_bd");
  }

  private static boolean isTextualStvnFile(Path path) {
    String name = path.getFileName().toString();
    return name.endsWith(".stvn")
        || name.endsWith(".stvn_i")
        || name.endsWith(".stvn_d")
        || name.endsWith(".stvn_di");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("provideValidationCases")
  public void testValidation(String displayName, ValidationTestCase testCase) {
    if (testCase.isSuccess()) {
      var ast = StvnCompiler.compilePayload(testCase.input()).orElseThrow();
      Assertions.assertNotNull(ast);
      Assertions.assertNotNull(testCase.irVerifier());
      testCase.irVerifier().accept(ast);
    } else {
      var exception = Assertions.assertThrows(RuntimeException.class, () -> {
        StvnCompiler.compilePayload(testCase.input());
      });
      var expectedError = testCase.expectedError();
      Assertions.assertNotNull(expectedError);
      var exceptionMessage = exception.getMessage();
      var matches = (exceptionMessage != null && exceptionMessage.contains(expectedError))
          || exception.getClass().getSimpleName().contains(expectedError)
          || (exception.getCause() != null && exception.getCause().getClass().getSimpleName().contains(expectedError));
      Assertions.assertTrue(matches, () -> "Expected error '" + expectedError + "' was not found in exception: " + exception);
    }
  }

  private static Stream<Arguments> provideValidationCases() {
    return Stream.of(
        // --- 2a) Happy-Path Implied Vector ---
        ValidationTestCase.success(
            "Implied Option - Scalar Integer Implied Some",
            "{ :type :Option(:Int) :body 42 }",
            val -> {
              var opt = Assertions.assertInstanceOf(StvnOption.class, val);
              Assertions.assertEquals(BigInteger.valueOf(42), ((StvnInt) opt.value().orElseThrow()).value());
            }
        ),
        ValidationTestCase.success(
            "Implied Either - Scalar Right Implied Route",
            "{ :type :Either(:Int :String) :body \"right-value\" }",
            val -> {
              var either = Assertions.assertInstanceOf(StvnEither.class, val);
              Assertions.assertEquals(EitherBranch.RIGHT, either.branch());
              Assertions.assertEquals("right-value", ((StvnString) either.value()).value());
            }
        ),

        // --- 2b) Explicit Disambiguation Vector ---
        ValidationTestCase.success(
            "Explicit Option - Explicit Some Tag",
            "{ :type :Option(:Int) :body #Some 42 }",
            val -> {
              var opt = Assertions.assertInstanceOf(StvnOption.class, val);
              Assertions.assertEquals(BigInteger.valueOf(42), ((StvnInt) opt.value().orElseThrow()).value());
            }
        ),
        ValidationTestCase.success(
            "Explicit Option - Explicit None Tag",
            "{ :type :Option(:Int) :body #None }",
            val -> {
              var opt = Assertions.assertInstanceOf(StvnOption.class, val);
              Assertions.assertTrue(opt.value().isEmpty());
            }
        ),
        ValidationTestCase.success(
            "Explicit Either - Left Variant Tagged",
            "{ :type :Either(:Int :String) :body #Left 42 }",
            val -> {
              var either = Assertions.assertInstanceOf(StvnEither.class, val);
              Assertions.assertEquals(EitherBranch.LEFT, either.branch());
              Assertions.assertEquals(BigInteger.valueOf(42), ((StvnInt) either.value()).value());
            }
        ),
        ValidationTestCase.success(
            "Explicit Disambiguation - Enum Variant Clash via Double Tagging",
            """
            {
              :defs { :Status :Enum [ #Left #Right #Pending ] }
              :type :Either(:Status :Int)
              :body #Left #Left
            }
            """,
            val -> {
              var either = Assertions.assertInstanceOf(StvnEither.class, val);
              Assertions.assertEquals(EitherBranch.LEFT, either.branch());
              var enumVal = Assertions.assertInstanceOf(StvnEnum.class, either.value());
              Assertions.assertEquals("#Left", enumVal.variantTag());
              Assertions.assertEquals(BigInteger.ZERO, enumVal.rootOrdinal());
            }
        ),

        // --- 2c) Rule C Failure Vector ---
        ValidationTestCase.failure(
            "Rule C Failure - Ambiguous Either Identical Sides (String)",
            "{ :type :Either(:String :String) :body \"world\" }",
            "Ambiguous implicit either: Both sides are identical (:String), explicit #Left or #Right tag is required"
        ),
        ValidationTestCase.failure(
            "Rule C Failure - Ambiguous Option Keyword Clash with Enum Value",
            """
            {
              :defs { :AppMode :Enum [ #None #Active ] }
              :type :Option(:AppMode)
              :body #None
            }
            """,
            "IllegalArgumentException"
        ),
        ValidationTestCase.failure(
            "Rule C Failure - Ambiguous Either Keyword Clash with Enum Value",
            """
            {
              :defs { :Command :Enum [ #Left #Stop ] }
              :type :Either(:Int :Command)
              :body #Left
            }
            """,
            "IllegalArgumentException"
        ),

        // --- Deeply Nested Sum Types Extension ---
        ValidationTestCase.success(
            "Nested Implicit Evaluation - Option Either Right",
            "{ :type :Option(:Either(:Int :String)) :body \"nested-implicit-right\" }",
            val -> {
              var opt = Assertions.assertInstanceOf(StvnOption.class, val);
              var either = Assertions.assertInstanceOf(StvnEither.class, opt.value().orElseThrow());
              Assertions.assertEquals(EitherBranch.RIGHT, either.branch());
              Assertions.assertEquals("nested-implicit-right", ((StvnString) either.value()).value());
            }
        ),
        ValidationTestCase.success(
            "Hybrid Nested Tagging - Explicit Either Implicit Option Some",
            "{ :type :Either(:Int :Option(:String)) :body #Right \"implicit-inner-some\" }",
            val -> {
              var either = Assertions.assertInstanceOf(StvnEither.class, val);
              Assertions.assertEquals(EitherBranch.RIGHT, either.branch());
              var opt = Assertions.assertInstanceOf(StvnOption.class, either.value());
              Assertions.assertEquals("implicit-inner-some", ((StvnString) opt.value().orElseThrow()).value());
            }
        ),
        ValidationTestCase.failure(
            "Deep Rule C Failure Propagation - Sequence Option Keyword Clash",
            """
            {
              :defs { :AppMode :Enum [ #None #Active ] }
              :type :Seq(:Option(:AppMode))
              :body [ #None ]
            }
            """,
            "IllegalArgumentException"
        )
    ).map(testCase -> Arguments.of(testCase.name(), testCase));
  }

  @Test
  public void testIncludeLegal() throws Exception {
    String input = Files.readString(Paths.get("shared-fixtures/syntax/valid/modules/include_legal.stvn_i"), StandardCharsets.UTF_8);
    var astOpt = StvnCompiler.compilePayload(input, "shared-fixtures/syntax/valid/modules/include_legal.stvn_i");
    Assertions.assertTrue(astOpt.isPresent());
    var ast = astOpt.get();
    Assertions.assertNotNull(ast);
    var astStr = ast.toString();
    Assertions.assertTrue(astStr.contains("ConflictType"));
    Assertions.assertTrue(astStr.contains("ConflictTypeB"));
  }

  @Test
  @DisplayName("TC-IR-TUPLE-01: Tuple arity mismatch throws MalformedPayloadException with pinned coordinates")
  void testTupleArityMismatchCoordinatesInStrictCompile() {
    String underflow = """
        {
          :type :Tuple(:Int :Int)
          :body ( 42 )
        }
        """;
    var exUnderflow = Assertions.assertThrows(
        MalformedPayloadException.class,
        () -> StvnCompiler.compilePayload(underflow)
    );
    int rparen = underflow.lastIndexOf(')');
    Assertions.assertEquals(rparen, exUnderflow.startOffset());
    Assertions.assertEquals(rparen + 1, exUnderflow.endOffset());

    String overflow = """
        {
          :type :Tuple(:Int)
          :body ( 42 84 126 )
        }
        """;
    var exOverflow = Assertions.assertThrows(
        MalformedPayloadException.class,
        () -> StvnCompiler.compilePayload(overflow)
    );
    int startExcess = overflow.indexOf("84");
    int endExcess = overflow.indexOf("126") + "126".length();
    Assertions.assertEquals(startExcess, exOverflow.startOffset());
    Assertions.assertEquals(endExcess, exOverflow.endOffset());
  }

  @Test
  @DisplayName("TC-CARD-01: Valid cardinality fixtures compile and extract values correctly")
  public void testValidCardinalityFixturesValueExtraction() throws Exception {
    Path cardDir = Paths.get("shared-fixtures/syntax/valid/cardinality");
    Assertions.assertTrue(Files.exists(cardDir), "Cardinality fixture directory must exist");

    // 1. Exact string fixed dimension
    var fixedAst = StvnCompiler.compilePayload(Files.readString(cardDir.resolve("string_exact_fixed_dimension.stvn"), StandardCharsets.UTF_8)).orElseThrow();
    Assertions.assertInstanceOf(StvnString.class, fixedAst);
    Assertions.assertEquals("STVN", ((StvnString) fixedAst).value());

    // 2. Bounded string interval
    var boundedAst = StvnCompiler.compilePayload(Files.readString(cardDir.resolve("string_bounded_interval.stvn"), StandardCharsets.UTF_8)).orElseThrow();
    Assertions.assertInstanceOf(StvnString.class, boundedAst);
    Assertions.assertEquals("A compliant payload within interval", ((StvnString) boundedAst).value());

    // 3. Unbounded string default
    var unboundedAst = StvnCompiler.compilePayload(Files.readString(cardDir.resolve("string_unbounded_default.stvn"), StandardCharsets.UTF_8)).orElseThrow();
    Assertions.assertInstanceOf(StvnString.class, unboundedAst);
    Assertions.assertTrue(((StvnString) unboundedAst).value().startsWith("Standard string payload"));

    // 4. Bounded sequence cardinality
    var seqAst = StvnCompiler.compilePayload(Files.readString(cardDir.resolve("seq_bounded_cardinality.stvn"), StandardCharsets.UTF_8)).orElseThrow();
    Assertions.assertInstanceOf(StvnValue.StvnSeq.class, seqAst);
    Assertions.assertEquals(5, ((StvnValue.StvnSeq) seqAst).elements().size());

    // 5. Bounded set cardinality
    var setAst = StvnCompiler.compilePayload(Files.readString(cardDir.resolve("set_bounded_cardinality.stvn"), StandardCharsets.UTF_8)).orElseThrow();
    Assertions.assertInstanceOf(StvnValue.StvnSet.class, setAst);
    Assertions.assertEquals(3, ((StvnValue.StvnSet) setAst).elements().size());

    // 6. Bounded map cardinality
    var mapAst = StvnCompiler.compilePayload(Files.readString(cardDir.resolve("map_bounded_cardinality.stvn"), StandardCharsets.UTF_8)).orElseThrow();
    Assertions.assertInstanceOf(StvnValue.StvnMap.class, mapAst);
    Assertions.assertEquals(2, ((StvnValue.StvnMap) mapAst).entries().size());

    // 7. Collection minimum only
    var minOnlyAst = StvnCompiler.compilePayload(Files.readString(cardDir.resolve("collection_minimum_only.stvn"), StandardCharsets.UTF_8)).orElseThrow();
    Assertions.assertInstanceOf(StvnValue.StvnSeq.class, minOnlyAst);
    Assertions.assertEquals(3, ((StvnValue.StvnSeq) minOnlyAst).elements().size());
  }
}
