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
    Path module = tempDir.resolve("module.stvn_incl");
    Files.writeString(module, """
        {
          :defs {
            #pkg/sub/TIMEOUT :Int32 5000
          }
        }
        """);

    Path mainFile = tempDir.resolve("main.stvn");
    String mainContent = """
        {
          :defs {
            :include [ "module.stvn_incl" { #strip } ]
          }
          :type :Int32
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
    Path module = tempDir.resolve("module.stvn_incl");
    Files.writeString(module, """
        {
          :defs {
            #pkg/sub/TIMEOUT :Int32 5000
            #other/RETRIES :Int32 3
          }
        }
        """);

    Path mainFile = tempDir.resolve("main.stvn");
    String mainContent = """
        {
          :defs {
            :include [ "module.stvn_incl" { #strip } ]
          }
          :type :Tuple( :Int32 :Int32 )
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
    Path module = tempDir.resolve("module.stvn_incl");
    Files.writeString(module, """
        {
          :defs {
            #config/db/POOL_SIZE :Int32 10
          }
        }
        """);

    Path mainFile = tempDir.resolve("main.stvn");
    String mainContent = """
        {
          :defs {
            :include [ "module.stvn_incl" { #strip "config/db/" } ]
          }
          :type :Int32
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
    Path modA = tempDir.resolve("module_a.stvn_incl");
    Files.writeString(modA, """
        {
          :defs {
            #PORT :Uint16 8080
          }
        }
        """);

    Path modB = tempDir.resolve("module_b.stvn_incl");
    Files.writeString(modB, """
        {
          :defs {
            #PORT :Uint16 9090
          }
        }
        """);

    Path mainFile = tempDir.resolve("main.stvn");
    String mainContent = """
        {
          :defs {
            :include [ "module_a.stvn_incl" "module_b.stvn_incl" ]
          }
          :type :Uint16
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
    Path modA = tempDir.resolve("module_a.stvn_incl");
    Files.writeString(modA, """
        {
          :defs {
            #PORT :Uint16 8080
          }
        }
        """);

    Path mainFile = tempDir.resolve("main.stvn");
    String mainContent = """
        {
          :defs {
            :include [ "module_a.stvn_incl" ]
            #PORT :Uint16 3000
          }
          :type :Uint16
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
        "main.stvn", """
            {
              :defs {
                :include [ "net.stvn_incl" { #strip } ]
                :App :Tuple( :Port )
              }
              :type :App
              :body ( #PORT )
            }
            """,
        "net.stvn_incl", """
            {
              :defs {
                #pkg/net/PORT :Uint16 8080
                :pkg/net/Port :Uint16
              }
            }
            """
    );

    String flattened = StvnSchemaFlattener.flatten(workspace, "main.stvn");
    Assertions.assertTrue(flattened.contains("#PORT :Uint16 8080"),
        "Flattened output must contain stripped #PORT: " + flattened);
    Assertions.assertTrue(flattened.contains(":Port :Uint16"),
        "Flattened output must contain stripped :Port: " + flattened);
  }
}
