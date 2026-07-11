package zio.bdd.intellij.hints

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the `<codeInsight.declarativeInlayProvider>` registration in plugin.xml.
 *
 * A declarative inlay provider MUST declare a `group`: IntelliJ 2026.1's Inlay Hints settings
 * panel calls `InlayHintsProviderExtensionBean.requiredGroup()` on every registered provider and
 * throws an NPE — aborting construction of the entire panel (not just our row) — when it is absent.
 * 2024.3 tolerated a missing group; 2026.1 made it strict. Losing the attribute again would leave
 * the Inlay Hints page stuck "Loading" for all declarative-hint plugins on 2026.1+.
 */
class PluginXmlInlayRegistrationTest {

    private val pluginXml: String =
        javaClass.getResource("/META-INF/plugin.xml")?.readText()
            ?: error("plugin.xml not found on the test classpath")

    @Test
    fun declarativeInlayProviderDeclaresANonEmptyGroup() {
        val tag = Regex("""<codeInsight\.declarativeInlayProvider\b[^>]*>""").find(pluginXml)?.value
        assertNotNull("no <codeInsight.declarativeInlayProvider> registration found", tag)
        assertTrue(
            "declarativeInlayProvider must set group=\"…\" (required by 2026.1's InlaySettings panel): $tag",
            tag!!.contains(Regex("""\bgroup\s*=\s*"[^"]+"""")),
        )
    }
}
