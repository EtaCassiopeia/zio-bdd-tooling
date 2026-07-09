package zio.bdd.intellij.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KtSuiteExtractorTest {

    private val calculatorSuite = """
        package samples.calculator
        import zio.bdd.core.step.ZIOSteps

        @Suite(
          featureDirs = Array("src/test/resources/features/calculator"),
          reporters = Array("pretty"),
          logLevel = "info"
        )
        object CalculatorSuite extends ZIOSteps[Any, CalcState]:
          Given("a fresh calculator") { ScenarioContext.update(_ => CalcState()) }
    """.trimIndent()

    @Test
    fun parsesSuiteNameAndFeatureDirs() {
        val suites = KtSuiteExtractor.extractFromSource(calculatorSuite)
        assertEquals(1, suites.size)
        assertEquals("CalculatorSuite", suites[0].name)
        assertEquals(listOf("src/test/resources/features/calculator"), suites[0].featureDirs)
    }

    @Test
    fun parsesMultipleFeatureDirsAndClassSuites() {
        val src = """
            @Suite(featureDirs = Array("a/features", "b/features"))
            final class ShoppingSuite extends ZIOSteps[Any, S]
        """.trimIndent()
        val suites = KtSuiteExtractor.extractFromSource(src)
        assertEquals(1, suites.size)
        assertEquals("ShoppingSuite", suites[0].name)
        assertEquals(listOf("a/features", "b/features"), suites[0].featureDirs)
    }

    @Test
    fun ignoresSourceWithoutSuiteAnnotation() {
        assertTrue(KtSuiteExtractor.extractFromSource("object NotASuite extends ZIOSteps[Any, S]").isEmpty())
    }

    @Test
    fun resolvesOwningSuiteByDirectoryContainment() {
        val suites = listOf(
            KtSuiteDecl("CalculatorSuite", listOf("src/test/resources/features/calculator")),
            KtSuiteDecl("ShoppingSuite", listOf("src/test/resources/features/shopping")),
        )
        val base = "/home/u/proj"
        val owner = KtSuiteExtractor.ownerFor(
            "/home/u/proj/src/test/resources/features/calculator/calculator.feature", base, suites,
        )
        assertEquals("CalculatorSuite", owner)
    }

    @Test
    fun returnsNullWhenNoSuiteCoversTheFeature() {
        val suites = listOf(KtSuiteDecl("CalculatorSuite", listOf("src/test/resources/features/calculator")))
        val owner = KtSuiteExtractor.ownerFor(
            "/home/u/proj/src/test/resources/features/auth/user_auth.feature", "/home/u/proj", suites,
        )
        assertNull(owner)
    }

    @Test
    fun doesNotMatchOnDirectoryPrefixCollision() {
        // "features/calc" must not be treated as covering "features/calculator/..."
        val suites = listOf(KtSuiteDecl("CalcSuite", listOf("features/calc")))
        assertNull(
            KtSuiteExtractor.ownerFor("/p/features/calculator/x.feature", "/p", suites),
        )
    }
}
