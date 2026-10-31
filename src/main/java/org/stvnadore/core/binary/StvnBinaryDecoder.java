package org.stvnadore.core.binary;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.binary.exceptions.PoisonedRegistryPayloadException;
import org.stvnadore.core.binary.exceptions.StvnCorruptedBitPatternException;
import org.stvnadore.core.binary.exceptions.StvnSerializationException;
import org.stvnadore.core.binary.exceptions.StvnVersionException;
import org.stvnadore.core.binary.readers.StvnMapReader;
import org.stvnadore.core.binary.readers.StvnSeqReader;
import org.stvnadore.core.binary.readers.StvnTupleReader;
import org.stvnadore.core.ir.StvnDefsCatalog;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnDocumentKind;
import org.stvnadore.core.ir.StvnDocumentMeta;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnSchema.DateTimeMode;
import org.stvnadore.core.ir.StvnSchema.FloatRepresentation;
import org.stvnadore.core.ir.StvnSchema.TemporalScale;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.ir.StvnValue.EitherBranch;
import org.stvnadore.core.ir.StvnValue.Ieee754SpecialKind;
import org.stvnadore.core.ir.StvnValue.KeyValueEntry;
import org.stvnadore.core.lowering.StvnLiteralParser;
import org.stvnadore.core.lowering.StvnPayloadLowerer;
import org.stvnadore.core.utils.StvnBinaryUtils;
import org.stvnadore.core.validation.MalformedPayloadException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32C;

/**
 * Pure-IR decoder engine for parsing STVN 2.0.0 binary buffers ({@code .stvn_b} and {@code .stvn_bd})
 * into {@link StvnDocument} and {@link StvnValue} trees or traversing them via zero-copy flyweight readers.
 * <p>
 * Evaluates binary magic headers, extracts Control Byte 4 {@code 1:3:4} bitfields, verifies optional
 * CRC-32C trailers, decodes Byte 5 {@link StvnDocumentMeta} frames, validates schema identity hashes,
 * and enforces Appendix A.3 / A.4 bit-width masking and § 10.5 temporal layouts.
 *
 * @since 1.0.0
 */
@NullMarked
public final class StvnBinaryDecoder {

  private StvnBinaryDecoder() {
    // Utility class, non-instantiable
  }

  private static final byte STRING_STYLE_SIMPLE = 0;
  private static final byte STRING_STYLE_FENCED = 1;
  private static final BigInteger MAX_INLINE_BIT_WIDTH = BigInteger.valueOf(134_217_728L);
  private static final int MAX_DECODE_RECURSION_DEPTH = 512;

  // ===========================================================================
  // 1. INTERFACES & CONTEXT RECORDS
  // ===========================================================================

  /**
   * Registry contract for looking up normalized pure-IR {@link StvnSchema} definitions by strategy identifier.
   */
  @FunctionalInterface
  public interface StvnSchemaRegistry {
    /**
     * Looks up a pure-IR schema by its identity strategy.
     *
     * @param strategy the strategy to look up
     * @return the resolved pure-IR {@link StvnSchema}, or {@code null} if unknown
     */
    @Nullable StvnSchema lookup(SchemaIdentityStrategy strategy);
  }

  /**
   * Tracks buffer boundaries, pointer offset sizes, and schema/encoding strategy context during decode operations.
   *
   * @param buffer           the raw byte buffer being decoded
   * @param offsetSize       the pointer offset size (1, 2, 4, or 8 bytes)
   * @param identityStrategy the active schema strategy, if any
   * @param encodingStrategy the active binary encoding strategy
   * @param payloadStart     the minimum permissible payload boundary
   * @param meta             the decoded pure-IR document metadata, if present
   */
  public record DecodeContext(
      ByteBuffer buffer,
      int offsetSize,
      Optional<SchemaIdentityStrategy> identityStrategy,
      BinaryEncodingStrategy encodingStrategy,
      int payloadStart,
      Optional<StvnDocumentMeta> meta
  ) {
    /**
     * Constructs a {@code DecodeContext} defaulting to {@link BinaryEncodingStrategy#ZERO_COPY_POST_ORDER},
     * {@code payloadStart = 0}, and empty metadata.
     *
     * @param buffer           the raw byte buffer
     * @param offsetSize       the pointer offset size
     * @param identityStrategy the active schema strategy, if any
     */
    public DecodeContext(ByteBuffer buffer, int offsetSize, Optional<SchemaIdentityStrategy> identityStrategy) {
      this(buffer, offsetSize, identityStrategy, BinaryEncodingStrategy.ZERO_COPY_POST_ORDER, 0, Optional.empty());
    }

    /**
     * Constructs a {@code DecodeContext} defaulting {@code payloadStart} to {@code 0} and empty metadata.
     *
     * @param buffer           the raw byte buffer
     * @param offsetSize       the pointer offset size
     * @param identityStrategy the active schema strategy, if any
     * @param encodingStrategy the active binary encoding strategy
     */
    public DecodeContext(
        ByteBuffer buffer,
        int offsetSize,
        Optional<SchemaIdentityStrategy> identityStrategy,
        BinaryEncodingStrategy encodingStrategy
    ) {
      this(buffer, offsetSize, identityStrategy, encodingStrategy, 0, Optional.empty());
    }

    /**
     * Constructs a {@code DecodeContext} defaulting metadata to empty.
     *
     * @param buffer           the raw byte buffer
     * @param offsetSize       the pointer offset size
     * @param identityStrategy the active schema strategy, if any
     * @param encodingStrategy the active binary encoding strategy
     * @param payloadStart     the minimum permissible payload boundary
     */
    public DecodeContext(
        ByteBuffer buffer,
        int offsetSize,
        Optional<SchemaIdentityStrategy> identityStrategy,
        BinaryEncodingStrategy encodingStrategy,
        int payloadStart
    ) {
      this(buffer, offsetSize, identityStrategy, encodingStrategy, payloadStart, Optional.empty());
    }

    /**
     * Alias for {@link #meta()} returning the document identity metadata.
     *
     * @return the document metadata
     */
    public Optional<StvnDocumentMeta> documentMeta() {
      return meta;
    }

    /**
     * Reads a pointer address from the specified absolute offset in the buffer, validating
     * against the context's {@code payloadStart} boundary.
     *
     * @param absoluteOffset the address containing the pointer
     * @return the dereferenced absolute address
     */
    public int readPointer(int absoluteOffset) {
      return readPointer(absoluteOffset, this.payloadStart);
    }

    /**
     * Reads a pointer address from the specified absolute offset in the buffer, validating
     * that the dereferenced target address satisfies {@code targetOffset >= payloadStart && targetOffset < buffer.limit()}.
     *
     * @param absoluteOffset the address containing the pointer
     * @param payloadStart   the minimum permissible payload boundary
     * @return the dereferenced absolute address
     * @throws MalformedPayloadException if the target address points backward into headers or out of bounds
     */
    public int readPointer(int absoluteOffset, int payloadStart) {
      if (absoluteOffset < 0 || absoluteOffset + offsetSize > buffer.limit()) {
        throw new MalformedPayloadException(String.format(
            "Pointer address outside buffer boundary: offset %d, offsetSize %d, limit %d",
            absoluteOffset, offsetSize, buffer.limit()));
      }
      int targetOffset = switch (offsetSize) {
        case 1 -> Byte.toUnsignedInt(buffer.get(absoluteOffset));
        case 2 -> Short.toUnsignedInt(buffer.getShort(absoluteOffset));
        case 4 -> buffer.getInt(absoluteOffset);
        case 8 -> (int) buffer.getLong(absoluteOffset);
        default -> throw new IllegalStateException("Invalid offset size: " + offsetSize);
      };

      if (targetOffset < payloadStart || targetOffset >= buffer.limit()) {
        throw new MalformedPayloadException(String.format(
            "Pointer table hijacking detected: offset %d outside valid payload range [%d, %d)",
            targetOffset, payloadStart, buffer.limit()));
      }
      return targetOffset;
    }
  }

  /**
   * Represents the root reference pointing to a parsed STVN binary structure.
   *
   * @param context    the decoding context containing the buffer and offset sizes
   * @param rootOffset the absolute buffer address of the root element
   * @param schema     the resolved pure-IR {@link StvnSchema} for the root element, if available
   * @param meta       the decoded pure-IR {@link StvnDocumentMeta}, if present
   */
  public record RootPointer(
      DecodeContext context,
      int rootOffset,
      Optional<StvnSchema> schema,
      Optional<StvnDocumentMeta> meta
  ) {
    /**
     * Constructs a {@code RootPointer} inheriting document metadata from the decoding context.
     *
     * @param context    the decoding context containing the buffer and offset sizes
     * @param rootOffset the absolute buffer address of the root element
     * @param schema     the resolved pure-IR schema for the root element, if available
     */
    public RootPointer(DecodeContext context, int rootOffset, Optional<StvnSchema> schema) {
      this(context, rootOffset, schema, context.meta());
    }

    /**
     * Alias for {@link #meta()} returning the document identity metadata.
     *
     * @return the document metadata
     */
    public Optional<StvnDocumentMeta> documentMeta() {
      return meta;
    }
  }

  private record HeaderInfo(
      ByteBuffer effectiveBuffer,
      int offsetSize,
      @Nullable SchemaIdentityStrategy identityStrategy,
      BinaryEncodingStrategy encodingStrategy,
      int payloadStart,
      StvnDocumentMeta documentMeta
  ) {
  }

  // ===========================================================================
  // 2. HEADER PARSING & INITIALIZATION
  // ===========================================================================

  private static HeaderInfo parseHeader(ByteBuffer buffer) {
    buffer.order(ByteOrder.LITTLE_ENDIAN);
    if (buffer.remaining() < 6) {
      throw new IllegalArgumentException("Buffer too small for STVN binary header: requires at least 6 bytes");
    }
    if (buffer.get(0) != (byte) 'S' || buffer.get(1) != (byte) 'T'
        || buffer.get(2) != (byte) 'V' || buffer.get(3) != (byte) 'N') {
      throw new IllegalArgumentException("Invalid STVN binary: Magic preamble mismatch (expected 'STVN')");
    }

    // Control Byte 4 1:3:4 bitmask extraction (§ 10.1 & § 10.4)
    byte controlByte = buffer.get(4);
    boolean hasTrailerCrc32c = (controlByte & (byte) 0x80) != 0;
    int encodingCode = (controlByte >>> 4) & 0x07;
    int identityCode = controlByte & 0x0F;

    if (identityCode == 7 && !hasTrailerCrc32c) {
      throw new MalformedPayloadException(
          "Zero-Trust Policy Violation: Strategy 0x7 envelope requires mandatory Bit 7 CRC-32C trailer (expected control byte 0x87, found 0x07)");
    }

    ByteBuffer effectiveBuffer = buffer;
    if (hasTrailerCrc32c) {
      if (buffer.remaining() < StvnBinaryUtils.MIN_CRC32C_BUFFER_CAPACITY) {
        throw new MalformedPayloadException(
            "Buffer too small for STVN binary with CRC-32C trailer: requires at least "
                + StvnBinaryUtils.MIN_CRC32C_BUFFER_CAPACITY + " bytes, found " + buffer.remaining());
      }
      int totalLimit = buffer.limit();
      int payloadLimit = totalLimit - 4;

      ByteBuffer crcView = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
      crcView.position(0);
      crcView.limit(payloadLimit);

      CRC32C crc = new CRC32C();
      crc.update(crcView);
      int computedCrc = (int) crc.getValue();
      int expectedCrc = buffer.getInt(payloadLimit);

      if (computedCrc != expectedCrc) {
        throw new MalformedPayloadException("CRC-32C trailer mismatch: payload corrupted or truncated");
      }
      effectiveBuffer = buffer.slice(0, payloadLimit).order(ByteOrder.LITTLE_ENDIAN);
    }

    BinaryEncodingStrategy encodingStrategy = BinaryEncodingStrategy.fromCode(encodingCode);

    int currentPos = 5;
    byte metaControlByte = effectiveBuffer.get(currentPos++);
    if ((metaControlByte & StvnBinaryUtils.META_MASK_RESERVED) != 0) {
      throw new StvnCorruptedBitPatternException(
          "Zero-Trust Violation: Non-zero reserved bits in MetaControlByte (Byte 5): 0x"
              + Integer.toHexString(Byte.toUnsignedInt(metaControlByte)));
    }

    int kindBits = metaControlByte & StvnBinaryUtils.META_MASK_KIND;
    StvnDocumentKind kind = switch (kindBits) {
      case 0b00 -> StvnDocumentKind.BODY;
      case 0b01 -> StvnDocumentKind.DEFS;
      case 0b10 -> StvnDocumentKind.BODY_INCLUDE;
      case 0b11 -> StvnDocumentKind.DEFS_INCLUDE;
      default -> throw new IllegalStateException("Unreachable 2-bit document kind: " + kindBits);
    };

    boolean hasName = (metaControlByte & StvnBinaryUtils.META_MASK_HAS_NAME) != 0;
    boolean hasDomain = (metaControlByte & StvnBinaryUtils.META_MASK_HAS_DOMAIN) != 0;

    Optional<String> nameOpt = Optional.empty();
    if (hasName) {
      validateAllocationBounds(1, effectiveBuffer, currentPos);
      int nameLen = Byte.toUnsignedInt(effectiveBuffer.get(currentPos++));
      if (nameLen < 1 || nameLen > StvnBinaryUtils.MAX_METADATA_IDENTIFIER_LENGTH) {
        throw new MalformedPayloadException("Invalid metadata #name length prefix: " + nameLen);
      }
      validateAllocationBounds(nameLen, effectiveBuffer, currentPos);
      byte[] nameBytes = new byte[nameLen];
      effectiveBuffer.get(currentPos, nameBytes);
      currentPos += nameLen;
      String name = new String(nameBytes, StandardCharsets.UTF_8);
      if (!name.matches("^[a-zA-Z0-9_-]{1,64}$")) {
        throw new MalformedPayloadException("Metadata #name does not match atomic POSIX pattern: " + name);
      }
      nameOpt = Optional.of(name);
    }

    Optional<String> domainOpt = Optional.empty();
    if (hasDomain) {
      validateAllocationBounds(1, effectiveBuffer, currentPos);
      int domainLen = Byte.toUnsignedInt(effectiveBuffer.get(currentPos++));
      if (domainLen < 1 || domainLen > StvnBinaryUtils.MAX_METADATA_IDENTIFIER_LENGTH) {
        throw new MalformedPayloadException("Invalid metadata #domain length prefix: " + domainLen);
      }
      validateAllocationBounds(domainLen, effectiveBuffer, currentPos);
      byte[] domainBytes = new byte[domainLen];
      effectiveBuffer.get(currentPos, domainBytes);
      currentPos += domainLen;
      String domain = new String(domainBytes, StandardCharsets.UTF_8);
      if (!domain.matches("^[a-zA-Z0-9_-]{1,64}$")) {
        throw new MalformedPayloadException("Metadata #domain does not match atomic POSIX pattern: " + domain);
      }
      domainOpt = Optional.of(domain);
    }

    StvnDocumentMeta documentMeta = new StvnDocumentMeta(nameOpt, domainOpt, Optional.of(kind));

    SchemaIdentityStrategy strategy = switch (identityCode) {
      case 0 -> new SchemaIdentityStrategy.UniversalDefault();
      case 1 -> new SchemaIdentityStrategy.UuidV8Hash();
      case 2 -> new SchemaIdentityStrategy.Sha256Hash();
      case 3 -> {
        validateAllocationBounds(2, effectiveBuffer, currentPos);
        int len = Short.toUnsignedInt(effectiveBuffer.getShort(currentPos)) + 1;
        currentPos += 2;
        validateAllocationBounds(len, effectiveBuffer, currentPos);
        byte[] bytes = new byte[len];
        effectiveBuffer.get(currentPos, bytes);
        currentPos += len;
        yield new SchemaIdentityStrategy.AsciiStringKey(new String(bytes, StandardCharsets.US_ASCII));
      }
      case 4 -> {
        validateAllocationBounds(2, effectiveBuffer, currentPos);
        int len = Short.toUnsignedInt(effectiveBuffer.getShort(currentPos)) + 1;
        currentPos += 2;
        validateAllocationBounds(len, effectiveBuffer, currentPos);
        byte[] bytes = new byte[len];
        effectiveBuffer.get(currentPos, bytes);
        currentPos += len;
        yield new SchemaIdentityStrategy.UnicodeStringKey(new String(bytes, StandardCharsets.UTF_8));
      }
      case 5 -> {
        validateAllocationBounds(4, effectiveBuffer, currentPos);
        long version = Integer.toUnsignedLong(effectiveBuffer.getInt(currentPos)) + 1;
        currentPos += 4;
        yield new SchemaIdentityStrategy.UniversalVersion(version);
      }
      case 6 -> {
        validateAllocationBounds(16, effectiveBuffer, currentPos);
        long mostSig = effectiveBuffer.getLong(currentPos);
        long leastSig = effectiveBuffer.getLong(currentPos + 8);
        currentPos += 16;
        yield new SchemaIdentityStrategy.ExplicitUuid(new UUID(mostSig, leastSig));
      }
      case 7 -> {
        validateAllocationBounds(32, effectiveBuffer, currentPos);
        byte[] hash = new byte[32];
        effectiveBuffer.get(currentPos, hash);
        currentPos += 32;
        yield new SchemaIdentityStrategy.ExplicitSha256(hash);
      }
      case 8 -> {
        validateAllocationBounds(4, effectiveBuffer, currentPos);
        long lenLong = Integer.toUnsignedLong(effectiveBuffer.getInt(currentPos)) + 1;
        currentPos += 4;
        validateAllocationBounds(lenLong, effectiveBuffer, currentPos);
        int len = (int) lenLong;
        byte[] bytes = new byte[len];
        effectiveBuffer.get(currentPos, bytes);
        currentPos += len;
        yield new SchemaIdentityStrategy.SelfDescribingSchema(new String(bytes, StandardCharsets.UTF_8));
      }
      default -> throw new StvnSerializationException("Invalid Schema Identity Strategy code: " + identityCode);
    };

    validateAllocationBounds(1, effectiveBuffer, currentPos);
    byte flags = effectiveBuffer.get(currentPos++);
    int offsetSize = 1 << (flags & 0b0000_0011);

    return new HeaderInfo(effectiveBuffer, offsetSize, strategy, encodingStrategy, currentPos, documentMeta);
  }

  private static RootPointer createRootPointer(HeaderInfo header, @Nullable StvnSchema schema) {
    int payloadStart = header.payloadStart() + header.offsetSize();
    DecodeContext ctx = new DecodeContext(
        header.effectiveBuffer(),
        header.offsetSize(),
        Optional.ofNullable(header.identityStrategy()),
        header.encodingStrategy(),
        payloadStart,
        Optional.of(header.documentMeta())
    );
    int rootOffset = ctx.readPointer(header.payloadStart(), payloadStart);
    return new RootPointer(ctx, rootOffset, Optional.ofNullable(schema), Optional.of(header.documentMeta()));
  }

  // ===========================================================================
  // 3. ENTRY POINTS
  // ===========================================================================

  /**
   * CONTRACT-FIRST Entry Point (Strict Mode): Opens the STVN binary buffer, parsing its header and validating
   * that the embedded schema identity strategy matches {@code expectedStrategy} exactly.
   *
   * @param buffer           the raw little-endian byte buffer to parse
   * @param expectedStrategy the exact strategy the binary is expected to match
   * @return a {@link RootPointer} pointing to the root layout offset
   * @throws StvnVersionException if the strategy is missing or does not match
   */
  public static RootPointer openStrict(ByteBuffer buffer, SchemaIdentityStrategy expectedStrategy) {
    Objects.requireNonNull(buffer, "buffer must not be null");
    Objects.requireNonNull(expectedStrategy, "expectedStrategy must not be null");

    HeaderInfo header = parseHeader(buffer);
    if (header.identityStrategy() == null) {
      throw new StvnVersionException(
          "Strict mode: Binary is schemaless, but " + expectedStrategy + " was expected.",
          expectedStrategy,
          null
      );
    }
    if (!expectedStrategy.equals(header.identityStrategy())) {
      throw new StvnVersionException(
          "Strict mode: Identity strategy mismatch. Expected " + expectedStrategy + ", found " + header.identityStrategy(),
          expectedStrategy,
          header.identityStrategy()
      );
    }
    return createRootPointer(header, null);
  }

  /**
   * DISCOVERY-FIRST Entry Point (General Mode): Opens the STVN binary buffer, resolving its pure-IR {@link StvnSchema}
   * dynamically via the supplied registry, inline self-describing definitions, or fallback schemas.
   *
   * @param buffer            the raw little-endian byte buffer to parse
   * @param registry          the schema registry used to look up strategies, or {@code null}
   * @param defaultFallback   the pure-IR fallback schema used if lookup fails, or {@code null}
   * @param universalFallback the pure-IR universal fallback schema to use, or {@code null}
   * @return a {@link RootPointer} containing the resolved pure-IR schema and root layout offset
   * @since 2.0.0
   */
  public static RootPointer open(
      ByteBuffer buffer,
      @Nullable StvnSchemaRegistry registry,
      @Nullable StvnSchema defaultFallback,
      @Nullable StvnSchema universalFallback
  ) {
    Objects.requireNonNull(buffer, "buffer must not be null");
    HeaderInfo header = parseHeader(buffer);
    StvnSchema resolved = null;

    if (header.identityStrategy() != null && registry != null) {
      resolved = registry.lookup(header.identityStrategy());
    }

    if (resolved == null
        && header.identityStrategy() instanceof SchemaIdentityStrategy.SelfDescribingSchema self) {
      StvnDocumentKind kind = header.documentMeta().kind().orElse(StvnDocumentKind.BODY);
      if (kind == StvnDocumentKind.BODY || kind == StvnDocumentKind.BODY_INCLUDE) {
        resolved = compileInlineSchema(self.schemaSourceText());
      }
    }

    if (resolved == null) {
      resolved = (defaultFallback != null) ? defaultFallback : universalFallback;
    }

    if (resolved != null && header.identityStrategy() != null) {
      validateSchemaHash(resolved, header.identityStrategy());
    }

    return createRootPointer(header, resolved);
  }

  /**
   * Opens an STVN binary buffer using pure-IR {@link StvnSchema} fallback definitions.
   *
   * @param buffer            the raw little-endian byte buffer to parse
   * @param registry          the schema registry used to look up strategies, or {@code null}
   * @param defaultFallback   the pure-IR fallback schema used if lookup fails, or {@code null}
   * @param universalFallback the pure-IR universal fallback schema to use, or {@code null}
   * @return a {@link RootPointer} containing the resolved pure-IR schema and root layout offset
   * @since 2.0.0
   */
  public static RootPointer openIr(
      ByteBuffer buffer,
      @Nullable StvnSchemaRegistry registry,
      @Nullable StvnSchema defaultFallback,
      @Nullable StvnSchema universalFallback
  ) {
    return open(buffer, registry, defaultFallback, universalFallback);
  }

  /**
   * CONVENIENCE Entry Point: Opens an STVN binary buffer where no registry or fallback schemas are required.
   *
   * @param buffer the raw little-endian byte buffer to parse
   * @return a {@link RootPointer} pointing to the root layout offset
   */
  public static RootPointer open(ByteBuffer buffer) {
    return open(buffer, null, null, null);
  }

  /**
   * Decodes a {@code .stvn_b} or {@code .stvn_bd} binary buffer directly into a pure-IR {@link StvnDocument}.
   * <p>
   * When Byte 5 declares {@code #kind == #DEFS} ({@code 0b01}) or {@code #DEFS_INCLUDE} ({@code 0b11}),
   * unpacks and compiles the canonical definitions schema into a payload-free {@link StvnDocument}.
   * When Byte 5 declares {@code #kind == #BODY} ({@code 0b00}) or {@code #BODY_INCLUDE} ({@code 0b10}),
   * unpacks the payload using the discovered schema.
   *
   * @param buffer the raw little-endian binary buffer
   * @return the decoded pure-IR {@link StvnDocument}
   * @since 2.0.0
   */
  public static StvnDocument decodeDocument(ByteBuffer buffer) {
    return decodeDocument(buffer, null);
  }

  /**
   * Decodes a {@code .stvn_b} or {@code .stvn_bd} binary buffer directly into a pure-IR {@link StvnDocument}
   * with an optional fallback root schema for {@code #BODY} frames.
   *
   * @param buffer         the raw little-endian binary buffer
   * @param fallbackSchema optional fallback root schema for {@code #BODY} frames, or {@code null}
   * @return the decoded pure-IR {@link StvnDocument}
   * @since 2.0.0
   */
  public static StvnDocument decodeDocument(ByteBuffer buffer, @Nullable StvnSchema fallbackSchema) {
    Objects.requireNonNull(buffer, "buffer must not be null");
    RootPointer root = open(buffer.duplicate(), null, fallbackSchema, null);
    StvnDocumentKind kind = root.meta().flatMap(StvnDocumentMeta::kind).orElse(StvnDocumentKind.BODY);

    if (kind == StvnDocumentKind.DEFS || kind == StvnDocumentKind.DEFS_INCLUDE) {
      String defsSource;
      if (root.context().identityStrategy().orElse(null) instanceof SchemaIdentityStrategy.SelfDescribingSchema self) {
        defsSource = self.schemaSourceText();
      } else {
        defsSource = readStringOutlined(root.context(), root.rootOffset());
      }
      StvnCompilationResult<StvnDocument> compiled = StvnCompiler.compile(defsSource);
      if (!compiled.isSuccess() || compiled.document().isEmpty()) {
        String errSummary = compiled.diagnostics().isEmpty()
            ? "Empty definitions payload"
            : compiled.diagnostics().getFirst().message();
        throw new MalformedPayloadException("Invalid .stvn_bd binary definitions payload: " + errSummary);
      }
      StvnDocument defsDoc = compiled.document().get();
      return new StvnDocument(
          root.meta(),
          defsDoc.defs(),
          defsDoc.rootType(),
          Optional.empty(),
          true,
          kind == StvnDocumentKind.DEFS_INCLUDE || defsDoc.hasIncludes()
      );
    }

    StvnDefsCatalog defsCatalog = StvnDefsCatalog.EMPTY;
    Optional<StvnSchema> targetSchema = Optional.ofNullable(fallbackSchema).or(root::schema);
    if (root.context().identityStrategy().orElse(null) instanceof SchemaIdentityStrategy.SelfDescribingSchema self) {
      StvnCompilationResult<StvnDocument> selfCompiled = StvnCompiler.compile(self.schemaSourceText());
      if (selfCompiled.isSuccess() && selfCompiled.document().isPresent()) {
        defsCatalog = selfCompiled.document().get().defs();
        if (targetSchema.isEmpty()) {
          targetSchema = selfCompiled.document().get().rootType();
        }
      }
    }

    StvnValue payload = unpack(root, targetSchema);
    return new StvnDocument(
        root.meta(),
        defsCatalog,
        Optional.of(payload.schema()),
        Optional.of(payload),
        !defsCatalog.nominalTypes().isEmpty() || !defsCatalog.constants().isEmpty(),
        kind == StvnDocumentKind.BODY_INCLUDE
    );
  }

  /**
   * Unpacks a binary payload against an explicit pure-IR {@link StvnSchema} into a pure-IR {@link StvnValue}.
   *
   * @param root   the root pointer reference
   * @param schema the pure-IR schema to use for unpacking
   * @return the unpacked pure-IR {@link StvnValue}
   * @since 2.0.0
   */
  public static StvnValue unpack(RootPointer root, StvnSchema schema) {
    Objects.requireNonNull(root, "root must not be null");
    Objects.requireNonNull(schema, "schema must not be null");
    return unpack(root, Optional.of(schema));
  }

  /**
   * Unpacks a binary payload into a pure-IR {@link StvnValue}.
   *
   * @param root      the root pointer reference
   * @param schemaOpt an optional explicit pure-IR {@link StvnSchema} overriding the discovered root schema
   * @return the unpacked pure-IR {@link StvnValue}
   * @since 2.0.0
   */
  public static StvnValue unpackIr(RootPointer root, Optional<StvnSchema> schemaOpt) {
    return unpack(root, schemaOpt);
  }

  /**
   * Eagerly unpacks the binary buffer and reconstructs the immutable pure-IR {@link StvnValue} tree.
   *
   * @param root      the root pointer reference containing buffer and offset configuration
   * @param schemaOpt an optional explicit pure-IR {@link StvnSchema} overriding the discovered root schema
   * @return the reconstructed root pure-IR {@link StvnValue}
   * @throws StvnSerializationException if no schema context can be resolved or validation fails
   * @since 2.0.0
   */
  public static StvnValue unpack(RootPointer root, Optional<StvnSchema> schemaOpt) {
    Objects.requireNonNull(root, "root must not be null");
    Objects.requireNonNull(schemaOpt, "schemaOpt must not be null");

    StvnSchema effectiveSchema = schemaOpt
        .or(root::schema)
        .orElseThrow(() -> new StvnSerializationException(
            "Cannot unpack STVN binary payload: No schema provided or discovered."));

    root.context().identityStrategy().ifPresent(strategy -> validateSchemaHash(effectiveSchema, strategy));

    Map<String, StvnSchema> nominalScope = new HashMap<>();
    collectNominalSchemas(effectiveSchema, nominalScope, Collections.newSetFromMap(new IdentityHashMap<>()));

    int inlineSize = getInlineSize(effectiveSchema, nominalScope);
    if (inlineSize > 0) {
      return readInlineNode(root.context(), root.rootOffset(), effectiveSchema, nominalScope);
    }
    return readOutlinedNode(root.context(), root.rootOffset(), effectiveSchema, nominalScope, 0);
  }

  // ===========================================================================
  // 4. ZERO-COPY FLYWEIGHT READERS
  // ===========================================================================

  /**
   * Opens a zero-copy tuple reader on the root of the parsed binary structure using the discovered schema.
   *
   * @param root the root pointer reference
   * @return a zero-copy {@link StvnTupleReader}
   */
  public static StvnTupleReader readRootTuple(RootPointer root) {
    return readRootTuple(root, root.schema());
  }

  /**
   * Opens a zero-copy sequence reader on the root of the parsed binary structure using the discovered schema.
   *
   * @param root the root pointer reference
   * @return a zero-copy {@link StvnSeqReader}
   */
  public static StvnSeqReader readRootSeq(RootPointer root) {
    return readRootSeq(root, root.schema());
  }

  /**
   * Opens a zero-copy map reader on the root of the parsed binary structure using the discovered schema.
   *
   * @param root the root pointer reference
   * @return a zero-copy {@link StvnMapReader}
   */
  public static StvnMapReader readRootMap(RootPointer root) {
    return readRootMap(root, root.schema());
  }

  /**
   * Opens a zero-copy tuple reader on the root of the parsed binary structure with an optional explicit schema.
   *
   * @param root           the root pointer reference
   * @param explicitSchema an optional explicit pure-IR {@link StvnSchema}
   * @return a zero-copy {@link StvnTupleReader}
   * @throws StvnSerializationException if no schema context can be resolved
   */
  public static StvnTupleReader readRootTuple(RootPointer root, Optional<StvnSchema> explicitSchema) {
    StvnSchema targetSchema = explicitSchema
        .or(root::schema)
        .orElseThrow(() -> new StvnSerializationException("Cannot read tuple: No schema context resolved."));
    return new StvnTupleReader(root.context(), root.rootOffset(), targetSchema);
  }

  /**
   * Opens a zero-copy sequence reader on the root of the parsed binary structure with an optional explicit schema.
   *
   * @param root           the root pointer reference
   * @param explicitSchema an optional explicit pure-IR {@link StvnSchema}
   * @return a zero-copy {@link StvnSeqReader}
   * @throws StvnSerializationException if no schema context can be resolved
   */
  public static StvnSeqReader readRootSeq(RootPointer root, Optional<StvnSchema> explicitSchema) {
    StvnSchema targetSchema = explicitSchema
        .or(root::schema)
        .orElseThrow(() -> new StvnSerializationException("Cannot read seq: No schema context resolved."));
    return new StvnSeqReader(root.context(), root.rootOffset(), targetSchema);
  }

  /**
   * Opens a zero-copy map reader on the root of the parsed binary structure with an optional explicit schema.
   *
   * @param root           the root pointer reference
   * @param explicitSchema an optional explicit pure-IR {@link StvnSchema}
   * @return a zero-copy {@link StvnMapReader}
   * @throws StvnSerializationException if no schema context can be resolved
   */
  public static StvnMapReader readRootMap(RootPointer root, Optional<StvnSchema> explicitSchema) {
    StvnSchema targetSchema = explicitSchema
        .or(root::schema)
        .orElseThrow(() -> new StvnSerializationException("Cannot read map: No schema context resolved."));
    return new StvnMapReader(root.context(), root.rootOffset(), targetSchema);
  }

  // ===========================================================================
  // 5. UTILITY READERS & HELPERS
  // ===========================================================================

  /**
   * Validates that an incoming wire payload length claim does not exceed remaining buffer capacity.
   *
   * @param requestedLength the number of bytes claimed by the wire length prefix
   * @param buffer          the buffer being decoded
   * @param currentOffset   the current offset where reading begins
   * @throws MalformedPayloadException if requested length is negative or exceeds remaining buffer capacity
   */
  public static void validateAllocationBounds(long requestedLength, ByteBuffer buffer, int currentOffset) {
    if (requestedLength < 0) {
      throw new MalformedPayloadException("Negative payload length prefix: " + requestedLength);
    }
    if (requestedLength > Integer.MAX_VALUE) {
      throw new MalformedPayloadException("Payload length exceeds Integer.MAX_VALUE: " + requestedLength);
    }
    if (currentOffset < 0 || currentOffset > buffer.limit()) {
      throw new MalformedPayloadException(
          "Current offset " + currentOffset + " outside buffer limit " + buffer.limit());
    }
    int remainingBytes = buffer.limit() - currentOffset;
    if (requestedLength > remainingBytes) {
      throw new MalformedPayloadException(String.format(
          "Payload length bomb detected: claimed %d bytes, but only %d bytes remain in buffer",
          requestedLength, remainingBytes));
    }
  }

  /**
   * Validates that an incoming wire payload length claim does not exceed remaining buffer capacity.
   *
   * @param requestedLength the number of bytes claimed by the wire length prefix
   * @param buffer          the buffer being decoded
   * @param currentOffset   the current offset where reading begins
   * @throws MalformedPayloadException if requested length is negative or exceeds remaining buffer capacity
   */
  public static void validateAllocationBounds(int requestedLength, ByteBuffer buffer, int currentOffset) {
    validateAllocationBounds((long) requestedLength, buffer, currentOffset);
  }

  /**
   * Reads the derived length prefix and extracts a UTF-8 string at {@code offset} without instantiating IR nodes.
   *
   * @param ctx    the decode context containing the buffer
   * @param offset the starting memory offset of the outlined string frame
   * @return the extracted Java {@link String}
   */
  public static String readStringOutlined(DecodeContext ctx, int offset) {
    LengthResult lr = readDerivedLengthPrefix(ctx.buffer(), offset);
    int totalLength = lr.length();
    int dataStartOffset = offset + lr.bytesConsumed();
    if (totalLength < 1) {
      throw new MalformedPayloadException("Invalid outlined string length (missing style byte): " + totalLength);
    }
    validateAllocationBounds(totalLength, ctx.buffer(), dataStartOffset);

    byte styleByte = ctx.buffer().get(dataStartOffset);
    if (styleByte != STRING_STYLE_SIMPLE && styleByte != STRING_STYLE_FENCED) {
      throw new MalformedPayloadException("Invalid string style byte: " + Byte.toUnsignedInt(styleByte));
    }

    int payloadOffset = dataStartOffset + 1;
    int payloadLength = totalLength - 1;

    if (styleByte == STRING_STYLE_FENCED) {
      validateAllocationBounds(1, ctx.buffer(), payloadOffset);
      int tagLen = Byte.toUnsignedInt(ctx.buffer().get(payloadOffset)) + 1;
      validateAllocationBounds(tagLen, ctx.buffer(), payloadOffset + 1);
      payloadOffset += (1 + tagLen);
      payloadLength -= (1 + tagLen);
    }

    validateAllocationBounds(payloadLength, ctx.buffer(), payloadOffset);
    byte[] textBytes = new byte[payloadLength];
    ctx.buffer().get(payloadOffset, textBytes);
    return new String(textBytes, StandardCharsets.UTF_8);
  }

  /**
   * Container carrying a parsed variable-length integer value and the count of prefix bytes consumed.
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
    validateAllocationBounds(1, buffer, offset);
    int b = Byte.toUnsignedInt(buffer.get(offset));
    if ((b & 0xC0) == 0) {
      return new LengthResult(b, 1);
    } else if ((b & 0xC0) == 0x40) {
      validateAllocationBounds(2, buffer, offset);
      int length = ((b & 0x3F) << 8) | Byte.toUnsignedInt(buffer.get(offset + 1));
      return new LengthResult(length, 2);
    } else if ((b & 0xC0) == 0x80) {
      validateAllocationBounds(3, buffer, offset);
      int length = ((b & 0x3F) << 16)
          | (Byte.toUnsignedInt(buffer.get(offset + 1)) << 8)
          | Byte.toUnsignedInt(buffer.get(offset + 2));
      return new LengthResult(length, 3);
    } else {
      validateAllocationBounds(5, buffer, offset);
      int length = buffer.getInt(offset + 1);
      return new LengthResult(length, 5);
    }
  }

  /**
   * Extracts the immediate structural child schemas from a composite pure-IR {@link StvnSchema}.
   *
   * @param schema the parent pure-IR schema to inspect, or {@code null}
   * @return an unmodifiable list of immediate child schemas
   * @since 2.0.0
   */
  public static List<StvnSchema> extractChildSchemas(@Nullable StvnSchema schema) {
    if (schema == null) {
      return List.of();
    }
    return switch (schema) {
      case StvnSchema.TupleSchema tup -> tup.fieldSchemas();
      case StvnSchema.OptionSchema opt -> List.of(opt.valueSchema());
      case StvnSchema.EitherSchema eith -> List.of(eith.leftSchema(), eith.rightSchema());
      case StvnSchema.UnionSchema un -> un.branchSchemas();
      case StvnSchema.SeqSchema seq -> List.of(seq.elementSchema());
      case StvnSchema.SetSchema set -> List.of(set.elementSchema());
      case StvnSchema.MapSchema map -> List.of(map.keySchema(), map.valueSchema());
      case StvnSchema.PoisonedSchema p ->
          p.bestEffortFallback().map(StvnBinaryDecoder::extractChildSchemas).orElse(List.of());
      default -> List.of();
    };
  }

  /**
   * Returns the fixed inline wire slot size in bytes for a pure-IR {@link StvnSchema},
   * or {@code 0} if values of this schema are stored out-of-line via offset pointers.
   *
   * @param schema the pure-IR schema to inspect, or {@code null}
   * @return the inline footprint size in bytes, or {@code 0} for out-of-line reference types
   * @since 2.0.0
   */
  public static int getInlineSize(@Nullable StvnSchema schema) {
    return getInlineSize(schema, Map.of());
  }

  private static int getInlineSize(@Nullable StvnSchema rawSchema, Map<String, StvnSchema> nominalScope) {
    if (rawSchema == null) {
      return 0;
    }
    StvnSchema schema = resolveNominalBackEdge(rawSchema, nominalScope);
    return switch (schema) {
      case StvnSchema.BooleanSchema ignored -> 1;
      case StvnSchema.FloatSchema f -> switch (f.representation()) {
        case IEEE_32 -> 4;
        case IEEE_64 -> 8;
        case EXACT_DECIMAL -> 0;
      };
      case StvnSchema.TimeEpochSchema te -> te.scale() == TemporalScale.NS ? 16 : 8;
      case StvnSchema.IntSchema i -> {
        if (i.bitWidth().compareTo(BigInteger.ONE) < 0) {
          throw new MalformedPayloadException("Non-positive bit width: " + i.bitWidth());
        }
        if (i.bitWidth().compareTo(MAX_INLINE_BIT_WIDTH) > 0) {
          throw new MalformedPayloadException("Integer bit-width exceeds wire allocation limit: " + i.bitWidth());
        }
        int n = i.bitWidth().intValueExact();
        yield (n + 7) / 8;
      }
      case StvnSchema.EnumSchema en -> enumOrdinalByteSize(en.rootVariants().size());
      case StvnSchema.EnumSubsetSchema sub -> enumOrdinalByteSize(sub.rootEnum().rootVariants().size());
      default -> 0;
    };
  }

  private static int enumOrdinalByteSize(int variantCount) {
    int count = Math.max(1, variantCount);
    if (count <= 256) {
      return 1;
    }
    if (count <= 65536) {
      return 2;
    }
    return 4;
  }

  // ===========================================================================
  // 6. EAGER UNPACKER IMPLEMENTATION
  // ===========================================================================

  private static void collectNominalSchemas(
      StvnSchema schema,
      Map<String, StvnSchema> nominalScope,
      Set<StvnSchema> visited
  ) {
    if (!visited.add(schema)) {
      return;
    }
    if (!(schema instanceof StvnSchema.PoisonedSchema) && schema.aliasName().isPresent()) {
      nominalScope.putIfAbsent(schema.aliasName().get(), schema);
    }
    for (StvnSchema child : extractChildSchemas(schema)) {
      collectNominalSchemas(child, nominalScope, visited);
    }
  }

  private static StvnSchema resolveNominalBackEdge(StvnSchema schema, Map<String, StvnSchema> nominalScope) {
    StvnSchema current = schema;
    for (int hop = 0; hop < 32; hop++) {
      if (current instanceof StvnSchema.PoisonedSchema p
          && StvnPayloadLowerer.RECURSIVE_BACK_EDGE_SENTINEL.equals(p.primaryDiagnosticCode())
          && p.aliasName().isPresent()) {
        StvnSchema target = nominalScope.get(p.aliasName().get());
        if (target != null && target != current) {
          current = target;
          continue;
        }
      }
      break;
    }
    return current;
  }

  private static StvnValue readInlineNode(
      DecodeContext ctx,
      int offset,
      StvnSchema rawSchema,
      Map<String, StvnSchema> nominalScope
  ) {
    StvnSchema schema = resolveNominalBackEdge(rawSchema, nominalScope);
    return switch (schema) {
      case StvnSchema.BooleanSchema boolSchema -> {
        validateAllocationBounds(1, ctx.buffer(), offset);
        yield new StvnValue.StvnBoolean(boolSchema, ctx.buffer().get(offset) != 0);
      }

      case StvnSchema.FloatSchema floatSchema -> {
        if (floatSchema.representation() == FloatRepresentation.IEEE_32) {
          validateAllocationBounds(4, ctx.buffer(), offset);
          float f = ctx.buffer().getFloat(offset);
          yield decodeIeee32Float(floatSchema, f);
        } else if (floatSchema.representation() == FloatRepresentation.IEEE_64) {
          validateAllocationBounds(8, ctx.buffer(), offset);
          double d = ctx.buffer().getDouble(offset);
          yield decodeIeee64Float(floatSchema, d);
        } else {
          throw new StvnSerializationException("EXACT_DECIMAL FloatSchema must be unpacked out-of-line");
        }
      }

      case StvnSchema.TimeEpochSchema teSchema -> {
        int byteSize = teSchema.scale() == TemporalScale.NS ? 16 : 8;
        validateAllocationBounds(byteSize, ctx.buffer(), offset);

        byte[] bigEndian = new byte[byteSize];
        for (int i = 0; i < byteSize; i++) {
          bigEndian[byteSize - 1 - i] = ctx.buffer().get(offset + i);
        }
        BigInteger ticks = new BigInteger(bigEndian);

        if (teSchema.explicitMinIncl().isPresent() && ticks.compareTo(teSchema.explicitMinIncl().get()) < 0) {
          throw new MalformedPayloadException(
              "Decoded :TimeEpoch tick " + ticks + " violates #minIncl " + teSchema.explicitMinIncl().get());
        }
        if (teSchema.explicitMaxExcl().isPresent() && ticks.compareTo(teSchema.explicitMaxExcl().get()) >= 0) {
          throw new MalformedPayloadException(
              "Decoded :TimeEpoch tick " + ticks + " violates #maxExcl " + teSchema.explicitMaxExcl().get());
        }
        yield new StvnValue.StvnTimeEpoch(teSchema, ticks, teSchema.scale());
      }

      case StvnSchema.IntSchema intSchema -> {
        int size = getInlineSize(intSchema, nominalScope);
        validateAllocationBounds(size, ctx.buffer(), offset);

        byte[] bigEndian = new byte[size];
        for (int i = 0; i < size; i++) {
          bigEndian[size - 1 - i] = ctx.buffer().get(offset + i);
        }

        int bitWidth = intSchema.bitWidth().intValueExact();
        boolean isUnsigned = intSchema.unsigned();

        validateHighBitMask(bigEndian[0], bitWidth, isUnsigned);

        BigInteger val;
        if (isUnsigned) {
          if ((bigEndian[0] & 0x80) != 0) {
            byte[] uBigEndian = new byte[size + 1];
            uBigEndian[0] = 0x00;
            System.arraycopy(bigEndian, 0, uBigEndian, 1, size);
            val = new BigInteger(uBigEndian);
          } else {
            val = new BigInteger(bigEndian);
          }
        } else {
          int remainderBits = bitWidth % 8;
          if (remainderBits != 0) {
            int signBit = (Byte.toUnsignedInt(bigEndian[0]) >> (remainderBits - 1)) & 1;
            if (signBit == 1) {
              int validMask = (1 << remainderBits) - 1;
              int upperMask = ~validMask & 0xFF;
              bigEndian[0] = (byte) (bigEndian[0] | upperMask);
            }
          }
          val = new BigInteger(bigEndian);
        }

        if (!intSchema.acceptsWidth(val)) {
          throw new MalformedPayloadException(
              "Decoded integer " + val + " exceeds " + bitWidth + "-bit schema capacity");
        }
        if (intSchema.explicitMinIncl().isPresent() && val.compareTo(intSchema.explicitMinIncl().get()) < 0) {
          throw new MalformedPayloadException(
              "Decoded integer " + val + " violates #minIncl " + intSchema.explicitMinIncl().get());
        }
        if (intSchema.explicitMaxExcl().isPresent() && val.compareTo(intSchema.explicitMaxExcl().get()) >= 0) {
          throw new MalformedPayloadException(
              "Decoded integer " + val + " violates #maxExcl " + intSchema.explicitMaxExcl().get());
        }
        yield new StvnValue.StvnInt(intSchema, val);
      }

      case StvnSchema.EnumSchema enumSchema -> {
        int size = getInlineSize(enumSchema, nominalScope);
        validateAllocationBounds(size, ctx.buffer(), offset);
        int seqIndex = switch (size) {
          case 1 -> Byte.toUnsignedInt(ctx.buffer().get(offset));
          case 2 -> Short.toUnsignedInt(ctx.buffer().getShort(offset));
          case 4 -> ctx.buffer().getInt(offset);
          default -> throw new IllegalStateException("Unsupported enum slot size: " + size);
        };
        List<String> variants = enumSchema.rootVariants();
        if (seqIndex < 0 || seqIndex >= variants.size()) {
          throw new MalformedPayloadException(
              "Decoded enum ordinal " + seqIndex + " out of bounds for "
                  + enumSchema.aliasName().orElse(StvnVocabulary.TYPE_ENUM));
        }
        yield new StvnValue.StvnEnum(enumSchema, variants.get(seqIndex), BigInteger.valueOf(seqIndex));
      }

      case StvnSchema.EnumSubsetSchema subsetSchema -> {
        int size = getInlineSize(subsetSchema, nominalScope);
        validateAllocationBounds(size, ctx.buffer(), offset);
        int seqIndex = switch (size) {
          case 1 -> Byte.toUnsignedInt(ctx.buffer().get(offset));
          case 2 -> Short.toUnsignedInt(ctx.buffer().getShort(offset));
          case 4 -> ctx.buffer().getInt(offset);
          default -> throw new IllegalStateException("Unsupported enum subset slot size: " + size);
        };
        List<String> rootVariants = subsetSchema.rootEnum().rootVariants();
        if (seqIndex < 0 || seqIndex >= rootVariants.size()) {
          throw new MalformedPayloadException(
              "Decoded enum ordinal " + seqIndex + " out of root bounds for "
                  + subsetSchema.aliasName().orElse(StvnVocabulary.TYPE_ENUM));
        }
        String kw = rootVariants.get(seqIndex);
        if (!subsetSchema.activeVariants().contains(kw)) {
          throw new MalformedPayloadException(
              "Decoded enum ordinal " + seqIndex + " (" + kw + ") violates active subset boundary for "
                  + subsetSchema.aliasName().orElse(StvnVocabulary.TYPE_ENUM));
        }
        yield new StvnValue.StvnEnum(subsetSchema, kw, BigInteger.valueOf(seqIndex));
      }

      default -> throw new StvnSerializationException("Unsupported inline unpack schema: " + schema.getClass().getSimpleName());
    };
  }

  private static StvnValue.StvnFloat decodeIeee32Float(StvnSchema.FloatSchema schema, float f) {
    if (Float.isNaN(f)) {
      return new StvnValue.StvnFloat(schema, BigDecimal.ZERO, false, Ieee754SpecialKind.NAN);
    }
    if (f == Float.POSITIVE_INFINITY) {
      return new StvnValue.StvnFloat(schema, BigDecimal.ZERO, false, Ieee754SpecialKind.POSITIVE_INFINITY);
    }
    if (f == Float.NEGATIVE_INFINITY) {
      return new StvnValue.StvnFloat(schema, BigDecimal.ZERO, false, Ieee754SpecialKind.NEGATIVE_INFINITY);
    }
    if (Float.floatToRawIntBits(f) == Float.floatToRawIntBits(-0.0f)) {
      return new StvnValue.StvnFloat(schema, new BigDecimal("0.0"), true, Ieee754SpecialKind.FINITE);
    }
    return new StvnValue.StvnFloat(schema, new BigDecimal(Float.toString(f)), false, Ieee754SpecialKind.FINITE);
  }

  private static StvnValue.StvnFloat decodeIeee64Float(StvnSchema.FloatSchema schema, double d) {
    if (Double.isNaN(d)) {
      return new StvnValue.StvnFloat(schema, BigDecimal.ZERO, false, Ieee754SpecialKind.NAN);
    }
    if (d == Double.POSITIVE_INFINITY) {
      return new StvnValue.StvnFloat(schema, BigDecimal.ZERO, false, Ieee754SpecialKind.POSITIVE_INFINITY);
    }
    if (d == Double.NEGATIVE_INFINITY) {
      return new StvnValue.StvnFloat(schema, BigDecimal.ZERO, false, Ieee754SpecialKind.NEGATIVE_INFINITY);
    }
    if (Double.doubleToRawLongBits(d) == Double.doubleToRawLongBits(-0.0d)) {
      return new StvnValue.StvnFloat(schema, new BigDecimal("0.0"), true, Ieee754SpecialKind.FINITE);
    }
    return new StvnValue.StvnFloat(schema, BigDecimal.valueOf(d), false, Ieee754SpecialKind.FINITE);
  }

  private static StvnValue readOutlinedNode(
      DecodeContext ctx,
      int offset,
      StvnSchema rawSchema,
      Map<String, StvnSchema> nominalScope,
      int depth
  ) {
    if (depth > MAX_DECODE_RECURSION_DEPTH) {
      throw new MalformedPayloadException(
          "Maximum binary pointer dereference depth exceeded (" + MAX_DECODE_RECURSION_DEPTH + ")");
    }
    StvnSchema schema = resolveNominalBackEdge(rawSchema, nominalScope);

    return switch (schema) {
      case StvnSchema.StringSchema stringSchema -> {
        String rawText = readStringOutlined(ctx, offset);
        yield new StvnValue.StvnString(stringSchema, rawText);
      }

      case StvnSchema.FloatSchema floatSchema -> {
        String rawText = readStringOutlined(ctx, offset);
        yield switch (rawText) {
          case "NaN" -> new StvnValue.StvnFloat(floatSchema, BigDecimal.ZERO, false, Ieee754SpecialKind.NAN);
          case "+Infinity", "Infinity" ->
              new StvnValue.StvnFloat(floatSchema, BigDecimal.ZERO, false, Ieee754SpecialKind.POSITIVE_INFINITY);
          case "-Infinity" ->
              new StvnValue.StvnFloat(floatSchema, BigDecimal.ZERO, false, Ieee754SpecialKind.NEGATIVE_INFINITY);
          default -> {
            BigDecimal dec = StvnLiteralParser.parseBigDecimal(rawText);
            boolean negZero = rawText.startsWith("-") && dec.signum() == 0;
            yield new StvnValue.StvnFloat(
                floatSchema,
                negZero ? new BigDecimal("0.0") : dec,
                negZero,
                Ieee754SpecialKind.FINITE
            );
          }
        };
      }

      case StvnSchema.DateTimeSchema dtSchema -> {
        String rawText = readStringOutlined(ctx, offset);
        String quoted = "\"" + rawText + "\"";
        yield switch (dtSchema.mode()) {
          case OFFSET -> {
            var parsed = StvnLiteralParser.parseDateTimeOffset(quoted);
            ZoneOffset zoneOffset = parsed.value().getOffset();
            String canonical = StvnLiteralParser.formatCanonicalDateTime(
                DateTimeMode.OFFSET,
                parsed.value().toLocalDateTime(),
                Optional.of(zoneOffset),
                Optional.empty()
            );
            yield new StvnValue.StvnDateTime(
                dtSchema,
                DateTimeMode.OFFSET,
                parsed.value().toLocalDateTime(),
                Optional.of(zoneOffset),
                Optional.empty(),
                canonical
            );
          }
          case ZONED -> {
            var parsed = StvnLiteralParser.parseDateTimeZoned(quoted);
            String canonical = StvnLiteralParser.formatCanonicalDateTime(
                DateTimeMode.ZONED,
                parsed.localDateTime(),
                Optional.empty(),
                Optional.of(parsed.zoneId())
            );
            yield new StvnValue.StvnDateTime(
                dtSchema,
                DateTimeMode.ZONED,
                parsed.localDateTime(),
                Optional.empty(),
                Optional.of(parsed.zoneId()),
                canonical
            );
          }
          case AUDITED -> {
            var parsed = StvnLiteralParser.parseDateTimeAudited(quoted);
            ZoneOffset zoneOffset = parsed.offsetDateTime().getOffset();
            String canonical = StvnLiteralParser.formatCanonicalDateTime(
                DateTimeMode.AUDITED,
                parsed.offsetDateTime().toLocalDateTime(),
                Optional.of(zoneOffset),
                Optional.of(parsed.zoneId())
            );
            yield new StvnValue.StvnDateTime(
                dtSchema,
                DateTimeMode.AUDITED,
                parsed.offsetDateTime().toLocalDateTime(),
                Optional.of(zoneOffset),
                Optional.of(parsed.zoneId()),
                canonical
            );
          }
        };
      }

      case StvnSchema.TupleSchema tupleSchema -> {
        List<StvnSchema> childSchemas = tupleSchema.fieldSchemas();
        List<StvnValue> elements = new ArrayList<>(childSchemas.size());
        int currentSlotOffset = offset;

        for (StvnSchema rawChild : childSchemas) {
          StvnSchema childSchema = resolveNominalBackEdge(rawChild, nominalScope);
          int inline = getInlineSize(childSchema, nominalScope);
          if (inline > 0) {
            validateAllocationBounds(inline, ctx.buffer(), currentSlotOffset);
            elements.add(readInlineNode(ctx, currentSlotOffset, childSchema, nominalScope));
            currentSlotOffset += inline;
          } else {
            int payloadOffset = ctx.readPointer(currentSlotOffset);
            elements.add(readOutlinedNode(ctx, payloadOffset, childSchema, nominalScope, depth + 1));
            currentSlotOffset += ctx.offsetSize();
          }
        }
        yield new StvnValue.StvnTuple(tupleSchema, elements);
      }

      case StvnSchema.SeqSchema seqSchema -> {
        StvnSchema elementSchema = resolveNominalBackEdge(seqSchema.elementSchema(), nominalScope);
        StvnSeqReader reader = new StvnSeqReader(ctx, offset, new StvnSchema.SeqSchema(
            seqSchema.aliasName(), seqSchema.minSize(), seqSchema.maxSize(), elementSchema, seqSchema.traits()));
        List<StvnValue> elements = new ArrayList<>(reader.size());
        int inline = getInlineSize(elementSchema, nominalScope);

        for (int i = 0; i < reader.size(); i++) {
          int slotOffset = reader.getAbsoluteOffset(i);
          if (inline > 0) {
            elements.add(readInlineNode(ctx, slotOffset, elementSchema, nominalScope));
          } else {
            int payloadOffset = ctx.readPointer(slotOffset);
            elements.add(readOutlinedNode(ctx, payloadOffset, elementSchema, nominalScope, depth + 1));
          }
        }
        yield new StvnValue.StvnSeq(seqSchema, elements);
      }

      case StvnSchema.SetSchema setSchema -> {
        StvnSchema elementSchema = resolveNominalBackEdge(setSchema.elementSchema(), nominalScope);
        StvnSeqReader reader = new StvnSeqReader(ctx, offset, new StvnSchema.SetSchema(
            setSchema.aliasName(), setSchema.minSize(), setSchema.maxSize(), elementSchema, setSchema.traits()));
        List<StvnValue> elements = new ArrayList<>(reader.size());
        Set<Object> seenKeys = new LinkedHashSet<>();
        int inline = getInlineSize(elementSchema, nominalScope);

        for (int i = 0; i < reader.size(); i++) {
          int slotOffset = reader.getAbsoluteOffset(i);
          StvnValue el = (inline > 0)
              ? readInlineNode(ctx, slotOffset, elementSchema, nominalScope)
              : readOutlinedNode(ctx, ctx.readPointer(slotOffset), elementSchema, nominalScope, depth + 1);
          if (!seenKeys.add(StvnPayloadLowerer.canonicalEqualityKey(el))) {
            throw new MalformedPayloadException("Duplicate element detected in binary :Set payload: " + el);
          }
          elements.add(el);
        }
        yield new StvnValue.StvnSet(setSchema, elements);
      }

      case StvnSchema.MapSchema mapSchema -> {
        StvnSchema keySchema = resolveNominalBackEdge(mapSchema.keySchema(), nominalScope);
        StvnSchema valSchema = resolveNominalBackEdge(mapSchema.valueSchema(), nominalScope);
        StvnMapReader reader = new StvnMapReader(ctx, offset, new StvnSchema.MapSchema(
            mapSchema.aliasName(),
            mapSchema.invertible(),
            mapSchema.minSize(),
            mapSchema.maxSize(),
            keySchema,
            valSchema,
            mapSchema.traits()
        ));
        List<KeyValueEntry> entries = new ArrayList<>(reader.size());
        Set<Object> seenKeys = new LinkedHashSet<>();
        Set<Object> seenValues = new LinkedHashSet<>();
        int keyInline = getInlineSize(keySchema, nominalScope);
        int valInline = getInlineSize(valSchema, nominalScope);

        for (int i = 0; i < reader.size(); i++) {
          int keySlot = reader.keys.getAbsoluteOffset(i);
          StvnValue key = (keyInline > 0)
              ? readInlineNode(ctx, keySlot, keySchema, nominalScope)
              : readOutlinedNode(ctx, ctx.readPointer(keySlot), keySchema, nominalScope, depth + 1);
          if (!seenKeys.add(StvnPayloadLowerer.canonicalEqualityKey(key))) {
            throw new MalformedPayloadException("Duplicate key detected in binary :Map payload: " + key);
          }

          int valSlot = reader.values.getAbsoluteOffset(i);
          StvnValue value = (valInline > 0)
              ? readInlineNode(ctx, valSlot, valSchema, nominalScope)
              : readOutlinedNode(ctx, ctx.readPointer(valSlot), valSchema, nominalScope, depth + 1);
          if (mapSchema.invertible() && !seenValues.add(StvnPayloadLowerer.canonicalEqualityKey(value))) {
            throw new MalformedPayloadException("Duplicate value detected in invertible binary :Map payload: " + value);
          }

          entries.add(new KeyValueEntry(key, value));
        }
        yield new StvnValue.StvnMap(mapSchema, entries);
      }

      case StvnSchema.OptionSchema optSchema -> {
        validateAllocationBounds(1, ctx.buffer(), offset);
        byte tag = ctx.buffer().get(offset);
        if (tag == 0x00) {
          yield new StvnValue.StvnOption(optSchema, Optional.empty());
        }
        if (tag != 0x01) {
          throw new MalformedPayloadException("Invalid :Option wire tag byte: " + Byte.toUnsignedInt(tag));
        }
        StvnSchema valueSchema = resolveNominalBackEdge(optSchema.valueSchema(), nominalScope);
        int inline = getInlineSize(valueSchema, nominalScope);
        int payloadSlotOffset = offset + 1;
        StvnValue payload = (inline > 0)
            ? readInlineNode(ctx, payloadSlotOffset, valueSchema, nominalScope)
            : readOutlinedNode(ctx, ctx.readPointer(payloadSlotOffset), valueSchema, nominalScope, depth + 1);
        yield new StvnValue.StvnOption(optSchema, Optional.of(payload));
      }

      case StvnSchema.EitherSchema eitherSchema -> {
        validateAllocationBounds(1, ctx.buffer(), offset);
        byte tag = ctx.buffer().get(offset);
        if (tag != 0x00 && tag != 0x01) {
          throw new MalformedPayloadException("Invalid :Either wire tag byte: " + Byte.toUnsignedInt(tag));
        }
        boolean isRight = (tag == 0x01);
        StvnSchema activeSchema = resolveNominalBackEdge(
            isRight ? eitherSchema.rightSchema() : eitherSchema.leftSchema(),
            nominalScope
        );
        int inline = getInlineSize(activeSchema, nominalScope);
        int payloadSlotOffset = offset + 1;
        StvnValue payload = (inline > 0)
            ? readInlineNode(ctx, payloadSlotOffset, activeSchema, nominalScope)
            : readOutlinedNode(ctx, ctx.readPointer(payloadSlotOffset), activeSchema, nominalScope, depth + 1);
        yield new StvnValue.StvnEither(
            eitherSchema,
            isRight ? EitherBranch.RIGHT : EitherBranch.LEFT,
            payload
        );
      }

      case StvnSchema.UnionSchema unionSchema -> {
        validateAllocationBounds(1, ctx.buffer(), offset);
        int variantIndex = Byte.toUnsignedInt(ctx.buffer().get(offset));
        List<StvnSchema> branches = unionSchema.branchSchemas();
        if (variantIndex >= branches.size()) {
          throw new StvnSerializationException("Union variant index exceeds defined variants.");
        }
        StvnSchema activeSchema = resolveNominalBackEdge(branches.get(variantIndex), nominalScope);
        int inline = getInlineSize(activeSchema, nominalScope);
        int payloadSlotOffset = offset + 1;
        StvnValue payload = (inline > 0)
            ? readInlineNode(ctx, payloadSlotOffset, activeSchema, nominalScope)
            : readOutlinedNode(ctx, ctx.readPointer(payloadSlotOffset), activeSchema, nominalScope, depth + 1);
        yield new StvnValue.StvnUnion(unionSchema, BigInteger.valueOf(variantIndex + 1L), payload);
      }

      default -> throw new StvnSerializationException(
          "Unsupported or undefined schema for outlined decoding: " + schema.getClass().getSimpleName());
    };
  }

  private static void validateHighBitMask(byte mostSignificantByte, int bitWidth, boolean isUnsigned) {
    if (bitWidth <= 0) {
      return;
    }
    int remainderBits = bitWidth % 8;
    if (remainderBits != 0) {
      int validMask = (1 << remainderBits) - 1;
      int b0 = Byte.toUnsignedInt(mostSignificantByte);
      int upperMask = ~validMask & 0xFF;
      int upperBits = b0 & upperMask;

      if (isUnsigned) {
        if (upperBits != 0) {
          throw new StvnCorruptedBitPatternException(String.format(
              "Corrupted bit pattern: %d-bit unsigned integer has non-zero unused high bits (0x%02X with mask 0x%02X)",
              bitWidth, b0, validMask));
        }
      } else {
        int signBit = (b0 >> (remainderBits - 1)) & 1;
        if (signBit == 0) {
          if (upperBits != 0) {
            throw new StvnCorruptedBitPatternException(String.format(
                "Corrupted bit pattern: %d-bit signed integer has non-zero unused high bits (0x%02X with mask 0x%02X)",
                bitWidth, b0, validMask));
          }
        } else {
          if (upperBits != 0 && upperBits != upperMask) {
            throw new StvnCorruptedBitPatternException(String.format(
                "Corrupted bit pattern: %d-bit signed integer has inconsistent high bits (0x%02X with mask 0x%02X)",
                bitWidth, b0, validMask));
          }
        }
      }
    }
  }

  /**
   * Validates that the structural hash of a pure-IR {@link StvnSchema} matches the expected explicit fingerprint
   * carried by {@code strategy}.
   *
   * @param schema   the pure-IR schema to validate
   * @param strategy the expected identity strategy
   * @throws StvnSerializationException       if an {@link SchemaIdentityStrategy.ExplicitUuid} fingerprint mismatches
   * @throws PoisonedRegistryPayloadException if an {@link SchemaIdentityStrategy.ExplicitSha256} fingerprint mismatches
   * @since 2.0.0
   */
  public static void validateSchemaHash(StvnSchema schema, SchemaIdentityStrategy strategy) {
    Objects.requireNonNull(schema, "schema must not be null");
    Objects.requireNonNull(strategy, "strategy must not be null");

    if (strategy instanceof SchemaIdentityStrategy.ExplicitUuid(UUID uuid)) {
      UUID computed = StvnSchemaHasher.hashSchema(schema);
      if (!computed.equals(uuid)) {
        throw new StvnSerializationException(
            "Schema hash mismatch! Expected ExplicitUuid: " + uuid + ", but computed: " + computed);
      }
    } else if (strategy instanceof SchemaIdentityStrategy.ExplicitSha256(byte[] hash)) {
      byte[] computed = StvnSchemaHasher.computeSha256(schema);
      if (!Arrays.equals(computed, hash)) {
        throw new PoisonedRegistryPayloadException(
            "Schema hash mismatch! Expected ExplicitSha256, but computed hash did not match.");
      }
    }
  }

  private static StvnSchema compileInlineSchema(String schemaSourceText) {
    try {
      StvnCompilationResult<StvnDocument> res = StvnCompiler.compile(schemaSourceText);
      if (!res.isSuccess() || res.document().isEmpty()) {
        String msg = res.diagnostics().isEmpty()
            ? "Empty inline schema"
            : res.diagnostics().getFirst().message();
        throw new StvnSerializationException("Inline schema compilation error: " + msg);
      }
      StvnDocument doc = res.document().get();
      StvnSchema rootType = doc.rootType().orElseThrow(() ->
          new StvnSerializationException("Inline schema does not define a root type entry."));
      validateUnionDistinctness(rootType, Collections.newSetFromMap(new IdentityHashMap<>()));
      return rootType;
    } catch (StvnSerializationException se) {
      throw se;
    } catch (Exception e) {
      throw new StvnSerializationException("Failed to compile self-describing inline schema", e);
    }
  }

  private static void validateUnionDistinctness(StvnSchema schema, Set<StvnSchema> visited) {
    if (!visited.add(schema)) {
      return;
    }
    if (schema instanceof StvnSchema.UnionSchema unionSchema) {
      Set<String> layoutCategories = new LinkedHashSet<>();
      for (StvnSchema variant : unionSchema.branchSchemas()) {
        String category = getLayoutCategory(variant);
        if (!layoutCategories.add(category)) {
          throw new StvnSerializationException(
              "Union distinctness violation: Overlapping layout category '" + category + "' in Union: "
                  + unionSchema.aliasName().orElse(StvnVocabulary.TYPE_UNION));
        }
      }
    }
    for (StvnSchema child : extractChildSchemas(schema)) {
      validateUnionDistinctness(child, visited);
    }
  }

  private static String getLayoutCategory(StvnSchema schema) {
    return switch (schema) {
      case StvnSchema.StringSchema ignored -> "STRING";
      case StvnSchema.DateTimeSchema ignored -> "STRING";
      case StvnSchema.IntSchema ignored -> "INTEGER";
      case StvnSchema.TimeEpochSchema ignored -> "INTEGER";
      case StvnSchema.FloatSchema ignored -> "FLOAT";
      case StvnSchema.BooleanSchema ignored -> "BOOLEAN";
      case StvnSchema.TupleSchema ignored -> "TUPLE";
      case StvnSchema.SeqSchema ignored -> "SEQUENCE";
      case StvnSchema.SetSchema ignored -> "SEQUENCE";
      case StvnSchema.MapSchema ignored -> "MAP";
      case StvnSchema.EnumSchema ignored -> "ENUM";
      case StvnSchema.EnumSubsetSchema ignored -> "ENUM";
      case StvnSchema.OptionSchema ignored -> StvnVocabulary.TYPE_OPTION;
      case StvnSchema.EitherSchema ignored -> StvnVocabulary.TYPE_EITHER;
      case StvnSchema.UnionSchema ignored -> StvnVocabulary.TYPE_UNION;
      case StvnSchema.PoisonedSchema p -> p.aliasName().orElse("UNKNOWN");
    };
  }
}
