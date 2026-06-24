package zio.bdd.intellij.lang

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

// ── Token types ──────────────────────────────────────────────────────────────

object ZioBddTokenTypes {
    @JvmField val KEYWORD        = IElementType("KEYWORD", ZioBddLanguage)        // Feature:, Scenario:, etc.
    @JvmField val STEP_KEYWORD   = IElementType("STEP_KEYWORD", ZioBddLanguage)   // Given, When, Then, And, But, *
    @JvmField val TAG            = IElementType("TAG", ZioBddLanguage)            // @smoke
    @JvmField val FLAGS_TAG      = IElementType("FLAGS_TAG", ZioBddLanguage)      // @flags( prefix
    @JvmField val FLAGS_VALUE    = IElementType("FLAGS_VALUE", ZioBddLanguage)    // k=v content inside @flags(...)
    @JvmField val COMMENT        = IElementType("COMMENT", ZioBddLanguage)        // # ...
    @JvmField val PLACEHOLDER    = IElementType("PLACEHOLDER", ZioBddLanguage)    // <param>
    @JvmField val TABLE_PIPE     = IElementType("TABLE_PIPE", ZioBddLanguage)     // |
    @JvmField val DOC_STRING     = IElementType("DOC_STRING", ZioBddLanguage)     // """ ... """ or ``` ... ```
    @JvmField val SCENARIO_NAME  = IElementType("SCENARIO_NAME", ZioBddLanguage) // text after Scenario:
    @JvmField val FEATURE_NAME   = IElementType("FEATURE_NAME", ZioBddLanguage)  // text after Feature:
    @JvmField val STEP_TEXT      = IElementType("STEP_TEXT", ZioBddLanguage)      // step body text
    @JvmField val TEXT           = IElementType("TEXT", ZioBddLanguage)           // anything else

    val ALL: TokenSet = TokenSet.create(
        KEYWORD, STEP_KEYWORD, TAG, FLAGS_TAG, FLAGS_VALUE, COMMENT,
        PLACEHOLDER, TABLE_PIPE, DOC_STRING, SCENARIO_NAME, FEATURE_NAME, STEP_TEXT, TEXT
    )
}
