package io.github.xororz.localdream.data

import android.content.Context
import android.util.Log
import io.github.xororz.localdream.BuildConfig
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the app can say about the native libraries it ships.
 *
 * Nothing on the Java side ever loads them -- the core is spawned as an
 * executable and the DiT engine is dlopen'ed by it -- so their provenance can
 * only be read back off disk. Each .so carries an ELF build-id, a content hash
 * the linker bakes in which survives the strip AGP applies while packaging;
 * tools/collect-build-info.py pairs that hash with the commit and toolchain of
 * the build that produced it and ships the pairing inside the APK. Matching the
 * two here is what lets the app answer "which commit is this .so from, and does
 * it still match what was built?" -- the fork ships a self-built engine that is
 * not tracked in git, so neither question can be answered any other way.
 */
object NativeBuildInfo {
    private const val TAG = "NativeBuildInfo"

    private const val MANIFEST_DIR = "build-info"
    private const val ENGINE_MANIFEST = "dit-engine"
    private const val CORE_MANIFEST = "core"

    private const val BUILD_ID_NOTE = 3
    private const val ANDROID_IDENT_NOTE = 1

    // The note segment is a few hundred bytes on every library seen so far; the
    // cap only exists so a corrupt program header cannot ask for a huge read.
    private const val MAX_NOTE_SEGMENT = 1 shl 20

    /** One .so as recorded by the build that produced it. */
    data class Record(
        val name: String,
        val buildId: String?,
        val ndk: String?,
    )

    /** The manifest written by one native build script. */
    data class Manifest(
        val id: String,
        val commitShort: String?,
        val dirty: Boolean,
        val builtAt: String?,
        val abiVersion: Int?,
        val toolchain: Map<String, String>,
        val records: List<Record>,
    ) {
        fun record(name: String): Record? = records.firstOrNull { it.name == name }
    }

    /** A shipped library, next to whatever the build recorded about it. */
    data class Library(
        val name: String,
        val path: String,
        val present: Boolean,
        val buildId: String?,
        val ndk: String?,
        val manifest: Manifest?,
    ) {
        val recordedBuildId: String? get() = manifest?.record(name)?.buildId

        /**
         * Whether the file on disk is the one the build recorded. Null when
         * there is nothing to compare -- no manifest, or an artifact the
         * linker gave no build-id (the DSP skels never get one).
         */
        val matchesRecord: Boolean?
            get() {
                val recorded = recordedBuildId ?: return null
                return recorded.equals(buildId, ignoreCase = true)
            }

        val commitShort: String? get() = manifest?.commitShort

        val toolchain: Map<String, String> get() = manifest?.toolchain.orEmpty()
    }

    /** Everything the Runtime card and the About section show. */
    data class Report(
        val versionName: String,
        val versionCode: Int,
        val appCommit: String,
        val appCommitSubject: String,
        val appCommitTime: String,
        val appDirty: Boolean,
        val engine: Library,
        val core: Library,
        val skels: List<Library>,
        val engineAbiVersion: Int?,
        val coreExpectedAbiVersion: Int?,
    ) {
        /**
         * Whether the engine and the core were built against the same
         * DIT_ENGINE_ABI_VERSION. The two are rebuilt by separate scripts, so
         * rebuilding only one silently produces a pair that fails at dlopen
         * time; this is the check that catches it before a generation does.
         */
        val abiMatches: Boolean?
            get() {
                val engineAbi = engineAbiVersion ?: return null
                val coreAbi = coreExpectedAbiVersion ?: return null
                return engineAbi == coreAbi
            }
    }

    /** Reads a manifest from assets, or null when the APK carries none. */
    private fun loadManifest(context: Context, id: String): Manifest? = runCatching {
        val text = context.assets
            .open("$MANIFEST_DIR/$id.json")
            .bufferedReader()
            .use { it.readText() }
        val json = JSONObject(text)

        val recordsJson = json.optJSONArray("artifacts") ?: JSONArray()
        val records = (0 until recordsJson.length()).mapNotNull { index ->
            val item = recordsJson.optJSONObject(index) ?: return@mapNotNull null
            Record(
                name = item.optString("name"),
                buildId = item.optString("buildId").takeIf { it.isNotEmpty() && it != "null" },
                ndk = item.optString("ndk").takeIf { it.isNotEmpty() && it != "null" },
            )
        }

        val toolchainJson = json.optJSONObject("toolchain")
        val toolchain = toolchainJson
            ?.keys()
            ?.asSequence()
            ?.associateWith { toolchainJson.optString(it) }
            .orEmpty()

        Manifest(
            id = json.optString("manifest", id),
            commitShort = json.optString("commitShort").takeIf { it.isNotEmpty() },
            dirty = json.optBoolean("dirty", false),
            builtAt = json.optString("builtAt").takeIf { it.isNotEmpty() },
            abiVersion = json.optInt("abiVersion", -1).takeIf { it >= 0 },
            toolchain = toolchain,
            records = records,
        )
    }.getOrElse { error ->
        // A missing manifest is a normal state (a clone that never rebuilt the
        // native libraries, or an APK assembled without them), so it is not
        // worth logging as a failure on every card open.
        Log.d(TAG, "no $id manifest: ${error.message}")
        null
    }

    /**
     * Builds the full picture: what is on disk, what the build recorded, and
     * whether the two agree. [runtimeDir] is the directory currently selected
     * in the Runtime picker, where the FastRPC skels live.
     */
    fun report(context: Context, runtimeDir: File): Report {
        val engineManifest = loadManifest(context, ENGINE_MANIFEST)
        val coreManifest = loadManifest(context, CORE_MANIFEST)

        val engineFile = File(DitEngine.dir(context), DitEngine.ENGINE_LIB)
        val coreFile = File(context.applicationInfo.nativeLibraryDir, CORE_LIB)

        return Report(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            appCommit = BuildConfig.GIT_COMMIT,
            appCommitSubject = BuildConfig.GIT_COMMIT_SUBJECT,
            appCommitTime = BuildConfig.GIT_COMMIT_TIME,
            appDirty = BuildConfig.GIT_DIRTY,
            engine = library(engineFile, engineManifest),
            core = library(coreFile, coreManifest),
            skels = engineManifest?.records
                ?.filter { it.name.startsWith(SKEL_PREFIX) }
                ?.map { record -> library(File(runtimeDir, record.name), engineManifest) }
                .orEmpty(),
            engineAbiVersion = engineManifest?.abiVersion,
            coreExpectedAbiVersion = coreManifest?.abiVersion,
        )
    }

    private fun library(file: File, manifest: Manifest?): Library {
        val identity = readElfIdentity(file)
        return Library(
            name = file.name,
            path = file.parent ?: "",
            present = file.isFile,
            buildId = identity?.first,
            ndk = identity?.second,
            manifest = manifest,
        )
    }

    /**
     * The flattened text behind the copy button. Screenshots of a settings
     * screen lose detail; a pasted block does not.
     */
    fun describe(report: Report, runtimeDirName: String?): String = buildString {
        appendLine("LocalDream ${report.versionName} (${report.versionCode})")
        appendLine("APK commit ${report.appCommit}${if (report.appDirty) " (dirty)" else ""}")
        if (report.appCommitTime.isNotEmpty()) {
            appendLine("  committed ${report.appCommitTime}")
        }
        if (report.appCommitSubject.isNotEmpty()) {
            appendLine("  ${report.appCommitSubject}")
        }
        appendLine()
        appendLibrary("DiT engine", report.engine, report.engineAbiVersion, abiLabel = "ABI")
        appendLine()
        appendLibrary("Core", report.core, report.coreExpectedAbiVersion, abiLabel = "expects ABI")
        if (report.skels.isNotEmpty()) {
            appendLine()
            appendLine("HTP skels")
            report.skels.forEach { skel ->
                appendLine("  ${skel.name}  ${if (skel.present) "present" else "missing"}")
            }
            appendLine("  (linked without a build-id, so not fingerprintable)")
        }
        appendLine()
        appendLine("Runtime dir  ${runtimeDirName ?: RuntimeManager.DEFAULT_SUBDIR}")
        appendLine("ABI check    ${describeAbi(report)}")
    }.trimEnd()

    private fun StringBuilder.appendLibrary(
        title: String,
        library: Library,
        abiVersion: Int?,
        abiLabel: String,
    ) {
        appendLine("$title (${library.name})")
        if (!library.present) {
            appendLine("  missing")
            return
        }
        appendLine("  build-id ${library.buildId ?: "none"}")
        if (library.buildId != null && library.recordedBuildId != null) {
            appendLine("  matches recorded build-id: ${library.matchesRecord == true}")
        }
        appendLine("  commit   ${library.commitShort ?: "unrecorded"}")
        library.manifest?.let { manifest ->
            manifest.builtAt?.let { appendLine("  built    $it") }
            if (manifest.dirty) appendLine("  working tree was dirty at build time")
        }
        abiVersion?.let { appendLine("  $abiLabel      v$it") }
        library.ndk?.let { appendLine("  NDK      $it") }
        library.toolchain.forEach { (key, value) -> appendLine("  $key  $value") }
    }

    private fun describeAbi(report: Report): String = when (report.abiMatches) {
        true -> "engine v${report.engineAbiVersion} matches core v${report.coreExpectedAbiVersion}"
        false -> "MISMATCH: engine v${report.engineAbiVersion} vs core v${report.coreExpectedAbiVersion}"
        null -> "unknown (a manifest is missing)"
    }

    /** build-id and NDK revision, or null when [file] is not a readable ELF. */
    private fun readElfIdentity(file: File): Pair<String?, String?>? {
        if (!file.isFile) return null
        return runCatching { readElfIdentityOrThrow(file) }.getOrNull()
    }

    private fun readElfIdentityOrThrow(file: File): Pair<String?, String?>? {
        RandomAccessFile(file, "r").use { raf ->
            val ident = ByteArray(ELF_IDENT_SIZE)
            raf.readFully(ident)
            if (ident[0] != ELF_MAGIC[0] ||
                ident[1] != ELF_MAGIC[1] ||
                ident[2] != ELF_MAGIC[2] ||
                ident[3] != ELF_MAGIC[3]
            ) {
                return null
            }
            val is64 = ident[ELF_CLASS_OFFSET] == ELF_CLASS_64.toByte()
            val order = if (ident[ELF_DATA_OFFSET] == ELF_DATA_LSB.toByte()) {
                ByteOrder.LITTLE_ENDIAN
            } else {
                ByteOrder.BIG_ENDIAN
            }
            val wordSize = if (is64) 8 else 4

            raf.seek(if (is64) ELF64_PHOFF_OFFSET else ELF32_PHOFF_OFFSET)
            val programHeaderOffset = raf.readWord(wordSize, order)
            raf.seek(if (is64) ELF64_PHHEADER_OFFSETS else ELF32_PHHEADER_OFFSETS)
            val header = raf.readBuffer(4, order)
            val entrySize = header.short.toInt() and 0xffff
            val entryCount = header.short.toInt() and 0xffff

            var buildId: String? = null
            var ndk: String? = null
            for (index in 0 until entryCount) {
                val base = programHeaderOffset + index.toLong() * entrySize
                raf.seek(base)
                if (raf.readBuffer(4, order).int != PT_NOTE) continue

                raf.seek(base + (if (is64) 8L else 4L))
                val offset = raf.readWord(wordSize, order)
                raf.seek(base + (if (is64) 32L else 16L))
                val size = raf.readWord(wordSize, order)
                if (size <= 0 || size > MAX_NOTE_SEGMENT) continue

                raf.seek(offset)
                parseNotes(raf.readBuffer(size.toInt(), order), order) { name, type, desc ->
                    if (name == "GNU" && type == BUILD_ID_NOTE) {
                        buildId = desc.toHex()
                    } else if (name == "Android" && type == ANDROID_IDENT_NOTE) {
                        ndk = parseAndroidIdent(desc)
                    }
                }
            }
            return buildId to ndk
        }
    }

    private fun RandomAccessFile.readWord(size: Int, order: ByteOrder): Long {
        val buffer = readBuffer(size, order)
        return if (size == 8) buffer.long else buffer.int.toLong() and 0xffffffffL
    }

    private fun RandomAccessFile.readBuffer(size: Int, order: ByteOrder): ByteBuffer {
        val bytes = ByteArray(size)
        readFully(bytes)
        return ByteBuffer.wrap(bytes).order(order)
    }

    /** Walks one PT_NOTE segment, handing each note to [onNote]. */
    private fun parseNotes(
        buffer: ByteBuffer,
        order: ByteOrder,
        onNote: (name: String, type: Int, desc: ByteArray) -> Unit,
    ) {
        buffer.order(order)
        while (buffer.remaining() >= 12) {
            val nameSize = buffer.int
            val descSize = buffer.int
            val type = buffer.int
            if (nameSize < 0 || descSize < 0) return
            if (buffer.remaining() < nameSize + descSize) return

            val name = ByteArray(nameSize)
            buffer.get(name)
            buffer.skipSafely(padding(nameSize))
            val desc = ByteArray(descSize)
            buffer.get(desc)
            buffer.skipSafely(padding(descSize))

            onNote(String(name, Charsets.US_ASCII).trimEnd('\u0000'), type, desc)
        }
    }

    /** Note fields are padded to 4 bytes; the padding can run past the end. */
    private fun ByteBuffer.skipSafely(count: Int) {
        position((position() + count).coerceAtMost(limit()))
    }

    private fun padding(size: Int): Int = (4 - size % 4) % 4

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /**
     * Renders the payload of .note.android.ident, which the linker fills with
     * the NDK that produced the library:
     *   uint32 sdk_version; char ndk_version[64]; char ndk_build_number[64];
     */
    private fun parseAndroidIdent(desc: ByteArray): String? {
        if (desc.size < ANDROID_IDENT_VERSION_END) return null
        val version = String(desc, 4, 64, Charsets.US_ASCII).trimEnd('\u0000').trim()
        if (version.isEmpty()) return null
        if (desc.size < ANDROID_IDENT_BUILD_END) return version
        val start = ANDROID_IDENT_VERSION_END
        val build = String(desc, start, ANDROID_IDENT_BUILD_END - start, Charsets.US_ASCII)
            .trimEnd('\u0000')
            .trim()
        return if (build.isEmpty()) version else "$version ($build)"
    }

    private const val CORE_LIB = "libstable_diffusion_core.so"
    private const val SKEL_PREFIX = "libggml-htp-"

    private const val ELF_IDENT_SIZE = 16
    private const val ELF_CLASS_OFFSET = 4
    private const val ELF_DATA_OFFSET = 5
    private const val ELF_CLASS_64 = 2
    private const val ELF_DATA_LSB = 1
    private const val PT_NOTE = 4
    private const val ELF32_PHOFF_OFFSET = 0x1cL
    private const val ELF64_PHOFF_OFFSET = 0x20L
    private const val ELF32_PHHEADER_OFFSETS = 0x2aL
    private const val ELF64_PHHEADER_OFFSETS = 0x36L
    private const val ANDROID_IDENT_VERSION_END = 68
    private const val ANDROID_IDENT_BUILD_END = 132

    // \x7f E L F
    private val ELF_MAGIC = byteArrayOf(0x7f.toByte(), 0x45, 0x4c, 0x46)
}
