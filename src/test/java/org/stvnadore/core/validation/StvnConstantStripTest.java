package org.stvnadore.core.validation;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnSchemaFlattener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

class StvnConstantStripTest {

  @Test
  @DisplayName("Bare #strip on :use strips cross-file constant path up to terminal slash preserving '#' sigil")
  void testBareStripConstantImports(@TempDir Path tempDir) throws IOException {
    Path module = tempDir.resolve("module.stvn_d");
    Files.writeString(module, """
        {
          :meta { #kind #DEFS }
          :defs {
            #pkg/sub/TIMEOUT :Int 5000
          }
        }
        """);

    Path mainFile = tempDir.resolve("main.stvn_i");
    String mainContent = """
        {
          :meta {
            #name "main"
            #kind #BODY_INCLUDE
          }
          :defs {
            :include [ "module.stvn_d" ]
            :use [ :pkg/sub { #strip } ]
          }
          :type :Int
          :body #TIMEOUT
        }
        """;
    Files.writeString(mainFile, mainContent);

    var res = StvnCompiler.compileToResult(mainContent, mainFile.toString());
    Assertions.assertTrue(res.isSuccess(), "Bare strip on :use must strip #pkg/sub/TIMEOUT to #TIMEOUT: " + res.diagnostics());
    Assertions.assertNotNull(res.orElseThrow());
  }

  @Test
  @DisplayName("Unary #strip across :use targets strips all imported constants to terminal identifiers")
  void testUnaryStripMultipleConstants(@TempDir Path tempDir) throws IOException {
    Path module = tempDir.resolve("module.stvn_d");
    Files.writeString(module, """
        {
          :meta { #kind #DEFS }
          :defs {
            #pkg/sub/TIMEOUT :Int 5000
            #other/RETRIES :Int 3
          }
        }
        """);

    Path mainFile = tempDir.resolve("main.stvn_i");
    String mainContent = """
        {
          :meta {
            #name "main"
            #kind #BODY_INCLUDE
          }
          :defs {
            :include [ "module.stvn_d" ]
            :use [
              :pkg/sub { #strip }
              :other { #strip }
            ]
          }
          :type :Tuple( :Int :Int )
          :body ( #TIMEOUT #RETRIES )
        }
        """;
    Files.writeString(mainFile, mainContent);

    var res = StvnCompiler.compileToResult(mainContent, mainFile.toString());
    Assertions.assertTrue(res.isSuccess(), "Unary strip must strip constants to terminal segments: " + res.diagnostics());
  }

  @Test
  @DisplayName("Parameterized #strip on :use fails with ERR_INVALID_METADATA_FACET and { #strip } on :include fails with E002")
  void testParameterizedStripFailsAtSyntaxGate(@TempDir Path tempDir) throws IOException {
    Path module = tempDir.resolve("module.stvn_d");
    Files.writeString(module, """
        {
          :meta { #kind #DEFS }
          :defs {
            #config/db/POOL_SIZE :Int 10
          }
        }
        """);

    Path mainFile = tempDir.resolve("main.stvn_i");
    String mainContent = """
        {
          :meta {
            #name "main"
            #kind #BODY_INCLUDE
          }
          :defs {
            :include [ "module.stvn_d" ]
            :use [ :config/db { #strip "config/db/" } ]
          }
          :type :Int
          :body #POOL_SIZE
        }
        """;
    Files.writeString(mainFile, mainContent);

    var res = StvnCompiler.compileToResult(mainContent, mainFile.toString());
    Assertions.assertFalse(res.isSuccess(), "Parameterized #strip must fail compilation");
    Assertions.assertTrue(res.diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_INVALID_METADATA_FACET.equals(d.errorCode().orElse(null))));

    String prohibitedIncludeStrip = """
        {
          :meta {
            #name "main"
            #kind #BODY_INCLUDE
          }
          :defs {
            :include [ "module.stvn_d" { #strip } ]
          }
          :type :Int
          :body 10
        }
        """;
    var incRes = StvnCompiler.compileToResult(prohibitedIncludeStrip, mainFile.toString());
    Assertions.assertFalse(incRes.isSuccess());
    Assertions.assertTrue(incRes.diagnostics().stream()
        .anyMatch(d -> "E002".equals(d.canonicalCode().orElse(""))));
  }

  @Test
  @DisplayName("Unmitigated constant collision emits ERR_NAMESPACE_COLLISION")
  void testUnmitigatedConstantCollisionEmitsDiagnostic(@TempDir Path tempDir) throws IOException {
    Path modA = tempDir.resolve("module_a.stvn_d");
    Files.writeString(modA, """
        {
          :meta { #kind #DEFS }
          :defs {
            #PORT :Int 8080
          }
        }
        """);

    Path modB = tempDir.resolve("module_b.stvn_d");
    Files.writeString(modB, """
        {
          :meta { #kind #DEFS }
          :defs {
            #PORT :Int 9090
          }
        }
        """);

    Path mainFile = tempDir.resolve("main.stvn_i");
    String mainContent = """
        {
          :meta {
            #name "main"
            #kind #BODY_INCLUDE
          }
          :defs {
            :include [ "module_a.stvn_d" "module_b.stvn_d" ]
          }
          :type :Int
          :body #PORT
        }
        """;
    Files.writeString(mainFile, mainContent);

    var res = StvnCompiler.compileToResult(mainContent, mainFile.toString());
    Assertions.assertFalse(res.isSuccess(), "Conflicting constants must fail compilation");
    Assertions.assertTrue(res.diagnostics().stream()
        .anyMatch(d -> DiagnosticBag.ERR_NAMESPACE_COLLISION.equals(d.errorCode().orElse(null))));
  }

  @Test
  @DisplayName("Local constant definition evicts raw imported constant")
  void testLocalConstantPriorityEviction(@TempDir Path tempDir) throws IOException {
    Path modA = tempDir.resolve("module_a.stvn_d");
    Files.writeString(modA, """
        {
          :meta { #kind #DEFS }
          :defs {
            #PORT :Int 8080
          }
        }
        """);

    Path mainFile = tempDir.resolve("main.stvn_i");
    String mainContent = """
        {
          :meta {
            #name "main"
            #kind #BODY_INCLUDE
          }
          :defs {
            :include [ "module_a.stvn_d" ]
            #PORT :Int 3000
          }
          :type :Int
          :body #PORT
        }
        """;
    Files.writeString(mainFile, mainContent);

    var res = StvnCompiler.compileToResult(mainContent, mainFile.toString());
    Assertions.assertTrue(res.isSuccess(), "Local constant must evict imported constant");
  }

  @Test
  @DisplayName("StvnSchemaFlattener desugars :use #strip and emits canonical FQNI constants")
  void testFlattenerStripsAndEmitsConstants() {
    Map<String, String> workspace = Map.of(
        "main.stvn_i", """
            {
              :defs {
                :include [ "net.stvn_d" ]
                :use [ :pkg/net { #strip } ]
                :App :Tuple( :Port )
              }
              :type :App
              :body ( #PORT )
            }
            """,
        "net.stvn_d", """
            {
              :meta { #kind #DEFS }
              :defs {
                #pkg/net/PORT :Int 8080
                :pkg/net/Port :Int
              }
            }
            """
    );

    String flattened = StvnSchemaFlattener.flatten(workspace, "main.stvn_i");
    Assertions.assertTrue(flattened.contains("#pkg/net/PORT :Int 8080"),
        "Flattened output must contain canonical #pkg/net/PORT: " + flattened);
    Assertions.assertTrue(flattened.contains(":pkg/net/Port :Int"),
        "Flattened output must contain canonical :pkg/net/Port: " + flattened);
    Assertions.assertTrue(flattened.contains(":App :Tuple(:pkg/net/Port)"),
        "Flattened output must desugar :Port to :pkg/net/Port: " + flattened);
  }
}
