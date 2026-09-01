package org.stvnadore.core.integration;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ir.StvnValue;

import org.stvnadore.core.ir.StvnValue.StvnEnum;
import org.stvnadore.core.ir.StvnValue.StvnOption;
import org.stvnadore.core.ir.StvnValue.StvnString;
import org.stvnadore.core.ir.StvnValue.StvnTuple;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@NullMarked
public class StvnIrValidationTest {

  private static final Path INVALID_FIXTURES_DIR = Paths.get("shared-fixtures/invalid-syntax");
  private static final boolean UPDATE_MODE = Boolean.getBoolean("updateSnapshots");

  public record ValidationTestCase(
      String name,
      String input,
      boolean isSuccess,
      @Nullable String expectedAstString,
      @Nullable String expectedError
  ) {
    public static ValidationTestCase success(String name, String input, String expectedAstString) {
      return new ValidationTestCase(name, input, true, expectedAstString, null);
    }

    public static ValidationTestCase failure(String name, String input, String expectedError) {
      return new ValidationTestCase(name, input, false, null, expectedError);
    }
  }

  public record DynamicInvalidTestCase(
      String fileName,
      Path stvnPath,
      Path contractPath,
      boolean hasContract
  ) {}

  public record StvnErrorContract(
      String category,
      String errorMessageSubstring,
      Optional<String> expectedExceptionClass
  ) {
    public static StvnErrorContract fromAst(StvnValue rootAst) {
      if (!(rootAst instanceof StvnTuple tuple) || tuple.elements().size() != 3) {
        throw new IllegalArgumentException("Contract root must be a 3-element StvnTuple: " + rootAst);
      }

      if (!(tuple.elements().get(0) instanceof StvnEnum categoryEnum)) {
        throw new IllegalArgumentException("Contract element 0 must be an ErrorCategory Enum: " + tuple.elements().get(0));
      }
      String category = categoryEnum.keyword();

      if (!(tuple.elements().get(1) instanceof StvnString messageStr)) {
        throw new IllegalArgumentException("Contract element 1 must be an error message String: " + tuple.elements().get(1));
      }
      String messageSubstring = messageStr.value();

      if (!(tuple.elements().get(2) instanceof StvnOption exceptionOpt)) {
        throw new IllegalArgumentException("Contract element 2 must be an expectedException Option: " + tuple.elements().get(2));
      }
      Optional<String> expectedExceptionClass = exceptionOpt.value().map(val -> {
        if (!(val instanceof StvnString str)) {
          throw new IllegalArgumentException("Expected exception class in Option must be String: " + val);
        }
        return str.value();
      });

      return new StvnErrorContract(category, messageSubstring, expectedExceptionClass);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("provideValidationCases")
  public void testValidation(String displayName, ValidationTestCase testCase) {
    if (testCase.isSuccess()) {
      var ast = StvnCompiler.compile(testCase.input()).orElseThrow();
      Assertions.assertNotNull(ast);
      var actualAstString = ast.toString();
      Assertions.assertEquals(testCase.expectedAstString(), actualAstString);
    } else {
      var exception = Assertions.assertThrows(RuntimeException.class, () -> {
        StvnCompiler.compile(testCase.input());
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

  @ParameterizedTest(name = "Invalid Syntax Fixture - {0}")
  @MethodSource("provideDynamicInvalidCases")
  public void testDynamicInvalidSyntaxFixtures(String displayName, DynamicInvalidTestCase testCase) throws IOException {
    String stvnContent = Files.readString(testCase.stvnPath());

    if (UPDATE_MODE) {
      try {
        StvnCompiler.compile(stvnContent, testCase.stvnPath().toString());
        Assertions.fail("Expected malformed fixture to fail compilation, but it succeeded: " + testCase.stvnPath());
      } catch (Throwable e) {
        String expectedException = e.getClass().getName();
        String errorMessage = e.getMessage();
        String escapedMessage = errorMessage != null 
            ? errorMessage.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
            : "";
        String category = deriveCategory(e);
        String contractContent = String.format("""
            {
              // %s
              :defs {
                :include [ "error_contract.stvn_inclf" ]
              }

              :type :ErrorContract

              :body (
                #%s
                "%s"
                "%s"
              )
            }
            """,
            testCase.contractPath().getFileName(),
            category,
            escapedMessage,
            expectedException
        );
        Files.writeString(testCase.contractPath(), contractContent);
        System.out.printf("  [GENERATED/UPDATED CONTRACT] %s%n", testCase.contractPath().getFileName());
      }
      return;
    }

    Assertions.assertTrue(
        testCase.hasContract(),
        "Missing assertion contract for malformed fixture: " + testCase.contractPath().getFileName()
    );

    String contractContent = Files.readString(testCase.contractPath());
    StvnValue contractAst = StvnCompiler.compile(contractContent, testCase.contractPath().toString())
        .orElseThrow(() -> new AssertionError("Failed to compile contract: " + testCase.contractPath()));
    
    StvnErrorContract contract = StvnErrorContract.fromAst(contractAst);

    Throwable exception = Assertions.assertThrows(Throwable.class, () -> {
      StvnCompiler.compile(stvnContent, testCase.stvnPath().toString());
    }, "Expected compilation to throw an exception for malformed fixture: " + testCase.stvnPath());

    contract.expectedExceptionClass().ifPresent(expectedException -> {
      Assertions.assertEquals(expectedException, exception.getClass().getName(), "Incorrect exception type thrown");
    });

    String actualMessage = exception.getMessage();
    Assertions.assertNotNull(actualMessage, "Exception message was null");
    Assertions.assertTrue(
        actualMessage.contains(contract.errorMessageSubstring()),
        () -> "Expected error substring '" + contract.errorMessageSubstring() + "' not found in: " + actualMessage
    );

    if (exception instanceof org.stvnadore.core.validation.CyclicDependencyException cyclicEx) {
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

        // Verify the raw path list matches the expected array literals on disk
        // The including file is at index (i - 1 + n) % n in the canonical list
        String parentCanonicalStr = canonicalPaths.get((i - 1 + n) % n);
        Path parentPath = Paths.get(parentCanonicalStr);
        String parentContent = Files.readString(parentPath);
        Assertions.assertTrue(parentContent.contains(raw), 
            "Parent file " + parentCanonicalStr + " does not contain the raw include literal: " + raw);
      }
    }
  }

  private static String deriveCategory(Throwable e) {
    if (e instanceof org.stvnadore.core.validation.CyclicDependencyException) {
      return "CYCLIC_DEPENDENCY";
    }
    if (e instanceof org.stvnadore.core.validation.NamespaceCollisionException) {
      return "NAMESPACE_COLLISION";
    }
    if (e instanceof org.stvnadore.core.validation.DuplicateModuleImportException) {
      return "DUPLICATE_IMPORT";
    }
    if (e instanceof org.stvnadore.core.validation.StvnTypeResolver.CircularReferenceException) {
      return "CIRCULAR_REFERENCE";
    }
    if (e instanceof org.stvnadore.core.validation.StvnMalformedLiteralException) {
      return "MALFORMED_LITERAL";
    }
    if (e instanceof org.stvnadore.core.validation.StvnCollectionCollisionException) {
      return "COLLECTION_COLLISION";
    }
    if (e instanceof org.stvnadore.core.validation.MalformedSchemaException) {
      return "MALFORMED_SCHEMA";
    }
    if (e instanceof org.stvnadore.core.validation.MalformedPayloadException) {
      return "MALFORMED_PAYLOAD";
    }
    if (e instanceof IllegalArgumentException) {
      return "TYPE_MISMATCH";
    }
    return "SYNTAX_ERROR";
  }

  private static Stream<Arguments> provideDynamicInvalidCases() throws IOException {
    if (!Files.exists(INVALID_FIXTURES_DIR)) {
      return Stream.empty();
    }
    try (Stream<Path> paths = Files.walk(INVALID_FIXTURES_DIR)) {
      List<Path> targetFiles = paths.filter(p -> {
        String s = p.toString();
        if (s.endsWith(".contract.stvn") || s.endsWith("error_contract.stvn_inclf") || s.endsWith(".stvn_ir") || s.endsWith(".stvn_bin")) {
          return false;
        }
        if (s.endsWith(".stvn")) {
          return true;
        }
        if (s.endsWith(".stvn_inclf") || s.endsWith(".stvn_incl")) {
          String baseName = p.getFileName().toString();
          String contractFileName = baseName.substring(0, baseName.lastIndexOf('.')) + ".contract.stvn";
          return Files.exists(p.resolveSibling(contractFileName));
        }
        return false;
      }).toList();
      return targetFiles.stream().map(stvnPath -> {
        String baseName = stvnPath.getFileName().toString();
        String contractFileName = baseName.substring(0, baseName.lastIndexOf('.')) + ".contract.stvn";
        Path contractPath = stvnPath.resolveSibling(contractFileName);
        boolean hasContract = Files.exists(contractPath);
        return Arguments.of(baseName, new DynamicInvalidTestCase(baseName, stvnPath, contractPath, hasContract));
      });
    }
  }

  private static Stream<Arguments> provideValidationCases() {
    return Stream.of(
        // --- 2a) Happy-Path Implied Vector ---
        ValidationTestCase.success(
            "Implied Option - Scalar Integer Implied Some",
            "{ :type :Option(:Int32) :body 42 }",
            "StvnOption[schema=ResolvedSchema[node=[134 92 85], constraints=StvnConstraints[minIncl=null, minExcl=null, maxIncl=null, maxExcl=null, regex=null, preserveIndent=false, equatable=true, comparable=true, explicitOverrides=[]], aliasName=null, implicitUnionTag=null, sumTypeNode=null, underlyingSchema=null, localConstraints=null], value=Optional[StvnInteger[schema=ResolvedSchema[node=[223 189 182 134 92 85], constraints=StvnConstraints[minIncl=null, minExcl=null, maxIncl=null, maxExcl=null, regex=null, preserveIndent=false, equatable=true, comparable=true, explicitOverrides=[]], aliasName=null, implicitUnionTag=0, sumTypeNode=[189 182 134 92 85], underlyingSchema=null, localConstraints=null], value=42, bitWidth=32, isUnsigned=false]]]"
        ),
        ValidationTestCase.success(
            "Implied Either - Scalar Right Implied Route",
            "{ :type :Either(:Int32 :String) :body \"right-value\" }",
            "StvnEither[schema=ResolvedSchema[node=[134 92 85], constraints=StvnConstraints[minIncl=null, minExcl=null, maxIncl=null, maxExcl=null, regex=null, preserveIndent=false, equatable=true, comparable=true, explicitOverrides=[]], aliasName=null, implicitUnionTag=null, sumTypeNode=null, underlyingSchema=null, localConstraints=null], value=StvnString[schema=ResolvedSchema[node=[231 189 182 134 92 85], constraints=StvnConstraints[minIncl=null, minExcl=null, maxIncl=null, maxExcl=null, regex=null, preserveIndent=false, equatable=true, comparable=true, explicitOverrides=[]], aliasName=null, implicitUnionTag=1, sumTypeNode=[189 182 134 92 85], underlyingSchema=null, localConstraints=null], value=right-value, style=SIMPLE, fenceTag=Optional.empty, trait=StringTrait[fixedLength=0, maxLength=0, isNonEmpty=false]], isRight=true, isAmbiguous=false]"
        ),

        // --- 2b) Explicit Disambiguation Vector ---
        ValidationTestCase.success(
            "Explicit Option - Explicit Some Tag",
            "{ :type :Option(:Int32) :body #Some 42 }",
            "StvnOption[schema=ResolvedSchema[node=[134 92 85], constraints=StvnConstraints[minIncl=null, minExcl=null, maxIncl=null, maxExcl=null, regex=null, preserveIndent=false, equatable=true, comparable=true, explicitOverrides=[]], aliasName=null, implicitUnionTag=null, sumTypeNode=null, underlyingSchema=null, localConstraints=null], value=Optional[StvnInteger[schema=ResolvedSchema[node=[223 189 182 134 92 85], constraints=StvnConstraints[minIncl=null, minExcl=null, maxIncl=null, maxExcl=null, regex=null, preserveIndent=false, equatable=true, comparable=true, explicitOverrides=[]], aliasName=null, implicitUnionTag=null, sumTypeNode=null, underlyingSchema=null, localConstraints=null], value=42, bitWidth=32, isUnsigned=false]]]"
        ),
        ValidationTestCase.success(
            "Explicit Option - Explicit None Tag",
            "{ :type :Option(:Int32) :body #None }",
            "StvnOption[schema=ResolvedSchema[node=[134 92 85], constraints=StvnConstraints[minIncl=null, minExcl=null, maxIncl=null, maxExcl=null, regex=null, preserveIndent=false, equatable=true, comparable=true, explicitOverrides=[]], aliasName=null, implicitUnionTag=null, sumTypeNode=null, underlyingSchema=null, localConstraints=null], value=Optional.empty]"
        ),
        ValidationTestCase.success(
            "Explicit Either - Left Variant Tagged",
            "{ :type :Either(:Int32 :String) :body #Left 42 }",
            "StvnEither[schema=ResolvedSchema[node=[134 92 85], constraints=StvnConstraints[minIncl=null, minExcl=null, maxIncl=null, maxExcl=null, regex=null, preserveIndent=false, equatable=true, comparable=true, explicitOverrides=[]], aliasName=null, implicitUnionTag=null, sumTypeNode=null, underlyingSchema=null, localConstraints=null], value=StvnInteger[schema=ResolvedSchema[node=[230 189 182 134 92 85], constraints=StvnConstraints[minIncl=null, minExcl=null, maxIncl=null, maxExcl=null, regex=null, preserveIndent=false, equatable=true, comparable=true, explicitOverrides=[]], aliasName=null, implicitUnionTag=null, sumTypeNode=null, underlyingSchema=null, localConstraints=null], value=42, bitWidth=32, isUnsigned=false], isRight=false, isAmbiguous=false]"
        ),
        ValidationTestCase.success(
            "Explicit Disambiguation - Enum Variant Clash via Double Tagging",
            """
            {
              :defs { :Status :Enum [ #Left #Right #Pending ] }
              :type :Either(:Status :Int32)
              :body #Left #Left
            }
            """,
            "StvnEither[schema=ResolvedSchema[node=[134 92 85], constraints=StvnConstraints[minIncl=null, minExcl=null, maxIncl=null, maxExcl=null, regex=null, preserveIndent=false, equatable=true, comparable=true, explicitOverrides=[]], aliasName=null, implicitUnionTag=null, sumTypeNode=null, underlyingSchema=null, localConstraints=null], value=StvnEnum[schema=ResolvedSchema[node=[143 100 89 85], constraints=StvnConstraints[minIncl=null, minExcl=null, maxIncl=null, maxExcl=null, regex=null, preserveIndent=false, equatable=true, comparable=true, explicitOverrides=[]], aliasName=:Status, implicitUnionTag=null, sumTypeNode=null, underlyingSchema=ResolvedSchema[node=[143 100 89 85], constraints=StvnConstraints[minIncl=null, minExcl=null, maxIncl=null, maxExcl=null, regex=null, preserveIndent=false, equatable=true, comparable=true, explicitOverrides=[]], aliasName=null, implicitUnionTag=null, sumTypeNode=null, underlyingSchema=null, localConstraints=null], localConstraints=StvnConstraints[minIncl=null, minExcl=null, maxIncl=null, maxExcl=null, regex=null, preserveIndent=false, equatable=null, comparable=null, explicitOverrides=[]]], keyword=#Left, sequentialIndex=0, variantCount=3], isRight=false, isAmbiguous=false]"
        ),

        // --- 2c) Rule C Failure Vector ---
        ValidationTestCase.failure(
            "Rule C Failure - Ambiguous Either Identical Sides (String)",
            "{ :type :Either(:String :String) :body [ \"world\" ] }",
            "share identical nominal type identities"
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
              :type :Either(:Int32 :Command)
              :body #Left
            }
            """,
            "IllegalArgumentException"
        ),

        // --- Deeply Nested Sum Types Extension ---
        ValidationTestCase.success(
            "Nested Implicit Evaluation - Option Either Right",
            "{ :type :Option(:Either(:Int32 :String)) :body \"nested-implicit-right\" }",
            "StvnOption[schema=ResolvedSchema[node=[134 92 85], constraints=StvnConstraints[minIncl=null, minExcl=null, maxIncl=null, maxExcl=null, regex=null, preserveIndent=false, equatable=true, comparable=true, explicitOverrides=[]], aliasName=null, implicitUnionTag=null, sumTypeNode=null, underlyingSchema=null, localConstraints=null], value=Optional[StvnEither[schema=ResolvedSchema[node=[223 189 182 134 92 85], constraints=StvnConstraints[minIncl=null, minExcl=null, maxIncl=null, maxExcl=null, regex=null, preserveIndent=false, equatable=true, comparable=true, explicitOverrides=[]], aliasName=null, implicitUnionTag=null, sumTypeNode=null, underlyingSchema=null, localConstraints=null], value=StvnString[schema=ResolvedSchema[node=[231 189 182 223 189 182 134 92 85], constraints=StvnConstraints[minIncl=null, minExcl=null, maxIncl=null, maxExcl=null, regex=null, preserveIndent=false, equatable=true, comparable=true, explicitOverrides=[]], aliasName=null, implicitUnionTag=1, sumTypeNode=[189 182 223 189 182 134 92 85], underlyingSchema=null, localConstraints=null], value=nested-implicit-right, style=SIMPLE, fenceTag=Optional.empty, trait=StringTrait[fixedLength=0, maxLength=0, isNonEmpty=false]], isRight=true, isAmbiguous=false]]]"
        ),
        ValidationTestCase.success(
            "Hybrid Nested Tagging - Explicit Either Implicit Option Some",
            "{ :type :Either(:Int32 :Option(:String)) :body #Right \"implicit-inner-some\" }",
            "StvnEither[schema=ResolvedSchema[node=[134 92 85], constraints=StvnConstraints[minIncl=null, minExcl=null, maxIncl=null, maxExcl=null, regex=null, preserveIndent=false, equatable=true, comparable=true, explicitOverrides=[]], aliasName=null, implicitUnionTag=null, sumTypeNode=null, underlyingSchema=null, localConstraints=null], value=StvnOption[schema=ResolvedSchema[node=[231 189 182 134 92 85], constraints=StvnConstraints[minIncl=null, minExcl=null, maxIncl=null, maxExcl=null, regex=null, preserveIndent=false, equatable=true, comparable=true, explicitOverrides=[]], aliasName=null, implicitUnionTag=null, sumTypeNode=null, underlyingSchema=null, localConstraints=null], value=Optional[StvnString[schema=ResolvedSchema[node=[223 189 182 231 189 182 134 92 85], constraints=StvnConstraints[minIncl=null, minExcl=null, maxIncl=null, maxExcl=null, regex=null, preserveIndent=false, equatable=true, comparable=true, explicitOverrides=[]], aliasName=null, implicitUnionTag=0, sumTypeNode=[189 182 231 189 182 134 92 85], underlyingSchema=null, localConstraints=null], value=implicit-inner-some, style=SIMPLE, fenceTag=Optional.empty, trait=StringTrait[fixedLength=0, maxLength=0, isNonEmpty=false]]]], isRight=true, isAmbiguous=false]"
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

  @org.junit.jupiter.api.Test
  public void testIncludeLegal() throws Exception {
    String input = java.nio.file.Files.readString(java.nio.file.Paths.get("shared-fixtures/valid-syntax/include_legal.stvn"));
    var astOpt = StvnCompiler.compile(input, "shared-fixtures/valid-syntax/include_legal.stvn");
    Assertions.assertTrue(astOpt.isPresent());
    var ast = astOpt.get();
    Assertions.assertNotNull(ast);
    var astStr = ast.toString();
    Assertions.assertTrue(astStr.contains("ConflictType"));
    Assertions.assertTrue(astStr.contains("ConflictTypeB"));
  }
}
