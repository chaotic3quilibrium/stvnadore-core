package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnSchemaFlattener;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test suite verifying diamond include deduplication across multi-file
 * dependency graphs (A -> B, C -> D).
 */
@NullMarked
public class StvnDiamondIncludeIntegrationTest {

  @Test
  @DisplayName("Diamond dependency graph (A -> B, C -> D) flattens successfully without NamespaceCollisionException")
  void testDiamondDependencyGraphFlattensSuccessfully() {
    Map<String, String> workspace = Map.of(
        "A.stvn", """
            {
              :defs {
                :include [ "b.stvn_incl" "c.stvn_incl" ]
                :TypeA :Tuple( :TypeB :TypeC )
              }
              :type :TypeA
              :body ( ( 42 10 ) ( 42 "hello" ) )
            }
            """,
        "b.stvn_incl", """
            {
              :defs {
                :include [ "d.stvn_incl" ]
                :TypeB :Tuple( :TypeD :Int )
              }
            }
            """,
        "c.stvn_incl", """
            {
              :defs {
                :include [ "d.stvn_incl" ]
                :TypeC :Tuple( :TypeD :String )
              }
            }
            """,
        "d.stvn_incl", """
            {
              :defs {
                :TypeD { #size 32 } :Int
              }
            }
            """
    );

    // Flattening must succeed cleanly without throwing NamespaceCollisionException
    String flattened = assertDoesNotThrow(() -> StvnSchemaFlattener.flatten(workspace, "A.stvn"));

    assertNotNull(flattened);
    assertTrue(flattened.contains(":TypeD { #size 32 } :Int"), "Flattened output must contain shared definition :TypeD");
    assertTrue(flattened.contains(":TypeB"), "Flattened output must contain :TypeB");
    assertTrue(flattened.contains(":TypeC"), "Flattened output must contain :TypeC");
    assertTrue(flattened.contains(":TypeA"), "Flattened output must contain :TypeA");

    // The flattened schema should be valid and compile cleanly
    String wrappedDocument = """
        {
        """ + flattened.substring(1, flattened.length() - 1) + """
          :type :TypeA
          :body ( ( 42 10 ) ( 42 "hello" ) )
        }
        """;
    var result = StvnCompiler.compileToResult(wrappedDocument);
    assertTrue(result.isSuccess(), "Flattened diamond schema must compile cleanly: " + result.diagnostics());
    assertFalse(result.hasErrors());
  }

  @Test
  @DisplayName("Diamond dependency with shared typed constants and multiple types deduplicates cleanly")
  void testDiamondIncludeWithConstantsAndMultipleTypes() {
    Map<String, String> workspace = Map.of(
        "root.stvn", """
            {
              :defs {
                :include [ "module1.stvn_incl" "module2.stvn_incl" ]
                :RootType :Tuple( :Type1 :Type2 )
              }
              :type :RootType
              :body ( 1 2 )
            }
            """,
        "module1.stvn_incl", """
            {
              :defs {
                :include [ "base.stvn_incl" ]
                :Type1 :BaseType
              }
            }
            """,
        "module2.stvn_incl", """
            {
              :defs {
                :include [ "base.stvn_incl" ]
                :Type2 :BaseType
              }
            }
            """,
        "base.stvn_incl", """
            {
              :defs {
                :BaseType :Int
                #BASE_CONST :Int 100
              }
            }
            """
    );

    String flattened = assertDoesNotThrow(() -> StvnSchemaFlattener.flatten(workspace, "root.stvn"));
    assertNotNull(flattened);
    assertTrue(flattened.contains(":BaseType :Int"));
    assertTrue(flattened.contains("#BASE_CONST :Int 100"));
  }

  @Test
  @DisplayName("Diamond dependency where root also directly includes the diamond leaf D")
  void testDiamondIncludeDirectIncludeInRoot() {
    Map<String, String> workspace = Map.of(
        "root.stvn", """
            {
              :defs {
                :include [ "leaf.stvn_incl" "mid1.stvn_incl" "mid2.stvn_incl" ]
                :Root :Tuple( :Mid1 :Mid2 :Leaf )
              }
            }
            """,
        "mid1.stvn_incl", """
            {
              :defs {
                :include [ "leaf.stvn_incl" ]
                :Mid1 :Leaf
              }
            }
            """,
        "mid2.stvn_incl", """
            {
              :defs {
                :include [ "leaf.stvn_incl" ]
                :Mid2 :Leaf
              }
            }
            """,
        "leaf.stvn_incl", """
            {
              :defs {
                :Leaf { #exact } :Float
              }
            }
            """
    );

    String flattened = assertDoesNotThrow(() -> StvnSchemaFlattener.flatten(workspace, "root.stvn"));
    assertNotNull(flattened);
    assertTrue(flattened.contains(":Leaf { #exact } :Float"));
  }

  @Test
  @DisplayName("Genuine namespace collisions across distinct source modules are still rejected")
  void testGenuineCollisionAcrossDistinctModulesStillFails() {
    Map<String, String> workspace = Map.of(
        "A.stvn", """
            {
              :defs {
                :include [ "b.stvn_incl" "c.stvn_incl" ]
              }
            }
            """,
        "b.stvn_incl", """
            {
              :defs {
                :include [ "d1.stvn_incl" ]
              }
            }
            """,
        "c.stvn_incl", """
            {
              :defs {
                :include [ "d2.stvn_incl" ]
              }
            }
            """,
        "d1.stvn_incl", """
            {
              :defs {
                :ConflictingType :Int
              }
            }
            """,
        "d2.stvn_incl", """
            {
              :defs {
                :ConflictingType :String
              }
            }
            """
    );

    // Two distinct modules (d1 and d2) defining :ConflictingType MUST trigger NamespaceCollisionException
    NamespaceCollisionException ex = assertThrows(
        NamespaceCollisionException.class,
        () -> StvnSchemaFlattener.flatten(workspace, "A.stvn")
    );
    assertTrue(ex.getMessage().contains("ConflictingType"), "Exception must mention the conflicting identifier");
  }
}
