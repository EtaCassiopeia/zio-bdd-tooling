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
    if trimmed.startsWith("@") then tagCompletion(index)
    else
      kws.find(kw => trimmed.startsWith(s"$kw ") || trimmed == kw) match
        case Some(kw) =>
          val typed = if trimmed == kw then "" else trimmed.drop(kw.length + 1)
          index.allSteps.map { defs =>
            StepMatcher
              .candidatesFor(kw, defs)
              .filter(d => typed.isEmpty || d.displayText.toLowerCase.contains(typed.toLowerCase))
              .map(featureCompletionItem)
              .take(50)
          }
        case None => ZIO.succeed(structuralCompletion(trimmed))

  // ── Tag completion ─────────────────────────────────────────────────────────

  // Built-in tags always offered alongside the workspace-collected ones.
  private val builtinTags = List("@ignore", "@flags(key=value)")

  private def tagCompletion(index: WorkspaceIndex): UIO[List[CompletionItem]] =
    index.allTags.map { tags =>
      val collected = tags.toList.sorted.map(name => s"@$name")
      (builtinTags ++ collected).distinct.map { tag =>
        val item = new CompletionItem(tag)
        item.setKind(CompletionItemKind.Text)
        // The user has already typed the "@" that triggered completion; inserting a
        // tag that also starts with "@" would yield "@@tag". Insert/filter on the
        // name without the leading "@" so the result is a single "@tag".
        val withoutAt = tag.stripPrefix("@")
        item.setInsertText(withoutAt)
        item.setFilterText(withoutAt)
        item
      }
    }

  // ── Structural keyword & template completion ───────────────────────────────

  // (label, insertText) for plain structural keywords inserted verbatim.
  private val structuralKeywords = List(
    "Feature:"          -> "Feature: ",
    "Background:"       -> "Background:\n  ",
    "Scenario:"         -> "Scenario: ",
    "Scenario Outline:" -> "Scenario Outline: ",
    "Rule:"             -> "Rule: ",
    "Examples:"         -> "Examples:\n  | | |\n  | | |"
  )

  // (label, snippet) templates expanded with LSP tab stops on selection.
  private val structuralTemplates = List(
    "Scenario (template)" ->
      "Scenario: ${1:title}\n  Given ${2:precondition}\n  When ${3:action}\n  Then ${4:outcome}",
    "Scenario Outline (template)" ->
      "Scenario Outline: ${1:title}\n  Given ${2:step with <${3:param}>}\n  Examples:\n    | ${3:param} |\n    | ${4:value} |",
    "Background (template)" ->
      "Background:\n  Given ${1:shared precondition}",
    "Feature (template)" ->
      "Feature: ${1:name}\n\n  Scenario: ${2:first scenario}\n    Given ${3:precondition}\n    When ${4:action}\n    Then ${5:outcome}"
  )

  private def insertItem(
    label: String,
    kind: CompletionItemKind,
    insert: String,
    format: InsertTextFormat
  ): CompletionItem =
    val item = new CompletionItem(label)
    item.setKind(kind)
    item.setInsertText(insert)
    item.setInsertTextFormat(format)
    item

  private def structuralCompletion(trimmed: String): List[CompletionItem] =
    val keep = (label: String) => trimmed.isEmpty || label.toLowerCase.startsWith(trimmed.toLowerCase)
    val keywordItems = structuralKeywords.collect {
      case (label, insert) if keep(label) =>
        insertItem(label, CompletionItemKind.Keyword, insert, InsertTextFormat.PlainText)
    }
    val templateItems = structuralTemplates.collect {
      case (label, snippet) if keep(label) =>
        insertItem(label, CompletionItemKind.Snippet, snippet, InsertTextFormat.Snippet)
    }
    keywordItems ++ templateItems

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
