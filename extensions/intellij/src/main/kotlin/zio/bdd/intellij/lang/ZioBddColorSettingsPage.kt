package zio.bdd.intellij.lang

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.*

/**
 * Registers zio-bdd Gherkin colour entries in
 * Settings → Editor → Color Scheme → zio-bdd Gherkin
 */
class ZioBddColorSettingsPage : ColorSettingsPage {

    private val attrs = arrayOf(
        AttributesDescriptor("Structural keyword (Feature:, Scenario:, …)", ZioBddSyntaxHighlighter.KEYWORD),
        AttributesDescriptor("Feature / scenario title text", ZioBddSyntaxHighlighter.TITLE_TEXT),
        AttributesDescriptor("Step keyword (Given, When, Then, And, But)", ZioBddSyntaxHighlighter.STEP_KEYWORD),
        AttributesDescriptor("Step text (body after keyword)", ZioBddSyntaxHighlighter.STEP_TEXT),
        AttributesDescriptor("Placeholder (<param>)", ZioBddSyntaxHighlighter.PLACEHOLDER),
        AttributesDescriptor("Tag (@smoke, @regression)", ZioBddSyntaxHighlighter.TAG),
        AttributesDescriptor("Flags tag (@flags(k=v))", ZioBddSyntaxHighlighter.FLAGS_TAG),
        AttributesDescriptor("Comment (# …)", ZioBddSyntaxHighlighter.COMMENT),
        AttributesDescriptor("Table cell separator (|)", ZioBddSyntaxHighlighter.TABLE_PIPE),
        AttributesDescriptor("Doc string (\"\"\" … \"\"\")", ZioBddSyntaxHighlighter.DOC_STRING),
    )

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = attrs
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getDisplayName(): String = "zio-bdd Gherkin"
    override fun getHighlighter(): SyntaxHighlighter = ZioBddSyntaxHighlighter
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null
    override fun getIcon(): javax.swing.Icon? = null

    override fun getDemoText(): String = """
        # zio-bdd Gherkin feature file
        @smoke @regression
        @flags(rateLimiting=true)
        @flags(rateLimiting=false)
        Feature: Account lifecycle
          As a developer I want to test account behaviour

          Background:
            Given the system is running

          Scenario: Provision an account
            Given a valid provision body
            When the provision request is sent
            Then the response status is 200
            And the response body contains accountReferenceId

          Scenario Outline: Post a <type> transaction
            Given a provisioned account
            When a <type> post of <amount> is sent
            Then the balance changes by <amount>

          Examples:
            | type       | amount |
            | deposit    | 1000   |
            | withdrawal | 500    |

          Scenario: Request with doc string
            Given the request body is
              ""${'"'}
              {
                "accountId": "abc-123"
              }
              ""${'"'}
            Then the response is valid
    """.trimIndent()
}
