# 万象输入法 · Wanxiang IME

万象输入法是以 librime-evo 为目标核心引擎、面向全拼、双拼、形码与音形混合输入的开源 Android 输入法。

## 项目来源

基于 [中州鹅（SandyYuR/fcitx5-android）](https://github.com/SandyYuR/fcitx5-android/tree/fx-rime-only) 的 fx-rime-only 分支，保留原有 Git 历史、版权声明与子模块关系。感谢 SandyYuR、fxliang、Fcitx5 for Android、Rime 及所有贡献者。

## 开发方向

- [librime-evo](https://github.com/amzxyz/librime-evo)：核心引擎。
- [rime-wanxiang](https://github.com/amzxyz/rime-wanxiang)：输入方案及词库。
- 在内核统一实现九键、14 键、17 键、18 键及其他歧义布局的编码选择。
- 统一用户词、手动排序和多设备同步，前端仅承担展示与交互。

## 当前状态

首次迁移先保留上游锁定的 SDK，以验证独立安装与 Android 构建链路；Debug CI 已切换到 EVO SDK；前端 JNI 使用 EVO 公共编码选择接口，旧 SDK 构建不显示编码选择栏。应用图标采用临时独立 W 标识。内部 Java/Kotlin 与 JNI 命名空间暂保留，避免破坏已有原生接口。正式安装包 ID 为 com.amzxyz.wanxiang.ime，Debug 为 com.amzxyz.wanxiang.ime.debug，显示名称为「万象输入法 Debug」，可以同时安装。项目仅保留 debug/release 构建类型，不使用产品 flavor。Gradle 任务为 `:app:assembleDebug` / `:app:assembleRelease`，输出为 `app/build/outputs/apk/debug/` / `app/build/outputs/apk/release/`。CI 下载产物为 `wanxiang-debug.apk`。

wanxiang 为正式分支，dev 为开发分支。Debug APK 从 [Actions](https://github.com/amzxyz/wanxiang-ime/actions) 下载，验证通过后再发起 dev → wanxiang PR。当前不发布正式签名包。

## 许可

保留上游 LGPL-2.1 许可及第三方组件的独立许可证，见 [LICENSE](LICENSE) 和应用内许可说明。
