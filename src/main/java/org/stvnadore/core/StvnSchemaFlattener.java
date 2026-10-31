package org.stvnadore.core;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity;
import org.stvnadore.core.io.CanonicalLayoutWriter;
import org.stvnadore.core.io.CanonicalStvnWriter;
import org.stvnadore.core.ir.StvnDefsCatalog;
import org.stvnadore.core.ir.StvnDocumentMeta;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.lowering.StvnDefsLowerer;
import org.stvnadore.core.parser.StvnLexer;
import org.stvnadore.core.parser.StvnParser;
import org.stvnadore.core.stdlib.StvnPrelude;
import org.stvnadore.core.validation.CyclicDependencyException;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.core.validation.DuplicateModuleImportException;
import org.stvnadore.core.validation.MalformedSchemaException;
import org.stvnadore.core.validation.NamespaceCollisionException;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Pure-IR utility engine for flattening multi-file STVN schema modules and {@code :include}
 * dependency graphs into a single self-contained, canonical {@code #DEFS} schema document.
 * <p>
 * This class delegates module graph resolution, Section 4.2 eviction cascade evaluation,
 * package/use FQNI desugaring, and 5-tier facet normalization directly to {@link StvnDefsLowerer},
 * then serializes all resolved definitions in canonical 5-tier decimal order ({@code 1.1.1}
 * through {@code 5.0}, § 6.1) with zero {@code W003} tautologies and zero {@code ERR_FACET_ORDER_VIOLATION}
 * errors on recompilation.
 * </p>
 *
 * @since 1.0.0
 */
@NullMarked
public final class StvnSchemaFlattener {

  private StvnSchemaFlattener() {
    // Utility / Facade class
  }

  /**
   * Performs path normalization using pure string manipulation to avoid JDK Path resolution nuances on Windows.
   *
   * @param path the raw file path string to normalize
   * @return the normalized POSIX-style path string with forward slashes and redundant segments resolved
   * @throws NullPointerException if {@code path} is {@code null}
   */
  public static String normalizePath(String path) {
    Objects.requireNonNull(path, "path must not be null");
    String posix = path.replace('\\', '/');
    String[] parts = posix.split("/");
    List<String> stack = new ArrayList<>();
    for (String part : parts) {
      String trimmed = part.trim();
      if (trimmed.isEmpty() || trimmed.equals(".")) {
        continue;
      }
      if (trimmed.equals("..")) {
        if (!stack.isEmpty() && !stack.getLast().equals("..")) {
          stack.removeLast();
        } else {
          stack.add("..");
        }
      } else {
        stack.add(trimmed);
      }
    }
    String resolved = String.join("/", stack);
    if (posix.startsWith("/")) {
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
    Objects.requireNonNull(currentPath, "currentPath must not be null");
    Objects.requireNonNull(includePath, "includePath must not be null");
    String normCurrent = normalizePath(currentPath);
    String posixInclude = includePath.replace('\\', '/');
    if (posixInclude.startsWith("/")) {
      return normalizePath(posixInclude);
    }
    int lastSlash = normCurrent.lastIndexOf('/');
    if (lastSlash == -1) {
      return normalizePath(posixInclude);
    }
    String parentDir = normCurrent.substring(0, lastSlash);
    return normalizePath(parentDir + "/" + posixInclude);
  }

  /**
   * Headless entry point for flattening schema files within a virtual workspace into a canonical
   * {@code #DEFS} STVN schema document.
   *
   * @param workspace      a map of file paths to their raw STVN document contents representing the virtual filesystem
   * @param entryPointPath the path of the root schema document to flatten
   * @return the canonical flattened STVN schema string containing all resolved definitions
   * @throws IllegalArgumentException       if {@code entryPointPath} or an included module is not found in {@code workspace}
   * @throws CyclicDependencyException      if a circular include dependency is detected
   * @throws DuplicateModuleImportException if a document imports the same module multiple times
   * @throws NamespaceCollisionException    if conflicting unmitigated definitions are imported
   * @throws MalformedSchemaException       if a semantic or structural schema error is encountered
   * @throws NullPointerException           if {@code workspace} or {@code entryPointPath} is {@code null}
   */
  public static String flatten(Map<String, String> workspace, String entryPointPath) {
    Objects.requireNonNull(workspace, "workspace must not be null");
    Objects.requireNonNull(entryPointPath, "entryPointPath must not be null");

    Map<String, String> normalizedWorkspace = new LinkedHashMap<>();
    for (var entry : workspace.entrySet()) {
      normalizedWorkspace.put(normalizePath(entry.getKey()), entry.getValue());
    }
    String normEntryPoint = normalizePath(entryPointPath);
    String rootContent = normalizedWorkspace.get(normEntryPoint);
    if (rootContent == null) {
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
        throw new MalformedSchemaException(
            "STVN Syntax Error at line " + line + ":" + charPositionInLine + " - " + msg,
            e
        );
      }
    };

    var lexer = new StvnLexer(CharStreams.fromString(rootContent));
    lexer.removeErrorListeners();
    lexer.addErrorListener(errorListener);
    var parser = new StvnParser(new CommonTokenStream(lexer));
    parser.removeErrorListeners();
    parser.addErrorListener(errorListener);
    var docCtx = parser.stvnDocument();

    DiagnosticBag bag = new DiagnosticBag(32);
    StvnDefsLowerer.DefsLoweringResult lowered = StvnDefsLowerer.lowerForFlattener(
        docCtx,
        normEntryPoint,
        normalizedWorkspace,
        bag
    );

    if (bag.hasErrors()) {
      for (StvnDiagnostic diag : bag.toList()) {
        if (diag.severity() != DiagnosticSeverity.ERROR) {
          continue;
        }
        Throwable cause = diag.cause();
        if (cause instanceof CyclicDependencyException cde) {
          throw cde;
        }
        if (cause instanceof DuplicateModuleImportException dmie) {
          throw dmie;
        }
        if (cause instanceof NamespaceCollisionException nce) {
          throw nce;
        }
        if (cause instanceof IllegalArgumentException iae) {
          throw iae;
        }
        if (cause instanceof MalformedSchemaException mse) {
          throw mse;
        }
      }
      StvnDiagnostic firstErr = bag.toList().stream()
          .filter(d -> d.severity() == DiagnosticSeverity.ERROR)
          .findFirst()
          .orElseThrow();
      throw new MalformedSchemaException(firstErr.message(), firstErr.cause());
    }

    StvnDefsCatalog prelude = StvnPrelude.preludeCatalog();
    TreeMap<String, String> sortedDefinitions = new TreeMap<>();

    for (var constEntry : lowered.defs().constants().entrySet()) {
      String constName = constEntry.getKey();
      if (prelude.constants().containsKey(constName)) {
        continue;
      }
      sortedDefinitions.put(constName, formatCanonicalConstant(constName, constEntry.getValue()));
    }

    for (var typeEntry : lowered.defs().nominalTypes().entrySet()) {
      String typeName = typeEntry.getKey();
      if (prelude.nominalTypes().containsKey(typeName)) {
        continue;
      }
      sortedDefinitions.put(typeName, formatCanonicalType(typeName, typeEntry.getValue()));
    }

    try {
      StringWriter sw = new StringWriter();
      CanonicalLayoutWriter layout = new CanonicalLayoutWriter(sw);
      layout.openGroup(StvnVocabulary.DELIM_OPEN_BRACE);

      layout.writeLiteral(StvnVocabulary.KEYWORD_META);
      layout.openGroup(StvnVocabulary.DELIM_OPEN_BRACE);
      if (lowered.meta().isPresent()) {
        StvnDocumentMeta meta = lowered.meta().get();
        if (meta.name().isPresent()) {
          layout.writeLiteral(StvnVocabulary.FACET_KW_META_NAME);
          layout.writeSimpleString(meta.name().get());
        }
        if (meta.domain().isPresent()) {
          layout.writeLiteral(StvnVocabulary.FACET_KW_META_DOMAIN);
          layout.writeSimpleString(meta.domain().get());
        }
      }
      layout.writeLiteral(StvnVocabulary.FACET_KW_META_KIND);
      layout.writeLiteral(StvnVocabulary.KIND_KW_DEFS);
      layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACE);

      layout.writeLiteral(StvnVocabulary.KEYWORD_DEFS);
      layout.openGroup(StvnVocabulary.DELIM_OPEN_BRACE);
      for (String renderedDef : sortedDefinitions.values()) {
        layout.writeLiteral(renderedDef);
      }
      layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACE);

      layout.closeGroup(StvnVocabulary.DELIM_CLOSE_BRACE);
      layout.flush();
      return sw.toString();
    } catch (IOException e) {
      throw new RuntimeException("Unreachable IOException in StringWriter", e);
    }
  }

  private static String formatCanonicalConstant(String constName, StvnDefsCatalog.StvnConstant constant) {
    try {
      StringWriter sw = new StringWriter();
      CanonicalLayoutWriter layout = new CanonicalLayoutWriter(sw);
      CanonicalStvnWriter.writeConstantDefinition(constName, constant, layout);
      layout.flush();
      return sw.toString();
    } catch (IOException e) {
      throw new RuntimeException("Unreachable IOException in StringWriter", e);
    }
  }

  private static String formatCanonicalType(String typeName, StvnSchema schema) {
    try {
      StringWriter sw = new StringWriter();
      CanonicalLayoutWriter layout = new CanonicalLayoutWriter(sw);
      layout.writeLiteral(typeName);
      CanonicalStvnWriter.writeSchemaDefinitionBody(schema, layout);
      layout.flush();
      return sw.toString();
    } catch (IOException e) {
      throw new RuntimeException("Unreachable IOException in StringWriter", e);
    }
  }
}
