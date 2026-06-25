package zio.bdd.lsp.bsp

import com.google.gson.{JsonObject, JsonParser}
import zio.*

import java.io.{InputStream, OutputStream}
import scala.jdk.CollectionConverters.*

// BSP JSON-RPC session.
//
// Lifecycle:
//   1. BspClient.connect(workspaceRoot, onCompile) — launches the BSP server
//      subprocess, performs the build/initialize handshake, queries build
//      targets and their source directories. Returns None immediately if no
//      .bsp/*.json is found.
//
//   2. sourceDirs — exact source roots from buildTarget/sources (test scope).
//      Populates asynchronously; empty until the round-trip completes (~3 s).
//
//   3. testClasspath — JVM classpath for the test targets, from
//      buildTarget/jvmTestEnvironment. Available for future class-loading
//      (see issue #2: BSP class-loading via subprocess).
//
//   4. A background reader fiber routes incoming messages. On build/taskFinish
//      with statusCode=OK (1) the onCompile callback fires so the caller can
//      re-scan source files and update the step index.
//
// Threading: all BSP I/O runs on ZIO's blocking thread pool. Responses to
// in-flight requests are routed via a Ref[Map[Int, Promise[...]]].
final class BspClient private (
  private val process: Process,
  private val in: InputStream,
  private val out: OutputStream,
  private val nextId: Ref[Int],
  private val pending: Ref[Map[Int, Promise[String, String]]],
  private val sourceDirsRef: Ref[List[String]],
  private val classpathRef: Ref[List[String]],
  private val onCompile: UIO[Unit]
):

  def sourceDirs: UIO[List[String]] = sourceDirsRef.get

  def testClasspath: UIO[List[String]] = classpathRef.get

  // ── BSP request/response ──────────────────────────────────────────────────

  private def nextRequestId: UIO[Int] =
    nextId.getAndUpdate(_ + 1)

  // Send a request and wait for its response JSON string.
  private def request(method: String, params: String): IO[String, String] =
    for
      id      <- nextRequestId
      promise <- Promise.make[String, String]
      _       <- pending.update(_ + (id -> promise))
      body     = s"""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}"""
      _       <- BspJsonRpc.send(out, body)
      result  <- promise.await
    yield result

  private def notify(method: String, params: String): UIO[Unit] =
    val body = s"""{"jsonrpc":"2.0","method":"$method","params":$params}"""
    BspJsonRpc.send(out, body)

  // ── Background reader fiber ───────────────────────────────────────────────

  private[bsp] def readerLoop: UIO[Unit] =
    BspJsonRpc
      .receive(in)
      .orElseSucceed(None)
      .flatMap {
        case None      => ZIO.unit // EOF — server exited
        case Some(msg) => dispatch(msg) *> readerLoop
      }

  private def dispatch(msg: String): UIO[Unit] =
    ZIO.succeedBlocking(tryParseJson(msg)).flatMap {
      case None => ZIO.unit
      case Some(obj) =>
        val hasId     = obj.has("id") && !obj.get("id").isJsonNull
        val hasMethod = obj.has("method")

        if hasId && !hasMethod then
          // JSON-RPC response — complete the matching promise
          val id = obj.get("id").getAsInt
          pending
            .modify(m => (m.get(id), m - id))
            .flatMap {
              case None => ZIO.unit
              case Some(promise) =>
                if obj.has("error") then promise.fail(obj.get("error").toString).unit
                else promise.succeed(if obj.has("result") then obj.get("result").toString else "{}").unit
            }
        else if hasMethod then
          val method = obj.get("method").getAsString
          method match
            case "build/taskFinish" => handleTaskFinish(obj)
            case "build/logMessage" => logBspMessage(obj)
            case _                  => ZIO.unit
        else ZIO.unit
    }

  private def handleTaskFinish(obj: JsonObject): UIO[Unit] =
    ZIO.succeedBlocking {
      if obj.has("params") then
        val params     = obj.get("params").getAsJsonObject
        val statusCode = if params.has("statusCode") then params.get("statusCode").getAsInt else -1
        statusCode == 1 // BSP StatusCode.OK = 1
      else false
    }.flatMap(isOk => if isOk then onCompile else ZIO.unit)

  private def logBspMessage(obj: JsonObject): UIO[Unit] =
    ZIO.succeedBlocking {
      if obj.has("params") then
        val p = obj.get("params").getAsJsonObject
        if p.has("message") then p.get("message").getAsString else ""
      else ""
    }.flatMap(msg => ZIO.logDebug(s"[BSP] $msg").when(msg.nonEmpty)).unit

  private def tryParseJson(s: String): Option[JsonObject] =
    try Some(JsonParser.parseString(s).getAsJsonObject)
    catch case _: Throwable => None

  // ── BSP protocol ──────────────────────────────────────────────────────────

  private[bsp] def initialize: UIO[Unit] =
    val params =
      s"""{
         |  "displayName": "zio-bdd-lsp",
         |  "version": "0.9.0",
         |  "bspVersion": "2.1.0",
         |  "capabilities": { "languageIds": ["scala", "java"] }
         |}""".stripMargin
    request("build/initialize", params)
      .tapError(err => ZIO.logWarning(s"BSP initialize failed: $err"))
      .flatMap(_ => notify("build/initialized", "{}"))
      .orElseSucceed(())

  private[bsp] def loadBuildTargets: UIO[Unit] =
    request("workspace/buildTargets", "{}").flatMap { result =>
      ZIO.succeedBlocking(extractTestTargetIds(result)).flatMap { ids =>
        ZIO.logInfo(s"BSP: ${ids.size} build target(s) — fetching source roots") *>
          ZIO.foreachDiscard(ids)(loadSourcesForTarget) *>
          ZIO.foreachDiscard(ids)(loadJvmEnvForTarget)
      }
    }
      .tapError(err => ZIO.logWarning(s"BSP workspace/buildTargets failed: $err"))
      .orElseSucceed(())

  private def loadSourcesForTarget(targetUri: String): UIO[Unit] =
    val params = s"""{"targets":[{"uri":"$targetUri"}]}"""
    request("buildTarget/sources", params).flatMap { result =>
      ZIO.succeedBlocking(extractSourceDirs(result)).flatMap { dirs =>
        ZIO
          .when(dirs.nonEmpty)(
            sourceDirsRef.update(existing => (existing ++ dirs).distinct) *>
              ZIO.logInfo(s"BSP: added ${dirs.size} source dir(s) from $targetUri")
          )
          .unit
      }
    }
      .tapError(e => ZIO.logDebug(s"BSP buildTarget/sources failed for $targetUri: $e"))
      .orElseSucceed(())

  private def loadJvmEnvForTarget(targetUri: String): UIO[Unit] =
    val params = s"""{"targets":[{"uri":"$targetUri"}]}"""
    request("buildTarget/jvmTestEnvironment", params).flatMap { result =>
      ZIO.succeedBlocking(extractClasspath(result)).flatMap { cp =>
        ZIO
          .when(cp.nonEmpty)(
            classpathRef.update(existing => (existing ++ cp).distinct) *>
              ZIO.logInfo(s"BSP: classpath has ${cp.size} entries for $targetUri")
          )
          .unit
      }
    }
      .tapError(e => ZIO.logDebug(s"BSP buildTarget/jvmTestEnvironment failed for $targetUri: $e"))
      .orElseSucceed(())

  // ── JSON extraction helpers ───────────────────────────────────────────────

  private def extractTestTargetIds(result: String): List[String] =
    try
      val targets = JsonParser.parseString(result).getAsJsonObject.getAsJsonArray("targets")
      targets.asScala.flatMap { t =>
        val obj = t.getAsJsonObject
        val uri = obj.getAsJsonObject("id").get("uri").getAsString
        val isTest =
          if obj.has("tags") then obj.getAsJsonArray("tags").asScala.exists(_.getAsString.contains("test"))
          else false
        if isTest || uri.contains("test") then Some(uri) else None
      }.toList
    catch case _: Throwable => Nil

  private def extractSourceDirs(result: String): List[String] =
    try
      val items = JsonParser.parseString(result).getAsJsonObject.getAsJsonArray("items")
      items.asScala.flatMap { item =>
        item.getAsJsonObject.getAsJsonArray("sources").asScala.flatMap { src =>
          val obj  = src.getAsJsonObject
          val kind = if obj.has("kind") then obj.get("kind").getAsInt else 0
          // kind=2 means GENERATED — skip to avoid indexing target/ output
          if kind != 2 then Some(obj.get("uri").getAsString.stripPrefix("file://"))
          else None
        }
      }
        .filter(_.nonEmpty)
        .toList
    catch case _: Throwable => Nil

  private def extractClasspath(result: String): List[String] =
    try
      val items = JsonParser.parseString(result).getAsJsonObject.getAsJsonArray("items")
      items.asScala.flatMap { item =>
        item.getAsJsonObject
          .getAsJsonArray("classpath")
          .asScala
          .map(_.getAsString.stripPrefix("file://"))
      }.toList
    catch case _: Throwable => Nil

object BspClient:
  // Connect to the BSP server for `workspaceRoot` and start the background
  // reader fiber. Returns None immediately if no BSP connection file exists.
  // Source dirs and classpath populate asynchronously as BSP responses arrive.
  def connect(
    workspaceRoot: String,
    onCompile: UIO[Unit]
  ): UIO[Option[BspClient]] =
    BspConnectionFile.find(workspaceRoot).flatMap {
      case None => ZIO.none
      case Some(conn) =>
        startClient(conn, onCompile)
          .tapError(e => ZIO.logWarning(s"BSP connection failed (${conn.name}): $e"))
          .map(Some(_))
          .orElseSucceed(None)
    }

  private def startClient(conn: BspConnection, onCompile: UIO[Unit]): Task[BspClient] =
    ZIO.attempt {
      val pb      = new ProcessBuilder(conn.argv*)
      val process = pb.start()
      (process, process.getInputStream, process.getOutputStream)
    }.flatMap { (process, in, out) =>
      for
        nextId        <- Ref.make(1)
        pending       <- Ref.make(Map.empty[Int, Promise[String, String]])
        sourceDirsRef <- Ref.make(List.empty[String])
        classpathRef  <- Ref.make(List.empty[String])
        bsp            = BspClient(process, in, out, nextId, pending, sourceDirsRef, classpathRef, onCompile)
        // Drain stderr so the BSP server doesn't block on a full pipe
        _ <- ZIO
               .attemptBlocking(process.getErrorStream.transferTo(java.io.OutputStream.nullOutputStream()))
               .forkDaemon
        // Background reader routes responses and notifications
        _ <- bsp.readerLoop.forkDaemon
        // Initialize and query targets; errors are logged, never propagated
        _ <- (bsp.initialize *> bsp.loadBuildTargets).forkDaemon
      yield bsp
    }
