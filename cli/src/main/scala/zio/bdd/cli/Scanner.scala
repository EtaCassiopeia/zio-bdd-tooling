package zio.bdd.cli

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import zio.bdd.lsp.{GherkinBridge, StepDefinition, StepMatcher, StepExtractor}
import zio.bdd.gherkin.{Feature, StepType}

object Scanner:

  case class UnmatchedStep(
    keyword: String,
    text: String,
    featureFile: Path,
    line: Int
  )

  def collectStepDefs(root: Path): List[StepDefinition] =
    scalaFiles(root).flatMap { f =>
      StepExtractor.extractFromSource(Files.readString(f), f.toString)
    }

  def collectFeatures(root: Path): List[(Path, Feature)] =
    featureFiles(root).flatMap { f =>
      GherkinBridge.parseFeature(Files.readString(f), f.toString).toOption.map(f -> _)
    }

  def unmatchedSteps(root: Path): List[UnmatchedStep] =
    val defs     = collectStepDefs(root)
    val features = collectFeatures(root)
    features.flatMap { case (path, feature) =>
      feature.scenarios.flatMap { scenario =>
        scenario.steps.flatMap { step =>
          val keyword = stepKeyword(step.stepType)
          val text    = step.pattern
          StepMatcher.find(keyword, text, defs) match
            case StepMatcher.MatchResult.NoMatch(_) =>
              List(UnmatchedStep(keyword, text, path, step.line.getOrElse(0)))
            case _ => Nil
        }
      }
    }

  def toSkeleton(u: UnmatchedStep): String =
    val escaped = u.text.replace("\\", "\\\\").replace("\"", "\\\"")
    s"""${u.keyword}("$escaped") {
       |  ZIO.unit // TODO implement
       |}""".stripMargin

  private def stepKeyword(st: StepType): String = st match
    case StepType.GivenStep => "Given"
    case StepType.WhenStep  => "When"
    case StepType.ThenStep  => "Then"
    case StepType.AndStep   => "And"
    case StepType.ButStep   => "But"

  private def scalaFiles(root: Path): List[Path] =
    Files
      .walk(root)
      .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".scala"))
      .iterator()
      .asScala
      .toList

  private def featureFiles(root: Path): List[Path] =
    Files
      .walk(root)
      .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".feature"))
      .iterator()
      .asScala
      .toList
