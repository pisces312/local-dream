# 超分 500 错误诊断（2026-09-19）

## 症状

Z-Image Turbo 生成图片成功后，点击超分按钮立即报错，UI Toast 只显示「500」。
（前置：DiT 引擎自构建已完成并实机验证通过，见 `2026-09-19-dit-engine-build.md`。）

## 为什么第一轮 logcat 什么都抓不到

1. 客户端 `performUpscale()` 抛出的完整错误（含服务端 JSON 错误体）只在
   **Toast（LENGTH_SHORT）** 里显示，长文本被截断，用户只看到 500；
   catch 块**不打任何 log**，错误信息没进 logcat。
2. 超分服务端是**独立 native 进程**（`libstable_diffusion_core.so`），
   QNN 日志走 `fprintf(stdout)`，stdout 接管道时是**全缓冲**——日志憋在
   缓冲区里，monitor 转发线程收不到任何行。
3. Honor 设备 logcat 冲刷极快，历史 buffer 已被清空。

诊断手段：自编 `setvbuf` 无缓冲 `LD_PRELOAD`，在设备上手动起一个
`--upscaler_mode --port 8099` 测试实例，用 `nc` 发最小请求拿到真实错误体。
（测试痕迹已全部清理；`D:\Temp\localdream-upscale.log` 留有当时的抓取。）

## 根因链条（三层）

### 根因 1：500 的直接原因 —— 请求打进了 DiT 生成服务器

- 超分设计与实现：`performUpscale()` POST 到 8081（`LOCAL_BACKEND_HOST`），
  设计假设「NPU 生成服务器自带 QNN runtime，能顺带处理 /upscale」。
- v3.0.0 起 Z-Image/Klein 是 DiT 模型，8081 上跑的是 DiT 生成服务器：
  `--type zimage --lib_dir <nativeLibraryDir>`。
  `DitEngine.dir()` = `nativeLibraryDir`，里面**没有 `libQnnHtp.so`**
  （APK 不带 QNN runtime 库，QNN 库在 `files/runtime_libs/<runtime>/`）。
- `/upscale` 处理时 `qnn_runtime::createModel` → dlopen `libQnnHtp.so` 失败 →
  `{"error":{"message":"Server Err: Failed to create upscaler model from: ..."}}` → 500。

### 根因 2：超分后端环境变量缺陷（上游已存在）

- `UpscaleScreen.startUpscalerBackend` 只设了 `DSP_LIBRARY_PATH`，
  **漏了 `ADSP_LIBRARY_PATH`**（FastRPC 找 skel 用的是后者；
  `BackendService` 的 DiT 分支两个都设了）。
- 补上后 dlopen libQnnHtp.so 成功，进入下一层失败。

### 根因 3：更深的问题 —— 本机 QNN unsigned PD 起不来

补全全部环境变量（含 vendor 路径）后，QNN 依然失败：

```
createUnsignedPD unsigned PD or DSPRPC_GET_DSP_INFO not supported by HTP (err 1002)
```

### ~~根因 3~~（已证伪，2026-09-19 20:40 实机复核）

> **更正**：手动测试实例经 `run-as` 启动，该上下文缺少应用进程的
> SELinux category，FastRPC unsigned PD 被拒（`createUnsignedPD ... err 1002`）
> 是 **run-as 测试环境的假象，不是设备问题**。
> 实机复核：运行时切到 `default`（QAIRT 2.50，与核心匹配）后，
> **illustrious_v17_dmd2 的生成和超分全部正常**——SM8850 上 QNN 栈完全可用。
> 之前给上游开的 #310 结论有误，需更正/关闭。
> 附带确认：245 运行时 + 2.50 核心的版本错配会报
> `Failed get QNN system func ptrs`（系统接口初始化失败），属运行时选择问题。

## 上游（main 分支）对比结论：确实是上游新改动破坏的

上游 `upstream/master`（`b33dafc`，即本地合并基点）逐项核对：

| 环节 | v2.8.1（超分正常） | v3.0.0-alpha.1 / 上游最新 |
|---|---|---|
| 后端类型 | 仅 sd15npu 等 QNN 模型 | 新增 **zimage / klein（DiT）**（上游提交 `7bbdfb0`） |
| 生成后端 lib_dir | 非 CPU 一律 `runtime_libs/`（含 libQnnHtp.so） | DiT 模型改为 `nativeLibraryDir`（**无 QNN 库**） |
| 超分按钮条件 | `!runOnCpu && 尺寸<=1024` | **完全相同**，只排除了 CPU，没排除 `isDit` |
| /upscale 路由 | 打到带 QNN 库的服务器 → 可用 | 打到 DiT 服务器 → **必 500** |

- 超分触发方式两版一致：`ModelRunScreen` → `UpscalerPickerFlow` →
  `performUpscale()` → POST `http://<8081>/upscale`，没有任何后端切换逻辑。
- 上游超分按钮条件注释里考虑了「CPU 后端没初始化 QNN runtime」，
  **但没有考虑 DiT 后端同样没加载 QNN runtime**——`7bbdfb0`
  （feat: add optimized DiT model support）引入 zimage 时破坏了超分，
  上游 master（含后续 `903284c` SDXL MNN、`b33dafc`）未修复。
- 结论：**是上游 v3.0.0-alpha.1 的 DiT 功能破坏了 Z-Image/Klein 的超分**，
  属于上游 bug（上游仓库可反馈）；sd15npu 等 QNN 模型的超分路由未受影响。

## 修复方案（已确认设计，待实施——2026-09-19 20:55）

已实机确认（20:54）：**独立超分界面（UpscaleScreen）对 Z-Image 生成的图片
超分正常**——其私有 `--upscaler_mode --lib_dir <runtime_libs>` 进程只用
`DSP_LIBRARY_PATH` 即可在本机工作（ADSP 疑虑同为 run-as 假象，撤销）。
所以 #309 的影响面精确为：**DiT 模型界面内的超分按钮**——请求打到
占着 8081 的 DiT 生成服务器（lib_dir 无 QNN 库）必然 500。

DiT 模型加独立的 8082 超分进程，生命周期对齐现有模式：

1. **懒启动**：DiT 模型界面点超分确认时，若 8082 无存活实例 → 起
   `--upscaler_mode --lib_dir <resolvedRuntimeDir>` 进程
   （首超分多等几秒 QNN 初始化，与 QNN 模型超分首启耗时一致）；
2. **复用**：实例活着直接 POST，连续超分不重复启动；
3. **随界面退出**：`ModelRunScreen.cleanup()` 里连同 8082 进程一起停
   （与 8081 的 `ACTION_STOP` 同一时机）；
4. ~~核查 UpscaleScreen env~~（已撤销）：独立超分界面实机验证正常，
   无需改动。

改动范围：`ModelRunScreen.kt`（超分确认回调 + cleanup）+ 抽一个
upscaler 进程管理 helper（可参考 `UpscaleScreen.startUpscalerBackend`
的实现，但换端口并抽到可复用位置）。

### 已确认（用户实机）

- QNN 模型（illustrious_v17_dmd2）运行时切到 `default` 后生成+超分正常；
- Z-Image 超分仍 500（即本文件根因 1/#309，独立于运行时选择）。

## 关键代码位置

- `utils/ImageUtils.kt` `performUpscale()` —— POST /upscale + Toast 错误链
- `service/BackendService.kt` ~L500 —— `isDitBackend` → `--lib_dir nativeLibraryDir`
- `ui/screens/UpscaleScreen.kt` —— `startUpscalerBackend`（缺 ADSP_LIBRARY_PATH）
- `app/src/main/cpp/src/main.cpp` `/upscale` handler —— 500 的 throw 点
- `app/src/main/cpp/src/QnnRuntime.hpp` —— `createModel` → `getQnnFunctionPointers`
