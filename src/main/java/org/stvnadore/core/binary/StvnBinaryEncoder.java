package org.stvnadore.core.binary;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.binary.exceptions.StvnSerializationException;
import org.stvnadore.core.io.CanonicalStvnWriter;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnDocumentKind;
import org.stvnadore.core.ir.StvnDocumentMeta;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnSchema.FloatRepresentation;
import org.stvnadore.core.ir.StvnSchema.TemporalScale;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.ir.StvnValue.EitherBranch;
import org.stvnadore.core.ir.StvnValue.KeyValueEntry;
import org.stvnadore.core.lowering.StvnLiteralParser;
import org.stvnadore.core.utils.StvnBinaryUtils;
import org.stvnadore.core.validation.StvnIntegerOverflowException;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.CRC32C;

/**
 * Pure-IR binary encoder for serializing STVN 2.0.0 {@link StvnDocument} and {@link StvnValue} trees
 * into zero-copy binary byte layouts ({@code .stvn_b} and {@code .stvn_bd}).
 * <p>
 * Dynamically determines optimal offset pointer sizes (1, 2, or 4 bytes) from total footprint estimation,
 * serializes composite payloads in post-order traversal, and emits the magic-prefixed binary header
 * with optional 4-byte CRC-32C integrity trailers.
 *
 * <h2>Temporal &amp; Arbitrary Bit-Width Fidelity</h2>
 * <ul>
 *   <li>{@code { #s | #ms | #us } :TimeEpoch} encodes as signed 64-bit two's-complement little-endian ticks (8 bytes),
 *       preserving negative pre-1970 Unix timestamps.</li>
 *   <li>{@code { #ns } :TimeEpoch} encodes as signed 128-bit two's-complement little-endian ticks (16 bytes)
 *       backed by {@link BigInteger} without 64-bit truncation.</li>
 *   <li>{@code :Int} encodes into exact $B = \lceil n / 8 \rceil$ little-endian two's-complement bytes
 *       (4 bytes for unadorned 32-bit {@code :Int}), failing closed on bit-width overflow.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * Instances maintain internal serialization buffers during an {@code encode} call and are <b>not thread-safe</b>.
 *
 * @since 1.0.0
 */
@NullMarked
public class StvnBinaryEncoder {

  private static final int INITIAL_CAPACITY = 1024 * 16;
  private static final byte STRING_STYLE_SIMPLE = 0;

  private static final BigInteger MIN_SIGNED_64 = BigInteger.valueOf(Long.MIN_VALUE);
  private static final BigInteger MAX_SIGNED_64 = BigInteger.valueOf(Long.MAX_VALUE);
  private static final BigInteger MIN_SIGNED_128 = BigInteger.ONE.shiftLeft(127).negate();
  private static final BigInteger MAX_SIGNED_128 = BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE);

  private byte[] data = new byte[0];
  private ByteBuffer buffer = ByteBuffer.allocate(0);

  private final boolean isMinimizingOffsetSize;
  private final @Nullable SchemaIdentityStrategy identityStrategy;
  private final BinaryEncodingStrategy encodingStrategy;
  private final boolean hasTrailerCrc32c;

  private int offsetSize;

  private final IdentityHashMap<StvnValue, byte[]> stringCache = new IdentityHashMap<>();

  private record Footprint(long staticBytes, long pointerCount) {
    Footprint add(Footprint other) {
      return new Footprint(this.staticBytes + other.staticBytes, this.pointerCount + other.pointerCount);
    }
  }

  /**
   * Constructs a new {@code StvnBinaryEncoder} defaulting to {@link BinaryEncodingStrategy#ZERO_COPY_POST_ORDER}.
   *
   * @param isMinimizingOffsetSize if {@code true}, downscales pointer widths to 1 or 2 bytes when footprint allows
   * @param identityStrategy       the schema identity strategy, or {@code null}
   */
  public StvnBinaryEncoder(boolean isMinimizingOffsetSize, @Nullable SchemaIdentityStrategy identityStrategy) {
    this(isMinimizingOffsetSize, identityStrategy, BinaryEncodingStrategy.ZERO_COPY_POST_ORDER, false);
  }

  /**
   * Constructs a new {@code StvnBinaryEncoder} with an explicit CRC-32C trailer flag.
   *
   * @param isMinimizingOffsetSize if {@code true}, downscales pointer widths to 1 or 2 bytes when footprint allows
   * @param identityStrategy       the schema identity strategy, or {@code null}
   * @param hasTrailerCrc32c       if {@code true}, appends a 4-byte CRC-32C trailer and sets Bit 7 of Byte 4
   */
  public StvnBinaryEncoder(
      boolean isMinimizingOffsetSize,
      @Nullable SchemaIdentityStrategy identityStrategy,
      boolean hasTrailerCrc32c
  ) {
    this(isMinimizingOffsetSize, identityStrategy, BinaryEncodingStrategy.ZERO_COPY_POST_ORDER, hasTrailerCrc32c);
  }

  /**
   * Constructs a new {@code StvnBinaryEncoder} with explicit encoding and identity strategies.
   *
   * @param isMinimizingOffsetSize if {@code true}, downscales pointer widths to 1 or 2 bytes when footprint allows
   * @param identityStrategy       the schema identity strategy, or {@code null}
   * @param encodingStrategy       the binary wire encoding strategy
   */
  public StvnBinaryEncoder(
      boolean isMinimizingOffsetSize,
      @Nullable SchemaIdentityStrategy identityStrategy,
      BinaryEncodingStrategy encodingStrategy
  ) {
    this(isMinimizingOffsetSize, identityStrategy, encodingStrategy, false);
  }

  /**
   * Constructs a new {@code StvnBinaryEncoder} with explicit encoding strategy, identity strategy, and CRC-32C flag.
   *
   * @param isMinimizingOffsetSize if {@code true}, downscales pointer widths to 1 or 2 bytes when footprint allows
   * @param identityStrategy       the schema identity strategy, or {@code null}
   * @param encodingStrategy       the binary wire encoding strategy
   * @param hasTrailerCrc32c       if {@code true}, appends a 4-byte CRC-32C trailer and sets Bit 7 of Byte 4
   */
  public StvnBinaryEncoder(
      boolean isMinimizingOffsetSize,
      @Nullable SchemaIdentityStrategy identityStrategy,
      BinaryEncodingStrategy encodingStrategy,
      boolean hasTrailerCrc32c
  ) {
    this.isMinimizingOffsetSize = isMinimizingOffsetSize;
    this.identityStrategy = identityStrategy;
    this.encodingStrategy = Objects.requireNonNull(encodingStrategy, "encodingStrategy must not be null");
    if (identityStrategy instanceof SchemaIdentityStrategy.ExplicitSha256) {
      this.hasTrailerCrc32c = true;
    } else {
      this.hasTrailerCrc32c = hasTrailerCrc32c;
    }
  }

  /**
   * Returns whether this encoder appends a 4-byte CRC-32C trailer to serialized byte buffers.
   *
   * @return {@code true} if CRC-32C trailer appending is enabled
   */
  public boolean hasTrailerCrc32c() {
    return hasTrailerCrc32c;
  }

  /**
   * Encodes a pure-IR {@link StvnDocument} into either a {@code .stvn_b} binary body frame
   * ({@code #kind == #BODY}, Byte 5 {@code 0b00}) or a {@code .stvn_bd} binary definitions frame
   * ({@code #kind == #DEFS}, Byte 5 {@code 0b01}).
   *
   * @param document the pure-IR document to encode
   * @return a little-endian {@link ByteBuffer} containing the serialized binary envelope
   * @throws IllegalStateException      if the document contains unrecovered {@link StvnValue.StvnErrorValue} nodes
   * @throws StvnSerializationException if schema or metadata validation fails
   * @since 2.0.0
   */
  public ByteBuffer encode(StvnDocument document) {
    Objects.requireNonNull(document, "document must not be null");
    if (document.payload().isPresent()) {
      StvnDocumentMeta effectiveMeta = document.meta()
          .map(m -> m.kind().isPresent()
              ? m
              : new StvnDocumentMeta(m.name(), m.domain(), Optional.of(StvnDocumentKind.BODY)))
          .orElseGet(() -> StvnDocumentMeta.ofKind(StvnDocumentKind.BODY));
      return encode(document.payload().get(), effectiveMeta);
    }

    StvnDocumentKind defsKind = document.hasIncludes() ? StvnDocumentKind.DEFS_INCLUDE : StvnDocumentKind.DEFS;
    StvnDocumentMeta effectiveMeta = document.meta()
        .map(m -> m.kind().isPresent()
            ? m
            : new StvnDocumentMeta(m.name(), m.domain(), Optional.of(defsKind)))
        .orElseGet(() -> StvnDocumentMeta.ofKind(defsKind));

    String canonicalDefsText = new CanonicalStvnWriter().printToString(document);
    byte[] defsUtf8 = canonicalDefsText.getBytes(StandardCharsets.UTF_8);
    int totalStringLength = 1 + defsUtf8.length;
    int prefixLen = getDerivedLengthPrefixSize(totalStringLength);

    Footprint payloadFp = new Footprint(prefixLen + totalStringLength, 1);
    return encodeEnvelope(effectiveMeta, payloadFp, () -> writeRawOutlinedUtf8Bytes(defsUtf8));
  }

  /**
   * Encodes the provided root pure-IR {@link StvnValue} tree into a {@code .stvn_b} binary byte buffer
   * with default {@code #BODY} document metadata.
   *
   * @param root the root pure-IR value node to encode
   * @return a little-endian {@link ByteBuffer} containing the STVN binary payload
   * @throws IllegalStateException      if {@code root} contains unrecovered {@link StvnValue.StvnErrorValue} nodes
   * @throws StvnSerializationException if schema validation fails
   * @since 2.0.0
   */
  public ByteBuffer encode(StvnValue root) {
    return encode(root, StvnDocumentMeta.ofKind(StvnDocumentKind.BODY));
  }

  /**
   * Encodes the provided root pure-IR {@link StvnValue} tree and optional {@link StvnDocumentMeta}
   * into a binary byte buffer.
   *
   * @param root the root pure-IR value node to encode
   * @param meta the pure-IR document identity metadata to serialize into Byte 5+, or {@code null}
   * @return a little-endian {@link ByteBuffer} containing the STVN binary payload
   * @throws IllegalStateException      if {@code root} contains unrecovered {@link StvnValue.StvnErrorValue} nodes
   * @throws StvnSerializationException if schema validation fails
   * @since 2.0.0
   */
  public ByteBuffer encode(StvnValue root, @Nullable StvnDocumentMeta meta) {
    Objects.requireNonNull(root, "root must not be null");
    if (StvnCompiler.hasErrorNodes(root)) {
      throw new IllegalStateException("Cannot encode partial IR containing unrecovered StvnErrorValue nodes");
    }

    this.stringCache.clear();
    StvnDocumentMeta effectiveMeta = meta != null ? meta : StvnDocumentMeta.ofKind(StvnDocumentKind.BODY);

    if (identityStrategy != null) {
      StvnBinaryDecoder.validateSchemaHash(root.schema(), identityStrategy);
    }

    Footprint fp = calculateFootprint(root).add(new Footprint(0, 1));
    return encodeEnvelope(effectiveMeta, fp, () -> writeValuePostOrder(root));
  }

  @FunctionalInterface
  private interface PayloadWriter {
    int write();
  }

  private ByteBuffer encodeEnvelope(
      StvnDocumentMeta effectiveMeta,
      Footprint payloadFootprint,
      PayloadWriter payloadWriter
  ) {
    long metaFrameLength = 1; // Byte 5 (MetaControlByte)
    byte[] nameBytes = null;
    if (effectiveMeta.name().isPresent()) {
      nameBytes = effectiveMeta.name().get().getBytes(StandardCharsets.UTF_8);
      metaFrameLength += 1 + nameBytes.length;
    }
    byte[] domainBytes = null;
    if (effectiveMeta.domain().isPresent()) {
      domainBytes = effectiveMeta.domain().get().getBytes(StandardCharsets.UTF_8);
      metaFrameLength += 1 + domainBytes.length;
    }

    long baseHeader = 5 + metaFrameLength + 1;
    if (identityStrategy != null) {
      baseHeader += switch (identityStrategy) {
        case SchemaIdentityStrategy.UniversalDefault ignored -> 0;
        case SchemaIdentityStrategy.UuidV8Hash ignored -> 0;
        case SchemaIdentityStrategy.Sha256Hash ignored -> 0;
        case SchemaIdentityStrategy.AsciiStringKey ascii ->
            2 + ascii.repositoryKey().getBytes(StandardCharsets.US_ASCII).length;
        case SchemaIdentityStrategy.UnicodeStringKey utf8 ->
            2 + utf8.repositoryKey().getBytes(StandardCharsets.UTF_8).length;
        case SchemaIdentityStrategy.UniversalVersion ignored -> 4;
        case SchemaIdentityStrategy.ExplicitUuid ignored -> 16;
        case SchemaIdentityStrategy.ExplicitSha256 ignored -> 32;
        case SchemaIdentityStrategy.SelfDescribingSchema self ->
            4 + self.schemaSourceText().getBytes(StandardCharsets.UTF_8).length;
      };
    }

    Footprint fp = payloadFootprint.add(new Footprint(baseHeader + (hasTrailerCrc32c ? 4 : 0), 0));

    this.offsetSize = 4;
    if (isMinimizingOffsetSize) {
      if (fp.staticBytes + fp.pointerCount <= 255) {
        this.offsetSize = 1;
      } else if (fp.staticBytes + (fp.pointerCount * 2) <= 65535) {
        this.offsetSize = 2;
      }
    }

    int headerSize = (int) baseHeader + this.offsetSize;
    int initialAlloc = Math.max(INITIAL_CAPACITY, headerSize + 64);
    this.data = new byte[initialAlloc];
    this.buffer = ByteBuffer.wrap(this.data).order(ByteOrder.LITTLE_ENDIAN);
    this.buffer.position(headerSize);

    int rootOffset = payloadWriter.write();
    int payloadLimit = buffer.position();
    writeHeader(rootOffset, effectiveMeta, nameBytes, domainBytes);

    int totalLimit = payloadLimit;
    if (hasTrailerCrc32c) {
      ensureCapacity(4);
      buffer.position(payloadLimit);

      CRC32C crc = new CRC32C();
      crc.update(ByteBuffer.wrap(this.data, 0, payloadLimit));
      int crc32cValue = (int) crc.getValue();

      buffer.putInt(crc32cValue);
      totalLimit = payloadLimit + 4;
    }

    buffer.limit(totalLimit);
    buffer.position(0);
    return buffer.slice().order(ByteOrder.LITTLE_ENDIAN);
  }

  private Footprint calculateFootprint(StvnValue value) {
    validateInlineConstraintsPrecheck(value);
    int inlineSize = getInlineSize(value);
    if (inlineSize > 0) {
      return new Footprint(inlineSize, 0);
    }

    return switch (value) {
      case StvnValue.StvnString str -> {
        byte[] utf8Bytes = str.value().getBytes(StandardCharsets.UTF_8);
        stringCache.put(str, utf8Bytes);
        int totalLength = 1 + utf8Bytes.length;
        int prefixLen = getDerivedLengthPrefixSize(totalLength);
        yield new Footprint(prefixLen + totalLength, 0);
      }
      case StvnValue.StvnFloat exactFloat -> {
        byte[] utf8Bytes = formatExactFloatWireString(exactFloat).getBytes(StandardCharsets.UTF_8);
        stringCache.put(exactFloat, utf8Bytes);
        int totalLength = 1 + utf8Bytes.length;
        int prefixLen = getDerivedLengthPrefixSize(totalLength);
        yield new Footprint(prefixLen + totalLength, 0);
      }
      case StvnValue.StvnDateTime dt -> {
        String canonicalIso = StvnLiteralParser.formatCanonicalDateTime(
            dt.mode(), dt.localWallClock(), dt.offset(), dt.zone());
        byte[] utf8Bytes = canonicalIso.getBytes(StandardCharsets.UTF_8);
        stringCache.put(dt, utf8Bytes);
        int totalLength = 1 + utf8Bytes.length;
        int prefixLen = getDerivedLengthPrefixSize(totalLength);
        yield new Footprint(prefixLen + totalLength, 0);
      }
      case StvnValue.StvnTuple tuple -> {
        long staticB = 0;
        long ptrs = 0;
        for (StvnValue el : tuple.elements()) {
          int elInline = getInlineSize(el);
          if (elInline > 0) {
            validateInlineConstraintsPrecheck(el);
            staticB += elInline;
          } else {
            ptrs += 1;
            Footprint childFp = calculateFootprint(el);
            staticB += childFp.staticBytes;
            ptrs += childFp.pointerCount;
          }
        }
        yield new Footprint(staticB, ptrs);
      }
      case StvnValue.StvnSeq seq -> calculateSequenceFootprint(seq.elements());
      case StvnValue.StvnSet set -> calculateSequenceFootprint(set.elements());
      case StvnValue.StvnMap map -> {
        int count = map.entries().size();
        long staticB = getDerivedLengthPrefixSize(count);
        long ptrs = 0;
        for (KeyValueEntry entry : map.entries()) {
          StvnValue k = entry.key();
          int kInline = getInlineSize(k);
          if (kInline > 0) {
            validateInlineConstraintsPrecheck(k);
            staticB += kInline;
          } else {
            ptrs += 1;
            Footprint kFp = calculateFootprint(k);
            staticB += kFp.staticBytes;
            ptrs += kFp.pointerCount;
          }

          StvnValue v = entry.value();
          int vInline = getInlineSize(v);
          if (vInline > 0) {
            validateInlineConstraintsPrecheck(v);
            staticB += vInline;
          } else {
            ptrs += 1;
            Footprint vFp = calculateFootprint(v);
            staticB += vFp.staticBytes;
            ptrs += vFp.pointerCount;
          }
        }
        yield new Footprint(staticB, ptrs);
      }
      case StvnValue.StvnOption opt -> opt.value()
          .map(inner -> {
            int optInline = getInlineSize(inner);
            if (optInline > 0) {
              validateInlineConstraintsPrecheck(inner);
              return new Footprint(1 + optInline, 0);
            }
            Footprint innerFp = calculateFootprint(inner);
            return new Footprint(1 + innerFp.staticBytes(), 1 + innerFp.pointerCount());
          })
          .orElse(new Footprint(1, 0));
      case StvnValue.StvnEither either -> {
        StvnValue inner = either.value();
        int eitherInline = getInlineSize(inner);
        if (eitherInline > 0) {
          validateInlineConstraintsPrecheck(inner);
          yield new Footprint(1 + eitherInline, 0);
        }
        Footprint innerFp = calculateFootprint(inner);
        yield new Footprint(1 + innerFp.staticBytes(), 1 + innerFp.pointerCount());
      }
      case StvnValue.StvnUnion union -> {
        StvnValue inner = union.value();
        int unionInline = getInlineSize(inner);
        if (unionInline > 0) {
          validateInlineConstraintsPrecheck(inner);
          yield new Footprint(1 + unionInline, 0);
        }
        Footprint innerFp = calculateFootprint(inner);
        yield new Footprint(1 + innerFp.staticBytes(), 1 + innerFp.pointerCount());
      }
      case StvnValue.StvnErrorValue ignored ->
          throw new IllegalStateException("Cannot binary encode IR containing unrecovered StvnErrorValue nodes");
      default ->
          throw new StvnSerializationException("Unexpected inline value reached outlined footprint calculator: " + value.getClass());
    };
  }

  private Footprint calculateSequenceFootprint(List<StvnValue> elements) {
    int count = elements.size();
    long staticB = getDerivedLengthPrefixSize(count);
    long ptrs = 0;
    for (StvnValue el : elements) {
      int elInline = getInlineSize(el);
      if (elInline > 0) {
        validateInlineConstraintsPrecheck(el);
        staticB += elInline;
      } else {
        ptrs += 1;
        Footprint childFp = calculateFootprint(el);
        staticB += childFp.staticBytes;
        ptrs += childFp.pointerCount;
      }
    }
    return new Footprint(staticB, ptrs);
  }

  private static void validateInlineConstraintsPrecheck(StvnValue value) {
    if (value instanceof StvnValue.StvnInt i) {
      if (!(i.schema() instanceof StvnSchema.IntSchema intSchema)) {
        throw new StvnSerializationException("StvnInt requires IntSchema governing schema");
      }
      if (!intSchema.acceptsWidth(i.value())) {
        throw new StvnIntegerOverflowException(
            "Integer value " + i.value() + " overflows "
                + (intSchema.unsigned() ? "unsigned " : "signed ")
                + intSchema.bitWidth() + "-bit binary wire slot",
            -1,
            -1
        );
      }
    } else if (value instanceof StvnValue.StvnTimeEpoch te) {
      BigInteger ticks = te.epochTicks();
      if (te.scale() == TemporalScale.NS) {
        if (ticks.compareTo(MIN_SIGNED_128) < 0 || ticks.compareTo(MAX_SIGNED_128) > 0) {
          throw new StvnIntegerOverflowException(
              "Nanosecond :TimeEpoch tick count " + ticks + " overflows signed 128-bit wire slot",
              -1,
              -1
          );
        }
      } else {
        if (ticks.compareTo(MIN_SIGNED_64) < 0 || ticks.compareTo(MAX_SIGNED_64) > 0) {
          throw new StvnIntegerOverflowException(
              ":TimeEpoch (" + te.scale() + ") tick count " + ticks + " overflows signed 64-bit wire slot",
              -1,
              -1
          );
        }
      }
    }
  }

  private static int getInlineSize(StvnValue value) {
    if (value instanceof StvnValue.StvnErrorValue) {
      throw new IllegalStateException("Cannot binary encode IR containing unrecovered StvnErrorValue nodes");
    }
    return StvnBinaryDecoder.getInlineSize(value.schema());
  }

  private void writeInlineValue(StvnValue value) {
    switch (value) {
      case StvnValue.StvnBoolean b ->
          buffer.put((byte) (b.value() ? 1 : 0));

      case StvnValue.StvnEnum e -> {
        int byteSize = getInlineSize(e);
        int index = e.rootOrdinal().intValueExact();
        switch (byteSize) {
          case 1 -> buffer.put((byte) index);
          case 2 -> buffer.putShort((short) index);
          case 4 -> buffer.putInt(index);
          default -> throw new IllegalStateException("Unsupported enum slot byte size: " + byteSize);
        }
      }

      case StvnValue.StvnInt i -> {
        StvnSchema.IntSchema intSchema = (StvnSchema.IntSchema) i.schema();
        int byteSize = getInlineSize(i);
        writeTwosComplementLittleEndian(i.value(), byteSize, intSchema.unsigned(), intSchema.bitWidth().intValueExact());
      }

      case StvnValue.StvnFloat f -> {
        int byteSize = getInlineSize(f);
        if (byteSize == 4) {
          int rawBits = switch (f.specialKind()) {
            case NAN -> Float.floatToRawIntBits(Float.NaN);
            case POSITIVE_INFINITY -> Float.floatToRawIntBits(Float.POSITIVE_INFINITY);
            case NEGATIVE_INFINITY -> Float.floatToRawIntBits(Float.NEGATIVE_INFINITY);
            case FINITE -> f.negativeZero()
                ? Float.floatToRawIntBits(-0.0f)
                : Float.floatToRawIntBits(f.decimalValue().floatValue());
          };
          buffer.putInt(rawBits);
        } else if (byteSize == 8) {
          long rawBits = switch (f.specialKind()) {
            case NAN -> Double.doubleToRawLongBits(Double.NaN);
            case POSITIVE_INFINITY -> Double.doubleToRawLongBits(Double.POSITIVE_INFINITY);
            case NEGATIVE_INFINITY -> Double.doubleToRawLongBits(Double.NEGATIVE_INFINITY);
            case FINITE -> f.negativeZero()
                ? Double.doubleToRawLongBits(-0.0d)
                : Double.doubleToRawLongBits(f.decimalValue().doubleValue());
          };
          buffer.putLong(rawBits);
        } else {
          throw new IllegalStateException("Unexpected inline float byteSize: " + byteSize);
        }
      }

      case StvnValue.StvnTimeEpoch te -> {
        int byteSize = te.scale() == TemporalScale.NS ? 16 : 8;
        writeTwosComplementLittleEndian(te.epochTicks(), byteSize, false, byteSize * 8);
      }

      case StvnValue.StvnErrorValue ignored ->
          throw new IllegalStateException("Cannot binary encode IR containing unrecovered StvnErrorValue nodes");

      default ->
          throw new IllegalStateException("Attempted to write out-of-line type inline: " + value.getClass());
    }
  }

  private void writeTwosComplementLittleEndian(BigInteger val, int byteSize, boolean unsigned, int bitWidth) {
    byte[] bigEndian = val.toByteArray();
    byte padByte = (!unsigned && val.signum() < 0) ? (byte) 0xFF : (byte) 0x00;

    for (int b = 0; b < byteSize; b++) {
      byte outByte;
      if (b < bigEndian.length) {
        outByte = bigEndian[bigEndian.length - 1 - b];
      } else {
        outByte = padByte;
      }
      if (unsigned && b == byteSize - 1 && (bitWidth % 8) != 0) {
        int validMask = (1 << (bitWidth % 8)) - 1;
        outByte = (byte) (outByte & validMask);
      }
      buffer.put(outByte);
    }
  }

  private static String formatExactFloatWireString(StvnValue.StvnFloat f) {
    return switch (f.specialKind()) {
      case NAN -> "NaN";
      case POSITIVE_INFINITY -> "+Infinity";
      case NEGATIVE_INFINITY -> "-Infinity";
      case FINITE -> f.negativeZero() ? "-0.0" : f.decimalValue().toString();
    };
  }

  private static int getDerivedLengthPrefixSize(int length) {
    if (length <= 0x3F) {
      return 1;
    }
    if (length <= 0x3FFF) {
      return 2;
    }
    if (length <= 0x3FFFFF) {
      return 3;
    }
    return 5;
  }

  private int writeValuePostOrder(StvnValue value) {
    int inlineSize = getInlineSize(value);
    if (inlineSize > 0) {
      ensureCapacity(inlineSize);
      int startOffset = buffer.position();
      writeInlineValue(value);
      return startOffset;
    }

    return switch (value) {
      case StvnValue.StvnString str -> writeCachedOrEncodeOutlinedString(str, str.value());
      case StvnValue.StvnFloat exactFloat ->
          writeCachedOrEncodeOutlinedString(exactFloat, formatExactFloatWireString(exactFloat));
      case StvnValue.StvnDateTime dt ->
          writeCachedOrEncodeOutlinedString(
              dt,
              StvnLiteralParser.formatCanonicalDateTime(dt.mode(), dt.localWallClock(), dt.offset(), dt.zone())
          );
      case StvnValue.StvnTuple tuple -> writeTuple(tuple);
      case StvnValue.StvnSeq seq -> writeSeqOutlined(seq.elements());
      case StvnValue.StvnSet set -> writeSeqOutlined(set.elements());
      case StvnValue.StvnMap map -> writeMapOutlined(map);
      case StvnValue.StvnOption opt -> writeOptionOutlined(opt);
      case StvnValue.StvnEither either -> writeEitherOutlined(either);
      case StvnValue.StvnUnion union -> writeUnionOutlined(union);
      case StvnValue.StvnErrorValue ignored ->
          throw new IllegalStateException("Cannot binary encode IR containing unrecovered StvnErrorValue nodes");
      default ->
          throw new StvnSerializationException("Unexpected value node in writeValuePostOrder: " + value.getClass());
    };
  }

  private int writeCachedOrEncodeOutlinedString(StvnValue cacheKey, String fallbackText) {
    byte[] utf8Bytes = stringCache.get(cacheKey);
    if (utf8Bytes == null) {
      utf8Bytes = fallbackText.getBytes(StandardCharsets.UTF_8);
    }
    return writeRawOutlinedUtf8Bytes(utf8Bytes);
  }

  private int writeRawOutlinedUtf8Bytes(byte[] utf8Bytes) {
    int totalLength = 1 + utf8Bytes.length;
    ensureCapacity(5 + totalLength);
    int startOffset = buffer.position();
    writeDerivedLengthPrefix(totalLength);
    buffer.put(STRING_STYLE_SIMPLE);
    buffer.put(utf8Bytes);
    return startOffset;
  }

  private int writeTuple(StvnValue.StvnTuple tuple) {
    List<StvnValue> elements = tuple.elements();
    int elementCount = elements.size();

    int[] outOfLineOffsets = new int[elementCount];
    int tableSize = 0;

    for (int i = 0; i < elementCount; i++) {
      StvnValue el = elements.get(i);
      int inlineSize = getInlineSize(el);
      if (inlineSize == 0) {
        outOfLineOffsets[i] = writeValuePostOrder(el);
        tableSize += this.offsetSize;
      } else {
        tableSize += inlineSize;
      }
    }

    ensureCapacity(tableSize);
    int tupleStartOffset = buffer.position();

    for (int i = 0; i < elementCount; i++) {
      StvnValue el = elements.get(i);
      int inlineSize = getInlineSize(el);
      if (inlineSize == 0) {
        writeOffsetPointer(outOfLineOffsets[i]);
      } else {
        writeInlineValue(el);
      }
    }
    return tupleStartOffset;
  }

  private int writeSeqOutlined(List<StvnValue> elements) {
    int count = elements.size();
    if (count == 0) {
      ensureCapacity(5);
      int emptyOffset = buffer.position();
      writeDerivedLengthPrefix(0);
      return emptyOffset;
    }

    int[] outOfLineOffsets = new int[count];
    int arrayBlockSize = 0;

    for (int i = 0; i < count; i++) {
      StvnValue el = elements.get(i);
      int inlineSize = getInlineSize(el);
      if (inlineSize == 0) {
        outOfLineOffsets[i] = writeValuePostOrder(el);
        arrayBlockSize += this.offsetSize;
      } else {
        arrayBlockSize += inlineSize;
      }
    }

    int prefixBytes = getDerivedLengthPrefixSize(count);
    ensureCapacity(prefixBytes + arrayBlockSize);
    int seqStartOffset = buffer.position();
    writeDerivedLengthPrefix(count);

    for (int i = 0; i < count; i++) {
      StvnValue el = elements.get(i);
      int inlineSize = getInlineSize(el);
      if (inlineSize == 0) {
        writeOffsetPointer(outOfLineOffsets[i]);
      } else {
        writeInlineValue(el);
      }
    }
    return seqStartOffset;
  }

  private int writeMapOutlined(StvnValue.StvnMap map) {
    List<KeyValueEntry> entries = map.entries();
    int count = entries.size();

    if (count == 0) {
      ensureCapacity(5);
      int emptyOffset = buffer.position();
      writeDerivedLengthPrefix(0);
      return emptyOffset;
    }

    int[] outOfLineKeyOffsets = new int[count];
    int[] outOfLineValOffsets = new int[count];
    int keyArrayBlockSize = 0;
    int valArrayBlockSize = 0;

    for (int i = 0; i < count; i++) {
      KeyValueEntry entry = entries.get(i);
      StvnValue k = entry.key();
      int kInline = getInlineSize(k);
      if (kInline == 0) {
        outOfLineKeyOffsets[i] = writeValuePostOrder(k);
        keyArrayBlockSize += this.offsetSize;
      } else {
        keyArrayBlockSize += kInline;
      }

      StvnValue v = entry.value();
      int vInline = getInlineSize(v);
      if (vInline == 0) {
        outOfLineValOffsets[i] = writeValuePostOrder(v);
        valArrayBlockSize += this.offsetSize;
      } else {
        valArrayBlockSize += vInline;
      }
    }

    int prefixBytes = getDerivedLengthPrefixSize(count);
    ensureCapacity(prefixBytes + keyArrayBlockSize + valArrayBlockSize);
    int mapStartOffset = buffer.position();
    writeDerivedLengthPrefix(count);

    for (int i = 0; i < count; i++) {
      StvnValue k = entries.get(i).key();
      if (getInlineSize(k) == 0) {
        writeOffsetPointer(outOfLineKeyOffsets[i]);
      } else {
        writeInlineValue(k);
      }
    }

    for (int i = 0; i < count; i++) {
      StvnValue v = entries.get(i).value();
      if (getInlineSize(v) == 0) {
        writeOffsetPointer(outOfLineValOffsets[i]);
      } else {
        writeInlineValue(v);
      }
    }

    return mapStartOffset;
  }

  private int writeOptionOutlined(StvnValue.StvnOption opt) {
    return opt.value()
        .map(inner -> {
          int inlineSize = getInlineSize(inner);
          if (inlineSize == 0) {
            int innerOffset = writeValuePostOrder(inner);
            ensureCapacity(1 + this.offsetSize);
            int startOffset = buffer.position();
            buffer.put((byte) 1);
            writeOffsetPointer(innerOffset);
            return startOffset;
          }
          ensureCapacity(1 + inlineSize);
          int startOffset = buffer.position();
          buffer.put((byte) 1);
          writeInlineValue(inner);
          return startOffset;
        })
        .orElseGet(() -> {
          ensureCapacity(1);
          int startOffset = buffer.position();
          buffer.put((byte) 0);
          return startOffset;
        });
  }

  private int writeEitherOutlined(StvnValue.StvnEither either) {
    StvnValue inner = either.value();
    int inlineSize = getInlineSize(inner);
    byte activeTag = (byte) (either.branch() == EitherBranch.RIGHT ? 1 : 0);

    if (inlineSize == 0) {
      int innerOffset = writeValuePostOrder(inner);
      ensureCapacity(1 + this.offsetSize);
      int startOffset = buffer.position();
      buffer.put(activeTag);
      writeOffsetPointer(innerOffset);
      return startOffset;
    }
    ensureCapacity(1 + inlineSize);
    int startOffset = buffer.position();
    buffer.put(activeTag);
    writeInlineValue(inner);
    return startOffset;
  }

  private int writeUnionOutlined(StvnValue.StvnUnion union) {
    StvnValue inner = union.value();
    int inlineSize = getInlineSize(inner);
    int zeroBasedIndex;
    try {
      zeroBasedIndex = union.oneBasedBranchIndex().intValueExact() - 1;
    } catch (ArithmeticException e) {
      throw new StvnSerializationException("Union branch index exceeds wire capacity: " + union.oneBasedBranchIndex(), e);
    }
    if (zeroBasedIndex < 0 || zeroBasedIndex > 255) {
      throw new StvnSerializationException("Union branch index out of 1-byte wire tag range [1..256]: " + union.oneBasedBranchIndex());
    }
    byte activeTag = (byte) zeroBasedIndex;

    if (inlineSize == 0) {
      int innerOffset = writeValuePostOrder(inner);
      ensureCapacity(1 + this.offsetSize);
      int startOffset = buffer.position();
      buffer.put(activeTag);
      writeOffsetPointer(innerOffset);
      return startOffset;
    }
    ensureCapacity(1 + inlineSize);
    int startOffset = buffer.position();
    buffer.put(activeTag);
    writeInlineValue(inner);
    return startOffset;
  }

  private void writeDerivedLengthPrefix(int length) {
    if (length < 0) {
      throw new IllegalArgumentException("Length cannot be negative");
    }
    if (length <= 0x3F) {
      buffer.put((byte) length);
    } else if (length <= 0x3FFF) {
      buffer.put((byte) (0x40 | (length >>> 8)));
      buffer.put((byte) (length & 0xFF));
    } else if (length <= 0x3FFFFF) {
      buffer.put((byte) (0x80 | (length >>> 16)));
      buffer.put((byte) ((length >>> 8) & 0xFF));
      buffer.put((byte) (length & 0xFF));
    } else {
      buffer.put((byte) 0xC0);
      buffer.putInt(length);
    }
  }

  private void writeOffsetPointer(int targetOffset) {
    switch (this.offsetSize) {
      case 1 -> buffer.put((byte) targetOffset);
      case 2 -> buffer.putShort((short) targetOffset);
      case 4 -> buffer.putInt(targetOffset);
      case 8 -> buffer.putLong(targetOffset);
      default -> throw new IllegalStateException("Unsupported offsetSize: " + this.offsetSize);
    }
  }

  private void writeHeader(
      int rootOffset,
      StvnDocumentMeta meta,
      byte @Nullable [] nameBytes,
      byte @Nullable [] domainBytes
  ) {
    int finalPos = buffer.position();
    buffer.position(0);

    buffer.put((byte) 'S');
    buffer.put((byte) 'T');
    buffer.put((byte) 'V');
    buffer.put((byte) 'N');

    int identityCode = (identityStrategy != null) ? identityStrategy.code() : 0x00;
    int trailerBit = hasTrailerCrc32c ? 0x80 : 0x00;
    int encodingBits = (encodingStrategy.code() & 0x07) << 4;
    int identityBits = identityCode & 0x0F;
    byte controlByte = (byte) (trailerBit | encodingBits | identityBits);
    buffer.put(controlByte);

    int kindBits = switch (meta.kind().orElse(StvnDocumentKind.BODY)) {
      case BODY -> 0b00;
      case DEFS -> 0b01;
      case BODY_INCLUDE -> 0b10;
      case DEFS_INCLUDE -> 0b11;
    };
    int hasNameBit = (nameBytes != null) ? StvnBinaryUtils.META_MASK_HAS_NAME : 0x00;
    int hasDomainBit = (domainBytes != null) ? StvnBinaryUtils.META_MASK_HAS_DOMAIN : 0x00;
    byte metaControlByte = (byte) (hasDomainBit | hasNameBit | kindBits);
    buffer.put(metaControlByte);

    if (nameBytes != null) {
      buffer.put((byte) nameBytes.length);
      buffer.put(nameBytes);
    }
    if (domainBytes != null) {
      buffer.put((byte) domainBytes.length);
      buffer.put(domainBytes);
    }

    if (identityStrategy != null) {
      switch (identityStrategy) {
        case SchemaIdentityStrategy.UniversalDefault ignored -> {
        }
        case SchemaIdentityStrategy.UuidV8Hash ignored -> {
        }
        case SchemaIdentityStrategy.Sha256Hash ignored -> {
        }
        case SchemaIdentityStrategy.AsciiStringKey ascii -> {
          byte[] bytes = ascii.repositoryKey().getBytes(StandardCharsets.US_ASCII);
          buffer.putShort((short) (bytes.length - 1));
          buffer.put(bytes);
        }
        case SchemaIdentityStrategy.UnicodeStringKey utf8 -> {
          byte[] bytes = utf8.repositoryKey().getBytes(StandardCharsets.UTF_8);
          buffer.putShort((short) (bytes.length - 1));
          buffer.put(bytes);
        }
        case SchemaIdentityStrategy.UniversalVersion uv ->
            buffer.putInt((int) (uv.version() - 1));
        case SchemaIdentityStrategy.ExplicitUuid explicit -> {
          buffer.putLong(explicit.uuid().getMostSignificantBits());
          buffer.putLong(explicit.uuid().getLeastSignificantBits());
        }
        case SchemaIdentityStrategy.ExplicitSha256 explicit ->
            buffer.put(explicit.hash());
        case SchemaIdentityStrategy.SelfDescribingSchema self -> {
          byte[] bytes = self.schemaSourceText().getBytes(StandardCharsets.UTF_8);
          buffer.putInt(bytes.length - 1);
          buffer.put(bytes);
        }
      }
    }

    int offsetCode = switch (offsetSize) {
      case 1 -> 0b00;
      case 2 -> 0b01;
      case 4 -> 0b10;
      case 8 -> 0b11;
      default -> throw new IllegalStateException("Unsupported offsetSize: " + offsetSize);
    };
    buffer.put((byte) offsetCode);

    writeOffsetPointer(rootOffset);
    buffer.position(finalPos);
  }

  private void ensureCapacity(int neededBytes) {
    if (buffer.position() + neededBytes > buffer.capacity()) {
      int newCapacity = Math.max(buffer.capacity() * 2, buffer.position() + neededBytes);
      byte[] newData = new byte[newCapacity];
      System.arraycopy(data, 0, newData, 0, buffer.position());
      int currentPos = buffer.position();
      this.data = newData;
      this.buffer = ByteBuffer.wrap(this.data).order(ByteOrder.LITTLE_ENDIAN);
      this.buffer.position(currentPos);
    }
  }
}
