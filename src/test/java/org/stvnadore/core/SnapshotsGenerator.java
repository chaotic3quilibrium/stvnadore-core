package org.stvnadore.core;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.utils.IrGeneratorUtility;

/**
 * Command-line test utility entrypoint delegating shared-fixture and Option B2 pure-IR snapshot
 * synchronization and verification to {@link IrGeneratorUtility}.
 */
@NullMarked
public final class SnapshotsGenerator {

  private SnapshotsGenerator() {
    // Utility class
  }

  /**
   * Executes the shared-fixture and Option B2 pure-IR snapshot verification or regeneration pipeline.
   *
   * @param args command-line arguments forwarded to {@link IrGeneratorUtility#main(String[])}
   * @throws Exception if snapshot processing or fixture verification fails
   */
  public static void main(String[] args) throws Exception {
    IrGeneratorUtility.main(args);
  }
}
