# Local Dream - Architecture Design Document

## 1. System Architecture

### 1.1 Process Architecture

Local Dream 采用双进程架构：

```
┌──────────────────────────────────────────────────┐
│              UI Process (Android App)             │
│                                                   │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────┐ │
│  │ Compose UI  │→│BackendService│→│OkHttp   │ │
│  │ (Screens)   │  │ (Lifecycle)  │  │Client   │ │
│  └─────────────┘  └──────────────┘  └────┬────┘ │
│                                          │       │
└──────────────────────────────────────────┼───────┘
                                           │ HTTP
                                           │ (localhost:8081)
┌──────────────────────────────────────────┼───────┐
│           Backend Process (C++)          │       │
│                                          │       │
│  ┌─────────────┐  ┌──────────────┐  ┌───┴────┐ │
│  │ cpp-httplib │→│  Pipeline    │→│QNN/MNN  │ │
│  │ (HTTP)      │  │  (Inference) │  │Runtime  │ │
│  └─────────────┘  └──────────────┘  └────────┘ │
└──────────────────────────────────────────────────┘
```

**为什么使用独立进程而非 JNI 直接调用？**

1.  **崩溃隔离** - 推理崩溃不影响 UI 进程，用户仍可操作界面。
2.  **内存管理** - 推理进程占用大量内存 (模型加载、推理中间结果)，独立进程允许操作系统在内存不足时更灵活地回收。
3.  **生命周期** - 推理进程可长时间运行，不受 Activity 生命周期影响。
4.  **模型切换** - 切换模型只需重启后端进程，无需重新加载整个应用。

### 1.2 Communication Protocol

UI 与后端之间通过 HTTP 协议通信，后端运行在 `127.0.0.1:8081`。

| Endpoint  | Method | Content-Type         | Direction    | 描述                          |
|-----------|--------|----------------------|--------------|-------------------------------|
| `/generate` | POST   | `text/event-stream`  | UI → Backend | 文生图 / 图生图 / Inpaint   |
| `/upscale` | POST   | Binary (raw RGB ↔ JPEG) | UI → Backend | 图像放大                     |
| `/tokenize` | POST   | `application/json`   | UI → Backend | Token 计数（提示词长度预警）|
| `/health` | GET    | -                    | UI → Backend | 健康检查                      |

---

## 2. Inference Pipeline

### 2.1 Pipeline Class Hierarchy

```
Pipeline (Base Class, Pipeline.hpp)
├── PipelineSd15Cpu (全 MNN, CPU/OpenCL)
├── PipelineQnn (QNN 基类)
│   ├── PipelineSd15Npu (QNN NPU + MNN CLIP)
│   └── PipelineSdxl (QNN NPU + Dual MNN CLIP)
```

### 2.2 Generation Flow

```
用户点击 "Generate"
       │
       ▼
┌──────────────────┐
│  1. CLIP Encoding │  Text → Hidden States (支持 Prompt Cache)
│     (MNN CPU)     │  SD1.5: [2, 77, 768]
└──────────────────┘  SDXL:   [2, 77, 2048] + pooled [2, 1280]
       │
       ▼
┌──────────────────┐
│  2. Scheduler     │  初始化噪声调度表
│     Init          │  (DPM, Euler, LCM, etc.)
└──────────────────┘
       │
       ▼
┌──────────────────┐
│  3. Latent Init   │  txt2img: 随机高斯噪声
│                   │  img2img: VAE Encode + add_noise
│                   │  ultrafix: DDIM Inversion
└──────────────────┘
       │
       ▼
┌──────────────────┐  循环 N 步 (steps)
│  4. Denoising     │  ┌──────────────────────────┐
│     Loop          │  │ a. Scale Model Input      │
│                   │  │ b. Run UNet (QNN or MNN)  │
│                   │  │ c. CFG: uncond + cfg*(txt-uncond) │
│                   │  │ d. Scheduler Step          │
│                   │  │ e. Mask Blend (inpaint)    │
│                   │  │ f. Ultrafix Structure Anchor │
│                   │  └──────────────────────────┘
└──────────────────┘
       │
       ▼
┌──────────────────┐
│  5. VAE Decode    │  Latent → Pixel Image
│                   │  支持 Tiling (大图)
└──────────────────┘
       │
       ▼
┌──────────────────┐
│  6. Post-process  │  Safety Check (可选)
│                   │  Laplacian Blend (inpaint)
│                   │  Aspect Ratio Crop (SDXL)
└──────────────────┘
```

### 2.3 Backend Comparison

| 特性               | PipelineSd15Cpu | PipelineSd15Npu | PipelineSdxl |
|--------------------|-----------------|-----------------|--------------|
| **UNet 后端**      | MNN (CPU/OpenCL)| QNN (NPU)       | QNN (NPU)    |
| **VAE 后端**       | MNN (CPU/OpenCL)| QNN (NPU)       | QNN (NPU)    |
| **CLIP 后端**      | MNN (CPU)       | MNN (CPU)       | MNN (CPU) x2 |
| **默认分辨率**     | 512x512         | 512x512         | 1024x1024    |
| **img2img**        | ✓ (可选)        | ✓ (可选)        | ✓ (可选)     |
| **Preview**        | ✗               | ✓               | ✓ (非 lowram)|
| **VAE Tiling**     | ✗ (MNN 原生)    | ✓ (>512px)      | ✓ (>1024px)  |
| **canSkipUncond**  | ✗               | ✓ (cfg=1)       | ✓ (cfg=1)    |
| **Low RAM Mode**   | ✗               | ✗               | ✓            |
| **Resolution Patch**| ✗              | ✓ (zstd)        | ✗            |
| **Ultrafix**       | ✗               | ✓               | ✓            |
| **SDXL Conditioning**| ✗             | ✗               | ✓ (time_ids) |
| **Model Files Ext** | .mnn           | UNet/VAE: .bin, CLIP: .mnn | UNet/VAE: .bin, CLIP: .mnn |

### 2.4 Scheduler Types

| 名称              | 类                          | 特点                        |
|-------------------|-----------------------------|-----------------------------|
| `dpm`             | DPMSolverMultistepScheduler | 默认，快速收敛              |
| `dpm_karras`      | DPMSolverMultistepScheduler | Karras sigma schedule       |
| `dpm_sde`         | DPMSolverMultistepScheduler | SDE variant, 更细腻         |
| `dpm_sde_karras`  | DPMSolverMultistepScheduler | SDE + Karras                |
| `euler`           | EulerDiscreteScheduler      | 简单，稳定                  |
| `euler_karras`    | EulerDiscreteScheduler      | Euler + Karras              |
| `euler_a`         | EulerAncestralDiscreteScheduler | 祖先采样，更多变化      |
| `euler_a_karras`  | EulerAncestralDiscreteScheduler | 祖先 + Karras           |
| `lcm`             | LCMScheduler                | Latent Consistency Model    |

---

## 3. Model Management

### 3.1 Model Lifecycle

```
┌─────────────┐     ┌──────────────┐     ┌──────────────┐
│  Download   │────→│  Extract     │────→│  Ready       │
│  (ZIP/URL)  │     │  (Unzip)     │     │  (Can Run)   │
└─────────────┘     └──────────────┘     └──────────────┘
                                               │
                                    ┌──────────┼──────────┐
                                    ▼          ▼          ▼
                              ┌──────────┐ ┌────────┐ ┌────────┐
                              │ Generate │ │ Rename │ │ Delete │
                              └──────────┘ └────────┘ └────────┘
```

### 3.2 Built-in Models

| Category      | Model IDs                                                        | File Suffix |
|---------------|------------------------------------------------------------------|-------------|
| SD1.5 CPU     | `base`, `characters`, `realistic`, `anime`, `2.5d`             | N/A         |
| SD1.5 NPU     | `npu_base`, `npu_characters`, `npu_realistic`, `npu_anime`, `npu_2.5d` | N/A |
| SDXL          | `sdxl_base`, `sdxl_vpred`, `sdxl_dmd2`, `sdxl_lightning`       | N/A         |

### 3.3 Chipset to Model Suffix Mapping (`Model.kt`)

| SoC       | Suffix | SoC       | Suffix  |
|-----------|--------|-----------|---------|
| SM8550    | 8gen2  | SM8450    | 8gen1   |
| SM8475    | 8g1p   | SM8435    | 8g1m    |
| SM8475P   | 8g1pp  | SM8350    | 888     |
| SM8550P   | 8g2p   | SM8315    | 888p    |
| SM8635    | 8sg3   | SM8475_Z  | 8g1z    |
| SM8650    | 8g3    | SM7475    | 7g1     |
| *其他 SM* | min    |           |         |

---

## 4. Ultrafix (Tiled Img2Img)

Ultrafix 是一种 PixelRush 风格的 tiled img2img 策略，用于对大尺寸图像进行细节增强。

### 4.1 Workflow

```
1. 输入图像 (如 4K 放大后)
      │
      ▼
2. DDIM Inversion (反演)
   - 将 clean latent 反演到目标噪声级别
   - 保留图像全局结构
   - 使用 tiled UNet (guidance-free)
      │
      ▼
3. Tiled Denoising (降噪)
   - 在反演后的 latent 上执行 denoising loop
   - UNet 以固定 tile size 运行 (SD1.5: 512px, SDXL: 1024px)
   - Overlapping tiles, blending with fade weights
   - SDXL micro-conditioning: 调整 time_ids 告知模型这是 crop
      │
      ▼
4. Low-frequency Structure Anchor
   - 每步之后，将 latent 的低频部分拉回反演轨迹
   - 防止 tiles 生成重复的主体
   - 使用高斯模糊提取低频分量
      │
      ▼
5. Noise Injection (Slerp)
   - 注入少量球形随机噪声，避免过度平滑
   - 注入量随 timestep 递增 (结构阶段少，细节阶段多)
      │
      ▼
6. Tiled VAE Decode
   - 输出最终图像
```

### 4.2 Key Parameters

| Parameter       | SD1.5 NPU | SDXL  |
|-----------------|-----------|-------|
| UNet tile size  | 512px     | 1024px|
| VAE tile size   | 512px     | 1024px|
| Max image size  | 8192px    | 8192px|
| Min image edge  | 512px     | 1024px|
| Grid alignment  | 8         | 4     |
| Inject max      | 0.08      | 0.08  |
| Struct frac     | 0.3       | 0.3   |

---

## 5. QNN Runtime 管理

### 5.1 目录结构

```
filesDir/runtime_libs/
├── default/        ← APK assets 自动复制（App 启动时）
└── <name>/         ← 用户通过文件管理器导入
```

所有 runtime .so 必须在内部存储（`filesDir`），Android 禁止从外部存储 `dlopen`。

### 5.2 Runtime 选择

- 每个模型独立存储 runtime 偏好（`GenerationPreferences`）
- NPU 模型在 Advanced Settings 中显示 runtime 下拉菜单
- 切换模型时后端进程自动重启，使用对应 runtime 的 `--lib_dir`

### 5.3 QNN 版本兼容性

`libstable_diffusion_core.so` 版本决定所需 QNN runtime 版本，向下不兼容。QNN 版本对速度影响不大，差异主要来自生成参数。

详见 `docs/android-dlopen-limitation.md`。

---

## 6. Privacy & Security

### 6.1 Report Image (已禁用)

官方版本中，`ImageUtils.kt` 的 `reportImage()` 会将生成的图片和参数上报到 `report.chino.icu`。

**Fork 中的处理：**
-   `reportImage()` 函数体已替换为空操作 (no-op)。
-   `reportClient` 字段已移除。

### 6.2 Safety Checker

-   可选的 NSFW 内容检测模型 (MNN 格式)。
-   仅 `filter` build variant 默认包含 `safety_checker.mnn`。
-   通过 `--safety_checker` 参数传入。
-   检测阈值默认 0.5 (`--nsfw_threshold`)。
-   检测到 NSFW 内容时，输出全白图像。

---

## 7. Build & Distribution

### 7.1 Build Variants

| Variant | Suffix            | Safety Checker | APK Size (approx) |
|---------|-------------------|----------------|-------------------|
| `basic` | (none)            | ✗              | ~56 MB (official) / ~127 MB (fork, 含 .so) |
| `filter`| `_with_filter`    | ✓              | ~67 MB (official) |

### 7.2 Release Signing

Release 构建需要签名配置。在 `gradle.properties` 中设置：

```properties
RELEASE_STORE_FILE=keystore.jks
RELEASE_STORE_PASSWORD=your_password
RELEASE_KEY_ALIAS=your_alias
RELEASE_KEY_PASSWORD=your_key_password
```

或将 `keystore.jks` 放在 `app/` 目录下。

### 7.3 C++ Backend Rebuild

**当前状态：** `libstable_diffusion_core.so` 从官方 APK 提取，非从源码编译。

**如需从源码编译，需要：**
1.  QNN SDK 2.28+ (高通开发者门户下载)
2.  Android NDK r27d+
3.  构建脚本 (`CMakeLists.txt` 或 `Android.mk`) - 目前不存在
4.  MNN 源码 (通过 git submodule 引入)

**依赖的第三方库 (cpp/src/ 中的头文件引用):**
-   cpp-httplib (HTTP server)
-   nlohmann/json (JSON)
-   xtensor (多维数组)
-   zstd (解压 patch 文件)
-   MNN (小米，开源)
-   QNN SDK (高通，闭源)

---

## 8. UI Screens

| Screen          | Route                | 功能                          |
|-----------------|----------------------|-------------------------------|
| `ModelListScreen` | `model_list`       | 模型列表，下载/导入模型        |
| `ModelRunScreen`  | `model_run/{modelId}` | 模型运行，参数调节，生成图像  |
| `UpscaleScreen`   | `upscale`          | 图像放大                      |
| `HistoryScreen`   | `history`          | 生成历史记录                  |
| `CropImageScreen` | (内部导航)          | 裁剪输入图像                  |
| `InpaintScreen`   | (内部导航)          | 绘制 inpaint mask             |

**ModelRunScreen 子页面 (`ModelRunPages.kt`):**
-   Text-to-Image / Image-to-Image 切换
-   Prompt / Negative Prompt 输入
-   参数调节 (Steps, CFG, Scheduler, Seed)
-   高级设置 (Resolution, Denoise Strength, etc.)
-   生成进度显示 (SSE stream)
-   生成结果展示和操作 (保存, 分享, 放大)

---

*Last updated: 2026-06-28*
