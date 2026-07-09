package zio.bdd.intellij.lang

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Regression tests for #42: the static source scan must populate the cache
 * independently of the (fallible) BSP class-load, and the synchronous warm must
 * scan once rather than re-scan the whole project on every call.
 */
class ZioBddStepCacheRefreshTest : BasePlatformTestCase() {

    fun testStaticScanPopulatesStepsWithoutBsp() {
        // ensureStaticWarmed does NOT run the BSP subprocess, so this proves the
        // static scan alone drives the snapshot — the invariant doRefresh relies on
        // when it publishes the static scan before the BSP upgrade.
        myFixture.addFileToProject(
            "CalcSteps.scala",
            "object CalcSteps extends ZIOSteps[Any, S] {\n  Given(\"a fresh calculator\") { ZIO.unit }\n}\n",
        )
        val cache = ZioBddStepCache.getInstance(myFixture.project)
        cache.invalidate()
        cache.ensureStaticWarmed()

        val texts = cache.getStepDefinitions().map { it.displayText }
        assertTrue("static scan should surface the step, got $texts", texts.any { it.contains("a fresh calculator") })
    }

    fun testStaticWarmIsIdempotentAndSurvivesRepeatedCalls() {
        myFixture.addFileToProject(
            "MoreSteps.scala",
            "object MoreSteps extends ZIOSteps[Any, S] {\n  When(\"I do a thing\") { ZIO.unit }\n}\n",
        )
        val cache = ZioBddStepCache.getInstance(myFixture.project)
        cache.invalidate()
        cache.ensureStaticWarmed()
        val first = cache.getStepDefinitions().size
        assertTrue("expected steps after first warm", first > 0)

        // A second warm must not clear or rescan away the populated snapshot.
        cache.ensureStaticWarmed()
        assertEquals("repeated warm must keep the snapshot", first, cache.getStepDefinitions().size)
    }
}
