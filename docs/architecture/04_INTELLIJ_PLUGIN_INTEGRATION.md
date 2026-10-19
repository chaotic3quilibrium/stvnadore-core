# STVN Architectural Specification 04: IntelliJ Platform Integration

**Document ID**: `STVN-SPEC-04`
**Status**: Canonical Specification
**Version**: 1.1.0
**Compliance**: Mandatory across all STVN IDE plugins, language server protocols, and editor integrations.

---

**Table of Contents**

<!-- TOC -->
* [STVN Architectural Specification 04: IntelliJ Platform Integration](#stvn-architectural-specification-04-intellij-platform-integration)
  * [1. PSI & Grammar Architecture](#1-psi--grammar-architecture)
  * [2. AST & PSI Bridge](#2-ast--psi-bridge)
  * [3. In-Memory Workspace Flattener (`StvnFlattenWorkspaceAction`)](#3-in-memory-workspace-flattener-stvnflattenworkspaceaction)
  * [4. Non-Blocking Event Dispatch Thread (EDT) Discipline](#4-non-blocking-event-dispatch-thread-edt-discipline)
  * [5. Sub-Token Diagnostic Precision & Structural Immunity](#5-sub-token-diagnostic-precision--structural-immunity)
  * [6. Interactive Scaffolding & Auto-Healing](#6-interactive-scaffolding--auto-healing)
<!-- TOC -->

---

## 1. PSI & Grammar Architecture

The STVN IntelliJ plugin implements language parsing via Grammar-Kit (`stvn.bnf`) and JFlex (`stvn.flex`), generating a strongly typed Program Structure Interface (PSI) tree rooted at `StvnFile`:

* `StvnPayloadFile` (`.stvn`): Canonical hermetic payload document containing `:type` and `:body` blocks (with optional `:defs`, zero `:include` statements).
* `StvnDefinitionsFile` (`.stvn_d`): Hermetic definitions schema (canonical CAS unit) containing `:defs` (zero `:include` statements).
* `StvnPayloadIncludeFile` (`.stvn_i`): Modular payload document requiring external module loader, containing `:type`, `:body`, optional `:defs`, and `:include` directives.
* `StvnDefsIncludeFile` (`.stvn_di`): Modular definitions schema module containing `:defs` and `:include` directives.
* `StvnBinaryFileType` (`.stvn_b`): Binary hermetic payload document.
* `StvnBinaryDefsFileType` (`.stvn_bd`): Binary hermetic definitions schema.

---

## 2. AST & PSI Bridge

The plugin leverages `StvnPsiUtils` to bridge IntelliJ PSI elements with core `org.stvnadore.core.ir.StvnValue` immutable records:
* PSI elements provide source text offsets, file references, and syntax highlighting spans.
* Core `StvnValue` records provide type resolution, algebraic validation, and binary serialization logic.

---

## 3. In-Memory Workspace Flattener (`StvnFlattenWorkspaceAction`)

The workspace flattener enables zero-IO schema bundling:
1. Ingests the selected entry-point modular schema (`.stvn_di`) or modular payload (`.stvn_i`) file.
2. Traverses all relative `:include` paths into an in-memory dependency DAG.
3. Validates that no circular import cycles or duplicate alias collisions exist.
4. Unrolls nominal type definitions into a single standalone `:defs` block.
5. Normalizes definitions and emits a canonical hermetic definitions schema (`.stvn_d`) with a `:meta { ... #kind #DEFS }` enclosure.

---

## 4. Non-Blocking Event Dispatch Thread (EDT) Discipline

To maintain IDE responsiveness and prevent UI freezes:
* **Background Type Checking**: `StvnExternalAnnotator` runs full ANTLR4 parsing and type resolution off the EDT in a background daemon thread (`doAnnotated()`), applying `AnnotationHolder` visual markers during the EDT pass (`apply()`).
* **Asynchronous Schema Publishing**: `PublishSchemaAction` executes network HTTP requests inside a background worker thread (`Task.Backgroundable`), posting completion balloon notifications via `StvnWorkspaceNotificationHelper` onto the Event Dispatch Thread via `ApplicationManager.getApplication().invokeLater()`.

---

## 5. Sub-Token Diagnostic Precision & Structural Immunity

The plugin maps `StvnDiagnostic` start and end offsets to exact PSI leaf literals:
* If a payload literal in `:body` is malformed, only that specific leaf token is underlined with an error squiggle.
* **Structural Immunity Invariant**: Root curly braces `{ ... }`, the `:type` header, and the `:defs` block remain immune from error squigglies when errors occur inside `:body` values.

---

## 6. Interactive Scaffolding & Auto-Healing

* **Schema Skeleton Scaffolder (`StvnSchemaSkeletonIntentionAction`)**: Triggered by `Alt+Enter` (macOS: `⌥Enter`) on an empty `:body` keyword. Recursively expands the resolved `:type` contract into standard mock literals with live template tab-stops.
* **Trap 2 Map Auto-Healer (`StvnMapAutoHealerQuickFix`)**: Detects flat lists authored in `:Map` slots and atomically wraps them into canonical paired bracket syntax (`{ [ key val ] }`).
