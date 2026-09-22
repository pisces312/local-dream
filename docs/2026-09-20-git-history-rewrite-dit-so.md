# 把 55MB 的 libdit_engine.so 移出 git 历史（2026-09-20）

## 背景

`app/src/main/jniLibs/arm64-v8a/libdit_engine.so` 是 fork 自建的 DiT 引擎产物，
约 55MB（55,548,384 字节）。它 87% 是 `.rodata`（内嵌的 ggml-hexagon HTP 计算图常量），
编译期编入、非调试符号，**不可裁剪**。上游 xororz/local-dream 不提交该文件，
fork 提交它是为了省去每个人自建 Hexagon SDK 环境。

代价：每个 clone 多付 55MB，GitHub 报 GH001 大文件告警。曾评估过 LFS，结论是
public fork 禁用 LFS（见 `2026-09-19-lfs-so-sizing-notes.md`），遂改为**彻底不入库**。

## 执行动作

```bash
# 1. 备份（仓库外）
cp app/src/main/jniLibs/arm64-v8a/libdit_engine.so /d/3rd-party-projects/.so-backup/

# 2. 从全部历史移除（filter-repo 单文件脚本，未安装进 conda base）
python <git-filter-repo> --path app/src/main/jniLibs/arm64-v8a/libdit_engine.so \
       --invert-paths --force

# 3. 恢复 origin remote（filter-repo 会主动移除它，防止误推送）
git remote add origin git@github.com:pisces312/local-dream.git

# 4. 从备份恢复工作区文件，并加入 .gitignore
#    .gitignore: app/src/main/jniLibs/arm64-v8a/libdit_engine.so
```

执行前的 HEAD `8dc2da0` → 执行后 `962cd77`，仓库 `.git` 从 316MB 降到 292MB。

## ⚠️ 副作用：与上游的提交 hash 永久分叉

filter-repo 会重写 commit message 中引用的 hash（见 `.git/filter-repo/suboptimal-issues`），
因此**变化沿提交链传播**：本地 584 个提交中 **554 个换了 hash**，只有 30 个不变。

上游提交同样受影响：

| 原始 | 重写后 |
|---|---|
| `b33dafc`（上次分叉点） | `1c71cc7` |
| `a7dd738` | `2566c99` |
| `eb84521` | `41c41b7` |
| `69170b1`（v3.0.0-alpha.2） | `6553835` |

**后果**：`git fetch upstream` 拉回的是原始 hash 的上游，与本地重写后的历史
无法按 hash 对应。实测 `git merge-base master upstream/master` 已从 `b33dafc`
退化到 `83b7b698`，**下次 merge 上游会涉及 246 个提交**。

**但不必恐慌**：这 246 个提交的内容本地早已全部具备，三方合并时 ours/theirs
对每个文件的内容一致，因此**预期无冲突**，只是历史里会出现"同内容双 hash"的
成对提交。这次合并完成后 merge-base 就回到上游 tip，**后续同步恢复正常**。

原始→重写后的完整映射保存在 `.git/filter-repo/commit-map`（585 行），
如需追溯旧 hash 用它查。注意 `.git/filter-repo/` 不会随 push 上传，换机需另行保留。

## 后续同步上游的正确姿势

1. `git fetch upstream`
2. **预期看到一次"巨大但无冲突"的 merge**（246 提交），直接 `git merge upstream/master`
3. 若出现冲突，多半是本地定制改动与上游同区域改动相撞，按常规解冲突即可
4. 合并后 merge-base 回到上游 tip，之后一切正常

`push` 必须是 force（历史已重写）：`git push --force-with-lease origin master`
—— 执行前确认没有别人基于旧历史做了工作。

## 重建流程（新环境 / 换机后）

该文件已不在仓库中，新 clone 必须自建：

```bash
# 子模块必须 --recursive；窗口期注意上游以 patch 形式施加的 Hexagon 修复
git submodule update --init --recursive

# DiT 引擎（需 Hexagon SDK + Linux NDK）
HEXAGON_SDK_ROOT=~/hexagon-sdk-v6.6.0.0 \
ANDROID_NDK_ROOT=~/android-ndk/android-ndk-r29 \
bash app/src/main/cpp/dit/build.sh
```

`dit/build.sh` 会自动从 `include/DitEngine.h` 读取 `DIT_ENGINE_ABI_VERSION`
（当前为 **3**），并把 `libdit_engine.so` 落到 `jniLibs/`、把 DSP skel
（`libggml-htp-v79.so` / `v81.so`）落到 `assets/ditlibs/`。
后两者在仓库中**被跟踪**，重建后如有变化需要提交。

core 库（`libstable_diffusion_core.so`）仍入库（12MB，低于告警阈值），
Windows 侧构建，见 `rebuild-native.bat`。
