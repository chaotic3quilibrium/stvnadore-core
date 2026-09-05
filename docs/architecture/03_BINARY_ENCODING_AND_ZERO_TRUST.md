 STVN Architectural Specification 03: Binary Encoding & Zero-Trust Verification

**Document ID**: `STVN-SPEC-03`
**Status**: Canonical Specification
**Version**: 1.0.2
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

---

## 5. Enum Subset Binary Wire Encoding & Zero-Copy Subtyping

STVN enum subsets achieve zero-copy polymorphism through root-relative ordinal preservation.

### 5.1 Root-Relative Ordinal Indexing
When serializing a payload value typed as an `EnumSubset`, the binary encoder emits the variant's sequential index relative to the **root `:Enum` declaration**, not a dense local ordinal.

* **Wire Byte Width:** The containment byte width matches the capacity required by the root enum ($N$ root variants):
  * $1 \le N \le 256$: 1 byte (`u8`).
  * $257 \le N \le 65536$: 2 bytes (`u16` Little-Endian).
  * $N > 65536$: 4 bytes (`u32` Little-Endian).
* **Ordinal Preservation:** An enum subset with 2 allowed variants derived from a root enum of 4 variants uses the root variant indices:

```
Root Enum: :Status :Enum [ #Pending #Active #Suspended #Deleted ]
Root Ordinals:               0        1         2          3

Subset: :ActiveStatus { #filterIncl [ #Active #Suspended ] } :Status
Payload: #Active
Wire Encoding: Byte value 0x01 (Root index 1, NOT local index 0)
```

### 5.2 Parent-Slot Zero-Copy Assignability
Because payloads serialize using root-relative ordinals and identical byte widths:
* Payloads typed with `:ActiveStatus` are byte-identical on the wire to payloads typed with `:Status`.
* Systems read and assign child subset payloads directly into storage slots typed as the parent enum without memory reallocation, trans-coding, or ordinal transformation.
* Transitive chains of arbitrary depth preserve this zero-overhead invariant.

### 5.3 Decoder Boundary Validation (`MalformedPayloadException`)
During payload deserialization, `StvnBinaryDecoder` enforces strict zero-trust boundary verification:

1. The decoder reads the root-relative ordinal index `seqIndex` from the input stream.
2. The decoder retrieves the variant keyword string from the root enum schema definition.
3. If the active target schema contains an `enumSubset`, the decoder evaluates:
   $$\text{seqIndex} \ge 0 \quad \land \quad \text{seqIndex} < N_{\text{root}} \quad \land \quad \text{subset.containsVariant}(\text{kw})$$
4. If the decoded ordinal references a variant that exists in the root enum but is excluded from the active subset, the decoder immediately aborts and throws `MalformedPayloadException`.
5. Undefined bytes and invalid ordinals are rejected before memory allocation or object instantiation occurs.

---

## 6. Cryptographic Schema Hashing for Enum Subsets

`StvnSchemaHasher` generates deterministic 32-byte SHA-256 fingerprints to identify schemas in Content-Addressable Storage (CAS) and Strategy `0x07` (`ExplicitSha256`) zero-trust binary headers.

### 6.1 Digest Ingestion Sequence
To prevent CAS hash collisions between distinct subsets or between a subset and its root enum, `StvnSchemaHasher.digestSchema()` digests subset metadata in strict sequential order:

1. **Base Primitive Type:** Emits UTF-8 string bytes for `:Enum`.
2. **Root Enum Variants:** For each variant keyword in the root enum definition, emits:
   ```
   "enumVariant:" + keyword
   ```
3. **Subset Identity & Derivation Metadata (if `enumSubset` is present):**
   * `"subsetName:" + subset.name()` (e.g., `subsetName::ActiveStatus`)
   * `"subsetParent:" + subset.parentType()` (e.g., `subsetParent::Status`)
   * `"subsetRoot:" + subset.rootEnum()` (e.g., `subsetRoot::Status`)
   * `"subsetFilterType:" + (subset.isInclusive() ? "incl" : "excl")`
4. **Allowed Variant Tokens:** For each variant in `subset.allowedVariants()`, in sorted declaration order, emits:
   ```
   "subsetVariant:" + variant
   ```
5. **Constraints & Traits:** Digests numeric limits, indent flags, and capability trait overrides in deterministic order.

Any modification to the allowed variant list, filter mode, parent link, or root enum changes the schema digest deterministically.
