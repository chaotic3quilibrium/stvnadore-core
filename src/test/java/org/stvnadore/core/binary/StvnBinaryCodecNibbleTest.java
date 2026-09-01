package org.stvnadore.core.binary;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.binary.exceptions.PoisonedRegistryPayloadException;
import org.stvnadore.core.binary.exceptions.StvnSerializationException;
import org.stvnadore.core.binary.exceptions.UnsupportedEncodingStrategyException;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Test suite verifying 4:4 bitwise packing and unpacking of Header Byte 4 (Control Byte),
 * fail-fast handling for unmapped upper and lower nibbles, and zero-trust cryptographic verification.
 *
 * @since 1.0.0
 */
class StvnBinaryCodecNibbleTest {

  @Test
  @DisplayName("TC-ENC-01: Bitwise packing correctly combines upper and lower nibbles")
  void testBitwisePackingZeroCopyPostOrderStrategy() {
    var ir = StvnCompiler.compile("{ :type :Int32 :body 42 }").orElseThrow();
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault(), BinaryEncodingStrategy.ZERO_COPY_POST_ORDER);
    ByteBuffer buf = encoder.encode(ir);

    byte controlByte = buf.get(4);
    int upper = (controlByte >>> 4) & 0x0F;
    int lower = controlByte & 0x0F;

    Assertions.assertEquals(0x00, upper, "Upper nibble must be 0x00 for ZERO_COPY_POST_ORDER encoding");
    Assertions.assertEquals(0x00, lower, "Lower nibble must be 0x00 for UniversalDefault");
    Assertions.assertEquals(0x00, controlByte);

    // Verify all 9 SchemaIdentityStrategy codes (0x00 to 0x08) pack correctly
    Assertions.assertEquals(0x00, new SchemaIdentityStrategy.UniversalDefault().code());
    Assertions.assertEquals(0x01, new SchemaIdentityStrategy.UuidV8Hash().code());
    Assertions.assertEquals(0x02, new SchemaIdentityStrategy.Sha256Hash().code());
    Assertions.assertEquals(0x03, new SchemaIdentityStrategy.AsciiStringKey("key").code());
    Assertions.assertEquals(0x04, new SchemaIdentityStrategy.UnicodeStringKey("key").code());
    Assertions.assertEquals(0x05, new SchemaIdentityStrategy.UniversalVersion(1L).code());
    Assertions.assertEquals(0x06, new SchemaIdentityStrategy.ExplicitUuid(java.util.UUID.randomUUID()).code());
    Assertions.assertEquals(0x07, new SchemaIdentityStrategy.ExplicitSha256(new byte[32]).code());
    Assertions.assertEquals(0x08, new SchemaIdentityStrategy.SelfDescribingSchema("schema").code());
  }

  @ParameterizedTest
  @ValueSource(ints = {0x10, 0x20, 0x30, 0x70, 0x80, 0x90, 0xF0})
  @DisplayName("TC-DEC-01: Unmapped upper nibble throws UnsupportedEncodingStrategyException")
  void testUnmappedUpperNibbleThrowsUnsupportedEncodingStrategyException(int corruptedByte4) {
    var ir = StvnCompiler.compile("{ :type :Int32 :body 42 }").orElseThrow();
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
    ByteBuffer buf = encoder.encode(ir);

    ByteBuffer corrupted = buf.duplicate();
    corrupted.put(4, (byte) corruptedByte4);

    var ex = Assertions.assertThrows(
        UnsupportedEncodingStrategyException.class,
        () -> StvnBinaryDecoder.open(corrupted)
    );
    Assertions.assertEquals((corruptedByte4 >>> 4) & 0x0F, ex.getStrategyCode());
  }

  @ParameterizedTest
  @ValueSource(ints = {0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F})
  @DisplayName("TC-DEC-02: Unmapped lower nibble throws StvnSerializationException")
  void testUnmappedLowerNibbleThrowsStvnSerializationException(int corruptedByte4) {
    var ir = StvnCompiler.compile("{ :type :Int32 :body 42 }").orElseThrow();
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
    ByteBuffer buf = encoder.encode(ir);

    ByteBuffer corrupted = buf.duplicate();
    corrupted.put(4, (byte) corruptedByte4);

    Assertions.assertThrows(
        StvnSerializationException.class,
        () -> StvnBinaryDecoder.open(corrupted)
    );
  }

  @Test
  @DisplayName("TC-DEC-03: Zero-trust SHA-256 mismatch throws PoisonedRegistryPayloadException")
  void testTamperedSha256ThrowsPoisonedRegistryPayloadException() {
    var ir = StvnCompiler.compile("{ :type :Int32 :body 42 }").orElseThrow();
    byte[] hash = StvnSchemaHasher.computeSha256(ir.schema());
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.ExplicitSha256(hash));
    ByteBuffer buf = encoder.encode(ir);

    // Tamper with byte in SHA-256 payload (Byte 5 is the first byte of the 32B hash)
    ByteBuffer tampered = buf.duplicate();
    tampered.put(5, (byte) (tampered.get(5) ^ 0xFF));

    Assertions.assertThrows(
        PoisonedRegistryPayloadException.class,
        () -> {
          var root = StvnBinaryDecoder.open(tampered);
          StvnBinaryDecoder.unpack(root, Optional.of(ir.schema()));
        }
    );
  }
}
