package org.stvnadore.core.matrix;

import org.jspecify.annotations.NullMarked;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Ingestion utility for parsing and validating the canonical STVN metadata matrix TSV file.
 * <p>
 * Enforces strict 10-column structural validation, invariant checks on positions and categories,
 * and yields unmodifiable lists of {@link MetadataMatrixRow}.
 */
@NullMarked
public final class MetadataMatrixReader {

  public static final String DEFAULT_MATRIX_RESOURCE_PATH =
      "metadata-matrix/stvn_2_0_0_metadata_matrix.tsv";

  public static final int EXPECTED_COLUMN_COUNT = 10;

  public static final List<String> EXPECTED_HEADERS = List.of(
      "Position",
      "Category",
      "Modifying",
      "Tag Name",
      "Parameter Kind",
      "Parameter Range",
      "Default",
      "Overridable",
      "Mutually Exclusive to",
      "Description"
  );

  private static final Set<String> VALID_CATEGORIES = Set.of(
      "Definition",
      "Trait",
      "Bounds",
      "Constraint",
      "Directive"
  );

  private MetadataMatrixReader() {
    // Pure utility class
  }

  /**
   * Reads the canonical matrix from the classpath default resource path or repository file system fallback.
   *
   * @return unmodifiable list of validated rows
   * @throws IllegalStateException if resource is missing or contains invalid lines
   */
  public static List<MetadataMatrixRow> readDefaultMatrix() {
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    InputStream stream = cl != null ? cl.getResourceAsStream(DEFAULT_MATRIX_RESOURCE_PATH) : null;
    if (stream == null) {
      stream = MetadataMatrixReader.class.getClassLoader().getResourceAsStream(DEFAULT_MATRIX_RESOURCE_PATH);
    }
    if (stream != null) {
      try (InputStream autoClosed = stream) {
        return readFromStream(autoClosed);
      } catch (IOException e) {
        throw new IllegalStateException("Failed to read metadata matrix resource", e);
      }
    }
    // Fallback for file system execution
    Path fsPath = Path.of("src/test/resources", DEFAULT_MATRIX_RESOURCE_PATH);
    if (Files.exists(fsPath)) {
      return readFromPath(fsPath);
    }
    throw new IllegalStateException("Canonical metadata matrix resource not found: " + DEFAULT_MATRIX_RESOURCE_PATH);
  }

  /**
   * Reads the matrix from a specific file system path.
   *
   * @param path target TSV file path
   * @return unmodifiable list of validated rows
   */
  public static List<MetadataMatrixRow> readFromPath(Path path) {
    Objects.requireNonNull(path, "path must not be null");
    try (InputStream is = Files.newInputStream(path)) {
      return readFromStream(is);
    } catch (IOException e) {
      throw new IllegalArgumentException("Failed to read metadata matrix file: " + path, e);
    }
  }

  /**
   * Ingests and validates the TSV input stream line-by-line.
   *
   * @param stream input stream containing UTF-8 TSV data
   * @return unmodifiable list of validated rows
   */
  public static List<MetadataMatrixRow> readFromStream(InputStream stream) {
    Objects.requireNonNull(stream, "stream must not be null");
    List<MetadataMatrixRow> rows = new ArrayList<>(100);

    try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      String headerLine = reader.readLine();
      if (headerLine == null) {
        throw new IllegalArgumentException("Metadata matrix file is empty");
      }
      validateHeader(headerLine);

      String line;
      int lineNum = 1;
      while ((line = reader.readLine()) != null) {
        lineNum++;
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        rows.add(parseLine(line, lineNum));
      }
    } catch (IOException e) {
      throw new RuntimeException("I/O failure reading metadata matrix stream", e);
    }

    return Collections.unmodifiableList(rows);
  }

  private static void validateHeader(String headerLine) {
    String[] parts = headerLine.split("\t", -1);
    if (parts.length != EXPECTED_COLUMN_COUNT) {
      throw new IllegalArgumentException(
          "Invalid header column count. Expected " + EXPECTED_COLUMN_COUNT + ", found " + parts.length);
    }
    for (int i = 0; i < EXPECTED_COLUMN_COUNT; i++) {
      String expected = EXPECTED_HEADERS.get(i);
      String actual = parts[i].trim();
      if (!expected.equalsIgnoreCase(actual)) {
        throw new IllegalArgumentException(
            "Header mismatch at column " + (i + 1) + ". Expected '" + expected + "', found '" + actual + "'");
      }
    }
  }

  /**
   * Parses a single TSV line into a {@link MetadataMatrixRow}.
   */
  public static MetadataMatrixRow parseLine(String line, int lineNum) {
    String[] cols = line.split("\t", -1);
    if (cols.length != EXPECTED_COLUMN_COUNT) {
      throw new IllegalArgumentException(
          "Row at line " + lineNum + " has " + cols.length + " columns; expected " + EXPECTED_COLUMN_COUNT);
    }

    String position = cols[0].trim();
    String category = cols[1].trim();
    String modifying = cols[2].trim();
    String tagName = cols[3].trim();
    String parameterKind = cols[4].trim();
    String parameterRange = cols[5].trim();
    String defaultValue = cols[6].trim();
    String overridableStr = cols[7].trim();
    String mutuallyExclusiveTo = cols[8].trim();
    String description = cols[9].trim();

    if (!position.matches("^[1-5](\\.[0-9]+)*$")) {
      throw new IllegalArgumentException("Line " + lineNum + ": Invalid position format: " + position);
    }
    if (!VALID_CATEGORIES.contains(category)) {
      throw new IllegalArgumentException("Line " + lineNum + ": Unknown category: " + category);
    }
    if (!"Yes".equalsIgnoreCase(overridableStr) && !"No".equalsIgnoreCase(overridableStr)) {
      throw new IllegalArgumentException("Line " + lineNum + ": Overridable must be 'Yes' or 'No', found: " + overridableStr);
    }
    boolean overridable = "Yes".equalsIgnoreCase(overridableStr);

    return new MetadataMatrixRow(
        position,
        category,
        modifying,
        tagName,
        parameterKind,
        parameterRange,
        defaultValue,
        overridable,
        mutuallyExclusiveTo,
        description
    );
  }
}
