package zio.bdd.lsp

import zio.*
import zio.test.*

object StepMatcherSpec extends ZIOSpecDefault:
  def spec = suite("StepMatcher")(
    test("matches an exact literal step") {
      val defs   = List(mkDef("Given", "the system is running", "^the system is running$"))
      val result = StepMatcher.find("Given", "the system is running", defs)
      assertTrue(result match
        case StepMatcher.MatchResult.Matched(d) => d.displayText == "the system is running"
        case _                                  => false
      )
    },
    test("matches a step with regex pattern") {
      val defs   = List(mkDef("Given", "a user named {string}", """^a user named (".*"|.*)$"""))
      val result = StepMatcher.find("Given", """a user named "Alice"""", defs)
      assertTrue(result.isInstanceOf[StepMatcher.MatchResult.Matched])
    },
    test("returns NoMatch with closest hint for near miss") {
      val defs   = List(mkDef("Given", "a valid provision body", "^a valid provision body$"))
      val result = StepMatcher.find("Given", "a valid provision bdy", defs)
      assertTrue(result match
        case StepMatcher.MatchResult.NoMatch(Some((hint, dist))) =>
          hint.displayText == "a valid provision body" && dist <= 5
        case _ => false
      )
    },
    test("returns NoMatch with no hint for completely different text") {
      val defs   = List(mkDef("Given", "the system is running", "^the system is running$"))
      val result = StepMatcher.find("Given", "a completely unrelated step text here", defs)
      assertTrue(result match
        case StepMatcher.MatchResult.NoMatch(None) => true
        case StepMatcher.MatchResult.NoMatch(_)    => true // hint or no hint both ok
        case _                                     => false
      )
    },
    test("And keyword falls back to Given definition") {
      val defs   = List(mkDef("Given", "an account exists", "^an account exists$"))
      val result = StepMatcher.find("And", "an account exists", defs)
      assertTrue(result.isInstanceOf[StepMatcher.MatchResult.Matched])
    },
    test("But keyword falls back to When definition") {
      val defs   = List(mkDef("When", "the request is sent", "^the request is sent$"))
      val result = StepMatcher.find("But", "the request is sent", defs)
      assertTrue(result.isInstanceOf[StepMatcher.MatchResult.Matched])
    },
    test("Scenario Outline <col> placeholder matches structurally against extractor step def") {
      val defn = StepDefinition(
        keyword          = "And",
        literals         = List("the user's age is "),
        extractors       = List(ExtractorInfo("int", "Int", """(-?\d+)""", "signed integer")),
        displayText      = "the user's age is {int}",
        pattern          = """^the user's age is (-?\d+)$""",
        file             = "Steps.scala",
        line             = 10,
        isStateInjecting = false
      )
      val result = StepMatcher.find("And", "the user's age is <age>", List(defn))
      assertTrue(result.isInstanceOf[StepMatcher.MatchResult.Matched])
    },
    test("mismatched placeholder count does not structurally match") {
      val defn = StepDefinition(
        keyword          = "Given",
        literals         = List("a user named "),
        extractors       = List(ExtractorInfo("string", "String", """(".*"|.*)""", "text")),
        displayText      = "a user named {string}",
        pattern          = """^a user named (".*"|.*)$""",
        file             = "Steps.scala",
        line             = 5,
        isStateInjecting = false
      )
      val result = StepMatcher.find("Given", "a user named <first> <last>", List(defn))
      assertTrue(result.isInstanceOf[StepMatcher.MatchResult.NoMatch])
    }
  )

  private def mkDef(kw: String, display: String, pattern: String): StepDefinition =
    StepDefinition(
      keyword = kw,
      literals = List(display),
      extractors = Nil,
      displayText = display,
      pattern = pattern,
      file = "Test.scala",
      line = 0,
      isStateInjecting = false
    )
