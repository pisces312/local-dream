# QNN Runtime 管理

## 核心限制

Android 的 SELinux/FUSE 禁止从外部存储 (`/storage/emulated/0/`) 通过 `dlopen` 加载 `.so` 文件。

```
dlopen failed: couldn't map "/storage/emulated/0/runtime_libs/239/libQnnSystem.so" segment 2: Permission denied
```

即使文件有读写权限，可执行段的 mmap 也被拒绝。这是硬限制，无法绕过。

| 文件类型 | 访问方式 | 外部存储 |
|---------|---------|---------|
| QNN runtime `.so` | `dlopen` | ❌ |
| 模型 `.bin` / `.mnn` | `open()`/`read()` | ✅ |

## 当前实现

所有 runtime `.so` 始终存放在内部存储 `filesDir/runtime_libs/<name>/`。

```
filesDir/
└── runtime_libs/           ← RuntimeManager 管理
    ├── default/            ← APK assets 自动复制（QNN 2.39）
    │   ├── libQnnHtp.so
    │   ├── libQnnSystem.so
    │   └── ...
    └── 245/                ← 用户通过文件管理器导入（QNN 2.45）
        ├── libQnnHtp.so
        └── ...
```

### 初始化流程

1. **App 启动** → `LocalDreamApplication.onCreate()` → `RuntimeManager.ensureDefaultRuntime()` 从 `assets/qnnlibs/` 复制到 `filesDir/runtime_libs/default/`
2. **用户导入** → 文件管理器进入 `runtime_libs` → "Import Folder" 按钮 → SAF 选择源文件夹 → 复制 .so 到内部存储
3. **运行模型** → `BackendService.startBackend()` → `RuntimeManager.getRuntimeDir(context, runtimeDirName)` → `--lib_dir` 指向内部存储

### 模型级别 Runtime 选择

每个模型独立存储 runtime 偏好（`GenerationPreferences`），切换模型时后端进程自动重启并使用对应的 `--lib_dir`。

- SD1.5 模型可选择 `default`（2.39）
- SDXL 模型可选择 `245`（2.45）
- 无需手动重启，`BackendConfig` data class 比较包含 `runtimeDirName`，变更即触发 reconcile

## QNN 版本兼容性

### 已验证的组合

| libstable_diffusion_core.so | QNN Runtime | SD1.5 | SDXL | 备注 |
|---|---|---|---|---|
| 2.39 | 2.39 | ✅ | ✅ | 默认组合 |
| 2.39 | 2.45 | ✅ | ✅ | 可用，速度无明显差异 |
| 2.45 | 2.39 | ❌ | ❌ | 不兼容，无法运行 |
| 2.45 | 2.45 | ❌ (anything) | ✅ | anything 模型出白图 |

### 结论

- `libstable_diffusion_core.so` 的版本决定了需要的 QNN runtime 版本，向下不兼容
- QNN runtime 版本（2.39 vs 2.45）对推理速度影响不大，速度差异主要来自生成参数
- 保持 2.39 core + 2.39 runtime 为默认，2.45 runtime 作为可选导入

## 关键代码

| 文件 | 职责 |
|---|---|
| `RuntimeManager.kt` | runtime 目录扫描、导入、默认初始化 |
| `LocalDreamApplication.kt` | App 启动时调用 `ensureDefaultRuntime()` |
| `BackendService.kt` | `prepareRuntimeDir()` 设置权限，`startBackend()` 解析 runtime 路径 |
| `AdvancedSettingsDialog.kt` | runtime 下拉选择（NPU 模型） |
| `ModelListScreen.kt` | 文件管理器中的 "Import Folder" 按钮 |

*Last updated: 2026-06-28*
