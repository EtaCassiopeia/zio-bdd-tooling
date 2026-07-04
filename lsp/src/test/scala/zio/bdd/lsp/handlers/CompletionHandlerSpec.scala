package zio.bdd.lsp.handlers

import org.eclipse.lsp4j.Position
import zio.*
import zio.test.*
import zio.bdd.lsp.{RuntimeMockSummary, WorkspaceIndex}

object CompletionHandlerSpec extends ZIOSpecDefault:

  private val catalog = List(RuntimeMockSummary("userService", "Dsl"), RuntimeMockSummary("paymentGateway", "Json"))

  // Complete on a single-line .feature document at the end of `line`.
  private def completeOn(line: String): URIO[WorkspaceIndex, List[org.eclipse.lsp4j.CompletionItem]] =
    ZIO.serviceWithZIO[WorkspaceIndex] { index =>
      index.setMocks(catalog) *>
        CompletionHandler.complete("file:///x.feature", new Position(0, line.length), line, index)
    }

  def spec = suite("CompletionHandler")(
    test("offers discovered catalog names inside a @mock( tag, inserting the bare name") {
      for items <- completeOn("@mock(")
      yield
        val labels = items.map(_.getLabel)
        assertTrue(
          labels.contains("userService"),
          labels.contains("paymentGateway"),
          items.forall(i => i.getInsertText == null || i.getInsertText == i.getLabel),
          // the source kind is surfaced as detail
          items.find(_.getLabel == "userService").exists(_.getDetail.contains("Dsl"))
        )
    },
    test("offers catalog names after a comma in a multi-name @mock(a, tag") {
      for items <- completeOn("@mock(userService, ")
      yield assertTrue(items.map(_.getLabel).contains("paymentGateway"))
    },
    test("a plain @ line still offers tag completion, not catalog names") {
      for items <- completeOn("@")
      yield
        val texts = items.map(i => Option(i.getInsertText).getOrElse(i.getLabel))
        assertTrue(
          items.exists(_.getLabel.contains("mock")), // @mock(name) builtin tag still offered
          !texts.contains("userService")             // but not catalog names outside @mock(...)
        )
    }
  ).provide(WorkspaceIndex.layer)
