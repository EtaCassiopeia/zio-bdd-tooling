package zio.bdd.intellij.hints

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import zio.bdd.intellij.lang.KtStepDefinition

class ZioBddStepDataFlowLabelTest {

    private val calcSteps =
        "object CalcSteps extends ZIOSteps[Any, S] {\n" +
            "  Given(\"a fresh calculator\") { ScenarioContext.update(_.copy(result = 0)) }\n" +
            "  When(\"I add \" / int / \" and \" / int) { (a: Int, b: Int) =>\n" +
            "    Stage.put(Sum(a + b)) *> ScenarioContext.update(_.copy(result = a + b))\n" +
            "  }\n" +
            "}\n"

    private fun def(keyword: String, pattern: String, line: Int) =
        KtStepDefinition(
            keyword = keyword,
            displayText = pattern,
            pattern = pattern,
            literals = emptyList(),
            extractorCount = 0,
            file = "/src/CalcSteps.scala",
            line = line,
        )

    private val defs = listOf(
        def("Given", "a fresh calculator", 1),
        def("When", "I add (-?\\d+) and (-?\\d+)", 2),
    )

    private fun content(@Suppress("UNUSED_PARAMETER") path: String) = calcSteps

    @Test
    fun labelsAStepThatSetsState() {
        val label = ZioBddStepDataFlowLabel.compute("Given", "a fresh calculator", defs, ::content)
        assertEquals("sets result", label)
    }

    @Test
    fun labelsAStepThatSetsBothStageAndState() {
        val label = ZioBddStepDataFlowLabel.compute("When", "I add 2 and 3", defs, ::content)
        assertEquals("sets result, Stage[Sum]", label)
    }

    @Test
    fun nullWhenNoStepMatches() {
        assertNull(ZioBddStepDataFlowLabel.compute("Then", "nothing matches", defs, ::content))
    }

    @Test
    fun nullWhenBlankStepText() {
        assertNull(ZioBddStepDataFlowLabel.compute("Given", "   ", defs, ::content))
    }

    @Test
    fun nullWhenContentUnavailable() {
        assertNull(ZioBddStepDataFlowLabel.compute("Given", "a fresh calculator", defs, { null }))
    }
}
