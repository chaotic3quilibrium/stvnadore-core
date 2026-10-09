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
  @DisplayName("Bare #strip imports strip constant path up to terminal slash preserving '#' sigil")
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
          :defs {
            :include [ "module.stvn_d" { #strip } ]
          }
          :type :Int
          :body #TIMEOUT
        }
        """;
    Files.writeString(mainFile, mainContent);

    var res = StvnCompiler.compileToResult(mainContent, mainFile.toString());
    Assertions.assertTrue(res.isSuccess(), "Bare strip must strip #pkg/sub/TIMEOUT to #TIMEOUT: " + res.diagnostics());
    Assertions.assertNotNull(res.orElseThrow());
  }

  @Test
  @DisplayName("Unary #strip strips all imported constants to terminal identifiers")
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
          :defs {
            :include [ "module.stvn_d" { #strip } ]
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
  @DisplayName("Parameterized #strip fails at syntax gate")
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
          :defs {
            :include [ "module.stvn_d" { #strip "config/db/" } ]
          }
          :type :Int
          :body #POOL_SIZE
        }
        """;
    Files.writeString(mainFile, mainContent);

    var res = StvnCompiler.compileToResult(mainContent, mainFile.toString());
    Assertions.assertFalse(res.isSuccess(), "Parameterized #strip must fail at parser gate");
    Assertions.assertTrue(res.diagnostics().stream()
        .anyMatch(d -> "STVN_SYNTAX_ERROR".equals(d.errorCode().orElse(null))));
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
  @DisplayName("StvnSchemaFlattener strips and emits constants canonically")
  void testFlattenerStripsAndEmitsConstants() {
    Map<String, String> workspace = Map.of(
        "main.stvn_i", """
            {
              :defs {
                :include [ "net.stvn_d" { #strip } ]
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
    Assertions.assertTrue(flattened.contains("#PORT :Int 8080"),
        "Flattened output must contain stripped #PORT: " + flattened);
    Assertions.assertTrue(flattened.contains(":Port :Int"),
        "Flattened output must contain stripped :Port: " + flattened);
  }
}
