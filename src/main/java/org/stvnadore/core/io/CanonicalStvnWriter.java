package org.stvnadore.core.io;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnDefsCatalog;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnDocumentMeta;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnSchema.BoundKind;
import org.stvnadore.core.ir.StvnSchema.FloatRepresentation;
import org.stvnadore.core.ir.StvnSchema.TraitProfile;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.ir.StvnValue.EitherBranch;
import org.stvnadore.core.ir.StvnValue.Ieee754SpecialKind;
import org.stvnadore.core.ir.StvnValue.KeyValueEntry;
import org.stvnadore.core.lowering.StvnLiteralParser;
import org.stvnadore.core.lowering.StvnPayloadLowerer;
import org.stvnadore.core.lowering.StvnReachabilityAndCasVerifier;
import org.stvnadore.core.printer.PrinterOptions;
import org.stvnadore.core.printer.StvnTextPrinter;
import org.stvnadore.core.validation.DiagnosticBag;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Serializes pure-IR STVN documents and value trees into a deterministic, canonical text representation.
 * <p>
 * This class implements the frozen Content-Addressable Storage (CAS) canonicalization profile
 * defined by the STVN 2.0.0 specification (§ 3.3.5, § 6.1, and Appendix G) to guarantee that
 * semantically identical documents and value trees produce byte-for-byte identical text streams
 * for SHA-256 cryptographic fingerprinting.
 * </p>
 *
 * <h2>Canonicalization Invariants</h2>
 * <ul>
 *   <li><b>5-Tier Decimal Metadata Facet Order:</b> Schema metadata facets serialize strictly in
 *       monotonic decimal sequence ({@code 1.1.1} through {@code 5.0}, § 6.1) with zero redundant
 *       {@code W003} tautologies.</li>
 *   <li><b>Frozen Short-Form &amp; Explicit Sum Profile:</b> Booleans and sum-type constructors
 *       always emit explicit short-form keywords ({@code #T}, {@code #F}, {@code #S}, {@code #N},
 *       {@code #L}, {@code #R}, and {@code #<k>} for {@code :Union}).</li>
 *   <li><b>Lossless Definitions &amp; Payload Serialization:</b> Both {@code #DEFS} / {@code #DEFS_INCLUDE}
 *       schemas ({@code .stvn_d}, {@code .stvn_di}) and {@code #BODY} / {@code #BODY_INCLUDE}
 *       documents ({@code .stvn}, {@code .stvn_i}) serialize losslessly.</li>
 *   <li><b>Zero Bracket Padding &amp; Temporal Fidelity:</b> Optional whitespace adjacent to structural
 *       delimiters is stripped, {@code :DateTime} values always retain {@code :00} seconds, and
 *       strings preserve {@code \t} losslessly.</li>
 * </ul>
 *
 * @since 1.0.0
 */
@NullMarked
public final class CanonicalStvnWriter implements StvnTextPrinter {

  private static final BigInteger THIRTY_TWO = BigInteger.valueOf(32L);

  /**
   * Constructs a new {@code CanonicalStvnWriter}.
   */
  public CanonicalStvnWriter() {
  }

  /**
   * Maps a {@link StvnSchema.TemporalScale} to its canonical lexical facet keyword.
   *
   * @param scale the temporal epoch scale
   * @return the canonical facet keyword ({@code #ns}, {@code #us}, {@code #ms}, or {@code #s})
   * @since 2.0.0
   */
  public static String temporalScaleFacetKeyword(StvnSchema.TemporalScale scale) {
    return switch (scale) {
      case NS -> StvnVocabulary.FACET_KW_SCALE_NS;
      case US -> StvnVocabulary.FACET_KW_SCALE_US;
      case MS -> StvnVocabulary.FACET_KW_SCALE_MS;
      case S -> StvnVocabulary.FACET_KW_SCALE_S;
    };
  }

  /**
   * Maps a {@link StvnSchema.DateTimeMode} to its canonical lexical facet keyword.
   *
   * @param mode the tripartite datetime mode
   * @return the canonical facet keyword ({@code #offset}, {@code #zoned}, or {@code #audited})
   * @since 2.0.0
   */
  public static String dateTimeModeFacetKeyword(StvnSchema.DateTimeMode mode) {
    return switch (mode) {
      case OFFSET -> StvnVocabulary.FACET_KW_OFFSET;
      case ZONED -> StvnVocabulary.FACET_KW_ZONED;
      case AUDITED -> StvnVocabulary.FACET_KW_AUDITED;
    };
  }

  /**
   * Serializes the given pure-IR STVN document into the specified writer in its canonical form.
   *
   * @param document the pure-IR STVN document to serialize
   * @param target   the destination writer
   * @throws IOException          if an I/O error occurs during serialization
   * @throws NullPointerException if {@code document} or {@code target} is {@code null}
   * @since 2.0.0
   */
  public void print(StvnDocument document, Writer target) throws IOException {
    Objects.requireNonNull(document, "document must not be null");
    Objects.requireNonNull(target, "target must not be null");

    StvnDocument canonicalDoc = StvnReachabilityAndCasVerifier.verifyAndPruneCanonical(
        document,
        false,
        new DiagnosticBag(16)
    );
    writeCanonicalDocument(canonicalDoc, target);
  }

  /**
   * Serializes the given pure-IR STVN document and returns its canonical string representation.
   *
   * @param document the pure-IR STVN document to serialize
   * @return the canonical STVN string representation
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

  /**
   * Serializes the given pure-IR STVN value tree into the specified writer in its canonical form.
   *
   * @param value  the pure-IR STVN value tree to serialize
   * @param target the destination writer
   * @throws IOException          if an I/O error occurs during serialization
   * @throws NullPointerException if {@code value} or {@code target} is {@code null}
   * @since 2.0.0
   */
  @Override
  public void print(StvnValue value, Writer target) throws IOException {
    Objects.requireNonNull(value, "value must not be null");
    Objects.requireNonNull(target, "target must not be null");

    StvnDocument synthesized = StvnReachabilityAndCasVerifier.synthesizeDocumentFromValue(value);
    writeCanonicalDocument(synthesized, target);
  }

  /**
   * Serializes the given pure-IR STVN value tree and returns its canonical string representation.
   *
   * @param value the pure-IR STVN value tree to serialize
   * @return the canonical STVN string representation
   * @throws NullPointerException if {@code value} is {@code null}
   * @since 2.0.0
   */
  @Override
  public String printToString(StvnValue value) {
    Objects.requireNonNull(value, "value must not be null");
    var sw = new StringWriter();
    try {
      print(value, sw);
    } catch (IOException e) {
      throw new RuntimeException("Unreachable IOException in StringWriter", e);
    }
    return sw.toString();
  }

  private static void writeCanonicalDocument(StvnDocument canonicalDoc, Writer target) throws IOException {
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
        writeCanonicalValue(constEntry.getValue().value(), layout);
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
      writeCanonicalValue(canonicalDoc.payload().get(), layout);
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

  /**
   * Writes a pure-IR {@link StvnSchema} definition body (optional 5-tier metadata block followed by
   * the target type constructor or parent enum identifier) into the supplied {@link CanonicalLayoutWriter}.
   *
   * @param schema the normalized pure-IR schema definition
   * @param layout the destination canonical layout writer
   * @throws IOException if an I/O error occurs during emission
   * @since 2.0.0
   */
  public static void writeSchemaDefinitionBody(
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

  /**
   * Writes a pure-IR {@link StvnDefsCatalog.StvnConstant} declaration ({@code #NAME <schema> <value>}) into the
   * supplied {@link CanonicalLayoutWriter}.
   *
   * @param constantName the constant identifier starting with {@code #}
   * @param constant     the lowered constant entry
   * @param layout       the destination canonical layout writer
   * @throws IOException if an I/O error occurs during emission
   * @since 2.0.0
   */
  public static void writeConstantDefinition(
      String constantName,
      StvnDefsCatalog.StvnConstant constant,
      CanonicalLayoutWriter layout
  ) throws IOException {
    layout.writeLiteral(constantName);
    writeSchemaReferenceOrInline(constant.schema(), layout);
    writeCanonicalValue(constant.value(), layout);
  }

  private static void writeSchemaFacets(
      StvnSchema schema,
      CanonicalLayoutWriter layout
  ) throws IOException {
    List<FacetEmitter> actions = new ArrayList<>();

    // Tier 1: Definition (1.1.1 .. 1.5.2)
    switch (schema) {
      case StvnSchema.IntSchema i -> {
        if (i.unsigned()) {
          actions.add(l -> l.writeLiteral(StvnVocabulary.FACET_KW_UNSIGNED));
        }
        if (!i.bitWidth().equals(StvnVocabulary.DEFAULT_INT_BIT_WIDTH)) {
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_SIZE);
            l.writeLiteral(i.bitWidth().toString());
          });
        }
      }
      case StvnSchema.FloatSchema f -> {
        if (f.representation() == FloatRepresentation.EXACT_DECIMAL) {
          actions.add(l -> l.writeLiteral(StvnVocabulary.FACET_KW_EXACT));
        }
        if (f.representation() == FloatRepresentation.IEEE_32) {
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_SIZE);
            l.writeLiteral(THIRTY_TWO.toString());
          });
        }
        if (f.representation() == FloatRepresentation.EXACT_DECIMAL
            && !f.exactMaxDigits().equals(StvnVocabulary.DEFAULT_MAX_SIZE)) {
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MAX_SIZE);
            l.writeLiteral(f.exactMaxDigits().toString());
          });
        }
      }
      case StvnSchema.MapSchema map -> {
        if (map.invertible()) {
          actions.add(l -> l.writeLiteral(StvnVocabulary.FACET_KW_INVERTIBLE));
        }
        if (map.minSize().signum() > 0) {
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MIN_SIZE);
            l.writeLiteral(map.minSize().toString());
          });
        }
        if (!map.maxSize().equals(StvnVocabulary.DEFAULT_MAX_SIZE)) {
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MAX_SIZE);
            l.writeLiteral(map.maxSize().toString());
          });
        }
      }
      case StvnSchema.TimeEpochSchema te ->
          actions.add(l -> l.writeLiteral(temporalScaleFacetKeyword(te.scale())));
      case StvnSchema.DateTimeSchema dt ->
          actions.add(l -> l.writeLiteral(dateTimeModeFacetKeyword(dt.mode())));
      case StvnSchema.StringSchema s -> {
        if (s.minSize().signum() > 0) {
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MIN_SIZE);
            l.writeLiteral(s.minSize().toString());
          });
        }
        if (!s.maxSize().equals(StvnVocabulary.DEFAULT_MAX_SIZE)) {
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MAX_SIZE);
            l.writeLiteral(s.maxSize().toString());
          });
        }
      }
      case StvnSchema.SeqSchema seq -> {
        if (seq.minSize().signum() > 0) {
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MIN_SIZE);
            l.writeLiteral(seq.minSize().toString());
          });
        }
        if (!seq.maxSize().equals(StvnVocabulary.DEFAULT_MAX_SIZE)) {
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MAX_SIZE);
            l.writeLiteral(seq.maxSize().toString());
          });
        }
      }
      case StvnSchema.SetSchema set -> {
        if (set.minSize().signum() > 0) {
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MIN_SIZE);
            l.writeLiteral(set.minSize().toString());
          });
        }
        if (!set.maxSize().equals(StvnVocabulary.DEFAULT_MAX_SIZE)) {
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MAX_SIZE);
            l.writeLiteral(set.maxSize().toString());
          });
        }
      }
      default -> {
        // No Tier-1 facets
      }
    }

    // Tier 2: Trait (2.1 .. 2.2)
    TraitProfile traits = schema.traits();
    if (traits.explicitEquatableOverride().isPresent()) {
      boolean eq = traits.explicitEquatableOverride().get();
      actions.add(l -> {
        l.writeLiteral(StvnVocabulary.FACET_KW_EQUATABLE);
        l.writeBoolean(eq, PrinterOptions.SymbolStyle.SHORT_FORM);
      });
    }
    if (traits.explicitComparableOverride().isPresent()) {
      boolean cmp = traits.explicitComparableOverride().get();
      actions.add(l -> {
        l.writeLiteral(StvnVocabulary.FACET_KW_COMPARABLE);
        l.writeBoolean(cmp, PrinterOptions.SymbolStyle.SHORT_FORM);
      });
    }

    // Tier 3: Domain Bounds (3.1 .. 3.4)
    switch (schema) {
      case StvnSchema.IntSchema i -> {
        if (i.explicitMinIncl().isPresent()) {
          BigInteger min = i.explicitMinIncl().get();
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MIN_INCL);
            l.writeLiteral(min.toString());
          });
        }
        if (i.explicitMaxExcl().isPresent()) {
          BigInteger max = i.explicitMaxExcl().get();
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MAX_EXCL);
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
          actions.add(l -> {
            l.writeLiteral(kw);
            l.writeFloat(lb.value(), f.representation());
          });
        }
        if (f.upperBound().isPresent()) {
          var ub = f.upperBound().get();
          String kw = ub.kind() == BoundKind.INCLUSIVE
              ? StvnVocabulary.FACET_KW_MAX_INCL
              : StvnVocabulary.FACET_KW_MAX_EXCL;
          actions.add(l -> {
            l.writeLiteral(kw);
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
          actions.add(l -> {
            l.writeLiteral(kw);
            l.writeSimpleString(lb.value());
          });
        }
        if (s.upperBound().isPresent()) {
          var ub = s.upperBound().get();
          String kw = ub.kind() == BoundKind.INCLUSIVE
              ? StvnVocabulary.FACET_KW_MAX_INCL
              : StvnVocabulary.FACET_KW_MAX_EXCL;
          actions.add(l -> {
            l.writeLiteral(kw);
            l.writeSimpleString(ub.value());
          });
        }
      }
      case StvnSchema.TimeEpochSchema te -> {
        if (te.explicitMinIncl().isPresent()) {
          BigInteger min = te.explicitMinIncl().get();
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MIN_INCL);
            l.writeLiteral(min.toString());
          });
        }
        if (te.explicitMaxExcl().isPresent()) {
          BigInteger max = te.explicitMaxExcl().get();
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MAX_EXCL);
            l.writeLiteral(max.toString());
          });
        }
      }
      case StvnSchema.DateTimeSchema dt -> {
        if (dt.explicitMinInclIso().isPresent()) {
          String minIso = dt.explicitMinInclIso().get();
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MIN_INCL);
            l.writeSimpleString(minIso);
          });
        }
        if (dt.explicitMaxExclIso().isPresent()) {
          String maxIso = dt.explicitMaxExclIso().get();
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_MAX_EXCL);
            l.writeSimpleString(maxIso);
          });
        }
      }
      default -> {
        // No Tier-3 facets
      }
    }

    // Tier 4: Constraint / Lexical (4.1 .. 4.4)
    switch (schema) {
      case StvnSchema.EnumSubsetSchema sub -> {
        String filterKw = sub.inclusiveFilter()
            ? StvnVocabulary.FACET_KW_FILTER_INCL
            : StvnVocabulary.FACET_KW_FILTER_EXCL;
        List<String> filterList = sub.declaredFilterList();
        actions.add(l -> {
          l.writeLiteral(filterKw);
          l.openGroup(StvnVocabulary.DELIM_OPEN_BRACKET);
          for (String v : filterList) {
            l.writeLiteral(v);
          }
          layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACKET);
        });
      }
      case StvnSchema.StringSchema s -> {
        if (s.preserveIndent()) {
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_PRESERVE_INDENT);
            l.writeBoolean(true, PrinterOptions.SymbolStyle.SHORT_FORM);
          });
        }
        if (s.rawRegexPattern().isPresent()) {
          String rx = s.rawRegexPattern().get();
          actions.add(l -> {
            l.writeLiteral(StvnVocabulary.FACET_KW_REGEX);
            l.writeSimpleString(rx);
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
    for (FacetEmitter emitter : actions) {
      emitter.emit(layout);
    }
    layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACE);
  }

  private static void writeCanonicalValue(
      StvnValue value,
      CanonicalLayoutWriter layout
  ) throws IOException {
    switch (value) {
      case StvnValue.StvnBoolean b ->
          layout.writeBoolean(b.value(), PrinterOptions.SymbolStyle.SHORT_FORM);
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
        if (preserveIndent
            && text.indexOf('\r') < 0
            && (text.indexOf('\n') >= 0 || text.indexOf('\t') >= 0)) {
          String fenceTag = StvnLiteralParser.chooseSafeFenceTag(text);
          layout.writeLiteral("\"\"\"[" + fenceTag + "]\n" + text + "[" + fenceTag + "]\"\"\"");
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
      case StvnValue.StvnTuple tup -> {
        layout.openGroup(StvnVocabulary.DELIM_OPEN_PAREN);
        for (StvnValue el : tup.elements()) {
          writeCanonicalValue(el, layout);
        }
        layout.closeGroup(StvnVocabulary.DELIM_CLOSE_PAREN);
      }
      case StvnValue.StvnSeq seq -> {
        layout.openGroup(StvnVocabulary.DELIM_OPEN_BRACKET);
        for (StvnValue el : seq.elements()) {
          writeCanonicalValue(el, layout);
        }
        layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACKET);
      }
      case StvnValue.StvnSet set -> {
        layout.openGroup(StvnVocabulary.DELIM_OPEN_BRACKET);
        for (StvnValue el : set.elements()) {
          writeCanonicalValue(el, layout);
        }
        layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACKET);
      }
      case StvnValue.StvnMap map -> {
        layout.openGroup(StvnVocabulary.DELIM_OPEN_BRACE);
        for (KeyValueEntry entry : map.entries()) {
          layout.openGroup(StvnVocabulary.DELIM_OPEN_BRACKET);
          writeCanonicalValue(entry.key(), layout);
          writeCanonicalValue(entry.value(), layout);
          layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACKET);
        }
        layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACE);
      }
      case StvnValue.StvnOption opt -> {
        if (opt.value().isPresent()) {
          layout.openOptionSomeTag(PrinterOptions.SymbolStyle.SHORT_FORM);
          writeCanonicalValue(opt.value().get(), layout);
          layout.closeTag();
        } else {
          layout.writeOptionNone(PrinterOptions.SymbolStyle.SHORT_FORM);
        }
      }
      case StvnValue.StvnEither eith -> {
        boolean isRight = eith.branch() == EitherBranch.RIGHT;
        layout.openEitherTag(isRight, PrinterOptions.SymbolStyle.SHORT_FORM);
        writeCanonicalValue(eith.value(), layout);
        layout.closeTag();
      }
      case StvnValue.StvnUnion un -> {
        layout.writeLiteral(StvnVocabulary.SIGIL_VALUE + un.oneBasedBranchIndex().toString());
        writeCanonicalValue(un.value(), layout);
      }
      case StvnValue.StvnErrorValue err ->
          layout.writeSimpleString(err.diagnostic().message());
    }
  }

  @FunctionalInterface
  private interface FacetEmitter {
    void emit(CanonicalLayoutWriter layout) throws IOException;
  }
}
