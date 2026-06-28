package zio.bdd.lsp.handlers

import zio.*
import zio.test.*
import zio.bdd.lsp.WorkspaceIndex

object CodeLensHandlerSpec extends ZIOSpecDefault:

  private val feature =
    """Feature: Greeting
      |  Scenario: Greet a user
      |    Given a user named World
      |""".stripMargin

  def spec = suite("CodeLensHandler")(
    test("feature and scenario lenses run a shell-safe sbt command targeting the matched suite") {
      for
        index  <- ZIO.service[WorkspaceIndex]
        lenses <- CodeLensHandler.codeLenses("file:///tmp/greeting.feature", feature, index)
      yield {
        val commands = lenses.map(_.getCommand.getArguments.get(0).toString)
        assertTrue(
          lenses.length == 2,
          // Values are wrapped in \" ... \" so sbt's QuotedString parser strips the outer
          // quotes and preserves internal spaces.  Single-quoted values are NOT stripped by
          // sbt — they arrive at the framework with literal ' characters.
          commands.forall(c => !c.contains("--feature-file '/tmp")),
          // With an empty index the selector falls back to * (all suites).
          commands(0) == "sbt \"testOnly * -- --feature-file \\\"/tmp/greeting.feature\\\"\"",
          // Scenario runs include --focused so only the targeted scenario appears in the report.
          commands(1) ==
            "sbt \"testOnly * -- --feature-file \\\"/tmp/greeting.feature\\\" --scenario-name \\\"Greet a user\\\" --focused\""
        )
      }
    },
    test("a scenario outline yields a single run-scenario lens targeting all rows") {
      val outline =
        """Feature: Math
          |  Scenario Outline: Addition table
          |    When I add <a> and <b>
          |    Then the result should be <sum>
          |
          |    Examples:
          |      | a | b | sum |
          |      | 0 | 0 | 0   |
          |      | 1 | 1 | 2   |
          |      | 2 | 3 | 5   |
          |""".stripMargin
      for
        index  <- ZIO.service[WorkspaceIndex]
        lenses <- CodeLensHandler.codeLenses("file:///tmp/math.feature", outline, index)
      yield {
        val scenarioLenses = lenses.tail // head is the feature lens
        val command        = scenarioLenses.headOption.map(_.getCommand.getArguments.get(0).toString).getOrElse("")
        assertTrue(
          // 1 feature lens + 1 scenario lens — NOT one per expanded Examples row.
          lenses.length == 2,
          scenarioLenses.length == 1,
          command.contains("--scenario-name \\\"Addition table"),
          // A trailing glob so the lens runs every expanded row, not just one.
          command.contains("*\\\" --focused")
        )
      }
    },
    test("single quotes in scenario names pass through unescaped (bash double-quoted context)") {
      val withQuote =
        """Feature: Greeting
          |  Scenario: Greet O'Brien
          |    Given a user named World
          |""".stripMargin
      for
        index  <- ZIO.service[WorkspaceIndex]
        lenses <- CodeLensHandler.codeLenses("file:///tmp/greeting.feature", withQuote, index)
      yield {
        val scenarioCommand = lenses(1).getCommand.getArguments.get(0).toString
        assertTrue(
          // Single quotes need no escaping inside a bash double-quoted string — they are literal.
          // sbt's QuotedString parser reads "Greet O'Brien" (with outer quotes stripped) → Greet O'Brien.
          scenarioCommand ==
            "sbt \"testOnly * -- --feature-file \\\"/tmp/greeting.feature\\\" --scenario-name \\\"Greet O'Brien\\\" --focused\""
        )
      }
    }
  ).provide(WorkspaceIndex.layer)
