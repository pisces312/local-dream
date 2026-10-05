package io.github.xororz.localdream.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.InterruptedIOException
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object LogCapture {
    private const val TAG = "LogCapture"
    private const val MAX_BUFFER_BYTES = 2_000_000
    private const val TRIM_KEEP_BYTES = 1_000_000
    private const val TAIL_LINES = 240
    private const val MAX_RUN_FILES = 3
    private const val MEM_SAMPLE_MS = 1_000L

    // The backend is started as a plain child process, so its memory is invisible
    // to ActivityManager; match it by the executable name it shows in /proc.
    private const val BACKEND_MARKER = "libstable_diffusion_core.so"

    private val lock = Any()
    private val buffer = StringBuilder()
    private val tail = ArrayDeque<String>()

    private var captureProcess: java.lang.Process? = null
    private var captureScope: CoroutineScope? = null
    private var captureJob: Job? = null
    private var memJob: Job? = null
    private var writer: BufferedWriter? = null
    private var backendPid = 0
    private var samples = 0

    val lastCapturedLogs = mutableStateOf<String?>(null)
    val memStatus = mutableStateOf("")
    val runFilePath = mutableStateOf<String?>(null)

    fun start(context: Context) {
        synchronized(lock) {
            stopInternalLocked()
            buffer.clear()
            tail.clear()
            samples = 0
            backendPid = 0
            openRunFile(context)
            appendLocked("=== capture start pid=${Process.myPid()} ===")
            reportPreviousExits(context)
        }
        try {
            Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "logcat -c failed", e)
        }
        try {
            val pid = Process.myPid()
            val proc = Runtime.getRuntime().exec(
                arrayOf("logcat", "--pid=$pid", "-v", "threadtime"),
            )
            captureProcess = proc
            // A capture is diagnostics: nothing it does may take the host process down,
            // so the scope swallows whatever escapes a child coroutine.
            val scope = CoroutineScope(
                Dispatchers.IO + CoroutineExceptionHandler { _, e ->
                    Log.w(TAG, "log capture coroutine failed", e)
                },
            )
            captureScope = scope
            captureJob = scope.launch {
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        var line: String? = null
                        while (isActive && reader.readLine().also { line = it } != null) {
                            val current = line ?: continue
                            synchronized(lock) { appendLocked(current) }
                        }
                    }
                } catch (e: IOException) {
                    // stopInternalLocked() destroys the capture process, which closes the
                    // descriptor under this blocked read. That interrupt is how every
                    // capture normally ends; anything else is a real failure.
                    if (e !is InterruptedIOException) Log.w(TAG, "logcat read failed", e)
                }
            }
            memJob = scope.launch {
                while (isActive) {
                    val status = sampleMemory()
                    synchronized(lock) {
                        memStatus.value = status
                        appendLocked("MEM $status")
                    }
                    delay(MEM_SAMPLE_MS)
                }
            }
            Log.i(TAG, "log capture started for pid=$pid file=${runFilePath.value}")
        } catch (e: Exception) {
            Log.e(TAG, "failed to start logcat", e)
        }
    }

    fun stopAndPublish() {
        stop()
        val captured: String = synchronized(lock) { buffer.toString() }
        lastCapturedLogs.value = captured
        Log.i(TAG, "log capture stopped, ${captured.length} chars")
    }

    /** Stop the sink without publishing the modal — used when nobody asked for the dialog. */
    fun stop() {
        synchronized(lock) {
            appendLocked("=== capture stop ${memStatus.value} ===")
            runFilePath.value?.let { appendLocked("log file: $it") }
            stopInternalLocked()
        }
    }

    fun consume() {
        lastCapturedLogs.value = null
    }

    /** Snapshot for the live dialog; callers poll, so log lines don't recompose the screen. */
    fun tailSnapshot(): Pair<String, List<String>> =
        synchronized(lock) { memStatus.value to tail.toList() }

    private fun appendLocked(line: String) {
        buffer.append(line).append('\n')
        if (buffer.length > MAX_BUFFER_BYTES) {
            val keep = buffer.substring(buffer.length - TRIM_KEEP_BYTES)
            buffer.setLength(0)
            buffer.append(keep)
        }
        tail.addLast(line)
        while (tail.size > TAIL_LINES) {
            tail.removeFirst()
        }
        val out = writer ?: return
        try {
            out.write(line)
            out.newLine()
            out.flush()
        } catch (e: IOException) {
            Log.w(TAG, "log file write failed", e)
            runCatching { out.close() }
            writer = null
        }
    }

    private fun openRunFile(context: Context) {
        val dir = File(context.getExternalFilesDir(null), "debug")
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val file = File(dir, "run_$stamp.log")
        writer = runCatching {
            dir.mkdirs()
            BufferedWriter(FileWriter(file))
        }.getOrNull()
        runFilePath.value = writer?.let { file.absolutePath }
        val runs = dir.listFiles { f -> f.name.startsWith("run_") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
        runs?.drop(MAX_RUN_FILES)?.forEach { it.delete() }
        // A process killed by the system never reaches stopAndPublish(), so the
        // previous file has no stop marker. Point at it: that file is the log of
        // the run that died.
        runs?.drop(1)?.firstOrNull()?.let { previous ->
            if (!hasStopMarker(previous)) {
                appendLocked(
                    "PREVIOUS run ended without a stop marker (killed?) " +
                        "size=${previous.length() / 1024}KiB path=${previous.absolutePath}",
                )
            }
        }
    }

    private fun hasStopMarker(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length < 16) return false
            // stopAndPublish writes "=== capture stop … ===" then a "log file: …"
            // line, so the marker can sit ~150 bytes before EOF.
            raf.seek(length - minOf(512L, length))
            val tailBytes = ByteArray((length - raf.filePointer).toInt())
            raf.readFully(tailBytes)
            String(tailBytes, Charsets.UTF_8).contains("capture stop")
        }
    }.getOrDefault(false)

    /**
     * The whole point of the file sink: an app killed by the system leaves no log in
     * logcat for anyone but adb, but the reason is recorded by the framework and can
     * be read back on the next launch.
     */
    private fun reportPreviousExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val reasons = runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            am?.getHistoricalProcessExitReasons(context.packageName, 0, 5)
        }.getOrNull() ?: return
        for (info in reasons) {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(info.timestamp))
            // getStatus() only exists since API 34; it is the interesting field
            // here (9 = SIGKILL from lmkd, 6 = SIGABRT, 11 = SIGSEGV).
            val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                " status=${info.status}"
            } else {
                ""
            }
            appendLocked(
                "EXIT $time reason=${exitReasonName(info.reason)}(${info.reason})" +
                    "$status imp=${info.importance}" +
                    " rss=${info.rss / 1048576}MB pss=${info.pss / 1048576}MB" +
                    " desc=${info.description}",
            )
        }
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        0 -> "UNKNOWN"
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "REASON_$reason"
    }

    private fun sampleMemory(): String {
        val totalKb = readMeminfoKb("MemTotal")
        val availKb = readMeminfoKb("MemAvailable")
        val freeKb = readMeminfoKb("MemFree")
        val appKb = readRssKb(Process.myPid())
        if (backendPid > 0 && readRssKb(backendPid) < 0) backendPid = 0
        // Walking /proc is the only expensive part; when no backend is up (sitting on
        // the run screen idle) retry rarely instead of once per sample.
        if (backendPid <= 0 && samples % 5 == 0) backendPid = findBackendPid()
        samples++
        val backendKb = if (backendPid > 0) readRssKb(backendPid) else -1L
        return buildString {
            append("avail=").append(gb(availKb)).append('/').append(gb(totalKb))
            append(" free=").append(gb(freeKb))
            append(" app=").append(gb(appKb))
            append(" backend=")
            if (backendKb >= 0) append(gb(backendKb)).append("(pid $backendPid)") else append('-')
        }
    }

    private fun gb(kb: Long): String = if (kb < 0) "-" else String.format(Locale.US, "%.2fG", kb / 1048576.0)

    private fun readMeminfoKb(key: String): Long = runCatching {
        File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("$key:") }?.fieldKb() ?: -1L
        }
    }.getOrDefault(-1L)

    private fun readRssKb(pid: Int): Long = runCatching {
        File("/proc/$pid/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }?.fieldKb() ?: -1L
        }
    }.getOrDefault(-1L)

    /** `/proc` lines are "<tag>:\t NNN kB" with runs of blanks, so pick the numeric field. */
    private fun String.fieldKb(): Long? =
        split(" ", "\t").filter { it.isNotBlank() }.getOrNull(1)?.toLongOrNull()

    private fun findBackendPid(): Int = runCatching {
        File("/proc").listFiles { f -> f.isDirectory && f.name.all(Char::isDigit) }
            ?.firstOrNull { dir ->
                runCatching {
                    File(dir, "cmdline").readBytes().toString(Charsets.UTF_8)
                        .contains(BACKEND_MARKER)
                }.getOrDefault(false)
            }?.name?.toIntOrNull() ?: 0
    }.getOrDefault(0)

    private fun stopInternalLocked() {
        try {
            captureProcess?.destroy()
        } catch (_: Exception) {
        }
        captureProcess = null
        try {
            captureJob?.cancel()
        } catch (_: Exception) {
        }
        captureJob = null
        try {
            memJob?.cancel()
        } catch (_: Exception) {
        }
        memJob = null
        try {
            captureScope?.cancel()
        } catch (_: Exception) {
        }
        captureScope = null
        runCatching { writer?.close() }
        writer = null
    }
}
