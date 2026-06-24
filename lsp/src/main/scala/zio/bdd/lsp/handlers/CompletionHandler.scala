package zio.bdd.lsp.handlers

import org.eclipse.lsp4j.*
import zio.*
import zio.bdd.lsp.{ExtractorInfo, GherkinLine, StepDefinition, StepMatcher, WorkspaceIndex}

/**
 * textDocument/completion for both .feature and .scala files.
 *
 * .feature files: suggest registered step definitions as tab-stop snippets
 * .scala files: suggest unimplemented feature-file steps inside a step-call
 * string, or extractor names after `/`
 */
object CompletionHandler:

  def complete(
    uri: String,
    position: Position,
    content: String,
    index: WorkspaceIndex
  ): UIO[List[CompletionItem]] =
    if uri.endsWith(".feature") then featureCompletion(position, content, index)
    else if uri.endsWith(".scala") then scalaCompletion(position, content, index)
    else ZIO.succeed(Nil)

  // ── Feature file completion ────────────────────────────────────────────────

  private def featureCompletion(
    position: Position,
    content: String,
    index: WorkspaceIndex
  ): UIO[List[CompletionItem]] =
    val line    = content.linesIterator.toList.lift(position.getLine).getOrElse("")
    val trimmed = line.trim
    val kws     = List("Given", "When", "Then", "And", "But", "*")
    kws.find(kw => trimmed.startsWith(s"$kw ") || trimmed == kw) match
      case None => ZIO.succeed(Nil)
      case Some(kw) =>
        val typed = if trimmed == kw then "" else trimmed.drop(kw.length + 1)
        index.allSteps.map { defs =>
          StepMatcher
            .candidatesFor(kw, defs)
            .filter(d => typed.isEmpty || d.displayText.toLowerCase.contains(typed.toLowerCase))
            .map(featureCompletionItem)
            .take(50)
        }

  private def featureCompletionItem(defn: StepDefinition): CompletionItem =
    val item = new CompletionItem(defn.displayText)
    item.setKind(CompletionItemKind.Function)
    val fileName = defn.file.split("[/\\\\]").lastOption.getOrElse(defn.file)
    item.setDetail(s"$fileName:${defn.line + 1}")
    item.setInsertText(buildSnippet(defn))
    item.setInsertTextFormat(InsertTextFormat.Snippet)
    if defn.extractors.nonEmpty then
      val doc = defn.extractors.zipWithIndex.map { case (e, i) =>
        s"${i + 1}. `${e.name}` → `${e.scalaType}` — ${e.description}"
      }
        .mkString("\n")
      item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, doc))
    item

  private def buildSnippet(defn: StepDefinition): String =
    var tabStop = 1
    val sb      = new StringBuilder
    val maxLen  = defn.literals.length max defn.extractors.length
    (0 until maxLen).foreach { i =>
      defn.literals.lift(i).foreach(sb.append)
      defn.extractors.lift(i).foreach { ext =>
        sb.append(s"$${$tabStop:${exampleValue(ext)}}")
        tabStop += 1
      }
    }
    sb.toString

  private def exampleValue(ext: ExtractorInfo): String = ext.name match
    case "int" | "long"          => "42"
    case "double" | "bigDecimal" => "9.99"
    case "boolean"               => "true"
    case "uuid"                  => "00000000-0000-0000-0000-000000000000"
    case "string" | "word"       => "value"
    case _                       => ext.name

  // ── Scala file completion ──────────────────────────────────────────────────

  private def scalaCompletion(
    position: Position,
    content: String,
    index: WorkspaceIndex
  ): UIO[List[CompletionItem]] =
    val line = content.linesIterator.toList.lift(position.getLine).getOrElse("")
    if isAfterSlash(line, position.getCharacter) then extractorCompletion
    else if isInsideStepString(line, position.getCharacter) then unimplementedStepCompletion(index)
    else ZIO.succeed(Nil)

  private val extractorCompletion: UIO[List[CompletionItem]] =
    ZIO.succeed {
      ExtractorInfo.allNames.map { name =>
        val item = new CompletionItem(name)
        item.setKind(CompletionItemKind.Value)
        ExtractorInfo.builtins.get(name).foreach { e =>
          item.setDetail(s"→ ${e.scalaType}")
          item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, e.description))
        }
        item
      }
    }

  private def unimplementedStepCompletion(index: WorkspaceIndex): UIO[List[CompletionItem]] =
    for
      allDefs     <- index.allSteps
      allFeatures <- index.allFeatures
    yield allFeatures
      .flatMap(_.scenarios)
      .flatMap(_.steps)
      .filter { step =>
        val kw = step.stepType.toString.replace("Step", "")
        StepMatcher.find(kw, step.pattern, allDefs).isInstanceOf[StepMatcher.MatchResult.NoMatch]
      }
      .distinctBy(_.pattern)
      .take(30)
      .map { step =>
        val item = new CompletionItem(s""""${step.pattern}"""")
        item.setKind(CompletionItemKind.Text)
        item.setDetail("Unimplemented step (from feature files)")
        item
      }

  private def isAfterSlash(line: String, char: Int): Boolean =
    line.take(char).trim.endsWith("/")

  private def isInsideStepString(line: String, char: Int): Boolean =
    val before = line.take(char)
    val kwRe   = """.*\b(Given|When|Then|And|But|GivenS|WhenS|ThenS|AndS|ButS)\s*\("""
    before.matches(s"$kwRe.*") && before.count(_ == '"') % 2 == 1
