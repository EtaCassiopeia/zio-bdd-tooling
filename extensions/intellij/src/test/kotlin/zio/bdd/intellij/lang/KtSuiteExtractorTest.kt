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
    fun bindsToRealObjectDespiteCommentMentioningDeclTokens() {
        // The declaration search is anchored to immediately after @Suite, so an
        // `object`/`class` token inside an intervening comment is not grabbed (#55).
        val src =
            """
            @Suite(featureDirs = Array("f/x"))
            // helper builds an internal object Foo and a class Bar
            /* another object Baz mentioned here */
            object RealSuite extends ZIOSteps[Any, S]
            """.trimIndent()
        val suites = KtSuiteExtractor.extractFromSource(src)
        assertEquals(listOf(KtSuiteDecl("RealSuite", listOf("f/x"))), suites)
    }

    @Test
    fun bindsToImmediatelyFollowingDeclaration() {
        // @Suite annotates the next declaration; a trait there binds to the trait.
        val src =
            """
            @Suite(featureDirs = Array("f/a"))
            trait Helper
            object A extends ZIOSteps[Any, S]
            """.trimIndent()
        assertEquals(listOf(KtSuiteDecl("Helper", listOf("f/a"))), KtSuiteExtractor.extractFromSource(src))
    }

    @Test
    fun dropsSuiteNotOnADeclaration() {
        val src =
            """
            @Suite(featureDirs = Array("f/a"))
            val notASuite = 42
            """.trimIndent()
        assertTrue(KtSuiteExtractor.extractFromSource(src).isEmpty())
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

    @Test
    fun resolvesOwnerWhenFeatureDirsIsAnExactFilePath() {
        // Real samples (ParallelIsolationSuite/HooksOrderingSuite) declare featureDirs
        // pointing at an exact .feature FILE, not a directory. The `feature == abs`
        // branch must resolve these — dropping it would regress them back to "*".
        val suites = listOf(
            KtSuiteDecl("HooksOrderingSuite", listOf("src/test/resources/features/core/hooks_ordering.feature")),
        )
        assertEquals(
            "HooksOrderingSuite",
            KtSuiteExtractor.ownerFor("/p/src/test/resources/features/core/hooks_ordering.feature", "/p", suites),
        )
    }

    @Test
    fun parsesSuiteWithoutFeatureDirsAsEmpty() {
        val src = """
            @Suite(reporters = Array("pretty"), logLevel = "info")
            object BareSuite extends ZIOSteps[Any, S]
        """.trimIndent()
        val suites = KtSuiteExtractor.extractFromSource(src)
        assertEquals(1, suites.size)
        assertEquals("BareSuite", suites[0].name)
        assertTrue(suites[0].featureDirs.isEmpty())
        assertNull(KtSuiteExtractor.ownerFor("/p/x.feature", "/p", suites))
    }

    @Test
    fun parsesMultipleSuitesInOneFile() {
        val src = """
            @Suite(featureDirs = Array("f/a"))
            object A extends ZIOSteps[Any, S]
            @Suite(featureDirs = Array("f/b"))
            object B extends ZIOSteps[Any, S]
        """.trimIndent()
        val suites = KtSuiteExtractor.extractFromSource(src)
        assertEquals(listOf("A", "B"), suites.map { it.name })
        assertEquals("B", KtSuiteExtractor.ownerFor("/p/f/b/x.feature", "/p", suites))
    }
}
