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
import org.stvnadore.core.StvnVocabulary;

/**
 * Custom error strategy implementing STVN Section 7.3 Coordinate Pinning Invariants.
 * Prevents blame-shifting across top-level clause boundaries and suppresses destructive
 * token consumption that causes cascade stacking at closing delimiters.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnErrorStrategy extends DefaultErrorStrategy {

  /** Constructs a new coordinate-pinning parser error strategy. */
  public StvnErrorStrategy() {
    super();
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
      String msg = "clause '" + StvnVocabulary.KEYWORD_DEFS + "' requires definition block '{ ... }'";
      recognizer.notifyErrorListeners(prevToken != null ? prevToken : offendingToken, msg, e);
      return true;
    }

    if (isMetaContext(ctx, prevToken) && (offendingToken == null || offendingToken.getType() != StvnParser.LBRACE)) {
      String msg = "clause '" + StvnVocabulary.KEYWORD_META + "' requires a metadata block '{ ... }'";
      recognizer.notifyErrorListeners(prevToken != null ? prevToken : offendingToken, msg, e);
      return true;
    }

    if (isTypeContext(ctx, prevToken) && (offendingToken == null || !isValidSchemaTypeStarter(offendingToken))) {
      String msg = "clause '" + StvnVocabulary.KEYWORD_TYPE + "' requires a schema type";
      recognizer.notifyErrorListeners(prevToken != null ? prevToken : offendingToken, msg, e);
      return true;
    }

    if (isBodyContext(ctx, prevToken)
        && (offendingToken == null
            || offendingToken.getType() == StvnParser.RBRACE
            || offendingToken.getType() == Token.EOF
            || isTopLevelKeyword(offendingToken))) {
      String msg = "clause '" + StvnVocabulary.KEYWORD_BODY + "' requires a value payload";
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
    if (isTypeContext(ctx, prevToken) && (offendingToken == null || !isValidSchemaTypeStarter(offendingToken))) {
      return;
    }
    if (isBodyContext(ctx, prevToken)
        && (offendingToken == null
            || offendingToken.getType() == StvnParser.RBRACE
            || offendingToken.getType() == Token.EOF
            || isTopLevelKeyword(offendingToken))) {
      return;
    }

    super.recover(recognizer, e);
  }

  @Override
  public Token recoverInline(Parser recognizer) throws RecognitionException {
    TokenStream stream = recognizer.getTokenStream();
    Token offendingToken = stream.LT(1);
    Token prevToken = stream.LT(-1);
    RuleContext ctx = recognizer.getContext();

    if ((isDefsContext(ctx, prevToken) && (offendingToken == null || offendingToken.getType() != StvnParser.LBRACE))
        || (isMetaContext(ctx, prevToken) && (offendingToken == null || offendingToken.getType() != StvnParser.LBRACE))
        || (isTypeContext(ctx, prevToken) && (offendingToken == null || !isValidSchemaTypeStarter(offendingToken)))
        || (isBodyContext(ctx, prevToken)
            && (offendingToken == null
                || offendingToken.getType() == StvnParser.RBRACE
                || offendingToken.getType() == Token.EOF
                || isTopLevelKeyword(offendingToken)))) {
      InputMismatchException e = new InputMismatchException(recognizer);
      reportError(recognizer, e);
      throw e;
    }

    return super.recoverInline(recognizer);
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
}
