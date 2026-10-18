package org.stvnadore.core.parser;

import org.antlr.v4.runtime.DefaultErrorStrategy;
import org.antlr.v4.runtime.InputMismatchException;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Custom error strategy implementing STVN Section 7.3 Coordinate Pinning Invariants.
 * Prevents blame-shifting across top-level clause boundaries and suppresses destructive
 * token consumption that causes cascade stacking at closing delimiters.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnErrorStrategy extends DefaultErrorStrategy {

  @Override
  public void reportError(Parser recognizer, RecognitionException e) {
    if (inErrorRecoveryMode(recognizer)) {
      return;
    }
    beginErrorCondition(recognizer);

    if (e instanceof InputMismatchException ime) {
      reportInputMismatchCustom(recognizer, ime);
    } else {
      super.reportError(recognizer, e);
    }
  }

  private void reportInputMismatchCustom(Parser recognizer, InputMismatchException e) {
    Token offendingToken = e.getOffendingToken();
    RuleContext ctx = recognizer.getContext();
    TokenStream stream = recognizer.getTokenStream();
    Token prevToken = stream.LT(-1);

    // Pinning Vector 1: In defsEntry, expecting LBRACE, but encountered KW_TYPE, KW_BODY, or RBRACE
    if (isDefsContext(ctx, prevToken) && isClauseOrBoundaryToken(offendingToken)) {
      String msg = "clause ':defs' requires definition block '{ ... }'";
      recognizer.notifyErrorListeners(prevToken != null ? prevToken : offendingToken, msg, e);
      return;
    }

    // Pinning Vector 2: In typeEntry, expecting schemaType, but encountered KW_BODY or RBRACE
    if (isTypeContext(ctx, prevToken) && (isBodyToken(offendingToken) || isBoundaryToken(offendingToken))) {
      String msg = "clause ':type' requires a schema type";
      recognizer.notifyErrorListeners(prevToken != null ? prevToken : offendingToken, msg, e);
      return;
    }

    // Pinning Vector 3: In bodyEntry, expecting value, but encountered RBRACE
    if (isBodyContext(ctx, prevToken) && isBoundaryToken(offendingToken)) {
      String msg = "clause ':body' requires a value payload";
      recognizer.notifyErrorListeners(prevToken != null ? prevToken : offendingToken, msg, e);
      return;
    }

    // Pinning Vector 4: In metaEntry, expecting metaBlock, but encountered KW_DEFS, KW_TYPE, or RBRACE
    if (isMetaContext(ctx, prevToken) && isClauseOrBoundaryToken(offendingToken)) {
      String msg = "clause ':meta' requires a metadata block '{ ... }'";
      recognizer.notifyErrorListeners(prevToken != null ? prevToken : offendingToken, msg, e);
      return;
    }

    super.reportInputMismatch(recognizer, e);
  }

  @Override
  public void recover(Parser recognizer, RecognitionException e) {
    Token offendingToken = e.getOffendingToken();
    RuleContext ctx = recognizer.getContext();
    TokenStream stream = recognizer.getTokenStream();
    Token prevToken = stream.LT(-1);

    // Non-destructive recovery: Do NOT consume following clause keywords or boundary braces!
    if (isDefsContext(ctx, prevToken) && isClauseOrBoundaryToken(offendingToken)) {
      return;
    }
    if (isTypeContext(ctx, prevToken) && (isBodyToken(offendingToken) || isBoundaryToken(offendingToken))) {
      return;
    }
    if (isBodyContext(ctx, prevToken) && isBoundaryToken(offendingToken)) {
      return;
    }
    if (isMetaContext(ctx, prevToken) && isClauseOrBoundaryToken(offendingToken)) {
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

    // Prevent single-token deletion from swallowing subsequent clause keywords
    if ((isDefsContext(ctx, prevToken) || isTypeContext(ctx, prevToken) || isBodyContext(ctx, prevToken) || isMetaContext(ctx, prevToken))
        && (isClauseOrBoundaryToken(offendingToken) || isBodyToken(offendingToken))) {
      InputMismatchException e = new InputMismatchException(recognizer);
      reportError(recognizer, e);
      throw e;
    }

    return super.recoverInline(recognizer);
  }

  private static boolean isDefsContext(RuleContext ctx, @Nullable Token prevToken) {
    return ctx instanceof StvnParser.DefsEntryContext
        || (prevToken != null && prevToken.getType() == StvnParser.KW_DEFS);
  }

  private static boolean isTypeContext(RuleContext ctx, @Nullable Token prevToken) {
    return ctx instanceof StvnParser.TypeEntryContext
        || (prevToken != null && prevToken.getType() == StvnParser.KW_TYPE);
  }

  private static boolean isBodyContext(RuleContext ctx, @Nullable Token prevToken) {
    return ctx instanceof StvnParser.BodyEntryContext
        || (prevToken != null && prevToken.getType() == StvnParser.KW_BODY);
  }

  private static boolean isMetaContext(RuleContext ctx, @Nullable Token prevToken) {
    return ctx instanceof StvnParser.MetaEntryContext
        || (prevToken != null && prevToken.getType() == StvnParser.KW_META);
  }

  private static boolean isClauseOrBoundaryToken(@Nullable Token token) {
    if (token == null) return false;
    int type = token.getType();
    return type == StvnParser.KW_META
        || type == StvnParser.KW_DEFS
        || type == StvnParser.KW_TYPE
        || type == StvnParser.KW_BODY
        || type == StvnParser.RBRACE
        || type == Token.EOF;
  }

  private static boolean isBodyToken(@Nullable Token token) {
    return token != null && token.getType() == StvnParser.KW_BODY;
  }

  private static boolean isBoundaryToken(@Nullable Token token) {
    if (token == null) return false;
    int type = token.getType();
    return type == StvnParser.RBRACE || type == Token.EOF;
  }
}
