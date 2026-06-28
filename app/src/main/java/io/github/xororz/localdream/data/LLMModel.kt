package io.github.xororz.localdream.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.xororz.localdream.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

@Immutable
data class LLMModel(
    val id: String,
    val name: String,
    val description: String,
    val modelDir: String,
    val isDownloaded: Boolean = false,
    val isCustom: Boolean = false,
) {
    companion object {
        fun getModelsDir(context: Context, customPath: String? = null): File {
            val base = if (!customPath.isNullOrEmpty()) {
                File(customPath)
            } else {
                File(context.filesDir, "models")
            }
            return File(base, "llm")
        }
    }
}

class LLMModelRepository private constructor(private val context: Context) {
    private val generationPreferences = GenerationPreferences(context)
    private val refreshMutex = Mutex()

    private var modelsStoragePath: String? = null

    private fun modelsDir(): File = LLMModel.getModelsDir(context, modelsStoragePath)

    var models by mutableStateOf<List<LLMModel>>(emptyList())
        private set

    var isLoaded by mutableStateOf(false)
        private set

    suspend fun ensureLoaded() {
        if (isLoaded) return
        refreshAllModels()
    }

    private suspend fun refreshAllModels() {
        refreshMutex.withLock {
            modelsStoragePath = generationPreferences.getModelsStoragePath()
            val scanned = withContext(Dispatchers.IO) { scanModels() }
            models = scanned
            isLoaded = true
        }
    }

    private fun scanModels(): List<LLMModel> {
        val dir = modelsDir()
        val result = mutableListOf<LLMModel>()

        if (dir.exists() && dir.isDirectory) {
            dir.listFiles()?.forEach { modelDir ->
                if (!modelDir.isDirectory) return@forEach

                // Valid LLM model must contain genie_config.json
                val genieConfig = File(modelDir, "genie_config.json")
                if (!genieConfig.exists()) return@forEach

                val modelId = modelDir.name
                val metadata = readMetadata(modelDir)

                result.add(
                    LLMModel(
                        id = modelId,
                        name = metadata?.first ?: modelId,
                        description = metadata?.second ?: context.getString(R.string.custom_model),
                        modelDir = modelDir.absolutePath,
                        isDownloaded = true,
                        isCustom = true,
                    )
                )
            }
        }

        return result.sortedBy { it.name.lowercase() }
    }

    private fun readMetadata(modelDir: File): Pair<String, String>? {
        return try {
            val metadataFile = File(modelDir, "metadata.json")
            if (!metadataFile.exists()) return null

            val content = metadataFile.readText()
            val json = org.json.JSONObject(content)
            val name = json.optString("model_name", modelDir.name)
            val desc = json.optString("model_description", "")
            Pair(name, desc)
        } catch (e: Exception) {
            Log.w("LLMModelRepository", "Failed to read metadata: ${e.message}")
            null
        }
    }

    companion object {
        @Volatile
        private var instance: LLMModelRepository? = null

        fun getInstance(context: Context): LLMModelRepository {
            return instance ?: synchronized(this) {
                instance ?: LLMModelRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
