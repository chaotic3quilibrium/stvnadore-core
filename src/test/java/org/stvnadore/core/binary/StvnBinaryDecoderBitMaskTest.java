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
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.zip.CRC32C;

/**
 * Tests verifying high-bit mask enforcement for non-byte-aligned integer bit-widths
 * and cryptographic schema tampering detection.
 *
 * @since 1.0.0
 */
class StvnBinaryDecoderBitMaskTest {

  @Test
  void testUint1BitMaskValidAndCorrupted() {
    var val = StvnCompiler.compilePayload("{ :defs { :U1 { #unsigned #size 1 } :Int } :type :U1 :body 1 }").orElseThrow();
    var schema = val.schema();

    // Valid: bit-0 is 1, upper 7 bits are 0
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
    var validBuffer = encoder.encode(new StvnValue.StvnInt(schema, BigInteger.ONE));

    var root = StvnBinaryDecoder.open(validBuffer);
    var decoded = (StvnValue.StvnInt) StvnBinaryDecoder.unpack(root, Optional.of(schema));
    Assertions.assertEquals(BigInteger.ONE, decoded.value());

    // Corrupted: set bit-1 (0x02) in the data payload
    var corruptedBuffer = validBuffer.duplicate();
    int payloadOffset = corruptedBuffer.capacity() - 1;
    int b1 = Byte.toUnsignedInt(corruptedBuffer.get(payloadOffset)) | 0x02;
    corruptedBuffer.put(payloadOffset, (byte) b1);

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
    var val = StvnCompiler.compilePayload("{ :defs { :U3 { #unsigned #size 3 } :Int } :type :U3 :body 7 }").orElseThrow();
    var schema = val.schema();

    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
    var validBuffer = encoder.encode(new StvnValue.StvnInt(schema, BigInteger.valueOf(7)));

    var root = StvnBinaryDecoder.open(validBuffer);
    var decoded = (StvnValue.StvnInt) StvnBinaryDecoder.unpack(root, Optional.of(schema));
    Assertions.assertEquals(BigInteger.valueOf(7), decoded.value());

    // Corrupted: set bit-3 (0x08) in the data payload
    var corruptedBuffer = validBuffer.duplicate();
    int payloadOffset = corruptedBuffer.capacity() - 1;
    int b3 = Byte.toUnsignedInt(corruptedBuffer.get(payloadOffset)) | 0x08;
    corruptedBuffer.put(payloadOffset, (byte) b3);

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
    var val = StvnCompiler.compilePayload("{ :defs { :I7 { #size 7 } :Int } :type :I7 :body 63 }").orElseThrow();
    var schema = val.schema();

    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
    var validBuffer = encoder.encode(new StvnValue.StvnInt(schema, BigInteger.valueOf(63)));

    var root = StvnBinaryDecoder.open(validBuffer);
    var decoded = (StvnValue.StvnInt) StvnBinaryDecoder.unpack(root, Optional.of(schema));
    Assertions.assertEquals(BigInteger.valueOf(63), decoded.value());

    // Corrupted: set bit-7 (0x80)
    var corruptedBuffer = validBuffer.duplicate();
    int payloadOffset = corruptedBuffer.capacity() - 1;
    int b7 = Byte.toUnsignedInt(corruptedBuffer.get(payloadOffset)) | 0x80;
    corruptedBuffer.put(payloadOffset, (byte) b7);

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
    var val = StvnCompiler.compilePayload("{ :defs { :U49 { #unsigned #size 49 } :Int } :type :U49 :body 1 }").orElseThrow();
    var schema = val.schema();

    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
    var validBuffer = encoder.encode(new StvnValue.StvnInt(schema, BigInteger.ONE));

    var root = StvnBinaryDecoder.open(validBuffer);
    var decoded = (StvnValue.StvnInt) StvnBinaryDecoder.unpack(root, Optional.of(schema));
    Assertions.assertEquals(BigInteger.ONE, decoded.value());

    // Corrupted: set bit 1 (0x02) in the most significant byte (byte offset + 6 in little-endian)
    var corruptedBuffer = validBuffer.duplicate();
    int payloadOffset = corruptedBuffer.capacity() - 1;
    int b49 = Byte.toUnsignedInt(corruptedBuffer.get(payloadOffset)) | 0x02;
    corruptedBuffer.put(payloadOffset, (byte) b49);

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
    var ir = StvnCompiler.compilePayload("{ :type :Int :body 42 }").orElseThrow();
    var schema = ir.schema();
    byte[] correctHash = StvnSchemaHasher.computeSha256(schema);

    // Encode with correct hash in ExplicitSha256 strategy
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.ExplicitSha256(correctHash));
    var encoded = encoder.encode(ir);

    // Tamper with the hash in the encoded buffer (header hash starts at byte index 6)
    var tampered = encoded.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    int flipped = Byte.toUnsignedInt(tampered.get(6)) ^ 0xFF;
    tampered.put(6, (byte) flipped);
    CRC32C crc = new CRC32C();
    ByteBuffer view = tampered.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    view.position(0);
    view.limit(tampered.limit() - 4);
    crc.update(view);
    tampered.putInt(tampered.limit() - 4, (int) crc.getValue());

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
    Assertions.assertNotNull(StvnPrelude.preludeDocument());
    var result = StvnCompiler.compilePayload("{ :type :Boolean :body #TRUE }");
    Assertions.assertTrue(result.isPresent());
  }

  @Test
  void testTripartiteBinaryCodecRoundTrip() {
    var source = """
        {
          :defs {
            :DTOffset { #offset } :DateTime
            :DTZoned { #zoned } :DateTime
            :DTAudited { #audited } :DateTime
            :AuditRecord :Tuple( :DTOffset :DTZoned :DTAudited )
          }
          :type :AuditRecord
          :body (
            "2026-03-15T08:00:00-05:00"
            "2026-03-15T08:00:00[America/Chicago]"
            "2026-03-15T08:00:00-05:00[America/Chicago]"
          )
        }
        """;
    var ast = StvnCompiler.compilePayload(source).orElseThrow();
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
    var encoded = encoder.encode(ast);

    var root = StvnBinaryDecoder.open(encoded);
    var decodedAst = StvnBinaryDecoder.unpackIr(root, Optional.of(ast.schema()));

    Assertions.assertEquals(ast, decodedAst);
  }
}
