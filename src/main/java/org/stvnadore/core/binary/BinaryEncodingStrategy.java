package org.stvnadore.core.binary;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.binary.exceptions.UnsupportedEncodingStrategyException;

/**
 * Strategy defining the binary wire layout, framing, and compression format of an STVN binary payload.
 * <p>
 * Encoded within bits 6–4 of Header Byte 4 (Control Byte).
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
   * Returns the 3-bit unsigned numeric identifier code for this encoding strategy.
   *
   * @return the 3-bit strategy code (range {@code 0x0} to {@code 0x7})
   */
  public int code() {
    return code;
  }

  /**
   * Resolves the {@link BinaryEncodingStrategy} corresponding to the provided 3-bit code (bits 6..4).
   *
   * @param code the 3-bit strategy code
   * @return the matching encoding strategy
   * @throws UnsupportedEncodingStrategyException if the code does not map to any recognized encoding strategy
   */
  public static BinaryEncodingStrategy fromCode(int code) {
    return switch (code) {
      case 0x00 -> ZERO_COPY_POST_ORDER;
      case 0x07 -> throw new UnsupportedEncodingStrategyException(
          "Strategy 0x7 is reserved for multi-byte header extension"
      );
      default -> throw new UnsupportedEncodingStrategyException(
          String.format("Unsupported binary encoding strategy code: 0x%X (bits 6..4).", code),
          code
      );
    };
  }
}
