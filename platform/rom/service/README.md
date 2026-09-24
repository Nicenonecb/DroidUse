# DroidUseManagerService source payload

本地 M3 另含 `DroidUseEditor` 和 `DroidUseClipboard`：普通文本框的连接身份校验、选区、复制/剪切/粘贴，内容限于会话内存。需要 0011 framework 补丁及 `DroidUseEditorActions`，尚未通过 Soong 或实机测试。

M2 版本已接入锁定的 LineageOS 23.2 `frameworks/base`，并在 Pixel 6 `lineage_oriole-bp4a-userdebug` 配置下通过真实 Soong 编译。2026-09-24 新增的 M3 目标与窗口观察实现仍待服务器编译、实机验收，见 [M3 记录](../../../docs/m3-system-operations.md)。

系统服务负责：

- 发布隐藏的 `droiduse` Binder 服务并报告版本化能力；
- 对每次调用核对 Executor 的包名、UID、主用户和 SHA-256 签名证书；
- 在单一控制线程上串行管理会话，拒绝旧 epoch、旧画面和错误目标；
- 每 10 秒更新资源保护证明，证明超过 30 秒或设备锁屏时停止会话；
- 客户端死亡、显式关闭和阶段失败时依次停止目标、关闭流并释放资源，失败阶段可重试；
- 通过 `ComputerControlDroidUsePlatform` 使用 Android 16 的受信电脑控制虚拟屏，执行目标 App 启动、截图和输入；
- 通过 `dumpsys droiduse` 输出健康状态、能力和当前会话信息。

产品集成安装：

- `/system_ext/etc/droiduse/executor-cert.sha256`：一个小写 SHA-256 发布证书摘要；
- `/system_ext/etc/droiduse/sepolicy-version`：只在 enforcing 策略和负向调用测试通过后安装的非空版本标记。

当前工程配置只安装第一项。缺少第二项时，服务保持失败关闭并拒绝创建会话。不得添加 permissive 域，也不得用返回成功的空实现代替未完成能力。

当前平台适配支持能力 4、5、7、9、10、11 和 23 的完整或降级子集；其余能力明确报告 `UNAVAILABLE`。具体边界、服务器编译证据和后续顺序见 [`docs/rom-backend-plan.md`](../../../docs/rom-backend-plan.md)。
