# 自定义模型存储路径：不提上游 PR 的决定

> 日期：2026-09-22
> **决定：本 feature 仅 fork 自用，不向 `xororz/local-dream` 提 PR。维持现状，不做剥离/重构。**
> 以后不要再重新评估「要不要合上游」；若上游权限政策明显变化，再另开文档推翻本决定。

> **⚠️ 已被推翻（2026-10-08），按下文自己的约定另开文档，不改本文。**
> 上游自己做了这个功能：PR #328（`395d7b6`，`data/ModelStorage.kt` + `ModelStorageSection.kt` +
> `ModelMoveService`），并且**在 `app/src/basic/AndroidManifest.xml` 里声明了 `MANAGE_EXTERNAL_STORAGE`**
> —— 本文 §2.1「上游拒绝 all-files 权限，所以永不合并」的前提不再成立。
> 现在的方向反过来了：以上游 `ModelStorage` 为底座，把 fork 独有的「任意目录（Custom SAF）」叠上去，
> fork 自己的整套 `customPath` 管线（56 个调用点）删除。详见
> `docs/2026-10-08-model-storage-upstream-merge-plan.md`（含 §9 实施记录，已在
> `feature/model-storage-upstream` 落地，未推送）。
> 本文保留的价值是 §2 当时的评估理由与 §3 的沟通记录，以及「fork 独有档位」这个需求本身——
> 它现在是 `Location.CUSTOM`，而不是被上游采纳。

---

## 1. 决定

| 项 | 内容 |
|---|---|
| Feature | 自定义模型存储位置（Internal / App-specific external / Custom SAF 目录 + 自动迁移） |
| 去向 | **仅 fork 自用**，叠在上游之上，不进上游 |
| 动作 | **什么都不做**，代码与现状保持一致 |
| 适用范围 | 含 Custom 档、`MANAGE_EXTERNAL_STORAGE`、`resolveFsPathFromUri`、迁移与 hardening 的整套实现 |

相关实现与历史 review：

- 源头：`58b50af` / `b6627a7` `feat: models storage path customization + export/import + privacy hardening`（export/import 后已删）
- 后续：`fada07a`、`2cadbd1`、`fecd083`（review findings 1–8 已修）
- 问题清单：`docs/2026-09-19-custom-model-storage-fix-plan.md`、`docs/2026-09-20-custom-storage-path-review-fix-plan.md`
- Fork 基线：`0a24942 fork: local customizations on v3.0.0-alpha.2 base`（净增含本 feature）

---

## 2. 不提 PR 的原因（按权重）

### 2.1 权限面与上游政策直接冲突（决定性）

上游维护者对「过多权限 / all-files access」明确拒绝过两次：

| Issue | 诉求 | 维护者回复 |
|---|---|---|
| [#126](https://github.com/xororz/local-dream/issues/126) Custom save location | 自定义保存路径 | 需要 access to all files，*“Google will kill my app”* |
| [#182](https://github.com/xororz/local-dream/issues/182) Custom/Model by folder | 按文件夹导入模型 | *“This requires excessive permissions.”* |

本实现的 Custom 档依赖：

- `AndroidManifest.xml` 的 `MANAGE_EXTERNAL_STORAGE`
- `MainActivity.checkStoragePermission()` 启动跳「所有文件访问」设置页
- `resolveFsPathFromUri()` 把 SAF tree URI 启发式拼成真实路径，再用 `java.io.File` 读写（native 收绝对 `--model_dir`，无法只走 ContentResolver）

在 Android 11+ 上，写 `/storage/emulated/0/...` 自选目录的 File API 没有 all-files access 不可靠。  
这与 #126 / #182 是同一类权限问题，原样提 PR 大概率被同一理由拒。

### 2.2 改动面大且与 fork 其他定制纠缠

相对上游不只存储路径，还混有 RuntimeManager、NativeBuildInfo、WakeLock、Qwen、构建脚本、预编译 `.so` 等。  
`Model.kt` / `ModelListScreen.kt` / `TempCleaner.kt` / Download / RemoteHost / Backend 全链路都动过。  
即便手工剥离，PR 也难评、难合，维护成本高。

### 2.3 收益不对称

- 痛点（模型数 GB 占内部存储）用 **App-specific external（零新权限）** 就能解大半。
- Custom 自选目录是增强，不是刚需；为它赌上游权限政策不划算。
- 自用 fork 已完整可用，无上游合入收益。

### 2.4 曾考虑过的替代（已否决，仅存档）

| 方案 | 内容 | 为何不做 |
|---|---|---|
| A. 零特权子集 PR | 只保留 Internal + AppExternal，砍 Custom | 能提，但用户决定**不做任何事**；且本机模型在 `/storage/emulated/0/models`，PR 包用不了现目录 |
| B. 完整版 + 先开 issue 探口风 | 先问维护者是否接受 all-files | 维护者已有两次同类拒绝，预期悲观；不值得再耗 |
| C. 全走 DocumentFile / fd | 规避 File API 与 all-files | native 侧改动过大，不现实 |

---

## 3. 维持现状的含义

1. **不改代码**：Custom 档、`MANAGE_EXTERNAL_STORAGE`、迁移、TempCleaner 正向清扫等全部保留。
2. **本机继续用**：现模型目录（如 `/storage/emulated/0/models`）原地有效，不必迁移。
3. **同步策略不变**：仍按 `docs/2026-09-22-fork-upstream-sync-qwen21.md`——上游用 merge 进，fork 定制作净增补丁叠上；禁止 cherry-pick 上游。
4. **不要**再开「拆零特权子集提 PR」的工作分支，也不要改 Manifest 去掉 all-files「以便上游」。

---

## 4. 以后若有人（或未来的自己）又想提 PR

先读本文 §2。除非同时满足：

- 上游明确表示接受 `MANAGE_EXTERNAL_STORAGE`（或等价 all-files）用于模型存储；
- 或 native 侧已能不依赖真实路径（fd / app-specific 固定目录）；

否则结论仍是 **不提**。满足后另开新文档覆盖本决定，不要直接改本文。

---

## 5. 自用维护备忘

- Custom 路径的已知坑与修复记录见上述两份 fix-plan；变更存储路径相关代码时先对照。
- 改 `getModelsDir` / 下载 / TempCleaner / 迁移后，冒烟清单见 `docs/2026-09-22-fork-upstream-sync-qwen21.md` §10 第 5 条。
- `MANAGE_EXTERNAL_STORAGE` 注释里仍写着 export/import（功能已删）；**不要**「顺手」删权限——Custom 档的 File API 写入靠它。若将来改注释，写清用途是 custom models path。
