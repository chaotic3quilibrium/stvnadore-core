package org.stvnadore.core.matrix;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Deterministic generator and file emitter for STVN metadata test fixtures.
 * <p>
 * Generates three categories of fixtures:
 * <ol>
 *   <li><b>Isolated Valid Fixtures:</b> Single facet tests adhering to compiler allowlists</li>
 *   <li><b>Saturated Valid Fixtures:</b> Multi-facet compositions adhering to canonical 5-tier ordering</li>
 *   <li><b>Invalid Negative Fixtures:</b> Edge cases covering domain mismatches, order violations,
 *       discrete bound prohibitions, mutual exclusivity, and interval soundness</li>
 * </ol>
 */
@NullMarked
public final class MetadataFixtureGenerator {

  public static final Path DEFAULT_OUTPUT_ROOT =
      Path.of("shared-fixtures/metadata");

  /**
   * Set of TSV type/tag combinations prohibited by the current compiler implementation.
   * Empty in STVN 2.0.0 as all 83 matrix rows are fully permitted and supported.
   */
  public static final Set<String> PROHIBITED_MATRIX_FACETS = Set.of();

  /**
   * Fixture coverage classification taxonomy.
   */
  public enum CoverageKind {
    ISOLATED_VALID,
    SATURATED_VALID,
    OPEN_UPPER_BOUND,
    TARGET_DOMAIN_MISMATCH,
    ORDER_VIOLATION,
    DISCRETE_BOUND_PROHIBITED,
    MUTUALLY_EXCLUSIVE,
    INTERVAL_SOUNDNESS,
    DUPLICATE_FACET
  }

  /**
   * Immutable record representing a generated STVN fixture.
   *
   * @param relativePath       relative subpath within the metadata fixture root
   * @param content            complete STVN source text with header annotations
   * @param kind               classification of test coverage
   * @param expectedDiagnostic expected diagnostic error code for invalid fixtures
   * @param targetToken        expected offending token pinned in diagnostic coordinates
   */
  public record GeneratedFixture(
      Path relativePath,
      String content,
      CoverageKind kind,
      Optional<String> expectedDiagnostic,
      Optional<String> targetToken
  ) {
    public GeneratedFixture {
      Objects.requireNonNull(relativePath, "relativePath must not be null");
      Objects.requireNonNull(content, "content must not be null");
      Objects.requireNonNull(kind, "kind must not be null");
      Objects.requireNonNull(expectedDiagnostic, "expectedDiagnostic must not be null");
      Objects.requireNonNull(targetToken, "targetToken must not be null");
    }
  }

  private MetadataFixtureGenerator() {
    // Utility / Generator entry point
  }

  /**
   * CLI entry point to materialize all fixtures to disk.
   *
   * @param args optional first argument specifies target output root path
   * @throws IOException if disk serialization fails
   */
  public static void main(String[] args) throws IOException {
    Path outputDir = args.length > 0 ? Path.of(args[0]) : DEFAULT_OUTPUT_ROOT;
    System.out.println("Starting Metadata Fixture Generation Pipeline...");
    List<MetadataMatrixRow> rows = MetadataMatrixReader.readDefaultMatrix();
    Map<Path, GeneratedFixture> fixtures = generateAllFixturesInMemory(rows);
    materializeFixturesToDirectory(outputDir, fixtures);
    System.out.printf("Successfully generated and materialized %d fixtures under %s%n",
        fixtures.size(), outputDir.toAbsolutePath());
  }

  /**
   * Computes the complete map of relative paths to generated fixtures in memory.
   *
   * @param rows parsed matrix rows from canonical TSV
   * @return deterministic sorted map of relative paths to fixtures
   */
  public static Map<Path, GeneratedFixture> generateAllFixturesInMemory(List<MetadataMatrixRow> rows) {
    Map<Path, GeneratedFixture> map = new TreeMap<>(Comparator.comparing(Path::toString));

    // 1. Isolated Valid Fixtures
    for (GeneratedFixture f : generateIsolatedValidFixtures(rows)) {
      map.put(f.relativePath(), f);
    }
    // 2. Saturated Valid Fixtures
    for (GeneratedFixture f : generateSaturatedValidFixtures(rows)) {
      map.put(f.relativePath(), f);
    }
    // 3. Open Upper Bound Valid Fixtures
    for (GeneratedFixture f : generateOpenUpperBoundFixtures(rows)) {
      map.put(f.relativePath(), f);
    }
    // 4. Invalid Negative Fixtures
    for (GeneratedFixture f : generateInvalidEdgeCaseFixtures(rows)) {
      map.put(f.relativePath(), f);
    }

    return Collections.unmodifiableMap(map);
  }

  /**
   * Generates isolated valid fixtures for all permitted matrix rows.
   * Also generates the supporting include helper fixture {@code empty_module.stvn_d}.
   *
   * @param rows parsed canonical matrix rows
   * @return list of isolated valid fixtures
   */
  public static List<GeneratedFixture> generateIsolatedValidFixtures(List<MetadataMatrixRow> rows) {
    List<GeneratedFixture> list = new ArrayList<>();

    // Supporting include module fixture
    String helperContent = """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         empty_module
        // COVERAGE-KIND:      ISOLATED_VALID
        // DESCRIPTION:        Helper include module declaring a nominal type for :include
        // ==============================================================================
        {
          :meta {
            #name "empty_module"
            #kind #DEFS
          }
          :defs {
            :IncludeType :Int
          }
        }
        """;
    list.add(new GeneratedFixture(
        Path.of("valid", "isolated", "empty_module.stvn_d"),
        helperContent,
        CoverageKind.ISOLATED_VALID,
        Optional.empty(),
        Optional.empty()
    ));

    for (MetadataMatrixRow row : rows) {
      String key = row.cleanBaseType() + "/" + row.tagName();
      if (PROHIBITED_MATRIX_FACETS.contains(key)) {
        continue;
      }
      if (row.cleanBaseType().startsWith(":include") || row.cleanBaseType().startsWith(":use")) {
        list.add(generateDirectiveIsolatedFixture(row));
      } else {
        list.add(generateTypeIsolatedFixture(row));
      }
    }
    return list;
  }

  /**
   * Generates saturated valid fixtures combining permitted facets in canonical 5-tier sequence.
   *
   * @param rows parsed canonical matrix rows
   * @return list of saturated valid fixtures
   */
  public static List<GeneratedFixture> generateSaturatedValidFixtures(List<MetadataMatrixRow> rows) {
    List<GeneratedFixture> list = new ArrayList<>();
    list.addAll(createSaturatedScalarFixtures());
    list.addAll(createSaturatedTemporalFixtures());
    list.addAll(createSaturatedCollectionFixtures());
    return list;
  }

  /**
   * Generates valid open upper bound fixtures testing values exceeding legacy or default ceilings.
   * Targets subpath shared-fixtures/metadata/valid/open_bounds/.
   *
   * @param rows parsed canonical matrix rows
   * @return list of valid open upper bound fixtures
   */
  public static List<GeneratedFixture> generateOpenUpperBoundFixtures(List<MetadataMatrixRow> rows) {
    List<GeneratedFixture> list = new ArrayList<>();

    // 1. :Int #size 1025
    list.add(new GeneratedFixture(
        Path.of("valid", "open_bounds", "val_open_int_size_1025.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         val_open_int_size_1025
        // MATRIX-POSITION:    1.3.1
        // MATRIX-CATEGORY:    Definition
        // TARGET-TYPE:        :Int
        // FACET-NAME:         #size
        // COVERAGE-KIND:      OPEN_UPPER_BOUND
        // DESCRIPTION:        Verifies open upper bound on :Int #size exceeding legacy 1024 ceiling (size = 1025)
        // ==============================================================================
        {
          :defs {
            :TestedType { #size 1025 } :Int
          }
          :type :TestedType
          :body 0
        }
        """,
        CoverageKind.OPEN_UPPER_BOUND,
        Optional.empty(),
        Optional.empty()
    ));

    // 2. :Int #size 4096
    list.add(new GeneratedFixture(
        Path.of("valid", "open_bounds", "val_open_int_size_4096.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         val_open_int_size_4096
        // MATRIX-POSITION:    1.3.1
        // MATRIX-CATEGORY:    Definition
        // TARGET-TYPE:        :Int
        // FACET-NAME:         #size
        // COVERAGE-KIND:      OPEN_UPPER_BOUND
        // DESCRIPTION:        Verifies open upper bound on :Int #size exceeding legacy 1024 ceiling (size = 4096)
        // ==============================================================================
        {
          :defs {
            :TestedType { #size 4096 } :Int
          }
          :type :TestedType
          :body 0
        }
        """,
        CoverageKind.OPEN_UPPER_BOUND,
        Optional.empty(),
        Optional.empty()
    ));

    // 3. :String #maxSize 33554432 (32 MiB)
    list.add(new GeneratedFixture(
        Path.of("valid", "open_bounds", "val_open_string_maxsize_32m.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         val_open_string_maxsize_32m
        // MATRIX-POSITION:    1.5.2
        // MATRIX-CATEGORY:    Definition
        // TARGET-TYPE:        :String
        // FACET-NAME:         #maxSize
        // COVERAGE-KIND:      OPEN_UPPER_BOUND
        // DESCRIPTION:        Verifies open upper bound on :String #maxSize exceeding 16 MiB default ceiling (maxSize = 33554432)
        // ==============================================================================
        {
          :defs {
            :TestedType { #maxSize 33554432 } :String
          }
          :type :TestedType
          :body "alpha"
        }
        """,
        CoverageKind.OPEN_UPPER_BOUND,
        Optional.empty(),
        Optional.empty()
    ));

    // 4. :Seq #maxSize 33554432 (32 MiB)
    list.add(new GeneratedFixture(
        Path.of("valid", "open_bounds", "val_open_seq_maxsize_32m.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         val_open_seq_maxsize_32m
        // MATRIX-POSITION:    1.5.2
        // MATRIX-CATEGORY:    Definition
        // TARGET-TYPE:        :Seq
        // FACET-NAME:         #maxSize
        // COVERAGE-KIND:      OPEN_UPPER_BOUND
        // DESCRIPTION:        Verifies open upper bound on :Seq #maxSize exceeding 16 MiB default ceiling (maxSize = 33554432)
        // ==============================================================================
        {
          :defs {
            :TestedType { #maxSize 33554432 } :Seq( :Int )
          }
          :type :TestedType
          :body [ 1 2 3 ]
        }
        """,
        CoverageKind.OPEN_UPPER_BOUND,
        Optional.empty(),
        Optional.empty()
    ));

    // 5. :Set #maxSize 33554432 (32 MiB)
    list.add(new GeneratedFixture(
        Path.of("valid", "open_bounds", "val_open_set_maxsize_32m.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         val_open_set_maxsize_32m
        // MATRIX-POSITION:    1.5.2
        // MATRIX-CATEGORY:    Definition
        // TARGET-TYPE:        :Set
        // FACET-NAME:         #maxSize
        // COVERAGE-KIND:      OPEN_UPPER_BOUND
        // DESCRIPTION:        Verifies open upper bound on :Set #maxSize exceeding 16 MiB default ceiling (maxSize = 33554432)
        // ==============================================================================
        {
          :defs {
            :TestedType { #maxSize 33554432 } :Set( :Int )
          }
          :type :TestedType
          :body [ 1 2 3 ]
        }
        """,
        CoverageKind.OPEN_UPPER_BOUND,
        Optional.empty(),
        Optional.empty()
    ));

    // 6. :Map #maxSize 33554432 (32 MiB)
    list.add(new GeneratedFixture(
        Path.of("valid", "open_bounds", "val_open_map_maxsize_32m.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         val_open_map_maxsize_32m
        // MATRIX-POSITION:    1.5.2
        // MATRIX-CATEGORY:    Definition
        // TARGET-TYPE:        :Map
        // FACET-NAME:         #maxSize
        // COVERAGE-KIND:      OPEN_UPPER_BOUND
        // DESCRIPTION:        Verifies open upper bound on :Map #maxSize exceeding 16 MiB default ceiling (maxSize = 33554432)
        // ==============================================================================
        {
          :defs {
            :TestedType { #maxSize 33554432 } :Map( :String :Int )
          }
          :type :TestedType
          :body { [ "k" 1 ] }
        }
        """,
        CoverageKind.OPEN_UPPER_BOUND,
        Optional.empty(),
        Optional.empty()
    ));

    return list;
  }

  /**
   * Generates invalid negative fixtures for domain mismatches, order violations, discrete bound prohibitions,
   * mutual exclusivity, and interval soundness.
   *
   * @param rows parsed canonical matrix rows
   * @return list of invalid edge case fixtures
   */
  public static List<GeneratedFixture> generateInvalidEdgeCaseFixtures(List<MetadataMatrixRow> rows) {
    List<GeneratedFixture> list = new ArrayList<>();
    list.addAll(generateDomainMismatchFixtures(rows));
    list.addAll(generateOrderViolationFixtures(rows));
    list.addAll(generateDiscreteBoundProhibitionFixtures(rows));
    list.addAll(generateMutuallyExclusiveFixtures(rows));
    list.addAll(generateIntervalSoundnessFixtures(rows));
    list.addAll(generateDuplicateFacetFixtures(rows));
    return list;
  }

  /**
   * Materializes fixtures into physical files on disk under the target root directory.
   *
   * @param rootDir  destination directory root
   * @param fixtures map of relative paths to generated fixtures
   * @throws IOException if directory creation or file writing fails
   */
  public static void materializeFixturesToDirectory(Path rootDir, Map<Path, GeneratedFixture> fixtures) throws IOException {
    for (Map.Entry<Path, GeneratedFixture> entry : fixtures.entrySet()) {
      Path target = rootDir.resolve(entry.getKey());
      Path parent = target.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(target, entry.getValue().content(), StandardCharsets.UTF_8);
    }
  }

  // --- Internal Generation Helpers ---

  private static GeneratedFixture generateTypeIsolatedFixture(MetadataMatrixRow row) {
    String fileName = "val_iso_" + sanitizeName(row.modifying()) + "_" + sanitizeName(row.tagName()) + ".stvn";
    Path relPath = Path.of("valid", "isolated", fileName);

    String facetSyntax = formatIsolatedFacetSyntax(row);
    String defPrefix = computeDefPrefix(row);
    String defSuffix = computeDefSuffix(row);
    String typeCtor = resolveBaseConstructor(row);
    String sampleBody = sampleValueForType(row);

    String defsBlock;
    if (":Enum".equals(row.cleanBaseType()) && ("filterIncl".equals(row.tagName()) || "filterExcl".equals(row.tagName()))) {
      defsBlock = """
            :BaseEnum :Enum [ #VAL_A #VAL_B ]
            :TestedType { %s } :BaseEnum
          """.formatted(facetSyntax).stripTrailing();
    } else {
      defsBlock = """
            :TestedType { %s%s%s } %s
          """.formatted(defPrefix, facetSyntax, defSuffix, typeCtor).stripTrailing();
    }

    String content = """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         %s
        // MATRIX-POSITION:    %s
        // MATRIX-CATEGORY:    %s
        // TARGET-TYPE:        %s
        // FACET-NAME:         #%s
        // COVERAGE-KIND:      ISOLATED_VALID
        // DESCRIPTION:        %s
        // ==============================================================================
        {
          :defs {
        %s
          }
          :type :TestedType
          :body %s
        }
        """.formatted(
        fileName.replace(".stvn", ""),
        row.position(),
        row.category(),
        row.modifying(),
        row.tagName(),
        row.description(),
        defsBlock,
        sampleBody
    );

    return new GeneratedFixture(relPath, content, CoverageKind.ISOLATED_VALID, Optional.empty(), Optional.empty());
  }

  private static GeneratedFixture generateDirectiveIsolatedFixture(MetadataMatrixRow row) {
    String fileName = "val_iso_" + sanitizeName(row.cleanBaseType()) + "_" + sanitizeName(row.tagName()) + ".stvn";
    Path relPath = Path.of("valid", "isolated", fileName);
    boolean isInclude = row.cleanBaseType().contains("include");

    String defsContent = isInclude
        ? "    :include [ \"empty_module.stvn_d\" { #strip } ]"
        : "    :package :PkgA { :TestedType :Int }\n    :use [ :PkgA { #strip } ]";
    String testedType = isInclude ? ":IncludeType" : ":TestedType";
    String bodyVal = "42";

    String content = """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         %s
        // MATRIX-POSITION:    %s
        // MATRIX-CATEGORY:    Directive
        // TARGET-TYPE:        %s
        // FACET-NAME:         #%s
        // COVERAGE-KIND:      ISOLATED_VALID
        // DESCRIPTION:        %s
        // ==============================================================================
        {
          :defs {
        %s
          }
          :type %s
          :body %s
        }
        """.formatted(
        fileName.replace(".stvn", ""),
        row.position(),
        row.modifying(),
        row.tagName(),
        row.description(),
        defsContent,
        testedType,
        bodyVal
    );

    return new GeneratedFixture(relPath, content, CoverageKind.ISOLATED_VALID, Optional.empty(), Optional.empty());
  }

  private static String computeDefPrefix(MetadataMatrixRow row) {
    String clean = row.cleanBaseType();
    String q = row.domainQualifier();
    if (":TimeEpoch".equals(clean)) {
      if (q != null) return "#" + q + " ";
      if (!row.isStandalone() && !"equatable".equals(row.tagName()) && !"comparable".equals(row.tagName())) {
        return "#s ";
      }
    } else if (":DateTime".equals(clean)) {
      if (q != null) return "#" + q + " ";
      if (!row.isStandalone()) return "#offset ";
    } else if (":Float".equals(clean)) {
      if ("exact".equals(q) && !"exact".equals(row.tagName())) return "#exact ";
      if ("32".equals(q) && !"size".equals(row.tagName()) && !"equatable".equals(row.tagName()) && !"comparable".equals(row.tagName())) {
        return "#size 32 ";
      }
      if ("64".equals(q) && !"size".equals(row.tagName()) && !"equatable".equals(row.tagName()) && !"comparable".equals(row.tagName())) {
        return "#size 64 ";
      }
    } else if (":Int".equals(clean)) {
      if ("unsigned".equals(q) && !"unsigned".equals(row.tagName())) return "#unsigned ";
    } else if (":Map".equals(clean)) {
      if ("invertible".equals(q)) return "#invertible ";
    }
    return "";
  }

  private static String computeDefSuffix(MetadataMatrixRow row) {
    String clean = row.cleanBaseType();
    String q = row.domainQualifier();
    if (":TimeEpoch".equals(clean) && q == null && !row.isStandalone()) {
      if ("equatable".equals(row.tagName()) || "comparable".equals(row.tagName())) {
        return " #s";
      }
    } else if (":Float".equals(clean)) {
      if ("32".equals(q) && ("equatable".equals(row.tagName()) || "comparable".equals(row.tagName()))) {
        return " #size 32";
      }
      if ("64".equals(q) && ("equatable".equals(row.tagName()) || "comparable".equals(row.tagName()))) {
        return " #size 64";
      }
    }
    return "";
  }

  private static String formatIsolatedFacetSyntax(MetadataMatrixRow row) {
    if (row.isStandalone()) {
      return "#" + row.tagName();
    }
    String kind = row.parameterKind();
    return switch (kind) {
      case ":Boolean" -> "#" + row.tagName() + " #TRUE"; // Emits valid boolean syntax; never emits literal "Derived"
      case ":Int" -> {
        if ("size".equals(row.tagName())) yield "#size 32";
        if ("minSize".equals(row.tagName())) yield "#minSize 1";
        if ("maxSize".equals(row.tagName())) yield "#maxSize 10";
        if ("minIncl".equals(row.tagName())) yield "#minIncl 0";
        if ("maxExcl".equals(row.tagName())) yield "#maxExcl 100";
        yield "#" + row.tagName() + " 1";
      }
      case ":Float (exact)" -> {
        if ("minIncl".equals(row.tagName())) yield "#minIncl 0.0";
        if ("minExcl".equals(row.tagName())) yield "#minExcl 0.0";
        if ("maxIncl".equals(row.tagName())) yield "#maxIncl 100.0";
        if ("maxExcl".equals(row.tagName())) yield "#maxExcl 100.0";
        yield "#" + row.tagName() + " 0.0";
      }
      case ":TimeEpoch" -> {
        if ("minIncl".equals(row.tagName())) yield "#minIncl 1000";
        if ("maxExcl".equals(row.tagName())) yield "#maxExcl 2000000000";
        yield "#" + row.tagName() + " 1000";
      }
      case ":DateTime" -> {
        String q = row.domainQualifier();
        if ("zoned".equals(q)) {
          if ("minIncl".equals(row.tagName())) yield "#minIncl \"2026-01-01T00:00:00Z\"";
          if ("maxExcl".equals(row.tagName())) yield "#maxExcl \"2027-01-01T00:00:00Z\"";
        } else if ("audited".equals(q)) {
          if ("minIncl".equals(row.tagName())) yield "#minIncl \"2026-06-01T00:00:00-05:00[America/Chicago]\"";
          if ("maxExcl".equals(row.tagName())) yield "#maxExcl \"2026-06-02T00:00:00-05:00[America/Chicago]\"";
        } else {
          if ("minIncl".equals(row.tagName())) yield "#minIncl \"2026-01-01T00:00:00Z\"";
          if ("maxExcl".equals(row.tagName())) yield "#maxExcl \"2027-01-01T00:00:00Z\"";
        }
        yield "#" + row.tagName() + " \"2026-01-01T00:00:00Z\"";
      }
      case ":String" -> "regex".equals(row.tagName()) ? "#regex \"^[a-z]+$\"" : "#" + row.tagName() + " \"alpha\"";
      case ":EnumValues" -> "#" + row.tagName() + " [ #VAL_A ]";
      default -> "#" + row.tagName();
    };
  }

  private static String resolveBaseConstructor(MetadataMatrixRow row) {
    String clean = row.cleanBaseType();
    return switch (clean) {
      case ":Seq" -> ":Seq( :Int )";
      case ":Set" -> ":Set( :Int )";
      case ":Map" -> ":Map( :String :Int )";
      case ":Tuple" -> ":Tuple( :Int :String )";
      case ":Enum" -> ":Enum [ #VAL_A #VAL_B ]";
      default -> clean;
    };
  }

  private static String sampleValueForType(MetadataMatrixRow row) {
    String clean = row.cleanBaseType();
    return switch (clean) {
      case ":Boolean" -> "#TRUE";
      case ":Int" -> "42";
      case ":Float" -> "3.14";
      case ":TimeEpoch" -> "1600000000";
      case ":DateTime" -> {
        String q = row.domainQualifier();
        if ("zoned".equals(q) || "zoned".equals(row.tagName())) yield "\"2026-06-01T12:00:00[Europe/Paris]\"";
        if ("audited".equals(q) || "audited".equals(row.tagName())) yield "\"2026-06-01T12:00:00-05:00[America/Chicago]\"";
        yield "\"2026-06-01T12:00:00Z\"";
      }
      case ":String" -> "\"alpha\"";
      case ":Enum" -> "filterExcl".equals(row.tagName()) ? "#VAL_B" : "#VAL_A";
      case ":Seq" -> "[ 1 2 3 ]";
      case ":Set" -> "[ 1 2 3 ]";
      case ":Map" -> "{ [ \"k\" 1 ] }";
      case ":Tuple" -> "( 42 \"alpha\" )";
      default -> "0";
    };
  }

  private static List<GeneratedFixture> createSaturatedScalarFixtures() {
    List<GeneratedFixture> list = new ArrayList<>();

    // 1. Boolean
    list.add(new GeneratedFixture(
        Path.of("valid", "saturated", "val_sat_boolean.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         val_sat_boolean
        // TARGET-TYPE:        :Boolean
        // COVERAGE-KIND:      SATURATED_VALID
        // DESCRIPTION:        Saturated boolean with #equatable trait
        // ==============================================================================
        {
          :defs {
            :SaturatedBoolean {
              #equatable #TRUE
            } :Boolean
          }
          :type :SaturatedBoolean
          :body #TRUE
        }
        """,
        CoverageKind.SATURATED_VALID, Optional.empty(), Optional.empty()));

    // 2. Int
    list.add(new GeneratedFixture(
        Path.of("valid", "saturated", "val_sat_int.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         val_sat_int
        // TARGET-TYPE:        :Int
        // COVERAGE-KIND:      SATURATED_VALID
        // DESCRIPTION:        Saturated signed integer with 5-tier canonical facet sequence
        // ==============================================================================
        {
          :defs {
            :SaturatedInt {
              #equatable #TRUE
              #comparable #TRUE
              #size 32
              #minIncl -1000
              #maxExcl 1000
            } :Int
          }
          :type :SaturatedInt
          :body 42
        }
        """,
        CoverageKind.SATURATED_VALID, Optional.empty(), Optional.empty()));

    // 3. Int (unsigned)
    list.add(new GeneratedFixture(
        Path.of("valid", "saturated", "val_sat_int_unsigned.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         val_sat_int_unsigned
        // TARGET-TYPE:        :Int (unsigned)
        // COVERAGE-KIND:      SATURATED_VALID
        // DESCRIPTION:        Saturated unsigned integer with 5-tier canonical facet sequence
        // ==============================================================================
        {
          :defs {
            :SaturatedUInt {
              #unsigned
              #equatable #TRUE
              #comparable #TRUE
              #size 32
              #minIncl 0
              #maxExcl 65536
            } :Int
          }
          :type :SaturatedUInt
          :body 1024
        }
        """,
        CoverageKind.SATURATED_VALID, Optional.empty(), Optional.empty()));

    // 4. Float (32)
    list.add(new GeneratedFixture(
        Path.of("valid", "saturated", "val_sat_float_32.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         val_sat_float_32
        // TARGET-TYPE:        :Float (32)
        // COVERAGE-KIND:      SATURATED_VALID
        // DESCRIPTION:        Saturated 32-bit floating point with 5-tier canonical facet sequence
        // ==============================================================================
        {
          :defs {
            :SaturatedFloat32 {
              #equatable #TRUE
              #comparable #TRUE
              #size 32
              #minIncl -1000.0
              #maxExcl 1000.0
            } :Float
          }
          :type :SaturatedFloat32
          :body 3.14
        }
        """,
        CoverageKind.SATURATED_VALID, Optional.empty(), Optional.empty()));

    // 5. Float (64)
    list.add(new GeneratedFixture(
        Path.of("valid", "saturated", "val_sat_float_64.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         val_sat_float_64
        // TARGET-TYPE:        :Float (64)
        // COVERAGE-KIND:      SATURATED_VALID
        // DESCRIPTION:        Saturated 64-bit floating point with 5-tier canonical facet sequence
        // ==============================================================================
        {
          :defs {
            :SaturatedFloat64 {
              #equatable #TRUE
              #comparable #TRUE
              #size 64
              #minIncl -1000.0
              #maxExcl 1000.0
            } :Float
          }
          :type :SaturatedFloat64
          :body 3.141592653589793
        }
        """,
        CoverageKind.SATURATED_VALID, Optional.empty(), Optional.empty()));

    // 6. Float (exact)
    list.add(new GeneratedFixture(
        Path.of("valid", "saturated", "val_sat_float_exact.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         val_sat_float_exact
        // TARGET-TYPE:        :Float (exact)
        // COVERAGE-KIND:      SATURATED_VALID
        // DESCRIPTION:        Saturated exact decimal float with closed/open bounds
        // ==============================================================================
        {
          :defs {
            :SaturatedFloatExact {
              #exact
              #equatable #TRUE
              #comparable #TRUE
              #minIncl 0.0
              #maxExcl 100.0
            } :Float
          }
          :type :SaturatedFloatExact
          :body 42.5
        }
        """,
        CoverageKind.SATURATED_VALID, Optional.empty(), Optional.empty()));

    // 7. String
    list.add(new GeneratedFixture(
        Path.of("valid", "saturated", "val_sat_string.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         val_sat_string
        // TARGET-TYPE:        :String
        // COVERAGE-KIND:      SATURATED_VALID
        // DESCRIPTION:        Saturated string with 5-tier canonical facet sequence
        // ==============================================================================
        {
          :defs {
            :SaturatedString {
              #preserveIndent #TRUE
              #equatable #TRUE
              #comparable #TRUE
              #minSize 2
              #maxSize 16
              #regex "^[a-z]+$"
            } :String
          }
          :type :SaturatedString
          :body "matrix"
        }
        """,
        CoverageKind.SATURATED_VALID, Optional.empty(), Optional.empty()));

    return list;
  }

  private static List<GeneratedFixture> createSaturatedTemporalFixtures() {
    List<GeneratedFixture> list = new ArrayList<>();

    // 1. TimeEpoch (ns)
    list.add(new GeneratedFixture(
        Path.of("valid", "saturated", "val_sat_time_epoch_ns.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         val_sat_time_epoch_ns
        // TARGET-TYPE:        :TimeEpoch (ns)
        // COVERAGE-KIND:      SATURATED_VALID
        // DESCRIPTION:        Saturated TimeEpoch nanoseconds with half-open bounds
        // ==============================================================================
        {
          :defs {
            :SaturatedEpochNs {
              #equatable #TRUE
              #comparable #TRUE
              #ns
              #minIncl 1000
              #maxExcl 2000000000
            } :TimeEpoch
          }
          :type :SaturatedEpochNs
          :body 1600000000
        }
        """,
        CoverageKind.SATURATED_VALID, Optional.empty(), Optional.empty()));

    // 2. TimeEpoch (us)
    list.add(new GeneratedFixture(
        Path.of("valid", "saturated", "val_sat_time_epoch_us.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         val_sat_time_epoch_us
        // TARGET-TYPE:        :TimeEpoch (us)
        // COVERAGE-KIND:      SATURATED_VALID
        // DESCRIPTION:        Saturated TimeEpoch microseconds with half-open bounds
        // ==============================================================================
        {
          :defs {
            :SaturatedEpochUs {
              #equatable #TRUE
              #comparable #TRUE
              #us
              #minIncl 1000
              #maxExcl 2000000000
            } :TimeEpoch
          }
          :type :SaturatedEpochUs
          :body 1600000000
        }
        """,
        CoverageKind.SATURATED_VALID, Optional.empty(), Optional.empty()));

    // 3. TimeEpoch (ms)
    list.add(new GeneratedFixture(
        Path.of("valid", "saturated", "val_sat_time_epoch_ms.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         val_sat_time_epoch_ms
        // TARGET-TYPE:        :TimeEpoch (ms)
        // COVERAGE-KIND:      SATURATED_VALID
        // DESCRIPTION:        Saturated TimeEpoch milliseconds with half-open bounds
        // ==============================================================================
        {
          :defs {
            :SaturatedEpochMs {
              #equatable #TRUE
              #comparable #TRUE
              #ms
              #minIncl 1000
              #maxExcl 2000000000
            } :TimeEpoch
          }
          :type :SaturatedEpochMs
          :body 1600000000
        }
        """,
        CoverageKind.SATURATED_VALID, Optional.empty(), Optional.empty()));

    // 4. TimeEpoch (s)
    list.add(new GeneratedFixture(
        Path.of("valid", "saturated", "val_sat_time_epoch_s.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         val_sat_time_epoch_s
        // TARGET-TYPE:        :TimeEpoch (s)
        // COVERAGE-KIND:      SATURATED_VALID
        // DESCRIPTION:        Saturated TimeEpoch seconds with half-open bounds
        // ==============================================================================
        {
          :defs {
            :SaturatedEpochS {
              #equatable #TRUE
              #comparable #TRUE
              #s
              #minIncl 1000
              #maxExcl 2000000000
            } :TimeEpoch
          }
          :type :SaturatedEpochS
          :body 1600000000
        }
        """,
        CoverageKind.SATURATED_VALID, Optional.empty(), Optional.empty()));

    // 5. DateTime (offset)
    list.add(new GeneratedFixture(
        Path.of("valid", "saturated", "val_sat_datetime_offset.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         val_sat_datetime_offset
        // TARGET-TYPE:        :DateTime (offset)
        // COVERAGE-KIND:      SATURATED_VALID
        // DESCRIPTION:        Saturated DateTime offset with half-open bounds
        // ==============================================================================
        {
          :defs {
            :SaturatedDateTimeOffset {
              #offset
              #equatable #TRUE
              #comparable #TRUE
              #minIncl "2026-01-01T00:00:00Z"
              #maxExcl "2027-01-01T00:00:00Z"
            } :DateTime
          }
          :type :SaturatedDateTimeOffset
          :body "2026-06-15T12:00:00Z"
        }
        """,
        CoverageKind.SATURATED_VALID, Optional.empty(), Optional.empty()));

    // 6. DateTime (zoned)
    list.add(new GeneratedFixture(
        Path.of("valid", "saturated", "val_sat_datetime_zoned.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         val_sat_datetime_zoned
        // TARGET-TYPE:        :DateTime (zoned)
        // COVERAGE-KIND:      SATURATED_VALID
        // DESCRIPTION:        Saturated DateTime zoned with IANA timezone ID
        // ==============================================================================
        {
          :defs {
            :SaturatedDateTimeZoned {
              #zoned
              #equatable #TRUE
              #comparable #TRUE
              #minIncl "2026-01-01T00:00:00Z"
              #maxExcl "2027-01-01T00:00:00Z"
            } :DateTime
          }
          :type :SaturatedDateTimeZoned
          :body "2026-06-15T12:00:00[Europe/Paris]"
        }
        """,
        CoverageKind.SATURATED_VALID, Optional.empty(), Optional.empty()));

    // 7. DateTime (audited)
    list.add(new GeneratedFixture(
        Path.of("valid", "saturated", "val_sat_datetime_audited.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         val_sat_datetime_audited
        // TARGET-TYPE:        :DateTime (audited)
        // COVERAGE-KIND:      SATURATED_VALID
        // DESCRIPTION:        Saturated DateTime audited with explicit offset and IANA timezone ID
        // ==============================================================================
        {
          :defs {
            :SaturatedDateTimeAudited {
              #audited
              #equatable #TRUE
              #comparable #TRUE
              #minIncl "2026-06-01T00:00:00-05:00[America/Chicago]"
              #maxExcl "2026-06-02T00:00:00-05:00[America/Chicago]"
            } :DateTime
          }
          :type :SaturatedDateTimeAudited
          :body "2026-06-01T12:00:00-05:00[America/Chicago]"
        }
        """,
        CoverageKind.SATURATED_VALID, Optional.empty(), Optional.empty()));

    return list;
  }

  private static List<GeneratedFixture> createSaturatedCollectionFixtures() {
    List<GeneratedFixture> list = new ArrayList<>();

    // 1. Enum
    list.add(new GeneratedFixture(
        Path.of("valid", "saturated", "val_sat_enum.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         val_sat_enum
        // TARGET-TYPE:        :Enum
        // COVERAGE-KIND:      SATURATED_VALID
        // DESCRIPTION:        Saturated Enum with traits and variant subset filtering
        // ==============================================================================
        {
          :defs {
            :BaseEnum :Enum [ #VAL_A #VAL_B #VAL_C ]
            :SaturatedEnum {
              #equatable #TRUE
              #comparable #TRUE
              #filterIncl [ #VAL_A #VAL_B ]
            } :BaseEnum
          }
          :type :SaturatedEnum
          :body #VAL_A
        }
        """,
        CoverageKind.SATURATED_VALID, Optional.empty(), Optional.empty()));

    // 2. Seq
    list.add(new GeneratedFixture(
        Path.of("valid", "saturated", "val_sat_seq.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         val_sat_seq
        // TARGET-TYPE:        :Seq
        // COVERAGE-KIND:      SATURATED_VALID
        // DESCRIPTION:        Saturated Sequence with traits and size boundaries
        // ==============================================================================
        {
          :defs {
            :SaturatedSeq {
              #equatable #TRUE
              #comparable #TRUE
              #minSize 1
              #maxSize 10
            } :Seq( :Int )
          }
          :type :SaturatedSeq
          :body [ 1 2 3 ]
        }
        """,
        CoverageKind.SATURATED_VALID, Optional.empty(), Optional.empty()));

    // 3. Set
    list.add(new GeneratedFixture(
        Path.of("valid", "saturated", "val_sat_set.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         val_sat_set
        // TARGET-TYPE:        :Set
        // COVERAGE-KIND:      SATURATED_VALID
        // DESCRIPTION:        Saturated Set with traits and size boundaries
        // ==============================================================================
        {
          :defs {
            :SaturatedSet {
              #equatable #TRUE
              #minSize 1
              #maxSize 10
            } :Set( :Int )
          }
          :type :SaturatedSet
          :body [ 1 2 3 ]
        }
        """,
        CoverageKind.SATURATED_VALID, Optional.empty(), Optional.empty()));

    // 4. Map
    list.add(new GeneratedFixture(
        Path.of("valid", "saturated", "val_sat_map_invertible.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         val_sat_map_invertible
        // TARGET-TYPE:        :Map
        // COVERAGE-KIND:      SATURATED_VALID
        // DESCRIPTION:        Saturated Map with #invertible, cardinality, and traits
        // ==============================================================================
        {
          :defs {
            :BijectiveMap {
              #invertible
              #equatable #TRUE
              #minSize 1
              #maxSize 10
            } :Map( :String :Int )
          }
          :type :BijectiveMap
          :body {
            [ "alpha" 1 ]
            [ "beta" 2 ]
          }
        }
        """,
        CoverageKind.SATURATED_VALID, Optional.empty(), Optional.empty()));

    return list;
  }

  private static List<GeneratedFixture> generateDomainMismatchFixtures(List<MetadataMatrixRow> rows) {
    List<GeneratedFixture> list = new ArrayList<>();

    // 1. #size on :String
    list.add(new GeneratedFixture(
        Path.of("invalid", "domain_mismatch", "inv_mismatch_string_size.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_mismatch_string_size
        // MATRIX-POSITION:    1.3.1
        // TARGET-TYPE:        :String
        // OFFENDING-FACET:    #size
        // COVERAGE-KIND:      TARGET_DOMAIN_MISMATCH
        // EXPECT-DIAGNOSTIC:  ERR_STRING_CARDINALITY_PROHIBITED
        // TARGET-TOKEN:       #size
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        Storage sizing #size is prohibited on :String
        // ==============================================================================
        {
          :defs {
            :BadString { #size 32 } :String
          }
          :type :BadString
          :body "hello"
        }
        """,
        CoverageKind.TARGET_DOMAIN_MISMATCH,
        Optional.of("ERR_STRING_CARDINALITY_PROHIBITED"),
        Optional.of("#size")
    ));

    // 2. #unsigned on :Float
    list.add(new GeneratedFixture(
        Path.of("invalid", "domain_mismatch", "inv_mismatch_float_unsigned.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_mismatch_float_unsigned
        // MATRIX-POSITION:    1.1.1
        // TARGET-TYPE:        :Float
        // OFFENDING-FACET:    #unsigned
        // COVERAGE-KIND:      TARGET_DOMAIN_MISMATCH
        // EXPECT-DIAGNOSTIC:  ERR_INVALID_METADATA_FACET
        // TARGET-TOKEN:       #unsigned
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        #unsigned is prohibited on :Float
        // ==============================================================================
        {
          :defs {
            :BadFloat { #unsigned } :Float
          }
          :type :BadFloat
          :body 3.14
        }
        """,
        CoverageKind.TARGET_DOMAIN_MISMATCH,
        Optional.of("ERR_INVALID_METADATA_FACET"),
        Optional.of("#unsigned")
    ));

    // 3. #regex on :Int
    list.add(new GeneratedFixture(
        Path.of("invalid", "domain_mismatch", "inv_mismatch_int_regex.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_mismatch_int_regex
        // MATRIX-POSITION:    4.4
        // TARGET-TYPE:        :Int
        // OFFENDING-FACET:    #regex
        // COVERAGE-KIND:      TARGET_DOMAIN_MISMATCH
        // EXPECT-DIAGNOSTIC:  ERR_INVALID_METADATA_FACET
        // TARGET-TOKEN:       #regex
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        #regex is prohibited on :Int
        // ==============================================================================
        {
          :defs {
            :BadInt { #regex "^[0-9]+$" } :Int
          }
          :type :BadInt
          :body 42
        }
        """,
        CoverageKind.TARGET_DOMAIN_MISMATCH,
        Optional.of("ERR_INVALID_METADATA_FACET"),
        Optional.of("#regex")
    ));

    // 4. #strip on type definition
    list.add(new GeneratedFixture(
        Path.of("invalid", "domain_mismatch", "inv_mismatch_typedef_strip.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_mismatch_typedef_strip
        // MATRIX-POSITION:    5
        // TARGET-TYPE:        :Int
        // OFFENDING-FACET:    #strip
        // COVERAGE-KIND:      TARGET_DOMAIN_MISMATCH
        // EXPECT-DIAGNOSTIC:  ERR_INVALID_METADATA_FACET
        // TARGET-TOKEN:       #strip
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        Directive facet #strip is prohibited on type declarations
        // ==============================================================================
        {
          :defs {
            :BadType { #strip } :Int
          }
          :type :BadType
          :body 0
        }
        """,
        CoverageKind.TARGET_DOMAIN_MISMATCH,
        Optional.of("ERR_INVALID_METADATA_FACET"),
        Optional.of("#strip")
    ));

    // 5. Negative zero #minIncl on :Int
    list.add(new GeneratedFixture(
        Path.of("invalid", "domain_mismatch", "inv_mismatch_int_negative_zero_minincl.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_mismatch_int_negative_zero_minincl
        // MATRIX-POSITION:    4.1
        // TARGET-TYPE:        :Int
        // OFFENDING-FACET:    #minIncl
        // COVERAGE-KIND:      TARGET_DOMAIN_MISMATCH
        // EXPECT-DIAGNOSTIC:  ERR_INVALID_NUMERIC_LITERAL
        // TARGET-TOKEN:       -0
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        Negative zero '-0' is prohibited for integer types
        // ==============================================================================
        {
          :defs {
            :BadZeroMin { #minIncl -0 } :Int
          }
          :type :BadZeroMin
          :body 0
        }
        """,
        CoverageKind.TARGET_DOMAIN_MISMATCH,
        Optional.of("ERR_INVALID_NUMERIC_LITERAL"),
        Optional.of("-0")
    ));

    // 6. Negative zero #maxExcl on :Int
    list.add(new GeneratedFixture(
        Path.of("invalid", "domain_mismatch", "inv_mismatch_int_negative_zero_maxexcl.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_mismatch_int_negative_zero_maxexcl
        // MATRIX-POSITION:    4.2
        // TARGET-TYPE:        :Int
        // OFFENDING-FACET:    #maxExcl
        // COVERAGE-KIND:      TARGET_DOMAIN_MISMATCH
        // EXPECT-DIAGNOSTIC:  ERR_INVALID_NUMERIC_LITERAL
        // TARGET-TOKEN:       -0
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        Negative zero '-0' is prohibited for integer types
        // ==============================================================================
        {
          :defs {
            :BadZeroMax { #minIncl -10 #maxExcl -0 } :Int
          }
          :type :BadZeroMax
          :body -5
        }
        """,
        CoverageKind.TARGET_DOMAIN_MISMATCH,
        Optional.of("ERR_INVALID_NUMERIC_LITERAL"),
        Optional.of("-0")
    ));

    return list;
  }

  private static List<GeneratedFixture> generateOrderViolationFixtures(List<MetadataMatrixRow> rows) {
    List<GeneratedFixture> list = new ArrayList<>();

    // 1. Tier 4 (Bounds) before Tier 1 (Definition)
    list.add(new GeneratedFixture(
        Path.of("invalid", "order_violation", "inv_order_bounds_before_definition.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_order_bounds_before_definition
        // MATRIX-POSITION:    3.1 before 1.1.1
        // TARGET-TYPE:        :Int
        // OFFENDING-FACET:    #unsigned
        // COVERAGE-KIND:      ORDER_VIOLATION
        // EXPECT-DIAGNOSTIC:  ERR_FACET_ORDER_VIOLATION
        // TARGET-TOKEN:       #unsigned
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        Bounds facet #minIncl placed before definition facet #unsigned
        // ==============================================================================
        {
          :defs {
            :BadOrderInt { #minIncl 0 #unsigned } :Int
          }
          :type :BadOrderInt
          :body 10
        }
        """,
        CoverageKind.ORDER_VIOLATION,
        Optional.of("ERR_FACET_ORDER_VIOLATION"),
        Optional.of("#unsigned")
    ));

    // 2. Tier 3 (Size) before Tier 1 (Trait)
    list.add(new GeneratedFixture(
        Path.of("invalid", "order_violation", "inv_order_trait_before_definition.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_order_trait_before_definition
        // MATRIX-POSITION:    1.3.1 before 2.1
        // TARGET-TYPE:        :Float
        // OFFENDING-FACET:    #equatable
        // COVERAGE-KIND:      ORDER_VIOLATION
        // EXPECT-DIAGNOSTIC:  ERR_FACET_ORDER_VIOLATION
        // TARGET-TOKEN:       #equatable
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        Size facet #size placed before trait facet #equatable
        // ==============================================================================
        {
          :defs {
            :BadOrderFloat { #size 32 #equatable #TRUE } :Float
          }
          :type :BadOrderFloat
          :body 1.0
        }
        """,
        CoverageKind.ORDER_VIOLATION,
        Optional.of("ERR_FACET_ORDER_VIOLATION"),
        Optional.of("#equatable")
    ));

    // 3. Sub-tier 4.3 (maxExcl) before 4.1 (minIncl)
    list.add(new GeneratedFixture(
        Path.of("invalid", "order_violation", "inv_order_bounds_subtier_inversion.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_order_bounds_subtier_inversion
        // MATRIX-POSITION:    3.4 before 3.1
        // TARGET-TYPE:        :Int
        // OFFENDING-FACET:    #minIncl
        // COVERAGE-KIND:      ORDER_VIOLATION
        // EXPECT-DIAGNOSTIC:  ERR_FACET_ORDER_VIOLATION
        // TARGET-TOKEN:       #minIncl
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        Upper bound #maxExcl placed before lower bound #minIncl
        // ==============================================================================
        {
          :defs {
            :BadOrderBounds { #maxExcl 100 #minIncl 0 } :Int
          }
          :type :BadOrderBounds
          :body 50
        }
        """,
        CoverageKind.ORDER_VIOLATION,
        Optional.of("ERR_FACET_ORDER_VIOLATION"),
        Optional.of("#minIncl")
    ));

    // 4. Constraint Tier 5 (regex) before Bounds Tier 3 (minSize)
    list.add(new GeneratedFixture(
        Path.of("invalid", "order_violation", "inv_order_constraint_before_bounds.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_order_constraint_before_bounds
        // MATRIX-POSITION:    4.4 before 1.5.1
        // TARGET-TYPE:        :String
        // OFFENDING-FACET:    #minSize
        // COVERAGE-KIND:      ORDER_VIOLATION
        // EXPECT-DIAGNOSTIC:  ERR_FACET_ORDER_VIOLATION
        // TARGET-TOKEN:       #minSize
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        Pattern #regex (Tier 5) placed before capacity #minSize (Tier 3)
        // ==============================================================================
        {
          :defs {
            :BadOrderStr { #regex "^[a-z]+$" #minSize 1 } :String
          }
          :type :BadOrderStr
          :body "a"
        }
        """,
        CoverageKind.ORDER_VIOLATION,
        Optional.of("ERR_FACET_ORDER_VIOLATION"),
        Optional.of("#minSize")
    ));

    return list;
  }

  private static List<GeneratedFixture> generateDiscreteBoundProhibitionFixtures(List<MetadataMatrixRow> rows) {
    List<GeneratedFixture> list = new ArrayList<>();

    // 1. #maxIncl on :Int
    list.add(new GeneratedFixture(
        Path.of("invalid", "discrete_bound", "inv_discrete_int_maxincl.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_discrete_int_maxincl
        // MATRIX-POSITION:    3.3
        // TARGET-TYPE:        :Int
        // OFFENDING-FACET:    #maxIncl
        // COVERAGE-KIND:      DISCRETE_BOUND_PROHIBITED
        // EXPECT-DIAGNOSTIC:  ERR_DISCRETE_BOUND_KIND_PROHIBITED
        // TARGET-TOKEN:       #maxIncl
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        Discrete type :Int prohibits bound #maxIncl; requires half-open #maxExcl
        // ==============================================================================
        {
          :defs {
            :BadIntBound { #minIncl 1 #maxIncl 65535 } :Int
          }
          :type :BadIntBound
          :body 8080
        }
        """,
        CoverageKind.DISCRETE_BOUND_PROHIBITED,
        Optional.of("ERR_DISCRETE_BOUND_KIND_PROHIBITED"),
        Optional.of("#maxIncl")
    ));

    // 2. #minExcl on :Int
    list.add(new GeneratedFixture(
        Path.of("invalid", "discrete_bound", "inv_discrete_int_minexcl.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_discrete_int_minexcl
        // MATRIX-POSITION:    3.2
        // TARGET-TYPE:        :Int
        // OFFENDING-FACET:    #minExcl
        // COVERAGE-KIND:      DISCRETE_BOUND_PROHIBITED
        // EXPECT-DIAGNOSTIC:  ERR_DISCRETE_BOUND_KIND_PROHIBITED
        // TARGET-TOKEN:       #minExcl
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        Discrete type :Int prohibits bound #minExcl; requires closed #minIncl
        // ==============================================================================
        {
          :defs {
            :BadIntMin { #minExcl 0 } :Int
          }
          :type :BadIntMin
          :body 1
        }
        """,
        CoverageKind.DISCRETE_BOUND_PROHIBITED,
        Optional.of("ERR_DISCRETE_BOUND_KIND_PROHIBITED"),
        Optional.of("#minExcl")
    ));

    // 3. #maxIncl on { #exact } :Float
    list.add(new GeneratedFixture(
        Path.of("invalid", "discrete_bound", "inv_discrete_exact_float_maxincl.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_discrete_exact_float_maxincl
        // MATRIX-POSITION:    3.3
        // TARGET-TYPE:        :Float (exact)
        // OFFENDING-FACET:    #maxIncl
        // COVERAGE-KIND:      DISCRETE_BOUND_PROHIBITED
        // EXPECT-DIAGNOSTIC:  ERR_DISCRETE_BOUND_KIND_PROHIBITED
        // TARGET-TOKEN:       #maxIncl
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        Discrete exact float prohibits bound #maxIncl; requires half-open #maxExcl
        // ==============================================================================
        {
          :defs {
            :BadExactFloat { #exact #minIncl 0.0 #maxIncl 100.0 } :Float
          }
          :type :BadExactFloat
          :body 50.0
        }
        """,
        CoverageKind.DISCRETE_BOUND_PROHIBITED,
        Optional.of("ERR_DISCRETE_BOUND_KIND_PROHIBITED"),
        Optional.of("#maxIncl")
    ));

    // 4. #minExcl on { #exact } :Float
    list.add(new GeneratedFixture(
        Path.of("invalid", "discrete_bound", "inv_discrete_exact_float_minexcl.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_discrete_exact_float_minexcl
        // MATRIX-POSITION:    3.2
        // TARGET-TYPE:        :Float (exact)
        // OFFENDING-FACET:    #minExcl
        // COVERAGE-KIND:      DISCRETE_BOUND_PROHIBITED
        // EXPECT-DIAGNOSTIC:  ERR_DISCRETE_BOUND_KIND_PROHIBITED
        // TARGET-TOKEN:       #minExcl
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        Discrete exact float prohibits bound #minExcl; requires closed #minIncl
        // ==============================================================================
        {
          :defs {
            :BadExactFloatMin { #exact #minExcl 0.0 } :Float
          }
          :type :BadExactFloatMin
          :body 50.0
        }
        """,
        CoverageKind.DISCRETE_BOUND_PROHIBITED,
        Optional.of("ERR_DISCRETE_BOUND_KIND_PROHIBITED"),
        Optional.of("#minExcl")
    ));

    // 5. #maxIncl on :DateTime
    list.add(new GeneratedFixture(
        Path.of("invalid", "discrete_bound", "inv_discrete_datetime_maxincl.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_discrete_datetime_maxincl
        // MATRIX-POSITION:    3.3
        // TARGET-TYPE:        :DateTime
        // OFFENDING-FACET:    #maxIncl
        // COVERAGE-KIND:      DISCRETE_BOUND_PROHIBITED
        // EXPECT-DIAGNOSTIC:  ERR_DISCRETE_BOUND_KIND_PROHIBITED
        // TARGET-TOKEN:       #maxIncl
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        Discrete temporal type :DateTime prohibits bound #maxIncl
        // ==============================================================================
        {
          :defs {
            :BadDtMax { #offset #maxIncl "2026-01-01T00:00:00Z" } :DateTime
          }
          :type :BadDtMax
          :body "2025-01-01T00:00:00Z"
        }
        """,
        CoverageKind.DISCRETE_BOUND_PROHIBITED,
        Optional.of("ERR_DISCRETE_BOUND_KIND_PROHIBITED"),
        Optional.of("#maxIncl")
    ));

    // 6. #minExcl on :TimeEpoch
    list.add(new GeneratedFixture(
        Path.of("invalid", "discrete_bound", "inv_discrete_time_epoch_minexcl.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_discrete_time_epoch_minexcl
        // MATRIX-POSITION:    3.2
        // TARGET-TYPE:        :TimeEpoch
        // OFFENDING-FACET:    #minExcl
        // COVERAGE-KIND:      DISCRETE_BOUND_PROHIBITED
        // EXPECT-DIAGNOSTIC:  ERR_DISCRETE_BOUND_KIND_PROHIBITED
        // TARGET-TOKEN:       #minExcl
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        Discrete temporal type :TimeEpoch prohibits bound #minExcl
        // ==============================================================================
        {
          :defs {
            :BadEpochMin { #s #minExcl 0 } :TimeEpoch
          }
          :type :BadEpochMin
          :body 10
        }
        """,
        CoverageKind.DISCRETE_BOUND_PROHIBITED,
        Optional.of("ERR_DISCRETE_BOUND_KIND_PROHIBITED"),
        Optional.of("#minExcl")
    ));

    return list;
  }

  private static List<GeneratedFixture> generateMutuallyExclusiveFixtures(List<MetadataMatrixRow> rows) {
    List<GeneratedFixture> list = new ArrayList<>();

    // 1. #minIncl and #minExcl simultaneously
    list.add(new GeneratedFixture(
        Path.of("invalid", "mutual_exclusion", "inv_mutex_float_minincl_minexcl.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_mutex_float_minincl_minexcl
        // MATRIX-POSITION:    3.1 vs 3.2
        // TARGET-TYPE:        :Float
        // OFFENDING-FACET:    #minExcl
        // COVERAGE-KIND:      MUTUALLY_EXCLUSIVE
        // EXPECT-DIAGNOSTIC:  MUTUALLY_EXCLUSIVE_BOUNDS
        // TARGET-TOKEN:       #minExcl
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        Lower bounds #minIncl and #minExcl are mutually exclusive
        // ==============================================================================
        {
          :defs {
            :ConflictingBounds { #size 64 #minIncl 0.0 #minExcl 0.0 } :Float
          }
          :type :ConflictingBounds
          :body 1.0
        }
        """,
        CoverageKind.MUTUALLY_EXCLUSIVE,
        Optional.of("MUTUALLY_EXCLUSIVE_BOUNDS"),
        Optional.of("#minExcl")
    ));

    // 2. #maxIncl and #maxExcl simultaneously
    list.add(new GeneratedFixture(
        Path.of("invalid", "mutual_exclusion", "inv_mutex_float_maxincl_maxexcl.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_mutex_float_maxincl_maxexcl
        // MATRIX-POSITION:    3.3 vs 3.4
        // TARGET-TYPE:        :Float
        // OFFENDING-FACET:    #maxIncl
        // COVERAGE-KIND:      MUTUALLY_EXCLUSIVE
        // EXPECT-DIAGNOSTIC:  MUTUALLY_EXCLUSIVE_BOUNDS
        // TARGET-TOKEN:       #maxIncl
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        Upper bounds #maxIncl and #maxExcl are mutually exclusive
        // ==============================================================================
        {
          :defs {
            :ConflictingMax { #size 64 #maxExcl 100.0 #maxIncl 100.0 } :Float
          }
          :type :ConflictingMax
          :body 1.0
        }
        """,
        CoverageKind.MUTUALLY_EXCLUSIVE,
        Optional.of("MUTUALLY_EXCLUSIVE_BOUNDS"),
        Optional.of("#maxIncl")
    ));

    // 3. Temporal scale conflict (#s and #ms)
    list.add(new GeneratedFixture(
        Path.of("invalid", "mutual_exclusion", "inv_mutex_time_epoch_scales.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_mutex_time_epoch_scales
        // MATRIX-POSITION:    1.2.3 vs 1.2.4
        // TARGET-TYPE:        :TimeEpoch
        // OFFENDING-FACET:    #ms
        // COVERAGE-KIND:      MUTUALLY_EXCLUSIVE
        // EXPECT-DIAGNOSTIC:  MUTUALLY_EXCLUSIVE_BOUNDS
        // TARGET-TOKEN:       #ms
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        Temporal scale facets (#s, #ms) are mutually exclusive
        // ==============================================================================
        {
          :defs {
            :BadEpochScale { #s #ms } :TimeEpoch
          }
          :type :BadEpochScale
          :body 1000
        }
        """,
        CoverageKind.MUTUALLY_EXCLUSIVE,
        Optional.of("MUTUALLY_EXCLUSIVE_BOUNDS"),
        Optional.of("#ms")
    ));

    // 4. Temporal mode conflict (#offset and #zoned)
    list.add(new GeneratedFixture(
        Path.of("invalid", "mutual_exclusion", "inv_mutex_datetime_modes.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_mutex_datetime_modes
        // MATRIX-POSITION:    1.2.5 vs 1.2.6
        // TARGET-TYPE:        :DateTime
        // OFFENDING-FACET:    #zoned
        // COVERAGE-KIND:      MUTUALLY_EXCLUSIVE
        // EXPECT-DIAGNOSTIC:  MUTUALLY_EXCLUSIVE_BOUNDS
        // TARGET-TOKEN:       #zoned
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        Temporal mode facets (#offset, #zoned) are mutually exclusive
        // ==============================================================================
        {
          :defs {
            :BadDtMode { #offset #zoned } :DateTime
          }
          :type :BadDtMode
          :body "2026-01-01T00:00:00Z"
        }
        """,
        CoverageKind.MUTUALLY_EXCLUSIVE,
        Optional.of("MUTUALLY_EXCLUSIVE_BOUNDS"),
        Optional.of("#zoned")
    ));

    // 5. Enum filter conflict (#filterIncl and #filterExcl)
    list.add(new GeneratedFixture(
        Path.of("invalid", "mutual_exclusion", "inv_mutex_enum_filter_incl_excl.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_mutex_enum_filter_incl_excl
        // MATRIX-POSITION:    4.1 vs 4.2
        // TARGET-TYPE:        :Enum
        // OFFENDING-FACET:    #filterExcl
        // COVERAGE-KIND:      MUTUALLY_EXCLUSIVE
        // EXPECT-DIAGNOSTIC:  MALFORMED_SCHEMA
        // TARGET-TOKEN:       #filterExcl
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        Enum subset filters #filterIncl and #filterExcl are mutually exclusive
        // ==============================================================================
        {
          :defs {
            :BaseEnum :Enum [ #A #B ]
            :BadEnum { #filterIncl [ #A ] #filterExcl [ #B ] } :BaseEnum
          }
          :type :BadEnum
          :body #A
        }
        """,
        CoverageKind.MUTUALLY_EXCLUSIVE,
        Optional.of("MALFORMED_SCHEMA"),
        Optional.of("#filterExcl")
    ));

    return list;
  }

  private static List<GeneratedFixture> generateIntervalSoundnessFixtures(List<MetadataMatrixRow> rows) {
    List<GeneratedFixture> list = new ArrayList<>();

    // 1. Empty domain [k, k) on :DateTime
    list.add(new GeneratedFixture(
        Path.of("invalid", "interval_soundness", "inv_interval_empty_datetime.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_interval_empty_datetime
        // MATRIX-POSITION:    3.1 vs 3.4
        // TARGET-TYPE:        :DateTime (offset)
        // COVERAGE-KIND:      INTERVAL_SOUNDNESS
        // EXPECT-DIAGNOSTIC:  ERR_EMPTY_INTERVAL_DOMAIN
        // TARGET-TOKEN:       #maxExcl
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        Discrete datetime interval [k, k) defines empty domain with zero habitable values
        // ==============================================================================
        {
          :defs {
            :EmptyDateTime {
              #offset
              #minIncl "2026-09-30T12:00:00Z"
              #maxExcl "2026-09-30T12:00:00Z"
            } :DateTime
          }
          :type :EmptyDateTime
          :body "2026-09-30T12:00:00Z"
        }
        """,
        CoverageKind.INTERVAL_SOUNDNESS,
        Optional.of("ERR_EMPTY_INTERVAL_DOMAIN"),
        Optional.of("#maxExcl")
    ));

    // 2. Inverted range on :Int
    list.add(new GeneratedFixture(
        Path.of("invalid", "interval_soundness", "inv_interval_inverted_int.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_interval_inverted_int
        // MATRIX-POSITION:    3.1 vs 3.4
        // TARGET-TYPE:        :Int
        // COVERAGE-KIND:      INTERVAL_SOUNDNESS
        // EXPECT-DIAGNOSTIC:  INVALID_NUMERIC_RANGE
        // TARGET-TOKEN:       #maxExcl
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        Effective integer range is inverted (minimum 100 is greater than maximum 50)
        // ==============================================================================
        {
          :defs {
            :InvertedInt { #minIncl 100 #maxExcl 50 } :Int
          }
          :type :InvertedInt
          :body 75
        }
        """,
        CoverageKind.INTERVAL_SOUNDNESS,
        Optional.of("INVALID_NUMERIC_RANGE"),
        Optional.of("#maxExcl")
    ));

    // 3. Inverted cardinality on :String
    list.add(new GeneratedFixture(
        Path.of("invalid", "interval_soundness", "inv_interval_inverted_string_cardinality.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_interval_inverted_string_cardinality
        // MATRIX-POSITION:    1.5.1 vs 1.5.2
        // TARGET-TYPE:        :String
        // COVERAGE-KIND:      INTERVAL_SOUNDNESS
        // EXPECT-DIAGNOSTIC:  INVALID_NUMERIC_RANGE
        // TARGET-TOKEN:       #maxSize
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        Cardinality range is invalid (#minSize 10 is greater than #maxSize 5)
        // ==============================================================================
        {
          :defs {
            :InvertedString { #minSize 10 #maxSize 5 } :String
          }
          :type :InvertedString
          :body "test"
        }
        """,
        CoverageKind.INTERVAL_SOUNDNESS,
        Optional.of("INVALID_NUMERIC_RANGE"),
        Optional.of("#maxSize")
    ));

    // 4. Inverted float range on :Float
    list.add(new GeneratedFixture(
        Path.of("invalid", "interval_soundness", "inv_interval_inverted_float.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_interval_inverted_float
        // MATRIX-POSITION:    3.1 vs 3.4
        // TARGET-TYPE:        :Float
        // COVERAGE-KIND:      INTERVAL_SOUNDNESS
        // EXPECT-DIAGNOSTIC:  INVALID_NUMERIC_RANGE
        // TARGET-TOKEN:       #maxExcl
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        Effective float range is inverted (minimum 100.0 is greater than maximum 50.0)
        // ==============================================================================
        {
          :defs {
            :InvertedFloat { #size 64 #minIncl 100.0 #maxExcl 50.0 } :Float
          }
          :type :InvertedFloat
          :body 75.0
        }
        """,
        CoverageKind.INTERVAL_SOUNDNESS,
        Optional.of("INVALID_NUMERIC_RANGE"),
        Optional.of("#maxExcl")
    ));

    // 5. Inverted datetime range on :DateTime
    list.add(new GeneratedFixture(
        Path.of("invalid", "interval_soundness", "inv_interval_inverted_datetime.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_interval_inverted_datetime
        // MATRIX-POSITION:    3.1 vs 3.4
        // TARGET-TYPE:        :DateTime
        // COVERAGE-KIND:      INTERVAL_SOUNDNESS
        // EXPECT-DIAGNOSTIC:  INVALID_NUMERIC_RANGE
        // TARGET-TOKEN:       #maxExcl
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        Effective datetime range is inverted (minimum is chronologically after maximum)
        // ==============================================================================
        {
          :defs {
            :InvertedDateTime { #offset #minIncl "2027-01-01T00:00:00Z" #maxExcl "2026-01-01T00:00:00Z" } :DateTime
          }
          :type :InvertedDateTime
          :body "2026-06-01T00:00:00Z"
        }
        """,
        CoverageKind.INTERVAL_SOUNDNESS,
        Optional.of("INVALID_NUMERIC_RANGE"),
        Optional.of("#maxExcl")
    ));

    return list;
  }

  /**
   * Generates invalid negative fixtures covering duplicate facet entries within metadata blocks.
   * Targets subpath shared-fixtures/metadata/invalid/duplicate_facet/.
   *
   * @param rows parsed canonical matrix rows
   * @return list of duplicate facet edge case fixtures
   */
  public static List<GeneratedFixture> generateDuplicateFacetFixtures(List<MetadataMatrixRow> rows) {
    List<GeneratedFixture> list = new ArrayList<>();

    // 1. Duplicate #size on :Int
    list.add(new GeneratedFixture(
        Path.of("invalid", "duplicate_facet", "inv_duplicate_int_size.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_duplicate_int_size
        // MATRIX-POSITION:    1.3.1
        // TARGET-TYPE:        :Int
        // OFFENDING-FACET:    #size
        // COVERAGE-KIND:      DUPLICATE_FACET
        // EXPECT-DIAGNOSTIC:  ERR_DUPLICATE_METADATA_FACET
        // TARGET-TOKEN:       #size
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        Duplicate facet #size declared within the same metadata block
        // ==============================================================================
        // EXPECT-DIAGNOSTIC: ERR_DUPLICATE_METADATA_FACET
        // TARGET-TOKEN: #size
        {
          :defs {
            :BadDuplicateInt { #size 53 #size 1 } :Int
          }
          :type :BadDuplicateInt
          :body 0
        }
        """,
        CoverageKind.DUPLICATE_FACET,
        Optional.of("ERR_DUPLICATE_METADATA_FACET"),
        Optional.of("#size")
    ));

    // 2. Duplicate #maxSize on :String
    list.add(new GeneratedFixture(
        Path.of("invalid", "duplicate_facet", "inv_duplicate_string_maxsize.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_duplicate_string_maxsize
        // MATRIX-POSITION:    1.5.2
        // TARGET-TYPE:        :String
        // OFFENDING-FACET:    #maxSize
        // COVERAGE-KIND:      DUPLICATE_FACET
        // EXPECT-DIAGNOSTIC:  ERR_DUPLICATE_METADATA_FACET
        // TARGET-TOKEN:       #maxSize
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        Duplicate facet #maxSize declared within the same metadata block
        // ==============================================================================
        // EXPECT-DIAGNOSTIC: ERR_DUPLICATE_METADATA_FACET
        // TARGET-TOKEN: #maxSize
        {
          :defs {
            :BadDuplicateString { #maxSize 4096 #maxSize 1024 } :String
          }
          :type :BadDuplicateString
          :body ""
        }
        """,
        CoverageKind.DUPLICATE_FACET,
        Optional.of("ERR_DUPLICATE_METADATA_FACET"),
        Optional.of("#maxSize")
    ));

    // 3. Duplicate #invertible on :Map
    list.add(new GeneratedFixture(
        Path.of("invalid", "duplicate_facet", "inv_duplicate_map_invertible.stvn"),
        """
        // ==============================================================================
        // STVN METADATA TEST FIXTURE (CANONICAL SUITE 2.0.0)
        // ==============================================================================
        // FIXTURE-ID:         inv_duplicate_map_invertible
        // MATRIX-POSITION:    1.1.3
        // TARGET-TYPE:        :Map
        // OFFENDING-FACET:    #invertible
        // COVERAGE-KIND:      DUPLICATE_FACET
        // EXPECT-DIAGNOSTIC:  ERR_DUPLICATE_METADATA_FACET
        // TARGET-TOKEN:       #invertible
        // EXPECT-SEVERITY:    ERROR
        // DESCRIPTION:        Duplicate facet #invertible declared within the same metadata block
        // ==============================================================================
        // EXPECT-DIAGNOSTIC: ERR_DUPLICATE_METADATA_FACET
        // TARGET-TOKEN: #invertible
        {
          :defs {
            :BadDuplicateMap { #invertible #invertible } :Map( :String :Int )
          }
          :type :BadDuplicateMap
          :body { }
        }
        """,
        CoverageKind.DUPLICATE_FACET,
        Optional.of("ERR_DUPLICATE_METADATA_FACET"),
        Optional.of("#invertible")
    ));

    return list;
  }

  private static String sanitizeName(String raw) {
    return raw.replace(":", "")
        .replace(" ", "_")
        .replace("(", "")
        .replace(")", "")
        .toLowerCase(Locale.ROOT);
  }
}
