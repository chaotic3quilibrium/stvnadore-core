package org.stvnadore.core.printer;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.parser.StvnParser;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;

/**
 * Core interface for printing STVN value trees to text outputs.
 * <p>
 * Implementations define the visual representation and formatting of STVN structures
 * (such as compact text versus pretty printed text formats).
 * </p>
 */
@NullMarked
public interface StvnTextPrinter {

  /**
   * Prints the given STVN value tree to the specified character stream destination.
   *
   * @param value  the STVN value tree to serialize
   * @param target the target writer destination
   * @throws IOException if an I/O error occurs during printing
   */
  void print(StvnValue value, Writer target) throws IOException;

  /**
   * Prints the given STVN value tree and returns its text representation as a string.
   *
   * @param value the STVN value tree to print
   * @return the serialized string representation of the value
   */
  default String printToString(StvnValue value) {
    var sw = new StringWriter();
    try {
      print(value, sw);
    } catch (IOException e) {
      throw new RuntimeException("Unreachable IOException in StringWriter", e);
    }
    return sw.toString();
  }

  /**
   * Translates an ANTLR collection type context into its corresponding STVN schema keyword.
   *
   * @param col the ANTLR context representing the collection type
   * @return the schema type keyword string (e.g. {@code :Seq}, {@code :MapNonEmpty})
   */
  @SuppressWarnings("ConstantValue")
  default String resolveCollectionType(StvnParser.CollectionTypeContext col) {
    var colType = "";
    if (col.COLL_SEQ() != null) colType = ":Seq";
    else if (col.COLL_SEQ_NON_EMPTY() != null) colType = ":SeqNonEmpty";
    else if (col.COLL_SET() != null) colType = ":Set";
    else if (col.COLL_SET_NON_EMPTY() != null) colType = ":SetNonEmpty";
    else if (col.COLL_MAP() != null) colType = ":Map";
    else if (col.COLL_MAP_NON_EMPTY() != null) colType = ":MapNonEmpty";
    else if (col.COLL_MAP_INV() != null) colType = ":MapInv";
    else if (col.COLL_MAP_INV_NON_EMPTY() != null) colType = ":MapInvNonEmpty";

    return colType;
  }
}
