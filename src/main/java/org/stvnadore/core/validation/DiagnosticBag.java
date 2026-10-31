package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Accumulator bag for recording semantic and syntactic diagnostics during STVN 2.0.0 compilation.
 * <p>
 * Enforces memory safety by bounding total accumulated diagnostics to a configurable limit
 * and populates dual diagnostic codes ({@code canonicalCode} {@code E001}–{@code E040} /
 * {@code W001}–{@code W003} alongside {@code symbolicId} {@code ERR_*} / {@code WARN_*}).
 *
 * @since 1.3.0
 */
@NullMarked
public final class DiagnosticBag {

  /** Error code emitted when a regular expression pattern is syntactically invalid. */
  public static final String ERR_INVALID_REGEX = "INVALID_REGEX_PATTERN";
  /** Error code emitted when a string payload fails to match a compiled {@code #regex} pattern (E013). */
  public static final String ERR_REGEX_MISMATCH = "REGEX_MISMATCH";
  /** Error code emitted when a numeric range has lower bound greater than upper bound. */
  public static final String ERR_INVERTED_RANGE = "INVALID_NUMERIC_RANGE";
  /** Error code emitted when collection element count or string length exceeds declared capacity bounds. */
  public static final String ERR_CAPACITY_OVERFLOW = "CAPACITY_OVERFLOW";
  /** Error code emitted when a floating-point literal exceeds IEEE-754 capacity boundaries (code E010). */
  public static final String ERR_FLOAT_OVERFLOW = ERR_CAPACITY_OVERFLOW;
  /** Error code emitted when continuous {@code :Float} {@code #size} is neither 32 nor 64 (E010). */
  public static final String ERR_FLOAT_INVALID_SIZE = "FLOAT_INVALID_SIZE";
  /** Error code emitted when metadata trait values are incompatible with the target type. */
  public static final String ERR_INCOMPATIBLE_TYPE = "INCOMPATIBLE_METADATA_TYPE";
  /** Error code emitted when a referenced metadata facet constant identifier has not been defined. */
  public static final String ERR_UNDEFINED_CONSTANT = "UNDEFINED_CONSTANT";
  /** Alias constant aligned with normative diagnostic naming. */
  public static final String ERR_INCOMPATIBLE_METADATA_TYPE = ERR_INCOMPATIBLE_TYPE;
  /** Error code emitted when mutually exclusive boundary constraints are simultaneously declared. */
  public static final String ERR_MUTUALLY_EXCLUSIVE = "MUTUALLY_EXCLUSIVE_BOUNDS";
  /** Error code emitted when recursive type aliases form a circular self-reference without disjunction. */
  public static final String ERR_CIRCULAR_TYPE = "CIRCULAR_TYPE_DEFINITION";
  /** Error code emitted when duplicate type identifiers are declared in the same scope. */
  public static final String ERR_DUPLICATE_DEF = "DUPLICATE_TYPE_DEFINITION";
  /** Error code emitted when a declared module include file cannot be located or read. */
  public static final String ERR_MODULE_IMPORT = "MODULE_IMPORT_FAILED";
  /** Error code emitted when a module path is imported more than once in the same {@code :defs} block (E005). */
  public static final String ERR_DUPLICATE_MODULE_IMPORT = "DUPLICATE_MODULE_IMPORT";
  /** Error code emitted when cyclic module include dependencies are detected. */
  @Deprecated(forRemoval = true, since = "2.0.0-SNAPSHOT")
  public static final String ERR_CYCLIC_MODULE = "CYCLIC_MODULE_INCLUDE";
  /** Error code emitted when namespace prefix collisions occur across included modules. */
  public static final String ERR_NAMESPACE_COLLISION = "NAMESPACE_COLLISION";
  /** Error code emitted when a referenced type identifier has not been defined. */
  public static final String ERR_UNDEFINED_TYPE = "UNDEFINED_TYPE";
  /** Error code emitted when an unqualified type reference is not found in document scope or root namespace. */
  public static final String ERR_UNKNOWN_TYPE = ERR_UNDEFINED_TYPE;
  /** Error code emitted when a reserved scalar or structural type keyword is declared on the LHS of a type definition. */
  public static final String ERR_RESERVED_KEYWORD_ON_LHS = "ERR_RESERVED_KEYWORD_ON_LHS";
  /** Error code emitted when an integer literal exceeds the capacity bounds of its declared bit-width type. */
  public static final String ERR_INTEGER_OVERFLOW = "ERR_INTEGER_OVERFLOW";
  /** Error code emitted when an include statement is declared inside a hermetic payload document (.stvn, .stvn_b) or hermetic definitions schema (.stvn_d, .stvn_bd). */
  public static final String ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT = "ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT";
  /** Error code emitted when a package enclosure is illegally nested inside another package enclosure. */
  public static final String ERR_NESTED_PACKAGE_PROHIBITED = "ERR_NESTED_PACKAGE_PROHIBITED";
  /** Error code emitted when a scoped import :use in a hermetic document targets an external non-prelude package (SPEC § 3.9 Invariant 7, E035). */
  public static final String ERR_UNRESOLVED_PACKAGE_IN_HERMETIC_DOCUMENT = "ERR_UNRESOLVED_PACKAGE_IN_HERMETIC_DOCUMENT";
  /** Error code emitted when a :use target specification contains trailing slash characters. */
  public static final String ERR_TRAILING_SLASH_PROHIBITED = "ERR_TRAILING_SLASH_PROHIBITED";
  /** Error code emitted when algebraic sum type branches share duplicate tag identifiers. */
  @Deprecated(forRemoval = true, since = "2.0.0-SNAPSHOT")
  public static final String ERR_SUM_TYPE_COLLISION = "SUM_TYPE_TAG_COLLISION";
  /** Error code emitted when implicit sum type payload inference is ambiguous due to duplicate nominal types or intersecting domains. */
  public static final String ERR_AMBIGUOUS_SUM_INFERENCE = "ERR_AMBIGUOUS_SUM_INFERENCE";
  /** Error code emitted when structural trait constraints are violated. */
  public static final String ERR_TRAIT_VIOLATION = "TRAIT_VIOLATION";
  /** Error code emitted when a schema definition syntax or structure is malformed. */
  public static final String ERR_MALFORMED_SCHEMA = "MALFORMED_SCHEMA";
  /** Error code emitted when the root document enclosure braces are missing (E002). */
  public static final String ERR_ROOT_ENCLOSURE_MISSING = "ROOT_ENCLOSURE_MISSING";
  /** Error code emitted when a forbidden tab character ('\t', U+0009) is encountered in STVN syntax outside the verbatim body of a fenced multi-line string (E001). */
  public static final String ERR_TAB_CHARACTER_FORBIDDEN = "ERR_TAB_CHARACTER_FORBIDDEN";
  /** Error code emitted when an empty metadata block '{}' is encountered on a type or constant definition. */
  public static final String ERR_EMPTY_METADATA_BLOCK = "ERR_EMPTY_METADATA_BLOCK";
  /** Error code emitted when an empty root document metadata block ':meta {}' is encountered (SPEC § 3.10.1, E039). */
  public static final String ERR_EMPTY_META_BLOCK = "ERR_EMPTY_META_BLOCK";
  /** Error code emitted when an empty definition block ':defs {}' is encountered. */
  public static final String ERR_EMPTY_DEFS_BLOCK = "ERR_EMPTY_DEFS_BLOCK";
  /** Error code emitted when an empty directive block '{}' is encountered in :use or :include. */
  public static final String ERR_EMPTY_DIRECTIVE_BLOCK = "ERR_EMPTY_DIRECTIVE_BLOCK";
  /** Error code emitted when a metadata facet is applied to an incompatible target entity kind. */
  public static final String ERR_INVALID_METADATA_FACET = "ERR_INVALID_METADATA_FACET";
  /** Error code emitted when an unrecognized constraint facet tag is encountered in a metadata block. */
  public static final String ERR_UNRECOGNIZED_METADATA_FACET = "ERR_UNRECOGNIZED_METADATA_FACET";
  /** Error code emitted when a discrete type (:Int or { #exact } :Float) declares #maxIncl or #minExcl. */
  public static final String ERR_DISCRETE_BOUND_KIND_PROHIBITED = "ERR_DISCRETE_BOUND_KIND_PROHIBITED";
  /** Error code emitted when two incompatible nominal types are unified or compared. */
  public static final String ERR_INCOMPATIBLE_NOMINAL_TYPE = "ERR_INCOMPATIBLE_NOMINAL_TYPE";
  /** Error code emitted when a temporal type declaration lacks a required facet. */
  public static final String ERR_MISSING_TEMPORAL_FACET = "ERR_MISSING_TEMPORAL_FACET";
  /** Error code emitted when a standalone colon ':' is encountered (trap7_infix_colon). */
  public static final String ERR_BARE_COLON_PROHIBITED = "ERR_BARE_COLON_PROHIBITED";
  /** Error code emitted when a standalone hash '#' is encountered (trap6_hash_comment). */
  public static final String ERR_BARE_HASH_PROHIBITED = "ERR_BARE_HASH_PROHIBITED";
  /** Error code emitted when a negative zero integer literal is encountered (prohibited on :Int). */
  public static final String ERR_INVALID_NUMERIC_LITERAL = "ERR_INVALID_NUMERIC_LITERAL";
  /** Error code emitted when a fenced string closing tag mismatches or is unclosed (Rule STR-04, E025). */
  public static final String ERR_FENCE_DELIMITER_MISMATCH = "FENCE_DELIMITER_MISMATCH";
  /** Error code emitted when a prohibited fenced string delimiter arrow is encountered under Rule STR-04. */
  public static final String ERR_PROHIBITED_FENCE_ARROW = "ERR_PROHIBITED_FENCE_ARROW";
  /** Error code emitted when a payload value violates declared or aliased cardinality bounds. */
  public static final String ERR_CONSTRAINT_VIOLATION = "ERR_CONSTRAINT_VIOLATION";
  /** Error code emitted when an AST payload literal kind conflicts with the expected schema constructor. */
  public static final String ERR_TYPE_MISMATCH = "ERR_TYPE_MISMATCH";
  /** Error code emitted when a nominal type alias attempts to override or re-specify a non-overridable definition facet. */
  public static final String ERR_NON_OVERRIDABLE_FACET = "ERR_NON_OVERRIDABLE_FACET";
  /** Error code emitted when child enum subset specifies a variant absent from parent enum (E014). */
  public static final String ERR_ENUM_SUBSET_NOT_SUBSET = "ENUM_SUBSET_NOT_SUBSET";
  /** Error code emitted when child enum subset reorders variants relative to root enum order (SPEC § 4.8.4, Appendix C.2 E015). */
  public static final String ERR_ENUM_SUBSET_ORDER_VIOLATION = "ENUM_SUBSET_ORDER_VIOLATION";
  /** Canonical alias for enum subset root declaration order mismatch (SPEC § 4.8.4, Appendix C.2 E015). */
  public static final String ERR_ORDER_MISMATCH = ERR_ENUM_SUBSET_ORDER_VIOLATION;
  /** Error code emitted when enum subset filtering evaluates to zero active variants (E016). */
  public static final String ERR_ENUM_SUBSET_EMPTY = "ENUM_SUBSET_EMPTY";

  /** Error code emitted when a collection payload violates {@code #minSize} underflow (E017). */
  public static final String ERR_COLLECTION_EMPTY_VIOLATION = "COLLECTION_EMPTY_VIOLATION";
  /** Error code emitted when duplicate elements appear in a {@code :Set} payload. */
  public static final String ERR_DUPLICATE_SET_ELEMENT = "DUPLICATE_SET_ELEMENT";
  /** Error code emitted when a {@code :Tuple} payload element count differs from declared schema arity. */
  public static final String ERR_TUPLE_ARITY_MISMATCH = "TUPLE_ARITY_MISMATCH";
  /** Error code emitted when a {@code :Union} branch index exceeds the declared branch count. */
  public static final String ERR_UNION_BRANCH_OVERFLOW = "UNION_BRANCH_OVERFLOW";
  /** Error code emitted when a zoned {@code :DateTime} literal contains a numerical UTC offset (E019). */
  public static final String ERR_TEMPORAL_OFFSET_IN_ZONED = "TEMPORAL_OFFSET_IN_ZONED";
  /** Error code emitted when a {@code :TimeEpoch} literal contains an IANA zone bracket (E020). */
  public static final String ERR_TEMPORAL_ZONE_IN_EPOCH = "TEMPORAL_ZONE_IN_EPOCH";
  /** Error code emitted when an audited {@code :DateTime} offset disagrees with IANA tzdb rules (E021). */
  public static final String ERR_TEMPORAL_AUDITED_MISMATCH = "TEMPORAL_AUDITED_MISMATCH";
  /** Error code emitted when a civil wall-clock literal falls inside a DST spring-forward gap (E022). */
  public static final String ERR_TEMPORAL_DST_GAP_LITERAL = "TEMPORAL_DST_GAP_LITERAL";
  /** Error code emitted when an untagged payload matches only the Left branch of {@code :Either} (E023). */
  public static final String ERR_EITHER_LEFT_MISSING_TAG = "EITHER_LEFT_MISSING_TAG";
  /** Error code emitted when unreachable definitions appear in strict canonical verification (E029). */
  public static final String ERR_DEAD_CODE_CANONICAL_FAIL = "DEAD_CODE_CANONICAL_FAIL";

  /** Error code emitted when a package enclosure targets a reserved runtime namespace (SPEC § 3.9 Invariant 8, E037). */
  public static final String ERR_RESERVED_PACKAGE_PROHIBITED = "ERR_RESERVED_PACKAGE_PROHIBITED";
  /** Error code emitted when a document declares a reserved system domain in :meta (SPEC § 3.10.1 Rule 6, E038). */
  public static final String ERR_RESERVED_DOMAIN_PROHIBITED = "ERR_RESERVED_DOMAIN_PROHIBITED";
  /** Error code emitted when duplicate keys appear in a map payload (SPEC § 6.2, § 6.3). */
  public static final String ERR_DUPLICATE_MAP_KEY = "DUPLICATE_MAP_KEY";
  /** Error code emitted when duplicate values appear in an invertible map payload (SPEC § 6.2, § 6.3). */
  public static final String ERR_DUPLICATE_INVERTED_MAP_VALUE = "DUPLICATE_INVERTED_MAP_VALUE";

  // Document Identity & Metadata Governance Codes
  /** Error code emitted when a document #kind facet conflicts with its physical file extension. */
  public static final String ERR_DOCUMENT_KIND_MISMATCH = "ERR_DOCUMENT_KIND_MISMATCH";
  /** Error code emitted when a file on disk lacks a visible non-empty stem (bare dotfile). */
  public static final String ERR_INVALID_FILENAME_STEM = "ERR_INVALID_FILENAME_STEM";
  /** Error code emitted when a :meta block is placed out of order (after :defs, :type, or :body). */
  public static final String ERR_META_POSITION_INVALID = "ERR_META_POSITION_INVALID";
  /** Warning code emitted when a declared #name facet does not match the filename stem slot 0. */
  public static final String WARN_DOCUMENT_NAME_MISMATCH = "WARN_DOCUMENT_NAME_MISMATCH";
  /** Warning code emitted when a declared #domain facet is omitted from filename stem slot 1. */
  public static final String WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME = "WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME";
  /** Warning code emitted when a metadata facet declaration is tautological or redundant (SPEC § 5.1.1, § 5.5.3, § 9.2, W003). */
  public static final String WARN_REDUNDANT_METADATA_FACET = "WARN_REDUNDANT_METADATA_FACET";
  /** Error code emitted when an excised legacy file extension (.stvn_incl, .stvn_inclf, .stvn_cas, .stvn_bin, .stvn_f, .stvn_df, .stvn_bf, .stvn_bdf) is encountered. */
  public static final String ERR_LEGACY_FILE_EXTENSION_PURGED = "ERR_LEGACY_FILE_EXTENSION_PURGED";

  // Normative STVN 2.0.0 Standard Diagnostic Codes
  /** Error code emitted when a :TimeEpoch type declaration lacks a mandatory scale facet (SPEC-02). */
  public static final String ERR_TEMPORAL_SCALE_MISSING = "ERR_TEMPORAL_SCALE_MISSING";
  /** Error code emitted when the legacy #unit facet is used instead of bare scale flags (SPEC-02). */
  @Deprecated(forRemoval = true, since = "2.0.0-SNAPSHOT")
  public static final String ERR_LEGACY_UNIT_FACET_PURGED = "ERR_LEGACY_UNIT_FACET_PURGED";
  /** Error code emitted when a :DateTime type declaration lacks or specifies invalid mode facets (SPEC-03). */
  public static final String ERR_DATETIME_MODE_INVALID = "ERR_DATETIME_MODE_INVALID";
  /** Error code emitted when purged prelude nominal aliases are referenced (SPEC-03). */
  public static final String ERR_PRELUDE_ALIAS_PURGED = "ERR_PRELUDE_ALIAS_PURGED";
  /** Error code emitted when metadata facets violate the 5-tier canonical sequence (SPEC-05). */
  public static final String ERR_FACET_ORDER_VIOLATION = "ERR_FACET_ORDER_VIOLATION";
  /** Error code emitted when duplicate metadata facet tags appear in a metadata block (SPEC § 5.1.4). */
  public static final String ERR_DUPLICATE_METADATA_FACET = "ERR_DUPLICATE_METADATA_FACET";
  /** Error code emitted when storage sizing #size is declared on :String (SPEC-06). */
  public static final String ERR_STRING_CARDINALITY_PROHIBITED = "ERR_STRING_CARDINALITY_PROHIBITED";
  /** Error code emitted when nominal types collide or CAS preimage bijectivity is violated (SPEC-07). */
  @Deprecated(forRemoval = true, since = "2.0.0-SNAPSHOT")
  public static final String ERR_CAS_PREIMAGE_AMBIGUITY = "ERR_CAS_PREIMAGE_AMBIGUITY";
  /** Error code emitted when discrete intervals define an empty domain [k, k) with zero habitable values (SPEC-04). */
  public static final String ERR_EMPTY_INTERVAL_DOMAIN = "ERR_EMPTY_INTERVAL_DOMAIN";

  private static final Map<String, String> SYMBOLIC_TO_CANONICAL = Map.ofEntries(
      Map.entry(ERR_TAB_CHARACTER_FORBIDDEN, "E001"),
      Map.entry("TAB_DISCIPLINE_VIOLATION", "E001"),
      Map.entry(ERR_ROOT_ENCLOSURE_MISSING, "E002"),
      Map.entry("STVN_SYNTAX_ERROR", "E002"),
      Map.entry("STVN_SYNTAX_CANCELLATION", "E002"),
      Map.entry("STVN_PARSE_CANCELLATION", "E002"),
      Map.entry(ERR_MALFORMED_SCHEMA, "E002"),
      Map.entry(ERR_BARE_COLON_PROHIBITED, "E002"),
      Map.entry(ERR_BARE_HASH_PROHIBITED, "E002"),
      Map.entry(ERR_RESERVED_KEYWORD_ON_LHS, "E002"),
      Map.entry(ERR_EMPTY_DEFS_BLOCK, "E002"),
      Map.entry(ERR_NESTED_PACKAGE_PROHIBITED, "E002"),
      Map.entry(ERR_TRAILING_SLASH_PROHIBITED, "E002"),
      Map.entry(ERR_EMPTY_METADATA_BLOCK, "E003"),
      Map.entry("EMPTY_METADATA_BLOCK", "E003"),
      Map.entry(ERR_EMPTY_DIRECTIVE_BLOCK, "E004"),
      Map.entry("EMPTY_DIRECTIVE_BLOCK", "E004"),
      Map.entry(ERR_DUPLICATE_MODULE_IMPORT, "E005"),
      Map.entry(ERR_MODULE_IMPORT, "E005"),
      Map.entry(ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT, "E005"),
      Map.entry(ERR_NAMESPACE_COLLISION, "E006"),
      Map.entry(ERR_DUPLICATE_DEF, "E006"),
      Map.entry("CYCLIC_MODULE_INCLUDE", "E007"),
      Map.entry("CIRCULAR_DEPENDENCY", "E007"),
      Map.entry(ERR_CIRCULAR_TYPE, "E007"),
      Map.entry(ERR_INVALID_METADATA_FACET, "E008"),
      Map.entry("INVALID_FACET_TARGET", "E008"),
      Map.entry(ERR_FACET_ORDER_VIOLATION, "E008"),
      Map.entry(ERR_DUPLICATE_METADATA_FACET, "E008"),
      Map.entry(ERR_STRING_CARDINALITY_PROHIBITED, "E008"),
      Map.entry(ERR_INCOMPATIBLE_TYPE, "E008"),
      Map.entry(ERR_MUTUALLY_EXCLUSIVE, "E008"),
      Map.entry(ERR_TRAIT_VIOLATION, "E008"),
      Map.entry(ERR_MISSING_TEMPORAL_FACET, "E008"),
      Map.entry(ERR_TEMPORAL_SCALE_MISSING, "E008"),
      Map.entry(ERR_DATETIME_MODE_INVALID, "E008"),
      Map.entry("ERR_LEGACY_UNIT_FACET_PURGED", "E008"),
      Map.entry(ERR_INTEGER_OVERFLOW, "E009"),
      Map.entry("INT_OVERFLOW", "E009"),
      Map.entry(ERR_INVALID_NUMERIC_LITERAL, "E009"),
      Map.entry(ERR_INVERTED_RANGE, "E009"),
      Map.entry(ERR_EMPTY_INTERVAL_DOMAIN, "E009"),
      Map.entry(ERR_FLOAT_INVALID_SIZE, "E010"),
      Map.entry(ERR_DISCRETE_BOUND_KIND_PROHIBITED, "E011"),
      Map.entry("FLOAT_EXACT_RANGE_CONFLICT", "E011"),
      Map.entry(ERR_CAPACITY_OVERFLOW, "E012"),
      Map.entry("STRING_BOUND_VIOLATION", "E012"),
      Map.entry(ERR_CONSTRAINT_VIOLATION, "E012"),
      Map.entry(ERR_INVALID_REGEX, "E013"),
      Map.entry(ERR_REGEX_MISMATCH, "E013"),
      Map.entry(ERR_ENUM_SUBSET_NOT_SUBSET, "E014"),
      Map.entry(ERR_ENUM_SUBSET_ORDER_VIOLATION, "E015"),
      Map.entry(ERR_ENUM_SUBSET_EMPTY, "E016"),
      Map.entry(ERR_COLLECTION_EMPTY_VIOLATION, "E017"),
      Map.entry(ERR_DUPLICATE_SET_ELEMENT, "E017"),
      Map.entry(ERR_DUPLICATE_MAP_KEY, "E017"),
      Map.entry(ERR_DUPLICATE_INVERTED_MAP_VALUE, "E018"),
      Map.entry("INVERTIBLE_MAP_DUPLICATE", "E018"),
      Map.entry(ERR_TEMPORAL_OFFSET_IN_ZONED, "E019"),
      Map.entry(ERR_TEMPORAL_ZONE_IN_EPOCH, "E020"),
      Map.entry(ERR_TEMPORAL_AUDITED_MISMATCH, "E021"),
      Map.entry(ERR_TEMPORAL_DST_GAP_LITERAL, "E022"),
      Map.entry(ERR_EITHER_LEFT_MISSING_TAG, "E023"),
      Map.entry(ERR_AMBIGUOUS_SUM_INFERENCE, "E024"),
      Map.entry("UNION_BRANCH_AMBIGUOUS", "E024"),
      Map.entry(ERR_FENCE_DELIMITER_MISMATCH, "E025"),
      Map.entry(ERR_PROHIBITED_FENCE_ARROW, "E026"),
      Map.entry("FENCE_PROHIBITED_ARROW", "E026"),
      Map.entry(ERR_INCOMPATIBLE_NOMINAL_TYPE, "E027"),
      Map.entry("OPAQUE_TYPE_BRAND_MISMATCH", "E027"),
      Map.entry(ERR_TYPE_MISMATCH, "E028"),
      Map.entry("CONSTANT_TYPE_MISMATCH", "E028"),
      Map.entry(ERR_TUPLE_ARITY_MISMATCH, "E028"),
      Map.entry(ERR_UNION_BRANCH_OVERFLOW, "E028"),
      Map.entry("MALFORMED_PAYLOAD", "E028"),
      Map.entry(ERR_DEAD_CODE_CANONICAL_FAIL, "E029"),
      Map.entry(ERR_UNDEFINED_TYPE, "E030"),
      Map.entry("ERR_UNKNOWN_TYPE", "E030"),
      Map.entry(ERR_UNDEFINED_CONSTANT, "E030"),
      Map.entry(ERR_PRELUDE_ALIAS_PURGED, "E030"),
      Map.entry("TYPE_RESOLUTION_FAILURE", "E030"),
      Map.entry(ERR_DOCUMENT_KIND_MISMATCH, "E031"),
      Map.entry("ERR_SECTION_PROHIBITED", "E031"),
      Map.entry("ERR_SECTION_REQUIRED", "E031"),
      Map.entry(ERR_INVALID_FILENAME_STEM, "E032"),
      Map.entry(ERR_META_POSITION_INVALID, "E033"),
      Map.entry(ERR_LEGACY_FILE_EXTENSION_PURGED, "E034"),
      Map.entry(ERR_UNRESOLVED_PACKAGE_IN_HERMETIC_DOCUMENT, "E035"),
      Map.entry(ERR_UNRECOGNIZED_METADATA_FACET, "E036"),
      Map.entry(ERR_RESERVED_PACKAGE_PROHIBITED, "E037"),
      Map.entry(ERR_RESERVED_DOMAIN_PROHIBITED, "E038"),
      Map.entry(ERR_EMPTY_META_BLOCK, "E039"),
      Map.entry(ERR_NON_OVERRIDABLE_FACET, "E040"),
      Map.entry(WARN_DOCUMENT_NAME_MISMATCH, "W001"),
      Map.entry(WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME, "W002"),
      Map.entry(WARN_REDUNDANT_METADATA_FACET, "W003")
  );

  private final int maxDiagnostics;
  private final List<StvnDiagnostic> diagnostics;
  private boolean thresholdExceeded = false;

  /**
   * Constructs a DiagnosticBag with a custom maximum diagnostic threshold capacity.
   *
   * @param maxDiagnostics the maximum number of diagnostics to accumulate before suppression
   */
  public DiagnosticBag(int maxDiagnostics) {
    this.maxDiagnostics = Math.max(1, maxDiagnostics);
    this.diagnostics = new ArrayList<>();
  }

  /**
   * Constructs a DiagnosticBag with the default threshold capacity of 100 diagnostics.
   */
  public DiagnosticBag() {
    this(100);
  }

  /**
   * Resolves the normative STVN 2.0.0 canonical specification code ({@code E001}–{@code E040},
   * {@code W001}–{@code W003}) corresponding to a symbolic diagnostic identifier.
   *
   * @param symbolicId the symbolic diagnostic identifier, or {@code null}
   * @return the optional canonical specification code
   */
  public static Optional<String> resolveCanonicalCode(@Nullable String symbolicId) {
    if (symbolicId == null || symbolicId.isEmpty()) {
      return Optional.empty();
    }
    return Optional.ofNullable(SYMBOLIC_TO_CANONICAL.get(symbolicId));
  }

  /**
   * Records a diagnostic into the bag.
   * <p>
   * Automatically enriches diagnostics that declare a {@code symbolicId} without an explicit
   * {@code canonicalCode}. If the number of accumulated diagnostics reaches {@code maxDiagnostics},
   * further diagnostics are suppressed and a single sentinel warning diagnostic is appended.
   *
   * @param diagnostic the diagnostic to record
   * @return {@code true} if the diagnostic was accepted, {@code false} if suppressed due to threshold
   */
  public boolean add(StvnDiagnostic diagnostic) {
    if (diagnostics.size() >= maxDiagnostics) {
      if (!thresholdExceeded) {
        thresholdExceeded = true;
        diagnostics.add(new StvnDiagnostic(
            "Diagnostic threshold limit (" + maxDiagnostics + ") reached. Further diagnostics suppressed.",
            DiagnosticSeverity.WARNING,
            diagnostic.line(),
            diagnostic.column(),
            diagnostic.startOffset(),
            diagnostic.endOffset(),
            null,
            Optional.of("STVN_DIAG_LIMIT_EXCEEDED")
        ));
      }
      return false;
    }
    StvnDiagnostic enriched = diagnostic;
    if (diagnostic.canonicalCode().isEmpty() && diagnostic.symbolicId().isPresent()) {
      Optional<String> mapped = resolveCanonicalCode(diagnostic.symbolicId().get());
      if (mapped.isPresent()) {
        enriched = new StvnDiagnostic(
            diagnostic.message(),
            diagnostic.severity(),
            diagnostic.line(),
            diagnostic.column(),
            diagnostic.startOffset(),
            diagnostic.endOffset(),
            diagnostic.cause(),
            mapped,
            diagnostic.symbolicId()
        );
      }
    }
    diagnostics.add(enriched);
    return true;
  }

  /**
   * Records an error-level diagnostic carrying both an explicit canonical specification code
   * and a symbolic identifier.
   *
   * @param message       the error message
   * @param startOffset   the 0-based character start offset
   * @param endOffset     the 0-based character end offset
   * @param line          the 1-based source line index
   * @param column        the 0-based character column offset
   * @param cause         the underlying throwable cause, or {@code null}
   * @param canonicalCode the canonical specification code ({@code E001}–{@code E040})
   * @param symbolicId    the symbolic diagnostic identifier
   */
  public void addErrorDual(
      String message,
      int startOffset,
      int endOffset,
      int line,
      int column,
      @Nullable Throwable cause,
      String canonicalCode,
      String symbolicId
  ) {
    Throwable effectiveCause = buildEffectiveCause(message, startOffset, endOffset, cause, symbolicId);
    add(new StvnDiagnostic(
        message,
        DiagnosticSeverity.ERROR,
        line,
        column,
        startOffset,
        endOffset,
        effectiveCause,
        Optional.of(canonicalCode),
        Optional.of(symbolicId)
    ));
  }

  /**
   * Records a warning-level diagnostic carrying both an explicit canonical specification code
   * and a symbolic identifier.
   *
   * @param message       the descriptive warning message
   * @param startOffset   the 0-based character start offset
   * @param endOffset     the 0-based character end offset
   * @param line          the 1-based source line index
   * @param column        the 0-based character column offset
   * @param canonicalCode the canonical warning code ({@code W001}–{@code W003})
   * @param symbolicId    the symbolic warning identifier
   */
  public void addWarningDual(
      String message,
      int startOffset,
      int endOffset,
      int line,
      int column,
      String canonicalCode,
      String symbolicId
  ) {
    add(new StvnDiagnostic(
        message,
        DiagnosticSeverity.WARNING,
        line,
        column,
        startOffset,
        endOffset,
        null,
        Optional.of(canonicalCode),
        Optional.of(symbolicId)
    ));
  }

  /**
   * Helper method to record an error-level diagnostic with exact coordinates.
   *
   * @param message     the error message
   * @param startOffset the 0-based character start offset
   * @param endOffset   the 0-based character end offset
   * @param line        the 1-based source line index
   * @param column      the 0-based character column offset
   * @param cause       the underlying throwable cause, or {@code null}
   * @param errorCode   the optional standardized error code identifier, or {@code null}
   */
  public void addError(
      String message,
      int startOffset,
      int endOffset,
      int line,
      int column,
      @Nullable Throwable cause,
      @Nullable String errorCode
  ) {
    Throwable effectiveCause = buildEffectiveCause(message, startOffset, endOffset, cause, errorCode);
    Optional<String> canonical = resolveCanonicalCode(errorCode);
    if (canonical.isPresent() && errorCode != null) {
      add(new StvnDiagnostic(
          message,
          DiagnosticSeverity.ERROR,
          line,
          column,
          startOffset,
          endOffset,
          effectiveCause,
          canonical,
          Optional.of(errorCode)
      ));
    } else {
      add(new StvnDiagnostic(
          message,
          DiagnosticSeverity.ERROR,
          line,
          column,
          startOffset,
          endOffset,
          effectiveCause,
          Optional.ofNullable(errorCode)
      ));
    }
  }

  /**
   * Helper method to record an error-level diagnostic with offset span and error code.
   *
   * @param message     the error message
   * @param startOffset the 0-based character start offset
   * @param endOffset   the 0-based character end offset
   * @param cause       the underlying throwable cause, or {@code null}
   * @param errorCode   the optional standardized error code identifier, or {@code null}
   */
  public void addError(
      String message,
      int startOffset,
      int endOffset,
      @Nullable Throwable cause,
      @Nullable String errorCode
  ) {
    addError(message, startOffset, endOffset, -1, -1, cause, errorCode);
  }

  /**
   * Helper method to record a warning-level diagnostic with exact coordinates.
   *
   * @param message     the descriptive warning message
   * @param startOffset the 0-based character start offset
   * @param endOffset   the 0-based character end offset
   * @param line        the 1-based source line index
   * @param column      the 0-based character column offset
   * @param errorCode   the optional standardized error code identifier, or {@code null}
   */
  public void addWarning(
      String message,
      int startOffset,
      int endOffset,
      int line,
      int column,
      @Nullable String errorCode
  ) {
    Optional<String> canonical = resolveCanonicalCode(errorCode);
    if (canonical.isPresent() && errorCode != null) {
      add(new StvnDiagnostic(
          message,
          DiagnosticSeverity.WARNING,
          line,
          column,
          startOffset,
          endOffset,
          null,
          canonical,
          Optional.of(errorCode)
      ));
    } else {
      add(new StvnDiagnostic(
          message,
          DiagnosticSeverity.WARNING,
          line,
          column,
          startOffset,
          endOffset,
          null,
          Optional.ofNullable(errorCode)
      ));
    }
  }

  /**
   * Helper method to record a warning-level diagnostic with offset span.
   *
   * @param message     the descriptive warning message
   * @param startOffset the 0-based character start offset
   * @param endOffset   the 0-based character end offset
   * @param errorCode   the optional standardized error code identifier, or {@code null}
   */
  public void addWarning(
      String message,
      int startOffset,
      int endOffset,
      @Nullable String errorCode
  ) {
    addWarning(message, startOffset, endOffset, -1, -1, errorCode);
  }

  private static Throwable buildEffectiveCause(
      String message,
      int startOffset,
      int endOffset,
      @Nullable Throwable cause,
      @Nullable String errorCode
  ) {
    if (cause == null) {
      if (ERR_DUPLICATE_DEF.equals(errorCode)) {
        return new IllegalStateException(message);
      } else if (ERR_CIRCULAR_TYPE.equals(errorCode)) {
        return new StvnTypeResolver.CircularReferenceException(message);
      } else if ("CYCLIC_MODULE_INCLUDE".equals(errorCode)) {
        return new CyclicDependencyException(message, List.of(), List.of());
      } else if (ERR_NAMESPACE_COLLISION.equals(errorCode)) {
        return new NamespaceCollisionException(message);
      }
      return new MalformedSchemaException(message, startOffset, endOffset);
    } else if (cause instanceof java.util.regex.PatternSyntaxException) {
      return new MalformedSchemaException(message, startOffset, endOffset, cause);
    }
    return cause;
  }

  /**
   * Checks if any accumulated diagnostic has {@link DiagnosticSeverity#ERROR}.
   *
   * @return {@code true} if any error diagnostic is present
   */
  public boolean hasErrors() {
    return diagnostics.stream().anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR);
  }

  /**
   * Returns the count of accumulated {@link DiagnosticSeverity#ERROR} diagnostics.
   *
   * @return total error diagnostic count
   */
  public int errorCount() {
    return (int) diagnostics.stream().filter(d -> d.severity() == DiagnosticSeverity.ERROR).count();
  }

  /**
   * Checks if any accumulated diagnostic has {@link DiagnosticSeverity#WARNING}.
   *
   * @return {@code true} if any warning diagnostic is present
   */
  public boolean hasWarnings() {
    return diagnostics.stream().anyMatch(d -> d.severity() == DiagnosticSeverity.WARNING);
  }

  /**
   * Returns an unmodifiable snapshot list of all accumulated diagnostics.
   *
   * @return the list of diagnostics; empty list if none were recorded
   */
  public List<StvnDiagnostic> toList() {
    return diagnostics.isEmpty() ? Collections.emptyList() : List.copyOf(diagnostics);
  }

  /**
   * Returns the count of accumulated diagnostics.
   *
   * @return total diagnostics count
   */
  public int size() {
    return diagnostics.size();
  }

  /**
   * Checks if no diagnostics were recorded.
   *
   * @return {@code true} if empty
   */
  public boolean isEmpty() {
    return diagnostics.isEmpty();
  }

  /**
   * Checks if the diagnostic threshold limit was exceeded.
   *
   * @return {@code true} if threshold was breached
   */
  public boolean isThresholdExceeded() {
    return thresholdExceeded;
  }

  /**
   * Returns the maximum diagnostic threshold capacity of this bag.
   *
   * @return the maximum diagnostics threshold
   */
  public int getMaxDiagnostics() {
    return maxDiagnostics;
  }
}
