package org.stvnadore.core.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares validation constraints on string record components or fields.
 * <p>
 * This annotation allows developers to enforce constraints such as non-emptiness on mapped string fields.
 *
 * @since 1.0.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface StvnString {
  /**
   * If {@code true}, validation mandates that the annotated string has a length of at least 1.
   * Defaults to {@code false}.
   *
   * @return {@code true} if non-empty validation is required, otherwise {@code false}
   */
  boolean nonEmpty() default false;
}
