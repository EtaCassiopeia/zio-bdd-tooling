package zio.bdd.intellij.schema

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType
import com.jetbrains.jsonSchema.impl.JsonSchemaVersion

/**
 * Filename recognition for Rift imposter files. Kept as a pure object (no
 * platform types) so the association rules are unit-testable without booting an
 * IDE. Mirrors the VSCode `jsonValidation.fileMatch` globs: files ending in
 * `.imposter.json` or `.rift.json`, plus any `.json` under an `imposters`
 * directory.
 */
object RiftImposterFileMatcher {

    fun isImposterFile(name: String): Boolean =
        name.endsWith(".imposter.json", ignoreCase = true) ||
            name.endsWith(".rift.json", ignoreCase = true)

    fun matches(name: String, parentDirName: String?): Boolean =
        isImposterFile(name) ||
            (name.endsWith(".json", ignoreCase = true) && parentDirName.equals("imposters", ignoreCase = true))
}

/**
 * Associates the bundled Rift imposter JSON schema with imposter files so the
 * IDE's JSON support offers validation and completion for Mountebank-compatible
 * imposter documents (including Rift's `_rift.*` extensions). The schema is
 * shipped as a plugin resource and kept byte-identical to the VSCode copy.
 */
class RiftImposterSchemaProviderFactory : JsonSchemaProviderFactory {

    override fun getProviders(project: Project): List<JsonSchemaFileProvider> {
        val schemaFile = JsonSchemaProviderFactory.getResourceFile(javaClass, SCHEMA_RESOURCE)
        if (schemaFile == null) {
            // A missing bundled resource is a packaging regression, not a normal
            // condition — surface it (matching BspStepLoader's convention) instead
            // of silently disabling imposter validation with no trace.
            LOG.warn("Rift imposter schema resource '$SCHEMA_RESOURCE' not found on the plugin classpath; imposter validation disabled.")
            return emptyList()
        }
        return listOf(RiftImposterSchemaFileProvider(schemaFile))
    }

    private companion object {
        const val SCHEMA_RESOURCE = "/schemas/rift-imposter.schema.json"
        val LOG = Logger.getInstance(RiftImposterSchemaProviderFactory::class.java)
    }
}

private class RiftImposterSchemaFileProvider(private val schemaFile: VirtualFile) : JsonSchemaFileProvider {

    override fun isAvailable(file: VirtualFile): Boolean =
        RiftImposterFileMatcher.matches(file.name, file.parent?.name)

    override fun getName(): String = "Rift imposter"

    override fun getSchemaFile(): VirtualFile = schemaFile

    override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema

    override fun getSchemaVersion(): JsonSchemaVersion = JsonSchemaVersion.SCHEMA_7
}
