# STVN Architectural Specification 01: Language & AST Overview

**Document ID**: `STVN-SPEC-01`  
**Status**: Canonical Specification  
**Version**: 1.0.0  
**Compliance**: Mandatory across all STVN parsers, compilers, and IDE integrations.

---

## 1. Dual-Track Lexical Grammar

Strongly Typed Value Notation (STVN) partitions identifiers, tokens, and type constructors into two distinct physical tracks:

1. **The Typic Track (`:`)**:
   * All type constructors, nominal type declarations, structural annotations, and module keywords begin with a colon (`:`).
   * Examples: `:defs`, `:type`, `:body`, `:include`, `:Int32`, `:Uint49`, `:String`, `:Tuple`, `:Option`, `:Either`, `:Union`, `:Map`, `:MapInv`, `:DateTimeOffset`, `:DateTimeZoned`, `:DateTimeAudited`.
   * Type names support forward-slash namespaces (e.g., `:net/http/Status`).

2. **The Variable / Value Track (`#`)**:
   * All literal values, sum type algebraic tags, boolean literals, enum constants, and constant bindings begin with a hash (`#`).
   * Examples: `#TRUE`, `#FALSE`, `#Some`, `#None`, `#Left`, `#Right`, `#1`, `#2`, `#HTTP`, `#MAX_RETRY`.
   * Constant names support forward-slash namespaces (e.g., `#net/http/OK`).
   * Compile-time typed constant bindings in `:defs` bind immutable values: `#PORT :Uint16 8080`.

### Syntactic Comment Standard
STVN strictly mandates single-line comments (`// ...`). Multi-line block comments (`/* ... */`) are strictly prohibited in the grammar to eliminate lexical ambiguity during single-pass streaming.

---

## 2. Root Enclosure & File Extension Contract

Every text-based STVN document must enclose its content within a single root curly brace pair `{ ... }`.

| Extension | File Purpose | Required Sections | Prohibited Sections |
|:---|:---|:---|:---|
| `.stvn` | Primary Payload Document | `:type`, `:body` (Optional `:defs`) | N/A |
| `.stvn_incl` | Transitive Shared Module | `:defs` | `:type`, `:body` |
| `.stvn_inclf` | Flat Standalone Module | `:defs` | `:type`, `:body`, `:include` |
| `.stvn_bin` | Zero-Copy Binary Bytecode | Embedded Binary Header | N/A |
| `.stvn_cas` | CAS Storage Profile Envelope | `:Tuple( :String :String :String )` | N/A |

---

## 3. Nominal AST Record Model

In the Java 21 SDK (`org.stvnadore.core.ir.StvnValue`), all AST nodes are immutable records adhering to 100% `@NullMarked` JSpecify 1.0.0 compliance:

```java
package org.stvnadore.core.ir;

import org.jspecify.annotations.NullMarked;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.SequencedMap;
import java.util.SequencedSet;

@NullMarked
public sealed interface StvnValue {
  record StvnBoolean(boolean value) implements StvnValue {}
  record StvnInteger(BigInteger value, int bitWidth, boolean signed) implements StvnValue {}
  record StvnFloat(BigDecimal value) implements StvnValue {}
  record StvnString(String value) implements StvnValue {}
  record StvnTuple(List<StvnValue> elements) implements StvnValue {}
  record StvnSeq(List<StvnValue> elements) implements StvnValue {}
  record StvnSet(SequencedSet<StvnValue> elements) implements StvnValue {}
  record StvnMap(SequencedMap<StvnValue, StvnValue> entries) implements StvnValue {}
  record StvnDateTimeOffset(Instant instant, ZoneOffset offset) implements StvnValue {}
  record StvnDateTimeZoned(LocalDateTime localDateTime, ZoneId zoneId) implements StvnValue {}
  record StvnDateTimeAudited(LocalDateTime localDateTime, ZoneOffset offset, ZoneId zoneId) implements StvnValue {}
  record StvnError(String errorMessage, int startOffset, int endOffset) implements StvnValue {}
}
```

---

## 4. Sum Types vs. Product Types

### Sum Types (Algebraic Disjunctions)
* **Option Types (`:Option( :T )`)**: Represents either a present value (`#Some value`) or an absent value (`#None`). Inferred from untagged values via Rule A.
* **Either Types (`:Either( :L :R )`)**: Represents either a left value (`#Left value`) or a right value (`#Right value`). Inferred `#Right` via Rule B.
* **Algebraic Unions (`:Union( :T1 :T2 ... )`)**: Explicit indexed branch tags (`#1 val`, `#2 val`). Inferred structural branches via Rule F/G.
* **Enumerations (`:Enum [ #A #B #C ]`)**: Finite set of constant value keywords.

### Product Types (Algebraic Conjunctions)
* **Tuples (`:Tuple( :T1 ... :Tn )`)**: Heterogeneous ordered sequence enclosed in parentheses `( val1 ... valN )`.
* **Maps (`:Map( :K :V )`)**: Ordered sequence of unique key-value pairs enclosed in braces: `{ [ k1 v1 ] [ k2 v2 ] }`.
* **Invertible Maps (`:MapInv( :K :V )`)**: Enforces dual-set uniqueness (all keys unique AND all values unique). Duplicate keys trigger `DUPLICATE_MAP_KEY`; duplicate values trigger `DUPLICATE_INVERTED_MAP_VALUE`.
* **Sequences (`:Seq( :T )`)**: Homogeneous ordered list enclosed in brackets: `[ v1 v2 v3 ]`.
* **Sets (`:Set( :T )`)**: Homogeneous unique element collection enclosed in brackets: `[ v1 v2 v3 ]`.

### Strict Product Demarcation
Parentheses `( ... )` in STVN are strictly and exclusively product constructors (`:Tuple`). Parenthesizing a scalar variant (e.g., `#Some ( 42 )` for `:Option(:Uint32)`) is a fatal syntax error (`MalformedPayloadException`).

---

## 5. Arbitrary Bit-Width Numeric Systems

STVN natively supports arbitrary bit-width integers ($n \ge 1$):
* **Signed Integers (`:Int`$n$)**: Valid range $[-2^{n-1}, 2^{n-1}-1]$ (e.g., `:Int1`, `:Int7`, `:Int16`, `:Int64`).
* **Unsigned Integers (`:Uint`$n$)**: Valid range $[0, 2^n-1]$ (e.g., `:Uint3`, `:Uint4`, `:Uint7`, `:Uint10`, `:Uint49`, `:Uint128`).
* **High-Bit Binary Masking**: Binary decoders allocate $B = \lceil n/8 \rceil$ bytes and verify that unused upper bits in containment bytes are zero. If any unused high bit is set to 1, decoders reject the buffer immediately with `StvnCorruptedBitPatternException`.
* **Exact Decimals (`:FloatExact`)**: Preserves arbitrary-precision decimal representations without IEEE 754 floating-point rounding hazards.

---

## 6. Tripartite Temporal Architecture

STVN partitions date-time values into three mathematically orthogonal, unambiguous types:

1. **Physical Instant (`:DateTimeOffset`)**:
   * Absolute timeline point with numerical UTC offset (`"2026-03-15T08:00:00-05:00"`).
   * Zone brackets are strictly prohibited.
   * Binary wire size: 12 bytes (`epoch_utc_nanos: i64` + `offset_seconds: i32`).

2. **Civil Wall-Clock Schedule (`:DateTimeZoned`)**:
   * Human civil wall-clock time bound to an IANA time zone (`"2026-03-15T08:00:00[America/Chicago]"`).
   * Numerical offsets are prohibited. Rejects invalid timestamps falling into Daylight Saving Time (DST) spring-forward gaps at parse time.
   * Binary wire size: 10 bytes (`local_nanos: i64` + `zone_dict_id: u16`).

3. **Regulatory Audit Record (`:DateTimeAudited`)**:
   * Compliance record capturing both observed UTC offset and IANA jurisdiction (`"2026-03-15T08:00:00-05:00[America/Chicago]"`).
   * Validates offset consistency against IANA `ZoneRules` at compile time.
   * Binary wire size: 14 bytes (`local_nanos: i64` + `offset_seconds: i32` + `zone_dict_id: u16`).

---

## 7. Monadic Compilation & Diagnostic Accumulation

* **Monadic Results (`StvnCompilationResult`)**: Returns clean or partial ASTs alongside accumulated `StvnDiagnostic` frames.
* **Bounded Diagnostic Accumulator (`DiagnosticBag`)**: Memory-bounded accumulation suppresses runaway error floods and appends a sentinel `STVN_DIAG_LIMIT_EXCEEDED` warning.
* **Error-Tolerant AST Nodes (`StvnError`)**: Isolates semantic and syntax failures into localized `StvnError` leaves, allowing sibling nodes and surrounding structures to parse successfully.
