# STVN Architectural Specification 01: Language & Canonical IR Overview

- **Document ID**: `STVN-SPEC-01`
- **Status**: Canonical Specification
- **Version**: 2.0.0
- **Compliance**: Mandatory across all STVN parsers, compilers, and IDE integrations.

---

**Table of Contents**

<!-- TOC -->
* [STVN Architectural Specification 01: Language & Canonical IR Overview](#stvn-architectural-specification-01-language--canonical-ir-overview)
  * [1. Dual-Track Lexical Grammar](#1-dual-track-lexical-grammar)
    * [Syntactic Comment Standard](#syntactic-comment-standard)
  * [2. Root Enclosure & File Extension Contract](#2-root-enclosure--file-extension-contract)
  * [3. Canonical IR Record Model (`org.stvnadore.core.ir`)](#3-canonical-ir-record-model-orgstvnadorecoreir)
  * [4. Sum Types vs. Product Types](#4-sum-types-vs-product-types)
    * [Sum Types (Algebraic Disjunctions)](#sum-types-algebraic-disjunctions)
    * [Product Types (Algebraic Conjunctions)](#product-types-algebraic-conjunctions)
    * [Strict Product Demarcation](#strict-product-demarcation)
  * [5. Arbitrary Bit-Width Numeric Systems](#5-arbitrary-bit-width-numeric-systems)
  * [6. Tripartite Temporal Architecture](#6-tripartite-temporal-architecture)
  * [7. Monadic Compilation & Diagnostic Accumulation](#7-monadic-compilation--diagnostic-accumulation)
  * [8. Transitive Canonicalization & Self-Containment Guarantees](#8-transitive-canonicalization--self-containment-guarantees)
<!-- TOC -->

---

## 1. Dual-Track Lexical Grammar

Strongly Typed Value Notation (STVN) partitions identifiers, tokens, and type constructors into two distinct physical tracks:

1. **The Typic Track (`:`)**:
   * The colon (`:`) is a bound prefix sigil attached directly to typic identifiers without intervening whitespace.
   * It is **never** a delimiter or infix operator. Standalone colons (`ERR_BARE_COLON_PROHIBITED`) and JSON-style key-value colons (`trap7_infix_colon.stvn`) are fatal syntax errors.
   * All type constructors, nominal type declarations, structural annotations, and module keywords begin with a colon (`:`).
   * Examples: `:meta`, `:defs`, `:type`, `:body`, `:include`, `:package`, `:use`, `:Int`, `:Float`, `:String`, `:Boolean`, `:TimeEpoch`, `:DateTime`, `:Tuple`, `:Option`, `:Either`, `:Union`, `:Enum`, `:Seq`, `:Set`, `:Map`.
   * Type names support forward-slash namespaces (e.g., `:net/http/Status`). Under STVN root clearance guarantees, all standard domain types reside in `:org/stvnadore/prelude/*`. Bare unqualified references trigger `ERR_UNKNOWN_TYPE`.

2. **The Variable / Value Track (`#`)**:
   * The hash (`#`) is a bound prefix sigil attached directly to value identifiers without intervening whitespace.
   * It is **never** a delimiter or comment marker. Standalone hashes (`ERR_BARE_HASH_PROHIBITED`) and shell-style hash comments (`trap6_hash_comment.stvn`) are fatal syntax errors.
   * All literal values, sum type algebraic tags, boolean literals, metadata facet tags, enum constants, and constant bindings begin with a hash (`#`).
   * Examples: `#TRUE`, `#FALSE`, `#T`, `#F`, `#Some`, `#None`, `#Left`, `#Right`, `#1`, `#2`, `#HTTP`, `#MAX_RETRY`.
   * Constant names support forward-slash namespaces (e.g., `#net/http/OK`).
   * Compile-time typed constant bindings in `:defs` bind immutable values: `#PORT { #unsigned #size 16 } :Int 8080`.

3. **Exhaustive Structural Delimiters**:
   * The *only* structural grouping delimiters in STVN are `{`, `}`, `[`, `]`, `(`, and `)`.
   * Delimitation between sibling values is exclusively whitespace.
   * Namespace hierarchy is delimited exclusively by the forward slash (`/`).

### Syntactic Comment Standard
STVN strictly mandates single-line comments (`// ...`). Multi-line block comments (`/* ... */`) are strictly prohibited in the grammar to eliminate lexical ambiguity during single-pass streaming.

---

## 2. Root Enclosure & File Extension Contract

Every text-based STVN document must enclose its content within a single root curly brace pair `{ ... }`. STVN 2.0.0 defines strictly six file extensions:

| Extension  | Document Kind   | Encoding | Required Sections   | Prohibited Sections |
|:-----------|:----------------|:---------|:--------------------|:--------------------|
| `.stvn`    | `#BODY`         | Textual  | `:type`, `:body`    | `:include`          |
| `.stvn_i`  | `#BODY_INCLUDE` | Textual  | `:type`, `:body`    | None                |
| `.stvn_d`  | `#DEFS`         | Textual  | `:defs`             | `:type`, `:body`, `:include` |
| `.stvn_di` | `#DEFS_INCLUDE` | Textual  | `:defs`             | `:type`, `:body`    |
| `.stvn_b`  | `#BODY`         | Binary   | Header + Payload    | `:include`          |
| `.stvn_bd` | `#DEFS`         | Binary   | Header + Payload    | `:include`, `:body` |

Content-Addressable Storage (CAS) envelope documents and Intermediate Representation (IR) snapshots use standard `.stvn` or `.stvn_i` extensions paired with reserved system domains (`"stvn_cas"` and `"stvn_ir"`).

---

## 3. Canonical IR Record Model (`org.stvnadore.core.ir`)

In the Java 21 SDK (`org.stvnadore.core.ir`), all Canonical IR nodes are immutable, ANTLR-decoupled records adhering to 100% `@NullMarked` JSpecify 1.0.0 compliance and the CPU-Bias-Free Expression Space Invariant (`java.math.BigInteger` and `java.math.BigDecimal` exclusively; zero hardware-sized primitives for STVN domain quantities):

```java
package org.stvnadore.core.ir;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnDiagnostic;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@NullMarked
public sealed interface StvnValue {
  StvnSchema schema();

  record StvnBoolean(StvnSchema schema, boolean value) implements StvnValue {}
  record StvnInt(StvnSchema schema, BigInteger value) implements StvnValue {}
  record StvnFloat(StvnSchema schema, BigDecimal decimalValue, boolean negativeZero, Ieee754SpecialKind specialKind) implements StvnValue {}
  record StvnString(StvnSchema schema, String value) implements StvnValue {}
  record StvnTimeEpoch(StvnSchema schema, BigInteger epochTicks, StvnSchema.TemporalScale scale) implements StvnValue {}
  record StvnDateTime(StvnSchema schema, StvnSchema.DateTimeMode mode, LocalDateTime localWallClock, Optional<ZoneOffset> offset, Optional<ZoneId> zone, String canonicalIsoText) implements StvnValue {}
  record StvnEnum(StvnSchema schema, String variantTag, BigInteger rootOrdinal) implements StvnValue {}
  record StvnTuple(StvnSchema schema, List<StvnValue> elements) implements StvnValue {}
  record StvnOption(StvnSchema schema, Optional<StvnValue> value) implements StvnValue {}
  record StvnEither(StvnSchema schema, EitherBranch branch, StvnValue value) implements StvnValue {}
  record StvnUnion(StvnSchema schema, BigInteger oneBasedBranchIndex, StvnValue value) implements StvnValue {}
  record StvnSeq(StvnSchema schema, List<StvnValue> elements) implements StvnValue {}
  record StvnSet(StvnSchema schema, List<StvnValue> elements) implements StvnValue {}
  record StvnMap(StvnSchema schema, List<MapEntryValue> entries) implements StvnValue {}
  record StvnErrorValue(StvnSchema schema, StvnDiagnostic diagnostic) implements StvnValue {}
}
```

---

## 4. Sum Types vs. Product Types

### Sum Types (Algebraic Disjunctions)
* **Option Types (`:Option( :T )`)**: Represents either a present value (`#Some value`) or an absent value (`#None`). Inferred from untagged values via Rule A.
* **Either Types (`:Either( :L :R )`)**: Represents either a left value (`#Left value`) or a right value (`#Right value`). Inferred `#Right` via Rule B; untagged Left payloads fail closed with `E023`.
* **Algebraic Unions (`:Union( :T1 :T2 ... )`)**: Explicit 1-based indexed branch tags (`#1 val`, `#2 val` using `BigInteger` branch indices). Inferred structural branches via Rule C/F/G.
* **Enumerations (`:Enum [ #A #B #C ]`)**: Finite set of constant value keywords with 0-based `BigInteger` root ordinals.

### Product Types (Algebraic Conjunctions)
* **Tuples (`:Tuple( :T1 ... :Tn )`)**: Heterogeneous ordered sequence enclosed in parentheses `( val1 ... valN )`.
* **Maps (`:Map( :K :V )`)**: Ordered sequence of unique key-value pairs enclosed in braces: `{ [ k1 v1 ] [ k2 v2 ] }`.
* **Invertible Maps (`{ #invertible } :Map( :K :V )`)**: Enforces dual-set uniqueness (all keys unique AND all values unique). Duplicate values trigger `E018` (`INVERTIBLE_MAP_DUPLICATE`).
* **Sequences (`:Seq( :T )`)**: Homogeneous ordered list enclosed in brackets: `[ v1 v2 v3 ]`.
* **Sets (`:Set( :T )`)**: Homogeneous unique element collection enclosed in brackets: `[ v1 v2 v3 ]`.

### Strict Product Demarcation
Parentheses `( ... )` in STVN are strictly and exclusively tuple constructors (`:Tuple`). Parenthesizing a scalar variant (e.g., `#Some ( 42 )` for `:Option( { #unsigned } :Int )`) is a fatal semantic error.

---

## 5. Arbitrary Bit-Width Numeric Systems

STVN natively supports arbitrary bit-width integers ($s \ge 1$, open upper bound in `BigInteger` expression space) using prefix metadata facets:
* **Signed Integers (`{ #size s } :Int`)**: Valid half-open range $[-2^{s-1}, 2^{s-1})$ (e.g., `{ #size 8 } :Int`, `{ #size 16 } :Int`, `{ #size 4096 } :Int`).
* **Unsigned Integers (`{ #unsigned #size s } :Int`)**: Valid half-open range $[0, 2^s)$ (e.g., `{ #unsigned #size 8 } :Int`, `{ #unsigned #size 16 } :Int`, `{ #unsigned #size 49 } :Int`).
* **Symbolic $2^s$ Capacity Verification**: Semantic validators verify $s$-bit capacity symbolically via `BigInteger.bitLength()` in $O(1)$ space without materializing $2^s$.
* **High-Bit Binary Masking**: Binary decoders allocate $B = \lceil s/8 \rceil$ bytes and verify that unused upper bits in containment bytes are zero. If any unused high bit is set to 1, decoders reject the buffer immediately with `StvnCorruptedBitPatternException`.
* **Exact Decimals (`{ #exact } :Float`)**: Preserves arbitrary-precision `BigDecimal` representations without IEEE-754 floating-point rounding hazards.

---

## 6. Tripartite Temporal Architecture

STVN elevates date-time values into base primitive types `:DateTime` and `:TimeEpoch` with mandatory standalone prefix metadata facets:

1. **Physical Instant (`{ #offset } :DateTime`)**:
   * Absolute timeline point with numerical UTC offset (`"2026-03-15T08:00:00-05:00"`).
   * Zone brackets are strictly prohibited.
   * Binary wire size: 12 bytes (`epoch_utc_nanos: i64` + `offset_seconds: i32`).

2. **Civil Wall-Clock Schedule (`{ #zoned } :DateTime`)**:
   * Human civil wall-clock time bound to an IANA time zone (`"2026-03-15T08:00:00[America/Chicago]"`).
   * Numerical offsets are prohibited (`E019`). Rejects invalid timestamps falling into Daylight Saving Time (DST) spring-forward gaps (`E022`).
   * Binary wire size: 10 bytes (`local_nanos: i64` + `zone_dict_id: u16`).

3. **Regulatory Audit Record (`{ #audited } :DateTime`)**:
   * Compliance record capturing both observed UTC offset and IANA jurisdiction (`"2026-03-15T08:00:00-05:00[America/Chicago]"`).
   * Validates offset consistency against IANA `ZoneRules` at compile time (`E021`).
   * Binary wire size: 14 bytes (`local_nanos: i64` + `offset_seconds: i32` + `zone_dict_id: u16`).

4. **Physical Epoch Timestamps (`{ #s } :TimeEpoch`, `{ #ms } :TimeEpoch`, `{ #us } :TimeEpoch`, `{ #ns } :TimeEpoch`)**:
   * Discrete physical epoch duration ticks since Unix epoch (1970-01-01T00:00:00Z), stored as `BigInteger` in the Canonical IR and lowered to 64-bit (`#s`, `#ms`, `#us`) or 128-bit (`#ns`) signed integers on the binary wire.
   * Requires exactly one standalone scale flag (`#s`, `#ms`, `#us`, or `#ns`). Omitting scale emits `ERR_TEMPORAL_SCALE_MISSING`.

---

## 7. Monadic Compilation & Diagnostic Accumulation

* **Monadic Results (`StvnCompilationResult` / `StvnAnalysisResult`)**: Returns validated Canonical IR (`StvnDocument`) alongside accumulated `StvnDiagnostic` frames (`canonicalCode` `E001`–`E040` / `W001`–`W003` and `symbolicId`).
* **Bounded Diagnostic Accumulator (`DiagnosticBag`)**: Memory-bounded accumulation suppresses runaway error floods and appends a sentinel `STVN_DIAG_LIMIT_EXCEEDED` warning.
* **Redundant Facet Normalization (`W003`)**: Tautological metadata facets emit non-blocking warning `W003` (`WARN_REDUNDANT_METADATA_FACET`) and are immediately purged from `StvnSchema` during IR lowering.
* **Fault-Isolated IR Sentinels (`PoisonedSchema` & `StvnErrorValue`)**: Isolates schema and child value failures into localized `PoisonedSchema` and `StvnErrorValue` nodes, preserving $M == N$ container cardinality so valid sibling elements validate without cascading noise.

---

## 8. Transitive Canonicalization & Self-Containment Guarantees

STVN serialization enforces mathematical determinism and hermetic document self-containment:

1. **Transitive Reachability Closure**:
   * Canonical IR serializers (`CanonicalStvnWriter`) compute the reachability closure of nominal types and typed constants in `StvnDefsCatalog`.
   * Traversal starts from the document root `:type` and `:body` payload.
   * The serializer retains all reachable intermediate nominal definitions in the `:defs` block.

2. **Dead-Code Elimination**:
   * Definitions not reachable from the root `:type` or `:body` are pruned (or rejected with `E029` under strict canonical validation).

3. **Universal Alias Desugaring**:
   * Local aliases declared via `:use` or `:include` desugar directly into Fully Qualified Nominal Identifiers (FQNIs).
   * Canonical documents emit zero `:package` or `:use` wrappers.

4. **Topological Dependency Ordering**:
   * Retained definitions emit in deterministic DFS post-order.
   * Dependencies strictly precede dependent definitions.
