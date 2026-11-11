package org.stvnadore.core.printer;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.core.validation.StvnSyntaxCancellationException;

import java.util.Optional;

/**
 * Unit test suite for {@link StvnSourceFormatter} (Spec § 3.3, Operation 1: Non-Destructive
 * Authoring Source Formatting).
 */
@NullMarked
class StvnSourceFormatterTest {

  @Test
  @DisplayName("Layout 1 and Layout 2 multi-line :use directives indent to columns 4, 6, and 8")
  void testLayout1AndLayout2MultiLineUseIndentation() {
    String raw = """
        {
          :defs {
            // Layout 1: target and opening brace on same line as :use [
            :use [   :org/stvnadore/prelude/meta   {
              :StvnPosixId64   :StvnPosixId64
            }
            ]

            // Layout 2: target and opening brace on new line after :use [
            :use [
          :org/stvnadore/prelude   {
            :Port   :Port
            #SHA256_LOWER_CASE_REGEX   #SHA256_LOWER_CASE_REGEX
          }
            ]
          }
          :type :Port
          :body 8080
        }
        """;

    String expected = """
        {
          :defs {
            // Layout 1: target and opening brace on same line as :use [
            :use [ :org/stvnadore/prelude/meta {
                :StvnPosixId64 :StvnPosixId64
              }
            ]

            // Layout 2: target and opening brace on new line after :use [
            :use [
              :org/stvnadore/prelude {
                :Port :Port
                #SHA256_LOWER_CASE_REGEX #SHA256_LOWER_CASE_REGEX
              }
            ]
          }
          :type :Port
          :body 8080
        }""";

    String actual = StvnSourceFormatter.format(raw);
    Assertions.assertEquals(expected, actual);
    Assertions.assertEquals(expected, StvnSourceFormatter.format(actual), "Idempotency check failed");
  }

  @Test
  @DisplayName("Layout 2 multi-line :use nested inside :package shifts right by +2 spaces (columns 6, 8, and 10)")
  void testLayout2UseNestedInsidePackage() {
    String raw = """
        {
          :defs {
            :package :com/acme/service {
              :use [
            :org/stvnadore/prelude { #strip } {
              :Port :ServicePort
            }
              ]
              :Endpoint :Tuple(:String :ServicePort)
            }
          }
          :type :com/acme/service/Endpoint
          :body ("api.acme.internal" 443)
        }
        """;

    String expected = """
        {
          :defs {
            :package :com/acme/service {
              :use [
                :org/stvnadore/prelude { #strip } {
                  :Port :ServicePort
                }
              ]
              :Endpoint :Tuple(:String :ServicePort)
            }
          }
          :type :com/acme/service/Endpoint
          :body ("api.acme.internal" 443)
        }""";

    String actual = StvnSourceFormatter.format(raw);
    Assertions.assertEquals(expected, actual);
    Assertions.assertEquals(expected, StvnSourceFormatter.format(actual));
  }

  @Test
  @DisplayName("Automatic 5-tier decimal metadata facet sorting and :meta key sorting (#name -> #domain -> #kind)")
  void testFiveTierMetadataFacetAndMetaBlockSorting() {
    String raw = """
        {
          :meta {
            #kind #BODY
            #domain "core_fmt"
            #name "facet_sorting_test"
          }
          :defs {
            :BoundedInt { #maxExcl 1000 #comparable #TRUE #minIncl 1 #equatable #T #size 16 #unsigned } :Int
            :FilteredText { #regex "^[a-z]+$" #preserveIndent #F #maxSize 64 #minSize 1 } :String
          }
          :type :Tuple(:BoundedInt :FilteredText)
          :body (42 "hello")
        }
        """;

    String expected = """
        {
          :meta {
            #name "facet_sorting_test"
            #domain "core_fmt"
            #kind #BODY
          }
          :defs {
            :BoundedInt { #unsigned #size 16 #equatable #T #comparable #TRUE #minIncl 1 #maxExcl 1000 } :Int
            :FilteredText { #minSize 1 #maxSize 64 #preserveIndent #F #regex "^[a-z]+$" } :String
          }
          :type :Tuple(:BoundedInt :FilteredText)
          :body (42 "hello")
        }""";

    String actual = StvnSourceFormatter.format(raw);
    Assertions.assertEquals(expected, actual);
  }

  @Test
  @DisplayName("Formats gauntlet_seams.core_test.stvn_i non-destructively even when Pass 3 #DRAINING semantic error exists")
  void testFormatsGauntletSeamsWithPass3SemanticErrorAndPreservesAllConstructs() {
    String gauntletWithDrainingError = """
        // Gauntlet seams interactive smoke test with Pass 3 #DRAINING subset violation
        {
          :meta {
            #kind #BODY_INCLUDE
            #domain "core_test"
            #name "gauntlet_seams"
          }



          :defs {
            :use [
          :org/stvnadore/prelude {
            :Port :Port
            #SHA256_LOWER_CASE_REGEX #SHA256_LOWER_CASE_REGEX
          }
            ]
            :NodeState :Enum [ #STARTING   #ACTIVE   #DRAINING   #STOPPED ]
            :ActiveNodeState { #filterExcl [ #DRAINING #STOPPED ] } :NodeState
            #DEFAULT_PORT :Port 8080
          }
          :type :Tuple( :Port :ActiveNodeState { #maxSize 128 #regex #SHA256_LOWER_CASE_REGEX } :String )
          :body (
            #DEFAULT_PORT // Preserved constant reference
            #DRAINING // Intentionally excluded variant triggering Pass 3 semantic error
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
          )
        }
        """;

    // Confirm Pass 3 semantic compilation fails on #DRAINING
    StvnCompilationResult<StvnDocument> compileRes = StvnCompiler.compile(
        StvnSourceFormatter.format(gauntletWithDrainingError),
        "gauntlet_seams.core_test.stvn_i",
        StvnParserConfig.DEFAULT
    );
    Assertions.assertTrue(
        compileRes.hasErrors(),
        "Expected Pass 3 semantic error for excluded enum variant #DRAINING"
    );

    // Confirm Operation 1 (StvnSourceFormatter) succeeds non-destructively despite Pass 3 error
    String formatted = StvnSourceFormatter.format(gauntletWithDrainingError);
    String expected = """
        // Gauntlet seams interactive smoke test with Pass 3 #DRAINING subset violation
        {
          :meta {
            #name "gauntlet_seams"
            #domain "core_test"
            #kind #BODY_INCLUDE
          }

          :defs {
            :use [
              :org/stvnadore/prelude {
                :Port :Port
                #SHA256_LOWER_CASE_REGEX #SHA256_LOWER_CASE_REGEX
              }
            ]
            :NodeState :Enum[#STARTING #ACTIVE #DRAINING #STOPPED]
            :ActiveNodeState { #filterExcl [#DRAINING #STOPPED] } :NodeState
            #DEFAULT_PORT :Port 8080
          }
          :type :Tuple(
            :Port
            :ActiveNodeState
            { #maxSize 128 #regex #SHA256_LOWER_CASE_REGEX } :String
          )
          :body (
            #DEFAULT_PORT // Preserved constant reference
            #DRAINING // Intentionally excluded variant triggering Pass 3 semantic error
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
          )
        }""";

    Assertions.assertEquals(expected, formatted);
    Assertions.assertEquals(Optional.of(expected), StvnSourceFormatter.tryFormat(gauntletWithDrainingError));
  }

  @Test
  @DisplayName("Rejects Pass 1 syntax errors and forbidden tab characters in format() and tryFormat()")
  void testRejectsPass1SyntaxAndTabErrors() {
    String unclosedBrace = "{\n  :type :Int\n  :body 42\n";
    Assertions.assertThrows(
        StvnSyntaxCancellationException.class,
        () -> StvnSourceFormatter.format(unclosedBrace)
    );
    Assertions.assertEquals(Optional.empty(), StvnSourceFormatter.tryFormat(unclosedBrace));

    String tabInIndentation = "{\n\t:type :Int\n  :body 42\n}";
    StvnSyntaxCancellationException tabEx = Assertions.assertThrows(
        StvnSyntaxCancellationException.class,
        () -> StvnSourceFormatter.format(tabInIndentation)
    );
    Assertions.assertEquals("E001", tabEx.getCanonicalCode());
    Assertions.assertEquals(DiagnosticBag.ERR_TAB_CHARACTER_FORBIDDEN, tabEx.getSymbolicId());
    Assertions.assertEquals(Optional.empty(), StvnSourceFormatter.tryFormat(tabInIndentation));

    String tabInComment = "// Comment with\ttab\n{\n  :type :Int\n  :body 42\n}";
    StvnSyntaxCancellationException commentTabEx = Assertions.assertThrows(
        StvnSyntaxCancellationException.class,
        () -> StvnSourceFormatter.format(tabInComment)
    );
    Assertions.assertEquals("E001", commentTabEx.getCanonicalCode());
    Assertions.assertEquals(DiagnosticBag.ERR_TAB_CHARACTER_FORBIDDEN, commentTabEx.getSymbolicId());
    Assertions.assertEquals(Optional.empty(), StvnSourceFormatter.tryFormat(tabInComment));

    Assertions.assertEquals(Optional.empty(), StvnSourceFormatter.tryFormat(null));
  }

  @Test
  @DisplayName("Hidden-channel // comments before illegal # comment do not shift Trap 6 sanitizer detection")
  void testHiddenCommentChannelDoesNotShiftHashCommentTrap() {
    String leadingSlashesThenHashComment = """
        // Valid leading comment
        # Invalid Python-style hash comment at root
        {
          :type :Int
          :body 1
        }
        """;
    StvnCompilationResult<StvnDocument> res = StvnCompiler.compile(leadingSlashesThenHashComment);
    Assertions.assertTrue(res.hasErrors());
    Assertions.assertEquals(
        Optional.of(DiagnosticBag.ERR_BARE_HASH_PROHIBITED),
        res.diagnostics().get(0).symbolicId()
    );
  }

  @Test
  @DisplayName("Preserves authored multi-line :body tuples in gauntlet_seams including #Some #Right #99999999999999999999 443 while keeping single-line scalar containers inline")
  void gauntletSeamsMultiLineBodyTuplesPreserved() {
    String source = """
        {
          :meta {
            #name "gauntlet_seams"
            #domain "core_test"
            #kind #BODY_INCLUDE
          }

          :defs {
            :use [
              :org/stvnadore/prelude {
                :Port :Port
                :Uint64 :Uint64
              }
            ]
            :NodeState :Enum[#INIT #RUNNING #DRAINING]
            :Outcome :Option(:Either(:String :Union(:Port :Uint64)))
            #DEFAULT_OUTCOME :Outcome #Some #Right #1 8080
          }
          :type :Seq(:Tuple(:Outcome :NodeState :Uint64))
          :body [
            // Row 0 (100% Valid):
            (
              #Some #Right #1 8080
              #RUNNING
              18446744073709551615
            )

            // Row 1 (Fault Isolation & Constant Barrier):
            (
              #DEFAULT_OUTCOME
              #DRAINING
              1773561600000000000
            )

            // Row 2 (Union Branch Overflow):
            (
              #Some #Right #99999999999999999999 443
              #INIT
              0
            )
          ]
        }""";

    String formatted = StvnSourceFormatter.format(source);
    Assertions.assertEquals(source, formatted);
    Assertions.assertEquals(formatted, StvnSourceFormatter.format(formatted));
  }

  @Test
  @DisplayName("Expands outer :Tuple and :Union when depth >= 3-4 descendant schema is multi-line and formats :package -> :use at 6/8/10/8/6 indentation")
  void deepRecursiveSchemaAndPackageUseIndentation() {
    String source = """
        {
          :defs {
            :package :com/acme/fmt/deep {
              :use [ :org/stvnadore/prelude {
                  :Port :Port
                }
              ]
              :use [
                :org/stvnadore/prelude/meta {
                  :StvnPosixId64 :StvnPosixId64
                }
              ]
              :DeepEndpoint :Tuple(:StvnPosixId64 :Option(:Either(:String :Seq(:Tuple({ #minIncl 1024 } :Port { #maxSize 64 } :String)))))
            }
          }
          :type :Int
          :body 1
        }
        """;

    String expected = """
        {
          :defs {
            :package :com/acme/fmt/deep {
              :use [ :org/stvnadore/prelude {
                  :Port :Port
                }
              ]
              :use [
                :org/stvnadore/prelude/meta {
                  :StvnPosixId64 :StvnPosixId64
                }
              ]
              :DeepEndpoint :Tuple(
                :StvnPosixId64
                :Option(:Either(:String :Seq(:Tuple(
                  { #minIncl 1024 } :Port
                  { #maxSize 64 } :String
                ))))
              )
            }
          }
          :type :Int
          :body 1
        }""";

    String formatted = StvnSourceFormatter.format(source);
    Assertions.assertEquals(expected, formatted);
    Assertions.assertEquals(formatted, StvnSourceFormatter.format(formatted));
  }

  @Test
  @DisplayName("Preserves trailing same-line comments on constants and types inside :package blocks across repeated formatting")
  void testTrailingSameLineCommentOnConstantAndTypeInPackage() {
    String source = """
        {
          :defs {
            :package :com/acme/net {
              #DEFAULT_PORT { #minIncl 1024 } :Int 8080 // comment
              :PortAlias { #minIncl 1024 } :Int // type comment
            }
          }
          :type :Int
          :body 8080
        }""";

    String formatted = StvnSourceFormatter.format(source);
    Assertions.assertEquals(source, formatted);
    Assertions.assertEquals(formatted, StvnSourceFormatter.format(formatted));
  }

  @Test
  @DisplayName("Attaches trailing comment on a single-line :package opener + child to the child declaration rather than the opening brace")
  void testSameLinePackageOpenerAndChildWithTrailingComment() {
    String source = """
        {
          :defs {
            :package :com/acme/net { #DEFAULT_PORT :Int 8080 // comment
            }
          }
          :type :Int
          :body 8080
        }""";

    String expected = """
        {
          :defs {
            :package :com/acme/net {
              #DEFAULT_PORT :Int 8080 // comment
            }
          }
          :type :Int
          :body 8080
        }""";

    String formatted = StvnSourceFormatter.format(source);
    Assertions.assertEquals(expected, formatted);
    Assertions.assertEquals(formatted, StvnSourceFormatter.format(formatted));
  }

  @Test
  @DisplayName("Preserves per-facet trailing same-line comments across canonical 5-tier facet sorting in multi-line MetadataMap")
  void testMultiLineMetadataMapPreservesPerFacetTrailingComments() {
    String source = """
        {
          :defs {
            :BoundedFloat {
              #maxIncl 100.0 // upper bound comment
              #size 32 // representation comment
              #minIncl 0.0 // lower bound comment
            } :Float
          }
          :type :BoundedFloat
          :body 42.0
        }""";

    String expected = """
        {
          :defs {
            :BoundedFloat {
              #size 32 // representation comment
              #minIncl 0.0 // lower bound comment
              #maxIncl 100.0 // upper bound comment
            } :Float
          }
          :type :BoundedFloat
          :body 42.0
        }""";

    String formatted = StvnSourceFormatter.format(source);
    Assertions.assertEquals(expected, formatted);
    Assertions.assertEquals(formatted, StvnSourceFormatter.format(formatted));
  }

  @Test
  @DisplayName("Preserves authored horizontal padding and target-column alignment before trailing // comments (§ 3.3.1)")
  void testPreservesAuthoredHorizontalPaddingBeforeTrailingComments() {
    String useNested = """
        {
          :meta {
            #kind #BODY_INCLUDE          // aligned meta comment
            #domain "json"               // aligned meta comment
            #name "use_nested"           // aligned meta comment
          }
          :defs {
            :use [ :org/stvnadore/prelude/meta { :StvnPosixId64 :StvnPosixId64 } ]
            :use [ :org/stvnadore/prelude  {                  // <-- Aligned trailing comment
                :Port :Port                                   // <-- Aligned trailing comment
                #SHA256_LOWER_CASE_REGEX #SHA256_LOWER_CASE_REGEX // <-- Aligned trailing comment
              }                                               // <-- Aligned trailing comment
            ]
            :BoundedPort {
              #maxIncl 65535             // aligned facet comment
              #minIncl 1                 // aligned facet comment
            } :Port
          }
          :type :BoundedPort // single-space comment
          :body 8080// zero-space comment
        }""";

    String expected = """
        {
          :meta {
            #name "use_nested"           // aligned meta comment
            #domain "json"               // aligned meta comment
            #kind #BODY_INCLUDE          // aligned meta comment
          }
          :defs {
            :use [ :org/stvnadore/prelude/meta { :StvnPosixId64 :StvnPosixId64 } ]
            :use [ :org/stvnadore/prelude {                   // <-- Aligned trailing comment
                :Port :Port                                   // <-- Aligned trailing comment
                #SHA256_LOWER_CASE_REGEX #SHA256_LOWER_CASE_REGEX // <-- Aligned trailing comment
              }                                               // <-- Aligned trailing comment
            ]
            :BoundedPort {
              #minIncl 1                 // aligned facet comment
              #maxIncl 65535             // aligned facet comment
            } :Port
          }
          :type :BoundedPort // single-space comment
          :body 8080 // zero-space comment
        }""";

    String formatted = StvnSourceFormatter.format(useNested);
    Assertions.assertEquals(expected, formatted);
    Assertions.assertEquals(expected, StvnSourceFormatter.format(formatted), "Idempotency check failed");

    for (String line : formatted.lines().toList()) {
      if (line.contains("Aligned trailing comment") && !line.contains("#SHA256_LOWER_CASE_REGEX")) {
        Assertions.assertEquals(
            54,
            line.indexOf("//"),
            "Expected trailing comment anchored at 0-based column 54 on line: " + line
        );
      }
    }
  }

  @Test
  @DisplayName("Preserves authored multi-line :Map, :Seq, :Set, :Option, and :Either schema constructors and trailing comments while keeping single-line wrappers inline")
  void preservesAuthoredMultiLineParameterizedConstructorsAndTrailingComments() {
    String source = """
        {
          :defs {
            :InlineWrapper :Seq(:Tuple(
              :Int
              :String
            ))
            :MultiLineConstructors :Map( // map open comment
              :String // key comment
              :Seq( // seq open comment
                :Option( // option open comment
                  :Either( // either open comment
                    :Boolean // left branch comment
                    :Set( // set open comment
                      :Int // set element comment
                    )
                  )
                )
              )
            )
          }
          :type :MultiLineConstructors
          :body {}
        }""";

    String formatted = StvnSourceFormatter.format(source);
    Assertions.assertEquals(source, formatted);
    Assertions.assertEquals(formatted, StvnSourceFormatter.format(formatted));
  }

  @Test
  @DisplayName("Preserves authored comment-free multi-line MetadataMap layout while sorting facets into canonical 5-tier order across :defs, :package, :type, and inline constructors")
  void testPreservesCommentFreeMultiLineMetadataMapAndSortsFiveTierOrder() {
    String source = """
        {
          :defs {
            :ScrambledFacetOrderTrap {
              #minIncl 10
              #unsigned
              #maxExcl 100
              #size 16
              #comparable #TRUE
            } :Int
            #BOUNDED_CONST {
              #maxExcl 500
              #minIncl 1
              #unsigned
            } :Int 42
            :package :com/acme/pkg {
              :PkgBoundedString {
                #regex "^[a-z]+$"
                #maxSize 32
                #minSize 2
              } :String
            }
            :InlineTupleWithMultiLineFacet :Tuple(
              :ScrambledFacetOrderTrap
              {
                #maxSize 64
                #minSize 1
              } :String
            )
          }
          :type {
            #maxSize 10
            #minSize 1
          } :Seq(:InlineTupleWithMultiLineFacet)
          :body [
            (42 "ok")
          ]
        }""";

    String expected = """
        {
          :defs {
            :ScrambledFacetOrderTrap {
              #unsigned
              #size 16
              #comparable #TRUE
              #minIncl 10
              #maxExcl 100
            } :Int
            #BOUNDED_CONST {
              #unsigned
              #minIncl 1
              #maxExcl 500
            } :Int 42
            :package :com/acme/pkg {
              :PkgBoundedString {
                #minSize 2
                #maxSize 32
                #regex "^[a-z]+$"
              } :String
            }
            :InlineTupleWithMultiLineFacet :Tuple(
              :ScrambledFacetOrderTrap
              {
                #minSize 1
                #maxSize 64
              } :String
            )
          }
          :type {
            #minSize 1
            #maxSize 10
          } :Seq(:InlineTupleWithMultiLineFacet)
          :body [
            (42 "ok")
          ]
        }""";

    String formatted = StvnSourceFormatter.format(source);
    Assertions.assertEquals(expected, formatted);
    Assertions.assertEquals(expected, StvnSourceFormatter.format(formatted), "Idempotency check failed");
  }

  @Test
  @DisplayName("Rejects duplicate and incomplete top-level sections with StvnSyntaxCancellationException (E033 / E002)")
  void testRejectsDuplicateAndIncompleteTopLevelSections() {
    String dupMeta = "{ :meta { #name \"a\" } :meta { #name \"b\" } :type :Int :body 1 }";
    StvnSyntaxCancellationException metaEx = Assertions.assertThrows(
        StvnSyntaxCancellationException.class,
        () -> StvnSourceFormatter.format(dupMeta)
    );
    Assertions.assertEquals("E033", metaEx.getCanonicalCode());
    Assertions.assertEquals(DiagnosticBag.ERR_META_POSITION_INVALID, metaEx.getSymbolicId());
    Assertions.assertTrue(StvnSourceFormatter.tryFormat(dupMeta).isEmpty());

    String dupDefs = "{ :defs { :A :Int } :defs { :B :Int } :type :A :body 1 }";
    StvnSyntaxCancellationException defsEx = Assertions.assertThrows(
        StvnSyntaxCancellationException.class,
        () -> StvnSourceFormatter.format(dupDefs)
    );
    Assertions.assertEquals("E002", defsEx.getCanonicalCode());
    Assertions.assertEquals(DiagnosticBag.ERR_DUPLICATE_ROOT_SECTION, defsEx.getSymbolicId());
    Assertions.assertTrue(StvnSourceFormatter.tryFormat(dupDefs).isEmpty());

    String dupType = "{ :type :Int :type :String :body 1 }";
    StvnSyntaxCancellationException typeEx = Assertions.assertThrows(
        StvnSyntaxCancellationException.class,
        () -> StvnSourceFormatter.format(dupType)
    );
    Assertions.assertEquals("E002", typeEx.getCanonicalCode());
    Assertions.assertEquals(DiagnosticBag.ERR_DUPLICATE_ROOT_SECTION, typeEx.getSymbolicId());
    Assertions.assertTrue(StvnSourceFormatter.tryFormat(dupType).isEmpty());

    String dupBody = "{ :type :Int :body 1 :body 2 }";
    StvnSyntaxCancellationException bodyEx = Assertions.assertThrows(
        StvnSyntaxCancellationException.class,
        () -> StvnSourceFormatter.format(dupBody)
    );
    Assertions.assertEquals("E002", bodyEx.getCanonicalCode());
    Assertions.assertEquals(DiagnosticBag.ERR_DUPLICATE_ROOT_SECTION, bodyEx.getSymbolicId());
    Assertions.assertTrue(StvnSourceFormatter.tryFormat(dupBody).isEmpty());

    for (String incomplete : new String[]{
        "{ :meta\n  :type :Int\n  :body 1 }",
        "{ :defs\n  :type :Int\n  :body 1 }",
        "{ :type\n  :body 1 }",
        "{ :type {}\n  :body {} }",
        "{ :type :Int\n  :body }"
    }) {
      StvnSyntaxCancellationException incEx = Assertions.assertThrows(
          StvnSyntaxCancellationException.class,
          () -> StvnSourceFormatter.format(incomplete),
          () -> "Expected StvnSyntaxCancellationException for incomplete section input: " + incomplete
      );
      Assertions.assertEquals("E002", incEx.getCanonicalCode());
      Assertions.assertEquals(DiagnosticBag.ERR_MALFORMED_SECTION_STRUCTURE, incEx.getSymbolicId());
      Assertions.assertTrue(StvnSourceFormatter.tryFormat(incomplete).isEmpty());
    }
  }

  @Test
  @DisplayName("Reorders all 23 non-canonical permutations of (:meta, :defs, :type, :body) into canonical order idempotently")
  void testReordersAllTwentyThreeNonCanonicalRootSectionPermutations() {
    String[] sections = {
        """
          :meta {
            #name "perm_test"
            #domain "domain"
            #kind #BODY
          }""",
        """
          :defs {
            :Type :String
          }""",
        "  :type :Type",
        "  :body \"ok\""
    };

    String expectedCanonical = """
        {
          :meta {
            #name "perm_test"
            #domain "domain"
            #kind #BODY
          }
          :defs {
            :Type :String
          }
          :type :Type
          :body "ok"
        }""";

    int nonCanonicalCount = 0;
    for (int a = 0; a < 4; a++) {
      for (int b = 0; b < 4; b++) {
        if (b == a) {
          continue;
        }
        for (int c = 0; c < 4; c++) {
          if (c == a || c == b) {
            continue;
          }
          for (int d = 0; d < 4; d++) {
            if (d == a || d == b || d == c) {
              continue;
            }
            if (a == 0 && b == 1 && c == 2 && d == 3) {
              continue;
            }
            nonCanonicalCount++;
            String permuted = "{\n" + sections[a] + "\n" + sections[b] + "\n" + sections[c] + "\n" + sections[d] + "\n}";
            String formatted = StvnSourceFormatter.format(permuted);
            Assertions.assertEquals(expectedCanonical, formatted,
                "Permutation (" + a + "," + b + "," + c + "," + d + ") failed canonical reordering");
            Assertions.assertEquals(expectedCanonical, StvnSourceFormatter.format(formatted),
                "Idempotency check failed for permutation (" + a + "," + b + "," + c + "," + d + ")");
          }
        }
      }
    }
    Assertions.assertEquals(23, nonCanonicalCount, "Must test all 4! - 1 = 23 non-canonical permutations");
  }

  @Test
  @DisplayName("Formats root_ordering.stvn by reordering root sections, indenting :defs body to 4 spaces, and migrating section comments")
  void testFormatsRootOrderingAndMigratesSectionComments() {
    String rootOrdering = """
        {
          :body ""
          :type :Type
          :defs {
          :Type :String
          }
          :meta {
            #name "root_ordering"
            #domain "domain"
            #kind #BODY
          }
        }""";

    String expected = """
        {
          :meta {
            #name "root_ordering"
            #domain "domain"
            #kind #BODY
          }
          :defs {
            :Type :String
          }
          :type :Type
          :body ""
        }""";

    String formatted = StvnSourceFormatter.format(rootOrdering);
    Assertions.assertEquals(expected, formatted);
    Assertions.assertEquals(expected, StvnSourceFormatter.format(formatted));

    StvnCompilationResult<StvnDocument> compileRes = StvnCompiler.compile(
        formatted,
        "root_ordering.domain.stvn",
        StvnParserConfig.DEFAULT
    );
    Assertions.assertFalse(compileRes.hasErrors(), () -> "Formatted root_ordering must have 0 errors: " + compileRes.diagnostics());
    Assertions.assertTrue(compileRes.diagnostics().isEmpty(), () -> "Expected 0 warnings: " + compileRes.diagnostics());

    String withComments = """
        {
          // Leading comment on :body A
          // Leading comment on :body B
          :body "" // Trailing comment on :body
          // Leading comment on :type
          :type :Type // Trailing comment on :type
          // Leading comment on :defs
          :defs {
          :Type :String // Inner defs comment
          } // Trailing comment on :defs
          // Leading comment on :meta
          :meta {
            #kind #BODY
            #domain "domain"
            #name "root_ordering"
          } // Trailing comment on :meta
          // Root footer comment before closing brace
        }""";

    String expectedWithComments = """
        {
          // Leading comment on :meta
          :meta {
            #name "root_ordering"
            #domain "domain"
            #kind #BODY
          } // Trailing comment on :meta
          // Leading comment on :defs
          :defs {
            :Type :String // Inner defs comment
          } // Trailing comment on :defs
          // Leading comment on :type
          :type :Type // Trailing comment on :type
          // Leading comment on :body A
          // Leading comment on :body B
          :body "" // Trailing comment on :body
          // Root footer comment before closing brace
        }""";

    String formattedWithComments = StvnSourceFormatter.format(withComments);
    Assertions.assertEquals(expectedWithComments, formattedWithComments);
    Assertions.assertEquals(expectedWithComments, StvnSourceFormatter.format(formattedWithComments));
  }
}
