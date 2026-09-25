package org.stvnadore.core.binary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.binary.readers.StvnMapReader;
import org.stvnadore.core.binary.readers.StvnSeqReader;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.ir.StvnValue.StvnFloat;
import org.stvnadore.core.ir.StvnValue.StvnInteger;
import org.stvnadore.core.validation.MalformedPayloadException;
import org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security, integrity, and isomorphism verification suite for the STVN Binary Wire Codec.
 * <p>
 * Evaluates defenses against:
 * <ol>
 *   <li>Preemptive heap exhaustion (allocation bombs / OOM attacks).</li>
 *   <li>Silent truncation of unbounded bare integers (> 31 bits).</li>
 *   <li>IEEE-754 special floating-point representation (NaN, +/-Infinity, -0.0) without BigDecimal crashes.</li>
 *   <li>Zero-trust Strategy 0x7 Bit 7 CRC-32C trailer enforcement and tampering defense.</li>
 *   <li>Pointer table hijacking (backward pointer into headers or out-of-bounds pointer).</li>
 *   <li>Magic preamble network byte order validation ('STVN' vs Little-Endian 'NVTS').</li>
 * </ol>
 *
 * @since 2.0.0
 */
public class StvnBinaryCodecSecurityTest {

  // =========================================================================
  // 1. PREEMPTIVE ALLOCATION BOMB DEFENSE (OOM PREVENTION)
  // =========================================================================

  @Nested
  @DisplayName("1. Preemptive Allocation Bomb Defense")
  class AllocationBombDefenseTests {

    @Test
    @DisplayName("TC-SEC-BOMB-01: Direct validateAllocationBounds rejects excessive length prefix")
    void testValidateAllocationBoundsExcessiveLength() {
      ByteBuffer buf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
      buf.limit(20);

      MalformedPayloadException ex = assertThrows(
          MalformedPayloadException.class,
          () -> StvnBinaryDecoder.validateAllocationBounds(100_000_000, buf, 5)
      );
      assertTrue(ex.getMessage().contains("Payload length bomb detected"),
          "Expected 'Payload length bomb detected' in: " + ex.getMessage());
      assertTrue(ex.getMessage().contains("claimed 100000000 bytes, but only 15 bytes remain"));
    }

    @Test
    @DisplayName("TC-SEC-BOMB-02: Direct validateAllocationBounds rejects near Integer.MAX_VALUE length")
    void testValidateAllocationBoundsNearMaxInt() {
      ByteBuffer buf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
      buf.limit(32);

      MalformedPayloadException ex = assertThrows(
          MalformedPayloadException.class,
          () -> StvnBinaryDecoder.validateAllocationBounds(Integer.MAX_VALUE - 100, buf, 10)
      );
      assertTrue(ex.getMessage().contains("Payload length bomb detected"));
    }

    @Test
    @DisplayName("TC-SEC-BOMB-03: Direct validateAllocationBounds rejects negative length prefix")
    void testValidateAllocationBoundsNegativeLength() {
      ByteBuffer buf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);

      MalformedPayloadException ex = assertThrows(
          MalformedPayloadException.class,
          () -> StvnBinaryDecoder.validateAllocationBounds(-1, buf, 0)
      );
      assertTrue(ex.getMessage().contains("Negative payload length prefix: -1"));
    }

    @Test
    @DisplayName("TC-SEC-BOMB-04: Outlined string length bomb in 20-byte wire buffer is trapped early")
    void testOutlinedStringAllocationBombTrapped() {
      // Create a 20-byte buffer claiming a string payload of 100,000,000 bytes
      ByteBuffer buf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
      // Magic 'STVN'
      buf.put((byte) 'S').put((byte) 'T').put((byte) 'V').put((byte) 'N');
      // Control byte: Strategy 0 (UniversalDefault), Post-Order, no CRC trailer
      buf.put((byte) 0x00);
      // Flags: offsetSize = 1 byte
      buf.put((byte) 0x00);
      // Root pointer pointing to offset 7
      buf.put((byte) 7);
      // At offset 7: derived length prefix claiming 100,000,000 bytes (0xC0 followed by int)
      buf.put((byte) 0xC0);
      buf.putInt(100_000_000);
      buf.flip();

      var schema = StvnCompiler.compile("{ :type :String :body \"\" }").orElseThrow().schema();
      var root = StvnBinaryDecoder.open(buf);

      MalformedPayloadException ex = assertThrows(
          MalformedPayloadException.class,
          () -> StvnBinaryDecoder.unpack(root, Optional.of(schema))
      );
      assertTrue(ex.getMessage().contains("Payload length bomb detected"),
          "Expected 'Payload length bomb detected' in: " + ex.getMessage());
    }

    @Test
    @DisplayName("TC-SEC-BOMB-05: Outlined sequence allocation bomb trapped by StvnSeqReader")
    void testOutlinedSequenceAllocationBombTrapped() {
      ByteBuffer buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
      buf.put((byte) 'S').put((byte) 'T').put((byte) 'V').put((byte) 'N');
      buf.put((byte) 0x00);
      buf.put((byte) 0x00);
      buf.put((byte) 7);
      // Derived length prefix claiming 50,000,000 elements
      buf.put((byte) 0xC0);
      buf.putInt(50_000_000);
      buf.flip();

      var schema = StvnCompiler.compile("{ :type :Seq(:Int) :body [ 0 ] }").orElseThrow().schema();
      var root = StvnBinaryDecoder.open(buf);

      MalformedPayloadException ex = assertThrows(
          MalformedPayloadException.class,
          () -> StvnBinaryDecoder.unpack(root, Optional.of(schema))
      );
      assertTrue(ex.getMessage().contains("Payload length bomb detected"));
    }

    @Test
    @DisplayName("TC-SEC-BOMB-06: Outlined map allocation bomb trapped by StvnMapReader")
    void testOutlinedMapAllocationBombTrapped() {
      ByteBuffer buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
      buf.put((byte) 'S').put((byte) 'T').put((byte) 'V').put((byte) 'N');
      buf.put((byte) 0x00);
      buf.put((byte) 0x00);
      buf.put((byte) 7);
      // Derived length prefix claiming 20,000,000 entries
      buf.put((byte) 0xC0);
      buf.putInt(20_000_000);
      buf.flip();

      var schema = StvnCompiler.compile("{ :type :Map(:String :Int) :body { [ \"k\" 1 ] } }").orElseThrow().schema();
      var root = StvnBinaryDecoder.open(buf);

      MalformedPayloadException ex = assertThrows(
          MalformedPayloadException.class,
          () -> StvnBinaryDecoder.unpack(root, Optional.of(schema))
      );
      assertTrue(ex.getMessage().contains("Payload length bomb detected"));
    }
  }

  // =========================================================================
  // 2. LOSSLESS BARE :Int BIGINTEGER ROUND-TRIP (NO SILENT TRUNCATION)
  // =========================================================================

  @Nested
  @DisplayName("2. Lossless Bare :Int BigInteger Round-Trip")
  class BareIntRoundTripTests {

    @Test
    @DisplayName("TC-SEC-INT-01: 100-digit positive BigInteger under bare :Int round-trips without truncation")
    void test100DigitPositiveBareIntRoundTrip() {
      String hundredDigits = "12345678901234567890123456789012345678901234567890" +
                             "98765432109876543210987654321098765432109876543210";
      BigInteger bigInt = new BigInteger(hundredDigits);

      ResolvedSchema bareIntSchema = StvnCompiler.compile("{ :type :Int :body 0 }").orElseThrow().schema();
      StvnInteger original = new StvnInteger(bareIntSchema, bigInt, 0, false);

      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
      ByteBuffer encoded = encoder.encode(original);

      var root = StvnBinaryDecoder.openStrict(encoded, new SchemaIdentityStrategy.UniversalDefault());
      StvnValue decoded = StvnBinaryDecoder.unpack(root, Optional.of(bareIntSchema));

      assertTrue(decoded instanceof StvnInteger, "Decoded value must be StvnInteger");
      StvnInteger decodedInt = (StvnInteger) decoded;

      assertEquals(bigInt, decodedInt.value(), "Decoded 100-digit integer must match original value exactly");
      assertEquals(0, decodedInt.bitWidth(), "Decoded bitWidth must be 0 for unbounded bare :Int");
      assertFalse(decodedInt.isUnsigned(), "Decoded integer must remain signed");
      assertEquals(original, decoded, "StvnInteger equals() must return true for round-tripped 100-digit value");
    }

    @Test
    @DisplayName("TC-SEC-INT-02: 100-digit negative BigInteger under bare :Int round-trips without truncation")
    void test100DigitNegativeBareIntRoundTrip() {
      String hundredDigits = "-9999888877776666555544443333222211110000" +
                             "1234567890123456789012345678901234567890" +
                             "55554444333322221111";
      BigInteger bigInt = new BigInteger(hundredDigits);

      ResolvedSchema bareIntSchema = StvnCompiler.compile("{ :type :Int :body 0 }").orElseThrow().schema();
      StvnInteger original = new StvnInteger(bareIntSchema, bigInt, 0, false);

      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
      ByteBuffer encoded = encoder.encode(original);

      var root = StvnBinaryDecoder.openStrict(encoded, new SchemaIdentityStrategy.UniversalDefault());
      StvnValue decoded = StvnBinaryDecoder.unpack(root, Optional.of(bareIntSchema));

      assertEquals(original, decoded);
      assertEquals(bigInt, ((StvnInteger) decoded).value());
    }

    @Test
    @DisplayName("TC-SEC-INT-03: Bare :Int with value <= 31 bits round-trips inline (4 bytes)")
    void testSmallBareIntRoundTripsInline() {
      BigInteger smallVal = BigInteger.valueOf(42);

      ResolvedSchema bareIntSchema = StvnCompiler.compile("{ :type :Int :body 0 }").orElseThrow().schema();
      StvnInteger original = new StvnInteger(bareIntSchema, smallVal, 32, false);

      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
      ByteBuffer encoded = encoder.encode(original);

      var root = StvnBinaryDecoder.openStrict(encoded, new SchemaIdentityStrategy.UniversalDefault());
      StvnValue decoded = StvnBinaryDecoder.unpack(root, Optional.of(bareIntSchema));

      assertEquals(original, decoded);
      assertEquals(smallVal, ((StvnInteger) decoded).value());
    }
  }

  // =========================================================================
  // 3. IEEE-754 SPECIAL FLOAT REPRESENTATION (NO BIGDECIMAL CRASHES)
  // =========================================================================

  @Nested
  @DisplayName("3. IEEE-754 Special Float Representation")
  class SpecialFloatRepresentationTests {

    @Test
    @DisplayName("TC-SEC-FLOAT-01: Float.NaN round-trips without BigDecimal crash")
    void testFloat32NaNRoundTrip() {
      ResolvedSchema schema = StvnCompiler.compile("""
          {
            :defs { :F32 { #size 32 } :Float }
            :type :F32
            :body 0.0
          }
          """).orElseThrow().schema();
      StvnFloat original = StvnFloat.ofFloat(schema, Float.NaN);

      assertTrue(original.isNaN());
      assertFalse(original.isPositiveInfinity());
      assertFalse(original.isNegativeInfinity());
      assertFalse(original.isNegativeZero());

      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
      ByteBuffer encoded = encoder.encode(original);

      var root = StvnBinaryDecoder.openStrict(encoded, new SchemaIdentityStrategy.UniversalDefault());
      StvnValue decoded = StvnBinaryDecoder.unpack(root, Optional.of(schema));

      assertTrue(decoded instanceof StvnFloat);
      StvnFloat decodedFloat = (StvnFloat) decoded;
      assertTrue(decodedFloat.isNaN());
      assertTrue(Float.isNaN(decodedFloat.floatValue()));
      assertEquals(original, decodedFloat);
    }

    @Test
    @DisplayName("TC-SEC-FLOAT-02: Double.NaN round-trips without BigDecimal crash")
    void testFloat64NaNRoundTrip() {
      ResolvedSchema schema = StvnCompiler.compile("""
          {
            :defs { :F64 { #size 64 } :Float }
            :type :F64
            :body 0.0
          }
          """).orElseThrow().schema();
      StvnFloat original = StvnFloat.ofDouble(schema, Double.NaN);

      assertTrue(original.isNaN());

      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
      ByteBuffer encoded = encoder.encode(original);

      var root = StvnBinaryDecoder.openStrict(encoded, new SchemaIdentityStrategy.UniversalDefault());
      StvnValue decoded = StvnBinaryDecoder.unpack(root, Optional.of(schema));

      assertTrue(decoded instanceof StvnFloat);
      StvnFloat decodedFloat = (StvnFloat) decoded;
      assertTrue(decodedFloat.isNaN());
      assertTrue(Double.isNaN(decodedFloat.doubleValue()));
      assertEquals(original, decodedFloat);
    }

    @Test
    @DisplayName("TC-SEC-FLOAT-03: Float32 +Infinity and -Infinity round-trip accurately")
    void testFloat32InfinitiesRoundTrip() {
      ResolvedSchema schema = StvnCompiler.compile("""
          {
            :defs { :F32 { #size 32 } :Float }
            :type :F32
            :body 0.0
          }
          """).orElseThrow().schema();
      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());

      // +Infinity
      StvnFloat posInf = StvnFloat.ofFloat(schema, Float.POSITIVE_INFINITY);
      assertTrue(posInf.isPositiveInfinity());
      ByteBuffer posBuf = encoder.encode(posInf);
      var posRoot = StvnBinaryDecoder.openStrict(posBuf, new SchemaIdentityStrategy.UniversalDefault());
      StvnFloat decodedPos = (StvnFloat) StvnBinaryDecoder.unpack(posRoot, Optional.of(schema));
      assertTrue(decodedPos.isPositiveInfinity());
      assertEquals(Float.POSITIVE_INFINITY, decodedPos.floatValue());
      assertEquals(posInf, decodedPos);

      // -Infinity
      StvnFloat negInf = StvnFloat.ofFloat(schema, Float.NEGATIVE_INFINITY);
      assertTrue(negInf.isNegativeInfinity());
      ByteBuffer negBuf = encoder.encode(negInf);
      var negRoot = StvnBinaryDecoder.openStrict(negBuf, new SchemaIdentityStrategy.UniversalDefault());
      StvnFloat decodedNeg = (StvnFloat) StvnBinaryDecoder.unpack(negRoot, Optional.of(schema));
      assertTrue(decodedNeg.isNegativeInfinity());
      assertEquals(Float.NEGATIVE_INFINITY, decodedNeg.floatValue());
      assertEquals(negInf, decodedNeg);
    }

    @Test
    @DisplayName("TC-SEC-FLOAT-04: Float64 +Infinity and -Infinity round-trip accurately")
    void testFloat64InfinitiesRoundTrip() {
      ResolvedSchema schema = StvnCompiler.compile("""
          {
            :defs { :F64 { #size 64 } :Float }
            :type :F64
            :body 0.0
          }
          """).orElseThrow().schema();
      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());

      // +Infinity
      StvnFloat posInf = StvnFloat.ofDouble(schema, Double.POSITIVE_INFINITY);
      assertTrue(posInf.isPositiveInfinity());
      ByteBuffer posBuf = encoder.encode(posInf);
      var posRoot = StvnBinaryDecoder.openStrict(posBuf, new SchemaIdentityStrategy.UniversalDefault());
      StvnFloat decodedPos = (StvnFloat) StvnBinaryDecoder.unpack(posRoot, Optional.of(schema));
      assertTrue(decodedPos.isPositiveInfinity());
      assertEquals(Double.POSITIVE_INFINITY, decodedPos.doubleValue());
      assertEquals(posInf, decodedPos);

      // -Infinity
      StvnFloat negInf = StvnFloat.ofDouble(schema, Double.NEGATIVE_INFINITY);
      assertTrue(negInf.isNegativeInfinity());
      ByteBuffer negBuf = encoder.encode(negInf);
      var negRoot = StvnBinaryDecoder.openStrict(negBuf, new SchemaIdentityStrategy.UniversalDefault());
      StvnFloat decodedNeg = (StvnFloat) StvnBinaryDecoder.unpack(negRoot, Optional.of(schema));
      assertTrue(decodedNeg.isNegativeInfinity());
      assertEquals(Double.NEGATIVE_INFINITY, decodedNeg.doubleValue());
      assertEquals(negInf, decodedNeg);
    }

    @Test
    @DisplayName("TC-SEC-FLOAT-05: Negative zero (-0.0f and -0.0d) preserves sign bit across wire")
    void testNegativeZeroPreservesSignBit() {
      ResolvedSchema schema32 = StvnCompiler.compile("""
          {
            :defs { :F32 { #size 32 } :Float }
            :type :F32
            :body 0.0
          }
          """).orElseThrow().schema();
      ResolvedSchema schema64 = StvnCompiler.compile("""
          {
            :defs { :F64 { #size 64 } :Float }
            :type :F64
            :body 0.0
          }
          """).orElseThrow().schema();
      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());

      // Float32 -0.0f
      StvnFloat negZero32 = StvnFloat.ofFloat(schema32, -0.0f);
      assertTrue(negZero32.isNegativeZero());
      ByteBuffer buf32 = encoder.encode(negZero32);
      var root32 = StvnBinaryDecoder.openStrict(buf32, new SchemaIdentityStrategy.UniversalDefault());
      StvnFloat decoded32 = (StvnFloat) StvnBinaryDecoder.unpack(root32, Optional.of(schema32));
      assertTrue(decoded32.isNegativeZero());
      assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits(decoded32.floatValue()));
      assertEquals(negZero32, decoded32);

      // Float64 -0.0d
      StvnFloat negZero64 = StvnFloat.ofDouble(schema64, -0.0d);
      assertTrue(negZero64.isNegativeZero());
      ByteBuffer buf64 = encoder.encode(negZero64);
      var root64 = StvnBinaryDecoder.openStrict(buf64, new SchemaIdentityStrategy.UniversalDefault());
      StvnFloat decoded64 = (StvnFloat) StvnBinaryDecoder.unpack(root64, Optional.of(schema64));
      assertTrue(decoded64.isNegativeZero());
      assertEquals(Double.doubleToRawLongBits(-0.0d), Double.doubleToRawLongBits(decoded64.doubleValue()));
      assertEquals(negZero64, decoded64);
    }
  }

  // =========================================================================
  // 4. MANDATORY STRATEGY 0x7 BIT 7 CRC-32C TRAILER ENFORCEMENT
  // =========================================================================

  @Nested
  @DisplayName("4. Mandatory Strategy 0x7 CRC-32C Trailer Enforcement")
  class Strategy0x7TrailerEnforcementTests {

    @Test
    @DisplayName("TC-SEC-STRAT7-01: ExplicitSha256 encodes with Bit 7 set (control byte 0x87)")
    void testStrategy0x7EncodesWithBit7Set() {
      var ir = StvnCompiler.compile("{ :type :Int :body 42 }").orElseThrow();
      byte[] hash = StvnSchemaHasher.computeSha256(ir.schema());

      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.ExplicitSha256(hash));
      ByteBuffer encoded = encoder.encode(ir);

      byte controlByte = encoded.get(4);
      assertEquals((byte) 0x87, controlByte, "Strategy 0x7 must mandate Bit 7 CRC-32C trailer flag (0x87)");

      // Buffer must decode successfully under strict and general open
      var strictRoot = StvnBinaryDecoder.openStrict(encoded.duplicate(), new SchemaIdentityStrategy.ExplicitSha256(hash));
      assertNotNull(strictRoot);

      var generalRoot = StvnBinaryDecoder.open(encoded.duplicate());
      assertNotNull(generalRoot);
    }

    @Test
    @DisplayName("TC-SEC-STRAT7-02: Clearing Bit 7 (0x87 -> 0x07) fails closed with MalformedPayloadException")
    void testBit7TamperFailsClosed() {
      var ir = StvnCompiler.compile("{ :type :Int :body 42 }").orElseThrow();
      byte[] hash = StvnSchemaHasher.computeSha256(ir.schema());

      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.ExplicitSha256(hash));
      ByteBuffer encoded = encoder.encode(ir);

      // Strip CRC-32C trailer and flip Bit 7 from 1 to 0 (control byte 0x87 -> 0x07)
      ByteBuffer tampered = ByteBuffer.allocate(encoded.limit() - 4).order(ByteOrder.LITTLE_ENDIAN);
      tampered.put(encoded.slice(0, encoded.limit() - 4));
      tampered.put(4, (byte) 0x07); // Clear Bit 7
      tampered.flip();

      // Verify openStrict fails closed
      MalformedPayloadException exStrict = assertThrows(
          MalformedPayloadException.class,
          () -> StvnBinaryDecoder.openStrict(tampered.duplicate(), new SchemaIdentityStrategy.ExplicitSha256(hash))
      );
      assertTrue(exStrict.getMessage().contains("Zero-Trust Policy Violation: Strategy 0x7 envelope requires mandatory Bit 7 CRC-32C trailer"));

      // Verify general open fails closed
      MalformedPayloadException exGeneral = assertThrows(
          MalformedPayloadException.class,
          () -> StvnBinaryDecoder.open(tampered.duplicate())
      );
      assertTrue(exGeneral.getMessage().contains("Zero-Trust Policy Violation: Strategy 0x7 envelope requires mandatory Bit 7 CRC-32C trailer"));
    }
  }

  // =========================================================================
  // 5. POINTER TABLE DEREFERENCE SANITIZATION (HIJACKING DEFENSE)
  // =========================================================================

  @Nested
  @DisplayName("5. Pointer Table Dereference Sanitization")
  class PointerTableSanitizationTests {

    @Test
    @DisplayName("TC-SEC-PTR-01: Root pointer pointing backward into header (< payloadStart) is rejected")
    void testBackwardPointerIntoHeaderRejected() {
      // 1-byte offset size, payloadStart = 5 + 1 (flags) + 1 (root pointer) = 7
      ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
      buf.put((byte) 'S').put((byte) 'T').put((byte) 'V').put((byte) 'N');
      buf.put((byte) 0x00); // controlByte
      buf.put((byte) 0x00); // flags: offsetSize = 1 byte
      buf.put((byte) 3);    // Root pointer points to offset 3 (inside magic bytes!)
      for (int i = 7; i < 16; i++) buf.put((byte) 0x00);
      buf.flip();

      MalformedPayloadException ex = assertThrows(
          MalformedPayloadException.class,
          () -> StvnBinaryDecoder.open(buf)
      );
      assertTrue(ex.getMessage().contains("Pointer table hijacking detected"),
          "Expected pointer hijacking exception, got: " + ex.getMessage());
      assertTrue(ex.getMessage().contains("offset 3 outside valid payload range [7, 16)"));
    }

    @Test
    @DisplayName("TC-SEC-PTR-02: Root pointer pointing out of buffer bounds (>= limit) is rejected")
    void testForwardPointerOutOfBoundsRejected() {
      ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
      buf.put((byte) 'S').put((byte) 'T').put((byte) 'V').put((byte) 'N');
      buf.put((byte) 0x00);
      buf.put((byte) 0x00);
      buf.put((byte) 50); // Root pointer points to offset 50 (limit is 16)
      for (int i = 7; i < 16; i++) buf.put((byte) 0x00);
      buf.flip();

      MalformedPayloadException ex = assertThrows(
          MalformedPayloadException.class,
          () -> StvnBinaryDecoder.open(buf)
      );
      assertTrue(ex.getMessage().contains("Pointer table hijacking detected"));
      assertTrue(ex.getMessage().contains("offset 50 outside valid payload range [7, 16)"));
    }

    @Test
    @DisplayName("TC-SEC-PTR-03: Child slot pointer in tuple pointing backward into headers is rejected")
    void testChildPointerTableHijackingRejected() {
      var tupleIr = StvnCompiler.compile("""
          {
            :type :Tuple( :String :String )
            :body ( "first" "second" )
          }
          """).orElseThrow();

      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
      ByteBuffer encoded = encoder.encode(tupleIr);

      // Find the tuple table containing slot pointers and tamper one pointer to point backward to offset 2
      ByteBuffer tampered = encoded.duplicate().order(ByteOrder.LITTLE_ENDIAN);
      var root = StvnBinaryDecoder.open(tampered);

      // Mutate the slot pointer to offset 2 (inside magic preamble)
      tampered.put(root.rootOffset(), (byte) 2);

      MalformedPayloadException ex = assertThrows(
          MalformedPayloadException.class,
          () -> StvnBinaryDecoder.unpack(root, Optional.of(tupleIr.schema()))
      );
      assertTrue(ex.getMessage().contains("Pointer table hijacking detected"));
    }
  }

  // =========================================================================
  // 6. MAGIC PREAMBLE NETWORK BYTE ORDER REALIGNMENT
  // =========================================================================

  @Nested
  @DisplayName("6. Magic Preamble Network Byte Order Realignment")
  class MagicPreambleRealignmentTests {

    @Test
    @DisplayName("TC-SEC-MAGIC-01: Literal ASCII preamble 'STVN' [0x53, 0x54, 0x56, 0x4E] is valid")
    void testLiteralAsciiMagicPreambleValid() {
      ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
      buf.put((byte) 'S');
      buf.put((byte) 'T');
      buf.put((byte) 'V');
      buf.put((byte) 'N');
      buf.put((byte) 0x00); // controlByte
      buf.put((byte) 0x00); // flags
      buf.put((byte) 7);    // root pointer
      buf.put((byte) 0);    // payload
      buf.flip();

      var root = StvnBinaryDecoder.open(buf);
      assertNotNull(root);
      assertEquals(7, root.rootOffset());
    }

    @Test
    @DisplayName("TC-SEC-MAGIC-02: Little-Endian integer preamble 'NVTS' [0x4E, 0x56, 0x54, 0x53] fails closed")
    void testLittleEndianInvertedMagicPreambleFailsClosed() {
      ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
      // Inverted little-endian putInt(0x5354564E)
      buf.put((byte) 'N');
      buf.put((byte) 'V');
      buf.put((byte) 'T');
      buf.put((byte) 'S');
      buf.put((byte) 0x00);
      buf.put((byte) 0x00);
      buf.put((byte) 7);
      buf.put((byte) 0);
      buf.flip();

      IllegalArgumentException ex = assertThrows(
          IllegalArgumentException.class,
          () -> StvnBinaryDecoder.open(buf)
      );
      assertEquals("Invalid STVN binary: Magic preamble mismatch (expected 'STVN')", ex.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    @DisplayName("TC-SEC-MAGIC-03: Buffer smaller than 5 bytes fails closed")
    void testBufferSmallerThanHeaderSize(int capacity) {
      ByteBuffer buf = ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
      for (int i = 0; i < capacity; i++) {
        buf.put((byte) 'S');
      }
      buf.flip();

      IllegalArgumentException ex = assertThrows(
          IllegalArgumentException.class,
          () -> StvnBinaryDecoder.open(buf)
      );
      assertTrue(ex.getMessage().contains("Buffer too small for STVN binary header"));
    }
  }
}
