package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test suite validating explicit cardinality bounds (#minSize, #maxSize)
 * and fail-fast validation on standard library prelude string types (:IPv4, :Email, :SemVer).
 */
public class StvnPreludeStringBoundsTest {

  @Test
  @DisplayName("TC-PRELUDE-01: Validates IPv4 bounds [7..15]")
  void testIPv4Bounds() {
    String valid = """
        {
          :defs {
            :Ip :org/stvnadore/prelude/IPv4
          }
          :type :Ip
          :body "192.168.1.1"
        }
        """;
    var resValid = StvnCompiler.compileToResult(valid, null, StvnParserConfig.DEFAULT);
    assertTrue(resValid.isSuccess(), "Valid IPv4 '192.168.1.1' (11 chars) must compile cleanly");

    String tooShort = """
        {
          :defs {
            :Ip :org/stvnadore/prelude/IPv4
          }
          :type :Ip
          :body "1.1.1"
        }
        """;
    var resShort = StvnCompiler.compileToResult(tooShort, null, StvnParserConfig.DEFAULT);
    assertFalse(resShort.isSuccess(), "Short IPv4 '1.1.1' (5 chars) must violate #minSize 7");
    assertTrue(resShort.diagnostics().stream().anyMatch(d ->
        d.message().contains("violates #minSize constraint (7)")));

    String tooLong = """
        {
          :defs {
            :Ip :org/stvnadore/prelude/IPv4
          }
          :type :Ip
          :body "192.168.1.1.extra"
        }
        """;
    var resLong = StvnCompiler.compileToResult(tooLong, null, StvnParserConfig.DEFAULT);
    assertFalse(resLong.isSuccess(), "Long IPv4 '192.168.1.1.extra' (17 chars) must violate #maxSize 15");
    assertTrue(resLong.diagnostics().stream().anyMatch(d ->
        d.message().contains("exceeds maximum length of 15 characters")));
  }

  @Test
  @DisplayName("TC-PRELUDE-02: Validates Email bounds [3..254]")
  void testEmailBounds() {
    String valid = """
        {
          :defs {
            :E :org/stvnadore/prelude/Email
          }
          :type :E
          :body "user@example.com"
        }
        """;
    var resValid = StvnCompiler.compileToResult(valid, null, StvnParserConfig.DEFAULT);
    assertTrue(resValid.isSuccess(), "Valid email 'user@example.com' must compile cleanly");

    String tooShort = """
        {
          :defs {
            :E :org/stvnadore/prelude/Email
          }
          :type :E
          :body "a@"
        }
        """;
    var resShort = StvnCompiler.compileToResult(tooShort, null, StvnParserConfig.DEFAULT);
    assertFalse(resShort.isSuccess(), "Short email 'a@' (2 chars) must violate #minSize 3");
    assertTrue(resShort.diagnostics().stream().anyMatch(d ->
        d.message().contains("violates #minSize constraint (3)")));
  }

  @Test
  @DisplayName("TC-PRELUDE-03: Validates SemVer bounds [5..128]")
  void testSemVerBounds() {
    String valid = """
        {
          :defs {
            :V :org/stvnadore/prelude/SemVer
          }
          :type :V
          :body "1.0.0-rc.1"
        }
        """;
    var resValid = StvnCompiler.compileToResult(valid, null, StvnParserConfig.DEFAULT);
    assertTrue(resValid.isSuccess(), "Valid SemVer '1.0.0-rc.1' must compile cleanly");

    String tooShort = """
        {
          :defs {
            :V :org/stvnadore/prelude/SemVer
          }
          :type :V
          :body "1.0"
        }
        """;
    var resShort = StvnCompiler.compileToResult(tooShort, null, StvnParserConfig.DEFAULT);
    assertFalse(resShort.isSuccess(), "Short SemVer '1.0' (3 chars) must violate #minSize 5");
    assertTrue(resShort.diagnostics().stream().anyMatch(d ->
        d.message().contains("violates #minSize constraint (5)")));
  }

  @Test
  @DisplayName("TC-PRELUDE-04: Rejects out-of-order facets on prelude aliases with ERR_FACET_ORDER_VIOLATION")
  void testFacetOrderViolationOnPreludeAlias() {
    String outOfOrder = """
        {
          :defs {
            :CustomIp { #regex "^.*$" #minSize 7 } :org/stvnadore/prelude/IPv4
          }
          :type :CustomIp
          :body "127.0.0.1"
        }
        """;
    var result = StvnCompiler.compileToResult(outOfOrder, null, StvnParserConfig.DEFAULT);
    assertFalse(result.isSuccess(), "Out-of-order facets (#regex before #minSize) must fail closed");
    assertTrue(result.diagnostics().stream().anyMatch(d ->
        DiagnosticBag.ERR_FACET_ORDER_VIOLATION.equals(d.errorCode().orElse(null))),
        "Expected ERR_FACET_ORDER_VIOLATION for out-of-order facets");
  }
}
