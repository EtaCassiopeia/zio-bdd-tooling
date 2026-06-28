package zio.bdd.intellij.lang

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ZioBddCompletionTest : BasePlatformTestCase() {

    fun testTagCompletionOffersBuiltins() {
        myFixture.configureByText("a.feature", "@<caret>\nFeature: F\n")
        val items = myFixture.completeBasic()?.map { it.lookupString } ?: emptyList()
        assertTrue("expected @ignore among $items", items.contains("@ignore"))
        assertTrue("expected @flags among $items", items.any { it.startsWith("@flags") })
    }

    fun testTagInsertNoDoubleAt() {
        myFixture.configureByText("b.feature", "@<caret>\nFeature: F\n")
        val items  = myFixture.completeBasic()
        val ignore = items?.firstOrNull { it.lookupString == "@ignore" }
        assertNotNull("@ignore should be offered", ignore)
        myFixture.lookup.currentItem = ignore
        myFixture.finishLookup('\n')
        val text = myFixture.file.text
        assertFalse("doubled @ in <<$text>>", text.contains("@@"))
        assertTrue("@ignore inserted in <<$text>>", text.contains("@ignore"))
    }

    fun testStepCompletionOffersRegisteredSteps() {
        myFixture.addFileToProject(
            "CalcSteps.scala",
            """
            |class CalcSteps {
            |  Given("the cart has " / int / " items") { n => () }
            |  Given("the order is confirmed") { () }
            |  When("the user checks out") { () }
            |}
            """.trimMargin(),
        )
        // No explicit warm — the provider must warm the cache itself on first use.
        myFixture.configureByText("d.feature", "Feature: F\n  Scenario: S\n    Given the <caret>\n")
        val items = myFixture.completeBasic()?.map { it.lookupString } ?: emptyList()
        assertTrue("expected a Given step suggestion, got $items", items.any { it.contains("the cart has") })
    }

    fun testStructuralKeywordCompletion() {
        myFixture.configureByText("c.feature", "Feature: F\n  <caret>\n")
        val items = myFixture.completeBasic()?.map { it.lookupString } ?: emptyList()
        assertTrue("expected Scenario: among $items", items.contains("Scenario:"))
        assertTrue("expected Background: among $items", items.contains("Background:"))
    }
}
