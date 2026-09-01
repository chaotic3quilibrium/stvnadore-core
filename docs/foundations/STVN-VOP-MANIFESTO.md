# The Strongly Typed Value Notation (STVN) Value-Oriented Programming (VOP) Manifesto

**Target Path:** `docs/philosophy/STVN-VOP-MANIFESTO.md`

**Status:** Canonical Foundation

**Version:** 1.0.0

---

# Table of Contents <!-- omit in toc -->

<!-- TOC -->
* [The Strongly Typed Value Notation (STVN) Value-Oriented Programming (VOP) Manifesto](#the-strongly-typed-value-notation-stvn-value-oriented-programming-vop-manifesto)
* [Table of Contents <!-- omit in toc -->](#table-of-contents----omit-in-toc---)
  * [1. The Principle (The North Star)](#1-the-principle-the-north-star)
    * [1.1 Purpose and the Dual Integrity Postures](#11-purpose-and-the-dual-integrity-postures)
    * [1.2 Fundamental Definitions](#12-fundamental-definitions)
    * [1.3 Core Axioms](#13-core-axioms)
      * [Tenet A: Integrity by Default (Operational Safety Floor)](#tenet-a-integrity-by-default-operational-safety-floor)
        * [Axiom 1: Total Immutability](#axiom-1-total-immutability)
        * [Axiom 2: Parse at System Boundaries](#axiom-2-parse-at-system-boundaries)
        * [Axiom 3: Total Functions](#axiom-3-total-functions)
      * [Tenet B: Integrity by Design (Domain Structural Modeling)](#tenet-b-integrity-by-design-domain-structural-modeling)
        * [Axiom 4: Compile-Time Invariant Enforcement](#axiom-4-compile-time-invariant-enforcement)
    * [1.4 The North Star Standard](#14-the-north-star-standard)
  * [2. The Engineering Reality (The Normalization Paradox)](#2-the-engineering-reality-the-normalization-paradox)
    * [2.1 The Theoretical Trap](#21-the-theoretical-trap)
    * [2.2 The Database Normalization Paradox](#22-the-database-normalization-paradox)
    * [2.3 The Pragmatic Shift](#23-the-pragmatic-shift)
  * [3. Trade-Off Heuristics and Decision Criteria](#3-trade-off-heuristics-and-decision-criteria)
    * [3.1 The Enforcement Spectrum](#31-the-enforcement-spectrum)
    * [3.2 Decision Matrix](#32-decision-matrix)
    * [3.3 The Three Evaluative Filters](#33-the-three-evaluative-filters)
  * [4. The VOP Standard in STVN](#4-the-vop-standard-in-stvn)
    * [4.1 The Role of STVN in Value-Oriented Systems](#41-the-role-of-stvn-in-value-oriented-systems)
    * [4.2 Concrete STVN Pillars for VOP](#42-concrete-stvn-pillars-for-vop)
      * [Pillar 1: Structural Boundary Defense (Parse, Don't Validate)](#pillar-1-structural-boundary-defense-parse-dont-validate)
      * [Pillar 2: Mechanical Sympathy Without the Wrapper Tax](#pillar-2-mechanical-sympathy-without-the-wrapper-tax)
      * [Pillar 3: Canonical Value Equivalence](#pillar-3-canonical-value-equivalence)
      * [Pillar 4: Explicit Error Semantics](#pillar-4-explicit-error-semantics)
    * [4.3 The VOP Commitment](#43-the-vop-commitment)
<!-- TOC -->

---

## 1. The Principle (The North Star)

### 1.1 Purpose and the Dual Integrity Postures

The purpose of Value-Oriented Programming (VOP) is to eliminate invalid software states during program execution. The primary principle is:

> **Make illegal states unrepresentable.**

To execute this principle in real-world systems without excessive complexity, VOP establishes two complementary postures:

1. **Integrity by Default (The Operational Floor):** Baseline safety provided by runtime conventions, codecs, and foundational types without custom code. This posture eliminates technical failures: null pointer references, memory mutation hazards, race conditions, and implicit serialization errors.
2. **Integrity by Design (The Structural Ceiling):** Intentional type-level and domain-level modeling. This posture eliminates semantic failures: invalid business state transitions, illegal field combinations, and domain logic violations.

---

### 1.2 Fundamental Definitions

* **Value:** Data that does not change after creation. Equality depends only on the stored data, not on a memory address.
* **Identity:** A property that tracks an entity across state changes over time. Values do not have identity.
* **State:** The snapshot of all values present in a system at one specific time.
* **Invariant:** A condition that must always remain true for a value or state to be valid.
* **Integrity by Default:** Safety guarantees provided automatically by baseline conventions, libraries, and wire codecs.
* **Integrity by Design:** Safety guarantees built deliberately into the domain architecture using strong types and explicit boundary models.

---

### 1.3 Core Axioms

#### Tenet A: Integrity by Default (Operational Safety Floor)

##### Axiom 1: Total Immutability

* A value MUST NOT change after creation.
* A change operation MUST produce a new value.
* A value MUST NOT hold references to mutable state.

##### Axiom 2: Parse at System Boundaries

* A system MUST convert unverified external data into strongly typed values at the entry boundary.
* A component MUST NOT pass raw, unparsed data (such as generic strings or numbers) into internal logic.
* If parsing fails, the boundary parser MUST reject the input immediately and return an explicit diagnostic error.

##### Axiom 3: Total Functions

* A function MUST return a valid result for every input allowed by its type signature.
* A function MUST NOT throw runtime exceptions for expected business or validation failures.
* A function MUST use algebraic return types (such as `Result` or `Option`) to represent operations that can fail.

#### Tenet B: Integrity by Design (Domain Structural Modeling)

##### Axiom 4: Compile-Time Invariant Enforcement

* The type system MUST enforce domain invariants.
* A type definition MUST NOT permit the creation of invalid domain representations.
* If a domain rule changes, the type definition MUST change to prevent invalid states at compile time.

---

### 1.4 The North Star Standard

When software fulfills these axioms:

1. Runtime validation inside core domain logic becomes unnecessary.
2. Null references and null pointer exceptions are eliminated.
3. Concurrency conflicts caused by shared mutable state cannot occur.
4. The type system proves program correctness before code executes.

---

## 2. The Engineering Reality (The Normalization Paradox)

### 2.1 The Theoretical Trap

The strict maxim—*make illegal states unrepresentable*—is the ideal North Star of type-driven design. However, attempting to enforce every transient domain rule, arithmetic relationship, and inter-field dependency strictly at compile time produces diminishing engineering returns.

When a type system is forced to model all invariants at the type level, the codebase encounters severe practical friction:

* **Hyper-Fragmented Wrapper Hierarchies:** Deeply nested types obscure domain intent.
* **Ergonomic Overhead:** Excessive boilerplate for simple mappings, traversals, and property accesses.
* **Mechanical Inefficiency:** Increased object allocation and garbage collection churn in performance-sensitive pipelines.
* **Developer Circumvention:** Cognitive fatigue leads engineers to introduce unchecked escape hatches, raw type casts, or bypass layers.

---

### 2.2 The Database Normalization Paradox

Software engineering solved an identical philosophical dilemma in Relational Database Management Systems (RDBMS).

| Normal Form            | Relational Objective                                    | VOP Operational Reality                                                                                             | VOP Architectural Posture                         |
|:-----------------------|:--------------------------------------------------------|:--------------------------------------------------------------------------------------------------------------------|:--------------------------------------------------|
| **1NF / Unstructured** | Minimal rules, atomic values.                           | **Anarchy:** Redundancy, update anomalies, and structural data corruption are common.                               | **Uncontrolled Primitive Obsession**              |
| **3NF / BCNF**         | Functional dependency separation.                       | **The Pragmatic Equilibrium:** Eliminates anomalies while preserving acceptable join overhead and query ergonomics. | **Integrity by Default** (The Baseline Standard)  |
| **5NF (Project-Join)** | Total decomposition to eliminate all join dependencies. | **Theoretical Purity:** Multi-table joins for basic queries introduce severe latency and cognitive complexity.      | **Integrity by Design** (Targeted Specialization) |

Pure 5NF is mathematically rigorous, but production systems standardize on **3NF or Boyce-Codd Normal Form (BCNF)** as the operational equilibrium.

Value-Oriented Programming uses the same structural threshold. Full type-level theorem proving represents the "5NF" ceiling: mathematically airtight, but counterproductive if applied indiscriminately across an entire codebase.

---

### 2.3 The Pragmatic Shift

To balance theoretical safety with developer ergonomics and mechanical efficiency, VOP adopts an operational maxim for everyday engineering:

> **The Pragmatic Maxim:**
> If an illegal state cannot be made *unrepresentable* without disproportionate friction, it MUST be made **substantially difficult to instantiate**.

This shift establishes an intentional gradient of trade-offs:

1. **Smart Constructors Over Pure Micro-Types:** Use private constructors coupled with factory methods returning explicit algebraic types (`Result[T, E]`). The state may exist in memory structures, but cannot be instantiated via public APIs without validation.
2. **Coarse-Grained Invariants at Aggregate Roots:** Enforce multi-field cross-validation at aggregate boundaries rather than fragmenting every scalar field into an isolated single-use type.
3. **Parse at the Perimeter, Trust at the Core:** Strongly type data entering through boundaries (parsers, wire codecs, public APIs) so internal domain logic operates on guaranteed types without redundant checks.

---

## 3. Trade-Off Heuristics and Decision Criteria

Engineering in VOP requires selecting the appropriate tier of invariant enforcement based on blast radius, developer ergonomics, and runtime performance.

---

### 3.1 The Enforcement Spectrum

```
┌───────────────────────────────────────────────────┐  ┌───────────────────────────────┐
│             Integrity by Default                  │  │      Integrity by Design      │
│  [ Level 0 ]  ──>  [ Level 1 ]  ──>  [ Level 2 ]  │  │  ──>       [ Level 3 ]        │
│   Primitive         Boundary          Smart       │  │           Type-State          │
│   Obsession        Validation      Constructor    │  │             Proof             │
└───────────────────────────────────────────────────┘  └───────────────────────────────┘

```

* **Level 0: Unbounded Primitive (`String`, `int`)**
* *Posture:* Anti-Pattern / Uncontrolled.
* *Mechanism:* Raw scalars passed across boundaries.
* *Trade-off:* Zero allocation cost; maximum risk of silent domain corruption.


* **Level 1: Boundary Validation (Edge Parsing)**
* *Posture:* Integrity by Default (Perimeter Defense).
* *Mechanism:* Structural and schema checks at entry points (parsers, wire codecs). Internal logic consumes flat structures.
* *Trade-off:* Low allocation cost; suitable for bulk I/O and zero-copy streaming, but vulnerable to regressions in complex internal workflows.


* **Level 2: Smart Constructor (`Result[Value, Error]`) — *The 3NF Baseline***
* *Posture:* Integrity by Default (Standard Operational Sweet Spot).
* *Mechanism:* Private constructors paired with static factory methods returning algebraic types. Invariants run once upon creation; downstream consumers accept validated instances directly.
* *Trade-off:* Nominal object allocation; optimal balance of compile-time/runtime safety and developer ergonomics.


* **Level 3: Type-State Proof (Compiler-Enforced Transitions)**
* *Posture:* Integrity by Design (The 5NF Ceiling).
* *Mechanism:* Distinct types represent each lifecycle phase (e.g., `DraftOrder` $\to$ `PaidOrder`). Functions consume the preceding type by value and return the subsequent type.
* *Trade-off:* Absolute compile-time guarantee; higher conceptual overhead and increased type verbosity.



---

### 3.2 Decision Matrix

| Scenario / Domain Characteristic      | Recommended Level             | Architecture Posture | Primary Mechanism                        | Rationale                                                                   |
|:--------------------------------------|:------------------------------|:---------------------|:-----------------------------------------|:----------------------------------------------------------------------------|
| **Monetary Amounts & Identifiers**    | Level 2 (Smart Constructor)   | Integrity by Default | Dedicated Value Object (`Money`, `UUID`) | Eliminates unit confusion without complex type gymnastics.                  |
| **Ordered Business Workflows**        | Level 3 (Type-State)          | Integrity by Design  | Distinct types per phase                 | Prevents out-of-order execution at compile time.                            |
| **High-Throughput Wire Decoding**     | Level 1 (Boundary Validation) | Integrity by Default | Schema / Parser validation               | Avoids GC allocation churn in hot zero-copy read loops.                     |
| **Cross-Field Relational Invariants** | Level 2 (Smart Constructor)   | Integrity by Default | Aggregate Root validation                | Encapsulates dependencies between fields without combinatorial micro-types. |
| **Transient Internal Loop Counters**  | Level 0 (Primitive)           | Controlled Primitive | Standard primitives (`int`, `long`)      | Modeling transient loop indices as custom types adds pure overhead.         |

---

### 3.3 The Three Evaluative Filters

Before introducing a new distinct value type, apply these three filters:

1. **The Blast Radius Filter**
* *Question:* If this value contains an invalid state, does it fail fast locally, or does it silently corrupt downstream state?
* *Action:* If corruption is silent and deferred, promote to **Level 2** or **Level 3**. If failure is immediate and localized, **Level 1** or **Level 0** is sufficient.


2. **The Ergonomic Tax Filter**
* *Question:* Does wrapping this value require callers to write excessive unpacking boilerplate across more than two operational boundaries?
* *Action:* If the ergonomic tax causes developers to bypass the type via raw casts, back off to an aggregate-level smart constructor.


3. **The Mechanical Sympathy Filter**
* *Question:* Is this data structure evaluated in a hot loop, stream processing pipeline, or zero-copy codec?
* *Action:* Prioritize memory layout and cache locality. Use unboxed primitives or flat array backing, enforcing invariants strictly at the ingestion boundary.



---

## 4. The VOP Standard in STVN

### 4.1 The Role of STVN in Value-Oriented Systems

Strongly Typed Value Notation (STVN) exists to eliminate the boundary impedance mismatch between external data streams and internal domain models. Traditional serialization formats (JSON, YAML, untyped key-value maps) force applications into Level 0 primitive obsession, deferring type validation deep into runtime business logic.

STVN delivers **Integrity by Default** directly at the wire, storage, and ingestion boundaries. By establishing a fail-closed perimeter, STVN gives applications the stable foundation required to practice **Integrity by Design** without performance or ergonomic penalties.

---

### 4.2 Concrete STVN Pillars for VOP

#### Pillar 1: Structural Boundary Defense (Parse, Don't Validate)

* **Native Rich Typing:** STVN embeds exact value semantics (scalars, precise temporal representations, algebraic variants, structured records) into the syntax itself.
* **Fail-Fast Ingestion:** Data entering an STVN codec is parsed into guaranteed structural types before reaching application logic. Invalid layouts are rejected at the perimeter.

#### Pillar 2: Mechanical Sympathy Without the Wrapper Tax

* **Zero-Copy Traversal:** STVN decoders allow applications to enforce strict type boundaries without allocating intermediate wrapper hierarchies for every field.
* **Primitive Performance with Value Safety:** High-throughput streaming and memory-mapped buffers maintain Level 1/Level 2 guarantees while operating at native CPU and cache efficiency.

#### Pillar 3: Canonical Value Equivalence

* **Deterministic Encoding:** An identical logical value produces an identical STVN binary and textual representation.
* **Address-Agnostic Semantics:** Equality across systems depends strictly on data content, enforcing Axiom 1 across process boundaries.

#### Pillar 4: Explicit Error Semantics

* **No Hidden Failure Paths:** Codec operations in STVN do not rely on unchecked exceptions for schema mismatches or decoding failures.
* **Algebraic Outcomes:** Parsing and serialization return explicit diagnostic types that force callers to handle corrupted or malformed data paths systematically.

---

### 4.3 The VOP Commitment

The STVN core codebase, its tooling, and its downstream libraries commit to the following architectural balance:

1. **Provide Integrity by Default:** Deliver fail-closed serialization, strict structural parsing, deterministic equivalence, and immutable baseline structures across all standard tooling.
2. **Standardize on the 3NF Sweet Spot:** When full type-level proofs introduce friction, use smart constructors and sealed algebraic hierarchies to make illegal states difficult to instantiate.
3. **Empower Integrity by Design:** Use compile-time type-state proofs and rich domain models selectively where business failure blast radius justifies the modeling investment.
4. **Guard the Perimeter:** Ensure external data crosses into the system only through validated STVN parsers, freeing internal domain functions to operate on trusted, immutable values.