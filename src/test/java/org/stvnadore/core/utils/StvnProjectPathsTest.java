package org.stvnadore.core.utils;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.stvnadore.core.matrix.MetadataFixtureGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression suite verifying the deterministic {@code ij_stvnadore_core} module basedir
 * resolution contract and fail-fast rogue-directory guards in {@link StvnProjectPaths},
 * {@link MetadataFixtureGenerator}, and {@link IrGeneratorUtility}.
 */
@NullMarked
class StvnProjectPathsTest {

  @Test
  @DisplayName("TC-BASEDIR-01: Explicit -Dstvn.project.basedir resolves both absolute and relative module roots")
  void testExplicitConfiguredBasedirAbsoluteAndRelative(@TempDir Path tempRoot) throws IOException {
    Path moduleRoot = tempRoot.resolve("ij_stvnadore_core");
    Files.createDirectories(moduleRoot.resolve("shared-fixtures").resolve("syntax"));
    Path unrelatedWorkingDir = tempRoot.resolve("sibling_workspace");
    Files.createDirectories(unrelatedWorkingDir);

    Path resolvedAbsolute = StvnProjectPaths.resolveProjectBasedir(moduleRoot.toString(), unrelatedWorkingDir);
    assertEquals(moduleRoot.toAbsolutePath().normalize(), resolvedAbsolute);

    Path resolvedRelative = StvnProjectPaths.resolveProjectBasedir("../ij_stvnadore_core", unrelatedWorkingDir);
    assertEquals(moduleRoot.toAbsolutePath().normalize(), resolvedRelative);
  }

  @Test
  @DisplayName("TC-BASEDIR-02: Invalid -Dstvn.project.basedir fails fast with STE IllegalStateException even when decoy metadata folder exists")
  void testInvalidExplicitBasedirFailsFastWithZeroSideEffects(@TempDir Path tempRoot) throws IOException {
    Path emptyDir = tempRoot.resolve("empty_root");
    Files.createDirectories(emptyDir);

    IllegalStateException emptyEx = assertThrows(
        IllegalStateException.class,
        () -> StvnProjectPaths.resolveProjectBasedir(emptyDir.toString(), tempRoot)
    );
    assertTrue(emptyEx.getMessage().contains("-Dstvn.project.basedir="));
    assertTrue(emptyEx.getMessage().contains("'shared-fixtures/syntax'"));
    try (Stream<Path> entries = Files.list(emptyDir)) {
      assertEquals(List.of(), entries.toList(), "Zero files or directories may be created under invalid basedir");
    }

    Path decoyRoot = tempRoot.resolve("decoy_workspace_root");
    Files.createDirectories(decoyRoot.resolve("shared-fixtures").resolve("metadata"));

    IllegalStateException decoyEx = assertThrows(
        IllegalStateException.class,
        () -> StvnProjectPaths.resolveProjectBasedir(decoyRoot.toString(), tempRoot)
    );
    assertTrue(decoyEx.getMessage().contains("'shared-fixtures/syntax'"));
  }

  @Test
  @DisplayName("TC-BASEDIR-03: Unset or blank property resolves working directory directly when shared-fixtures/syntax exists")
  void testDirectModuleWorkingDirectoryFallback(@TempDir Path tempRoot) throws IOException {
    Path moduleRoot = tempRoot.resolve("ij_stvnadore_core");
    Files.createDirectories(moduleRoot.resolve("shared-fixtures").resolve("syntax"));

    assertEquals(moduleRoot.toAbsolutePath().normalize(), StvnProjectPaths.resolveProjectBasedir(null, moduleRoot));
    assertEquals(moduleRoot.toAbsolutePath().normalize(), StvnProjectPaths.resolveProjectBasedir("   ", moduleRoot));
  }

  @Test
  @DisplayName("TC-BASEDIR-04: Workspace parent root resolves ij_stvnadore_core child even when decoy root shared-fixtures/metadata exists")
  void testWorkspaceParentRootFallbackIgnoresDecoyRootMetadataDirectory(@TempDir Path workspaceRoot) throws IOException {
    // Stage a stray decoy shared-fixtures/metadata directory at workspace parent root (without syntax/)
    Files.createDirectories(workspaceRoot.resolve("shared-fixtures").resolve("metadata"));
    // Stage canonical ij_stvnadore_core/shared-fixtures/syntax child module directory
    Path canonicalModuleRoot = workspaceRoot.resolve("ij_stvnadore_core");
    Files.createDirectories(canonicalModuleRoot.resolve("shared-fixtures").resolve("syntax"));

    Path resolved = StvnProjectPaths.resolveProjectBasedir(null, workspaceRoot);
    assertEquals(
        canonicalModuleRoot.toAbsolutePath().normalize(),
        resolved,
        "Resolver must reject decoy workspaceRoot/shared-fixtures/metadata and select workspaceRoot/ij_stvnadore_core"
    );
  }

  @Test
  @DisplayName("TC-BASEDIR-05: Unanchored ancestor working directory fails fast without creating rogue shared-fixtures folder")
  void testUnanchoredWorkingDirectoryFailsFastWithZeroDirectoriesCreated(@TempDir Path unanchoredDir) throws IOException {
    IllegalStateException ex = assertThrows(
        IllegalStateException.class,
        () -> StvnProjectPaths.resolveProjectBasedir(null, unanchoredDir)
    );
    assertTrue(ex.getMessage().contains("Cannot resolve 'ij_stvnadore_core' module root"));
    assertTrue(ex.getMessage().contains("'shared-fixtures/syntax'"));
    assertTrue(ex.getMessage().contains("-Dstvn.project.basedir=<path-to-ij_stvnadore_core>"));

    try (Stream<Path> entries = Files.list(unanchoredDir)) {
      assertEquals(List.of(), entries.toList(), "Unanchored directory must remain completely empty");
    }
  }

  @Test
  @DisplayName("TC-BASEDIR-06: MetadataFixtureGenerator and IrGeneratorUtility fail fast before creating any directory when stvn.project.basedir is invalid")
  void testGeneratorsFailFastUnderInvalidSystemPropertyWithoutCreatingStrayFolders(@TempDir Path badRoot) throws IOException {
    @Nullable String previousProp = System.getProperty(StvnProjectPaths.BASEDIR_PROPERTY);
    try {
      System.setProperty(StvnProjectPaths.BASEDIR_PROPERTY, badRoot.toString());

      assertThrows(IllegalStateException.class, MetadataFixtureGenerator::resolveDefaultOutputRoot);
      assertThrows(IllegalStateException.class, () -> MetadataFixtureGenerator.main(new String[0]));
      assertThrows(IllegalStateException.class, () -> IrGeneratorUtility.syncNegativeBinaryDefsFixtures(true));
      assertThrows(IllegalStateException.class, () -> IrGeneratorUtility.main(new String[0]));

      try (Stream<Path> entries = Files.list(badRoot)) {
        assertEquals(
            List.of(),
            entries.toList(),
            "Neither MetadataFixtureGenerator nor IrGeneratorUtility may create shared-fixtures in an invalid root"
        );
      }
    } finally {
      if (previousProp == null) {
        System.clearProperty(StvnProjectPaths.BASEDIR_PROPERTY);
      } else {
        System.setProperty(StvnProjectPaths.BASEDIR_PROPERTY, previousProp);
      }
    }
  }

  @Test
  @DisplayName("TC-BASEDIR-07: Live ij_stvnadore_core module resolution and read-only IrGeneratorUtility verification succeed")
  void testLiveModuleResolutionAndReadOnlySnapshotPipeline() throws Exception {
    Path resolvedRoot = StvnProjectPaths.resolveProjectBasedir();
    assertTrue(Files.isRegularFile(resolvedRoot.resolve("pom.xml")), "Resolved root must contain pom.xml");
    assertTrue(Files.isDirectory(StvnProjectPaths.sharedFixturesRoot().resolve("syntax")), "shared-fixtures/syntax must exist");
    assertTrue(Files.isDirectory(StvnProjectPaths.sharedFixturesRoot().resolve("metadata")), "shared-fixtures/metadata must exist");
    assertTrue(Files.isDirectory(StvnProjectPaths.sharedFixturesRoot().resolve("formatting")), "shared-fixtures/formatting must exist");
    assertTrue(
        Files.isRegularFile(StvnProjectPaths.resolve("docs", "architecture", "STVN_LANGUAGE_SPEC.md")),
        "STVN_LANGUAGE_SPEC.md must resolve cleanly"
    );

    // Verify negative .stvn_bd fixture parity in read-only mode (writeToDisk = false)
    IrGeneratorUtility.syncNegativeBinaryDefsFixtures(false);
  }
}
