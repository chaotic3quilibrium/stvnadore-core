package org.stvnadore.core.printer.internal;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnSchema.FloatRepresentation;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.ir.StvnValue.EitherBranch;
import org.stvnadore.core.ir.StvnValue.Ieee754SpecialKind;
import org.stvnadore.core.ir.StvnValue.KeyValueEntry;
import org.stvnadore.core.lowering.StvnLiteralParser;
import org.stvnadore.core.lowering.StvnPayloadLowerer;
import org.stvnadore.core.printer.PrinterOptions;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;

/**
 * Dispatcher mapping pure-IR {@link StvnValue} trees to their corresponding {@link LayoutWriter}
 * serialization methods.
 *
 * @since 1.0.0
 */
@NullMarked
public final class PatternPrinterDispatcher {

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
    if (options.sumTypePolicy() == PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
        && isRealPoisonedSchema(val.schema())) {
      throw new IllegalStateException(
          "Cannot apply HAPPY_PATH_INFERRED without schema context: target schema is required to verify variant elision legality under Rules A, B, and I."
      );
    }

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
        boolean shouldEmitFenced = text.indexOf('\r') < 0
            && ((text.indexOf('\n') >= 0
                && (preserveIndent || text.equals(StvnPayloadLowerer.trimCommonIndent(text))))
                || (preserveIndent && text.indexOf('\t') >= 0));
        if (shouldEmitFenced) {
          String tag = StvnLiteralParser.chooseSafeFenceTag(text);
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

      case StvnValue.StvnSeq seq -> {
        layout.openGroup(StvnVocabulary.DELIM_OPEN_BRACKET);
        boolean first = true;
        for (StvnValue el : seq.elements()) {
          if (!first) {
            layout.appendSeparator();
          }
          first = false;
          dispatch(el, layout, options);
        }
        layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACKET);
      }

      case StvnValue.StvnSet set -> {
        layout.openGroup(StvnVocabulary.DELIM_OPEN_BRACKET);
        boolean first = true;
        for (StvnValue el : set.elements()) {
          if (!first) {
            layout.appendSeparator();
          }
          first = false;
          dispatch(el, layout, options);
        }
        layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACKET);
      }

      case StvnValue.StvnTuple tup -> {
        layout.openGroup(StvnVocabulary.DELIM_OPEN_PAREN);
        boolean first = true;
        for (StvnValue el : tup.elements()) {
          if (!first) {
            layout.appendSeparator();
          }
          first = false;
          dispatch(el, layout, options);
        }
        layout.closeGroup(StvnVocabulary.DELIM_CLOSE_PAREN);
      }

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
          dispatch(entry.key(), layout, options);
          layout.appendSeparator();
          dispatch(entry.value(), layout, options);
          layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACKET);
        }
        if (pretty && !entries.isEmpty()) {
          layout.outdent();
          layout.newline();
        }
        layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACE);
      }

      case StvnValue.StvnOption opt -> {
        StvnValue inner = opt.value().orElse(null);
        boolean isSome = (inner != null);
        boolean happyPath = options.sumTypePolicy() == PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
            && isSome
            && !isControlKeywordColliding(inner)
            && !(inner instanceof StvnValue.StvnOption);

        if (happyPath) {
          dispatch(inner, layout, options);
        } else if (isSome) {
          layout.openOptionSomeTag(options.symbolStyle());
          dispatch(inner, layout, options);
          layout.closeTag();
        } else {
          layout.writeOptionNone(options.symbolStyle());
        }
      }

      case StvnValue.StvnEither eith -> {
        boolean isRight = eith.branch() == EitherBranch.RIGHT;
        StvnValue inner = eith.value();
        boolean happyPath = options.sumTypePolicy() == PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
            && isRight
            && !isControlKeywordColliding(inner)
            && canElideEitherRightTag(eith.schema(), inner);

        if (happyPath) {
          dispatch(inner, layout, options);
        } else {
          layout.openEitherTag(isRight, options.symbolStyle());
          dispatch(inner, layout, options);
          layout.closeTag();
        }
      }

      case StvnValue.StvnUnion un -> {
        StvnValue inner = un.value();
        boolean happyPath = options.sumTypePolicy() == PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
            && !isControlKeywordColliding(inner)
            && canElideUnionBranchTag(un.schema(), un.oneBasedBranchIndex(), inner);

        if (happyPath) {
          dispatch(inner, layout, options);
        } else {
          layout.openTag(StvnVocabulary.SIGIL_VALUE + un.oneBasedBranchIndex().toString());
          dispatch(inner, layout, options);
          layout.closeTag();
        }
      }

      case StvnValue.StvnErrorValue err ->
          layout.writeSimpleString(err.diagnostic().message());
    }
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

  private static boolean isControlKeywordColliding(StvnValue value) {
    if (value instanceof StvnValue.StvnString strVal) {
      return isControlOrUnionTagToken(strVal.value());
    }
    if (value instanceof StvnValue.StvnEnum enumVal) {
      return isControlOrUnionTagToken(enumVal.variantTag());
    }
    return false;
  }

  private static boolean isControlOrUnionTagToken(String s) {
    if (StvnVocabulary.CONTROL_KEYWORDS.contains(s)) {
      return true;
    }
    if (s.length() >= 2 && s.charAt(0) == '#') {
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

  private static boolean canElideEitherRightTag(StvnSchema schema, StvnValue rightVal) {
    if (!(schema instanceof StvnSchema.EitherSchema es)) {
      return false;
    }
    return !couldSchemaAcceptValueLiteral(es.leftSchema(), rightVal);
  }

  private static boolean canElideUnionBranchTag(
      StvnSchema schema,
      BigInteger oneBasedBranchIndex,
      StvnValue branchVal
  ) {
    if (!(schema instanceof StvnSchema.UnionSchema us)) {
      return false;
    }
    int selectedZeroBased;
    try {
      selectedZeroBased = oneBasedBranchIndex.intValueExact() - 1;
    } catch (ArithmeticException e) {
      return false;
    }
    List<StvnSchema> branches = us.branchSchemas();
    for (int i = 0; i < branches.size(); i++) {
      if (i == selectedZeroBased) {
        continue;
      }
      if (couldSchemaAcceptValueLiteral(branches.get(i), branchVal)) {
        return false;
      }
    }
    return true;
  }

  private static boolean couldSchemaAcceptValueLiteral(StvnSchema candidate, StvnValue value) {
    return switch (candidate) {
      case StvnSchema.BooleanSchema ignored -> value instanceof StvnValue.StvnBoolean;
      case StvnSchema.IntSchema ignored ->
          value instanceof StvnValue.StvnInt || value instanceof StvnValue.StvnTimeEpoch;
      case StvnSchema.TimeEpochSchema ignored ->
          value instanceof StvnValue.StvnInt || value instanceof StvnValue.StvnTimeEpoch;
      case StvnSchema.FloatSchema ignored -> value instanceof StvnValue.StvnFloat;
      case StvnSchema.StringSchema ignored ->
          value instanceof StvnValue.StvnString || value instanceof StvnValue.StvnDateTime;
      case StvnSchema.DateTimeSchema ignored ->
          value instanceof StvnValue.StvnDateTime || value instanceof StvnValue.StvnString;
      case StvnSchema.EnumSchema en ->
          value instanceof StvnValue.StvnEnum ev && en.rootVariants().contains(ev.variantTag());
      case StvnSchema.EnumSubsetSchema sub ->
          value instanceof StvnValue.StvnEnum ev && sub.activeVariants().contains(ev.variantTag());
      case StvnSchema.TupleSchema ignored -> value instanceof StvnValue.StvnTuple;
      case StvnSchema.SeqSchema ignored ->
          value instanceof StvnValue.StvnSeq || value instanceof StvnValue.StvnSet;
      case StvnSchema.SetSchema ignored ->
          value instanceof StvnValue.StvnSeq || value instanceof StvnValue.StvnSet;
      case StvnSchema.MapSchema ignored -> value instanceof StvnValue.StvnMap;
      case StvnSchema.OptionSchema opt ->
          (value instanceof StvnValue.StvnOption)
              || couldSchemaAcceptValueLiteral(opt.valueSchema(), value);
      case StvnSchema.EitherSchema es ->
          (value instanceof StvnValue.StvnEither)
              || couldSchemaAcceptValueLiteral(es.leftSchema(), value)
              || couldSchemaAcceptValueLiteral(es.rightSchema(), value);
      case StvnSchema.UnionSchema us -> {
        if (value instanceof StvnValue.StvnUnion) {
          yield true;
        }
        for (StvnSchema b : us.branchSchemas()) {
          if (couldSchemaAcceptValueLiteral(b, value)) {
            yield true;
          }
        }
        yield false;
      }
      case StvnSchema.PoisonedSchema ignored -> true;
    };
  }
}
