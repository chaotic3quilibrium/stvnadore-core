package org.stvnadore.core.printer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ir.StvnValue;

import java.io.StringWriter;

/**
 * Comprehensive verification tests for canonical AST printers (AstPrettyPrinter, AstCompactPrinter).
 */
class AstPrinterTest {

  @Test
  @DisplayName("AstPrettyPrinter formats with 2-space indentation and long-form keywords by default")
  void testAstPrettyPrinterDefault2Space() {
    String source = """
        {
          :type :Tuple(:Boolean :Option(:Boolean))
          :body (
            #TRUE
            #None
          )
        }
        """;
    StvnValue ast = StvnCompiler.compile(source).orElseThrow();
    String printed = AstPrettyPrinter.print(ast);

    // Verify 2-space indentation standard
    Assertions.assertTrue(printed.contains("  :type :Tuple(:Boolean :Option(:Boolean))\n  :body (#TRUE #None)"),
        "Output must use 2-space indentation:\n" + printed);

    // Verify long-form keyword emission
    Assertions.assertTrue(printed.contains("#TRUE"), "Output must contain #TRUE");
    Assertions.assertTrue(printed.contains("#None"), "Output must contain #None");

    // Verify strict zero-tab invariant
    Assertions.assertFalse(printed.contains("\t"), "Output must not contain tab characters");

    // Verify round-trip compilation
    StvnValue roundTrip = StvnCompiler.compile(printed).orElseThrow();
    Assertions.assertEquals(ast, roundTrip, "Round-trip AST must be mathematically identical");
  }

  @Test
  @DisplayName("AstPrettyPrinter accepts configurable indentation width")
  void testAstPrettyPrinterConfigurableIndent() {
    String source = "{ :type :Int :body 42 }";
    StvnValue ast = StvnCompiler.compile(source).orElseThrow();

    String printed4 = AstPrettyPrinter.print(ast, 4);
    Assertions.assertTrue(printed4.contains("    :type :Int\n    :body 42"),
        "Output must use 4-space indentation when specified:\n" + printed4);

    String printed0 = AstPrettyPrinter.print(ast, 0);
    Assertions.assertFalse(printed0.contains("    "), "Output with 0 indent must have no leading indentation");

    // Negative indentation must throw IllegalArgumentException
    Assertions.assertThrows(IllegalArgumentException.class, () -> new AstPrettyPrinter(-1));
  }

  @Test
  @DisplayName("AstCompactPrinter emits single-line minimal layout with short-form keywords")
  void testAstCompactPrinter() {
    String source = """
        {
          :type :Tuple(:Boolean :Option(:Boolean))
          :body (
            #TRUE
            #None
          )
        }
        """;
    StvnValue ast = StvnCompiler.compile(source).orElseThrow();
    String compact = AstCompactPrinter.print(ast);

    // Verify compact single-line layout
    Assertions.assertEquals("{:type :Tuple(:Boolean :Option(:Boolean)) :body (#T #N)}", compact);

    // Verify short-form keywords
    Assertions.assertTrue(compact.contains("#T"), "Output must contain #T");
    Assertions.assertTrue(compact.contains("#N"), "Output must contain #N");

    // Verify zero-tab invariant
    Assertions.assertFalse(compact.contains("\t"), "Output must not contain tab characters");

    // Verify round-trip compilation
    StvnValue roundTrip = StvnCompiler.compile(compact).orElseThrow();
    Assertions.assertEquals(ast, roundTrip, "Round-trip AST must be mathematically identical");
  }

  @Test
  @DisplayName("AstPrettyPrinter and AstCompactPrinter support Writer destinations")
  void testWriterDestinations() throws Exception {
    String source = "{ :type :String :body \"Test\" }";
    StvnValue ast = StvnCompiler.compile(source).orElseThrow();

    var prettySw = new StringWriter();
    AstPrettyPrinter.print(ast, prettySw);
    Assertions.assertFalse(prettySw.toString().isEmpty());

    var compactSw = new StringWriter();
    AstCompactPrinter.print(ast, compactSw);
    Assertions.assertEquals("{:type :String :body \"Test\"}", compactSw.toString());
  }
}
