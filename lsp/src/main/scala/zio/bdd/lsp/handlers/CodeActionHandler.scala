package zio.bdd.lsp.handlers

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either as JEither
import zio.*
import zio.bdd.gherkin.StepType
import zio.bdd.lsp.{GherkinBridge, StepDefinition, WorkspaceIndex}

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

// LSP textDocument/codeAction handler.
//
// When the cursor is on a step that has a "No step definition found" diagnostic,
// offers one code action per indexed step-definition file: "Add step skeleton to
// <File.scala>". The action inserts a minimal Given/When/Then(...) { ZIO.unit }
// block at the end of that file so the user can fill in the implementation.
//
// Limitations:
//   - The insertion always goes at the end of the file. For braceless
//     (significant-indentation) ZIOSteps subclasses this is correct; for
//     brace-style classes the user may need to move the snippet inside the
//     closing `}` manually.
//   - No extractor inference: the skeleton always uses the literal step text
//     (no / int / / string / fragments). The user adds extractors by hand.
object CodeActionHandler:

  def codeActions(
    featureUri: String,
    range: Range,
    context: CodeActionContext,
    content: String,
    index: WorkspaceIndex
  ): UIO[List[CodeAction]] =
    // Only act on our own "No step definition" diagnostics that overlap the
    // cursor / selection range.
    val relevant = context.getDiagnostics.asScala.toList
      .filter(d => "zio-bdd" == d.getSource && overlaps(d.getRange, range))

    if relevant.isEmpty then ZIO.succeed(Nil)
    else
      index.allSteps.flatMap { allDefs =>
        // Deduplicate by file — offer one action per target file.
        val targetFiles = allDefs.map(_.file).distinct.take(3)
        if targetFiles.isEmpty then ZIO.succeed(Nil)
        else
          ZIO
            .foreach(relevant) { diag =>
              val (keyword, stepText) = extractFromDiagnostic(diag.getMessage, content, diag.getRange)
              ZIO
                .foreach(targetFiles)(f => makeAction(featureUri, keyword, stepText, f))
                .map(_.collect { case Some(a) => a })
            }
            .map(_.flatten)
      }

  // ── internals ─────────────────────────────────────────────────────────────

  // Pull "step text" from a zio-bdd diagnostic message. The message starts
  // with `No step definition found for: "..."` or `No step definition found.`
  // (with a closest-match hint). We also derive the BDD keyword from the
  // feature file content at the diagnostic line.
  private def extractFromDiagnostic(
    message: String,
    content: String,
    range: Range
  ): (String, String) =
    // Keyword: read it from the feature file line at the diagnostic range.
    val lines    = content.linesIterator.toIndexedSeq
    val lineNum  = range.getStart.getLine
    val lineText = lines.lift(lineNum).getOrElse("").trim
    val keyword =
      List("Given", "When", "Then", "And", "But")
        .find(k => lineText.startsWith(k))
        .getOrElse("Given")

    // Step text: prefer the quoted text in the first line of the message;
    // fall back to reading it directly from the feature file.
    val quotedRe = """No step definition found for: "(.+)"""".r
    val stepText = quotedRe
      .findFirstMatchIn(message)
      .map(_.group(1))
      .orElse {
        // The "closest match" variant doesn't repeat the step text. Fall back
        // to stripping the keyword from the feature file line.
        val rest = lineText.dropWhile(!_.isWhitespace).trim
        if rest.nonEmpty then Some(rest) else None
      }
      .getOrElse("TODO: describe this step")
    (keyword, stepText)

  // Build a WorkspaceEdit that appends a step skeleton to `targetFile`.
  private def makeAction(
    featureUri: String,
    keyword: String,
    stepText: String,
    targetFile: String
  ): UIO[Option[CodeAction]] =
    ZIO.attemptBlocking {
      val path    = Paths.get(targetFile)
      val lines   = if Files.exists(path) then Files.readAllLines(path).size else 0
      val endPos  = new Position(lines, 0)
      val snippet = buildSnippet(keyword, stepText)
      val edit    = new TextEdit(new Range(endPos, endPos), snippet)
      val docEdit = new TextDocumentEdit(
        new VersionedTextDocumentIdentifier(s"file://$targetFile", null),
        java.util.List.of(edit)
      )
      val wsEdit = new WorkspaceEdit(
        java.util.List.of(JEither.forLeft[TextDocumentEdit, ResourceOperation](docEdit))
      )
      val action = new CodeAction(s"Add step skeleton to ${shortName(targetFile)}")
      action.setKind(CodeActionKind.QuickFix)
      action.setEdit(wsEdit)
      Some(action)
    }.orElseSucceed(None)

  private def buildSnippet(keyword: String, stepText: String): String =
    s"""
  $keyword("$stepText") {
    // TODO: implement
    ZIO.unit
  }
"""

  private def shortName(path: String): String =
    path.split("[/\\\\]").last

  private def overlaps(a: Range, b: Range): Boolean =
    val aStart = posToInt(a.getStart)
    val aEnd   = posToInt(a.getEnd)
    val bStart = posToInt(b.getStart)
    val bEnd   = posToInt(b.getEnd)
    aStart <= bEnd && bStart <= aEnd

  private def posToInt(p: Position): Int = p.getLine * 10000 + p.getCharacter
