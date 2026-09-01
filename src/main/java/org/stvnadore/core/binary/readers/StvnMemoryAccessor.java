package org.stvnadore.core.binary.readers;

import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.binary.StvnBinaryDecoder.DecodeContext;
import org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema;

import static org.stvnadore.core.binary.StvnBinaryDecoder.getInlineSize;

/**
 * Accessor interface providing zero-copy, in-place primitive extraction from raw STVN binary buffers.
 * <p>
 * This abstraction traverses byte buffers in-place without generating intermediate garbage,
 * converting raw binary segments directly to Java primitives.
 *
 * @since 1.0.0
 */
public interface StvnMemoryAccessor {

  /**
   * Retrieves the active decoding context associated with this accessor.
   *
   * @return the non-null {@link DecodeContext}
   */
  DecodeContext context();

  /**
   * Computes the absolute memory offset in the underlying buffer for a given component index.
   *
   * @param index the component index
   * @return the absolute byte offset
   */
  int getAbsoluteOffset(int index);

  // =========================================================================
  // PRIMITIVE EXTRACTORS
  // =========================================================================

  /**
   * Extracts a signed 8-bit integer value at the specified element index.
   *
   * @param index the element index
   * @return the raw 8-bit byte value
   */
  default byte getInt8(int index) {
    return context().buffer().get(getAbsoluteOffset(index));
  }

  /**
   * Extracts a signed 16-bit integer value at the specified element index.
   *
   * @param index the element index
   * @return the raw 16-bit short value
   */
  default short getInt16(int index) {
    return context().buffer().getShort(getAbsoluteOffset(index));
  }

  /**
   * Extracts a signed 32-bit integer value at the specified element index.
   *
   * @param index the element index
   * @return the raw 32-bit integer value
   */
  default int getInt32(int index) {
    return context().buffer().getInt(getAbsoluteOffset(index));
  }

  /**
   * Extracts a signed 64-bit integer value at the specified element index.
   *
   * @param index the element index
   * @return the raw 64-bit long value
   */
  default long getInt64(int index) {
    return context().buffer().getLong(getAbsoluteOffset(index));
  }

  /**
   * Extracts a 32-bit floating point value at the specified element index.
   *
   * @param index the element index
   * @return the 32-bit float value
   */
  default float getFloat32(int index) {
    return context().buffer().getFloat(getAbsoluteOffset(index));
  }

  /**
   * Extracts a 64-bit floating point value at the specified element index.
   *
   * @param index the element index
   * @return the 64-bit double value
   */
  default double getFloat64(int index) {
    return context().buffer().getDouble(getAbsoluteOffset(index));
  }

  /**
   * Extracts a boolean flag value at the specified element index.
   *
   * @param index the element index
   * @return {@code true} if the byte is non-zero, otherwise {@code false}
   */
  default boolean getBoolean(int index) {
    return context().buffer().get(getAbsoluteOffset(index)) != 0;
  }

  /**
   * Extracts a string value by dereferencing its outlined pointer at the specified element index.
   *
   * @param index the element index
   * @return the decoded Java String
   */
  default String getString(int index) {
    int stringPayloadOffset = getPointerDereference(index);
    return StvnBinaryDecoder.readStringOutlined(context(), stringPayloadOffset);
  }

  /**
   * Dereferences an outlined pointer at the specified element index, returning
   * the absolute target memory address of the referenced data block.
   *
   * @param index the element index
   * @return the absolute target byte offset in the buffer
   */
  default int getPointerDereference(int index) {
    return context().readPointer(getAbsoluteOffset(index));
  }

  // =========================================================================
  // NESTED READER FACTORIES
  // =========================================================================

  /**
   * Resolves a nested tuple reader at the specified element index using the provided schema.
   *
   * @param index       the element index
   * @param tupleSchema the resolved schema context for the nested tuple
   * @return a non-null {@link StvnTupleReader}
   */
  default StvnTupleReader getTuple(int index, ResolvedSchema tupleSchema) {
    int inline = getInlineSize(tupleSchema);
    int targetOffset = (inline > 0)
        ? getAbsoluteOffset(index)
        : getPointerDereference(index);
    return new StvnTupleReader(context(), targetOffset, tupleSchema);
  }

  /**
   * Resolves a nested sequence reader at the specified element index using the provided schema.
   *
   * @param index     the element index
   * @param seqSchema the resolved schema context for the nested sequence
   * @return a non-null {@link StvnSeqReader}
   */
  default StvnSeqReader getSeq(int index, ResolvedSchema seqSchema) {
    int inline = getInlineSize(seqSchema);
    int targetOffset = (inline > 0)
        ? getAbsoluteOffset(index)
        : getPointerDereference(index);
    return new StvnSeqReader(context(), targetOffset, seqSchema);
  }

  /**
   * Resolves a nested map reader at the specified element index using the provided schema.
   *
   * @param index     the element index
   * @param mapSchema the resolved schema context for the nested map
   * @return a non-null {@link StvnMapReader}
   */
  default StvnMapReader getMap(int index, ResolvedSchema mapSchema) {
    int inline = getInlineSize(mapSchema);
    int targetOffset = (inline > 0)
        ? getAbsoluteOffset(index)
        : getPointerDereference(index);
    return new StvnMapReader(context(), targetOffset, mapSchema);
  }
}
