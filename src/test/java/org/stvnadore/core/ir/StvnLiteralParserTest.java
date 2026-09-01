package org.stvnadore.core.ir;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Unit tests for {@link StvnLiteralParser} validating tripartite temporal parsing and pattern matching.
 *
 * @since 1.0.0
 */
@NullMarked
class StvnLiteralParserTest {

  @Test
  void testParseDateTimeOffsetValid() {
    var parsed = StvnLiteralParser.parseDateTimeOffset("\"2026-03-15T08:00:00-05:00\"");
    Assertions.assertEquals(OffsetDateTime.parse("2026-03-15T08:00:00-05:00"), parsed.value());
    Assertions.assertEquals("2026-03-15T08:00:00-05:00", parsed.rawText());

    var parsedUtc = StvnLiteralParser.parseDateTimeOffset("\"2026-03-15T13:00:00Z\"");
    Assertions.assertEquals(OffsetDateTime.parse("2026-03-15T13:00:00Z"), parsedUtc.value());
    Assertions.assertEquals("2026-03-15T13:00:00Z", parsedUtc.rawText());
  }

  @Test
  void testParseDateTimeOffsetInvalid() {
    // Rejects bracketed zone
    Assertions.assertThrows(IllegalArgumentException.class, () -> {
      StvnLiteralParser.parseDateTimeOffset("\"2026-03-15T08:00:00-05:00[America/Chicago]\"");
    });

    // Rejects non-ISO format
    Assertions.assertThrows(IllegalArgumentException.class, () -> {
      StvnLiteralParser.parseDateTimeOffset("\"2026/03/15 08:00:00\"");
    });

    // Rejects unquoted
    Assertions.assertThrows(IllegalArgumentException.class, () -> {
      StvnLiteralParser.parseDateTimeOffset("2026-03-15T08:00:00-05:00");
    });
  }

  @Test
  void testParseDateTimeZonedValid() {
    var parsed = StvnLiteralParser.parseDateTimeZoned("\"2026-03-15T08:00:00[America/Chicago]\"");
    Assertions.assertEquals(LocalDateTime.parse("2026-03-15T08:00:00"), parsed.localDateTime());
    Assertions.assertEquals(ZoneId.of("America/Chicago"), parsed.zoneId());
    Assertions.assertEquals("2026-03-15T08:00:00[America/Chicago]", parsed.rawText());

    var parsedLondon = StvnLiteralParser.parseDateTimeZoned("\"2026-08-18T18:30:00[Europe/London]\"");
    Assertions.assertEquals(LocalDateTime.parse("2026-08-18T18:30:00"), parsedLondon.localDateTime());
    Assertions.assertEquals(ZoneId.of("Europe/London"), parsedLondon.zoneId());
  }

  @Test
  void testParseDateTimeZonedInvalid() {
    // Rejects explicit numerical offset before bracketed zone
    Assertions.assertThrows(IllegalArgumentException.class, () -> {
      StvnLiteralParser.parseDateTimeZoned("\"2026-03-15T08:00:00-05:00[America/Chicago]\"");
    });

    // Rejects missing bracketed zone
    Assertions.assertThrows(IllegalArgumentException.class, () -> {
      StvnLiteralParser.parseDateTimeZoned("\"2026-03-15T08:00:00\"");
    });

    // Rejects invalid IANA zone syntax
    Assertions.assertThrows(Exception.class, () -> {
      StvnLiteralParser.parseDateTimeZoned("\"2026-03-15T08:00:00[Invalid/NonExistentZoneXYZ123]\"");
    });
  }

  @Test
  void testParseDateTimeAuditedValid() {
    var parsed = StvnLiteralParser.parseDateTimeAudited("\"2026-03-15T08:00:00-05:00[America/Chicago]\"");
    Assertions.assertEquals(OffsetDateTime.parse("2026-03-15T08:00:00-05:00"), parsed.offsetDateTime());
    Assertions.assertEquals(ZoneId.of("America/Chicago"), parsed.zoneId());
    Assertions.assertEquals("2026-03-15T08:00:00-05:00[America/Chicago]", parsed.rawText());

    var parsedUtc = StvnLiteralParser.parseDateTimeAudited("\"2026-03-15T13:00:00Z[UTC]\"");
    Assertions.assertEquals(OffsetDateTime.parse("2026-03-15T13:00:00Z"), parsedUtc.offsetDateTime());
    Assertions.assertEquals(ZoneId.of("UTC"), parsedUtc.zoneId());
  }

  @Test
  void testParseDateTimeAuditedInvalid() {
    // Rejects missing offset
    Assertions.assertThrows(IllegalArgumentException.class, () -> {
      StvnLiteralParser.parseDateTimeAudited("\"2026-03-15T08:00:00[America/Chicago]\"");
    });

    // Rejects missing zone
    Assertions.assertThrows(IllegalArgumentException.class, () -> {
      StvnLiteralParser.parseDateTimeAudited("\"2026-03-15T08:00:00-05:00\"");
    });
  }
}
