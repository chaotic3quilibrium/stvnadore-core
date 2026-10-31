package org.stvnadore.core.validation;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.ir.StvnDefsCatalog;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.lowering.StvnDefsLowerer;

import java.util.Map;
import java.util.Optional;

/**
 * Pure-IR plugin boundary null-safety and fail-closed contract tests replacing the excised 1.x {@code StvnTypeResolver} boundary tests.
 */
class StvnTypeResolverPluginBoundaryTest {

  @Test
  void testReservedNamespacePredicatesHandleNullSafely() {
    Assertions.assertFalse(StvnDefsLowerer.isReservedPackage(null));
    Assertions.assertFalse(StvnDefsLowerer.isReservedDomain(null));
    Assertions.assertFalse(StvnDefsLowerer.isReservedConstant(null));
    Assertions.assertTrue(StvnDefsLowerer.isReservedPackage(":org/stvnadore/prelude"));
    Assertions.assertTrue(StvnDefsLowerer.isReservedDomain("stvn_core"));
    Assertions.assertTrue(StvnDefsLowerer.isReservedConstant("#org/stvnadore/prelude/PI"));
  }

  @Test
  void testSliceTerminalPreservesLeadingSigils() {
    Assertions.assertEquals(":MyType", StvnDefsLowerer.sliceTerminal(":pkg/sub/MyType"));
    Assertions.assertEquals("#MY_CONST", StvnDefsLowerer.sliceTerminal("#pkg/sub/MY_CONST"));
    Assertions.assertEquals(":RootType", StvnDefsLowerer.sliceTerminal(":RootType"));
  }

  @Test
  void testStvnCompilerNullGuards() {
    Assertions.assertThrows(NullPointerException.class, () -> StvnCompiler.compile((String) null));
    Assertions.assertThrows(NullPointerException.class, () -> StvnCompiler.compile("{}", null, null));
    Assertions.assertThrows(NullPointerException.class, () -> StvnCompiler.compileToResult((String) null));
    Assertions.assertThrows(NullPointerException.class, () -> StvnCompiler.analyze(null));
    Assertions.assertThrows(NullPointerException.class, () -> StvnCompiler.analyze("{}", null, null));
    Assertions.assertThrows(NullPointerException.class, () -> StvnCompiler.hasErrorNodes(null));
  }

  @Test
  void testStvnDefsCatalogNullGuards() {
    Assertions.assertThrows(NullPointerException.class, () -> new StvnDefsCatalog(null, Map.of()));
    Assertions.assertThrows(NullPointerException.class, () -> new StvnDefsCatalog(Map.of(), null));
    Assertions.assertThrows(NullPointerException.class, () -> StvnDefsCatalog.EMPTY.findType(null));
    Assertions.assertThrows(NullPointerException.class, () -> StvnDefsCatalog.EMPTY.findConstant(null));
    Assertions.assertEquals(Optional.empty(), StvnDefsCatalog.EMPTY.findType(":Missing"));
    Assertions.assertEquals(Optional.empty(), StvnDefsCatalog.EMPTY.findConstant("#MISSING"));
  }

  @Test
  void testStvnDocumentNullGuards() {
    Assertions.assertThrows(
        NullPointerException.class,
        () -> new StvnDocument(null, StvnDefsCatalog.EMPTY, Optional.empty(), Optional.empty(), false, false)
    );
    Assertions.assertThrows(
        NullPointerException.class,
        () -> new StvnDocument(Optional.empty(), null, Optional.empty(), Optional.empty(), false, false)
    );
    Assertions.assertThrows(
        NullPointerException.class,
        () -> new StvnDocument(Optional.empty(), StvnDefsCatalog.EMPTY, null, Optional.empty(), false, false)
    );
    Assertions.assertThrows(
        NullPointerException.class,
        () -> new StvnDocument(Optional.empty(), StvnDefsCatalog.EMPTY, Optional.empty(), null, false, false)
    );
  }

  @Test
  void testEmptyAndRecoveredPluginCompilationNeverThrows() {
    var emptyRes = StvnCompiler.compileToResult("{}", null, StvnParserConfig.DEFAULT);
    Assertions.assertFalse(emptyRes.hasErrors());
    Assertions.assertTrue(emptyRes.document().isEmpty());

    var malformedRes = StvnCompiler.compileToResult("{ :type :UnknownPluginType :body 1 }", null, StvnParserConfig.DEFAULT);
    Assertions.assertTrue(malformedRes.hasErrors());
    Assertions.assertFalse(malformedRes.diagnostics().isEmpty());
  }
}
