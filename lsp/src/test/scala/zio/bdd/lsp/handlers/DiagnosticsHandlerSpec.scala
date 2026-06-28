package zio.bdd.lsp.handlers

import org.eclipse.lsp4j.DiagnosticSeverity
import zio.*
import zio.test.*
import zio.bdd.lsp.WorkspaceIndex

object DiagnosticsHandlerSpec extends ZIOSpecDefault:

  private def errorsFor(content: String): URIO[WorkspaceIndex, List[org.eclipse.lsp4j.Diagnostic]] =
    ZIO.serviceWithZIO[WorkspaceIndex] { index =>
      DiagnosticsHandler
        .computeDiagnostics("file:///fake/x.feature", content, index)
        .map(_.filter(_.getSeverity == DiagnosticSeverity.Error))
    }

  def spec = suite("DiagnosticsHandler")(
    test("flags a mis-cased step keyword as an error") {
      val content =
        """|Feature: Auth
           |  Scenario: Login
           |    Given a user
           |    then the auth service should enforce max 42 attempts
           |""".stripMargin
      for errs <- errorsFor(content)
      yield assertTrue(
        errs.length == 1,
        errs.head.getMessage.contains("\"then\""),
        errs.head.getMessage.contains("\"Then\""),
        errs.head.getRange.getStart.getLine == 3,
        errs.head.getRange.getStart.getCharacter == 4,
        errs.head.getRange.getEnd.getCharacter == 8 // highlights just the keyword "then"
      )
    },
    test("flags an ALL-CAPS keyword too") {
      val content =
        """|Feature: Auth
           |  Scenario: Login
           |    WHEN the user logs in
           |""".stripMargin
      for errs <- errorsFor(content)
      yield assertTrue(errs.length == 1, errs.head.getMessage.contains("\"When\""))
    },
    test("does not flag correctly-cased keywords or ordinary description prose") {
      val content =
        """|Feature: Auth
           |  The auth service validates credentials and tokens.
           |  Scenario: Login
           |    Given a user
           |    When the user logs in
           |    Then access is granted
           |""".stripMargin
      for errs <- errorsFor(content)
      yield assertTrue(errs.isEmpty)
    },
    test("does not flag keyword-looking text inside a doc string") {
      val content =
        "Feature: Auth\n" +
          "  Scenario: Login\n" +
          "    Given a payload\n" +
          "      \"\"\"\n" +
          "      then this is documentation, not a step\n" +
          "      \"\"\"\n"
      for errs <- errorsFor(content)
      yield assertTrue(errs.isEmpty)
    }
  ).provide(WorkspaceIndex.layer)
