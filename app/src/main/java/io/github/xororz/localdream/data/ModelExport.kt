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
     * Export selected models to a ZIP archive at [uri].
     * The ZIP uses NO_COMPRESSION since model weights are already compressed.
     */
    suspend fun exportModels(
        context: Context,
        uri: Uri,
        modelEntries: List<ModelExportEntry>,
        customPath: String? = null,
        onProgress: (doneFiles: Int, totalFiles: Int) -> Unit,
    ): ModelExportResult = withContext(Dispatchers.IO) {
        val modelsDir = Model.getModelsDir(context, customPath)

        // Count total files across all selected models
        val modelDirs = mutableMapOf<String, File>()
        var totalFiles = 0
        val failedModels = mutableListOf<String>()

        for (entry in modelEntries) {
            val dir = File(modelsDir, entry.id)
            if (!dir.exists() || !dir.isDirectory) {
                failedModels.add(entry.id)
                continue
            }
            val fileCount = dir.listFiles()?.count { it.isFile } ?: 0
            if (fileCount == 0) {
                failedModels.add(entry.id)
                continue
            }
            modelDirs[entry.id] = dir
            totalFiles += fileCount
        }

        if (modelDirs.isEmpty()) {
            return@withContext ModelExportResult(0, 0L, failedModels)
        }

        // +1 for manifest
        totalFiles += 1

        val output = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("Cannot open export destination")

        var doneFiles = 0
        var totalBytes = 0L

        try {
            ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION)

                // Write manifest
                zip.putNextEntry(ZipEntry(MANIFEST_NAME))
                val manifestBytes = buildManifest(modelEntries.filter { it.id in modelDirs.keys })
                zip.write(manifestBytes)
                zip.closeEntry()
                doneFiles++
                onProgress(doneFiles, totalFiles)

                // Write model directories
                for ((id, dir) in modelDirs) {
                    dir.listFiles()?.sortedBy { it.name }?.forEach { file ->
                        if (!file.isFile) return@forEach
                        ensureActive()
                        val entryName = "$MODELS_PREFIX$id/${file.name}"
                        zip.putNextEntry(ZipEntry(entryName))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        totalBytes += file.length()
                        doneFiles++
                        onProgress(doneFiles, totalFiles)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Delete half-written export
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            throw e
        }

        ModelExportResult(modelDirs.size, totalBytes, failedModels)
    }

    /**
     * Read the manifest from a model export ZIP without extracting.
     * Returns the list of models in the archive.
     */
    suspend fun readManifest(
        context: Context,
        uri: Uri,
    ): List<ModelExportEntry> = withContext(Dispatchers.IO) {
        val zip = openZip(context, uri)
        val manifestJson = zip.use { z ->
            generateSequence { z.nextEntry }
                .firstOrNull { it.name == MANIFEST_NAME }
                ?.let { JSONObject(z.readBytes().toString(Charsets.UTF_8)) }
        } ?: throw ModelExportException("manifest.json not found")

        if (manifestJson.optString("format") != FORMAT) {
            throw ModelExportException("Unrecognized export format")
        }
        if (manifestJson.optInt("version", Int.MAX_VALUE) > VERSION) {
            throw ModelExportException("Export was created by a newer app version")
        }

        val items = manifestJson.optJSONArray("models") ?: JSONArray()
        val entries = mutableListOf<ModelExportEntry>()
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            entries.add(ModelExportEntry(
                id = obj.optString("id"),
                name = obj.optString("name"),
                type = obj.optString("type"),
                isCustom = obj.optBoolean("isCustom", false),
                sizeBytes = obj.optLong("sizeBytes", 0),
                fileCount = obj.optInt("fileCount", 0),
            ))
        }
        entries
    }

    /**
     * Import models from a ZIP archive at [uri].
     * Existing model directories are overwritten (deleted then re-extracted).
     */
    suspend fun importModels(
        context: Context,
        uri: Uri,
        customPath: String? = null,
        onProgress: (doneFiles: Int, totalFiles: Int) -> Unit,
    ): ModelImportResult = withContext(Dispatchers.IO) {
        val modelsDir = Model.getModelsDir(context, customPath)

        // Pass 1: read manifest and count entries
        val entries = mutableListOf<ZipEntry>()
        var totalFiles = 0
        val manifestModels = mutableSetOf<String>()

        openZip(context, uri).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (entry.name == MANIFEST_NAME) return@forEach
                if (!entry.name.startsWith(MODELS_PREFIX)) return@forEach
                if (entry.isDirectory) return@forEach
                entries.add(entry)
                totalFiles++
                // Extract model id from "models/{modelId}/..."
                val path = entry.name.removePrefix(MODELS_PREFIX)
                val slash = path.indexOf('/')
                if (slash > 0) {
                    manifestModels.add(path.substring(0, slash))
                }
            }
        }

        if (entries.isEmpty()) {
            return@withContext ModelImportResult(0, 0, 0, 0L)
        }

        // Delete existing model directories that will be overwritten
        for (modelId in manifestModels) {
            val dir = File(modelsDir, modelId)
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }

        // Pass 2: extract files
        var doneFiles = 0
        var totalBytes = 0L
        var imported = 0
        var failed = 0
        val extractedModels = mutableSetOf<String>()
        val failedModels = mutableSetOf<String>()

        openZip(context, uri).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (entry.name == MANIFEST_NAME) return@forEach
                if (!entry.name.startsWith(MODELS_PREFIX)) return@forEach
                if (entry.isDirectory) return@forEach

                ensureActive()

                val relativePath = entry.name.removePrefix(MODELS_PREFIX)
                val slash = relativePath.indexOf('/')
                if (slash <= 0) {
                    failed++
                    doneFiles++
                    onProgress(doneFiles, totalFiles)
                    return@forEach
                }

                val modelId = relativePath.substring(0, slash)
                val fileName = relativePath.substring(slash + 1)

                val modelDir = File(modelsDir, modelId)
                if (!modelDir.exists()) modelDir.mkdirs()

                val destFile = File(modelDir, fileName)
                try {
                    // Use temp file + rename for crash safety
                    val tmpFile = File(modelDir, "$fileName.part")
                    tmpFile.outputStream().use { out -> zip.copyTo(out) }
                    if (!tmpFile.renameTo(destFile)) {
                        tmpFile.delete()
                        // Fallback: write directly
                        destFile.outputStream().use { out -> zip.copyTo(out) }
                    }
                    totalBytes += destFile.length()
                    extractedModels.add(modelId)
                } catch (e: Exception) {
                    failedModels.add(modelId)
                    failed++
                }

                doneFiles++
                onProgress(doneFiles, totalFiles)
            }
        }

        imported = extractedModels.size
        val skipped = manifestModels.size - extractedModels.size - failedModels.size

        ModelImportResult(imported, skipped, failed, totalBytes)
    }

    /**
     * Build a manifest JSON from export entries.
     */
    private fun buildManifest(entries: List<ModelExportEntry>): ByteArray {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().apply {
                put("id", entry.id)
                put("name", entry.name)
                put("type", entry.type)
                put("isCustom", entry.isCustom)
                put("sizeBytes", entry.sizeBytes)
                put("fileCount", entry.fileCount)
            })
        }
        return JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("models", array)
        }.toString().toByteArray(Charsets.UTF_8)
    }

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

    private fun openZip(context: Context, uri: Uri): ZipInputStream {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open import file")
        return ZipInputStream(input.buffered())
    }
}
