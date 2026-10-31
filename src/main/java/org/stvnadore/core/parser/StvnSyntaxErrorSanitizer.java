package org.stvnadore.core.parser;

import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.LexerNoViableAltException;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.validation.DiagnosticBag;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pass 1 syntax error sanitizer for the STVN 2.0.0 4-pass lowering pipeline.
 * <p>
 * Translates 100% of ANTLR4 lexer and parser recognition failures into deterministic,
 * Simplified Technical English (STE) {@link StvnDiagnostic} records carrying dual
 * specification codes ({@code E001}–{@code E040}) and symbolic identifiers ({@code ERR_*}),
 * while enforcing Section 7.3.1 Document Bootstrap Clause Blame Pinning.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnSyntaxErrorSanitizer {

  private static final Pattern CLOSING_FENCE_PATTERN =
      Pattern.compile("\\[([a-zA-Z0-9_-]+)\\]\"\"\"");
  private static final Pattern MISSING_AT_PATTERN =
      Pattern.compile("(?i)missing\\s+(.+?)\\s+at\\s+");
  private static final Pattern TOKEN_RECOG_PATTERN =
      Pattern.compile("(?i)token recognition error at:\\s*");
  private static final Pattern NO_VIABLE_ALT_PATTERN =
      Pattern.compile("(?i)no viable alternative at input\\s*");
  private static final Pattern MISMATCHED_INPUT_PATTERN =
      Pattern.compile("(?i)mismatched input\\s*");
  private static final Pattern EXTRANEOUS_INPUT_PATTERN =
      Pattern.compile("(?i)extraneous input\\s*");

  private StvnSyntaxErrorSanitizer() {
  }

  /**
   * Immutable sanitized Pass 1 syntax diagnostic payload.
   *
   * @param message              the STE-compliant diagnostic message prefixed with {@code STVN Syntax Error: }
   * @param coreMessage          the STE-compliant diagnostic message without prefix
   * @param line                 the 1-based source line number
   * @param column               the 0-based character column offset
   * @param startOffset          the 0-based inclusive start character offset
   * @param endOffset            the 0-based exclusive end character offset
   * @param canonicalCode        the normative specification code ({@code E001}–{@code E040})
   * @param symbolicId           the symbolic diagnostic identifier
   * @param clauseStructureError {@code true} if this error represents an incomplete bootstrap clause
   */
  public record SanitizedSyntaxDiagnostic(
      String message,
      String coreMessage,
      int line,
      int column,
      int startOffset,
      int endOffset,
      String canonicalCode,
      String symbolicId,
      boolean clauseStructureError
  ) {
    /**
     * Converts this sanitized record into an immutable {@link StvnDiagnostic}.
     *
     * @param cause the underlying recognition exception, or {@code null}
     * @return the constructed {@link StvnDiagnostic}
     */
    public StvnDiagnostic toDiagnostic(@Nullable Throwable cause) {
      return new StvnDiagnostic(
          message,
          StvnDiagnostic.DiagnosticSeverity.ERROR,
          line,
          column,
          startOffset,
          endOffset,
          cause,
          Optional.of(canonicalCode),
          Optional.of(symbolicId)
      );
    }
  }

  /**
   * Sanitizes a raw ANTLR lexer or parser recognition error into a deterministic
   * {@link SanitizedSyntaxDiagnostic}.
   *
   * @param recognizer         the ANTLR recognizer (lexer or parser)
   * @param offendingSymbol    the offending token symbol, or {@code null}
   * @param line               the reported 1-based line number
   * @param charPositionInLine the reported 0-based column offset
   * @param rawMsg             the raw ANTLR error message
   * @param e                  the recognition exception, or {@code null}
   * @return the sanitized syntax diagnostic record
   */
  public static SanitizedSyntaxDiagnostic sanitize(
      Recognizer<?, ?> recognizer,
      @Nullable Object offendingSymbol,
      int line,
      int charPositionInLine,
      @Nullable String rawMsg,
      @Nullable RecognitionException e
  ) {
    int startOffset = -1;
    int endOffset = -1;
    Token offendingToken = null;
    boolean isBareColon = false;
    boolean isBareHash = false;
    boolean isRootMissing = false;
    String effectiveRawMsg = rawMsg != null ? rawMsg : "";

    if (offendingSymbol instanceof Token token) {
      offendingToken = token;
      startOffset = token.getStartIndex();
      endOffset = token.getType() == Token.EOF
          ? startOffset
          : Math.max(startOffset + 1, token.getStopIndex() + 1);
      if (":".equals(token.getText())) {
        isBareColon = true;
      } else if ("#".equals(token.getText())) {
        isBareHash = true;
      }

      if (recognizer instanceof Parser parser) {
        var stream = parser.getTokenStream();
        if (stream != null) {
          int idx = token.getTokenIndex();
          if (idx == 0
              && token.getType() != StvnLexer.LBRACE
              && token.getType() != StvnLexer.TAB_CHARACTER
              && token.getType() != StvnLexer.MALFORMED_FENCE_OPEN
              && token.getType() != StvnLexer.MALFORMED_FENCE_CLOSE) {
            isRootMissing = true;
          }
          if (idx > 0 && stream.get(idx - 1).getType() == StvnLexer.KW_PRESERVE_INDENT) {
            Token prev = stream.get(idx - 1);
            startOffset = prev.getStartIndex();
            endOffset = prev.getStopIndex() + 1;
            line = prev.getLine();
            charPositionInLine = prev.getCharPositionInLine();
          } else if (idx > 0
              && token.getType() != StvnLexer.KW_DEFS
              && token.getType() != StvnLexer.KW_TYPE
              && token.getType() != StvnLexer.KW_BODY
              && token.getType() != StvnLexer.KW_META) {
            Token prev = stream.get(idx - 1);
            int prevType = prev.getType();
            if (prevType == StvnLexer.KW_DEFS && token.getType() != StvnLexer.LBRACE) {
              offendingToken = prev;
              startOffset = prev.getStartIndex();
              endOffset = prev.getStopIndex() + 1;
              line = prev.getLine();
              charPositionInLine = prev.getCharPositionInLine();
              effectiveRawMsg = "clause '" + StvnVocabulary.KEYWORD_DEFS + "' requires definition block '{ ... }'";
            } else if (prevType == StvnLexer.KW_META && token.getType() != StvnLexer.LBRACE) {
              offendingToken = prev;
              startOffset = prev.getStartIndex();
              endOffset = prev.getStopIndex() + 1;
              line = prev.getLine();
              charPositionInLine = prev.getCharPositionInLine();
              effectiveRawMsg = "clause '" + StvnVocabulary.KEYWORD_META + "' requires a metadata block '{ ... }'";
            } else if (prevType == StvnLexer.KW_TYPE && !StvnErrorStrategy.isValidSchemaTypeStarter(token)) {
              offendingToken = prev;
              startOffset = prev.getStartIndex();
              endOffset = prev.getStopIndex() + 1;
              line = prev.getLine();
              charPositionInLine = prev.getCharPositionInLine();
              effectiveRawMsg = "clause '" + StvnVocabulary.KEYWORD_TYPE + "' requires a schema type";
            } else if (prevType == StvnLexer.KW_BODY
                && (token.getType() == StvnLexer.RBRACE
                    || token.getType() == Token.EOF
                    || StvnErrorStrategy.isTopLevelKeyword(token))) {
              offendingToken = prev;
              startOffset = prev.getStartIndex();
              endOffset = prev.getStopIndex() + 1;
              line = prev.getLine();
              charPositionInLine = prev.getCharPositionInLine();
              effectiveRawMsg = "clause '" + StvnVocabulary.KEYWORD_BODY + "' requires a value payload";
            }
          }

          // Pin unclosed / mismatched fenced string at EOF back to FENCE_START
          if (offendingToken != null && offendingToken.getType() == Token.EOF) {
            IntervalSet expected = e != null ? e.getExpectedTokens() : parser.getExpectedTokens();
            if (expected != null && expected.contains(StvnLexer.FENCE_END)) {
              Token lastFenceStart = findLastFenceStart(stream);
              if (lastFenceStart != null) {
                line = lastFenceStart.getLine();
                charPositionInLine = lastFenceStart.getCharPositionInLine();
                startOffset = lastFenceStart.getStartIndex();
                endOffset = Math.max(startOffset + 1, token.getStartIndex());
              }
            }
          }
        }
      }
    } else if (recognizer instanceof Lexer lexerRec) {
      if (e instanceof LexerNoViableAltException lnvae && lnvae.getStartIndex() >= 0) {
        startOffset = lnvae.getStartIndex();
      } else if (lexerRec._tokenStartCharIndex >= 0) {
        startOffset = lexerRec._tokenStartCharIndex;
      } else {
        startOffset = lexerRec.getCharIndex();
      }

      var input = lexerRec.getInputStream();
      String source = input != null ? input.getText(Interval.of(0, input.size())) : "";

      int matchedEnd = lexerRec.getCharIndex();
      if (matchedEnd <= startOffset) {
        matchedEnd = startOffset + 1;
      }

      int eol = source.length();
      for (int i = startOffset; i < source.length(); i++) {
        char c = source.charAt(i);
        if (c == '\r' || c == '\n') {
          eol = i;
          break;
        }
      }

      while (matchedEnd > startOffset && matchedEnd <= source.length()
          && (source.charAt(matchedEnd - 1) == '\n' || source.charAt(matchedEnd - 1) == '\r')) {
        matchedEnd--;
      }

      endOffset = Math.min(matchedEnd, eol);

      if (startOffset >= 0 && startOffset < source.length()) {
        char c = source.charAt(startOffset);
        if (c == '#') {
          endOffset = startOffset + 1;
          isBareHash = true;
        } else if (c == ':') {
          endOffset = startOffset + 1;
          isBareColon = true;
        }
      }

      if (endOffset <= startOffset) {
        endOffset = Math.min(startOffset + 1, source.length());
      }
    }

    String coreMsg = buildSanitizedCoreMessage(
        recognizer,
        offendingToken,
        effectiveRawMsg,
        e,
        isBareColon,
        isBareHash,
        isRootMissing
    );
    coreMsg = purgeResidualAntlrJargon(coreMsg);

    boolean isClauseError = coreMsg.contains("clause '" + StvnVocabulary.KEYWORD_DEFS + "' requires")
        || coreMsg.contains("clause '" + StvnVocabulary.KEYWORD_TYPE + "' requires")
        || coreMsg.contains("clause '" + StvnVocabulary.KEYWORD_BODY + "' requires")
        || coreMsg.contains("clause '" + StvnVocabulary.KEYWORD_META + "' requires");

    String symbolicId = classifySymbolicId(
        offendingToken,
        coreMsg,
        isBareColon,
        isBareHash,
        isRootMissing,
        isClauseError
    );
    String canonicalCode = DiagnosticBag.resolveCanonicalCode(symbolicId).orElse("E002");

    String prefixedMsg = coreMsg.startsWith("STVN Syntax Error:")
        ? coreMsg
        : "STVN Syntax Error: " + coreMsg;

    return new SanitizedSyntaxDiagnostic(
        prefixedMsg,
        coreMsg,
        line,
        charPositionInLine,
        startOffset,
        endOffset,
        canonicalCode,
        symbolicId,
        isClauseError
    );
  }

  /**
   * Formats a clean, STE-compliant diagnostic message without raw ANTLR parser-generator jargon.
   *
   * @param recognizer     the ANTLR parser or lexer instance
   * @param offendingToken the offending token, or {@code null}
   * @param rawMsg         the raw message from ANTLR
   * @param e              the underlying recognition exception, or {@code null}
   * @return the sanitized core message text
   */
  public static String formatSanitizedMessage(
      Recognizer<?, ?> recognizer,
      @Nullable Token offendingToken,
      @Nullable String rawMsg,
      @Nullable RecognitionException e
  ) {
    boolean isBareColon = offendingToken != null && ":".equals(offendingToken.getText());
    boolean isBareHash = offendingToken != null && "#".equals(offendingToken.getText());
    boolean isRootMissing = false;
    if (recognizer instanceof Parser parser && offendingToken != null) {
      if (offendingToken.getTokenIndex() == 0
          && offendingToken.getType() != StvnLexer.LBRACE
          && offendingToken.getType() != StvnLexer.TAB_CHARACTER
          && offendingToken.getType() != StvnLexer.MALFORMED_FENCE_OPEN
          && offendingToken.getType() != StvnLexer.MALFORMED_FENCE_CLOSE) {
        isRootMissing = true;
      }
    }
    String msg = buildSanitizedCoreMessage(
        recognizer,
        offendingToken,
        rawMsg != null ? rawMsg : "",
        e,
        isBareColon,
        isBareHash,
        isRootMissing
    );
    return purgeResidualAntlrJargon(msg);
  }

  private static String buildSanitizedCoreMessage(
      Recognizer<?, ?> recognizer,
      @Nullable Token offendingToken,
      String rawMsg,
      @Nullable RecognitionException e,
      boolean isBareColon,
      boolean isBareHash,
      boolean isRootMissing
  ) {
    if (isBareColon) {
      return "Standalone colon ':' is prohibited in STVN syntax; attach ':' directly as a prefix to a type or clause identifier";
    }
    if (isBareHash) {
      return "Standalone hash '#' is prohibited in STVN syntax; attach '#' directly to a value or facet identifier, or use '//' for comments";
    }

    if (recognizer instanceof Lexer) {
      if (rawMsg.contains("\t") || rawMsg.contains("\\t")) {
        return "Tab character ('\\t', U+0009) is strictly forbidden in STVN syntax outside fenced string literals; use standard spaces instead (ERR_TAB_CHARACTER_FORBIDDEN)";
      }
      return sanitizeLexerFallback(rawMsg);
    }

    if (recognizer instanceof Parser parser) {
      RuleContext ctx = e != null && e.getCtx() != null ? e.getCtx() : parser.getContext();
      String tokenText = offendingToken != null && offendingToken.getText() != null ? offendingToken.getText() : "";
      int tokenType = offendingToken != null ? offendingToken.getType() : Token.INVALID_TYPE;

      if (tokenType == StvnLexer.TAB_CHARACTER) {
        return "Tab character ('\\t', U+0009) is strictly forbidden in STVN syntax outside fenced string literals; use standard spaces instead (ERR_TAB_CHARACTER_FORBIDDEN)";
      }

      if (tokenType == StvnLexer.MALFORMED_FENCE_OPEN) {
        return formatMalformedFenceMessage(tokenText);
      }

      if (tokenType == StvnLexer.MALFORMED_FENCE_CLOSE) {
        return "Rule STR-04 violation: Orphan or unexpected closing fence delimiter " + escapeWsAndQuote(tokenText);
      }

      if (tokenType == Token.EOF) {
        IntervalSet expected = e != null ? e.getExpectedTokens() : parser.getExpectedTokens();
        if (expected != null && expected.contains(StvnLexer.FENCE_END)) {
          return formatUnclosedFenceMessage(parser);
        }
      }

      if (rawMsg.startsWith("clause '")) {
        return rawMsg;
      }

      if (isRootMissing) {
        return "Document root must be enclosed within curly braces '{ ... }'; found leading token "
            + escapeWsAndQuote(tokenType == Token.EOF ? "<EOF>" : tokenText);
      }

      if (tokenType == StvnLexer.KW_META) {
        return "Document '" + StvnVocabulary.KEYWORD_META + "' block must appear before '"
            + StvnVocabulary.KEYWORD_DEFS + "', '" + StvnVocabulary.KEYWORD_TYPE + "', and '"
            + StvnVocabulary.KEYWORD_BODY + "'";
      }

      boolean inCollectionLiteral = isInCollectionLiteral(ctx);

      if (offendingToken != null) {
        var stream = parser.getTokenStream();
        int idx = offendingToken.getTokenIndex();
        Token prevToken = (idx > 0 && stream != null) ? stream.get(idx - 1) : null;

        if (prevToken != null && prevToken.getType() == StvnLexer.KW_DEFS && isClauseOrBoundary(offendingToken)) {
          return "clause '" + StvnVocabulary.KEYWORD_DEFS + "' requires definition block '{ ... }'";
        }
        if (prevToken != null && prevToken.getType() == StvnLexer.KW_TYPE && isClauseOrBoundary(offendingToken)) {
          return "clause '" + StvnVocabulary.KEYWORD_TYPE + "' requires a schema type";
        }
        if (prevToken != null && prevToken.getType() == StvnLexer.KW_BODY && isClauseOrBoundary(offendingToken)) {
          return "clause '" + StvnVocabulary.KEYWORD_BODY + "' requires a value payload";
        }
        if (prevToken != null && prevToken.getType() == StvnLexer.KW_META && isClauseOrBoundary(offendingToken)) {
          return "clause '" + StvnVocabulary.KEYWORD_META + "' requires a metadata block '{ ... }'";
        }

        if (prevToken != null && prevToken.getType() == StvnLexer.KW_PRESERVE_INDENT) {
          return "Metadata facet #preserveIndent requires an explicit boolean value (#TRUE or #FALSE)";
        }

        if (stream != null && "(".equals(tokenText) && idx > 0 && idx + 1 < stream.size()) {
          Token prev = stream.get(idx - 1);
          Token next = stream.get(idx + 1);
          if (")".equals(next.getText())) {
            if (StvnVocabulary.TYPE_TUPLE.equals(prev.getText())) {
              return "empty composite argument list: " + StvnVocabulary.TYPE_TUPLE + "() requires at least one schema type";
            }
            if (StvnVocabulary.TYPE_UNION.equals(prev.getText())) {
              return "empty composite argument list: " + StvnVocabulary.TYPE_UNION + "() requires at least one schema type";
            }
            if (isSingleArgCollectionKeyword(prev.getText())) {
              return "empty composite argument list: collection requires schema type argument";
            }
            if (isTwoArgCollectionKeyword(prev.getText())) {
              return "insufficient composite arguments: expected 2 schema type arguments";
            }
          }
        }

        if (stream != null && "[".equals(tokenText) && idx > 0 && idx + 1 < stream.size()) {
          Token prev = stream.get(idx - 1);
          Token next = stream.get(idx + 1);
          if (StvnVocabulary.TYPE_ENUM.equals(prev.getText()) && "]".equals(next.getText())) {
            return "empty enum variant list: " + StvnVocabulary.TYPE_ENUM + "[] requires at least one value keyword variant";
          }
          if ((StvnVocabulary.KEYWORD_INCLUDE.equals(prev.getText()) || StvnVocabulary.KEYWORD_USE.equals(prev.getText()))
              && "]".equals(next.getText())) {
            return "Empty directive '" + prev.getText() + " []' is prohibited";
          }
        }

        if (stream != null && ")".equals(tokenText) && idx >= 2) {
          Token prev = stream.get(idx - 1);
          Token prev2 = stream.get(idx - 2);
          if ("(".equals(prev.getText())) {
            if (StvnVocabulary.TYPE_TUPLE.equals(prev2.getText())) {
              return "empty composite argument list: " + StvnVocabulary.TYPE_TUPLE + "() requires at least one schema type";
            }
            if (StvnVocabulary.TYPE_UNION.equals(prev2.getText())) {
              return "empty composite argument list: " + StvnVocabulary.TYPE_UNION + "() requires at least one schema type";
            }
            if (isSingleArgCollectionKeyword(prev2.getText())) {
              return "empty composite argument list: collection requires schema type argument";
            }
            if (isTwoArgCollectionKeyword(prev2.getText())) {
              return "insufficient composite arguments: expected 2 schema type arguments";
            }
          }
        }

        if (stream != null && "]".equals(tokenText) && idx >= 2) {
          Token prev = stream.get(idx - 1);
          Token prev2 = stream.get(idx - 2);
          if ("[".equals(prev.getText()) && StvnVocabulary.TYPE_ENUM.equals(prev2.getText())) {
            return "empty enum variant list: " + StvnVocabulary.TYPE_ENUM + "[] requires at least one value keyword variant";
          }
          if ("[".equals(prev.getText())
              && (StvnVocabulary.KEYWORD_INCLUDE.equals(prev2.getText()) || StvnVocabulary.KEYWORD_USE.equals(prev2.getText()))) {
            return "Empty directive '" + prev2.getText() + " []' is prohibited";
          }
        }
      }

      if (")".equals(tokenText)) {
        if (isProductTypeContext(ctx)) {
          return "empty composite argument list: " + StvnVocabulary.TYPE_TUPLE + "() requires at least one schema type";
        }
        if (isUnionContext(ctx)) {
          return "empty composite argument list: " + StvnVocabulary.TYPE_UNION + "() requires at least one schema type";
        }
        if (isSingleArgumentCollection(ctx)) {
          return "empty composite argument list: collection requires schema type argument";
        }
        if (isTwoArgumentCollection(ctx)) {
          return "insufficient composite arguments: expected 2 schema type arguments";
        }
      }

      if ("]".equals(tokenText) && isEnumDefContext(ctx)) {
        return "empty enum variant list: " + StvnVocabulary.TYPE_ENUM + "[] requires at least one value keyword variant";
      }

      String offendingText = tokenType == Token.EOF ? "<EOF>" : tokenText;
      String displayToken = escapeWsAndQuote(offendingText);

      IntervalSet expectedTokens = e != null ? e.getExpectedTokens() : parser.getExpectedTokens();
      if (expectedTokens != null && !expectedTokens.isNil() && offendingToken != null) {
        String simplifiedExpected = simplifyExpectedTokenSet(expectedTokens, parser);
        if (inCollectionLiteral) {
          return "unexpected extraneous symbol " + displayToken;
        }
        return "unexpected token " + displayToken + "; expected " + simplifiedExpected;
      }

      if (offendingToken != null) {
        if (inCollectionLiteral) {
          return "unexpected extraneous symbol " + displayToken;
        }
        return "unexpected token " + displayToken;
      }
    }

    return sanitizeLexerFallback(rawMsg);
  }

  private static String classifySymbolicId(
      @Nullable Token offendingToken,
      String coreMsg,
      boolean isBareColon,
      boolean isBareHash,
      boolean isRootMissing,
      boolean isClauseError
  ) {
    if (coreMsg.contains("ERR_TAB_CHARACTER_FORBIDDEN")
        || (offendingToken != null && offendingToken.getType() == StvnLexer.TAB_CHARACTER)) {
      return DiagnosticBag.ERR_TAB_CHARACTER_FORBIDDEN;
    }
    if (isBareColon) {
      return DiagnosticBag.ERR_BARE_COLON_PROHIBITED;
    }
    if (isBareHash) {
      return DiagnosticBag.ERR_BARE_HASH_PROHIBITED;
    }
    if (coreMsg.contains("delimiter arrow '->' is prohibited")
        || (offendingToken != null && offendingToken.getText() != null && offendingToken.getText().startsWith("\"\"\"->["))) {
      return DiagnosticBag.ERR_PROHIBITED_FENCE_ARROW;
    }
    if (coreMsg.contains("Rule STR-04 violation") || coreMsg.contains("Unclosed fenced string")) {
      return DiagnosticBag.ERR_FENCE_DELIMITER_MISMATCH;
    }
    if (coreMsg.contains("Metadata facet #preserveIndent requires an explicit boolean value")) {
      return DiagnosticBag.ERR_INVALID_METADATA_FACET;
    }
    if (coreMsg.startsWith("Empty directive '")) {
      return DiagnosticBag.ERR_EMPTY_DIRECTIVE_BLOCK;
    }
    if (!isClauseError
        && ((offendingToken != null && offendingToken.getType() == StvnLexer.KW_META)
            || coreMsg.contains("Document '" + StvnVocabulary.KEYWORD_META + "' block must appear before"))) {
      return DiagnosticBag.ERR_META_POSITION_INVALID;
    }
    if (isRootMissing) {
      return DiagnosticBag.ERR_ROOT_ENCLOSURE_MISSING;
    }
    return "STVN_SYNTAX_ERROR";
  }

  private static @Nullable Token findLastFenceStart(org.antlr.v4.runtime.TokenStream stream) {
    Token found = null;
    for (int i = 0; i < stream.size(); i++) {
      Token t = stream.get(i);
      if (t.getType() == StvnLexer.FENCE_START) {
        found = t;
      }
    }
    return found;
  }

  private static String formatMalformedFenceMessage(String tokenText) {
    if (tokenText.startsWith("\"\"\"->[")) {
      return "Rule STR-04 violation: Fenced string delimiter arrow '->' is prohibited";
    }
    if (!tokenText.contains("[")) {
      return "Rule STR-04 violation: Unfenced block strings are prohibited; multi-line strings must use fenced syntax \"\"\"[TAG]";
    }
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
    return "Rule STR-04 violation: Fenced string delimiter tag '" + tag
        + "' contains invalid characters; must match positive character class ^[a-zA-Z0-9_-]{1,256}$";
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
    Matcher m = CLOSING_FENCE_PATTERN.matcher(fullInput);
    String foundClosingTag = null;
    while (m.find()) {
      String tag = m.group(1);
      if (!tag.equals(expectedTag)) {
        foundClosingTag = tag;
      }
    }

    if (foundClosingTag != null && !expectedTag.isEmpty()) {
      return "Rule STR-04 violation: Unclosed fenced string: expected closing delimiter '[" + expectedTag
          + "]\"\"\"', found mismatched closing tag '[" + foundClosingTag + "]'";
    }
    if (!expectedTag.isEmpty()) {
      return "Rule STR-04 violation: Unclosed fenced string: expected closing delimiter '[" + expectedTag + "]\"\"\"'";
    }
    return "Rule STR-04 violation: Unclosed fenced string: expected closing delimiter '[TAG]\"\"\"'";
  }

  private static String sanitizeLexerFallback(String rawMsg) {
    if (rawMsg.isEmpty()) {
      return "invalid syntax construct";
    }
    int quoteStart = rawMsg.indexOf('\'');
    int quoteEnd = rawMsg.lastIndexOf('\'');
    if (TOKEN_RECOG_PATTERN.matcher(rawMsg).find() && quoteStart >= 0 && quoteEnd > quoteStart) {
      String inner = stripTrailingNewlines(rawMsg.substring(quoteStart + 1, quoteEnd));
      return "unrecognized lexical character sequence '" + inner + "'";
    }
    return purgeResidualAntlrJargon(rawMsg);
  }

  /**
   * Eliminates any residual ANTLR recognizer phrases from a diagnostic message string.
   *
   * @param message the candidate diagnostic message
   * @return the STE-sanitized diagnostic message containing zero ANTLR generator jargon
   */
  public static String purgeResidualAntlrJargon(String message) {
    String cleaned = message;
    cleaned = TOKEN_RECOG_PATTERN.matcher(cleaned).replaceAll("unrecognized lexical character sequence ");
    cleaned = NO_VIABLE_ALT_PATTERN.matcher(cleaned).replaceAll("invalid syntax construct near ");
    cleaned = MISMATCHED_INPUT_PATTERN.matcher(cleaned).replaceAll("unexpected token ");
    cleaned = EXTRANEOUS_INPUT_PATTERN.matcher(cleaned).replaceAll("unexpected extraneous symbol ");
    cleaned = MISSING_AT_PATTERN.matcher(cleaned).replaceAll("missing required token $1 before ");
    if (cleaned.contains(" expecting {")) {
      int idx = cleaned.indexOf(" expecting {");
      String prefix = cleaned.substring(0, idx).trim();
      String setPart = cleaned.substring(idx + " expecting {".length());
      String[] elements = setPart.split("[,}]");
      if (elements.length > 4) {
        cleaned = prefix + "; expected <valid token>";
      }
    }
    cleaned = cleaned.replace(" expecting ", "; expected ");
    return cleaned;
  }

  private static String stripTrailingNewlines(String inner) {
    String res = inner;
    while (res.endsWith("\\n") || res.endsWith("\\r") || res.endsWith("\n") || res.endsWith("\r")) {
      if (res.endsWith("\\n") || res.endsWith("\\r")) {
        res = res.substring(0, res.length() - 2);
      } else {
        res = res.substring(0, res.length() - 1);
      }
    }
    return res;
  }

  private static boolean isInCollectionLiteral(@Nullable RuleContext ctx) {
    RuleContext cur = ctx;
    while (cur != null) {
      String name = cur.getClass().getSimpleName();
      if ("ListLiteralContext".equals(name)
          || "CollectionValueContext".equals(name)
          || "MapLiteralContext".equals(name)
          || "TupleLiteralContext".equals(name)) {
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
      if ("ProductTypeContext".equals(name) || "TupleTypeContext".equals(name)) {
        return true;
      }
      if (StvnVocabulary.isConstructorMatch(cur.getText(), StvnVocabulary.TYPE_TUPLE)) {
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
      if ("SumTypeContext".equals(name) && StvnVocabulary.isConstructorMatch(cur.getText(), StvnVocabulary.TYPE_UNION)) {
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
      if ("EnumDefContext".equals(name)
          || cur.getText().startsWith("[")
          || StvnVocabulary.isConstructorMatch(cur.getText(), StvnVocabulary.TYPE_ENUM)) {
        return true;
      }
      cur = cur.parent;
    }
    return false;
  }

  private static boolean isSingleArgCollectionKeyword(@Nullable String text) {
    return StvnVocabulary.TYPE_SEQ.equals(text)
        || StvnVocabulary.TYPE_SET.equals(text)
        || StvnVocabulary.TYPE_OPTION.equals(text);
  }

  private static boolean isTwoArgCollectionKeyword(@Nullable String text) {
    return StvnVocabulary.TYPE_MAP.equals(text)
        || StvnVocabulary.TYPE_EITHER.equals(text);
  }

  private static boolean isSingleArgumentCollection(@Nullable RuleContext ctx) {
    RuleContext cur = ctx;
    while (cur != null) {
      String text = cur.getText();
      if (StvnVocabulary.isConstructorMatch(text, StvnVocabulary.TYPE_SEQ)
          || StvnVocabulary.isConstructorMatch(text, StvnVocabulary.TYPE_SET)
          || StvnVocabulary.isConstructorMatch(text, StvnVocabulary.TYPE_OPTION)) {
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
      if (StvnVocabulary.isConstructorMatch(text, StvnVocabulary.TYPE_MAP)
          || StvnVocabulary.isConstructorMatch(text, StvnVocabulary.TYPE_EITHER)) {
        return true;
      }
      cur = cur.parent;
    }
    return false;
  }

  private static String simplifyExpectedTokenSet(IntervalSet tokens, Parser parser) {
    boolean hasAtomic = tokens.contains(StvnParser.ATOM_INT)
        || tokens.contains(StvnParser.ATOM_STRING)
        || tokens.contains(StvnParser.ATOM_BOOLEAN)
        || tokens.contains(StvnParser.ATOM_FLOAT);
    boolean hasCollection = tokens.contains(StvnParser.COLL_SEQ)
        || tokens.contains(StvnParser.COLL_SET)
        || tokens.contains(StvnParser.COLL_MAP);
    boolean hasTypeKw = tokens.contains(StvnParser.TYPE_KEYWORD_BASE);
    boolean hasValueKw = tokens.contains(StvnParser.VALUE_KEYWORD_BASE)
        || tokens.contains(StvnParser.KW_TRUE)
        || tokens.contains(StvnParser.UNION_TAG_PREFIX);

    if (hasAtomic || hasCollection || hasTypeKw) {
      return "<schema type>";
    }
    if (tokens.contains(StvnParser.KW_PACKAGE) || tokens.contains(StvnParser.KW_USE)) {
      return "<definition keyword>";
    }
    if (tokens.contains(StvnParser.LITERAL_INTEGER) && tokens.contains(StvnParser.LITERAL_STRING_SIMPLE)) {
      return "<value>";
    }
    if (hasValueKw && tokens.size() > 5) {
      return "<value keyword>";
    }
    if (tokens.contains(StvnParser.KW_EQUATABLE)
        || tokens.contains(StvnParser.KW_MIN_INCL)
        || tokens.contains(StvnParser.KW_REGEX)) {
      return "<metadata keyword>";
    }

    if (tokens.size() == 1) {
      return parser.getVocabulary().getDisplayName(tokens.getMinElement());
    }
    if (tokens.size() <= 3) {
      return tokens.toString(parser.getVocabulary());
    }

    var list = tokens.toList();
    var voc = parser.getVocabulary();
    return "{" + voc.getDisplayName(list.get(0)) + ", "
        + voc.getDisplayName(list.get(1)) + ", "
        + voc.getDisplayName(list.get(2)) + ", ... (" + list.size() + " options)}";
  }

  private static String escapeWsAndQuote(@Nullable String s) {
    if (s == null) {
      return "null";
    }
    String escaped = s.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    return "'" + escaped + "'";
  }

  private static boolean isClauseOrBoundary(@Nullable Token token) {
    if (token == null) {
      return false;
    }
    int type = token.getType();
    return type == StvnLexer.KW_META
        || type == StvnLexer.KW_DEFS
        || type == StvnLexer.KW_TYPE
        || type == StvnLexer.KW_BODY
        || type == StvnLexer.RBRACE
        || type == Token.EOF;
  }
}
