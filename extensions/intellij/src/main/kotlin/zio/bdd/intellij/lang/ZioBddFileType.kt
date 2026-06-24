package zio.bdd.intellij.lang

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** File type that owns the .feature extension. */
object ZioBddFileType : LanguageFileType(ZioBddLanguage) {
    override fun getName(): String = "Gherkin Feature"
    override fun getDescription(): String = "Gherkin feature file (zio-bdd)"
    override fun getDefaultExtension(): String = "feature"
    override fun getIcon(): Icon? =
        IconLoader.findIcon("/icons/feature.svg", ZioBddFileType::class.java)
}
