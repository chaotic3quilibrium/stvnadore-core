package org.stvnadore.core.validation;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.parser.StvnParser;
import org.stvnadore.core.parser.StvnParser.ConstantDefinitionContext;
import org.stvnadore.core.parser.StvnParser.MetadataMapContext;
import org.stvnadore.core.parser.StvnParser.StvnDocumentContext;
import org.stvnadore.core.parser.StvnParser.SchemaTypeContext;
import org.stvnadore.core.parser.StvnParser.TypeDefinitionContext;
import org.stvnadore.core.parser.StvnParser.ValueContext;
import org.stvnadore.core.parser.StvnParser.SumTypeContext;
import org.stvnadore.core.stdlib.StvnPrelude;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.ir.StvnLiteralParser;

import java.io.IOException;
import java.io.Serial;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import java.util.*;

/**
 * Central schema resolution, validation, and type-unification engine for STVN.
 * <p>
 * The type resolver consolidates metadata constraints (min/max range bounds, regular expression patterns),
 * propagates traits (such as determining if a compound collection satisfies {@code #equatable} or
 * {@code #comparable}), resolves nominal aliases, and unifies raw value literals against schema shapes.
 *
 * <h2>Type Unification &amp; Trait Propagation</h2>
 * <ul>
 *   <li><b>Type Unification:</b> Structural compatibility is resolved by checking underlying base types
 *       (e.g., confirming if an integer conforms to a target bit-width boundary or if a map possesses sequenced keys).</li>
 *   <li><b>Trait Propagation (Capability Bubbling):</b> For container and algebraic types, capability states
 *       (such as {@code #equatable} or {@code #comparable}) are a function of their underlying sub-component schemas.
 *       If any inner element resolves to {@code #equatable #FALSE}, that constraint recursively bubbles up to
 *       disable the trait for the entire outer container.</li>
 * </ul>
 *
 * <h2>Implied Tagging Unification Mechanics</h2>
 * The unification method {@link #canMatch} implements STVN's happy-path implied tagging rules:
 * <ul>
 *   <li><b>Rule A (Implied Option {@code #Some}):</b> If the target schema is {@code :Option(T)} and a raw literal
 *       matching {@code T} is encountered, it unifies as an implied {@code #Some} node.</li>
 *   <li><b>Rule B (Implied Either {@code #Right}):</b> If the target schema is {@code :Either(L R)} and a raw literal
 *       matching {@code R} is encountered, it unifies as an implied {@code #Right} node.</li>
 *   <li><b>Rule C (Ambiguity Resolution):</b> If an untagged literal raw value is type-compatible with both
 *       the explicit tag identifier and the implied scalar type (e.g. matching a keyword symbol), explicit
 *       tagging is mandatory, otherwise a {@link MalformedPayloadException} is thrown during parsing/mapping.</li>
 * </ul>
 *
 * @since 1.0.0
 */
@NullMarked
@SuppressWarnings({"ConstantConditions", "DataFlowIssue"})
public class StvnTypeResolver {

  /**
   * A mapping of document context instances to their corresponding file paths.
   * <p>
   * This map is designed as a {@link Collections#synchronizedMap(Map)} wrapping a {@link WeakHashMap}.
   * This configuration prevents memory leaks of {@link StvnDocumentContext} AST instances by allowing
   * them to be garbage collected when they are no longer referenced elsewhere, while preserving
   * concurrent thread safety.
   */
  public static final Map<StvnDocumentContext, String> documentPaths = Collections.synchronizedMap(new WeakHashMap<>());
  private static final Map<StvnDocumentContext, Map<String, DefSource>> documentDefinitionsCache = Collections.synchronizedMap(new WeakHashMap<>());
  private static final Map<StvnDocumentContext, Map<String, ConstantDefSource>> documentConstantDefinitionsCache = Collections.synchronizedMap(new WeakHashMap<>());
  private static final Map<StvnDocumentContext, Set<String>> documentPoisonedTypes = Collections.synchronizedMap(new WeakHashMap<>());

  /**
   * Marks a nominal type as a poisoned sentinel in the document context.
   *
   * @param doc the document context
   * @param typeName the name of the poisoned type
   */
  public static void markTypePoisoned(@Nullable StvnDocumentContext doc, String typeName) {
    if (doc != null) {
      documentPoisonedTypes.computeIfAbsent(doc, d -> Collections.synchronizedSet(new HashSet<>())).add(typeName);
    }
  }

  /**
   * Checks if a nominal type is marked as a poisoned sentinel in the document context.
   *
   * @param doc the document context
   * @param typeName the name of the type to check
   * @return true if the type is poisoned
   */
  public static boolean isTypePoisoned(@Nullable StvnDocumentContext doc, String typeName) {
    if (doc == null) return false;
    var set = documentPoisonedTypes.get(doc);
    return set != null && set.contains(typeName);
  }

  /**
   * Resolves and returns all type definitions defined or imported in the given document context.
   * <p>
   * <b>Null-Handling Contract:</b> If the provided document context {@code doc} is {@code null},
   * or has no body or definitions, this method returns an empty, immutable map immediately
   * without throwing an exception.
   *
   * @param doc the document context to resolve definitions for; may be {@code null}
   * @return a map of type names to their definition sources; never {@code null}
   */
  public static Map<String, DefSource> getDocumentDefinitions(@Nullable StvnDocumentContext doc) {
    return getDocumentDefinitions(doc, new DiagnosticBag());
  }

  /**
   * Resolves and returns all type definitions defined or imported in the given document context,
   * accumulating any definition diagnostics into the provided {@link DiagnosticBag}.
   *
   * @param doc the document context to resolve definitions for; may be {@code null}
   * @param diagnosticBag the accumulator bag for recording semantic diagnostics
   * @return a map of type names to their definition sources; never {@code null}
   */
  public static Map<String, DefSource> getDocumentDefinitions(@Nullable StvnDocumentContext doc, DiagnosticBag diagnosticBag) {
    if (doc == null || doc.documentBody() == null || doc.documentBody().defsEntry() == null) {
      return Collections.emptyMap();
    }
    synchronized (documentDefinitionsCache) {
      if (documentDefinitionsCache.containsKey(doc)) {
        return documentDefinitionsCache.get(doc);
      }
      List<String> activePaths = new ArrayList<>();
      List<String> activeRawPaths = new ArrayList<>();
      String currentDocPath = documentPaths.get(doc);
      if (currentDocPath != null && !currentDocPath.isEmpty()) {
        activePaths.add(Paths.get(currentDocPath).toAbsolutePath().toString());
        activeRawPaths.add(currentDocPath);
      }
      Map<String, DefSource> resolved = resolveDefinitionsAndValidate(doc, activePaths, activeRawPaths, diagnosticBag);
      documentDefinitionsCache.put(doc, resolved);
      return resolved;
    }
  }

  /**
   * Resolves and returns all constant definitions defined or imported in the given document context.
   * <p>
   * <b>Null-Handling Contract:</b> If the provided document context {@code doc} is {@code null},
   * or has no body or definitions, this method returns an empty, immutable map immediately
   * without throwing an exception.
   *
   * @param doc the document context to resolve constant definitions for; may be {@code null}
   * @return a map of constant names to their constant definition sources; never {@code null}
   */
  public static Map<String, ConstantDefSource> getDocumentConstantDefinitions(@Nullable StvnDocumentContext doc) {
    return getDocumentConstantDefinitions(doc, new DiagnosticBag());
  }

  /**
   * Resolves and returns all constant definitions defined or imported in the given document context,
   * accumulating any definition diagnostics into the provided {@link DiagnosticBag}.
   *
   * @param doc the document context to resolve constant definitions for; may be {@code null}
   * @param diagnosticBag the accumulator bag for recording semantic diagnostics
   * @return a map of constant names to their constant definition sources; never {@code null}
   */
  public static Map<String, ConstantDefSource> getDocumentConstantDefinitions(@Nullable StvnDocumentContext doc, DiagnosticBag diagnosticBag) {
    if (doc == null || doc.documentBody() == null || doc.documentBody().defsEntry() == null) {
      return Collections.emptyMap();
    }
    synchronized (documentDefinitionsCache) {
      if (documentConstantDefinitionsCache.containsKey(doc)) {
        return documentConstantDefinitionsCache.get(doc);
      }
      getDocumentDefinitions(doc, diagnosticBag);
      return documentConstantDefinitionsCache.getOrDefault(doc, Collections.emptyMap());
    }
  }

  /**
   * Returns the cached type definitions map for the specified document context.
   *
   * @param doc the document context to query
   * @return the map of canonical nominal type identifiers to their definition sources
   * @since 1.3.0
   */
  public static Map<String, DefSource> getDocumentDefinitionsCache(@Nullable StvnDocumentContext doc) {
    if (doc == null) {
      return Collections.emptyMap();
    }
    getDocumentDefinitions(doc);
    return documentDefinitionsCache.getOrDefault(doc, Collections.emptyMap());
  }

  /**
   * Returns the cached constant definitions map for the specified document context.
   *
   * @param doc the document context to query
   * @return the map of canonical nominal constant identifiers to their definition sources
   * @since 1.3.0
   */
  public static Map<String, ConstantDefSource> getDocumentConstantDefinitionsCache(@Nullable StvnDocumentContext doc) {
    if (doc == null) {
      return Collections.emptyMap();
    }
    getDocumentDefinitions(doc);
    return documentConstantDefinitionsCache.getOrDefault(doc, Collections.emptyMap());
  }

  private static Path resolveIncludePath(@Nullable String currentDocPath, String includePathStr) {
    Path includePath = Paths.get(includePathStr);
    if (includePath.isAbsolute()) {
      return includePath;
    }
    if (currentDocPath != null && !currentDocPath.isEmpty()) {
      Path parent = Paths.get(currentDocPath).getParent();
      if (parent != null) {
        Path resolved = parent.resolve(includePathStr);
        if (Files.exists(resolved)) {
          return resolved;
        }
      }
    }
    return includePath;
  }

  /**
   * Slices a slash-delimited nominal identifier to its terminal segment while preserving the leading sigil.
   *
   * @param identifier the full nominal type or constant identifier (e.g., {@code :org/example/Port} or {@code #org/example/PORT})
   * @return the sliced terminal identifier preserving the leading sigil (e.g., {@code :Port} or {@code #PORT})
   */
  public static String sliceTerminal(String identifier) {
    int lastSlash = identifier.lastIndexOf('/');
    if (lastSlash < 0) {
      return identifier;
    }
    char sigil = identifier.charAt(0);
    return sigil + identifier.substring(lastSlash + 1);
  }

  /**
   * Encapsulates the active lexical scoping environment for symbol resolution, including package-local
   * aliases, reverse alias mappings, and stripped imports.
   *
   * @param packagePath the optional enclosing package path prefix, or empty if at root document scope
   * @param typeAliases map of local type aliases to fully qualified nominal type identifiers
   * @param constAliases map of local constant aliases to fully qualified nominal constant identifiers
   * @param reverseTypeAliases map of fully qualified nominal type identifiers back to local aliases
   */
  public record ScopedUseEnvironment(
      Optional<String> packagePath,
      Map<String, String> typeAliases,
      Map<String, String> constAliases,
      Map<String, String> reverseTypeAliases
  ) {}

  private static final Map<StvnDocumentContext, ScopedUseEnvironment> documentScopes = Collections.synchronizedMap(new WeakHashMap<>());
  private static final Map<StvnParser.PackageEnclosureContext, ScopedUseEnvironment> packageEnclosureScopes = Collections.synchronizedMap(new WeakHashMap<>());

  /**
   * Discovers and evaluates the active {@link ScopedUseEnvironment} corresponding to a specific AST context node.
   *
   * @param doc the enclosing STVN document context, or null
   * @param node the parse tree context node whose lexical scope is being evaluated
   * @return the resolved lexical scoping environment for the context node
   */
  public static ScopedUseEnvironment findScope(@Nullable StvnDocumentContext doc, @Nullable ParserRuleContext node) {
    ParserRuleContext cur = node;
    while (cur != null) {
      if (cur instanceof StvnParser.PackageEnclosureContext pkgEnc) {
        var env = packageEnclosureScopes.get(pkgEnc);
        if (env != null) return env;
      }
      cur = cur.getParent();
    }
    if (doc != null) {
      var rootEnv = documentScopes.get(doc);
      if (rootEnv != null) return rootEnv;
    }
    return new ScopedUseEnvironment(Optional.empty(), Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
  }

  /**
   * Resolves a nominal type identifier reference against the active lexical scope, applying the Section 4.2
   * precedence cascade and expanding relative identifiers to fully qualified nominal identifiers.
   *
   * @param doc the enclosing STVN document context, or null
   * @param kw the raw type keyword identifier to resolve (e.g., {@code :Port})
   * @param referenceContext the parse tree context where the type reference occurs
   * @return the resolved canonical fully qualified nominal type identifier
   */
  public static String resolveTypeIdentifier(@Nullable StvnDocumentContext doc, String kw, @Nullable ParserRuleContext referenceContext) {
    if (doc == null || kw == null || isReservedFundamentalType(kw)) {
      return kw;
    }
    var env = findScope(doc, referenceContext);
    // 2. In-scope :use aliases and #strip mappings (package-local first)
    if (env.typeAliases().containsKey(kw)) {
      return env.typeAliases().get(kw);
    }
    // Document-global :use mappings (if in package scope)
    var docEnv = documentScopes.get(doc);
    if (docEnv != null && docEnv.typeAliases().containsKey(kw)) {
      return docEnv.typeAliases().get(kw);
    }
    // 3. Enclosing :package prefix check for sibling definitions
    if (env.packagePath().isPresent()) {
      String localSuffix = kw.startsWith(":") ? kw.substring(1) : kw;
      String siblingFqni = env.packagePath().get() + "/" + localSuffix;
      if (findDefInDocument(doc, siblingFqni).isPresent()) {
        return siblingFqni;
      }
    }
    // 4. Document-global definitions (or Prelude)
    if (findDefInDocument(doc, kw).isPresent()) {
      return kw;
    }
    var preludeDoc = StvnPrelude.getPreludeDocument();
    if (preludeDoc != null && findDefInDocument(preludeDoc, kw).isPresent()) {
      return kw;
    }
    return kw;
  }

  /**
   * Resolves a typed constant identifier reference against the active lexical scope, expanding relative
   * names to fully qualified nominal constant identifiers while preserving the leading value sigil.
   *
   * @param doc the enclosing STVN document context, or null
   * @param kw the raw constant keyword identifier to resolve (e.g., {@code #PORT})
   * @param referenceContext the parse tree context where the constant reference occurs
   * @return the resolved canonical fully qualified nominal constant identifier
   */
  public static String resolveConstantIdentifier(@Nullable StvnDocumentContext doc, String kw, @Nullable ParserRuleContext referenceContext) {
    if (doc == null || kw == null) {
      return kw;
    }
    var env = findScope(doc, referenceContext);
    // 2. In-scope :use aliases and #strip mappings
    if (env.constAliases().containsKey(kw)) {
      return env.constAliases().get(kw);
    }
    var docEnv = documentScopes.get(doc);
    if (docEnv != null && docEnv.constAliases().containsKey(kw)) {
      return docEnv.constAliases().get(kw);
    }
    // 3. Enclosing :package prefix check for sibling constants
    if (env.packagePath().isPresent()) {
      String localSuffix = kw.startsWith("#") ? kw.substring(1) : kw;
      String siblingFqni = "#" + env.packagePath().get().substring(1) + "/" + localSuffix;
      if (findConstantDefInDocument(doc, siblingFqni).isPresent()) {
        return siblingFqni;
      }
    }
    // 4. Document-global definitions
    if (findConstantDefInDocument(doc, kw).isPresent()) {
      return kw;
    }
    var preludeDoc = StvnPrelude.getPreludeDocument();
    if (preludeDoc != null && findConstantDefInDocument(preludeDoc, kw).isPresent()) {
      return kw;
    }
    return kw;
  }

  private record NamespaceClaim<T extends ParserRuleContext>(
      String identifier,
      @Nullable T defNode,
      String sourceModule,
      ClaimType type
  ) {}

  private enum ClaimType {
    LOCAL,
    RAW_IMPORT,
    RENAMED_IMPORT_LHS,
    RENAMED_IMPORT_RHS
  }

  private static Map<String, DefSource> resolveDefinitionsAndValidate(
      StvnDocumentContext doc,
      List<String> activePaths,
      List<String> activeRawPaths,
      DiagnosticBag diagnosticBag) {
    var seenPaths = new HashSet<String>();
    var collisions = new ArrayList<String>();
    var accumulator = new LinkedHashMap<String, List<NamespaceClaim<TypeDefinitionContext>>>();
    var constAccumulator = new LinkedHashMap<String, List<NamespaceClaim<ConstantDefinitionContext>>>();

    // Pass 1: Implicit ingestion of standard library prelude under :org/stvnadore/prelude/
    if (doc != org.stvnadore.core.stdlib.StvnPrelude.getPreludeDocument()) {
      var preludeDoc = org.stvnadore.core.stdlib.StvnPrelude.getPreludeDocument();
      if (preludeDoc.documentBody() != null && preludeDoc.documentBody().defsEntry() != null) {
        for (var de : preludeDoc.documentBody().defsEntry().defsElement()) {
          if (de.typeDefinition() != null) {
            var pDef = de.typeDefinition();
            String pName = pDef.typeDefTarget() != null ? pDef.typeDefTarget().getText() : pDef.getText();
            accumulator.computeIfAbsent(pName, k -> new ArrayList<>())
                .add(new NamespaceClaim<>(pName, pDef, "Prelude", ClaimType.RAW_IMPORT));
          }
        }
      }
    }

    if (doc.documentBody() != null && doc.documentBody().defsEntry() != null) {
      var defsEntry = doc.documentBody().defsEntry();
      var currentDocPath = documentPaths.get(doc);

      // Iterate over the elements of defsEntry in sorted source order
      var elements = new ArrayList<ParserRuleContext>();
      if (defsEntry.defsElement() != null) {
        for (var de : defsEntry.defsElement()) {
          if (de.includeStmt() != null) elements.add(de.includeStmt());
          else if (de.packageEnclosure() != null) elements.add(de.packageEnclosure());
          else if (de.useStmt() != null) elements.add(de.useStmt());
          else if (de.typeDefinition() != null) elements.add(de.typeDefinition());
          else if (de.constantDefinition() != null) elements.add(de.constantDefinition());
        }
      }
      elements.sort((a, b) -> {
        var startA = a.getStart();
        var startB = b.getStart();
        if (startA != null && startB != null) {
          return Integer.compare(startA.getTokenIndex(), startB.getTokenIndex());
        }
        return 0;
      });

      for (var child : elements) {
        if (child instanceof StvnParser.TypeDefinitionContext typeDef) {
          var typeName = typeDef.typeDefTarget().getText();
          var existingClaims = accumulator.get(typeName);
          if (existingClaims != null) {
            var hasLocal = false;
            for (var claim : existingClaims) {
              if (claim.type() == ClaimType.LOCAL) {
                hasLocal = true;
                break;
              }
            }
            if (hasLocal) {
              diagnosticBag.addError(
                  "Zero-Shadowing constraint violated: " + typeName,
                  typeDef.getStart().getStartIndex(),
                  typeDef.getStop().getStopIndex() + 1,
                  typeDef.getStart().getLine(),
                  typeDef.getStart().getCharPositionInLine(),
                  null,
                  DiagnosticBag.ERR_DUPLICATE_DEF
              );
            }
          }
          accumulator.computeIfAbsent(typeName, k -> new ArrayList<>())
              .add(new NamespaceClaim<>(typeName, typeDef, "Inline Document", ClaimType.LOCAL));
        } else if (child instanceof StvnParser.ConstantDefinitionContext constDef) {
          var constName = constDef.valueKeyword().getText();
          var existingClaims = constAccumulator.get(constName);
          if (existingClaims != null) {
            var hasLocal = false;
            for (var claim : existingClaims) {
              if (claim.type() == ClaimType.LOCAL) {
                hasLocal = true;
                break;
              }
            }
            if (hasLocal) {
              diagnosticBag.addError(
                  "Zero-Shadowing constraint violated: " + constName,
                  constDef.getStart().getStartIndex(),
                  constDef.getStop().getStopIndex() + 1,
                  constDef.getStart().getLine(),
                  constDef.getStart().getCharPositionInLine(),
                  null,
                  DiagnosticBag.ERR_DUPLICATE_DEF
              );
            }
          }
          constAccumulator.computeIfAbsent(constName, k -> new ArrayList<>())
              .add(new NamespaceClaim<>(constName, constDef, "Inline Document", ClaimType.LOCAL));
        } else if (child instanceof StvnParser.IncludeStmtContext includeStmt) {
          if (currentDocPath != null && (currentDocPath.endsWith(".stvn_f") || currentDocPath.endsWith(".stvn_inclf"))) {
            String msg = currentDocPath.endsWith(".stvn_f")
                ? "Flat document or leaf module (.stvn_f / .stvn_inclf) cannot contain include statements: " + currentDocPath
                : "Flat document or leaf module (.stvn_f / .stvn_inclf) cannot contain include statements (Leaf module (.stvn_inclf) cannot contain include statements): " + currentDocPath;
            diagnosticBag.addError(
                msg,
                includeStmt.getStart().getStartIndex(),
                includeStmt.getStop().getStopIndex() + 1,
                includeStmt.getStart().getLine(),
                includeStmt.getStart().getCharPositionInLine(),
                null,
                DiagnosticBag.ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT
            );
          }
          if (includeStmt.includeElement() != null) {
            for (var element : includeStmt.includeElement()) {
              if (element.includeOptionsBlock() != null && element.includeOptionsBlock().includeOption().isEmpty()) {
                diagnosticBag.addError(
                    "Empty directive block in :use or :include statement is invalid; specify {#strip}, alias mappings, or remove '{}'",
                    element.includeOptionsBlock().getStart().getStartIndex(),
                    element.includeOptionsBlock().getStop().getStopIndex() + 1,
                    element.includeOptionsBlock().getStart().getLine(),
                    element.includeOptionsBlock().getStart().getCharPositionInLine(),
                    null,
                    DiagnosticBag.ERR_EMPTY_DIRECTIVE_BLOCK
                );
              }
              if (element.includeAliasBlock() != null && element.includeAliasBlock().includeMapAlias().isEmpty()) {
                diagnosticBag.addError(
                    "Empty directive block in :use or :include statement is invalid; specify {#strip}, alias mappings, or remove '{}'",
                    element.includeAliasBlock().getStart().getStartIndex(),
                    element.includeAliasBlock().getStop().getStopIndex() + 1,
                    element.includeAliasBlock().getStart().getLine(),
                    element.includeAliasBlock().getStart().getCharPositionInLine(),
                    null,
                    DiagnosticBag.ERR_EMPTY_DIRECTIVE_BLOCK
                );
              }
              var rawPathStr = element.stringLiteral().getText();
              var pathVal = extractRawStringValue(rawPathStr);

              if (!seenPaths.add(pathVal)) {
                diagnosticBag.addError(
                    "Duplicate module import detected for path: " + pathVal,
                    element.getStart().getStartIndex(),
                    element.getStop().getStopIndex() + 1,
                    element.getStart().getLine(),
                    element.getStart().getCharPositionInLine(),
                    new DuplicateModuleImportException("Duplicate module import detected for path: " + pathVal),
                    DiagnosticBag.ERR_DUPLICATE_DEF
                );
              }

              var resolvedPath = resolveIncludePath(currentDocPath, pathVal);
              var resolvedPathStr = resolvedPath.toAbsolutePath().toString();

              if (activePaths.contains(resolvedPathStr)) {
                int cycleStartIndex = activePaths.indexOf(resolvedPathStr);
                List<String> rawPathsSlice = new ArrayList<>();
                List<String> canonicalPathsSlice = new ArrayList<>();
                for (int i = cycleStartIndex + 1; i < activePaths.size(); i++) {
                  rawPathsSlice.add(activeRawPaths.get(i));
                  canonicalPathsSlice.add(activePaths.get(i));
                }
                rawPathsSlice.add(pathVal);
                canonicalPathsSlice.add(resolvedPathStr);

                List<String> names = new ArrayList<>();
                String startFile = activePaths.get(cycleStartIndex);
                int lastSlash = startFile.replace('\\', '/').lastIndexOf('/');
                names.add(lastSlash == -1 ? startFile : startFile.substring(lastSlash + 1));

                for (String p : canonicalPathsSlice) {
                  int ls = p.replace('\\', '/').lastIndexOf('/');
                  names.add(ls == -1 ? p : p.substring(ls + 1));
                }
                String trace = String.join(" -> ", names);

                throw new CyclicDependencyException("Cycle detected: " + trace, rawPathsSlice, canonicalPathsSlice);
              }

              StvnDocumentContext importedDoc;
              try {
                var content = Files.readString(resolvedPath);
                var lexer = new org.stvnadore.core.parser.StvnLexer(CharStreams.fromString(content));
                lexer.removeErrorListeners();
                var parser = new org.stvnadore.core.parser.StvnParser(new CommonTokenStream(lexer));
                parser.removeErrorListeners();
                importedDoc = parser.stvnDocument();
              } catch (IOException e) {
                diagnosticBag.addError(
                    "Failed to read include file: " + pathVal,
                    element.getStart().getStartIndex(),
                    element.getStop().getStopIndex() + 1,
                    element.getStart().getLine(),
                    element.getStart().getCharPositionInLine(),
                    e,
                    DiagnosticBag.ERR_MODULE_IMPORT
                );
                continue;
              } catch (Exception e) {
                diagnosticBag.addError(
                    "Failed to parse include file: " + pathVal,
                    element.getStart().getStartIndex(),
                    element.getStop().getStopIndex() + 1,
                    element.getStart().getLine(),
                    element.getStart().getCharPositionInLine(),
                    e,
                    DiagnosticBag.ERR_MODULE_IMPORT
                );
                continue;
              }

              documentPaths.put(importedDoc, resolvedPath.toString());

              var nextActive = new ArrayList<>(activePaths);
              nextActive.add(resolvedPathStr);
              var nextActiveRaw = new ArrayList<>(activeRawPaths);
              nextActiveRaw.add(pathVal);

              var importedDefs = resolveDefinitionsAndValidate(importedDoc, nextActive, nextActiveRaw, diagnosticBag);
              var importedConstDefs = documentConstantDefinitionsCache.getOrDefault(importedDoc, Collections.emptyMap());
              validateDocumentConstraints(importedDoc, diagnosticBag);

              boolean hasStrip = false;
              if (element.includeOptionsBlock() != null) {
                for (var opt : element.includeOptionsBlock().includeOption()) {
                  if (opt.KW_STRIP() != null) {
                    hasStrip = true;
                  }
                }
              }

              for (var entry : importedDefs.entrySet()) {
                var defSource = entry.getValue();
                if ("Prelude".equals(defSource.sourceName())) {
                  continue;
                }
                var originalName = entry.getKey();

                var candidateName = originalName;
                if (hasStrip) {
                  candidateName = sliceTerminal(originalName);
                }

                var importedName = candidateName;
                var isRenamed = false;
                if (element.includeAliasBlock() != null && element.includeAliasBlock().includeMapAlias() != null) {
                  for (var alias : element.includeAliasBlock().includeMapAlias()) {
                    if (alias.typeKeyword(0).getText().equals(candidateName)
                        || alias.typeKeyword(0).getText().equals(originalName)) {
                      importedName = alias.typeKeyword(1).getText();
                      isRenamed = true;
                      break;
                    }
                  }
                }

                if (isRenamed) {
                  accumulator.computeIfAbsent(importedName, k -> new ArrayList<>())
                      .add(new NamespaceClaim<>(importedName, defSource.defNode(), resolvedPath.getFileName().toString(), ClaimType.RENAMED_IMPORT_RHS));
                  accumulator.computeIfAbsent(originalName, k -> new ArrayList<>())
                      .add(new NamespaceClaim<>(originalName, defSource.defNode(), resolvedPath.getFileName().toString(), ClaimType.RENAMED_IMPORT_LHS));
                } else {
                  accumulator.computeIfAbsent(candidateName, k -> new ArrayList<>())
                      .add(new NamespaceClaim<>(candidateName, defSource.defNode(), resolvedPath.getFileName().toString(), ClaimType.RAW_IMPORT));
                }
              }

              for (var entry : importedConstDefs.entrySet()) {
                var originalName = entry.getKey();
                var constSource = entry.getValue();

                var candidateName = originalName;
                if (hasStrip) {
                  candidateName = sliceTerminal(originalName);
                }

                constAccumulator.computeIfAbsent(candidateName, k -> new ArrayList<>())
                    .add(new NamespaceClaim<>(candidateName, constSource.defNode(), resolvedPath.getFileName().toString(), ClaimType.RAW_IMPORT));
              }
            }
          }
        } else if (child instanceof StvnParser.PackageEnclosureContext pkgEnc) {
          processPackageEnclosure(pkgEnc, accumulator, constAccumulator, diagnosticBag);
        } else if (child instanceof StvnParser.UseStmtContext useStmt) {
          validateUseStmt(useStmt, diagnosticBag);
        }
      }
    }

    var localDefs = new LinkedHashMap<String, DefSource>();
    applyEvictionCascade(accumulator, localDefs, collisions, DefSource::new);

    var localConstDefs = new LinkedHashMap<String, ConstantDefSource>();
    applyEvictionCascade(constAccumulator, localConstDefs, collisions, ConstantDefSource::new);
    documentConstantDefinitionsCache.put(doc, localConstDefs);

    // Build ScopedUseEnvironments
    if (doc.documentBody() != null && doc.documentBody().defsEntry() != null) {
      var defsEntry = doc.documentBody().defsEntry();
      var rootUseStmts = new ArrayList<StvnParser.UseStmtContext>();
      var rootLocalTypes = new ArrayList<String>();
      var rootLocalConsts = new ArrayList<String>();

      if (defsEntry.defsElement() != null) {
        for (var de : defsEntry.defsElement()) {
          if (de.useStmt() != null) {
            rootUseStmts.add(de.useStmt());
          } else if (de.typeDefinition() != null) {
            rootLocalTypes.add(de.typeDefinition().typeDefTarget().getText());
          } else if (de.constantDefinition() != null) {
            rootLocalConsts.add(de.constantDefinition().valueKeyword().getText());
          } else if (de.packageEnclosure() != null) {
            var pkgEnc = de.packageEnclosure();
            var pkgPrefix = pkgEnc.packagePath().getText();
            var pkgUseStmts = new ArrayList<StvnParser.UseStmtContext>();
            var pkgLocalTypes = new ArrayList<String>();
            var pkgLocalConsts = new ArrayList<String>();

            if (pkgEnc.packageElement() != null) {
              for (var pe : pkgEnc.packageElement()) {
                if (pe.useStmt() != null) {
                  pkgUseStmts.add(pe.useStmt());
                } else if (pe.typeDefinition() != null) {
                  pkgLocalTypes.add(pe.typeDefinition().typeDefTarget().getText());
                } else if (pe.constantDefinition() != null) {
                  pkgLocalConsts.add(pe.constantDefinition().valueKeyword().getText());
                }
              }
            }
            var pkgEnv = buildScopeEnvironment(doc, pkgUseStmts, localDefs, localConstDefs, pkgPrefix, pkgLocalTypes, pkgLocalConsts, diagnosticBag);
            packageEnclosureScopes.put(pkgEnc, pkgEnv);
          }
        }
      }
      var docEnv = buildScopeEnvironment(doc, rootUseStmts, localDefs, localConstDefs, null, rootLocalTypes, rootLocalConsts, diagnosticBag);
      documentScopes.put(doc, docEnv);
    }

    if (!collisions.isEmpty()) {
      for (var col : collisions) {
        diagnosticBag.addError(
            "Namespace collision(s) detected: " + collisions,
            doc.getStart() != null ? doc.getStart().getStartIndex() : -1,
            doc.getStop() != null ? doc.getStop().getStopIndex() + 1 : -1,
            doc.getStart() != null ? doc.getStart().getLine() : -1,
            doc.getStart() != null ? doc.getStart().getCharPositionInLine() : -1,
            new NamespaceCollisionException("Namespace collision(s) detected: " + collisions),
            DiagnosticBag.ERR_NAMESPACE_COLLISION
        );
      }
    }

    return localDefs;
  }

  private static void processPackageEnclosure(
      StvnParser.PackageEnclosureContext pkgEnc,
      Map<String, List<NamespaceClaim<TypeDefinitionContext>>> accumulator,
      Map<String, List<NamespaceClaim<ConstantDefinitionContext>>> constAccumulator,
      DiagnosticBag diagnosticBag
  ) {
    var pkgPath = pkgEnc.packagePath().getText();
    if (pkgEnc.packageElement() == null) {
      return;
    }
    for (var pe : pkgEnc.packageElement()) {
      if (pe.nestedPackageIllegal() != null) {
        var illegalNode = pe.nestedPackageIllegal();
        diagnosticBag.addError(
            "Nested packages are prohibited: " + illegalNode.packagePath().getText(),
            illegalNode.getStart().getStartIndex(),
            illegalNode.getStop().getStopIndex() + 1,
            illegalNode.getStart().getLine(),
            illegalNode.getStart().getCharPositionInLine(),
            null,
            DiagnosticBag.ERR_NESTED_PACKAGE_PROHIBITED
        );
      } else if (pe.typeDefinition() != null) {
        var typeDef = pe.typeDefinition();
        var localName = typeDef.typeDefTarget().getText();
        var fqni = pkgPath + "/" + localName.substring(1);
        var existingClaims = accumulator.get(fqni);
        if (existingClaims != null) {
          var hasLocal = false;
          for (var claim : existingClaims) {
            if (claim.type() == ClaimType.LOCAL) {
              hasLocal = true;
              break;
            }
          }
          if (hasLocal) {
            diagnosticBag.addError(
                "Zero-Shadowing constraint violated: " + fqni,
                typeDef.getStart().getStartIndex(),
                typeDef.getStop().getStopIndex() + 1,
                typeDef.getStart().getLine(),
                typeDef.getStart().getCharPositionInLine(),
                null,
                DiagnosticBag.ERR_DUPLICATE_DEF
            );
          }
        }
        accumulator.computeIfAbsent(fqni, k -> new ArrayList<>())
            .add(new NamespaceClaim<>(fqni, typeDef, "Inline Document", ClaimType.LOCAL));
      } else if (pe.constantDefinition() != null) {
        var constDef = pe.constantDefinition();
        var localName = constDef.valueKeyword().getText();
        var fqni = "#" + pkgPath.substring(1) + "/" + localName.substring(1);
        var existingClaims = constAccumulator.get(fqni);
        if (existingClaims != null) {
          var hasLocal = false;
          for (var claim : existingClaims) {
            if (claim.type() == ClaimType.LOCAL) {
              hasLocal = true;
              break;
            }
          }
          if (hasLocal) {
            diagnosticBag.addError(
                "Zero-Shadowing constraint violated: " + fqni,
                constDef.getStart().getStartIndex(),
                constDef.getStop().getStopIndex() + 1,
                constDef.getStart().getLine(),
                constDef.getStart().getCharPositionInLine(),
                null,
                DiagnosticBag.ERR_DUPLICATE_DEF
            );
          }
        }
        constAccumulator.computeIfAbsent(fqni, k -> new ArrayList<>())
            .add(new NamespaceClaim<>(fqni, constDef, "Inline Document", ClaimType.LOCAL));
      } else if (pe.useStmt() != null) {
        validateUseStmt(pe.useStmt(), diagnosticBag);
      }
    }
  }

  private static void validateUseStmt(StvnParser.UseStmtContext useStmt, DiagnosticBag diagnosticBag) {
    if (useStmt.useTarget() != null && useStmt.useTarget().useTargetIllegal() != null) {
      var illegalTarget = useStmt.useTarget().useTargetIllegal();
      diagnosticBag.addError(
          "Trailing slash prohibited in :use target: " + illegalTarget.getText(),
          illegalTarget.getStart().getStartIndex(),
          illegalTarget.getStop().getStopIndex() + 1,
          illegalTarget.getStart().getLine(),
          illegalTarget.getStart().getCharPositionInLine(),
          null,
          DiagnosticBag.ERR_TRAILING_SLASH_PROHIBITED
      );
    }
    if (useStmt.useOptionsBlock() != null && useStmt.useOptionsBlock().KW_STRIP().isEmpty()) {
      diagnosticBag.addError(
          "Empty directive block in :use or :include statement is invalid; specify {#strip}, alias mappings, or remove '{}'",
          useStmt.useOptionsBlock().getStart().getStartIndex(),
          useStmt.useOptionsBlock().getStop().getStopIndex() + 1,
          useStmt.useOptionsBlock().getStart().getLine(),
          useStmt.useOptionsBlock().getStart().getCharPositionInLine(),
          null,
          DiagnosticBag.ERR_EMPTY_DIRECTIVE_BLOCK
      );
    }
    if (useStmt.useAliasBlock() != null && useStmt.useAliasBlock().useMapAlias().isEmpty()) {
      diagnosticBag.addError(
          "Empty directive block in :use or :include statement is invalid; specify {#strip}, alias mappings, or remove '{}'",
          useStmt.useAliasBlock().getStart().getStartIndex(),
          useStmt.useAliasBlock().getStop().getStopIndex() + 1,
          useStmt.useAliasBlock().getStart().getLine(),
          useStmt.useAliasBlock().getStart().getCharPositionInLine(),
          null,
          DiagnosticBag.ERR_EMPTY_DIRECTIVE_BLOCK
      );
    }
  }

  private static ScopedUseEnvironment buildScopeEnvironment(
      @Nullable StvnDocumentContext doc,
      List<StvnParser.UseStmtContext> useStmts,
      Map<String, DefSource> allTypes,
      Map<String, ConstantDefSource> allConstants,
      @Nullable String packagePrefix,
      List<String> localTypeNames,
      List<String> localConstNames,
      DiagnosticBag diagnosticBag
  ) {
    var typeClaims = new LinkedHashMap<String, List<NamespaceClaim<TypeDefinitionContext>>>();
    var constClaims = new LinkedHashMap<String, List<NamespaceClaim<ConstantDefinitionContext>>>();
    var typeTargetMap = new LinkedHashMap<String, String>();
    var constTargetMap = new LinkedHashMap<String, String>();

    for (var useStmt : useStmts) {
      if (useStmt.useTarget() == null || useStmt.useTarget().useTargetIllegal() != null) {
        continue;
      }
      var target = useStmt.useTarget().getText();
      boolean hasStrip = useStmt.useOptionsBlock() != null && !useStmt.useOptionsBlock().KW_STRIP().isEmpty();
      var typeAliasMap = new LinkedHashMap<String, String>();
      var constAliasMap = new LinkedHashMap<String, String>();
      if (useStmt.useAliasBlock() != null && useStmt.useAliasBlock().useMapAlias() != null) {
        for (var alias : useStmt.useAliasBlock().useMapAlias()) {
          if (alias.typeKeyword(0) != null && alias.typeKeyword(1) != null) {
            typeAliasMap.put(alias.typeKeyword(0).getText(), alias.typeKeyword(1).getText());
          }
          if (alias.valueKeyword(0) != null && alias.valueKeyword(1) != null) {
            constAliasMap.put(alias.valueKeyword(0).getText(), alias.valueKeyword(1).getText());
          }
        }
      }

      // Types matching target
      for (var entry : allTypes.entrySet()) {
        String fqni = entry.getKey();
        if (fqni.equals(target) || fqni.startsWith(target + "/")) {
          DefSource ds = entry.getValue();
          String stripped = sliceTerminal(fqni);
          if (typeAliasMap.containsKey(fqni)) {
            String alias = typeAliasMap.get(fqni);
            typeClaims.computeIfAbsent(alias, k -> new ArrayList<>())
                .add(new NamespaceClaim<>(alias, ds.defNode(), target, ClaimType.RENAMED_IMPORT_RHS));
            typeTargetMap.put(alias, fqni);
          } else if (typeAliasMap.containsKey(stripped)) {
            String alias = typeAliasMap.get(stripped);
            typeClaims.computeIfAbsent(alias, k -> new ArrayList<>())
                .add(new NamespaceClaim<>(alias, ds.defNode(), target, ClaimType.RENAMED_IMPORT_RHS));
            typeTargetMap.put(alias, fqni);
          } else if (hasStrip) {
            typeClaims.computeIfAbsent(stripped, k -> new ArrayList<>())
                .add(new NamespaceClaim<>(stripped, ds.defNode(), target, ClaimType.RAW_IMPORT));
            typeTargetMap.put(stripped, fqni);
          }
        }
      }

      // Constants matching target
      String constTarget = "#" + (target.startsWith(":") ? target.substring(1) : target);
      for (var entry : allConstants.entrySet()) {
        String fqni = entry.getKey();
        if (fqni.equals(constTarget) || fqni.startsWith(constTarget + "/")) {
          ConstantDefSource cs = entry.getValue();
          String stripped = sliceTerminal(fqni);
          if (constAliasMap.containsKey(fqni)) {
            String alias = constAliasMap.get(fqni);
            constClaims.computeIfAbsent(alias, k -> new ArrayList<>())
                .add(new NamespaceClaim<>(alias, cs.defNode(), target, ClaimType.RENAMED_IMPORT_RHS));
            constTargetMap.put(alias, fqni);
          } else if (constAliasMap.containsKey(stripped)) {
            String alias = constAliasMap.get(stripped);
            constClaims.computeIfAbsent(alias, k -> new ArrayList<>())
                .add(new NamespaceClaim<>(alias, cs.defNode(), target, ClaimType.RENAMED_IMPORT_RHS));
            constTargetMap.put(alias, fqni);
          } else if (hasStrip) {
            constClaims.computeIfAbsent(stripped, k -> new ArrayList<>())
                .add(new NamespaceClaim<>(stripped, cs.defNode(), target, ClaimType.RAW_IMPORT));
            constTargetMap.put(stripped, fqni);
          }
        }
      }
    }

    // Local definitions evict RAW_IMPORT
    for (var localName : localTypeNames) {
      typeClaims.computeIfAbsent(localName, k -> new ArrayList<>())
          .add(new NamespaceClaim<>(localName, null, "Inline Document", ClaimType.LOCAL));
    }
    for (var localName : localConstNames) {
      constClaims.computeIfAbsent(localName, k -> new ArrayList<>())
          .add(new NamespaceClaim<>(localName, null, "Inline Document", ClaimType.LOCAL));
    }

    var scopeCollisions = new ArrayList<String>();
    var resolvedTypes = new LinkedHashMap<String, String>();
    for (var entry : typeClaims.entrySet()) {
      var id = entry.getKey();
      var claims = entry.getValue();
      var hasLocal = claims.stream().anyMatch(c -> c.type() == ClaimType.LOCAL);
      if (hasLocal) {
        if (claims.stream().anyMatch(c -> c.type() == ClaimType.RENAMED_IMPORT_RHS)) {
          scopeCollisions.add(id);
        }
      } else {
        long count = claims.stream().filter(c -> c.type() == ClaimType.RAW_IMPORT || c.type() == ClaimType.RENAMED_IMPORT_RHS).count();
        if (count > 1) {
          scopeCollisions.add(id);
        } else if (count == 1) {
          resolvedTypes.put(id, typeTargetMap.get(id));
        }
      }
    }

    var resolvedConstants = new LinkedHashMap<String, String>();
    for (var entry : constClaims.entrySet()) {
      var id = entry.getKey();
      var claims = entry.getValue();
      var hasLocal = claims.stream().anyMatch(c -> c.type() == ClaimType.LOCAL);
      if (hasLocal) {
        if (claims.stream().anyMatch(c -> c.type() == ClaimType.RENAMED_IMPORT_RHS)) {
          scopeCollisions.add(id);
        }
      } else {
        long count = claims.stream().filter(c -> c.type() == ClaimType.RAW_IMPORT || c.type() == ClaimType.RENAMED_IMPORT_RHS).count();
        if (count > 1) {
          scopeCollisions.add(id);
        } else if (count == 1) {
          resolvedConstants.put(id, constTargetMap.get(id));
        }
      }
    }

    if (!scopeCollisions.isEmpty() && doc != null) {
      for (var col : scopeCollisions) {
        diagnosticBag.addError(
            "Namespace collision(s) detected: " + scopeCollisions,
            doc.getStart() != null ? doc.getStart().getStartIndex() : -1,
            doc.getStop() != null ? doc.getStop().getStopIndex() + 1 : -1,
            doc.getStart() != null ? doc.getStart().getLine() : -1,
            doc.getStart() != null ? doc.getStart().getCharPositionInLine() : -1,
            new NamespaceCollisionException("Namespace collision(s) detected: " + scopeCollisions),
            DiagnosticBag.ERR_NAMESPACE_COLLISION
        );
      }
    }

    var reverseTypeAliases = new LinkedHashMap<String, String>();
    for (var entry : resolvedTypes.entrySet()) {
      reverseTypeAliases.put(entry.getValue(), entry.getKey());
    }
    return new ScopedUseEnvironment(Optional.ofNullable(packagePrefix), resolvedTypes, resolvedConstants, reverseTypeAliases);
  }

  @FunctionalInterface
  private interface DefFactory<N extends ParserRuleContext, S> {
    S create(N node, String sourceModule);
  }

  private static <N extends ParserRuleContext, S> void applyEvictionCascade(
      Map<String, List<NamespaceClaim<N>>> accumulator,
      Map<String, S> destination,
      List<String> collisions,
      DefFactory<N, S> factory) {
    for (var entry : accumulator.entrySet()) {
      var id = entry.getKey();
      var claims = entry.getValue();

      var hasLocal = false;
      NamespaceClaim<N> localClaim = null;
      for (var c : claims) {
        if (c.type() == ClaimType.LOCAL) {
          hasLocal = true;
          localClaim = c;
          break;
        }
      }

      if (hasLocal) {
        var filteredClaims = new ArrayList<NamespaceClaim<N>>();
        filteredClaims.add(localClaim);
        for (var c : claims) {
          if (c.type() == ClaimType.RENAMED_IMPORT_RHS) {
            filteredClaims.add(c);
          }
        }

        if (filteredClaims.size() > 1) {
          collisions.add(id);
        } else {
          destination.put(id, factory.create(localClaim.defNode(), localClaim.sourceModule()));
        }
      } else {
        var rawClaims = new ArrayList<NamespaceClaim<N>>();
        var lhsClaims = new ArrayList<NamespaceClaim<N>>();
        var rhsClaims = new ArrayList<NamespaceClaim<N>>();
        var seenRawModuleOrigins = new HashSet<String>();

        for (var c : claims) {
          if (c.type() == ClaimType.RAW_IMPORT) {
            // Deduplicate identical claims imported from the exact same source module via diamond paths
            if (seenRawModuleOrigins.add(c.sourceModule())) {
              rawClaims.add(c);
            }
          } else if (c.type() == ClaimType.RENAMED_IMPORT_LHS) {
            lhsClaims.add(c);
          } else if (c.type() == ClaimType.RENAMED_IMPORT_RHS) {
            rhsClaims.add(c);
          }
        }

        if (!lhsClaims.isEmpty() && !rawClaims.isEmpty()) {
          lhsClaims.clear();
        } else if (lhsClaims.size() > 1) {
          lhsClaims.clear();
        }

        var remainingClaims = new ArrayList<NamespaceClaim<N>>();
        remainingClaims.addAll(rawClaims);
        remainingClaims.addAll(lhsClaims);
        remainingClaims.addAll(rhsClaims);

        if (remainingClaims.size() == 1) {
          var single = remainingClaims.get(0);
          destination.put(id, factory.create(single.defNode(), single.sourceModule()));
        } else if (remainingClaims.size() > 1) {
          collisions.add(id);
        }
      }
    }
  }

  /**
   * Default constructor.
   */
  public StvnTypeResolver() {
  }

  /**
   * Classification of STVN literal raw types detected during parsing.
   */
  public enum LiteralType {
    /** Unknown/unparseable literal type. */
    UNKNOWN,
    /** Base integer literal. */
    INTEGER_LITERAL,
    /** Base float literal. */
    FLOAT_LITERAL,
    /** Base string literal. */
    STRING_LITERAL,
    /** Base boolean literal. */
    BOOLEAN_LITERAL,
    /** Explicit tag Option value. */
    EXPLICIT_OPTION_VALUE,
    /** Explicit tag Either value. */
    EXPLICIT_EITHER_VALUE,
    /** Explicit tag Union value. */
    EXPLICIT_UNION_VALUE,
    /** Sequence/list literal grouping. */
    LIST_LITERAL,
    /** Map key-value literal grouping. */
    MAP_LITERAL,
    /** Tuple heterogeneous literal sequence. */
    TUPLE_LITERAL,
    /** Keyword nominal constant. */
    KEYWORD_LITERAL
  }

  private static final Set<String> TYPES_FLOAT = Set.of(
      ":Float",
      ":Float32",
      ":Float64",
      ":FloatExact");

  private static boolean isFloatType(String type) {
    return TYPES_FLOAT.contains(type);
  }

  private static boolean isSeqType(String type) {
    return type.equals(":Seq") || type.equals(":SeqNonEmpty");
  }

  private static boolean isSetType(String type) {
    return type.equals(":Set") || type.equals(":SetNonEmpty");
  }

  private static final Set<String> TYPES_MAP = Set.of(
      ":Map",
      ":MapNonEmpty",
      ":MapInv",
      ":MapInvNonEmpty",
      ":MapEntry");

  private static boolean isMapType(String type) {
    return TYPES_MAP.contains(type);
  }

  private static final Set<String> TYPES_TIME = Set.of(
      ":TimeEpoch");

  private static boolean isTimeEpochType(String type) {
    return TYPES_TIME.contains(type);
  }

  private static final Set<String> TYPES_DATE_TIME = Set.of(
      ":DateTime");

  /**
   * Tests whether a type name represents a temporal date-time scalar.
   *
   * @param type the type name keyword token (e.g. {@code ":DateTime"})
   * @return true if the type is a temporal date-time scalar
   */
  public static boolean isDateTimeType(String type) {
    return TYPES_DATE_TIME.contains(type);
  }

  private static boolean isIntegerType(String type) {
    return (type.startsWith(":Int") && type.substring(4).matches("\\d*")) ||
        (type.startsWith(":Uint") && type.substring(5).matches("\\d*")) ||
        isTimeEpochType(type);
  }

  private static boolean isStringType(String type) {
    return (type.startsWith(":StringFixed") && type.substring(12).matches("\\d*")) ||
        (type.startsWith(":StringNonEmpty") && type.substring(15).matches("\\d*")) ||
        (type.startsWith(":String") && !type.startsWith(":StringFixed") && !type.startsWith(":StringNonEmpty") && type.substring(7).matches("\\d*"));
  }

  /**
   * Represents the source definition node of a resolved nominal type, along with the source name context.
   *
   * @param defNode    the ANTLR parsing context node of the type definition
   * @param sourceName the name of the source module or document where this definition resides
   */
  public record DefSource(
      TypeDefinitionContext defNode,
      String sourceName
  ) {
    /**
     * Canonical constructor validating that all parameters are non-null.
     *
     * @param defNode    the type definition parse context node
     * @param sourceName the name of the source module
     */
    public DefSource {
      if (defNode == null) {
        throw new MalformedSchemaException("Type definition node is null in DefSource");
      }
      java.util.Objects.requireNonNull(sourceName);
    }
  }

  /**
   * Represents the source definition node of a resolved typed constant, along with the source name context.
   *
   * @param defNode    the ANTLR parsing context node of the constant definition
   * @param sourceName the name of the source module or document where this definition resides
   */
  public record ConstantDefSource(
      ConstantDefinitionContext defNode,
      String sourceName
  ) {
    /**
     * Canonical constructor validating that all parameters are non-null.
     *
     * @param defNode    the constant definition parse context node
     * @param sourceName the name of the source module
     */
    public ConstantDefSource {
      if (defNode == null) {
        throw new MalformedSchemaException("Constant definition node is null in ConstantDefSource");
      }
      java.util.Objects.requireNonNull(sourceName);
    }
  }

  /**
   * Consolidates all metadata constraints declared on a type schema.
   *
   * @param minIncl           optional inclusive minimum value boundary
   * @param minExcl           optional exclusive minimum value boundary
   * @param maxIncl           optional inclusive maximum value boundary
   * @param maxExcl           optional exclusive maximum value boundary
   * @param regex             optional regular expression pattern to validate strings
   * @param preserveIndent    if true, preserves formatting indentation for multi-line block strings
   * @param equatable         optional user override for the {@code #equatable} trait
   * @param comparable        optional user override for the {@code #comparable} trait
   * @param explicitOverrides list of explicit traits overridden by the developer
   * @param filterIncl        optional immutable list of variants included in enum subset
   * @param filterExcl        optional immutable list of variants excluded from enum subset
   * @param size              optional bit-width or size constraint
   * @param unsigned          if true, integer type is unsigned
   * @param exact             if true, float type enforces exact arbitrary-precision decimal representation
   * @param minSize           optional minimum collection or string cardinality
   * @param maxSize           optional maximum collection or string cardinality
   * @param invertible        if true, map is bidirectional and invertible
   * @param scale             optional temporal epoch scale flag (#s, #ms, #us, #ns)
   * @param offset            if true, temporal datetime has numerical offset
   * @param zoned             if true, temporal datetime has timezone identifier
   * @param audited           if true, temporal datetime retains full audited representation
   * @param dateMinIncl       optional inclusive minimum ISO-8601 boundary
   * @param dateMinExcl       optional exclusive minimum ISO-8601 boundary
   * @param dateMaxIncl       optional inclusive maximum ISO-8601 boundary
   * @param dateMaxExcl       optional exclusive maximum ISO-8601 boundary
   */
  public record StvnConstraints(
      Optional<BigDecimal> minIncl,
      Optional<BigDecimal> minExcl,
      Optional<BigDecimal> maxIncl,
      Optional<BigDecimal> maxExcl,
      Optional<String> regex,
      boolean preserveIndent,
      Optional<Boolean> equatable,
      Optional<Boolean> comparable,
      java.util.List<String> explicitOverrides,
      Optional<List<String>> filterIncl,
      Optional<List<String>> filterExcl,
      Optional<Integer> size,
      boolean unsigned,
      boolean exact,
      Optional<Integer> minSize,
      Optional<Integer> maxSize,
      boolean invertible,
      Optional<String> scale,
      boolean offset,
      boolean zoned,
      boolean audited,
      Optional<String> dateMinIncl,
      Optional<String> dateMinExcl,
      Optional<String> dateMaxIncl,
      Optional<String> dateMaxExcl
  ) {
    /**
     * Backward-compatible 9-parameter constructor defaulting filterIncl, filterExcl, and 2.0.0 facets to empty.
     *
     * @param minIncl           optional inclusive minimum value boundary
     * @param minExcl           optional exclusive minimum value boundary
     * @param maxIncl           optional inclusive maximum value boundary
     * @param maxExcl           optional exclusive maximum value boundary
     * @param regex             optional regular expression pattern to validate strings
     * @param preserveIndent    if true, preserves formatting indentation for multi-line block strings
     * @param equatable         optional user override for the {@code #equatable} trait
     * @param comparable        optional user override for the {@code #comparable} trait
     * @param explicitOverrides list of explicit traits overridden by the developer
     */
    public StvnConstraints(
        Optional<BigDecimal> minIncl,
        Optional<BigDecimal> minExcl,
        Optional<BigDecimal> maxIncl,
        Optional<BigDecimal> maxExcl,
        Optional<String> regex,
        boolean preserveIndent,
        Optional<Boolean> equatable,
        Optional<Boolean> comparable,
        @Nullable List<String> explicitOverrides
    ) {
      this(minIncl, minExcl, maxIncl, maxExcl, regex, preserveIndent, equatable, comparable, explicitOverrides, Optional.empty(), Optional.empty(),
          Optional.empty(), false, false, Optional.empty(), Optional.empty(), false, Optional.empty(), false, false, false,
          Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * Backward-compatible 11-parameter constructor defaulting 2.0.0 facets to empty.
     *
     * @param minIncl           optional inclusive minimum value boundary
     * @param minExcl           optional exclusive minimum value boundary
     * @param maxIncl           optional inclusive maximum value boundary
     * @param maxExcl           optional exclusive maximum value boundary
     * @param regex             optional regular expression pattern to validate strings
     * @param preserveIndent    if true, preserves formatting indentation for multi-line block strings
     * @param equatable         optional user override for the {@code #equatable} trait
     * @param comparable        optional user override for the {@code #comparable} trait
     * @param explicitOverrides list of explicit traits overridden by the developer
     * @param filterIncl        optional immutable list of variants included in enum subset
     * @param filterExcl        optional immutable list of variants excluded from enum subset
     */
    public StvnConstraints(
        Optional<BigDecimal> minIncl,
        Optional<BigDecimal> minExcl,
        Optional<BigDecimal> maxIncl,
        Optional<BigDecimal> maxExcl,
        Optional<String> regex,
        boolean preserveIndent,
        Optional<Boolean> equatable,
        Optional<Boolean> comparable,
        @Nullable List<String> explicitOverrides,
        Optional<List<String>> filterIncl,
        Optional<List<String>> filterExcl
    ) {
      this(minIncl, minExcl, maxIncl, maxExcl, regex, preserveIndent, equatable, comparable, explicitOverrides, filterIncl, filterExcl,
          Optional.empty(), false, false, Optional.empty(), Optional.empty(), false, Optional.empty(), false, false, false,
          Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * Backward-compatible 21-parameter constructor from 2.0.0-M1.
     *
     * @param minIncl           optional inclusive minimum value boundary
     * @param minExcl           optional exclusive minimum value boundary
     * @param maxIncl           optional inclusive maximum value boundary
     * @param maxExcl           optional exclusive maximum value boundary
     * @param regex             optional regular expression pattern to validate strings
     * @param preserveIndent    if true, preserves formatting indentation for multi-line block strings
     * @param equatable         optional user override for the {@code #equatable} trait
     * @param comparable        optional user override for the {@code #comparable} trait
     * @param explicitOverrides list of explicit traits overridden by the developer
     * @param filterIncl        optional immutable list of variants included in enum subset
     * @param filterExcl        optional immutable list of variants excluded from enum subset
     * @param size              optional bit-width or size constraint
     * @param unsigned          if true, integer type is unsigned
     * @param exact             if true, float type enforces exact arbitrary-precision decimal representation
     * @param minSize           optional minimum collection or string cardinality
     * @param maxSize           optional maximum collection or string cardinality
     * @param invertible        if true, map is bidirectional and invertible
     * @param scale             optional temporal epoch scale (s, ms, us, ns)
     * @param offset            if true, temporal datetime has numerical offset
     * @param zoned             if true, temporal datetime has timezone identifier
     * @param audited           if true, temporal datetime retains full audited representation
     */
    public StvnConstraints(
        Optional<BigDecimal> minIncl,
        Optional<BigDecimal> minExcl,
        Optional<BigDecimal> maxIncl,
        Optional<BigDecimal> maxExcl,
        Optional<String> regex,
        boolean preserveIndent,
        Optional<Boolean> equatable,
        Optional<Boolean> comparable,
        @Nullable List<String> explicitOverrides,
        Optional<List<String>> filterIncl,
        Optional<List<String>> filterExcl,
        Optional<Integer> size,
        boolean unsigned,
        boolean exact,
        Optional<Integer> minSize,
        Optional<Integer> maxSize,
        boolean invertible,
        Optional<String> scale,
        boolean offset,
        boolean zoned,
        boolean audited
    ) {
      this(minIncl, minExcl, maxIncl, maxExcl, regex, preserveIndent, equatable, comparable, explicitOverrides, filterIncl, filterExcl,
          size, unsigned, exact, minSize, maxSize, invertible, scale, offset, zoned, audited,
          Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * Canonical constructor validating that all optional parameters are non-null and copying lists.
     *
     * @param minIncl           optional inclusive minimum value boundary
     * @param minExcl           optional exclusive minimum value boundary
     * @param maxIncl           optional inclusive maximum value boundary
     * @param maxExcl           optional exclusive maximum value boundary
     * @param regex             optional regular expression pattern to validate strings
     * @param preserveIndent    if true, preserves formatting indentation for multi-line block strings
     * @param equatable         optional user override for the {@code #equatable} trait
     * @param comparable        optional user override for the {@code #comparable} trait
     * @param explicitOverrides list of explicit traits overridden by the developer
     * @param filterIncl        optional immutable list of variants included in enum subset
     * @param filterExcl        optional immutable list of variants excluded from enum subset
     * @param size              optional bit-width or size constraint
     * @param unsigned          if true, integer type is unsigned
     * @param exact             if true, float type enforces exact arbitrary-precision decimal representation
     * @param minSize           optional minimum collection or string cardinality
     * @param maxSize           optional maximum collection or string cardinality
     * @param invertible        if true, map is bidirectional and invertible
     * @param scale             optional temporal epoch scale (s, ms, us, ns)
     * @param offset            if true, temporal datetime has numerical offset
     * @param zoned             if true, temporal datetime has timezone identifier
     * @param audited           if true, temporal datetime retains full audited representation
     * @param dateMinIncl       optional inclusive minimum date string boundary
     * @param dateMinExcl       optional exclusive minimum date string boundary
     * @param dateMaxIncl       optional inclusive maximum date string boundary
     * @param dateMaxExcl       optional exclusive maximum date string boundary
     */
    public StvnConstraints(
        Optional<BigDecimal> minIncl,
        Optional<BigDecimal> minExcl,
        Optional<BigDecimal> maxIncl,
        Optional<BigDecimal> maxExcl,
        Optional<String> regex,
        boolean preserveIndent,
        Optional<Boolean> equatable,
        Optional<Boolean> comparable,
        @Nullable List<String> explicitOverrides,
        Optional<List<String>> filterIncl,
        Optional<List<String>> filterExcl,
        Optional<Integer> size,
        boolean unsigned,
        boolean exact,
        Optional<Integer> minSize,
        Optional<Integer> maxSize,
        boolean invertible,
        Optional<String> scale,
        boolean offset,
        boolean zoned,
        boolean audited,
        Optional<String> dateMinIncl,
        Optional<String> dateMinExcl,
        Optional<String> dateMaxIncl,
        Optional<String> dateMaxExcl
    ) {
      this.minIncl = java.util.Objects.requireNonNull(minIncl);
      this.minExcl = java.util.Objects.requireNonNull(minExcl);
      this.maxIncl = java.util.Objects.requireNonNull(maxIncl);
      this.maxExcl = java.util.Objects.requireNonNull(maxExcl);
      this.regex = java.util.Objects.requireNonNull(regex);
      this.preserveIndent = preserveIndent;
      this.equatable = java.util.Objects.requireNonNull(equatable);
      this.comparable = java.util.Objects.requireNonNull(comparable);
      this.explicitOverrides = explicitOverrides != null
          ? java.util.List.copyOf(explicitOverrides)
          : java.util.List.of();
      this.filterIncl = java.util.Objects.requireNonNull(filterIncl).map(java.util.List::copyOf);
      this.filterExcl = java.util.Objects.requireNonNull(filterExcl).map(java.util.List::copyOf);
      this.size = java.util.Objects.requireNonNull(size);
      this.unsigned = unsigned;
      this.exact = exact;
      this.minSize = java.util.Objects.requireNonNull(minSize);
      this.maxSize = java.util.Objects.requireNonNull(maxSize);
      this.invertible = invertible;
      this.scale = java.util.Objects.requireNonNull(scale);
      this.offset = offset;
      this.zoned = zoned;
      this.audited = audited;
      this.dateMinIncl = java.util.Objects.requireNonNull(dateMinIncl);
      this.dateMinExcl = java.util.Objects.requireNonNull(dateMinExcl);
      this.dateMaxIncl = java.util.Objects.requireNonNull(dateMaxIncl);
      this.dateMaxExcl = java.util.Objects.requireNonNull(dateMaxExcl);
    }

    /**
     * Backward-compatible accessor delegating to {@link #scale()}.
     *
     * @return the scale facet value, or empty
     */
    public Optional<String> unit() {
      return scale;
    }

    /**
     * Returns an empty StvnConstraints instance with no constraints applied.
     *
     * @return the empty constraints instance
     */
    public static StvnConstraints empty() {
      return new StvnConstraints(
          Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
          Optional.empty(), false, Optional.empty(), Optional.empty(), java.util.List.of(),
          Optional.empty(), Optional.empty(),
          Optional.empty(), false, false, Optional.empty(), Optional.empty(), false, Optional.empty(), false, false, false,
          Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()
      );
    }

    /**
     * Returns a copy with the specified size bit-width facet.
     *
     * @param bitWidth the bit-width to set
     * @return a new {@link StvnConstraints} instance
     */
    public StvnConstraints withSize(int bitWidth) {
      var overrides = new ArrayList<>(explicitOverrides);
      if (!overrides.contains("size")) overrides.add("size");
      return new StvnConstraints(
          minIncl, minExcl, maxIncl, maxExcl, regex, preserveIndent, equatable, comparable, overrides,
          filterIncl, filterExcl, Optional.of(bitWidth), unsigned, exact, minSize, maxSize, invertible, scale, offset, zoned, audited,
          dateMinIncl, dateMinExcl, dateMaxIncl, dateMaxExcl
      );
    }

    /**
     * Returns a copy with the specified unsigned flag facet.
     *
     * @param isUnsigned true if unsigned
     * @return a new {@link StvnConstraints} instance
     */
    public StvnConstraints withUnsigned(boolean isUnsigned) {
      var overrides = new ArrayList<>(explicitOverrides);
      if (isUnsigned && !overrides.contains("unsigned")) overrides.add("unsigned");
      else if (!isUnsigned) overrides.remove("unsigned");
      return new StvnConstraints(
          minIncl, minExcl, maxIncl, maxExcl, regex, preserveIndent, equatable, comparable, overrides,
          filterIncl, filterExcl, size, isUnsigned, exact, minSize, maxSize, invertible, scale, offset, zoned, audited,
          dateMinIncl, dateMinExcl, dateMaxIncl, dateMaxExcl
      );
    }

    /**
     * Returns a copy with the specified exact decimal facet.
     *
     * @param isExact true if exact
     * @return a new {@link StvnConstraints} instance
     */
    public StvnConstraints withExact(boolean isExact) {
      var overrides = new ArrayList<>(explicitOverrides);
      if (isExact && !overrides.contains("exact")) overrides.add("exact");
      else if (!isExact) overrides.remove("exact");
      return new StvnConstraints(
          minIncl, minExcl, maxIncl, maxExcl, regex, preserveIndent, equatable, comparable, overrides,
          filterIncl, filterExcl, size, unsigned, isExact, minSize, maxSize, invertible, scale, offset, zoned, audited,
          dateMinIncl, dateMinExcl, dateMaxIncl, dateMaxExcl
      );
    }

    /**
     * Returns a copy with the specified invertible map facet.
     *
     * @param isInvertible true if invertible
     * @return a new {@link StvnConstraints} instance
     */
    public StvnConstraints withInvertible(boolean isInvertible) {
      var overrides = new ArrayList<>(explicitOverrides);
      if (isInvertible && !overrides.contains("invertible")) overrides.add("invertible");
      else if (!isInvertible) overrides.remove("invertible");
      return new StvnConstraints(
          minIncl, minExcl, maxIncl, maxExcl, regex, preserveIndent, equatable, comparable, overrides,
          filterIncl, filterExcl, size, unsigned, exact, minSize, maxSize, isInvertible, scale, offset, zoned, audited,
          dateMinIncl, dateMinExcl, dateMaxIncl, dateMaxExcl
      );
    }

    /**
     * Returns a copy with the specified regex constraint pattern.
     *
     * @param regex optional regular expression pattern
     * @return a new {@link StvnConstraints} instance
     */
    public StvnConstraints withRegex(Optional<String> regex) {
      return new StvnConstraints(
          minIncl, minExcl, maxIncl, maxExcl, regex, preserveIndent, equatable, comparable, explicitOverrides,
          filterIncl, filterExcl, size, unsigned, exact, minSize, maxSize, invertible, scale, offset, zoned, audited,
          dateMinIncl, dateMinExcl, dateMaxIncl, dateMaxExcl
      );
    }

    /**
     * Returns a copy with the specified preserveIndent formatting flag.
     *
     * @param preserveIndent    true if indent preservation is enabled
     * @param explicitOverrides list of explicit developer overrides
     * @return a new {@link StvnConstraints} instance
     */
    public StvnConstraints withPreserveIndent(boolean preserveIndent, List<String> explicitOverrides) {
      return new StvnConstraints(
          minIncl, minExcl, maxIncl, maxExcl, regex, preserveIndent, equatable, comparable, explicitOverrides,
          filterIncl, filterExcl, size, unsigned, exact, minSize, maxSize, invertible, scale, offset, zoned, audited,
          dateMinIncl, dateMinExcl, dateMaxIncl, dateMaxExcl
      );
    }

    /**
     * Returns a copy with the specified trait overrides.
     *
     * @param equatable         optional equatable trait override
     * @param comparable        optional comparable trait override
     * @param explicitOverrides list of explicit developer overrides
     * @return a new {@link StvnConstraints} instance
     */
    public StvnConstraints withTraits(Optional<Boolean> equatable, Optional<Boolean> comparable, List<String> explicitOverrides) {
      return new StvnConstraints(
          minIncl, minExcl, maxIncl, maxExcl, regex, preserveIndent, equatable, comparable, explicitOverrides,
          filterIncl, filterExcl, size, unsigned, exact, minSize, maxSize, invertible, scale, offset, zoned, audited,
          dateMinIncl, dateMinExcl, dateMaxIncl, dateMaxExcl
      );
    }

    /**
     * Merges this constraints instance with another (typically representing inner or base schema constraints),
     * where local overrides declared on this instance take precedence.
     *
     * @param inner the inner constraints to merge with
     * @return a new merged {@link StvnConstraints} instance
     */
    public StvnConstraints merge(StvnConstraints inner) {
      if (inner == null) {
        throw new MalformedSchemaException("Inner constraints are null in merge");
      }
      Optional<BigDecimal> resMinIncl = Optional.empty();
      Optional<BigDecimal> resMinExcl = Optional.empty();
      Optional<BigDecimal> resMaxIncl = Optional.empty();
      Optional<BigDecimal> resMaxExcl = Optional.empty();

      if (this.minIncl.isPresent()) {
        resMinIncl = this.minIncl;
      } else if (this.minExcl.isPresent()) {
        resMinExcl = this.minExcl;
      } else {
        resMinIncl = inner.minIncl;
        resMinExcl = inner.minExcl;
      }

      if (this.maxIncl.isPresent()) {
        resMaxIncl = this.maxIncl;
      } else if (this.maxExcl.isPresent()) {
        resMaxExcl = this.maxExcl;
      } else {
        resMaxIncl = inner.maxIncl;
        resMaxExcl = inner.maxExcl;
      }

      Optional<String> resDateMinIncl = Optional.empty();
      Optional<String> resDateMinExcl = Optional.empty();
      Optional<String> resDateMaxIncl = Optional.empty();
      Optional<String> resDateMaxExcl = Optional.empty();

      if (this.dateMinIncl.isPresent()) {
        resDateMinIncl = this.dateMinIncl;
      } else if (this.dateMinExcl.isPresent()) {
        resDateMinExcl = this.dateMinExcl;
      } else {
        resDateMinIncl = inner.dateMinIncl;
        resDateMinExcl = inner.dateMinExcl;
      }

      if (this.dateMaxIncl.isPresent()) {
        resDateMaxIncl = this.dateMaxIncl;
      } else if (this.dateMaxExcl.isPresent()) {
        resDateMaxExcl = this.dateMaxExcl;
      } else {
        resDateMaxIncl = inner.dateMaxIncl;
        resDateMaxExcl = inner.dateMaxExcl;
      }

      var mergedOverrides = new java.util.LinkedHashSet<String>();
      mergedOverrides.addAll(this.explicitOverrides);
      mergedOverrides.addAll(inner.explicitOverrides);

      Optional<Boolean> resEquatable = this.explicitOverrides.contains("equatable")
          ? this.equatable
          : inner.equatable;
      Optional<Boolean> resComparable = this.explicitOverrides.contains("comparable")
          ? this.comparable
          : inner.comparable;
      boolean resPreserveIndent = this.explicitOverrides.contains("preserveIndent")
          ? this.preserveIndent
          : inner.preserveIndent;

      Optional<String> resRegex = this.regex.isPresent()
          ? this.regex
          : inner.regex;

      Optional<List<String>> resFilterIncl = this.filterIncl.isPresent()
          ? this.filterIncl
          : inner.filterIncl;
      Optional<List<String>> resFilterExcl = this.filterExcl.isPresent()
          ? this.filterExcl
          : inner.filterExcl;

      Optional<Integer> resSize = this.explicitOverrides.contains("size") || this.size.isPresent()
          ? this.size
          : inner.size;
      boolean resUnsigned = this.explicitOverrides.contains("unsigned") ? this.unsigned : (this.unsigned || inner.unsigned);
      boolean resExact = this.explicitOverrides.contains("exact") ? this.exact : (this.exact || inner.exact);
      Optional<Integer> resMinSize = this.explicitOverrides.contains("minSize") || this.minSize.isPresent()
          ? this.minSize
          : inner.minSize;
      Optional<Integer> resMaxSize = this.explicitOverrides.contains("maxSize") || this.maxSize.isPresent()
          ? this.maxSize
          : inner.maxSize;
      boolean resInvertible = this.explicitOverrides.contains("invertible") ? this.invertible : (this.invertible || inner.invertible);
      Optional<String> resScale = this.explicitOverrides.contains("scale") || this.explicitOverrides.contains("unit") || this.scale.isPresent()
          ? this.scale
          : inner.scale;
      boolean resOffset = this.explicitOverrides.contains("offset") ? this.offset : (this.offset || inner.offset);
      boolean resZoned = this.explicitOverrides.contains("zoned") ? this.zoned : (this.zoned || inner.zoned);
      boolean resAudited = this.explicitOverrides.contains("audited") ? this.audited : (this.audited || inner.audited);

      return new StvnConstraints(
          resMinIncl, resMinExcl, resMaxIncl, resMaxExcl,
          resRegex,
          resPreserveIndent,
          resEquatable,
          resComparable,
          java.util.List.copyOf(mergedOverrides),
          resFilterIncl,
          resFilterExcl,
          resSize,
          resUnsigned,
          resExact,
          resMinSize,
          resMaxSize,
          resInvertible,
          resScale,
          resOffset,
          resZoned,
          resAudited,
          resDateMinIncl,
          resDateMinExcl,
          resDateMaxIncl,
          resDateMaxExcl
      );
    }

    /**
     * Returns a string representation of the consolidated constraints.
     *
     * @return a string describing the constraints
     */
    @Override
    public String toString() {
      return "StvnConstraints[" +
          "minIncl=" + minIncl.orElse(null) +
          ", minExcl=" + minExcl.orElse(null) +
          ", maxIncl=" + maxIncl.orElse(null) +
          ", maxExcl=" + maxExcl.orElse(null) +
          ", regex=" + regex.orElse(null) +
          ", preserveIndent=" + preserveIndent +
          ", equatable=" + equatable.orElse(null) +
          ", comparable=" + comparable.orElse(null) +
          ", explicitOverrides=" + explicitOverrides +
          ", filterIncl=" + filterIncl.orElse(null) +
          ", filterExcl=" + filterExcl.orElse(null) +
          ']';
    }
  }

  /**
   * Represents a schema configuration that has been successfully resolved and validated,
   * consolidated with nominal definitions, default constraints, and structural traits.
   *
   * @param node              the AST schema type context node
   * @param constraints       the consolidated constraints for this schema
   * @param aliasName         the nominal alias name of the type, if any
   * @param implicitUnionTag  the implicit variant index if resolved as an untagged union branch
   * @param sumTypeNode       the parsing context of the sum type, if any
   * @param underlyingSchema  the underlying schema, if this schema aliases or delegates to another schema
   * @param localConstraints  the constraints defined locally on this specific schema node
   * @param isPoisonedSentinel true if this schema instance represents a poisoned error sentinel
   * @param enumSubset         the resolved enum subset metadata, if this schema represents an enum subset
   */
  public record ResolvedSchema(
      SchemaTypeContext node,
      StvnConstraints constraints,
      Optional<String> aliasName,
      Optional<Integer> implicitUnionTag,
      Optional<SumTypeContext> sumTypeNode,
      Optional<ResolvedSchema> underlyingSchema,
      Optional<StvnConstraints> localConstraints,
      boolean isPoisonedSentinel,
      Optional<ResolvedType.EnumSubset> enumSubset
  ) {
    /**
     * Canonical constructor validating that all optional parameters are non-null.
     */
    public ResolvedSchema {
      java.util.Objects.requireNonNull(node);
      java.util.Objects.requireNonNull(aliasName);
      java.util.Objects.requireNonNull(implicitUnionTag);
      java.util.Objects.requireNonNull(sumTypeNode);
      java.util.Objects.requireNonNull(underlyingSchema);
      java.util.Objects.requireNonNull(localConstraints);
      java.util.Objects.requireNonNull(enumSubset);
    }

    /**
     * Backward-compatible 8-arg constructor defaulting enumSubset to empty.
     *
     * @param node               schema type parse context
     * @param constraints        effective constraints
     * @param aliasName          optional nominal alias name
     * @param implicitUnionTag   optional implicit union branch tag index
     * @param sumTypeNode        optional AST context for sum type definitions
     * @param underlyingSchema   optional underlying aliased schema
     * @param localConstraints   optional localized schema constraints
     * @param isPoisonedSentinel whether this schema represents an unresolvable error sentinel
     */
    public ResolvedSchema(
        SchemaTypeContext node,
        StvnConstraints constraints,
        Optional<String> aliasName,
        Optional<Integer> implicitUnionTag,
        Optional<SumTypeContext> sumTypeNode,
        Optional<ResolvedSchema> underlyingSchema,
        Optional<StvnConstraints> localConstraints,
        boolean isPoisonedSentinel
    ) {
      this(node, constraints, aliasName, implicitUnionTag, sumTypeNode, underlyingSchema, localConstraints, isPoisonedSentinel, Optional.empty());
    }

    /**
     * Backward-compatible 7-arg constructor defaulting isPoisonedSentinel to false and enumSubset to empty.
     *
     * @param node             schema type parse context
     * @param constraints      effective constraints
     * @param aliasName        optional nominal alias name
     * @param implicitUnionTag optional implicit union branch tag index
     * @param sumTypeNode      optional AST context for sum type definitions
     * @param underlyingSchema optional underlying aliased schema
     * @param localConstraints optional localized schema constraints
     */
    public ResolvedSchema(
        SchemaTypeContext node,
        StvnConstraints constraints,
        Optional<String> aliasName,
        Optional<Integer> implicitUnionTag,
        Optional<SumTypeContext> sumTypeNode,
        Optional<ResolvedSchema> underlyingSchema,
        Optional<StvnConstraints> localConstraints
    ) {
      this(node, constraints, aliasName, implicitUnionTag, sumTypeNode, underlyingSchema, localConstraints, false, Optional.empty());
    }

    /**
     * Convenience constructor to build a ResolvedSchema with default empty sum type context and underlying schemas.
     *
     * @param node        schema type parse context
     * @param constraints effective constraints
     * @param aliasName   optional nominal alias name
     */
    public ResolvedSchema(SchemaTypeContext node, StvnConstraints constraints, Optional<String> aliasName) {
      this(node, constraints, aliasName, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false, Optional.empty());
    }

    /**
     * Convenience constructor to build a ResolvedSchema specifying implicit union tag and sum type node context.
     *
     * @param node             schema type parse context
     * @param constraints      effective constraints
     * @param aliasName        optional nominal alias name
     * @param implicitUnionTag optional implicit union branch tag index
     * @param sumTypeNode      optional AST context for sum type definitions
     */
    public ResolvedSchema(SchemaTypeContext node, StvnConstraints constraints, Optional<String> aliasName, Optional<Integer> implicitUnionTag, Optional<SumTypeContext> sumTypeNode) {
      this(node, constraints, aliasName, implicitUnionTag, sumTypeNode, Optional.empty(), Optional.empty(), false, Optional.empty());
    }

    /**
     * Factory for creating a poisoned sentinel schema for unresolved or structurally broken types.
     *
     * @param aliasName the alias name associated with the broken type
     * @param node      the schema type parse context
     * @return a poisoned sentinel schema instance
     */
    public static ResolvedSchema error(String aliasName, SchemaTypeContext node) {
      return new ResolvedSchema(
          node,
          StvnConstraints.empty(),
          Optional.of(aliasName),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.of(StvnConstraints.empty()),
          true,
          Optional.empty()
      );
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof ResolvedSchema that)) return false;
      return java.util.Objects.equals(constraints, that.constraints) &&
          java.util.Objects.equals(aliasName, that.aliasName) &&
          java.util.Objects.equals(enumSubset, that.enumSubset);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(constraints, aliasName, enumSubset);
    }

    @Override
    public String toString() {
      return "ResolvedSchema[" +
          "node=" + node +
          ", constraints=" + constraints +
          ", aliasName=" + aliasName.orElse(null) +
          ", implicitUnionTag=" + implicitUnionTag.orElse(null) +
          ", sumTypeNode=" + sumTypeNode.orElse(null) +
          ", underlyingSchema=" + underlyingSchema.orElse(null) +
          ", localConstraints=" + localConstraints.orElse(null) +
          ", enumSubset=" + enumSubset.orElse(null) +
          ']';
    }
  }

  /**
   * Exception thrown when a circular reference loop is detected in nominal type definitions
   * (e.g. {@code :A} aliases {@code :B} which aliases {@code :A}) without an intervening named nominal definition.
   */
  public static class CircularReferenceException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = -2096418347015401423L;

    /**
     * Constructs a new CircularReferenceException with the specified detail message.
     *
     * @param message the detail message describing the circular reference loop
     */
    public CircularReferenceException(String message) {
      super(message);
    }
  }

  /**
   * Extracts and consolidates metadata constraints from an ANTLR metadata map parse tree.
   * <p>
   * This parses properties like {@code #minIncl}, {@code #maxIncl}, {@code #regex}, {@code #preserveIndent},
   * {@code #equatable}, and {@code #comparable}.
   *
   * @param metadataMap the metadata map context parse tree node, or {@code null} if none is present
   * @return the resolved non-null {@link StvnConstraints}
   */
  public static StvnConstraints extractConstraints(@Nullable MetadataMapContext metadataMap) {
    // PATHWAY B: Defensive guard to prevent null pointer exceptions if the metadata map is not present
    if (metadataMap == null) return StvnConstraints.empty();

    BigDecimal minIncl = null, minExcl = null, maxIncl = null, maxExcl = null;
    String regex = null;
    boolean preserveIndent = false;
    Boolean equatable = null, comparable = null;
    var explicitOverrides = new java.util.ArrayList<String>();
    List<String> filterIncl = null;
    List<String> filterExcl = null;
    Integer size = null;
    boolean unsigned = false;
    boolean exact = false;
    Integer minSize = null;
    Integer maxSize = null;
    boolean invertible = false;
    String scale = null;
    boolean offset = false;
    boolean zoned = false;
    boolean audited = false;
    String dateMinIncl = null, dateMinExcl = null, dateMaxIncl = null, dateMaxExcl = null;

    for (StvnParser.MetadataEntryContext entry : metadataMap.metadataEntry()) {
      if (entry.metadataFilter() != null) {
        var filterCtx = entry.metadataFilter();
        var list = new java.util.ArrayList<String>();
        if (filterCtx.variantList() != null && filterCtx.variantList().valueKeyword() != null) {
          for (var vk : filterCtx.variantList().valueKeyword()) {
            list.add(vk.getText());
          }
        }
        if (filterCtx.KW_FILTER_INCL() != null) {
          filterIncl = list;
          explicitOverrides.add("filterIncl");
        } else if (filterCtx.KW_FILTER_EXCL() != null) {
          filterExcl = list;
          explicitOverrides.add("filterExcl");
        }
      } else if (entry.metadataRange() != null) {
        var numCtx = entry.metadataRange();
        BigDecimal val = null;
        String dateVal = null;
        if (numCtx.metadataValue() != null) {
          if (numCtx.metadataValue().integerLiteral() != null)
            val = new BigDecimal(StvnLiteralParser.parseBigInteger(numCtx.metadataValue().integerLiteral().getText()));
          else if (numCtx.metadataValue().floatLiteral() != null)
            val = new BigDecimal(numCtx.metadataValue().floatLiteral().getText());
          else if (numCtx.metadataValue().stringLiteral() != null)
            dateVal = extractRawStringValue(numCtx.metadataValue().stringLiteral().getText());
        }

        if (val != null) {
          if (numCtx.KW_MIN_INCL() != null) minIncl = val;
          else if (numCtx.KW_MIN_EXCL() != null) minExcl = val;
          else if (numCtx.KW_MAX_INCL() != null) maxIncl = val;
          else if (numCtx.KW_MAX_EXCL() != null) maxExcl = val;
        }
        if (dateVal != null) {
          if (numCtx.KW_MIN_INCL() != null) dateMinIncl = dateVal;
          else if (numCtx.KW_MIN_EXCL() != null) dateMinExcl = dateVal;
          else if (numCtx.KW_MAX_INCL() != null) dateMaxIncl = dateVal;
          else if (numCtx.KW_MAX_EXCL() != null) dateMaxExcl = dateVal;
        }
      } else if (entry.metadataString() != null) {
        var strCtx = entry.metadataString();
        if (strCtx.KW_REGEX() != null && strCtx.metadataValue() != null && strCtx.metadataValue().stringLiteral() != null) {
          regex = extractRawStringValue(strCtx.metadataValue().stringLiteral().getText());
        }
      } else if (entry.metadataBool() != null) {
        var boolCtx = entry.metadataBool();
        var mv = boolCtx.metadataValue();
        if (mv != null) {
          var boolLit = mv.booleanLiteral();
          if (boolLit != null) {
            var isTrue = boolLit.KW_TRUE() != null || boolLit.KW_TRUE_SHORT() != null;
            if (boolCtx.KW_EQUATABLE() != null) {
              equatable = isTrue;
              explicitOverrides.add("equatable");
            } else if (boolCtx.KW_COMPARABLE() != null) {
              comparable = isTrue;
              explicitOverrides.add("comparable");
            }
          }
        }
      } else if (entry.metadataSize() != null) {
        var sizeCtx = entry.metadataSize();
        Integer sz = null;
        if (sizeCtx.metadataValue() != null && sizeCtx.metadataValue().integerLiteral() != null) {
          try {
            sz = StvnLiteralParser.parseBigInteger(sizeCtx.metadataValue().integerLiteral().getText()).intValueExact();
          } catch (Exception ignored) {}
        }
        if (sizeCtx.KW_SIZE() != null) {
          size = sz;
          explicitOverrides.add("size");
        } else if (sizeCtx.KW_MIN_SIZE() != null) {
          minSize = sz;
          explicitOverrides.add("minSize");
        } else if (sizeCtx.KW_MAX_SIZE() != null) {
          maxSize = sz;
          explicitOverrides.add("maxSize");
        }
      } else if (entry.metadataFlag() != null) {
        var flagCtx = entry.metadataFlag();
        boolean flagVal = true;
        if (flagCtx.booleanLiteral() != null) {
          var bLit = flagCtx.booleanLiteral();
          flagVal = bLit.KW_TRUE() != null || bLit.KW_TRUE_SHORT() != null;
        }
        if (flagCtx.KW_PRESERVE_INDENT() != null) {
          preserveIndent = flagVal;
          explicitOverrides.add("preserveIndent");
        } else if (flagCtx.KW_UNSIGNED() != null) {
          unsigned = flagVal;
          explicitOverrides.add("unsigned");
        } else if (flagCtx.KW_EXACT() != null) {
          exact = flagVal;
          explicitOverrides.add("exact");
        } else if (flagCtx.KW_INVERTIBLE() != null) {
          invertible = flagVal;
          explicitOverrides.add("invertible");
        } else if (flagCtx.KW_OFFSET() != null) {
          offset = flagVal;
          explicitOverrides.add("offset");
        } else if (flagCtx.KW_ZONED() != null) {
          zoned = flagVal;
          explicitOverrides.add("zoned");
        } else if (flagCtx.KW_AUDITED() != null) {
          audited = flagVal;
          explicitOverrides.add("audited");
        } else if (flagCtx.KW_SCALE_S() != null) {
          scale = "s";
          explicitOverrides.add("scale");
        } else if (flagCtx.KW_SCALE_MS() != null) {
          scale = "ms";
          explicitOverrides.add("scale");
        } else if (flagCtx.KW_SCALE_US() != null) {
          scale = "us";
          explicitOverrides.add("scale");
        } else if (flagCtx.KW_SCALE_NS() != null) {
          scale = "ns";
          explicitOverrides.add("scale");
        }
      }
    }
    return new StvnConstraints(
        Optional.ofNullable(minIncl),
        Optional.ofNullable(minExcl),
        Optional.ofNullable(maxIncl),
        Optional.ofNullable(maxExcl),
        Optional.ofNullable(regex),
        preserveIndent,
        Optional.ofNullable(equatable),
        Optional.ofNullable(comparable),
        java.util.List.copyOf(explicitOverrides),
        Optional.ofNullable(filterIncl),
        Optional.ofNullable(filterExcl),
        Optional.ofNullable(size),
        unsigned,
        exact,
        Optional.ofNullable(minSize),
        Optional.ofNullable(maxSize),
        invertible,
        Optional.ofNullable(scale),
        offset,
        zoned,
        audited,
        Optional.ofNullable(dateMinIncl),
        Optional.ofNullable(dateMinExcl),
        Optional.ofNullable(dateMaxIncl),
        Optional.ofNullable(dateMaxExcl)
    );
  }

  private static String extractRawStringValue(String raw) {
    return org.stvnadore.core.ir.StvnLiteralParser.parseString(raw, true);
  }

  /**
   * Searches the definitions block (`:defs`) of a specific STVN document context
   * for a nominal type definition matching the target name.
   *
   * @param doc        the STVN document context to search, or {@code null}
   * @param targetName the nominal type name (e.g. {@code :Uuid})
   * @return an {@link Optional} containing the matched {@link DefSource}, or {@link Optional#empty()}
   */
  public static Optional<DefSource> findDefInDocument(@Nullable StvnDocumentContext doc, String targetName) {
    if (doc == null) return Optional.empty();
    var defs = getDocumentDefinitions(doc);
    return Optional.ofNullable(defs.get(targetName));
  }

  /**
   * Searches both the local document definition block and the standard library prelude
   * for a type definition matching the target name, enforcing zero-shadowing rules.
   *
   * @param doc        the STVN document context to search, or {@code null}
   * @param targetName the nominal type name
   * @return a list containing the resolved {@link DefSource}
   * @throws IllegalStateException if the Zero-Shadowing constraint is violated (e.g., duplicate definitions found)
   */
  public static List<DefSource> findAllDefinitions(@Nullable StvnDocumentContext doc, String targetName) {
    List<DefSource> results = new ArrayList<>();
    if (doc != null && doc.documentBody() != null && doc.documentBody().defsEntry() != null) {
      findDefInDocument(doc, targetName).ifPresent(results::add);
    }

    // Check shadowing
    if (results.size() > 1) {
      throw new IllegalStateException("Zero-Shadowing constraint violated: " + targetName);
    }

    // Fallback to Prelude
    if (results.isEmpty()) {
      var preludeDoc = StvnPrelude.getPreludeDocument();
      findDefInDocument(preludeDoc, targetName).ifPresent(s -> results.add(new DefSource(s.defNode(), "Prelude")));
    }

    return results;
  }

  /**
   * Helper that resolves a nominal type definition context.
   *
   * @param doc     the STVN document context
   * @param keyword the type name keyword
   * @return an {@link Optional} containing the definition context, or {@link Optional#empty()}
   */
  public static Optional<TypeDefinitionContext> findTypeDefinition(@Nullable StvnDocumentContext doc, String keyword) {
    return findTypeDefinition(doc, keyword, null);
  }

  /**
   * Locates the AST definition context node for a specified type keyword within the document or its packages.
   *
   * @param doc the enclosing STVN document context, or null
   * @param keyword the fully qualified or scoped type keyword to locate
   * @param contextNode the parse tree context initiating the lookup
   * @return an optional containing the matching {@link StvnParser.TypeDefinitionContext} if found, or empty
   */
  public static Optional<TypeDefinitionContext> findTypeDefinition(@Nullable StvnDocumentContext doc, String keyword, @Nullable ParserRuleContext contextNode) {
    String resolved = resolveTypeIdentifier(doc, keyword, contextNode);
    return findAllDefinitions(doc, resolved).stream().findFirst().map(DefSource::defNode);
  }

  /**
   * Searches the definitions block (`:defs`) of a specific STVN document context
   * for a constant definition matching the target name.
   *
   * @param doc        the STVN document context to search, or {@code null}
   * @param targetName the constant name (e.g. {@code #MAX_RETRY})
   * @return an {@link Optional} containing the matched {@link ConstantDefSource}, or {@link Optional#empty()}
   */
  public static Optional<ConstantDefSource> findConstantDefInDocument(@Nullable StvnDocumentContext doc, String targetName) {
    if (doc == null) return Optional.empty();
    var defs = getDocumentConstantDefinitions(doc);
    return Optional.ofNullable(defs.get(targetName));
  }

  /**
   * Searches both the local document definition block and the standard library prelude
   * for a constant definition matching the target name, enforcing zero-shadowing rules.
   *
   * @param doc        the STVN document context to search, or {@code null}
   * @param targetName the constant name
   * @return a list containing the resolved {@link ConstantDefSource}
   * @throws IllegalStateException if the Zero-Shadowing constraint is violated
   */
  public static List<ConstantDefSource> findAllConstantDefinitions(@Nullable StvnDocumentContext doc, String targetName) {
    List<ConstantDefSource> results = new ArrayList<>();
    if (doc != null && doc.documentBody() != null && doc.documentBody().defsEntry() != null) {
      findConstantDefInDocument(doc, targetName).ifPresent(results::add);
    }

    // Check shadowing
    if (results.size() > 1) {
      throw new IllegalStateException("Zero-Shadowing constraint violated: " + targetName);
    }

    // Fallback to Prelude
    if (results.isEmpty()) {
      var preludeDoc = StvnPrelude.getPreludeDocument();
      findConstantDefInDocument(preludeDoc, targetName).ifPresent(s -> results.add(new ConstantDefSource(s.defNode(), "Prelude")));
    }

    return results;
  }

  /**
   * Helper that resolves a typed constant definition context.
   *
   * @param doc     the STVN document context
   * @param keyword the constant name keyword (e.g. {@code #MAX_RETRY})
   * @return an {@link Optional} containing the definition context, or {@link Optional#empty()}
   */
  public static Optional<ConstantDefinitionContext> findConstantDefinition(@Nullable StvnDocumentContext doc, String keyword) {
    return findConstantDefinition(doc, keyword, null);
  }

  /**
   * Locates the AST definition context node for a specified constant keyword within the document or its packages.
   *
   * @param doc the enclosing STVN document context, or null
   * @param keyword the fully qualified or scoped constant keyword to locate
   * @param contextNode the parse tree context initiating the lookup
   * @return an optional containing the matching {@link StvnParser.ConstantDefinitionContext} if found, or empty
   */
  public static Optional<ConstantDefinitionContext> findConstantDefinition(@Nullable StvnDocumentContext doc, String keyword, @Nullable ParserRuleContext contextNode) {
    String resolved = resolveConstantIdentifier(doc, keyword, contextNode);
    return findAllConstantDefinitions(doc, resolved).stream().findFirst().map(ConstantDefSource::defNode);
  }

  /**
   * Resolves a schema type context into a validated {@link ResolvedSchema}, checking circular references
   * and propagating constraints.
   *
   * @param doc        the STVN document context
   * @param schemaNode the schema type context node to resolve
   * @param visited    a set tracking visited nominal keywords to detect circular reference chains
   * @return an {@link Optional} containing the resolved schema, or {@link Optional#empty()}
   * @throws CircularReferenceException if a circular nominal type reference loop is detected
   */
  public static Optional<ResolvedSchema> resolvePrimitiveSchema(@Nullable StvnDocumentContext doc, @Nullable SchemaTypeContext schemaNode, Set<String> visited) {
    if (doc != null) {
      getDocumentDefinitions(doc);
    }
    return resolvePrimitiveSchema(doc, schemaNode, visited, false);
  }

  private static Optional<ResolvedSchema> resolvePrimitiveSchema(@Nullable StvnDocumentContext doc, @Nullable SchemaTypeContext schemaNode, Set<String> visited, boolean passedConstructor) {
    // PATHWAY B: Defensive guard to handle missing or incomplete schema node declarations in incomplete parse trees
    if (schemaNode == null) return Optional.empty();

    if (schemaNode.typeKeyword() != null) {
      var rawKw = schemaNode.typeKeyword().getText();
      var kw = resolveTypeIdentifier(doc, rawKw, schemaNode);
      if (doc != null && isTypePoisoned(doc, kw)) {
        return Optional.of(ResolvedSchema.error(kw, schemaNode));
      }
      if (visited.contains(kw)) {
        if (passedConstructor) {
          return Optional.of(validateResolvedSchema(applyDefaults(new ResolvedSchema(schemaNode, StvnConstraints.empty(), Optional.of(kw), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(StvnConstraints.empty()), false))));
        } else {
          markTypePoisoned(doc, kw);
          throw new CircularReferenceException("Circular type definition detected: " + String.join(" -> ", visited) + " -> " + kw);
        }
      }
      var nextVisited = new LinkedHashSet<>(visited);
      nextVisited.add(kw);

      var typeDefOpt = findTypeDefinition(doc, kw, schemaNode);
      if (typeDefOpt.isPresent()) {
        var typeDef = typeDefOpt.get();
        var meta = extractConstraints(typeDef.metadataMap());
        var innerRes = resolvePrimitiveSchema(doc, typeDef.schemaType(), nextVisited, false);

        Optional<ResolvedType.EnumSubset> derivedSubset = Optional.empty();
        if (meta.filterIncl().isPresent() || meta.filterExcl().isPresent()) {
          var target = innerRes.orElseThrow(() -> new MalformedSchemaException("Cannot resolve target for enum filter: " + kw));
          var baseType = getPrimitiveBaseType(target.node());
          if (!":Enum".equals(baseType)) {
            throw new MalformedSchemaException("Constraint violation (" + kw + "): filter facets are not allowed on " + baseType);
          }
          if (typeDef.schemaType().schemaConstructor() != null && typeDef.schemaType().schemaConstructor().sumType() != null && typeDef.schemaType().schemaConstructor().sumType().enumDef() != null) {
            throw new MalformedSchemaException("Constraint violation (" + kw + "): filter facets cannot be applied to inline enum constructors; filter facets are only allowed on nominal aliases of :Enum or existing enum subsets");
          }
          if (meta.filterIncl().isPresent() && meta.filterExcl().isPresent()) {
            throw new MalformedSchemaException("Constraint violation (" + kw + "): #filterIncl and #filterExcl are mutually exclusive");
          }

          List<String> parentAllowed;
          String parentName = target.aliasName().orElse(":Enum");
          String rootEnumName;
          List<String> rootVariants;

          if (target.enumSubset().isPresent()) {
            var parentSubset = target.enumSubset().get();
            parentAllowed = parentSubset.allowedVariants();
            rootEnumName = parentSubset.rootEnum();
            rootVariants = parentSubset.rootVariants();
          } else {
            var rootEnumDef = target.node().schemaConstructor().sumType().enumDef();
            if (rootEnumDef == null) {
              throw new MalformedSchemaException("Cannot resolve enum definition for " + kw);
            }
            rootVariants = rootEnumDef.valueKeyword().stream().map(ParseTree::getText).toList();
            parentAllowed = rootVariants;
            rootEnumName = parentName;
          }

          boolean isIncl = meta.filterIncl().isPresent();
          List<String> facetList = isIncl ? meta.filterIncl().get() : meta.filterExcl().get();

          if (facetList.isEmpty()) {
            throw new MalformedSchemaException("Constraint violation (" + kw + "): enum filter variant list cannot be empty");
          }
          if (new HashSet<>(facetList).size() != facetList.size()) {
            throw new MalformedSchemaException("Constraint violation (" + kw + "): duplicate variant in filter facet");
          }
          for (String v : facetList) {
            if (!parentAllowed.contains(v)) {
              throw new MalformedSchemaException("Constraint violation (" + kw + "): Monotonic narrowing violation: variant " + v + " does not exist in immediate parent type " + parentName);
            }
          }
          int prevRootIdx = -1;
          for (String v : facetList) {
            int idx = rootVariants.indexOf(v);
            if (idx <= prevRootIdx) {
              throw new MalformedSchemaException("Constraint violation (" + kw + "): Root ordering violation: variant " + v + " does not match relative declaration order of root :Enum " + rootEnumName);
            }
            prevRootIdx = idx;
          }

          List<String> computedAllowed;
          if (isIncl) {
            computedAllowed = List.copyOf(facetList);
          } else {
            computedAllowed = parentAllowed.stream().filter(v -> !facetList.contains(v)).toList();
          }

          if (computedAllowed.isEmpty()) {
            throw new MalformedSchemaException("Constraint violation (" + kw + "): Complete exclusion violation: enum subset results in empty variant list");
          }

          derivedSubset = Optional.of(new ResolvedType.EnumSubset(kw, parentName, rootEnumName, computedAllowed, rootVariants, isIncl));
        } else if (innerRes.isPresent() && innerRes.get().enumSubset().isPresent()) {
          derivedSubset = innerRes.get().enumSubset();
        }

        final var finalSubset = derivedSubset;
        if (innerRes.isPresent()) {
          var resolvedSchema = innerRes.get();
          var merged = meta.merge(resolvedSchema.constraints());
          if (merged.invertible()) {
            var base = getPrimitiveBaseType(resolvedSchema.node());
            if (base != null && isMapType(base)) {
              var inner = getInnerSchemas(resolvedSchema.node());
              if (inner.size() >= 2) {
                var valOpt = resolvePrimitiveSchema(doc, inner.get(1), visited, true);
                if (valOpt.isPresent() && valOpt.get().constraints().equatable().equals(Optional.of(false))) {
                  throw new MalformedSchemaException("Inverted map values require types to be #equatable #TRUE");
                }
              }
            }
          }
        }
        return innerRes
            .map(resolvedSchema ->
                applyDefaults(new ResolvedSchema(resolvedSchema.node(), meta.merge(resolvedSchema.constraints()), Optional.of(kw), Optional.empty(), Optional.empty(), Optional.of(resolvedSchema), Optional.of(meta), resolvedSchema.isPoisonedSentinel(), finalSubset)))
            .or(() ->
                Optional.of(applyDefaults(new ResolvedSchema(schemaNode, meta, Optional.of(kw), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(meta), false, finalSubset))))
            .map(StvnTypeResolver::validateResolvedSchema);
      } else {
        markTypePoisoned(doc, kw);
        String legacyMsg = getLegacyTypeDeprecationMessage(rawKw);
        if (legacyMsg == null) {
          legacyMsg = getLegacyTypeDeprecationMessage(kw);
        }
        String errMsg = legacyMsg != null ? legacyMsg : ("Undefined type: " + kw);
        throw new MalformedSchemaException(errMsg,
            schemaNode.getStart().getStartIndex(),
            schemaNode.getStop().getStopIndex() + 1);
      }
    }



    var baseText = getPrimitiveBaseType(schemaNode);
    var children = new java.util.ArrayList<ResolvedSchema>();
    if (baseText != null) {
      for (var child : getInnerSchemas(schemaNode)) {
        resolvePrimitiveSchema(doc, child, visited, true).ifPresent(children::add);
      }
    }



    if (baseText != null && isSetType(baseText)) {
      if (!children.isEmpty()) {
        var c = children.getFirst().constraints();
        if (c != null && c.equatable().equals(Optional.of(false))) {
          throw new MalformedSchemaException("Set elements require types to be #equatable #TRUE");
        }
      }
    }

    if (baseText != null && isMapType(baseText)) {
      if (children.size() >= 2) {
        var keySchema = children.get(0);
        var keyConstraints = keySchema.constraints();
        if (keyConstraints != null && keyConstraints.equatable().equals(Optional.of(false))) {
          throw new MalformedSchemaException("Map keys require types to be #equatable #TRUE");
        }
        if (baseText.equals(":MapInv") || baseText.equals(":MapInvNonEmpty")) {
          var valSchema = children.get(1);
          var valConstraints = valSchema.constraints();
          if (valConstraints != null && valConstraints.equatable().equals(Optional.of(false))) {
            throw new MalformedSchemaException("Inverted map values require types to be #equatable #TRUE");
          }
        }
      }
    }

    var baseRs = applyDefaults(new ResolvedSchema(schemaNode, StvnConstraints.empty(), Optional.empty()));
    return Optional.of(validateResolvedSchema(deriveAndApplyTraits(baseRs, children)));
  }

  private static ResolvedSchema validateResolvedSchema(ResolvedSchema rs) {
    if (rs.isPoisonedSentinel()) {
      return rs;
    }
    var baseType = getPrimitiveBaseType(rs.node());
    if (rs.node() != null && rs.node().schemaConstructor() != null && rs.node().schemaConstructor().atomicType() != null) {
      var atomicTypeStr = rs.node().schemaConstructor().atomicType().getText();
      String suffix = null;
      if (atomicTypeStr.startsWith(":Int") && !atomicTypeStr.equals(":Int")) {
        suffix = atomicTypeStr.substring(4);
      } else if (atomicTypeStr.startsWith(":Uint") && !atomicTypeStr.equals(":Uint")) {
        suffix = atomicTypeStr.substring(5);
      } else if (atomicTypeStr.startsWith(":Float") && !atomicTypeStr.equals(":Float") && !atomicTypeStr.equals(":FloatExact")) {
        suffix = atomicTypeStr.substring(6);
      } else if (atomicTypeStr.startsWith(":StringFixed") && !atomicTypeStr.equals(":StringFixed")) {
        suffix = atomicTypeStr.substring(12);
      } else if (atomicTypeStr.startsWith(":StringNonEmpty") && !atomicTypeStr.equals(":StringNonEmpty")) {
        suffix = atomicTypeStr.substring(15);
      } else if (atomicTypeStr.startsWith(":String") && !atomicTypeStr.startsWith(":StringFixed") && !atomicTypeStr.startsWith(":StringNonEmpty") && !atomicTypeStr.equals(":String")) {
        suffix = atomicTypeStr.substring(7);
      }

      if (suffix != null && !suffix.isEmpty()) {
        if (suffix.length() > 1 && suffix.startsWith("0")) {
          throw new MalformedSchemaException("Constraint violation: Leading zeros are forbidden in type suffix dimensions: " + atomicTypeStr);
        }
        if (suffix.contains("-") || suffix.contains("+")) {
          throw new MalformedSchemaException("Constraint violation: Type suffix dimensions cannot contain negative symbols or sign specifiers: " + baseType);
        }
        try {
          var bigSuffix = new java.math.BigInteger(suffix);
          if (bigSuffix.compareTo(java.math.BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new MalformedSchemaException("Constraint violation: Type suffix dimension overflows signed 32-bit integer limit: " + baseType);
          }
          var parsedN = bigSuffix.intValue();
          if (parsedN < 1) {
            throw new MalformedSchemaException("Constraint violation: Type suffix dimensions must be strictly positive (N >= 1): " + baseType);
          }
        } catch (NumberFormatException e) {
          throw new MalformedSchemaException("Constraint violation: Malformed numeric type suffix format: " + baseType, e);
        }
      }
    }

    var constraints = rs.constraints();
    if (constraints.regex().isPresent()) {
      var regexStr = constraints.regex().get();
      if (baseType == null || !isStringType(baseType)) {
        var cleanConstraints = constraints.withRegex(Optional.empty());
        return new ResolvedSchema(rs.node(), cleanConstraints, rs.aliasName(), rs.implicitUnionTag(), rs.sumTypeNode(), rs.underlyingSchema(), rs.localConstraints(), rs.isPoisonedSentinel(), rs.enumSubset());
      }
      try {
        java.util.regex.Pattern.compile(regexStr);
      } catch (java.util.regex.PatternSyntaxException e) {
        var cleanConstraints = constraints.withRegex(Optional.empty());
        return new ResolvedSchema(rs.node(), cleanConstraints, rs.aliasName(), rs.implicitUnionTag(), rs.sumTypeNode(), rs.underlyingSchema(), rs.localConstraints(), rs.isPoisonedSentinel(), rs.enumSubset());
      }
    }
    if (constraints.preserveIndent() || constraints.explicitOverrides().contains("preserveIndent")) {
      var ultimateBase = getUltimateBaseType(rs);
      if (ultimateBase == null || !isStringType(ultimateBase)) {
        var cleanOverrides = new ArrayList<>(constraints.explicitOverrides());
        cleanOverrides.remove("preserveIndent");
        var cleanConstraints = constraints.withPreserveIndent(false, cleanOverrides);
        return new ResolvedSchema(rs.node(), cleanConstraints, rs.aliasName(), rs.implicitUnionTag(), rs.sumTypeNode(), rs.underlyingSchema(), rs.localConstraints(), rs.isPoisonedSentinel(), rs.enumSubset());
      }
    }
    return rs;
  }

  private static @Nullable String getUltimateBaseType(ResolvedSchema rs) {
    var current = rs;
    while (current.underlyingSchema().isPresent()) {
      current = current.underlyingSchema().get();
    }
    return getPrimitiveBaseType(current.node());
  }

  /**
   * Applies default trait settings to a schema based on its base primitive type.
   * <p>
   * For example, standard floating point types (like {@code :Float32} and {@code :Float64}) are default non-equatable.
   * Other types default to both equatable and comparable.
   *
   * @param rs the resolved schema context to apply defaults to
   * @return a new non-null {@link ResolvedSchema} with default constraints set
   */
  public static ResolvedSchema applyDefaults(ResolvedSchema rs) {
    if (rs == null) {
      throw new MalformedSchemaException("Resolved schema is null in applyDefaults");
    }
    var baseText = getPrimitiveBaseType(rs.node());
    if (baseText == null) return rs;

    var equatable = rs.constraints().equatable();
    var comparable = rs.constraints().comparable();
    var changed = false;

    if (equatable.isEmpty()) {
      equatable = Optional.of(!(isFloatType(baseText) && !baseText.equals(":FloatExact")));
      changed = true;
    }
    if (comparable.isEmpty()) {
      comparable = Optional.of(true);
      changed = true;
    }

    if (changed) {
      var updatedC = rs.constraints().withTraits(equatable, comparable, rs.constraints().explicitOverrides());
      return new ResolvedSchema(rs.node(), updatedC, rs.aliasName(), rs.implicitUnionTag(), rs.sumTypeNode(), rs.underlyingSchema(), rs.localConstraints(), rs.isPoisonedSentinel(), rs.enumSubset());
    }
    return rs;
  }

  /**
   * Derives capability traits (such as {@code #equatable} and {@code #comparable}) for composite or
   * algebraic schemas by analyzing their child component schemas.
   * <p>
   * Trait derivation follows the capability bubbling rules, where any non-compliant child type
   * nullifies the parent trait unless an explicit user override is present.
   *
   * @param parent   the parent schema
   * @param children the list of child schemas
   * @return a new non-null {@link ResolvedSchema} with derived traits applied
   */
  public static ResolvedSchema deriveAndApplyTraits(ResolvedSchema parent, List<ResolvedSchema> children) {
    if (parent == null) {
      throw new MalformedSchemaException("Parent schema is null in deriveAndApplyTraits");
    }
    var baseText = getPrimitiveBaseType(parent.node());
    if (baseText == null) return parent;

    var constraints = parent.constraints();
    var overrides = constraints.explicitOverrides();

    var equatable = constraints.equatable();
    var comparable = constraints.comparable();

    var hasEquatableOverride = overrides.contains("equatable");
    var hasComparableOverride = overrides.contains("comparable");

    // 1. Equatable Derivation
    if (!hasEquatableOverride) {
      if (isFloatType(baseText) && !baseText.equals(":FloatExact")) {
        equatable = Optional.of(false);
      } else if (isSeqType(baseText) || isSetType(baseText) || baseText.equals(":Option")) {
        if (!children.isEmpty()) {
          equatable = children.getFirst().constraints().equatable();
        } else {
          equatable = Optional.of(true);
        }
      } else if (baseText.equals(":Tuple") || baseText.equals(":Union") ||
          baseText.equals(":Either") || isMapType(baseText)) {
        if (!children.isEmpty()) {
          equatable = Optional.of(children.stream().allMatch(c -> c.constraints().equatable().orElse(false)));
        } else {
          equatable = Optional.of(true);
        }
      } else {
        // Atomic scalar types (Int, Uint, String, Boolean, Enum, FloatExact)
        equatable = Optional.of(true);
      }
    }

    // 2. Comparable Derivation
    if (!hasComparableOverride) {
      if (isSetType(baseText) || isMapType(baseText)) {
        // Unordered structures
        comparable = Optional.of(false);
      } else if (isSeqType(baseText) || baseText.equals(":Option") ||
          baseText.equals(":Tuple") || baseText.equals(":Union") ||
          baseText.equals(":Either")) {
        // Ordered structural compounds
        if (!children.isEmpty()) {
          comparable = Optional.of(children.stream().allMatch(c -> c.constraints().comparable().orElse(false)));
        } else {
          comparable = Optional.of(true);
        }
      } else {
        // Scalar primitives and enums (including Float32, Float64, Float)
        comparable = Optional.of(true);
      }
    }

    if (Objects.equals(equatable, constraints.equatable()) && Objects.equals(comparable, constraints.comparable())) {
      return parent;
    }

    var updatedC = constraints.withTraits(equatable, comparable, overrides);

    return new ResolvedSchema(parent.node(), updatedC, parent.aliasName(), parent.implicitUnionTag(), parent.sumTypeNode(), parent.underlyingSchema(), parent.localConstraints(), parent.isPoisonedSentinel(), parent.enumSubset());
  }

  /**
   * Resolves the primary base type name (e.g. {@code :Int32}, {@code :Seq}) for a schema type context.
   *
   * @param schemaType the schema type context node to analyze
   * @return the base type string keyword, or {@code null} if it cannot be resolved
   */
  public static @Nullable String getPrimitiveBaseType(@Nullable SchemaTypeContext schemaType) {
    // PATHWAY B: Defensive guard to handle missing or incomplete schema type declarations in incomplete parse trees
    if (schemaType == null) return null;
    if (schemaType.schemaConstructor() != null) {
      var ctor = schemaType.schemaConstructor();
      if (ctor.atomicType() != null) return ctor.atomicType().getText();
      if (ctor.collectionType() != null) {
        if (ctor.collectionType().COLL_SEQ() != null) return ":Seq";
        if (ctor.collectionType().COLL_SET() != null) return ":Set";
        if (ctor.collectionType().COLL_MAP() != null) return ":Map";
      }
      if (ctor.productType() != null) {
        if (ctor.productType() instanceof StvnParser.TupleTypeContext) return ":Tuple";
      }
      if (ctor.sumType() != null) {
        if (ctor.sumType().KW_OPTION() != null) return ":Option";
        if (ctor.sumType().KW_EITHER() != null) return ":Either";
        if (ctor.sumType().KW_UNION() != null) return ":Union";
        if (ctor.sumType().KW_ENUM() != null || ctor.sumType().enumDef() != null) return ":Enum";
      }
    }

    if (schemaType.typeKeyword() != null) {
      return schemaType.typeKeyword().getText();
    }
    return null;
  }

  private record PathStep(int index, LiteralType type, @Nullable String literalText) {
  }

  /**
   * Traces back a parsed value node in the AST up to a typed boundary,
   * resolving the corresponding schema configuration for that specific value context.
   * <p>
   * This is crucial during parsing and unification to validate deep structure values
   * against parent schema constraints.
   *
   * @param doc  the active document context
   * @param node the value context node to resolve
   * @return an {@link Optional} containing the resolved schema, or {@link Optional#empty()}
   */
  public static Optional<ResolvedSchema> resolveSchemaNode(@Nullable StvnDocumentContext doc, @Nullable ValueContext node) {
    if (doc != null) {
      getDocumentDefinitions(doc);
    }
    // PATHWAY B: Defensive guard to prevent null pointer exceptions when resolving schema nodes of incomplete AST nodes
    if (node == null) return Optional.empty();

    ParserRuleContext curr = node;
    ParserRuleContext boundary = null;
    Deque<PathStep> path = new ArrayDeque<>();

    LiteralType valType = LiteralType.UNKNOWN;
    String literalText = null;
    if (node.integerLiteral() != null) valType = LiteralType.INTEGER_LITERAL;
    else if (node.floatLiteral() != null) valType = LiteralType.FLOAT_LITERAL;
    else if (node.stringLiteral() != null) valType = LiteralType.STRING_LITERAL;
    else if (node.booleanLiteral() != null) valType = LiteralType.BOOLEAN_LITERAL;
    else if (node.explicitOptionValue() != null) valType = LiteralType.EXPLICIT_OPTION_VALUE;
    else if (node.explicitEitherValue() != null) valType = LiteralType.EXPLICIT_EITHER_VALUE;
    else if (node.explicitUnionValue() != null) valType = LiteralType.EXPLICIT_UNION_VALUE;
    else if (node.collectionValue() != null) {
      if (node.collectionValue().listLiteral() != null) valType = LiteralType.LIST_LITERAL;
      else if (node.collectionValue().mapLiteral() != null) valType = LiteralType.MAP_LITERAL;
      else if (node.collectionValue().tupleLiteral() != null) valType = LiteralType.TUPLE_LITERAL;
    } else if (node.valueKeyword() != null) {
      valType = LiteralType.KEYWORD_LITERAL;
      literalText = node.start.getText();
    }
    path.push(new PathStep(-2, valType, literalText));

    while (curr != null) {
      var parent = curr.getParent();
      if (parent == null) break;

      switch (parent) {
        case StvnParser.BodyEntryContext ctx -> boundary = ctx;

        case StvnParser.ConstantDefinitionContext ctx -> boundary = ctx;

        case StvnParser.TupleLiteralContext tupleParent -> {
          var i = 0;
          for (var c : tupleParent.value()) {
            if (c == curr || isAncestor(c, curr)) break;
            i++;
          }
          path.push(new PathStep(i, LiteralType.TUPLE_LITERAL, null));
        }

        case StvnParser.ListLiteralContext ignored -> path.push(new PathStep(-1, LiteralType.LIST_LITERAL, null));

        case StvnParser.MapEntryContext mapEntry -> {
          var i = 0;
          for (var c : mapEntry.value()) {
            if (c == curr || isAncestor(c, curr)) break;
            i++;
          }
          path.push(new PathStep(i % 2, LiteralType.MAP_LITERAL, null));
        }

        case StvnParser.MapLiteralContext ignored -> path.push(new PathStep(-1, LiteralType.MAP_LITERAL, null));

        case StvnParser.ExplicitEitherValueContext eitherParent -> {
          var isRight = eitherParent.KW_RIGHT() != null || eitherParent.KW_RIGHT_SHORT() != null;
          path.push(new PathStep(isRight
              ? 1
              : 0, LiteralType.EXPLICIT_EITHER_VALUE, null));
        }

        case StvnParser.ExplicitOptionValueContext ignored ->
            path.push(new PathStep(0, LiteralType.EXPLICIT_OPTION_VALUE, null));

        case StvnParser.ExplicitUnionValueContext uniParent -> {
          String tagText = uniParent.UNION_TAG_PREFIX().getText();
          int tagIndex = 0;
          for (int i = 1; i < tagText.length(); i++) {
            tagIndex = tagIndex * 10 + (tagText.charAt(i) - '0');
          }
          path.push(new PathStep(tagIndex - 1, LiteralType.EXPLICIT_UNION_VALUE, null));
        }

        default -> {
        }
      }

      if (boundary != null) break;
      curr = parent;
    }

    if (boundary == null) return Optional.empty();

    Optional<ResolvedSchema> rsOpt = Optional.empty();
    if (boundary instanceof StvnParser.BodyEntryContext) {
      if (doc.documentBody() != null && doc.documentBody().typeEntry() != null) {
        rsOpt = resolvePrimitiveSchema(doc, doc.documentBody().typeEntry().schemaType(), Set.of());
      }
    } else if (boundary instanceof StvnParser.TypeDefinitionContext typeDef) {
      rsOpt = resolvePrimitiveSchema(doc, typeDef.schemaType(), Set.of());
    } else if (boundary instanceof StvnParser.ConstantDefinitionContext constDef) {
      rsOpt = resolvePrimitiveSchema(doc, constDef.schemaType(), Set.of());
    }

    while (!path.isEmpty() && rsOpt.isPresent()) {
      PathStep step = path.peek();
      ResolvedSchema rs = rsOpt.get();
      var baseType = getPrimitiveBaseType(rs.node());
      if (baseType != null && baseType.equals(":Option") && step.type() != LiteralType.EXPLICIT_OPTION_VALUE) {
        var inner = getInnerSchemas(rs.node());
        if (!inner.isEmpty()) {
          var baseInner = resolvePrimitiveSchema(doc, inner.get(0), Set.of()).orElse(null);
          if (baseInner != null) {
            rsOpt = Optional.of(new ResolvedSchema(
                baseInner.node(),
                baseInner.constraints(),
                baseInner.aliasName(),
                Optional.of(0),
                Optional.ofNullable(rs.node().schemaConstructor() != null ? rs.node().schemaConstructor().sumType() : null),
                baseInner.underlyingSchema(),
                baseInner.localConstraints()
            ));
          } else {
            rsOpt = Optional.empty();
          }
          continue;
        }
      }
      step = path.pop();

      if (step.index() == -1) {
        // collection inner type
        if (rs.node().schemaConstructor() != null && rs.node().schemaConstructor().collectionType() != null) {
          var col = rs.node().schemaConstructor().collectionType();
          var isMap = col.COLL_MAP() != null;
          if (isMap) {
            rsOpt = Optional.of(rs);
          } else {
            rsOpt = resolvePrimitiveSchema(doc, col.schemaType(0), Set.of());
          }
        } else {
          rsOpt = Optional.empty();
        }
      } else {
        var innerSchemas = getInnerSchemas(rs.node());
        var ctor = rs.node().schemaConstructor();
        if (ctor != null && ctor.sumType() != null && step.type() != LiteralType.EXPLICIT_OPTION_VALUE && step.type() != LiteralType.EXPLICIT_EITHER_VALUE && step.type() != LiteralType.EXPLICIT_UNION_VALUE && !innerSchemas.isEmpty()) {
          // Implicit Sum Type Resolution
          path.push(step);
          var sumContent = ctor.sumType();

          boolean isEither = sumContent.KW_EITHER() != null;
          boolean isUnion = sumContent.KW_UNION() != null;
          if (isEither && innerSchemas.size() >= 2) {
            if (isSameSchemaNode(doc, innerSchemas.get(0), innerSchemas.get(1))) {
              var leftOpt = resolvePrimitiveSchema(doc, innerSchemas.getFirst(), Set.of());
              var leftBase = leftOpt.map(resolvedSchema -> getPrimitiveBaseType(resolvedSchema.node())).orElse(null);
              throw new MalformedPayloadException("Ambiguous implicit either: Both sides are identical (" + (leftBase != null
                  ? leftBase
                  : "") + "), explicit #Left or #Right tag is required");
            }

            boolean leftMatches = canMatch(doc, innerSchemas.get(0), step.type(), step.literalText());
            boolean rightMatches = canMatch(doc, innerSchemas.get(1), step.type(), step.literalText());

            if (leftMatches && rightMatches) {
              throw new MalformedPayloadException(
                  "Ambiguous implicit resolution: Value matches both Left and Right branches of :Either",
                  node.start.getStartIndex(),
                  node.stop.getStopIndex() + 1
              );
            }
            if (leftMatches) {
              throw new MalformedPayloadException(
                  "Rule E Violation: Untagged value matching Left branch of :Either is non-inferable; explicit #Left tag is required",
                  node.start.getStartIndex(),
                  node.stop.getStopIndex() + 1
              );
            }
            if (rightMatches) {
              Optional<ResolvedSchema> rcOpt = resolvePrimitiveSchema(doc, innerSchemas.get(1), Set.of());
              if (rcOpt.isPresent()) {
                ResolvedSchema matched = rcOpt.get();
                rsOpt = Optional.of(new ResolvedSchema(
                    matched.node(),
                    matched.constraints(),
                    matched.aliasName(),
                    Optional.of(1),
                    Optional.ofNullable(ctor.sumType()),
                    matched.underlyingSchema(),
                    matched.localConstraints()
                ));
              } else {
                rsOpt = Optional.empty();
              }
            } else {
              rsOpt = Optional.empty();
            }
          } else {
            ResolvedSchema matched = null;
            int matchedIndex = -1;
            int matchCount = 0;

            for (int i = 0; i < innerSchemas.size(); i++) {
              StvnParser.SchemaTypeContext cand = innerSchemas.get(i);
              if (canMatch(doc, cand, step.type(), step.literalText())) {
                matchCount++;
                Optional<ResolvedSchema> rcOpt = resolvePrimitiveSchema(doc, cand, Set.of());
                if (rcOpt.isPresent()) {
                  matched = rcOpt.get();
                  matchedIndex = i;
                }
              }
            }

            if (matchCount > 1) {
              if (isUnion) {
                throw new StvnCollectionCollisionException(
                    "Ambiguous implicit resolution: Value matches multiple branches",
                    node.start.getStartIndex(),
                    node.stop.getStopIndex() + 1
                );
              } else {
                throw new MalformedPayloadException(
                    "Ambiguous implicit resolution: Value matches multiple branches",
                    node.start.getStartIndex(),
                    node.stop.getStopIndex() + 1
                );
              }
            }

            if (matched != null) {
              rsOpt = Optional.of(new ResolvedSchema(
                  matched.node(),
                  matched.constraints(),
                  matched.aliasName(),
                  Optional.of(matchedIndex),
                  Optional.ofNullable(ctor.sumType()),
                  matched.underlyingSchema(),
                  matched.localConstraints()
              ));
            } else {
              rsOpt = Optional.empty();
            }
          }
        } else //noinspection StatementWithEmptyBody
          if (step.index() == -2) {
            // DO NOTHING, we reached the leaf step
          } else if (step.index() >= 0 && step.index() < innerSchemas.size()) {
            rsOpt = resolvePrimitiveSchema(doc, innerSchemas.get(step.index()), Set.of());
          } else //noinspection StatementWithEmptyBody
            if (path.isEmpty()) {
              // DO NOTHING
            } else {
              rsOpt = Optional.empty();
            }
      }
    }
    return rsOpt;
  }

  /**
   * Evaluates if two schema type contexts are structurally equivalent.
   * <p>
   * This handles nominal alias resolution and structural comparisons for complex collection and sum types.
   *
   * @param doc the active document context
   * @param n1  the first schema node
   * @param n2  the second schema node
   * @return {@code true} if the schemas are structurally identical, otherwise {@code false}
   */
  public static boolean isSameSchemaNode(@Nullable StvnDocumentContext doc, @Nullable SchemaTypeContext n1, @Nullable SchemaTypeContext n2) {
    return isSameSchemaNodeRecursive(doc, n1, n2, new HashSet<>(), new HashSet<>());
  }

  private static boolean isSameSchemaNodeRecursive(
      @Nullable StvnDocumentContext doc,
      @Nullable SchemaTypeContext n1,
      @Nullable SchemaTypeContext n2,
      Set<String> visited1,
      Set<String> visited2
  ) {
    if (n1 == n2) return true;
    if (n1 == null || n2 == null) return false;

    // Opaque Nominal Branding: Nominal types must match by exact nominal identifier
    if (n1.typeKeyword() != null || n2.typeKeyword() != null) {
      if (n1.typeKeyword() != null && n2.typeKeyword() != null) {
        return n1.typeKeyword().getText().equals(n2.typeKeyword().getText());
      }
      return false;
    }

    var base1 = getPrimitiveBaseType(n1);
    var base2 = getPrimitiveBaseType(n2);
    if (!Objects.equals(base1, base2)) return false;
    if (base1 == null) return true;

    // For Enums, compare variant values
    if (base1.equals(":Enum")) {
      var ctor1 = n1.schemaConstructor();
      var ctor2 = n2.schemaConstructor();
      if (ctor1 != null && ctor2 != null && ctor1.sumType() != null && ctor2.sumType() != null) {
        var enumDef1 = ctor1.sumType().enumDef();
        var enumDef2 = ctor2.sumType().enumDef();
        if (enumDef1 != null && enumDef2 != null) {
          var list1 = enumDef1.valueKeyword();
          var list2 = enumDef2.valueKeyword();
          if (list1.size() != list2.size()) return false;
          for (var i = 0; i < list1.size(); i++) {
            if (!list1.get(i).getText().equals(list2.get(i).getText())) {
              return false;
            }
          }
          return true;
        }
      }
    }

    var inner1 = getInnerSchemas(n1);
    var inner2 = getInnerSchemas(n2);
    if (inner1.size() != inner2.size()) return false;

    for (var i = 0; i < inner1.size(); i++) {
      if (!isSameSchemaNodeRecursive(doc, inner1.get(i), inner2.get(i), visited1, visited2)) {
        return false;
      }
    }

    return true;
  }

  /**
   * Evaluates structural type compatibility for type-directed parser unification.
   * <p>
   * Implements the STVN happy-path implied tagging checks:
   * <ul>
   *   <li><b>Rule A (Implied Option {@code #Some}):</b> If {@code schemaNode} resolves to {@code :Option(T)},
   *       and {@code valType} is compatible with {@code T}, returns {@code true}.</li>
   *   <li><b>Rule B (Implied Either {@code #Right}):</b> If {@code schemaNode} resolves to {@code :Either(L R)},
   *       and {@code valType} is compatible with {@code R}, returns {@code true}.</li>
   *   <li><b>Rule C (Ambiguity Resolution):</b> Explicit tagging is mandatory if an untagged literal raw value
   *       conflicts with sum tags (e.g. {@code #None}). This method returns {@code true} for matches but is subject
   *       to ambiguity exceptions at validation boundaries.</li>
   * </ul>
   *
   * @param doc         the active document context
   * @param schemaNode  the schema type context node to unify against
   * @param valType     the literal type category of the active payload
   * @param literalText the raw text representation of the payload value
   * @return {@code true} if the value type unifies successfully with the schema shape, otherwise {@code false}
   */
  public static boolean canMatch(@Nullable StvnDocumentContext doc, @Nullable SchemaTypeContext schemaNode, LiteralType valType, @Nullable String literalText) {
    if (schemaNode == null) return false;
    var resolvedOpt = resolvePrimitiveSchema(doc, schemaNode, new HashSet<>());
    if (resolvedOpt.isEmpty()) return false;
    var resolved = resolvedOpt.get();
    var baseType = getPrimitiveBaseType(resolved.node());
    if (baseType == null) return false;

    if (baseType.equals(":Option") || baseType.equals(":Either") || baseType.equals(":Union")) {
      if (valType == LiteralType.EXPLICIT_OPTION_VALUE && baseType.equals(":Option")) return true;
      if (valType == LiteralType.EXPLICIT_EITHER_VALUE && baseType.equals(":Either")) return true;
      if (valType == LiteralType.EXPLICIT_UNION_VALUE && baseType.equals(":Union")) return true;

      if (baseType.equals(":Either")) {
        var innerSchemas = getInnerSchemas(resolved.node());
        if (innerSchemas.size() >= 2) {
          boolean leftMatches = canMatch(doc, innerSchemas.get(0), valType, literalText);
          boolean rightMatches = canMatch(doc, innerSchemas.get(1), valType, literalText);
          return rightMatches && !leftMatches;
        }
        return false;
      }

      var innerSchemas = getInnerSchemas(resolved.node());
      for (var inner : innerSchemas) {
        if (canMatch(doc, inner, valType, literalText)) {
          return true;
        }
      }
      return false;
    }


    return switch (valType) {
      case STRING_LITERAL ->
          isStringType(baseType) || isDateTimeType(baseType);
      case INTEGER_LITERAL -> isIntegerType(baseType);
      case FLOAT_LITERAL -> isFloatType(baseType);
      case BOOLEAN_LITERAL -> baseType.equals(":Boolean");
      case TUPLE_LITERAL -> baseType.equals(":Tuple");
      case LIST_LITERAL -> isSeqType(baseType) || isSetType(baseType);
      case MAP_LITERAL -> isMapType(baseType) || baseType.equals(":MapEntry");
      case KEYWORD_LITERAL -> {
        if (baseType.equals(":Enum")) {
          if (literalText != null) {
            if (resolved.enumSubset().isPresent()) {
              yield resolved.enumSubset().get().containsVariant(literalText);
            }
            yield isValidEnumVariant(resolved.node(), literalText);
          }
          yield true;
        }
        if (literalText != null && (literalText.equals("#TRUE") || literalText.equals("#FALSE") || literalText.equals("#T") || literalText.equals("#F")) && baseType.equals(":Boolean")) {
          yield true;
        }
        if (literalText != null && doc != null) {
          var constDefOpt = findConstantDefinition(doc, literalText);
          if (constDefOpt.isPresent()) {
            var constDef = constDefOpt.get();
            var constSchemaOpt = resolvePrimitiveSchema(doc, constDef.schemaType(), new HashSet<>());
            if (constSchemaOpt.isPresent()) {
              var constBaseType = getPrimitiveBaseType(constSchemaOpt.get().node());
              if (constBaseType != null) {
                if (isSameSchemaNode(doc, resolved.node(), constSchemaOpt.get().node())) {
                  yield true;
                }
                boolean targetNominal = resolved.node().typeKeyword() != null || resolved.aliasName().isPresent();
                boolean constNominal = constSchemaOpt.get().node().typeKeyword() != null || constSchemaOpt.get().aliasName().isPresent();
                if (!targetNominal && !constNominal) {
                  if (isIntegerType(baseType) && isIntegerType(constBaseType)) yield true;
                  if (isStringType(baseType) && isStringType(constBaseType)) yield true;
                  if (isFloatType(baseType) && isFloatType(constBaseType)) yield true;
                  if (baseType.equals(":Boolean") && constBaseType.equals(":Boolean")) yield true;
                  if (baseType.equals(":Tuple") && constBaseType.equals(":Tuple")) yield true;
                  if ((isSeqType(baseType) || isSetType(baseType)) && (isSeqType(constBaseType) || isSetType(constBaseType))) yield true;
                  if (isMapType(baseType) && isMapType(constBaseType)) yield true;
                }
              }
            }
          }
        }
        yield false;
      }
      default -> false;
    };
  }

  /**
   * Validates whether a given keyword token represents a valid variant defined within the enum schema.
   *
   * @param enumSchemaNode the schema type context representing the enum definition
   * @param tokenText      the keyword variant name to validate
   * @return {@code true} if the variant is defined in the enum, otherwise {@code false}
   */
  private static boolean isValidEnumVariant(SchemaTypeContext enumSchemaNode, String tokenText) {
    var ctor = enumSchemaNode.schemaConstructor();
    if (ctor != null && ctor.sumType() != null && ctor.sumType().KW_ENUM() != null) {
      var enumDef = ctor.sumType().enumDef();
      for (var childKw : enumDef.valueKeyword()) {
        if (childKw.getText().equals(tokenText)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean hasErrorNode(org.antlr.v4.runtime.tree.ParseTree tree) {
    if (tree == null) return false;
    if (tree instanceof org.antlr.v4.runtime.tree.ErrorNode) return true;
    for (int i = 0; i < tree.getChildCount(); i++) {
      if (hasErrorNode(tree.getChild(i))) return true;
    }
    return false;
  }

  /**
   * Evaluates if a given schema type context represents a valid, parsed schema type structure.
   * <p>
   * <b>Evaluation Criteria:</b> A schema type context is considered valid if:
   * <ul>
   *   <li>It is non-null.</li>
   *   <li>It explicitly defines a schema constructor, a type keyword reference, or a bracketed layout.</li>
   *   <li>Alternatively, if none of those are present, it has no parser exceptions and does not contain any ANTLR error nodes.</li>
   * </ul>
   *
   * @param schemaType the schema type context to evaluate; may be {@code null}
   * @return {@code true} if the schema type context meets the validity criteria; {@code false} otherwise
   */
  public static boolean isValidSchemaType(StvnParser.SchemaTypeContext schemaType) {
    if (schemaType == null) return false;
    if (schemaType.schemaConstructor() != null
        || schemaType.typeKeyword() != null) {
      return true;
    }
    return schemaType.exception == null && !hasErrorNode(schemaType);
  }

  /**
   * Extracts the direct child schema node references from a composite schema definition.
   * <p>
   * This traverses product types (tuples, map entries), sum types (options, eithers, unions),
   * and collection types (sequences, sets) to locate nested schema contexts. If the node
   * is a primitive leaf type, an empty list is returned.
   * <p>
   * <b>Value-Oriented Programming (VOP) Contract:</b> Passing a {@code null} context node
   * represents an invalid operation state and results in an immediate, fast-failing
   * {@link MalformedSchemaException} exception.
   *
   * @param node the composite schema type context to inspect; must not be {@code null}
   * @return a list of nested {@link SchemaTypeContext} elements, or an empty list if none exist
   * @throws MalformedSchemaException if the provided context node is {@code null} or if collection
   *         parameter types are malformed
   */
  public static List<StvnParser.SchemaTypeContext> getInnerSchemas(StvnParser.SchemaTypeContext node) {
    if (node == null) {
      throw new MalformedSchemaException("Schema node is null in getInnerSchemas");
    }
    if (node.schemaConstructor() != null) {
      var ctor = node.schemaConstructor();
      if (ctor.productType() != null) {
        if (ctor.productType() instanceof StvnParser.TupleTypeContext tt) {
          return tt.schemaType();
        }
      }
      if (ctor.sumType() != null) {
        return ctor.sumType().schemaType();
      }
      if (ctor.collectionType() != null) {
        var col = ctor.collectionType();
        for (var st : col.schemaType()) {
          if (!isValidSchemaType(st)) {
            throw new MalformedSchemaException("Collection type schema requires a parameter type definition (e.g. :Seq(:Int))");
          }
        }
        return col.schemaType();
      }
    }
    return List.of();
  }

  /**
   * Resolves all primitive and nested candidate schema representations under a given schema node.
   * <p>
   * For sum types (such as {@code :Either} and {@code :Union}), this method recursively unfolds
   * and resolves each constituent variant. For standard product, collection, or primitive types,
   * it returns a single resolved schema reference if valid.
   *
   * @param doc  the active document context, used for resolving type aliases
   * @param node the schema type context to resolve
   * @return a list of {@link ResolvedSchema} candidates representing the leaf definitions or variants
   */
  public static List<ResolvedSchema> resolveCandidateSchemas(@Nullable StvnDocumentContext doc, @Nullable SchemaTypeContext node) {
    if (node == null) return List.of();
    var ctor = node.schemaConstructor();
    if (ctor == null && doc != null && node.typeKeyword() != null) {
      var resolvedOpt = resolvePrimitiveSchema(doc, node, Set.of());
      if (resolvedOpt.isPresent() && resolvedOpt.get().underlyingSchema().isPresent()) {
        return resolveCandidateSchemas(doc, resolvedOpt.get().underlyingSchema().get().node());
      }
    }
    if (ctor != null && ctor.sumType() != null) {
      List<ResolvedSchema> res = new ArrayList<>();
      var sumContent = ctor.sumType();
      for (var inner : sumContent.schemaType()) {
        resolvePrimitiveSchema(doc, inner, Set.of()).ifPresent(res::add);
      }
      return res;
    }
    return resolvePrimitiveSchema(doc, node, Set.of()).map(List::of).orElse(List.of());
  }

  /**
   * Performs a bottom-up traversal of the ANTLR AST to check if the specified ancestor
   * node is a parent (or transitive ancestor) of the given node.
   *
   * @param ancestor the target ancestor node to search for
   * @param node     the current node whose lineage is being queried
   * @return {@code true} if {@code ancestor} lies on the path from {@code node} to the root,
   *         otherwise {@code false}
   */
  public static boolean isAncestor(ParseTree ancestor, @Nullable ParseTree node) {
    while (node != null) {
      if (node == ancestor) return true;
      node = node.getParent();
    }
    return false;
  }

  /**
   * Recursively verifies that all schemas nested within a type declaration have unique
   * member branch nominal type identities within any single sum type (e.g. Union or Either).
   *
   * @param doc        the document context containing the schema definition to validate
   * @param schemaNode the schema type context node to validate
   * @param visited    the set of visited type keyword names to detect and prevent cycles
   * @throws MalformedSchemaException if any structural constraint violation is detected
   * @deprecated Sum types with duplicate nominal branch types are valid coproducts as of 1.3.1.
   */
  @Deprecated
  public static void validateSchemaSumTypeUniqueness(
      @Nullable StvnDocumentContext doc,
      @Nullable SchemaTypeContext schemaNode,
      Set<String> visited) {
    // Deprecated no-op: Duplicate nominal branches are valid algebraic coproducts
  }

  /**
   * Recursively verifies that all schemas nested within a type declaration have unique
   * member branch nominal type identities within any single sum type (e.g. Union or Either),
   * accumulating errors into the provided {@link DiagnosticBag}.
   *
   * @param doc           the document context containing the schema definition to validate
   * @param schemaNode    the schema type context node to validate
   * @param visited       the set of visited type keyword names to detect and prevent cycles
   * @param diagnosticBag the accumulator bag for recording semantic diagnostics
   * @deprecated Sum types with duplicate nominal branch types are valid coproducts as of 1.3.1.
   */
  @Deprecated
  public static void validateSchemaSumTypeUniqueness(
      @Nullable StvnDocumentContext doc,
      @Nullable SchemaTypeContext schemaNode,
      Set<String> visited,
      DiagnosticBag diagnosticBag) {
    // Deprecated no-op: Duplicate nominal branches are valid algebraic coproducts
  }

  /**
   * Validates schema capability constraints against the document context.
   *
   * @param doc the STVN document context
   * @param schemaNode the root schema AST context node
   * @param visited set of previously visited schema names to prevent infinite recursion
   * @throws MalformedSchemaException if a structural capability rule is violated
   */
  public static void validateSchemaCapabilities(
      @Nullable StvnDocumentContext doc,
      @Nullable SchemaTypeContext schemaNode,
      Set<String> visited) {
    var bag = new DiagnosticBag();
    validateSchemaCapabilities(doc, schemaNode, visited, bag);
    if (bag.hasErrors()) {
      var first = bag.toList().getFirst();
      if (first.cause() instanceof RuntimeException re) {
        throw re;
      }
      throw new MalformedSchemaException(first.message(), first.startOffset(), first.endOffset(), first.cause());
    }
  }

  /**
   * Recursively verifies that all schemas nested within a type declaration satisfy structural capability rules,
   * accumulating errors into the provided {@link DiagnosticBag}.
   *
   * @param doc           the document context containing the schema definition to validate
   * @param schemaNode    the schema type context node to validate
   * @param visited       the set of visited type keyword names to detect and prevent cycles
   * @param diagnosticBag the accumulator bag for recording semantic diagnostics
   */
  public static void validateSchemaCapabilities(
      @Nullable StvnDocumentContext doc,
      @Nullable SchemaTypeContext schemaNode,
      Set<String> visited,
      DiagnosticBag diagnosticBag) {
    if (schemaNode == null) return;

    if (schemaNode.typeKeyword() != null) {
      var rawKw = schemaNode.typeKeyword().getText();
      var kw = resolveTypeIdentifier(doc, rawKw, schemaNode);
      if (!visited.add(kw)) {
        return; // Break recursion on cycle
      }
      var typeDefOpt = findTypeDefinition(doc, kw, schemaNode);
      if (typeDefOpt.isPresent()) {
        var typeDef = typeDefOpt.get();
        validateSchemaCapabilities(doc, typeDef.schemaType(), new java.util.HashSet<>(visited), diagnosticBag);
      }
    }

    var baseText = getPrimitiveBaseType(schemaNode);
    if (baseText != null) {
      if (isSetType(baseText)) {
        var inner = getInnerSchemas(schemaNode);
        if (!inner.isEmpty()) {
          var elemNode = inner.getFirst();
          var resolvedOpt = resolvePrimitiveSchema(doc, elemNode, new java.util.HashSet<>(visited));
          if (resolvedOpt.isPresent()) {
            var resolved = resolvedOpt.get();
            var equatable = resolved.constraints().equatable().orElse(false);
            if (!equatable) {
              int line = schemaNode.getStart().getLine();
              int col = schemaNode.getStart().getCharPositionInLine();
              int start = schemaNode.getStart().getStartIndex();
              int end = schemaNode.getStop().getStopIndex() + 1;
              diagnosticBag.addError(
                  "Set elements require types to be #equatable #TRUE",
                  start, end, line, col, null, DiagnosticBag.ERR_TRAIT_VIOLATION
              );
            }
          }
        }
      } else if (isMapType(baseText)) {
        var inner = getInnerSchemas(schemaNode);
        if (!inner.isEmpty()) {
          var keyNode = inner.getFirst();
          var resolvedOpt = resolvePrimitiveSchema(doc, keyNode, new java.util.HashSet<>(visited));
          if (resolvedOpt.isPresent()) {
            var resolved = resolvedOpt.get();
            var equatable = resolved.constraints().equatable().orElse(false);
            if (!equatable) {
              int line = schemaNode.getStart().getLine();
              int col = schemaNode.getStart().getCharPositionInLine();
              int start = schemaNode.getStart().getStartIndex();
              int end = schemaNode.getStop().getStopIndex() + 1;
              diagnosticBag.addError(
                  "Map keys require types to be #equatable #TRUE",
                  start, end, line, col, null, DiagnosticBag.ERR_TRAIT_VIOLATION
              );
            }
          }
        }
      }
    }

    for (var child : getInnerSchemas(schemaNode)) {
      validateSchemaCapabilities(doc, child, new java.util.HashSet<>(visited), diagnosticBag);
    }
  }

  /**
   * Performs static analysis validation of all constraints within the active document.
   *
   * @param doc the document context containing the schema definition to validate
   * @throws MalformedSchemaException if any structural constraint violation is detected
   */
  public static void validateDocumentConstraints(@Nullable StvnDocumentContext doc) {
    var bag = new DiagnosticBag();
    validateDocumentConstraints(doc, bag);
    if (bag.hasErrors()) {
      var first = bag.toList().getFirst();
      if (first.cause() instanceof RuntimeException re) {
        throw re;
      }
      throw new MalformedSchemaException(first.message(), first.startOffset(), first.endOffset(), first.cause());
    }
  }

  /**
   * Performs static analysis validation of all constraints within the active document,
   * accumulating diagnostic violations into the provided {@link DiagnosticBag}.
   *
   * @param doc           the document context containing the schema definition to validate
   * @param diagnosticBag the accumulator bag for recording semantic diagnostics
   */
  public static void validateDocumentConstraints(@Nullable StvnDocumentContext doc, DiagnosticBag diagnosticBag) {
    if (doc == null || doc.documentBody() == null) {
      return;
    }
    validateFencedStringDelimiters(doc, diagnosticBag);
    getDocumentDefinitions(doc, diagnosticBag);
    var defsEntry = doc.documentBody().defsEntry();
    if (defsEntry != null && defsEntry.defsElement() != null) {
      for (var de : defsEntry.defsElement()) {
        if (de.typeDefinition() != null) {
          validateTypeDefinition(doc, de.typeDefinition(), diagnosticBag);
        } else if (de.constantDefinition() != null) {
          validateConstantDefinition(doc, de.constantDefinition(), diagnosticBag);
        } else if (de.packageEnclosure() != null) {
          if (de.packageEnclosure().packageElement() != null) {
            for (var pe : de.packageEnclosure().packageElement()) {
              if (pe.typeDefinition() != null) {
                validateTypeDefinition(doc, pe.typeDefinition(), diagnosticBag);
              } else if (pe.constantDefinition() != null) {
                validateConstantDefinition(doc, pe.constantDefinition(), diagnosticBag);
              }
            }
          }
        }
      }
    }
    if (doc.documentBody().typeEntry() != null) {
      if (doc != StvnPrelude.getPreludeDocument()) {
        validateTemporalTypeConstraints(doc.documentBody().typeEntry().schemaType(), null, null, diagnosticBag);
      }
      try {
        resolvePrimitiveSchema(doc, doc.documentBody().typeEntry().schemaType(), new java.util.HashSet<>());
      } catch (MalformedSchemaException e) {
        int start = e.startOffset() >= 0 ? e.startOffset() : doc.documentBody().typeEntry().schemaType().getStart().getStartIndex();
        int end = e.endOffset() >= 0 ? e.endOffset() : doc.documentBody().typeEntry().schemaType().getStop().getStopIndex() + 1;
        int line = doc.documentBody().typeEntry().schemaType().getStart().getLine();
        int col = doc.documentBody().typeEntry().schemaType().getStart().getCharPositionInLine();
        String msg = e.getMessage() != null ? e.getMessage() : "";
        String code = DiagnosticBag.ERR_MALFORMED_SCHEMA;
        if (msg.contains("Legacy temporal epoch keyword") || msg.contains("requires a scale facet")) {
          code = DiagnosticBag.ERR_TEMPORAL_SCALE_MISSING;
        } else if (msg.contains("Legacy datetime keyword") || msg.contains("requires exactly one mode facet")) {
          code = DiagnosticBag.ERR_DATETIME_MODE_INVALID;
        } else if (msg.contains("Compound") || msg.contains("deprecated in 2.0.0")) {
          code = DiagnosticBag.ERR_COMPOUND_TYPE_OBSOLETE;
        } else if (msg.contains("prelude") && msg.contains("purged")) {
          code = DiagnosticBag.ERR_PRELUDE_ALIAS_PURGED;
        } else if (msg.contains("Undefined type") || msg.contains("Unknown or undefined type")) {
          code = DiagnosticBag.ERR_UNKNOWN_TYPE;
        } else if (msg.contains("filter facets")) {
          code = DiagnosticBag.ERR_INVALID_METADATA_FACET;
        }
        diagnosticBag.addError(e.getMessage(), start, end, line, col, e, code);
      } catch (CircularReferenceException e) {
        int start = doc.documentBody().typeEntry().schemaType().getStart().getStartIndex();
        int end = doc.documentBody().typeEntry().schemaType().getStop().getStopIndex() + 1;
        int line = doc.documentBody().typeEntry().schemaType().getStart().getLine();
        int col = doc.documentBody().typeEntry().schemaType().getStart().getCharPositionInLine();
        diagnosticBag.addError(e.getMessage(), start, end, line, col, e, DiagnosticBag.ERR_CIRCULAR_TYPE);
      }
      validateSchemaCapabilities(doc, doc.documentBody().typeEntry().schemaType(), new java.util.HashSet<>(), diagnosticBag);
    }
  }

  /**
   * Scans the document parse tree for deprecated Rule STR-04 fenced string arrow delimiters.
   *
   * @param tree          the parse tree node to inspect recursively
   * @param diagnosticBag the accumulator bag for recording deprecation warnings
   */
  public static void validateFencedStringDelimiters(
      @Nullable ParseTree tree,
      DiagnosticBag diagnosticBag
  ) {
    if (tree == null) {
      return;
    }
    if (tree instanceof StvnParser.FencedStringContext fencedCtx) {
      var fenceStart = fencedCtx.FENCE_START();
      if (fenceStart != null) {
        var token = fenceStart.getSymbol();
        if (token != null && token.getText().startsWith("\"\"\"->")) {
          diagnosticBag.addWarning(
              org.stvnadore.core.parser.StvnErrorListener.RULE_STR_04_ARROW_DEPRECATION_MSG,
              token.getStartIndex(),
              token.getStopIndex() + 1,
              token.getLine(),
              token.getCharPositionInLine(),
              DiagnosticBag.WARN_DEPRECATED_FENCE_ARROW
          );
        }
      }
    }
    for (int i = 0; i < tree.getChildCount(); i++) {
      validateFencedStringDelimiters(tree.getChild(i), diagnosticBag);
    }
  }

  /**
   * Validates the constraints of a single type definition against its underlying primitive base type.
   *
   * @param doc     the document context, used to resolve type aliases recursively
   * @param typeDef the specific type definition AST node to validate
   * @throws MalformedSchemaException if illegal or contradictory metadata constraints are configured
   */
  public static void validateTypeDefinition(StvnDocumentContext doc, StvnParser.TypeDefinitionContext typeDef) {
    var bag = new DiagnosticBag();
    validateTypeDefinition(doc, typeDef, bag);
    if (bag.hasErrors()) {
      var first = bag.toList().getFirst();
      if (first.cause() instanceof RuntimeException re) {
        throw re;
      }
      throw new MalformedSchemaException(first.message(), first.startOffset(), first.endOffset(), first.cause());
    }
  }

  /**
   * Validates the constraints of a single type definition against its underlying primitive base type,
   * accumulating diagnostic violations into the provided {@link DiagnosticBag}.
   *
   * @param doc           the document context, used to resolve type aliases recursively
   * @param typeDef       the specific type definition AST node to validate
   * @param diagnosticBag the accumulator bag for recording semantic diagnostics
   */
  public static void validateTypeDefinition(StvnDocumentContext doc, StvnParser.TypeDefinitionContext typeDef, DiagnosticBag diagnosticBag) {
    if (typeDef == null) {
      diagnosticBag.addError("Type definition is null in validateTypeDefinition", -1, -1, null, DiagnosticBag.ERR_MALFORMED_SCHEMA);
      return;
    }
    var typeName = typeDef.typeDefTarget().getText();
    if (isReservedFundamentalType(typeName) || typeDef.typeDefTarget().reservedKeyword() != null) {
      int line = typeDef.typeDefTarget().getStart().getLine();
      int col = typeDef.typeDefTarget().getStart().getCharPositionInLine();
      int start = typeDef.typeDefTarget().getStart().getStartIndex();
      int end = typeDef.typeDefTarget().getStop().getStopIndex() + 1;
      diagnosticBag.addError(
          "Reserved fundamental type or keyword cannot be used on left-hand side of type definition: " + typeName,
          start, end, line, col, null, DiagnosticBag.ERR_RESERVED_KEYWORD_ON_LHS
      );
      markTypePoisoned(doc, typeName);
      return;
    }
    var visited = new java.util.LinkedHashSet<String>();
    visited.add(typeName);
    Optional<ResolvedSchema> resolvedOpt;
    try {
      resolvedOpt = resolvePrimitiveSchema(doc, typeDef.schemaType(), visited);
    } catch (CircularReferenceException e) {
      int line = typeDef.getStart().getLine();
      int col = typeDef.getStart().getCharPositionInLine();
      int start = typeDef.getStart().getStartIndex();
      int end = typeDef.getStop().getStopIndex() + 1;
      diagnosticBag.addError(e.getMessage(), start, end, line, col, e, DiagnosticBag.ERR_CIRCULAR_TYPE);
      markTypePoisoned(doc, typeName);
      return;
    } catch (MalformedSchemaException e) {
      int line = typeDef.getStart().getLine();
      int col = typeDef.getStart().getCharPositionInLine();
      int start = e.startOffset() >= 0 ? e.startOffset() : typeDef.getStart().getStartIndex();
      int end = e.endOffset() >= 0 ? e.endOffset() : typeDef.getStop().getStopIndex() + 1;
      String msg = e.getMessage() != null ? e.getMessage() : "";
      String code = DiagnosticBag.ERR_MALFORMED_SCHEMA;
      if (msg.contains("Legacy temporal epoch keyword") || msg.contains("requires a scale facet")) {
        code = DiagnosticBag.ERR_TEMPORAL_SCALE_MISSING;
      } else if (msg.contains("Legacy datetime keyword") || msg.contains("requires exactly one mode facet")) {
        code = DiagnosticBag.ERR_DATETIME_MODE_INVALID;
      } else if (msg.contains("Compound") || msg.contains("deprecated in 2.0.0")) {
        code = DiagnosticBag.ERR_COMPOUND_TYPE_OBSOLETE;
      } else if (msg.contains("prelude") && msg.contains("purged")) {
        code = DiagnosticBag.ERR_PRELUDE_ALIAS_PURGED;
      } else if (msg.contains("Undefined type") || msg.contains("Unknown or undefined type")) {
        code = DiagnosticBag.ERR_UNKNOWN_TYPE;
      } else if (msg.contains("filter facets") || msg.contains("is not permitted on")) {
        code = DiagnosticBag.ERR_INVALID_METADATA_FACET;
      } else if (msg.contains("Facet '#size' is prohibited on :String") || msg.contains("#size' is prohibited on :String")) {
        code = DiagnosticBag.ERR_STRING_CARDINALITY_PROHIBITED;
      } else if (msg.contains("violates canonical 7-tier order")) {
        code = DiagnosticBag.ERR_FACET_ORDER_VIOLATION;
      } else if (msg.contains("defines an empty domain")) {
        code = DiagnosticBag.ERR_EMPTY_INTERVAL_DOMAIN;
      }
      diagnosticBag.addError(e.getMessage(), start, end, line, col, e, code);
      markTypePoisoned(doc, typeName);
      return;
    }

    validateSchemaCapabilities(doc, typeDef.schemaType(), new java.util.HashSet<>(), diagnosticBag);
    if (typeDef.metadataMap() != null) {
      if (typeDef.metadataMap().metadataEntry().isEmpty()) {
        diagnosticBag.addError(
            "Empty metadata block is invalid; remove '{}' or specify valid facets",
            typeDef.metadataMap().getStart().getStartIndex(),
            typeDef.metadataMap().getStop().getStopIndex() + 1,
            typeDef.metadataMap().getStart().getLine(),
            typeDef.metadataMap().getStart().getCharPositionInLine(),
            null,
            DiagnosticBag.ERR_EMPTY_METADATA_BLOCK
        );
      }
      validateMetadataMapConstraints(typeName, typeDef.metadataMap(), resolvedOpt.orElse(null), diagnosticBag);
      if (resolvedOpt.isPresent()) {
        var metaConstraints = extractConstraints(typeDef.metadataMap());
        if (metaConstraints.invertible()) {
          var baseType = getPrimitiveBaseType(resolvedOpt.get().node());
          if (baseType != null && isMapType(baseType)) {
            var inner = getInnerSchemas(resolvedOpt.get().node());
            if (inner.size() >= 2) {
              var valOpt = resolvePrimitiveSchema(doc, inner.get(1), new java.util.HashSet<>());
              if (valOpt.isPresent() && !valOpt.get().constraints().equatable().orElse(false)) {
                int line = typeDef.metadataMap().getStart().getLine();
                int col = typeDef.metadataMap().getStart().getCharPositionInLine();
                int start = typeDef.metadataMap().getStart().getStartIndex();
                int end = typeDef.metadataMap().getStop().getStopIndex() + 1;
                diagnosticBag.addError(
                    "Inverted map values require types to be #equatable #TRUE",
                    start, end, line, col, null, DiagnosticBag.ERR_TRAIT_VIOLATION
                );
              }
            }
          }
        }
      }
    }
    if (doc != StvnPrelude.getPreludeDocument()) {
      validateTemporalTypeConstraints(typeDef.schemaType(), typeDef.metadataMap(), resolvedOpt.orElse(null), diagnosticBag);
    }
  }

  /**
   * Validates a typed constant definition, verifying that its schema is sound,
   * its metadata constraints are legally configured, its assigned payload
   * conforms to the declared schema, and no circular constant dependencies exist.
   *
   * @param doc      the STVN document context
   * @param constDef the constant definition AST node to validate
   * @throws MalformedSchemaException if validation fails
   */
  public static void validateConstantDefinition(StvnDocumentContext doc, StvnParser.ConstantDefinitionContext constDef) {
    var bag = new DiagnosticBag();
    validateConstantDefinition(doc, constDef, bag);
    if (bag.hasErrors()) {
      var first = bag.toList().getFirst();
      if (first.cause() instanceof RuntimeException re) {
        throw re;
      }
      throw new MalformedSchemaException(first.message(), first.startOffset(), first.endOffset(), first.cause());
    }
  }

  /**
   * Validates a typed constant definition, accumulating diagnostic violations into the provided {@link DiagnosticBag}.
   *
   * @param doc           the STVN document context
   * @param constDef      the constant definition AST node to validate
   * @param diagnosticBag the accumulator bag for recording semantic diagnostics
   */
  public static void validateConstantDefinition(StvnDocumentContext doc, StvnParser.ConstantDefinitionContext constDef, DiagnosticBag diagnosticBag) {
    if (constDef == null) {
      diagnosticBag.addError("Constant definition is null in validateConstantDefinition", -1, -1, null, DiagnosticBag.ERR_MALFORMED_SCHEMA);
      return;
    }
    var constName = constDef.valueKeyword().getText();
    var visited = new java.util.LinkedHashSet<String>();
    visited.add(constName);
    Optional<ResolvedSchema> resolvedOpt;
    try {
      resolvedOpt = resolvePrimitiveSchema(doc, constDef.schemaType(), visited);
    } catch (CircularReferenceException e) {
      int line = constDef.getStart().getLine();
      int col = constDef.getStart().getCharPositionInLine();
      int start = constDef.getStart().getStartIndex();
      int end = constDef.getStop().getStopIndex() + 1;
      diagnosticBag.addError(e.getMessage(), start, end, line, col, e, DiagnosticBag.ERR_CIRCULAR_TYPE);
      return;
    } catch (MalformedSchemaException e) {
      int line = constDef.getStart().getLine();
      int col = constDef.getStart().getCharPositionInLine();
      int start = e.startOffset() >= 0 ? e.startOffset() : constDef.getStart().getStartIndex();
      int end = e.endOffset() >= 0 ? e.endOffset() : constDef.getStop().getStopIndex() + 1;
      String msg = e.getMessage() != null ? e.getMessage() : "";
      String code = DiagnosticBag.ERR_MALFORMED_SCHEMA;
      if (msg.contains("Legacy temporal epoch keyword") || msg.contains("requires a scale facet")) {
        code = DiagnosticBag.ERR_TEMPORAL_SCALE_MISSING;
      } else if (msg.contains("Legacy datetime keyword") || msg.contains("requires exactly one mode facet")) {
        code = DiagnosticBag.ERR_DATETIME_MODE_INVALID;
      } else if (msg.contains("Compound") || msg.contains("deprecated in 2.0.0")) {
        code = DiagnosticBag.ERR_COMPOUND_TYPE_OBSOLETE;
      } else if (msg.contains("prelude") && msg.contains("purged")) {
        code = DiagnosticBag.ERR_PRELUDE_ALIAS_PURGED;
      } else if (msg.contains("Undefined type") || msg.contains("Unknown or undefined type")) {
        code = DiagnosticBag.ERR_UNKNOWN_TYPE;
      } else if (msg.contains("filter facets")) {
        code = DiagnosticBag.ERR_INVALID_METADATA_FACET;
      }
      diagnosticBag.addError(e.getMessage(), start, end, line, col, e, code);
      return;
    }

    validateSchemaCapabilities(doc, constDef.schemaType(), new java.util.HashSet<>(), diagnosticBag);
    if (constDef.metadataMap() != null) {
      if (constDef.metadataMap().metadataEntry().isEmpty()) {
        diagnosticBag.addError(
            "Empty metadata block is invalid; remove '{}' or specify valid facets",
            constDef.metadataMap().getStart().getStartIndex(),
            constDef.metadataMap().getStop().getStopIndex() + 1,
            constDef.metadataMap().getStart().getLine(),
            constDef.metadataMap().getStart().getCharPositionInLine(),
            null,
            DiagnosticBag.ERR_EMPTY_METADATA_BLOCK
        );
      }
      for (var entry : constDef.metadataMap().metadataEntry()) {
        if (entry.metadataFilter() != null) {
          var f = entry.metadataFilter();
          var constraintName = f.KW_FILTER_INCL() != null ? "filterIncl" : "filterExcl";
          diagnosticBag.addError(
              "Constraint violation (" + constName + "): " + constraintName + " is not allowed on constants; filter facets are prohibited on constants; permitted facets: []",
              f.getStart().getStartIndex(),
              f.getStop().getStopIndex() + 1,
              f.getStart().getLine(),
              f.getStart().getCharPositionInLine(),
              null,
              DiagnosticBag.ERR_INVALID_METADATA_FACET
          );
        }
      }
      validateMetadataMapConstraints(constName, constDef.metadataMap(), resolvedOpt.orElse(null), diagnosticBag);
    }

    // Detect constant circular reference loops
    detectConstantCycles(doc, constDef, new java.util.LinkedHashSet<>(List.of(constName)), diagnosticBag);

    // Validate constant literal value constraints
    if (resolvedOpt.isPresent() && constDef.value() != null) {
      var localConstraints = constDef.metadataMap() != null
          ? extractConstraints(constDef.metadataMap())
          : StvnConstraints.empty();
      var effectiveConstraints = localConstraints.merge(resolvedOpt.get().constraints());
      validateConstantValueConstraints(constName, constDef.value(), effectiveConstraints, diagnosticBag);
      validateConstantBitWidthCapacity(constName, resolvedOpt.get(), effectiveConstraints, constDef.value(), diagnosticBag);
    }
  }

  private static void detectConstantCycles(StvnDocumentContext doc, StvnParser.ConstantDefinitionContext constDef, Set<String> visited, DiagnosticBag diagnosticBag) {
    if (constDef.value() != null && constDef.value().valueKeyword() != null) {
      String refConst = constDef.value().valueKeyword().getText();
      if (visited.contains(refConst)) {
        int line = constDef.getStart().getLine();
        int col = constDef.getStart().getCharPositionInLine();
        int start = constDef.getStart().getStartIndex();
        int end = constDef.getStop().getStopIndex() + 1;
        diagnosticBag.addError(
            "Circular constant definition detected: " + String.join(" -> ", visited) + " -> " + refConst,
            start, end, line, col, null, DiagnosticBag.ERR_CIRCULAR_TYPE
        );
        return;
      }
      var targetOpt = findConstantDefinition(doc, refConst);
      if (targetOpt.isPresent()) {
        var nextVisited = new LinkedHashSet<>(visited);
        nextVisited.add(refConst);
        detectConstantCycles(doc, targetOpt.get(), nextVisited, diagnosticBag);
      }
    }
  }

  private static void validateConstantValueConstraints(String constName, StvnParser.ValueContext valueCtx, StvnConstraints c, DiagnosticBag diagnosticBag) {
    int line = valueCtx.getStart().getLine();
    int col = valueCtx.getStart().getCharPositionInLine();
    int start = valueCtx.getStart().getStartIndex();
    int end = valueCtx.getStop().getStopIndex() + 1;

    if (valueCtx.integerLiteral() != null) {
      var valBI = StvnLiteralParser.parseBigInteger(valueCtx.integerLiteral().getText());
      if (c.minIncl().isPresent() && valBI.compareTo(c.minIncl().get().toBigIntegerExact()) < 0) {
        diagnosticBag.addError("Constraint violation (" + constName + "): Value must be greater than or equal to " + c.minIncl().get(), start, end, line, col, null, DiagnosticBag.ERR_INVERTED_RANGE);
      }
      if (c.minExcl().isPresent() && valBI.compareTo(c.minExcl().get().toBigIntegerExact()) <= 0) {
        diagnosticBag.addError("Constraint violation (" + constName + "): Value must be strictly greater than " + c.minExcl().get(), start, end, line, col, null, DiagnosticBag.ERR_INVERTED_RANGE);
      }
      if (c.maxIncl().isPresent() && valBI.compareTo(c.maxIncl().get().toBigIntegerExact()) > 0) {
        diagnosticBag.addError("Constraint violation (" + constName + "): Value must be less than or equal to " + c.maxIncl().get(), start, end, line, col, null, DiagnosticBag.ERR_INVERTED_RANGE);
      }
      if (c.maxExcl().isPresent() && valBI.compareTo(c.maxExcl().get().toBigIntegerExact()) >= 0) {
        diagnosticBag.addError("Constraint violation (" + constName + "): Value must be strictly less than " + c.maxExcl().get(), start, end, line, col, null, DiagnosticBag.ERR_INVERTED_RANGE);
      }
    } else if (valueCtx.floatLiteral() != null) {
      var valBD = new BigDecimal(valueCtx.floatLiteral().getText());
      if (c.minIncl().isPresent() && valBD.compareTo(c.minIncl().get()) < 0) {
        diagnosticBag.addError("Constraint violation (" + constName + "): Value must be greater than or equal to " + c.minIncl().get(), start, end, line, col, null, DiagnosticBag.ERR_INVERTED_RANGE);
      }
      if (c.minExcl().isPresent() && valBD.compareTo(c.minExcl().get()) <= 0) {
        diagnosticBag.addError("Constraint violation (" + constName + "): Value must be strictly greater than " + c.minExcl().get(), start, end, line, col, null, DiagnosticBag.ERR_INVERTED_RANGE);
      }
      if (c.maxIncl().isPresent() && valBD.compareTo(c.maxIncl().get()) > 0) {
        diagnosticBag.addError("Constraint violation (" + constName + "): Value must be less than or equal to " + c.maxIncl().get(), start, end, line, col, null, DiagnosticBag.ERR_INVERTED_RANGE);
      }
      if (c.maxExcl().isPresent() && valBD.compareTo(c.maxExcl().get()) >= 0) {
        diagnosticBag.addError("Constraint violation (" + constName + "): Value must be strictly less than " + c.maxExcl().get(), start, end, line, col, null, DiagnosticBag.ERR_INVERTED_RANGE);
      }
    } else if (valueCtx.stringLiteral() != null && c.regex().isPresent()) {
      var parsed = StvnLiteralParser.parseStringNew(valueCtx.stringLiteral().getText(), false);
      try {
        if (!java.util.regex.Pattern.compile(c.regex().get()).matcher(parsed.text()).matches()) {
          diagnosticBag.addError("Constraint violation (" + constName + "): String does not match required pattern: " + c.regex().get(), start, end, line, col, null, DiagnosticBag.ERR_INVALID_REGEX);
        }
      } catch (java.util.regex.PatternSyntaxException e) {
        diagnosticBag.addError("Constraint violation (" + constName + "): Invalid regex pattern: " + c.regex().get(), start, end, line, col, e, DiagnosticBag.ERR_INVALID_REGEX);
      }
    }
  }

  private static final Map<String, Set<String>> PERMITTED_FACETS = Map.ofEntries(
      // 6 Foundation Scalars
      Map.entry(":Int", Set.of("unsigned", "equatable", "comparable", "size", "minIncl", "maxExcl")),
      Map.entry(":Float", Set.of("exact", "equatable", "comparable", "size", "minIncl", "minExcl", "maxExcl", "maxIncl")),
      Map.entry(":String", Set.of("preserveIndent", "equatable", "comparable", "minSize", "maxSize", "regex")),
      Map.entry(":Boolean", Set.of("equatable")),
      Map.entry(":TimeEpoch", Set.of("s", "ms", "us", "ns", "equatable", "comparable", "minIncl", "maxExcl")),
      Map.entry(":DateTime", Set.of("offset", "zoned", "audited", "equatable", "comparable", "minIncl", "maxExcl")),

      // 6 Collections and Algebraic Composites
      Map.entry(":Seq", Set.of("minSize", "maxSize", "equatable", "comparable")),
      Map.entry(":Set", Set.of("minSize", "maxSize", "equatable")),
      Map.entry(":Map", Set.of("invertible", "minSize", "maxSize", "equatable")),
      Map.entry(":Tuple", Set.of("equatable", "comparable")),
      Map.entry(":Union", Set.of("equatable", "comparable")),
      Map.entry(":Enum", Set.of("equatable", "comparable", "filterIncl", "filterExcl"))
  );

  private static int getFacetTier(String facetName) {
    return switch (facetName) {
      case "unsigned", "exact", "invertible", "preserveIndent", "offset", "zoned", "audited", "equatable", "comparable" -> 1;
      case "s", "ms", "us", "ns" -> 2;
      case "size", "minSize", "maxSize" -> 3;
      case "minIncl", "minExcl", "maxExcl", "maxIncl" -> 4;
      case "regex" -> 5;
      case "filterIncl", "filterExcl" -> 6;
      case "strip" -> 7;
      default -> 99;
    };
  }

  private static int getFacetSubTier(String facetName) {
    return switch (facetName) {
      // Tier 1 sub-order
      case "unsigned" -> 101; case "exact" -> 102; case "invertible" -> 103;
      case "preserveIndent" -> 104; case "offset" -> 105; case "zoned" -> 106;
      case "audited" -> 107; case "equatable" -> 108; case "comparable" -> 109;
      // Tier 2 sub-order
      case "s" -> 201; case "ms" -> 202; case "us" -> 203; case "ns" -> 204;
      // Tier 3 sub-order
      case "size" -> 301; case "minSize" -> 302; case "maxSize" -> 303;
      // Tier 4 sub-order: Lower bounds strictly precede Upper bounds
      case "minIncl" -> 401; case "minExcl" -> 402; case "maxExcl" -> 403; case "maxIncl" -> 404;
      // Tier 5 sub-order
      case "regex" -> 501;
      // Tier 6 sub-order
      case "filterIncl" -> 601; case "filterExcl" -> 602;
      // Tier 7 sub-order
      case "strip" -> 701;
      default -> 999;
    };
  }

  private static @Nullable String extractFacetName(StvnParser.MetadataEntryContext entry) {
    if (entry == null) return null;
    if (entry.metadataFlag() != null) {
      var flagCtx = entry.metadataFlag();
      if (flagCtx.KW_UNSIGNED() != null) return "unsigned";
      if (flagCtx.KW_EXACT() != null) return "exact";
      if (flagCtx.KW_INVERTIBLE() != null) return "invertible";
      if (flagCtx.KW_PRESERVE_INDENT() != null) return "preserveIndent";
      if (flagCtx.KW_OFFSET() != null) return "offset";
      if (flagCtx.KW_ZONED() != null) return "zoned";
      if (flagCtx.KW_AUDITED() != null) return "audited";
      if (flagCtx.KW_SCALE_S() != null) return "s";
      if (flagCtx.KW_SCALE_MS() != null) return "ms";
      if (flagCtx.KW_SCALE_US() != null) return "us";
      if (flagCtx.KW_SCALE_NS() != null) return "ns";
    }
    if (entry.metadataBool() != null) {
      var boolCtx = entry.metadataBool();
      if (boolCtx.KW_EQUATABLE() != null) return "equatable";
      if (boolCtx.KW_COMPARABLE() != null) return "comparable";
    }
    if (entry.metadataSize() != null) {
      var sizeCtx = entry.metadataSize();
      if (sizeCtx.KW_SIZE() != null) return "size";
      if (sizeCtx.KW_MIN_SIZE() != null) return "minSize";
      if (sizeCtx.KW_MAX_SIZE() != null) return "maxSize";
    }
    if (entry.metadataRange() != null) {
      var numCtx = entry.metadataRange();
      if (numCtx.KW_MIN_INCL() != null) return "minIncl";
      if (numCtx.KW_MIN_EXCL() != null) return "minExcl";
      if (numCtx.KW_MAX_EXCL() != null) return "maxExcl";
      if (numCtx.KW_MAX_INCL() != null) return "maxIncl";
    }
    if (entry.metadataString() != null) {
      var strCtx = entry.metadataString();
      if (strCtx.KW_REGEX() != null) return "regex";
    }
    if (entry.metadataFilter() != null) {
      var filterCtx = entry.metadataFilter();
      if (filterCtx.KW_FILTER_INCL() != null) return "filterIncl";
      if (filterCtx.KW_FILTER_EXCL() != null) return "filterExcl";
    }
    if (entry.metadataDirective() != null) {
      return "strip";
    }
    return null;
  }

  private static @Nullable String normalizeBaseType(@Nullable String baseType) {
    if (baseType == null) return null;
    if (isTimeEpochType(baseType) || baseType.equals(":TimeEpoch") || baseType.endsWith("/TimeEpoch")) return ":TimeEpoch";
    if (isDateTimeType(baseType) || baseType.equals(":DateTime") || baseType.endsWith("/DateTime")) return ":DateTime";
    if (baseType.startsWith(":Int") || baseType.startsWith(":Uint")) return ":Int";
    if (isFloatType(baseType) || baseType.startsWith(":Float")) return ":Float";
    if (isStringType(baseType) || baseType.startsWith(":String")) return ":String";
    if (":Boolean".equals(baseType)) return ":Boolean";
    if (isSeqType(baseType)) return ":Seq";
    if (isSetType(baseType)) return ":Set";
    if (isMapType(baseType)) return ":Map";
    if (":Tuple".equals(baseType)) return ":Tuple";
    if (":Union".equals(baseType)) return ":Union";
    if (":Enum".equals(baseType)) return ":Enum";
    return baseType;
  }

  private static void validateDateTimeIntervalBounds(
      String name,
      MetadataMapContext metadataMap,
      StvnConstraints constraints,
      DiagnosticBag diagnosticBag
  ) {
    if (constraints.dateMinIncl().isEmpty() && constraints.dateMaxExcl().isEmpty()) return;

    Instant minInstant = null;
    Instant maxInstant = null;

    if (constraints.dateMinIncl().isPresent()) {
      minInstant = parseAndValidateDateBound(constraints.dateMinIncl().get(), "minIncl", name, metadataMap, diagnosticBag);
    }
    if (constraints.dateMaxExcl().isPresent()) {
      maxInstant = parseAndValidateDateBound(constraints.dateMaxExcl().get(), "maxExcl", name, metadataMap, diagnosticBag);
    }

    if (minInstant != null && maxInstant != null) {
      if (minInstant.equals(maxInstant)) {
        diagnosticBag.addError(
            "Constraint violation (" + name + "): discrete datetime interval [" + constraints.dateMinIncl().get() +
            ", " + constraints.dateMaxExcl().get() + ") defines an empty domain (zero habitable values)",
            metadataMap.getStart().getStartIndex(), metadataMap.getStop().getStopIndex() + 1,
            metadataMap.getStart().getLine(), metadataMap.getStart().getCharPositionInLine(),
            null, DiagnosticBag.ERR_EMPTY_INTERVAL_DOMAIN
        );
      } else if (minInstant.isAfter(maxInstant)) {
        diagnosticBag.addError(
            "Constraint violation (" + name + "): effective datetime range is invalid (minimum instant " +
            constraints.dateMinIncl().get() + " is chronologically after maximum instant " + constraints.dateMaxExcl().get() + ")",
            metadataMap.getStart().getStartIndex(), metadataMap.getStop().getStopIndex() + 1,
            metadataMap.getStart().getLine(), metadataMap.getStart().getCharPositionInLine(),
            null, DiagnosticBag.ERR_INVERTED_RANGE
        );
      }
    }
  }

  private static @Nullable Instant parseAndValidateDateBound(
      String raw,
      String boundName,
      String typeName,
      MetadataMapContext metadataMap,
      DiagnosticBag diagnosticBag
  ) {
    int start = metadataMap.getStart().getStartIndex();
    int end = metadataMap.getStop().getStopIndex() + 1;
    int line = metadataMap.getStart().getLine();
    int col = metadataMap.getStart().getCharPositionInLine();

    try {
      String quoted = raw.startsWith("\"") ? raw : ("\"" + raw + "\"");
      if (StvnLiteralParser.DATETIME_AUDITED_PATTERN.matcher(quoted).matches()) {
        var audited = StvnLiteralParser.parseDateTimeAudited(quoted);
        ZoneOffset expectedOffset = audited.zoneId().getRules().getOffset(audited.offsetDateTime().toInstant());
        if (!audited.offsetDateTime().getOffset().equals(expectedOffset)) {
          diagnosticBag.addError(
              "Constraint violation (" + typeName + "): Contradictory offset in audited datetime bound: " + raw,
              start, end, line, col, null, DiagnosticBag.ERR_INCOMPATIBLE_TYPE
          );
        }
        return audited.offsetDateTime().toInstant();
      } else if (StvnLiteralParser.DATETIME_ZONED_PATTERN.matcher(quoted).matches()) {
        var zoned = StvnLiteralParser.parseDateTimeZoned(quoted);
        if (zoned.zoneId().getRules().getValidOffsets(zoned.localDateTime()).isEmpty()) {
          diagnosticBag.addError(
              "Constraint violation (" + typeName + "): Datetime bound falls in DST spring-forward gap: " + raw,
              start, end, line, col, null, DiagnosticBag.ERR_INCOMPATIBLE_TYPE
          );
        }
        return zoned.localDateTime().atZone(zoned.zoneId()).toInstant();
      } else if (StvnLiteralParser.DATETIME_OFFSET_PATTERN.matcher(quoted).matches()) {
        var offset = StvnLiteralParser.parseDateTimeOffset(quoted);
        return offset.value().toInstant();
      } else {
        diagnosticBag.addError(
            "Constraint violation (" + typeName + "): invalid ISO-8601 datetime literal in #" + boundName + ": " + raw,
            start, end, line, col, null, DiagnosticBag.ERR_INCOMPATIBLE_TYPE
        );
        return null;
      }
    } catch (Exception e) {
      diagnosticBag.addError(
          "Constraint violation (" + typeName + "): invalid ISO-8601 datetime literal in #" + boundName + ": " + raw + " (" + e.getMessage() + ")",
          start, end, line, col, e, DiagnosticBag.ERR_INCOMPATIBLE_TYPE
      );
      return null;
    }
  }

  private static void validateMetadataMapConstraints(String name, MetadataMapContext metadataMap, @Nullable ResolvedSchema resolved, DiagnosticBag diagnosticBag) {
    if (metadataMap == null) return;

    // 1. Strict 7-Tier Canonical Facet Ordering & Universal Allowlist Check
    String normalizedBase = resolved != null ? normalizeBaseType(getPrimitiveBaseType(resolved.node())) : null;
    Set<String> permitted = normalizedBase != null ? PERMITTED_FACETS.get(normalizedBase) : null;

    int lastTier = -1;
    int lastSubTier = -1;
    String lastFacetName = null;

    for (var entry : metadataMap.metadataEntry()) {
      String facetName = extractFacetName(entry);
      if (facetName == null) continue;

      int tier = getFacetTier(facetName);
      int subTier = getFacetSubTier(facetName);

      // Enforce Strict 7-Tier Ordering
      if (tier < lastTier || (tier == lastTier && subTier < lastSubTier)) {
        diagnosticBag.addError(
            "Facet order violation (" + name + "): facet '#" + facetName + "' (Tier " + tier +
            ") violates canonical 7-tier order after '#" + lastFacetName + "'",
            entry.getStart().getStartIndex(),
            entry.getStop().getStopIndex() + 1,
            entry.getStart().getLine(),
            entry.getStart().getCharPositionInLine(),
            null,
            DiagnosticBag.ERR_FACET_ORDER_VIOLATION
        );
      }
      lastTier = tier;
      lastSubTier = subTier;
      lastFacetName = facetName;

      // Universal Allowlist Gating (Early Fail-Closed)
      if ("strip".equals(facetName)) {
        diagnosticBag.addError(
            "Constraint violation (" + name + "): directive facet '#strip' is not permitted on type declarations; directive facets are valid strictly in :use and :include blocks",
            entry.getStart().getStartIndex(),
            entry.getStop().getStopIndex() + 1,
            entry.getStart().getLine(),
            entry.getStart().getCharPositionInLine(),
            null,
            DiagnosticBag.ERR_INVALID_METADATA_FACET
        );
      } else if (permitted != null && !permitted.contains(facetName)) {
        if ((":Int".equals(normalizedBase) || ":TimeEpoch".equals(normalizedBase) || ":DateTime".equals(normalizedBase))
            && ("minExcl".equals(facetName) || "maxIncl".equals(facetName))) {
          // Discrete bound kind violations on discrete types are handled specifically by discrete interval validator
          continue;
        }
        String errCode = (":String".equals(normalizedBase) && "size".equals(facetName))
            ? DiagnosticBag.ERR_STRING_CARDINALITY_PROHIBITED
            : DiagnosticBag.ERR_INVALID_METADATA_FACET;

        String message;
        if (":String".equals(normalizedBase) && "size".equals(facetName)) {
          message = "Facet '#size' is prohibited on :String; use '#minSize' and '#maxSize' (e.g. { #minSize N #maxSize N } :String)";
        } else if (":String".equals(normalizedBase) && (facetName.equals("minIncl") || facetName.equals("minExcl") || facetName.equals("maxIncl") || facetName.equals("maxExcl"))) {
          message = "Constraint violation (" + name + "): facet '" + facetName + "' is not permitted on " + normalizedBase + "; permitted facets for numeric types: [#equatable, #comparable, #minIncl, #maxIncl, #minExcl, #maxExcl]";
        } else if (":Int".equals(normalizedBase) && "regex".equals(facetName)) {
          message = "Constraint violation (" + name + "): facet 'regex' is not permitted on :Int; permitted facets for string types: [#equatable, #comparable, #regex, #preserveIndent]";
        } else if ("preserveIndent".equals(facetName)) {
          message = "Constraint violation (" + name + "): facet 'preserveIndent' is not permitted on " + normalizedBase + "; permitted facets for string types: [#equatable, #comparable, #regex, #preserveIndent]";
        } else if ("filterIncl".equals(facetName) || "filterExcl".equals(facetName)) {
          message = "Constraint violation (" + name + "): facet '" + facetName + "' is not permitted on " + normalizedBase + "; filter facets are permitted strictly on nominal aliases of :Enum and enum subsets";
        } else {
          message = "Constraint violation (" + name + "): facet '#" + facetName + "' is not permitted on " + normalizedBase +
              "; permitted facets: " + permitted.stream().map(f -> "#" + f).toList();
        }

        diagnosticBag.addError(
            message,
            entry.getStart().getStartIndex(),
            entry.getStop().getStopIndex() + 1,
            entry.getStart().getLine(),
            entry.getStart().getCharPositionInLine(),
            null,
            errCode
        );
      }
    }

    var hasMinIncl = false;
    var hasMinExcl = false;
    var hasMaxIncl = false;
    var hasMaxExcl = false;
    var hasFilterIncl = false;
    var hasFilterExcl = false;

    for (var entry : metadataMap.metadataEntry()) {
      if (entry.metadataRange() != null) {
        var numCtx = entry.metadataRange();
        if (numCtx.KW_MIN_INCL() != null) hasMinIncl = true;
        if (numCtx.KW_MIN_EXCL() != null) hasMinExcl = true;
        if (numCtx.KW_MAX_INCL() != null) hasMaxIncl = true;
        if (numCtx.KW_MAX_EXCL() != null) hasMaxExcl = true;
      } else if (entry.metadataFilter() != null) {
        var filterCtx = entry.metadataFilter();
        if (filterCtx.KW_FILTER_INCL() != null) hasFilterIncl = true;
        if (filterCtx.KW_FILTER_EXCL() != null) hasFilterExcl = true;
      }
    }

    if (hasMinIncl && hasMinExcl) {
      diagnosticBag.addError(
          "Constraint violation (" + name + "): #minIncl and #minExcl are mutually exclusive",
          metadataMap.getStart().getStartIndex(),
          metadataMap.getStop().getStopIndex() + 1,
          metadataMap.getStart().getLine(),
          metadataMap.getStart().getCharPositionInLine(),
          null,
          DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
      );
    }
    if (hasMaxIncl && hasMaxExcl) {
      diagnosticBag.addError(
          "Constraint violation (" + name + "): #maxIncl and #maxExcl are mutually exclusive",
          metadataMap.getStart().getStartIndex(),
          metadataMap.getStop().getStopIndex() + 1,
          metadataMap.getStart().getLine(),
          metadataMap.getStart().getCharPositionInLine(),
          null,
          DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
      );
    }
    if (hasFilterIncl && hasFilterExcl) {
      diagnosticBag.addError(
          "Constraint violation (" + name + "): #filterIncl and #filterExcl are mutually exclusive",
          metadataMap.getStart().getStartIndex(),
          metadataMap.getStop().getStopIndex() + 1,
          metadataMap.getStart().getLine(),
          metadataMap.getStart().getCharPositionInLine(),
          null,
          DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
      );
    }

    StvnParser.MetadataFlagContext firstModeCtx = null;
    StvnParser.MetadataFlagContext firstScaleCtx = null;
    for (var entry : metadataMap.metadataEntry()) {
      if (entry.metadataFlag() != null) {
        var flag = entry.metadataFlag();
        if (flag.KW_OFFSET() != null || flag.KW_ZONED() != null || flag.KW_AUDITED() != null) {
          if (firstModeCtx == null) {
            firstModeCtx = flag;
          } else {
            diagnosticBag.addError(
                "Temporal mode facets (#offset, #zoned, #audited) are mutually exclusive",
                flag.getStart().getStartIndex(),
                flag.getStop().getStopIndex() + 1,
                flag.getStart().getLine(),
                flag.getStart().getCharPositionInLine(),
                null,
                DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
            );
          }
        }
        if (flag.KW_SCALE_S() != null || flag.KW_SCALE_MS() != null || flag.KW_SCALE_US() != null || flag.KW_SCALE_NS() != null) {
          if (firstScaleCtx == null) {
            firstScaleCtx = flag;
          } else {
            diagnosticBag.addError(
                "Temporal scale facets (#s, #ms, #us, #ns) are mutually exclusive",
                flag.getStart().getStartIndex(),
                flag.getStop().getStopIndex() + 1,
                flag.getStart().getLine(),
                flag.getStart().getCharPositionInLine(),
                null,
                DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
            );
          }
        }
      }
    }

    if (resolved == null) {
      return;
    }
    var baseType = getPrimitiveBaseType(resolved.node());
    if (baseType == null) {
      return;
    }

    var isIntegerType = isIntegerType(baseType);
    var isFloatType = isFloatType(baseType);
    var isStringType = isStringType(baseType);
    var isTimeEpoch = isTimeEpochType(baseType);
    var isDateTime = isDateTimeType(baseType);
    var isNumeric = isIntegerType || isFloatType || isTimeEpoch;

    for (var entry : metadataMap.metadataEntry()) {
      if (entry.metadataRange() != null) {
        var numCtx = entry.metadataRange();
        var constraintName = "";
        if (numCtx.KW_MIN_INCL() != null) constraintName = "minIncl";
        else if (numCtx.KW_MIN_EXCL() != null) constraintName = "minExcl";
        else if (numCtx.KW_MAX_INCL() != null) constraintName = "maxIncl";
        else if (numCtx.KW_MAX_EXCL() != null) constraintName = "maxExcl";

        var consolidatedConstraints = extractConstraints(metadataMap).merge(resolved.constraints());
        // Enforce discrete half-open intervals: :Int, { #exact } :Float, :TimeEpoch, :DateTime reject #maxIncl and #minExcl
        boolean isDiscrete = isIntegerType || (isFloatType && consolidatedConstraints.exact()) || isTimeEpoch || isDateTime;
        if (isDiscrete &&
            (numCtx.KW_MAX_INCL() != null || numCtx.KW_MIN_EXCL() != null)) {
          var illegalFacet = numCtx.KW_MAX_INCL() != null ? "#maxIncl" : "#minExcl";
          var suggested = numCtx.KW_MAX_INCL() != null ? "#maxExcl" : "#minIncl";
          var typeDesc = isTimeEpoch ? "Discrete temporal type ':TimeEpoch'" :
              (isDateTime ? "Discrete temporal type ':DateTime'" :
              (isIntegerType ? "Discrete type ':Int'" : "Discrete exact float '{ #exact } :Float'"));
          diagnosticBag.addError(
              typeDesc + " prohibits bound '" + illegalFacet + "'; use half-open bound '" + suggested + "'",
              numCtx.getStart().getStartIndex(),
              numCtx.getStop().getStopIndex() + 1,
              numCtx.getStart().getLine(),
              numCtx.getStart().getCharPositionInLine(),
              null,
              DiagnosticBag.ERR_DISCRETE_BOUND_KIND_PROHIBITED
          );
        }

        var mv = numCtx.metadataValue();
        if (mv == null) {
          continue;
        }

        int mvStart = mv.getStart().getStartIndex();
        int mvEnd = mv.getStop().getStopIndex() + 1;
        int mvLine = mv.getStart().getLine();
        int mvCol = mv.getStart().getCharPositionInLine();

        if (isDateTime) {
          if (mv.stringLiteral() == null) {
            diagnosticBag.addError(
                "Constraint violation (" + name + "): #" + constraintName + " for " + baseType + " requires a string literal",
                mvStart, mvEnd, mvLine, mvCol, null,
                DiagnosticBag.ERR_INCOMPATIBLE_TYPE
            );
          }
        } else if (isIntegerType) {
          if (mv.integerLiteral() == null) {
            if (mv.booleanLiteral() != null) {
              diagnosticBag.addError(
                  "Constraint violation (" + name + "): #" + constraintName + " requires an integer literal, found boolean",
                  mvStart, mvEnd, mvLine, mvCol, null,
                  DiagnosticBag.ERR_INCOMPATIBLE_TYPE
              );
            } else if (mv.floatLiteral() != null) {
              diagnosticBag.addError(
                  "Constraint violation (" + name + "): " + constraintName + " for " + baseType + " requires an integer literal",
                  mvStart, mvEnd, mvLine, mvCol, null,
                  DiagnosticBag.ERR_INCOMPATIBLE_TYPE
              );
            } else {
              var foundType = "";
              if (mv.stringLiteral() != null) foundType = "string";
              else foundType = "symbol '" + mv.getText() + "'";
              diagnosticBag.addError(
                  "Constraint violation (" + name + "): #" + constraintName + " requires an integer literal, found " + foundType,
                  mvStart, mvEnd, mvLine, mvCol, null,
                  DiagnosticBag.ERR_INCOMPATIBLE_TYPE
              );
            }
          }
        }

        if (isFloatType) {
          if (mv.floatLiteral() == null) {
            if (mv.booleanLiteral() != null) {
              diagnosticBag.addError(
                  "Constraint violation (" + name + "): #" + constraintName + " requires a float literal, found boolean",
                  mvStart, mvEnd, mvLine, mvCol, null,
                  DiagnosticBag.ERR_INCOMPATIBLE_TYPE
              );
            } else if (mv.integerLiteral() != null) {
              diagnosticBag.addError(
                  "Constraint violation (" + name + "): " + constraintName + " for " + baseType + " requires a float literal",
                  mvStart, mvEnd, mvLine, mvCol, null,
                  DiagnosticBag.ERR_INCOMPATIBLE_TYPE
              );
            } else {
              var foundType = "";
              if (mv.stringLiteral() != null) foundType = "string";
              else foundType = "symbol '" + mv.getText() + "'";
              diagnosticBag.addError(
                  "Constraint violation (" + name + "): #" + constraintName + " requires a float literal, found " + foundType,
                  mvStart, mvEnd, mvLine, mvCol, null,
                  DiagnosticBag.ERR_INCOMPATIBLE_TYPE
              );
            }
          }
        }
      } else if (entry.metadataString() != null) {
        var strCtx = entry.metadataString();
        var mv = strCtx.metadataValue();
        if (mv != null && strCtx.KW_REGEX() != null) {
          if (mv.stringLiteral() == null) {
            diagnosticBag.addError(
                "Constraint violation (" + name + "): #regex requires a string literal",
                mv.getStart().getStartIndex(),
                mv.getStop().getStopIndex() + 1,
                mv.getStart().getLine(),
                mv.getStart().getCharPositionInLine(),
                null,
                DiagnosticBag.ERR_INCOMPATIBLE_TYPE
            );
          } else {
            var rawPattern = extractRawStringValue(mv.stringLiteral().getText());
            try {
              java.util.regex.Pattern.compile(rawPattern);
            } catch (java.util.regex.PatternSyntaxException e) {
              diagnosticBag.addError(
                  "Constraint violation (" + name + "): Invalid regex pattern: " + rawPattern,
                  mv.stringLiteral().getStart().getStartIndex(),
                  mv.stringLiteral().getStop().getStopIndex() + 1,
                  mv.stringLiteral().getStart().getLine(),
                  mv.stringLiteral().getStart().getCharPositionInLine(),
                  e,
                  DiagnosticBag.ERR_INVALID_REGEX
              );
            }
          }
        }
      } else if (entry.metadataBool() != null) {
        var boolCtx = entry.metadataBool();
        var constraintName = boolCtx.KW_EQUATABLE() != null ? "equatable" : "comparable";

        var mv = boolCtx.metadataValue();
        if (mv != null) {
          var text = mv.getText();
          var isValid = mv.booleanLiteral() != null;
          if (!isValid) {
            var foundType = "";
            if (mv.integerLiteral() != null) foundType = "integer";
            else if (mv.floatLiteral() != null) foundType = "float";
            else if (mv.stringLiteral() != null) foundType = "string";
            else foundType = "symbol '" + text + "'";
            diagnosticBag.addError(
                "Constraint violation (" + name + "): #" + constraintName + " requires a boolean literal (#TRUE, #T, #FALSE, or #F), found " + foundType,
                mv.getStart().getStartIndex(),
                mv.getStop().getStopIndex() + 1,
                mv.getStart().getLine(),
                mv.getStart().getCharPositionInLine(),
                null,
                DiagnosticBag.ERR_INCOMPATIBLE_TYPE
            );
          }
        }
      }
    }

    var consolidated = extractConstraints(metadataMap).merge(resolved.constraints());

    // 2. Storage Bit-Width Bounds Validation
    if (consolidated.size().isPresent()) {
      int sz = consolidated.size().get();
      if (":Int".equals(normalizedBase) && (sz < 1 || sz > 1024)) {
        diagnosticBag.addError(
            "Constraint violation (" + name + "): Integer bit-width #size must be between 1 and 1024, found " + sz,
            metadataMap.getStart().getStartIndex(), metadataMap.getStop().getStopIndex() + 1,
            metadataMap.getStart().getLine(), metadataMap.getStart().getCharPositionInLine(),
            null, DiagnosticBag.ERR_CAPACITY_OVERFLOW
        );
      }
      if (":Float".equals(normalizedBase) && sz != 32 && sz != 64) {
        diagnosticBag.addError(
            "Constraint violation (" + name + "): Float bit-width #size must be exactly 32 or 64, found " + sz,
            metadataMap.getStart().getStartIndex(), metadataMap.getStop().getStopIndex() + 1,
            metadataMap.getStart().getLine(), metadataMap.getStart().getCharPositionInLine(),
            null, DiagnosticBag.ERR_INVALID_METADATA_FACET
        );
      }
    }

    // 3. Cardinality Bounds Validation
    if (consolidated.minSize().isPresent() && consolidated.minSize().get() < 0) {
      diagnosticBag.addError(
          "Constraint violation (" + name + "): Minimum cardinality #minSize cannot be negative, found " + consolidated.minSize().get(),
          metadataMap.getStart().getStartIndex(), metadataMap.getStop().getStopIndex() + 1,
          metadataMap.getStart().getLine(), metadataMap.getStart().getCharPositionInLine(),
          null, DiagnosticBag.ERR_INVERTED_RANGE
      );
    }
    if (consolidated.maxSize().isPresent() && consolidated.maxSize().get() < 0) {
      diagnosticBag.addError(
          "Constraint violation (" + name + "): Maximum cardinality #maxSize cannot be negative, found " + consolidated.maxSize().get(),
          metadataMap.getStart().getStartIndex(), metadataMap.getStop().getStopIndex() + 1,
          metadataMap.getStart().getLine(), metadataMap.getStart().getCharPositionInLine(),
          null, DiagnosticBag.ERR_INVERTED_RANGE
      );
    }
    if (consolidated.minSize().isPresent() && consolidated.maxSize().isPresent()) {
      int minSz = consolidated.minSize().get();
      int maxSz = consolidated.maxSize().get();
      if (minSz > maxSz) {
        diagnosticBag.addError(
            "Constraint violation (" + name + "): cardinality range is invalid (#minSize " + minSz + " is greater than #maxSize " + maxSz + ")",
            metadataMap.getStart().getStartIndex(), metadataMap.getStop().getStopIndex() + 1,
            metadataMap.getStart().getLine(), metadataMap.getStart().getCharPositionInLine(),
            null, DiagnosticBag.ERR_INVERTED_RANGE
        );
      }
    }

    // 4. DateTime Interval Bounds & Empty Domain Validation
    if (":DateTime".equals(normalizedBase)) {
      validateDateTimeIntervalBounds(name, metadataMap, consolidated, diagnosticBag);
    }

    if (isIntegerType) {
      var bitWidth = consolidated.size().orElse(32);
      if (consolidated.size().isEmpty()) {
        if (baseType.startsWith(":Int") && baseType.length() > 4 && Character.isDigit(baseType.charAt(4))) {
          bitWidth = Integer.parseInt(baseType.substring(4));
        } else if (baseType.startsWith(":Uint") && baseType.length() > 5 && Character.isDigit(baseType.charAt(5))) {
          bitWidth = Integer.parseInt(baseType.substring(5));
        } else if (isTimeEpochType(baseType)) {
          bitWidth = (consolidated.scale().isPresent() && "ns".equals(consolidated.scale().get())) ? 128 : 64;
        }
      }

      var isUnsigned = consolidated.unsigned() || baseType.startsWith(":Uint");

      var minPhys = java.math.BigInteger.ZERO;
      var maxPhys = java.math.BigInteger.ZERO;
      if (isUnsigned) {
        minPhys = java.math.BigInteger.ZERO;
        maxPhys = java.math.BigInteger.ONE.shiftLeft(bitWidth).subtract(java.math.BigInteger.ONE);
      } else {
        minPhys = java.math.BigInteger.ONE.shiftLeft(bitWidth - 1).negate();
        maxPhys = java.math.BigInteger.ONE.shiftLeft(bitWidth - 1).subtract(java.math.BigInteger.ONE);
      }

      for (var entry : metadataMap.metadataEntry()) {
        if (entry.metadataRange() != null) {
          var numCtx = entry.metadataRange();
          var constraintName = "";
          if (numCtx.KW_MIN_INCL() != null) constraintName = "minIncl";
          else if (numCtx.KW_MIN_EXCL() != null) constraintName = "minExcl";
          else if (numCtx.KW_MAX_INCL() != null) constraintName = "maxIncl";
          else if (numCtx.KW_MAX_EXCL() != null) constraintName = "maxExcl";

          var mv = numCtx.metadataValue();
          if (mv != null && mv.integerLiteral() != null) {
            var valBI = StvnLiteralParser.parseBigInteger(mv.integerLiteral().getText());
            var maxBound = constraintName.equals("maxExcl") ? maxPhys.add(java.math.BigInteger.ONE) : maxPhys;
            var minBound = constraintName.equals("minExcl") ? minPhys.subtract(java.math.BigInteger.ONE) : minPhys;
            if (valBI.compareTo(minBound) < 0 || valBI.compareTo(maxBound) > 0) {
              diagnosticBag.addError(
                  "Constraint violation (" + name + "): #" + constraintName + " value " + valBI + " is out of bounds for physical capacity of " + baseType + " (" + minPhys + " to " + maxPhys + ")",
                  mv.integerLiteral().getStart().getStartIndex(),
                  mv.integerLiteral().getStop().getStopIndex() + 1,
                  mv.integerLiteral().getStart().getLine(),
                  mv.integerLiteral().getStart().getCharPositionInLine(),
                  null,
                  DiagnosticBag.ERR_CAPACITY_OVERFLOW
              );
            }
          }
        }
      }

      var minEff = consolidated.minIncl().isPresent()
          ? consolidated.minIncl().get().toBigIntegerExact()
          : (consolidated.minExcl().isPresent()
              ? consolidated.minExcl().get().toBigIntegerExact().add(java.math.BigInteger.ONE)
              : null);

      var maxEff = consolidated.maxIncl().isPresent()
          ? consolidated.maxIncl().get().toBigIntegerExact()
          : (consolidated.maxExcl().isPresent()
              ? consolidated.maxExcl().get().toBigIntegerExact().subtract(java.math.BigInteger.ONE)
              : null);

      if (minEff != null && maxEff != null) {
        if (minEff.compareTo(maxEff) > 0) {
          diagnosticBag.addError(
              "Constraint violation (" + name + "): effective range is invalid (minimum " + minEff + " is greater than maximum " + maxEff + ")",
              metadataMap.getStart().getStartIndex(),
              metadataMap.getStop().getStopIndex() + 1,
              metadataMap.getStart().getLine(),
              metadataMap.getStart().getCharPositionInLine(),
              null,
              DiagnosticBag.ERR_INVERTED_RANGE
          );
        }
      }
    }

    if (isFloatType) {
      var isExact = consolidated.exact() || baseType.equals(":FloatExact");
      if (!isExact) {
        var minPhys = BigDecimal.ZERO;
        var maxPhys = BigDecimal.ZERO;
        var bitWidth = consolidated.size().orElse(64);
        if (bitWidth == 32 || baseType.equals(":Float32")) {
          minPhys = BigDecimal.valueOf(-Float.MAX_VALUE);
          maxPhys = BigDecimal.valueOf(Float.MAX_VALUE);
        } else {
          minPhys = BigDecimal.valueOf(-Double.MAX_VALUE);
          maxPhys = BigDecimal.valueOf(Double.MAX_VALUE);
        }

        for (var entry : metadataMap.metadataEntry()) {
          if (entry.metadataRange() != null) {
            var numCtx = entry.metadataRange();
            var constraintName = "";
            if (numCtx.KW_MIN_INCL() != null) constraintName = "minIncl";
            else if (numCtx.KW_MIN_EXCL() != null) constraintName = "minExcl";
            else if (numCtx.KW_MAX_INCL() != null) constraintName = "maxIncl";
            else if (numCtx.KW_MAX_EXCL() != null) constraintName = "maxExcl";

            var mv = numCtx.metadataValue();
            if (mv != null && mv.floatLiteral() != null) {
              var valBD = new java.math.BigDecimal(mv.floatLiteral().getText());
              if (valBD.compareTo(minPhys) < 0 || valBD.compareTo(maxPhys) > 0) {
                diagnosticBag.addError(
                    "Constraint violation (" + name + "): #" + constraintName + " value " + valBD + " is out of bounds for physical capacity of " + baseType + " (" + minPhys + " to " + maxPhys + ")",
                    mv.floatLiteral().getStart().getStartIndex(),
                    mv.floatLiteral().getStop().getStopIndex() + 1,
                    mv.floatLiteral().getStart().getLine(),
                    mv.floatLiteral().getStart().getCharPositionInLine(),
                    null,
                    DiagnosticBag.ERR_CAPACITY_OVERFLOW
                );
              }
            }
          }
        }
      }

      var minVal = consolidated.minIncl().isPresent()
          ? consolidated.minIncl().get()
          : (consolidated.minExcl().isPresent() ? consolidated.minExcl().get() : null);

      var maxVal = consolidated.maxIncl().isPresent()
          ? consolidated.maxIncl().get()
          : (consolidated.maxExcl().isPresent() ? consolidated.maxExcl().get() : null);

      if (minVal != null && maxVal != null) {
        var strictComparison = consolidated.minExcl().isPresent() || consolidated.maxExcl().isPresent();
        if (strictComparison) {
          if (minVal.compareTo(maxVal) >= 0) {
            diagnosticBag.addError(
                "Constraint violation (" + name + "): effective range is invalid (minimum " + minVal + " must be strictly less than maximum " + maxVal + ")",
                metadataMap.getStart().getStartIndex(),
                metadataMap.getStop().getStopIndex() + 1,
                metadataMap.getStart().getLine(),
                metadataMap.getStart().getCharPositionInLine(),
                null,
                DiagnosticBag.ERR_INVERTED_RANGE
            );
          }
        } else {
          if (minVal.compareTo(maxVal) > 0) {
            diagnosticBag.addError(
                "Constraint violation (" + name + "): effective range is invalid (minimum " + minVal + " is greater than maximum " + maxVal + ")",
                metadataMap.getStart().getStartIndex(),
                metadataMap.getStop().getStopIndex() + 1,
                metadataMap.getStart().getLine(),
                metadataMap.getStart().getCharPositionInLine(),
                null,
                DiagnosticBag.ERR_INVERTED_RANGE
            );
          }
        }
      }
    }
  }

  private static void validateTemporalTypeConstraints(
      @Nullable SchemaTypeContext schemaType,
      @Nullable MetadataMapContext metadataMap,
      @Nullable ResolvedSchema resolved,
      DiagnosticBag diagnosticBag) {
    if (schemaType == null) return;
    String typeText = schemaType.getText();
    boolean isEpoch = typeText.equals(":TimeEpoch");
    boolean isDateTime = typeText.equals(":DateTime");
    if (!isEpoch && !isDateTime) return;

    var localConstraints = extractConstraints(metadataMap);
    var consolidated = resolved != null ? localConstraints.merge(resolved.constraints()) : localConstraints;

    int start = schemaType.getStart().getStartIndex();
    int end = schemaType.getStop().getStopIndex() + 1;
    int line = schemaType.getStart().getLine();
    int col = schemaType.getStart().getCharPositionInLine();

    if (isEpoch) {
      if (!consolidated.scale().isPresent()) {
        diagnosticBag.addError(
            "Temporal type ':TimeEpoch' requires a scale facet: '#s', '#ms', '#us', or '#ns'",
            start, end, line, col, null,
            DiagnosticBag.ERR_MISSING_TEMPORAL_FACET
        );
      } else {
        String u = consolidated.scale().get();
        if (!u.equals("#s") && !u.equals("#ms") && !u.equals("#us") && !u.equals("#ns") && !u.equals("s") && !u.equals("ms") && !u.equals("us") && !u.equals("ns")) {
          diagnosticBag.addError(
              "Invalid scale facet '" + u + "' for ':TimeEpoch'; permitted scales: [#s, #ms, #us, #ns]",
              start, end, line, col, null,
              DiagnosticBag.ERR_INVALID_METADATA_FACET
          );
        }
      }
    } else if (isDateTime) {
      int modes = (consolidated.offset() ? 1 : 0) + (consolidated.zoned() ? 1 : 0) + (consolidated.audited() ? 1 : 0);
      if (modes == 0) {
        diagnosticBag.addError(
            "Temporal type ':DateTime' requires exactly one mode facet: '#offset', '#zoned', or '#audited'",
            start, end, line, col, null,
            DiagnosticBag.ERR_MISSING_TEMPORAL_FACET
        );
      } else if (modes > 1) {
        diagnosticBag.addError(
            "Constraint violation: '#offset', '#zoned', and '#audited' are mutually exclusive",
            metadataMap != null ? metadataMap.getStart().getStartIndex() : start,
            metadataMap != null ? metadataMap.getStop().getStopIndex() + 1 : end,
            line, col, null,
            DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
        );
      }
    }
  }

  /**
   * Searches the document's type definition entries (and the STVN standard library prelude)
   * to find the alias keyword matching the given schema type instance.
   *
   * @param doc        the active document context
   * @param schemaType the exact schema type AST context to look up
   * @return an {@link Optional} containing the nominal type name/alias if found, otherwise empty
   */
  public static Optional<String> findAliasNameForSchemaType(@Nullable StvnDocumentContext doc, SchemaTypeContext schemaType) {
    if (doc == null) {
      return Optional.empty();
    }
    if (doc.documentBody() != null && doc.documentBody().defsEntry() != null) {
      for (var de : doc.documentBody().defsEntry().defsElement()) {
        if (de.typeDefinition() != null && de.typeDefinition().schemaType() == schemaType) {
          return Optional.of(de.typeDefinition().typeDefTarget().getText());
        } else if (de.packageEnclosure() != null) {
          var pkgPath = de.packageEnclosure().packagePath().getText();
          if (de.packageEnclosure().packageElement() != null) {
            for (var pe : de.packageEnclosure().packageElement()) {
              if (pe.typeDefinition() != null && pe.typeDefinition().schemaType() == schemaType) {
                String localName = pe.typeDefinition().typeDefTarget().getText().substring(1);
                return Optional.of(pkgPath + "/" + localName);
              }
            }
          }
        }
      }
    }
    var preludeDoc = StvnPrelude.getPreludeDocument();
    if (preludeDoc != null && preludeDoc.documentBody() != null && preludeDoc.documentBody().defsEntry() != null) {
      for (var de : preludeDoc.documentBody().defsEntry().defsElement()) {
        if (de.typeDefinition() != null && de.typeDefinition().schemaType() == schemaType) {
          return Optional.of(de.typeDefinition().typeDefTarget().getText());
        }
      }
    }
    return Optional.empty();
  }

  /**
   * Validates that an integer literal assigned to a typed constant fits within the declared bit-width bounds.
   *
   * @param constName the constant name
   * @param resolved the resolved schema of the constant
   * @param valueCtx the AST value context containing the integer literal
   * @param diagnosticBag the accumulator bag for recording semantic diagnostics
   */
  public static void validateConstantBitWidthCapacity(
      String constName,
      ResolvedSchema resolved,
      StvnParser.ValueContext valueCtx,
      DiagnosticBag diagnosticBag
  ) {
    validateConstantBitWidthCapacity(constName, resolved, resolved.constraints(), valueCtx, diagnosticBag);
  }

  /**
   * Validates that an integer literal assigned to a typed constant fits within the declared bit-width bounds.
   *
   * @param constName the constant name
   * @param resolved the resolved schema of the constant
   * @param effectiveConstraints the effective merged constraints
   * @param valueCtx the AST value context containing the integer literal
   * @param diagnosticBag the accumulator bag for recording semantic diagnostics
   */
  public static void validateConstantBitWidthCapacity(
      String constName,
      ResolvedSchema resolved,
      StvnConstraints effectiveConstraints,
      StvnParser.ValueContext valueCtx,
      DiagnosticBag diagnosticBag
  ) {
    if (valueCtx.integerLiteral() == null || resolved.node() == null) {
      return;
    }
    String baseType = getPrimitiveBaseType(resolved.node());
    if (baseType == null) return;

    boolean isUnsigned = effectiveConstraints.unsigned() || baseType.startsWith(":Uint");
    boolean isSigned = (baseType.startsWith(":Int") || baseType.equals(":Int")) && !isUnsigned;
    if (!isUnsigned && !isSigned) return;

    int bitWidth = effectiveConstraints.size().orElse(32);
    if (effectiveConstraints.size().isEmpty()) {
      String suffix = isUnsigned && baseType.startsWith(":Uint") ? baseType.substring(5) : (baseType.startsWith(":Int") ? baseType.substring(4) : "");
      if (!suffix.isEmpty() && suffix.matches("\\d+")) {
        bitWidth = Integer.parseInt(suffix);
      }
    }

    java.math.BigInteger val = StvnLiteralParser.parseBigInteger(valueCtx.integerLiteral().getText());
    java.math.BigInteger min = isUnsigned
        ? java.math.BigInteger.ZERO
        : java.math.BigInteger.ONE.shiftLeft(bitWidth - 1).negate();
    java.math.BigInteger max = isUnsigned
        ? java.math.BigInteger.ONE.shiftLeft(bitWidth).subtract(java.math.BigInteger.ONE)
        : java.math.BigInteger.ONE.shiftLeft(bitWidth - 1).subtract(java.math.BigInteger.ONE);

    if (val.compareTo(min) < 0 || val.compareTo(max) > 0) {
      int line = valueCtx.getStart().getLine();
      int col = valueCtx.getStart().getCharPositionInLine();
      int start = valueCtx.getStart().getStartIndex();
      int end = valueCtx.getStop().getStopIndex() + 1;
      String displayType = resolved.aliasName().orElse(baseType);
      diagnosticBag.addError(
          "Integer literal " + val + " out of range for " + displayType + " [" + min + ", " + max + "]",
          start, end, line, col, null, DiagnosticBag.ERR_INTEGER_OVERFLOW
      );
    }
  }

  /**
   * Checks whether a type name matches a built-in reserved fundamental type keyword.
   *
   * @param name the type name to check
   * @return {@code true} if the name is a reserved fundamental type keyword, {@code false} otherwise
   */
  public static boolean isReservedFundamentalType(String name) {
    if (name.equals(":Boolean") || name.equals(":Int") || name.equals(":Float") || name.equals(":String") ||
        name.equals(":Tuple") || name.equals(":Enum") || name.equals(":Option") ||
        name.equals(":Either") || name.equals(":Union") || name.equals(":MapEntry") ||
        name.equals(":Seq") || name.equals(":SeqNonEmpty") || name.equals(":Set") ||
        name.equals(":SetNonEmpty") || name.equals(":Map") || name.equals(":MapNonEmpty") ||
        name.equals(":defs") || name.equals(":type") || name.equals(":body") || name.equals(":include") ||
        name.equals(":package") || name.equals(":use")) {
      return true;
    }
    return false;
  }

  /**
   * Validates map key uniqueness during traversal/unification.
   *
   * @param key        the StvnValue key
   * @param seenKeys   the set of already parsed keys
   * @param keyCtx     the AST parser context of the key
   * @throws StvnCollectionCollisionException if a duplicate key is detected
   */
  public static void validateMapKeyUniqueness(
      org.stvnadore.core.ir.StvnValue key,
      Set<org.stvnadore.core.ir.StvnValue> seenKeys,
      ValueContext keyCtx) {
    if (!seenKeys.add(key)) {
      int startOffset = keyCtx.getStart().getStartIndex();
      int endOffset = keyCtx.getStop().getStopIndex() + 1;
      throw new StvnCollectionCollisionException(
          "Duplicate map key detected", startOffset, endOffset);
    }
  }

  /**
   * Validates map value uniqueness during traversal/unification for invertible maps.
   *
   * @param val        the StvnValue value
   * @param seenValues the set of already parsed values
   * @param valCtx     the AST parser context of the value
   * @throws StvnCollectionCollisionException if a duplicate value is detected
   */
  public static void validateInvertibleMapValueUniqueness(
      org.stvnadore.core.ir.StvnValue val,
      Set<org.stvnadore.core.ir.StvnValue> seenValues,
      ValueContext valCtx) {
    if (!seenValues.add(val)) {
      int startOffset = valCtx.getStart().getStartIndex();
      int endOffset = valCtx.getStop().getStopIndex() + 1;
      throw new StvnCollectionCollisionException(
          "Duplicate inverted map value detected", startOffset, endOffset);
    }
  }

  /**
   * Validates set element uniqueness during traversal/unification.
   *
   * @param element      the StvnValue element
   * @param seenElements the set of already parsed elements
   * @param elementCtx   the AST parser context of the element
   * @throws StvnCollectionCollisionException if a duplicate element is detected
   */
  public static void validateSetElementUniqueness(
      StvnValue element,
      Set<StvnValue> seenElements,
      ValueContext elementCtx) {
    if (!seenElements.add(element)) {
      int startOffset = elementCtx.getStart().getStartIndex();
      int endOffset = elementCtx.getStop().getStopIndex() + 1;
      throw new StvnCollectionCollisionException(
          "Duplicate set element detected", startOffset, endOffset);
    }
  }

  /**
   * Translates a legacy compound type keyword into an actionable migration message.
   *
   * @param kw the type keyword to evaluate
   * @return the deprecation error message, or {@code null} if the keyword does not match a legacy compound type
   */
  public static @Nullable String getLegacyTypeDeprecationMessage(@Nullable String kw) {
    if (kw == null) {
      return null;
    }
    String unqualified = kw.contains("/") ? (":" + kw.substring(kw.lastIndexOf('/') + 1)) : kw;
    if (unqualified.matches("^:Int[0-9]+$")) {
      String width = unqualified.substring(4);
      return "Compound integer keyword '" + unqualified + "' is deprecated in 2.0.0; use '{ #size " + width + " } :Int'";
    }
    if (unqualified.matches("^:Uint[0-9]*$")) {
      String width = unqualified.substring(5);
      String sizeClause = width.isEmpty() ? "" : " #size " + width;
      return "Compound unsigned integer keyword '" + unqualified + "' is deprecated in 2.0.0; use '{ #unsigned" + sizeClause + " } :Int'";
    }
    if (unqualified.matches("^:Float(32|64)$")) {
      String width = unqualified.substring(6);
      return "Compound float keyword '" + unqualified + "' is deprecated in 2.0.0; use '{ #size " + width + " } :Float'";
    }
    if (unqualified.equals(":FloatExact")) {
      return "Compound float keyword ':FloatExact' is deprecated in 2.0.0; use '{ #exact } :Float'";
    }
    if (unqualified.matches("^:StringFixed[0-9]+$")) {
      String len = unqualified.substring(12);
      return "Compound string keyword '" + unqualified + "' is deprecated in 2.0.0; use '{ #minSize " + len + " #maxSize " + len + " } :String'";
    }
    if (unqualified.equals(":StringNonEmpty")) {
      return "Compound string keyword ':StringNonEmpty' is deprecated in 2.0.0; use '{ #minSize 1 } :String'";
    }
    if (unqualified.equals(":SeqNonEmpty")) {
      return "Compound collection keyword ':SeqNonEmpty' is deprecated in 2.0.0; use '{ #minSize 1 } :Seq'";
    }
    if (unqualified.equals(":SetNonEmpty")) {
      return "Compound collection keyword ':SetNonEmpty' is deprecated in 2.0.0; use '{ #minSize 1 } :Set'";
    }
    if (unqualified.equals(":MapNonEmpty")) {
      return "Compound collection keyword ':MapNonEmpty' is deprecated in 2.0.0; use '{ #minSize 1 } :Map'";
    }
    if (unqualified.equals(":MapInv")) {
      return "Compound collection keyword ':MapInv' is deprecated in 2.0.0; use '{ #invertible } :Map'";
    }
    if (unqualified.equals(":MapInvNonEmpty")) {
      return "Compound collection keyword ':MapInvNonEmpty' is deprecated in 2.0.0; use '{ #invertible #minSize 1 } :Map'";
    }
    if (unqualified.matches("^:TimeEpoch[A-Za-z0-9_]+$")) {
      return "Legacy temporal epoch keyword is deprecated in 2.0.0; use ':TimeEpoch' with mandatory facet '{ #s }', '{ #ms }', '{ #us }', or '{ #ns }'";
    }
    if (unqualified.equals(":DateTimeOffset") || unqualified.equals(":DateTimeZoned") || unqualified.equals(":DateTimeAudited")) {
      return "Legacy datetime keyword is deprecated in 2.0.0; use ':DateTime' with mode facet '{ #offset }', '{ #zoned }', or '{ #audited }'";
    }
    return null;
  }
}
