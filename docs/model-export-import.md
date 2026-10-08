# Model Export / Import

## Overview

Export downloaded model files (weights + config + markers) to a ZIP archive, and import them back. This enables cross-device migration and offline backup of multi-GB model data.

Distinguished from **History Backup** (DataBackupDialog) which handles generated images + DB metadata (KB~MB scale). Model Export handles inference weights (GB scale).

## Storage Permission Strategy

Follow StreamClip's approach: request `MANAGE_EXTERNAL_STORAGE` on Android 11+ so the C++ backend and file operations can access public directories without SAF indirection.

### AndroidManifest additions

```xml
<!-- All files access for model export/import to public dirs -->
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE"
    tools:ignore="ScopedStorage" />
```

### Runtime check (MainActivity)

On Android 11+, prompt user to grant `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` via system settings dialog (same pattern as StreamClip's `checkPermissions()`).

## ZIP Structure

```
manifest.json
models/{modelId}/
  ├── finished | npucustom | SDXL   (marker files — required for scanCustomModels)
  ├── config.json                   (if present)
  ├── *.mnn / *.bin / *.patch       (model weights)
  ├── upscaler.bin                  (for upscaler models)
  └── ...
```

### manifest.json

```json
{
  "format": "localdream-models-export",
  "version": 1,
  "exportedAt": 1718700000000,
  "models": [
    {
      "id": "anythingv5",
      "name": "Anything V5",
      "type": "sd15npu",
      "isCustom": false,
      "sizeBytes": 945000000,
      "fileCount": 12
    }
  ]
}
```

- `type`: matches `Model.backendType` — `"sd15npu"`, `"sd15cpu"`, `"sdxl"`, or `"upscaler"`
- Marker files included in the directory but not listed separately in manifest

## Data Layer: `ModelExport.kt`

### Export

```kotlin
suspend fun exportModels(
    context: Context,
    uri: Uri,                    // SAF destination (CreateDocument)
    modelIds: Set<String>,       // selected model IDs
    customPath: String? = null,  // models storage path
    onProgress: (doneFiles: Int, totalFiles: Int) -> Unit,
): ModelExportResult
```

- Scan each `models/{modelId}/` directory, count files
- Write `manifest.json` first, then stream each file into the zip
- Compression: `NO_COMPRESSION` (model weights are already compressed; re-compressing wastes CPU)
- On cancellation: delete the half-written SAF document
- Return: `ModelExportResult(exported: Int, totalBytes: Long, failedModels: List<String>)`

### Import

```kotlin
suspend fun importModels(
    context: Context,
    uri: Uri,                    // SAF source (GetContent)
    customPath: String? = null,
    onProgress: (doneFiles: Int, totalFiles: Int) -> Unit,
): ModelImportResult
```

- **Pass 1**: Read `manifest.json`, list models to import
- Conflict handling: **overwrite** existing model directories (delete old → extract new). User explicitly chose to import; same-ID model should be replaced.
- `RESERVED_MODEL_IDS` are allowed — restoring an official model is valid.
- **Pass 2**: Stream files from zip to `models/{modelId}/`
- Marker files (`finished`/`npucustom`/`SDXL`) are extracted along with weights
- After import: caller must invoke `ModelRepository.refreshAllModels()` + `UpscalerRepository.refreshBaseUrl()`
- On cancellation: keep already-extracted models (partial but valid)
- Return: `ModelImportResult(imported: Int, skipped: Int, failed: Int, totalBytes: Long)`

### Upscaler handling

Upscaler models (`upscaler_anime`, `upscaler_realistic`) live in the same `models/` root directory with a single `upscaler.bin` file. They are treated identically to regular models in the zip.

## UI Layer: `ModelExportDialog.kt`

### Export flow

1. Display list of downloaded models (from `ModelRepository.models` + `UpscalerRepository.upscalers`)
2. Multi-select with checkboxes; show individual size + total selected size
3. Tap "Export" → SAF `CreateDocument("application/zip")` → progress bar → done summary

### Import flow

1. Tap "Import" → SAF `GetContent("application/zip")`
2. Parse manifest, display model list preview
3. Models already present marked with "⚠ Will overwrite" warning
4. Confirm → progress bar → done summary
5. Auto-refresh model list

### Entry point

`ModelListScreen` settings section, below "Model Storage Location":

```
📦 Model Export / Import
```

## Files to create/modify

| Action | File |
|--------|------|
| Create | `data/ModelExport.kt` |
| Create | `ui/screens/ModelExportDialog.kt` |
| Modify | `AndroidManifest.xml` — add `MANAGE_EXTERNAL_STORAGE` |
| Modify | `MainActivity.kt` — add all-files permission check |
| Modify | `ui/screens/ModelListScreen.kt` — add entry + dialog state |
| Modify | `res/values/strings.xml` — add string resources |
