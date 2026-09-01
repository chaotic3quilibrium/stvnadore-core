package org.stvnadore.core.integration;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.stvnadore.core.binary.SchemaIdentityStrategy;
import org.stvnadore.core.binary.StvnBinaryDecoder;
import org.stvnadore.core.binary.StvnBinaryEncoder;
import org.stvnadore.core.StvnCompiler;

import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

@NullMarked
public class StvnEndToEndIntegrationTest {

  public record TestProfile(
      String name,
      String stvnPayload,
      Optional<String> optionalExpectedErrorMessage
  ) {
    public static TestProfile create(String name, String stvnPayload) {
      return new TestProfile(name, stvnPayload, Optional.empty());
    }

    public static TestProfile createError(String name, String stvnPayload, String expectedErrorMessage) {
      return new TestProfile(name, stvnPayload, Optional.of(expectedErrorMessage));
    }

    public String displayName() {
      return "%s%s".formatted(
          this.optionalExpectedErrorMessage.map(expectedErrorMessageIgnored -> "^").orElse(""),
          this.name());
    }
  }

  // =========================================================================
  // CATEGORIZED TEST RUNNERS
  // =========================================================================

  @ParameterizedTest(name = "Booleans & Enums -> {0}")
  @MethodSource("booleanAndEnumSource")
  public void testBooleanAndEnumScenarios(String displayName, TestProfile testProfile) {
    runTestProfile(testProfile);
  }

  @ParameterizedTest(name = "Numeric Types & Widths -> {0}")
  @MethodSource("numericSource")
  public void testNumericScenarios(String displayName, TestProfile testProfile) {
    runTestProfile(testProfile);
  }

  @ParameterizedTest(name = "Strings & Temporals -> {0}")
  @MethodSource("stringAndTemporalSource")
  public void testStringAndTemporalScenarios(String displayName, TestProfile testProfile) {
    runTestProfile(testProfile);
  }

  @ParameterizedTest(name = "Collections & Structures -> {0}")
  @MethodSource("collectionAndStructuralSource")
  public void testCollectionAndStructuralScenarios(String displayName, TestProfile testProfile) {
    runTestProfile(testProfile);
  }

  @ParameterizedTest(name = "Unions & Polymorphism -> {0}")
  @MethodSource("unionAndPolymorphismSource")
  public void testUnionAndPolymorphismScenarios(String displayName, TestProfile testProfile) {
    runTestProfile(testProfile);
  }

  @ParameterizedTest(name = "Schema Definitions & Constraints -> {0}")
  @MethodSource("schemaAndConstraintSource")
  public void testSchemaAndConstraintScenarios(String displayName, TestProfile testProfile) {
    runTestProfile(testProfile);
  }

  // =========================================================================
  // CENTRALIZED EXECUTION HARNESS
  // =========================================================================

  @SuppressWarnings({"ConstantConditions"})
  private void runTestProfile(TestProfile testProfile) {
    System.out.printf("Running test: %s%n", testProfile.displayName());
    try {
      var irOpt = StvnCompiler.compile(testProfile.stvnPayload());

      // PATHWAY B: Harness boundary check to guard against syntax compilation errors in error-profile payloads
      if (irOpt.isPresent()) {
        var ir = irOpt.get();
        var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
        var encoded = encoder.encode(ir);

        var rootPointer = StvnBinaryDecoder.openStrict(encoded, new SchemaIdentityStrategy.UniversalDefault());
        var decodedIr = StvnBinaryDecoder.unpack(rootPointer, Optional.of(ir.schema()));
        org.junit.jupiter.api.Assertions.assertEquals(ir, decodedIr);
      }
    } catch (RuntimeException runtimeException) {
      testProfile.optionalExpectedErrorMessage()
          .filter(expectedErrorMessage ->
              Optional.ofNullable(runtimeException.getMessage())
                  .map(exceptionMessage ->
                      exceptionMessage.contains(expectedErrorMessage))
                  .orElse(false))
          .orElseThrow(() ->
              runtimeException);

      //can only get here if the expectedErrorMessage was found within the non-null runtimeException.getMessage()
      return;
    }

    //No exception was thrown
    testProfile.optionalExpectedErrorMessage()
        .ifPresent(expectedErrorMessage ->
            fail("Expected exception was not thrown: " + expectedErrorMessage));
  }

  // =========================================================================
  // ARGUMENT DATA STREAMS
  // =========================================================================

  private static Stream<Arguments> booleanAndEnumSource() {
    return Stream.of(
        TestProfile.create(
            "Boolean Validation - Valid Variants",
            """
                {
                  :type :Seq( :Boolean )
                  :body [ #TRUE #FALSE #T #F ]
                }
                """),

        TestProfile.create(
            "Boolean Validation - PreserveIndent Variants",
            """
                {
                  :defs {
                    :strTrue { #preserveIndent #TRUE } :String
                    :strFalse { #preserveIndent #FALSE } :String
                    :strT { #preserveIndent #T } :String
                    :strF { #preserveIndent #F } :String
                  }
                  :type :Tuple( :strTrue :strFalse :strT :strF )
                  :body ( "a" "b" "c" "d" )
                }
                """),

        TestProfile.createError(
            "Boolean Validation - Implicit Truthiness Integer 1",
            """
                {
                  :type :Seq( :Boolean )
                  :body [ 1 ]
                }
                """,
            "Type mismatch: Expected boolean, got integer"),

        TestProfile.createError(
            "Boolean Validation - Implicit Truthiness String 'true'",
            """
                {
                  :type :Seq( :Boolean )
                  :body [ "true" ]
                }
                """,
            "Type mismatch: Expected boolean, got string"),

        TestProfile.createError(
            "Boolean Validation - Implicit Truthiness bare word boolean true",
            """
                {
                  :type :Seq( :Boolean )
                  :body [ true ]
                }
                """,
            "STVN Syntax Error: extraneous input 'true'"),

        TestProfile.create(
            "Enum Validation - Keywords Match Defined List",
            """
                {
                  :type :Seq( :Enum[ #RED #GREEN #BLUE ] )
                  :body [ #BLUE #GREEN #RED ]
                }
                """),

        TestProfile.createError(
            "Enum Validation - Keyword Missing From Definitions List",
            """
                {
                  :type :Seq( :Enum[ #RED #GREEN #BLUE ] )
                  :body [ #YELLOW ]
                }
                """,
            "Invalid enum value: expected one of [#RED, #GREEN, #BLUE], got #YELLOW"),

        TestProfile.create(
            "Extending Enum - Registry Constant Injection Valid Map Usage",
            """
                {
                  :defs {
                    :Status :Enum [ #INITIALIZED #PROCESSING #COMPLETED #FAILED ]
                    :StatusRegistry :Map( :Status :Uuid )
                    #StatusUuidMap :StatusRegistry {
                      [#INITIALIZED "f47ac10b-58cc-4372-a567-0e02b2c3d479"]
                      [#PROCESSING  "550e8400-e29b-41d4-a716-446655440000"]
                      [#COMPLETED   "6ba7b810-9dad-11d1-80b4-00c04fd430c8"]
                      [#FAILED      "6ba7b811-9dad-11d1-80b4-00c04fd430c8"]
                    }
                  }
                  :type :StatusRegistry
                  :body #StatusUuidMap
                }
                """),

        TestProfile.create(
            "Enum Validation - Option Tag Collision None",
            """
                {
                  :type :Option( :Enum[ #Active #Inactive #None ] )
                  :body #Some #None
                }
                """),

        TestProfile.create(
            "Enum Validation - Option Tag Collision Short None",
            """
                {
                  :type :Option( :Enum[ #Y #M #N ] )
                  :body #Some #N
                }
                """),

        TestProfile.create(
            "Enum Validation - Implicit Sum Type Hijacking Protection",
            """
                {
                  :type :Seq( :Option( :Enum[ #None #N #Some #S #Left #L #Right #R #TRUE #T #FALSE #F] ))
                  :body [
                    #Left #L
                    #Right #R
                    #TRUE #T
                    #FALSE #F
                  ]
                }
                """),

        TestProfile.createError(
            "Enum Validation - Option Tag Collision Ambiguity Short None",
            """
                {
                  :type :Option( :Enum[ #Y #M #N ] )
                  :body #N
                }
                """,
            "Ambiguous keyword clash: Token #N is both a control keyword for :Option and a valid variant of enum"),

        TestProfile.createError(
            "Enum Validation - Option Tag Collision Ambiguity Short Some",
            """
                {
                  :type :Option( :Enum[ #Y #M #S ] )
                  :body #S
                }
                """,
            "Ambiguous keyword clash: Token #S is both a control keyword for :Option and a valid variant of enum"),

        TestProfile.createError(
            "Enum Validation - Either Tag Collision Ambiguity Short Left",
            """
                {
                  :type :Either( :Int8 :Enum[ #Y #M #L ] )
                  :body #L
                }
                """,
            "Ambiguous keyword clash: Token #L is both a control keyword for :Either and a valid variant of enum"),

        TestProfile.createError(
            "Enum Validation - Either Tag Collision Ambiguity Short Right",
            """
                {
                  :type :Either( :Int8 :Enum[ #Y #M #R ] )
                  :body #R
                }
                """,
            "Ambiguous keyword clash: Token #R is both a control keyword for :Either and a valid variant of enum")
    ).map(testProfile -> Arguments.of(testProfile.displayName(), testProfile));
  }

  private static Stream<Arguments> numericSource() {
    return Stream.of(
        TestProfile.create(
            "Default Number Alias - Int32 Boundaries Success",
            """
                {
                  :type :Seq( :Int )
                  :body [ -2147483648 2147483647 ]
                }
                """),

        TestProfile.createError(
            "Default Number Alias - Int32 Underflow",
            """
                {
                  :type :Seq( :Int )
                  :body [ -2147483649 ]
                }
                """,
            "Value [-2147483649] is out of bounds for :Int (-2147483648 to 2147483647)"),

        TestProfile.createError(
            "Default Number Alias - Int32 Overflow",
            """
                {
                  :type :Seq( :Int )
                  :body [ 2147483648 ]
                }
                """,
            "Value [2147483648] is out of bounds for :Int (-2147483648 to 2147483647)"),

        TestProfile.create(
            "Default Number Alias - Uint32 Boundaries Success",
            """
                {
                  :type :Seq( :Uint )
                  :body [ 0 4294967295 ]
                }
                """),

        TestProfile.createError(
            "Default Number Alias - Uint32 Underflow",
            """
                {
                  :type :Seq( :Uint )
                  :body [ -1 ]
                }
                """,
            "Value [-1] is out of bounds for :Uint (0 to 4294967295)"),

        TestProfile.createError(
            "Default Number Alias - Uint32 Overflow",
            """
                {
                  :type :Seq( :Uint )
                  :body [ 4294967296 ]
                }
                """,
            "Value [4294967296] is out of bounds for :Uint (0 to 4294967295)"),

        TestProfile.create(
            "Integer 1-Bit Validation - Int1 Boundaries success",
            """
                {
                  :type :Seq( :Int1 )
                  :body [ -1 0 ]
                }
                """),

        TestProfile.createError(
            "Integer 1-Bit Validation - Int1 Underflow",
            """
                {
                  :type :Seq( :Int1 )
                  :body [ -2 ]
                }
                """,
            "Value [-2] is out of bounds for :Int1 (-1 to 0)"),

        TestProfile.createError(
            "Integer 1-Bit Validation - Int1 Overflow",
            """
                {
                  :type :Seq( :Int1 )
                  :body [ 1 ]
                }
                """,
            "Value [1] is out of bounds for :Int1 (-1 to 0)"),

        TestProfile.create(
            "Integer 1-Bit Validation - Uint1 Boundaries Success",
            """
                {
                  :type :Seq( :Uint1 )
                  :body [ 0 1 ]
                }
                """),

        TestProfile.createError(
            "Integer 1-Bit Validation - Uint1 Underflow",
            """
                {
                  :type :Seq( :Uint1 )
                  :body [ -1 ]
                }
                """,
            "Value [-1] is out of bounds for :Uint1 (0 to 1)"),

        TestProfile.createError(
            "Integer 1-Bit Validation - Uint1 Overflow",
            """
                {
                  :type :Seq( :Uint1 )
                  :body [ 2 ]
                }
                """,
            "Value [2] is out of bounds for :Uint1 (0 to 1)"),

        TestProfile.createError(
            "Integer 7-Bit Validation - Int7 Underflow",
            """
                {
                  :type :Seq( :Int7 )
                  :body [ -65 ]
                }
                """,
            "Value [-65] is out of bounds for :Int7 (-64 to 63)"),

        TestProfile.create(
            "Integer 8-Bit Validation - Int8 Success",
            """
                {
                  :type :Seq( :Int8 )
                  :body [ -128 127 ]
                }
                """),

        TestProfile.createError(
            "Integer 256-Bit Validation - Uint256 Underflow",
            """
                {
                  :type :Seq( :Uint256 )
                  :body [ -1 ]
                }
                """,
            "Value [-1] is out of bounds for :Uint256 (0 to 115792089237316195423570985008687907853269984665640564039457584007913129639935)"),

        TestProfile.create(
            "Float Boundaries - Float32 Success",
            """
                {
                  :type :Seq( :Float32 )
                  :body [ -3.4028235E38 3.4028235E38 ]
                }
                """),

        TestProfile.createError(
            "Float Boundaries - Float64 Overflow",
            """
                {
                  :type :Seq( :Float64 )
                  :body [ 1.7976931348623159E308 ]
                }
                """,
            "Value [1.7976931348623159E+308] is out of bounds for :Float64 (-1.7976931348623157E+308 to 1.7976931348623157E+308)"),

        TestProfile.create(
            "FloatExact - Micro Arbitrary Precision Capacity Success",
            """
                {
                  :type :Seq( :FloatExact )
                  :body [ 0.0000000000000000000000000000000000000000000000000000000000001 ]
                }
                """),

        TestProfile.createError(
            "FloatExact - Accurate Bound Overhead Trapping via BigDecimal",
            """
                {
                  :defs {
                    :precisePercentage { #minIncl 0.0 #maxIncl 100.0 } :FloatExact
                  }
                  :type :Seq( :precisePercentage )
                  :body [ 100.00000000000000000000000000000000001 ]
                }
                """,
            "Constraint violation (:precisePercentage): Value must be less than or equal to 100.0"),

        TestProfile.create(
            "Alternative Numeric Bases - Hex Octal Binary Integrations Validated",
            """
                {
                  :type :Seq( :Int16 )
                  :body [ 0x7FFF -0x8000 0b0111111111111111 0o77777 ]
                }
                """),

        TestProfile.createError(
            "Alternative Numeric Bases - Hex Out Of Range Upper Limit",
            """
                {
                  :type :Seq( :Int16 )
                  :body [ 0x8000 ]
                }
                """,
            "Value [32768] is out of bounds for :Int16 (-32768 to 32767)")
    ).map(testProfile -> Arguments.of(testProfile.displayName(), testProfile));
  }

  private static Stream<Arguments> stringAndTemporalSource() {
    //noinspection TextBlockMigration
    return Stream.of(
        TestProfile.create(
            "Uuid Validation - Mixed Case Success",
            """
                {
                  :type :Seq( :Uuid )
                  :body [ "A6287828-1b9b-4295-B66D-015de536c4e1" ]
                }
                """),

        TestProfile.createError(
            "Uuid Validation - Malformed Length Short",
            """
                {
                  :type :Seq( :Uuid )
                  :body [ "not-a-uuid" ]
                }
                """,
            "Constraint violation (:Uuid): Fixed string must be exactly 36 characters long, got 10"),

        TestProfile.create(
            "Uuid Validation - Standard String Bypasses Uuid Checks",
            """
                {
                  :type :Seq( :String )
                  :body [ "not-a-uuid" "a6287828-1b9b-4295-b66d-015de536c4e" ]
                }
                """),

        TestProfile.create(
            "Sha256 Validation - Lowercase Hex Success",
            """
                {
                  :type :Seq( :Sha256 )
                  :body [ "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" ]
                }
                """),

        TestProfile.create(
            "Sha256 Validation - Uppercase Hex Success",
            """
                {
                  :type :Seq( :Sha256 )
                  :body [ "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD" ]
                }
                """),

        TestProfile.create(
            "Sha256 Validation - Mixed Case Hex Success",
            """
                {
                  :type :Seq( :Sha256 )
                  :body [ "bA7816bF8f01CfEa414140De5dAE2223B00361A396177A9cB410Ff61F20015aD" ]
                }
                """),

        TestProfile.createError(
            "Sha256 Validation - Error Length Short (40 chars)",
            """
                {
                  :type :Seq( :Sha256 )
                  :body [ "2fd4e1c67a2d28fced849ee1bb76e7391b93eb12" ]
                }
                """,
            "Constraint violation (:Sha256): Fixed string must be exactly 64 characters long, got 40"),

        TestProfile.createError(
            "Sha256 Validation - Error Length Short (63 chars)",
            """
                {
                  :type :Seq( :Sha256 )
                  :body [ "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015a" ]
                }
                """,
            "Constraint violation (:Sha256): Fixed string must be exactly 64 characters long, got 63"),

        TestProfile.createError(
            "Sha256 Validation - Error Length Long (65 chars)",
            """
                {
                  :type :Seq( :Sha256 )
                  :body [ "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad0" ]
                }
                """,
            "Constraint violation (:Sha256): Fixed string must be exactly 64 characters long, got 65"),

        TestProfile.createError(
            "Sha256 Validation - Error Non-Hex Character",
            """
                {
                  :type :Seq( :Sha256 )
                  :body [ "ga7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" ]
                }
                """,
            "Constraint violation (:Sha256): String does not match required pattern: ^[0-9a-fA-F]{64}$"),

        TestProfile.createError(
            "Sha256 Validation - Error Non-Hex Symbol",
            """
                {
                  :type :Seq( :Sha256 )
                  :body [ "ba7816bf8f01cfea414140de5dae2223-00361a396177a9cb410ff61f20015ad" ]
                }
                """,
            "Constraint violation (:Sha256): String does not match required pattern: ^[0-9a-fA-F]{64}$"),

        TestProfile.createError(
            "Sha256 Validation - Removed Sha1 Rejection",
            """
                {
                  :type :Seq( :Sha1 )
                  :body [ "2fd4e1c67a2d28fced849ee1bb76e7391b93eb12" ]
                }
                """,
            "Undefined type: :Sha1"),

        TestProfile.create(
            "Temporal Validation - DateTimeOffset Success",
            """
                {
                  :type :Seq( :DateTimeOffset )
                  :body [ "2026-03-06T15:53:08Z" "2026-03-06T15:53:08-06:00" ]
                }
                """),

        TestProfile.createError(
            "Temporal Validation - DateTimeOffset Invalid Format",
            """
                {
                  :type :Seq( :DateTimeOffset )
                  :body [ "2026/03/06 15:53:08" ]
                }
                """,
            "Invalid OffsetDateTime format (e.g., 2026-03-06T15:53:08-06:00)"),

        TestProfile.createError(
            "Temporal Validation - DateTimeOffset Rejects Zone",
            """
                {
                  :type :Seq( :DateTimeOffset )
                  :body [ "2026-03-15T08:00:00-05:00[America/Chicago]" ]
                }
                """,
            "Time zone brackets [...] are prohibited in :DateTimeOffset"),

        TestProfile.create(
            "Temporal Validation - DateTimeZoned Success",
            """
                {
                  :type :Seq( :DateTimeZoned )
                  :body [ "2026-03-15T08:00:00[America/Chicago]" "2026-08-18T18:30:00[Europe/London]" ]
                }
                """),

        TestProfile.createError(
            "Temporal Validation - DateTimeZoned Missing Region ID",
            """
                {
                  :type :Seq( :DateTimeZoned )
                  :body [ "2026-03-06T15:53:08-06:00" ]
                }
                """,
            "Invalid ZonedDateTime format. Must include a Region/City zone ID (e.g., ...[Europe/Paris])"),

        TestProfile.createError(
            "Temporal Validation - DateTimeZoned Rejects Offset",
            """
                {
                  :type :Seq( :DateTimeZoned )
                  :body [ "2026-03-15T08:00:00-05:00[America/Chicago]" ]
                }
                """,
            "Explicit offsets (±HH:mm or Z) are prohibited in :DateTimeZoned"),

        TestProfile.createError(
            "Temporal Validation - DateTimeZoned DST Spring Forward Gap",
            """
                {
                  :type :Seq( :DateTimeZoned )
                  :body [ "2026-03-08T02:30:00[America/Chicago]" ]
                }
                """,
            "falls into a DST spring-forward gap in zone 'America/Chicago'"),

        TestProfile.create(
            "Temporal Validation - DateTimeAudited Success",
            """
                {
                  :type :Seq( :DateTimeAudited )
                  :body [ "2026-03-15T08:00:00-05:00[America/Chicago]" "2026-01-15T08:00:00-06:00[America/Chicago]" ]
                }
                """),

        TestProfile.createError(
            "Temporal Validation - DateTimeAudited Missing Zone",
            """
                {
                  :type :Seq( :DateTimeAudited )
                  :body [ "2026-03-15T08:00:00-05:00" ]
                }
                """,
            "Mandates both an explicit UTC offset and an IANA zone ID"),

        TestProfile.createError(
            "Temporal Validation - DateTimeAudited Contradictory Offset",
            """
                {
                  :type :Seq( :DateTimeAudited )
                  :body [ "2026-03-15T08:00:00-07:00[America/Chicago]" ]
                }
                """,
            "Contradictory offset in :DateTimeAudited literal. Declared offset '-07:00' does not match valid offset(s) [-05:00]"),

        TestProfile.create(
            "Temporal Validation - TimeEpochNs BigInteger Storage Success",
            """
                {
                  :type :Seq( :TimeEpochNs )
                  :body [ 9223372036854775808 ]
                }
                """),

        TestProfile.create(
            "Bounded String - Fixed Length Match",
            """
                {
                  :type :Seq( :StringFixed5 )
                  :body [ "hello" ]
                }
                """),

        TestProfile.createError(
            "String Validation - Rogue Java Concatenation Artifacts",
            """
                {
                  :type :Seq( :StringFixed5 )
                  :body ["  //<-- trailing double-quote
                    "hello"
                  ]" +      //<-- trailing double-quote, space, and plus-sign
                }
                """,
            "STVN Syntax Error: token recognition error"),

        TestProfile.createError(
            "Bounded String - Fixed Length Mismatch",
            """
                {
                  :type :Seq( :StringFixed5 )
                  :body [ "hello!" ]
                }
                """,
            "Constraint violation (:StringFixed5): Fixed string must be exactly 5 characters long, got 6"),

        TestProfile.createError(
            "Bounded String - NonEmpty Rejects Empty String",
            """
                {
                  :type :Seq( :StringNonEmpty5 )
                  :body [ "" ]
                }
                """,
            "Constraint violation (:StringNonEmpty5): String cannot be empty"),

        // ====================================================================
        // STRING CONSTRAINT RESOLUTION TEST SUITE (:StringN, :StringNonEmptyN, :StringFixedN)
        // ====================================================================

        // --- 1. Bounded String Tests (:String64) ---
        TestProfile.create(
            "Bounded String - Empty String Allowed (:String64)",
            """
                {
                  :type :Seq( :String64 )
                  :body [ "" ]
                }
                """),

        TestProfile.create(
            "Bounded String - Sub-Limit Typical String (:String64 with len 37)",
            """
                {
                  :type :Seq( :String64 )
                  :body [ "This string is exactly 37 chars long." ]
                }
                """),

        TestProfile.create(
            "Bounded String - Boundary Limit String (:String64 with len 64)",
            """
                {
                  :type :Seq( :String64 )
                  :body [ "1234567890123456789012345678901234567890123456789012345678901234" ]
                }
                """),

        TestProfile.createError(
            "Bounded String - Overflow String Rejection (:String64 with len 65)",
            """
                {
                  :type :Seq( :String64 )
                  :body [ "12345678901234567890123456789012345678901234567890123456789012345" ]
                }
                """,
            "Constraint violation (:String64): String length exceeds maximum length of 64 characters, got 65"),

        // --- 2. Bounded Non-Empty String Tests (:StringNonEmpty64) ---
        TestProfile.createError(
            "Bounded Non-Empty - Empty String Rejection (:StringNonEmpty64)",
            """
                {
                  :type :Seq( :StringNonEmpty64 )
                  :body [ "" ]
                }
                """,
            "Constraint violation (:StringNonEmpty64): String cannot be empty"),

        TestProfile.create(
            "Bounded Non-Empty - Sub-Limit String (:StringNonEmpty64 with len 37)",
            """
                {
                  :type :Seq( :StringNonEmpty64 )
                  :body [ "This string is exactly 37 chars long." ]
                }
                """),

        TestProfile.create(
            "Bounded Non-Empty - Boundary Limit String (:StringNonEmpty64 with len 64)",
            """
                {
                  :type :Seq( :StringNonEmpty64 )
                  :body [ "1234567890123456789012345678901234567890123456789012345678901234" ]
                }
                """),

        TestProfile.createError(
            "Bounded Non-Empty - Overflow String Rejection (:StringNonEmpty64 with len 65)",
            """
                {
                  :type :Seq( :StringNonEmpty64 )
                  :body [ "12345678901234567890123456789012345678901234567890123456789012345" ]
                }
                """,
            "Constraint violation (:StringNonEmpty64): String length exceeds maximum length of 64 characters, got 65"),

        // --- 3. Fixed-Length String Tests (:StringFixed64) ---
        TestProfile.create(
            "Fixed String - Exact Length Match (:StringFixed64)",
            """
                {
                  :type :Seq( :StringFixed64 )
                  :body [ "1234567890123456789012345678901234567890123456789012345678901234" ]
                }
                """),

        TestProfile.createError(
            "Fixed String - Underflow Rejection (:StringFixed64 with len 37)",
            """
                {
                  :type :Seq( :StringFixed64 )
                  :body [ "This string is exactly 37 chars long." ]
                }
                """,
            "Constraint violation (:StringFixed64): Fixed string must be exactly 64 characters long, got 37"),

        TestProfile.createError(
            "Fixed String - Overflow Rejection (:StringFixed64 with len 65)",
            """
                {
                  :type :Seq( :StringFixed64 )
                  :body [ "12345678901234567890123456789012345678901234567890123456789012345" ]
                }
                """,
            "Constraint violation (:StringFixed64): Fixed string must be exactly 64 characters long, got 65"),

        // --- 4. Nominal Type Alias Bounded Tests ---
        TestProfile.create(
            "Nominal Alias - Bounded Text Sub-Limit (:BoundedText :String64)",
            """
                {
                  :defs { :BoundedText :String64 }
                  :type :BoundedText
                  :body "Short text"
                }
                """),

        TestProfile.createError(
            "Nominal Alias - Bounded Text Overflow (:BoundedText :String64)",
            """
                {
                  :defs { :BoundedText :String64 }
                  :type :BoundedText
                  :body "12345678901234567890123456789012345678901234567890123456789012345"
                }
                """,
            "Constraint violation (:BoundedText): String length exceeds maximum length of 64 characters, got 65"),

        TestProfile.create(
            "String Literal Variations - Block for end on same line (#preserveIndent implicitly #FALSE)",
            """
                {
                  :type :Seq( :StringFixed6 )
                  :body [
                    "hello!"
                    ""\"
                    hi1234""\"
                  ]
                }"""),

        TestProfile.create(
            "String Literal Variations - Block for end on next line (#preserveIndent implicitly #FALSE)",
            """
                {
                  :type :Seq( :StringFixed7 )
                  :body [
                    "hello!!"
                    ""\"
                    hi1234
                    ""\"
                  ]
                }"""),

        TestProfile.create(
            "String Literal Variations - Block for end on same line (#preserveIndent explicitly #TRUE)",
            """
                {
                  :defs {
                    :strTrue { #preserveIndent #TRUE } :StringFixed10
                  }
                  :type :Seq( :strTrue )
                  :body [
                    "hello!!!!!"
                    ""\"
                    hi1234""\"
                  ]
                }"""),

        TestProfile.create(
            "String Literal Variations - Block for end on next line (#preserveIndent explicitly #TRUE)",
            """
                {
                  :defs {
                    :strTrue { #preserveIndent #TRUE } :StringFixed15
                  }
                  :type :Seq( :strTrue )
                  :body [
                    "hello!!!!!hello"
                    ""\"
                    hi1234
                    ""\"
                  ]
                }"""),

        TestProfile.create(
            "String Literal Variations - Fenced for end on same line (#preserveIndent implicitly #FALSE)",
            """
                {
                  :type :Seq( :StringFixed6 )
                  :body [
                    "hello!"
                    ""\"->[FENCE]
                    hi1234[FENCE]""\"
                  ]
                }"""),

        TestProfile.create(
            "String Literal Variations - Fenced for end on next line (#preserveIndent implicitly #FALSE)",
            """
                {
                  :type :Seq( :StringFixed7 )
                  :body [
                    "hello!!"
                    ""\"->[FENCE]
                    hi1234
                    [FENCE]""\"
                  ]
                }"""),

        TestProfile.create(
            "String Literal Variations - Fenced for end on same line (#preserveIndent explicitly #TRUE)",
            """
                {
                  :defs {
                    :strTrue { #preserveIndent #TRUE } :StringFixed10
                  }
                  :type :Seq( :strTrue )
                  :body [
                    "hello!!!!!"
                    ""\"->[FENCE]
                    hi1234[FENCE]""\"
                  ]
                }"""),

        TestProfile.create(
            "String Literal Variations - Fenced for end on next line (#preserveIndent explicitly #TRUE)",
            """
                {
                  :defs {
                    :strTrue { #preserveIndent #TRUE } :StringFixed15
                  }
                  :type :Seq( :strTrue )
                  :body [
                    "hello!!!!!hello"
                    ""\"->[FENCE]
                    hi1234
                    [FENCE]""\"
                  ]
                }"""),

        TestProfile.create(
            "String Literal Variations - Fenced for STVN containing STVN",
            """
                {
                  :type :Seq( :String )
                  :body [
                    "hello!"
                    ""\"->[STVN_WITHIN_STVN]
                    {
                      :type :Seq( :StringFixed7 )
                      :body [
                        "hello!!"
                        ""\"->[FENCE]
                        hi1234
                        [FENCE]""\"
                      ]
                    }
                    [STVN_WITHIN_STVN]""\"
                  ]
                }"""),

        TestProfile.createError(
            "String Literal Variations - Fenced Closing Tag Mismatch",
            """
                {
                  :type :Seq( :String )
                  :body [
                    "hello!"
                    ""\"->[FENCE]
                    hi1234[FENCE_OOPS]""\"
                  ]
                }""",
            "STVN Syntax Error: extraneous input '<EOF>'")
    ).map(testProfile -> Arguments.of(testProfile.displayName(), testProfile));
  }

  private static Stream<Arguments> collectionAndStructuralSource() {
    return Stream.of(
        TestProfile.create(
            "Scenario A - Testing enums with Uuids in a :MapInvNonEmpty",
            """
                {
                  :defs {
                    :StatusType :Enum [ #PENDING #ACTIVE #TERMINATED ]
                    :StatusId :Uuid
                    :StatusRegistry :MapInvNonEmpty( :StatusType :StatusId )
                  }
                  :type :StatusRegistry
                  :body {
                    [ #PENDING "a47ac10b-58cc-4372-a567-0e02b2c3d479" ]
                    [ #ACTIVE  "b50e8400-e29b-41d4-a716-446655440000" ]
                    [ #TERMINATED "c50e8400-e29b-41d4-a716-446655440000" ]
                  }
                }
                """),

        TestProfile.create(
            "Tuple - Correct Positional Elements",
            """
                {
                  :type :Seq( :Tuple( :Int :StringFixed5 ) )
                  :body [ ( 1 "hello" ) ]
                }
                """),

        TestProfile.createError(
            "Tuple - Arity Mismatch Excess Elements",
            """
                {
                  :type :Seq( :Tuple( :Int :StringFixed5 ) )
                  :body [ ( 1 "hello" #TRUE ) ]
                }
                """,
            "Tuple arity mismatch: Expected 2 elements, got 3"),

        TestProfile.createError(
            "Map Positional Resolution - Flipped Inner Structural Shapes",
            """
                {
                  :type :Map( :StringFixed3 :Int8 )
                  :body {
                    [ 3 "six" ]
                  }
                }
                """,
            "Type mismatch: Expected string, got integer"),

        TestProfile.createError(
            "Non-Empty Collections - Void Content Failure Sequence",
            """
                {
                  :type :Seq( :SeqNonEmpty( :Int8 ) )
                  :body [ [ ] ]
                }
                """,
            "Sequence is marked as non-empty but contains no elements"),

        TestProfile.createError(
            "Deep Composition - Nested Multi-Layer Pathing Tracking Error",
            """
                {
                  :type :Seq( :Map( :StringFixed3 :Either( :Int8 :Tuple( :Boolean :Float32 ) ) ) )
                  :body [
                    { [ "err" ( #FALSE "bad_float" ) ] }
                  ]
                }
                """,
            "Type mismatch: Expected float, got string"),

        TestProfile.create(
            "Empty Collections Allowed - Default States Allowed Gracefully",
            """
                {
                  :type :Seq( :Tuple( :Seq( :Int ) :Set( :String ) :Map( :Int :Int ) :MapInv( :Int :Int ) ) )
                  :body [ ( [] [] {} {} ) ]
                }
                """),

        TestProfile.create(
            "JsonLikeAnyValue - High Complexity Composite Scheme Validation Pass",
            """
                {
                  :defs {
                    :JsonNull :Enum [ #Null ]
                    :JsonValue :Union(
                      :JsonNull
                      :Boolean
                      :Int32
                      :Float64
                      :String
                      :Seq( :JsonValue )
                      :Map( :String :JsonValue )
                    )
                    :JsonObject :Map( :String :JsonValue )
                  }
                  :type :JsonObject
                  :body {
                    ["project" "STVN IDEA Plugin"]
                    ["version" 0.1]
                    ["open_source" #TRUE]
                    ["features" [ "highlighting" "folding" "completion" 100 ] ]
                    ["metadata" {
                        ["author" "Dallas Scala Enthusiasts"]
                        ["nested_mixed_array" [ 1 2.5 "three" { ["deep" #FALSE] } ] ]
                    }]
                  }
                }
                """),

        TestProfile.createError(
            "Uniqueness - Set Array Double Entry Value Rejection",
            """
                {
                  :type :SetNonEmpty( :Uint )
                  :body [ 1 2 3 1 ]
                }
                """,
            "Duplicate set element detected"),

        TestProfile.createError(
            "Uniqueness - Inverse Map Values Clash",
            """
                {
                  :type :MapInvNonEmpty( :StringFixed3 :Int8 )
                  :body {
                    ["ABC" 1]
                    ["EFG" 2]
                    ["HIJ" 1]
                  }
                }
                """,
            "Duplicate inverted map value detected")
    ).map(testProfile -> Arguments.of(testProfile.displayName(), testProfile));
  }

  private static Stream<Arguments> unionAndPolymorphismSource() {
    var identicalNesting = TestProfile.create(
        "Nested Implicit Evaluation - Double Option Nesting",
        """
            {
              :type :Option( :Option( :Int32 ) )
              :body 42
            }
            """
    );

    var secondaryBranch = TestProfile.create(
        "Nested Implicit Evaluation - Secondary Branch Route",
        """
            {
              :type :Either( :Int32 :Either( :Boolean :String ) )
              :body "hello"
            }
            """
    );

    var overlapRejection = TestProfile.createError(
        "Overlap Rejection - Ambiguous Nested Sum Layout",
        """
            {
              :type :Either( :Option( :Int32 ) :Option( :Int64 ) )
              :body 42
            }
            """,
        "Two member branches within a single sum type share identical nominal type identities: :Option"
    );

    var nominalEitherSubstringTest = TestProfile.create(
        "Nominal Substring Overlap - Either Validator Alias in Union",
        """
        {
          :defs {
            :EitherValidator :Int32
          }
          :type :Union( :EitherValidator :String )
          :body "hello"
        }
        """
    );

    var nominalOptionSubstringTest = TestProfile.create(
        "Nominal Substring Overlap - Option Store Alias",
        """
        {
          :defs {
            :OptionStore :String
          }
          :type :Seq( :OptionStore )
          :body [ "hello" ]
        }
        """
    );

    return Stream.of(
        TestProfile.create(
            "Explicit Tagged Unions - Either Success Scenarios",
            """
                {
                  :type :Seq( :Either( :Int8 :StringFixed5 ) )
                  :body [ #Left 1 #Right "hello" ]
                }
                """),

        TestProfile.createError(
            "Explicit Tagged Unions - Inner Tag Routing Bounds Validation",
            """
                {
                  :type :Seq( :Either( :Int8 :StringFixed5 ) )
                  :body [ #Right "hi" ]
                }
                """,
            "Constraint violation (:StringFixed5): Fixed string must be exactly 5 characters long, got 2"),

        TestProfile.createError(
            "Rule E Rejection - Left Variant Non-Inferable in Sequence",
            """
                {
                  :type :Seq( :Either( :Int8 :StringFixed5 ) )
                  :body [ 1 "hello" ]
                }
                """,
            "Rule E Violation: Untagged value matching Left branch of :Either is non-inferable; explicit #Left tag is required"),

        TestProfile.createError(
            "Implicit Polymorphism - Ambigous Union Shape Clash",
            """
                {
                  :type :Seq( :Either( :String :String ) )
                  :body [ "world" ]
                }
                """,
            "Two member branches within a single sum type share identical nominal type identities: :String"),

        TestProfile.createError(
            "Rogue Wrappers - Option Tags Enforced on Primitives",
            """
                {
                  :type :Seq( :Int8 )
                  :body [ #Some 1 ]
                }
                """,
            "Unexpected Option tag (#Some/#None), schema does not define an :Option"),

        TestProfile.createError(
            "Short Aliases And Downcasting - Nested Target Error Extraction Only",
            """
                {
                  :type :Seq( :Tuple( :Option( :Int8 ) :Either( :Int8 :String ) ) )
                  :body [ ( #S "bad" #L 1 ) ]
                }
                """,
            "Type mismatch: Expected integer, got string"),

        identicalNesting,
        secondaryBranch,
        overlapRejection,
        nominalEitherSubstringTest,
        nominalOptionSubstringTest
    ).map(testProfile -> Arguments.of(testProfile.displayName(), testProfile));
  }

  private static Stream<Arguments> schemaAndConstraintSource() {
    return Stream.of(
        TestProfile.create(
            "Symbol Resolution - Alias Merge Valid Usage",
            """
                {
                  :defs {
                    :myInt :Int8
                  }
                  :type :Seq( :myInt )
                  :body [ 127 ]
                }
                """),

        TestProfile.createError(
            "Symbol Resolution - Circular Dependency Graph Lock Check",
            """
                {
                  :defs {
                    :a :b
                    :b :a
                  }
                  :type :Seq( :a )
                  :body [ 1 ]
                }
                """,
            "Circular type definition detected: :a -> :b -> :a"),

        TestProfile.createError(
            "Metadata Constraints - Set Elements Must Be Equatable",
            """
                {
                  :defs {
                    :badType { #equatable #FALSE } :String
                  }
                  :type :Seq( :Set( :badType ) )
                  :body []
                }
                """,
            "Set elements require types to be #equatable #TRUE"),

        TestProfile.createError(
            "Metadata Constraints - Equatable missing value",
            """
                {
                  :defs {
                    :badMetaData { #equatable } :String
                  }
                  :type :Seq( :Set( :badMetaData ) )
                  :body []
                }
                """,
            "STVN Syntax Error: mismatched input '}'"),

        TestProfile.createError(
            "Numeric Constraints - Min Bounds Checked",
            """
                {
                  :defs {
                    :age { #minIncl 0 #maxIncl 120 } :Int8
                  }
                  :type :Seq( :age )
                  :body [ -1 ]
                }
                """,
            "Constraint violation (:age): Value must be greater than or equal to 0"),

        TestProfile.createError(
            "Numeric Constraints - Min Bounds Collision",
            """
                {
                  :defs {
                    :age { #minIncl 0 #minExcl -1 } :Int8
                  }
                  :type :Seq( :age )
                  :body [ -1 ]
                }
                """,
            "Constraint violation (:age): #minIncl and #minExcl are mutually exclusive"),

        TestProfile.createError(
            "Numeric Constraints - Max Bounds Collision",
            """
                {
                  :defs {
                    :age { #maxIncl 100 #maxExcl 99 } :Int8
                  }
                  :type :Seq( :age )
                  :body [ 100 ]
                }
                """,
            "Constraint violation (:age): #maxIncl and #maxExcl are mutually exclusive"),

        TestProfile.createError(
            "Regex Constraints - Active Evaluator Discrepancy",
            """
                {
                  :defs {
                    :hexColor { #regex "^#([A-Fa-f0-9]{6})$" } :String
                  }
                  :type :Seq( :hexColor )
                  :body [ "FFFFFF" ]
                }
                """,
            "Constraint violation (:hexColor): String does not match required pattern: ^#([A-Fa-f0-9]{6})$"),

        TestProfile.createError(
            "Constraint Inheritance - Child Exclusive Overrides Parent Inclusive",
            """
                {
                  :defs {
                    :naturalNumber { #minIncl 0 } :Int32
                    :strictlyPositive { #minExcl 0 } :naturalNumber
                  }
                  :type :Seq( :strictlyPositive )
                  :body [ 0 ]
                }
                """,
            "Constraint violation (:strictlyPositive): Value must be strictly greater than 0"),

        TestProfile.create(
            "Typed Constants - Inline Substitution References",
            """
                {
                  :defs {
                    #MAX_RETRY :Int8 3
                    #BASE_URL  :String "https://stvn.iore"
                  }
                  :type :Seq( :Tuple( :Int8 :String ) )
                  :body [ ( #MAX_RETRY #BASE_URL ) ]
                }
                """),

        TestProfile.createError(
            "Inherited Constraint Overrides - Dropping Parent Maximum Value Verification",
            """
                {
                  :defs {
                    :ParentInt { #minIncl 10 #maxIncl 20 } :Int32
                    :ChildInt { #minExcl 0 #maxExcl 5 } :ParentInt
                  }
                  :type :Seq( :Tuple( :ParentInt :ChildInt ) )
                  :body [ ( 15 5 ) ]
                }
                """,
            "Constraint violation (:ChildInt): Value must be strictly less than 5"),

        TestProfile.createError(
            "Numeric Constraints - Float literal on Integer type fails",
            """
                {
                  :defs {
                    :age { #minIncl 0.0 } :Int8
                  }
                  :type :Seq( :age )
                  :body [ 1 ]
                }
                """,
            "Constraint violation (:age): minIncl for :Int8 requires an integer literal"
        ),

        TestProfile.createError(
            "Numeric Constraints - Integer literal on Float type fails",
            """
                {
                  :defs {
                    :weight { #minIncl 2 } :Float64
                  }
                  :type :Seq( :weight )
                  :body [ 55.5 ]
                }
                """,
            "Constraint violation (:weight): minIncl for :Float64 requires a float literal"
        ),

        TestProfile.createError(
            "String Constraints - Numeric bounds forbidden on String",
            """
                {
                  :defs {
                    :username { #minIncl 5 } :String
                  }
                  :type :Seq( :username )
                  :body [ "admin" ]
                }
                """,
            "Constraint violation (:username): minIncl is not allowed on :String"
        ),

        TestProfile.createError(
            "Numeric Constraints - Regex forbidden on Integer",
            """
                {
                  :defs {
                    :postalCode { #regex "^\\d{5}$" } :Int32
                  }
                  :type :Seq( :postalCode )
                  :body [ 90210 ]
                }
                """,
            "Constraint violation (:postalCode): regex is not allowed on :Int32"
        ),

        TestProfile.createError(
            "Numeric Constraints - PreserveIndent forbidden on Float",
            """
                {
                  :defs {
                    :matrix { #preserveIndent #TRUE } :Float32
                  }
                  :type :Seq( :matrix )
                  :body [ 1.1 ]
                }
                """,
            "Constraint violation (:matrix): preserveIndent is not allowed on :Float32"
        ),

        TestProfile.createError(
            "Metadata Value Constraints - Invalid bare word boolean 'true' for preserveIndent",
            """
                {
                  :defs {
                    :matrix { #preserveIndent true } :String
                  }
                  :type :Seq( :matrix )
                  :body [ "1.1" ]
                }
                """,
            "STVN Syntax Error: mismatched input 'true'"
        ),

        TestProfile.createError(
            "Metadata Value Constraints - Invalid bare word boolean 'false' for preserveIndent",
            """
                {
                  :defs {
                    :matrix { #preserveIndent false } :String
                  }
                  :type :Seq( :matrix )
                  :body [ "1.1" ]
                }
                """,
            "STVN Syntax Error: mismatched input 'false'"
        ),

        TestProfile.createError(
            "Metadata Value Constraints - Invalid boolean for minIncl",
            """
                {
                  :defs {
                    :age { #minIncl #TRUE } :Int8
                  }
                  :type :Seq( :age )
                  :body [ 1 ]
                }
                """,
            "Constraint violation (:age): #minIncl requires an integer literal, found boolean"
        ),

        TestProfile.createError(
            "Metadata Value Constraints - Random symbol in regex",
            """
                {
                  :defs {
                    :username { #regex pattern_without_quotes } :String
                  }
                  :type :Seq( :username )
                  :body [ "admin" ]
                }
                """,
            "STVN Syntax Error: mismatched input 'pattern_without_quotes'"
        ),

        TestProfile.createError(
            "Eager Schema Validation - Unreferenced Broken Regex Fails Eagerly",
            """
                {
                  :defs {
                    :BrokenRegex { #regex "[" } :String
                  }
                  :type :String
                  :body "valid string"
                }
                """,
            "Constraint violation (:BrokenRegex): Invalid regex pattern: ["
        ),

        TestProfile.createError(
            "Eager Schema Validation - Unreferenced Inverted Numeric Range Fails Eagerly",
            """
                {
                  :defs {
                    :InvalidRange { #minIncl 100 #maxIncl 10 } :Int32
                  }
                  :type :String
                  :body "valid string"
                }
                """,
            "Constraint violation (:InvalidRange): effective range is invalid"
        ),

        TestProfile.createError(
            "Eager Schema Validation - Unreferenced Incompatible Metadata Type Fails Eagerly",
            """
                {
                  :defs {
                    :BadType { #minIncl "abc" } :Int32
                  }
                  :type :String
                  :body "valid string"
                }
                """,
            "Constraint violation (:BadType): #minIncl requires an integer literal, found string"
        )
    ).map(testProfile -> Arguments.of(testProfile.displayName(), testProfile));
  }

  // =========================================================================
  // ADDITIONAL BLUEPRINT INTEGRATION TESTS
  // =========================================================================

  private static class CustomTypeKeywordContext extends org.stvnadore.core.parser.StvnParser.TypeKeywordContext {
      private final String text;
      public CustomTypeKeywordContext(String text) {
          super(null, 0);
          this.text = text;
      }
      @Override
      public String getText() {
          return text;
      }
  }

  private static class CustomTypeDefinitionContext extends org.stvnadore.core.parser.StvnParser.TypeDefinitionContext {
      private final org.stvnadore.core.parser.StvnParser.TypeKeywordContext kw;
      private final org.stvnadore.core.parser.StvnParser.SchemaTypeContext schemaType;
      public CustomTypeDefinitionContext(org.stvnadore.core.parser.StvnParser.TypeKeywordContext kw, org.stvnadore.core.parser.StvnParser.SchemaTypeContext schemaType) {
          super(null, 0);
          this.kw = kw;
          this.schemaType = schemaType;
      }
      @Override
      public org.stvnadore.core.parser.StvnParser.TypeKeywordContext typeKeyword() {
          return kw;
      }
      @Override
      public org.stvnadore.core.parser.StvnParser.SchemaTypeContext schemaType() {
          return schemaType;
      }
  }

  private static class CustomDefsEntryContext extends org.stvnadore.core.parser.StvnParser.DefsEntryContext {
      private final java.util.List<org.stvnadore.core.parser.StvnParser.TypeDefinitionContext> defsList;
      public CustomDefsEntryContext(java.util.List<org.stvnadore.core.parser.StvnParser.TypeDefinitionContext> defsList) {
          super(null, 0);
          this.defsList = defsList;
      }
      @Override
      public java.util.List<org.stvnadore.core.parser.StvnParser.TypeDefinitionContext> typeDefinition() {
          return defsList;
      }
  }

  private static class CustomDocumentBodyContext extends org.stvnadore.core.parser.StvnParser.DocumentBodyContext {
      private final org.stvnadore.core.parser.StvnParser.DefsEntryContext defs;
      public CustomDocumentBodyContext(org.stvnadore.core.parser.StvnParser.DefsEntryContext defs) {
          super(null, 0);
          this.defs = defs;
      }
      @Override
      public org.stvnadore.core.parser.StvnParser.DefsEntryContext defsEntry() {
          return defs;
      }
  }

  private static class CustomDocumentContext extends org.stvnadore.core.parser.StvnParser.StvnDocumentContext {
      private final org.stvnadore.core.parser.StvnParser.DocumentBodyContext body;
      public CustomDocumentContext(org.stvnadore.core.parser.StvnParser.DocumentBodyContext body) {
          super(null, 0);
          this.body = body;
      }
      @Override
      public org.stvnadore.core.parser.StvnParser.DocumentBodyContext documentBody() {
          return body;
      }
  }

  private static class CustomSchemaTypeContext extends org.stvnadore.core.parser.StvnParser.SchemaTypeContext {
      private final org.stvnadore.core.parser.StvnParser.SchemaConstructorContext ctor;
      private final org.stvnadore.core.parser.StvnParser.TypeKeywordContext kw;

      public CustomSchemaTypeContext(org.stvnadore.core.parser.StvnParser.SchemaConstructorContext ctor, org.stvnadore.core.parser.StvnParser.TypeKeywordContext kw) {
          super(null, 0);
          this.ctor = ctor;
          this.kw = kw;
      }

      @Override
      public org.stvnadore.core.parser.StvnParser.SchemaConstructorContext schemaConstructor() {
          return ctor;
      }

      @Override
      public org.stvnadore.core.parser.StvnParser.TypeKeywordContext typeKeyword() {
          return kw;
      }
  }

  private static class CustomSchemaConstructorContext extends org.stvnadore.core.parser.StvnParser.SchemaConstructorContext {
      private final org.stvnadore.core.parser.StvnParser.CollectionTypeContext coll;

      public CustomSchemaConstructorContext(org.stvnadore.core.parser.StvnParser.CollectionTypeContext coll) {
          super(null, 0);
          this.coll = coll;
      }

      @Override
      public org.stvnadore.core.parser.StvnParser.CollectionTypeContext collectionType() {
          return coll;
      }
  }

  private static class CustomCollectionTypeContext extends org.stvnadore.core.parser.StvnParser.CollectionTypeContext {
      private final org.stvnadore.core.parser.StvnParser.SchemaTypeContext child;

      public CustomCollectionTypeContext(org.stvnadore.core.parser.StvnParser.SchemaTypeContext child) {
          super(null, 0);
          this.child = child;
      }

      @Override
      public java.util.List<org.stvnadore.core.parser.StvnParser.SchemaTypeContext> schemaType() {
          return java.util.List.of(child);
      }

      @Override
      public org.stvnadore.core.parser.StvnParser.SchemaTypeContext schemaType(int i) {
          return i == 0 ? child : null;
      }

      @Override
      public org.antlr.v4.runtime.tree.TerminalNode COLL_SEQ() {
          return new org.antlr.v4.runtime.tree.TerminalNodeImpl(new org.antlr.v4.runtime.CommonToken(0, ":Seq"));
      }
  }

  private static class AnonymousCyclicSchemaTypeContext extends org.stvnadore.core.parser.StvnParser.SchemaTypeContext {
      private org.stvnadore.core.parser.StvnParser.SchemaConstructorContext ctor;
      public boolean resolving = false;

      public AnonymousCyclicSchemaTypeContext() {
          super(null, 0);
      }

      public void setCtor(org.stvnadore.core.parser.StvnParser.SchemaConstructorContext ctor) {
          this.ctor = ctor;
      }

      @Override
      public org.stvnadore.core.parser.StvnParser.SchemaConstructorContext schemaConstructor() {
          if (resolving) {
              return null;
          }
          return ctor;
      }

      @Override
      public org.stvnadore.core.parser.StvnParser.TypeKeywordContext typeKeyword() {
          return null;
      }
  }

  @org.junit.jupiter.api.Test
  public void testProgrammaticCyclicAstHashing() {
      var kw = new CustomTypeKeywordContext(":MyCircularType");
      var childCtx = new CustomSchemaTypeContext(null, kw);
      var parentColl = new CustomCollectionTypeContext(childCtx);
      var parentCtor = new CustomSchemaConstructorContext(parentColl);
      var parentCtx = new CustomSchemaTypeContext(parentCtor, null);

      var def = new CustomTypeDefinitionContext(kw, parentCtx);
      var defsEntry = new CustomDefsEntryContext(java.util.List.of(def));
      var body = new CustomDocumentBodyContext(defsEntry);
      var doc = new CustomDocumentContext(body);

      // Set parent links for ANTLR hierarchy traversal
      childCtx.parent = parentColl;
      parentColl.parent = parentCtor;
      parentCtor.parent = parentCtx;
      parentCtx.parent = def;
      def.parent = defsEntry;
      defsEntry.parent = body;
      body.parent = doc;

      var resolved = org.stvnadore.core.validation.StvnTypeResolver.resolvePrimitiveSchema(doc, parentCtx, java.util.Set.of()).get();

      var hash1 = org.stvnadore.core.binary.StvnSchemaHasher.hashSchema(resolved);
      var hash2 = org.stvnadore.core.binary.StvnSchemaHasher.hashSchema(resolved);
      org.junit.jupiter.api.Assertions.assertEquals(hash1, hash2);
  }

  @org.junit.jupiter.api.Test
  public void testAnonymousCyclicAstThrowsException() {
      var parentCtx = new AnonymousCyclicSchemaTypeContext();
      var childCtx = new AnonymousCyclicSchemaTypeContext() {
          @Override
          public org.stvnadore.core.parser.StvnParser.SchemaConstructorContext schemaConstructor() {
              parentCtx.resolving = true; // break parent's recursion when resolving child
              return super.schemaConstructor();
          }
      };

      var parentColl = new CustomCollectionTypeContext(childCtx);
      var parentCtor = new CustomSchemaConstructorContext(parentColl);
      parentCtx.setCtor(parentCtor);

      var childColl = new CustomCollectionTypeContext(parentCtx);
      var childCtor = new CustomSchemaConstructorContext(childColl);
      childCtx.setCtor(childCtor);

      org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema resolved;
      try {
          parentCtx.resolving = false;
          childCtx.resolving = false;
          resolved = org.stvnadore.core.validation.StvnTypeResolver.resolvePrimitiveSchema(null, parentCtx, java.util.Set.of()).get();
      } finally {
          parentCtx.resolving = false;
          childCtx.resolving = false;
      }

      org.junit.jupiter.api.Assertions.assertThrows(
          org.stvnadore.core.validation.MalformedSchemaException.class,
          () -> org.stvnadore.core.binary.StvnSchemaHasher.hashSchema(resolved)
      );
  }

  @org.junit.jupiter.api.Test
  public void testSha256TamperValidation() {
      var ir = org.stvnadore.core.StvnCompiler.compile("""
          {
            :type :Int32
            :body 42
          }
          """).orElseThrow();
      var schema = ir.schema();
      var correctHash = org.stvnadore.core.binary.StvnSchemaHasher.computeSha256(schema);

      // Test that the encoder throws if mismatching hash is passed
      var incorrectHash = new byte[32];
      org.junit.jupiter.api.Assertions.assertThrows(
          org.stvnadore.core.binary.exceptions.StvnSerializationException.class,
          () -> {
              var badEncoder = new org.stvnadore.core.binary.StvnBinaryEncoder(true, new SchemaIdentityStrategy.ExplicitSha256(incorrectHash));
              badEncoder.encode(ir);
          }
      );

      // Encode with correct hash in ExplicitSha256 strategy
      var encoder = new org.stvnadore.core.binary.StvnBinaryEncoder(true, new SchemaIdentityStrategy.ExplicitSha256(correctHash));
      var encoded = encoder.encode(ir);

      // Tamper with the hash in the encoded buffer
      var tampered = encoded.duplicate();
      tampered.put(5, (byte) (tampered.get(5) ^ 0xFF));

      // Attempting to decode the tampered buffer should throw StvnSerializationException
      org.junit.jupiter.api.Assertions.assertThrows(
          org.stvnadore.core.binary.exceptions.StvnSerializationException.class,
          () -> {
              var root = org.stvnadore.core.binary.StvnBinaryDecoder.open(tampered, null, null, null);
              org.stvnadore.core.binary.StvnBinaryDecoder.unpack(root, Optional.of(schema));
          }
      );
  }

  @org.junit.jupiter.api.Test
  public void testSelfDescribingSandboxEtl() {
      String schemaText = """
          {
            :type :Tuple( :Int32 :String )
            :body ( 0 "" )
          }
          """;
      var ir = org.stvnadore.core.StvnCompiler.compile("""
          {
            :type :Tuple( :Int32 :String )
            :body ( 42 "ETL data" )
          }
          """).orElseThrow();

      // Encode with SelfDescribingSchema strategy
      var encoder = new org.stvnadore.core.binary.StvnBinaryEncoder(true, new SchemaIdentityStrategy.SelfDescribingSchema(schemaText));
      var encoded = encoder.encode(ir);

      // Decode passing null registry/fallback/universalFallback
      var root = org.stvnadore.core.binary.StvnBinaryDecoder.open(encoded, null, null, null);
      var decodedIr = org.stvnadore.core.binary.StvnBinaryDecoder.unpack(root, Optional.empty());

      org.junit.jupiter.api.Assertions.assertEquals(ir, decodedIr);
  }

  @org.junit.jupiter.api.Test
  public void testSelfDescribingSandboxUnionDistinctnessViolation() {
      String invalidSchemaText = """
          {
            :type :Union( :Int32 :Int16 :String )
            :body "hello"
          }
          """;
      var ir = org.stvnadore.core.StvnCompiler.compile("""
          {
            :type :Union( :Int32 :Int16 :String )
            :body "hello"
          }
          """).orElseThrow();

      // Encode
      var encoder = new org.stvnadore.core.binary.StvnBinaryEncoder(true, new SchemaIdentityStrategy.SelfDescribingSchema(invalidSchemaText));
      var encoded = encoder.encode(ir);

      // Decoding should throw StvnSerializationException due to Union distinctness violation
      org.junit.jupiter.api.Assertions.assertThrows(
          org.stvnadore.core.binary.exceptions.StvnSerializationException.class,
          () -> org.stvnadore.core.binary.StvnBinaryDecoder.open(encoded, null, null, null)
      );
  }

  @org.junit.jupiter.api.Test
  public void testLayoutDeterminism() {
      var input1 = """
          {
            :type :Seq( :Int )
            :body [ 1 2 3 ]
          }
          """;
      var input2 = "{:type :Seq(:Int) :body [1 2 3]}";
      var ir1 = org.stvnadore.core.StvnCompiler.compile(input1).orElseThrow();
      var ir2 = org.stvnadore.core.StvnCompiler.compile(input2).orElseThrow();
      org.junit.jupiter.api.Assertions.assertNotNull(ir1);
      org.junit.jupiter.api.Assertions.assertNotNull(ir2);

      var canon1 = org.stvnadore.core.StvnCompiler.toCanonicalString(ir1);
      var canon2 = org.stvnadore.core.StvnCompiler.toCanonicalString(ir2);
      org.junit.jupiter.api.Assertions.assertEquals(canon1, canon2);

      var fp1 = org.stvnadore.core.StvnCompiler.computeCasFingerprint(ir1);
      var fp2 = org.stvnadore.core.StvnCompiler.computeCasFingerprint(ir2);
      org.junit.jupiter.api.Assertions.assertArrayEquals(fp1, fp2);
  }

  @org.junit.jupiter.api.Test
  public void testPositionalIdentityDifferentiation() {
      var ir1 = org.stvnadore.core.StvnCompiler.compile("{:type :Seq(:Int) :body [1 2 3]}").orElseThrow();
      var ir2 = org.stvnadore.core.StvnCompiler.compile("{:type :Seq(:Int) :body [3 2 1]}").orElseThrow();
      org.junit.jupiter.api.Assertions.assertNotNull(ir1);
      org.junit.jupiter.api.Assertions.assertNotNull(ir2);

      var canon1 = org.stvnadore.core.StvnCompiler.toCanonicalString(ir1);
      var canon2 = org.stvnadore.core.StvnCompiler.toCanonicalString(ir2);
      org.junit.jupiter.api.Assertions.assertNotEquals(canon1, canon2);

      var fp1 = org.stvnadore.core.StvnCompiler.computeCasFingerprint(ir1);
      var fp2 = org.stvnadore.core.StvnCompiler.computeCasFingerprint(ir2);
      org.junit.jupiter.api.Assertions.assertFalse(java.util.Arrays.equals(fp1, fp2));
  }

  @org.junit.jupiter.api.Test
  public void testSequencingRejection() {
      var dummySchema = org.stvnadore.core.test.StvnTestFactory.createDummySchema();
      var nonSequencedSet = new java.util.HashSet<org.stvnadore.core.ir.StvnValue>();
      nonSequencedSet.add(new org.stvnadore.core.ir.StvnValue.StvnBoolean(dummySchema, true));

      org.junit.jupiter.api.Assertions.assertThrows(
          org.stvnadore.core.validation.MalformedPayloadException.class,
          () -> new org.stvnadore.core.ir.StvnValue.StvnSet(dummySchema, nonSequencedSet, false)
      );

      var nonSequencedMap = new java.util.HashMap<org.stvnadore.core.ir.StvnValue, org.stvnadore.core.ir.StvnValue>();
      nonSequencedMap.put(
          new org.stvnadore.core.ir.StvnValue.StvnBoolean(dummySchema, true),
          new org.stvnadore.core.ir.StvnValue.StvnBoolean(dummySchema, false)
      );

      org.junit.jupiter.api.Assertions.assertThrows(
          org.stvnadore.core.validation.MalformedPayloadException.class,
          () -> new org.stvnadore.core.ir.StvnValue.StvnMap(dummySchema, nonSequencedMap, false, false)
      );
  }

  @org.junit.jupiter.api.Test
  public void testBlockStringCollapsingAndRetention() {
      var ir1 = org.stvnadore.core.StvnCompiler.compile("""
          {
            :type :String
            :body \"\"\"
              hello
              world
            \"\"\"
          }
          """).orElseThrow();
      var canon1 = org.stvnadore.core.StvnCompiler.toCanonicalString(ir1);
      org.junit.jupiter.api.Assertions.assertTrue(canon1.contains("\"hello\\nworld\\n\""));

      var ir2 = org.stvnadore.core.StvnCompiler.compile("""
          {
            :defs {
              :preserved { #preserveIndent #TRUE } :String
            }
            :type :preserved
            :body \"\"\"
              helloPreserved
              worldPreserved
            \"\"\"
          }
          """).orElseThrow();
      org.junit.jupiter.api.Assertions.assertNotNull(ir2);
      var canon2 = org.stvnadore.core.StvnCompiler.toCanonicalString(ir2);
      org.junit.jupiter.api.Assertions.assertTrue(canon2.contains("\"\"\"\n"));
      org.junit.jupiter.api.Assertions.assertTrue(canon2.contains("helloPreserved"));
      org.junit.jupiter.api.Assertions.assertTrue(canon2.contains("worldPreserved"));
  }
}
