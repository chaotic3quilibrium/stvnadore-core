package org.stvnadore.core.matrix;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Immutable value record representing a single row in the canonical STVN metadata matrix.
 * <p>
 * Captures all 10 tab-separated columns defined in {@code stvn_2_0_0_metadata_matrix.tsv}
 * with strict non-null assertions and domain query helpers.
 *
 * @param position            hierarchical tier position (e.g., "1.1.1", "2.1", "3.1", "4.1", "5")
 * @param category            semantic category ("Definition", "Trait", "Bounds", "Constraint", "Directive")
 * @param modifying           target type domain (e.g., ":Int", ":Float (32)", ":DateTime (offset)", ":String")
 * @param tagName             unadorned facet identifier (e.g., "unsigned", "size", "equatable", "minIncl")
 * @param parameterKind       parameter type kind (e.g., "Standalone", ":Int", ":Boolean", ":String")
 * @param parameterRange      permissible parameter domain (e.g., "s >= 1", ":F|:T|:FALSE|:TRUE", "N/A")
 * @param defaultValue        default parameter value when omitted (e.g., "32", ":TRUE", "<None>", "Absent")
 * @param overridable         whether downstream subtypes or instances may override the facet
 * @param mutuallyExclusiveTo comma-separated list of conflicting facets, or "N/A"
 * @param description         normative architectural specification of the facet behavior
 */
@NullMarked
public record MetadataMatrixRow(
    String position,
    String category,
    String modifying,
    String tagName,
    String parameterKind,
    String parameterRange,
    String defaultValue,
    boolean overridable,
    String mutuallyExclusiveTo,
    String description
) {

  public MetadataMatrixRow {
    Objects.requireNonNull(position, "position must not be null");
    Objects.requireNonNull(category, "category must not be null");
    Objects.requireNonNull(modifying, "modifying must not be null");
    Objects.requireNonNull(tagName, "tagName must not be null");
    Objects.requireNonNull(parameterKind, "parameterKind must not be null");
    Objects.requireNonNull(parameterRange, "parameterRange must not be null");
    Objects.requireNonNull(defaultValue, "defaultValue must not be null");
    Objects.requireNonNull(mutuallyExclusiveTo, "mutuallyExclusiveTo must not be null");
    Objects.requireNonNull(description, "description must not be null");
  }

  /**
   * Returns the primary tier integer (1 through 5) parsed from the position hierarchy.
   */
  public int primaryTier() {
    int dotIndex = position.indexOf('.');
    String root = dotIndex < 0 ? position : position.substring(0, dotIndex);
    return Integer.parseInt(root);
  }

  /**
   * Returns true if the facet requires no parameter payload (e.g., #unsigned, #exact, #strip).
   */
  public boolean isStandalone() {
    return "Standalone".equalsIgnoreCase(parameterKind.trim());
  }

  /**
   * Returns the base STVN type name without parenthetical specialization.
   * E.g., ":Float (32)" returns ":Float", ":Int (unsigned)" returns ":Int".
   */
  public String cleanBaseType() {
    int parenIndex = modifying.indexOf('(');
    return parenIndex < 0 ? modifying.trim() : modifying.substring(0, parenIndex).trim();
  }

  /**
   * Returns the parenthetical domain qualification, or null if none exists.
   * E.g., ":Float (exact)" returns "exact", ":DateTime (zoned)" returns "zoned".
   */
  public @Nullable String domainQualifier() {
    int open = modifying.indexOf('(');
    int close = modifying.indexOf(')');
    if (open >= 0 && close > open) {
      return modifying.substring(open + 1, close).trim();
    }
    return null;
  }

  /**
   * Returns the set of conflicting facet names declared in the mutually exclusive column.
   */
  public Set<String> conflictingFacets() {
    if ("N/A".equalsIgnoreCase(mutuallyExclusiveTo.trim()) || mutuallyExclusiveTo.isBlank()) {
      return Collections.emptySet();
    }
    return Arrays.stream(mutuallyExclusiveTo.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toUnmodifiableSet());
  }
}
