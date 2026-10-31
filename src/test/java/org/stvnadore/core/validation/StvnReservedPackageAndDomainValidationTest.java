package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.stdlib.StvnPrelude;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive verification test suite for Reserved Package Namespace (E037)
 * and Reserved System Metadata Domain (E038) validation.
 * <p>
 * Verifies normative compile-time rejection rules codified in STVN Language Specification
 * Section 3.9 Invariant 8, Section 3.10.1 Rule 6, Appendix C.2, and Appendix C.3.
 */
public class StvnReservedPackageAndDomainValidationTest {

  @Test
  @DisplayName("TC-RES-01: Rejection of exact reserved package :org/stvnadore")
  void testExactReservedPackageRejection() {
    String source = """
        {
          :defs {
            :package :org/stvnadore {
              :CustomType :Int
            }
          }
          :type :Int
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    assertTrue(result.hasErrors(), "Must reject exact reserved package :org/stvnadore");
    assertTrue(
        result.diagnostics().stream().anyMatch(d ->
            DiagnosticBag.ERR_RESERVED_PACKAGE_PROHIBITED.equals(d.errorCode().orElse(null))),
        "Must emit ERR_RESERVED_PACKAGE_PROHIBITED (E037): " + result.diagnostics()
    );
  }

  @Test
  @DisplayName("TC-RES-02: Rejection of prelude package :org/stvnadore/prelude")
  void testPreludeSubPackageRejection() {
    String source = """
        {
          :defs {
            :package :org/stvnadore/prelude {
              :CustomPort :Int
            }
          }
          :type :Int
          :body 8080
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    assertTrue(result.hasErrors(), "Must reject standard prelude package claim");
    assertTrue(
        result.diagnostics().stream().anyMatch(d ->
            DiagnosticBag.ERR_RESERVED_PACKAGE_PROHIBITED.equals(d.errorCode().orElse(null))),
        "Must emit ERR_RESERVED_PACKAGE_PROHIBITED (E037): " + result.diagnostics()
    );
  }

  @Test
  @DisplayName("TC-RES-03: Rejection of arbitrary sub-package :org/stvnadore/custom/network")
  void testArbitraryReservedSubPackageRejection() {
    String source = """
        {
          :defs {
            :package :org/stvnadore/custom/network {
              :Endpoint :String
            }
          }
          :type :String
          :body "https://api.internal"
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    assertTrue(result.hasErrors(), "Must reject arbitrary sub-packages under :org/stvnadore/*");
    assertTrue(
        result.diagnostics().stream().anyMatch(d ->
            DiagnosticBag.ERR_RESERVED_PACKAGE_PROHIBITED.equals(d.errorCode().orElse(null))),
        "Must emit ERR_RESERVED_PACKAGE_PROHIBITED (E037): " + result.diagnostics()
    );
  }

  @Test
  @DisplayName("TC-RES-04: Acceptance of disjoint prefix boundary :org/stvnadore_custom")
  void testDisjointPrefixPackageAcceptance() {
    String source = """
        {
          :defs {
            :package :org/stvnadore_custom {
              :LocalId :String
            }
          }
          :type :org/stvnadore_custom/LocalId
          :body "valid-identifier"
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    assertTrue(result.isSuccess(), "Must permit non-matching disjoint prefix :org/stvnadore_custom: " + result.diagnostics());
    assertTrue(
        result.diagnostics().stream().noneMatch(d ->
            DiagnosticBag.ERR_RESERVED_PACKAGE_PROHIBITED.equals(d.errorCode().orElse(null))),
        "Must not emit ERR_RESERVED_PACKAGE_PROHIBITED for disjoint prefix"
    );
  }

  @Test
  @DisplayName("TC-RES-05: Acceptance of user namespace :com/example/stvnadore")
  void testUserNamespaceWithStvnadoreSegmentAcceptance() {
    String source = """
        {
          :defs {
            :package :com/example/stvnadore {
              :Config :Int
            }
          }
          :type :com/example/stvnadore/Config
          :body 100
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    assertTrue(result.isSuccess(), "Must permit package containing stvnadore outside prefix: " + result.diagnostics());
    assertTrue(
        result.diagnostics().stream().noneMatch(d ->
            DiagnosticBag.ERR_RESERVED_PACKAGE_PROHIBITED.equals(d.errorCode().orElse(null))),
        "Must not emit ERR_RESERVED_PACKAGE_PROHIBITED for user package"
    );
  }

  @Test
  @DisplayName("TC-RES-06: Acceptance of arbitrary third-party package :io/github/project")
  void testThirdPartyPackageAcceptance() {
    String source = """
        {
          :defs {
            :package :io/github/project {
              :Payload :String
            }
          }
          :type :io/github/project/Payload
          :body "payload-data"
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    assertTrue(result.isSuccess(), "Must permit standard third-party package: " + result.diagnostics());
    assertTrue(
        result.diagnostics().stream().noneMatch(d ->
            DiagnosticBag.ERR_RESERVED_PACKAGE_PROHIBITED.equals(d.errorCode().orElse(null))),
        "Must not emit ERR_RESERVED_PACKAGE_PROHIBITED for third-party package"
    );
  }

  @Test
  @DisplayName("TC-RES-07: Rejection of qualified type definition :org/stvnadore/CustomType outside enclosure")
  void testQualifiedTypeDefinitionReservedNamespaceRejection() {
    String source = """
        {
          :defs {
            :org/stvnadore/CustomType :String
          }
          :type :String
          :body "test"
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    assertTrue(result.hasErrors(), "Must reject qualified type definition targeting reserved namespace");
    assertTrue(
        result.diagnostics().stream().anyMatch(d ->
            DiagnosticBag.ERR_RESERVED_PACKAGE_PROHIBITED.equals(d.errorCode().orElse(null))),
        "Must emit ERR_RESERVED_PACKAGE_PROHIBITED (E037): " + result.diagnostics()
    );
  }

  @Test
  @DisplayName("TC-RES-08: Rejection of qualified constant definition #org/stvnadore/CUSTOM_PORT")
  void testQualifiedConstantDefinitionReservedNamespaceRejection() {
    String source = """
        {
          :defs {
            #org/stvnadore/CUSTOM_PORT :Int 8080
          }
          :type :Int
          :body 8080
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    assertTrue(result.hasErrors(), "Must reject qualified constant definition targeting reserved namespace");
    assertTrue(
        result.diagnostics().stream().anyMatch(d ->
            DiagnosticBag.ERR_RESERVED_PACKAGE_PROHIBITED.equals(d.errorCode().orElse(null))),
        "Must emit ERR_RESERVED_PACKAGE_PROHIBITED (E037): " + result.diagnostics()
    );
  }

  @Test
  @DisplayName("TC-RES-09: Rejection of reserved system domain stvn_core")
  void testReservedDomainStvnCoreRejection() {
    String source = """
        {
          :meta {
            #name "custom"
            #domain "stvn_core"
            #kind #BODY
          }
          :type :Int
          :body 10
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    assertTrue(result.hasErrors(), "Must reject user document claiming #domain \"stvn_core\"");
    assertTrue(
        result.diagnostics().stream().anyMatch(d ->
            DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED.equals(d.errorCode().orElse(null))),
        "Must emit ERR_RESERVED_DOMAIN_PROHIBITED (E038): " + result.diagnostics()
    );
  }

  @Test
  @DisplayName("TC-RES-10: Rejection of reserved system domain stvn_ir")
  void testReservedDomainStvnIrRejection() {
    String source = """
        {
          :meta {
            #name "custom"
            #domain "stvn_ir"
            #kind #BODY
          }
          :type :Int
          :body 20
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    assertTrue(result.hasErrors(), "Must reject user document claiming #domain \"stvn_ir\"");
    assertTrue(
        result.diagnostics().stream().anyMatch(d ->
            DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED.equals(d.errorCode().orElse(null))),
        "Must emit ERR_RESERVED_DOMAIN_PROHIBITED (E038): " + result.diagnostics()
    );
  }

  @Test
  @DisplayName("TC-RES-11: Rejection of reserved system domain stvn_cas")
  void testReservedDomainStvnCasRejection() {
    String source = """
        {
          :meta {
            #name "custom"
            #domain "stvn_cas"
            #kind #BODY
          }
          :type :Int
          :body 30
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    assertTrue(result.hasErrors(), "Must reject user document claiming #domain \"stvn_cas\"");
    assertTrue(
        result.diagnostics().stream().anyMatch(d ->
            DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED.equals(d.errorCode().orElse(null))),
        "Must emit ERR_RESERVED_DOMAIN_PROHIBITED (E038): " + result.diagnostics()
    );
  }

  @Test
  @DisplayName("TC-RES-12: Acceptance of valid user domain app_core")
  void testUserDomainAcceptance() {
    String source = """
        {
          :meta {
            #name "custom"
            #domain "app_core"
            #kind #BODY
          }
          :type :Int
          :body 100
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    assertTrue(result.isSuccess(), "Must permit standard user domain app_core: " + result.diagnostics());
    assertTrue(
        result.diagnostics().stream().noneMatch(d ->
            DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED.equals(d.errorCode().orElse(null))),
        "Must not emit ERR_RESERVED_DOMAIN_PROHIBITED for user domain"
    );
  }

  @Test
  @DisplayName("TC-RES-13: Rejection of prefix-sharing user domain stvn_core_ext under reified prefix rule")
  void testPrefixSharingUserDomainRejection() {
    String source = """
        {
          :meta {
            #name "custom"
            #domain "stvn_core_ext"
            #kind #BODY
          }
          :type :Int
          :body 200
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    assertTrue(result.hasErrors(), "Must reject domain extending reserved prefix stvn_core_ext");
    assertTrue(
        result.diagnostics().stream().anyMatch(d ->
            DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED.equals(d.errorCode().orElse(null))),
        "Must emit ERR_RESERVED_DOMAIN_PROHIBITED for extended domain name"
    );
  }

  @Test
  @DisplayName("TC-RES-14: Diagnostic coordinate span pinning for E037 on package path")
  void testPackageCoordinateSpanPinning() {
    String target = ":org/stvnadore/payload";
    String source = """
        {
          :defs {
            :package :org/stvnadore/payload {
              :Payload :String
            }
          }
          :type :String
          :body "test"
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    var diagOpt = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_RESERVED_PACKAGE_PROHIBITED.equals(d.errorCode().orElse(null)))
        .findFirst();

    assertTrue(diagOpt.isPresent(), "Must emit ERR_RESERVED_PACKAGE_PROHIBITED");
    StvnDiagnostic diag = diagOpt.get();

    int expectedStart = source.indexOf(target);
    int expectedEnd = expectedStart + target.length();

    assertEquals(expectedStart, diag.startOffset(), "Start offset must pin strictly to package path token");
    assertEquals(expectedEnd, diag.endOffset(), "End offset must cover exact package path token length");
  }

  @Test
  @DisplayName("TC-RES-15: Diagnostic coordinate span pinning for E038 on domain string literal")
  void testDomainCoordinateSpanPinning() {
    String target = "\"stvn_core\"";
    String source = """
        {
          :meta {
            #name "custom"
            #domain "stvn_core"
            #kind #BODY
          }
          :type :Int
          :body 10
        }
        """;
    var result = StvnCompiler.compileToResult(source);
    var diagOpt = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED.equals(d.errorCode().orElse(null)))
        .findFirst();

    assertTrue(diagOpt.isPresent(), "Must emit ERR_RESERVED_DOMAIN_PROHIBITED");
    StvnDiagnostic diag = diagOpt.get();

    int expectedStart = source.indexOf(target);
    int expectedEnd = expectedStart + target.length();

    assertEquals(expectedStart, diag.startOffset(), "Start offset must pin strictly to domain string literal token");
    assertEquals(expectedEnd, diag.endOffset(), "End offset must cover exact string literal token length");
  }

  @Test
  @DisplayName("TC-RES-16: Decoupled synthetic prelude authority preserves standard library")
  void testSyntheticPreludeAuthorityPreservation() {
    var preludeDoc = StvnPrelude.preludeDocument();
    assertNotNull(preludeDoc, "Synthetic prelude document must exist");

    var diagnosticBag = new DiagnosticBag();
    org.stvnadore.core.lowering.StvnReachabilityAndCasVerifier.verifyAndOrder(preludeDoc, diagnosticBag);
    assertFalse(diagnosticBag.hasErrors(), "Prelude validation must produce zero errors: " + diagnosticBag.toList());
    assertTrue(
        diagnosticBag.toList().stream().noneMatch(d ->
            DiagnosticBag.ERR_RESERVED_PACKAGE_PROHIBITED.equals(d.errorCode().orElse(null))
            || DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED.equals(d.errorCode().orElse(null))),
        "Prelude must not trigger E037 or E038"
    );

    var defs = StvnPrelude.preludeCatalog().nominalTypes();
    assertFalse(defs.isEmpty(), "Prelude document definitions must not be empty");
    assertTrue(defs.containsKey(":org/stvnadore/prelude/Port"), "Prelude must define :org/stvnadore/prelude/Port");
    assertTrue(defs.containsKey(":org/stvnadore/prelude/UuidLowerCase"), "Prelude must define :org/stvnadore/prelude/UuidLowerCase");
  }

  @Test
  @DisplayName("TC-RES-17: System parser configuration authority compiles internal documents")
  void testSystemParserConfigurationAuthority() {
    String source = """
        {
          :meta {
            #name "snapshot"
            #domain "stvn_ir"
            #kind #BODY
          }
          :defs {
            :package :org/stvnadore/ir {
              :IrNodeType :String
            }
          }
          :type :org/stvnadore/ir/IrNodeType
          :body "RootNode"
        }
        """;

    // 1. User mode (DEFAULT) must reject both domain and package
    var userResult = StvnCompiler.compileToResult(source, "snapshot.stvn_ir.stvn", StvnParserConfig.DEFAULT);
    assertTrue(userResult.hasErrors(), "User compilation must reject reserved domain and package");
    assertTrue(
        userResult.diagnostics().stream().anyMatch(d ->
            DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED.equals(d.errorCode().orElse(null))),
        "Must emit ERR_RESERVED_DOMAIN_PROHIBITED in user mode"
    );
    assertTrue(
        userResult.diagnostics().stream().anyMatch(d ->
            DiagnosticBag.ERR_RESERVED_PACKAGE_PROHIBITED.equals(d.errorCode().orElse(null))),
        "Must emit ERR_RESERVED_PACKAGE_PROHIBITED in user mode"
    );

    // 2. System mode (SYSTEM) must succeed cleanly with zero diagnostics
    var systemResult = StvnCompiler.compileToResult(source, "snapshot.stvn_ir.stvn", StvnParserConfig.SYSTEM);
    assertTrue(systemResult.isSuccess(), "System compilation must succeed with zero errors: " + systemResult.diagnostics());
    assertTrue(systemResult.diagnostics().isEmpty(), "System compilation must produce zero diagnostics: " + systemResult.diagnostics());
  }
}
