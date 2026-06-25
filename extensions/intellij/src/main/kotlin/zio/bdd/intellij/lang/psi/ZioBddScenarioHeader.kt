package zio.bdd.intellij.lang.psi

import com.intellij.lang.ASTNode

class ZioBddScenarioHeader(node: ASTNode) : ZioBddCompositeElement(node) {
    fun getScenarioName(): String {
        val text = node.text.trim()
        val idx = text.indexOf(':')
        return if (idx >= 0) text.substring(idx + 1).trim() else text
    }

    fun isOutline(): Boolean {
        val text = node.text.trim()
        return text.startsWith("Scenario Outline:") || text.startsWith("Scenario Template:")
    }

    fun isBackground(): Boolean = node.text.trim().startsWith("Background:")
}
