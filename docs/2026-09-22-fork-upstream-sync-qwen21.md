# Fork 与上游同步方案（Qwen Image 2.1 / 3.0.0-alpha.3）

> 日期：2026-09-22
> 目标：整理 fork 历史，便于以后持续合并上游；并合入 `feat: add Qwen Image 2.1 support`。

---

## 1. 背景与问题

本仓库是 `xororz/local-dream` 的 fork。历史上上游提交以 **cherry-pick / 平行提交** 方式进入 `master`
（消息相同、SHA 不同），中间还夹杂若干次 `merge: upstream ...`。

后果：

- `git merge-base master upstream/master` 停在远古祖先（`83b7b69`）。
- `master...upstream/master` 双方各挂 200+「幽灵提交」，直接 `git merge upstream/master` 会全量对打。
- 无法直观回答「fork 相对上游究竟改了什么」。

`git cherry upstream/master master` 证实：上游历史上的提交在 `master` 中**几乎都有 patch-id 等价物**
（大量 `-`），真正只缺 `440899f feat: add Qwen Image 2.1 support`。

## 2. 目标状态

```text
fork/rebuild
  └─ … upstream v3.0.0-alpha.2 (69170b1)     ← 与上游同 SHA
       └─ fork: local customizations        ← 相对 alpha.2 的净增补丁（1 或数个 commit）
            └─ merge/cherry-pick 440899f    ← Qwen Image 2.1（可独立回滚）
```

**验收标准（步骤 A）**：`git diff --ignore-submodules=dirty master fork/rebuild` 为空
（见 §5 例外）。即：重建后的代码与当前 fork `master` 内容一致。

**验收标准（步骤 B）**：在 A 通过后再吃进 Qwen，tree 等于「master + 440899f」。

## 3. 方案选型

| 方案 | 说明 | 结论 |
|---|---|---|
| A. 从 fork 点重开 + 逐个 apply 本地 commit | 历史最细 | 旧 commit 基于 v2.x 树，逐个 apply 冲突大、收益低 |
| **B. 从 fork 点 FF 到 alpha.2 + 施加净增补丁** | tree 可与 master 逐字节对拍 | **采用** |
| C. 当前 master 直接 merge upstream | 零改写 | 备选；历史仍脏 |

补充：**不要逐个 replay 早期本地 commit**（`66c02f3` 等）。它们的 diff 上下文是旧代码，
对着 3.0.0-alpha.2 施加会碎片化冲突。正确做法是提取 **相对 `69170b1` 的净增**
（`git diff 69170b1 master`），作为「fork 定制补丁」一次性或按主题落盘。

## 4. 执行步骤

### A. 在 alpha.2 基座上还原 fork 定制（tree == 当前 master）

1. 记录当前指针：`master` = `fecd083`（备份分支 `master-pre-sync`）。
2. `git checkout -b fork/rebuild 69170b1`
   - fork 点 → `v3.0.0-alpha.2` 为 fast-forward，无冲突。
3. 施加净增定制：以 `master` 树为准回填差异
   （或按主题 path 组分多个 commit：工程化/存储路径/DiT 自建/保活与 UI/文档）。
4. **对拍验收**：`git diff --ignore-submodules=dirty master fork/rebuild --stat`
   期望为空（§5 例外除外）。

### B. 合入 Qwen Image 2.1

5. `git cherry-pick 440899f`（或 `git merge 440899f`）到 `fork/rebuild`。
6. 冲突原则：
   - **保留 fork 定制**：自定义模型存储路径、RuntimeManager、构建溯源、WakeLock/`setpriority`、
     BackHandler 回 prompt 页、可选签名、`tokenizers-cpp` fork、`HistoryEntity.runtimeDir`、
     `.gitignore` 放宽、`dit/build.sh` 的 build-info 收尾。
   - **合入 Qwen 功能**：`qwen21` / `QWEN_IMAGE_2_1`、RGBA 全链路、`--qnn_lib_dir`、
     ABI 5、`dit_references.json`、透明图强制 PNG、`createQwenImage21Model` 等。
   - `Model.kt` 建议手工合（本地 `customPath` 面大），在本地签名上插入 Qwen 五处：
     package files / marker / scanCustomModels / createQwenImage21Model / RESERVED id。
7. `versionName` → `3.0.0-alpha.3`（保留 BuildConfig 溯源与签名逻辑）。
8. 四语 `strings.xml` 补 `qwen_image_2_1_description`。

### C. 子模块与产物重建

9. `stable-diffusion.cpp`：`6fe523f` → `c3352fb`（含嵌套 ggml）；**禁止** `submodule update --force`
   清 dirty（Hexagon patch 由 `dit/CMakeLists.txt` 幂等施加，dirty 是预期）。
10. 重建 `libdit_engine.so`（**ABI 3→5**）：WSL2 + Hexagon SDK 6.6.0.0 + NDK r29，
    跑 `app/src/main/cpp/dit/build.sh`。
11. 重建 `libstable_diffusion_core.so`：Git Bash 直调 cmake（见 `AGENTS.md`，勿走 PS 工具），
    NDK r28 + QAIRT 2.50.0.260828。
12. 打 APK：`build.bat release basic` 或 `build-sm8850.sh release basic`。

## 5. 对拍验收的合理例外

| 路径 | 原因 |
|---|---|
| `app/src/main/jniLibs/**`、`assets/qnnlibs/**`、`assets/ditlibs/**`、`assets/build-info/**` | gitignore 本地产物，不在 tree |
| `3rdparty/tokenizers-cpp` 等 submodule 指针 | fork 尖（`fix/rust-1.96-autoref`）属预期定制，diff 应有记录 |
| `.gitignore` | 以 fork（master）为准 |
| 行尾/空白 | 以 master 为准修掉 |

命令：

```bash
git diff --ignore-submodules=dirty master fork/rebuild --stat
```

例外之外仍有 diff ⇒ 补丁抽取不完整，先补齐再进步骤 B。

## 6. 真·本地提交清单（patch-id 过滤）

`git log --oneline --cherry-pick --left-only master...upstream/master --reverse`
（仅列主题代表，完整清单以命令输出为准；`merge: upstream ...` 与已等价的 cherry-pick 不需 replay。）

| 主题 | 代表提交 |
|---|---|
| 工程化/构建脚本/docs | `66c02f3` `3ff8b9d` `55ff102` `90cbd19` `b301e66` `cd6e9de` `0045a6f` `a53d6c6` `decb95d` |
| 模型存储路径定制 | `58b50af` `69e681d` `e20408f` `2cadbd1` `fecd083` |
| MNN/QNN 工具链与 so | `0983ab1` `32bd0bb` `29a4277` `cddb956` `7bf666e` |
| DiT 自建与溯源 | `b5cd39f` `3cb57f8` `12bbc36` `8becfff` |
| 后台保活 | `358da7e` |
| UI/交互 | `ef98115` `ea42706` |
| 权限/安全/配置 | `5b88297` `7f6dcc6` `8e9ff27` `86e0d29` `ae1af47` |
| 子模块 fork | `78244c7`（tokenizers-cpp rust-1.96） |

## 7. Qwen 2.1 功能要点（步骤 B 输入）

- 模型包约 10.8GB：`dit.gguf` + `llm.gguf` + `llm_vision.gguf` + `tokenizer.json` + RGBA `vae.safetensors`
- DiT ABI：`DIT_ENGINE_ABI_VERSION` **3 → 5**（`out_channels` / `llm_vision_path` / `params_backend=all=disk`）
- 输出可带 alpha；透明结果强制 PNG；upscale 单独缩放 alpha 平面
- CLI：`--type qwen21`、`--qnn_lib_dir`（DiT 进程内共用 /upscale）
- 标记文件：`QWEN_IMAGE_2_1`；`ditKind = "qwen21"`

## 8. 以后如何跟上游（红线）

```bash
git fetch upstream
git merge upstream/master    # 或补丁少时 git rebase upstream/master
```

- **禁止**再 cherry-pick / rebase 上游提交进 `master`（会再次制造平行 SHA）。
- 上游功能用 merge 进入；fork 定制只作为补丁叠在上面。
- 需要单独验证某上游提交时，开临时分支试验，不进 `master`。

## 9. 风险

- 改写/替换 `master` 历史：若需推送 `origin`，要 force-push，先与协作者确认；可保留 `master-pre-sync` 备份分支。
- `libdit_engine.so`（~55MB）不入库，每次 ABI/patch 变更必须用 WSL2 重建，否则运行时拒绝加载。
- Qwen 包 10.8GB + `all=disk` 参数换入换出，内存吃紧，真机验证需 12GB 级设备。
- 不要 `git submodule update --force` 清理 dirty，会抹掉 Hexagon 算子 patch。

## 10. 真机冒烟清单（合并+构建完成后）

1. z-image / klein 回归可生成
2. Qwen txt2img 出图，透明 PNG 保存正确
3. Qwen 原生编辑（base + reference）；无 `llm_vision.gguf` 时不允许编辑
4. Upscale 透明图 alpha 不丢；DiT 进程 `--qnn_lib_dir` 下 /upscale 可用
5. 自定义存储路径：下载/扫描/删除/迁移仍指向用户目录
6. 后台生成不冻结（WakeLock + setpriority）
7. 自定义模型目录 `QWEN_IMAGE_2_1` 标记可识别
