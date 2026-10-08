package io.github.xororz.localdream.data

import android.content.Context
import android.util.Log
import io.github.xororz.localdream.utils.containsModelMarker
import io.github.xororz.localdream.utils.isUsableCustomRoot
import java.io.File

/**
 * One-time bridge from the fork's older custom-directory setting to
 * [ModelStorage.Location.CUSTOM].
 *
 * The old setting stored just a path in the app's DataStore, and pointed at the
 * **models directory itself** (`/storage/emulated/0/models/<id>/...`). The
 * layout ModelStorage uses — and the layout the backend expects, since it reads
 * `embeddings/` from two levels above `--model_dir` — keeps `models/` beside
 * `embeddings/` under a root. Adopting an old folder therefore means renaming
 * its contents down one level: same volume, so a rename rather than a copy, and
 * it finishes in milliseconds however many gigabytes are in there. Nothing has
 * to be moved by hand, and nothing leaves the device.
 *
 * Textual inversions never followed the old custom folder (they stayed in app
 * storage even when the models did not), and the backend only reads them from
 * beside the models — so adopting a folder would otherwise strand every
 * embedding the user had imported: invisible in Settings, and ignored at
 * generation time because the native side looks two levels above `--model_dir`.
 * [seedEmbeddings] moves them into the root once, with the same tree mover a
 * real location change uses.
 */
object LegacyStoragePath {
    private const val TAG = "LegacyStoragePath"

    private const val MODELS_DIR = "models"
    private const val EMBEDDINGS_DIR = "embeddings"

    // Not ModelStorage's own journal: that one names the single file an
    // interrupted move may have left on both sides, and this step must never
    // rewrite it.
    private const val JOURNAL = "legacy_storage_journal"

    // Names this app writes at the root itself; anything else belongs under models/.
    private val ROOT_OWN_NAMES = setOf(MODELS_DIR, EMBEDDINGS_DIR, "temp_downloads", ".nomedia")

    /**
     * Reads the retired preference once and, if it named a folder, points
     * CUSTOM at it without copying anything. Runs before the first model scan
     * (see LocalDreamApplication) so the list is never built from the wrong
     * root. Returns whether it changed anything.
     */
    suspend fun adopt(context: Context): Boolean {
        val app = context.applicationContext
        if (ModelStorage.customPath(app) != null) return false
        val legacy = runCatching { GenerationPreferences(app).getModelsStoragePath() }.getOrNull()
        if (legacy.isNullOrBlank()) return false
        val dir = File(legacy)
        // Adopt only a folder that really holds models. An old setting that was
        // saved while the models still lived in app storage must not hide them
        // behind an empty custom root.
        if (dir.listFiles().orEmpty().none { it.isDirectory && it.containsModelMarker() }) {
            Log.i(TAG, "legacy custom directory holds no models, staying in app storage: $legacy")
            return false
        }
        // Reject a folder we could actually read and would not pick ourselves.
        // Without All files access every shared folder looks unusable, and
        // switching to CUSTOM is still right — Settings then offers the grant
        // instead of pretending the models were never anywhere.
        if (ModelStorage.hasAllFilesAccess() && !isUsableCustomRoot(dir)) {
            Log.w(TAG, "legacy custom directory cannot hold models, staying in app storage: $legacy")
            return false
        }
        ModelStorage.setCustomRoot(app, legacy)
        ModelStorage.selectInPlace(app, ModelStorage.Location.CUSTOM)
        // Consumed for good: the folder lives in ModelStorage's own prefs from
        // here on, and the relocation below is detected from the filesystem,
        // so the retired key must not fire a second time.
        runCatching { GenerationPreferences(app).saveModelsStoragePath(null) }
            .onFailure { Log.w(TAG, "could not clear the retired storage path", it) }
        Log.i(TAG, "adopted legacy custom directory: $legacy")
        relocateIfNeeded(app)
        return true
    }

    /**
     * Renames a flat (pre-layout) custom folder down into `models/` so its
     * layout matches the other locations, and moves app-storage embeddings
     * beside it. Detected from the filesystem, so it is safe to call on every
     * resume and does nothing once the folder is already laid out. Returns
     * whether anything moved.
     */
    fun relocateIfNeeded(context: Context): Boolean {
        if (ModelStorage.location(context) != ModelStorage.Location.CUSTOM) return false
        // A move in flight owns both sides; it would race this and lose.
        if (ModelStorage.moveState.value is ModelStorage.MoveState.Moving) return false
        // Not the All files access check: below Android 11 shared folders are
        // reached through the legacy storage permission, and bailing there would
        // leave the folder flat behind an empty models/ with nothing to say about
        // it. isAccessLost is the API-aware form of "we may not read this yet".
        if (ModelStorage.isAccessLost(context)) return false
        val root = ModelStorage.rootFor(context, ModelStorage.Location.CUSTOM)
        var changed = false
        val moved = relocateFlatRoot(root)
        if (moved > 0) {
            Log.i(TAG, "relocated $moved entries of ${root.path} into $MODELS_DIR/")
            changed = true
        }
        // Its own journal file: an interrupted move keeps a record in
        // ModelStorage's, and this step must never overwrite it.
        val journal = ModelStorage.MoveJournal(File(context.noBackupFilesDir, JOURNAL))
        val source = File(context.filesDir, EMBEDDINGS_DIR)
        val seeded = runCatching { seedEmbeddings(source, File(root, EMBEDDINGS_DIR), journal) }
        if (seeded.getOrDefault(false)) {
            Log.i(TAG, "moved app embeddings into ${root.path}/$EMBEDDINGS_DIR")
            changed = true
        }
        // A failed seed only means app storage keeps a copy it can no longer
        // serve; the models themselves are unaffected, so say so and move on.
        seeded.exceptionOrNull()?.let { Log.w(TAG, "embeddings could not be moved", it) }
        journal.clear()
        return changed
    }

    /**
     * `root/<entry>` -> `root/models/<entry>` for everything that is not part
     * of the layout already. Returns how many entries moved, 0 when there was
     * nothing to do — kept free of android.util.Log so the JVM test can drive
     * it directly.
     */
    internal fun relocateFlatRoot(root: File): Int {
        if (!root.isDirectory || File(root, MODELS_DIR).isDirectory) return 0
        val entries = root.listFiles()?.filter { it.name !in ROOT_OWN_NAMES } ?: return 0
        // Only a folder that actually holds subfolders counts as the old
        // layout; an empty or unrelated one keeps its files where they are.
        if (entries.none { it.isDirectory }) return 0
        val models = File(root, MODELS_DIR)
        if (!models.mkdir()) return 0
        var moved = 0
        for (entry in entries) {
            if (entry.renameTo(File(models, entry.name))) moved++
        }
        if (moved == 0) models.delete()
        return moved
    }

    /**
     * `filesDir/embeddings` -> `root/embeddings`, the one part of the adoption
     * that really copies (app storage and shared storage are different mounts).
     * Uses the same tree mover as a location change, so a file reaches its
     * final name only after it is fully on disk and the app-side copy goes last.
     *
     * Bails when both sides hold a file of the same name: deciding which of two
     * `inv.safetensors` wins is not this function's call, and an ambiguous merge
     * would silently drop one of them. Anything else moves, so a root that
     * already has other embeddings still picks up the app-side ones, and a copy
     * interrupted half way finishes on the next call. Returns whether it moved
     * something — kept free of android.util.Log so the JVM test can drive it.
     */
    internal fun seedEmbeddings(source: File, destination: File, journal: ModelStorage.MoveJournal): Boolean {
        val names = source.listFiles()?.filter { it.isFile }?.map { it.name }?.toSet().orEmpty()
        if (names.isEmpty()) return false
        val alreadyThere = destination.listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet()
        if (names.any { it in alreadyThere }) return false
        ModelStorage.moveTree(source, destination, journal) { }
        return true
    }
}
