package zio.bdd.intellij.lang.psi

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider
import zio.bdd.intellij.lang.ZioBddFileType
import zio.bdd.intellij.lang.ZioBddLanguage

class ZioBddFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, ZioBddLanguage) {
    override fun getFileType(): FileType = ZioBddFileType
    override fun toString(): String = "Gherkin Feature File"
}
