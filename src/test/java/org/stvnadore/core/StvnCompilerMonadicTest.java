package org.stvnadore.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import org.stvnadore.core.ir.StvnValue;

class StvnCompilerMonadicTest {

  @Test
  void testSuccessfulAnalysis() {
    var source = "{ :type :Int32 :body 42 }";
    var result = StvnCompiler.analyze(source);
    Assertions.assertTrue(result.value().isPresent());
    Assertions.assertTrue(result.diagnostics().isEmpty());
    Assertions.assertEquals("{:type :Int32 :body 42}", StvnCompiler.toCanonicalString(result.value().get()));
  }

  @Test
  void testEmptyDocumentBody() {
    var source = "{ }";
    var result = StvnCompiler.analyze(source);
    Assertions.assertTrue(result.value().isEmpty());
    Assertions.assertTrue(result.diagnostics().isEmpty());
  }

  @Test
  void testSyntaxErrorMissingBrace() {
    var source = "{ :type :Int32 :body 42";
    var result = StvnCompiler.analyze(source);
    Assertions.assertTrue(result.value().isEmpty());
    Assertions.assertFalse(result.diagnostics().isEmpty());
    
    var diagnostic = result.diagnostics().get(0);
    Assertions.assertTrue(diagnostic.message().contains("STVN Syntax Error"));
    Assertions.assertEquals(1, diagnostic.line());
    Assertions.assertTrue(diagnostic.column() > 0);
  }

  @Test
  void testSyntaxErrorInvalidToken() {
    var source = "{ :type :Int32 :body @ }";
    var result = StvnCompiler.analyze(source);
    Assertions.assertTrue(result.value().isEmpty());
    Assertions.assertFalse(result.diagnostics().isEmpty());

    var diagnostic = result.diagnostics().get(0);
    Assertions.assertTrue(diagnostic.message().contains("STVN Syntax Error") || diagnostic.message().contains("token recognition error"));
    Assertions.assertEquals(1, diagnostic.line());
    Assertions.assertEquals(21, diagnostic.column());
    Assertions.assertEquals(21, diagnostic.startOffset());
  }

  @Test
  void testSemanticErrorInvalidBooleanCasing() {
    var source = """
        {
          :type :Boolean
          :body #False
        }
        """;
    var result = StvnCompiler.analyze(source);
    Assertions.assertTrue(result.value().isEmpty());
    Assertions.assertFalse(result.diagnostics().isEmpty());

    var diagnostic = result.diagnostics().get(0);
    Assertions.assertTrue(diagnostic.message().contains("Invalid boolean casing") || diagnostic.message().contains("Invalid boolean literal casing"));
    Assertions.assertEquals(3, diagnostic.line());
    Assertions.assertEquals(8, diagnostic.column());
    Assertions.assertEquals(source.indexOf("#False"), diagnostic.startOffset());
    Assertions.assertEquals(source.indexOf("#False") + "#False".length(), diagnostic.endOffset());
    Assertions.assertNotNull(diagnostic.cause());
  }

  @Test
  void testVopInvariantAssertion() {
    var diagnostic = new StvnDiagnostic("Error message", 1, 1, -1, -1, null);
    Assertions.assertThrows(IllegalStateException.class, () -> {
      new StvnAnalysisResult<>(Optional.of("some value"), List.of(diagnostic));
    });
  }

  @Test
  void testDiagnosticNullMessageThrows() {
    Assertions.assertThrows(NullPointerException.class, () -> {
      new StvnDiagnostic(null, 1, 1, -1, -1, null);
    });
  }

  @Test
  void testAnalysisResultNullParametersThrow() {
    Assertions.assertThrows(NullPointerException.class, () -> {
      new StvnAnalysisResult<>(null, List.of());
    });
    Assertions.assertThrows(NullPointerException.class, () -> {
      new StvnAnalysisResult<>(Optional.empty(), null);
    });
  }

  @Test
  void testUnionBranchTypeMismatchExactCoordinates() {
    var source = """
        {
          :type :Union(:Int32 :String)
          :body #1 1024.0
        }
        """;
    var result = StvnCompiler.analyze(source);
    Assertions.assertTrue(result.value().isEmpty());
    Assertions.assertFalse(result.diagnostics().isEmpty());

    var diagnostic = result.diagnostics().get(0);
    Assertions.assertTrue(diagnostic.message().contains("Type mismatch: Expected integer, got float"));
    int expectedStart = source.indexOf("1024.0");
    int expectedEnd = expectedStart + "1024.0".length();
    Assertions.assertEquals(expectedStart, diagnostic.startOffset());
    Assertions.assertEquals(expectedEnd, diagnostic.endOffset());
  }
}

