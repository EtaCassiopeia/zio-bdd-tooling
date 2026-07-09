package zio.bdd.intellij.lang

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BspLoaderJarReuseTest {

    @Test
    fun reusesWhenExtractedSizeMatchesBundled() {
        assertTrue(BspStepLoader.shouldReuseExtractedJar(jarExists = true, jarSize = 27_000L, bundledSize = 27_000L))
    }

    @Test
    fun reExtractsWhenSizeDiffers() {
        // A plugin update ships a new LSP jar (different size) — the stale tmpdir
        // copy must be replaced, not reused (#43).
        assertFalse(BspStepLoader.shouldReuseExtractedJar(jarExists = true, jarSize = 27_000L, bundledSize = 28_500L))
    }

    @Test
    fun reExtractsWhenNotYetExtracted() {
        assertFalse(BspStepLoader.shouldReuseExtractedJar(jarExists = false, jarSize = 0L, bundledSize = 27_000L))
    }

    @Test
    fun reusesExistingWhenBundledSizeUnknown() {
        // If the bundled resource's size can't be determined (-1), don't churn by
        // re-extracting 27MB on every call — reuse whatever is already there.
        assertTrue(BspStepLoader.shouldReuseExtractedJar(jarExists = true, jarSize = 27_000L, bundledSize = -1L))
    }
}
