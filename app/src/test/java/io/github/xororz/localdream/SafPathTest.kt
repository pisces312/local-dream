package io.github.xororz.localdream

import io.github.xororz.localdream.utils.CustomRootProblem
import io.github.xororz.localdream.utils.customRootProblem
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The usability check behind the custom-folder picker. Driven with the temp
 * folder as the fake volume root so no Android Environment is needed.
 */
class SafPathTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // Stands in for /storage/emulated/0.
    private val volumeRoot: String get() = tmp.root.absolutePath

    private fun write(file: File, text: String) = file.apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    @Test
    fun acceptsAnEmptyFolder() {
        assertNull(customRootProblem(tmp.newFolder("empty"), volumeRoot))
    }

    @Test
    fun acceptsAFolderWithTheAppLayout() {
        val dir = tmp.newFolder("laid_out")
        File(dir, "models").mkdirs()
        assertNull(customRootProblem(dir, volumeRoot))
    }

    @Test
    fun acceptsAFlatFolderHoldingModelsAndStrays() {
        val dir = tmp.newFolder("flat")
        write(File(dir, "my_anima/ANIMA"), "")
        write(File(dir, "my_anima/dit.gguf"), "weights")
        // Unknown entries beside the models are left alone, not a reason to
        // reject the whole folder.
        write(File(dir, "notes.txt"), "keep me")
        write(File(dir, "other_stuff/x.bin"), "x")

        assertNull(customRootProblem(dir, volumeRoot))
    }

    @Test
    fun rejectsAFolderWithOnlyForeignContent() {
        val dir = tmp.newFolder("foreign")
        write(File(dir, "notes.txt"), "keep me")
        write(File(dir, "photos/pic.jpg"), "x")

        assertEquals(CustomRootProblem.NO_MODELS, customRootProblem(dir, volumeRoot))
    }

    @Test
    fun rejectsTheVolumeRootAndSharedCollections() {
        assertEquals(CustomRootProblem.VOLUME_ROOT, customRootProblem(tmp.root, volumeRoot))

        val download = File(tmp.root, "Download").apply { mkdirs() }
        assertEquals(CustomRootProblem.PUBLIC_DIR, customRootProblem(download, volumeRoot))

        // A subfolder of a shared collection is fine.
        val sub = File(download, "LocalDream").apply { mkdirs() }
        assertNull(customRootProblem(sub, volumeRoot))
    }

    @Test
    fun rejectsAPlainFile() {
        val file = write(File(tmp.root, "afile"), "x")
        assertEquals(CustomRootProblem.NOT_WRITABLE, customRootProblem(file, volumeRoot))
    }

    @Test
    fun appManagedScratchDoesNotMakeAFolderUsed() {
        val dir = tmp.newFolder("scratch_only")
        write(File(dir, "temp_downloads/leftover.tmp"), "x")
        write(File(dir, ".tmp_downloads/old.tmp"), "x")
        write(File(dir, ".nomedia"), "")

        assertNull(customRootProblem(dir, volumeRoot))
    }
}
