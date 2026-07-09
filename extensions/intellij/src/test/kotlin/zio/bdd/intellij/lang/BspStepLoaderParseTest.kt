package zio.bdd.intellij.lang

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BspStepLoaderParseTest : BasePlatformTestCase() {

    private val envelope =
        """{"steps":[{"keyword":"Given","pattern":"^ready$","displayText":"ready"}],""" +
            """"mocks":[{"name":"userService","sourceKind":"Dsl"},{"name":"paymentGateway","sourceKind":"Json"}]}"""

    fun testParsesStepsIgnoringMockObjects() {
        val steps = BspStepLoader.parseSteps(envelope)
        assertEquals(1, steps.size)
        assertEquals("Given", steps[0].keyword)
        assertEquals("ready", steps[0].displayText)
        assertEquals("^ready$", steps[0].pattern)
    }

    fun testParsesMocksIgnoringStepObjects() {
        val mocks = BspStepLoader.parseMocks(envelope)
        assertEquals(
            listOf(KtMockSummary("userService", "Dsl"), KtMockSummary("paymentGateway", "Json")),
            mocks,
        )
    }

    fun testEmptyEnvelopeYieldsNothing() {
        val empty = """{"steps":[],"mocks":[]}"""
        assertTrue(BspStepLoader.parseSteps(empty).isEmpty())
        assertTrue(BspStepLoader.parseMocks(empty).isEmpty())
    }

    fun testMocksOnlyEnvelopeYieldsNoSteps() {
        val json = """{"steps":[],"mocks":[{"name":"svc","sourceKind":"File"}]}"""
        assertTrue(BspStepLoader.parseSteps(json).isEmpty())
        assertEquals(listOf(KtMockSummary("svc", "File")), BspStepLoader.parseMocks(json))
    }

    fun testDecodesBraceUnicodeEscapes() {
        // #40.2: StepLoader escapes braces as {/} on the wire so the
        // brace-blind object scanner keeps the object intact; parse must decode them.
        // Build the raw wire tokens via concatenation to avoid Kotlin escape ambiguity.
        val bs = "\\"
        val open = "${bs}u007b"
        val close = "${bs}u007d"
        val json =
            """{"steps":[{"keyword":"When","pattern":"${bs}d${open}3${close}","displayText":"code ${open}int${close}"}],"mocks":[]}"""
        val steps = BspStepLoader.parseSteps(json)
        assertEquals(1, steps.size)
        assertEquals("""\d{3}""", steps[0].pattern)
        assertEquals("code {int}", steps[0].displayText)
    }

    fun testEscapedBackslashBeforeUIsNotMisdecodedToBrace() {
        // An escaped backslash (\\) followed by the text u007b must decode to the
        // raw 6-char text {, NOT to '{' (#40 single-pass unescaper).
        val bs = "\\"
        val json = """{"steps":[{"keyword":"When","pattern":"$bs${bs}u007b","displayText":"x"}],"mocks":[]}"""
        val steps = BspStepLoader.parseSteps(json)
        assertEquals(1, steps.size)
        assertEquals("${bs}u007b", steps[0].pattern)
    }

    fun testUnrecognizedObjectCountIsZeroForWellFormedAndEmptyEnvelopes() {
        assertEquals(0, BspStepLoader.unrecognizedObjectCount(envelope))
        assertEquals(0, BspStepLoader.unrecognizedObjectCount("""{"steps":[],"mocks":[]}"""))
    }

    fun testUnrecognizedObjectCountFlagsAnUnparseableObject() {
        // An object that is neither a step nor a mock used to be dropped silently (#47).
        val json =
            """{"steps":[{"keyword":"Given","pattern":"^x$","displayText":"x"}],""" +
                """"mocks":[{"name":"svc","sourceKind":"Dsl"}],"junk":[{"bogus":"1"}]}"""
        assertEquals(1, BspStepLoader.unrecognizedObjectCount(json))
    }
}
