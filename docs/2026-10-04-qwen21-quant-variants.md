# Qwen Image 2.1 量化变体调研（FP8 / Q4_0 双支持可行性）

> 初稿：2026-10-04 ｜ 修订：2026-10-05（§4 新增、§1/§5/§6/§8/§9 结论改写、§10 新增日志手册；
> **§5.3 关于 `disk` 的结论二次修订（勘误）**；原 §12 崩溃实测已整体迁到
> [2026-10-05-qwen21-1024-decode-oom.md](./2026-10-05-qwen21-1024-decode-oom.md)）
> 背景：合并上游 v3.0.0-alpha.4 后，Qwen Image 2.1 的下载包由 `dit.gguf`(Q4_0) 换成
> `dit.safetensors`(F8_E4M3)，整包 10.8GB → 13.7GB，真机 1024×1024 内存吃紧。
> 问题：Q4_0 是不是因为精度不够被废弃？能否同时支持两种量化？
> 本文除 §9 的实验记录外均为调研结论，不含已落地的功能改动。

---

## 1. 结论速览

1. **Q4_0 没有被引擎废弃**，被废弃的只是 App 的下载包条目。子模块 README 的
   验证矩阵仍列 `Q4_0, MXFP4, Q8_0 (GGUF); F8_E4M3 (safetensors)`（`README.md:16`），
   并保留一条完整的 `#### Edit with Qwen Image 2.1 Q4_0` 示例（`README.md:274-291`）。
2. 上游换 FP8 的动机是**算力路径 + 少步数可用**，不是画质崩塌。
3. **双量化在代码层面很便宜**（按魔数嗅探、路径透传、不用重建 DiT 引擎、不用 bump ABI），
   但**收益不划算**：整数量化在 HTP 上走 HVX 反量化路径，上游实测比 FP8 慢约 9 倍
   （§4）。1024×1024 / 20 步会变成半小时量级，且更高分辨率下反而更容易 OOM。
   → **方案 A 降级为「不做」**。
4. 「qwen21 的 params_backend 被硬编码成 `te=disk` ⇒ **DiT 常驻**」这个方向**是对的**，
   但两点要修正：① 常驻量实测是 `diffusion_model 6792.67 MB`（不是估算的 8 GB）；
   ② `te=disk` 本身工作正常（conditioning 后确实释放了 3739.16 MB），失败点在**它之后的
   解码**，不在加载阶段。
5. ~~真机表现待你复现，判据见 §10。~~ —— **2026-10-05 17:33 已复现并定位**，崩溃记录与
   修复方案评估独立成文：[2026-10-05-qwen21-1024-decode-oom.md](./2026-10-05-qwen21-1024-decode-oom.md)。
6. **真因**：1024² 的 **VAE 整帧解码**要 2.72 GB dma-buf，而当时 `MemAvailable` 只有 1.7 GB
   ⇒ App 与 backend 双双被 lmkd SIGKILL；解码根本没分块，因为 `PipelineDit.hpp:285` 的门槛是
   `1536²`，且默认 `dit_vae_tile_size=64` 在 64×64 latent 上等于没切。上游的「解码失败自动
   重试分块」兜底不触发，因为 dma-buf 是慢而成功、失败的形式是杀进程。机制细节与修复方案
   见上面那篇（本文原 §12 已整体迁出）。
7. **由此带出一个比双量化重要得多的修正**：本文 §5.3 原先据「`disk` 每步重读」否掉的
   方案 C（`diffusion=disk`）**依据是错的**——DiT 的 `runner_end()` 是整轮一次。真实代价
   是每请求约 12 s 的权重重读，换来解码前归还 6.79 GB ⇒ **它就是首选修复**（§5.3）。

---

## 2. Q4_0 被「删除」的真相

`3f76ac7 feat: delete the obsolete Qwen Image 2.1 Q4_0 weights` 的 commit body：
「The package switched to an FP8 dit.safetensors, leaving a 4GB dit.gguf **nothing
reads**.」——是 App 层没人读，不是引擎不能读。根因是 `main.cpp:356` 把文件名写死：

```cpp
std::string dit_path = (dir / "dit.safetensors").string();   // 不再按 kind 分叉
```

本 fork 在合并前是 `qwen21 ? "dit.gguf" : "dit.safetensors"` 的三元式，合并时
git 自动合并保留了本地版，与上游语义冲突，已在 `40dc019` 统一改成上游写法。
对应的 App 层清理是 `Model.kt:803`：

```kotlin
File(modelsDir(), "$id/dit.gguf").delete()   // 每次刷新模型列表都执行
```

**这两处就是引入双量化时的必改点，也是唯一的形式冲突点。**

---

## 3. 上游为什么选 FP8

| 动机 | 证据 |
|---|---|
| FP8 走 HMX 原生 fp8 数据通路，无反量化步骤 | `ggml/src/ggml-hexagon/htp/hmx-mm-kernels-tiled.h:679-703` `core_dot_tile_fp16_f8`（`__HEXAGON_ARCH__ >= 79`，指令 `weight.f8 = mxmem(...)`）；`htp/matmul-ops.c:2599-2600` `direct_f8` 为真时 `dequant_worker_fn` 保持 `NULL`，f8 原始字节直接喂给 kernel（`:2759/2864` 的 `direct_f8 ? curr_raw : ...`） |
| Q4_0/Q8_0 要 HVX 先把权重反量化成 f16 tile，再由 HMX 做 f16×f16 | `htp/matmul-ops.c:2602-2606` 给每种整数类型设 `dequantize_tiled_worker_loop_*`，仅当 `!direct_f8` 才执行（`:2734/2739/2840/2846`） |
| 因此 Q4_0 省的是 DRAM 字节与存储，**不省 HMX 周期** | 上一条 + `matmul-ops.c:2606` 的 q8_0 同构路径 |
| FP8 一步出图可用，Q4_0 需要 20 步 | `README.md:252`「With the F8_E4M3 DiT, Qwen Image 2.1 appears to produce a good edit with only one sampling step.」，对比图里 Q4_0 那张是 20 步（`README.md:250`） |
| FP8 前置修复：Unsloth/torchao 的 per-row scale 存成 `[out,1]`，Linear 要 1-D，不修则所有量化层 shape 校验失败 | `dit/stable-diffusion.cpp.patch` 的 `src/model_io/safetensors_io.cpp` hunk（上游 `0d795bf`） |

FP8 的启用条件（本 fork 全部满足）：`ggml-hexagon.cpp:5028` 要求
`opt_arch >= 79` 且激活为 F32；`:5072` 要求 `n_hmx != 0`。
`dit/build.sh:55` + `dit/CMakeLists.txt:76-77` 只产 **v79/v81** skel，覆盖 App 支持的所有机型。

---

## 4. Q4_0 的真实代价（本次新增，决定方案 A 去留）

### 4.1 派发证据：整数类型一律不走 HMX fp8 通路

`ggml/src/ggml-hexagon/htp/matmul-ops.c` 的重排权重分派表——矩阵乘与向量乘两组，
整数类型全部指向 `hvx_*` 核：

```c
// matmul-ops.c:1420-1428（src1_nrows > 1）
case HTP_TYPE_Q4_0:   matmul_job_func = hvx_mm_2d_repacked_q4_0;   break;
case HTP_TYPE_Q4_1:   matmul_job_func = hvx_mm_2d_repacked_q4_1;   break;
case HTP_TYPE_Q8_0:   matmul_job_func = hvx_mm_2d_repacked_q8_0;   break;
case HTP_TYPE_IQ4_NL: matmul_job_func = hvx_mm_2d_repacked_iq4nl;  break;
case HTP_TYPE_MXFP4:  matmul_job_func = hvx_mm_2d_repacked_mxfp4;  break;
// matmul-ops.c:1433-1441（src1_nrows == 1）同构，换成 hvx_mv_2d_repacked_*
```

**MXFP4 也在同一档**——我原先猜「MXFP4 可能有原生通路」是错的，它同样反量化成 f16。
所以想靠换量化格式提速，在 HTP 上只有 `F8_E4M3` 一条路。

### 4.2 上游实测（SM8750 / HTP v79，1024×1024）

`README.md:55-62`（同模型、同分辨率、同 steps 的 Q4_0 vs FP8 直接对比）：

| Model | Res | Steps | Q4_0 | FP8 |
|---|---:|---:|---:|---:|
| Z-Image Turbo | 1024² | 8 | **91.03 s/it** | **8.24 s/it** |
| FLUX.2 Klein 4B | 1024² | 4 | **79.42 s/it** | **6.62 s/it** |
| Z-Image Turbo | 1536² | 8 | **OOM** | 29.63 s/it |
| FLUX.2 Klein 4B | 1536² | 4 | **OOM** | 18.62 s/it |
| Z-Image / Klein | 2048² | 4 | **OOM** | 60.20 / 42.49 s/it |

`README.md:64` 自己的结论：**「At 1K, FP8 is 8.87x faster for Z-Image and 9.30x faster for
FLUX.2 Klein than the current upstream Hexagon Q4_0/Q8_0 path.」**

两点对本项目直接相关：

- **反直觉但关键**：Q4_0 权重更小却在 ≥1536² **OOM**，FP8 反而能跑。整数路径要在片上
  把 tile 反量化成 f16 暂存，峰值占用高于直接以 f8 参与运算。
  ⇒ 「换 Q4_0 来省内存」这个前提本身不成立。
- 表内数据是 Z-Image / FLUX.2 Klein，不是 Qwen Image 2.1；但派发路径（§4.1）与
  Qwen 2.1 的 Linear 结构相同，量级可外推。Qwen 2.1 在 `README.md:248-256` 只给了
  图片对比（Q4_0 20 步 vs FP8 1/20 步），没给 s/it。

### 4.3 算一笔账

按 9× 折算，若 Qwen 2.1 FP8 在本机约 8 s/it（未实测，仅同量级参考），Q4_0 约 70 s/it：
20 步 ≈ **23 分钟/张**，加文本编码与解码更久。省下的约 4GB 常驻内存，代价是这个。

⇒ **方案 A（同目录双权重 + 变体开关）不做。** 内存问题的正确解法是 §5/§6 的常驻策略
与峰值内存旋钮，而不是换量化格式。

---

## 5. params_backend 语义（本次内存问题的核心）

传递链：`main.cpp:96`(默认) → `main.cpp:379-382`(qwen21 特例) → `PipelineDit.hpp:39,76`
→ `dit/DitEngine.cpp:163-164`（原样透传，不解析）
→ `src/pipeline/diffusion_engine.cpp:857`
→ `src/core/ggml_extend_backend.cpp:764-781` `SDBackendManager::init`
→ 解析器 `ggml_extend_backend.cpp:596-644`。

语法：逗号分隔；`key=value`，或裸 `value`（等价于给所有模块设默认值，`:614-616`）。

- key（`:53-91`，大小写不敏感、忽略 `-`/`_`）：`all`/`default`/`*`；
  `diffusion|model|unet|dit`；`te|clip|text|textencoder|llm|t5`；
  `clipvision|vision`；`vae|firststage|autoencoder`；`upscaler|esrgan|hires` 等
- value：任意 GGML 设备名（`cpu`/`HTP0`/…，前缀唯一即可）；`auto`/`default`/空 ⇒ 跟随
  计算后端；**`disk` 仅 params_backend 允许**（`:826-831` 明确拒绝把它当 runtime backend）

### 5.1 三种取值的内存行为

| 取值 | ResidencyMode | 行为 |
|---|---|---|
| 空 | 跟随 runtime | 参数常驻计算后端，最快、最占内存 |
| 具名后端（`te=cpu`） | `ParamBackend` | 参数落在 params 后端的缓冲里（`model_manager.cpp:983-1000` `params_buffer_type_for`），用时 stage 进计算后端（置位点 `model_manager.cpp:580`），缓冲本身保留 |
| `disk` | `Disk` | 在 `runner_end()` 释放参数缓冲，下次 `prepare_params()` 重新读（`model_manager.cpp:1397`）。**DiT 的 `runner_end()` 是整轮采样一次，不是每步**（见 §5.3 勘误） |

模式选择只有一处：`diffusion_engine.cpp:355`
`params_backend_is_disk(module) ? Disk : ParamBackend`。

### 5.2 上游给 qwen21 设 `te=disk` 的意图

`README.md:11`「Devices with less than 16 GB of memory … use `--params-backend te=disk`」
与 `README.md:76`「Text encoder parameters are released after conditioning with `te=disk`」。
即：**只把「一次生成只调用一遍」的文本编码器踢出常驻，DiT 留在内存里保证 s/it。**
这不是为了「全放内存提速」，而是在内存与速度之间只砍代价最小的一刀——代价最小指的是
**读回次数最少**：TE 与 DiT 在 disk 模式下都只在各自那一轮结束时被释放/重读一次
（DiT 的释放点在 `sample()` 返回时，见 §5.3），所以把 DiT 也设为 `disk` 只是每请求
多一次权重重读，**不是**每步一次。

副作用：非空 params 规格会**关掉 `auto_fit`**（`diffusion_engine.cpp:859`）。

关键分歧：fork 默认 `all=disk`（`main.cpp:96`，三个模块都不常驻），`2a79df0` 给 qwen21
换成 `te=disk`（只流式 TE，**DiT 常驻**）。真机实测常驻量
`diffusion_model 6792.67MB(VRAM)`（见 [解码 OOM 记录](./2026-10-05-qwen21-1024-decode-oom.md) §2）。

### 5.3 `disk` 的真实代价（2026-10-05 二次修订：**此前结论是错的**）

> 本节原先写「DiT 每步都跑一次 `runner_end()` ⇒ `all=disk` 把 8GB 权重每步重读 ⇒ 慢到不可用」，
> 并据此否掉了方案 C。**那个推断是错的**，错因在下面第二条里写明。修正后的结论与代价口径
> 以 [解码 OOM 记录](./2026-10-05-qwen21-1024-decode-oom.md) §5 A / §6 为准。

释放链路本身没看错。`src/core/ggml_runner.cpp:468-489`：

```cpp
void GGMLRunner::runner_end() {
    ...
    workspace_.release();
    cache_.clear();
    ...
        manager->evict_compute_backend_params(tensors);   // :485
}
```

`ModelManager::evict_compute_backend_params`（`src/model_manager.cpp:1461-1512`）里，
只有满足下面条件的 `params_storage_blocks_` 才会被释放（`:1498-1507`）：

```cpp
state->pin_count == 0 && !state->staged_to_compute_backend &&
state->residency_mode == ResidencyMode::Disk
```

错在**触发频率**。`GGMLRunner::compute()` 确实用 RAII guard 在每次图执行结束时调
`runner_end()`（`ggml_runner.cpp:587-595`），`auto_runner_end` 也确实是
默认 true（`ggml_runner.h:318`）——但 **DiT 的实际调用点显式传了 false**：
`src/model/diffusion/qwen_image_2_1.hpp:468`
`GGMLRunner::compute(build, n_threads, false)`。整轮采样的结束点在
`src/pipeline/diffusion_engine.cpp:2087-2095`：`sample()` 用
`RunnerEndOnExit sample_diffusion_runner_end{work_diffusion_model.get()}`，**函数返回时**
才 `runner_end()`；而 `image.cpp:848` 之后才走到 `decode_first_stage`。
重读也只发生一次：`load_tensors_to_params_backend`（`model_manager.cpp:423`）只处理
`loaded_to_params_backend == false` 的张量（`:436`）。

修正后的口径：**`diffusion=disk` = 每请求一次权重重读**（本机实测
`dit.safetensors` 的 `copy_to_backend` 为 **11.85 s**）换来 **6.79 GB** 在解码前归还，
20 步 / 166 s 的运行约 **+7 %**。所以它是首选修复，而不是「只能当诊断手段」。
`2a79df0` 那次特判成 `te=disk`，本质是拿内存换回这 12 秒——在 16 GB 机型上这笔交易
不划算，因为省下的 12 秒远小于崩掉重跑的时间。

### 5.4 mmap：两道墙，但墙后仍有一条可试的路（方案 D）

`src/model_manager.cpp:817-827`：

```cpp
bool ModelManager::can_mmap_storage(const TensorState& state) const {
    if (state.source_file != 0 || !enable_mmap_ ||
        state.residency_mode != ResidencyMode::ParamBackend) {   // :818
        return false;
    }
    if (state.compute_backend == nullptr || state.params_backend == nullptr) {
        return false;
    }
    return sd_backend_is_cpu(state.compute_backend) ||
           sd_backend_is_cpu(state.params_backend) ||
           backend_supports_host_buffer(state.compute_backend);  // :824-826
}
```

两个结论：
1. `enable_mmap_` 默认 false（`model_manager.h:107`）；C API 里有 `sd_ctx_params_t.enable_mmap`
   （`stable-diffusion.h:225`，默认 false 由 `stable-diffusion.cpp:323` 写入），
   链路是通的（`diffusion_engine.cpp:852` 读 → `:883` `model_manager->set_enable_mmap()`），
   只是 **`DitEngine.cpp:148-164` 从不赋值** ⇒ App 侧走不到。
2. 更硬的一堵墙：HTP 设备自己声明**不支持 host buffer / mmap**
   （`ggml-hexagon.cpp:7040-7046` 的 `host_buffer = false`、`buffer_from_host_ptr = false`、
   **`mmap_support = false`**）。于是 `can_mmap_storage` 的最后一行
   （`:824-826`：CPU 或 `buffer_from_host_ptr`）对「计算=HTP、参数=HTP」恒为假。
   ⇒ **mmap 只对「params 落在 CPU 后端」的模块成立**，且与 `disk` 互斥。

> 记为**方案 D**（比原以为的便宜，且未验证）：把 DiT 的权重 mmap 进 CPU 侧 params 缓冲，
> 让内核页缓存承担重复使用——常驻可回收，又不像 `disk` 每步重读。需要两件事：
> 1. `DitEngine.cpp` 里补一行 `sd_params.enable_mmap = true`（**不改 `dit_ctx_params` 结构 ⇒
>    不用 bump ABI，只需 WSL2 重编 engine + 刷新 `dit-engine.json`**）；
> 2. `main.cpp` 给 DiT 的 params backend 设成 CPU（例如 `diffusion=cpu,te=disk,vae=HTP0`），
>    绕开 HTP 的 `mmap_support = false`。
>
> 未知数：CPU 参数 → HTP 计算的每步搬运开销（`params_buffer_type_for` 走
> `ggml_backend_dev_host_buffer_type(compute_dev)` 那条分支），以及它是否比 `all=disk`
> 真的更快/更省。排在 §11 前三步之后。

### 5.5 CLI 可达性

`main.cpp:192-203` 的长选项表里**没有** `--dit_params_backend` / `--dit_backend` /
`--dit_vae_tile_size` / `--dit_threads`，这些全是编译期默认值，App 侧无法传，
要试参数就得先加 flag 再重编 core。

---

## 6. 1024×1024 的内存旋钮盘点

| 旋钮 | 现状 | 可动性 |
|---|---|---|
| params 常驻策略 | qwen21 被硬编码 `te=disk`（DiT 常驻 6792.67 MB） | **最容易改且已核实机制**：`diffusion=disk` 在解码前释放，代价约 12 s/请求（§5.3、§8 方案 C） |
| VAE tiling 阈值 | `PipelineDit.hpp:194-197` 仅在 `w*h > kTileAbovePixels`（`:285` = 1536²）时才传 tile 参数 ⇒ **1024² 整帧解码** | **单独下调阈值是空转**，见 §6.1 |
| `dit_vae_tile_size` | `main.cpp:98` = 64，且无 CLI flag | 与阈值一起下调才有意义（§6.1） |
| 采样步数 | App 侧参数，与量化格式无关 | **免费的线性旋钮**：FP8 一步出图已可用（`README.md:252`），先降步数再动别的 |
| 自动分段计算 | `ggml_runner.cpp:806-809` 只有整图放不下才切段；`DitEngine.cpp:161` 强制 `disable_prefetch = false` | 默认受益，无需动 |
| mmap | 见 §5.4 的两道墙 | engine 里补一行 + 把 DiT 的 params backend 设成 CPU 才有效；**不用 bump ABI** |
| `wtype` / `tensor_type_rules` | C API 有（`stable-diffusion.h:218-220` → `model_loader.cpp:774-798`） | 未过 DiT ABI，同样要 bump ABI |
| `lowram` | `main.cpp:147` 只对 sdxl/anima 生效 | 与 DiT 无关 |
| 关掉其它常驻大户 | 后台 App / upscale 进程 / 未释放的 `sd15npu` 后端 | 排查时先看 `dumpsys meminfo`，成本最低 |

### 6.1 为什么「下调 `kTileAbovePixels`」在 1024² 上不起作用

Qwen Image 2.1 的 VAE `scale_factor = 16`（`src/model/vae/vae.hpp:161-166`）⇒
1024² 对应 **64×64 latent**。解码分块是以 latent 尺寸为 `small_dim` 计算的
（`process_tiles_2d` 中 `decode` 分支把 `small_*` 设为 input 尺寸），而
`sd_tiling_calc_tiles` 的收口在 `src/runtime/tiling.cpp:50-53`：

```cpp
if (num_tiles_dim <= 2) {
    if (small_dim <= tile_size) {
        num_tiles_dim           = 1;      // 单块 == 不分块
        tile_overlap_factor_dim = 0;
```

`dit_vae_tile_size` 默认就是 64 ⇒ `small_dim(64) <= tile_size(64)` ⇒ **1 块，等于没分**。
真要分块必须**同时**把 tile size 降到 32 或 16（4 块 / 16 块），代价：
- 每块都带一次完整的 encoder/decoder 图执行，重叠区被算两次 → 总计算量上升
- 混合是 25% 重叠上的 **smootherstep**（`6x⁵−15x⁴+10x³`，不是线性淡入淡出；定义
  `src/runtime/tiling.cpp:110-113`、混合累加 `:123-133`，宽度由 `PipelineDit.hpp:197` 的
  `vae_tile_overlap = 0.25f` 决定）→ 低频内容基本无感，细密纹理/文字在缝上可能轻微发虚；
  因两端导数为 0，接缝比线性更不易察觉，但仍需人眼验收

⇒ 若 OOM 确实发生在解码阶段，这是对症的；若发生在采样阶段，调它无用。先用 §10 判阶段。

---

## 7. App 层约束（决定方案形状；若日后重启 A 再看）

| 约束 | 位置 | 影响 |
|---|---|---|
| **`id` 即目录名**，无解耦字段 | `Model.kt:113`；用于 `getModelsDir(...)/id`、DataStore key 前缀 `<id>_`（`Preferences.kt:17-32`）、history 目录、`--model_dir`（`BackendService.kt:483,516`）、导航路由、列表 `key` | 两个模型条目 = 两份共享文件；同 id 两条目会 LazyColumn key 冲突直接崩（`ModelListScreen.kt:1152-1154`） |
| `packageFiles` 是 `List<String>`，`remote\|localName` | `Model.kt:136, 295-305`；解析重复在 `Model.kt:410-411` 与 `ModelDownloadService.kt:282-283` | 无「可选」语义 |
| 「已下载」要求**全部**文件存在且非空 | `isDitPackageDownloaded` `Model.kt:399-414`（只看存在 + `length()>0`） | 直接加一条 packageFiles 会把只有 FP8 的安装判成未下载 |
| marker 最后写，按 ditKind 而非按量化 | `ModelDownloadService.kt:315`、`markerFileName` `Model.kt:428-433` | 目录里无法从 marker 推断「装了/在用哪种量化」 |
| 下载器**按文件粒度幂等**（HEAD size 相符即跳过） | `ModelDownloadService.kt:287-309`，`remoteSize` `:338-349`；`.part` 由 `TempCleaner.kt:86-107` 扫 | 往已有目录补下**单个**文件天然可行，但没有「只下这一个」的 UI 入口（`startDownload` 永远发整个 `packageFiles`） |
| `RESERVED_MODEL_IDS` 挡自定义 id 与 rename | `Model.kt:1191-1209` | 新增内置 id 必须同步加入 |
| 已有「按目录内容生成选项 chips」范式 | `AdvancedSettingsDialog.kt:234-271` + `PatchScanner`（`Model.kt:39-67`） | 变体选择 UI 可照抄 |
| 每模型偏好的既有写法 | `Preferences.kt` `<id>_` key（17-32）、`clearPreferencesForModel`(250-268)、`migratePreferencesForModel`(219-248) | 变体偏好放这里，删除/改名自动正确 |

`app/src/main/assets` 下**没有**模型包清单 JSON，硬编码 DiT 文件名的地方只有：
`Model.kt`（281/289/297/282/290/299/301/801-803）、`main.cpp`（54-55 注释、356-362），
以及编译进 `libstable_diffusion_core.so` 的字面量。

---

## 8. 方案对比（2026-10-05 修订）

| 方案 | 改动面 | 结论 |
|---|---|---|
| ~~**A. 同目录双权重 + Q4_0 变体开关**~~ | `main.cpp:356` 嗅探回退 + `Model.kt` 拆 required/optional + 变体 chips + 去掉 `dit.gguf.delete()` | **不做**。代码便宜（§2 两处），但 §4 的 9× 与「整数量化反而更容易 OOM」使收益为负 |
| B. 兄弟模型 `qwen_image_2_1_q4` | 零 native 改动（gguf 映射成 `dit.safetensors` 名字即可，loader 按魔数识别） | 不做，同 A，还多一份重复权重 |
| **C. `diffusion=disk`（解码前先释放 DiT 权重）** | 改 `main.cpp:379-382` 一个字符串 | **2026-10-05 复活为首选修复**。原判定「每步重读 ⇒ 慢到不可用」是误读（§5.3）；真实代价约 12 s/请求，收益是解码前归还 6.79 GB。完整评估见 [解码 OOM 记录](./2026-10-05-qwen21-1024-decode-oom.md) §5 A |
| **D. mmap + CPU 侧 params 缓冲** | engine 一行 `enable_mmap` + DiT params backend 设为 `cpu`（§5.4） | **降级**：C 已经是字符串级且更对症；且 HTP 自己声明 `mmap_support = false`，这条路只在 `params=cpu` 时成立。留作 C 之后的备选 |
| **E. 免费旋钮** | 无 native 改动 | 降步数（§6）、腾常驻内存、必要时加 `--dit_vae_tile_size`/`--dit_params_backend`/`--dit_threads` CLI flag 以便不重编就能扫参数 |

---

## 9. 实验记录（均为 2026-10-04/05 实际做过的事）

| 动作 | 结果 |
|---|---|
| 方案 C 改动：`main.cpp` 去掉 qwen21 `te=disk` 特例，回落到 `all=disk` | 重编 core 通过，产物 11983448 字节 |
| 打 `./build-sm8850.sh debug basic` + `adb install -r` | Success（真机为无线 adb） |
| 判定 C 不合理（§5.3 逐次释放、§5.4 无 mmap），**revert** | `main.cpp` 与已提交版本 `git diff` 为空。⚠️ **这个判定当时没有真机数据支撑，且 §5.3 的推断后来被证明是错的**（§9 末、§5.3 二次修订）|
| 重编 core + 刷新 `core.json` | `libstable_diffusion_core.so` 回到 **11983544** 字节（与 `te=disk` 那次一致） |
| 打包 debug + 安装 | `adb install -r` → Success |
| 签名基线修正 | debug 变体不再复用 release keystore（`build.gradle.kts` 的 debug 不挂 `signingConfig`）；v3-only 写进 `AGENTS.md` |
| `te=disk` debug 包跑 1024×1024 | **~95% 时闪退**（跑到快结束，不是启动就挂） |
| 同一包、**默认两行提示词**跑 1024×1024 | 能出图 |
| 失败那次的条件补充 | 后台已清空；提示词**很长**；`use_img2img=true`（走 `llm_vision.gguf` 参考图路径） |
| 设备热状态 | 每跑一次机身很烫，要等一段时间才能跑第二次 ⇒ 有降频/散热变量 |
| 事后 adb 取证 | main buffer 只有 256 KiB 且已滚掉，crash buffer 0 B，dropbox 只有 3 条旧记录 ⇒ **当时无法判读** |
| `logcat -G 16M`（含 `-b crash`） | 无 root 即成功，已生效并清空；证据不再会滚没 |
| debug 包内置日志落盘 + 实时内存弹框 | 见 §10.2 A；`compileBasicDebugKotlin` 通过，已 `adb install -r` |

**这段方向推断已被 2026-10-05 17:33 的实测部分推翻，保留只为记录思路**：当时猜「峰值来自文本编码器
KV / `llm_vision` 参考图编码」。实测结果是——TE 那条路正常（conditioning 4.26 s、3739 MB
如期释放），**峰值在 VAE 整帧解码的 f32 激活**（0.84 + 0.56 + 1.125 GB）。提示词长度确实是
变量，但机制是「DiT 常驻激活随 token 数增长、把解码所需的那 1 GB 余量吃掉」，不是 TE 自己放不下。
完整证据见 [解码 OOM 记录](./2026-10-05-qwen21-1024-decode-oom.md) §2–§3。

> 踩坑：想用「在 `.so` 里 grep 字符串」验证编译进二进制的默认值是错的——
> AArch64 上 ≤7 字符的字面量会被 movz/movk 立即数直接物化，`.rodata` 里搜不到。
> 判据只能用编译日志 + 产物大小。

---

## 10. 真机 OOM 复现时的日志手册（回答「能靠日志继续查吗」）

**结论：能，而且不用重编任何东西。** 引擎的全部日志（含 ggml 层）已经被接到
App 的 stdout 管道上，默认级别就够看到失败发生在哪一步。

### 10.1 链路（已逐级核实）

```
sd core LOG_DEBUG/VERBOSE/INFO/WARN/ERROR
  └─ log_printf()  —— 没有任何级别门控，全量送到回调 (core/util.cpp:600-620)
ggml 内部日志
  └─ ggml_log_set(sd_ggml_log_callback, ...)  (pipeline/diffusion_engine.cpp:879)
     → util.cpp:622-639 映射成 LOG_VERBOSE/INFO/WARN/ERROR
  └─ DitEngine.cpp:324-328 engine_set_log_callback → sd_set_log_callback(forward_log)
  └─ PipelineDit.hpp:444-450 forwardLog：sd 级别 >=3(WARN/ERROR) → QNN_ERROR，其余 → QNN_INFO
  └─ SampleApp Logger → fprintf(stdout, "  xxxxxms [INFO   ] ...")  (LogUtils.cpp:41-44)
  └─ BackendService.kt:656-658 ProcessBuilder(...).redirectErrorStream(true)
  └─ BackendService.kt:678-682 每行 Log.i("BackendService", "Backend: $line")
```

- sd 级别枚举：`SD_LOG_DEBUG=0, VERBOSE=1, INFO=2, WARN=3, ERROR=4`（`stable-diffusion.h:148-154`）
- QNN 级别枚举：`ERROR=1, WARN=2, INFO=3, VERBOSE=4, DEBUG=5`（`QnnLog.h:65-72`）；
  默认 max level = **INFO**（`Logger.cpp:70-80`，`main.cpp:805` 初始化），所以 QNN_INFO 打得出来。
  `--log_level debug` 是**运行时** flag（`main.cpp:202/266-271`），但 App 不传它 ⇒ QNN 自身
  的 DEBUG/VERBOSE 看不到；sd core 的 DEBUG/VERBOSE 因为统一映射成 QNN_INFO，**看得到**。
- 每行自带 `文件:行号 - ` 前缀（`util.cpp:605`），定位到源码不用猜。

### 10.2 采两种日志

**A. App 内置（最省事）**

debug 包现在**默认就采集**（`ModelRunScreen.kt:1377`，门条件是
`BuildConfig.DEBUG || enable_log_capture`），不用再去设置里手动打开开关。
`LogCapture.start(context)`（`utils/LogCapture.kt:56`）做四件事：

1. 每次进生成页开一个新文件
   `/sdcard/Android/data/io.github.xororz.localdream.debug/files/debug/run_<时间戳>.log`
   （`getExternalFilesDir` 私有目录，不需要存储权限），**逐行 flush**，只保留最近 3 个
   （`LogCapture.kt:152`）。所以进程被杀也不会丢已写的行。
2. 写 `PREVIOUS run ended without a stop marker (killed?) … path=…` ——上一次运行没走到
   `stop()` 收尾（就是被杀的那次）时，直接指出那个文件的绝对路径（`:177`）。
3. 每秒追加一行 `MEM avail=/total free= app= backend=(pid N)`：backend 是**独立子进程**，
   `ActivityManager` 看不到它，所以按 `/proc/<pid>/cmdline` 里的
   `libstable_diffusion_core.so` 找它的 pid 再读 `VmRSS`（`:240`）。
4. 把框架记录的历史退出原因写进文件头（`reportPreviousExits`，`:195`，API 30+）：
   `EXIT <time> reason=LOW_MEMORY(3) status=9 imp=… rss=…MB pss=…MB desc=…`。
   **这是 App 内唯一能看到「自己被系统杀了」的通道**——`READ_LOGS` 是
   signature|privileged，debuggable 并不授予，所以 `logcat` 里 lmkd/AMS 的行本 App 读不到。
   `status` 即信号号（9=SIGKILL，6=SIGABRT，11=SIGSEGV）；`getSubReason()` 在
   compileSdk 37 的 `android.jar` 里**没有**，别用。

生成中点进度卡下方那行灰色的 `MEM …` 即可弹出实时日志框
（`ui/components/DebugLogDialog.kt:35`，500 ms 轮询 `LogCapture.tailSnapshot()`，
自动滚到底并显示当前文件路径）。离开生成页时：只有仍开着设置里的 `Capture logs`
（pref `enable_log_capture`）才弹原来的「抓到的日志」框并一键存到
`Downloads/LocalDream/local_dream_log_<时间戳>.log`（写文件在 `ModelListScreen.kt:623-665`）；
debug 默认只静默 `LogCapture.stop()` 收尾，免得每次生成完都被弹框。

采集用的是 `logcat --pid=<本 App 进程>`——backend 子进程的每一行是被 `BackendService`
重播成自己的 `Log.i` 才进到这份日志里的；同理 `logcat -c` 会清历史，且 `--pid` 过滤掉了
lmkd / ActivityManager / crash buffer，所以 **B 仍然要开**。

> 取文件：`adb pull /storage/emulated/0/Android/data/io.github.xororz.localdream.debug/files/debug/`。
> 部分 Android 11+ 固件会挡 shell 读 `Android/data`——那就顺手把设置里的 `Capture logs`
> 打开，这样离开生成页会弹框，一键把同一份内容存进 `Downloads/LocalDream/`。

**B. 外部 adb（判定「被系统杀」必看）**

```bash
# 先放大 buffer（这台 Honor 无 root 也允许；默认只有 256 KiB，一滚就没证据了）
adb -s "$DEV" shell logcat -G 16M
adb -s "$DEV" shell logcat -b crash -G 16M

# 设备走无线 adb，transport 名带空格：逐行读、必须加引号
adb devices | sed -n '2,$p' | cut -f1 | while IFS= read -r DEV; do
  adb -s "$DEV" logcat -c
  adb -s "$DEV" logcat -v threadtime > local_dream_device.log &
  adb -s "$DEV" logcat -b crash -v threadtime > local_dream_crash.log &
done
# 复现期间另开一个窗口循环采样内存
adb -s "$DEV" shell dumpsys meminfo io.github.xororz.localdream.debug
```

mDNS transport 会改名/消失，`nohup … &` 的子进程也会被回收：要长时间挂着就用带
重连的循环（每轮重新 `adb devices` 取一个还在的 transport），别一次性起两个进程。
（debug 包 applicationId 带 `.debug`，包名别写错。）

**更省事的做法：buffer 调大之后不必挂着采集循环。** 复现完（**不要重启**，且尽量在
十几分钟内）一次性 dump：

```bash
D=$(adb devices | sed -n '2,$p' | cut -f1 | head -1)   # transport 名带空格，必须加引号
adb -s "$D" logcat -d -b all > local_dream_after_crash.log
```

实测（17:33 崩溃 → 17:57 取，期间空闲）：`main` 16 MiB 里 17:30–17:34 窗口的
`lowmemorykiller` / `rpcmem_dma_alloc` 行仍在，共 4252 条命中。也就是说单次复现
**事后 dump 就够**；但连续跑多张（对照实验正是如此）会滚掉前面的证据，那种情况
还是要挂着实时采集。§12 的外部证据当时来自 PC 侧全量采集。

### 10.3 判读表

| 日志特征 | 判定 | 下一步 |
|---|---|---|
| 末尾有 `Backend: model_manager.cpp:xxxx - ...` 或 `GGML_ASSERT` / `failed to allocate` | 引擎内部分配失败，**没被系统杀**，且能看到请求的字节数与阶段 | 按报错所在模块（DiT 建图 / VAE 解码 / TE）对症调 §6 旋钮 |
| 最后一行停在 `generating image: NN%`，随后 `Backend process exited with code: 137` | **137 = 128+9 = SIGKILL** ⇒ lmkd/AMS 杀的；配合 `logcat` 里的 `lowmemorykiller`/`am_kill` 行确认目标 RSS | 常驻内存不足 ⇒ §5.2 的 8GB DiT 是大头；走 E（先降步数/腾内存） |
| `exited with code: 134` + `Fatal signal 6` | SIGABRT ⇒ assert / `std::bad_alloc` | 读 crash buffer 的 abort message |
| `exited with code: 139` | SIGSEGV | 看 crash buffer 栈顶帧 |
| 失败前出现 `num tiles : N, M`（`tiling.cpp:201`）或 VAE 相关行 | 峰值在**解码** | §6.1：`tile_size` 降到 32/16 才有真分块 |
| 失败在加载/首次 `prepare_params`/建图阶段 | **常驻权重**放不下 | `diffusion=disk`（§5.3，代价约 12 s/请求）或 `diffusion=cpu` 这类具名 params backend |
| 全程无异常，只是极慢后 UI 超时 | 不是 OOM | 看 `s/it` 与 `Warm DiT` 段耗时 |

`exitCode` 的来源：`BackendService.kt:684/692` 打印 `Backend process exited with code: N`，
JDK 对被信号终止的进程返回 `128+signum`。

若整个 App 进程都没了（不是 backend 退出），判据换成文件头那行 `EXIT … reason=… status=…`：

| EXIT 特征 | 判定 |
|---|---|
| `reason=LOW_MEMORY(3)` 或 `reason=SIGNALED(2) status=9` | lmkd 回收 ⇒ 就是内存；看同一时刻 `MEM avail=` 还剩多少 |
| `reason=CRASH_NATIVE(5)` | native 段错误/abort ⇒ 拉 tombstone（`desc` 里有路径线索，`getTraceInputStream()` 就是它） |
| `reason=EXIT_SELF(1)` / `USER_REQUESTED(10)` | 主动退出或被划掉，不是 OOM |
| 只有 `PREVIOUS run ended without a stop marker` 而没有对应 EXIT 行 | 系统还没写完退出记录（下次启动太早）；改看外部 adb 日志 |

### 10.4 复现时请一并告诉我的三件事

1. 分辨率 / 步数 / 是否 img2img（有无参考图会多一次 VAE encode）
2. 失败瞬间 UI 上的提示文案，以及 `local_dream_log_*.log` 的**最后 80 行**
3. 这台机器的 RAM 与当时前台/后台情况（`dumpsys meminfo` 里 App 的 Total PSS 更准）

有这三样就能定位到 §10.3 的某一行；没有 A 日志文件时，光靠 B 的 adb logcat 也够。

### 10.5 这套采集实测下来的收获（2026-10-05）

- `logcat -G 16M` **无 root 即生效**，且跨进程死亡保留（只有重启才清）；单次复现「事后
  `logcat -d -b all`」就够，连续跑多张还是要挂实时采集。
- App 侧文件逐行 flush ⇒ 进程被 SIGKILL 时**不丢证据**，本次崩溃的全部第一手数据来自它。
- `getHistoricalProcessExitReasons` 在下次启动写出的 `EXIT … reason=LOW_MEMORY(3)`，与外部
  日志的 `KILLED mUids=[9]` 互相印证——两条独立通道，误判概率低。
- **唯一盲点是 dma-buf：判内存只能看 `MemAvailable`，不能看 RSS**（机制见
  [解码 OOM 记录](./2026-10-05-qwen21-1024-decode-oom.md) §2 末尾）。

---

## 11. 修订后的执行顺序（2026-10-05 二次修订）

> 崩溃问题的实测与方案评估已经移到
> [2026-10-05-qwen21-1024-decode-oom.md](./2026-10-05-qwen21-1024-decode-oom.md)，
> 下面的顺序以那篇的 §5/§7 为准；本节只留「本文（量化调研）视角」的排期。

1. **降分辨率对照**（零成本，真机手测）：长提示词降到 896/960/832/768，确认瓶颈是不是
   「解码面积 × 常驻激活」这条线。判据表在 OOM 那篇的 §5 F 与 §7。
2. **方案 C 落地**（`diffusion=disk`，一个字符串 + 重编 core，**不 bump ABI、不动 engine**）：
   现在它排在分块方案之前，因为机制已逐环核实且无画质风险。验收要点（第二条 release 行、
   解码前 `avail` 是否跳回约 8 G、每张多几秒）在 OOM 那篇的 §5 A。
   ~~排期中~~ **2026-10-08 已由上游 `647dc6f` 落进代码并在 `feature/model-storage-upstream` 重编进包，
   真机验收仍未做** —— 进展只记在 OOM 那篇的 §7 第 2 条，别在这里更新。
3. **同一次重编顺手做**：1024² 纳入分块 + tile 降到 32、加 `--dit_vae_tile_size` /
   `--dit-vae-tile-above` / `--dit-threads` / `--dit_params_backend` CLI flag（§5.5）。
4. **免费的线性旋钮**：步数降到 4/1（`README.md:252`）；腾常驻内存；两次运行之间等机身降温。
5. 若 C 之后仍紧张 ⇒ 才谈 §5.4 的方案 D（mmap，只在 `params=cpu` 时成立）。
6. Q4_0 双量化（A/B）**不再排期**。

### 待实测风险

- §4.2 的 9× 来自 Z-Image / FLUX.2 Klein，不是 Qwen 2.1；但派发路径同源（§4.1）。
- Qwen 2.1 FP8 在本机的实际 s/it 还没量过（没有 baseline 数字，§4.3 的 23 分钟是折算）。
- 分段计算是「整图装不下才切段」（`ggml_runner.cpp:806-809`），段间权重预取由
  `DitEngine.cpp:161` 强制开启；分段本身会不会引入额外峰值尚未观测。

---

## 12. 崩溃实测与修复方案（已迁出）

本文原来的 §12「崩溃点已定位」整体搬到
[2026-10-05-qwen21-1024-decode-oom.md](./2026-10-05-qwen21-1024-decode-oom.md)：
内存账本（§2）、崩溃时间线（§3）、为什么没被兜住（§4）、方案 A–F 评估（§5）、
对本文 §5.3 的勘误（§6）、待办与判据（§7）。

迁出理由：一个概念一份权威正文。量化双支持（本文）与 1024² 解码 OOM（那篇）是两件事；
本文只保留 OOM 的**结论**（§1 第 4–7 点）和对 params_backend 的机制分析（§5）。
