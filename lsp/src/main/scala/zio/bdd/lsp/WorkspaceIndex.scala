package zio.bdd.lsp

import zio.*
import zio.bdd.gherkin.{Feature, StepType}

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * In-memory index of step definitions (per Scala file) and parsed feature
 * files.
 *
 * All mutations go through this class, which is the only thing inside the LSP
 * server allowed to write to the index Refs. Reads are non-blocking.
 */
// A step summary produced by the BSP class-loading subprocess (StepLoader).
// keyword/pattern/displayText mirror ZIOSteps.allDefinitions / StepSummary.
case class RuntimeStepSummary(keyword: String, pattern: String, displayText: String)

// A @mock(name) catalog entry produced by the BSP class-loading subprocess.
// name/sourceKind mirror MockSteps.allMocks / MockSummary (zio-bdd 1.3.0).
case class RuntimeMockSummary(name: String, sourceKind: String)

final class WorkspaceIndex private (
  stepsRef: Ref[Map[String, List[StepDefinition]]],
  featuresRef: Ref[Map[String, Feature]],
  tagsRef: Ref[Map[String, Set[String]]],
  // The @mock(name) catalog is a workspace-global fact (it comes from the
  // compiled test classpath, not from any one file), so — unlike steps/features
  // — it is a single list, replaced wholesale on each BSP class-load.
  mocksRef: Ref[List[RuntimeMockSummary]]
):

  def allSteps: UIO[List[StepDefinition]]     = stepsRef.get.map(_.values.flatten.toList)
  def allFeatures: UIO[List[Feature]]         = featuresRef.get.map(_.values.toList)
  def allTags: UIO[Set[String]]               = tagsRef.get.map(_.values.flatten.toSet)
  def allMocks: UIO[List[RuntimeMockSummary]] = mocksRef.get

  // Replace the discovered @mock catalog with the latest BSP class-load result.
  def setMocks(mocks: List[RuntimeMockSummary]): UIO[Unit] = mocksRef.set(mocks)

  def indexScalaFile(path: String, content: String): UIO[Unit] =
    stepsRef.update(_.updated(path, StepExtractor.extractFromSource(content, path)))

  // Tags are keyed by path (like steps/features) so re-indexing a file replaces
  // its tag set and removeFile drops it — the workspace tag set never goes stale.
  def indexFeatureFile(path: String, content: String): UIO[Unit] =
    val storeFeature = GherkinBridge.parseFeature(content, path) match
      case Right(f) => featuresRef.update(_.updated(path, f))
      case Left(_)  => ZIO.unit
    storeFeature *> tagsRef.update(_.updated(path, WorkspaceIndex.scanTags(content)))

  def removeFile(path: String): UIO[Unit] =
    stepsRef.update(_ - path) *> featuresRef.update(_ - path) *> tagsRef.update(_ - path)

  def findStep(keyword: String, text: String): UIO[StepMatcher.MatchResult] =
    allSteps.map(StepMatcher.find(keyword, text, _))

  def suiteFilesForFeature(featurePath: String): UIO[List[String]] =
    for
      featureMap <- featuresRef.get
      stepMap    <- stepsRef.get
    yield featureMap.get(featurePath).fold(List.empty[String]) { feature =>
      stepMap.collect {
        case (scalaFile, defs) if defs.nonEmpty && featureMatchesDefs(feature, defs) => scalaFile
      }.toList
    }

  // Returns a mapping of Scala suite file → feature file paths for every suite
  // that has at least one matching step definition.  Used by the sidebar to
  // group features under the suite that owns them.
  def suiteFeatureMap(): UIO[List[(String, List[String])]] =
    for
      featureMap <- featuresRef.get
      stepMap    <- stepsRef.get
    yield stepMap.toList.flatMap { (scalaFile, defs) =>
      if defs.isEmpty then None
      else
        val matched = featureMap.keys.filter { featurePath =>
          featureMap.get(featurePath).exists(featureMatchesDefs(_, defs))
        }.toList.sorted
        if matched.isEmpty then None else Some(scalaFile -> matched)
    }

  private def featureMatchesDefs(feature: Feature, defs: List[StepDefinition]): Boolean =
    feature.scenarios.flatMap(_.steps).exists { step =>
      val kw = step.stepType match
        case StepType.GivenStep => "Given"
        case StepType.WhenStep  => "When"
        case StepType.ThenStep  => "Then"
        case StepType.ButStep   => "But"
        case StepType.AndStep   => "And"
      StepMatcher.find(kw, step.pattern, defs) match
        case StepMatcher.MatchResult.Matched(_) => true
        case _                                  => false
    }

  // Walk source roots and index every .scala / .feature file in parallel.
  //
  // When `roots` is non-empty those paths are used directly — the BSP client
  // supplies exact roots from buildTarget/sources, bypassing heuristic
  // discovery. When `roots` is empty the method falls back to
  // BspWorkspaceDetector or a full workspace walk.
  def scanSourceRoots(workspaceRoot: String, roots: List[String] = Nil): UIO[Unit] =
    for
      effectiveRoots <- if roots.nonEmpty then ZIO.succeed(roots)
                        else
                          BspWorkspaceDetector.isBspProject(workspaceRoot).flatMap { isBsp =>
                            if isBsp then BspWorkspaceDetector.sourceRoots(workspaceRoot)
                            else ZIO.succeed(List(workspaceRoot))
                          }
      pairs <-
        ZIO.foreach(effectiveRoots)(r => ZIO.attemptBlocking(collectFiles(Paths.get(r))).orElseSucceed((Nil, Nil)))
      (scalaFiles, featureFiles) = pairs.foldLeft((List.empty[String], List.empty[String])) { case ((s, f), (si, fi)) =>
                                     (s ++ si, f ++ fi)
                                   }
      _ <-
        ZIO.logInfo(
          s"scanSourceRoots: ${scalaFiles.size} .scala, ${featureFiles.size} .feature (${effectiveRoots.size} root(s))"
        )
      _ <- ZIO.foreachParDiscard(scalaFiles)(p => readFile(p).flatMap(indexScalaFile(p, _)))
      _ <- ZIO.foreachParDiscard(featureFiles)(p => readFile(p).flatMap(indexFeatureFile(p, _)))
    yield ()

  // Backwards-compatible entry point used before BSP source roots are known.
  def initialScan(workspaceRoot: String): UIO[Unit] =
    scanSourceRoots(workspaceRoot, Nil)

  // Replace the static-scan step definitions for a Scala file with a list
  // produced by BSP class-loading (allDefinitions output). Uses the same
  // per-file key so subsequent file-change events continue to work normally.
  def updateStepsFromBsp(scalaFilePath: String, defs: List[StepDefinition]): UIO[Unit] =
    stepsRef.update(_.updated(scalaFilePath, defs))

  // Upgrade static-scan step patterns with runtime-accurate versions from the
  // BSP class-loading subprocess.  Matches by keyword + literal key (the
  // literal text segments with extractor placeholders stripped).  Where a match
  // is found, the `pattern` field is replaced with the runtime regex; file and
  // line come from the static scan and are preserved unchanged.
  def mergeRuntimeSteps(summaries: List[RuntimeStepSummary]): UIO[Unit] =
    if summaries.isEmpty then ZIO.unit
    else
      val runtimeByKey = summaries.map(s => (s.keyword.toLowerCase, runtimeLiteralKey(s.displayText)) -> s).toMap
      stepsRef.update { fileMap =>
        fileMap.transform { (_, defs) =>
          defs.map { sd =>
            val key = (sd.keyword.toLowerCase, staticLiteralKey(sd.displayText))
            runtimeByKey.get(key) match
              case Some(rs) => sd.copy(pattern = rs.pattern)
              case None     => sd
          }
        }
      }

  // Literal segments of a static-scan displayText like "the cart has {int} items"
  // → "the cart has  items" (placeholder stripped).
  private def staticLiteralKey(displayText: String): String =
    displayText.replaceAll("\\{[^}]+\\}", "")

  // Literal segments of a runtime displayText like `"the cart has " / <IntExtractor> / " items"`
  // → "the cart has  items" (extractor tokens stripped, quoted strings joined).
  private def runtimeLiteralKey(displayText: String): String =
    displayText
      .split(" / ")
      .collect { case s if s.startsWith("\"") => s.stripPrefix("\"").stripSuffix("\"") }
      .mkString

  // Directory names that are never useful to scan: build output, VCS, IDE metadata,
  // dependency caches, and tool-specific directories.
  private val excludedDirs = Set(
    "target",
    ".git",
    "node_modules",
    ".bloop",
    ".metals",
    ".bsp",
    ".idea",
    ".scala-build",
    "out",
    ".cache"
  )

  /** Returns (scalaPaths, featurePaths). Empty lists if root is missing. */
  private def collectFiles(root: Path): (List[String], List[String]) =
    if !Files.exists(root) then (Nil, Nil)
    else
      val all = Files
        .walk(root)
        .iterator()
        .asScala
        .filter { p =>
          Files.isRegularFile(p) &&
          // Reject any path whose components include an excluded directory name.
          !p.iterator().asScala.exists(part => excludedDirs.contains(part.toString))
        }
        .map(_.toAbsolutePath.toString)
        .toList
      (all.filter(_.endsWith(".scala")), all.filter(_.endsWith(".feature")))

  private def readFile(path: String): UIO[String] =
    ZIO.attemptBlocking(Files.readString(Paths.get(path))).orElseSucceed("")

object WorkspaceIndex:
  // Tags live on their own line: one or more `@name` tokens, optionally a
  // `@flags(...)` call. Only scan lines that begin with `@` so step text that
  // happens to contain an `@` is never mistaken for a tag. Captured names omit
  // the leading `@`.
  private val tagToken = """@(\w+)""".r

  def scanTags(content: String): Set[String] =
    content.linesIterator.collect {
      case line if line.trim.startsWith("@") => tagToken.findAllMatchIn(line).map(_.group(1)).toList
    }.flatten.toSet

  val layer: ULayer[WorkspaceIndex] =
    ZLayer.fromZIO(
      for
        s <- Ref.make(Map.empty[String, List[StepDefinition]])
        f <- Ref.make(Map.empty[String, Feature])
        t <- Ref.make(Map.empty[String, Set[String]])
        m <- Ref.make(List.empty[RuntimeMockSummary])
      yield WorkspaceIndex(s, f, t, m)
    )
