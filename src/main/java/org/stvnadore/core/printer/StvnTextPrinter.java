package org.stvnadore.core.printer;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.ir.StvnValue;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;

/**
 * Core interface for printing pure-IR STVN value trees to text outputs.
 * <p>
 * Implementations define the visual representation and formatting of STVN structures
 * (such as canonical, compact, or pretty-printed text formats).
 * </p>
 */
@NullMarked
public interface StvnTextPrinter {

  /**
   * Prints the given pure-IR STVN value tree to the specified character stream destination.
   *
   * @param value  the pure-IR STVN value tree to serialize
   * @param target the target writer destination
   * @throws IOException if an I/O error occurs during printing
   */
  void print(StvnValue value, Writer target) throws IOException;

  /**
   * Prints the given pure-IR STVN value tree and returns its text representation as a string.
   *
   * @param value the pure-IR STVN value tree to print
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
}
