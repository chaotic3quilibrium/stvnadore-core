package org.stvnadore.core.validation;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.parser.StvnParser;
import java.util.Optional;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;

class StvnTypeResolverPluginBoundaryTest {

  @Test
  void test_getDocumentDefinitions() {
    StvnTypeResolver.getDocumentDefinitions(null);
  }

  @Test
  void test_extractConstraints() {
    StvnTypeResolver.extractConstraints(null);
  }

  @Test
  void test_findDefInDocument() {
    StvnTypeResolver.findDefInDocument(null, "");
  }

  @Test
  void test_findAllDefinitions() {
    StvnTypeResolver.findAllDefinitions(null, "");
  }

  @Test
  void test_findTypeDefinition() {
    StvnTypeResolver.findTypeDefinition(null, "");
  }

  @Test
  void test_resolvePrimitiveSchema() {
    StvnTypeResolver.resolvePrimitiveSchema(null, null, new HashSet<>());
  }

  @Test
  void test_applyDefaults() {
    Assertions.assertThrows(MalformedSchemaException.class, () -> {
      StvnTypeResolver.applyDefaults(null);
    });
  }

  @Test
  void test_deriveAndApplyTraits() {
    Assertions.assertThrows(MalformedSchemaException.class, () -> {
      StvnTypeResolver.deriveAndApplyTraits(null, new ArrayList<>());
    });
  }

  @Test
  void test_getPrimitiveBaseType() {
    StvnTypeResolver.getPrimitiveBaseType(null);
  }

  @Test
  void test_resolveSchemaNode() {
    StvnTypeResolver.resolveSchemaNode(null, null);
  }

  @Test
  void test_isSameSchemaNode() {
    StvnTypeResolver.isSameSchemaNode(null, null, null);
  }

  @Test
  void test_canMatch() {
    StvnTypeResolver.canMatch(null, null, StvnTypeResolver.LiteralType.STRING_LITERAL, "");
  }

  @Test
  void test_isValidSchemaType() {
    StvnTypeResolver.isValidSchemaType(null);
  }

  @Test
  void test_getInnerSchemas() {
    Assertions.assertThrows(MalformedSchemaException.class, () -> {
      StvnTypeResolver.getInnerSchemas(null);
    });
  }

  @Test
  void test_resolveCandidateSchemas() {
    StvnTypeResolver.resolveCandidateSchemas(null, null);
  }

  @Test
  void test_isAncestor() {
    StvnTypeResolver.isAncestor(null, null);
  }

  @Test
  void test_validateDocumentConstraints() {
    StvnTypeResolver.validateDocumentConstraints(null);
  }

  @Test
  void test_validateTypeDefinition() {
    Assertions.assertThrows(MalformedSchemaException.class, () -> {
      StvnTypeResolver.validateTypeDefinition(null, null);
    });
  }

  @Test
  void test_findAliasNameForSchemaType() {
    StvnTypeResolver.findAliasNameForSchemaType(null, null);
  }

  @Test
  void test_DefSourceConstructor() {
    Assertions.assertThrows(MalformedSchemaException.class, () -> {
      new StvnTypeResolver.DefSource(null, "module");
    });
  }

  @Test
  void test_ResolvedSchemaConstructor1() {
    Assertions.assertThrows(NullPointerException.class, () -> {
      new StvnTypeResolver.ResolvedSchema(null, StvnTypeResolver.StvnConstraints.empty(), Optional.empty());
    });
  }

  @Test
  void test_ResolvedSchemaConstructor2() {
    Assertions.assertThrows(NullPointerException.class, () -> {
      new StvnTypeResolver.ResolvedSchema(null, StvnTypeResolver.StvnConstraints.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    });
  }

  @Test
  void test_StvnConstraintsMerge() {
    Assertions.assertThrows(MalformedSchemaException.class, () -> {
      StvnTypeResolver.StvnConstraints.empty().merge(null);
    });
  }
}
