package org.stvnadore.core.lowering;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnDefsCatalog;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnDocumentKind;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.validation.DiagnosticBag;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pass 4 stateless reachability, alias-desugaring, and Content-Addressable Storage (CAS)
 * canonicalizer for the STVN 2.0.0 4-pass lowering pipeline.
 * <p>
 * Computes the Appendix G transitive reachability closure over lowered {@link StvnDocument}
 * and {@link StvnValue} IR trees, verifies that all nominal schema references are desugared
 * to canonical FQNIs, orders {@link StvnDefsCatalog} entries in deterministic topological
 * depth-first post-order, and enforces {@code E029} ({@code DEAD_CODE_CANONICAL_FAIL})
 * when strict canonical dead-code rejection is requested.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnReachabilityAndCasVerifier {

  private static final String PRELUDE_TYPE_PREFIX = StvnVocabulary.SIGIL_TYPIC + "org/stvnadore/prelude/";
  private static final String PRELUDE_CONST_PREFIX = StvnVocabulary.SIGIL_VALUE + "org/stvnadore/prelude/";

  private StvnReachabilityAndCasVerifier() {
    // Non-instantiable stateless pass
  }

  /**
   * Executes Pass 4 structural verification and deterministic topological DFS post-order
   * catalog ordering during standard document compilation.
   * <p>
   * Reachable nominal types seeded from {@code rootType} and {@code payload} are ordered first
   * in topological DFS post-order, followed by any remaining declared definitions in DFS post-order
   * so downstream inspection via {@link StvnDefsCatalog#findType(String)} retains full unit visibility.
   *
   * @param document      the lowered IR document produced by Passes 2 and 3
   * @param diagnosticBag the diagnostic accumulator
   * @return a verified {@link StvnDocument} with topologically ordered definitions
   */
  public static StvnDocument verifyAndOrder(
      StvnDocument document,
      DiagnosticBag diagnosticBag
  ) {
    Objects.requireNonNull(document, "document must not be null");
    Objects.requireNonNull(diagnosticBag, "diagnosticBag must not be null");

    StvnDefsCatalog srcCatalog = document.defs();
    if (srcCatalog.nominalTypes().isEmpty() && srcCatalog.constants().isEmpty()) {
      return document;
    }

    Map<String, StvnSchema> orderedTypes = new LinkedHashMap<>();
    Set<String> activeStack = new HashSet<>();

    if (document.rootType().isPresent()) {
      collectReachableTypesPostOrder(document.rootType().get(), srcCatalog, orderedTypes, activeStack);
    }
    if (document.payload().isPresent()) {
      collectReachableFromValue(document.payload().get(), srcCatalog, orderedTypes, activeStack);
    }

    // Preserve remaining declared unit definitions in deterministic DFS post-order for non-canonical inspection
    for (var entry : srcCatalog.nominalTypes().entrySet()) {
      String fqni = entry.getKey();
      if (!orderedTypes.containsKey(fqni)) {
        visitCatalogTypePostOrder(fqni, entry.getValue(), srcCatalog, orderedTypes, activeStack);
      }
    }

    StvnDefsCatalog orderedCatalog = new StvnDefsCatalog(orderedTypes, srcCatalog.constants());
    return new StvnDocument(
        document.meta(),
        orderedCatalog,
        document.rootType(),
        document.payload(),
        document.hasDefsSection(),
        document.hasIncludes()
    );
  }

  /**
   * Executes Appendix G canonical reachability pruning, implicit prelude exclusion, topological
   * DFS post-order ordering, and optional {@code E029} ({@code DEAD_CODE_CANONICAL_FAIL}) enforcement.
   *
   * @param document       the lowered IR document to canonicalize
   * @param failOnDeadCode {@code true} to emit {@code E029} when unreachable definitions exist in {@code document.defs()}
   * @param diagnosticBag  the diagnostic accumulator
   * @return a canonicalized {@link StvnDocument} ready for bijective CAS serialization and SHA-256 hashing
   */
  public static StvnDocument verifyAndPruneCanonical(
      StvnDocument document,
      boolean failOnDeadCode,
      DiagnosticBag diagnosticBag
  ) {
    Objects.requireNonNull(document, "document must not be null");
    Objects.requireNonNull(diagnosticBag, "diagnosticBag must not be null");

    StvnDefsCatalog srcCatalog = document.defs();
    boolean isDefsOnlyDoc = document.meta()
        .flatMap(m -> m.kind())
        .map(k -> k == StvnDocumentKind.DEFS || k == StvnDocumentKind.DEFS_INCLUDE)
        .orElseGet(() -> document.rootType().isEmpty() && document.payload().isEmpty());

    Map<String, StvnSchema> reachableTypes = new LinkedHashMap<>();
    Set<String> activeStack = new HashSet<>();

    if (isDefsOnlyDoc) {
      for (var entry : srcCatalog.nominalTypes().entrySet()) {
        if (!isImplicitPreludeType(entry.getKey())) {
          visitCatalogTypePostOrder(entry.getKey(), entry.getValue(), srcCatalog, reachableTypes, activeStack);
        }
      }
      StvnDefsCatalog defsOut = new StvnDefsCatalog(reachableTypes, filterNonPreludeConstants(srcCatalog.constants()));
      return new StvnDocument(
          document.meta(),
          defsOut,
          document.rootType(),
          document.payload(),
          !defsOut.nominalTypes().isEmpty() || !defsOut.constants().isEmpty(),
          false
      );
    }

    if (document.rootType().isPresent()) {
      collectReachableTypesPostOrder(document.rootType().get(), srcCatalog, reachableTypes, activeStack);
    }
    if (document.payload().isPresent()) {
      collectReachableFromValue(document.payload().get(), srcCatalog, reachableTypes, activeStack);
    }

    if (failOnDeadCode) {
      Set<String> deadSymbols = new LinkedHashSet<>();
      for (String declaredType : srcCatalog.nominalTypes().keySet()) {
        if (!isImplicitPreludeType(declaredType) && !reachableTypes.containsKey(declaredType)) {
          deadSymbols.add(declaredType);
        }
      }
      for (String declaredConst : srcCatalog.constants().keySet()) {
        if (!isImplicitPreludeConstant(declaredConst)) {
          deadSymbols.add(declaredConst);
        }
      }
      for (String dead : deadSymbols) {
        diagnosticBag.addError(
            "Unreachable definition '" + dead + "' is prohibited in strict canonical mode",
            0, 1, 1, 0, null,
            DiagnosticBag.ERR_DEAD_CODE_CANONICAL_FAIL
        );
      }
    }

    // Constants are inlined during Passes 2 & 3; canonical payload projection emits only reachable nominal types
    StvnDefsCatalog prunedCatalog = new StvnDefsCatalog(reachableTypes, Map.of());
    return new StvnDocument(
        document.meta(),
        prunedCatalog,
        document.rootType(),
        document.payload(),
        !reachableTypes.isEmpty(),
        false
    );
  }

  /**
   * Synthesizes a self-contained canonical {@link StvnDocument} from a standalone {@link StvnValue}
   * by extracting all reachable non-prelude nominal {@link StvnSchema} definitions in topological
   * DFS post-order.
   *
   * @param value the standalone lowered IR value
   * @return a canonical {@link StvnDocument} enclosing the reachable definitions, root schema, and value
   */
  public static StvnDocument synthesizeDocumentFromValue(StvnValue value) {
    Objects.requireNonNull(value, "value must not be null");
    Map<String, StvnSchema> reachableTypes = new LinkedHashMap<>();
    Set<String> activeStack = new HashSet<>();
    StvnDefsCatalog emptyCat = StvnDefsCatalog.EMPTY;

    collectReachableTypesPostOrder(value.schema(), emptyCat, reachableTypes, activeStack);
    collectReachableFromValue(value, emptyCat, reachableTypes, activeStack);

    StvnDefsCatalog catalog = new StvnDefsCatalog(reachableTypes, Map.of());
    return new StvnDocument(
        Optional.empty(),
        catalog,
        Optional.of(value.schema()),
        Optional.of(value),
        !reachableTypes.isEmpty(),
        false
    );
  }

  private static boolean isImplicitPreludeType(String fqni) {
    return fqni.startsWith(PRELUDE_TYPE_PREFIX);
  }

  private static boolean isImplicitPreludeConstant(String fqni) {
    return fqni.startsWith(PRELUDE_CONST_PREFIX);
  }

  private static Map<String, StvnDefsCatalog.StvnConstant> filterNonPreludeConstants(
      Map<String, StvnDefsCatalog.StvnConstant> constants
  ) {
    Map<String, StvnDefsCatalog.StvnConstant> out = new LinkedHashMap<>();
    for (var entry : constants.entrySet()) {
      if (!isImplicitPreludeConstant(entry.getKey())) {
        out.put(entry.getKey(), entry.getValue());
      }
    }
    return out;
  }

  private static void collectReachableFromValue(
      StvnValue value,
      StvnDefsCatalog catalog,
      Map<String, StvnSchema> orderedOut,
      Set<String> activeStack
  ) {
    collectReachableTypesPostOrder(value.schema(), catalog, orderedOut, activeStack);
    switch (value) {
      case StvnValue.StvnTuple tup -> {
        for (StvnValue child : tup.elements()) {
          collectReachableFromValue(child, catalog, orderedOut, activeStack);
        }
      }
      case StvnValue.StvnOption opt -> opt.value()
          .ifPresent(inner -> collectReachableFromValue(inner, catalog, orderedOut, activeStack));
      case StvnValue.StvnEither eith -> collectReachableFromValue(eith.value(), catalog, orderedOut, activeStack);
      case StvnValue.StvnUnion un -> collectReachableFromValue(un.value(), catalog, orderedOut, activeStack);
      case StvnValue.StvnSeq seq -> {
        for (StvnValue elem : seq.elements()) {
          collectReachableFromValue(elem, catalog, orderedOut, activeStack);
        }
      }
      case StvnValue.StvnSet set -> {
        for (StvnValue elem : set.elements()) {
          collectReachableFromValue(elem, catalog, orderedOut, activeStack);
        }
      }
      case StvnValue.StvnMap map -> {
        for (StvnValue.KeyValueEntry entry : map.entries()) {
          collectReachableFromValue(entry.key(), catalog, orderedOut, activeStack);
          collectReachableFromValue(entry.value(), catalog, orderedOut, activeStack);
        }
      }
      default -> {
        // Scalar or error leaf value
      }
    }
  }

  private static void collectReachableTypesPostOrder(
      StvnSchema schema,
      StvnDefsCatalog catalog,
      Map<String, StvnSchema> orderedOut,
      Set<String> activeStack
  ) {
    if (schema instanceof StvnSchema.PoisonedSchema p) {
      if (StvnPayloadLowerer.RECURSIVE_BACK_EDGE_SENTINEL.equals(p.primaryDiagnosticCode())
          && p.aliasName().isPresent()) {
        String target = p.aliasName().get();
        if (!isImplicitPreludeType(target) && !orderedOut.containsKey(target) && !activeStack.contains(target)) {
          catalog.findType(target).ifPresent(resolved ->
              visitCatalogTypePostOrder(target, resolved, catalog, orderedOut, activeStack));
        }
      }
      return;
    }

    Optional<String> aliasOpt = schema.aliasName();
    if (aliasOpt.isPresent()) {
      String fqni = aliasOpt.get();
      if (isImplicitPreludeType(fqni)) {
        return;
      }
      if (orderedOut.containsKey(fqni) || activeStack.contains(fqni)) {
        return;
      }
      StvnSchema targetSchema = catalog.findType(fqni).orElse(schema);
      visitCatalogTypePostOrder(fqni, targetSchema, catalog, orderedOut, activeStack);
      return;
    }

    traverseChildSchemas(schema, catalog, orderedOut, activeStack);
  }

  private static void visitCatalogTypePostOrder(
      String fqni,
      StvnSchema schema,
      StvnDefsCatalog catalog,
      Map<String, StvnSchema> orderedOut,
      Set<String> activeStack
  ) {
    if (orderedOut.containsKey(fqni) || !activeStack.add(fqni)) {
      return;
    }
    try {
      traverseChildSchemas(schema, catalog, orderedOut, activeStack);
      if (!isImplicitPreludeType(fqni)) {
        orderedOut.put(fqni, schema);
      }
    } finally {
      activeStack.remove(fqni);
    }
  }

  private static void traverseChildSchemas(
      StvnSchema schema,
      StvnDefsCatalog catalog,
      Map<String, StvnSchema> orderedOut,
      Set<String> activeStack
  ) {
    switch (schema) {
      case StvnSchema.EnumSubsetSchema sub -> {
        collectReachableTypesPostOrder(sub.parentEnum(), catalog, orderedOut, activeStack);
        collectReachableTypesPostOrder(sub.rootEnum(), catalog, orderedOut, activeStack);
      }
      case StvnSchema.TupleSchema tup -> {
        for (StvnSchema child : tup.fieldSchemas()) {
          collectReachableTypesPostOrder(child, catalog, orderedOut, activeStack);
        }
      }
      case StvnSchema.OptionSchema opt -> collectReachableTypesPostOrder(opt.valueSchema(), catalog, orderedOut, activeStack);
      case StvnSchema.EitherSchema eith -> {
        collectReachableTypesPostOrder(eith.leftSchema(), catalog, orderedOut, activeStack);
        collectReachableTypesPostOrder(eith.rightSchema(), catalog, orderedOut, activeStack);
      }
      case StvnSchema.UnionSchema un -> {
        for (StvnSchema branch : un.branchSchemas()) {
          collectReachableTypesPostOrder(branch, catalog, orderedOut, activeStack);
        }
      }
      case StvnSchema.SeqSchema seq -> collectReachableTypesPostOrder(seq.elementSchema(), catalog, orderedOut, activeStack);
      case StvnSchema.SetSchema set -> collectReachableTypesPostOrder(set.elementSchema(), catalog, orderedOut, activeStack);
      case StvnSchema.MapSchema map -> {
        collectReachableTypesPostOrder(map.keySchema(), catalog, orderedOut, activeStack);
        collectReachableTypesPostOrder(map.valueSchema(), catalog, orderedOut, activeStack);
      }
      default -> {
        // Atomic, root enum, or poisoned leaf schema
      }
    }
  }
}
