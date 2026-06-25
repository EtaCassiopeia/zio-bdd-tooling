package zio.bdd.intellij.lang.psi

import com.intellij.lang.ASTNode

class ZioBddFeatureHeader(node: ASTNode) : ZioBddCompositeElement(node) {
    fun getFeatureName(): String {
        val text = node.text.trim()
        val idx = text.indexOf(':')
        return if (idx >= 0) text.substring(idx + 1).trim() else text
    }
}
