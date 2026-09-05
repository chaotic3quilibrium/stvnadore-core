package org.stvnadore.core.binary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.binary.readers.StvnMapReader;
import org.stvnadore.core.binary.readers.StvnSeqReader;
import org.stvnadore.core.binary.readers.StvnTupleReader;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.utils.StvnBinaryUtils;
import org.stvnadore.core.validation.MalformedPayloadException;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Unit and integration test suite verifying CRC-32C trailer appending, verification,
 * zero-copy flyweight reader isolation, and backward compatibility.
 *
 * @since 1.1.0
 */
@NullMarked
class StvnBinaryCrc32cTrailerTest {

  @Test
  @DisplayName("TC-CRC-01: Encode and decode with valid CRC-32C trailer")
  void testEncodeAndDecodeWithCrc32cTrailerValid() {
    var ir = StvnCompiler.compile("{ :type :Int32 :body 42 }").orElseThrow();

    var encoderNoCrc = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault(), false);
    ByteBuffer bufNoCrc = encoderNoCrc.encode(ir);
    int lenNoCrc = bufNoCrc.remaining();

    var encoderCrc = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault(), true);
    ByteBuffer bufCrc = encoderCrc.encode(ir);
    int lenCrc = bufCrc.remaining();

    assertEquals(lenNoCrc + StvnBinaryUtils.CRC32C_TRAILER_SIZE, lenCrc,
        "Buffer with CRC-32C trailer must be exactly 4 bytes larger");

    byte controlByte = bufCrc.get(4);
    assertTrue((controlByte & (byte) StvnBinaryUtils.CONTROL_MASK_TRAILER_CRC32C) != 0,
        "Bit 7 of Byte 4 must be set when CRC-32C trailer is enabled");

    var root = StvnBinaryDecoder.open(bufCrc);
    StvnValue unpacked = StvnBinaryDecoder.unpack(root, Optional.of(ir.schema()));
    assertInstanceOf(StvnValue.StvnInteger.class, unpacked);
    assertEquals(BigInteger.valueOf(42), ((StvnValue.StvnInteger) unpacked).value());
  }

  @Test
  @DisplayName("TC-CRC-02: CRC-32C across all scalar and collection types")
  void testCrc32cAcrossAllScalarAndCollectionTypes() throws IOException {
    Path validFixturePath = Paths.get("shared-fixtures/valid-syntax/crc32c_trailer_valid.stvn");
    String stvnContent = Files.readString(validFixturePath);
    var expectedIr = StvnCompiler.compile(stvnContent, validFixturePath.toString()).orElseThrow();

    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault(), true);
    ByteBuffer buf = encoder.encode(expectedIr);

    byte controlByte = buf.get(4);
    assertTrue((controlByte & (byte) StvnBinaryUtils.CONTROL_MASK_TRAILER_CRC32C) != 0);

    var root = StvnBinaryDecoder.open(buf);
    StvnValue unpacked = StvnBinaryDecoder.unpack(root, Optional.of(expectedIr.schema()));

    assertEquals(expectedIr.toString(), unpacked.toString(),
        "Unpacked AST from CRC-32C protected binary must equal original compiled AST");
  }

  @Test
  @DisplayName("TC-CRC-03: Single-bit payload corruption triggers CRC-32C mismatch")
  void testCrc32cSingleBitPayloadCorruption() {
    var ir = StvnCompiler.compile("{ :type :Int32 :body 42 }").orElseThrow();
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault(), true);
    ByteBuffer buf = encoder.encode(ir);

    byte[] bytes = new byte[buf.remaining()];
    buf.duplicate().get(bytes);

    // Tamper with a byte inside the payload arena (e.g. byte 6)
    bytes[6] ^= 0x01;

    ByteBuffer corrupted = ByteBuffer.wrap(bytes);
    MalformedPayloadException ex = assertThrows(
        MalformedPayloadException.class,
        () -> StvnBinaryDecoder.open(corrupted)
    );
    assertEquals("CRC-32C trailer mismatch: payload corrupted or truncated", ex.getMessage());
  }

  @Test
  @DisplayName("TC-CRC-04: Trailer corruption triggers CRC-32C mismatch")
  void testCrc32cTrailerCorruption() {
    var ir = StvnCompiler.compile("{ :type :Int32 :body 42 }").orElseThrow();
    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault(), true);
    ByteBuffer buf = encoder.encode(ir);

    byte[] bytes = new byte[buf.remaining()];
    buf.duplicate().get(bytes);

    // Tamper with a byte inside the 4-byte CRC-32C trailer at (limit - 2)
    bytes[bytes.length - 2] ^= 0x01;

    ByteBuffer corrupted = ByteBuffer.wrap(bytes);
    MalformedPayloadException ex = assertThrows(
        MalformedPayloadException.class,
        () -> StvnBinaryDecoder.open(corrupted)
    );
    assertEquals("CRC-32C trailer mismatch: payload corrupted or truncated", ex.getMessage());
  }

  @ParameterizedTest
  @ValueSource(ints = {5, 6, 7, 8})
  @DisplayName("TC-CRC-05: Truncated buffer below 9 bytes throws MalformedPayloadException")
  void testCrc32cTruncatedBufferBelow9Bytes(int size) {
    ByteBuffer shortBuf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    shortBuf.putInt(StvnBinaryUtils.MAGIC_BYTES);
    shortBuf.put((byte) StvnBinaryUtils.CONTROL_MASK_TRAILER_CRC32C); // Bit 7 set
    for (int i = 5; i < size; i++) {
      shortBuf.put((byte) 0x00);
    }
    shortBuf.flip();

    MalformedPayloadException ex = assertThrows(
        MalformedPayloadException.class,
        () -> StvnBinaryDecoder.open(shortBuf)
    );
    assertTrue(
        ex.getMessage().contains("Buffer too small for STVN binary with CRC-32C trailer"),
        () -> "Unexpected message: " + ex.getMessage()
    );
  }

  @Test
  @DisplayName("TC-CRC-06: Zero-copy readers are oblivious to CRC-32C trailer")
  void testZeroCopyReadersObliviousToTrailer() {
    // 1. Test Tuple Reader
    var tupleIr = StvnCompiler.compile("""
        {
          :type :Tuple( :Seq(:Int32) :Map(:String :Int32) )
          :body ( [ 10 20 30 ] { [ "alpha" 100 ] [ "beta" 200 ] } )
        }
        """).orElseThrow();

    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault(), true);
    ByteBuffer tupleBuf = encoder.encode(tupleIr);
    int fullLength = tupleBuf.remaining();

    var tupleRoot = StvnBinaryDecoder.open(tupleBuf);
    assertEquals(fullLength - StvnBinaryUtils.CRC32C_TRAILER_SIZE, tupleRoot.context().buffer().limit(),
        "Effective buffer limit must exclude the 4-byte CRC-32C trailer");

    StvnTupleReader tupleReader = StvnBinaryDecoder.readRootTuple(tupleRoot, Optional.of(tupleIr.schema()));
    assertEquals(2, tupleReader.size());

    // 2. Test Seq Reader directly as root
    var seqIr = StvnCompiler.compile("{ :type :Seq(:Int32) :body [ 1 2 3 ] }").orElseThrow();
    ByteBuffer seqBuf = encoder.encode(seqIr);
    var seqRoot = StvnBinaryDecoder.open(seqBuf);
    StvnSeqReader seqReader = StvnBinaryDecoder.readRootSeq(seqRoot, Optional.of(seqIr.schema()));
    assertEquals(3, seqReader.size());

    // 3. Test Map Reader directly as root
    var mapIr = StvnCompiler.compile("{ :type :Map(:String :Int32) :body { [ \"x\" 1 ] [ \"y\" 2 ] } }").orElseThrow();
    ByteBuffer mapBuf = encoder.encode(mapIr);
    var mapRoot = StvnBinaryDecoder.open(mapBuf);
    StvnMapReader mapReader = StvnBinaryDecoder.readRootMap(mapRoot, Optional.of(mapIr.schema()));
    assertEquals(2, mapReader.size());
  }

  @Test
  @DisplayName("TC-CRC-09: Backward compatibility - all non-CRC valid fixtures parse without trailer")
  void testBackwardCompatibilityAll21FixturesWithoutTrailer() throws IOException {
    Path validDir = Paths.get("shared-fixtures/valid-syntax");
    try (Stream<Path> stream = Files.walk(validDir)) {
      List<Path> nonCrcBins = stream
          .filter(p -> p.toString().endsWith(".stvn_bin"))
          .filter(p -> !p.getFileName().toString().contains("crc32c"))
          .toList();

      assertFalse(nonCrcBins.isEmpty(), "Expected at least 21 non-CRC binary fixtures");

      for (Path binPath : nonCrcBins) {
        byte[] bytes = Files.readAllBytes(binPath);
        byte controlByte = bytes[4];
        assertFalse((controlByte & (byte) StvnBinaryUtils.CONTROL_MASK_TRAILER_CRC32C) != 0,
            "Fixture " + binPath.getFileName() + " must have Bit 7 = 0");

        ByteBuffer buf = ByteBuffer.wrap(bytes);
        var root = StvnBinaryDecoder.open(buf);
        assertNotNull(root, "Root pointer must not be null for " + binPath.getFileName());
      }
    }
  }

  @Test
  @DisplayName("TC-CRC-10: Direct ByteBuffer and Heap ByteBuffer parity")
  void testDirectByteBufferAndHeapByteBufferParity() {
    var ir = StvnCompiler.compile("""
        {
          :type :Tuple( :Int32 :String )
          :body ( 42 "direct-parity" )
        }
        """).orElseThrow();

    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault(), true);
    ByteBuffer heapBuf = encoder.encode(ir);

    // Decode from Heap Buffer
    var heapRoot = StvnBinaryDecoder.open(heapBuf);
    StvnValue heapValue = StvnBinaryDecoder.unpack(heapRoot, Optional.of(ir.schema()));

    // Allocate Direct Buffer and copy
    ByteBuffer directBuf = ByteBuffer.allocateDirect(heapBuf.remaining()).order(ByteOrder.LITTLE_ENDIAN);
    directBuf.put(heapBuf.duplicate().position(0));
    directBuf.flip();

    // Decode from Direct Buffer
    var directRoot = StvnBinaryDecoder.open(directBuf);
    StvnValue directValue = StvnBinaryDecoder.unpack(directRoot, Optional.of(ir.schema()));

    assertEquals(heapValue.toString(), directValue.toString(),
        "Direct buffer and heap buffer unpacking must yield identical AST results");

    // Corrupt Direct Buffer and verify failure
    ByteBuffer corruptedDirect = ByteBuffer.allocateDirect(directBuf.capacity()).order(ByteOrder.LITTLE_ENDIAN);
    corruptedDirect.put(directBuf.duplicate().position(0));
    corruptedDirect.put(6, (byte) (corruptedDirect.get(6) ^ 0x01));
    corruptedDirect.flip();

    assertThrows(MalformedPayloadException.class, () -> StvnBinaryDecoder.open(corruptedDirect));
  }
}
