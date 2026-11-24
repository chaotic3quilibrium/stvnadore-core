package org.stvnadore.core.printer;

import org.jspecify.annotations.NullMarked;

/**
 * Section-scoped wrapping policy governing single-line versus multi-line container layout in
 * {@link StvnSourceFormatter} (Operation 1: Non-Destructive Authoring Source Formatting).
 *
 * @since 2.0.0
 */
@NullMarked
public enum NestedContainerWrapPolicy {

  /**
   * Preserves authored single-line versus multi-line container boundaries unless interior
   * {@code //} line comments or multi-line Rule STR-04 fenced strings require multi-line layout.
   */
  PRESERVE_AUTHORED,

  /**
   * Expands single-line composite containers that contain nested composite constructs onto
   * multiple 2-space indented lines while keeping leaf wrappers, enumerations, single-line
   * metadata maps, and scalar-only collections on a single line.
   */
  EXPAND_NESTED
}
