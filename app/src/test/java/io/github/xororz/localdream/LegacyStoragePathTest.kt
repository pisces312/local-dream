package io.github.xororz.localdream

import io.github.xororz.localdream.data.LegacyStoragePath
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The rename that turns the fork's old flat custom folder into a
 * ModelStorage root. Everything here is same-volume metadata work, so the test
 * needs no Context and no All files access.
 */
class LegacyStoragePathTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(file: File, text: String) = file.apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    @Test
    fun relocatesModelFoldersOneLevelDown() {
        val root = tmp.newFolder("models_root")
        write(File(root, "my_anima/ANIMA"), "")
        write(File(root, "my_anima/dit.gguf"), "weights")
        write(File(root, "sd_xl_base_1.0/finished"), "")

        assertTrue(LegacyStoragePath.relocateFlatRoot(root) > 0)

        assertEquals(0, root.listFiles().orEmpty().count { it.isDirectory && it.name != "models" })
        assertTrue(File(root, "models/my_anima/dit.gguf").isFile)
        assertTrue(File(root, "models/sd_xl_base_1.0/finished").isFile)
        assertFalse(File(root, "my_anima").exists())
    }

    @Test
    fun leavesALaidOutRootAlone() {
        val root = tmp.newFolder("laid_out")
        write(File(root, "models/a/finished"), "")
        write(File(root, "embeddings/inv.safetensors"), "x")

        assertEquals(0, LegacyStoragePath.relocateFlatRoot(root))
        assertTrue(File(root, "models/a/finished").isFile)
        assertFalse(File(root, "models/models").exists())
    }

    @Test
    fun keepsAppManagedNamesAtTheRoot() {
        val root = tmp.newFolder("mixed")
        write(File(root, "my_model/finished"), "")
        write(File(root, "embeddings/inv.safetensors"), "x")
        write(File(root, "temp_downloads/leftover.tmp"), "x")
        write(File(root, ".nomedia"), "")

        assertTrue(LegacyStoragePath.relocateFlatRoot(root) > 0)

        assertTrue(File(root, "embeddings/inv.safetensors").isFile)
        assertTrue(File(root, "temp_downloads").isDirectory)
        assertTrue(File(root, ".nomedia").isFile)
        assertTrue(File(root, "models/my_model/finished").isFile)
    }

    @Test
    fun doesNothingForAnEmptyOrForeignRoot() {
        val empty = tmp.newFolder("empty")
        assertEquals(0, LegacyStoragePath.relocateFlatRoot(empty))
        assertFalse(File(empty, "models").exists())

        // Loose files only: nothing here is a model folder, so nothing moves.
        val foreign = tmp.newFolder("foreign")
        write(File(foreign, "notes.txt"), "keep me")
        assertEquals(0, LegacyStoragePath.relocateFlatRoot(foreign))
        assertFalse(File(foreign, "models").exists())
        assertTrue(File(foreign, "notes.txt").isFile)

        assertEquals(0, LegacyStoragePath.relocateFlatRoot(File(tmp.root, "missing")))
    }
}
