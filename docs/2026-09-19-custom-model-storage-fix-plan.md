# 自定义模型存储（customPath）问题清单与修复计划

日期：2026-09-19
分支：`merge/upstream-3.0.0-alpha.1`（已提交 `2bbd93d` / `7c9d431`，未合回 master、未 push）
设备实测：`models_storage_path = /storage/emulated/0/models`，`MANAGE_EXTERNAL_STORAGE=allow`

---

## 实施状态（2026-09-19 更新）

7 条全部已实现，见分支上的 `fix: custom model storage — ...` 系列提交。与计划的偏差：

| # | 状态 | 说明 |
|---|---|---|
| 1 | 已修 | 两处 catch 块改为 `Model.getModelsDir(context, GenerationPreferences...getModelsStoragePath())` |
| 2 | 已修 | `Model.isCustomModelsPathUsable()`（存在性 + `canWrite()`）供 `getModelsDir` 与 `ModelRepository.storageFallback` 共用；模型列表顶部显示红色提示条（新字符串 `models_storage_unavailable`） |
| 3 | 已修 | 采用方案 B 完成标记 `Model.COMPLETE_MARKER = ".complete"`：下载服务解压完成后写入；`ModelRepository.refreshAllModels()` 每次扫描前调 `Model.backfillCompleteMarkers()` 给存量目录补标记（空目录不补） |
| 4 | 已修 | 临时目录改为 `<modelsDir>/.tmp_downloads`（`ModelDownloadService.TEMP_DIR_NAME`），内部 `temp_downloads` 仅作遗留路径由 TempCleaner 清理 |
| 5 | 已修 | 标记清单收敛为 `Model.MARKER_*` + `Model.hasModelMarker()`，`scanCustomModels` 与 `TempCleaner.isRecognizedModelEntry` 共用；TempCleaner 扫描目录改走 customPath；判定不含 `DitEngine.isSupportedDevice()` |
| 6 | 已修 | 逐项 `sizeOf` 比对后再删源；任一项不一致则保留源并提示 `models_storage_migrate_incomplete` |
| 7 | 已修 | 删除 `data/ModelExport.kt`；`RemoteHostService.models()` 只做一次阻塞读；文件管理器加范围说明 `file_manager_scope_hint` |

已知取舍：第 3 条的一次性回填会把**已存在的**半解压目录也算作完整（无法区分），
严格判定只对回填之后的新下载生效。

---

## 0. 现状：覆盖是无遗漏的

自定义存储路径的解析统一走 `Model.getModelsDir(context, customPath)`（`data/Model.kt:316`），
**19 处调用全部传了 `customPath`**，没有裸调用（即直接 `File(context.filesDir, "models")`）：

| 文件 | 处数 | 用途 |
|---|---|---|
| `data/Model.kt` | 7 | 下载判定 / DiT 包判定 / 超分判定 / 重命名 / 删除模型 / PatchScanner / 自定义模型扫描 |
| `service/ModelDownloadService.kt` | 4 | 下载落盘、解压目标、多文件包、`.part` 清理（Service 内封装为 `getModelsDir()`） |
| `ui/screens/ModelListScreen.kt` | 2 | `extractNpuModel`、 `convertCustomModel` |
| `ui/screens/ModelsStorageDialog.kt` | 2 | 切换路径时的迁移（源 / 目标） |
| `data/ModelExport.kt`、`utils/ImageUtils.kt`、`service/BackendService.kt`、`service/RemoteHostService.kt` | 各 1 | 体积统计、超分权重、生成入参、host 模式目录 |

非模型目录（`history`、`runtime_libs`、`safety_checker.mnn`、`tagcomplete`）仍在内部存储，
这是设计如此，不在本次范围内。

**因此下面 7 项都不是"漏传 customPath"，而是设计与健壮性层面的缺陷。**

---

## 1. [高] 导入 / 转换失败时清理的是内部目录，残留留在自定义存储

- 位置：`ui/screens/ModelListScreen.kt:3590`（NPU 模型解压 catch 块）、`:4074`（SD 模型转换 catch 块）
  ```kotlin
  val modelDir = File(File(context.filesDir, "models"), modelId)   // 硬编码内部
  if (modelDir.exists()) modelDir.deleteRecursively()
  ```
- 影响：导入/转换中途失败（最常见的就是大模型转到一半被杀），真实残留目录在
  `/storage/emulated/0/models/<id>`，清理动作却打在空的 `filesDir/models/<id>` 上。
  结果是**残留永久留在外存**，占几 GB；因为它没有 `finished`/`npucustom`/`SDXL` 标记，
  UI 里也不显示，用户无从删除；再次导入同名模型还会撞上残留。
- 修复：两处改为从 `GenerationPreferences.getModelsStoragePath()` 取 customPath，
  复用 `Model.getModelsDir(context, customPath)`；同时把成功路径与失败路径统一为
  同一个 `modelDir` 变量（现在成功路径用的是 `modelsDir` = customPath，失败路径是另一份硬编码，
  本就是不一致）。
- 验收：把自定义存储切到外存 → 导入一个大模型并在转换中强制停止 → 外存对应目录应被清空。

## 2. [高] 自定义路径不可达时静默回退内部存储，且不校验可写

- 位置：`data/Model.kt:316-327`
  ```kotlin
  if (customPath != null) {
      val dir = File(customPath)
      if (dir.exists() && dir.isDirectory || dir.mkdirs()) return dir
      Log.w("Model", "Custom models path unreachable: ...")   // 只有日志
  }
  return File(context.filesDir, MODELS_DIR)...
  ```
- 影响：权限被回收（`MANAGE_EXTERNAL_STORAGE` 在设置里可随时关闭）、目录被用户删掉、
  外存未挂载——任一发生时，App 会**静默切回内部空目录**，模型列表"集体消失"。
  此时用户最自然的反应是重新下载，于是同一批模型在内部/外存各存一份（每份数 GB）。
  另外判定只做 `exists() && isDirectory()`，**不做 `canWrite()`**：目录存在但不可写时
  照样返回，失败会延迟到下载/解压阶段才炸，错误信息与根因无关。
- 修复：
  1. `getModelsDir` 增加可写性校验（不存在则 `mkdirs()`，随后 `dir.canWrite()`），
     不可用时返回内部目录 **并** 通过返回值/回调把"已回退"这件事暴露出来；
  2. `ModelRepository` 增加 `storageFallback: Boolean`（或 `storageStatus`）状态，
     模型列表顶部显示一条提示条（"模型目录不可用，已临时使用内部存储"），
     并禁用下载按钮或至少二次确认，避免误下到内部。
- 验收：在系统设置里关掉"所有文件访问权限" → 打开 App → 应看到明确提示，而不是空列表。

## 3. [高] 内置模型没有完整性标记，半下载状态被当成"已下载"

- 位置：`data/Model.kt:329-341`
  ```kotlin
  val files = modelDir.listFiles()
  return files != null && files.isNotEmpty()      // 目录非空即算下载完成
  ```
- 影响：下载/解压被中断、或用户手工删掉某个文件后，模型仍然显示为"已下载"，
  点生成才在 native 侧报加载失败，错误信息与"模型不完整"无关，排查成本高。
  对比：DiT 包（`isDitPackageDownloaded`，marker + 每个文件 `size > 0`）和超分
  （`isUpscalerDownloaded`，单文件 + `size > 0`）已经是严格口径，**三者不一致**。
- 相关现状：`ModelDownloadService.discardPartialFiles`（`:323`）只在
  `TYPE_MULTI_FILE` 下清理 `*.part`，zip 解压（`TYPE_SD`）与单文件超分失败后
  只删临时文件，**目标目录里的半成品不会被清理**——和第 1 条叠加后，
  外存里会同时留下"半解压目录"和"显示成已下载的模型"。
- 修复（两选一，推荐前者）：
  - **A. 必需文件清单**：给每个 `create*Model()` 增加 `requiredFiles`（如
    `unet.bin`/`unet.mnn`、`clip_v2.mnn`、`vae_decoder.bin`、`tokenizer.json`），
    `isModelDownloaded` 校验这些文件存在且 `length() > 0`；
  - **B. 完成标记**：下载/解压/多文件包全部完成后写入 `.complete`，
    `isModelDownloaded` 只认标记。
- **必须配套一次性回填**：本机现有 13 个模型目录**都没有**这类标记，直接改判定会让它们
  全部变成"未下载"。做法是首次扫描时，对已通过必需文件校验的目录补写标记
  （或直接用方案 A，天生不需要回填）。
- UI：不完整的模型显示"下载不完整 / 重新下载"而不是勾，避免用户以为可用。
- 范围说明：自定义模型（`isCustom = true`）走的是另一条路——`isModelDownloaded` 对它们
  直接 `return true`（`data/Model.kt:329-331`），是否可用由 `scanCustomModels` 的标记
  决定，所以本条只针对内置模型（改判定时不要把自定义项一起改坏）。

## 4. [中] 下载临时目录写死内部存储，跨挂载要全量拷贝

- 位置：`service/ModelDownloadService.kt:141` → `File(filesDir, "temp_downloads")`
- 影响：目标是外存时 `renameTo` 必然失败，代码退化为 `copyTo` + `delete`
  （`:186`、`:208`）。2 GB 的模型 = 内部先占 2 GB + 外存再占 2 GB + 一次全量拷贝，
  内部空间不足时直接失败，且失败点晚、报错不明。
- 修复：临时目录改为与目标同一挂载点——优先 `getModelsDir()` 同级（例如
  `<modelsDir>/.tmp_downloads`），内部存储作为不可用时的兜底；
  或者对 zip/单文件直接下到目标目录下的 `.tmp` 文件，完成后就地解压/改名。
  需要同步改 `TempCleaner`（见第 5 条）里的 `temp_downloads` 清理路径。
- 验收：自定义存储设为外存 → 下载一个大模型 → 内部存储占用不应出现 GB 级增长。

## 5. [中] TempCleaner 只扫内部 models 目录，且标记清单缺 DiT

- 位置：`utils/TempCleaner.kt:70`（`File(filesDir, "models")`）、`:91-103`
  （`isRecognizedModelEntry`：只认 `finished` / `npucustom` / `SDXL`）
- 影响（现在）：外存里的半解压目录**永远不会被清理**，"清理临时文件"按钮对外存无效。
- 影响（改了之后，如果不小心）：`isRecognizedModelEntry` **不认 `ANIMA` / `ZIMAGE` / `KLEIN`**
  这三个上游新加的标记。一旦把扫描目录改成 customPath，所有 DiT 自定义模型目录都会被判定为
  "没有标记的残留" → **被清理按钮删掉**。这是本次修复最大的数据丢失陷阱。
- 修复：分两步，且必须同时做：
  1. 扫描目录改用 `Model.getModelsDir(context, customPath)`；
  2. `isRecognizedModelEntry` 的标记清单与 `ModelRepository.scanCustomModels`
     （`data/Model.kt:552-577`）**共用同一个判定函数**（`ZIMAGE`、`KLEIN`、`ANIMA`、
     `SDXL`、`finished`、`npucustom` 全量），禁止两处各写一份。
- 验收：外存放一个 DiT 自定义模型（仅 `ZIMAGE` 标记）+ 一个半解压目录 →
  点清理后，前者必须还在，后者必须消失。
- 补充陷阱：`scanCustomModels` 对 `ZIMAGE` / `KLEIN` 还额外要求
  `DitEngine.isSupportedDevice()`（`data/Model.kt:553-560`）。共用判定函数时**不能**
  把这个设备条件带进 TempCleaner——否则在不支持 DiT 的机型上，DiT 模型目录一样会被
  判成"无标记残留"而被删。判定函数应只认"标记文件是否存在"，设备支持与否交给列表 UI。

## 6. [低] 迁移成功后无条件删除源目录，无校验无回滚

- 位置：`ui/screens/ModelsStorageDialog.kt:218-243`
- 现状：跨挂载用 `copyRecursively` 兜底，异常时不会删源（这点是对的）；
  但复制完成后不校验文件数/总大小就 `oldDir.deleteRecursively()`，
  中途被杀会两边各留一份（不会丢，但占空间）。
- 修复：复制后比对条目数与总字节数，一致再删源；不一致则保留源并提示"迁移未完全完成"。

## 7. [低] 死代码与小瑕疵

- `data/ModelExport.kt`：唯一函数 `modelStats`（第 51 行）**无任何调用点**，整文件是死代码，
  建议删除（与 `docs/model-export-import.md` 里描述的功能已不是同一套实现，保留会误导）。
- `service/RemoteHostService.kt:150-165`：`models()` 里两次 `runBlocking`
  （一次 `refreshAllModels`，一次在 `installedUpscalers` 参数里取路径），
  且每次都新建 `GenerationPreferences`。跑在 HTTP 线程上不会 ANR，但应改为
  在函数开头读一次路径传进去，或把 handler 接口改成 suspend。
- `ui/screens/ModelListScreen.kt:2323`（`loadTopItems`）只看 `filesDir`，
  模型在外存时该"文件浏览 / 导出导入"面板看不到模型——语义上它是"应用内部文件"，
  但容易误读，建议标题或说明里点明范围。

---

## 建议的落地顺序与提交切分

| 批次 | 内容 | 风险 |
|---|---|---|
| 1 | 第 1 条（catch 块清理路径）+ 第 6 条（迁移校验） | 低，纯路径/校验修正 |
| 2 | 第 2 条（可写校验 + 回退提示） | 低 |
| 3 | 第 5 条（TempCleaner 共用标记判定） | **中**：先合并判定函数再换扫描目录，否则有删库风险 |
| 4 | 第 3 条（完整性判定 + 回填） | **中**：会改变现有模型的显示状态，必须带回填 |
| 5 | 第 4 条（临时目录跟随目标） | 中：涉及下载主流程，需真机大文件实测 |
| 6 | 第 7 条（删死代码 + 收尾） | 低 |

每一批单独提交，commit message 注明 `docs/2026-09-19-custom-model-storage-fix-plan.md` 里的条目号，
便于回看与回滚。

## 不在本计划内（已评估，明确不做）

- 改默认存储位置（保持内部存储为默认，自定义为可选项）。
- 把 `history` / `runtime_libs` 也迁到自定义路径——它们体量小、且迁移会牵动备份与恢复。
  DiT 引擎目录 `DitEngine.dir()` 走内部存储也是**正确**的：那是 native 库而非模型权重，
  模型权重本身已经走 customPath。
- 备份/恢复（`HistoryBackup`）纳入外存模型：模型几 GB，不走备份通道是合理的。

## 备注：为什么合并上游后这类问题会反复出现

上游 `Model.getModelsDir(context)` 只有 `filesDir/models` 一个概念，
**每一次新增功能（DiT 包、host 模式超分、TempCleaner、导入失败清理）默认都只认内部目录**。
fork 侧每合一次上游，都要按"所有模型路径解析点"重新过一遍。
建议在 `Model.getModelsDir` 上加一条约定：**任何新增代码不得直接 `File(context.filesDir, "models")`**，
可以用 detekt 自定义规则或 review checklist 固化。
