package zio.bdd.lsp

import zio.test.*

object SuiteExtractorSpec extends ZIOSpecDefault:

  private val calculatorSuite =
    """package samples.calculator
      |import zio.bdd.core.step.ZIOSteps
      |
      |@Suite(
      |  featureDirs = Array("src/test/resources/features/calculator"),
      |  reporters = Array("pretty"),
      |  logLevel = "info"
      |)
      |object CalculatorSuite extends ZIOSteps[Any, CalcState]:
      |  Given("a fresh calculator") { ScenarioContext.update(_ => CalcState()) }
      |""".stripMargin

  def spec = suite("SuiteExtractor")(
    test("parses the suite name and featureDirs") {
      val suites = SuiteExtractor.extractFromSource(calculatorSuite)
      assertTrue(suites == List(SuiteDecl("CalculatorSuite", List("src/test/resources/features/calculator"))))
    },
    test("parses multiple featureDirs and a final class suite") {
      val src =
        """@Suite(featureDirs = Array("a/features", "b/features"))
          |final class ShoppingSuite extends ZIOSteps[Any, S]
          |""".stripMargin
      assertTrue(
        SuiteExtractor.extractFromSource(src) == List(SuiteDecl("ShoppingSuite", List("a/features", "b/features")))
      )
    },
    test("ignores source with no @Suite annotation") {
      assertTrue(SuiteExtractor.extractFromSource("object NotASuite extends ZIOSteps[Any, S]").isEmpty)
    },
    test("binds to the real object even when a comment mentions object/class tokens (#55)") {
      // The declaration search is anchored to immediately after @Suite, so a decl
      // keyword inside an intervening comment is not grabbed by mistake.
      val src =
        """@Suite(featureDirs = Array("f/x"))
          |// helper builds an internal `object Foo` and a `class Bar`
          |/* another object Baz mentioned here */
          |object RealSuite extends ZIOSteps[Any, S]
          |""".stripMargin
      assertTrue(SuiteExtractor.extractFromSource(src) == List(SuiteDecl("RealSuite", List("f/x"))))
    },
    test("binds to the immediately-following declaration (Scala annotation semantics)") {
      // @Suite annotates the next declaration; if that is a trait, that is what it
      // binds to — the extractor must not skip ahead to a later object.
      val src =
        """@Suite(featureDirs = Array("f/a"))
          |trait Helper
          |object A extends ZIOSteps[Any, S]
          |""".stripMargin
      assertTrue(SuiteExtractor.extractFromSource(src) == List(SuiteDecl("Helper", List("f/a"))))
    },
    test("drops a @Suite that is not on a class/object/trait declaration") {
      val src =
        """@Suite(featureDirs = Array("f/a"))
          |val notASuite = 42
          |""".stripMargin
      assertTrue(SuiteExtractor.extractFromSource(src).isEmpty)
    },
    test("resolves the owner by directory containment") {
      val suites = List(
        SuiteDecl("CalculatorSuite", List("src/test/resources/features/calculator")),
        SuiteDecl("ShoppingSuite", List("src/test/resources/features/shopping"))
      )
      assertTrue(
        SuiteExtractor.ownerFor("/home/u/proj/src/test/resources/features/calculator/calculator.feature", suites)
          == Some("CalculatorSuite")
      )
    },
    test("resolves the owner when featureDirs points at an exact .feature file") {
      // Real samples (HooksOrderingSuite/ParallelIsolationSuite) declare an exact file.
      val suites =
        List(SuiteDecl("HooksOrderingSuite", List("src/test/resources/features/core/hooks_ordering.feature")))
      assertTrue(
        SuiteExtractor.ownerFor("/p/src/test/resources/features/core/hooks_ordering.feature", suites)
          == Some("HooksOrderingSuite")
      )
    },
    test("returns None when no suite covers the feature") {
      val suites = List(SuiteDecl("CalculatorSuite", List("src/test/resources/features/calculator")))
      assertTrue(SuiteExtractor.ownerFor("/p/src/test/resources/features/auth/user_auth.feature", suites).isEmpty)
    },
    test("does not match on a directory prefix collision") {
      val suites = List(SuiteDecl("CalcSuite", List("features/calc")))
      assertTrue(SuiteExtractor.ownerFor("/p/features/calculator/x.feature", suites).isEmpty)
    },
    test("parses a @Suite with no featureDirs as empty (never an owner)") {
      val src =
        """@Suite(reporters = Array("pretty"), logLevel = "info")
          |object BareSuite extends ZIOSteps[Any, S]
          |""".stripMargin
      val suites = SuiteExtractor.extractFromSource(src)
      assertTrue(
        suites == List(SuiteDecl("BareSuite", Nil)),
        SuiteExtractor.ownerFor("/p/x.feature", suites).isEmpty
      )
    },
    test("parses multiple @Suite declarations in one file") {
      val src =
        """@Suite(featureDirs = Array("f/a"))
          |object A extends ZIOSteps[Any, S]
          |@Suite(featureDirs = Array("f/b"))
          |object B extends ZIOSteps[Any, S]
          |""".stripMargin
      assertTrue(
        SuiteExtractor.extractFromSource(src) == List(SuiteDecl("A", List("f/a")), SuiteDecl("B", List("f/b"))),
        SuiteExtractor.ownerFor("/p/f/b/x.feature", SuiteExtractor.extractFromSource(src)) == Some("B")
      )
    },
    test("resolves an absolute featureDir") {
      val suites = List(SuiteDecl("AbsSuite", List("/abs/features")))
      assertTrue(
        SuiteExtractor.ownerFor("/abs/features/x.feature", suites) == Some("AbsSuite"),
        SuiteExtractor.ownerFor("/other/x.feature", suites).isEmpty
      )
    }
  )
