package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.binary.StvnSchemaHasher;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test suite for opaque nominal branding and CAS bijectivity.
 * <p>
 * Verifies that distinct nominal types (such as {@code :UserId} and {@code :AccountId})
 * do not unwrap to primitive structural equality, and emit unique CAS schema hashes.
 */
public class StvnOpaqueNominalBrandingTest {

  @Test
  @DisplayName("TC-NOM-01: Distinct nominal types are not interchangeable during constant evaluation")
  void testDistinctNominalTypesRejected() {
    String source = """
        {
          :defs {
            :UserId :Int
            :AccountId :Int
            #AccId :AccountId 20
          }
          :type :UserId
          :body #AccId
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Incompatible nominal constant must be rejected");
    var error = result.diagnostics().getFirst();
    assertEquals(DiagnosticBag.ERR_INCOMPATIBLE_NOMINAL_TYPE, error.errorCode().orElse(null));
    assertTrue(error.message().contains("cannot be assigned to target nominal type"));
  }

  @Test
  @DisplayName("TC-NOM-02: Matching nominal types compile successfully")
  void testMatchingNominalTypesAccepted() {
    String source = """
        {
          :defs {
            :UserId :Int
            #AdminUser :UserId 1
          }
          :type :UserId
          :body #AdminUser
        }
        """;
    var result = StvnCompiler.compileToResult(source, null, StvnParserConfig.DEFAULT);
    assertFalse(result.hasErrors(), "Matching nominal constant must compile cleanly");
  }

  @Test
  @DisplayName("TC-NOM-03: Distinct nominal schemas produce distinct CAS hashes")
  void testNominalSchemasProduceUniqueCasHashes() {
    String source1 = "{ :defs { :UserId :Int } :type :UserId :body 42 }";
    String source2 = "{ :defs { :AccountId :Int } :type :AccountId :body 42 }";

    var ast1 = StvnCompiler.compile(source1).orElseThrow();
    var ast2 = StvnCompiler.compile(source2).orElseThrow();

    assertNotNull(ast1.schema());
    assertNotNull(ast2.schema());

    java.util.UUID hash1 = StvnSchemaHasher.hashSchema(ast1.schema());
    java.util.UUID hash2 = StvnSchemaHasher.hashSchema(ast2.schema());

    assertNotEquals(hash1, hash2,
        "Nominal schemas :UserId and :AccountId must produce distinct CAS hashes");
  }
}
