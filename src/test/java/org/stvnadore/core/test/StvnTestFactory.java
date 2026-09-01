package org.stvnadore.core.test;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.stvnadore.core.parser.StvnLexer;
import org.stvnadore.core.parser.StvnParser;
import org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema;
import org.stvnadore.core.validation.StvnTypeResolver.StvnConstraints;
import java.util.Optional;

/**
 * Factory class providing unified, valid schemas for testing purposes.
 */
public final class StvnTestFactory {

  private StvnTestFactory() {}

  /**
   * Initializes an ANTLR CharStream from the string literal ":Int", runs it through
   * StvnLexer and StvnParser, calls the parser's schemaType() rule to yield a legitimate,
   * non-null SchemaTypeContext node, and returns a fully populated, valid ResolvedSchema instance
   * using empty constraints and empty optionals for the remaining fields.
   */
  public static ResolvedSchema createDummySchema() {
    var lexer = new StvnLexer(CharStreams.fromString(":Int"));
    lexer.removeErrorListeners();
    var parser = new StvnParser(new CommonTokenStream(lexer));
    parser.removeErrorListeners();
    var node = parser.schemaType();

    return new ResolvedSchema(
        node,
        StvnConstraints.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty()
    );
  }
}
