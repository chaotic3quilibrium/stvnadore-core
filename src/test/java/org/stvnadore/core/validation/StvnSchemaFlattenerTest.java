package org.stvnadore.core.validation;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnSchemaFlattener;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

class StvnSchemaFlattenerTest {

  @Test
  void testSimpleFlattenNoImports() {
    Map<String, String> workspace = Map.of(
        "main.stvn", """
            {
              :defs {
                :User { #regex "^[a-z]+$" } :String
                :Age { #minIncl 0 #maxExcl 151 } :Int
              }
              :type :User
              :body "bob"
            }
            """
    );

    String result = StvnSchemaFlattener.flatten(workspace, "main.stvn");
    // Nominal sorting: :Age should come before :User
    String expected = "{:meta{#kind #DEFS}:defs{:Age{#minIncl 0 #maxExcl 151}:Int :User{#regex \"^[a-z]+$\"}:String}}";
    Assertions.assertEquals(expected, result);
  }

  @Test
  void testTransitiveImportsAndSorting() {
    Map<String, String> workspace = Map.of(
        "main.stvn_i", """
            {
              :defs {
                :include [
                  "types/user.stvn_di"
                ]
                :Status :Enum[#ACTIVE #INACTIVE]
              }
              :type :User
              :body ( "Alice" 30 )
            }
            """,
        "types/user.stvn_di", """
            {
              :defs {
                :include [
                  "../common/primitives.stvn_d"
                ]
                :User :Tuple( :Username :Age )
              }
            }
            """,
        "common/primitives.stvn_d", """
            {
              :defs {
                :Username { #regex "^[A-Za-z0-9_]+$" } :String
                :Age :Int
              }
            }
            """
    );

    String result = StvnSchemaFlattener.flatten(workspace, "main.stvn_i");
    // Sorted alphabetically by nominal name:
    // :Age
    // :Status
    // :User
    // :Username
    String expected = "{:meta{#kind #DEFS}:defs{:Age :Int :Status :Enum[#ACTIVE #INACTIVE] :User :Tuple(:Username :Age) :Username{#regex \"^[A-Za-z0-9_]+$\"}:String}}";
    Assertions.assertEquals(expected, result);
  }

  @Test
  void testDirectCycleThrows() {
    Map<String, String> workspace = Map.of(
        "main.stvn_i", """
            {
              :defs {
                :include [ "main.stvn_i" ]
              }
            }
            """
    );

    CyclicDependencyException ex = Assertions.assertThrows(
        CyclicDependencyException.class,
        () -> StvnSchemaFlattener.flatten(workspace, "main.stvn_i")
    );

    Assertions.assertTrue(ex.getMessage().contains("Cycle detected: main.stvn_i -> main.stvn_i"));
    Assertions.assertEquals(1, ex.getOffendingIncludePathsRaw().size());
    Assertions.assertEquals("main.stvn_i", ex.getOffendingIncludePathsRaw().get(0));
  }

  @Test
  void testIndirectCycleThrows() {
    Map<String, String> workspace = Map.of(
        "main.stvn_i", """
            {
              :defs {
                :include [ "module_a.stvn_di" ]
              }
            }
            """,
        "module_a.stvn_di", """
            {
              :defs {
                :include [ "module_b.stvn_di" ]
              }
            }
            """,
        "module_b.stvn_di", """
            {
              :defs {
                :include [ "module_a.stvn_di" ]
              }
            }
            """
    );

    CyclicDependencyException ex = Assertions.assertThrows(
        CyclicDependencyException.class,
        () -> StvnSchemaFlattener.flatten(workspace, "main.stvn_i")
    );

    // main.stvn_i -> module_a -> module_b -> module_a
    Assertions.assertTrue(ex.getMessage().contains("Cycle detected: module_a.stvn_di -> module_b.stvn_di -> module_a.stvn_di"));
  }

  @Test
  void testDuplicateModuleImportThrows() {
    Map<String, String> workspace = Map.of(
        "main.stvn_i", """
            {
              :defs {
                :include [
                  "common.stvn_d"
                  "common.stvn_d"
                ]
              }
            }
            """,
        "common.stvn_d", """
            {
              :defs {
                :A :Int
              }
            }
            """
    );

    DuplicateModuleImportException ex = Assertions.assertThrows(
        DuplicateModuleImportException.class,
        () -> StvnSchemaFlattener.flatten(workspace, "main.stvn_i")
    );

    Assertions.assertTrue(ex.getMessage().contains("Duplicate module import detected for path: common.stvn_d"));
  }

  @Test
  void testCollisionAbsoluteLocalPriority() {
    Map<String, String> workspace = Map.of(
        "main.stvn_i", """
            {
              :defs {
                :include [
                  "module_a.stvn_d"
                ]
                :ConflictType :String
              }
            }
            """,
        "module_a.stvn_d", """
            {
              :defs {
                :ConflictType :Int
              }
            }
            """
    );

    String result = StvnSchemaFlattener.flatten(workspace, "main.stvn_i");
    // Local priority evicts the imported :ConflictType :Int, leaving :ConflictType :String
    String expected = "{:meta{#kind #DEFS}:defs{:ConflictType :String}}";
    Assertions.assertEquals(expected, result);
  }

  @Test
  void testCollisionAsymmetricIngestionEviction() {
    Map<String, String> workspace = Map.of(
        "main.stvn_i", """
            {
              :defs {
                :include [
                  "module_a.stvn_d"
                  "module_b.stvn_d"
                ]
                :use [ :pkg/a { :ConflictType :AliasA } ]
                :Combined :Tuple(:AliasA :ConflictType)
              }
              :type :Combined
              :body (1 "ok")
            }
            """,
        "module_a.stvn_d", """
            {
              :defs {
                :package :pkg/a {
                  :ConflictType :Int
                }
              }
            }
            """,
        "module_b.stvn_d", """
            {
              :defs {
                :ConflictType :String
              }
            }
            """
    );

    String result = StvnSchemaFlattener.flatten(workspace, "main.stvn_i");
    // Pure :include + :use preserves canonical FQNI :pkg/a/ConflictType alongside unpackaged :ConflictType
    String expected = "{:meta{#kind #DEFS}:defs{:Combined :Tuple(:pkg/a/ConflictType :ConflictType) :ConflictType :String :pkg/a/ConflictType :Int}}";
    Assertions.assertEquals(expected, result);
  }

  @Test
  void testCollisionDualIngestionEviction() {
    Map<String, String> workspace = Map.of(
        "main.stvn_i", """
            {
              :defs {
                :include [
                  "module_a.stvn_d"
                  "module_b.stvn_d"
                ]
                :use [
                  :pkg/a { :ConflictType :AliasA }
                  :pkg/b { :ConflictType :AliasB }
                ]
                :Combined :Tuple(:AliasA :AliasB)
              }
              :type :Combined
              :body (1 "ok")
            }
            """,
        "module_a.stvn_d", """
            {
              :defs {
                :package :pkg/a {
                  :ConflictType :Int
                }
              }
            }
            """,
        "module_b.stvn_d", """
            {
              :defs {
                :package :pkg/b {
                  :ConflictType :String
                }
              }
            }
            """
    );

    String result = StvnSchemaFlattener.flatten(workspace, "main.stvn_i");
    String expected = "{:meta{#kind #DEFS}:defs{:Combined :Tuple(:pkg/a/ConflictType :pkg/b/ConflictType) :pkg/a/ConflictType :Int :pkg/b/ConflictType :String}}";
    Assertions.assertEquals(expected, result);
  }

  @Test
  void testCollisionUnmitigatedRawCollisionThrows() {
    Map<String, String> workspace = Map.of(
        "main.stvn_i", """
            {
              :defs {
                :include [
                  "module_a.stvn_d"
                  "module_b.stvn_d"
                ]
              }
            }
            """,
        "module_a.stvn_d", """
            {
              :defs {
                :ConflictType :Int
              }
            }
            """,
        "module_b.stvn_d", """
            {
              :defs {
                :ConflictType :String
              }
            }
            """
    );

    NamespaceCollisionException ex = Assertions.assertThrows(
        NamespaceCollisionException.class,
        () -> StvnSchemaFlattener.flatten(workspace, "main.stvn_i")
    );
    Assertions.assertTrue(ex.getMessage().contains("enclose module declarations in ':package' and disambiguate local symbols with ':use'"));
  }

  @Test
  void testReferenceRewriting() {
    Map<String, String> workspace = Map.of(
        "main.stvn_i", """
            {
              :defs {
                :include [
                  "module_a.stvn_d"
                ]
                :use [ :pkg/a { :ConflictType :ConflictTypeA :TypeA :ImportedTypeA } ]
                :ConflictType :Int
                :RootPair :Tuple(:ConflictType :ImportedTypeA)
              }
              :type :RootPair
              :body (7 ("s" 9))
            }
            """,
        "module_a.stvn_d", """
            {
              :defs {
                :package :pkg/a {
                  :TypeA :Tuple( :ConflictType :Int )
                  :ConflictType :String
                }
              }
            }
            """
    );

    String result = StvnSchemaFlattener.flatten(workspace, "main.stvn_i");
    String expected = "{:meta{#kind #DEFS}:defs{:ConflictType :Int :RootPair :Tuple(:ConflictType :pkg/a/TypeA) :pkg/a/ConflictType :String :pkg/a/TypeA :Tuple(:pkg/a/ConflictType :Int)}}";
    Assertions.assertEquals(expected, result);
  }

  @Test
  void testCanonicalFormatStripping() {
    Map<String, String> workspace = Map.of(
        "main.stvn", """
            {
              :defs {
                // This is a line comment that must be stripped
                :TypeA       :Int    // Another trailing comment
                :TypeB {
                   #regex     "^[a-z]+$" // spacing constraint
                } :String
              }
            }
            """
    );

    String result = StvnSchemaFlattener.flatten(workspace, "main.stvn");
    // Comments must be gone, whitespace normalized to single spaces, constraints sorted.
    String expected = "{:meta{#kind #DEFS}:defs{:TypeA :Int :TypeB{#regex \"^[a-z]+$\"}:String}}";
    Assertions.assertEquals(expected, result);
  }

  @Test
  void testCrossPlatformAndDeclarationOrderInvariance() {
    // Workspace 1: Mix of Windows backslashes and different file insertion order (LinkedHashMap)
    Map<String, String> workspace1 = new LinkedHashMap<>();
    workspace1.put("types\\user.stvn_d", """
        {
          :defs {
            :Username :String
            :Age :Int
            :User :Tuple( :Username :Age )
          }
        }
        """);
    workspace1.put("main.stvn_i", """
        {
          :defs {
            :include [
              "types\\\\user.stvn_d"
            ]
            :Status :Enum[#ACTIVE #INACTIVE]
          }
        }
        """);

    // Workspace 2: Standard Unix forward slashes, different file insertion order (TreeMap)
    Map<String, String> workspace2 = new TreeMap<>();
    workspace2.put("main.stvn_i", """
        {
          :defs {
            :include [
              "types/user.stvn_d"
            ]
            :Status :Enum[#ACTIVE #INACTIVE]
          }
        }
        """);
    workspace2.put("types/user.stvn_d", """
        {
          :defs {
            :Age :Int
            :Username :String
            :User :Tuple( :Username :Age )
          }
        }
        """);

    String result1 = StvnSchemaFlattener.flatten(workspace1, "main.stvn_i");
    String result2 = StvnSchemaFlattener.flatten(workspace2, "main.stvn_i");

    // Both must yield identical byte-for-byte outputs
    Assertions.assertEquals(result1, result2);
    String expected = "{:meta{#kind #DEFS}:defs{:Age :Int :Status :Enum[#ACTIVE #INACTIVE] :User :Tuple(:Username :Age) :Username :String}}";
    Assertions.assertEquals(expected, result1);
  }
}
