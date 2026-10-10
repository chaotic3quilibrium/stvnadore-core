package org.stvnadore.core.stdlib;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.parser.StvnLexer;
import org.stvnadore.core.parser.StvnParser;

/**
 * Static registry for standard library types implicitly available in all STVN context environments.
 * <p>
 * Contains definitions for common nominal types such as {@code :org/stvnadore/prelude/Uuid},
 * {@code :org/stvnadore/prelude/Ulid}, {@code :org/stvnadore/prelude/IPv4}, {@code :org/stvnadore/prelude/Port},
 * {@code :org/stvnadore/prelude/Percentage}, {@code :org/stvnadore/prelude/Currency},
 * {@code :org/stvnadore/prelude/PosixId}, and {@code :org/stvnadore/prelude/PolyglotCodeId},
 * as well as the meta-circular substrate under {@code :org/stvnadore/prelude/meta}.
 *
 * @since 1.0.0
 */
@NullMarked
public final class StvnPrelude {

  /**
   * The canonical STVN source string defining the standard library prelude schema (.stvn_d).
   */
  public static final String PRELUDE_STVN_D = """
      {
        //File:        prelude.stvn_d
        //Description: Made available implicitly in every .stvn* file
        //Version:     2.0.0
        //Date:        2026.10.10
        :defs {
          :package :org/stvnadore/prelude {
            :Uuid { #minSize 36 #maxSize 36 #regex "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$" } :String
            :Ulid { #minSize 26 #maxSize 26 #regex "^[0-7][0-9A-HJKMNP-TV-Z]{25}$" } :String
            :Sha256 { #minSize 64 #maxSize 64 #regex "^[0-9a-fA-F]{64}$" } :String
            :SemVer { #minSize 5 #maxSize 128 #regex "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-((?:0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-zA-Z0-9-]*)(?:\\.(?:0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-zA-Z0-9-]*))*))?(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$" } :String

            :Email { #minSize 3 #maxSize 254 #regex "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$" } :String
            :IPv4  { #minSize 7 #maxSize 15 #regex "^((25[0-5]|(2[0-4]|1[0-9]|[1-9]|)[0-9])\\.?\\b){4}$" } :String
            :Port  { #unsigned #size 16 #minIncl 1 #maxExcl 65536 } :Int

            :Percentage  { #size 64 #minIncl 0.0 #maxIncl 100.0 } :Float
            :Probability { #size 64 #minIncl 0.0 #maxIncl 1.0 }   :Float
            :Currency    { #exact } :Float
            :Latitude    { #size 64 #minIncl -90.0 #maxIncl 90.0 }   :Float
            :Longitude   { #size 64 #minIncl -180.0 #maxIncl 180.0 } :Float

            :PosixId { #minSize 1 #maxSize 255 #regex "^[a-zA-Z0-9_-]+$" } :String
            :PolyglotCodeId { #minSize 1 #maxSize 128 #regex "^[a-zA-Z_][a-zA-Z0-9_]*$" } :String
          }

          :package :org/stvnadore/prelude/meta {
            :use [ :org/stvnadore/prelude { #strip } ]

            :StvnPosixId64 { #maxSize 64 } :PosixId
            :StvnDocumentKind :Enum [ #BODY #DEFS #BODY_INCLUDE #DEFS_INCLUDE ]
            :StvnDocumentMeta :Tuple( :Option(:StvnPosixId64) :Option(:StvnPosixId64) :StvnDocumentKind )
            :StvnCatalogMeta :Tuple( :StvnPosixId64 :StvnPosixId64 :StvnDocumentKind )
          }
        }
      }""";

  /**
   * Backward-compatibility alias for downstream tooling referencing the legacy field name.
   */
  public static final String PRELUDE_STVN_INCLF = PRELUDE_STVN_D;

  private StvnPrelude() {}

  private static final StvnParser.StvnDocumentContext CACHED_STVN_DOCUMENT_CONTEXT_PRELUDE =
    new StvnParser(
        new CommonTokenStream(
            new StvnLexer(CharStreams.fromString(PRELUDE_STVN_D))))
        .stvnDocument();

  /**
   * Returns the parsed ANTLR document context of the standard library prelude.
   * <p>
   * This is used internally to resolve standard types when no custom context overrides them.
   *
   * @return the non-null {@link org.stvnadore.core.parser.StvnParser.StvnDocumentContext} for the prelude
   */
  public static StvnParser.StvnDocumentContext getPreludeDocument() {
    return CACHED_STVN_DOCUMENT_CONTEXT_PRELUDE;
  }
}
