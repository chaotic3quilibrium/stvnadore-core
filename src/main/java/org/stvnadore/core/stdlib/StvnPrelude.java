package org.stvnadore.core.stdlib;

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.ir.StvnDefsCatalog;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.lowering.StvnDefsLowerer;
import org.stvnadore.core.parser.StvnErrorListener;
import org.stvnadore.core.parser.StvnLexer;
import org.stvnadore.core.parser.StvnParser;
import org.stvnadore.core.validation.DiagnosticBag;

import java.util.Optional;

/**
 * Static registry for standard library types and constants implicitly available via
 * {@code :org/stvnadore/prelude} across all STVN 2.0.0 compilation units.
 * <p>
 * Exposes the lowered, immutable {@link StvnDocument} and {@link StvnDefsCatalog}
 * containing {@code :org/stvnadore/prelude/UuidLowerCase}, {@code :org/stvnadore/prelude/UuidMixedCase},
 * {@code :org/stvnadore/prelude/Ulid}, {@code :org/stvnadore/prelude/Sha256LowerCase},
 * {@code :org/stvnadore/prelude/Sha256MixedCase}, {@code :org/stvnadore/prelude/SemVer},
 * {@code :org/stvnadore/prelude/EmailLowerCase}, {@code :org/stvnadore/prelude/EmailMixedCase},
 * {@code :org/stvnadore/prelude/IPv4}, {@code :org/stvnadore/prelude/Port},
 * {@code :org/stvnadore/prelude/Percentage}, {@code :org/stvnadore/prelude/Probability},
 * {@code :org/stvnadore/prelude/Currency}, {@code :org/stvnadore/prelude/Latitude},
 * {@code :org/stvnadore/prelude/Longitude}, {@code :org/stvnadore/prelude/PosixId},
 * {@code :org/stvnadore/prelude/PolyglotCodeId}, and the meta-circular identity types under
 * {@code :org/stvnadore/prelude/meta}.
 *
 * @since 1.0.0
 */
@NullMarked
public final class StvnPrelude {

  /**
   * The canonical STVN 2.0.0 source string defining the standard library prelude schema ({@code .stvn_d}).
   */
  public static final String PRELUDE_STVN_D = """
      {
        :meta {
          #name "prelude"
          #domain "stvn_core"
          #kind #DEFS
        }
        :defs {
          :package :org/stvnadore/prelude {
            #UUID_LOWER_CASE_REGEX :String "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
            #UUID_MIXED_CASE_REGEX :String "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
            :UuidLowerCase { #minSize 36 #maxSize 36 #regex #UUID_LOWER_CASE_REGEX } :String
            :UuidMixedCase { #minSize 36 #maxSize 36 #regex #UUID_MIXED_CASE_REGEX } :String

            :Ulid { #minSize 26 #maxSize 26 #regex "^[0-7][0-9A-HJKMNP-TV-Z]{25}$" } :String

            #SHA256_LOWER_CASE_REGEX :String "^[0-9a-f]{64}$"
            #SHA256_MIXED_CASE_REGEX :String "^[0-9a-fA-F]{64}$"
            :Sha256LowerCase { #minSize 64 #maxSize 64 #regex #SHA256_LOWER_CASE_REGEX } :String
            :Sha256MixedCase { #minSize 64 #maxSize 64 #regex #SHA256_MIXED_CASE_REGEX } :String

            :SemVer { #minSize 5 #maxSize 128 #regex "^(0|[1-9][0-9]*)\\\\.(0|[1-9][0-9]*)\\\\.(0|[1-9][0-9]*)(?:-((?:0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\\\.(?:0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\\\\+([0-9a-zA-Z-]+(?:\\\\.[0-9a-zA-Z-]+)*))?$" } :String

            #EMAIL_LOWER_CASE_REGEX :String "^[a-z0-9._%+-]+@[a-z0-9.-]+\\\\.[a-z]{2,}$"
            #EMAIL_MIXED_CASE_REGEX :String "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\\\.[a-zA-Z]{2,}$"
            :EmailLowerCase { #minSize 3 #maxSize 254 #regex #EMAIL_LOWER_CASE_REGEX } :String
            :EmailMixedCase { #minSize 3 #maxSize 254 #regex #EMAIL_MIXED_CASE_REGEX } :String

            :IPv4  { #minSize 7 #maxSize 15 #regex "^((25[0-5]|(2[0-4]|1[0-9]|[1-9]|)[0-9])\\\\.?\\\\b){4}$" } :String
            :Port  { #unsigned #size 16 #minIncl 1 } :Int

            :Percentage  { #minIncl 0.0 #maxIncl 100.0 } :Float
            :Probability { #minIncl 0.0 #maxIncl 1.0 }   :Float
            :Currency    { #exact } :Float
            :Latitude    { #minIncl -90.0 #maxIncl 90.0 }   :Float
            :Longitude   { #minIncl -180.0 #maxIncl 180.0 } :Float

            #POSIX_ID_REGEX :String "^[a-zA-Z0-9_-]+$"
            :PosixId { #minSize 1 #maxSize 255 #regex #POSIX_ID_REGEX } :String
            #POLYGLOT_CODE_ID_REGEX :String "^[a-zA-Z_][a-zA-Z0-9_]*$"
            :PolyglotCodeId { #minSize 1 #maxSize 128 #regex #POLYGLOT_CODE_ID_REGEX } :String
          }

          :package :org/stvnadore/prelude/meta {
            :use [ :org/stvnadore/prelude { #strip } ]

            :StvnPosixId64 { #minSize 1 #maxSize 64 #regex #POSIX_ID_REGEX } :String
            :StvnDocumentKind :Enum [ #BODY #DEFS #BODY_INCLUDE #DEFS_INCLUDE ]
            :StvnDocumentMeta :Tuple( :Option(:StvnPosixId64) :Option(:StvnPosixId64) :StvnDocumentKind )
            :StvnCatalogMeta :Tuple( :StvnPosixId64 :StvnPosixId64 :StvnDocumentKind )
          }
        }
      }""";

  private static final StvnDocument CACHED_PRELUDE_DOCUMENT = bootstrapPreludeDocument();

  private StvnPrelude() {
    // Non-instantiable standard library holder
  }

  private static StvnDocument bootstrapPreludeDocument() {
    DiagnosticBag bag = new DiagnosticBag();
    StvnLexer lexer = new StvnLexer(CharStreams.fromString(PRELUDE_STVN_D));
    lexer.removeErrorListeners();
    StvnParser parser = new StvnParser(new CommonTokenStream(lexer));
    parser.removeErrorListeners();
    StvnErrorListener listener = StvnErrorListener.strict();
    lexer.addErrorListener(listener);
    parser.addErrorListener(listener);
    parser.setErrorHandler(new BailErrorStrategy());

    StvnParser.StvnDocumentContext docCtx = parser.stvnDocument();
    StvnDefsLowerer.DefsLoweringResult lowered = StvnDefsLowerer.lowerPreludeBootstrap(docCtx, bag);
    if (bag.hasErrors()) {
      throw new IllegalStateException("Standard library prelude failed bootstrap validation: " + bag.toList());
    }
    return new StvnDocument(
        lowered.meta(),
        lowered.defs(),
        Optional.empty(),
        Optional.empty(),
        lowered.hasDefsSection(),
        lowered.hasIncludes()
    );
  }

  /**
   * Returns the lowered immutable IR {@link StvnDocument} representing the standard library prelude.
   *
   * @return the standard library prelude {@link StvnDocument}
   * @since 2.0.0
   */
  public static StvnDocument preludeDocument() {
    return CACHED_PRELUDE_DOCUMENT;
  }

  /**
   * Returns the lowered immutable {@link StvnDefsCatalog} containing all standard library prelude types and constants.
   *
   * @return the standard library prelude {@link StvnDefsCatalog}
   * @since 2.0.0
   */
  public static StvnDefsCatalog preludeCatalog() {
    return CACHED_PRELUDE_DOCUMENT.defs();
  }
}
