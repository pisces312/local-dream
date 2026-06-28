package io.github.xororz.localdream.service

import android.util.Log
import io.github.xororz.localdream.utils.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object LLMHttpClient {
    private const val TAG = "LLMHttpClient"
    private const val BASE_URL = "http://127.0.0.1:8082"

    suspend fun checkHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/health")
                .get()
                .build()
            Http.client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun loadModel(modelDir: String, htpConfig: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("model_dir", modelDir)
                    put("htp_config", htpConfig)
                }.toString().toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$BASE_URL/load")
                    .post(body)
                    .build()

                Http.client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Result.success(Unit)
                    } else {
                        val error = response.body?.string() ?: "Unknown error"
                        Result.failure(IOException("Load failed: $error"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadModel failed", e)
                Result.failure(e)
            }
        }

    suspend fun chat(
        prompt: String,
        onToken: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("prompt", prompt)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$BASE_URL/chat")
                .post(body)
                .build()

            suspendCancellableCoroutine { continuation ->
                val call = Http.client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }

                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(e)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (!response.isSuccessful) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IOException("Chat failed: ${response.code}")
                                )
                            }
                            return
                        }

                        try {
                            val reader = BufferedReader(
                                InputStreamReader(response.body!!.byteStream())
                            )
                            var fullResponse = ""
                            var eventType = ""
                            var eventData = ""

                            reader.use { r ->
                                var line: String?
                                while (r.readLine().also { line = it } != null) {
                                    val l = line ?: continue
                                    when {
                                        l.startsWith("event: ") -> {
                                            eventType = l.removePrefix("event: ").trim()
                                        }
                                        l.startsWith("data: ") -> {
                                            eventData = l.removePrefix("data: ")
                                        }
                                        l.isEmpty() -> {
                                            // End of SSE event
                                            if (eventData.isNotEmpty()) {
                                                try {
                                                    val json = JSONObject(eventData)
                                                    when (eventType) {
                                                        "token" -> {
                                                            val text = json.optString("text", "")
                                                            fullResponse += text
                                                            onToken(text)
                                                        }
                                                        "complete" -> {
                                                            fullResponse = json.optString("text", fullResponse)
                                                        }
                                                        "error" -> {
                                                            val msg = json.optString("message", "Unknown error")
                                                            if (continuation.isActive) {
                                                                continuation.resumeWithException(
                                                                    IOException(msg)
                                                                )
                                                            }
                                                            return
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    Log.w(TAG, "Failed to parse SSE data: $eventData")
                                                }
                                            }
                                            eventType = ""
                                            eventData = ""
                                        }
                                    }
                                }
                            }

                            if (continuation.isActive) {
                                continuation.resume(Result.success(fullResponse))
                            }
                        } catch (e: Exception) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(e)
                            }
                        }
                    }
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "chat failed", e)
            Result.failure(e)
        }
    }

    suspend fun reset(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/reset")
                .post("".toRequestBody("application/json".toMediaType()))
                .build()

            Http.client.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(Unit)
                else Result.failure(IOException("Reset failed: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun unload(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/unload")
                .post("".toRequestBody("application/json".toMediaType()))
                .build()

            Http.client.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(Unit)
                else Result.failure(IOException("Unload failed: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
