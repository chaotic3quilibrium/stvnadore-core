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
    if (Files.exists(includePath)) {
      return includePath;
    }
    Path validPath = Paths.get("shared-fixtures/valid-syntax").resolve(includePathStr);
    if (Files.exists(validPath)) {
      return validPath;
    }
    Path invalidPath = Paths.get("shared-fixtures/invalid-syntax").resolve(includePathStr);
    if (Files.exists(invalidPath)) {
      return invalidPath;
    }
    return includePath;
  }

  private record NamespaceClaim<T extends ParserRuleContext>(
      String identifier,
      T defNode,
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

    if (doc.documentBody() != null && doc.documentBody().defsEntry() != null) {
      var defsEntry = doc.documentBody().defsEntry();
      var currentDocPath = documentPaths.get(doc);

      // Iterate over the elements of defsEntry in sorted source order
      var elements = new ArrayList<ParserRuleContext>();
      if (defsEntry.typeDefinition() != null) {
        elements.addAll(defsEntry.typeDefinition());
      }
      if (defsEntry.constantDefinition() != null) {
        elements.addAll(defsEntry.constantDefinition());
      }
      if (defsEntry.includeStmt() != null) {
        elements.addAll(defsEntry.includeStmt());
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
          var typeName = typeDef.typeKeyword().getText();
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
          if (currentDocPath != null && currentDocPath.endsWith(".stvn_inclf")) {
            diagnosticBag.addError(
                "Leaf module (.stvn_inclf) cannot contain include statements: " + currentDocPath,
                includeStmt.getStart().getStartIndex(),
                includeStmt.getStop().getStopIndex() + 1,
                includeStmt.getStart().getLine(),
                includeStmt.getStart().getCharPositionInLine(),
                null,
                DiagnosticBag.ERR_MODULE_IMPORT
            );
          }
          if (includeStmt.includeElement() != null) {
            for (var element : includeStmt.includeElement()) {
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
                var cycleStartIndex = activePaths.indexOf(resolvedPathStr);
                var rawPathsSlice = new ArrayList<String>();
                var canonicalPathsSlice = new ArrayList<String>();

                for (var i = cycleStartIndex + 1; i < activePaths.size(); i++) {
                  rawPathsSlice.add(activeRawPaths.get(i));
                  canonicalPathsSlice.add(activePaths.get(i));
                }
                rawPathsSlice.add(pathVal);
                canonicalPathsSlice.add(resolvedPathStr);

                var names = new ArrayList<String>();
                names.add(Paths.get(activePaths.get(cycleStartIndex)).getFileName().toString());
                for (var p : canonicalPathsSlice) {
                  names.add(Paths.get(p).getFileName().toString());
                }
                var trace = String.join(" -> ", names);

                diagnosticBag.addError(
                    "Cycle detected: " + trace,
                    element.getStart().getStartIndex(),
                    element.getStop().getStopIndex() + 1,
                    element.getStart().getLine(),
                    element.getStart().getCharPositionInLine(),
                    new CyclicDependencyException("Cycle detected: " + trace, rawPathsSlice, canonicalPathsSlice),
                    DiagnosticBag.ERR_CYCLIC_MODULE
                );
                continue;
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

              var nextActive = new ArrayList<String>(activePaths);
              nextActive.add(resolvedPathStr);
              var nextActiveRaw = new ArrayList<String>(activeRawPaths);
              nextActiveRaw.add(pathVal);
              var importedDefs = resolveDefinitionsAndValidate(importedDoc, nextActive, nextActiveRaw, diagnosticBag);
              var importedConstDefs = documentConstantDefinitionsCache.getOrDefault(importedDoc, Collections.emptyMap());
              validateDocumentConstraints(importedDoc, diagnosticBag);

              for (var entry : importedDefs.entrySet()) {
                var originalName = entry.getKey();
                var defSource = entry.getValue();

                var importedName = originalName;
                var isRenamed = false;
                if (element.includeAliasBlock() != null && element.includeAliasBlock().includeMapAlias() != null) {
                  for (var alias : element.includeAliasBlock().includeMapAlias()) {
                    if (alias.typeKeyword(0).getText().equals(originalName)) {
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
                  accumulator.computeIfAbsent(originalName, k -> new ArrayList<>())
                      .add(new NamespaceClaim<>(originalName, defSource.defNode(), resolvedPath.getFileName().toString(), ClaimType.RAW_IMPORT));
                }
              }

              for (var entry : importedConstDefs.entrySet()) {
                var originalName = entry.getKey();
                var constSource = entry.getValue();
                constAccumulator.computeIfAbsent(originalName, k -> new ArrayList<>())
                    .add(new NamespaceClaim<>(originalName, constSource.defNode(), resolvedPath.getFileName().toString(), ClaimType.RAW_IMPORT));
              }
            }
          }
        }
      }
    }

    var localDefs = new LinkedHashMap<String, DefSource>();
    applyEvictionCascade(accumulator, localDefs, collisions, DefSource::new);

    var localConstDefs = new LinkedHashMap<String, ConstantDefSource>();
    applyEvictionCascade(constAccumulator, localConstDefs, collisions, ConstantDefSource::new);
    documentConstantDefinitionsCache.put(doc, localConstDefs);

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

        for (var c : claims) {
          if (c.type() == ClaimType.RAW_IMPORT) {
            rawClaims.add(c);
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
      ":TimeEpochS",
      ":TimeEpochMs",
      ":TimeEpochNs");

  private static boolean isTimeEpochType(String type) {
    return TYPES_TIME.contains(type);
  }

  private static final Set<String> TYPES_DATE_TIME = Set.of(
      ":DateTime",
      ":DateTimeOffset",
      ":DateTimeZoned",
      ":DateTimeAudited");

  /**
   * Tests whether a type name represents a temporal date-time scalar.
   *
   * @param type the type name keyword token (e.g. {@code ":DateTimeOffset"})
   * @return true if the type is one of the tripartite date-time types
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
      java.util.List<String> explicitOverrides
  ) {
    /**
     * Canonical constructor validating that all optional parameters are non-null.
     *
     * @param minIncl           optional inclusive minimum boundary
     * @param minExcl           optional exclusive minimum boundary
     * @param maxIncl           optional inclusive maximum boundary
     * @param maxExcl           optional exclusive maximum boundary
     * @param regex             optional regex pattern
     * @param preserveIndent    if true, preserves indentation
     * @param equatable         optional equatable override
     * @param comparable        optional comparable override
     * @param explicitOverrides list of explicit traits overridden
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
    }

    /**
     * Returns an empty StvnConstraints instance with no constraints applied.
     *
     * @return the empty constraints instance
     */
    public static StvnConstraints empty() {
      return new StvnConstraints(
          Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
          Optional.empty(), false, Optional.empty(), Optional.empty(), java.util.List.of()
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

      return new StvnConstraints(
          resMinIncl, resMinExcl, resMaxIncl, resMaxExcl,
          resRegex,
          resPreserveIndent,
          resEquatable,
          resComparable,
          java.util.List.copyOf(mergedOverrides)
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
   */
  public record ResolvedSchema(
      SchemaTypeContext node,
      StvnConstraints constraints,
      Optional<String> aliasName,
      Optional<Integer> implicitUnionTag,
      Optional<SumTypeContext> sumTypeNode,
      Optional<ResolvedSchema> underlyingSchema,
      Optional<StvnConstraints> localConstraints,
      boolean isPoisonedSentinel
  ) {
    /**
     * Canonical constructor validating that all optional parameters are non-null.
     *
     * @param node              the AST schema type context node
     * @param constraints       the consolidated constraints for this schema
     * @param aliasName         the nominal alias name of the type
     * @param implicitUnionTag  the implicit variant index if resolved as an untagged union branch
     * @param sumTypeNode       the parsing context of the sum type
     * @param underlyingSchema  the underlying schema
     * @param localConstraints  the constraints defined locally on this specific schema node
     * @param isPoisonedSentinel true if this schema represents a poisoned error sentinel
     */
    public ResolvedSchema {
      java.util.Objects.requireNonNull(node);
      java.util.Objects.requireNonNull(aliasName);
      java.util.Objects.requireNonNull(implicitUnionTag);
      java.util.Objects.requireNonNull(sumTypeNode);
      java.util.Objects.requireNonNull(underlyingSchema);
      java.util.Objects.requireNonNull(localConstraints);
    }

    /**
     * Backward-compatible 7-arg constructor defaulting isPoisonedSentinel to false.
     *
     * @param node              the AST schema type context node
     * @param constraints       the consolidated constraints for this schema
     * @param aliasName         the nominal alias name of the type
     * @param implicitUnionTag  the implicit variant index if resolved as an untagged union branch
     * @param sumTypeNode       the parsing context of the sum type
     * @param underlyingSchema  the underlying schema
     * @param localConstraints  the constraints defined locally on this specific schema node
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
      this(node, constraints, aliasName, implicitUnionTag, sumTypeNode, underlyingSchema, localConstraints, false);
    }

    /**
     * Convenience constructor to build a ResolvedSchema with default empty sum type context and underlying schemas.
     *
     * @param node        the AST schema type context node
     * @param constraints the consolidated constraints for this schema
     * @param aliasName   the nominal alias name of the type
     */
    public ResolvedSchema(SchemaTypeContext node, StvnConstraints constraints, Optional<String> aliasName) {
      this(node, constraints, aliasName, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false);
    }

    /**
     * Convenience constructor to build a ResolvedSchema specifying implicit union tag and sum type node context.
     *
     * @param node             the AST schema type context node
     * @param constraints      the consolidated constraints for this schema
     * @param aliasName        the nominal alias name of the type
     * @param implicitUnionTag the implicit variant index if resolved as an untagged union branch
     * @param sumTypeNode      the parsing context of the sum type
     */
    public ResolvedSchema(SchemaTypeContext node, StvnConstraints constraints, Optional<String> aliasName, Optional<Integer> implicitUnionTag, Optional<SumTypeContext> sumTypeNode) {
      this(node, constraints, aliasName, implicitUnionTag, sumTypeNode, Optional.empty(), Optional.empty(), false);
    }

    /**
     * Factory for creating a poisoned sentinel schema for unresolved or structurally broken types.
     *
     * @param aliasName the name of the poisoned type
     * @param node      the schema AST context node
     * @return a poisoned ResolvedSchema sentinel
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
          true
      );
    }

    /**
     * Compares this resolved schema with another object for equivalence based on constraints and alias name.
     *
     * @param o the other object to compare against
     * @return {@code true} if the objects are equivalent, otherwise {@code false}
     */
    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof ResolvedSchema that)) return false;
      return java.util.Objects.equals(constraints, that.constraints) &&
          java.util.Objects.equals(aliasName, that.aliasName);
    }

    /**
     * Computes the hash code value for this resolved schema based on constraints and alias name.
     *
     * @return the computed hash code
     */
    @Override
    public int hashCode() {
      return java.util.Objects.hash(constraints, aliasName);
    }

    /**
     * Returns a string representation of the resolved schema configuration.
     *
     * @return the string representation
     */
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

    for (StvnParser.MetadataEntryContext entry : metadataMap.metadataEntry()) {
      // PATHWAY B: Defensive checks to route metadata entry variants, as only one will be non-null at runtime depending on syntax matching
      if (entry.metadataNum() != null) {
        var numCtx = entry.metadataNum();
        BigDecimal val = null;
        if (numCtx.metadataValue() != null) {
          if (numCtx.metadataValue().integerLiteral() != null)
            val = new BigDecimal(numCtx.metadataValue().integerLiteral().getText());
          else if (numCtx.metadataValue().floatLiteral() != null)
            val = new BigDecimal(numCtx.metadataValue().floatLiteral().getText());
        }

        if (val != null) {
          if (numCtx.KW_MIN_INCL() != null) minIncl = val;
          else if (numCtx.KW_MIN_EXCL() != null) minExcl = val;
          else if (numCtx.KW_MAX_INCL() != null) maxIncl = val;
          else if (numCtx.KW_MAX_EXCL() != null) maxExcl = val;
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
            if (boolCtx.KW_PRESERVE_INDENT() != null) {
              preserveIndent = isTrue;
              explicitOverrides.add("preserveIndent");
            } else if (boolCtx.KW_EQUATABLE() != null) {
              equatable = isTrue;
              explicitOverrides.add("equatable");
            } else if (boolCtx.KW_COMPARABLE() != null) {
              comparable = isTrue;
              explicitOverrides.add("comparable");
            }
          }
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
        java.util.List.copyOf(explicitOverrides)
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
    return findAllDefinitions(doc, keyword).stream().findFirst().map(DefSource::defNode);
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
    return findAllConstantDefinitions(doc, keyword).stream().findFirst().map(ConstantDefSource::defNode);
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
      var kw = schemaNode.typeKeyword().getText();
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

      var typeDefOpt = findTypeDefinition(doc, kw);
      if (typeDefOpt.isPresent()) {
        var typeDef = typeDefOpt.get();
        var meta = extractConstraints(typeDef.metadataMap());
        var innerRes = resolvePrimitiveSchema(doc, typeDef.schemaType(), nextVisited, false);

        return innerRes
            .map(resolvedSchema ->
                applyDefaults(new ResolvedSchema(resolvedSchema.node(), meta.merge(resolvedSchema.constraints()), Optional.of(kw), Optional.empty(), Optional.empty(), Optional.of(resolvedSchema), Optional.of(meta), resolvedSchema.isPoisonedSentinel())))
            .or(() ->
                Optional.of(applyDefaults(new ResolvedSchema(schemaNode, meta, Optional.of(kw), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(meta), false))))
            .map(StvnTypeResolver::validateResolvedSchema);
      } else {
        markTypePoisoned(doc, kw);
        throw new MalformedSchemaException("Undefined type: " + kw);
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
        var cleanConstraints = new StvnConstraints(
            constraints.minIncl(), constraints.minExcl(), constraints.maxIncl(), constraints.maxExcl(),
            Optional.empty(), constraints.preserveIndent(), constraints.equatable(), constraints.comparable(),
            constraints.explicitOverrides()
        );
        return new ResolvedSchema(rs.node(), cleanConstraints, rs.aliasName(), rs.implicitUnionTag(), rs.sumTypeNode(), rs.underlyingSchema(), rs.localConstraints(), rs.isPoisonedSentinel());
      }
      try {
        java.util.regex.Pattern.compile(regexStr);
      } catch (java.util.regex.PatternSyntaxException e) {
        var cleanConstraints = new StvnConstraints(
            constraints.minIncl(), constraints.minExcl(), constraints.maxIncl(), constraints.maxExcl(),
            Optional.empty(), constraints.preserveIndent(), constraints.equatable(), constraints.comparable(),
            constraints.explicitOverrides()
        );
        return new ResolvedSchema(rs.node(), cleanConstraints, rs.aliasName(), rs.implicitUnionTag(), rs.sumTypeNode(), rs.underlyingSchema(), rs.localConstraints(), rs.isPoisonedSentinel());
      }
    }
    if (constraints.preserveIndent() || constraints.explicitOverrides().contains("preserveIndent")) {
      var ultimateBase = getUltimateBaseType(rs);
      if (ultimateBase == null || !isStringType(ultimateBase)) {
        var cleanOverrides = new ArrayList<>(constraints.explicitOverrides());
        cleanOverrides.remove("preserveIndent");
        var cleanConstraints = new StvnConstraints(
            constraints.minIncl(), constraints.minExcl(), constraints.maxIncl(), constraints.maxExcl(),
            constraints.regex(), false, constraints.equatable(), constraints.comparable(),
            cleanOverrides
        );
        return new ResolvedSchema(rs.node(), cleanConstraints, rs.aliasName(), rs.implicitUnionTag(), rs.sumTypeNode(), rs.underlyingSchema(), rs.localConstraints(), rs.isPoisonedSentinel());
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
      var updatedC = new StvnConstraints(
          rs.constraints().minIncl(), rs.constraints().minExcl(),
          rs.constraints().maxIncl(), rs.constraints().maxExcl(),
          rs.constraints().regex(), rs.constraints().preserveIndent(),
          equatable, comparable,
          rs.constraints().explicitOverrides()
      );
      return new ResolvedSchema(rs.node(), updatedC, rs.aliasName(), rs.implicitUnionTag(), rs.sumTypeNode(), rs.underlyingSchema(), rs.localConstraints());
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

    var updatedC = new StvnConstraints(
        constraints.minIncl(), constraints.minExcl(),
        constraints.maxIncl(), constraints.maxExcl(),
        constraints.regex(), constraints.preserveIndent(),
        equatable, comparable, overrides
    );

    return new ResolvedSchema(parent.node(), updatedC, parent.aliasName(), parent.implicitUnionTag(), parent.sumTypeNode(), parent.underlyingSchema(), parent.localConstraints());
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
        if (ctor.collectionType().COLL_SEQ_NON_EMPTY() != null) return ":SeqNonEmpty";
        if (ctor.collectionType().COLL_SET() != null) return ":Set";
        if (ctor.collectionType().COLL_SET_NON_EMPTY() != null) return ":SetNonEmpty";
        if (ctor.collectionType().COLL_MAP() != null) return ":Map";
        if (ctor.collectionType().COLL_MAP_NON_EMPTY() != null) return ":MapNonEmpty";
        if (ctor.collectionType().COLL_MAP_INV() != null) return ":MapInv";
        if (ctor.collectionType().COLL_MAP_INV_NON_EMPTY() != null) return ":MapInvNonEmpty";
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
          var isMap = col.COLL_MAP() != null || col.COLL_MAP_NON_EMPTY() != null
              || col.COLL_MAP_INV() != null || col.COLL_MAP_INV_NON_EMPTY() != null;
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

    // Follow alias references
    if (n1.typeKeyword() != null) {
      var kw1 = n1.typeKeyword().getText();
      if (!visited1.contains(kw1)) {
        visited1.add(kw1);
        var typeDefOpt1 = findTypeDefinition(doc, kw1);
        if (typeDefOpt1.isPresent()) {
          var res = isSameSchemaNodeRecursive(doc, typeDefOpt1.get().schemaType(), n2, visited1, visited2);
          visited1.remove(kw1);
          return res;
        }
        visited1.remove(kw1);
      }
    }

    if (n2.typeKeyword() != null) {
      var kw2 = n2.typeKeyword().getText();
      if (!visited2.contains(kw2)) {
        visited2.add(kw2);
        var typeDefOpt2 = findTypeDefinition(doc, kw2);
        if (typeDefOpt2.isPresent()) {
          var res = isSameSchemaNodeRecursive(doc, n1, typeDefOpt2.get().schemaType(), visited1, visited2);
          visited2.remove(kw2);
          return res;
        }
        visited2.remove(kw2);
      }
    }

    // Now both n1 and n2 are resolved to their underlying/primitive schemas (or self-referential keywords)
    if (n1.typeKeyword() != null && n2.typeKeyword() != null) {
      return n1.typeKeyword().getText().equals(n2.typeKeyword().getText());
    }
    if (n1.typeKeyword() != null || n2.typeKeyword() != null) {
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
            throw new MalformedSchemaException("Collection type schema requires a parameter type definition (e.g. :Seq(:Int32))");
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
   */
  public static void validateSchemaSumTypeUniqueness(
      @Nullable StvnDocumentContext doc,
      @Nullable SchemaTypeContext schemaNode,
      Set<String> visited) {
    var bag = new DiagnosticBag();
    validateSchemaSumTypeUniqueness(doc, schemaNode, visited, bag);
    if (bag.hasErrors()) {
      var first = bag.toList().getFirst();
      if (first.cause() instanceof RuntimeException re) {
        throw re;
      }
      throw new MalformedSchemaException(first.message(), first.startOffset(), first.endOffset(), first.cause());
    }
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
   */
  public static void validateSchemaSumTypeUniqueness(
      @Nullable StvnDocumentContext doc,
      @Nullable SchemaTypeContext schemaNode,
      Set<String> visited,
      DiagnosticBag diagnosticBag) {
    if (schemaNode == null) return;

    if (schemaNode.typeKeyword() != null) {
      var kw = schemaNode.typeKeyword().getText();
      if (!visited.add(kw)) {
        return; // Break recursion on cycle
      }
    }

    var resolvedOpt = resolvePrimitiveSchema(doc, schemaNode, new java.util.HashSet<>(visited), true);
    if (resolvedOpt.isEmpty()) return;

    var resolved = resolvedOpt.get();
    var baseText = getPrimitiveBaseType(resolved.node());

    if (baseText != null && (baseText.equals(":Union") || baseText.equals(":Either"))) {
      var candidates = resolveCandidateSchemas(doc, resolved.node());
      for (int i = 0; i < candidates.size(); i++) {
        for (int j = i + 1; j < candidates.size(); j++) {
          var r1 = candidates.get(i);
          var r2 = candidates.get(j);
          var nom1 = r1.aliasName().orElseGet(() -> getPrimitiveBaseType(r1.node()));
          var nom2 = r2.aliasName().orElseGet(() -> getPrimitiveBaseType(r2.node()));
          if (Objects.equals(nom1, nom2)) {
            int line = schemaNode.getStart().getLine();
            int col = schemaNode.getStart().getCharPositionInLine();
            int start = schemaNode.getStart().getStartIndex();
            int end = schemaNode.getStop().getStopIndex() + 1;
            diagnosticBag.addError(
                "Two member branches within a single sum type share identical nominal type identities: " + nom1,
                start, end, line, col, null, DiagnosticBag.ERR_SUM_TYPE_COLLISION
            );
          }
        }
      }
    }

    for (var child : getInnerSchemas(schemaNode)) {
      validateSchemaSumTypeUniqueness(doc, child, new java.util.HashSet<>(visited), diagnosticBag);
    }
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
      var kw = schemaNode.typeKeyword().getText();
      if (!visited.add(kw)) {
        return; // Break recursion on cycle
      }
      var typeDefOpt = findTypeDefinition(doc, kw);
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
    getDocumentDefinitions(doc, diagnosticBag);
    var defsEntry = doc.documentBody().defsEntry();
    if (defsEntry != null) {
      if (defsEntry.typeDefinition() != null) {
        for (var typeDef : defsEntry.typeDefinition()) {
          validateTypeDefinition(doc, typeDef, diagnosticBag);
        }
      }
      if (defsEntry.constantDefinition() != null) {
        for (var constDef : defsEntry.constantDefinition()) {
          validateConstantDefinition(doc, constDef, diagnosticBag);
        }
      }
    }
    if (doc.documentBody().typeEntry() != null) {
      validateSchemaSumTypeUniqueness(doc, doc.documentBody().typeEntry().schemaType(), new java.util.HashSet<>(), diagnosticBag);
      validateSchemaCapabilities(doc, doc.documentBody().typeEntry().schemaType(), new java.util.HashSet<>(), diagnosticBag);
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
    var typeName = typeDef.typeKeyword().getText();
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
      diagnosticBag.addError(e.getMessage(), start, end, line, col, e, DiagnosticBag.ERR_MALFORMED_SCHEMA);
      markTypePoisoned(doc, typeName);
      return;
    }

    validateSchemaSumTypeUniqueness(doc, typeDef.schemaType(), new java.util.HashSet<>(), diagnosticBag);
    validateSchemaCapabilities(doc, typeDef.schemaType(), new java.util.HashSet<>(), diagnosticBag);
    if (typeDef.metadataMap() != null) {
      validateMetadataMapConstraints(typeName, typeDef.metadataMap(), resolvedOpt.orElse(null), diagnosticBag);
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
      diagnosticBag.addError(e.getMessage(), start, end, line, col, e, DiagnosticBag.ERR_MALFORMED_SCHEMA);
      return;
    }

    validateSchemaSumTypeUniqueness(doc, constDef.schemaType(), new java.util.HashSet<>(), diagnosticBag);
    validateSchemaCapabilities(doc, constDef.schemaType(), new java.util.HashSet<>(), diagnosticBag);
    if (constDef.metadataMap() != null) {
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
      var valBI = new java.math.BigInteger(valueCtx.integerLiteral().getText());
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

  private static void validateMetadataMapConstraints(String name, MetadataMapContext metadataMap, @Nullable ResolvedSchema resolved, DiagnosticBag diagnosticBag) {
    var hasMinIncl = false;
    var hasMinExcl = false;
    var hasMaxIncl = false;
    var hasMaxExcl = false;

    for (var entry : metadataMap.metadataEntry()) {
      if (entry.metadataNum() != null) {
        var numCtx = entry.metadataNum();
        if (numCtx.KW_MIN_INCL() != null) hasMinIncl = true;
        if (numCtx.KW_MIN_EXCL() != null) hasMinExcl = true;
        if (numCtx.KW_MAX_INCL() != null) hasMaxIncl = true;
        if (numCtx.KW_MAX_EXCL() != null) hasMaxExcl = true;
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
    var isNumeric = isIntegerType || isFloatType;

    for (var entry : metadataMap.metadataEntry()) {
      if (entry.metadataNum() != null) {
        var numCtx = entry.metadataNum();
        var constraintName = "";
        if (numCtx.KW_MIN_INCL() != null) constraintName = "minIncl";
        else if (numCtx.KW_MIN_EXCL() != null) constraintName = "minExcl";
        else if (numCtx.KW_MAX_INCL() != null) constraintName = "maxIncl";
        else if (numCtx.KW_MAX_EXCL() != null) constraintName = "maxExcl";

        if (!isNumeric) {
          diagnosticBag.addError(
              "Constraint violation (" + name + "): " + constraintName + " is not allowed on " + baseType,
              numCtx.getStart().getStartIndex(),
              numCtx.getStop().getStopIndex() + 1,
              numCtx.getStart().getLine(),
              numCtx.getStart().getCharPositionInLine(),
              null,
              DiagnosticBag.ERR_INCOMPATIBLE_TYPE
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

        if (isIntegerType) {
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
        if (mv != null) {
          if (strCtx.KW_REGEX() != null) {
            if (!isStringType) {
              diagnosticBag.addError(
                  "Constraint violation (" + name + "): regex is not allowed on " + baseType,
                  strCtx.getStart().getStartIndex(),
                  strCtx.getStop().getStopIndex() + 1,
                  strCtx.getStart().getLine(),
                  strCtx.getStart().getCharPositionInLine(),
                  null,
                  DiagnosticBag.ERR_INCOMPATIBLE_TYPE
              );
            }
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
        }
      } else if (entry.metadataBool() != null) {
        var boolCtx = entry.metadataBool();
        var constraintName = "";
        if (boolCtx.KW_PRESERVE_INDENT() != null) constraintName = "preserveIndent";
        else if (boolCtx.KW_EQUATABLE() != null) constraintName = "equatable";
        else if (boolCtx.KW_COMPARABLE() != null) constraintName = "comparable";

        if (boolCtx.KW_PRESERVE_INDENT() != null && !isStringType) {
          diagnosticBag.addError(
              "Constraint violation (" + name + "): preserveIndent is not allowed on " + baseType,
              boolCtx.getStart().getStartIndex(),
              boolCtx.getStop().getStopIndex() + 1,
              boolCtx.getStart().getLine(),
              boolCtx.getStart().getCharPositionInLine(),
              null,
              DiagnosticBag.ERR_INCOMPATIBLE_TYPE
          );
        }

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

    if (isIntegerType) {
      var bitWidth = 32;
      if (baseType.startsWith(":Int") && baseType.length() > 4 && Character.isDigit(baseType.charAt(4))) {
        bitWidth = Integer.parseInt(baseType.substring(4));
      } else if (baseType.startsWith(":Uint") && baseType.length() > 5 && Character.isDigit(baseType.charAt(5))) {
        bitWidth = Integer.parseInt(baseType.substring(5));
      } else if (isTimeEpochType(baseType)) {
        bitWidth = 64;
      }

      var isUnsigned = baseType.startsWith(":Uint");

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
        if (entry.metadataNum() != null) {
          var numCtx = entry.metadataNum();
          var constraintName = "";
          if (numCtx.KW_MIN_INCL() != null) constraintName = "minIncl";
          else if (numCtx.KW_MIN_EXCL() != null) constraintName = "minExcl";
          else if (numCtx.KW_MAX_INCL() != null) constraintName = "maxIncl";
          else if (numCtx.KW_MAX_EXCL() != null) constraintName = "maxExcl";

          var mv = numCtx.metadataValue();
          if (mv != null && mv.integerLiteral() != null) {
            var valBI = new java.math.BigInteger(mv.integerLiteral().getText());
            if (valBI.compareTo(minPhys) < 0 || valBI.compareTo(maxPhys) > 0) {
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

      var consolidated = extractConstraints(metadataMap).merge(resolved.constraints());
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
      var isExact = baseType.equals(":FloatExact");
      if (!isExact) {
        var minPhys = BigDecimal.ZERO;
        var maxPhys = BigDecimal.ZERO;
        if (baseType.equals(":Float32")) {
          minPhys = BigDecimal.valueOf(-Float.MAX_VALUE);
          maxPhys = BigDecimal.valueOf(Float.MAX_VALUE);
        } else {
          minPhys = BigDecimal.valueOf(-Double.MAX_VALUE);
          maxPhys = BigDecimal.valueOf(Double.MAX_VALUE);
        }

        for (var entry : metadataMap.metadataEntry()) {
          if (entry.metadataNum() != null) {
            var numCtx = entry.metadataNum();
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

      var consolidated = extractConstraints(metadataMap).merge(resolved.constraints());
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
      for (var def : doc.documentBody().defsEntry().typeDefinition()) {
        if (def.schemaType() == schemaType) {
          return Optional.of(def.typeKeyword().getText());
        }
      }
    }
    var preludeDoc = StvnPrelude.getPreludeDocument();
    if (preludeDoc != null && preludeDoc.documentBody() != null && preludeDoc.documentBody().defsEntry() != null) {
      for (var def : preludeDoc.documentBody().defsEntry().typeDefinition()) {
        if (def.schemaType() == schemaType) {
          return Optional.of(def.typeKeyword().getText());
        }
      }
    }
    return Optional.empty();
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
}
