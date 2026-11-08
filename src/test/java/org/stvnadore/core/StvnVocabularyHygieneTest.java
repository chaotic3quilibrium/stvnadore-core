package org.stvnadore.core;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full-repository vocabulary hygiene gate for {@code ij_stvnadore_core}.
 * <p>
 * Scans all Java source files under {@code src/main/java/org/stvnadore/core/} (excluding
 * {@link StvnVocabulary} itself and the embedded standard library source text in
 * {@code StvnPrelude}) to permanently prohibit:
 * <ol>
 *   <li>Hardcoded standalone STVN {@code :Type}, {@code :section}, {@code #facet}, {@code #kind},
 *       {@code #boolean}, {@code #sum}, and IEEE-754 special string literals that bypass
 *       {@link StvnVocabulary} constants.</li>
 *   <li>Invented or non-existent STVN type literals (such as {@code :Value}, {@code :Any},
 *       {@code :Record}, {@code :Product}, or {@code :MapEntry}).</li>
 *   <li>Excised STVN 1.x compound temporal type literals ({@code :DateTimeOffset},
 *       {@code :DateTimeZoned}, {@code :DateTimeAudited}, {@code :EpochSeconds},
 *       {@code :EpochMillis}, {@code :EpochMicros}, {@code :EpochNanos}) outside the
 *       explicit legacy deprecation rejection table in {@code StvnDefsLowerer}.</li>
 * </ol>
 */
@NullMarked
class StvnVocabularyHygieneTest {

  private static final Path MAIN_JAVA_ROOT = Path.of("src/main/java/org/stvnadore/core");

  private static final Set<String> FORBIDDEN_EXACT_STRING_LITERALS = Set.of(
      // Base scalar & composite types
      ":Boolean", ":Int", ":Float", ":String", ":TimeEpoch", ":DateTime",
      ":Seq", ":Set", ":Map", ":Tuple", ":Option", ":Either", ":Union", ":Enum", ":MapEntry",
      // Document section & modular keywords
      ":meta", ":defs", ":type", ":body", ":include", ":package", ":use",
      // Metadata facet keywords (all 7 tiers + :meta facets)
      "#unsigned", "#exact", "#invertible", "#preserveIndent",
      "#offset", "#zoned", "#audited",
      "#equatable", "#comparable",
      "#s", "#ms", "#us", "#ns",
      "#size", "#minSize", "#maxSize",
      "#minIncl", "#minExcl", "#maxExcl", "#maxIncl",
      "#regex", "#filterIncl", "#filterExcl", "#strip",
      "#name", "#domain", "#kind",
      // Document kind variants
      "#BODY", "#DEFS", "#BODY_INCLUDE", "#DEFS_INCLUDE",
      // Boolean & algebraic sum value keywords
      "#TRUE", "#FALSE", "#T", "#F",
      "#Some", "#None", "#S", "#N",
      "#Left", "#Right", "#L", "#R",
      // IEEE-754 special floating-point literals
      "NaN", "+Infinity", "-Infinity", "-0.0",
      // Dual-track prefix sigils
      ":", "#"
  );

  private static final Pattern INVENTED_TYPE_LITERAL_PATTERN = Pattern.compile(
      ":(?:Value|Any|Record|Product|MapEntry)\\b"
  );

  private static final Pattern PURGED_1X_TEMPORAL_TYPE_PATTERN = Pattern.compile(
      ":(?:DateTimeOffset|DateTimeZoned|DateTimeAudited|EpochSeconds|EpochMillis|EpochMicros|EpochNanos)\\b"
  );

  private static final Pattern DOUBLE_QUOTED_STRING_PATTERN = Pattern.compile(
      "\"((?:[^\"\\\\]|\\\\.)*)\""
  );

  @Test
  @DisplayName("TC-HYGIENE-01: Zero raw STVN :Type, :section, #facet, #kind, #boolean, #sum, sigil, or IEEE-754 string literals in src/main/java")
  void testZeroRawVocabularyStringLiterals() throws IOException {
    assertTrue(Files.exists(MAIN_JAVA_ROOT), "Main Java source root must exist: " + MAIN_JAVA_ROOT);

    List<String> violations = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(MAIN_JAVA_ROOT)) {
      List<Path> javaFiles = paths
          .filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".java"))
          .filter(p -> !p.getFileName().toString().equals("StvnVocabulary.java"))
          .sorted()
          .toList();

      for (Path file : javaFiles) {
        scanFileForExactVocabularyLiterals(file, violations);
      }
    }

    assertTrue(
        violations.isEmpty(),
        "Discovered hardcoded STVN vocabulary string literals bypassing StvnVocabulary:\n"
            + String.join("\n", violations)
    );
  }

  @Test
  @DisplayName("TC-HYGIENE-02: Zero invented (:Value, :Any, :Record, :Product, :MapEntry) or leaked 1.x temporal type literals in src/main/java")
  void testZeroInventedOrPurged1xTypeLiterals() throws IOException {
    assertTrue(Files.exists(MAIN_JAVA_ROOT), "Main Java source root must exist: " + MAIN_JAVA_ROOT);

    List<String> violations = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(MAIN_JAVA_ROOT)) {
      List<Path> javaFiles = paths
          .filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".java"))
          .filter(p -> !p.getFileName().toString().equals("StvnVocabulary.java"))
          .sorted()
          .toList();

      for (Path file : javaFiles) {
        scanFileForInventedAndPurgedTypeLiterals(file, violations);
      }
    }

    assertTrue(
        violations.isEmpty(),
        "Discovered invented or purged 1.x STVN type literals in src/main/java:\n"
            + String.join("\n", violations)
    );
  }

  private static void scanFileForExactVocabularyLiterals(Path file, List<String> violations) throws IOException {
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    boolean inBlockComment = false;
    boolean inTextBlock = false;

    for (int i = 0; i < lines.size(); i++) {
      String rawLine = lines.get(i);
      String trimmed = rawLine.trim();

      if (trimmed.contains("\"\"\"")) {
        long tripleQuotes = countOccurrences(trimmed, "\"\"\"");
        if (tripleQuotes % 2 == 1) {
          inTextBlock = !inTextBlock;
        }
        continue;
      }
      if (inTextBlock) {
        continue;
      }
      if (inBlockComment) {
        if (trimmed.contains("*/")) {
          inBlockComment = false;
        }
        continue;
      }
      if (trimmed.startsWith("/*")) {
        if (!trimmed.contains("*/")) {
          inBlockComment = true;
        }
        continue;
      }
      if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
        continue;
      }

      String codePortion = stripLineCommentOutsideStrings(rawLine);
      Matcher m = DOUBLE_QUOTED_STRING_PATTERN.matcher(codePortion);
      while (m.find()) {
        String literalValue = m.group(1);
        if (FORBIDDEN_EXACT_STRING_LITERALS.contains(literalValue)) {
          violations.add(file + ":" + (i + 1) + " -> hardcoded literal \"" + literalValue + "\" in: " + trimmed);
        }
      }
    }
  }

  private static void scanFileForInventedAndPurgedTypeLiterals(Path file, List<String> violations) throws IOException {
    String fileName = file.getFileName().toString();
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    boolean inBlockComment = false;
    boolean inPurgedLegacySet = false;

    for (int i = 0; i < lines.size(); i++) {
      String rawLine = lines.get(i);
      String trimmed = rawLine.trim();

      if (inBlockComment) {
        if (trimmed.contains("*/")) {
          inBlockComment = false;
        }
        continue;
      }
      if (trimmed.startsWith("/*")) {
        if (!trimmed.contains("*/")) {
          inBlockComment = true;
        }
        continue;
      }
      if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
        continue;
      }

      if (fileName.equals("StvnDefsLowerer.java") && trimmed.contains("PURGED_LEGACY_PRELUDE_ALIASES")) {
        inPurgedLegacySet = true;
      }
      if (inPurgedLegacySet) {
        if (trimmed.contains(");")) {
          inPurgedLegacySet = false;
        }
        continue;
      }

      String codePortion = stripLineCommentOutsideStrings(rawLine);
      Matcher m = DOUBLE_QUOTED_STRING_PATTERN.matcher(codePortion);
      while (m.find()) {
        String literalContent = m.group(1);
        Matcher inventedMatcher = INVENTED_TYPE_LITERAL_PATTERN.matcher(literalContent);
        if (inventedMatcher.find()) {
          violations.add(file + ":" + (i + 1) + " -> invented type literal '"
              + inventedMatcher.group() + "' in: " + trimmed);
        }
        Matcher purgedMatcher = PURGED_1X_TEMPORAL_TYPE_PATTERN.matcher(literalContent);
        if (purgedMatcher.find()) {
          violations.add(file + ":" + (i + 1) + " -> purged 1.x temporal type literal '"
              + purgedMatcher.group() + "' in: " + trimmed);
        }
      }
    }
  }

  private static String stripLineCommentOutsideStrings(String line) {
    boolean inQuotes = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '\\' && inQuotes) {
        i++;
        continue;
      }
      if (c == '"') {
        inQuotes = !inQuotes;
      } else if (!inQuotes && c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
        return line.substring(0, i);
      }
    }
    return line;
  }

  private static long countOccurrences(String text, String sub) {
    long count = 0;
    int idx = 0;
    while ((idx = text.indexOf(sub, idx)) != -1) {
      count++;
      idx += sub.length();
    }
    return count;
  }
}
