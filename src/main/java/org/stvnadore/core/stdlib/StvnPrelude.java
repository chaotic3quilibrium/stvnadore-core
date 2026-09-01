package org.stvnadore.core.stdlib;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.parser.StvnLexer;
import org.stvnadore.core.parser.StvnParser;

/**
 * Static registry for standard library types implicitly available in all STVN context environments.
 * <p>
 * Contains definitions for common nominal types such as {@code :Uuid}, {@code :Ulid}, {@code :IPv4},
 * {@code :Port}, {@code :Percentage}, and {@code :Currency}.
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
          :Uuid { #regex "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$" } :StringFixed36
          :Ulid { #regex "^[0-7][0-9A-HJKMNP-TV-Z]{25}$" } :StringFixed26
          :Sha256 { #regex "^[0-9a-fA-F]{64}$" } :StringFixed64
          :SemVer { #regex "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-((?:0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-zA-Z0-9-]*)(?:\\.(?:0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-zA-Z0-9-]*))*))?(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$" } :String
      
          :Email { #regex "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$" } :String
          :IPv4  { #regex "^((25[0-5]|(2[0-4]|1[0-9]|[1-9]|)[0-9])\\.?\\b){4}$" } :String
          :Port  { #minIncl 1 #maxIncl 65535 } :Uint16
      
          :Percentage  { #minIncl 0.0 #maxIncl 100.0 } :Float64
          :Probability { #minIncl 0.0 #maxIncl 1.0 }   :Float64
          :Currency :FloatExact
          :Latitude    { #minIncl -90.0 #maxIncl 90.0 }   :Float64
          :Longitude   { #minIncl -180.0 #maxIncl 180.0 } :Float64
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
