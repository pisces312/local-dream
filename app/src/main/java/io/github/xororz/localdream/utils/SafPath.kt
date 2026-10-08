package io.github.xororz.localdream.utils

import android.content.Context
import android.net.Uri
import android.os.Environment
import io.github.xororz.localdream.data.Model
import java.io.File

/**
 * Best-effort resolution of a SAF tree URI to a filesystem path.
 *
 * Fork-only: the runtime-libs import (and the custom model directory) need a
 * real path, because native code dlopen()s from it — a content:// URI is not
 * enough. Returns null when the path cannot be determined; callers must reject
 * the selection rather than fall back to a guess.
 */
internal fun resolveFsPathFromUri(context: Context, uri: Uri): String? {
    // Common content URIs from external storage
    val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
    // docId is like "primary:Android/media/..." or "home:..."
    val parts = docId.split(":")
    if (parts.size >= 2) {
        val volume = parts[0]
        val relative = parts[1]
        val basePath = when (volume) {
            "primary" -> Environment.getExternalStorageDirectory().absolutePath
            "home" -> Environment.getExternalStorageDirectory().absolutePath
            else -> "/storage/$volume"
        }
        // Deliberately no exists()/mkdirs() probe: without All files access a
        // shared folder answers false to both, which would read as "cannot
        // resolve" before the permission request that follows ever runs. The
        // path construction above is deterministic; whether the folder is
        // usable is checked after access is granted.
        return File(basePath, relative).absolutePath
    }
    return null
}

/**
 * Why [dir] cannot be the custom model storage root, or null when it can.
 * Models, embeddings and the download scratch get created and deleted inside
 * it, so it must be a writable folder the app owns — never the volume root or
 * one of Android's shared collections, whose contents belong to the user and
 * to other apps.
 *
 * Accepts a fresh (or empty) folder, one that already has the app layout
 * (`models/` beside `embeddings/`), and any folder holding at least one model
 * directory — that last case is what lets an existing custom directory keep
 * working without moving anything. Unknown entries beside the models are
 * simply left alone: TempCleaner only sweeps the models dir in app storage,
 * so nothing here is ever deleted.
 */
internal enum class CustomRootProblem { VOLUME_ROOT, PUBLIC_DIR, NOT_WRITABLE, NO_MODELS }

internal fun customRootProblem(dir: File): CustomRootProblem? =
    customRootProblem(dir, Environment.getExternalStorageDirectory().absolutePath)

// Split from the Android entry point so the JVM test can name its own
// volume root.
internal fun customRootProblem(dir: File, externalRoot: String): CustomRootProblem? {
    val root = externalRoot.trimEnd('/')
    val normalized = dir.absolutePath.trimEnd('/')
    if (normalized == root) return CustomRootProblem.VOLUME_ROOT
    // File.separator agnostic so the JVM test can drive this on Windows.
    val relative = normalized.removePrefix(root).trimStart('/', '\\')
    if (relative in PUBLIC_TOP_DIRS) return CustomRootProblem.PUBLIC_DIR
    if (!dir.isDirectory && !dir.mkdirs()) return CustomRootProblem.NOT_WRITABLE
    if (!dir.canWrite()) return CustomRootProblem.NOT_WRITABLE

    // Scratch the app leaves at the root does not make a folder "used".
    val significant = dir.listFiles().orEmpty().filter { it.name !in APP_MANAGED_NAMES }
    if (significant.isEmpty()) return null
    if (File(dir, "models").isDirectory) return null
    if (significant.any { it.isDirectory && it.containsModelMarker() }) return null
    return CustomRootProblem.NO_MODELS
}

internal fun isUsableCustomRoot(dir: File): Boolean = customRootProblem(dir) == null

/** A folder the app manages: it carries at least one model marker file. */
internal fun File.containsModelMarker(): Boolean =
    File(this, Model.COMPLETE_MARKER).exists() ||
        Model.CUSTOM_MODEL_MARKERS.any { marker -> File(this, marker).exists() }

// The shared collections on the primary volume; deleting inside these is not
// the app's business.
private val PUBLIC_TOP_DIRS = setOf(
    "Download", "Downloads", "DCIM", "Pictures", "Movies", "Music",
    "Alarms", "Notifications", "Ringtones", "Podcasts",
)

// Files the app itself leaves at the root. `.tmp_downloads` is the scratch
// dir the fork's older versions wrote beside the models.
private val APP_MANAGED_NAMES = setOf("embeddings", "temp_downloads", ".tmp_downloads", ".nomedia")
