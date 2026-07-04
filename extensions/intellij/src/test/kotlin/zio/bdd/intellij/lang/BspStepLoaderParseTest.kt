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
}
