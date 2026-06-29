# Local Dream 使用说明

## 双模型页面

App 主界面有两个模型标签页：

| 标签 | 说明 | 推理后端 |
|------|------|----------|
| **CPU/GPU 模型** | SD 1.5 模型 | MNN（OpenCL） |
| **NPU 模型** | SD 1.5 / SDXL 模型 | QNN HTP（骁龙 NPU） |

### CPU/GPU 模型（标签页标题：CPU/GPU Models）

虽然标签叫 "CPU/GPU"，实际上这些模型使用 **MNN 推理引擎**，支持两种设备：

- **CPU**：纯 ARM NEON 加速，所有设备通用
- **GPU**：通过 OpenCL 后端在 Adreno GPU 上运行，速度更快

APP 会自动检测并选择最优后端（优先 GPU，不可用时回退 CPU）。

> **注意**：这不是"只能 CPU 跑"，而是"用 MNN 跑，CPU 和 GPU 都行"。之所以和 NPU 模型分开，是因为推理管线不同（MNN vs QNN），模型格式和性能特征也不同。

### NPU 模型

仅限骁龙设备，需要：

- **SD 1.5**：Hexagon V68 及以上（骁龙 8 Gen 1 起）
- **SDXL**：骁龙 8 Gen 3 及以上（需要更大显存）

使用高通 QNN HTP 运行时，NPU 专用推理，功耗更低、速度更快。

## 模型管理

详见 [model-import-export.md](model-import-export.md)

- 下载模型后自动解压到 `Android/data/io.github.xororz.localdream/files/models/`
- 支持从 ZIP 导入/导出模型
- 默认模型可通过 App 内下载
