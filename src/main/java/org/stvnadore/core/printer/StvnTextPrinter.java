package org.stvnadore.core.printer;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ast.StvnValue;
import org.stvnadore.core.lowering.LegacyAstBridge;
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
   * Prints the given pure-IR STVN value tree to the specified character stream destination.
   *
   * @param value  the pure-IR STVN value tree to serialize
   * @param target the target writer destination
   * @throws IOException if an I/O error occurs during printing
   * @since 2.0.0
   */
  default void print(org.stvnadore.core.ir.StvnValue value, Writer target) throws IOException {
    print(LegacyAstBridge.toLegacyValue(value), target);
  }

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
   * Prints the given pure-IR STVN value tree and returns its text representation as a string.
   *
   * @param value the pure-IR STVN value tree to print
   * @return the serialized string representation of the value
   * @since 2.0.0
   */
  default String printToString(org.stvnadore.core.ir.StvnValue value) {
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
   * @return the schema type keyword string
   */
  default String resolveCollectionType(StvnParser.CollectionTypeContext col) {
    var colType = "";
    if (col.COLL_SEQ() != null) colType = StvnVocabulary.TYPE_SEQ;
    else if (col.COLL_SET() != null) colType = StvnVocabulary.TYPE_SET;
    else if (col.COLL_MAP() != null) colType = StvnVocabulary.TYPE_MAP;

    return colType;
  }
}
