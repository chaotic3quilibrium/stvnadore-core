package org.stvnadore.core.binary;

import org.jspecify.annotations.Nullable;
import org.stvnadore.core.binary.exceptions.PoisonedRegistryPayloadException;
import org.stvnadore.core.binary.exceptions.StvnCorruptedBitPatternException;
import org.stvnadore.core.binary.exceptions.StvnSerializationException;
import org.stvnadore.core.binary.exceptions.StvnVersionException;
import org.stvnadore.core.binary.exceptions.UnsupportedEncodingStrategyException;
import org.stvnadore.core.binary.readers.StvnMapReader;
import org.stvnadore.core.binary.readers.StvnSeqReader;
import org.stvnadore.core.binary.readers.StvnTupleReader;
import org.stvnadore.core.ir.StvnLiteralParser;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.validation.StvnTypeResolver;
import org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Decoder engine for parsing STVN binary buffers back into AST values or accessing them via zero-copy flyweights.
 * <p>
 * Evaluates binary magic headers, extracts offset pointer sizes, decodes schema strategy identities, and builds target
 * representations.
 *
 * <h2>Unsigned 1-Based Length Format</h2>
 * In STVN binary formats, all variable-length fields (like strings, sequences, and maps) store their sizes using an
 * unsigned 1-based format ({@code length = value + 1}). A stored byte value of {@code 0} indicates a length of
 * {@code 1} element. This design choice optimizes space efficiency and protects against buffer overflow exploits.
 *
 * <h2>Buffer Safety Bounds</h2>
 * The decoder enforces safety checks at byte array and stream boundaries. Because Java arrays use signed 32-bit
 * integers for indexing, any decoded unsigned lengths are validated against {@link Integer#MAX_VALUE} to prevent index
 * wrapping or memory allocation exceptions on signed/unsigned integer mismatches.
 *
 * @since 1.0.0
 */
public class StvnBinaryDecoder {

  private StvnBinaryDecoder() {
    // Utility class, non-instantiable
  }

  private static final int MAGIC_BYTES = 0x5354564E; // "STVN"

  // ===========================================================================
  // 1. INTERFACES & CONTEXT RECORDS
  // ===========================================================================

  /**
   * Registry contract for looking up resolved schemas by their strategy identifier.
   */
  @FunctionalInterface
  public interface StvnSchemaRegistry {
    /**
     * Looks up a schema by its identity strategy.
     *
     * @param strategy the strategy to look up
     * @return the resolved schema, or {@code null} if unknown
     */
    @Nullable ResolvedSchema lookup(SchemaIdentityStrategy strategy);
  }

  /**
   * Tracks buffer boundaries, pointer offset sizes, and schema/encoding strategy context during decode operations.
   *
   * @param buffer           the raw byte buffer being decoded
   * @param offsetSize       the pointer offset size (1, 2, or 4 bytes)
   * @param identityStrategy the active schema strategy, if any
   * @param encodingStrategy the active binary encoding strategy
   */
  public record DecodeContext(
      ByteBuffer buffer,
      int offsetSize,
      Optional<SchemaIdentityStrategy> identityStrategy,
      BinaryEncodingStrategy encodingStrategy
  ) {
    /**
     * Constructs a DecodeContext defaulting to {@link BinaryEncodingStrategy#ZERO_COPY_POST_ORDER}.
     *
     * @param buffer           the raw byte buffer
     * @param offsetSize       the pointer offset size
     * @param identityStrategy the active schema strategy, if any
     */
    public DecodeContext(ByteBuffer buffer, int offsetSize, Optional<SchemaIdentityStrategy> identityStrategy) {
      this(buffer, offsetSize, identityStrategy, BinaryEncodingStrategy.ZERO_COPY_POST_ORDER);
    }

    /**
     * Reads a pointer address from the specified absolute offset in the buffer.
     *
     * @param absoluteOffset the address containing the pointer
     * @return the dereferenced absolute address
     * @throws IllegalStateException if the offset size configuration is invalid
     */
    public int readPointer(int absoluteOffset) {
      return switch (offsetSize) {
        case 1 -> Byte.toUnsignedInt(buffer.get(absoluteOffset));
        case 2 -> Short.toUnsignedInt(buffer.getShort(absoluteOffset));
        case 4 -> buffer.getInt(absoluteOffset);
        case 8 -> (int) buffer.getLong(absoluteOffset); // Cast to int assuming standard 2GB max ByteBuffer
        default -> throw new IllegalStateException("Invalid offset size: " + offsetSize);
      };
    }
  }

  /**
   * Represents the root reference pointing to a parsed STVN binary structure.
   *
   * @param context    the decoding context containing the buffer and offset sizes
   * @param rootOffset the absolute buffer address of the root element
   * @param schema     the resolved schema configuration for the root element, if available
   */
  public record RootPointer(
      DecodeContext context,
      int rootOffset,
      Optional<ResolvedSchema> schema
  ) {
  }

  private record HeaderInfo(
      int offsetSize,
      @Nullable SchemaIdentityStrategy identityStrategy,
      BinaryEncodingStrategy encodingStrategy,
      int payloadStart
  ) {
  }

  // ===========================================================================
  // 2. HEADER PARSING & INITIALIZATION
  // ===========================================================================

  private static HeaderInfo parseHeader(ByteBuffer buffer) {
    buffer.order(ByteOrder.LITTLE_ENDIAN);
    if (buffer.getInt(0) != MAGIC_BYTES) {
      throw new IllegalArgumentException("Invalid STVN binary: Magic bytes mismatch");
    }

    byte controlByte = buffer.get(4);
    int upperNibble = (controlByte >>> 4) & 0x0F;
    int lowerNibble = controlByte & 0x0F;

    BinaryEncodingStrategy encodingStrategy = BinaryEncodingStrategy.fromCode(upperNibble);

    int currentPos = 5;
    SchemaIdentityStrategy strategy = switch (lowerNibble) {
      case 0 -> new SchemaIdentityStrategy.UniversalDefault();
      case 1 -> new SchemaIdentityStrategy.UuidV8Hash();
      case 2 -> new SchemaIdentityStrategy.Sha256Hash();
      case 3 -> {
        int len = Short.toUnsignedInt(buffer.getShort(currentPos)) + 1;
        currentPos += 2;
        byte[] bytes = new byte[len];
        buffer.get(currentPos, bytes);
        currentPos += len;
        yield new SchemaIdentityStrategy.AsciiStringKey(new String(bytes, StandardCharsets.US_ASCII));
      }
      case 4 -> {
        int len = Short.toUnsignedInt(buffer.getShort(currentPos)) + 1;
        currentPos += 2;
        byte[] bytes = new byte[len];
        buffer.get(currentPos, bytes);
        currentPos += len;
        yield new SchemaIdentityStrategy.UnicodeStringKey(new String(bytes, StandardCharsets.UTF_8));
      }
      case 5 -> {
        long version = Integer.toUnsignedLong(buffer.getInt(currentPos)) + 1;
        currentPos += 4;
        yield new SchemaIdentityStrategy.UniversalVersion(version);
      }
      case 6 -> {
        long mostSig = buffer.getLong(currentPos);
        long leastSig = buffer.getLong(currentPos + 8);
        currentPos += 16;
        yield new SchemaIdentityStrategy.ExplicitUuid(new java.util.UUID(mostSig, leastSig));
      }
      case 7 -> {
        byte[] hash = new byte[32];
        buffer.get(currentPos, hash);
        currentPos += 32;
        yield new SchemaIdentityStrategy.ExplicitSha256(hash);
      }
      case 8 -> {
        long lenLong = Integer.toUnsignedLong(buffer.getInt(currentPos)) + 1;
        int len = (int) lenLong;
        currentPos += 4;
        byte[] bytes = new byte[len];
        buffer.get(currentPos, bytes);
        currentPos += len;
        yield new SchemaIdentityStrategy.SelfDescribingSchema(new String(bytes, StandardCharsets.UTF_8));
      }
      default -> throw new StvnSerializationException("Invalid Schema Identity Strategy code: " + lowerNibble);
    };

    byte flags = buffer.get(currentPos++);
    int offsetSize = 1 << (flags & 0b0000_0011);

    return new HeaderInfo(offsetSize, strategy, encodingStrategy, currentPos);
  }

  private static RootPointer createRootPointer(ByteBuffer buffer, HeaderInfo header, @Nullable ResolvedSchema schema) {
    DecodeContext ctx = new DecodeContext(buffer, header.offsetSize, Optional.ofNullable(header.identityStrategy()), header.encodingStrategy());
    int rootOffset = ctx.readPointer(header.payloadStart());
    return new RootPointer(ctx, rootOffset, Optional.ofNullable(schema));
  }

  // ===========================================================================
  // 3. ENTRY POINTS
  // ===========================================================================

  /**
   * CONTRACT-FIRST Entry Point (Strict Mode): Opens the STVN binary buffer, parsing its header and validating that the
   * embedded schema identity strategy matches the expected strategy exactly.
   *
   * @param buffer           the raw little-endian byte buffer to parse
   * @param expectedStrategy the exact strategy the binary is expected to match
   * @return a {@link RootPointer} pointing to the root layout offset
   * @throws StvnVersionException if the strategy is missing or does not match
   */
  public static RootPointer openStrict(ByteBuffer buffer, SchemaIdentityStrategy expectedStrategy) {
    HeaderInfo header = parseHeader(buffer);

    if (header.identityStrategy() == null) {
      throw new StvnVersionException("Strict mode: Binary is schemaless, but " + expectedStrategy + " was expected.", expectedStrategy, null);
    }
    if (!expectedStrategy.equals(header.identityStrategy())) {
      throw new StvnVersionException("Strict mode: Identity strategy mismatch. Expected " + expectedStrategy + ", found " + header.identityStrategy(), expectedStrategy, header.identityStrategy());
    }

    return createRootPointer(buffer, header, null);
  }

  /**
   * DISCOVERY-FIRST Entry Point (General Mode): Opens the STVN binary buffer, resolving its schema dynamically by
   * consulting the provided registry, checking inline self-describing definitions, and falling back to
   * default/universal schemas if necessary.
   *
   * @param buffer            the raw little-endian byte buffer to parse
   * @param registry          the schema registry used to lookup strategies, or {@code null}
   * @param defaultFallback   the fallback schema used if lookup fails, or {@code null}
   * @param universalFallback the universal fallback schema to use, or {@code null}
   * @return a {@link RootPointer} containing the resolved schema and root layout offset
   */
  public static RootPointer open(
      ByteBuffer buffer,
      @Nullable StvnSchemaRegistry registry,
      @Nullable ResolvedSchema defaultFallback,
      @Nullable ResolvedSchema universalFallback
  ) {
    HeaderInfo header = parseHeader(buffer);
    ResolvedSchema resolved = null;

    // 1. Strategy Discovery via Registry
    if (header.identityStrategy() != null && registry != null) {
      resolved = registry.lookup(header.identityStrategy());
    }

    // 2. Self-Describing Check integration
    if (resolved == null && header.identityStrategy() instanceof SchemaIdentityStrategy.SelfDescribingSchema self) {
      resolved = compileInlineSchema(self.stvnInclfContent());
    }

    // 3. Fallbacks
    if (resolved == null) {
      resolved = (defaultFallback != null)
          ? defaultFallback
          : universalFallback;
    }

    if (resolved != null && header.identityStrategy() != null) {
      validateSchemaHash(resolved, header.identityStrategy());
    }

    return createRootPointer(buffer, header, resolved);
  }

  /**
   * CONVENIENCE Entry Point: Opens a schemaless or simple STVN binary buffer where no registry or fallback schemas are
   * needed.
   *
   * @param buffer the raw little-endian byte buffer to parse
   * @return a {@link RootPointer} pointing to the root layout offset
   */
  public static RootPointer open(ByteBuffer buffer) {
    return open(buffer, null, null, null);
  }

  /**
   * EAGER UNPACKER: Recursively parses the binary buffer and reconstructs the full, immutable STVN IR {@link StvnValue}
   * AST.
   *
   * @param root   the root pointer reference containing the buffer and offset configuration
   * @param schema an optional explicit schema to use for unpacking, overriding the root schema
   * @return the fully reconstructed root {@link StvnValue} AST
   * @throws StvnSerializationException if no schema context can be resolved, or if validation fails
   */
  @SuppressWarnings({"ConstantConditions", "DataFlowIssue"})
  public static StvnValue unpack(RootPointer root, Optional<ResolvedSchema> schema) {
    ResolvedSchema effectiveSchema = schema
        .or(root::schema)
        .orElseThrow(() -> new StvnSerializationException("Cannot unpack STVN binary payload: No schema provided or discovered."));

    root.context().identityStrategy().ifPresent(strategy -> validateSchemaHash(effectiveSchema, strategy));

    int inlineSize = getInlineSize(effectiveSchema);
    if (inlineSize > 0) {
      // Root is a standalone primitive
      return readInlineNode(root.context(), root.rootOffset(), effectiveSchema);
    } else {
      // Root is out-of-line
      return readOutlinedNode(root.context(), root.rootOffset(), effectiveSchema);
    }
  }

// ===========================================================================
  // 4. ZERO-COPY FLYWEIGHT READERS
  // ===========================================================================

  // --- 1-Arg Variants (Uses the embedded schema from Discovery/Registry) ---

  /**
   * Opens a zero-copy tuple reader on the root of the parsed binary structure using the schema resolved in the root
   * pointer.
   *
   * @param root the root pointer reference
   * @return a zero-copy {@link StvnTupleReader}
   */
  public static StvnTupleReader readRootTuple(RootPointer root) {
    return readRootTuple(root, root.schema());
  }

  /**
   * Opens a zero-copy sequence reader on the root of the parsed binary structure using the schema resolved in the root
   * pointer.
   *
   * @param root the root pointer reference
   * @return a zero-copy {@link StvnSeqReader}
   */
  public static StvnSeqReader readRootSeq(RootPointer root) {
    return readRootSeq(root, root.schema());
  }

  /**
   * Opens a zero-copy map reader on the root of the parsed binary structure using the schema resolved in the root
   * pointer.
   *
   * @param root the root pointer reference
   * @return a zero-copy {@link StvnMapReader}
   */
  public static StvnMapReader readRootMap(RootPointer root) {
    return readRootMap(root, root.schema());
  }

  // --- 2-Arg Variants (Allows explicit schema injection for Legacy/Tests) ---

  /**
   * Opens a zero-copy tuple reader on the root of the parsed binary structure, overriding the embedded schema with an
   * explicit configuration.
   *
   * @param root           the root pointer reference
   * @param explicitSchema an optional explicit schema to use for reading
   * @return a zero-copy {@link StvnTupleReader}
   * @throws StvnSerializationException if no schema context can be resolved
   */
  public static StvnTupleReader readRootTuple(RootPointer root, Optional<ResolvedSchema> explicitSchema) {
    ResolvedSchema targetSchema = explicitSchema
        .or(root::schema)
        .orElseThrow(() -> new StvnSerializationException("Cannot read tuple: No schema context resolved."));
    return new StvnTupleReader(root.context(), root.rootOffset(), targetSchema);
  }

  /**
   * Opens a zero-copy sequence reader on the root of the parsed binary structure, overriding the embedded schema with
   * an explicit configuration.
   *
   * @param root           the root pointer reference
   * @param explicitSchema an optional explicit schema to use for reading
   * @return a zero-copy {@link StvnSeqReader}
   * @throws StvnSerializationException if no schema context can be resolved
   */
  public static StvnSeqReader readRootSeq(RootPointer root, Optional<ResolvedSchema> explicitSchema) {
    ResolvedSchema targetSchema = explicitSchema
        .or(root::schema)
        .orElseThrow(() -> new StvnSerializationException("Cannot read seq: No schema context resolved."));
    return new StvnSeqReader(root.context(), root.rootOffset(), targetSchema);
  }

  /**
   * Opens a zero-copy map reader on the root of the parsed binary structure, overriding the embedded schema with an
   * explicit configuration.
   *
   * @param root           the root pointer reference
   * @param explicitSchema an optional explicit schema to use for reading
   * @return a zero-copy {@link StvnMapReader}
   * @throws StvnSerializationException if no schema context can be resolved
   */
  public static StvnMapReader readRootMap(RootPointer root, Optional<ResolvedSchema> explicitSchema) {
    ResolvedSchema targetSchema = explicitSchema
        .or(root::schema)
        .orElseThrow(() -> new StvnSerializationException("Cannot read map: No schema context resolved."));
    return new StvnMapReader(root.context(), root.rootOffset(), targetSchema);
  }

// ===========================================================================
  // 5. UTILITY READERS & HELPERS
  // ===========================================================================

  /**
   * Follows a pointer at the given slot, reads the derived length prefix, and extracts the UTF-8 string without
   * creating any STVN IR nodes.
   *
   * @param ctx    the decode context containing the buffer
   * @param offset the starting memory offset of the outlined pointer
   * @return the extracted Java String
   */
  public static String readStringOutlined(DecodeContext ctx, int offset) {
    // 1. Read the variable-length prefix
    LengthResult lr = readDerivedLengthPrefix(ctx.buffer(), offset);
    int totalLength = lr.length();
    int dataStartOffset = offset + lr.bytesConsumed();

    // 2. Read the new Style byte
    byte styleByte = ctx.buffer().get(dataStartOffset);

    int payloadOffset = dataStartOffset + 1;
    int payloadLength = totalLength - 1;

    // 3. If it's FENCED (ordinal 2), we must skip over the Tag metadata
    if (styleByte == 2) {
      int tagLen = Byte.toUnsignedInt(ctx.buffer().get(payloadOffset));

      // Advance past the tag length byte and the tag string itself
      payloadOffset += (1 + tagLen);
      payloadLength -= (1 + tagLen);
    }

    // 4. Read and return the pure string text
    byte[] textBytes = new byte[payloadLength];
    // Use absolute get() to prevent mutating global buffer state
    ctx.buffer().get(payloadOffset, textBytes);

    return new String(textBytes, StandardCharsets.UTF_8);
  }

  /**
   * Container carrying the parsed length value and the count of bytes consumed to read it.
   *
   * @param length        the parsed length value
   * @param bytesConsumed the count of bytes consumed (1, 2, 3, or 5 bytes)
   */
  public record LengthResult(int length, int bytesConsumed) {
  }

  /**
   * Reads a variable-length prefix byte stream and returns the decoded length and consumed byte count.
   *
   * @param buffer the buffer containing the prefix bytes
   * @param offset the absolute starting memory offset
   * @return the decoded {@link LengthResult}
   */
  public static LengthResult readDerivedLengthPrefix(ByteBuffer buffer, int offset) {
    int b = Byte.toUnsignedInt(buffer.get(offset));
    if ((b & 0xC0) == 0) { // 00xx xxxx (<= 0x3F)
      return new LengthResult(b, 1);
    } else if ((b & 0xC0) == 0x40) { // 01xx xxxx (<= 0x3FFF)
      int length = ((b & 0x3F) << 8) | Byte.toUnsignedInt(buffer.get(offset + 1));
      return new LengthResult(length, 2);
    } else if ((b & 0xC0) == 0x80) { // 10xx xxxx (<= 0x3FFFFF)
      int length = ((b & 0x3F) << 16) | (Byte.toUnsignedInt(buffer.get(offset + 1)) << 8) | Byte.toUnsignedInt(buffer.get(offset + 2));
      return new LengthResult(length, 3);
    } else { // 1100 0000 (0xC0)
      int length = buffer.getInt(offset + 1);
      return new LengthResult(length, 5);
    }
  }

  /**
   * Extracts the nested schemas (e.g., Key and Value schemas for a Map, or Tuple elements) directly from a parent
   * schema's AST definition.
   *
   * @param schema the parent schema context to inspect, or {@code null}
   * @return a list of resolved nested child schemas
   */
  public static List<ResolvedSchema> extractChildSchemas(@Nullable ResolvedSchema schema) {
    if (schema == null || schema.node() == null) return List.of();

    org.stvnadore.core.parser.StvnParser.StvnDocumentContext doc = null;
    org.antlr.v4.runtime.tree.ParseTree current = schema.node();
    while (current != null) {
      if (current instanceof org.stvnadore.core.parser.StvnParser.StvnDocumentContext ctx) {
        doc = ctx;
        break;
      }
      current = current.getParent();
    }

    List<ResolvedSchema> children = new java.util.ArrayList<>();
    var innerNodes = StvnTypeResolver.getInnerSchemas(schema.node());
    for (var c : innerNodes) {
      StvnTypeResolver.resolvePrimitiveSchema(doc, c, java.util.Set.of())
          .ifPresent(children::add);
    }
    return children;
  }

  /**
   * Returns the fixed byte size of a primitive type when stored inline.
   *
   * @param schema the resolved schema to check
   * @return the inline footprint size in bytes, or {@code 0} if it is a reference type
   */
  public static int getInlineSize(@Nullable ResolvedSchema schema) {
    if (schema == null || schema.node() == null) return 0;
    String baseType = StvnTypeResolver.getPrimitiveBaseType(schema.node());

    return switch (baseType) {
      case null -> 0;
      case ":Boolean" -> 1;
      case ":Float32" -> 4;
      case ":Float64", ":TimeEpochS", ":TimeEpochMs", ":TimeEpochNs" -> 8;
      case String s when s.startsWith(":Int") || s.startsWith(":Uint") -> {
        String w = s.replaceAll("[^0-9]", "");
        yield w.isEmpty()
            ? 4
            : (Integer.parseInt(w) + 7) / 8;
      }
      case String s when s.startsWith(":Enum") -> {
        int variants = countEnumVariants(schema.node());
        if (variants <= 256) yield 1;
        if (variants <= 65536) yield 2;
        yield 4;
      }
      default -> 0; // Out-of-line types
    };
  }

  private static int countEnumVariants(org.stvnadore.core.parser.StvnParser.SchemaTypeContext node) {
    if (node == null) return 1;
    int count = 0;
    if (node.schemaConstructor() != null && node.schemaConstructor().sumType() != null) {
      var sumType = node.schemaConstructor().sumType();
      if (sumType.enumDef() != null) {
        count = sumType.enumDef().valueKeyword().size();
      }
    }
    return Math.max(1, count);
  }

  // ===========================================================================
  // 6. EAGER UNPACKER IMPLEMENTATION
  // ===========================================================================

  @SuppressWarnings({"ConstantConditions", "DataFlowIssue"})
  private static StvnValue readInlineNode(DecodeContext ctx, int offset, ResolvedSchema schema) {
    String baseType = StvnTypeResolver.getPrimitiveBaseType(schema.node());
    // PATHWAY B: Wire safeguard to verify primitive base type resolves correctly before inline decoding
    if (baseType == null)
      throw new org.stvnadore.core.binary.exceptions.StvnSerializationException("Unknown inline schema");

    return switch (baseType) {
      case ":Boolean" -> new StvnValue.StvnBoolean(schema, ctx.buffer().get(offset) != 0);
      case ":Float32" -> new StvnValue.StvnFloat(schema, ctx.buffer().getFloat(offset));
      case ":Float64" -> new StvnValue.StvnFloat(schema, ctx.buffer().getDouble(offset));
      case ":TimeEpochS", ":TimeEpochMs", ":TimeEpochNs" -> {
        long epochVal = ctx.buffer().getLong(offset);
        // Treat as 64-bit unsigned
        java.math.BigInteger unsignedVal = new java.math.BigInteger(1, java.nio.ByteBuffer.allocate(8).putLong(epochVal).array());
        StvnValue.TimeKind kind = switch (baseType) {
          case ":TimeEpochS" -> StvnValue.TimeKind.EPOCH_S;
          case ":TimeEpochMs" -> StvnValue.TimeKind.EPOCH_MS;
          case ":TimeEpochNs" -> StvnValue.TimeKind.EPOCH_NS;
          default -> throw new IllegalStateException();
        };
        yield new StvnValue.StvnTime(schema, unsignedVal, kind);
      }
      case String s when s.startsWith(":Int") || s.startsWith(":Uint") -> {
        int size = getInlineSize(schema);

        // 1. Read exact little-endian footprint
        byte[] littleEndian = new byte[size];
        for (int i = 0; i < size; i++) {
          littleEndian[i] = ctx.buffer().get(offset + i);
        }

        // 2. Reverse to standard Big-Endian for Java's BigInteger
        byte[] bigEndian = new byte[size];
        for (int i = 0; i < size; i++) {
          bigEndian[i] = littleEndian[size - 1 - i];
        }

        boolean isUnsigned = s.startsWith(":Uint");
        String widthStr = s.replaceAll("[^0-9]", "");
        int bitWidth = widthStr.isEmpty()
            ? (size * 8)
            : Integer.parseInt(widthStr);

        // High-bit mask verification for arbitrary bit-widths (n mod 8 != 0)
        validateHighBitMask(bigEndian[0], bitWidth, isUnsigned);

        // 3. Prevent unsigned high-bit (e.g., 255 in Uint8) from being parsed as a negative
        java.math.BigInteger val;
        if (isUnsigned && (bigEndian[0] & 0x80) != 0) {
          byte[] uBigEndian = new byte[size + 1];
          uBigEndian[0] = 0x00;
          System.arraycopy(bigEndian, 0, uBigEndian, 1, size);
          val = new java.math.BigInteger(uBigEndian);
        } else {
          val = new java.math.BigInteger(bigEndian);
        }

        yield new StvnValue.StvnInteger(schema, val, bitWidth, isUnsigned);
      }
      case String s when s.startsWith(":Enum") -> {
        int size = getInlineSize(schema);
        int seqIndex = switch (size) {
          case 1 -> Byte.toUnsignedInt(ctx.buffer().get(offset));
          case 2 -> Short.toUnsignedInt(ctx.buffer().getShort(offset));
          case 4 -> ctx.buffer().getInt(offset);
          default -> throw new IllegalStateException();
        };
        String kw = "UNKNOWN";
        int count = -1;
        if (schema != null && schema.node() != null && schema.node().schemaConstructor() != null && schema.node().schemaConstructor().sumType() != null) {
          var enumDef = schema.node().schemaConstructor().sumType().enumDef();
          if (enumDef != null) {
            count = enumDef.valueKeyword().size();
            if (seqIndex >= 0 && seqIndex < count) {
              kw = enumDef.valueKeyword(seqIndex).getText();
            }
          }
        }
        yield new StvnValue.StvnEnum(schema, kw, seqIndex, count);
      }
      default -> throw new UnsupportedOperationException("Unsupported inline unpack type: " + baseType);
    };
  }

  @SuppressWarnings({"ConstantConditions", "DataFlowIssue"})
  private static StvnValue readOutlinedNode(DecodeContext ctx, int offset, @Nullable ResolvedSchema schema) {
    if (schema == null) {
      throw new org.stvnadore.core.binary.exceptions.StvnSerializationException("Missing schema context for outlined node decoding.");
    }
    String baseType = StvnTypeResolver.getPrimitiveBaseType(schema.node());
    if (baseType == null) {
      throw new org.stvnadore.core.binary.exceptions.StvnSerializationException("Unknown base type under schema.");
    }

    // -------------------------------------------------------------------------
    // 1. STRINGS
    // -------------------------------------------------------------------------
    if (baseType.startsWith(":String") || baseType.equals(":Uuid")) {
      StvnBinaryDecoder.LengthResult lr = readDerivedLengthPrefix(ctx.buffer(), offset);
      int totalLength = lr.length();
      int dataStartOffset = offset + lr.bytesConsumed();

      // Read Style (0=SIMPLE, 1=BLOCK, 2=FENCED)
      byte styleByte = ctx.buffer().get(dataStartOffset);
      StvnValue.StringStyle style = StvnValue.StringStyle.values()[styleByte];

      int payloadOffset = dataStartOffset + 1;
      int payloadLength = totalLength - 1;
      String fenceTag = null;

      // Extract the dynamic fence tag if the style requires it
      if (style == StvnValue.StringStyle.FENCED) {
        int tagLen = Byte.toUnsignedInt(ctx.buffer().get(payloadOffset));
        payloadOffset += 1;
        payloadLength -= 1;

        byte[] tagBytes = new byte[tagLen];
        ctx.buffer().get(payloadOffset, tagBytes);
        fenceTag = new String(tagBytes, StandardCharsets.UTF_8);

        payloadOffset += tagLen;
        payloadLength -= tagLen;
      }

      // Read the actual string characters
      byte[] payloadBytes = new byte[payloadLength];
      ctx.buffer().get(payloadOffset, payloadBytes);
      String rawText = new String(payloadBytes, StandardCharsets.UTF_8);

      boolean isFixed = baseType != null && baseType.startsWith(":StringFixed") && isNumeric(baseType.substring(12));
      boolean isNonEmpty = baseType != null && (baseType.equals(":StringNonEmpty") || (baseType.startsWith(":StringNonEmpty") && isNumeric(baseType.substring(15))));
      boolean isBounded = baseType != null && baseType.startsWith(":String") && !baseType.startsWith(":StringFixed") && !baseType.startsWith(":StringNonEmpty") && isNumeric(baseType.substring(7));

      int fixedLength = 0;
      int maxLength = 0;

      if (isFixed) {
        fixedLength = Integer.parseInt(baseType.substring(12));
      } else if (isNonEmpty && baseType.length() > 15) {
        maxLength = Integer.parseInt(baseType.substring(15));
      } else if (isBounded && baseType.length() > 7) {
        maxLength = Integer.parseInt(baseType.substring(7));
      }

      StvnValue.StringTrait trait = new StvnValue.StringTrait(fixedLength, maxLength, isNonEmpty);
      return new StvnValue.StvnString(schema, rawText, style, Optional.ofNullable(fenceTag), trait);
    }

    // -------------------------------------------------------------------------
    // 1.5 EXACT FLOATS
    // -------------------------------------------------------------------------
    if (baseType != null && baseType.equals(":FloatExact")) {
      StvnBinaryDecoder.LengthResult lr = readDerivedLengthPrefix(ctx.buffer(), offset);
      int dataStartOffset = offset + lr.bytesConsumed();

      // Skip the fallback style byte (1 byte)
      int payloadOffset = dataStartOffset + 1;
      int payloadLength = lr.length() - 1;

      byte[] textBytes = new byte[payloadLength];
      ctx.buffer().get(payloadOffset, textBytes);
      String rawText = new String(textBytes, StandardCharsets.UTF_8);

      return new StvnValue.StvnFloat(schema, new java.math.BigDecimal(rawText));
    }

    // -------------------------------------------------------------------------
    // 1.6 OUTLINED TIME (ZONED / OFFSET / AUDITED / DATETIME)
    // -------------------------------------------------------------------------
    if (baseType != null && (baseType.equals(":DateTimeOffset") || baseType.equals(":DateTimeZoned") || baseType.equals(":DateTimeAudited") || baseType.equals(":DateTime"))) {
      StvnBinaryDecoder.LengthResult lr = readDerivedLengthPrefix(ctx.buffer(), offset);
      int dataStartOffset = offset + lr.bytesConsumed();

      // Skip the fallback style byte (1 byte)
      int payloadOffset = dataStartOffset + 1;
      int payloadLength = lr.length() - 1;

      byte[] textBytes = new byte[payloadLength];
      ctx.buffer().get(payloadOffset, textBytes);
      String rawText = new String(textBytes, StandardCharsets.UTF_8);

      if (baseType.equals(":DateTimeOffset")) {
        var parsed = StvnLiteralParser.parseDateTimeOffset("\"" + rawText + "\"");
        return new StvnValue.StvnDateTimeOffset(schema, parsed.value());
      } else if (baseType.equals(":DateTimeZoned")) {
        var parsed = StvnLiteralParser.parseDateTimeZoned("\"" + rawText + "\"");
        return new StvnValue.StvnDateTimeZoned(schema, parsed.localDateTime(), parsed.zoneId());
      } else if (baseType.equals(":DateTimeAudited")) {
        var parsed = StvnLiteralParser.parseDateTimeAudited("\"" + rawText + "\"");
        return new StvnValue.StvnDateTimeAudited(schema, parsed.offsetDateTime(), parsed.zoneId());
      } else {
        if (rawText.contains("[")) {
          if (rawText.contains("+") || rawText.contains("-") || rawText.contains("Z")) {
            var parsed = StvnLiteralParser.parseDateTimeAudited("\"" + rawText + "\"");
            return new StvnValue.StvnDateTimeAudited(schema, parsed.offsetDateTime(), parsed.zoneId());
          } else {
            var parsed = StvnLiteralParser.parseDateTimeZoned("\"" + rawText + "\"");
            return new StvnValue.StvnDateTimeZoned(schema, parsed.localDateTime(), parsed.zoneId());
          }
        } else {
          var parsed = StvnLiteralParser.parseDateTimeOffset("\"" + rawText + "\"");
          return new StvnValue.StvnDateTimeOffset(schema, parsed.value());
        }
      }
    }

    // -------------------------------------------------------------------------
    // 1.7 OUTLINED BIG INTEGER
    // -------------------------------------------------------------------------
    if (baseType != null && (baseType.startsWith(":Int") || baseType.startsWith(":Uint"))) {
      StvnBinaryDecoder.LengthResult lr = readDerivedLengthPrefix(ctx.buffer(), offset);
      int dataStartOffset = offset + lr.bytesConsumed();
      byte[] bytes = new byte[lr.length()];
      // Use absolute get instead of mutating buffer position
      for (int i = 0; i < bytes.length; i++) {
        bytes[i] = ctx.buffer().get(dataStartOffset + i);
      }

      boolean isUnsigned = baseType.startsWith(":Uint");
      String widthStr = baseType.replaceAll("[^0-9]", "");
      int bitWidth = widthStr.isEmpty()
          ? (bytes.length * 8)
          : Integer.parseInt(widthStr);
      if (bytes.length > 0) {
        validateHighBitMask(bytes[0], bitWidth, isUnsigned);
      }

      java.math.BigInteger val;
      if (isUnsigned && (bytes[0] & 0x80) != 0) {
        byte[] uBytes = new byte[bytes.length + 1];
        uBytes[0] = 0x00;
        System.arraycopy(bytes, 0, uBytes, 1, bytes.length);
        val = new java.math.BigInteger(uBytes);
      } else {
        val = new java.math.BigInteger(bytes);
      }
      return new StvnValue.StvnInteger(schema, val, 0, isUnsigned);
    }

    // -------------------------------------------------------------------------
    // 2. TUPLES
    // -------------------------------------------------------------------------
    if (baseType != null && baseType.startsWith(":Tuple")) {
      List<ResolvedSchema> childSchemas = extractChildSchemas(schema);
      List<StvnValue> elements = new java.util.ArrayList<>();
      int currentSlotOffset = offset;

      for (ResolvedSchema childSchema : childSchemas) {
        int inline = getInlineSize(childSchema);
        // CRITICAL: Dereference if outlined!
        int payloadOffset = (inline > 0)
            ? currentSlotOffset
            : ctx.readPointer(currentSlotOffset);

        elements.add(inline > 0
            ? readInlineNode(ctx, payloadOffset, childSchema)
            : readOutlinedNode(ctx, payloadOffset, childSchema));

        currentSlotOffset += (inline > 0
            ? inline
            : ctx.offsetSize());
      }
      return new StvnValue.StvnTuple(schema, elements);
    }

    // -------------------------------------------------------------------------
    // 3. SEQUENCES / LISTS
    // -------------------------------------------------------------------------
    if (baseType != null && (baseType.startsWith(":List") || baseType.startsWith(":Seq"))) {
      StvnSeqReader reader = new StvnSeqReader(ctx, offset, schema);
      List<ResolvedSchema> childSchemas = extractChildSchemas(schema);
      ResolvedSchema elementSchema = childSchemas.isEmpty()
          ? null
          : childSchemas.getFirst();
      List<StvnValue> elements = new java.util.ArrayList<>();

      if (reader.size() > 0) {
        // PATHWAY B: Wire safeguard to verify Seq/List element schema before unpacking payload
        if (elementSchema == null)
          throw new org.stvnadore.core.binary.exceptions.StvnSerializationException("Missing element schema.");
        int inline = getInlineSize(elementSchema);

        for (int i = 0; i < reader.size(); i++) {
          int slotOffset = reader.getAbsoluteOffset(i);
          // CRITICAL: Dereference array slot if outlined!
          int payloadOffset = (inline > 0)
              ? slotOffset
              : ctx.readPointer(slotOffset);

          elements.add(inline > 0
              ? readInlineNode(ctx, payloadOffset, elementSchema)
              : readOutlinedNode(ctx, payloadOffset, elementSchema));
        }
      }
      return new StvnValue.StvnSeq(schema, elements, baseType.endsWith("NonEmpty"));
    }

    // -------------------------------------------------------------------------
    // 4. SETS
    // -------------------------------------------------------------------------
    if (baseType != null && baseType.startsWith(":Set")) {
      StvnSeqReader reader = new StvnSeqReader(ctx, offset, schema);
      List<ResolvedSchema> childSchemas = extractChildSchemas(schema);
      ResolvedSchema elementSchema = childSchemas.isEmpty()
          ? null
          : childSchemas.getFirst();
      java.util.LinkedHashSet<StvnValue> elements = new java.util.LinkedHashSet<>();

      if (reader.size() > 0) {
        // PATHWAY B: Wire safeguard to verify Set element schema before unpacking payload
        if (elementSchema == null)
          throw new org.stvnadore.core.binary.exceptions.StvnSerializationException("Missing element schema.");
        int inline = getInlineSize(elementSchema);

        for (int i = 0; i < reader.size(); i++) {
          int slotOffset = reader.getAbsoluteOffset(i);
          int payloadOffset = (inline > 0)
              ? slotOffset
              : ctx.readPointer(slotOffset);

          elements.add(inline > 0
              ? readInlineNode(ctx, payloadOffset, elementSchema)
              : readOutlinedNode(ctx, payloadOffset, elementSchema));
        }
      }
      return new StvnValue.StvnSet(schema, elements, baseType.endsWith("NonEmpty"));
    }

    // -------------------------------------------------------------------------
    // 5. MAPS
    // -------------------------------------------------------------------------
    if (baseType != null && baseType.startsWith(":Map")) {
      StvnMapReader reader = new StvnMapReader(ctx, offset, schema);
      List<ResolvedSchema> childSchemas = extractChildSchemas(schema);
      ResolvedSchema keySchema = childSchemas.size() > 0
          ? childSchemas.get(0)
          : null;
      ResolvedSchema valSchema = childSchemas.size() > 1
          ? childSchemas.get(1)
          : null;
      java.util.LinkedHashMap<StvnValue, StvnValue> entries = new java.util.LinkedHashMap<>();

      if (reader.size() > 0) {
        // PATHWAY B: Wire safeguard to verify Map key and value schemas before unpacking payload
        if (keySchema == null || valSchema == null)
          throw new org.stvnadore.core.binary.exceptions.StvnSerializationException("Missing schemas.");
        int keyInline = getInlineSize(keySchema);
        int valInline = getInlineSize(valSchema);

        for (int i = 0; i < reader.size(); i++) {
          // Note: Adjust 'getKeyOffset' to match your MapReader's actual method name!
          int keySlot = reader.keys.getAbsoluteOffset(i);
          int keyPayload = (keyInline > 0)
              ? keySlot
              : ctx.readPointer(keySlot);
          StvnValue key = keyInline > 0
              ? readInlineNode(ctx, keyPayload, keySchema)
              : readOutlinedNode(ctx, keyPayload, keySchema);

          // Note: Adjust 'getValueOffset' to match your MapReader's actual method name!
          int valSlot = reader.values.getAbsoluteOffset(i);
          int valPayload = (valInline > 0)
              ? valSlot
              : ctx.readPointer(valSlot);
          StvnValue value = valInline > 0
              ? readInlineNode(ctx, valPayload, valSchema)
              : readOutlinedNode(ctx, valPayload, valSchema);

          entries.put(key, value);
        }
      }
      boolean isInverse = baseType.startsWith(":MapInv");
      return new StvnValue.StvnMap(schema, entries, baseType.endsWith("NonEmpty"), isInverse);
    }

    // -------------------------------------------------------------------------
    // 6. OPTIONS
    // -------------------------------------------------------------------------
    if (baseType != null && baseType.startsWith(":Option")) {
      List<ResolvedSchema> childSchemas = extractChildSchemas(schema);
      ResolvedSchema valueSchema = childSchemas.isEmpty()
          ? null
          : childSchemas.getFirst();

      byte tag = ctx.buffer().get(offset);
      if (tag == 0x00) {
        return new StvnValue.StvnOption(schema, Optional.empty());
      } else {
        // PATHWAY B: Wire safeguard to verify Option value schema before unpacking payload
        if (valueSchema == null) {
          throw new org.stvnadore.core.binary.exceptions.StvnSerializationException("Cannot decode Option payload: Missing value schema.");
        }

        int inline = getInlineSize(valueSchema);
        int payloadSlotOffset = offset + 1; // Assuming packed 1-byte tag
        int payloadOffset = (inline > 0)
            ? payloadSlotOffset
            : ctx.readPointer(payloadSlotOffset);

        StvnValue payload = inline > 0
            ? readInlineNode(ctx, payloadOffset, valueSchema)
            : readOutlinedNode(ctx, payloadOffset, valueSchema);

        return new StvnValue.StvnOption(schema, Optional.of(payload));
      }
    }

    // -------------------------------------------------------------------------
    // 7. EITHERS
    // -------------------------------------------------------------------------
    if (baseType != null && baseType.startsWith(":Either")) {
      List<ResolvedSchema> childSchemas = extractChildSchemas(schema);
      ResolvedSchema leftSchema = !childSchemas.isEmpty()
          ? childSchemas.get(0)
          : null;
      ResolvedSchema rightSchema = childSchemas.size() > 1
          ? childSchemas.get(1)
          : null;

      boolean isAmbiguous = false;
      if (leftSchema != null && rightSchema != null) {
        String leftBase = StvnTypeResolver.getPrimitiveBaseType(leftSchema.node());
        String rightBase = StvnTypeResolver.getPrimitiveBaseType(rightSchema.node());
        isAmbiguous = (leftBase != null && leftBase.equals(rightBase));
      }

      byte tag = ctx.buffer().get(offset);
      boolean isRight = (tag == 0x01);
      ResolvedSchema activeSchema = isRight
          ? rightSchema
          : leftSchema;

      // Pass the implicit union tag to the child schema
      if (activeSchema != null && schema.node() != null && schema.node().schemaConstructor() != null && schema.node().schemaConstructor().sumType() != null) {
        activeSchema = new ResolvedSchema(activeSchema.node(), activeSchema.constraints(), activeSchema.aliasName(), Optional.of(isRight
            ? 1
            : 0), Optional.of(schema.node().schemaConstructor().sumType()));
      }

      // PATHWAY B: Wire safeguard to verify Either active variant schema before unpacking payload
      if (activeSchema == null) {
        throw new org.stvnadore.core.binary.exceptions.StvnSerializationException("Cannot decode Either payload: Missing schema for active variant.");
      }

      int inline = getInlineSize(activeSchema);
      int payloadSlotOffset = offset + 1; // Assuming packed 1-byte tag
      int payloadOffset = (inline > 0)
          ? payloadSlotOffset
          : ctx.readPointer(payloadSlotOffset);

      StvnValue payload = inline > 0
          ? readInlineNode(ctx, payloadOffset, activeSchema)
          : readOutlinedNode(ctx, payloadOffset, activeSchema);

      return new StvnValue.StvnEither(schema, payload, isRight, isAmbiguous);
    }

    // -------------------------------------------------------------------------
    // 8. UNIONS
    // -------------------------------------------------------------------------
    if (baseType != null && baseType.startsWith(":Union")) {
      List<ResolvedSchema> childSchemas = extractChildSchemas(schema);
      int variantIndex = Byte.toUnsignedInt(ctx.buffer().get(offset));

      if (variantIndex >= childSchemas.size())
        throw new org.stvnadore.core.binary.exceptions.StvnSerializationException("Union variant index exceeds defined variants.");

      ResolvedSchema activeSchema = childSchemas.get(variantIndex);

      // Pass the implicit union tag to the child schema
      if (activeSchema != null && schema.node() != null && schema.node().schemaConstructor() != null && schema.node().schemaConstructor().sumType() != null) {
        activeSchema = new ResolvedSchema(activeSchema.node(), activeSchema.constraints(), activeSchema.aliasName(), Optional.of(variantIndex), Optional.of(schema.node().schemaConstructor().sumType()));
      }

      int inline = getInlineSize(activeSchema);
      int payloadSlotOffset = offset + 1; // Assuming packed 1-byte tag
      int payloadOffset = (inline > 0)
          ? payloadSlotOffset
          : ctx.readPointer(payloadSlotOffset);

      StvnValue payload = inline > 0
          ? readInlineNode(ctx, payloadOffset, activeSchema)
          : readOutlinedNode(ctx, payloadOffset, activeSchema);

      return new StvnValue.StvnUnion(schema, payload, variantIndex);
    }

    throw new org.stvnadore.core.binary.exceptions.StvnSerializationException("Unsupported or undefined type for decoding: " + baseType);
  }

  private static @Nullable ResolvedSchema cachedStringSchema = null;

  private static ResolvedSchema getStringSchema() {
    if (cachedStringSchema == null) {
      var prelude = org.stvnadore.core.stdlib.StvnPrelude.getPreludeDocument();
      var def = org.stvnadore.core.validation.StvnTypeResolver.findTypeDefinition(prelude, ":SemVer").get();
      cachedStringSchema = org.stvnadore.core.validation.StvnTypeResolver.resolvePrimitiveSchema(prelude, def.schemaType(), java.util.Set.of()).get();
    }
    return cachedStringSchema;
  }

  /**
   * Verifies that unused high bits in the leading byte container are strictly zero for arbitrary bit-widths where
   * {@code bitWidth % 8 != 0}.
   *
   * @param mostSignificantByte the highest-order byte from the big-endian representation
   * @param bitWidth            the schema-defined bit-width (n)
   * @param isUnsigned          whether the integer type is unsigned
   * @throws StvnCorruptedBitPatternException if any unused bit is non-zero
   */
  private static void validateHighBitMask(byte mostSignificantByte, int bitWidth, boolean isUnsigned) {
    if (bitWidth <= 0) return;
    int remainderBits = bitWidth % 8;
    if (remainderBits != 0) {
      int validMask = (1 << remainderBits) - 1;
      int b0 = Byte.toUnsignedInt(mostSignificantByte);
      int upperMask = ~validMask & 0xFF;
      int upperBits = b0 & upperMask;

      if (isUnsigned) {
        if (upperBits != 0) {
          throw new StvnCorruptedBitPatternException(
              String.format("Corrupted bit pattern: %d-bit unsigned integer has non-zero unused high bits (0x%02X with mask 0x%02X)",
                  bitWidth, b0, validMask));
        }
      } else {
        int signBit = (b0 >> (remainderBits - 1)) & 1;
        if (signBit == 0) {
          if (upperBits != 0) {
            throw new StvnCorruptedBitPatternException(
                String.format("Corrupted bit pattern: %d-bit signed integer has non-zero unused high bits (0x%02X with mask 0x%02X)",
                    bitWidth, b0, validMask));
          }
        } else {
          if (upperBits != 0 && upperBits != upperMask) {
            throw new StvnCorruptedBitPatternException(
                String.format("Corrupted bit pattern: %d-bit signed integer has inconsistent high bits (0x%02X with mask 0x%02X)",
                    bitWidth, b0, validMask));
          }
        }
      }
    }
  }

  /**
   * Validates that the structural hash of a given schema matches the expected explicit fingerprint defined in the
   * identity strategy.
   *
   * @param schema   the resolved schema context to validate
   * @param strategy the expected identity strategy containing the fingerprint hash
   * @throws StvnSerializationException if the computed hash does not match the expected strategy fingerprint
   */
  public static void validateSchemaHash(ResolvedSchema schema, SchemaIdentityStrategy strategy) {
    if (strategy instanceof SchemaIdentityStrategy.ExplicitUuid(java.util.UUID uuid)) {
      java.util.UUID computed = StvnSchemaHasher.hashSchema(schema);
      if (!computed.equals(uuid)) {
        throw new StvnSerializationException("Schema hash mismatch! Expected ExplicitUuid: " + uuid + ", but computed: " + computed);
      }
    } else if (strategy instanceof SchemaIdentityStrategy.ExplicitSha256(byte[] hash)) {
      byte[] computed = StvnSchemaHasher.computeSha256(schema);
      if (!java.util.Arrays.equals(computed, hash)) {
        throw new PoisonedRegistryPayloadException("Schema hash mismatch! Expected ExplicitSha256, but computed hash did not match.");
      }
    }
  }

  private static ResolvedSchema compileInlineSchema(String content) {
    try {
      var lexer = new org.stvnadore.core.parser.StvnLexer(org.antlr.v4.runtime.CharStreams.fromString(content));
      var parser = new org.stvnadore.core.parser.StvnParser(new org.antlr.v4.runtime.CommonTokenStream(lexer));

      var errorListener = new org.antlr.v4.runtime.BaseErrorListener() {
        @Override
        public void syntaxError(
            org.antlr.v4.runtime.Recognizer<?, ?> recognizer,
            @Nullable Object offendingSymbol,
            int line,
            int charPositionInLine,
            String msg,
            org.antlr.v4.runtime.@Nullable RecognitionException e
        ) {
          throw new StvnSerializationException("Inline schema compilation syntax error: " + msg, e);
        }
      };
      lexer.removeErrorListeners();
      lexer.addErrorListener(errorListener);
      parser.removeErrorListeners();
      parser.addErrorListener(errorListener);

      var docCtx = parser.stvnDocument();
      if (docCtx.documentBody() == null || docCtx.documentBody().typeEntry() == null) {
        throw new StvnSerializationException("Inline schema does not define a root type entry.");
      }
      var resolved = org.stvnadore.core.validation.StvnTypeResolver.resolvePrimitiveSchema(
          docCtx,
          docCtx.documentBody().typeEntry().schemaType(),
          java.util.Set.of()
      ).orElseThrow(() -> new StvnSerializationException("Failed to resolve inline schema root type."));

      validateUnionDistinctness(resolved, new java.util.HashSet<>());
      return resolved;
    } catch (Exception e) {
      if (e instanceof StvnSerializationException se) throw se;
      throw new StvnSerializationException("Failed to compile self-describing inline schema", e);
    }
  }

  private static void validateUnionDistinctness(ResolvedSchema schema, java.util.Set<ResolvedSchema> visited) {
    if (schema == null || !visited.add(schema)) return;

    String baseType = org.stvnadore.core.validation.StvnTypeResolver.getPrimitiveBaseType(schema.node());
    if (baseType != null && baseType.startsWith(":Union")) {
      List<ResolvedSchema> variants = extractChildSchemas(schema);
      java.util.Set<String> layoutCategories = new java.util.HashSet<>();
      for (ResolvedSchema variant : variants) {
        String category = getLayoutCategory(variant);
        if (!layoutCategories.add(category)) {
          throw new org.stvnadore.core.binary.exceptions.StvnSerializationException(
              "Union distinctness violation: Overlapping layout category '" + category + "' in Union: " + baseType);
        }
      }
    }

    List<ResolvedSchema> children = extractChildSchemas(schema);
    for (ResolvedSchema child : children) {
      validateUnionDistinctness(child, visited);
    }
    schema.underlyingSchema().ifPresent(underlying -> validateUnionDistinctness(underlying, visited));
  }

  private static String getLayoutCategory(ResolvedSchema rs) {
    if (rs == null || rs.node() == null) return "UNKNOWN";
    String base = org.stvnadore.core.validation.StvnTypeResolver.getPrimitiveBaseType(rs.node());
    if (base == null) return "UNKNOWN";

    if (base.startsWith(":String") || base.equals(":DateTimeOffset") || base.equals(":DateTimeZoned") || base.equals(":DateTimeAudited") || base.equals(":DateTime")
        || base.equals(":Uuid") || base.equals(":Ulid") || base.equals(":Sha256") || base.equals(":SemVer")
        || base.equals(":Email") || base.equals(":IPv4")) {
      return "STRING";
    }
    if (base.startsWith(":Int") || base.startsWith(":Uint") || base.equals(":TimeEpochS")
        || base.equals(":TimeEpochMs") || base.equals(":TimeEpochNs") || base.equals(":Port")) {
      return "INTEGER";
    }
    if (base.startsWith(":Float") || base.equals(":Percentage") || base.equals(":Probability")
        || base.equals(":Currency") || base.equals(":Latitude") || base.equals(":Longitude")) {
      return "FLOAT";
    }
    if (base.equals(":Boolean")) {
      return "BOOLEAN";
    }
    if (base.startsWith(":Tuple")) {
      return "TUPLE";
    }
    if (base.startsWith(":Seq") || base.startsWith(":Set") || base.startsWith(":SeqNonEmpty") || base.startsWith(":SetNonEmpty")) {
      return "SEQUENCE";
    }
    if (base.startsWith(":Map") || base.startsWith(":MapEntry") || base.startsWith(":MapInv")) {
      return "MAP";
    }
    if (base.equals(":Enum")) {
      return "ENUM";
    }
    return base;
  }

  private static boolean isNumeric(String str) {
    if (str.isEmpty()) return false;
    for (var i = 0; i < str.length(); i++) {
      if (!Character.isDigit(str.charAt(i))) {
        return false;
      }
    }
    return true;
  }
}
