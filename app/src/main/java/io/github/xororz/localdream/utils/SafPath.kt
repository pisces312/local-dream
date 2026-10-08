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
 * Whether [dir] can hold the model storage root: models, embeddings and the
 * download scratch get created and deleted inside it, so it must be a writable
 * folder the app owns — never the volume root or one of Android's shared
 * collections, whose contents belong to the user and to other apps.
 *
 * Accepts a fresh (or empty) folder, one that already has the app layout
 * (`models/` beside `embeddings/`), and a folder holding only model directories
 * in the pre-layout shape the fork used to write — that last case is what lets
 * an existing custom directory keep working without moving anything.
 */
internal fun isUsableCustomRoot(dir: File): Boolean {
    val root = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
    val normalized = dir.absolutePath.trimEnd('/')
    if (normalized == root) return false
    if (normalized.removePrefix("$root/") in PUBLIC_TOP_DIRS) return false
    if (!dir.isDirectory && !dir.mkdirs()) return false
    if (!dir.canWrite()) return false

    val entries = dir.listFiles().orEmpty()
    if (File(dir, "models").isDirectory) return true
    return entries.all { it.isDirectory && (it.containsModelMarker() || it.name in APP_MANAGED_NAMES) }
}

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

// Files the app itself leaves at the root.
private val APP_MANAGED_NAMES = setOf("embeddings", "temp_downloads", ".nomedia")
