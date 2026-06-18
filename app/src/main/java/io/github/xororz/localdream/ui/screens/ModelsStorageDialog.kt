package io.github.xororz.localdream.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import io.github.xororz.localdream.data.Model
import io.github.xororz.localdream.data.ModelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Possible storage locations for model files. */
private sealed interface StorageOption {
    /** Default internal: {app}/files/models/ */
    data object Internal : StorageOption

    /** App-specific external: /Android/data/{pkg}/files/models/ */
    data object AppExternal : StorageOption

    /** User-chosen directory via SAF. */
    data class Custom(val uri: Uri, val displayPath: String) : StorageOption
}

private fun appExternalModelsDir(context: Context): File {
    return File(context.getExternalFilesDir(null), "models").apply {
        if (!exists()) mkdirs()
    }
}

@Composable
fun ModelsStorageDialog(
    onDismiss: () -> Unit,
    onPathChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { GenerationPreferences(context) }

    var currentPath by remember { mutableStateOf<String?>(null) }
    var selectedOption by remember { mutableStateOf<StorageOption>(StorageOption.Internal) }
    var customDisplayPath by remember { mutableStateOf("") }
    var isMigrating by remember { mutableStateOf(false) }
    var migrationStatus by remember { mutableStateOf<String?>(null) }

    // Load current preference
    LaunchedEffect(Unit) {
        currentPath = prefs.getModelsStoragePath()
        selectedOption = when {
            currentPath == null -> StorageOption.Internal
            currentPath == appExternalModelsDir(context).absolutePath -> StorageOption.AppExternal
            else -> {
                customDisplayPath = currentPath!!
                StorageOption.Custom(Uri.EMPTY, currentPath!!)
            }
        }
    }

    // SAF directory picker
    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            // Take persistable permission so the path survives app restarts
            val flags = context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            // We need a real filesystem path for the C++ backend ProcessBuilder.
            // SAF tree URIs are not direct filesystem paths, so we resolve it.
            val fsPath = resolveFsPathFromUri(context, uri)
            if (fsPath != null) {
                customDisplayPath = fsPath
                selectedOption = StorageOption.Custom(uri, fsPath)
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.models_storage_path_unresolvable),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    val internalDefault = File(context.filesDir, "models").absolutePath
    val appExternal = appExternalModelsDir(context).absolutePath

    AlertDialog(
        onDismissRequest = { if (!isMigrating) onDismiss() },
        title = { Text(stringResource(R.string.models_storage_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.models_storage_desc),
                    style = MaterialTheme.typography.bodySmall,
                )

                // Option: Internal (default)
                StorageRadioRow(
                    selected = selectedOption is StorageOption.Internal,
                    label = stringResource(R.string.models_storage_internal),
                    detail = internalDefault,
                    onClick = { selectedOption = StorageOption.Internal },
                )

                // Option: App-specific external
                StorageRadioRow(
                    selected = selectedOption is StorageOption.AppExternal,
                    label = stringResource(R.string.models_storage_app_external),
                    detail = appExternal,
                    onClick = { selectedOption = StorageOption.AppExternal },
                )

                // Option: Custom directory
                StorageRadioRow(
                    selected = selectedOption is StorageOption.Custom,
                    label = stringResource(R.string.models_storage_custom),
                    detail = if (customDisplayPath.isNotEmpty()) customDisplayPath else "",
                    onClick = { dirPicker.launch(null) },
                )

                if (selectedOption is StorageOption.Custom && customDisplayPath.isEmpty()) {
                    FilledTonalButton(
                        onClick = { dirPicker.launch(null) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.models_storage_pick_dir))
                    }
                }

                // Migration status
                if (migrationStatus != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        migrationStatus!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (isMigrating) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.models_storage_migrating),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (isMigrating) return@TextButton
                    scope.launch {
                        val newPath = when (selectedOption) {
                            is StorageOption.Internal -> null
                            is StorageOption.AppExternal -> appExternal
                            is StorageOption.Custom -> (selectedOption as StorageOption.Custom).displayPath
                        }

                        val oldPath = currentPath
                        if (newPath == oldPath) {
                            onDismiss()
                            return@launch
                        }

                        isMigrating = true
                        migrationStatus = null

                        try {
                            val oldDir = Model.getModelsDir(context, oldPath)
                            val newDir = Model.getModelsDir(context, newPath)

                            // Migrate existing model files if old dir has content
                            if (oldDir.exists() && oldDir.isDirectory) {
                                val files = oldDir.listFiles()
                                if (files != null && files.isNotEmpty()) {
                                    migrationStatus = context.getString(
                                        R.string.models_storage_migrating_progress,
                                    )
                                    withContext(Dispatchers.IO) {
                                        if (!newDir.exists()) newDir.mkdirs()
                                        for (f in files) {
                                            val dest = File(newDir, f.name)
                                            if (!f.renameTo(dest)) {
                                                // renameTo can fail across mount points; copy+delete
                                                f.copyRecursively(dest, overwrite = true)
                                                f.deleteRecursively()
                                            }
                                        }
                                    }
                                    // Clean up old empty dir
                                    withContext(Dispatchers.IO) {
                                        oldDir.deleteRecursively()
                                    }
                                }
                            }

                            prefs.saveModelsStoragePath(newPath)
                            currentPath = newPath

                            // Refresh model list so download states update
                            ModelRepository.getInstance(context).refreshAllModels()

                            migrationStatus = context.getString(
                                R.string.models_storage_migrate_done,
                            )
                            onPathChanged()
                        } catch (e: Exception) {
                            migrationStatus = context.getString(
                                R.string.models_storage_migrate_failed,
                                e.message ?: "unknown",
                            )
                        } finally {
                            isMigrating = false
                        }
                    }
                },
                enabled = !isMigrating,
            ) {
                Text(stringResource(R.string.models_storage_apply))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = { if (!isMigrating) onDismiss() }) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun StorageRadioRow(
    selected: Boolean,
    label: String,
    detail: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Best-effort resolution of a SAF tree URI to a filesystem path.
 * Returns null if the path cannot be determined (the caller should reject
 * the selection and show a toast).
 */
private fun resolveFsPathFromUri(context: Context, uri: Uri): String? {
    // Common content URIs from external storage
    val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
    // docId is like "primary:Android/media/..." or "home:..."
    val parts = docId.split(":")
    if (parts.size >= 2) {
        val volume = parts[0]
        val relative = parts[1]
        val basePath = when (volume) {
            "primary" -> Environment.getExternalStorageDirectory().absolutePath
            "home" -> Environment.getExternalStorageDirectory().absolutePath
            else -> "/storage/$volume"
        }
        val fsPath = File(basePath, relative)
        if (fsPath.exists() || fsPath.mkdirs()) {
            return fsPath.absolutePath
        }
    }
    // Fallback: try to resolve via canonical path
    return null
}

