package zio.bdd.lsp

import zio.*
import zio.test.*
import zio.bdd.core.step.DefaultTypedExtractor

object StepExtractorSpec extends ZIOSpecDefault:
  def spec = suite("StepExtractor")(
    test("extracts a literal-only step") {
      val src = """
                  |object S extends ZIOSteps[Any, Any]:
                  |  Given("the system is initialised") { ZIO.unit }
        """.stripMargin
      val defs = StepExtractor.extractFromSource(src, "S.scala")
      assertTrue(
        defs.length == 1,
        defs.head.keyword == "Given",
        defs.head.literals == List("the system is initialised"),
        defs.head.extractors.isEmpty,
        defs.head.displayText == "the system is initialised"
      )
    },
    test("extracts a step with one extractor") {
      val src = """
                  |Given("a user named " / string) { (name: String) => ZIO.unit }
        """.stripMargin
      val defs = StepExtractor.extractFromSource(src, "S.scala")
      assertTrue(
        defs.length == 1,
        defs.head.literals == List("a user named "),
        defs.head.extractors.map(_.name) == List("string"),
        defs.head.displayText.contains("{string}")
      )
    },
    test("extracts a step with multiple extractors") {
      val src = """
                  |When("transfer " / bigDecimal / " from " / string / " to " / string) {
                  |  (amount: BigDecimal, from: String, to: String) => ZIO.unit
                  |}
        """.stripMargin
      val defs = StepExtractor.extractFromSource(src, "S.scala")
      assertTrue(
        defs.length == 1,
        defs.head.extractors.map(_.name) == List("bigDecimal", "string", "string"),
        defs.head.literals == List("transfer ", " from ", " to ")
      )
    },
    test("normalises GivenS keyword to Given, sets isStateInjecting") {
      val src  = """GivenS("an account is ready") { s => ZIO.unit }"""
      val defs = StepExtractor.extractFromSource(src, "S.scala")
      assertTrue(
        defs.length == 1,
        defs.head.keyword == "Given",
        defs.head.isStateInjecting
      )
    },
    test("extracts oneOf extractor") {
      val src  = """Then("the item is " / oneOf("in stock", "out of stock")) { _ => ZIO.unit }"""
      val defs = StepExtractor.extractFromSource(src, "S.scala")
      assertTrue(
        defs.length == 1,
        defs.head.extractors.head.name.startsWith("oneOf"),
        defs.head.extractors.head.scalaType == "String"
      )
    },
    test("extracts optional extractor") {
      val src  = """Given("a valid body" / optional(" with simulationId")) { _ => ZIO.unit }"""
      val defs = StepExtractor.extractFromSource(src, "S.scala")
      assertTrue(
        defs.length == 1,
        defs.head.extractors.head.name.startsWith("optional"),
        defs.head.extractors.head.scalaType == "Option[String]"
      )
    },
    test("extracts table[T] extractor") {
      val src  = """Given("the following users" / table[User]) { _ => ZIO.unit }"""
      val defs = StepExtractor.extractFromSource(src, "S.scala")
      assertTrue(
        defs.length == 1,
        defs.head.extractors.head.name == "table[User]",
        defs.head.extractors.head.scalaType == "List[User]"
      )
    },
    test("extracts multiple steps from a single source") {
      val src = """
                  |Given("a") { ZIO.unit }
                  |When("b") { ZIO.unit }
                  |Then("c") { ZIO.unit }
        """.stripMargin
      val defs = StepExtractor.extractFromSource(src, "S.scala")
      assertTrue(defs.length == 3)
    },
    test("returns empty list for unparseable source") {
      val defs = StepExtractor.extractFromSource("this is not scala {{{", "bad.scala")
      assertTrue(defs.isEmpty)
    },
    test("all five base keywords are handled") {
      val src = """
                  |Given("a") { ZIO.unit }
                  |When("b") { ZIO.unit }
                  |Then("c") { ZIO.unit }
                  |And("d") { ZIO.unit }
                  |But("e") { ZIO.unit }
        """.stripMargin
      val defs = StepExtractor.extractFromSource(src, "S.scala")
      assertTrue(defs.map(_.keyword).toSet == Set("Given", "When", "Then", "And", "But"))
    },
    test("built-in extractor patterns come from core, not a hand-copy (regression for the double-pattern drift)") {
      val src     = """Given("count " / double) { (n: Double) => ZIO.unit }"""
      val defs    = StepExtractor.extractFromSource(src, "S.scala")
      val pattern = defs.head.extractors.head.pattern.r
      assertTrue(
        defs.head.extractors.head.pattern == DefaultTypedExtractor.byName("double").pattern,
        // The real pattern matches "1.5" but NOT "1.2.3" — the hand-copied pattern this
        // replaces (`[\d.+\-]+`) would have wrongly accepted "1.2.3".
        pattern.matches("1.5"),
        !pattern.matches("1.2.3")
      )
    },
    test("captures the step body and derives its reads/sets data-flow") {
      val src =
        """object CalcSteps extends ZIOSteps[Any, S]:
          |  Given("a fresh calculator") { ScenarioContext.update(_.copy(result = 0)) }
          |  When("I add " / int / " and " / int) { (a: Int, b: Int) =>
          |    Stage.put(Sum(a + b)) *> ScenarioContext.update(_.copy(result = a + b))
          |  }
          |  Then("nothing stateful here") { ZIO.unit }
        """.stripMargin
      val defs      = StepExtractor.extractFromSource(src, "S.scala")
      val givenStep = defs.find(_.keyword == "Given").get
      val whenStep  = defs.find(_.keyword == "When").get
      val thenStep  = defs.find(_.keyword == "Then").get
      assertTrue(
        givenStep.dataFlow.sets == Set[DataRef](DataRef.StateField("result")),
        whenStep.dataFlow.sets == Set[DataRef](DataRef.StateField("result"), DataRef.StageType("Sum")),
        thenStep.dataFlow.isEmpty
      )
    }
  )
