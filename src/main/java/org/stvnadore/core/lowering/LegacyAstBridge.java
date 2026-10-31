package org.stvnadore.core.lowering;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ast.StvnIrVisitor;
import org.stvnadore.core.io.CanonicalLayoutWriter;
import org.stvnadore.core.ir.StvnDefsCatalog;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnDocumentKind;
import org.stvnadore.core.ir.StvnDocumentMeta;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnSchema.BoundKind;
import org.stvnadore.core.ir.StvnSchema.ContinuousBound;
import org.stvnadore.core.ir.StvnSchema.DateTimeMode;
import org.stvnadore.core.ir.StvnSchema.FloatRepresentation;
import org.stvnadore.core.ir.StvnSchema.TemporalScale;
import org.stvnadore.core.ir.StvnSchema.TraitProfile;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.ir.StvnValue.EitherBranch;
import org.stvnadore.core.ir.StvnValue.Ieee754SpecialKind;
import org.stvnadore.core.ir.StvnValue.KeyValueEntry;
import org.stvnadore.core.parser.StvnErrorListener;
import org.stvnadore.core.parser.StvnLexer;
import org.stvnadore.core.parser.StvnParser;
import org.stvnadore.core.printer.PrinterOptions;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.core.validation.StvnTypeResolver;
import org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Transitional Stage 2 adapter and pure-IR canonical serializer bridging the STVN 2.0.0
 * {@code org.stvnadore.core.ir} algebra with legacy Stage 3 printers, binary codecs, and POJO mappers
 * prior to Stage 3 excision of {@code org.stvnadore.core.ast}.
 *
 * @since 2.0.0
 */
@NullMarked
public final class LegacyAstBridge {

  private static final BigInteger SIZE_32 = BigInteger.valueOf(32L);

  private LegacyAstBridge() {
    // Non-instantiable bridge utility
  }

  /**
   * Serializes a pure-IR {@link StvnDocument} into its deterministic canonical STVN text preimage.
   *
   * @param document the lowered IR document to serialize
   * @param target   the character stream destination
   * @throws IOException if an I/O error occurs while writing
   */
  public static void writeCanonical(StvnDocument document, Writer target) throws IOException {
    Objects.requireNonNull(document, "document must not be null");
    Objects.requireNonNull(target, "target must not be null");
    writeDocumentInternal(document, target, false);
  }

  /**
   * Serializes a pure-IR {@link StvnValue} into its self-contained canonical STVN text preimage.
   *
   * @param value  the lowered IR value to serialize
   * @param target the character stream destination
   * @throws IOException if an I/O error occurs while writing
   */
  public static void writeCanonical(StvnValue value, Writer target) throws IOException {
    Objects.requireNonNull(value, "value must not be null");
    Objects.requireNonNull(target, "target must not be null");
    StvnDocument synthesized = StvnReachabilityAndCasVerifier.synthesizeDocumentFromValue(value);
    writeDocumentInternal(synthesized, target, false);
  }

  /**
   * Formats a pure-IR {@link StvnDocument} into its canonical STVN text string.
   *
   * @param document the lowered IR document to format
   * @return the canonical STVN string representation
   */
  public static String toCanonicalStvnText(StvnDocument document) {
    Objects.requireNonNull(document, "document must not be null");
    StringWriter sw = new StringWriter();
    try {
      writeCanonical(document, sw);
    } catch (IOException e) {
      throw new IllegalStateException("Unexpected IOException in StringWriter", e);
    }
    return sw.toString();
  }

  /**
   * Formats a pure-IR {@link StvnValue} into its canonical STVN text string.
   *
   * @param value the lowered IR value to format
   * @return the canonical STVN string representation
   */
  public static String toCanonicalStvnText(StvnValue value) {
    Objects.requireNonNull(value, "value must not be null");
    StringWriter sw = new StringWriter();
    try {
      writeCanonical(value, sw);
    } catch (IOException e) {
      throw new IllegalStateException("Unexpected IOException in StringWriter", e);
    }
    return sw.toString();
  }

  /**
   * Converts a pure-IR {@link StvnDocumentMeta} record to its legacy Stage 3 AST counterpart.
   *
   * @param meta the pure-IR metadata record
   * @return the legacy AST metadata record
   */
  public static org.stvnadore.core.ast.StvnDocumentMeta toLegacyMeta(StvnDocumentMeta meta) {
    Objects.requireNonNull(meta, "meta must not be null");
    Optional<org.stvnadore.core.ast.StvnDocumentKind> legacyKind = meta.kind()
        .map(k -> org.stvnadore.core.ast.StvnDocumentKind.valueOf(k.name()));
    return new org.stvnadore.core.ast.StvnDocumentMeta(meta.name(), meta.domain(), legacyKind);
  }

  /**
   * Converts a legacy Stage 3 AST {@link org.stvnadore.core.ast.StvnDocumentMeta} record to pure IR.
   *
   * @param legacyMeta the legacy AST metadata record
   * @return the pure-IR metadata record
   */
  public static StvnDocumentMeta fromLegacyMeta(org.stvnadore.core.ast.StvnDocumentMeta legacyMeta) {
    Objects.requireNonNull(legacyMeta, "legacyMeta must not be null");
    Optional<StvnDocumentKind> irKind = legacyMeta.kind()
        .map(k -> StvnDocumentKind.valueOf(k.name()));
    return new StvnDocumentMeta(legacyMeta.name(), legacyMeta.domain(), irKind);
  }

  /**
   * Bridges a pure-IR {@link StvnDocument} into a legacy {@link org.stvnadore.core.ast.StvnDocument}
   * backed by a freshly parsed, stateless ANTLR context for Stage 3 printers and binary encoders.
   *
   * @param document the pure-IR document
   * @return the bridged legacy AST document
   */
  public static org.stvnadore.core.ast.StvnDocument toLegacyDocument(StvnDocument document) {
    Objects.requireNonNull(document, "document must not be null");
    StringWriter sw = new StringWriter();
    try {
      writeDocumentInternal(document, sw, true);
    } catch (IOException e) {
      throw new IllegalStateException("Unexpected IOException in StringWriter", e);
    }
    String bridgeText = sw.toString();

    var bag = new DiagnosticBag(64);
    var lexer = new StvnLexer(CharStreams.fromString(bridgeText));
    lexer.removeErrorListeners();
    var parser = new StvnParser(new CommonTokenStream(lexer));
    parser.removeErrorListeners();
    var listener = StvnErrorListener.accumulating(bag);
    lexer.addErrorListener(listener);
    parser.addErrorListener(listener);

    var docCtx = parser.stvnDocument();
    Optional<org.stvnadore.core.ast.StvnDocumentMeta> legacyMeta = document.meta().map(LegacyAstBridge::toLegacyMeta);
    Optional<org.stvnadore.core.ast.StvnValue> legacyPayload = Optional.empty();
    if (document.payload().isPresent()
        && docCtx.documentBody() != null
        && docCtx.documentBody().bodyEntry() != null) {
      var visitor = new StvnIrVisitor(docCtx, bag);
      org.stvnadore.core.ast.StvnValue visited = visitor.visit(docCtx.documentBody().bodyEntry().value());
      legacyPayload = Optional.ofNullable(visited);
    }
    return new org.stvnadore.core.ast.StvnDocument(legacyMeta, legacyPayload, Optional.of(docCtx));
  }

  /**
   * Bridges a pure-IR {@link StvnValue} into a legacy {@link org.stvnadore.core.ast.StvnValue}.
   *
   * @param value the pure-IR value
   * @return the bridged legacy AST value
   */
  public static org.stvnadore.core.ast.StvnValue toLegacyValue(StvnValue value) {
    Objects.requireNonNull(value, "value must not be null");
    StvnDocument synthesized = StvnReachabilityAndCasVerifier.synthesizeDocumentFromValue(value);
    org.stvnadore.core.ast.StvnDocument legacyDoc = toLegacyDocument(synthesized);
    return legacyDoc.requirePayload();
  }

  /**
   * Bridges a pure-IR {@link StvnSchema} into a legacy {@link ResolvedSchema}.
   *
   * @param schema the pure-IR schema
   * @return the bridged legacy resolved schema
   */
  public static ResolvedSchema toLegacySchema(StvnSchema schema) {
    Objects.requireNonNull(schema, "schema must not be null");
    StvnDocument dummyDoc = new StvnDocument(
        Optional.empty(),
        StvnDefsCatalog.EMPTY,
        Optional.of(schema),
        Optional.empty(),
        false,
        false
    );
    StvnDocument canonicalDummy = StvnReachabilityAndCasVerifier.verifyAndPruneCanonical(
        new StvnDocument(
            Optional.empty(),
            extractCatalogFromSchema(schema),
            Optional.of(schema),
            Optional.empty(),
            true,
            false
        ),
        false,
        new DiagnosticBag(16)
    );
    StringWriter sw = new StringWriter();
    try {
      writeDocumentInternal(canonicalDummy.defs().nominalTypes().isEmpty() ? dummyDoc : canonicalDummy, sw, true);
    } catch (IOException e) {
      throw new IllegalStateException("Unexpected IOException in StringWriter", e);
    }
    var lexer = new StvnLexer(CharStreams.fromString(sw.toString()));
    lexer.removeErrorListeners();
    var parser = new StvnParser(new CommonTokenStream(lexer));
    parser.removeErrorListeners();
    var docCtx = parser.stvnDocument();
    StvnTypeResolver.validateDocumentConstraints(docCtx, new DiagnosticBag(16));
    return StvnTypeResolver.resolvePrimitiveSchema(
        docCtx,
        docCtx.documentBody().typeEntry().schemaType(),
        java.util.Set.of()
    ).orElseThrow(() -> new IllegalStateException("Failed to bridge pure-IR schema to ResolvedSchema"));
  }

  /**
   * Converts a legacy {@link org.stvnadore.core.ast.StvnValue} (such as an unpacked binary payload)
   * into a pure-IR {@link StvnValue}.
   *
   * @param legacyValue the legacy AST value
   * @return the pure-IR value
   */
  public static StvnValue fromLegacyValue(org.stvnadore.core.ast.StvnValue legacyValue) {
    Objects.requireNonNull(legacyValue, "legacyValue must not be null");
    Map<ResolvedSchema, StvnSchema> schemaCache = new IdentityHashMap<>();
    return convertLegacyValue(legacyValue, schemaCache);
  }

  /**
   * Converts a legacy {@link ResolvedSchema} into a pure-IR {@link StvnSchema}.
   *
   * @param legacySchema the legacy resolved schema
   * @return the pure-IR schema
   */
  public static StvnSchema fromLegacySchema(ResolvedSchema legacySchema) {
    Objects.requireNonNull(legacySchema, "legacySchema must not be null");
    return convertLegacySchema(legacySchema, new IdentityHashMap<>());
  }

  // ===========================================================================
  // INTERNAL PURE-IR CANONICAL SERIALIZATION
  // ===========================================================================

  private static void writeDocumentInternal(
      StvnDocument document,
      Writer target,
      boolean preserveExplicitUnionTags
  ) throws IOException {
    StvnDocument canonicalDoc = StvnReachabilityAndCasVerifier.verifyAndPruneCanonical(
        document,
        false,
        new DiagnosticBag(16)
    );
    var layout = new CanonicalLayoutWriter(target);
    layout.openGroup(StvnVocabulary.DELIM_OPEN_BRACE);

    if (canonicalDoc.meta().isPresent()) {
      StvnDocumentMeta meta = canonicalDoc.meta().get();
      layout.writeLiteral(StvnVocabulary.KEYWORD_META);
      layout.openGroup(StvnVocabulary.DELIM_OPEN_BRACE);
      if (meta.name().isPresent()) {
        layout.writeLiteral(StvnVocabulary.FACET_KW_META_NAME);
        layout.writeSimpleString(meta.name().get());
      }
      if (meta.domain().isPresent()) {
        layout.writeLiteral(StvnVocabulary.FACET_KW_META_DOMAIN);
        layout.writeSimpleString(meta.domain().get());
      }
      if (meta.kind().isPresent()) {
        layout.writeLiteral(StvnVocabulary.FACET_KW_META_KIND);
        layout.writeLiteral(meta.kind().get().keyword());
      }
      layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACE);
    }

    StvnDefsCatalog defs = canonicalDoc.defs();
    if (!defs.nominalTypes().isEmpty() || !defs.constants().isEmpty()) {
      layout.writeLiteral(StvnVocabulary.KEYWORD_DEFS);
      layout.openGroup(StvnVocabulary.DELIM_OPEN_BRACE);

      for (var constEntry : defs.constants().entrySet()) {
        layout.writeLiteral(constEntry.getKey());
        writeSchemaReferenceOrInline(constEntry.getValue().schema(), layout);
        writeValueInternal(constEntry.getValue().value(), layout, preserveExplicitUnionTags);
      }

      for (var typeEntry : defs.nominalTypes().entrySet()) {
        layout.writeLiteral(typeEntry.getKey());
        writeSchemaDefinitionBody(typeEntry.getValue(), layout);
      }

      layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACE);
    }

    Optional<StvnSchema> effectiveRootType = canonicalDoc.rootType().isPresent()
        ? canonicalDoc.rootType()
        : canonicalDoc.payload().map(StvnValue::schema);

    if (effectiveRootType.isPresent()) {
      layout.writeLiteral(StvnVocabulary.KEYWORD_TYPE);
      writeSchemaReferenceOrInline(effectiveRootType.get(), layout);
    }

    if (canonicalDoc.payload().isPresent()) {
      layout.writeLiteral(StvnVocabulary.KEYWORD_BODY);
      writeValueInternal(canonicalDoc.payload().get(), layout, preserveExplicitUnionTags);
    }

    layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACE);
    layout.flush();
  }

  private static void writeSchemaReferenceOrInline(
      StvnSchema schema,
      CanonicalLayoutWriter layout
  ) throws IOException {
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

    writeSchemaDefinitionBody(schema, layout);
  }

  private static void writeSchemaDefinitionBody(
      StvnSchema schema,
      CanonicalLayoutWriter layout
  ) throws IOException {
    writeSchemaFacets(schema, layout);
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
        for (String v : en.rootVariants()) {
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
        for (StvnSchema field : tup.fieldSchemas()) {
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
        writeSchemaReferenceOrInline(eith.rightSchema(), layout);
        layout.closeGroup(StvnVocabulary.DELIM_CLOSE_PAREN);
      }
      case StvnSchema.UnionSchema un -> {
        layout.writeLiteral(StvnVocabulary.TYPE_UNION);
        layout.openGroup(StvnVocabulary.DELIM_OPEN_PAREN);
        for (StvnSchema branch : un.branchSchemas()) {
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
        writeSchemaReferenceOrInline(map.valueSchema(), layout);
        layout.closeGroup(StvnVocabulary.DELIM_CLOSE_PAREN);
      }
      case StvnSchema.PoisonedSchema p -> {
        if (p.bestEffortFallback().isPresent()) {
          writeSchemaDefinitionBody(p.bestEffortFallback().get(), layout);
        } else {
          layout.writeLiteral(StvnVocabulary.TYPE_BOOLEAN);
        }
      }
    }
  }

  private static void writeSchemaFacets(
      StvnSchema schema,
      CanonicalLayoutWriter layout
  ) throws IOException {
    List<FacetAction> actions = new ArrayList<>();

    // Tier 1: Definition
    switch (schema) {
      case StvnSchema.IntSchema is -> {
        if (is.unsigned()) {
          actions.add(l -> l.writeLiteral(StvnVocabulary.FACET_KW_UNSIGNED));
        }
        if (!is.bitWidth().equals(StvnVocabulary.DEFAULT_INT_BIT_WIDTH)) {
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_SIZE);
            l.writeInteger(is.bitWidth());
          });
        }
      }
      case StvnSchema.FloatSchema fs -> {
        if (fs.representation() == FloatRepresentation.EXACT_DECIMAL) {
          actions.add(l -> l.writeLiteral(StvnVocabulary.FACET_KW_EXACT));
          if (!fs.exactMaxDigits().equals(StvnVocabulary.DEFAULT_MAX_SIZE)) {
            actions.add(l -> {
              l.writeLiteral(StvnVocabulary.FACET_KW_MAX_SIZE);
              l.writeInteger(fs.exactMaxDigits());
            });
          }
        } else if (fs.representation() == FloatRepresentation.IEEE_32) {
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_SIZE);
            l.writeInteger(SIZE_32);
          });
        }
      }
      case StvnSchema.MapSchema ms -> {
        if (ms.invertible()) {
          actions.add(l -> l.writeLiteral(StvnVocabulary.FACET_KW_INVERTIBLE));
        }
        addMinMaxSizeActions(ms.minSize(), ms.maxSize(), actions);
      }
      case StvnSchema.TimeEpochSchema te -> {
        String scaleKw = switch (te.scale()) {
          case NS -> StvnVocabulary.FACET_KW_SCALE_NS;
          case US -> StvnVocabulary.FACET_KW_SCALE_US;
          case MS -> StvnVocabulary.FACET_KW_SCALE_MS;
          case S -> StvnVocabulary.FACET_KW_SCALE_S;
        };
        actions.add(l -> l.writeLiteral(scaleKw));
      }
      case StvnSchema.DateTimeSchema dt -> {
        String modeKw = switch (dt.mode()) {
          case OFFSET -> StvnVocabulary.FACET_KW_OFFSET;
          case ZONED -> StvnVocabulary.FACET_KW_ZONED;
          case AUDITED -> StvnVocabulary.FACET_KW_AUDITED;
        };
        actions.add(l -> l.writeLiteral(modeKw));
      }
      case StvnSchema.StringSchema ss -> addMinMaxSizeActions(ss.minSize(), ss.maxSize(), actions);
      case StvnSchema.SeqSchema sq -> addMinMaxSizeActions(sq.minSize(), sq.maxSize(), actions);
      case StvnSchema.SetSchema st -> addMinMaxSizeActions(st.minSize(), st.maxSize(), actions);
      default -> {
        // No Tier-1 facets
      }
    }

    // Tier 2: Trait
    TraitProfile traits = schema.traits();
    if (traits.explicitEquatableOverride().isPresent()) {
      boolean eqVal = traits.explicitEquatableOverride().get();
      actions.add(l -> {
        l.writeLiteral(StvnVocabulary.FACET_KW_EQUATABLE);
        l.writeBoolean(eqVal, PrinterOptions.SymbolStyle.SHORT_FORM);
      });
    }
    if (traits.explicitComparableOverride().isPresent()) {
      boolean compVal = traits.explicitComparableOverride().get();
      actions.add(l -> {
        l.writeLiteral(StvnVocabulary.FACET_KW_COMPARABLE);
        l.writeBoolean(compVal, PrinterOptions.SymbolStyle.SHORT_FORM);
      });
    }

    // Tier 3: Bounds
    switch (schema) {
      case StvnSchema.IntSchema is -> {
        if (is.explicitMinIncl().isPresent()) {
          BigInteger v = is.explicitMinIncl().get();
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MIN_INCL);
            l.writeInteger(v);
          });
        }
        if (is.explicitMaxExcl().isPresent()) {
          BigInteger v = is.explicitMaxExcl().get();
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MAX_EXCL);
            l.writeInteger(v);
          });
        }
      }
      case StvnSchema.TimeEpochSchema te -> {
        if (te.explicitMinIncl().isPresent()) {
          BigInteger v = te.explicitMinIncl().get();
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MIN_INCL);
            l.writeInteger(v);
          });
        }
        if (te.explicitMaxExcl().isPresent()) {
          BigInteger v = te.explicitMaxExcl().get();
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MAX_EXCL);
            l.writeInteger(v);
          });
        }
      }
      case StvnSchema.DateTimeSchema dt -> {
        if (dt.explicitMinInclIso().isPresent()) {
          String v = dt.explicitMinInclIso().get();
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MIN_INCL);
            l.writeSimpleString(v);
          });
        }
        if (dt.explicitMaxExclIso().isPresent()) {
          String v = dt.explicitMaxExclIso().get();
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MAX_EXCL);
            l.writeSimpleString(v);
          });
        }
      }
      case StvnSchema.FloatSchema fs -> {
        var prec = toLegacyFloatPrecision(fs.representation());
        if (fs.lowerBound().isPresent()) {
          ContinuousBound<BigDecimal> lb = fs.lowerBound().get();
          String kw = lb.kind() == BoundKind.INCLUSIVE
              ? StvnVocabulary.FACET_KW_MIN_INCL
              : StvnVocabulary.FACET_KW_MIN_EXCL;
          actions.add(l -> {
            l.writeLiteral(kw);
            l.writeFloat(lb.value(), prec);
          });
        }
        if (fs.upperBound().isPresent()) {
          ContinuousBound<BigDecimal> ub = fs.upperBound().get();
          String kw = ub.kind() == BoundKind.INCLUSIVE
              ? StvnVocabulary.FACET_KW_MAX_INCL
              : StvnVocabulary.FACET_KW_MAX_EXCL;
          actions.add(l -> {
            l.writeLiteral(kw);
            l.writeFloat(ub.value(), prec);
          });
        }
      }
      case StvnSchema.StringSchema ss -> {
        if (ss.lowerBound().isPresent()) {
          ContinuousBound<String> lb = ss.lowerBound().get();
          String kw = lb.kind() == BoundKind.INCLUSIVE
              ? StvnVocabulary.FACET_KW_MIN_INCL
              : StvnVocabulary.FACET_KW_MIN_EXCL;
          actions.add(l -> {
            l.writeLiteral(kw);
            l.writeSimpleString(lb.value());
          });
        }
        if (ss.upperBound().isPresent()) {
          ContinuousBound<String> ub = ss.upperBound().get();
          String kw = ub.kind() == BoundKind.INCLUSIVE
              ? StvnVocabulary.FACET_KW_MAX_INCL
              : StvnVocabulary.FACET_KW_MAX_EXCL;
          actions.add(l -> {
            l.writeLiteral(kw);
            l.writeSimpleString(ub.value());
          });
        }
      }
      default -> {
        // No Tier-3 bounds
      }
    }

    // Tier 4: Constraint
    switch (schema) {
      case StvnSchema.EnumSubsetSchema sub -> {
        String kw = sub.inclusiveFilter()
            ? StvnVocabulary.FACET_KW_FILTER_INCL
            : StvnVocabulary.FACET_KW_FILTER_EXCL;
        actions.add(l -> {
          l.writeLiteral(kw);
          l.openGroup(StvnVocabulary.DELIM_OPEN_BRACKET);
          for (String v : sub.declaredFilterList()) {
            l.writeLiteral(v);
          }
          l.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACKET);
        });
      }
      case StvnSchema.StringSchema ss -> {
        if (ss.preserveIndent()) {
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_PRESERVE_INDENT);
            l.writeBoolean(true, PrinterOptions.SymbolStyle.SHORT_FORM);
          });
        }
        if (ss.rawRegexPattern().isPresent()) {
          String pat = ss.rawRegexPattern().get();
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_REGEX);
            l.writeSimpleString(pat);
          });
        }
      }
      default -> {
        // No Tier-4 facets
      }
    }

    if (actions.isEmpty()) {
      return;
    }

    layout.openGroup(StvnVocabulary.DELIM_OPEN_BRACE);
    for (FacetAction action : actions) {
      action.emit(layout);
    }
    layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACE);
  }

  private static void addMinMaxSizeActions(
      BigInteger minSize,
      BigInteger maxSize,
      List<FacetAction> actions
  ) {
    if (minSize.signum() > 0) {
      actions.add(l -> {
        l.writeLiteral(StvnVocabulary.FACET_KW_MIN_SIZE);
        l.writeInteger(minSize);
      });
    }
    if (!maxSize.equals(StvnVocabulary.DEFAULT_MAX_SIZE)) {
      actions.add(l -> {
        l.writeLiteral(StvnVocabulary.FACET_KW_MAX_SIZE);
        l.writeInteger(maxSize);
      });
    }
  }

  private static void writeValueInternal(
      StvnValue val,
      CanonicalLayoutWriter layout,
      boolean preserveExplicitUnionTags
  ) throws IOException {
    switch (val) {
      case StvnValue.StvnBoolean b -> layout.writeBoolean(b.value(), PrinterOptions.SymbolStyle.SHORT_FORM);
      case StvnValue.StvnInt i -> layout.writeInteger(i.value());
      case StvnValue.StvnFloat f -> {
        switch (f.specialKind()) {
          case NAN -> layout.writeLiteral(StvnVocabulary.LITERAL_NAN);
          case POSITIVE_INFINITY -> layout.writeLiteral(StvnVocabulary.LITERAL_POS_INFINITY);
          case NEGATIVE_INFINITY -> layout.writeLiteral(StvnVocabulary.LITERAL_NEG_INFINITY);
          case FINITE -> {
            if (f.negativeZero()) {
              layout.writeLiteral(StvnVocabulary.LITERAL_NEG_ZERO);
            } else {
              FloatRepresentation rep = (f.schema() instanceof StvnSchema.FloatSchema fs)
                  ? fs.representation()
                  : FloatRepresentation.IEEE_64;
              layout.writeFloat(f.decimalValue(), toLegacyFloatPrecision(rep));
            }
          }
        }
      }
      case StvnValue.StvnString s -> {
        String text = s.value();
        boolean preserveIndent = s.schema() instanceof StvnSchema.StringSchema ss && ss.preserveIndent();
        if (preserveExplicitUnionTags
            && text.indexOf('\t') >= 0
            && text.indexOf('\r') < 0
            && (preserveIndent || text.equals(StvnPayloadLowerer.trimCommonIndent(text)))) {
          String fenceTag = chooseSafeFenceTag(text);
          layout.writeLiteral("\"\"\"[" + fenceTag + "]\n" + text + "[" + fenceTag + "]\"\"\"");
        } else {
          layout.writeSimpleString(text);
        }
      }
      case StvnValue.StvnTimeEpoch te -> layout.writeInteger(te.epochTicks());
      case StvnValue.StvnDateTime dt -> layout.writeSimpleString(dt.canonicalIsoText());
      case StvnValue.StvnEnum en -> layout.writeEnumKeyword(en.variantTag());
      case StvnValue.StvnTuple tup -> {
        layout.openGroup(StvnVocabulary.DELIM_OPEN_PAREN);
        for (StvnValue el : tup.elements()) {
          writeValueInternal(el, layout, preserveExplicitUnionTags);
        }
        layout.closeGroup(StvnVocabulary.DELIM_CLOSE_PAREN);
      }
      case StvnValue.StvnSeq seq -> {
        layout.openGroup(StvnVocabulary.DELIM_OPEN_BRACKET);
        for (StvnValue el : seq.elements()) {
          writeValueInternal(el, layout, preserveExplicitUnionTags);
        }
        layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACKET);
      }
      case StvnValue.StvnSet set -> {
        layout.openGroup(StvnVocabulary.DELIM_OPEN_BRACKET);
        for (StvnValue el : set.elements()) {
          writeValueInternal(el, layout, preserveExplicitUnionTags);
        }
        layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACKET);
      }
      case StvnValue.StvnMap map -> {
        layout.openGroup(StvnVocabulary.DELIM_OPEN_BRACE);
        for (KeyValueEntry entry : map.entries()) {
          layout.openGroup(StvnVocabulary.DELIM_OPEN_BRACKET);
          writeValueInternal(entry.key(), layout, preserveExplicitUnionTags);
          writeValueInternal(entry.value(), layout, preserveExplicitUnionTags);
          layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACKET);
        }
        layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACE);
      }
      case StvnValue.StvnOption opt -> {
        if (opt.value().isPresent()) {
          layout.openOptionSomeTag(PrinterOptions.SymbolStyle.SHORT_FORM);
          writeValueInternal(opt.value().get(), layout, preserveExplicitUnionTags);
          layout.closeTag();
        } else {
          layout.writeOptionNone(PrinterOptions.SymbolStyle.SHORT_FORM);
        }
      }
      case StvnValue.StvnEither eith -> {
        boolean isRight = eith.branch() == EitherBranch.RIGHT;
        layout.openEitherTag(isRight, PrinterOptions.SymbolStyle.SHORT_FORM);
        writeValueInternal(eith.value(), layout, preserveExplicitUnionTags);
        layout.closeTag();
      }
      case StvnValue.StvnUnion un -> {
        if (preserveExplicitUnionTags) {
          layout.writeLiteral(StvnVocabulary.SIGIL_VALUE + un.oneBasedBranchIndex().toString());
        }
        writeValueInternal(un.value(), layout, preserveExplicitUnionTags);
      }
      case StvnValue.StvnErrorValue err -> layout.writeSimpleString(err.diagnostic().message());
    }
  }

  private static String chooseSafeFenceTag(String text) {
    String candidate = "RAW";
    int suffix = 0;
    while (text.contains("[" + candidate + "]\"\"\"")) {
      suffix++;
      candidate = "RAW_" + suffix;
    }
    return candidate;
  }

  private static org.stvnadore.core.ast.StvnValue.FloatPrecision toLegacyFloatPrecision(FloatRepresentation rep) {
    return switch (rep) {
      case IEEE_32 -> org.stvnadore.core.ast.StvnValue.FloatPrecision.FLOAT32;
      case IEEE_64 -> org.stvnadore.core.ast.StvnValue.FloatPrecision.FLOAT64;
      case EXACT_DECIMAL -> org.stvnadore.core.ast.StvnValue.FloatPrecision.EXACT;
    };
  }

  @FunctionalInterface
  private interface FacetAction {
    void emit(CanonicalLayoutWriter layout) throws IOException;
  }

  private static StvnDefsCatalog extractCatalogFromSchema(StvnSchema schema) {
    StvnDocument dummy = StvnReachabilityAndCasVerifier.synthesizeDocumentFromValue(
        new StvnValue.StvnErrorValue(
            schema,
            new StvnDiagnostic(
                "bridge",
                DiagnosticSeverity.WARNING,
                1, 0, 0, 0, null,
                Optional.of("W003"),
                Optional.of(DiagnosticBag.WARN_REDUNDANT_METADATA_FACET)
            )
        )
    );
    return dummy.defs();
  }

  // ===========================================================================
  // LEGACY AST -> PURE IR CONVERSION (FOR STAGE 3 BINARY DECODER INGRESS)
  // ===========================================================================

  private static StvnValue convertLegacyValue(
      org.stvnadore.core.ast.StvnValue legacyValue,
      Map<ResolvedSchema, StvnSchema> schemaCache
  ) {
    StvnSchema irSchema = convertLegacySchema(legacyValue.schema(), schemaCache);
    return switch (legacyValue) {
      case org.stvnadore.core.ast.StvnValue.StvnBoolean b ->
          new StvnValue.StvnBoolean(irSchema, b.value());

      case org.stvnadore.core.ast.StvnValue.StvnInteger i ->
          new StvnValue.StvnInt(irSchema, i.value());

      case org.stvnadore.core.ast.StvnValue.StvnFloat f -> {
        Ieee754SpecialKind kind = f.isNaN()
            ? Ieee754SpecialKind.NAN
            : (f.isPositiveInfinity()
                ? Ieee754SpecialKind.POSITIVE_INFINITY
                : (f.isNegativeInfinity() ? Ieee754SpecialKind.NEGATIVE_INFINITY : Ieee754SpecialKind.FINITE));
        yield new StvnValue.StvnFloat(irSchema, f.value(), f.isNegativeZero(), kind);
      }

      case org.stvnadore.core.ast.StvnValue.StvnString s ->
          new StvnValue.StvnString(irSchema, s.value());

      case org.stvnadore.core.ast.StvnValue.StvnTime t -> {
        TemporalScale scale = switch (t.kind()) {
          case EPOCH_NS -> TemporalScale.NS;
          case EPOCH_US -> TemporalScale.US;
          case EPOCH_MS -> TemporalScale.MS;
          default -> TemporalScale.S;
        };
        BigInteger ticks = (t.value() instanceof BigInteger bi)
            ? bi
            : new BigInteger(t.value().toString());
        StvnSchema effectiveSchema = (irSchema instanceof StvnSchema.TimeEpochSchema)
            ? irSchema
            : new StvnSchema.TimeEpochSchema(
                irSchema.aliasName(), scale, TraitProfile.ofDefault(true, true), Optional.empty(), Optional.empty()
            );
        yield new StvnValue.StvnTimeEpoch(effectiveSchema, ticks, scale);
      }

      case org.stvnadore.core.ast.StvnValue.StvnDateTimeOffset dto -> {
        OffsetDateTime odt = dto.value();
        String iso = formatOffsetDateTimePreservingSeconds(odt);
        yield new StvnValue.StvnDateTime(
            irSchema, DateTimeMode.OFFSET, odt.toLocalDateTime(),
            Optional.of(odt.getOffset()), Optional.empty(), iso
        );
      }

      case org.stvnadore.core.ast.StvnValue.StvnDateTimeZoned dtz -> {
        LocalDateTime ldt = dtz.localDateTime();
        ZoneId zid = dtz.zoneId();
        String iso = formatLocalDateTimePreservingSeconds(ldt) + "[" + zid.getId() + "]";
        yield new StvnValue.StvnDateTime(
            irSchema, DateTimeMode.ZONED, ldt,
            Optional.empty(), Optional.of(zid), iso
        );
      }

      case org.stvnadore.core.ast.StvnValue.StvnDateTimeAudited dta -> {
        OffsetDateTime odt = dta.offsetDateTime();
        ZoneId zid = dta.zoneId();
        String iso = formatOffsetDateTimePreservingSeconds(odt) + "[" + zid.getId() + "]";
        yield new StvnValue.StvnDateTime(
            irSchema, DateTimeMode.AUDITED, odt.toLocalDateTime(),
            Optional.of(odt.getOffset()), Optional.of(zid), iso
        );
      }

      case org.stvnadore.core.ast.StvnValue.StvnEnum en -> {
        String tag = en.keyword().startsWith(StvnVocabulary.SIGIL_VALUE)
            ? en.keyword()
            : (StvnVocabulary.SIGIL_VALUE + en.keyword());
        yield new StvnValue.StvnEnum(irSchema, tag, BigInteger.valueOf(Math.max(0, en.sequentialIndex())));
      }

      case org.stvnadore.core.ast.StvnValue.StvnTuple tup -> {
        List<StvnValue> els = new ArrayList<>(tup.elements().size());
        for (var child : tup.elements()) {
          els.add(convertLegacyValue(child, schemaCache));
        }
        yield new StvnValue.StvnTuple(irSchema, els);
      }

      case org.stvnadore.core.ast.StvnValue.StvnSeq seq -> {
        List<StvnValue> els = new ArrayList<>(seq.elements().size());
        for (var child : seq.elements()) {
          els.add(convertLegacyValue(child, schemaCache));
        }
        yield new StvnValue.StvnSeq(irSchema, els);
      }

      case org.stvnadore.core.ast.StvnValue.StvnSet set -> {
        List<StvnValue> els = new ArrayList<>(set.elements().size());
        for (var child : set.elements()) {
          els.add(convertLegacyValue(child, schemaCache));
        }
        yield new StvnValue.StvnSet(irSchema, els);
      }

      case org.stvnadore.core.ast.StvnValue.StvnMap map -> {
        List<KeyValueEntry> entries = new ArrayList<>(map.entries().size());
        for (var entry : map.entries().entrySet()) {
          entries.add(new KeyValueEntry(
              convertLegacyValue(entry.getKey(), schemaCache),
              convertLegacyValue(entry.getValue(), schemaCache)
          ));
        }
        yield new StvnValue.StvnMap(irSchema, entries);
      }

      case org.stvnadore.core.ast.StvnValue.StvnOption opt ->
          new StvnValue.StvnOption(irSchema, opt.value().map(v -> convertLegacyValue(v, schemaCache)));

      case org.stvnadore.core.ast.StvnValue.StvnEither eith ->
          new StvnValue.StvnEither(
              irSchema,
              eith.isRight() ? EitherBranch.RIGHT : EitherBranch.LEFT,
              convertLegacyValue(eith.value(), schemaCache)
          );

      case org.stvnadore.core.ast.StvnValue.StvnUnion un ->
          new StvnValue.StvnUnion(
              irSchema,
              BigInteger.valueOf(Math.max(1L, un.tagIndex() + 1L)),
              convertLegacyValue(un.value(), schemaCache)
          );

      case org.stvnadore.core.ast.StvnValue.StvnError err -> {
        StvnDiagnostic diag = !err.diagnostics().isEmpty()
            ? err.diagnostics().getFirst()
            : new StvnDiagnostic(
                err.rawText(),
                DiagnosticSeverity.ERROR,
                1, 0, err.startOffset(), err.endOffset(), null,
                Optional.of("E012"),
                Optional.of(DiagnosticBag.ERR_TYPE_MISMATCH)
            );
        yield new StvnValue.StvnErrorValue(irSchema, diag);
      }
    };
  }

  private static String formatLocalDateTimePreservingSeconds(LocalDateTime ldt) {
    if (ldt.getNano() == 0) {
      return ldt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
    }
    return ldt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
  }

  private static String formatOffsetDateTimePreservingSeconds(OffsetDateTime odt) {
    String offsetText = odt.getOffset().equals(ZoneOffset.UTC) ? "Z" : odt.getOffset().getId();
    return formatLocalDateTimePreservingSeconds(odt.toLocalDateTime()) + offsetText;
  }

  private static StvnSchema convertLegacySchema(
      ResolvedSchema legacySchema,
      Map<ResolvedSchema, StvnSchema> cache
  ) {
    StvnSchema existing = cache.get(legacySchema);
    if (existing != null) {
      return existing;
    }

    Optional<String> alias = legacySchema.aliasName();
    if (alias.isPresent()) {
      // Pre-insert recursive back-edge sentinel to break cyclic nominal types safely
      cache.put(
          legacySchema,
          new StvnSchema.PoisonedSchema(
              alias,
              StvnPayloadLowerer.RECURSIVE_BACK_EDGE_SENTINEL,
              Optional.empty(),
              TraitProfile.ofDefault(true, false)
          )
      );
    }

    var c = legacySchema.constraints();
    var node = legacySchema.node();
    TraitProfile defaultScalarTraits = buildTraits(c, true, true);

    StvnSchema converted;
    if (legacySchema.isPoisonedSentinel()) {
      converted = new StvnSchema.PoisonedSchema(
          alias,
          DiagnosticBag.ERR_MALFORMED_SCHEMA,
          Optional.empty(),
          TraitProfile.ofDefault(false, false)
      );
    } else if (node == null || node.schemaConstructor() == null) {
      converted = new StvnSchema.BooleanSchema(alias, defaultScalarTraits);
    } else {
      var ctor = node.schemaConstructor();
      if (ctor.atomicType() != null) {
        String kw = ctor.atomicType().getText();
        if (kw.equals(StvnVocabulary.TYPE_BOOLEAN)) {
          converted = new StvnSchema.BooleanSchema(alias, defaultScalarTraits);
        } else if (kw.equals(StvnVocabulary.TYPE_INT)) {
          BigInteger bw = c.size().map(i -> BigInteger.valueOf(i.longValue())).orElse(StvnVocabulary.DEFAULT_INT_BIT_WIDTH);
          Optional<BigInteger> minI = c.minIncl().map(BigDecimal::toBigInteger);
          Optional<BigInteger> maxE = c.maxExcl().map(BigDecimal::toBigInteger);
          converted = new StvnSchema.IntSchema(alias, c.unsigned(), bw, defaultScalarTraits, minI, maxE);
        } else if (kw.equals(StvnVocabulary.TYPE_FLOAT)) {
          FloatRepresentation rep = c.exact()
              ? FloatRepresentation.EXACT_DECIMAL
              : (c.size().filter(i -> i == 32).isPresent()
                  ? FloatRepresentation.IEEE_32
                  : FloatRepresentation.IEEE_64);
          BigInteger maxDigits = c.maxSize().map(i -> BigInteger.valueOf(i.longValue())).orElse(StvnVocabulary.DEFAULT_MAX_SIZE);
          TraitProfile floatTraits = buildTraits(c, c.exact(), true);
          Optional<ContinuousBound<BigDecimal>> lb = c.minIncl()
              .map(v -> new ContinuousBound<>(v, BoundKind.INCLUSIVE))
              .or(() -> c.minExcl().map(v -> new ContinuousBound<>(v, BoundKind.EXCLUSIVE)));
          Optional<ContinuousBound<BigDecimal>> ub = c.maxIncl()
              .map(v -> new ContinuousBound<>(v, BoundKind.INCLUSIVE))
              .or(() -> c.maxExcl().map(v -> new ContinuousBound<>(v, BoundKind.EXCLUSIVE)));
          converted = new StvnSchema.FloatSchema(alias, rep, maxDigits, floatTraits, lb, ub);
        } else if (kw.equals(StvnVocabulary.TYPE_STRING)) {
          BigInteger minS = c.minSize().map(i -> BigInteger.valueOf(i.longValue())).orElse(BigInteger.ZERO);
          BigInteger maxS = c.maxSize().map(i -> BigInteger.valueOf(i.longValue())).orElse(StvnVocabulary.DEFAULT_MAX_SIZE);
          Optional<ContinuousBound<String>> lb = c.dateMinIncl()
              .map(v -> new ContinuousBound<>(v, BoundKind.INCLUSIVE));
          Optional<ContinuousBound<String>> ub = c.dateMaxExcl()
              .map(v -> new ContinuousBound<>(v, BoundKind.EXCLUSIVE));
          Optional<Pattern> pat = c.regex().map(Pattern::compile);
          converted = new StvnSchema.StringSchema(
              alias, minS, maxS, defaultScalarTraits, lb, ub, c.preserveIndent(), pat, c.regex()
          );
        } else if (kw.equals(StvnVocabulary.TYPE_TIME_EPOCH)) {
          String scaleStr = c.scale().orElse(StvnVocabulary.SCALE_S);
          TemporalScale scale = switch (scaleStr) {
            case StvnVocabulary.SCALE_NS, StvnVocabulary.FACET_KW_SCALE_NS -> TemporalScale.NS;
            case StvnVocabulary.SCALE_US, StvnVocabulary.FACET_KW_SCALE_US -> TemporalScale.US;
            case StvnVocabulary.SCALE_MS, StvnVocabulary.FACET_KW_SCALE_MS -> TemporalScale.MS;
            default -> TemporalScale.S;
          };
          Optional<BigInteger> minI = c.minIncl().map(BigDecimal::toBigInteger);
          Optional<BigInteger> maxE = c.maxExcl().map(BigDecimal::toBigInteger);
          converted = new StvnSchema.TimeEpochSchema(alias, scale, defaultScalarTraits, minI, maxE);
        } else if (kw.equals(StvnVocabulary.TYPE_DATE_TIME)) {
          DateTimeMode mode = c.zoned()
              ? DateTimeMode.ZONED
              : (c.audited() ? DateTimeMode.AUDITED : DateTimeMode.OFFSET);
          converted = new StvnSchema.DateTimeSchema(
              alias, mode, defaultScalarTraits, c.dateMinIncl(), c.dateMaxExcl()
          );
        } else {
          converted = new StvnSchema.BooleanSchema(alias, defaultScalarTraits);
        }
      } else if (ctor.collectionType() != null) {
        var col = ctor.collectionType();
        BigInteger minS = c.minSize().map(i -> BigInteger.valueOf(i.longValue())).orElse(BigInteger.ZERO);
        BigInteger maxS = c.maxSize().map(i -> BigInteger.valueOf(i.longValue())).orElse(StvnVocabulary.DEFAULT_MAX_SIZE);
        if (col.COLL_SEQ() != null) {
          StvnSchema el = convertLegacyChild(col.schemaType(0), cache);
          TraitProfile tp = buildTraits(c, el.traits().equatable(), el.traits().comparable());
          converted = new StvnSchema.SeqSchema(alias, minS, maxS, el, tp);
        } else if (col.COLL_SET() != null) {
          StvnSchema el = convertLegacyChild(col.schemaType(0), cache);
          TraitProfile tp = buildTraits(c, true, false);
          converted = new StvnSchema.SetSchema(alias, minS, maxS, el, tp);
        } else {
          StvnSchema k = convertLegacyChild(col.schemaType(0), cache);
          StvnSchema v = convertLegacyChild(col.schemaType(1), cache);
          boolean defEq = c.invertible()
              ? (k.traits().equatable() && v.traits().equatable())
              : k.traits().equatable();
          TraitProfile tp = buildTraits(c, defEq, false);
          converted = new StvnSchema.MapSchema(alias, c.invertible(), minS, maxS, k, v, tp);
        }
      } else if (ctor.productType() instanceof StvnParser.TupleTypeContext tt) {
        List<StvnSchema> fields = new ArrayList<>();
        boolean allEq = true;
        boolean allComp = true;
        for (var st : tt.schemaType()) {
          StvnSchema f = convertLegacyChild(st, cache);
          fields.add(f);
          allEq &= f.traits().equatable();
          allComp &= f.traits().comparable();
        }
        converted = new StvnSchema.TupleSchema(alias, fields, buildTraits(c, allEq, allComp));
      } else if (ctor.sumType() != null) {
        var sum = ctor.sumType();
        if (sum.KW_OPTION() != null) {
          StvnSchema inner = convertLegacyChild(sum.schemaType(0), cache);
          TraitProfile tp = buildTraits(c, inner.traits().equatable(), inner.traits().comparable());
          converted = new StvnSchema.OptionSchema(alias, inner, tp);
        } else if (sum.KW_EITHER() != null) {
          StvnSchema l = convertLegacyChild(sum.schemaType(0), cache);
          StvnSchema r = convertLegacyChild(sum.schemaType(1), cache);
          TraitProfile tp = buildTraits(
              c,
              l.traits().equatable() && r.traits().equatable(),
              l.traits().comparable() && r.traits().comparable()
          );
          converted = new StvnSchema.EitherSchema(alias, l, r, tp);
        } else if (sum.KW_UNION() != null) {
          List<StvnSchema> branches = new ArrayList<>();
          boolean allEq = true;
          boolean allComp = true;
          for (var st : sum.schemaType()) {
            StvnSchema b = convertLegacyChild(st, cache);
            branches.add(b);
            allEq &= b.traits().equatable();
            allComp &= b.traits().comparable();
          }
          converted = new StvnSchema.UnionSchema(alias, branches, buildTraits(c, allEq, allComp));
        } else {
          List<String> rootVars = new ArrayList<>();
          for (var vk : sum.enumDef().valueKeyword()) {
            rootVars.add(vk.getText());
          }
          converted = new StvnSchema.EnumSchema(alias, rootVars, defaultScalarTraits);
        }
      } else {
        converted = new StvnSchema.BooleanSchema(alias, defaultScalarTraits);
      }
    }

    cache.put(legacySchema, converted);
    return converted;
  }

  private static StvnSchema convertLegacyChild(
      StvnParser.SchemaTypeContext childCtx,
      Map<ResolvedSchema, StvnSchema> cache
  ) {
    org.antlr.v4.runtime.RuleContext cur = childCtx;
    StvnParser.StvnDocumentContext docCtx = null;
    while (cur != null) {
      if (cur instanceof StvnParser.StvnDocumentContext dc) {
        docCtx = dc;
        break;
      }
      cur = cur.parent;
    }
    ResolvedSchema resolved = StvnTypeResolver.resolvePrimitiveSchema(docCtx, childCtx, java.util.Set.of())
        .orElseThrow(() -> new IllegalStateException("Failed to resolve child schema context"));
    return convertLegacySchema(resolved, cache);
  }

  private static TraitProfile buildTraits(
      StvnTypeResolver.StvnConstraints c,
      boolean defaultEq,
      boolean defaultComp
  ) {
    boolean eq = c.equatable().orElse(defaultEq);
    boolean comp = c.comparable().orElse(defaultComp);
    Optional<Boolean> expEq = (c.explicitOverrides().contains(StvnVocabulary.FACET_NAME_EQUATABLE) && eq != defaultEq)
        ? Optional.of(eq)
        : Optional.empty();
    Optional<Boolean> expComp = (c.explicitOverrides().contains(StvnVocabulary.FACET_NAME_COMPARABLE) && comp != defaultComp)
        ? Optional.of(comp)
        : Optional.empty();
    return new TraitProfile(eq, comp, expEq, expComp);
  }
}
