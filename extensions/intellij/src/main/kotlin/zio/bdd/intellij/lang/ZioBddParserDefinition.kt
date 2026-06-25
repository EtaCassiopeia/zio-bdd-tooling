package zio.bdd.intellij.lang

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import zio.bdd.intellij.lang.psi.*

class ZioBddParserDefinition : ParserDefinition {
    override fun createLexer(project: Project): Lexer = ZioBddLexer()
    override fun createParser(project: Project): PsiParser = ZioBddParser()
    override fun getFileNodeType(): IFileElementType = ZioBddElementTypes.FILE
    override fun getCommentTokens(): TokenSet = TokenSet.create(ZioBddTokenTypes.COMMENT)
    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY
    override fun createElement(node: ASTNode): PsiElement = when (node.elementType) {
        ZioBddElementTypes.FEATURE  -> ZioBddFeatureHeader(node)
        ZioBddElementTypes.SCENARIO -> ZioBddScenarioHeader(node)
        ZioBddElementTypes.STEP     -> ZioBddStep(node)
        else                        -> ZioBddCompositeElement(node)
    }
    override fun createFile(viewProvider: FileViewProvider): PsiFile = ZioBddFile(viewProvider)
}
