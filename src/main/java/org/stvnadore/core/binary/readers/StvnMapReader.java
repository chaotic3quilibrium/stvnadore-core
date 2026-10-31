package org.stvnadore.core.binary.readers;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.binary.StvnBinaryDecoder.DecodeContext;
import org.stvnadore.core.ir.StvnSchema;

import java.util.List;
import java.util.Objects;

/**
 * Zero-copy in-place reader for traversing STVN binary representations of {@code :Map} collections.
 * <p>
 * Exposes separate {@link StvnMemoryAccessor} instances for contiguous key and value segments,
 * allowing out-of-order map entry queries without generating intermediate garbage or instantiating IR nodes.
 *
 * @since 1.0.0
 */
@NullMarked
public final class StvnMapReader {

  private final int length;

  /**
   * Accessor providing zero-copy lookup on the contiguous array of map keys.
   */
  public final StvnMemoryAccessor keys;

  /**
   * Accessor providing zero-copy lookup on the contiguous array of map values.
   */
  public final StvnMemoryAccessor values;

  /**
   * Constructs a new {@code StvnMapReader} resolving key and value strides and length boundaries.
   *
   * @param ctx        the decode context containing the raw buffer
   * @param baseOffset the start memory address of the map data block
   * @param schema     the map's pure-IR {@link StvnSchema}
   */
  public StvnMapReader(DecodeContext ctx, int baseOffset, StvnSchema schema) {
    Objects.requireNonNull(ctx, "ctx must not be null");
    Objects.requireNonNull(schema, "schema must not be null");

    StvnBinaryDecoder.LengthResult lr = StvnBinaryDecoder.readDerivedLengthPrefix(ctx.buffer(), baseOffset);
    this.length = lr.length();
    int keysStartOffset = baseOffset + lr.bytesConsumed();

    List<StvnSchema> childSchemas = StvnBinaryDecoder.extractChildSchemas(schema);
    StvnSchema keySchema = childSchemas.isEmpty() ? null : childSchemas.get(0);
    StvnSchema valueSchema = childSchemas.size() > 1 ? childSchemas.get(1) : null;

    int kInline = StvnBinaryDecoder.getInlineSize(keySchema);
    int keyStride = (kInline > 0) ? kInline : ctx.offsetSize();

    int vInline = StvnBinaryDecoder.getInlineSize(valueSchema);
    int valueStride = (vInline > 0) ? vInline : ctx.offsetSize();

    int valuesStartOffset = keysStartOffset + (this.length * keyStride);

    if (this.length > 0) {
      long totalMapBytes = (long) this.length * (keyStride + valueStride);
      StvnBinaryDecoder.validateAllocationBounds(totalMapBytes, ctx.buffer(), keysStartOffset);
    }

    this.keys = new StvnMemoryAccessor() {
      @Override
      public DecodeContext context() {
        return ctx;
      }

      @Override
      public int getAbsoluteOffset(int index) {
        return keysStartOffset + (index * keyStride);
      }
    };

    this.values = new StvnMemoryAccessor() {
      @Override
      public DecodeContext context() {
        return ctx;
      }

      @Override
      public int getAbsoluteOffset(int index) {
        return valuesStartOffset + (index * valueStride);
      }
    };
  }

  /**
   * Returns the count of entries in this map.
   *
   * @return the map size
   */
  public int size() {
    return length;
  }
}
