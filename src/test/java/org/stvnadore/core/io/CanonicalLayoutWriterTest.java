package org.stvnadore.core.io;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.printer.PrinterOptions;

import java.io.IOException;
import java.io.StringWriter;

@NullMarked
class CanonicalLayoutWriterTest {

  @Test
  void testParameterBypassingDefensiveLongForm() throws IOException {
    // Assert writeBoolean ignores SymbolStyle.SHORT_FORM and outputs LONG_FORM representation
    var writerTrue = new StringWriter();
    var layoutTrue = new CanonicalLayoutWriter(writerTrue);
    layoutTrue.writeBoolean(true, PrinterOptions.SymbolStyle.SHORT_FORM);
    Assertions.assertEquals("#TRUE", writerTrue.toString());

    var writerFalse = new StringWriter();
    var layoutFalse = new CanonicalLayoutWriter(writerFalse);
    layoutFalse.writeBoolean(false, PrinterOptions.SymbolStyle.SHORT_FORM);
    Assertions.assertEquals("#FALSE", writerFalse.toString());

    // Assert openOptionSomeTag ignores SymbolStyle.SHORT_FORM and outputs LONG_FORM representation
    var writerSome = new StringWriter();
    var layoutSome = new CanonicalLayoutWriter(writerSome);
    layoutSome.openOptionSomeTag(PrinterOptions.SymbolStyle.SHORT_FORM);
    Assertions.assertEquals("#Some", writerSome.toString());

    // Assert writeOptionNone ignores SymbolStyle.SHORT_FORM and outputs LONG_FORM representation
    var writerNone = new StringWriter();
    var layoutNone = new CanonicalLayoutWriter(writerNone);
    layoutNone.writeOptionNone(PrinterOptions.SymbolStyle.SHORT_FORM);
    Assertions.assertEquals("#None", writerNone.toString());

    // Assert openEitherTag ignores SymbolStyle.SHORT_FORM and outputs LONG_FORM representation for Left
    var writerLeft = new StringWriter();
    var layoutLeft = new CanonicalLayoutWriter(writerLeft);
    layoutLeft.openEitherTag(false, PrinterOptions.SymbolStyle.SHORT_FORM);
    Assertions.assertEquals("#Left", writerLeft.toString());

    // Assert openEitherTag ignores SymbolStyle.SHORT_FORM and outputs LONG_FORM representation for Right
    var writerRight = new StringWriter();
    var layoutRight = new CanonicalLayoutWriter(writerRight);
    layoutRight.openEitherTag(true, PrinterOptions.SymbolStyle.SHORT_FORM);
    Assertions.assertEquals("#Right", writerRight.toString());
  }

  @Test
  void testFirewallCoercionOfShortFormTags() throws IOException {
    // Assert openTag catches short-form tokens and coerces them to their long-form equivalents
    var writer = new StringWriter();
    var layout = new CanonicalLayoutWriter(writer);

    layout.openTag("#S");
    layout.openTag("#L");
    layout.openTag("#R");

    // The writer separates non-punctuation tokens with a space
    Assertions.assertEquals("#Some #Left #Right", writer.toString());
  }
}
