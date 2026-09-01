package org.stvnadore.core.binary.readers;

import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.binary.StvnBinaryDecoder.DecodeContext;
import org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema;

import java.util.List;

/**
 * Zero-copy in-place reader for traversing STVN binary representation of Sequences (lists or sets).
 * <p>
 * This class facilitates direct memory access semantics on the underlying byte buffer,
 * calculating uniform stride element addresses dynamically and allowing out-of-order element
 * queries without generating intermediate garbage or instantiating AST nodes.
 *
 * <h2>Unsigned 1-Based Length Format</h2>
 * Sequence length is encoded using an unsigned 1-based format ({@code length = value + 1}).
 * A stored byte/word value of {@code 0} indicates a sequence length of {@code 1} element.
 * This optimizes storage space (avoiding representing empty states which are handled at schema
 * type boundary layers) and mitigates buffer overrun risks associated with null-terminated payloads.
 *
 * @since 1.0.0
 */
public final class StvnSeqReader implements StvnMemoryAccessor {

  private final DecodeContext ctx;
  private final int length;
  private final int dataStartOffset;
  private final int elementStride;

  /**
   * Constructs a new StvnSeqReader resolving element strides and length prefix boundaries.
   *
   * @param ctx        the decode context containing the raw buffer
   * @param baseOffset the start memory address of the sequence data block
   * @param schema     the sequence's resolved schema configuration
   */
  public StvnSeqReader(DecodeContext ctx, int baseOffset, ResolvedSchema schema) {
    this.ctx = ctx;

    // Parse the derived length prefix
    StvnBinaryDecoder.LengthResult lr = StvnBinaryDecoder.readDerivedLengthPrefix(ctx.buffer(), baseOffset);
    this.length = lr.length();
    this.dataStartOffset = baseOffset + lr.bytesConsumed();

    // Determine uniform element stride
    List<ResolvedSchema> childSchemas = StvnBinaryDecoder.extractChildSchemas(schema);
    ResolvedSchema elementSchema = childSchemas.isEmpty()
        ? null
        : childSchemas.getFirst();

    int inline = StvnBinaryDecoder.getInlineSize(elementSchema);
    this.elementStride = (inline > 0)
        ? inline
        : ctx.offsetSize();
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
    return dataStartOffset + (index * elementStride);
  }

  /**
   * Returns the count of elements in this sequence.
   *
   * @return the sequence size
   */
  public int size() {
    return length;
  }
}
