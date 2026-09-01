package org.stvnadore.core;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStreamRewriter;
import org.antlr.v4.runtime.tree.ParseTree;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.ir.StvnLiteralParser;
import org.stvnadore.core.parser.StvnLexer;
import org.stvnadore.core.parser.StvnParser;
import org.stvnadore.core.parser.StvnParser.SchemaTypeContext;
import org.stvnadore.core.parser.StvnParser.TypeDefinitionContext;
import org.stvnadore.core.parser.StvnParser.ValueContext;
import org.stvnadore.core.validation.CyclicDependencyException;
import org.stvnadore.core.validation.DuplicateModuleImportException;
import org.stvnadore.core.validation.NamespaceCollisionException;
import org.stvnadore.core.validation.StvnTypeResolver;
import org.stvnadore.core.validation.StvnTypeResolver.DefSource;
import org.stvnadore.core.validation.StvnTypeResolver.StvnConstraints;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/**
 * Utility engine for flattening multi-file STVN schema modules and include dependency graphs
 * into a single self-contained, canonical schema document.
 * <p>
 * This class coordinates relative path resolution, import aliasing, duplicate import detection,
 * cyclic dependency detection, zero-shadowing validation, and canonical emission of flattened schemas.
 *
 * @since 1.0.0
 */
@NullMarked
public final class StvnSchemaFlattener {

  private StvnSchemaFlattener() {
    // Utility / Facade class
  }

  private enum ClaimType {
    LOCAL,
    RAW_IMPORT,
    RENAMED_IMPORT_LHS,
    RENAMED_IMPORT_RHS
  }

  private record NamespaceClaim(
      String identifier,
      TypeDefinitionContext defNode,
      String sourceModule,
      ClaimType type
  ) {}

  private record ImportInfo(
      String rawPath,
      String resolvedPath,
      Map<String, String> aliasMap
  ) {}

  private record ParsedDocument(
      String normalizedPath,
      StvnParser.StvnDocumentContext docCtx,
      CommonTokenStream tokenStream,
      List<ImportInfo> imports,
      List<TypeDefinitionContext> localDefs
  ) {}

  /**
   * Performs path normalization using pure string manipulation to avoid JDK Path resolution nuances on Windows.
   *
   * @param path the raw file path string to normalize
   * @return the normalized POSIX-style path string with forward slashes and redundant segments resolved
   * @throws NullPointerException if {@code path} is {@code null}
   */
  public static String normalizePath(String path) {
    path = path.replace('\\', '/');
    String[] parts = path.split("/");
    List<String> stack = new ArrayList<>();
    for (String part : parts) {
      part = part.trim();
      if (part.isEmpty() || part.equals(".")) {
        continue;
      }
      if (part.equals("..")) {
        if (!stack.isEmpty() && !stack.getLast().equals("..")) {
          stack.removeLast();
        } else {
          stack.add("..");
        }
      } else {
        stack.add(part);
      }
    }
    String resolved = String.join("/", stack);
    if (path.startsWith("/")) {
      return "/" + resolved;
    }
    return resolved;
  }

  /**
   * Resolves include paths relative to the current file using pure string manipulation.
   *
   * @param currentPath the normalized path of the importing document
   * @param includePath the relative or absolute target include path string
   * @return the normalized absolute or relative target include path string
   * @throws NullPointerException if {@code currentPath} or {@code includePath} is {@code null}
   */
  public static String resolveIncludePath(String currentPath, String includePath) {
    currentPath = normalizePath(currentPath);
    includePath = includePath.replace('\\', '/');
    if (includePath.startsWith("/")) {
      return normalizePath(includePath);
    }
    int lastSlash = currentPath.lastIndexOf('/');
    if (lastSlash == -1) {
      return normalizePath(includePath);
    }
    String parentDir = currentPath.substring(0, lastSlash);
    return normalizePath(parentDir + "/" + includePath);
  }

  /**
   * Headless entry point for flattening schema files within a virtual workspace.
   * <p>
   * Recursively parses the entry point document and all referenced imports, enforces acyclicity,
   * detects namespace collisions, and outputs a canonical definitions block.
   *
   * @param workspace a map of normalized file paths to their raw document contents representing the virtual filesystem
   * @param entryPointPath the path of the root schema document to flatten
   * @return the canonical flattened STVN schema string containing all resolved definitions
   * @throws IllegalArgumentException if {@code entryPointPath} is not found in {@code workspace}
   * @throws org.stvnadore.core.validation.CyclicDependencyException if a circular include dependency is detected
   * @throws org.stvnadore.core.validation.DuplicateModuleImportException if a document imports the same module multiple times
   * @throws org.stvnadore.core.validation.NamespaceCollisionException if conflicting type definitions are imported
   * @throws NullPointerException if {@code workspace} or {@code entryPointPath} is {@code null}
   * @throws RuntimeException if a syntax error or unresolved reference is encountered during flattening
   */
  public static String flatten(Map<String, String> workspace, String entryPointPath) {
    Map<String, String> normalizedWorkspace = new LinkedHashMap<>();
    for (var entry : workspace.entrySet()) {
      normalizedWorkspace.put(normalizePath(entry.getKey()), entry.getValue());
    }
    String normEntryPoint = normalizePath(entryPointPath);

    if (!normalizedWorkspace.containsKey(normEntryPoint)) {
      throw new IllegalArgumentException("Entry point path not found in workspace: " + entryPointPath);
    }

    var errorListener = new BaseErrorListener() {
      @Override
      public void syntaxError(
          Recognizer<?, ?> recognizer,
          @Nullable Object offendingSymbol,
          int line,
          int charPositionInLine,
          String msg,
          @Nullable RecognitionException e
      ) {
        throw new RuntimeException("STVN Syntax Error: " + msg, e);
      }
    };

    Map<String, ParsedDocument> parsedCache = new HashMap<>();
    Map<String, Map<String, DefSource>> resolvedCache = new HashMap<>();
    LinkedHashSet<String> activePaths = new LinkedHashSet<>();
    List<String> activeRawPaths = new ArrayList<>();

    // Recursively resolve all definitions and check for cycles
    Map<String, DefSource> entryPointDefs = resolveDocument(
        normEntryPoint,
        "",
        parsedCache,
        normalizedWorkspace,
        activePaths,
        activeRawPaths,
        resolvedCache,
        errorListener
    );

    // Map each definition context to its final name in the entry point context (supporting multiple aliases)
    Map<TypeDefinitionContext, List<String>> entryPointTypeNames = new IdentityHashMap<>();
    for (var entry : entryPointDefs.entrySet()) {
      entryPointTypeNames.computeIfAbsent(entry.getValue().defNode(), k -> new ArrayList<>())
          .add(entry.getKey());
    }

    // Rewrite type definitions and canonicalize them
    List<String> outputDefinitions = new ArrayList<>();

    for (var entry : entryPointDefs.entrySet()) {
      String entryName = entry.getKey();
      DefSource ds = entry.getValue();
      TypeDefinitionContext originalDef = ds.defNode();

      // Locate the document in which this type was originally defined
      String sourcePath = ds.sourceName();
      ParsedDocument doc = parsedCache.get(normalizePath(sourcePath));
      if (doc == null) {
        throw new IllegalStateException("Failed to find parsed document for: " + sourcePath);
      }

      // Perform TokenStream rewrites for the definition
      TokenStreamRewriter rewriter = new TokenStreamRewriter(doc.tokenStream());

      // Rewrite nominal identifier
      rewriter.replace(originalDef.typeKeyword().getStart(), originalDef.typeKeyword().getStop(), entryName);

      // Find all descendant references inside schemaType
      List<StvnParser.TypeKeywordContext> internalRefs = new ArrayList<>();
      collectTypeKeywords(originalDef.schemaType(), internalRefs);

      for (var refCtx : internalRefs) {
        String refText = refCtx.getText();
        String targetName = resolveReference(refText, doc.normalizedPath(), resolvedCache, entryPointTypeNames);
        rewriter.replace(refCtx.getStart(), refCtx.getStop(), targetName);
      }

      // Convert rewritten token stream to a spaced canonical text format to prevent token blending
      int startIdx = originalDef.getStart().getTokenIndex();
      int stopIdx = originalDef.getStop().getTokenIndex();
      List<String> tokenTexts = new ArrayList<>();
      for (int i = startIdx; i <= stopIdx; i++) {
        var interval = new org.antlr.v4.runtime.misc.Interval(i, i);
        tokenTexts.add(rewriter.getText(interval));
      }
      String spacedRewritten = toCanonicalString(tokenTexts);
      var cleanDef = parseTypeDefinition(spacedRewritten, errorListener);

      // Render the definition canonically
      String canonicalDef = printCanonical(cleanDef);
      outputDefinitions.add(canonicalDef);
    }

    // Sort all definitions alphabetically by nominal identifier name
    outputDefinitions.sort((a, b) -> {
      String nameA = getNominalName(a);
      String nameB = getNominalName(b);
      return nameA.compareTo(nameB);
    });

    // Assemble final output
    String joinedDefs = String.join(" ", outputDefinitions);
    return "{ :defs { " + joinedDefs + " } }";
  }

  private static String getNominalName(String canonicalDef) {
    int firstSpace = canonicalDef.indexOf(' ');
    if (firstSpace == -1) {
      return canonicalDef;
    }
    return canonicalDef.substring(0, firstSpace);
  }

  private static void collectTypeKeywords(ParseTree node, List<StvnParser.TypeKeywordContext> list) {
    if (node instanceof StvnParser.TypeKeywordContext tk) {
      list.add(tk);
    } else {
      for (int i = 0; i < node.getChildCount(); i++) {
        collectTypeKeywords(node.getChild(i), list);
      }
    }
  }

  private static String resolveReference(
      String refText,
      String currentPath,
      Map<String, Map<String, DefSource>> resolvedCache,
      Map<TypeDefinitionContext, List<String>> entryPointTypeNames
  ) {
    if (isPrimitiveType(refText) || isPreludeType(refText)) {
      return refText;
    }

    Map<String, DefSource> currentDefs = resolvedCache.get(currentPath);
    if (currentDefs != null && currentDefs.containsKey(refText)) {
      DefSource target = currentDefs.get(refText);
      List<String> entryNames = entryPointTypeNames.get(target.defNode());
      if (entryNames != null && !entryNames.isEmpty()) {
        if (entryNames.contains(refText)) {
          return refText;
        }
        List<String> sortedNames = new ArrayList<>(entryNames);
        Collections.sort(sortedNames);
        return sortedNames.get(0);
      }
    }

    return refText;
  }

  private static boolean isPrimitiveType(String type) {
    if (type.equals(":Boolean") || type.equals(":FloatExact") || type.equals(":TimeEpochS") ||
        type.equals(":TimeEpochMs") || type.equals(":TimeEpochNs") || type.equals(":DateTimeOffset") ||
        type.equals(":DateTimeZoned") || type.equals(":DateTimeAudited") || type.equals(":Seq") || type.equals(":SeqNonEmpty") ||
        type.equals(":Set") || type.equals(":SetNonEmpty") || type.equals(":Map") ||
        type.equals(":MapNonEmpty") || type.equals(":MapInv") || type.equals(":MapInvNonEmpty") ||
        type.equals(":Tuple") || type.equals(":MapEntry") || type.equals(":Option") ||
        type.equals(":Either") || type.equals(":Union") || type.equals(":Enum")) {
      return true;
    }
    if (type.startsWith(":Int") && type.substring(4).matches("\\d*")) return true;
    if (type.startsWith(":Uint") && type.substring(5).matches("\\d*")) return true;
    if (type.startsWith(":Float") && type.substring(6).matches("\\d*")) return true;
    if (type.startsWith(":StringFixed") && type.substring(12).matches("\\d*")) return true;
    if (type.startsWith(":StringNonEmpty") && type.substring(15).matches("\\d*")) return true;
    if (type.startsWith(":String") && !type.startsWith(":StringFixed") && !type.startsWith(":StringNonEmpty") && type.substring(7).matches("\\d*")) return true;
    return false;
  }

  private static boolean isPreludeType(String type) {
    return type.equals(":Uuid") || type.equals(":Ulid") || type.equals(":Sha256") ||
        type.equals(":SemVer") || type.equals(":Email") || type.equals(":IPv4") ||
        type.equals(":Port") || type.equals(":Percentage") || type.equals(":Probability") ||
        type.equals(":Currency") || type.equals(":Latitude") || type.equals(":Longitude");
  }

  private static ParsedDocument parseFile(
      String normalizedPath,
      String content,
      BaseErrorListener errorListener
  ) {
    var lexer = new StvnLexer(CharStreams.fromString(content));
    lexer.removeErrorListeners();
    lexer.addErrorListener(errorListener);

    List<Token> tokens = new ArrayList<>();
    Token token;
    while ((token = lexer.nextToken()).getType() != Token.EOF) {
      if (token.getChannel() == Token.DEFAULT_CHANNEL) {
        tokens.add(token);
      }
    }
    tokens.add(token);

    var tokenSource = new org.antlr.v4.runtime.ListTokenSource(tokens);
    var tokenStream = new CommonTokenStream(tokenSource);
    var parser = new StvnParser(tokenStream);
    parser.removeErrorListeners();
    parser.addErrorListener(errorListener);

    var docCtx = parser.stvnDocument();

    List<ImportInfo> imports = new ArrayList<>();
    List<TypeDefinitionContext> localDefs = new ArrayList<>();

    if (docCtx.documentBody() != null && docCtx.documentBody().defsEntry() != null) {
      var defsEntry = docCtx.documentBody().defsEntry();
      if (defsEntry.includeStmt() != null) {
        Set<String> seenRawPaths = new LinkedHashSet<>();
        for (var includeStmt : defsEntry.includeStmt()) {
          if (includeStmt.includeElement() != null) {
            for (var element : includeStmt.includeElement()) {
              var rawPathStr = element.stringLiteral().getText();
              var pathVal = StvnLiteralParser.parseString(rawPathStr, true);
              if (!seenRawPaths.add(pathVal)) {
                throw new DuplicateModuleImportException("Duplicate module import detected for path: " + pathVal);
              }
              var resolvedPath = resolveIncludePath(normalizedPath, pathVal);

              Map<String, String> aliasMap = new LinkedHashMap<>();
              if (element.includeAliasBlock() != null && element.includeAliasBlock().includeMapAlias() != null) {
                for (var alias : element.includeAliasBlock().includeMapAlias()) {
                  aliasMap.put(alias.typeKeyword(0).getText(), alias.typeKeyword(1).getText());
                }
              }
              imports.add(new ImportInfo(pathVal, resolvedPath, aliasMap));
            }
          }
        }
      }
      if (defsEntry.typeDefinition() != null) {
        localDefs.addAll(defsEntry.typeDefinition());
      }
    }

    return new ParsedDocument(normalizedPath, docCtx, tokenStream, imports, localDefs);
  }

  private static TypeDefinitionContext parseTypeDefinition(String source, BaseErrorListener errorListener) {
    String wrapped = "{ :defs { " + source + " } }";
    var lexer = new StvnLexer(CharStreams.fromString(wrapped));
    lexer.removeErrorListeners();
    lexer.addErrorListener(errorListener);

    List<Token> tokens = new ArrayList<>();
    Token token;
    while ((token = lexer.nextToken()).getType() != Token.EOF) {
      if (token.getChannel() == Token.DEFAULT_CHANNEL) {
        tokens.add(token);
      }
    }
    tokens.add(token);

    var tokenSource = new org.antlr.v4.runtime.ListTokenSource(tokens);
    var tokenStream = new CommonTokenStream(tokenSource);
    var parser = new StvnParser(tokenStream);
    parser.removeErrorListeners();
    parser.addErrorListener(errorListener);

    var doc = parser.stvnDocument();
    return doc.documentBody().defsEntry().typeDefinition(0);
  }

  private static Map<String, DefSource> resolveDocument(
      String currentPath,
      String currentRawImport,
      Map<String, ParsedDocument> parsedCache,
      Map<String, String> workspace,
      LinkedHashSet<String> activePaths,
      List<String> activeRawPaths,
      Map<String, Map<String, DefSource>> resolvedCache,
      BaseErrorListener errorListener
  ) {
    if (activePaths.contains(currentPath)) {
      List<String> canonicalList = new ArrayList<>(activePaths);
      int cycleStartIndex = canonicalList.indexOf(currentPath);

      List<String> rawPathsSlice = new ArrayList<>();
      List<String> canonicalPathsSlice = new ArrayList<>();

      for (int i = cycleStartIndex + 1; i < canonicalList.size(); i++) {
        rawPathsSlice.add(activeRawPaths.get(i));
        canonicalPathsSlice.add(canonicalList.get(i));
      }
      rawPathsSlice.add(currentRawImport);
      canonicalPathsSlice.add(currentPath);

      List<String> names = new ArrayList<>();
      String startFile = canonicalList.get(cycleStartIndex);
      int lastSlash = startFile.lastIndexOf('/');
      names.add(lastSlash == -1 ? startFile : startFile.substring(lastSlash + 1));

      for (String p : canonicalPathsSlice) {
        int ls = p.lastIndexOf('/');
        names.add(ls == -1 ? p : p.substring(ls + 1));
      }
      String trace = String.join(" -> ", names);

      throw new CyclicDependencyException("Cycle detected: " + trace, rawPathsSlice, canonicalPathsSlice);
    }

    if (resolvedCache.containsKey(currentPath)) {
      return resolvedCache.get(currentPath);
    }

    activePaths.add(currentPath);
    activeRawPaths.add(currentRawImport);

    ParsedDocument parsed = parsedCache.get(currentPath);
    if (parsed == null) {
      String content = workspace.get(currentPath);
      if (content == null) {
        throw new RuntimeException("Missing file in workspace: " + currentPath);
      }
      parsed = parseFile(currentPath, content, errorListener);
      parsedCache.put(currentPath, parsed);
    }

    var accumulator = new LinkedHashMap<String, List<NamespaceClaim>>();

    if (parsed.docCtx.documentBody() != null && parsed.docCtx.documentBody().defsEntry() != null) {
      var defsEntry = parsed.docCtx.documentBody().defsEntry();
      var elements = new ArrayList<ParserRuleContext>();
      if (defsEntry.typeDefinition() != null) {
        elements.addAll(defsEntry.typeDefinition());
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

      int importIdx = 0;

      for (var element : elements) {
        if (element instanceof TypeDefinitionContext typeDef) {
          String typeName = typeDef.typeKeyword().getText();
          var existingClaims = accumulator.get(typeName);
          if (existingClaims != null) {
            for (var claim : existingClaims) {
              if (claim.type() == ClaimType.LOCAL) {
                throw new IllegalStateException("Zero-Shadowing constraint violated: " + typeName);
              }
            }
          }
          accumulator.computeIfAbsent(typeName, k -> new ArrayList<>())
              .add(new NamespaceClaim(typeName, typeDef, currentPath, ClaimType.LOCAL));
        } else if (element instanceof StvnParser.IncludeStmtContext includeStmt) {
          if (includeStmt.includeElement() != null) {
            for (var inclEl : includeStmt.includeElement()) {
              ImportInfo imp = parsed.imports.get(importIdx++);
              Map<String, DefSource> importedDefs = resolveDocument(
                  imp.resolvedPath(),
                  imp.rawPath(),
                  parsedCache,
                  workspace,
                  activePaths,
                  activeRawPaths,
                  resolvedCache,
                  errorListener
              );

              for (var entry : importedDefs.entrySet()) {
                String originalName = entry.getKey();
                DefSource defSource = entry.getValue();

                String importedName = originalName;
                boolean isRenamed = false;
                if (imp.aliasMap().containsKey(originalName)) {
                  importedName = imp.aliasMap().get(originalName);
                  isRenamed = true;
                }

                if (isRenamed) {
                  accumulator.computeIfAbsent(importedName, k -> new ArrayList<>())
                      .add(new NamespaceClaim(importedName, defSource.defNode(), defSource.sourceName(), ClaimType.RENAMED_IMPORT_RHS));
                  accumulator.computeIfAbsent(originalName, k -> new ArrayList<>())
                      .add(new NamespaceClaim(originalName, defSource.defNode(), defSource.sourceName(), ClaimType.RENAMED_IMPORT_LHS));
                } else {
                  accumulator.computeIfAbsent(originalName, k -> new ArrayList<>())
                      .add(new NamespaceClaim(originalName, defSource.defNode(), defSource.sourceName(), ClaimType.RAW_IMPORT));
                }
              }
            }
          }
        }
      }
    }

    Map<String, DefSource> localDefs = new LinkedHashMap<>();
    List<String> collisions = new ArrayList<>();

    for (var entry : accumulator.entrySet()) {
      String id = entry.getKey();
      List<NamespaceClaim> claims = entry.getValue();

      boolean hasLocal = false;
      NamespaceClaim localClaim = null;
      for (var c : claims) {
        if (c.type() == ClaimType.LOCAL) {
          hasLocal = true;
          localClaim = c;
          break;
        }
      }

      if (hasLocal) {
        List<NamespaceClaim> filteredClaims = new ArrayList<>();
        filteredClaims.add(localClaim);
        for (var c : claims) {
          if (c.type() == ClaimType.RENAMED_IMPORT_RHS) {
            filteredClaims.add(c);
          }
        }
        if (filteredClaims.size() > 1) {
          collisions.add(id);
        } else {
          localDefs.put(id, new DefSource(localClaim.defNode(), localClaim.sourceModule()));
        }
      } else {
        List<NamespaceClaim> rawClaims = new ArrayList<>();
        List<NamespaceClaim> lhsClaims = new ArrayList<>();
        List<NamespaceClaim> rhsClaims = new ArrayList<>();

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

        List<NamespaceClaim> remainingClaims = new ArrayList<>();
        remainingClaims.addAll(rawClaims);
        remainingClaims.addAll(lhsClaims);
        remainingClaims.addAll(rhsClaims);

        if (remainingClaims.size() == 1) {
          NamespaceClaim single = remainingClaims.get(0);
          localDefs.put(id, new DefSource(single.defNode(), single.sourceModule()));
        } else if (remainingClaims.size() > 1) {
          collisions.add(id);
        }
      }
    }

    if (!collisions.isEmpty()) {
      throw new NamespaceCollisionException("Namespace collision(s) detected: " + collisions);
    }

    resolvedCache.put(currentPath, localDefs);

    activePaths.remove(currentPath);
    activeRawPaths.remove(activeRawPaths.size() - 1);

    return localDefs;
  }

  private static String printCanonical(TypeDefinitionContext typeDef) {
    StringBuilder sb = new StringBuilder();
    sb.append(typeDef.typeKeyword().getText());

    StvnConstraints constraints = StvnTypeResolver.extractConstraints(typeDef.metadataMap());
    if (hasConstraints(constraints)) {
      sb.append(" {");
      var comparable = constraints.comparable().orElse(null);
      if (comparable != null && constraints.explicitOverrides().contains("comparable")) {
        sb.append(" #comparable ").append(comparable ? "#TRUE" : "#FALSE");
      }
      var equatable = constraints.equatable().orElse(null);
      if (equatable != null && constraints.explicitOverrides().contains("equatable")) {
        sb.append(" #equatable ").append(equatable ? "#TRUE" : "#FALSE");
      }
      var maxExcl = constraints.maxExcl().orElse(null);
      if (maxExcl != null) {
        sb.append(" #maxExcl ").append(maxExcl);
      }
      var maxIncl = constraints.maxIncl().orElse(null);
      if (maxIncl != null) {
        sb.append(" #maxIncl ").append(maxIncl);
      }
      var minExcl = constraints.minExcl().orElse(null);
      if (minExcl != null) {
        sb.append(" #minExcl ").append(minExcl);
      }
      var minIncl = constraints.minIncl().orElse(null);
      if (minIncl != null) {
        sb.append(" #minIncl ").append(minIncl);
      }
      if (constraints.preserveIndent() && constraints.explicitOverrides().contains("preserveIndent")) {
        sb.append(" #preserveIndent #TRUE");
      }
      var regex = constraints.regex().orElse(null);
      if (regex != null) {
        sb.append(" #regex \"").append(escapeString(regex)).append("\"");
      }
      sb.append(" }");
    }

    List<String> tokens = new ArrayList<>();
    collectSchemaTypeTokens(typeDef.schemaType(), tokens);

    String tail = toCanonicalString(tokens);
    sb.append(" ").append(tail);
    return sb.toString();
  }

  private static boolean hasConstraints(StvnConstraints c) {
    var hasMinIncl = c.minIncl().isPresent();
    var hasMinExcl = c.minExcl().isPresent();
    var hasMaxIncl = c.maxIncl().isPresent();
    var hasMaxExcl = c.maxExcl().isPresent();
    var hasRegex = c.regex().isPresent();
    var hasPreserveIndent = c.preserveIndent() && c.explicitOverrides().contains("preserveIndent");
    var hasEquatable = c.equatable().isPresent() && c.explicitOverrides().contains("equatable");
    var hasComparable = c.comparable().isPresent() && c.explicitOverrides().contains("comparable");

    return hasMinIncl || hasMinExcl || hasMaxIncl || hasMaxExcl
        || hasRegex || hasPreserveIndent || hasEquatable || hasComparable;
  }

  private static String escapeString(String s) {
    return s.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  private static boolean isPunctuation(String token) {
    return (token.length() == 1) && "()[]{}".contains(token);
  }

  private static String toCanonicalString(List<String> tokens) {
    StringBuilder sb = new StringBuilder();
    String lastToken = "";
    for (String token : tokens) {
      if (token.isEmpty()) {
        continue;
      }
      if (!lastToken.isEmpty()) {
        if (!isPunctuation(lastToken) && !isPunctuation(token)) {
          if (!lastToken.endsWith("\n")) {
            sb.append(' ');
          }
        }
      }
      sb.append(token);
      lastToken = token;
    }
    return sb.toString();
  }

  private static void collectSchemaTypeTokens(SchemaTypeContext ctx, List<String> tokens) {
    if (ctx.typeKeyword() != null) {
      tokens.add(ctx.typeKeyword().getText());
    } else if (ctx.schemaConstructor() != null) {
      var ctor = ctx.schemaConstructor();
      if (ctor.atomicType() != null) {
        tokens.add(ctor.atomicType().getText());
      } else if (ctor.collectionType() != null) {
        var col = ctor.collectionType();
        tokens.add(col.getChild(0).getText());
        tokens.add("(");
        for (var st : col.schemaType()) {
          collectSchemaTypeTokens(st, tokens);
        }
        tokens.add(")");
      } else if (ctor.productType() != null) {
        var prod = ctor.productType();
        if (prod instanceof StvnParser.TupleTypeContext tt) {
          tokens.add(":Tuple");
          tokens.add("(");
          for (var st : tt.schemaType()) {
            collectSchemaTypeTokens(st, tokens);
          }
          tokens.add(")");
        }
      } else if (ctor.sumType() != null) {
        var sum = ctor.sumType();
        if (sum.KW_OPTION() != null) {
          tokens.add(":Option");
          tokens.add("(");
          collectSchemaTypeTokens(sum.schemaType(0), tokens);
          tokens.add(")");
        } else if (sum.KW_EITHER() != null) {
          tokens.add(":Either");
          tokens.add("(");
          collectSchemaTypeTokens(sum.schemaType(0), tokens);
          collectSchemaTypeTokens(sum.schemaType(1), tokens);
          tokens.add(")");
        } else if (sum.KW_UNION() != null) {
          tokens.add(":Union");
          tokens.add("(");
          for (var st : sum.schemaType()) {
            collectSchemaTypeTokens(st, tokens);
          }
          tokens.add(")");
        } else if (sum.KW_ENUM() != null) {
          tokens.add(":Enum");
          tokens.add("[");
          for (var kw : sum.enumDef().valueKeyword()) {
            tokens.add(kw.getText());
          }
          tokens.add("]");
        }
      }
    }
  }

  private static void collectValueTokens(ValueContext ctx, List<String> tokens, boolean preserveIndent) {
    if (ctx.explicitOptionValue() != null) {
      var opt = ctx.explicitOptionValue();
      if (opt.KW_NONE() != null || opt.KW_NONE_SHORT() != null) {
        tokens.add("#None");
      } else {
        tokens.add("#Some");
        collectValueTokens(opt.value(), tokens, preserveIndent);
      }
    } else if (ctx.explicitEitherValue() != null) {
      var eit = ctx.explicitEitherValue();
      if (eit.KW_LEFT() != null || eit.KW_LEFT_SHORT() != null) {
        tokens.add("#Left");
        collectValueTokens(eit.value(), tokens, preserveIndent);
      } else {
        tokens.add("#Right");
        collectValueTokens(eit.value(), tokens, preserveIndent);
      }
    } else if (ctx.explicitUnionValue() != null) {
      var uni = ctx.explicitUnionValue();
      tokens.add(uni.UNION_TAG_PREFIX().getText());
      collectValueTokens(uni.value(), tokens, preserveIndent);
    } else if (ctx.booleanLiteral() != null) {
      var bool = ctx.booleanLiteral();
      if (bool.KW_TRUE() != null || bool.KW_TRUE_SHORT() != null) {
        tokens.add("#TRUE");
      } else {
        tokens.add("#FALSE");
      }
    } else if (ctx.integerLiteral() != null) {
      BigInteger val = StvnLiteralParser.parseBigInteger(ctx.integerLiteral().getText());
      tokens.add(val.toString());
    } else if (ctx.floatLiteral() != null) {
      BigDecimal val = StvnLiteralParser.parseFloat(ctx.floatLiteral().getText());
      tokens.add(formatFloat(val, org.stvnadore.core.ir.StvnValue.FloatPrecision.EXACT));
    } else if (ctx.stringLiteral() != null) {
      var parsed = StvnLiteralParser.parseStringNew(ctx.stringLiteral().getText(), preserveIndent);
      String escaped = escapeString(parsed.text());
      if (parsed.style() == org.stvnadore.core.ir.StvnValue.StringStyle.SIMPLE) {
        tokens.add("\"" + escaped + "\"");
      } else if (parsed.style() == org.stvnadore.core.ir.StvnValue.StringStyle.BLOCK) {
        tokens.add("\"\"\"\n" + parsed.text() + "\"\"\"");
      } else if (parsed.style() == org.stvnadore.core.ir.StvnValue.StringStyle.FENCED) {
        String tag = parsed.optionalFenceTag().orElse("FENCE");
        tokens.add("\"\"\"->[" + tag + "]\n" + parsed.text() + "[" + tag + "]\"\"\"");
      }
    } else if (ctx.valueKeyword() != null) {
      tokens.add(ctx.valueKeyword().getText());
    } else if (ctx.collectionValue() != null) {
      var coll = ctx.collectionValue();
      if (coll.listLiteral() != null) {
        tokens.add("[");
        for (var v : coll.listLiteral().value()) {
          collectValueTokens(v, tokens, preserveIndent);
        }
        tokens.add("]");
      } else if (coll.mapLiteral() != null) {
        tokens.add("{");
        for (var entry : coll.mapLiteral().mapEntry()) {
          tokens.add("[");
          collectValueTokens(entry.value(0), tokens, preserveIndent);
          collectValueTokens(entry.value(1), tokens, preserveIndent);
          tokens.add("]");
        }
        tokens.add("}");
      } else if (coll.tupleLiteral() != null) {
        tokens.add("(");
        for (var v : coll.tupleLiteral().value()) {
          collectValueTokens(v, tokens, preserveIndent);
        }
        tokens.add(")");
      }
    }
  }

  private static String formatFloat(BigDecimal value, org.stvnadore.core.ir.StvnValue.FloatPrecision floatPrecision) {
    var s = (floatPrecision == org.stvnadore.core.ir.StvnValue.FloatPrecision.EXACT)
        ? value.toPlainString()
        : value.toString();
    if (!s.contains(".")) {
      var eIdx = s.indexOf('e');
      if (eIdx == -1) {
        eIdx = s.indexOf('E');
      }

      return (eIdx == -1)
          ? s + ".0"
          : s.substring(0, eIdx) + ".0" + s.substring(eIdx);
    }
    return s;
  }
}
