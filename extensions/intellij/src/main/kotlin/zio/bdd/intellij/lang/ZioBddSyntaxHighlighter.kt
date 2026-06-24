package zio.bdd.intellij.lang

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Default
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey as key
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType

/**
 * Maps token types to editor colour attributes.
 * Users can customise all colours via Settings → Editor → Color Scheme → zio-bdd Gherkin.
 */
object ZioBddSyntaxHighlighter : SyntaxHighlighterBase() {

    // ── Colour attribute keys (customisable in colour scheme settings) ────────
    @JvmField val KEYWORD       = key("ZIO_BDD_KEYWORD",      Default.KEYWORD)
    @JvmField val STEP_KEYWORD  = key("ZIO_BDD_STEP_KEYWORD", Default.FUNCTION_DECLARATION)
    @JvmField val TAG           = key("ZIO_BDD_TAG",           Default.METADATA)
    @JvmField val FLAGS_TAG     = key("ZIO_BDD_FLAGS_TAG",     Default.METADATA)
    @JvmField val FLAGS_VALUE   = key("ZIO_BDD_FLAGS_VALUE",   Default.CONSTANT)
    @JvmField val COMMENT       = key("ZIO_BDD_COMMENT",       Default.LINE_COMMENT)
    @JvmField val PLACEHOLDER   = key("ZIO_BDD_PLACEHOLDER",   Default.TEMPLATE_LANGUAGE_COLOR)
    @JvmField val TABLE_PIPE    = key("ZIO_BDD_TABLE_PIPE",    Default.OPERATION_SIGN)
    @JvmField val DOC_STRING    = key("ZIO_BDD_DOC_STRING",    Default.STRING)
    @JvmField val STEP_TEXT     = key("ZIO_BDD_STEP_TEXT",     Default.STRING)
    @JvmField val TEXT          = key("ZIO_BDD_TEXT",          Default.STRING)

    private val KEYWORD_KEYS       = arrayOf(KEYWORD)
    private val STEP_KEYWORD_KEYS  = arrayOf(STEP_KEYWORD)
    private val TAG_KEYS           = arrayOf(TAG)
    private val FLAGS_TAG_KEYS     = arrayOf(FLAGS_TAG)
    private val COMMENT_KEYS       = arrayOf(COMMENT)
    private val PLACEHOLDER_KEYS   = arrayOf(PLACEHOLDER)
    private val TABLE_PIPE_KEYS    = arrayOf(TABLE_PIPE)
    private val DOC_STRING_KEYS    = arrayOf(DOC_STRING)
    private val STEP_TEXT_KEYS     = arrayOf(STEP_TEXT)
    private val TEXT_KEYS          = arrayOf(TEXT)
    private val EMPTY              = emptyArray<TextAttributesKey>()

    override fun getHighlightingLexer() = ZioBddLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> =
        when (tokenType) {
            ZioBddTokenTypes.KEYWORD       -> KEYWORD_KEYS
            ZioBddTokenTypes.STEP_KEYWORD  -> STEP_KEYWORD_KEYS
            ZioBddTokenTypes.TAG           -> TAG_KEYS
            ZioBddTokenTypes.FLAGS_TAG,
            ZioBddTokenTypes.FLAGS_VALUE   -> FLAGS_TAG_KEYS
            ZioBddTokenTypes.COMMENT       -> COMMENT_KEYS
            ZioBddTokenTypes.PLACEHOLDER   -> PLACEHOLDER_KEYS
            ZioBddTokenTypes.TABLE_PIPE    -> TABLE_PIPE_KEYS
            ZioBddTokenTypes.DOC_STRING    -> DOC_STRING_KEYS
            ZioBddTokenTypes.STEP_TEXT     -> STEP_TEXT_KEYS
            ZioBddTokenTypes.TEXT          -> TEXT_KEYS
            else                           -> EMPTY
        }
}
