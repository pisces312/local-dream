package io.github.xororz.localdream.ui.screens

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.GenerationPreferences
import io.github.xororz.localdream.data.ModelExport
import io.github.xororz.localdream.data.ModelExportEntry
import io.github.xororz.localdream.data.ModelExportResult
import io.github.xororz.localdream.data.ModelImportResult
import io.github.xororz.localdream.data.ModelRepository
import io.github.xororz.localdream.data.UpscalerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface ExportPhase {
    data object Idle : ExportPhase
    data class Selecting(
        val entries: List<ModelExportEntry>,
        val selected: Set<String>,
    ) : ExportPhase

    data class Running(
        val isImport: Boolean,
        val done: Int,
        val total: Int,
    ) : ExportPhase

    data class ExportDone(val result: ModelExportResult) : ExportPhase
    data class ImportDone(val result: ModelImportResult) : ExportPhase
    data class ImportPreview(val entries: List<ModelExportEntry>) : ExportPhase
    data class Error(val message: String) : ExportPhase
}

@Composable
fun ModelExportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { GenerationPreferences(context) }

    var phase by remember { mutableStateOf<ExportPhase>(ExportPhase.Idle) }
    var job by remember { mutableStateOf<Job?>(null) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    val msgInvalidFile = stringResource(R.string.model_export_invalid_file)
    val msgUnknownError = stringResource(R.string.unknown_error)

    fun failureMessage(e: Exception): String = when (e) {
        is io.github.xororz.localdream.data.ModelExportException -> msgInvalidFile
        else -> e.message ?: msgUnknownError
    }

    // Load downloaded models on first composition
    LaunchedEffect(Unit) {
        val customPath = prefs.getModelsStoragePath()
        val repo = ModelRepository.getInstance(context)
        repo.ensureLoaded()
        val upscalerRepo = UpscalerRepository.getInstance(context)
        upscalerRepo.ensureLoaded()

        val entries = mutableListOf<ModelExportEntry>()
        for (model in repo.models) {
            if (model.isDownloaded || model.isCustom) {
                val (size, count) = ModelExport.modelStats(context, model.id, customPath)
                if (count > 0) {
                    entries.add(ModelExportEntry(
                        id = model.id,
                        name = model.name,
                        type = model.backendType,
                        isCustom = model.isCustom,
                        sizeBytes = size,
                        fileCount = count,
                    ))
                }
            }
        }
        for (upscaler in upscalerRepo.upscalers) {
            if (upscaler.isDownloaded) {
                val (size, count) = ModelExport.modelStats(context, upscaler.id, customPath)
                if (count > 0) {
                    entries.add(ModelExportEntry(
                        id = upscaler.id,
                        name = upscaler.name,
                        type = "upscaler",
                        isCustom = false,
                        sizeBytes = size,
                        fileCount = count,
                    ))
                }
            }
        }

        phase = if (entries.isEmpty()) {
            ExportPhase.Idle
        } else {
            ExportPhase.Selecting(entries, entries.map { it.id }.toSet())
        }
    }

    // Export: SAF CreateDocument
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val currentPhase = phase as? ExportPhase.Selecting ?: return@rememberLauncherForActivityResult
        val selectedEntries = currentPhase.entries.filter { it.id in currentPhase.selected }
        if (selectedEntries.isEmpty()) return@rememberLauncherForActivityResult

        val customPath = runBlocking { prefs.getModelsStoragePath() }
        job = scope.launch {
            phase = ExportPhase.Running(isImport = false, done = 0, total = 0)
            try {
                val result = ModelExport.exportModels(
                    context, uri, selectedEntries, customPath,
                ) { done, total ->
                    phase = ExportPhase.Running(isImport = false, done = done, total = total)
                }
                phase = ExportPhase.ExportDone(result)
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                }
                phase = ExportPhase.Selecting(currentPhase.entries, currentPhase.selected)
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                }
                phase = ExportPhase.Error(failureMessage(e))
            }
        }
    }

    // Import: SAF GetContent → preview then confirm
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingImportUri = uri
        job = scope.launch {
            try {
                val entries = ModelExport.readManifest(context, uri)
                if (entries.isEmpty()) {
                    phase = ExportPhase.Error(context.getString(R.string.model_export_empty))
                } else {
                    phase = ExportPhase.ImportPreview(entries)
                }
            } catch (e: Exception) {
                phase = ExportPhase.Error(failureMessage(e))
            }
        }
    }

    val running = phase as? ExportPhase.Running

    AlertDialog(
        onDismissRequest = { if (running == null) onDismiss() },
        title = { Text(stringResource(R.string.model_export_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.model_export_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                when (val p = phase) {
                    is ExportPhase.Idle -> {
                        Text(
                            text = stringResource(R.string.model_export_no_models),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    is ExportPhase.Selecting -> {
                        val selectedSize = p.entries
                            .filter { it.id in p.selected }
                            .sumOf { it.sizeBytes }

                        Text(
                            text = stringResource(
                                R.string.model_export_selected,
                                p.selected.size,
                                p.entries.size,
                                formatBytes(selectedSize),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        p.entries.forEach { entry ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = entry.id in p.selected,
                                    onCheckedChange = { checked ->
                                        val newSelected = if (checked) {
                                            p.selected + entry.id
                                        } else {
                                            p.selected - entry.id
                                        }
                                        phase = p.copy(selected = newSelected)
                                    },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        entry.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        "${formatBytes(entry.sizeBytes)} · ${entry.type}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    is ExportPhase.Running -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (p.total > 0) {
                                LinearProgressIndicator(
                                    progress = { p.done.toFloat() / p.total },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            Text(
                                text = stringResource(
                                    if (p.isImport) R.string.model_export_importing
                                    else R.string.model_export_exporting,
                                    p.done,
                                    p.total,
                                ),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFeatureSettings = "tnum",
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    is ExportPhase.ExportDone -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(
                                    R.string.model_export_export_done,
                                    p.result.exported,
                                    formatBytes(p.result.totalBytes),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (p.result.failedModels.isNotEmpty()) {
                                Text(
                                    text = stringResource(
                                        R.string.model_export_failed_models,
                                        p.result.failedModels.size,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    is ExportPhase.ImportPreview -> {
                        Text(
                            text = stringResource(
                                R.string.model_export_import_preview,
                                p.entries.size,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        p.entries.forEach { entry ->
                            Column(modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)) {
                                Text(
                                    entry.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "${formatBytes(entry.sizeBytes)} · ${entry.type}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.model_export_overwrite_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    is ExportPhase.ImportDone -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(
                                    R.string.model_export_import_done,
                                    p.result.imported,
                                    formatBytes(p.result.totalBytes),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (p.result.failed > 0) {
                                Text(
                                    text = stringResource(
                                        R.string.model_export_import_failed,
                                        p.result.failed,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    is ExportPhase.Error -> {
                        Text(
                            text = stringResource(R.string.model_export_error, p.message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (val p = phase) {
                is ExportPhase.Selecting -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                                    .format(Date())
                                exportLauncher.launch("LocalDream_models_$name.zip")
                            },
                            enabled = p.selected.isNotEmpty() && running == null,
                        ) {
                            Text(stringResource(R.string.model_export_export))
                        }
                        OutlinedButton(
                            onClick = { importLauncher.launch("application/zip") },
                        ) {
                            Text(stringResource(R.string.model_export_import))
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }

                is ExportPhase.ImportPreview -> {
                    FilledTonalButton(
                        onClick = {
                            val uri = pendingImportUri ?: return@FilledTonalButton
                            job = scope.launch {
                                val customPath = prefs.getModelsStoragePath()
                                phase = ExportPhase.Running(isImport = true, done = 0, total = 0)
                                try {
                                    val result = ModelExport.importModels(
                                        context, uri, customPath,
                                    ) { done, total ->
                                        phase = ExportPhase.Running(isImport = true, done = done, total = total)
                                    }
                                    phase = ExportPhase.ImportDone(result)
                                    // Refresh model list
                                    ModelRepository.getInstance(context).refreshAllModels()
                                    UpscalerRepository.getInstance(context).refreshBaseUrl()
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    phase = ExportPhase.Idle
                                    throw e
                                } catch (e: Exception) {
                                    phase = ExportPhase.Error(failureMessage(e))
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.model_export_import_confirm))
                    }
                    TextButton(onClick = {
                        pendingImportUri = null
                        phase = ExportPhase.Idle
                    }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }

                is ExportPhase.Running -> {
                    TextButton(onClick = { job?.cancel() }) {
                        Text(stringResource(R.string.cancel))
                    }
                }

                is ExportPhase.ExportDone, is ExportPhase.ImportDone -> {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.close))
                    }
                }

                is ExportPhase.Error -> {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.close))
                    }
                }

                is ExportPhase.Idle -> {
                    OutlinedButton(
                        onClick = { importLauncher.launch("application/zip") },
                    ) {
                        Text(stringResource(R.string.model_export_import))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            }
        },
        dismissButton = null,
    )
}
