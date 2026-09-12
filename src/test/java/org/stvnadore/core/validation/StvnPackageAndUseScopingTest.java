package org.stvnadore.core.validation;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.ir.StvnValue;

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
              :Port :Uint16
              #DEFAULT_PORT :Uint16 8080
            }
          }
          :type :org/example/network/Port
          :body #org/example/network/DEFAULT_PORT
        }
        """;

    var result = StvnCompiler.compileToResult(source);
    Assertions.assertTrue(result.isSuccess(), "LHS expansion to FQNI must compile cleanly: " + result.diagnostics());
    StvnValue val = result.orElseThrow();
    Assertions.assertInstanceOf(StvnValue.StvnInteger.class, val);
    Assertions.assertEquals(8080L, ((StvnValue.StvnInteger) val).value().longValue());
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
  @DisplayName("TC-PKG-03: Ingress gate enforcement for .stvn_f emits ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT")
  void testIngressGateEnforcementForFlatDocument(@TempDir Path tempDir) throws IOException {
    Path flatFile = tempDir.resolve("payload.stvn_f");
    String content = """
        {
          :defs {
            :include [ "common.stvn_incl" ]
            :Local :String
          }
          :type :Local
          :body "data"
        }
        """;
    Files.writeString(flatFile, content);

    var result = StvnCompiler.compileToResult(content, flatFile.toString());
    Assertions.assertFalse(result.isSuccess(), "Includes in .stvn_f must fail compilation before lowering");
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
            :package :PkgB { :Model :Int64 }
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
            :package :PkgB { :Model :Int64 }
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
              #DEFAULT_TIMEOUT :Uint32 5000
              :Timeout :Uint32
            }
            :use [ :org/example/config { #strip } ]
          }
          :type :Timeout
          :body #DEFAULT_TIMEOUT
        }
        """;

    var result = StvnCompiler.compileToResult(source);
    Assertions.assertTrue(result.isSuccess(), "Constant stripping must preserve '#' sigil and resolve: " + result.diagnostics());
    StvnValue val = result.orElseThrow();
    Assertions.assertInstanceOf(StvnValue.StvnInteger.class, val);
    Assertions.assertEquals(5000L, ((StvnValue.StvnInteger) val).value().longValue());
  }

  @Test
  @DisplayName("TC-STRIP-01: Terminal slicing execution for types and constants")
  void testTerminalSlicingExecution() {
    Assertions.assertEquals(":Uuid", StvnTypeResolver.sliceTerminal(":org/stvnadore/prelude/Uuid"));
    Assertions.assertEquals("#PORT", StvnTypeResolver.sliceTerminal("#org/stvnadore/network/PORT"));
    Assertions.assertEquals(":Simple", StvnTypeResolver.sliceTerminal(":Simple"));
    Assertions.assertEquals("#SIMPLE", StvnTypeResolver.sliceTerminal("#SIMPLE"));
    Assertions.assertEquals(":DeepType", StvnTypeResolver.sliceTerminal(":a/b/c/d/e/DeepType"));
    Assertions.assertEquals("#DEEP_CONST", StvnTypeResolver.sliceTerminal("#a/b/c/d/e/DEEP_CONST"));
  }

  @Test
  @DisplayName("TC-STRIP-02: Parameterized string rejection on #strip")
  void testParameterizedStringRejectionOnStrip() {
    String source = """
        {
          :defs {
            :include [ "module.stvn_incl" { #strip "pkg/sub/" } ]
          }
          :type :Int32
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
          :type :Int32
          :body 1
        }
        """;

    var result = StvnCompiler.compileToResult(source);
    Assertions.assertFalse(result.isSuccess(), "Trailing slash in :use target must fail compilation");
    Assertions.assertTrue(result.diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_TRAILING_SLASH_PROHIBITED.equals(d.errorCode().orElse(null))),
        "Must record ERR_TRAILING_SLASH_PROHIBITED: " + result.diagnostics());
  }
}
