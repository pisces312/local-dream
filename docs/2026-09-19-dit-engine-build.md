# DiT 引擎（libdit_engine.so）构建说明

> 用途：记录 2026-09-19 修复「Z-Image Turbo 后端启动失败」的全过程，以及**如何从官方 APK 复制**过渡
> 到**完全独立构建** DiT 引擎。本文是后续会话实现独立构建的操作手册。

---

## 1. 背景与现状（混合来源）

v3.0.0-alpha.1 起，z-image / flux2-klein 等走 DiT 后端，依赖 `libdit_engine.so`
（独立 CMake 子项目 `app/src/main/cpp/dit/`，需 Hexagon SDK）。fork 的构建脚本
（`build.sh` / `build-sm8850.sh` / `rebuild-native.bat`）只构建**核心** so
（`libstable_diffusion_core.so`），从没构建过 DiT 引擎，导致我们打的包缺件。

当前手机上正常使用的 APK `LocalDream_armv8a_3.0.0-alpha.1-basic-sm8850-debug.apk`
（116MB）是**混合来源**：

| 产物 | 来源 | 说明 |
|---|---|---|
| `lib/arm64-v8a/libstable_diffusion_core.so` (11M) | **本机构建** | 主 CMakeLists，源码来自上游 v3.0.0，用本机 NDK r28 + QAIRT 2.50.0.260828 编译 |
| `assets/qnnlibs/*`（5 个 so：Htp / HtpV81 / HtpV81Skel / HtpV81Stub / System） | QAIRT 2.50 SDK 放置 | 我们下载的 SDK 运行时库（非上游编译、非我们编译） |
| `lib/arm64-v8a/libdit_engine.so` (55M) | **来自官方 APK** | xororz/local-dream v3.0.0-alpha.1 预构建（见第 3 节） |
| `assets/ditlibs/libggml-htp-v79.so` / `v81.so` | **来自官方 APK** | DiT 引擎的 HTP skel，同上 |
| `libandroidx.graphics.path.so` / `libdatastore_shared_counter.so` | AGP 自动打入 | Compose / Jetpack 原生依赖 |

ABI 契约：`app/src/main/cpp/include/DitEngine.h` 里 `DIT_ENGINE_ABI_VERSION=1`，
与官方 v3.0.0 同源一致；`BackendService` 的 skel 复制逻辑（`prepareRuntimeDir()`）
已就绪，只差把 so + skel 打进包。

---

## 2. 诊断过程（为什么缺）

启动链路：`zimage` → `BackendService.isDitBackend()` 返回 true →
`DitEngine.isInstalled()` 检查 `lib/arm64-v8a/libdit_engine.so` 是否存在 →
不存在 → `updateState(Error("DiT engine not installed"))` → UI 显示「后端启动失败」。

三重证据：
1. 设备实测：`dumpsys package` 的 `legacyNativeLibraryDir` 仅 3 个 so，无 `libdit_engine.so`。
2. 本地工程：`app/src/main/jniLibs/arm64-v8a/` 无该 so，`app/src/main/assets/` 无 `ditlibs/`。
3. 上游官方 APK `v3.0.0-alpha.1` 含 `libdit_engine.so`（55MB，aarch64 / API28 / NDK r28）
   + `assets/ditlibs/libggml-htp-v79.so` / `v81.so`。

设备 `ro.soc.model=SM8850`，满足 DiT 要求的 SM8750+，**机型不是问题**。

> 注：实时 `logcat` 行没抓到（缓冲区被系统/厂商噪声冲掉），改用「导出全量 buffer +
> 设备 nativeLibraryDir 实测 + 官方 APK 二进制对比」定位。

---

## 3. 临时方案：从官方 APK 提取（已落地，2026-09-19）

官方 APK 已下载：`E:\downloads\LocalDream_armv8a_3.0.0-alpha.1.apk`（87MB）。

提取步骤：
```bash
# 从官方 APK 解出 DiT 引擎产物
mkdir -p /tmp/official_apk
unzip -o -q "E:/downloads/LocalDream_armv8a_3.0.0-alpha.1.apk" \
  "lib/arm64-v8a/libdit_engine.so" "assets/ditlibs/*" -d /tmp/official_apk

# 落到工程（与 jniLibs / assets 约定一致）
cp /tmp/official_apk/lib/arm64-v8a/libdit_engine.so \
   app/src/main/jniLibs/arm64-v8a/libdit_engine.so
mkdir -p app/src/main/assets/ditlibs
cp /tmp/official_apk/assets/ditlibs/*.so app/src/main/assets/ditlibs/
```

配套改动：
- 删 `.gitignore` 第 29 行 `app/src/main/assets/ditlibs`（与 `qnnlibs` 一致，保证可复现入库）。
- `bash build-sm8850.sh debug basic` → BUILD SUCCESSFUL，产物 116MB。
  脚本只 strip `assets/qnnlibs/` 非 V81 项，不动 `ditlibs/`，两个 skel 都保留。
- 已 `adb install -r` 装到手机，设备 `lib/arm64/` 含 `libdit_engine.so`(53M)，z-image 正常。

这是**权宜之计**：直接用上游预构建二进制，未经验证是否与我们本机工具链完全一致。
建议后续用第 4 节的独立构建替换，并保留此二进制作对照。

---

## 4. 完全独立构建（后续会话目标）

目标：不再依赖官方 APK，自己用源码 + Hexagon SDK + NDK 29 编译
`libdit_engine.so` 与 `ditlibs` skel。

### 4.1 为什么必须在 Linux 环境跑

- `dit/build.sh` 调用 Hexagon SDK 的 **FastRPC IDL 编译器 `qaic`**（DSP 侧 skel 交叉编译），
  官方只提供 **Linux AMD64** 版（`hexagon-sdk-v6.6.0.0-amd64-lnx.tar.xz`）。
- 纯 Windows / Git Bash(MSYS) 无法直接用该 SDK。
- 本机已确认：**有 WSL2 Ubuntu**（默认发行版，Stopped 状态）；**无 Docker Desktop**；
  Git Bash 下的 ninja 在 conda 里但只对 MSYS 有效。

→ **确定路径：在 WSL2 Ubuntu 中构建**，产物写回 Windows 仓库目录。

### 4.2 本机前置条件核对（2026-09-19 实测）

| 条件 | 状态 | 路径 / 版本 |
|---|---|---|
| NDK 29.0.14206865（Linux 版，WSL 用） | ✅ 已安装 | `D:\dev\android-ndk-r29-linux.zip`（747MiB）→ WSL 内解压至 `~/android-ndk/android-ndk-r29`（`source.properties` 确认 `Pkg.Revision=29.0.14206865`）。**注意：Windows 版 NDK（`D:\dev\android_sdk\ndk\29.0.14206865`）toolchain 只有 `windows-x86_64` 的 .exe，WSL 跑不了，必须用 Linux 版** |
| Hexagon SDK 6.6.0.0 | ✅ 已解压（早于本文档） | **WSL 内 `/home/pisces312/hexagon-sdk-v6.6.0.0`**（MNN QNN 研究时已解压，`tools/HEXAGON_Tools/19.0.07` + `ipc/fastrpc/qaic/bin/qaic` 完整）。tar.xz 仍在 `D:\dev\hexagon-sdk-v6.6.0.0-amd64-lnx.tar.xz` |
| stable-diffusion.cpp 子模块 | ✅ 已 init（含嵌套） | commit `6fe523f`（分支 `qualcomm-showcase-assets`）。**注意：必须 `--recursive`**——其内部 `ggml`、`thirdparty/libwebm`、`thirdparty/libwebp`、`examples/server/frontend` 是嵌套 submodule，漏 init 会在 CMake configure 报 "ggml does not contain a CMakeLists.txt" |
| WSL2 Ubuntu | ✅ 已装 | 默认发行版，`wsl -d Ubuntu` 启动 |
| ninja / cmake（WSL 内） | ✅ 已有 | cmake 3.28.3、ninja 1.11.1（系统 apt 已装，无需再装） |

> `dit/build.sh` 头部注释要求的精确版本：`HEXAGON_SDK_ROOT=.../hexagon/6.6.0.0`、
> `ANDROID_NDK_ROOT=.../ndk/29.0.14206865`。本机两者版本均匹配。

### 4.3 构建步骤（在 WSL2 Ubuntu 内执行）

```bash
# 0. 启动 WSL
wsl -d Ubuntu

# 1. 安装构建工具（首次）
sudo apt update && sudo apt install -y ninja-build cmake

# 2. 解压 Hexagon SDK 到 WSL 本地（避免 /mnt 路径/性能问题）
mkdir -p ~/hexagon && tar -xJf /mnt/d/dev/hexagon-sdk-v6.6.0.0-amd64-lnx.tar.xz -C ~/hexagon
# 本机实际安装在 ~/hexagon-sdk-v6.6.0.0（顶层即 SDK 根，含 hexagon_sdk.json）
export HEXAGON_SDK_ROOT="$HOME/hexagon-sdk-v6.6.0.0"

# 3. NDK 29：建议也复制到 WSL 本地（/mnt/c 长路径偶尔会触发 cmake 问题）
#    若先试 /mnt/c 路径失败再复制。复制示例：
#    mkdir -p ~/android-ndk && cp -r /mnt/c/Users/nili6/AppData/Local/Android/Sdk/ndk/29.0.14206865 ~/android-ndk/
export ANDROID_NDK_ROOT="$HOME/android-ndk/29.0.14206865"
#    或先用 Windows 路径：
#    export ANDROID_NDK_ROOT="/mnt/c/Users/nili6/AppData/Local/Android/Sdk/ndk/29.0.14206865"

# 4. 确认子模块已就位（Windows 侧已 init；WSL 内若为空则补 init）
cd /mnt/d/3rd-party-projects/local-dream
git submodule status app/src/main/cpp/3rdparty/stable-diffusion.cpp
# 若显示前面带 '-'（未 init），在 Windows 侧执行：
#   git submodule update --init --depth 1 app/src/main/cpp/3rdparty/stable-diffusion.cpp

# 5. 跑 DiT 独立构建（自动 cp 产物到 jniLibs 和 assets/ditlibs）
cd /mnt/d/3rd-party-projects/local-dream
HEXAGON_SDK_ROOT="$HEXAGON_SDK_ROOT" \
ANDROID_NDK_ROOT="$ANDROID_NDK_ROOT" \
bash app/src/main/cpp/dit/build.sh
```

`dit/build.sh` 行为（已读源码确认）：
- 读 `DIT_ENGINE_ABI_VERSION`（=1）并据此编译；若主干 `DitEngine.h` 改了版本号需同步。
- `cmake -B build/android -G Ninja`，`ANDROID_ABI=arm64-v8a`，`ANDROID_PLATFORM=android-28`，
  `-march=armv8.7a+fp16+dotprod+i8mm`（armv8.7a 需 NDK 29 的 clang，NDK r28 不够 → 这也是必须 29 的原因）。
- 自动 `cp build/android/lib/arm64-v8a/libdit_engine.so` → `app/src/main/jniLibs/arm64-v8a/`
- 自动 `rm -f assets/ditlibs/libggml-htp-v73.so libggml-htp-v75.so`，
  再 `cp build/android/sdcpp/ggml/src/ggml-hexagon/libggml-htp-v79.so` + `v81.so` → `app/src/main/assets/ditlibs/`
  （skel 是 DSP ELF32，由 FastRPC 交给 Hexagon DSP 执行，不进 nativeLibraryDir）。

### 4.4 产物校验与对照

构建后，在 Windows 侧：
```bash
# 与官方提取的二进制对照（建议保留第 3 节的官方 so 作 baseline）
ls -lh app/src/main/jniLibs/arm64-v8a/libdit_engine.so app/src/main/assets/ditlibs/
file app/src/main/jniLibs/arm64-v8a/libdit_engine.so
# 期望：aarch64 / API28 / NDK r28 风格剥离符号的共享库，大小与官方 ~55MB 量级接近
```

- ABI 版本：确认 `DIT_ENGINE_ABI_VERSION` 与官方一致（=1），否则运行时 `DitEngine`
  会拒绝加载。
- 若自构建产物与官方二进制 `file` 输出、大小、z-image 实机启动行为一致，即可**移除
  官方 APK 提取的 so**，只保留自构建产物，达成完全独立构建。

### 4.5 重新打包与验证

```bash
# Windows 侧（Git Bash）
cd /mnt/d/3rd-party-projects/local-dream   # 实际在 Windows 用 D:\ 路径
bash build-sm8850.sh debug basic
```
- 校验 APK 内 `lib/arm64-v8a/libdit_engine.so` + `assets/ditlibs/*` 已更新。
- `adb install -r` 装手机，进 Z-Image Turbo 实机跑一次，确认后端启动正常
  （抓 `logcat` 看 `BackendService` / `DitEngine` 行，或确认不再弹「后端启动失败」）。

---

## 5. 风险与注意

- **Hexagon SDK 是 Linux 版**：Windows 原生无法跑 `qaic`，必须用 WSL2（或 Docker
  镜像 `ghcr.io/snapdragon-toolchain/arm64-android`，其 `/opt/hexagon` 内含 SDK；本机无 Docker）。
- **NDK 版本**：DiT 必须 NDK 29（`-march=armv8.7a`）；核心 so 用 NDK r28。两者不同，不要混用。
- **armv8.7a**：DiT 引擎专用于 SM8750+ 设备，核心 so 覆盖更低端设备；二者只在 DitEngine ABI 层交汇。
- **skel 架构**：v79/v81 覆盖 SM8750 及更新的机型；更新设备支持时需同步 `dit/build.sh` 的
  `foreach(htp_version v79 v81)` 列表与 CMakeLists。
- **提交约定**：按 fork 约定，构建产物（so / ditlibs）落入 `jniLibs` / `assets` 后是否入库、
  是否 commit，均需用户确认（本次仅做构建与安装，未 commit）。
- **回退**：若自构建失败，第 3 节的官方 APK 提取方案可随时恢复；
  旧 QAIRT 2.39 目录与 Hexagon tar.xz 都保留，不强制清理。

---

## 6. 后续会话 TODO

- [x] 启动 WSL2 Ubuntu，解压 Hexagon SDK 6.6.0.0 到本地 → **无需解压，复用 MNN 研究已有的 `~/hexagon-sdk-v6.6.0.0`**（2026-09-19）
- [x] WSL 内装 ninja-build + cmake → 系统已有 cmake 3.28.3 / ninja 1.11.1（2026-09-19）
- [x] 确认 NDK 29 路径 → Windows 版 NDK 无法用于 WSL（.exe toolchain）；已下载 Linux 版 `android-ndk-r29-linux.zip` 解压到 `~/android-ndk/android-ndk-r29`（2026-09-19）
- [x] `bash app/src/main/cpp/dit/build.sh` 独立构建 → BUILD 成功（1m23s，WSL2 内，`HEXAGON_SDK_ROOT=~/hexagon-sdk-v6.6.0.0 ANDROID_NDK_ROOT=~/android-ndk/android-ndk-r29`），产物自动落入 jniLibs + assets/ditlibs（2026-09-19）
- [x] 对照官方二进制 → 自构建 55,548,384 B vs 官方 55,716,088 B（差 0.3%）；`file` 确认 aarch64 / Android 28 / NDK r29 / stripped；skel 为 ELF32 QUALCOMM DSP6（2026-09-19）
- [x] `build-sm8850.sh debug basic` 重新打包 → BUILD SUCCESSFUL，116MB，APK 内含自构建 libdit_engine.so + 两个 skel（2026-09-19）
- [x] `adb install -r` 成功；设备 native 目录 4 个 so（含 libdit_engine.so）确认到位（2026-09-19）
- [x] **实机验证 Z-Image Turbo**（2026-09-19 19:10 前后，用户实机生成图片成功 → 自构建引擎工作正常，完全独立构建达成）
- [x] 官方 APK 提取的 baseline so 已被自构建产物覆盖（官方 APK 仍保留在 `E:\downloads\LocalDream_armv8a_3.0.0-alpha.1.apk`，需要时可随时再提取对照）
- [ ] 按约定与用户确认后再 commit

## 7. 本次构建实测命令（2026-09-19，可复现）

```bash
# Windows Git Bash 侧：下载 Linux 版 NDK（aria2，Windows 风格路径）
aria2c --dir=D:/dev --out=android-ndk-r29-linux.zip \
  "https://dl.google.com/android/repository/android-ndk-r29-linux.zip"

# 补齐嵌套子模块（Windows 侧执行）
git submodule update --init --recursive --depth 1 \
  app/src/main/cpp/3rdparty/stable-diffusion.cpp

# WSL2 内构建
wsl -d Ubuntu -- bash -lc 'cd /mnt/d/3rd-party-projects/local-dream && \
  HEXAGON_SDK_ROOT=$HOME/hexagon-sdk-v6.6.0.0 \
  ANDROID_NDK_ROOT=$HOME/android-ndk/android-ndk-r29 \
  bash app/src/main/cpp/dit/build.sh'

# Windows 侧重新打包
bash build-sm8850.sh debug basic
```

### 踩坑记录（2026-09-19 实测）

1. **Windows 版 NDK 不可用于 WSL**：`D:\dev\android_sdk\ndk\29.0.14206865` 的
   `toolchains/llvm/prebuilt/` 只有 `windows-x86_64`（clang 是 .exe），WSL Linux 无法执行，
   即使复制到 WSL 本地也没用。必须下载 Linux 版 zip（747MiB）。
2. **嵌套子模块**：stable-diffusion.cpp 自身有 4 个嵌套 submodule（`ggml`、
   `thirdparty/libwebm`、`thirdparty/libwebp`、`examples/server/frontend`）。
   只 init 顶层时 CMake configure 报：
   `The source directory .../stable-diffusion.cpp/ggml does not contain a CMakeLists.txt file.`
   → `git submodule update --init --recursive --depth 1` 解决。
3. **Hexagon SDK 无需重新解压**：MNN QNN 研究时已解压在
   `/home/pisces312/hexagon-sdk-v6.6.0.0`，直接复用；`HEXAGON_SDK_ROOT` 指向该目录即可
   （不是文档早先写的 `~/hexagon/hexagon/6.6.0.0` 层级结构）。
4. **WSL 的 cmake/ninja 已装**：cmake 3.28.3 / ninja 1.11.1（系统 apt），文档早先
   "需在 WSL 内安装" 的记载过时。
5. 构建耗时 **1m23s**（104 个 ninja target，WSL2 → /mnt/d 交叉文件系统）；编译警告均为
   上游源码的 deprecation/override 类噪声，无错误。
6. 验证结论：自构建 `libdit_engine.so` 55,548,384 B vs 官方 55,716,088 B（差 0.3%），
   `file` 输出 aarch64 / Android 28 / NDK r29 / stripped，实机 Z-Image Turbo 生成成功。

7. **（2026-09-20 补记）Windows 侧构建不要走 PowerShell 工具，用 Bash 直调 exe。**

   本次重建 v3.0.0-alpha.2 的 so 时，先尝试用 PowerShell 工具跑 cmake configure，**被沙箱拦截**：
   调用后零输出、约 3 秒即报 failed，没有任何诊断信息（`Status: failed` + 空 stdout/stderr），
   极易误判成 cmake 本身出错。另有一个独立限制：**Bash 命令里只要出现 `PowerShell` 字面
   （哪怕只是 `grep PowerShell docs/` 这种关键词检索）就会被安全策略直接拒绝**，报
   `Invoking PowerShell from Bash bypasses PowerShell security checks; use the PowerShell tool instead`。

   **实际可用路径**（本次实测成功）：在 Git Bash 里直接调 Windows 原生 exe，并用
   `MSYS2_ARG_CONV_EXCL` 关掉 MSYS 的参数路径转换：

   ```bash
   cd app/src/main/cpp
   export QAIRT_PATH='D:\dev\qairt\2.50.0.260828'
   export ANDROID_NDK_ROOT='D:/dev/android_sdk/ndk/28.2.13676358'
   export MSYS2_ARG_CONV_EXCL='*'
   CM='D:/dev/android_sdk/cmake/3.22.1/bin/cmake.exe'
   "$CM" --preset android-release \
       -DCMAKE_C_COMPILER_LAUNCHER= -DCMAKE_CXX_COMPILER_LAUNCHER= \
       -DCMAKE_MAKE_PROGRAM=D:/dev/android_sdk/cmake/3.22.1/bin/ninja.exe
   "$CM" --build --preset android-release
   ```

   本次耗时：configure 15s、增量 build 1m8s（只重编 SampleApp 的 2 个文件 + `main.cpp`），
   产物 11,981,560 字节。注意增量构建很快是因为 `build/android/` 缓存还在。

   **为什么 2026-09-19 那次没碰到**：那天的 core 构建走的就是上面这条 Bash 直调 exe 的路径
   （即 `AGENTS.md`「快速构建」里的"或手动"部分，原 `claude.md`，2026-09-20 起更名合并），**全程没有经过 PowerShell 工具**，
   自然不会触发沙箱。本次是**调用路径不同**，不是环境发生了变化。特别提醒：用户环境里
   那条「跑 Windows 程序用 PowerShell 工具原生调用」的经验，是为绕开
   「MSYS 下 `import torch` 必 SIGSEGV」而立的，**不适用于 cmake / ninja 这类普通构建工具** ——
   它们在 Bash 下直调更可靠。

   同类坑还有一条（2026-09-19 已踩，见下）：MSYS 下把 `/tmp/xxx` 交给原生 Windows 程序
   （python / zipalign / apksigner）会被当成 `D:\tmp\xxx`，必须 `cygpath -w` 转换。
   两条根因相同：**Git Bash 与 Windows 原生程序之间的边界**，传参一律用 Windows 风格路径。
