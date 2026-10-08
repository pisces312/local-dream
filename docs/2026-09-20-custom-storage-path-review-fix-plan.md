# 自定义模型存储路径（customPath）review 结果与修复计划

日期：2026-09-20
范围：master 上自定义模型存储路径功能的全部改动（源自 `b6627a7`，后续 `fada07a`、`f100528`、`2cadbd1`、`b5cd39f` 等）。
本文档面向实现修复的模型/开发者：每条发现附文件:行号、失败场景与修复方向。行号基于撰写时的 master（`8becfff`），实现时以代码内容为准。

背景机制：`Model.getModelsDir(context, customPath)`（`app/src/main/java/io/github/xororz/localdream/data/Model.kt:329-339`）在 `customPath` 可用时返回 `File(customPath)`，否则**静默回退**内置 `filesDir/models`。配置路径来自 `GenerationPreferences.getModelsStoragePath()`。

> **实现状态（2026-09-20 更新）**：发现 1–8 均已修复并通过两轮 review 与编译验证。修复中 TempCleaner 改为正向识别（只删含 `.part` 的条目），不再使用标记判定，`Model.MODEL_MARKERS`/`hasModelMarker` 随之删除，下文"已验证 OK"中相关描述已过时。除本文档列出的 8 项外，复审追加修复了：文件管理器对 "models" 条目的删除与导入也走 `resolveFileManagerDir`（此前漏改，会误操作内置目录）；英文 `download_model_fallback_hint` 的 `\n` 转义错误。

---

## 高危

### 1. TempCleaner 会删除用户自定义目录里的任意非模型文件

- 位置：`app/src/main/java/io/github/xororz/localdream/utils/TempCleaner.kt:81-83`（删除逻辑）、`isRecognizedModelEntry`（`:102-116`）、`app/src/main/java/io/github/xororz/localdream/ui/screens/ModelsStorageDialog.kt:99-121`（SAF 选目录）、`resolveFsPathFromUri`（`:331-351`）。
- 场景：存储路径对话框允许通过 `OpenDocumentTree` 选择**任意目录**（`/storage/emulated/0` 根目录、Download、DCIM 等均可通过校验）。TempCleaner 的 "models 目录清扫" 会递归删除 modelsDir 下所有不满足 `isRecognizedModelEntry` 的条目——保留条件仅为：目录、且是 reserved id / `upscaler*` / 含 `upscaler.bin` / 含 `Model.MODEL_MARKERS` 之一。**用户照片、下载文件等会被当作垃圾删除**，属数据丢失级风险。
- 修复方向（任选或组合）：
  1. `ModelsStorageDialog` 拒绝非空且不含任何模型标记的目录（提示用户选择空目录或 app 专用目录）；至少拒绝 `/storage/emulated/0` 根及 `Download`/`DCIM`/`Pictures` 等公共目录。
  2. TempCleaner 只删除 app 自己产生的条目：`.tmp_downloads`（`ModelDownloadService.TEMP_DIR_NAME`）+ 命名/结构可判定为半成品模型的目录（如存在 `.part` 文件），不再按"非模型即删"的反向逻辑清扫。
  3. 防御性兜底：清扫前记录 modelsDir 是否等于若干已知公共目录，是则跳过。
- 注意：修复后需保持 `MODEL_MARKERS` 单一来源原则（见下文"已验证 OK"），不要把标记列表复制第二份。

## 中危

### 2. 两处 `PatchScanner.scanAvailableResolutions` 未传 customPath

- 位置：
  - `app/src/main/java/io/github/xororz/localdream/ui/screens/ModelRunScreen.kt:1393`
  - `app/src/main/java/io/github/xororz/localdream/service/RemoteHostService.kt:289`
- 场景：`PatchScanner.scanAvailableResolutions(context, modelId, customPath)` 第三个参数有默认值 `null`（`Model.kt:36`），这两个上游带入的调用点被默认值掩盖，始终扫内置目录。存放在自定义路径的 SD1.5 NPU 模型在运行页和设备互联模式下的分辨率下拉只剩 512×512，即使模型目录里存在 `<w>.patch` / `<w>x<h>.patch`。
- 修复：两处调用补上配置路径。`ModelRunScreen` 处用 `GenerationPreferences(context).getModelsStoragePath()`（suspend，调用点已在 `withContext(Dispatchers.IO)` 内）；`RemoteHostService.toRemoteInfo` 参考同文件 `installedUpscalers`（`RemoteHostService.kt:322-329`）的读法。
- 防御：可考虑去掉 `customPath` 参数的默认值，让编译器强制所有调用点显式传参，避免再次漏传（评估改动面后决定）。

### 3. 切换存储路径后 UpscalerRepository 不刷新

- 位置：`ModelsStorageDialog.kt:266` 附近（迁移完成后只调 `ModelRepository.getInstance(context).refreshAllModels()`）；`UpscalerRepository.modelsStoragePath` 仅在 `ensureLoaded()` / `refreshBaseUrl()` 中重读（`Model.kt:483-491`、`541-548`），后者只在 baseUrl 变化时触发（`ModelListScreen.kt:1367,1404,1422`）。
- 场景：切换内置↔自定义后，UpscalerScreen 的 `isDownloaded` 状态仍按旧路径计算：已下载的 upscaler 显示"下载"，重新下载后仍可能显示"未下载"，直到 app 重启。
- 修复：路径迁移成功后调用 `UpscalerRepository` 的对应刷新方法（重读 `modelsStoragePath` 并刷新各 upscaler 的下载状态）；若该方法不存在则新增一个 `refreshStoragePath()`，与 `refreshBaseUrl()` 同模式。

### 4. 下载进行中切换路径的竞态

- 位置：`ModelDownloadService.getModelsDir()` 每次调用重读 DataStore（`ModelDownloadService.kt:446-449`）；`ModelsStorageDialog` 迁移前不检查下载状态。
- 场景：
  - zip 流程：scratch 下载到旧目录（`:145` 附近），安装时解析到新目录（`:167`/`201` 附近），跨挂载 `renameTo` 失败退化为全量拷贝（`:184-187`），旧位置遗留空 `.tmp_downloads`。
  - 多文件流程：`modelDir` 一次性解析（`:275` 附近）写进旧目录，完成后在新路径下显示"未下载"（数 GB 隐形重复；reserved id 使 TempCleaner 也不会清它）；`discardPartialFiles()`（`:328-335`）清的是新目录，旧目录 `.part` 成孤儿。
  - 迁移本身也可能在 extractor 正在写目录时 rename 该目录。
- 修复：`ModelsStorageDialog` 应用新路径前检查 `ModelDownloadService.downloadState`（Downloading/Extracting 中则拒绝并提示，或排队到下载结束）；同理检查 backend 是否在运行（模型目录被迁移后运行中的 backend 路径失效）。实现时确认 downloadState 的可观察方式（StateFlow）。

## 低危

### 5. 迁移源路径不可用时静默回退内置目录

- 位置：`ModelsStorageDialog.kt:220` 附近 `val oldDir = Model.getModelsDir(context, oldPath)`。
- 场景：旧路径临时不可达（SD 卡卸载、权限被回收）时 `getModelsDir` 静默返回内置目录，迁移搬走并**删除**内置内容后保存新路径、报告成功；真正在卸载卷上的模型被遗留且无任何提示。`newPath` 同理（回退内置后 pref 指向死路径，下次启动才显示回退横幅）。
- 修复：迁移前对 `oldPath`/`newPath` 显式调用 `isCustomModelsPathUsable`（`Model.kt:322-327` 附近），非空且不可用则中止迁移并给出明确提示，不走 `getModelsDir` 的静默回退。

### 6. `storageFallback` 期间下载不受限

- 位置：回退横幅 `ModelListScreen.kt:1063-1098`；下载入口未做判断。
- 场景：自定义路径失效回退内置时只有横幅警告，下载按钮仍可点，会把数 GB 模型静默装进内置存储。此前 fix plan（`docs/2026-09-19-custom-model-storage-fix-plan.md`）条目 2 原计划"禁用下载或二次确认"，只实现了横幅。
- 修复：`ModelRepository.storageFallback == true` 时禁用下载按钮或弹确认框说明将安装到内置存储。

### 7. 文件管理器导出只认内置 models 目录

- 位置：`ModelListScreen.kt:2385-2400`（`loadTopItems`）、`3005-3029`（`exportFilesToUri`），均只操作 `filesDir`。
- 场景：使用自定义路径时，导出"models"得到空/过期内容。浏览侧已有 scope 提示（`:2741` 附近），导出动作没有。
- 修复：导出 models 条目时经 `Model.getModelsDir(context, GenerationPreferences(context).getModelsStoragePath())` 解析实际目录；或在自定义路径生效时隐藏/禁用该导出项并提示。

### 8. 迁移不完整也弹成功 snackbar

- 位置：`ModelsStorageDialog.kt:277` 附近 `onPathChanged()` 无条件调用；`ModelListScreen.kt:563` 据此一律显示成功文案。
- 场景：`migrationIncomplete == true` 时对话框内显示"不完整"文案，外层 snackbar 同时显示"迁移完成"，UI 自相矛盾。
- 修复：`onPathChanged` 携带迁移结果（如 `onPathChanged(complete: Boolean)`），外层按结果显示成功/部分完成文案。

---

## 已验证 OK（不要重复修）

- 所有 `Model.getModelsDir(context, customPath)` 直接调用点均正确传了配置路径：`BackendService.kt:478-483`（每次启动 backend 新鲜读取）、`ModelDownloadService.getModelsDir()`、`Model.deleteModel`/`rename`（`Model.kt:197-265`）、`TempCleaner.kt:69-72`、`ImageUtils.performUpscale`（`:84-93`）、`ModelListScreen` 导入/转换（`:3564-3566`、`:3960-3962`）及其 catch 清理块（`:3662-3668`、`:4153-4159`）。
- C++ 侧无硬编码存储路径：native backend 收绝对 `--model_dir`（`main.cpp`），upscaler 权重走 `X-Upscaler-Path` 头；`PipelineSdxl.hpp:39` 的 "models" 仅为日志字符串。
- 下载流程：scratch 与 models 同挂载点（`ModelDownloadService.kt:460-464`），`.complete` 标记最后写（`:196`），`.part` 在失败与取消路径均清理（`:231`、`:243`、`:328-335`），有截断防护（`:408-412`）。
- 迁移顺序合理：逐条目 rename，跨挂载时拷贝后校验大小才删源，迁移完成后才存 pref（中途杀进程保留旧 pref）——`ModelsStorageDialog.kt:230-262`。
- `MODEL_MARKERS`/`hasModelMarker` 是 `scanCustomModels`（`Model.kt:629-647`）与 TempCleaner 共用的单一来源；TempCleaner 刻意不带 `DitEngine.isSupportedDevice()` 条件，且下载进行中跳过 models 清扫（`TempCleaner.kt:62-66`）。
- `RemoteHostService.installedUpscalers` 按配置路径解析，每次 `/models` 请求单次阻塞读（`RemoteHostService.kt:149-166`、`:322-329`）；`BackendService` 的 `runBlocking` 读在专用 `backend-control` 线程（`:64-66`），不在主线程。
- 历史/缩略图（HistoryManager、HistoryBackup、HistoryMigration）固定在内置 `filesDir/history`，各路径下一致，设计上不随存储路径迁移。
- `ModelExport.kt` 已移除；`backfillCompleteMarkers` 会为旧目录补 `.complete` 标记；`storageFallback` 检测与 `canWrite` 可用性校验（`Model.kt:322-327`、`1135` 附近）均已就位。

## 建议实施顺序

1. 发现 1（数据丢失风险）——最高优先。
2. 发现 2（功能缺陷，两个调用点补参数即可，成本最低）。
3. 发现 3、4（状态一致性）。
4. 发现 5–8（健壮性与 UI 一致性）。
