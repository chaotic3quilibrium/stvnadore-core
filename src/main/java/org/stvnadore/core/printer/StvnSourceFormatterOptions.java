package org.stvnadore.core.printer;

import java.util.Objects;
import org.jspecify.annotations.NullMarked;

/**
 * Immutable configuration options for {@link StvnSourceFormatter} controlling section-scoped
 * nested container wrapping across schema declarations ({@code :defs} and {@code :type}) and
 * value payloads ({@code :body} and {@code #CONST} right-hand-side values).
 *
 * @param schemaNestedWrapPolicy  wrapping policy for {@code :defs} and {@code :type} schema
 *                                constructors and {@code metadataMap} blocks
 * @param payloadNestedWrapPolicy wrapping policy for {@code :body} values and {@code :defs}
 *                                {@code ConstantDefinition} right-hand-side values
 * @since 2.0.0
 */
@NullMarked
public record StvnSourceFormatterOptions(
    NestedContainerWrapPolicy schemaNestedWrapPolicy,
    NestedContainerWrapPolicy payloadNestedWrapPolicy
) {

  /**
   * Default non-destructive authoring options preserving single-line and multi-line container
   * boundaries across both schema and payload sections.
   */
  public static final StvnSourceFormatterOptions DEFAULT = new StvnSourceFormatterOptions(
      NestedContainerWrapPolicy.PRESERVE_AUTHORED,
      NestedContainerWrapPolicy.PRESERVE_AUTHORED
  );

  /**
   * Constructs validated {@code StvnSourceFormatterOptions}.
   *
   * @param schemaNestedWrapPolicy  wrapping policy for {@code :defs} and {@code :type} schema
   *                                constructors and {@code metadataMap} blocks
   * @param payloadNestedWrapPolicy wrapping policy for {@code :body} values and {@code :defs}
   *                                {@code ConstantDefinition} right-hand-side values
   * @throws NullPointerException if {@code schemaNestedWrapPolicy} or
   *                              {@code payloadNestedWrapPolicy} is {@code null}
   */
  public StvnSourceFormatterOptions {
    Objects.requireNonNull(schemaNestedWrapPolicy, "schemaNestedWrapPolicy must not be null");
    Objects.requireNonNull(payloadNestedWrapPolicy, "payloadNestedWrapPolicy must not be null");
  }
}
