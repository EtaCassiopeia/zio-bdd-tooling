package zio.bdd.lsp

import zio.*
import zio.test.*

import java.io.{ByteArrayOutputStream, PrintStream}
import java.lang.System as JSystem

// The LSP server speaks JSON-RPC over stdout; any stray byte there corrupts the
// Content-Length framing and kills the connection. GherkinBridge parses feature
// files through the core GherkinParser, which emits `ZIO.logWarning` for
// malformed lines — those warnings must go to stderr, never stdout.
object GherkinBridgeSpec extends ZIOSpecDefault:

  // A non-keyword line after a valid step makes GherkinParser log a
  // "unrecognized line ignored (misspelled step keyword?)" warning.
  private val malformedFeature =
    """|Feature: F
       |  Scenario: S
       |    Given a precondition
       |    Wzz this is not a real step keyword
       |""".stripMargin

  def spec = suite("GherkinBridge")(
    test("parsing a malformed feature writes nothing to stdout") {
      for captured <- ZIO.attempt {
                        val original = JSystem.out
                        val buffer   = new ByteArrayOutputStream()
                        try
                          JSystem.setOut(new PrintStream(buffer, true, "UTF-8"))
                          val _ = GherkinBridge.parseFeature(malformedFeature, "/fake/malformed.feature")
                          buffer.toString("UTF-8")
                        finally JSystem.setOut(original)
                      }
      yield assertTrue(
        !captured.contains("unrecognized line"),
        !captured.toLowerCase.contains("level=warn")
      )
    } @@ TestAspect.sequential
  )
