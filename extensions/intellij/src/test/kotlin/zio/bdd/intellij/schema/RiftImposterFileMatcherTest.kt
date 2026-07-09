package zio.bdd.intellij.schema

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiftImposterFileMatcherTest {

    @Test
    fun recognizesImposterSuffixes() {
        assertTrue(RiftImposterFileMatcher.isImposterFile("cart.imposter.json"))
        assertTrue(RiftImposterFileMatcher.isImposterFile("cdn-config.rift.json"))
        assertTrue(RiftImposterFileMatcher.isImposterFile("CART.IMPOSTER.JSON"))
    }

    @Test
    fun ignoresPlainJson() {
        assertFalse(RiftImposterFileMatcher.isImposterFile("package.json"))
        assertFalse(RiftImposterFileMatcher.isImposterFile("config.json"))
        assertFalse(RiftImposterFileMatcher.isImposterFile("notes.txt"))
    }

    @Test
    fun matchesJsonUnderImpostersDir() {
        assertTrue(RiftImposterFileMatcher.matches("cart.json", "imposters"))
        assertTrue(RiftImposterFileMatcher.matches("cart.json", "Imposters"))
        assertFalse(RiftImposterFileMatcher.matches("cart.json", "fixtures"))
        assertFalse(RiftImposterFileMatcher.matches("cart.json", null))
        // A non-.json file under imposters/ must not match.
        assertFalse(RiftImposterFileMatcher.matches("notes.txt", "imposters"))
    }

    @Test
    fun bundledSchemaResourceIsOnClasspath() {
        // Guards the SCHEMA_RESOURCE path used by RiftImposterSchemaProviderFactory:
        // a typo or a packaging move would make getProviders() return an empty list.
        assertTrue(javaClass.getResource("/schemas/rift-imposter.schema.json") != null)
    }

    @Test
    fun matchesSuffixRegardlessOfDir() {
        assertTrue(RiftImposterFileMatcher.matches("cart.imposter.json", "anywhere"))
        assertTrue(RiftImposterFileMatcher.matches("cart.rift.json", null))
    }
}
