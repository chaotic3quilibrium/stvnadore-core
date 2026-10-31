package org.stvnadore.core.binary;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Strategy contract defining how schema identities are registered, cached,
 * and resolved across local or remote environments.
 * <p>
 * Fingerprint signatures are computed deterministically using {@link StvnSchemaHasher}.
 *
 * @since 1.0.0
 */
@NullMarked
public sealed interface SchemaIdentityStrategy {

  /**
   * Returns the 4-bit numeric code corresponding to this schema identity strategy
   * for packing into the lower nibble of Header Byte 4.
   *
   * @return the 4-bit lower nibble identity code (range {@code 0x0} to {@code 0x8})
   */
  default int code() {
    return switch (this) {
      case UniversalDefault ignored -> 0x00;
      case UuidV8Hash ignored -> 0x01;
      case Sha256Hash ignored -> 0x02;
      case AsciiStringKey ignored -> 0x03;
      case UnicodeStringKey ignored -> 0x04;
      case UniversalVersion ignored -> 0x05;
      case ExplicitUuid ignored -> 0x06;
      case ExplicitSha256 ignored -> 0x07;
      case SelfDescribingSchema ignored -> 0x08;
    };
  }

  /**
   * The default strategy representing universal schema layout settings.
   */
  record UniversalDefault() implements SchemaIdentityStrategy {
  }

  /**
   * Identifies schemas via their RFC-compliant Version 8 UUID hashing fingerprint.
   */
  record UuidV8Hash() implements SchemaIdentityStrategy {
  }

  /**
   * Identifies schemas using their raw 32-byte SHA-256 fingerprint hash directly.
   */
  record Sha256Hash() implements SchemaIdentityStrategy {
  }

  /**
   * Identifies schemas via a registered ASCII-only repository string key.
   *
   * @param repositoryKey the ASCII key under which the schema is cataloged
   */
  record AsciiStringKey(String repositoryKey) implements SchemaIdentityStrategy {
    /**
     * Canonical constructor verifying non-null repository key.
     *
     * @param repositoryKey the ASCII key under which the schema is cataloged
     */
    public AsciiStringKey {
      Objects.requireNonNull(repositoryKey, "repositoryKey must not be null");
    }
  }

  /**
   * Identifies schemas via a registered Unicode repository string key.
   *
   * @param repositoryKey the Unicode key under which the schema is cataloged
   */
  record UnicodeStringKey(String repositoryKey) implements SchemaIdentityStrategy {
    /**
     * Canonical constructor verifying non-null repository key.
     *
     * @param repositoryKey the Unicode key under which the schema is cataloged
     */
    public UnicodeStringKey {
      Objects.requireNonNull(repositoryKey, "repositoryKey must not be null");
    }
  }

  /**
   * Identifies schemas using a universal long integer version number.
   *
   * @param version the schema version number
   */
  record UniversalVersion(long version) implements SchemaIdentityStrategy {
  }

  /**
   * Identifies schemas via an explicit UUID identifier.
   *
   * @param uuid the explicit UUID
   */
  record ExplicitUuid(UUID uuid) implements SchemaIdentityStrategy {
    /**
     * Canonical constructor verifying non-null UUID.
     *
     * @param uuid the explicit UUID
     */
    public ExplicitUuid {
      Objects.requireNonNull(uuid, "uuid must not be null");
    }
  }

  /**
   * Identifies schemas via an explicit pre-computed SHA-256 hash.
   *
   * @param hash the raw SHA-256 byte array
   */
  record ExplicitSha256(byte[] hash) implements SchemaIdentityStrategy {
    /**
     * Canonical constructor verifying non-null hash array.
     *
     * @param hash the raw SHA-256 byte array
     */
    public ExplicitSha256 {
      Objects.requireNonNull(hash, "hash must not be null");
    }

    /**
     * Compares this strategy with another object for equivalence based on the hash bytes.
     *
     * @param o the other object to compare
     * @return {@code true} if the hashes are identical, otherwise {@code false}
     */
    @Override
    public boolean equals(@Nullable Object o) {
      return (this == o) || (
          (o instanceof ExplicitSha256(var thatHash))
              && Arrays.equals(this.hash, thatHash));
    }

    /**
     * Computes the hash code value for this strategy based on the hash bytes.
     *
     * @return the computed hash code
     */
    @Override
    public int hashCode() {
      return Arrays.hashCode(hash);
    }
  }

  /**
   * Self-describing schema strategy carrying inline STVN definitions schema source text ({@code .stvn_d}).
   *
   * @param schemaSourceText the STVN definitions schema source text ({@code .stvn_d})
   * @since 2.0.0
   */
  record SelfDescribingSchema(String schemaSourceText) implements SchemaIdentityStrategy {
    /**
     * Canonical constructor verifying non-null schema source text.
     *
     * @param schemaSourceText the STVN definitions schema source text
     */
    public SelfDescribingSchema {
      Objects.requireNonNull(schemaSourceText, "schemaSourceText must not be null");
    }
  }
}
