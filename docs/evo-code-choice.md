# EVO 编码选择对接

内核契约：[Prism 公共编码选择](https://github.com/amzxyz/librime-evo/blob/dev/docs/code_choice.zh-CN.md)。

Debug CI 每次 checkout librime-evo/dev，固定 EVO Lua 与 EVO Octagram 插件版本，使用已校验 SDK
的 LevelDB、Marisa、yaml-cpp 依赖库（检查与 EVO gitlink 一致），从源码编译
当前核心、内嵌 LuaJIT 与 Octagram。旧 SDK 的 librime、Lua、OpenCC 静态库不参与 EVO 链接。
OpenCC 数据仍作为原有资源保留。SDK manifest 记录核心 commit/tree、插件 commit、
Android 构建适配及依赖来源。通过真实最终链接探针后，交给 Gradle localRimeSdkDir。
正式 release 仍走原有固定 SDK 发布机制，不自动替换。

JNI 仅从 get_code_choices 获取 label、replacement 和原始输入区间，复制后 free。
点击候选编码以当前快照的区间调用 select_code_choice，不创建影子输入；
所有选择由 EVO 自己验证。旧外部引擎缺少接口时不显示选项。
EVO debug 构建要求头文件提供 RIME_EVO_CODE_CHOICES。

OrderedSpellingTabSdkTest 使用 build/dictionaries 下的 algebra，
覆盖严格子集收窄、非数字异长编码、相等集合不产生选项。
不再以空 tabs 为由跳过失败用例。CI 编译 instrumentation APK，
真机或模拟器运行需另行执行 connectedDebugAndroidTest。
