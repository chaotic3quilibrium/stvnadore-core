package org.stvnadore.core.parser;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.core.validation.StvnSyntaxCancellationException;

import java.util.HashSet;
import java.util.Set;

/**
 * Pass 1 ANTLR error listener integrating {@link StvnSyntaxErrorSanitizer}.
 * <p>
 * Translates all lexer and parser recognition errors into STE-compliant STVN 2.0.0
 * {@link org.stvnadore.core.StvnDiagnostic} records with dual specification codes and
 * zero raw ANTLR generator jargon.
 *
 * @since 1.4.0
 */
@NullMarked
public final class StvnErrorListener extends BaseErrorListener {

  private final @Nullable DiagnosticBag diagnosticBag;
  private final boolean strict;
  private final Set<String> recordedCoordinates = new HashSet<>();
  private boolean hasClauseStructureError = false;

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
    if (this.hasClauseStructureError && offendingSymbol instanceof Token offendingToken) {
      if (offendingToken.getType() == Token.EOF && rawMsg != null && rawMsg.contains("'}'")) {
        return;
      }
      if (offendingToken.getType() == StvnLexer.RBRACE
          && rawMsg != null
          && (rawMsg.contains("<value>") || rawMsg.contains("<schema type>"))) {
        return;
      }
    }

    var sanitized = StvnSyntaxErrorSanitizer.sanitize(
        recognizer,
        offendingSymbol,
        line,
        charPositionInLine,
        rawMsg,
        e
    );

    String coordKey = sanitized.line() + ":" + sanitized.column() + ":" + sanitized.startOffset();
    if (this.recordedCoordinates.contains(coordKey)) {
      return;
    }
    this.recordedCoordinates.add(coordKey);

    if (sanitized.clauseStructureError()) {
      this.hasClauseStructureError = true;
    }

    if (this.strict) {
      throw new StvnSyntaxCancellationException(
          sanitized.message(),
          sanitized.line(),
          sanitized.column(),
          sanitized.startOffset(),
          sanitized.endOffset(),
          e,
          sanitized.canonicalCode(),
          sanitized.symbolicId()
      );
    } else if (this.diagnosticBag != null) {
      this.diagnosticBag.add(sanitized.toDiagnostic(e));
    }
  }

  /**
   * Sanitizes ANTLR raw error messages via {@link StvnSyntaxErrorSanitizer}.
   *
   * @param recognizer     the ANTLR parser or lexer instance
   * @param offendingToken the offending token, or {@code null}
   * @param rawMsg         the raw message from ANTLR
   * @param e              the underlying recognition exception, or {@code null}
   * @return a clean, STE-compliant diagnostic message
   */
  public static String formatSanitizedMessage(
      Recognizer<?, ?> recognizer,
      @Nullable Token offendingToken,
      String rawMsg,
      @Nullable RecognitionException e
  ) {
    return StvnSyntaxErrorSanitizer.formatSanitizedMessage(recognizer, offendingToken, rawMsg, e);
  }
}
