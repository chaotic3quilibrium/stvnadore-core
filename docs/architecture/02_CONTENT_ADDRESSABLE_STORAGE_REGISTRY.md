# STVN Architectural Specification 02: Content-Addressable Storage (CAS) & Schema Registry

**Document ID**: `STVN-SPEC-02`  
**Status**: Canonical Specification  
**Version**: 1.0.2
**Compliance**: Mandatory across all STVN repository servers, storage backends, and deployment topologies.

---

## 1. 2/62 Physical Storage Topology

To eliminate filesystem inode saturation and directory search degradation, physical Content-Addressable Storage (CAS) files are stored using a 2-character directory prefix derived from the 64-character lowercase hexadecimal SHA-256 digest:

$$\text{File Path} = \langle\text{CAS_ROOT}\rangle \,/\, \text{hash}[0..1] \,/\, \text{hash}[2..63] \text{".stvn_cas"}$$

### Directory Layout
```
<CAS_ROOT>/
├── 0a/
│   └── 1b2c3d4e5f...62chars.stvn_cas
├── e3/
│   └── b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855.stvn_cas
└── .quarantine/
    └── b0c44298fc...62chars.stvn_cas.1772398400000.HASH_MISMATCH.quarantine
```

---

## 2. CAS Envelope Format & Storage Immutability

Once written, a physical CAS file is immutable. The physical `.stvn_cas` file encloses the canonical source text in a 3-element STVN tuple:

```stvn
(:Tuple
  "UserProfile"
  "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
  "{\n  :defs {\n    :UserProfile :Tuple( :Int64 :StringNonEmpty )\n  }\n}"
)
```

1. **Element 0 (`:String`)**: Nominal schema identifier (e.g. `"UserProfile"`).
2. **Element 1 (`:String`)**: 64-character lowercase hexadecimal SHA-256 content digest.
3. **Element 2 (`:String`)**: Canonical STVN source text.

---

## 3. Relational Schema Catalog (PostgreSQL & H2 DDL)

Relational indexing maps nominal schema names and flattened shape signatures to physical CAS hashes:

```sql
CREATE TABLE IF NOT EXISTS version_catalog (
    schema_name      VARCHAR(255) NOT NULL,
    shape_signature  TEXT         NOT NULL,
    cas_hash         VARCHAR(64)  NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_version_catalog PRIMARY KEY (schema_name),
    CONSTRAINT uq_version_catalog_cas_hash UNIQUE (cas_hash)
);

CREATE INDEX IF NOT EXISTS idx_version_catalog_shape ON version_catalog (shape_signature);

CREATE TABLE IF NOT EXISTS schema_source_audit (
    audit_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    schema_name      VARCHAR(255) NOT NULL,
    cas_hash         VARCHAR(64)  NOT NULL,
    source_text      TEXT         NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_schema_source_audit_schema FOREIGN KEY (schema_name)
        REFERENCES version_catalog (schema_name) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_schema_source_audit_hash ON schema_source_audit (cas_hash);
```

---

## 4. REST API & Protocol Boundaries

### Media Type Standard
All schema payload bodies must use the MIME Content-Type: `application/stvn`.

### Endpoint Contract
1. **`POST /api/v1/schemas/{name}`**:
   * Publishes and indexes a new canonical STVN schema.
   * `201 Created`: Schema successfully published and indexed.
   * `200 OK`: Idempotent publication (exact schema name and hash already exist).
   * `202 Accepted`: CAS write succeeded; relational indexing deferred to background sweeper.
   * `409 Conflict`: Schema name exists with a different hash. In-place schema mutations are prohibited.
   * `415 Unsupported Media Type`: Request `Content-Type` is not `application/stvn`.
   * `422 Unprocessable Entity`: STVN compilation diagnostics reported syntax or semantic errors.

2. **`GET /api/v1/schemas/{name}/shapes/{signature}`**:
   * Queries metadata for a schema matching a nominal name and structural shape signature.
   * `200 OK`: Match found. Returns JSON metadata.
   * `404 Not Found`: No matching record.

3. **`GET /api/v1/schemas/cas/{hash}`**:
   * Fetches raw immutable STVN schema source directly by 64-character SHA-256 CAS hash.
   * `200 OK`: Returns schema source with `Content-Type: application/stvn`.
   * `400 Bad Request`: Hash parameter is not a 64-character hex string.
   * `404 Not Found`: Hash does not exist in CAS storage.

---

## 5. Background Relational Projection Sweeper

The `RelationalProjectionSweeper` executes on a background Virtual Thread every 60 seconds:
1. Iterates all physical `.stvn_cas` files under `<CAS_ROOT>`.
2. Checks if `cas_hash` exists in `version_catalog`.
3. If absent, decodes the tuple envelope, parses inner source text with `StvnCompiler.analyze()`, and recomputes the SHA-256 digest using `StvnSchemaHasher.computeSha256(schema)`.
4. If computed hash $\ne$ filename hash, the file is atomically relocated to `.quarantine/` with a timestamped reason suffix:
   `<CAS_ROOT>/.quarantine/<hash_suffix>.stvn_cas.<timestamp>.<REASON>.quarantine`
5. If valid, inserts projection into `version_catalog` and `schema_source_audit`.
