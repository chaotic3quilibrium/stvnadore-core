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
      var irOpt = StvnCompiler.compilePayload(testProfile.stvnPayload());

      // PATHWAY B: Harness boundary check to guard against syntax compilation errors in error-profile payloads
      if (irOpt.isPresent()) {
        var ir = irOpt.get();
        var encoder = new StvnBinaryEncoder(true, new SchemaIdentityStrategy.UniversalDefault());
        var encoded = encoder.encode(ir);

        var rootPointer = StvnBinaryDecoder.openStrict(encoded, new SchemaIdentityStrategy.UniversalDefault());
        var decodedIr = StvnBinaryDecoder.unpackIr(rootPointer, Optional.of(ir.schema()));
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
            "Type mismatch: expected scalar :Boolean, found scalar :Int literal '1'"),

        TestProfile.createError(
            "Boolean Validation - Implicit Truthiness String 'true'",
            """
                {
                  :type :Seq( :Boolean )
                  :body [ "true" ]
                }
                """,
            "Type mismatch: expected scalar :Boolean, found scalar :String literal"),

        TestProfile.createError(
            "Boolean Validation - Implicit Truthiness bare word boolean true",
            """
                {
                  :type :Seq( :Boolean )
                  :body [ true ]
                }
                """,
            "STVN Syntax Error: unexpected extraneous symbol 'true'"),

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
                    :StatusRegistry :Map( :Status :org/stvnadore/prelude/UuidLowerCase )
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
            "Rule C violation: Ambiguous keyword clash; '#N' is both an :Option control tag and an active inner :Enum variant"),

        TestProfile.createError(
            "Enum Validation - Option Tag Collision Ambiguity Short Some",
            """
                {
                  :type :Option( :Enum[ #Y #M #S ] )
                  :body #S
                }
                """,
            "Rule C violation: Ambiguous keyword clash; '#S' is both an :Option control tag and an active inner :Enum variant"),

        TestProfile.createError(
            "Enum Validation - Either Tag Collision Ambiguity Short Left",
            """
                {
                  :type :Either( :Int :Enum[ #Y #M #L ] )
                  :body #L
                }
                """,
            "Rule C violation: Ambiguous keyword clash; '#L' is both an :Either control tag and an active branch :Enum variant"),

        TestProfile.createError(
            "Enum Validation - Either Tag Collision Ambiguity Short Right",
            """
                {
                  :type :Either( :Int :Enum[ #Y #M #R ] )
                  :body #R
                }
                """,
            "Rule C violation: Ambiguous keyword clash; '#R' is both an :Either control tag and an active branch :Enum variant")
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
            "Integer literal '-2147483649' overflows signed 32-bit capacity for :Int"),

        TestProfile.createError(
            "Default Number Alias - Int32 Overflow",
            """
                {
                  :type :Seq( :Int )
                  :body [ 2147483648 ]
                }
                """,
            "Integer literal '2147483648' overflows signed 32-bit capacity for :Int"),

        TestProfile.create(
            "Default Number Alias - Uint32 Boundaries Success",
            """
                {
                  :defs {
                    :U32 { #unsigned } :Int
                  }
                  :type :Seq( :U32 )
                  :body [ 0 4294967295 ]
                }
                """),

        TestProfile.createError(
            "Default Number Alias - Uint32 Underflow",
            """
                {
                  :defs {
                    :U32 { #unsigned } :Int
                  }
                  :type :Seq( :U32 )
                  :body [ -1 ]
                }
                """,
            "Integer literal '-1' overflows unsigned 32-bit capacity for :U32"),

        TestProfile.createError(
            "Default Number Alias - Uint32 Overflow",
            """
                {
                  :defs {
                    :U32 { #unsigned } :Int
                  }
                  :type :Seq( :U32 )
                  :body [ 4294967296 ]
                }
                """,
            "Integer literal '4294967296' overflows unsigned 32-bit capacity for :U32"),

        TestProfile.create(
            "Integer 1-Bit Validation - Int1 Boundaries success",
            """
                {
                  :defs {
                    :I1 { #size 1 } :Int
                  }
                  :type :Seq( :I1 )
                  :body [ -1 0 ]
                }
                """),

        TestProfile.createError(
            "Integer 1-Bit Validation - Int1 Underflow",
            """
                {
                  :defs {
                    :I1 { #size 1 } :Int
                  }
                  :type :Seq( :I1 )
                  :body [ -2 ]
                }
                """,
            "Integer literal '-2' overflows signed 1-bit capacity for :I1"),

        TestProfile.createError(
            "Integer 1-Bit Validation - Int1 Overflow",
            """
                {
                  :defs {
                    :I1 { #size 1 } :Int
                  }
                  :type :Seq( :I1 )
                  :body [ 1 ]
                }
                """,
            "Integer literal '1' overflows signed 1-bit capacity for :I1"),

        TestProfile.create(
            "Integer 1-Bit Validation - Uint1 Boundaries Success",
            """
                {
                  :defs {
                    :U1 { #unsigned #size 1 } :Int
                  }
                  :type :Seq( :U1 )
                  :body [ 0 1 ]
                }
                """),

        TestProfile.createError(
            "Integer 1-Bit Validation - Uint1 Underflow",
            """
                {
                  :defs {
                    :U1 { #unsigned #size 1 } :Int
                  }
                  :type :Seq( :U1 )
                  :body [ -1 ]
                }
                """,
            "Integer literal '-1' overflows unsigned 1-bit capacity for :U1"),

        TestProfile.createError(
            "Integer 1-Bit Validation - Uint1 Overflow",
            """
                {
                  :defs {
                    :U1 { #unsigned #size 1 } :Int
                  }
                  :type :Seq( :U1 )
                  :body [ 2 ]
                }
                """,
            "Integer literal '2' overflows unsigned 1-bit capacity for :U1"),

        TestProfile.createError(
            "Integer 7-Bit Validation - Int7 Underflow",
            """
                {
                  :defs {
                    :I7 { #size 7 } :Int
                  }
                  :type :Seq( :I7 )
                  :body [ -65 ]
                }
                """,
            "Integer literal '-65' overflows signed 7-bit capacity for :I7"),

        TestProfile.create(
            "Integer 8-Bit Validation - Int8 Success",
            """
                {
                  :defs {
                    :I8 { #size 8 } :Int
                  }
                  :type :Seq( :I8 )
                  :body [ -128 127 ]
                }
                """),

        TestProfile.createError(
            "Integer 256-Bit Validation - Uint256 Underflow",
            """
                {
                  :defs {
                    :U256 { #unsigned #size 256 } :Int
                  }
                  :type :Seq( :U256 )
                  :body [ -1 ]
                }
                """,
            "Integer literal '-1' overflows unsigned 256-bit capacity for :U256"),

        TestProfile.create(
            "Float Boundaries - Float32 Success",
            """
                {
                  :defs {
                    :F32 { #size 32 } :Float
                  }
                  :type :Seq( :F32 )
                  :body [ -3.4028235E38 3.4028235E38 ]
                }
                """),

        TestProfile.createError(
            "Float Boundaries - Float64 Overflow",
            """
                {
                  :defs {
                    :F64 :Float
                  }
                  :type :Seq( :F64 )
                  :body [ 1.7976931348623159E308 ]
                }
                """,
            "Value [1.7976931348623159E+308] is out of bounds for :F64 (-1.7976931348623157E+308 to 1.7976931348623157E+308)"),

        TestProfile.create(
            "FloatExact - Micro Arbitrary Precision Capacity Success",
            """
                {
                  :defs {
                    :ExactFloat { #exact } :Float
                  }
                  :type :Seq( :ExactFloat )
                  :body [ 0.0000000000000000000000000000000000000000000000000000000000001 ]
                }
                """),

        TestProfile.createError(
            "FloatExact - Accurate Bound Overhead Trapping via BigDecimal",
            """
                {
                  :defs {
                    :precisePercentage { #exact #minIncl 0.0 #maxExcl 100.0 } :Float
                  }
                  :type :Seq( :precisePercentage )
                  :body [ 100.00000000000000000000000000000000001 ]
                }
                """,
            "Constraint violation (:precisePercentage): Value must be strictly less than 100.0"),

        TestProfile.create(
            "Alternative Numeric Bases - Hex Octal Binary Integrations Validated",
            """
                {
                  :defs {
                    :I16 { #size 16 } :Int
                  }
                  :type :Seq( :I16 )
                  :body [ 0x7FFF -0x8000 0b0111111111111111 0o77777 ]
                }
                """),

        TestProfile.createError(
            "Alternative Numeric Bases - Hex Out Of Range Upper Limit",
            """
                {
                  :defs {
                    :I16 { #size 16 } :Int
                  }
                  :type :Seq( :I16 )
                  :body [ 0x8000 ]
                }
                """,
            "Integer literal '0x8000' overflows signed 16-bit capacity for :I16")
    ).map(testProfile -> Arguments.of(testProfile.displayName(), testProfile));
  }

  private static Stream<Arguments> stringAndTemporalSource() {
    //noinspection TextBlockMigration
    return Stream.of(
        TestProfile.create(
            "Uuid Validation - Mixed Case Success",
            """
                {
                  :type :Seq( :org/stvnadore/prelude/UuidMixedCase )
                  :body [ "A6287828-1b9b-4295-B66D-015de536c4e1" ]
                }
                """),

        TestProfile.createError(
            "Uuid Validation - Malformed Length Short",
            """
                {
                  :type :Seq( :org/stvnadore/prelude/UuidMixedCase )
                  :body [ "not-a-uuid" ]
                }
                """,
            "Constraint violation (:org/stvnadore/prelude/UuidMixedCase): Fixed string must be exactly 36 characters long, got 10"),

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
                  :type :Seq( :org/stvnadore/prelude/Sha256LowerCase )
                  :body [ "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" ]
                }
                """),

        TestProfile.create(
            "Sha256 Validation - Uppercase Hex Success",
            """
                {
                  :type :Seq( :org/stvnadore/prelude/Sha256MixedCase )
                  :body [ "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD" ]
                }
                """),

        TestProfile.create(
            "Sha256 Validation - Mixed Case Hex Success",
            """
                {
                  :type :Seq( :org/stvnadore/prelude/Sha256MixedCase )
                  :body [ "bA7816bF8f01CfEa414140De5dAE2223B00361A396177A9cB410Ff61F20015aD" ]
                }
                """),

        TestProfile.createError(
            "Sha256 Validation - Error Length Short (40 chars)",
            """
                {
                  :type :Seq( :org/stvnadore/prelude/Sha256LowerCase )
                  :body [ "2fd4e1c67a2d28fced849ee1bb76e7391b93eb12" ]
                }
                """,
            "Constraint violation (:org/stvnadore/prelude/Sha256LowerCase): Fixed string must be exactly 64 characters long, got 40"),

        TestProfile.createError(
            "Sha256 Validation - Error Length Short (63 chars)",
            """
                {
                  :type :Seq( :org/stvnadore/prelude/Sha256LowerCase )
                  :body [ "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015a" ]
                }
                """,
            "Constraint violation (:org/stvnadore/prelude/Sha256LowerCase): Fixed string must be exactly 64 characters long, got 63"),

        TestProfile.createError(
            "Sha256 Validation - Error Length Long (65 chars)",
            """
                {
                  :type :Seq( :org/stvnadore/prelude/Sha256LowerCase )
                  :body [ "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad0" ]
                }
                """,
            "Constraint violation (:org/stvnadore/prelude/Sha256LowerCase): Fixed string must be exactly 64 characters long, got 65"),

        TestProfile.createError(
            "Sha256 Validation - Error Non-Hex Character",
            """
                {
                  :type :Seq( :org/stvnadore/prelude/Sha256LowerCase )
                  :body [ "ga7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" ]
                }
                """,
            "Constraint violation (:org/stvnadore/prelude/Sha256LowerCase): String does not match required pattern: ^[0-9a-f]{64}$"),

        TestProfile.createError(
            "Sha256 Validation - Error Non-Hex Symbol",
            """
                {
                  :type :Seq( :org/stvnadore/prelude/Sha256MixedCase )
                  :body [ "ba7816bf8f01cfea414140de5dae2223-00361a396177a9cb410ff61f20015ad" ]
                }
                """,
            "Constraint violation (:org/stvnadore/prelude/Sha256MixedCase): String does not match required pattern: ^[0-9a-fA-F]{64}$"),

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
                  :defs {
                    :DtOffset { #offset } :DateTime
                  }
                  :type :Seq( :DtOffset )
                  :body [ "2026-03-15T08:00:00-05:00" ]
                }
                """),

        TestProfile.createError(
            "Temporal Validation - DateTimeOffset Invalid Format",
            """
                {
                  :defs {
                    :DtOffset { #offset } :DateTime
                  }
                  :type :Seq( :DtOffset )
                  :body [ "2026-03-15 08:00:00" ]
                }
                """,
            "Invalid {#offset} :DateTime format"),

        TestProfile.createError(
            "Temporal Validation - DateTimeOffset Rejects Zone",
            """
                {
                  :defs {
                    :DtOffset { #offset } :DateTime
                  }
                  :type :Seq( :DtOffset )
                  :body [ "2026-03-15T08:00:00[America/Chicago]" ]
                }
                """,
            "Time zone brackets [...] are prohibited in {#offset} :DateTime"),

        TestProfile.create(
            "Temporal Validation - DateTimeZoned Success",
            """
                {
                  :defs {
                    :DtZoned { #zoned } :DateTime
                  }
                  :type :Seq( :DtZoned )
                  :body [ "2026-03-15T08:00:00[America/Chicago]" ]
                }
                """),

        TestProfile.createError(
            "Temporal Validation - DateTimeZoned Missing Region ID",
            """
                {
                  :defs {
                    :DtZoned { #zoned } :DateTime
                  }
                  :type :Seq( :DtZoned )
                  :body [ "2026-03-06T15:53:08-06:00" ]
                }
                """,
            "Explicit UTC offsets (+/-HH:mm or Z) are prohibited in {#zoned} :DateTime"),

        TestProfile.createError(
            "Temporal Validation - DateTimeZoned Rejects Offset",
            """
                {
                  :defs {
                    :DtZoned { #zoned } :DateTime
                  }
                  :type :Seq( :DtZoned )
                  :body [ "2026-03-15T08:00:00-05:00[America/Chicago]" ]
                }
                """,
            "Explicit UTC offsets (+/-HH:mm or Z) are prohibited in {#zoned} :DateTime"),

        TestProfile.create(
            "Temporal Validation - DateTimeAudited Success",
            """
                {
                  :defs {
                    :DtAudited { #audited } :DateTime
                  }
                  :type :Seq( :DtAudited )
                  :body [ "2026-03-15T08:00:00-05:00[America/Chicago]" ]
                }
                """),

        TestProfile.createError(
            "Temporal Validation - DateTimeAudited Missing Zone",
            """
                {
                  :defs {
                    :DtAudited { #audited } :DateTime
                  }
                  :type :Seq( :DtAudited )
                  :body [ "2026-03-15T08:00:00-05:00" ]
                }
                """,
            "Invalid {#audited} :DateTime format"),

        TestProfile.create(
            "Temporal Validation - TimeEpochNs BigInteger Storage Success",
            """
                {
                  :defs {
                    :EpochNs { #ns } :TimeEpoch
                  }
                  :type :Seq( :EpochNs )
                  :body [ 9223372036854775808 ]
                }
                """),

        TestProfile.create(
            "Bounded String - Fixed Length Match",
            """
                {
                  :defs {
                    :StrFixed5 { #minSize 5 #maxSize 5 } :String
                  }
                  :type :Seq( :StrFixed5 )
                  :body [ "hello" ]
                }
                """),

        TestProfile.createError(
            "String Validation - Rogue Java Concatenation Artifacts",
            """
                {
                  :defs {
                    :StrFixed5 { #minSize 5 #maxSize 5 } :String
                  }
                  :type :Seq( :StrFixed5 )
                  :body ["  //<-- trailing double-quote
                    "hello"
                  ]" +      //<-- trailing double-quote, space, and plus-sign
                }
                """,
            "STVN Syntax Error: unrecognized lexical character sequence"),

        TestProfile.createError(
            "Bounded String - Fixed Length Mismatch",
            """
                {
                  :defs {
                    :StrFixed5 { #minSize 5 #maxSize 5 } :String
                  }
                  :type :Seq( :StrFixed5 )
                  :body [ "hello!" ]
                }
                """,
            "Constraint violation (:StrFixed5): Fixed string must be exactly 5 characters long, got 6"),

        TestProfile.createError(
            "Bounded String - NonEmpty Rejects Empty String",
            """
                {
                  :defs {
                    :StringNonEmpty5 { #minSize 1 #maxSize 5 } :String
                  }
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
                  :defs {
                    :String64 { #maxSize 64 } :String
                  }
                  :type :Seq( :String64 )
                  :body [ "" ]
                }
                """),

        TestProfile.create(
            "Bounded String - Sub-Limit Typical String (:String64 with len 37)",
            """
                {
                  :defs {
                    :String64 { #maxSize 64 } :String
                  }
                  :type :Seq( :String64 )
                  :body [ "This string is exactly 37 chars long." ]
                }
                """),

        TestProfile.create(
            "Bounded String - Boundary Limit String (:String64 with len 64)",
            """
                {
                  :defs {
                    :String64 { #maxSize 64 } :String
                  }
                  :type :Seq( :String64 )
                  :body [ "1234567890123456789012345678901234567890123456789012345678901234" ]
                }
                """),

        TestProfile.createError(
            "Bounded String - Overflow String Rejection (:String64 with len 65)",
            """
                {
                  :defs {
                    :String64 { #maxSize 64 } :String
                  }
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
                  :defs {
                    :StringNonEmpty64 { #minSize 1 #maxSize 64 } :String
                  }
                  :type :Seq( :StringNonEmpty64 )
                  :body [ "" ]
                }
                """,
            "Constraint violation (:StringNonEmpty64): String cannot be empty"),

        TestProfile.create(
            "Bounded Non-Empty - Sub-Limit String (:StringNonEmpty64 with len 37)",
            """
                {
                  :defs {
                    :StringNonEmpty64 { #minSize 1 #maxSize 64 } :String
                  }
                  :type :Seq( :StringNonEmpty64 )
                  :body [ "This string is exactly 37 chars long." ]
                }
                """),

        TestProfile.create(
            "Bounded Non-Empty - Boundary Limit String (:StringNonEmpty64 with len 64)",
            """
                {
                  :defs {
                    :StringNonEmpty64 { #minSize 1 #maxSize 64 } :String
                  }
                  :type :Seq( :StringNonEmpty64 )
                  :body [ "1234567890123456789012345678901234567890123456789012345678901234" ]
                }
                """),

        TestProfile.createError(
            "Bounded Non-Empty - Overflow String Rejection (:StringNonEmpty64 with len 65)",
            """
                {
                  :defs {
                    :StringNonEmpty64 { #minSize 1 #maxSize 64 } :String
                  }
                  :type :Seq( :StringNonEmpty64 )
                  :body [ "12345678901234567890123456789012345678901234567890123456789012345" ]
                }
                """,
            "Constraint violation (:StringNonEmpty64): String length exceeds maximum length of 64 characters, got 65"),

        // --- 3. Fixed-Length String Tests (:StrFixed64) ---
        TestProfile.create(
            "Fixed String - Exact Length Match (:StrFixed64)",
            """
                {
                  :defs {
                    :StrFixed64 { #minSize 64 #maxSize 64 } :String
                  }
                  :type :Seq( :StrFixed64 )
                  :body [ "1234567890123456789012345678901234567890123456789012345678901234" ]
                }
                """),

        TestProfile.createError(
            "Fixed String - Underflow Rejection (:StrFixed64 with len 37)",
            """
                {
                  :defs {
                    :StrFixed64 { #minSize 64 #maxSize 64 } :String
                  }
                  :type :Seq( :StrFixed64 )
                  :body [ "This string is exactly 37 chars long." ]
                }
                """,
            "Constraint violation (:StrFixed64): Fixed string must be exactly 64 characters long, got 37"),

        TestProfile.createError(
            "Fixed String - Overflow Rejection (:StrFixed64 with len 65)",
            """
                {
                  :defs {
                    :StrFixed64 { #minSize 64 #maxSize 64 } :String
                  }
                  :type :Seq( :StrFixed64 )
                  :body [ "12345678901234567890123456789012345678901234567890123456789012345" ]
                }
                """,
            "Constraint violation (:StrFixed64): Fixed string must be exactly 64 characters long, got 65"),

        // --- 4. Nominal Type Alias Bounded Tests ---
        TestProfile.create(
            "Nominal Alias - Bounded Text Sub-Limit (:BoundedText :String64)",
            """
                {
                  :defs {
                    :String64 { #maxSize 64 } :String
                    :BoundedText :String64
                  }
                  :type :BoundedText
                  :body "Short text"
                }
                """),

        TestProfile.createError(
            "Nominal Alias - Bounded Text Overflow (:BoundedText :String64)",
            """
                {
                  :defs {
                    :String64 { #maxSize 64 } :String
                    :BoundedText :String64
                  }
                  :type :BoundedText
                  :body "12345678901234567890123456789012345678901234567890123456789012345"
                }
                """,
            "Constraint violation (:BoundedText): String length exceeds maximum length of 64 characters, got 65"),

        TestProfile.createError(
            "String Literal Variations - Block for end on same line (#preserveIndent implicitly #FALSE) [PROHIBITED]",
            """
                {
                  :defs {
                    :StrFixed6 { #minSize 6 #maxSize 6 } :String
                  }
                  :type :Seq( :StrFixed6 )
                  :body [
                    "hello!"
                    \"\"\"
                    hi1234\"\"\"
                  ]
                }""",
            "STVN Syntax Error: Rule STR-04 violation: Unfenced block strings are prohibited; multi-line strings must use fenced syntax \"\"\"[TAG]"),

        TestProfile.createError(
            "String Literal Variations - Block for end on next line (#preserveIndent implicitly #FALSE) [PROHIBITED]",
            """
                {
                  :defs {
                    :StrFixed7 { #minSize 7 #maxSize 7 } :String
                  }
                  :type :Seq( :StrFixed7 )
                  :body [
                    "hello!!"
                    \"\"\"
                    hi1234
                    \"\"\"
                  ]
                }""",
            "STVN Syntax Error: Rule STR-04 violation: Unfenced block strings are prohibited; multi-line strings must use fenced syntax \"\"\"[TAG]"),

        TestProfile.createError(
            "String Literal Variations - Block for end on same line (#preserveIndent explicitly #TRUE) [PROHIBITED]",
            """
                {
                  :defs {
                    :strTrue { #minSize 10 #maxSize 10 #preserveIndent #TRUE } :String
                  }
                  :type :Seq( :strTrue )
                  :body [
                    "hello!!!!!"
                    \"\"\"
                    hi1234\"\"\"
                  ]
                }""",
            "STVN Syntax Error: Rule STR-04 violation: Unfenced block strings are prohibited; multi-line strings must use fenced syntax \"\"\"[TAG]"),

        TestProfile.createError(
            "String Literal Variations - Block for end on next line (#preserveIndent explicitly #TRUE) [PROHIBITED]",
            """
                {
                  :defs {
                    :strTrue { #minSize 15 #maxSize 15 #preserveIndent #TRUE } :String
                  }
                  :type :Seq( :strTrue )
                  :body [
                    "hello!!!!!hello"
                    \"\"\"
                    hi1234
                    \"\"\"
                  ]
                }""",
            "STVN Syntax Error: Rule STR-04 violation: Unfenced block strings are prohibited; multi-line strings must use fenced syntax \"\"\"[TAG]"),

        TestProfile.create(
            "String Literal Variations - Fenced for end on same line (#preserveIndent implicitly #FALSE)",
            """
                {
                  :defs {
                    :StrFixed6 { #minSize 6 #maxSize 6 } :String
                  }
                  :type :Seq( :StrFixed6 )
                  :body [
                    "hello!"
                    \"\"\"[FENCE]
                    hi1234[FENCE]\"\"\"
                  ]
                }"""),

        TestProfile.create(
            "String Literal Variations - Fenced for end on next line (#preserveIndent implicitly #FALSE)",
            """
                {
                  :defs {
                    :StrFixed7 { #minSize 7 #maxSize 7 } :String
                  }
                  :type :Seq( :StrFixed7 )
                  :body [
                    "hello!!"
                    \"\"\"[FENCE]
                    hi1234
                    [FENCE]\"\"\"
                  ]
                }"""),

        TestProfile.create(
            "String Literal Variations - Fenced for end on same line (#preserveIndent explicitly #TRUE)",
            """
                {
                  :defs {
                    :strTrue { #minSize 10 #maxSize 10 #preserveIndent #TRUE } :String
                  }
                  :type :Seq( :strTrue )
                  :body [
                    "hello!!!!!"
                    \"\"\"[FENCE]
                    hi1234[FENCE]\"\"\"
                  ]
                }"""),

        TestProfile.create(
            "String Literal Variations - Fenced for end on next line (#preserveIndent explicitly #TRUE)",
            """
                {
                  :defs {
                    :strTrue { #minSize 15 #maxSize 15 #preserveIndent #TRUE } :String
                  }
                  :type :Seq( :strTrue )
                  :body [
                    "hello!!!!!hello"
                    \"\"\"[FENCE]
                    hi1234
                    [FENCE]\"\"\"
                  ]
                }"""),

        TestProfile.create(
            "String Literal Variations - Fenced for STVN containing STVN",
            """
                {
                  :defs {
                    :StrFixed7 { #minSize 7 #maxSize 7 } :String
                  }
                  :type :Seq( :String )
                  :body [
                    "hello!"
                    \"\"\"[STVN_WITHIN_STVN]
                    {
                      :type :Seq( :StrFixed7 )
                      :body [
                        "hello!!"
                        \"\"\"[FENCE]
                        hi1234
                        [FENCE]\"\"\"
                      ]
                    }
                    [STVN_WITHIN_STVN]\"\"\"
                  ]
                }"""),

        TestProfile.createError(
            "String Literal Variations - Fenced Closing Tag Mismatch",
            """
                {
                  :type :Seq( :String )
                  :body [
                    "hello!"
                    \"\"\"[FENCE]
                    hi1234[FENCE_OOPS]\"\"\"
                  ]
                }""",
            "STVN Syntax Error: Rule STR-04 violation: Unclosed fenced string: expected closing delimiter '[FENCE]\"\"\"', found mismatched closing tag '[FENCE_OOPS]'")
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
                    :StatusId :org/stvnadore/prelude/UuidLowerCase
                    :StatusRegistry { #invertible #minSize 1 } :Map( :StatusType :StatusId )
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
                  :defs {
                    :StrFixed5 { #minSize 5 #maxSize 5 } :String
                  }
                  :type :Seq( :Tuple( :Int :StrFixed5 ) )
                  :body [ ( 1 "hello" ) ]
                }
                """),

        TestProfile.createError(
            "Tuple - Arity Mismatch Excess Elements",
            """
                {
                  :defs {
                    :StrFixed5 { #minSize 5 #maxSize 5 } :String
                  }
                  :type :Seq( :Tuple( :Int :StrFixed5 ) )
                  :body [ ( 1 "hello" #TRUE ) ]
                }
                """,
            "Tuple arity mismatch: Expected 2 elements, got 3"),

        TestProfile.createError(
            "Map Positional Resolution - Flipped Inner Structural Shapes",
            """
                {
                  :defs {
                    :StrFixed3 { #minSize 3 #maxSize 3 } :String
                    :I8 { #size 8 } :Int
                  }
                  :type :Map( :StrFixed3 :I8 )
                  :body {
                    [ 3 "six" ]
                  }
                }
                """,
            "Type mismatch: expected scalar :String, found scalar :Int literal '3'"),

        TestProfile.createError(
            "Non-Empty Collections - Void Content Failure Sequence",
            """
                {
                  :defs {
                    :I8 { #size 8 } :Int
                    :SeqNonEmptyInt8 { #minSize 1 } :Seq( :I8 )
                  }
                  :type :Seq( :SeqNonEmptyInt8 )
                  :body [ [ ] ]
                }
                """,
            "Sequence is marked as non-empty (#minSize 1) but contains no elements"),

        TestProfile.createError(
            "Deep Composition - Nested Multi-Layer Pathing Tracking Error",
            """
                {
                  :defs {
                    :StrFixed3 { #minSize 3 #maxSize 3 } :String
                    :I8 { #size 8 } :Int
                    :F32 { #size 32 } :Float
                  }
                  :type :Seq( :Map( :StrFixed3 :Either( :I8 :Tuple( :Boolean :F32 ) ) ) )
                  :body [
                    { [ "err" ( #FALSE "bad_float" ) ] }
                  ]
                }
                """,
            "Type mismatch: expected scalar :Float, found scalar :String literal"),

        TestProfile.create(
            "Empty Collections Allowed - Default States Allowed Gracefully",
            """
                {
                  :defs {
                    :MapInvIntInt { #invertible } :Map( :Int :Int )
                  }
                  :type :Seq( :Tuple( :Seq( :Int ) :Set( :String ) :Map( :Int :Int ) :MapInvIntInt ) )
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
                       :Int
                       :Float
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
                  :defs {
                    :U32 { #unsigned } :Int
                    :SetNonEmptyUint { #minSize 1 } :Set( :U32 )
                  }
                  :type :SetNonEmptyUint
                  :body [ 1 2 3 1 ]
                }
                """,
            "Duplicate set element detected"),

        TestProfile.createError(
            "Uniqueness - Inverse Map Values Clash",
            """
                {
                  :defs {
                    :StrFixed3 { #minSize 3 #maxSize 3 } :String
                    :I8 { #size 8 } :Int
                    :MapInvNonEmptyStrInt { #invertible #minSize 1 } :Map( :StrFixed3 :I8 )
                  }
                  :type :MapInvNonEmptyStrInt
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
              :type :Option( :Option( :Int ) )
              :body 42
            }
            """
    );

    var secondaryBranch = TestProfile.create(
        "Nested Implicit Evaluation - Secondary Branch Route",
        """
            {
              :type :Either( :Int :Either( :Boolean :String ) )
              :body "hello"
            }
            """
    );

    var overlapRejection = TestProfile.createError(
        "Overlap Rejection - Ambiguous Nested Sum Layout",
        """
            {
              :defs {
                :I32 :Int
                :I64 { #size 64 } :Int
              }
              :type :Either( :Option( :I32 ) :Option( :I64 ) )
              :body 42
            }
            """,
        "Ambiguous implicit resolution: Value matches both Left and Right branches of :Either"
    );

    var nominalEitherSubstringTest = TestProfile.create(
        "Nominal Substring Overlap - Either Validator Alias in Union",
        """
        {
          :defs {
            :EitherValidator :Int
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
                  :defs {
                    :I8 { #size 8 } :Int
                    :StrFixed5 { #minSize 5 #maxSize 5 } :String
                  }
                  :type :Seq( :Either( :I8 :StrFixed5 ) )
                  :body [ #Left 1 #Right "hello" ]
                }
                """),

        TestProfile.createError(
            "Explicit Tagged Unions - Inner Tag Routing Bounds Validation",
            """
                {
                  :defs {
                    :I8 { #size 8 } :Int
                    :StrFixed5 { #minSize 5 #maxSize 5 } :String
                  }
                  :type :Seq( :Either( :I8 :StrFixed5 ) )
                  :body [ #Right "hi" ]
                }
                """,
            "Constraint violation (:StrFixed5): Fixed string must be exactly 5 characters long, got 2"),

        TestProfile.createError(
            "Rule E Rejection - Left Variant Non-Inferable in Sequence",
            """
                {
                  :defs {
                    :I8 { #size 8 } :Int
                    :StrFixed5 { #minSize 5 #maxSize 5 } :String
                  }
                  :type :Seq( :Either( :I8 :StrFixed5 ) )
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
            "Ambiguous implicit either: Both sides are identical (:String), explicit #Left or #Right tag is required"),

        TestProfile.createError(
            "Rogue Wrappers - Option Tags Enforced on Primitives",
            """
                {
                  :defs {
                    :I8 { #size 8 } :Int
                  }
                  :type :Seq( :I8 )
                  :body [ #Some 1 ]
                }
                """,
            "Unexpected Option tag '#Some'; target schema is :I8"),

        TestProfile.createError(
            "Short Aliases And Downcasting - Nested Target Error Extraction Only",
            """
                {
                  :defs {
                    :I8 { #size 8 } :Int
                  }
                  :type :Seq( :Tuple( :Option( :I8 ) :Either( :I8 :String ) ) )
                  :body [ ( #S "bad" #L 1 ) ]
                }
                """,
            "Type mismatch: expected scalar :Int, found scalar :String literal"),

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
                    :myInt { #size 8 } :Int
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
            "STVN Syntax Error: unexpected token '}'; expected <value>"),

        TestProfile.createError(
            "Numeric Constraints - Min Bounds Checked",
            """
                {
                  :defs {
                    :age { #minIncl 0 #maxExcl 120 } :Int
                  }
                  :type :Seq( :age )
                  :body [ -1 ]
                }
                """,
            "Constraint violation (:age): Value -1 is outside allowable range (must be >= 0)"),

        TestProfile.createError(
            "Numeric Constraints - Min Bounds Collision",
            """
                {
                  :defs {
                    :speed { #minIncl 0.0 #minExcl -1.0 } :Float
                  }
                  :type :Seq( :speed )
                  :body [ -1.0 ]
                }
                """,
            "Constraint violation (:speed): #minIncl and #minExcl are mutually exclusive"),

        TestProfile.createError(
            "Numeric Constraints - Max Bounds Collision",
            """
                {
                  :defs {
                    :speed { #maxIncl 100.0 #maxExcl 99.0 } :Float
                  }
                  :type :Seq( :speed )
                  :body [ 100.0 ]
                }
                """,
            "Constraint violation (:speed): #maxIncl and #maxExcl are mutually exclusive"),

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
                    :naturalNumber { #minIncl 0.0 } :Float
                    :strictlyPositive { #minExcl 0.0 } :naturalNumber
                  }
                  :type :Seq( :strictlyPositive )
                  :body [ 0.0 ]
                }
                """,
            "Constraint violation (:strictlyPositive): Value must be strictly greater than 0.0"),

        TestProfile.create(
            "Typed Constants - Inline Substitution References",
            """
                {
                  :defs {
                    #MAX_RETRY :Int 3
                    #BASE_URL  :String "https://stvn.iore"
                  }
                  :type :Seq( :Tuple( :Int :String ) )
                  :body [ ( #MAX_RETRY #BASE_URL ) ]
                }
                """),

        TestProfile.createError(
            "Inherited Constraint Overrides - Dropping Parent Maximum Value Verification",
            """
                {
                  :defs {
                    :ParentFloat { #minIncl 0.0 #maxIncl 20.0 } :Float
                    :ChildFloat { #minExcl 0.0 #maxExcl 5.0 } :ParentFloat
                  }
                  :type :Seq( :Tuple( :ParentFloat :ChildFloat ) )
                  :body [ ( 15.0 5.0 ) ]
                }
                """,
            "Constraint violation (:ChildFloat): Value must be strictly less than 5.0"),

        TestProfile.createError(
            "Numeric Constraints - Float literal on Integer type fails",
            """
                {
                  :defs {
                    :age { #minIncl 0.0 } :Int
                  }
                  :type :Seq( :age )
                  :body [ 1 ]
                }
                """,
            "Constraint violation (:age): minIncl for :Int requires an integer literal"
        ),

        TestProfile.createError(
            "Numeric Constraints - Integer literal on Float type fails",
            """
                {
                  :defs {
                    :weight { #minIncl 2 } :Float
                  }
                  :type :Seq( :weight )
                  :body [ 55.5 ]
                }
                """,
            "Constraint violation (:weight): minIncl for :Float requires a float literal"
        ),

        TestProfile.createError(
            "String Constraints - Numeric bounds on String requires string literal",
            """
                {
                  :defs {
                    :username { #minIncl 5 } :String
                  }
                  :type :Seq( :username )
                  :body [ "admin" ]
                }
                """,
            "requires a string literal"
        ),

        TestProfile.createError(
            "Numeric Constraints - Regex forbidden on Integer",
            """
                {
                  :defs {
                    :postalCode { #regex "^\\d{5}$" } :Int
                  }
                  :type :Seq( :postalCode )
                  :body [ 90210 ]
                }
                """,
            "facet '#regex' is not permitted on :Int"
        ),

        TestProfile.createError(
            "Numeric Constraints - PreserveIndent forbidden on Float",
            """
                {
                  :defs {
                    :matrix { #preserveIndent #TRUE } :Float
                  }
                  :type :Seq( :matrix )
                  :body [ 1.1 ]
                }
                """,
            "facet '#preserveIndent' is not permitted on :Float"
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
            "Metadata facet #preserveIndent requires an explicit boolean value (#TRUE or #FALSE)"
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
            "Metadata facet #preserveIndent requires an explicit boolean value (#TRUE or #FALSE)"
        ),

        TestProfile.createError(
            "Metadata Value Constraints - Invalid boolean for minIncl",
            """
                {
                  :defs {
                    :age { #minIncl #TRUE } :Int
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
            "STVN Syntax Error: unexpected token 'pattern_without_quotes'; expected <value>"
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
                    :InvalidRange { #minIncl 100 #maxExcl 10 } :Int
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
                    :BadType { #minIncl "abc" } :Int
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

  @org.junit.jupiter.api.Test
  public void testProgrammaticCyclicAstHashing() {
      var doc = org.stvnadore.core.StvnCompiler.compile("""
          {
            :defs {
              :MyCircularType :Seq(:MyCircularType)
            }
            :type :MyCircularType
            :body []
          }
          """).document().orElseThrow();
      var resolved = doc.defs().findType(":MyCircularType").orElseThrow();

      var hash1 = org.stvnadore.core.binary.StvnSchemaHasher.hashSchema(resolved);
      var hash2 = org.stvnadore.core.binary.StvnSchemaHasher.hashSchema(resolved);
      org.junit.jupiter.api.Assertions.assertEquals(hash1, hash2);
  }

  @org.junit.jupiter.api.Test
  public void testAnonymousCyclicAstThrowsException() {
      var anonBackEdge = new org.stvnadore.core.ir.StvnSchema.PoisonedSchema(
          Optional.empty(),
          org.stvnadore.core.lowering.StvnPayloadLowerer.RECURSIVE_BACK_EDGE_SENTINEL,
          Optional.empty(),
          org.stvnadore.core.ir.StvnSchema.TraitProfile.ofDefault(true, true)
      );
      var cyclicAnonymousSeq = new org.stvnadore.core.ir.StvnSchema.SeqSchema(
          Optional.empty(),
          java.math.BigInteger.ZERO,
          org.stvnadore.core.StvnVocabulary.DEFAULT_MAX_SIZE,
          anonBackEdge,
          org.stvnadore.core.ir.StvnSchema.TraitProfile.ofDefault(true, true)
      );

      org.junit.jupiter.api.Assertions.assertThrows(
          org.stvnadore.core.validation.MalformedSchemaException.class,
          () -> org.stvnadore.core.binary.StvnSchemaHasher.hashSchema(cyclicAnonymousSeq)
      );
  }

  @org.junit.jupiter.api.Test
  public void testSha256TamperValidation() {
      var ir = org.stvnadore.core.StvnCompiler.compilePayload("""
          {
            :type :Int
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
      var tampered = encoded.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN);
      tampered.put(6, (byte) (tampered.get(6) ^ 0xFF));
      java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
      java.nio.ByteBuffer view = tampered.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN);
      view.position(0);
      view.limit(tampered.limit() - 4);
      crc.update(view);
      tampered.putInt(tampered.limit() - 4, (int) crc.getValue());

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
            :type :Tuple( :Int :String )
            :body ( 0 "" )
          }
          """;
      var ir = org.stvnadore.core.StvnCompiler.compilePayload("""
          {
            :type :Tuple( :Int :String )
            :body ( 42 "ETL data" )
          }
          """).orElseThrow();

      // Encode with SelfDescribingSchema strategy
      var encoder = new org.stvnadore.core.binary.StvnBinaryEncoder(true, new SchemaIdentityStrategy.SelfDescribingSchema(schemaText));
      var encoded = encoder.encode(ir);

      // Decode passing null registry/fallback/universalFallback
      var root = org.stvnadore.core.binary.StvnBinaryDecoder.open(encoded, null, null, null);
      var decodedIr = org.stvnadore.core.binary.StvnBinaryDecoder.unpackIr(root, Optional.empty());

      org.junit.jupiter.api.Assertions.assertEquals(ir, decodedIr);
  }

  @org.junit.jupiter.api.Test
  public void testSelfDescribingSandboxUnionDistinctnessViolation() {
      String invalidSchemaText = """
          {
            :defs {
              :I32 :Int
              :I16 { #size 16 } :Int
            }
            :type :Union( :I32 :I16 :String )
            :body "hello"
          }
          """;
      var ir = org.stvnadore.core.StvnCompiler.compilePayload("""
          {
            :defs {
              :I32 :Int
              :I16 { #size 16 } :Int
            }
            :type :Union( :I32 :I16 :String )
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
      var ir1 = org.stvnadore.core.StvnCompiler.compilePayload(input1).orElseThrow();
      var ir2 = org.stvnadore.core.StvnCompiler.compilePayload(input2).orElseThrow();
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
      var ir1 = org.stvnadore.core.StvnCompiler.compilePayload("{:type :Seq(:Int) :body [1 2 3]}").orElseThrow();
      var ir2 = org.stvnadore.core.StvnCompiler.compilePayload("{:type :Seq(:Int) :body [3 2 1]}").orElseThrow();
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
      var nonSequencedSet = new java.util.HashSet<Boolean>();
      nonSequencedSet.add(true);

      org.junit.jupiter.api.Assertions.assertThrows(
          org.stvnadore.core.validation.MalformedPayloadException.class,
          () -> org.stvnadore.core.mapper.StvnMapper.toValue(nonSequencedSet, Optional.empty())
      );

      var nonSequencedMap = new java.util.HashMap<Boolean, Boolean>();
      nonSequencedMap.put(true, false);

      org.junit.jupiter.api.Assertions.assertThrows(
          org.stvnadore.core.validation.MalformedPayloadException.class,
          () -> org.stvnadore.core.mapper.StvnMapper.toValue(nonSequencedMap, Optional.empty())
      );
  }

  @org.junit.jupiter.api.Test
  public void testBlockStringCollapsingAndRetention() {
      var res1 = org.stvnadore.core.StvnCompiler.compile("""
          {
            :type :String
            :body \"\"\"
              hello
              world
            \"\"\"
          }
          """, "test.stvn", org.stvnadore.core.StvnParserConfig.DEFAULT);
      org.junit.jupiter.api.Assertions.assertTrue(
          res1.diagnostics().stream().anyMatch(d -> d.message().contains("Rule STR-04 violation")),
          "Expected Rule STR-04 violation for unfenced block string"
      );

      var ir2 = org.stvnadore.core.StvnCompiler.compilePayload("""
          {
            :defs {
              :preserved { #preserveIndent #TRUE } :String
            }
            :type :preserved
            :body \"\"\"[TAG]
              helloPreserved
              worldPreserved
            [TAG]\"\"\"
          }
          """).orElseThrow();
      org.junit.jupiter.api.Assertions.assertNotNull(ir2);
      var canon2 = org.stvnadore.core.StvnCompiler.toCanonicalString(ir2);
      org.junit.jupiter.api.Assertions.assertTrue(canon2.contains("\"\"\"[RAW]\n"));
      org.junit.jupiter.api.Assertions.assertTrue(canon2.contains("helloPreserved"));
      org.junit.jupiter.api.Assertions.assertTrue(canon2.contains("worldPreserved"));
      org.junit.jupiter.api.Assertions.assertEquals(
          ir2,
          org.stvnadore.core.StvnCompiler.compilePayload(canon2).orElseThrow()
      );
  }
}
