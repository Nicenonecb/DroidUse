# 执行模块

2026-09-23 更新：APK 已接入 ROM 会话、截图、点击/滑动/返回/文字插入，以及暂停、恢复和取消；助手通过 v3 接口指定目标应用。
`contracts/system-client` 自动生成 APK 专用 AIDL 客户端，避免 framework 隐藏类冲突。
实机验证入口：`python3 tools/phone/validate_rom_m2.py`（需先安装同签名的助手、Executor 及两个测试 APK）。
尚未提供完整编辑器所有权、跨应用目标枚举或 M3/M4 能力；工程标记仍不等于正式安全验收。

以下为早期未接通 ROM 时的历史说明。

`app/` 是 Kotlin bound-service APK，`contracts/ipc/` 提供真实 AIDL。每次调用验证签名权限、调用包与 UID；绑定客户端死亡、解绑或服务销毁时清除预览会话。当前安装在原厂系统，未作为特权系统服务集成。

当前能力固定为 `ISOLATION_NOT_READY`：没有原生资源保护或独立编辑连接时，不启动目标 App，不注入触摸，不调用模型。它不是完整运行时，也不能以现有会话清理代替未来的目标进程停止流程。

[runtime](runtime/README.md) 实现可单独测试的保护状态机、启动/失败清理协调器、独立编辑协议；系统适配仍待实现。`prototype-virtual-display/` 是另外的 ADB 实验，尚未接成 APK 自主执行链。

参见[实际 IPC](../../contracts/ipc/README.md)和[目标执行协议](../../contracts/executor.md)。
