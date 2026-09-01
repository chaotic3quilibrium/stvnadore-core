package org.stvnadore.core.binary;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.binary.exceptions.UnsupportedEncodingStrategyException;

/**
 * Strategy defining the binary wire layout, framing, and compression format of an STVN binary payload.
 * <p>
 * Encoded within the upper nibble (bits 7–4) of Header Byte 4 (Control Byte).
 *
 * @since 1.0.0
 */
@NullMarked
public enum BinaryEncodingStrategy {

  /**
   * Standard zero-copy post-order binary encoding layout (Code {@code 0x00}).
   */
  ZERO_COPY_POST_ORDER(0x00);

  private final int code;

  BinaryEncodingStrategy(int code) {
    this.code = code;
  }

  /**
   * Returns the 4-bit unsigned numeric identifier code for this encoding strategy.
   *
   * @return the 4-bit strategy code (range {@code 0x0} to {@code 0xF})
   */
  public int code() {
    return code;
  }

  /**
   * Resolves the {@link BinaryEncodingStrategy} corresponding to the provided 4-bit upper nibble code.
   *
   * @param code the 4-bit upper nibble code
   * @return the matching encoding strategy
   * @throws UnsupportedEncodingStrategyException if the code does not map to any recognized encoding strategy
   */
  public static BinaryEncodingStrategy fromCode(int code) {
    return switch (code) {
      case 0x00 -> ZERO_COPY_POST_ORDER;
      default -> throw new UnsupportedEncodingStrategyException(
          String.format("Unsupported binary encoding strategy code: 0x%X (upper nibble).", code),
          code
      );
    };
  }
}
