package org.stvnadore.core.io;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.parser.StvnParser;
import org.stvnadore.core.parser.StvnParser.SchemaTypeContext;
import org.stvnadore.core.parser.StvnParser.StvnDocumentContext;
import org.stvnadore.core.parser.StvnParser.TypeDefinitionContext;
import org.stvnadore.core.parser.StvnParser.ConstantDefinitionContext;
import org.stvnadore.core.validation.StvnTypeResolver;
import org.stvnadore.core.validation.StvnTypeResolver.ConstantDefSource;
import org.stvnadore.core.validation.StvnTypeResolver.DefSource;
import org.stvnadore.core.validation.StvnTypeResolver.StvnConstraints;

import java.util.*;

/**
 * Resolves transitive reachability, dead-code elimination, and universal alias desugaring
 * for canonical STVN AST serialization.
 * <p>
 * This component extracts the transitive closure of nominal type and constant definitions
 * referenced by the document root schema and value payload. It eliminates dead definitions,
 * inlines scoped aliases to Fully Qualified Nominal Identifiers (FQNIs), and sorts retained
 * definitions into topological dependency order.
 *
 * @since 1.3.0
 */
@NullMarked
public final class StvnCanonicalDefinitionsResolver {

  private StvnCanonicalDefinitionsResolver() {
    // Utility class
  }

  /**
   * Represents a resolved, self-contained definition ready for canonical serialization.
   *
   * @param canonicalName the fully qualified nominal identifier
   * @param isConstant     {@code true} if this represents a typed constant, {@code false} for a type
   * @param constraints    the structural metadata constraints
   * @param schemaNode     the parse tree schema node of the definition
   * @param constantValue  the raw constant value parse node if this is a constant, or empty
   * @param lexicalContext the parse rule context used for resolving nested relative aliases
   * @since 1.3.0
   */
  public record ResolvedCanonicalDefinition(
      String canonicalName,
      boolean isConstant,
      StvnConstraints constraints,
      SchemaTypeContext schemaNode,
      Optional<StvnParser.ValueContext> constantValue,
      ParserRuleContext lexicalContext
  ) {
    /**
     * Canonical constructor validating that non-optional parameters are non-null.
     */
    public ResolvedCanonicalDefinition {
      Objects.requireNonNull(canonicalName, "canonicalName must not be null");
      Objects.requireNonNull(constraints, "constraints must not be null");
      Objects.requireNonNull(schemaNode, "schemaNode must not be null");
      Objects.requireNonNull(constantValue, "constantValue must not be null");
      Objects.requireNonNull(lexicalContext, "lexicalContext must not be null");
    }
  }

  /**
   * Resolves the transitively reachable, fully desugared definitions for the given AST value.
   *
   * @param value the root AST value node to serialize
   * @return a list of resolved canonical definitions ordered with dependencies preceding dependents
   * @since 1.3.0
   */
  public static List<ResolvedCanonicalDefinition> resolveDefinitions(StvnValue value) {
    var doc = findDocumentContext(value);
    if (doc == null) {
      return fallbackLinearChain(value);
    }

    var allTypes = StvnTypeResolver.getDocumentDefinitionsCache(doc);
    var allConstants = StvnTypeResolver.getDocumentConstantDefinitionsCache(doc);
    if (allTypes.isEmpty() && allConstants.isEmpty()) {
      return List.of();
    }

    Set<String> initialSeeds = new LinkedHashSet<>();
    var schema = value.schema();
    if (schema != null) {
      schema.aliasName().ifPresent(initialSeeds::add);
      collectReferencedNominalSymbols(schema.node(), doc, initialSeeds);
    }
    if (doc.documentBody() != null) {
      if (doc.documentBody().typeEntry() != null) {
        collectReferencedNominalSymbols(doc.documentBody().typeEntry().schemaType(), doc, initialSeeds);
      }
      if (doc.documentBody().bodyEntry() != null) {
        collectReferencedValueSymbols(doc.documentBody().bodyEntry().value(), doc, initialSeeds);
      }
    }

    Set<String> visited = new HashSet<>();
    Queue<String> worklist = new ArrayDeque<>();
    for (String seed : initialSeeds) {
      if (visited.add(seed)) {
        worklist.add(seed);
      }
    }

    Map<String, ResolvedCanonicalDefinition> retainedDefs = new LinkedHashMap<>();
    Map<String, List<String>> dependencyGraph = new LinkedHashMap<>();

    while (!worklist.isEmpty()) {
      String symbol = worklist.poll();
      if (symbol.startsWith("#")) {
        ConstantDefSource cs = allConstants.get(symbol);
        if (cs != null) {
          ConstantDefinitionContext cNode = cs.defNode();
          StvnConstraints cConstraints = StvnTypeResolver.extractConstraints(cNode.metadataMap());
          retainedDefs.put(symbol, new ResolvedCanonicalDefinition(
              symbol,
              true,
              cConstraints,
              cNode.schemaType(),
              Optional.ofNullable(cNode.value()),
              cNode
          ));
          List<String> deps = new ArrayList<>();
          collectReferencedNominalSymbols(cNode.schemaType(), doc, deps);
          if (cNode.value() != null) {
            collectReferencedValueSymbols(cNode.value(), doc, deps);
          }
          dependencyGraph.put(symbol, deps);
          for (String dep : deps) {
            if (visited.add(dep)) {
              worklist.add(dep);
            }
          }
        }
      } else {
        DefSource ds = allTypes.get(symbol);
        if (ds != null) {
          TypeDefinitionContext tNode = ds.defNode();
          StvnConstraints tConstraints = StvnTypeResolver.extractConstraints(tNode.metadataMap());
          retainedDefs.put(symbol, new ResolvedCanonicalDefinition(
              symbol,
              false,
              tConstraints,
              tNode.schemaType(),
              Optional.empty(),
              tNode
          ));
          List<String> deps = new ArrayList<>();
          collectReferencedNominalSymbols(tNode.schemaType(), doc, deps);
          dependencyGraph.put(symbol, deps);
          for (String dep : deps) {
            if (visited.add(dep)) {
              worklist.add(dep);
            }
          }
        }
      }
    }

    return topologicalSort(retainedDefs, dependencyGraph, doc);
  }

  private static List<ResolvedCanonicalDefinition> topologicalSort(
      Map<String, ResolvedCanonicalDefinition> defs,
      Map<String, List<String>> graph,
      StvnDocumentContext doc
  ) {
    List<ResolvedCanonicalDefinition> result = new ArrayList<>();
    Set<String> visited = new HashSet<>();
    Set<String> visiting = new HashSet<>();

    for (String sym : defs.keySet()) {
      if (!visited.contains(sym)) {
        dfs(sym, defs, graph, visited, visiting, result);
      }
    }
    return result;
  }

  private static void dfs(
      String sym,
      Map<String, ResolvedCanonicalDefinition> defs,
      Map<String, List<String>> graph,
      Set<String> visited,
      Set<String> visiting,
      List<ResolvedCanonicalDefinition> result
  ) {
    if (visiting.contains(sym)) {
      // Cycle detected; proceed gracefully without re-visiting
      return;
    }
    if (visited.contains(sym)) {
      return;
    }
    visiting.add(sym);
    List<String> deps = graph.getOrDefault(sym, Collections.emptyList());
    for (String dep : deps) {
      if (defs.containsKey(dep) && !visited.contains(dep)) {
        dfs(dep, defs, graph, visited, visiting, result);
      }
    }
    visiting.remove(sym);
    visited.add(sym);
    ResolvedCanonicalDefinition def = defs.get(sym);
    if (def != null) {
      result.add(def);
    }
  }

  private static void collectReferencedNominalSymbols(
      @Nullable ParseTree node,
      StvnDocumentContext doc,
      Collection<String> sink
  ) {
    if (node == null) return;
    if (node instanceof StvnParser.TypeKeywordContext tk) {
      String raw = tk.getText();
      if (!StvnTypeResolver.isReservedFundamentalType(raw)) {
        String resolved = StvnTypeResolver.resolveTypeIdentifier(doc, raw, tk);
        if (!StvnTypeResolver.isReservedFundamentalType(resolved)) {
          sink.add(resolved);
        }
      }
    } else {
      for (int i = 0; i < node.getChildCount(); i++) {
        collectReferencedNominalSymbols(node.getChild(i), doc, sink);
      }
    }
  }

  private static void collectReferencedValueSymbols(
      @Nullable ParseTree node,
      StvnDocumentContext doc,
      Collection<String> sink
  ) {
    if (node == null) return;
    if (node instanceof StvnParser.ValueKeywordContext vk) {
      String raw = vk.getText();
      if (raw.startsWith("#")) {
        String resolved = StvnTypeResolver.resolveConstantIdentifier(doc, raw, vk);
        sink.add(resolved);
      }
    } else {
      for (int i = 0; i < node.getChildCount(); i++) {
        collectReferencedValueSymbols(node.getChild(i), doc, sink);
      }
    }
  }

  private static @Nullable StvnDocumentContext findDocumentContext(@Nullable StvnValue value) {
    if (value == null || value.schema() == null || value.schema().node() == null) {
      return null;
    }
    ParserRuleContext cur = value.schema().node();
    while (cur != null) {
      if (cur instanceof StvnDocumentContext d) {
        return d;
      }
      cur = cur.getParent();
    }
    return null;
  }

  private static List<ResolvedCanonicalDefinition> fallbackLinearChain(StvnValue value) {
    var schema = value.schema();
    if (schema == null || schema.aliasName().isEmpty()) {
      return List.of();
    }
    List<ResolvedCanonicalDefinition> chain = new ArrayList<>();
    var current = schema;
    while (current != null) {
      var alias = current.aliasName().orElse(null);
      if (alias == null) break;
      var constraints = current.localConstraints().orElse(current.constraints());
      chain.addFirst(new ResolvedCanonicalDefinition(
          alias,
          false,
          constraints,
          current.node(),
          Optional.empty(),
          current.node()
      ));
      current = current.underlyingSchema().orElse(null);
    }
    return chain;
  }

  /**
   * Resolves a nominal type keyword in the context of canonical emission, expanding local aliases to FQNIs.
   *
   * @param rawTypeKeyword the raw nominal type keyword from the parse tree
   * @param lexicalContext  the parse tree context for scoping
   * @return the fully qualified canonical nominal type name
   * @since 1.3.0
   */
  public static String resolveCanonicalTypeKeyword(String rawTypeKeyword, ParserRuleContext lexicalContext) {
    if (StvnTypeResolver.isReservedFundamentalType(rawTypeKeyword)) {
      return rawTypeKeyword;
    }
    ParserRuleContext cur = lexicalContext;
    StvnDocumentContext doc = null;
    while (cur != null) {
      if (cur instanceof StvnDocumentContext d) {
        doc = d;
        break;
      }
      cur = cur.getParent();
    }
    return StvnTypeResolver.resolveTypeIdentifier(doc, rawTypeKeyword, lexicalContext);
  }
}
