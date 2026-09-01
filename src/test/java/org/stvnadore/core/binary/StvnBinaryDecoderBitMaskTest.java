package org.stvnadore.core.binary;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.binary.exceptions.PoisonedRegistryPayloadException;
import org.stvnadore.core.binary.exceptions.StvnCorruptedBitPatternException;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.stdlib.StvnPrelude;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;

/**
 * Tests verifying high-bit mask enforcement for non-byte-aligned integer bit-widths
 * and cryptographic schema tampering detection.
 *
 * @since 1.0.0
 */
class StvnBinaryDecoderBitMaskTest {

  @Test
  void testUint1BitMaskValidAndCorrupted() {
    var doc = StvnCompiler.compile("{ :type :Uint1 :body 1 }").orElseThrow();
    var schema = doc.schema();

    // Valid: bit-0 is 1, upper 7 bits are 0
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
    var validBuffer = encoder.encode(new StvnValue.StvnInteger(schema, BigInteger.ONE, 1, true));

    var root = StvnBinaryDecoder.open(validBuffer);
    var decoded = (StvnValue.StvnInteger) StvnBinaryDecoder.unpack(root, Optional.of(schema));
    Assertions.assertEquals(BigInteger.ONE, decoded.value());

    // Corrupted: set bit-1 (0x02) in the data payload
    var corruptedBuffer = validBuffer.duplicate();
    // Offset 6 is the data payload byte
    int payloadOffset = corruptedBuffer.capacity() - 1;
    corruptedBuffer.put(payloadOffset, (byte) (corruptedBuffer.get(payloadOffset) | 0x02));

    Assertions.assertThrows(
        StvnCorruptedBitPatternException.class,
        () -> {
          var r = StvnBinaryDecoder.open(corruptedBuffer);
          StvnBinaryDecoder.unpack(r, Optional.of(schema));
        }
    );
  }

  @Test
  void testUint3BitMaskValidAndCorrupted() {
    var doc = StvnCompiler.compile("{ :type :Uint3 :body 7 }").orElseThrow();
    var schema = doc.schema();

    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
    var validBuffer = encoder.encode(new StvnValue.StvnInteger(schema, BigInteger.valueOf(7), 3, true));

    var root = StvnBinaryDecoder.open(validBuffer);
    var decoded = (StvnValue.StvnInteger) StvnBinaryDecoder.unpack(root, Optional.of(schema));
    Assertions.assertEquals(BigInteger.valueOf(7), decoded.value());

    // Corrupted: set bit-3 (0x08) in the data payload
    var corruptedBuffer = validBuffer.duplicate();
    int payloadOffset = corruptedBuffer.capacity() - 1;
    corruptedBuffer.put(payloadOffset, (byte) (corruptedBuffer.get(payloadOffset) | 0x08));

    Assertions.assertThrows(
        StvnCorruptedBitPatternException.class,
        () -> {
          var r = StvnBinaryDecoder.open(corruptedBuffer);
          StvnBinaryDecoder.unpack(r, Optional.of(schema));
        }
    );
  }

  @Test
  void testInt7BitMaskValidAndCorrupted() {
    var doc = StvnCompiler.compile("{ :type :Int7 :body 63 }").orElseThrow();
    var schema = doc.schema();

    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
    var validBuffer = encoder.encode(new StvnValue.StvnInteger(schema, BigInteger.valueOf(63), 7, false));

    var root = StvnBinaryDecoder.open(validBuffer);
    var decoded = (StvnValue.StvnInteger) StvnBinaryDecoder.unpack(root, Optional.of(schema));
    Assertions.assertEquals(BigInteger.valueOf(63), decoded.value());

    // Corrupted: set bit-7 (0x80)
    var corruptedBuffer = validBuffer.duplicate();
    int payloadOffset = corruptedBuffer.capacity() - 1;
    corruptedBuffer.put(payloadOffset, (byte) (corruptedBuffer.get(payloadOffset) | (byte) 0x80));

    Assertions.assertThrows(
        StvnCorruptedBitPatternException.class,
        () -> {
          var r = StvnBinaryDecoder.open(corruptedBuffer);
          StvnBinaryDecoder.unpack(r, Optional.of(schema));
        }
    );
  }

  @Test
  void testUint49BitMaskValidAndCorrupted() {
    // 49 bits -> 7 bytes. 49 % 8 = 1. Valid mask for the 7th byte (highest byte) is 0x01.
    var doc = StvnCompiler.compile("{ :type :Uint49 :body 1 }").orElseThrow();
    var schema = doc.schema();

    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
    var validBuffer = encoder.encode(new StvnValue.StvnInteger(schema, BigInteger.ONE, 49, true));

    var root = StvnBinaryDecoder.open(validBuffer);
    var decoded = (StvnValue.StvnInteger) StvnBinaryDecoder.unpack(root, Optional.of(schema));
    Assertions.assertEquals(BigInteger.ONE, decoded.value());

    // Corrupted: set bit 1 (0x02) in the most significant byte (byte offset + 6 in little-endian)
    var corruptedBuffer = validBuffer.duplicate();
    // In little-endian, byte index for most-significant byte is base data offset + 6
    int payloadOffset = corruptedBuffer.capacity() - 1; // Highest byte in 7-byte integer payload
    corruptedBuffer.put(payloadOffset, (byte) (corruptedBuffer.get(payloadOffset) | 0x02));

    Assertions.assertThrows(
        StvnCorruptedBitPatternException.class,
        () -> {
          var r = StvnBinaryDecoder.open(corruptedBuffer);
          StvnBinaryDecoder.unpack(r, Optional.of(schema));
        }
    );
  }

  @Test
  void testControlByte0x07TamperingThrowsPoisonedRegistryPayloadException() {
    var ir = StvnCompiler.compile("{ :type :Int32 :body 42 }").orElseThrow();
    var schema = ir.schema();
    byte[] correctHash = StvnSchemaHasher.computeSha256(schema);

    // Encode with correct hash in ExplicitSha256 strategy
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.ExplicitSha256(correctHash));
    var encoded = encoder.encode(ir);

    // Tamper with the hash in the encoded buffer (header hash starts at byte index 5)
    var tampered = encoded.duplicate();
    tampered.put(5, (byte) (tampered.get(5) ^ 0xFF));

    Assertions.assertThrows(
        PoisonedRegistryPayloadException.class,
        () -> {
          var root = StvnBinaryDecoder.open(tampered, null, null, null);
          StvnBinaryDecoder.unpack(root, Optional.of(schema));
        }
    );
  }

  @Test
  void testJpmsModuleExportAccess() {
    // Verify accessibility of StvnCompiler and StvnPrelude from exported packages
    Assertions.assertNotNull(StvnCompiler.class.getModule());
    Assertions.assertNotNull(StvnPrelude.getPreludeDocument());
    var result = StvnCompiler.compile("{ :type :Boolean :body #TRUE }");
    Assertions.assertTrue(result.isPresent());
  }

  @Test
  void testTripartiteBinaryCodecRoundTrip() {
    var source = """
        {
          :defs {
            :AuditRecord :Tuple( :DateTimeOffset :DateTimeZoned :DateTimeAudited )
          }
          :type :AuditRecord
          :body (
            "2026-03-15T08:00:00-05:00"
            "2026-03-15T08:00:00[America/Chicago]"
            "2026-03-15T08:00:00-05:00[America/Chicago]"
          )
        }
        """;
    var ast = StvnCompiler.compile(source).orElseThrow();
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
    var encoded = encoder.encode(ast);

    var root = StvnBinaryDecoder.open(encoded);
    var decodedAst = StvnBinaryDecoder.unpack(root, Optional.of(ast.schema()));

    Assertions.assertEquals(ast, decodedAst);
  }
}
