package org.stvnadore.core.printer.internal;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnDefsCatalog;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnSchema.FloatRepresentation;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.ir.StvnValue.EitherBranch;
import org.stvnadore.core.ir.StvnValue.Ieee754SpecialKind;
import org.stvnadore.core.ir.StvnValue.KeyValueEntry;
import org.stvnadore.core.lowering.StvnLiteralParser;
import org.stvnadore.core.lowering.StvnPayloadLowerer;
import org.stvnadore.core.lowering.StvnReachabilityAndCasVerifier;
import org.stvnadore.core.printer.PrinterOptions;

import java.io.IOException;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Dispatcher mapping pure-IR {@link StvnValue} trees to their corresponding {@link LayoutWriter}
 * serialization methods.
 *
 * @since 1.0.0
 */
@NullMarked
public final class PatternPrinterDispatcher {

  private static final String PRELUDE_CONST_PREFIX = StvnVocabulary.SIGIL_VALUE + "org/stvnadore/prelude/";

  private sealed interface SurfaceNode permits
      OptionNoneSurface,
      OptionSomeSurface,
      EitherTagSurface,
      UnionTagSurface,
      LeafSurface {
  }

  private record OptionNoneSurface() implements SurfaceNode {}

  private record OptionSomeSurface(SurfaceNode child) implements SurfaceNode {}

  private record EitherTagSurface(EitherBranch branch, SurfaceNode child) implements SurfaceNode {}

  private record UnionTagSurface(BigInteger oneBasedBranchIndex, SurfaceNode child) implements SurfaceNode {}

  private record LeafSurface(StvnValue value) implements SurfaceNode {}

  private PatternPrinterDispatcher() {
    // Utility/Facade class
  }

  /**
   * Dispatches the printing of a pure-IR {@link StvnValue} to the provided {@link LayoutWriter}
   * according to the supplied {@link PrinterOptions}.
   *
   * @param val     the pure-IR value to print
   * @param layout  the layout writer to write to
   * @param options the formatting printer options
   * @throws IOException           if a writing exception occurs
   * @throws IllegalStateException if {@code HAPPY_PATH_INFERRED} is active on a value whose schema
   *                               is a poisoned sentinel lacking valid schema context
   */
  public static void dispatch(StvnValue val, LayoutWriter layout, PrinterOptions options) throws IOException {
    dispatch(val, layout, options, StvnVocabulary.FENCE_TAG_RAW);
  }

  /**
   * Dispatches the printing of a pure-IR {@link StvnValue} to the provided {@link LayoutWriter}
   * using {@code preferredFenceTag} when the top-level value is a {@link StvnValue.StvnString}
   * serialized as a Rule STR-04 fenced string.
   *
   * @param val               the pure-IR value to print
   * @param layout            the layout writer to write to
   * @param options           the formatting printer options
   * @param preferredFenceTag the preferred base fence tag ({@code RAW} or {@code REGEX})
   * @throws IOException           if a writing exception occurs
   * @throws IllegalStateException if {@code HAPPY_PATH_INFERRED} is active on a value whose schema
   *                               is a poisoned sentinel lacking valid schema context
   */
  public static void dispatch(
      StvnValue val, LayoutWriter layout, PrinterOptions options, String preferredFenceTag
  ) throws IOException {
    dispatch(
        val,
        layout,
        options,
        preferredFenceTag,
        StvnReachabilityAndCasVerifier.synthesizeDocumentFromValue(val).defs()
    );
  }

  /**
   * Dispatches the printing of a pure-IR {@link StvnValue} to the provided {@link LayoutWriter}
   * using {@code preferredFenceTag} and {@code catalog} for cycle-safe recursive nominal schema
   * unfolding and constant-shadowing protection under {@code HAPPY_PATH_INFERRED}.
   *
   * @param val               the pure-IR value to print
   * @param layout            the layout writer to write to
   * @param options           the formatting printer options
   * @param preferredFenceTag the preferred base fence tag ({@code RAW} or {@code REGEX})
   * @param catalog           the resolved definitions catalog for recursive nominal lookup
   * @throws IOException           if a writing exception occurs
   * @throws IllegalStateException if {@code HAPPY_PATH_INFERRED} is active on a value whose schema
   *                               is a poisoned sentinel lacking valid schema context
   */
  public static void dispatch(
      StvnValue val,
      LayoutWriter layout,
      PrinterOptions options,
      String preferredFenceTag,
      StvnDefsCatalog catalog
  ) throws IOException {
    if (options.sumTypePolicy() == PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
        && isRealPoisonedSchema(val.schema())) {
      throw new IllegalStateException(
          "Cannot apply HAPPY_PATH_INFERRED without schema context: target schema is required to verify variant elision legality under Rules A, B, and I."
      );
    }

    SurfaceNode surface = resolveSumSurface(val, options, catalog);
    emitSurface(surface, layout, options, preferredFenceTag, catalog);
  }

  private static void emitSurface(
      SurfaceNode surface,
      LayoutWriter layout,
      PrinterOptions options,
      String preferredFenceTag,
      StvnDefsCatalog catalog
  ) throws IOException {
    switch (surface) {
      case OptionNoneSurface ignored ->
          layout.writeOptionNone(options.symbolStyle());
      case OptionSomeSurface some -> {
        layout.openOptionSomeTag(options.symbolStyle());
        emitSurface(some.child(), layout, options, preferredFenceTag, catalog);
        layout.closeTag();
      }
      case EitherTagSurface eith -> {
        layout.openEitherTag(eith.branch() == EitherBranch.RIGHT, options.symbolStyle());
        emitSurface(eith.child(), layout, options, preferredFenceTag, catalog);
        layout.closeTag();
      }
      case UnionTagSurface un -> {
        layout.openTag(StvnVocabulary.SIGIL_VALUE + un.oneBasedBranchIndex().toString());
        emitSurface(un.child(), layout, options, preferredFenceTag, catalog);
        layout.closeTag();
      }
      case LeafSurface leaf ->
          dispatchNonSumLeaf(leaf.value(), layout, options, preferredFenceTag, catalog);
    }
  }

  private static void dispatchNonSumLeaf(
      StvnValue val,
      LayoutWriter layout,
      PrinterOptions options,
      String preferredFenceTag,
      StvnDefsCatalog catalog
  ) throws IOException {
    switch (val) {
      case StvnValue.StvnBoolean b ->
          layout.writeBoolean(b.value(), options.symbolStyle());

      case StvnValue.StvnInt i ->
          layout.writeInteger(i.value());

      case StvnValue.StvnFloat f -> {
        Ieee754SpecialKind special = f.specialKind();
        switch (special) {
          case NAN -> layout.writeLiteral(StvnVocabulary.LITERAL_NAN);
          case POSITIVE_INFINITY -> layout.writeLiteral(StvnVocabulary.LITERAL_POS_INFINITY);
          case NEGATIVE_INFINITY -> layout.writeLiteral(StvnVocabulary.LITERAL_NEG_INFINITY);
          case FINITE -> {
            if (f.negativeZero()) {
              layout.writeLiteral(StvnVocabulary.LITERAL_NEG_ZERO);
            } else {
              FloatRepresentation rep = f.schema() instanceof StvnSchema.FloatSchema fs
                  ? fs.representation()
                  : FloatRepresentation.IEEE_64;
              layout.writeFloat(f.decimalValue(), rep);
            }
          }
        }
      }

      case StvnValue.StvnString s -> {
        String text = s.value();
        boolean preserveIndent = s.schema() instanceof StvnSchema.StringSchema ss && ss.preserveIndent();
        if (layout instanceof PrettyLayoutWriter prettyLayout) {
          if (shouldEmitFencedPretty(text, preserveIndent)) {
            String tag = StvnLiteralParser.chooseSafeFenceTag(text, preferredFenceTag);
            prettyLayout.writeFencedString(text, tag, preserveIndent);
          } else {
            prettyLayout.writeSimpleString(text);
          }
        } else if (shouldEmitFencedCompact(text, preserveIndent)) {
          String tag = StvnLiteralParser.chooseSafeFenceTag(text, StvnVocabulary.FENCE_TAG_RAW);
          layout.writeLiteral("\"\"\"[" + tag + "]\n" + text + "[" + tag + "]\"\"\"");
        } else {
          layout.writeSimpleString(text);
        }
      }

      case StvnValue.StvnTimeEpoch te ->
          layout.writeInteger(te.epochTicks());

      case StvnValue.StvnDateTime dt ->
          layout.writeSimpleString(
              StvnLiteralParser.formatCanonicalDateTime(dt.mode(), dt.localWallClock(), dt.offset(), dt.zone())
          );

      case StvnValue.StvnEnum en ->
          layout.writeEnumKeyword(en.variantTag());

      case StvnValue.StvnSeq seq ->
          writeLinearContainer(
              seq.elements(), StvnVocabulary.DELIM_OPEN_BRACKET, StvnVocabulary.DELIM_CLOSE_BRACKET,
              layout, options, catalog
          );

      case StvnValue.StvnSet set ->
          writeLinearContainer(
              List.copyOf(set.elements()), StvnVocabulary.DELIM_OPEN_BRACKET, StvnVocabulary.DELIM_CLOSE_BRACKET,
              layout, options, catalog
          );

      case StvnValue.StvnTuple tup ->
          writeLinearContainer(
              tup.elements(), StvnVocabulary.DELIM_OPEN_PAREN, StvnVocabulary.DELIM_CLOSE_PAREN,
              layout, options, catalog
          );

      case StvnValue.StvnMap map -> {
        layout.openGroup(StvnVocabulary.DELIM_OPEN_BRACE);
        boolean pretty = layout instanceof PrettyLayoutWriter;
        List<KeyValueEntry> entries = map.entries();
        if (pretty && !entries.isEmpty()) {
          layout.newline();
          layout.indent();
        }
        boolean first = true;
        for (KeyValueEntry entry : entries) {
          if (!first) {
            if (pretty) {
              layout.newline();
            } else {
              layout.appendSeparator();
            }
          }
          first = false;
          layout.openGroup(StvnVocabulary.DELIM_OPEN_BRACKET);
          dispatch(entry.key(), layout, options, StvnVocabulary.FENCE_TAG_RAW, catalog);
          layout.appendSeparator();
          dispatch(entry.value(), layout, options, StvnVocabulary.FENCE_TAG_RAW, catalog);
          layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACKET);
        }
        if (pretty && !entries.isEmpty()) {
          layout.outdent();
          layout.newline();
        }
        layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACE);
      }

      case StvnValue.StvnOption opt ->
          dispatch(opt, layout, options, preferredFenceTag, catalog);
      case StvnValue.StvnEither eith ->
          dispatch(eith, layout, options, preferredFenceTag, catalog);
      case StvnValue.StvnUnion un ->
          dispatch(un, layout, options, preferredFenceTag, catalog);
      case StvnValue.StvnErrorValue err ->
          layout.writeSimpleString(err.diagnostic().message());
    }
  }

  private static void writeLinearContainer(
      List<StvnValue> elements,
      String openDelim,
      String closeDelim,
      LayoutWriter layout,
      PrinterOptions options,
      StvnDefsCatalog catalog
  ) throws IOException {
    boolean multiLine = (layout instanceof PrettyLayoutWriter) && requiresMultiLinePayloadContainer(elements, layout);
    layout.openGroup(openDelim);
    if (multiLine) {
      layout.indent();
    }
    boolean first = true;
    for (StvnValue el : elements) {
      if (multiLine) {
        layout.newline();
      } else if (!first) {
        layout.appendSeparator();
      }
      first = false;
      dispatch(el, layout, options, StvnVocabulary.FENCE_TAG_RAW, catalog);
    }
    if (multiLine) {
      layout.outdent();
      layout.newline();
    }
    layout.closeGroup(closeDelim);
  }

  private static boolean requiresMultiLinePayloadContainer(List<StvnValue> elements, LayoutWriter layout) {
    for (StvnValue el : elements) {
      if (isStructuredOrFencedPayload(el, layout)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isStructuredOrFencedPayload(StvnValue val, LayoutWriter layout) {
    return switch (val) {
      case StvnValue.StvnMap ignored -> true;
      case StvnValue.StvnTuple ignored -> true;
      case StvnValue.StvnSeq ignored -> true;
      case StvnValue.StvnSet ignored -> true;
      case StvnValue.StvnString s -> {
        boolean preserveIndent = s.schema() instanceof StvnSchema.StringSchema ss && ss.preserveIndent();
        yield (layout instanceof PrettyLayoutWriter)
            ? shouldEmitFencedPretty(s.value(), preserveIndent)
            : shouldEmitFencedCompact(s.value(), preserveIndent);
      }
      case StvnValue.StvnOption opt -> opt.value().map(inner -> isStructuredOrFencedPayload(inner, layout)).orElse(false);
      case StvnValue.StvnEither eith -> isStructuredOrFencedPayload(eith.value(), layout);
      case StvnValue.StvnUnion un -> isStructuredOrFencedPayload(un.value(), layout);
      default -> false;
    };
  }

  /**
   * Evaluates whether {@code AstPrettyPrinter} ({@link PrettyLayoutWriter}) must emit {@code text}
   * as a Rule STR-04 fenced verbatim string under Fenced-String Bias and the 100% lossless
   * round-trip soundness guard ({@code decode(print(s)) == s}).
   *
   * @param text           the decoded UTF-16 string content
   * @param preserveIndent the effective {@code #preserveIndent} flag for this emission site
   * @return {@code true} if fenced emission is triggered and 100% round-trip lossless
   */
  public static boolean shouldEmitFencedPretty(String text, boolean preserveIndent) {
    if (!containsSimpleEscapeTrigger(text) || containsForbiddenFencedControlChar(text)) {
      return false;
    }
    if (preserveIndent) {
      return true;
    }
    return !isAsciiSpacesOnly(text)
        && !hasNonEmptyAsciiSpaceOnlyLine(text)
        && StvnPayloadLowerer.trimCommonIndent(text).equals(text);
  }

  private static boolean shouldEmitFencedCompact(String text, boolean preserveIndent) {
    return !containsForbiddenFencedControlChar(text)
        && ((text.indexOf('\n') >= 0
            && (preserveIndent
                || (!isAsciiSpacesOnly(text)
                    && !hasNonEmptyAsciiSpaceOnlyLine(text)
                    && text.equals(StvnPayloadLowerer.trimCommonIndent(text)))))
            || (preserveIndent && text.indexOf('\t') >= 0));
  }

  private static boolean hasNonEmptyAsciiSpaceOnlyLine(String text) {
    int lineStart = 0;
    int len = text.length();
    for (int i = 0; i <= len; i++) {
      if (i == len || text.charAt(i) == '\n') {
        if (i > lineStart) {
          boolean allSpaces = true;
          for (int j = lineStart; j < i; j++) {
            if (text.charAt(j) != ' ') {
              allSpaces = false;
              break;
            }
          }
          if (allSpaces) {
            return true;
          }
        }
        lineStart = i + 1;
      }
    }
    return false;
  }

  private static boolean containsSimpleEscapeTrigger(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\\' || c == '"' || c == '\n' || c == '\t') {
        return true;
      }
    }
    return false;
  }

  private static boolean containsForbiddenFencedControlChar(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c < 0x20 && c != '\n' && c != '\t') {
        return true;
      }
    }
    return false;
  }

  private static boolean isAsciiSpacesOnly(String text) {
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) != ' ') {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns {@code true} if {@code schema} is a genuine poisoned sentinel (excluding recursive back-edges).
   *
   * @param schema the schema to inspect
   * @return {@code true} if poisoned without valid schema context
   */
  public static boolean isRealPoisonedSchema(StvnSchema schema) {
    return schema instanceof StvnSchema.PoisonedSchema p
        && !StvnPayloadLowerer.RECURSIVE_BACK_EDGE_SENTINEL.equals(p.primaryDiagnosticCode());
  }

  private static SurfaceNode resolveSumSurface(
      StvnValue val,
      PrinterOptions options,
      StvnDefsCatalog catalog
  ) {
    if (options.sumTypePolicy() == PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
        && isRealPoisonedSchema(val.schema())) {
      throw new IllegalStateException(
          "Cannot apply HAPPY_PATH_INFERRED without schema context: target schema is required to verify variant elision legality under Rules A, B, and I."
      );
    }

    return switch (val) {
      case StvnValue.StvnOption opt -> {
        StvnValue inner = opt.value().orElse(null);
        if (inner == null) {
          yield new OptionNoneSurface();
        }
        SurfaceNode child = resolveSumSurface(inner, options, catalog);
        boolean canElideSome = options.sumTypePolicy() == PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
            && !isControlOrConstantColliding(inner, catalog)
            && !(child instanceof OptionNoneSurface)
            && !(child instanceof OptionSomeSurface);
        yield canElideSome ? child : new OptionSomeSurface(child);
      }
      case StvnValue.StvnEither eith -> {
        SurfaceNode child = resolveSumSurface(eith.value(), options, catalog);
        if (eith.branch() != EitherBranch.RIGHT) {
          yield new EitherTagSurface(EitherBranch.LEFT, child);
        }
        boolean canElideRight = options.sumTypePolicy() == PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
            && !isControlOrConstantColliding(eith.value(), catalog)
            && !(child instanceof EitherTagSurface)
            && canElideEitherRightSurface(eith.schema(), child, options.symbolStyle(), catalog);
        yield canElideRight ? child : new EitherTagSurface(EitherBranch.RIGHT, child);
      }
      case StvnValue.StvnUnion un -> {
        SurfaceNode child = resolveSumSurface(un.value(), options, catalog);
        boolean canElideBranch = options.sumTypePolicy() == PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
            && !isControlOrConstantColliding(un.value(), catalog)
            && !(child instanceof UnionTagSurface)
            && canElideUnionBranchSurface(
                un.schema(), un.oneBasedBranchIndex(), child, options.symbolStyle(), catalog
            );
        yield canElideBranch ? child : new UnionTagSurface(un.oneBasedBranchIndex(), child);
      }
      default -> new LeafSurface(val);
    };
  }

  private static boolean isControlOrConstantColliding(StvnValue value, StvnDefsCatalog catalog) {
    if (value instanceof StvnValue.StvnString strVal) {
      return isControlOrUnionTagToken(strVal.value());
    }
    if (value instanceof StvnValue.StvnEnum enumVal) {
      String tag = enumVal.variantTag();
      return isControlOrUnionTagToken(tag)
          || catalog.findConstant(tag).isPresent()
          || tag.startsWith(PRELUDE_CONST_PREFIX);
    }
    return false;
  }

  private static boolean isControlOrUnionTagToken(String s) {
    if (StvnVocabulary.CONTROL_KEYWORDS.contains(s)) {
      return true;
    }
    if (s.length() >= 2 && s.startsWith(StvnVocabulary.SIGIL_VALUE)) {
      for (int i = 1; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c < '0' || c > '9') {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  private static boolean areIdenticalSchemas(StvnSchema a, StvnSchema b) {
    if (a.aliasName().isPresent() || b.aliasName().isPresent()) {
      return a.aliasName().equals(b.aliasName());
    }
    return a.equals(b);
  }

  private static boolean canElideEitherRightSurface(
      StvnSchema schema,
      SurfaceNode rightSurface,
      PrinterOptions.SymbolStyle symbolStyle,
      StvnDefsCatalog catalog
  ) {
    if (!(schema instanceof StvnSchema.EitherSchema es)) {
      return false;
    }
    if (areIdenticalSchemas(es.leftSchema(), es.rightSchema())) {
      return false;
    }
    return !couldSchemaCaptureSurfaceFresh(es.leftSchema(), rightSurface, symbolStyle, catalog);
  }

  private static boolean canElideUnionBranchSurface(
      StvnSchema schema,
      BigInteger oneBasedBranchIndex,
      SurfaceNode branchSurface,
      PrinterOptions.SymbolStyle symbolStyle,
      StvnDefsCatalog catalog
  ) {
    if (!(schema instanceof StvnSchema.UnionSchema us)) {
      return false;
    }
    List<StvnSchema> branches = us.branchSchemas();
    if (oneBasedBranchIndex.compareTo(BigInteger.ONE) < 0
        || oneBasedBranchIndex.compareTo(BigInteger.valueOf(branches.size())) > 0) {
      return false;
    }
    int selectedZeroBased = oneBasedBranchIndex.intValueExact() - 1;
    for (int i = 0; i < branches.size(); i++) {
      if (i == selectedZeroBased) {
        continue;
      }
      if (couldSchemaCaptureSurfaceFresh(branches.get(i), branchSurface, symbolStyle, catalog)) {
        return false;
      }
    }
    return true;
  }

  private static boolean couldSchemaCaptureSurfaceFresh(
      StvnSchema candidate,
      SurfaceNode surface,
      PrinterOptions.SymbolStyle symbolStyle,
      StvnDefsCatalog catalog
  ) {
    return couldSchemaCaptureSurface(
        candidate, surface, symbolStyle, catalog, new HashSet<>(), new HashSet<>()
    );
  }

  private static boolean couldSchemaCaptureSurface(
      StvnSchema candidate,
      SurfaceNode surface,
      PrinterOptions.SymbolStyle symbolStyle,
      StvnDefsCatalog catalog,
      Set<StvnSchema> visitedSchemas,
      Set<String> visitedAliases
  ) {
    if (candidate instanceof StvnSchema.PoisonedSchema p) {
      if (StvnPayloadLowerer.RECURSIVE_BACK_EDGE_SENTINEL.equals(p.primaryDiagnosticCode())
          && p.aliasName().isPresent()) {
        String fqni = p.aliasName().get();
        if (!visitedAliases.add(fqni)) {
          return false;
        }
        Optional<StvnSchema> resolved = catalog.findType(fqni);
        if (resolved.isEmpty()) {
          return true;
        }
        return couldSchemaCaptureSurface(
            resolved.get(), surface, symbolStyle, catalog, visitedSchemas, visitedAliases
        );
      }
      return true;
    }

    if (!visitedSchemas.add(candidate)) {
      return false;
    }
    candidate.aliasName().ifPresent(visitedAliases::add);

    return switch (candidate) {
      case StvnSchema.BooleanSchema ignored ->
          surface instanceof LeafSurface leaf && leaf.value() instanceof StvnValue.StvnBoolean;
      case StvnSchema.IntSchema ignored ->
          surface instanceof LeafSurface leaf
              && (leaf.value() instanceof StvnValue.StvnInt || leaf.value() instanceof StvnValue.StvnTimeEpoch);
      case StvnSchema.TimeEpochSchema ignored ->
          surface instanceof LeafSurface leaf
              && (leaf.value() instanceof StvnValue.StvnInt || leaf.value() instanceof StvnValue.StvnTimeEpoch);
      case StvnSchema.FloatSchema ignored ->
          surface instanceof LeafSurface leaf && leaf.value() instanceof StvnValue.StvnFloat;
      case StvnSchema.StringSchema ignored ->
          surface instanceof LeafSurface leaf
              && (leaf.value() instanceof StvnValue.StvnString || leaf.value() instanceof StvnValue.StvnDateTime);
      case StvnSchema.DateTimeSchema ignored ->
          surface instanceof LeafSurface leaf
              && (leaf.value() instanceof StvnValue.StvnDateTime || leaf.value() instanceof StvnValue.StvnString);
      case StvnSchema.EnumSchema en -> {
        String headTok = emittedHeadToken(surface, symbolStyle);
        yield headTok != null && en.rootVariants().contains(headTok);
      }
      case StvnSchema.EnumSubsetSchema sub -> {
        String headTok = emittedHeadToken(surface, symbolStyle);
        yield headTok != null && sub.activeVariants().contains(headTok);
      }
      case StvnSchema.TupleSchema ignored ->
          surface instanceof LeafSurface leaf && leaf.value() instanceof StvnValue.StvnTuple;
      case StvnSchema.SeqSchema ignored ->
          surface instanceof LeafSurface leaf
              && (leaf.value() instanceof StvnValue.StvnSeq || leaf.value() instanceof StvnValue.StvnSet);
      case StvnSchema.SetSchema ignored ->
          surface instanceof LeafSurface leaf
              && (leaf.value() instanceof StvnValue.StvnSeq || leaf.value() instanceof StvnValue.StvnSet);
      case StvnSchema.MapSchema ignored ->
          surface instanceof LeafSurface leaf && leaf.value() instanceof StvnValue.StvnMap;
      case StvnSchema.OptionSchema opt -> switch (surface) {
        case OptionNoneSurface ignored -> true;
        case OptionSomeSurface some ->
            couldSchemaCaptureSurfaceFresh(opt.valueSchema(), some.child(), symbolStyle, catalog);
        case EitherTagSurface ignored ->
            couldSchemaCaptureSurface(
                opt.valueSchema(), surface, symbolStyle, catalog, visitedSchemas, visitedAliases
            );
        case UnionTagSurface ignored ->
            couldSchemaCaptureSurface(
                opt.valueSchema(), surface, symbolStyle, catalog, visitedSchemas, visitedAliases
            );
        case LeafSurface ignored ->
            couldSchemaCaptureSurface(
                opt.valueSchema(), surface, symbolStyle, catalog, visitedSchemas, visitedAliases
            );
      };
      case StvnSchema.EitherSchema es -> switch (surface) {
        case EitherTagSurface eitherTag -> eitherTag.branch() == EitherBranch.LEFT
            ? couldSchemaCaptureSurfaceFresh(es.leftSchema(), eitherTag.child(), symbolStyle, catalog)
            : couldSchemaCaptureSurfaceFresh(es.rightSchema(), eitherTag.child(), symbolStyle, catalog);
        case OptionNoneSurface ignored ->
            couldSchemaCaptureSurface(
                es.rightSchema(), surface, symbolStyle, catalog, visitedSchemas, visitedAliases
            );
        case OptionSomeSurface ignored ->
            couldSchemaCaptureSurface(
                es.rightSchema(), surface, symbolStyle, catalog, visitedSchemas, visitedAliases
            );
        case UnionTagSurface ignored ->
            couldSchemaCaptureSurface(
                es.rightSchema(), surface, symbolStyle, catalog, visitedSchemas, visitedAliases
            );
        case LeafSurface ignored ->
            couldSchemaCaptureSurface(
                es.rightSchema(), surface, symbolStyle, catalog, visitedSchemas, visitedAliases
            );
      };
      case StvnSchema.UnionSchema us -> switch (surface) {
        case UnionTagSurface unionTag -> {
          BigInteger idx = unionTag.oneBasedBranchIndex();
          List<StvnSchema> branches = us.branchSchemas();
          if (idx.compareTo(BigInteger.ONE) < 0
              || idx.compareTo(BigInteger.valueOf(branches.size())) > 0) {
            yield false;
          }
          yield couldSchemaCaptureSurfaceFresh(
              branches.get(idx.intValueExact() - 1), unionTag.child(), symbolStyle, catalog
          );
        }
        case OptionNoneSurface ignored ->
            couldAnyUnionBranchCapture(us.branchSchemas(), surface, symbolStyle, catalog, visitedSchemas, visitedAliases);
        case OptionSomeSurface ignored ->
            couldAnyUnionBranchCapture(us.branchSchemas(), surface, symbolStyle, catalog, visitedSchemas, visitedAliases);
        case EitherTagSurface ignored ->
            couldAnyUnionBranchCapture(us.branchSchemas(), surface, symbolStyle, catalog, visitedSchemas, visitedAliases);
        case LeafSurface ignored ->
            couldAnyUnionBranchCapture(us.branchSchemas(), surface, symbolStyle, catalog, visitedSchemas, visitedAliases);
      };
      case StvnSchema.PoisonedSchema ignored -> true;
    };
  }

  private static boolean couldAnyUnionBranchCapture(
      List<StvnSchema> branches,
      SurfaceNode surface,
      PrinterOptions.SymbolStyle symbolStyle,
      StvnDefsCatalog catalog,
      Set<StvnSchema> visitedSchemas,
      Set<String> visitedAliases
  ) {
    for (StvnSchema b : branches) {
      if (couldSchemaCaptureSurface(b, surface, symbolStyle, catalog, visitedSchemas, visitedAliases)) {
        return true;
      }
    }
    return false;
  }

  private static @Nullable String emittedHeadToken(
      SurfaceNode surface,
      PrinterOptions.SymbolStyle symbolStyle
  ) {
    boolean longForm = symbolStyle == PrinterOptions.SymbolStyle.LONG_FORM;
    return switch (surface) {
      case OptionNoneSurface ignored -> longForm ? StvnVocabulary.VAL_NONE : StvnVocabulary.VAL_NONE_SHORT;
      case OptionSomeSurface ignored -> longForm ? StvnVocabulary.VAL_SOME : StvnVocabulary.VAL_SOME_SHORT;
      case EitherTagSurface e -> e.branch() == EitherBranch.RIGHT
          ? (longForm ? StvnVocabulary.VAL_RIGHT : StvnVocabulary.VAL_RIGHT_SHORT)
          : (longForm ? StvnVocabulary.VAL_LEFT : StvnVocabulary.VAL_LEFT_SHORT);
      case UnionTagSurface u -> StvnVocabulary.SIGIL_VALUE + u.oneBasedBranchIndex().toString();
      case LeafSurface leaf -> switch (leaf.value()) {
        case StvnValue.StvnEnum ev -> ev.variantTag();
        case StvnValue.StvnBoolean b -> b.value()
            ? (longForm ? StvnVocabulary.VAL_TRUE : StvnVocabulary.VAL_TRUE_SHORT)
            : (longForm ? StvnVocabulary.VAL_FALSE : StvnVocabulary.VAL_FALSE_SHORT);
        default -> null;
      };
    };
  }
}
