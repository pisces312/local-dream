package io.github.xororz.localdream.data

import android.content.Context
import android.util.Log
import java.io.File

data class RuntimeDir(
    val name: String,
    val path: File,
    val isDefault: Boolean,
    val soFiles: List<String>,
)

object RuntimeManager {
    private const val TAG = "RuntimeManager"
    private const val DEFAULT_BASE_DIR = "runtime_libs"
    const val DEFAULT_SUBDIR = "default"

    /**
     * QNN SDK revision the shipped runtime libs are built against. Keep in sync
     * with the SDK the native build pulls in (QNN_SDK_ROOT in CMakeLists.txt).
     */
    const val RUNTIME_VERSION = "qnn_2_50_0_260828"
    private const val RUNTIME_VERSION_FILE = ".runtime_version"

    /** Returns the runtime base directory (always internal storage). */
    fun getRuntimeBaseDir(context: Context): File {
        return File(context.filesDir, DEFAULT_BASE_DIR).apply { mkdirs() }
    }

    /** Lists all runtime subdirectories under internal storage. */
    fun listAvailableRuntimes(context: Context): List<RuntimeDir> {
        val baseDir = getRuntimeBaseDir(context)
        val subdirs = baseDir.listFiles()
            ?.filter { it.isDirectory }
            ?: return emptyList()

        return subdirs
            .map { dir ->
                val soFiles = dir.listFiles()
                    ?.filter { it.name.endsWith(".so") }
                    ?.map { it.name }
                    ?.sorted()
                    ?: emptyList()
                RuntimeDir(
                    name = dir.name,
                    path = dir,
                    isDefault = dir.name == DEFAULT_SUBDIR,
                    soFiles = soFiles,
                )
            }
            .sortedWith(compareBy<RuntimeDir> { !it.isDefault }.thenBy { it.name })
    }

    /** Resolves a runtime subdirectory name to its File path. */
    fun getRuntimeDir(context: Context, dirName: String?): File {
        val baseDir = getRuntimeBaseDir(context)
        if (dirName != null) {
            val dir = File(baseDir, dirName)
            if (dir.isDirectory) return dir
        }
        return File(baseDir, DEFAULT_SUBDIR)
    }

    /**
     * Imports a folder as a runtime: copies .so files from [sourceDir] into
     * filesDir/runtime_libs/<sourceDir.name>/.
     * Returns false if a runtime with the same name already exists.
     */
    fun importRuntimeDir(context: Context, sourceDir: File): Boolean {
        if (!sourceDir.isDirectory) return false
        val baseDir = getRuntimeBaseDir(context)
        val destDir = File(baseDir, sourceDir.name)
        if (destDir.isDirectory) return false // already exists

        destDir.mkdirs()
        sourceDir.listFiles()?.filter { it.name.endsWith(".so") }?.forEach { src ->
            src.copyTo(File(destDir, src.name), overwrite = true)
        }
        return true
    }

    /**
     * Ensures the default runtime directory exists and contains .so files.
     * Creates the directory and copies assets if needed.
     * Safe to call multiple times (idempotent).
     */
    fun ensureDefaultRuntime(context: Context) {
        val baseDir = getRuntimeBaseDir(context)
        val defaultDir = File(baseDir, DEFAULT_SUBDIR).apply { mkdirs() }

        // Migrate .so files from flat layout (legacy)
        val flatSoFiles = baseDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".so") }
        if (!flatSoFiles.isNullOrEmpty()) {
            flatSoFiles.forEach { it.renameTo(File(defaultDir, it.name)) }
        }

        // A per-file copy only refreshes libs whose size changed, so an SDK bump
        // would otherwise leave same-size stale libs (and libs the new SDK
        // dropped) behind. Wipe the default runtime when it holds another
        // version's worth of libs.
        val stamp = File(defaultDir, RUNTIME_VERSION_FILE)
        if (runCatching { stamp.readText() }.getOrNull() != RUNTIME_VERSION &&
            defaultDir.listFiles()?.any { it.name.endsWith(".so") } == true
        ) {
            Log.i(TAG, "Default runtime holds another SDK version, wiping")
            defaultDir.deleteRecursively()
            defaultDir.mkdirs()
        }
        runCatching { File(defaultDir, RUNTIME_VERSION_FILE).writeText(RUNTIME_VERSION) }

        // Copy QNN assets if default dir has no .so files
        val hasSoFiles = defaultDir.listFiles()?.any { it.name.endsWith(".so") } == true
        if (!hasSoFiles) {
            try {
                context.assets.list("qnnlibs")?.forEach { fileName ->
                    context.assets.open("qnnlibs/$fileName").use { input ->
                        File(defaultDir, fileName).outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            } catch (_: Exception) { }
        }
    }
}
