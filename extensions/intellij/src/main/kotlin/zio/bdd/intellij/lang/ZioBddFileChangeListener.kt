package zio.bdd.intellij.lang

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.messages.MessageBusConnection

/**
 * Invalidates the per-project step cache whenever a `.scala` file is modified,
 * created, or deleted. Registered as a startup activity so the file-change
 * subscription is set up immediately when the project opens.
 */
class ZioBddFileChangeListener : ProjectActivity {
    override suspend fun execute(project: Project) {
        val cache      = ZioBddStepCache.getInstance(project)
        val connection = project.messageBus.connect()
        subscribeFileChanges(connection, cache)
    }

    private fun subscribeFileChanges(connection: MessageBusConnection, cache: ZioBddStepCache) {
        connection.subscribe(
            com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: MutableList<out VFileEvent>) {
                    if (events.any { it.file?.extension == "scala" }) {
                        cache.invalidate()
                    }
                }
            }
        )
    }
}
