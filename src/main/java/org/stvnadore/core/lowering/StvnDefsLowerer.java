package org.stvnadore.core.lowering;

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.ir.StvnDefsCatalog;
import org.stvnadore.core.ir.StvnDocumentKind;
import org.stvnadore.core.ir.StvnDocumentMeta;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnSchema.BoundKind;
import org.stvnadore.core.ir.StvnSchema.ContinuousBound;
import org.stvnadore.core.ir.StvnSchema.DateTimeMode;
import org.stvnadore.core.ir.StvnSchema.FloatRepresentation;
import org.stvnadore.core.ir.StvnSchema.TemporalScale;
import org.stvnadore.core.ir.StvnSchema.TraitProfile;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.lowering.StvnPayloadLowerer.Span;
import org.stvnadore.core.parser.StvnErrorListener;
import org.stvnadore.core.parser.StvnErrorStrategy;
import org.stvnadore.core.parser.StvnLexer;
import org.stvnadore.core.parser.StvnParser;
import org.stvnadore.core.parser.StvnSyntaxErrorSanitizer;
import org.stvnadore.core.stdlib.StvnPrelude;
import org.stvnadore.core.validation.CircularReferenceException;
import org.stvnadore.core.validation.CyclicDependencyException;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.core.validation.DuplicateModuleImportException;
import org.stvnadore.core.validation.MalformedSchemaException;
import org.stvnadore.core.validation.NamespaceCollisionException;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Pass 2 stateless definitions and metadata lowerer for the STVN 2.0.0 4-pass lowering pipeline.
 * <p>
 * Lowers {@code :meta}, {@code :defs}, and root {@code :type} clauses from an ANTLR parse tree into
 * pure {@link StvnDocumentMeta}, {@link StvnDefsCatalog}, and {@link StvnSchema} IR nodes using an
 * isolated per-compilation session without mutable static caches.
 * <p>
 * Enforces:
 * <ul>
 *   <li>Document identity, stem cardinality ({@code 1 <= |stems| <= 2}), and section eligibility ({@code E031}–{@code E034}, {@code E038}, {@code E039}, {@code W001}, {@code W002})</li>
 *   <li>Reserved {@code :org/stvnadore/*} namespaces ({@code E037}), hermetic {@code :use} isolation ({@code E035}), module DAG cycle checks ({@code E005}, {@code E007}), and Appendix F Scenarios 1–6 namespace eviction ({@code E006})</li>
 *   <li>All 94 rows of the § 6.1 10-column metadata facet matrix, 5-tier monotonic ordering, Tier-1 immutability across nominal hops ({@code E040}), and § 4.8 enum subset narrowing ({@code E014}–{@code E016})</li>
 *   <li>{@code W003} ({@code WARN_REDUNDANT_METADATA_FACET}) tautology purging with symbolic {@code O(1)} bit-width bound checks</li>
 *   <li>{@link StvnSchema.PoisonedSchema} sentinel registration to prevent downstream {@code E030} cascades</li>
 * </ul>
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnDefsLowerer {

  private static final String RESERVED_DOMAIN_PREFIX = "stvn_";
  private static final String RESERVED_PKG_ROOT = StvnVocabulary.SIGIL_TYPIC + "org/stvnadore";
  private static final String PRELUDE_PKG_ROOT = StvnVocabulary.SIGIL_TYPIC + "org/stvnadore/prelude";
  private static final Pattern POSIX_META_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
  private static final BigDecimal IEEE_32_MAX_FINITE = new BigDecimal(String.valueOf(Float.MAX_VALUE));

  private static final List<String> PERMANENTLY_EXCISED_EXTENSIONS = List.of(
      ".stvn_incl", ".stvn_inclf", ".stvn_cas", ".stvn_bin",
      ".stvn_f", ".stvn_df", ".stvn_bf", ".stvn_bdf"
  );

  /**
   * Immutable result of Pass 2 lowering for a single STVN document.
   *
   * @param meta                 the lowered {@code :meta} block, if declared
   * @param defs                 the lowered definitions catalog containing nominal types and constants
   * @param rootType             the lowered root {@code :type} schema, if declared
   * @param hasDefsSection       {@code true} when an explicit {@code :defs} section appeared in source
   * @param hasIncludes          {@code true} when at least one {@code :include} statement appeared in {@code :defs}
   * @param rootConstantResolver scoped constant lookup callback for Pass 3 root {@code :body} lowering
   * @param nominalTypeLookup    scoped nominal schema lookup callback for guarded recursive types in Pass 3
   * @param sectionsEligible     {@code true} when top-level clauses satisfy document kind section eligibility
   * @param definitionSpans      source coordinate spans of locally declared types and constants for {@code E029} pinning
   * @since 2.0.0
   */
  public record DefsLoweringResult(
      Optional<StvnDocumentMeta> meta,
      StvnDefsCatalog defs,
      Optional<StvnSchema> rootType,
      boolean hasDefsSection,
      boolean hasIncludes,
      StvnPayloadLowerer.ConstantResolver rootConstantResolver,
      StvnPayloadLowerer.NominalSchemaResolver nominalTypeLookup,
      boolean sectionsEligible,
      Map<String, Span> definitionSpans
  ) {
    /**
     * Canonical constructor verifying non-null components.
     *
     * @param meta                 lowered metadata
     * @param defs                 lowered definitions catalog
     * @param rootType             lowered root payload schema
     * @param hasDefsSection       whether {@code :defs} is present
     * @param hasIncludes          whether {@code :include} is present
     * @param rootConstantResolver root scope constant resolver
     * @param nominalTypeLookup    nominal schema lookup for recursive types
     * @param sectionsEligible     whether top-level sections are eligible for the effective document kind
     * @param definitionSpans      source coordinate spans of locally declared definitions
     */
    public DefsLoweringResult {
      Objects.requireNonNull(meta, "meta must not be null");
      Objects.requireNonNull(defs, "defs must not be null");
      Objects.requireNonNull(rootType, "rootType must not be null");
      Objects.requireNonNull(rootConstantResolver, "rootConstantResolver must not be null");
      Objects.requireNonNull(nominalTypeLookup, "nominalTypeLookup must not be null");
      Objects.requireNonNull(definitionSpans, "definitionSpans must not be null");
      definitionSpans = Collections.unmodifiableMap(new LinkedHashMap<>(definitionSpans));
    }

    /**
     * Convenience constructor defaulting {@code definitionSpans} to an empty map.
     *
     * @param meta                 lowered metadata
     * @param defs                 lowered definitions catalog
     * @param rootType             lowered root payload schema
     * @param hasDefsSection       whether {@code :defs} is present
     * @param hasIncludes          whether {@code :include} is present
     * @param rootConstantResolver root scope constant resolver
     * @param nominalTypeLookup    nominal schema lookup for recursive types
     * @param sectionsEligible     whether top-level sections are eligible for the effective document kind
     */
    public DefsLoweringResult(
        Optional<StvnDocumentMeta> meta,
        StvnDefsCatalog defs,
        Optional<StvnSchema> rootType,
        boolean hasDefsSection,
        boolean hasIncludes,
        StvnPayloadLowerer.ConstantResolver rootConstantResolver,
        StvnPayloadLowerer.NominalSchemaResolver nominalTypeLookup,
        boolean sectionsEligible
    ) {
      this(meta, defs, rootType, hasDefsSection, hasIncludes, rootConstantResolver, nominalTypeLookup, sectionsEligible, Map.of());
    }
  }

  private StvnDefsLowerer() {
    // Non-instantiable stateless pass
  }

  /**
   * Parses raw STVN text into an ANTLR {@link StvnParser.StvnDocumentContext} root node using the
   * specified parser configuration.
   * <p>
   * Encapsulated inside {@code org.stvnadore.core.lowering} for intra-module lowering callers and
   * whitebox unit tests without exposing ANTLR types on exported public API surfaces.
   *
   * @param input  the raw STVN document text to parse
   * @param config the parser configuration defining error handling and strictness
   * @return the parsed {@link StvnParser.StvnDocumentContext} parse tree root
   * @throws NullPointerException                                            if {@code input} or {@code config} is {@code null}
   * @throws org.stvnadore.core.validation.StvnSyntaxCancellationException if {@code config.strict()} is {@code true} and a syntax error occurs
   */
  public static StvnParser.StvnDocumentContext parseDocument(String input, StvnParserConfig config) {
    Objects.requireNonNull(input, "input must not be null");
    Objects.requireNonNull(config, "config must not be null");

    var lexer = new StvnLexer(CharStreams.fromString(input));
    lexer.removeErrorListeners();

    var tokens = new CommonTokenStream(lexer);
    var parser = new StvnParser(tokens);
    parser.removeErrorListeners();

    if (config.strict()) {
      parser.setErrorHandler(new BailErrorStrategy());
      var errorListener = StvnErrorListener.strict();
      lexer.addErrorListener(errorListener);
      parser.addErrorListener(errorListener);
    } else {
      parser.setErrorHandler(new StvnErrorStrategy());
    }

    StvnParser.StvnDocumentContext docCtx = parser.stvnDocument();
    if (config.strict()) {
      StvnSyntaxErrorSanitizer.scanMalformedFenceTokens(tokens, parser, null, true);
      validateDocumentBodyStructure(docCtx, null, true);
    }
    return docCtx;
  }

  /**
   * Evaluates whether a package or type path targets the reserved runtime namespace ({@code :org/stvnadore/*}).
   *
   * @param pkgPath the package or type path to evaluate, or {@code null}
   * @return {@code true} if reserved; {@code false} otherwise
   */
  public static boolean isReservedPackage(@Nullable String pkgPath) {
    if (pkgPath == null) {
      return false;
    }
    String normalized = pkgPath.startsWith(StvnVocabulary.SIGIL_TYPIC)
        ? pkgPath
        : StvnVocabulary.SIGIL_TYPIC + pkgPath;
    return normalized.equals(RESERVED_PKG_ROOT) || normalized.startsWith(RESERVED_PKG_ROOT + "/");
  }

  /**
   * Evaluates whether a domain identifier begins with the reserved system prefix ({@code "stvn_"}).
   *
   * @param domain the domain identifier to evaluate, or {@code null}
   * @return {@code true} if reserved; {@code false} otherwise
   */
  public static boolean isReservedDomain(@Nullable String domain) {
    return domain != null && domain.startsWith(RESERVED_DOMAIN_PREFIX);
  }

  /**
   * Evaluates whether a constant identifier targets the reserved runtime namespace ({@code #org/stvnadore/*}).
   *
   * @param constName the constant identifier to evaluate, or {@code null}
   * @return {@code true} if reserved; {@code false} otherwise
   */
  public static boolean isReservedConstant(@Nullable String constName) {
    if (constName == null) {
      return false;
    }
    String valRoot = StvnVocabulary.SIGIL_VALUE + "org/stvnadore";
    return constName.equals(valRoot)
        || constName.startsWith(valRoot + "/")
        || constName.equals(RESERVED_PKG_ROOT)
        || constName.startsWith(RESERVED_PKG_ROOT + "/")
        || constName.equals("org/stvnadore")
        || constName.startsWith("org/stvnadore/");
  }

  /**
   * Slices a slash-delimited nominal identifier to its terminal segment while preserving its leading sigil.
   *
   * @param identifier the full nominal type or constant identifier
   * @return the terminal identifier preserving {@code :} or {@code #}
   */
  public static String sliceTerminal(String identifier) {
    int lastSlash = identifier.lastIndexOf('/');
    if (lastSlash < 0) {
      return identifier;
    }
    return identifier.charAt(0) + identifier.substring(lastSlash + 1);
  }

  /**
   * Checks whether {@code name} matches a reserved fundamental STVN type or clause keyword.
   *
   * @param name the identifier to check
   * @return {@code true} if reserved; {@code false} otherwise
   */
  public static boolean isReservedFundamentalKeyword(String name) {
    return name.equals(StvnVocabulary.TYPE_BOOLEAN)
        || name.equals(StvnVocabulary.TYPE_INT)
        || name.equals(StvnVocabulary.TYPE_FLOAT)
        || name.equals(StvnVocabulary.TYPE_STRING)
        || name.equals(StvnVocabulary.TYPE_TIME_EPOCH)
        || name.equals(StvnVocabulary.TYPE_DATE_TIME)
        || name.equals(StvnVocabulary.TYPE_TUPLE)
        || name.equals(StvnVocabulary.TYPE_ENUM)
        || name.equals(StvnVocabulary.TYPE_OPTION)
        || name.equals(StvnVocabulary.TYPE_EITHER)
        || name.equals(StvnVocabulary.TYPE_UNION)
        || name.equals(StvnVocabulary.TYPE_SEQ)
        || name.equals(StvnVocabulary.TYPE_SET)
        || name.equals(StvnVocabulary.TYPE_MAP)
        || name.equals(StvnVocabulary.KEYWORD_META)
        || name.equals(StvnVocabulary.KEYWORD_DEFS)
        || name.equals(StvnVocabulary.KEYWORD_TYPE)
        || name.equals(StvnVocabulary.KEYWORD_BODY)
        || name.equals(StvnVocabulary.KEYWORD_INCLUDE)
        || name.equals(StvnVocabulary.KEYWORD_PACKAGE)
        || name.equals(StvnVocabulary.KEYWORD_USE);
  }

  /**
   * Bootstraps the standard library prelude document without recursively accessing {@link StvnPrelude#preludeCatalog()}.
   *
   * @param doc           the parsed prelude ANTLR context
   * @param diagnosticBag the diagnostic accumulator
   * @return the lowered prelude definitions result
   */
  public static DefsLoweringResult lowerPreludeBootstrap(
      StvnParser.StvnDocumentContext doc,
      DiagnosticBag diagnosticBag
  ) {
    Objects.requireNonNull(doc, "doc must not be null");
    Objects.requireNonNull(diagnosticBag, "diagnosticBag must not be null");
    ModuleCompilationContext moduleCtx = new ModuleCompilationContext(StvnParserConfig.SYSTEM_STRICT, true);
    return lowerDocumentUnit(doc, null, moduleCtx, diagnosticBag, false, false, false).toPublicResult();
  }

  /**
   * Lowers the {@code :meta}, {@code :defs}, and root {@code :type} sections of a parsed STVN document.
   *
   * @param doc           the parsed ANTLR document context
   * @param docPath       the optional physical file path or URI for identity and relative include resolution
   * @param config        the active parser configuration
   * @param diagnosticBag the diagnostic accumulator
   * @return the lowered {@link DefsLoweringResult}
   */
  public static DefsLoweringResult lowerDocumentHeaderAndDefs(
      StvnParser.StvnDocumentContext doc,
      @Nullable String docPath,
      StvnParserConfig config,
      DiagnosticBag diagnosticBag
  ) {
    return lowerDocumentHeaderAndDefs(doc, docPath, config, diagnosticBag, false);
  }

  /**
   * Lowers the {@code :meta}, {@code :defs}, and root {@code :type} sections of a parsed STVN document
   * with explicit Pass 1 syntax-truncation anti-cascade awareness.
   *
   * @param doc                     the parsed ANTLR document context
   * @param docPath                 the optional physical file path or URI for identity and relative include resolution
   * @param config                  the active parser configuration
   * @param diagnosticBag           the diagnostic accumulator
   * @param unitHasPass1SyntaxError {@code true} if Pass 1 recorded a lexical or syntactic error on this unit
   * @return the lowered {@link DefsLoweringResult}
   * @since 2.0.0
   */
  public static DefsLoweringResult lowerDocumentHeaderAndDefs(
      StvnParser.StvnDocumentContext doc,
      @Nullable String docPath,
      StvnParserConfig config,
      DiagnosticBag diagnosticBag,
      boolean unitHasPass1SyntaxError
  ) {
    return lowerDocumentHeaderAndDefsInternal(doc, docPath, config, diagnosticBag, false, false, unitHasPass1SyntaxError);
  }

  /**
   * Lowers the embedded textual STVN definitions payload of a {@code .stvn_bd} binary frame
   * strictly as a flat hermetic {@code #DEFS} compilation unit.
   * <p>
   * Skips duplicate outer filename-vs-{@code :meta} checks on the top-level embedded payload
   * (because {@link #validateBinaryDocumentIdentity(StvnDocumentMeta, StvnBinaryDecoder.BinaryHeaderSpans, String, StvnParserConfig, DiagnosticBag)}
   * already binds {@code docPath} to Byte 5+ header byte slices), and enforces the Hermetic Binary Invariant
   * by rejecting {@code :include} directives ({@code E005}) and external non-prelude {@code :use} targets ({@code E035})
   * even when {@code docPath} is {@code null}.
   *
   * @param doc           the parsed ANTLR document context of the embedded definitions text
   * @param docPath       the optional physical file path or URI of the enclosing {@code .stvn_bd} container
   * @param config        the active parser configuration
   * @param diagnosticBag the diagnostic accumulator
   * @return the lowered {@link DefsLoweringResult}
   * @since 2.0.0
   */
  public static DefsLoweringResult lowerEmbeddedBinaryDefs(
      StvnParser.StvnDocumentContext doc,
      @Nullable String docPath,
      StvnParserConfig config,
      DiagnosticBag diagnosticBag
  ) {
    return lowerEmbeddedBinaryDefs(doc, docPath, config, diagnosticBag, false);
  }

  /**
   * Lowers the embedded textual STVN definitions payload of a {@code .stvn_bd} binary frame
   * strictly as a flat hermetic {@code #DEFS} compilation unit
   * with explicit Pass 1 syntax-truncation anti-cascade awareness.
   *
   * @param doc                     the parsed ANTLR document context of the embedded definitions text
   * @param docPath                 the optional physical file path or URI of the enclosing {@code .stvn_bd} container
   * @param config                  the active parser configuration
   * @param diagnosticBag           the diagnostic accumulator
   * @param unitHasPass1SyntaxError {@code true} if Pass 1 recorded a lexical or syntactic error on the embedded payload
   * @return the lowered {@link DefsLoweringResult}
   * @since 2.0.0
   */
  public static DefsLoweringResult lowerEmbeddedBinaryDefs(
      StvnParser.StvnDocumentContext doc,
      @Nullable String docPath,
      StvnParserConfig config,
      DiagnosticBag diagnosticBag,
      boolean unitHasPass1SyntaxError
  ) {
    return lowerDocumentHeaderAndDefsInternal(doc, docPath, config, diagnosticBag, true, true, unitHasPass1SyntaxError);
  }

  private static DefsLoweringResult lowerDocumentHeaderAndDefsInternal(
      StvnParser.StvnDocumentContext doc,
      @Nullable String docPath,
      StvnParserConfig config,
      DiagnosticBag diagnosticBag,
      boolean skipTopLevelPathIdentityChecks,
      boolean embeddedBinaryDefsMode,
      boolean unitHasPass1SyntaxError
  ) {
    Objects.requireNonNull(doc, "doc must not be null");
    Objects.requireNonNull(config, "config must not be null");
    Objects.requireNonNull(diagnosticBag, "diagnosticBag must not be null");
    ModuleCompilationContext moduleCtx = new ModuleCompilationContext(config, false);
    if (docPath != null && !docPath.isEmpty()) {
      try {
        Path norm = Paths.get(docPath).toAbsolutePath().normalize();
        moduleCtx.activeCanonicalPaths.add(norm);
        moduleCtx.activeRawPaths.add(docPath);
      } catch (Exception ignored) {
        // Non-filesystem virtual URI; skip canonical stack registration
      }
    }
    return lowerDocumentUnit(
        doc, docPath, moduleCtx, diagnosticBag, skipTopLevelPathIdentityChecks, embeddedBinaryDefsMode, unitHasPass1SyntaxError
    ).toPublicResult();
  }

  /**
   * Lowers the {@code :meta} and {@code :defs} sections of a parsed STVN document against an
   * in-memory virtual workspace for {@link org.stvnadore.core.StvnSchemaFlattener}.
   *
   * @param doc                        the parsed ANTLR root document context
   * @param rootVirtualPath            the POSIX-normalized virtual root file path
   * @param normalizedVirtualWorkspace map of POSIX-normalized virtual paths to raw STVN contents
   * @param diagnosticBag              the diagnostic accumulator
   * @return the lowered {@link DefsLoweringResult}
   */
  public static DefsLoweringResult lowerForFlattener(
      StvnParser.StvnDocumentContext doc,
      String rootVirtualPath,
      Map<String, String> normalizedVirtualWorkspace,
      DiagnosticBag diagnosticBag
  ) {
    Objects.requireNonNull(doc, "doc must not be null");
    Objects.requireNonNull(rootVirtualPath, "rootVirtualPath must not be null");
    Objects.requireNonNull(normalizedVirtualWorkspace, "normalizedVirtualWorkspace must not be null");
    Objects.requireNonNull(diagnosticBag, "diagnosticBag must not be null");

    ModuleCompilationContext moduleCtx = new ModuleCompilationContext(
        StvnParserConfig.DEFAULT, false, normalizedVirtualWorkspace, true
    );
    moduleCtx.activeVirtualPaths.add(rootVirtualPath);
    moduleCtx.activeRawPaths.add(rootVirtualPath);
    return lowerDocumentUnit(doc, rootVirtualPath, moduleCtx, diagnosticBag, false, false, false).toPublicResult();
  }

  /**
   * Lowers a standalone {@link StvnParser.SchemaTypeContext} against an already finalized {@link StvnDefsCatalog}.
   *
   * @param schemaCtx     the parsed schema type context
   * @param catalog       the definitions catalog for resolving nominal references
   * @param diagnosticBag the diagnostic accumulator
   * @return the lowered {@link StvnSchema} (or {@link StvnSchema.PoisonedSchema} on validation failure)
   */
  public static StvnSchema lowerStandaloneSchema(
      StvnParser.SchemaTypeContext schemaCtx,
      StvnDefsCatalog catalog,
      DiagnosticBag diagnosticBag
  ) {
    Objects.requireNonNull(schemaCtx, "schemaCtx must not be null");
    Objects.requireNonNull(catalog, "catalog must not be null");
    Objects.requireNonNull(diagnosticBag, "diagnosticBag must not be null");

    ModuleCompilationContext moduleCtx = new ModuleCompilationContext(StvnParserConfig.DEFAULT, false);
    UnitEnvironment env = UnitEnvironment.fromStandaloneCatalog(catalog, moduleCtx, diagnosticBag);
    return env.lowerAnonymousOrRootSchema(schemaCtx, ScopeFrame.EMPTY, new LinkedHashSet<>(), true).schema();
  }

  /**
   * Validates document identity metadata decoded from an STVN binary payload ({@code .stvn_b}, {@code .stvn_bd})
   * against its physical filename contract under {@link StvnParserConfig#DEFAULT} and {@link StvnBinaryDecoder.BinaryHeaderSpans#DEFAULT}.
   *
   * @param meta          the decoded IR metadata record, or {@code null} if absent
   * @param docPath       the optional physical file path or URI
   * @param diagnosticBag the diagnostic accumulator
   */
  public static void validateBinaryDocumentIdentity(
      @Nullable StvnDocumentMeta meta,
      @Nullable String docPath,
      DiagnosticBag diagnosticBag
  ) {
    validateBinaryDocumentIdentity(
        meta,
        StvnBinaryDecoder.BinaryHeaderSpans.DEFAULT,
        docPath,
        StvnParserConfig.DEFAULT,
        diagnosticBag
    );
  }

  /**
   * Validates document identity metadata decoded from an STVN binary payload ({@code .stvn_b}, {@code .stvn_bd})
   * against its physical filename contract and system-authority reserved domain rules, pinning all header
   * diagnostics to their exact binary header byte spans.
   *
   * @param meta          the decoded IR metadata record, or {@code null} if absent
   * @param headerSpans   the exact byte-offset spans of Byte 5, {@code #name}, and {@code #domain} in the binary header
   * @param docPath       the optional physical file path or URI
   * @param config        the parser configuration carrying system authority state
   * @param diagnosticBag the diagnostic accumulator
   * @since 2.0.0
   */
  public static void validateBinaryDocumentIdentity(
      @Nullable StvnDocumentMeta meta,
      StvnBinaryDecoder.BinaryHeaderSpans headerSpans,
      @Nullable String docPath,
      StvnParserConfig config,
      DiagnosticBag diagnosticBag
  ) {
    Objects.requireNonNull(headerSpans, "headerSpans must not be null");
    Objects.requireNonNull(config, "config must not be null");
    Objects.requireNonNull(diagnosticBag, "diagnosticBag must not be null");

    StvnDocumentMeta.SourceSpan ctrlSpan = headerSpans.metaControlSpan();
    StvnDocumentMeta.SourceSpan nameSpan = headerSpans.nameSpan().orElse(ctrlSpan);
    StvnDocumentMeta.SourceSpan domainSpan = headerSpans.domainSpan().orElse(ctrlSpan);

    String ext = null;
    String[] dotParts = null;
    boolean fatalPathRejection = false;

    if (docPath != null && !docPath.isEmpty()) {
      String normalized = docPath.replace('\\', '/');
      int lastSlash = normalized.lastIndexOf('/');
      String fileName = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;

      if (rejectLegacyExtension(
          fileName,
          diagnosticBag,
          ctrlSpan.startOffset(),
          ctrlSpan.endOffset(),
          ctrlSpan.line(),
          ctrlSpan.column()
      )) {
        fatalPathRejection = true;
      } else if (fileName.startsWith(".")) {
        diagnosticBag.addError(
            "Filename '" + fileName + "' lacks a visible non-empty stem segment before extension; bare dotfiles are prohibited",
            ctrlSpan.startOffset(), ctrlSpan.endOffset(), ctrlSpan.line(), ctrlSpan.column(), null,
            DiagnosticBag.ERR_INVALID_FILENAME_STEM
        );
        fatalPathRejection = true;
      } else {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot >= 0) {
          ext = fileName.substring(lastDot);
          String stemPart = fileName.substring(0, lastDot);
          dotParts = stemPart.split("\\.", -1);
          if (dotParts.length >= 3) {
            diagnosticBag.addError(
                "Invalid filename stem '" + fileName
                    + "'; filename must contain exactly one or two dot-separated stem segments (<name>.<ext> or <name>.<domain>.<ext>)",
                ctrlSpan.startOffset(), ctrlSpan.endOffset(), ctrlSpan.line(), ctrlSpan.column(), null,
                DiagnosticBag.ERR_INVALID_FILENAME_STEM
            );
            fatalPathRejection = true;
          }
        }
      }
    }

    Optional<StvnDocumentKind> expectedKindOpt =
        ext != null ? StvnDocumentKind.fromExtension(ext) : Optional.empty();

    if (meta == null) {
      if (!fatalPathRejection && expectedKindOpt.isPresent()
          && (expectedKindOpt.get() == StvnDocumentKind.DEFS || ".stvn_b".equals(ext))) {
        diagnosticBag.addError(
            "Mandatory :meta block missing; document kind '" + expectedKindOpt.get().keyword()
                + "' is required for extension '" + ext + "'",
            ctrlSpan.startOffset(), ctrlSpan.endOffset(), ctrlSpan.line(), ctrlSpan.column(), null,
            DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH
        );
      }
      return;
    }

    if (!config.systemAuthority() && meta.domain().isPresent() && isReservedDomain(meta.domain().get())) {
      String declaredDomain = meta.domain().get();
      diagnosticBag.addError(
          "Reserved system domain prohibited: User document cannot declare reserved system domain '" + declaredDomain
              + "'; domains with prefix 'stvn_' are strictly reserved for system artifacts",
          domainSpan.startOffset(), domainSpan.endOffset(), domainSpan.line(), domainSpan.column(), null,
          DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED
      );
    }

    if (fatalPathRejection) {
      return;
    }

    StvnDocumentKind declaredKind = meta.kind().orElse(null);
    if (declaredKind == StvnDocumentKind.BODY_INCLUDE || declaredKind == StvnDocumentKind.DEFS_INCLUDE) {
      diagnosticBag.addError(
          "Binary document header cannot declare modular kind '" + declaredKind.keyword()
              + "'; binary frames (.stvn_b, .stvn_bd) are strictly hermetic (#BODY or #DEFS)",
          ctrlSpan.startOffset(), ctrlSpan.endOffset(), ctrlSpan.line(), ctrlSpan.column(), null,
          DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH
      );
    } else if (declaredKind != null && expectedKindOpt.isPresent() && declaredKind != expectedKindOpt.get()) {
      diagnosticBag.addError(
          "Document kind '" + declaredKind.keyword() + "' does not match file extension '" + ext
              + "' (expected '" + expectedKindOpt.get().keyword() + "')",
          ctrlSpan.startOffset(), ctrlSpan.endOffset(), ctrlSpan.line(), ctrlSpan.column(), null,
          DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH
      );
    }

    if (dotParts != null && dotParts.length >= 1) {
      String stemName = dotParts[0];
      if (meta.name().isPresent() && !meta.name().get().equals(stemName)) {
        diagnosticBag.addWarning(
            "Declared #name '" + meta.name().get() + "' does not match filename stem slot 0 '" + stemName + "'",
            nameSpan.startOffset(), nameSpan.endOffset(), nameSpan.line(), nameSpan.column(),
            DiagnosticBag.WARN_DOCUMENT_NAME_MISMATCH
        );
      }
      if (meta.domain().isPresent()) {
        String declaredDomain = meta.domain().get();
        if (dotParts.length == 1) {
          diagnosticBag.addWarning(
              "Declared #domain '" + declaredDomain + "' is omitted from filename stem slot 1",
              domainSpan.startOffset(), domainSpan.endOffset(), domainSpan.line(), domainSpan.column(),
              DiagnosticBag.WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME
          );
        } else if (!declaredDomain.equals(dotParts[1])) {
          diagnosticBag.addWarning(
              "Declared #domain '" + declaredDomain + "' does not match filename stem slot 1 '" + dotParts[1] + "'",
              domainSpan.startOffset(), domainSpan.endOffset(), domainSpan.line(), domainSpan.column(),
              DiagnosticBag.WARN_DOCUMENT_NAME_MISMATCH
          );
        }
      } else if (dotParts.length == 2 && !dotParts[1].isEmpty() && !isReservedDomain(dotParts[1])) {
        diagnosticBag.addWarning(
            "Filename stem slot 1 declares domain '" + dotParts[1]
                + "', but :meta omits #domain \"" + dotParts[1] + "\"",
            ctrlSpan.startOffset(), ctrlSpan.endOffset(), ctrlSpan.line(), ctrlSpan.column(),
            DiagnosticBag.WARN_DOCUMENT_DOMAIN_OMITTED_IN_META
        );
      }
    }
  }

  private static boolean rejectLegacyExtension(
      String fileName,
      DiagnosticBag diagnosticBag,
      int startIndex,
      int stopIndex,
      int line,
      int column
  ) {
    for (String legacyExt : PERMANENTLY_EXCISED_EXTENSIONS) {
      if (fileName.endsWith(legacyExt)) {
        int dotIdx = fileName.indexOf('.');
        String matchedExt = dotIdx >= 0 ? fileName.substring(dotIdx) : fileName;
        diagnosticBag.addError(
            "Excised legacy file extension '" + matchedExt + "' is permanently prohibited",
            startIndex, stopIndex, line, column, null,
            DiagnosticBag.ERR_LEGACY_FILE_EXTENSION_PURGED
        );
        return true;
      }
    }
    return false;
  }

  // ===========================================================================
  // INTERNAL PER-COMPILATION SESSION & MODULE TYPES
  // ===========================================================================

  private static final class ModuleCompilationContext {
    final StvnParserConfig config;
    final boolean bootstrapPreludeMode;
    final @Nullable Map<String, String> virtualWorkspace;
    final boolean flattenerMode;
    final List<Path> activeCanonicalPaths = new ArrayList<>();
    final List<String> activeVirtualPaths = new ArrayList<>();
    final List<String> activeRawPaths = new ArrayList<>();
    final Map<Path, CompiledUnit> compiledModuleCache = new HashMap<>();
    final Map<String, CompiledUnit> compiledVirtualModuleCache = new HashMap<>();

    ModuleCompilationContext(StvnParserConfig config, boolean bootstrapPreludeMode) {
      this(config, bootstrapPreludeMode, null, false);
    }

    ModuleCompilationContext(
        StvnParserConfig config,
        boolean bootstrapPreludeMode,
        @Nullable Map<String, String> virtualWorkspace,
        boolean flattenerMode
    ) {
      this.config = config;
      this.bootstrapPreludeMode = bootstrapPreludeMode;
      this.virtualWorkspace = virtualWorkspace;
      this.flattenerMode = flattenerMode;
    }

    boolean isSystemAuthority() {
      return bootstrapPreludeMode || config.systemMode();
    }
  }

  /**
   * Ledger of Tier-1 Definition facets locked along a nominal derivation chain
   * prior to {@code W003} tautology stripping. Used to enforce {@code E040} ({@code ERR_NON_OVERRIDABLE_FACET})
   * and cross-hop mutual exclusivity ({@code E008}), including when a parent nominal alias relies on intrinsic defaults.
   */
  record AncestorFacetLedger(
      Optional<Boolean> lockedUnsigned,
      Optional<Boolean> lockedExact,
      Optional<Boolean> lockedInvertible,
      Optional<BigInteger> lockedSize,
      Optional<BigInteger> explicitMinSize,
      Optional<BigInteger> explicitMaxSize,
      Optional<TemporalScale> explicitScale,
      Optional<DateTimeMode> explicitMode
  ) {
    static final AncestorFacetLedger EMPTY = new AncestorFacetLedger(
        Optional.empty(), Optional.empty(), Optional.empty(),
        Optional.empty(), Optional.empty(), Optional.empty(),
        Optional.empty(), Optional.empty()
    );

    AncestorFacetLedger mergeChild(AncestorFacetLedger child) {
      return new AncestorFacetLedger(
          child.lockedUnsigned.isPresent() ? child.lockedUnsigned : this.lockedUnsigned,
          child.lockedExact.isPresent() ? child.lockedExact : this.lockedExact,
          child.lockedInvertible.isPresent() ? child.lockedInvertible : this.lockedInvertible,
          child.lockedSize.isPresent() ? child.lockedSize : this.lockedSize,
          child.explicitMinSize.isPresent() ? child.explicitMinSize : this.explicitMinSize,
          child.explicitMaxSize.isPresent() ? child.explicitMaxSize : this.explicitMaxSize,
          child.explicitScale.isPresent() ? child.explicitScale : this.explicitScale,
          child.explicitMode.isPresent() ? child.explicitMode : this.explicitMode
      );
    }
  }

  private record LoweredSchemaBundle(StvnSchema schema, AncestorFacetLedger ledger) {}

  private record ExportedTypeSymbol(
      String canonicalFqni,
      StvnSchema schema,
      AncestorFacetLedger ledger,
      String originModuleKey
  ) {}

  private record ExportedConstantSymbol(
      String canonicalFqni,
      StvnDefsCatalog.StvnConstant constant,
      String originModuleKey
  ) {}

  private record CompiledUnit(
      Optional<StvnDocumentMeta> meta,
      Optional<StvnDocumentKind> effectiveKind,
      StvnDefsCatalog catalog,
      Optional<StvnSchema> rootType,
      boolean hasDefsSection,
      boolean hasIncludes,
      boolean sectionsEligible,
      Map<String, ExportedTypeSymbol> exportedTypes,
      Map<String, ExportedConstantSymbol> exportedConstants,
      StvnPayloadLowerer.ConstantResolver rootConstantResolver,
      StvnPayloadLowerer.NominalSchemaResolver nominalTypeLookup,
      Map<String, Span> definitionSpans
  ) {
    DefsLoweringResult toPublicResult() {
      return new DefsLoweringResult(
          meta,
          catalog,
          rootType,
          hasDefsSection,
          hasIncludes,
          rootConstantResolver,
          nominalTypeLookup,
          sectionsEligible,
          definitionSpans
      );
    }
  }

  private enum ClaimKind {
    LOCAL,
    RAW_IMPORT,
    RENAMED_IMPORT_RHS
  }

  private record ScopeClaim<T>(
      String boundName,
      T payload,
      String originKey,
      ClaimKind kind
  ) {}

  private record ScopeFrame(
      Optional<String> packagePrefix,
      Map<String, String> typeAliases,
      Map<String, String> constAliases
  ) {
    static final ScopeFrame EMPTY = new ScopeFrame(Optional.empty(), Map.of(), Map.of());
  }

  private record LocalTypeAstDecl(
      String canonicalFqni,
      StvnParser.TypeDefinitionContext ctx,
      @Nullable String enclosingPackagePrefix,
      StvnParser.@Nullable PackageEnclosureContext packageCtx
  ) {}

  private record LocalConstantAstDecl(
      String canonicalFqni,
      String displayName,
      StvnParser.ConstantDefinitionContext ctx,
      @Nullable String enclosingPackagePrefix,
      StvnParser.@Nullable PackageEnclosureContext packageCtx
  ) {}
  // ===========================================================================
  // DOCUMENT HEADER, IDENTITY, MODULE DAG & SCOPE RESOLUTION
  // ===========================================================================

  private record IdentityOutcome(
      Optional<StvnDocumentMeta> meta,
      Optional<StvnDocumentKind> effectiveKind,
      @Nullable String fileName,
      @Nullable String fileExt
  ) {}

  private static boolean hasNoTopLevelClauses(StvnParser.StvnDocumentContext doc) {
    var docBody = doc.documentBody();
    return docBody == null
        || (docBody.metaEntry().isEmpty()
            && docBody.defsEntry().isEmpty()
            && docBody.typeEntry().isEmpty()
            && docBody.bodyEntry().isEmpty());
  }

  private static final String CANONICAL_SECTION_PROGRESSION =
      StvnVocabulary.KEYWORD_META + " -> " + StvnVocabulary.KEYWORD_DEFS + " -> "
          + StvnVocabulary.KEYWORD_TYPE + " -> " + StvnVocabulary.KEYWORD_BODY;

  private static String duplicateSectionMessage(String sectionKw) {
    return "Duplicate '" + sectionKw + "' section prohibited: document may declare at most one '"
        + sectionKw + "' section (canonical order: " + CANONICAL_SECTION_PROGRESSION + ")";
  }

  /**
   * Enforces STVN 2.0.0 § 1.1 top-level section multiplicity (at most one {@code :meta},
   * {@code :defs}, {@code :type}, and {@code :body} section per document) and canonical
   * progression ({@code :meta -> :defs -> :type -> :body}).
   *
   * @param doc           the parsed ANTLR document context
   * @param diagnosticBag the optional diagnostic bag for accumulating mode
   * @param strict        {@code true} to throw {@link org.stvnadore.core.validation.StvnSyntaxCancellationException} on the first violation
   * @return {@code true} if a duplicate or incomplete root section occurred
   */
  public static boolean validateDocumentBodyStructure(
      StvnParser.StvnDocumentContext doc,
      @Nullable DiagnosticBag diagnosticBag,
      boolean strict
  ) {
    return validateDocumentBodyStructureInternal(doc, diagnosticBag, strict, true);
  }

  /**
   * Validates root-section multiplicity and structural completeness for {@code StvnSourceFormatter},
   * rejecting duplicate or incomplete root sections while permitting unique complete root sections
   * in any permuted order so the formatter can canonicalize them into {@code :meta -> :defs -> :type -> :body}.
   *
   * @param doc the parsed ANTLR document context
   */
  public static void validateDocumentBodyStructureForFormatter(StvnParser.StvnDocumentContext doc) {
    validateDocumentBodyStructureInternal(doc, null, true, false);
  }

  private static boolean validateDocumentBodyStructureInternal(
      StvnParser.StvnDocumentContext doc,
      @Nullable DiagnosticBag diagnosticBag,
      boolean strict,
      boolean enforceCanonicalOrder
  ) {
    var body = doc.documentBody();
    if (body == null || body.children == null) {
      return false;
    }

    boolean seenMeta = false;
    boolean seenDefs = false;
    boolean seenType = false;
    boolean seenBody = false;
    int maxOrder = 0;
    boolean hasPoisoningSectionError = false;

    for (int childIdx = 0; childIdx < body.children.size(); childIdx++) {
      var child = body.children.get(childIdx);
      if (child instanceof StvnParser.MetaEntryContext metaCtx) {
        Token kw = metaCtx.KW_META() != null ? metaCtx.KW_META().getSymbol() : metaCtx.getStart();
        if (seenMeta) {
          reportTopLevelSectionViolation(
              kw,
              duplicateSectionMessage(StvnVocabulary.KEYWORD_META),
              "E033",
              DiagnosticBag.ERR_META_POSITION_INVALID,
              diagnosticBag,
              strict
          );
        } else {
          if (enforceCanonicalOrder && maxOrder > 1) {
            reportTopLevelSectionViolation(
                kw,
                "Misplaced '" + StvnVocabulary.KEYWORD_META + "' section prohibited: '"
                    + StvnVocabulary.KEYWORD_META + "' must appear before '"
                    + StvnVocabulary.KEYWORD_DEFS + "', '" + StvnVocabulary.KEYWORD_TYPE + "', and '"
                    + StvnVocabulary.KEYWORD_BODY + "' (canonical order: " + CANONICAL_SECTION_PROGRESSION + ")",
                "E033",
                DiagnosticBag.ERR_META_POSITION_INVALID,
                diagnosticBag,
                strict
            );
          }
          if (!enforceCanonicalOrder && (metaCtx.exception != null
              || metaCtx.metaBlock() == null
              || metaCtx.metaBlock().exception != null
              || metaCtx.metaBlock().LBRACE() == null
              || metaCtx.metaBlock().RBRACE() == null)) {
            reportTopLevelSectionViolation(
                kw,
                "Incomplete '" + StvnVocabulary.KEYWORD_META + "' section: declare a metadata block '{ ... }' after '"
                    + StvnVocabulary.KEYWORD_META + "'",
                "E002",
                DiagnosticBag.ERR_MALFORMED_SECTION_STRUCTURE,
                diagnosticBag,
                strict
            );
          }
        }
        seenMeta = true;
        maxOrder = Math.max(maxOrder, 1);
      } else if (child instanceof StvnParser.DefsEntryContext defsCtx) {
        Token kw = defsCtx.KW_DEFS() != null ? defsCtx.KW_DEFS().getSymbol() : defsCtx.getStart();
        if (seenDefs) {
          hasPoisoningSectionError = true;
          reportTopLevelSectionViolation(
              kw,
              duplicateSectionMessage(StvnVocabulary.KEYWORD_DEFS),
              "E002",
              DiagnosticBag.ERR_DUPLICATE_ROOT_SECTION,
              diagnosticBag,
              strict
          );
        } else {
          if (enforceCanonicalOrder && maxOrder > 2) {
            reportTopLevelSectionViolation(
                kw,
                "Misplaced '" + StvnVocabulary.KEYWORD_DEFS + "' section prohibited: '"
                    + StvnVocabulary.KEYWORD_DEFS + "' must appear before '"
                    + StvnVocabulary.KEYWORD_TYPE + "' and '" + StvnVocabulary.KEYWORD_BODY
                    + "' (canonical order: " + CANONICAL_SECTION_PROGRESSION + ")",
                "E002",
                DiagnosticBag.ERR_SECTION_ORDER_VIOLATION,
                diagnosticBag,
                strict
            );
          }
          if (!enforceCanonicalOrder && (defsCtx.exception != null
              || defsCtx.LBRACE() == null
              || defsCtx.RBRACE() == null)) {
            reportTopLevelSectionViolation(
                kw,
                "Incomplete '" + StvnVocabulary.KEYWORD_DEFS + "' section: declare a definitions block '{ ... }' after '"
                    + StvnVocabulary.KEYWORD_DEFS + "'",
                "E002",
                DiagnosticBag.ERR_MALFORMED_SECTION_STRUCTURE,
                diagnosticBag,
                strict
            );
          }
        }
        seenDefs = true;
        maxOrder = Math.max(maxOrder, 2);
      } else if (child instanceof StvnParser.TypeEntryContext typeCtx) {
        Token kw = typeCtx.KW_TYPE() != null ? typeCtx.KW_TYPE().getSymbol() : typeCtx.getStart();
        if (seenType) {
          hasPoisoningSectionError = true;
          reportTopLevelSectionViolation(
              kw,
              duplicateSectionMessage(StvnVocabulary.KEYWORD_TYPE),
              "E002",
              DiagnosticBag.ERR_DUPLICATE_ROOT_SECTION,
              diagnosticBag,
              strict
          );
        } else {
          if (enforceCanonicalOrder && maxOrder > 3) {
            reportTopLevelSectionViolation(
                kw,
                "Misplaced '" + StvnVocabulary.KEYWORD_TYPE + "' section prohibited: '"
                    + StvnVocabulary.KEYWORD_TYPE + "' must appear before '"
                    + StvnVocabulary.KEYWORD_BODY + "' (canonical order: " + CANONICAL_SECTION_PROGRESSION + ")",
                "E002",
                DiagnosticBag.ERR_SECTION_ORDER_VIOLATION,
                diagnosticBag,
                strict
            );
          }
          if (typeCtx.schemaType() == null && (!enforceCanonicalOrder || typeCtx.exception == null)) {
            hasPoisoningSectionError = true;
            if (enforceCanonicalOrder && typeCtx.metadataMap() != null) {
              validateStandaloneOrphanMetadataMap(typeCtx.metadataMap(), diagnosticBag, strict);
            }
            int afterStop = typeCtx.metadataMap() != null && typeCtx.metadataMap().getStop() != null
                ? typeCtx.metadataMap().getStop().getStopIndex() + 1
                : (kw != null ? kw.getStopIndex() + 1 : 0);
            int nextBoundaryStart = nextRootBoundaryStartOffset(doc, body.children, childIdx);
            if (!enforceCanonicalOrder || !hasNonTabErrorInGap(diagnosticBag, afterStop, nextBoundaryStart)) {
              reportTopLevelSectionViolation(
                  kw,
                  "Incomplete '" + StvnVocabulary.KEYWORD_TYPE
                      + "' section: declare a target schema type after '" + StvnVocabulary.KEYWORD_TYPE
                      + "' (for example, '" + StvnVocabulary.KEYWORD_TYPE + " " + StvnVocabulary.TYPE_INT + "')",
                  "E002",
                  DiagnosticBag.ERR_MALFORMED_SECTION_STRUCTURE,
                  diagnosticBag,
                  strict
              );
            }
          }
        }
        seenType = true;
        maxOrder = Math.max(maxOrder, 3);
      } else if (child instanceof StvnParser.BodyEntryContext bodyCtx) {
        Token kw = bodyCtx.KW_BODY() != null ? bodyCtx.KW_BODY().getSymbol() : bodyCtx.getStart();
        if (seenBody) {
          hasPoisoningSectionError = true;
          reportTopLevelSectionViolation(
              kw,
              duplicateSectionMessage(StvnVocabulary.KEYWORD_BODY),
              "E002",
              DiagnosticBag.ERR_DUPLICATE_ROOT_SECTION,
              diagnosticBag,
              strict
          );
        } else if (bodyCtx.value() == null && (!enforceCanonicalOrder || bodyCtx.exception == null)) {
          hasPoisoningSectionError = true;
          int afterStop = kw != null ? kw.getStopIndex() + 1 : 0;
          int nextBoundaryStart = nextRootBoundaryStartOffset(doc, body.children, childIdx);
          if (!enforceCanonicalOrder || !hasNonTabErrorInGap(diagnosticBag, afterStop, nextBoundaryStart)) {
            reportTopLevelSectionViolation(
                kw,
                "Incomplete '" + StvnVocabulary.KEYWORD_BODY
                    + "' section: declare a payload value after '" + StvnVocabulary.KEYWORD_BODY + "'",
                "E002",
                DiagnosticBag.ERR_MALFORMED_SECTION_STRUCTURE,
                diagnosticBag,
                strict
            );
          }
        }
        seenBody = true;
        maxOrder = Math.max(maxOrder, 4);
      }
    }
    return hasPoisoningSectionError;
  }

  private static int nextRootBoundaryStartOffset(
      StvnParser.StvnDocumentContext doc,
      List<org.antlr.v4.runtime.tree.ParseTree> bodyChildren,
      int currentChildIdx
  ) {
    for (int j = currentChildIdx + 1; j < bodyChildren.size(); j++) {
      var next = bodyChildren.get(j);
      if (next instanceof ParserRuleContext nextCtx
          && nextCtx.getStart() != null
          && nextCtx.getStart().getStartIndex() >= 0) {
        return nextCtx.getStart().getStartIndex();
      }
    }
    if (doc.RBRACE() != null && doc.RBRACE().getSymbol().getStartIndex() >= 0) {
      return doc.RBRACE().getSymbol().getStartIndex();
    }
    return Integer.MAX_VALUE;
  }

  private static boolean hasNonTabErrorInGap(
      @Nullable DiagnosticBag diagnosticBag,
      int startInclusive,
      int endExclusive
  ) {
    if (diagnosticBag == null || startInclusive >= endExclusive) {
      return false;
    }
    for (var diag : diagnosticBag.toList()) {
      if (diag.severity() != org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity.ERROR) {
        continue;
      }
      String sym = diag.symbolicId().or(diag::errorCode).orElse("");
      if (DiagnosticBag.ERR_TAB_CHARACTER_FORBIDDEN.equals(sym)) {
        continue;
      }
      int off = diag.startOffset();
      if (off >= startInclusive && off < endExclusive) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasDiagnosticAtOffset(DiagnosticBag diagnosticBag, int startOffset) {
    for (var diag : diagnosticBag.toList()) {
      if (diag.severity() == org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity.ERROR
          && diag.startOffset() == startOffset) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasPass1SyntaxErrorInSpan(DiagnosticBag diagnosticBag, Span span) {
    Optional<String> targetPath = diagnosticBag.defaultSourcePath();
    for (var diag : diagnosticBag.toList()) {
      if (diag.severity() != org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity.ERROR) {
        continue;
      }
      if (!Objects.equals(diag.sourcePath(), targetPath)) {
        continue;
      }
      String sym = diag.symbolicId().or(diag::errorCode).orElse("");
      boolean isPass1Syntax = "STVN_SYNTAX_ERROR".equals(sym)
          || sym.startsWith("SYNTAX_")
          || DiagnosticBag.ERR_FENCE_DELIMITER_MISMATCH.equals(sym)
          || DiagnosticBag.ERR_PROHIBITED_FENCE_ARROW.equals(sym)
          || DiagnosticBag.ERR_BARE_COLON_PROHIBITED.equals(sym)
          || DiagnosticBag.ERR_BARE_HASH_PROHIBITED.equals(sym);
      if (isPass1Syntax && diag.startOffset() >= span.start() && diag.startOffset() <= span.end()) {
        return true;
      }
    }
    return false;
  }

  private static boolean isMissingOrAbortedMetadataValue(StvnParser.@Nullable MetadataValueContext mv) {
    if (mv == null || mv.exception != null) {
      return true;
    }
    return mv.booleanLiteral() == null
        && mv.integerLiteral() == null
        && mv.floatLiteral() == null
        && mv.stringLiteral() == null
        && mv.facetConstRef() == null;
  }

  private static String formatMissingTimeEpochScaleMessage() {
    return "Temporal primitive '" + StvnVocabulary.TYPE_TIME_EPOCH
        + "' requires exactly one scale facet ('" + StvnVocabulary.FACET_KW_SCALE_NS
        + "', '" + StvnVocabulary.FACET_KW_SCALE_US + "', '" + StvnVocabulary.FACET_KW_SCALE_MS
        + "', or '" + StvnVocabulary.FACET_KW_SCALE_S + "')";
  }

  private static String formatMissingDateTimeModeMessage() {
    return "Temporal primitive '" + StvnVocabulary.TYPE_DATE_TIME
        + "' requires exactly one mode facet ('" + StvnVocabulary.FACET_KW_OFFSET
        + "', '" + StvnVocabulary.FACET_KW_ZONED + "', or '" + StvnVocabulary.FACET_KW_AUDITED + "')";
  }

  private static String formatConflictingTemporalScalesMessage() {
    return "Temporal scale facets ('" + StvnVocabulary.FACET_KW_SCALE_NS
        + "', '" + StvnVocabulary.FACET_KW_SCALE_US + "', '" + StvnVocabulary.FACET_KW_SCALE_MS
        + "', '" + StvnVocabulary.FACET_KW_SCALE_S + "') are mutually exclusive";
  }

  private static String formatConflictingTemporalModesMessage() {
    return "Temporal mode facets ('" + StvnVocabulary.FACET_KW_OFFSET
        + "', '" + StvnVocabulary.FACET_KW_ZONED + "', '" + StvnVocabulary.FACET_KW_AUDITED
        + "') are mutually exclusive";
  }

  private static void validateStandaloneOrphanMetadataMap(
      StvnParser.MetadataMapContext metaMap,
      @Nullable DiagnosticBag diagnosticBag,
      boolean strict
  ) {
    if (metaMap.LBRACE() != null && metaMap.RBRACE() != null
        && (metaMap.metadataEntry() == null || metaMap.metadataEntry().isEmpty())) {
      int start = metaMap.LBRACE().getSymbol().getStartIndex();
      int stop = metaMap.RBRACE().getSymbol().getStopIndex() + 1;
      int line = metaMap.LBRACE().getSymbol().getLine();
      int col = metaMap.LBRACE().getSymbol().getCharPositionInLine();
      String msg = "Empty metadata block is invalid; remove '{}' or specify valid facets";
      if (strict) {
        throw new org.stvnadore.core.validation.StvnSyntaxCancellationException(
            msg, line, col, start, stop, null, "E003", DiagnosticBag.ERR_EMPTY_METADATA_BLOCK
        );
      }
      if (diagnosticBag != null) {
        diagnosticBag.addError(msg, start, stop, line, col, null, DiagnosticBag.ERR_EMPTY_METADATA_BLOCK);
      }
      return;
    }
    if (diagnosticBag == null || metaMap.metadataEntry() == null) {
      return;
    }
    String ownerLabel = "<" + StvnVocabulary.KEYWORD_TYPE + ">";
    Set<String> seenFacetNames = new HashSet<>();
    boolean seenScale = false;
    boolean seenMode = false;
    boolean seenSizeOrExact = false;
    boolean seenMinBound = false;
    boolean seenMaxBound = false;
    boolean seenEnumFilter = false;
    int lastTier = -1;
    int lastSubTier = -1;
    String lastFacetName = null;

    for (var entry : metaMap.metadataEntry()) {
      if (entry.metadataUnknownEntry() != null) {
        var unk = entry.metadataUnknownEntry();
        var kwNode = unk.unknownFacetKeyword();
        Span sp = Span.ofContext(kwNode != null ? kwNode : unk);
        String facetTag = kwNode != null ? kwNode.getText() : unk.getText();
        diagnosticBag.addError(
            "Metadata violation (" + ownerLabel + "): Unrecognized constraint facet '" + facetTag + "'",
            sp.start(), sp.end(), sp.line(), sp.column(), null,
            DiagnosticBag.ERR_UNRECOGNIZED_METADATA_FACET
        );
        continue;
      }
      StvnParser.StandaloneFacetIllegalArgContext illegalArg = extractStandaloneIllegalArg(entry);
      String facetName = extractFacetName(entry);
      if (facetName == null) {
        continue;
      }
      if (illegalArg != null) {
        Span argSpan = Span.ofContext(illegalArg);
        diagnosticBag.addError(
            "Metadata facet '" + StvnVocabulary.SIGIL_VALUE + facetName
                + "' is a standalone flag and does not accept arguments; unexpected '" + illegalArg.getText() + "'",
            argSpan.start(), argSpan.end(), argSpan.line(), argSpan.column(), null,
            DiagnosticBag.ERR_INVALID_METADATA_FACET
        );
        continue;
      }
      Span entrySpan = Span.ofContext(entry);
      if (!seenFacetNames.add(facetName)) {
        diagnosticBag.addError(
            "Metadata violation (" + ownerLabel + "): Duplicate facet '" + StvnVocabulary.SIGIL_VALUE + facetName
                + "' is prohibited; each facet may appear at most once per metadata block",
            entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
            DiagnosticBag.ERR_DUPLICATE_METADATA_FACET
        );
        continue;
      }
      if ((entry.metadataBool() != null && entry.metadataBool().metadataValue() == null)
          || (entry.metadataTrait() != null && entry.metadataTrait().metadataValue() == null)) {
        if (!hasDiagnosticAtOffset(diagnosticBag, entrySpan.start())) {
          diagnosticBag.addError(
              "Metadata facet '" + StvnVocabulary.SIGIL_VALUE + facetName
                  + "' requires an explicit boolean value ("
                  + StvnVocabulary.VAL_TRUE + " or " + StvnVocabulary.VAL_FALSE + ")",
              entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
              DiagnosticBag.ERR_INCOMPLETE_TRAIT_FACET
          );
        }
        continue;
      }
      if ("strip".equals(facetName)) {
        diagnosticBag.addError(
            "Constraint violation (" + ownerLabel + "): directive facet '" + StvnVocabulary.FACET_KW_STRIP
                + "' is not permitted on type declarations; directive facets are valid strictly in "
                + StvnVocabulary.KEYWORD_USE + " and " + StvnVocabulary.KEYWORD_INCLUDE + " blocks",
            entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
            DiagnosticBag.ERR_INVALID_METADATA_FACET
        );
        continue;
      }
      if (isTemporalScaleName(facetName)) {
        if (seenScale) {
          diagnosticBag.addError(
              formatConflictingTemporalScalesMessage(),
              entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
              DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
          );
          continue;
        }
        seenScale = true;
      }
      if (isTemporalModeName(facetName)) {
        if (seenMode) {
          diagnosticBag.addError(
              formatConflictingTemporalModesMessage(),
              entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
              DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
          );
          continue;
        }
        seenMode = true;
      }
      if ("size".equals(facetName) || "exact".equals(facetName)) {
        if (seenSizeOrExact) {
          String peer = "size".equals(facetName) ? StvnVocabulary.FACET_KW_EXACT : StvnVocabulary.FACET_KW_SIZE;
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): facet '"
                  + StvnVocabulary.SIGIL_VALUE + facetName + "' is mutually exclusive with '" + peer + "'",
              entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
              DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
          );
          continue;
        }
        seenSizeOrExact = true;
      }
      if ("minIncl".equals(facetName) || "minExcl".equals(facetName)) {
        if (seenMinBound) {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): "
                  + StvnVocabulary.FACET_KW_MIN_INCL + " and " + StvnVocabulary.FACET_KW_MIN_EXCL
                  + " are mutually exclusive",
              entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
              DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
          );
          continue;
        }
        seenMinBound = true;
      }
      if ("maxIncl".equals(facetName) || "maxExcl".equals(facetName)) {
        if (seenMaxBound) {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): "
                  + StvnVocabulary.FACET_KW_MAX_INCL + " and " + StvnVocabulary.FACET_KW_MAX_EXCL
                  + " are mutually exclusive",
              entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
              DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
          );
          continue;
        }
        seenMaxBound = true;
      }
      if ("filterIncl".equals(facetName) || "filterExcl".equals(facetName)) {
        if (seenEnumFilter) {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): "
                  + StvnVocabulary.FACET_KW_FILTER_INCL + " and " + StvnVocabulary.FACET_KW_FILTER_EXCL
                  + " are mutually exclusive",
              entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
              DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
          );
          continue;
        }
        seenEnumFilter = true;
      }
      int tier = getFacetTier(facetName);
      int subTier = getFacetSubTier(facetName);
      if (tier < lastTier || (tier == lastTier && subTier < lastSubTier)) {
        diagnosticBag.addError(
            "Facet order violation (" + ownerLabel + "): facet '" + StvnVocabulary.SIGIL_VALUE + facetName
                + "' (Tier " + tier + ") violates canonical 5-tier order after '"
                + StvnVocabulary.SIGIL_VALUE + lastFacetName + "'",
            entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
            DiagnosticBag.ERR_FACET_ORDER_VIOLATION
        );
      }
      lastTier = tier;
      lastSubTier = subTier;
      lastFacetName = facetName;
    }
  }

  private static void reportTopLevelSectionViolation(
      @Nullable Token kw,
      String message,
      String canonicalCode,
      String symbolicId,
      @Nullable DiagnosticBag diagnosticBag,
      boolean strict
  ) {
    int start = kw != null ? Math.max(0, kw.getStartIndex()) : 0;
    int stop = kw != null ? Math.max(start + 1, kw.getStopIndex() + 1) : 1;
    int line = kw != null ? kw.getLine() : 1;
    int col = kw != null ? kw.getCharPositionInLine() : 0;
    if (strict) {
      throw new org.stvnadore.core.validation.StvnSyntaxCancellationException(
          message, line, col, start, stop, null, canonicalCode, symbolicId
      );
    }
    if (diagnosticBag != null) {
      diagnosticBag.addErrorDual(message, start, stop, line, col, null, canonicalCode, symbolicId);
    }
  }

  private static CompiledUnit emptyFailedChildUnit() {
    return new CompiledUnit(
        Optional.empty(),
        Optional.empty(),
        StvnDefsCatalog.EMPTY,
        Optional.empty(),
        false,
        false,
        false,
        Map.of(),
        Map.of(),
        (tok, site) -> Optional.empty(),
        fqni -> Optional.empty(),
        Map.of()
    );
  }

  private static CompiledUnit lowerDocumentUnit(
      StvnParser.StvnDocumentContext doc,
      @Nullable String docPath,
      ModuleCompilationContext moduleCtx,
      DiagnosticBag diagnosticBag,
      boolean skipPathIdentityChecks,
      boolean embeddedBinaryDefsMode,
      boolean unitHasPass1SyntaxError
  ) {
    IdentityOutcome identity = lowerAndValidateDocumentIdentity(doc, docPath, moduleCtx, diagnosticBag, skipPathIdentityChecks);
    validateDocumentBodyStructure(doc, diagnosticBag, moduleCtx.config.strict());

    var body = doc.documentBody();
    if (body == null) {
      return new CompiledUnit(
          identity.meta(),
          identity.effectiveKind(),
          StvnDefsCatalog.EMPTY,
          Optional.empty(),
          false,
          false,
          true,
          Map.of(),
          Map.of(),
          (tok, site) -> Optional.empty(),
          fqni -> Optional.empty(),
          Map.of()
      );
    }

    boolean sectionsEligible = validateDocumentSectionEligibility(
        doc, docPath, identity, moduleCtx, diagnosticBag, embeddedBinaryDefsMode, unitHasPass1SyntaxError
    );
    if (!sectionsEligible) {
      return new CompiledUnit(
          identity.meta(),
          identity.effectiveKind(),
          StvnDefsCatalog.EMPTY,
          Optional.empty(),
          !body.defsEntry().isEmpty(),
          false,
          false,
          Map.of(),
          Map.of(),
          (tok, site) -> Optional.empty(),
          fqni -> Optional.empty(),
          Map.of()
      );
    }

    UnitEnvironment env = new UnitEnvironment(doc, docPath, identity, moduleCtx, diagnosticBag, embeddedBinaryDefsMode);
    env.collectAndResolveDefs();

    Optional<StvnSchema> rootType = Optional.empty();
    if (body.typeEntry(0) != null && body.typeEntry(0).schemaType() != null) {
      rootType = Optional.of(
          env.lowerAnonymousOrRootSchema(body.typeEntry(0).schemaType(), env.rootScope, new LinkedHashSet<>(), true).schema()
      );
    }

    StvnDefsCatalog catalog = env.buildCatalog();
    Map<String, Span> defSpans = new LinkedHashMap<>();
    for (var entry : env.localTypeAsts.entrySet()) {
      defSpans.put(entry.getKey(), Span.ofContext(entry.getValue().ctx()));
    }
    for (var entry : env.localConstAsts.entrySet()) {
      defSpans.put(entry.getKey(), Span.ofContext(entry.getValue().ctx()));
    }
    StvnPayloadLowerer.ConstantResolver rootConstResolver =
        (tokenText, site) -> env.resolveConstantAtSite(tokenText, site);
    StvnPayloadLowerer.NominalSchemaResolver nominalLookup = env::findLoweredNominalSchema;

    return new CompiledUnit(
        identity.meta(),
        identity.effectiveKind(),
        catalog,
        rootType,
        env.hasDefsSection,
        env.hasIncludes,
        true,
        env.exportedTypes,
        env.exportedConstants,
        rootConstResolver,
        nominalLookup,
        defSpans
    );
  }

  private static IdentityOutcome lowerAndValidateDocumentIdentity(
      StvnParser.StvnDocumentContext doc,
      @Nullable String docPath,
      ModuleCompilationContext moduleCtx,
      DiagnosticBag diagnosticBag,
      boolean skipPathIdentityChecks
  ) {
    String ext = null;
    String fileName = null;
    String[] dotParts = null;

    Token docStart = doc.getStart();
    int docStartIdx = docStart != null ? Math.max(0, docStart.getStartIndex()) : 0;
    int docStopIdx = docStart != null ? Math.max(docStartIdx + 1, docStart.getStopIndex() + 1) : 1;
    int docLine = docStart != null ? docStart.getLine() : 1;
    int docCol = docStart != null ? docStart.getCharPositionInLine() : 0;

    boolean fatalPathRejection = false;
    if (!moduleCtx.bootstrapPreludeMode && docPath != null && !docPath.isEmpty()) {
      String normalized = docPath.replace('\\', '/');
      int lastSlash = normalized.lastIndexOf('/');
      fileName = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;

      if (skipPathIdentityChecks) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot >= 0) {
          ext = fileName.substring(lastDot);
        }
      } else if (rejectLegacyExtension(fileName, diagnosticBag, docStartIdx, docStopIdx, docLine, docCol)) {
        fatalPathRejection = true;
      } else if (fileName.startsWith(".")) {
        diagnosticBag.addError(
            "Filename '" + fileName + "' lacks a visible non-empty stem segment before extension; bare dotfiles are prohibited",
            docStartIdx, docStopIdx, docLine, docCol, null,
            DiagnosticBag.ERR_INVALID_FILENAME_STEM
        );
        fatalPathRejection = true;
      } else {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot >= 0) {
          ext = fileName.substring(lastDot);
          String stemPart = fileName.substring(0, lastDot);
          dotParts = stemPart.split("\\.", -1);
          if (dotParts.length >= 3) {
            diagnosticBag.addError(
                "Invalid filename stem '" + fileName
                    + "'; filename must contain exactly one or two dot-separated stem segments (<name>.<ext> or <name>.<domain>.<ext>)",
                docStartIdx, docStopIdx, docLine, docCol, null,
                DiagnosticBag.ERR_INVALID_FILENAME_STEM
            );
            fatalPathRejection = true;
          }
        }
      }
    }

    var body = doc.documentBody();
    var metaEntry = body != null ? body.metaEntry(0) : null;
    Optional<StvnDocumentKind> expectedKindOpt =
        ext != null ? StvnDocumentKind.fromExtension(ext) : Optional.empty();

    if (metaEntry == null) {
      if (!skipPathIdentityChecks
          && !fatalPathRejection
          && !moduleCtx.bootstrapPreludeMode
          && !moduleCtx.flattenerMode
          && isMetaMandatoryForExtension(ext, expectedKindOpt)) {
        diagnosticBag.addError(
            "Mandatory :meta block missing; document kind '" + expectedKindOpt.get().keyword()
                + "' is required for extension '" + ext + "'",
            docStartIdx, docStopIdx, docLine, docCol, null,
            DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH
        );
      }
      return new IdentityOutcome(Optional.empty(), expectedKindOpt, fileName, ext);
    }

    var metaBlock = metaEntry.metaBlock();
    if (metaBlock != null && metaBlock.LBRACE() != null && metaBlock.RBRACE() != null
        && (metaBlock.metaElement() == null || metaBlock.metaElement().isEmpty())) {
      int start = metaBlock.LBRACE().getSymbol().getStartIndex();
      int stop = metaBlock.RBRACE().getSymbol().getStopIndex() + 1;
      int line = metaBlock.LBRACE().getSymbol().getLine();
      int col = metaBlock.LBRACE().getSymbol().getCharPositionInLine();
      boolean metaMandatory = (expectedKindOpt.isPresent()
              && (expectedKindOpt.get() == StvnDocumentKind.DEFS
                  || expectedKindOpt.get() == StvnDocumentKind.DEFS_INCLUDE
                  || expectedKindOpt.get() == StvnDocumentKind.BODY_INCLUDE))
          || ".stvn_b".equals(ext)
          || ".stvn_bd".equals(ext)
          || (dotParts != null && dotParts.length == 2 && !dotParts[1].isEmpty() && !isReservedDomain(dotParts[1]));
      String emptyMetaMsg = metaMandatory
          ? "Empty '" + StvnVocabulary.KEYWORD_META + " {}' section is prohibited; declare document identity facets ("
              + StvnVocabulary.FACET_KW_META_NAME + ", " + StvnVocabulary.FACET_KW_META_DOMAIN + ", "
              + StvnVocabulary.FACET_KW_META_KIND + ")"
          : "Empty '" + StvnVocabulary.KEYWORD_META + " {}' section is prohibited; omit '"
              + StvnVocabulary.KEYWORD_META + "' or declare document identity facets ("
              + StvnVocabulary.FACET_KW_META_NAME + ", " + StvnVocabulary.FACET_KW_META_DOMAIN + ", "
              + StvnVocabulary.FACET_KW_META_KIND + ")";
      diagnosticBag.addError(
          emptyMetaMsg,
          start, stop, line, col, null,
          DiagnosticBag.ERR_EMPTY_META_BLOCK
      );
      return new IdentityOutcome(Optional.empty(), expectedKindOpt, fileName, ext);
    }
    if (metaBlock == null || metaBlock.LBRACE() == null) {
      return new IdentityOutcome(Optional.empty(), expectedKindOpt, fileName, ext);
    }

    boolean seenName = false;
    boolean seenDomain = false;
    boolean seenKind = false;
    int currentTier = 0;

    String declaredName = null;
    String declaredDomain = null;
    StvnDocumentKind declaredKind = null;

    ParserRuleContext nameNode = null;
    ParserRuleContext domainNode = null;
    ParserRuleContext kindNode = null;

    for (var el : metaBlock.metaElement()) {
      if (el.metaUnknownFacet() != null) {
        var unk = el.metaUnknownFacet();
        String facetTag = unk.valueKeyword() != null ? unk.valueKeyword().getText() : unk.getText();
        if (StvnVocabulary.FACET_KW_META_NAME.equals(facetTag)) {
          seenName = true;
        } else if (StvnVocabulary.FACET_KW_META_DOMAIN.equals(facetTag)) {
          seenDomain = true;
        } else if (StvnVocabulary.FACET_KW_META_KIND.equals(facetTag)) {
          seenKind = true;
        }
        Span sp = Span.ofContext(unk);
        diagnosticBag.addError(
            "Invalid metadata facet tag '" + facetTag + "' in ':meta' block",
            sp.start(), sp.end(), sp.line(), sp.column(), null,
            DiagnosticBag.ERR_INVALID_METADATA_FACET
        );
      } else if (el.metaNameFacet() != null) {
        var nf = el.metaNameFacet();
        Span nfSpan = Span.ofContext(nf);
        if (seenName) {
          diagnosticBag.addError(
              "Duplicate metadata facet tag '#name' declared in ':meta' block",
              nfSpan.start(), nfSpan.end(), nfSpan.line(), nfSpan.column(), null,
              DiagnosticBag.ERR_DUPLICATE_METADATA_FACET
          );
        } else if (currentTier > 1) {
          diagnosticBag.addError(
              "Metadata facet '#name' violates 3-tier monotonic progression (#name -> #domain -> #kind)",
              nfSpan.start(), nfSpan.end(), nfSpan.line(), nfSpan.column(), null,
              DiagnosticBag.ERR_FACET_ORDER_VIOLATION
          );
        }
        seenName = true;
        currentTier = Math.max(currentTier, 1);
        nameNode = nf.stringLiteral();
        if (nf.stringLiteral() != null && !(nf.stringLiteral() instanceof StvnParser.StringMalformedFencedContext)) {
          Span strSpan = Span.ofContext(nf.stringLiteral());
          try {
            declaredName = StvnPayloadLowerer.decodeStringLiteral(nf.stringLiteral().getText(), true, strSpan);
            if (!POSIX_META_ID_PATTERN.matcher(declaredName).matches()) {
              diagnosticBag.addError(
                  "Metadata #name '" + declaredName + "' does not match required atomic POSIX pattern ^[a-zA-Z0-9_-]{1,64}$",
                  strSpan.start(), strSpan.end(), strSpan.line(), strSpan.column(), null,
                  DiagnosticBag.ERR_INVALID_METADATA_FACET
              );
            }
          } catch (StvnPayloadLowerer.LoweringLiteralException le) {
            diagnosticBag.addErrorDual(
                le.getMessage(), le.span().start(), le.span().end(), le.span().line(), le.span().column(),
                le.getCause(), le.canonicalCode(), le.symbolicId()
            );
          }
        }
      } else if (el.metaDomainFacet() != null) {
        var df = el.metaDomainFacet();
        Span dfSpan = Span.ofContext(df);
        if (seenDomain) {
          diagnosticBag.addError(
              "Duplicate metadata facet tag '#domain' declared in ':meta' block",
              dfSpan.start(), dfSpan.end(), dfSpan.line(), dfSpan.column(), null,
              DiagnosticBag.ERR_DUPLICATE_METADATA_FACET
          );
        } else if (currentTier > 2) {
          diagnosticBag.addError(
              "Metadata facet '#domain' violates 3-tier monotonic progression (#name -> #domain -> #kind)",
              dfSpan.start(), dfSpan.end(), dfSpan.line(), dfSpan.column(), null,
              DiagnosticBag.ERR_FACET_ORDER_VIOLATION
          );
        }
        seenDomain = true;
        currentTier = Math.max(currentTier, 2);
        domainNode = df.stringLiteral();
        if (df.stringLiteral() != null && !(df.stringLiteral() instanceof StvnParser.StringMalformedFencedContext)) {
          Span strSpan = Span.ofContext(df.stringLiteral());
          try {
            declaredDomain = StvnPayloadLowerer.decodeStringLiteral(df.stringLiteral().getText(), true, strSpan);
            if (!POSIX_META_ID_PATTERN.matcher(declaredDomain).matches()) {
              diagnosticBag.addError(
                  "Metadata #domain '" + declaredDomain + "' does not match required atomic POSIX pattern ^[a-zA-Z0-9_-]{1,64}$",
                  strSpan.start(), strSpan.end(), strSpan.line(), strSpan.column(), null,
                  DiagnosticBag.ERR_INVALID_METADATA_FACET
              );
            } else if (!moduleCtx.isSystemAuthority() && isReservedDomain(declaredDomain)) {
              diagnosticBag.addError(
                  "Reserved system domain prohibited: User document cannot declare reserved system domain '" + declaredDomain
                      + "'; domains with prefix 'stvn_' are strictly reserved for system artifacts",
                  strSpan.start(), strSpan.end(), strSpan.line(), strSpan.column(), null,
                  DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED
              );
            }
          } catch (StvnPayloadLowerer.LoweringLiteralException le) {
            diagnosticBag.addErrorDual(
                le.getMessage(), le.span().start(), le.span().end(), le.span().line(), le.span().column(),
                le.getCause(), le.canonicalCode(), le.symbolicId()
            );
          }
        }
      } else if (el.metaKindFacet() != null) {
        var kf = el.metaKindFacet();
        Span kfSpan = Span.ofContext(kf);
        if (seenKind) {
          diagnosticBag.addError(
              "Duplicate metadata facet tag '#kind' declared in ':meta' block",
              kfSpan.start(), kfSpan.end(), kfSpan.line(), kfSpan.column(), null,
              DiagnosticBag.ERR_DUPLICATE_METADATA_FACET
          );
        } else if (currentTier > 3) {
          diagnosticBag.addError(
              "Metadata facet '#kind' violates 3-tier monotonic progression (#name -> #domain -> #kind)",
              kfSpan.start(), kfSpan.end(), kfSpan.line(), kfSpan.column(), null,
              DiagnosticBag.ERR_FACET_ORDER_VIOLATION
          );
        }
        seenKind = true;
        currentTier = Math.max(currentTier, 3);
        kindNode = kf;
        if (kf.metaKindValue() != null) {
          declaredKind = StvnDocumentKind.fromKeyword(kf.metaKindValue().getText()).orElse(null);
        }
      }
    }

    if (!skipPathIdentityChecks && !fatalPathRejection && !moduleCtx.bootstrapPreludeMode) {
      if (declaredKind != null && expectedKindOpt.isPresent() && declaredKind != expectedKindOpt.get()) {
        Span sp = kindNode != null ? Span.ofContext(kindNode) : kwMetaSpan(metaEntry);
        diagnosticBag.addError(
            "Document kind '" + declaredKind.keyword() + "' does not match file extension '" + ext
                + "' (expected '" + expectedKindOpt.get().keyword() + "')",
            sp.start(), sp.end(), sp.line(), sp.column(), null,
            DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH
        );
      } else if (!seenKind
          && !moduleCtx.flattenerMode
          && isMetaMandatoryForExtension(ext, expectedKindOpt)) {
        Span sp = kwMetaSpan(metaEntry);
        diagnosticBag.addError(
            "Root '" + StvnVocabulary.KEYWORD_META + "' section requires "
                + StvnVocabulary.FACET_KW_META_KIND + " facet matching file extension '" + ext
                + "' (expected '" + expectedKindOpt.get().keyword() + "')",
            sp.start(), sp.end(), sp.line(), sp.column(), null,
            DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH
        );
      }

      if (dotParts != null && dotParts.length >= 1) {
        String stemName = dotParts[0];
        if (declaredName != null && !declaredName.equals(stemName)) {
          Span sp = nameNode != null ? Span.ofContext(nameNode) : kwMetaSpan(metaEntry);
          diagnosticBag.addWarning(
              "Declared " + StvnVocabulary.FACET_KW_META_NAME + " '" + declaredName
                  + "' does not match filename stem slot 0 '" + stemName + "'",
              sp.start(), sp.end(), sp.line(), sp.column(),
              DiagnosticBag.WARN_DOCUMENT_NAME_MISMATCH
          );
        }
        if (declaredDomain != null) {
          Span sp = domainNode != null ? Span.ofContext(domainNode) : kwMetaSpan(metaEntry);
          if (dotParts.length == 1) {
            diagnosticBag.addWarning(
                "Declared " + StvnVocabulary.FACET_KW_META_DOMAIN + " '" + declaredDomain
                    + "' is omitted from filename stem slot 1",
                sp.start(), sp.end(), sp.line(), sp.column(),
                DiagnosticBag.WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME
            );
          } else if (!declaredDomain.equals(dotParts[1])) {
            diagnosticBag.addWarning(
                "Declared " + StvnVocabulary.FACET_KW_META_DOMAIN + " '" + declaredDomain
                    + "' does not match filename stem slot 1 '" + dotParts[1] + "'",
                sp.start(), sp.end(), sp.line(), sp.column(),
                DiagnosticBag.WARN_DOCUMENT_NAME_MISMATCH
            );
          }
        } else if (!seenDomain && dotParts.length == 2 && !dotParts[1].isEmpty() && !isReservedDomain(dotParts[1])) {
          Span sp = kwMetaSpan(metaEntry);
          diagnosticBag.addWarning(
              "Filename stem slot 1 declares domain '" + dotParts[1]
                  + "', but " + StvnVocabulary.KEYWORD_META + " omits "
                  + StvnVocabulary.FACET_KW_META_DOMAIN + " \"" + dotParts[1] + "\"",
              sp.start(), sp.end(), sp.line(), sp.column(),
              DiagnosticBag.WARN_DOCUMENT_DOMAIN_OMITTED_IN_META
          );
        }
      }
    }

    Span metaSpan = Span.ofContext(metaEntry);
    StvnDocumentMeta.SourceSpan irMetaSpan = new StvnDocumentMeta.SourceSpan(
        metaSpan.line(),
        metaSpan.column(),
        metaSpan.start(),
        metaSpan.end()
    );
    Optional<StvnDocumentMeta> loweredMeta = Optional.of(new StvnDocumentMeta(
        Optional.ofNullable(declaredName),
        Optional.ofNullable(declaredDomain),
        Optional.ofNullable(declaredKind),
        Optional.of(irMetaSpan)
    ));
    Optional<StvnDocumentKind> effectiveKind = declaredKind != null
        ? Optional.of(declaredKind)
        : expectedKindOpt;

    return new IdentityOutcome(loweredMeta, effectiveKind, fileName, ext);
  }

  private static boolean isMetaMandatoryForExtension(
      @Nullable String ext,
      Optional<StvnDocumentKind> expectedKindOpt
  ) {
    return expectedKindOpt.isPresent()
        && (expectedKindOpt.get() == StvnDocumentKind.DEFS
            || expectedKindOpt.get() == StvnDocumentKind.DEFS_INCLUDE
            || expectedKindOpt.get() == StvnDocumentKind.BODY_INCLUDE
            || ".stvn_b".equals(ext));
  }

  private static Span kwMetaSpan(StvnParser.MetaEntryContext metaEntry) {
    if (metaEntry.KW_META() != null && metaEntry.KW_META().getSymbol() != null) {
      return Span.ofToken(metaEntry.KW_META().getSymbol());
    }
    return Span.ofContext(metaEntry);
  }

  private static boolean validateDocumentSectionEligibility(
      StvnParser.StvnDocumentContext doc,
      @Nullable String docPath,
      IdentityOutcome identity,
      ModuleCompilationContext moduleCtx,
      DiagnosticBag diagnosticBag,
      boolean embeddedBinaryDefsMode,
      boolean unitHasPass1SyntaxError
  ) {
    var body = doc.documentBody();
    if (body == null) {
      return true;
    }

    StvnDocumentKind effectiveKind = identity.effectiveKind().orElse(null);
    String ext = identity.fileExt();
    String fileName = identity.fileName();
    String targetDesc = fileName != null ? "'" + fileName + "'" : (ext != null ? ext : "<in-memory>");

    boolean isDefsSchema = embeddedBinaryDefsMode
        || effectiveKind == StvnDocumentKind.DEFS
        || effectiveKind == StvnDocumentKind.DEFS_INCLUDE
        || (ext != null && (ext.equals(".stvn_d") || ext.equals(".stvn_di") || ext.equals(".stvn_bd")));

    if (isDefsSchema) {
      boolean hasViolations = false;
      String kindLabel = effectiveKind != null ? effectiveKind.keyword() : StvnVocabulary.KIND_KW_DEFS;
      if (body.typeEntry(0) != null) {
        Span sp = Span.ofContext(body.typeEntry(0));
        diagnosticBag.addError(
            "Structural violation: Definitions schema (" + targetDesc + ") declaring #kind " + kindLabel
                + " cannot declare '" + StvnVocabulary.KEYWORD_TYPE + "' section [stvn-core:ERR_DOCUMENT_KIND_MISMATCH]",
            sp.start(), sp.end(), sp.line(), sp.column(), null,
            DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH
        );
        hasViolations = true;
      }
      if (body.bodyEntry(0) != null) {
        Span sp = Span.ofContext(body.bodyEntry(0));
        diagnosticBag.addError(
            "Structural violation: Definitions schema (" + targetDesc + ") declaring #kind " + kindLabel
                + " cannot declare '" + StvnVocabulary.KEYWORD_BODY + "' section [stvn-core:ERR_DOCUMENT_KIND_MISMATCH]",
            sp.start(), sp.end(), sp.line(), sp.column(), null,
            DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH
        );
        hasViolations = true;
      }
      if (!moduleCtx.flattenerMode && body.defsEntry(0) == null) {
        if (!unitHasPass1SyntaxError) {
          Token startTok = doc.getStart();
          int start = startTok != null ? Math.max(0, startTok.getStartIndex()) : 0;
          int stop = startTok != null ? Math.max(start + 1, startTok.getStopIndex() + 1) : 1;
          int line = startTok != null ? startTok.getLine() : 1;
          int col = startTok != null ? startTok.getCharPositionInLine() : 0;
          diagnosticBag.addError(
              "Mandatory '" + StvnVocabulary.KEYWORD_DEFS + "' section missing; definitions schema ("
                  + targetDesc + ") declaring #kind " + kindLabel + " requires a '"
                  + StvnVocabulary.KEYWORD_DEFS + "' section",
              start, stop, line, col, null,
              DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH
          );
        }
        hasViolations = true;
      }
      return !hasViolations;
    }

    boolean isHermeticBody = (effectiveKind == StvnDocumentKind.BODY
        || (ext != null && (ext.equals(".stvn") || ext.equals(".stvn_b"))))
        && (ext == null || !ext.equals(".stvn_i"));

    if (!moduleCtx.flattenerMode && isHermeticBody && docPath != null && !docPath.isEmpty()) {
      if (body.typeEntry(0) == null || body.bodyEntry(0) == null) {
        if (!unitHasPass1SyntaxError) {
          Token startTok = doc.getStart();
          int start = startTok != null ? Math.max(0, startTok.getStartIndex()) : 0;
          int stop = startTok != null ? Math.max(start + 1, startTok.getStopIndex() + 1) : 1;
          int line = startTok != null ? startTok.getLine() : 1;
          int col = startTok != null ? startTok.getCharPositionInLine() : 0;
          diagnosticBag.addError(
              "Mandatory '" + StvnVocabulary.KEYWORD_TYPE + "' and '" + StvnVocabulary.KEYWORD_BODY
                  + "' sections missing; hermetic payload document (" + targetDesc + ") requires payload schema and body value",
              start, stop, line, col, null,
              DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH
          );
        }
        return false;
      }
    }

    return true;
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
        return resolved;
      }
    }
    return includePath;
  }

  private static String normalizeVirtualPath(String path) {
    String posix = path.replace('\\', '/');
    String[] parts = posix.split("/");
    List<String> stack = new ArrayList<>();
    for (String part : parts) {
      String trimmed = part.trim();
      if (trimmed.isEmpty() || ".".equals(trimmed)) {
        continue;
      }
      if ("..".equals(trimmed)) {
        if (!stack.isEmpty() && !"..".equals(stack.getLast())) {
          stack.removeLast();
        } else {
          stack.add("..");
        }
      } else {
        stack.add(trimmed);
      }
    }
    String resolved = String.join("/", stack);
    return posix.startsWith("/") ? "/" + resolved : resolved;
  }

  private static String resolveVirtualIncludePath(@Nullable String currentDocPath, String includePathStr) {
    String posixInc = includePathStr.replace('\\', '/');
    if (posixInc.startsWith("/") || currentDocPath == null || currentDocPath.isEmpty()) {
      return normalizeVirtualPath(posixInc);
    }
    String normCurrent = normalizeVirtualPath(currentDocPath);
    int lastSlash = normCurrent.lastIndexOf('/');
    if (lastSlash < 0) {
      return normalizeVirtualPath(posixInc);
    }
    return normalizeVirtualPath(normCurrent.substring(0, lastSlash) + "/" + posixInc);
  }

  // ===========================================================================
  // PER-DOCUMENT UNIT ENVIRONMENT
  // ===========================================================================

  private static final class UnitEnvironment {
    private final StvnParser.@Nullable StvnDocumentContext doc;
    private final @Nullable String docPath;
    private final IdentityOutcome identity;
    private final ModuleCompilationContext moduleCtx;
    private final DiagnosticBag diagnosticBag;
    private final boolean embeddedBinaryDefsMode;

    boolean hasDefsSection = false;
    boolean hasIncludes = false;

    // Local AST declarations indexed by canonical FQNI
    private final Map<String, LocalTypeAstDecl> localTypeAsts = new LinkedHashMap<>();
    private final Map<String, LocalConstantAstDecl> localConstAsts = new LinkedHashMap<>();
    private final Set<String> locallyDeclaredPackages = new LinkedHashSet<>();

    // Active resolved bindings in the unit's global namespace (includes + local eviction)
    private final Map<String, ExportedTypeSymbol> importedTypePool = new LinkedHashMap<>();
    private final Map<String, ExportedConstantSymbol> importedConstPool = new LinkedHashMap<>();

    // Packaged symbols accessible via qualified FQNI or :use (excluding those explicitly evicted by include alias)
    private final Map<String, ExportedTypeSymbol> packagedImportPool = new LinkedHashMap<>();
    private final Map<String, ExportedConstantSymbol> packagedConstImportPool = new LinkedHashMap<>();

    // Memoized lowered local/active types and constants
    private final Map<String, LoweredSchemaBundle> loweredTypes = new LinkedHashMap<>();
    private final Map<String, StvnDefsCatalog.StvnConstant> loweredConstants = new LinkedHashMap<>();
    private final Set<String> evaluatingConstants = new LinkedHashSet<>();
    private final Set<String> circularDiagnosticsEmitted = new HashSet<>();
    private final Map<String, String> structuralCycleTraces = new HashMap<>();

    // Lexical scopes
    ScopeFrame rootScope = ScopeFrame.EMPTY;
    private final Map<StvnParser.PackageEnclosureContext, ScopeFrame> packageScopes = new HashMap<>();

    // Final module export tables
    final Map<String, ExportedTypeSymbol> exportedTypes = new LinkedHashMap<>();
    final Map<String, ExportedConstantSymbol> exportedConstants = new LinkedHashMap<>();

    UnitEnvironment(
        StvnParser.@Nullable StvnDocumentContext doc,
        @Nullable String docPath,
        IdentityOutcome identity,
        ModuleCompilationContext moduleCtx,
        DiagnosticBag diagnosticBag,
        boolean embeddedBinaryDefsMode
    ) {
      this.doc = doc;
      this.docPath = docPath;
      this.identity = identity;
      this.moduleCtx = moduleCtx;
      this.diagnosticBag = diagnosticBag;
      this.embeddedBinaryDefsMode = embeddedBinaryDefsMode;
    }

    static UnitEnvironment fromStandaloneCatalog(
        StvnDefsCatalog catalog,
        ModuleCompilationContext moduleCtx,
        DiagnosticBag diagnosticBag
    ) {
      IdentityOutcome emptyId = new IdentityOutcome(Optional.empty(), Optional.empty(), null, null);
      UnitEnvironment env = new UnitEnvironment(null, null, emptyId, moduleCtx, diagnosticBag, false);
      for (var entry : catalog.nominalTypes().entrySet()) {
        LoweredSchemaBundle bundle = new LoweredSchemaBundle(entry.getValue(), inferLedgerFromSchema(entry.getValue()));
        env.loweredTypes.put(entry.getKey(), bundle);
        ExportedTypeSymbol sym = new ExportedTypeSymbol(entry.getKey(), entry.getValue(), bundle.ledger(), "<catalog>");
        env.importedTypePool.put(entry.getKey(), sym);
        if (entry.getKey().contains("/")) {
          env.packagedImportPool.put(entry.getKey(), sym);
        }
      }
      for (var entry : catalog.constants().entrySet()) {
        env.loweredConstants.put(entry.getKey(), entry.getValue());
        ExportedConstantSymbol sym = new ExportedConstantSymbol(entry.getKey(), entry.getValue(), "<catalog>");
        env.importedConstPool.put(entry.getKey(), sym);
        if (entry.getKey().contains("/")) {
          env.packagedConstImportPool.put(entry.getKey(), sym);
        }
      }
      return env;
    }

    private static AncestorFacetLedger inferLedgerFromSchema(StvnSchema schema) {
      return switch (schema) {
        case StvnSchema.IntSchema i -> new AncestorFacetLedger(
            Optional.of(i.unsigned()), Optional.empty(), Optional.empty(),
            Optional.of(i.bitWidth()),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()
        );
        case StvnSchema.FloatSchema f -> new AncestorFacetLedger(
            Optional.empty(),
            Optional.of(f.representation() == FloatRepresentation.EXACT_DECIMAL),
            Optional.empty(),
            f.representation() == FloatRepresentation.EXACT_DECIMAL
                ? Optional.empty()
                : Optional.of(f.representation() == FloatRepresentation.IEEE_32
                    ? new BigInteger("32")
                    : StvnVocabulary.DEFAULT_FLOAT_BIT_WIDTH),
            Optional.empty(),
            f.representation() == FloatRepresentation.EXACT_DECIMAL
                ? Optional.of(f.exactMaxDigits()) : Optional.empty(),
            Optional.empty(), Optional.empty()
        );
        case StvnSchema.StringSchema s -> new AncestorFacetLedger(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of(s.minSize()),
            Optional.of(s.maxSize()),
            Optional.empty(), Optional.empty()
        );
        case StvnSchema.TimeEpochSchema te -> new AncestorFacetLedger(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of(te.scale()), Optional.empty()
        );
        case StvnSchema.DateTimeSchema dt -> new AncestorFacetLedger(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of(dt.mode())
        );
        case StvnSchema.SeqSchema sq -> new AncestorFacetLedger(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of(sq.minSize()),
            Optional.of(sq.maxSize()),
            Optional.empty(), Optional.empty()
        );
        case StvnSchema.SetSchema st -> new AncestorFacetLedger(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of(st.minSize()),
            Optional.of(st.maxSize()),
            Optional.empty(), Optional.empty()
        );
        case StvnSchema.MapSchema mp -> new AncestorFacetLedger(
            Optional.empty(), Optional.empty(), Optional.of(mp.invertible()), Optional.empty(),
            Optional.of(mp.minSize()),
            Optional.of(mp.maxSize()),
            Optional.empty(), Optional.empty()
        );
        default -> AncestorFacetLedger.EMPTY;
      };
    }

    private static AncestorFacetLedger lockIntrinsicDefaults(StvnSchema schema, AncestorFacetLedger ledger) {
      StvnSchema target = schema;
      if (target instanceof StvnSchema.PoisonedSchema ps && ps.bestEffortFallback().isPresent()) {
        target = ps.bestEffortFallback().get();
      }
      Optional<Boolean> lockedUnsigned = ledger.lockedUnsigned();
      Optional<Boolean> lockedExact = ledger.lockedExact();
      Optional<Boolean> lockedInvertible = ledger.lockedInvertible();
      Optional<BigInteger> lockedSize = ledger.lockedSize();
      Optional<BigInteger> explicitMinSize = ledger.explicitMinSize();
      Optional<BigInteger> explicitMaxSize = ledger.explicitMaxSize();
      Optional<TemporalScale> explicitScale = ledger.explicitScale();
      Optional<DateTimeMode> explicitMode = ledger.explicitMode();

      if (target instanceof StvnSchema.IntSchema intSchema) {
        if (lockedUnsigned.isEmpty()) {
          lockedUnsigned = Optional.of(intSchema.unsigned());
        }
        if (lockedSize.isEmpty()) {
          lockedSize = Optional.of(intSchema.bitWidth());
        }
      } else if (target instanceof StvnSchema.FloatSchema floatSchema) {
        boolean isExact = floatSchema.representation() == FloatRepresentation.EXACT_DECIMAL;
        if (lockedExact.isEmpty()) {
          lockedExact = Optional.of(isExact);
        }
        if (isExact) {
          lockedSize = Optional.empty();
          if (explicitMaxSize.isEmpty()) {
            explicitMaxSize = Optional.of(floatSchema.exactMaxDigits());
          }
        } else if (lockedSize.isEmpty()) {
          lockedSize = Optional.of(
              floatSchema.representation() == FloatRepresentation.IEEE_32
                  ? new BigInteger("32")
                  : StvnVocabulary.DEFAULT_FLOAT_BIT_WIDTH
          );
        }
      } else if (target instanceof StvnSchema.StringSchema strSchema) {
        if (explicitMinSize.isEmpty()) {
          explicitMinSize = Optional.of(strSchema.minSize());
        }
        if (explicitMaxSize.isEmpty()) {
          explicitMaxSize = Optional.of(strSchema.maxSize());
        }
      } else if (target instanceof StvnSchema.SeqSchema seqSchema) {
        if (explicitMinSize.isEmpty()) {
          explicitMinSize = Optional.of(seqSchema.minSize());
        }
        if (explicitMaxSize.isEmpty()) {
          explicitMaxSize = Optional.of(seqSchema.maxSize());
        }
      } else if (target instanceof StvnSchema.SetSchema setSchema) {
        if (explicitMinSize.isEmpty()) {
          explicitMinSize = Optional.of(setSchema.minSize());
        }
        if (explicitMaxSize.isEmpty()) {
          explicitMaxSize = Optional.of(setSchema.maxSize());
        }
      } else if (target instanceof StvnSchema.MapSchema mapSchema) {
        if (lockedInvertible.isEmpty()) {
          lockedInvertible = Optional.of(mapSchema.invertible());
        }
        if (explicitMinSize.isEmpty()) {
          explicitMinSize = Optional.of(mapSchema.minSize());
        }
        if (explicitMaxSize.isEmpty()) {
          explicitMaxSize = Optional.of(mapSchema.maxSize());
        }
      } else if (target instanceof StvnSchema.TimeEpochSchema teSchema) {
        if (explicitScale.isEmpty()) {
          explicitScale = Optional.of(teSchema.scale());
        }
      } else if (target instanceof StvnSchema.DateTimeSchema dtSchema) {
        if (explicitMode.isEmpty()) {
          explicitMode = Optional.of(dtSchema.mode());
        }
      }

      return new AncestorFacetLedger(
          lockedUnsigned,
          lockedExact,
          lockedInvertible,
          lockedSize,
          explicitMinSize,
          explicitMaxSize,
          explicitScale,
          explicitMode
      );
    }

    void collectAndResolveDefs() {
      if (doc == null || doc.documentBody() == null || doc.documentBody().defsEntry(0) == null) {
        return;
      }
      var defsEntry = doc.documentBody().defsEntry(0);
      this.hasDefsSection = true;

      if (defsEntry.LBRACE() != null && defsEntry.RBRACE() != null
          && (defsEntry.defsElement() == null || defsEntry.defsElement().isEmpty())) {
        boolean isSchemaDoc = identity.effectiveKind().map(k -> k == StvnDocumentKind.DEFS || k == StvnDocumentKind.DEFS_INCLUDE).orElse(false)
            || (doc.documentBody().typeEntry(0) == null && doc.documentBody().bodyEntry(0) == null);
        int start = defsEntry.LBRACE().getSymbol().getStartIndex();
        int stop = defsEntry.RBRACE().getSymbol().getStopIndex() + 1;
        int line = defsEntry.LBRACE().getSymbol().getLine();
        int col = defsEntry.LBRACE().getSymbol().getCharPositionInLine();
        String ext = identity.fileExt() != null ? identity.fileExt() : ".stvn_d";
        String msg = isSchemaDoc
            ? "Empty '" + StvnVocabulary.KEYWORD_DEFS + " {}' section is prohibited; definitions schema ("
                + ext + ") requires at least one type or constant definition"
            : "Empty '" + StvnVocabulary.KEYWORD_DEFS + " {}' section is prohibited; omit '"
                + StvnVocabulary.KEYWORD_DEFS + "' or declare at least one type or constant definition";
        diagnosticBag.addError(msg, start, stop, line, col, null, DiagnosticBag.ERR_EMPTY_DEFS_BLOCK);
        return;
      }
      if (defsEntry.defsElement() == null) {
        return;
      }

      // Pre-scan declared local packages and check if :include statements exist
      for (var de : defsEntry.defsElement()) {
        if (de.includeStmt() != null) {
          this.hasIncludes = true;
        } else if (de.packageEnclosure() != null && de.packageEnclosure().packagePath() != null) {
          locallyDeclaredPackages.add(de.packageEnclosure().packagePath().getText());
        } else if (de.typeDefinition() != null) {
          String rawName = de.typeDefinition().typeDefTarget().getText();
          int lastSlash = rawName.lastIndexOf('/');
          if (lastSlash > 1) {
            locallyDeclaredPackages.add(rawName.substring(0, lastSlash));
          }
        }
      }

      Map<String, List<ScopeClaim<ExportedTypeSymbol>>> typeClaims = new LinkedHashMap<>();
      Map<String, List<ScopeClaim<ExportedConstantSymbol>>> constClaims = new LinkedHashMap<>();
      Map<String, List<ScopeClaim<ExportedTypeSymbol>>> packagedTypeClaims = new LinkedHashMap<>();
      Map<String, List<ScopeClaim<ExportedConstantSymbol>>> packagedConstClaims = new LinkedHashMap<>();

      Set<String> seenIncludeRawPaths = new HashSet<>();
      List<StvnParser.UseStmtContext> rootUseStmts = new ArrayList<>();
      List<String> rootLocalUnpackagedTypes = new ArrayList<>();
      List<String> rootLocalUnpackagedConsts = new ArrayList<>();

      for (var de : defsEntry.defsElement()) {
        if (de.includeStmt() != null) {
          processIncludeStmt(
              de.includeStmt(),
              seenIncludeRawPaths,
              typeClaims,
              constClaims,
              packagedTypeClaims,
              packagedConstClaims
          );
        } else if (de.packageEnclosure() != null) {
          processPackageEnclosure(de.packageEnclosure(), typeClaims, constClaims, packagedTypeClaims, packagedConstClaims);
        } else if (de.useStmt() != null) {
          validateUseStmt(de.useStmt());
          rootUseStmts.add(de.useStmt());
        } else if (de.typeDefinition() != null) {
          processRootTypeDef(de.typeDefinition(), typeClaims, packagedTypeClaims, rootLocalUnpackagedTypes);
        } else if (de.constantDefinition() != null) {
          processRootConstDef(de.constantDefinition(), constClaims, packagedConstClaims, rootLocalUnpackagedConsts);
        }
      }

      // Resolve Appendix F eviction cascade across root namespace and packaged pools
      List<String> collisions = new ArrayList<>();
      resolveNamespaceClaims(typeClaims, importedTypePool, collisions);
      resolveNamespaceClaims(constClaims, importedConstPool, collisions);
      resolveNamespaceClaims(packagedTypeClaims, packagedImportPool, collisions);
      resolveNamespaceClaims(packagedConstClaims, packagedConstImportPool, collisions);

      if (!collisions.isEmpty()) {
        Span docSpan = Span.ofContext(doc);
        List<String> uniqueCollisions = new ArrayList<>(new LinkedHashSet<>(collisions));
        for (int i = 0; i < uniqueCollisions.size(); i++) {
          String msg = "Namespace collision(s) detected: " + uniqueCollisions;
          diagnosticBag.addError(
              msg,
              docSpan.start(), docSpan.end(), docSpan.line(), docSpan.column(),
              new NamespaceCollisionException(msg),
              DiagnosticBag.ERR_NAMESPACE_COLLISION
          );
        }
      }

      // Build :use scope frames for root and each :package enclosure
      this.rootScope = buildScopeFrame(null, rootUseStmts, rootLocalUnpackagedTypes, rootLocalUnpackagedConsts);
      for (var de : defsEntry.defsElement()) {
        if (de.packageEnclosure() != null) {
          var pkgEnc = de.packageEnclosure();
          String pkgPrefix = pkgEnc.packagePath().getText();
          if (!moduleCtx.isSystemAuthority() && isReservedPackage(pkgPrefix)) {
            continue;
          }
          List<StvnParser.UseStmtContext> pkgUses = new ArrayList<>();
          List<String> pkgLocalSimpleTypes = new ArrayList<>();
          List<String> pkgLocalSimpleConsts = new ArrayList<>();
          if (pkgEnc.packageElement() != null) {
            for (var pe : pkgEnc.packageElement()) {
              if (pe.useStmt() != null) {
                pkgUses.add(pe.useStmt());
              } else if (pe.typeDefinition() != null) {
                pkgLocalSimpleTypes.add(pe.typeDefinition().typeDefTarget().getText());
              } else if (pe.constantDefinition() != null) {
                pkgLocalSimpleConsts.add(pe.constantDefinition().valueKeyword().getText());
              }
            }
          }
          packageScopes.put(pkgEnc, buildScopeFrame(pkgPrefix, pkgUses, pkgLocalSimpleTypes, pkgLocalSimpleConsts));
        }
      }

      // Eagerly lower all active local nominal types and local constants in declaration order
      for (String fqni : localTypeAsts.keySet()) {
        ensureLocalNominalTypeLowered(fqni, new LinkedHashSet<>(), false);
        verifyRecursiveInhabitationAndPoisonUnguarded();
      }
      verifyRecursiveInhabitationAndPoisonUnguarded();
      for (String fqni : localConstAsts.keySet()) {
        ensureLocalConstantLowered(fqni);
      }

      // Populate module export tables for downstream :include consumers
      String myOriginKey = docPath != null && !docPath.isEmpty()
          ? normalizeOriginKey(docPath)
          : "<inline>";
      for (var entry : importedTypePool.entrySet()) {
        if (entry.getValue() != null) {
          exportedTypes.put(entry.getKey(), entry.getValue());
        }
      }
      for (var entry : packagedImportPool.entrySet()) {
        if (entry.getValue() != null) {
          exportedTypes.put(entry.getKey(), entry.getValue());
        }
      }
      for (var entry : loweredTypes.entrySet()) {
        LoweredSchemaBundle b = entry.getValue();
        exportedTypes.put(entry.getKey(), new ExportedTypeSymbol(entry.getKey(), b.schema(), b.ledger(), myOriginKey));
      }

      for (var entry : importedConstPool.entrySet()) {
        if (entry.getValue() != null) {
          exportedConstants.put(entry.getKey(), entry.getValue());
        }
      }
      for (var entry : packagedConstImportPool.entrySet()) {
        if (entry.getValue() != null) {
          exportedConstants.put(entry.getKey(), entry.getValue());
        }
      }
      for (var entry : loweredConstants.entrySet()) {
        exportedConstants.put(
            entry.getKey(),
            new ExportedConstantSymbol(entry.getKey(), entry.getValue(), myOriginKey)
        );
      }
    }

    private static String normalizeOriginKey(String pathStr) {
      try {
        return Paths.get(pathStr).toAbsolutePath().normalize().toString();
      } catch (Exception e) {
        return pathStr;
      }
    }

    private void processRootTypeDef(
        StvnParser.TypeDefinitionContext typeDef,
        Map<String, List<ScopeClaim<ExportedTypeSymbol>>> typeClaims,
        Map<String, List<ScopeClaim<ExportedTypeSymbol>>> packagedTypeClaims,
        List<String> rootLocalUnpackagedTypes
    ) {
      String typeName = typeDef.typeDefTarget().getText();
      Span targetSpan = Span.ofContext(typeDef.typeDefTarget());

      if (isReservedFundamentalKeyword(typeName) || typeDef.typeDefTarget().reservedKeyword() != null) {
        diagnosticBag.addError(
            "Reserved fundamental type or keyword cannot be used on left-hand side of type definition: " + typeName,
            targetSpan.start(), targetSpan.end(), targetSpan.line(), targetSpan.column(), null,
            DiagnosticBag.ERR_RESERVED_KEYWORD_ON_LHS
        );
        registerPoisonedType(typeName, DiagnosticBag.ERR_RESERVED_KEYWORD_ON_LHS);
        return;
      }

      if (!moduleCtx.isSystemAuthority() && isReservedPackage(typeName)) {
        diagnosticBag.addError(
            "Reserved package namespace prohibited: '" + typeName
                + "' is reserved for runtime and standard library definitions",
            targetSpan.start(), targetSpan.end(), targetSpan.line(), targetSpan.column(), null,
            DiagnosticBag.ERR_RESERVED_PACKAGE_PROHIBITED
        );
        registerPoisonedType(typeName, DiagnosticBag.ERR_RESERVED_PACKAGE_PROHIBITED);
        return;
      }

      Map<String, List<ScopeClaim<ExportedTypeSymbol>>> targetClaimsMap =
          typeName.contains("/") ? packagedTypeClaims : typeClaims;
      if (hasLocalClaim(targetClaimsMap.get(typeName))) {
        Span defSpan = Span.ofContext(typeDef);
        String msg = "Zero-Shadowing constraint violated: " + typeName;
        diagnosticBag.addError(
            msg,
            defSpan.start(), defSpan.end(), defSpan.line(), defSpan.column(),
            new IllegalStateException(msg),
            DiagnosticBag.ERR_DUPLICATE_DEF
        );
        return;
      }

      targetClaimsMap.computeIfAbsent(typeName, k -> new ArrayList<>())
          .add(new ScopeClaim<>(typeName, null, "<local>", ClaimKind.LOCAL));
      if (!typeName.contains("/")) {
        rootLocalUnpackagedTypes.add(typeName);
      }
      localTypeAsts.put(typeName, new LocalTypeAstDecl(typeName, typeDef, null, null));
    }

    private void processRootConstDef(
        StvnParser.ConstantDefinitionContext constDef,
        Map<String, List<ScopeClaim<ExportedConstantSymbol>>> constClaims,
        Map<String, List<ScopeClaim<ExportedConstantSymbol>>> packagedConstClaims,
        List<String> rootLocalUnpackagedConsts
    ) {
      String constName = constDef.valueKeyword().getText();
      if (!moduleCtx.isSystemAuthority() && isReservedConstant(constName)) {
        Span kwSpan = Span.ofContext(constDef.valueKeyword());
        diagnosticBag.addError(
            "Reserved package namespace prohibited: '" + constName
                + "' is reserved for runtime and standard library definitions",
            kwSpan.start(), kwSpan.end(), kwSpan.line(), kwSpan.column(), null,
            DiagnosticBag.ERR_RESERVED_PACKAGE_PROHIBITED
        );
        registerPoisonedConstant(constName, DiagnosticBag.ERR_RESERVED_PACKAGE_PROHIBITED);
        return;
      }

      Map<String, List<ScopeClaim<ExportedConstantSymbol>>> targetClaimsMap =
          constName.contains("/") ? packagedConstClaims : constClaims;
      if (hasLocalClaim(targetClaimsMap.get(constName))) {
        Span defSpan = Span.ofContext(constDef);
        String msg = "Zero-Shadowing constraint violated: " + constName;
        diagnosticBag.addError(
            msg,
            defSpan.start(), defSpan.end(), defSpan.line(), defSpan.column(),
            new IllegalStateException(msg),
            DiagnosticBag.ERR_DUPLICATE_DEF
        );
        return;
      }

      targetClaimsMap.computeIfAbsent(constName, k -> new ArrayList<>())
          .add(new ScopeClaim<>(constName, null, "<local>", ClaimKind.LOCAL));
      if (!constName.contains("/")) {
        rootLocalUnpackagedConsts.add(constName);
      }
      localConstAsts.put(constName, new LocalConstantAstDecl(constName, constName, constDef, null, null));
    }

    private void processPackageEnclosure(
        StvnParser.PackageEnclosureContext pkgEnc,
        Map<String, List<ScopeClaim<ExportedTypeSymbol>>> typeClaims,
        Map<String, List<ScopeClaim<ExportedConstantSymbol>>> constClaims,
        Map<String, List<ScopeClaim<ExportedTypeSymbol>>> packagedTypeClaims,
        Map<String, List<ScopeClaim<ExportedConstantSymbol>>> packagedConstClaims
    ) {
      String pkgPath = pkgEnc.packagePath().getText();
      if (!moduleCtx.isSystemAuthority() && isReservedPackage(pkgPath)) {
        Span pathSpan = Span.ofContext(pkgEnc.packagePath());
        diagnosticBag.addError(
            "Reserved package namespace prohibited: '" + pkgPath
                + "' is reserved for runtime and standard library definitions",
            pathSpan.start(), pathSpan.end(), pathSpan.line(), pathSpan.column(), null,
            DiagnosticBag.ERR_RESERVED_PACKAGE_PROHIBITED
        );
        return;
      }
      if (pkgEnc.packageElement() == null) {
        return;
      }

      for (var pe : pkgEnc.packageElement()) {
        if (pe.nestedPackageIllegal() != null) {
          var illegalNode = pe.nestedPackageIllegal();
          Span sp = Span.ofContext(illegalNode);
          diagnosticBag.addError(
              "Nested packages are prohibited: " + illegalNode.packagePath().getText(),
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_NESTED_PACKAGE_PROHIBITED
          );
        } else if (pe.useStmt() != null) {
          validateUseStmt(pe.useStmt());
        } else if (pe.typeDefinition() != null) {
          var typeDef = pe.typeDefinition();
          String localName = typeDef.typeDefTarget().getText();
          Span targetSpan = Span.ofContext(typeDef.typeDefTarget());
          if (isReservedFundamentalKeyword(localName) || typeDef.typeDefTarget().reservedKeyword() != null) {
            diagnosticBag.addError(
                "Reserved fundamental type or keyword cannot be used on left-hand side of type definition: " + localName,
                targetSpan.start(), targetSpan.end(), targetSpan.line(), targetSpan.column(), null,
                DiagnosticBag.ERR_RESERVED_KEYWORD_ON_LHS
            );
            String fqni = pkgPath + "/" + localName.substring(1);
            registerPoisonedType(fqni, DiagnosticBag.ERR_RESERVED_KEYWORD_ON_LHS);
            continue;
          }
          String fqni = pkgPath + "/" + localName.substring(1);
          if (hasLocalClaim(packagedTypeClaims.get(fqni))) {
            Span defSpan = Span.ofContext(typeDef);
            String msg = "Zero-Shadowing constraint violated: " + fqni;
            diagnosticBag.addError(
                msg,
                defSpan.start(), defSpan.end(), defSpan.line(), defSpan.column(),
                new IllegalStateException(msg),
                DiagnosticBag.ERR_DUPLICATE_DEF
            );
            continue;
          }
          packagedTypeClaims.computeIfAbsent(fqni, k -> new ArrayList<>())
              .add(new ScopeClaim<>(fqni, null, "<local>", ClaimKind.LOCAL));
          localTypeAsts.put(fqni, new LocalTypeAstDecl(fqni, typeDef, pkgPath, pkgEnc));
        } else if (pe.constantDefinition() != null) {
          var constDef = pe.constantDefinition();
          String localName = constDef.valueKeyword().getText();
          String fqni = StvnVocabulary.SIGIL_VALUE + pkgPath.substring(1) + "/" + localName.substring(1);
          if (hasLocalClaim(packagedConstClaims.get(fqni))) {
            Span defSpan = Span.ofContext(constDef);
            String msg = "Zero-Shadowing constraint violated: " + fqni;
            diagnosticBag.addError(
                msg,
                defSpan.start(), defSpan.end(), defSpan.line(), defSpan.column(),
                new IllegalStateException(msg),
                DiagnosticBag.ERR_DUPLICATE_DEF
            );
            continue;
          }
          packagedConstClaims.computeIfAbsent(fqni, k -> new ArrayList<>())
              .add(new ScopeClaim<>(fqni, null, "<local>", ClaimKind.LOCAL));
          localConstAsts.put(fqni, new LocalConstantAstDecl(fqni, localName, constDef, pkgPath, pkgEnc));
        }
      }
    }

    private void validateUseStmt(StvnParser.UseStmtContext useStmt) {
      if (useStmt.useTarget() != null && useStmt.useTarget().useTargetIllegal() != null) {
        var illegal = useStmt.useTarget().useTargetIllegal();
        Span sp = Span.ofContext(illegal);
        diagnosticBag.addError(
            "Trailing slash prohibited in " + StvnVocabulary.KEYWORD_USE + " target: " + illegal.getText(),
            sp.start(), sp.end(), sp.line(), sp.column(), null,
            DiagnosticBag.ERR_TRAILING_SLASH_PROHIBITED
        );
      } else if (useStmt.useTarget() != null) {
        String target = useStmt.useTarget().getText();
        boolean isHermetic = isHermeticUnit();
        if (isHermetic) {
          boolean isPrelude = target.equals(PRELUDE_PKG_ROOT) || target.startsWith(PRELUDE_PKG_ROOT + "/");
          boolean isLocal = locallyDeclaredPackages.contains(target);
          if (!isPrelude && !isLocal) {
            Span sp = Span.ofContext(useStmt.useTarget());
            diagnosticBag.addError(
                "Scoped " + StvnVocabulary.KEYWORD_USE
                    + " in hermetic document (.stvn, .stvn_d, .stvn_b, .stvn_bd) cannot target external package: " + target,
                sp.start(), sp.end(), sp.line(), sp.column(), null,
                DiagnosticBag.ERR_UNRESOLVED_PACKAGE_IN_HERMETIC_DOCUMENT
            );
          }
        }
      }

      if (useStmt.useOptionsBlock() != null) {
        if (useStmt.useOptionsBlock().useOption().isEmpty()) {
          Span sp = Span.ofContext(useStmt.useOptionsBlock());
          diagnosticBag.addError(
              "Empty directive block in " + StvnVocabulary.KEYWORD_USE + " or "
                  + StvnVocabulary.KEYWORD_INCLUDE + " statement is invalid; specify {#strip}, alias mappings, or remove '{}'",
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_EMPTY_DIRECTIVE_BLOCK
          );
        } else {
          for (var opt : useStmt.useOptionsBlock().useOption()) {
            if (opt.standaloneFacetIllegalArg() != null) {
              reportStandaloneFacetIllegalArg(StvnVocabulary.FACET_KW_STRIP, opt.standaloneFacetIllegalArg());
            }
          }
        }
      }
      if (useStmt.useAliasBlock() != null && useStmt.useAliasBlock().useMapAlias().isEmpty()) {
        Span sp = Span.ofContext(useStmt.useAliasBlock());
        diagnosticBag.addError(
            "Empty directive block in " + StvnVocabulary.KEYWORD_USE + " or "
                + StvnVocabulary.KEYWORD_INCLUDE + " statement is invalid; specify {#strip}, alias mappings, or remove '{}'",
            sp.start(), sp.end(), sp.line(), sp.column(), null,
            DiagnosticBag.ERR_EMPTY_DIRECTIVE_BLOCK
        );
      }
    }

    private boolean isHermeticUnit() {
      if (embeddedBinaryDefsMode) {
        return true;
      }
      StvnDocumentKind kind = identity.effectiveKind().orElse(null);
      if (kind == StvnDocumentKind.BODY || kind == StvnDocumentKind.DEFS) {
        return true;
      }
      if (kind == StvnDocumentKind.BODY_INCLUDE || kind == StvnDocumentKind.DEFS_INCLUDE) {
        return false;
      }
      String ext = identity.fileExt();
      if (ext != null && (ext.equals(".stvn") || ext.equals(".stvn_d") || ext.equals(".stvn_b") || ext.equals(".stvn_bd"))) {
        return true;
      }
      return !hasIncludes;
    }

    private boolean prohibitsIncludeStatements() {
      if (embeddedBinaryDefsMode) {
        return true;
      }
      StvnDocumentKind kind = identity.effectiveKind().orElse(null);
      if (kind == StvnDocumentKind.BODY || kind == StvnDocumentKind.DEFS) {
        return true;
      }
      String ext = identity.fileExt();
      return ext != null && (ext.equals(".stvn") || ext.equals(".stvn_d") || ext.equals(".stvn_b") || ext.equals(".stvn_bd"));
    }

    private void processIncludeStmt(
        StvnParser.IncludeStmtContext includeStmt,
        Set<String> seenIncludeRawPaths,
        Map<String, List<ScopeClaim<ExportedTypeSymbol>>> typeClaims,
        Map<String, List<ScopeClaim<ExportedConstantSymbol>>> constClaims,
        Map<String, List<ScopeClaim<ExportedTypeSymbol>>> packagedTypeClaims,
        Map<String, List<ScopeClaim<ExportedConstantSymbol>>> packagedConstClaims
    ) {
      boolean hermeticBlocked = prohibitsIncludeStatements();
      if (hermeticBlocked) {
        Span sp = Span.ofContext(includeStmt);
        String unitName = identity.fileName() != null ? identity.fileName() : "<in-memory>";
        String msg = "Hermetic document or schema (.stvn, .stvn_d, .stvn_b, .stvn_bd) cannot contain include statements: "
            + unitName;
        diagnosticBag.addError(
            msg,
            sp.start(), sp.end(), sp.line(), sp.column(), null,
            DiagnosticBag.ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT
        );
      }

      if (includeStmt.includeElement() == null) {
        return;
      }

      for (var element : includeStmt.includeElement()) {
        boolean hasIllegalOptionArg = false;
        if (element.includeOptionsBlock() != null) {
          if (element.includeOptionsBlock().includeOption().isEmpty()) {
            Span sp = Span.ofContext(element.includeOptionsBlock());
            diagnosticBag.addError(
                "Empty directive block in " + StvnVocabulary.KEYWORD_USE + " or "
                    + StvnVocabulary.KEYWORD_INCLUDE + " statement is invalid; specify {#strip}, alias mappings, or remove '{}'",
                sp.start(), sp.end(), sp.line(), sp.column(), null,
                DiagnosticBag.ERR_EMPTY_DIRECTIVE_BLOCK
            );
          } else {
            for (var opt : element.includeOptionsBlock().includeOption()) {
              if (opt.standaloneFacetIllegalArg() != null) {
                reportStandaloneFacetIllegalArg(StvnVocabulary.FACET_KW_STRIP, opt.standaloneFacetIllegalArg());
                hasIllegalOptionArg = true;
              }
            }
          }
        }
        if (element.includeAliasBlock() != null && element.includeAliasBlock().includeMapAlias().isEmpty()) {
          Span sp = Span.ofContext(element.includeAliasBlock());
          diagnosticBag.addError(
              "Empty directive block in " + StvnVocabulary.KEYWORD_USE + " or "
                  + StvnVocabulary.KEYWORD_INCLUDE + " statement is invalid; specify {#strip}, alias mappings, or remove '{}'",
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_EMPTY_DIRECTIVE_BLOCK
          );
        }
        if (hermeticBlocked
            || hasIllegalOptionArg
            || element.stringLiteral() == null
            || element.stringLiteral() instanceof StvnParser.StringMalformedFencedContext) {
          continue;
        }

        Span pathLitSpan = Span.ofContext(element.stringLiteral());
        Span elemSpan = Span.ofContext(element);
        String pathVal;
        try {
          pathVal = StvnPayloadLowerer.decodeStringLiteral(element.stringLiteral().getText(), true, pathLitSpan);
        } catch (StvnPayloadLowerer.LoweringLiteralException le) {
          diagnosticBag.addErrorDual(
              le.getMessage(), le.span().start(), le.span().end(), le.span().line(), le.span().column(),
              le.getCause(), le.canonicalCode(), le.symbolicId()
          );
          continue;
        }

        String normalizedInc = pathVal.replace('\\', '/');
        int lastSlash = normalizedInc.lastIndexOf('/');
        String incFileName = lastSlash >= 0 ? normalizedInc.substring(lastSlash + 1) : normalizedInc;
        if (rejectLegacyExtension(incFileName, diagnosticBag, pathLitSpan.start(), pathLitSpan.end(), pathLitSpan.line(), pathLitSpan.column())) {
          continue;
        }

        if (!seenIncludeRawPaths.add(pathVal)) {
          String msg = "Duplicate module import detected for path: " + pathVal;
          diagnosticBag.addErrorDual(
              msg,
              elemSpan.start(), elemSpan.end(), elemSpan.line(), elemSpan.column(),
              new DuplicateModuleImportException(msg),
              "E005",
              DiagnosticBag.ERR_DUPLICATE_DEF
          );
          continue;
        }

        CompiledUnit importedUnit;
        if (moduleCtx.virtualWorkspace != null) {
          String resolvedVirtual = resolveVirtualIncludePath(docPath, pathVal);
          if (moduleCtx.activeVirtualPaths.contains(resolvedVirtual)) {
            int cycleStart = moduleCtx.activeVirtualPaths.indexOf(resolvedVirtual);
            List<String> rawSlice = new ArrayList<>();
            List<String> canonicalSlice = new ArrayList<>();
            for (int i = cycleStart + 1; i < moduleCtx.activeVirtualPaths.size(); i++) {
              rawSlice.add(moduleCtx.activeRawPaths.get(i));
              canonicalSlice.add(moduleCtx.activeVirtualPaths.get(i));
            }
            rawSlice.add(pathVal);
            canonicalSlice.add(resolvedVirtual);

            List<String> names = new ArrayList<>();
            String startVirtual = moduleCtx.activeVirtualPaths.get(cycleStart);
            int startSlash = startVirtual.lastIndexOf('/');
            names.add(startSlash < 0 ? startVirtual : startVirtual.substring(startSlash + 1));
            for (String p : canonicalSlice) {
              int ls = p.lastIndexOf('/');
              names.add(ls < 0 ? p : p.substring(ls + 1));
            }
            String trace = String.join(" -> ", names);
            CyclicDependencyException cde = new CyclicDependencyException("Cycle detected: " + trace, rawSlice, canonicalSlice);
            diagnosticBag.addErrorDual(
                cde.getMessage(),
                elemSpan.start(), elemSpan.end(), elemSpan.line(), elemSpan.column(),
                cde,
                "E007",
                "CYCLIC_MODULE_INCLUDE"
            );
            continue;
          }

          if (incFileName.endsWith(".stvn") || incFileName.endsWith(".stvn_i") || incFileName.endsWith(".stvn_b")) {
            String msg = "Invalid " + StvnVocabulary.KEYWORD_INCLUDE + " target '" + pathVal
                + "': payload documents cannot be imported; target must be a definitions module (.stvn_d or .stvn_di)";
            diagnosticBag.addError(
                msg,
                pathLitSpan.start(), pathLitSpan.end(), pathLitSpan.line(), pathLitSpan.column(),
                null,
                DiagnosticBag.ERR_MODULE_IMPORT
            );
            continue;
          }
          if (incFileName.endsWith(".stvn_bd")) {
            String msg = "Invalid " + StvnVocabulary.KEYWORD_INCLUDE + " target '" + pathVal
                + "': binary definitions containers (.stvn_bd) are hermetic wire artifacts and cannot be imported via textual :include; target must be a textual definitions module (.stvn_d or .stvn_di)";
            diagnosticBag.addError(
                msg,
                pathLitSpan.start(), pathLitSpan.end(), pathLitSpan.line(), pathLitSpan.column(),
                null,
                DiagnosticBag.ERR_MODULE_IMPORT
            );
            continue;
          }

          importedUnit = moduleCtx.compiledVirtualModuleCache.get(resolvedVirtual);
          if (importedUnit == null) {
            String content = moduleCtx.virtualWorkspace.get(resolvedVirtual);
            if (content == null) {
              IllegalArgumentException missingEx = new IllegalArgumentException(
                  "Included module path not found in workspace: " + pathVal + " (resolved: " + resolvedVirtual + ")"
              );
              diagnosticBag.addError(
                  missingEx.getMessage(),
                  elemSpan.start(), elemSpan.end(), elemSpan.line(), elemSpan.column(),
                  missingEx,
                  DiagnosticBag.ERR_MODULE_IMPORT
              );
              continue;
            }

            DiagnosticBag childBag = diagnosticBag.withSourcePath(resolvedVirtual);
            int childErrorsBeforePass1 = childBag.errorCount();
            var lexer = new StvnLexer(CharStreams.fromString(content));
            lexer.removeErrorListeners();
            var childTokens = new CommonTokenStream(lexer);
            var parser = new StvnParser(childTokens);
            parser.removeErrorListeners();
            var childListener = StvnErrorListener.accumulating(childBag);
            lexer.addErrorListener(childListener);
            parser.addErrorListener(childListener);
            parser.setErrorHandler(new StvnErrorStrategy());

            StvnParser.StvnDocumentContext importedDocCtx = parser.stvnDocument();
            StvnSyntaxErrorSanitizer.scanMalformedFenceTokens(childTokens, parser, childBag, false);
            boolean childHasPass1SyntaxError =
                parser.getNumberOfSyntaxErrors() > 0 || childBag.errorCount() > childErrorsBeforePass1;

            if (hasNoTopLevelClauses(importedDocCtx) && childHasPass1SyntaxError) {
              moduleCtx.compiledVirtualModuleCache.put(resolvedVirtual, emptyFailedChildUnit());
              continue;
            }

            moduleCtx.activeVirtualPaths.add(resolvedVirtual);
            moduleCtx.activeRawPaths.add(pathVal);
            try {
              importedUnit = lowerDocumentUnit(importedDocCtx, resolvedVirtual, moduleCtx, childBag, false, false, childHasPass1SyntaxError);
            } finally {
              moduleCtx.activeVirtualPaths.removeLast();
              moduleCtx.activeRawPaths.removeLast();
            }

            StvnDocumentKind impKind = importedUnit.effectiveKind().orElse(null);
            if (impKind == StvnDocumentKind.BODY || impKind == StvnDocumentKind.BODY_INCLUDE) {
              String msg = "Invalid " + StvnVocabulary.KEYWORD_INCLUDE + " target '" + pathVal
                  + "': document declares payload kind '" + impKind.keyword() + "'";
              diagnosticBag.addError(
                  msg,
                  pathLitSpan.start(), pathLitSpan.end(), pathLitSpan.line(), pathLitSpan.column(),
                  null,
                  DiagnosticBag.ERR_MODULE_IMPORT
              );
              continue;
            }

            moduleCtx.compiledVirtualModuleCache.put(resolvedVirtual, importedUnit);
          }
        } else {
          Path resolvedPath = resolveIncludePath(docPath, pathVal);
          Path canonicalPath = resolvedPath.toAbsolutePath().normalize();

          if (moduleCtx.activeCanonicalPaths.contains(canonicalPath)) {
            int cycleStart = moduleCtx.activeCanonicalPaths.indexOf(canonicalPath);
            List<String> rawSlice = new ArrayList<>();
            List<String> canonicalSlice = new ArrayList<>();
            for (int i = cycleStart + 1; i < moduleCtx.activeCanonicalPaths.size(); i++) {
              rawSlice.add(moduleCtx.activeRawPaths.get(i));
              canonicalSlice.add(moduleCtx.activeCanonicalPaths.get(i).toString());
            }
            rawSlice.add(pathVal);
            canonicalSlice.add(canonicalPath.toString());

            List<String> names = new ArrayList<>();
            Path startFile = moduleCtx.activeCanonicalPaths.get(cycleStart);
            names.add(startFile.getFileName() != null ? startFile.getFileName().toString() : startFile.toString());
            for (String p : canonicalSlice) {
              int ls = p.replace('\\', '/').lastIndexOf('/');
              names.add(ls == -1 ? p : p.substring(ls + 1));
            }
            String trace = String.join(" -> ", names);
            CyclicDependencyException cde = new CyclicDependencyException("Cycle detected: " + trace, rawSlice, canonicalSlice);
            diagnosticBag.addErrorDual(
                cde.getMessage(),
                elemSpan.start(), elemSpan.end(), elemSpan.line(), elemSpan.column(),
                cde,
                "E007",
                "CYCLIC_MODULE_INCLUDE"
            );
            continue;
          }

          if (incFileName.endsWith(".stvn") || incFileName.endsWith(".stvn_i") || incFileName.endsWith(".stvn_b")) {
            String msg = "Invalid " + StvnVocabulary.KEYWORD_INCLUDE + " target '" + pathVal
                + "': payload documents cannot be imported; target must be a definitions module (.stvn_d or .stvn_di)";
            diagnosticBag.addError(
                msg,
                pathLitSpan.start(), pathLitSpan.end(), pathLitSpan.line(), pathLitSpan.column(),
                null,
                DiagnosticBag.ERR_MODULE_IMPORT
            );
            continue;
          }
          if (incFileName.endsWith(".stvn_bd")) {
            String msg = "Invalid " + StvnVocabulary.KEYWORD_INCLUDE + " target '" + pathVal
                + "': binary definitions containers (.stvn_bd) are hermetic wire artifacts and cannot be imported via textual :include; target must be a textual definitions module (.stvn_d or .stvn_di)";
            diagnosticBag.addError(
                msg,
                pathLitSpan.start(), pathLitSpan.end(), pathLitSpan.line(), pathLitSpan.column(),
                null,
                DiagnosticBag.ERR_MODULE_IMPORT
            );
            continue;
          }

          importedUnit = moduleCtx.compiledModuleCache.get(canonicalPath);
          if (importedUnit == null) {
            String content;
            try {
              content = Files.readString(resolvedPath, StandardCharsets.UTF_8);
            } catch (IOException ioe) {
              diagnosticBag.addError(
                  "Failed to read include file: " + pathVal,
                  elemSpan.start(), elemSpan.end(), elemSpan.line(), elemSpan.column(),
                  ioe,
                  DiagnosticBag.ERR_MODULE_IMPORT
              );
              continue;
            }

            DiagnosticBag childBag = diagnosticBag.withSourcePath(resolvedPath.toString());
            int childErrorsBeforePass1 = childBag.errorCount();
            var lexer = new StvnLexer(CharStreams.fromString(content));
            lexer.removeErrorListeners();
            var childTokens = new CommonTokenStream(lexer);
            var parser = new StvnParser(childTokens);
            parser.removeErrorListeners();
            var childListener = StvnErrorListener.accumulating(childBag);
            lexer.addErrorListener(childListener);
            parser.addErrorListener(childListener);
            parser.setErrorHandler(new StvnErrorStrategy());

            StvnParser.StvnDocumentContext importedDocCtx = parser.stvnDocument();
            StvnSyntaxErrorSanitizer.scanMalformedFenceTokens(childTokens, parser, childBag, false);
            boolean childHasPass1SyntaxError =
                parser.getNumberOfSyntaxErrors() > 0 || childBag.errorCount() > childErrorsBeforePass1;

            if (hasNoTopLevelClauses(importedDocCtx) && childHasPass1SyntaxError) {
              moduleCtx.compiledModuleCache.put(canonicalPath, emptyFailedChildUnit());
              continue;
            }

            moduleCtx.activeCanonicalPaths.add(canonicalPath);
            moduleCtx.activeRawPaths.add(pathVal);
            try {
              importedUnit = lowerDocumentUnit(importedDocCtx, resolvedPath.toString(), moduleCtx, childBag, false, false, childHasPass1SyntaxError);
            } finally {
              moduleCtx.activeCanonicalPaths.removeLast();
              moduleCtx.activeRawPaths.removeLast();
            }

            StvnDocumentKind impKind = importedUnit.effectiveKind().orElse(null);
            if (impKind == StvnDocumentKind.BODY || impKind == StvnDocumentKind.BODY_INCLUDE) {
              String msg = "Invalid " + StvnVocabulary.KEYWORD_INCLUDE + " target '" + pathVal
                  + "': document declares payload kind '" + impKind.keyword() + "'";
              diagnosticBag.addError(
                  msg,
                  pathLitSpan.start(), pathLitSpan.end(), pathLitSpan.line(), pathLitSpan.column(),
                  null,
                  DiagnosticBag.ERR_MODULE_IMPORT
              );
              continue;
            }

            moduleCtx.compiledModuleCache.put(canonicalPath, importedUnit);
          }
        }

        projectIncludedModule(
            importedUnit,
            element,
            typeClaims,
            constClaims,
            packagedTypeClaims,
            packagedConstClaims
        );
      }
    }

    private void projectIncludedModule(
        CompiledUnit importedUnit,
        StvnParser.IncludeElementContext element,
        Map<String, List<ScopeClaim<ExportedTypeSymbol>>> typeClaims,
        Map<String, List<ScopeClaim<ExportedConstantSymbol>>> constClaims,
        Map<String, List<ScopeClaim<ExportedTypeSymbol>>> packagedTypeClaims,
        Map<String, List<ScopeClaim<ExportedConstantSymbol>>> packagedConstClaims
    ) {
      boolean hasStrip = element.includeOptionsBlock() != null
          && element.includeOptionsBlock().includeOption().stream().anyMatch(o -> o.KW_STRIP() != null);

      Map<String, String> aliasMap = new LinkedHashMap<>();
      if (element.includeAliasBlock() != null && element.includeAliasBlock().includeMapAlias() != null) {
        for (var alias : element.includeAliasBlock().includeMapAlias()) {
          if (alias.typeKeyword(0) != null && alias.typeKeyword(1) != null) {
            aliasMap.put(alias.typeKeyword(0).getText(), alias.typeKeyword(1).getText());
          }
        }
      }

      Map<String, String> includeRenames = new LinkedHashMap<>();
      for (String exportedKey : importedUnit.exportedTypes().keySet()) {
        String strippedKey = sliceTerminal(exportedKey);
        if (aliasMap.containsKey(exportedKey)) {
          includeRenames.put(exportedKey, aliasMap.get(exportedKey));
        } else if (hasStrip && aliasMap.containsKey(strippedKey)) {
          includeRenames.put(exportedKey, aliasMap.get(strippedKey));
        } else if (hasStrip && exportedKey.contains("/")) {
          includeRenames.put(exportedKey, strippedKey);
        }
      }

      for (var entry : importedUnit.exportedTypes().entrySet()) {
        String originalFqni = entry.getKey();
        ExportedTypeSymbol sym = entry.getValue();
        boolean isPackaged = originalFqni.contains("/");
        String stripped = sliceTerminal(originalFqni);
        StvnSchema baseSymSchema = includeRenames.isEmpty()
            ? sym.schema()
            : rewriteNestedChildAliases(sym.schema(), includeRenames);
        ExportedTypeSymbol effectiveSym = baseSymSchema == sym.schema()
            ? sym
            : new ExportedTypeSymbol(sym.canonicalFqni(), baseSymSchema, sym.ledger(), sym.originModuleKey());

        if (aliasMap.containsKey(originalFqni) || (hasStrip && aliasMap.containsKey(stripped))) {
          String aliasRhs = aliasMap.containsKey(originalFqni)
              ? aliasMap.get(originalFqni)
              : aliasMap.get(stripped);
          ExportedTypeSymbol projected = new ExportedTypeSymbol(
              aliasRhs,
              rebrandSchema(baseSymSchema, Optional.of(aliasRhs)),
              sym.ledger(),
              sym.originModuleKey()
          );
          Map<String, List<ScopeClaim<ExportedTypeSymbol>>> dest =
              aliasRhs.contains("/") ? packagedTypeClaims : typeClaims;
          dest.computeIfAbsent(aliasRhs, k -> new ArrayList<>())
              .add(new ScopeClaim<>(aliasRhs, projected, sym.originModuleKey() + "::" + originalFqni, ClaimKind.RENAMED_IMPORT_RHS));
          // Note: originalFqni is intentionally evicted from this include projection per Appendix F Scenarios 2 & 3!
        } else if (isPackaged && hasStrip) {
          ExportedTypeSymbol strippedSym = new ExportedTypeSymbol(
              stripped,
              rebrandSchema(baseSymSchema, Optional.of(stripped)),
              sym.ledger(),
              sym.originModuleKey()
          );
          typeClaims.computeIfAbsent(stripped, k -> new ArrayList<>())
              .add(new ScopeClaim<>(stripped, strippedSym, sym.originModuleKey() + "::" + originalFqni, ClaimKind.RAW_IMPORT));
        } else if (isPackaged) {
          // Retain full FQNI in packagedImportPool for qualified :pkg/Name and :use [ :pkg ... ]
          packagedTypeClaims.computeIfAbsent(originalFqni, k -> new ArrayList<>())
              .add(new ScopeClaim<>(originalFqni, effectiveSym, sym.originModuleKey() + "::" + originalFqni, ClaimKind.RAW_IMPORT));
        } else {
          typeClaims.computeIfAbsent(originalFqni, k -> new ArrayList<>())
              .add(new ScopeClaim<>(originalFqni, effectiveSym, sym.originModuleKey() + "::" + originalFqni, ClaimKind.RAW_IMPORT));
        }
      }

      for (var entry : importedUnit.exportedConstants().entrySet()) {
        String originalFqni = entry.getKey();
        ExportedConstantSymbol sym = entry.getValue();
        boolean isPackaged = originalFqni.contains("/");
        String stripped = sliceTerminal(originalFqni);
        ExportedConstantSymbol effectiveConstSym = sym;
        if (!includeRenames.isEmpty()) {
          StvnSchema rewrittenConstSchema = rewriteSchemaAliasesRecursive(sym.constant().schema(), includeRenames, true);
          if (rewrittenConstSchema != sym.constant().schema()) {
            String targetConstFqni = isPackaged && hasStrip ? stripped : sym.canonicalFqni();
            effectiveConstSym = new ExportedConstantSymbol(
                targetConstFqni,
                new StvnDefsCatalog.StvnConstant(targetConstFqni, rewrittenConstSchema, sym.constant().value()),
                sym.originModuleKey()
            );
          }
        }

        if (isPackaged && hasStrip) {
          constClaims.computeIfAbsent(stripped, k -> new ArrayList<>())
              .add(new ScopeClaim<>(stripped, effectiveConstSym, sym.originModuleKey() + "::" + originalFqni, ClaimKind.RAW_IMPORT));
        } else if (isPackaged) {
          packagedConstClaims.computeIfAbsent(originalFqni, k -> new ArrayList<>())
              .add(new ScopeClaim<>(originalFqni, effectiveConstSym, sym.originModuleKey() + "::" + originalFqni, ClaimKind.RAW_IMPORT));
        } else {
          constClaims.computeIfAbsent(originalFqni, k -> new ArrayList<>())
              .add(new ScopeClaim<>(originalFqni, effectiveConstSym, sym.originModuleKey() + "::" + originalFqni, ClaimKind.RAW_IMPORT));
        }
      }
    }

    private static <T> boolean hasLocalClaim(@Nullable List<ScopeClaim<T>> claims) {
      if (claims == null) {
        return false;
      }
      for (var c : claims) {
        if (c.kind() == ClaimKind.LOCAL) {
          return true;
        }
      }
      return false;
    }

    private static <T> void resolveNamespaceClaims(
        Map<String, List<ScopeClaim<T>>> claimsMap,
        Map<String, T> resolvedOut,
        List<String> collisionsOut
    ) {
      for (var entry : claimsMap.entrySet()) {
        String id = entry.getKey();
        List<ScopeClaim<T>> claims = entry.getValue();

        ScopeClaim<T> localClaim = null;
        List<ScopeClaim<T>> rhsClaims = new ArrayList<>();
        List<ScopeClaim<T>> rawClaims = new ArrayList<>();
        Set<String> seenRawOrigins = new HashSet<>();

        for (var c : claims) {
          if (c.kind() == ClaimKind.LOCAL) {
            localClaim = c;
          } else if (c.kind() == ClaimKind.RENAMED_IMPORT_RHS) {
            rhsClaims.add(c);
          } else if (c.kind() == ClaimKind.RAW_IMPORT) {
            if (seenRawOrigins.add(c.originKey())) {
              rawClaims.add(c);
            }
          }
        }

        if (localClaim != null) {
          // Local definitions evict RAW_IMPORT claims cleanly (Appendix F Scenario 1),
          // but collide with explicit RENAMED_IMPORT_RHS targeting the same identifier.
          if (!rhsClaims.isEmpty()) {
            collisionsOut.add(id);
          }
        } else {
          int activeCount = rawClaims.size() + rhsClaims.size();
          if (activeCount > 1) {
            collisionsOut.add(id);
          } else if (rawClaims.size() == 1) {
            resolvedOut.put(id, rawClaims.getFirst().payload());
          } else if (rhsClaims.size() == 1) {
            resolvedOut.put(id, rhsClaims.getFirst().payload());
          }
        }
      }
    }

    private ScopeFrame buildScopeFrame(
        @Nullable String packagePrefix,
        List<StvnParser.UseStmtContext> useStmts,
        List<String> localSimpleTypes,
        List<String> localSimpleConsts
    ) {
      Map<String, List<ScopeClaim<String>>> typeAliasClaims = new LinkedHashMap<>();
      Map<String, List<ScopeClaim<String>>> constAliasClaims = new LinkedHashMap<>();

      // Gather all available packaged FQNIs (local + imported + prelude)
      Map<String, String> availableTypeFqnis = new LinkedHashMap<>();
      Map<String, String> availableConstFqnis = new LinkedHashMap<>();

      if (!moduleCtx.bootstrapPreludeMode) {
        StvnDefsCatalog preludeCat = StvnPrelude.preludeCatalog();
        for (String pType : preludeCat.nominalTypes().keySet()) {
          availableTypeFqnis.put(pType, "Prelude::" + pType);
        }
        for (String pConst : preludeCat.constants().keySet()) {
          availableConstFqnis.put(pConst, "Prelude::" + pConst);
        }
      }
      for (var entry : packagedImportPool.entrySet()) {
        if (entry.getValue() != null) {
          availableTypeFqnis.put(entry.getKey(), entry.getValue().originModuleKey() + "::" + entry.getKey());
        }
      }
      for (var entry : packagedConstImportPool.entrySet()) {
        if (entry.getValue() != null) {
          availableConstFqnis.put(entry.getKey(), entry.getValue().originModuleKey() + "::" + entry.getKey());
        }
      }
      for (String localFqni : localTypeAsts.keySet()) {
        if (localFqni.contains("/")) {
          availableTypeFqnis.put(localFqni, "<local>::" + localFqni);
        }
      }
      for (String localConstFqni : localConstAsts.keySet()) {
        if (localConstFqni.contains("/")) {
          availableConstFqnis.put(localConstFqni, "<local>::" + localConstFqni);
        }
      }

      for (var useStmt : useStmts) {
        if (useStmt.useTarget() == null || useStmt.useTarget().useTargetIllegal() != null) {
          continue;
        }
        String target = useStmt.useTarget().getText();
        boolean hasStrip = useStmt.useOptionsBlock() != null
            && useStmt.useOptionsBlock().useOption().stream()
                .anyMatch(o -> o.KW_STRIP() != null && o.standaloneFacetIllegalArg() == null);

        Map<String, String> typeRenameMap = new LinkedHashMap<>();
        Map<String, String> constRenameMap = new LinkedHashMap<>();
        if (useStmt.useAliasBlock() != null && useStmt.useAliasBlock().useMapAlias() != null) {
          for (var alias : useStmt.useAliasBlock().useMapAlias()) {
            if (alias.typeKeyword(0) != null && alias.typeKeyword(1) != null) {
              typeRenameMap.put(alias.typeKeyword(0).getText(), alias.typeKeyword(1).getText());
            } else if (alias.valueKeyword(0) != null && alias.valueKeyword(1) != null) {
              constRenameMap.put(alias.valueKeyword(0).getText(), alias.valueKeyword(1).getText());
            }
          }
        }

        for (var entry : availableTypeFqnis.entrySet()) {
          String fqni = entry.getKey();
          String origin = entry.getValue();
          if (fqni.equals(target) || fqni.startsWith(target + "/")) {
            String stripped = sliceTerminal(fqni);
            if (typeRenameMap.containsKey(fqni)) {
              String alias = typeRenameMap.get(fqni);
              typeAliasClaims.computeIfAbsent(alias, k -> new ArrayList<>())
                  .add(new ScopeClaim<>(alias, fqni, origin, ClaimKind.RENAMED_IMPORT_RHS));
            } else if (typeRenameMap.containsKey(stripped)) {
              String alias = typeRenameMap.get(stripped);
              typeAliasClaims.computeIfAbsent(alias, k -> new ArrayList<>())
                  .add(new ScopeClaim<>(alias, fqni, origin, ClaimKind.RENAMED_IMPORT_RHS));
            } else if (hasStrip) {
              typeAliasClaims.computeIfAbsent(stripped, k -> new ArrayList<>())
                  .add(new ScopeClaim<>(stripped, fqni, origin, ClaimKind.RAW_IMPORT));
            }
          }
        }

        String constTarget = StvnVocabulary.SIGIL_VALUE
            + (target.startsWith(StvnVocabulary.SIGIL_TYPIC) ? target.substring(1) : target);
        for (var entry : availableConstFqnis.entrySet()) {
          String fqni = entry.getKey();
          String origin = entry.getValue();
          if (fqni.equals(constTarget) || fqni.startsWith(constTarget + "/")) {
            String stripped = sliceTerminal(fqni);
            if (constRenameMap.containsKey(fqni)) {
              String alias = constRenameMap.get(fqni);
              constAliasClaims.computeIfAbsent(alias, k -> new ArrayList<>())
                  .add(new ScopeClaim<>(alias, fqni, origin, ClaimKind.RENAMED_IMPORT_RHS));
            } else if (constRenameMap.containsKey(stripped)) {
              String alias = constRenameMap.get(stripped);
              constAliasClaims.computeIfAbsent(alias, k -> new ArrayList<>())
                  .add(new ScopeClaim<>(alias, fqni, origin, ClaimKind.RENAMED_IMPORT_RHS));
            } else if (hasStrip) {
              constAliasClaims.computeIfAbsent(stripped, k -> new ArrayList<>())
                  .add(new ScopeClaim<>(stripped, fqni, origin, ClaimKind.RAW_IMPORT));
            }
          }
        }
      }

      for (String localSimple : localSimpleTypes) {
        typeAliasClaims.computeIfAbsent(localSimple, k -> new ArrayList<>())
            .add(new ScopeClaim<>(localSimple, localSimple, "<local>", ClaimKind.LOCAL));
      }
      for (String localSimple : localSimpleConsts) {
        constAliasClaims.computeIfAbsent(localSimple, k -> new ArrayList<>())
            .add(new ScopeClaim<>(localSimple, localSimple, "<local>", ClaimKind.LOCAL));
      }

      List<String> scopeCollisions = new ArrayList<>();
      Map<String, String> resolvedTypes = new LinkedHashMap<>();
      Map<String, String> resolvedConsts = new LinkedHashMap<>();
      resolveNamespaceClaims(typeAliasClaims, resolvedTypes, scopeCollisions);
      resolveNamespaceClaims(constAliasClaims, resolvedConsts, scopeCollisions);

      if (!scopeCollisions.isEmpty() && doc != null) {
        Span docSpan = Span.ofContext(doc);
        List<String> uniqueCols = new ArrayList<>(new LinkedHashSet<>(scopeCollisions));
        for (int i = 0; i < uniqueCols.size(); i++) {
          String msg = "Namespace collision(s) detected: " + uniqueCols;
          diagnosticBag.addError(
              msg,
              docSpan.start(), docSpan.end(), docSpan.line(), docSpan.column(),
              new NamespaceCollisionException(msg),
              DiagnosticBag.ERR_NAMESPACE_COLLISION
          );
        }
      }

      return new ScopeFrame(Optional.ofNullable(packagePrefix), resolvedTypes, resolvedConsts);
    }

    ScopeFrame scopeForSite(@Nullable ParserRuleContext site) {
      ParserRuleContext cur = site;
      while (cur != null) {
        if (cur instanceof StvnParser.PackageEnclosureContext pkgEnc) {
          ScopeFrame pkgFrame = packageScopes.get(pkgEnc);
          if (pkgFrame != null) {
            return pkgFrame;
          }
        }
        cur = cur.getParent();
      }
      return rootScope;
    }

    private void reportStandaloneFacetIllegalArg(
        String facetKeyword,
        StvnParser.StandaloneFacetIllegalArgContext illegalArg
    ) {
      Span sp = Span.ofContext(illegalArg);
      diagnosticBag.addError(
          "Metadata facet '" + facetKeyword + "' is a standalone flag and does not accept arguments; unexpected '"
              + illegalArg.getText() + "'",
          sp.start(), sp.end(), sp.line(), sp.column(), null,
          DiagnosticBag.ERR_INVALID_METADATA_FACET
      );
    }

    private void registerPoisonedType(String fqni, String symbolicId) {
      StvnSchema.PoisonedSchema poisoned = new StvnSchema.PoisonedSchema(
          Optional.of(fqni),
          symbolicId,
          Optional.empty(),
          TraitProfile.ofDefault(true, true)
      );
      loweredTypes.put(fqni, new LoweredSchemaBundle(poisoned, AncestorFacetLedger.EMPTY));
    }

    static boolean isRealPoisonedSchema(StvnSchema schema) {
      return schema instanceof StvnSchema.PoisonedSchema p
          && !StvnPayloadLowerer.RECURSIVE_BACK_EDGE_SENTINEL.equals(p.primaryDiagnosticCode());
    }

    static boolean isRecursiveBackEdge(StvnSchema schema) {
      return schema instanceof StvnSchema.PoisonedSchema p
          && StvnPayloadLowerer.RECURSIVE_BACK_EDGE_SENTINEL.equals(p.primaryDiagnosticCode());
    }

    static StvnSchema rebrandSchema(StvnSchema schema, Optional<String> newAliasName) {
      return switch (schema) {
        case StvnSchema.BooleanSchema s -> new StvnSchema.BooleanSchema(newAliasName, s.traits());
        case StvnSchema.IntSchema s -> new StvnSchema.IntSchema(
            newAliasName, s.unsigned(), s.bitWidth(), s.traits(), s.explicitMinIncl(), s.explicitMaxExcl()
        );
        case StvnSchema.FloatSchema s -> new StvnSchema.FloatSchema(
            newAliasName, s.representation(), s.exactMaxDigits(), s.traits(), s.lowerBound(), s.upperBound()
        );
        case StvnSchema.StringSchema s -> new StvnSchema.StringSchema(
            newAliasName, s.minSize(), s.maxSize(), s.traits(), s.lowerBound(), s.upperBound(),
            s.preserveIndent(), s.compiledRegex(), s.rawRegexPattern()
        );
        case StvnSchema.TimeEpochSchema s -> new StvnSchema.TimeEpochSchema(
            newAliasName, s.scale(), s.traits(), s.explicitMinIncl(), s.explicitMaxExcl()
        );
        case StvnSchema.DateTimeSchema s -> new StvnSchema.DateTimeSchema(
            newAliasName, s.mode(), s.traits(), s.explicitMinInclIso(), s.explicitMaxExclIso()
        );
        case StvnSchema.EnumSchema s -> new StvnSchema.EnumSchema(
            newAliasName, s.rootVariants(), s.traits()
        );
        case StvnSchema.EnumSubsetSchema s -> new StvnSchema.EnumSubsetSchema(
            newAliasName, s.parentEnum(), s.rootEnum(), s.activeVariants(),
            s.inclusiveFilter(), s.declaredFilterList(), s.traits()
        );
        case StvnSchema.TupleSchema s -> new StvnSchema.TupleSchema(newAliasName, s.fieldSchemas(), s.traits());
        case StvnSchema.OptionSchema s -> new StvnSchema.OptionSchema(newAliasName, s.valueSchema(), s.traits());
        case StvnSchema.EitherSchema s -> new StvnSchema.EitherSchema(newAliasName, s.leftSchema(), s.rightSchema(), s.traits());
        case StvnSchema.UnionSchema s -> new StvnSchema.UnionSchema(newAliasName, s.branchSchemas(), s.traits());
        case StvnSchema.SeqSchema s -> new StvnSchema.SeqSchema(
            newAliasName, s.minSize(), s.maxSize(), s.elementSchema(), s.traits()
        );
        case StvnSchema.SetSchema s -> new StvnSchema.SetSchema(
            newAliasName, s.minSize(), s.maxSize(), s.elementSchema(), s.traits()
        );
        case StvnSchema.MapSchema s -> new StvnSchema.MapSchema(
            newAliasName, s.invertible(), s.minSize(), s.maxSize(), s.keySchema(), s.valueSchema(), s.traits()
        );
        case StvnSchema.PoisonedSchema s -> new StvnSchema.PoisonedSchema(
            newAliasName, s.primaryDiagnosticCode(), s.bestEffortFallback(), s.traits()
        );
      };
    }

    static StvnSchema rewriteNestedChildAliases(StvnSchema schema, Map<String, String> renames) {
      if (renames.isEmpty()) {
        return schema;
      }
      return rewriteSchemaAliasesRecursive(schema, renames, false);
    }

    private static StvnSchema rewriteSchemaAliasesRecursive(
        StvnSchema schema,
        Map<String, String> renames,
        boolean rewriteTopAlias
    ) {
      Optional<String> effectiveAlias = schema.aliasName();
      if (rewriteTopAlias && effectiveAlias.isPresent() && renames.containsKey(effectiveAlias.get())) {
        effectiveAlias = Optional.of(renames.get(effectiveAlias.get()));
      }
      return switch (schema) {
        case StvnSchema.BooleanSchema s -> effectiveAlias.equals(s.aliasName())
            ? s
            : new StvnSchema.BooleanSchema(effectiveAlias, s.traits());
        case StvnSchema.IntSchema s -> effectiveAlias.equals(s.aliasName())
            ? s
            : new StvnSchema.IntSchema(
                effectiveAlias, s.unsigned(), s.bitWidth(), s.traits(), s.explicitMinIncl(), s.explicitMaxExcl()
            );
        case StvnSchema.FloatSchema s -> effectiveAlias.equals(s.aliasName())
            ? s
            : new StvnSchema.FloatSchema(
                effectiveAlias, s.representation(), s.exactMaxDigits(), s.traits(), s.lowerBound(), s.upperBound()
            );
        case StvnSchema.StringSchema s -> effectiveAlias.equals(s.aliasName())
            ? s
            : new StvnSchema.StringSchema(
                effectiveAlias, s.minSize(), s.maxSize(), s.traits(), s.lowerBound(), s.upperBound(),
                s.preserveIndent(), s.compiledRegex(), s.rawRegexPattern()
            );
        case StvnSchema.TimeEpochSchema s -> effectiveAlias.equals(s.aliasName())
            ? s
            : new StvnSchema.TimeEpochSchema(
                effectiveAlias, s.scale(), s.traits(), s.explicitMinIncl(), s.explicitMaxExcl()
            );
        case StvnSchema.DateTimeSchema s -> effectiveAlias.equals(s.aliasName())
            ? s
            : new StvnSchema.DateTimeSchema(
                effectiveAlias, s.mode(), s.traits(), s.explicitMinInclIso(), s.explicitMaxExclIso()
            );
        case StvnSchema.EnumSchema s -> effectiveAlias.equals(s.aliasName())
            ? s
            : new StvnSchema.EnumSchema(effectiveAlias, s.rootVariants(), s.traits());
        case StvnSchema.EnumSubsetSchema s -> new StvnSchema.EnumSubsetSchema(
            effectiveAlias,
            rewriteSchemaAliasesRecursive(s.parentEnum(), renames, true),
            (StvnSchema.EnumSchema) rewriteSchemaAliasesRecursive(s.rootEnum(), renames, true),
            s.activeVariants(),
            s.inclusiveFilter(),
            s.declaredFilterList(),
            s.traits()
        );
        case StvnSchema.TupleSchema s -> {
          List<StvnSchema> rewrittenFields = new ArrayList<>(s.fieldSchemas().size());
          for (StvnSchema f : s.fieldSchemas()) {
            rewrittenFields.add(rewriteSchemaAliasesRecursive(f, renames, true));
          }
          yield new StvnSchema.TupleSchema(effectiveAlias, rewrittenFields, s.traits());
        }
        case StvnSchema.OptionSchema s -> new StvnSchema.OptionSchema(
            effectiveAlias,
            rewriteSchemaAliasesRecursive(s.valueSchema(), renames, true),
            s.traits()
        );
        case StvnSchema.EitherSchema s -> new StvnSchema.EitherSchema(
            effectiveAlias,
            rewriteSchemaAliasesRecursive(s.leftSchema(), renames, true),
            rewriteSchemaAliasesRecursive(s.rightSchema(), renames, true),
            s.traits()
        );
        case StvnSchema.UnionSchema s -> {
          List<StvnSchema> rewrittenBranches = new ArrayList<>(s.branchSchemas().size());
          for (StvnSchema b : s.branchSchemas()) {
            rewrittenBranches.add(rewriteSchemaAliasesRecursive(b, renames, true));
          }
          yield new StvnSchema.UnionSchema(effectiveAlias, rewrittenBranches, s.traits());
        }
        case StvnSchema.SeqSchema s -> new StvnSchema.SeqSchema(
            effectiveAlias,
            s.minSize(),
            s.maxSize(),
            rewriteSchemaAliasesRecursive(s.elementSchema(), renames, true),
            s.traits()
        );
        case StvnSchema.SetSchema s -> new StvnSchema.SetSchema(
            effectiveAlias,
            s.minSize(),
            s.maxSize(),
            rewriteSchemaAliasesRecursive(s.elementSchema(), renames, true),
            s.traits()
        );
        case StvnSchema.MapSchema s -> new StvnSchema.MapSchema(
            effectiveAlias,
            s.invertible(),
            s.minSize(),
            s.maxSize(),
            rewriteSchemaAliasesRecursive(s.keySchema(), renames, true),
            rewriteSchemaAliasesRecursive(s.valueSchema(), renames, true),
            s.traits()
        );
        case StvnSchema.PoisonedSchema s -> effectiveAlias.equals(s.aliasName())
            ? s
            : new StvnSchema.PoisonedSchema(
                effectiveAlias, s.primaryDiagnosticCode(), s.bestEffortFallback(), s.traits()
            );
      };
    }

    Optional<StvnSchema> findLoweredNominalSchema(String fqni) {
      LoweredSchemaBundle local = loweredTypes.get(fqni);
      if (local != null) {
        return Optional.of(local.schema());
      }
      ExportedTypeSymbol imp = importedTypePool.get(fqni);
      if (imp != null) {
        return Optional.of(imp.schema());
      }
      ExportedTypeSymbol pkgImp = packagedImportPool.get(fqni);
      if (pkgImp != null) {
        return Optional.of(pkgImp.schema());
      }
      if (!moduleCtx.bootstrapPreludeMode && fqni.startsWith(PRELUDE_PKG_ROOT + "/")) {
        return StvnPrelude.preludeCatalog().findType(fqni);
      }
      return Optional.empty();
    }

    StvnDefsCatalog buildCatalog() {
      Map<String, StvnSchema> nominalTypes = new LinkedHashMap<>();
      for (var entry : importedTypePool.entrySet()) {
        if (entry.getValue() != null) {
          nominalTypes.put(entry.getKey(), entry.getValue().schema());
        }
      }
      for (var entry : packagedImportPool.entrySet()) {
        if (entry.getValue() != null) {
          nominalTypes.put(entry.getKey(), entry.getValue().schema());
        }
      }
      for (var entry : loweredTypes.entrySet()) {
        nominalTypes.put(entry.getKey(), entry.getValue().schema());
      }

      Map<String, StvnDefsCatalog.StvnConstant> constants = new LinkedHashMap<>();
      for (var entry : importedConstPool.entrySet()) {
        if (entry.getValue() != null && !isPoisonedConstant(entry.getValue().constant())) {
          constants.put(entry.getKey(), entry.getValue().constant());
        }
      }
      for (var entry : packagedConstImportPool.entrySet()) {
        if (entry.getValue() != null && !isPoisonedConstant(entry.getValue().constant())) {
          constants.put(entry.getKey(), entry.getValue().constant());
        }
      }
      for (var entry : loweredConstants.entrySet()) {
        if (!isPoisonedConstant(entry.getValue())) {
          constants.put(entry.getKey(), entry.getValue());
        }
      }
      return new StvnDefsCatalog(nominalTypes, constants);
    }

    private static boolean isPoisonedConstant(StvnDefsCatalog.StvnConstant constant) {
      return isRealPoisonedSchema(constant.schema()) || constant.value() instanceof StvnValue.StvnErrorValue;
    }

    // =========================================================================
    // SCOPED CONSTANT & TYPE SYMBOL RESOLUTION (§ 4.2)
    // =========================================================================

    Optional<StvnDefsCatalog.StvnConstant> resolveConstantAtSite(String rawToken, @Nullable ParserRuleContext site) {
      ScopeFrame scope = scopeForSite(site);
      return resolveConstantInScope(rawToken, scope);
    }

    private Optional<StvnDefsCatalog.StvnConstant> resolveConstantInScope(String rawToken, ScopeFrame scope) {
      if (scope.constAliases().containsKey(rawToken)) {
        String mapped = scope.constAliases().get(rawToken);
        if (mapped.equals(rawToken) && scope.packagePrefix().isPresent() && !rawToken.contains("/")) {
          String pkgConstFqni = StvnVocabulary.SIGIL_VALUE
              + scope.packagePrefix().get().substring(1) + "/" + rawToken.substring(1);
          Optional<StvnDefsCatalog.StvnConstant> sibling = lookupConstantByFqni(pkgConstFqni);
          if (sibling.isPresent()) {
            return sibling;
          }
        }
        Optional<StvnDefsCatalog.StvnConstant> aliased = lookupConstantByFqni(mapped);
        if (aliased.isPresent()) {
          return aliased;
        }
      }
      if (scope.packagePrefix().isPresent() && !rawToken.contains("/")) {
        String pkgConstFqni = StvnVocabulary.SIGIL_VALUE
            + scope.packagePrefix().get().substring(1) + "/" + rawToken.substring(1);
        Optional<StvnDefsCatalog.StvnConstant> sibling = lookupConstantByFqni(pkgConstFqni);
        if (sibling.isPresent()) {
          return sibling;
        }
      }
      if (scope != rootScope && rootScope.constAliases().containsKey(rawToken)) {
        String rootMapped = rootScope.constAliases().get(rawToken);
        Optional<StvnDefsCatalog.StvnConstant> rootAliased = lookupConstantByFqni(rootMapped);
        if (rootAliased.isPresent()) {
          return rootAliased;
        }
      }
      return lookupConstantByFqni(rawToken);
    }

    private Optional<StvnDefsCatalog.StvnConstant> lookupConstantByFqni(String fqni) {
      if (loweredConstants.containsKey(fqni)) {
        return Optional.of(loweredConstants.get(fqni));
      }
      if (localConstAsts.containsKey(fqni)) {
        return ensureLocalConstantLowered(fqni);
      }
      ExportedConstantSymbol imp = importedConstPool.get(fqni);
      if (imp != null) {
        return Optional.of(imp.constant());
      }
      ExportedConstantSymbol pkgImp = packagedConstImportPool.get(fqni);
      if (pkgImp != null && fqni.contains("/")) {
        return Optional.of(pkgImp.constant());
      }
      String preludeConstPrefix = StvnVocabulary.SIGIL_VALUE + "org/stvnadore/prelude/";
      if (!moduleCtx.bootstrapPreludeMode && fqni.startsWith(preludeConstPrefix)) {
        return StvnPrelude.preludeCatalog().findConstant(fqni);
      }
      return Optional.empty();
    }

    private Optional<LoweredSchemaBundle> resolveNominalTypeInScope(
        String rawKw,
        ScopeFrame scope,
        Set<String> visitedTypeStack,
        boolean passedConstructor
    ) {
      if (scope.typeAliases().containsKey(rawKw)) {
        String mapped = scope.typeAliases().get(rawKw);
        if (mapped.equals(rawKw) && scope.packagePrefix().isPresent() && !rawKw.contains("/")) {
          String siblingFqni = scope.packagePrefix().get() + "/" + rawKw.substring(1);
          Optional<LoweredSchemaBundle> sibling = lookupTypeByFqni(siblingFqni, visitedTypeStack, passedConstructor);
          if (sibling.isPresent()) {
            return sibling;
          }
        }
        Optional<LoweredSchemaBundle> aliased = lookupTypeByFqni(mapped, visitedTypeStack, passedConstructor);
        if (aliased.isPresent()) {
          return aliased;
        }
      }
      if (scope.packagePrefix().isPresent() && !rawKw.contains("/")) {
        String siblingFqni = scope.packagePrefix().get() + "/" + rawKw.substring(1);
        Optional<LoweredSchemaBundle> sibling = lookupTypeByFqni(siblingFqni, visitedTypeStack, passedConstructor);
        if (sibling.isPresent()) {
          return sibling;
        }
      }
      if (scope != rootScope && rootScope.typeAliases().containsKey(rawKw)) {
        String rootMapped = rootScope.typeAliases().get(rawKw);
        Optional<LoweredSchemaBundle> rootAliased = lookupTypeByFqni(rootMapped, visitedTypeStack, passedConstructor);
        if (rootAliased.isPresent()) {
          return rootAliased;
        }
      }
      return lookupTypeByFqni(rawKw, visitedTypeStack, passedConstructor);
    }

    private Optional<LoweredSchemaBundle> lookupTypeByFqni(
        String fqni,
        Set<String> visitedTypeStack,
        boolean passedConstructor
    ) {
      if (localTypeAsts.containsKey(fqni)) {
        return Optional.of(ensureLocalNominalTypeLowered(fqni, visitedTypeStack, passedConstructor));
      }
      if (loweredTypes.containsKey(fqni)) {
        return Optional.of(loweredTypes.get(fqni));
      }
      ExportedTypeSymbol imp = importedTypePool.get(fqni);
      if (imp != null) {
        return Optional.of(new LoweredSchemaBundle(imp.schema(), imp.ledger()));
      }
      ExportedTypeSymbol pkgImp = packagedImportPool.get(fqni);
      if (pkgImp != null && fqni.contains("/")) {
        return Optional.of(new LoweredSchemaBundle(pkgImp.schema(), pkgImp.ledger()));
      }
      if (!moduleCtx.bootstrapPreludeMode && fqni.startsWith(PRELUDE_PKG_ROOT + "/")) {
        Optional<StvnSchema> pOpt = StvnPrelude.preludeCatalog().findType(fqni);
        if (pOpt.isPresent()) {
          return Optional.of(new LoweredSchemaBundle(pOpt.get(), inferLedgerFromSchema(pOpt.get())));
        }
      }
      return Optional.empty();
    }

    // =========================================================================
    // LOCAL CONSTANT & NOMINAL TYPE LOWERING WITH CYCLE & POISON ISOLATION
    // =========================================================================

    private StvnDefsCatalog.StvnConstant registerPoisonedConstant(String fqni, String code) {
      StvnSchema.PoisonedSchema poisonedSchema = new StvnSchema.PoisonedSchema(
          Optional.empty(),
          code,
          Optional.empty(),
          TraitProfile.ofDefault(true, true)
      );
      org.stvnadore.core.StvnDiagnostic syntheticDiag = new org.stvnadore.core.StvnDiagnostic(
          "Poisoned constant sentinel: " + fqni,
          org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity.ERROR,
          1,
          0,
          0,
          1,
          null,
          DiagnosticBag.resolveCanonicalCode(code),
          Optional.of(code)
      ).withSourcePath(diagnosticBag.defaultSourcePath().orElse(null));
      StvnValue.StvnErrorValue errVal = new StvnValue.StvnErrorValue(poisonedSchema, syntheticDiag);
      StvnDefsCatalog.StvnConstant sentinel = new StvnDefsCatalog.StvnConstant(fqni, poisonedSchema, errVal);
      loweredConstants.put(fqni, sentinel);
      return sentinel;
    }

    private Optional<StvnDefsCatalog.StvnConstant> ensureLocalConstantLowered(String fqni) {
      if (loweredConstants.containsKey(fqni)) {
        return Optional.of(loweredConstants.get(fqni));
      }
      LocalConstantAstDecl decl = localConstAsts.get(fqni);
      if (decl == null) {
        return Optional.empty();
      }
      if (evaluatingConstants.contains(fqni)) {
        Span sp = Span.ofContext(decl.ctx());
        String trace = String.join(" -> ", evaluatingConstants) + " -> " + fqni;
        String msg = "Circular constant definition detected: " + trace;
        diagnosticBag.addError(
            msg,
            sp.start(), sp.end(), sp.line(), sp.column(),
            new CircularReferenceException(msg),
            DiagnosticBag.ERR_CIRCULAR_TYPE
        );
        return Optional.of(registerPoisonedConstant(fqni, DiagnosticBag.ERR_CIRCULAR_TYPE));
      }

      evaluatingConstants.add(fqni);
      try {
        ScopeFrame scope = decl.packageCtx() != null
            ? packageScopes.getOrDefault(decl.packageCtx(), rootScope)
            : rootScope;
        LoweredSchemaBundle schemaBundle = lowerSchemaNode(
            fqni,
            Optional.empty(),
            decl.ctx().schemaType(),
            scope,
            new LinkedHashSet<>(),
            true,
            false
        );
        StvnSchema constSchema = schemaBundle.schema();
        if (isRealPoisonedSchema(constSchema)) {
          if (constSchema instanceof StvnSchema.PoisonedSchema ps
              && DiagnosticBag.ERR_EMPTY_METADATA_BLOCK.equals(ps.primaryDiagnosticCode())
              && ps.bestEffortFallback().isPresent()
              && decl.ctx().value() != null) {
            StvnSchema fallbackSchema = ps.bestEffortFallback().get();
            StvnValue fallbackVal = StvnPayloadLowerer.lowerConstantValue(
                decl.displayName(),
                decl.ctx().value(),
                fallbackSchema,
                this::resolveConstantAtSite,
                this::findLoweredNominalSchema,
                diagnosticBag
            );
            if (!(fallbackVal instanceof StvnValue.StvnErrorValue)) {
              StvnDefsCatalog.StvnConstant lowered = new StvnDefsCatalog.StvnConstant(fqni, fallbackSchema, fallbackVal);
              loweredConstants.put(fqni, lowered);
              return Optional.of(lowered);
            }
          }
          return Optional.of(registerPoisonedConstant(fqni, DiagnosticBag.ERR_MALFORMED_SCHEMA));
        }
        if (decl.ctx().value() == null) {
          return Optional.of(registerPoisonedConstant(fqni, DiagnosticBag.ERR_MALFORMED_SCHEMA));
        }
        StvnValue val = StvnPayloadLowerer.lowerConstantValue(
            decl.displayName(),
            decl.ctx().value(),
            constSchema,
            this::resolveConstantAtSite,
            this::findLoweredNominalSchema,
            diagnosticBag
        );
        if (val instanceof StvnValue.StvnErrorValue) {
          return Optional.of(registerPoisonedConstant(fqni, DiagnosticBag.ERR_MALFORMED_SCHEMA));
        }
        StvnDefsCatalog.StvnConstant lowered = new StvnDefsCatalog.StvnConstant(fqni, constSchema, val);
        loweredConstants.put(fqni, lowered);
        return Optional.of(lowered);
      } finally {
        evaluatingConstants.remove(fqni);
      }
    }

    private LoweredSchemaBundle ensureLocalNominalTypeLowered(
        String fqni,
        Set<String> visitedTypeStack,
        boolean passedConstructor
    ) {
      if (loweredTypes.containsKey(fqni)) {
        return loweredTypes.get(fqni);
      }
      if (visitedTypeStack.contains(fqni)) {
        if (passedConstructor) {
          StvnSchema.PoisonedSchema backEdge = new StvnSchema.PoisonedSchema(
              Optional.of(fqni),
              StvnPayloadLowerer.RECURSIVE_BACK_EDGE_SENTINEL,
              Optional.empty(),
              TraitProfile.ofDefault(true, true)
          );
          return new LoweredSchemaBundle(backEdge, AncestorFacetLedger.EMPTY);
        }
        LocalTypeAstDecl decl = localTypeAsts.get(fqni);
        Span sp = decl != null ? Span.ofContext(decl.ctx()) : new Span(0, 1, 1, 0);
        String trace = String.join(" -> ", visitedTypeStack) + " -> " + fqni;
        String msg = "Circular type definition detected: " + trace;
        diagnosticBag.addError(
            msg,
            sp.start(), sp.end(), sp.line(), sp.column(),
            new CircularReferenceException(msg),
            DiagnosticBag.ERR_CIRCULAR_TYPE
        );
        circularDiagnosticsEmitted.add(fqni);
        registerPoisonedType(fqni, DiagnosticBag.ERR_CIRCULAR_TYPE);
        return loweredTypes.get(fqni);
      }

      LocalTypeAstDecl decl = localTypeAsts.get(fqni);
      if (decl == null) {
        registerPoisonedType(fqni, DiagnosticBag.ERR_UNDEFINED_TYPE);
        return loweredTypes.get(fqni);
      }

      ScopeFrame scope = decl.packageCtx() != null
          ? packageScopes.getOrDefault(decl.packageCtx(), rootScope)
          : rootScope;

      Set<String> nextVisited = new LinkedHashSet<>(visitedTypeStack);
      nextVisited.add(fqni);

      LoweredSchemaBundle rawBundle = lowerSchemaNode(
          fqni,
          Optional.of(fqni),
          decl.ctx().schemaType(),
          scope,
          nextVisited,
          passedConstructor,
          true
      );
      boolean declHadPass1SyntaxError = hasPass1SyntaxErrorInSpan(diagnosticBag, Span.ofContext(decl.ctx()));
      if (isRealPoisonedSchema(rawBundle.schema()) || declHadPass1SyntaxError) {
        Optional<StvnSchema> bestEffort = extractBestEffortFallback(rawBundle.schema(), Optional.of(fqni));
        StvnSchema.PoisonedSchema poisoned = new StvnSchema.PoisonedSchema(
            Optional.of(fqni),
            rawBundle.schema() instanceof StvnSchema.PoisonedSchema ps
                ? ps.primaryDiagnosticCode()
                : (declHadPass1SyntaxError ? "STVN_SYNTAX_ERROR" : DiagnosticBag.ERR_MALFORMED_SCHEMA),
            bestEffort,
            rawBundle.schema().traits()
        );
        LoweredSchemaBundle poisonedBundle = new LoweredSchemaBundle(poisoned, rawBundle.ledger());
        loweredTypes.put(fqni, poisonedBundle);
        return poisonedBundle;
      }

      StvnSchema branded = rebrandSchema(rawBundle.schema(), Optional.of(fqni));
      LoweredSchemaBundle finalized = new LoweredSchemaBundle(branded, rawBundle.ledger());
      loweredTypes.put(fqni, finalized);
      return finalized;
    }

    private @Nullable String resolveLocalNominalKey(String rawKw, ScopeFrame scope) {
      if (scope.typeAliases().containsKey(rawKw)) {
        String mapped = scope.typeAliases().get(rawKw);
        if (mapped.equals(rawKw) && scope.packagePrefix().isPresent() && !rawKw.contains("/")) {
          String siblingFqni = scope.packagePrefix().get() + "/" + rawKw.substring(1);
          if (localTypeAsts.containsKey(siblingFqni)) {
            return siblingFqni;
          }
        }
        if (localTypeAsts.containsKey(mapped)) {
          return mapped;
        }
      }
      if (scope.packagePrefix().isPresent() && !rawKw.contains("/")) {
        String siblingFqni = scope.packagePrefix().get() + "/" + rawKw.substring(1);
        if (localTypeAsts.containsKey(siblingFqni)) {
          return siblingFqni;
        }
      }
      if (scope != rootScope && rootScope.typeAliases().containsKey(rawKw)) {
        String rootMapped = rootScope.typeAliases().get(rawKw);
        if (localTypeAsts.containsKey(rootMapped)) {
          return rootMapped;
        }
      }
      if (localTypeAsts.containsKey(rawKw)) {
        return rawKw;
      }
      return null;
    }

    private String buildCircularDerivationTrace(String startFqni, String fallbackTarget) {
      List<String> chain = new ArrayList<>();
      Set<String> seen = new LinkedHashSet<>();
      String curr = startFqni;
      while (curr != null && seen.add(curr)) {
        if (structuralCycleTraces.containsKey(curr)) {
          chain.add(structuralCycleTraces.get(curr));
          return String.join(" -> ", chain);
        }
        chain.add(curr);
        LocalTypeAstDecl ast = localTypeAsts.get(curr);
        if (ast == null || ast.ctx().schemaType() == null || ast.ctx().schemaType().typeKeyword() == null) {
          curr = null;
          break;
        }
        ScopeFrame astScope = ast.packageCtx() != null
            ? packageScopes.getOrDefault(ast.packageCtx(), rootScope)
            : rootScope;
        String nextRaw = ast.ctx().schemaType().typeKeyword().getText();
        curr = resolveLocalNominalKey(nextRaw, astScope);
      }
      if (curr != null) {
        chain.add(curr);
      } else if (!chain.contains(fallbackTarget)) {
        chain.add(fallbackTarget);
      }
      return String.join(" -> ", chain);
    }

    private void verifyRecursiveInhabitationAndPoisonUnguarded() {
      Set<String> cyclicTypes = new LinkedHashSet<>();
      for (Map.Entry<String, LoweredSchemaBundle> entry : loweredTypes.entrySet()) {
        String fqni = entry.getKey();
        StvnSchema schema = entry.getValue().schema();
        if (!isRealPoisonedSchema(schema) && reachesTargetAlias(schema, fqni, new HashSet<>())) {
          cyclicTypes.add(fqni);
        }
      }
      if (cyclicTypes.isEmpty()) {
        return;
      }

      Map<String, Boolean> inhabited = new LinkedHashMap<>();
      for (Map.Entry<String, LoweredSchemaBundle> entry : loweredTypes.entrySet()) {
        String fqni = entry.getKey();
        StvnSchema schema = entry.getValue().schema();
        if (cyclicTypes.contains(fqni)) {
          inhabited.put(fqni, false);
        } else if (schema instanceof StvnSchema.PoisonedSchema ps
            && DiagnosticBag.ERR_CIRCULAR_TYPE.equals(ps.primaryDiagnosticCode())
            && ps.isUngrounded()) {
          inhabited.put(fqni, false);
        } else {
          inhabited.put(fqni, true);
        }
      }

      boolean changed = true;
      while (changed) {
        changed = false;
        for (String fqni : cyclicTypes) {
          if (!Boolean.TRUE.equals(inhabited.get(fqni))) {
            StvnSchema schema = loweredTypes.get(fqni).schema();
            if (isSchemaFinitelyInhabited(schema, inhabited)) {
              inhabited.put(fqni, true);
              changed = true;
            }
          }
        }
      }

      Set<String> newlyPoisoned = new LinkedHashSet<>();
      for (String fqni : cyclicTypes) {
        if (!Boolean.TRUE.equals(inhabited.get(fqni))) {
          newlyPoisoned.add(fqni);
          String trace = buildStructuralCycleTrace(fqni, cyclicTypes, inhabited);
          structuralCycleTraces.put(fqni, trace);
          if (!circularDiagnosticsEmitted.contains(fqni)) {
            LocalTypeAstDecl decl = localTypeAsts.get(fqni);
            Span sp = decl != null ? Span.ofContext(decl.ctx()) : new Span(0, 1, 1, 0);
            String msg = "Circular type definition detected: " + trace;
            diagnosticBag.addError(
                msg,
                sp.start(), sp.end(), sp.line(), sp.column(),
                new CircularReferenceException(msg),
                DiagnosticBag.ERR_CIRCULAR_TYPE
            );
            circularDiagnosticsEmitted.add(fqni);
          }
          registerPoisonedType(fqni, DiagnosticBag.ERR_CIRCULAR_TYPE);
        }
      }

      if (!newlyPoisoned.isEmpty()) {
        boolean stemChanged = true;
        while (stemChanged) {
          stemChanged = false;
          for (Map.Entry<String, LocalTypeAstDecl> entry : localTypeAsts.entrySet()) {
            String fqni = entry.getKey();
            if (newlyPoisoned.contains(fqni) || !loweredTypes.containsKey(fqni)) {
              continue;
            }
            LocalTypeAstDecl ast = entry.getValue();
            if (ast.ctx().schemaType() != null && ast.ctx().schemaType().typeKeyword() != null) {
              ScopeFrame astScope = ast.packageCtx() != null
                  ? packageScopes.getOrDefault(ast.packageCtx(), rootScope)
                  : rootScope;
              String rawTarget = ast.ctx().schemaType().typeKeyword().getText();
              String targetKey = resolveLocalNominalKey(rawTarget, astScope);
              if (targetKey != null && newlyPoisoned.contains(targetKey)) {
                newlyPoisoned.add(fqni);
                stemChanged = true;
                String trace = buildCircularDerivationTrace(fqni, rawTarget);
                structuralCycleTraces.put(fqni, trace);
                if (!circularDiagnosticsEmitted.contains(fqni)) {
                  Span sp = Span.ofContext(ast.ctx());
                  String msg = "Circular type definition detected: " + trace;
                  diagnosticBag.addError(
                      msg,
                      sp.start(), sp.end(), sp.line(), sp.column(),
                      new CircularReferenceException(msg),
                      DiagnosticBag.ERR_CIRCULAR_TYPE
                  );
                  circularDiagnosticsEmitted.add(fqni);
                }
                registerPoisonedType(fqni, DiagnosticBag.ERR_CIRCULAR_TYPE);
              }
            }
          }
        }

        for (String fqni : new ArrayList<>(loweredTypes.keySet())) {
          if (!newlyPoisoned.contains(fqni)) {
            LoweredSchemaBundle bundle = loweredTypes.get(fqni);
            StvnSchema updated = replaceNewlyPoisonedReferences(bundle.schema(), newlyPoisoned);
            if (updated != bundle.schema()) {
              loweredTypes.put(fqni, new LoweredSchemaBundle(updated, bundle.ledger()));
            }
          }
        }
      }
    }

    private boolean reachesTargetAlias(
        @Nullable StvnSchema schema,
        String targetFqni,
        Set<String> visitedAliases
    ) {
      if (schema == null) {
        return false;
      }
      if (schema instanceof StvnSchema.PoisonedSchema ps) {
        if (StvnPayloadLowerer.RECURSIVE_BACK_EDGE_SENTINEL.equals(ps.primaryDiagnosticCode())) {
          String backTarget = ps.aliasName().orElse("");
          if (backTarget.equals(targetFqni)) {
            return true;
          }
          if (!backTarget.isEmpty() && visitedAliases.add(backTarget)) {
            LoweredSchemaBundle b = loweredTypes.get(backTarget);
            if (b != null) {
              return reachesTargetAlias(b.schema(), targetFqni, visitedAliases);
            }
          }
        }
        return false;
      }
      List<StvnSchema> children = switch (schema) {
        case StvnSchema.TupleSchema ts -> ts.fieldSchemas();
        case StvnSchema.SeqSchema ss -> List.of(ss.elementSchema());
        case StvnSchema.SetSchema sts -> List.of(sts.elementSchema());
        case StvnSchema.MapSchema ms -> List.of(ms.keySchema(), ms.valueSchema());
        case StvnSchema.OptionSchema os -> List.of(os.valueSchema());
        case StvnSchema.EitherSchema es -> List.of(es.leftSchema(), es.rightSchema());
        case StvnSchema.UnionSchema us -> us.branchSchemas();
        default -> List.of();
      };
      for (StvnSchema child : children) {
        if (child.aliasName().isPresent() && child.aliasName().get().equals(targetFqni)) {
          return true;
        }
        if (reachesTargetAlias(child, targetFqni, visitedAliases)) {
          return true;
        }
      }
      return false;
    }

    private boolean isSchemaFinitelyInhabited(
        @Nullable StvnSchema schema,
        Map<String, Boolean> inhabited
    ) {
      if (schema == null) {
        return true;
      }
      if (schema instanceof StvnSchema.PoisonedSchema ps) {
        if (StvnPayloadLowerer.RECURSIVE_BACK_EDGE_SENTINEL.equals(ps.primaryDiagnosticCode())) {
          String target = ps.aliasName().orElse("");
          return Boolean.TRUE.equals(inhabited.getOrDefault(target, false));
        }
        if (ps.bestEffortFallback().isPresent()) {
          return isSchemaFinitelyInhabited(ps.bestEffortFallback().get(), inhabited);
        }
        if (DiagnosticBag.ERR_CIRCULAR_TYPE.equals(ps.primaryDiagnosticCode())) {
          return false;
        }
        return true;
      }
      return switch (schema) {
        case StvnSchema.BooleanSchema bs -> true;
        case StvnSchema.IntSchema is -> true;
        case StvnSchema.FloatSchema fs -> true;
        case StvnSchema.StringSchema ss -> true;
        case StvnSchema.TimeEpochSchema ts -> true;
        case StvnSchema.DateTimeSchema ds -> true;
        case StvnSchema.EnumSchema es -> true;
        case StvnSchema.EnumSubsetSchema ess -> true;
        case StvnSchema.OptionSchema os -> true;
        case StvnSchema.SeqSchema ss ->
            ss.minSize().compareTo(BigInteger.ZERO) <= 0
                || isSchemaFinitelyInhabited(ss.elementSchema(), inhabited);
        case StvnSchema.SetSchema sts ->
            sts.minSize().compareTo(BigInteger.ZERO) <= 0
                || isSchemaFinitelyInhabited(sts.elementSchema(), inhabited);
        case StvnSchema.MapSchema ms ->
            ms.minSize().compareTo(BigInteger.ZERO) <= 0
                || (isSchemaFinitelyInhabited(ms.keySchema(), inhabited)
                    && isSchemaFinitelyInhabited(ms.valueSchema(), inhabited));
        case StvnSchema.TupleSchema ts ->
            ts.fieldSchemas().stream().allMatch(f -> isSchemaFinitelyInhabited(f, inhabited));
        case StvnSchema.EitherSchema es ->
            isSchemaFinitelyInhabited(es.leftSchema(), inhabited)
                || isSchemaFinitelyInhabited(es.rightSchema(), inhabited);
        case StvnSchema.UnionSchema us ->
            us.branchSchemas().stream().anyMatch(b -> isSchemaFinitelyInhabited(b, inhabited));
        case StvnSchema.PoisonedSchema ps -> true;
      };
    }

    private String buildStructuralCycleTrace(
        String startFqni,
        Set<String> cyclicTypes,
        Map<String, Boolean> inhabited
    ) {
      List<String> chain = new ArrayList<>();
      Set<String> visited = new LinkedHashSet<>();
      String curr = startFqni;
      while (curr != null && visited.add(curr)) {
        chain.add(curr);
        LoweredSchemaBundle bundle = loweredTypes.get(curr);
        if (bundle == null) {
          curr = null;
          break;
        }
        curr = findFirstUninhabitedTarget(bundle.schema(), cyclicTypes, inhabited);
      }
      if (curr != null) {
        chain.add(curr);
      } else {
        chain.add(startFqni);
      }
      return String.join(" -> ", chain);
    }

    private @Nullable String findFirstUninhabitedTarget(
        @Nullable StvnSchema schema,
        Set<String> cyclicTypes,
        Map<String, Boolean> inhabited
    ) {
      if (schema == null) {
        return null;
      }
      if (schema instanceof StvnSchema.PoisonedSchema ps) {
        if (StvnPayloadLowerer.RECURSIVE_BACK_EDGE_SENTINEL.equals(ps.primaryDiagnosticCode())) {
          String target = ps.aliasName().orElse(null);
          if (target != null && !Boolean.TRUE.equals(inhabited.get(target))) {
            return target;
          }
        }
        return null;
      }
      List<StvnSchema> children = switch (schema) {
        case StvnSchema.TupleSchema ts -> ts.fieldSchemas();
        case StvnSchema.SeqSchema ss -> List.of(ss.elementSchema());
        case StvnSchema.SetSchema sts -> List.of(sts.elementSchema());
        case StvnSchema.MapSchema ms -> List.of(ms.keySchema(), ms.valueSchema());
        case StvnSchema.OptionSchema os -> List.of(os.valueSchema());
        case StvnSchema.EitherSchema es -> List.of(es.leftSchema(), es.rightSchema());
        case StvnSchema.UnionSchema us -> us.branchSchemas();
        default -> List.of();
      };
      for (StvnSchema child : children) {
        if (child.aliasName().isPresent()
            && cyclicTypes.contains(child.aliasName().get())
            && !Boolean.TRUE.equals(inhabited.get(child.aliasName().get()))) {
          return child.aliasName().get();
        }
        String nested = findFirstUninhabitedTarget(child, cyclicTypes, inhabited);
        if (nested != null) {
          return nested;
        }
      }
      return null;
    }

    private StvnSchema replaceNewlyPoisonedReferences(StvnSchema schema, Set<String> newlyPoisoned) {
      if (schema.aliasName().isPresent() && newlyPoisoned.contains(schema.aliasName().get())) {
        LoweredSchemaBundle poisonedBundle = loweredTypes.get(schema.aliasName().get());
        if (poisonedBundle != null) {
          return rebrandSchema(poisonedBundle.schema(), schema.aliasName());
        }
      }
      return switch (schema) {
        case StvnSchema.TupleSchema ts -> {
          List<StvnSchema> updatedFields = new ArrayList<>(ts.fieldSchemas().size());
          boolean changed = false;
          for (StvnSchema f : ts.fieldSchemas()) {
            StvnSchema uf = replaceNewlyPoisonedReferences(f, newlyPoisoned);
            if (uf != f) {
              changed = true;
            }
            updatedFields.add(uf);
          }
          yield changed ? new StvnSchema.TupleSchema(ts.aliasName(), updatedFields, ts.traits()) : ts;
        }
        case StvnSchema.SeqSchema ss -> {
          StvnSchema ue = replaceNewlyPoisonedReferences(ss.elementSchema(), newlyPoisoned);
          yield ue != ss.elementSchema()
              ? new StvnSchema.SeqSchema(ss.aliasName(), ss.minSize(), ss.maxSize(), ue, ss.traits())
              : ss;
        }
        case StvnSchema.SetSchema sts -> {
          StvnSchema ue = replaceNewlyPoisonedReferences(sts.elementSchema(), newlyPoisoned);
          yield ue != sts.elementSchema()
              ? new StvnSchema.SetSchema(sts.aliasName(), sts.minSize(), sts.maxSize(), ue, sts.traits())
              : sts;
        }
        case StvnSchema.MapSchema ms -> {
          StvnSchema uk = replaceNewlyPoisonedReferences(ms.keySchema(), newlyPoisoned);
          StvnSchema uv = replaceNewlyPoisonedReferences(ms.valueSchema(), newlyPoisoned);
          yield (uk != ms.keySchema() || uv != ms.valueSchema())
              ? new StvnSchema.MapSchema(ms.aliasName(), ms.invertible(), ms.minSize(), ms.maxSize(), uk, uv, ms.traits())
              : ms;
        }
        case StvnSchema.OptionSchema os -> {
          StvnSchema uv = replaceNewlyPoisonedReferences(os.valueSchema(), newlyPoisoned);
          yield uv != os.valueSchema()
              ? new StvnSchema.OptionSchema(os.aliasName(), uv, os.traits())
              : os;
        }
        case StvnSchema.EitherSchema es -> {
          StvnSchema ul = replaceNewlyPoisonedReferences(es.leftSchema(), newlyPoisoned);
          StvnSchema ur = replaceNewlyPoisonedReferences(es.rightSchema(), newlyPoisoned);
          yield (ul != es.leftSchema() || ur != es.rightSchema())
              ? new StvnSchema.EitherSchema(es.aliasName(), ul, ur, es.traits())
              : es;
        }
        case StvnSchema.UnionSchema us -> {
          List<StvnSchema> updatedBranches = new ArrayList<>(us.branchSchemas().size());
          boolean changed = false;
          for (StvnSchema b : us.branchSchemas()) {
            StvnSchema ub = replaceNewlyPoisonedReferences(b, newlyPoisoned);
            if (ub != b) {
              changed = true;
            }
            updatedBranches.add(ub);
          }
          yield changed ? new StvnSchema.UnionSchema(us.aliasName(), updatedBranches, us.traits()) : us;
        }
        default -> schema;
      };
    }

    LoweredSchemaBundle lowerAnonymousOrRootSchema(
        StvnParser.SchemaTypeContext schemaCtx,
        ScopeFrame scope,
        Set<String> visitedTypeStack,
        boolean passedConstructor
    ) {
      return lowerSchemaNode(
          "<" + StvnVocabulary.KEYWORD_TYPE + ">",
          Optional.empty(),
          schemaCtx,
          scope,
          visitedTypeStack,
          passedConstructor,
          false
      );
    }

    // =========================================================================
    // SCHEMA CONSTRUCTOR & § 6.1 94-ROW METADATA FACET MATRIX LOWERER
    // =========================================================================

    private LoweredSchemaBundle lowerSchemaNode(
        String ownerLabel,
        Optional<String> targetNominalAlias,
        StvnParser.SchemaTypeContext schemaCtx,
        ScopeFrame scope,
        Set<String> visitedTypeStack,
        boolean passedConstructor,
        boolean isNominalDeclarationRhs
    ) {
      int errBefore = diagnosticBag.errorCount();
      var metaMap = schemaCtx.metadataMap();

      if (metaMap != null && metaMap.LBRACE() != null && metaMap.RBRACE() != null
          && (metaMap.metadataEntry() == null || metaMap.metadataEntry().isEmpty())) {
        int start = metaMap.LBRACE().getSymbol().getStartIndex();
        int stop = metaMap.RBRACE().getSymbol().getStopIndex() + 1;
        int line = metaMap.LBRACE().getSymbol().getLine();
        int col = metaMap.LBRACE().getSymbol().getCharPositionInLine();
        diagnosticBag.addError(
            "Empty metadata block is invalid; remove '{}' or specify valid facets",
            start, stop, line, col, null,
            DiagnosticBag.ERR_EMPTY_METADATA_BLOCK
        );
      }
      boolean hasEmptyMetaBlockError = diagnosticBag.errorCount() > errBefore;

      // Case 1: Nominal reference (possibly with derivation facets)
      if (schemaCtx.typeKeyword() != null) {
        String rawKw = schemaCtx.typeKeyword().getText();
        Span kwSpan = Span.ofContext(schemaCtx.typeKeyword());

        Optional<LoweredSchemaBundle> parentOpt = resolveNominalTypeInScope(
            rawKw, scope, visitedTypeStack, passedConstructor
        );
        if (parentOpt.isEmpty()) {
          String errCode = isPurgedPreludeAlias(rawKw)
              ? DiagnosticBag.ERR_PRELUDE_ALIAS_PURGED
              : DiagnosticBag.ERR_UNKNOWN_TYPE;
          String msg = isPurgedPreludeAlias(rawKw)
              ? "Purged legacy prelude alias is no longer available: " + rawKw
              : "Undefined type: " + rawKw;
          diagnosticBag.addError(
              msg,
              kwSpan.start(), kwSpan.end(), kwSpan.line(), kwSpan.column(),
              new MalformedSchemaException(msg),
              errCode
          );
          return poisonedBundle(targetNominalAlias, errCode);
        }

        LoweredSchemaBundle parentBundle = parentOpt.get();
        int childFacetErrBefore = diagnosticBag.errorCount() - (hasEmptyMetaBlockError ? 1 : 0);
        StvnSchema parentSchema = parentBundle.schema();
        if (isRealPoisonedSchema(parentSchema)) {
          Optional<String> effectiveBrand = targetNominalAlias.isPresent()
              ? targetNominalAlias
              : parentSchema.aliasName();
          if (parentSchema instanceof StvnSchema.PoisonedSchema ps && ps.bestEffortFallback().isPresent()) {
            StvnSchema fallbackBase = ps.bestEffortFallback().get();
            if (metaMap != null && metaMap.metadataEntry() != null && !metaMap.metadataEntry().isEmpty()) {
              String parentName = parentSchema.aliasName().orElse(rawKw);
              LoweredSchemaBundle validatedFallback = applyFacetsToBaseSchema(
                  ownerLabel,
                  effectiveBrand,
                  baseTypeKeywordOf(fallbackBase),
                  fallbackBase,
                  parentName,
                  parentBundle.ledger(),
                  true,
                  metaMap,
                  schemaCtx,
                  scope,
                  childFacetErrBefore
              );
              Optional<StvnSchema> nextFallback = extractBestEffortFallback(validatedFallback.schema(), effectiveBrand);
              StvnSchema.PoisonedSchema nextPoisoned = new StvnSchema.PoisonedSchema(
                  effectiveBrand,
                  ps.primaryDiagnosticCode(),
                  nextFallback,
                  validatedFallback.schema().traits()
              );
              return new LoweredSchemaBundle(nextPoisoned, validatedFallback.ledger());
            }
            StvnSchema rebrandedParent = targetNominalAlias.isPresent()
                ? rebrandSchema(parentSchema, targetNominalAlias)
                : parentSchema;
            return new LoweredSchemaBundle(rebrandedParent, parentBundle.ledger());
          }
          if (isNominalDeclarationRhs
              && parentSchema instanceof StvnSchema.PoisonedSchema ps
              && DiagnosticBag.ERR_CIRCULAR_TYPE.equals(ps.primaryDiagnosticCode())
              && ps.isUngrounded()
              && !circularDiagnosticsEmitted.contains(ownerLabel)) {
            LocalTypeAstDecl decl = localTypeAsts.get(ownerLabel);
            Span sp = decl != null ? Span.ofContext(decl.ctx()) : Span.ofContext(schemaCtx);
            String cycleTrace = buildCircularDerivationTrace(ownerLabel, rawKw);
            String msg = "Circular type definition detected: " + cycleTrace;
            diagnosticBag.addError(
                msg,
                sp.start(), sp.end(), sp.line(), sp.column(),
                new CircularReferenceException(msg),
                DiagnosticBag.ERR_CIRCULAR_TYPE
            );
            circularDiagnosticsEmitted.add(ownerLabel);
          }
          // Suppress downstream E030 / secondary cascades when referencing a poisoned nominal type
          return new LoweredSchemaBundle(
              targetNominalAlias.isPresent() ? rebrandSchema(parentSchema, targetNominalAlias) : parentSchema,
              AncestorFacetLedger.EMPTY
          );
        }
        if (isRecursiveBackEdge(parentSchema)) {
          if (metaMap != null && metaMap.metadataEntry() != null && !metaMap.metadataEntry().isEmpty()) {
            Span sp = Span.ofContext(metaMap);
            diagnosticBag.addError(
                "Metadata facets cannot be applied directly to an unguarded recursive back-edge reference: " + rawKw,
                sp.start(), sp.end(), sp.line(), sp.column(), null,
                DiagnosticBag.ERR_MALFORMED_SCHEMA
            );
            return poisonedBundle(targetNominalAlias, DiagnosticBag.ERR_MALFORMED_SCHEMA);
          }
          return parentBundle;
        }

        // If no local metadata facets and anonymous reference (e.g. :type :MyAlias or field :MyAlias), preserve parent brand!
        if (metaMap == null || metaMap.metadataEntry() == null || metaMap.metadataEntry().isEmpty()) {
          if (hasEmptyMetaBlockError) {
            Optional<String> effectiveBrand = targetNominalAlias.isPresent()
                ? targetNominalAlias
                : parentSchema.aliasName();
            Optional<StvnSchema> bestEffort = extractBestEffortFallback(parentSchema, effectiveBrand);
            return new LoweredSchemaBundle(
                new StvnSchema.PoisonedSchema(
                    effectiveBrand,
                    DiagnosticBag.ERR_EMPTY_METADATA_BLOCK,
                    bestEffort,
                    parentSchema.traits()
                ),
                parentBundle.ledger()
            );
          }
          StvnSchema outSchema = targetNominalAlias.isPresent()
              ? rebrandSchema(parentSchema, targetNominalAlias)
              : parentSchema;
          return new LoweredSchemaBundle(outSchema, parentBundle.ledger());
        }

        // Apply derivation facets on top of parentSchema!
        String parentName = parentSchema.aliasName().orElse(rawKw);
        Optional<String> effectiveBrand = targetNominalAlias.isPresent()
            ? targetNominalAlias
            : parentSchema.aliasName();
        return applyFacetsToBaseSchema(
            ownerLabel,
            effectiveBrand,
            baseTypeKeywordOf(parentSchema),
            parentSchema,
            parentName,
            parentBundle.ledger(),
            true,
            metaMap,
            schemaCtx,
            scope,
            childFacetErrBefore
        );
      }

      // Case 2: Concrete schemaConstructor
      var ctor = schemaCtx.schemaConstructor();
      if (ctor == null) {
        return poisonedBundle(targetNominalAlias, DiagnosticBag.ERR_MALFORMED_SCHEMA);
      }

      if (ctor.atomicType() != null) {
        var atom = ctor.atomicType();
        String baseKw = atom.getText();
        return applyFacetsToRootAtomic(
            ownerLabel,
            targetNominalAlias,
            baseKw,
            atom,
            metaMap,
            scope,
            errBefore
        );
      }

      if (ctor.collectionType() != null) {
        var coll = ctor.collectionType();
        if (coll.COLL_SEQ() != null) {
          LoweredSchemaBundle elemBundle = lowerSchemaNode(
              ownerLabel, Optional.empty(), coll.schemaType(0), scope, visitedTypeStack, true, false
          );
          StvnSchema elem = elemBundle.schema();
          boolean elemPoisoned = isRealPoisonedSchema(elem);
          TraitProfile defaultTraits = TraitProfile.ofDefault(
              elemPoisoned || elem.traits().equatable(),
              elemPoisoned || elem.traits().comparable()
          );
          StvnSchema baseSeq = new StvnSchema.SeqSchema(
              targetNominalAlias, BigInteger.ZERO, StvnVocabulary.DEFAULT_MAX_SIZE, elem, defaultTraits
          );
          int effectiveErrBefore = diagnosticBag.errorCount() - (hasEmptyMetaBlockError ? 1 : 0);
          return applyFacetsToBaseSchema(
              ownerLabel, targetNominalAlias, StvnVocabulary.TYPE_SEQ,
              baseSeq, null, AncestorFacetLedger.EMPTY, false, metaMap, schemaCtx, scope, effectiveErrBefore
          );
        }
        if (coll.COLL_SET() != null) {
          var elemCtx = coll.schemaType(0);
          LoweredSchemaBundle elemBundle = lowerSchemaNode(
              ownerLabel, Optional.empty(), elemCtx, scope, visitedTypeStack, true, false
          );
          StvnSchema elem = elemBundle.schema();
          boolean childPoisoned = isRealPoisonedSchema(elem);
          boolean traitViolation = !childPoisoned && !elem.traits().equatable();
          if (traitViolation) {
            Span sp = Span.ofContext(elemCtx);
            diagnosticBag.addError(
                "Set elements require types to be #equatable #TRUE",
                sp.start(), sp.end(), sp.line(), sp.column(), null,
                DiagnosticBag.ERR_TRAIT_VIOLATION
            );
          }
          TraitProfile defaultTraits = TraitProfile.ofDefault(childPoisoned || elem.traits().equatable(), false);
          StvnSchema baseSet = new StvnSchema.SetSchema(
              targetNominalAlias, BigInteger.ZERO, StvnVocabulary.DEFAULT_MAX_SIZE, elem, defaultTraits
          );
          if (traitViolation) {
            validateFacetsOnlyIfPresent(ownerLabel, StvnVocabulary.TYPE_SET, baseSet, null, AncestorFacetLedger.EMPTY, false, metaMap, schemaCtx, scope);
            return poisonedBundle(targetNominalAlias, DiagnosticBag.ERR_TRAIT_VIOLATION);
          }
          int effectiveErrBefore = diagnosticBag.errorCount() - (hasEmptyMetaBlockError ? 1 : 0);
          return applyFacetsToBaseSchema(
              ownerLabel, targetNominalAlias, StvnVocabulary.TYPE_SET,
              baseSet, null, AncestorFacetLedger.EMPTY, false, metaMap, schemaCtx, scope, effectiveErrBefore
          );
        }
        if (coll.COLL_MAP() != null) {
          var keyCtx = coll.schemaType(0);
          var valCtx = coll.schemaType(1);
          LoweredSchemaBundle keyBundle = lowerSchemaNode(
              ownerLabel, Optional.empty(), keyCtx, scope, visitedTypeStack, true, false
          );
          LoweredSchemaBundle valBundle = lowerSchemaNode(
              ownerLabel, Optional.empty(), valCtx, scope, visitedTypeStack, true, false
          );
          StvnSchema keySchema = keyBundle.schema();
          StvnSchema valSchema = valBundle.schema();

          boolean localInvertible = hasLocalInvertibleFlag(metaMap);
          boolean keyPoisoned = isRealPoisonedSchema(keySchema);
          boolean valPoisoned = isRealPoisonedSchema(valSchema);
          boolean traitViolation = false;
          if (!keyPoisoned && !keySchema.traits().equatable()) {
            Span sp = Span.ofContext(keyCtx);
            diagnosticBag.addError(
                "Map keys require types to be #equatable #TRUE",
                sp.start(), sp.end(), sp.line(), sp.column(), null,
                DiagnosticBag.ERR_TRAIT_VIOLATION
            );
            traitViolation = true;
          }
          if (localInvertible && !valPoisoned && !valSchema.traits().equatable()) {
            Span sp = Span.ofContext(valCtx);
            diagnosticBag.addError(
                "Inverted map values require types to be #equatable #TRUE",
                sp.start(), sp.end(), sp.line(), sp.column(), null,
                DiagnosticBag.ERR_TRAIT_VIOLATION
            );
            traitViolation = true;
          }

          boolean keyEq = keyPoisoned || keySchema.traits().equatable();
          boolean valEq = valPoisoned || valSchema.traits().equatable();
          boolean mapDefaultEq = localInvertible ? (keyEq && valEq) : keyEq;
          TraitProfile defaultTraits = TraitProfile.ofDefault(mapDefaultEq, false);
          StvnSchema baseMap = new StvnSchema.MapSchema(
              targetNominalAlias, false,
              BigInteger.ZERO, StvnVocabulary.DEFAULT_MAX_SIZE, keySchema, valSchema, defaultTraits
          );
          if (traitViolation) {
            validateFacetsOnlyIfPresent(ownerLabel, StvnVocabulary.TYPE_MAP, baseMap, null, AncestorFacetLedger.EMPTY, false, metaMap, schemaCtx, scope);
            return poisonedBundle(targetNominalAlias, DiagnosticBag.ERR_TRAIT_VIOLATION);
          }
          int effectiveErrBefore = diagnosticBag.errorCount() - (hasEmptyMetaBlockError ? 1 : 0);
          return applyFacetsToBaseSchema(
              ownerLabel, targetNominalAlias, StvnVocabulary.TYPE_MAP,
              baseMap, null, AncestorFacetLedger.EMPTY, false, metaMap, schemaCtx, scope, effectiveErrBefore
          );
        }
      }

      if (ctor.productType() instanceof StvnParser.TupleTypeContext tupCtx) {
        List<StvnSchema> elems = new ArrayList<>();
        boolean allEq = true;
        boolean allComp = true;
        for (var childCtx : tupCtx.schemaType()) {
          LoweredSchemaBundle cb = lowerSchemaNode(
              ownerLabel, Optional.empty(), childCtx, scope, visitedTypeStack, true, false
          );
          StvnSchema cs = cb.schema();
          elems.add(cs);
          if (!isRealPoisonedSchema(cs)) {
            allEq = allEq && cs.traits().equatable();
            allComp = allComp && cs.traits().comparable();
          }
        }
        StvnSchema baseTup = new StvnSchema.TupleSchema(
            targetNominalAlias, elems, TraitProfile.ofDefault(allEq, allComp)
        );
        int effectiveErrBefore = diagnosticBag.errorCount() - (hasEmptyMetaBlockError ? 1 : 0);
        return applyFacetsToBaseSchema(
            ownerLabel, targetNominalAlias, StvnVocabulary.TYPE_TUPLE,
            baseTup, null, AncestorFacetLedger.EMPTY, false, metaMap, schemaCtx, scope, effectiveErrBefore
        );
      }

      if (ctor.sumType() != null) {
        var sum = ctor.sumType();
        if (sum.KW_OPTION() != null) {
          LoweredSchemaBundle innerBundle = lowerSchemaNode(
              ownerLabel, Optional.empty(), sum.schemaType(0), scope, visitedTypeStack, true, false
          );
          StvnSchema inner = innerBundle.schema();
          boolean innerPoisoned = isRealPoisonedSchema(inner);
          StvnSchema baseOpt = new StvnSchema.OptionSchema(
              targetNominalAlias, inner,
              TraitProfile.ofDefault(
                  innerPoisoned || inner.traits().equatable(),
                  innerPoisoned || inner.traits().comparable()
              )
          );
          int effectiveErrBefore = diagnosticBag.errorCount() - (hasEmptyMetaBlockError ? 1 : 0);
          return applyFacetsToBaseSchema(
              ownerLabel, targetNominalAlias, StvnVocabulary.TYPE_OPTION,
              baseOpt, null, AncestorFacetLedger.EMPTY, false, metaMap, schemaCtx, scope, effectiveErrBefore
          );
        }
        if (sum.KW_EITHER() != null) {
          LoweredSchemaBundle leftB = lowerSchemaNode(
              ownerLabel, Optional.empty(), sum.schemaType(0), scope, visitedTypeStack, true, false
          );
          LoweredSchemaBundle rightB = lowerSchemaNode(
              ownerLabel, Optional.empty(), sum.schemaType(1), scope, visitedTypeStack, true, false
          );
          StvnSchema l = leftB.schema();
          StvnSchema r = rightB.schema();
          boolean lPoisoned = isRealPoisonedSchema(l);
          boolean rPoisoned = isRealPoisonedSchema(r);
          boolean eq = (lPoisoned || l.traits().equatable()) && (rPoisoned || r.traits().equatable());
          boolean comp = (lPoisoned || l.traits().comparable()) && (rPoisoned || r.traits().comparable());
          StvnSchema baseEither = new StvnSchema.EitherSchema(
              targetNominalAlias, l, r, TraitProfile.ofDefault(eq, comp)
          );
          int effectiveErrBefore = diagnosticBag.errorCount() - (hasEmptyMetaBlockError ? 1 : 0);
          return applyFacetsToBaseSchema(
              ownerLabel, targetNominalAlias, StvnVocabulary.TYPE_EITHER,
              baseEither, null, AncestorFacetLedger.EMPTY, false, metaMap, schemaCtx, scope, effectiveErrBefore
          );
        }
        if (sum.KW_UNION() != null) {
          List<StvnSchema> branches = new ArrayList<>();
          boolean arityInvalid = false;
          boolean allEq = true;
          boolean allComp = true;
          for (var bCtx : sum.schemaType()) {
            LoweredSchemaBundle bb = lowerSchemaNode(
                ownerLabel, Optional.empty(), bCtx, scope, visitedTypeStack, true, false
            );
            StvnSchema bs = bb.schema();
            branches.add(bs);
            if (!isRealPoisonedSchema(bs)) {
              allEq = allEq && bs.traits().equatable();
              allComp = allComp && bs.traits().comparable();
            }
          }
          if (branches.size() < 2) {
            Span sp = Span.ofContext(sum);
            diagnosticBag.addErrorDual(
                "Union schema requires at least 2 branch types, got " + branches.size(),
                sp.start(), sp.end(), sp.line(), sp.column(), null,
                "E010",
                DiagnosticBag.ERR_MALFORMED_SCHEMA
            );
            arityInvalid = true;
          }
          StvnSchema baseUnion = new StvnSchema.UnionSchema(
              targetNominalAlias,
              branches.size() >= 2
                  ? branches
                  : List.of(
                      new StvnSchema.BooleanSchema(Optional.empty(), TraitProfile.ofDefault(true, true)),
                      new StvnSchema.BooleanSchema(Optional.empty(), TraitProfile.ofDefault(true, true))
                  ),
              TraitProfile.ofDefault(allEq, allComp)
          );
          if (arityInvalid) {
            validateFacetsOnlyIfPresent(ownerLabel, StvnVocabulary.TYPE_UNION, baseUnion, null, AncestorFacetLedger.EMPTY, false, metaMap, schemaCtx, scope);
            return poisonedBundle(targetNominalAlias, DiagnosticBag.ERR_MALFORMED_SCHEMA);
          }
          int effectiveErrBefore = diagnosticBag.errorCount() - (hasEmptyMetaBlockError ? 1 : 0);
          return applyFacetsToBaseSchema(
              ownerLabel, targetNominalAlias, StvnVocabulary.TYPE_UNION,
              baseUnion, null, AncestorFacetLedger.EMPTY, false, metaMap, schemaCtx, scope, effectiveErrBefore
          );
        }
        if (sum.KW_ENUM() != null && sum.enumDef() != null) {
          Set<String> variants = new LinkedHashSet<>();
          for (var vk : sum.enumDef().valueKeyword()) {
            String vName = vk.getText();
            if (variants.contains(vName)) {
              Span sp = Span.ofContext(vk);
              diagnosticBag.addError(
                  "Duplicate enum variant '" + vName + "' in enum declaration",
                  sp.start(), sp.end(), sp.line(), sp.column(), null,
                  DiagnosticBag.ERR_DUPLICATE_DEF
              );
            } else {
              variants.add(vName);
            }
          }
          if (variants.isEmpty()) {
            Span sp = Span.ofContext(sum);
            diagnosticBag.addErrorDual(
                "Enum schema requires at least 1 variant, got 0",
                sp.start(), sp.end(), sp.line(), sp.column(), null,
                "E010",
                DiagnosticBag.ERR_MALFORMED_SCHEMA
            );
            validateFacetsOnlyIfPresent(ownerLabel, StvnVocabulary.TYPE_ENUM, new StvnSchema.BooleanSchema(Optional.empty(), TraitProfile.ofDefault(true, true)), null, AncestorFacetLedger.EMPTY, false, metaMap, schemaCtx, scope);
            return poisonedBundle(targetNominalAlias, DiagnosticBag.ERR_MALFORMED_SCHEMA);
          }
          StvnSchema baseEnum = new StvnSchema.EnumSchema(
              targetNominalAlias, List.copyOf(variants), TraitProfile.ofDefault(true, true)
          );
          int effectiveErrBefore = diagnosticBag.errorCount() - (hasEmptyMetaBlockError ? 1 : 0);
          return applyFacetsToBaseSchema(
              ownerLabel, targetNominalAlias, StvnVocabulary.TYPE_ENUM,
              baseEnum, null, AncestorFacetLedger.EMPTY, false, metaMap, schemaCtx, scope, effectiveErrBefore
          );
        }
      }

      return poisonedBundle(targetNominalAlias, DiagnosticBag.ERR_MALFORMED_SCHEMA);
    }

    private LoweredSchemaBundle poisonedBundle(Optional<String> alias, String symbolicCode) {
      return new LoweredSchemaBundle(
          new StvnSchema.PoisonedSchema(alias, symbolicCode, Optional.empty(), TraitProfile.ofDefault(true, true)),
          AncestorFacetLedger.EMPTY
      );
    }

    private static boolean hasLocalInvertibleFlag(StvnParser.@Nullable MetadataMapContext metaMap) {
      if (metaMap == null || metaMap.metadataEntry() == null) {
        return false;
      }
      for (var entry : metaMap.metadataEntry()) {
        if (entry.facetInvertible() != null && entry.facetInvertible().standaloneFacetIllegalArg() == null) {
          return true;
        }
      }
      return false;
    }

    private void validateFacetsOnlyIfPresent(
        String ownerLabel,
        String baseKw,
        StvnSchema dummyBase,
        @Nullable String parentName,
        AncestorFacetLedger parentLedger,
        boolean isNominalDerivation,
        StvnParser.@Nullable MetadataMapContext metaMap,
        ParserRuleContext schemaCtx,
        ScopeFrame scope
    ) {
      if (metaMap != null && metaMap.metadataEntry() != null && !metaMap.metadataEntry().isEmpty()) {
        applyFacetsToBaseSchema(
            ownerLabel, Optional.empty(), baseKw, dummyBase, parentName, parentLedger,
            isNominalDerivation, metaMap, schemaCtx, scope, diagnosticBag.errorCount()
        );
      }
    }

    private LoweredSchemaBundle applyFacetsToRootAtomic(
        String ownerLabel,
        Optional<String> targetNominalAlias,
        String baseKw,
        StvnParser.AtomicTypeContext atomCtx,
        StvnParser.@Nullable MetadataMapContext metaMap,
        ScopeFrame scope,
        int errBefore
    ) {
      if (baseKw.equals(StvnVocabulary.TYPE_BOOLEAN)) {
        StvnSchema base = new StvnSchema.BooleanSchema(targetNominalAlias, TraitProfile.ofDefault(true, true));
        return applyFacetsToBaseSchema(
            ownerLabel, targetNominalAlias, baseKw, base, null, AncestorFacetLedger.EMPTY,
            false, metaMap, atomCtx, scope, errBefore
        );
      }
      if (baseKw.equals(StvnVocabulary.TYPE_INT)) {
        StvnSchema base = new StvnSchema.IntSchema(
            targetNominalAlias, false, StvnVocabulary.DEFAULT_INT_BIT_WIDTH,
            TraitProfile.ofDefault(true, true), Optional.empty(), Optional.empty()
        );
        return applyFacetsToBaseSchema(
            ownerLabel, targetNominalAlias, baseKw, base, null, AncestorFacetLedger.EMPTY,
            false, metaMap, atomCtx, scope, errBefore
        );
      }
      if (baseKw.equals(StvnVocabulary.TYPE_FLOAT)) {
        StvnSchema base = new StvnSchema.FloatSchema(
            targetNominalAlias, FloatRepresentation.IEEE_64, StvnVocabulary.DEFAULT_MAX_SIZE,
            TraitProfile.ofDefault(false, true), Optional.empty(), Optional.empty()
        );
        return applyFacetsToBaseSchema(
            ownerLabel, targetNominalAlias, baseKw, base, null, AncestorFacetLedger.EMPTY,
            false, metaMap, atomCtx, scope, errBefore
        );
      }
      if (baseKw.equals(StvnVocabulary.TYPE_STRING)) {
        StvnSchema base = new StvnSchema.StringSchema(
            targetNominalAlias, BigInteger.ZERO, StvnVocabulary.DEFAULT_MAX_SIZE,
            TraitProfile.ofDefault(true, true), Optional.empty(), Optional.empty(),
            false, Optional.empty(), Optional.empty()
        );
        return applyFacetsToBaseSchema(
            ownerLabel, targetNominalAlias, baseKw, base, null, AncestorFacetLedger.EMPTY,
            false, metaMap, atomCtx, scope, errBefore
        );
      }
      if (baseKw.equals(StvnVocabulary.TYPE_TIME_EPOCH)) {
        StvnSchema base = new StvnSchema.TimeEpochSchema(
            targetNominalAlias, TemporalScale.S, TraitProfile.ofDefault(true, true),
            Optional.empty(), Optional.empty()
        );
        return applyFacetsToBaseSchema(
            ownerLabel, targetNominalAlias, baseKw, base, null, AncestorFacetLedger.EMPTY,
            false, metaMap, atomCtx, scope, errBefore
        );
      }
      if (baseKw.equals(StvnVocabulary.TYPE_DATE_TIME)) {
        StvnSchema base = new StvnSchema.DateTimeSchema(
            targetNominalAlias, DateTimeMode.OFFSET, TraitProfile.ofDefault(true, true),
            Optional.empty(), Optional.empty()
        );
        return applyFacetsToBaseSchema(
            ownerLabel, targetNominalAlias, baseKw, base, null, AncestorFacetLedger.EMPTY,
            false, metaMap, atomCtx, scope, errBefore
        );
      }
      return poisonedBundle(targetNominalAlias, DiagnosticBag.ERR_MALFORMED_SCHEMA);
    }

    private LoweredSchemaBundle applyFacetsToBaseSchema(
        String ownerLabel,
        Optional<String> effectiveBrand,
        String baseKw,
        StvnSchema baseSchema,
        @Nullable String parentName,
        AncestorFacetLedger parentLedger,
        boolean isNominalDerivation,
        StvnParser.@Nullable MetadataMapContext metaMap,
        ParserRuleContext atomicOrSchemaPinCtx,
        ScopeFrame scope,
        int errBefore
    ) {
      if (metaMap == null || metaMap.metadataEntry() == null || metaMap.metadataEntry().isEmpty()) {
        // Check mandatory temporal facet on root :TimeEpoch / :DateTime without metadata!
        if (!isNominalDerivation && baseKw.equals(StvnVocabulary.TYPE_TIME_EPOCH)) {
          Span sp = Span.ofContext(atomicOrSchemaPinCtx);
          diagnosticBag.addError(
              formatMissingTimeEpochScaleMessage(),
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_MISSING_TEMPORAL_FACET
          );
          return poisonedBundle(effectiveBrand, DiagnosticBag.ERR_MISSING_TEMPORAL_FACET);
        }
        if (!isNominalDerivation && baseKw.equals(StvnVocabulary.TYPE_DATE_TIME)) {
          Span sp = Span.ofContext(atomicOrSchemaPinCtx);
          diagnosticBag.addError(
              formatMissingDateTimeModeMessage(),
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_MISSING_TEMPORAL_FACET
          );
          return poisonedBundle(effectiveBrand, DiagnosticBag.ERR_MISSING_TEMPORAL_FACET);
        }
        AncestorFacetLedger lockedParentLedger = lockIntrinsicDefaults(baseSchema, parentLedger);
        if (diagnosticBag.errorCount() > errBefore) {
          Optional<StvnSchema> bestEffort = extractBestEffortFallback(baseSchema, effectiveBrand);
          return new LoweredSchemaBundle(
              new StvnSchema.PoisonedSchema(
                  effectiveBrand,
                  DiagnosticBag.ERR_EMPTY_METADATA_BLOCK,
                  bestEffort,
                  baseSchema.traits()
              ),
              lockedParentLedger
          );
        }
        return new LoweredSchemaBundle(rebrandSchema(baseSchema, effectiveBrand), lockedParentLedger);
      }

      if (isNominalDerivation) {
        parentLedger = lockIntrinsicDefaults(baseSchema, parentLedger);
      }

      Set<String> permitted = permittedFacetsFor(baseKw, isNominalDerivation);

      // Pre-scan local flags needed for cross-facet gating (#exact, #unsigned, #size)
      boolean localHasExact = false;
      boolean localHasUnsigned = false;
      for (var entry : metaMap.metadataEntry()) {
        if (entry.facetExact() != null && entry.facetExact().standaloneFacetIllegalArg() == null) {
          localHasExact = true;
        }
        if (entry.facetUnsigned() != null && entry.facetUnsigned().standaloneFacetIllegalArg() == null) {
          localHasUnsigned = true;
        }
      }
      boolean effectiveExactFloat = baseKw.equals(StvnVocabulary.TYPE_FLOAT)
          && (parentLedger.lockedExact().orElse(false) || localHasExact);

      int lastTier = -1;
      int lastSubTier = -1;
      String lastFacetName = null;
      int orderViolationCount = 0;
      String primaryFacetErrorCode = null;
      Set<String> seenFacetNames = new HashSet<>();
      boolean seenFloatSizeOrExact = false;
      boolean seenMinBound = false;
      boolean seenMaxBound = false;
      boolean seenEnumFilter = false;

      // Local parsed facet slots
      boolean childUnsigned = false;
      boolean childExact = false;
      boolean childInvertible = false;
      TemporalScale childScale = null;
      String firstScaleTag = null;
      DateTimeMode childMode = null;
      String firstModeTag = null;
      BigInteger childSize = null;
      Span childSizeSpan = null;
      BigInteger childMinSize = null;
      Span childMinSizeSpan = null;
      BigInteger childMaxSize = null;
      Span childMaxSizeSpan = null;

      Boolean childEquatable = null;
      Span childEquatableSpan = null;
      Boolean childComparable = null;
      Span childComparableSpan = null;
      Boolean childPreserveIndent = null;
      Span childPreserveIndentSpan = null;

      StvnParser.MetadataRangeContext minBoundCtx = null;
      String minBoundName = null;
      StvnParser.MetadataRangeContext maxBoundCtx = null;
      String maxBoundName = null;
      StvnParser.MetadataRangeContext prohibitedDiscreteMinCtx = null;
      StvnParser.MetadataRangeContext prohibitedDiscreteMaxCtx = null;

      StvnParser.MetadataStringContext regexCtx = null;
      StvnParser.MetadataFilterContext filterInclCtx = null;
      StvnParser.MetadataFilterContext filterExclCtx = null;

      for (var entry : metaMap.metadataEntry()) {
        if (entry.metadataUnknownEntry() != null) {
          var unk = entry.metadataUnknownEntry();
          var kwNode = unk.unknownFacetKeyword();
          Span sp = Span.ofContext(kwNode != null ? kwNode : unk);
          String facetTag = kwNode != null ? kwNode.getText() : unk.getText();
          diagnosticBag.addError(
              "Metadata violation (" + ownerLabel + "): Unrecognized constraint facet '" + facetTag + "'",
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_UNRECOGNIZED_METADATA_FACET
          );
          continue;
        }

        // Check illegal trailing argument on standalone flag facets first!
        StvnParser.StandaloneFacetIllegalArgContext illegalArg = extractStandaloneIllegalArg(entry);
        String facetName = extractFacetName(entry);
        if (facetName == null) {
          continue;
        }
        if (illegalArg != null) {
          reportStandaloneFacetIllegalArg(StvnVocabulary.SIGIL_VALUE + facetName, illegalArg);
          continue;
        }

        Span entrySpan = Span.ofContext(entry);

        // 1. Duplicate facet check (§ 5.1.4)
        if (!seenFacetNames.add(facetName)) {
          diagnosticBag.addError(
              "Metadata violation (" + ownerLabel + "): Duplicate facet '#" + facetName
                  + "' is prohibited; each facet may appear at most once per metadata block",
              entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
              DiagnosticBag.ERR_DUPLICATE_METADATA_FACET
          );
          continue;
        }

        // 1b. Incomplete boolean trait facet check (#preserveIndent, #equatable, #comparable)
        if ((entry.metadataBool() != null && entry.metadataBool().metadataValue() == null)
            || (entry.metadataTrait() != null && entry.metadataTrait().metadataValue() == null)) {
          if (!hasDiagnosticAtOffset(diagnosticBag, entrySpan.start())) {
            diagnosticBag.addError(
                "Metadata facet '" + StvnVocabulary.SIGIL_VALUE + facetName
                    + "' requires an explicit boolean value ("
                    + StvnVocabulary.VAL_TRUE + " or " + StvnVocabulary.VAL_FALSE + ")",
                entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
                DiagnosticBag.ERR_INCOMPLETE_TRAIT_FACET
            );
          }
          if (primaryFacetErrorCode == null) {
            primaryFacetErrorCode = DiagnosticBag.ERR_INCOMPLETE_TRAIT_FACET;
          }
          continue;
        }

        // 2. Directive #strip rejection on type declarations
        if ("strip".equals(facetName)) {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): directive facet '#strip' is not permitted on type declarations; directive facets are valid strictly in "
                  + StvnVocabulary.KEYWORD_USE + " and " + StvnVocabulary.KEYWORD_INCLUDE + " blocks",
              entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
              DiagnosticBag.ERR_INVALID_METADATA_FACET
          );
          continue;
        }

        // 3. Target base type compatibility check (§ 6.1 94-row matrix)
        if (baseKw.equals(StvnVocabulary.TYPE_STRING) && "size".equals(facetName)) {
          diagnosticBag.addError(
              "Facet '#size' is prohibited on " + StvnVocabulary.TYPE_STRING
                  + "; use '#minSize' and '#maxSize' (e.g. { #minSize N #maxSize N } " + StvnVocabulary.TYPE_STRING + ")",
              entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
              DiagnosticBag.ERR_STRING_CARDINALITY_PROHIBITED
          );
          continue;
        }
        if (baseKw.equals(StvnVocabulary.TYPE_FLOAT) && "maxSize".equals(facetName) && !effectiveExactFloat) {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): facet '#maxSize' is prohibited on continuous '"
                  + StvnVocabulary.TYPE_FLOAT + "'; '#maxSize' on float is permitted strictly with '#exact'",
              entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
              DiagnosticBag.ERR_INVALID_METADATA_FACET
          );
          continue;
        }
        boolean isDiscreteBoundCandidate = ("minExcl".equals(facetName) || "maxIncl".equals(facetName))
            && (baseKw.equals(StvnVocabulary.TYPE_INT)
                || baseKw.equals(StvnVocabulary.TYPE_TIME_EPOCH)
                || baseKw.equals(StvnVocabulary.TYPE_DATE_TIME)
                || (baseKw.equals(StvnVocabulary.TYPE_FLOAT) && effectiveExactFloat));
        if (isDiscreteBoundCandidate) {
          String illegalFacet = "maxIncl".equals(facetName)
              ? StvnVocabulary.FACET_KW_MAX_INCL
              : StvnVocabulary.FACET_KW_MIN_EXCL;
          String suggested = "maxIncl".equals(facetName)
              ? StvnVocabulary.FACET_KW_MAX_EXCL
              : StvnVocabulary.FACET_KW_MIN_INCL;
          String typeDesc = baseKw.equals(StvnVocabulary.TYPE_TIME_EPOCH)
              ? "Discrete temporal type '" + StvnVocabulary.TYPE_TIME_EPOCH + "'"
              : (baseKw.equals(StvnVocabulary.TYPE_DATE_TIME)
                  ? "Discrete temporal type '" + StvnVocabulary.TYPE_DATE_TIME + "'"
                  : (baseKw.equals(StvnVocabulary.TYPE_INT)
                      ? "Discrete type '" + StvnVocabulary.TYPE_INT + "'"
                      : "Discrete exact float '{ #exact } " + StvnVocabulary.TYPE_FLOAT + "'"));
          diagnosticBag.addError(
              typeDesc + " prohibits bound '" + illegalFacet + "'; use half-open bound '" + suggested + "'",
              entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
              DiagnosticBag.ERR_DISCRETE_BOUND_KIND_PROHIBITED
          );
          if ("minExcl".equals(facetName)) {
            prohibitedDiscreteMinCtx = entry.metadataRange();
          } else {
            prohibitedDiscreteMaxCtx = entry.metadataRange();
          }
          continue;
        }
        if (("filterIncl".equals(facetName) || "filterExcl".equals(facetName))
            && ownerLabel.startsWith(StvnVocabulary.SIGIL_VALUE)) {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): facet '#" + facetName
                  + "' is not allowed on constant definitions; filter facets are permitted strictly on nominal aliases of "
                  + StvnVocabulary.TYPE_ENUM,
              entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
              DiagnosticBag.ERR_INVALID_METADATA_FACET
          );
          continue;
        }
        if (!permitted.contains(facetName)) {
          String message;
          if ("filterIncl".equals(facetName) || "filterExcl".equals(facetName)) {
            if (baseKw.equals(StvnVocabulary.TYPE_ENUM)) {
              message = "Constraint violation (" + ownerLabel + "): facet '#" + facetName
                  + "' cannot be applied to inline enum constructors; filter facets are permitted strictly on nominal aliases of "
                  + StvnVocabulary.TYPE_ENUM;
            } else {
              message = "Constraint violation (" + ownerLabel + "): filter facets are not allowed on "
                  + baseKw + "; filter facets are permitted strictly on nominal aliases of " + StvnVocabulary.TYPE_ENUM;
            }
          } else {
            message = "Constraint violation (" + ownerLabel + "): facet '#" + facetName + "' is not permitted on "
                + baseKw + "; permitted facets: "
                + permitted.stream().sorted().map(f -> StvnVocabulary.SIGIL_VALUE + f).toList();
          }
          diagnosticBag.addError(
              message,
              entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
              DiagnosticBag.ERR_INVALID_METADATA_FACET
          );
          continue;
        }

        // 4. Same-block mutual exclusivity checks (pinned strictly to the second conflicting facet!)
        if (isTemporalScaleName(facetName)) {
          if (firstScaleTag != null) {
            diagnosticBag.addError(
                formatConflictingTemporalScalesMessage(),
                entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
                DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
            );
            if (primaryFacetErrorCode == null) {
              primaryFacetErrorCode = DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE;
            }
            continue;
          }
          firstScaleTag = facetName;
        }
        if (isTemporalModeName(facetName)) {
          if (firstModeTag != null) {
            diagnosticBag.addError(
                formatConflictingTemporalModesMessage(),
                entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
                DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
            );
            if (primaryFacetErrorCode == null) {
              primaryFacetErrorCode = DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE;
            }
            continue;
          }
          firstModeTag = facetName;
        }
        if (baseKw.equals(StvnVocabulary.TYPE_FLOAT) && ("size".equals(facetName) || "exact".equals(facetName))) {
          if (seenFloatSizeOrExact) {
            String peer = "size".equals(facetName) ? StvnVocabulary.FACET_KW_EXACT : StvnVocabulary.FACET_KW_SIZE;
            diagnosticBag.addError(
                "Constraint violation (" + ownerLabel + "): facet '"
                    + StvnVocabulary.SIGIL_VALUE + facetName + "' is mutually exclusive with '" + peer + "'",
                entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
                DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
            );
            if (primaryFacetErrorCode == null) {
              primaryFacetErrorCode = DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE;
            }
            continue;
          }
          seenFloatSizeOrExact = true;
        }
        if ("minIncl".equals(facetName) || "minExcl".equals(facetName)) {
          if (seenMinBound) {
            diagnosticBag.addError(
                "Constraint violation (" + ownerLabel + "): "
                    + StvnVocabulary.FACET_KW_MIN_INCL + " and " + StvnVocabulary.FACET_KW_MIN_EXCL
                    + " are mutually exclusive",
                entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
                DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
            );
            if (primaryFacetErrorCode == null) {
              primaryFacetErrorCode = DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE;
            }
            continue;
          }
          seenMinBound = true;
        }
        if ("maxIncl".equals(facetName) || "maxExcl".equals(facetName)) {
          if (seenMaxBound) {
            diagnosticBag.addError(
                "Constraint violation (" + ownerLabel + "): "
                    + StvnVocabulary.FACET_KW_MAX_INCL + " and " + StvnVocabulary.FACET_KW_MAX_EXCL
                    + " are mutually exclusive",
                entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
                DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
            );
            if (primaryFacetErrorCode == null) {
              primaryFacetErrorCode = DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE;
            }
            continue;
          }
          seenMaxBound = true;
        }
        if ("filterIncl".equals(facetName) || "filterExcl".equals(facetName)) {
          if (seenEnumFilter) {
            filterInclCtx = null;
            filterExclCtx = null;
            diagnosticBag.addError(
                "Constraint violation (" + ownerLabel + "): "
                    + StvnVocabulary.FACET_KW_FILTER_INCL + " and " + StvnVocabulary.FACET_KW_FILTER_EXCL
                    + " are mutually exclusive",
                entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
                DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
            );
            if (primaryFacetErrorCode == null) {
              primaryFacetErrorCode = DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE;
            }
            continue;
          }
          seenEnumFilter = true;
        }

        // 5. Canonical 5-Tier Monotonic Ordering check
        int tier = getFacetTier(facetName);
        int subTier = getFacetSubTier(facetName);
        if (tier < lastTier || (tier == lastTier && subTier < lastSubTier)) {
          diagnosticBag.addError(
              "Facet order violation (" + ownerLabel + "): facet '#" + facetName + "' (Tier " + tier
                  + ") violates canonical 5-tier order after '#" + lastFacetName + "'",
              entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
              DiagnosticBag.ERR_FACET_ORDER_VIOLATION
          );
          orderViolationCount++;
        }
        lastTier = tier;
        lastSubTier = subTier;
        lastFacetName = facetName;

        // 6. Cross-hop Mutual Exclusivity & Tier-1 Non-Overridability (E040) against AncestorFacetLedger
        if (isNominalDerivation) {
          String ancName = parentName != null ? parentName : "ancestor";
          if ("exact".equals(facetName) && parentLedger.lockedSize().isPresent()) {
            diagnosticBag.addError(
                "Constraint violation (" + ownerLabel + "): facet '#exact' is mutually exclusive with ancestor storage size '#size "
                    + parentLedger.lockedSize().get() + "' from " + ancName,
                entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
                DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
            );
            continue;
          }
          if ("size".equals(facetName) && parentLedger.lockedExact().orElse(false)) {
            diagnosticBag.addError(
                "Constraint violation (" + ownerLabel + "): facet '#size' is mutually exclusive with ancestor facet '#exact' from "
                    + ancName,
                entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
                DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
            );
            continue;
          }
          if (isTemporalScaleName(facetName) && parentLedger.explicitScale().isPresent()) {
            TemporalScale pScale = parentLedger.explicitScale().get();
            TemporalScale curScale = parseTemporalScale(facetName);
            if (curScale != pScale) {
              diagnosticBag.addError(
                  "Constraint violation (" + ownerLabel + "): facet '#" + facetName
                      + "' is a non-overridable definition facet (temporal scale is already locked as '"
                      + temporalScaleKeyword(pScale) + "' by ancestor " + ancName + " and cannot be overridden)",
                  entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
                  DiagnosticBag.ERR_NON_OVERRIDABLE_FACET
              );
            } else {
              diagnosticBag.addError(
                  "Constraint violation (" + ownerLabel + "): non-overridable definition facet '#" + facetName
                      + "' is already established by ancestor " + ancName + " and cannot be re-specified",
                  entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
                  DiagnosticBag.ERR_NON_OVERRIDABLE_FACET
              );
            }
            continue;
          }
          if (isTemporalModeName(facetName) && parentLedger.explicitMode().isPresent()) {
            DateTimeMode pMode = parentLedger.explicitMode().get();
            DateTimeMode curMode = parseDateTimeMode(facetName);
            if (curMode != pMode) {
              diagnosticBag.addError(
                  "Constraint violation (" + ownerLabel + "): facet '#" + facetName
                      + "' is a non-overridable definition facet (temporal mode is already locked as '"
                      + dateTimeModeKeyword(pMode) + "' by ancestor " + ancName + " and cannot be overridden)",
                  entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
                  DiagnosticBag.ERR_NON_OVERRIDABLE_FACET
              );
            } else {
              diagnosticBag.addError(
                  "Constraint violation (" + ownerLabel + "): non-overridable definition facet '#" + facetName
                      + "' is already established by ancestor " + ancName + " and cannot be re-specified",
                  entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
                  DiagnosticBag.ERR_NON_OVERRIDABLE_FACET
              );
            }
            continue;
          }
          if (("unsigned".equals(facetName) && parentLedger.lockedUnsigned().isPresent())
              || ("exact".equals(facetName) && parentLedger.lockedExact().orElse(false))
              || ("invertible".equals(facetName) && parentLedger.lockedInvertible().isPresent())
              || ("minSize".equals(facetName) && parentLedger.explicitMinSize().isPresent())
              || ("maxSize".equals(facetName) && parentLedger.explicitMaxSize().isPresent())) {
            diagnosticBag.addError(
                "Constraint violation (" + ownerLabel + "): non-overridable definition facet '#" + facetName
                    + "' is already established by ancestor " + ancName + " and cannot be re-specified",
                entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
                DiagnosticBag.ERR_NON_OVERRIDABLE_FACET
            );
            continue;
          }
        }

        // 7. Parse and validate individual facet argument values
        switch (facetName) {
          case "unsigned" -> childUnsigned = true;
          case "exact" -> childExact = true;
          case "invertible" -> childInvertible = true;
          case "ns", "us", "ms", "s" -> childScale = parseTemporalScale(facetName);
          case "offset", "zoned", "audited" -> childMode = parseDateTimeMode(facetName);
          case "size" -> {
            BigInteger sz = resolveIntegerMetadataArg(ownerLabel, StvnVocabulary.FACET_KW_SIZE, entry.metadataSize().metadataValue(), scope);
            if (sz != null) {
              if (isNominalDerivation && parentLedger.lockedSize().isPresent()) {
                String ancName = parentName != null ? parentName : "ancestor";
                diagnosticBag.addError(
                    "Constraint violation (" + ownerLabel + "): non-overridable definition facet '#size' is already established by ancestor "
                        + ancName + " and cannot be re-specified",
                    entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
                    DiagnosticBag.ERR_NON_OVERRIDABLE_FACET
                );
              } else {
                childSize = sz;
                childSizeSpan = entrySpan;
              }
            }
          }
          case "minSize" -> {
            BigInteger ms = resolveIntegerMetadataArg(ownerLabel, StvnVocabulary.FACET_KW_MIN_SIZE, entry.metadataSize().metadataValue(), scope);
            if (ms != null) {
              childMinSize = ms;
              childMinSizeSpan = entrySpan;
            }
          }
          case "maxSize" -> {
            BigInteger mx = resolveIntegerMetadataArg(ownerLabel, StvnVocabulary.FACET_KW_MAX_SIZE, entry.metadataSize().metadataValue(), scope);
            if (mx != null) {
              childMaxSize = mx;
              childMaxSizeSpan = entrySpan;
            }
          }
          case "equatable" -> {
            Boolean b = resolveBooleanMetadataArg(ownerLabel, StvnVocabulary.FACET_KW_EQUATABLE, entry.metadataBool().metadataValue(), scope);
            if (b != null) {
              childEquatable = b;
              childEquatableSpan = entrySpan;
            }
          }
          case "comparable" -> {
            Boolean b = resolveBooleanMetadataArg(ownerLabel, StvnVocabulary.FACET_KW_COMPARABLE, entry.metadataBool().metadataValue(), scope);
            if (b != null) {
              childComparable = b;
              childComparableSpan = entrySpan;
            }
          }
          case "preserveIndent" -> {
            Boolean b = resolvePreserveIndentArg(ownerLabel, entry.metadataTrait(), scope);
            if (b != null) {
              childPreserveIndent = b;
              childPreserveIndentSpan = entrySpan;
            }
          }
          case "minIncl", "minExcl" -> {
            minBoundCtx = entry.metadataRange();
            minBoundName = facetName;
          }
          case "maxIncl", "maxExcl" -> {
            maxBoundCtx = entry.metadataRange();
            maxBoundName = facetName;
          }
          case "regex" -> regexCtx = entry.metadataString();
          case "filterIncl" -> filterInclCtx = entry.metadataFilter();
          case "filterExcl" -> filterExclCtx = entry.metadataFilter();
          default -> {}
        }
      }

      // Build merged AncestorFacetLedger BEFORE W003 tautology stripping!
      AncestorFacetLedger childExplicitLedger = new AncestorFacetLedger(
          childUnsigned ? Optional.of(true) : Optional.empty(),
          childExact ? Optional.of(true) : Optional.empty(),
          childInvertible ? Optional.of(true) : Optional.empty(),
          Optional.ofNullable(childSize),
          Optional.ofNullable(childMinSize),
          Optional.ofNullable(childMaxSize),
          Optional.ofNullable(childScale),
          Optional.ofNullable(childMode)
      );
      AncestorFacetLedger mergedLedger = parentLedger.mergeChild(childExplicitLedger);

      // Validate mandatory temporal scale/mode on root :TimeEpoch and :DateTime
      if (!isNominalDerivation && baseKw.equals(StvnVocabulary.TYPE_TIME_EPOCH) && childScale == null) {
        if (firstScaleTag == null) {
          Span sp = Span.ofContext(atomicOrSchemaPinCtx);
          diagnosticBag.addError(
              formatMissingTimeEpochScaleMessage(),
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_MISSING_TEMPORAL_FACET
          );
        }
        return poisonedBundle(effectiveBrand, DiagnosticBag.ERR_MISSING_TEMPORAL_FACET);
      }
      if (!isNominalDerivation && baseKw.equals(StvnVocabulary.TYPE_DATE_TIME) && childMode == null) {
        if (firstModeTag == null) {
          Span sp = Span.ofContext(atomicOrSchemaPinCtx);
          diagnosticBag.addError(
              formatMissingDateTimeModeMessage(),
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_MISSING_TEMPORAL_FACET
          );
        }
        return poisonedBundle(effectiveBrand, DiagnosticBag.ERR_MISSING_TEMPORAL_FACET);
      }

      // Compute default / inherited traits and check W003 on Tier-2 trait facets (#equatable / #comparable)
      boolean defaultEq = baseSchema.traits().equatable();
      boolean defaultComp = baseSchema.traits().comparable();
      if (!isNominalDerivation && baseKw.equals(StvnVocabulary.TYPE_FLOAT)) {
        defaultEq = childExact;
        defaultComp = true;
      }
      if (!isNominalDerivation && baseKw.equals(StvnVocabulary.TYPE_MAP) && baseSchema instanceof StvnSchema.MapSchema ms) {
        defaultEq = childInvertible
            ? (ms.keySchema().traits().equatable() && ms.valueSchema().traits().equatable())
            : ms.keySchema().traits().equatable();
        defaultComp = false;
      }

      if (childEquatable != null && childEquatableSpan != null && childEquatable == defaultEq) {
        emitRedundantTraitWarning(
            ownerLabel, "equatable", childEquatable, defaultEq, baseKw,
            isNominalDerivation ? parentName : null,
            effectiveExactFloat, childEquatableSpan
        );
      }
      if (childComparable != null && childComparableSpan != null && childComparable == defaultComp) {
        emitRedundantTraitWarning(
            ownerLabel, "comparable", childComparable, defaultComp, baseKw,
            isNominalDerivation ? parentName : null,
            effectiveExactFloat, childComparableSpan
        );
      }

      boolean finalEq = childEquatable != null ? childEquatable : defaultEq;
      boolean finalComp = childComparable != null ? childComparable : defaultComp;

      // If #equatable is explicitly disabled to #FALSE on an equatable type while #comparable is #TRUE (unless continuous float)
      if (!finalEq && finalComp && !baseKw.equals(StvnVocabulary.TYPE_FLOAT)) {
        if (childComparable != null && childComparable) {
          Span sp = childComparableSpan != null ? childComparableSpan : Span.ofContext(metaMap);
          diagnosticBag.addError(
              "Trait violation (" + ownerLabel + "): '#comparable #TRUE' requires '#equatable #TRUE'",
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_TRAIT_VIOLATION
          );
        } else {
          finalComp = false;
        }
      }

      boolean hasEnumFilter = filterInclCtx != null || filterExclCtx != null;
      boolean intrinsicRootEq = switch (baseSchema) {
        case StvnSchema.BooleanSchema ignored -> true;
        case StvnSchema.IntSchema ignored -> true;
        case StvnSchema.FloatSchema ignored -> effectiveExactFloat;
        case StvnSchema.StringSchema ignored -> true;
        case StvnSchema.TimeEpochSchema ignored -> true;
        case StvnSchema.DateTimeSchema ignored -> true;
        case StvnSchema.EnumSchema en -> hasEnumFilter ? en.traits().equatable() : true;
        case StvnSchema.EnumSubsetSchema sub -> hasEnumFilter
            ? sub.traits().equatable()
            : sub.parentEnum().traits().equatable();
        case StvnSchema.TupleSchema tup -> tup.fieldSchemas().stream().allMatch(f -> f.traits().equatable());
        case StvnSchema.OptionSchema opt -> opt.valueSchema().traits().equatable();
        case StvnSchema.EitherSchema eith ->
            eith.leftSchema().traits().equatable() && eith.rightSchema().traits().equatable();
        case StvnSchema.UnionSchema un -> un.branchSchemas().stream().allMatch(b -> b.traits().equatable());
        case StvnSchema.SeqSchema seq -> seq.elementSchema().traits().equatable();
        case StvnSchema.SetSchema set -> set.elementSchema().traits().equatable();
        case StvnSchema.MapSchema map -> (map.invertible() || childInvertible)
            ? (map.keySchema().traits().equatable() && map.valueSchema().traits().equatable())
            : map.keySchema().traits().equatable();
        case StvnSchema.PoisonedSchema p -> p.traits().equatable();
      };
      boolean intrinsicRootComp = switch (baseSchema) {
        case StvnSchema.BooleanSchema ignored -> true;
        case StvnSchema.IntSchema ignored -> true;
        case StvnSchema.FloatSchema ignored -> true;
        case StvnSchema.StringSchema ignored -> true;
        case StvnSchema.TimeEpochSchema ignored -> true;
        case StvnSchema.DateTimeSchema ignored -> true;
        case StvnSchema.EnumSchema en -> hasEnumFilter ? en.traits().comparable() : true;
        case StvnSchema.EnumSubsetSchema sub -> hasEnumFilter
            ? sub.traits().comparable()
            : sub.parentEnum().traits().comparable();
        case StvnSchema.TupleSchema tup -> tup.fieldSchemas().stream().allMatch(f -> f.traits().comparable());
        case StvnSchema.OptionSchema opt -> opt.valueSchema().traits().comparable();
        case StvnSchema.EitherSchema eith ->
            eith.leftSchema().traits().comparable() && eith.rightSchema().traits().comparable();
        case StvnSchema.UnionSchema un -> un.branchSchemas().stream().allMatch(b -> b.traits().comparable());
        case StvnSchema.SeqSchema seq -> seq.elementSchema().traits().comparable();
        case StvnSchema.SetSchema ignored -> false;
        case StvnSchema.MapSchema ignored -> false;
        case StvnSchema.PoisonedSchema p -> p.traits().comparable();
      };
      Optional<Boolean> normExplicitEq = (finalEq != intrinsicRootEq)
          ? Optional.of(finalEq)
          : Optional.empty();
      Optional<Boolean> normExplicitComp;
      if (finalComp == intrinsicRootComp) {
        normExplicitComp = Optional.empty();
      } else if (childComparable != null) {
        normExplicitComp = Optional.of(finalComp);
      } else {
        boolean resolvedComp = finalComp;
        normExplicitComp = baseSchema.traits().explicitComparableOverride()
            .filter(inherited -> inherited == resolvedComp);
      }
      TraitProfile finalTraits = new TraitProfile(
          finalEq,
          finalComp,
          normExplicitEq,
          normExplicitComp
      );

      // Compute unfiltered enum trait profile (hasEnumFilter == false) for rebrandEnumParent when
      // an identity #filterIncl is purged under W003 or when an invalid enum filter falls back.
      boolean unfilteredEnumIntrinsicEq = switch (baseSchema) {
        case StvnSchema.EnumSchema ignored -> true;
        case StvnSchema.EnumSubsetSchema sub -> sub.parentEnum().traits().equatable();
        default -> intrinsicRootEq;
      };
      boolean unfilteredEnumIntrinsicComp = switch (baseSchema) {
        case StvnSchema.EnumSchema ignored -> true;
        case StvnSchema.EnumSubsetSchema sub -> sub.parentEnum().traits().comparable();
        default -> intrinsicRootComp;
      };
      Optional<Boolean> unfilteredNormEq = (finalEq != unfilteredEnumIntrinsicEq)
          ? Optional.of(finalEq)
          : Optional.empty();
      Optional<Boolean> unfilteredNormComp;
      if (finalComp == unfilteredEnumIntrinsicComp) {
        unfilteredNormComp = Optional.empty();
      } else if (childComparable != null) {
        unfilteredNormComp = Optional.of(finalComp);
      } else {
        boolean resolvedComp = finalComp;
        unfilteredNormComp = baseSchema.traits().explicitComparableOverride()
            .filter(inherited -> inherited == resolvedComp);
      }
      TraitProfile unfilteredEnumTraits = new TraitProfile(
          finalEq,
          finalComp,
          unfilteredNormEq,
          unfilteredNormComp
      );

      // Now specialize & validate domain constraints per concrete schema family + W003 tautology purging!
      StvnSchema loweredSchema = switch (baseSchema) {
        case StvnSchema.BooleanSchema ignored -> new StvnSchema.BooleanSchema(effectiveBrand, finalTraits);

        case StvnSchema.IntSchema intBase -> {
          StvnSchema res = lowerIntFacets(
              ownerLabel, effectiveBrand, intBase, parentName, parentLedger, isNominalDerivation,
              childUnsigned, childSize, childSizeSpan, minBoundCtx, maxBoundCtx, finalTraits, scope
          );
          if (prohibitedDiscreteMinCtx != null || prohibitedDiscreteMaxCtx != null) {
            BigInteger candMin = minBoundCtx != null
                ? resolveIntegerMetadataArgQuiet(minBoundCtx.metadataValue(), scope)
                : (prohibitedDiscreteMinCtx != null
                    ? resolveIntegerMetadataArgQuiet(prohibitedDiscreteMinCtx.metadataValue(), scope)
                    : intBase.explicitMinIncl().orElse(null));
            BigInteger candMax = maxBoundCtx != null
                ? resolveIntegerMetadataArgQuiet(maxBoundCtx.metadataValue(), scope)
                : (prohibitedDiscreteMaxCtx != null
                    ? resolveIntegerMetadataArgQuiet(prohibitedDiscreteMaxCtx.metadataValue(), scope)
                    : intBase.explicitMaxExcl().orElse(null));
            if (candMin != null && candMax != null && candMin.compareTo(candMax) > 0) {
              Span pin = Span.ofContext(
                  maxBoundCtx != null ? maxBoundCtx
                      : (prohibitedDiscreteMaxCtx != null ? prohibitedDiscreteMaxCtx
                          : (minBoundCtx != null ? minBoundCtx : prohibitedDiscreteMinCtx))
              );
              diagnosticBag.addError(
                  "Constraint violation (" + ownerLabel + "): effective range is invalid (minimum "
                      + candMin + " is greater than maximum " + candMax + ")",
                  pin.start(), pin.end(), pin.line(), pin.column(), null,
                  DiagnosticBag.ERR_INVERTED_RANGE
              );
            }
          }
          yield res;
        }

        case StvnSchema.FloatSchema floatBase -> lowerFloatFacets(
            ownerLabel, effectiveBrand, floatBase, parentName, isNominalDerivation,
            childExact, childSize, childSizeSpan, childMaxSize, childMaxSizeSpan,
            minBoundCtx, minBoundName, maxBoundCtx, maxBoundName, finalTraits, scope
        );

        case StvnSchema.StringSchema strBase -> lowerStringFacets(
            ownerLabel, effectiveBrand, strBase, parentName, isNominalDerivation,
            childMinSize, childMinSizeSpan, childMaxSize, childMaxSizeSpan,
            minBoundCtx, minBoundName, maxBoundCtx, maxBoundName, regexCtx,
            childPreserveIndent, childPreserveIndentSpan, finalTraits, scope
        );

        case StvnSchema.TimeEpochSchema teBase -> lowerTimeEpochFacets(
            ownerLabel, effectiveBrand, teBase, parentName, isNominalDerivation,
            childScale, minBoundCtx, maxBoundCtx, finalTraits, scope
        );

        case StvnSchema.DateTimeSchema dtBase -> lowerDateTimeFacets(
            ownerLabel, effectiveBrand, dtBase, parentName, isNominalDerivation,
            childMode, minBoundCtx, maxBoundCtx, finalTraits, scope
        );

        case StvnSchema.EnumSchema enumBase -> lowerEnumFacets(
            ownerLabel, effectiveBrand, enumBase, enumBase, parentName, isNominalDerivation,
            enumBase.rootVariants(), filterInclCtx, filterExclCtx, finalTraits, unfilteredEnumTraits
        );

        case StvnSchema.EnumSubsetSchema subsetBase -> lowerEnumFacets(
            ownerLabel, effectiveBrand, subsetBase, subsetBase.rootEnum(), parentName, isNominalDerivation,
            subsetBase.activeVariants(), filterInclCtx, filterExclCtx, finalTraits, unfilteredEnumTraits
        );

        case StvnSchema.TupleSchema tupBase -> new StvnSchema.TupleSchema(effectiveBrand, tupBase.fieldSchemas(), finalTraits);

        case StvnSchema.OptionSchema optBase -> new StvnSchema.OptionSchema(effectiveBrand, optBase.valueSchema(), finalTraits);

        case StvnSchema.EitherSchema eithBase -> new StvnSchema.EitherSchema(
            effectiveBrand, eithBase.leftSchema(), eithBase.rightSchema(), finalTraits
        );

        case StvnSchema.UnionSchema unBase -> new StvnSchema.UnionSchema(effectiveBrand, unBase.branchSchemas(), finalTraits);

        case StvnSchema.SeqSchema seqBase -> {
          CardinalityPair cp = resolveCardinalityPair(
              ownerLabel, StvnVocabulary.TYPE_SEQ, seqBase.minSize(), seqBase.maxSize(),
              childMinSize, childMinSizeSpan, childMaxSize, childMaxSizeSpan
          );
          yield new StvnSchema.SeqSchema(effectiveBrand, cp.min(), cp.max(), seqBase.elementSchema(), finalTraits);
        }

        case StvnSchema.SetSchema setBase -> {
          CardinalityPair cp = resolveCardinalityPair(
              ownerLabel, StvnVocabulary.TYPE_SET, setBase.minSize(), setBase.maxSize(),
              childMinSize, childMinSizeSpan, childMaxSize, childMaxSizeSpan
          );
          yield new StvnSchema.SetSchema(effectiveBrand, cp.min(), cp.max(), setBase.elementSchema(), finalTraits);
        }

        case StvnSchema.MapSchema mapBase -> {
          boolean effectiveInv = mapBase.invertible() || childInvertible;
          if (isNominalDerivation && childInvertible && !mapBase.invertible()
              && !isRealPoisonedSchema(mapBase.valueSchema()) && !mapBase.valueSchema().traits().equatable()) {
            Span sp = Span.ofContext(metaMap);
            diagnosticBag.addError(
                "Inverted map values require types to be #equatable #TRUE",
                sp.start(), sp.end(), sp.line(), sp.column(), null,
                DiagnosticBag.ERR_TRAIT_VIOLATION
            );
          }
          CardinalityPair cp = resolveCardinalityPair(
              ownerLabel, StvnVocabulary.TYPE_MAP, mapBase.minSize(), mapBase.maxSize(),
              childMinSize, childMinSizeSpan, childMaxSize, childMaxSizeSpan
          );
          yield new StvnSchema.MapSchema(
              effectiveBrand, effectiveInv, cp.min(), cp.max(), mapBase.keySchema(), mapBase.valueSchema(), finalTraits
          );
        }

        case StvnSchema.PoisonedSchema p -> rebrandSchema(p, effectiveBrand);
      };

      AncestorFacetLedger finalLedger = lockIntrinsicDefaults(loweredSchema, mergedLedger);
      if (diagnosticBag.errorCount() - orderViolationCount > errBefore) {
        Optional<StvnSchema> bestEffort = extractBestEffortFallback(loweredSchema, effectiveBrand);
        StvnSchema.PoisonedSchema poisoned = new StvnSchema.PoisonedSchema(
            effectiveBrand,
            primaryFacetErrorCode != null ? primaryFacetErrorCode : DiagnosticBag.ERR_MALFORMED_SCHEMA,
            bestEffort,
            loweredSchema.traits()
        );
        return new LoweredSchemaBundle(poisoned, finalLedger);
      }
      return new LoweredSchemaBundle(loweredSchema, finalLedger);
    }

    private static Optional<StvnSchema> extractBestEffortFallback(
        StvnSchema schema,
        Optional<String> effectiveBrand
    ) {
      if (schema instanceof StvnSchema.PoisonedSchema ps) {
        return ps.bestEffortFallback().map(fb -> effectiveBrand.isPresent() ? rebrandSchema(fb, effectiveBrand) : fb);
      }
      return Optional.of(effectiveBrand.isPresent() ? rebrandSchema(schema, effectiveBrand) : schema);
    }

    private record CardinalityPair(BigInteger min, BigInteger max) {}

    private CardinalityPair resolveCardinalityPair(
        String ownerLabel,
        String targetBaseType,
        BigInteger inheritedMin,
        BigInteger inheritedMax,
        @Nullable BigInteger childMinSize,
        @Nullable Span childMinSizeSpan,
        @Nullable BigInteger childMaxSize,
        @Nullable Span childMaxSizeSpan
    ) {
      BigInteger effectiveMin = inheritedMin;
      BigInteger effectiveMax = inheritedMax;

      if (childMinSize != null && childMinSizeSpan != null) {
        if (childMinSize.signum() < 0) {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): #minSize must be >= 0, got " + childMinSize,
              childMinSizeSpan.start(), childMinSizeSpan.end(), childMinSizeSpan.line(), childMinSizeSpan.column(),
              null, DiagnosticBag.ERR_INVALID_METADATA_FACET
          );
        } else if (childMinSize.signum() == 0) {
          boolean isStr = targetBaseType.equals(StvnVocabulary.TYPE_STRING);
          diagnosticBag.addWarning(
              "Constraint violation (" + ownerLabel + "): facet '#minSize 0' is redundant; "
                  + (isStr ? "string cardinality is inherently non-negative" : "container cardinality is inherently non-negative"),
              childMinSizeSpan.start(), childMinSizeSpan.end(), childMinSizeSpan.line(), childMinSizeSpan.column(),
              DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
          );
        } else {
          effectiveMin = childMinSize;
        }
      }

      if (childMaxSize != null && childMaxSizeSpan != null) {
        if (childMaxSize.compareTo(BigInteger.ONE) < 0) {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): #maxSize must be >= 1, got " + childMaxSize,
              childMaxSizeSpan.start(), childMaxSizeSpan.end(), childMaxSizeSpan.line(), childMaxSizeSpan.column(),
              null, DiagnosticBag.ERR_CAPACITY_OVERFLOW
          );
        } else if (childMaxSize.equals(StvnVocabulary.DEFAULT_MAX_SIZE)) {
          diagnosticBag.addWarning(
              "Constraint violation (" + ownerLabel + "): facet '#maxSize 16777216' is redundant; '"
                  + targetBaseType + "' intrinsically defaults to a maximum cardinality of 16,777,216",
              childMaxSizeSpan.start(), childMaxSizeSpan.end(), childMaxSizeSpan.line(), childMaxSizeSpan.column(),
              DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
          );
        } else {
          effectiveMax = childMaxSize;
        }
      }

      if (effectiveMin.compareTo(effectiveMax) > 0) {
        Span pin = childMaxSizeSpan != null ? childMaxSizeSpan : (childMinSizeSpan != null ? childMinSizeSpan : new Span(0, 1, 1, 0));
        diagnosticBag.addError(
            "Constraint violation (" + ownerLabel + "): #minSize (" + effectiveMin
                + ") cannot exceed #maxSize (" + effectiveMax + ")",
            pin.start(), pin.end(), pin.line(), pin.column(),
            null, DiagnosticBag.ERR_INVERTED_RANGE
        );
        return new CardinalityPair(BigInteger.ZERO, StvnVocabulary.DEFAULT_MAX_SIZE);
      }

      return new CardinalityPair(effectiveMin, effectiveMax);
    }

    private StvnSchema lowerIntFacets(
        String ownerLabel,
        Optional<String> effectiveBrand,
        StvnSchema.IntSchema intBase,
        @Nullable String parentName,
        AncestorFacetLedger parentLedger,
        boolean isNominalDerivation,
        boolean childUnsigned,
        @Nullable BigInteger childSize,
        @Nullable Span childSizeSpan,
        StvnParser.@Nullable MetadataRangeContext minBoundCtx,
        StvnParser.@Nullable MetadataRangeContext maxBoundCtx,
        TraitProfile finalTraits,
        ScopeFrame scope
    ) {
      boolean effectiveUnsigned = intBase.unsigned() || childUnsigned;
      BigInteger effectiveBitWidth = intBase.bitWidth();

      if (childSize != null && childSizeSpan != null) {
        if (childSize.compareTo(BigInteger.ONE) < 0) {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): integer #size must be >= 1, got " + childSize,
              childSizeSpan.start(), childSizeSpan.end(), childSizeSpan.line(), childSizeSpan.column(),
              null, DiagnosticBag.ERR_INVERTED_RANGE
          );
        } else if (childSize.equals(StvnVocabulary.DEFAULT_INT_BIT_WIDTH)) {
          String desc = effectiveUnsigned
              ? "unsigned integer '{ #unsigned } " + StvnVocabulary.TYPE_INT + "'"
              : "signed integer '" + StvnVocabulary.TYPE_INT + "'";
          diagnosticBag.addWarning(
              "Constraint violation (" + ownerLabel + "): facet '#size 32' is redundant; "
                  + desc + " intrinsically defaults to 32-bit storage",
              childSizeSpan.start(), childSizeSpan.end(), childSizeSpan.line(), childSizeSpan.column(),
              DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
          );
          effectiveBitWidth = StvnVocabulary.DEFAULT_INT_BIT_WIDTH;
        } else {
          effectiveBitWidth = childSize;
        }
      }

      // If child newly added #unsigned onto a signed parent that already had negative bounds, verify compatibility
      StvnSchema.IntSchema widthCarrier = new StvnSchema.IntSchema(
          effectiveBrand, effectiveUnsigned, effectiveBitWidth,
          finalTraits, Optional.empty(), Optional.empty()
      );

      Optional<BigInteger> effectiveMin = intBase.explicitMinIncl();
      Optional<BigInteger> effectiveMax = intBase.explicitMaxExcl();
      String deferredMinW003 = null;
      Span deferredMinSpan = null;
      String deferredMaxW003 = null;
      Span deferredMaxSpan = null;

      if (childUnsigned && !intBase.unsigned()) {
        if (effectiveMin.isPresent() && effectiveMin.get().signum() < 0) {
          Span sp = Span.ofContext(minBoundCtx != null ? minBoundCtx : (ParserRuleContext) null);
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): ancestor negative #minIncl " + effectiveMin.get()
                  + " is incompatible with '#unsigned'",
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_INTEGER_OVERFLOW
          );
          effectiveMin = Optional.empty();
        }
      }

      if (minBoundCtx != null) {
        Span bSpan = Span.ofContext(minBoundCtx);
        BigInteger minVal = resolveIntegerMetadataArg(ownerLabel, StvnVocabulary.FACET_KW_MIN_INCL, minBoundCtx.metadataValue(), scope);
        if (minVal != null) {
          if (!widthCarrier.acceptsWidth(minVal)) {
            diagnosticBag.addError(
                "Constraint violation (" + ownerLabel + "): #minIncl value " + minVal
                    + " is out of bounds for physical capacity of " + StvnVocabulary.TYPE_INT
                    + " (" + (effectiveUnsigned ? "unsigned " : "signed ") + effectiveBitWidth + "-bit)"
                    + "; valid #minIncl range is " + formatPhysicalIntRange(effectiveBitWidth, effectiveUnsigned, false),
                bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(), null,
                DiagnosticBag.ERR_INTEGER_OVERFLOW
            );
          } else if (effectiveUnsigned && minVal.signum() == 0) {
            boolean ancestorEstablishesUnsigned = isNominalDerivation && parentLedger.lockedUnsigned().orElse(false) && !childUnsigned;
            String ancName = parentName != null ? parentName : "ancestor";
            String msg = ancestorEstablishesUnsigned
                ? "Constraint violation (" + ownerLabel + "): facet '#minIncl 0' is redundant; ancestor '"
                    + ancName + "' already establishes '#unsigned'"
                : "Constraint violation (" + ownerLabel + "): facet '#minIncl 0' is redundant; '#unsigned' already bounds the domain to [0, \\infty)";
            diagnosticBag.addWarning(
                msg, bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(),
                DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
            );
            // Strip redundant #minIncl 0 from IR node!
          } else if (!effectiveUnsigned && isExactSignedMinBound(minVal, effectiveBitWidth)) {
            diagnosticBag.addWarning(
                "Constraint violation (" + ownerLabel + "): facet '#minIncl " + minVal
                    + "' is redundant; '#size " + effectiveBitWidth + "' already bounds the minimum value to " + minVal,
                bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(),
                DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
            );
          } else {
            if (effectiveMin.isPresent() && minVal.equals(effectiveMin.get())) {
              String ancName = parentName != null ? parentName : "ancestor";
              String minFacet = StvnVocabulary.FACET_KW_MIN_INCL + " " + minVal;
              deferredMinW003 = "Constraint violation (" + ownerLabel + "): facet '" + minFacet
                  + "' is redundant; ancestor '" + ancName + "' already establishes '" + minFacet + "'";
              deferredMinSpan = bSpan;
            }
            effectiveMin = Optional.of(minVal);
          }
        }
      }

      if (maxBoundCtx != null) {
        Span bSpan = Span.ofContext(maxBoundCtx);
        BigInteger maxVal = resolveIntegerMetadataArg(ownerLabel, StvnVocabulary.FACET_KW_MAX_EXCL, maxBoundCtx.metadataValue(), scope);
        if (maxVal != null) {
          // Note: For half-open [min, maxExcl), maxExcl == 2^s (unsigned) or 2^(s-1) (signed) is the exact architectural ceiling!
          boolean isExactUnsignedCeiling = effectiveUnsigned && isExactPowerOfTwo(maxVal, effectiveBitWidth);
          boolean isExactSignedCeiling = !effectiveUnsigned
              && effectiveBitWidth.compareTo(BigInteger.ONE) >= 0
              && isExactPowerOfTwo(maxVal, effectiveBitWidth.subtract(BigInteger.ONE));
          boolean withinExclusiveCapacity = isExactUnsignedCeiling
              || isExactSignedCeiling
              || (effectiveUnsigned
                  ? (maxVal.signum() >= 0 && widthCarrier.acceptsWidth(maxVal))
                  : (widthCarrier.acceptsWidth(maxVal) || widthCarrier.acceptsWidth(maxVal.subtract(BigInteger.ONE))));
          if (effectiveUnsigned && maxVal.signum() < 0) {
            withinExclusiveCapacity = false;
          }
          // Strictly verify maxVal - 1 <= maxInclusive of bitWidth, and maxVal >= minArchitecturalBound
          if (!isExactUnsignedCeiling && !isExactSignedCeiling) {
            // Check upper overflow: maxVal > architectural ceiling => maxVal - 1 does not fit!
            BigInteger highestMember = maxVal.subtract(BigInteger.ONE);
            if (maxVal.signum() > 0 && !widthCarrier.acceptsWidth(highestMember)) {
              withinExclusiveCapacity = false;
            }
            // Check lower overflow: maxVal < architectural minimum
            if (!effectiveUnsigned && maxVal.signum() < 0 && !widthCarrier.acceptsWidth(maxVal)) {
              withinExclusiveCapacity = false;
            }
          }

          if (!withinExclusiveCapacity) {
            diagnosticBag.addError(
                "Constraint violation (" + ownerLabel + "): #maxExcl value " + maxVal
                    + " is out of bounds for physical capacity of " + StvnVocabulary.TYPE_INT
                    + " (" + (effectiveUnsigned ? "unsigned " : "signed ") + effectiveBitWidth + "-bit)"
                    + "; valid #maxExcl range is " + formatPhysicalIntRange(effectiveBitWidth, effectiveUnsigned, true),
                bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(), null,
                DiagnosticBag.ERR_INTEGER_OVERFLOW
            );
          } else if (isExactUnsignedCeiling) {
            diagnosticBag.addWarning(
                "Constraint violation (" + ownerLabel + "): facet '#maxExcl " + maxVal
                    + "' is redundant; '#unsigned #size " + effectiveBitWidth
                    + "' already bounds the domain to [0, " + maxVal + ")",
                bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(),
                DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
            );
            // Strip redundant architectural ceiling from IR node!
          } else if (isExactSignedCeiling) {
            diagnosticBag.addWarning(
                "Constraint violation (" + ownerLabel + "): facet '#maxExcl " + maxVal
                    + "' is redundant; '#size " + effectiveBitWidth
                    + "' already bounds the maximum exclusive bound to " + maxVal,
                bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(),
                DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
            );
          } else if (effectiveUnsigned && maxVal.signum() == 0) {
            diagnosticBag.addError(
                "Constraint violation (" + ownerLabel + "): discrete integer interval [0, 0) defines an empty domain",
                bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(), null,
                DiagnosticBag.ERR_EMPTY_INTERVAL_DOMAIN
            );
          } else {
            if (effectiveMax.isPresent() && maxVal.equals(effectiveMax.get())) {
              String ancName = parentName != null ? parentName : "ancestor";
              String maxFacet = StvnVocabulary.FACET_KW_MAX_EXCL + " " + maxVal;
              deferredMaxW003 = "Constraint violation (" + ownerLabel + "): facet '" + maxFacet
                  + "' is redundant; ancestor '" + ancName + "' already establishes '" + maxFacet + "'";
              deferredMaxSpan = bSpan;
            }
            effectiveMax = Optional.of(maxVal);
          }
        }
      }

      boolean pinnedIntervalErrorOnMin = false;
      boolean pinnedIntervalErrorOnMax = false;
      if (effectiveMin.isPresent() && effectiveMax.isPresent()) {
        int cmp = effectiveMin.get().compareTo(effectiveMax.get());
        Span pin = Span.ofContext(maxBoundCtx != null ? maxBoundCtx : minBoundCtx);
        if (cmp == 0) {
          pinnedIntervalErrorOnMax = maxBoundCtx != null;
          pinnedIntervalErrorOnMin = maxBoundCtx == null && minBoundCtx != null;
          if (!pinnedIntervalErrorOnMin && deferredMinW003 != null && deferredMinSpan != null) {
            diagnosticBag.addWarning(
                deferredMinW003, deferredMinSpan.start(), deferredMinSpan.end(),
                deferredMinSpan.line(), deferredMinSpan.column(), DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
            );
          }
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): discrete integer interval ["
                  + effectiveMin.get() + ", " + effectiveMax.get() + ") defines an empty domain",
              pin.start(), pin.end(), pin.line(), pin.column(), null,
              DiagnosticBag.ERR_EMPTY_INTERVAL_DOMAIN
          );
          return new StvnSchema.IntSchema(
              effectiveBrand, effectiveUnsigned, effectiveBitWidth, finalTraits, Optional.empty(), Optional.empty()
          );
        } else if (cmp > 0) {
          pinnedIntervalErrorOnMax = maxBoundCtx != null;
          pinnedIntervalErrorOnMin = maxBoundCtx == null && minBoundCtx != null;
          if (!pinnedIntervalErrorOnMin && deferredMinW003 != null && deferredMinSpan != null) {
            diagnosticBag.addWarning(
                deferredMinW003, deferredMinSpan.start(), deferredMinSpan.end(),
                deferredMinSpan.line(), deferredMinSpan.column(), DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
            );
          }
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): effective range is invalid (#minIncl "
                  + effectiveMin.get() + " exceeds #maxExcl " + effectiveMax.get() + ")",
              pin.start(), pin.end(), pin.line(), pin.column(), null,
              DiagnosticBag.ERR_INVERTED_RANGE
          );
          return new StvnSchema.IntSchema(
              effectiveBrand, effectiveUnsigned, effectiveBitWidth, finalTraits, Optional.empty(), Optional.empty()
          );
        }
      }

      if (!pinnedIntervalErrorOnMin && deferredMinW003 != null && deferredMinSpan != null) {
        diagnosticBag.addWarning(
            deferredMinW003, deferredMinSpan.start(), deferredMinSpan.end(),
            deferredMinSpan.line(), deferredMinSpan.column(), DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
        );
      }
      if (!pinnedIntervalErrorOnMax && deferredMaxW003 != null && deferredMaxSpan != null) {
        diagnosticBag.addWarning(
            deferredMaxW003, deferredMaxSpan.start(), deferredMaxSpan.end(),
            deferredMaxSpan.line(), deferredMaxSpan.column(), DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
        );
      }

      return new StvnSchema.IntSchema(
          effectiveBrand, effectiveUnsigned, effectiveBitWidth, finalTraits, effectiveMin, effectiveMax
      );
    }

    /**
     * Symbolic {@code O(1)} bit check verifying {@code val == -2^(bitWidth - 1)} without materializing {@code 1 << bitWidth}.
     */
    private static boolean isExactSignedMinBound(BigInteger val, BigInteger bitWidth) {
      if (val.signum() >= 0 || bitWidth.compareTo(BigInteger.ONE) < 0) {
        return false;
      }
      BigInteger mag = val.negate();
      return mag.bitCount() == 1
          && BigInteger.valueOf(mag.getLowestSetBit()).equals(bitWidth.subtract(BigInteger.ONE));
    }

    /**
     * Symbolic {@code O(1)} bit check verifying {@code val == 2^exponent} without materializing {@code 1 << exponent}.
     */
    private static boolean isExactPowerOfTwo(BigInteger val, BigInteger exponent) {
      if (val.signum() <= 0 || exponent.signum() < 0) {
        return false;
      }
      return val.bitCount() == 1 && BigInteger.valueOf(val.getLowestSetBit()).equals(exponent);
    }

    private StvnSchema lowerFloatFacets(
        String ownerLabel,
        Optional<String> effectiveBrand,
        StvnSchema.FloatSchema floatBase,
        @Nullable String parentName,
        boolean isNominalDerivation,
        boolean childExact,
        @Nullable BigInteger childSize,
        @Nullable Span childSizeSpan,
        @Nullable BigInteger childMaxSize,
        @Nullable Span childMaxSizeSpan,
        StvnParser.@Nullable MetadataRangeContext minBoundCtx,
        @Nullable String minBoundName,
        StvnParser.@Nullable MetadataRangeContext maxBoundCtx,
        @Nullable String maxBoundName,
        TraitProfile finalTraits,
        ScopeFrame scope
    ) {
      FloatRepresentation rep = floatBase.representation();
      if (childExact) {
        rep = FloatRepresentation.EXACT_DECIMAL;
      } else if (childSize != null && childSizeSpan != null) {
        if (childSize.equals(new BigInteger("32"))) {
          rep = FloatRepresentation.IEEE_32;
        } else if (childSize.equals(StvnVocabulary.DEFAULT_FLOAT_BIT_WIDTH)) {
          diagnosticBag.addWarning(
              "Constraint violation (" + ownerLabel + "): facet '#size 64' is redundant; continuous '"
                  + StvnVocabulary.TYPE_FLOAT + "' intrinsically defaults to 64-bit IEEE-754 double precision",
              childSizeSpan.start(), childSizeSpan.end(), childSizeSpan.line(), childSizeSpan.column(),
              DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
          );
          rep = FloatRepresentation.IEEE_64;
        } else {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): continuous " + StvnVocabulary.TYPE_FLOAT
                  + " #size must be 32 or 64, got " + childSize,
              childSizeSpan.start(), childSizeSpan.end(), childSizeSpan.line(), childSizeSpan.column(),
              null, DiagnosticBag.ERR_FLOAT_INVALID_SIZE
          );
        }
      }

      BigInteger exactMaxDigits = floatBase.exactMaxDigits();
      if (rep == FloatRepresentation.EXACT_DECIMAL && childMaxSize != null && childMaxSizeSpan != null) {
        if (childMaxSize.compareTo(BigInteger.ONE) < 0) {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): exact float #maxSize must be >= 1, got " + childMaxSize,
              childMaxSizeSpan.start(), childMaxSizeSpan.end(), childMaxSizeSpan.line(), childMaxSizeSpan.column(),
              null, DiagnosticBag.ERR_CAPACITY_OVERFLOW
          );
        } else if (childMaxSize.equals(StvnVocabulary.DEFAULT_MAX_SIZE)) {
          diagnosticBag.addWarning(
              "Constraint violation (" + ownerLabel + "): facet '#maxSize 16777216' is redundant; exact decimal float '{ #exact } "
                  + StvnVocabulary.TYPE_FLOAT + "' intrinsically defaults to a ceiling of 16,777,216 significant decimal digits",
              childMaxSizeSpan.start(), childMaxSizeSpan.end(), childMaxSizeSpan.line(), childMaxSizeSpan.column(),
              DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
          );
        } else {
          exactMaxDigits = childMaxSize;
        }
      }

      Optional<ContinuousBound<BigDecimal>> lower = floatBase.lowerBound();
      Optional<ContinuousBound<BigDecimal>> upper = floatBase.upperBound();
      String deferredMinW003 = null;
      Span deferredMinSpan = null;
      String deferredMaxW003 = null;
      Span deferredMaxSpan = null;
      String ancName = parentName != null ? parentName : "ancestor";

      if (minBoundCtx != null && minBoundName != null) {
        String minKw = StvnVocabulary.SIGIL_VALUE + minBoundName;
        BigDecimal bd = resolveDecimalMetadataArg(ownerLabel, minKw, minBoundCtx.metadataValue(), rep, exactMaxDigits, scope);
        if (bd != null) {
          BoundKind kind = "minIncl".equals(minBoundName) ? BoundKind.INCLUSIVE : BoundKind.EXCLUSIVE;
          if (isNominalDerivation && lower.isPresent()
              && lower.get().kind() == kind && bd.compareTo(lower.get().value()) == 0) {
            deferredMinW003 = "Constraint violation (" + ownerLabel + "): facet '"
                + minKw + " " + formatCanonicalFloatBound(bd, rep) + "' is redundant; ancestor '"
                + ancName + "' already establishes '"
                + minKw + " " + formatCanonicalFloatBound(lower.get().value(), rep) + "'";
            deferredMinSpan = Span.ofContext(minBoundCtx);
            // Retain floatBase.lowerBound() so BigDecimal scale (e.g., 0.0 vs 0.00) preserves CAS bijectivity!
          } else {
            lower = Optional.of(new ContinuousBound<>(bd, kind));
          }
        }
      }

      if (maxBoundCtx != null && maxBoundName != null) {
        String maxKw = StvnVocabulary.SIGIL_VALUE + maxBoundName;
        BigDecimal bd = resolveDecimalMetadataArg(ownerLabel, maxKw, maxBoundCtx.metadataValue(), rep, exactMaxDigits, scope);
        if (bd != null) {
          BoundKind kind = "maxIncl".equals(maxBoundName) ? BoundKind.INCLUSIVE : BoundKind.EXCLUSIVE;
          if (isNominalDerivation && upper.isPresent()
              && upper.get().kind() == kind && bd.compareTo(upper.get().value()) == 0) {
            deferredMaxW003 = "Constraint violation (" + ownerLabel + "): facet '"
                + maxKw + " " + formatCanonicalFloatBound(bd, rep) + "' is redundant; ancestor '"
                + ancName + "' already establishes '"
                + maxKw + " " + formatCanonicalFloatBound(upper.get().value(), rep) + "'";
            deferredMaxSpan = Span.ofContext(maxBoundCtx);
            // Retain floatBase.upperBound() so BigDecimal scale preserves CAS bijectivity!
          } else {
            upper = Optional.of(new ContinuousBound<>(bd, kind));
          }
        }
      }

      if (lower.isPresent() && upper.isPresent()) {
        int cmp = lower.get().value().compareTo(upper.get().value());
        Span pin = Span.ofContext(maxBoundCtx != null ? maxBoundCtx : minBoundCtx);
        if (cmp > 0) {
          if (maxBoundCtx != null && deferredMinW003 != null && deferredMinSpan != null) {
            diagnosticBag.addWarning(
                deferredMinW003, deferredMinSpan.start(), deferredMinSpan.end(),
                deferredMinSpan.line(), deferredMinSpan.column(), DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
            );
          }
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): effective range is invalid (lower bound "
                  + lower.get().value() + " exceeds upper bound " + upper.get().value() + ")",
              pin.start(), pin.end(), pin.line(), pin.column(), null,
              DiagnosticBag.ERR_INVERTED_RANGE
          );
          return new StvnSchema.FloatSchema(
              effectiveBrand, rep, exactMaxDigits, finalTraits, Optional.empty(), Optional.empty()
          );
        } else if (cmp == 0 && (lower.get().kind() == BoundKind.EXCLUSIVE || upper.get().kind() == BoundKind.EXCLUSIVE)) {
          if (maxBoundCtx != null && deferredMinW003 != null && deferredMinSpan != null) {
            diagnosticBag.addWarning(
                deferredMinW003, deferredMinSpan.start(), deferredMinSpan.end(),
                deferredMinSpan.line(), deferredMinSpan.column(), DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
            );
          }
          String errCode = rep == FloatRepresentation.EXACT_DECIMAL
              ? DiagnosticBag.ERR_EMPTY_INTERVAL_DOMAIN
              : DiagnosticBag.ERR_INVERTED_RANGE;
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): effective range is invalid; interval defines an empty domain ("
                  + lower.get().value() + ")",
              pin.start(), pin.end(), pin.line(), pin.column(), null,
              errCode
          );
          return new StvnSchema.FloatSchema(
              effectiveBrand, rep, exactMaxDigits, finalTraits, Optional.empty(), Optional.empty()
          );
        }
      }

      if (deferredMinW003 != null && deferredMinSpan != null) {
        diagnosticBag.addWarning(
            deferredMinW003, deferredMinSpan.start(), deferredMinSpan.end(),
            deferredMinSpan.line(), deferredMinSpan.column(), DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
        );
      }
      if (deferredMaxW003 != null && deferredMaxSpan != null) {
        diagnosticBag.addWarning(
            deferredMaxW003, deferredMaxSpan.start(), deferredMaxSpan.end(),
            deferredMaxSpan.line(), deferredMaxSpan.column(), DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
        );
      }

      return new StvnSchema.FloatSchema(effectiveBrand, rep, exactMaxDigits, finalTraits, lower, upper);
    }

    private StvnSchema lowerStringFacets(
        String ownerLabel,
        Optional<String> effectiveBrand,
        StvnSchema.StringSchema strBase,
        @Nullable String parentName,
        boolean isNominalDerivation,
        @Nullable BigInteger childMinSize,
        @Nullable Span childMinSizeSpan,
        @Nullable BigInteger childMaxSize,
        @Nullable Span childMaxSizeSpan,
        StvnParser.@Nullable MetadataRangeContext minBoundCtx,
        @Nullable String minBoundName,
        StvnParser.@Nullable MetadataRangeContext maxBoundCtx,
        @Nullable String maxBoundName,
        StvnParser.@Nullable MetadataStringContext regexCtx,
        @Nullable Boolean childPreserveIndent,
        @Nullable Span childPreserveIndentSpan,
        TraitProfile finalTraits,
        ScopeFrame scope
    ) {
      CardinalityPair cp = resolveCardinalityPair(
          ownerLabel, StvnVocabulary.TYPE_STRING, strBase.minSize(), strBase.maxSize(),
          childMinSize, childMinSizeSpan, childMaxSize, childMaxSizeSpan
      );

      Optional<ContinuousBound<String>> lowerBound = strBase.lowerBound();
      Optional<ContinuousBound<String>> upperBound = strBase.upperBound();
      String deferredMinW003 = null;
      Span deferredMinSpan = null;
      String deferredMaxW003 = null;
      Span deferredMaxSpan = null;
      String ancName = parentName != null ? parentName : "ancestor";

      if (minBoundCtx != null && minBoundName != null) {
        String minKw = StvnVocabulary.SIGIL_VALUE + minBoundName;
        String sVal = resolveStringMetadataArg(ownerLabel, minKw, minBoundCtx.metadataValue(), scope);
        if (sVal != null) {
          BoundKind kind = "minIncl".equals(minBoundName) ? BoundKind.INCLUSIVE : BoundKind.EXCLUSIVE;
          if (isNominalDerivation && lowerBound.isPresent()
              && lowerBound.get().kind() == kind
              && compareUtf8Lexicographical(sVal, lowerBound.get().value()) == 0) {
            String quoted = formatQuotedStvnString(sVal);
            deferredMinW003 = "Constraint violation (" + ownerLabel + "): facet '" + minKw + " " + quoted
                + "' is redundant; ancestor '" + ancName + "' already establishes '" + minKw + " " + quoted + "'";
            deferredMinSpan = Span.ofContext(minBoundCtx);
          } else {
            lowerBound = Optional.of(new ContinuousBound<>(sVal, kind));
          }
        }
      }

      if (maxBoundCtx != null && maxBoundName != null) {
        String maxKw = StvnVocabulary.SIGIL_VALUE + maxBoundName;
        String sVal = resolveStringMetadataArg(ownerLabel, maxKw, maxBoundCtx.metadataValue(), scope);
        if (sVal != null) {
          BoundKind kind = "maxIncl".equals(maxBoundName) ? BoundKind.INCLUSIVE : BoundKind.EXCLUSIVE;
          if (isNominalDerivation && upperBound.isPresent()
              && upperBound.get().kind() == kind
              && compareUtf8Lexicographical(sVal, upperBound.get().value()) == 0) {
            String quoted = formatQuotedStvnString(sVal);
            deferredMaxW003 = "Constraint violation (" + ownerLabel + "): facet '" + maxKw + " " + quoted
                + "' is redundant; ancestor '" + ancName + "' already establishes '" + maxKw + " " + quoted + "'";
            deferredMaxSpan = Span.ofContext(maxBoundCtx);
          } else {
            upperBound = Optional.of(new ContinuousBound<>(sVal, kind));
          }
        }
      }

      boolean pinnedErrorOnMin = false;
      boolean pinnedErrorOnMax = false;
      if (lowerBound.isPresent() && upperBound.isPresent()) {
        int cmp = compareUtf8Lexicographical(lowerBound.get().value(), upperBound.get().value());
        Span pin = Span.ofContext(maxBoundCtx != null ? maxBoundCtx : minBoundCtx);
        if (cmp > 0) {
          pinnedErrorOnMax = maxBoundCtx != null;
          pinnedErrorOnMin = maxBoundCtx == null && minBoundCtx != null;
          if (!pinnedErrorOnMin && deferredMinW003 != null && deferredMinSpan != null) {
            diagnosticBag.addWarning(
                deferredMinW003, deferredMinSpan.start(), deferredMinSpan.end(),
                deferredMinSpan.line(), deferredMinSpan.column(), DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
            );
            deferredMinW003 = null;
          }
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): string lower bound ('" + lowerBound.get().value()
                  + "') exceeds upper bound ('" + upperBound.get().value() + "')",
              pin.start(), pin.end(), pin.line(), pin.column(), null,
              DiagnosticBag.ERR_INVERTED_RANGE
          );
          lowerBound = Optional.empty();
          upperBound = Optional.empty();
        } else if (cmp == 0
            && (lowerBound.get().kind() == BoundKind.EXCLUSIVE || upperBound.get().kind() == BoundKind.EXCLUSIVE)) {
          pinnedErrorOnMax = maxBoundCtx != null;
          pinnedErrorOnMin = maxBoundCtx == null && minBoundCtx != null;
          if (!pinnedErrorOnMin && deferredMinW003 != null && deferredMinSpan != null) {
            diagnosticBag.addWarning(
                deferredMinW003, deferredMinSpan.start(), deferredMinSpan.end(),
                deferredMinSpan.line(), deferredMinSpan.column(), DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
            );
            deferredMinW003 = null;
          }
          String openBracket = lowerBound.get().kind() == BoundKind.INCLUSIVE ? "[" : "(";
          String closeBracket = upperBound.get().kind() == BoundKind.INCLUSIVE ? "]" : ")";
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): string interval " + openBracket + "'"
                  + lowerBound.get().value() + "', '" + upperBound.get().value() + "'" + closeBracket
                  + " defines an empty domain",
              pin.start(), pin.end(), pin.line(), pin.column(), null,
              DiagnosticBag.ERR_EMPTY_INTERVAL_DOMAIN
          );
          lowerBound = Optional.empty();
          upperBound = Optional.empty();
        }
      }

      if (!pinnedErrorOnMin && deferredMinW003 != null && deferredMinSpan != null) {
        diagnosticBag.addWarning(
            deferredMinW003, deferredMinSpan.start(), deferredMinSpan.end(),
            deferredMinSpan.line(), deferredMinSpan.column(), DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
        );
      }
      if (!pinnedErrorOnMax && deferredMaxW003 != null && deferredMaxSpan != null) {
        diagnosticBag.addWarning(
            deferredMaxW003, deferredMaxSpan.start(), deferredMaxSpan.end(),
            deferredMaxSpan.line(), deferredMaxSpan.column(), DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
        );
      }

      // Evaluate Tier 4.3 #preserveIndent before Tier 4.4 #regex so diagnostics follow 5-tier source order
      boolean effectivePreserveIndent = strBase.preserveIndent();
      if (childPreserveIndent != null && childPreserveIndentSpan != null) {
        String boolTok = childPreserveIndent ? StvnVocabulary.VAL_TRUE : StvnVocabulary.VAL_FALSE;
        String preserveFacet = StvnVocabulary.FACET_KW_PRESERVE_INDENT + " " + boolTok;
        if (isNominalDerivation && childPreserveIndent == strBase.preserveIndent()) {
          diagnosticBag.addWarning(
              "Constraint violation (" + ownerLabel + "): facet '" + preserveFacet
                  + "' is redundant; ancestor '" + ancName + "' already establishes '" + preserveFacet + "'",
              childPreserveIndentSpan.start(), childPreserveIndentSpan.end(),
              childPreserveIndentSpan.line(), childPreserveIndentSpan.column(),
              DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
          );
        } else if (!isNominalDerivation && !childPreserveIndent) {
          diagnosticBag.addWarning(
              "Constraint violation (" + ownerLabel + "): facet '" + preserveFacet
                  + "' is redundant; fenced strings trim common leading indentation by default",
              childPreserveIndentSpan.start(), childPreserveIndentSpan.end(),
              childPreserveIndentSpan.line(), childPreserveIndentSpan.column(),
              DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
          );
        }
        effectivePreserveIndent = childPreserveIndent;
      }

      Optional<String> regexPattern = strBase.rawRegexPattern();
      Optional<Pattern> compiledRegex = strBase.compiledRegex();
      if (regexCtx != null) {
        Span rSpan = Span.ofContext(regexCtx);
        String patStr = resolveStringMetadataArg(ownerLabel, StvnVocabulary.FACET_KW_REGEX, regexCtx.metadataValue(), scope);
        if (patStr != null) {
          try {
            Pattern compiled = Pattern.compile(patStr);
            if (isNominalDerivation && strBase.rawRegexPattern().isPresent()
                && patStr.equals(strBase.rawRegexPattern().get())) {
              String regexFacet = StvnVocabulary.FACET_KW_REGEX + " " + formatQuotedStvnString(patStr);
              diagnosticBag.addWarning(
                  "Constraint violation (" + ownerLabel + "): facet '" + regexFacet
                      + "' is redundant; ancestor '" + ancName + "' already establishes '" + regexFacet + "'",
                  rSpan.start(), rSpan.end(), rSpan.line(), rSpan.column(),
                  DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
              );
            } else {
              regexPattern = Optional.of(patStr);
              compiledRegex = Optional.of(compiled);
            }
          } catch (PatternSyntaxException pse) {
            diagnosticBag.addError(
                "Constraint violation (" + ownerLabel + "): Invalid regex pattern: " + patStr,
                rSpan.start(), rSpan.end(), rSpan.line(), rSpan.column(), pse,
                DiagnosticBag.ERR_INVALID_REGEX
            );
          }
        }
      }

      return new StvnSchema.StringSchema(
          effectiveBrand, cp.min(), cp.max(), finalTraits, lowerBound, upperBound,
          effectivePreserveIndent, compiledRegex, regexPattern
      );
    }

    private static int compareUtf8Lexicographical(String a, String b) {
      byte[] ba = a.getBytes(StandardCharsets.UTF_8);
      byte[] bb = b.getBytes(StandardCharsets.UTF_8);
      return Arrays.compareUnsigned(ba, bb);
    }

    private StvnSchema lowerTimeEpochFacets(
        String ownerLabel,
        Optional<String> effectiveBrand,
        StvnSchema.TimeEpochSchema teBase,
        @Nullable String parentName,
        boolean isNominalDerivation,
        @Nullable TemporalScale childScale,
        StvnParser.@Nullable MetadataRangeContext minBoundCtx,
        StvnParser.@Nullable MetadataRangeContext maxBoundCtx,
        TraitProfile finalTraits,
        ScopeFrame scope
    ) {
      TemporalScale scale = childScale != null ? childScale : teBase.scale();
      BigInteger bitWidth = scale.bitWidth();
      StvnSchema.IntSchema widthCarrier = new StvnSchema.IntSchema(
          Optional.empty(), false, bitWidth, finalTraits, Optional.empty(), Optional.empty()
      );
      Optional<BigInteger> minIncl = teBase.explicitMinIncl();
      Optional<BigInteger> maxExcl = teBase.explicitMaxExcl();
      String deferredMinW003 = null;
      Span deferredMinSpan = null;
      String deferredMaxW003 = null;
      Span deferredMaxSpan = null;
      String ancName = parentName != null ? parentName : "ancestor";

      if (minBoundCtx != null) {
        Span bSpan = Span.ofContext(minBoundCtx);
        BigInteger v = resolveIntegerMetadataArg(ownerLabel, StvnVocabulary.FACET_KW_MIN_INCL, minBoundCtx.metadataValue(), scope);
        if (v != null) {
          if (!widthCarrier.acceptsWidth(v)) {
            diagnosticBag.addError(
                "Constraint violation (" + ownerLabel + "): #minIncl value " + v
                    + " is out of bounds for physical capacity of " + StvnVocabulary.TYPE_TIME_EPOCH
                    + " (signed " + bitWidth + "-bit)"
                    + "; valid #minIncl range is " + formatPhysicalIntRange(bitWidth, false, false),
                bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(), null,
                DiagnosticBag.ERR_INTEGER_OVERFLOW
            );
          } else {
            if (isNominalDerivation && minIncl.isPresent() && v.equals(minIncl.get())) {
              String minFacet = StvnVocabulary.FACET_KW_MIN_INCL + " " + v;
              deferredMinW003 = "Constraint violation (" + ownerLabel + "): facet '" + minFacet
                  + "' is redundant; ancestor '" + ancName + "' already establishes '" + minFacet + "'";
              deferredMinSpan = bSpan;
            } else {
              minIncl = Optional.of(v);
            }
          }
        }
      }
      if (maxBoundCtx != null) {
        Span bSpan = Span.ofContext(maxBoundCtx);
        BigInteger v = resolveIntegerMetadataArg(ownerLabel, StvnVocabulary.FACET_KW_MAX_EXCL, maxBoundCtx.metadataValue(), scope);
        if (v != null) {
          if (!widthCarrier.acceptsWidth(v)) {
            diagnosticBag.addError(
                "Constraint violation (" + ownerLabel + "): #maxExcl value " + v
                    + " is out of bounds for physical capacity of " + StvnVocabulary.TYPE_TIME_EPOCH
                    + " (signed " + bitWidth + "-bit)"
                    + "; valid #maxExcl range is " + formatPhysicalIntRange(bitWidth, false, false),
                bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(), null,
                DiagnosticBag.ERR_INTEGER_OVERFLOW
            );
          } else {
            if (isNominalDerivation && maxExcl.isPresent() && v.equals(maxExcl.get())) {
              String maxFacet = StvnVocabulary.FACET_KW_MAX_EXCL + " " + v;
              deferredMaxW003 = "Constraint violation (" + ownerLabel + "): facet '" + maxFacet
                  + "' is redundant; ancestor '" + ancName + "' already establishes '" + maxFacet + "'";
              deferredMaxSpan = bSpan;
            } else {
              maxExcl = Optional.of(v);
            }
          }
        }
      }

      boolean pinnedErrorOnMin = false;
      boolean pinnedErrorOnMax = false;
      if (minIncl.isPresent() && maxExcl.isPresent()) {
        int cmp = minIncl.get().compareTo(maxExcl.get());
        Span pin = Span.ofContext(maxBoundCtx != null ? maxBoundCtx : minBoundCtx);
        if (cmp == 0) {
          pinnedErrorOnMax = maxBoundCtx != null;
          pinnedErrorOnMin = maxBoundCtx == null && minBoundCtx != null;
          if (!pinnedErrorOnMin && deferredMinW003 != null && deferredMinSpan != null) {
            diagnosticBag.addWarning(
                deferredMinW003, deferredMinSpan.start(), deferredMinSpan.end(),
                deferredMinSpan.line(), deferredMinSpan.column(), DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
            );
            deferredMinW003 = null;
          }
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): discrete half-open interval ["
                  + minIncl.get() + ", " + maxExcl.get() + ") defines an empty domain",
              pin.start(), pin.end(), pin.line(), pin.column(), null,
              DiagnosticBag.ERR_EMPTY_INTERVAL_DOMAIN
          );
          minIncl = Optional.empty();
          maxExcl = Optional.empty();
        } else if (cmp > 0) {
          pinnedErrorOnMax = maxBoundCtx != null;
          pinnedErrorOnMin = maxBoundCtx == null && minBoundCtx != null;
          if (!pinnedErrorOnMin && deferredMinW003 != null && deferredMinSpan != null) {
            diagnosticBag.addWarning(
                deferredMinW003, deferredMinSpan.start(), deferredMinSpan.end(),
                deferredMinSpan.line(), deferredMinSpan.column(), DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
            );
            deferredMinW003 = null;
          }
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): effective range is invalid (#minIncl "
                  + minIncl.get() + " exceeds #maxExcl " + maxExcl.get() + ")",
              pin.start(), pin.end(), pin.line(), pin.column(), null,
              DiagnosticBag.ERR_INVERTED_RANGE
          );
          minIncl = Optional.empty();
          maxExcl = Optional.empty();
        }
      }

      if (!pinnedErrorOnMin && deferredMinW003 != null && deferredMinSpan != null) {
        diagnosticBag.addWarning(
            deferredMinW003, deferredMinSpan.start(), deferredMinSpan.end(),
            deferredMinSpan.line(), deferredMinSpan.column(), DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
        );
      }
      if (!pinnedErrorOnMax && deferredMaxW003 != null && deferredMaxSpan != null) {
        diagnosticBag.addWarning(
            deferredMaxW003, deferredMaxSpan.start(), deferredMaxSpan.end(),
            deferredMaxSpan.line(), deferredMaxSpan.column(), DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
        );
      }

      return new StvnSchema.TimeEpochSchema(effectiveBrand, scale, finalTraits, minIncl, maxExcl);
    }

    private StvnSchema lowerDateTimeFacets(
        String ownerLabel,
        Optional<String> effectiveBrand,
        StvnSchema.DateTimeSchema dtBase,
        @Nullable String parentName,
        boolean isNominalDerivation,
        @Nullable DateTimeMode childMode,
        StvnParser.@Nullable MetadataRangeContext minBoundCtx,
        StvnParser.@Nullable MetadataRangeContext maxBoundCtx,
        TraitProfile finalTraits,
        ScopeFrame scope
    ) {
      DateTimeMode mode = childMode != null ? childMode : dtBase.mode();
      Optional<String> minRaw = dtBase.explicitMinInclIso();
      Optional<String> maxRaw = dtBase.explicitMaxExclIso();
      Optional<BigInteger> minNanos = minRaw.flatMap(this::parseDateTimeBoundEpochNanosQuiet);
      Optional<BigInteger> maxNanos = maxRaw.flatMap(this::parseDateTimeBoundEpochNanosQuiet);
      String deferredMinW003 = null;
      Span deferredMinSpan = null;
      String deferredMaxW003 = null;
      Span deferredMaxSpan = null;
      String ancName = parentName != null ? parentName : "ancestor";

      if (minBoundCtx != null) {
        Span bSpan = Span.ofContext(minBoundCtx);
        String rawStr = resolveDateTimeBoundStringArg(ownerLabel, StvnVocabulary.FACET_KW_MIN_INCL, minBoundCtx.metadataValue(), scope);
        if (rawStr != null) {
          BigInteger epochNanos = parseDateTimeBoundEpochNanos(ownerLabel, StvnVocabulary.FACET_KW_MIN_INCL, rawStr, bSpan);
          if (epochNanos != null) {
            if (isNominalDerivation && minRaw.isPresent() && minNanos.isPresent()
                && epochNanos.compareTo(minNanos.get()) == 0) {
              deferredMinW003 = "Constraint violation (" + ownerLabel + "): facet '"
                  + StvnVocabulary.FACET_KW_MIN_INCL + " " + formatQuotedStvnString(rawStr)
                  + "' is redundant; ancestor '" + ancName + "' already establishes '"
                  + StvnVocabulary.FACET_KW_MIN_INCL + " " + formatQuotedStvnString(minRaw.get()) + "'";
              deferredMinSpan = bSpan;
              // Retain dtBase.explicitMinInclIso() so equivalent UTC offsets (+00:00 vs Z) preserve CAS bijectivity!
            } else {
              minNanos = Optional.of(epochNanos);
              minRaw = Optional.of(rawStr);
            }
          }
        }
      }

      if (maxBoundCtx != null) {
        Span bSpan = Span.ofContext(maxBoundCtx);
        String rawStr = resolveDateTimeBoundStringArg(ownerLabel, StvnVocabulary.FACET_KW_MAX_EXCL, maxBoundCtx.metadataValue(), scope);
        if (rawStr != null) {
          BigInteger epochNanos = parseDateTimeBoundEpochNanos(ownerLabel, StvnVocabulary.FACET_KW_MAX_EXCL, rawStr, bSpan);
          if (epochNanos != null) {
            if (isNominalDerivation && maxRaw.isPresent() && maxNanos.isPresent()
                && epochNanos.compareTo(maxNanos.get()) == 0) {
              deferredMaxW003 = "Constraint violation (" + ownerLabel + "): facet '"
                  + StvnVocabulary.FACET_KW_MAX_EXCL + " " + formatQuotedStvnString(rawStr)
                  + "' is redundant; ancestor '" + ancName + "' already establishes '"
                  + StvnVocabulary.FACET_KW_MAX_EXCL + " " + formatQuotedStvnString(maxRaw.get()) + "'";
              deferredMaxSpan = bSpan;
              // Retain dtBase.explicitMaxExclIso() so equivalent UTC offsets (+00:00 vs Z) preserve CAS bijectivity!
            } else {
              maxNanos = Optional.of(epochNanos);
              maxRaw = Optional.of(rawStr);
            }
          }
        }
      }

      boolean pinnedErrorOnMin = false;
      boolean pinnedErrorOnMax = false;
      if (minNanos.isPresent() && maxNanos.isPresent()) {
        int cmp = minNanos.get().compareTo(maxNanos.get());
        Span pin = Span.ofContext(maxBoundCtx != null ? maxBoundCtx : minBoundCtx);
        if (cmp == 0) {
          pinnedErrorOnMax = maxBoundCtx != null;
          pinnedErrorOnMin = maxBoundCtx == null && minBoundCtx != null;
          if (!pinnedErrorOnMin && deferredMinW003 != null && deferredMinSpan != null) {
            diagnosticBag.addWarning(
                deferredMinW003, deferredMinSpan.start(), deferredMinSpan.end(),
                deferredMinSpan.line(), deferredMinSpan.column(), DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
            );
            deferredMinW003 = null;
          }
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): datetime half-open interval defines an empty domain",
              pin.start(), pin.end(), pin.line(), pin.column(), null,
              DiagnosticBag.ERR_EMPTY_INTERVAL_DOMAIN
          );
          minRaw = Optional.empty();
          maxRaw = Optional.empty();
        } else if (cmp > 0) {
          pinnedErrorOnMax = maxBoundCtx != null;
          pinnedErrorOnMin = maxBoundCtx == null && minBoundCtx != null;
          if (!pinnedErrorOnMin && deferredMinW003 != null && deferredMinSpan != null) {
            diagnosticBag.addWarning(
                deferredMinW003, deferredMinSpan.start(), deferredMinSpan.end(),
                deferredMinSpan.line(), deferredMinSpan.column(), DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
            );
            deferredMinW003 = null;
          }
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): datetime #minIncl exceeds #maxExcl",
              pin.start(), pin.end(), pin.line(), pin.column(), null,
              DiagnosticBag.ERR_INVERTED_RANGE
          );
          minRaw = Optional.empty();
          maxRaw = Optional.empty();
        }
      }

      if (!pinnedErrorOnMin && deferredMinW003 != null && deferredMinSpan != null) {
        diagnosticBag.addWarning(
            deferredMinW003, deferredMinSpan.start(), deferredMinSpan.end(),
            deferredMinSpan.line(), deferredMinSpan.column(), DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
        );
      }
      if (!pinnedErrorOnMax && deferredMaxW003 != null && deferredMaxSpan != null) {
        diagnosticBag.addWarning(
            deferredMaxW003, deferredMaxSpan.start(), deferredMaxSpan.end(),
            deferredMaxSpan.line(), deferredMaxSpan.column(), DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
        );
      }

      return new StvnSchema.DateTimeSchema(effectiveBrand, mode, finalTraits, minRaw, maxRaw);
    }

    private Optional<BigInteger> parseDateTimeBoundEpochNanosQuiet(String decodedText) {
      int lbIdx = decodedText.indexOf('[');
      int rbIdx = decodedText.endsWith("]") ? decodedText.length() - 1 : -1;
      try {
        Instant inst;
        if (lbIdx > 0 && rbIdx > lbIdx) {
          String prefix = decodedText.substring(0, lbIdx);
          String zoneStr = decodedText.substring(lbIdx + 1, rbIdx);
          if (!java.time.zone.ZoneRulesProvider.getAvailableZoneIds().contains(zoneStr)) {
            return Optional.empty();
          }
          java.time.ZoneId zoneId = java.time.ZoneId.of(zoneStr);
          if (hasExplicitOffsetSuffix(prefix)) {
            java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(prefix);
            List<java.time.ZoneOffset> validOffsets = zoneId.getRules().getValidOffsets(odt.toLocalDateTime());
            if (validOffsets.isEmpty() || !validOffsets.contains(odt.getOffset())) {
              return Optional.empty();
            }
            inst = odt.toInstant();
          } else {
            java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(prefix);
            if (zoneId.getRules().getValidOffsets(ldt).isEmpty()) {
              return Optional.empty();
            }
            inst = ldt.atZone(zoneId).toInstant();
          }
        } else {
          inst = java.time.OffsetDateTime.parse(decodedText).toInstant();
        }
        return Optional.of(BigInteger.valueOf(inst.getEpochSecond())
            .multiply(new BigInteger("1000000000"))
            .add(BigInteger.valueOf(inst.getNano())));
      } catch (Exception ex) {
        return Optional.empty();
      }
    }

    private @Nullable BigInteger parseDateTimeBoundEpochNanos(
        String ownerLabel,
        String boundFacet,
        String decodedText,
        Span bSpan
    ) {
      int lbIdx = decodedText.indexOf('[');
      int rbIdx = decodedText.endsWith("]") ? decodedText.length() - 1 : -1;
      try {
        Instant inst;
        if (lbIdx > 0 && rbIdx > lbIdx) {
          String prefix = decodedText.substring(0, lbIdx);
          String zoneStr = decodedText.substring(lbIdx + 1, rbIdx);
          if (!java.time.zone.ZoneRulesProvider.getAvailableZoneIds().contains(zoneStr)) {
            diagnosticBag.addError(
                "Constraint violation (" + ownerLabel + "): unknown IANA time zone '[" + zoneStr
                    + "]' in datetime bound " + boundFacet + ": " + decodedText,
                bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(), null,
                DiagnosticBag.ERR_INCOMPATIBLE_TYPE
            );
            return null;
          }
          java.time.ZoneId zoneId = java.time.ZoneId.of(zoneStr);
          if (hasExplicitOffsetSuffix(prefix)) {
            java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(prefix);
            java.time.LocalDateTime ldt = odt.toLocalDateTime();
            List<java.time.ZoneOffset> validOffsets = zoneId.getRules().getValidOffsets(ldt);
            if (validOffsets.isEmpty()) {
              diagnosticBag.addError(
                  "Constraint violation (" + ownerLabel + "): datetime bound " + boundFacet
                      + " falls in DST spring-forward gap: " + decodedText,
                  bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(), null,
                  DiagnosticBag.ERR_TEMPORAL_DST_GAP_LITERAL
              );
              return null;
            }
            if (!validOffsets.contains(odt.getOffset())) {
              diagnosticBag.addError(
                  "Constraint violation (" + ownerLabel + "): contradictory offset in audited datetime bound "
                      + boundFacet + ": " + decodedText,
                  bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(), null,
                  DiagnosticBag.ERR_TEMPORAL_AUDITED_MISMATCH
              );
              return null;
            }
            inst = odt.toInstant();
          } else {
            java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(prefix);
            List<java.time.ZoneOffset> validOffsets = zoneId.getRules().getValidOffsets(ldt);
            if (validOffsets.isEmpty()) {
              diagnosticBag.addError(
                  "Constraint violation (" + ownerLabel + "): datetime bound " + boundFacet
                      + " falls in DST spring-forward gap: " + decodedText,
                  bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(), null,
                  DiagnosticBag.ERR_TEMPORAL_DST_GAP_LITERAL
              );
              return null;
            }
            inst = ldt.atZone(zoneId).toInstant();
          }
        } else {
          java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(decodedText);
          inst = odt.toInstant();
        }
        return BigInteger.valueOf(inst.getEpochSecond())
            .multiply(new BigInteger("1000000000"))
            .add(BigInteger.valueOf(inst.getNano()));
      } catch (Exception ex) {
        diagnosticBag.addError(
            "Constraint violation (" + ownerLabel + "): invalid ISO-8601 datetime literal in "
                + boundFacet + ": " + decodedText,
            bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(), ex,
            DiagnosticBag.ERR_INCOMPATIBLE_TYPE
        );
        return null;
      }
    }

    private static boolean hasExplicitOffsetSuffix(String isoPrefix) {
      if (isoPrefix.endsWith("Z") || isoPrefix.endsWith("z")) {
        return true;
      }
      int tIdx = Math.max(isoPrefix.indexOf('T'), isoPrefix.indexOf('t'));
      if (tIdx < 0) {
        return false;
      }
      for (int i = tIdx + 1; i < isoPrefix.length(); i++) {
        char c = isoPrefix.charAt(i);
        if (c == '+' || c == '-') {
          return true;
        }
      }
      return false;
    }

    private static StvnSchema rebrandEnumParent(
        StvnSchema parentEnum,
        StvnSchema.EnumSchema rootEnum,
        Optional<String> effectiveBrand,
        TraitProfile finalTraits
    ) {
      if (parentEnum instanceof StvnSchema.EnumSubsetSchema subset) {
        return new StvnSchema.EnumSubsetSchema(
            effectiveBrand, subset.parentEnum(), subset.rootEnum(), subset.activeVariants(),
            subset.inclusiveFilter(), subset.declaredFilterList(), finalTraits
        );
      }
      return new StvnSchema.EnumSchema(effectiveBrand, rootEnum.rootVariants(), finalTraits);
    }

    private StvnSchema lowerEnumFacets(
        String ownerLabel,
        Optional<String> effectiveBrand,
        StvnSchema parentEnum,
        StvnSchema.EnumSchema rootEnum,
        @Nullable String parentName,
        boolean isNominalDerivation,
        List<String> parentActiveVariants,
        StvnParser.@Nullable MetadataFilterContext filterInclCtx,
        StvnParser.@Nullable MetadataFilterContext filterExclCtx,
        TraitProfile finalTraits,
        TraitProfile unfilteredEnumTraits
    ) {
      if (filterInclCtx == null && filterExclCtx == null) {
        return rebrandEnumParent(parentEnum, rootEnum, effectiveBrand, unfilteredEnumTraits);
      }

      int filterErrorsBefore = diagnosticBag.errorCount();
      boolean isIncl = filterInclCtx != null;
      var filterCtx = isIncl ? filterInclCtx : filterExclCtx;
      var variantList = filterCtx.variantList();
      Span filterSpan = Span.ofContext(filterCtx);

      if (variantList == null || variantList.valueKeyword() == null || variantList.valueKeyword().isEmpty()) {
        diagnosticBag.addError(
            "Enum subset violation (" + ownerLabel + "): filter list cannot be empty",
            filterSpan.start(), filterSpan.end(), filterSpan.line(), filterSpan.column(), null,
            DiagnosticBag.ERR_ENUM_SUBSET_EMPTY
        );
        return rebrandEnumParent(parentEnum, rootEnum, effectiveBrand, unfilteredEnumTraits);
      }

      Map<String, Integer> rootOrdinalMap = new HashMap<>();
      List<String> rootVars = rootEnum.rootVariants();
      for (int i = 0; i < rootVars.size(); i++) {
        rootOrdinalMap.put(rootVars.get(i), i);
      }
      Set<String> parentActiveSet = new HashSet<>(parentActiveVariants);

      Set<String> filterVariants = new LinkedHashSet<>();
      List<String> declaredFilterList = new ArrayList<>();
      Integer lastOrdinal = null;
      boolean hadInvalidMember = false;

      for (var vk : variantList.valueKeyword()) {
        String vName = vk.getText();
        Span vkSpan = Span.ofContext(vk);
        declaredFilterList.add(vName);

        if (!filterVariants.add(vName)) {
          diagnosticBag.addError(
              "Enum subset violation (" + ownerLabel + "): duplicate variant '" + vName + "' in filter list",
              vkSpan.start(), vkSpan.end(), vkSpan.line(), vkSpan.column(), null,
              DiagnosticBag.ERR_FILTER_DUPLICATE_VARIANT
          );
          continue;
        }

        if (!parentActiveSet.contains(vName)) {
          diagnosticBag.addError(
              "Monotonic narrowing violation (" + ownerLabel + "): variant '" + vName + "' is not active in parent enum",
              vkSpan.start(), vkSpan.end(), vkSpan.line(), vkSpan.column(), null,
              DiagnosticBag.ERR_ENUM_SUBSET_NOT_SUBSET
          );
          hadInvalidMember = true;
          continue;
        }

        Integer ord = rootOrdinalMap.get(vName);
        if (ord != null && lastOrdinal != null && ord.compareTo(lastOrdinal) < 0) {
          diagnosticBag.addError(
              "Root ordering violation (" + ownerLabel + "): variant '" + vName
                  + "' violates monotonic root enum declaration order",
              vkSpan.start(), vkSpan.end(), vkSpan.line(), vkSpan.column(), null,
              DiagnosticBag.ERR_ENUM_SUBSET_ORDER_VIOLATION
          );
        }
        if (ord != null) {
          lastOrdinal = ord;
        }
      }

      List<String> childActive = new ArrayList<>();
      for (String v : parentActiveVariants) {
        boolean include = isIncl
            ? filterVariants.contains(v)
            : !filterVariants.contains(v);
        if (include) {
          childActive.add(v);
        }
      }

      if (childActive.isEmpty()) {
        if (!hadInvalidMember) {
          diagnosticBag.addError(
              "Complete exclusion violation (" + ownerLabel + "): filtered enum subset evaluates to zero active variants",
              filterSpan.start(), filterSpan.end(), filterSpan.line(), filterSpan.column(), null,
              DiagnosticBag.ERR_ENUM_SUBSET_EMPTY
          );
        }
        return rebrandEnumParent(parentEnum, rootEnum, effectiveBrand, unfilteredEnumTraits);
      }

      if (diagnosticBag.errorCount() > filterErrorsBefore) {
        return rebrandEnumParent(parentEnum, rootEnum, effectiveBrand, unfilteredEnumTraits);
      }

      if (isNominalDerivation && isIncl && childActive.equals(parentActiveVariants)) {
        String ancName = parentName != null ? parentName : "ancestor";
        String activeBracket = StvnVocabulary.DELIM_OPEN_BRACKET
            + String.join(" ", childActive)
            + StvnVocabulary.DELIM_CLOSE_BRACKET;
        String canonicalFilter = StvnVocabulary.FACET_KW_FILTER_INCL + " " + activeBracket;
        diagnosticBag.addWarning(
            "Constraint violation (" + ownerLabel + "): facet '" + canonicalFilter
                + "' is redundant; ancestor '" + ancName + "' already establishes active variants '"
                + activeBracket + "'",
            filterSpan.start(), filterSpan.end(), filterSpan.line(), filterSpan.column(),
            DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
        );
        return rebrandEnumParent(parentEnum, rootEnum, effectiveBrand, unfilteredEnumTraits);
      }

      return new StvnSchema.EnumSubsetSchema(
          effectiveBrand, parentEnum, rootEnum, childActive, isIncl, declaredFilterList, finalTraits
      );
    }

    // =========================================================================
    // METADATA ARGUMENT EVALUATION & W003 TRAIT WARNING HELPERS
    // =========================================================================

    private @Nullable BigInteger resolveIntegerMetadataArgQuiet(
        StvnParser.@Nullable MetadataValueContext mv,
        ScopeFrame scope
    ) {
      if (isMissingOrAbortedMetadataValue(mv)) {
        return null;
      }
      if (mv.integerLiteral() != null) {
        String raw = mv.integerLiteral().getText();
        if (StvnLiteralParser.isNegativeZero(raw)) {
          return null;
        }
        return StvnLiteralParser.parseBigInteger(raw);
      }
      if (mv.facetConstRef() != null) {
        String kw = mv.facetConstRef().getText();
        if (isBuiltinNonConstantValueKeyword(kw)) {
          return null;
        }
        Optional<StvnDefsCatalog.StvnConstant> cOpt = resolveConstantInScope(kw, scope);
        if (cOpt.isEmpty() || isPoisonedConstant(cOpt.get())) {
          return null;
        }
        StvnValue cv = cOpt.get().value();
        if (cv instanceof StvnValue.StvnInt si) {
          return si.value();
        }
        if (cv instanceof StvnValue.StvnTimeEpoch te) {
          return te.epochTicks();
        }
      }
      return null;
    }

    private @Nullable BigInteger resolveIntegerMetadataArg(
        String ownerLabel,
        String facetLabel,
        StvnParser.@Nullable MetadataValueContext mv,
        ScopeFrame scope
    ) {
      if (mv == null || mv.stringLiteral() instanceof StvnParser.StringMalformedFencedContext) {
        return null;
      }
      Span sp = Span.ofContext(mv);
      if (mv.integerLiteral() != null) {
        String raw = mv.integerLiteral().getText();
        if (StvnLiteralParser.isNegativeZero(raw)) {
          diagnosticBag.addError(
              "Negative zero '" + raw + "' is prohibited on integer facet '" + facetLabel + "'",
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_INVALID_NUMERIC_LITERAL
          );
          return null;
        }
        return StvnLiteralParser.parseBigInteger(raw);
      }
      if (mv.facetConstRef() != null) {
        String kw = mv.facetConstRef().getText();
        if (isBuiltinNonConstantValueKeyword(kw)) {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): facet '" + facetLabel
                  + "' requires an integer argument, got '" + kw + "'",
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_INCOMPATIBLE_TYPE
          );
          return null;
        }
        Optional<StvnDefsCatalog.StvnConstant> cOpt = resolveConstantInScope(kw, scope);
        if (cOpt.isEmpty()) {
          diagnosticBag.addError(
              "Undefined constant '" + kw + "' referenced in metadata facet '" + facetLabel + "'",
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_UNDEFINED_CONSTANT
          );
          return null;
        }
        if (isPoisonedConstant(cOpt.get())) {
          return null;
        }
        StvnValue cv = cOpt.get().value();
        if (cv instanceof StvnValue.StvnInt si) {
          return si.value();
        }
        if (cv instanceof StvnValue.StvnTimeEpoch te) {
          return te.epochTicks();
        }
        diagnosticBag.addError(
            "Constraint violation (" + ownerLabel + "): constant '" + kw + "' in facet '"
                + facetLabel + "' must resolve to an integer, got " + cOpt.get().schema().getClass().getSimpleName(),
            sp.start(), sp.end(), sp.line(), sp.column(), null,
            DiagnosticBag.ERR_INCOMPATIBLE_TYPE
        );
        return null;
      }
      String nonIntMsg;
      if (mv.floatLiteral() != null) {
        nonIntMsg = "Constraint violation (" + ownerLabel + "): " + facetLabel.substring(1)
            + " for " + StvnVocabulary.TYPE_INT + " requires an integer literal";
      } else if (mv.stringLiteral() != null) {
        nonIntMsg = "Constraint violation (" + ownerLabel + "): " + facetLabel
            + " requires an integer literal, found string";
      } else if (mv.booleanLiteral() != null) {
        nonIntMsg = "Constraint violation (" + ownerLabel + "): " + facetLabel
            + " requires an integer literal, found boolean";
      } else {
        nonIntMsg = "Constraint violation (" + ownerLabel + "): facet '" + facetLabel
            + "' requires an integer literal, got '" + mv.getText() + "'";
      }
      diagnosticBag.addError(
          nonIntMsg,
          sp.start(), sp.end(), sp.line(), sp.column(), null,
          DiagnosticBag.ERR_INCOMPATIBLE_TYPE
      );
      return null;
    }

    private @Nullable BigDecimal resolveDecimalMetadataArg(
        String ownerLabel,
        String facetLabel,
        StvnParser.@Nullable MetadataValueContext mv,
        FloatRepresentation rep,
        BigInteger exactMaxDigits,
        ScopeFrame scope
    ) {
      if (mv == null || mv.stringLiteral() instanceof StvnParser.StringMalformedFencedContext) {
        return null;
      }
      Span sp = Span.ofContext(mv);
      BigDecimal bd = null;
      if (mv.floatLiteral() != null) {
        try {
          bd = new BigDecimal(mv.floatLiteral().getText());
        } catch (NumberFormatException nfe) {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): invalid float literal in '" + facetLabel + "'",
              sp.start(), sp.end(), sp.line(), sp.column(), nfe,
              DiagnosticBag.ERR_INVALID_NUMERIC_LITERAL
          );
          return null;
        }
      } else if (mv.integerLiteral() != null) {
        diagnosticBag.addError(
            "Constraint violation (" + ownerLabel + "): " + facetLabel.substring(1)
                + " for " + StvnVocabulary.TYPE_FLOAT + " requires a float literal",
            sp.start(), sp.end(), sp.line(), sp.column(), null,
            DiagnosticBag.ERR_INCOMPATIBLE_TYPE
        );
        return null;
      } else if (mv.facetConstRef() != null) {
        String kw = mv.facetConstRef().getText();
        if (isBuiltinNonConstantValueKeyword(kw)) {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): facet '" + facetLabel
                  + "' requires a float literal, got '" + kw + "'",
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_INCOMPATIBLE_TYPE
          );
          return null;
        }
        Optional<StvnDefsCatalog.StvnConstant> cOpt = resolveConstantInScope(kw, scope);
        if (cOpt.isEmpty()) {
          diagnosticBag.addError(
              "Undefined constant '" + kw + "' referenced in metadata facet '" + facetLabel + "'",
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_UNDEFINED_CONSTANT
          );
          return null;
        }
        if (isPoisonedConstant(cOpt.get())) {
          return null;
        }
        StvnValue cv = cOpt.get().value();
        if (cv instanceof StvnValue.StvnFloat sf) {
          bd = sf.decimalValue();
        } else {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): constant '" + kw + "' in facet '"
                  + facetLabel + "' must resolve to " + StvnVocabulary.TYPE_FLOAT,
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_INCOMPATIBLE_TYPE
          );
          return null;
        }
      } else {
        diagnosticBag.addError(
            "Constraint violation (" + ownerLabel + "): facet '" + facetLabel
                + "' requires a float literal, got '" + mv.getText() + "'",
            sp.start(), sp.end(), sp.line(), sp.column(), null,
            DiagnosticBag.ERR_INCOMPATIBLE_TYPE
        );
        return null;
      }

      // Check float capacity of bound!
      if (rep == FloatRepresentation.IEEE_32) {
        if (bd.abs().compareTo(IEEE_32_MAX_FINITE) > 0) {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): bound " + bd + " in '" + facetLabel
                  + "' overflows 32-bit IEEE-754 finite range; valid value range is ["
                  + StvnVocabulary.FLOAT32_MIN + ", " + StvnVocabulary.FLOAT32_MAX + "]",
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_FLOAT_OVERFLOW
          );
          return null;
        }
      } else if (rep == FloatRepresentation.IEEE_64) {
        double d = bd.doubleValue();
        if (Double.isInfinite(d) || Double.isNaN(d)) {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): bound " + bd + " in '" + facetLabel
                  + "' overflows 64-bit IEEE-754 finite range; valid value range is ["
                  + StvnVocabulary.FLOAT64_MIN + ", " + StvnVocabulary.FLOAT64_MAX + "]",
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_FLOAT_OVERFLOW
          );
          return null;
        }
      } else if (rep == FloatRepresentation.EXACT_DECIMAL) {
        int sigDigits = bd.stripTrailingZeros().precision();
        if (BigInteger.valueOf(sigDigits).compareTo(exactMaxDigits) > 0) {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): bound " + bd + " requires " + sigDigits
                  + " significant digits, exceeding #maxSize " + exactMaxDigits,
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_CAPACITY_OVERFLOW
          );
          return null;
        }
      }
      return bd;
    }

    private @Nullable Boolean resolveBooleanMetadataArg(
        String ownerLabel,
        String facetLabel,
        StvnParser.@Nullable MetadataValueContext mv,
        ScopeFrame scope
    ) {
      if (isMissingOrAbortedMetadataValue(mv) || mv.stringLiteral() instanceof StvnParser.StringMalformedFencedContext) {
        return null;
      }
      Span sp = Span.ofContext(mv);
      if (mv.booleanLiteral() != null) {
        String txt = mv.booleanLiteral().getText();
        return StvnVocabulary.VAL_TRUE.equals(txt) || StvnVocabulary.VAL_TRUE_SHORT.equals(txt);
      }
      if (mv.facetConstRef() != null) {
        String kw = mv.facetConstRef().getText();
        if (isBuiltinNonConstantValueKeyword(kw)) {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): facet '" + facetLabel
                  + "' requires a boolean argument, got '" + kw + "'",
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_INCOMPATIBLE_TYPE
          );
          return null;
        }
        Optional<StvnDefsCatalog.StvnConstant> cOpt = resolveConstantInScope(kw, scope);
        if (cOpt.isEmpty()) {
          diagnosticBag.addError(
              "Undefined constant '" + kw + "' referenced in metadata facet '" + facetLabel + "'",
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_UNDEFINED_CONSTANT
          );
          return null;
        }
        if (isPoisonedConstant(cOpt.get())) {
          return null;
        }
        if (cOpt.get().value() instanceof StvnValue.StvnBoolean sb) {
          return sb.value();
        }
        diagnosticBag.addError(
            "Constraint violation (" + ownerLabel + "): constant '" + kw + "' in facet '"
                + facetLabel + "' must resolve to " + StvnVocabulary.TYPE_BOOLEAN,
            sp.start(), sp.end(), sp.line(), sp.column(), null,
            DiagnosticBag.ERR_INCOMPATIBLE_TYPE
        );
        return null;
      }
      diagnosticBag.addError(
          "Constraint violation (" + ownerLabel + "): facet '" + facetLabel
              + "' requires a boolean argument, got '" + mv.getText() + "'",
          sp.start(), sp.end(), sp.line(), sp.column(), null,
          DiagnosticBag.ERR_INCOMPATIBLE_TYPE
      );
      return null;
    }

    private @Nullable Boolean resolvePreserveIndentArg(
        String ownerLabel,
        StvnParser.@Nullable MetadataTraitContext traitCtx,
        ScopeFrame scope
    ) {
      if (traitCtx == null) {
        return null;
      }
      StvnParser.MetadataValueContext mv = traitCtx.metadataValue();
      if (isMissingOrAbortedMetadataValue(mv) || mv.stringLiteral() instanceof StvnParser.StringMalformedFencedContext) {
        return null;
      }
      Span sp = Span.ofContext(mv);
      if (mv.booleanLiteral() != null) {
        String txt = mv.booleanLiteral().getText();
        return StvnVocabulary.VAL_TRUE.equals(txt) || StvnVocabulary.VAL_TRUE_SHORT.equals(txt);
      }
      if (mv.facetConstRef() != null) {
        String kw = mv.facetConstRef().getText();
        if (isBuiltinNonConstantValueKeyword(kw)) {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): facet '"
                  + StvnVocabulary.FACET_KW_PRESERVE_INDENT + "' requires a boolean argument, got '" + kw + "'",
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_INCOMPATIBLE_TYPE
          );
          return null;
        }
        Optional<StvnDefsCatalog.StvnConstant> cOpt = resolveConstantInScope(kw, scope);
        if (cOpt.isEmpty()) {
          diagnosticBag.addError(
              "Undefined constant '" + kw + "' referenced in metadata facet '"
                  + StvnVocabulary.FACET_KW_PRESERVE_INDENT + "'",
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_UNDEFINED_CONSTANT
          );
          return null;
        }
        if (isPoisonedConstant(cOpt.get())) {
          return null;
        }
        if (cOpt.get().value() instanceof StvnValue.StvnBoolean sb) {
          return sb.value();
        }
        diagnosticBag.addError(
            "Constraint violation (" + ownerLabel + "): constant '" + kw
                + "' in facet '" + StvnVocabulary.FACET_KW_PRESERVE_INDENT + "' must resolve to "
                + StvnVocabulary.TYPE_BOOLEAN,
            sp.start(), sp.end(), sp.line(), sp.column(), null,
            DiagnosticBag.ERR_INCOMPATIBLE_TYPE
        );
        return null;
      }
      diagnosticBag.addError(
          "Metadata facet '" + StvnVocabulary.FACET_KW_PRESERVE_INDENT
              + "' requires an explicit boolean value ("
              + StvnVocabulary.VAL_TRUE + " or " + StvnVocabulary.VAL_FALSE + ")",
          sp.start(), sp.end(), sp.line(), sp.column(), null,
          DiagnosticBag.ERR_INVALID_METADATA_FACET
      );
      return null;
    }

    private @Nullable String resolveStringMetadataArg(
        String ownerLabel,
        String facetLabel,
        StvnParser.@Nullable MetadataValueContext mv,
        ScopeFrame scope
    ) {
      if (isMissingOrAbortedMetadataValue(mv) || mv.stringLiteral() instanceof StvnParser.StringMalformedFencedContext) {
        return null;
      }
      Span sp = Span.ofContext(mv);
      if (mv.stringLiteral() != null) {
        try {
          return StvnPayloadLowerer.decodeStringLiteral(mv.stringLiteral().getText(), false, sp);
        } catch (StvnPayloadLowerer.LoweringLiteralException le) {
          diagnosticBag.addErrorDual(
              le.getMessage(), le.span().start(), le.span().end(), le.span().line(), le.span().column(),
              le.getCause(), le.canonicalCode(), le.symbolicId()
          );
          return null;
        }
      }
      if (mv.facetConstRef() != null) {
        String kw = mv.facetConstRef().getText();
        if (isBuiltinNonConstantValueKeyword(kw)) {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): facet '" + facetLabel
                  + "' requires a string argument, got '" + kw + "'",
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_INCOMPATIBLE_TYPE
          );
          return null;
        }
        Optional<StvnDefsCatalog.StvnConstant> cOpt = resolveConstantInScope(kw, scope);
        if (cOpt.isEmpty()) {
          diagnosticBag.addError(
              "Undefined constant '" + kw + "' referenced in metadata facet '" + facetLabel + "'",
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_UNDEFINED_CONSTANT
          );
          return null;
        }
        if (isPoisonedConstant(cOpt.get())) {
          return null;
        }
        if (cOpt.get().value() instanceof StvnValue.StvnString ss) {
          return ss.value();
        }
        diagnosticBag.addError(
            "Constraint violation (" + ownerLabel + "): constant '" + kw + "' in facet '"
                + facetLabel + "' must resolve to " + StvnVocabulary.TYPE_STRING,
            sp.start(), sp.end(), sp.line(), sp.column(), null,
            DiagnosticBag.ERR_INCOMPATIBLE_TYPE
        );
        return null;
      }
      diagnosticBag.addError(
          "Constraint violation (" + ownerLabel + "): facet '" + facetLabel
              + "' requires a string literal, got '" + mv.getText() + "'",
          sp.start(), sp.end(), sp.line(), sp.column(), null,
          DiagnosticBag.ERR_INCOMPATIBLE_TYPE
      );
      return null;
    }

    private @Nullable String resolveDateTimeBoundStringArg(
        String ownerLabel,
        String facetLabel,
        StvnParser.@Nullable MetadataValueContext mv,
        ScopeFrame scope
    ) {
      if (isMissingOrAbortedMetadataValue(mv) || mv.stringLiteral() instanceof StvnParser.StringMalformedFencedContext) {
        return null;
      }
      Span sp = Span.ofContext(mv);
      if (mv.stringLiteral() != null) {
        try {
          return StvnPayloadLowerer.decodeStringLiteral(mv.stringLiteral().getText(), true, sp);
        } catch (StvnPayloadLowerer.LoweringLiteralException le) {
          diagnosticBag.addErrorDual(
              le.getMessage(), le.span().start(), le.span().end(), le.span().line(), le.span().column(),
              le.getCause(), le.canonicalCode(), le.symbolicId()
          );
          return null;
        }
      }
      if (mv.facetConstRef() != null) {
        String kw = mv.facetConstRef().getText();
        Optional<StvnDefsCatalog.StvnConstant> cOpt = resolveConstantInScope(kw, scope);
        if (cOpt.isEmpty()) {
          diagnosticBag.addError(
              "Undefined constant '" + kw + "' referenced in metadata facet '" + facetLabel + "'",
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_UNDEFINED_CONSTANT
          );
          return null;
        }
        if (isPoisonedConstant(cOpt.get())) {
          return null;
        }
        return switch (cOpt.get().value()) {
          case StvnValue.StvnString ss -> ss.value();
          case StvnValue.StvnDateTime dt -> dt.canonicalIsoText();
          default -> {
            diagnosticBag.addError(
                "Constraint violation (" + ownerLabel + "): constant '" + kw + "' in facet '"
                    + facetLabel + "' must resolve to a datetime or string literal",
                sp.start(), sp.end(), sp.line(), sp.column(), null,
                DiagnosticBag.ERR_INCOMPATIBLE_TYPE
            );
            yield null;
          }
        };
      }
      diagnosticBag.addError(
          "Constraint violation (" + ownerLabel + "): facet '" + facetLabel
              + "' on " + StvnVocabulary.TYPE_DATE_TIME + " requires an ISO-8601 string literal, got '" + mv.getText() + "'",
          sp.start(), sp.end(), sp.line(), sp.column(), null,
          DiagnosticBag.ERR_INCOMPATIBLE_TYPE
      );
      return null;
    }

    private void emitRedundantTraitWarning(
        String ownerLabel,
        String traitName,
        boolean declaredVal,
        boolean defaultVal,
        String targetBaseType,
        @Nullable String ancestorName,
        boolean isExactFloat,
        Span span
    ) {
      String boolLit = declaredVal ? StvnVocabulary.VAL_TRUE : StvnVocabulary.VAL_FALSE;
      String defBoolLit = defaultVal ? StvnVocabulary.VAL_TRUE : StvnVocabulary.VAL_FALSE;
      String traitKw = "equatable".equals(traitName)
          ? StvnVocabulary.FACET_KW_EQUATABLE
          : StvnVocabulary.FACET_KW_COMPARABLE;
      String canonicalFacet = traitKw + " " + boolLit;
      String defaultFacet = traitKw + " " + defBoolLit;
      String message;
      if (ancestorName != null) {
        message = "Constraint violation (" + ownerLabel + "): facet '" + canonicalFacet
            + "' is redundant; ancestor '" + ancestorName + "' already establishes '" + defaultFacet + "'";
      } else if (targetBaseType.equals(StvnVocabulary.TYPE_FLOAT) && !isExactFloat && "equatable".equals(traitName) && !declaredVal) {
        message = "Constraint violation (" + ownerLabel + "): facet '" + canonicalFacet + "' is redundant; continuous '"
            + StvnVocabulary.TYPE_FLOAT + "' is non-equatable by default";
      } else if (targetBaseType.equals(StvnVocabulary.TYPE_FLOAT) && !isExactFloat && "comparable".equals(traitName) && declaredVal) {
        message = "Constraint violation (" + ownerLabel + "): facet '" + canonicalFacet + "' is redundant; continuous '"
            + StvnVocabulary.TYPE_FLOAT + "' is intrinsically comparable";
      } else if (targetBaseType.equals(StvnVocabulary.TYPE_FLOAT) && isExactFloat && declaredVal) {
        message = "Constraint violation (" + ownerLabel + "): facet '" + canonicalFacet
            + "' is redundant; exact decimal float is intrinsically " + traitName;
      } else if ((targetBaseType.equals(StvnVocabulary.TYPE_BOOLEAN)
          || targetBaseType.equals(StvnVocabulary.TYPE_INT)
          || targetBaseType.equals(StvnVocabulary.TYPE_STRING)
          || targetBaseType.equals(StvnVocabulary.TYPE_TIME_EPOCH)
          || targetBaseType.equals(StvnVocabulary.TYPE_DATE_TIME)) && declaredVal) {
        message = "Constraint violation (" + ownerLabel + "): facet '" + canonicalFacet
            + "' is redundant; scalar primitive '" + targetBaseType + "' is intrinsically " + traitName;
      } else {
        message = "Constraint violation (" + ownerLabel + "): facet '" + canonicalFacet
            + "' is redundant; '" + targetBaseType + "' already resolves to '" + defaultFacet + "'";
      }
      diagnosticBag.addWarning(
          message, span.start(), span.end(), span.line(), span.column(),
          DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
      );
    }

    private static String formatCanonicalFloatBound(BigDecimal value, FloatRepresentation representation) {
      String s = (representation == FloatRepresentation.EXACT_DECIMAL)
          ? value.toPlainString()
          : value.toString();
      if (!s.contains(".")) {
        int eIdx = s.indexOf('e');
        if (eIdx == -1) {
          eIdx = s.indexOf('E');
        }
        return (eIdx == -1)
            ? s + ".0"
            : s.substring(0, eIdx) + ".0" + s.substring(eIdx);
      }
      return s;
    }

    private static String formatQuotedStvnString(String raw) {
      var sb = new StringBuilder(raw.length() + 4);
      sb.append('"');
      for (int i = 0; i < raw.length(); i++) {
        char c = raw.charAt(i);
        switch (c) {
          case '\\' -> sb.append("\\\\");
          case '"' -> sb.append("\\\"");
          case '\n' -> sb.append("\\n");
          case '\r' -> sb.append("\\r");
          case '\t' -> sb.append("\\t");
          case '\b' -> sb.append("\\b");
          case '\f' -> sb.append("\\f");
          default -> {
            if (c < 0x20) {
              sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
              sb.append(c);
            }
          }
        }
      }
      sb.append('"');
      return sb.toString();
    }
  }

  // ===========================================================================
  // STATIC FACET TAXONOMY, 5-TIER RANKING & VOCABULARY HELPERS
  // ===========================================================================

  private static final Set<String> PURGED_LEGACY_PRELUDE_ALIASES = Set.of(
      ":DateTimeOffset", ":DateTimeZoned", ":DateTimeAudited",
      ":org/stvnadore/prelude/DateTimeOffset",
      ":org/stvnadore/prelude/DateTimeZoned",
      ":org/stvnadore/prelude/DateTimeAudited",
      ":EpochSeconds", ":EpochMillis", ":EpochMicros", ":EpochNanos",
      ":org/stvnadore/prelude/Uuid",
      ":org/stvnadore/prelude/Sha256",
      ":org/stvnadore/prelude/Email"
  );

  private static boolean isPurgedPreludeAlias(String rawKw) {
    return rawKw.startsWith(PRELUDE_PKG_ROOT + "/") && PURGED_LEGACY_PRELUDE_ALIASES.contains(rawKw);
  }

  private static boolean isBuiltinNonConstantValueKeyword(String kw) {
    return kw.equals(StvnVocabulary.VAL_NONE) || kw.equals(StvnVocabulary.VAL_NONE_SHORT)
        || kw.equals(StvnVocabulary.VAL_SOME) || kw.equals(StvnVocabulary.VAL_SOME_SHORT)
        || kw.equals(StvnVocabulary.VAL_LEFT) || kw.equals(StvnVocabulary.VAL_LEFT_SHORT)
        || kw.equals(StvnVocabulary.VAL_RIGHT) || kw.equals(StvnVocabulary.VAL_RIGHT_SHORT);
  }

  private static String baseTypeKeywordOf(StvnSchema schema) {
    return switch (schema) {
      case StvnSchema.BooleanSchema ignored -> StvnVocabulary.TYPE_BOOLEAN;
      case StvnSchema.IntSchema ignored -> StvnVocabulary.TYPE_INT;
      case StvnSchema.FloatSchema ignored -> StvnVocabulary.TYPE_FLOAT;
      case StvnSchema.StringSchema ignored -> StvnVocabulary.TYPE_STRING;
      case StvnSchema.TimeEpochSchema ignored -> StvnVocabulary.TYPE_TIME_EPOCH;
      case StvnSchema.DateTimeSchema ignored -> StvnVocabulary.TYPE_DATE_TIME;
      case StvnSchema.EnumSchema ignored -> StvnVocabulary.TYPE_ENUM;
      case StvnSchema.EnumSubsetSchema ignored -> StvnVocabulary.TYPE_ENUM;
      case StvnSchema.TupleSchema ignored -> StvnVocabulary.TYPE_TUPLE;
      case StvnSchema.OptionSchema ignored -> StvnVocabulary.TYPE_OPTION;
      case StvnSchema.EitherSchema ignored -> StvnVocabulary.TYPE_EITHER;
      case StvnSchema.UnionSchema ignored -> StvnVocabulary.TYPE_UNION;
      case StvnSchema.SeqSchema ignored -> StvnVocabulary.TYPE_SEQ;
      case StvnSchema.SetSchema ignored -> StvnVocabulary.TYPE_SET;
      case StvnSchema.MapSchema ignored -> StvnVocabulary.TYPE_MAP;
      case StvnSchema.PoisonedSchema ignored -> StvnVocabulary.TYPE_BOOLEAN;
    };
  }

  private static Set<String> permittedFacetsFor(String baseKw, boolean isNominalDerivation) {
    if (baseKw.equals(StvnVocabulary.TYPE_BOOLEAN)) {
      return Set.of("equatable", "comparable");
    }
    if (baseKw.equals(StvnVocabulary.TYPE_INT)) {
      return Set.of("unsigned", "size", "equatable", "comparable", "minIncl", "maxExcl");
    }
    if (baseKw.equals(StvnVocabulary.TYPE_FLOAT)) {
      return Set.of("exact", "size", "maxSize", "equatable", "comparable", "minIncl", "minExcl", "maxIncl", "maxExcl");
    }
    if (baseKw.equals(StvnVocabulary.TYPE_STRING)) {
      return Set.of("minSize", "maxSize", "equatable", "comparable", "minIncl", "minExcl", "maxIncl", "maxExcl", "preserveIndent", "regex");
    }
    if (baseKw.equals(StvnVocabulary.TYPE_TIME_EPOCH)) {
      return Set.of("ns", "us", "ms", "s", "equatable", "comparable", "minIncl", "maxExcl");
    }
    if (baseKw.equals(StvnVocabulary.TYPE_DATE_TIME)) {
      return Set.of("offset", "zoned", "audited", "equatable", "comparable", "minIncl", "maxExcl");
    }
    if (baseKw.equals(StvnVocabulary.TYPE_ENUM)) {
      return isNominalDerivation
          ? Set.of("equatable", "comparable", "filterIncl", "filterExcl")
          : Set.of("equatable", "comparable");
    }
    if (baseKw.equals(StvnVocabulary.TYPE_TUPLE)
        || baseKw.equals(StvnVocabulary.TYPE_OPTION)
        || baseKw.equals(StvnVocabulary.TYPE_EITHER)
        || baseKw.equals(StvnVocabulary.TYPE_UNION)) {
      return Set.of("equatable", "comparable");
    }
    if (baseKw.equals(StvnVocabulary.TYPE_SEQ) || baseKw.equals(StvnVocabulary.TYPE_SET)) {
      return Set.of("minSize", "maxSize", "equatable", "comparable");
    }
    if (baseKw.equals(StvnVocabulary.TYPE_MAP)) {
      return Set.of("invertible", "minSize", "maxSize", "equatable", "comparable");
    }
    return Set.of();
  }

  private static StvnParser.@Nullable StandaloneFacetIllegalArgContext extractStandaloneIllegalArg(
      StvnParser.MetadataEntryContext entry
  ) {
    if (entry.facetUnsigned() != null) {
      return entry.facetUnsigned().standaloneFacetIllegalArg();
    }
    if (entry.facetExact() != null) {
      return entry.facetExact().standaloneFacetIllegalArg();
    }
    if (entry.facetInvertible() != null) {
      return entry.facetInvertible().standaloneFacetIllegalArg();
    }
    if (entry.facetTemporalScale() != null) {
      return entry.facetTemporalScale().standaloneFacetIllegalArg();
    }
    if (entry.facetTemporalMode() != null) {
      return entry.facetTemporalMode().standaloneFacetIllegalArg();
    }
    if (entry.facetDirective() != null) {
      return entry.facetDirective().standaloneFacetIllegalArg();
    }
    return null;
  }

  private static @Nullable String extractFacetName(StvnParser.MetadataEntryContext entry) {
    if (entry.facetUnsigned() != null) {
      return "unsigned";
    }
    if (entry.facetExact() != null) {
      return "exact";
    }
    if (entry.facetInvertible() != null) {
      return "invertible";
    }
    if (entry.facetDirective() != null) {
      return "strip";
    }
    if (entry.facetTemporalScale() != null) {
      var ts = entry.facetTemporalScale();
      if (ts.KW_SCALE_NS() != null) return "ns";
      if (ts.KW_SCALE_US() != null) return "us";
      if (ts.KW_SCALE_MS() != null) return "ms";
      if (ts.KW_SCALE_S() != null) return "s";
    }
    if (entry.facetTemporalMode() != null) {
      var tm = entry.facetTemporalMode();
      if (tm.KW_OFFSET() != null) return "offset";
      if (tm.KW_ZONED() != null) return "zoned";
      if (tm.KW_AUDITED() != null) return "audited";
    }
    if (entry.metadataSize() != null) {
      var ms = entry.metadataSize();
      if (ms.KW_SIZE() != null) return "size";
      if (ms.KW_MIN_SIZE() != null) return "minSize";
      if (ms.KW_MAX_SIZE() != null) return "maxSize";
    }
    if (entry.metadataBool() != null) {
      var mb = entry.metadataBool();
      if (mb.KW_EQUATABLE() != null) return "equatable";
      if (mb.KW_COMPARABLE() != null) return "comparable";
    }
    if (entry.metadataTrait() != null) {
      return "preserveIndent";
    }
    if (entry.metadataRange() != null) {
      var mr = entry.metadataRange();
      if (mr.KW_MIN_INCL() != null) return "minIncl";
      if (mr.KW_MIN_EXCL() != null) return "minExcl";
      if (mr.KW_MAX_INCL() != null) return "maxIncl";
      if (mr.KW_MAX_EXCL() != null) return "maxExcl";
    }
    if (entry.metadataString() != null) {
      return "regex";
    }
    if (entry.metadataFilter() != null) {
      var mf = entry.metadataFilter();
      if (mf.KW_FILTER_INCL() != null) return "filterIncl";
      if (mf.KW_FILTER_EXCL() != null) return "filterExcl";
    }
    return null;
  }

  private static boolean isTemporalScaleName(String facetName) {
    return "ns".equals(facetName) || "us".equals(facetName) || "ms".equals(facetName) || "s".equals(facetName);
  }

  private static boolean isTemporalModeName(String facetName) {
    return "offset".equals(facetName) || "zoned".equals(facetName) || "audited".equals(facetName);
  }

  private static TemporalScale parseTemporalScale(String facetName) {
    return switch (facetName) {
      case "ns" -> TemporalScale.NS;
      case "us" -> TemporalScale.US;
      case "ms" -> TemporalScale.MS;
      default -> TemporalScale.S;
    };
  }

  private static String temporalScaleKeyword(TemporalScale scale) {
    return switch (scale) {
      case NS -> StvnVocabulary.FACET_KW_SCALE_NS;
      case US -> StvnVocabulary.FACET_KW_SCALE_US;
      case MS -> StvnVocabulary.FACET_KW_SCALE_MS;
      case S -> StvnVocabulary.FACET_KW_SCALE_S;
    };
  }

  private static DateTimeMode parseDateTimeMode(String facetName) {
    return switch (facetName) {
      case "zoned" -> DateTimeMode.ZONED;
      case "audited" -> DateTimeMode.AUDITED;
      default -> DateTimeMode.OFFSET;
    };
  }

  private static String dateTimeModeKeyword(DateTimeMode mode) {
    return switch (mode) {
      case OFFSET -> StvnVocabulary.FACET_KW_OFFSET;
      case ZONED -> StvnVocabulary.FACET_KW_ZONED;
      case AUDITED -> StvnVocabulary.FACET_KW_AUDITED;
    };
  }

  private static int getFacetTier(String facetName) {
    return switch (facetName) {
      case "unsigned", "exact", "invertible", "ns", "us", "ms", "s", "offset", "zoned", "audited", "size", "minSize", "maxSize" -> 1;
      case "equatable", "comparable" -> 2;
      case "minIncl", "minExcl", "maxIncl", "maxExcl" -> 3;
      case "filterIncl", "filterExcl", "preserveIndent", "regex" -> 4;
      case "strip" -> 5;
      default -> 99;
    };
  }

  private static int getFacetSubTier(String facetName) {
    return switch (facetName) {
      case "unsigned" -> 1110;
      case "exact" -> 1120;
      case "invertible" -> 1130;
      case "ns" -> 1210;
      case "us" -> 1220;
      case "ms" -> 1230;
      case "s" -> 1240;
      case "offset" -> 1250;
      case "zoned" -> 1260;
      case "audited" -> 1270;
      case "size" -> 1310;
      case "minSize" -> 1510;
      case "maxSize" -> 1520;
      case "equatable" -> 2100;
      case "comparable" -> 2200;
      case "minIncl" -> 3100;
      case "minExcl" -> 3200;
      case "maxIncl" -> 3300;
      case "maxExcl" -> 3400;
      case "filterIncl" -> 4100;
      case "filterExcl" -> 4200;
      case "preserveIndent" -> 4300;
      case "regex" -> 4400;
      case "strip" -> 5000;
      default -> 9900;
    };
  }

  /**
   * Formats the valid physical integer range for a declared bit-width and signedness.
   * <p>
   * For bit-widths {@code s <= 128}, outputs the exact decimal interval {@code [min, max]}.
   * For bit-widths {@code s > 128}, outputs the symbolic power-of-two interval.
   *
   * @param bitWidth              the declared integer bit-width
   * @param unsigned              {@code true} if unsigned, {@code false} if signed two's-complement
   * @param isExclusiveUpperBound {@code true} when formatting the valid range for {@code #maxExcl} on {@code :Int},
   *                              which shifts the inclusive member range by {@code +1}
   * @return the formatted valid physical range string
   */
  public static String formatPhysicalIntRange(BigInteger bitWidth, boolean unsigned, boolean isExclusiveUpperBound) {
    if (bitWidth.compareTo(BigInteger.valueOf(128)) <= 0) {
      int s = bitWidth.intValue();
      BigInteger minAllowed = unsigned ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(s - 1).negate();
      BigInteger maxAllowed = unsigned
          ? BigInteger.ONE.shiftLeft(s).subtract(BigInteger.ONE)
          : BigInteger.ONE.shiftLeft(s - 1).subtract(BigInteger.ONE);
      if (isExclusiveUpperBound) {
        return "[" + minAllowed.add(BigInteger.ONE) + ", " + maxAllowed.add(BigInteger.ONE) + "]";
      }
      return "[" + minAllowed + ", " + maxAllowed + "]";
    }
    if (unsigned) {
      return isExclusiveUpperBound
          ? "[1, 2^" + bitWidth + "]"
          : "[0, 2^" + bitWidth + " - 1]";
    }
    BigInteger exp = bitWidth.subtract(BigInteger.ONE);
    return isExclusiveUpperBound
        ? "[-2^" + exp + " + 1, 2^" + exp + "]"
        : "[-2^" + exp + ", 2^" + exp + " - 1]";
  }
}
