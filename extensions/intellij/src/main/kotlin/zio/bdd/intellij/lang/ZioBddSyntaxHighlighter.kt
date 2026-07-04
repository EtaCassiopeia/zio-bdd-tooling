package zio.bdd.intellij.lang

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Default
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey as key
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType

object ZioBddSyntaxHighlighter : SyntaxHighlighterBase() {

    // ── Colour attribute keys ─────────────────────────────────────────────
    //
    // Lexer-level keys (whole-line tokens):
    @JvmField val KEYWORD      = key("ZIO_BDD_KEYWORD",      Default.KEYWORD)
    @JvmField val STEP_KEYWORD = key("ZIO_BDD_STEP_KEYWORD", Default.KEYWORD)      // same base as KEYWORD
    @JvmField val TAG          = key("ZIO_BDD_TAG",           Default.METADATA)
    @JvmField val FLAGS_TAG    = key("ZIO_BDD_FLAGS_TAG",     Default.METADATA)
    @JvmField val FLAGS_VALUE  = key("ZIO_BDD_FLAGS_VALUE",   Default.CONSTANT)
    @JvmField val MOCK_TAG     = key("ZIO_BDD_MOCK_TAG",      Default.METADATA)
    @JvmField val MOCK_VALUE   = key("ZIO_BDD_MOCK_VALUE",    Default.CONSTANT)
    @JvmField val COMMENT      = key("ZIO_BDD_COMMENT",       Default.LINE_COMMENT)
    @JvmField val TABLE_PIPE   = key("ZIO_BDD_TABLE_PIPE",    Default.OPERATION_SIGN)
    @JvmField val DOC_STRING   = key("ZIO_BDD_DOC_STRING",    Default.STRING)

    // Sub-line keys applied by ZioBddSyntaxAnnotator on top of the lexer:
    @JvmField val TITLE_TEXT   = key("ZIO_BDD_TITLE_TEXT",    Default.FUNCTION_DECLARATION) // feature/scenario name text (gold)
    @JvmField val STEP_TEXT    = key("ZIO_BDD_STEP_TEXT",     Default.IDENTIFIER)           // step body (default foreground)
    @JvmField val PLACEHOLDER  = key("ZIO_BDD_PLACEHOLDER",   Default.TEMPLATE_LANGUAGE_COLOR)
    @JvmField val TEXT         = key("ZIO_BDD_TEXT",          Default.IDENTIFIER)

    private val KEYWORD_KEYS      = arrayOf(KEYWORD)
    private val STEP_KEYWORD_KEYS = arrayOf(STEP_KEYWORD)
    private val TAG_KEYS          = arrayOf(TAG)
    private val FLAGS_TAG_KEYS    = arrayOf(FLAGS_TAG)
    private val MOCK_TAG_KEYS     = arrayOf(MOCK_TAG)
    private val COMMENT_KEYS      = arrayOf(COMMENT)
    private val TABLE_PIPE_KEYS   = arrayOf(TABLE_PIPE)
    private val DOC_STRING_KEYS   = arrayOf(DOC_STRING)
    private val TEXT_KEYS         = arrayOf(TEXT)
    private val EMPTY             = emptyArray<TextAttributesKey>()

    override fun getHighlightingLexer() = ZioBddLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> =
        when (tokenType) {
            ZioBddTokenTypes.KEYWORD       -> KEYWORD_KEYS
            ZioBddTokenTypes.STEP_KEYWORD  -> STEP_KEYWORD_KEYS
            ZioBddTokenTypes.TAG           -> TAG_KEYS
            ZioBddTokenTypes.FLAGS_TAG,
            ZioBddTokenTypes.FLAGS_VALUE   -> FLAGS_TAG_KEYS
            ZioBddTokenTypes.MOCK_TAG,
            ZioBddTokenTypes.MOCK_VALUE    -> MOCK_TAG_KEYS
            ZioBddTokenTypes.COMMENT       -> COMMENT_KEYS
            ZioBddTokenTypes.PLACEHOLDER   -> arrayOf(PLACEHOLDER)
            ZioBddTokenTypes.TABLE_PIPE    -> TABLE_PIPE_KEYS
            ZioBddTokenTypes.DOC_STRING    -> DOC_STRING_KEYS
            ZioBddTokenTypes.STEP_TEXT     -> arrayOf(STEP_TEXT)
            ZioBddTokenTypes.TEXT          -> TEXT_KEYS
            else                           -> EMPTY
        }
}
