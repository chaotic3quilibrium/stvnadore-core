# STVN Architectural Specification 03: Binary Encoding & Zero-Trust Verification

**Document ID**: `STVN-SPEC-03`  
**Status**: Canonical Specification  
**Version**: 1.0.0  
**Compliance**: Mandatory across all STVN binary encoders, decoders, zero-copy readers, and wire protocol bindings.

---

## 1. Binary Wire Framing (`.stvn_bin`)

The STVN binary stream is encoded in Little-Endian byte order with a deterministic header structure:

```
+-------------------+---------------+-----------------------+---------------+--------------------+
| Bytes 0-3 (4B)    | Byte 4 (1B)   | Bytes 5..N (0..Var B) | Byte N+1 (1B) | Bytes N+2.. (1..8B)|
| "STVN" Magic      | Control Byte  | Schema Identity Data  | Flags (Offset)| Root Node Pointer  |
+-------------------+---------------+-----------------------+---------------+--------------------+
```

### Byte 4 Control Byte (4:4 Nibble Split)
Byte 4 is partitioned into two 4-bit unsigned bitfields:

* **Upper Nibble (Bits 7..4, Mask `0xF0`)**: `BinaryEncodingStrategy`
  * `0x0`: `ZERO_COPY_POST_ORDER` (Standard zero-copy post-order layout).
  * `0x1`–`0xF`: Reserved for future compression and layout models.

* **Lower Nibble (Bits 3..0, Mask `0x0F`)**: `SchemaIdentityStrategy`
  * `0x0`: `UniversalDefault` (0 bytes payload). Resolves payload against universal default schema context.
  * `0x1`: `UuidV8Hash` (0 bytes payload). Out-of-band resolution referencing stable cached UUIDv8 hash.
  * `0x2`: `Sha256Hash` (0 bytes payload). Out-of-band resolution referencing stable cached SHA-256 fingerprint.
  * `0x3`: `AsciiStringKey` (2B length prefix + ASCII bytes). Matches schema by looking up ASCII repository key.
  * `0x4`: `UnicodeStringKey` (2B length prefix + UTF-8 bytes). Matches schema by looking up UTF-8 repository key.
  * `0x5`: `UniversalVersion` (4B version integer). Matches schema using sequential 64-bit version integer.
  * `0x6`: `ExplicitUuid` (16B UUID). Zero-trust verification: Decoded UUID must match `StvnSchemaHasher.hashSchema(schema)`.
  * `0x7`: `ExplicitSha256` (32B SHA-256 hash). Zero-trust verification: Decoded hash must match `StvnSchemaHasher.computeSha256(schema)`.
  * `0x8`: `SelfDescribingSchema` (4B length prefix + UTF-8 source string). Ephemeral sandbox: Compiles inline schema in-memory.

---

## 2. Zero-Trust Security Enforcement (Strategy `0x07`)

When decoding an STVN binary payload marked with Strategy `0x07` (`ExplicitSha256`):
1. `StvnBinaryDecoder.open()` reads the 37-byte header and extracts the embedded 32-byte SHA-256 digest at Header Bytes 5..36.
2. The decoder computes the cryptographic SHA-256 digest of the local resolved schema using `StvnSchemaHasher.computeSha256(schema)`.
3. If the computed digest does not match the embedded header digest byte-for-byte, the decoder immediately aborts and throws `PoisonedRegistryPayloadException`. Payload bytes are never read or deserialized.

---

## 3. Tripartite Temporal Wire Memory Layouts

STVN eliminates string parsing overhead for temporal types by embedding fixed-width binary representations:

```
1. :DateTimeOffset (12 Bytes Total)
   +---------------------------------------+---------------------------------------+
   |   epoch_utc_nanos: i64 (8 Bytes)      |     offset_seconds: i32 (4 Bytes)     |
   +---------------------------------------+---------------------------------------+

2. :DateTimeZoned (10 Bytes Total)
   +---------------------------------------+---------------------------------------+
   |     local_nanos: i64 (8 Bytes)        |     zone_dict_id: u16 (2 Bytes)       |
   +---------------------------------------+---------------------------------------+

3. :DateTimeAudited (14 Bytes Total)
   +-----------------------------------+-------------------+-------------------+
   |    local_nanos: i64 (8 Bytes)     | offset_s: i32 (4B)| zone_dict_id: u16 |
   +-----------------------------------+-------------------+-------------------+
```

### Header IANA Zone Dictionary Pool
To avoid repeating long time zone identifier strings (e.g. `"America/Argentina/Buenos_Aires"`), `.stvn_bin` binary headers contain an indexed zone dictionary table mapping unique `ZoneId` strings to 16-bit unsigned integers (`zone_dict_id: u16`), enabling zero-allocation JSR-310 lookups.

---

## 4. Arbitrary Bit-Width High-Bit Masking

For any arbitrary integer type `:Int`$n$ or `:Uint`$n$ ($n \ge 1$), the wire allocates $B = \lceil n/8 \rceil$ containment bytes in Little-Endian order.

* **High-Bit Zero Invariant**: Unused upper bits in the most significant byte (bits $n \pmod 8$ through 7 when $n \not\equiv 0 \pmod 8$) must be 0.
* **Corrupted Pattern Trap**: If any unused high bit is set to 1, decoders reject the buffer immediately with `StvnCorruptedBitPatternException`.
* **Zero-Copy Readers**: Reader flyweights (`StvnTupleReader`, `StvnSeqReader`, `StvnMapReader`) traverse nested buffers using direct memory offset pointers without intermediate heap allocations.
