# ROM 集成检查与后续验证

2026-09-23 进展：M2 基础 ROM 与助手链路、锁屏和进程退出清理已完成实机验证；新增 framework 修复与验收边界见 [M2 联调记录](m2-validation-2026-09-23.md)。下文保留 2026-09-21 的实施记录，不代表当前全部状态。

更新日期：2026-09-21。范围包括本地模块、服务器 LineageOS 23.2 源码和实际 Soong 构建；尚未刷机或执行真机测试。应用单元测试、ROM 工具测试和受影响系统模块编译已经完成，Lint 仍按用户要求不作为当前步骤。

## 构建入口

| 模块 | 当前依赖/入口 | ROM 接入缺口 |
| --- | --- | --- |
| assistant | Gradle 应用；ipc、agent、paddleocr、Compose、Activity、协程、ML Kit | 无 Android.bp；不能把 Maven 坐标直接当成 Soong 模块；依赖 BuildConfig、资源和模型资产生成 |
| executor | Gradle 应用；ipc、runtime | 无 Android.bp；需要 AIDL 生成、Manifest、资源及签名方案 |
| ipc | Gradle Android library；IExecutor.aidl | 无 Soong 模块；源码接入时须配置 AIDL 源目录和 framework 类型依赖 |
| runtime | Gradle library；Soong 名 droiduse-executor-protocol | 唯一现有 Android.bp；system_current/min SDK 35；当前只是状态机及接口，尚未被 Soong 应用引用 |
| agent | Gradle Android library；Kotlin、org.json | 无 Android.bp；源码接入需 Android SDK 类库 |
| paddleocr | Gradle library；ONNX Runtime、OpenCV、协程、core-ktx | 无 Android.bp；还涉及 AAR、arm64 JNI 和模型资产，不能只复制 Kotlin 源码 |

建议首轮使用 **Gradle release APK → Soong android_app_import 预装**，保留现有依赖解析与资源生成流程。模板在 `platform/rom/integration/Android.bp.example`，未启用。runtime 无需在预装方案中重复打包；以后做源码 Soong 迁移再逐个接入库。现有 runtime 模块名称虽然包含 protocol，但包名为 dev.droiduse.executor.protocol，源代码包为 dev.droiduse.executor；模块名、namespace、源码包不是必须相同，当前不随意重命名。

预装前必须确认：

- 两个 release APK 使用同一专用发布证书；debug 签名不作为发布信任。不需要为了当前 signature 权限而给助手平台签名或 shared UID。
- assistant 的 prepareTinyModels 所需 det/rec 模型和 inference.yml 已准备。正式 APK 不包含 debug 专用 builtin-model.json、bridge-token、phone-executor.jar；不把本地 secrets 打包入 ROM。
- arm64-v8a 原生库齐全，ONNX/OpenCV 能加载；最终 APK 内资源、模型、依赖和签名经过检查。
- 精确构建树的 SDK、Soong 属性及分区约束尚待验证；示例不代表可直接编译。

## 执行链的实际状态

ExecutorService 会探测隐藏的 `droiduse` 服务；ROM 侧每次 Binder 调用检查包名、UID、主用户和签名证书，会话绑定调用 UID 与死亡通知。ROM 已实现请求去重、旧 epoch/旧画面/错误目标拒绝、保护心跳和分阶段清理。Android 16 `ComputerControlSession` 适配器已能创建目标专用虚拟屏、启动目标 App、截图并注入触摸、按键和文字。

这仍不等于全部 31 项能力完成。当前实体屏幕、UI 语义、剪贴板、通知、包与权限、设备设置、连接、电源、连续媒体流和 Telecom/电话音频没有平台适配。服务会将这些能力明确报告为 `UNAVAILABLE`，不会用空实现返回成功。真机 SELinux 标记尚未启用，因此工程代码保持失败关闭。

## framework 补丁

`0001-scoped-editor-focus-query.patch` 是早期 WMS 焦点查询实验，参考 frameworks/base 提交为 `781c37c3f3c8566177b85ff80637e7affc834858`，当前服务器分支没有应用它。

服务器实际采用 Android 16 已有的电脑控制会话，并额外设置自定义最近任务、传感器、音频、相机和默认设备相机策略。对应 framework、SELinux、oriole 产品和 Aconfig 四组补丁见 `platform/rom/patches/server`；它们已通过 Soong 与 `git diff --check`。

边界：虚拟显示和输入仍需在手机上证明目标包限制、跨显示拒绝、锁屏撤销、窗口变化和中文复杂编辑行为。早期补丁保留作设计参考，不应与当前已编译实现同时应用。

后续真机验证清单：

1. 验证 null token、错误 UID/包名/签名、错误用户、旧 epoch、错误目标和旧画面均被拒绝。
2. 验证只允许目标包出现在虚拟屏，默认屏与其他 App 不可被误控；锁屏、会话取消和客户端死亡会撤销输入并销毁显示。
3. 验证截图帧率/尺寸/共享内存限额、多点事件、中文输入、删除与编辑动作。
4. 在 enforcing 下检查服务标签和 AVC；通过后再启用 `sepolicy-version` 标记。
5. 验证手机主屏的 IME、触摸、相机、麦克风和传感器不受虚拟会话影响。

撤回：补丁独立提交后用 git revert <该补丁提交>，重建对应 ROM 并重新验收。未提交且无其他改动时才考虑 git apply -R --check 后反向应用；不使用 reset --hard 清理混合工作树。刷回必须使用相容的已验证完整产物，不能只凭撤回源码认定设备已恢复。

## 手机与备份

当前没有连接手机。尚未确认目标机的型号属性、当前固件、OEM 解锁开关、bootloader 状态或槽位；服务器侧编译不依赖手机。

用户需解锁手机并接受本机 USB 调试授权，再只读核对 ro.product.model、ro.product.device、ro.build.fingerprint、ro.build.version.security_patch、ro.boot.flash.locked、ro.boot.slot_suffix。属性缺失不能解释为“已解锁”；OEM 解锁开关需手机开发者选项中确认，支持解锁与当前允许解锁不是同一回事。

刷机前备份照片/下载/文档并抽样恢复，核对联系人、聊天应用各自备份、验证器迁移/恢复码和 eSIM 恢复途径。应用私有数据不一定能用 adb 复制；不要将 adb backup 当作完整备份。解锁会清除数据，必须等设备条件和备份确认后另行操作。固件/恢复镜像具体要求沿用基线手册并在刷机前重新核对官方设备指南。

## 下一步顺序

继续完成可离线实现的平台适配 → 冻结工程接口并构建新工程 ROM → 连接 Pixel 6 核对设备条件 → 实机鉴权、SELinux、隔离显示和故障回收验收 → 电话 Audio HAL 探针 → 最终候选 ROM。真机安全标记通过前继续保持会话创建失败关闭。
