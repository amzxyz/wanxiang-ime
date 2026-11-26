# Android Rime SDK

正式 APK 使用 `native/evo-release.lock.json` 锁定的 librime-evo、Lua 与 octagram 源码提交。CI 逐 ABI 从源码编译 EVO，验证其来源、链接和文件哈希；Release 必须传入 `-PreleaseEvoSdkDir=/absolute/path/to/evo-sdk`，拒绝未锁定的本地 SDK。

Debug CI 使用 EVO dev 源码，开发者可通过 `-PlocalRimeSdkDir=/absolute/path/to/development-sdk` 验证本地 SDK；此参数不能用于 Release。

目前 `rime-sdk.lock.json` 仅作为过渡期依赖种子：`prepareRimeSdk` 下载并校验归档。EVO 构建仅复用与其 gitlink 一致且通过 SHA-256 校验的 LevelDB、Marisa、yaml-cpp，以及 OpenCC 数据和许可证，不链接归档中的旧 librime、Lua 或 OpenCC 静态库。正式 APK 内置引擎必须是 EVO。后续可迁移到独立的万象 Android 依赖 SDK。

构建步骤见 `.github/workflows/release.yml`，源码构建脚本为 `tools/build-evo-sdk.py`。审计工具 `tools/verify-direct-rime-apk.py` 与 `tools/audit-direct-rime-link.py` 校验 APK 与真实静态链接输入。APK 内的 `assets/bundled/rime-sdk-manifest.json` 记录内核来源。

`rime-prelude` 子模块提供标点与快捷键预设；用户仍可授权加载兼容的外部 Rime 引擎变体。
