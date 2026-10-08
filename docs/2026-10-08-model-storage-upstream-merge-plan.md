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
| `4f30c1a` | 接管自选目录时把内置 `embeddings/` 播种到 root 下（+ 6 例测试） | 审阅后补的缺口 ① |
| `08b2807` | 目录不可达/权限被关 → 模型页对话框 + 闸住下载入口 | 审阅后补的缺口 ② |
| `0911467` | 接管搬迁按 API 分档放行 + `dit.gguf` 只在新包就位后才删 | §10.3、§10.4 |
| `0a618b1` | 空的 `models/` 不再算「已就位」+ 每次刷新列表补做搬迁 | §10.5 |

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
   几十 GB 也是毫秒级；由文件系统状态判断，可重复调用，权限什么时候恢复什么时候补做（§10.5）。
   `embeddings/` 也一起搬过去，见第 7 条（`fa5046d` 落地时留下的缺口，不是设计选择）。
   结果是路径多套一层（`/sdcard/models/models/<id>`），换来的是三档布局统一 + embeddings 查找正确。
5. **fork 的护栏合并成一个 `isUsableCustomRoot()`**：拒绝盘根与 `Download`/`DCIM`/`Pictures` 等共享顶层目录，
   接受空目录、已有上游布局的目录、以及「全是模型目录」的旧布局目录（最后这条正是原地接管能成立的原因）。
6. **第一次 merge 提交编译不过**：`resolveFsPathFromUri` 原本挂在被删的 `ModelsStorageDialog.kt` 里（同包所以无需 import），
   而 fork 独有的 `runtime_libs` 导入在用它。`7145865` 单独修，保留 merge 提交的原貌。
7. **接管自选目录时必须把 `embeddings/` 一起带过去**（`4f30c1a` 补，审阅时发现的缺口）。
   反转文字（inversion）从来只写内置存储，即使模型在自选目录也一样，而 native 只认 root 边上的那份
   （§2 的 `main.cpp:854`）⇒ 不处理的话用户切到自选目录后，已导入的 embeddings 在设置页看不见、
   生成时也不生效，等于静默作废。`LegacyStoragePath.seedEmbeddings()` 复用上游的
   `ModelStorage.moveTree`/`MoveJournal`（不另写拷贝逻辑），单独一个 journal 文件以免覆盖真迁移的断点记录；
   跨卷所以是真拷贝。判据用**文件名集合**而不是「目标非空就跳过」：后者会让一次中断的播种永久卡住，
   前者能续传且不覆盖 root 里已有的同名文件。这与「搬家不重下模型」是同一条承诺的两半。
8. **上游没有 fork 原有的 `storageFallback` 提示**（审阅时发现的第二个缺口，本轮补）。fork 的模型页会在
   自选目录不可用时显示一张红卡 +「去设置」；取上游后这条整条消失，于是权限被关 / 目录被删的情况表现为
   **列表静静变空**，而用户最自然的反应是重新下载——那会往内置存储再写一份几十 GB。
   本轮改成：`ModelStorage.storageProblem()` 把原因分成 `AccessLost`（权限被关，仅 API 30+ 判定，
   否则 Android 10 上正常的老设备会被误报）与 `Unreachable`（权限没问题，目录本身读不了/写不了），
   `ModelRepository` 在 `refreshAllModels()` 里**第一次 `getModelsDir()` 之前**取值——`modelsDir()` 会
   `mkdirs()`，先问后查才能让被删的目录仍然表现为「不可达」。UI 表现为模型页一个对话框
   （`AccessLost` → 「授予访问」+「知道了」；`Unreachable` → 只有「知道了」，并回显具体路径），
   同一原因只弹一次，原因变了才重弹；下载入口在问题未解决前一律闸住并说明理由。

### 已验证

- `./gradlew compileBasicDebugKotlin` 通过（含本轮的对话框 + 下载闸门）。
- `./gradlew testBasicDebugUnitTest`：**21 例全绿** = 上游 `ModelStorageTest` 7 + `LegacyStoragePathTest` 13 + `ExampleUnitTest` 1。
  其中 6 例专测 embeddings 播种：搬进 root、幂等（第二次调用不动）、root 已有同名文件时绝不覆盖、
  root 缺的文件补齐、中断后的 `.moving` 残留下次能续完、内置目录空/缺失时直接跳过。
  `0a618b1` 再加 3 例专测「空的 `models/`」：只被 `mkdirs()` 建出来的空目录照样下沉（并验证下沉后二次调用为 no-op）、
  `models/` 已有内容时一个字都不动、以及这次没建出来的空 `models/` 不会被删掉（删除只允许发生在自己刚建且仍空的那个上）。
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
- 本轮新文案 3 个 key（`model_storage_problem_title`/`_unreachable`/`_download_blocked`）四语齐全，
  复用已有的 `model_storage_access_lost`/`model_storage_grant`/`got_it`/`cannot_download`；
  四语 key 集合对齐检查只余**上游本就有**的 14–18 个缺翻（`delete_*`/`export_*`/`import_*`/`no_files` 等），
  与本次改动无关。
- `isRootUnreachable()` 不建目录这条是代码事实而非推测：它只走 `rootFor()`，而 `modelsDir()` 的 `mkdirs()`
  在 `ModelStorage.kt:168-170`，`storageProblem` 的赋值点在 `refreshAllModels()` 里第一次 `getModelsDir()` 之前。
- `08b2807` 之后重新 `./build-sm8850.sh debug basic` 出包（116 MB），并用
  `aapt2 dump resources <apk>` 在**包内 resources.arsc** 里确认三个新 key 的 default/zh/ja/ko 四份文案都在
  （`model_storage_problem_title` `0x7f0f01d5`、`_unreachable` `0x7f0f01d9`、`_download_blocked` `0x7f0f01c8`），
  不是只看源文件。第一次包装机失败：`build-sm8850.sh -d emulator-5554` 会把设备串当主机名解析而报错，
  且当时 x86_64 模拟器在跑、真机没连；`0911467` 之后真机（SM8850）出现，改用不带 `-d` 的
  `adb install -r` 装上成功。

### 还没做

- 真机冒烟（§7 的判据），重点是六条：旧目录原地接管后模型列表完整、接管后已导入的倒置在设置页仍看得见
  且生成时生效（本轮的播种）、Internal↔Custom 迁移中途 `force-stop` 能续传、
  到系统设置里关掉「所有文件访问」后模型页弹出可授权的对话框且下载被闸住、
  **先拒权限再授权**的顺序下（即 §10.5 那条路径）授权回来后列表应自己恢复，不需要进设置页、
  也不该出现「空的 `models/` 把平面目录永远封住」，
  Qwen Image 2.1 在 1024² 能出图（验证 `all=disk` 真的把解码内存让出来了）。包已装到真机，
  点击层的验收归用户（此前只挂着 x86_64 模拟器，跑不了 sm8850 包）。
- 本轮四个补丁（`4f30c1a`/`08b2807`/`0911467`/`0a618b1`）：编译 + 21 例单测 + 重新打包 + `adb install -r` 到真机
  （Honor `BKQ-AN80`，`ro.soc.model=SM8850`，API 37）均 Success，`versionName=3.0.0-alpha.5_debug`。
  **界面点击层的验收还没做**，按分工归用户；`docs` 之外没有再动 native。
- 装机时序要说清：`0a618b1` 的**功能代码**（`relocateFlatRoot` 新判据 + 刷列表补做 + `customPath` 守卫）已在那次
  Success 的包里；提交之后只剩 `Model.kt` 一行注释措辞的改动，重出的包（116 MB）准备好时设备已从
  `adb devices` 掉线（mDNS 串消失，`adb get-state` = no devices），所以**最后一版没再装第二次**。
  两者只差一行注释，行为一致；真机重连后直接 `adb install -r` 那个文件即可。
- §10.3 的 Android 9/10 放行**在这台机器上无法实测**：真机是 Android 15/16，模拟器是 x86_64 跑不了 sm8850 包，
  所以那条只有代码走查作为依据。有 Android 9/10 设备时可以这样验：旧包设过自定义目录 → 装新包 → 列表应完整、
  设置里「自定义文件夹」应显示为已选中且能改回应用存储。
- §10.4 的前置条件同理：已下新包的用户第一次刷列表应回收那 4 GB，未下新包时 `dit.gguf` 必须原样留着。
- 分支**未推送**，`master` 未动。

## 10. 升级路径核对 + 模型目录的删除策略（2026-10-08 追加，逐处读代码得出）

### 10.1 embeddings 只在内置存储、外部模型目录里没有——会不会被正确带走

| 升级后所处档位 | `embeddings/` 的结局 | 依据 |
|---|---|---|
| INTERNAL（从没设过旧自定义目录） | 不用动：`rootFor(INTERNAL)=filesDir` ⇒ `root/embeddings` 本来就是 `filesDir/embeddings` | `ModelStorage.kt:151`/`:173` |
| 旧自定义目录被原地接管 → CUSTOM | `seedEmbeddings` 把 `filesDir/embeddings` 整个搬进 `root/embeddings`（跨卷=真复制，`fd.sync()` 后才改名，内置副本最后删） | `LegacyStoragePath.kt:106-116` + 单测 `movesAppEmbeddingsIntoTheAdoptedRoot`/`fillsAnExistingButEmptyRootFolder` |
| 在设置里主动 INTERNAL→DOWNLOADS/CUSTOM | 上游 `move()` 遍历 `MOVED_DIRS=[models, embeddings]`，倒置跟着走 | `ModelStorage.kt:82`/`:406-413` |
| root 里已有**同名**文件 | 整条跳过，两边都留着，下次 resume 再判；不覆盖、不删 | `LegacyStoragePath.kt:158-159` |
| root 有 A、内置有 B（名字不冲突） | 合并，只把 B 搬过去 | 单测 `addsAppEmbeddingsThatTheRootDoesNotHaveYet` |
| 搬到一半被杀 | 内置还剩的文件不算冲突 ⇒ 下次调用续完 | 单测 `finishesAnInterruptedSeedOnTheNextCall` |

⇒ 问的「升级时倒置只在内置、外部没有」= 表里第 2/3 行，能正确处理且有测试覆盖。曾经唯一漏网的是 Android 9/10，见 10.3（本轮已修）。

### 10.2 模型目录里的删除动作清单

> 判据是用户 2026-10-08 定的规则：**模型目录里的文件，只在「界面点删除模型」或「切换存储目录」时才允许删**；
> 改名（rename）不算删除但也只在原地接管那一次发生。下面按触发者逐处审。

| 位置 | 删什么 | 触发者 | 与「非用户点击不删」一致？ |
|---|---|---|---|
| `Model.deleteModel()`（`Model.kt:198-210`） | 整个模型目录 `deleteRecursively()` | 界面点删除 | ✅ |
| `ModelStorage.move()`（`:402`） | 源侧 `temp_downloads/` | 用户确认的切换目录 | ✅（本就是移动的一部分） |
| `ModelStorage.moveTree()`（`:482`/`:487`/`:510`/`:540`/`:542`） | 移空后的源目录、`.moving` 残片 | 用户确认的切换目录 / 原地接管的搬运 | ✅ |
| `LegacyStoragePath.relocateFlatRoot()`（`:154`） | **只删本次自己建出来、且搬完仍是空的** `models/`（`created` 在 `:145`；不是本次建的一律不删） | 接管时 / 每次刷新列表（§10.5） | ✅（不碰任何用户文件） |
| `ModelDownloadService`（`:144`/`:168-171`/`:242`/`:330`） | `temp_downloads`、重下同名 zip 前的旧目录、失败残留、`.part` | 用户点下载 | ✅ |
| `TempCleaner.clean()`（`:50`/`:78`） | 内置 `models/` 下无标记的残骸 + `temp_downloads` | 界面「清理临时文件」确认框（`ModelListScreen.kt:532-540`） | ✅ 且**只扫 INTERNAL**，共享/自选目录一概不碰 |
| `Model.initializeModels()` → `createQwenImage21Model()`（`Model.kt:756-764`） | ~~无条件~~ **仅当 `dit.safetensors` 已在**才删 `models/qwen_image_2_1/dit.gguf`（旧 Q4_0 DiT 权重 4 GB） | 每次刷新列表自动 | ✅ 本轮加前置条件后不再是「无谓的删除」：新包没就位时一份文件都不动 |

其余 `delete()` 调用点（`TagAutocompleteRepository`/`HistoryManager`/`RuntimeManager`/`LogCapture`/
`HistoryBackup`）都在模型目录之外，不在本条约束范围内。

### 10.3 已修：Android 9/10 上接管不做搬迁

`relocateIfNeeded()` 原来用 `hasAllFilesAccess()` 当闸门（`LegacyStoragePath.kt:96`），而它内含
`isPublicStorageSupported()`（API 30+）⇒ **API ≤ 29 恒为 false，这条永远提前返回**。
而 `adopt()` 不看 API 就设了 CUSTOM，于是 Android 9/10 上：目录仍是旧平面布局、`modelsDir()` 又 `mkdirs()`
出一个空的 `root/models/` ⇒ 列表静静变空；`storageProblem` 也报不出来（`isAccessLost` 按 API 分档为 false，
`isRootUnreachable` 看 root 本身确实可读可写）；且 Settings 里 CUSTOM 行 `enabled = supported` 也是 false，
只剩「应用存储」可点，等于把人困在原地。minSdk 是 28，这是活路径。
**改法（本轮）**：闸门换成 `ModelStorage.isAccessLost(context)`——它就是「按 API 分档地判断权限」的那把尺，
API ≤29 恒 false ⇒ rename 与播种照常执行；API 30+ 未授权时仍然提前返回，行为不变。
`adopt()` 里 `hasAllFilesAccess() && !isUsableCustomRoot(dir)` 那句保持不动：它的语义是「能查才查」，本来就该用权限位。

### 10.4 已修：`dit.gguf` 的自动删除

`createQwenImage21Model()` 来自**上游** `3f76ac7`（`upstream/master` 也有，不是 fork 引入），
在每次构建模型列表时删 `models/qwen_image_2_1/dit.gguf`。新包（FP8）要的是
`dit.safetensors`/`llm.gguf`/`llm_vision.gguf`（`Model.kt:288-294`），旧文件确实没人读；
但若用户还没下新包，这一删就把他手里唯一那份 4 GB 权重抹掉了，且没有任何提示——
自选目录里那还可能是他自己放的文件。
**改法（本轮）**：加前置条件 `File(modelDir, "dit.safetensors").isFile` 才删，保留上游回收 4 GB 的初衷，
又不会出现「新的还没就位、旧的先被抹掉」。代价：还没下新包的用户那 4 GB 继续占位，下完新包后第一次刷新列表回收。
将来同步上游时这条要**保留 fork 版本**，别默认取上游把它带回无条件删除。

### 10.5 已修：空的 `models/` 会永久封住接管（`0a618b1`）

回答「embedding 只从内置搬过去一次吗」时顺带查出来的死胡同，与 §10.3 是同一类问题（权限来得比接管晚），
但这条连授权之后都修不好。现象链（逐处读代码得出）：

1. `adopt()` 一生只跑一次：见到 `customPath != null` 就返回（`LegacyStoragePath.kt:52`），
   所以接管之后只有设置页存储区的 resume 还会叫 `relocateIfNeeded()`（`ModelStorageSection.kt:366`）。
2. 而那一次可能因为没权限提前返回——API 30+ 未授予「所有文件访问」时 `isAccessLost` 为 true（§10.3 的闸门）。
3. 与此同时 `modelsDir()` 会 `mkdirs()`（`ModelStorage.kt:169-171`），而刷新列表第一件事就问它
   ⇒ 自选目录里留下一个**空的 `models/`**。
4. 旧判据「`models/` 存在 = 布局已就位」（原 `:129`）从此让 `relocateFlatRoot()` 永远返回 0。

结果：用户在新加的模型页对话框里点「授予访问」回来后，`storageProblem` 是 null（root 可达、权限也有，
它查不出「文件在错的层级」），列表仍然空，且再没有任何提示——只有去 设置→模型存储 让那段 resume 跑一次才可能自愈。

| 位置 | 改动 | 为什么这样判 |
|---|---|---|
| `relocateFlatRoot()`（`:140`） | 「`models/` 存在**且有内容**」才算已就位，空的照旧下沉 | 空的那个是我们自己 `mkdirs()` 出来的，不是布局信号 |
| `relocateFlatRoot()`（`:145`/`:154`） | 收尾 `delete()` 只删**本次建出来且搬完仍空**的目录 | §10.2 的规则：不是本次建的就不该由它删 |
| `refreshAllModels()`（`Model.kt:1143`） | 算 `storageProblem` 之前，在 IO 线程补一次 `relocateIfNeeded()` | 每次重扫都自愈；模型页授权回调走的正是这条 |
| `relocateIfNeeded()`（`:95`） | `CUSTOM` 但没记路径时直接返回 | `rootFor()` 那种情况回答的是 `filesDir`（`ModelStorage.kt:161`），继续走下去会把**应用自己的文件**搬进 `models/`；这个守卫在把 `relocateIfNeeded` 挂到每次刷列表之后才变得要紧 |

幂等性有测试断言：搬完 `models/` 就有内容 ⇒ 第二次调用返回 0（`relocatesIntoAModelsFolderThatWasOnlyCreated`），
`models/` 已有内容时旁边的散目录一律不动（`doesNotReachIntoAModelsFolderThatAlreadyHoldsSomething`），
本次没建出来的空 `models/` 不会被删（`keepsAnEmptyModelsFolderItDidNotCreate`）。
副作用是刷新列表多一次 `relocateIfNeeded`（几次 `listFiles()`，空跑时不写盘），以及每次刷列都会
`journal.clear()` 那个**接管专用** journal 文件——它是独立文件名，不会覆盖真迁移的断点记录（`:36-39` 的原注释）。
