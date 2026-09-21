package org.stvnadore.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.printer.AstPrettyPrinter;

public class StvnCompilerTest {

  @Test
  @DisplayName("TC-COMP-01: Transitive reachability preserves nested nominal references in canonical AST")
  void testTransitiveReachabilityCanonicalOutput() {
    String source = """
        {
          :defs {
            :package :org/stvnadore/finance {
              :Transaction :Tuple(:Int :Float)
              :CreationTime :Int
            }
            :use [ :org/stvnadore/finance { :Transaction :LocalTx } ]
            :A :String
            :T :Tuple(:LocalTx :A)
          }
          :type :T
          :body (
            (1001 49.99)
            "a"
          )
        }
        """;

    var result = StvnCompiler.compileToResult(source);
    Assertions.assertTrue(result.isSuccess(), "Source compilation must succeed: " + result.diagnostics());
    StvnValue ast = result.orElseThrow();

    String canonical = StvnCompiler.toCanonicalString(ast);

    // 1. Assert intermediate definitions are retained
    Assertions.assertTrue(canonical.contains(":org/stvnadore/finance/Transaction"),
        "Must retain transitive dependency :org/stvnadore/finance/Transaction in canonical output: " + canonical);
    Assertions.assertTrue(canonical.contains(":A :String"),
        "Must retain transitive dependency :A in canonical output: " + canonical);
    Assertions.assertTrue(canonical.contains(":T :Tuple(:org/stvnadore/finance/Transaction :A)"),
        "Must rewrite :LocalTx to FQNI inside :T: " + canonical);

    // 2. Assert unreferenced dead code is pruned
    Assertions.assertFalse(canonical.contains(":CreationTime"),
        "Must prune dead code :CreationTime from canonical output: " + canonical);

    // 3. Assert zero raw local alias remains
    Assertions.assertFalse(canonical.contains(":LocalTx"),
        "Must not emit un-desugared local alias :LocalTx: " + canonical);

    // 4. Assert re-compilation round-trip produces clean AST without errors
    var roundTrip = StvnCompiler.compileToResult(canonical);
    Assertions.assertTrue(roundTrip.isSuccess(), "Canonical output must re-compile with zero errors: " + roundTrip.diagnostics());
    Assertions.assertEquals(canonical, StvnCompiler.toCanonicalString(roundTrip.orElseThrow()),
        "Canonical serialization must be idempotent");
  }

  @Test
  @DisplayName("TC-COMP-02: Multi-tier transitive chain retention (D -> C -> B -> A)")
  void testDeepTransitiveChainRetention() {
    String source = """
        {
          :defs {
            :D :Int
            :C :Tuple(:D)
            :B :Tuple(:C)
            :A :Tuple(:B)
            :Unused :String
          }
          :type :A
          :body (((42)))
        }
        """;

    var result = StvnCompiler.compileToResult(source);
    Assertions.assertTrue(result.isSuccess(), "Compilation must succeed: " + result.diagnostics());
    String canonical = StvnCompiler.toCanonicalString(result.orElseThrow());

    Assertions.assertTrue(canonical.contains(":D :Int"));
    Assertions.assertTrue(canonical.contains(":C :Tuple(:D)"));
    Assertions.assertTrue(canonical.contains(":B :Tuple(:C)"));
    Assertions.assertTrue(canonical.contains(":A :Tuple(:B)"));
    Assertions.assertFalse(canonical.contains(":Unused"));

    var recompiled = StvnCompiler.compileToResult(canonical);
    Assertions.assertTrue(recompiled.isSuccess());
  }

  @Test
  @DisplayName("TC-COMP-03: Pretty printer preserves transitive definitions with 2-space indentation")
  void testPrettyPrinterTransitiveParity() {
    String source = """
        {
          :defs {
            :package :org/example/geo {
              :Coord :Float
            }
            :use [ :org/example/geo { #strip } ]
            :Point :Tuple(:Coord :Coord)
          }
          :type :Point
          :body (12.34 56.78)
        }
        """;

    var result = StvnCompiler.compileToResult(source);
    Assertions.assertTrue(result.isSuccess());
    String pretty = AstPrettyPrinter.print(result.orElseThrow());

    Assertions.assertTrue(pretty.contains(":org/example/geo/Coord :Float"));
    Assertions.assertTrue(pretty.contains(":Point :Tuple(:org/example/geo/Coord :org/example/geo/Coord)"));
  }

  @Test
  @DisplayName("TC-COMP-04: Sum types with duplicate branches compile cleanly with explicit tags")
  void testDuplicateBranchesCompileWithExplicitTags() {
    String source = """
        {
          :defs {
            :MyEither :Either( :Int :Int )
            :MyUnion  :Union( :Int :Int )
            :RootPayload :Tuple( :MyEither :MyEither :MyUnion :MyUnion )
          }
          :type :RootPayload
          :body (
            #Left 42
            #Right 84
            #1 100
            #2 200
          )
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    Assertions.assertTrue(result.isSuccess(), "Compilation must succeed with explicit tags: " + result.diagnostics());
    Assertions.assertTrue(result.diagnostics().isEmpty());
  }

  @Test
  @DisplayName("TC-COMP-05: Untagged payload matching duplicate Either branches fails with ERR_AMBIGUOUS_SUM_INFERENCE")
  void testUntaggedEitherDuplicateBranchesFailsClosed() {
    String source = """
        {
          :defs {
            :MyEither :Either( :Int :Int )
          }
          :type :MyEither
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    Assertions.assertTrue(result.hasErrors());
    Assertions.assertTrue(
        result.diagnostics().stream().anyMatch(d ->
            org.stvnadore.core.validation.DiagnosticBag.ERR_AMBIGUOUS_SUM_INFERENCE.equals(d.errorCode().orElse(null))
        ),
        "Must emit ERR_AMBIGUOUS_SUM_INFERENCE: " + result.diagnostics()
    );
  }

  @Test
  @DisplayName("TC-COMP-06: Untagged payload matching duplicate Union branches fails with ERR_AMBIGUOUS_SUM_INFERENCE")
  void testUntaggedUnionDuplicateBranchesFailsClosed() {
    String source = """
        {
          :defs {
            :MyUnion :Union( :Int :Int )
          }
          :type :MyUnion
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    Assertions.assertTrue(result.hasErrors());
    Assertions.assertTrue(
        result.diagnostics().stream().anyMatch(d ->
            org.stvnadore.core.validation.DiagnosticBag.ERR_AMBIGUOUS_SUM_INFERENCE.equals(d.errorCode().orElse(null))
        ),
        "Must emit ERR_AMBIGUOUS_SUM_INFERENCE: " + result.diagnostics()
    );
  }

  @Test
  @DisplayName("TC-COMP-07: Tuple arity underflow clamps coordinate strictly to closing delimiter ')'")
  void testTupleArityUnderflowClampsToClosingDelimiter() {
    String source = """
        {
          :type :Tuple(:Int :String :Boolean)
          :body (
            42
            "test"
          )
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    Assertions.assertTrue(result.hasErrors());
    var diag = result.diagnostics().stream()
        .filter(d -> "TUPLE_ARITY_MISMATCH".equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Expected TUPLE_ARITY_MISMATCH diagnostic"));

    int rparenOffset = source.lastIndexOf(')');
    Assertions.assertEquals(rparenOffset, diag.startOffset(), "startOffset must clamp to closing delimiter ')'");
    Assertions.assertEquals(rparenOffset + 1, diag.endOffset(), "endOffset must clamp to closing delimiter ')' + 1");
    Assertions.assertTrue(diag.message().contains("Tuple arity mismatch: Expected 3 elements, got 2"));
  }

  @Test
  @DisplayName("TC-COMP-08: Tuple arity overflow clamps coordinate across extraneous elements")
  void testTupleArityOverflowClampsToExtraneousElements() {
    String source = """
        {
          :type :Tuple(:Int :String)
          :body (
            42
            "valid"
            #TRUE
            100
          )
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    Assertions.assertTrue(result.hasErrors());
    var diag = result.diagnostics().stream()
        .filter(d -> "TUPLE_ARITY_MISMATCH".equals(d.errorCode().orElse(null)))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Expected TUPLE_ARITY_MISMATCH diagnostic"));

    int expectedStart = source.indexOf("#TRUE");
    int expectedEnd = source.indexOf("100") + "100".length();
    Assertions.assertEquals(expectedStart, diag.startOffset(), "startOffset must match first extraneous element");
    Assertions.assertEquals(expectedEnd, diag.endOffset(), "endOffset must match end of last extraneous element");
    Assertions.assertTrue(diag.message().contains("Tuple arity mismatch: Expected 2 elements, got 4"));
  }

  @Test
  @DisplayName("TC-COMP-09: Bare '#' followed by newline clamps strictly to single '#' without newline spillover")
  void testBareHashLexerErrorClampsWithoutNewlineSpillover() {
    String source = "{\n  :type :Int\n  :body #\n}\n";
    var result = StvnCompiler.compileToResult(source);
    Assertions.assertTrue(result.hasErrors());
    var diag = result.diagnostics().getFirst();
    int hashOffset = source.indexOf('#');
    Assertions.assertEquals(hashOffset, diag.startOffset(), "startOffset must pin directly to '#'");
    Assertions.assertEquals(hashOffset + 1, diag.endOffset(), "endOffset must pin directly to '#' + 1");
    Assertions.assertEquals(3, diag.line());
    Assertions.assertFalse(diag.message().contains("\n"), "Sanitized diagnostic message must not contain raw '\\n'");
    Assertions.assertFalse(diag.message().contains("\r"), "Sanitized diagnostic message must not contain raw '\\r'");
    Assertions.assertFalse(diag.message().contains("\\n"), "Sanitized diagnostic message must not contain escaped '\\\\n'");
    Assertions.assertFalse(diag.message().contains("\\r"), "Sanitized diagnostic message must not contain escaped '\\\\r'");
    Assertions.assertTrue(diag.message().contains("token recognition error at: '#'"), "Must display clean token text '#'");
  }

  @Test
  @DisplayName("TC-COMP-10: Tuple child error isolation preserves element count and suppresses arity underflow on ')'")
  void testTupleChildErrorIsolationPreservesElementCountAndSuppressesArityUnderflow() {
    String source = """
        {
          :defs {
            :UnionLikeEitherC :Union(:Int :Int)
          }
          :type :Tuple(:Int :Int :UnionLikeEitherC :Int :Int :Int)
          :body (
            1
            2
            #3 3
            4
            5
            6
          )
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    Assertions.assertTrue(result.hasErrors());
    Assertions.assertEquals(1, result.diagnostics().size(), "Must isolate error strictly to malformed child without cascading diagnostics");
    var diag = result.diagnostics().getFirst();
    Assertions.assertTrue(diag.message().contains("Union variant tag '#3' exceeds branch count (2)"));
    int tagOffset = source.indexOf("#3");
    Assertions.assertEquals(tagOffset, diag.startOffset(), "Diagnostic must pin strictly to UNION_TAG_PREFIX start");
    Assertions.assertEquals(tagOffset + 2, diag.endOffset(), "Diagnostic must pin strictly to UNION_TAG_PREFIX stop + 1");
    Assertions.assertFalse(
        result.diagnostics().stream().anyMatch(d -> "TUPLE_ARITY_MISMATCH".equals(d.errorCode().orElse(null))),
        "Must not emit spurious TUPLE_ARITY_MISMATCH on closing delimiter ')'"
    );
    Assertions.assertTrue(result.document().isPresent());
    Assertions.assertInstanceOf(org.stvnadore.core.ir.StvnValue.StvnTuple.class, result.document().get());
    var tuple = (org.stvnadore.core.ir.StvnValue.StvnTuple) result.document().get();
    Assertions.assertEquals(6, tuple.elements().size(), "Tuple must maintain full element cardinality");
    Assertions.assertInstanceOf(org.stvnadore.core.ir.StvnValue.StvnError.class, tuple.elements().get(2), "Malformed child element must be recorded as StvnError");
  }
}
