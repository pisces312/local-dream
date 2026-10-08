package io.github.xororz.localdream.utils

import android.content.Context
import android.net.Uri
import android.os.Environment
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
        val fsPath = File(basePath, relative)
        if (fsPath.exists() || fsPath.mkdirs()) {
            return fsPath.absolutePath
        }
    }
    // Fallback: try to resolve via canonical path
    return null
}
