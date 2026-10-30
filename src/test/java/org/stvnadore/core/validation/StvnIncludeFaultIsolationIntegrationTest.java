package org.stvnadore.core.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.stvnadore.core.StvnCompiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration verification test suite for fault-isolated include ingestion, deferred body validation,
 * and diamond include deduplication (Target E).
 */
public class StvnIncludeFaultIsolationIntegrationTest {

  @Test
  @DisplayName("TC-INC-01: Multi-include fault isolation preserves valid definitions and accumulates tuple arity underflow on ')'")
  void testMultiIncludeFaultIsolationAndBodyArityPinning(@TempDir Path tempDir) throws IOException {
    Path marketDefs = tempDir.resolve("market_definitions.trading.stvn_d");
    Files.writeString(marketDefs, """
        {
          :meta {
            #name "market_definitions"
            #kind #DEFS
          }
          :defs {
            :Symbol :String
            :PriceOffset :Int
            :TradeTimestamp { #s } :TimeEpoch
          }
        }
        """);

    Path execPolicy = tempDir.resolve("execution_policy.trading.stvn_di");
    Files.writeString(execPolicy, """
        {
          :meta {
            #name "execution_policy"
            #kind #DEFS_INCLUDE
          }
          :defs {
            :ExecutionPolicy { #exact } :Int
          }
        }
        """);

    Path orderSubmission = tempDir.resolve("order_submission.trading.stvn_i");
    String orderStvn = """
        {
          :meta {
            #name "order_submission"
            #kind #BODY_INCLUDE
          }
          :defs {
            :include [ "market_definitions.trading.stvn_d" ]
            :include [ "execution_policy.trading.stvn_di" ]
          }
          :type :Tuple( :Symbol :PriceOffset :TradeTimestamp )
          :body ( "AAPL" 150 )
        }
        """;
    Files.writeString(orderSubmission, orderStvn);

    var result = StvnCompiler.compileToResult(orderStvn, orderSubmission.toString(), org.stvnadore.core.StvnParserConfig.DEFAULT);
    assertTrue(result.hasErrors(), "Result must have errors from child include and body arity mismatch");
    var diags = result.diagnostics();

    // 1. Verify child error was forwarded
    var childErrorOpt = diags.stream()
        .filter(d -> DiagnosticBag.ERR_INVALID_METADATA_FACET.equals(d.errorCode().orElse(null)))
        .findFirst();
    assertTrue(childErrorOpt.isPresent(), "Must accumulate ERR_INVALID_METADATA_FACET from execution_policy.trading.stvn_di");
    assertTrue(childErrorOpt.get().message().contains("facet '#exact' is not permitted on :Int"));

    // 2. Verify parent body arity mismatch was evaluated and accumulated despite child error
    var arityErrorOpt = diags.stream()
        .filter(d -> "TUPLE_ARITY_MISMATCH".equals(d.errorCode().orElse(null)))
        .findFirst();
    assertTrue(arityErrorOpt.isPresent(), "Must accumulate TUPLE_ARITY_MISMATCH from order_submission.trading.stvn_i body");
    var arityDiag = arityErrorOpt.get();
    assertTrue(arityDiag.message().contains("Tuple arity mismatch: Expected 3 elements, got 2"));

    // 3. Verify exact coordinate pinning of arity underflow strictly to closing delimiter ')'
    int rparenIndex = orderStvn.lastIndexOf(')');
    assertEquals(rparenIndex, arityDiag.startOffset(), "Arity underflow must pin strictly to closing delimiter ')'");
    assertEquals(rparenIndex + 1, arityDiag.endOffset(), "Arity underflow end must match ')'");

    // 4. Anti-tautological assertions:
    // Ensure valid child values ("AAPL", 150) and opening '(' carry 0 diagnostics
    int aaplIndex = orderStvn.indexOf("\"AAPL\"");
    int num150Index = orderStvn.indexOf("150");
    int lparenIndex = orderStvn.indexOf('(', orderStvn.indexOf(":body"));
    for (var d : diags) {
      assertNotEquals(aaplIndex, d.startOffset(), "Valid child element '\"AAPL\"' must not carry diagnostics");
      assertNotEquals(num150Index, d.startOffset(), "Valid child element '150' must not carry diagnostics");
      assertNotEquals(lparenIndex, d.startOffset(), "Opening delimiter '(' must not carry diagnostics");
    }

    // Ensure valid imported types (:Symbol, :PriceOffset, :TradeTimestamp) were NOT discarded
    boolean hasUndefinedType = diags.stream()
        .anyMatch(d -> DiagnosticBag.ERR_UNDEFINED_TYPE.equals(d.errorCode().orElse(null)));
    assertFalse(hasUndefinedType, "Valid types from first include must not be dropped when second include fails");
  }

  @Test
  @DisplayName("TC-INC-02: Diamond include graph deduplicates identical module definitions without collision")
  void testDiamondIncludeDeduplication(@TempDir Path tempDir) throws IOException {
    Path rootDefs = tempDir.resolve("root_definitions.trading.stvn_d");
    Files.writeString(rootDefs, """
        {
          :meta {
            #name "root_definitions"
            #kind #DEFS
          }
          :defs {
            :BasePrice :Int
          }
        }
        """);

    Path branchA = tempDir.resolve("branch_a.trading.stvn_di");
    Files.writeString(branchA, """
        {
          :meta {
            #name "branch_a"
            #kind #DEFS_INCLUDE
          }
          :defs {
            :include [ "root_definitions.trading.stvn_d" ]
            :PriceA :BasePrice
          }
        }
        """);

    Path branchB = tempDir.resolve("branch_b.trading.stvn_di");
    Files.writeString(branchB, """
        {
          :meta {
            #name "branch_b"
            #kind #DEFS_INCLUDE
          }
          :defs {
            :include [ "root_definitions.trading.stvn_d" ]
            :PriceB :BasePrice
          }
        }
        """);

    Path consumer = tempDir.resolve("diamond_consumer.trading.stvn_i");
    String consumerStvn = """
        {
          :meta {
            #name "diamond_consumer"
            #kind #BODY_INCLUDE
          }
          :defs {
            :include [ "branch_a.trading.stvn_di" ]
            :include [ "branch_b.trading.stvn_di" ]
          }
          :type :Tuple( :PriceA :PriceB :BasePrice )
          :body ( 100 200 300 )
        }
        """;
    Files.writeString(consumer, consumerStvn);

    var result = StvnCompiler.compileToResult(consumerStvn, consumer.toString(), org.stvnadore.core.StvnParserConfig.DEFAULT);
    assertTrue(result.isSuccess(), "Diamond include must compile cleanly without namespace collision: " + result.diagnostics());
    assertEquals(0, result.diagnostics().size(), "Must contain zero diagnostics");
  }
}
