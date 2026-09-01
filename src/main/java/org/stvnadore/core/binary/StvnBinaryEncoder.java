package org.stvnadore.core.binary;

import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.ir.StvnValue.*;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;

import org.jspecify.annotations.Nullable;

/**
 * Binary encoder for serializing STVN Intermediate Representation (IR) values
 * into highly optimized, zero-copy binary byte layouts.
 * <p>
 * The encoder determines optimal offset sizes (1, 2, or 4 bytes) dynamically based on the total
 * footprint estimation, serializes values in a post-order traversal, and builds the magic-prefixed
 * binary header.
 *
 * <h2>Unsigned 1-Based Length Format</h2>
 * Any variable-length fields (like strings, sequences, and maps) write their sizes using an unsigned
 * 1-based encoding format ({@code length = value + 1}). Under this scheme, a stored byte value of {@code 0}
 * corresponds to a decoded length of {@code 1} byte. This minimizes overall framing size overhead for small
 * payloads and removes dependency on null-terminators, eliminating traditional buffer overflow vulnerabilities.
 *
 * <h2>Thread Safety &amp; Mutability</h2>
 * This class is stateful (maintains serialization buffers and caches during the encoding lifecycle)
 * and is <b>not thread-safe</b>. Instances should not be shared concurrently across threads.
 *
 * @since 1.0.0
 */
public class StvnBinaryEncoder {

  private static final int MAGIC_BYTES = 0x5354564E; // "STVN"
  private static final int INITIAL_CAPACITY = 1024 * 16;

  private byte[] data;
  private ByteBuffer buffer;

  private final boolean isMinimizingOffsetSize;
  private final @Nullable SchemaIdentityStrategy identityStrategy;
  private final BinaryEncodingStrategy encodingStrategy;

  private int offsetSize;

  // Cache UTF-8 conversions during the pre-pass to avoid double-encoding strings later
  private final IdentityHashMap<StvnValue, byte[]> stringCache = new IdentityHashMap<>();

  private record Footprint(long staticBytes, long pointerCount) {
    Footprint add(Footprint other) {
      return new Footprint(this.staticBytes + other.staticBytes, this.pointerCount + other.pointerCount);
    }
  }

  /**
   * Constructs a new StvnBinaryEncoder configuration defaulting to {@link BinaryEncodingStrategy#ZERO_COPY_POST_ORDER}.
   *
   * @param isMinimizingOffsetSize if {@code true}, the encoder will downscale offset pointer sizes (to 1 or 2 bytes)
   *                               if the total buffer footprint allows, saving space
   * @param identityStrategy       the strategy used to register and verify schema hashes, or {@code null} if untyped
   */
  public StvnBinaryEncoder(boolean isMinimizingOffsetSize, @Nullable SchemaIdentityStrategy identityStrategy) {
    this(isMinimizingOffsetSize, identityStrategy, BinaryEncodingStrategy.ZERO_COPY_POST_ORDER);
  }

  /**
   * Constructs a new StvnBinaryEncoder configuration with explicit encoding and identity strategies.
   *
   * @param isMinimizingOffsetSize if {@code true}, the encoder will downscale offset pointer sizes (to 1 or 2 bytes)
   *                               if the total buffer footprint allows, saving space
   * @param identityStrategy       the strategy used to register and verify schema hashes, or {@code null} if untyped
   * @param encodingStrategy       the binary wire encoding strategy to use
   */
  public StvnBinaryEncoder(boolean isMinimizingOffsetSize, @Nullable SchemaIdentityStrategy identityStrategy, BinaryEncodingStrategy encodingStrategy) {
    this.isMinimizingOffsetSize = isMinimizingOffsetSize;
    this.identityStrategy = identityStrategy;
    this.encodingStrategy = encodingStrategy;
  }

  /**
   * Encodes the provided root STVN IR AST value tree into a binary byte buffer.
   *
   * @param root the root value node of the AST to encode
   * @return a read-only, little-endian byte buffer containing the STVN binary payload
   * @throws org.stvnadore.core.binary.exceptions.StvnSerializationException if structural fields or schemas are invalid
   */
  public ByteBuffer encode(StvnValue root) {
    this.stringCache.clear();

    if (identityStrategy != null && root.schema() != null) {
      StvnBinaryDecoder.validateSchemaHash(root.schema(), identityStrategy);
    }

    // 1. Calculate static footprint and pointer count (Root takes 1 pointer in header)
    Footprint fp = calculateFootprint(root).add(new Footprint(0, 1));

    // 2. Base header size: 4 (Magic) + 1 (Control Byte) + 1 (Flags) + Strategy Payload
    long baseHeader = 6;
    if (identityStrategy != null) {
      baseHeader += switch (identityStrategy) {
        case SchemaIdentityStrategy.UniversalDefault ignored -> 0;
        case SchemaIdentityStrategy.UuidV8Hash ignored -> 0;
        case SchemaIdentityStrategy.Sha256Hash ignored -> 0;
        case SchemaIdentityStrategy.AsciiStringKey ascii -> 2 + ascii.repositoryKey().getBytes(StandardCharsets.US_ASCII).length;
        case SchemaIdentityStrategy.UnicodeStringKey utf8 -> 2 + utf8.repositoryKey().getBytes(StandardCharsets.UTF_8).length;
        case SchemaIdentityStrategy.UniversalVersion ignored -> 4;
        case SchemaIdentityStrategy.ExplicitUuid ignored -> 16;
        case SchemaIdentityStrategy.ExplicitSha256 ignored -> 32;
        case SchemaIdentityStrategy.SelfDescribingSchema self -> 4 + self.stvnInclfContent().getBytes(StandardCharsets.UTF_8).length;
      };
    }
    fp = fp.add(new Footprint(baseHeader, 0));

    // 3. Determine optimal Offset Size
    this.offsetSize = 4; // Default to uint32
    if (isMinimizingOffsetSize) {
      if (fp.staticBytes + fp.pointerCount <= 255) {
        this.offsetSize = 1;
      } else if (fp.staticBytes + (fp.pointerCount * 2) <= 65535) {
        this.offsetSize = 2;
      }
    }

    // 4. Initialize buffer state
    var headerSize = (int) baseHeader + this.offsetSize;
    this.data = new byte[INITIAL_CAPACITY];
    this.buffer = ByteBuffer.wrap(this.data).order(ByteOrder.LITTLE_ENDIAN);
    this.buffer.position(headerSize);

    // 5. Execute post-order traversal
    int rootOffset = writeValuePostOrder(root);
    int totalLimit = buffer.position();
    writeHeader(rootOffset);

    buffer.limit(totalLimit);
    buffer.position(0);
    return buffer.slice().order(ByteOrder.LITTLE_ENDIAN);
  }

  // --- PHASE 1: FOOTPRINT CALCULATION ---

  private Footprint calculateFootprint(StvnValue value) {
    // GUARD: Handle ANY standalone fixed-width primitive (e.g., a root Int32 or Float64)
    int inlineSize = getInlineSize(value);
    if (inlineSize > 0) {
      return new Footprint(inlineSize, 0);
    }

    if (value.schema() == null) {
      throw new org.stvnadore.core.binary.exceptions.StvnSerializationException("Missing schema context for value: " + value);
    }
    var isAny = false;

    var fp = switch (value) {
      case StvnValue.StvnString str -> {
        byte[] utf8Bytes = str.value().getBytes(StandardCharsets.UTF_8);
        stringCache.put(str, utf8Bytes);
        int totalLength = 1 + utf8Bytes.length; // +1 for the style byte
        if (str.style() == StvnValue.StringStyle.FENCED) {
          byte[] tagBytes = str.fenceTag()
              .map(fTag -> fTag.getBytes(StandardCharsets.UTF_8))
              .orElseGet(() -> "TAG".getBytes(StandardCharsets.UTF_8));
          totalLength += 1 + tagBytes.length; // +1 for tag length byte
        }
        int prefixLen = getDerivedLengthPrefixSize(totalLength);
        yield new Footprint(prefixLen + totalLength, 0);
      }
      case StvnValue.StvnInteger bigInt -> { // bitWidth == 0 (Arbitrary Precision / Out-of-line)
        byte[] bytes = bigInt.value().toByteArray();
        int prefixLen = getDerivedLengthPrefixSize(bytes.length);
        yield new Footprint(prefixLen + bytes.length, 0);
      }
      case StvnFloat exactFloat -> { // precision == EXACT (Out-of-line)
        byte[] utf8Bytes = exactFloat.value().toString().getBytes(StandardCharsets.UTF_8);
        int totalLength = 1 + utf8Bytes.length; // +1 for fallback style byte
        int prefixLen = getDerivedLengthPrefixSize(totalLength);
        yield new Footprint(prefixLen + totalLength, 0);
      }
      case StvnTime time -> { // ZONED or OFFSET (Out-of-line)
        byte[] utf8Bytes = time.value().toString().getBytes(StandardCharsets.UTF_8);
        stringCache.put(time, utf8Bytes);
        int totalLength = 1 + utf8Bytes.length; // +1 for fallback style byte
        int prefixLen = getDerivedLengthPrefixSize(totalLength);
        yield new Footprint(prefixLen + totalLength, 0);
      }
      case StvnDateTimeOffset dto -> {
        byte[] utf8Bytes = dto.value().toString().getBytes(StandardCharsets.UTF_8);
        stringCache.put(dto, utf8Bytes);
        int totalLength = 1 + utf8Bytes.length; // +1 for fallback style byte
        int prefixLen = getDerivedLengthPrefixSize(totalLength);
        yield new Footprint(prefixLen + totalLength, 0);
      }
      case StvnDateTimeZoned dtz -> {
        byte[] utf8Bytes = (dtz.localDateTime().toString() + "[" + dtz.zoneId().getId() + "]").getBytes(StandardCharsets.UTF_8);
        stringCache.put(dtz, utf8Bytes);
        int totalLength = 1 + utf8Bytes.length; // +1 for fallback style byte
        int prefixLen = getDerivedLengthPrefixSize(totalLength);
        yield new Footprint(prefixLen + totalLength, 0);
      }
      case StvnDateTimeAudited dta -> {
        byte[] utf8Bytes = (dta.offsetDateTime().toString() + "[" + dta.zoneId().getId() + "]").getBytes(StandardCharsets.UTF_8);
        stringCache.put(dta, utf8Bytes);
        int totalLength = 1 + utf8Bytes.length; // +1 for fallback style byte
        int prefixLen = getDerivedLengthPrefixSize(totalLength);
        yield new Footprint(prefixLen + totalLength, 0);
      }
      case StvnTuple tuple -> {
        long staticB = 0;
        long ptrs = 0;
        for (StvnValue el : tuple.elements()) {
          int elInline = getInlineSize(el);
          if (elInline > 0) staticB += elInline;
          else {
            ptrs += 1;
            Footprint childFp = calculateFootprint(el);
            staticB += childFp.staticBytes;
            ptrs += childFp.pointerCount;
          }
        }
        yield new Footprint(staticB, ptrs);
      }
      case StvnSeq seq -> {
        int count = seq.elements().size();
        long staticB = getDerivedLengthPrefixSize(count);
        long ptrs = 0;
        for (StvnValue el : seq.elements()) {
          int elInline = getInlineSize(el);
          if (elInline > 0) staticB += elInline;
          else {
            ptrs += 1;
            Footprint childFp = calculateFootprint(el);
            staticB += childFp.staticBytes;
            ptrs += childFp.pointerCount;
          }
        }
        yield new Footprint(staticB, ptrs);
      }
      case StvnSet set -> {
        int count = set.elements().size();
        long staticB = getDerivedLengthPrefixSize(count);
        long ptrs = 0;
        for (StvnValue el : set.elements()) {
          int elInline = getInlineSize(el);
          if (elInline > 0) staticB += elInline;
          else {
            ptrs += 1;
            Footprint childFp = calculateFootprint(el);
            staticB += childFp.staticBytes;
            ptrs += childFp.pointerCount;
          }
        }
        yield new Footprint(staticB, ptrs);
      }
      case StvnMap map -> {
        int count = map.entries().size();
        long staticB = getDerivedLengthPrefixSize(count);
        long ptrs = 0;
        for (var entry : map.entries().entrySet()) {
          StvnValue k = entry.getKey();
          int kInline = getInlineSize(k);
          if (kInline > 0) staticB += kInline;
          else {
            ptrs += 1;
            Footprint kFp = calculateFootprint(k);
            staticB += kFp.staticBytes;
            ptrs += kFp.pointerCount;
          }

          StvnValue v = entry.getValue();
          int vInline = getInlineSize(v);
          if (vInline > 0) staticB += vInline;
          else {
            ptrs += 1;
            Footprint vFp = calculateFootprint(v);
            staticB += vFp.staticBytes;
            ptrs += vFp.pointerCount;
          }
        }
        yield new Footprint(staticB, ptrs);
      }
      case StvnOption opt -> opt.value()
          .map(inner -> {
            int optInline = getInlineSize(inner);
            if (optInline > 0) {
              return new Footprint(1 + optInline, 0);
            } else {
              Footprint innerFp = calculateFootprint(inner);
              // 1 tag byte + 1 pointer to the out-of-line payload
              return new Footprint(1 + innerFp.staticBytes(), 1 + innerFp.pointerCount());
            }
          })
          .orElse(new Footprint(1, 0));
      case StvnEither either -> {
        StvnValue inner = either.value();
        int eitherInline = getInlineSize(inner);
        if (eitherInline > 0) {
          yield new Footprint(1 + eitherInline, 0);
        } else {
          Footprint innerFp = calculateFootprint(inner);
          yield new Footprint(1 + innerFp.staticBytes(), 1 + innerFp.pointerCount());
        }
      }
      case StvnUnion union -> {
        StvnValue inner = union.value();
        int unionInline = getInlineSize(inner);
        if (unionInline > 0) {
          yield new Footprint(1 + unionInline, 0);
        } else {
          Footprint innerFp = calculateFootprint(inner);
          yield new Footprint(1 + innerFp.staticBytes(), 1 + innerFp.pointerCount());
        }
      }
      case StvnBoolean b -> new Footprint(1, 0);
      case StvnEnum e -> new Footprint(calculateEnumByteSize(e), 0);
      case StvnError err -> throw new IllegalStateException("Cannot binary encode AST containing unrecovered StvnError nodes");
      default -> throw new UnsupportedOperationException("Type mapping pending: " + value.getClass());
    };

    if (isAny) {
      fp = fp.add(new Footprint(1, 0));
    }
    return fp;
  }

  private int getInlineSize(StvnValue value) {
    if (value.schema() == null) {
      throw new org.stvnadore.core.binary.exceptions.StvnSerializationException("Missing schema context for value: " + value);
    }

    if (value.schema() != null) {
      int schemaSize = StvnBinaryDecoder.getInlineSize(value.schema());
      // If the schema correctly resolved to an inline size, strictly obey it
      if (schemaSize > 0) return schemaSize;
    }

    // 2. Safely fallback for out-of-line / schemaless / mock unit test evaluations
    return switch (value) {
      case StvnBoolean ignored -> 1;
      case StvnEnum e -> calculateEnumByteSize(e);
      case StvnInteger i -> i.bitWidth() == 0
          ? 0
          : (i.bitWidth() + 7) / 8;
      case StvnFloat f -> f.precision() == FloatPrecision.FLOAT32
          ? 4
          : (f.precision() == FloatPrecision.FLOAT64
             ? 8
              : 0);
      case StvnTime t ->
          (t.kind() == TimeKind.EPOCH_S || t.kind() == TimeKind.EPOCH_MS || t.kind() == TimeKind.EPOCH_NS)
              ? 8
              : 0;
      case StvnError err -> throw new IllegalStateException("Cannot binary encode AST containing unrecovered StvnError nodes");
      default -> 0; // Strings, Collections, BigInts, Sum Types are out-of-line
    };
  }

  private void writeInlineValue(StvnValue value) {
    switch (value) {
      case StvnBoolean b -> buffer.put((byte) (b.value()
          ? 1
          : 0));
      case StvnEnum e -> {
        int byteSize = getInlineSize(e); // CRITICAL: Synchronized with Decoder
        int index = e.sequentialIndex();
        switch (byteSize) {
          case 1 -> buffer.put((byte) index);
          case 2 -> buffer.putShort((short) index);
          case 4 -> buffer.putInt(index);
        }
      }
      case StvnInteger i -> {
        int byteSize = getInlineSize(i); // CRITICAL: Synchronized with Decoder
        byte[] bigEndian = i.value().toByteArray();

        // Dynamically write exact little-endian bytes, with safe sign-extension padding
        for (int b = 0; b < byteSize; b++) {
          if (b < bigEndian.length) {
            buffer.put(bigEndian[bigEndian.length - 1 - b]);
          } else {
            buffer.put((byte) (i.value().signum() < 0
                ? 0xFF
                : 0x00));
          }
        }
      }
      case StvnFloat f -> {
        int byteSize = getInlineSize(f); // CRITICAL: Synchronized with Decoder
        // Force the float to pack perfectly into the schema's expected slot
        if (byteSize == 4) buffer.putFloat(f.value().floatValue());
        else if (byteSize == 8) buffer.putDouble(f.value().doubleValue());
      }
      case StvnTime t -> {
        long val = ((BigInteger) t.value()).longValue();
        buffer.putLong(val);
      }
      case StvnError err -> throw new IllegalStateException("Cannot binary encode AST containing unrecovered StvnError nodes");
      default -> throw new IllegalStateException("Attempted to write out-of-line type inline: " + value.getClass());
    }
  }

  private int calculateEnumByteSize(StvnEnum enumNode) {
    int variantCount = enumNode.variantCount();
    if (variantCount <= 256) return 1;
    if (variantCount <= 65536) return 2;
    return 4;
  }

  private int getDerivedLengthPrefixSize(int length) {
    if (length <= 0x3F) return 1;
    if (length <= 0x3FFF) return 2;
    if (length <= 0x3FFFFF) return 3;
    return 5;
  }

  // --- PHASE 2: BINARY WRITING ---

  private int writeValuePostOrder(StvnValue value) {
    return writeValuePostOrder(value, false);
  }

  private int writeValuePostOrder(StvnValue value, boolean forceNoAny) {
    // GUARD: Handle ANY standalone fixed-width primitive (e.g., a root Int32 or Float64)
    var inlineSize = getInlineSize(value);
    if (inlineSize > 0) {
      ensureCapacity(inlineSize);
      var startOffset = buffer.position();
      writeInlineValue(value);
      return startOffset;
    }

    if (value.schema() == null) {
      throw new org.stvnadore.core.binary.exceptions.StvnSerializationException("Missing schema context for value: " + value);
    }
    var isAny = false;
    var tag = (byte) 0;

    return switch (value) {
      case StvnString str -> writeStringOutlined(str, str.value(), isAny, tag);
      case StvnTime time -> (time.kind() == TimeKind.OFFSET || time.kind() == TimeKind.ZONED)
          ? writeStringOutlined(time, time.value().toString(), isAny, tag)
          : -1;
      case StvnDateTimeOffset dto -> writeStringOutlined(dto, dto.value().toString(), isAny, tag);
      case StvnDateTimeZoned dtz -> writeStringOutlined(dtz, dtz.localDateTime().toString() + "[" + dtz.zoneId().getId() + "]", isAny, tag);
      case StvnDateTimeAudited dta -> writeStringOutlined(dta, dta.offsetDateTime().toString() + "[" + dta.zoneId().getId() + "]", isAny, tag);
      // Zoned/Offset Fallback
      case StvnInteger bigInt -> {
        // Out-of-line BigInt (Arbitrary Precision)
        var bytes = bigInt.value().toByteArray();
        var length = bytes.length;
        if (isAny) {
          ensureCapacity(1 + 5 + length);
          var startOffset = buffer.position();
          buffer.put(tag);
          writeDerivedLengthPrefix(length);
          buffer.put(bytes);
          yield startOffset;
        } else {
          ensureCapacity(5 + length);
          var startOffset = buffer.position();
          writeDerivedLengthPrefix(length);
          buffer.put(bytes);
          yield startOffset;
        }
      }
      case StvnFloat exactFloat -> writeStringOutlined(exactFloat, exactFloat.value().toString(), isAny, tag); // EXACT fallback
      case StvnTuple tuple -> writeTuple(tuple, isAny, tag);
      case StvnSeq seq -> writeSeqOutlined(seq.elements(), isAny, tag);
      case StvnSet set -> writeSeqOutlined(set.elements().stream().toList(), isAny, tag);
      case StvnMap map -> writeMapOutlined(map, isAny, tag);
      case StvnOption opt -> writeOptionOutlined(opt, isAny, tag);
      case StvnEither either -> writeEitherOutlined(either, isAny, tag);
      case StvnUnion union -> writeUnionOutlined(union, isAny, tag);
      case StvnBoolean b -> {
        if (isAny) {
          ensureCapacity(2);
          var startOffset = buffer.position();
          buffer.put(tag);
          buffer.put((byte) (b.value() ? 1 : 0));
          yield startOffset;
        } else {
          ensureCapacity(1);
          var startOffset = buffer.position();
          buffer.put((byte) (b.value() ? 1 : 0));
          yield startOffset;
        }
      }
      case StvnEnum e -> {
        var byteSize = calculateEnumByteSize(e);
        if (isAny) {
          ensureCapacity(1 + byteSize);
          var startOffset = buffer.position();
          buffer.put(tag);
          var index = e.sequentialIndex();
          switch (byteSize) {
            case 1 -> buffer.put((byte) index);
            case 2 -> buffer.putShort((short) index);
            case 4 -> buffer.putInt(index);
          }
          yield startOffset;
        } else {
          ensureCapacity(byteSize);
          var startOffset = buffer.position();
          var index = e.sequentialIndex();
          switch (byteSize) {
            case 1 -> buffer.put((byte) index);
            case 2 -> buffer.putShort((short) index);
            case 4 -> buffer.putInt(index);
          }
          yield startOffset;
        }
      }
      case StvnError err -> throw new IllegalStateException("Cannot binary encode AST containing unrecovered StvnError nodes");
      default -> -1;
    };
  }

  private int writeStringOutlined(StvnValue sourceNode, String fallbackStr, boolean isAny, byte tag) {
    // 1. Safe cast to extract string metadata (Style and Tag)
    if (!(sourceNode instanceof StvnString strNode)) {
      // Failsafe: If the node isn't a string, fallback to a SIMPLE style string
      var utf8Bytes = fallbackStr.getBytes(StandardCharsets.UTF_8);
      var totalLength = 1 + utf8Bytes.length; // 1 byte for style + text

      ensureCapacity((isAny ? 1 : 0) + 5 + totalLength);
      var startOffset = buffer.position();
      if (isAny) {
        buffer.put(tag);
      }
      writeDerivedLengthPrefix(totalLength);

      buffer.put((byte) StvnValue.StringStyle.SIMPLE.ordinal());
      buffer.put(utf8Bytes);
      return startOffset;
    }

    // 2. Extract the raw text bytes (Querying your cache first!)
    var textBytes = stringCache.get(strNode);
    if (textBytes == null) {
      textBytes = fallbackStr.getBytes(StandardCharsets.UTF_8);
    }

    var styleByte = (byte) strNode.style().ordinal();
    int startOffset;

    // 3. Pack the data based on the string style
    if (strNode.style() == StvnValue.StringStyle.FENCED) {
      var tagBytes = strNode.fenceTag()
          .map(fTag -> fTag.getBytes(StandardCharsets.UTF_8))
          .orElseGet(() -> "TAG".getBytes(StandardCharsets.UTF_8));

      // Total = 1 (style) + 1 (tag length) + tag bytes + text bytes
      var totalLength = 1 + 1 + tagBytes.length + textBytes.length;

      ensureCapacity((isAny ? 1 : 0) + 5 + totalLength);
      startOffset = buffer.position();
      if (isAny) {
        buffer.put(tag);
      }
      writeDerivedLengthPrefix(totalLength);

      buffer.put(styleByte);
      buffer.put((byte) tagBytes.length);
      buffer.put(tagBytes);
      buffer.put(textBytes);

    } else {
      // Total = 1 (style) + text bytes
      var totalLength = 1 + textBytes.length;

      ensureCapacity((isAny ? 1 : 0) + 5 + totalLength);
      startOffset = buffer.position();
      if (isAny) {
        buffer.put(tag);
      }
      writeDerivedLengthPrefix(totalLength);

      buffer.put(styleByte);
      buffer.put(textBytes);
    }

    return startOffset;
  }

  private int writeTuple(StvnTuple tuple, boolean isAny, byte tag) {
    var elements = tuple.elements();
    var elementCount = elements.size();

    var outOfLineOffsets = new int[elementCount];
    var tableSize = 0;

    for (var i = 0; i < elementCount; i++) {
      var el = elements.get(i);
      var inlineSize = getInlineSize(el);
      if (inlineSize == 0) {
        outOfLineOffsets[i] = writeValuePostOrder(el);
        tableSize += this.offsetSize;
      } else {
        tableSize += inlineSize;
      }
    }

    ensureCapacity((isAny ? 1 : 0) + tableSize);
    var tupleStartOffset = buffer.position();
    if (isAny) {
      buffer.put(tag);
    }

    for (var i = 0; i < elementCount; i++) {
      var el = elements.get(i);
      var inlineSize = getInlineSize(el);
      if (inlineSize == 0) {
        writeOffsetPointer(outOfLineOffsets[i]);
      } else {
        writeInlineValue(el);
      }
    }
    return tupleStartOffset;
  }

  private int writeSeqOutlined(java.util.List<StvnValue> elements, boolean isAny, byte tag) {
    var count = elements.size();
    if (count == 0) {
      ensureCapacity((isAny ? 1 : 0) + 5);
      var emptyOffset = buffer.position();
      if (isAny) {
        buffer.put(tag);
      }
      writeDerivedLengthPrefix(0);
      return emptyOffset;
    }

    var outOfLineOffsets = new int[count];
    var arrayBlockSize = 0;

    for (var i = 0; i < count; i++) {
      var el = elements.get(i);
      var inlineSize = getInlineSize(el);
      if (inlineSize == 0) {
        outOfLineOffsets[i] = writeValuePostOrder(el);
        arrayBlockSize += this.offsetSize;
      } else {
        arrayBlockSize += inlineSize;
      }
    }

    var prefixBytes = getDerivedLengthPrefixSize(count);
    ensureCapacity((isAny ? 1 : 0) + prefixBytes + arrayBlockSize);
    var seqStartOffset = buffer.position();
    if (isAny) {
      buffer.put(tag);
    }
    writeDerivedLengthPrefix(count);

    for (var i = 0; i < count; i++) {
      var el = elements.get(i);
      var inlineSize = getInlineSize(el);
      if (inlineSize == 0) writeOffsetPointer(outOfLineOffsets[i]);
      else writeInlineValue(el);
    }
    return seqStartOffset;
  }

  private int writeMapOutlined(StvnMap map, boolean isAny, byte tag) {
    var entries = map.entries();
    var count = entries.size();

    if (count == 0) {
      ensureCapacity((isAny ? 1 : 0) + 5);
      var emptyOffset = buffer.position();
      if (isAny) {
        buffer.put(tag);
      }
      writeDerivedLengthPrefix(0);
      return emptyOffset;
    }

    var outOfLineKeyOffsets = new int[count];
    var outOfLineValOffsets = new int[count];
    var keyArrayBlockSize = 0;
    var valArrayBlockSize = 0;

    var i = 0;
    for (var entry : entries.entrySet()) {
      var k = entry.getKey();
      var kInline = getInlineSize(k);
      if (kInline == 0) {
        outOfLineKeyOffsets[i] = writeValuePostOrder(k, true);
        keyArrayBlockSize += this.offsetSize;
      } else keyArrayBlockSize += kInline;

      var v = entry.getValue();
      var vInline = getInlineSize(v);
      if (vInline == 0) {
        outOfLineValOffsets[i] = writeValuePostOrder(v);
        valArrayBlockSize += this.offsetSize;
      } else valArrayBlockSize += vInline;
      i++;
    }

    var prefixBytes = getDerivedLengthPrefixSize(count);
    ensureCapacity((isAny ? 1 : 0) + prefixBytes + keyArrayBlockSize + valArrayBlockSize);
    var mapStartOffset = buffer.position();
    if (isAny) {
      buffer.put(tag);
    }

    writeDerivedLengthPrefix(count);

    i = 0;
    for (var k : entries.keySet()) {
      if (getInlineSize(k) == 0) writeOffsetPointer(outOfLineKeyOffsets[i]);
      else writeInlineValue(k);
      i++;
    }

    i = 0;
    for (var v : entries.values()) {
      if (getInlineSize(v) == 0) writeOffsetPointer(outOfLineValOffsets[i]);
      else writeInlineValue(v);
      i++;
    }

    return mapStartOffset;
  }

  private int writeOptionOutlined(StvnOption opt, boolean isAny, byte tag) {
    return opt.value()
        .map(inner -> {
          var inlineSize = getInlineSize(inner);
          if (inlineSize == 0) {
            var innerOffset = writeValuePostOrder(inner);
            ensureCapacity((isAny ? 1 : 0) + 1 + this.offsetSize);
            var startOffset = buffer.position();
            if (isAny) {
              buffer.put(tag);
            }
            buffer.put((byte) 1);
            writeOffsetPointer(innerOffset);
            return startOffset;
          } else {
            ensureCapacity((isAny ? 1 : 0) + 1 + inlineSize);
            var startOffset = buffer.position();
            if (isAny) {
              buffer.put(tag);
            }
            buffer.put((byte) 1);
            writeInlineValue(inner);
            return startOffset;
          }
        })
        .orElseGet(() -> {
          ensureCapacity((isAny ? 1 : 0) + 1);
          var startOffset = buffer.position();
          if (isAny) {
            buffer.put(tag);
          }
          buffer.put((byte) 0);
          return startOffset;
        });
  }

  private int writeEitherOutlined(StvnEither either, boolean isAny, byte tag) {
    var inner = either.value();
    var inlineSize = getInlineSize(inner);
    var activeTag = (byte) (either.isRight()
        ? 1
        : 0);

    if (inlineSize == 0) {
      var innerOffset = writeValuePostOrder(inner);
      ensureCapacity((isAny ? 1 : 0) + 1 + this.offsetSize);
      var startOffset = buffer.position();
      if (isAny) {
        buffer.put(tag);
      }
      buffer.put(activeTag);
      writeOffsetPointer(innerOffset);
      return startOffset;
    } else {
      ensureCapacity((isAny ? 1 : 0) + 1 + inlineSize);
      var startOffset = buffer.position();
      if (isAny) {
        buffer.put(tag);
      }
      buffer.put(activeTag);
      writeInlineValue(inner);
      return startOffset;
    }
  }

  private int writeUnionOutlined(StvnUnion union, boolean isAny, byte tag) {
    var inner = union.value();
    var inlineSize = getInlineSize(inner);
    var activeTag = (byte) union.tagIndex();

    if (inlineSize == 0) {
      var innerOffset = writeValuePostOrder(inner);
      ensureCapacity((isAny ? 1 : 0) + 1 + this.offsetSize);
      var startOffset = buffer.position();
      if (isAny) {
        buffer.put(tag);
      }
      buffer.put(activeTag);
      writeOffsetPointer(innerOffset);
      return startOffset;
    } else {
      ensureCapacity((isAny ? 1 : 0) + 1 + inlineSize);
      var startOffset = buffer.position();
      if (isAny) {
        buffer.put(tag);
      }
      buffer.put(activeTag);
      writeInlineValue(inner);
      return startOffset;
    }
  }

  // --- BUFFER UTILITIES ---

  private void writeDerivedLengthPrefix(int length) {
    if (length < 0) throw new IllegalArgumentException("Length cannot be negative");
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
      // 4 additional bytes. (Upper bits are always 0 for Java's 32-bit int max length)
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
    }
  }

  private void writeHeader(int rootOffset) {
    int finalPos = buffer.position();
    buffer.position(0);
    buffer.putInt(MAGIC_BYTES);

    int identityCode = (identityStrategy != null) ? identityStrategy.code() : 0x00;
    byte controlByte = (byte) (((encodingStrategy.code() & 0x0F) << 4) | (identityCode & 0x0F));
    buffer.put(controlByte);

    if (identityStrategy != null) {
      switch (identityStrategy) {
        case SchemaIdentityStrategy.UniversalDefault ignored -> {}
        case SchemaIdentityStrategy.UuidV8Hash ignored -> {}
        case SchemaIdentityStrategy.Sha256Hash ignored -> {}
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
        case SchemaIdentityStrategy.UniversalVersion uv -> {
          buffer.putInt((int) (uv.version() - 1));
        }
        case SchemaIdentityStrategy.ExplicitUuid explicit -> {
          buffer.putLong(explicit.uuid().getMostSignificantBits());
          buffer.putLong(explicit.uuid().getLeastSignificantBits());
        }
        case SchemaIdentityStrategy.ExplicitSha256 explicit -> {
          buffer.put(explicit.hash());
        }
        case SchemaIdentityStrategy.SelfDescribingSchema self -> {
          byte[] bytes = self.stvnInclfContent().getBytes(StandardCharsets.UTF_8);
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
      default -> throw new IllegalStateException();
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

  private byte getTagForValue(StvnValue value) {
    return (byte) (switch (value) {
      case StvnString ignored -> 0;
      case StvnInteger ignored -> 1;
      case StvnFloat ignored -> 2;
      case StvnBoolean ignored -> 3;
      case StvnSeq ignored -> 4;
      case StvnMap ignored -> 5;
      case StvnSet ignored -> 6;
      case StvnTuple ignored -> 7;
      case StvnTime ignored -> 8;
      case StvnDateTimeOffset ignored -> 8;
      case StvnDateTimeZoned ignored -> 8;
      case StvnDateTimeAudited ignored -> 8;
      case StvnEnum ignored -> 9;
      case StvnOption ignored -> 10;
      case StvnEither ignored -> 11;
      case StvnUnion ignored -> 12;
      case StvnError err -> throw new IllegalStateException("Cannot binary encode AST containing unrecovered StvnError nodes");
    });
  }
}
