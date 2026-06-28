package zio.bdd.intellij.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlineExamplesTest {

    @Test
    fun namesOneExactEntryPerLiteralRow() {
        val text = """
            |Feature: Math
            |  Scenario Outline: Subtraction table
            |    When I subtract <b> from <a>
            |    Then the result should be <diff>
            |
            |    Examples:
            |      | a  | b | diff |
            |      | 10 | 3 | 7    |
            |      | 0  | 5 | -5   |
            |      | 7  | 7 | 0    |
        """.trimMargin()
        assertEquals(
            listOf(
                "Subtraction table - Example 1",
                "Subtraction table - Example 2",
                "Subtraction table - Example 3",
            ),
            OutlineExamples.exampleNames(text, "Subtraction table"),
        )
    }

    @Test
    fun emptyForHeaderOnlyPropertyOutline() {
        val text = """
            |Feature: F
            |  Scenario Outline: Multiplying by one is a no-op
            |    When I multiply <a> by 1
            |    Examples:
            |      | a |
        """.trimMargin()
        assertTrue(OutlineExamples.exampleNames(text, "Multiplying by one is a no-op").isEmpty())
    }

    @Test
    fun stopsAtNextScenarioAndIgnoresOtherOutlines() {
        val text = """
            |Feature: F
            |  Scenario Outline: First
            |    Examples:
            |      | a |
            |      | 1 |
            |  Scenario Outline: Second
            |    Examples:
            |      | a |
            |      | 9 |
            |      | 8 |
        """.trimMargin()
        assertEquals(listOf("First - Example 1"), OutlineExamples.exampleNames(text, "First"))
        assertEquals(
            listOf("Second - Example 1", "Second - Example 2"),
            OutlineExamples.exampleNames(text, "Second"),
        )
    }

    @Test
    fun namedExamplesBlockPrefixesTheRowNames() {
        val text = """
            |Feature: F
            |  Scenario Outline: Validate
            |    When I check <x>
            |    Examples: Happy cases
            |      | x |
            |      | 1 |
            |      | 2 |
        """.trimMargin()
        assertEquals(
            listOf("Validate - Happy cases - Example 1", "Validate - Happy cases - Example 2"),
            OutlineExamples.exampleNames(text, "Validate"),
        )
    }

    @Test
    fun multipleExamplesBlocksRestartNumberingPerBlock() {
        val text = """
            |Feature: F
            |  Scenario Outline: Calc
            |    When I add <a>
            |    Examples:
            |      | a |
            |      | 1 |
            |      | 2 |
            |    Examples:
            |      | a |
            |      | 7 |
            |      | 8 |
            |      | 9 |
        """.trimMargin()
        assertEquals(
            listOf(
                "Calc - Example 1",
                "Calc - Example 2",
                "Calc - Example 1",
                "Calc - Example 2",
                "Calc - Example 3",
            ),
            OutlineExamples.exampleNames(text, "Calc"),
        )
    }

    @Test
    fun scenariosKeywordIsTreatedAsExamplesAlias() {
        val text = """
            |Feature: F
            |  Scenario Outline: Aliased
            |    When I check <a>
            |    Scenarios:
            |      | a |
            |      | 1 |
        """.trimMargin()
        assertEquals(listOf("Aliased - Example 1"), OutlineExamples.exampleNames(text, "Aliased"))
    }

    @Test
    fun emptyForUnknownOutline() {
        assertTrue(OutlineExamples.exampleNames("Feature: F\n  Scenario: S\n", "Nope").isEmpty())
    }
}
