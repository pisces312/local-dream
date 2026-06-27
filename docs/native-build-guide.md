# Native C++ 后端构建指南

## 当前实际构建方式（2026-06-17 采用）

### 核心策略：使用官方预编译二进制文件

项目当前不直接从源码编译 C++ 后端，而是使用从官方 Release APK 提取的预编译二进制文件。

| 组件 | 来源 | 位置 |
|------|------|------|
| `libstable_diffusion_core.so` | 官方 Release APK 提取 | `app/src/main/jniLibs/arm64-v8a/` |
| QNN 运行时库（20个 .so） | 官方 Release APK 提取 | `app/src/main/assets/qnnlibs/` |

### 快速构建命令

```bash
# Debug 构建（直接打包预编译 .so）
./gradlew assembleBasicDebug

# Release 构建（含签名）
export KEY_STORE="D:\nili\my-git-projects\my-backup\backup-settings\my-android-release.keystore"
export KEY_STORE_PASSWORD="314159"
./build-local.sh release basic
```

---

## 官方原生 C++ 源码编译流程

> **注意**：以下流程本地尚未实际执行，仅根据项目配置还原。如需从源码编译，需配置完整 QNN SDK 环境。

### 1. 环境要求

| 依赖 | 版本/路径 | 说明 |
|------|-----------|------|
| QNN SDK | 2.39.0.250926 | 高通闭源 NPU 推理框架 |
| QNN_SDK_ROOT | `/data/qairt/2.39.0.250926` | 环境变量 |
| Android NDK | r28 | 路径 `/data/android-ndk-r28` |
| MNN | git submodule | 小米开源神经网络引擎 |
| 其他 | zstd、xtensor、cpp-httplib、nlohmann/json、tokenizers-cpp | C++ 依赖库 |

### 2. 编译步骤

```bash
# 步骤 1: 初始化 git submodule（MNN）
git submodule update --init --recursive

# 步骤 2: 编译 C++ 后端
cd app/src/main/cpp

# Windows
./build.bat

# Linux/WSL
./build.sh

# 步骤 3: 复制产出物到 Gradle 打包目录
# - libstable_diffusion_core.so → app/src/main/jniLibs/arm64-v8a/
# - QNN .so 文件 → app/src/main/assets/qnnlibs/

# 步骤 4: Gradle 打包 APK
cd ../../../../
./gradlew assembleBasicDebug
```

### 3. CMake 配置说明

- `CMakeLists.txt` 读取 `QNN_SDK_ROOT` 环境变量定位 QNN SDK
- `CMakePresets.json` 默认 NDK 路径：`/data/android-ndk-r28`
- `SampleApp` 代码：从 QNN SDK 复制后，通过 `SampleApp.patch` 打补丁（mmap 优化、accessibility 修改等）

---

## 方案选择说明

### 为什么使用预编译方案？

1. **QNN SDK 闭源专有** — 仅高通授权设备可获取，普通开发环境无安装
2. **路径依赖强** — CMake 配置硬编码 Linux 路径 `/data/qairt/`，Windows 环境对齐成本高
3. **构建复杂度高** — MNN submodule + QNN SDK 交叉编译链配置复杂
4. **预编译满足需求** — APK 构建仅需 .so 文件，提取官方预编译版本可快速完成构建

### 何时需要源码编译？

- 修改 C++ 后端推理逻辑（`Pipeline*.hpp`、`main.cpp` 等）
- 升级 QNN SDK 版本
- 新增芯片支持（如新 HTP 版本）
- 调试 native 层崩溃问题

---

## 相关文档

- [SM8850 专属构建](./sm8850-build.md) - QNN 库精简方案
- [架构设计](./architecture.md) - C++ 后端与 Kotlin 前端通信协议
