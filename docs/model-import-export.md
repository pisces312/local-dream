# 模型导入/导出功能方案

## 背景

Local Dream 的模型下载后存储在应用内部存储 `context.filesDir/models/{modelId}/`，用户无法直接访问这些文件。需求是让用户能够将已下载的模型备份/迁移到其他设备，或从外部导入模型。

---

## 方案 A：ZIP 导出/导入

### 设计

复用 `HistoryBackup.kt` 的 manifest + zip 模式，新建 `ModelBackup.kt`。

**导出 zip 结构**：
```
LocalDream_models_20260618.zip
├── manifest.json
└── models/
    ├── anythingv5/
    │   ├── unet.mnn
    │   ├── vae.mnn
    │   ├── clip.mnn
    │   ├── 512.patch
    │   ├── v3                  # NPU 标记
    │   └── config.json         # 模型默认参数
    ├── qteamixcpu/
    │   ├── unet.mnn
    │   ├── ...
    │   └── finished            # CPU 自定义模型标记
    └── upscaler_anime/
        └── upscaler.bin
```

**manifest.json**：
```json
{
  "format": "localdream-model-backup",
  "version": 1,
  "exportTime": "2026-06-18T12:00:00+08:00",
  "models": [
    {
      "id": "anythingv5",
      "name": "Anything V5.0",
      "type": "sd15npu",
      "sizeBytes": 1234567890,
      "fileCount": 5,
      "isCustom": false
    }
  ]
}
```

### 核心逻辑

| 功能 | 说明 |
|------|------|
| `export()` | 用户勾选模型 → 逐个 zip → 写入 SAF URI，进度回调 |
| `import()` | 用户选 zip → 校验 manifest → 逐个解压到 `models/{id}/` |
| `stats()` | 扫描已下载模型，返回列表+大小 |

**导入冲突**：
- 同 ID 已存在 → 默认跳过，可选覆盖
- 保留 ID 与官方模型冲突 → 拒绝导入

### UI

新建 `ModelBackupDialog.kt`，参照 `DataBackupDialog` 风格：
- **导出**：多选模型 → 选保存路径 → 进度条 → 完成
- **导入**：选 zip → 预览模型列表 → 冲突提示 → 进度条 → 完成

入口：ModelListScreen 设置区，"File Manager" 和 "Backup & Restore" 之间。

### 技术要点

- `ZipOutputStream` + `Deflater.BEST_SPEED`（模型文件已压缩，速度优先）
- SAF 交互：导出 `CreateDocument("application/zip")`，导入 `GetContent()`
- 自定义模型标记文件（`finished`/`npucustom`/`SDXL`）一并打包
- `config.json` 一并打包
- 协程 Job + `ensureActive()` 支持取消

### 涉及文件

| 文件 | 操作 |
|------|------|
| `data/ModelBackup.kt` | 新建 |
| `ui/screens/ModelBackupDialog.kt` | 新建 |
| `ui/screens/ModelListScreen.kt` | 修改（添加入口） |
| strings 资源 | 修改 |

### 优缺点

| 优点 | 缺点 |
|------|------|
| 不影响现有架构，C++ 后端无需改动 | 导出/导入耗时（压缩/解压大文件） |
| 不需要新权限 | 占用双份存储空间（内部+zip） |
| 兼容性好，任何 Android 版本均可 | 操作繁琐，需手动导出导入 |
| 安全（不暴露内部存储结构） | 无法实时共享，只能离线迁移 |

---

## 方案 B：可配置外部存储目录

### 设计

让用户选择模型下载的根目录，直接存到外部存储（如 SD 卡或公共目录），省去导出导入的麻烦。

### 当前路径传递链

```
Model.getModelsDir(context)
  → File(context.filesDir, "models")
  → /data/data/io.github.xororz.localdream/files/models/

BackendService.startBackend()
  → val modelsDir = File(Model.getModelsDir(this), modelId)
  → command: --model_dir /data/data/.../models/anythingv5
  → ProcessBuilder 启动 libstable_diffusion_core.so
```

C++ 后端通过 `--model_dir` 接收绝对路径，用标准 POSIX 文件 API 读取模型文件。

### 实现方案

#### 1. 修改 `Model.getModelsDir()`

```kotlin
// 之前
fun getModelsDir(context: Context): File = File(context.filesDir, MODELS_DIR)

// 之后
fun getModelsDir(context: Context): File {
    val customPath = getCustomModelsPath(context)
    if (customPath != null) {
        return File(customPath).apply { if (!exists()) mkdirs() }
    }
    return File(context.filesDir, MODELS_DIR).apply { if (!exists()) mkdirs() }
}
```

#### 2. 路径选择策略

| 存储位置 | 路径示例 | 权限 | C++ 可读 |
|----------|----------|------|----------|
| 应用内部（默认） | `/data/data/.../files/models/` | 无需 | ✅ |
| 应用专属外部 | `/storage/emulated/0/Android/data/.../files/models/` | 无需 | ✅ |
| 公共目录 | `/storage/emulated/0/LocalDream/models/` | `MANAGE_EXTERNAL_STORAGE` 或 Media Store | ✅ (有权限时) |
| SD 卡 | `/storage/ABCD-1234/LocalDream/models/` | `MANAGE_EXTERNAL_STORAGE` | ⚠️ 取决于挂载和权限 |
| SAF document URI | `content://com.android.externalstorage...` | SAF | ❌ C++ 无法读取 |

#### 3. 推荐方案：应用专属外部存储

```
/storage/emulated/0/Android/data/io.github.xororz.localdream/files/models/
```

- **优点**：无需任何额外权限（Android 4.4+），C++ 进程可直接读取
- **缺点**：卸载 App 时会被清除（和内部存储一样）
- **访问**：用户通过文件管理器可以访问到

#### 4. 替代方案：公共目录 + `MANAGE_EXTERNAL_STORAGE`

```
/storage/emulated/0/LocalDream/models/
```

- **优点**：卸载不清除，用户完全控制；其他 App 可共享
- **缺点**：需申请 `MANAGE_EXTERNAL_STORAGE`（Google Play 对此权限审核严格）；Android 11+ scoped storage 限制
- **风险**：Google Play 可能拒绝上架（需提交权限声明）

#### 5. 迁移逻辑

用户切换存储路径后，需迁移已有模型：

```kotlin
suspend fun migrateModels(from: File, to: File) {
    // 1. 停止 BackendService
    // 2. 移动所有模型目录 from → to
    // 3. 更新 preferences 中的路径
    // 4. 重启 BackendService（如果有活跃模型）
}
```

### C++ 后端影响分析

**结论：C++ 后端不受影响，只要传入的路径可读。**

原因：
1. `BackendService` 通过 `--model_dir` 传递绝对路径
2. C++ 端使用标准 `fopen()` / `mmap()` 读取文件
3. `ProcessBuilder` 启动的子进程继承 App 的 UID 和权限
4. 应用专属外部存储（`/Android/data/包名/`）对 App 及其子进程天然可读
5. 公共目录需 `MANAGE_EXTERNAL_STORAGE`，但一旦授予，子进程同样可读

**唯一风险**：SD 卡热插拔时正在运行的模型会崩溃。需检测存储状态并提示用户。

### UI

在设置区新增 "Storage Location" 选项：
- 显示当前路径和已用空间
- 提供选项：内部存储 / 外部存储 / 自定义路径
- 切换时弹出迁移确认对话框
- 迁移过程带进度条

### 涉及文件

| 文件 | 操作 |
|------|------|
| `data/Model.kt` | 修改 `getModelsDir()` |
| `data/Preferences.kt` | 新增 `models_path` 偏好 |
| `service/BackendService.kt` | 无需改动（已用 `Model.getModelsDir()`） |
| `service/ModelDownloadService.kt` | 无需改动（已用 `getModelsDir()`） |
| `ui/screens/ModelListScreen.kt` | 修改（添加存储设置入口） |
| `ui/screens/StorageLocationDialog.kt` | 新建 |
| `AndroidManifest.xml` | 可能新增 `MANAGE_EXTERNAL_STORAGE` |
| strings 资源 | 修改 |

### 优缺点

| 优点 | 缺点 |
|------|------|
| 用户直接访问模型文件，无需导出导入 | 改动范围大，需处理迁移逻辑 |
| 省去压缩/解压步骤 | 公共目录需特殊权限 |
| 外部存储空间通常更大 | SD 卡热插拔风险 |
| 其他 App 可共享模型文件 | 卸载时外部存储可能残留 |
| 一次设置，长期有效 | 首次切换需迁移已有数据 |

---

## 方案 C：混合方案（推荐）

**同时实现方案 A 和方案 B**：

1. **方案 B（外部存储）** 作为基础能力 → 解决日常存储和访问需求
2. **方案 A（ZIP 导出/导入）** 作为补充 → 解决跨设备迁移和备份需求

### 优先级

| 阶段 | 内容 | 理由 |
|------|------|------|
| Phase 1 | 方案 B（应用专属外部存储） | 最实用，改动可控，无需新权限 |
| Phase 2 | 方案 A（ZIP 导出/导入） | 补充跨设备迁移能力 |
| Phase 3（可选） | 方案 B 扩展（公共目录 + `MANAGE_EXTERNAL_STORAGE`） | 高级用户需求 |

### Phase 1 具体改动

1. `Model.getModelsDir()` 支持自定义路径
2. `Preferences` 新增 `models_storage_path`
3. 设置 UI 添加存储位置选择
4. 首次切换时自动迁移已有模型
5. App 启动时检测存储可用性（SD 卡是否在线）

### 风险控制

- 迁移失败时回滚到原路径
- 存储不可用时 fallback 到内部存储
- 不删除源文件直到迁移确认成功

### 已确认：切换目录后自动识别

模型"已下载"状态不是数据库标记，是每次 `refreshAllModels()` 实时探测文件系统：
- `isModelDownloaded()` → 检查 File 目录存在且非空
- `scanCustomModels()` → 扫描目录下标记文件
- 切换目录后 App 启动/返回时自动触发 `refreshAllModels()` → 已迁移模型自动显示为已下载
- 无需额外处理元数据同步

---

## 实施记录

### Phase 1：可配置外部存储 — 开始实施 2026-06-18
