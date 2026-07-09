package zio.bdd.intellij.lang

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Integration regression test for #41: suiteNamesForFeature must resolve the
 * single owning suite (via @Suite(featureDirs=...)) instead of falling back to the
 * "*" selector that ran a scenario against every suite. Exercises the real path —
 * a live Project + VirtualFile with a synchronous cache warm.
 */
class ZioBddStepCacheOwnershipTest : BasePlatformTestCase() {

    fun testResolvesOwningSuiteInsteadOfStar() {
        val feature = myFixture.addFileToProject(
            "features/calc/calculator.feature",
            "Feature: Calculator\n  Scenario: Add\n    When I add 3 and 5\n    Then the result should be 8\n",
        )
        // Declare the suite's featureDirs as the feature's actual directory so the
        // owner resolves regardless of the fixture's project base path.
        val dir = feature.virtualFile.parent.path
        myFixture.addFileToProject(
            "CalculatorSuite.scala",
            "@Suite(featureDirs = Array(\"$dir\"))\nobject CalculatorSuite extends ZIOSteps[Any, S]\n",
        )
        val cache = ZioBddStepCache.getInstance(myFixture.project)
        cache.invalidate()

        val selector = cache.suiteNamesForFeature(feature.virtualFile)

        assertEquals("run must target the owning suite, not fan out via \"*\"", "*CalculatorSuite*", selector)
    }

    fun testUnownedFeatureDoesNotResolveToThatSuite() {
        val feature = myFixture.addFileToProject(
            "features/auth/user_auth.feature",
            "Feature: Auth\n  Scenario: Login\n    When the user logs in\n",
        )
        myFixture.addFileToProject(
            "CalculatorSuite.scala",
            "@Suite(featureDirs = Array(\"/nowhere/calculator\"))\nobject CalculatorSuite extends ZIOSteps[Any, S]\n",
        )
        val cache = ZioBddStepCache.getInstance(myFixture.project)
        cache.invalidate()

        // No @Suite owns this feature and no step defs match → the CalculatorSuite
        // owner must NOT be selected for it.
        val selector = cache.suiteNamesForFeature(feature.virtualFile)
        assertFalse("unowned feature must not select CalculatorSuite", selector == "*CalculatorSuite*")
    }
}
