package org.stvnadore.core.binary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.binary.readers.StvnTupleReader;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.ir.StvnValue.Ieee754SpecialKind;
import org.stvnadore.core.ir.StvnValue.StvnFloat;
import org.stvnadore.core.ir.StvnValue.StvnInt;
import org.stvnadore.core.ir.StvnValue.StvnTimeEpoch;
import org.stvnadore.core.validation.MalformedPayloadException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security, integrity, and isomorphism verification suite for the pure-IR STVN Binary Wire Codec.
 * <p>
 * Evaluates defenses against:
 * <ol>
 *   <li>Preemptive heap exhaustion (allocation bombs / OOM attacks).</li>
 *   <li>Lossless arbitrary bit-width {@code :Int} round-trips (Appendix A.3 &amp; A.4) and 32-bit inline {@code :Int}.</li>
 *   <li>IEEE-754 special floating-point representation (NaN, +/-Infinity, -0.0) without BigDecimal crashes.</li>
 *   <li>Zero-trust Strategy 0x7 Bit 7 CRC-32C trailer enforcement and tampering defense.</li>
 *   <li>Pointer table hijacking (backward pointer into headers or out-of-bounds pointer).</li>
 *   <li>Magic preamble network byte order validation ('STVN' vs Little-Endian 'NVTS').</li>
 *   <li>128-bit {@code #ns :TimeEpoch}, negative pre-1970 {@code :TimeEpoch}, and {@code .stvn_bd} schema framing.</li>
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
      ByteBuffer buf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
      buf.put((byte) 'S').put((byte) 'T').put((byte) 'V').put((byte) 'N');
      buf.put((byte) 0x00); // Byte 4: Strategy 0, Post-Order, no CRC trailer
      buf.put((byte) 0x00); // Byte 5: #BODY
      buf.put((byte) 0x00); // Byte 6: offsetSize = 1 byte
      buf.put((byte) 8);    // Byte 7: root pointer -> offset 8
      buf.put((byte) 0xC0); // Byte 8+: derived length prefix claiming 100,000,000 bytes
      buf.putInt(100_000_000);
      buf.flip();

      var schema = StvnCompiler.compilePayload("{ :type :String :body \"\" }").orElseThrow().schema();
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
      buf.put((byte) 0x00);
      buf.put((byte) 8);
      buf.put((byte) 0xC0);
      buf.putInt(50_000_000);
      buf.flip();

      var schema = StvnCompiler.compilePayload("{ :type :Seq(:Int) :body [ 0 ] }").orElseThrow().schema();
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
      buf.put((byte) 0x00);
      buf.put((byte) 8);
      buf.put((byte) 0xC0);
      buf.putInt(20_000_000);
      buf.flip();

      var schema = StvnCompiler.compilePayload("{ :type :Map(:String :Int) :body { [ \"k\" 1 ] } }").orElseThrow().schema();
      var root = StvnBinaryDecoder.open(buf);

      MalformedPayloadException ex = assertThrows(
          MalformedPayloadException.class,
          () -> StvnBinaryDecoder.unpack(root, Optional.of(schema))
      );
      assertTrue(ex.getMessage().contains("Payload length bomb detected"));
    }
  }

  // =========================================================================
  // 2. LOSSLESS ARBITRARY-WIDTH & 32-BIT :Int ROUND-TRIP (APPENDIX A.3 & A.4)
  // =========================================================================

  @Nested
  @DisplayName("2. Lossless Arbitrary-Width & Unadorned 32-Bit :Int Round-Trip")
  class ArbitraryAndBareIntRoundTripTests {

    @Test
    @DisplayName("TC-SEC-INT-01: 100-digit positive BigInteger under { #size 512 } :Int round-trips without truncation")
    void test100DigitPositiveArbitraryWidthIntRoundTrip() {
      String hundredDigits = "12345678901234567890123456789012345678901234567890" +
                             "98765432109876543210987654321098765432109876543210";
      BigInteger bigInt = new BigInteger(hundredDigits);

      StvnSchema wideIntSchema = StvnCompiler.compilePayload(
          "{ :defs { :Int512 { #size 512 } :Int } :type :Int512 :body 0 }"
      ).orElseThrow().schema();
      StvnInt original = new StvnInt(wideIntSchema, bigInt);

      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
      ByteBuffer encoded = encoder.encode(original);

      var root = StvnBinaryDecoder.openStrict(encoded, new SchemaIdentityStrategy.UniversalDefault());
      StvnValue decoded = StvnBinaryDecoder.unpack(root, Optional.of(wideIntSchema));

      assertTrue(decoded instanceof StvnInt, "Decoded value must be StvnInt");
      StvnInt decodedInt = (StvnInt) decoded;

      assertEquals(bigInt, decodedInt.value(), "Decoded 100-digit integer must match original value exactly");
      assertEquals(original, decoded, "StvnInt equals() must return true for round-tripped 100-digit value");
    }

    @Test
    @DisplayName("TC-SEC-INT-02: 100-digit negative BigInteger under { #size 512 } :Int round-trips with two's-complement sign extension")
    void test100DigitNegativeArbitraryWidthIntRoundTrip() {
      String hundredDigits = "-9999888877776666555544443333222211110000" +
                             "1234567890123456789012345678901234567890" +
                             "55554444333322221111";
      BigInteger bigInt = new BigInteger(hundredDigits);

      StvnSchema wideIntSchema = StvnCompiler.compilePayload(
          "{ :defs { :Int512 { #size 512 } :Int } :type :Int512 :body 0 }"
      ).orElseThrow().schema();
      StvnInt original = new StvnInt(wideIntSchema, bigInt);

      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
      ByteBuffer encoded = encoder.encode(original);

      var root = StvnBinaryDecoder.openStrict(encoded, new SchemaIdentityStrategy.UniversalDefault());
      StvnValue decoded = StvnBinaryDecoder.unpack(root, Optional.of(wideIntSchema));

      assertEquals(original, decoded);
      assertEquals(bigInt, ((StvnInt) decoded).value());
    }

    @Test
    @DisplayName("TC-SEC-INT-03: Unadorned 32-bit :Int round-trips inline (4 bytes) including negative signed values")
    void testUnadorned32BitIntRoundTripsInline() {
      StvnSchema bareIntSchema = StvnCompiler.compilePayload("{ :type :Int :body 0 }").orElseThrow().schema();
      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());

      for (BigInteger val : new BigInteger[]{
          BigInteger.valueOf(42),
          BigInteger.valueOf(Integer.MIN_VALUE),
          BigInteger.valueOf(Integer.MAX_VALUE)
      }) {
        StvnInt original = new StvnInt(bareIntSchema, val);
        ByteBuffer encoded = encoder.encode(original);
        var root = StvnBinaryDecoder.openStrict(encoded, new SchemaIdentityStrategy.UniversalDefault());
        StvnValue decoded = StvnBinaryDecoder.unpack(root, Optional.of(bareIntSchema));
        assertEquals(original, decoded);
        assertEquals(val, ((StvnInt) decoded).value());
      }
    }
  }

  // =========================================================================
  // 3. IEEE-754 SPECIAL FLOAT REPRESENTATION (NO BIGDECIMAL CRASHES)
  // =========================================================================

  @Nested
  @DisplayName("3. IEEE-754 Special Float Representation")
  class SpecialFloatRepresentationTests {

    @Test
    @DisplayName("TC-SEC-FLOAT-01: Float32 NaN round-trips without BigDecimal crash")
    void testFloat32NaNRoundTrip() {
      StvnSchema schema = StvnCompiler.compilePayload("""
          {
            :defs { :F32 { #size 32 } :Float }
            :type :F32
            :body 0.0
          }
          """).orElseThrow().schema();
      StvnFloat original = new StvnFloat(schema, BigDecimal.ZERO, false, Ieee754SpecialKind.NAN);

      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
      ByteBuffer encoded = encoder.encode(original);

      var root = StvnBinaryDecoder.openStrict(encoded, new SchemaIdentityStrategy.UniversalDefault());
      StvnValue decoded = StvnBinaryDecoder.unpack(root, Optional.of(schema));

      assertTrue(decoded instanceof StvnFloat);
      StvnFloat decodedFloat = (StvnFloat) decoded;
      assertEquals(Ieee754SpecialKind.NAN, decodedFloat.specialKind());
      assertEquals(original, decodedFloat);
    }

    @Test
    @DisplayName("TC-SEC-FLOAT-02: Float64 NaN round-trips without BigDecimal crash")
    void testFloat64NaNRoundTrip() {
      StvnSchema schema = StvnCompiler.compilePayload("""
          {
            :defs { :F64 :Float }
            :type :F64
            :body 0.0
          }
          """).orElseThrow().schema();
      StvnFloat original = new StvnFloat(schema, BigDecimal.ZERO, false, Ieee754SpecialKind.NAN);

      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
      ByteBuffer encoded = encoder.encode(original);

      var root = StvnBinaryDecoder.openStrict(encoded, new SchemaIdentityStrategy.UniversalDefault());
      StvnValue decoded = StvnBinaryDecoder.unpack(root, Optional.of(schema));

      assertTrue(decoded instanceof StvnFloat);
      StvnFloat decodedFloat = (StvnFloat) decoded;
      assertEquals(Ieee754SpecialKind.NAN, decodedFloat.specialKind());
      assertEquals(original, decodedFloat);
    }

    @Test
    @DisplayName("TC-SEC-FLOAT-03: Float32 +Infinity and -Infinity round-trip accurately")
    void testFloat32InfinitiesRoundTrip() {
      StvnSchema schema = StvnCompiler.compilePayload("""
          {
            :defs { :F32 { #size 32 } :Float }
            :type :F32
            :body 0.0
          }
          """).orElseThrow().schema();
      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());

      // +Infinity
      StvnFloat posInf = new StvnFloat(schema, BigDecimal.ZERO, false, Ieee754SpecialKind.POSITIVE_INFINITY);
      ByteBuffer posBuf = encoder.encode(posInf);
      var posRoot = StvnBinaryDecoder.openStrict(posBuf, new SchemaIdentityStrategy.UniversalDefault());
      StvnFloat decodedPos = (StvnFloat) StvnBinaryDecoder.unpack(posRoot, Optional.of(schema));
      assertEquals(Ieee754SpecialKind.POSITIVE_INFINITY, decodedPos.specialKind());
      assertEquals(posInf, decodedPos);

      // -Infinity
      StvnFloat negInf = new StvnFloat(schema, BigDecimal.ZERO, false, Ieee754SpecialKind.NEGATIVE_INFINITY);
      ByteBuffer negBuf = encoder.encode(negInf);
      var negRoot = StvnBinaryDecoder.openStrict(negBuf, new SchemaIdentityStrategy.UniversalDefault());
      StvnFloat decodedNeg = (StvnFloat) StvnBinaryDecoder.unpack(negRoot, Optional.of(schema));
      assertEquals(Ieee754SpecialKind.NEGATIVE_INFINITY, decodedNeg.specialKind());
      assertEquals(negInf, decodedNeg);
    }

    @Test
    @DisplayName("TC-SEC-FLOAT-04: Float64 +Infinity and -Infinity round-trip accurately")
    void testFloat64InfinitiesRoundTrip() {
      StvnSchema schema = StvnCompiler.compilePayload("""
          {
            :defs { :F64 :Float }
            :type :F64
            :body 0.0
          }
          """).orElseThrow().schema();
      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());

      // +Infinity
      StvnFloat posInf = new StvnFloat(schema, BigDecimal.ZERO, false, Ieee754SpecialKind.POSITIVE_INFINITY);
      ByteBuffer posBuf = encoder.encode(posInf);
      var posRoot = StvnBinaryDecoder.openStrict(posBuf, new SchemaIdentityStrategy.UniversalDefault());
      StvnFloat decodedPos = (StvnFloat) StvnBinaryDecoder.unpack(posRoot, Optional.of(schema));
      assertEquals(Ieee754SpecialKind.POSITIVE_INFINITY, decodedPos.specialKind());
      assertEquals(posInf, decodedPos);

      // -Infinity
      StvnFloat negInf = new StvnFloat(schema, BigDecimal.ZERO, false, Ieee754SpecialKind.NEGATIVE_INFINITY);
      ByteBuffer negBuf = encoder.encode(negInf);
      var negRoot = StvnBinaryDecoder.openStrict(negBuf, new SchemaIdentityStrategy.UniversalDefault());
      StvnFloat decodedNeg = (StvnFloat) StvnBinaryDecoder.unpack(negRoot, Optional.of(schema));
      assertEquals(Ieee754SpecialKind.NEGATIVE_INFINITY, decodedNeg.specialKind());
      assertEquals(negInf, decodedNeg);
    }

    @Test
    @DisplayName("TC-SEC-FLOAT-05: Negative zero (-0.0f and -0.0d) preserves sign bit across wire")
    void testNegativeZeroPreservesSignBit() {
      StvnSchema schema32 = StvnCompiler.compilePayload("""
          {
            :defs { :F32 { #size 32 } :Float }
            :type :F32
            :body 0.0
          }
          """).orElseThrow().schema();
      StvnSchema schema64 = StvnCompiler.compilePayload("""
          {
            :defs { :F64 :Float }
            :type :F64
            :body 0.0
          }
          """).orElseThrow().schema();
      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());

      // Float32 -0.0f
      StvnFloat negZero32 = new StvnFloat(schema32, new BigDecimal("0.0"), true, Ieee754SpecialKind.FINITE);
      ByteBuffer buf32 = encoder.encode(negZero32);
      var root32 = StvnBinaryDecoder.openStrict(buf32, new SchemaIdentityStrategy.UniversalDefault());
      StvnFloat decoded32 = (StvnFloat) StvnBinaryDecoder.unpack(root32, Optional.of(schema32));
      assertTrue(decoded32.negativeZero());
      assertEquals(negZero32, decoded32);

      // Float64 -0.0d
      StvnFloat negZero64 = new StvnFloat(schema64, new BigDecimal("0.0"), true, Ieee754SpecialKind.FINITE);
      ByteBuffer buf64 = encoder.encode(negZero64);
      var root64 = StvnBinaryDecoder.openStrict(buf64, new SchemaIdentityStrategy.UniversalDefault());
      StvnFloat decoded64 = (StvnFloat) StvnBinaryDecoder.unpack(root64, Optional.of(schema64));
      assertTrue(decoded64.negativeZero());
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
      var ir = StvnCompiler.compilePayload("{ :type :Int :body 42 }").orElseThrow();
      byte[] hash = StvnSchemaHasher.computeSha256(ir.schema());

      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.ExplicitSha256(hash));
      ByteBuffer encoded = encoder.encode(ir);

      byte controlByte = encoded.get(4);
      assertEquals((byte) 0x87, controlByte, "Strategy 0x7 must mandate Bit 7 CRC-32C trailer flag (0x87)");

      var strictRoot = StvnBinaryDecoder.openStrict(encoded.duplicate(), new SchemaIdentityStrategy.ExplicitSha256(hash));
      assertNotNull(strictRoot);

      var generalRoot = StvnBinaryDecoder.open(encoded.duplicate());
      assertNotNull(generalRoot);
    }

    @Test
    @DisplayName("TC-SEC-STRAT7-02: Clearing Bit 7 (0x87 -> 0x07) fails closed with MalformedPayloadException")
    void testBit7TamperFailsClosed() {
      var ir = StvnCompiler.compilePayload("{ :type :Int :body 42 }").orElseThrow();
      byte[] hash = StvnSchemaHasher.computeSha256(ir.schema());

      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.ExplicitSha256(hash));
      ByteBuffer encoded = encoder.encode(ir);

      ByteBuffer tampered = ByteBuffer.allocate(encoded.limit() - 4).order(ByteOrder.LITTLE_ENDIAN);
      tampered.put(encoded.slice(0, encoded.limit() - 4));
      tampered.put(4, (byte) 0x07);
      tampered.flip();

      MalformedPayloadException exStrict = assertThrows(
          MalformedPayloadException.class,
          () -> StvnBinaryDecoder.openStrict(tampered.duplicate(), new SchemaIdentityStrategy.ExplicitSha256(hash))
      );
      assertTrue(exStrict.getMessage().contains("Zero-Trust Policy Violation: Strategy 0x7 envelope requires mandatory Bit 7 CRC-32C trailer"));

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
      ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
      buf.put((byte) 'S').put((byte) 'T').put((byte) 'V').put((byte) 'N');
      buf.put((byte) 0x00);
      buf.put((byte) 0x00);
      buf.put((byte) 0x00);
      buf.put((byte) 3);
      for (int i = 8; i < 16; i++) {
        buf.put((byte) 0x00);
      }
      buf.flip();

      MalformedPayloadException ex = assertThrows(
          MalformedPayloadException.class,
          () -> StvnBinaryDecoder.open(buf)
      );
      assertTrue(ex.getMessage().contains("Pointer table hijacking detected"),
          "Expected pointer hijacking exception, got: " + ex.getMessage());
      assertTrue(ex.getMessage().contains("offset 3 outside valid payload range [8, 16)"));
    }

    @Test
    @DisplayName("TC-SEC-PTR-02: Root pointer pointing out of buffer bounds (>= limit) is rejected")
    void testForwardPointerOutOfBoundsRejected() {
      ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
      buf.put((byte) 'S').put((byte) 'T').put((byte) 'V').put((byte) 'N');
      buf.put((byte) 0x00);
      buf.put((byte) 0x00);
      buf.put((byte) 0x00);
      buf.put((byte) 50);
      for (int i = 8; i < 16; i++) {
        buf.put((byte) 0x00);
      }
      buf.flip();

      MalformedPayloadException ex = assertThrows(
          MalformedPayloadException.class,
          () -> StvnBinaryDecoder.open(buf)
      );
      assertTrue(ex.getMessage().contains("Pointer table hijacking detected"));
      assertTrue(ex.getMessage().contains("offset 50 outside valid payload range [8, 16)"));
    }

    @Test
    @DisplayName("TC-SEC-PTR-03: Child slot pointer in tuple pointing backward into headers is rejected")
    void testChildPointerTableHijackingRejected() {
      var tupleIr = StvnCompiler.compilePayload("""
          {
            :type :Tuple( :String :String )
            :body ( "first" "second" )
          }
          """).orElseThrow();

      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
      ByteBuffer encoded = encoder.encode(tupleIr);

      ByteBuffer tampered = encoded.duplicate().order(ByteOrder.LITTLE_ENDIAN);
      var root = StvnBinaryDecoder.open(tampered);

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
      ByteBuffer buf = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN);
      buf.put((byte) 'S');
      buf.put((byte) 'T');
      buf.put((byte) 'V');
      buf.put((byte) 'N');
      buf.put((byte) 0x00);
      buf.put((byte) 0x00);
      buf.put((byte) 0x00);
      buf.put((byte) 8);
      buf.put((byte) 0);
      buf.flip();

      var root = StvnBinaryDecoder.open(buf);
      assertNotNull(root);
      assertEquals(8, root.rootOffset());
    }

    @Test
    @DisplayName("TC-SEC-MAGIC-02: Little-Endian integer preamble 'NVTS' [0x4E, 0x56, 0x54, 0x53] fails closed")
    void testLittleEndianInvertedMagicPreambleFailsClosed() {
      ByteBuffer buf = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN);
      buf.put((byte) 'N');
      buf.put((byte) 'V');
      buf.put((byte) 'T');
      buf.put((byte) 'S');
      buf.put((byte) 0x00);
      buf.put((byte) 0x00);
      buf.put((byte) 0x00);
      buf.put((byte) 8);
      buf.put((byte) 0);
      buf.flip();

      IllegalArgumentException ex = assertThrows(
          IllegalArgumentException.class,
          () -> StvnBinaryDecoder.open(buf)
      );
      assertEquals("Invalid STVN binary: Magic preamble mismatch (expected 'STVN')", ex.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    @DisplayName("TC-SEC-MAGIC-03: Buffer smaller than 6 bytes fails closed")
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

  // =========================================================================
  // 7. STAGE 3B: 128-BIT #ns :TimeEpoch, PRE-1970 :TimeEpoch & .stvn_bd FRAMING
  // =========================================================================

  @Nested
  @DisplayName("7. Stage 3B Temporal 128-Bit / Negative :TimeEpoch and .stvn_bd Framing")
  class TemporalAndBinaryDefsFramingTests {

    @Test
    @DisplayName("TC-SEC-TIME-01: Signed 128-bit { #ns } :TimeEpoch preserves >64-bit positive and negative ticks losslessly")
    void test128BitNanosecondTimeEpochRoundTrip() {
      BigInteger posBeyond64 = new BigInteger("9223372036854775808000");
      BigInteger negBeyond64 = new BigInteger("-9223372036854775809000");

      String source = """
          {
            :defs {
              :NanoEpoch { #ns } :TimeEpoch
              :NanoPair :Tuple( :NanoEpoch :NanoEpoch )
            }
            :type :NanoPair
            :body ( %s %s )
          }
          """.formatted(posBeyond64, negBeyond64);

      StvnValue ir = StvnCompiler.compilePayload(source).orElseThrow();
      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault(), true);
      ByteBuffer buf = encoder.encode(ir);

      var root = StvnBinaryDecoder.open(buf);
      StvnValue decoded = StvnBinaryDecoder.unpack(root, Optional.of(ir.schema()));

      assertEquals(ir, decoded);
      StvnValue.StvnTuple pair = (StvnValue.StvnTuple) decoded;
      assertEquals(posBeyond64, ((StvnTimeEpoch) pair.elements().get(0)).epochTicks());
      assertEquals(negBeyond64, ((StvnTimeEpoch) pair.elements().get(1)).epochTicks());

      // Verify zero-copy StvnMemoryAccessor.getInt128 on StvnTupleReader
      var tupleReader = new StvnTupleReader(root.context(), root.rootOffset(), ir.schema());
      assertEquals(posBeyond64, tupleReader.getInt128(0));
      assertEquals(negBeyond64, tupleReader.getInt128(1));
    }

    @Test
    @DisplayName("TC-SEC-TIME-02: Signed 64-bit { #s | #ms | #us } and 128-bit { #ns } :TimeEpoch preserve negative pre-1970 timestamps")
    void testNegativePre1970TimeEpochRoundTrip() {
      String source = """
          {
            :defs {
              :SecEpoch { #s } :TimeEpoch
              :MsEpoch { #ms } :TimeEpoch
              :UsEpoch { #us } :TimeEpoch
              :NsEpoch { #ns } :TimeEpoch
              :HistoricalRecord :Tuple( :SecEpoch :MsEpoch :UsEpoch :NsEpoch )
            }
            :type :HistoricalRecord
            :body ( -86400 -9223372036854775808 -1 -999999999999999999999 )
          }
          """;

      StvnValue ir = StvnCompiler.compilePayload(source).orElseThrow();
      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault(), true);
      ByteBuffer buf = encoder.encode(ir);

      var root = StvnBinaryDecoder.open(buf);
      StvnValue decoded = StvnBinaryDecoder.unpack(root, Optional.of(ir.schema()));
      assertEquals(ir, decoded);

      StvnValue.StvnTuple tuple = (StvnValue.StvnTuple) decoded;
      assertEquals(BigInteger.valueOf(-86400L), ((StvnTimeEpoch) tuple.elements().get(0)).epochTicks());
      assertEquals(BigInteger.valueOf(Long.MIN_VALUE), ((StvnTimeEpoch) tuple.elements().get(1)).epochTicks());
      assertEquals(BigInteger.valueOf(-1L), ((StvnTimeEpoch) tuple.elements().get(2)).epochTicks());
      assertEquals(new BigInteger("-999999999999999999999"), ((StvnTimeEpoch) tuple.elements().get(3)).epochTicks());
    }

    @Test
    @DisplayName("TC-SEC-BD-01: Binary definitions schema (.stvn_bd, Byte 5 0b01) encodes and decodes losslessly")
    void testBinaryDefinitionsStvnBdFramingRoundTrip() {
      String defsSource = """
          {
            :meta {
              #name "telemetry_defs"
              #domain "org_stvnadore_binary"
              #kind #DEFS
            }
            :defs {
              :DeviceId { #unsigned #size 64 } :Int
              :NanoStamp { #ns } :TimeEpoch
              :Reading :Tuple( :DeviceId :NanoStamp )
            }
          }
          """;

      StvnDocument defsDoc = StvnCompiler.compile(defsSource, "telemetry_defs.stvn_d").document().orElseThrow();
      var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault(), true);
      ByteBuffer bdBuf = encoder.encode(defsDoc);

      // Verify Byte 5 carries #kind == #DEFS (0b01 in low 2 bits) and #name/#domain presence bits
      int metaByte = Byte.toUnsignedInt(bdBuf.get(5));
      assertEquals(0x01, metaByte & 0x03, "Byte 5 low 2 bits must encode #DEFS (0b01)");

      StvnDocument decodedDoc = StvnBinaryDecoder.decodeDocument(bdBuf.duplicate());
      assertTrue(decodedDoc.payload().isEmpty(), "Decoded .stvn_bd document must have empty payload");
      assertEquals(defsDoc.meta(), decodedDoc.meta());
      assertEquals(defsDoc.defs().nominalTypes().keySet(), decodedDoc.defs().nominalTypes().keySet());

      // Also verify StvnCompiler.compile(ByteBuffer, "telemetry_defs.stvn_bd", StvnParserConfig.DEFAULT) integration
      StvnCompilationResult<StvnDocument> compiledBd = StvnCompiler.compile(
          bdBuf.duplicate(), "telemetry_defs.stvn_bd", org.stvnadore.core.StvnParserConfig.DEFAULT
      );
      assertFalse(compiledBd.hasErrors(), () -> "Binary .stvn_bd compile errors: " + compiledBd.diagnostics());
      assertEquals(
          defsDoc.defs().nominalTypes().keySet(),
          compiledBd.document().orElseThrow().defs().nominalTypes().keySet()
      );
    }

    @Test
    @DisplayName("TC-SEC-BD-02: .stvn_bd with multiple inner semantic errors preserves full DiagnosticBag list and exact inner coordinates")
    void testBinaryDefinitionsPreservesMultiErrorDiagnosticBagAndInnerCoordinates() {
      String invalidInnerDefs = """
          {
            :meta {
              #name "broken_defs"
              #domain "crm"
              #kind #DEFS
            }
            :defs {
              :BadString { #size 16 } :String
              :BadRef :MissingType
            }
          }
          """;
      var headerMeta = new org.stvnadore.core.ir.StvnDocumentMeta(
          Optional.of("broken_defs"),
          Optional.of("crm"),
          Optional.of(org.stvnadore.core.ir.StvnDocumentKind.DEFS)
      );
      var encoder = new StvnBinaryEncoder(
          true,
          new SchemaIdentityStrategy.SelfDescribingSchema(invalidInnerDefs),
          true
      );
      ByteBuffer bdBuf = encoder.encodeDefinitionsText(invalidInnerDefs, headerMeta);
      int byteLength = bdBuf.remaining();

      StvnCompilationResult<StvnDocument> textExpected = StvnCompiler.compile(
          invalidInnerDefs,
          "broken_defs.crm.stvn_bd",
          org.stvnadore.core.StvnParserConfig.DEFAULT
      );
      StvnCompilationResult<StvnDocument> binResult = StvnCompiler.compile(
          bdBuf.duplicate(),
          "broken_defs.crm.stvn_bd",
          org.stvnadore.core.StvnParserConfig.DEFAULT
      );

      assertFalse(binResult.isSuccess());
      assertTrue(binResult.hasErrors());
      assertTrue(binResult.diagnostics().size() >= 2,
          "Expected >= 2 accumulated inner diagnostics rather than collapsed MALFORMED_PAYLOAD: " + binResult.diagnostics());
      assertEquals(textExpected.diagnostics().size(), binResult.diagnostics().size());

      for (int i = 0; i < textExpected.diagnostics().size(); i++) {
        var expectedDiag = textExpected.diagnostics().get(i);
        var actualDiag = binResult.diagnostics().get(i);
        assertEquals(expectedDiag.canonicalCode(), actualDiag.canonicalCode());
        assertEquals(expectedDiag.symbolicId(), actualDiag.symbolicId());
        assertEquals(expectedDiag.line(), actualDiag.line());
        assertEquals(expectedDiag.column(), actualDiag.column());
        assertEquals(expectedDiag.startOffset(), actualDiag.startOffset());
        assertEquals(expectedDiag.endOffset(), actualDiag.endOffset());
        assertFalse(actualDiag.startOffset() == 0 && actualDiag.endOffset() == byteLength,
            "Diagnostic span must never collapse to [0, byteLength)");
      }
    }

    @Test
    @DisplayName("TC-SEC-BD-03: .stvn_bd with W003 redundant facet warning succeeds while preserving W003 and exact inner coordinates")
    void testBinaryDefinitionsPreservesW003WarningsAndInnerCoordinates() {
      String tautologicalDefs = """
          {
            :meta {
              #name "taut_defs"
              #domain "crm"
              #kind #DEFS
            }
            :defs {
              :UnsignedPort { #unsigned #size 16 #minIncl 0 } :Int
            }
          }
          """;
      var headerMeta = new org.stvnadore.core.ir.StvnDocumentMeta(
          Optional.of("taut_defs"),
          Optional.of("crm"),
          Optional.of(org.stvnadore.core.ir.StvnDocumentKind.DEFS)
      );
      var encoder = new StvnBinaryEncoder(
          true,
          new SchemaIdentityStrategy.SelfDescribingSchema(tautologicalDefs),
          true
      );
      ByteBuffer bdBuf = encoder.encodeDefinitionsText(tautologicalDefs, headerMeta);

      StvnCompilationResult<StvnDocument> binResult = StvnCompiler.compile(
          bdBuf.duplicate(),
          "taut_defs.crm.stvn_bd",
          org.stvnadore.core.StvnParserConfig.DEFAULT
      );

      assertTrue(binResult.isSuccess(), () -> "Expected success with W003 warning: " + binResult.diagnostics());
      assertFalse(binResult.hasErrors());
      assertTrue(binResult.hasWarnings());
      var w003 = binResult.diagnostics().stream()
          .filter(d -> d.canonicalCode().filter("W003"::equals).isPresent())
          .findFirst()
          .orElseThrow(() -> new AssertionError("Expected W003 warning in .stvn_bd result: " + binResult.diagnostics()));
      assertEquals(org.stvnadore.core.validation.DiagnosticBag.WARN_REDUNDANT_METADATA_FACET, w003.symbolicId().orElse(null));
      int expectedFacetIdx = tautologicalDefs.indexOf("#minIncl 0");
      assertEquals(expectedFacetIdx, w003.startOffset());
      assertEquals(expectedFacetIdx + "#minIncl 0".length(), w003.endOffset());
      assertTrue(binResult.document().orElseThrow().hasDefsSection());
    }

    @Test
    @DisplayName("TC-SEC-BD-04: .stvn_bd rejects Byte 5 vs inner :meta spoofing with E031 pinned to inner :meta and preserves hasDefsSection")
    void testBinaryDefinitionsRejectsByte5VsInnerMetaSpoofingAndPreservesHasDefsSection() {
      // Attack 1: Outer Byte 5 declares ("legit_defs", "crm", DEFS), inner :meta declares ("spoofed_defs", "crm", DEFS)
      String spoofedNameDefs = """
          {
            :meta {
              #name "spoofed_defs"
              #domain "crm"
              #kind #DEFS
            }
            :defs {
              :UserId :Int
            }
          }
          """;
      var outerMeta = new org.stvnadore.core.ir.StvnDocumentMeta(
          Optional.of("legit_defs"),
          Optional.of("crm"),
          Optional.of(org.stvnadore.core.ir.StvnDocumentKind.DEFS)
      );
      ByteBuffer spoofedNameBuf = new StvnBinaryEncoder(
          true,
          new SchemaIdentityStrategy.SelfDescribingSchema(spoofedNameDefs),
          true
      ).encodeDefinitionsText(spoofedNameDefs, outerMeta);

      StvnCompilationResult<StvnDocument> spoofRes = StvnCompiler.compile(
          spoofedNameBuf.duplicate(),
          "legit_defs.crm.stvn_bd",
          org.stvnadore.core.StvnParserConfig.DEFAULT
      );
      assertFalse(spoofRes.isSuccess());
      assertTrue(spoofRes.hasErrors());
      // Ensure WT-03 fix suppresses spurious W001 warning from inner :meta ("spoofed_defs") vs outer filename ("legit_defs")
      assertEquals(1, spoofRes.diagnostics().size(),
          "Must emit single E031 contradiction without spurious inner filename W001 warning: " + spoofRes.diagnostics());
      var e031 = spoofRes.diagnostics().getFirst();
      assertEquals("E031", e031.canonicalCode().orElse(null));
      assertEquals(org.stvnadore.core.validation.DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH, e031.symbolicId().orElse(null));
      int expectedMetaStart = spoofedNameDefs.indexOf(":meta");
      assertEquals(expectedMetaStart, e031.startOffset(), "E031 must pin to inner :meta start");
      assertTrue(e031.message().contains("contradicts embedded ':meta' block"));
      assertThrows(MalformedPayloadException.class, () -> StvnBinaryDecoder.decodeDocument(spoofedNameBuf.duplicate()));

      // Attack 2: Outer Byte 5 declares ("body_spoof", "crm", DEFS), inner document is a #BODY unit lacking :defs (in-memory docPath = null)
      String spoofedBodyUnit = "{ :meta { #name \"body_spoof\" #domain \"crm\" #kind #BODY } :type :Int :body 7 }";
      var bodySpoofOuterMeta = new org.stvnadore.core.ir.StvnDocumentMeta(
          Optional.of("body_spoof"),
          Optional.of("crm"),
          Optional.of(org.stvnadore.core.ir.StvnDocumentKind.DEFS)
      );
      ByteBuffer spoofedBodyBuf = new StvnBinaryEncoder(
          true,
          new SchemaIdentityStrategy.SelfDescribingSchema(spoofedBodyUnit),
          true
      ).encodeDefinitionsText(spoofedBodyUnit, bodySpoofOuterMeta);

      StvnCompilationResult<StvnDocument> bodySpoofRes = StvnCompiler.compile(
          spoofedBodyBuf.duplicate(),
          null,
          org.stvnadore.core.StvnParserConfig.DEFAULT
      );
      assertFalse(bodySpoofRes.isSuccess());
      assertTrue(bodySpoofRes.hasErrors());
      assertEquals("E031", bodySpoofRes.diagnostics().getFirst().canonicalCode().orElse(null));
      // Decoded partial document must NOT hardcode hasDefsSection = true when inner unit had no :defs section
      assertFalse(bodySpoofRes.document().orElseThrow().hasDefsSection(),
          "Decoded .stvn_bd document must preserve inner hasDefsSection == false rather than hardcoding true");

      // Attack 3: Outer Byte 5 declares ("no_meta", "crm", DEFS), inner document omits :meta altogether (in-memory docPath = null)
      String omittedMetaUnit = "{ :defs { :OnlyType :Int } }";
      var omittedOuterMeta = new org.stvnadore.core.ir.StvnDocumentMeta(
          Optional.of("no_meta"),
          Optional.of("crm"),
          Optional.of(org.stvnadore.core.ir.StvnDocumentKind.DEFS)
      );
      ByteBuffer omittedMetaBuf = new StvnBinaryEncoder(
          true,
          new SchemaIdentityStrategy.SelfDescribingSchema(omittedMetaUnit),
          true
      ).encodeDefinitionsText(omittedMetaUnit, omittedOuterMeta);

      StvnCompilationResult<StvnDocument> omittedRes = StvnCompiler.compile(
          omittedMetaBuf.duplicate(),
          null,
          org.stvnadore.core.StvnParserConfig.DEFAULT
      );
      assertFalse(omittedRes.isSuccess());
      assertTrue(omittedRes.hasErrors());
      assertEquals("E031", omittedRes.diagnostics().getFirst().canonicalCode().orElse(null));
    }

    @Test
    @DisplayName("TC-SEC-BD-05: .stvn_bd missing inner :meta after multi-line leading comments pins E031 strictly to root '{' line and column (WT-01)")
    void testBinaryDefinitionsMissingMetaAfterLeadingCommentsPinsExactLineAndColumn() {
      String commentedNoMeta = "// header comment\n// second comment\n  {\n    :defs {\n      :T :Int\n    }\n  }\n";
      int expectedBraceOffset = commentedNoMeta.indexOf('{');
      var outerMeta = org.stvnadore.core.ir.StvnDocumentMeta.of(
          "commented_defs", "crm", org.stvnadore.core.ir.StvnDocumentKind.DEFS
      );
      ByteBuffer bdBuf = new StvnBinaryEncoder(
          true,
          new SchemaIdentityStrategy.UniversalDefault(),
          true
      ).encodeDefinitionsText(commentedNoMeta, outerMeta);

      StvnCompilationResult<StvnDocument> res = StvnCompiler.compile(
          bdBuf.duplicate(),
          null,
          org.stvnadore.core.StvnParserConfig.DEFAULT
      );
      assertFalse(res.isSuccess());
      assertTrue(res.hasErrors());
      assertEquals(1, res.diagnostics().size(), () -> "Unexpected diagnostics: " + res.diagnostics());
      var diag = res.diagnostics().getFirst();
      assertEquals("E031", diag.canonicalCode().orElse(null));
      assertEquals(org.stvnadore.core.validation.DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH, diag.symbolicId().orElse(null));
      assertEquals(3, diag.line(), "Root '{' after two comment lines must pin to line 3");
      assertEquals(2, diag.column(), "Root '{' after two leading spaces on line 3 must pin to 0-based column 2");
      assertEquals(expectedBraceOffset, diag.startOffset());
      assertEquals(expectedBraceOffset + 1, diag.endOffset());
    }

    @Test
    @DisplayName("TC-SEC-BD-06: .stvn_bd suppresses cascade E031 when inner :meta is empty (E039) or has invalid POSIX facet (E008) (WT-02)")
    void testBinaryDefinitionsSuppressesCascadeE031OnMalformedInnerMeta() {
      var outerMeta = org.stvnadore.core.ir.StvnDocumentMeta.of(
          "valid_name", "crm", org.stvnadore.core.ir.StvnDocumentKind.DEFS
      );

      // Case A: Empty inner :meta {} -> strictly E039 (ERR_EMPTY_META_BLOCK), zero cascade E031
      String emptyMetaDefs = "{\n  :meta {}\n  :defs {\n    :T :Int\n  }\n}\n";
      ByteBuffer emptyMetaBuf = new StvnBinaryEncoder(
          true,
          new SchemaIdentityStrategy.UniversalDefault(),
          true
      ).encodeDefinitionsText(emptyMetaDefs, outerMeta);

      StvnCompilationResult<StvnDocument> emptyRes = StvnCompiler.compile(
          emptyMetaBuf.duplicate(),
          "valid_name.crm.stvn_bd",
          org.stvnadore.core.StvnParserConfig.DEFAULT
      );
      assertTrue(emptyRes.hasErrors());
      assertEquals(1, emptyRes.diagnostics().size(),
          () -> "Empty inner :meta {} must emit strictly E039 without cascade E031: " + emptyRes.diagnostics());
      assertEquals("E039", emptyRes.diagnostics().getFirst().canonicalCode().orElse(null));
      assertEquals(org.stvnadore.core.validation.DiagnosticBag.ERR_EMPTY_META_BLOCK,
          emptyRes.diagnostics().getFirst().symbolicId().orElse(null));

      // Case B: Non-POSIX inner #name "bad name!" -> strictly E008 (ERR_INVALID_METADATA_FACET), zero cascade E031
      String invalidPosixMetaDefs = """
          {
            :meta {
              #name "bad name!"
              #domain "crm"
              #kind #DEFS
            }
            :defs {
              :T :Int
            }
          }
          """;
      ByteBuffer badFacetBuf = new StvnBinaryEncoder(
          true,
          new SchemaIdentityStrategy.UniversalDefault(),
          true
      ).encodeDefinitionsText(invalidPosixMetaDefs, outerMeta);

      StvnCompilationResult<StvnDocument> badFacetRes = StvnCompiler.compile(
          badFacetBuf.duplicate(),
          "valid_name.crm.stvn_bd",
          org.stvnadore.core.StvnParserConfig.DEFAULT
      );
      assertTrue(badFacetRes.hasErrors());
      assertEquals(1, badFacetRes.diagnostics().size(),
          () -> "Non-POSIX inner :meta facet must emit strictly E008 without cascade E031: " + badFacetRes.diagnostics());
      assertEquals("E008", badFacetRes.diagnostics().getFirst().canonicalCode().orElse(null));
      assertEquals(org.stvnadore.core.validation.DiagnosticBag.ERR_INVALID_METADATA_FACET,
          badFacetRes.diagnostics().getFirst().symbolicId().orElse(null));
    }

    @Test
    @DisplayName("TC-SEC-BD-07: StvnBinaryDecoder.decodeDocument rejects reserved 'stvn_*' domain under DEFAULT and permits under SYSTEM_DEFAULT (WT-04)")
    void testDecodeDocumentEnforcesReservedDomainSystemAuthorityOnBodyAndDefs() {
      // 1. #BODY binary frame declaring #domain "stvn_core"
      String inlineSchema = "{ :type :Int :body 0 }";
      StvnValue bodyVal = StvnCompiler.compilePayload("{ :type :Int :body 77 }").orElseThrow();
      var reservedBodyMeta = org.stvnadore.core.ir.StvnDocumentMeta.of(
          "sys_payload", "stvn_core", org.stvnadore.core.ir.StvnDocumentKind.BODY
      );
      ByteBuffer bodyBuf = new StvnBinaryEncoder(
          true,
          new SchemaIdentityStrategy.SelfDescribingSchema(inlineSchema),
          true
      ).encode(bodyVal, reservedBodyMeta);

      MalformedPayloadException bodyEx = assertThrows(
          MalformedPayloadException.class,
          () -> StvnBinaryDecoder.decodeDocument(bodyBuf.duplicate())
      );
      assertTrue(bodyEx.getMessage().contains("Reserved system domain prohibited"));

      StvnDocument sysBodyDoc = StvnBinaryDecoder.decodeDocument(
          bodyBuf.duplicate(), null, org.stvnadore.core.StvnParserConfig.SYSTEM_DEFAULT
      );
      assertEquals(Optional.of("stvn_core"), sysBodyDoc.requireMeta().domain());

      // 2. #DEFS (.stvn_bd) binary frame declaring #domain "stvn_core"
      String reservedDefsText = """
          {
            :meta {
              #name "sys_defs"
              #domain "stvn_core"
              #kind #DEFS
            }
            :defs {
              :SysId :Int
            }
          }
          """;
      var reservedDefsMeta = org.stvnadore.core.ir.StvnDocumentMeta.of(
          "sys_defs", "stvn_core", org.stvnadore.core.ir.StvnDocumentKind.DEFS
      );
      ByteBuffer defsBuf = new StvnBinaryEncoder(
          true,
          new SchemaIdentityStrategy.UniversalDefault(),
          true
      ).encodeDefinitionsText(reservedDefsText, reservedDefsMeta);

      MalformedPayloadException defsEx = assertThrows(
          MalformedPayloadException.class,
          () -> StvnBinaryDecoder.decodeDocument(defsBuf.duplicate())
      );
      assertTrue(defsEx.getMessage().contains("Reserved system domain prohibited"));

      StvnDocument sysDefsDoc = StvnBinaryDecoder.decodeDocument(
          defsBuf.duplicate(), null, org.stvnadore.core.StvnParserConfig.SYSTEM_DEFAULT
      );
      assertEquals(Optional.of("stvn_core"), sysDefsDoc.requireMeta().domain());
      assertTrue(sysDefsDoc.hasDefsSection());
    }
  }
}
