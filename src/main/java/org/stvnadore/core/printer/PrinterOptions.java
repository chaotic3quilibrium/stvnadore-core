package org.stvnadore.core.printer;

import org.jspecify.annotations.NullMarked;

/**
 * Holds options configuration for STVN text output formatting.
 * <p>
 * These settings govern the structural visualization (such as indentation size,
 * symbol style, and sum-type tagging rules) when rendering values to text.
 * They alter the layout visual presentation without modifying the underlying value data semantics.
 * </p>
 *
 * @param coverage       controls whether the printed document includes the full metadata headers
 *                       ({@code :defs} and {@code :type}) or only the value body ({@code :body})
 * @param indentStep     the number of spaces to insert per indentation level in pretty-printed layout.
 *                       A value of {@code 0} suppresses newlines and spacing, producing compact output.
 * @param symbolStyle    toggles between verbose, descriptive keyword names (e.g. {@code #TRUE}, {@code #Some})
 *                       and abbreviated short-forms (e.g. {@code #T}, {@code #S})
 * @param sumTypePolicy  configures whether Option and Either wrapping tags are explicitly written,
 *                       or omitted when their types are unambiguously inferred from the schema
 */
@NullMarked
public record PrinterOptions(
    Coverage coverage,
    int indentStep,
    SymbolStyle symbolStyle,
    SumTypePolicy sumTypePolicy
) {
  /**
   * Defines the scope of sections emitted in the output document.
   */
  public enum Coverage {
    /**
     * Outputs all document components: the {@code :defs} section, the {@code :type} signature,
     * and the {@code :body} payload.
     */
    ALL_SECTIONS,

    /**
     * Outputs only the {@code :body} value payload, omitting the metadata blocks.
     */
    BODY_ONLY
  }

  /**
   * Defines the style format of keywords and tags.
   */
  public enum SymbolStyle {
    /**
     * Enforces verbose long-form representation (e.g. {@code #TRUE}, {@code #FALSE},
     * {@code #Some}, {@code #None}, {@code #Left}, {@code #Right}).
     */
    LONG_FORM,

    /**
     * Enforces abbreviated short-form representation (e.g. {@code #T}, {@code #F},
     * {@code #S}, {@code #N}, {@code #L}, {@code #R}).
     */
    SHORT_FORM
  }

  /**
   * Defines the tagging strategy for Option and Either sum types.
   */
  public enum SumTypePolicy {
    /**
     * Always writes explicit wrapping tags (like {@code #Some} or {@code #Right})
     * even when types are clear from the schema context.
     */
    FORCE_EXPLICIT,

    /**
     * Omit tags when the type structure resolves unambiguously under the target schema.
     */
    HAPPY_PATH_INFERRED
  }

  /**
   * Constructs a new {@code PrinterOptions} with default settings.
   * <p>
   * Default settings render all sections ({@link Coverage#ALL_SECTIONS}), use 4-space
   * indentation ({@code indentStep = 4}), select verbose keywords ({@link SymbolStyle#LONG_FORM}),
   * and allow implied tagging ({@link SumTypePolicy#HAPPY_PATH_INFERRED}).
   * </p>
   */
  public PrinterOptions() {
    this(Coverage.ALL_SECTIONS, 4, SymbolStyle.LONG_FORM, SumTypePolicy.HAPPY_PATH_INFERRED);
  }
}
