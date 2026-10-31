package org.stvnadore.core.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares STVN 2.0.0 validation facets on {@link String} record components or fields.
 * <p>
 * Maps directly to the pure-IR {@link org.stvnadore.core.ir.StvnSchema.StringSchema} cardinality
 * and regular-expression facets ({@code #minSize}, {@code #maxSize}, and {@code #regex}).
 *
 * @since 2.0.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface StvnString {

  /**
   * Minimum inclusive Unicode code-point count ({@code #minSize}).
   * Defaults to {@code 0}.
   *
   * @return the minimum inclusive code-point length
   */
  int minSize() default 0;

  /**
   * Maximum inclusive Unicode code-point count ({@code #maxSize}).
   * Defaults to {@code 16_777_216} ({@link org.stvnadore.core.StvnVocabulary#DEFAULT_MAX_SIZE}).
   *
   * @return the maximum inclusive code-point length
   */
  int maxSize() default 16_777_216;

  /**
   * Optional Java/ECMA regular-expression pattern ({@code #regex}).
   * Defaults to {@code ""} (unconstrained).
   *
   * @return the regular-expression pattern, or {@code ""} if unconstrained
   */
  String regex() default "";
}
