package org.stvnadore.core.parser;

import org.antlr.v4.runtime.DefaultErrorStrategy;
import org.antlr.v4.runtime.InputMismatchException;
import org.antlr.v4.runtime.NoViableAltException;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.validation.DiagnosticBag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Custom error strategy implementing STVN Section 7.3 Coordinate Pinning Invariants.
 * Prevents blame-shifting across top-level clause boundaries and suppresses destructive
 * token consumption that causes cascade stacking at closing delimiters.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnErrorStrategy extends DefaultErrorStrategy {

  private final @Nullable DiagnosticBag diagnosticBag;
  private final List<StvnDiagnostic> coalescedNumericDiagnostics = new ArrayList<>();

  /** Constructs a new coordinate-pinning parser error strategy. */
  public StvnErrorStrategy() {
    this(null);
  }

  /**
   * Constructs a new coordinate-pinning parser error strategy bound to the specified diagnostic accumulator.
   *
   * @param diagnosticBag the diagnostic accumulator, or {@code null}
   */
  public StvnErrorStrategy(@Nullable DiagnosticBag diagnosticBag) {
    super();
    this.diagnosticBag = diagnosticBag;
  }

  /**
   * Returns an unmodifiable view of Pass 1 numeric-literal underscore diagnostics coalesced during parsing.
   *
   * @return the list of coalesced numeric syntax diagnostics
   */
  public List<StvnDiagnostic> getCoalescedNumericDiagnostics() {
    return Collections.unmodifiableList(coalescedNumericDiagnostics);
  }

  @Override
  public void sync(Parser recognizer) throws RecognitionException {
    tryCoalesceContiguousNumericUnderscore(recognizer);
    super.sync(recognizer);
  }

  @Override
  public void reportError(Parser recognizer, RecognitionException e) {
    if (inErrorRecoveryMode(recognizer)) {
      return;
    }

    if (reportClauseErrorCustom(recognizer, e)) {
      beginErrorCondition(recognizer);
      return;
    }

    super.reportError(recognizer, e);
  }

  @Override
  protected void reportNoViableAlternative(Parser recognizer, NoViableAltException e) {
    Token offending = e.getOffendingToken();
    String msg = StvnSyntaxErrorSanitizer.formatSanitizedMessage(recognizer, offending, null, e);
    recognizer.notifyErrorListeners(offending, msg, e);
  }

  @Override
  protected void reportInputMismatch(Parser recognizer, InputMismatchException e) {
    Token offending = e.getOffendingToken();
    String msg = StvnSyntaxErrorSanitizer.formatSanitizedMessage(recognizer, offending, null, e);
    recognizer.notifyErrorListeners(offending, msg, e);
  }

  @Override
  protected void reportUnwantedToken(Parser recognizer) {
    if (inErrorRecoveryMode(recognizer)) {
      return;
    }
    beginErrorCondition(recognizer);
    Token t = recognizer.getCurrentToken();
    String msg = StvnSyntaxErrorSanitizer.formatSanitizedMessage(recognizer, t, null, null);
    recognizer.notifyErrorListeners(t, msg, null);
  }

  @Override
  protected void reportMissingToken(Parser recognizer) {
    if (inErrorRecoveryMode(recognizer)) {
      return;
    }
    beginErrorCondition(recognizer);
    Token t = recognizer.getCurrentToken();
    IntervalSet expecting = getExpectedTokens(recognizer);
    String expectedDesc = expecting.toString(recognizer.getVocabulary());
    String foundText = getTokenErrorDisplay(t);
    String msg = "missing required token " + expectedDesc + " before " + foundText;
    recognizer.notifyErrorListeners(t, msg, null);
  }

  private boolean reportClauseErrorCustom(Parser recognizer, RecognitionException e) {
    Token offendingToken = e.getOffendingToken();
    RuleContext ctx = recognizer.getContext();
    TokenStream stream = recognizer.getTokenStream();
    Token prevToken = stream.LT(-1);

    if (isDefsContext(ctx, prevToken) && (offendingToken == null || offendingToken.getType() != StvnParser.LBRACE)) {
      String msg = "Incomplete '" + StvnVocabulary.KEYWORD_DEFS
          + "' section: declare a definitions block '{ ... }' after '" + StvnVocabulary.KEYWORD_DEFS + "'";
      recognizer.notifyErrorListeners(prevToken != null ? prevToken : offendingToken, msg, e);
      return true;
    }

    if (isMetaContext(ctx, prevToken) && (offendingToken == null || offendingToken.getType() != StvnParser.LBRACE)) {
      String msg = "Incomplete '" + StvnVocabulary.KEYWORD_META
          + "' section: declare a metadata block '{ ... }' after '" + StvnVocabulary.KEYWORD_META + "'";
      recognizer.notifyErrorListeners(prevToken != null ? prevToken : offendingToken, msg, e);
      return true;
    }

    if (isTypeContext(ctx, prevToken) && isClauseOrBoundary(offendingToken)) {
      String msg = "Incomplete '" + StvnVocabulary.KEYWORD_TYPE
          + "' section: declare a target schema type after '" + StvnVocabulary.KEYWORD_TYPE
          + "' (for example, '" + StvnVocabulary.KEYWORD_TYPE + " " + StvnVocabulary.TYPE_INT + "')";
      recognizer.notifyErrorListeners(prevToken != null ? prevToken : offendingToken, msg, e);
      return true;
    }

    if (isBodyContext(ctx, prevToken) && isClauseOrBoundary(offendingToken)) {
      String msg = "Incomplete '" + StvnVocabulary.KEYWORD_BODY
          + "' section: declare a payload value after '" + StvnVocabulary.KEYWORD_BODY + "'";
      recognizer.notifyErrorListeners(prevToken != null ? prevToken : offendingToken, msg, e);
      return true;
    }

    return false;
  }

  @Override
  public void recover(Parser recognizer, RecognitionException e) {
    Token offendingToken = e.getOffendingToken();
    RuleContext ctx = recognizer.getContext();
    TokenStream stream = recognizer.getTokenStream();
    Token prevToken = stream.LT(-1);

    if (isDefsContext(ctx, prevToken) && (offendingToken == null || offendingToken.getType() != StvnParser.LBRACE)) {
      return;
    }
    if (isMetaContext(ctx, prevToken) && (offendingToken == null || offendingToken.getType() != StvnParser.LBRACE)) {
      return;
    }
    if (isTypeContext(ctx, prevToken) && isClauseOrBoundary(offendingToken)) {
      return;
    }
    if (isBodyContext(ctx, prevToken) && isClauseOrBoundary(offendingToken)) {
      return;
    }

    super.recover(recognizer, e);
  }

  @Override
  public Token recoverInline(Parser recognizer) throws RecognitionException {
    if (tryCoalesceContiguousNumericUnderscore(recognizer)) {
      if (recognizer.getExpectedTokens().contains(recognizer.getTokenStream().LA(1))) {
        Token matchedSymbol = recognizer.getCurrentToken();
        recognizer.consume();
        return matchedSymbol;
      }
    }
    TokenStream stream = recognizer.getTokenStream();
    Token offendingToken = stream.LT(1);
    Token prevToken = stream.LT(-1);
    RuleContext ctx = recognizer.getContext();

    if ((isDefsContext(ctx, prevToken) && (offendingToken == null || offendingToken.getType() != StvnParser.LBRACE))
        || (isMetaContext(ctx, prevToken) && (offendingToken == null || offendingToken.getType() != StvnParser.LBRACE))
        || (isTypeContext(ctx, prevToken) && isClauseOrBoundary(offendingToken))
        || (isBodyContext(ctx, prevToken) && isClauseOrBoundary(offendingToken))) {
      InputMismatchException e = new InputMismatchException(recognizer);
      reportError(recognizer, e);
      throw e;
    }

    return super.recoverInline(recognizer);
  }

  private boolean tryCoalesceContiguousNumericUnderscore(Parser recognizer) {
    TokenStream tokens = recognizer.getInputStream();
    if (tokens == null) {
      return false;
    }
    Token prev = tokens.LT(-1);
    Token curr = tokens.LT(1);
    if (prev == null
        || curr == null
        || (prev.getType() != StvnParser.LITERAL_INTEGER && prev.getType() != StvnParser.LITERAL_FLOAT)
        || curr.getType() != StvnParser.IDENTIFIER
        || curr.getText() == null
        || !curr.getText().startsWith("_")
        || prev.getStopIndex() + 1 != curr.getStartIndex()) {
      return false;
    }

    StringBuilder combined = new StringBuilder(prev.getText());
    Token endTok = prev;
    while (tokens.LA(1) != Token.EOF) {
      Token next = tokens.LT(1);
      if (next == null
          || next.getStartIndex() != endTok.getStopIndex() + 1
          || isStructuralDelimiterToken(next.getType())) {
        break;
      }
      endTok = next;
      combined.append(next.getText());
      recognizer.consume();
    }

    int startOffset = prev.getStartIndex();
    int endOffset = endTok.getStopIndex() + 1;
    int line = prev.getLine();
    int col = prev.getCharPositionInLine();
    String msg = "STVN Syntax Error: Underscore separators are prohibited in numeric literals: '" + combined + "'";

    StvnDiagnostic diag = new StvnDiagnostic(
        msg,
        StvnDiagnostic.DiagnosticSeverity.ERROR,
        line,
        col,
        startOffset,
        endOffset,
        null,
        Optional.of(DiagnosticBag.ERR_INVALID_NUMERIC_LITERAL),
        Optional.of("E002"),
        Optional.of(DiagnosticBag.ERR_INVALID_NUMERIC_LITERAL),
        diagnosticBag != null ? diagnosticBag.defaultSourcePath() : Optional.empty()
    );
    coalescedNumericDiagnostics.add(diag);
    if (diagnosticBag != null) {
      diagnosticBag.add(diag);
    } else {
      recognizer.notifyErrorListeners(prev, msg, null);
    }
    return true;
  }

  private static boolean isStructuralDelimiterToken(int tokenType) {
    return tokenType == StvnParser.LPAREN
        || tokenType == StvnParser.RPAREN
        || tokenType == StvnParser.LBRACK
        || tokenType == StvnParser.RBRACK
        || tokenType == StvnParser.LBRACE
        || tokenType == StvnParser.RBRACE;
  }

  private static boolean isDefsContext(@Nullable RuleContext ctx, @Nullable Token prevToken) {
    return prevToken != null && prevToken.getType() == StvnParser.KW_DEFS;
  }

  private static boolean isTypeContext(@Nullable RuleContext ctx, @Nullable Token prevToken) {
    return prevToken != null && prevToken.getType() == StvnParser.KW_TYPE;
  }

  private static boolean isBodyContext(@Nullable RuleContext ctx, @Nullable Token prevToken) {
    return prevToken != null && prevToken.getType() == StvnParser.KW_BODY;
  }

  private static boolean isMetaContext(@Nullable RuleContext ctx, @Nullable Token prevToken) {
    return prevToken != null && prevToken.getType() == StvnParser.KW_META;
  }

  /**
   * Determines whether the supplied token can legally begin a {@code schemaType} production.
   *
   * @param token the lookahead token to inspect, or {@code null}
   * @return {@code true} if {@code token} is a valid {@code schemaType} starter token
   */
  public static boolean isValidSchemaTypeStarter(@Nullable Token token) {
    if (token == null) {
      return false;
    }
    int type = token.getType();
    return type == StvnParser.LBRACE
        || type == StvnParser.KW_TUPLE
        || type == StvnParser.KW_ENUM
        || type == StvnParser.KW_OPTION
        || type == StvnParser.KW_EITHER
        || type == StvnParser.KW_UNION
        || type == StvnParser.ATOM_BOOLEAN
        || type == StvnParser.ATOM_INT
        || type == StvnParser.ATOM_FLOAT
        || type == StvnParser.ATOM_STRING
        || type == StvnParser.ATOM_TIME_EPOCH
        || type == StvnParser.ATOM_DATE_TIME
        || type == StvnParser.COLL_SEQ
        || type == StvnParser.COLL_SET
        || type == StvnParser.COLL_MAP
        || type == StvnParser.TYPE_KEYWORD_BASE;
  }

  /**
   * Determines whether the supplied token is a root document clause keyword
   * ({@code :meta}, {@code :defs}, {@code :type}, or {@code :body}).
   *
   * @param token the lookahead token to inspect, or {@code null}
   * @return {@code true} if {@code token} is a root document clause keyword
   */
  public static boolean isTopLevelKeyword(@Nullable Token token) {
    if (token == null) {
      return false;
    }
    int type = token.getType();
    return type == StvnParser.KW_META
        || type == StvnParser.KW_DEFS
        || type == StvnParser.KW_TYPE
        || type == StvnParser.KW_BODY;
  }

  private static boolean isClauseOrBoundary(@Nullable Token token) {
    return token == null
        || token.getType() == StvnParser.RBRACE
        || token.getType() == Token.EOF
        || isTopLevelKeyword(token);
  }
}
