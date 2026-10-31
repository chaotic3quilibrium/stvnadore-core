package org.stvnadore.core.binary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.lowering.StvnPayloadLowerer;
import org.stvnadore.core.validation.MalformedSchemaException;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification hygiene tests for pure-IR {@link StvnSchemaHasher}.
 */
class StvnSchemaHasherHygieneTest {

  @Test
  @DisplayName("TC-HASH-01: Compute SHA-256 and UUID for schemas across all 6 base scalar types")
  void testAllBaseScalarsFingerprinting() {
    String[] sources = {
        "{ :type :Boolean :body #TRUE }",
        "{ :type :Int :body 42 }",
        "{ :type :Float :body 3.14 }",
        "{ :type :String :body \"hello\" }",
        "{ :defs { :MyEpoch { #s } :TimeEpoch } :type :MyEpoch :body 1700000000 }",
        "{ :defs { :MyDate { #offset } :DateTime } :type :MyDate :body \"2026-09-26T12:00:00Z\" }"
    };

    for (String source : sources) {
      var ir = StvnCompiler.compilePayload(source).orElseThrow();
      assertNotNull(ir.schema(), "Schema must be non-null for source: " + source);

      byte[] sha256First = StvnSchemaHasher.computeSha256(ir.schema());
      byte[] sha256Second = StvnSchemaHasher.computeSha256(ir.schema());
      assertArrayEquals(sha256First, sha256Second, "SHA-256 must be deterministic for: " + source);

      UUID uuid1 = StvnSchemaHasher.hashSchema(ir.schema());
      UUID uuid2 = StvnSchemaHasher.hashSchema(ir.schema());
      assertEquals(uuid1, uuid2, "UUID must be deterministic for: " + source);
      assertEquals(8, uuid1.version(), "UUID must be RFC Version 8 (Custom)");
      assertEquals(2, uuid1.variant(), "UUID must be RFC Variant 2");
    }
  }

  @Test
  @DisplayName("TC-HASH-02: Assert static constant byte arrays produce bit-for-bit identical SHA-256 digests to canonical specification preimage")
  void testStaticConstantByteEquivalence() throws Exception {
    // 1. Bare :Int -> ":Int" + "preserveIndent:false"
    var bareInt = StvnCompiler.compilePayload("{ :type :Int :body 42 }").orElseThrow();
    assertArrayEquals(
        sha256Of(":Int", "preserveIndent:false"),
        StvnSchemaHasher.computeSha256(bareInt.schema()),
        "Bare :Int preimage digest mismatch"
    );

    // 2. :BoundedInt -> "alias::BoundedInt" + ":Int" + "minIncl:10" + "maxExcl:100" + "preserveIndent:false"
    var boundedInt = StvnCompiler.compilePayload("""
        {
          :defs {
            :BoundedInt { #minIncl 10 #maxExcl 100 } :Int
          }
          :type :BoundedInt
          :body 42
        }
        """).orElseThrow();
    assertArrayEquals(
        sha256Of("alias::BoundedInt", ":Int", "minIncl:10", "maxExcl:100", "preserveIndent:false"),
        StvnSchemaHasher.computeSha256(boundedInt.schema()),
        ":BoundedInt preimage digest mismatch"
    );

    // 3. :LimitedString -> "alias::LimitedString" + ":String" + "regex:^[A-Z]+$" + "preserveIndent:false" + "minSize:1" + "maxSize:100"
    var limitedStr = StvnCompiler.compilePayload("""
        {
          :defs {
            :LimitedString { #minSize 1 #maxSize 100 #regex "^[A-Z]+$" } :String
          }
          :type :LimitedString
          :body "TEST"
        }
        """).orElseThrow();
    assertArrayEquals(
        sha256Of("alias::LimitedString", ":String", "regex:^[A-Z]+$", "preserveIndent:false", "minSize:1", "maxSize:100"),
        StvnSchemaHasher.computeSha256(limitedStr.schema()),
        ":LimitedString preimage digest mismatch"
    );
  }

  @Test
  @DisplayName("TC-HASH-03: Verify anonymous cycle detection and nominal cycle determinism")
  void testCycleDetection() {
    var doc = StvnCompiler.compilePayload("""
        {
          :defs {
            :Node :Option(:Node)
          }
          :type :Node
          :body #None
        }
        """).orElseThrow();

    UUID uuid1 = StvnSchemaHasher.hashSchema(doc.schema());
    UUID uuid2 = StvnSchemaHasher.hashSchema(doc.schema());
    assertEquals(uuid1, uuid2, "Nominal recursive cycle must hash deterministically");

    // Anonymous recursive back-edge sentinel without an aliasName must fail closed with MalformedSchemaException
    var anonymousBackEdge = new StvnSchema.PoisonedSchema(
        Optional.empty(),
        StvnPayloadLowerer.RECURSIVE_BACK_EDGE_SENTINEL,
        Optional.empty(),
        StvnSchema.TraitProfile.ofDefault(true, false)
    );
    var anonymousCyclicSeq = new StvnSchema.SeqSchema(
        Optional.empty(),
        BigInteger.ZERO,
        StvnVocabulary.DEFAULT_MAX_SIZE,
        anonymousBackEdge,
        StvnSchema.TraitProfile.ofDefault(true, false)
    );

    assertThrows(
        MalformedSchemaException.class,
        () -> StvnSchemaHasher.hashSchema(anonymousCyclicSeq),
        "Anonymous cycle must throw MalformedSchemaException"
    );
  }

  private static byte[] sha256Of(String... chunks) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    for (String chunk : chunks) {
      md.update(chunk.getBytes(StandardCharsets.UTF_8));
    }
    return md.digest();
  }
}
