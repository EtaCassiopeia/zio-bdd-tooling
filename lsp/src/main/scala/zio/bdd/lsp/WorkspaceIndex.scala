package zio.bdd.lsp

import zio.*
import zio.bdd.gherkin.Feature

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * In-memory index of step definitions (per Scala file) and parsed feature
 * files.
 *
 * All mutations go through this class, which is the only thing inside the LSP
 * server allowed to write to the index Refs. Reads are non-blocking.
 */
final class WorkspaceIndex private (
  stepsRef: Ref[Map[String, List[StepDefinition]]],
  featuresRef: Ref[Map[String, Feature]]
):

  def allSteps: UIO[List[StepDefinition]] = stepsRef.get.map(_.values.flatten.toList)
  def allFeatures: UIO[List[Feature]]     = featuresRef.get.map(_.values.toList)

  def indexScalaFile(path: String, content: String): UIO[Unit] =
    stepsRef.update(_.updated(path, StepExtractor.extractFromSource(content, path)))

  def indexFeatureFile(path: String, content: String): UIO[Unit] =
    GherkinBridge.parseFeature(content, path) match
      case Right(f) => featuresRef.update(_.updated(path, f))
      case Left(_)  => ZIO.unit

  def removeFile(path: String): UIO[Unit] =
    stepsRef.update(_ - path) *> featuresRef.update(_ - path)

  def findStep(keyword: String, text: String): UIO[StepMatcher.MatchResult] =
    allSteps.map(StepMatcher.find(keyword, text, _))

  /**
   * Walk `workspaceRoot` and index every .scala / .feature file in parallel.
   * Missing roots and individual unreadable files are silently skipped.
   */
  def initialScan(workspaceRoot: String): UIO[Unit] =
    ZIO
      .attemptBlocking(collectFiles(Paths.get(workspaceRoot)))
      .orElseSucceed((Nil, Nil))
      .flatMap { (scalaFiles, featureFiles) =>
        for
          _ <- ZIO.logInfo(s"initialScan: ${scalaFiles.size} .scala files, ${featureFiles.size} .feature files")
          _ <- ZIO.foreachParDiscard(scalaFiles)(p => readFile(p).flatMap(indexScalaFile(p, _)))
          _ <- ZIO.foreachParDiscard(featureFiles)(p => readFile(p).flatMap(indexFeatureFile(p, _)))
        yield ()
      }

  /** Returns (scalaPaths, featurePaths). Empty lists if root is missing. */
  private def collectFiles(root: Path): (List[String], List[String]) =
    if !Files.exists(root) then (Nil, Nil)
    else
      val all = Files
        .walk(root)
        .iterator()
        .asScala
        .filter(Files.isRegularFile(_))
        .map(_.toAbsolutePath.toString)
        .toList
      (all.filter(_.endsWith(".scala")), all.filter(_.endsWith(".feature")))

  private def readFile(path: String): UIO[String] =
    ZIO.attemptBlocking(Files.readString(Paths.get(path))).orElseSucceed("")

object WorkspaceIndex:
  val layer: ULayer[WorkspaceIndex] =
    ZLayer.fromZIO(
      for
        s <- Ref.make(Map.empty[String, List[StepDefinition]])
        f <- Ref.make(Map.empty[String, Feature])
      yield WorkspaceIndex(s, f)
    )
