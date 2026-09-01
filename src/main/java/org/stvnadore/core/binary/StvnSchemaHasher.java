package org.stvnadore.core.binary;

import org.stvnadore.core.validation.StvnTypeResolver;
import org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema;
import org.stvnadore.core.validation.StvnTypeResolver.StvnConstraints;
import org.stvnadore.core.validation.MalformedSchemaException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Deterministic schema hashing engine for STVN.
 * <p>
 * Generates structural fingerprint identities (such as RFC-compliant Version 8 UUIDs or SHA-256 hashes)
 * by recursively digesting schema definitions. The hashing process digests:
 * <ol>
 *   <li>The primitive base type name (e.g. {@code :Int32}, {@code :Seq}).</li>
 *   <li>Metadata constraints in a deterministic sequence (bounds, regex, preserveIndent, equatable, comparable).</li>
 *   <li>Recursive nested structures (tuple items, union branches, underlying aliases).</li>
 * </ol>
 * <p>
 * To prevent stack overflows or infinite loops, cycles are tracked using thread-local active checks.
 * Anonymous cycles trigger a {@link MalformedSchemaException}, whereas nominal circular definitions
 * write their alias identifier to break the cycle deterministically.
 *
 * @since 1.0.0
 */
public class StvnSchemaHasher {

  private StvnSchemaHasher() {
    // Utility class, non-instantiable
  }

  private static final ThreadLocal<java.util.Set<ResolvedSchema>> ACTIVE_SCHEMAS =
      ThreadLocal.withInitial(() -> java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));

  private static final ThreadLocal<java.util.Set<org.antlr.v4.runtime.ParserRuleContext>> ACTIVE_NODES =
      ThreadLocal.withInitial(() -> java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));

  /**
   * Generates a deterministic RFC-compliant Version 8 (Custom) UUID based on the structural constraints of the schema.
   * <p>
   * This is computed by wrapping the first 16 bytes of the structural SHA-256 fingerprint
   * and applying standard RFC 9562 version and variant bitmasks.
   *
   * @param schema the resolved schema to hash
   * @return a deterministic Version 8 UUID representing the schema's identity
   * @throws MalformedSchemaException if an anonymous cycle is detected
   */
  public static UUID hashSchema(ResolvedSchema schema) {
    byte[] hashBytes = computeSha256(schema);

    // Construct a UUID from the first 16 bytes of the SHA-256 hash
    ByteBuffer buffer = ByteBuffer.wrap(hashBytes);
    long msb = buffer.getLong();
    long lsb = buffer.getLong();

    // Apply RFC standard Version 8 (Custom) UUID masking
    msb = (msb & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000008000L;
    lsb = (lsb & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;

    return new UUID(msb, lsb);
  }

  /**
   * Computes a deterministic SHA-256 structural identity fingerprint for the given schema.
   *
   * @param schema the resolved schema to digest
   * @return a byte array containing the SHA-256 hash representation
   * @throws MalformedSchemaException if an anonymous structural cycle is detected
   */
  public static byte[] computeSha256(ResolvedSchema schema) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digestSchema(schema, digest);
      return digest.digest();
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 algorithm missing from environment", e);
    }
  }

  private static void digestSchema(ResolvedSchema schema, MessageDigest digest) {
    digestSchema(schema, digest, new java.util.HashSet<>());
  }

  private static void digestSchema(ResolvedSchema schema, MessageDigest digest, java.util.Set<String> visited) {
    if (schema == null) return;

    var activeSchemas = ACTIVE_SCHEMAS.get();
    var activeNodes = ACTIVE_NODES.get();

    boolean isSchemaCycle = activeSchemas.contains(schema);
    boolean isNodeCycle = schema.node() != null && activeNodes.contains(schema.node());

    if (isSchemaCycle || isNodeCycle) {
      String alias = schema.aliasName().orElseThrow(() ->
          new MalformedSchemaException("Malformed schema: anonymous cycle detected during schema hashing."));
      digest.update(("cycle:" + alias).getBytes(StandardCharsets.UTF_8));
      return;
    }

    activeSchemas.add(schema);
    if (schema.node() != null) {
      activeNodes.add(schema.node());
    }

    try {
      String alias = schema.aliasName().orElse(null);
      if (alias != null) {
        digest.update(("alias:" + alias).getBytes(StandardCharsets.UTF_8));
        if (visited.contains(alias)) {
          return;
        }
        visited = new java.util.HashSet<>(visited);
        visited.add(alias);
      }

      // 1. Digest the primitive base type (e.g., ":String", ":Int32")
      String baseType = StvnTypeResolver.getPrimitiveBaseType(schema.node());
      if (baseType != null) {
        digest.update(baseType.getBytes(StandardCharsets.UTF_8));
      }

      // 2. Digest constraints in a strictly deterministic order
      StvnConstraints constraints = schema.constraints();
      constraints.minIncl().ifPresent(val -> digest.update(("minIncl:" + val).getBytes(StandardCharsets.UTF_8)));
      constraints.minExcl().ifPresent(val -> digest.update(("minExcl:" + val).getBytes(StandardCharsets.UTF_8)));
      constraints.maxIncl().ifPresent(val -> digest.update(("maxIncl:" + val).getBytes(StandardCharsets.UTF_8)));
      constraints.maxExcl().ifPresent(val -> digest.update(("maxExcl:" + val).getBytes(StandardCharsets.UTF_8)));
      constraints.regex().ifPresent(val -> digest.update(("regex:" + val).getBytes(StandardCharsets.UTF_8)));

      digest.update(("preserveIndent:" + constraints.preserveIndent()).getBytes(StandardCharsets.UTF_8));

      constraints.equatable().ifPresent(val -> digest.update(("equatable:" + val).getBytes(StandardCharsets.UTF_8)));
      constraints.comparable().ifPresent(val -> digest.update(("comparable:" + val).getBytes(StandardCharsets.UTF_8)));

      // 3. Recursively digest structural children (e.g., Tuple elements, Map Key/Values)
      List<ResolvedSchema> children = StvnBinaryDecoder.extractChildSchemas(schema);
      for (ResolvedSchema child : children) {
        digestSchema(child, digest, visited);
      }
    } finally {
      activeSchemas.remove(schema);
      if (schema.node() != null) {
        activeNodes.remove(schema.node());
      }
    }
  }
}