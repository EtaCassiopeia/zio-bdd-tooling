package zio.bdd.intellij.lang

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ZioBddCompletionTest : BasePlatformTestCase() {

    fun testTypedHandlerTriggersAutoPopupOnAt() {
        myFixture.configureByText("g.feature", "<caret>\nFeature: F\n")
        val result = ZioBddTypedHandler()
            .checkAutoPopup('@', myFixture.project, myFixture.editor, myFixture.file)
        assertEquals(TypedHandlerDelegate.Result.STOP, result)
    }

    fun testTypedHandlerIgnoresNonTriggerChars() {
        myFixture.configureByText("h.feature", "<caret>\nFeature: F\n")
        val result = ZioBddTypedHandler()
            .checkAutoPopup('x', myFixture.project, myFixture.editor, myFixture.file)
        assertEquals(TypedHandlerDelegate.Result.CONTINUE, result)
    }

    // The handler is registered globally, so the `file is ZioBddFile` guard is what
    // keeps "@" from scheduling our tag popup in unrelated editors.
    fun testTypedHandlerIgnoresNonFeatureFiles() {
        myFixture.configureByText("notes.txt", "<caret>\n")
        val result = ZioBddTypedHandler()
            .checkAutoPopup('@', myFixture.project, myFixture.editor, myFixture.file)
        assertEquals(TypedHandlerDelegate.Result.CONTINUE, result)
    }

    // Exercises the goto handler entry path: a step with no matching definition
    // returns null. The matching/navigation path can't be unit-tested in the light
    // fixture — the handler resolves the target via LocalFileSystem, but fixture
    // files live in an in-memory VFS, so the lookup always misses here. The
    // FakePsiElement navigation target itself is covered by review (it inherits the
    // PsiElement defaults rather than hand-delegating them).
    fun testGotoReturnsNullForUnknownStep() {
        ZioBddStepCache.getInstance(myFixture.project).invalidate()
        myFixture.configureByText("g2.feature", "Feature: F\n  Scenario: S\n    Given nothing matches th<caret>is\n")
        val element = myFixture.file.findElementAt(myFixture.caretOffset)
        val targets = ZioBddGotoStepHandler()
            .getGotoDeclarationTargets(element, myFixture.caretOffset, myFixture.editor)
        assertNull("no target expected when no step definition matches", targets)
    }

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
        // The light fixture reuses one project across tests, so the project-scoped
        // cache leaks between them — invalidate before warming for this test's files.
        ZioBddStepCache.getInstance(myFixture.project).apply { invalidate(); ensureWarmed() }
        myFixture.configureByText("d.feature", "Feature: F\n  Scenario: S\n    Given the <caret>\n")
        val items = myFixture.completeBasic()?.map { it.lookupString } ?: emptyList()
        assertTrue("expected a Given step suggestion, got $items", items.any { it.contains("the cart has") })
    }

    fun testStepKeywordOffered() {
        // Blank line → many items (keywords + structural), so no auto-insert.
        myFixture.configureByText("e.feature", "Feature: F\n  Scenario: S\n    <caret>\n")
        val items = myFixture.completeBasic()?.map { it.lookupString } ?: emptyList()
        assertTrue("expected step keywords offered, got $items", items.containsAll(listOf("Given", "When", "Then")))
    }

    fun testStepInsertFillsExampleValues() {
        myFixture.addFileToProject(
            "DecSteps.scala",
            """
            |class DecSteps {
            |  Then("the decimal result should be " / double) { d => () }
            |}
            """.trimMargin(),
        )
        ZioBddStepCache.getInstance(myFixture.project).apply { invalidate(); ensureWarmed() }
        myFixture.configureByText("f.feature", "Feature: F\n  Scenario: S\n    Then the dec<caret>\n")
        myFixture.completeBasic() // single match auto-inserts and runs the template
        val text = myFixture.file.text
        assertFalse("placeholder left literal in <<$text>>", text.contains("{double}"))
        assertTrue("example value not filled in <<$text>>", text.contains("9.99"))
    }

    fun testStructuralKeywordCompletion() {
        myFixture.configureByText("c.feature", "Feature: F\n  <caret>\n")
        val items = myFixture.completeBasic()?.map { it.lookupString } ?: emptyList()
        assertTrue("expected Scenario: among $items", items.contains("Scenario:"))
        assertTrue("expected Background: among $items", items.contains("Background:"))
    }
}
