package org.stvnadore.core;

import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

/**
 * Centralized repository of canonical STVN 2.0.0 lexical keywords, type identifiers,
 * facet names, and value tokens.
 * <p>
 * This class provides immutable compile-time constants to eliminate scattered string
 * literals across validation, binary serialization, canonical writing, and AST lowering.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnVocabulary {

  private StvnVocabulary() {
    // Non-instantiable utility class
  }

  // ============================================================================
  // 1. BASE SCALAR TYPIC IDENTIFIERS (6 FOUNDATION PRIMITIVES)
  // ============================================================================

  /** Canonical type identifier for boolean primitives ({@code :Boolean}). */
  public static final String TYPE_BOOLEAN = ":Boolean";

  /** Canonical type identifier for integer primitives ({@code :Int}). */
  public static final String TYPE_INT = ":Int";

  /** Canonical type identifier for floating-point primitives ({@code :Float}). */
  public static final String TYPE_FLOAT = ":Float";

  /** Canonical type identifier for UTF-8 string primitives ({@code :String}). */
  public static final String TYPE_STRING = ":String";

  /** Canonical type identifier for temporal epoch offset primitives ({@code :TimeEpoch}). */
  public static final String TYPE_TIME_EPOCH = ":TimeEpoch";

  /** Canonical type identifier for calendar datetime primitives ({@code :DateTime}). */
  public static final String TYPE_DATE_TIME = ":DateTime";

  // ============================================================================
  // 2. COLLECTION AND ALGEBRAIC COMPOSITE TYPIC IDENTIFIERS
  // ============================================================================

  /** Canonical type identifier for ordered sequence collections ({@code :Seq}). */
  public static final String TYPE_SEQ = ":Seq";

  /** Canonical type identifier for unique set collections ({@code :Set}). */
  public static final String TYPE_SET = ":Set";

  /** Canonical type identifier for associative key-value map collections ({@code :Map}). */
  public static final String TYPE_MAP = ":Map";

  /** Canonical type identifier for fixed heterogeneous product types ({@code :Tuple}). */
  public static final String TYPE_TUPLE = ":Tuple";

  /** Canonical type identifier for optional monadic coproducts ({@code :Option}). */
  public static final String TYPE_OPTION = ":Option";

  /** Canonical type identifier for binary disjoint coproducts ({@code :Either}). */
  public static final String TYPE_EITHER = ":Either";

  /** Canonical type identifier for tagged multi-branch union coproducts ({@code :Union}). */
  public static final String TYPE_UNION = ":Union";

  /** Canonical type identifier for symbolic discrete enumerations ({@code :Enum}). */
  public static final String TYPE_ENUM = ":Enum";

  /** Canonical type identifier for isolated key-value map entries ({@code :MapEntry}). */
  public static final String TYPE_MAP_ENTRY = ":MapEntry";

  // ============================================================================
  // 3. STRUCTURAL DOCUMENT KEYWORDS
  // ============================================================================

  /** Structural keyword for document identity metadata blocks ({@code :meta}). */
  public static final String KEYWORD_META = ":meta";

  /** Structural keyword for nominal type definition blocks ({@code :defs}). */
  public static final String KEYWORD_DEFS = ":defs";

  /** Structural keyword for document root schema type declarations ({@code :type}). */
  public static final String KEYWORD_TYPE = ":type";

  /** Structural keyword for document root payload body declarations ({@code :body}). */
  public static final String KEYWORD_BODY = ":body";

  /** Structural keyword for external module include declarations ({@code :include}). */
  public static final String KEYWORD_INCLUDE = ":include";

  /** Structural keyword for module package namespace declarations ({@code :package}). */
  public static final String KEYWORD_PACKAGE = ":package";

  /** Structural keyword for imported namespace scoping declarations ({@code :use}). */
  public static final String KEYWORD_USE = ":use";

  // ============================================================================
  // 4. CANONICAL 7-TIER METADATA FACET KEYWORDS (LEXICAL SYNTAX)
  // ============================================================================

  // Tier 1: Flags & Intrinsic Modes
  /** Lexical keyword for unsigned integer facet ({@code #unsigned}). */
  public static final String FACET_KW_UNSIGNED = "#unsigned";

  /** Lexical keyword for exact decimal arithmetic facet ({@code #exact}). */
  public static final String FACET_KW_EXACT = "#exact";

  /** Lexical keyword for bidirectional invertible map facet ({@code #invertible}). */
  public static final String FACET_KW_INVERTIBLE = "#invertible";

  /** Lexical keyword for multi-line block string indentation preservation ({@code #preserveIndent}). */
  public static final String FACET_KW_PRESERVE_INDENT = "#preserveIndent";

  /** Lexical keyword for datetime numerical UTC offset facet ({@code #offset}). */
  public static final String FACET_KW_OFFSET = "#offset";

  /** Lexical keyword for datetime IANA timezone identifier facet ({@code #zoned}). */
  public static final String FACET_KW_ZONED = "#zoned";

  /** Lexical keyword for datetime full audited representation facet ({@code #audited}). */
  public static final String FACET_KW_AUDITED = "#audited";

  /** Lexical keyword for equatable trait capability override ({@code #equatable}). */
  public static final String FACET_KW_EQUATABLE = "#equatable";

  /** Lexical keyword for comparable trait capability override ({@code #comparable}). */
  public static final String FACET_KW_COMPARABLE = "#comparable";

  // Tier 2: Temporal Scales
  /** Lexical keyword for seconds temporal epoch scale ({@code #s}). */
  public static final String FACET_KW_SCALE_S = "#s";

  /** Lexical keyword for milliseconds temporal epoch scale ({@code #ms}). */
  public static final String FACET_KW_SCALE_MS = "#ms";

  /** Lexical keyword for microseconds temporal epoch scale ({@code #us}). */
  public static final String FACET_KW_SCALE_US = "#us";

  /** Lexical keyword for nanoseconds temporal epoch scale ({@code #ns}). */
  public static final String FACET_KW_SCALE_NS = "#ns";

  // Tier 3: Dimensions & Capacity
  /** Lexical keyword for explicit bit-width or size facet ({@code #size}). */
  public static final String FACET_KW_SIZE = "#size";

  /** Lexical keyword for minimum collection or string cardinality ({@code #minSize}). */
  public static final String FACET_KW_MIN_SIZE = "#minSize";

  /** Lexical keyword for maximum collection or string cardinality ({@code #maxSize}). */
  public static final String FACET_KW_MAX_SIZE = "#maxSize";

  // Tier 4: Value Intervals
  /** Lexical keyword for inclusive lower bound ({@code #minIncl}). */
  public static final String FACET_KW_MIN_INCL = "#minIncl";

  /** Lexical keyword for exclusive lower bound ({@code #minExcl}). */
  public static final String FACET_KW_MIN_EXCL = "#minExcl";

  /** Lexical keyword for exclusive upper bound ({@code #maxExcl}). */
  public static final String FACET_KW_MAX_EXCL = "#maxExcl";

  /** Lexical keyword for inclusive upper bound ({@code #maxIncl}). */
  public static final String FACET_KW_MAX_INCL = "#maxIncl";

  // Tier 5: Validation Patterns
  /** Lexical keyword for regular expression string pattern validation ({@code #regex}). */
  public static final String FACET_KW_REGEX = "#regex";

  // Tier 6: Domain Subsets
  /** Lexical keyword for inclusive enum variant subset filtering ({@code #filterIncl}). */
  public static final String FACET_KW_FILTER_INCL = "#filterIncl";

  /** Lexical keyword for exclusive enum variant subset filtering ({@code #filterExcl}). */
  public static final String FACET_KW_FILTER_EXCL = "#filterExcl";

  // Tier 7: Directives
  /** Lexical keyword for string whitespace stripping directive ({@code #strip}). */
  public static final String FACET_KW_STRIP = "#strip";

  // Document Identity Facets
  /** Lexical keyword for document identity name facet ({@code #name}). */
  public static final String FACET_KW_META_NAME = "#name";

  /** Lexical keyword for document identity domain facet ({@code #domain}). */
  public static final String FACET_KW_META_DOMAIN = "#domain";

  /** Lexical keyword for document identity kind facet ({@code #kind}). */
  public static final String FACET_KW_META_KIND = "#kind";

  // Document Kind Enum Constant Tokens
  /** Document kind enum constant token for hermetic document body ({@code #BODY}). */
  public static final String KIND_KW_BODY = "#BODY";

  /** Document kind enum constant token for hermetic definitions schema ({@code #DEFS}). */
  public static final String KIND_KW_DEFS = "#DEFS";

  /** Document kind enum constant token for modular document body ({@code #BODY_INCLUDE}). */
  public static final String KIND_KW_BODY_INCLUDE = "#BODY_INCLUDE";

  /** Document kind enum constant token for modular definitions module ({@code #DEFS_INCLUDE}). */
  public static final String KIND_KW_DEFS_INCLUDE = "#DEFS_INCLUDE";

  // ============================================================================
  // 5. CANONICAL BARE FACET NAMES (AST & DICTIONARY KEYS)
  // ============================================================================

  // Tier 1 Names
  /** Bare facet name for unsigned integer flag ({@code unsigned}). */
  public static final String FACET_NAME_UNSIGNED = "unsigned";

  /** Bare facet name for exact decimal flag ({@code exact}). */
  public static final String FACET_NAME_EXACT = "exact";

  /** Bare facet name for invertible map flag ({@code invertible}). */
  public static final String FACET_NAME_INVERTIBLE = "invertible";

  /** Bare facet name for preserve indent flag ({@code preserveIndent}). */
  public static final String FACET_NAME_PRESERVE_INDENT = "preserveIndent";

  /** Bare facet name for datetime offset flag ({@code offset}). */
  public static final String FACET_NAME_OFFSET = "offset";

  /** Bare facet name for datetime zoned flag ({@code zoned}). */
  public static final String FACET_NAME_ZONED = "zoned";

  /** Bare facet name for datetime audited flag ({@code audited}). */
  public static final String FACET_NAME_AUDITED = "audited";

  /** Bare facet name for equatable trait override ({@code equatable}). */
  public static final String FACET_NAME_EQUATABLE = "equatable";

  /** Bare facet name for comparable trait override ({@code comparable}). */
  public static final String FACET_NAME_COMPARABLE = "comparable";

  // Tier 2 Names / Scale Identifiers
  /** Scale identifier for seconds ({@code s}). */
  public static final String SCALE_S = "s";

  /** Scale identifier for milliseconds ({@code ms}). */
  public static final String SCALE_MS = "ms";

  /** Scale identifier for microseconds ({@code us}). */
  public static final String SCALE_US = "us";

  /** Scale identifier for nanoseconds ({@code ns}). */
  public static final String SCALE_NS = "ns";

  /** Bare facet name for temporal scale category ({@code scale}). */
  public static final String FACET_NAME_SCALE = "scale";

  // Tier 3 Names
  /** Bare facet name for size dimension ({@code size}). */
  public static final String FACET_NAME_SIZE = "size";

  /** Bare facet name for minimum size dimension ({@code minSize}). */
  public static final String FACET_NAME_MIN_SIZE = "minSize";

  /** Bare facet name for maximum size dimension ({@code maxSize}). */
  public static final String FACET_NAME_MAX_SIZE = "maxSize";

  // Tier 4 Names
  /** Bare facet name for inclusive lower bound ({@code minIncl}). */
  public static final String FACET_NAME_MIN_INCL = "minIncl";

  /** Bare facet name for exclusive lower bound ({@code minExcl}). */
  public static final String FACET_NAME_MIN_EXCL = "minExcl";

  /** Bare facet name for exclusive upper bound ({@code maxExcl}). */
  public static final String FACET_NAME_MAX_EXCL = "maxExcl";

  /** Bare facet name for inclusive upper bound ({@code maxIncl}). */
  public static final String FACET_NAME_MAX_INCL = "maxIncl";

  // Tier 5 Names
  /** Bare facet name for regex pattern ({@code regex}). */
  public static final String FACET_NAME_REGEX = "regex";

  // Tier 6 Names
  /** Bare facet name for inclusive variant filter ({@code filterIncl}). */
  public static final String FACET_NAME_FILTER_INCL = "filterIncl";

  /** Bare facet name for exclusive variant filter ({@code filterExcl}). */
  public static final String FACET_NAME_FILTER_EXCL = "filterExcl";

  // Tier 7 Names
  /** Bare facet name for strip directive ({@code strip}). */
  public static final String FACET_NAME_STRIP = "strip";

  // ============================================================================
  // 6. BOOLEAN AND SUM VALUE TRACK TOKENS
  // ============================================================================

  /** Canonical token for boolean true literal ({@code #TRUE}). */
  public static final String VAL_TRUE = "#TRUE";

  /** Canonical token for boolean false literal ({@code #FALSE}). */
  public static final String VAL_FALSE = "#FALSE";

  /** Abbreviated token for boolean true literal ({@code #T}). */
  public static final String VAL_TRUE_SHORT = "#T";

  /** Abbreviated token for boolean false literal ({@code #F}). */
  public static final String VAL_FALSE_SHORT = "#F";

  /** Canonical token for Option populated variant ({@code #Some}). */
  public static final String VAL_SOME = "#Some";

  /** Canonical token for Option empty variant ({@code #None}). */
  public static final String VAL_NONE = "#None";

  /** Abbreviated token for Option populated variant ({@code #S}). */
  public static final String VAL_SOME_SHORT = "#S";

  /** Abbreviated token for Option empty variant ({@code #N}). */
  public static final String VAL_NONE_SHORT = "#N";

  /** Canonical token for Either left variant ({@code #Left}). */
  public static final String VAL_LEFT = "#Left";

  /** Canonical token for Either right variant ({@code #Right}). */
  public static final String VAL_RIGHT = "#Right";

  /** Abbreviated token for Either left variant ({@code #L}). */
  public static final String VAL_LEFT_SHORT = "#L";

  /** Abbreviated token for Either right variant ({@code #R}). */
  public static final String VAL_RIGHT_SHORT = "#R";

  // ============================================================================
  // 7. IEEE-754 SPECIAL FLOATING-POINT LITERALS
  // ============================================================================

  /** Canonical token for IEEE-754 Not-a-Number literal ({@code NaN}). */
  public static final String LITERAL_NAN = "NaN";

  /** Canonical token for IEEE-754 positive infinity literal ({@code +Infinity}). */
  public static final String LITERAL_POS_INFINITY = "+Infinity";

  /** Canonical token for IEEE-754 negative infinity literal ({@code -Infinity}). */
  public static final String LITERAL_NEG_INFINITY = "-Infinity";

  /** Canonical token for IEEE-754 negative zero literal ({@code -0.0}). */
  public static final String LITERAL_NEG_ZERO = "-0.0";

  // ============================================================================
  // 8. EXHAUSTIVE STRUCTURAL GROUP DELIMITERS
  // ============================================================================

  /** Canonical token for structural opening brace delimiter (<code>{</code>). */
  public static final String DELIM_OPEN_BRACE = "{";

  /** Canonical token for structural closing brace delimiter (<code>}</code>). */
  public static final String DELIM_CLOSE_BRACE = "}";

  /** Canonical token for structural opening bracket delimiter ({@code [}). */
  public static final String DELIM_OPEN_BRACKET = "[";

  /** Canonical token for structural closing bracket delimiter ({@code ]}). */
  public static final String DELIM_CLOSE_BRACKET = "]";

  /** Canonical token for structural opening parenthesis delimiter ({@code (}). */
  public static final String DELIM_OPEN_PAREN = "(";

  /** Canonical token for structural closing parenthesis delimiter ({@code )}). */
  public static final String DELIM_CLOSE_PAREN = ")";

  /** Pre-baked unmodifiable set containing the exhaustive structural grouping delimiters: {, }, [, ], (, ). */
  public static final Set<String> STRUCTURAL_DELIMITERS = Set.of(
      DELIM_OPEN_BRACE,
      DELIM_CLOSE_BRACE,
      DELIM_OPEN_BRACKET,
      DELIM_CLOSE_BRACKET,
      DELIM_OPEN_PAREN,
      DELIM_CLOSE_PAREN
  );

  // ============================================================================
  // 9. DUAL-TRACK BOUND PREFIX SIGILS
  // ============================================================================

  /** Canonical prefix sigil for the Typic Track (:). */
  public static final String SIGIL_TYPIC = ":";

  /** Canonical prefix sigil for the Value / Variable Track (#). */
  public static final String SIGIL_VALUE = "#";

  // ============================================================================
  // 10. PRE-BAKED CANONICAL TOKEN SETS FOR FAST INTERCEPTION & LOOKUP
  // ============================================================================

  /** Pre-baked unmodifiable set containing all 6 canonical base scalar primitive type keywords. */
  public static final Set<String> BASE_SCALAR_TYPES = Set.of(
      TYPE_BOOLEAN,
      TYPE_INT,
      TYPE_FLOAT,
      TYPE_STRING,
      TYPE_TIME_EPOCH,
      TYPE_DATE_TIME
  );

  /** Pre-baked unmodifiable set containing all 8 composite type constructor keywords. */
  public static final Set<String> COMPOSITE_CONSTRUCTOR_TYPES = Set.of(
      TYPE_SEQ,
      TYPE_SET,
      TYPE_MAP,
      TYPE_TUPLE,
      TYPE_OPTION,
      TYPE_EITHER,
      TYPE_UNION,
      TYPE_ENUM
  );

  /**
   * Tests whether a candidate type string exactly equals one of the 6 canonical base scalar primitives.
   *
   * @param typeStr the candidate type keyword to evaluate
   * @return {@code true} if the type string exactly equals a base scalar type; {@code false} otherwise
   */
  public static boolean isBaseScalarType(@Nullable String typeStr) {
    return typeStr != null && BASE_SCALAR_TYPES.contains(typeStr);
  }

  /**
   * Tests whether a type string matches a composite type constructor name with strict delimiter discipline.
   * <p>
   * Matching succeeds if {@code typeStr} is equal to {@code constructorName}, or begins with
   * {@code constructorName} followed immediately by an opening delimiter ({@code (} or {@code [} for enum)
   * or whitespace. Any alphanumeric continuation (e.g. {@code :SeqRecord} or {@code :MapStore})
   * is strictly rejected.
   *
   * @param typeStr the candidate type string to evaluate
   * @param constructorName the canonical constructor keyword to match (e.g. {@code :Seq}, {@code :Map})
   * @return {@code true} if the candidate matches the constructor boundary; {@code false} otherwise
   */
  public static boolean isConstructorMatch(@Nullable String typeStr, String constructorName) {
    if (typeStr == null || constructorName == null) {
      return false;
    }
    if (typeStr.equals(constructorName)) {
      return true;
    }
    if (typeStr.startsWith(constructorName)) {
      int prefixLen = constructorName.length();
      if (typeStr.length() > prefixLen) {
        char nextChar = typeStr.charAt(prefixLen);
        if (TYPE_ENUM.equals(constructorName)) {
          return nextChar == '[' || Character.isWhitespace(nextChar);
        }
        return nextChar == '(' || Character.isWhitespace(nextChar);
      }
    }
    return false;
  }

  /** Pre-baked unmodifiable set containing all canonical and abbreviated boolean tokens. */
  public static final Set<String> BOOLEAN_KEYWORDS = Set.of(
      VAL_TRUE,
      VAL_FALSE,
      VAL_TRUE_SHORT,
      VAL_FALSE_SHORT
  );

  /** Pre-baked unmodifiable set containing all canonical and abbreviated Option tokens. */
  public static final Set<String> OPTION_KEYWORDS = Set.of(
      VAL_SOME,
      VAL_NONE,
      VAL_SOME_SHORT,
      VAL_NONE_SHORT
  );

  /** Pre-baked unmodifiable set containing all canonical and abbreviated Either tokens. */
  public static final Set<String> EITHER_KEYWORDS = Set.of(
      VAL_LEFT,
      VAL_RIGHT,
      VAL_LEFT_SHORT,
      VAL_RIGHT_SHORT
  );

  /** Pre-baked unmodifiable set containing all canonical and abbreviated control/value keywords. */
  public static final Set<String> CONTROL_KEYWORDS = Set.of(
      VAL_NONE, VAL_NONE_SHORT,
      VAL_SOME, VAL_SOME_SHORT,
      VAL_LEFT, VAL_LEFT_SHORT,
      VAL_RIGHT, VAL_RIGHT_SHORT,
      VAL_TRUE, VAL_TRUE_SHORT,
      VAL_FALSE, VAL_FALSE_SHORT
  );

  // ============================================================================
  // 11. ARCHITECTURAL LIMITS & BUFFER CAPACITIES
  // ============================================================================

  /** Default allocation capacity limit in characters for unadorned {@code :String} instances (16,777,216 characters / 16 MiB). */
  public static final int DEFAULT_UNBOUNDED_STRING_CAPACITY = 16_777_216;

  /** Default inspection threshold in characters for schema and diagnostic analyzers (4,096 characters). */
  public static final int DEFAULT_INSPECTION_THRESHOLD = 4096;

  /** Default indentation width in spaces for canonical text formatting (2 spaces). */
  public static final int DEFAULT_INDENT_WIDTH = 2;

  // ============================================================================
  // 12. CANONICAL PRELUDE IDENTIFIERS & META-CIRCULAR SUBSTRATE
  // ============================================================================

  /** Canonical FQNI for universal POSIX filesystem identifiers ({@code :org/stvnadore/prelude/PosixId}). */
  public static final String PRELUDE_TYPE_POSIX_ID = ":org/stvnadore/prelude/PosixId";

  /** Canonical FQNI for polyglot code identifiers ({@code :org/stvnadore/prelude/PolyglotCodeId}). */
  public static final String PRELUDE_TYPE_POLYGLOT_CODE_ID = ":org/stvnadore/prelude/PolyglotCodeId";

  /** Canonical FQNI for 64-character document POSIX identifiers ({@code :org/stvnadore/prelude/meta/StvnPosixId64}). */
  public static final String META_TYPE_POSIX_ID_64 = ":org/stvnadore/prelude/meta/StvnPosixId64";

  /** Canonical FQNI for document kind enum ({@code :org/stvnadore/prelude/meta/StvnDocumentKind}). */
  public static final String META_TYPE_DOCUMENT_KIND = ":org/stvnadore/prelude/meta/StvnDocumentKind";

  /** Canonical FQNI for document metadata tuple ({@code :org/stvnadore/prelude/meta/StvnDocumentMeta}). */
  public static final String META_TYPE_DOCUMENT_META = ":org/stvnadore/prelude/meta/StvnDocumentMeta";

  /** Canonical FQNI for authoritative catalog metadata tuple ({@code :org/stvnadore/prelude/meta/StvnCatalogMeta}). */
  public static final String META_TYPE_CATALOG_META = ":org/stvnadore/prelude/meta/StvnCatalogMeta";
}
