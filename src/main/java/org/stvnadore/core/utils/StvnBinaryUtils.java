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

  /** Mandatory magic preamble bytes ('S', 'T', 'V', 'N' in Little-Endian). */
  public static final int MAGIC_BYTES = 0x5354564E;

  /** Bitmask for Bit 7 of Byte 4: HAS_TRAILER_CRC32C flag (0x80). */
  public static final int CONTROL_MASK_TRAILER_CRC32C = 0x80;
  /** Bitmask for Bits 6..4 of Byte 4: BinaryEncodingStrategy (0x70). */
  public static final int CONTROL_MASK_ENCODING_STRATEGY = 0x70;
  /** Bitmask for Bits 3..0 of Byte 4: SchemaIdentityStrategy (0x0F). */
  public static final int CONTROL_MASK_SCHEMA_IDENTITY = 0x0F;
  /** Strategy code 0x7 reserved as an extension sentinel. */
  public static final int ENCODING_STRATEGY_EXTENSION_SENTINEL = 0x07;

  /** Bitmask for Bits 1..0 of Byte 5: Document Kind (0x03). */
  public static final int META_MASK_KIND = 0x03;
  /** Bitmask for Bit 2 of Byte 5: HAS_NAME flag (0x04). */
  public static final int META_MASK_HAS_NAME = 0x04;
  /** Bitmask for Bit 3 of Byte 5: HAS_DOMAIN flag (0x08). */
  public static final int META_MASK_HAS_DOMAIN = 0x08;
  /** Bitmask for Bits 7..4 of Byte 5: RESERVED bits (0xF0). */
  public static final int META_MASK_RESERVED = 0xF0;

  /** Maximum permissible byte length for metadata #name and #domain identifier strings. */
  public static final int MAX_METADATA_IDENTIFIER_LENGTH = 64;

  /** Minimum header size for STVN binary stream (4B Magic + 1B Control + 1B MetaControl + 1B Flags + 1B RootPointer = 8B). */
  public static final int MIN_BINARY_HEADER_SIZE = 8;
  /** Minimum header capacity when CRC-32C trailer is enabled (8B Header + 4B CRC-32C = 12B). */
  public static final int MIN_CRC32C_BUFFER_CAPACITY = 12;
  /** Size in bytes of the CRC-32C trailer. */
  public static final int CRC32C_TRAILER_SIZE = 4;

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
