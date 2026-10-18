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

    if (reportClauseErrorCustom(recognizer, e)) {
      beginErrorCondition(recognizer);
      return;
    }

    super.reportError(recognizer, e);
  }

  private boolean reportClauseErrorCustom(Parser recognizer, RecognitionException e) {
    Token offendingToken = e.getOffendingToken();
    RuleContext ctx = recognizer.getContext();
    TokenStream stream = recognizer.getTokenStream();
    Token prevToken = stream.LT(-1);

    // Pinning Vector 1: In defsEntry, expecting LBRACE; attribute blame unconditionally to KW_DEFS
    if (isDefsContext(ctx, prevToken) && (offendingToken == null || offendingToken.getType() != StvnParser.LBRACE)) {
      String msg = "clause ':defs' requires definition block '{ ... }'";
      recognizer.notifyErrorListeners(prevToken != null ? prevToken : offendingToken, msg, e);
      return true;
    }

    // Pinning Vector 2: In metaEntry, expecting LBRACE; attribute blame unconditionally to KW_META
    if (isMetaContext(ctx, prevToken) && (offendingToken == null || offendingToken.getType() != StvnParser.LBRACE)) {
      String msg = "clause ':meta' requires a metadata block '{ ... }'";
      recognizer.notifyErrorListeners(prevToken != null ? prevToken : offendingToken, msg, e);
      return true;
    }

    // Pinning Vector 3: In typeEntry, expecting schemaType; attribute blame unconditionally to KW_TYPE
    if (isTypeContext(ctx, prevToken) && (offendingToken == null || !isValidSchemaTypeStarter(offendingToken))) {
      String msg = "clause ':type' requires a schema type";
      recognizer.notifyErrorListeners(prevToken != null ? prevToken : offendingToken, msg, e);
      return true;
    }

    // Pinning Vector 4: In bodyEntry, expecting value; attribute blame unconditionally to KW_BODY
    if (isBodyContext(ctx, prevToken) && (offendingToken == null || offendingToken.getType() == StvnParser.RBRACE || offendingToken.getType() == Token.EOF || isTopLevelKeyword(offendingToken))) {
      String msg = "clause ':body' requires a value payload";
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

    // Non-destructive recovery: Do NOT consume following tokens; pop rule cleanly
    if (isDefsContext(ctx, prevToken) && (offendingToken == null || offendingToken.getType() != StvnParser.LBRACE)) {
      return;
    }
    if (isMetaContext(ctx, prevToken) && (offendingToken == null || offendingToken.getType() != StvnParser.LBRACE)) {
      return;
    }
    if (isTypeContext(ctx, prevToken) && (offendingToken == null || !isValidSchemaTypeStarter(offendingToken))) {
      return;
    }
    if (isBodyContext(ctx, prevToken) && (offendingToken == null || offendingToken.getType() == StvnParser.RBRACE || offendingToken.getType() == Token.EOF || isTopLevelKeyword(offendingToken))) {
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

    // Prevent single-token deletion from swallowing subsequent clause tokens or typos
    if ((isDefsContext(ctx, prevToken) && (offendingToken == null || offendingToken.getType() != StvnParser.LBRACE))
        || (isMetaContext(ctx, prevToken) && (offendingToken == null || offendingToken.getType() != StvnParser.LBRACE))
        || (isTypeContext(ctx, prevToken) && (offendingToken == null || !isValidSchemaTypeStarter(offendingToken)))
        || (isBodyContext(ctx, prevToken) && (offendingToken == null || offendingToken.getType() == StvnParser.RBRACE || offendingToken.getType() == Token.EOF || isTopLevelKeyword(offendingToken)))) {
      InputMismatchException e = new InputMismatchException(recognizer);
      reportError(recognizer, e);
      throw e;
    }

    return super.recoverInline(recognizer);
  }

  private static boolean isDefsContext(RuleContext ctx, @Nullable Token prevToken) {
    return prevToken != null && prevToken.getType() == StvnParser.KW_DEFS;
  }

  private static boolean isTypeContext(RuleContext ctx, @Nullable Token prevToken) {
    return prevToken != null && prevToken.getType() == StvnParser.KW_TYPE;
  }

  private static boolean isBodyContext(RuleContext ctx, @Nullable Token prevToken) {
    return prevToken != null && prevToken.getType() == StvnParser.KW_BODY;
  }

  private static boolean isMetaContext(RuleContext ctx, @Nullable Token prevToken) {
    return prevToken != null && prevToken.getType() == StvnParser.KW_META;
  }

  public static boolean isValidSchemaTypeStarter(@Nullable Token token) {
    if (token == null) return false;
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

  public static boolean isTopLevelKeyword(@Nullable Token token) {
    if (token == null) return false;
    int type = token.getType();
    return type == StvnParser.KW_META
        || type == StvnParser.KW_DEFS
        || type == StvnParser.KW_TYPE
        || type == StvnParser.KW_BODY;
  }
}
