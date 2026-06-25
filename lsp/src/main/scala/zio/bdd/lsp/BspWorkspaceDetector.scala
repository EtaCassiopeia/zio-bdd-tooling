package zio.bdd.lsp

import zio.*

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

// Detects BSP (Build Server Protocol) presence in the workspace and derives
// source-root candidates from well-known project layouts.
//
// A full BSP JSON-RPC client (connecting to sbt/Bloop, subscribing to compile
// events, loading step classes via URLClassLoader) would give exact source
// roots and real-time compile notifications. That requires a running BSP server
// and is tracked as a follow-up. Here we provide:
//
//   1. BSP presence detection (.bsp/<name>.json) so the LSP can log that a
//      smarter integration is available.
//   2. Heuristic source-root discovery covering single- and multi-module
//      sbt/Mill projects without requiring the build server to be active.
object BspWorkspaceDetector:

  // Returns true when a .bsp connection descriptor JSON file is present.
  def isBspProject(workspaceRoot: String): UIO[Boolean] =
    ZIO.attemptBlocking {
      val bspDir = Paths.get(workspaceRoot, ".bsp")
      Files.isDirectory(bspDir) &&
      Files.list(bspDir).iterator().asScala.exists(_.getFileName.toString.endsWith(".json"))
    }.orElseSucceed(false)

  // Discover all source directories under workspaceRoot using heuristics that
  // cover common sbt / Mill / Gradle layouts. Returns absolute path strings.
  //
  // Priority order:
  //   1. Canonical Scala source roots (src/main/scala, src/test/scala)
  //   2. Module subdirectory roots (modules/<mod>/src/main/scala, etc.)
  //   3. Fallback: workspaceRoot itself (original behaviour — walk everything)
  def sourceRoots(workspaceRoot: String): UIO[List[String]] =
    ZIO.attemptBlocking {
      val root = Paths.get(workspaceRoot)
      if (!Files.isDirectory(root)) List(workspaceRoot)
      else
        val candidates = List.newBuilder[Path]

        // Standard Maven/sbt layout directly under the root
        standardSourceDirs(root).foreach(d => candidates += d)

        // Top-level module directories (one level deep)
        Files.list(root).iterator().asScala
          .filter(p => Files.isDirectory(p) && !isHiddenOrBuild(p))
          .foreach { moduleDir =>
            standardSourceDirs(moduleDir).foreach(d => candidates += d)
          }

        val found = candidates.result().filter(Files.isDirectory(_)).map(_.toAbsolutePath.toString)
        if (found.isEmpty) List(workspaceRoot) else found
    }.orElseSucceed(List(workspaceRoot))

  private def standardSourceDirs(base: Path): List[Path] = List(
    base.resolve("src/main/scala"),
    base.resolve("src/test/scala"),
    base.resolve("src/main/java"),
    base.resolve("src"),
  )

  private def isHiddenOrBuild(p: Path): Boolean =
    val name = p.getFileName.toString
    name.startsWith(".") || name == "target" || name == "build" || name == "out" || name == "node_modules"
