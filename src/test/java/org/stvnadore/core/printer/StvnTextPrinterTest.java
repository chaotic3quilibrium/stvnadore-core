package org.stvnadore.core.printer;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ir.StvnValue;

@NullMarked
class StvnTextPrinterTest {

  private record VisualMatrix(
      String noisedInput,
      PrinterOptions options,
      String expectedCompact,
      String expectedPretty
  ) {
  }

  private void assertVisualLayout(VisualMatrix matrix) {
    var referenceAst = StvnCompiler.compile(matrix.noisedInput()).orElseThrow();
    Assertions.assertNotNull(referenceAst);

    var compactPrinter = new CompactTextPrinter(matrix.options());
    var compactStr = compactPrinter.printToString(referenceAst);
    Assertions.assertEquals(matrix.expectedCompact(), compactStr);

    var prettyPrinter = new PrettyTextPrinter(matrix.options());
    var prettyStr = prettyPrinter.printToString(referenceAst);
    Assertions.assertEquals(matrix.expectedPretty(), prettyStr);

    StvnValue roundTripAst;
    if (matrix.options().coverage() == PrinterOptions.Coverage.BODY_ONLY) {
      var envelopeOptions = new PrinterOptions(
          PrinterOptions.Coverage.ALL_SECTIONS,
          matrix.options().indentStep(),
          matrix.options().symbolStyle(),
          matrix.options().sumTypePolicy()
      );
      var envelopePrinter = new PrettyTextPrinter(envelopeOptions);
      var envelopeStr = envelopePrinter.printToString(referenceAst);
      roundTripAst = StvnCompiler.compile(envelopeStr).orElseThrow();
    } else {
      roundTripAst = StvnCompiler.compile(prettyStr).orElseThrow();
    }
    Assertions.assertEquals(referenceAst, roundTripAst);
  }

  @Test
  public void testCoverageTransitions() {
    var optionsAll = new PrinterOptions(
        PrinterOptions.Coverage.ALL_SECTIONS,
        4,
        PrinterOptions.SymbolStyle.LONG_FORM,
        PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
    );
    var matrixAll = new VisualMatrix(
        """
            {
              :type :Int32
              :body 42
            }
            """,
        optionsAll,
        "{:type :Int32 :body 42}",
        """
            {
                :type :Int32
                :body 42
            }"""
    );
    assertVisualLayout(matrixAll);

    var optionsBody = new PrinterOptions(
        PrinterOptions.Coverage.BODY_ONLY,
        4,
        PrinterOptions.SymbolStyle.LONG_FORM,
        PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
    );
    var matrixBody = new VisualMatrix(
        """
            {
              :type :Int32
              :body 42
            }
            """,
        optionsBody,
        "42",
        "42"
    );
    assertVisualLayout(matrixBody);

    var matrixMapAll = new VisualMatrix(
        """
            {
              :type :Map( :String :Int32 )
              :body {
                [ "a" 1 ]
              }
            }
            """,
        optionsAll,
        "{:type :Map(:String :Int32) :body {[\"a\" 1]}}",
        """
            {
                :type :Map(:String :Int32)
                :body {
                    ["a" 1]
                }
            }"""
    );
    assertVisualLayout(matrixMapAll);

    var matrixMapBody = new VisualMatrix(
        """
            {
              :type :Map( :String :Int32 )
              :body {
                [ "a" 1 ]
              }
            }
            """,
        optionsBody,
        "{[\"a\" 1]}",
        """
            {
                ["a" 1]
            }"""
    );
    assertVisualLayout(matrixMapBody);
  }

  @Test
  public void testIndentationScaling() {
    var options2 = new PrinterOptions(
        PrinterOptions.Coverage.ALL_SECTIONS,
        2,
        PrinterOptions.SymbolStyle.LONG_FORM,
        PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
    );
    var matrix2 = new VisualMatrix(
        """
            {
              :type :Map( :String :Int32 )
              :body {
                [ "x" 10 ]
                [ "y" 20 ]
              }
            }
            """,
        options2,
        "{:type :Map(:String :Int32) :body {[\"x\" 10] [\"y\" 20]}}",
        """
            {
              :type :Map(:String :Int32)
              :body {
                ["x" 10]
                ["y" 20]
              }
            }"""
    );
    assertVisualLayout(matrix2);

    var options4 = new PrinterOptions(
        PrinterOptions.Coverage.ALL_SECTIONS,
        4,
        PrinterOptions.SymbolStyle.LONG_FORM,
        PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
    );
    var matrix4 = new VisualMatrix(
        """
            {
              :type :Map( :String :Int32 )
              :body {
                [ "x" 10 ]
                [ "y" 20 ]
              }
            }
            """,
        options4,
        "{:type :Map(:String :Int32) :body {[\"x\" 10] [\"y\" 20]}}",
        """
            {
                :type :Map(:String :Int32)
                :body {
                    ["x" 10]
                    ["y" 20]
                }
            }"""
    );
    assertVisualLayout(matrix4);
  }

  @Test
  public void testDensityGridsBooleansAndOptions() {
    var optBoolLong = new PrinterOptions(
        PrinterOptions.Coverage.BODY_ONLY,
        4,
        PrinterOptions.SymbolStyle.LONG_FORM,
        PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
    );
    var matrixBoolLong = new VisualMatrix(
        """
            {
              :type :Boolean
              :body #TRUE
            }
            """,
        optBoolLong,
        "#TRUE",
        "#TRUE"
    );
    assertVisualLayout(matrixBoolLong);

    var optBoolShort = new PrinterOptions(
        PrinterOptions.Coverage.BODY_ONLY,
        4,
        PrinterOptions.SymbolStyle.SHORT_FORM,
        PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
    );
    var matrixBoolShort = new VisualMatrix(
        """
            {
              :type :Boolean
              :body #TRUE
            }
            """,
        optBoolShort,
        "#T",
        "#T"
    );
    assertVisualLayout(matrixBoolShort);

    var optOptInferred = new PrinterOptions(
        PrinterOptions.Coverage.BODY_ONLY,
        4,
        PrinterOptions.SymbolStyle.LONG_FORM,
        PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
    );
    var matrixOptInferred = new VisualMatrix(
        """
            {
              :type :Option( :Int32 )
              :body 42
            }
            """,
        optOptInferred,
        "42",
        "42"
    );
    assertVisualLayout(matrixOptInferred);

    var optOptExplicitLong = new PrinterOptions(
        PrinterOptions.Coverage.BODY_ONLY,
        4,
        PrinterOptions.SymbolStyle.LONG_FORM,
        PrinterOptions.SumTypePolicy.FORCE_EXPLICIT
    );
    var matrixOptExplicitLong = new VisualMatrix(
        """
            {
              :type :Option( :Int32 )
              :body 42
            }
            """,
        optOptExplicitLong,
        "#Some 42",
        "#Some 42"
    );
    assertVisualLayout(matrixOptExplicitLong);

    var optOptExplicitShort = new PrinterOptions(
        PrinterOptions.Coverage.BODY_ONLY,
        4,
        PrinterOptions.SymbolStyle.SHORT_FORM,
        PrinterOptions.SumTypePolicy.FORCE_EXPLICIT
    );
    var matrixOptExplicitShort = new VisualMatrix(
        """
            {
              :type :Option( :Int32 )
              :body 42
            }
            """,
        optOptExplicitShort,
        "#S 42",
        "#S 42"
    );
    assertVisualLayout(matrixOptExplicitShort);

    var matrixNoneLong = new VisualMatrix(
        """
            {
              :type :Option( :Int32 )
              :body #None
            }
            """,
        optOptInferred,
        "#None",
        "#None"
    );
    assertVisualLayout(matrixNoneLong);

    var matrixNoneShort = new VisualMatrix(
        """
            {
              :type :Option( :Int32 )
              :body #None
            }
            """,
        optOptExplicitShort,
        "#N",
        "#N"
    );
    assertVisualLayout(matrixNoneShort);
  }

  @Test
  public void testDensityGridsEithers() {
    var optOptInferred = new PrinterOptions(
        PrinterOptions.Coverage.BODY_ONLY,
        4,
        PrinterOptions.SymbolStyle.LONG_FORM,
        PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
    );
    var optOptExplicitLong = new PrinterOptions(
        PrinterOptions.Coverage.BODY_ONLY,
        4,
        PrinterOptions.SymbolStyle.LONG_FORM,
        PrinterOptions.SumTypePolicy.FORCE_EXPLICIT
    );
    var optOptExplicitShort = new PrinterOptions(
        PrinterOptions.Coverage.BODY_ONLY,
        4,
        PrinterOptions.SymbolStyle.SHORT_FORM,
        PrinterOptions.SumTypePolicy.FORCE_EXPLICIT
    );

    var matrixEitherInferred = new VisualMatrix(
        """
            {
              :type :Either( :Int32 :String )
              :body "hello"
            }
            """,
        optOptInferred,
        "\"hello\"",
        "\"hello\""
    );
    assertVisualLayout(matrixEitherInferred);

    var matrixEitherExplicitLong = new VisualMatrix(
        """
            {
              :type :Either( :Int32 :String )
              :body "hello"
            }
            """,
        optOptExplicitLong,
        "#Right \"hello\"",
        "#Right \"hello\""
    );
    assertVisualLayout(matrixEitherExplicitLong);

    var matrixEitherExplicitShort = new VisualMatrix(
        """
            {
              :type :Either( :Int32 :String )
              :body "hello"
            }
            """,
        optOptExplicitShort,
        "#R \"hello\"",
        "#R \"hello\""
    );
    assertVisualLayout(matrixEitherExplicitShort);

    var matrixLeftLong = new VisualMatrix(
        """
            {
              :type :Either( :Int32 :String )
              :body #Left 42
            }
            """,
        optOptInferred,
        "#Left 42",
        "#Left 42"
    );
    assertVisualLayout(matrixLeftLong);

    var matrixLeftShort = new VisualMatrix(
        """
            {
              :type :Either( :Int32 :String )
              :body #Left 42
            }
            """,
        optOptExplicitShort,
        "#L 42",
        "#L 42"
    );
    assertVisualLayout(matrixLeftShort);
  }

  @Test
  public void testRuleCAmbigGuards() {
    var optLong = new PrinterOptions(
        PrinterOptions.Coverage.ALL_SECTIONS,
        4,
        PrinterOptions.SymbolStyle.LONG_FORM,
        PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
    );
    var matrixEnumAmbiguity = new VisualMatrix(
        """
            {
              :type :Seq( :Option( :Enum[ #None #N #Some #S #Left #L #Right #R #TRUE #T #FALSE #F ] ) )
              :body [
                #Left #L
                #Right #R
                #TRUE #T
                #FALSE #F
              ]
            }
            """,
        optLong,
        "{:type :Seq(:Option(:Enum[#None #N #Some #S #Left #L #Right #R #TRUE #T #FALSE #F])) :body [#Some #Left #Some #L #Some #Right #Some #R #Some #TRUE #Some #T #Some #FALSE #Some #F]}",
        """
            {
                :type :Seq(:Option(:Enum[#None #N #Some #S #Left #L #Right #R #TRUE #T #FALSE #F]))
                :body [#Some #Left #Some #L #Some #Right #Some #R #Some #TRUE #Some #T #Some #FALSE #Some #F]
            }"""
    );
    assertVisualLayout(matrixEnumAmbiguity);

    var matrixStringAmbiguity = new VisualMatrix(
        """
            {
              :type :Either( :Int8 :String )
              :body #Right "#Left"
            }
            """,
        optLong,
        """
            {:type :Either(:Int8 :String) :body #Right "#Left"}""",
        """
            {
                :type :Either(:Int8 :String)
                :body #Right "#Left"
            }"""
    );
    assertVisualLayout(matrixStringAmbiguity);

    var matrixStringAmbiguityShort = new VisualMatrix(
        """
            {
              :type :Either( :Int8 :String )
              :body #Right "#L"
            }
            """,
        new PrinterOptions(
            PrinterOptions.Coverage.ALL_SECTIONS,
            4,
            PrinterOptions.SymbolStyle.SHORT_FORM,
            PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
        ),
        """
            {:type :Either(:Int8 :String) :body #R "#L"}""",
        """
            {
                :type :Either(:Int8 :String)
                :body #R "#L"
            }"""
    );
    assertVisualLayout(matrixStringAmbiguityShort);
  }

  @Test
  public void testOriginalStringsAndCollections() {
    var optDefault = new PrinterOptions(
        PrinterOptions.Coverage.ALL_SECTIONS,
        4,
        PrinterOptions.SymbolStyle.LONG_FORM,
        PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
    );

    var matrixBlockStr = new VisualMatrix(
        """
            {
              :type :String
              :body ""\"
                  Hello
                  World
                  ""\"
            }
            """,
        optDefault,
        """
            {:type :String :body ""\"
            Hello
            World
            ""\"}""",
        """
            {
                :type :String
                :body ""\"
            Hello
            World
            ""\"
            }"""
    );
    assertVisualLayout(matrixBlockStr);

    var matrixFencedStr = new VisualMatrix(
        """
            {
              :type :String
              :body ""\"->[CUSTOM_FENCE]
                  Nested fenced content
                  [CUSTOM_FENCE]""\"
            }
            """,
        optDefault,
        """
            {:type :String :body ""\"->[CUSTOM_FENCE]
            Nested fenced content
            [CUSTOM_FENCE]""\"}""",
        """
            {
                :type :String
                :body ""\"->[CUSTOM_FENCE]
            Nested fenced content
            [CUSTOM_FENCE]""\"
            }"""
    );
    assertVisualLayout(matrixFencedStr);

    var matrixCollections = new VisualMatrix(
        """
            {
              :type :Tuple( :Int32 :String :Boolean )
              :body ( 42 "Answer" #FALSE )
            }
            """,
        optDefault,
        """
            {:type :Tuple(:Int32 :String :Boolean) :body (42 "Answer" #FALSE)}""",
        """
            {
                :type :Tuple(:Int32 :String :Boolean)
                :body (42 "Answer" #FALSE)
            }"""
    );
    assertVisualLayout(matrixCollections);
  }

  @Test
  public void testOriginalAliasAndConstraints() {
    var optDefault = new PrinterOptions(
        PrinterOptions.Coverage.ALL_SECTIONS,
        4,
        PrinterOptions.SymbolStyle.LONG_FORM,
        PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
    );

    var matrixAlias = new VisualMatrix(
        """
            {
              :defs {
                :age { #minIncl 0 #maxIncl 120 } :Int8
              }
              :type :age
              :body 42
            }
            """,
        optDefault,
        "{:defs {:age {#minIncl 0 #maxIncl 120} :Int8} :type :age :body 42}",
        """
            {
                :defs {
                    :age {
                        #minIncl 0
                        #maxIncl 120
                    } :Int8
                }
                :type :age
                :body 42
            }"""
    );
    assertVisualLayout(matrixAlias);
  }

  @Test
  public void testExplicitPreserveIndentTrue() {
    var optDefault = new PrinterOptions(
        PrinterOptions.Coverage.ALL_SECTIONS,
        4,
        PrinterOptions.SymbolStyle.LONG_FORM,
        PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
    );

    var matrixPreserved = new VisualMatrix(
        """
            {
              :defs {
                :preservedBlock { #preserveIndent #TRUE } :String
              }
              :type :preservedBlock
              :body ""\"
                  Line 1
                    Nested Indent
                  Line 3
            ""\"
            }""",
        optDefault,
        """
            {:defs {:preservedBlock {#preserveIndent #TRUE} :String} :type :preservedBlock :body ""\"
                  Line 1
                    Nested Indent
                  Line 3
            ""\"}""",
        """
            {
                :defs {
                    :preservedBlock {#preserveIndent #TRUE} :String
                }
                :type :preservedBlock
                :body ""\"
                  Line 1
                    Nested Indent
                  Line 3
            ""\"
            }"""
    );
    assertVisualLayout(matrixPreserved);
  }

  @Test
  public void testExplicitTraitOverrides() {
    var optDefault = new PrinterOptions(
        PrinterOptions.Coverage.ALL_SECTIONS,
        4,
        PrinterOptions.SymbolStyle.LONG_FORM,
        PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
    );

    var matrixFloat = new VisualMatrix(
        """
            {
              :defs {
                :myFloat { #equatable #TRUE } :Float32
              }
              :type :myFloat
              :body 3.14
            }
            """,
        optDefault,
        "{:defs {:myFloat {#equatable #TRUE} :Float32} :type :myFloat :body 3.14}",
        """
            {
                :defs {
                    :myFloat {#equatable #TRUE} :Float32
                }
                :type :myFloat
                :body 3.14
            }"""
    );
    assertVisualLayout(matrixFloat);

    var matrixSeq = new VisualMatrix(
        """
            {
              :defs {
                :mySeq { #comparable #FALSE } :Seq( :Float32 )
              }
              :type :mySeq
              :body [ 1.0 2.0 ]
            }
            """,
        optDefault,
        "{:defs {:mySeq {#comparable #FALSE} :Seq(:Float32)} :type :mySeq :body [1.0 2.0]}",
        """
            {
                :defs {
                    :mySeq {#comparable #FALSE} :Seq(:Float32)
                }
                :type :mySeq
                :body [1.0 2.0]
            }"""
    );
    assertVisualLayout(matrixSeq);
  }

  @Test
  public void testGlobalMetadataMinimization() {
    var optDefault = new PrinterOptions(
        PrinterOptions.Coverage.ALL_SECTIONS,
        4,
        PrinterOptions.SymbolStyle.LONG_FORM,
        PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
    );

    var matrixRegex = new VisualMatrix(
        """
            {
              :defs {
                :code { #regex "^[A-Z]{3}$" } :String
              }
              :type :code
              :body "STV"
            }
            """,
        optDefault,
        "{:defs {:code {#regex \"^[A-Z]{3}$\"} :String} :type :code :body \"STV\"}",
        """
            {
                :defs {
                    :code {#regex "^[A-Z]{3}$"} :String
                }
                :type :code
                :body "STV"
            }"""
    );
    assertVisualLayout(matrixRegex);
  }

  @Test
  public void testLayeredAliasOverrideChains() {
    var optDefault = new PrinterOptions(
        PrinterOptions.Coverage.ALL_SECTIONS,
        4,
        PrinterOptions.SymbolStyle.LONG_FORM,
        PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
    );

    var matrixChain = new VisualMatrix(
        """
            {
              :defs {
                :baseInt { #minIncl 10 } :Int32
                :restrictedInt { #maxIncl 100 #comparable #FALSE } :baseInt
                :finalInt { #comparable #TRUE } :restrictedInt
              }
              :type :finalInt
              :body 42
            }
            """,
        optDefault,
        "{:defs {:baseInt {#minIncl 10} :Int32 :restrictedInt {#maxIncl 100 #comparable #FALSE} :baseInt :finalInt {#comparable #TRUE} :restrictedInt} :type :finalInt :body 42}",
        """
            {
                :defs {
                    :baseInt {#minIncl 10} :Int32
                    :restrictedInt {
                        #maxIncl 100
                        #comparable #FALSE
                    } :baseInt
                    :finalInt {#comparable #TRUE} :restrictedInt
                }
                :type :finalInt
                :body 42
            }"""
    );
    assertVisualLayout(matrixChain);
  }

  @Test
  public void testCanonicalWriterImmunityToSymbolStyle() {
    var inputs = new String[] {
      "{ :type :Boolean :body #T }",
      "{ :type :Boolean :body #TRUE }",
      "{ :type :Boolean :body #F }",
      "{ :type :Boolean :body #FALSE }",
      "{ :type :Option(:Boolean) :body #N }",
      "{ :type :Option(:Boolean) :body #None }",
      "{ :type :Either(:Int32 :String) :body #L 1 }",
      "{ :type :Either(:Int32 :String) :body #Left 1 }",
      "{ :type :Either(:Int32 :String) :body #Right \"a\" }",
      "{ :type :Either(:Int32 :String) :body #R \"a\" }",
      "{ :type :Option(:Enum[#Y #N]) :body #Some #N }",
      "{ :type :Option(:Enum[#Y #N]) :body #S #N }"
    };

    var expectedCanonical = new String[] {
      "{:type :Boolean :body #TRUE}",
      "{:type :Boolean :body #TRUE}",
      "{:type :Boolean :body #FALSE}",
      "{:type :Boolean :body #FALSE}",
      "{:type :Option(:Boolean):body #None}",
      "{:type :Option(:Boolean):body #None}",
      "{:type :Either(:Int32 :String):body #Left 1}",
      "{:type :Either(:Int32 :String):body #Left 1}",
      "{:type :Either(:Int32 :String):body \"a\"}",
      "{:type :Either(:Int32 :String):body \"a\"}",
      "{:type :Option(:Enum[#Y #N]):body #Some #N}",
      "{:type :Option(:Enum[#Y #N]):body #Some #N}"
    };

    for (var i = 0; i < inputs.length; i++) {
      var ast = StvnCompiler.compile(inputs[i]).orElseThrow();
      Assertions.assertNotNull(ast);
      var canonical = StvnCompiler.toCanonicalString(ast);
      Assertions.assertEquals(expectedCanonical[i], canonical);
    }
  }

  @Test
  public void testPrinterTokenMinimizationMandated() {
    var optShort = new PrinterOptions(
        PrinterOptions.Coverage.ALL_SECTIONS,
        4,
        PrinterOptions.SymbolStyle.SHORT_FORM,
        PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
    );

    var matrix = new VisualMatrix(
        """
        {
          :defs {
            :myInt :Int32
          }
          :type :myInt
          :body 42
        }
        """,
        optShort,
        "{:defs {:myInt :Int32} :type :myInt :body 42}",
        """
        {
            :defs {
                :myInt :Int32
            }
            :type :myInt
            :body 42
        }"""
    );
    assertVisualLayout(matrix);
  }

  @Test
  public void testShortFormMetaTokensExplicitOverrides() {
    var optShort = new PrinterOptions(
        PrinterOptions.Coverage.ALL_SECTIONS,
        4,
        PrinterOptions.SymbolStyle.SHORT_FORM,
        PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
    );

    var matrix = new VisualMatrix(
        """
        {
          :defs {
            :myInt { #equatable #TRUE #comparable #FALSE } :Int32
          }
          :type :myInt
          :body 42
        }
        """,
        optShort,
        "{:defs {:myInt {#equatable #T #comparable #F} :Int32} :type :myInt :body 42}",
        """
        {
            :defs {
                :myInt {
                    #equatable #T
                    #comparable #F
                } :Int32
            }
            :type :myInt
            :body 42
        }"""
    );
    assertVisualLayout(matrix);
  }

  @Test
  public void testShortFormSumTypePayloads() {
    var optShortExplicit = new PrinterOptions(
        PrinterOptions.Coverage.ALL_SECTIONS,
        4,
        PrinterOptions.SymbolStyle.SHORT_FORM,
        PrinterOptions.SumTypePolicy.FORCE_EXPLICIT
    );

    var optShortInferred = new PrinterOptions(
        PrinterOptions.Coverage.ALL_SECTIONS,
        4,
        PrinterOptions.SymbolStyle.SHORT_FORM,
        PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
    );

    // 1. Option Data Payloads
    var matrixOptionExplicit = new VisualMatrix(
        """
        {
          :type :Tuple( :Option( :String ) :Option( :String ) )
          :body ( #None #Some "value" )
        }
        """,
        optShortExplicit,
        "{:type :Tuple(:Option(:String) :Option(:String)) :body (#N #S \"value\")}",
        """
        {
            :type :Tuple(:Option(:String) :Option(:String))
            :body (#N #S "value")
        }"""
    );
    assertVisualLayout(matrixOptionExplicit);

    var matrixOptionInferred = new VisualMatrix(
        """
        {
          :type :Tuple( :Option( :String ) :Option( :String ) )
          :body ( #None #Some "value" )
        }
        """,
        optShortInferred,
        "{:type :Tuple(:Option(:String) :Option(:String)) :body (#N \"value\")}",
        """
        {
            :type :Tuple(:Option(:String) :Option(:String))
            :body (#N "value")
        }"""
    );
    assertVisualLayout(matrixOptionInferred);

    // 2. Either Data Payloads
    var matrixEitherExplicit = new VisualMatrix(
        """
        {
          :type :Tuple( :Either( :Int32 :String ) :Either( :Int32 :String ) )
          :body ( #Left 42 #Right "hello" )
        }
        """,
        optShortExplicit,
        "{:type :Tuple(:Either(:Int32 :String) :Either(:Int32 :String)) :body (#L 42 #R \"hello\")}",
        """
        {
            :type :Tuple(:Either(:Int32 :String) :Either(:Int32 :String))
            :body (#L 42 #R "hello")
        }"""
    );
    assertVisualLayout(matrixEitherExplicit);

    var matrixEitherInferred = new VisualMatrix(
        """
        {
          :type :Tuple( :Either( :Int32 :String ) :Either( :Int32 :String ) )
          :body ( #Left 42 #Right "hello" )
        }
        """,
        optShortInferred,
        "{:type :Tuple(:Either(:Int32 :String) :Either(:Int32 :String)) :body (#L 42 \"hello\")}",
        """
        {
            :type :Tuple(:Either(:Int32 :String) :Either(:Int32 :String))
            :body (#L 42 "hello")
        }"""
    );
    assertVisualLayout(matrixEitherInferred);
  }

  @Test
  void testTripartiteDateTimeFormatting() {
    var options = new PrinterOptions(
        PrinterOptions.Coverage.ALL_SECTIONS,
        4,
        PrinterOptions.SymbolStyle.LONG_FORM,
        PrinterOptions.SumTypePolicy.HAPPY_PATH_INFERRED
    );
    var matrix = new VisualMatrix(
        """
        {
          :type :Tuple( :DateTimeOffset :DateTimeZoned :DateTimeAudited )
          :body (
            "2026-03-15T08:00:00-05:00"
            "2026-03-15T08:00:00[America/Chicago]"
            "2026-03-15T08:00:00-05:00[America/Chicago]"
          )
        }
        """,
        options,
        "{:type :Tuple(:DateTimeOffset :DateTimeZoned :DateTimeAudited) :body (\"2026-03-15T08:00:00-05:00\" \"2026-03-15T08:00:00[America/Chicago]\" \"2026-03-15T08:00:00-05:00[America/Chicago]\")}",
        """
        {
            :type :Tuple(:DateTimeOffset :DateTimeZoned :DateTimeAudited)
            :body ("2026-03-15T08:00:00-05:00" "2026-03-15T08:00:00[America/Chicago]" "2026-03-15T08:00:00-05:00[America/Chicago]")
        }"""
    );
    assertVisualLayout(matrix);
  }
}

