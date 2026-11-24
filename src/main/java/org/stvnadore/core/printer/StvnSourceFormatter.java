package org.stvnadore.core.printer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.lowering.StvnDefsLowerer;
import org.stvnadore.core.parser.StvnErrorListener;
import org.stvnadore.core.parser.StvnErrorStrategy;
import org.stvnadore.core.parser.StvnLexer;
import org.stvnadore.core.parser.StvnParser;
import org.stvnadore.core.parser.StvnSyntaxErrorSanitizer;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.core.validation.StvnSyntaxCancellationException;

/**
 * Non-destructive authoring source formatter for STVN 2.0.0 documents (Spec § 3.3, Operation 1).
 * <p>
 * Operates directly on the Pass 1 Concrete Syntax Tree ({@link StvnParser#stvnDocument()}) and
 * hidden-channel {@code //} line comment tokens ({@link CommonTokenStream}), succeeding even when
 * Pass 2–4 semantic errors exist in {@code :defs}, {@code :type}, or {@code :body}.
 * <p>
 * Preserves 100% of authoring constructs—including standalone and trailing {@code //} comments,
 * authored blank lines (collapsing 2 or more consecutive blank lines to at most 1 blank line inside
 * blocks), {@code :package} blocks, {@code :use} and {@code :include} directives, {@code #CONST}
 * definitions and references, unreferenced {@code :defs}, user-authored short/long keywords
 * ({@code #T} vs. {@code #TRUE}, {@code #S} vs. {@code #Some}), fenced string tags
 * ({@code """[SQL]..."""}), and explicit vs. inferred sum tags—while enforcing:
 * <ul>
 *   <li>2-space structural indentation hierarchy ({@code indentWidth = 2}), including Layout 1
 *       and Layout 2 multi-line {@code :use} and {@code :include} directives</li>
 *   <li>Section-scoped container wrapping ({@link StvnSourceFormatterOptions}) across schema
 *       declarations and payload containers</li>
 *   <li>1-based target-column anchoring across tabular same-line gaps ({@code authoredSpaces >= 2})
 *       and bidirectional peer-aware trailing {@code //} comment alignment (§ 3.3.1)</li>
 *   <li>Canonical root-section ordering ({@code :meta} -&gt; {@code :defs} -&gt; {@code :type} -&gt; {@code :body}, § 1.1)</li>
 *   <li>{@code :meta} key ordering ({@code #name} -&gt; {@code #domain} -&gt; {@code #kind})</li>
 *   <li>Canonical 5-tier decimal metadata facet ordering ({@code 1.1.1} through {@code 5.0}, § 6.1)</li>
 * </ul>
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnSourceFormatter {

  private static final String INDENT_UNIT = "  ";

  private StvnSourceFormatter() {
    // Static utility class
  }

  /**
   * Formats a Pass 1 syntactically valid STVN 2.0.0 source string into its canonical
   * non-destructive authoring representation (without a trailing newline) using
   * {@link StvnSourceFormatterOptions#DEFAULT}.
   *
   * @param source the raw STVN 2.0.0 authoring source text
   * @return the formatted STVN 2.0.0 authoring source text
   * @throws NullPointerException            if {@code source} is {@code null}
   * @throws StvnSyntaxCancellationException if Pass 1 lexical or syntactic validation fails
   * @since 2.0.0
   */
  public static String format(String source) {
    return format(source, StvnSourceFormatterOptions.DEFAULT);
  }

  /**
   * Formats a Pass 1 syntactically valid STVN 2.0.0 source string into its canonical
   * non-destructive authoring representation (without a trailing newline) using explicit
   * {@link StvnSourceFormatterOptions}.
   *
   * @param source  the raw STVN 2.0.0 authoring source text
   * @param options section-scoped schema and payload container wrap policies
   * @return the formatted STVN 2.0.0 authoring source text
   * @throws NullPointerException            if {@code source} or {@code options} is {@code null}
   * @throws StvnSyntaxCancellationException if Pass 1 lexical or syntactic validation fails
   * @since 2.0.0
   */
  public static String format(String source, StvnSourceFormatterOptions options) {
    Objects.requireNonNull(source, "source must not be null");
    Objects.requireNonNull(options, "options must not be null");

    StvnSyntaxErrorSanitizer.sanitizeNonFencedTabs(source, null, true);

    StvnLexer lexer = new StvnLexer(CharStreams.fromString(source));
    lexer.removeErrorListeners();
    StvnErrorListener strictListener = StvnErrorListener.strict();
    lexer.addErrorListener(strictListener);

    CommonTokenStream tokenStream = new CommonTokenStream(lexer);
    StvnParser parser = new StvnParser(tokenStream);
    parser.removeErrorListeners();
    parser.addErrorListener(strictListener);
    parser.setErrorHandler(new StvnErrorStrategy());

    StvnParser.StvnDocumentContext docCtx = parser.stvnDocument();
    StvnSyntaxErrorSanitizer.scanMalformedFenceTokens(tokenStream, parser, null, true);
    StvnDefsLowerer.validateDocumentBodyStructureForFormatter(docCtx);
    tokenStream.fill();

    for (Token token : tokenStream.getTokens()) {
      if (token.getType() == StvnLexer.TAB_CHARACTER) {
        throw new StvnSyntaxCancellationException(
            "STVN Syntax Error: Tab character ('\\t', U+0009) is strictly forbidden in STVN syntax outside fenced string literals; use standard spaces instead (ERR_TAB_CHARACTER_FORBIDDEN)",
            token.getLine(),
            token.getCharPositionInLine(),
            token.getStartIndex(),
            Math.max(token.getStartIndex() + 1, token.getStopIndex() + 1),
            null,
            "E001",
            DiagnosticBag.ERR_TAB_CHARACTER_FORBIDDEN
        );
      }
    }

    FormatterCursor cursor = new FormatterCursor(source, tokenStream.getTokens(), options);
    return cursor.formatDocument(docCtx);
  }

  /**
   * Attempts to format a STVN 2.0.0 source string using {@link StvnSourceFormatterOptions#DEFAULT}
   * if Pass 1 lexical and syntactic validation succeeds with zero errors.
   *
   * @param source the raw STVN 2.0.0 authoring source text, or {@code null}
   * @return {@code Optional.of(formatted)} when Pass 1 succeeds with 0 syntax errors,
   *         or {@code Optional.empty()} if {@code source} is {@code null} or contains Pass 1 syntax errors
   * @since 2.0.0
   */
  public static Optional<String> tryFormat(@Nullable String source) {
    return tryFormat(source, StvnSourceFormatterOptions.DEFAULT);
  }

  /**
   * Attempts to format a STVN 2.0.0 source string using explicit {@link StvnSourceFormatterOptions}
   * if Pass 1 lexical and syntactic validation succeeds with zero errors.
   *
   * @param source  the raw STVN 2.0.0 authoring source text, or {@code null}
   * @param options section-scoped schema and payload container wrap policies
   * @return {@code Optional.of(formatted)} when Pass 1 succeeds with 0 syntax errors,
   *         or {@code Optional.empty()} if {@code source} is {@code null} or contains Pass 1 syntax errors
   * @throws NullPointerException if {@code options} is {@code null}
   * @since 2.0.0
   */
  public static Optional<String> tryFormat(@Nullable String source, StvnSourceFormatterOptions options) {
    Objects.requireNonNull(options, "options must not be null");
    if (source == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(format(source, options));
    } catch (StvnSyntaxCancellationException e) {
      return Optional.empty();
    }
  }

  private static int metaElementRank(StvnParser.MetaElementContext ctx) {
    if (ctx.metaNameFacet() != null) {
      return 1;
    }
    if (ctx.metaDomainFacet() != null) {
      return 2;
    }
    if (ctx.metaKindFacet() != null) {
      return 3;
    }
    return 4;
  }

  private static int metadataFacetRank(StvnParser.MetadataEntryContext ctx) {
    String kw = ctx.getStart().getText();
    return switch (kw) {
      // Tier 1.1: Representation / Mode
      case StvnVocabulary.FACET_KW_UNSIGNED -> 1110;
      case StvnVocabulary.FACET_KW_EXACT -> 1120;
      case StvnVocabulary.FACET_KW_INVERTIBLE -> 1130;
      // Tier 1.2: Temporal Scale & DateTime Mode
      case StvnVocabulary.FACET_KW_SCALE_NS -> 1210;
      case StvnVocabulary.FACET_KW_SCALE_US -> 1220;
      case StvnVocabulary.FACET_KW_SCALE_MS -> 1230;
      case StvnVocabulary.FACET_KW_SCALE_S -> 1240;
      case StvnVocabulary.FACET_KW_OFFSET -> 1250;
      case StvnVocabulary.FACET_KW_ZONED -> 1260;
      case StvnVocabulary.FACET_KW_AUDITED -> 1270;
      // Tier 1.3: Machine Bit-Width
      case StvnVocabulary.FACET_KW_SIZE -> 1310;
      // Tier 1.5: Cardinality / Length Bounds
      case StvnVocabulary.FACET_KW_MIN_SIZE -> 1510;
      case StvnVocabulary.FACET_KW_MAX_SIZE -> 1520;
      // Tier 2.0: Algebraic Traits
      case StvnVocabulary.FACET_KW_EQUATABLE -> 2100;
      case StvnVocabulary.FACET_KW_COMPARABLE -> 2200;
      // Tier 3.0: Value Domain Bounds
      case StvnVocabulary.FACET_KW_MIN_INCL -> 3100;
      case StvnVocabulary.FACET_KW_MIN_EXCL -> 3200;
      case StvnVocabulary.FACET_KW_MAX_INCL -> 3300;
      case StvnVocabulary.FACET_KW_MAX_EXCL -> 3400;
      // Tier 4.0: Lexical & Subset Constraints
      case StvnVocabulary.FACET_KW_FILTER_INCL -> 4100;
      case StvnVocabulary.FACET_KW_FILTER_EXCL -> 4200;
      case StvnVocabulary.FACET_KW_PRESERVE_INDENT -> 4300;
      case StvnVocabulary.FACET_KW_REGEX -> 4400;
      // Tier 5.0: Directive Modifiers
      case StvnVocabulary.FACET_KW_STRIP -> 5000;
      default -> 9000;
    };
  }

  private static @Nullable String metadataMutexGroup(String kw) {
    return switch (kw) {
      case StvnVocabulary.FACET_KW_SCALE_NS,
           StvnVocabulary.FACET_KW_SCALE_US,
           StvnVocabulary.FACET_KW_SCALE_MS,
           StvnVocabulary.FACET_KW_SCALE_S -> "TEMPORAL_SCALE";
      case StvnVocabulary.FACET_KW_OFFSET,
           StvnVocabulary.FACET_KW_ZONED,
           StvnVocabulary.FACET_KW_AUDITED -> "TEMPORAL_MODE";
      case StvnVocabulary.FACET_KW_EXACT,
           StvnVocabulary.FACET_KW_SIZE -> "SIZE_EXACT";
      case StvnVocabulary.FACET_KW_MIN_INCL,
           StvnVocabulary.FACET_KW_MIN_EXCL -> "MIN_BOUND";
      case StvnVocabulary.FACET_KW_MAX_INCL,
           StvnVocabulary.FACET_KW_MAX_EXCL -> "MAX_BOUND";
      case StvnVocabulary.FACET_KW_FILTER_INCL,
           StvnVocabulary.FACET_KW_FILTER_EXCL -> "ENUM_FILTER";
      default -> null;
    };
  }

  private static ToIntFunction<StvnParser.MetadataEntryContext> createMutexStabilizedMetadataRanker() {
    Map<String, Integer> firstRankByMutexGroup = new HashMap<>();
    return ctx -> {
      String kw = ctx.getStart().getText();
      int baseRank = metadataFacetRank(ctx);
      String mutexGroup = metadataMutexGroup(kw);
      if (mutexGroup == null) {
        return baseRank;
      }
      return firstRankByMutexGroup.computeIfAbsent(mutexGroup, k -> baseRank);
    };
  }

  private record CommentLine(
      Token token,
      boolean blankLineBefore
  ) {}

  private record CommentedBlockSlice<T extends ParserRuleContext>(
      int rank,
      int originalOrder,
      T ctx,
      List<CommentLine> leadingComments,
      boolean hadBlankLineBefore,
      boolean blankLineBeforeEntry,
      @Nullable Token trailingComment
  ) {}

  private record PartitionedCommentedBlock<T extends ParserRuleContext>(
      List<CommentedBlockSlice<T>> slices,
      List<CommentLine> footerComments,
      boolean footerHadBlankLineBefore,
      Map<Integer, Integer> peerTrailingCommentColCounts
  ) {}

  private record RootSectionSlice(
      int canonicalRank,
      int authoredIndex,
      ParserRuleContext ctx,
      List<Token> leadingComments,
      int interiorCommentStartIdx,
      int interiorCommentEndIdxExclusive,
      @Nullable Token trailingSameLineComment
  ) {}

  private static int sectionCanonicalRank(ParserRuleContext ctx) {
    if (ctx instanceof StvnParser.MetaEntryContext) {
      return 1;
    }
    if (ctx instanceof StvnParser.DefsEntryContext) {
      return 2;
    }
    if (ctx instanceof StvnParser.TypeEntryContext) {
      return 3;
    }
    return 4;
  }

  private static boolean isCanonicalSectionOrder(List<ParserRuleContext> authoredSections) {
    int lastRank = 0;
    for (ParserRuleContext section : authoredSections) {
      int rank = sectionCanonicalRank(section);
      if (rank <= lastRank) {
        return false;
      }
      lastRank = rank;
    }
    return true;
  }

  private static final class FormatterCursor {

    private final String source;
    private final List<Token> allTokens;
    private final List<Token> comments;
    private final StvnSourceFormatterOptions options;
    private final Set<Integer> lineStartTokenIndices;
    private final Map<Integer, Integer> trailingCommentColByLine;
    private final StringBuilder sb = new StringBuilder();
    private int nextCommentIdx = 0;
    private int activeCommentEndIdxExclusive;
    private int lastEmittedLine = -1;
    private boolean currentOutputLineAnchoredToInputLineStart = true;
    private boolean inSingleLineMetadataMap = false;

    FormatterCursor(String source, List<Token> allTokens, StvnSourceFormatterOptions options) {
      this.source = source;
      this.allTokens = List.copyOf(allTokens);
      this.options = options;
      List<Token> commentList = new ArrayList<>();
      Set<Integer> lineStarts = new HashSet<>();
      Map<Integer, Integer> trailingByLine = new HashMap<>();
      int lastDefaultLine = -1;
      for (Token token : allTokens) {
        if (token.getChannel() == Token.DEFAULT_CHANNEL && token.getType() != Token.EOF) {
          if (token.getLine() != lastDefaultLine) {
            lineStarts.add(token.getTokenIndex());
            lastDefaultLine = token.getLine();
          }
        } else if (token.getType() == StvnLexer.COMMENT) {
          commentList.add(token);
          if (lastDefaultLine == token.getLine()) {
            trailingByLine.put(token.getLine(), token.getCharPositionInLine());
          }
        }
      }
      this.comments = List.copyOf(commentList);
      this.lineStartTokenIndices = Set.copyOf(lineStarts);
      this.trailingCommentColByLine = Map.copyOf(trailingByLine);
      this.activeCommentEndIdxExclusive = this.comments.size();
    }

    private int currentLineCol() {
      return sb.length() - (sb.lastIndexOf("\n") + 1);
    }

    private void markLineAnchor(Token firstTokOnOutputLine) {
      currentOutputLineAnchoredToInputLineStart =
          lineStartTokenIndices.contains(firstTokOnOutputLine.getTokenIndex());
    }

    private void appendAlignableSpace(Token leftStop, Token rightStart) {
      if (!inSingleLineMetadataMap
          && currentOutputLineAnchoredToInputLineStart
          && leftStop.getLine() == rightStart.getLine()
          && !hasCommentsBetween(leftStop.getTokenIndex(), rightStart.getTokenIndex())) {
        int authoredSpaces = rightStart.getStartIndex() - leftStop.getStopIndex() - 1;
        if (authoredSpaces >= 2) {
          int origTargetCol = rightStart.getCharPositionInLine();
          int spaces = Math.max(1, origTargetCol - currentLineCol());
          sb.append(" ".repeat(spaces));
          return;
        }
      }
      sb.append(' ');
    }

    String formatDocument(StvnParser.StvnDocumentContext docCtx) {
      Token openBrace = docCtx.LBRACE().getSymbol();
      Token closeBrace = docCtx.RBRACE().getSymbol();

      // 1. Leading comments before root '{'
      boolean hadLeadingComment = false;
      while (nextCommentIdx < activeCommentEndIdxExclusive
          && comments.get(nextCommentIdx).getTokenIndex() < openBrace.getTokenIndex()) {
        Token c = comments.get(nextCommentIdx++);
        if (hadLeadingComment && c.getLine() - lastEmittedLine >= 2) {
          sb.append('\n');
        }
        sb.append(c.getText()).append('\n');
        lastEmittedLine = c.getLine();
        hadLeadingComment = true;
      }

      // 2. Root '{'
      markLineAnchor(openBrace);
      sb.append(StvnVocabulary.DELIM_OPEN_BRACE);
      lastEmittedLine = openBrace.getLine();

      StvnParser.DocumentBodyContext body = docCtx.documentBody();
      List<ParserRuleContext> authoredSections = new ArrayList<>(4);
      if (body != null && body.children != null) {
        for (var child : body.children) {
          if (child instanceof StvnParser.MetaEntryContext
              || child instanceof StvnParser.DefsEntryContext
              || child instanceof StvnParser.TypeEntryContext
              || child instanceof StvnParser.BodyEntryContext) {
            authoredSections.add((ParserRuleContext) child);
          }
        }
      }

      if (isCanonicalSectionOrder(authoredSections)) {
        int rootOpenLimit = authoredSections.isEmpty()
            ? closeBrace.getTokenIndex()
            : authoredSections.get(0).getStart().getTokenIndex();
        appendTrailingCommentIfOnLine(openBrace.getLine(), rootOpenLimit);

        boolean emittedInRoot = false;
        for (int i = 0; i < authoredSections.size(); i++) {
          ParserRuleContext section = authoredSections.get(i);
          int sectionLimit = (i + 1 < authoredSections.size())
              ? authoredSections.get(i + 1).getStart().getTokenIndex()
              : closeBrace.getTokenIndex();
          emittedInRoot = flushStandaloneCommentsBefore(
              section.getStart().getTokenIndex(),
              1,
              emittedInRoot
          );
          startNewBlockChildLine(1, section.getStart(), emittedInRoot);
          emittedInRoot = true;

          formatRootSection(section, 1, sectionLimit);
        }

        flushStandaloneCommentsBefore(closeBrace.getTokenIndex(), 1, emittedInRoot);
        sb.append('\n');
        markLineAnchor(closeBrace);
        sb.append(StvnVocabulary.DELIM_CLOSE_BRACE);
        lastEmittedLine = closeBrace.getLine();
        appendTrailingCommentIfOnLine(closeBrace.getLine(), Integer.MAX_VALUE);

        while (nextCommentIdx < activeCommentEndIdxExclusive) {
          Token trailingDocComment = comments.get(nextCommentIdx++);
          sb.append('\n').append(trailingDocComment.getText());
          lastEmittedLine = trailingDocComment.getLine();
        }

        return sb.toString();
      }

      // Non-canonical root-section order: partition slices with leading, interior, and same-line trailing comments
      int rootOpenLimit = authoredSections.get(0).getStart().getTokenIndex();
      appendTrailingCommentIfOnLine(openBrace.getLine(), rootOpenLimit);

      List<RootSectionSlice> slices = new ArrayList<>(authoredSections.size());
      for (int i = 0; i < authoredSections.size(); i++) {
        ParserRuleContext section = authoredSections.get(i);
        int secStartTok = section.getStart().getTokenIndex();
        int secStopTok = section.getStop().getTokenIndex();
        int nextAuthoredLimit = (i + 1 < authoredSections.size())
            ? authoredSections.get(i + 1).getStart().getTokenIndex()
            : closeBrace.getTokenIndex();

        List<Token> leading = new ArrayList<>();
        while (nextCommentIdx < comments.size()
            && comments.get(nextCommentIdx).getTokenIndex() < secStartTok) {
          leading.add(comments.get(nextCommentIdx++));
        }

        int interiorStart = nextCommentIdx;
        while (nextCommentIdx < comments.size()
            && comments.get(nextCommentIdx).getTokenIndex() <= secStopTok) {
          nextCommentIdx++;
        }
        int interiorEndExclusive = nextCommentIdx;

        Token trailingSameLine = null;
        if (nextCommentIdx < comments.size()) {
          Token candidate = comments.get(nextCommentIdx);
          if (candidate.getTokenIndex() < nextAuthoredLimit
              && candidate.getLine() == section.getStop().getLine()) {
            trailingSameLine = candidate;
            nextCommentIdx++;
          }
        }

        slices.add(new RootSectionSlice(
            sectionCanonicalRank(section),
            i,
            section,
            List.copyOf(leading),
            interiorStart,
            interiorEndExclusive,
            trailingSameLine
        ));
      }

      List<Token> rootFooterComments = new ArrayList<>();
      while (nextCommentIdx < comments.size()
          && comments.get(nextCommentIdx).getTokenIndex() < closeBrace.getTokenIndex()) {
        rootFooterComments.add(comments.get(nextCommentIdx++));
      }
      int postCloseBraceCommentIdx = nextCommentIdx;

      slices.sort(Comparator.comparingInt(RootSectionSlice::canonicalRank)
          .thenComparingInt(RootSectionSlice::authoredIndex));

      for (RootSectionSlice slice : slices) {
        Token prevLeading = null;
        for (Token c : slice.leadingComments()) {
          if (prevLeading != null && c.getLine() - prevLeading.getLine() >= 2) {
            sb.append('\n');
          }
          sb.append('\n').append(indentStr(1)).append(c.getText());
          prevLeading = c;
        }
        if (prevLeading != null && slice.ctx().getStart().getLine() - prevLeading.getLine() >= 2) {
          sb.append('\n');
        }
        sb.append('\n').append(indentStr(1));
        markLineAnchor(slice.ctx().getStart());

        nextCommentIdx = slice.interiorCommentStartIdx();
        activeCommentEndIdxExclusive = slice.interiorCommentEndIdxExclusive();
        lastEmittedLine = slice.ctx().getStart().getLine();

        formatRootSection(slice.ctx(), 1, slice.ctx().getStop().getTokenIndex() + 1);

        if (slice.trailingSameLineComment() != null) {
          Token tc = slice.trailingSameLineComment();
          sb.append(trailingCommentPadding(tc, null)).append(tc.getText());
        }
      }

      Token prevFooter = null;
      for (Token c : rootFooterComments) {
        if (prevFooter != null && c.getLine() - prevFooter.getLine() >= 2) {
          sb.append('\n');
        }
        sb.append('\n').append(indentStr(1)).append(c.getText());
        prevFooter = c;
      }

      nextCommentIdx = postCloseBraceCommentIdx;
      activeCommentEndIdxExclusive = comments.size();

      sb.append('\n');
      markLineAnchor(closeBrace);
      sb.append(StvnVocabulary.DELIM_CLOSE_BRACE);
      lastEmittedLine = closeBrace.getLine();
      appendTrailingCommentIfOnLine(closeBrace.getLine(), Integer.MAX_VALUE);

      while (nextCommentIdx < activeCommentEndIdxExclusive) {
        Token trailingDocComment = comments.get(nextCommentIdx++);
        sb.append('\n').append(trailingDocComment.getText());
        lastEmittedLine = trailingDocComment.getLine();
      }

      return sb.toString();
    }

    private void formatRootSection(ParserRuleContext section, int indent, int sectionLimit) {
      if (section instanceof StvnParser.MetaEntryContext metaCtx) {
        formatMetaEntry(metaCtx, indent, sectionLimit);
      } else if (section instanceof StvnParser.DefsEntryContext defsCtx) {
        formatDefsEntry(defsCtx, indent, sectionLimit);
      } else if (section instanceof StvnParser.TypeEntryContext typeCtx) {
        formatTypeEntry(typeCtx, indent, sectionLimit);
      } else if (section instanceof StvnParser.BodyEntryContext bodyCtx) {
        formatBodyEntry(bodyCtx, indent, sectionLimit);
      }
    }

    private void formatMetaEntry(StvnParser.MetaEntryContext ctx, int indent, int nextSectionLimit) {
      StvnParser.MetaBlockContext block = ctx.metaBlock();
      Token openBrace = block.LBRACE().getSymbol();
      Token closeBrace = block.RBRACE().getSymbol();

      sb.append(StvnVocabulary.KEYWORD_META)
          .append(' ')
          .append(StvnVocabulary.DELIM_OPEN_BRACE);
      lastEmittedLine = openBrace.getLine();

      List<StvnParser.MetaElementContext> rawElements = block.metaElement();
      PartitionedCommentedBlock<StvnParser.MetaElementContext> partitioned = partitionCommentedBlock(
          openBrace,
          closeBrace,
          rawElements,
          StvnSourceFormatter::metaElementRank
      );
      emitPartitionedCommentedBlock(partitioned, indent + 1, this::formatMetaElementInline);

      sb.append('\n').append(indentStr(indent));
      markLineAnchor(closeBrace);
      sb.append(StvnVocabulary.DELIM_CLOSE_BRACE);
      lastEmittedLine = closeBrace.getLine();
      appendTrailingCommentIfOnLine(closeBrace.getLine(), nextSectionLimit);
    }

    private void formatMetaElementInline(StvnParser.MetaElementContext ctx) {
      if (ctx.metaNameFacet() != null) {
        Token kw = ctx.metaNameFacet().KW_META_NAME().getSymbol();
        StvnParser.StringLiteralContext val = ctx.metaNameFacet().stringLiteral();
        sb.append(StvnVocabulary.FACET_KW_META_NAME);
        appendAlignableSpace(kw, val.getStart());
        sb.append(formatStringLiteralText(val));
      } else if (ctx.metaDomainFacet() != null) {
        Token kw = ctx.metaDomainFacet().KW_META_DOMAIN().getSymbol();
        StvnParser.StringLiteralContext val = ctx.metaDomainFacet().stringLiteral();
        sb.append(StvnVocabulary.FACET_KW_META_DOMAIN);
        appendAlignableSpace(kw, val.getStart());
        sb.append(formatStringLiteralText(val));
      } else if (ctx.metaKindFacet() != null) {
        Token kw = ctx.metaKindFacet().KW_META_KIND().getSymbol();
        StvnParser.MetaKindValueContext val = ctx.metaKindFacet().metaKindValue();
        sb.append(StvnVocabulary.FACET_KW_META_KIND);
        appendAlignableSpace(kw, val.getStart());
        sb.append(val.getText());
      } else if (ctx.metaUnknownFacet() != null) {
        StvnParser.MetaUnknownFacetContext unk = ctx.metaUnknownFacet();
        sb.append(unk.valueKeyword().getText());
        if (unk.value() != null) {
          appendAlignableSpace(unk.valueKeyword().getStop(), unk.value().getStart());
          formatValue(unk.value(), 2);
        } else if (unk.schemaType() != null) {
          appendAlignableSpace(unk.valueKeyword().getStop(), unk.schemaType().getStart());
          formatSchemaType(unk.schemaType(), 2);
        }
      }
    }

    private void formatDefsEntry(StvnParser.DefsEntryContext ctx, int indent, int nextSectionLimit) {
      Token openBrace = ctx.LBRACE().getSymbol();
      Token closeBrace = ctx.RBRACE().getSymbol();

      sb.append(StvnVocabulary.KEYWORD_DEFS)
          .append(' ')
          .append(StvnVocabulary.DELIM_OPEN_BRACE);
      lastEmittedLine = openBrace.getLine();

      boolean emittedInDefs = false;
      List<StvnParser.DefsElementContext> elements = ctx.defsElement();
      int openLimit = elements.isEmpty()
          ? closeBrace.getTokenIndex()
          : elements.get(0).getStart().getTokenIndex();
      appendTrailingCommentIfOnLine(openBrace.getLine(), openLimit);
      for (int i = 0; i < elements.size(); i++) {
        StvnParser.DefsElementContext el = elements.get(i);
        int nextLimit = (i + 1 < elements.size())
            ? elements.get(i + 1).getStart().getTokenIndex()
            : closeBrace.getTokenIndex();

        emittedInDefs = flushStandaloneCommentsBefore(
            el.getStart().getTokenIndex(),
            indent + 1,
            emittedInDefs
        );
        startNewBlockChildLine(indent + 1, el.getStart(), emittedInDefs);
        emittedInDefs = true;

        formatDefsElement(el, indent + 1, nextLimit);
      }

      flushStandaloneCommentsBefore(closeBrace.getTokenIndex(), indent + 1, emittedInDefs);
      sb.append('\n').append(indentStr(indent));
      markLineAnchor(closeBrace);
      sb.append(StvnVocabulary.DELIM_CLOSE_BRACE);
      lastEmittedLine = closeBrace.getLine();
      appendTrailingCommentIfOnLine(closeBrace.getLine(), nextSectionLimit);
    }

    private void formatDefsElement(StvnParser.DefsElementContext ctx, int indent, int nextTokenLimit) {
      if (ctx.packageEnclosure() != null) {
        formatPackageEnclosure(ctx.packageEnclosure(), indent, nextTokenLimit);
      } else if (ctx.useStmt() != null) {
        formatUseStmt(ctx.useStmt(), indent, nextTokenLimit);
      } else if (ctx.includeStmt() != null) {
        formatIncludeStmt(ctx.includeStmt(), indent, nextTokenLimit);
      } else if (ctx.typeDefinition() != null) {
        formatTypeDefinition(ctx.typeDefinition(), indent, nextTokenLimit);
      } else if (ctx.constantDefinition() != null) {
        formatConstantDefinition(ctx.constantDefinition(), indent, nextTokenLimit);
      }
    }

    private void formatPackageEnclosure(
        StvnParser.PackageEnclosureContext ctx,
        int indent,
        int nextTokenLimit
    ) {
      Token openBrace = ctx.LBRACE().getSymbol();
      Token closeBrace = ctx.RBRACE().getSymbol();

      sb.append(StvnVocabulary.KEYWORD_PACKAGE)
          .append(' ')
          .append(ctx.packagePath().getText())
          .append(' ')
          .append(StvnVocabulary.DELIM_OPEN_BRACE);
      lastEmittedLine = openBrace.getLine();

      boolean emittedInPkg = false;
      List<StvnParser.PackageElementContext> elements = ctx.packageElement();
      int openLimit = elements.isEmpty()
          ? closeBrace.getTokenIndex()
          : elements.get(0).getStart().getTokenIndex();
      appendTrailingCommentIfOnLine(openBrace.getLine(), openLimit);
      for (int i = 0; i < elements.size(); i++) {
        StvnParser.PackageElementContext el = elements.get(i);
        int childLimit = (i + 1 < elements.size())
            ? elements.get(i + 1).getStart().getTokenIndex()
            : closeBrace.getTokenIndex();

        emittedInPkg = flushStandaloneCommentsBefore(
            el.getStart().getTokenIndex(),
            indent + 1,
            emittedInPkg
        );
        startNewBlockChildLine(indent + 1, el.getStart(), emittedInPkg);
        emittedInPkg = true;

        formatPackageElement(el, indent + 1, childLimit);
      }

      flushStandaloneCommentsBefore(closeBrace.getTokenIndex(), indent + 1, emittedInPkg);
      sb.append('\n').append(indentStr(indent));
      markLineAnchor(closeBrace);
      sb.append(StvnVocabulary.DELIM_CLOSE_BRACE);
      lastEmittedLine = closeBrace.getLine();
      appendTrailingCommentIfOnLine(closeBrace.getLine(), nextTokenLimit);
    }

    private void formatPackageElement(
        StvnParser.PackageElementContext ctx,
        int indent,
        int nextTokenLimit
    ) {
      if (ctx.useStmt() != null) {
        formatUseStmt(ctx.useStmt(), indent, nextTokenLimit);
      } else if (ctx.typeDefinition() != null) {
        formatTypeDefinition(ctx.typeDefinition(), indent, nextTokenLimit);
      } else if (ctx.constantDefinition() != null) {
        formatConstantDefinition(ctx.constantDefinition(), indent, nextTokenLimit);
      } else if (ctx.nestedPackageIllegal() != null) {
        StvnParser.NestedPackageIllegalContext nested = ctx.nestedPackageIllegal();
        Token openBrace = nested.LBRACE().getSymbol();
        Token closeBrace = nested.RBRACE().getSymbol();
        sb.append(StvnVocabulary.KEYWORD_PACKAGE)
            .append(' ')
            .append(nested.packagePath().getText())
            .append(' ')
            .append(StvnVocabulary.DELIM_OPEN_BRACE);
        lastEmittedLine = openBrace.getLine();
        boolean emitted = false;
        List<StvnParser.PackageElementContext> children = nested.packageElement();
        int nestedOpenLimit = children.isEmpty()
            ? closeBrace.getTokenIndex()
            : children.get(0).getStart().getTokenIndex();
        appendTrailingCommentIfOnLine(openBrace.getLine(), nestedOpenLimit);
        for (int i = 0; i < children.size(); i++) {
          StvnParser.PackageElementContext child = children.get(i);
          int limit = (i + 1 < children.size())
              ? children.get(i + 1).getStart().getTokenIndex()
              : closeBrace.getTokenIndex();
          emitted = flushStandaloneCommentsBefore(child.getStart().getTokenIndex(), indent + 1, emitted);
          startNewBlockChildLine(indent + 1, child.getStart(), emitted);
          emitted = true;
          formatPackageElement(child, indent + 1, limit);
        }
        flushStandaloneCommentsBefore(closeBrace.getTokenIndex(), indent + 1, emitted);
        sb.append('\n').append(indentStr(indent));
        markLineAnchor(closeBrace);
        sb.append(StvnVocabulary.DELIM_CLOSE_BRACE);
        lastEmittedLine = closeBrace.getLine();
        appendTrailingCommentIfOnLine(closeBrace.getLine(), nextTokenLimit);
      }
    }

    private void formatUseStmt(StvnParser.UseStmtContext ctx, int baseIndent, int nextTokenLimit) {
      Token lbrack = ctx.LBRACK().getSymbol();
      Token rbrack = ctx.RBRACK().getSymbol();
      List<StvnParser.UseElementContext> elements = ctx.useElement();
      boolean hasInteriorComments = hasCommentsBetween(lbrack.getTokenIndex(), rbrack.getTokenIndex());

      // Case 1: Single-line :use [ ... ]
      if (lbrack.getLine() == rbrack.getLine() && !hasInteriorComments) {
        sb.append(StvnVocabulary.KEYWORD_USE).append(" [");
        for (int i = 0; i < elements.size(); i++) {
          sb.append(' ');
          appendUseElementInline(elements.get(i));
        }
        sb.append(" ]");
        lastEmittedLine = rbrack.getLine();
        appendTrailingCommentIfOnLine(rbrack.getLine(), nextTokenLimit);
        return;
      }

      Token firstStart = elements.get(0).getStart();

      // Case 2: Layout 1 — single-element multi-line :use starting on the '[' line (:use [ :pkg {)
      if (lbrack.getLine() == firstStart.getLine() && elements.size() == 1) {
        StvnParser.UseElementContext elem = elements.get(0);
        sb.append(StvnVocabulary.KEYWORD_USE)
            .append(" [ ")
            .append(elem.useTarget().getText());
        Token prevEnd = elem.useTarget().getStop();
        if (elem.useOptionsBlock() != null) {
          appendAlignableSpace(prevEnd, elem.useOptionsBlock().LBRACE().getSymbol());
          sb.append(formatUseOptionsBlockInline(elem.useOptionsBlock()));
          prevEnd = elem.useOptionsBlock().RBRACE().getSymbol();
        }
        if (elem.useAliasBlock() != null) {
          StvnParser.UseAliasBlockContext aliasBlock = elem.useAliasBlock();
          Token aliasOpen = aliasBlock.LBRACE().getSymbol();
          Token aliasClose = aliasBlock.RBRACE().getSymbol();
          if (elem.useOptionsBlock() != null) {
            sb.append(' ');
          } else {
            appendAlignableSpace(prevEnd, aliasOpen);
          }
          sb.append(StvnVocabulary.DELIM_OPEN_BRACE);
          lastEmittedLine = aliasOpen.getLine();

          boolean emittedAlias = false;
          List<StvnParser.UseMapAliasContext> aliases = aliasBlock.useMapAlias();
          int aliasOpenLimit = aliases.isEmpty()
              ? aliasClose.getTokenIndex()
              : aliases.get(0).getStart().getTokenIndex();
          appendTrailingCommentIfOnLine(aliasOpen.getLine(), aliasOpenLimit);
          for (int i = 0; i < aliases.size(); i++) {
            StvnParser.UseMapAliasContext alias = aliases.get(i);
            int aliasLimit = (i + 1 < aliases.size())
                ? aliases.get(i + 1).getStart().getTokenIndex()
                : aliasClose.getTokenIndex();
            emittedAlias = flushStandaloneCommentsBefore(
                alias.getStart().getTokenIndex(),
                baseIndent + 2,
                emittedAlias
            );
            startNewBlockChildLine(baseIndent + 2, alias.getStart(), emittedAlias);
            emittedAlias = true;
            appendUseMapAliasInline(alias);
            lastEmittedLine = alias.getStop().getLine();
            appendTrailingCommentIfOnLine(alias.getStop().getLine(), aliasLimit);
          }

          flushStandaloneCommentsBefore(aliasClose.getTokenIndex(), baseIndent + 2, emittedAlias);
          boolean sameLineClose = aliasClose.getLine() == rbrack.getLine()
              && !hasCommentsBetween(aliasClose.getTokenIndex(), rbrack.getTokenIndex());
          if (sameLineClose) {
            sb.append('\n').append(indentStr(baseIndent + 1));
            markLineAnchor(aliasClose);
            sb.append("} ]");
            lastEmittedLine = rbrack.getLine();
            appendTrailingCommentIfOnLine(rbrack.getLine(), nextTokenLimit);
          } else {
            sb.append('\n').append(indentStr(baseIndent + 1));
            markLineAnchor(aliasClose);
            sb.append(StvnVocabulary.DELIM_CLOSE_BRACE);
            lastEmittedLine = aliasClose.getLine();
            appendTrailingCommentIfOnLine(aliasClose.getLine(), rbrack.getTokenIndex());
            flushStandaloneCommentsBefore(rbrack.getTokenIndex(), baseIndent + 1, true);
            sb.append('\n').append(indentStr(baseIndent));
            markLineAnchor(rbrack);
            sb.append(StvnVocabulary.DELIM_CLOSE_BRACKET);
            lastEmittedLine = rbrack.getLine();
            appendTrailingCommentIfOnLine(rbrack.getLine(), nextTokenLimit);
          }
        } else {
          lastEmittedLine = elem.getStop().getLine();
          appendTrailingCommentIfOnLine(lastEmittedLine, rbrack.getTokenIndex());
          flushStandaloneCommentsBefore(rbrack.getTokenIndex(), baseIndent + 1, false);
          sb.append('\n').append(indentStr(baseIndent));
          markLineAnchor(rbrack);
          sb.append(StvnVocabulary.DELIM_CLOSE_BRACKET);
          lastEmittedLine = rbrack.getLine();
          appendTrailingCommentIfOnLine(rbrack.getLine(), nextTokenLimit);
        }
        return;
      }

      // Case 3: Layout 2 — multi-line or multi-element :use [ ... ]
      sb.append(StvnVocabulary.KEYWORD_USE).append(" [");
      lastEmittedLine = lbrack.getLine();
      appendTrailingCommentIfOnLine(lbrack.getLine(), firstStart.getTokenIndex());

      boolean emittedElem = false;
      for (int eIdx = 0; eIdx < elements.size(); eIdx++) {
        StvnParser.UseElementContext elem = elements.get(eIdx);
        int elemLimit = (eIdx + 1 < elements.size())
            ? elements.get(eIdx + 1).getStart().getTokenIndex()
            : rbrack.getTokenIndex();

        emittedElem = flushStandaloneCommentsBefore(
            elem.getStart().getTokenIndex(),
            baseIndent + 1,
            emittedElem
        );
        startNewBlockChildLine(baseIndent + 1, elem.getStart(), emittedElem);
        emittedElem = true;

        sb.append(elem.useTarget().getText());
        Token prevEnd = elem.useTarget().getStop();
        if (elem.useOptionsBlock() != null) {
          appendAlignableSpace(prevEnd, elem.useOptionsBlock().LBRACE().getSymbol());
          sb.append(formatUseOptionsBlockInline(elem.useOptionsBlock()));
          prevEnd = elem.useOptionsBlock().RBRACE().getSymbol();
        }
        if (elem.useAliasBlock() != null) {
          StvnParser.UseAliasBlockContext aliasBlock = elem.useAliasBlock();
          Token aliasOpen = aliasBlock.LBRACE().getSymbol();
          Token aliasClose = aliasBlock.RBRACE().getSymbol();
          boolean singleLineAliasBlock = aliasOpen.getLine() == aliasClose.getLine()
              && !hasCommentsBetween(aliasOpen.getTokenIndex(), aliasClose.getTokenIndex());
          if (elem.useOptionsBlock() != null) {
            sb.append(' ');
          } else {
            appendAlignableSpace(prevEnd, aliasOpen);
          }
          if (singleLineAliasBlock) {
            appendUseAliasBlockInline(aliasBlock);
            lastEmittedLine = aliasClose.getLine();
            appendTrailingCommentIfOnLine(aliasClose.getLine(), elemLimit);
          } else {
            sb.append(StvnVocabulary.DELIM_OPEN_BRACE);
            lastEmittedLine = aliasOpen.getLine();

            boolean emittedAlias = false;
            List<StvnParser.UseMapAliasContext> aliases = aliasBlock.useMapAlias();
            int aliasOpenLimit = aliases.isEmpty()
                ? aliasClose.getTokenIndex()
                : aliases.get(0).getStart().getTokenIndex();
            appendTrailingCommentIfOnLine(aliasOpen.getLine(), aliasOpenLimit);
            for (int i = 0; i < aliases.size(); i++) {
              StvnParser.UseMapAliasContext alias = aliases.get(i);
              int aliasLimit = (i + 1 < aliases.size())
                  ? aliases.get(i + 1).getStart().getTokenIndex()
                  : aliasClose.getTokenIndex();
              emittedAlias = flushStandaloneCommentsBefore(
                  alias.getStart().getTokenIndex(),
                  baseIndent + 2,
                  emittedAlias
              );
              startNewBlockChildLine(baseIndent + 2, alias.getStart(), emittedAlias);
              emittedAlias = true;
              appendUseMapAliasInline(alias);
              lastEmittedLine = alias.getStop().getLine();
              appendTrailingCommentIfOnLine(alias.getStop().getLine(), aliasLimit);
            }

            flushStandaloneCommentsBefore(aliasClose.getTokenIndex(), baseIndent + 2, emittedAlias);
            sb.append('\n').append(indentStr(baseIndent + 1));
            markLineAnchor(aliasClose);
            sb.append(StvnVocabulary.DELIM_CLOSE_BRACE);
            lastEmittedLine = aliasClose.getLine();
            appendTrailingCommentIfOnLine(aliasClose.getLine(), elemLimit);
          }
        } else {
          lastEmittedLine = elem.getStop().getLine();
          appendTrailingCommentIfOnLine(lastEmittedLine, elemLimit);
        }
      }

      flushStandaloneCommentsBefore(rbrack.getTokenIndex(), baseIndent + 1, emittedElem);
      sb.append('\n').append(indentStr(baseIndent));
      markLineAnchor(rbrack);
      sb.append(StvnVocabulary.DELIM_CLOSE_BRACKET);
      lastEmittedLine = rbrack.getLine();
      appendTrailingCommentIfOnLine(rbrack.getLine(), nextTokenLimit);
    }

    private void appendUseElementInline(StvnParser.UseElementContext elem) {
      sb.append(elem.useTarget().getText());
      Token prevEnd = elem.useTarget().getStop();
      if (elem.useOptionsBlock() != null) {
        appendAlignableSpace(prevEnd, elem.useOptionsBlock().LBRACE().getSymbol());
        sb.append(formatUseOptionsBlockInline(elem.useOptionsBlock()));
        prevEnd = elem.useOptionsBlock().RBRACE().getSymbol();
      }
      if (elem.useAliasBlock() != null) {
        if (elem.useOptionsBlock() != null) {
          sb.append(' ');
        } else {
          appendAlignableSpace(prevEnd, elem.useAliasBlock().LBRACE().getSymbol());
        }
        appendUseAliasBlockInline(elem.useAliasBlock());
      }
    }

    private void formatIncludeStmt(
        StvnParser.IncludeStmtContext ctx,
        int baseIndent,
        int nextTokenLimit
    ) {
      Token lbrack = ctx.LBRACK().getSymbol();
      Token rbrack = ctx.RBRACK().getSymbol();
      List<StvnParser.IncludeElementContext> elements = ctx.includeElement();
      boolean hasInteriorComments = hasCommentsBetween(lbrack.getTokenIndex(), rbrack.getTokenIndex());

      // Single-line :include
      if (lbrack.getLine() == rbrack.getLine() && !hasInteriorComments) {
        sb.append(StvnVocabulary.KEYWORD_INCLUDE).append(" [");
        for (int i = 0; i < elements.size(); i++) {
          sb.append(' ').append(formatStringLiteralText(elements.get(i).stringLiteral()));
        }
        sb.append(" ]");
        lastEmittedLine = rbrack.getLine();
        appendTrailingCommentIfOnLine(rbrack.getLine(), nextTokenLimit);
        return;
      }

      Token firstStart = elements.get(0).getStart();
      if (lbrack.getLine() == firstStart.getLine() && elements.size() == 1) {
        // Layout 1 single-element multi-line :include
        StvnParser.IncludeElementContext elem = elements.get(0);
        sb.append(StvnVocabulary.KEYWORD_INCLUDE)
            .append(" [ ")
            .append(formatStringLiteralText(elem.stringLiteral()));
        lastEmittedLine = elem.getStop().getLine();
        appendTrailingCommentIfOnLine(lastEmittedLine, rbrack.getTokenIndex());
        flushStandaloneCommentsBefore(rbrack.getTokenIndex(), baseIndent + 1, false);
        sb.append('\n').append(indentStr(baseIndent));
        markLineAnchor(rbrack);
        sb.append(StvnVocabulary.DELIM_CLOSE_BRACKET);
        lastEmittedLine = rbrack.getLine();
        appendTrailingCommentIfOnLine(rbrack.getLine(), nextTokenLimit);
        return;
      }

      // Layout 2 multi-line :include
      sb.append(StvnVocabulary.KEYWORD_INCLUDE).append(" [");
      lastEmittedLine = lbrack.getLine();
      appendTrailingCommentIfOnLine(lbrack.getLine(), firstStart.getTokenIndex());

      boolean emittedElem = false;
      for (int eIdx = 0; eIdx < elements.size(); eIdx++) {
        StvnParser.IncludeElementContext elem = elements.get(eIdx);
        int elemLimit = (eIdx + 1 < elements.size())
            ? elements.get(eIdx + 1).getStart().getTokenIndex()
            : rbrack.getTokenIndex();

        emittedElem = flushStandaloneCommentsBefore(
            elem.getStart().getTokenIndex(),
            baseIndent + 1,
            emittedElem
        );
        startNewBlockChildLine(baseIndent + 1, elem.getStart(), emittedElem);
        emittedElem = true;

        sb.append(formatStringLiteralText(elem.stringLiteral()));
        lastEmittedLine = elem.getStop().getLine();
        appendTrailingCommentIfOnLine(lastEmittedLine, elemLimit);
      }

      flushStandaloneCommentsBefore(rbrack.getTokenIndex(), baseIndent + 1, emittedElem);
      sb.append('\n').append(indentStr(baseIndent));
      markLineAnchor(rbrack);
      sb.append(StvnVocabulary.DELIM_CLOSE_BRACKET);
      lastEmittedLine = rbrack.getLine();
      appendTrailingCommentIfOnLine(rbrack.getLine(), nextTokenLimit);
    }

    private String formatUseOptionsBlockInline(StvnParser.UseOptionsBlockContext ctx) {
      List<StvnParser.UseOptionContext> opts = ctx.useOption();
      if (opts.isEmpty()) {
        return "{}";
      }
      StringBuilder b = new StringBuilder("{ ");
      for (int i = 0; i < opts.size(); i++) {
        if (i > 0) {
          b.append(' ');
        }
        b.append(opts.get(i).KW_STRIP().getText());
        if (opts.get(i).standaloneFacetIllegalArg() != null) {
          b.append(' ').append(opts.get(i).standaloneFacetIllegalArg().getText());
        }
      }
      b.append(" }");
      return b.toString();
    }

    private void appendUseAliasBlockInline(StvnParser.UseAliasBlockContext ctx) {
      List<StvnParser.UseMapAliasContext> aliases = ctx.useMapAlias();
      if (aliases.isEmpty()) {
        sb.append("{}");
        return;
      }
      sb.append("{ ");
      for (int i = 0; i < aliases.size(); i++) {
        if (i > 0) {
          sb.append(' ');
        }
        appendUseMapAliasInline(aliases.get(i));
      }
      sb.append(" }");
    }

    private void appendUseMapAliasInline(StvnParser.UseMapAliasContext ctx) {
      if (!ctx.typeKeyword().isEmpty()) {
        StvnParser.TypeKeywordContext left = ctx.typeKeyword(0);
        StvnParser.TypeKeywordContext right = ctx.typeKeyword(1);
        sb.append(left.getText());
        appendAlignableSpace(left.getStop(), right.getStart());
        sb.append(right.getText());
        return;
      }
      StvnParser.ValueKeywordContext left = ctx.valueKeyword(0);
      StvnParser.ValueKeywordContext right = ctx.valueKeyword(1);
      sb.append(left.getText());
      appendAlignableSpace(left.getStop(), right.getStart());
      sb.append(right.getText());
    }

    private void formatTypeDefinition(
        StvnParser.TypeDefinitionContext ctx,
        int indent,
        int nextTokenLimit
    ) {
      sb.append(ctx.typeDefTarget().getText());
      appendAlignableSpace(ctx.typeDefTarget().getStop(), ctx.schemaType().getStart());
      formatSchemaType(ctx.schemaType(), indent);
      lastEmittedLine = ctx.getStop().getLine();
      appendTrailingCommentIfOnLine(ctx.getStop().getLine(), nextTokenLimit);
    }

    private void formatConstantDefinition(
        StvnParser.ConstantDefinitionContext ctx,
        int indent,
        int nextTokenLimit
    ) {
      sb.append(ctx.valueKeyword().getText());
      appendAlignableSpace(ctx.valueKeyword().getStop(), ctx.schemaType().getStart());
      formatSchemaType(ctx.schemaType(), indent);
      appendAlignableSpace(ctx.schemaType().getStop(), ctx.value().getStart());
      formatValue(ctx.value(), indent);
      lastEmittedLine = ctx.getStop().getLine();
      appendTrailingCommentIfOnLine(ctx.getStop().getLine(), nextTokenLimit);
    }

    private void formatTypeEntry(StvnParser.TypeEntryContext ctx, int indent, int nextSectionLimit) {
      sb.append(StvnVocabulary.KEYWORD_TYPE);
      if (ctx.schemaType() != null) {
        appendAlignableSpace(ctx.KW_TYPE().getSymbol(), ctx.schemaType().getStart());
        formatSchemaType(ctx.schemaType(), indent);
      }
      lastEmittedLine = ctx.getStop().getLine();
      appendTrailingCommentIfOnLine(ctx.getStop().getLine(), nextSectionLimit);
    }

    private void formatBodyEntry(StvnParser.BodyEntryContext ctx, int indent, int nextSectionLimit) {
      sb.append(StvnVocabulary.KEYWORD_BODY);
      if (ctx.value() != null) {
        appendAlignableSpace(ctx.KW_BODY().getSymbol(), ctx.value().getStart());
        formatValue(ctx.value(), indent);
      }
      lastEmittedLine = ctx.getStop().getLine();
      appendTrailingCommentIfOnLine(ctx.getStop().getLine(), nextSectionLimit);
    }

    private void formatSchemaType(StvnParser.SchemaTypeContext ctx, int indent) {
      if (ctx.metadataMap() != null) {
        formatMetadataMap(ctx.metadataMap(), indent);
        Token rightStart = ctx.typeKeyword() != null
            ? ctx.typeKeyword().getStart()
            : ctx.schemaConstructor().getStart();
        appendAlignableSpace(ctx.metadataMap().RBRACE().getSymbol(), rightStart);
      }
      if (ctx.typeKeyword() != null) {
        sb.append(ctx.typeKeyword().getText());
        lastEmittedLine = ctx.typeKeyword().getStop().getLine();
      } else if (ctx.schemaConstructor() != null) {
        formatSchemaConstructor(ctx.schemaConstructor(), indent);
      }
    }

    private void formatMetadataMap(StvnParser.MetadataMapContext ctx, int indent) {
      Token openBrace = ctx.LBRACE().getSymbol();
      Token closeBrace = ctx.RBRACE().getSymbol();
      List<StvnParser.MetadataEntryContext> rawEntries = ctx.metadataEntry();
      boolean hasComments = hasCommentsBetween(openBrace.getTokenIndex(), closeBrace.getTokenIndex());

      if (rawEntries.isEmpty() && !hasComments) {
        sb.append("{}");
        lastEmittedLine = closeBrace.getLine();
        return;
      }

      boolean multiLine = openBrace.getLine() < closeBrace.getLine() || hasComments;

      if (!multiLine) {
        ToIntFunction<StvnParser.MetadataEntryContext> ranker = createMutexStabilizedMetadataRanker();
        List<CommentedBlockSlice<StvnParser.MetadataEntryContext>> sorted = new ArrayList<>(rawEntries.size());
        for (int i = 0; i < rawEntries.size(); i++) {
          StvnParser.MetadataEntryContext entry = rawEntries.get(i);
          sorted.add(new CommentedBlockSlice<>(
              ranker.applyAsInt(entry), i, entry, List.of(), false, false, null
          ));
        }
        sorted.sort(Comparator.<CommentedBlockSlice<StvnParser.MetadataEntryContext>>comparingInt(CommentedBlockSlice::rank)
            .thenComparingInt(CommentedBlockSlice::originalOrder));
        sb.append("{ ");
        boolean prevSingleLineMeta = inSingleLineMetadataMap;
        inSingleLineMetadataMap = true;
        try {
          for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) {
              sb.append(' ');
            }
            formatMetadataEntry(sorted.get(i).ctx(), indent, false);
          }
        } finally {
          inSingleLineMetadataMap = prevSingleLineMeta;
        }
        sb.append(" }");
        lastEmittedLine = closeBrace.getLine();
      } else {
        sb.append(StvnVocabulary.DELIM_OPEN_BRACE);
        lastEmittedLine = openBrace.getLine();
        PartitionedCommentedBlock<StvnParser.MetadataEntryContext> partitioned = partitionCommentedBlock(
            openBrace,
            closeBrace,
            rawEntries,
            createMutexStabilizedMetadataRanker()
        );
        emitPartitionedCommentedBlock(
            partitioned,
            indent + 1,
            entryCtx -> formatMetadataEntry(entryCtx, indent + 1, true)
        );
        sb.append('\n').append(indentStr(indent));
        markLineAnchor(closeBrace);
        sb.append(StvnVocabulary.DELIM_CLOSE_BRACE);
        lastEmittedLine = closeBrace.getLine();
      }
    }

    private void appendFacetArgSpace(Token kwToken, Token argStartToken, boolean alignable) {
      if (alignable) {
        appendAlignableSpace(kwToken, argStartToken);
      } else {
        sb.append(' ');
      }
    }

    private void formatMetadataEntry(
        StvnParser.MetadataEntryContext ctx,
        int indent,
        boolean alignable
    ) {
      if (ctx.facetUnsigned() != null) {
        sb.append(StvnVocabulary.FACET_KW_UNSIGNED);
        if (ctx.facetUnsigned().standaloneFacetIllegalArg() != null) {
          appendFacetArgSpace(
              ctx.facetUnsigned().KW_UNSIGNED().getSymbol(),
              ctx.facetUnsigned().standaloneFacetIllegalArg().getStart(),
              alignable
          );
          sb.append(ctx.facetUnsigned().standaloneFacetIllegalArg().getText());
        }
      } else if (ctx.facetExact() != null) {
        sb.append(StvnVocabulary.FACET_KW_EXACT);
        if (ctx.facetExact().standaloneFacetIllegalArg() != null) {
          appendFacetArgSpace(
              ctx.facetExact().KW_EXACT().getSymbol(),
              ctx.facetExact().standaloneFacetIllegalArg().getStart(),
              alignable
          );
          sb.append(ctx.facetExact().standaloneFacetIllegalArg().getText());
        }
      } else if (ctx.facetInvertible() != null) {
        sb.append(StvnVocabulary.FACET_KW_INVERTIBLE);
        if (ctx.facetInvertible().standaloneFacetIllegalArg() != null) {
          appendFacetArgSpace(
              ctx.facetInvertible().KW_INVERTIBLE().getSymbol(),
              ctx.facetInvertible().standaloneFacetIllegalArg().getStart(),
              alignable
          );
          sb.append(ctx.facetInvertible().standaloneFacetIllegalArg().getText());
        }
      } else if (ctx.facetTemporalScale() != null) {
        sb.append(ctx.facetTemporalScale().getStart().getText());
        if (ctx.facetTemporalScale().standaloneFacetIllegalArg() != null) {
          appendFacetArgSpace(
              ctx.facetTemporalScale().getStart(),
              ctx.facetTemporalScale().standaloneFacetIllegalArg().getStart(),
              alignable
          );
          sb.append(ctx.facetTemporalScale().standaloneFacetIllegalArg().getText());
        }
      } else if (ctx.facetTemporalMode() != null) {
        sb.append(ctx.facetTemporalMode().getStart().getText());
        if (ctx.facetTemporalMode().standaloneFacetIllegalArg() != null) {
          appendFacetArgSpace(
              ctx.facetTemporalMode().getStart(),
              ctx.facetTemporalMode().standaloneFacetIllegalArg().getStart(),
              alignable
          );
          sb.append(ctx.facetTemporalMode().standaloneFacetIllegalArg().getText());
        }
      } else if (ctx.metadataSize() != null) {
        sb.append(ctx.metadataSize().getStart().getText());
        appendFacetArgSpace(
            ctx.metadataSize().getStart(),
            ctx.metadataSize().metadataValue().getStart(),
            alignable
        );
        sb.append(formatMetadataValueText(ctx.metadataSize().metadataValue()));
      } else if (ctx.metadataBool() != null) {
        sb.append(ctx.metadataBool().getStart().getText());
        if (ctx.metadataBool().metadataValue() != null) {
          appendFacetArgSpace(
              ctx.metadataBool().getStart(),
              ctx.metadataBool().metadataValue().getStart(),
              alignable
          );
          sb.append(formatMetadataValueText(ctx.metadataBool().metadataValue()));
        }
      } else if (ctx.metadataRange() != null) {
        sb.append(ctx.metadataRange().getStart().getText());
        appendFacetArgSpace(
            ctx.metadataRange().getStart(),
            ctx.metadataRange().metadataValue().getStart(),
            alignable
        );
        sb.append(formatMetadataValueText(ctx.metadataRange().metadataValue()));
      } else if (ctx.metadataFilter() != null) {
        sb.append(ctx.metadataFilter().getStart().getText());
        appendFacetArgSpace(
            ctx.metadataFilter().getStart(),
            ctx.metadataFilter().variantList().getStart(),
            alignable
        );
        sb.append(formatVariantListText(ctx.metadataFilter().variantList()));
      } else if (ctx.metadataTrait() != null) {
        StvnParser.MetadataTraitContext trait = ctx.metadataTrait();
        sb.append(StvnVocabulary.FACET_KW_PRESERVE_INDENT);
        if (trait.metadataValue() != null) {
          appendFacetArgSpace(
              trait.KW_PRESERVE_INDENT().getSymbol(),
              trait.metadataValue().getStart(),
              alignable
          );
          sb.append(formatMetadataValueText(trait.metadataValue()));
        }
      } else if (ctx.metadataString() != null) {
        sb.append(StvnVocabulary.FACET_KW_REGEX);
        appendFacetArgSpace(
            ctx.metadataString().KW_REGEX().getSymbol(),
            ctx.metadataString().metadataValue().getStart(),
            alignable
        );
        sb.append(formatMetadataValueText(ctx.metadataString().metadataValue()));
      } else if (ctx.facetDirective() != null) {
        sb.append(StvnVocabulary.FACET_KW_STRIP);
        if (ctx.facetDirective().standaloneFacetIllegalArg() != null) {
          appendFacetArgSpace(
              ctx.facetDirective().KW_STRIP().getSymbol(),
              ctx.facetDirective().standaloneFacetIllegalArg().getStart(),
              alignable
          );
          sb.append(ctx.facetDirective().standaloneFacetIllegalArg().getText());
        }
      } else if (ctx.metadataUnknownEntry() != null) {
        StvnParser.MetadataUnknownEntryContext unk = ctx.metadataUnknownEntry();
        sb.append(unk.unknownFacetKeyword().getText());
        if (unk.metadataUnknownArg() != null) {
          appendFacetArgSpace(
              unk.unknownFacetKeyword().getStop(),
              unk.metadataUnknownArg().getStart(),
              alignable
          );
          formatMetadataUnknownArg(unk.metadataUnknownArg(), indent);
        } else if (unk.schemaType() != null) {
          appendFacetArgSpace(
              unk.unknownFacetKeyword().getStop(),
              unk.schemaType().getStart(),
              alignable
          );
          formatSchemaType(unk.schemaType(), indent);
        }
      }
    }

    private void formatMetadataUnknownArg(StvnParser.MetadataUnknownArgContext ctx, int indent) {
      if (ctx.booleanLiteral() != null) {
        sb.append(ctx.booleanLiteral().getText());
        lastEmittedLine = ctx.booleanLiteral().getStop().getLine();
      } else if (ctx.integerLiteral() != null) {
        sb.append(ctx.integerLiteral().getText());
        lastEmittedLine = ctx.integerLiteral().getStop().getLine();
      } else if (ctx.floatLiteral() != null) {
        sb.append(ctx.floatLiteral().getText());
        lastEmittedLine = ctx.floatLiteral().getStop().getLine();
      } else if (ctx.stringLiteral() != null) {
        sb.append(formatStringLiteralText(ctx.stringLiteral()));
        lastEmittedLine = ctx.stringLiteral().getStop().getLine();
      } else if (ctx.facetConstRef() != null) {
        sb.append(ctx.facetConstRef().getText());
        lastEmittedLine = ctx.facetConstRef().getStop().getLine();
      } else if (ctx.explicitOptionValue() != null) {
        formatExplicitOptionValue(ctx.explicitOptionValue(), indent);
      } else if (ctx.explicitEitherValue() != null) {
        formatExplicitEitherValue(ctx.explicitEitherValue(), indent);
      } else if (ctx.explicitUnionValue() != null) {
        formatExplicitUnionValue(ctx.explicitUnionValue(), indent);
      } else if (ctx.collectionValue() != null) {
        formatCollectionValue(ctx.collectionValue(), indent);
      }
    }

    private String formatMetadataValueText(StvnParser.MetadataValueContext ctx) {
      if (ctx.stringLiteral() != null) {
        return formatStringLiteralText(ctx.stringLiteral());
      }
      return ctx.getText();
    }

    private static String formatVariantListText(StvnParser.VariantListContext ctx) {
      List<StvnParser.ValueKeywordContext> variants = ctx.valueKeyword();
      StringBuilder b = new StringBuilder(StvnVocabulary.DELIM_OPEN_BRACKET);
      for (int i = 0; i < variants.size(); i++) {
        if (i > 0) {
          b.append(' ');
        }
        b.append(variants.get(i).getText());
      }
      b.append(StvnVocabulary.DELIM_CLOSE_BRACKET);
      return b.toString();
    }

    private void formatSchemaConstructor(StvnParser.SchemaConstructorContext ctx, int indent) {
      if (ctx.atomicType() != null) {
        sb.append(ctx.atomicType().getText());
        lastEmittedLine = ctx.atomicType().getStop().getLine();
      } else if (ctx.collectionType() != null) {
        StvnParser.CollectionTypeContext coll = ctx.collectionType();
        String head = coll.getStart().getText();
        boolean isMap = coll.COLL_MAP() != null;
        formatSchemaParenConstructor(
            head,
            coll.LPAREN().getSymbol(),
            coll.RPAREN().getSymbol(),
            coll.schemaType(),
            indent,
            isMap
        );
      } else if (ctx.productType() != null) {
        StvnParser.TupleTypeContext tup = (StvnParser.TupleTypeContext) ctx.productType();
        formatSchemaParenConstructor(
            StvnVocabulary.TYPE_TUPLE,
            tup.LPAREN().getSymbol(),
            tup.RPAREN().getSymbol(),
            tup.schemaType(),
            indent,
            true
        );
      } else if (ctx.sumType() != null) {
        StvnParser.SumTypeContext sum = ctx.sumType();
        if (sum.KW_ENUM() != null) {
          formatEnumConstructor(sum, indent);
        } else if (sum.KW_OPTION() != null) {
          formatSchemaParenConstructor(
              StvnVocabulary.TYPE_OPTION,
              sum.LPAREN().getSymbol(),
              sum.RPAREN().getSymbol(),
              sum.schemaType(),
              indent,
              false
          );
        } else if (sum.KW_EITHER() != null) {
          formatSchemaParenConstructor(
              StvnVocabulary.TYPE_EITHER,
              sum.LPAREN().getSymbol(),
              sum.RPAREN().getSymbol(),
              sum.schemaType(),
              indent,
              true
          );
        } else if (sum.KW_UNION() != null) {
          formatSchemaParenConstructor(
              StvnVocabulary.TYPE_UNION,
              sum.LPAREN().getSymbol(),
              sum.RPAREN().getSymbol(),
              sum.schemaType(),
              indent,
              true
          );
        }
      }
    }

    private void formatEnumConstructor(StvnParser.SumTypeContext sum, int indent) {
      StvnParser.EnumDefContext enumDef = sum.enumDef();
      Token lbrack = enumDef.LBRACK().getSymbol();
      Token rbrack = enumDef.RBRACK().getSymbol();
      List<StvnParser.ValueKeywordContext> variants = enumDef.valueKeyword();
      boolean hasComments = hasCommentsBetween(lbrack.getTokenIndex(), rbrack.getTokenIndex());
      boolean multiLine = lbrack.getLine() < rbrack.getLine() || hasComments;

      sb.append(StvnVocabulary.TYPE_ENUM).append(StvnVocabulary.DELIM_OPEN_BRACKET);
      lastEmittedLine = lbrack.getLine();
      if (!multiLine) {
        for (int i = 0; i < variants.size(); i++) {
          if (i > 0) {
            sb.append(' ');
          }
          sb.append(variants.get(i).getText());
        }
        sb.append(StvnVocabulary.DELIM_CLOSE_BRACKET);
        lastEmittedLine = rbrack.getLine();
        return;
      }

      int openLimit = variants.isEmpty()
          ? rbrack.getTokenIndex()
          : variants.get(0).getStart().getTokenIndex();
      appendTrailingCommentIfOnLine(lbrack.getLine(), openLimit);
      boolean emitted = false;
      for (int i = 0; i < variants.size(); i++) {
        StvnParser.ValueKeywordContext v = variants.get(i);
        int limit = (i + 1 < variants.size())
            ? variants.get(i + 1).getStart().getTokenIndex()
            : rbrack.getTokenIndex();
        emitted = flushStandaloneCommentsBefore(v.getStart().getTokenIndex(), indent + 1, emitted);
        startNewBlockChildLine(indent + 1, v.getStart(), emitted);
        emitted = true;
        sb.append(v.getText());
        lastEmittedLine = v.getStop().getLine();
        appendTrailingCommentIfOnLine(v.getStop().getLine(), limit);
      }
      flushStandaloneCommentsBefore(rbrack.getTokenIndex(), indent + 1, emitted);
      sb.append('\n').append(indentStr(indent));
      markLineAnchor(rbrack);
      sb.append(StvnVocabulary.DELIM_CLOSE_BRACKET);
      lastEmittedLine = rbrack.getLine();
    }

    private void formatSchemaParenConstructor(
        String keyword,
        Token openParen,
        Token closeParen,
        List<StvnParser.SchemaTypeContext> args,
        int indent,
        boolean expandableUnderPolicy
    ) {
      boolean multiLine = isAuthoredMultiLineParameterizedConstructor(openParen, closeParen, args)
          || (expandableUnderPolicy
              && options.schemaNestedWrapPolicy() == NestedContainerWrapPolicy.EXPAND_NESTED
              && hasNestedSchemaConstructorOrMultiLineArg(args));

      sb.append(keyword).append(StvnVocabulary.DELIM_OPEN_PAREN);
      lastEmittedLine = openParen.getLine();

      if (!multiLine) {
        for (int i = 0; i < args.size(); i++) {
          if (i > 0) {
            appendAlignableSpace(args.get(i - 1).getStop(), args.get(i).getStart());
          }
          formatSchemaType(args.get(i), indent);
        }
        sb.append(StvnVocabulary.DELIM_CLOSE_PAREN);
        lastEmittedLine = closeParen.getLine();
        return;
      }

      int openParenLimit = args.isEmpty()
          ? closeParen.getTokenIndex()
          : args.get(0).getStart().getTokenIndex();
      appendTrailingCommentIfOnLine(openParen.getLine(), openParenLimit);
      boolean emittedChild = false;
      for (int i = 0; i < args.size(); i++) {
        StvnParser.SchemaTypeContext child = args.get(i);
        int childLimit = (i + 1 < args.size())
            ? args.get(i + 1).getStart().getTokenIndex()
            : closeParen.getTokenIndex();
        emittedChild = flushStandaloneCommentsBefore(
            child.getStart().getTokenIndex(),
            indent + 1,
            emittedChild
        );
        startNewBlockChildLine(indent + 1, child.getStart(), emittedChild);
        emittedChild = true;
        formatSchemaType(child, indent + 1);
        lastEmittedLine = child.getStop().getLine();
        appendTrailingCommentIfOnLine(child.getStop().getLine(), childLimit);
      }

      flushStandaloneCommentsBefore(closeParen.getTokenIndex(), indent + 1, emittedChild);
      sb.append('\n').append(indentStr(indent));
      markLineAnchor(closeParen);
      sb.append(StvnVocabulary.DELIM_CLOSE_PAREN);
      lastEmittedLine = closeParen.getLine();
    }

    private boolean hasNestedSchemaConstructorOrMultiLineArg(List<StvnParser.SchemaTypeContext> args) {
      for (StvnParser.SchemaTypeContext arg : args) {
        if (arg.schemaConstructor() != null && arg.schemaConstructor().atomicType() == null) {
          return true;
        }
        if (arg.metadataMap() != null && !arg.metadataMap().metadataEntry().isEmpty()) {
          return true;
        }
        if (isMultiLineMetadataMap(arg.metadataMap())) {
          return true;
        }
      }
      return false;
    }

    private boolean isMultiLineMetadataMap(StvnParser.@Nullable MetadataMapContext ctx) {
      if (ctx == null) {
        return false;
      }
      Token openBrace = ctx.LBRACE().getSymbol();
      Token closeBrace = ctx.RBRACE().getSymbol();
      return openBrace.getLine() < closeBrace.getLine()
          || hasCommentsBetween(openBrace.getTokenIndex(), closeBrace.getTokenIndex());
    }

    private boolean isAuthoredMultiLineParameterizedConstructor(
        Token openParen,
        Token closeParen,
        List<StvnParser.SchemaTypeContext> args
    ) {
      if (args.isEmpty()) {
        return openParen.getLine() < closeParen.getLine()
            || hasCommentsBetween(openParen.getTokenIndex(), closeParen.getTokenIndex());
      }
      StvnParser.SchemaTypeContext first = args.get(0);
      if (openParen.getLine() < first.getStart().getLine()
          || hasCommentsBetween(openParen.getTokenIndex(), first.getStart().getTokenIndex())) {
        return true;
      }
      for (int i = 1; i < args.size(); i++) {
        StvnParser.SchemaTypeContext prev = args.get(i - 1);
        StvnParser.SchemaTypeContext curr = args.get(i);
        if (prev.getStop().getLine() < curr.getStart().getLine()
            || hasCommentsBetween(prev.getStop().getTokenIndex(), curr.getStart().getTokenIndex())) {
          return true;
        }
      }
      StvnParser.SchemaTypeContext last = args.get(args.size() - 1);
      return last.getStop().getLine() < closeParen.getLine()
          || hasCommentsBetween(last.getStop().getTokenIndex(), closeParen.getTokenIndex());
    }

    private void formatValue(StvnParser.ValueContext ctx, int indent) {
      if (ctx.booleanLiteral() != null) {
        sb.append(ctx.booleanLiteral().getText());
        lastEmittedLine = ctx.booleanLiteral().getStop().getLine();
      } else if (ctx.integerLiteral() != null) {
        sb.append(ctx.integerLiteral().getText());
        lastEmittedLine = ctx.integerLiteral().getStop().getLine();
      } else if (ctx.floatLiteral() != null) {
        sb.append(ctx.floatLiteral().getText());
        lastEmittedLine = ctx.floatLiteral().getStop().getLine();
      } else if (ctx.stringLiteral() != null) {
        sb.append(formatStringLiteralText(ctx.stringLiteral()));
        lastEmittedLine = ctx.stringLiteral().getStop().getLine();
      } else if (ctx.valueKeyword() != null) {
        sb.append(ctx.valueKeyword().getText());
        lastEmittedLine = ctx.valueKeyword().getStop().getLine();
      } else if (ctx.explicitOptionValue() != null) {
        formatExplicitOptionValue(ctx.explicitOptionValue(), indent);
      } else if (ctx.explicitEitherValue() != null) {
        formatExplicitEitherValue(ctx.explicitEitherValue(), indent);
      } else if (ctx.explicitUnionValue() != null) {
        formatExplicitUnionValue(ctx.explicitUnionValue(), indent);
      } else if (ctx.collectionValue() != null) {
        formatCollectionValue(ctx.collectionValue(), indent);
      }
    }

    private void formatExplicitOptionValue(StvnParser.ExplicitOptionValueContext opt, int indent) {
      sb.append(opt.getStart().getText());
      if (opt.value() != null) {
        appendAlignableSpace(opt.getStart(), opt.value().getStart());
        formatValue(opt.value(), indent);
      }
      lastEmittedLine = opt.getStop().getLine();
    }

    private void formatExplicitEitherValue(StvnParser.ExplicitEitherValueContext eith, int indent) {
      sb.append(eith.getStart().getText());
      appendAlignableSpace(eith.getStart(), eith.value().getStart());
      formatValue(eith.value(), indent);
      lastEmittedLine = eith.getStop().getLine();
    }

    private void formatExplicitUnionValue(StvnParser.ExplicitUnionValueContext un, int indent) {
      Token tagTok = un.UNION_TAG_PREFIX().getSymbol();
      sb.append(tagTok.getText());
      appendAlignableSpace(tagTok, un.value().getStart());
      formatValue(un.value(), indent);
      lastEmittedLine = un.getStop().getLine();
    }

    private void formatCollectionValue(StvnParser.CollectionValueContext coll, int indent) {
      if (coll.listLiteral() != null) {
        formatLinearValueContainer(
            coll.listLiteral().LBRACK().getSymbol(),
            coll.listLiteral().RBRACK().getSymbol(),
            StvnVocabulary.DELIM_OPEN_BRACKET,
            StvnVocabulary.DELIM_CLOSE_BRACKET,
            coll.listLiteral().value(),
            indent
        );
      } else if (coll.tupleLiteral() != null) {
        formatLinearValueContainer(
            coll.tupleLiteral().LPAREN().getSymbol(),
            coll.tupleLiteral().RPAREN().getSymbol(),
            StvnVocabulary.DELIM_OPEN_PAREN,
            StvnVocabulary.DELIM_CLOSE_PAREN,
            coll.tupleLiteral().value(),
            indent
        );
      } else if (coll.mapLiteral() != null) {
        formatMapLiteral(coll.mapLiteral(), indent);
      }
    }

    private void formatLinearValueContainer(
        Token openDelim,
        Token closeDelim,
        String openText,
        String closeText,
        List<StvnParser.ValueContext> elements,
        int indent
    ) {
      boolean hasInteriorComments = hasCommentsBetween(openDelim.getTokenIndex(), closeDelim.getTokenIndex());
      if (elements.isEmpty() && !hasInteriorComments) {
        sb.append(openText).append(closeText);
        lastEmittedLine = closeDelim.getLine();
        return;
      }

      boolean multiLine = hasInteriorComments
          || openDelim.getLine() < closeDelim.getLine()
          || containsFencedPayloadString(elements)
          || (options.payloadNestedWrapPolicy() == NestedContainerWrapPolicy.EXPAND_NESTED
              && requiresMultiLinePayloadContainer(elements));
      sb.append(openText);
      lastEmittedLine = openDelim.getLine();

      if (!multiLine) {
        for (int i = 0; i < elements.size(); i++) {
          if (i > 0) {
            appendAlignableSpace(elements.get(i - 1).getStop(), elements.get(i).getStart());
          }
          formatValue(elements.get(i), indent);
        }
        sb.append(closeText);
        lastEmittedLine = closeDelim.getLine();
        return;
      }

      int openDelimLimit = elements.isEmpty()
          ? closeDelim.getTokenIndex()
          : elements.get(0).getStart().getTokenIndex();
      appendTrailingCommentIfOnLine(openDelim.getLine(), openDelimLimit);
      boolean emittedElem = false;
      for (int i = 0; i < elements.size(); i++) {
        StvnParser.ValueContext el = elements.get(i);
        int elLimit = (i + 1 < elements.size())
            ? elements.get(i + 1).getStart().getTokenIndex()
            : closeDelim.getTokenIndex();
        emittedElem = flushStandaloneCommentsBefore(
            el.getStart().getTokenIndex(),
            indent + 1,
            emittedElem
        );
        startNewBlockChildLine(indent + 1, el.getStart(), emittedElem);
        emittedElem = true;
        formatValue(el, indent + 1);
        lastEmittedLine = el.getStop().getLine();
        appendTrailingCommentIfOnLine(el.getStop().getLine(), elLimit);
      }

      flushStandaloneCommentsBefore(closeDelim.getTokenIndex(), indent + 1, emittedElem);
      sb.append('\n').append(indentStr(indent));
      markLineAnchor(closeDelim);
      sb.append(closeText);
      lastEmittedLine = closeDelim.getLine();
    }

    private void formatMapLiteral(StvnParser.MapLiteralContext ctx, int indent) {
      Token openBrace = ctx.LBRACE().getSymbol();
      Token closeBrace = ctx.RBRACE().getSymbol();
      List<StvnParser.MapEntryContext> entries = ctx.mapEntry();
      boolean hasInteriorComments = hasCommentsBetween(openBrace.getTokenIndex(), closeBrace.getTokenIndex());

      if (entries.isEmpty() && !hasInteriorComments) {
        sb.append("{}");
        lastEmittedLine = closeBrace.getLine();
        return;
      }

      boolean multiLine = hasInteriorComments
          || openBrace.getLine() < closeBrace.getLine()
          || containsFencedMapString(entries)
          || options.payloadNestedWrapPolicy() == NestedContainerWrapPolicy.EXPAND_NESTED;

      sb.append(StvnVocabulary.DELIM_OPEN_BRACE);
      lastEmittedLine = openBrace.getLine();

      if (!multiLine) {
        for (int i = 0; i < entries.size(); i++) {
          StvnParser.MapEntryContext entry = entries.get(i);
          if (i > 0) {
            appendAlignableSpace(entries.get(i - 1).getStop(), entry.getStart());
          }
          StvnParser.ValueContext keyVal = entry.value(0);
          StvnParser.ValueContext valVal = entry.value(1);
          sb.append(StvnVocabulary.DELIM_OPEN_BRACKET);
          formatValue(keyVal, indent);
          appendAlignableSpace(keyVal.getStop(), valVal.getStart());
          formatValue(valVal, indent);
          sb.append(StvnVocabulary.DELIM_CLOSE_BRACKET);
        }
        sb.append(StvnVocabulary.DELIM_CLOSE_BRACE);
        lastEmittedLine = closeBrace.getLine();
        return;
      }

      int openBraceLimit = entries.isEmpty()
          ? closeBrace.getTokenIndex()
          : entries.get(0).getStart().getTokenIndex();
      appendTrailingCommentIfOnLine(openBrace.getLine(), openBraceLimit);

      boolean emittedEntry = false;
      for (int i = 0; i < entries.size(); i++) {
        StvnParser.MapEntryContext entry = entries.get(i);
        int entryLimit = (i + 1 < entries.size())
            ? entries.get(i + 1).getStart().getTokenIndex()
            : closeBrace.getTokenIndex();
        emittedEntry = flushStandaloneCommentsBefore(
            entry.getStart().getTokenIndex(),
            indent + 1,
            emittedEntry
        );
        startNewBlockChildLine(indent + 1, entry.getStart(), emittedEntry);
        emittedEntry = true;

        StvnParser.ValueContext keyVal = entry.value(0);
        StvnParser.ValueContext valVal = entry.value(1);
        Token entryClose = entry.RBRACK().getSymbol();

        sb.append(StvnVocabulary.DELIM_OPEN_BRACKET);
        formatValue(keyVal, indent + 1);

        boolean valueOnNewLine = valVal.getStart().getLine() > keyVal.getStop().getLine()
            || hasCommentsBetween(keyVal.getStop().getTokenIndex(), valVal.getStart().getTokenIndex());
        if (valueOnNewLine) {
          lastEmittedLine = keyVal.getStop().getLine();
          appendTrailingCommentIfOnLine(keyVal.getStop().getLine(), valVal.getStart().getTokenIndex());
          flushStandaloneCommentsBefore(valVal.getStart().getTokenIndex(), indent + 2, true);
          sb.append('\n').append(indentStr(indent + 2));
          markLineAnchor(valVal.getStart());
          formatValue(valVal, indent + 2);
          lastEmittedLine = valVal.getStop().getLine();
          appendTrailingCommentIfOnLine(valVal.getStop().getLine(), entryClose.getTokenIndex());
          flushStandaloneCommentsBefore(entryClose.getTokenIndex(), indent + 2, true);
          sb.append('\n').append(indentStr(indent + 1));
          markLineAnchor(entryClose);
          sb.append(StvnVocabulary.DELIM_CLOSE_BRACKET);
        } else {
          appendAlignableSpace(keyVal.getStop(), valVal.getStart());
          formatValue(valVal, indent + 1);
          sb.append(StvnVocabulary.DELIM_CLOSE_BRACKET);
        }
        lastEmittedLine = entryClose.getLine();
        appendTrailingCommentIfOnLine(entryClose.getLine(), entryLimit);
      }

      flushStandaloneCommentsBefore(closeBrace.getTokenIndex(), indent + 1, emittedEntry);
      sb.append('\n').append(indentStr(indent));
      markLineAnchor(closeBrace);
      sb.append(StvnVocabulary.DELIM_CLOSE_BRACE);
      lastEmittedLine = closeBrace.getLine();
    }

    private boolean containsFencedPayloadString(List<StvnParser.ValueContext> elements) {
      for (StvnParser.ValueContext el : elements) {
        if (containsFencedValue(el)) {
          return true;
        }
      }
      return false;
    }

    private boolean containsFencedMapString(List<StvnParser.MapEntryContext> entries) {
      for (StvnParser.MapEntryContext entry : entries) {
        if (containsFencedValue(entry.value(0)) || containsFencedValue(entry.value(1))) {
          return true;
        }
      }
      return false;
    }

    private boolean containsFencedValue(StvnParser.@Nullable ValueContext ctx) {
      if (ctx == null) {
        return false;
      }
      if (ctx.stringLiteral() instanceof StvnParser.StringFencedContext
          || ctx.stringLiteral() instanceof StvnParser.StringMalformedFencedContext) {
        return true;
      }
      if (ctx.explicitOptionValue() != null) {
        return containsFencedValue(ctx.explicitOptionValue().value());
      }
      if (ctx.explicitEitherValue() != null) {
        return containsFencedValue(ctx.explicitEitherValue().value());
      }
      if (ctx.explicitUnionValue() != null) {
        return containsFencedValue(ctx.explicitUnionValue().value());
      }
      if (ctx.collectionValue() != null) {
        var coll = ctx.collectionValue();
        if (coll.listLiteral() != null) {
          return containsFencedPayloadString(coll.listLiteral().value());
        }
        if (coll.tupleLiteral() != null) {
          return containsFencedPayloadString(coll.tupleLiteral().value());
        }
        if (coll.mapLiteral() != null) {
          return containsFencedMapString(coll.mapLiteral().mapEntry());
        }
      }
      return false;
    }

    private boolean requiresMultiLinePayloadContainer(List<StvnParser.ValueContext> elements) {
      for (StvnParser.ValueContext el : elements) {
        if (isStructuredOrFencedPayloadValue(el)) {
          return true;
        }
      }
      return false;
    }

    private boolean isStructuredOrFencedPayloadValue(StvnParser.@Nullable ValueContext ctx) {
      if (ctx == null) {
        return false;
      }
      if (ctx.collectionValue() != null) {
        return true;
      }
      if (ctx.stringLiteral() instanceof StvnParser.StringFencedContext) {
        return true;
      }
      if (ctx.explicitOptionValue() != null) {
        return isStructuredOrFencedPayloadValue(ctx.explicitOptionValue().value());
      }
      if (ctx.explicitEitherValue() != null) {
        return isStructuredOrFencedPayloadValue(ctx.explicitEitherValue().value());
      }
      if (ctx.explicitUnionValue() != null) {
        return isStructuredOrFencedPayloadValue(ctx.explicitUnionValue().value());
      }
      return false;
    }

    private String formatStringLiteralText(StvnParser.StringLiteralContext ctx) {
      if (ctx instanceof StvnParser.StringFencedContext fencedCtx) {
        int start = fencedCtx.fencedString().getStart().getStartIndex();
        int stop = fencedCtx.fencedString().getStop().getStopIndex();
        return source.substring(start, stop + 1).replace("\r\n", "\n").replace('\r', '\n');
      }
      return ctx.getText();
    }

    private <T extends ParserRuleContext> PartitionedCommentedBlock<T> partitionCommentedBlock(
        Token openBrace,
        Token closeBrace,
        List<T> rawEntries,
        ToIntFunction<T> ranker
    ) {
      int openLimit = rawEntries.isEmpty()
          ? closeBrace.getTokenIndex()
          : rawEntries.get(0).getStart().getTokenIndex();
      appendTrailingCommentIfOnLine(openBrace.getLine(), openLimit);

      int prevAnchorStopLine = openBrace.getLine();
      List<CommentedBlockSlice<T>> slices = new ArrayList<>(rawEntries.size());
      Map<Integer, Integer> peerColCounts = new HashMap<>();

      for (int i = 0; i < rawEntries.size(); i++) {
        T entry = rawEntries.get(i);
        int entryStartTok = entry.getStart().getTokenIndex();
        int entryStopTok = entry.getStop().getTokenIndex();
        int entryStartLine = entry.getStart().getLine();
        int entryStopLine = entry.getStop().getLine();
        int nextLimit = (i + 1 < rawEntries.size())
            ? rawEntries.get(i + 1).getStart().getTokenIndex()
            : closeBrace.getTokenIndex();

        List<Token> rawLeading = new ArrayList<>();
        while (nextCommentIdx < activeCommentEndIdxExclusive
            && comments.get(nextCommentIdx).getTokenIndex() < entryStartTok) {
          rawLeading.add(comments.get(nextCommentIdx++));
        }

        while (nextCommentIdx < activeCommentEndIdxExclusive
            && comments.get(nextCommentIdx).getTokenIndex() <= entryStopTok) {
          nextCommentIdx++;
        }

        int firstSliceLine = rawLeading.isEmpty()
            ? entryStartLine
            : rawLeading.get(0).getLine();
        boolean hadBlankLineBefore = i > 0 && (firstSliceLine - prevAnchorStopLine >= 2);

        List<CommentLine> leading = new ArrayList<>(rawLeading.size());
        for (int j = 0; j < rawLeading.size(); j++) {
          Token c = rawLeading.get(j);
          boolean blankBeforeComment = j > 0 && (c.getLine() - rawLeading.get(j - 1).getLine() >= 2);
          leading.add(new CommentLine(c, blankBeforeComment));
        }

        boolean blankLineBeforeEntry = !rawLeading.isEmpty()
            && (entryStartLine - rawLeading.get(rawLeading.size() - 1).getLine() >= 2);

        Token trailing = null;
        if (nextCommentIdx < activeCommentEndIdxExclusive) {
          Token candidate = comments.get(nextCommentIdx);
          if (candidate.getTokenIndex() < nextLimit && candidate.getLine() == entryStopLine) {
            trailing = candidate;
            peerColCounts.merge(candidate.getCharPositionInLine(), 1, Integer::sum);
            nextCommentIdx++;
          }
        }

        slices.add(new CommentedBlockSlice<>(
            ranker.applyAsInt(entry),
            i,
            entry,
            List.copyOf(leading),
            hadBlankLineBefore,
            blankLineBeforeEntry,
            trailing
        ));
        prevAnchorStopLine = entryStopLine;
      }

      List<Token> rawFooter = new ArrayList<>();
      while (nextCommentIdx < activeCommentEndIdxExclusive
          && comments.get(nextCommentIdx).getTokenIndex() < closeBrace.getTokenIndex()) {
        rawFooter.add(comments.get(nextCommentIdx++));
      }

      boolean footerHadBlankLineBefore = !rawEntries.isEmpty()
          && !rawFooter.isEmpty()
          && (rawFooter.get(0).getLine() - prevAnchorStopLine >= 2);
      List<CommentLine> footerComments = new ArrayList<>(rawFooter.size());
      for (int j = 0; j < rawFooter.size(); j++) {
        Token c = rawFooter.get(j);
        boolean blankBeforeFooter = j > 0 && (c.getLine() - rawFooter.get(j - 1).getLine() >= 2);
        footerComments.add(new CommentLine(c, blankBeforeFooter));
      }

      slices.sort(Comparator.<CommentedBlockSlice<T>>comparingInt(CommentedBlockSlice::rank)
          .thenComparingInt(CommentedBlockSlice::originalOrder));

      return new PartitionedCommentedBlock<>(
          slices,
          List.copyOf(footerComments),
          footerHadBlankLineBefore,
          Map.copyOf(peerColCounts)
      );
    }

    private <T extends ParserRuleContext> void emitPartitionedCommentedBlock(
        PartitionedCommentedBlock<T> partitioned,
        int entryIndent,
        Consumer<T> inlineEntryFormatter
    ) {
      List<CommentedBlockSlice<T>> sorted = partitioned.slices();
      Map<Integer, Integer> peerCols = partitioned.peerTrailingCommentColCounts();
      for (int i = 0; i < sorted.size(); i++) {
        CommentedBlockSlice<T> item = sorted.get(i);
        if (i > 0 && item.hadBlankLineBefore()) {
          sb.append('\n');
        }
        for (CommentLine c : item.leadingComments()) {
          if (c.blankLineBefore()) {
            sb.append('\n');
          }
          sb.append('\n').append(indentStr(entryIndent)).append(c.token().getText());
        }
        if (item.blankLineBeforeEntry()) {
          sb.append('\n');
        }
        sb.append('\n').append(indentStr(entryIndent));
        markLineAnchor(item.ctx().getStart());
        inlineEntryFormatter.accept(item.ctx());
        if (item.trailingComment() != null) {
          sb.append(trailingCommentPadding(item.trailingComment(), peerCols))
              .append(item.trailingComment().getText());
        }
      }

      List<CommentLine> footer = partitioned.footerComments();
      if (!footer.isEmpty()) {
        if (partitioned.footerHadBlankLineBefore() && !sorted.isEmpty()) {
          sb.append('\n');
        }
        for (CommentLine c : footer) {
          if (c.blankLineBefore()) {
            sb.append('\n');
          }
          sb.append('\n').append(indentStr(entryIndent)).append(c.token().getText());
        }
      }
      if (!footer.isEmpty()) {
        lastEmittedLine = footer.get(footer.size() - 1).token().getLine();
      } else if (!sorted.isEmpty()) {
        CommentedBlockSlice<T> last = sorted.get(sorted.size() - 1);
        lastEmittedLine = last.trailingComment() != null
            ? last.trailingComment().getLine()
            : last.ctx().getStop().getLine();
      }
    }

    private boolean hasCommentsBetween(int startTokenIndexExclusive, int endTokenIndexExclusive) {
      for (int i = nextCommentIdx; i < activeCommentEndIdxExclusive; i++) {
        int tokIdx = comments.get(i).getTokenIndex();
        if (tokIdx <= startTokenIndexExclusive) {
          continue;
        }
        return tokIdx < endTokenIndexExclusive;
      }
      return false;
    }

    private void appendTrailingCommentIfOnLine(int line, int maxTokenIndexExclusive) {
      if (nextCommentIdx < activeCommentEndIdxExclusive) {
        Token candidate = comments.get(nextCommentIdx);
        if (candidate.getLine() == line && candidate.getTokenIndex() < maxTokenIndexExclusive) {
          sb.append(trailingCommentPadding(candidate, null)).append(candidate.getText());
          lastEmittedLine = candidate.getLine();
          nextCommentIdx++;
        }
      }
    }

    private boolean isTrailingCommentColumnAnchored(
        Token commentToken,
        int authoredSpaces,
        @Nullable Map<Integer, Integer> blockPeerColCounts
    ) {
      if (authoredSpaces >= 2) {
        return true;
      }
      int line = commentToken.getLine();
      int origCol = commentToken.getCharPositionInLine();
      Integer prevLineCol = trailingCommentColByLine.get(line - 1);
      if (prevLineCol != null && prevLineCol == origCol) {
        return true;
      }
      Integer nextLineCol = trailingCommentColByLine.get(line + 1);
      if (nextLineCol != null && nextLineCol == origCol) {
        return true;
      }
      return blockPeerColCounts != null && blockPeerColCounts.getOrDefault(origCol, 0) >= 2;
    }

    private String trailingCommentPadding(
        Token commentToken,
        @Nullable Map<Integer, Integer> blockPeerColCounts
    ) {
      int tokIdx = commentToken.getTokenIndex();
      for (int i = tokIdx - 1; i >= 0 && i < allTokens.size(); i--) {
        Token prev = allTokens.get(i);
        if (prev.getChannel() == Token.DEFAULT_CHANNEL) {
          if (prev.getLine() == commentToken.getLine()) {
            int authoredSpaces = commentToken.getStartIndex() - prev.getStopIndex() - 1;
            if (!currentOutputLineAnchoredToInputLineStart
                || !isTrailingCommentColumnAnchored(commentToken, authoredSpaces, blockPeerColCounts)) {
              return " ";
            }
            int origCommentCol = commentToken.getCharPositionInLine();
            int spaces = Math.max(1, origCommentCol - currentLineCol());
            return " ".repeat(spaces);
          }
          break;
        }
      }
      return " ";
    }

    private boolean flushStandaloneCommentsBefore(
        int limitTokenIndexExclusive,
        int indent,
        boolean hasEmittedItemInBlock
    ) {
      boolean emitted = hasEmittedItemInBlock;
      while (nextCommentIdx < activeCommentEndIdxExclusive
          && comments.get(nextCommentIdx).getTokenIndex() < limitTokenIndexExclusive) {
        Token c = comments.get(nextCommentIdx++);
        if (emitted && c.getLine() - lastEmittedLine >= 2) {
          sb.append('\n');
        }
        sb.append('\n').append(indentStr(indent)).append(c.getText());
        lastEmittedLine = c.getLine();
        emitted = true;
      }
      return emitted;
    }

    private void startNewBlockChildLine(int indent, Token childStartToken, boolean hasEmittedItemInBlock) {
      if (hasEmittedItemInBlock && lastEmittedLine > 0 && childStartToken.getLine() - lastEmittedLine >= 2) {
        sb.append('\n');
      }
      sb.append('\n').append(indentStr(indent));
      markLineAnchor(childStartToken);
    }

    private static String indentStr(int level) {
      return INDENT_UNIT.repeat(Math.max(0, level));
    }
  }
}
