package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.binary.SchemaIdentityStrategy;
import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.binary.StvnBinaryEncoder;
import org.stvnadore.core.binary.StvnSchemaHasher;
import org.stvnadore.core.ir.StvnValue;

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
    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(stvn);
    assertTrue(result.isSuccess(), "Compilation failed: " + result.diagnostics());
    var ir = result.orElseThrow();
    assertInstanceOf(StvnValue.StvnEnum.class, ir);
    var e = (StvnValue.StvnEnum) ir;
    assertEquals("#Active", e.keyword());
    assertEquals(1, e.sequentialIndex()); // Root ordinal index preserved!
    assertEquals(4, e.variantCount());    // Root variant count preserved!
    assertTrue(ir.schema().enumSubset().isPresent());
    var subset = ir.schema().enumSubset().get();
    assertEquals(":ActiveStatus", subset.name());
    assertEquals(":Status", subset.parentType());
    assertEquals(":Status", subset.rootEnum());
    assertEquals(List.of("#Active", "#Suspended"), subset.allowedVariants());
    assertEquals(List.of("#Pending", "#Active", "#Suspended", "#Deleted"), subset.rootVariants());
    assertTrue(subset.isInclusive());
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
    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(stvn);
    assertTrue(result.isSuccess(), "Compilation failed: " + result.diagnostics());
    var ir = result.orElseThrow();
    var subset = ir.schema().enumSubset().orElseThrow();
    assertEquals(List.of("#Pending", "#Active", "#Suspended"), subset.allowedVariants());
    assertEquals(List.of("#Pending", "#Active", "#Suspended", "#Deleted"), subset.rootVariants());
    assertFalse(subset.isInclusive());
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
    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(stvn);
    assertTrue(result.isSuccess(), "Compilation failed: " + result.diagnostics());
    var ir = result.orElseThrow();
    var subset = ir.schema().enumSubset().orElseThrow();
    assertEquals(":ImmediateStatus", subset.name());
    assertEquals(":WorkingStatus", subset.parentType());
    assertEquals(":Status", subset.rootEnum());
    assertEquals(List.of("#Pending", "#Active"), subset.allowedVariants());
    assertEquals(List.of("#Pending", "#Active", "#Suspended", "#Deleted"), subset.rootVariants());
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
    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(stvn);
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
    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(stvn);
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
    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(stvn);
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
    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(stvn);
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
    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(stvn);
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
    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(stvn);
    assertFalse(result.isSuccess());
    assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("Root ordering violation")));
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
    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(stvn);
    assertFalse(result.isSuccess());
    assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("cannot be applied to inline enum constructors")));
  }

  @Test
  @DisplayName("TC-SUBSET-11: Reject filter facets on non-enum types")
  void testFilterOnNonEnumRejected() {
    String stvn = """
        {
          :defs {
            :Illegal { #filterIncl [ #A ] } :Int32
          }
          :type :Illegal
          :body 42
        }
        """;
    StvnCompilationResult<StvnValue> result = StvnCompiler.compileToResult(stvn);
    assertFalse(result.isSuccess());
    assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("filter facets are not allowed on :Int32")));
  }

  @Test
  @DisplayName("TC-SUBSET-12: Zero-trust binary decoding rejects ordinal outside subset boundary")
  void testBinaryDecodeRejectsInvalidOrdinal() {
    var validIr = StvnCompiler.compile("""
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

    var workingSchema = StvnCompiler.compile("""
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
    var schemaA = StvnCompiler.compile("""
        {
          :defs {
            :Status :Enum [ #Pending #Active #Suspended ]
            :SubA { #filterIncl [ #Pending ] } :Status
          }
          :type :SubA :body #Pending
        }
        """).orElseThrow().schema();

    var schemaB = StvnCompiler.compile("""
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
}
