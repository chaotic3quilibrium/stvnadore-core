package org.stvnadore.core.binary;

import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.antlr.v4.runtime.tree.TerminalNodeImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.parser.StvnParser;
import org.stvnadore.core.validation.MalformedSchemaException;
import org.stvnadore.core.validation.StvnTypeResolver;
import org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification hygiene tests for {@link StvnSchemaHasher}.
 */
class StvnSchemaHasherHygieneTest {

  @Test
  @DisplayName("TC-HASH-01: Compute SHA-256 and UUID for schemas across all 6 base scalar types")
  void testAllBaseScalarsFingerprinting() {
    String[] sources = {
        "{ :type :Boolean :body #TRUE }",
        "{ :type :Int :body 42 }",
        "{ :type :Float :body 3.14 }",
        "{ :type :String :body \"hello\" }",
        "{ :defs { :MyEpoch { #s } :TimeEpoch } :type :MyEpoch :body 1700000000 }",
        "{ :defs { :MyDate { #offset } :DateTime } :type :MyDate :body \"2026-09-26T12:00:00Z\" }"
    };

    for (String source : sources) {
      var ir = StvnCompiler.compile(source).orElseThrow();
      assertNotNull(ir.schema(), "Schema must be non-null for source: " + source);

      byte[] sha256First = StvnSchemaHasher.computeSha256(ir.schema());
      byte[] sha256Second = StvnSchemaHasher.computeSha256(ir.schema());
      assertArrayEquals(sha256First, sha256Second, "SHA-256 must be deterministic for: " + source);

      UUID uuid1 = StvnSchemaHasher.hashSchema(ir.schema());
      UUID uuid2 = StvnSchemaHasher.hashSchema(ir.schema());
      assertEquals(uuid1, uuid2, "UUID must be deterministic for: " + source);
      assertEquals(8, uuid1.version(), "UUID must be RFC Version 8 (Custom)");
      assertEquals(2, uuid1.variant(), "UUID must be RFC Variant 2");
    }
  }

  @Test
  @DisplayName("TC-HASH-02: Assert static constant byte arrays produce bit-for-bit identical hashes to legacy concatenation")
  void testStaticConstantByteEquivalence() throws Exception {
    String[] testSources = {
        "{ :type :Int :body 42 }",
        """
        {
          :defs {
            :BoundedInt { #minIncl 10 #maxExcl 100 } :Int
          }
          :type :BoundedInt
          :body 42
        }
        """,
        """
        {
          :defs {
            :LimitedString { #minSize 1 #maxSize 100 #regex "^[A-Z]+$" } :String
          }
          :type :LimitedString
          :body "TEST"
        }
        """
    };

    for (String source : testSources) {
      var ir = StvnCompiler.compile(source).orElseThrow();
      byte[] actualSha256 = StvnSchemaHasher.computeSha256(ir.schema());
      byte[] legacySha256 = legacyComputeSha256(ir.schema());

      assertArrayEquals(legacySha256, actualSha256,
          "Static prefix byte arrays must produce bit-for-bit identical hashes to legacy concatenation for: " + source);
    }
  }

  @Test
  @DisplayName("TC-HASH-03: Verify anonymous cycle detection and nominal cycle determinism")
  void testCycleDetection() {
    var doc = StvnCompiler.compile("""
        {
          :defs {
            :Node :Option(:Node)
          }
          :type :Node
          :body #None
        }
        """).orElseThrow();

    UUID uuid1 = StvnSchemaHasher.hashSchema(doc.schema());
    UUID uuid2 = StvnSchemaHasher.hashSchema(doc.schema());
    assertEquals(uuid1, uuid2, "Nominal recursive cycle must hash deterministically");

    // Anonymous schema cycle detection
    var parentCtx = new AnonymousCyclicSchemaTypeContext();
    var childCtx = new AnonymousCyclicSchemaTypeContext() {
      @Override
      public StvnParser.SchemaConstructorContext schemaConstructor() {
        parentCtx.resolving = true;
        return super.schemaConstructor();
      }
    };

    var parentColl = new CustomCollectionTypeContext(childCtx);
    var parentCtor = new CustomSchemaConstructorContext(parentColl);
    parentCtx.setCtor(parentCtor);

    var childColl = new CustomCollectionTypeContext(parentCtx);
    var childCtor = new CustomSchemaConstructorContext(childColl);
    childCtx.setCtor(childCtor);

    ResolvedSchema resolved;
    try {
      parentCtx.resolving = false;
      childCtx.resolving = false;
      resolved = StvnTypeResolver.resolvePrimitiveSchema(null, parentCtx, Set.of()).orElseThrow();
    } finally {
      parentCtx.resolving = false;
      childCtx.resolving = false;
    }

    assertThrows(
        MalformedSchemaException.class,
        () -> StvnSchemaHasher.hashSchema(resolved),
        "Anonymous cycle must throw MalformedSchemaException"
    );
  }

  private static byte[] legacyComputeSha256(ResolvedSchema schema) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    legacyDigestSchema(schema, digest, new java.util.HashSet<>());
    return digest.digest();
  }

  private static void legacyDigestSchema(ResolvedSchema schema, MessageDigest digest, Set<String> visited) {
    if (schema == null) return;
    String alias = schema.aliasName().orElse(null);
    if (alias != null) {
      digest.update(("alias:" + alias).getBytes(StandardCharsets.UTF_8));
      if (visited.contains(alias)) return;
      visited = new java.util.HashSet<>(visited);
      visited.add(alias);
    }
    String baseType = StvnTypeResolver.getPrimitiveBaseType(schema.node());
    if (baseType != null) {
      digest.update(baseType.getBytes(StandardCharsets.UTF_8));
      if (":Enum".equals(baseType) && schema.node() != null && schema.node().schemaConstructor() != null && schema.node().schemaConstructor().sumType() != null) {
        var enumDef = schema.node().schemaConstructor().sumType().enumDef();
        if (enumDef != null) {
          for (var kw : enumDef.valueKeyword()) {
            digest.update(("enumVariant:" + kw.getText()).getBytes(StandardCharsets.UTF_8));
          }
        }
      }
    }
    var constraints = schema.constraints();
    constraints.minIncl().ifPresent(val -> digest.update(("minIncl:" + val).getBytes(StandardCharsets.UTF_8)));
    constraints.minExcl().ifPresent(val -> digest.update(("minExcl:" + val).getBytes(StandardCharsets.UTF_8)));
    constraints.maxIncl().ifPresent(val -> digest.update(("maxIncl:" + val).getBytes(StandardCharsets.UTF_8)));
    constraints.maxExcl().ifPresent(val -> digest.update(("maxExcl:" + val).getBytes(StandardCharsets.UTF_8)));
    constraints.dateMinIncl().ifPresent(val -> digest.update(("dateMinIncl:" + val).getBytes(StandardCharsets.UTF_8)));
    constraints.dateMaxExcl().ifPresent(val -> digest.update(("dateMaxExcl:" + val).getBytes(StandardCharsets.UTF_8)));
    constraints.regex().ifPresent(val -> digest.update(("regex:" + val).getBytes(StandardCharsets.UTF_8)));
    digest.update(("preserveIndent:" + constraints.preserveIndent()).getBytes(StandardCharsets.UTF_8));
    constraints.equatable().ifPresent(val -> digest.update(("equatable:" + val).getBytes(StandardCharsets.UTF_8)));
    constraints.comparable().ifPresent(val -> digest.update(("comparable:" + val).getBytes(StandardCharsets.UTF_8)));
    constraints.size().ifPresent(val -> digest.update(("size:" + val).getBytes(StandardCharsets.UTF_8)));
    if (constraints.unsigned()) {
      digest.update("unsigned:true".getBytes(StandardCharsets.UTF_8));
    }
    if (constraints.exact()) {
      digest.update("exact:true".getBytes(StandardCharsets.UTF_8));
    }
    constraints.minSize().ifPresent(val -> digest.update(("minSize:" + val).getBytes(StandardCharsets.UTF_8)));
    constraints.maxSize().ifPresent(val -> digest.update(("maxSize:" + val).getBytes(StandardCharsets.UTF_8)));
    if (constraints.invertible()) {
      digest.update("invertible:true".getBytes(StandardCharsets.UTF_8));
    }
    constraints.scale().ifPresent(val -> digest.update(("scale:" + (val.startsWith("#") ? val.substring(1) : val)).getBytes(StandardCharsets.UTF_8)));
    if (constraints.offset()) {
      digest.update("offset:true".getBytes(StandardCharsets.UTF_8));
    }
    if (constraints.zoned()) {
      digest.update("zoned:true".getBytes(StandardCharsets.UTF_8));
    }
    if (constraints.audited()) {
      digest.update("audited:true".getBytes(StandardCharsets.UTF_8));
    }
    List<ResolvedSchema> children = StvnBinaryDecoder.extractChildSchemas(schema);
    for (ResolvedSchema child : children) {
      legacyDigestSchema(child, digest, visited);
    }
  }

  private static class AnonymousCyclicSchemaTypeContext extends StvnParser.SchemaTypeContext {
    private StvnParser.SchemaConstructorContext ctor;
    public boolean resolving = false;

    public AnonymousCyclicSchemaTypeContext() {
      super(null, 0);
    }

    public void setCtor(StvnParser.SchemaConstructorContext ctor) {
      this.ctor = ctor;
    }

    @Override
    public StvnParser.SchemaConstructorContext schemaConstructor() {
      if (resolving) {
        return null;
      }
      return ctor;
    }

    @Override
    public StvnParser.TypeKeywordContext typeKeyword() {
      return null;
    }
  }

  private static class CustomSchemaConstructorContext extends StvnParser.SchemaConstructorContext {
    private final StvnParser.CollectionTypeContext coll;

    public CustomSchemaConstructorContext(StvnParser.CollectionTypeContext coll) {
      super(null, 0);
      this.coll = coll;
    }

    @Override
    public StvnParser.CollectionTypeContext collectionType() {
      return coll;
    }
  }

  private static class CustomCollectionTypeContext extends StvnParser.CollectionTypeContext {
    private final StvnParser.SchemaTypeContext child;

    public CustomCollectionTypeContext(StvnParser.SchemaTypeContext child) {
      super(null, 0);
      this.child = child;
    }

    @Override
    public List<StvnParser.SchemaTypeContext> schemaType() {
      return List.of(child);
    }

    @Override
    public StvnParser.SchemaTypeContext schemaType(int i) {
      return i == 0 ? child : null;
    }

    @Override
    public TerminalNode COLL_SEQ() {
      return new TerminalNodeImpl(new CommonToken(0, ":Seq"));
    }
  }
}
