package io.github.xororz.localdream.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ModelExportException(message: String) : IOException(message)

data class ModelExportResult(
    val exported: Int,
    val totalBytes: Long,
    val failedModels: List<String>,
)

data class ModelImportResult(
    val imported: Int,
    val skipped: Int,
    val failed: Int,
    val totalBytes: Long,
)

data class ModelExportEntry(
    val id: String,
    val name: String,
    val type: String,
    val isCustom: Boolean,
    val sizeBytes: Long,
    val fileCount: Int,
)

object ModelExport {
    private const val MANIFEST_NAME = "manifest.json"
    private const val FORMAT = "localdream-models-export"
    private const val VERSION = 1
    private const val MODELS_PREFIX = "models/"

    /**
     * Calculate the on-disk size and file count of a model directory.
     */
    fun modelStats(context: Context, modelId: String, customPath: String? = null): Pair<Long, Int> {
        val dir = File(Model.getModelsDir(context, customPath), modelId)
        if (!dir.exists() || !dir.isDirectory) return 0L to 0
        var totalSize = 0L
        var count = 0
        dir.listFiles()?.forEach { f ->
            if (f.isFile) {
                totalSize += f.length()
                count++
            }
        }
        return totalSize to count
    }
}
