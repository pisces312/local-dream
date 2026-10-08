# 模型存储路径：改用上游 ModelStorage 为底座

> 日期：2026-10-08
> 状态：**已实施**，分支 `feature/model-storage-upstream`（6 个提交，未推送）。方案见 §4–§5，实施结果与偏差见 §9。
> 触发：上游 `395d7b6`（PR #328 `public-models-folder`）带入了与我们 fork 同类的「选择模型存储位置」功能。
> 覆盖：`docs/2026-09-22-custom-storage-path-upstream-pr-research.md` 的结论自本文起作废（按下文 §6 处理）。

---

## 1. 上游带进来了什么

相关上游提交（`git log master..upstream/master`）：

| 提交 | 内容 |
|---|---|
| `8b1d97e` | `data/ModelStorage.kt`(277) + `ui/screens/ModelStorageSection.kt`(330) + basic flavor 权限声明 |
| `39f88c4` | `ModelStorage.kt` 扩到 483 行，新增 `service/ModelMoveService.kt`(116)、断点续传 journal、备份排除规则 |
| `647dc6f` | Qwen 2.1 顺序加载（与本主题无关，同批进入） |
| `395d7b6` | Merge PR #328 |

`ModelStorageTest.kt`(142 行 / 7 例) 是纯 JVM 单测，只测 `moveTree` + `MoveJournal`，能直接跑。

---

## 2. 两套实现的差异（读代码得出，非推测）

| 维度 | fork `master` | 上游 PR #328 |
|---|---|---|
| 档位 | Internal / App-specific external / **Custom SAF 任意目录** | Internal / `Download/LocalDream` |
| 路径抽象 | `Model.getModelsDir(context, customPath)`，`customPath` 显式穿过 **56 个调用点 / 10 个文件** | `ModelStorage` 单例持 `Location`，所有路径从 `rootFor()` 派生 |
| root 语义 | 配置值 = **models 目录本身** | root 下并列 `models/`、`embeddings/`、`temp_downloads/` |
| 迁移执行 | 对话框内协程同步搬，离开屏幕即无保障；`renameTo` 失败退化为 `copyRecursively(overwrite=true)` | **前台服务** `ModelMoveService` + `MoveState` 进度流 + 独立 `moveScope`；`.moving` 临时名 → rename → `fd.sync()` 后才删源 |
| 崩溃续传 | 无（留双份，标记 incomplete） | `KEY_MOVING_TO` 先落盘 + `MoveJournal` 指名唯一可安全续传的已提交文件 |
| 并发保护 | 检查 download/backend 状态后提示并返回 | `gate`/`moving` 互斥；`unlessMoving{}`；`BackendService` 在锁内发布 `Starting` |
| 空间 | 无预检 | 每文件 `usableSpace` 预检，留 256MB 余量 |
| 大小写 | 无处理 | `ignoresCase()`：共享存储大小写不敏感，`AnythingV5` 会撞内置 `anythingv5`；`checkNames()` 迁前预检重名与 reserved id |
| 权限 | `MANAGE_EXTERNAL_STORAGE` 声明在 **main** manifest，`MainActivity.checkStoragePermission()` **启动即请求** | 只在 **`app/src/basic/AndroidManifest.xml`** 声明（Play 包不声明则 `isChoiceShown()` 自动隐藏选项），用户点了才请求；`isAccessLost`/`pollAccessChange` 处理事后被关 |
| embeddings | 恒在 `filesDir/embeddings`，不跟随自定义路径 | 跟随 root 一起搬 |
| TempCleaner | 扫 models 内的 `.tmp_downloads`；**只做正向识别**（含 `.part` 才删），注释明确「自定义目录里的陌生文件可能是用户自己的」 | 扫 root 旁 `temp_downloads/`；非识别即删，但**仅 `location == INTERNAL` 时才扫 models/** |
| 单测 | 无 | 7 例 JVM 单测 |

**判读**：上游不是「同一功能做得差一点」，迁移状态机在 5 个点上严格强于我们（前台服务保活、journal 续传、跨 mount 拷贝语义、大小写预检、权限降级处理），这 5 点恰好是 `docs/2026-09-20-custom-storage-path-review-fix-plan.md` 的 findings 1–8 没覆盖的。fork 独有价值只剩**任意目录（含 SD 卡）**和 App-specific external 一档。

---

## 3. 冲突面（`git merge-tree --write-tree master upstream/master` 实测）

6 个文件内容冲突，其余全部自动合并（四语 `strings.xml`、4 个新文件、`basic/AndroidManifest.xml`、`app/build.gradle.kts`）。

| 冲突文件 | 上游 hunks | fork hunks |
|---|---|---|
| `data/Model.kt` | 4 | 37 |
| `service/BackendService.kt` | 2 | 21 |
| `service/ModelDownloadService.kt` | 3 | 6 |
| `ui/screens/ModelListScreen.kt` | 11 | 30 |
| `ui/screens/ModelRunScreen.kt` | 2 | 21 |
| `utils/TempCleaner.kt` | 3 | 5 |

上游在这些文件里的改动基本是机械替换（`File(filesDir,"models")` → `ModelStorage.modelsDir()`，`isReservedModelId` 加 `ignoreCase` 形参）。真正的成本是**两套并行管线的取舍**：要么 fork 的 56 个 `customPath` 调用点让位给 `ModelStorage`，要么以后每次同步都重解这 6 个文件。

必须注意的抽象缺口：`ModelStorage.other(location)` **假设档位只有 2 个**。加第三档要把它泛化成 `Location.entries - location`，并重新定义 `pendingMove` 的「原路退回」语义——这是叠加 Custom 的实际成本，不是白送。

---

## 4. 三条路线

| 路线 | 做法 | 得 / 失 | 量级（估，未实测） |
|---|---|---|---|
| **A 全用上游** | merge 后删 `ModelsStorageDialog.kt`(431) + 18 个 `models_storage_*` 字符串，`Model.kt` 等退回上游一参签名，`MainActivity` 去掉启动权限请求 | 以后同步零冲突；**失去自选目录**，本机 `/storage/emulated/0/models` 要一次性搬走 | 1 天 |
| **B 上游为底座 + Custom 叠加（推荐）** | 见 §5 三个提交 | 保住刚需档位，共享上游状态机；后续同步冲突面收敛到 3 个文件 | 2–3 天 |
| **C 保留 fork 实现，硬解冲突** | 只手工解 §3 的 6 处，不引入 `ModelStorage` | 当下最省事；每次同步重复解同样冲突，迁移仍无断点续传 | 0.5 天 / 每次同步 |

推荐 **B**：fork 的自定义路径管线是维护负债（`Model.kt:36-38` 注释自己写着「a bug that already shipped once」——漏传 `customPath` 就会静默扫内部目录），而上游的 root 抽象正好消灭这类错误；Custom 在该抽象下只是 `rootFor()` 的一个分支，不是一整套管线。

---

## 5. B 路线的提交拆分与验收

分支：`feature/model-storage-upstream`。**实施时方向反转**：不从 `master` 起，而是从 `upstream/master` 起、把 `master` merge 进来（`git merge --no-ff master`）。这样 `master` 成为 HEAD 的祖先，将来 `master` 可以直接 fast-forward 到这个分支，不需要 force-push——上一轮 `fork/rebuild` 吃过这个亏。代价是 merge 时 `--ours` 指向上游一侧。

1. `merge: upstream/master through 647dc6f`
   存储相关一律取上游；本提交先把 fork 的 `customPath` 面摘掉（`Model.kt` 一参化、`ModelsStorageDialog.kt` 暂删、TempCleaner 取上游版）。
   验收：`./gradlew compileBasicDebugKotlin` + `./gradlew testBasicDebugUnitTest`（上游 7 例全绿）。
2. `feat: add CUSTOM location to ModelStorage`
   `Location.CUSTOM` + `rootFor()` 分支（绝对路径存 `model_storage` prefs）；复用 fork 的 `resolveFsPathFromUri` / `isPublicStorageDir` / `isCustomModelsPathUsable` 三个护栏；泛化 `other()` / `checkNames()` / `sizeAt()`；Custom 档位下 TempCleaner 恢复 fork 的正向检测；`ModelStorageSection` 加第三档 UI + 四语文案；`ModelStorageTest` 补 Custom 用例。
3. `fix: keep legacy modelsStoragePath readable`
   读到旧 `GenerationPreferences.modelsStoragePath` 时按 legacy 语义（值 = models 目录）解释，设置页提示一次性搬迁；避免升级后整屏模型「像一夜消失」。

其余同批处理：`versionName` → `3.0.0-alpha.5`，保留 fork 的 `_debug` suffix 与内置 debug keystore 逻辑（merge-tree 显示 `app/build.gradle.kts` 可自动合并）。

计划里写的「纯 Kotlin，不动 native」**不成立**：`647dc6f` 同时改了 `app/src/main/cpp/src/main.cpp`（删掉 qwen21 的 `te=disk,diffusion=disk` 特判 ⇒ 回落到默认 `all=disk`），这正是 `docs/2026-10-05-qwen21-1024-decode-oom.md` §5 A 评估过的首选修复（同一改动在 `docs/2026-10-04-qwen21-quant-variants.md` §8/§9 里叫「方案 C」，且是它的超集：连 VAE 也按需加载）。既然取了上游的源文件，`libstable_diffusion_core.so` 必须重编，并按 `AGENTS.md` 重写 `assets/build-info/core.json`。`DitEngine.h` 的 `DIT_ENGINE_ABI_VERSION` 未变 ⇒ WSL2 侧 `libdit_engine.so` 不必重建。

---

## 6. 顺带要处理的文档

`docs/2026-09-22-custom-storage-path-upstream-pr-research.md` §2.1 的前提已失效：它写「上游拒绝 all-files 权限，本 feature 永不合并」，而上游现在自己在 `basic` flavor 声明了 `MANAGE_EXTERNAL_STORAGE`（注释理由：Play 限制，GitHub 包才有）。按该文档 §5 的自我约定，**另开新文档覆盖**，不改旧文档。

---

## 7. 验证口径

- 自动化：`./gradlew testBasicDebugUnitTest`（上游 7 例 + Custom 新增用例）。
- 打包装机：`D:/dev/git/bin/bash.exe build-sm8850.sh debug basic -d <ip:port>`，AI 职责止于 `adb install -r`。
- 真机冒烟沿用 `docs/2026-09-22-fork-upstream-sync-qwen21.md` §10 第 5、7 条，另加本次专属判据：
  **搬 GB 级模型途中 `adb shell am force-stop`，重进设置页能自动续传、不产生双份、TempCleaner 不误删半份模型。**
- 若选了保留 Custom（路线 B）：`Download/` 与 SD 卡目录两个目标都要跑一次完整迁移 + 一次中途杀进程。

---

## 8. 待决策（阻塞实施）

| # | 问题 | 选项 |
|---|---|---|
| 1 | 路线 | A 全用上游 / **B 上游底座 + Custom 叠加** / C 硬解冲突 |
| 2 | App-specific external（`/Android/data/...`）这一档 | 砍掉（Android 13+ 文件管理器基本看不到，上游也没做） / 保留为第四个 Location |
| 3 | 启动即请求 all-files 权限 | 改成上游的懒请求 / 暂留启动请求，等 Custom 落地再收 |
| 4 | 本机 `/storage/emulated/0/models` 现有模型 | 原地继续用（选 B 天然解决） / 一次性搬到 `Download/LocalDream` / 搬回应用内部 |

**决策（2026-10-08 用户定）**：1 选 B 但基座反过来用上游 master；2 砍掉 AppExternal；3 改懒请求；4 原地继续用。

---

## 9. 实施记录（同一天，分支 `feature/model-storage-upstream`）

提交从旧到新：

| 提交 | 内容 | 对应计划 |
|---|---|---|
| `68f8068` | merge `master` 进上游基座，存储取上游、fork 非存储定制保留 | §5.1 |
| `7145865` | 把 `resolveFsPathFromUri` 独立成 `utils/SafPath.kt` | §5.1 的修补 |
| `6da3973` | MainActivity 权限改懒请求，`MANAGE_EXTERNAL_STORAGE` 声明只留 `src/basic` | 决策 2、3 |
| `79c2a22` | `Location.CUSTOM`（root/移动/大小写/权限/UI 第三档/四语文案） | §5.2 |
| `fa5046d` | 旧偏好原地接管 + `LegacyStoragePathTest` 4 例 | §5.3、决策 4 |
| `d9ffa4d` | 重编 `libstable_diffusion_core.so` + 重写 `core.json` | §5 末尾的更正 |

### 与计划的偏差（都是实测逼出来的，不是改主意）

1. **「把 fork 的修改 apply 到上游上」这条路走不通**。fork 跟踪 25 个 `.so`，上游一个都不跟踪；
   `git diff` 产出的 binary patch 没有 full index line，`git apply`（含 `-3`）直接拒绝。
   能保持用户要的「干净」的办法就是反过来 merge：`upstream/master` 为基座 + `git merge --no-ff master`，
   这样 `master` 成为祖先，将来 fast-forward 即可，不需要 force-push。
2. **`other(location)` 不是泛化，是删掉**。三档下「另一边」无定义。改成「移动永远从 `location(context)` 出发」，
   而 `setLocation()` 只在移动结束时发生 ⇒ 进程中途重启后这个不变式仍然成立，上游的续传语义原样保留。
   UI 侧唯一的外部调用点（确认弹窗算字节）跟着换成 `sizeAt(context, location(context))`。
3. **AppExternal 一档不需要写删除代码**：取上游 `ModelStorage` 后它自然不存在（枚举只有 INTERNAL/DOWNLOADS）。
4. **旧自定义目录不是「提示用户搬」，是自动 rename 下沉一层**。上游布局要求 `models/` 与 `embeddings/` 平级
   （native `main.cpp:854` 按 `--model_dir` 上两级找 embeddings），而旧设置存的值就是 models 目录本身。
   `LegacyStoragePath.relocateFlatRoot()` 把 `root/<id>` 改名到 `root/models/<id>`：同卷 rename，不复制数据，
   几十 GB 也是毫秒级；由文件系统状态判断，可重复调用，权限恢复时在模型页 resume 补做。
   `embeddings/` 不跟随——改自定义目录之前它本来也一直留在内置存储，没有回归。
   结果是路径多套一层（`/sdcard/models/models/<id>`），换来的是三档布局统一 + embeddings 查找正确。
5. **fork 的护栏合并成一个 `isUsableCustomRoot()`**：拒绝盘根与 `Download`/`DCIM`/`Pictures` 等共享顶层目录，
   接受空目录、已有上游布局的目录、以及「全是模型目录」的旧布局目录（最后这条正是原地接管能成立的原因）。
6. **第一次 merge 提交编译不过**：`resolveFsPathFromUri` 原本挂在被删的 `ModelsStorageDialog.kt` 里（同包所以无需 import），
   而 fork 独有的 `runtime_libs` 导入在用它。`7145865` 单独修，保留 merge 提交的原貌。

### 已验证

- `./gradlew compileBasicDebugKotlin` 通过。
- `./gradlew testBasicDebugUnitTest`：**12 例全绿** = 上游 `ModelStorageTest` 7 + 新增 `LegacyStoragePathTest` 4 + `ExampleUnitTest` 1。
- 合并后的 `basicDebug` manifest 里 `MANAGE_EXTERNAL_STORAGE` 恰好出现 1 次（来自 `src/basic`），`filter` 变体不声明。
- **对拍**（客观判据）：`git diff --name-only upstream/master HEAD` ⊆ `git diff --name-only upstream/master master`，
  多余文件 0 个；差集 8 个全是存储抽象本体（`ModelStorage.kt`/`ModelMoveService.kt`/`ModelStorageSection.kt`/
  `ModelStorageTest.kt`/`ModelsStorageDialog.kt`/`src/basic/AndroidManifest.xml`/两个 backup rules），
  即「这些文件现在与上游逐字节相同」正是本次目标。fork 的非存储定制（`.complete` 标记 + backfill、
  `CUSTOM_MODEL_MARKERS` 正向检测、rename→copy 兜底、`WAKE_LOCK`、debug 日志采集、构建脚本、docs）全部保留。
- native：`libstable_diffusion_core.so` 已按上游 `main.cpp` 重编，`assets/build-info/core.json` 由 `cpp/build.sh` 重写。
- 包装机产物：`./build-sm8850.sh debug basic` → `LocalDream_armv8a_3.0.0-alpha.5-basic-sm8850-debug.apk`（116 MB，剥掉 16 个非 V81 lib）。
  逐项核对过：签名 v3-only / `CN=Android Debug` / `a0a19938…`（符合 AGENTS.md 的 debug 恒用 debug 证书）；`lib/` 只剩 `arm64-v8a`；
  `assets/qnnlibs` 剩 5 个（Htp/V81/V81Skel/V81Stub/System）、`assets/ditlibs` 只剩 `libggml-htp-v81.so`；
  包内 `.so` 的 `.note.gnu.build-id` = `d253c1d7…4d61b`，与 `core.json` 记录一致（AGP strip 只少 8 字节段表，build-id 未变，
  所以设置页「matches recorded build-id」不会误报）；两份 manifest `abiVersion` 均为 5。

### 还没做

- 真机冒烟（§7 的判据），重点是三条：旧目录原地接管后模型列表完整、Internal↔Custom 迁移中途 `force-stop` 能续传、
  Qwen Image 2.1 在 1024² 能出图（验证 `all=disk` 真的把解码内存让出来了）。本机只挂着 x86_64 模拟器，跑不了 sm8850 包，
  所以装包后的验证归用户。
- 分支**未推送**，`master` 未动。
