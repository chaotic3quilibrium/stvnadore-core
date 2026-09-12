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
 * {@code :org/stvnadore/prelude/Percentage}, and {@code :org/stvnadore/prelude/Currency}.
 *
 * @since 1.0.0
 */
@NullMarked
public final class StvnPrelude {

  /**
   * The raw STVN source string defining the standard library prelude.
   */
  public static final String PRELUDE_STVN_INCLF = """
      {
        //File:        prelude.stvn_inclf
        //Description: Made available implicitly in every .stvn* file
        //Version:     0.2.0
        //Date:        2026.05.30
        :defs {
          :org/stvnadore/prelude/Uuid { #regex "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$" } :StringFixed36
          :org/stvnadore/prelude/Ulid { #regex "^[0-7][0-9A-HJKMNP-TV-Z]{25}$" } :StringFixed26
          :org/stvnadore/prelude/Sha256 { #regex "^[0-9a-fA-F]{64}$" } :StringFixed64
          :org/stvnadore/prelude/SemVer { #regex "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-((?:0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-zA-Z0-9-]*)(?:\\.(?:0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-zA-Z0-9-]*))*))?(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$" } :String

          :org/stvnadore/prelude/Email { #regex "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$" } :String
          :org/stvnadore/prelude/IPv4  { #regex "^((25[0-5]|(2[0-4]|1[0-9]|[1-9]|)[0-9])\\.?\\b){4}$" } :String
          :org/stvnadore/prelude/Port  { #minIncl 1 #maxIncl 65535 } :Uint16

          :org/stvnadore/prelude/Percentage  { #minIncl 0.0 #maxIncl 100.0 } :Float64
          :org/stvnadore/prelude/Probability { #minIncl 0.0 #maxIncl 1.0 }   :Float64
          :org/stvnadore/prelude/Currency :FloatExact
          :org/stvnadore/prelude/Latitude    { #minIncl -90.0 #maxIncl 90.0 }   :Float64
          :org/stvnadore/prelude/Longitude   { #minIncl -180.0 #maxIncl 180.0 } :Float64

          :org/stvnadore/prelude/TimeEpochS :Int64
          :org/stvnadore/prelude/TimeEpochMs :Int64
          :org/stvnadore/prelude/TimeEpochNs :Int128
          :org/stvnadore/prelude/DateTimeOffset  { #regex "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]+)?)?(Z|[+-][0-9]{2}:[0-9]{2})$" } :String
          :org/stvnadore/prelude/DateTimeZoned   { #regex "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]+)?)?\\[[A-Za-z0-9_\\-+]+(/[A-Za-z0-9_\\-+]+)*\\]$" } :String
          :org/stvnadore/prelude/DateTimeAudited { #regex "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]+)?)?(Z|[+-][0-9]{2}:[0-9]{2})\\[[A-Za-z0-9_\\-+]+(/[A-Za-z0-9_\\-+]+)*\\]$" } :String
        }
      }""";

  private StvnPrelude() {}

  private static final StvnParser.StvnDocumentContext CACHED_STVN_DOCUMENT_CONTEXT_PRELUDE =
    new StvnParser(
        new CommonTokenStream(
            new StvnLexer(CharStreams.fromString(PRELUDE_STVN_INCLF))))
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
