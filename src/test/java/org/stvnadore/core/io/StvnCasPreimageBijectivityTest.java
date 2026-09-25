package org.stvnadore.core.io;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ir.StvnValue;

import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test suite for SHA-256 Content-Addressable Storage (CAS) Preimage Bijectivity
 * and 7-Tier Canonical Serialization Invariant Parity ($1:1$ Law).
 */
@NullMarked
public class StvnCasPreimageBijectivityTest {

  @Test
  @DisplayName("CAS Preimage Bijectivity: Bit-width variation (#size 16 vs #size 32 vs bare :Int)")
  void testBitWidthCasPreimageBijectivity() {
    String docSize16 = """
        {
          :defs {
            :TargetInt { #size 16 } :Int
          }
          :type :TargetInt
          :body 42
        }
        """;

    String docSize32 = """
        {
          :defs {
            :TargetInt { #size 32 } :Int
          }
          :type :TargetInt
          :body 42
        }
        """;

    String docBareInt = """
        {
          :type :Int
          :body 42
        }
        """;

    var ast16 = StvnCompiler.compile(docSize16).orElseThrow();
    var ast32 = StvnCompiler.compile(docSize32).orElseThrow();
    var astBare = StvnCompiler.compile(docBareInt).orElseThrow();

    String canon16 = StvnCompiler.toCanonicalString(ast16);
    String canon32 = StvnCompiler.toCanonicalString(ast32);
    String canonBare = StvnCompiler.toCanonicalString(astBare);

    // Verify canonical strings are distinct
    assertNotEquals(canon16, canon32, "Canonical strings for #size 16 and #size 32 must be distinct");
    assertNotEquals(canon16, canonBare, "Canonical strings for #size 16 and bare :Int must be distinct");
    assertNotEquals(canon32, canonBare, "Canonical strings for #size 32 and bare :Int must be distinct");

    assertTrue(canon16.contains("#size 16"), "Canonical string must retain #size 16 facet");
    assertTrue(canon32.contains("#size 32"), "Canonical string must retain #size 32 facet");

    // Verify SHA-256 CAS fingerprints are distinct (1:1 Nominal Bijectivity Invariant)
    byte[] fp16 = StvnCompiler.computeCasFingerprint(ast16);
    byte[] fp32 = StvnCompiler.computeCasFingerprint(ast32);
    byte[] fpBare = StvnCompiler.computeCasFingerprint(astBare);

    assertFalse(Arrays.equals(fp16, fp32), "CAS fingerprints must differ for #size 16 vs #size 32");
    assertFalse(Arrays.equals(fp16, fpBare), "CAS fingerprints must differ for #size 16 vs bare :Int");
    assertFalse(Arrays.equals(fp32, fpBare), "CAS fingerprints must differ for #size 32 vs bare :Int");
  }

  @Test
  @DisplayName("CAS Preimage Bijectivity: Signedness variation (#unsigned vs signed :Int)")
  void testSignednessCasPreimageBijectivity() {
    String docUnsigned = """
        {
          :defs {
            :NumType { #unsigned #size 32 } :Int
          }
          :type :NumType
          :body 42
        }
        """;

    String docSigned = """
        {
          :defs {
            :NumType { #size 32 } :Int
          }
          :type :NumType
          :body 42
        }
        """;

    var astUnsigned = StvnCompiler.compile(docUnsigned).orElseThrow();
    var astSigned = StvnCompiler.compile(docSigned).orElseThrow();

    String canonUnsigned = StvnCompiler.toCanonicalString(astUnsigned);
    String canonSigned = StvnCompiler.toCanonicalString(astSigned);

    assertNotEquals(canonUnsigned, canonSigned, "Canonical strings must differ between #unsigned and signed :Int");
    assertTrue(canonUnsigned.contains("#unsigned"), "Canonical string must retain bare #unsigned flag");
    assertFalse(canonUnsigned.contains("#unsigned #TRUE"), "Canonical string must not append #TRUE to bare flag");

    byte[] fpUnsigned = StvnCompiler.computeCasFingerprint(astUnsigned);
    byte[] fpSigned = StvnCompiler.computeCasFingerprint(astSigned);

    assertFalse(Arrays.equals(fpUnsigned, fpSigned), "CAS fingerprints must differ between #unsigned and signed :Int");
  }

  @Test
  @DisplayName("CAS Preimage Bijectivity: Temporal scale variation (#s vs #ms vs #us vs #ns)")
  void testTemporalScaleCasPreimageBijectivity() {
    String docS = """
        {
          :defs {
            :EpochType { #s } :TimeEpoch
          }
          :type :EpochType
          :body 1000
        }
        """;

    String docMs = """
        {
          :defs {
            :EpochType { #ms } :TimeEpoch
          }
          :type :EpochType
          :body 1000
        }
        """;

    String docUs = """
        {
          :defs {
            :EpochType { #us } :TimeEpoch
          }
          :type :EpochType
          :body 1000
        }
        """;

    String docNs = """
        {
          :defs {
            :EpochType { #ns } :TimeEpoch
          }
          :type :EpochType
          :body 1000
        }
        """;

    var astS = StvnCompiler.compile(docS).orElseThrow();
    var astMs = StvnCompiler.compile(docMs).orElseThrow();
    var astUs = StvnCompiler.compile(docUs).orElseThrow();
    var astNs = StvnCompiler.compile(docNs).orElseThrow();

    String canonS = StvnCompiler.toCanonicalString(astS);
    String canonMs = StvnCompiler.toCanonicalString(astMs);
    String canonUs = StvnCompiler.toCanonicalString(astUs);
    String canonNs = StvnCompiler.toCanonicalString(astNs);

    assertNotEquals(canonS, canonMs);
    assertNotEquals(canonMs, canonUs);
    assertNotEquals(canonUs, canonNs);
    assertNotEquals(canonS, canonNs);

    assertTrue(canonS.contains("#s"));
    assertTrue(canonMs.contains("#ms"));
    assertTrue(canonUs.contains("#us"));
    assertTrue(canonNs.contains("#ns"));

    byte[] fpS = StvnCompiler.computeCasFingerprint(astS);
    byte[] fpMs = StvnCompiler.computeCasFingerprint(astMs);
    byte[] fpUs = StvnCompiler.computeCasFingerprint(astUs);
    byte[] fpNs = StvnCompiler.computeCasFingerprint(astNs);

    assertFalse(Arrays.equals(fpS, fpMs));
    assertFalse(Arrays.equals(fpMs, fpUs));
    assertFalse(Arrays.equals(fpUs, fpNs));
    assertFalse(Arrays.equals(fpS, fpNs));
  }

  @Test
  @DisplayName("CAS Preimage Bijectivity: Temporal mode variation (#offset vs #zoned vs #audited)")
  void testTemporalModeCasPreimageBijectivity() {
    String docOffset = """
        {
          :defs {
            :MyDt { #offset } :DateTime
          }
          :type :MyDt
          :body "2026-09-24T12:00:00+00:00"
        }
        """;

    String docZoned = """
        {
          :defs {
            :MyDt { #zoned } :DateTime
          }
          :type :MyDt
          :body "2026-09-24T12:00:00[UTC]"
        }
        """;

    String docAudited = """
        {
          :defs {
            :MyDt { #audited } :DateTime
          }
          :type :MyDt
          :body "2026-09-24T12:00:00+00:00[UTC]"
        }
        """;

    var astOffset = StvnCompiler.compile(docOffset).orElseThrow();
    var astZoned = StvnCompiler.compile(docZoned).orElseThrow();
    var astAudited = StvnCompiler.compile(docAudited).orElseThrow();

    String canonOffset = StvnCompiler.toCanonicalString(astOffset);
    String canonZoned = StvnCompiler.toCanonicalString(astZoned);
    String canonAudited = StvnCompiler.toCanonicalString(astAudited);

    assertNotEquals(canonOffset, canonZoned);
    assertNotEquals(canonOffset, canonAudited);
    assertNotEquals(canonZoned, canonAudited);

    assertTrue(canonOffset.contains("#offset"));
    assertTrue(canonZoned.contains("#zoned"));
    assertTrue(canonAudited.contains("#audited"));

    byte[] fpOffset = StvnCompiler.computeCasFingerprint(astOffset);
    byte[] fpZoned = StvnCompiler.computeCasFingerprint(astZoned);
    byte[] fpAudited = StvnCompiler.computeCasFingerprint(astAudited);

    assertFalse(Arrays.equals(fpOffset, fpZoned));
    assertFalse(Arrays.equals(fpOffset, fpAudited));
    assertFalse(Arrays.equals(fpZoned, fpAudited));
  }

  @Test
  @DisplayName("CAS Preimage Bijectivity: Cardinality variation (#minSize 1 #maxSize 20 vs unbounded :String)")
  void testCardinalityCasPreimageBijectivity() {
    String docBounded = """
        {
          :defs {
            :StrType { #minSize 1 #maxSize 20 } :String
          }
          :type :StrType
          :body "hello"
        }
        """;

    String docUnbounded = """
        {
          :type :String
          :body "hello"
        }
        """;

    var astBounded = StvnCompiler.compile(docBounded).orElseThrow();
    var astUnbounded = StvnCompiler.compile(docUnbounded).orElseThrow();

    String canonBounded = StvnCompiler.toCanonicalString(astBounded);
    String canonUnbounded = StvnCompiler.toCanonicalString(astUnbounded);

    assertNotEquals(canonBounded, canonUnbounded);
    assertTrue(canonBounded.contains("#minSize 1"));
    assertTrue(canonBounded.contains("#maxSize 20"));

    byte[] fpBounded = StvnCompiler.computeCasFingerprint(astBounded);
    byte[] fpUnbounded = StvnCompiler.computeCasFingerprint(astUnbounded);

    assertFalse(Arrays.equals(fpBounded, fpUnbounded));
  }

  @Test
  @DisplayName("CAS Preimage Bijectivity: Floating-point mode variation (#exact vs continuous :Float)")
  void testFloatingPointModeCasPreimageBijectivity() {
    String docExact = """
        {
          :defs {
            :FloatType { #exact } :Float
          }
          :type :FloatType
          :body 3.14159
        }
        """;

    String docContinuous = """
        {
          :type :Float
          :body 3.14159
        }
        """;

    var astExact = StvnCompiler.compile(docExact).orElseThrow();
    var astContinuous = StvnCompiler.compile(docContinuous).orElseThrow();

    String canonExact = StvnCompiler.toCanonicalString(astExact);
    String canonContinuous = StvnCompiler.toCanonicalString(astContinuous);

    assertNotEquals(canonExact, canonContinuous);
    assertTrue(canonExact.contains("#exact"));

    byte[] fpExact = StvnCompiler.computeCasFingerprint(astExact);
    byte[] fpContinuous = StvnCompiler.computeCasFingerprint(astContinuous);

    assertFalse(Arrays.equals(fpExact, fpContinuous));
  }

  @Test
  @DisplayName("Canonical Round-Trip Recompilation Idempotency: 64-bit unsigned integer with 10^19 payload")
  void testRoundTripRecompilationIdempotencyU64LargePayload() {
    String source = """
        {
          :defs {
            :U64 { #unsigned #size 64 } :Int
          }
          :type :U64
          :body 10000000000000000000
        }
        """;

    // 1. Initial compilation
    StvnCompilationResult<StvnValue> r1 = StvnCompiler.compileToResult(source);
    assertTrue(r1.isSuccess(), "Initial compilation of 64-bit unsigned integer with 10^19 payload must succeed");
    assertFalse(r1.hasErrors());

    // 2. Canonical serialization
    String canonical1 = StvnCompiler.toCanonicalString(r1.orElseThrow());
    assertTrue(canonical1.contains("#unsigned"), "Canonical output must serialize #unsigned");
    assertTrue(canonical1.contains("#size 64"), "Canonical output must serialize #size 64");
    assertTrue(canonical1.contains("10000000000000000000"), "Canonical output must retain full large integer payload");

    // 3. Recompilation of canonical output
    StvnCompilationResult<StvnValue> r2 = StvnCompiler.compileToResult(canonical1);
    assertTrue(r2.isSuccess(), "Recompilation of canonical output must succeed cleanly without CAPACITY_OVERFLOW");
    assertFalse(r2.hasErrors(), "Recompilation must produce 0 errors");

    // 4. Idempotency: C(P(C(P(T)))) == C(P(T))
    String canonical2 = StvnCompiler.toCanonicalString(r2.orElseThrow());
    assertEquals(canonical1, canonical2, "Canonical round-trip recompilation idempotency invariant violated");

    // 5. CAS Fingerprint stability
    byte[] fp1 = StvnCompiler.computeCasFingerprint(r1.orElseThrow());
    byte[] fp2 = StvnCompiler.computeCasFingerprint(r2.orElseThrow());
    assertArrayEquals(fp1, fp2, "CAS fingerprints across recompilation cycles must be bit-for-byte identical");
  }

  @Test
  @DisplayName("Canonical Round-Trip Recompilation Idempotency: Temporal types (epoch and datetimes)")
  void testRoundTripRecompilationIdempotencyTemporalTypes() {
    String sourceEpoch = """
        {
          :defs {
            :EpochMs { #ms } :TimeEpoch
          }
          :type :EpochMs
          :body 1727220000000
        }
        """;

    StvnCompilationResult<StvnValue> resEpoch = StvnCompiler.compileToResult(sourceEpoch);
    assertTrue(resEpoch.isSuccess());
    String canonEpoch1 = StvnCompiler.toCanonicalString(resEpoch.orElseThrow());
    StvnCompilationResult<StvnValue> resEpochRecompiled = StvnCompiler.compileToResult(canonEpoch1);
    assertTrue(resEpochRecompiled.isSuccess());
    String canonEpoch2 = StvnCompiler.toCanonicalString(resEpochRecompiled.orElseThrow());
    assertEquals(canonEpoch1, canonEpoch2);
    assertArrayEquals(
        StvnCompiler.computeCasFingerprint(resEpoch.orElseThrow()),
        StvnCompiler.computeCasFingerprint(resEpochRecompiled.orElseThrow())
    );

    String sourceDtOffset = """
        {
          :defs {
            :DtOffset { #offset } :DateTime
          }
          :type :DtOffset
          :body "2026-09-24T12:00:00+00:00"
        }
        """;

    StvnCompilationResult<StvnValue> resDtOffset = StvnCompiler.compileToResult(sourceDtOffset);
    assertTrue(resDtOffset.isSuccess());
    String canonDtOffset1 = StvnCompiler.toCanonicalString(resDtOffset.orElseThrow());
    StvnCompilationResult<StvnValue> resDtOffsetRecompiled = StvnCompiler.compileToResult(canonDtOffset1);
    assertTrue(resDtOffsetRecompiled.isSuccess());
    String canonDtOffset2 = StvnCompiler.toCanonicalString(resDtOffsetRecompiled.orElseThrow());
    assertEquals(canonDtOffset1, canonDtOffset2);
    assertArrayEquals(
        StvnCompiler.computeCasFingerprint(resDtOffset.orElseThrow()),
        StvnCompiler.computeCasFingerprint(resDtOffsetRecompiled.orElseThrow())
    );
  }

  @Test
  @DisplayName("Deterministic Definition Sorting: Independent DAG components sort lexicographically")
  void testDeterministicIndependentDefinitionSorting() {
    String source = """
        {
          :defs {
            :Zebra :Int
            :Alpha :Int
            :Mango :Int
          }
          :type :Tuple( :Zebra :Alpha :Mango )
          :body ( 1 2 3 )
        }
        """;

    var ast = StvnCompiler.compile(source).orElseThrow();
    String canonical = StvnCompiler.toCanonicalString(ast);

    // Independent definitions should sort lexicographically: :Alpha < :Mango < :Zebra
    int idxAlpha = canonical.indexOf(":Alpha");
    int idxMango = canonical.indexOf(":Mango");
    int idxZebra = canonical.indexOf(":Zebra");

    assertTrue(idxAlpha >= 0 && idxMango >= 0 && idxZebra >= 0);
    assertTrue(idxAlpha < idxMango, ":Alpha must precede :Mango in canonical definitions");
    assertTrue(idxMango < idxZebra, ":Mango must precede :Zebra in canonical definitions");
  }
}
