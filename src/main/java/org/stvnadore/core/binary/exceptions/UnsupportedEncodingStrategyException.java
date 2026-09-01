package org.stvnadore.core.binary.exceptions;

import org.jspecify.annotations.NullMarked;
import java.io.Serial;

/**
 * Exception thrown when an STVN binary payload declares an unrecognized or unsupported
 * {@link org.stvnadore.core.binary.BinaryEncodingStrategy} in the upper nibble of Header Byte 4.
 *
 * @since 1.0.0
 */
@NullMarked
public class UnsupportedEncodingStrategyException extends StvnSerializationException {
  @Serial
  private static final long serialVersionUID = 1004L;

  /**
   * The 4-bit upper nibble strategy code encountered in the binary header.
   */
  private final int strategyCode;

  /**
   * Constructs a new UnsupportedEncodingStrategyException with the specified detail message and unmapped strategy code.
   *
   * @param message      the detail message describing the unsupported encoding strategy
   * @param strategyCode the 4-bit upper nibble strategy code encountered
   */
  public UnsupportedEncodingStrategyException(String message, int strategyCode) {
    super(message);
    this.strategyCode = strategyCode;
  }

  /**
   * Constructs a new UnsupportedEncodingStrategyException with detail message, strategy code, and underlying cause.
   *
   * @param message      the detail message describing the unsupported encoding strategy
   * @param strategyCode the 4-bit upper nibble strategy code encountered
   * @param cause        the underlying cause of the exception
   */
  public UnsupportedEncodingStrategyException(String message, int strategyCode, Throwable cause) {
    super(message, cause);
    this.strategyCode = strategyCode;
  }

  /**
   * Returns the unmapped 4-bit strategy code that caused this exception.
   *
   * @return the strategy code
   */
  public int getStrategyCode() {
    return strategyCode;
  }
}
