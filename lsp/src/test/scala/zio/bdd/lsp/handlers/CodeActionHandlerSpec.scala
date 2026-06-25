package zio.bdd.lsp.handlers

import org.eclipse.lsp4j.*
import zio.*
import zio.bdd.lsp.{StepDefinition, WorkspaceIndex}
import zio.test.*

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

object CodeActionHandlerSpec extends ZIOSpecDefault:

  private val featureContent =
    """|Feature: Checkout
       |  Scenario: Happy path
       |    Given the cart has 3 items
       |    When the user checks out
       |    Then the order is confirmed
       |""".stripMargin

  private def noDiags(range: Range): CodeActionContext =
    new CodeActionContext(java.util.List.of())

  private def diag(msg: String, line: Int): Diagnostic =
    val range = new Range(new Position(line, 4), new Position(line, 30))
    new Diagnostic(range, msg, DiagnosticSeverity.Warning, "zio-bdd")

  private def diags(diags: Diagnostic*): CodeActionContext =
    new CodeActionContext(java.util.List.of(diags*))

  def spec = suite("CodeActionHandler")(
    test("returns empty list when context has no zio-bdd diagnostics") {
      val range = new Range(new Position(2, 4), new Position(2, 30))
      for
        index   <- ZIO.service[WorkspaceIndex]
        actions <- CodeActionHandler.codeActions("file:///x.feature", range, noDiags(range), featureContent, index)
      yield assertTrue(actions.isEmpty)
    },
    test("returns empty list when no step definitions are indexed") {
      val d     = diag("""No step definition found for: "the user checks out"""", 3)
      val range = d.getRange
      for
        index   <- ZIO.service[WorkspaceIndex]
        actions <- CodeActionHandler.codeActions("file:///x.feature", range, diags(d), featureContent, index)
      yield assertTrue(actions.isEmpty)
    },
    test("ignores diagnostics from other sources") {
      val d = new Diagnostic(
        new Range(new Position(2, 0), new Position(2, 10)),
        "some error",
        DiagnosticSeverity.Error,
        "other-tool"
      )
      val range = d.getRange
      for
        index   <- ZIO.service[WorkspaceIndex]
        _       <- index.indexScalaFile("/fake/Steps.scala", "Given(\"x\") { ZIO.unit }")
        actions <- CodeActionHandler.codeActions("file:///x.feature", range, diags(d), featureContent, index)
      yield assertTrue(actions.isEmpty)
    },
    test("produces a code action with WorkspaceEdit for each step-definition file") {
      val tmp = Files.createTempFile("CodeActionSpec", ".scala")
      Files.writeString(tmp, "class MySteps extends ZIOSteps[Any, Unit]:\n  Given(\"existing step\") { ZIO.unit }\n")
      val tmpPath = tmp.toAbsolutePath.toString
      val d       = diag(s"""No step definition found for: "the user checks out"""", 3)
      val range   = d.getRange
      for
        index   <- ZIO.service[WorkspaceIndex]
        _       <- index.indexScalaFile(tmpPath, Files.readString(tmp))
        actions <- CodeActionHandler.codeActions("file:///x.feature", range, diags(d), featureContent, index)
      yield assertTrue(actions.size == 1) &&
        assertTrue(actions.head.getTitle.contains(tmp.getFileName.toString)) &&
        assertTrue(actions.head.getEdit != null) &&
        assertTrue(actions.head.getKind == CodeActionKind.QuickFix)
    },
    test("skeleton text includes keyword and step text") {
      val tmp = Files.createTempFile("CodeActionSpec2", ".scala")
      Files.writeString(tmp, "class MySteps extends ZIOSteps[Any, Unit]:\n  Given(\"x\") { ZIO.unit }\n")
      val tmpPath = tmp.toAbsolutePath.toString
      val d       = diag(s"""No step definition found for: "the user checks out"""", 3)
      val range   = d.getRange
      for
        index   <- ZIO.service[WorkspaceIndex]
        _       <- index.indexScalaFile(tmpPath, Files.readString(tmp))
        actions <- CodeActionHandler.codeActions("file:///x.feature", range, diags(d), featureContent, index)
      yield
        val edit    = actions.head.getEdit.getDocumentChanges.get(0).getLeft
        val newText = edit.getEdits.get(0).getNewText
        assertTrue(newText.contains("When")) && // line 3 has "When"
        assertTrue(newText.contains("the user checks out")) &&
        assertTrue(newText.contains("ZIO.unit"))
    }
  ).provide(WorkspaceIndex.layer)
