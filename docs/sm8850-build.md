# SM8850 专属构建

## 背景

默认 APK 打包了所有骁龙芯片的 QNN 运行时库（V68/V69/V73/V75/V79/V81），共 20 个 .so 文件，约 135 MB。

SM8850（第五代骁龙 8 至尊版）只需要 HTP V81 相关的 5 个库（约 28.5 MB），其余 15 个可以移除以减小 APK 体积。

| 文件 | 大小 | 说明 |
|------|------|------|
| `libQnnHtp.so` | 2.4 MB | HTP 通用加载器（所有版本共用） |
| `libQnnSystem.so` | 2.4 MB | 系统库（所有版本共用） |
| `libQnnHtpV81.so` | 12.9 MB | SM8850 HTP 主库 |
| `libQnnHtpV81Skel.so` | 10.2 MB | SM8850 NPU 侧 |
| `libQnnHtpV81Stub.so` | 0.6 MB | SM8850 CPU 侧 |

## 使用方式

```bash
# 设置签名环境变量
export KEY_STORE="D:\nili\my-git-projects\my-backup\backup-settings\my-android-release.keystore"
export KEY_STORE_PASSWORD="314159"

# 构建 SM8850 专属 release APK
./build-sm8850.sh release basic

# 输出: LocalDream_armv8a_2.7.0-basic-sm8850-signed.apk
```

## 实现原理

1. **Gradle 正常构建** — 包含全部 qnnlibs，源目录零改动
2. **Python `zipfile` 过滤** — 从未签名 APK 中移除非 V81 的 .so 条目
3. **zipalign + apksigner 签名** — 对精简后的 APK 签名

> **注意**: Windows Git Bash 环境没有 `zip` 命令，因此使用 Python `zipfile` 模块实现 APK 内文件过滤。

## QNN HTP 版本与芯片对照

| HTP 版本 | 骁龙芯片 |
|----------|---------|
| V68 | 8 Gen 1 (SM8450) |
| V69 | 8+ Gen 1 (SM8475) |
| V73 | 8 Gen 2 (SM8550) |
| V75 | 8 Gen 3 (SM8650) |
| V79 | 8 Elite (SM8750) |
| V81 | 8 Elite 2nd gen (SM8850) |
