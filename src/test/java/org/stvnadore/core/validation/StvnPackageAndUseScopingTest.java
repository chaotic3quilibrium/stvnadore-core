package org.stvnadore.core.validation;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.lowering.StvnDefsLowerer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verification test suite for ECD-2026-04 and ECD-2026-04-A1 implementations.
 * Tests package enclosures, scoped use imports, terminal slicing, and ingress gates.
 */
public class StvnPackageAndUseScopingTest {

  @Test
  @DisplayName("TC-PKG-01: Flat enclosure expansion to FQNI resolves types and constants")
  void testFlatEnclosureExpansionToFqni() {
    String source = """
        {
          :defs {
            :package :org/example/network {
              :Port :Int
              #DEFAULT_PORT :Port 8080
            }
          }
          :type :org/example/network/Port
          :body #org/example/network/DEFAULT_PORT
        }
        """;

    var result = StvnCompiler.compileToResult(source);
    Assertions.assertTrue(result.isSuccess(), "LHS expansion to FQNI must compile cleanly: " + result.diagnostics());
    StvnValue val = result.orElseThrow().requirePayload();
    Assertions.assertInstanceOf(StvnValue.StvnInt.class, val);
    Assertions.assertEquals(8080L, ((StvnValue.StvnInt) val).value().longValue());
  }

  @Test
  @DisplayName("TC-PKG-02: Prohibition of nested packages emits ERR_NESTED_PACKAGE_PROHIBITED")
  void testProhibitionOfNestedPackages() {
    String source = """
        {
          :defs {
            :package :Outer {
              :package :Inner {
                :Sub :String
              }
            }
          }
          :type :Outer/Inner/Sub
          :body "test"
        }
        """;

    var result = StvnCompiler.compileToResult(source);
    Assertions.assertFalse(result.isSuccess(), "Nested packages must fail compilation");
    Assertions.assertTrue(result.diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_NESTED_PACKAGE_PROHIBITED.equals(d.errorCode().orElse(null))),
        "Must record ERR_NESTED_PACKAGE_PROHIBITED in DiagnosticBag: " + result.diagnostics());
  }

  @Test
  @DisplayName("TC-PKG-03: Ingress gate enforcement for .stvn emits ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT")
  void testIngressGateEnforcementForFlatDocument(@TempDir Path tempDir) throws IOException {
    Path flatFile = tempDir.resolve("payload.stvn");
    String content = """
        {
          :meta { #kind #BODY }
          :defs {
            :include [ "common.stvn_d" ]
            :Local :String
          }
          :type :Local
          :body "data"
        }
        """;
    Files.writeString(flatFile, content);

    var result = StvnCompiler.compileToResult(content, flatFile.toString());
    Assertions.assertFalse(result.isSuccess(), "Includes in .stvn must fail compilation before lowering");
    Assertions.assertTrue(result.diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT.equals(d.errorCode().orElse(null))),
        "Must emit ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT: " + result.diagnostics());
  }

  @Test
  @DisplayName("TC-PKG-04: Section 4.2 eviction cascade resolution with :use")
  void testSection42EvictionCascadeResolutionWithUse() {
    // Sub-case A: Unmitigated collision emits ERR_NAMESPACE_COLLISION
    String collisionSource = """
        {
          :defs {
            :package :PkgA { :Model :String }
            :package :PkgB { :Model :Int }
            :use [ :PkgA { #strip } ]
            :use [ :PkgB { #strip } ]
          }
          :type :Model
          :body "val"
        }
        """;
    var colResult = StvnCompiler.compileToResult(collisionSource);
    Assertions.assertFalse(colResult.isSuccess(), "Unmitigated :use collisions must fail");
    Assertions.assertTrue(colResult.diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_NAMESPACE_COLLISION.equals(d.errorCode().orElse(null))),
        "Must emit ERR_NAMESPACE_COLLISION: " + colResult.diagnostics());

    // Sub-case B: Local definition evicts clashing :use import
    String localOverrideSource = """
        {
          :defs {
            :package :PkgA { :Model :String }
            :use [ :PkgA { #strip } ]
            :Model :Boolean
          }
          :type :Model
          :body #TRUE
        }
        """;
    var overrideResult = StvnCompiler.compileToResult(localOverrideSource);
    Assertions.assertTrue(overrideResult.isSuccess(), "Local definition must evict clashing :use import: " + overrideResult.diagnostics());

    // Sub-case C: Explicit alias block resolves collision
    String aliasedSource = """
        {
          :defs {
            :package :PkgA { :Model :String }
            :package :PkgB { :Model :Int }
            :use [ :PkgA { #strip } ]
            :use [ :PkgB { :Model :PkgBModel } ]
          }
          :type :Tuple( :Model :PkgBModel )
          :body ( "hello" 42 )
        }
        """;
    var aliasResult = StvnCompiler.compileToResult(aliasedSource);
    Assertions.assertTrue(aliasResult.isSuccess(), "Explicit alias block must resolve collision: " + aliasResult.diagnostics());
  }

  @Test
  @DisplayName("TC-PKG-05: Constant stripping parity across #strip and :use")
  void testConstantStrippingParityAcrossStripAndUse() {
    String source = """
        {
          :defs {
            :package :org/example/config {
              :Timeout :Int
              #DEFAULT_TIMEOUT :Timeout 5000
            }
            :use [ :org/example/config { #strip } ]
          }
          :type :Timeout
          :body #DEFAULT_TIMEOUT
        }
        """;

    var result = StvnCompiler.compileToResult(source);
    Assertions.assertTrue(result.isSuccess(), "Constant stripping must preserve '#' sigil and resolve: " + result.diagnostics());
    StvnValue val = result.orElseThrow().requirePayload();
    Assertions.assertInstanceOf(StvnValue.StvnInt.class, val);
    Assertions.assertEquals(5000L, ((StvnValue.StvnInt) val).value().longValue());
  }

  @Test
  @DisplayName("TC-STRIP-01: Terminal slicing execution for types and constants")
  void testTerminalSlicingExecution() {
    Assertions.assertEquals(":UuidLowerCase", StvnDefsLowerer.sliceTerminal(":org/stvnadore/prelude/UuidLowerCase"));
    Assertions.assertEquals(":UuidMixedCase", StvnDefsLowerer.sliceTerminal(":org/stvnadore/prelude/UuidMixedCase"));
    Assertions.assertEquals("#PORT", StvnDefsLowerer.sliceTerminal("#org/stvnadore/network/PORT"));
    Assertions.assertEquals(":Simple", StvnDefsLowerer.sliceTerminal(":Simple"));
    Assertions.assertEquals("#SIMPLE", StvnDefsLowerer.sliceTerminal("#SIMPLE"));
    Assertions.assertEquals(":DeepType", StvnDefsLowerer.sliceTerminal(":a/b/c/d/e/DeepType"));
    Assertions.assertEquals("#DEEP_CONST", StvnDefsLowerer.sliceTerminal("#a/b/c/d/e/DEEP_CONST"));
  }

  @Test
  @DisplayName("TC-STRIP-02: Parameterized string rejection on #strip")
  void testParameterizedStringRejectionOnStrip() {
    String source = """
        {
          :defs {
            :include [ "module.stvn_d" { #strip "pkg/sub/" } ]
          }
          :type :Int
          :body 1
        }
        """;

    var result = StvnCompiler.compileToResult(source);
    Assertions.assertFalse(result.isSuccess(), "Parameterized #strip string argument must fail at the parser gate");
  }

  @Test
  @DisplayName("TC-USE-01: Trailing slash error accumulation halts compilation before lowering")
  void testTrailingSlashErrorAccumulation() {
    String source = """
        {
          :defs {
            :use [ :org/stvnadore/prelude/ { #strip } ]
          }
          :type :Int
          :body 1
        }
        """;

    var result = StvnCompiler.compileToResult(source);
    Assertions.assertFalse(result.isSuccess(), "Trailing slash in :use target must fail compilation");
    Assertions.assertTrue(result.diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_TRAILING_SLASH_PROHIBITED.equals(d.errorCode().orElse(null))),
        "Must record ERR_TRAILING_SLASH_PROHIBITED: " + result.diagnostics());
  }

  @Test
  @DisplayName("Package enclave barrier: Unqualified reference to packaged symbol across include fails closed with ERR_UNKNOWN_TYPE")
  void testPackagedTypeUnqualifiedLeakFailsClosed(@TempDir Path tempDir) throws IOException {
    Path modFile = tempDir.resolve("network.stvn_d");
    String modContent = """
        {
          :meta { #kind #DEFS }
          :defs {
            :package :Network {
              :Port :Int
            }
          }
        }
        """;
    Files.writeString(modFile, modContent);

    String docContent = """
        {
          :meta {
            #name "main"
            #kind #BODY_INCLUDE
          }
          :defs {
            :include [ "network.stvn_d" ]
          }
          :type :Port
          :body 8080
        }
        """;
    Path docFile = tempDir.resolve("main.stvn_i");
    Files.writeString(docFile, docContent);

    var result = StvnCompiler.compileToResult(docContent, docFile.toString());
    Assertions.assertFalse(result.isSuccess(), "Bare reference to packaged type must fail closed");
    var diag = result.diagnostics().getFirst();
    Assertions.assertEquals(DiagnosticBag.ERR_UNKNOWN_TYPE, diag.errorCode().orElse(""));
    Assertions.assertTrue(diag.message().contains("Undefined type: :Port"));
  }

  @Test
  @DisplayName("TC-USE-MIXED-01: Multi-line :use with mixed :Type and #CONST aliases and inline // comments compiles cleanly")
  void testMultiLineUseMixedTypeAndConstWithInlineComments() {
    String source = """
        {
          :meta {
            #name "use_nested"
            #domain "json"
            #kind #BODY_INCLUDE
          }
          :defs {
            :use [ :org/stvnadore/prelude/meta { :StvnPosixId64 :StvnPosixId64 } ]
            // Comment before second :use
            :use [ :org/stvnadore/prelude  {                   // <-- inline comment with '/ ' inside :use
                :Port :Port                                    // <-- type alias entry
              #SHA256_LOWER_CASE_REGEX #SHA256_LOWER_CASE_REGEX // <-- constant alias entry
              }                                                // <-- closing brace comment
            ]

            :Values :Tuple(
              :StvnPosixId64
              :Port
              :String
            )
          }

          :type { #maxSize 4096 } :Seq(:Values)

          :body [
            (
              "stvnPosixId64"
              12345
              #SHA256_LOWER_CASE_REGEX
            )
          ]
        }
        """;
    var result = StvnCompiler.compileToResult(source, "use_nested.json.stvn_i");
    Assertions.assertTrue(result.isSuccess(), () -> "Expected clean compilation but got: " + result.diagnostics());
    Assertions.assertFalse(result.hasErrors());
    Assertions.assertFalse(result.hasWarnings());
  }

  @Test
  @DisplayName("TC-STAGE9-01: Combined nested package, reserved package, trailing slash :use, and nominal brand mismatch accumulate exact spans without swallowing")
  void testStage9CombinedNestedReservedPackageTrailingSlashAndBrandMismatch() {
    String reservedPkgPath = ":org/stvnadore/custom_hack";
    String trailingSlashTarget = ":com/acme/identity/";
    String brandConstValue = "#ADMIN_USER_ID";
    String source = """
        {
          :meta {
            #name "gauntlet_stage9"
            #kind #BODY_INCLUDE
          }
          :defs {
            :package :com/acme/identity {
              :UserId { #unsigned #size 64 #minIncl 1000 } :Int
              :AccountId { #unsigned #size 64 #minIncl 1000 } :Int
              #ADMIN_USER_ID :UserId 9001

              :package :com/acme/identity/nested {
                :IllegalNestedType :Int
              }
            }

            :package :org/stvnadore/custom_hack {
              :HijackedPort :Int
            }

            :use [ :com/acme/identity/ ]
            :use [ :com/acme/identity { #strip } ]

            #WRONG_BRAND_CONST :AccountId #ADMIN_USER_ID
          }
          :type :AccountId
          :body #ADMIN_USER_ID
        }
        """.replace("\r\n", "\n");

    var result = StvnCompiler.compileToResult(source, "gauntlet_stage9.stvn_i");
    Assertions.assertTrue(result.hasErrors(), "Combined Stage 9 document must fail compilation");
    var diagnostics = result.diagnostics();
    Assertions.assertEquals(5, diagnostics.size(), () -> "Expected all 5 diagnostics without recovery swallowing, got: " + diagnostics);

    // 1. 2A: ERR_NESTED_PACKAGE_PROHIBITED (E002)
    StvnDiagnostic dNested = diagnostics.get(0);
    Assertions.assertEquals(DiagnosticBag.ERR_NESTED_PACKAGE_PROHIBITED, dNested.errorCode().orElse(""));
    Assertions.assertEquals("E002", dNested.canonicalCode().orElse(""));
    int nestedStart = source.indexOf(":package :com/acme/identity/nested");
    int nestedEnd = source.indexOf("}", nestedStart) + 1;
    Assertions.assertEquals(12, dNested.line());
    Assertions.assertEquals(6, dNested.column());
    Assertions.assertEquals(nestedStart, dNested.startOffset());
    Assertions.assertEquals(nestedEnd, dNested.endOffset());

    // 2. 2B: ERR_RESERVED_PACKAGE_PROHIBITED (E037)
    StvnDiagnostic dReserved = diagnostics.get(1);
    Assertions.assertEquals(DiagnosticBag.ERR_RESERVED_PACKAGE_PROHIBITED, dReserved.errorCode().orElse(""));
    Assertions.assertEquals("E037", dReserved.canonicalCode().orElse(""));
    int reservedStart = source.indexOf(reservedPkgPath);
    int reservedEnd = reservedStart + reservedPkgPath.length();
    Assertions.assertEquals(17, dReserved.line());
    Assertions.assertEquals(13, dReserved.column());
    Assertions.assertEquals(reservedStart, dReserved.startOffset());
    Assertions.assertEquals(reservedEnd, dReserved.endOffset());

    // 3. 2C: ERR_TRAILING_SLASH_PROHIBITED (E002)
    StvnDiagnostic dSlash = diagnostics.get(2);
    Assertions.assertEquals(DiagnosticBag.ERR_TRAILING_SLASH_PROHIBITED, dSlash.errorCode().orElse(""));
    Assertions.assertEquals("E002", dSlash.canonicalCode().orElse(""));
    int slashStart = source.indexOf(trailingSlashTarget, source.indexOf(":use ["));
    int slashEnd = slashStart + trailingSlashTarget.length();
    Assertions.assertEquals(21, dSlash.line());
    Assertions.assertEquals(11, dSlash.column());
    Assertions.assertEquals(slashStart, dSlash.startOffset());
    Assertions.assertEquals(slashEnd, dSlash.endOffset());

    // 4. 3A: ERR_INCOMPATIBLE_NOMINAL_TYPE (E027) on #WRONG_BRAND_CONST
    StvnDiagnostic dConstBrand = diagnostics.get(3);
    Assertions.assertEquals(DiagnosticBag.ERR_INCOMPATIBLE_NOMINAL_TYPE, dConstBrand.errorCode().orElse(""));
    Assertions.assertEquals("E027", dConstBrand.canonicalCode().orElse(""));
    int constBrandStart = source.indexOf(brandConstValue, source.indexOf("#WRONG_BRAND_CONST"));
    int constBrandEnd = constBrandStart + brandConstValue.length();
    Assertions.assertEquals(24, dConstBrand.line());
    Assertions.assertEquals(34, dConstBrand.column());
    Assertions.assertEquals(constBrandStart, dConstBrand.startOffset());
    Assertions.assertEquals(constBrandEnd, dConstBrand.endOffset());

    // 5. 3B: ERR_INCOMPATIBLE_NOMINAL_TYPE (E027) in :body
    StvnDiagnostic dBodyBrand = diagnostics.get(4);
    Assertions.assertEquals(DiagnosticBag.ERR_INCOMPATIBLE_NOMINAL_TYPE, dBodyBrand.errorCode().orElse(""));
    Assertions.assertEquals("E027", dBodyBrand.canonicalCode().orElse(""));
    int bodyBrandStart = source.lastIndexOf(brandConstValue);
    int bodyBrandEnd = bodyBrandStart + brandConstValue.length();
    Assertions.assertEquals(27, dBodyBrand.line());
    Assertions.assertEquals(8, dBodyBrand.column());
    Assertions.assertEquals(bodyBrandStart, dBodyBrand.startOffset());
    Assertions.assertEquals(bodyBrandEnd, dBodyBrand.endOffset());
  }

  @Test
  @DisplayName("TC-STAGE9-02: Shared fixture inv_stage9_nested_reserved_package_and_trailing_slash.stvn_i accumulates all 9 Stage 7/8/9 diagnostics")
  void testStage789PolyglotSharedFixtureAccumulation() throws IOException {
    Path fixturePath = Path.of("shared-fixtures/syntax/invalid/packages/inv_stage9_nested_reserved_package_and_trailing_slash.stvn_i");
    String source = Files.readString(fixturePath);
    var result = StvnCompiler.compileToResult(source, fixturePath.toString());
    Assertions.assertTrue(result.hasErrors(), "Polyglot Stage 7/8/9 fixture must fail compilation");
    var diagnostics = result.diagnostics();
    Assertions.assertEquals(9, diagnostics.size(), () -> "Expected all 9 diagnostics, got: " + diagnostics);

    Assertions.assertEquals(DiagnosticBag.ERR_NESTED_PACKAGE_PROHIBITED, diagnostics.get(0).errorCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.ERR_RESERVED_PACKAGE_PROHIBITED, diagnostics.get(1).errorCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.ERR_TRAILING_SLASH_PROHIBITED, diagnostics.get(2).errorCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.ERR_INCOMPATIBLE_NOMINAL_TYPE, diagnostics.get(3).errorCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.ERR_INCOMPATIBLE_NOMINAL_TYPE, diagnostics.get(4).errorCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.ERR_TYPE_MISMATCH, diagnostics.get(5).errorCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.ERR_EITHER_LEFT_MISSING_TAG, diagnostics.get(6).errorCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.ERR_CONSTRAINT_VIOLATION, diagnostics.get(7).errorCode().orElse(""));
    Assertions.assertEquals(DiagnosticBag.ERR_DUPLICATE_INVERTED_MAP_VALUE, diagnostics.get(8).errorCode().orElse(""));
  }
}
