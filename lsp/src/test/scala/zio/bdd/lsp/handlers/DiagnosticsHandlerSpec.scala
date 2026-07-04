package zio.bdd.lsp.handlers

import org.eclipse.lsp4j.DiagnosticSeverity
import zio.*
import zio.test.*
import zio.bdd.lsp.{RuntimeMockSummary, WorkspaceIndex}

object DiagnosticsHandlerSpec extends ZIOSpecDefault:

  private def errorsFor(content: String): URIO[WorkspaceIndex, List[org.eclipse.lsp4j.Diagnostic]] =
    ZIO.serviceWithZIO[WorkspaceIndex] { index =>
      DiagnosticsHandler
        .computeDiagnostics("file:///fake/x.feature", content, index)
        .map(_.filter(_.getSeverity == DiagnosticSeverity.Error))
    }

  // Only the @mock catalog diagnostics — isolated from step/keyword diagnostics.
  private def mockDiagsFor(
    content: String,
    catalog: List[String]
  ): URIO[WorkspaceIndex, List[org.eclipse.lsp4j.Diagnostic]] =
    ZIO.serviceWithZIO[WorkspaceIndex] { index =>
      index.setMocks(catalog.map(n => RuntimeMockSummary(n, "Dsl"))) *>
        DiagnosticsHandler
          .computeDiagnostics("file:///fake/x.feature", content, index)
          .map(_.filter(_.getMessage.contains("catalog entry")))
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
    },
    // ── @mock(name) unknown-catalog-name diagnostics ────────────────────────
    test("flags a @mock(name) whose name is not in the discovered catalog") {
      val content =
        """|@mock(unknownSvc)
           |Feature: Auth
           |  Scenario: Login
           |    Given a user
           |""".stripMargin
      for diags <- mockDiagsFor(content, List("userService", "paymentGateway"))
      yield assertTrue(
        diags.length == 1,
        diags.head.getMessage.contains("no catalog entry named 'unknownSvc'"),
        diags.head.getSeverity == DiagnosticSeverity.Warning,
        // range highlights just the name, not the whole @mock(...) tag
        diags.head.getRange.getStart.getLine == 0,
        diags.head.getRange.getStart.getCharacter == 6,
        diags.head.getRange.getEnd.getCharacter == 16
      )
    },
    test("does not flag a @mock(name) whose name is in the catalog") {
      val content =
        """|@mock(userService)
           |Feature: Auth
           |  Scenario: Login
           |    Given a user
           |""".stripMargin
      for diags <- mockDiagsFor(content, List("userService", "paymentGateway"))
      yield assertTrue(diags.isEmpty)
    },
    test("flags each unknown name in a multi-name @mock(a, b, c) tag, leaving known ones alone") {
      val content =
        """|@mock(userService, bad1, bad2)
           |Feature: Auth
           |  Scenario: Login
           |    Given a user
           |""".stripMargin
      for diags <- mockDiagsFor(content, List("userService"))
      yield assertTrue(
        diags.length == 2,
        diags.exists(_.getMessage.contains("'bad1'")),
        diags.exists(_.getMessage.contains("'bad2'"))
      )
    },
    test("never flags @mock names when the catalog is empty (discovery yielded nothing — no false positives)") {
      val content =
        """|@mock(anything)
           |Feature: Auth
           |  Scenario: Login
           |    Given a user
           |""".stripMargin
      for diags <- mockDiagsFor(content, Nil)
      yield assertTrue(diags.isEmpty)
    }
  ).provide(WorkspaceIndex.layer)
