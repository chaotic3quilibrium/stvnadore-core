package org.stvnadore.core.binary.readers;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.binary.StvnBinaryDecoder.DecodeContext;
import org.stvnadore.core.ir.StvnSchema;

import java.util.List;
import java.util.Objects;

/**
 * Zero-copy in-place reader for traversing STVN binary representations of {@code :Tuple} values.
 * <p>
 * Facilitates direct memory access semantics on the underlying byte buffer, resolving positional
 * field offsets statically at construction time and allowing out-of-order element queries without
 * generating intermediate garbage or instantiating IR nodes.
 *
 * @since 1.0.0
 */
@NullMarked
public final class StvnTupleReader implements StvnMemoryAccessor {

  private final DecodeContext ctx;
  private final int[] fieldOffsets;

  /**
   * Constructs a new {@code StvnTupleReader} resolving positional field offsets in-place.
   *
   * @param ctx        the decode context containing the raw buffer
   * @param baseOffset the start memory address of the tuple data block
   * @param schema     the tuple's normalized pure-IR {@link StvnSchema}
   */
  public StvnTupleReader(DecodeContext ctx, int baseOffset, StvnSchema schema) {
    this.ctx = Objects.requireNonNull(ctx, "ctx must not be null");
    Objects.requireNonNull(schema, "schema must not be null");
    List<StvnSchema> childSchemas = StvnBinaryDecoder.extractChildSchemas(schema);
    this.fieldOffsets = new int[childSchemas.size()];

    int currentOffset = baseOffset;
    for (int i = 0; i < childSchemas.size(); i++) {
      this.fieldOffsets[i] = currentOffset;
      int inline = StvnBinaryDecoder.getInlineSize(childSchemas.get(i));
      currentOffset += (inline > 0 ? inline : ctx.offsetSize());
    }
  }

  @Override
  public DecodeContext context() {
    return ctx;
  }

  @Override
  public int getAbsoluteOffset(int index) {
    return fieldOffsets[index];
  }

  /**
   * Returns the positional element count of this tuple structure.
   *
   * @return the tuple arity
   */
  public int size() {
    return fieldOffsets.length;
  }
}
