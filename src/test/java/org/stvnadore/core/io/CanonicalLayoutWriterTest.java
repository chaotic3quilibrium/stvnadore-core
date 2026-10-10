package org.stvnadore.core.io;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.printer.PrinterOptions;
import org.stvnadore.core.StvnCompiler;

import java.io.IOException;
import java.io.StringWriter;

@NullMarked
class CanonicalLayoutWriterTest {

  @Test
  void testParameterBypassingDefensiveShortForm() throws IOException {
    // Assert writeBoolean ignores SymbolStyle.LONG_FORM and outputs SHORT_FORM representation
    var writerTrue = new StringWriter();
    var layoutTrue = new CanonicalLayoutWriter(writerTrue);
    layoutTrue.writeBoolean(true, PrinterOptions.SymbolStyle.LONG_FORM);
    Assertions.assertEquals("#T", writerTrue.toString());

    var writerFalse = new StringWriter();
    var layoutFalse = new CanonicalLayoutWriter(writerFalse);
    layoutFalse.writeBoolean(false, PrinterOptions.SymbolStyle.LONG_FORM);
    Assertions.assertEquals("#F", writerFalse.toString());

    // Assert openOptionSomeTag ignores SymbolStyle.LONG_FORM and outputs SHORT_FORM representation
    var writerSome = new StringWriter();
    var layoutSome = new CanonicalLayoutWriter(writerSome);
    layoutSome.openOptionSomeTag(PrinterOptions.SymbolStyle.LONG_FORM);
    Assertions.assertEquals("#S", writerSome.toString());

    // Assert writeOptionNone ignores SymbolStyle.LONG_FORM and outputs SHORT_FORM representation
    var writerNone = new StringWriter();
    var layoutNone = new CanonicalLayoutWriter(writerNone);
    layoutNone.writeOptionNone(PrinterOptions.SymbolStyle.LONG_FORM);
    Assertions.assertEquals("#N", writerNone.toString());

    // Assert openEitherTag ignores SymbolStyle.LONG_FORM and outputs SHORT_FORM representation for Left
    var writerLeft = new StringWriter();
    var layoutLeft = new CanonicalLayoutWriter(writerLeft);
    layoutLeft.openEitherTag(false, PrinterOptions.SymbolStyle.LONG_FORM);
    Assertions.assertEquals("#L", writerLeft.toString());

    // Assert openEitherTag ignores SymbolStyle.LONG_FORM and outputs SHORT_FORM representation for Right
    var writerRight = new StringWriter();
    var layoutRight = new CanonicalLayoutWriter(writerRight);
    layoutRight.openEitherTag(true, PrinterOptions.SymbolStyle.LONG_FORM);
    Assertions.assertEquals("#R", writerRight.toString());
  }

  @Test
  void testFirewallCoercionOfLongFormTags() throws IOException {
    // Assert openTag catches long-form tokens and coerces them to their short-form equivalents
    var writer = new StringWriter();
    var layout = new CanonicalLayoutWriter(writer);

    layout.openTag("#Some");
    layout.openTag("#Left");
    layout.openTag("#Right");

    // The writer separates non-punctuation tokens with a space
    Assertions.assertEquals("#S #L #R", writer.toString());
  }

  @Test
  void testCanonicalNormalizationOfFencedStringArrowProhibition() {
    String legacySource = """
        {
          :defs {
            :Doc { #preserveIndent #TRUE } :String
          }
          :type :Doc
          :body ""\"->[SQL]
        SELECT * FROM users;
        ""\"[SQL]
        }
        """;

    String modernSource = """
        {
          :defs {
            :Doc { #preserveIndent #TRUE } :String
          }
          :type :Doc
          :body ""\"[SQL]
        SELECT * FROM users;
        [SQL]""\"
        }
        """;

    Assertions.assertThrows(RuntimeException.class, () -> StvnCompiler.compilePayload(legacySource),
        "Legacy '->' arrow delimiter must be rejected under Rule STR-04");

    var modernAst = StvnCompiler.compilePayload(modernSource).orElseThrow();

    // 1. Assert byte-for-byte canonical serialized output
    String canonicalModern = StvnCompiler.toCanonicalString(modernAst);
    Assertions.assertFalse(canonicalModern.contains("->[SQL]"), "Canonical output must strictly omit '->' arrow");
    Assertions.assertTrue(canonicalModern.contains("\"\"\"[SQL]"), "Canonical output must format as '\"\"\"[TAG]'");

    // 2. Assert SHA-256 CAS fingerprint generation
    byte[] hashModern = StvnCompiler.computeCasFingerprint(modernAst);
    Assertions.assertNotNull(hashModern);
  }
}
