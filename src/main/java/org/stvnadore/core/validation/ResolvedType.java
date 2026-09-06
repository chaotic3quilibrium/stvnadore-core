package org.stvnadore.core.validation;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;

/**
 * Sealed algebraic type representation for resolved domain types in STVN.
 * <p>
 * Supports Value-Oriented Programming (VOP) by guaranteeing deep immutability
 * across all semantic type definitions.
 *
 * @since 1.1.0
 */
@NullMarked
public sealed interface ResolvedType permits ResolvedType.EnumSubset {

  /**
   * Represents an enum subset derived by filtering a parent enum or existing enum subset.
   * <p>
   * Supports transitive chaining back to the root {@code :Enum} declaration.
   *
   * @param name            the nominal alias name of this subset (e.g. {@code :ActiveStatus})
   * @param parentType      the nominal name of the immediate parent type (e.g. {@code :Status})
   * @param rootEnum        the nominal name of the root {@code :Enum} (e.g. {@code :Status})
   * @param allowedVariants the immutable list of allowed variant keyword identifiers in root declaration order
   * @param rootVariants    the immutable list of all variant keyword identifiers defined in the root {@code :Enum} in declaration order
   * @param isInclusive     {@code true} if derived via {@code #filterIncl}; {@code false} if derived via {@code #filterExcl}
   */
  record EnumSubset(
      String name,
      String parentType,
      String rootEnum,
      List<String> allowedVariants,
      List<String> rootVariants,
      boolean isInclusive
  ) implements ResolvedType {

    /**
     * Canonical constructor validating non-null parameters and enforcing defensive copying.
     */
    public EnumSubset {
      Objects.requireNonNull(name, "name cannot be null");
      Objects.requireNonNull(parentType, "parentType cannot be null");
      Objects.requireNonNull(rootEnum, "rootEnum cannot be null");
      Objects.requireNonNull(allowedVariants, "allowedVariants cannot be null");
      Objects.requireNonNull(rootVariants, "rootVariants cannot be null");
      allowedVariants = List.copyOf(allowedVariants);
      rootVariants = List.copyOf(rootVariants);
    }

    /**
     * Checks if a given variant is permitted within this subset boundary.
     *
     * @param variant the variant identifier (e.g. {@code #Active})
     * @return {@code true} if allowed; {@code false} otherwise
     */
    public boolean containsVariant(String variant) {
      return allowedVariants.contains(variant);
    }

    /**
     * Returns the total count of allowed variants in this active subset.
     *
     * @return variant count
     */
    public int size() {
      return allowedVariants.size();
    }
  }
}
