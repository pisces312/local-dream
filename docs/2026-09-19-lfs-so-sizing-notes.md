# 项目提醒：Git LFS 禁用 与 DiT 引擎 .so 体积（2026-09-19）

## 1. public fork 上 Git LFS 走不通，不要再做 `lfs migrate`

**根因**
GitHub 的 Git LFS 存储按**仓库**归属，而 fork 的 LFS 对象归属**父仓库**
（`xororz/local-dream`）。只有根仓库能接收新 LFS 对象，**public fork
（`pisces312/local-dream`）一律禁止上传 LFS 对象**。

**实测现象（本次已踩）**
- `git lfs migrate import --include=...libdit_engine.so --include-ref=refs/heads/master`
  本地改写成功，`.gitattributes` 写入 LFS 规则。
- `git lfs push origin --all` 被拒：
  `batch response: @pisces312 can not upload new objects to public fork pisces312/local-dream`
- 若当时强行 `--force` 推送，所有 clone 都会拿到 133 B 指针、
  `git lfs pull` 报对象不存在 → 构建直接崩。

**回滚方式（已执行，仓库已恢复干净）**
```bash
git reset --hard origin/master        # master 回到非 LFS 的 8863f0b
# 工作树 .so 已确认为真实二进制（55,548,384 B），.gitattributes 无 lfs 行
```

**可选方案（按需）**
1. 维持现状（推荐）：53 MB blob 直接入库，GitHub 仅给 `GH001` 提示性警告，
   push/clone 不受影响，本地 `build-sm8850.sh` 无需改动。
2. 把 fork 转 **private**：private 仓库允许 LFS，迁移即可成功，但改变项目公开性质，需显式确认。
3. 放弃 LFS（即方案 1）。

**注**：`git lfs prune` 因安全策略（"未推送对象不可删"）会保留孤儿；
本次回滚后手动删除 `.git/lfs/objects/40` 与 `/f4` 两个孤儿缓存目录
（删除前已验证 `git lfs ls-files` 为空、无可达 ref 引用其 oid）。

## 2. `libdit_engine.so` 是正式版，体积基本不可减、且没必要减

**是否正式版 —— 是，release 等价构建**
- `app/src/main/cpp/dit/build.sh`：`CMAKE_BUILD_TYPE=Release` +
  `-march=armv8.7a+fp16+dotprod+i8mm`，无 `-g`。
- `CMakeLists.txt`：链接期 `-Wl,-s`（strip）+ `-fvisibility=hidden` +
  版本脚本限定单导出符号。
- `file` 实测：`... built by NDK r29 ..., stripped`。
- 与官方 APK 内同文件仅差 **0.3%**（55,548,384 vs 55,716,088 B），构成一致。

**体积构成（实测节区）**

| 节区 | 大小 | 占比 |
|---|---|---|
| `.rodata` | 49.1 MB | **87%** |
| `.text`（代码） | 5.4 MB | 10% |
| `.eh_frame` | 0.49 MB | ~1% |
| 其余 | <1 MB | — |

- 49 MB 的 `.rodata` 是**静态链入的 ggml-hexagon 内嵌 HTP 计算图
  （DSP 字节码）常量数据**——编译期直接编进 `.so`，不是调试符号
  （已 strip，故 `strip` 救不了），也非易删代码。
- 因引擎静态链接 `libstable-diffusion.a` + `ggml`（含 Hexagon 后端），
  这 49 MB 是固有体积；官方构建同为 55 MB，印证是 intrinsic size，非配置失误。
- 改 `-Os` 重编只影响 `.text`（5.4 MB），省不到 2 MB，杯水车薪。
- 真要减须上游级架构改动（不内嵌多版本 HTP 图 / 运行时从 assets 加载），非本地可改。

**结论**：保持现状即可，它是优化过的正式版，55 MB 推拉均正常，
`GH001` 仅提示性告警、不影响功能。为消该告警去折腾 LFS（且 public fork 还不允许）
反而会把仓库弄坏，不值得。
