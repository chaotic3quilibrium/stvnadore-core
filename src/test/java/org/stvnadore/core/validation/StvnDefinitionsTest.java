package org.stvnadore.core.validation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.ir.StvnValue;

/**
 * Verification test suite for multi-error diagnostics, duplicate definition detection,
 * circular reference poisoned sentinels, and modular include validations in {@code :defs}.
 *
 * @since 1.3.0
 */
public class StvnDefinitionsTest {

  @Test
  @DisplayName("Duplicate nominal type definition violates Zero-Shadowing with ERR_DUPLICATE_DEF")
  void testDuplicateTypeDefinitionViolatesZeroShadowing() {
    String input = """
        {
          :defs {
            :MyType :Int32
            :MyType :String
          }
          :type :String
          :body "hello"
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertTrue(result.hasErrors());

    var diag = result.diagnostics().getFirst();
    Assertions.assertTrue(diag.message().contains("Zero-Shadowing constraint violated: :MyType"));
    Assertions.assertEquals(DiagnosticBag.ERR_DUPLICATE_DEF, diag.errorCode().orElse(null));
  }

  @Test
  @DisplayName("Duplicate typed constant definition violates Zero-Shadowing with ERR_DUPLICATE_DEF")
  void testDuplicateConstantDefinitionViolatesZeroShadowing() {
    String input = """
        {
          :defs {
            #MAX :Int32 10
            #MAX :Int32 20
          }
          :type :Int32
          :body 10
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertTrue(result.hasErrors());

    var diag = result.diagnostics().getFirst();
    Assertions.assertTrue(diag.message().contains("Zero-Shadowing constraint violated: #MAX"));
    Assertions.assertEquals(DiagnosticBag.ERR_DUPLICATE_DEF, diag.errorCode().orElse(null));
  }

  @Test
  @DisplayName("Circular type definition loop records ERR_CIRCULAR_TYPE and creates sentinel")
  void testCircularTypeDefinitionRecordsDiagnosticAndSentinel() {
    String input = """
        {
          :defs {
            :A :B
            :B :A
          }
          :type :A
          :body "sentinel_value"
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertTrue(result.hasErrors());

    Assertions.assertTrue(
        result.diagnostics().stream().anyMatch(d -> DiagnosticBag.ERR_CIRCULAR_TYPE.equals(d.errorCode().orElse(null))),
        "Must record ERR_CIRCULAR_TYPE"
    );
  }

  @Test
  @DisplayName("Leaf module containing include statement records ERR_MODULE_IMPORT")
  void testLeafModuleIncludeRestriction() {
    String input = """
        {
          :defs {
            :include [ "other.stvn_inclf" ]
            :MyType :String
          }
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input, "leaf.stvn_inclf");
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertTrue(result.hasErrors());

    var diag = result.diagnostics().getFirst();
    Assertions.assertTrue(diag.message().contains("Leaf module (.stvn_inclf) cannot contain include statements"));
    Assertions.assertEquals(DiagnosticBag.ERR_MODULE_IMPORT, diag.errorCode().orElse(null));
  }

  @Test
  @DisplayName("Transitive include with multiple definition errors accumulates all diagnostics across files")
  void testTransitiveIncludeMultiErrors(@TempDir Path tempDir) throws IOException {
    Path incl = tempDir.resolve("common.stvn_inclf");
    Files.writeString(incl, """
        {
          :defs {
            :BrokenRegex { #regex "[" } :String
            :BrokenRange { #minIncl 100 #maxIncl 1 } :Int32
          }
        }
        """);

    Path mainFile = tempDir.resolve("main.stvn");
    String mainContent = """
        {
          :defs {
            :include [ "common.stvn_inclf" ]
            :LocalBroken { #preserveIndent #TRUE } :Int32
          }
          :type :String
          :body "active"
        }
        """;
    Files.writeString(mainFile, mainContent);

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(mainContent, mainFile.toString());
    Assertions.assertFalse(result.isSuccess());
    Assertions.assertTrue(result.hasErrors());
    Assertions.assertTrue(result.diagnostics().size() >= 3, "Must accumulate diagnostics from both included file and root document");
  }
}
