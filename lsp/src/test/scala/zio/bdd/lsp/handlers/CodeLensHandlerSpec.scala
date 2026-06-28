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
    test("a scenario outline yields a run-outline lens plus one exact lens per example row") {
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
        val labels         = scenarioLenses.map(_.getCommand.getTitle)
        val commands       = scenarioLenses.map(_.getCommand.getArguments.get(0).toString)
        assertTrue(
          // 1 feature + 1 run-outline + one per expanded Examples row (3).
          lenses.length == 5,
          scenarioLenses.length == 4,
          // The run-outline lens is first, relabelled, and keeps the glob over all rows.
          labels.head == "▶ Run outline (3 examples)",
          commands.head.contains("--scenario-name \\\"Addition table - Example *\\\" --focused"),
          // Each example has its own lens with the exact name (no glob).
          labels.contains("▶ Run Example 2"),
          commands.exists(_.contains("--scenario-name \\\"Addition table - Example 1\\\" --focused")),
          commands.exists(_.contains("--scenario-name \\\"Addition table - Example 3\\\" --focused")),
          // No bare-glob example lens leaks through.
          commands.tail.forall(c => !c.contains("Example *"))
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
