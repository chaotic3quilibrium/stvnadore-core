package org.stvnadore.core.binary;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnSchema.BoundKind;
import org.stvnadore.core.ir.StvnSchema.FloatRepresentation;
import org.stvnadore.core.lowering.StvnPayloadLowerer;
import org.stvnadore.core.validation.MalformedSchemaException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic pure-IR schema hashing engine for STVN 2.0.0.
 * <p>
 * Generates structural fingerprint identities (such as RFC-compliant Version 8 UUIDs or SHA-256 hashes)
 * by recursively digesting normalized {@link StvnSchema} definitions. The hashing process digests:
 * <ol>
 *   <li>Optional nominal FQNI alias identity.</li>
 *   <li>The canonical base type or constructor identifier (for example, {@code :Int}, {@code :Seq}, {@code :Enum}).</li>
 *   <li>Normalized metadata facets and trait overrides in a deterministic sequence.</li>
 *   <li>Recursive nested child schemas (tuple fields, union branches, collection elements, map key/values).</li>
 * </ol>
 * <p>
 * Recursive back-edges and reference cycles are tracked deterministically. Anonymous cycles fail closed
 * with {@link MalformedSchemaException}, whereas nominal circular definitions digest their alias identifier
 * to break the cycle deterministically.
 *
 * @since 1.0.0
 */
@NullMarked
public final class StvnSchemaHasher {

  private StvnSchemaHasher() {
    // Utility class, non-instantiable
  }

  private static final byte[] PREFIX_CYCLE = "cycle:".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PREFIX_ALIAS = "alias:".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PREFIX_ENUM_VARIANT = "enumVariant:".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PREFIX_SUBSET_NAME = "subsetName:".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PREFIX_SUBSET_PARENT = "subsetParent:".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PREFIX_SUBSET_ROOT = "subsetRoot:".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PREFIX_SUBSET_FILTER_TYPE = "subsetFilterType:".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PREFIX_SUBSET_VARIANT = "subsetVariant:".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PREFIX_MIN_INCL = "minIncl:".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PREFIX_MIN_EXCL = "minExcl:".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PREFIX_MAX_INCL = "maxIncl:".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PREFIX_MAX_EXCL = "maxExcl:".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PREFIX_DATE_MIN_INCL = "dateMinIncl:".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PREFIX_DATE_MAX_EXCL = "dateMaxExcl:".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PREFIX_REGEX = "regex:".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PREFIX_PRESERVE_INDENT_TRUE = "preserveIndent:true".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PREFIX_PRESERVE_INDENT_FALSE = "preserveIndent:false".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PREFIX_EQUATABLE = "equatable:".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PREFIX_COMPARABLE = "comparable:".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PREFIX_SIZE = "size:".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PREFIX_UNSIGNED_TRUE = "unsigned:true".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PREFIX_EXACT_TRUE = "exact:true".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PREFIX_MIN_SIZE = "minSize:".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PREFIX_MAX_SIZE = "maxSize:".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PREFIX_INVERTIBLE_TRUE = "invertible:true".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PREFIX_SCALE = "scale:".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PREFIX_OFFSET_TRUE = "offset:true".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PREFIX_ZONED_TRUE = "zoned:true".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PREFIX_AUDITED_TRUE = "audited:true".getBytes(StandardCharsets.UTF_8);

  private static final ThreadLocal<Set<StvnSchema>> ACTIVE_SCHEMAS =
      ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));

  /**
   * Generates a deterministic RFC-compliant Version 8 (Custom) UUID for a pure-IR {@link StvnSchema}.
   * <p>
   * Computed by wrapping the first 16 bytes of the structural SHA-256 fingerprint
   * and applying standard RFC 9562 version and variant bitmasks.
   *
   * @param schema the pure-IR schema to hash
   * @return a deterministic Version 8 UUID representing the schema's structural identity
   * @throws MalformedSchemaException if an anonymous structural cycle or poisoned schema is detected
   * @since 2.0.0
   */
  public static UUID hashSchema(StvnSchema schema) {
    Objects.requireNonNull(schema, "schema must not be null");
    byte[] hashBytes = computeSha256(schema);

    ByteBuffer buffer = ByteBuffer.wrap(hashBytes);
    long msb = buffer.getLong();
    long lsb = buffer.getLong();

    msb = (msb & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000008000L;
    lsb = (lsb & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;

    return new UUID(msb, lsb);
  }

  /**
   * Computes a deterministic SHA-256 structural identity fingerprint for a pure-IR {@link StvnSchema}.
   *
   * @param schema the pure-IR schema to digest
   * @return a 32-byte array containing the SHA-256 hash representation
   * @throws MalformedSchemaException if an anonymous structural cycle or poisoned schema is detected
   * @since 2.0.0
   */
  public static byte[] computeSha256(StvnSchema schema) {
    Objects.requireNonNull(schema, "schema must not be null");
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digestSchema(schema, digest, new HashSet<>());
      return digest.digest();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm missing from environment", e);
    }
  }

  private static void digestSchema(StvnSchema schema, MessageDigest digest, Set<String> visited) {
    if (schema instanceof StvnSchema.PoisonedSchema poisoned) {
      if (StvnPayloadLowerer.RECURSIVE_BACK_EDGE_SENTINEL.equals(poisoned.primaryDiagnosticCode())) {
        String alias = poisoned.aliasName().orElseThrow(() ->
            new MalformedSchemaException("Malformed schema: anonymous cycle detected during schema hashing.", -1, -1));
        digest.update(PREFIX_CYCLE);
        digest.update(alias.getBytes(StandardCharsets.UTF_8));
        return;
      }
      if (poisoned.bestEffortFallback().isPresent()) {
        digestSchema(poisoned.bestEffortFallback().get(), digest, visited);
        return;
      }
      throw new MalformedSchemaException(
          "Cannot compute hash for poisoned schema: " + poisoned.primaryDiagnosticCode(), -1, -1);
    }

    Set<StvnSchema> activeSchemas = ACTIVE_SCHEMAS.get();
    if (activeSchemas.contains(schema)) {
      String alias = schema.aliasName().orElseThrow(() ->
          new MalformedSchemaException("Malformed schema: anonymous cycle detected during schema hashing.", -1, -1));
      digest.update(PREFIX_CYCLE);
      digest.update(alias.getBytes(StandardCharsets.UTF_8));
      return;
    }

    activeSchemas.add(schema);
    try {
      String alias = schema.aliasName().orElse(null);
      if (alias != null) {
        digest.update(PREFIX_ALIAS);
        digest.update(alias.getBytes(StandardCharsets.UTF_8));
        if (visited.contains(alias)) {
          return;
        }
        visited = new HashSet<>(visited);
        visited.add(alias);
      }

      // 1. Digest base type constructor and enum/subset variants
      String baseType = switch (schema) {
        case StvnSchema.BooleanSchema ignored -> StvnVocabulary.TYPE_BOOLEAN;
        case StvnSchema.IntSchema ignored -> StvnVocabulary.TYPE_INT;
        case StvnSchema.FloatSchema ignored -> StvnVocabulary.TYPE_FLOAT;
        case StvnSchema.StringSchema ignored -> StvnVocabulary.TYPE_STRING;
        case StvnSchema.TimeEpochSchema ignored -> StvnVocabulary.TYPE_TIME_EPOCH;
        case StvnSchema.DateTimeSchema ignored -> StvnVocabulary.TYPE_DATE_TIME;
        case StvnSchema.EnumSchema ignored -> StvnVocabulary.TYPE_ENUM;
        case StvnSchema.EnumSubsetSchema ignored -> StvnVocabulary.TYPE_ENUM;
        case StvnSchema.TupleSchema ignored -> StvnVocabulary.TYPE_TUPLE;
        case StvnSchema.OptionSchema ignored -> StvnVocabulary.TYPE_OPTION;
        case StvnSchema.EitherSchema ignored -> StvnVocabulary.TYPE_EITHER;
        case StvnSchema.UnionSchema ignored -> StvnVocabulary.TYPE_UNION;
        case StvnSchema.SeqSchema ignored -> StvnVocabulary.TYPE_SEQ;
        case StvnSchema.SetSchema ignored -> StvnVocabulary.TYPE_SET;
        case StvnSchema.MapSchema ignored -> StvnVocabulary.TYPE_MAP;
        case StvnSchema.PoisonedSchema ignored -> throw new IllegalStateException("Handled above");
      };
      digest.update(baseType.getBytes(StandardCharsets.UTF_8));

      if (schema instanceof StvnSchema.EnumSchema en) {
        for (String variant : en.rootVariants()) {
          digest.update(PREFIX_ENUM_VARIANT);
          digest.update(variant.getBytes(StandardCharsets.UTF_8));
        }
      } else if (schema instanceof StvnSchema.EnumSubsetSchema sub) {
        for (String rootVar : sub.rootEnum().rootVariants()) {
          digest.update(PREFIX_ENUM_VARIANT);
          digest.update(rootVar.getBytes(StandardCharsets.UTF_8));
        }
        digest.update(PREFIX_SUBSET_NAME);
        digest.update(sub.aliasName().orElse("").getBytes(StandardCharsets.UTF_8));
        digest.update(PREFIX_SUBSET_PARENT);
        digest.update(sub.parentEnum().aliasName().orElse("").getBytes(StandardCharsets.UTF_8));
        digest.update(PREFIX_SUBSET_ROOT);
        digest.update(sub.rootEnum().aliasName().orElse("").getBytes(StandardCharsets.UTF_8));
        digest.update(PREFIX_SUBSET_FILTER_TYPE);
        digest.update((sub.inclusiveFilter() ? "incl" : "excl").getBytes(StandardCharsets.UTF_8));
        for (String variant : sub.activeVariants()) {
          digest.update(PREFIX_SUBSET_VARIANT);
          digest.update(variant.getBytes(StandardCharsets.UTF_8));
        }
      }

      // 2. Digest constraints in deterministic sequence
      switch (schema) {
        case StvnSchema.IntSchema i -> {
          i.explicitMinIncl().ifPresent(val -> {
            digest.update(PREFIX_MIN_INCL);
            digest.update(val.toString().getBytes(StandardCharsets.UTF_8));
          });
          i.explicitMaxExcl().ifPresent(val -> {
            digest.update(PREFIX_MAX_EXCL);
            digest.update(val.toString().getBytes(StandardCharsets.UTF_8));
          });
        }
        case StvnSchema.TimeEpochSchema te -> {
          te.explicitMinIncl().ifPresent(val -> {
            digest.update(PREFIX_MIN_INCL);
            digest.update(val.toString().getBytes(StandardCharsets.UTF_8));
          });
          te.explicitMaxExcl().ifPresent(val -> {
            digest.update(PREFIX_MAX_EXCL);
            digest.update(val.toString().getBytes(StandardCharsets.UTF_8));
          });
        }
        case StvnSchema.FloatSchema f -> {
          f.lowerBound().ifPresent(lb -> {
            digest.update(lb.kind() == BoundKind.INCLUSIVE ? PREFIX_MIN_INCL : PREFIX_MIN_EXCL);
            digest.update(lb.value().toPlainString().getBytes(StandardCharsets.UTF_8));
          });
          f.upperBound().ifPresent(ub -> {
            digest.update(ub.kind() == BoundKind.INCLUSIVE ? PREFIX_MAX_INCL : PREFIX_MAX_EXCL);
            digest.update(ub.value().toPlainString().getBytes(StandardCharsets.UTF_8));
          });
        }
        case StvnSchema.StringSchema s -> {
          s.lowerBound().ifPresent(lb -> {
            digest.update(lb.kind() == BoundKind.INCLUSIVE ? PREFIX_MIN_INCL : PREFIX_MIN_EXCL);
            digest.update(lb.value().getBytes(StandardCharsets.UTF_8));
          });
          s.upperBound().ifPresent(ub -> {
            digest.update(ub.kind() == BoundKind.INCLUSIVE ? PREFIX_MAX_INCL : PREFIX_MAX_EXCL);
            digest.update(ub.value().getBytes(StandardCharsets.UTF_8));
          });
        }
        case StvnSchema.DateTimeSchema dt -> {
          dt.explicitMinInclIso().ifPresent(val -> {
            digest.update(PREFIX_DATE_MIN_INCL);
            digest.update(val.getBytes(StandardCharsets.UTF_8));
          });
          dt.explicitMaxExclIso().ifPresent(val -> {
            digest.update(PREFIX_DATE_MAX_EXCL);
            digest.update(val.getBytes(StandardCharsets.UTF_8));
          });
        }
        default -> {
          // No interval bounds
        }
      }

      if (schema instanceof StvnSchema.StringSchema s) {
        s.rawRegexPattern().ifPresent(rx -> {
          digest.update(PREFIX_REGEX);
          digest.update(rx.getBytes(StandardCharsets.UTF_8));
        });
        digest.update(s.preserveIndent() ? PREFIX_PRESERVE_INDENT_TRUE : PREFIX_PRESERVE_INDENT_FALSE);
      } else {
        digest.update(PREFIX_PRESERVE_INDENT_FALSE);
      }

      schema.traits().explicitEquatableOverride().ifPresent(val -> {
        digest.update(PREFIX_EQUATABLE);
        digest.update(val.toString().getBytes(StandardCharsets.UTF_8));
      });
      schema.traits().explicitComparableOverride().ifPresent(val -> {
        digest.update(PREFIX_COMPARABLE);
        digest.update(val.toString().getBytes(StandardCharsets.UTF_8));
      });

      switch (schema) {
        case StvnSchema.IntSchema i -> {
          if (!i.bitWidth().equals(StvnVocabulary.DEFAULT_INT_BIT_WIDTH)) {
            digest.update(PREFIX_SIZE);
            digest.update(i.bitWidth().toString().getBytes(StandardCharsets.UTF_8));
          }
          if (i.unsigned()) {
            digest.update(PREFIX_UNSIGNED_TRUE);
          }
        }
        case StvnSchema.FloatSchema f -> {
          if (f.representation() == FloatRepresentation.IEEE_32) {
            digest.update(PREFIX_SIZE);
            digest.update("32".getBytes(StandardCharsets.UTF_8));
          } else if (f.representation() == FloatRepresentation.EXACT_DECIMAL) {
            digest.update(PREFIX_EXACT_TRUE);
            if (!f.exactMaxDigits().equals(StvnVocabulary.DEFAULT_MAX_SIZE)) {
              digest.update(PREFIX_MAX_SIZE);
              digest.update(f.exactMaxDigits().toString().getBytes(StandardCharsets.UTF_8));
            }
          }
        }
        case StvnSchema.StringSchema s -> {
          if (s.minSize().signum() > 0) {
            digest.update(PREFIX_MIN_SIZE);
            digest.update(s.minSize().toString().getBytes(StandardCharsets.UTF_8));
          }
          if (!s.maxSize().equals(StvnVocabulary.DEFAULT_MAX_SIZE)) {
            digest.update(PREFIX_MAX_SIZE);
            digest.update(s.maxSize().toString().getBytes(StandardCharsets.UTF_8));
          }
        }
        case StvnSchema.SeqSchema seq -> {
          if (seq.minSize().signum() > 0) {
            digest.update(PREFIX_MIN_SIZE);
            digest.update(seq.minSize().toString().getBytes(StandardCharsets.UTF_8));
          }
          if (!seq.maxSize().equals(StvnVocabulary.DEFAULT_MAX_SIZE)) {
            digest.update(PREFIX_MAX_SIZE);
            digest.update(seq.maxSize().toString().getBytes(StandardCharsets.UTF_8));
          }
        }
        case StvnSchema.SetSchema set -> {
          if (set.minSize().signum() > 0) {
            digest.update(PREFIX_MIN_SIZE);
            digest.update(set.minSize().toString().getBytes(StandardCharsets.UTF_8));
          }
          if (!set.maxSize().equals(StvnVocabulary.DEFAULT_MAX_SIZE)) {
            digest.update(PREFIX_MAX_SIZE);
            digest.update(set.maxSize().toString().getBytes(StandardCharsets.UTF_8));
          }
        }
        case StvnSchema.MapSchema map -> {
          if (map.minSize().signum() > 0) {
            digest.update(PREFIX_MIN_SIZE);
            digest.update(map.minSize().toString().getBytes(StandardCharsets.UTF_8));
          }
          if (!map.maxSize().equals(StvnVocabulary.DEFAULT_MAX_SIZE)) {
            digest.update(PREFIX_MAX_SIZE);
            digest.update(map.maxSize().toString().getBytes(StandardCharsets.UTF_8));
          }
          if (map.invertible()) {
            digest.update(PREFIX_INVERTIBLE_TRUE);
          }
        }
        case StvnSchema.TimeEpochSchema te -> {
          digest.update(PREFIX_SCALE);
          String scaleToken = switch (te.scale()) {
            case NS -> StvnVocabulary.SCALE_NS;
            case US -> StvnVocabulary.SCALE_US;
            case MS -> StvnVocabulary.SCALE_MS;
            case S -> StvnVocabulary.SCALE_S;
          };
          digest.update(scaleToken.getBytes(StandardCharsets.UTF_8));
        }
        case StvnSchema.DateTimeSchema dt -> {
          switch (dt.mode()) {
            case OFFSET -> digest.update(PREFIX_OFFSET_TRUE);
            case ZONED -> digest.update(PREFIX_ZONED_TRUE);
            case AUDITED -> digest.update(PREFIX_AUDITED_TRUE);
          }
        }
        default -> {
          // No additional Tier-1 facets
        }
      }

      // 3. Recursively digest structural children
      List<StvnSchema> children = StvnBinaryDecoder.extractChildSchemas(schema);
      for (StvnSchema child : children) {
        digestSchema(child, digest, visited);
      }
    } finally {
      activeSchemas.remove(schema);
    }
  }
}
