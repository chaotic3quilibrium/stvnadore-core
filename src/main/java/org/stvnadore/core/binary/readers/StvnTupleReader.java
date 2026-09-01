package org.stvnadore.core.binary.readers;

import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.binary.StvnBinaryDecoder.DecodeContext;
import org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema;

import java.util.List;

/**
 * Zero-copy in-place reader for traversing STVN binary representation of Tuples.
 * <p>
 * This class facilitates direct memory access semantics on the underlying byte buffer,
 * resolving field offsets statically at construction time and allowing out-of-order element
 * queries without generating intermediate garbage or instantiating AST nodes.
 *
 * @since 1.0.0
 */
public final class StvnTupleReader implements StvnMemoryAccessor {

  private final DecodeContext ctx;
  private final int[] fieldOffsets;

  /**
   * Constructs a new StvnTupleReader resolving positional field offsets in-place.
   *
   * @param ctx        the decode context containing the raw buffer
   * @param baseOffset the start memory address of the tuple data block
   * @param schema     the tuple's resolved schema configuration
   */
  public StvnTupleReader(DecodeContext ctx, int baseOffset, ResolvedSchema schema) {
    this.ctx = ctx;
    List<ResolvedSchema> childSchemas = StvnBinaryDecoder.extractChildSchemas(schema);
    this.fieldOffsets = new int[childSchemas.size()];

    int currentOffset = baseOffset;
    for (int i = 0; i < childSchemas.size(); i++) {
      this.fieldOffsets[i] = currentOffset;
      int inline = StvnBinaryDecoder.getInlineSize(childSchemas.get(i));

      // Advance by the inline primitive size, or the dynamic pointer size
      currentOffset += (inline > 0
          ? inline
          : ctx.offsetSize());
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public DecodeContext context() {
    return ctx;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getAbsoluteOffset(int index) {
    return fieldOffsets[index];
  }

  /**
   * Returns the positional element size of this tuple structure.
   *
   * @return the tuple size
   */
  public int size() {
    return fieldOffsets.length;
  }
}
