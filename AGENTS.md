# LocalDream

Android 本地 AI 图像生成应用，基于 QNN (高通 NPU) + MNN 双推理引擎。

## 项目结构

```
app/src/main/
├── cpp/          # C++ 推理后端 (CMake + Ninja)
│   ├── src/      # Pipeline, QNN, MNN 推理管线和调度器
│   └── 3rdparty/ # MNN, tokenizers-cpp, cpp-httplib, SampleApp 等
├── java/         # Kotlin/Compose UI 层
├── jniLibs/      # 预编译 .so (libstable_diffusion_core.so)
├── assets/       # QNN runtime .so (qnnlibs/)
└── res/          # 多语言字符串
```

## 构建环境

| 项目 | 路径 |
|---|---|
| QAIRT SDK | `D:/dev/qairt/2.50.0.260828` |
| Android NDK | `D:/dev/android_sdk/ndk/28.2.13676358` |
| Android SDK | `D:/dev/android_sdk`（`ANDROID_HOME`） |
| CMake | `D:/dev/android_sdk/cmake/3.22.1/bin/cmake.exe` |
| Ninja | `D:/dev/android_sdk/cmake/3.22.1/bin/ninja.exe` |
| JDK | `D:/dev/AndroidStudio/jbr`（`JAVA_HOME`；Git Bash 里已默认是它） |
| Git / Git Bash | `D:/dev/git`（bash: `D:/dev/git/bin/bash.exe`） |
| Python | `D:/dev/miniconda3/python.exe` |
| Rust | `D:/dev/rust` (1.96.0, target: aarch64-linux-android) |
| DiT 构建 | WSL2 + Hexagon SDK `~/hexagon-sdk-v6.6.0.0` + NDK r29 |

## 构建

### 各脚本的执行环境（不要混用）

| 脚本 | 必须在哪跑 | 原因 |
|---|---|---|
| `build-sm8850.sh` | **Git Bash**（`D:\dev\git\bin\bash.exe`） | 依赖 `cygpath`、免扩展名调用 `zipalign`/Windows 工具；不是 WSL、不是 PowerShell |
| `build.bat` | Windows CMD 或 PowerShell | 纯 bat |
| `rebuild-native.bat` / `app/src/main/cpp/build.bat` | Windows CMD | 纯 bat |
| `app/src/main/cpp/build.sh` | Git Bash | `cygpath` + 调 `cmake.exe` |
| `app/src/main/cpp/dit/build.sh` | **WSL2** | 需 Hexagon SDK / NDK r29 Linux 工具链 |

AI/脚本调 Git Bash 时直接指到绝对路径，避免 `bash` 解析到 WSL：

```bash
D:/dev/git/bin/bash.exe build-sm8850.sh debug basic
```

WSL 里没有 `cygpath`，且 `zipalign` 是 Windows PE，**不要**在 WSL 执行 `build-sm8850.sh`。

### 选哪条打包命令

| 场景 | 命令（Git Bash） | 包内 native |
|---|---|---|
| SM8850 真机自用 / 调试（**默认**） | `./build-sm8850.sh debug basic` | 只留 V81（QNN + DiT skel） |
| SM8850 对外分发 | `./build-sm8850.sh release basic` | 同上 |
| 多机型通用包 | `build.bat debug\|release basic\|filter` | 全部 qnnlibs arch（~156MB） |

`build-sm8850.sh` 会在打包后剥掉非 V81 的 `assets/qnnlibs/*` 与 `assets/ditlibs/*` 再重签；
源目录 `assets/` 不受影响。产物在仓库根目录：
`LocalDream_armv8a_<versionName>-basic-sm8850-debug.apk` 或 `…-sm8850-signed.apk`
（文件名用 gradle 的 `versionName`，不含 `_debug` 后缀）。

签名约定：

| 构建 | 证书 | 签名方案 |
|---|---|---|
| **debug** | `~/.android/debug.keystore`（`androiddebugkey` / `android`），无需 export | **v3** |
| **release** | `KEY_STORE` 环境变量（见下文），必须 export | v2（脚本固定；可再开 v3） |

debug **不**用 release 证书。从旧的 `CN=pisces312` debug 包升级时必须先
`adb uninstall io.github.xororz.localdream.debug`。

Honor 文件管理器只认 META-INF/v1，对 v3-only 可能报「未包含任何证书」——用
`adb install -r` 即可。

`-d <ip:port>` 可在打包后 `adb install` 到指定设备。

debug 包 applicationId 带 `.debug` 后缀，与正式版并存。

### 何时要动 native（一般打包不必）

只改 Kotlin/Compose / 资源 / 打包脚本 → 直接打 APK，不必重编 `.so`。

改了 `app/src/main/cpp/` 或 `DitEngine.h` ABI 才需要：

```bash
# core（libstable_diffusion_core.so + qnnlibs）
#   AI 工具执行时：在 Git Bash 里直调 cmake.exe，不要走 PowerShell 工具（会被沙箱
#   静默拦截：零输出秒退）；并先 export MSYS2_ARG_CONV_EXCL='*'。
#   详见 docs/2026-09-19-dit-engine-build.md 踩坑记录第 7 条。
rebuild-native.bat    # Windows CMD, 或手动:
export ANDROID_NDK_ROOT=D:/dev/android_sdk/ndk/28.2.13676358
export QAIRT_PATH=D:/dev/qairt/2.50.0.260828
cd app/src/main/cpp
D:/dev/android_sdk/cmake/3.22.1/bin/cmake.exe --preset android-release \
    -DCMAKE_C_COMPILER_LAUNCHER="" \
    -DCMAKE_CXX_COMPILER_LAUNCHER="" \
    -DCMAKE_MAKE_PROGRAM=D:/dev/android_sdk/cmake/3.22.1/bin/ninja.exe
D:/dev/android_sdk/cmake/3.22.1/bin/cmake.exe --build --preset android-release
cp build/android/bin/arm64-v8a/libstable_diffusion_core.so ../../jniLibs/arm64-v8a/
cp -r build/android/qnnlibs/* ../../assets/qnnlibs/
# 若走的是上面手动 cmake（而非 cpp/build.sh），必须补写 build-info，见下文
# 「Native 重建必须同步 build-info」。

# DiT engine（libdit_engine.so + HTP skels）— WSL2 + Hexagon SDK，见
# docs/2026-09-19-dit-engine-build.md。ABI 变更时两侧都要重建。
```

## 正式版（release）签名必须走环境变量

**规则：release/对外分发 APK 只用环境变量签名；密钥路径、密码、alias 一律不入库**
（不写进 `gradle.properties`、`local.properties`、`build*.bat|sh` 或任何提交进 git 的文件）。

未设置签名变量时，release 构建仍可产出 **unsigned** APK（便于本机调试）；需要安装/分发时
必须补齐下列变量后再打包或用 apksigner 重签。

release 构建（含 `build-sm8850.sh release`）需要这些变量。debug 不需要。

### Gradle 签名（`app/build.gradle.kts` 的 `signingConfigs.release`）

通过 Gradle 属性注入，等价环境变量写法：

```bash
# Git Bash / Linux
export ORG_GRADLE_PROJECT_RELEASE_STORE_FILE='D:/my-projects/my-backup/backup-settings/my-android-release.keystore'
export ORG_GRADLE_PROJECT_RELEASE_STORE_PASSWORD='<password>'
export ORG_GRADLE_PROJECT_RELEASE_KEY_ALIAS='pisces312'
export ORG_GRADLE_PROJECT_RELEASE_KEY_PASSWORD='<password>'
./gradlew.bat assembleBasicRelease
```

```powershell
# PowerShell
$env:ORG_GRADLE_PROJECT_RELEASE_STORE_FILE = 'D:\my-projects\my-backup\backup-settings\my-android-release.keystore'
$env:ORG_GRADLE_PROJECT_RELEASE_STORE_PASSWORD = '<password>'
$env:ORG_GRADLE_PROJECT_RELEASE_KEY_ALIAS = 'pisces312'
$env:ORG_GRADLE_PROJECT_RELEASE_KEY_PASSWORD = '<password>'
```

- `build.gradle.kts` 仅在 `RELEASE_STORE_FILE` 存在时挂 `signingConfig`；否则 release 产物不签名。
- debug 变体同样只在提供了上述变量时复用 release keystore（保证覆盖安装签名一致）。
- 密码与 keystore 本机路径见用户全局配置（不在本仓库），**不要复制进本仓库**。

### apksigner 重签（`build.bat` / `build-sm8850.sh` 精简流程）

SM8850 脚本会剥非 V81 `.so` 后 **zipalign + apksigner 重签**，使用另一组变量：

```bash
export KEY_STORE='D:/my-projects/my-backup/backup-settings/my-android-release.keystore'
export KEY_STORE_PASSWORD='<password>'
export KEY_ALIAS='pisces312'          # 可省，默认 pisces312
```

未设置时脚本只警告并跳过签名，产出未签名 APK。

## 重要注意事项

- **SampleApp patch 已直接入库**：`3rdparty/SampleApp/src/` 中的源文件已包含 mmap、convertToFloatInto 等改动，不需要运行时 apply `SampleApp.patch`。`SampleApp.patch` 仅为历史归档。
- **tokenizers-cpp 子模块**：已 fork 到 `pisces312/tokenizers-cpp`，含 Rust 1.96 autoref 修复 (`fix/rust-1.96-autoref`)。
- **ccache 禁用**：CMakePresets.json 配置了 `CMAKE_C_COMPILER_LAUNCHER=ccache`，Windows 下必须传入 `-DCMAKE_C_COMPILER_LAUNCHER="" -DCMAKE_CXX_COMPILER_LAUNCHER=""`。
- **Ninja 路径**：必须显式指定 `-DCMAKE_MAKE_PROGRAM=...`，避免 cmake 找到不兼容版本。
- **上游**：`upstream` = `github.com/xororz/local-dream`，`origin` = 自己的 fork。

## Native 重建必须同步 build-info（防 ABI 误报）

`app/src/main/assets/build-info/{core,dit-engine}.json` 由 `tools/collect-build-info.py`
写入，**gitignored 本地产物**，打进 APK 后供「高级设置 → 构建信息」展示。UI 的
`ABI check` / `matches recorded build-id` **只比 manifest**，不读 `.so` 里的编译常量——
manifest 过期会误报 `MISMATCH`（二进制实际可能完全匹配）。

**规则：每次替换/重建任一 native .so，必须重写对应 manifest。**

| 产物 | 构建入口 | manifest | 谁负责写 |
|---|---|---|---|
| `libstable_diffusion_core.so` | `rebuild-native.bat` / `cpp/build.sh` / `cpp/build.bat` | `core.json` | **仅** `cpp/build.sh` 自动写 |
| `libdit_engine.so` + HTP skels | `dit/build.sh`（WSL2） | `dit-engine.json` | `dit/build.sh` 自动写 |

`rebuild-native.bat`、`cpp/build.bat`、`build.bat` 以及「WSL2 编完只拷 `.so`」都
**不会**更新 manifest。Windows 侧补 core manifest：

```bash
python tools/collect-build-info.py \
  --name core \
  --out app/src/main/assets/build-info/core.json \
  --repo . \
  --abi-version "$(sed -n 's/^#define DIT_ENGINE_ABI_VERSION \([0-9]*\).*/\1/p' app/src/main/cpp/include/DitEngine.h)" \
  --toolchain-path "qairt=${QAIRT_PATH}" \
  --artifact app/src/main/jniLibs/arm64-v8a/libstable_diffusion_core.so
```

**改 `DIT_ENGINE_ABI_VERSION`（`cpp/include/DitEngine.h`）时：core 与 engine 两侧都要
重建，两侧 manifest 都要重写。** 二者由独立脚本构建，只动一侧会在运行时被
`dit_engine_get_api()` 的 exact match 拒绝。验收顺序：

1. UI `ABI check` 为 match，两侧 `abiVersion` 相同
2. 两侧 `matches recorded build-id: true`（`.so` 未在 manifest 写完后被替换）
3. 实机 DiT 出一张图（能出图才证明二进制层 ABI 真匹配）

已踩坑（2026-09-22 Qwen Image 2.1，ABI 3→5）：`67384f7` 重建了两侧 `.so` 但未刷新
`core.json`，UI 报 `MISMATCH: engine v5 vs core v3`，实际 Qwen 可正常出图——纯 stale
manifest 误报。`assets/build-info/` 不进 git，rebuild commit 不会自动带上它。

## submodule 的 dirty 状态是预期的，不要"清理"

`app/src/main/cpp/3rdparty/stable-diffusion.cpp` 及其内嵌 `ggml` submodule 会**长期显示
dirty**（`git status` 报 ` m`），这不是遗留的未提交改动：

- 来源是上游以 patch 文件形式入库的 Hexagon 算子修复（`dit/stable-diffusion.cpp.patch`
  和 `dit/ggml.patch`），由 `dit/CMakeLists.txt` 的 `apply_local_patch()` 在 configure
  阶段 **in-place 施加**到 submodule 工作区（用 `git apply --reverse --check` 做幂等
  检测，已应用则跳过）。
- 正确状态 = submodule 停在上游 commit + patch 留在工作区。构建（gradle 打包）编译的
  就是打过 patch 的代码。

因此：

1. **不要** `git submodule update --force` 去"清理" dirty——会抹掉 patch，虽会幂等重施，
   但中间状态易误判。
2. **不要**把 patch 施加后的改动在 submodule 里 commit——与上游 patch 机制重复，只会造成
   指针漂移。
3. 统计构建状态/是否 dirty 时必须用 `git status --porcelain --ignore-submodules=dirty`
   （`tools/collect-build-info.py` 和 `app/build.gradle.kts` 的 `GIT_DIRTY` 均已如此），
   否则 submodule 的永久 dirty 会污染信号。

## 模型支持

| 类型 | 架构 | 文本编码器 | 分辨率 | 后端 |
|---|---|---|---|---|
| SD1.5 CPU | UNet | CLIP | 512×512 | MNN |
| SD1.5 NPU | UNet | CLIP | 512×512 | QNN |
| SDXL | UNet | CLIP×2 | 1024×1024 | QNN |
| Anima | DiT | Qwen3-0.6B | 1024×1024 | QNN |

Anima 模型通过自定义导入使用，模型目录需包含 `ANIMA` 标记文件。
