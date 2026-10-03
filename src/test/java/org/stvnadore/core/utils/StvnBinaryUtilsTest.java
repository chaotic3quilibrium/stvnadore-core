package org.stvnadore.core.utils;

import org.junit.jupiter.api.Test;
import org.stvnadore.core.validation.MalformedPayloadException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StvnBinaryUtilsTest {

  @Test
  @SuppressWarnings("NullAway")
  void testNullPayloadThrowsException() {
    assertThrows(MalformedPayloadException.class, () -> {
      StvnBinaryUtils.toHexDumpString(null);
    });
  }

  @Test
  void testEmptyPayloadThrowsException() {
    assertThrows(MalformedPayloadException.class, () -> {
      StvnBinaryUtils.toHexDumpString(new byte[0]);
    });
  }

  @Test
  void testMissingMagicBytesThrowsException() {
    byte[] badPayload = new byte[]{'B', 'A', 'D', '!', 'a', 'b', 'c'};
    assertThrows(MalformedPayloadException.class, () -> {
      StvnBinaryUtils.toHexDumpString(badPayload);
    });
  }

  @Test
  void testShortMagicBytesThrowsException() {
    byte[] badPayload = new byte[]{'S', 'T', 'V'};
    assertThrows(MalformedPayloadException.class, () -> {
      StvnBinaryUtils.toHexDumpString(badPayload);
    });
  }

  @Test
  void testValid16BytesHexDump() {
    byte[] payload = new byte[]{
        'S', 'T', 'V', 'N', 'a', 'b', 'c', 'd',
        'e', 'f', 'g', 'h', 0, 1, 10, 127
    };
    String expected = "00000000: 53 54 56 4e 61 62 63 64  65 66 67 68 00 01 0a 7f  |STVNabcdefgh....|\n";
    assertEquals(expected, StvnBinaryUtils.toHexDumpString(payload));
  }

  @Test
  void testPartialRowHexDump() {
    byte[] payload = new byte[]{
        'S', 'T', 'V', 'N', 'X'
    };
    // 5 bytes row
    String expected = "00000000: 53 54 56 4e 58                                    |STVNX           |\n";
    assertEquals(expected, StvnBinaryUtils.toHexDumpString(payload));
  }

  @Test
  void testMultipleRowsHexDump() {
    byte[] payload = new byte[]{
        'S', 'T', 'V', 'N', 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
        13, 14, 15, 16, 17, 18
    };
    String expected =
        "00000000: 53 54 56 4e 01 02 03 04  05 06 07 08 09 0a 0b 0c  |STVN............|\n" +
            "00000010: 0d 0e 0f 10 11 12                                 |......          |\n";
    assertEquals(expected, StvnBinaryUtils.toHexDumpString(payload));
  }
}
