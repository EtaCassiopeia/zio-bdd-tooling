package zio.bdd.intellij.lang

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ZioBddStepCache(private val project: Project) {

    private val cache       = ConcurrentHashMap<String, List<KtStepDefinition>>()
    private val lastScan    = AtomicLong(0L)
    private val refreshing  = AtomicBoolean(false)

    companion object {
        private const val TTL_MS = 30_000L
        fun getInstance(project: Project): ZioBddStepCache = project.service()
    }

    fun getStepDefinitions(): List<KtStepDefinition> {
        if (System.currentTimeMillis() - lastScan.get() > TTL_MS) {
            scheduleRefresh()
        }
        return cache.values.flatten()
    }

    fun invalidate() {
        cache.clear()
        lastScan.set(0L)
    }

    private fun scheduleRefresh() {
        if (!refreshing.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try { refresh() } finally { refreshing.set(false) }
        }
    }

    private fun refresh() {
        val fresh = ConcurrentHashMap<String, List<KtStepDefinition>>()
        try {
            scalaFiles().forEach { vf ->
                try {
                    val content = String(vf.contentsToByteArray(), vf.charset)
                    val defs    = KtStepExtractor.extractFromSource(content, vf.path)
                    if (defs.isNotEmpty()) fresh[vf.path] = defs
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        cache.clear()
        cache.putAll(fresh)
        lastScan.set(System.currentTimeMillis())
    }

    private fun scalaFiles(): List<VirtualFile> {
        val acc = mutableListOf<VirtualFile>()
        ProjectRootManager.getInstance(project).contentSourceRoots.forEach { collect(it, acc) }
        return acc
    }

    private fun collect(dir: VirtualFile, acc: MutableList<VirtualFile>) {
        if (!dir.isValid || !dir.isDirectory) return
        for (child in dir.children) {
            if (child.isDirectory) collect(child, acc)
            else if (child.extension == "scala") acc += child
        }
    }
}
