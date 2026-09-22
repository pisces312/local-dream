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
| CMake | `D:/dev/android_sdk/cmake/3.22.1/bin/cmake.exe` |
| Ninja | `D:/dev/android_sdk/cmake/3.22.1/bin/ninja.exe` |
| Rust | `D:/dev/rust` (1.96.0, target: aarch64-linux-android) |

## 快速构建

```bash
# 1. 编译 native .so
#    AI 工具执行时：在 Git Bash 里直调下面的 cmake.exe，不要走 PowerShell 工具（会被沙箱
#    静默拦截：零输出秒退）；并先 export MSYS2_ARG_CONV_EXCL='*' 关掉参数路径转换。
#    详见 docs/2026-09-19-dit-engine-build.md 踩坑记录第 7 条。
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

# 2. 构建 APK
build.bat release basic         # 通用 APK
build-sm8850.sh release basic   # SM8850 精简 APK
```

## 正式版（release）签名必须走环境变量

**规则：release/对外分发 APK 只用环境变量签名；密钥路径、密码、alias 一律不入库**
（不写进 `gradle.properties`、`local.properties`、`build*.bat|sh` 或任何提交进 git 的文件）。

未设置签名变量时，release 构建仍可产出 **unsigned** APK（便于本机调试）；需要安装/分发时
必须补齐下列变量后再打包或用 apksigner 重签。

### Gradle 签名（`app/build.gradle.kts` 的 `signingConfigs.release`）

通过 Gradle 属性注入，等价环境变量写法：

```bash
# Git Bash / Linux
export ORG_GRADLE_PROJECT_RELEASE_STORE_FILE='D:/nili/my-git-projects/my-backup/backup-settings/my-android-release.keystore'
export ORG_GRADLE_PROJECT_RELEASE_STORE_PASSWORD='<password>'
export ORG_GRADLE_PROJECT_RELEASE_KEY_ALIAS='pisces312'
export ORG_GRADLE_PROJECT_RELEASE_KEY_PASSWORD='<password>'
./gradlew.bat assembleBasicRelease
```

```powershell
# PowerShell
$env:ORG_GRADLE_PROJECT_RELEASE_STORE_FILE = 'D:\nili\my-git-projects\my-backup\backup-settings\my-android-release.keystore'
$env:ORG_GRADLE_PROJECT_RELEASE_STORE_PASSWORD = '<password>'
$env:ORG_GRADLE_PROJECT_RELEASE_KEY_ALIAS = 'pisces312'
$env:ORG_GRADLE_PROJECT_RELEASE_KEY_PASSWORD = '<password>'
```

- `build.gradle.kts` 仅在 `RELEASE_STORE_FILE` 存在时挂 `signingConfig`；否则 release 产物不签名。
- debug 变体同样只在提供了上述变量时复用 release keystore（保证覆盖安装签名一致）。
- 密码与 keystore 本机路径见用户全局配置（`CLAUDE.md`），**不要复制进本仓库**。

### apksigner 重签（`build.bat` / `build-sm8850.sh` 精简流程）

SM8850 脚本会剥非 V81 `.so` 后 **zipalign + apksigner 重签**，使用另一组变量：

```bash
export KEY_STORE='D:/nili/my-git-projects/my-backup/backup-settings/my-android-release.keystore'
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
