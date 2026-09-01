package org.stvnadore.core.ir;

import org.jspecify.annotations.NullMarked;

/**
 * Represents a single resolution step taken during sum-type unwrapping.
 *
 * @param tag        the representation of the variant tag (e.g., "#Some", "#Right", "#None", "#Left")
 * @param isInferred true if the variant step was auto-synthesized by implicit resolution rules
 */
@NullMarked
public record VariantStep(String tag, boolean isInferred) {
  /**
   * Canonical constructor verifying that the tag is non-null.
   */
  public VariantStep {
    java.util.Objects.requireNonNull(tag);
  }
}
