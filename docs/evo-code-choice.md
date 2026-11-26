# EVO 编码选择对接

内核契约：[Prism 公共编码选择](https://github.com/amzxyz/librime-evo/blob/dev/docs/code_choice.zh-CN.md)。

Debug CI 每次 checkout librime-evo/dev，固定 EVO Lua 与 EVO Octagram 插件版本，使用已校验 SDK
的 LevelDB、Marisa、yaml-cpp 依赖库（检查与 EVO gitlink 一致），从源码编译
当前核心、内嵌 LuaJIT 与 Octagram。旧 SDK 的 librime、Lua、OpenCC 静态库不参与 EVO 链接。
OpenCC 数据仍作为原有资源保留。SDK manifest 记录核心 commit/tree、插件 commit、
Android 构建适配及依赖来源。通过真实最终链接探针后，交给 Gradle localRimeSdkDir。
正式 Release 使用 `native/evo-release.lock.json` 锁定的 EVO main 提交及两个插件提交；四 ABI 分别从源码编译并通过 `-PreleaseEvoSdkDir` 接入。CI 核对 SDK 来源、哈希、最终 APK 内的 EVO manifest 与实际链接输入，不允许旧内核回退。

JNI 仅从 get_code_choices 获取 label、replacement 和原始输入区间，复制后 free。
点击候选编码以当前快照的区间调用 select_code_choice，不创建影子输入；
所有选择由 EVO 自己验证。旧外部引擎缺少接口时不显示选项。
EVO Debug 和 Release 源码构建均要求头文件提供 `RIME_EVO_CODE_CHOICES`。

OrderedSpellingTabSdkTest 使用 build/dictionaries 下的 algebra，
覆盖严格子集收窄、非数字异长编码、相等集合不产生选项。
不再以空 tabs 为由跳过失败用例。CI 编译 instrumentation APK，
真机或模拟器运行需另行执行 `:direct-rime:connectedDebugAndroidTest`（现有内部 Gradle 模块名）。

Android LuaJIT 交叉编译只构建 `libluajit.a` 及 VM 对象，不构建独立 `luajit` 命令行程序；避免宿主机 strip 处理目标架构 ELF。CMake 脚本显式启用现代条件判断策略。

## 选择动作与界面边界

JNI action 4 的 Snapshot.handled 直接反映 select_code_choice 的返回值。无接口、越界或内核拒绝时返回 handled=false，仍读取并发布当前有效快照。Kotlin 的受保护选择入口仅在 ready && handled 时返回成功。负数标签 ID 不进入 JNI；原清除 action 7 的编号保留为拒绝操作，不再作为业务接口调用。

后续公共选择器设计建议（尚未实现）：选择器为键盘布局的通用组件（code_choices），不按方案 ID 或拼音类型推导关系。布局决定是否显示及位置，当前引擎返回的选项决定是否有内容。可使用布局的 auto/on/off 策略，默认 auto；如需要方案推荐布局，可使用独立的布局提示字段而非把方案名称写死在选择器中。选择器只消费现有快照与动作 API，无额外内核配置。
