package io.github.xororz.localdream

import android.app.Application
import android.util.Log
import io.github.xororz.localdream.data.HistoryMigration
import io.github.xororz.localdream.data.LegacyStoragePath
import io.github.xororz.localdream.data.RuntimeManager
import io.github.xororz.localdream.data.MigrationState
import io.github.xororz.localdream.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LocalDreamApplication : Application() {

    private companion object {
        const val TAG = "LocalDreamApplication"
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _migrationState = MutableStateFlow<MigrationState>(MigrationState.Idle)
    val migrationState: StateFlow<MigrationState> = _migrationState.asStateFlow()

    private var migrationJob: Job? = null

    // The retired custom-folder setting has to be applied before anything reads
    // the models dir, or the first model list is built from the wrong root.
    // ModelRepository waits on this job instead of racing it.
    private var storageAdoptionJob: Job? = null

    suspend fun awaitStorageAdoption() {
        storageAdoptionJob?.join()
    }

    override fun onCreate() {
        super.onCreate()
        storageAdoptionJob = appScope.launch {
            runCatching { LegacyStoragePath.adopt(this@LocalDreamApplication) }
                .onFailure { Log.w(TAG, "could not adopt the custom folder", it) }
        }
        appScope.launch { RuntimeManager.ensureDefaultRuntime(this@LocalDreamApplication) }
        startMigration()
    }

    private fun startMigration() {
        migrationJob?.cancel()
        migrationJob = appScope.launch {
            try {
                if (HistoryMigration.isDone(this@LocalDreamApplication)) {
                    _migrationState.value = MigrationState.NotNeeded
                    return@launch
                }
                HistoryMigration.migrate(
                    this@LocalDreamApplication,
                    AppDatabase.get(this@LocalDreamApplication),
                    _migrationState,
                )
            } catch (e: Throwable) {
                _migrationState.value = MigrationState.Failed(e)
            }
        }
    }

    fun retryMigration() {
        _migrationState.value = MigrationState.Idle
        startMigration()
    }

    fun skipMigration() {
        migrationJob?.cancel()
        appScope.launch {
            try {
                HistoryMigration.markDoneExternal(this@LocalDreamApplication)
            } catch (_: Throwable) {
                // ignore — UI will still proceed
            }
            _migrationState.value = MigrationState.Done
        }
    }
}
