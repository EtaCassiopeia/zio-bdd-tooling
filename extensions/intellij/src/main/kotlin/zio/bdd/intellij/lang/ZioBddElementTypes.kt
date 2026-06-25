package zio.bdd.intellij.lang

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType

object ZioBddElementTypes {
    @JvmField val FILE     = IFileElementType(ZioBddLanguage)
    @JvmField val FEATURE  = IElementType("FEATURE",  ZioBddLanguage)
    @JvmField val SCENARIO = IElementType("SCENARIO", ZioBddLanguage)
    @JvmField val STEP     = IElementType("STEP",     ZioBddLanguage)
    @JvmField val EXAMPLES = IElementType("EXAMPLES", ZioBddLanguage)
    @JvmField val MISC     = IElementType("MISC",     ZioBddLanguage)
}
