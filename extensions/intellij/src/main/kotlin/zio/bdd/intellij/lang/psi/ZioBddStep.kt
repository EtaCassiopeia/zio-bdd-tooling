package zio.bdd.intellij.lang.psi

import com.intellij.lang.ASTNode

class ZioBddStep(node: ASTNode) : ZioBddCompositeElement(node) {
    fun getKeyword(): String {
        val text = node.text.trimStart()
        val space = text.indexOf(' ')
        return if (space > 0) text.substring(0, space) else text.trimEnd()
    }

    fun getStepText(): String {
        val text = node.text.trimStart()
        val space = text.indexOf(' ')
        return if (space > 0) text.substring(space + 1).trimEnd() else ""
    }
}
