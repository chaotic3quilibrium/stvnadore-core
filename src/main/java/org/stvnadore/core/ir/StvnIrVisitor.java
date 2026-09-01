package org.stvnadore.core.ir;

import org.antlr.v4.runtime.ParserRuleContext;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.ir.StvnValue.*;
import org.stvnadore.core.parser.StvnParser;
import org.stvnadore.core.parser.StvnParser.BodyEntryContext;
import org.stvnadore.core.parser.StvnParser.StvnDocumentContext;
import org.stvnadore.core.parser.StvnParserBaseVisitor;
import org.stvnadore.core.validation.MalformedPayloadException;
import org.stvnadore.core.validation.StvnIntegerOverflowException;
import org.stvnadore.core.validation.StvnTypeResolver;
import org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema;

import java.util.*;

/**
 * Visitor implementation that acts as the critical translation bridge transforming raw ANTLR parse tree contexts into
 * type-safe, validated {@link StvnValue} AST nodes.
 * <p>
 * This class traverses the parse tree, unwraps context structures, and isolates syntax errors from structural typing
 * anomalies using the document's resolved schema constraints.
 *
 * @since 1.0.0
 */
public class StvnIrVisitor extends StvnParserBaseVisitor<StvnValue> {

  private final StvnDocumentContext documentContext;
  private final org.stvnadore.core.validation.DiagnosticBag diagnosticBag;
  private final List<VariantStep> currentTrajectory = new ArrayList<>();

  /**
   * Constructs a new StvnIrVisitor using the provided document context and a default DiagnosticBag.
   *
   * @param documentContext the active parsed document context containing schemas and definitions
   */
  public StvnIrVisitor(StvnDocumentContext documentContext) {
    this(documentContext, new org.stvnadore.core.validation.DiagnosticBag());
  }

  /**
   * Constructs a new StvnIrVisitor using the provided document context and custom DiagnosticBag.
   *
   * @param documentContext the active parsed document context
   * @param diagnosticBag   the accumulator bag for recording semantic diagnostics
   */
  public StvnIrVisitor(StvnDocumentContext documentContext, org.stvnadore.core.validation.DiagnosticBag diagnosticBag) {
    this.documentContext = documentContext;
    this.diagnosticBag = diagnosticBag;
  }

  /**
   * Entry point to translate a body entry context into a validated, type-safe {@link StvnValue} AST.
   * <p>
   * First validates all document constraints recursively, then walks the value parse tree.
   *
   * @param bodyEntry       the parsed body entry AST node, or {@code null}
   * @param documentContext the active parsed document context
   * @return an {@link Optional} containing the constructed value, or empty if bodyEntry is null
   */
  public static Optional<StvnValue> build(@Nullable BodyEntryContext bodyEntry, StvnDocumentContext documentContext) {
    var bag = new org.stvnadore.core.validation.DiagnosticBag();
    var val = build(bodyEntry, documentContext, bag);
    if (bag.hasErrors()) {
      var first = bag.toList().getFirst();
      if (first.cause() instanceof RuntimeException re) {
        throw re;
      }
      throw new RuntimeException(first.message(), first.cause());
    }
    return val;
  }

  /**
   * Entry point to translate a body entry context into a validated, type-safe {@link StvnValue} AST accumulating
   * diagnostics into the provided {@link org.stvnadore.core.validation.DiagnosticBag}.
   *
   * @param bodyEntry       the parsed body entry AST node, or {@code null}
   * @param documentContext the active parsed document context
   * @param diagnosticBag   the accumulator bag for recording semantic diagnostics
   * @return an {@link Optional} containing the constructed value, or empty if bodyEntry is null
   */
  public static Optional<StvnValue> build(
      @Nullable BodyEntryContext bodyEntry,
      StvnDocumentContext documentContext,
      org.stvnadore.core.validation.DiagnosticBag diagnosticBag
  ) {
    StvnTypeResolver.validateDocumentConstraints(documentContext, diagnosticBag);
    return bodyEntry == null
        ? Optional.empty()
        : Optional.of(new StvnIrVisitor(documentContext, diagnosticBag).visit(bodyEntry.value()));
  }

  private int[] getLineCol(ParserRuleContext ctx) {
    if (ctx != null && ctx.getStart() != null) {
      return new int[]{ctx.getStart().getLine(), ctx.getStart().getCharPositionInLine()};
    }
    return new int[]{-1, -1};
  }

  private int getStartOffset(Throwable t, ParserRuleContext ctx) {
    if (t instanceof org.stvnadore.core.validation.StvnMalformedLiteralException e) return e.startOffset();
    if (t instanceof org.stvnadore.core.validation.StvnCollectionCollisionException e) return e.startOffset();
    if (t instanceof org.stvnadore.core.validation.MalformedPayloadException e) return e.startOffset();
    if (t instanceof org.stvnadore.core.validation.StvnIntegerOverflowException e) return e.startOffset();
    if (ctx != null && ctx.getStart() != null) return ctx.getStart().getStartIndex();
    return -1;
  }

  private int getEndOffset(Throwable t, ParserRuleContext ctx) {
    if (t instanceof org.stvnadore.core.validation.StvnMalformedLiteralException e) return e.endOffset();
    if (t instanceof org.stvnadore.core.validation.StvnCollectionCollisionException e) return e.endOffset();
    if (t instanceof org.stvnadore.core.validation.MalformedPayloadException e) return e.endOffset();
    if (t instanceof org.stvnadore.core.validation.StvnIntegerOverflowException e) return e.endOffset();
    if (ctx != null && ctx.getStop() != null) return ctx.getStop().getStopIndex() + 1;
    return -1;
  }

  private static boolean isValidValueContext(StvnParser.ValueContext ctx) {
    return ctx != null && (
        ctx.explicitOptionValue() != null
            || ctx.explicitEitherValue() != null
            || ctx.explicitUnionValue() != null
            || ctx.booleanLiteral() != null
            || ctx.integerLiteral() != null
            || ctx.floatLiteral() != null
            || ctx.stringLiteral() != null
            || ctx.valueKeyword() != null
            || ctx.collectionValue() != null
    );
  }

  private static boolean hasErrorNode(org.antlr.v4.runtime.tree.ParseTree tree) {
    if (tree == null) return false;
    if (tree instanceof org.antlr.v4.runtime.tree.ErrorNode) return true;
    for (int i = 0; i < tree.getChildCount(); i++) {
      if (hasErrorNode(tree.getChild(i))) return true;
    }
    return false;
  }

  private static boolean hasException(org.antlr.v4.runtime.tree.ParseTree tree) {
    if (tree == null) return false;
    if (tree instanceof org.antlr.v4.runtime.ParserRuleContext prc) {
      if (prc.exception != null) return true;
    }
    for (int i = 0; i < tree.getChildCount(); i++) {
      if (hasException(tree.getChild(i))) return true;
    }
    return false;
  }

  private static boolean isMalformedValueContext(StvnParser.ValueContext ctx) {
    if (ctx == null) return true;
    if (!isValidValueContext(ctx)) return true;
    return hasException(ctx) || hasErrorNode(ctx);
  }

  /**
   * Resolves the schema node context for the parsed value context and translates it into a {@link StvnValue}.
   *
   * @param ctx the value parse tree context
   * @return the resolved {@link StvnValue} AST node
   */
  @Override
  public StvnValue visitValue(StvnParser.ValueContext ctx) {
    if (isMalformedValueContext(ctx)) {
      throw new org.stvnadore.core.validation.MalformedAstContextException("Malformed or empty AST value context (parser recovery leak)");
    }
    ResolvedSchema schema = StvnTypeResolver.resolveSchemaNode(documentContext, ctx)
        .orElseThrow(() -> new org.stvnadore.core.validation.MalformedPayloadException("Unresolved schema for value context"));
    var val = visitValueWithSchema(ctx, schema);
    if (ctx.getParent() instanceof StvnParser.BodyEntryContext) {
      if (documentContext.documentBody() != null && documentContext.documentBody().typeEntry() != null) {
        var rootSchemaOpt = StvnTypeResolver.resolvePrimitiveSchema(documentContext, documentContext.documentBody().typeEntry().schemaType(), java.util.Set.of());
        if (rootSchemaOpt.isPresent()) {
          var rootSchema = rootSchemaOpt.get();
          if (!rootSchema.isPoisonedSentinel()) {
            val = wrapValueInRootSchema(val, rootSchema, ctx.start.getStartIndex(), ctx.stop.getStopIndex() + 1);
          }
        }
      }
    }
    return val;
  }

  /**
   * Traversal hook for intercepting value validation cycles and processing child values.
   * <p>
   * This routing hook allows subclasses to override how child AST value nodes are resolved and validated.
   *
   * @param ctx    the value parse tree context
   * @param schema the resolved schema mapping constraints for this value
   * @return the processed {@link StvnValue}
   */
  protected StvnValue visitChildValue(StvnParser.ValueContext ctx, ResolvedSchema schema) {
    return visitValueWithSchema(ctx, schema);
  }

  private StvnValue visitValueWithSchema(StvnParser.ValueContext ctx, ResolvedSchema schema) {
    if (isMalformedValueContext(ctx)) {
      throw new org.stvnadore.core.validation.MalformedAstContextException("Malformed or empty AST value context (parser recovery leak)");
    }
    if (schema.isPoisonedSentinel()) {
      return new StvnValue.StvnError(
          schema,
          ctx.getText(),
          ctx.getStart().getStartIndex(),
          ctx.getStop().getStopIndex() + 1,
          diagnosticBag.toList()
      );
    }
    checkKeywordClash(ctx, schema);

    String baseType = StvnTypeResolver.getPrimitiveBaseType(schema.node());
    if (baseType != null && baseType.equals(":Option")) {
      var token = ctx.start.getText();
      boolean isExplicitOption = ctx.explicitOptionValue() != null ||
          token.equals("#Some") || token.equals("#S") || token.equals("#None") || token.equals("#N");
      if (!isExplicitOption) {
        List<StvnParser.SchemaTypeContext> inner = StvnTypeResolver.getInnerSchemas(schema.node());
        if (!inner.isEmpty()) {
          ResolvedSchema baseInner = StvnTypeResolver.resolvePrimitiveSchema(documentContext, inner.getFirst(), Set.of())
              .orElseThrow(() -> new org.stvnadore.core.validation.MalformedPayloadException("Unresolved option inner schema"));
          ResolvedSchema innerSchema = new ResolvedSchema(
              baseInner.node(),
              baseInner.constraints(),
              baseInner.aliasName(),
              Optional.of(0),
              Optional.ofNullable(schema.node().schemaConstructor() != null
                  ? schema.node().schemaConstructor().sumType()
                  : null),
              baseInner.underlyingSchema(),
              baseInner.localConstraints()
          );
          currentTrajectory.add(new VariantStep("#Some", true));
          try {
            StvnValue innerVal = visitChildValue(ctx, innerSchema);
            if (innerVal == null) {
              throw new org.stvnadore.core.validation.MalformedPayloadException("Option inner value cannot be null");
            }
            return new StvnOption(schema, Optional.of(innerVal), List.copyOf(currentTrajectory));
          } catch (Exception e) {
            if (e instanceof org.stvnadore.core.validation.MalformedPayloadException) {
              throw e;
            }
            throw new org.stvnadore.core.validation.MalformedPayloadException("Failed to validate inner schema of Option", e);
          } finally {
            currentTrajectory.removeLast();
          }
        }
      }
    }

    schema = resolveImplicitSumCandidate(ctx, schema);

    var pushedImplicitEither = false;
    if (schema.implicitUnionTag().isPresent() && schema.sumTypeNode().isPresent()) {
      var sumTypeNode = schema.sumTypeNode().get();
      if (sumTypeNode.KW_EITHER() != null) {
        boolean isRight = (schema.implicitUnionTag().get() == 1);
        currentTrajectory.add(new VariantStep(isRight
            ? "#Right"
            : "#Left", true));
        pushedImplicitEither = true;
      }
    }

    try {
      baseType = StvnTypeResolver.getPrimitiveBaseType(schema.node());
      if (baseType == null) baseType = ":Undefined";

      String aliasOrBase = schema.aliasName().orElse(baseType);

      ResolvedSchema enumSchema = null;
      var textVal = ctx.valueKeyword() != null
          ? ctx.valueKeyword().getText()
          : ctx.start.getText();
      if (schema != null) {
        if (baseType.equals(":Enum")) {
          enumSchema = schema;
        } else if (baseType.equals(":Option") || baseType.equals(":Either") || baseType.equals(":Union")) {
          var candidates = StvnTypeResolver.resolveCandidateSchemas(documentContext, schema.node());
          for (var cand : candidates) {
            var candBase = StvnTypeResolver.getPrimitiveBaseType(cand.node());
            if (candBase != null && candBase.equals(":Enum")) {
              if (isValidEnumVariant(cand, textVal) && !isExplicitTagForSchema(textVal, schema)) {
                enumSchema = cand;
                break;
              }
            }
          }
        }
      }

      StvnValue rawValue;
      if (enumSchema != null) {
        rawValue = buildValueEnum(textVal, enumSchema, ctx);
      } else if (ctx.integerLiteral() != null) {
        verifyStructuralTypeMatch(baseType, "integer", ctx);
        rawValue = buildIntegerOrTime(ctx.integerLiteral(), schema, baseType, aliasOrBase);
      } else if (ctx.floatLiteral() != null) {
        verifyStructuralTypeMatch(baseType, "float", ctx);
        rawValue = buildFloat(ctx.floatLiteral(), schema, baseType, aliasOrBase);
      } else if (ctx.stringLiteral() != null) {
        verifyStructuralTypeMatch(baseType, "string", ctx);
        rawValue = buildStringOrTime(ctx.stringLiteral(), schema, baseType, aliasOrBase);
      } else if (ctx.booleanLiteral() != null) {
        verifyStructuralTypeMatch(baseType, "boolean", ctx);
        var text = ctx.booleanLiteral().getText();
        rawValue = new StvnBoolean(schema, text.equals("#TRUE") || text.equals("#T"));
      } else if (ctx.valueKeyword() != null) {
        rawValue = buildValueKeywordOrConstant(ctx.valueKeyword(), schema);
      } else if (ctx.collectionValue() != null) {
        if (ctx.collectionValue().listLiteral() != null)
          rawValue = buildList(ctx.collectionValue().listLiteral(), schema, baseType);
        else if (ctx.collectionValue().mapLiteral() != null)
          rawValue = buildMap(ctx.collectionValue().mapLiteral(), schema, baseType);
        else if (ctx.collectionValue().tupleLiteral() != null) {
          if (!baseType.equals(":Tuple")) {
            throw new MalformedPayloadException(
                "Type mismatch: Expected scalar (" + baseType + "), got Tuple (parenthesized product syntax is strictly reserved for :Tuple)",
                ctx.collectionValue().tupleLiteral().start.getStartIndex(),
                ctx.collectionValue().tupleLiteral().stop.getStopIndex() + 1
            );
          }
          rawValue = buildTuple(ctx.collectionValue().tupleLiteral(), schema);
        } else throw new IllegalStateException("Unknown collection value");
      } else if (ctx.explicitOptionValue() != null) {
        if (!baseType.equals(":Option")) {
          if (ctx.explicitOptionValue().value() == null) {
            rawValue = evaluateKeywordToken(ctx.explicitOptionValue().start.getText(), schema, ctx.explicitOptionValue());
          } else {
            throw new MalformedPayloadException(
                "Unexpected Option tag (#Some/#None), schema does not define an :Option. baseType='" + baseType + "', nodeText='" + (schema != null
                    ? schema.node().getText()
                    : "null") + "'",
                ctx.explicitOptionValue().start.getStartIndex(),
                ctx.explicitOptionValue().stop.getStopIndex() + 1
            );
          }
        } else {
          rawValue = buildOption(ctx.explicitOptionValue(), schema);
        }
      } else if (ctx.explicitEitherValue() != null) {
        if (!baseType.equals(":Either")) {
          if (ctx.explicitEitherValue().value() == null) {
            rawValue = evaluateKeywordToken(ctx.explicitEitherValue().start.getText(), schema, ctx.explicitEitherValue());
          } else {
            throw new MalformedPayloadException(
                "Unexpected Either tag (#Left/#Right), schema does not define an :Either. baseType='" + baseType + "', nodeText='" + (schema != null
                    ? schema.node().getText()
                    : "null") + "'",
                ctx.explicitEitherValue().start.getStartIndex(),
                ctx.explicitEitherValue().stop.getStopIndex() + 1
            );
          }
        } else {
          rawValue = buildEither(ctx.explicitEitherValue(), schema);
        }
      } else if (ctx.explicitUnionValue() != null) {
        if (!baseType.equals(":Union")) {
          if (ctx.explicitUnionValue().value() == null) {
            rawValue = evaluateKeywordToken(ctx.explicitUnionValue().UNION_TAG_PREFIX().getText(), schema, ctx.explicitUnionValue());
          } else {
            throw new MalformedPayloadException(
                "Unexpected Union tag (" + ctx.explicitUnionValue().UNION_TAG_PREFIX().getText() + "), schema does not define a :Union. baseType='" + baseType + "', nodeText='" + (schema != null
                    ? schema.node().getText()
                    : "null") + "'",
                ctx.explicitUnionValue().start.getStartIndex(),
                ctx.explicitUnionValue().stop.getStopIndex() + 1
            );
          }
        } else {
          rawValue = buildUnion(ctx.explicitUnionValue(), schema);
        }
      } else throw new IllegalStateException("Unexpected STVN node type");

      return wrapImplicitSum(rawValue, schema);
    } finally {
      if (pushedImplicitEither) {
        currentTrajectory.removeLast();
      }
    }
  }

  private void verifyStructuralTypeMatch(String baseType, String got, StvnParser.ValueContext ctx) {
    if (baseType.equals(":Option") || baseType.equals(":Either") || baseType.equals(":Union"))
      return;
    var expected = "unknown";
    if (isIntType(baseType) || isTimeEpochType(baseType)) expected = "integer";
    else if (isFloatType(baseType)) expected = "float";
    else if (isStringType(baseType) || isDateTimeType(baseType)) expected = "string";
    else if (baseType.equals(":Boolean")) expected = "boolean";
    else if (baseType.equals(":Tuple")) expected = "tuple";

    if (!expected.equals("unknown") && !expected.equals(got)) {
      throw new MalformedPayloadException(
          "Type mismatch: Expected " + expected + ", got " + got,
          ctx.getStart().getStartIndex(),
          ctx.getStop().getStopIndex() + 1
      );
    }
  }

  private String unwrapValue(StvnValue val) {
    return switch (val) {
      case StvnInteger i -> i.value().toString();
      case StvnFloat f -> f.value().toPlainString();
      case StvnString s -> s.value();
      case StvnBoolean b -> String.valueOf(b.value());
      case StvnTime t -> t.value().toString();
      case StvnDateTimeOffset dto -> dto.value().toString();
      case StvnDateTimeZoned dtz -> dtz.localDateTime().toString() + "[" + dtz.zoneId().getId() + "]";
      case StvnDateTimeAudited dta -> dta.offsetDateTime().toString() + "[" + dta.zoneId().getId() + "]";
      default -> val.toString();
    };
  }

  private StvnValue buildIntegerOrTime(StvnParser.IntegerLiteralContext ctx, ResolvedSchema schema, String baseType, String aliasOrBase) {
    var rawValue = StvnLiteralParser.parseBigInteger(ctx.getText());
    if (baseType.equals(":TimeEpoch") || baseType.equals(":TimeEpochS") || baseType.equals(":TimeEpochMs") || baseType.equals(":TimeEpochNs")) {
      var kind = switch (baseType) {
        case ":TimeEpochS" -> TimeKind.EPOCH_S;
        case ":TimeEpochMs" -> TimeKind.EPOCH_MS;
        default -> TimeKind.EPOCH_NS;
      };
      return new StvnTime(schema, rawValue, kind);
    }
    var isUnsigned = baseType.equals(":Uint") || (baseType.startsWith(":Uint") && isNumeric(baseType.substring(5)));
    var bitWidth = 32;
    if (baseType.startsWith(":Int") && baseType.length() > 4) {
      var suffix = baseType.substring(4);
      if (isNumeric(suffix)) {
        bitWidth = Integer.parseInt(suffix);
      }
    } else if (baseType.startsWith(":Uint") && baseType.length() > 5) {
      var suffix = baseType.substring(5);
      if (isNumeric(suffix)) {
        bitWidth = Integer.parseInt(suffix);
      }
    } else if (!baseType.equals(":Int") && !baseType.equals(":Uint")) {
      bitWidth = 0;
    }

    // VALIDATE BOUNDS NOW (as requested)
    if (bitWidth > 0) {
      java.math.BigInteger min;
      java.math.BigInteger max;
      if (isUnsigned) {
        min = java.math.BigInteger.ZERO;
        max = java.math.BigInteger.ONE.shiftLeft(bitWidth).subtract(java.math.BigInteger.ONE);
      } else {
        min = java.math.BigInteger.ONE.shiftLeft(bitWidth - 1).negate();
        max = java.math.BigInteger.ONE.shiftLeft(bitWidth - 1).subtract(java.math.BigInteger.ONE);
      }
      if (rawValue.compareTo(min) < 0 || rawValue.compareTo(max) > 0) {
        throw new StvnIntegerOverflowException(
            "Value [" + rawValue + "] is out of bounds for " + aliasOrBase + " (" + min + " to " + max + ")",
            ctx.getStart().getStartIndex(),
            ctx.getStop().getStopIndex() + 1
        );
      }
    }

    if (schema != null && schema.constraints() != null) {
      var c = schema.constraints();
      var bdRaw = new java.math.BigDecimal(rawValue);
      c.minIncl().ifPresent(minIncl -> {
        if (bdRaw.compareTo(minIncl) < 0) {
          throw new MalformedPayloadException(
              "Constraint violation (" + aliasOrBase + "): Value must be greater than or equal to " + minIncl,
              ctx.getStart().getStartIndex(),
              ctx.getStop().getStopIndex() + 1
          );
        }
      });
      c.minExcl().ifPresent(minExcl -> {
        if (bdRaw.compareTo(minExcl) <= 0) {
          throw new MalformedPayloadException(
              "Constraint violation (" + aliasOrBase + "): Value must be strictly greater than " + minExcl,
              ctx.getStart().getStartIndex(),
              ctx.getStop().getStopIndex() + 1
          );
        }
      });
      c.maxIncl().ifPresent(maxIncl -> {
        if (bdRaw.compareTo(maxIncl) > 0) {
          throw new MalformedPayloadException(
              "Constraint violation (" + aliasOrBase + "): Value must be less than or equal to " + maxIncl,
              ctx.getStart().getStartIndex(),
              ctx.getStop().getStopIndex() + 1
          );
        }
      });
      c.maxExcl().ifPresent(maxExcl -> {
        if (bdRaw.compareTo(maxExcl) >= 0) {
          throw new MalformedPayloadException(
              "Constraint violation (" + aliasOrBase + "): Value must be strictly less than " + maxExcl,
              ctx.getStart().getStartIndex(),
              ctx.getStop().getStopIndex() + 1
          );
        }
      });
    }

    return new StvnInteger(schema, rawValue, bitWidth, isUnsigned);
  }

  private StvnFloat buildFloat(StvnParser.FloatLiteralContext ctx, ResolvedSchema schema, String baseType, String aliasOrBase) {
    FloatPrecision precision = switch (baseType) {
      case ":Float32" -> FloatPrecision.FLOAT32;
      case ":FloatExact" -> FloatPrecision.EXACT;
      default -> FloatPrecision.FLOAT64;
    };
    var rawVal = StvnLiteralParser.parseFloat(ctx.getText());
    // Basic bounds checking for Float64 overflow/underflow if requested
    if (precision == FloatPrecision.FLOAT64) {
      if (rawVal.compareTo(java.math.BigDecimal.valueOf(Double.MAX_VALUE)) > 0 ||
          rawVal.compareTo(java.math.BigDecimal.valueOf(-Double.MAX_VALUE)) < 0) {
        throw new MalformedPayloadException(
            "Value [" + rawVal + "] is out of bounds for " + aliasOrBase + " (-1.7976931348623157E+308 to 1.7976931348623157E+308)",
            ctx.getStart().getStartIndex(),
            ctx.getStop().getStopIndex() + 1
        );
      }
    }

    if (schema != null && schema.constraints() != null) {
      var c = schema.constraints();
      c.minIncl().ifPresent(minIncl -> {
        if (rawVal.compareTo(minIncl) < 0) {
          throw new MalformedPayloadException(
              "Constraint violation (" + aliasOrBase + "): Value must be greater than or equal to " + minIncl,
              ctx.getStart().getStartIndex(),
              ctx.getStop().getStopIndex() + 1
          );
        }
      });
      c.minExcl().ifPresent(minExcl -> {
        if (rawVal.compareTo(minExcl) <= 0) {
          throw new MalformedPayloadException(
              "Constraint violation (" + aliasOrBase + "): Value must be strictly greater than " + minExcl,
              ctx.getStart().getStartIndex(),
              ctx.getStop().getStopIndex() + 1
          );
        }
      });
      c.maxIncl().ifPresent(maxIncl -> {
        if (rawVal.compareTo(maxIncl) > 0) {
          throw new MalformedPayloadException(
              "Constraint violation (" + aliasOrBase + "): Value must be less than or equal to " + maxIncl,
              ctx.getStart().getStartIndex(),
              ctx.getStop().getStopIndex() + 1
          );
        }
      });
      c.maxExcl().ifPresent(maxExcl -> {
        if (rawVal.compareTo(maxExcl) >= 0) {
          throw new MalformedPayloadException(
              "Constraint violation (" + aliasOrBase + "): Value must be strictly less than " + maxExcl,
              ctx.getStart().getStartIndex(),
              ctx.getStop().getStopIndex() + 1
          );
        }
      });
    }

    return new StvnFloat(schema, rawVal, precision);
  }

  private StvnValue buildStringOrTime(StvnParser.StringLiteralContext ctx, ResolvedSchema schema, String baseType, String aliasOrBase) {
    var preserveIndent = schema.constraints().preserveIndent();
    var parsedString = StvnLiteralParser.parseStringNew(ctx.getText(), preserveIndent);

    if (isDateTimeType(baseType)) {
      return buildDateTime(ctx, schema, baseType, aliasOrBase);
    }

    // 1. Precise String Typology Classification
    boolean isFixed = baseType.startsWith(":StringFixed") && isNumeric(baseType.substring(12));
    boolean isNonEmpty = baseType.equals(":StringNonEmpty") || (baseType.startsWith(":StringNonEmpty") && isNumeric(baseType.substring(15)));
    boolean isBounded = baseType.startsWith(":String") && !baseType.startsWith(":StringFixed") && !baseType.startsWith(":StringNonEmpty") && isNumeric(baseType.substring(7));

    int fixedLength = 0;
    int maxLength = 0;

    if (isFixed) {
      fixedLength = Integer.parseInt(baseType.substring(12));
    } else if (isNonEmpty && baseType.length() > 15) {
      maxLength = Integer.parseInt(baseType.substring(15));
    } else if (isBounded && baseType.length() > 7) {
      maxLength = Integer.parseInt(baseType.substring(7));
    }

    int textLength = parsedString.text().length();

    // 2. Non-Emptiness Constraint Check (1 <= len)
    if (isNonEmpty && textLength == 0) {
      throw new MalformedPayloadException(
          "Constraint violation (" + aliasOrBase + "): String cannot be empty",
          ctx.getStart().getStartIndex(),
          ctx.getStop().getStopIndex() + 1
      );
    }

    // 3. Exact Fixed-Length Constraint Check (len == N)
    if (fixedLength > 0 && textLength != fixedLength) {
      throw new MalformedPayloadException(
          "Constraint violation (" + aliasOrBase + "): Fixed string must be exactly " + fixedLength + " characters long, got " + textLength,
          ctx.getStart().getStartIndex(),
          ctx.getStop().getStopIndex() + 1
      );
    }

    // 4. Max-Bounded Constraint Check (len <= N for :StringN and :StringNonEmptyN)
    if (maxLength > 0 && textLength > maxLength) {
      throw new MalformedPayloadException(
          "Constraint violation (" + aliasOrBase + "): String length exceeds maximum length of " + maxLength + " characters, got " + textLength,
          ctx.getStart().getStartIndex(),
          ctx.getStop().getStopIndex() + 1
      );
    }

    // 5. Global Baseline Allocation Limit Check (len <= 16,777,216)
    if (fixedLength == 0 && maxLength == 0 && textLength > 16_777_216) {
      throw new MalformedPayloadException(
          "Constraint violation (" + aliasOrBase + "): String length exceeds maximum allocation size of 16777216",
          ctx.getStart().getStartIndex(),
          ctx.getStop().getStopIndex() + 1
      );
    }

    if (schema.constraints() != null) {
      var c = schema.constraints();
      c.regex().ifPresent(regex -> {
        if (!java.util.regex.Pattern.compile(regex).matcher(parsedString.text()).matches()) {
          throw new MalformedPayloadException(
              "Constraint violation (" + aliasOrBase + "): String does not match required pattern: " + regex,
              ctx.getStart().getStartIndex(),
              ctx.getStop().getStopIndex() + 1
          );
        }
      });
    }

    var extractedTrait = new StringTrait(fixedLength, maxLength, isNonEmpty);
    return new StvnString(schema, parsedString.text(), parsedString.style(), parsedString.optionalFenceTag(), extractedTrait);
  }

  private StvnValue buildDateTime(StvnParser.StringLiteralContext ctx, ResolvedSchema schema, String baseType, String aliasOrBase) {
    var rawText = ctx.getText();
    var startIndex = ctx.getStart().getStartIndex();
    var stopIndex = ctx.getStop().getStopIndex() + 1;

    switch (baseType) {
      case ":DateTimeOffset" -> {
        if (rawText.contains("[")) {
          throw new MalformedPayloadException(
              "Time zone brackets [...] are prohibited in :DateTimeOffset (e.g., use :DateTimeAudited for offset+zone or :DateTimeZoned for pure zone)",
              startIndex,
              stopIndex
          );
        }
        try {
          var parsed = StvnLiteralParser.parseDateTimeOffset(rawText);
          return new StvnDateTimeOffset(schema, parsed.value());
        } catch (Exception e) {
          throw new MalformedPayloadException(
              "Invalid OffsetDateTime format (e.g., 2026-03-06T15:53:08-06:00)",
              startIndex,
              stopIndex,
              e
          );
        }
      }

      case ":DateTimeZoned" -> {
        if (!rawText.contains("[")) {
          throw new MalformedPayloadException(
              "Invalid ZonedDateTime format. Must include a Region/City zone ID (e.g., ...[Europe/Paris])",
              startIndex,
              stopIndex
          );
        }
        if (StvnLiteralParser.DATETIME_AUDITED_PATTERN.matcher(rawText).matches()) {
          throw new MalformedPayloadException(
              "Explicit offsets (±HH:mm or Z) are prohibited in :DateTimeZoned; use :DateTimeAudited to specify both offset and zone.",
              startIndex,
              stopIndex
          );
        }
        StvnLiteralParser.ParsedDateTimeZoned parsed;
        try {
          parsed = StvnLiteralParser.parseDateTimeZoned(rawText);
        } catch (Exception e) {
          throw new MalformedPayloadException(
              "Invalid ZonedDateTime format. Must include a Region/City zone ID (e.g., ...[Europe/Paris])",
              startIndex,
              stopIndex,
              e
          );
        }

        var rules = parsed.zoneId().getRules();
        var validOffsets = rules.getValidOffsets(parsed.localDateTime());
        if (validOffsets.isEmpty()) {
          var transition = rules.getTransition(parsed.localDateTime());
          throw new MalformedPayloadException(
              "Invalid civil time: '" + parsed.localDateTime() + "' falls into a DST spring-forward gap in zone '" + parsed.zoneId() + "' (" + transition + ").",
              startIndex,
              stopIndex
          );
        }

        return new StvnDateTimeZoned(schema, parsed.localDateTime(), parsed.zoneId());
      }

      case ":DateTimeAudited" -> {
        if (!rawText.contains("[") || (!rawText.contains("+") && !rawText.contains("-") && !rawText.contains("Z"))) {
          throw new MalformedPayloadException(
              "Invalid :DateTimeAudited literal. Mandates both an explicit UTC offset and an IANA zone ID (e.g., \"2026-03-15T08:00:00-05:00[America/Chicago]\").",
              startIndex,
              stopIndex
          );
        }
        StvnLiteralParser.ParsedDateTimeAudited parsed;
        try {
          parsed = StvnLiteralParser.parseDateTimeAudited(rawText);
        } catch (Exception e) {
          throw new MalformedPayloadException(
              "Invalid :DateTimeAudited format. Expected \"YYYY-MM-DDTHH:mm:ss±HH:mm[Region/City]\": " + e.getMessage(),
              startIndex,
              stopIndex,
              e
          );
        }

        var rules = parsed.zoneId().getRules();
        var localTime = parsed.offsetDateTime().toLocalDateTime();
        var validOffsets = rules.getValidOffsets(localTime);
        if (validOffsets.isEmpty()) {
          var transition = rules.getTransition(localTime);
          throw new MalformedPayloadException(
              "Invalid civil time: '" + localTime + "' falls into a DST spring-forward gap in zone '" + parsed.zoneId() + "' (" + transition + ").",
              startIndex,
              stopIndex
          );
        }

        var declaredOffset = parsed.offsetDateTime().getOffset();
        if (!validOffsets.contains(declaredOffset)) {
          throw new MalformedPayloadException(
              "Contradictory offset in :DateTimeAudited literal. Declared offset '" + declaredOffset + "' does not match valid offset(s) " + validOffsets + " for '" + localTime + "' in zone '" + parsed.zoneId() + "'.",
              startIndex,
              stopIndex
          );
        }

        return new StvnDateTimeAudited(schema, parsed.offsetDateTime(), parsed.zoneId());
      }

      case ":DateTime" -> {
        if (rawText.contains("[")) {
          if (StvnLiteralParser.DATETIME_AUDITED_PATTERN.matcher(rawText).matches()) {
            return buildDateTime(ctx, schema, ":DateTimeAudited", aliasOrBase);
          } else {
            return buildDateTime(ctx, schema, ":DateTimeZoned", aliasOrBase);
          }
        } else {
          return buildDateTime(ctx, schema, ":DateTimeOffset", aliasOrBase);
        }
      }

      default -> throw new IllegalStateException("Unexpected temporal type: " + baseType);
    }
  }

  private sealed interface StreamItem permits OptionTagItem, EitherTagItem, UnionTagItem, KeywordTokenItem, BooleanLiteralItem, AtomicValueItem {
    @Nullable ParserRuleContext sourceCtx();
  }

  private record OptionTagItem(String tag, StvnParser.@Nullable ValueContext childValue,
                               @Nullable ParserRuleContext sourceCtx) implements StreamItem {
  }

  private record EitherTagItem(String tag, StvnParser.@Nullable ValueContext childValue,
                               @Nullable ParserRuleContext sourceCtx) implements StreamItem {
  }

  private record UnionTagItem(String tag, StvnParser.@Nullable ValueContext childValue,
                              @Nullable ParserRuleContext sourceCtx) implements StreamItem {
  }

  private record KeywordTokenItem(String tokenText, @Nullable ParserRuleContext sourceCtx) implements StreamItem {
  }

  private record BooleanLiteralItem(String tokenText, @Nullable ParserRuleContext sourceCtx) implements StreamItem {
  }

  private record AtomicValueItem(StvnParser.ValueContext ctx) implements StreamItem {
    @Override
    public @Nullable ParserRuleContext sourceCtx() {
      return ctx;
    }
  }

  private StreamItem toStreamItem(StvnParser.ValueContext ctx) {
    if (ctx.explicitOptionValue() != null) {
      return new OptionTagItem(ctx.explicitOptionValue().start.getText(), ctx.explicitOptionValue().value(), ctx.explicitOptionValue());
    }
    if (ctx.explicitEitherValue() != null) {
      return new EitherTagItem(ctx.explicitEitherValue().start.getText(), ctx.explicitEitherValue().value(), ctx.explicitEitherValue());
    }
    if (ctx.explicitUnionValue() != null) {
      return new UnionTagItem(ctx.explicitUnionValue().UNION_TAG_PREFIX().getText(), ctx.explicitUnionValue().value(), ctx.explicitUnionValue());
    }
    if (ctx.valueKeyword() != null) {
      return new KeywordTokenItem(ctx.valueKeyword().getText(), ctx.valueKeyword());
    }
    if (ctx.booleanLiteral() != null) {
      return new BooleanLiteralItem(ctx.booleanLiteral().getText(), ctx.booleanLiteral());
    }
    return new AtomicValueItem(ctx);
  }

  private record ItemTypeInfo(StvnTypeResolver.LiteralType valType, @Nullable String literalText) {
  }

  private ItemTypeInfo getItemTypeInfo(StreamItem item) {
    if (item instanceof KeywordTokenItem kw) {
      return new ItemTypeInfo(StvnTypeResolver.LiteralType.KEYWORD_LITERAL, kw.tokenText());
    }
    if (item instanceof BooleanLiteralItem b) {
      return new ItemTypeInfo(StvnTypeResolver.LiteralType.BOOLEAN_LITERAL, b.tokenText());
    }
    if (item instanceof OptionTagItem) {
      return new ItemTypeInfo(StvnTypeResolver.LiteralType.EXPLICIT_OPTION_VALUE, null);
    }
    if (item instanceof EitherTagItem) {
      return new ItemTypeInfo(StvnTypeResolver.LiteralType.EXPLICIT_EITHER_VALUE, null);
    }
    if (item instanceof UnionTagItem) {
      return new ItemTypeInfo(StvnTypeResolver.LiteralType.EXPLICIT_UNION_VALUE, null);
    }
    if (item instanceof AtomicValueItem atom) {
      var ctx = atom.ctx();
      if (ctx.integerLiteral() != null) return new ItemTypeInfo(StvnTypeResolver.LiteralType.INTEGER_LITERAL, null);
      if (ctx.floatLiteral() != null) return new ItemTypeInfo(StvnTypeResolver.LiteralType.FLOAT_LITERAL, null);
      if (ctx.stringLiteral() != null) return new ItemTypeInfo(StvnTypeResolver.LiteralType.STRING_LITERAL, null);
      if (ctx.booleanLiteral() != null)
        return new ItemTypeInfo(StvnTypeResolver.LiteralType.BOOLEAN_LITERAL, ctx.booleanLiteral().getText());
      if (ctx.explicitOptionValue() != null)
        return new ItemTypeInfo(StvnTypeResolver.LiteralType.EXPLICIT_OPTION_VALUE, null);
      if (ctx.explicitEitherValue() != null)
        return new ItemTypeInfo(StvnTypeResolver.LiteralType.EXPLICIT_EITHER_VALUE, null);
      if (ctx.explicitUnionValue() != null)
        return new ItemTypeInfo(StvnTypeResolver.LiteralType.EXPLICIT_UNION_VALUE, null);
      if (ctx.collectionValue() != null) {
        if (ctx.collectionValue().listLiteral() != null)
          return new ItemTypeInfo(StvnTypeResolver.LiteralType.LIST_LITERAL, null);
        if (ctx.collectionValue().mapLiteral() != null)
          return new ItemTypeInfo(StvnTypeResolver.LiteralType.MAP_LITERAL, null);
        if (ctx.collectionValue().tupleLiteral() != null)
          return new ItemTypeInfo(StvnTypeResolver.LiteralType.TUPLE_LITERAL, null);
      }
      if (ctx.valueKeyword() != null)
        return new ItemTypeInfo(StvnTypeResolver.LiteralType.KEYWORD_LITERAL, ctx.valueKeyword().getText());
    }
    return new ItemTypeInfo(StvnTypeResolver.LiteralType.UNKNOWN, null);
  }

  private boolean isEnumSchema(ResolvedSchema schema) {
    if (schema == null || schema.node() == null) return false;
    String baseType = StvnTypeResolver.getPrimitiveBaseType(schema.node());
    if (baseType != null && baseType.equals(":Enum")) return true;
    var ctor = schema.node().schemaConstructor();
    if (ctor == null && schema.underlyingSchema().isPresent()) {
      var curr = schema.underlyingSchema().get();
      while (curr != null) {
        if (curr.node() != null && curr.node().schemaConstructor() != null) {
          ctor = curr.node().schemaConstructor();
          break;
        }
        curr = curr.underlyingSchema().orElse(null);
      }
    }
    return ctor != null && ctor.sumType() != null && ctor.sumType().KW_ENUM() != null;
  }

  private StvnValue evaluateKeywordToken(String text, ResolvedSchema schema, @Nullable ParserRuleContext ctx) {
    int start = ctx != null && ctx.getStart() != null
        ? ctx.getStart().getStartIndex()
        : -1;
    int end = ctx != null && ctx.getStop() != null
        ? ctx.getStop().getStopIndex() + 1
        : -1;

    String baseType = (schema != null && schema.node() != null)
        ? StvnTypeResolver.getPrimitiveBaseType(schema.node())
        : ":Undefined";
    if (baseType == null) baseType = ":Undefined";

    // 1. Dimension 1: Target :Enum
    if (baseType.equals(":Enum") || isEnumSchema(schema)) {
      return buildValueEnum(text, schema, ctx);
    }

    // 2. Boolean Literals
    if (baseType.equals(":Boolean")) {
      if (text.equals("#TRUE") || text.equals("#T")) {
        return new StvnBoolean(schema, true);
      } else if (text.equals("#FALSE") || text.equals("#F")) {
        return new StvnBoolean(schema, false);
      }
    }

    // 3. Option Unit Tag (#None / #N)
    if (baseType.equals(":Option") && (text.equals("#None") || text.equals("#N"))) {
      currentTrajectory.add(new VariantStep("#None", false));
      try {
        return new StvnOption(schema, Optional.empty(), List.copyOf(currentTrajectory));
      } finally {
        currentTrajectory.removeLast();
      }
    }

    // 4. Dimension 2, 3, 4: Typed Constant Lookup in :defs
    var constDefOpt = StvnTypeResolver.findConstantDefinition(documentContext, text);
    if (constDefOpt.isPresent()) {
      var constDef = constDefOpt.get();
      if (!baseType.equals(":Option") && !baseType.equals(":Either") && !baseType.equals(":Union")) {
        return visitChildValue(constDef.value(), schema);
      } else if (baseType.equals(":Option")) {
        List<StvnParser.SchemaTypeContext> inner = StvnTypeResolver.getInnerSchemas(schema.node());
        if (!inner.isEmpty()) {
          ResolvedSchema innerSchema = StvnTypeResolver.resolvePrimitiveSchema(documentContext, inner.getFirst(), Set.of())
              .orElseThrow(() -> new MalformedPayloadException("Unresolved option inner schema", start, end));
          currentTrajectory.add(new VariantStep("#Some", true));
          try {
            StvnValue innerVal = visitChildValue(constDef.value(), innerSchema);
            return new StvnOption(schema, Optional.of(innerVal), List.copyOf(currentTrajectory));
          } finally {
            currentTrajectory.removeLast();
          }
        }
      } else if (baseType.equals(":Either")) {
        List<ResolvedSchema> candidates = StvnTypeResolver.resolveCandidateSchemas(documentContext, schema.node());
        if (candidates.size() >= 2) {
          ResolvedSchema leftSchema = candidates.get(0);
          ResolvedSchema rightSchema = candidates.get(1);

          boolean leftMatches = StvnTypeResolver.canMatch(documentContext, leftSchema.node(), StvnTypeResolver.LiteralType.KEYWORD_LITERAL, text);
          boolean rightMatches = StvnTypeResolver.canMatch(documentContext, rightSchema.node(), StvnTypeResolver.LiteralType.KEYWORD_LITERAL, text);

          if (leftMatches && rightMatches) {
            throw new MalformedPayloadException(
                "Ambiguous implicit resolution: Value matches both Left and Right branches of :Either",
                start, end
            );
          }
          if (rightMatches) {
            currentTrajectory.add(new VariantStep("#Right", true));
            try {
              StvnValue rightVal = visitChildValue(constDef.value(), rightSchema);
              return new StvnEither(schema, rightVal, true, false, List.copyOf(currentTrajectory));
            } finally {
              currentTrajectory.removeLast();
            }
          }
          if (leftMatches) {
            throw new MalformedPayloadException(
                "Rule E Violation: Untagged value matching Left branch of :Either is non-inferable; explicit #Left tag is required",
                start, end
            );
          }
        }
        throw new MalformedPayloadException("Constant '" + text + "' does not match branches of :Either", start, end);
      } else if (baseType.equals(":Union")) {
        List<ResolvedSchema> candidates = StvnTypeResolver.resolveCandidateSchemas(documentContext, schema.node());
        int matchCount = 0;
        int matchedIndex = -1;
        ResolvedSchema matchedCand = null;

        for (int i = 0; i < candidates.size(); i++) {
          ResolvedSchema cand = candidates.get(i);
          if (StvnTypeResolver.canMatch(documentContext, cand.node(), StvnTypeResolver.LiteralType.KEYWORD_LITERAL, text)) {
            matchCount++;
            matchedIndex = i;
            matchedCand = cand;
          }
        }

        if (matchCount > 1) {
          throw new org.stvnadore.core.validation.StvnCollectionCollisionException(
              "Ambiguous implicit resolution: Value matches multiple branches", start, end
          );
        }
        if (matchCount == 1 && matchedCand != null) {
          StvnValue branchVal = visitChildValue(constDef.value(), matchedCand);
          return new StvnUnion(schema, branchVal, matchedIndex);
        }
        throw new MalformedPayloadException("Constant '" + text + "' does not match any branch of :Union", start, end);
      }
    }

    if (baseType.equals(":Boolean")) {
      throw new org.stvnadore.core.validation.StvnMalformedLiteralException(
          "Invalid boolean literal casing: found '" + text + "', expected exactly '#TRUE', '#T', '#FALSE', or '#F'.", start, end);
    }

    throw new MalformedPayloadException(
        "Undeclared value keyword or constant: '" + text + "'. Value keywords in payload position must match an active :Enum variant or a declared constant in :defs.",
        start, end
    );
  }

  private StvnValue buildValueKeywordOrConstant(StvnParser.ValueKeywordContext ctx, ResolvedSchema schema) {
    return evaluateKeywordToken(ctx.getText(), schema, ctx);
  }

  private StvnValue buildValueEnum(String keywordText, ResolvedSchema schema, @Nullable ParserRuleContext ctx) {
    int sequentialIndex = 0;
    int variantCount = 0;

    var ctor = schema.node().schemaConstructor();
    if (ctor == null && schema.underlyingSchema().isPresent()) {
      var curr = schema.underlyingSchema().get();
      while (curr != null) {
        if (curr.node() != null && curr.node().schemaConstructor() != null) {
          ctor = curr.node().schemaConstructor();
          break;
        }
        curr = curr.underlyingSchema().orElse(null);
      }
    }
    if (ctor != null && ctor.sumType() != null && ctor.sumType().KW_ENUM() != null) {
      var enumDef = ctor.sumType().enumDef();
      int currentIndex = 0;
      for (var childKw : enumDef.valueKeyword()) {
        variantCount++;
        if (childKw.getText().equals(keywordText)) {
          sequentialIndex = currentIndex;
        }
        currentIndex++;
      }
      if (variantCount > 0 && sequentialIndex == 0 && !enumDef.valueKeyword(0).getText().equals(keywordText)) {
        List<String> allowed = new ArrayList<>();
        for (var k : enumDef.valueKeyword()) allowed.add(k.getText());
        int start = ctx != null && ctx.getStart() != null
            ? ctx.getStart().getStartIndex()
            : -1;
        int end = ctx != null && ctx.getStop() != null
            ? ctx.getStop().getStopIndex() + 1
            : -1;
        throw new MalformedPayloadException(
            "Invalid enum value: expected one of " + allowed + ", got " + keywordText,
            start,
            end
        );
      }
    }
    return new StvnEnum(schema, keywordText, sequentialIndex, variantCount);
  }

  private StvnValue evaluateNextItem(Deque<StreamItem> queue, ResolvedSchema schema) {
    if (queue.isEmpty()) {
      throw new MalformedPayloadException("Unexpected end of value stream");
    }

    StreamItem peekItem = queue.getFirst();
    if (peekItem instanceof OptionTagItem opt && opt.childValue() == null) {
      checkKeywordClash(opt.tag(), schema);
    } else if (peekItem instanceof EitherTagItem either && either.childValue() == null) {
      checkKeywordClash(either.tag(), schema);
    } else if (peekItem instanceof KeywordTokenItem kw) {
      checkKeywordClash(kw.tokenText(), schema);
    }

    String baseType = (schema != null && schema.node() != null)
        ? StvnTypeResolver.getPrimitiveBaseType(schema.node())
        : ":Undefined";
    if (schema != null && schema.underlyingSchema().isPresent()) {
      var curr = schema.underlyingSchema().get();
      while (curr != null) {
        if (curr.node() != null) {
          String bt = StvnTypeResolver.getPrimitiveBaseType(curr.node());
          if (bt != null && !bt.equals(":Undefined")) {
            baseType = bt;
            break;
          }
        }
        curr = curr.underlyingSchema().orElse(null);
      }
    }
    if (baseType == null) baseType = ":Undefined";

    // If schema is Enum, Dimension 1 applies:
    if (baseType.equals(":Enum") || isEnumSchema(schema)) {
      StreamItem item = queue.removeFirst();
      if (item instanceof OptionTagItem optTag) {
        if (optTag.childValue() != null) {
          queue.addFirst(toStreamItem(optTag.childValue()));
        }
        return buildValueEnum(optTag.tag(), schema, optTag.sourceCtx());
      }
      if (item instanceof EitherTagItem eitherTag) {
        if (eitherTag.childValue() != null) {
          queue.addFirst(toStreamItem(eitherTag.childValue()));
        }
        return buildValueEnum(eitherTag.tag(), schema, eitherTag.sourceCtx());
      }
      if (item instanceof UnionTagItem unionTag) {
        if (unionTag.childValue() != null) {
          queue.addFirst(toStreamItem(unionTag.childValue()));
        }
        return buildValueEnum(unionTag.tag(), schema, unionTag.sourceCtx());
      }
      if (item instanceof KeywordTokenItem kw) {
        return buildValueEnum(kw.tokenText(), schema, kw.sourceCtx());
      }
      if (item instanceof BooleanLiteralItem b) {
        return buildValueEnum(b.tokenText(), schema, b.sourceCtx());
      }
      if (item instanceof AtomicValueItem atom) {
        return visitChildValue(atom.ctx(), schema);
      }
    }

    StreamItem item = queue.removeFirst();

    // 1. Target Schema is :Option(T)
    if (baseType.equals(":Option")) {
      if (item instanceof KeywordTokenItem kw && (kw.tokenText().equals("#None") || kw.tokenText().equals("#N"))) {
        currentTrajectory.add(new VariantStep("#None", false));
        try {
          return new StvnOption(schema, Optional.empty(), List.copyOf(currentTrajectory));
        } finally {
          currentTrajectory.removeLast();
        }
      }
      if (item instanceof OptionTagItem optTag) {
        if (optTag.tag().equals("#None") || optTag.tag().equals("#N")) {
          if (optTag.childValue() != null) {
            queue.addFirst(toStreamItem(optTag.childValue()));
          }
          currentTrajectory.add(new VariantStep("#None", false));
          try {
            return new StvnOption(schema, Optional.empty(), List.copyOf(currentTrajectory));
          } finally {
            currentTrajectory.removeLast();
          }
        }
        // Explicit #Some
        List<StvnParser.SchemaTypeContext> inner = (schema != null && schema.node() != null)
            ? StvnTypeResolver.getInnerSchemas(schema.node())
            : List.of();
        if (inner.isEmpty() && schema != null && schema.underlyingSchema().isPresent() && schema.underlyingSchema().get().node() != null) {
          inner = StvnTypeResolver.getInnerSchemas(schema.underlyingSchema().get().node());
        }
        if (!inner.isEmpty()) {
          ResolvedSchema innerSchema = StvnTypeResolver.resolvePrimitiveSchema(documentContext, inner.getFirst(), Set.of())
              .orElseGet(() -> ensureSchema(null));
          if (optTag.childValue() != null) {
            queue.addFirst(toStreamItem(optTag.childValue()));
          }
          currentTrajectory.add(new VariantStep("#Some", false));
          try {
            StvnValue innerVal = evaluateNextItem(queue, innerSchema);
            return new StvnOption(schema, Optional.of(innerVal), List.copyOf(currentTrajectory));
          } finally {
            currentTrajectory.removeLast();
          }
        }
      }
      // Untagged or Constant -> Rule A (Implied #Some)
      List<StvnParser.SchemaTypeContext> inner = (schema != null && schema.node() != null)
          ? StvnTypeResolver.getInnerSchemas(schema.node())
          : List.of();
      if (inner.isEmpty() && schema != null && schema.underlyingSchema().isPresent() && schema.underlyingSchema().get().node() != null) {
        inner = StvnTypeResolver.getInnerSchemas(schema.underlyingSchema().get().node());
      }
      if (!inner.isEmpty()) {
        ResolvedSchema innerSchema = StvnTypeResolver.resolvePrimitiveSchema(documentContext, inner.getFirst(), Set.of())
            .orElseGet(() -> ensureSchema(null));
        queue.addFirst(item);
        currentTrajectory.add(new VariantStep("#Some", true));
        try {
          StvnValue innerVal = evaluateNextItem(queue, innerSchema);
          return new StvnOption(schema, Optional.of(innerVal), List.copyOf(currentTrajectory));
        } finally {
          currentTrajectory.removeLast();
        }
      }
    }

    // 2. Target Schema is :Either(L R)
    if (baseType.equals(":Either")) {
      List<ResolvedSchema> candidates = StvnTypeResolver.resolveCandidateSchemas(documentContext, schema.node());
      if (candidates.size() >= 2) {
        ResolvedSchema leftSchema = candidates.get(0);
        ResolvedSchema rightSchema = candidates.get(1);

        if (item instanceof EitherTagItem eitherTag) {
          boolean isRight = eitherTag.tag().equals("#Right") || eitherTag.tag().equals("#R");
          if (eitherTag.childValue() != null) {
            queue.addFirst(toStreamItem(eitherTag.childValue()));
          }
          currentTrajectory.add(new VariantStep(isRight
              ? "#Right"
              : "#Left", false));
          try {
            StvnValue childVal = evaluateNextItem(queue, isRight
                ? rightSchema
                : leftSchema);
            return new StvnEither(schema, childVal, isRight, false, List.copyOf(currentTrajectory));
          } finally {
            currentTrajectory.removeLast();
          }
        }

        // Untagged or Constant -> Evaluate Left and Right branches symmetrically
        var typeInfo = getItemTypeInfo(item);
        if (candidates.size() >= 2 && typeInfo.valType() != StvnTypeResolver.LiteralType.EXPLICIT_EITHER_VALUE) {
          if (StvnTypeResolver.isSameSchemaNode(documentContext, candidates.get(0).node(), candidates.get(1).node())) {
            var leftBase = StvnTypeResolver.getPrimitiveBaseType(candidates.getFirst().node());
            throw new org.stvnadore.core.validation.MalformedPayloadException("Ambiguous implicit either: Both sides are identical (" + (leftBase != null
                ? leftBase
                : "") + "), explicit #Left or #Right tag is required");
          }
        }

        boolean leftMatches = StvnTypeResolver.canMatch(documentContext, leftSchema.node(), typeInfo.valType(), typeInfo.literalText());
        boolean rightMatches = StvnTypeResolver.canMatch(documentContext, rightSchema.node(), typeInfo.valType(), typeInfo.literalText());

        if (leftMatches && rightMatches) {
          int start = item.sourceCtx() != null && item.sourceCtx().getStart() != null
              ? item.sourceCtx().getStart().getStartIndex()
              : -1;
          int end = item.sourceCtx() != null && item.sourceCtx().getStop() != null
              ? item.sourceCtx().getStop().getStopIndex() + 1
              : -1;
          throw new MalformedPayloadException(
              "Ambiguous implicit resolution: Value matches both Left and Right branches of :Either",
              start, end
          );
        }

        if (rightMatches) {
          queue.addFirst(item);
          currentTrajectory.add(new VariantStep("#Right", true));
          try {
            StvnValue rightVal = evaluateNextItem(queue, rightSchema);
            return new StvnEither(schema, rightVal, true, false, List.copyOf(currentTrajectory));
          } finally {
            currentTrajectory.removeLast();
          }
        }

        if (leftMatches) {
          int start = item.sourceCtx() != null && item.sourceCtx().getStart() != null
              ? item.sourceCtx().getStart().getStartIndex()
              : -1;
          int end = item.sourceCtx() != null && item.sourceCtx().getStop() != null
              ? item.sourceCtx().getStop().getStopIndex() + 1
              : -1;
          throw new MalformedPayloadException(
              "Rule E Violation: Untagged value matching Left branch of :Either is non-inferable; explicit #Left tag is required",
              start, end
          );
        }

        throw new MalformedPayloadException("Type mismatch: Value matches neither Left nor Right branch of :Either");
      }
    }

    // 3. Target Schema is :Union(T1 ... Tn)
    if (baseType.equals(":Union")) {
      List<ResolvedSchema> candidates = StvnTypeResolver.resolveCandidateSchemas(documentContext, schema.node());
      if (item instanceof UnionTagItem unionTag) {
        int tagNum = Integer.parseInt(unionTag.tag().substring(1));
        int branchIndex = tagNum - 1;
        if (branchIndex < 0 || branchIndex >= candidates.size()) {
          int start = unionTag.sourceCtx() != null && unionTag.sourceCtx().getStart() != null
              ? unionTag.sourceCtx().getStart().getStartIndex()
              : -1;
          int end = unionTag.sourceCtx() != null && unionTag.sourceCtx().getStop() != null
              ? unionTag.sourceCtx().getStop().getStopIndex() + 1
              : -1;
          throw new org.stvnadore.core.validation.StvnMalformedLiteralException("Union tag #" + tagNum + " out of bounds for " + candidates.size() + "-branch union", start, end);
        }
        if (unionTag.childValue() != null) {
          queue.addFirst(toStreamItem(unionTag.childValue()));
        }
        StvnValue branchVal = evaluateNextItem(queue, candidates.get(branchIndex));
        return new StvnUnion(schema, branchVal, branchIndex);
      }
      // Untagged -> Rule C / Rule I
      if (item instanceof KeywordTokenItem kw) {
        int matchCount = 0;
        int matchedIndex = -1;
        ResolvedSchema matchedCand = null;
        for (int i = 0; i < candidates.size(); i++) {
          ResolvedSchema cand = candidates.get(i);
          if (StvnTypeResolver.canMatch(documentContext, cand.node(), StvnTypeResolver.LiteralType.KEYWORD_LITERAL, kw.tokenText())) {
            matchCount++;
            matchedIndex = i;
            matchedCand = cand;
          }
        }
        if (matchCount > 1) {
          int start = kw.sourceCtx() != null && kw.sourceCtx().getStart() != null
              ? kw.sourceCtx().getStart().getStartIndex()
              : -1;
          int end = kw.sourceCtx() != null && kw.sourceCtx().getStop() != null
              ? kw.sourceCtx().getStop().getStopIndex() + 1
              : -1;
          throw new org.stvnadore.core.validation.StvnCollectionCollisionException("Ambiguous implicit resolution: Value matches multiple branches", start, end);
        }
        if (matchCount == 1 && matchedCand != null) {
          StvnValue branchVal = evaluateKeywordToken(kw.tokenText(), matchedCand, kw.sourceCtx());
          return new StvnUnion(schema, branchVal, matchedIndex);
        }
      }
      if (item instanceof AtomicValueItem atom) {
        ResolvedSchema matched = resolveImplicitSumCandidate(atom.ctx(), schema);
        return visitChildValue(atom.ctx(), matched);
      }
      throw new MalformedPayloadException("Unresolved schema for value context");
    }

    // 4. Target Schema is NON-SUM (:Boolean, :Scalar, :Tuple, :Seq, :Set, :Map)
    if (item instanceof OptionTagItem optTag) {
      var constDefOpt = StvnTypeResolver.findConstantDefinition(documentContext, optTag.tag());
      if (constDefOpt.isPresent()) {
        if (optTag.childValue() != null) {
          queue.addFirst(toStreamItem(optTag.childValue()));
        }
        return visitChildValue(constDefOpt.get().value(), schema);
      }
      int start = optTag.sourceCtx() != null && optTag.sourceCtx().getStart() != null
          ? optTag.sourceCtx().getStart().getStartIndex()
          : -1;
      int end = optTag.sourceCtx() != null && optTag.sourceCtx().getStop() != null
          ? optTag.sourceCtx().getStop().getStopIndex() + 1
          : -1;
      throw new MalformedPayloadException(
          "Unexpected Option tag (#Some/#None), schema does not define an :Option. baseType='" + baseType + "', nodeText='" + (schema != null && schema.node() != null
              ? schema.node().getText()
              : "null") + "'",
          start, end
      );
    }
    if (item instanceof EitherTagItem eitherTag) {
      var constDefOpt = StvnTypeResolver.findConstantDefinition(documentContext, eitherTag.tag());
      if (constDefOpt.isPresent()) {
        if (eitherTag.childValue() != null) {
          queue.addFirst(toStreamItem(eitherTag.childValue()));
        }
        return visitChildValue(constDefOpt.get().value(), schema);
      }
      int start = eitherTag.sourceCtx() != null && eitherTag.sourceCtx().getStart() != null
          ? eitherTag.sourceCtx().getStart().getStartIndex()
          : -1;
      int end = eitherTag.sourceCtx() != null && eitherTag.sourceCtx().getStop() != null
          ? eitherTag.sourceCtx().getStop().getStopIndex() + 1
          : -1;
      throw new MalformedPayloadException(
          "Unexpected Either tag (#Left/#Right), schema does not define an :Either. baseType='" + baseType + "', nodeText='" + (schema != null && schema.node() != null
              ? schema.node().getText()
              : "null") + "'",
          start, end
      );
    }
    if (item instanceof UnionTagItem unionTag) {
      var constDefOpt = StvnTypeResolver.findConstantDefinition(documentContext, unionTag.tag());
      if (constDefOpt.isPresent()) {
        if (unionTag.childValue() != null) {
          queue.addFirst(toStreamItem(unionTag.childValue()));
        }
        return visitChildValue(constDefOpt.get().value(), schema);
      }
      int start = unionTag.sourceCtx() != null && unionTag.sourceCtx().getStart() != null
          ? unionTag.sourceCtx().getStart().getStartIndex()
          : -1;
      int end = unionTag.sourceCtx() != null && unionTag.sourceCtx().getStop() != null
          ? unionTag.sourceCtx().getStop().getStopIndex() + 1
          : -1;
      throw new MalformedPayloadException(
          "Unexpected Union tag (" + unionTag.tag() + "), schema does not define a :Union. baseType='" + baseType + "', nodeText='" + (schema != null && schema.node() != null
              ? schema.node().getText()
              : "null") + "'",
          start, end
      );
    }
    if (item instanceof KeywordTokenItem kw) {
      return evaluateKeywordToken(kw.tokenText(), schema, kw.sourceCtx());
    }
    if (item instanceof BooleanLiteralItem b) {
      if (baseType.equals(":Boolean") || baseType.equals(":Undefined")) {
        return new StvnBoolean(ensureSchema(schema), b.tokenText().equals("#TRUE") || b.tokenText().equals("#T"));
      }
      return evaluateKeywordToken(b.tokenText(), schema, b.sourceCtx());
    }
    if (item instanceof AtomicValueItem atom) {
      return visitChildValue(atom.ctx(), schema);
    }

    throw new IllegalStateException("Unknown StreamItem type: " + item.getClass().getName());
  }

  private StvnCollection buildList(StvnParser.ListLiteralContext ctx, ResolvedSchema schema, String baseType) {
    var saved = List.copyOf(currentTrajectory);
    currentTrajectory.clear();
    try {
      var isSet = baseType.equals(":Set") || baseType.equals(":SetNonEmpty");
      var isNonEmpty = baseType.equals(":SeqNonEmpty") || baseType.equals(":SetNonEmpty");

      ResolvedSchema elementSchema;
      List<StvnParser.SchemaTypeContext> inner = (schema != null && schema.node() != null)
          ? StvnTypeResolver.getInnerSchemas(schema.node())
          : List.of();
      if (!inner.isEmpty()) {
        elementSchema = StvnTypeResolver.resolvePrimitiveSchema(documentContext, inner.getFirst(), Set.of())
            .orElseGet(() -> ensureSchema(null));
      } else {
        int start = ctx.start.getStartIndex();
        int end = ctx.stop.getStopIndex() + 1;
        int[] pos = getLineCol(ctx);
        var ex = new MalformedPayloadException("List/Set schema lacks element type definition", start, end);
        var diag = new StvnDiagnostic(ex.getMessage(), StvnDiagnostic.DiagnosticSeverity.ERROR, pos[0], pos[1], start, end, ex);
        diagnosticBag.add(diag);
        elementSchema = ensureSchema(null);
      }

      List<StvnValue> elements = new ArrayList<>();
      LinkedHashSet<StvnValue> setElements = new LinkedHashSet<>();

      Deque<StreamItem> queue = new ArrayDeque<>();
      for (var child : ctx.value()) {
        queue.addLast(toStreamItem(child));
      }

      while (!queue.isEmpty()) {
        StreamItem item = queue.getFirst();
        int prevSize = queue.size();
        try {
          StvnValue val = evaluateNextItem(queue, elementSchema);
          addCollectionElement(val, elementSchema, elements, setElements, isSet, ctx);
        } catch (Throwable t) {
          if (queue.size() == prevSize) {
            queue.removeFirst();
          }
          int start = getStartOffset(t, item.sourceCtx());
          int end = getEndOffset(t, item.sourceCtx());
          int[] pos = getLineCol(item.sourceCtx());
          var diag = new StvnDiagnostic(t.getMessage() != null
              ? t.getMessage()
              : t.toString(), StvnDiagnostic.DiagnosticSeverity.ERROR, pos[0], pos[1], start, end, t);
          diagnosticBag.add(diag);
          if (!isSet) {
            String rawText = item.sourceCtx() != null
                ? item.sourceCtx().getText()
                : "<error>";
            elements.add(new StvnError(ensureSchema(elementSchema), rawText, start, end, List.of(diag)));
          }
        }
      }

      if (isNonEmpty && (isSet
          ? setElements.isEmpty()
          : elements.isEmpty())) {
        String msg = isSet
            ? "Set is marked as non-empty but contains no elements"
            : "Sequence is marked as non-empty but contains no elements";
        int start = ctx.start.getStartIndex();
        int end = ctx.stop.getStopIndex() + 1;
        int[] pos = getLineCol(ctx);
        var ex = new MalformedPayloadException(msg, start, end);
        var diag = new StvnDiagnostic(msg, StvnDiagnostic.DiagnosticSeverity.ERROR, pos[0], pos[1], start, end, ex, Optional.of("NON_EMPTY_CONSTRAINT_VIOLATION"));
        diagnosticBag.add(diag);
      }
      return isSet
          ? new StvnSet(schema, setElements, isNonEmpty)
          : new StvnSeq(schema, elements, isNonEmpty);
    } finally {
      currentTrajectory.clear();
      currentTrajectory.addAll(saved);
    }
  }

  private void addCollectionElement(
      StvnValue val,
      ResolvedSchema elementSchema,
      List<StvnValue> elements,
      LinkedHashSet<StvnValue> setElements,
      boolean isSet,
      ParserRuleContext ctx
  ) {
    if (isSet) {
      if (!setElements.add(val)) {
        int startOffset = ctx.getStart() != null
            ? ctx.getStart().getStartIndex()
            : -1;
        int endOffset = ctx.getStop() != null
            ? ctx.getStop().getStopIndex() + 1
            : -1;
        int[] pos = getLineCol(ctx);
        var ex = new org.stvnadore.core.validation.StvnCollectionCollisionException("Duplicate set element detected", startOffset, endOffset);
        diagnosticBag.add(new StvnDiagnostic(ex.getMessage(), StvnDiagnostic.DiagnosticSeverity.ERROR, pos[0], pos[1], startOffset, endOffset, ex, Optional.of("DUPLICATE_SET_ELEMENT")));
      }
    } else {
      elements.add(val);
    }
  }

  private StvnMap buildMap(StvnParser.MapLiteralContext ctx, ResolvedSchema schema, String baseType) {
    var saved = List.copyOf(currentTrajectory);
    currentTrajectory.clear();
    try {
      var isNonEmpty = baseType.equals(":MapNonEmpty") || baseType.equals(":MapInvNonEmpty");
      var isInverted = baseType.equals(":MapInv") || baseType.equals(":MapInvNonEmpty");

      ResolvedSchema keySchema;
      ResolvedSchema valSchema;
      List<StvnParser.SchemaTypeContext> inner = StvnTypeResolver.getInnerSchemas(schema.node());
      if (inner.size() >= 2) {
        keySchema = StvnTypeResolver.resolvePrimitiveSchema(documentContext, inner.get(0), Set.of())
            .orElseGet(() -> ensureSchema(null));
        valSchema = StvnTypeResolver.resolvePrimitiveSchema(documentContext, inner.get(1), Set.of())
            .orElseGet(() -> ensureSchema(null));
      } else {
        int start = ctx.start.getStartIndex();
        int end = ctx.stop.getStopIndex() + 1;
        int[] pos = getLineCol(ctx);
        var ex = new MalformedPayloadException("Map entry schema must contain key and value types", start, end);
        var diag = new StvnDiagnostic(ex.getMessage(), StvnDiagnostic.DiagnosticSeverity.ERROR, pos[0], pos[1], start, end, ex);
        diagnosticBag.add(diag);
        keySchema = ensureSchema(null);
        valSchema = ensureSchema(null);
      }

      LinkedHashMap<StvnValue, StvnValue> entries = new LinkedHashMap<>();
      java.util.LinkedHashSet<StvnValue> seenKeys = new java.util.LinkedHashSet<>();
      Set<StvnValue> seenValues = new HashSet<>();

      for (var child : ctx.mapEntry()) {
        if (child == null || isMalformedValueContext(child.value(0)) || isMalformedValueContext(child.value(1)) || child.exception != null || hasErrorNode(child)) {
          int start = child != null && child.getStart() != null
              ? child.getStart().getStartIndex()
              : ctx.start.getStartIndex();
          int end = child != null && child.getStop() != null
              ? child.getStop().getStopIndex() + 1
              : ctx.stop.getStopIndex() + 1;
          int[] pos = getLineCol(child != null
              ? child
              : ctx);
          var ex = new org.stvnadore.core.validation.MalformedAstContextException("Map entry contains a null or malformed key or value node (malformed syntax or parser recovery leak)");
          var diag = new StvnDiagnostic(ex.getMessage(), StvnDiagnostic.DiagnosticSeverity.ERROR, pos[0], pos[1], start, end, ex);
          diagnosticBag.add(diag);
          continue;
        }

        StvnValue key;
        try {
          key = visitChildValue(child.value(0), keySchema);
        } catch (Throwable t) {
          int start = getStartOffset(t, child.value(0));
          int end = getEndOffset(t, child.value(0));
          int[] pos = getLineCol(child.value(0));
          var diag = new StvnDiagnostic(t.getMessage() != null
              ? t.getMessage()
              : t.toString(), StvnDiagnostic.DiagnosticSeverity.ERROR, pos[0], pos[1], start, end, t);
          diagnosticBag.add(diag);
          key = new StvnError(ensureSchema(keySchema), child.value(0).getText(), start, end, List.of(diag));
        }

        StvnValue val;
        try {
          val = visitChildValue(child.value(1), valSchema);
        } catch (Throwable t) {
          int start = getStartOffset(t, child.value(1));
          int end = getEndOffset(t, child.value(1));
          int[] pos = getLineCol(child.value(1));
          var diag = new StvnDiagnostic(t.getMessage() != null
              ? t.getMessage()
              : t.toString(), StvnDiagnostic.DiagnosticSeverity.ERROR, pos[0], pos[1], start, end, t);
          diagnosticBag.add(diag);
          val = new StvnError(ensureSchema(valSchema), child.value(1).getText(), start, end, List.of(diag));
        }

        if (!(key instanceof StvnError)) {
          if (!seenKeys.add(key)) {
            int start = child.value(0).getStart().getStartIndex();
            int end = child.value(0).getStop().getStopIndex() + 1;
            int[] pos = getLineCol(child.value(0));
            var ex = new org.stvnadore.core.validation.StvnCollectionCollisionException("Duplicate map key detected", start, end);
            var diag = new StvnDiagnostic(ex.getMessage(), StvnDiagnostic.DiagnosticSeverity.ERROR, pos[0], pos[1], start, end, ex, Optional.of("DUPLICATE_MAP_KEY"));
            diagnosticBag.add(diag);
          }
        }

        if (isInverted && !(val instanceof StvnError)) {
          if (!seenValues.add(val)) {
            int start = child.value(1).getStart().getStartIndex();
            int end = child.value(1).getStop().getStopIndex() + 1;
            int[] pos = getLineCol(child.value(1));
            var ex = new org.stvnadore.core.validation.StvnCollectionCollisionException("Duplicate inverted map value detected", start, end);
            var diag = new StvnDiagnostic(ex.getMessage(), StvnDiagnostic.DiagnosticSeverity.ERROR, pos[0], pos[1], start, end, ex, Optional.of("DUPLICATE_INVERTED_MAP_VALUE"));
            diagnosticBag.add(diag);
          }
        }

        entries.put(key, val);
      }
      if (isNonEmpty && entries.isEmpty()) {
        String msg = isInverted
            ? "Invertible map is marked as non-empty but contains no elements"
            : "Map is marked as non-empty but contains no elements";
        int start = ctx.start.getStartIndex();
        int end = ctx.stop.getStopIndex() + 1;
        int[] pos = getLineCol(ctx);
        var ex = new MalformedPayloadException(msg, start, end);
        var diag = new StvnDiagnostic(msg, StvnDiagnostic.DiagnosticSeverity.ERROR, pos[0], pos[1], start, end, ex, Optional.of("NON_EMPTY_CONSTRAINT_VIOLATION"));
        diagnosticBag.add(diag);
      }
      return new StvnMap(schema, entries, isNonEmpty, isInverted);
    } finally {
      currentTrajectory.clear();
      currentTrajectory.addAll(saved);
    }
  }

  private StvnTuple buildTuple(StvnParser.TupleLiteralContext ctx, ResolvedSchema schema) {
    var saved = List.copyOf(currentTrajectory);
    currentTrajectory.clear();
    try {
      String baseType = (schema != null && schema.node() != null)
          ? StvnTypeResolver.getPrimitiveBaseType(schema.node())
          : ":Undefined";
      if (baseType == null || !baseType.equals(":Tuple")) {
        int start = ctx.start.getStartIndex();
        int end = ctx.stop.getStopIndex() + 1;
        int[] pos = getLineCol(ctx);
        var ex = new MalformedPayloadException("Type mismatch: Expected " + (baseType != null
            ? baseType
            : "non-tuple") + ", got Tuple", start, end);
        var diag = new StvnDiagnostic(
            ex.getMessage(),
            StvnDiagnostic.DiagnosticSeverity.ERROR, pos[0], pos[1], start, end, ex
        );
        diagnosticBag.add(diag);
      }
      List<StvnValue> elements = new ArrayList<>();
      List<ResolvedSchema> elementSchemas = new ArrayList<>();
      List<StvnParser.SchemaTypeContext> inner = (schema != null && schema.node() != null)
          ? StvnTypeResolver.getInnerSchemas(schema.node())
          : List.of();
      for (var sNode : inner) {
        elementSchemas.add(StvnTypeResolver.resolvePrimitiveSchema(documentContext, sNode, Set.of())
            .orElseGet(() -> ensureSchema(null)));
      }

      int expected = 0;
      if (schema != null && schema.node() != null && schema.node().schemaConstructor() != null && schema.node().schemaConstructor().productType() != null) {
        var prod = schema.node().schemaConstructor().productType();
        if (prod instanceof StvnParser.TupleTypeContext tt) {
          expected = tt.schemaType().size();
        }
      } else {
        expected = elementSchemas.size();
      }

      Deque<StreamItem> queue = new ArrayDeque<>();
      for (var child : ctx.value()) {
        queue.addLast(toStreamItem(child));
      }

      int targetIndex = 0;
      while (!queue.isEmpty()) {
        StreamItem item = queue.getFirst();
        int prevSize = queue.size();
        ResolvedSchema elSchema = (targetIndex < elementSchemas.size())
            ? elementSchemas.get(targetIndex)
            : null;
        targetIndex++;
        try {
          StvnValue elVal = evaluateNextItem(queue, elSchema);
          elements.add(elVal);
        } catch (Throwable t) {
          if (queue.size() == prevSize) {
            queue.removeFirst();
          }
          int start = getStartOffset(t, item.sourceCtx());
          int end = getEndOffset(t, item.sourceCtx());
          int[] pos = getLineCol(item.sourceCtx());
          var diag = new StvnDiagnostic(t.getMessage() != null
              ? t.getMessage()
              : t.toString(), StvnDiagnostic.DiagnosticSeverity.ERROR, pos[0], pos[1], start, end, t);
          diagnosticBag.add(diag);
          String rawText = item.sourceCtx() != null
              ? item.sourceCtx().getText()
              : "<error>";
          elements.add(new StvnError(ensureSchema(elSchema), rawText, start, end, List.of(diag)));
        }
      }

      if (expected > 0 && expected != elements.size()) {
        int start = ctx.start.getStartIndex();
        int end = ctx.stop.getStopIndex() + 1;
        int[] pos = getLineCol(ctx);
        var ex = new MalformedPayloadException("Tuple arity mismatch: Expected " + expected + " elements, got " + elements.size(), start, end);
        var diag = new StvnDiagnostic(
            ex.getMessage(),
            StvnDiagnostic.DiagnosticSeverity.ERROR, pos[0], pos[1], start, end, ex, Optional.of("TUPLE_ARITY_MISMATCH")
        );
        diagnosticBag.add(diag);
      }

      return new StvnTuple(schema, elements);
    } finally {
      currentTrajectory.clear();
      currentTrajectory.addAll(saved);
    }
  }

  private StvnOption buildOption(StvnParser.ExplicitOptionValueContext ctx, ResolvedSchema schema) {
    boolean isNone = ctx.KW_NONE() != null || ctx.KW_NONE_SHORT() != null;
    if (isNone) {
      currentTrajectory.add(new VariantStep("#None", false));
      try {
        return new StvnOption(schema, Optional.empty(), List.copyOf(currentTrajectory));
      } finally {
        currentTrajectory.removeLast();
      }
    }
    ResolvedSchema childSchema = null;
    List<StvnParser.SchemaTypeContext> inner = StvnTypeResolver.getInnerSchemas(schema.node());
    if (!inner.isEmpty()) {
      childSchema = StvnTypeResolver.resolvePrimitiveSchema(documentContext, inner.getFirst(), Set.of()).orElse(null);
    }
    currentTrajectory.add(new VariantStep("#Some", false));
    try {
      if (ctx.value() == null) {
        throw new org.stvnadore.core.validation.MalformedPayloadException(
            "Option #Some payload cannot be null/empty",
            ctx.start.getStartIndex(),
            ctx.stop.getStopIndex() + 1
        );
      }
      StvnValue childVal;
      try {
        if (childSchema != null) {
          childVal = visitChildValue(ctx.value(), childSchema);
        } else {
          childVal = visitValue(ctx.value());
        }
      } catch (Throwable t) {
        int start = getStartOffset(t, ctx.value());
        int end = getEndOffset(t, ctx.value());
        int[] pos = getLineCol(ctx.value());
        var diag = new StvnDiagnostic(t.getMessage() != null
            ? t.getMessage()
            : t.toString(), StvnDiagnostic.DiagnosticSeverity.ERROR, pos[0], pos[1], start, end, t);
        diagnosticBag.add(diag);
        childVal = new StvnError(ensureSchema(childSchema), ctx.value().getText(), start, end, List.of(diag));
      }
      return new StvnOption(schema, Optional.of(childVal), List.copyOf(currentTrajectory));
    } finally {
      currentTrajectory.removeLast();
    }
  }

  private StvnEither buildEither(StvnParser.ExplicitEitherValueContext ctx, ResolvedSchema schema) {
    boolean isRight = ctx.KW_RIGHT() != null || ctx.KW_RIGHT_SHORT() != null;
    ResolvedSchema childSchema = null;
    List<ResolvedSchema> childSchemas = StvnTypeResolver.resolveCandidateSchemas(documentContext, schema.node());
    if (childSchemas.size() >= 2) {
      childSchema = isRight
          ? childSchemas.get(1)
          : childSchemas.get(0);
    }
    currentTrajectory.add(new VariantStep(isRight
        ? "#Right"
        : "#Left", false));
    try {
      if (ctx.value() == null) {
        throw new org.stvnadore.core.validation.MalformedPayloadException(
            "Either payload cannot be null",
            ctx.start.getStartIndex(),
            ctx.stop.getStopIndex() + 1
        );
      }
      StvnValue childValue;
      try {
        if (childSchema != null) {
          childValue = visitChildValue(ctx.value(), childSchema);
        } else {
          childValue = visitValue(ctx.value());
        }
      } catch (Throwable t) {
        int start = getStartOffset(t, ctx.value());
        int end = getEndOffset(t, ctx.value());
        int[] pos = getLineCol(ctx.value());
        var diag = new StvnDiagnostic(t.getMessage() != null
            ? t.getMessage()
            : t.toString(), StvnDiagnostic.DiagnosticSeverity.ERROR, pos[0], pos[1], start, end, t);
        diagnosticBag.add(diag);
        childValue = new StvnError(ensureSchema(childSchema), ctx.value().getText(), start, end, List.of(diag));
      }

      boolean isAmbiguous = false;
      if (childSchemas.size() >= 2) {
        String leftBase = StvnTypeResolver.getPrimitiveBaseType(childSchemas.get(0).node());
        String rightBase = StvnTypeResolver.getPrimitiveBaseType(childSchemas.get(1).node());
        if (leftBase != null && leftBase.equals(rightBase)) {
          isAmbiguous = true;
        }
      }
      return new StvnEither(schema, childValue, isRight, isAmbiguous, List.copyOf(currentTrajectory));
    } finally {
      currentTrajectory.removeLast();
    }
  }

  private StvnUnion buildUnion(StvnParser.ExplicitUnionValueContext ctx, ResolvedSchema schema) {
    String tagText = ctx.UNION_TAG_PREFIX().getText();
    int tagIndex = 0;
    for (int i = 1; i < tagText.length(); i++) {
      tagIndex = tagIndex * 10 + (tagText.charAt(i) - '0');
    }
    int zeroBasedIndex = tagIndex - 1;
    List<ResolvedSchema> childSchemas = StvnTypeResolver.resolveCandidateSchemas(documentContext, schema.node());
    int maxBranchCapacity = childSchemas.size();
    if (zeroBasedIndex < 0 || zeroBasedIndex >= maxBranchCapacity) {
      int start = ctx.start.getStartIndex();
      int end = ctx.stop.getStopIndex() + 1;
      int[] pos = getLineCol(ctx);
      var ex = new org.stvnadore.core.validation.StvnMalformedLiteralException(
          "Explicit branch tag #" + tagIndex + " overflows union schema constraints. Maximum branch capacity is " + maxBranchCapacity + ".",
          start,
          end
      );
      var diag = new StvnDiagnostic(
          ex.getMessage(),
          StvnDiagnostic.DiagnosticSeverity.ERROR,
          pos[0],
          pos[1],
          start,
          end,
          ex,
          Optional.of("UNION_BRANCH_OVERFLOW")
      );
      diagnosticBag.add(diag);
      var errNode = new StvnError(ensureSchema(schema), ctx.getText(), start, end, List.of(diag));
      return new StvnUnion(schema, errNode, 0);
    }
    ResolvedSchema childSchema = childSchemas.get(zeroBasedIndex);
    if (ctx.value() == null) {
      throw new org.stvnadore.core.validation.MalformedPayloadException(
          "Union payload cannot be null",
          ctx.start.getStartIndex(),
          ctx.stop.getStopIndex() + 1
      );
    }
    StvnValue childValue;
    try {
      childValue = visitChildValue(ctx.value(), childSchema);
    } catch (Throwable t) {
      int start = getStartOffset(t, ctx.value());
      int end = getEndOffset(t, ctx.value());
      int[] pos = getLineCol(ctx.value());
      var diag = new StvnDiagnostic(t.getMessage() != null
          ? t.getMessage()
          : t.toString(), StvnDiagnostic.DiagnosticSeverity.ERROR, pos[0], pos[1], start, end, t);
      diagnosticBag.add(diag);
      childValue = new StvnError(ensureSchema(childSchema), ctx.value().getText(), start, end, List.of(diag));
    }
    return new StvnUnion(schema, childValue, zeroBasedIndex);
  }

  private boolean isValidEnumVariant(ResolvedSchema enumSchema, String tokenText) {
    var ctor = enumSchema.node() != null
        ? enumSchema.node().schemaConstructor()
        : null;
    if (ctor == null && enumSchema.underlyingSchema().isPresent()) {
      var curr = enumSchema.underlyingSchema().get();
      while (curr != null) {
        if (curr.node() != null && curr.node().schemaConstructor() != null) {
          ctor = curr.node().schemaConstructor();
          break;
        }
        curr = curr.underlyingSchema().orElse(null);
      }
    }
    if (ctor != null && ctor.sumType() != null && ctor.sumType().KW_ENUM() != null) {
      var enumDef = ctor.sumType().enumDef();
      for (var childKw : enumDef.valueKeyword()) {
        if (childKw.getText().equals(tokenText)) {
          return true;
        }
      }
    }
    return false;
  }

  private static final Set<String> KEYWORDS_MAP = Set.of(
      ":Map",
      ":MapNonEmpty",
      ":MapInv",
      ":MapInvNonEmpty");

  private boolean matchesSchema(StvnValue val, ResolvedSchema cand) {
    var t = StvnTypeResolver.getPrimitiveBaseType(cand.node());
    if (t == null) return false;

    return switch (val) {
      case StvnEnum ignored -> t.equals(":Enum");
      case StvnInteger ignored -> isIntType(t) || isTimeEpochType(t);
      case StvnFloat ignored -> isFloatType(t);
      case StvnBoolean ignored -> t.equals(":Boolean");
      case StvnString ignored -> isStringType(t) || isDateTimeType(t);
      case StvnTime ignored -> isTimeEpochType(t);
      case StvnDateTimeOffset ignored -> t.equals(":DateTimeOffset") || t.equals(":DateTime");
      case StvnDateTimeZoned ignored -> t.equals(":DateTimeZoned") || t.equals(":DateTime");
      case StvnDateTimeAudited ignored -> t.equals(":DateTimeAudited") || t.equals(":DateTime");
      case StvnSeq ignored -> t.equals(":Seq") || t.equals(":SeqNonEmpty");
      case StvnSet ignored -> t.equals(":Set") || t.equals(":SetNonEmpty");
      case StvnMap ignored -> KEYWORDS_MAP.contains(t);
      case StvnTuple ignored -> t.equals(":Tuple");
      default -> false;
    };
  }

  private int findImplicitUnionTag(StvnValue val, ResolvedSchema sumSchema) {
    var candidates = StvnTypeResolver.resolveCandidateSchemas(documentContext, sumSchema.node());
    var baseType = StvnTypeResolver.getPrimitiveBaseType(sumSchema.node());
    if (baseType == null) return 0;

    if (baseType.equals(":Either")) {
      if (candidates.size() >= 2) {
        boolean leftMatches = matchesSchema(val, candidates.get(0));
        boolean rightMatches = matchesSchema(val, candidates.get(1));
        if (leftMatches && rightMatches) {
          throw new org.stvnadore.core.validation.MalformedPayloadException("Ambiguous implicit resolution: Value matches both Left and Right branches of :Either");
        }
        if (leftMatches) {
          throw new org.stvnadore.core.validation.MalformedPayloadException("Rule E Violation: Untagged value matching Left branch of :Either is non-inferable; explicit #Left tag is required");
        }
        if (rightMatches) {
          return 1;
        }
      }
      return 0;
    } else if (baseType.equals(":Union")) {
      for (var i = 0; i < candidates.size(); i++) {
        if (matchesSchema(val, candidates.get(i))) {
          return i;
        }
      }
    }
    return 0;
  }

  private StvnValue wrapImplicitSum(StvnValue rawValue, ResolvedSchema schema) {
    StvnValue wrapped = schema.sumTypeNode().flatMap(sumTypeNode ->
        schema.implicitUnionTag().flatMap(tag -> {
          var sumNodeParent = (StvnParser.SchemaTypeContext) sumTypeNode.getParent().getParent();
          return StvnTypeResolver.resolvePrimitiveSchema(documentContext, sumNodeParent, java.util.Set.of())
              .map(baseSum -> {
                var alias = StvnTypeResolver.findAliasNameForSchemaType(documentContext, sumNodeParent);
                var sumSchema = new ResolvedSchema(
                    baseSum.node(),
                    baseSum.constraints(),
                    alias,
                    baseSum.implicitUnionTag(),
                    baseSum.sumTypeNode(),
                    baseSum.underlyingSchema(),
                    baseSum.localConstraints()
                );

                if (sumTypeNode.KW_EITHER() != null) {
                  var alreadyWrapped = (rawValue instanceof StvnEither either && either.schema() != null &&
                      StvnTypeResolver.isSameSchemaNode(documentContext, either.schema().node(), sumSchema.node()));
                  if (alreadyWrapped) return rawValue;
                  var isRight = (tag == 1);
                  var childSchemas = StvnTypeResolver.resolveCandidateSchemas(documentContext, sumSchema.node());
                  if (childSchemas.size() >= 2) {
                    if (StvnTypeResolver.isSameSchemaNode(documentContext, childSchemas.get(0).node(), childSchemas.get(1).node())) {
                      var leftBase = StvnTypeResolver.getPrimitiveBaseType(childSchemas.getFirst().node());
                      throw new org.stvnadore.core.validation.MalformedPayloadException("Ambiguous implicit either: Both sides are identical (" + (leftBase != null
                          ? leftBase
                          : "") + "), explicit #Left or #Right tag is required");
                    }
                  }
                  return new StvnEither(sumSchema, rawValue, isRight, false, List.copyOf(currentTrajectory));
                } else if (sumTypeNode.KW_UNION() != null) {
                  var alreadyWrapped = (rawValue instanceof StvnUnion union && union.schema() != null &&
                      StvnTypeResolver.isSameSchemaNode(documentContext, union.schema().node(), sumSchema.node()));
                  if (alreadyWrapped) return rawValue;
                  return new StvnUnion(sumSchema, rawValue, tag);
                } else if (sumTypeNode.KW_OPTION() != null) {
                  return rawValue;
                }
                return rawValue;
              });
        })
    ).orElse(null);

    if (wrapped != null) {
      return wrapped;
    }

    var primBase = StvnTypeResolver.getPrimitiveBaseType(schema.node());
    if (primBase != null) {
      if (primBase.equals(":Option")) {
        var alreadyWrapped = (rawValue instanceof StvnOption opt && opt.schema() != null &&
            StvnTypeResolver.isSameSchemaNode(documentContext, opt.schema().node(), schema.node()));
        if (!alreadyWrapped) {
          currentTrajectory.add(new VariantStep("#Some", true));
          try {
            return new StvnOption(schema, Optional.of(rawValue), List.copyOf(currentTrajectory));
          } finally {
            currentTrajectory.removeLast();
          }
        }
      }
      if (primBase.equals(":Either")) {
        var alreadyWrapped = (rawValue instanceof StvnEither either && either.schema() != null &&
            StvnTypeResolver.isSameSchemaNode(documentContext, either.schema().node(), schema.node()));
        if (!alreadyWrapped) {
          var childSchemas = StvnTypeResolver.resolveCandidateSchemas(documentContext, schema.node());
          if (childSchemas.size() >= 2) {
            if (StvnTypeResolver.isSameSchemaNode(documentContext, childSchemas.get(0).node(), childSchemas.get(1).node())) {
              var leftBase = StvnTypeResolver.getPrimitiveBaseType(childSchemas.getFirst().node());
              throw new org.stvnadore.core.validation.MalformedPayloadException("Ambiguous implicit either: Both sides are identical (" + (leftBase != null
                  ? leftBase
                  : "") + "), explicit #Left or #Right tag is required");
            }
          }
          var isRight = schema.implicitUnionTag()
              .map(tag -> tag == 1)
              .orElseGet(() -> findImplicitUnionTag(rawValue, schema) == 1);
          currentTrajectory.add(new VariantStep(isRight
              ? "#Right"
              : "#Left", true));
          try {
            return new StvnEither(schema, rawValue, isRight, false, List.copyOf(currentTrajectory));
          } finally {
            currentTrajectory.removeLast();
          }
        }
      }
      if (primBase.equals(":Union")) {
        var alreadyWrapped = (rawValue instanceof StvnUnion union && union.schema() != null &&
            StvnTypeResolver.isSameSchemaNode(documentContext, union.schema().node(), schema.node()));
        if (!alreadyWrapped) {
          var tagIndex = schema.implicitUnionTag()
              .orElseGet(() -> findImplicitUnionTag(rawValue, schema));
          return new StvnUnion(schema, rawValue, tagIndex);
        }
      }
    }
    return rawValue;
  }

  private static final org.stvnadore.core.parser.StvnParser.SchemaTypeContext DUMMY_SCHEMA_TYPE_CONTEXT;

  static {
    var lexer = new org.stvnadore.core.parser.StvnLexer(org.antlr.v4.runtime.CharStreams.fromString(""));
    lexer.removeErrorListeners();
    var parser = new org.stvnadore.core.parser.StvnParser(new org.antlr.v4.runtime.CommonTokenStream(lexer));
    parser.removeErrorListeners();
    DUMMY_SCHEMA_TYPE_CONTEXT = parser.schemaType();
  }

  private ResolvedSchema ensureSchema(ResolvedSchema schema) {
    if (schema != null) return schema;
    return new org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema(
        DUMMY_SCHEMA_TYPE_CONTEXT,
        org.stvnadore.core.validation.StvnTypeResolver.StvnConstraints.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.of(org.stvnadore.core.validation.StvnTypeResolver.StvnConstraints.empty())
    );
  }


  private ResolvedSchema resolveImplicitSumCandidate(StvnParser.ValueContext ctx, ResolvedSchema schema) {
    var baseType = StvnTypeResolver.getPrimitiveBaseType(schema.node());
    if (baseType == null) return schema;
    if (baseType.equals(":Option") && ctx.explicitOptionValue() != null) {
      return schema;
    }
    if (baseType.equals(":Either") && ctx.explicitEitherValue() != null) {
      return schema;
    }
    if (baseType.equals(":Union") && ctx.explicitUnionValue() != null) {
      return schema;
    }
    if (baseType.equals(":Union")) {
      if (ctx.collectionValue() != null && ctx.collectionValue().listLiteral() != null) {
        var list = ctx.collectionValue().listLiteral();
        if (list.value().size() == 2 && list.value(0).integerLiteral() != null) {
          return schema;
        }
      }
    }
    if (baseType.equals(":Option") || baseType.equals(":Either") || baseType.equals(":Union")) {
      List<ResolvedSchema> candidates = StvnTypeResolver.resolveCandidateSchemas(documentContext, schema.node());

      var valType = StvnTypeResolver.LiteralType.UNKNOWN;
      var literalText = (String) null;
      if (ctx.integerLiteral() != null) valType = StvnTypeResolver.LiteralType.INTEGER_LITERAL;
      else if (ctx.floatLiteral() != null) valType = StvnTypeResolver.LiteralType.FLOAT_LITERAL;
      else if (ctx.stringLiteral() != null) valType = StvnTypeResolver.LiteralType.STRING_LITERAL;
      else if (ctx.booleanLiteral() != null) valType = StvnTypeResolver.LiteralType.BOOLEAN_LITERAL;
      else if (ctx.explicitOptionValue() != null) valType = StvnTypeResolver.LiteralType.EXPLICIT_OPTION_VALUE;
      else if (ctx.explicitEitherValue() != null) valType = StvnTypeResolver.LiteralType.EXPLICIT_EITHER_VALUE;
      else if (ctx.explicitUnionValue() != null) valType = StvnTypeResolver.LiteralType.EXPLICIT_UNION_VALUE;
      else if (ctx.collectionValue() != null) {
        if (ctx.collectionValue().listLiteral() != null) valType = StvnTypeResolver.LiteralType.LIST_LITERAL;
        else if (ctx.collectionValue().mapLiteral() != null) valType = StvnTypeResolver.LiteralType.MAP_LITERAL;
        else if (ctx.collectionValue().tupleLiteral() != null) valType = StvnTypeResolver.LiteralType.TUPLE_LITERAL;
      } else if (ctx.valueKeyword() != null) {
        valType = StvnTypeResolver.LiteralType.KEYWORD_LITERAL;
        literalText = ctx.start.getText();
      }

      if (baseType.equals(":Either") && candidates.size() >= 2) {
        if (valType != StvnTypeResolver.LiteralType.EXPLICIT_OPTION_VALUE && valType != StvnTypeResolver.LiteralType.EXPLICIT_EITHER_VALUE) {
          if (StvnTypeResolver.isSameSchemaNode(documentContext, candidates.get(0).node(), candidates.get(1).node())) {
            var leftBase = StvnTypeResolver.getPrimitiveBaseType(candidates.getFirst().node());
            throw new org.stvnadore.core.validation.MalformedPayloadException("Ambiguous implicit either: Both sides are identical (" + (leftBase != null
                ? leftBase
                : "") + "), explicit #Left or #Right tag is required");
          }
        }

        var leftSchema = candidates.get(0);
        var rightSchema = candidates.get(1);

        boolean leftMatches = StvnTypeResolver.canMatch(documentContext, leftSchema.node(), valType, literalText);
        boolean rightMatches = StvnTypeResolver.canMatch(documentContext, rightSchema.node(), valType, literalText);

        if (leftMatches && rightMatches) {
          throw new org.stvnadore.core.validation.MalformedPayloadException(
              "Ambiguous implicit resolution: Value matches both Left and Right branches of :Either",
              ctx.start.getStartIndex(),
              ctx.stop.getStopIndex() + 1
          );
        }

        if (leftMatches) {
          throw new org.stvnadore.core.validation.MalformedPayloadException(
              "Rule E Violation: Untagged value matching Left branch of :Either is non-inferable; explicit #Left tag is required",
              ctx.start.getStartIndex(),
              ctx.stop.getStopIndex() + 1
          );
        }

        if (rightMatches) {
          var sumTypeNode = schema.node().schemaConstructor() != null
              ? schema.node().schemaConstructor().sumType()
              : null;
          return new ResolvedSchema(
              rightSchema.node(),
              rightSchema.constraints(),
              rightSchema.aliasName(),
              Optional.of(1),
              Optional.ofNullable(sumTypeNode),
              rightSchema.underlyingSchema(),
              rightSchema.localConstraints()
          );
        }

        throw new org.stvnadore.core.validation.MalformedPayloadException(
            "Type mismatch: Value matches neither Left nor Right branch of :Either",
            ctx.start.getStartIndex(),
            ctx.stop.getStopIndex() + 1
        );
      }

      ResolvedSchema matched = null;
      int matchedIndex = -1;
      int matchCount = 0;

      for (int i = 0; i < candidates.size(); i++) {
        ResolvedSchema cand = candidates.get(i);
        if (StvnTypeResolver.canMatch(documentContext, cand.node(), valType, literalText)) {
          matchCount++;
          matched = cand;
          matchedIndex = i;
        }
      }

      if (matchCount > 1) {
        if (baseType.equals(":Union")) {
          throw new org.stvnadore.core.validation.StvnCollectionCollisionException(
              "Ambiguous implicit resolution: Value matches multiple branches",
              ctx.start.getStartIndex(),
              ctx.stop.getStopIndex() + 1
          );
        } else {
          throw new org.stvnadore.core.validation.MalformedPayloadException(
              "Ambiguous implicit resolution: Value matches multiple branches",
              ctx.start.getStartIndex(),
              ctx.stop.getStopIndex() + 1
          );
        }
      }

      if (matched != null) {
        var sumTypeNode = schema.node().schemaConstructor() != null
            ? schema.node().schemaConstructor().sumType()
            : null;
        return new ResolvedSchema(
            matched.node(),
            matched.constraints(),
            matched.aliasName(),
            Optional.of(matchedIndex),
            Optional.ofNullable(sumTypeNode),
            matched.underlyingSchema(),
            matched.localConstraints()
        );
      } else {
        throw new org.stvnadore.core.validation.MalformedPayloadException("Unresolved schema for value context");
      }
    }
    return schema;
  }

  private boolean isExplicitTagForSchema(String token, ResolvedSchema schema) {
    var baseType = StvnTypeResolver.getPrimitiveBaseType(schema.node());
    if (baseType == null) return false;
    if (baseType.equals(":Option")) {
      return token.equals("#Some") || token.equals("#S") || token.equals("#None") || token.equals("#N");
    }
    if (baseType.equals(":Either")) {
      return token.equals("#Left") || token.equals("#L") || token.equals("#Right") || token.equals("#R");
    }
    if (baseType.equals(":Union")) {
      return token.startsWith("#") && token.length() > 1 && Character.isDigit(token.charAt(1));
    }
    return false;
  }

  private void checkKeywordClash(String token, ResolvedSchema schema) {
    if (schema == null) return;
    final var activeSchema = ensureSchema(schema);
    String effectiveBase = activeSchema.sumTypeNode()
        .map(sumNode -> {
          if (sumNode.KW_OPTION() != null) return ":Option";
          if (sumNode.KW_EITHER() != null) return ":Either";
          if (sumNode.KW_UNION() != null) return ":Union";
          return null;
        })
        .orElseGet(() -> {
          String bt = (activeSchema.node() != null)
              ? StvnTypeResolver.getPrimitiveBaseType(activeSchema.node())
              : null;
          if (bt == null && activeSchema.underlyingSchema().isPresent()) {
            var curr = activeSchema.underlyingSchema().get();
            while (curr != null) {
              if (curr.node() != null) {
                bt = StvnTypeResolver.getPrimitiveBaseType(curr.node());
                if (bt != null) break;
              }
              curr = curr.underlyingSchema().orElse(null);
            }
          }
          return bt;
        });
    if (effectiveBase == null) return;

    if (effectiveBase.equals(":Option") || effectiveBase.equals(":Either") || effectiveBase.equals(":Union")) {
      boolean isClashCandidate = false;
      if (effectiveBase.equals(":Option")) {
        isClashCandidate = token.equals("#Some") || token.equals("#S") || token.equals("#None") || token.equals("#N");
      } else if (effectiveBase.equals(":Either")) {
        isClashCandidate = token.equals("#Left") || token.equals("#L") || token.equals("#Right") || token.equals("#R");
      }

      if (isClashCandidate) {
        var cNode = activeSchema.sumTypeNode()
            .map(node -> (StvnParser.SchemaTypeContext) node.getParent().getParent())
            .orElse(activeSchema.node());
        var candidates = StvnTypeResolver.resolveCandidateSchemas(documentContext, cNode);
        for (var cand : candidates) {
          if (isEnumSchema(cand)) {
            if (isValidEnumVariant(cand, token)) {
              throw new IllegalArgumentException("Ambiguous keyword clash: Token " + token + " is both a control keyword for " + effectiveBase + " and a valid variant of enum " + cand.aliasName().orElse(""));
            }
          }
        }
      }
    }
  }

  private void checkKeywordClash(StvnParser.ValueContext ctx, ResolvedSchema schema) {
    if (ctx == null) return;
    if (ctx.explicitOptionValue() != null && ctx.explicitOptionValue().value() != null) return;
    if (ctx.explicitEitherValue() != null && ctx.explicitEitherValue().value() != null) return;
    if (ctx.explicitUnionValue() != null && ctx.explicitUnionValue().value() != null) return;
    checkKeywordClash(ctx.start.getText(), schema);
  }

  private StvnTypeResolver.LiteralType getValTypeFromStvnValue(StvnValue val) {
    if (val instanceof StvnInteger) return StvnTypeResolver.LiteralType.INTEGER_LITERAL;
    if (val instanceof StvnFloat) return StvnTypeResolver.LiteralType.FLOAT_LITERAL;
    if (val instanceof StvnString) return StvnTypeResolver.LiteralType.STRING_LITERAL;
    if (val instanceof StvnBoolean) return StvnTypeResolver.LiteralType.BOOLEAN_LITERAL;
    if (val instanceof StvnTuple) return StvnTypeResolver.LiteralType.TUPLE_LITERAL;
    if (val instanceof StvnSeq || val instanceof StvnSet) return StvnTypeResolver.LiteralType.LIST_LITERAL;
    if (val instanceof StvnMap) return StvnTypeResolver.LiteralType.MAP_LITERAL;
    if (val instanceof StvnEnum) return StvnTypeResolver.LiteralType.KEYWORD_LITERAL;
    if (val instanceof StvnOption) return StvnTypeResolver.LiteralType.EXPLICIT_OPTION_VALUE;
    if (val instanceof StvnEither) return StvnTypeResolver.LiteralType.EXPLICIT_EITHER_VALUE;
    return StvnTypeResolver.LiteralType.UNKNOWN;
  }

  private @Nullable String getLiteralTextFromStvnValue(StvnValue val) {
    if (val instanceof StvnEnum e) return e.keyword();
    return null;
  }

  private StvnValue wrapValueInRootSchema(StvnValue val, ResolvedSchema rootSchema, int startOffset, int endOffset) {
    var baseType = StvnTypeResolver.getPrimitiveBaseType(rootSchema.node());
    if (baseType == null) return val;

    if (baseType.equals(":Option")) {
      var alreadyWrapped = (val instanceof StvnOption opt && opt.schema() != null &&
          StvnTypeResolver.isSameSchemaNode(documentContext, opt.schema().node(), rootSchema.node()));
      if (alreadyWrapped) {
        if (val instanceof StvnOption opt) {
          var combined = new ArrayList<VariantStep>(currentTrajectory);
          combined.addAll(opt.trajectory());
          var innerVal = opt.value().orElse(null);
          if (innerVal != null) {
            var innerSchemas = StvnTypeResolver.getInnerSchemas(rootSchema.node());
            if (!innerSchemas.isEmpty()) {
              var innerSchema = StvnTypeResolver.resolvePrimitiveSchema(documentContext, innerSchemas.get(0), java.util.Set.of()).orElse(null);
              if (innerSchema != null) {
                innerVal = wrapValueInRootSchema(innerVal, innerSchema, startOffset, endOffset);
              }
            }
          }
          return new StvnOption(opt.schema(), Optional.ofNullable(innerVal), combined);
        }
        return val;
      }

      currentTrajectory.add(new VariantStep("#Some", true));
      try {
        var innerSchemas = StvnTypeResolver.getInnerSchemas(rootSchema.node());
        if (!innerSchemas.isEmpty()) {
          var innerSchema = StvnTypeResolver.resolvePrimitiveSchema(documentContext, innerSchemas.get(0), java.util.Set.of()).orElse(null);
          if (innerSchema != null) {
            val = wrapValueInRootSchema(val, innerSchema, startOffset, endOffset);
          }
        }
        return new StvnOption(rootSchema, Optional.of(val), List.copyOf(currentTrajectory));
      } finally {
        currentTrajectory.removeLast();
      }
    }

    if (baseType.equals(":Either")) {
      var alreadyWrapped = (val instanceof StvnEither either && either.schema() != null &&
          StvnTypeResolver.isSameSchemaNode(documentContext, either.schema().node(), rootSchema.node()));
      if (alreadyWrapped) {
        if (val instanceof StvnEither either) {
          var combined = new ArrayList<VariantStep>(currentTrajectory);
          combined.addAll(either.trajectory());
          var innerVal = either.value();
          var childSchemas = StvnTypeResolver.resolveCandidateSchemas(documentContext, rootSchema.node());
          if (childSchemas.size() >= 2) {
            var childSchema = either.isRight()
                ? childSchemas.get(1)
                : childSchemas.get(0);
            innerVal = wrapValueInRootSchema(innerVal, childSchema, startOffset, endOffset);
          }
          return new StvnEither(either.schema(), innerVal, either.isRight(), either.isAmbiguous(), combined);
        }
        return val;
      }

      var candidates = StvnTypeResolver.resolveCandidateSchemas(documentContext, rootSchema.node());
      if (candidates.size() < 2) return val;

      var leftCand = candidates.get(0);
      var rightCand = candidates.get(1);

      boolean leftMatches = false;
      boolean rightMatches = false;

      if (val.schema() != null) {
        leftMatches = StvnTypeResolver.isSameSchemaNode(documentContext, val.schema().node(), leftCand.node());
        rightMatches = StvnTypeResolver.isSameSchemaNode(documentContext, val.schema().node(), rightCand.node());
      }

      if (!leftMatches && !rightMatches) {
        var valType = getValTypeFromStvnValue(val);
        var literalText = getLiteralTextFromStvnValue(val);
        leftMatches = StvnTypeResolver.canMatch(documentContext, leftCand.node(), valType, literalText);
        rightMatches = StvnTypeResolver.canMatch(documentContext, rightCand.node(), valType, literalText);
      }

      if (leftMatches && rightMatches) {
        throw new org.stvnadore.core.validation.MalformedPayloadException(
            "Ambiguous implicit resolution: Value matches both Left and Right branches of :Either",
            startOffset,
            endOffset
        );
      }

      if (leftMatches) {
        throw new org.stvnadore.core.validation.MalformedPayloadException(
            "Rule E Violation: Untagged value matching Left branch of :Either is non-inferable; explicit #Left tag is required",
            startOffset,
            endOffset
        );
      }

      if (rightMatches) {
        currentTrajectory.add(new VariantStep("#Right", true));
        try {
          var wrappedVal = wrapValueInRootSchema(val, rightCand, startOffset, endOffset);
          return new StvnEither(rootSchema, wrappedVal, true, false, List.copyOf(currentTrajectory));
        } finally {
          currentTrajectory.removeLast();
        }
      }

      throw new org.stvnadore.core.validation.MalformedPayloadException(
          "Type mismatch: Value matches neither Left nor Right branch of :Either",
          startOffset,
          endOffset
      );
    }

    if (baseType.equals(":Union")) {
      var alreadyWrapped = (val instanceof StvnUnion union && union.schema() != null &&
          StvnTypeResolver.isSameSchemaNode(documentContext, union.schema().node(), rootSchema.node()));
      if (alreadyWrapped) {
        return val;
      }

      var candidates = StvnTypeResolver.resolveCandidateSchemas(documentContext, rootSchema.node());

      ResolvedSchema matchedCand = null;
      var matchedIndex = -1;
      for (var i = 0; i < candidates.size(); i++) {
        var cand = candidates.get(i);
        if (val.schema() != null && StvnTypeResolver.isSameSchemaNode(documentContext, val.schema().node(), cand.node())) {
          matchedCand = cand;
          matchedIndex = i;
          break;
        }
      }

      if (matchedCand != null) {
        return new StvnUnion(rootSchema, val, matchedIndex);
      }

      var matchCount = 0;
      for (var i = 0; i < candidates.size(); i++) {
        var cand = candidates.get(i);
        var valType = getValTypeFromStvnValue(val);
        var literalText = getLiteralTextFromStvnValue(val);
        if (StvnTypeResolver.canMatch(documentContext, cand.node(), valType, literalText)) {
          matchCount++;
          matchedCand = cand;
          matchedIndex = i;
        }
      }

      if (matchCount > 1) {
        throw new org.stvnadore.core.validation.StvnCollectionCollisionException(
            "Ambiguous implicit resolution: Value matches multiple branches",
            startOffset,
            endOffset
        );
      }

      if (matchedCand != null) {
        val = wrapValueInRootSchema(val, matchedCand, startOffset, endOffset);
        return new StvnUnion(rootSchema, val, matchedIndex);
      } else {
        return new StvnUnion(rootSchema, val, 0);
      }
    }

    return val;
  }

  private static boolean isNumeric(String str) {
    if (str.isEmpty()) return false;
    for (var i = 0; i < str.length(); i++) {
      if (!Character.isDigit(str.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static boolean isIntType(String baseType) {
    if (baseType.equals(":Int") || baseType.equals(":Uint")) return true;
    if (baseType.startsWith(":Int") && isNumeric(baseType.substring(4))) return true;
    if (baseType.startsWith(":Uint") && isNumeric(baseType.substring(5))) return true;
    return false;
  }

  private static boolean isTimeEpochType(String baseType) {
    return baseType.equals(":TimeEpoch")
        || baseType.equals(":TimeEpochS")
        || baseType.equals(":TimeEpochMs")
        || baseType.equals(":TimeEpochNs");
  }

  private static boolean isFloatType(String baseType) {
    if (baseType.equals(":Float") || baseType.equals(":FloatExact")) return true;
    if (baseType.startsWith(":Float") && isNumeric(baseType.substring(6))) return true;
    return false;
  }

  private static boolean isStringType(String baseType) {
    if (baseType.equals(":String") || baseType.equals(":StringNonEmpty") || baseType.equals(":StringFixed"))
      return true;
    if (baseType.startsWith(":StringFixed") && isNumeric(baseType.substring(12))) return true;
    if (baseType.startsWith(":StringNonEmpty") && isNumeric(baseType.substring(15))) return true;
    if (baseType.startsWith(":String") && !baseType.startsWith(":StringFixed") && !baseType.startsWith(":StringNonEmpty") && isNumeric(baseType.substring(7)))
      return true;
    return false;
  }

  private static boolean isDateTimeType(String baseType) {
    return baseType.equals(":DateTime")
        || baseType.equals(":DateTimeOffset")
        || baseType.equals(":DateTimeZoned")
        || baseType.equals(":DateTimeAudited");
  }
}
