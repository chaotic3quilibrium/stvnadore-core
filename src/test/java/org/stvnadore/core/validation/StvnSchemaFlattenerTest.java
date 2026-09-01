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
                :Age { #minIncl 0 #maxIncl 150 } :Int32
              }
              :type :User
              :body "bob"
            }
            """
    );

    String result = StvnSchemaFlattener.flatten(workspace, "main.stvn");
    // Nominal sorting: :Age should come before :User
    String expected = "{ :defs { :Age { #maxIncl 150 #minIncl 0 } :Int32 :User { #regex \"^[a-z]+$\" } :String } }";
    Assertions.assertEquals(expected, result);
  }

  @Test
  void testTransitiveImportsAndSorting() {
    Map<String, String> workspace = Map.of(
        "main.stvn", """
            {
              :defs {
                :include [
                  "types/user.stvn_incl"
                ]
                :Status :Enum[#Active #Inactive]
              }
              :type :User
              :body ( "Alice" 30 )
            }
            """,
        "types/user.stvn_incl", """
            {
              :defs {
                :include [
                  "../common/primitives.stvn_incl"
                ]
                :User :Tuple( :Username :Age )
              }
            }
            """,
        "common/primitives.stvn_incl", """
            {
              :defs {
                :Username { #regex "^[A-Za-z0-9_]+$" } :String
                :Age :Int32
              }
            }
            """
    );

    String result = StvnSchemaFlattener.flatten(workspace, "main.stvn");
    // Sorted alphabetically by nominal name:
    // :Age
    // :Status
    // :User
    // :Username
    String expected = "{ :defs { :Age :Int32 :Status :Enum[#Active #Inactive] :User :Tuple(:Username :Age) :Username { #regex \"^[A-Za-z0-9_]+$\" } :String } }";
    Assertions.assertEquals(expected, result);
  }

  @Test
  void testDirectCycleThrows() {
    Map<String, String> workspace = Map.of(
        "main.stvn", """
            {
              :defs {
                :include [ "main.stvn" ]
              }
            }
            """
    );

    CyclicDependencyException ex = Assertions.assertThrows(
        CyclicDependencyException.class,
        () -> StvnSchemaFlattener.flatten(workspace, "main.stvn")
    );

    Assertions.assertTrue(ex.getMessage().contains("Cycle detected: main.stvn -> main.stvn"));
    Assertions.assertEquals(1, ex.getOffendingIncludePathsRaw().size());
    Assertions.assertEquals("main.stvn", ex.getOffendingIncludePathsRaw().get(0));
  }

  @Test
  void testIndirectCycleThrows() {
    Map<String, String> workspace = Map.of(
        "main.stvn", """
            {
              :defs {
                :include [ "module_a.stvn_incl" ]
              }
            }
            """,
        "module_a.stvn_incl", """
            {
              :defs {
                :include [ "module_b.stvn_incl" ]
              }
            }
            """,
        "module_b.stvn_incl", """
            {
              :defs {
                :include [ "module_a.stvn_incl" ]
              }
            }
            """
    );

    CyclicDependencyException ex = Assertions.assertThrows(
        CyclicDependencyException.class,
        () -> StvnSchemaFlattener.flatten(workspace, "main.stvn")
    );

    // main.stvn -> module_a -> module_b -> module_a
    Assertions.assertTrue(ex.getMessage().contains("Cycle detected: module_a.stvn_incl -> module_b.stvn_incl -> module_a.stvn_incl"));
  }

  @Test
  void testDuplicateModuleImportThrows() {
    Map<String, String> workspace = Map.of(
        "main.stvn", """
            {
              :defs {
                :include [
                  "common.stvn_incl"
                  "common.stvn_incl"
                ]
              }
            }
            """,
        "common.stvn_incl", """
            {
              :defs {
                :TypeA :Int32
              }
            }
            """
    );

    Assertions.assertThrows(
        DuplicateModuleImportException.class,
        () -> StvnSchemaFlattener.flatten(workspace, "main.stvn")
    );
  }

  @Test
  void testCollisionAbsoluteLocalPriority() {
    Map<String, String> workspace = Map.of(
        "main.stvn", """
            {
              :defs {
                :include [
                  "module_a.stvn_incl"
                ]
                :ConflictType :String
              }
            }
            """,
        "module_a.stvn_incl", """
            {
              :defs {
                :ConflictType :Int32
              }
            }
            """
    );

    String result = StvnSchemaFlattener.flatten(workspace, "main.stvn");
    // Local priority evicts the imported :ConflictType :Int32, leaving :ConflictType :String
    String expected = "{ :defs { :ConflictType :String } }";
    Assertions.assertEquals(expected, result);
  }

  @Test
  void testCollisionAsymmetricIngestionEviction() {
    Map<String, String> workspace = Map.of(
        "main.stvn", """
            {
              :defs {
                :include [
                  "module_a.stvn_incl" {
                    :ConflictType :AliasA
                  }
                  "module_b.stvn_incl"
                ]
              }
            }
            """,
        "module_a.stvn_incl", """
            {
              :defs {
                :ConflictType :Int32
              }
            }
            """,
        "module_b.stvn_incl", """
            {
              :defs {
                :ConflictType :String
              }
            }
            """
    );

    String result = StvnSchemaFlattener.flatten(workspace, "main.stvn");
    // :ConflictType from module_a is renamed to :AliasA, which evicts its LHS name (:ConflictType) from the collision,
    // allowing module_b's raw :ConflictType to survive.
    // Alphabetical order: :AliasA, :ConflictType
    String expected = "{ :defs { :AliasA :Int32 :ConflictType :String } }";
    Assertions.assertEquals(expected, result);
  }

  @Test
  void testCollisionDualIngestionEviction() {
    Map<String, String> workspace = Map.of(
        "main.stvn", """
            {
              :defs {
                :include [
                  "module_a.stvn_incl" {
                    :ConflictType :AliasA
                  }
                  "module_b.stvn_incl" {
                    :ConflictType :AliasB
                  }
                ]
              }
            }
            """,
        "module_a.stvn_incl", """
            {
              :defs {
                :ConflictType :Int32
              }
            }
            """,
        "module_b.stvn_incl", """
            {
              :defs {
                :ConflictType :String
              }
            }
            """
    );

    String result = StvnSchemaFlattener.flatten(workspace, "main.stvn");
    // Both aliases survive, but the raw LHS name (:ConflictType) is evicted and unresolved in the local namespace.
    // No exception is thrown because the collision itself was mitigated by alias renaming.
    String expected = "{ :defs { :AliasA :Int32 :AliasB :String } }";
    Assertions.assertEquals(expected, result);
  }

  @Test
  void testCollisionUnmitigatedRawCollisionThrows() {
    Map<String, String> workspace = Map.of(
        "main.stvn", """
            {
              :defs {
                :include [
                  "module_a.stvn_incl"
                  "module_b.stvn_incl"
                ]
              }
            }
            """,
        "module_a.stvn_incl", """
            {
              :defs {
                :ConflictType :Int32
              }
            }
            """,
        "module_b.stvn_incl", """
            {
              :defs {
                :ConflictType :String
              }
            }
            """
    );

    Assertions.assertThrows(
        NamespaceCollisionException.class,
        () -> StvnSchemaFlattener.flatten(workspace, "main.stvn")
    );
  }

  @Test
  void testReferenceRewriting() {
    Map<String, String> workspace = Map.of(
        "main.stvn", """
            {
              :defs {
                :include [
                  "module_a.stvn_incl" {
                    :ConflictType :ConflictTypeA
                  }
                ]
                :ConflictType :Int32
              }
            }
            """,
        "module_a.stvn_incl", """
            {
              :defs {
                :TypeA :Tuple( :ConflictType :Int32 )
                :ConflictType :String
              }
            }
            """
    );

    String result = StvnSchemaFlattener.flatten(workspace, "main.stvn");
    // TypeA's internal reference to :ConflictType must be rewritten to :ConflictTypeA
    String expected = "{ :defs { :ConflictType :Int32 :ConflictTypeA :String :TypeA :Tuple(:ConflictTypeA :Int32) } }";
    Assertions.assertEquals(expected, result);
  }

  @Test
  void testCanonicalFormatStripping() {
    Map<String, String> workspace = Map.of(
        "main.stvn", """
            {
              :defs {
                // This is a line comment that must be stripped
                :TypeA       :Int32    // Another trailing comment
                :TypeB {
                   #regex     "^[a-z]+$" // spacing constraint
                } :String
              }
            }
            """
    );

    String result = StvnSchemaFlattener.flatten(workspace, "main.stvn");
    // Comments must be gone, whitespace normalized to single spaces, constraints sorted.
    String expected = "{ :defs { :TypeA :Int32 :TypeB { #regex \"^[a-z]+$\" } :String } }";
    Assertions.assertEquals(expected, result);
  }

  @Test
  void testCrossPlatformAndDeclarationOrderInvariance() {
    // Workspace 1: Mix of Windows backslashes, tabs, and different file insertion order (LinkedHashMap)
    Map<String, String> workspace1 = new LinkedHashMap<>();
    workspace1.put("types\\user.stvn_incl", """
        {
          :defs {
            :User :Tuple( :Username :Age )
          }
        }
        """);
    workspace1.put("main.stvn", """
        {
          :defs {
            :include [
              "types\\\\user.stvn_incl"
            ]
            :Status :Enum[#Active #Inactive]
          }
        }
        """);

    // Workspace 2: Standard Unix forward slashes, different file insertion order (TreeMap)
    Map<String, String> workspace2 = new TreeMap<>();
    workspace2.put("main.stvn", """
        {
          :defs {
            :include [
              "types/user.stvn_incl"
            ]
            :Status :Enum[#Active #Inactive]
          }
        }
        """);
    workspace2.put("types/user.stvn_incl", """
        {
          :defs {
            :User :Tuple( :Username :Age )
          }
        }
        """);

    String result1 = StvnSchemaFlattener.flatten(workspace1, "main.stvn");
    String result2 = StvnSchemaFlattener.flatten(workspace2, "main.stvn");

    // Both must yield identical byte-for-byte outputs
    Assertions.assertEquals(result1, result2);
    String expected = "{ :defs { :Status :Enum[#Active #Inactive] :User :Tuple(:Username :Age) } }";
    Assertions.assertEquals(expected, result1);
  }
}
