# STVN Core SDK (`stvnadore-core`)

[![STVN Core SDK](https://img.shields.io/badge/STVN-1.0.0-blue.svg)](https://github.com/chaotic3quilibrium/stvnadore_core/blob/main/docs/architecture/01_STVN_SPECIFICATION_OVERVIEW.md)
[![Java Version Compatibility](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Build Verification Status](https://img.shields.io/badge/Tests-371%20Passed-green.svg)](https://github.com/chaotic3quilibrium/stvnadore_core/blob/main/src/test/java/org/stvnadore/core/)
[![Null Safety](https://img.shields.io/badge/NullMarked-Tier%201%20Soundness-brightgreen.svg)](https://github.com/chaotic3quilibrium/stvnadore_core/blob/main/docs/architecture/SOUNDNESS_BOUNDARIES.md)

`stvnadore-core` is the high-performance, strongly typed value notation (STVN) engine and SDK for Java 21. Tailored for safety-critical environments demanding zero-copy binary serialization, algebraic type safety, deterministic content-addressable storage (CAS) fingerprinting, and value-oriented programming (VOP) models, `stvnadore-core` eliminates reference nulls and uninitialized states at compile-time and serialization boundaries.

The engine is 100% feature-complete, zero-warning compliant (`-Xlint:all -Werror`), and validated against a comprehensive 371-test verification suite spanning 19 test suites covering structural schema hashing, zero-trust binary negotiation, arbitrary bit-width integers, tripartite temporal models, and POJO-free record marshalling.

---

- Version: 1.0.0 - 2026.08.31

---

# Table of Contents <!-- omit in toc -->

<!-- TOC -->
* [STVN Core SDK (`stvnadore-core`)](#stvn-core-sdk-stvnadore-core)
* [Table of Contents <!-- omit in toc -->](#table-of-contents----omit-in-toc---)
  * [Installation](#installation)
  * [Canonical Architecture Specifications](#canonical-architecture-specifications)
  * [Quick Start: Record Marshalling & Compilation](#quick-start-record-marshalling--compilation)
    * [1. Define Your Target Record Profile](#1-define-your-target-record-profile)
    * [2. Execute Bidirectional Marshalling](#2-execute-bidirectional-marshalling)
    * [3. Monadic Compilation with Diagnostic Accumulation](#3-monadic-compilation-with-diagnostic-accumulation)
    * [Expected STVN String Output Format](#expected-stvn-string-output-format)
  * [The Seven Core Architectural Pillars](#the-seven-core-architectural-pillars)
    * [1. Dual-Track Syntax & Strict Product Demarcation](#1-dual-track-syntax--strict-product-demarcation)
    * [2. Value-Oriented Immutability & CAS Fingerprinting](#2-value-oriented-immutability--cas-fingerprinting)
    * [3. Arbitrary Bit-Width Numeric Systems](#3-arbitrary-bit-width-numeric-systems)
    * [4. Tripartite Temporal Architecture](#4-tripartite-temporal-architecture)
    * [5. Algebraic Sum Types & Sealed Interface Marshalling](#5-algebraic-sum-types--sealed-interface-marshalling)
    * [6. Monadic Diagnostic Accumulation & Error Recovery](#6-monadic-diagnostic-accumulation--error-recovery)
    * [7. Zero-Copy Protocol Engine & High-Bit Masking](#7-zero-copy-protocol-engine--high-bit-masking)
  * [9-Pathway Binary Wire Specification](#9-pathway-binary-wire-specification)
    * [Control Byte Wire Resolution Matrix](#control-byte-wire-resolution-matrix)
    * [Tripartite Temporal Binary Memory Layouts](#tripartite-temporal-binary-memory-layouts)
  * [Build & Verification](#build--verification)
    * [Execute Test Suite](#execute-test-suite)
    * [Compiler Quality Invariants](#compiler-quality-invariants)
* [Support](#support)
  * [License](#license)
    * [GNU AFFERO GENERAL PUBLIC LICENSE](#gnu-affero-general-public-license)
    * [REALLY HATE the GNU AFFERO GENERAL PUBLIC LICENSE, a.k.a. AGPLv3?](#really-hate-the-gnu-affero-general-public-license-aka-agplv3)
    * [FYI, I'd prefer to move stvnadore-core to an Apache 2.0 license](#fyi-id-prefer-to-move-stvnadore-core-to-an-apache-20-license)
    * [I'm not looking to win the lottery, I just don't want to work for free](#im-not-looking-to-win-the-lottery-i-just-dont-want-to-work-for-free)
<!-- TOC -->

---

## Installation

Add the following Maven dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>org.stvnadore</groupId>
    <artifactId>stvnadore-core</artifactId>
    <version>1.0.1</version>
</dependency>
```

**Java 21 Prerequisite:** `stvnadore-core` requires OpenJDK 21 or higher and provides a full JPMS module descriptor (`module org.stvnadore.core`).

---

## Canonical Architecture Specifications

The formal specifications governing the STVN grammar, CAS topology, binary protocols, and IDE integration are located in `docs/architecture/`:

1. [01_STVN_SPECIFICATION_OVERVIEW.md](https://github.com/chaotic3quilibrium/stvnadore_core/blob/main/docs/architecture/01_STVN_SPECIFICATION_OVERVIEW.md) — Dual-track grammar, AST records, algebraic sum/product types, and arbitrary bit-widths.
2. [02_CONTENT_ADDRESSABLE_STORAGE_REGISTRY.md](https://github.com/chaotic3quilibrium/stvnadore_core/blob/main/docs/architecture/02_CONTENT_ADDRESSABLE_STORAGE_REGISTRY.md) — 2/62 filesystem CAS sharding, envelope framing, PostgreSQL/H2 catalog DDL, and background projection sweeper.
3. [03_BINARY_ENCODING_AND_ZERO_TRUST.md](https://github.com/chaotic3quilibrium/stvnadore_core/blob/main/docs/architecture/03_BINARY_ENCODING_AND_ZERO_TRUST.md) — Byte 4 4:4 nibble control architecture, Strategy `0x07` zero-trust verification, and tripartite temporal binary layouts.
4. [04_INTELLIJ_PLUGIN_INTEGRATION.md](https://github.com/chaotic3quilibrium/stvnadore_core/blob/main/docs/architecture/04_INTELLIJ_PLUGIN_INTEGRATION.md) — Grammar-Kit PSI tree, EDT discipline, sub-token diagnostic annotator, and schema flattener.
5. [SOUNDNESS_BOUNDARIES.md](https://github.com/chaotic3quilibrium/stvnadore_core/blob/main/docs/architecture/SOUNDNESS_BOUNDARIES.md) — Nullability invariants, Tier 1 soundness, and JSpecify 1.0.0 boundary definitions.
6. [STVN_LANGUAGE_SPEC.md](https://github.com/chaotic3quilibrium/stvnadore_core/blob/main/docs/architecture/STVN_LANGUAGE_SPEC.md) — The complete data grammar specification.

---

## Quick Start: Record Marshalling & Compilation

`stvnadore-core` provides [StvnMapper](https://github.com/chaotic3quilibrium/stvnadore_core/blob/main/src/main/java/org/stvnadore/core/mapper/StvnMapper.java) to map Java 21 `record` classes to and from STVN representations symmetrically. The following example demonstrates record serialization, deserialization, arbitrary bit-width validation, and canonical string generation.

### 1. Define Your Target Record Profile

Use metadata annotations to declare structural and range constraints directly on your record components:

```java
package org.stvnadore.core.example;

import org.stvnadore.core.annotations.StvnBits;
import org.stvnadore.core.annotations.StvnInt;
import org.stvnadore.core.annotations.StvnString;

import java.math.BigInteger;
import java.util.Optional;

public record UserProfile(
    @StvnString(nonEmpty = true) String username,
    @StvnInt(minIncl = 18, maxIncl = 150) int age,
    @StvnBits(value = 49, unsigned = true) BigInteger accountId,
    Optional<String> bio
) {
}
```

### 2. Execute Bidirectional Marshalling

```java
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.mapper.StvnMapper;

import java.math.BigInteger;
import java.util.Optional;

public class Demonstration {
  public static void main(String[] args) {
    // 1. Compile schema context (Map from String keys to variant values)
    var schemaDoc = StvnCompiler.compile("""
        {
          :type :Map( :String :Union( :String :Int32 :Uint49 :Option( :String ) ) )
          :body {}
        }
        """).orElseThrow();
    var schema = schemaDoc.schema();

    // 2. Instantiate Java 21 Record
    var profile = new UserProfile(
        "stvn_engineer",
        28,
        BigInteger.valueOf(500_000_000_000L),
        Optional.of("Zero-copy zero-null VOP specialist")
    );

    // 3. Serialize Record to STVN IR
    StvnValue stvnValue = StvnMapper.toValue(profile, schema).orElseThrow();

    // 4. Generate deterministic, canonical STVN text output
    String canonicalStvn = StvnCompiler.toCanonicalString(stvnValue);
    System.out.println("Canonical STVN Output:");
    System.out.println(canonicalStvn);

    // 5. Compute cryptographic SHA-256 CAS fingerprint
    byte[] casFingerprint = StvnCompiler.computeCasFingerprint(stvnValue);
    System.out.printf("CAS SHA-256: %s%n", java.util.HexFormat.of().formatHex(casFingerprint));

    // 6. Deserialize STVN AST back into a strongly typed Java 21 Record
    StvnValue parsedValue = StvnCompiler.compile(canonicalStvn).orElseThrow();
    UserProfile restored = StvnMapper.fromValue(parsedValue, UserProfile.class, schema).orElseThrow();

    assert restored.username().equals("stvn_engineer");
    assert restored.age() == 28;
    assert restored.accountId().equals(BigInteger.valueOf(500_000_000_000L));
  }
}
```

### 3. Monadic Compilation with Diagnostic Accumulation

For language servers, tooling, and batch pipelines, `StvnCompiler.compileToResult()` accumulates all diagnostics without throwing runtime exceptions:

```java
var result = StvnCompiler.compileToResult(sourceCode);
if (result.isSuccess()) {
  StvnValue doc = result.orElseThrow();
  // Process verified AST...
} else {
  for (var diagnostic : result.diagnostics()) {
      System.err.printf(
          "[%s] Line %d:%d (Offset %d..%d): %s (Code: %s)%n",
          diagnostic.severity(),
          diagnostic.line(),
          diagnostic.column(),
          diagnostic.startOffset(),
          diagnostic.endOffset(),
          diagnostic.message(),
          diagnostic.errorCode().orElse("UNSPECIFIED")
      );
   }
}
```

### Expected STVN String Output Format

```stvn
{:type :Map(:String :Union(:String :Int32 :Uint49 :Option(:String))) :body {["username" "stvn_engineer"] ["age" 28] ["accountId" 500000000000] ["bio" "Zero-copy zero-null VOP specialist"]}}
```

---

## The Seven Core Architectural Pillars

### 1. Dual-Track Syntax & Strict Product Demarcation

STVN strictly segregates the lexical and semantic namespaces of types and values:

* **Type Constructors (`:`)**: The colon prefix sigil is reserved exclusively for type constructors, annotations, and module keywords (e.g., `:type`, `:defs`, `:Boolean`, `:String`, `:Tuple`, `:Union`).
* **Value Tokens & Constants (`#`)**: The hash prefix sigil is reserved for literal values, sum type algebraic tags, enum constants, and value constants in `:defs` (e.g., `#TRUE`, `#Some`, `#Left`, `#HTTP`, `#MAX_RETRY`).
* **Path-Delimited Identifiers**: Identifiers support forward-slash namespaces in both type space (`:net/http/Status`) and value space (`#net/http/OK`).
* **Typed Constants in `:defs`**: Immutably binds compile-time constants: `#PORT :Uint16 8080`.
* **Bare Variant Syntax vs. Strict Product Demarcation**: Sum variants apply directly to trailing values without function parentheses (`#Some 42`). Parentheses `( ... )` in STVN are strictly and exclusively product constructors (`:Tuple`). Parenthesizing a scalar variant (e.g., `#Some ( 42 )` for `:Option(:Uint32)`) triggers a fatal `MalformedPayloadException`.

### 2. Value-Oriented Immutability & CAS Fingerprinting

Data models represent pure, immutable values:

* **Deterministic Sequenced Ordering**: Collections in the AST ([StvnSet](https://github.com/chaotic3quilibrium/stvnadore_core/blob/main/src/main/java/org/stvnadore/core/ir/StvnValue.java#L647) and [StvnMap](https://github.com/chaotic3quilibrium/stvnadore_core/blob/main/src/main/java/org/stvnadore/core/ir/StvnValue.java#L716)) strictly enforce `java.util.SequencedSet` and `java.util.SequencedMap` and wrap them in unmodifiable decorators.
* **Invertible Bidirectional Maps (`:MapInv`)**: Enforces dual-set uniqueness: all keys must be unique **and** all values must be unique. Duplicate keys trigger `DUPLICATE_MAP_KEY`; duplicate values trigger `DUPLICATE_INVERTED_MAP_VALUE`.
* **Content-Addressable Storage (CAS)**: `StvnCompiler.computeCasFingerprint(StvnValue)` computes deterministic 32-byte SHA-256 fingerprints across canonically serialized representations.

### 3. Arbitrary Bit-Width Numeric Systems

STVN natively supports arbitrary bit-width integers ($n \ge 1$):

* **Signed & Unsigned Scalability**: `:Int`$n$ ($[-2^{n-1}, 2^{n-1}-1]$) and `:Uint`$n$ ($[0, 2^n-1]$) support non-power-of-two widths (e.g., `:Int1`, `:Int7`, `:Uint3`, `:Uint49`, `:Uint128`).
* **High-Bit Binary Masking**: Binary decoders calculate $B = \lceil n/8 \rceil$ bytes and verify that unused upper bits in containment bytes are zero, throwing `StvnCorruptedBitPatternException` on illegal bit patterns.
* **Exact Decimals**: `:FloatExact` preserves arbitrary-precision decimal representations without IEEE 754 floating-point rounding hazards.

### 4. Tripartite Temporal Architecture

STVN partitions date-time values into three mathematically orthogonal, unambiguous types:

* **Physical Instant (`:DateTimeOffset`)**: Absolute timeline point with numerical UTC offset (`"2026-03-15T08:00:00-05:00"`). Zone brackets are strictly prohibited. Binary wire size: 12 bytes.
* **Civil Wall-Clock Schedule (`:DateTimeZoned`)**: Human civil wall-clock time bound to an IANA time zone (`"2026-03-15T08:00:00[America/Chicago]"`). Numerical offsets are prohibited. Rejects invalid timestamps falling into Daylight Saving Time (DST) spring-forward gaps at parse time. Binary wire size: 10 bytes.
* **Regulatory Audit Record (`:DateTimeAudited`)**: Compliance record capturing both observed UTC offset and IANA jurisdiction (`"2026-03-15T08:00:00-05:00[America/Chicago]"`). Validates offset consistency against IANA `ZoneRules` at compile time. Binary wire size: 14 bytes.

### 5. Algebraic Sum Types & Sealed Interface Marshalling

* **Sum Type Families**: `:Option(T)`, `:Either(L R)`, `:Union(T1 ... Tn)`, and `:Enum[ #A #B ]`.
* **Sealed Interfaces as Native Unions**: `SealedInterfaceMapper` dynamically inspects permitted subclasses, sorting them alphabetically by fully qualified class name (`Class::getName`) for platform-agnostic, stable tag indices.
* **Optional Null Loophole Prevention**: Prohibits `null` references inside record components typed as `Optional`, throwing `MalformedPayloadException` at the perimeter.

### 6. Monadic Diagnostic Accumulation & Error Recovery

* **Monadic Results (`StvnCompilationResult`)**: Returns clean or partial ASTs alongside accumulated `StvnDiagnostic` frames.
* **Bounded Diagnostic Accumulator (`DiagnosticBag`)**: Memory-bounded accumulation suppresses runaway error floods and appends a sentinel `STVN_DIAG_LIMIT_EXCEEDED` warning.
* **Error-Tolerant AST Nodes (`StvnError`)**: Isolates semantic and syntax failures into localized `StvnError` leaves, allowing sibling nodes and surrounding structures to parse successfully.

### 7. Zero-Copy Protocol Engine & High-Bit Masking

* **9-Pathway Wire Negotiation**: Decodes payloads dynamically through 9 control-byte pathways using 1-based length prefixes.
* **Header IANA Zone Dictionary Pool**: Deduplicates time zone strings in `.stvn_bin` headers into 16-bit indices (`zone_dict_id: u16`), enabling zero-allocation JSR-310 `ZoneId` lookups.
* **Zero-Trust Hash Validation**: Verifies explicit UUID (`0x06`) and SHA-256 (`0x07`) headers against computed schema hashes before decoding payloads.

---

## 9-Pathway Binary Wire Specification

### Control Byte Wire Resolution Matrix

The low-level binary codec negotiating [SchemaIdentityStrategy](https://github.com/chaotic3quilibrium/stvnadore_core/blob/main/src/main/java/org/stvnadore/core/binary/SchemaIdentityStrategy.java) is governed by 9 control-byte pathways (header offset position 4, following `MAGIC_BYTES = 0x5354564E`):

| Control Byte | Schema Strategy        | Header Payload Structure                           | Verification & Action                                                                      |
|:-------------|:-----------------------|:---------------------------------------------------|:-------------------------------------------------------------------------------------------|
| **`0x00`**   | `UniversalDefault`     | None (0 bytes)                                     | Resolves payload against universal default schema context.                                 |
| **`0x01`**   | `UuidV8Hash`           | None (0 bytes)                                     | Out-of-band resolution referencing stable cached UUIDv8 hash.                              |
| **`0x02`**   | `Sha256Hash`           | None (0 bytes)                                     | Out-of-band resolution referencing stable cached SHA-256 fingerprint.                      |
| **`0x03`**   | `AsciiStringKey`       | 2-byte short (1-based length prefix) + ASCII bytes | Matches schema by looking up ASCII repository key.                                         |
| **`0x04`**   | `UnicodeStringKey`     | 2-byte short (1-based length prefix) + UTF-8 bytes | Matches schema by looking up UTF-8 repository key.                                         |
| **`0x05`**   | `UniversalVersion`     | 4-byte int (1-based version prefix)                | Matches schema using sequential 64-bit version integer.                                    |
| **`0x06`**   | `ExplicitUuid`         | 16-byte UUID value                                 | Zero-trust verification: Decoded UUID must match `StvnSchemaHasher.hashSchema(schema)`.    |
| **`0x07`**   | `ExplicitSha256`       | 32-byte SHA-256 hash                               | Zero-trust verification: Decoded hash must match `StvnSchemaHasher.computeSha256(schema)`. |
| **`0x08`**   | `SelfDescribingSchema` | 4-byte int (1-based length prefix) + UTF-8 string  | Ephemeral Sandbox: Compiles inline `.stvn_inclf` schema in-memory.                         |

### Tripartite Temporal Binary Memory Layouts

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

---

## Build & Verification

`stvnadore-core` adheres to strict zero-warning compilation and robust test coverage.

### Execute Test Suite

```bash
# Linux / macOS
./mvnw clean test

# Windows PowerShell
.\mvnw.cmd clean test
```

### Compiler Quality Invariants

* **Java Version:** JDK 21+ (`--release 21`).
* **Compiler Flags:** `-Xlint:all` and `-Werror` enforced on all compilation phases.
* **Null Safety:** Strict `@NullMarked` enforcement across all Tier 1 core packages.

---

# Support

**Website:** <https://github.com/chaotic3quilibrium/stvnadore_core/tree/main>

**Email:** [jim.oflaherty.jr@gmail.com](mailto:jim.oflaherty.jr+scrm@gmail.com)

---

## License

### [GNU AFFERO GENERAL PUBLIC LICENSE](https://github.com/chaotic3quilibrium/stvnadore_core/blob/main/LICENSE.md)

The stvnadore-core files are free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

You should have received a copy of the [GNU Affero General Public License](https://www.gnu.org/licenses/agpl-3.0.en.html) along with this program. If not, see <https://www.gnu.org/licenses/>.

---

### REALLY HATE the GNU AFFERO GENERAL PUBLIC LICENSE, a.k.a. AGPLv3?

- It was chosen entirely because of Amazon's/AWS's (and many other wealthy corporations) historic abuses and exploitation of FOSS (Free Open Source Software)
- No Worries, I'd Love to Work with You

If the AGPLv3 doesn't work for you, I would LOVE to work with you to generate a **custom/different/commercial/non-profit/government license** for stvnadore-core.

Please email: <jim.oflaherty.jr+scrml@gmail.com>, letting us know what license you would prefer. I am happy to discuss this with you.

---

### FYI, I'd prefer to move stvnadore-core to an Apache 2.0 license

---

### I'm not looking to win the lottery, I just don't want to work for free