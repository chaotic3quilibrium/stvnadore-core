package org.stvnadore.core.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares mathematical minimum and maximum range boundary constraints on integer record components or fields.
 * <p>
 * This annotation allows developers to restrict mapped integer values to specific closed intervals.
 *
 * @since 1.0.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface StvnInt {
  /**
   * The minimum inclusive value constraint allowed for the integer field.
   * Defaults to {@link Long#MIN_VALUE}.
   *
   * @return the inclusive minimum boundary
   */
  long minIncl() default Long.MIN_VALUE;

  /**
   * The maximum inclusive value constraint allowed for the integer field.
   * Defaults to {@link Long#MAX_VALUE}.
   *
   * @return the inclusive maximum boundary
   */
  long maxIncl() default Long.MAX_VALUE;
}
