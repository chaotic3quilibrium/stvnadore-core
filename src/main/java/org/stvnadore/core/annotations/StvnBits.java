package org.stvnadore.core.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Configures the target bit-width and signedness constraints for numeric fields and record components.
 * <p>
 * This annotation allows developers to map native Java integer types (like {@code int} or {@code long})
 * to specific STVN integer widths (such as 8, 16, 32, or 64 bits) and enforce unsigned integer decoding behavior.
 * <p>
 * <b>Preconditions:</b>
 * <ul>
 *   <li>The target element must be a record component or a field.</li>
 *   <li>The annotated type must resolve to a numeric category.</li>
 * </ul>
 * <p>
 * <b>Example:</b>
 * <pre>{@code
 * public record DeviceInfo(
 *     @StvnBits(value = 16, unsigned = true) int port
 * ) {}
 * }</pre>
 *
 * @since 1.0.0
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface StvnBits {
  /**
   * The target bit-width constraint for the integer representation (e.g. 8, 16, 32, 64).
   *
   * @return the bit-width size constraint
   */
  int value();

  /**
   * Specifies whether the target integer should be decoded and validated as an unsigned value.
   * Defaults to {@code false} (signed).
   *
   * @return {@code true} if unsigned validation is required, otherwise {@code false}
   */
  boolean unsigned() default false;
}
