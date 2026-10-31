package org.stvnadore.core.binary.readers;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.binary.StvnBinaryDecoder.DecodeContext;
import org.stvnadore.core.ir.StvnSchema;

import java.util.List;
import java.util.Objects;

/**
 * Zero-copy in-place reader for traversing STVN binary representations of {@code :Seq} and {@code :Set} collections.
 * <p>
 * Facilitates direct memory access semantics on the underlying byte buffer, calculating uniform stride
 * element addresses dynamically and allowing out-of-order element queries without generating intermediate
 * garbage or instantiating IR nodes.
 *
 * @since 1.0.0
 */
@NullMarked
public final class StvnSeqReader implements StvnMemoryAccessor {

  private final DecodeContext ctx;
  private final int length;
  private final int dataStartOffset;
  private final int elementStride;

  /**
   * Constructs a new {@code StvnSeqReader} resolving element strides and length prefix boundaries.
   *
   * @param ctx        the decode context containing the raw buffer
   * @param baseOffset the start memory address of the sequence data block
   * @param schema     the sequence or set pure-IR {@link StvnSchema}
   */
  public StvnSeqReader(DecodeContext ctx, int baseOffset, StvnSchema schema) {
    this.ctx = Objects.requireNonNull(ctx, "ctx must not be null");
    Objects.requireNonNull(schema, "schema must not be null");

    StvnBinaryDecoder.LengthResult lr = StvnBinaryDecoder.readDerivedLengthPrefix(ctx.buffer(), baseOffset);
    this.length = lr.length();
    this.dataStartOffset = baseOffset + lr.bytesConsumed();

    List<StvnSchema> childSchemas = StvnBinaryDecoder.extractChildSchemas(schema);
    StvnSchema elementSchema = childSchemas.isEmpty() ? null : childSchemas.getFirst();

    int inline = StvnBinaryDecoder.getInlineSize(elementSchema);
    this.elementStride = (inline > 0) ? inline : ctx.offsetSize();

    if (this.length > 0) {
      StvnBinaryDecoder.validateAllocationBounds(
          (long) this.length * this.elementStride,
          ctx.buffer(),
          this.dataStartOffset
      );
    }
  }

  @Override
  public DecodeContext context() {
    return ctx;
  }

  @Override
  public int getAbsoluteOffset(int index) {
    return dataStartOffset + (index * elementStride);
  }

  /**
   * Returns the count of elements in this sequence or set.
   *
   * @return the element count
   */
  public int size() {
    return length;
  }
}
