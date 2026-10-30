package org.stvnadore.core.ir;

import org.jspecify.annotations.NullMarked;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable Canonical IR catalog storing desugared Fully Qualified Nominal Identifiers (FQNIs)
 * mapped to normalized {@link StvnSchema} definitions and {@link StvnConstant} values in
 * deterministic topological order.
 *
 * @param nominalTypes ordered unmodifiable map from typic FQNI to normalized {@link StvnSchema}
 * @param constants    ordered unmodifiable map from value FQNI to lowered {@link StvnConstant}
 * @since 2.0.0
 */
@NullMarked
public record StvnDefsCatalog(
    Map<String, StvnSchema> nominalTypes,
    Map<String, StvnConstant> constants
) {

  /** Shared empty catalog instance containing zero nominal types and zero constants. */
  public static final StvnDefsCatalog EMPTY = new StvnDefsCatalog(Map.of(), Map.of());

  /**
   * Canonical constructor defensively copying both maps into insertion-ordered unmodifiable views.
   *
   * @param nominalTypes map from typic FQNI to normalized {@link StvnSchema}
   * @param constants    map from value FQNI to lowered {@link StvnConstant}
   */
  public StvnDefsCatalog {
    Objects.requireNonNull(nominalTypes, "nominalTypes must not be null");
    Objects.requireNonNull(constants, "constants must not be null");
    var copiedTypes = new LinkedHashMap<String, StvnSchema>();
    for (var entry : nominalTypes.entrySet()) {
      copiedTypes.put(
          Objects.requireNonNull(entry.getKey(), "nominalTypes key must not be null"),
          Objects.requireNonNull(entry.getValue(), "nominalTypes value must not be null")
      );
    }
    var copiedConstants = new LinkedHashMap<String, StvnConstant>();
    for (var entry : constants.entrySet()) {
      copiedConstants.put(
          Objects.requireNonNull(entry.getKey(), "constants key must not be null"),
          Objects.requireNonNull(entry.getValue(), "constants value must not be null")
      );
    }
    nominalTypes = Collections.unmodifiableMap(copiedTypes);
    constants = Collections.unmodifiableMap(copiedConstants);
  }

  /**
   * Looks up a normalized nominal schema by its Fully Qualified Nominal Identifier.
   *
   * @param fqni the Fully Qualified Nominal Identifier (for example, {@code :pkg/TypeName})
   * @return an {@link Optional} containing the matching {@link StvnSchema}, or empty if absent
   */
  public Optional<StvnSchema> findType(String fqni) {
    Objects.requireNonNull(fqni, "fqni must not be null");
    return Optional.ofNullable(nominalTypes.get(fqni));
  }

  /**
   * Looks up a lowered typed constant by its Fully Qualified Nominal Identifier.
   *
   * @param fqni the Fully Qualified Nominal Identifier (for example, {@code :pkg/#CONST_NAME})
   * @return an {@link Optional} containing the matching {@link StvnConstant}, or empty if absent
   */
  public Optional<StvnConstant> findConstant(String fqni) {
    Objects.requireNonNull(fqni, "fqni must not be null");
    return Optional.ofNullable(constants.get(fqni));
  }

  /**
   * Immutable Canonical IR record representing a lowered typed constant definition in {@code :defs}.
   *
   * @param fqni   the Fully Qualified Nominal Identifier of the constant
   * @param schema the governing normalized {@link StvnSchema} of the constant
   * @param value  the validated and lowered {@link StvnValue} of the constant
   * @since 2.0.0
   */
  public record StvnConstant(String fqni, StvnSchema schema, StvnValue value) {

    /**
     * Canonical constructor verifying non-null constant components.
     *
     * @param fqni   the Fully Qualified Nominal Identifier of the constant
     * @param schema the governing normalized {@link StvnSchema}
     * @param value  the validated and lowered {@link StvnValue}
     */
    public StvnConstant {
      Objects.requireNonNull(fqni, "fqni must not be null");
      Objects.requireNonNull(schema, "schema must not be null");
      Objects.requireNonNull(value, "value must not be null");
    }
  }
}
