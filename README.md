# 万象输入法 · Wanxiang IME

基于 [librime-evo](https://github.com/amzxyz/librime-evo) 的开源 Android 输入法，支持全拼、双拼、形码、音形混合及其他 Rime 输入方案。

## 使用说明

安装与设备架构匹配的 [正式 APK](https://github.com/amzxyz/wanxiang-ime/releases)，在系统设置中启用万象输入法并选择为当前输入法。可在「中州韵引擎」中导入和部署 Rime 方案，或使用 [万象输入方案](https://github.com/amzxyz/rime-wanxiang)。切换或覆盖安装前建议备份用户数据。

## 开发与发版

- dev：Debug CI 使用 librime-evo dev，与 Release 共用仓库配置的稳定签名；包名 `com.amzxyz.wanxiang.ime.debug`。PR 和其他分支构建不接入正式密钥，产物标记为 `ci-test-key`，不用于日常覆盖更新。
- wanxiang：正式分支，Release 使用 `native/evo-release.lock.json` 锁定的 EVO、Lua 与 octagram 提交；包名 `com.amzxyz.wanxiang.ime`。
- 发版前在 `build-logic/convention/src/main/kotlin/Versions.kt` 修改 `baseVersionName`（X.Y.Z）并递增 `baseVersionCode`。Debug、Release 与原生构建均使用此版本来源；提交哈希独立记录。
- 在正式分支提交上推送与源码一致的 `vX.Y.Z` 标签。CI 拒绝不匹配的标签及相对历史正式标签未递增的版本号/版本码；四种 ABI 签名构建、单测及 APK 校验通过后发布。手动运行正式工作流只生成产物。
- [构建产物与 CI](https://github.com/amzxyz/wanxiang-ime/actions)；[原生 SDK 说明](native/README.md)。

## 隐私与数据

Rime 的本地输入方案与用户词典在设备上运行。更新检查、方案下载及用户启用的网络插件涉及联网；使用第三方服务时请查看对应服务的隐私规则。导出备份可能包含用户词典及配置，请妥善保管。

## 常见问题

- 正式版与 Debug 版包名不同，可共存。覆盖升级要求包名与签名一致。
- 输入方案部署异常时，先备份用户目录并核对方案与引擎兼容性；可在 [Issues](https://github.com/amzxyz/wanxiang-ime/issues) 反馈。

## 项目来源与许可

源自 [SandyYuR/fcitx5-android（上游项目）](https://github.com/SandyYuR/fcitx5-android/tree/fx-rime-only) 的 fx-rime-only 分支，保留原有 Git 历史、版权声明及第三方许可证。感谢 SandyYuR、fxliang、Fcitx5 for Android、Rime 及所有贡献者。见 [LICENSE](LICENSE)。
