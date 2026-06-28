package zio.bdd.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either as JEither
import zio.*
import zio.test.*

import scala.jdk.CollectionConverters.*

// Integration tests for ZIOBddServer.
//
// Drive the server through its public LSP handler methods (definition, hover,
// completion, codeAction) rather than raw JSON-RPC bytes. This covers the full
// handler-chain including the ZIO ↔ CompletableFuture bridge, the WorkspaceIndex
// dependency, and the `ready` gate.
object ZIOBddServerSpec extends ZIOSpecDefault:

  private val stepsScala =
    """|Given("the cart has " / int / " items") { n =>
       |  ZIO.unit
       |}
       |When("the user checks out") {
       |  ZIO.unit
       |}
       |Then("the order is confirmed") {
       |  ZIO.unit
       |}
       |""".stripMargin

  private val featureContent =
    """|Feature: Checkout
       |  Scenario: Happy path
       |    Given the cart has 3 items
       |    When the user checks out
       |    Then the order is confirmed
       |    When the user cancels
       |""".stripMargin

  private val stepsFile   = "/fake/CheckoutSteps.scala"
  private val featureFile = "/fake/checkout.feature"
  private val featureUri  = s"file://$featureFile"

  private def makeServer: ZIO[WorkspaceIndex & ZIOBddServer, Nothing, ZIOBddServer] =
    for
      index  <- ZIO.service[WorkspaceIndex]
      _      <- index.indexScalaFile(stepsFile, stepsScala)
      _      <- index.indexFeatureFile(featureFile, featureContent)
      server <- ZIO.service[ZIOBddServer]
      // Populate the content cache so currentContent(uri) returns the feature text.
      _ <- server.putContent(featureUri, featureContent)
      // Complete the `ready` gate immediately — no workspace scan needed in tests.
      _ <- server.forceReady
    yield server

  def spec = suite("ZIOBddServer")(
    suite("definition")(
      test("resolves a matched step to its source file:line") {
        for
          server <- makeServer
          params = new DefinitionParams(
                     new TextDocumentIdentifier(featureUri),
                     new Position(3, 10) // "When the user checks out" (line 3, 0-based)
                   )
          result <- ZIO.fromCompletableFuture(server.definition(params))
        yield
          val links = result.getRight.asScala.toList
          assertTrue(links.nonEmpty) &&
          assertTrue(links.head.getTargetUri.contains("CheckoutSteps.scala"))
      },
      test("returns empty list for an unmatched step") {
        for
          server <- makeServer
          params = new DefinitionParams(
                     new TextDocumentIdentifier(featureUri),
                     new Position(5, 10) // "When the user cancels" — no definition
                   )
          result <- ZIO.fromCompletableFuture(server.definition(params))
        yield
          val links = result.getRight.asScala.toList
          assertTrue(links.isEmpty)
      }
    ),
    suite("hover")(
      test("returns hover text for a matched step") {
        for
          server <- makeServer
          params = new HoverParams(
                     new TextDocumentIdentifier(featureUri),
                     new Position(2, 10) // "Given the cart has 3 items"
                   )
          result <- ZIO.fromCompletableFuture(server.hover(params))
        yield assertTrue(
          result != null,
          result.getContents.getRight.getValue.contains("CheckoutSteps.scala")
        )
      },
      test("returns null for an unmatched step") {
        for
          server <- makeServer
          params = new HoverParams(
                     new TextDocumentIdentifier(featureUri),
                     new Position(5, 10) // "When the user cancels"
                   )
          result <- ZIO.fromCompletableFuture(server.hover(params))
        yield assertTrue(result == null)
      }
    ),
    suite("completion")(
      test("offers step completions when cursor is on an incomplete step line") {
        val featureWithPartial =
          """|Feature: F
             |  Scenario: S
             |    Given the cart has 3 items
             |    When
             |""".stripMargin
        val partialUri = "file:///fake/partial.feature"
        for
          server <- makeServer
          index  <- ZIO.service[WorkspaceIndex]
          _      <- index.indexFeatureFile("/fake/partial.feature", featureWithPartial)
          _      <- server.putContent(partialUri, featureWithPartial)
          params = new CompletionParams(
                     new TextDocumentIdentifier(partialUri),
                     new Position(3, 8) // inside "When"
                   )
          result <- ZIO.fromCompletableFuture(server.completion(params))
        yield
          val items = result.getLeft.asScala.toList
          assertTrue(items.nonEmpty)
      }
    ),
    suite("tag completion")(
      test("collects tags from indexed feature files (allTags)") {
        val tagged =
          """|@smoke @checkout
             |Feature: Checkout
             |  @fast
             |  Scenario: Happy path
             |    Given the cart has 3 items
             |""".stripMargin
        for
          _     <- makeServer
          index <- ZIO.service[WorkspaceIndex]
          _     <- index.indexFeatureFile("/fake/tagged.feature", tagged)
          tags  <- index.allTags
        yield assertTrue(tags.contains("smoke"), tags.contains("checkout"), tags.contains("fast"))
      },
      test("does not mistake an @ inside step text for a tag") {
        val withAt =
          """|Feature: F
             |  Scenario: S
             |    Given the user mentions user@host
             |""".stripMargin
        for
          _     <- makeServer
          index <- ZIO.service[WorkspaceIndex]
          _     <- index.indexFeatureFile("/fake/atstep.feature", withAt)
          tags  <- index.allTags
        yield assertTrue(!tags.contains("host"))
      },
      test("drops a file's tags when it is removed") {
        val tagged =
          """|@only @here
             |Feature: F
             |""".stripMargin
        for
          _      <- makeServer
          index  <- ZIO.service[WorkspaceIndex]
          _      <- index.indexFeatureFile("/fake/removable.feature", tagged)
          before <- index.allTags
          _      <- index.removeFile("/fake/removable.feature")
          after  <- index.allTags
        yield assertTrue(before.contains("only"), !after.contains("only"), !after.contains("here"))
      },
      test("offers workspace tags and built-ins on a line starting with @") {
        val tagged =
          """|@smoke @checkout
             |Feature: Checkout
             |  @
             |""".stripMargin
        val taggedUri = "file:///fake/tagged2.feature"
        for
          server <- makeServer
          index  <- ZIO.service[WorkspaceIndex]
          _      <- index.indexFeatureFile("/fake/tagged2.feature", tagged)
          _      <- server.putContent(taggedUri, tagged)
          params = new CompletionParams(
                     new TextDocumentIdentifier(taggedUri),
                     new Position(2, 3) // inside "  @"
                   )
          result <- ZIO.fromCompletableFuture(server.completion(params))
        yield
          val labels = result.getLeft.asScala.toList.map(_.getLabel)
          assertTrue(
            labels.contains("@smoke"),
            labels.contains("@checkout"),
            labels.contains("@ignore"),
            labels.exists(_.startsWith("@flags"))
          )
      }
    ),
    suite("structural keyword completion")(
      test("offers structural keywords on a blank line") {
        val doc =
          """|Feature: F
             |
             |""".stripMargin
        val docUri = "file:///fake/blank.feature"
        for
          server <- makeServer
          _      <- server.putContent(docUri, doc)
          params = new CompletionParams(
                     new TextDocumentIdentifier(docUri),
                     new Position(1, 0) // the blank line
                   )
          result <- ZIO.fromCompletableFuture(server.completion(params))
        yield
          val labels = result.getLeft.asScala.toList.map(_.getLabel)
          assertTrue(
            labels.contains("Feature:"),
            labels.contains("Background:"),
            labels.contains("Scenario:"),
            labels.contains("Scenario Outline:"),
            labels.contains("Rule:"),
            labels.contains("Examples:")
          )
      },
      test("filters structural keywords by the typed prefix") {
        val doc =
          """|Feature: F
             |  Sc
             |""".stripMargin
        val docUri = "file:///fake/prefix.feature"
        for
          server <- makeServer
          _      <- server.putContent(docUri, doc)
          params = new CompletionParams(
                     new TextDocumentIdentifier(docUri),
                     new Position(1, 4) // after "Sc"
                   )
          result <- ZIO.fromCompletableFuture(server.completion(params))
        yield
          val labels = result.getLeft.asScala.toList.map(_.getLabel)
          assertTrue(
            labels.contains("Scenario:"),
            labels.contains("Scenario Outline:"),
            !labels.contains("Feature:"),
            !labels.contains("Background:"),
            !labels.contains("Rule:")
          )
      },
      test("offers snippet template items with tab stops") {
        val doc =
          """|Feature: F
             |
             |""".stripMargin
        val docUri = "file:///fake/snippet.feature"
        for
          server <- makeServer
          _      <- server.putContent(docUri, doc)
          params = new CompletionParams(
                     new TextDocumentIdentifier(docUri),
                     new Position(1, 0)
                   )
          result <- ZIO.fromCompletableFuture(server.completion(params))
        yield
          val items    = result.getLeft.asScala.toList
          val snippets = items.filter(_.getInsertTextFormat == InsertTextFormat.Snippet)
          assertTrue(
            snippets.nonEmpty,
            snippets.exists(i => Option(i.getInsertText).exists(_.contains("${1:")))
          )
      }
    ),
    suite("completion capabilities")(
      test("advertises @ as a completion trigger character") {
        for
          server <- makeServer
          result <- ZIO.attempt(server.initialize(new InitializeParams())).orDie
          init   <- ZIO.fromCompletableFuture(result)
        yield
          val triggers =
            Option(init.getCapabilities.getCompletionProvider)
              .flatMap(p => Option(p.getTriggerCharacters))
              .map(_.asScala.toList)
              .getOrElse(Nil)
          assertTrue(triggers.contains("@"))
      }
    ),
    suite("codeAction")(
      test("offers skeleton action for an unmatched step diagnostic") {
        val diag = new Diagnostic(
          new Range(new Position(5, 4), new Position(5, 28)),
          s"""No step definition found for: "the user cancels"""",
          DiagnosticSeverity.Warning,
          "zio-bdd"
        )
        for
          server <- makeServer
          params = new CodeActionParams(
                     new TextDocumentIdentifier(featureUri),
                     diag.getRange,
                     new CodeActionContext(java.util.List.of(diag))
                   )
          result <- ZIO.fromCompletableFuture(server.codeAction(params))
        yield
          val actions = result.asScala.toList.map(_.getRight)
          assertTrue(
            actions.nonEmpty,
            actions.head.getKind == CodeActionKind.QuickFix,
            actions.head.getTitle.contains("CheckoutSteps.scala"),
            actions.head.getEdit.getDocumentChanges
              .get(0)
              .getLeft
              .getEdits
              .get(0)
              .getNewText
              .contains("the user cancels")
          )
      }
    )
  ).provide(WorkspaceIndex.layer, ZIOBddServer.testLayer)
