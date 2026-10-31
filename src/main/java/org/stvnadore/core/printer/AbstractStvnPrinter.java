package org.stvnadore.core.printer;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.io.CanonicalStvnWriter;
import org.stvnadore.core.ir.StvnDefsCatalog;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnDocumentMeta;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnSchema.BoundKind;
import org.stvnadore.core.ir.StvnSchema.FloatRepresentation;
import org.stvnadore.core.ir.StvnSchema.TraitProfile;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.lowering.StvnPayloadLowerer;
import org.stvnadore.core.lowering.StvnReachabilityAndCasVerifier;
import org.stvnadore.core.printer.internal.LayoutWriter;
import org.stvnadore.core.printer.internal.PatternPrinterDispatcher;
import org.stvnadore.core.printer.internal.PrettyLayoutWriter;
import org.stvnadore.core.validation.DiagnosticBag;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Abstract base class for pure-IR STVN printers, orchestrating formatting and serialization loops.
 * <p>
 * This class provides standard implementation logic for printing the {@code :meta}, {@code :defs},
 * {@code :type}, and {@code :body} sections of a pure-IR {@link StvnDocument} or {@link StvnValue}.
 * Metadata facets serialize strictly in the canonical 5-tier decimal sequence ({@code 1.1.1}
 * through {@code 5.0}, § 6.1) with zero {@code W003} tautologies. Both {@code #DEFS} /
 * {@code #DEFS_INCLUDE} schemas and {@code #BODY} / {@code #BODY_INCLUDE} payload documents
 * serialize losslessly.
 * </p>
 *
 * @since 1.0.0
 */
@NullMarked
public abstract class AbstractStvnPrinter implements StvnTextPrinter {

  private static final BigInteger THIRTY_TWO = BigInteger.valueOf(32L);

  /**
   * The formatting options applied to this printer.
   */
  protected final PrinterOptions options;

  /**
   * Constructs an {@code AbstractStvnPrinter} with the specified printer options.
   *
   * @param options the formatting options to use
   */
  protected AbstractStvnPrinter(PrinterOptions options) {
    this.options = Objects.requireNonNull(options, "options must not be null");
  }

  /**
   * Constructs an {@code AbstractStvnPrinter} using default printer options.
   */
  protected AbstractStvnPrinter() {
    this(new PrinterOptions());
  }

  /**
   * Factory method to create a concrete {@link LayoutWriter} instance.
   *
   * @param target the target writer stream
   * @return the resolved layout writer for the target stream
   */
  protected abstract LayoutWriter createLayoutWriter(Writer target);

  @Override
  public void print(StvnValue value, Writer target) throws IOException {
    print(value, null, target);
  }

  /**
   * Serializes the given pure-IR {@link StvnValue} with an optional explicit {@link StvnSchema} context.
   *
   * @param value          the pure-IR STVN value tree to serialize
   * @param explicitSchema optional explicit target schema, or {@code null}
   * @param target         the target writer destination
   * @throws IOException          if an I/O error occurs during serialization
   * @throws NullPointerException if {@code value} or {@code target} is {@code null}
   * @since 2.0.0
   */
  public void print(StvnValue value, @Nullable StvnSchema explicitSchema, Writer target) throws IOException {
    Objects.requireNonNull(value, "value must not be null");
    Objects.requireNonNull(target, "target must not be null");

    if (options.sumTypePolicy() == PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED) {
      boolean hasContext = (explicitSchema != null && !PatternPrinterDispatcher.isRealPoisonedSchema(explicitSchema))
          || !PatternPrinterDispatcher.isRealPoisonedSchema(value.schema());
      if (!hasContext) {
        throw new IllegalStateException(
            "Cannot apply HAPPY_PATH_INFERRED without schema context: target schema is required to verify variant elision legality under Rules A, B, and I."
        );
      }
    }

    var layout = createLayoutWriter(target);
    if (options.coverage() == PrinterOptions.Coverage.BODY_ONLY) {
      PatternPrinterDispatcher.dispatch(value, layout, options);
      layout.flush();
      return;
    }

    StvnDocument synthesized = StvnReachabilityAndCasVerifier.synthesizeDocumentFromValue(value);
    if (explicitSchema != null && explicitSchema != value.schema()) {
      StvnDocument withExplicitRoot = new StvnDocument(
          Optional.empty(),
          mergeExplicitRootIntoCatalog(synthesized.defs(), explicitSchema),
          Optional.of(explicitSchema),
          Optional.of(value),
          true,
          false
      );
      synthesized = StvnReachabilityAndCasVerifier.verifyAndPruneCanonical(
          withExplicitRoot,
          false,
          new DiagnosticBag(16)
      );
    }

    writeFullDocumentSections(synthesized, layout);
    layout.flush();
  }

  /**
   * Serializes the given pure-IR {@link StvnValue} with an explicit {@link StvnSchema} context to a string.
   *
   * @param value          the pure-IR STVN value tree to serialize
   * @param explicitSchema the explicit target schema
   * @return the serialized STVN string
   * @throws NullPointerException if {@code value} or {@code explicitSchema} is {@code null}
   * @since 2.0.0
   */
  public String printToString(StvnValue value, StvnSchema explicitSchema) {
    Objects.requireNonNull(value, "value must not be null");
    Objects.requireNonNull(explicitSchema, "explicitSchema must not be null");
    var sw = new StringWriter();
    try {
      print(value, explicitSchema, sw);
    } catch (IOException e) {
      throw new RuntimeException("Unreachable IOException in StringWriter", e);
    }
    return sw.toString();
  }

  /**
   * Serializes a pure-IR {@link StvnDocument} to the destination writer.
   * <p>
   * Supports both {@code #DEFS} / {@code #DEFS_INCLUDE} schemas (where {@code payload()} is empty)
   * and {@code #BODY} / {@code #BODY_INCLUDE} payload documents without returning an empty string
   * on definitions-only schemas.
   * </p>
   *
   * @param document the pure-IR document to serialize
   * @param target   the target writer destination
   * @throws IOException          if an I/O error occurs during serialization
   * @throws NullPointerException if {@code document} or {@code target} is {@code null}
   * @since 2.0.0
   */
  public void print(StvnDocument document, Writer target) throws IOException {
    Objects.requireNonNull(document, "document must not be null");
    Objects.requireNonNull(target, "target must not be null");

    var layout = createLayoutWriter(target);
    if (options.coverage() == PrinterOptions.Coverage.BODY_ONLY && document.payload().isPresent()) {
      PatternPrinterDispatcher.dispatch(document.payload().get(), layout, options);
      layout.flush();
      return;
    }

    StvnDocument canonicalDoc = StvnReachabilityAndCasVerifier.verifyAndPruneCanonical(
        document,
        false,
        new DiagnosticBag(16)
    );
    writeFullDocumentSections(canonicalDoc, layout);
    layout.flush();
  }

  /**
   * Serializes a pure-IR {@link StvnDocument} and returns its formatted string.
   *
   * @param document the pure-IR document to serialize
   * @return the formatted STVN string
   * @throws NullPointerException if {@code document} is {@code null}
   * @since 2.0.0
   */
  public String printToString(StvnDocument document) {
    Objects.requireNonNull(document, "document must not be null");
    var sw = new StringWriter();
    try {
      print(document, sw);
    } catch (IOException e) {
      throw new RuntimeException("Unreachable IOException in StringWriter", e);
    }
    return sw.toString();
  }

  private void writeFullDocumentSections(StvnDocument doc, LayoutWriter layout) throws IOException {
    boolean pretty = layout instanceof PrettyLayoutWriter;
    layout.openGroup(StvnVocabulary.DELIM_OPEN_BRACE);
    if (pretty) {
      layout.newline();
      layout.indent();
    }

    boolean wroteSection = false;

    if (doc.meta().isPresent()) {
      wroteSection = true;
      StvnDocumentMeta meta = doc.meta().get();
      layout.writeLiteral(StvnVocabulary.KEYWORD_META);
      layout.appendSeparator();
      layout.openGroup(StvnVocabulary.DELIM_OPEN_BRACE);
      boolean firstMeta = true;
      if (meta.name().isPresent()) {
        firstMeta = false;
        layout.writeLiteral(StvnVocabulary.FACET_KW_META_NAME);
        layout.appendSeparator();
        layout.writeSimpleString(meta.name().get());
      }
      if (meta.domain().isPresent()) {
        if (!firstMeta) {
          layout.appendSeparator();
        }
        firstMeta = false;
        layout.writeLiteral(StvnVocabulary.FACET_KW_META_DOMAIN);
        layout.appendSeparator();
        layout.writeSimpleString(meta.domain().get());
      }
      if (meta.kind().isPresent()) {
        if (!firstMeta) {
          layout.appendSeparator();
        }
        layout.writeLiteral(StvnVocabulary.FACET_KW_META_KIND);
        layout.appendSeparator();
        layout.writeLiteral(meta.kind().get().keyword());
      }
      layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACE);
    }

    StvnDefsCatalog defs = doc.defs();
    if (!defs.nominalTypes().isEmpty() || !defs.constants().isEmpty()) {
      if (wroteSection) {
        if (pretty) {
          layout.newline();
        } else {
          layout.appendSeparator();
        }
      }
      wroteSection = true;

      layout.writeLiteral(StvnVocabulary.KEYWORD_DEFS);
      layout.appendSeparator();
      layout.openGroup(StvnVocabulary.DELIM_OPEN_BRACE);

      boolean firstDef = true;
      for (var constEntry : defs.constants().entrySet()) {
        if (!firstDef) {
          if (pretty) {
            layout.newline();
          } else {
            layout.appendSeparator();
          }
        } else if (pretty) {
          layout.newline();
          layout.indent();
        }
        firstDef = false;

        layout.writeLiteral(constEntry.getKey());
        layout.appendSeparator();
        writeSchemaReferenceOrInline(constEntry.getValue().schema(), layout);
        layout.appendSeparator();
        PatternPrinterDispatcher.dispatch(constEntry.getValue().value(), layout, options);
      }

      for (var typeEntry : defs.nominalTypes().entrySet()) {
        if (!firstDef) {
          if (pretty) {
            layout.newline();
          } else {
            layout.appendSeparator();
          }
        } else if (pretty) {
          layout.newline();
          layout.indent();
        }
        firstDef = false;

        layout.writeLiteral(typeEntry.getKey());
        writeSchemaDefinitionWithOptionalFacets(typeEntry.getValue(), layout);
      }

      if (pretty && !firstDef) {
        layout.outdent();
        layout.newline();
      }
      layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACE);
    }

    Optional<StvnSchema> effectiveRootType = doc.rootType().isPresent()
        ? doc.rootType()
        : doc.payload().map(StvnValue::schema);

    if (effectiveRootType.isPresent()) {
      if (wroteSection) {
        if (pretty) {
          layout.newline();
        } else {
          layout.appendSeparator();
        }
      }
      wroteSection = true;

      layout.writeLiteral(StvnVocabulary.KEYWORD_TYPE);
      layout.appendSeparator();
      writeSchemaReferenceOrInline(effectiveRootType.get(), layout);
    }

    if (doc.payload().isPresent()) {
      if (wroteSection) {
        if (pretty) {
          layout.newline();
        } else {
          layout.appendSeparator();
        }
      }
      layout.writeLiteral(StvnVocabulary.KEYWORD_BODY);
      layout.appendSeparator();
      PatternPrinterDispatcher.dispatch(doc.payload().get(), layout, options);
    }

    if (pretty) {
      layout.outdent();
      layout.newline();
    }
    layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACE);
  }

  private void writeSchemaDefinitionWithOptionalFacets(StvnSchema schema, LayoutWriter layout) throws IOException {
    List<FacetEmitter> facets = collectFiveTierFacets(schema);
    if (!facets.isEmpty()) {
      layout.appendSeparator();
      emitFacetBlock(facets, layout);
    }
    layout.appendSeparator();
    writeSchemaConstructorOnly(schema, layout);
  }

  private void writeSchemaReferenceOrInline(StvnSchema schema, LayoutWriter layout) throws IOException {
    if (schema instanceof StvnSchema.PoisonedSchema p) {
      if (StvnPayloadLowerer.RECURSIVE_BACK_EDGE_SENTINEL.equals(p.primaryDiagnosticCode())
          && p.aliasName().isPresent()) {
        layout.writeLiteral(p.aliasName().get());
        return;
      }
      if (p.bestEffortFallback().isPresent()) {
        writeSchemaReferenceOrInline(p.bestEffortFallback().get(), layout);
        return;
      }
      layout.writeLiteral(StvnVocabulary.TYPE_BOOLEAN);
      return;
    }

    if (schema.aliasName().isPresent()) {
      layout.writeLiteral(schema.aliasName().get());
      return;
    }

    List<FacetEmitter> facets = collectFiveTierFacets(schema);
    if (!facets.isEmpty()) {
      emitFacetBlock(facets, layout);
      layout.appendSeparator();
    }
    writeSchemaConstructorOnly(schema, layout);
  }

  private void writeSchemaConstructorOnly(StvnSchema schema, LayoutWriter layout) throws IOException {
    switch (schema) {
      case StvnSchema.BooleanSchema ignored -> layout.writeLiteral(StvnVocabulary.TYPE_BOOLEAN);
      case StvnSchema.IntSchema ignored -> layout.writeLiteral(StvnVocabulary.TYPE_INT);
      case StvnSchema.FloatSchema ignored -> layout.writeLiteral(StvnVocabulary.TYPE_FLOAT);
      case StvnSchema.StringSchema ignored -> layout.writeLiteral(StvnVocabulary.TYPE_STRING);
      case StvnSchema.TimeEpochSchema ignored -> layout.writeLiteral(StvnVocabulary.TYPE_TIME_EPOCH);
      case StvnSchema.DateTimeSchema ignored -> layout.writeLiteral(StvnVocabulary.TYPE_DATE_TIME);
      case StvnSchema.EnumSchema en -> {
        layout.writeLiteral(StvnVocabulary.TYPE_ENUM);
        layout.openGroup(StvnVocabulary.DELIM_OPEN_BRACKET);
        boolean first = true;
        for (String v : en.rootVariants()) {
          if (!first) {
            layout.appendSeparator();
          }
          first = false;
          layout.writeLiteral(v);
        }
        layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACKET);
      }
      case StvnSchema.EnumSubsetSchema sub -> {
        String parentAlias = sub.parentEnum().aliasName()
            .or(() -> sub.rootEnum().aliasName())
            .orElseThrow(() -> new IllegalStateException("EnumSubsetSchema parent lacks nominal alias"));
        layout.writeLiteral(parentAlias);
      }
      case StvnSchema.TupleSchema tup -> {
        layout.writeLiteral(StvnVocabulary.TYPE_TUPLE);
        layout.openGroup(StvnVocabulary.DELIM_OPEN_PAREN);
        boolean first = true;
        for (StvnSchema field : tup.fieldSchemas()) {
          if (!first) {
            layout.appendSeparator();
          }
          first = false;
          writeSchemaReferenceOrInline(field, layout);
        }
        layout.closeGroup(StvnVocabulary.DELIM_CLOSE_PAREN);
      }
      case StvnSchema.OptionSchema opt -> {
        layout.writeLiteral(StvnVocabulary.TYPE_OPTION);
        layout.openGroup(StvnVocabulary.DELIM_OPEN_PAREN);
        writeSchemaReferenceOrInline(opt.valueSchema(), layout);
        layout.closeGroup(StvnVocabulary.DELIM_CLOSE_PAREN);
      }
      case StvnSchema.EitherSchema eith -> {
        layout.writeLiteral(StvnVocabulary.TYPE_EITHER);
        layout.openGroup(StvnVocabulary.DELIM_OPEN_PAREN);
        writeSchemaReferenceOrInline(eith.leftSchema(), layout);
        layout.appendSeparator();
        writeSchemaReferenceOrInline(eith.rightSchema(), layout);
        layout.closeGroup(StvnVocabulary.DELIM_CLOSE_PAREN);
      }
      case StvnSchema.UnionSchema un -> {
        layout.writeLiteral(StvnVocabulary.TYPE_UNION);
        layout.openGroup(StvnVocabulary.DELIM_OPEN_PAREN);
        boolean first = true;
        for (StvnSchema branch : un.branchSchemas()) {
          if (!first) {
            layout.appendSeparator();
          }
          first = false;
          writeSchemaReferenceOrInline(branch, layout);
        }
        layout.closeGroup(StvnVocabulary.DELIM_CLOSE_PAREN);
      }
      case StvnSchema.SeqSchema seq -> {
        layout.writeLiteral(StvnVocabulary.TYPE_SEQ);
        layout.openGroup(StvnVocabulary.DELIM_OPEN_PAREN);
        writeSchemaReferenceOrInline(seq.elementSchema(), layout);
        layout.closeGroup(StvnVocabulary.DELIM_CLOSE_PAREN);
      }
      case StvnSchema.SetSchema set -> {
        layout.writeLiteral(StvnVocabulary.TYPE_SET);
        layout.openGroup(StvnVocabulary.DELIM_OPEN_PAREN);
        writeSchemaReferenceOrInline(set.elementSchema(), layout);
        layout.closeGroup(StvnVocabulary.DELIM_CLOSE_PAREN);
      }
      case StvnSchema.MapSchema map -> {
        layout.writeLiteral(StvnVocabulary.TYPE_MAP);
        layout.openGroup(StvnVocabulary.DELIM_OPEN_PAREN);
        writeSchemaReferenceOrInline(map.keySchema(), layout);
        layout.appendSeparator();
        writeSchemaReferenceOrInline(map.valueSchema(), layout);
        layout.closeGroup(StvnVocabulary.DELIM_CLOSE_PAREN);
      }
      case StvnSchema.PoisonedSchema p -> {
        if (p.bestEffortFallback().isPresent()) {
          writeSchemaConstructorOnly(p.bestEffortFallback().get(), layout);
        } else {
          layout.writeLiteral(StvnVocabulary.TYPE_BOOLEAN);
        }
      }
    }
  }

  private static void emitFacetBlock(List<FacetEmitter> facets, LayoutWriter layout) throws IOException {
    boolean pretty = layout instanceof PrettyLayoutWriter;
    boolean inline = (facets.size() <= 1) || !pretty;

    layout.openGroup(StvnVocabulary.DELIM_OPEN_BRACE);
    if (!inline) {
      layout.indent();
    }

    boolean first = true;
    for (FacetEmitter emitter : facets) {
      if (inline) {
        if (!first) {
          layout.appendSeparator();
        }
      } else {
        layout.newline();
      }
      first = false;
      emitter.emit(layout);
    }

    if (!inline) {
      layout.outdent();
      layout.newline();
    }
    layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACE);
  }

  private List<FacetEmitter> collectFiveTierFacets(StvnSchema schema) {
    List<FacetEmitter> facets = new ArrayList<>();

    // Tier 1: Definition (§ 6.1 1.1.1 .. 1.5.2)
    switch (schema) {
      case StvnSchema.IntSchema i -> {
        if (i.unsigned()) {
          facets.add(l -> l.writeLiteral(StvnVocabulary.FACET_KW_UNSIGNED));
        }
        if (!i.bitWidth().equals(StvnVocabulary.DEFAULT_INT_BIT_WIDTH)) {
          facets.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_SIZE);
            l.appendSeparator();
            l.writeLiteral(i.bitWidth().toString());
          });
        }
      }
      case StvnSchema.FloatSchema f -> {
        if (f.representation() == FloatRepresentation.EXACT_DECIMAL) {
          facets.add(l -> l.writeLiteral(StvnVocabulary.FACET_KW_EXACT));
        }
        if (f.representation() == FloatRepresentation.IEEE_32) {
          facets.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_SIZE);
            l.appendSeparator();
            l.writeLiteral(THIRTY_TWO.toString());
          });
        }
        if (f.representation() == FloatRepresentation.EXACT_DECIMAL
            && !f.exactMaxDigits().equals(StvnVocabulary.DEFAULT_MAX_SIZE)) {
          facets.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MAX_SIZE);
            l.appendSeparator();
            l.writeLiteral(f.exactMaxDigits().toString());
          });
        }
      }
      case StvnSchema.MapSchema map -> {
        if (map.invertible()) {
          facets.add(l -> l.writeLiteral(StvnVocabulary.FACET_KW_INVERTIBLE));
        }
        if (map.minSize().signum() > 0) {
          facets.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MIN_SIZE);
            l.appendSeparator();
            l.writeLiteral(map.minSize().toString());
          });
        }
        if (!map.maxSize().equals(StvnVocabulary.DEFAULT_MAX_SIZE)) {
          facets.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MAX_SIZE);
            l.appendSeparator();
            l.writeLiteral(map.maxSize().toString());
          });
        }
      }
      case StvnSchema.TimeEpochSchema te ->
          facets.add(l -> l.writeLiteral(CanonicalStvnWriter.temporalScaleFacetKeyword(te.scale())));
      case StvnSchema.DateTimeSchema dt ->
          facets.add(l -> l.writeLiteral(CanonicalStvnWriter.dateTimeModeFacetKeyword(dt.mode())));
      case StvnSchema.StringSchema s -> {
        if (s.minSize().signum() > 0) {
          facets.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MIN_SIZE);
            l.appendSeparator();
            l.writeLiteral(s.minSize().toString());
          });
        }
        if (!s.maxSize().equals(StvnVocabulary.DEFAULT_MAX_SIZE)) {
          facets.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MAX_SIZE);
            l.appendSeparator();
            l.writeLiteral(s.maxSize().toString());
          });
        }
      }
      case StvnSchema.SeqSchema seq -> {
        if (seq.minSize().signum() > 0) {
          facets.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MIN_SIZE);
            l.appendSeparator();
            l.writeLiteral(seq.minSize().toString());
          });
        }
        if (!seq.maxSize().equals(StvnVocabulary.DEFAULT_MAX_SIZE)) {
          facets.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MAX_SIZE);
            l.appendSeparator();
            l.writeLiteral(seq.maxSize().toString());
          });
        }
      }
      case StvnSchema.SetSchema set -> {
        if (set.minSize().signum() > 0) {
          facets.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MIN_SIZE);
            l.appendSeparator();
            l.writeLiteral(set.minSize().toString());
          });
        }
        if (!set.maxSize().equals(StvnVocabulary.DEFAULT_MAX_SIZE)) {
          facets.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MAX_SIZE);
            l.appendSeparator();
            l.writeLiteral(set.maxSize().toString());
          });
        }
      }
      default -> {
        // No Tier-1 facets
      }
    }

    // Tier 2: Trait (§ 6.1 2.1 .. 2.2)
    TraitProfile traits = schema.traits();
    if (traits.explicitEquatableOverride().isPresent()) {
      boolean eq = traits.explicitEquatableOverride().get();
      facets.add(l -> {
        l.writeLiteral(StvnVocabulary.FACET_KW_EQUATABLE);
        l.appendSeparator();
        l.writeBoolean(eq, options.symbolStyle());
      });
    }
    if (traits.explicitComparableOverride().isPresent()) {
      boolean cmp = traits.explicitComparableOverride().get();
      facets.add(l -> {
        l.writeLiteral(StvnVocabulary.FACET_KW_COMPARABLE);
        l.appendSeparator();
        l.writeBoolean(cmp, options.symbolStyle());
      });
    }

    // Tier 3: Domain Bounds (§ 6.1 3.1 .. 3.4)
    switch (schema) {
      case StvnSchema.IntSchema i -> {
        if (i.explicitMinIncl().isPresent()) {
          BigInteger min = i.explicitMinIncl().get();
          facets.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MIN_INCL);
            l.appendSeparator();
            l.writeLiteral(min.toString());
          });
        }
        if (i.explicitMaxExcl().isPresent()) {
          BigInteger max = i.explicitMaxExcl().get();
          facets.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MAX_EXCL);
            l.appendSeparator();
            l.writeLiteral(max.toString());
          });
        }
      }
      case StvnSchema.FloatSchema f -> {
        if (f.lowerBound().isPresent()) {
          var lb = f.lowerBound().get();
          String kw = lb.kind() == BoundKind.INCLUSIVE
              ? StvnVocabulary.FACET_KW_MIN_INCL
              : StvnVocabulary.FACET_KW_MIN_EXCL;
          facets.add(l -> {
            l.writeLiteral(kw);
            l.appendSeparator();
            l.writeFloat(lb.value(), f.representation());
          });
        }
        if (f.upperBound().isPresent()) {
          var ub = f.upperBound().get();
          String kw = ub.kind() == BoundKind.INCLUSIVE
              ? StvnVocabulary.FACET_KW_MAX_INCL
              : StvnVocabulary.FACET_KW_MAX_EXCL;
          facets.add(l -> {
            l.writeLiteral(kw);
            l.appendSeparator();
            l.writeFloat(ub.value(), f.representation());
          });
        }
      }
      case StvnSchema.StringSchema s -> {
        if (s.lowerBound().isPresent()) {
          var lb = s.lowerBound().get();
          String kw = lb.kind() == BoundKind.INCLUSIVE
              ? StvnVocabulary.FACET_KW_MIN_INCL
              : StvnVocabulary.FACET_KW_MIN_EXCL;
          facets.add(l -> {
            l.writeLiteral(kw);
            l.appendSeparator();
            l.writeSimpleString(lb.value());
          });
        }
        if (s.upperBound().isPresent()) {
          var ub = s.upperBound().get();
          String kw = ub.kind() == BoundKind.INCLUSIVE
              ? StvnVocabulary.FACET_KW_MAX_INCL
              : StvnVocabulary.FACET_KW_MAX_EXCL;
          facets.add(l -> {
            l.writeLiteral(kw);
            l.appendSeparator();
            l.writeSimpleString(ub.value());
          });
        }
      }
      case StvnSchema.TimeEpochSchema te -> {
        if (te.explicitMinIncl().isPresent()) {
          BigInteger min = te.explicitMinIncl().get();
          facets.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MIN_INCL);
            l.appendSeparator();
            l.writeLiteral(min.toString());
          });
        }
        if (te.explicitMaxExcl().isPresent()) {
          BigInteger max = te.explicitMaxExcl().get();
          facets.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MAX_EXCL);
            l.appendSeparator();
            l.writeLiteral(max.toString());
          });
        }
      }
      case StvnSchema.DateTimeSchema dt -> {
        if (dt.explicitMinInclIso().isPresent()) {
          String minIso = dt.explicitMinInclIso().get();
          facets.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MIN_INCL);
            l.appendSeparator();
            l.writeSimpleString(minIso);
          });
        }
        if (dt.explicitMaxExclIso().isPresent()) {
          String maxIso = dt.explicitMaxExclIso().get();
          facets.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MAX_EXCL);
            l.appendSeparator();
            l.writeSimpleString(maxIso);
          });
        }
      }
      default -> {
        // No Tier-3 facets
      }
    }

    // Tier 4: Constraint / Lexical (§ 6.1 4.1 .. 4.4)
    switch (schema) {
      case StvnSchema.EnumSubsetSchema sub -> {
        String filterKw = sub.inclusiveFilter()
            ? StvnVocabulary.FACET_KW_FILTER_INCL
            : StvnVocabulary.FACET_KW_FILTER_EXCL;
        List<String> filterList = sub.declaredFilterList();
        facets.add(l -> {
          l.writeLiteral(filterKw);
          l.appendSeparator();
          l.openGroup(StvnVocabulary.DELIM_OPEN_BRACKET);
          boolean first = true;
          for (String v : filterList) {
            if (!first) {
              l.appendSeparator();
            }
            first = false;
            l.writeLiteral(v);
          }
          l.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACKET);
        });
      }
      case StvnSchema.StringSchema s -> {
        if (s.preserveIndent()) {
          facets.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_PRESERVE_INDENT);
            l.appendSeparator();
            l.writeBoolean(true, options.symbolStyle());
          });
        }
        if (s.rawRegexPattern().isPresent()) {
          String rx = s.rawRegexPattern().get();
          facets.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_REGEX);
            l.appendSeparator();
            l.writeSimpleString(rx);
          });
        }
      }
      default -> {
        // No Tier-4 facets
      }
    }

    return facets;
  }

  private static StvnDefsCatalog mergeExplicitRootIntoCatalog(StvnDefsCatalog base, StvnSchema explicitSchema) {
    if (explicitSchema.aliasName().isEmpty()) {
      return base;
    }
    String alias = explicitSchema.aliasName().get();
    if (base.nominalTypes().containsKey(alias)) {
      return base;
    }
    Map<String, StvnSchema> merged = new LinkedHashMap<>(base.nominalTypes());
    merged.put(alias, explicitSchema);
    return new StvnDefsCatalog(merged, base.constants());
  }

  @FunctionalInterface
  private interface FacetEmitter {
    void emit(LayoutWriter layout) throws IOException;
  }
}
