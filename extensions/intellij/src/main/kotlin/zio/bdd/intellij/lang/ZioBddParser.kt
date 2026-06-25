package zio.bdd.intellij.lang

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.tree.IElementType

class ZioBddParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val rootMark = builder.mark()
        while (!builder.eof()) {
            parseElement(builder)
        }
        rootMark.done(root)
        return builder.treeBuilt
    }

    private fun parseElement(builder: PsiBuilder) {
        when (builder.tokenType) {
            ZioBddTokenTypes.KEYWORD     -> parseKeyword(builder)
            ZioBddTokenTypes.STEP_KEYWORD -> parseStep(builder)
            else -> {
                val mark = builder.mark()
                builder.advanceLexer()
                mark.done(ZioBddElementTypes.MISC)
            }
        }
    }

    private fun parseKeyword(builder: PsiBuilder) {
        val text = builder.tokenText?.trimStart() ?: ""
        val type = when {
            text.startsWith("Feature:") || text.startsWith("Rule:") ->
                ZioBddElementTypes.FEATURE
            text.startsWith("Scenario") || text.startsWith("Background:") || text.startsWith("Example:") ->
                ZioBddElementTypes.SCENARIO
            text.startsWith("Examples:") || text.startsWith("Scenarios:") ->
                ZioBddElementTypes.EXAMPLES
            else ->
                ZioBddElementTypes.MISC
        }
        val mark = builder.mark()
        builder.advanceLexer()
        mark.done(type)
    }

    private fun parseStep(builder: PsiBuilder) {
        val mark = builder.mark()
        builder.advanceLexer()
        mark.done(ZioBddElementTypes.STEP)
    }
}
