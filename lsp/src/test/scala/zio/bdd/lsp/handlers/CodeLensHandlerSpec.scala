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
    test("feature and scenario lenses run a single-quoted, shell-safe sbt command") {
      for
        index  <- ZIO.service[WorkspaceIndex]
        lenses <- CodeLensHandler.codeLenses("file:///tmp/greeting.feature", feature, index)
      yield {
        val commands = lenses.map(_.getCommand.getArguments.get(0).toString)
        assertTrue(
          lenses.length == 2,
          // The whole sbt argument is double-quoted; flag values must be single-quoted,
          // not double-quoted, or the shell would terminate the outer argument early
          // (a regression found by manually exercising the LSP against example/'s
          // simple.feature, where a scenario name produced an invalid shell command).
          commands.forall(c => !c.contains("--feature-file \"")),
          commands(0) == """sbt "testOnly * -- --feature-file '/tmp/greeting.feature'"""",
          commands(1) ==
            """sbt "testOnly * -- --feature-file '/tmp/greeting.feature' --scenario-name 'Greet a user'""""
        )
      }
    },
    test("scenario names containing a single quote are escaped, not left to break the command") {
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
          scenarioCommand ==
            "sbt \"testOnly * -- --feature-file '/tmp/greeting.feature' --scenario-name 'Greet O'\"'\"'Brien'\""
        )
      }
    }
  ).provide(WorkspaceIndex.layer)
