package org.stvnadore.core.parser;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.core.validation.StvnSyntaxCancellationException;

import java.util.Optional;

/**
 * High-precision, sanitizing ANTLR error listener for STVN grammar recognition.
 * Eliminates raw DFA vocabulary dumps and provides concise, human-readable structural diagnostics.
 *
 * @since 1.4.0
 */
@NullMarked
public final class StvnErrorListener extends BaseErrorListener {

  private final @Nullable DiagnosticBag diagnosticBag;
  private final boolean strict;

  /**
   * Constructs an instance with the specified diagnostic bag and strictness mode.
   *
   * @param diagnosticBag the diagnostic accumulator, or {@code null} if running in strict/cancellation mode
   * @param strict        {@code true} to fail-fast throwing {@link StvnSyntaxCancellationException}
   */
  public StvnErrorListener(@Nullable DiagnosticBag diagnosticBag, boolean strict) {
    this.diagnosticBag = diagnosticBag;
    this.strict = strict;
  }

  /**
   * Factory for creating a strict fail-fast error listener.
   *
   * @return a strict {@link StvnErrorListener} instance
   */
  public static StvnErrorListener strict() {
    return new StvnErrorListener(null, true);
  }

  /**
   * Factory for creating an accumulating diagnostic error listener.
   *
   * @param diagnosticBag the target diagnostic accumulator
   * @return an accumulating {@link StvnErrorListener} instance
   */
  public static StvnErrorListener accumulating(DiagnosticBag diagnosticBag) {
    return new StvnErrorListener(diagnosticBag, false);
  }

  @Override
  public void syntaxError(
      Recognizer<?, ?> recognizer,
      @Nullable Object offendingSymbol,
      int line,
      int charPositionInLine,
      String rawMsg,
      @Nullable RecognitionException e
  ) {
    int startOffset = -1;
    int endOffset = -1;
    Token offendingToken = null;

    if (offendingSymbol instanceof Token token) {
      offendingToken = token;
      startOffset = token.getStartIndex();
      endOffset = token.getStopIndex();
    } else if (recognizer instanceof org.antlr.v4.runtime.Lexer lexerRec) {
      startOffset = lexerRec.getCharIndex();
      endOffset = lexerRec.getCharIndex();
    }

    String sanitizedMessage = formatSanitizedMessage(recognizer, offendingToken, rawMsg, e);

    if (this.strict) {
      throw new StvnSyntaxCancellationException(
          "STVN Syntax Error: " + sanitizedMessage,
          line,
          charPositionInLine,
          startOffset,
          endOffset,
          e
      );
    } else if (this.diagnosticBag != null) {
      this.diagnosticBag.add(new StvnDiagnostic(
          "STVN Syntax Error: " + sanitizedMessage,
          StvnDiagnostic.DiagnosticSeverity.ERROR,
          line,
          charPositionInLine,
          startOffset,
          endOffset,
          e,
          Optional.of("STVN_SYNTAX_ERROR")
      ));
    }
  }

  /**
   * Sanitizes ANTLR raw error messages, recognizing empty composites and simplifying expected token sets.
   *
   * @param recognizer     the ANTLR parser or lexer instance
   * @param offendingToken the offending token, or {@code null}
   * @param rawMsg         the raw message from ANTLR
   * @param e              the underlying recognition exception, or {@code null}
   * @return a clean, human-readable diagnostic message
   */
  public static String formatSanitizedMessage(
      Recognizer<?, ?> recognizer,
      @Nullable Token offendingToken,
      String rawMsg,
      @Nullable RecognitionException e
  ) {
    if (recognizer instanceof Parser parser) {
      RuleContext ctx = e != null && e.getCtx() != null ? e.getCtx() : parser.getContext();
      String tokenText = offendingToken != null ? offendingToken.getText() : "";
      int tokenType = offendingToken != null ? offendingToken.getType() : Token.INVALID_TYPE;

      // 0a. Intercept MALFORMED_FENCE_OPEN token citing Rule STR-04
      if (offendingToken != null && offendingToken.getType() == StvnLexer.MALFORMED_FENCE_OPEN) {
        return formatMalformedFenceMessage(tokenText);
      }

      // 0b. Intercept MALFORMED_FENCE_CLOSE token in DEFAULT_MODE citing Rule STR-04
      if (offendingToken != null && offendingToken.getType() == StvnLexer.MALFORMED_FENCE_CLOSE) {
        return "Rule STR-04 violation: Orphan or unexpected closing fence delimiter " + escapeWsAndQuote(tokenText);
      }

      // 0c. Intercept unclosed fenced string / asymmetric closing tag at EOF
      if (offendingToken != null && offendingToken.getType() == Token.EOF) {
        IntervalSet expected = e != null ? e.getExpectedTokens() : parser.getExpectedTokens();
        if (expected != null && expected.contains(StvnLexer.FENCE_END)) {
          return formatUnclosedFenceMessage(parser);
        }
      }

      // 1. Check if we are inside a collection literal where unexpected tokens should be flagged as extraneous
      boolean inCollectionLiteral = isInCollectionLiteral(ctx);

      // 2. Structural Pattern: Empty Composite Argument Detection (Context & Token Stream Inspection)
      if (offendingToken != null) {
        var stream = parser.getTokenStream();
        int idx = offendingToken.getTokenIndex();

        // Check if offending token is '(' preceded by :Tuple, :Union, etc. and followed by ')'
        if ("(".equals(tokenText) && idx > 0 && idx + 1 < stream.size()) {
          Token prev = stream.get(idx - 1);
          Token next = stream.get(idx + 1);
          if (")".equals(next.getText())) {
            if (":Tuple".equals(prev.getText())) {
              return "empty composite argument list: :Tuple() requires at least one schema type";
            }
            if (":Union".equals(prev.getText())) {
              return "empty composite argument list: :Union() requires at least one schema type";
            }
            if (isSingleArgCollectionKeyword(prev.getText())) {
              return "empty composite argument list: collection requires schema type argument";
            }
            if (isTwoArgCollectionKeyword(prev.getText())) {
              return "insufficient composite arguments: expected 2 schema type arguments";
            }
          }
        }

        // Check if offending token is '[' preceded by :Enum and followed by ']'
        if ("[".equals(tokenText) && idx > 0 && idx + 1 < stream.size()) {
          Token prev = stream.get(idx - 1);
          Token next = stream.get(idx + 1);
          if (":Enum".equals(prev.getText()) && "]".equals(next.getText())) {
            return "empty enum variant list: :Enum[] requires at least one value keyword variant";
          }
        }

        // Check if offending token is ')' preceded by '(' preceded by :Tuple, :Union, etc.
        if (")".equals(tokenText) && idx >= 2) {
          Token prev = stream.get(idx - 1);
          Token prev2 = stream.get(idx - 2);
          if ("(".equals(prev.getText())) {
            if (":Tuple".equals(prev2.getText())) {
              return "empty composite argument list: :Tuple() requires at least one schema type";
            }
            if (":Union".equals(prev2.getText())) {
              return "empty composite argument list: :Union() requires at least one schema type";
            }
            if (isSingleArgCollectionKeyword(prev2.getText())) {
              return "empty composite argument list: collection requires schema type argument";
            }
            if (isTwoArgCollectionKeyword(prev2.getText())) {
              return "insufficient composite arguments: expected 2 schema type arguments";
            }
          }
        }

        // Check if offending token is ']' preceded by '[' preceded by :Enum
        if ("]".equals(tokenText) && idx >= 2) {
          Token prev = stream.get(idx - 1);
          Token prev2 = stream.get(idx - 2);
          if ("[".equals(prev.getText()) && ":Enum".equals(prev2.getText())) {
            return "empty enum variant list: :Enum[] requires at least one value keyword variant";
          }
        }
      }

      if (")".equals(tokenText)) {
        if (isProductTypeContext(ctx)) {
          return "empty composite argument list: :Tuple() requires at least one schema type";
        }
        if (isUnionContext(ctx)) {
          return "empty composite argument list: :Union() requires at least one schema type";
        }
        if (isSingleArgumentCollection(ctx)) {
          return "empty composite argument list: collection requires schema type argument";
        }
        if (isTwoArgumentCollection(ctx)) {
          return "insufficient composite arguments: expected 2 schema type arguments";
        }
      }

      // 3. Structural Pattern: Empty Enum Definition Detection
      if ("]".equals(tokenText)) {
        if (isEnumDefContext(ctx)) {
          return "empty enum variant list: :Enum[] requires at least one value keyword variant";
        }
      }

      String offendingText = tokenType == Token.EOF ? "<EOF>" : tokenText;
      String displayToken = escapeWsAndQuote(offendingText);

      // 4. Expected Token Set Simplification
      IntervalSet expectedTokens = e != null ? e.getExpectedTokens() : parser.getExpectedTokens();
      if (expectedTokens != null && !expectedTokens.isNil()) {
        String simplifiedExpected = simplifyExpectedTokenSet(expectedTokens, parser);
        if (offendingToken != null) {
          if (inCollectionLiteral) {
            return "extraneous input " + displayToken;
          } else {
            return "mismatched input " + displayToken + " expecting " + simplifiedExpected;
          }
        }
      }

      if (offendingToken != null) {
        if (inCollectionLiteral) {
          return "extraneous input " + displayToken;
        } else {
          return "mismatched input " + displayToken;
        }
      }
    }

    // Fallback: Strip existing verbose vocabulary sets if present in rawMsg
    return sanitizeRawFallback(rawMsg);
  }

  private static String formatMalformedFenceMessage(String tokenText) {
    int start = tokenText.indexOf('[');
    int end = tokenText.indexOf(']', start >= 0 ? start : 0);
    String tag = (start >= 0 && end > start) ? tokenText.substring(start + 1, end) : "";

    if (tag.isEmpty()) {
      return "Rule STR-04 violation: Fenced string delimiter tag must not be empty";
    }
    if (tag.contains(" ") || tag.contains("\t") || tag.contains("\r") || tag.contains("\n")) {
      return "Rule STR-04 violation: Fenced string delimiter tag must not contain whitespace, got '" + tag + "'";
    }
    if (tag.length() > 256) {
      return "Rule STR-04 violation: Fenced string delimiter tag length exceeds maximum of 256 characters, got " + tag.length();
    }
    return "Rule STR-04 violation: Fenced string delimiter tag '" + tag + "' contains invalid characters; must match positive character class ^[a-zA-Z0-9_-]{1,256}$";
  }

  private static String formatUnclosedFenceMessage(Parser parser) {
    String expectedTag = "";
    var stream = parser.getTokenStream();
    for (int i = 0; i < stream.size(); i++) {
      Token t = stream.get(i);
      if (t.getType() == StvnLexer.FENCE_START) {
        String txt = t.getText();
        int open = txt.indexOf('[');
        int close = txt.indexOf(']', open);
        if (open >= 0 && close > open) {
          expectedTag = txt.substring(open + 1, close);
        }
      }
    }

    String fullInput = stream.getText();
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[([a-zA-Z0-9_-]+)\\]\"\"\"").matcher(fullInput);
    String foundClosingTag = null;
    while (m.find()) {
      String tag = m.group(1);
      if (!tag.equals(expectedTag)) {
        foundClosingTag = tag;
      }
    }

    if (foundClosingTag != null && !expectedTag.isEmpty()) {
      return "Rule STR-04 violation: Mismatched closing fence tag '[" + foundClosingTag + "]', expected '[" + expectedTag + "]'";
    }
    if (!expectedTag.isEmpty()) {
      return "Rule STR-04 violation: Unclosed fenced string block; expected closing delimiter '[" + expectedTag + "]\"\"\"'";
    }
    return "Rule STR-04 violation: Unclosed fenced string block; expected closing delimiter '[TAG]\"\"\"'";
  }

  private static boolean isInCollectionLiteral(@Nullable RuleContext ctx) {
    RuleContext cur = ctx;
    while (cur != null) {
      String name = cur.getClass().getSimpleName();
      if (name.equals("ListLiteralContext") ||
          name.equals("CollectionValueContext") ||
          name.equals("MapLiteralContext") ||
          name.equals("TupleLiteralContext")) {
        return true;
      }
      cur = cur.parent;
    }
    return false;
  }

  private static boolean isProductTypeContext(@Nullable RuleContext ctx) {
    RuleContext cur = ctx;
    while (cur != null) {
      String name = cur.getClass().getSimpleName();
      if (name.equals("ProductTypeContext") || name.equals("TupleTypeContext")) {
        return true;
      }
      if (cur.getText().startsWith(":Tuple(")) {
        return true;
      }
      cur = cur.parent;
    }
    return false;
  }

  private static boolean isUnionContext(@Nullable RuleContext ctx) {
    RuleContext cur = ctx;
    while (cur != null) {
      String name = cur.getClass().getSimpleName();
      if (name.equals("SumTypeContext") && cur.getText().startsWith(":Union(")) {
        return true;
      }
      cur = cur.parent;
    }
    return false;
  }

  private static boolean isEnumDefContext(@Nullable RuleContext ctx) {
    RuleContext cur = ctx;
    while (cur != null) {
      String name = cur.getClass().getSimpleName();
      if (name.equals("EnumDefContext") || cur.getText().startsWith("[")) {
        return true;
      }
      cur = cur.parent;
    }
    return false;
  }

  private static boolean isSingleArgCollectionKeyword(String text) {
    return ":Seq".equals(text) ||
           ":SeqNonEmpty".equals(text) ||
           ":Set".equals(text) ||
           ":SetNonEmpty".equals(text) ||
           ":Option".equals(text);
  }

  private static boolean isTwoArgCollectionKeyword(String text) {
    return ":Map".equals(text) ||
           ":MapNonEmpty".equals(text) ||
           ":MapInv".equals(text) ||
           ":MapInvNonEmpty".equals(text) ||
           ":Either".equals(text);
  }

  private static boolean isSingleArgumentCollection(@Nullable RuleContext ctx) {
    RuleContext cur = ctx;
    while (cur != null) {
      String text = cur.getText();
      if (text.startsWith(":Seq(") || text.startsWith(":SeqNonEmpty(") ||
          text.startsWith(":Set(") || text.startsWith(":SetNonEmpty(") ||
          text.startsWith(":Option(")) {
        return true;
      }
      cur = cur.parent;
    }
    return false;
  }

  private static boolean isTwoArgumentCollection(@Nullable RuleContext ctx) {
    RuleContext cur = ctx;
    while (cur != null) {
      String text = cur.getText();
      if (text.startsWith(":Map(") || text.startsWith(":MapNonEmpty(") ||
          text.startsWith(":MapInv(") || text.startsWith(":MapInvNonEmpty(") ||
          text.startsWith(":Either(")) {
        return true;
      }
      cur = cur.parent;
    }
    return false;
  }

  private static String simplifyExpectedTokenSet(IntervalSet tokens, Parser parser) {
    // Check if token set contains major schema constructors
    boolean hasAtomic = tokens.contains(StvnParser.ATOM_INT) ||
                        tokens.contains(StvnParser.ATOM_STRING) ||
                        tokens.contains(StvnParser.ATOM_BOOLEAN) ||
                        tokens.contains(StvnParser.ATOM_UINT) ||
                        tokens.contains(StvnParser.ATOM_FLOAT) ||
                        tokens.contains(StvnParser.ATOM_FLOAT_EXACT) ||
                        tokens.contains(StvnParser.ATOM_STRING_FIXED) ||
                        tokens.contains(StvnParser.ATOM_STRING_NON_EMPTY) ||
                        tokens.contains(StvnParser.ATOM_TIME_EPOCH_S) ||
                        tokens.contains(StvnParser.ATOM_TIME_EPOCH_MS) ||
                        tokens.contains(StvnParser.ATOM_TIME_EPOCH_NS) ||
                        tokens.contains(StvnParser.ATOM_DATE_TIME_OFFSET) ||
                        tokens.contains(StvnParser.ATOM_DATE_TIME_ZONED) ||
                        tokens.contains(StvnParser.ATOM_DATE_TIME_AUDITED);
    boolean hasCollection = tokens.contains(StvnParser.COLL_SEQ) ||
                            tokens.contains(StvnParser.COLL_SEQ_NON_EMPTY) ||
                            tokens.contains(StvnParser.COLL_SET) ||
                            tokens.contains(StvnParser.COLL_SET_NON_EMPTY) ||
                            tokens.contains(StvnParser.COLL_MAP) ||
                            tokens.contains(StvnParser.COLL_MAP_NON_EMPTY) ||
                            tokens.contains(StvnParser.COLL_MAP_INV) ||
                            tokens.contains(StvnParser.COLL_MAP_INV_NON_EMPTY);
    boolean hasTypeKw = tokens.contains(StvnParser.TYPE_KEYWORD_BASE);
    boolean hasValueKw = tokens.contains(StvnParser.VALUE_KEYWORD_BASE) ||
                         tokens.contains(StvnParser.KW_TRUE) ||
                         tokens.contains(StvnParser.UNION_TAG_PREFIX);

    if (hasAtomic || hasCollection || hasTypeKw) {
      return "<schema type>";
    }
    if (hasValueKw && tokens.size() > 5) {
      return "<value keyword>";
    }
    if (tokens.contains(StvnParser.LITERAL_INTEGER) && tokens.contains(StvnParser.LITERAL_STRING_SIMPLE)) {
      return "<value>";
    }
    if (tokens.contains(StvnParser.KW_EQUATABLE) || tokens.contains(StvnParser.KW_MIN_INCL) || tokens.contains(StvnParser.KW_REGEX)) {
      return "<metadata keyword>";
    }

    // Single token exact representation
    if (tokens.size() == 1) {
      return parser.getVocabulary().getDisplayName(tokens.getMinElement());
    }

    if (tokens.size() <= 3) {
      return tokens.toString(parser.getVocabulary());
    }

    // Truncate large fallback sets
    var list = tokens.toList();
    var voc = parser.getVocabulary();
    return "{" + voc.getDisplayName(list.get(0)) + ", " +
                 voc.getDisplayName(list.get(1)) + ", " +
                 voc.getDisplayName(list.get(2)) + ", ... (" + list.size() + " options)}";
  }

  private static String sanitizeRawFallback(String rawMsg) {
    if (rawMsg == null) return "syntax error";
    if (rawMsg.contains("expecting {")) {
      int idx = rawMsg.indexOf("expecting {");
      String prefix = rawMsg.substring(0, idx).trim();
      String setPart = rawMsg.substring(idx + "expecting {".length());
      String[] elements = setPart.split("[,}]");
      if (elements.length > 4) {
        return prefix + " expecting <valid token>";
      }
    }
    if (rawMsg.contains("no viable alternative at input")) {
      return rawMsg.replace("no viable alternative at input", "mismatched input");
    }
    return rawMsg;
  }

  private static String escapeWsAndQuote(String s) {
    if (s == null) return "null";
    s = s.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    return "'" + s + "'";
  }
}
