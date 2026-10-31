package org.stvnadore.core.mapper;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.annotations.StvnBits;
import org.stvnadore.core.annotations.StvnInt;
import org.stvnadore.core.annotations.StvnString;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.ir.StvnValue.EitherBranch;
import org.stvnadore.core.ir.StvnValue.StvnEither;
import org.stvnadore.core.ir.StvnValue.StvnUnion;
import org.stvnadore.core.validation.MalformedPayloadException;
import org.stvnadore.core.validation.StvnIntegerOverflowException;

class StvnRecordMapperTest {

  public record UserProfile(
      @StvnString(minSize = 1) String username,
      @StvnInt(minIncl = 18, maxIncl = 150) int age,
      Optional<String> bio
  ) {}

  public sealed interface Status permits Active, Suspended {}
  public record Active(String reason) implements Status {}
  public record Suspended(int level) implements Status {}

  public sealed interface Decision permits Left, Right {}
  public record Left(String reason) implements Decision {}
  public record Right(int code) implements Decision {}

  public static class SimplePOJO {
    private final String name;
    public SimplePOJO(String name) { this.name = name; }
    public String getName() { return name; }
  }

  public record ExactLedgerRecord(
      BigInteger transactionId,
      BigDecimal exactAmount
  ) {}

  public record NarrowHardwareRecord(
      byte b,
      short s,
      int i,
      long l
  ) {}

  @Test
  void testBidirectionalCompositeMapping() {
    var profile = new UserProfile("Alice", 30, Optional.of("Hello world"));

    var doc = StvnCompiler.compilePayload("""
        {
          :type :Map( :String :String )
          :body {}
        }
        """).orElseThrow();
    var schema = doc.schema();

    // Object -> STVN IR
    var mappedIr = StvnMapper.toValue(profile, schema).orElseThrow();
    assertNotNull(mappedIr);
    assertTrue(mappedIr instanceof StvnValue.StvnMap);

    // Verify constraints propagation on pure-IR schemas
    var mappedMap = (StvnValue.StvnMap) mappedIr;
    StvnValue usernameVal = null;
    StvnValue ageVal = null;
    for (var entry : mappedMap.entries()) {
      if (entry.key() instanceof StvnValue.StvnString s) {
        if (s.value().equals("username")) {
          usernameVal = entry.value();
        } else if (s.value().equals("age")) {
          ageVal = entry.value();
        }
      }
    }

    assertNotNull(usernameVal);
    assertInstanceOf(StvnSchema.StringSchema.class, usernameVal.schema());
    var usernameSchema = (StvnSchema.StringSchema) usernameVal.schema();
    assertEquals(BigInteger.ONE, usernameSchema.minSize());

    assertNotNull(ageVal);
    assertInstanceOf(StvnSchema.IntSchema.class, ageVal.schema());
    var ageSchema = (StvnSchema.IntSchema) ageVal.schema();
    assertEquals(BigInteger.valueOf(18), ageSchema.explicitMinIncl().orElse(null));
    // Half-open discrete interval [18, 150] normalizes to [18, 151) in STVN 2.0.0
    assertEquals(BigInteger.valueOf(151), ageSchema.explicitMaxExcl().orElse(null));

    // STVN IR -> Object
    var restored = StvnMapper.fromValue(mappedIr, UserProfile.class, schema).orElseThrow();
    assertEquals("Alice", restored.username());
    assertEquals(30, restored.age());
    assertEquals(Optional.of("Hello world"), restored.bio());
  }

  @Test
  void testSealedUnionExtraction() {
    // 1. Union Variant Mapping (Status - N-way Union)
    var status = new Suspended(5);
    var statusDoc = StvnCompiler.compilePayload("""
        {
          :defs {
            :Active :Map( :String :String )
            :Suspended :Map( :String :String )
          }
          :type :Union( :Active :Suspended )
          :body #2 {}
        }
        """).orElseThrow();
    var statusSchema = statusDoc.schema();

    var mappedUnion = StvnMapper.toValue(status, statusSchema).orElseThrow();
    assertTrue(mappedUnion instanceof StvnUnion);

    var unionNode = (StvnUnion) mappedUnion;
    assertEquals(BigInteger.TWO, unionNode.oneBasedBranchIndex());

    var restoredUnion = StvnMapper.fromValue(mappedUnion, Status.class, statusSchema).orElseThrow();
    assertTrue(restoredUnion instanceof Suspended);
    assertEquals(5, ((Suspended) restoredUnion).level());

    // 2. Either Variant Mapping (Decision - 2-way Either)
    var decision = new Right(42);
    var decisionDoc = StvnCompiler.compilePayload("""
        {
          :defs {
            :LeftBranch :Map( :String :String )
            :RightBranch :Map( :String :String )
          }
          :type :Either( :LeftBranch :RightBranch )
          :body #Right {}
        }
        """).orElseThrow();
    var decisionSchema = decisionDoc.schema();

    var mappedEither = StvnMapper.toValue(decision, decisionSchema).orElseThrow();
    assertTrue(mappedEither instanceof StvnEither);

    var eitherNode = (StvnEither) mappedEither;
    assertEquals(EitherBranch.RIGHT, eitherNode.branch());

    var restoredEither = StvnMapper.fromValue(mappedEither, Decision.class, decisionSchema).orElseThrow();
    assertTrue(restoredEither instanceof Right);
    assertEquals(42, ((Right) restoredEither).code());
  }

  @Test
  void testPOJOBootstrapRejection() {
    var pojo = new SimplePOJO("not-a-record");
    var doc = StvnCompiler.compilePayload("""
        {
          :type :Map( :String :String )
          :body {}
        }
        """).orElseThrow();
    var schema = doc.schema();

    assertThrows(IllegalArgumentException.class, () -> {
      StvnMapper.toValue(pojo, schema);
    });

    var dummyValue = new StvnValue.StvnString(schema, "val");
    assertThrows(IllegalArgumentException.class, () -> {
      StvnMapper.fromValue(dummyValue, SimplePOJO.class, schema);
    });
  }

  @Test
  void testNullOptionalFailureInduction() {
    var doc = StvnCompiler.compilePayload("""
        {
          :type :Map( :String :String )
          :body {}
        }
        """).orElseThrow();
    var schema = doc.schema();

    assertThrows(MalformedPayloadException.class, () -> {
      var badProfile = new UserProfile("Bob", 25, null);
      StvnMapper.toValue(badProfile, schema);
    });
  }

  @Test
  void testConstraintValidationViolations() {
    var doc = StvnCompiler.compilePayload("""
        {
          :type :Map( :String :String )
          :body {}
        }
        """).orElseThrow();
    var schema = doc.schema();

    // Username cannot be empty (minSize = 1)
    assertThrows(MalformedPayloadException.class, () -> {
      var badProfile = new UserProfile("", 25, Optional.empty());
      StvnMapper.toValue(badProfile, schema);
    });

    // Age too low
    assertThrows(MalformedPayloadException.class, () -> {
      var badProfile = new UserProfile("Charlie", 15, Optional.empty());
      StvnMapper.toValue(badProfile, schema);
    });
  }

  public record BitsRecord(
      @StvnBits(value = 7, unsigned = true) BigInteger u7,
      @StvnBits(value = 128, unsigned = false) BigInteger i128
  ) {}

  public record UnannotatedBigIntRecord(
      int id,
      BigInteger val
  ) {}

  @Test
  void testStvnBitsValidationAndInference() {
    var record = new BitsRecord(BigInteger.valueOf(127), BigInteger.TEN);
    var doc = StvnCompiler.compilePayload(
        "{ :defs { :U7 { #unsigned #size 7 } :Int :I128 { #size 128 } :Int } :type :Tuple( :U7 :I128 ) :body (0 0) }"
    ).orElseThrow();
    var schema = doc.schema();

    var mapped = StvnMapper.toValue(record, schema).orElseThrow();
    assertNotNull(mapped);

    var restored = StvnMapper.fromValue(mapped, BitsRecord.class, schema).orElseThrow();
    assertEquals(BigInteger.valueOf(127), restored.u7());
    assertEquals(BigInteger.TEN, restored.i128());

    var recordOverrun = new BitsRecord(BigInteger.valueOf(128), BigInteger.TEN);
    assertThrows(StvnIntegerOverflowException.class, () -> {
      StvnMapper.toValue(recordOverrun, schema);
    });

    var docUnannotated = StvnCompiler.compilePayload("{ :type :Tuple( :Int ) :body (0) }").orElseThrow();
    var schemaUnannotated = docUnannotated.schema();
    var unannotatedRecord = new UnannotatedBigIntRecord(42, BigInteger.TEN);
    assertThrows(MalformedPayloadException.class, () -> {
      StvnMapper.toValue(unannotatedRecord, schemaUnannotated);
    });
  }

  @Test
  void testNativeBigIntegerAndBigDecimalRecordMapping() {
    var doc = StvnCompiler.compilePayload("""
        {
          :defs {
            :TxId { #unsigned #size 128 } :Int
            :ExactMoney { #exact } :Float
          }
          :type :Tuple( :TxId :ExactMoney )
          :body ( 0 0.0 )
        }
        """).orElseThrow();
    var schema = doc.schema();

    BigInteger largeTxId = new BigInteger("340282366920938463463374607431768211455");
    BigDecimal preciseMoney = new BigDecimal("98765432109876543210.12345678901234567890");
    var ledger = new ExactLedgerRecord(largeTxId, preciseMoney);

    StvnValue mapped = StvnMapper.toValue(ledger, schema).orElseThrow();
    ExactLedgerRecord restored = StvnMapper.fromValue(mapped, ExactLedgerRecord.class, schema).orElseThrow();

    assertEquals(largeTxId, restored.transactionId());
    assertEquals(preciseMoney, restored.exactAmount());
  }

  @Test
  void testExactNarrowingOverflowChecksOnHardwarePrimitives() {
    String schemaHeader = """
        :defs {
          :WideInt { #size 128 } :Int
          :WideQuad :Tuple( :WideInt :WideInt :WideInt :WideInt )
        }
        :type :WideQuad
        """;

    // Valid exact boundaries
    var validIr = StvnCompiler.compilePayload("{" + schemaHeader + ":body ( 127 32767 2147483647 9223372036854775807 ) }")
        .orElseThrow();
    NarrowHardwareRecord validRec = StvnMapper.fromValue(validIr, NarrowHardwareRecord.class, validIr.schema())
        .orElseThrow();
    assertEquals((byte) 127, validRec.b());
    assertEquals((short) 32767, validRec.s());
    assertEquals(Integer.MAX_VALUE, validRec.i());
    assertEquals(Long.MAX_VALUE, validRec.l());

    // Overflow byteValueExact() (128 > Byte.MAX_VALUE)
    var overflowByteIr = StvnCompiler.compilePayload("{" + schemaHeader + ":body ( 128 0 0 0 ) }").orElseThrow();
    assertThrows(StvnIntegerOverflowException.class,
        () -> StvnMapper.fromValue(overflowByteIr, NarrowHardwareRecord.class, overflowByteIr.schema()));

    // Overflow shortValueExact() (32768 > Short.MAX_VALUE)
    var overflowShortIr = StvnCompiler.compilePayload("{" + schemaHeader + ":body ( 0 32768 0 0 ) }").orElseThrow();
    assertThrows(StvnIntegerOverflowException.class,
        () -> StvnMapper.fromValue(overflowShortIr, NarrowHardwareRecord.class, overflowShortIr.schema()));

    // Overflow intValueExact() (2147483648 > Integer.MAX_VALUE)
    var overflowIntIr = StvnCompiler.compilePayload("{" + schemaHeader + ":body ( 0 0 2147483648 0 ) }").orElseThrow();
    assertThrows(StvnIntegerOverflowException.class,
        () -> StvnMapper.fromValue(overflowIntIr, NarrowHardwareRecord.class, overflowIntIr.schema()));

    // Overflow longValueExact() (9223372036854775808 > Long.MAX_VALUE)
    var overflowLongIr = StvnCompiler.compilePayload("{" + schemaHeader + ":body ( 0 0 0 9223372036854775808 ) }")
        .orElseThrow();
    assertThrows(StvnIntegerOverflowException.class,
        () -> StvnMapper.fromValue(overflowLongIr, NarrowHardwareRecord.class, overflowLongIr.schema()));
  }
}
