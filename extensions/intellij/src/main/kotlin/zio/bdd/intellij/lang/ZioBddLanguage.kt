package zio.bdd.intellij.lang

import com.intellij.lang.Language

/**
 * The zio-bdd Gherkin language singleton.
 * Owns .feature files — no dependency on the Gherkin plugin.
 */
object ZioBddLanguage : Language("ZioBddGherkin")
