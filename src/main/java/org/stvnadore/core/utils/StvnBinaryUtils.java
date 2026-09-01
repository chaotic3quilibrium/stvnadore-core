package org.stvnadore.core.utils;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.validation.MalformedPayloadException;

/**
 * Diagnostic and binary utility methods for STVN payloads.
 *
 * @since 1.0.0
 */
@NullMarked
public final class StvnBinaryUtils {

  private StvnBinaryUtils() {
    // Utility class, non-instantiable
  }

  /**
   * Generates a formatted hexadecimal dump string of the provided STVN binary payload.
   *
   * @param payload the STVN binary payload
   * @return a formatted hexadecimal dump string
   * @throws MalformedPayloadException if the payload is null, empty, or lacks the mandatory "STVN" magic bytes
   */
  @SuppressWarnings("NullAway")
  public static String toHexDumpString(byte[] payload) {
    if (payload == null) {
      throw new MalformedPayloadException("Payload cannot be null");
    }
    if (payload.length == 0) {
      throw new MalformedPayloadException("Payload cannot be empty");
    }
    if (payload.length < 4
        || payload[0] != (byte) 'S'
        || payload[1] != (byte) 'T'
        || payload[2] != (byte) 'V'
        || payload[3] != (byte) 'N') {
      throw new MalformedPayloadException("Invalid magic preamble: expected 'STVN'");
    }

    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < payload.length; i += 16) {
      // 1. Memory address offset
      sb.append(String.format("%08x: ", i));

      // 2. Structured hex pairs
      int rowBytes = Math.min(16, payload.length - i);
      for (int j = 0; j < 16; j++) {
        if (j < rowBytes) {
          sb.append(String.format("%02x", payload[i + j]));
        } else {
          sb.append("  ");
        }

        if (j == 7) {
          sb.append("  "); // extra space between 8th and 9th bytes
        } else {
          sb.append(" ");
        }
      }

      // 3. ASCII margin
      sb.append(" |");
      for (int j = 0; j < 16; j++) {
        if (j < rowBytes) {
          byte b = payload[i + j];
          if (b >= 32 && b <= 126) {
            sb.append((char) b);
          } else {
            sb.append('.');
          }
        } else {
          sb.append(' ');
        }
      }
      sb.append("|\n");
    }

    return sb.toString();
  }
}
