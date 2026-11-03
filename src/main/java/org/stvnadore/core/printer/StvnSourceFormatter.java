package org.stvnadore.core.printer;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.parser.StvnErrorListener;
import org.stvnadore.core.parser.StvnErrorStrategy;
import org.stvnadore.core.parser.StvnLexer;
import org.stvnadore.core.parser.StvnParser;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.core.validation.StvnSyntaxCancellationException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
 * ({@code """[SQL]..."""}), and explicit vs. inferred sum tags—while normalizing:
 * <ul>
 *   <li>2-space structural indentation hierarchy ({@code indentWidth = 2}), including Layout 1
 *       and Layout 2 multi-line {@code :use} and {@code :include} directives</li>
 *   <li>Canonical horizontal spacing (§ 3.3)</li>
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
   * non-destructive authoring representation (without a trailing newline).
   *
   * @param source the raw STVN 2.0.0 authoring source text
   * @return the formatted STVN 2.0.0 authoring source text
   * @throws NullPointerException            if {@code source} is {@code null}
   * @throws StvnSyntaxCancellationException if Pass 1 lexical or syntactic validation fails
   * @since 2.0.0
   */
  public static String format(String source) {
    Objects.requireNonNull(source, "source must not be null");

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
            "E035",
            DiagnosticBag.ERR_TAB_CHARACTER_FORBIDDEN
        );
      }
    }

    FormatterCursor cursor = new FormatterCursor(source, tokenStream.getTokens());
    return cursor.formatDocument(docCtx);
  }

  /**
   * Attempts to format a STVN 2.0.0 source string if Pass 1 lexical and syntactic validation
   * succeeds with zero errors.
   *
   * @param source the raw STVN 2.0.0 authoring source text, or {@code null}
   * @return {@code Optional.of(formatted)} when Pass 1 succeeds with 0 syntax errors,
   *         or {@code Optional.empty()} if {@code source} is {@code null} or contains Pass 1 syntax errors
   * @since 2.0.0
   */
  public static Optional<String> tryFormat(@Nullable String source) {
    if (source == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(format(source));
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

  private record SortedMetaItem(
      int rank,
      int originalOrder,
      StvnParser.MetaElementContext ctx,
      List<Token> leadingComments,
      @Nullable Token trailingComment
  ) {}

  private record SortedFacetItem(
      int rank,
      int originalOrder,
      StvnParser.MetadataEntryContext ctx,
      List<Token> leadingComments,
      @Nullable Token trailingComment
  ) {}

  private static final class FormatterCursor {

    private final String source;
    private final List<Token> comments;
    private final StringBuilder sb = new StringBuilder();
    private int nextCommentIdx = 0;
    private int lastEmittedLine = -1;

    FormatterCursor(String source, List<Token> allTokens) {
      this.source = source;
      List<Token> commentList = new ArrayList<>();
      for (Token token : allTokens) {
        if (token.getType() == StvnLexer.COMMENT) {
          commentList.add(token);
        }
      }
      this.comments = List.copyOf(commentList);
    }

    String formatDocument(StvnParser.StvnDocumentContext docCtx) {
      Token openBrace = docCtx.LBRACE().getSymbol();
      Token closeBrace = docCtx.RBRACE().getSymbol();

      // 1. Leading comments before root '{'
      boolean hadLeadingComment = false;
      while (nextCommentIdx < comments.size()
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
      sb.append(StvnVocabulary.DELIM_OPEN_BRACE);
      lastEmittedLine = openBrace.getLine();

      StvnParser.DocumentBodyContext body = docCtx.documentBody();
      List<ParserRuleContext> sections = new ArrayList<>(4);
      if (body.metaEntry() != null) {
        sections.add(body.metaEntry());
      }
      if (body.defsEntry() != null) {
        sections.add(body.defsEntry());
      }
      if (body.typeEntry() != null) {
        sections.add(body.typeEntry());
      }
      if (body.bodyEntry() != null) {
        sections.add(body.bodyEntry());
      }

      int rootOpenLimit = sections.isEmpty()
          ? closeBrace.getTokenIndex()
          : sections.get(0).getStart().getTokenIndex();
      appendTrailingCommentIfOnLine(openBrace.getLine(), rootOpenLimit);

      boolean emittedInRoot = false;
      for (int i = 0; i < sections.size(); i++) {
        ParserRuleContext section = sections.get(i);
        int sectionLimit = (i + 1 < sections.size())
            ? sections.get(i + 1).getStart().getTokenIndex()
            : closeBrace.getTokenIndex();
        emittedInRoot = flushStandaloneCommentsBefore(
            section.getStart().getTokenIndex(),
            1,
            emittedInRoot
        );
        startNewBlockChildLine(1, section.getStart().getLine(), emittedInRoot);
        emittedInRoot = true;

        if (section instanceof StvnParser.MetaEntryContext metaCtx) {
          formatMetaEntry(metaCtx, 1, sectionLimit);
        } else if (section instanceof StvnParser.DefsEntryContext defsCtx) {
          formatDefsEntry(defsCtx, 1, sectionLimit);
        } else if (section instanceof StvnParser.TypeEntryContext typeCtx) {
          formatTypeEntry(typeCtx, 1, sectionLimit);
        } else if (section instanceof StvnParser.BodyEntryContext bodyCtx) {
          formatBodyEntry(bodyCtx, 1, sectionLimit);
        }
      }

      flushStandaloneCommentsBefore(closeBrace.getTokenIndex(), 1, emittedInRoot);
      sb.append('\n').append(StvnVocabulary.DELIM_CLOSE_BRACE);
      lastEmittedLine = closeBrace.getLine();
      appendTrailingCommentIfOnLine(closeBrace.getLine(), Integer.MAX_VALUE);

      while (nextCommentIdx < comments.size()) {
        Token trailingDocComment = comments.get(nextCommentIdx++);
        sb.append('\n').append(trailingDocComment.getText());
        lastEmittedLine = trailingDocComment.getLine();
      }

      return sb.toString();
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
      int openLimit = rawElements.isEmpty()
          ? closeBrace.getTokenIndex()
          : rawElements.get(0).getStart().getTokenIndex();
      appendTrailingCommentIfOnLine(openBrace.getLine(), openLimit);
      List<SortedMetaItem> items = new ArrayList<>(rawElements.size());
      for (int i = 0; i < rawElements.size(); i++) {
        StvnParser.MetaElementContext el = rawElements.get(i);
        int nextLimit = (i + 1 < rawElements.size())
            ? rawElements.get(i + 1).getStart().getTokenIndex()
            : closeBrace.getTokenIndex();

        List<Token> leading = new ArrayList<>();
        while (nextCommentIdx < comments.size()
            && comments.get(nextCommentIdx).getTokenIndex() < el.getStart().getTokenIndex()) {
          leading.add(comments.get(nextCommentIdx++));
        }
        Token trailing = null;
        if (nextCommentIdx < comments.size()) {
          Token candidate = comments.get(nextCommentIdx);
          if (candidate.getTokenIndex() < nextLimit && candidate.getLine() == el.getStop().getLine()) {
            trailing = candidate;
            nextCommentIdx++;
          }
        }
        items.add(new SortedMetaItem(metaElementRank(el), i, el, leading, trailing));
      }

      List<Token> footerComments = new ArrayList<>();
      while (nextCommentIdx < comments.size()
          && comments.get(nextCommentIdx).getTokenIndex() < closeBrace.getTokenIndex()) {
        footerComments.add(comments.get(nextCommentIdx++));
      }

      items.sort(Comparator.comparingInt(SortedMetaItem::rank)
          .thenComparingInt(SortedMetaItem::originalOrder));

      for (SortedMetaItem item : items) {
        for (Token c : item.leadingComments()) {
          sb.append('\n').append(indentStr(indent + 1)).append(c.getText());
        }
        sb.append('\n').append(indentStr(indent + 1));
        formatMetaElementInline(item.ctx());
        if (item.trailingComment() != null) {
          sb.append(' ').append(item.trailingComment().getText());
        }
      }

      for (Token c : footerComments) {
        sb.append('\n').append(indentStr(indent + 1)).append(c.getText());
      }

      sb.append('\n').append(indentStr(indent)).append(StvnVocabulary.DELIM_CLOSE_BRACE);
      lastEmittedLine = closeBrace.getLine();
      appendTrailingCommentIfOnLine(closeBrace.getLine(), nextSectionLimit);
    }

    private void formatMetaElementInline(StvnParser.MetaElementContext ctx) {
      if (ctx.metaNameFacet() != null) {
        sb.append(StvnVocabulary.FACET_KW_META_NAME)
            .append(' ')
            .append(formatStringLiteralText(ctx.metaNameFacet().stringLiteral()));
      } else if (ctx.metaDomainFacet() != null) {
        sb.append(StvnVocabulary.FACET_KW_META_DOMAIN)
            .append(' ')
            .append(formatStringLiteralText(ctx.metaDomainFacet().stringLiteral()));
      } else if (ctx.metaKindFacet() != null) {
        sb.append(StvnVocabulary.FACET_KW_META_KIND)
            .append(' ')
            .append(ctx.metaKindFacet().metaKindValue().getText());
      } else if (ctx.metaUnknownFacet() != null) {
        StvnParser.MetaUnknownFacetContext unk = ctx.metaUnknownFacet();
        sb.append(unk.valueKeyword().getText());
        if (unk.value() != null) {
          sb.append(' ');
          formatValue(unk.value(), 2);
        } else if (unk.schemaType() != null) {
          sb.append(' ');
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
        startNewBlockChildLine(indent + 1, el.getStart().getLine(), emittedInDefs);
        emittedInDefs = true;

        formatDefsElement(el, indent + 1, nextLimit);
      }

      flushStandaloneCommentsBefore(closeBrace.getTokenIndex(), indent + 1, emittedInDefs);
      sb.append('\n').append(indentStr(indent)).append(StvnVocabulary.DELIM_CLOSE_BRACE);
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
        startNewBlockChildLine(indent + 1, el.getStart().getLine(), emittedInPkg);
        emittedInPkg = true;

        formatPackageElement(el, indent + 1, childLimit);
      }

      flushStandaloneCommentsBefore(closeBrace.getTokenIndex(), indent + 1, emittedInPkg);
      sb.append('\n').append(indentStr(indent)).append(StvnVocabulary.DELIM_CLOSE_BRACE);
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
          startNewBlockChildLine(indent + 1, child.getStart().getLine(), emitted);
          emitted = true;
          formatPackageElement(child, indent + 1, limit);
        }
        flushStandaloneCommentsBefore(closeBrace.getTokenIndex(), indent + 1, emitted);
        sb.append('\n').append(indentStr(indent)).append(StvnVocabulary.DELIM_CLOSE_BRACE);
        lastEmittedLine = closeBrace.getLine();
        appendTrailingCommentIfOnLine(closeBrace.getLine(), nextTokenLimit);
      }
    }

    private void formatUseStmt(StvnParser.UseStmtContext ctx, int baseIndent, int nextTokenLimit) {
      Token lbrack = ctx.LBRACK().getSymbol();
      Token rbrack = ctx.RBRACK().getSymbol();
      Token targetStart = ctx.useTarget().getStart();
      boolean hasInteriorComments = hasCommentsBetween(lbrack.getTokenIndex(), rbrack.getTokenIndex());

      // Case 1: Single-line :use [ ... ]
      if (lbrack.getLine() == rbrack.getLine() && !hasInteriorComments) {
        sb.append(StvnVocabulary.KEYWORD_USE)
            .append(" [ ")
            .append(ctx.useTarget().getText());
        if (ctx.useOptionsBlock() != null) {
          sb.append(' ').append(formatUseOptionsBlockInline(ctx.useOptionsBlock()));
        }
        if (ctx.useAliasBlock() != null) {
          sb.append(' ').append(formatUseAliasBlockInline(ctx.useAliasBlock()));
        }
        sb.append(" ]");
        lastEmittedLine = rbrack.getLine();
        appendTrailingCommentIfOnLine(rbrack.getLine(), nextTokenLimit);
        return;
      }

      // Case 2: Layout 1 — target starts on the same line as '[' (:use [ :pkg {)
      if (lbrack.getLine() == targetStart.getLine()) {
        sb.append(StvnVocabulary.KEYWORD_USE)
            .append(" [ ")
            .append(ctx.useTarget().getText());
        if (ctx.useOptionsBlock() != null) {
          sb.append(' ').append(formatUseOptionsBlockInline(ctx.useOptionsBlock()));
        }
        if (ctx.useAliasBlock() != null) {
          StvnParser.UseAliasBlockContext aliasBlock = ctx.useAliasBlock();
          Token aliasOpen = aliasBlock.LBRACE().getSymbol();
          Token aliasClose = aliasBlock.RBRACE().getSymbol();
          sb.append(" {");
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
            startNewBlockChildLine(baseIndent + 2, alias.getStart().getLine(), emittedAlias);
            emittedAlias = true;
            sb.append(formatUseMapAliasInline(alias));
            lastEmittedLine = alias.getStop().getLine();
            appendTrailingCommentIfOnLine(alias.getStop().getLine(), aliasLimit);
          }

          flushStandaloneCommentsBefore(aliasClose.getTokenIndex(), baseIndent + 2, emittedAlias);
          boolean sameLineClose = aliasClose.getLine() == rbrack.getLine()
              && !hasCommentsBetween(aliasClose.getTokenIndex(), rbrack.getTokenIndex());
          if (sameLineClose) {
            sb.append('\n')
                .append(indentStr(baseIndent + 1))
                .append("} ]");
            lastEmittedLine = rbrack.getLine();
            appendTrailingCommentIfOnLine(rbrack.getLine(), nextTokenLimit);
          } else {
            sb.append('\n')
                .append(indentStr(baseIndent + 1))
                .append(StvnVocabulary.DELIM_CLOSE_BRACE);
            lastEmittedLine = aliasClose.getLine();
            appendTrailingCommentIfOnLine(aliasClose.getLine(), rbrack.getTokenIndex());
            flushStandaloneCommentsBefore(rbrack.getTokenIndex(), baseIndent + 1, true);
            sb.append('\n')
                .append(indentStr(baseIndent))
                .append(StvnVocabulary.DELIM_CLOSE_BRACKET);
            lastEmittedLine = rbrack.getLine();
            appendTrailingCommentIfOnLine(rbrack.getLine(), nextTokenLimit);
          }
        } else {
          lastEmittedLine = ctx.getStart().getLine();
          appendTrailingCommentIfOnLine(lastEmittedLine, rbrack.getTokenIndex());
          flushStandaloneCommentsBefore(rbrack.getTokenIndex(), baseIndent + 1, false);
          sb.append('\n')
              .append(indentStr(baseIndent))
              .append(StvnVocabulary.DELIM_CLOSE_BRACKET);
          lastEmittedLine = rbrack.getLine();
          appendTrailingCommentIfOnLine(rbrack.getLine(), nextTokenLimit);
        }
        return;
      }

      // Case 3: Layout 2 — target starts on a new line after ':use ['
      sb.append(StvnVocabulary.KEYWORD_USE).append(" [");
      lastEmittedLine = lbrack.getLine();
      appendTrailingCommentIfOnLine(lbrack.getLine(), targetStart.getTokenIndex());

      boolean emittedInUse = flushStandaloneCommentsBefore(
          targetStart.getTokenIndex(),
          baseIndent + 1,
          false
      );
      startNewBlockChildLine(baseIndent + 1, targetStart.getLine(), emittedInUse);
      sb.append(ctx.useTarget().getText());
      if (ctx.useOptionsBlock() != null) {
        sb.append(' ').append(formatUseOptionsBlockInline(ctx.useOptionsBlock()));
      }
      if (ctx.useAliasBlock() != null) {
        StvnParser.UseAliasBlockContext aliasBlock = ctx.useAliasBlock();
        Token aliasOpen = aliasBlock.LBRACE().getSymbol();
        Token aliasClose = aliasBlock.RBRACE().getSymbol();
        boolean singleLineAliasBlock = aliasOpen.getLine() == aliasClose.getLine()
            && !hasCommentsBetween(aliasOpen.getTokenIndex(), aliasClose.getTokenIndex());
        if (singleLineAliasBlock) {
          sb.append(' ').append(formatUseAliasBlockInline(aliasBlock));
          lastEmittedLine = aliasClose.getLine();
          appendTrailingCommentIfOnLine(aliasClose.getLine(), rbrack.getTokenIndex());
        } else {
          sb.append(" {");
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
            startNewBlockChildLine(baseIndent + 2, alias.getStart().getLine(), emittedAlias);
            emittedAlias = true;
            sb.append(formatUseMapAliasInline(alias));
            lastEmittedLine = alias.getStop().getLine();
            appendTrailingCommentIfOnLine(alias.getStop().getLine(), aliasLimit);
          }

          flushStandaloneCommentsBefore(aliasClose.getTokenIndex(), baseIndent + 2, emittedAlias);
          sb.append('\n')
              .append(indentStr(baseIndent + 1))
              .append(StvnVocabulary.DELIM_CLOSE_BRACE);
          lastEmittedLine = aliasClose.getLine();
          appendTrailingCommentIfOnLine(aliasClose.getLine(), rbrack.getTokenIndex());
        }
      } else {
        lastEmittedLine = ctx.useOptionsBlock() != null
            ? ctx.useOptionsBlock().getStop().getLine()
            : ctx.useTarget().getStop().getLine();
        appendTrailingCommentIfOnLine(lastEmittedLine, rbrack.getTokenIndex());
      }

      flushStandaloneCommentsBefore(rbrack.getTokenIndex(), baseIndent + 1, true);
      sb.append('\n')
          .append(indentStr(baseIndent))
          .append(StvnVocabulary.DELIM_CLOSE_BRACKET);
      lastEmittedLine = rbrack.getLine();
      appendTrailingCommentIfOnLine(rbrack.getLine(), nextTokenLimit);
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
          if (i > 0) {
            sb.append(' ');
          } else {
            sb.append(' ');
          }
          sb.append(formatIncludeElementInline(elements.get(i)));
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
        if (elem.includeOptionsBlock() != null) {
          sb.append(' ').append(formatIncludeOptionsBlockInline(elem.includeOptionsBlock()));
        }
        if (elem.includeAliasBlock() != null) {
          StvnParser.IncludeAliasBlockContext aliasBlock = elem.includeAliasBlock();
          Token aliasOpen = aliasBlock.LBRACE().getSymbol();
          Token aliasClose = aliasBlock.RBRACE().getSymbol();
          sb.append(" {");
          lastEmittedLine = aliasOpen.getLine();

          boolean emittedAlias = false;
          List<StvnParser.IncludeMapAliasContext> aliases = aliasBlock.includeMapAlias();
          int aliasOpenLimit = aliases.isEmpty()
              ? aliasClose.getTokenIndex()
              : aliases.get(0).getStart().getTokenIndex();
          appendTrailingCommentIfOnLine(aliasOpen.getLine(), aliasOpenLimit);
          for (int i = 0; i < aliases.size(); i++) {
            StvnParser.IncludeMapAliasContext alias = aliases.get(i);
            int aliasLimit = (i + 1 < aliases.size())
                ? aliases.get(i + 1).getStart().getTokenIndex()
                : aliasClose.getTokenIndex();
            emittedAlias = flushStandaloneCommentsBefore(
                alias.getStart().getTokenIndex(),
                baseIndent + 2,
                emittedAlias
            );
            startNewBlockChildLine(baseIndent + 2, alias.getStart().getLine(), emittedAlias);
            emittedAlias = true;
            sb.append(alias.typeKeyword(0).getText())
                .append(' ')
                .append(alias.typeKeyword(1).getText());
            lastEmittedLine = alias.getStop().getLine();
            appendTrailingCommentIfOnLine(alias.getStop().getLine(), aliasLimit);
          }

          flushStandaloneCommentsBefore(aliasClose.getTokenIndex(), baseIndent + 2, emittedAlias);
          boolean sameLineClose = aliasClose.getLine() == rbrack.getLine()
              && !hasCommentsBetween(aliasClose.getTokenIndex(), rbrack.getTokenIndex());
          if (sameLineClose) {
            sb.append('\n').append(indentStr(baseIndent + 1)).append("} ]");
            lastEmittedLine = rbrack.getLine();
            appendTrailingCommentIfOnLine(rbrack.getLine(), nextTokenLimit);
          } else {
            sb.append('\n').append(indentStr(baseIndent + 1)).append(StvnVocabulary.DELIM_CLOSE_BRACE);
            lastEmittedLine = aliasClose.getLine();
            appendTrailingCommentIfOnLine(aliasClose.getLine(), rbrack.getTokenIndex());
            flushStandaloneCommentsBefore(rbrack.getTokenIndex(), baseIndent + 1, true);
            sb.append('\n').append(indentStr(baseIndent)).append(StvnVocabulary.DELIM_CLOSE_BRACKET);
            lastEmittedLine = rbrack.getLine();
            appendTrailingCommentIfOnLine(rbrack.getLine(), nextTokenLimit);
          }
        } else {
          lastEmittedLine = elem.getStop().getLine();
          appendTrailingCommentIfOnLine(lastEmittedLine, rbrack.getTokenIndex());
          flushStandaloneCommentsBefore(rbrack.getTokenIndex(), baseIndent + 1, false);
          sb.append('\n').append(indentStr(baseIndent)).append(StvnVocabulary.DELIM_CLOSE_BRACKET);
          lastEmittedLine = rbrack.getLine();
          appendTrailingCommentIfOnLine(rbrack.getLine(), nextTokenLimit);
        }
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
        startNewBlockChildLine(baseIndent + 1, elem.getStart().getLine(), emittedElem);
        emittedElem = true;

        sb.append(formatStringLiteralText(elem.stringLiteral()));
        if (elem.includeOptionsBlock() != null) {
          sb.append(' ').append(formatIncludeOptionsBlockInline(elem.includeOptionsBlock()));
        }
        if (elem.includeAliasBlock() != null) {
          StvnParser.IncludeAliasBlockContext aliasBlock = elem.includeAliasBlock();
          Token aliasOpen = aliasBlock.LBRACE().getSymbol();
          Token aliasClose = aliasBlock.RBRACE().getSymbol();
          boolean singleLineAlias = aliasOpen.getLine() == aliasClose.getLine()
              && !hasCommentsBetween(aliasOpen.getTokenIndex(), aliasClose.getTokenIndex());
          if (singleLineAlias) {
            sb.append(' ').append(formatIncludeAliasBlockInline(aliasBlock));
            lastEmittedLine = aliasClose.getLine();
            appendTrailingCommentIfOnLine(aliasClose.getLine(), elemLimit);
          } else {
            sb.append(" {");
            lastEmittedLine = aliasOpen.getLine();

            boolean emittedAlias = false;
            List<StvnParser.IncludeMapAliasContext> aliases = aliasBlock.includeMapAlias();
            int aliasOpenLimit = aliases.isEmpty()
                ? aliasClose.getTokenIndex()
                : aliases.get(0).getStart().getTokenIndex();
            appendTrailingCommentIfOnLine(aliasOpen.getLine(), aliasOpenLimit);
            for (int i = 0; i < aliases.size(); i++) {
              StvnParser.IncludeMapAliasContext alias = aliases.get(i);
              int aliasLimit = (i + 1 < aliases.size())
                  ? aliases.get(i + 1).getStart().getTokenIndex()
                  : aliasClose.getTokenIndex();
              emittedAlias = flushStandaloneCommentsBefore(
                  alias.getStart().getTokenIndex(),
                  baseIndent + 2,
                  emittedAlias
              );
              startNewBlockChildLine(baseIndent + 2, alias.getStart().getLine(), emittedAlias);
              emittedAlias = true;
              sb.append(alias.typeKeyword(0).getText())
                  .append(' ')
                  .append(alias.typeKeyword(1).getText());
              lastEmittedLine = alias.getStop().getLine();
              appendTrailingCommentIfOnLine(alias.getStop().getLine(), aliasLimit);
            }

            flushStandaloneCommentsBefore(aliasClose.getTokenIndex(), baseIndent + 2, emittedAlias);
            sb.append('\n')
                .append(indentStr(baseIndent + 1))
                .append(StvnVocabulary.DELIM_CLOSE_BRACE);
            lastEmittedLine = aliasClose.getLine();
            appendTrailingCommentIfOnLine(aliasClose.getLine(), elemLimit);
          }
        } else {
          lastEmittedLine = elem.getStop().getLine();
          appendTrailingCommentIfOnLine(lastEmittedLine, elemLimit);
        }
      }

      flushStandaloneCommentsBefore(rbrack.getTokenIndex(), baseIndent + 1, emittedElem);
      sb.append('\n')
          .append(indentStr(baseIndent))
          .append(StvnVocabulary.DELIM_CLOSE_BRACKET);
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

    private String formatUseAliasBlockInline(StvnParser.UseAliasBlockContext ctx) {
      List<StvnParser.UseMapAliasContext> aliases = ctx.useMapAlias();
      if (aliases.isEmpty()) {
        return "{}";
      }
      StringBuilder b = new StringBuilder("{ ");
      for (int i = 0; i < aliases.size(); i++) {
        if (i > 0) {
          b.append(' ');
        }
        b.append(formatUseMapAliasInline(aliases.get(i)));
      }
      b.append(" }");
      return b.toString();
    }

    private static String formatUseMapAliasInline(StvnParser.UseMapAliasContext ctx) {
      if (!ctx.typeKeyword().isEmpty()) {
        return ctx.typeKeyword(0).getText() + " " + ctx.typeKeyword(1).getText();
      }
      return ctx.valueKeyword(0).getText() + " " + ctx.valueKeyword(1).getText();
    }

    private String formatIncludeElementInline(StvnParser.IncludeElementContext ctx) {
      StringBuilder b = new StringBuilder(formatStringLiteralText(ctx.stringLiteral()));
      if (ctx.includeOptionsBlock() != null) {
        b.append(' ').append(formatIncludeOptionsBlockInline(ctx.includeOptionsBlock()));
      }
      if (ctx.includeAliasBlock() != null) {
        b.append(' ').append(formatIncludeAliasBlockInline(ctx.includeAliasBlock()));
      }
      return b.toString();
    }

    private static String formatIncludeOptionsBlockInline(StvnParser.IncludeOptionsBlockContext ctx) {
      List<StvnParser.IncludeOptionContext> opts = ctx.includeOption();
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

    private static String formatIncludeAliasBlockInline(StvnParser.IncludeAliasBlockContext ctx) {
      List<StvnParser.IncludeMapAliasContext> aliases = ctx.includeMapAlias();
      if (aliases.isEmpty()) {
        return "{}";
      }
      StringBuilder b = new StringBuilder("{ ");
      for (int i = 0; i < aliases.size(); i++) {
        if (i > 0) {
          b.append(' ');
        }
        b.append(aliases.get(i).typeKeyword(0).getText())
            .append(' ')
            .append(aliases.get(i).typeKeyword(1).getText());
      }
      b.append(" }");
      return b.toString();
    }

    private void formatTypeDefinition(
        StvnParser.TypeDefinitionContext ctx,
        int indent,
        int nextTokenLimit
    ) {
      sb.append(ctx.typeDefTarget().getText()).append(' ');
      formatSchemaType(ctx.schemaType(), indent);
      lastEmittedLine = ctx.getStop().getLine();
      appendTrailingCommentIfOnLine(ctx.getStop().getLine(), nextTokenLimit);
    }

    private void formatConstantDefinition(
        StvnParser.ConstantDefinitionContext ctx,
        int indent,
        int nextTokenLimit
    ) {
      sb.append(ctx.valueKeyword().getText()).append(' ');
      formatSchemaType(ctx.schemaType(), indent);
      sb.append(' ');
      formatValue(ctx.value(), indent);
      lastEmittedLine = ctx.getStop().getLine();
      appendTrailingCommentIfOnLine(ctx.getStop().getLine(), nextTokenLimit);
    }

    private void formatTypeEntry(StvnParser.TypeEntryContext ctx, int indent, int nextSectionLimit) {
      sb.append(StvnVocabulary.KEYWORD_TYPE).append(' ');
      formatSchemaType(ctx.schemaType(), indent);
      lastEmittedLine = ctx.getStop().getLine();
      appendTrailingCommentIfOnLine(ctx.getStop().getLine(), nextSectionLimit);
    }

    private void formatBodyEntry(StvnParser.BodyEntryContext ctx, int indent, int nextSectionLimit) {
      sb.append(StvnVocabulary.KEYWORD_BODY).append(' ');
      formatValue(ctx.value(), indent);
      lastEmittedLine = ctx.getStop().getLine();
      appendTrailingCommentIfOnLine(ctx.getStop().getLine(), nextSectionLimit);
    }

    private void formatSchemaType(StvnParser.SchemaTypeContext ctx, int indent) {
      if (ctx.metadataMap() != null) {
        formatMetadataMap(ctx.metadataMap(), indent);
        sb.append(' ');
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

      if (!hasComments) {
        List<SortedFacetItem> sorted = new ArrayList<>(rawEntries.size());
        for (int i = 0; i < rawEntries.size(); i++) {
          StvnParser.MetadataEntryContext entry = rawEntries.get(i);
          sorted.add(new SortedFacetItem(metadataFacetRank(entry), i, entry, List.of(), null));
        }
        sorted.sort(Comparator.comparingInt(SortedFacetItem::rank)
            .thenComparingInt(SortedFacetItem::originalOrder));
        sb.append("{ ");
        for (int i = 0; i < sorted.size(); i++) {
          if (i > 0) {
            sb.append(' ');
          }
          formatMetadataEntryInline(sorted.get(i).ctx());
        }
        sb.append(" }");
        lastEmittedLine = closeBrace.getLine();
      } else {
        sb.append(StvnVocabulary.DELIM_OPEN_BRACE);
        lastEmittedLine = openBrace.getLine();
        int openLimit = rawEntries.isEmpty()
            ? closeBrace.getTokenIndex()
            : rawEntries.get(0).getStart().getTokenIndex();
        appendTrailingCommentIfOnLine(openBrace.getLine(), openLimit);

        List<SortedFacetItem> sorted = new ArrayList<>(rawEntries.size());
        for (int i = 0; i < rawEntries.size(); i++) {
          StvnParser.MetadataEntryContext entry = rawEntries.get(i);
          int nextLimit = (i + 1 < rawEntries.size())
              ? rawEntries.get(i + 1).getStart().getTokenIndex()
              : closeBrace.getTokenIndex();

          List<Token> leading = new ArrayList<>();
          while (nextCommentIdx < comments.size()
              && comments.get(nextCommentIdx).getTokenIndex() < entry.getStart().getTokenIndex()) {
            leading.add(comments.get(nextCommentIdx++));
          }
          Token trailing = null;
          if (nextCommentIdx < comments.size()) {
            Token candidate = comments.get(nextCommentIdx);
            if (candidate.getTokenIndex() < nextLimit && candidate.getLine() == entry.getStop().getLine()) {
              trailing = candidate;
              nextCommentIdx++;
            }
          }
          sorted.add(new SortedFacetItem(metadataFacetRank(entry), i, entry, leading, trailing));
        }

        List<Token> footerComments = new ArrayList<>();
        while (nextCommentIdx < comments.size()
            && comments.get(nextCommentIdx).getTokenIndex() < closeBrace.getTokenIndex()) {
          footerComments.add(comments.get(nextCommentIdx++));
        }

        sorted.sort(Comparator.comparingInt(SortedFacetItem::rank)
            .thenComparingInt(SortedFacetItem::originalOrder));

        for (SortedFacetItem item : sorted) {
          for (Token c : item.leadingComments()) {
            sb.append('\n').append(indentStr(indent + 1)).append(c.getText());
          }
          sb.append('\n').append(indentStr(indent + 1));
          formatMetadataEntryInline(item.ctx());
          if (item.trailingComment() != null) {
            sb.append(' ').append(item.trailingComment().getText());
          }
        }
        for (Token c : footerComments) {
          sb.append('\n').append(indentStr(indent + 1)).append(c.getText());
        }
        sb.append('\n').append(indentStr(indent)).append(StvnVocabulary.DELIM_CLOSE_BRACE);
        lastEmittedLine = closeBrace.getLine();
      }
    }

    private void formatMetadataEntryInline(StvnParser.MetadataEntryContext ctx) {
      if (ctx.facetUnsigned() != null) {
        sb.append(StvnVocabulary.FACET_KW_UNSIGNED);
        if (ctx.facetUnsigned().standaloneFacetIllegalArg() != null) {
          sb.append(' ').append(ctx.facetUnsigned().standaloneFacetIllegalArg().getText());
        }
      } else if (ctx.facetExact() != null) {
        sb.append(StvnVocabulary.FACET_KW_EXACT);
        if (ctx.facetExact().standaloneFacetIllegalArg() != null) {
          sb.append(' ').append(ctx.facetExact().standaloneFacetIllegalArg().getText());
        }
      } else if (ctx.facetInvertible() != null) {
        sb.append(StvnVocabulary.FACET_KW_INVERTIBLE);
        if (ctx.facetInvertible().standaloneFacetIllegalArg() != null) {
          sb.append(' ').append(ctx.facetInvertible().standaloneFacetIllegalArg().getText());
        }
      } else if (ctx.facetTemporalScale() != null) {
        sb.append(ctx.facetTemporalScale().getStart().getText());
        if (ctx.facetTemporalScale().standaloneFacetIllegalArg() != null) {
          sb.append(' ').append(ctx.facetTemporalScale().standaloneFacetIllegalArg().getText());
        }
      } else if (ctx.facetTemporalMode() != null) {
        sb.append(ctx.facetTemporalMode().getStart().getText());
        if (ctx.facetTemporalMode().standaloneFacetIllegalArg() != null) {
          sb.append(' ').append(ctx.facetTemporalMode().standaloneFacetIllegalArg().getText());
        }
      } else if (ctx.metadataSize() != null) {
        sb.append(ctx.metadataSize().getStart().getText())
            .append(' ')
            .append(formatMetadataValueText(ctx.metadataSize().metadataValue()));
      } else if (ctx.metadataBool() != null) {
        sb.append(ctx.metadataBool().getStart().getText())
            .append(' ')
            .append(formatMetadataValueText(ctx.metadataBool().metadataValue()));
      } else if (ctx.metadataRange() != null) {
        sb.append(ctx.metadataRange().getStart().getText())
            .append(' ')
            .append(formatMetadataValueText(ctx.metadataRange().metadataValue()));
      } else if (ctx.metadataFilter() != null) {
        sb.append(ctx.metadataFilter().getStart().getText())
            .append(' ')
            .append(formatVariantListText(ctx.metadataFilter().variantList()));
      } else if (ctx.metadataTrait() != null) {
        StvnParser.MetadataTraitContext trait = ctx.metadataTrait();
        String arg = trait.booleanLiteral() != null
            ? trait.booleanLiteral().getText()
            : trait.valueKeyword().getText();
        sb.append(StvnVocabulary.FACET_KW_PRESERVE_INDENT).append(' ').append(arg);
      } else if (ctx.metadataString() != null) {
        sb.append(StvnVocabulary.FACET_KW_REGEX)
            .append(' ')
            .append(formatMetadataValueText(ctx.metadataString().metadataValue()));
      } else if (ctx.facetDirective() != null) {
        sb.append(StvnVocabulary.FACET_KW_STRIP);
        if (ctx.facetDirective().standaloneFacetIllegalArg() != null) {
          sb.append(' ').append(ctx.facetDirective().standaloneFacetIllegalArg().getText());
        }
      } else if (ctx.metadataUnknownEntry() != null) {
        StvnParser.MetadataUnknownEntryContext unk = ctx.metadataUnknownEntry();
        sb.append(unk.unknownFacetKeyword().getText());
        if (unk.value() != null) {
          sb.append(' ');
          formatValue(unk.value(), 2);
        } else if (unk.schemaType() != null) {
          sb.append(' ');
          formatSchemaType(unk.schemaType(), 2);
        }
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
        sb.append(head).append(StvnVocabulary.DELIM_OPEN_PAREN);
        List<StvnParser.SchemaTypeContext> args = coll.schemaType();
        for (int i = 0; i < args.size(); i++) {
          if (i > 0) {
            sb.append(' ');
          }
          formatSchemaType(args.get(i), indent);
        }
        sb.append(StvnVocabulary.DELIM_CLOSE_PAREN);
        lastEmittedLine = coll.getStop().getLine();
      } else if (ctx.productType() != null) {
        StvnParser.TupleTypeContext tup = (StvnParser.TupleTypeContext) ctx.productType();
        sb.append(StvnVocabulary.TYPE_TUPLE);
        formatSchemaGroup(
            tup.LPAREN().getSymbol(),
            tup.RPAREN().getSymbol(),
            tup.schemaType(),
            indent
        );
      } else if (ctx.sumType() != null) {
        StvnParser.SumTypeContext sum = ctx.sumType();
        if (sum.KW_ENUM() != null) {
          sb.append(StvnVocabulary.TYPE_ENUM);
          List<StvnParser.ValueKeywordContext> variants = sum.enumDef().valueKeyword();
          sb.append(StvnVocabulary.DELIM_OPEN_BRACKET);
          for (int i = 0; i < variants.size(); i++) {
            if (i > 0) {
              sb.append(' ');
            }
            sb.append(variants.get(i).getText());
          }
          sb.append(StvnVocabulary.DELIM_CLOSE_BRACKET);
          lastEmittedLine = sum.getStop().getLine();
        } else if (sum.KW_OPTION() != null) {
          sb.append(StvnVocabulary.TYPE_OPTION).append(StvnVocabulary.DELIM_OPEN_PAREN);
          formatSchemaType(sum.schemaType(0), indent);
          sb.append(StvnVocabulary.DELIM_CLOSE_PAREN);
          lastEmittedLine = sum.getStop().getLine();
        } else if (sum.KW_EITHER() != null) {
          sb.append(StvnVocabulary.TYPE_EITHER).append(StvnVocabulary.DELIM_OPEN_PAREN);
          formatSchemaType(sum.schemaType(0), indent);
          sb.append(' ');
          formatSchemaType(sum.schemaType(1), indent);
          sb.append(StvnVocabulary.DELIM_CLOSE_PAREN);
          lastEmittedLine = sum.getStop().getLine();
        } else if (sum.KW_UNION() != null) {
          sb.append(StvnVocabulary.TYPE_UNION);
          formatSchemaGroup(
              sum.LPAREN().getSymbol(),
              sum.RPAREN().getSymbol(),
              sum.schemaType(),
              indent
          );
        }
      }
    }

    private void formatSchemaGroup(
        Token openParen,
        Token closeParen,
        List<StvnParser.SchemaTypeContext> children,
        int indent
    ) {
      boolean hasInteriorComments = hasCommentsBetween(openParen.getTokenIndex(), closeParen.getTokenIndex());
      boolean multiLine = hasInteriorComments
          || openParen.getLine() < closeParen.getLine()
          || requiresMultiLineSchemaGroup(children);

      sb.append(StvnVocabulary.DELIM_OPEN_PAREN);
      lastEmittedLine = openParen.getLine();

      if (!multiLine) {
        for (int i = 0; i < children.size(); i++) {
          if (i > 0) {
            sb.append(' ');
          }
          formatSchemaType(children.get(i), indent);
        }
        sb.append(StvnVocabulary.DELIM_CLOSE_PAREN);
        lastEmittedLine = closeParen.getLine();
        return;
      }

      int openParenLimit = children.isEmpty()
          ? closeParen.getTokenIndex()
          : children.get(0).getStart().getTokenIndex();
      appendTrailingCommentIfOnLine(openParen.getLine(), openParenLimit);
      boolean emittedChild = false;
      for (int i = 0; i < children.size(); i++) {
        StvnParser.SchemaTypeContext child = children.get(i);
        int childLimit = (i + 1 < children.size())
            ? children.get(i + 1).getStart().getTokenIndex()
            : closeParen.getTokenIndex();
        emittedChild = flushStandaloneCommentsBefore(
            child.getStart().getTokenIndex(),
            indent + 1,
            emittedChild
        );
        startNewBlockChildLine(indent + 1, child.getStart().getLine(), emittedChild);
        emittedChild = true;
        formatSchemaType(child, indent + 1);
        lastEmittedLine = child.getStop().getLine();
        appendTrailingCommentIfOnLine(child.getStop().getLine(), childLimit);
      }

      flushStandaloneCommentsBefore(closeParen.getTokenIndex(), indent + 1, emittedChild);
      sb.append('\n').append(indentStr(indent)).append(StvnVocabulary.DELIM_CLOSE_PAREN);
      lastEmittedLine = closeParen.getLine();
    }

    private boolean requiresMultiLineSchemaGroup(List<StvnParser.SchemaTypeContext> children) {
      for (StvnParser.SchemaTypeContext child : children) {
        if (isInlineFacetedOrMultiLineSchemaNode(child)) {
          return true;
        }
      }
      return false;
    }

    private boolean isInlineFacetedOrMultiLineSchemaNode(StvnParser.SchemaTypeContext ctx) {
      if (ctx.metadataMap() != null && !ctx.metadataMap().metadataEntry().isEmpty()) {
        return true;
      }
      return containsMultiLineSchemaConstruct(ctx);
    }

    private boolean containsMultiLineSchemaConstruct(StvnParser.@Nullable SchemaTypeContext ctx) {
      if (ctx == null) {
        return false;
      }
      if (ctx.schemaConstructor() != null) {
        StvnParser.SchemaConstructorContext sc = ctx.schemaConstructor();
        if (sc.productType() != null) {
          StvnParser.TupleTypeContext tup = (StvnParser.TupleTypeContext) sc.productType();
          Token openParen = tup.LPAREN().getSymbol();
          Token closeParen = tup.RPAREN().getSymbol();
          return openParen.getLine() < closeParen.getLine()
              || hasCommentsBetween(openParen.getTokenIndex(), closeParen.getTokenIndex())
              || requiresMultiLineSchemaGroup(tup.schemaType());
        }
        if (sc.sumType() != null) {
          StvnParser.SumTypeContext sum = sc.sumType();
          if (sum.KW_UNION() != null) {
            Token openParen = sum.LPAREN().getSymbol();
            Token closeParen = sum.RPAREN().getSymbol();
            return openParen.getLine() < closeParen.getLine()
                || hasCommentsBetween(openParen.getTokenIndex(), closeParen.getTokenIndex())
                || requiresMultiLineSchemaGroup(sum.schemaType());
          }
          for (StvnParser.SchemaTypeContext child : sum.schemaType()) {
            if (containsMultiLineSchemaConstruct(child)) {
              return true;
            }
          }
        }
        if (sc.collectionType() != null) {
          for (StvnParser.SchemaTypeContext child : sc.collectionType().schemaType()) {
            if (containsMultiLineSchemaConstruct(child)) {
              return true;
            }
          }
        }
      }
      return false;
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
        StvnParser.ExplicitOptionValueContext opt = ctx.explicitOptionValue();
        sb.append(opt.getStart().getText());
        if (opt.value() != null) {
          sb.append(' ');
          formatValue(opt.value(), indent);
        }
        lastEmittedLine = opt.getStop().getLine();
      } else if (ctx.explicitEitherValue() != null) {
        StvnParser.ExplicitEitherValueContext eith = ctx.explicitEitherValue();
        sb.append(eith.getStart().getText()).append(' ');
        formatValue(eith.value(), indent);
        lastEmittedLine = eith.getStop().getLine();
      } else if (ctx.explicitUnionValue() != null) {
        StvnParser.ExplicitUnionValueContext un = ctx.explicitUnionValue();
        sb.append(un.UNION_TAG_PREFIX().getText()).append(' ');
        formatValue(un.value(), indent);
        lastEmittedLine = un.getStop().getLine();
      } else if (ctx.collectionValue() != null) {
        StvnParser.CollectionValueContext coll = ctx.collectionValue();
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
          || requiresMultiLinePayloadContainer(elements);
      sb.append(openText);
      lastEmittedLine = openDelim.getLine();

      if (!multiLine) {
        for (int i = 0; i < elements.size(); i++) {
          if (i > 0) {
            sb.append(' ');
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
        startNewBlockChildLine(indent + 1, el.getStart().getLine(), emittedElem);
        emittedElem = true;
        formatValue(el, indent + 1);
        lastEmittedLine = el.getStop().getLine();
        appendTrailingCommentIfOnLine(el.getStop().getLine(), elLimit);
      }

      flushStandaloneCommentsBefore(closeDelim.getTokenIndex(), indent + 1, emittedElem);
      sb.append('\n').append(indentStr(indent)).append(closeText);
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

      sb.append(StvnVocabulary.DELIM_OPEN_BRACE);
      lastEmittedLine = openBrace.getLine();
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
        startNewBlockChildLine(indent + 1, entry.getStart().getLine(), emittedEntry);
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
          formatValue(valVal, indent + 2);
          lastEmittedLine = valVal.getStop().getLine();
          appendTrailingCommentIfOnLine(valVal.getStop().getLine(), entryClose.getTokenIndex());
          flushStandaloneCommentsBefore(entryClose.getTokenIndex(), indent + 2, true);
          sb.append('\n').append(indentStr(indent + 1)).append(StvnVocabulary.DELIM_CLOSE_BRACKET);
        } else {
          sb.append(' ');
          formatValue(valVal, indent + 1);
          sb.append(StvnVocabulary.DELIM_CLOSE_BRACKET);
        }
        lastEmittedLine = entryClose.getLine();
        appendTrailingCommentIfOnLine(entryClose.getLine(), entryLimit);
      }

      flushStandaloneCommentsBefore(closeBrace.getTokenIndex(), indent + 1, emittedEntry);
      sb.append('\n').append(indentStr(indent)).append(StvnVocabulary.DELIM_CLOSE_BRACE);
      lastEmittedLine = closeBrace.getLine();
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

    private boolean hasCommentsBetween(int startTokenIndexExclusive, int endTokenIndexExclusive) {
      for (int i = nextCommentIdx; i < comments.size(); i++) {
        int tokIdx = comments.get(i).getTokenIndex();
        if (tokIdx <= startTokenIndexExclusive) {
          continue;
        }
        return tokIdx < endTokenIndexExclusive;
      }
      return false;
    }

    private void appendTrailingCommentIfOnLine(int line, int maxTokenIndexExclusive) {
      if (nextCommentIdx < comments.size()) {
        Token candidate = comments.get(nextCommentIdx);
        if (candidate.getLine() == line && candidate.getTokenIndex() < maxTokenIndexExclusive) {
          sb.append(' ').append(candidate.getText());
          lastEmittedLine = candidate.getLine();
          nextCommentIdx++;
        }
      }
    }

    private boolean flushStandaloneCommentsBefore(
        int limitTokenIndexExclusive,
        int indent,
        boolean hasEmittedItemInBlock
    ) {
      boolean emitted = hasEmittedItemInBlock;
      while (nextCommentIdx < comments.size()
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

    private void startNewBlockChildLine(int indent, int childStartLine, boolean hasEmittedItemInBlock) {
      if (hasEmittedItemInBlock && lastEmittedLine > 0 && childStartLine - lastEmittedLine >= 2) {
        sb.append('\n');
      }
      sb.append('\n').append(indentStr(indent));
    }

    private static String indentStr(int level) {
      return INDENT_UNIT.repeat(Math.max(0, level));
    }
  }
}
