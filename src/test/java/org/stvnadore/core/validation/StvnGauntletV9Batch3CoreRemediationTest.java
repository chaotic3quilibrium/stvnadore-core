package org.stvnadore.core.validation;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.printer.StvnSourceFormatter;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * Conformance regression suite for Gauntlet v9 Batch 3 Core Remediation ({@code B3R-01}..{@code B3R-03}
 * and Safeguards 1–4):
 * <ul>
 *   <li>{@code B3R-01}: 0-branch {@code :Union()} minimum branch count diagnostic accuracy.</li>
 *   <li>{@code B3R-02}: {@code :package :path/} trailing-slash grammar symmetry, Pass 2 enclosure
 *       isolation, {@link StvnSourceFormatter} non-destructive formatting, and zero {@code :body} blackout.</li>
 *   <li>{@code B3R-03}: Recursive and nested-sum {@code #CONST} opaque nominal branding (§ 9.1 / {@code E027}).</li>
 * </ul>
 */
@NullMarked
class StvnGauntletV9Batch3CoreRemediationTest {

  @Test
  @DisplayName("B3R-01: 0-branch :Union() and :Union (   ) state 'requires at least 2 schema types' while :Tuple() states 'at least one'")
  void testEmptyUnionVsEmptyTupleDiagnosticAccuracy() {
    String source = """
        {
          :defs {
            :EmptyTuple :Tuple()
            :EmptyUnion :Union()
            :SpacedUnion :Union (   )
            :SingleUnion :Union(:Int)
          }
          :type :Int
          :body 42
        }
        """;

    StvnCompilationResult<StvnDocument> res = StvnCompiler.compile(source);
    List<StvnDiagnostic> errors = res.diagnostics().stream()
        .filter(d -> d.severity() == DiagnosticSeverity.ERROR)
        .toList();

    Assertions.assertEquals(4, errors.size(), () -> "Expected 4 E010 constructor errors, got: " + errors);
    Assertions.assertEquals(
        "STVN Syntax Error: empty composite argument list: :Tuple() requires at least one schema type",
        errors.get(0).message()
    );
    Assertions.assertEquals(
        "STVN Syntax Error: empty composite argument list: :Union() requires at least 2 schema types",
        errors.get(1).message()
    );
    Assertions.assertEquals(
        "STVN Syntax Error: empty composite argument list: :Union() requires at least 2 schema types",
        errors.get(2).message()
    );
    Assertions.assertEquals(
        "Union schema requires at least 2 branch types, got 1",
        errors.get(3).message()
    );
  }

  @Test
  @DisplayName("B3R-02 & Safeguard 1: Trailing-slash :package parses with 0 Pass 1 errors, isolates inner defs, formats cleanly, and preserves Pass 3 :body validation")
  void testTrailingSlashPackageRecoveryFormatterAndBodyPreservation() {
    String source = """
        {
          :meta {
            #name "stage9_remediation"
            #kind #BODY
          }
          :defs {
            :package :propulsion/thruster {
              :ThrustMillinewtons { #unsigned #minIncl 100 #maxExcl 500000 } :Int
              #CRUISE_THRUST :ThrustMillinewtons 12500
            }

            :package :propulsion/attitude {
              :ThrustMillinewtons { #unsigned #minIncl 100 #maxExcl 500000 } :Int
              #RCS_PULSE_THRUST :ThrustMillinewtons 12500
            }

            :package :propulsion/trailing_slash/ {
              :SlashInt :Int
            }

            :use [
              :propulsion/trailing_slash { #strip }
              :propulsion/thruster { #strip }
              :propulsion/attitude {
                :ThrustMillinewtons :AttitudeThrust
                #RCS_PULSE_THRUST #ATTITUDE_PULSE
              }
            ]

            :Stage9Matrix :Tuple(
              :ThrustMillinewtons
              :AttitudeThrust
              :ThrustMillinewtons
              :ThrustMillinewtons
            )
          }
          :type :Stage9Matrix
          :body (
            #CRUISE_THRUST
            #ATTITUDE_PULSE
            #ATTITUDE_PULSE
            50
          )
        }
        """.replace("\r\n", "\n");

    // 1. Non-destructive Pass 1 formatter succeeds without throwing
    String formatted = StvnSourceFormatter.format(source);
    Assertions.assertTrue(
        formatted.contains(":package :propulsion/trailing_slash/ {"),
        "Formatter must preserve trailing-slash package header verbatim:\n" + formatted
    );

    // 2. Pass 2 + Pass 3 compilation emits exact 3 errors (E002 on packagePath, E027 on Slot 3, E012 on Slot 4)
    //    with 0 cascading E035 on ':use [ :propulsion/trailing_slash { #strip } ]' and 0 :body blackout
    StvnCompilationResult<StvnDocument> res = StvnCompiler.compile(source, "stage9_remediation.stvn", StvnParserConfig.DEFAULT);
    List<StvnDiagnostic> errors = res.diagnostics().stream()
        .filter(d -> d.severity() == DiagnosticSeverity.ERROR)
        .toList();

    Assertions.assertEquals(3, errors.size(), () -> "Expected exact 3 errors without cascades or blackout, got: " + errors);

    int slashStart = source.indexOf(":propulsion/trailing_slash/");
    StvnDiagnostic d0 = errors.get(0);
    Assertions.assertEquals(Optional.of("E002"), d0.canonicalCode());
    Assertions.assertEquals(Optional.of(DiagnosticBag.ERR_TRAILING_SLASH_PROHIBITED), d0.symbolicId());
    Assertions.assertEquals(slashStart, d0.startOffset());
    Assertions.assertEquals(slashStart + ":propulsion/trailing_slash/".length(), d0.endOffset());
    Assertions.assertEquals(
        "Trailing slash prohibited in :package path ':propulsion/trailing_slash/'; use ':propulsion/trailing_slash'",
        d0.message()
    );

    int slotBrandStart = source.lastIndexOf("#ATTITUDE_PULSE");
    StvnDiagnostic d1 = errors.get(1);
    Assertions.assertEquals(Optional.of("E027"), d1.canonicalCode());
    Assertions.assertEquals(Optional.of(DiagnosticBag.ERR_INCOMPATIBLE_NOMINAL_TYPE), d1.symbolicId());
    Assertions.assertEquals(slotBrandStart, d1.startOffset());
    Assertions.assertEquals(slotBrandStart + "#ATTITUDE_PULSE".length(), d1.endOffset());

    StvnDiagnostic d2 = errors.get(2);
    Assertions.assertEquals(Optional.of("E012"), d2.canonicalCode());
    Assertions.assertEquals(Optional.of(DiagnosticBag.ERR_CONSTRAINT_VIOLATION), d2.symbolicId());
  }

  @Test
  @DisplayName("Safeguard 1: Nested :package and :use directives inside trailing-slash :package validate without duplicate E030")
  void testNestedDirectivesInsideTrailingSlashPackageValidatedOnce() {
    String source = """
        {
          :meta {
            #name "nested_in_trailing"
            #kind #BODY_INCLUDE
          }
          :defs {
            :package :propulsion/trailing_slash/ {
              :package :propulsion/nested {
                :NestedType :Int
              }
              :use [ :missing/external/pkg { #strip } ]
            }
          }
          :type :Int
          :body 1
        }
        """.replace("\r\n", "\n");

    StvnCompilationResult<StvnDocument> res = StvnCompiler.compile(
        source, "nested_in_trailing.stvn_i", StvnParserConfig.DEFAULT
    );
    List<StvnDiagnostic> errors = res.diagnostics().stream()
        .filter(d -> d.severity() == DiagnosticSeverity.ERROR)
        .toList();

    Assertions.assertEquals(3, errors.size(), () -> "Expected exact 3 errors (E002, E002 nested, 1x E030), got: " + errors);
    Assertions.assertEquals(Optional.of(DiagnosticBag.ERR_TRAILING_SLASH_PROHIBITED), errors.get(0).symbolicId());
    Assertions.assertEquals(Optional.of(DiagnosticBag.ERR_NESTED_PACKAGE_PROHIBITED), errors.get(1).symbolicId());
    Assertions.assertEquals(Optional.of(DiagnosticBag.ERR_UNDEFINED_TYPE), errors.get(2).symbolicId());
  }

  @Test
  @DisplayName("B3R-03 & Safeguard 2: Nested composite #CONST brand isolation (E027), Rule A/C/D nested sum #CONST inference, and :Either E030 parity")
  void testCompositeAndNestedSumNominalBrandingContract() {
    String validSource = """
        {
          :defs {
            :PrimaryPort { #unsigned #size 16 #minIncl 1 } :Int
            :FallbackPort { #unsigned #size 16 #minIncl 1 } :Int
            :InnerOpt :Option(:PrimaryPort)
            :OuterOpt :Option(:InnerOpt)
            :InnerEither :Either(:String :PrimaryPort)
            :OuterEither :Either(:Boolean :InnerEither)
            :InnerUnion :Union(:PrimaryPort :String)
            :OuterUnion :Union(:Boolean :InnerUnion)

            #PRIMARY_VAL :PrimaryPort 443
            #FALLBACK_VAL :FallbackPort 8443
            #INNER_OPT_CONST :InnerOpt #Some 443
            #INNER_EITHER_CONST :InnerEither #Right 443
            #INNER_UNION_CONST :InnerUnion #1 443
            #TUPLE_SUM_WRAP :Tuple(:FallbackPort :FallbackPort :InnerOpt) ( #FALLBACK_VAL #FALLBACK_VAL #INNER_OPT_CONST )
          }
          :type :Tuple(
            :OuterOpt
            :OuterEither
            :OuterUnion
            :Tuple(
              :Union(:PrimaryPort :FallbackPort)
              :Either(:PrimaryPort :FallbackPort)
              :Option(:InnerOpt)
            )
          )
          :body (
            #INNER_OPT_CONST
            #INNER_EITHER_CONST
            #INNER_UNION_CONST
            #TUPLE_SUM_WRAP
          )
        }
        """;

    StvnCompilationResult<StvnDocument> validRes = StvnCompiler.compile(validSource);
    Assertions.assertFalse(validRes.hasErrors(), () -> "Expected 0 errors on valid nested sum #CONSTs, got: " + validRes.diagnostics());
    StvnValue.StvnTuple rootTuple = (StvnValue.StvnTuple) validRes.document().orElseThrow().payload().orElseThrow();
    StvnValue.StvnTuple wrappedSubTuple = (StvnValue.StvnTuple) rootTuple.elements().get(3);
    StvnValue.StvnUnion wrappedUnion = (StvnValue.StvnUnion) wrappedSubTuple.elements().get(0);
    Assertions.assertEquals(BigInteger.TWO, wrappedUnion.oneBasedBranchIndex());

    // Wrong branded Option constant supplied to :Option(:PrimaryPort) must emit E027
    String wrongOptBrandSource = """
        {
          :defs {
            :PrimaryPort { #unsigned #size 16 #minIncl 1 } :Int
            :MyOpt :Option(:PrimaryPort)
            :OtherOpt :Option(:PrimaryPort)
            #OTHER_OPT_CONST :OtherOpt #Some 443
          }
          :type :MyOpt
          :body #OTHER_OPT_CONST
        }
        """;
    StvnCompilationResult<StvnDocument> wrongOptRes = StvnCompiler.compile(wrongOptBrandSource);
    Assertions.assertEquals(1, wrongOptRes.diagnostics().size());
    Assertions.assertEquals(Optional.of("E027"), wrongOptRes.diagnostics().getFirst().canonicalCode());

    // Undeclared #CONST supplied untagged to :Either(:Int :String) must preserve E030 ERR_UNDEFINED_CONSTANT
    String undeclaredEitherSource = """
        {
          :type :Either(:Int :String)
          :body #NON_EXISTENT_CONST
        }
        """;
    StvnCompilationResult<StvnDocument> undeclaredEitherRes = StvnCompiler.compile(undeclaredEitherSource);
    Assertions.assertEquals(1, undeclaredEitherRes.diagnostics().size());
    Assertions.assertEquals(Optional.of("E030"), undeclaredEitherRes.diagnostics().getFirst().canonicalCode());
    Assertions.assertEquals(
        Optional.of(DiagnosticBag.ERR_UNDEFINED_CONSTANT),
        undeclaredEitherRes.diagnostics().getFirst().symbolicId()
    );
  }
}
