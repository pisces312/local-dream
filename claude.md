# Local Dream - Project Implementation Notes

## Overview

Local Dream 是一个 Android 应用，允许在本地设备上运行 Stable Diffusion 模型（SD1.5 和 SDXL），完全离线运行，无需联网。应用通过 Native C++ 后端进程实现推理任务，并通过 HTTP 协议与前端的 Kotlin/Compose UI 进行通信。

---

## Architecture Overview

### High-Level Architecture

```
+-----------------------------------+
|       Android App (Kotlin)        |
|  - Jetpack Compose UI            |
|  - Model Management (Kotlin)     |
|  - BackendService (Manage Process)|
+-----------------------------------+
                   | HTTP (localhost:8081)
                   v
+-----------------------------------+
|  Native Backend (C++)             |
|  - HTTP Server (cpp-httplib)     |
|  - Pipeline (Inference Logic)    |
|  - MNN / QNN Runtime             |
+-----------------------------------+
                   |
                   v
+-----------------------------------+
|  Model Files (.mnn / .bin / etc) |
+-----------------------------------+
```

### Key Components

1.  **`BackendService.kt`** - Android Service，负责管理本地 C++ 推理进程的生命周期。
2.  **`Model.kt` / `ModelRepository.kt`** - 数据处理层，管理模型文件、配置和元数据。
3.  **`stable_diffusion_core` (C++ binary)** - 独立的推理进程，通过 localhost HTTP 提供服务。
4.  **`Pipeline.hpp` / `PipelineSd15Cpu.hpp` / `PipelineSd15Npu.hpp` / `PipelineSdxl.hpp`** - 推理管线，封装不同后端实现。
5.  **`main.cpp`** - C++ 后端入口，包含 HTTP 服务器和端点注册。

---

## Detailed Components

### 1. BackendService (Android Service)

**File:** `app/src/main/java/io/github/xororz/localdream/service/BackendService.kt`

**Responsibilities:**
-   启动/停止本地推理进程 (`libstable_diffusion_core.so` via `ProcessBuilder`)。
-   通过 HTTP 向推理进程发送生成请求 (`/generate`, `/upscale`, `/tokenize`)。
-   管理进程生命周期，避免频繁重启（grace period 1.5 秒）。
-   处理进程崩溃和超时回调 (`onTimeout` → 判定为崩溃并重启)。

**Key Implementation Details:**
-   使用 `Service.START_NOT_STICKY` 避免系统自动重启服务。
-   使用 `desired` (所需配置) 和 `serving` (当前运行配置) 避免同一配置的重复重启。
-   `stopping` volatile 变量防止在宽限期内意外退出被误报为崩溃。
-   `prepareRuntimeDir()` 在启动时将 `assets/qnnlibs/` 复制到 `filesDir/runtime_libs/`（QNN 需要）。
-   命令行构造示例:
    ```
    --type sd15npu --model_dir /data/user/0/.../models/model_id --lib_dir /data/user/0/.../runtime_libs [--patch resolution.patch] [--use_v_pred] [--safety_checker safety_checker.mnn] [--lowram]
    ```
-   对于 Mali GPU 设备，会动态添加 `libOpenCL.so` 路径到 `LD_LIBRARY_PATH`。

### 2. Model Data Layer

**Key Files:**
-   `app/src/main/java/io/github/xororz/localdream/data/Model.kt`
-   `app/src/main/java/io/github/xororz/localdream/data/ModelRepository.kt`
-   `app/src/main/java/io/github/xororz/localdream/data/UpscalerRepository.kt`

**Model Identification:**
-   Model ID 由目录名决定 (`Model.dir.name`)。
-   Model 类型由文件标记判断:
    -   `finished` → 自定义 SD1.5 CPU 模型
    -   `npucustom` → 自定义 SD1.5 NPU 模型
    -   `SDXL` → SDXL 模型
    -   内置模型 ID 列表:
        -   SD1.5 CPU (5): `base`, `characters`, `realistic`, `anime`, `2.5d`
        -   SD1.5 NPU (5): `npu_base`, `npu_characters`, `npu_realistic`, `npu_anime`, `npu_2.5d`
        -   SDXL (4): `sdxl_base`, `sdxl_vpred`, `sdxl_dmd2`, `sdxl_lightning`

**Model Directory Structure:**
```
/models/<model_id>/
├── model.safetensors   (原始模型，仅用于 convert 模式)
├── tokenizer.json      (CLIP tokenizer)
├── token_emb.bin       (预计算的 token embeddings)
├── pos_emb.bin         (预计算的 position embeddings)
├── clip_v2.mnn         (SD1.5 CLIP, MNN format)
├── clip.mnn            (SDXL CLIP-L, MNN format)
├── clip_2.mnn          (SDXL CLIP-G, MNN format)
├── unet.mnn            (SD1.5 CPU UNet, MNN format)
├── unet.bin            (SD1.5 NPU / SDXL UNet, QNN format)
├── vae_encoder.mnn     (SD1.5 CPU VAE Encoder, MNN format)
├── vae_encoder.bin     (SD1.5 NPU / SDXL VAE Encoder, QNN format)
├── vae_decoder.mnn     (SD1.5 CPU VAE Decoder, MNN format)
├── vae_decoder.bin     (SD1.5 NPU / SDXL VAE Decoder, QNN format)
├── v3                  (标记文件，指示为 NPU 模型)
├── config.json         (可选，模型默认生成参数)
└── *.patch             (可选，NPU 模型的非 512x512 分辨率补丁)
```

**Genertion Defaults Resolution (`Model.kt`):**
1.  Code-level 默认值 (全局 `GenerationDefaults.GLOBAL`)
2.  `config.json` 中的模型特定默认值 (通过 `ModelConfig` 解析)
3.  用户历史记录中的参数 (`HistoryRepository`)
4.  用户 UI 实时调整

### 3. Native C++ Backend

**Entry Point:** `app/src/main/cpp/src/main.cpp`

**Compilation Unit:** `libstable_diffusion_core.so` (通过 JNI 作为独立进程运行)

**HTTP Endpoints:**
-   `POST /generate` - SSE (Server-Sent Events)，生成图像。
    -   Request body: JSON，包含 `prompt`, `negative_prompt`, `steps`, `cfg`, `scheduler`, `seed`, `width`, `height`, `img2img`, `mask`, `ultrafix` 等。
    -   Response: `text/event-stream`，事件类型: `progress` (步进) 和 `complete` (完成)。
-   `POST /upscale` - 二进制协议，放大图像。
    -   Headers: `X-Image-Width`, `X-Image-Height`, `X-Upscaler-Path`, `X-Use-OpenCL` (可选)。
    -   Request body: 原始 RGB 字节流。
    -   Response: JPEG 字节流，headers 包含输出尺寸和耗时。
-   `POST /tokenize` - 返回 prompt 的 token 数量 (用于提示词长度预警)。
-   `GET /health` - 健康检查。

**Build Flavors & .so Packaging:**
-   `basic` - 不含安全过滤器 (官方 APK: `LocalDream_armv8a_2.7.0.apk`，约 56.5 MB)
-   `filter` - 含安全过滤器 (需额外 `safety_checker.mnn`)

**已提取的预编译库 (手动添加到 fork 以便构建):**
-   `app/src/main/jniLibs/arm64-v8a/libstable_diffusion_core.so` (从官方 APK 提取)
-   `app/src/main/assets/qnnlibs/*.so` (20 个 QNN 运行时库，从官方 APK 提取)

### 4. Inference Pipeline (C++)

**Base Class:** `Pipeline.hpp` - 定义推理算法的通用流程。

**Subclasses:**
1.  **`PipelineSd15Cpu`** - 全 MNN (CPU/OpenCL) 后端。
    -   所有阶段 (CLIP, UNet, VAE) 使用 MNN 格式模型。
    -   每次推理重新创建 MNN 会话 (无持久会话)。
    -   不支持 `canSkipUncond()` (MNN 一次执行两个 batch)。
    -   不支持预览 (无持久 UNet 会话)。
    -   不支持 VAE tiling (MNN 原生支持大分辨率)。

2.  **`PipelineSd15Npu`** - QNN (NPU) 后端，用于 SD1.5。
    -   CLIP 使用 MNN (CPU)，UNet 和 VAE 使用 QNN (NPU)。
    -   支持 zstd 分辨率补丁 (`--patch`)。
    -   支持 VAE tiling (输出 > 512px 时)。
    -   支持 `canSkipUncond()` (QNN 分开执行两个 batch，cfg=1 时可跳过 negative)。

3.  **`PipelineSdxl`** - QNN (NPU) 后端，用于 SDXL。
    -   双 CLIP (CLIP-L + CLIP-G)，均使用 MNN。
    -   UNet 和 VAE 使用 QNN。
    -   支持 `lowram` 模式 (每阶段加载/释放模型，以时间换空间)。
    -   支持 VAE tiling (仅 ultrafix 时触发，因为标准 SDXL 生成固定 1024x1024)。
    -   支持 SDXL micro-conditioning (`time_ids`)。

**Inference Algorithm (`Pipeline::generate()`):**
1.  **CLIP Encoding** - 将文本 prompt 编码为 hidden states (支持 prompt cache，避免重复推理)。
2.  **Scheduler Initialization** - 根据 `scheduler_type` 创建调度器 (DPM, Euler, LCM 等)。
3.  **Latent Initialization** - 随机噪声或从输入图像 (img2img) 反演。
4.  **Denoising Loop** - 多次 UNet 推理，逐步降噪。
    -   支持 tiled UNet (ultrafix 模式，用于大图)。
    -   支持 mask (inpaint)。
    -   支持 PixelRush 部分反演 (ultrafix 模式，保留输入图像结构)。
5.  **VAE Decode** - 将 latent 解码为像素。
6.  **Post-processing** - 安全检查 (NSFW 过滤)，aspect ratio 裁剪 (SDXL)。

**Key C++ Libraries:**
-   **MNN** - 小米开源的轻量级神经网络引擎，用于 CPU 推理和 CLIP 编码。
-   **QNN** - 高通 NPU 推理框架 (闭源)，用于 NPU 推理。
-   **cpp-httplib** - 轻量级 HTTP 服务器库。
-   **nlohmann/json** - JSON 解析库。
-   **xtensor** - C++ 多维数组库 (类似 NumPy)。

---

## Build & Environment

### Android Project Build

**Gradle Version:** 9.4.1 (与 streamclip 项目对齐)
**AGP Version:** 9.1.1
**Compile SDK:** 37
**Min SDK:** 28
**Target SDK:** 36
**Kotlin Version:** 由 `libs.versions.toml` 管理

**Key Gradle Configuration (`app/build.gradle.kts`):**
-   `namespace = "io.github.xororz.localdream"`
-   `abiFilters += "arm64-v8a"` (仅支持 ARM64)
-   `useLegacyPackaging = true` (jniLibs 打包进 APK)
-   `isMinifyEnabled = true` (release 构建启用代码压缩)
-   `signingConfigs` - 需要 `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` (通过 `gradle.properties` 或环境变量传入)

**Signing Config (for release builds):**

`app/build.gradle.kts` 使用**条件签名**配置：
- 如果 Gradle 属性 `RELEASE_STORE_FILE` 存在，Gradle 自动签名
- 否则 Gradle 输出 **unsigned APK**，由构建脚本调用 `apksigner` 签名（推荐方式）

**方式 A：Gradle 自动签名（需 gradle.properties 或环境变量）**
```properties
# gradle.properties (or -P flags)
RELEASE_STORE_FILE=keystore.jks
RELEASE_STORE_PASSWORD=password
RELEASE_KEY_ALIAS=alias
RELEASE_KEY_PASSWORD=password
```

**方式 B：构建脚本签名（推荐，环境变量驱动）**
```bash
# Windows Git Bash / WSL / Linux
export KEY_STORE=/path/to/keystore.jks
export KEY_STORE_PASSWORD=password
export KEY_ALIAS=alias        # optional, default: pisces312

./build-local.sh release basic
```

环境变量签名不将密钥信息写入任何文件，符合安全最佳实践。`build-local.sh` 在 Windows 下自动处理路径转换（`cygpath`），确保 `zipalign` 和 `apksigner` 正确工作。

**Build Commands:**
```bash
# Debug build (no signing required)
./gradlew assembleBasicDebug

# Release build via script (recommended, handles signing + path conversion on Windows)
./build-local.sh release basic

# Release build, filter flavor (with safety checker)
./build-local.sh release filter

# Install debug build to connected device
./build-local.sh debug basic -d 192.168.1.5:5555

# Rebuild C++ backend (requires QNN SDK + NDK, then build APK)
./build-local.sh release --rebuild-native
```

**Output APK:**
-   Debug: `LocalDream_armv8a_2.7.0-basic-debug.apk` (约 133 MB)
-   Release (signed): `LocalDream_armv8a_2.7.0-basic-signed.apk` (约 55 MB, 含 R8 压缩)
-   Release (unsigned): `LocalDream_armv8a_2.7.0-basic-unsigned.apk`

### C++ Backend Build (libstable_diffusion_core.so)

**IMPORTANT:** 官方构建的 `libstable_diffusion_core.so` 是从官方 Release APK 提取的，因为构建需要 **QNN SDK** (高通闭源 NPU 推理框架)，一般开发环境没有。

**If you need to rebuild the C++ backend:**
1.  Download QNN SDK from Qualcomm Developer Portal.
2.  Set `ANDROID_NDK_ROOT` and QNN environment variables.
3.  Create `CMakeLists.txt` or `Android.mk` (目前项目 `cpp/` 目录仅含源代码，构建脚本需自行编写或从官方提取构建环境)。
4.  Build with `ndk-build` or CMake.

**Current workaround (for fork maintainers):**
-   从官方 GitHub Release (https://github.com/xororz/local-dream/releases) 下载预编译 APK。
-   使用 `apktool` 或 `unzip` 提取 `lib/arm64-v8a/libstable_diffusion_core.so` 和 `assets/qnnlibs/*.so`。
-   放入 fork 仓库的 `app/src/main/jniLibs/arm64-v8a/` 和 `app/src/main/assets/qnnlibs/`。

---

## Model Format & Conversion

### Convert Mode (C++ Backend)

`stable_diffusion_core --convert <model_dir> [--clip_skip_2]`

-   读取 `<model_dir>/model.safetensors` 和可选的 `lora.N.safetensors`。
-   将模型权重转换为 MNN 格式 (生成 `clip_v2.mnn`, `unet.mnn`, `vae_encoder.mnn`, `vae_decoder.mnn` 等)。
-   Android 应用本身 **不执行转换**，转换需在 PC 上完成，然后将转换后的文件传入设备。

### Model Installation on Device

1.  **Built-in models** - 随 APK 打包 (通过 `assets/models/` 在首次启动时复制到 `filesDir/models/`)。
2.  **Custom models** - 用户通过 UI 下载 (HTTP URL) 或导入 (ZIP 文件)。
    -   Download: `ModelDownloadService` 通过 OkHttp 下载 ZIP，解压到 `filesDir/models/<model_id>/`。
    -   NPU models: 需在解压后创建 `v3` 标记文件。

---

## Device Compatibility & Chipset Support

### Supported Chipsets

**SDXL Capable SoCs (from `Model.kt`):**
-   SM8750, SM8750P, SM8850, SM8850P, SM8845, SM8650

**SD1.5 NPU Models:**
-   需匹配具体的芯片命名后缀 (从 `chipsetModelSuffixes` 映射获取)。
-   例如: SM8550 → `8gen2`, SM8450 → `8gen1`, etc.

### Known Limitations

1.  **Honor Magic8 (SM8850)** - NPU 库被 MagicOS 裁剪，无法使用 NPU 模型，CPU 模型因缺少 `libstable_diffusion_core.so` 也无法运行 (需从官方 APK 提取)。
2.  **Mali GPU devices** - 不支持 NPU 模型，但可以使用 CPU (MNN) 模型，并通过 `LD_LIBRARY_PATH` 添加 Mali OpenCL 库路径以加速。
3.  **32-bit devices** - 不支持 (minSdk=28, abiFilter=arm64-v8a)。

---

## Key Design Decisions & Observations

1.  **Process Isolation** - 推理进程是独立进程，而非 Android Service 直接链接 .so。这允许推理崩溃不影响 UI 进程，且方便单进程单模型的生命周期管理。
2.  **QNN vs MNN** - QNN 用于 NPU 加速，但依赖厂商提供的闭源库，且 high-level API 可能随系统更新变化。MNN 用于 CPU 回退和 CLIP 编码，开源且稳定。
3.  **Prompt Cache** - CLIP 编码结果按 prompt 文本缓存到磁盘 (`filesDir/models/<model_id>/cache/`)，避免重复编码相同 prompt。
4.  **Tiling Strategy** - 大图生成通过 tiling 实现：VAE 和 UNet 都支持 tile 处理，以避免 QNN 固定输入尺寸的限制。
5.  **Ultrafix (PixelRush)** - 一种 tiled img2img 策略，通过 DDIM 反演保留输入图像结构，然后通过 tiled denoising 添加细节，适用于大尺寸图像 (如 4K 放大)。
6.  **Safety Checker** - 可选，通过 `--safety_checker` 传入 MNN 模型，对生成图像进行 NSFW 检测 (仅 `filter` build variant 默认包含)。
7.  **Privacy** - 官方版本有 `reportImage()` 功能 (上报生成图像和 prompt 到 `report.chino.icu`)，但在 fork 中已被禁用 (函数体为空操作)。

---

## Development Guidelines

### Code Style
-   Kotlin: 遵循 Android Kotlin Style Guide，使用 Jetpack Compose 的最佳实践。
-   C++: 使用 C++17，遵循项目现有的命名约定 (驼峰 + 下划线混合)。

### Key Files to Modify
-   **UI changes:** `app/src/main/java/io/github/xororz/localdream/ui/screens/`
-   **Model management:** `app/src/main/java/io/github/xororz/localdream/data/Model.kt`
-   **Backend communication:** `app/src/main/java/io/github/xororz/localdream/service/BackendService.kt`
-   **Inference algorithm:** `app/src/main/cpp/src/Pipeline.hpp` (通用流程) 和子类。

### Adding a New Model Type
1.  在 `Model.kt` 中添加 ID 和类型标记。
2.  在 `ModelRepository.kt` 中添加模型扫描逻辑。
3.  如果需要新的后端类型，在 `main.cpp` 中添加新的 `ModelType` 和 `Pipeline` 子类。
4.  更新 UI 以显示新模型类型。

### Debugging Tips
-   **Backend logs:** 推理进程的 stdout/stderr 会输出到 logcat (通过 `ProcessBuilder.redirectErrorStream(true)`)。
-   **Backend crash:** `BackendService.onTimeout()` 会被调用，并在 logcat 中输出 `"Backend serving <config> timed out"`。
-   **Model file missing:** 后端会输出 `"File not found: ..."` 并以 exit code 1 退出。

---

## Appendix: AAB & Distribution

-   项目使用 Android App Bundle (AAB) 分发到 Google Play。
-   `build.gradle.kts` 中启用了 density 和 abi splits (`bundle { density { enableSplit = true } abi { enableSplit = true } }`)。
-   由于仅支持 `arm64-v8a`，AAB 只会生成一个 APK (不含 x86 等)。
-   F-Droid 构建可能需要禁用 `useLegacyPackaging` (jniLibs 提取方式不同)。

---

## Appendix: Project File Structure

```
local-dream/
├── app/
│   ├── src/main/
│   │   ├── cpp/src/              # C++ 推理后端源代码
│   │   ├── java/.../localdream/
│   │   │   ├── data/             # 模型和数据层
│   │   │   ├── navigation/       # Compose Navigation
│   │   │   ├── service/         # Android Services (BackendService, ModelDownloadService)
│   │   │   ├── ui/screens/      # Compose UI 界面
│   │   │   └── utils/           # HTTP 客户端, 图像工具等
│   │   ├── jniLibs/arm64-v8a/  # 预编译的 libstable_diffusion_core.so (从官方 APK 提取)
│   │   └── assets/qnnlibs/      # QNN 运行时库 (从官方 APK 提取)
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── build.gradle.kts
├── gradle.properties
├── settings.gradle.kts
└── README.md
```

---

**Last updated:** 2026-06-18
**Fork maintainer:** pisces312 (https://github.com/pisces312/local-dream)
**Upstream:** xororz (https://github.com/xororz/local-dream)
