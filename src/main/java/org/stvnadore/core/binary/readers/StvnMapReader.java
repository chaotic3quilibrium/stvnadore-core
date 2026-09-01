package org.stvnadore.core.binary.readers;

import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.binary.StvnBinaryDecoder.DecodeContext;
import org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema;

import java.util.List;

/**
 * Zero-copy in-place reader for traversing STVN binary representation of Maps.
 * <p>
 * This class facilitates direct memory access semantics on the underlying byte buffer,
 * exposing separate {@link StvnMemoryAccessor} instances for keys and values segments.
 * This structure allows out-of-order map entry queries without generating intermediate garbage
 * or instantiating AST nodes.
 *
 * <h2>Unsigned 1-Based Length Format</h2>
 * Map entry count is encoded using an unsigned 1-based format ({@code length = value + 1}).
 * A stored byte/word value of {@code 0} indicates a map length of {@code 1} entry.
 * This optimizes space and prevents buffer overrun exploits by eliminating reliance on null termination.
 *
 * @since 1.0.0
 */
public final class StvnMapReader {

  private final int length;

  /**
   * Accessor providing zero-copy primitive lookup on the contiguous array of Map Keys.
   */
  public final StvnMemoryAccessor keys;

  /**
   * Accessor providing zero-copy primitive lookup on the contiguous array of Map Values.
   */
  public final StvnMemoryAccessor values;

  /**
   * Constructs a new StvnMapReader resolving key and value strides and length boundaries.
   *
   * @param ctx        the decode context containing the raw buffer
   * @param baseOffset the start memory address of the map data block
   * @param schema     the map's resolved schema configuration
   */
  public StvnMapReader(DecodeContext ctx, int baseOffset, ResolvedSchema schema) {
    StvnBinaryDecoder.LengthResult lr = StvnBinaryDecoder.readDerivedLengthPrefix(ctx.buffer(), baseOffset);
    this.length = lr.length();
    int keysStartOffset = baseOffset + lr.bytesConsumed();

    List<ResolvedSchema> childSchemas = StvnBinaryDecoder.extractChildSchemas(schema);
    ResolvedSchema keySchema = childSchemas.isEmpty()
        ? null
        : childSchemas.get(0);
    ResolvedSchema valueSchema = childSchemas.size() > 1
        ? childSchemas.get(1)
        : null;

    int kInline = StvnBinaryDecoder.getInlineSize(keySchema);
    int keyStride = (kInline > 0)
        ? kInline
        : ctx.offsetSize();

    int vInline = StvnBinaryDecoder.getInlineSize(valueSchema);
    int valueStride = (vInline > 0)
        ? vInline
        : ctx.offsetSize();

    // Values array starts exactly after the Keys array finishes
    int valuesStartOffset = keysStartOffset + (this.length * keyStride);

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
