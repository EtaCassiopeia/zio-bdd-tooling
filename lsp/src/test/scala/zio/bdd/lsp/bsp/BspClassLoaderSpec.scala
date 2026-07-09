package zio.bdd.lsp.bsp

import zio.*
import zio.test.*
import zio.bdd.lsp.{RuntimeMockSummary, RuntimeStepSummary}

object BspClassLoaderSpec extends ZIOSpecDefault:

  private val envelope =
    """{"steps":[{"keyword":"Given","pattern":"^ready$","displayText":"ready"}],""" +
      """"mocks":[{"name":"userService","sourceKind":"Dsl"},{"name":"paymentGateway","sourceKind":"Json"}]}"""

  def spec = suite("BspClassLoader")(
    test("parses step summaries from the {steps,mocks} envelope, ignoring mock objects") {
      assertTrue(
        BspClassLoader.parseSteps(envelope) ==
          List(RuntimeStepSummary("Given", "^ready$", "ready"))
      )
    },
    test("parses mock summaries from the envelope, ignoring step objects") {
      assertTrue(
        BspClassLoader.parseMocks(envelope) ==
          List(RuntimeMockSummary("userService", "Dsl"), RuntimeMockSummary("paymentGateway", "Json"))
      )
    },
    test("an empty envelope yields no steps and no mocks") {
      val empty = """{"steps":[],"mocks":[]}"""
      assertTrue(BspClassLoader.parseSteps(empty).isEmpty, BspClassLoader.parseMocks(empty).isEmpty)
    },
    test("a mocks-only envelope yields no steps") {
      val json = """{"steps":[],"mocks":[{"name":"svc","sourceKind":"File"}]}"""
      assertTrue(
        BspClassLoader.parseSteps(json).isEmpty,
        BspClassLoader.parseMocks(json) == List(RuntimeMockSummary("svc", "File"))
      )
    },
    test("a step whose pattern/displayText contains braces round-trips through serialize + parse") {
      // #40.2: a regex quantifier like \d{3} must not truncate the JSON object
      // and get silently dropped by the brace-blind `\{[^{}]+\}` scanner.
      val json = StepLoader.serialize(List(("Given", """\d{3}""", "code {int} here")), Nil)
      assertTrue(
        BspClassLoader.parseSteps(json) == List(RuntimeStepSummary("Given", """\d{3}""", "code {int} here")),
        // braces are escaped on the wire, so the raw envelope carries no literal `{3}`
        !json.contains("{3}")
      )
    },
    test("a mock whose fields contain braces round-trips through serialize + parse") {
      val json = StepLoader.serialize(Nil, List(("svc{1}", "Json")))
      assertTrue(BspClassLoader.parseMocks(json) == List(RuntimeMockSummary("svc{1}", "Json")))
    },
    test("captureStdout drains a flooding stderr without deadlocking") {
      // #40.1: >64KB of stderr before the child exits would deadlock a parent
      // that only reads stdout. captureStdout must drain stderr concurrently.
      val script =
        """for i in $(seq 1 50000); do echo "stderr noise line $i" 1>&2; done; printf '%s' '{"steps":[],"mocks":[]}'"""
      val proc = new ProcessBuilder("bash", "-c", script).start()
      assertTrue(BspClassLoader.captureStdout(proc, 30) == Right("""{"steps":[],"mocks":[]}"""))
    },
    test("captureStdout reports a non-zero exit as a Left reason instead of an empty success") {
      val proc = new ProcessBuilder("bash", "-c", "echo boom 1>&2; exit 7").start()
      assertTrue(BspClassLoader.captureStdout(proc, 30) match
        case Left(reason) => reason.contains("7")
        case Right(_)     => false
      )
    },
    test("captureStdout reports a timeout as a Left reason for a child that never exits") {
      // The child stalls without closing stdout; captureStdout must still return
      // via the deadline rather than blocking forever (#40).
      val proc = new ProcessBuilder("bash", "-c", "sleep 30").start()
      assertTrue(BspClassLoader.captureStdout(proc, 1) match
        case Left(reason) => reason.contains("timed out")
        case Right(_)     => false
      )
    },
    test("a value literally containing the text \\u007b round-trips (not mis-decoded to a brace)") {
      // In Scala 3, "\\u007b" is the raw 6-char sequence, not '{'. The encoder turns
      // it into \\u007b on the wire; the single-pass decoder must restore the text.
      val literal = "\\u007b end \\u007d"
      val json    = StepLoader.serialize(List(("Given", literal, "x")), Nil)
      assertTrue(BspClassLoader.parseSteps(json) == List(RuntimeStepSummary("Given", literal, "x")))
    },
    test("unrecognizedObjectCount is 0 for a well-formed envelope") {
      assertTrue(BspClassLoader.unrecognizedObjectCount(envelope) == 0)
    },
    test("unrecognizedObjectCount flags an object that is neither a step nor a mock") {
      // A malformed/truncated object (here: neither `keyword`- nor `name`-first, and
      // too few fields) used to be dropped silently (#47).
      val json =
        """{"steps":[{"keyword":"Given","pattern":"^x$","displayText":"x"}],""" +
          """"mocks":[{"name":"svc","sourceKind":"Dsl"}],"junk":[{"bogus":"1"}]}"""
      assertTrue(BspClassLoader.unrecognizedObjectCount(json) == 1)
    },
    test("unrecognizedObjectCount is 0 for an empty envelope") {
      assertTrue(BspClassLoader.unrecognizedObjectCount("""{"steps":[],"mocks":[]}""") == 0)
    }
  ) @@ TestAspect.timeout(60.seconds)
