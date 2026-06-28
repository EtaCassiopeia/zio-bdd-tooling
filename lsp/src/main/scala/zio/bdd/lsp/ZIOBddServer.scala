package zio.bdd.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either as JEither
import org.eclipse.lsp4j.services.*
import zio.*
import zio.bdd.lsp.bsp.{BspClassLoader, BspClient}
import zio.bdd.lsp.handlers.*

import java.lang.System as JSystem
import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters.*

/**
 * The zio-bdd LSP server.
 *
 * Threading & lifecycle:
 *   - `initialize` returns immediately; the workspace scan is forked.
 *   - The `ready` Promise gates user-facing requests until the scan finishes.
 *     This avoids blocking the JSON-RPC reader thread while still ensuring
 *     `definition`/`hover`/`completion` see a populated index.
 *   - `initialized` registers a `workspace/didChangeWatchedFiles` capability so
 *     the client notifies us when .scala files change on disk.
 *
 * IO bridging: `dispatch` is the only function that crosses the ZIO ↔
 * CompletableFuture boundary. All LSP handlers go through it.
 */
final class ZIOBddServer(
  index: WorkspaceIndex,
  runtime: Runtime[Any],
  client: Ref[Option[LanguageClient]],
  ready: Promise[Nothing, Unit],
  bspRef: Ref[Option[BspClient]]
) extends LanguageServer
    with TextDocumentService
    with WorkspaceService
    with ZioBddExtension:

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  override def initialize(params: InitializeParams): CompletableFuture[InitializeResult] =
    val rootPath = resolveRootPath(params)

    fireAndForget(
      ZIO.logInfo(s"initialize: rootPath=$rootPath") *>
        startBspClient(rootPath) *>
        index.allSteps.flatMap(d => ZIO.logInfo(s"scan complete: ${d.size} step definitions indexed")) *>
        ready.succeed(()).unit
    )

    val caps = new ServerCapabilities()
    caps.setTextDocumentSync(TextDocumentSyncKind.Full)
    caps.setDefinitionProvider(true)
    caps.setHoverProvider(true)
    caps.setDocumentSymbolProvider(true)
    caps.setCodeLensProvider(new CodeLensOptions(false))
    caps.setReferencesProvider(true)
    caps.setCodeActionProvider(true)
    caps.setCompletionProvider(
      new CompletionOptions(false, List("Given ", "When ", "Then ", "And ", "But ", "/", "@").asJava)
    )
    CompletableFuture.completedFuture(new InitializeResult(caps))

  override def initialized(params: InitializedParams): Unit =
    fireAndForget(
      client.get.flatMap {
        case None => ZIO.unit
        case Some(c) =>
          ZIO.attempt {
            val watcher = new FileSystemWatcher(JEither.forLeft[String, RelativePattern]("**/*.scala"))
            val reg = new Registration(
              "zio-bdd-scala-watcher",
              "workspace/didChangeWatchedFiles",
              new DidChangeWatchedFilesRegistrationOptions(java.util.List.of(watcher))
            )
            c.registerCapability(new RegistrationParams(java.util.List.of(reg)))
          }.unit.catchAllCause(c => ZIO.logWarningCause("Could not register file watcher", c))
      }
    )

  override def shutdown(): CompletableFuture[AnyRef] =
    CompletableFuture.completedFuture(null)

  override def exit(): Unit = JSystem.exit(0)

  override def getTextDocumentService: TextDocumentService = this
  override def getWorkspaceService: WorkspaceService       = this

  def setClient(c: LanguageClient): Unit = fireAndForget(client.set(Some(c)))

  // Complete the ready gate immediately — for use in unit/integration tests only.
  private[lsp] def forceReady: UIO[Unit] = ready.succeed(()).unit

  // Pre-populate the content cache without a filesystem round-trip or async
  // reindex — for use in integration tests only.
  private[lsp] def putContent(uri: String, content: String): UIO[Unit] =
    ZIO.succeed(contentCache.put(uri, content)).unit

  // ── TextDocumentService ───────────────────────────────────────────────────

  override def didOpen(params: DidOpenTextDocumentParams): Unit =
    val doc = params.getTextDocument
    reindex(doc.getUri, doc.getText)
    publishDiagnostics(doc.getUri, doc.getText)

  override def didChange(params: DidChangeTextDocumentParams): Unit =
    val doc     = params.getTextDocument
    val content = params.getContentChanges.asScala.lastOption.map(_.getText).getOrElse("")
    reindex(doc.getUri, content)
    publishDiagnostics(doc.getUri, content)

  override def didSave(params: DidSaveTextDocumentParams): Unit   = ()
  override def didClose(params: DidCloseTextDocumentParams): Unit = ()

  override def definition(
    params: DefinitionParams
  ): CompletableFuture[JEither[java.util.List[? <: Location], java.util.List[? <: LocationLink]]] =
    val uri = params.getTextDocument.getUri
    dispatchGated(
      DefinitionHandler.definition(uri, params.getPosition, currentContent(uri), index).map { links =>
        JEither
          .forRight[java.util.List[Location], java.util.List[LocationLink]](links.asJava)
          .asInstanceOf[JEither[java.util.List[? <: Location], java.util.List[? <: LocationLink]]]
      }
    )

  override def hover(params: HoverParams): CompletableFuture[Hover] =
    val uri = params.getTextDocument.getUri
    dispatchGated(HoverHandler.hover(params.getPosition, currentContent(uri), index).map(_.orNull))

  override def completion(
    params: CompletionParams
  ): CompletableFuture[JEither[java.util.List[CompletionItem], CompletionList]] =
    val uri = params.getTextDocument.getUri
    dispatchGated(
      CompletionHandler
        .complete(uri, params.getPosition, currentContent(uri), index)
        .map(items => JEither.forLeft[java.util.List[CompletionItem], CompletionList](items.asJava))
    )

  override def references(params: ReferenceParams): CompletableFuture[java.util.List[? <: Location]] =
    val uri = params.getTextDocument.getUri
    dispatchGated(
      ReferencesHandler
        .references(uri, params.getPosition, currentContent(uri), index)
        .map(_.asJava)
    )

  override def codeLens(params: CodeLensParams): CompletableFuture[java.util.List[? <: CodeLens]] =
    val uri = params.getTextDocument.getUri
    dispatchGated(CodeLensHandler.codeLenses(uri, currentContent(uri), index).map(_.asJava))

  override def codeAction(
    params: CodeActionParams
  ): CompletableFuture[java.util.List[JEither[Command, CodeAction]]] =
    val uri = params.getTextDocument.getUri
    dispatchGated(
      CodeActionHandler
        .codeActions(uri, params.getRange, params.getContext, currentContent(uri), index)
        .map(_.map(a => JEither.forRight[Command, CodeAction](a)).asJava)
    )

  override def documentSymbol(
    params: DocumentSymbolParams
  ): CompletableFuture[java.util.List[JEither[SymbolInformation, DocumentSymbol]]] =
    val uri = params.getTextDocument.getUri
    dispatch(
      DocumentSymbolsHandler
        .symbols(uri, currentContent(uri))
        .map(_.map(s => JEither.forRight[SymbolInformation, DocumentSymbol](s)).asJava)
    )

  // ── WorkspaceService ──────────────────────────────────────────────────────

  override def didChangeConfiguration(params: DidChangeConfigurationParams): Unit = ()

  override def didChangeWatchedFiles(params: DidChangeWatchedFilesParams): Unit =
    params.getChanges.asScala.foreach { change =>
      if !inBuildDir(change.getUri) then
        val path = change.getUri.stripPrefix("file://")
        change.getType match
          case FileChangeType.Deleted => fireAndForget(index.removeFile(path))
          case _                      => reindex(change.getUri, readFile(path))
    }

  // ── Internals ─────────────────────────────────────────────────────────────

  private val contentCache = new java.util.concurrent.ConcurrentHashMap[String, String]()

  // Connect to the BSP server if one is configured for `rootPath`.
  // The client is stored in `bspRef` so the compile callback can read
  // its source dirs at fire time (resolves the chicken-and-egg: the callback
  // is created before the client exists, but is only ever called after).
  private def startBspClient(rootPath: String): UIO[Unit] =
    // Compile callback: re-scan using BSP exact source roots, then upgrade step
    // patterns with runtime-accurate data from the BSP class-loading subprocess.
    val onCompile: UIO[Unit] =
      bspRef.get.flatMap {
        case None => index.scanSourceRoots(rootPath, Nil)
        case Some(bsp) =>
          bsp.sourceDirs.flatMap { dirs =>
            ZIO.logInfo(s"BSP compile — re-scanning ${dirs.size} source root(s)") *>
              index.scanSourceRoots(rootPath, dirs)
          } *> bsp.testClasspath.flatMap { cp =>
            if cp.isEmpty then ZIO.unit
            else
              ZIO.logInfo(s"BSP compile — loading runtime step definitions (${cp.size} cp entries)") *>
                BspClassLoader.loadSteps(cp).flatMap { summaries =>
                  if summaries.isEmpty then ZIO.unit
                  else
                    ZIO.logInfo(s"BSP class-loading: merging ${summaries.size} runtime step definitions") *>
                      index.mergeRuntimeSteps(summaries)
                }
          }
      }

    BspClient
      .connect(rootPath, onCompile)
      .flatMap { optClient =>
        bspRef.set(optClient) *>
          optClient.fold(
            // No BSP server — fall back to heuristic workspace walk.
            ZIO.logInfo("No BSP server found; using heuristic source-root discovery") *>
              index.initialScan(rootPath)
          ) { bsp =>
            // BSP is available: give it a moment to respond to the
            // initialize/workspace/buildTargets round-trip, then use the exact
            // source roots it provides.  Only if it returns nothing yet do we
            // fall back to heuristics; the compile callback will upgrade to BSP
            // roots once the first build completes.
            ZIO.logInfo("BSP client connected — waiting for source roots") *>
              ZIO.sleep(3.seconds) *>
              bsp.sourceDirs.flatMap { dirs =>
                if dirs.isEmpty then
                  ZIO.logInfo("BSP: no source roots yet; falling back to heuristic scan") *>
                    index.initialScan(rootPath)
                else
                  ZIO.logInfo(s"BSP: indexing with ${dirs.size} exact source root(s) from BSP") *>
                    index.scanSourceRoots(rootPath, dirs)
              }
          }
      }

  private def currentContent(uri: String): String =
    Option(contentCache.get(uri)).getOrElse("")

  private val buildDirs = Set("target", ".bloop", ".metals", ".bsp", "out", ".cache", "node_modules")

  private def inBuildDir(uri: String): Boolean =
    val sep = java.io.File.separator
    buildDirs.exists(d => uri.contains(sep + d + sep) || uri.contains("/" + d + "/"))

  private def reindex(uri: String, content: String): Unit =
    if inBuildDir(uri) then return
    contentCache.put(uri, content)
    val path = uri.stripPrefix("file://")
    val effect =
      if uri.endsWith(".scala") then index.indexScalaFile(path, content)
      else if uri.endsWith(".feature") then index.indexFeatureFile(path, content)
      else ZIO.unit
    fireAndForget(effect)

  private def publishDiagnostics(uri: String, content: String): Unit =
    if !uri.endsWith(".feature") then return
    fireAndForget(
      ready.await *>
        DiagnosticsHandler.computeDiagnostics(uri, content, index).flatMap { diags =>
          client.get.flatMap {
            case None => ZIO.unit
            case Some(c) =>
              ZIO
                .attempt(c.publishDiagnostics(new PublishDiagnosticsParams(uri, diags.asJava)))
                .catchAllCause(c => ZIO.logWarningCause(s"Failed to publish diagnostics for $uri", c))
                .unit
          }
        }
    )

  private def readFile(path: String): String =
    try scala.io.Source.fromFile(path, "UTF-8").mkString
    catch case _: Throwable => ""

  /**
   * Resolve the workspace root from the LSP `initialize` params.
   *
   * Preference order: first `workspaceFolders` entry → deprecated `rootUri` →
   * deprecated `rootPath` → ".". Modern clients (IntelliJ + lsp4ij, VS Code)
   * always send `workspaceFolders`; the legacy fields are kept as fallback for
   * older clients that still set them.
   */
  @annotation.nowarn("cat=deprecation")
  private def resolveRootPath(params: InitializeParams): String =
    val raw =
      Option(params.getWorkspaceFolders)
        .map(_.asScala.toList)
        .getOrElse(Nil)
        .headOption
        .map(_.getUri)
        .orElse(Option(params.getRootUri))
        .orElse(Option(params.getRootPath))
        .getOrElse(".")
    raw.stripPrefix("file://")

  /** Fork an effect into the runtime; failures are logged, never propagated. */
  private def fireAndForget(effect: ZIO[Any, Any, Any]): Unit =
    Unsafe.unsafe { implicit u =>
      runtime.unsafe.fork(effect.tapErrorCause(c => ZIO.logErrorCause("Background effect failed", c)))
      ()
    }

  /**
   * Bridge a UIO into a CompletableFuture. Failures complete the future
   * exceptionally.
   */
  private def dispatch[A](effect: UIO[A]): CompletableFuture[A] =
    val cf = new CompletableFuture[A]()
    Unsafe.unsafe { implicit u =>
      runtime.unsafe.fork(
        effect.foldCauseZIO(
          c => ZIO.succeed(cf.completeExceptionally(c.squashTrace)),
          a => ZIO.succeed(cf.complete(a))
        )
      )
    }
    cf

  /**
   * Like `dispatch` but waits for the initial scan to finish before running the
   * effect.
   */
  private def dispatchGated[A](effect: UIO[A]): CompletableFuture[A] =
    dispatch(ready.await *> effect)

  // ── ZioBddExtension ──────────────────────────────────────────────────────

  override def suiteFeatureMap(params: com.google.gson.JsonObject): CompletableFuture[String] =
    dispatchGated(
      index.suiteFeatureMap().map { entries =>
        val arr = new com.google.gson.JsonArray()
        entries.foreach { (scalaFile, featurePaths) =>
          val obj = new com.google.gson.JsonObject()
          obj.addProperty("suiteName", java.nio.file.Paths.get(scalaFile).getFileName.toString.stripSuffix(".scala"))
          obj.addProperty("scalaFile", scalaFile)
          val featArr = new com.google.gson.JsonArray()
          featurePaths.foreach(featArr.add)
          obj.add("featurePaths", featArr)
          arr.add(obj)
        }
        arr.toString
      }
    )

  override def buildRunCommand(params: com.google.gson.JsonObject): CompletableFuture[String] =
    val featureUri   = params.get("featureUri").getAsString
    val scenarioName = Option(params.get("scenarioName")).filterNot(_.isJsonNull).map(_.getAsString)
    val featurePath  = featureUri.stripPrefix("file://")
    dispatchGated(
      for
        suiteFiles <- index.suiteFilesForFeature(featurePath)
        selector    = handlers.CodeLensHandler.suiteSelector(suiteFiles)
        flags = scenarioName match
                  case Some(name) =>
                    s"--feature-file ${handlers.CodeLensHandler.shellQuote(featurePath)}" +
                      s" --scenario-name ${handlers.CodeLensHandler.shellQuote(name)} --focused"
                  case None =>
                    s"--feature-file ${handlers.CodeLensHandler.shellQuote(featurePath)}"
      yield handlers.CodeLensHandler.buildRunCommand(selector, flags)
    )

object ZIOBddServer:
  val layer: ZLayer[WorkspaceIndex, Nothing, ZIOBddServer] =
    ZLayer.fromZIO {
      for
        index  <- ZIO.service[WorkspaceIndex]
        rt     <- ZIO.runtime[Any]
        client <- Ref.make(Option.empty[LanguageClient])
        ready  <- Promise.make[Nothing, Unit]
        bspRef <- Ref.make(Option.empty[BspClient])
      yield ZIOBddServer(index, rt, client, ready, bspRef)
    }

  // Like `layer` but also provides ZIOBddServer as a typed service — for
  // integration tests that need to call handler methods directly.
  val testLayer: ZLayer[WorkspaceIndex, Nothing, ZIOBddServer] = layer
