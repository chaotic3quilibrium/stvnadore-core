package org.stvnadore.core.mapper;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.annotations.StvnInt;
import org.stvnadore.core.annotations.StvnString;
import org.stvnadore.core.annotations.StvnBits;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.ir.StvnValue.StvnEither;
import org.stvnadore.core.ir.StvnValue.StvnUnion;
import org.stvnadore.core.validation.MalformedPayloadException;

class StvnRecordMapperTest {

  public record UserProfile(
      @StvnString(nonEmpty = true) String username,
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

  @Test
  void testBidirectionalCompositeMapping() {
    var profile = new UserProfile("Alice", 30, Optional.of("Hello world"));

    var doc = StvnCompiler.compile("""
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

    // Verify constraints propagation
    var mappedMap = (StvnValue.StvnMap) mappedIr;
    StvnValue usernameVal = null;
    StvnValue ageVal = null;
    for (var entry : mappedMap.entries().entrySet()) {
      if (entry.getKey() instanceof StvnValue.StvnString s) {
        if (s.value().equals("username")) {
          usernameVal = entry.getValue();
        } else if (s.value().equals("age")) {
          ageVal = entry.getValue();
        }
      }
    }

    assertNotNull(usernameVal);
    assertNotNull(usernameVal.schema());
    assertEquals(java.math.BigDecimal.ONE, usernameVal.schema().constraints().minIncl().orElse(null));

    assertNotNull(ageVal);
    assertNotNull(ageVal.schema());
    assertEquals(java.math.BigDecimal.valueOf(18), ageVal.schema().constraints().minIncl().orElse(null));
    assertEquals(java.math.BigDecimal.valueOf(150), ageVal.schema().constraints().maxIncl().orElse(null));

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
    var statusDoc = StvnCompiler.compile("""
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
    assertEquals(1, unionNode.tagIndex());

    var restoredUnion = StvnMapper.fromValue(mappedUnion, Status.class, statusSchema).orElseThrow();
    assertTrue(restoredUnion instanceof Suspended);
    assertEquals(5, ((Suspended) restoredUnion).level());

    // 2. Either Variant Mapping (Decision - 2-way Either)
    var decision = new Right(42);
    var decisionDoc = StvnCompiler.compile("""
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
    assertTrue(eitherNode.isRight());

    var restoredEither = StvnMapper.fromValue(mappedEither, Decision.class, decisionSchema).orElseThrow();
    assertTrue(restoredEither instanceof Right);
    assertEquals(42, ((Right) restoredEither).code());
  }

  @Test
  void testPOJOBootstrapRejection() {
    var pojo = new SimplePOJO("not-a-record");
    var doc = StvnCompiler.compile("""
        {
          :type :Map( :String :String )
          :body {}
        }
        """).orElseThrow();
    var schema = doc.schema();

    assertThrows(IllegalArgumentException.class, () -> {
      StvnMapper.toValue(pojo, schema);
    });

    var dummyValue = new StvnValue.StvnString(schema, "val", StvnValue.StringStyle.SIMPLE, Optional.empty(), new StvnValue.StringTrait(0, false));
    assertThrows(IllegalArgumentException.class, () -> {
      StvnMapper.fromValue(dummyValue, SimplePOJO.class, schema);
    });
  }

  @Test
  void testNullOptionalFailureInduction() {
    var doc = StvnCompiler.compile("""
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
    var doc = StvnCompiler.compile("""
        {
          :type :Map( :String :String )
          :body {}
        }
        """).orElseThrow();
    var schema = doc.schema();

    // Username cannot be empty
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
      @StvnBits(value = 7, unsigned = true) java.math.BigInteger u7,
      @StvnBits(value = 128, unsigned = false) java.math.BigInteger i128
  ) {}

  public record UnannotatedBigIntRecord(
      int id,
      java.math.BigInteger val
  ) {}

  @Test
  void testStvnBitsValidationAndInference() {
    var record = new BitsRecord(java.math.BigInteger.valueOf(127), java.math.BigInteger.TEN);
    var doc = StvnCompiler.compile("{ :type :Tuple( :Uint7 :Int128 ) :body [0 0] }").orElseThrow();
    var schema = doc.schema();

    var mapped = StvnMapper.toValue(record, schema).orElseThrow();
    assertNotNull(mapped);

    var restored = StvnMapper.fromValue(mapped, BitsRecord.class, schema).orElseThrow();
    assertEquals(java.math.BigInteger.valueOf(127), restored.u7());
    assertEquals(java.math.BigInteger.TEN, restored.i128());

    var recordOverrun = new BitsRecord(java.math.BigInteger.valueOf(128), java.math.BigInteger.TEN);
    assertThrows(org.stvnadore.core.validation.StvnIntegerOverflowException.class, () -> {
      StvnMapper.toValue(recordOverrun, schema);
    });

    var docUnannotated = StvnCompiler.compile("{ :type :Tuple( :Int32 ) :body [0] }").orElseThrow();
    var schemaUnannotated = docUnannotated.schema();
    var unannotatedRecord = new UnannotatedBigIntRecord(42, java.math.BigInteger.TEN);
    assertThrows(MalformedPayloadException.class, () -> {
      StvnMapper.toValue(unannotatedRecord, schemaUnannotated);
    });
  }
}

