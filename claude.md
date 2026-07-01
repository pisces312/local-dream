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
| QAIRT SDK | `D:/dev/qairt/2.39.0.250926` |
| Android NDK | `D:/dev/android_sdk/ndk/28.2.13676358` |
| CMake | `D:/dev/android_sdk/cmake/3.22.1/bin/cmake.exe` |
| Ninja | `D:/dev/android_sdk/cmake/3.22.1/bin/ninja.exe` |
| Rust | `D:/dev/rust` (1.96.0, target: aarch64-linux-android) |

## 快速构建

```bash
# 1. 编译 native .so
rebuild-native.bat    # Windows CMD, 或手动:
export ANDROID_NDK_ROOT=D:/dev/android_sdk/ndk/28.2.13676358
export QAIRT_PATH=D:/dev/qairt/2.39.0.250926
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

## 重要注意事项

- **SampleApp patch 已直接入库**：`3rdparty/SampleApp/src/` 中的源文件已包含 mmap、convertToFloatInto 等改动，不需要运行时 apply `SampleApp.patch`。`SampleApp.patch` 仅为历史归档。
- **tokenizers-cpp 子模块**：已 fork 到 `pisces312/tokenizers-cpp`，含 Rust 1.96 autoref 修复 (`fix/rust-1.96-autoref`)。
- **ccache 禁用**：CMakePresets.json 配置了 `CMAKE_C_COMPILER_LAUNCHER=ccache`，Windows 下必须传入 `-DCMAKE_C_COMPILER_LAUNCHER="" -DCMAKE_CXX_COMPILER_LAUNCHER=""`。
- **Ninja 路径**：必须显式指定 `-DCMAKE_MAKE_PROGRAM=...`，避免 cmake 找到不兼容版本。
- **上游**：`upstream` = `github.com/xororz/local-dream`，`origin` = 自己的 fork。

## 模型支持

| 类型 | 架构 | 文本编码器 | 分辨率 | 后端 |
|---|---|---|---|---|
| SD1.5 CPU | UNet | CLIP | 512×512 | MNN |
| SD1.5 NPU | UNet | CLIP | 512×512 | QNN |
| SDXL | UNet | CLIP×2 | 1024×1024 | QNN |
| Anima | DiT | Qwen3-0.6B | 1024×1024 | QNN |

Anima 模型通过自定义导入使用，模型目录需包含 `ANIMA` 标记文件。
