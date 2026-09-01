package org.stvnadore.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity;
import org.stvnadore.core.binary.StvnBinaryEncoder;
import org.stvnadore.core.io.CanonicalStvnWriter;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.ir.StvnValue.*;
import org.stvnadore.core.printer.StvnTextPrinter;

import java.io.StringWriter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Comprehensive verification suite for the Accumulating Semantic Diagnostic Engine & Multi-Error Recovery Pipeline.
 *
 * @since 1.3.0
 */
public class StvnDiagnosticAccumulationTest {

  @Test
  @DisplayName("Sequence multi-element diagnostic accumulation and error node recovery")
  void testSequenceMultiElementDiagnosticAccumulation() {
    String input = """
        {
          :type :Seq(:Int8)
          :body [ 10 300 20 400 30 ]
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);

    Assertions.assertFalse(result.isSuccess(), "Compilation should not be clean success due to integer overflows");
    Assertions.assertTrue(result.hasErrors(), "Result must have error diagnostics");
    Assertions.assertTrue(result.isRecoveredPartialAst(), "Result must contain recovered partial AST");
    Assertions.assertTrue(result.document().isPresent(), "Document must be present for recovered AST");

    List<StvnDiagnostic> diagnostics = result.diagnostics();
    Assertions.assertEquals(2, diagnostics.size(), "Expected 2 overflow diagnostics for 300 and 400");
    Assertions.assertEquals(DiagnosticSeverity.ERROR, diagnostics.get(0).severity());
    Assertions.assertEquals(DiagnosticSeverity.ERROR, diagnostics.get(1).severity());

    StvnValue doc = result.document().get();
    Assertions.assertInstanceOf(StvnSeq.class, doc);
    StvnSeq seq = (StvnSeq) doc;
    Assertions.assertEquals(5, seq.elements().size());

    Assertions.assertInstanceOf(StvnInteger.class, seq.elements().get(0));
    Assertions.assertInstanceOf(StvnError.class, seq.elements().get(1));
    Assertions.assertInstanceOf(StvnInteger.class, seq.elements().get(2));
    Assertions.assertInstanceOf(StvnError.class, seq.elements().get(3));
    Assertions.assertInstanceOf(StvnInteger.class, seq.elements().get(4));

    StvnError err1 = (StvnError) seq.elements().get(1);
    Assertions.assertEquals("300", err1.rawText().trim());
    Assertions.assertFalse(err1.diagnostics().isEmpty());
  }

  @Test
  @DisplayName("Set uniqueness diagnostic accumulation without aborting sibling validation")
  void testSetUniquenessDiagnosticAccumulation() {
    String input = """
        {
          :type :Set(:Int32)
          :body [ 10 20 10 30 20 40 ]
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);

    Assertions.assertFalse(result.isSuccess());
    Assertions.assertTrue(result.hasErrors());
    Assertions.assertTrue(result.isRecoveredPartialAst());

    List<StvnDiagnostic> diags = result.diagnostics();
    Assertions.assertEquals(2, diags.size(), "Expected 2 collision diagnostics for duplicate elements 10 and 20");
    Assertions.assertEquals("DUPLICATE_SET_ELEMENT", diags.get(0).errorCode().orElse(""));
    Assertions.assertEquals("DUPLICATE_SET_ELEMENT", diags.get(1).errorCode().orElse(""));

    StvnValue doc = result.document().get();
    Assertions.assertInstanceOf(StvnSet.class, doc);
    StvnSet set = (StvnSet) doc;
    Assertions.assertEquals(4, set.elements().size());
  }

  @Test
  @DisplayName("Tuple arity and positional diagnostic accumulation")
  void testTupleArityAndPositionalDiagnosticAccumulation() {
    String input = """
        {
          :type :Tuple(:Int8 :String :Boolean)
          :body ( 300 "valid" #TRUE 500 )
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);

    Assertions.assertFalse(result.isSuccess());
    Assertions.assertTrue(result.hasErrors());

    List<StvnDiagnostic> diags = result.diagnostics();
    Assertions.assertTrue(diags.stream().anyMatch(d -> "TUPLE_ARITY_MISMATCH".equals(d.errorCode().orElse(""))),
        "Must record TUPLE_ARITY_MISMATCH diagnostic");

    StvnValue doc = result.document().get();
    Assertions.assertInstanceOf(StvnTuple.class, doc);
    StvnTuple tuple = (StvnTuple) doc;
    Assertions.assertEquals(4, tuple.elements().size());
    Assertions.assertInstanceOf(StvnError.class, tuple.elements().get(0));
    Assertions.assertInstanceOf(StvnString.class, tuple.elements().get(1));
    Assertions.assertInstanceOf(StvnBoolean.class, tuple.elements().get(2));
  }

  @Test
  @DisplayName("Map independent key, value, and collision diagnostic accumulation")
  void testMapKeyAndValueDiagnosticAccumulation() {
    String input = """
        {
          :type :Map(:Int8 :Int8)
          :body {
            [ 1 10 ]
            [ 200 20 ]
            [ 3 300 ]
            [ 1 40 ]
          }
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);

    Assertions.assertFalse(result.isSuccess());
    Assertions.assertTrue(result.hasErrors());

    List<StvnDiagnostic> diags = result.diagnostics();
    Assertions.assertTrue(diags.size() >= 3, "Expected at least 3 diagnostics: invalid key 200, invalid value 300, duplicate key 1");
    Assertions.assertTrue(diags.stream().anyMatch(d -> "DUPLICATE_MAP_KEY".equals(d.errorCode().orElse(""))));

    StvnValue doc = result.document().get();
    Assertions.assertInstanceOf(StvnMap.class, doc);
  }

  @Test
  @DisplayName("Invertible Map value collision accumulation")
  void testInvertibleMapValueCollisionAccumulation() {
    String input = """
        {
          :type :MapInv(:String :Int32)
          :body {
            [ "a" 10 ]
            [ "b" 20 ]
            [ "c" 10 ]
          }
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);

    Assertions.assertFalse(result.isSuccess());
    Assertions.assertTrue(result.hasErrors());

    List<StvnDiagnostic> diags = result.diagnostics();
    Assertions.assertTrue(diags.stream().anyMatch(d -> "DUPLICATE_INVERTED_MAP_VALUE".equals(d.errorCode().orElse(""))));
  }

  @Test
  @DisplayName("Sum types (Option, Either, Union) error accumulation and StvnError wrapping")
  void testSumOptionEitherUnionDiagnosticAccumulation() {
    String unionOverflowInput = """
        {
          :defs {
            :MyUnion :Union(:Int32 :String)
          }
          :type :MyUnion
          :body #5 42
        }
        """;

    StvnCompilationResult<StvnValue> unionRes = StvnCompiler.compileToResult(unionOverflowInput);
    Assertions.assertFalse(unionRes.isSuccess());
    Assertions.assertTrue(unionRes.hasErrors());
    Assertions.assertTrue(unionRes.diagnostics().stream().anyMatch(d -> "UNION_BRANCH_OVERFLOW".equals(d.errorCode().orElse(""))));
    Assertions.assertInstanceOf(StvnUnion.class, unionRes.document().get());
    StvnUnion union = (StvnUnion) unionRes.document().get();
    Assertions.assertInstanceOf(StvnError.class, union.value());

    String eitherMismatchInput = """
        {
          :type :Either(:Int8 :String)
          :body #Right 300
        }
        """;

    StvnCompilationResult<StvnValue> eitherRes = StvnCompiler.compileToResult(eitherMismatchInput);
    Assertions.assertFalse(eitherRes.isSuccess());
    Assertions.assertTrue(eitherRes.hasErrors());
    Assertions.assertInstanceOf(StvnEither.class, eitherRes.document().get());
    StvnEither either = (StvnEither) eitherRes.document().get();
    Assertions.assertInstanceOf(StvnError.class, either.value());
  }

  @Test
  @DisplayName("DiagnosticBag capacity threshold enforcement and sentinel warning generation")
  void testDiagnosticBagCapacityAndSentinelWarning() {
    StringBuilder sb = new StringBuilder();
    sb.append("{\n  :type :Seq(:Int8)\n  :body [ ");
    for (int i = 0; i < 150; i++) {
      sb.append("500 ");
    }
    sb.append("]\n}\n");

    StvnParserConfig config = new StvnParserConfig(false, 50);
    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(sb.toString(), null, config);

    Assertions.assertTrue(result.hasErrors());
    Assertions.assertTrue(result.hasWarnings());

    List<StvnDiagnostic> diags = result.diagnostics();
    Assertions.assertEquals(51, diags.size(), "Expected 50 error diagnostics + 1 sentinel warning");
    Assertions.assertEquals(DiagnosticSeverity.WARNING, diags.getLast().severity());
    Assertions.assertEquals("STVN_DIAG_LIMIT_EXCEEDED", diags.getLast().errorCode().orElse(""));
  }

  @Test
  @DisplayName("Monadic compiler API workflow and functional transformations")
  void testMonadicCompilerApiWorkflow() {
    String cleanInput = """
        {
          :type :Int32
          :body 42
        }
        """;

    StvnCompilationResult<StvnValue> successRes = StvnCompiler.compileToResult(cleanInput);
    Assertions.assertTrue(successRes.isSuccess());
    Assertions.assertFalse(successRes.hasErrors());
    Assertions.assertFalse(successRes.hasWarnings());
    Assertions.assertFalse(successRes.isRecoveredPartialAst());
    Assertions.assertNotNull(successRes.orElseThrow());

    AtomicBoolean executed = new AtomicBoolean(false);
    successRes.ifSuccess(doc -> executed.set(true));
    Assertions.assertTrue(executed.get());

    StvnCompilationResult<Integer> mapped = successRes.map(doc -> ((StvnInteger) doc).value().intValue());
    Assertions.assertEquals(42, mapped.document().orElse(0));

    String errorInput = """
        {
          :type :Int8
          :body 999
        }
        """;

    StvnCompilationResult<StvnValue> failRes = StvnCompiler.compileToResult(errorInput);
    Assertions.assertFalse(failRes.isSuccess());
    Assertions.assertTrue(failRes.hasErrors());
    Assertions.assertThrows(RuntimeException.class, failRes::orElseThrow);
  }

  @Test
  @DisplayName("Binary encoder rejects ASTs containing unrecovered StvnError nodes")
  void testBinaryEncoderRejectsUnrecoveredErrors() {
    String input = """
        {
          :type :Seq(:Int8)
          :body [ 10 300 20 ]
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.document().isPresent());
    StvnValue partialAst = result.document().get();

    var encoder = new StvnBinaryEncoder(true, new org.stvnadore.core.binary.SchemaIdentityStrategy.UniversalDefault());
    Assertions.assertThrows(IllegalStateException.class, () -> {
      encoder.encode(partialAst);
    });
  }

  @Test
  @DisplayName("Canonical writer and pattern printer render StvnError gracefully without throwing")
  void testPrintersHandleStvnErrorNodesGracefully() throws Exception {
    String input = """
        {
          :type :Seq(:Int8)
          :body [ 10 300 20 ]
        }
        """;

    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(input);
    Assertions.assertTrue(result.document().isPresent());
    StvnValue partialAst = result.document().get();

    StringWriter sw = new StringWriter();
    CanonicalStvnWriter writer = new CanonicalStvnWriter();
    Assertions.assertDoesNotThrow(() -> writer.print(partialAst, sw));
    String canonicalOut = sw.toString();
    Assertions.assertTrue(canonicalOut.contains("300"));

    String printedOut = writer.printToString(partialAst);
    Assertions.assertTrue(printedOut.contains("300"));
  }
}
