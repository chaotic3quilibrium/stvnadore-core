package org.stvnadore.core.test;

import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ir.StvnSchema;

/**
 * Factory class providing unified, valid pure-IR schemas for testing purposes.
 */
public final class StvnTestFactory {

  private StvnTestFactory() {}

  /**
   * Compiles a canonical {@code :Int} payload through {@link StvnCompiler} and returns
   * its lowered pure-IR {@link StvnSchema}.
   *
   * @return a lowered pure-IR {@code :Int} schema
   */
  public static StvnSchema createDummySchema() {
    return StvnCompiler.compilePayload("{ :type :Int :body 0 }").orElseThrow().schema();
  }
}
