package io.github.xororz.localdream

import io.github.xororz.localdream.data.LegacyStoragePath
import io.github.xororz.localdream.data.ModelStorage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The two steps that turn the fork's old flat custom folder into a
 * ModelStorage root: the rename that lays models/ out, and the embeddings that
 * follow it. On one JVM filesystem both end up as renames, so the test needs no
 * Context and no All files access — the end state is what the app depends on.
 */
class LegacyStoragePathTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val journal by lazy { ModelStorage.MoveJournal(File(tmp.root, "journal")) }

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

    @Test
    fun relocatesIntoAModelsFolderThatWasOnlyCreated() {
        val root = tmp.newFolder("half_adopted")
        // Asking for the path mkdirs models/, so a root adopted without access
        // ends up with an empty models/ beside a folder that is still flat.
        // That must not read as "already laid out" forever.
        File(root, "models").mkdirs()
        write(File(root, "my_anima/ANIMA"), "")
        write(File(root, "my_anima/dit.gguf"), "weights")

        assertTrue(LegacyStoragePath.relocateFlatRoot(root) > 0)

        assertTrue(File(root, "models/my_anima/dit.gguf").isFile)
        assertFalse(File(root, "my_anima").exists())
        assertEquals(0, root.listFiles().orEmpty().count { it.isDirectory && it.name != "models" })

        // models/ now has entries, so the next call has nothing to do.
        assertEquals(0, LegacyStoragePath.relocateFlatRoot(root))
    }

    @Test
    fun doesNotReachIntoAModelsFolderThatAlreadyHoldsSomething() {
        val root = tmp.newFolder("mixed_layout")
        write(File(root, "models/a/finished"), "")
        write(File(root, "loose_dir/x.bin"), "")

        assertEquals(0, LegacyStoragePath.relocateFlatRoot(root))

        assertTrue(File(root, "loose_dir/x.bin").isFile)
        assertFalse(File(root, "models/models").exists())
    }

    @Test
    fun keepsAnEmptyModelsFolderItDidNotCreate() {
        val root = tmp.newFolder("no_model_folders")
        File(root, "models").mkdirs()
        write(File(root, "notes.txt"), "keep me")

        assertEquals(0, LegacyStoragePath.relocateFlatRoot(root))

        // Deleting models/ here would be removing a folder this call never made.
        assertTrue(File(root, "models").isDirectory)
        assertTrue(File(root, "notes.txt").isFile)
    }

    @Test
    fun movesAppEmbeddingsIntoTheAdoptedRoot() {
        val appStorage = tmp.newFolder("filesDir")
        val root = tmp.newFolder("sdcard_models")
        write(File(appStorage, "embeddings/inv.safetensors"), "bytes")
        write(File(appStorage, "embeddings/other.safetensors"), "bytes2")

        assertTrue(LegacyStoragePath.seedEmbeddings(
            File(appStorage, "embeddings"), File(root, "embeddings"), journal))

        assertTrue(File(root, "embeddings/inv.safetensors").isFile)
        assertTrue(File(root, "embeddings/other.safetensors").isFile)
        assertFalse(File(appStorage, "embeddings").exists())

        // Called again on the next resume: nothing left to move.
        assertFalse(LegacyStoragePath.seedEmbeddings(
            File(appStorage, "embeddings"), File(root, "embeddings"), journal))
    }

    @Test
    fun neverWritesIntoRootEmbeddingsThatAlreadyHaveFiles() {
        val appStorage = tmp.newFolder("filesDir2")
        val root = tmp.newFolder("sdcard_models2")
        write(File(appStorage, "embeddings/inv.safetensors"), "app copy")
        write(File(root, "embeddings/inv.safetensors"), "root copy")

        assertFalse(LegacyStoragePath.seedEmbeddings(
            File(appStorage, "embeddings"), File(root, "embeddings"), journal))

        assertEquals("app copy", File(appStorage, "embeddings/inv.safetensors").readText())
        assertEquals("root copy", File(root, "embeddings/inv.safetensors").readText())
    }

    @Test
    fun addsAppEmbeddingsThatTheRootDoesNotHaveYet() {
        // Two different embeddings is not a conflict: both belong in the folder
        // the backend reads, whichever side each of them came from.
        val appStorage = tmp.newFolder("filesDir5")
        val root = tmp.newFolder("sdcard_models5")
        write(File(appStorage, "embeddings/app_side.safetensors"), "bytes")
        write(File(root, "embeddings/root_side.safetensors"), "bytes")

        assertTrue(LegacyStoragePath.seedEmbeddings(
            File(appStorage, "embeddings"), File(root, "embeddings"), journal))

        assertTrue(File(root, "embeddings/app_side.safetensors").isFile)
        assertTrue(File(root, "embeddings/root_side.safetensors").isFile)
        assertFalse(File(appStorage, "embeddings").exists())
    }

    @Test
    fun finishesAnInterruptedSeedOnTheNextCall() {
        val appStorage = tmp.newFolder("filesDir6")
        val root = tmp.newFolder("sdcard_models6")
        write(File(appStorage, "embeddings/one.safetensors"), "a")
        write(File(root, "embeddings/two.safetensors"), "b")
        // A copy interrupted on the way over leaves its half file behind under
        // a suffix, not under its real name — so it is not a name clash, and the
        // remaining files still have somewhere to go.
        write(File(root, "embeddings/one.safetensors.moving"), "half")

        assertTrue(LegacyStoragePath.seedEmbeddings(
            File(appStorage, "embeddings"), File(root, "embeddings"), journal))

        assertTrue(File(root, "embeddings/one.safetensors").isFile)
        assertEquals("a", File(root, "embeddings/one.safetensors").readText())
        assertFalse(File(appStorage, "embeddings").exists())
    }

    @Test
    fun skipsAnEmptyOrMissingAppEmbeddingsDir() {
        val appStorage = tmp.newFolder("filesDir3")
        val root = tmp.newFolder("sdcard_models3")

        assertFalse(LegacyStoragePath.seedEmbeddings(
            File(appStorage, "embeddings"), File(root, "embeddings"), journal))
        assertFalse(File(root, "embeddings").exists())

        // An embeddings folder holding only empty dirs is nothing to adopt.
        File(appStorage, "embeddings/sub").mkdirs()
        assertFalse(LegacyStoragePath.seedEmbeddings(
            File(appStorage, "embeddings"), File(root, "embeddings"), journal))
        assertTrue(File(appStorage, "embeddings").isDirectory)
    }

    @Test
    fun fillsAnExistingButEmptyRootFolder() {
        val appStorage = tmp.newFolder("filesDir4")
        val root = tmp.newFolder("sdcard_models4")
        write(File(appStorage, "embeddings/inv.safetensors"), "bytes")
        File(root, "embeddings").mkdirs()

        assertTrue(LegacyStoragePath.seedEmbeddings(
            File(appStorage, "embeddings"), File(root, "embeddings"), journal))
        assertTrue(File(root, "embeddings/inv.safetensors").isFile)
        assertFalse(File(appStorage, "embeddings").exists())
    }
}
