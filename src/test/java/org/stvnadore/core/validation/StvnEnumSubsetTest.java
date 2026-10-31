package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.binary.SchemaIdentityStrategy;
import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.binary.StvnBinaryEncoder;
import org.stvnadore.core.binary.StvnSchemaHasher;
import org.stvnadore.core.ir.StvnSchema.EnumSubsetSchema;
import org.stvnadore.core.ir.StvnValue;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class StvnEnumSubsetTest {

  @Test
  @DisplayName("TC-SUBSET-01: Valid nominal enum subset via #filterIncl")
  void testValidInclusiveSubset() {
    String stvn = """
        {
          :defs {
            :Status :Enum [ #Pending #Active #Suspended #Deleted ]
            :ActiveStatus { #filterIncl [ #Active #Suspended ] } :Status
          }
          :type :ActiveStatus
          :body #Active
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertTrue(result.isSuccess(), "Compilation failed: " + result.diagnostics());
    var ir = result.orElseThrow().requirePayload();
    assertInstanceOf(StvnValue.StvnEnum.class, ir);
    var e = (StvnValue.StvnEnum) ir;
    assertEquals("#Active", e.variantTag());
    assertEquals(BigInteger.ONE, e.rootOrdinal()); // Root ordinal index preserved!
    var subset = assertInstanceOf(EnumSubsetSchema.class, ir.schema());
    assertEquals(4, subset.rootEnum().rootVariants().size()); // Root variant count preserved!
    assertEquals(":ActiveStatus", subset.aliasName().orElseThrow());
    assertEquals(":Status", subset.parentEnum().aliasName().orElseThrow());
    assertEquals(":Status", subset.rootEnum().aliasName().orElseThrow());
    assertEquals(List.of("#Active", "#Suspended"), subset.activeVariants());
    assertEquals(List.of("#Pending", "#Active", "#Suspended", "#Deleted"), subset.rootEnum().rootVariants());
    assertTrue(subset.inclusiveFilter());
  }

  @Test
  @DisplayName("TC-SUBSET-02: Valid nominal enum subset via #filterExcl")
  void testValidExclusiveSubset() {
    String stvn = """
        {
          :defs {
            :Status :Enum [ #Pending #Active #Suspended #Deleted ]
            :NonDeleted { #filterExcl [ #Deleted ] } :Status
          }
          :type :NonDeleted
          :body #Pending
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertTrue(result.isSuccess(), "Compilation failed: " + result.diagnostics());
    var ir = result.orElseThrow().requirePayload();
    var subset = assertInstanceOf(EnumSubsetSchema.class, ir.schema());
    assertEquals(List.of("#Pending", "#Active", "#Suspended"), subset.activeVariants());
    assertEquals(List.of("#Pending", "#Active", "#Suspended", "#Deleted"), subset.rootEnum().rootVariants());
    assertFalse(subset.inclusiveFilter());
  }

  @Test
  @DisplayName("TC-SUBSET-03: Transitive chaining (3 levels) with monotonic narrowing")
  void testTransitiveChaining() {
    String stvn = """
        {
          :defs {
            :Status :Enum [ #Pending #Active #Suspended #Deleted ]
            :WorkingStatus { #filterExcl [ #Deleted ] } :Status
            :ImmediateStatus { #filterIncl [ #Pending #Active ] } :WorkingStatus
          }
          :type :ImmediateStatus
          :body #Active
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertTrue(result.isSuccess(), "Compilation failed: " + result.diagnostics());
    var ir = result.orElseThrow().requirePayload();
    var subset = assertInstanceOf(EnumSubsetSchema.class, ir.schema());
    assertEquals(":ImmediateStatus", subset.aliasName().orElseThrow());
    assertEquals(":WorkingStatus", subset.parentEnum().aliasName().orElseThrow());
    assertEquals(":Status", subset.rootEnum().aliasName().orElseThrow());
    assertEquals(List.of("#Pending", "#Active"), subset.activeVariants());
    assertEquals(List.of("#Pending", "#Active", "#Suspended", "#Deleted"), subset.rootEnum().rootVariants());
  }

  @Test
  @DisplayName("TC-SUBSET-04: Reject payload variant not permitted in active subset")
  void testPayloadVariantNotInSubsetRejected() {
    String stvn = """
        {
          :defs {
            :Status :Enum [ #Pending #Active #Suspended #Deleted ]
            :WorkingStatus { #filterExcl [ #Deleted ] } :Status
          }
          :type :WorkingStatus
          :body #Deleted
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertFalse(result.isSuccess());
    assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("not permitted in enum subset")));
  }

  @Test
  @DisplayName("TC-SUBSET-05: Reject mutual exclusivity violation (#filterIncl and #filterExcl)")
  void testMutualExclusivityRejected() {
    String stvn = """
        {
          :defs {
            :Status :Enum [ #Pending #Active #Suspended #Deleted ]
            :Invalid { #filterIncl [ #Active ] #filterExcl [ #Deleted ] } :Status
          }
          :type :Invalid
          :body #Active
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertFalse(result.isSuccess());
    assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("mutually exclusive")));
  }

  @Test
  @DisplayName("TC-SUBSET-06: Reject empty inclusion or exclusion list")
  void testEmptyFilterListRejected() {
    String stvn = """
        {
          :defs {
            :Status :Enum [ #Pending #Active ]
            :Invalid { #filterIncl [ ] } :Status
          }
          :type :Invalid
          :body #Active
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertFalse(result.isSuccess());
    assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("cannot be empty")));
  }

  @Test
  @DisplayName("TC-SUBSET-07: Reject complete exclusion (0 remaining variants)")
  void testCompleteExclusionRejected() {
    String stvn = """
        {
          :defs {
            :Status :Enum [ #Pending #Active ]
            :Invalid { #filterExcl [ #Pending #Active ] } :Status
          }
          :type :Invalid
          :body #Active
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertFalse(result.isSuccess());
    assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("Complete exclusion violation")));
  }

  @Test
  @DisplayName("TC-SUBSET-08: Reject monotonic narrowing violation")
  void testMonotonicNarrowingViolationRejected() {
    String stvn = """
        {
          :defs {
            :Status :Enum [ #Pending #Active #Suspended #Deleted ]
            :Working { #filterExcl [ #Deleted ] } :Status
            :Illegal { #filterIncl [ #Deleted ] } :Working
          }
          :type :Illegal
          :body #Deleted
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertFalse(result.isSuccess());
    assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("Monotonic narrowing violation")));
  }

  @Test
  @DisplayName("TC-SUBSET-09: Reject root ordering violation")
  void testRootOrderingViolationRejected() {
    String stvn = """
        {
          :defs {
            :Status :Enum [ #Pending #Active #Suspended #Deleted ]
            :IllegalOrder { #filterIncl [ #Active #Pending ] } :Status
          }
          :type :IllegalOrder
          :body #Active
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertFalse(result.isSuccess());
    var diagOpt = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_ENUM_SUBSET_ORDER_VIOLATION.equals(d.errorCode().orElse(null)))
        .findFirst();
    assertTrue(diagOpt.isPresent(), "Expected ERR_ENUM_SUBSET_ORDER_VIOLATION diagnostic");
    var diag = diagOpt.get();
    assertTrue(diag.message().contains("Root ordering violation"));
    int filterIndex = stvn.indexOf("#filterIncl");
    int pendingStart = stvn.indexOf("#Pending", filterIndex);
    int pendingEnd = pendingStart + "#Pending".length();
    assertEquals(pendingStart, diag.startOffset(), "startOffset must pin strictly to #Pending");
    assertEquals(pendingEnd, diag.endOffset(), "endOffset must pin strictly to #Pending");
  }

  @Test
  @DisplayName("TC-SUBSET-10: Reject filter facets on inline enum constructors")
  void testFilterOnInlineEnumRejected() {
    String stvn = """
        {
          :defs {
            :Illegal { #filterIncl [ #A ] } :Enum [ #A #B ]
          }
          :type :Illegal
          :body #A
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertFalse(result.isSuccess());
    assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("cannot be applied to inline enum constructors")));
  }

  @Test
  @DisplayName("TC-SUBSET-11: Reject filter facets on non-enum types")
  void testFilterOnNonEnumRejected() {
    String stvn = """
        {
          :defs {
            :Illegal { #filterIncl [ #A ] } :Int
          }
          :type :Illegal
          :body 42
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertFalse(result.isSuccess());
    assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("filter facets are not allowed on :Int")));
  }

  @Test
  @DisplayName("TC-SUBSET-12: Zero-trust binary decoding rejects ordinal outside subset boundary")
  void testBinaryDecodeRejectsInvalidOrdinal() {
    var validIr = StvnCompiler.compilePayload("""
        {
          :defs {
            :Status :Enum [ #Pending #Active #Suspended #Deleted ]
            :Working { #filterExcl [ #Deleted ] } :Status
          }
          :type :Status
          :body #Deleted
        }
        """).orElseThrow();

    var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
    ByteBuffer buf = encoder.encode(validIr); // Contains byte 0x03 for #Deleted

    var workingSchema = StvnCompiler.compilePayload("""
        {
          :defs {
            :Status :Enum [ #Pending #Active #Suspended #Deleted ]
            :Working { #filterExcl [ #Deleted ] } :Status
          }
          :type :Working
          :body #Active
        }
        """).orElseThrow().schema();

    var root = StvnBinaryDecoder.open(buf);
    assertThrows(MalformedPayloadException.class, () -> {
      StvnBinaryDecoder.unpack(root, Optional.of(workingSchema));
    });
  }

  @Test
  @DisplayName("TC-SUBSET-13: Schema Hasher distinguishes subsets and prevents CAS collisions")
  void testSchemaHasherDifferentiatesSubsets() {
    var schemaA = StvnCompiler.compilePayload("""
        {
          :defs {
            :Status :Enum [ #Pending #Active #Suspended ]
            :SubA { #filterIncl [ #Pending ] } :Status
          }
          :type :SubA :body #Pending
        }
        """).orElseThrow().schema();

    var schemaB = StvnCompiler.compilePayload("""
        {
          :defs {
            :Status :Enum [ #Pending #Active #Suspended ]
            :SubB { #filterIncl [ #Active ] } :Status
          }
          :type :SubB :body #Active
        }
        """).orElseThrow().schema();

    byte[] hashA = StvnSchemaHasher.computeSha256(schemaA);
    byte[] hashB = StvnSchemaHasher.computeSha256(schemaB);

    assertNotEquals(ByteBuffer.wrap(hashA), ByteBuffer.wrap(hashB));
  }

  @Test
  @DisplayName("TC-ENUM-04: Out-of-order variant in enum subset pins coordinates strictly to offending variant token")
  void testOutOfOrderVariantTokenCoordinatePinning() {
    String stvn = """
        {
          :defs {
            :TerminalStatus :Enum [ #REJECTED #FILLED #CANCELLED ]
            :FilterSubset { #filterIncl [ #FILLED #REJECTED ] } :TerminalStatus
          }
          :type :FilterSubset
          :body #FILLED
        }
        """;
    var result = StvnCompiler.compileToResult(stvn);
    assertFalse(result.isSuccess());
    var diagOpt = result.diagnostics().stream()
        .filter(d -> DiagnosticBag.ERR_ENUM_SUBSET_ORDER_VIOLATION.equals(d.errorCode().orElse(null)))
        .findFirst();
    assertTrue(diagOpt.isPresent(), "Expected ERR_ENUM_SUBSET_ORDER_VIOLATION diagnostic");
    var diag = diagOpt.get();
    assertTrue(diag.message().contains("Root ordering violation"));
    assertTrue(diag.message().contains("#REJECTED"));

    int filterInclIndex = stvn.indexOf("#filterIncl");
    int rejectedStart = stvn.indexOf("#REJECTED", filterInclIndex);
    int rejectedEnd = rejectedStart + "#REJECTED".length();

    assertEquals(rejectedStart, diag.startOffset(), "startOffset must pin strictly to #REJECTED");
    assertEquals(rejectedEnd, diag.endOffset(), "endOffset must pin strictly to #REJECTED");
    assertEquals(4, diag.line(), "Line must pin strictly to line 4 of offending variant token");
    int lineStart = stvn.lastIndexOf('\n', rejectedStart) + 1;
    int expectedCol = rejectedStart - lineStart;
    assertEquals(expectedCol, diag.column(), "column must pin strictly to column of #REJECTED");
  }
}
