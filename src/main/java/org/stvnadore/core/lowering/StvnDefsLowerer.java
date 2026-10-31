package org.stvnadore.core.lowering;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnVocabulary;
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
import org.stvnadore.core.stdlib.StvnPrelude;
import org.stvnadore.core.validation.CyclicDependencyException;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.core.validation.DuplicateModuleImportException;
import org.stvnadore.core.validation.MalformedSchemaException;
import org.stvnadore.core.validation.NamespaceCollisionException;
import org.stvnadore.core.validation.StvnTypeResolver;

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
      boolean sectionsEligible
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
     */
    public DefsLoweringResult {
      Objects.requireNonNull(meta, "meta must not be null");
      Objects.requireNonNull(defs, "defs must not be null");
      Objects.requireNonNull(rootType, "rootType must not be null");
      Objects.requireNonNull(rootConstantResolver, "rootConstantResolver must not be null");
      Objects.requireNonNull(nominalTypeLookup, "nominalTypeLookup must not be null");
    }
  }

  private StvnDefsLowerer() {
    // Non-instantiable stateless pass
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
    return lowerDocumentUnit(doc, null, moduleCtx, diagnosticBag).toPublicResult();
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
    return lowerDocumentUnit(doc, docPath, moduleCtx, diagnosticBag).toPublicResult();
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
   * against its physical filename contract.
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
    Objects.requireNonNull(diagnosticBag, "diagnosticBag must not be null");
    if (docPath == null || docPath.isEmpty()) {
      return;
    }

    String normalized = docPath.replace('\\', '/');
    int lastSlash = normalized.lastIndexOf('/');
    String fileName = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;

    if (rejectLegacyExtension(fileName, diagnosticBag, 0, 1, 1, 0)) {
      return;
    }

    if (fileName.startsWith(".")) {
      diagnosticBag.addError(
          "Filename '" + fileName + "' lacks a visible non-empty stem segment before extension; bare dotfiles are prohibited",
          0, 1, 1, 0, null,
          DiagnosticBag.ERR_INVALID_FILENAME_STEM
      );
      return;
    }

    int lastDot = fileName.lastIndexOf('.');
    String ext = null;
    String[] dotParts = null;
    if (lastDot >= 0) {
      ext = fileName.substring(lastDot);
      String stemPart = fileName.substring(0, lastDot);
      dotParts = stemPart.split("\\.", -1);
      if (dotParts.length >= 3) {
        diagnosticBag.addError(
            "Invalid filename stem '" + fileName
                + "'; filename must contain exactly one or two dot-separated stem segments (<name>.<ext> or <name>.<domain>.<ext>)",
            0, 1, 1, 0, null,
            DiagnosticBag.ERR_INVALID_FILENAME_STEM
        );
        return;
      }
    }

    Optional<StvnDocumentKind> expectedKindOpt =
        ext != null ? StvnDocumentKind.fromExtension(ext) : Optional.empty();

    if (meta == null) {
      if (expectedKindOpt.isPresent()
          && (expectedKindOpt.get() == StvnDocumentKind.DEFS || ".stvn_b".equals(ext))) {
        diagnosticBag.addError(
            "Mandatory :meta block missing; document kind '" + expectedKindOpt.get().keyword()
                + "' is required for extension '" + ext + "'",
            0, 1, 1, 0, null,
            DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH
        );
      }
      return;
    }

    if (meta.domain().isPresent() && isReservedDomain(meta.domain().get())) {
      // Binary payloads decoded outside system authority check domain prefix
    }

    StvnDocumentKind declaredKind = meta.kind().orElse(null);
    if (declaredKind != null && expectedKindOpt.isPresent() && declaredKind != expectedKindOpt.get()) {
      diagnosticBag.addError(
          "Document kind '" + declaredKind.keyword() + "' does not match file extension '" + ext
              + "' (expected '" + expectedKindOpt.get().keyword() + "')",
          0, 1, 1, 0, null,
          DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH
      );
    }

    if (dotParts != null && dotParts.length >= 1) {
      String stemName = dotParts[0];
      if (meta.name().isPresent() && !meta.name().get().equals(stemName)) {
        diagnosticBag.addWarning(
            "Declared #name '" + meta.name().get() + "' does not match filename stem slot 0 '" + stemName + "'",
            0, 1, 1, 0,
            DiagnosticBag.WARN_DOCUMENT_NAME_MISMATCH
        );
      }
      if (meta.domain().isPresent()) {
        String declaredDomain = meta.domain().get();
        if (dotParts.length == 1) {
          diagnosticBag.addWarning(
              "Declared #domain '" + declaredDomain + "' is omitted from filename stem slot 1",
              0, 1, 1, 0,
              DiagnosticBag.WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME
          );
        } else if (!declaredDomain.equals(dotParts[1])) {
          diagnosticBag.addWarning(
              "Declared #domain '" + declaredDomain + "' does not match filename stem slot 1 '" + dotParts[1] + "'",
              0, 1, 1, 0,
              DiagnosticBag.WARN_DOCUMENT_NAME_MISMATCH
          );
        }
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
    final List<Path> activeCanonicalPaths = new ArrayList<>();
    final List<String> activeRawPaths = new ArrayList<>();
    final Map<Path, CompiledUnit> compiledModuleCache = new HashMap<>();

    ModuleCompilationContext(StvnParserConfig config, boolean bootstrapPreludeMode) {
      this.config = config;
      this.bootstrapPreludeMode = bootstrapPreludeMode;
    }

    boolean isSystemAuthority() {
      return bootstrapPreludeMode || config.systemMode();
    }
  }

  /**
   * Ledger of Tier-1 Definition facets explicitly authored anywhere along a nominal derivation chain
   * prior to {@code W003} tautology stripping. Used to enforce {@code E040} ({@code ERR_NON_OVERRIDABLE_FACET})
   * and cross-hop mutual exclusivity ({@code E008}).
   */
  record AncestorFacetLedger(
      boolean explicitUnsigned,
      boolean explicitExact,
      boolean explicitInvertible,
      Optional<BigInteger> explicitSize,
      Optional<BigInteger> explicitMinSize,
      Optional<BigInteger> explicitMaxSize,
      Optional<TemporalScale> explicitScale,
      Optional<DateTimeMode> explicitMode
  ) {
    static final AncestorFacetLedger EMPTY = new AncestorFacetLedger(
        false, false, false,
        Optional.empty(), Optional.empty(), Optional.empty(),
        Optional.empty(), Optional.empty()
    );

    AncestorFacetLedger mergeChild(AncestorFacetLedger child) {
      return new AncestorFacetLedger(
          this.explicitUnsigned || child.explicitUnsigned,
          this.explicitExact || child.explicitExact,
          this.explicitInvertible || child.explicitInvertible,
          child.explicitSize.isPresent() ? child.explicitSize : this.explicitSize,
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
      StvnPayloadLowerer.NominalSchemaResolver nominalTypeLookup
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
          sectionsEligible
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

  private static CompiledUnit lowerDocumentUnit(
      StvnParser.StvnDocumentContext doc,
      @Nullable String docPath,
      ModuleCompilationContext moduleCtx,
      DiagnosticBag diagnosticBag
  ) {
    IdentityOutcome identity = lowerAndValidateDocumentIdentity(doc, docPath, moduleCtx, diagnosticBag);

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
          fqni -> Optional.empty()
      );
    }

    boolean sectionsEligible = validateDocumentSectionEligibility(doc, docPath, identity, diagnosticBag);
    if (!sectionsEligible) {
      return new CompiledUnit(
          identity.meta(),
          identity.effectiveKind(),
          StvnDefsCatalog.EMPTY,
          Optional.empty(),
          body.defsEntry() != null,
          false,
          false,
          Map.of(),
          Map.of(),
          (tok, site) -> Optional.empty(),
          fqni -> Optional.empty()
      );
    }

    UnitEnvironment env = new UnitEnvironment(doc, docPath, identity, moduleCtx, diagnosticBag);
    env.collectAndResolveDefs();

    Optional<StvnSchema> rootType = Optional.empty();
    if (body.typeEntry() != null && body.typeEntry().schemaType() != null) {
      rootType = Optional.of(
          env.lowerAnonymousOrRootSchema(body.typeEntry().schemaType(), env.rootScope, new LinkedHashSet<>(), true).schema()
      );
    }

    StvnDefsCatalog catalog = env.buildCatalog();
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
        nominalLookup
    );
  }

  private static IdentityOutcome lowerAndValidateDocumentIdentity(
      StvnParser.StvnDocumentContext doc,
      @Nullable String docPath,
      ModuleCompilationContext moduleCtx,
      DiagnosticBag diagnosticBag
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

      if (rejectLegacyExtension(fileName, diagnosticBag, docStartIdx, docStopIdx, docLine, docCol)) {
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
    var metaEntry = body != null ? body.metaEntry() : null;
    Optional<StvnDocumentKind> expectedKindOpt =
        ext != null ? StvnDocumentKind.fromExtension(ext) : Optional.empty();

    if (metaEntry == null) {
      if (!fatalPathRejection && !moduleCtx.bootstrapPreludeMode && expectedKindOpt.isPresent()
          && (expectedKindOpt.get() == StvnDocumentKind.DEFS
              || expectedKindOpt.get() == StvnDocumentKind.DEFS_INCLUDE
              || expectedKindOpt.get() == StvnDocumentKind.BODY_INCLUDE
              || ".stvn_b".equals(ext))) {
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
      diagnosticBag.addError(
          "Metadata block ':meta {}' must not be empty",
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
        if (nf.stringLiteral() != null) {
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
        if (df.stringLiteral() != null) {
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

    if (!fatalPathRejection && !moduleCtx.bootstrapPreludeMode) {
      if (declaredKind != null && expectedKindOpt.isPresent() && declaredKind != expectedKindOpt.get()) {
        Span sp = Span.ofContext(kindNode != null ? kindNode : metaEntry);
        diagnosticBag.addError(
            "Document kind '" + declaredKind.keyword() + "' does not match file extension '" + ext
                + "' (expected '" + expectedKindOpt.get().keyword() + "')",
            sp.start(), sp.end(), sp.line(), sp.column(), null,
            DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH
        );
      }

      if (dotParts != null && dotParts.length >= 1) {
        String stemName = dotParts[0];
        if (declaredName != null && !declaredName.equals(stemName)) {
          Span sp = Span.ofContext(nameNode != null ? nameNode : metaEntry);
          diagnosticBag.addWarning(
              "Declared #name '" + declaredName + "' does not match filename stem slot 0 '" + stemName + "'",
              sp.start(), sp.end(), sp.line(), sp.column(),
              DiagnosticBag.WARN_DOCUMENT_NAME_MISMATCH
          );
        }
        if (declaredDomain != null) {
          Span sp = Span.ofContext(domainNode != null ? domainNode : metaEntry);
          if (dotParts.length == 1) {
            diagnosticBag.addWarning(
                "Declared #domain '" + declaredDomain + "' is omitted from filename stem slot 1",
                sp.start(), sp.end(), sp.line(), sp.column(),
                DiagnosticBag.WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME
            );
          } else if (!declaredDomain.equals(dotParts[1])) {
            diagnosticBag.addWarning(
                "Declared #domain '" + declaredDomain + "' does not match filename stem slot 1 '" + dotParts[1] + "'",
                sp.start(), sp.end(), sp.line(), sp.column(),
                DiagnosticBag.WARN_DOCUMENT_NAME_MISMATCH
            );
          }
        }
      }
    }

    Optional<StvnDocumentMeta> loweredMeta = Optional.of(new StvnDocumentMeta(
        Optional.ofNullable(declaredName),
        Optional.ofNullable(declaredDomain),
        Optional.ofNullable(declaredKind)
    ));
    Optional<StvnDocumentKind> effectiveKind = declaredKind != null
        ? Optional.of(declaredKind)
        : expectedKindOpt;

    return new IdentityOutcome(loweredMeta, effectiveKind, fileName, ext);
  }

  private static boolean validateDocumentSectionEligibility(
      StvnParser.StvnDocumentContext doc,
      @Nullable String docPath,
      IdentityOutcome identity,
      DiagnosticBag diagnosticBag
  ) {
    var body = doc.documentBody();
    if (body == null) {
      return true;
    }

    StvnDocumentKind effectiveKind = identity.effectiveKind().orElse(null);
    String ext = identity.fileExt();
    String fileName = identity.fileName();
    String targetDesc = fileName != null ? "'" + fileName + "'" : (ext != null ? ext : "<in-memory>");

    boolean isDefsSchema = effectiveKind == StvnDocumentKind.DEFS
        || effectiveKind == StvnDocumentKind.DEFS_INCLUDE
        || (ext != null && (ext.equals(".stvn_d") || ext.equals(".stvn_di") || ext.equals(".stvn_bd")));

    if (isDefsSchema) {
      boolean hasViolations = false;
      String kindLabel = effectiveKind != null ? effectiveKind.keyword() : StvnVocabulary.KIND_KW_DEFS;
      if (body.typeEntry() != null) {
        Span sp = Span.ofContext(body.typeEntry());
        diagnosticBag.addError(
            "Structural violation: Definitions schema (" + targetDesc + ") declaring #kind " + kindLabel
                + " cannot declare '" + StvnVocabulary.KEYWORD_TYPE + "' section [stvn-core:ERR_DOCUMENT_KIND_MISMATCH]",
            sp.start(), sp.end(), sp.line(), sp.column(), null,
            DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH
        );
        hasViolations = true;
      }
      if (body.bodyEntry() != null) {
        Span sp = Span.ofContext(body.bodyEntry());
        diagnosticBag.addError(
            "Structural violation: Definitions schema (" + targetDesc + ") declaring #kind " + kindLabel
                + " cannot declare '" + StvnVocabulary.KEYWORD_BODY + "' section [stvn-core:ERR_DOCUMENT_KIND_MISMATCH]",
            sp.start(), sp.end(), sp.line(), sp.column(), null,
            DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH
        );
        hasViolations = true;
      }
      return !hasViolations;
    }

    boolean isHermeticBody = (effectiveKind == StvnDocumentKind.BODY
        || (ext != null && (ext.equals(".stvn") || ext.equals(".stvn_b"))))
        && (ext == null || !ext.equals(".stvn_i"));

    if (isHermeticBody && docPath != null && !docPath.isEmpty()) {
      if (body.typeEntry() == null || body.bodyEntry() == null) {
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

  // ===========================================================================
  // PER-DOCUMENT UNIT ENVIRONMENT
  // ===========================================================================

  private static final class UnitEnvironment {
    private final StvnParser.@Nullable StvnDocumentContext doc;
    private final @Nullable String docPath;
    private final IdentityOutcome identity;
    private final ModuleCompilationContext moduleCtx;
    private final DiagnosticBag diagnosticBag;

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
        DiagnosticBag diagnosticBag
    ) {
      this.doc = doc;
      this.docPath = docPath;
      this.identity = identity;
      this.moduleCtx = moduleCtx;
      this.diagnosticBag = diagnosticBag;
    }

    static UnitEnvironment fromStandaloneCatalog(
        StvnDefsCatalog catalog,
        ModuleCompilationContext moduleCtx,
        DiagnosticBag diagnosticBag
    ) {
      IdentityOutcome emptyId = new IdentityOutcome(Optional.empty(), Optional.empty(), null, null);
      UnitEnvironment env = new UnitEnvironment(null, null, emptyId, moduleCtx, diagnosticBag);
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
            i.unsigned(), false, false,
            i.bitWidth().equals(StvnVocabulary.DEFAULT_INT_BIT_WIDTH) ? Optional.empty() : Optional.of(i.bitWidth()),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()
        );
        case StvnSchema.FloatSchema f -> new AncestorFacetLedger(
            false,
            f.representation() == FloatRepresentation.EXACT_DECIMAL,
            false,
            f.representation() == FloatRepresentation.IEEE_32 ? Optional.of(new BigInteger("32")) : Optional.empty(),
            Optional.empty(),
            f.representation() == FloatRepresentation.EXACT_DECIMAL
                && !f.exactMaxDigits().equals(StvnVocabulary.DEFAULT_MAX_SIZE)
                ? Optional.of(f.exactMaxDigits()) : Optional.empty(),
            Optional.empty(), Optional.empty()
        );
        case StvnSchema.StringSchema s -> new AncestorFacetLedger(
            false, false, false, Optional.empty(),
            s.minSize().signum() == 0 ? Optional.empty() : Optional.of(s.minSize()),
            s.maxSize().equals(StvnVocabulary.DEFAULT_MAX_SIZE) ? Optional.empty() : Optional.of(s.maxSize()),
            Optional.empty(), Optional.empty()
        );
        case StvnSchema.TimeEpochSchema te -> new AncestorFacetLedger(
            false, false, false, Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of(te.scale()), Optional.empty()
        );
        case StvnSchema.DateTimeSchema dt -> new AncestorFacetLedger(
            false, false, false, Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of(dt.mode())
        );
        case StvnSchema.SeqSchema sq -> new AncestorFacetLedger(
            false, false, false, Optional.empty(),
            sq.minSize().signum() == 0 ? Optional.empty() : Optional.of(sq.minSize()),
            sq.maxSize().equals(StvnVocabulary.DEFAULT_MAX_SIZE) ? Optional.empty() : Optional.of(sq.maxSize()),
            Optional.empty(), Optional.empty()
        );
        case StvnSchema.SetSchema st -> new AncestorFacetLedger(
            false, false, false, Optional.empty(),
            st.minSize().signum() == 0 ? Optional.empty() : Optional.of(st.minSize()),
            st.maxSize().equals(StvnVocabulary.DEFAULT_MAX_SIZE) ? Optional.empty() : Optional.of(st.maxSize()),
            Optional.empty(), Optional.empty()
        );
        case StvnSchema.MapSchema mp -> new AncestorFacetLedger(
            false, false, mp.invertible(), Optional.empty(),
            mp.minSize().signum() == 0 ? Optional.empty() : Optional.of(mp.minSize()),
            mp.maxSize().equals(StvnVocabulary.DEFAULT_MAX_SIZE) ? Optional.empty() : Optional.of(mp.maxSize()),
            Optional.empty(), Optional.empty()
        );
        default -> AncestorFacetLedger.EMPTY;
      };
    }

    void collectAndResolveDefs() {
      if (doc == null || doc.documentBody() == null || doc.documentBody().defsEntry() == null) {
        return;
      }
      var defsEntry = doc.documentBody().defsEntry();
      this.hasDefsSection = true;

      if (defsEntry.LBRACE() != null && defsEntry.RBRACE() != null
          && (defsEntry.defsElement() == null || defsEntry.defsElement().isEmpty())) {
        boolean isSchemaDoc = identity.effectiveKind().map(k -> k == StvnDocumentKind.DEFS || k == StvnDocumentKind.DEFS_INCLUDE).orElse(false)
            || (doc.documentBody().typeEntry() == null && doc.documentBody().bodyEntry() == null);
        int start = defsEntry.LBRACE().getSymbol().getStartIndex();
        int stop = defsEntry.RBRACE().getSymbol().getStopIndex() + 1;
        int line = defsEntry.LBRACE().getSymbol().getLine();
        int col = defsEntry.LBRACE().getSymbol().getCharPositionInLine();
        String ext = identity.fileExt() != null ? identity.fileExt() : ".stvn_d";
        String msg = isSchemaDoc
            ? "Definitions schema (" + ext + ") requires at least one type definition inside '"
                + StvnVocabulary.KEYWORD_DEFS + " { ... }'"
            : "Empty definition block '" + StvnVocabulary.KEYWORD_DEFS + " {}' is redundant; omit '"
                + StvnVocabulary.KEYWORD_DEFS + "' or add definitions";
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
      }
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
                "Scoped " + StvnVocabulary.KEYWORD_USE + " in hermetic document (.stvn / .stvn_d) cannot target external package: " + target,
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
        String msg = "Hermetic document or schema (.stvn / .stvn_d) cannot contain include statements: "
            + (docPath != null ? docPath : "<in-memory>");
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
        if (hermeticBlocked || hasIllegalOptionArg || element.stringLiteral() == null) {
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

        CompiledUnit importedUnit = moduleCtx.compiledModuleCache.get(canonicalPath);
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

          var lexer = new StvnLexer(CharStreams.fromString(content));
          lexer.removeErrorListeners();
          var parser = new StvnParser(new CommonTokenStream(lexer));
          parser.removeErrorListeners();
          var childListener = StvnErrorListener.accumulating(diagnosticBag);
          lexer.addErrorListener(childListener);
          parser.addErrorListener(childListener);
          parser.setErrorHandler(new StvnErrorStrategy());

          StvnParser.StvnDocumentContext importedDocCtx = parser.stvnDocument();

          moduleCtx.activeCanonicalPaths.add(canonicalPath);
          moduleCtx.activeRawPaths.add(pathVal);
          try {
            importedUnit = lowerDocumentUnit(importedDocCtx, resolvedPath.toString(), moduleCtx, diagnosticBag);
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

      for (var entry : importedUnit.exportedTypes().entrySet()) {
        String originalFqni = entry.getKey();
        ExportedTypeSymbol sym = entry.getValue();
        boolean isPackaged = originalFqni.contains("/");
        String stripped = sliceTerminal(originalFqni);

        if (aliasMap.containsKey(originalFqni) || (hasStrip && aliasMap.containsKey(stripped))) {
          String aliasRhs = aliasMap.containsKey(originalFqni)
              ? aliasMap.get(originalFqni)
              : aliasMap.get(stripped);
          ExportedTypeSymbol projected = isPackaged
              ? sym
              : new ExportedTypeSymbol(aliasRhs, rebrandSchema(sym.schema(), Optional.of(aliasRhs)), sym.ledger(), sym.originModuleKey());
          Map<String, List<ScopeClaim<ExportedTypeSymbol>>> dest =
              aliasRhs.contains("/") ? packagedTypeClaims : typeClaims;
          dest.computeIfAbsent(aliasRhs, k -> new ArrayList<>())
              .add(new ScopeClaim<>(aliasRhs, projected, sym.originModuleKey() + "::" + originalFqni, ClaimKind.RENAMED_IMPORT_RHS));
          // Note: originalFqni is intentionally evicted from this include projection per Appendix F Scenarios 2 & 3!
        } else if (isPackaged) {
          // Retain full FQNI in packagedImportPool for qualified :pkg/Name and :use [ :pkg ... ]
          packagedTypeClaims.computeIfAbsent(originalFqni, k -> new ArrayList<>())
              .add(new ScopeClaim<>(originalFqni, sym, sym.originModuleKey() + "::" + originalFqni, ClaimKind.RAW_IMPORT));
          if (hasStrip) {
            typeClaims.computeIfAbsent(stripped, k -> new ArrayList<>())
                .add(new ScopeClaim<>(stripped, sym, sym.originModuleKey() + "::" + originalFqni, ClaimKind.RAW_IMPORT));
          }
        } else {
          typeClaims.computeIfAbsent(originalFqni, k -> new ArrayList<>())
              .add(new ScopeClaim<>(originalFqni, sym, sym.originModuleKey() + "::" + originalFqni, ClaimKind.RAW_IMPORT));
        }
      }

      for (var entry : importedUnit.exportedConstants().entrySet()) {
        String originalFqni = entry.getKey();
        ExportedConstantSymbol sym = entry.getValue();
        boolean isPackaged = originalFqni.contains("/");
        String stripped = sliceTerminal(originalFqni);

        if (isPackaged) {
          packagedConstClaims.computeIfAbsent(originalFqni, k -> new ArrayList<>())
              .add(new ScopeClaim<>(originalFqni, sym, sym.originModuleKey() + "::" + originalFqni, ClaimKind.RAW_IMPORT));
          if (hasStrip) {
            constClaims.computeIfAbsent(stripped, k -> new ArrayList<>())
                .add(new ScopeClaim<>(stripped, sym, sym.originModuleKey() + "::" + originalFqni, ClaimKind.RAW_IMPORT));
          }
        } else {
          constClaims.computeIfAbsent(originalFqni, k -> new ArrayList<>())
              .add(new ScopeClaim<>(originalFqni, sym, sym.originModuleKey() + "::" + originalFqni, ClaimKind.RAW_IMPORT));
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
      );
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
            new StvnTypeResolver.CircularReferenceException(msg),
            DiagnosticBag.ERR_CIRCULAR_TYPE
        );
        return Optional.of(registerPoisonedConstant(fqni, DiagnosticBag.ERR_CIRCULAR_TYPE));
      }

      evaluatingConstants.add(fqni);
      try {
        ScopeFrame scope = decl.packageCtx() != null
            ? packageScopes.getOrDefault(decl.packageCtx(), rootScope)
            : rootScope;
        int errBefore = diagnosticBag.errorCount();
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
        if (diagnosticBag.errorCount() > errBefore || isRealPoisonedSchema(constSchema)) {
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
            new StvnTypeResolver.CircularReferenceException(msg),
            DiagnosticBag.ERR_CIRCULAR_TYPE
        );
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

      int errBefore = diagnosticBag.errorCount();
      LoweredSchemaBundle rawBundle = lowerSchemaNode(
          fqni,
          Optional.of(fqni),
          decl.ctx().schemaType(),
          scope,
          nextVisited,
          passedConstructor,
          true
      );
      if (diagnosticBag.errorCount() > errBefore || isRealPoisonedSchema(rawBundle.schema())) {
        registerPoisonedType(fqni, DiagnosticBag.ERR_MALFORMED_SCHEMA);
        return loweredTypes.get(fqni);
      }

      StvnSchema branded = rebrandSchema(rawBundle.schema(), Optional.of(fqni));
      LoweredSchemaBundle finalized = new LoweredSchemaBundle(branded, rawBundle.ledger());
      loweredTypes.put(fqni, finalized);
      return finalized;
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
        StvnSchema parentSchema = parentBundle.schema();
        if (isRealPoisonedSchema(parentSchema)) {
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
          if (diagnosticBag.errorCount() > errBefore) {
            return poisonedBundle(targetNominalAlias, DiagnosticBag.ERR_EMPTY_METADATA_BLOCK);
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
            errBefore
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
          TraitProfile defaultTraits = TraitProfile.ofDefault(elem.traits().equatable(), elem.traits().comparable());
          StvnSchema baseSeq = new StvnSchema.SeqSchema(
              targetNominalAlias, BigInteger.ZERO, StvnVocabulary.DEFAULT_MAX_SIZE, elem, defaultTraits
          );
          if (isRealPoisonedSchema(elem)) {
            validateFacetsOnlyIfPresent(ownerLabel, StvnVocabulary.TYPE_SEQ, baseSeq, null, AncestorFacetLedger.EMPTY, false, metaMap, schemaCtx, scope);
            return poisonedBundle(targetNominalAlias, DiagnosticBag.ERR_MALFORMED_SCHEMA);
          }
          return applyFacetsToBaseSchema(
              ownerLabel, targetNominalAlias, StvnVocabulary.TYPE_SEQ,
              baseSeq, null, AncestorFacetLedger.EMPTY, false, metaMap, schemaCtx, scope, errBefore
          );
        }
        if (coll.COLL_SET() != null) {
          var elemCtx = coll.schemaType(0);
          LoweredSchemaBundle elemBundle = lowerSchemaNode(
              ownerLabel, Optional.empty(), elemCtx, scope, visitedTypeStack, true, false
          );
          StvnSchema elem = elemBundle.schema();
          boolean childPoisoned = isRealPoisonedSchema(elem);
          if (!childPoisoned && !elem.traits().equatable()) {
            Span sp = Span.ofContext(elemCtx);
            diagnosticBag.addError(
                "Set elements require types to be #equatable #TRUE",
                sp.start(), sp.end(), sp.line(), sp.column(), null,
                DiagnosticBag.ERR_TRAIT_VIOLATION
            );
            childPoisoned = true;
          }
          TraitProfile defaultTraits = TraitProfile.ofDefault(elem.traits().equatable(), false);
          StvnSchema baseSet = new StvnSchema.SetSchema(
              targetNominalAlias, BigInteger.ZERO, StvnVocabulary.DEFAULT_MAX_SIZE, elem, defaultTraits
          );
          if (childPoisoned) {
            validateFacetsOnlyIfPresent(ownerLabel, StvnVocabulary.TYPE_SET, baseSet, null, AncestorFacetLedger.EMPTY, false, metaMap, schemaCtx, scope);
            return poisonedBundle(targetNominalAlias, DiagnosticBag.ERR_TRAIT_VIOLATION);
          }
          return applyFacetsToBaseSchema(
              ownerLabel, targetNominalAlias, StvnVocabulary.TYPE_SET,
              baseSet, null, AncestorFacetLedger.EMPTY, false, metaMap, schemaCtx, scope, errBefore
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
          boolean childPoisoned = isRealPoisonedSchema(keySchema) || isRealPoisonedSchema(valSchema);
          if (!isRealPoisonedSchema(keySchema) && !keySchema.traits().equatable()) {
            Span sp = Span.ofContext(keyCtx);
            diagnosticBag.addError(
                "Map keys require types to be #equatable #TRUE",
                sp.start(), sp.end(), sp.line(), sp.column(), null,
                DiagnosticBag.ERR_TRAIT_VIOLATION
            );
            childPoisoned = true;
          }
          if (localInvertible && !isRealPoisonedSchema(valSchema) && !valSchema.traits().equatable()) {
            Span sp = Span.ofContext(valCtx);
            diagnosticBag.addError(
                "Inverted map values require types to be #equatable #TRUE",
                sp.start(), sp.end(), sp.line(), sp.column(), null,
                DiagnosticBag.ERR_TRAIT_VIOLATION
            );
            childPoisoned = true;
          }

          boolean mapDefaultEq = localInvertible
              ? (keySchema.traits().equatable() && valSchema.traits().equatable())
              : keySchema.traits().equatable();
          TraitProfile defaultTraits = TraitProfile.ofDefault(mapDefaultEq, false);
          StvnSchema baseMap = new StvnSchema.MapSchema(
              targetNominalAlias, false,
              BigInteger.ZERO, StvnVocabulary.DEFAULT_MAX_SIZE, keySchema, valSchema, defaultTraits
          );
          if (childPoisoned) {
            validateFacetsOnlyIfPresent(ownerLabel, StvnVocabulary.TYPE_MAP, baseMap, null, AncestorFacetLedger.EMPTY, false, metaMap, schemaCtx, scope);
            return poisonedBundle(targetNominalAlias, DiagnosticBag.ERR_TRAIT_VIOLATION);
          }
          return applyFacetsToBaseSchema(
              ownerLabel, targetNominalAlias, StvnVocabulary.TYPE_MAP,
              baseMap, null, AncestorFacetLedger.EMPTY, false, metaMap, schemaCtx, scope, errBefore
          );
        }
      }

      if (ctor.productType() instanceof StvnParser.TupleTypeContext tupCtx) {
        List<StvnSchema> elems = new ArrayList<>();
        boolean anyChildPoisoned = false;
        boolean allEq = true;
        boolean allComp = true;
        for (var childCtx : tupCtx.schemaType()) {
          LoweredSchemaBundle cb = lowerSchemaNode(
              ownerLabel, Optional.empty(), childCtx, scope, visitedTypeStack, true, false
          );
          StvnSchema cs = cb.schema();
          elems.add(cs);
          if (isRealPoisonedSchema(cs)) {
            anyChildPoisoned = true;
          } else {
            allEq = allEq && cs.traits().equatable();
            allComp = allComp && cs.traits().comparable();
          }
        }
        StvnSchema baseTup = new StvnSchema.TupleSchema(
            targetNominalAlias, elems, TraitProfile.ofDefault(allEq, allComp)
        );
        if (anyChildPoisoned) {
          validateFacetsOnlyIfPresent(ownerLabel, StvnVocabulary.TYPE_TUPLE, baseTup, null, AncestorFacetLedger.EMPTY, false, metaMap, schemaCtx, scope);
          return poisonedBundle(targetNominalAlias, DiagnosticBag.ERR_MALFORMED_SCHEMA);
        }
        return applyFacetsToBaseSchema(
            ownerLabel, targetNominalAlias, StvnVocabulary.TYPE_TUPLE,
            baseTup, null, AncestorFacetLedger.EMPTY, false, metaMap, schemaCtx, scope, errBefore
        );
      }

      if (ctor.sumType() != null) {
        var sum = ctor.sumType();
        if (sum.KW_OPTION() != null) {
          LoweredSchemaBundle innerBundle = lowerSchemaNode(
              ownerLabel, Optional.empty(), sum.schemaType(0), scope, visitedTypeStack, true, false
          );
          StvnSchema inner = innerBundle.schema();
          StvnSchema baseOpt = new StvnSchema.OptionSchema(
              targetNominalAlias, inner,
              TraitProfile.ofDefault(inner.traits().equatable(), inner.traits().comparable())
          );
          if (isRealPoisonedSchema(inner)) {
            validateFacetsOnlyIfPresent(ownerLabel, StvnVocabulary.TYPE_OPTION, baseOpt, null, AncestorFacetLedger.EMPTY, false, metaMap, schemaCtx, scope);
            return poisonedBundle(targetNominalAlias, DiagnosticBag.ERR_MALFORMED_SCHEMA);
          }
          return applyFacetsToBaseSchema(
              ownerLabel, targetNominalAlias, StvnVocabulary.TYPE_OPTION,
              baseOpt, null, AncestorFacetLedger.EMPTY, false, metaMap, schemaCtx, scope, errBefore
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
          boolean eq = l.traits().equatable() && r.traits().equatable();
          boolean comp = l.traits().comparable() && r.traits().comparable();
          StvnSchema baseEither = new StvnSchema.EitherSchema(
              targetNominalAlias, l, r, TraitProfile.ofDefault(eq, comp)
          );
          if (isRealPoisonedSchema(l) || isRealPoisonedSchema(r)) {
            validateFacetsOnlyIfPresent(ownerLabel, StvnVocabulary.TYPE_EITHER, baseEither, null, AncestorFacetLedger.EMPTY, false, metaMap, schemaCtx, scope);
            return poisonedBundle(targetNominalAlias, DiagnosticBag.ERR_MALFORMED_SCHEMA);
          }
          return applyFacetsToBaseSchema(
              ownerLabel, targetNominalAlias, StvnVocabulary.TYPE_EITHER,
              baseEither, null, AncestorFacetLedger.EMPTY, false, metaMap, schemaCtx, scope, errBefore
          );
        }
        if (sum.KW_UNION() != null) {
          List<StvnSchema> branches = new ArrayList<>();
          boolean anyPoisoned = false;
          boolean allEq = true;
          boolean allComp = true;
          for (var bCtx : sum.schemaType()) {
            LoweredSchemaBundle bb = lowerSchemaNode(
                ownerLabel, Optional.empty(), bCtx, scope, visitedTypeStack, true, false
            );
            StvnSchema bs = bb.schema();
            branches.add(bs);
            if (isRealPoisonedSchema(bs)) {
              anyPoisoned = true;
            } else {
              allEq = allEq && bs.traits().equatable();
              allComp = allComp && bs.traits().comparable();
            }
          }
          if (branches.size() < 2) {
            Span sp = Span.ofContext(sum);
            diagnosticBag.addError(
                "Union schema requires at least 2 branch types, got " + branches.size(),
                sp.start(), sp.end(), sp.line(), sp.column(), null,
                DiagnosticBag.ERR_MALFORMED_SCHEMA
            );
            anyPoisoned = true;
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
          if (anyPoisoned) {
            validateFacetsOnlyIfPresent(ownerLabel, StvnVocabulary.TYPE_UNION, baseUnion, null, AncestorFacetLedger.EMPTY, false, metaMap, schemaCtx, scope);
            return poisonedBundle(targetNominalAlias, DiagnosticBag.ERR_MALFORMED_SCHEMA);
          }
          return applyFacetsToBaseSchema(
              ownerLabel, targetNominalAlias, StvnVocabulary.TYPE_UNION,
              baseUnion, null, AncestorFacetLedger.EMPTY, false, metaMap, schemaCtx, scope, errBefore
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
            return poisonedBundle(targetNominalAlias, DiagnosticBag.ERR_MALFORMED_SCHEMA);
          }
          StvnSchema baseEnum = new StvnSchema.EnumSchema(
              targetNominalAlias, List.copyOf(variants), TraitProfile.ofDefault(true, true)
          );
          return applyFacetsToBaseSchema(
              ownerLabel, targetNominalAlias, StvnVocabulary.TYPE_ENUM,
              baseEnum, null, AncestorFacetLedger.EMPTY, false, metaMap, schemaCtx, scope, errBefore
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
              "Temporal primitive '" + StvnVocabulary.TYPE_TIME_EPOCH
                  + "' requires a scale facet (#ns, #us, #ms, or #s)",
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_MISSING_TEMPORAL_FACET
          );
          return poisonedBundle(effectiveBrand, DiagnosticBag.ERR_MISSING_TEMPORAL_FACET);
        }
        if (!isNominalDerivation && baseKw.equals(StvnVocabulary.TYPE_DATE_TIME)) {
          Span sp = Span.ofContext(atomicOrSchemaPinCtx);
          diagnosticBag.addError(
              "Temporal primitive '" + StvnVocabulary.TYPE_DATE_TIME
                  + "' requires exactly one mode facet (#offset, #zoned, or #audited)",
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_MISSING_TEMPORAL_FACET
          );
          return poisonedBundle(effectiveBrand, DiagnosticBag.ERR_MISSING_TEMPORAL_FACET);
        }
        if (diagnosticBag.errorCount() > errBefore) {
          return poisonedBundle(effectiveBrand, DiagnosticBag.ERR_MALFORMED_SCHEMA);
        }
        return new LoweredSchemaBundle(rebrandSchema(baseSchema, effectiveBrand), parentLedger);
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
          && (parentLedger.explicitExact() || localHasExact);

      int lastTier = -1;
      int lastSubTier = -1;
      String lastFacetName = null;
      Set<String> seenFacetNames = new HashSet<>();

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
                + permitted.stream().map(f -> StvnVocabulary.SIGIL_VALUE + f).toList();
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
                "Temporal scale facets (#s, #ms, #us, #ns) are mutually exclusive",
                entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
                DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
            );
            continue;
          }
          firstScaleTag = facetName;
        }
        if (isTemporalModeName(facetName)) {
          if (firstModeTag != null) {
            diagnosticBag.addError(
                "Temporal mode facets (#offset, #zoned, #audited) are mutually exclusive",
                entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
                DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
            );
            continue;
          }
          firstModeTag = facetName;
        }
        if (baseKw.equals(StvnVocabulary.TYPE_FLOAT)
            && (("size".equals(facetName) && childExact) || ("exact".equals(facetName) && childSize != null))) {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): facet '#" + facetName + "' is mutually exclusive with '#"
                  + ("size".equals(facetName) ? "exact" : "size") + "'",
              entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
              DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
          );
          continue;
        }
        if (("minIncl".equals(facetName) || "minExcl".equals(facetName)) && minBoundCtx != null) {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): #minIncl and #minExcl are mutually exclusive",
              entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
              DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
          );
          continue;
        }
        if (("maxIncl".equals(facetName) || "maxExcl".equals(facetName)) && maxBoundCtx != null) {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): #maxIncl and #maxExcl are mutually exclusive",
              entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
              DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
          );
          continue;
        }
        if (("filterIncl".equals(facetName) || "filterExcl".equals(facetName))
            && (filterInclCtx != null || filterExclCtx != null)) {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): #filterIncl and #filterExcl are mutually exclusive",
              entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
              DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
          );
          continue;
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
        }
        lastTier = tier;
        lastSubTier = subTier;
        lastFacetName = facetName;

        // 6. Cross-hop Mutual Exclusivity & Tier-1 Non-Overridability (E040) against AncestorFacetLedger
        if (isNominalDerivation) {
          String ancName = parentName != null ? parentName : "ancestor";
          if ("exact".equals(facetName) && parentLedger.explicitSize().isPresent()) {
            diagnosticBag.addError(
                "Constraint violation (" + ownerLabel + "): facet '#exact' is mutually exclusive with ancestor storage size '#size "
                    + parentLedger.explicitSize().get() + "' from " + ancName,
                entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
                DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
            );
            continue;
          }
          if ("size".equals(facetName) && parentLedger.explicitExact()) {
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
                  "Constraint violation (" + ownerLabel + "): temporal scale facet '#" + facetName
                      + "' is mutually exclusive with ancestor scale '" + temporalScaleKeyword(pScale) + "' from " + ancName,
                  entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
                  DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
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
                  "Constraint violation (" + ownerLabel + "): temporal mode facet '#" + facetName
                      + "' is mutually exclusive with ancestor mode '" + dateTimeModeKeyword(pMode) + "' from " + ancName,
                  entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
                  DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
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
          if (("unsigned".equals(facetName) && parentLedger.explicitUnsigned())
              || ("exact".equals(facetName) && parentLedger.explicitExact())
              || ("invertible".equals(facetName) && parentLedger.explicitInvertible())
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
            BigInteger sz = resolveIntegerMetadataArg(ownerLabel, "#size", entry.metadataSize().metadataValue(), scope);
            if (sz != null) {
              if (isNominalDerivation && parentLedger.explicitSize().isPresent()) {
                String ancName = parentName != null ? parentName : "ancestor";
                BigInteger ancSize = parentLedger.explicitSize().get();
                if (baseKw.equals(StvnVocabulary.TYPE_FLOAT) && !sz.equals(ancSize)) {
                  diagnosticBag.addError(
                      "Constraint violation (" + ownerLabel + "): facet '#size " + sz
                          + "' is mutually exclusive with ancestor storage size '#size " + ancSize + "' from " + ancName,
                      entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
                      DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
                  );
                } else {
                  diagnosticBag.addError(
                      "Constraint violation (" + ownerLabel + "): non-overridable definition facet '#size' is already established by ancestor "
                          + ancName + " and cannot be re-specified",
                      entrySpan.start(), entrySpan.end(), entrySpan.line(), entrySpan.column(), null,
                      DiagnosticBag.ERR_NON_OVERRIDABLE_FACET
                  );
                }
              } else {
                childSize = sz;
                childSizeSpan = entrySpan;
              }
            }
          }
          case "minSize" -> {
            BigInteger ms = resolveIntegerMetadataArg(ownerLabel, "#minSize", entry.metadataSize().metadataValue(), scope);
            if (ms != null) {
              childMinSize = ms;
              childMinSizeSpan = entrySpan;
            }
          }
          case "maxSize" -> {
            BigInteger mx = resolveIntegerMetadataArg(ownerLabel, "#maxSize", entry.metadataSize().metadataValue(), scope);
            if (mx != null) {
              childMaxSize = mx;
              childMaxSizeSpan = entrySpan;
            }
          }
          case "equatable" -> {
            Boolean b = resolveBooleanMetadataArg(ownerLabel, "#equatable", entry.metadataBool().metadataValue(), scope);
            if (b != null) {
              childEquatable = b;
              childEquatableSpan = entrySpan;
            }
          }
          case "comparable" -> {
            Boolean b = resolveBooleanMetadataArg(ownerLabel, "#comparable", entry.metadataBool().metadataValue(), scope);
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
          childUnsigned,
          childExact,
          childInvertible,
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
              "Temporal primitive '" + StvnVocabulary.TYPE_TIME_EPOCH
                  + "' requires a scale facet (#ns, #us, #ms, or #s)",
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
              "Temporal primitive '" + StvnVocabulary.TYPE_DATE_TIME
                  + "' requires exactly one mode facet (#offset, #zoned, or #audited)",
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

      // Unordered sets and maps cannot be elevated to #comparable #TRUE
      if (finalComp && (baseKw.equals(StvnVocabulary.TYPE_SET) || baseKw.equals(StvnVocabulary.TYPE_MAP))) {
        Span sp = childComparableSpan != null ? childComparableSpan : Span.ofContext(metaMap);
        diagnosticBag.addError(
            "Trait violation (" + ownerLabel + "): unordered container '" + baseKw
                + "' cannot satisfy '#comparable #TRUE'",
            sp.start(), sp.end(), sp.line(), sp.column(), null,
            DiagnosticBag.ERR_TRAIT_VIOLATION
        );
      }
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

      boolean eqOverridden = childEquatable != null && childEquatable != defaultEq;
      boolean compOverridden = childComparable != null && childComparable != defaultComp;
      TraitProfile finalTraits = new TraitProfile(
          finalEq,
          finalComp,
          eqOverridden ? Optional.of(finalEq) : baseSchema.traits().explicitEquatableOverride(),
          compOverridden ? Optional.of(finalComp) : baseSchema.traits().explicitComparableOverride()
      );

      // Now specialize & validate domain constraints per concrete schema family + W003 tautology purging!
      StvnSchema loweredSchema = switch (baseSchema) {
        case StvnSchema.BooleanSchema ignored -> new StvnSchema.BooleanSchema(effectiveBrand, finalTraits);

        case StvnSchema.IntSchema intBase -> lowerIntFacets(
            ownerLabel, effectiveBrand, intBase, parentName, parentLedger, isNominalDerivation,
            childUnsigned, childSize, childSizeSpan, minBoundCtx, maxBoundCtx, finalTraits, scope
        );

        case StvnSchema.FloatSchema floatBase -> lowerFloatFacets(
            ownerLabel, effectiveBrand, floatBase, isNominalDerivation,
            childExact, childSize, childSizeSpan, childMaxSize, childMaxSizeSpan,
            minBoundCtx, minBoundName, maxBoundCtx, maxBoundName, finalTraits, scope
        );

        case StvnSchema.StringSchema strBase -> lowerStringFacets(
            ownerLabel, effectiveBrand, strBase, isNominalDerivation,
            childMinSize, childMinSizeSpan, childMaxSize, childMaxSizeSpan,
            minBoundCtx, minBoundName, maxBoundCtx, maxBoundName, regexCtx,
            childPreserveIndent, childPreserveIndentSpan, finalTraits, scope
        );

        case StvnSchema.TimeEpochSchema teBase -> lowerTimeEpochFacets(
            ownerLabel, effectiveBrand, teBase, isNominalDerivation,
            childScale, minBoundCtx, maxBoundCtx, finalTraits, scope
        );

        case StvnSchema.DateTimeSchema dtBase -> lowerDateTimeFacets(
            ownerLabel, effectiveBrand, dtBase, isNominalDerivation,
            childMode, minBoundCtx, maxBoundCtx, finalTraits, scope
        );

        case StvnSchema.EnumSchema enumBase -> lowerEnumFacets(
            ownerLabel, effectiveBrand, enumBase, enumBase, enumBase.rootVariants(), filterInclCtx, filterExclCtx, finalTraits
        );

        case StvnSchema.EnumSubsetSchema subsetBase -> lowerEnumFacets(
            ownerLabel, effectiveBrand, subsetBase, subsetBase.rootEnum(), subsetBase.activeVariants(), filterInclCtx, filterExclCtx, finalTraits
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
          if (isNominalDerivation && childInvertible && !mapBase.invertible() && !mapBase.valueSchema().traits().equatable()) {
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

      if (diagnosticBag.errorCount() > errBefore) {
        return poisonedBundle(effectiveBrand, DiagnosticBag.ERR_MALFORMED_SCHEMA);
      }
      return new LoweredSchemaBundle(loweredSchema, mergedLedger);
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
              null, DiagnosticBag.ERR_INVALID_METADATA_FACET
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
              null, DiagnosticBag.ERR_INVALID_METADATA_FACET
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
        BigInteger minVal = resolveIntegerMetadataArg(ownerLabel, "#minIncl", minBoundCtx.metadataValue(), scope);
        if (minVal != null) {
          if (!widthCarrier.acceptsWidth(minVal)) {
            diagnosticBag.addError(
                "Constraint violation (" + ownerLabel + "): #minIncl value " + minVal
                    + " is out of bounds for physical capacity of " + StvnVocabulary.TYPE_INT
                    + " (" + (effectiveUnsigned ? "unsigned " : "signed ") + effectiveBitWidth + "-bit)",
                bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(), null,
                DiagnosticBag.ERR_INTEGER_OVERFLOW
            );
          } else if (effectiveUnsigned && minVal.signum() == 0) {
            boolean ancestorEstablishesUnsigned = isNominalDerivation && parentLedger.explicitUnsigned() && !childUnsigned;
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
          } else if (effectiveMin.isPresent() && minVal.compareTo(effectiveMin.get()) < 0) {
            diagnosticBag.addError(
                "Constraint violation (" + ownerLabel + "): derived #minIncl " + minVal
                    + " cannot widen ancestor lower bound " + effectiveMin.get(),
                bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(), null,
                DiagnosticBag.ERR_INVERTED_RANGE
            );
          } else {
            if (effectiveMin.isPresent() && minVal.equals(effectiveMin.get())) {
              String ancName = parentName != null ? parentName : "ancestor";
              diagnosticBag.addWarning(
                  "Constraint violation (" + ownerLabel + "): facet '#minIncl " + minVal
                      + "' is redundant; ancestor '" + ancName + "' already establishes '#minIncl " + minVal + "'",
                  bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(),
                  DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
              );
            }
            effectiveMin = Optional.of(minVal);
          }
        }
      }

      if (maxBoundCtx != null) {
        Span bSpan = Span.ofContext(maxBoundCtx);
        BigInteger maxVal = resolveIntegerMetadataArg(ownerLabel, "#maxExcl", maxBoundCtx.metadataValue(), scope);
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
                    + " (" + (effectiveUnsigned ? "unsigned " : "signed ") + effectiveBitWidth + "-bit)",
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
                "Constraint violation (" + ownerLabel + "): discrete half-open interval [0, 0) defines an empty domain",
                bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(), null,
                DiagnosticBag.ERR_EMPTY_INTERVAL_DOMAIN
            );
          } else if (effectiveMax.isPresent() && maxVal.compareTo(effectiveMax.get()) > 0) {
            diagnosticBag.addError(
                "Constraint violation (" + ownerLabel + "): derived #maxExcl " + maxVal
                    + " cannot widen ancestor upper bound " + effectiveMax.get(),
                bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(), null,
                DiagnosticBag.ERR_INVERTED_RANGE
            );
          } else {
            if (effectiveMax.isPresent() && maxVal.equals(effectiveMax.get())) {
              String ancName = parentName != null ? parentName : "ancestor";
              diagnosticBag.addWarning(
                  "Constraint violation (" + ownerLabel + "): facet '#maxExcl " + maxVal
                      + "' is redundant; ancestor '" + ancName + "' already establishes '#maxExcl " + maxVal + "'",
                  bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(),
                  DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
              );
            }
            effectiveMax = Optional.of(maxVal);
          }
        }
      }

      if (effectiveMin.isPresent() && effectiveMax.isPresent()) {
        int cmp = effectiveMin.get().compareTo(effectiveMax.get());
        Span pin = Span.ofContext(maxBoundCtx != null ? maxBoundCtx : minBoundCtx);
        if (cmp == 0) {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): discrete half-open interval ["
                  + effectiveMin.get() + ", " + effectiveMax.get() + ") defines an empty domain",
              pin.start(), pin.end(), pin.line(), pin.column(), null,
              DiagnosticBag.ERR_EMPTY_INTERVAL_DOMAIN
          );
          return new StvnSchema.IntSchema(
              effectiveBrand, effectiveUnsigned, effectiveBitWidth, finalTraits, Optional.empty(), Optional.empty()
          );
        } else if (cmp > 0) {
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
              null, DiagnosticBag.ERR_INVALID_METADATA_FACET
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

      if (minBoundCtx != null && minBoundName != null) {
        Span bSpan = Span.ofContext(minBoundCtx);
        BigDecimal bd = resolveDecimalMetadataArg(ownerLabel, "#" + minBoundName, minBoundCtx.metadataValue(), rep, exactMaxDigits, scope);
        if (bd != null) {
          BoundKind kind = "minIncl".equals(minBoundName) ? BoundKind.INCLUSIVE : BoundKind.EXCLUSIVE;
          ContinuousBound<BigDecimal> cand = new ContinuousBound<>(bd, kind);
          if (lower.isPresent() && narrowsFloatLowerViolation(lower.get(), cand)) {
            diagnosticBag.addError(
                "Constraint violation (" + ownerLabel + "): derived lower bound " + bd
                    + " cannot widen ancestor lower bound " + lower.get().value(),
                bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(), null,
                DiagnosticBag.ERR_INVERTED_RANGE
            );
          } else {
            lower = Optional.of(cand);
          }
        }
      }

      if (maxBoundCtx != null && maxBoundName != null) {
        Span bSpan = Span.ofContext(maxBoundCtx);
        BigDecimal bd = resolveDecimalMetadataArg(ownerLabel, "#" + maxBoundName, maxBoundCtx.metadataValue(), rep, exactMaxDigits, scope);
        if (bd != null) {
          BoundKind kind = "maxIncl".equals(maxBoundName) ? BoundKind.INCLUSIVE : BoundKind.EXCLUSIVE;
          ContinuousBound<BigDecimal> cand = new ContinuousBound<>(bd, kind);
          if (upper.isPresent() && narrowsFloatUpperViolation(upper.get(), cand)) {
            diagnosticBag.addError(
                "Constraint violation (" + ownerLabel + "): derived upper bound " + bd
                    + " cannot widen ancestor upper bound " + upper.get().value(),
                bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(), null,
                DiagnosticBag.ERR_INVERTED_RANGE
            );
          } else {
            upper = Optional.of(cand);
          }
        }
      }

      if (lower.isPresent() && upper.isPresent()) {
        int cmp = lower.get().value().compareTo(upper.get().value());
        Span pin = Span.ofContext(maxBoundCtx != null ? maxBoundCtx : minBoundCtx);
        if (cmp > 0) {
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

      return new StvnSchema.FloatSchema(effectiveBrand, rep, exactMaxDigits, finalTraits, lower, upper);
    }

    private static boolean narrowsFloatLowerViolation(ContinuousBound<BigDecimal> anc, ContinuousBound<BigDecimal> child) {
      int cmp = child.value().compareTo(anc.value());
      if (cmp < 0) {
        return true;
      }
      return cmp == 0 && anc.kind() == BoundKind.EXCLUSIVE && child.kind() == BoundKind.INCLUSIVE;
    }

    private static boolean narrowsFloatUpperViolation(ContinuousBound<BigDecimal> anc, ContinuousBound<BigDecimal> child) {
      int cmp = child.value().compareTo(anc.value());
      if (cmp > 0) {
        return true;
      }
      return cmp == 0 && anc.kind() == BoundKind.EXCLUSIVE && child.kind() == BoundKind.INCLUSIVE;
    }

    private StvnSchema lowerStringFacets(
        String ownerLabel,
        Optional<String> effectiveBrand,
        StvnSchema.StringSchema strBase,
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

      if (minBoundCtx != null && minBoundName != null) {
        Span bSpan = Span.ofContext(minBoundCtx);
        String sVal = resolveStringMetadataArg(ownerLabel, "#" + minBoundName, minBoundCtx.metadataValue(), scope);
        if (sVal != null) {
          BoundKind kind = "minIncl".equals(minBoundName) ? BoundKind.INCLUSIVE : BoundKind.EXCLUSIVE;
          ContinuousBound<String> cand = new ContinuousBound<>(sVal, kind);
          if (lowerBound.isPresent() && narrowsStringLowerViolation(lowerBound.get(), cand)) {
            diagnosticBag.addError(
                "Constraint violation (" + ownerLabel + "): derived string #" + minBoundName + " '" + sVal
                    + "' cannot widen ancestor lower bound '" + lowerBound.get().value() + "'",
                bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(), null,
                DiagnosticBag.ERR_INVERTED_RANGE
            );
          } else {
            lowerBound = Optional.of(cand);
          }
        }
      }

      if (maxBoundCtx != null && maxBoundName != null) {
        Span bSpan = Span.ofContext(maxBoundCtx);
        String sVal = resolveStringMetadataArg(ownerLabel, "#" + maxBoundName, maxBoundCtx.metadataValue(), scope);
        if (sVal != null) {
          BoundKind kind = "maxIncl".equals(maxBoundName) ? BoundKind.INCLUSIVE : BoundKind.EXCLUSIVE;
          ContinuousBound<String> cand = new ContinuousBound<>(sVal, kind);
          if (upperBound.isPresent() && narrowsStringUpperViolation(upperBound.get(), cand)) {
            diagnosticBag.addError(
                "Constraint violation (" + ownerLabel + "): derived string #" + maxBoundName + " '" + sVal
                    + "' cannot widen ancestor upper bound '" + upperBound.get().value() + "'",
                bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(), null,
                DiagnosticBag.ERR_INVERTED_RANGE
            );
          } else {
            upperBound = Optional.of(cand);
          }
        }
      }

      if (lowerBound.isPresent() && upperBound.isPresent()) {
        int cmp = compareUtf8Lexicographical(lowerBound.get().value(), upperBound.get().value());
        Span pin = Span.ofContext(maxBoundCtx != null ? maxBoundCtx : minBoundCtx);
        if (cmp > 0) {
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

      Optional<String> regexPattern = strBase.rawRegexPattern();
      Optional<Pattern> compiledRegex = strBase.compiledRegex();
      if (regexCtx != null) {
        Span rSpan = Span.ofContext(regexCtx);
        String patStr = resolveStringMetadataArg(ownerLabel, "#regex", regexCtx.metadataValue(), scope);
        if (patStr != null) {
          try {
            Pattern compiled = Pattern.compile(patStr);
            regexPattern = Optional.of(patStr);
            compiledRegex = Optional.of(compiled);
          } catch (PatternSyntaxException pse) {
            diagnosticBag.addError(
                "Constraint violation (" + ownerLabel + "): Invalid regex pattern: " + patStr,
                rSpan.start(), rSpan.end(), rSpan.line(), rSpan.column(), pse,
                DiagnosticBag.ERR_INVALID_REGEX
            );
          }
        }
      }

      boolean effectivePreserveIndent = strBase.preserveIndent();
      if (childPreserveIndent != null && childPreserveIndentSpan != null) {
        if (!childPreserveIndent && !strBase.preserveIndent()) {
          diagnosticBag.addWarning(
              "Constraint violation (" + ownerLabel
                  + "): facet '#preserveIndent #FALSE' is redundant; fenced strings trim common leading indentation by default",
              childPreserveIndentSpan.start(), childPreserveIndentSpan.end(),
              childPreserveIndentSpan.line(), childPreserveIndentSpan.column(),
              DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
          );
        } else if (childPreserveIndent && strBase.preserveIndent() && isNominalDerivation) {
          diagnosticBag.addWarning(
              "Constraint violation (" + ownerLabel
                  + "): facet '#preserveIndent #TRUE' is redundant; ancestor already establishes '#preserveIndent #TRUE'",
              childPreserveIndentSpan.start(), childPreserveIndentSpan.end(),
              childPreserveIndentSpan.line(), childPreserveIndentSpan.column(),
              DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
          );
        }
        effectivePreserveIndent = childPreserveIndent;
      }

      return new StvnSchema.StringSchema(
          effectiveBrand, cp.min(), cp.max(), finalTraits, lowerBound, upperBound,
          effectivePreserveIndent, compiledRegex, regexPattern
      );
    }

    private static boolean narrowsStringLowerViolation(ContinuousBound<String> anc, ContinuousBound<String> child) {
      int cmp = compareUtf8Lexicographical(child.value(), anc.value());
      if (cmp < 0) {
        return true;
      }
      return cmp == 0 && anc.kind() == BoundKind.EXCLUSIVE && child.kind() == BoundKind.INCLUSIVE;
    }

    private static boolean narrowsStringUpperViolation(ContinuousBound<String> anc, ContinuousBound<String> child) {
      int cmp = compareUtf8Lexicographical(child.value(), anc.value());
      if (cmp > 0) {
        return true;
      }
      return cmp == 0 && anc.kind() == BoundKind.EXCLUSIVE && child.kind() == BoundKind.INCLUSIVE;
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

      if (minBoundCtx != null) {
        Span bSpan = Span.ofContext(minBoundCtx);
        BigInteger v = resolveIntegerMetadataArg(ownerLabel, "#minIncl", minBoundCtx.metadataValue(), scope);
        if (v != null) {
          if (!widthCarrier.acceptsWidth(v)) {
            diagnosticBag.addError(
                "Constraint violation (" + ownerLabel + "): #minIncl value " + v
                    + " is out of bounds for physical capacity of " + StvnVocabulary.TYPE_TIME_EPOCH
                    + " (signed " + bitWidth + "-bit)",
                bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(), null,
                DiagnosticBag.ERR_INTEGER_OVERFLOW
            );
          } else if (minIncl.isPresent() && v.compareTo(minIncl.get()) < 0) {
            diagnosticBag.addError(
                "Constraint violation (" + ownerLabel + "): derived #minIncl " + v
                    + " cannot widen ancestor lower bound " + minIncl.get(),
                bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(), null,
                DiagnosticBag.ERR_INVERTED_RANGE
            );
          } else {
            minIncl = Optional.of(v);
          }
        }
      }
      if (maxBoundCtx != null) {
        Span bSpan = Span.ofContext(maxBoundCtx);
        BigInteger v = resolveIntegerMetadataArg(ownerLabel, "#maxExcl", maxBoundCtx.metadataValue(), scope);
        if (v != null) {
          if (!widthCarrier.acceptsWidth(v)) {
            diagnosticBag.addError(
                "Constraint violation (" + ownerLabel + "): #maxExcl value " + v
                    + " is out of bounds for physical capacity of " + StvnVocabulary.TYPE_TIME_EPOCH
                    + " (signed " + bitWidth + "-bit)",
                bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(), null,
                DiagnosticBag.ERR_INTEGER_OVERFLOW
            );
          } else if (maxExcl.isPresent() && v.compareTo(maxExcl.get()) > 0) {
            diagnosticBag.addError(
                "Constraint violation (" + ownerLabel + "): derived #maxExcl " + v
                    + " cannot widen ancestor upper bound " + maxExcl.get(),
                bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(), null,
                DiagnosticBag.ERR_INVERTED_RANGE
            );
          } else {
            maxExcl = Optional.of(v);
          }
        }
      }

      if (minIncl.isPresent() && maxExcl.isPresent()) {
        int cmp = minIncl.get().compareTo(maxExcl.get());
        Span pin = Span.ofContext(maxBoundCtx != null ? maxBoundCtx : minBoundCtx);
        if (cmp == 0) {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): discrete half-open interval ["
                  + minIncl.get() + ", " + maxExcl.get() + ") defines an empty domain",
              pin.start(), pin.end(), pin.line(), pin.column(), null,
              DiagnosticBag.ERR_EMPTY_INTERVAL_DOMAIN
          );
          minIncl = Optional.empty();
          maxExcl = Optional.empty();
        } else if (cmp > 0) {
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

      return new StvnSchema.TimeEpochSchema(effectiveBrand, scale, finalTraits, minIncl, maxExcl);
    }

    private StvnSchema lowerDateTimeFacets(
        String ownerLabel,
        Optional<String> effectiveBrand,
        StvnSchema.DateTimeSchema dtBase,
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

      if (minBoundCtx != null) {
        Span bSpan = Span.ofContext(minBoundCtx);
        String rawStr = resolveDateTimeBoundStringArg(ownerLabel, "#minIncl", minBoundCtx.metadataValue(), scope);
        if (rawStr != null) {
          BigInteger epochNanos = parseDateTimeBoundEpochNanos(ownerLabel, "#minIncl", rawStr, bSpan);
          if (epochNanos != null) {
            if (minNanos.isPresent() && epochNanos.compareTo(minNanos.get()) < 0) {
              diagnosticBag.addError(
                  "Constraint violation (" + ownerLabel + "): derived datetime #minIncl '" + rawStr
                      + "' cannot widen ancestor lower bound",
                  bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(), null,
                  DiagnosticBag.ERR_INVERTED_RANGE
              );
            } else {
              minNanos = Optional.of(epochNanos);
              minRaw = Optional.of(rawStr);
            }
          }
        }
      }

      if (maxBoundCtx != null) {
        Span bSpan = Span.ofContext(maxBoundCtx);
        String rawStr = resolveDateTimeBoundStringArg(ownerLabel, "#maxExcl", maxBoundCtx.metadataValue(), scope);
        if (rawStr != null) {
          BigInteger epochNanos = parseDateTimeBoundEpochNanos(ownerLabel, "#maxExcl", rawStr, bSpan);
          if (epochNanos != null) {
            if (maxNanos.isPresent() && epochNanos.compareTo(maxNanos.get()) > 0) {
              diagnosticBag.addError(
                  "Constraint violation (" + ownerLabel + "): derived datetime #maxExcl '" + rawStr
                      + "' cannot widen ancestor upper bound",
                  bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(), null,
                  DiagnosticBag.ERR_INVERTED_RANGE
              );
            } else {
              maxNanos = Optional.of(epochNanos);
              maxRaw = Optional.of(rawStr);
            }
          }
        }
      }

      if (minNanos.isPresent() && maxNanos.isPresent()) {
        int cmp = minNanos.get().compareTo(maxNanos.get());
        Span pin = Span.ofContext(maxBoundCtx != null ? maxBoundCtx : minBoundCtx);
        if (cmp == 0) {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): datetime half-open interval defines an empty domain",
              pin.start(), pin.end(), pin.line(), pin.column(), null,
              DiagnosticBag.ERR_EMPTY_INTERVAL_DOMAIN
          );
          minRaw = Optional.empty();
          maxRaw = Optional.empty();
        } else if (cmp > 0) {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): datetime #minIncl exceeds #maxExcl",
              pin.start(), pin.end(), pin.line(), pin.column(), null,
              DiagnosticBag.ERR_INVERTED_RANGE
          );
          minRaw = Optional.empty();
          maxRaw = Optional.empty();
        }
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
          java.time.ZoneId zoneId = java.time.ZoneId.of(zoneStr);
          if (hasExplicitOffsetSuffix(prefix)) {
            inst = java.time.OffsetDateTime.parse(prefix).toInstant();
          } else {
            inst = java.time.LocalDateTime.parse(prefix).atZone(zoneId).toInstant();
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
          java.time.ZoneId zoneId = java.time.ZoneId.of(zoneStr);
          if (hasExplicitOffsetSuffix(prefix)) {
            java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(prefix);
            inst = odt.toInstant();
            java.time.ZoneOffset expected = zoneId.getRules().getOffset(inst);
            if (!odt.getOffset().equals(expected)) {
              diagnosticBag.addError(
                  "Constraint violation (" + ownerLabel + "): contradictory offset in audited datetime bound "
                      + boundFacet + ": " + decodedText,
                  bSpan.start(), bSpan.end(), bSpan.line(), bSpan.column(), null,
                  DiagnosticBag.ERR_TEMPORAL_AUDITED_MISMATCH
              );
              return null;
            }
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

    private StvnSchema lowerEnumFacets(
        String ownerLabel,
        Optional<String> effectiveBrand,
        StvnSchema parentEnum,
        StvnSchema.EnumSchema rootEnum,
        List<String> parentActiveVariants,
        StvnParser.@Nullable MetadataFilterContext filterInclCtx,
        StvnParser.@Nullable MetadataFilterContext filterExclCtx,
        TraitProfile finalTraits
    ) {
      if (filterInclCtx == null && filterExclCtx == null) {
        if (parentEnum instanceof StvnSchema.EnumSubsetSchema subset) {
          return new StvnSchema.EnumSubsetSchema(
              effectiveBrand, subset.parentEnum(), subset.rootEnum(), subset.activeVariants(),
              subset.inclusiveFilter(), subset.declaredFilterList(), finalTraits
          );
        }
        return new StvnSchema.EnumSchema(effectiveBrand, rootEnum.rootVariants(), finalTraits);
      }

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
        return new StvnSchema.EnumSchema(effectiveBrand, rootEnum.rootVariants(), finalTraits);
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
              DiagnosticBag.ERR_DUPLICATE_DEF
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
        return new StvnSchema.EnumSchema(effectiveBrand, rootEnum.rootVariants(), finalTraits);
      }

      return new StvnSchema.EnumSubsetSchema(
          effectiveBrand, parentEnum, rootEnum, childActive, isIncl, declaredFilterList, finalTraits
      );
    }

    // =========================================================================
    // METADATA ARGUMENT EVALUATION & W003 TRAIT WARNING HELPERS
    // =========================================================================

    private @Nullable BigInteger resolveIntegerMetadataArg(
        String ownerLabel,
        String facetLabel,
        StvnParser.@Nullable MetadataValueContext mv,
        ScopeFrame scope
    ) {
      if (mv == null) {
        return null;
      }
      Span sp = Span.ofContext(mv);
      if (mv.integerLiteral() != null) {
        String raw = mv.integerLiteral().getText();
        if ("-0".equals(raw)) {
          diagnosticBag.addError(
              "Negative zero '-0' is prohibited on integer facet '" + facetLabel + "'",
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_INVALID_NUMERIC_LITERAL
          );
          return null;
        }
        return new BigInteger(raw);
      }
      if (mv.valueKeyword() != null) {
        String kw = mv.valueKeyword().getText();
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
      if (mv == null) {
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
      } else if (mv.valueKeyword() != null) {
        String kw = mv.valueKeyword().getText();
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
                  + "' overflows 32-bit IEEE-754 finite range",
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
                  + "' overflows 64-bit IEEE-754 finite range",
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
      if (mv == null) {
        return null;
      }
      Span sp = Span.ofContext(mv);
      if (mv.booleanLiteral() != null) {
        String txt = mv.booleanLiteral().getText();
        return StvnVocabulary.VAL_TRUE.equals(txt) || StvnVocabulary.VAL_TRUE_SHORT.equals(txt);
      }
      if (mv.valueKeyword() != null) {
        String kw = mv.valueKeyword().getText();
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
      if (traitCtx.booleanLiteral() != null) {
        String txt = traitCtx.booleanLiteral().getText();
        return StvnVocabulary.VAL_TRUE.equals(txt) || StvnVocabulary.VAL_TRUE_SHORT.equals(txt);
      }
      if (traitCtx.valueKeyword() != null) {
        Span sp = Span.ofContext(traitCtx.valueKeyword());
        String kw = traitCtx.valueKeyword().getText();
        if (isBuiltinNonConstantValueKeyword(kw)) {
          diagnosticBag.addError(
              "Constraint violation (" + ownerLabel + "): facet '#preserveIndent' requires a boolean argument, got '" + kw + "'",
              sp.start(), sp.end(), sp.line(), sp.column(), null,
              DiagnosticBag.ERR_INCOMPATIBLE_TYPE
          );
          return null;
        }
        Optional<StvnDefsCatalog.StvnConstant> cOpt = resolveConstantInScope(kw, scope);
        if (cOpt.isEmpty()) {
          diagnosticBag.addError(
              "Undefined constant '" + kw + "' referenced in metadata facet '#preserveIndent'",
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
                + "' in facet '#preserveIndent' must resolve to " + StvnVocabulary.TYPE_BOOLEAN,
            sp.start(), sp.end(), sp.line(), sp.column(), null,
            DiagnosticBag.ERR_INCOMPATIBLE_TYPE
        );
      }
      return null;
    }

    private @Nullable String resolveStringMetadataArg(
        String ownerLabel,
        String facetLabel,
        StvnParser.@Nullable MetadataValueContext mv,
        ScopeFrame scope
    ) {
      if (mv == null) {
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
      if (mv.valueKeyword() != null) {
        String kw = mv.valueKeyword().getText();
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
      if (mv == null) {
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
      if (mv.valueKeyword() != null) {
        String kw = mv.valueKeyword().getText();
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
      String message;
      if (ancestorName != null) {
        message = "Constraint violation (" + ownerLabel + "): facet '#" + traitName + " " + boolLit
            + "' is redundant; ancestor '" + ancestorName + "' already resolves to " + traitName + " " + defBoolLit;
      } else if (targetBaseType.equals(StvnVocabulary.TYPE_FLOAT) && !isExactFloat && "equatable".equals(traitName) && !declaredVal) {
        message = "Constraint violation (" + ownerLabel + "): facet '#equatable #FALSE' is redundant; continuous '"
            + StvnVocabulary.TYPE_FLOAT + "' is non-equatable by default";
      } else if (targetBaseType.equals(StvnVocabulary.TYPE_FLOAT) && !isExactFloat && "comparable".equals(traitName) && declaredVal) {
        message = "Constraint violation (" + ownerLabel + "): facet '#comparable #TRUE' is redundant; continuous '"
            + StvnVocabulary.TYPE_FLOAT + "' is intrinsically comparable";
      } else if (targetBaseType.equals(StvnVocabulary.TYPE_FLOAT) && isExactFloat && "equatable".equals(traitName) && declaredVal) {
        message = "Constraint violation (" + ownerLabel
            + "): facet '#equatable #TRUE' is redundant; exact decimal float is intrinsically equatable";
      } else if ((targetBaseType.equals(StvnVocabulary.TYPE_BOOLEAN)
          || targetBaseType.equals(StvnVocabulary.TYPE_INT)
          || targetBaseType.equals(StvnVocabulary.TYPE_STRING)
          || targetBaseType.equals(StvnVocabulary.TYPE_TIME_EPOCH)
          || targetBaseType.equals(StvnVocabulary.TYPE_DATE_TIME)) && declaredVal) {
        message = "Constraint violation (" + ownerLabel + "): facet '#" + traitName + " " + boolLit
            + "' is redundant; scalar primitive '" + targetBaseType + "' is intrinsically " + traitName;
      } else {
        message = "Constraint violation (" + ownerLabel + "): facet '#" + traitName + " " + boolLit
            + "' is redundant; '" + targetBaseType + "' already resolves to " + traitName + " " + defBoolLit;
      }
      diagnosticBag.addWarning(
          message, span.start(), span.end(), span.line(), span.column(),
          DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
      );
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
}
