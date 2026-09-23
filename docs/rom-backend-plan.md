# DroidUse ROM 后端实施计划

2026-09-23 进展：M2 基础 ROM 与助手链路、锁屏和进程退出清理已完成实机验证；新增 framework 修复与验收边界见 [M2 联调记录](m2-validation-2026-09-23.md)。下文保留 2026-09-21 的实施记录，不代表当前全部状态。

更新时间：2026-09-21。目标为 Pixel 6（`oriole`）、Android 16、LineageOS 23.2。接口保留已经确认的 31 项能力；第一版只支持主用户 `userId=0`，不绕过 PIN、生物识别、受保护内容或 Android 的应用数据沙箱。

## 当前进度

| 阶段 | 状态 | 已完成的证据 | 仍需完成 |
| --- | --- | --- | --- |
| M0 接口与安全骨架 | 已完成 | 版本化 Binder 合同、能力 1–31 固定编号、请求验证器、会话状态机、死亡回收、限额和结构化错误；本地 runtime 42 个场景、ROM 工具 11 项和 Gradle 构建通过 | 真机负向鉴权仍归 M5 |
| M1 AOSP 编译接入 | 服务器编译完成 | `droiduse` 隐藏系统服务、`SystemServer` 启动、`SystemServiceRegistry` 类型化入口、包名/UID/证书校验、主用户限制、`dumpsys`、动态总开关、SELinux 类型与服务上下文均已接入；`framework-minus-apex services systemextimage` 与 `selinux_policy` 通过 | 刷工程 ROM 后验证启动、服务标签、错误调用方拒绝和 AVC 日志 |
| M2 屏幕与应用控制 | 工程 ROM 已打包 | Android 16 `ComputerControlSession` 适配器已通过 Soong 和完整 `m bacon`；支持目标 App 启动/切换/恢复、受信虚拟屏、PNG 截图、单点/长按/滑动/拖拽、多点事件、按键、文字、删除和回车；锁屏时拒绝并清理会话 | 实机验证显示/输入/截图；实体屏幕、语义 UI 树、任务结束/强停和可调整显示仍未实现 |
| M3 语义与系统操作 | 未实现 | 合同已预留能力和操作编号 | UI 语义、剪贴板、通知、包与权限、设置、连接、电源和媒体音频适配 |
| M4 蜂窝通话 | 未实现 | 合同已预留 Telecom 操作和 6 条音频流类型 | 通话控制、上下行捕获、PCM/TTS 上行注入和私密指令；必须使用 Pixel 6、SIM 与通话对端验证 Audio HAL |
| M5 加固与候选 ROM | 工程包完成，真机验收待做 | 保护心跳每 10 秒刷新，30 秒失效；分阶段清理可重试；工程签名摘要已写入产品配置；Aconfig 隔离开关已解析为 `true`；工程 ROM 已完整打包和校验 | 真机 SELinux 负向测试、正式证书、熔断回归与最终候选 ROM |

## 服务器实现现状

服务器源码位于 `/srv/rom/android`，五个受影响仓库均使用分支 `codex/droiduse-backend-v1`。当前代码基于以下提交：

| 仓库 | 基础提交 |
| --- | --- |
| `frameworks/base` | `781c37c3f3c8566177b85ff80637e7affc834858` |
| `system/sepolicy` | `885cc500f6078a766d1f6def5ce4c06c55841773` |
| `device/google/raviole` | `2bb485707a08808028accfb12bee131a026b69f6` |
| `vendor/lineage` | `895dbdb6c39cc3cb51b34958279a232f3c63f19d` |
| `build/soong` | `9aa045a2aef10b8089e32e847fed26d9aa3d61be` |

M2 全量受影响模块编译记录为 `/srv/rom/home/droiduse-build-runs/20260921T150603Z-m2-computer-control-r1`，执行 `m framework-minus-apex services -j16`，用时 8 分 53 秒并成功结束。随后移除截图模式隐式 `ALWAYS_UNLOCKED`，增量记录 `/srv/rom/home/droiduse-build-runs/20260921T152431Z-m2-lock-policy-r2` 执行 `m services -j16`，用时 3 分 05 秒并成功。编译产物已核验包含：

- `ComputerControlDroidUsePlatform`、`DroidUseManagerService` 和 `SystemSession`；
- `DroidUseContract`、验证器、全部 Parcelable 与 Binder Stub/Proxy；
- 电脑控制、严格活动隔离、文字输入、虚拟相机默认隔离、逐显示器动画和按设备区分录音权限六组只读开关。

虚拟设备使用自定义传感器、音频、相机、最近任务和默认设备相机策略。未安装虚拟传感器或相机时，它们向目标 App 暴露空设备集合；目标 App 不继承实体手机的传感器、麦克风和相机。框架不再给截图虚拟屏强制添加 `ALWAYS_UNLOCKED`；当前仅开放目标包名，且每次动作和保护心跳都会重新检查锁屏与会话所有权。

底层 `ComputerControlSession` 入口还由 Android 自身的 `android.permission.ACCESS_COMPUTER_CONTROL` 保护。该权限是 `internal|knownSigner`，当前产品没有配置任何外部已知签名证书；因此普通 App 即使知道隐藏 Binder 接口也拿不到权限。DroidUse 由 `system_server` 内部调用该入口，外部 Executor 只能先通过 DroidUse 的包名、UID、证书和会话检查。

完整 `m bacon` 首轮在 Boot JAR 包检查处正确拒绝了新包 `dev.droiduse.system`。`build/soong` 现已只对这个精确包名增加白名单，并且单独的 `platform-bootclasspath check boot jar packages` 已通过；没有加入 `dev.droiduse.*` 一类宽泛规则。

缓存后的整机重建在 `/srv/rom/home/droiduse-build-runs/20260921T162948Z-engineering-m2-r2` 成功完成，用时 11 分 52 秒。可刷写工程包只从 `/srv/rom/deliveries/droiduse-oriole-engineering-m2-20260921T084459Z/lineage-23.2-20260921-UNOFFICIAL-oriole.zip` 取用，大小 1,333,782,059 字节，SHA-256 为 `9c39583f0c3973d15d482d8486ad96ae8f7fe9210ed957825f46909dc654d154`。该独立交付目录已通过 `sha256sum -c`，并包含 ROM、刷写镜像、构建证据和五个可复现补丁。

另用全部 DroidUse 改动暂存后的干净源码成功重建回退基线，`m bacon -j16` 用时 42 分 26 秒。独立基线为 `/srv/rom/deliveries/oriole-clean-baseline-rebuild-20260921/lineage-23.2-20260921-CLEAN-BASELINE-oriole.zip`，大小 1,333,858,533 字节，SHA-256 为 `7c169ee9bcb33f98364873259794e12f9b97b88cac2e47df3f961d1c3d19b966`；ZIP 和交付校验均通过。最后一次构建后源码树的通用 `out/.../lineage-23.2-20260921-UNOFFICIAL-oriole.zip` 是这份干净基线，不能当作 DroidUse 工程包。

## 当前失败关闭边界

`/system_ext/etc/droiduse/sepolicy-version` 尚未加入产品镜像。这是有意的：策略已通过编译和 neverallow 检查，但还没有在真实手机上完成错误 UID、错误签名、错误包名、跨显示访问和 AVC 验证。在该标记不存在时，服务可以启动和报告状态，但拒绝创建执行会话，避免把“可编译”误报成“已在设备上安全验证”。

工程 ROM 刷入后先完成负向测试，再在 userdebug 的可写覆盖层中启用验证标记并测试 M2。最终候选 ROM 只有在这些测试通过后才内置正式标记。

## 当前缺口

1. **真实设备启动与隔离验证**：服务注册、SELinux 标签、证书拒绝、锁屏清理、截图和输入都需要 Pixel 6 运行证据。
2. **完整任务和实体屏幕控制**：当前只支持隔离屏上的启动、切换和恢复，不支持任务查询、移动、结束、强停或实体屏幕模式。
3. **UI 语义与系统操作**：能力 8、11–17、22 仍只有合同，没有 Android 内部服务适配。
4. **高速连续流**：单帧截图通过只读共享内存文件描述符传输；连续截图和音频环形缓冲、背压及时钟尚未实现。
5. **电话链路**：Pixel 6 厂商 Audio HAL 是否允许蜂窝通话上下行分别捕获和 PCM 上行注入尚未验证。
6. **长期升级身份**：当前是工程证书摘要。正式 ROM 必须固定发布证书，之后 APK 才能长期使用 `adb install -r` 升级。

## 后续执行顺序

1. 保持当前工程 ROM 和干净回退基线的独立交付副本；不要从会被后续构建替换的 `out` 通用路径刷工程包。
2. 连接 Pixel 6，只读确认型号、槽位、bootloader 与固件，再刷第一轮工程 ROM。
3. 验证服务启动、负向鉴权、SELinux、虚拟显示、截图、输入、崩溃清理和锁屏失效；通过后启用策略标记。
4. 根据实机结果完成剩余 M2/M3 适配，逐项保持“未实现即报告不可用”。
5. 用 SIM、普通蜂窝通话对端、听筒/免提/蓝牙三条路线完成 M4 音频探针。
6. 根据实机证据完成 Audio HAL 或 audioserver 路径、正式证书和最终策略，再集中构建候选 ROM。

## ROM 重编译边界

模型、OCR、ASR、TTS、任务规划、界面和业务逻辑放在可更新 APK 中，通常只需 `adb install -r`。只有新增或改变 framework/HAL 能力、SELinux、系统签名、产品文件或不兼容的 Binder 合同时才需要重编 ROM。

当前服务器工作不需要连接手机；开始运行和验收这些能力时才需要手机。可复现补丁及其校验值见 [`platform/rom/patches/server`](../platform/rom/patches/server/README.md)。
