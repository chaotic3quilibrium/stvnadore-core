package org.stvnadore.core.validation;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.ir.StvnValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Verification test suite for the Definitions ({@code :defs}) section overhaul,
 * standard prelude relocation, LHS reserved keyword prohibition, bit-width range
 * enforcement on constants, and include prefix stripping ({@code #strip}).
 *
 * @since 1.2.0
 */
public class StvnDefinitionsOverhaulTest {

  // ==========================================================================
  // Test Group 1: Standard Prelude Relocation & Root Namespace Clearance
  // ==========================================================================

  @Test
  @DisplayName("Bare unqualified prelude type fails at root document scope with ERR_UNKNOWN_TYPE")
  void testBarePreludeTypeFailsAtRoot() {
    String input = """
        {
          :type :Port
          :body 8080
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess(), "Bare prelude type :Port must fail without import or alias");
    Assertions.assertTrue(result.hasErrors());

    boolean hasErrUnknownType = result.diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_UNKNOWN_TYPE.equals(d.errorCode().orElse(null)));
    Assertions.assertTrue(hasErrUnknownType, "Expected diagnostic code ERR_UNKNOWN_TYPE");
  }

  @Test
  @DisplayName("Namespaced prelude type succeeds at root document scope")
  void testNamespacedPreludeTypeSucceeds() {
    String input = """
        {
          :type :org/stvnadore/prelude/Port
          :body 8080
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.isSuccess(), "Namespaced prelude type must compile cleanly");
    Assertions.assertFalse(result.hasErrors());
    Assertions.assertNotNull(result.orElseThrow());
  }

  @Test
  @DisplayName("Local prelude alias allows unqualified usage")
  void testLocalPreludeAliasSucceeds() {
    String input = """
        {
          :defs {
            :Port :org/stvnadore/prelude/Port
          }
          :type :Port
          :body 443
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.isSuccess(), "Local alias for prelude type must compile cleanly");
    Assertions.assertFalse(result.hasErrors());
    Assertions.assertNotNull(result.orElseThrow());
  }

  // ==========================================================================
  // Test Group 2: LHS Reserved Keyword Prohibition
  // ==========================================================================

  @Test
  @DisplayName("LHS definition cannot bind reserved atomic primitive keywords")
  void testLhsReservedAtomicPrimitiveFails() {
    String input1 = """
        {
          :defs {
            :Int :String
          }
          :type :String
          :body "test"
        }
        """;
    var res1 = StvnCompiler.compileToResult(input1);
    Assertions.assertFalse(res1.isSuccess());
    Assertions.assertTrue(res1.diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_RESERVED_KEYWORD_ON_LHS.equals(d.errorCode().orElse(null))));

    String input2 = """
        {
          :defs {
            :Float :String
          }
          :type :String
          :body "test"
        }
        """;
    var res2 = StvnCompiler.compileToResult(input2);
    Assertions.assertFalse(res2.isSuccess());
    Assertions.assertTrue(res2.diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_RESERVED_KEYWORD_ON_LHS.equals(d.errorCode().orElse(null))));
  }

  @Test
  @DisplayName("LHS definition cannot bind structural constructor keywords")
  void testLhsReservedStructuralConstructorFails() {
    for (String type : List.of(":Tuple", ":Enum", ":Option")) {
      String input = "{ :defs { " + type + " :Int } :type :Int :body 1 }";
      var res = StvnCompiler.compileToResult(input);
      Assertions.assertFalse(res.isSuccess(), "Expected failure for " + type);
      Assertions.assertTrue(res.diagnostics().stream()
          .anyMatch(d -> DiagnosticBag.ERR_RESERVED_KEYWORD_ON_LHS.equals(d.errorCode().orElse(null))),
          "Expected ERR_RESERVED_KEYWORD_ON_LHS for " + type);
    }
  }

  @Test
  @DisplayName("LHS definition cannot bind collection constructor keywords")
  void testLhsReservedCollectionConstructorFails() {
    for (String type : List.of(":Seq", ":Map", ":Set")) {
      String input = "{ :defs { " + type + " :Int } :type :Int :body 1 }";
      var res = StvnCompiler.compileToResult(input);
      Assertions.assertFalse(res.isSuccess(), "Expected failure for " + type);
      Assertions.assertTrue(res.diagnostics().stream()
          .anyMatch(d -> DiagnosticBag.ERR_RESERVED_KEYWORD_ON_LHS.equals(d.errorCode().orElse(null))),
          "Expected ERR_RESERVED_KEYWORD_ON_LHS for " + type);
    }
  }

  @Test
  @DisplayName("LHS definition cannot bind directive keywords")
  void testLhsDirectiveKeywordFails() {
    for (String type : List.of(":defs", ":type", ":body", ":include")) {
      String input = "{ :defs { " + type + " :Int } :type :Int :body 1 }";
      var res = StvnCompiler.compileToResult(input);
      Assertions.assertFalse(res.isSuccess(), "Expected failure for " + type);
      Assertions.assertTrue(res.diagnostics().stream()
          .anyMatch(d -> DiagnosticBag.ERR_RESERVED_KEYWORD_ON_LHS.equals(d.errorCode().orElse(null))),
          "Expected ERR_RESERVED_KEYWORD_ON_LHS for " + type);
    }
  }

  @Test
  @DisplayName("Multi-error accumulation across invalid LHS definitions in :defs")
  void testMultiErrorAccumulationAcrossDefs() {
    String input = """
        {
          :defs {
            :Int :String
            :Float :String
            :Tuple :Int
            :Seq :Int
            :defs :Boolean
          }
          :type :String
          :body "test"
        }
        """;

    var res = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(res.isSuccess());
    long count = res.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_RESERVED_KEYWORD_ON_LHS.equals(d.errorCode().orElse(null)))
        .count();
    Assertions.assertEquals(5, count, "Must record all 5 ERR_RESERVED_KEYWORD_ON_LHS errors");
  }

  // ==========================================================================
  // Test Group 3: Constant Definition Literal Bit-Width Range Enforcement
  // ==========================================================================

  @Test
  @DisplayName("Unsigned integer constants validate capacity bounds")
  void testUnsignedIntegerUnderflowAndOverflow() {
    String underflow = """
        {
          :defs {
            :Uint3 { #unsigned #size 3 } :Int
            #C1 :Uint3 -1
          }
          :type :Uint3
          :body 1
        }
        """;
    var res1 = StvnCompiler.compileToResult(underflow);
    Assertions.assertFalse(res1.isSuccess());
    Assertions.assertTrue(res1.diagnostics().stream().anyMatch(d ->
        DiagnosticBag.ERR_INTEGER_OVERFLOW.equals(d.errorCode().orElse(null)) &&
        d.message().contains("Integer literal -1 out of range for :Uint3 [0, 7]")));

    String overflow = """
        {
          :defs {
            :Uint3 { #unsigned #size 3 } :Int
            #C2 :Uint3 8
          }
          :type :Uint3
          :body 1
        }
        """;
    var res2 = StvnCompiler.compileToResult(overflow);
    Assertions.assertFalse(res2.isSuccess());
    Assertions.assertTrue(res2.diagnostics().stream().anyMatch(d ->
        DiagnosticBag.ERR_INTEGER_OVERFLOW.equals(d.errorCode().orElse(null)) &&
        d.message().contains("Integer literal 8 out of range for :Uint3 [0, 7]")));
  }

  @Test
  @DisplayName("Signed integer constants validate capacity bounds")
  void testSignedIntegerUnderflowAndOverflow() {
    String underflow = """
        {
          :defs {
            :Int3 { #size 3 } :Int
            #C1 :Int3 -5
          }
          :type :Int3
          :body 1
        }
        """;
    var res1 = StvnCompiler.compileToResult(underflow);
    Assertions.assertFalse(res1.isSuccess());
    Assertions.assertTrue(res1.diagnostics().stream().anyMatch(d ->
        DiagnosticBag.ERR_INTEGER_OVERFLOW.equals(d.errorCode().orElse(null)) &&
        d.message().contains("Integer literal -5 out of range for :Int3 [-4, 3]")));

    String overflow = """
        {
          :defs {
            :Int3 { #size 3 } :Int
            #C2 :Int3 4
          }
          :type :Int3
          :body 1
        }
        """;
    var res2 = StvnCompiler.compileToResult(overflow);
    Assertions.assertFalse(res2.isSuccess());
    Assertions.assertTrue(res2.diagnostics().stream().anyMatch(d ->
        DiagnosticBag.ERR_INTEGER_OVERFLOW.equals(d.errorCode().orElse(null)) &&
        d.message().contains("Integer literal 4 out of range for :Int3 [-4, 3]")));
  }

  @Test
  @DisplayName("1-bit integer boundary conditions for constants")
  void test1BitBoundaryConditions() {
    String b1 = "{ :defs { :Uint1 { #unsigned #size 1 } :Int #B1 :Uint1 1 } :type :Uint1 :body 0 }";
    Assertions.assertTrue(StvnCompiler.compileToResult(b1).isSuccess());

    String b2 = "{ :defs { :Uint1 { #unsigned #size 1 } :Int #B2 :Uint1 2 } :type :Uint1 :body 0 }";
    var resB2 = StvnCompiler.compileToResult(b2);
    Assertions.assertFalse(resB2.isSuccess());
    Assertions.assertTrue(resB2.diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_INTEGER_OVERFLOW.equals(d.errorCode().orElse(null))),
        "Diagnostics for b2: " + resB2.diagnostics());

    String s1 = "{ :defs { :Int1 { #size 1 } :Int #S1 :Int1 -1 } :type :Int1 :body 0 }";
    Assertions.assertTrue(StvnCompiler.compileToResult(s1).isSuccess());

    String s2 = "{ :defs { :Int1 { #size 1 } :Int #S2 :Int1 1 } :type :Int1 :body 0 }";
    var resS2 = StvnCompiler.compileToResult(s2);
    Assertions.assertFalse(resS2.isSuccess());
    Assertions.assertTrue(resS2.diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_INTEGER_OVERFLOW.equals(d.errorCode().orElse(null))),
        "Diagnostics for s2: " + resS2.diagnostics());
  }

  @Test
  @DisplayName("64-bit and 128-bit boundary conditions for constants")
  void test64BitAnd128BitBoundaryConditions() {
    // Uint64: max is 18446744073709551615
    String u64Ok = "{ :defs { :Uint64 { #unsigned #size 64 } :Int #U :Uint64 18446744073709551615 } :type :Uint64 :body 0 }";
    Assertions.assertTrue(StvnCompiler.compileToResult(u64Ok).isSuccess());

    String u64Fail = "{ :defs { :Uint64 { #unsigned #size 64 } :Int #U :Uint64 18446744073709551616 } :type :Uint64 :body 0 }";
    Assertions.assertTrue(StvnCompiler.compileToResult(u64Fail).diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_INTEGER_OVERFLOW.equals(d.errorCode().orElse(null))),
        "Diagnostics for u64Fail: " + StvnCompiler.compileToResult(u64Fail).diagnostics());

    // Int64: max is 9223372036854775807
    String i64Ok = "{ :defs { :Int64 { #size 64 } :Int #I :Int64 9223372036854775807 } :type :Int64 :body 0 }";
    Assertions.assertTrue(StvnCompiler.compileToResult(i64Ok).isSuccess());

    String i64Fail = "{ :defs { :Int64 { #size 64 } :Int #I :Int64 9223372036854775808 } :type :Int64 :body 0 }";
    Assertions.assertTrue(StvnCompiler.compileToResult(i64Fail).diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_INTEGER_OVERFLOW.equals(d.errorCode().orElse(null))));

    // Uint128: max is 340282366920938463463374607431768211455
    String u128Ok = "{ :defs { :Uint128 { #unsigned #size 128 } :Int #U :Uint128 340282366920938463463374607431768211455 } :type :Uint128 :body 0 }";
    Assertions.assertTrue(StvnCompiler.compileToResult(u128Ok).isSuccess());

    String u128Fail = "{ :defs { :Uint128 { #unsigned #size 128 } :Int #U :Uint128 340282366920938463463374607431768211456 } :type :Uint128 :body 0 }";
    Assertions.assertTrue(StvnCompiler.compileToResult(u128Fail).diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_INTEGER_OVERFLOW.equals(d.errorCode().orElse(null))));

    // Int128: min is -170141183460469231731687303715884105728
    String i128Ok = "{ :defs { :Int128 { #size 128 } :Int #I :Int128 -170141183460469231731687303715884105728 } :type :Int128 :body 0 }";
    Assertions.assertTrue(StvnCompiler.compileToResult(i128Ok).isSuccess());

    String i128Fail = "{ :defs { :Int128 { #size 128 } :Int #I :Int128 -170141183460469231731687303715884105729 } :type :Int128 :body 0 }";
    Assertions.assertTrue(StvnCompiler.compileToResult(i128Fail).diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_INTEGER_OVERFLOW.equals(d.errorCode().orElse(null))));
  }

  @Test
  @DisplayName("Constant overflow halts before lowering to IR")
  void testConstantOverflowHaltsBeforeLowering() {
    String input = """
        {
          :defs {
            :Uint8 { #unsigned #size 8 } :Int
            #BAD :Uint8 256
          }
          :type :Uint8
          :body #BAD
        }
        """;
    var res = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(res.isSuccess());
    Assertions.assertTrue(res.hasErrors());
    Assertions.assertTrue(res.diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_INTEGER_OVERFLOW.equals(d.errorCode().orElse(null))));
  }

  // ==========================================================================
  // Test Group 4: Include Prefix Stripping (#strip)
  // ==========================================================================

  @Test
  @DisplayName("Bare #strip imports strip up to terminal slash")
  void testBareStripImports(@TempDir Path tempDir) throws IOException {
    Path subDir = tempDir.resolve("sub");
    Files.createDirectories(subDir);
    Path module = subDir.resolve("module.stvn_incl");
    Files.writeString(module, """
        {
          :defs {
            :pkg/sub/Type :Int
          }
        }
        """);

    Path mainFile = tempDir.resolve("main.stvn");
    String mainContent = """
        {
          :defs {
            :include [ "sub/module.stvn_incl" { #strip } ]
          }
          :type :Type
          :body 42
        }
        """;
    Files.writeString(mainFile, mainContent);

    var res = StvnCompiler.compileToResult(mainContent, mainFile.toString());
    Assertions.assertTrue(res.isSuccess(), "Bare strip should strip pkg/sub/Type to Type");
    Assertions.assertNotNull(res.orElseThrow());
  }

  @Test
  @DisplayName("Unary #strip strips all definitions to terminal segment")
  void testUnaryStripMultipleDefinitions(@TempDir Path tempDir) throws IOException {
    Path module = tempDir.resolve("module.stvn_incl");
    Files.writeString(module, """
        {
          :defs {
            :pkg/sub/Type :Int
            :other/Foo :String
          }
        }
        """);

    Path mainFile = tempDir.resolve("main.stvn");
    String mainContent = """
        {
          :defs {
            :include [ "module.stvn_incl" { #strip } ]
          }
          :type :Tuple( :Type :Foo )
          :body ( 42 "bar" )
        }
        """;
    Files.writeString(mainFile, mainContent);

    var res = StvnCompiler.compileToResult(mainContent, mainFile.toString());
    Assertions.assertTrue(res.isSuccess(), "Unary strip should strip all definitions to terminal segments: " + res.diagnostics());
    Assertions.assertNotNull(res.orElseThrow());
  }

  @Test
  @DisplayName("Parameterized #strip argument fails at parser gate")
  void testParameterizedStripFailsAtParserGate(@TempDir Path tempDir) throws IOException {
    Path module = tempDir.resolve("module.stvn_incl");
    Files.writeString(module, """
        {
          :defs {
            :pkg/sub/Type :Int
          }
        }
        """);

    Path mainFile = tempDir.resolve("main.stvn");
    String mainContent = """
        {
          :defs {
            :include [ "module.stvn_incl" { #strip "unmatched/path/" } ]
          }
          :type :Int
          :body 42
        }
        """;
    Files.writeString(mainFile, mainContent);

    var res = StvnCompiler.compileToResult(mainContent, mainFile.toString());
    Assertions.assertFalse(res.isSuccess());
  }

  @Test
  @DisplayName("#strip combined with alias block renames stripped symbol")
  void testStripCombinedWithAliasBlock(@TempDir Path tempDir) throws IOException {
    Path module = tempDir.resolve("module.stvn_incl");
    Files.writeString(module, """
        {
          :defs {
            :pkg/sub/Type :Int
          }
        }
        """);

    Path mainFile = tempDir.resolve("main.stvn");
    String mainContent = """
        {
          :defs {
            :include [ "module.stvn_incl" { #strip } { :Type :LocalType } ]
          }
          :type :LocalType
          :body 123
        }
        """;
    Files.writeString(mainFile, mainContent);

    var res = StvnCompiler.compileToResult(mainContent, mainFile.toString());
    Assertions.assertTrue(res.isSuccess(), "Stripped symbol should be renamed by alias block to LocalType");
    Assertions.assertNotNull(res.orElseThrow());
  }

  // ==========================================================================
  // Test Group 5: Flattener Nominal Alias Preservation
  // ==========================================================================

  @Test
  @DisplayName("Flattener preserves nominal aliases verbatim without inlining")
  void testFlattenerPreservesNominalAliasVerbatim() {
    Map<String, String> workspace = Map.of(
        "main.stvn", """
            {
              :defs {
                :Port :org/stvnadore/prelude/Port
                :LocalPort :Port
              }
              :type :LocalPort
              :body 8080
            }
            """
    );

    String flattened = StvnSchemaFlattener.flatten(workspace, "main.stvn");
    Assertions.assertTrue(flattened.contains(":LocalPort :Port"),
        "Must preserve nominal alias :LocalPort :Port verbatim: " + flattened);
    Assertions.assertTrue(flattened.contains(":Port :org/stvnadore/prelude/Port"),
        "Must preserve :Port :org/stvnadore/prelude/Port verbatim: " + flattened);
  }

  @Test
  @DisplayName("Flattener CAS hash stability across modular and flattened forms")
  void testFlattenerCasHashStability(@TempDir Path tempDir) throws IOException {
    Path netModule = tempDir.resolve("net.stvn_incl");
    Files.writeString(netModule, """
        {
          :defs {
            :org/stvnadore/network/Port :org/stvnadore/prelude/Port
          }
        }
        """);

    Path mainFile = tempDir.resolve("main.stvn");
    String mainContent = """
        {
          :defs {
            :include [ "net.stvn_incl" { #strip } ]
          }
          :type :Port
          :body 8080
        }
        """;
    Files.writeString(mainFile, mainContent);

    var modularResult = StvnCompiler.compileToResult(mainContent, mainFile.toString());
    Assertions.assertTrue(modularResult.isSuccess(), "Modular document should compile cleanly");

    Map<String, String> workspace = Map.of(
        "net.stvn_incl", Files.readString(netModule),
        "main.stvn", mainContent
    );
    String flattenedDefs = StvnSchemaFlattener.flatten(workspace, "main.stvn");
    String flattenedDocument = flattenedDefs.replaceFirst("\\}\\s*$", ":type :Port :body 8080 }");

    var flattenedResult = StvnCompiler.compileToResult(flattenedDocument);
    Assertions.assertTrue(flattenedResult.isSuccess(), "Flattened document should compile cleanly");

    byte[] modularCas = StvnCompiler.computeCasFingerprint(modularResult.orElseThrow());
    byte[] flattenedCas = StvnCompiler.computeCasFingerprint(flattenedResult.orElseThrow());
    Assertions.assertArrayEquals(modularCas, flattenedCas, "CAS hashes must be identical before and after flattening");
  }
}
