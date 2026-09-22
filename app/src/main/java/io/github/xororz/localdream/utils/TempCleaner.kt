package io.github.xororz.localdream.utils

import android.content.Context
import android.util.Log
import io.github.xororz.localdream.data.GenerationPreferences
import io.github.xororz.localdream.data.Model
import io.github.xororz.localdream.service.ModelDownloadService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Removes scratch / orphaned files the app can leave behind, without touching
 * anything it actively manages (models, history images, embeddings, tag dicts,
 * the QNN runtime libs, prompt/latent caches). The targeted set:
 *
 *  - `.tmp_downloads/`        partial model `.tmp` files from an interrupted or
 *                             process-killed download (can be several GB). Sits
 *                             inside the models dir, wherever that is.
 *  - tmp.txt / mask.txt / ultrafix.txt  base64 IPC buffers handed to the
 *                             generation service; safe to drop while idle.
 *  - .part files in history/  half-written images from a cancelled backup import.
 *  - models entries           entries under the models dir that the app
 *                             provably created and left unfinished: anything
 *                             containing .part download chunks. Everything
 *                             else — including entries this app version does
 *                             not recognise — is left alone: when models live
 *                             on a user-chosen custom path, "anything without
 *                             a model marker" can be the user's own files.
 *                             Built-in models, upscalers and finished custom
 *                             models are never candidates for deletion.
 *
 * The download scratch dir and the models sweep are skipped while a
 * download/extract is in flight so cleaning can't pull the rug out from under
 * an active transfer (a model dir being populated by a rename).
 */
object TempCleaner {
    private const val TAG = "TempCleaner"

    /** Total size in bytes of everything [clean] would remove right now. */
    suspend fun scan(context: Context): Long = withContext(Dispatchers.IO) {
        collectTargets(context).sumOf { sizeOf(it) }
    }

    /** Deletes the targets and returns the number of bytes actually freed. */
    suspend fun clean(context: Context): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        for (target in collectTargets(context)) {
            val size = sizeOf(target)
            val deleted = if (target.isDirectory) target.deleteRecursively() else target.delete()
            if (deleted) {
                freed += size
            } else {
                Log.w(TAG, "failed to delete ${target.absolutePath}")
            }
        }
        freed
    }

    private suspend fun collectTargets(context: Context): List<File> {
        val filesDir = context.filesDir
        val targets = mutableListOf<File>()

        // Download scratch dir: only when no transfer is using it.
        val downloadActive = ModelDownloadService.downloadState.value.let { state ->
            state is ModelDownloadService.DownloadState.Downloading ||
                state is ModelDownloadService.DownloadState.Extracting
        }
        if (!downloadActive) {
            // Downloads scratch next to the models dir so the final install is
            // a rename; the internal path is legacy from before that change.
            val modelsDir = Model.getModelsDir(
                context,
                GenerationPreferences(context).getModelsStoragePath(),
            )
            File(modelsDir, ModelDownloadService.TEMP_DIR_NAME)
                .takeIf { it.exists() }?.let { targets += it }
            File(filesDir, "temp_downloads").takeIf { it.exists() }?.let { targets += it }

            // App-generated leftovers under models/: a multi-file download
            // writes .part chunks straight into the model dir, so any entry
            // containing them is provably ours and unfinished. Detected
            // positively — no marker-based judgement, so unrecognized entries
            // in a custom models dir (user's own files) always survive.
            // Skipped during a download since a model dir may be mid-populate.
            modelsDir.takeIf { it.isDirectory }?.listFiles()?.forEach { entry ->
                if (isAppGeneratedPartial(entry)) targets += entry
            }
        }

        // Transient base64 IPC buffers.
        for (name in listOf("tmp.txt", "mask.txt", "ultrafix.txt")) {
            File(filesDir, name).takeIf { it.isFile }?.let { targets += it }
        }

        // Half-written backup-import images.
        File(filesDir, "history").takeIf { it.isDirectory }?.walkTopDown()
            ?.filter { it.isFile && it.name.endsWith(".part") }
            ?.forEach { targets += it }

        return targets
    }

    // A models/ entry the app itself created and can safely remove: any entry
    // (loose file or directory tree) containing .part download chunks.
    private fun isAppGeneratedPartial(entry: File): Boolean =
        entry.walkTopDown().any { it.isFile && it.name.endsWith(".part") }

    private fun sizeOf(file: File): Long = if (file.isDirectory) {
        file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } else {
        file.length()
    }
}
