# 执行模块

`app/` 是 Kotlin bound-service APK，`contracts/ipc/` 提供真实 AIDL。每次调用验证签名权限、调用包与 UID；绑定客户端死亡、解绑或服务销毁时清除预览会话。当前安装在原厂系统，未作为特权系统服务集成。

当前能力固定为 `ISOLATION_NOT_READY`：没有原生资源保护或独立编辑连接时，不启动目标 App，不注入触摸，不调用模型。它不是完整运行时，也不能以现有会话清理代替未来的目标进程停止流程。

[runtime](runtime/README.md) 实现可单独测试的保护状态机、启动/失败清理协调器、独立编辑协议；系统适配仍待实现。`prototype-virtual-display/` 是另外的 ADB 实验，尚未接成 APK 自主执行链。

参见[实际 IPC](../../contracts/ipc/README.md)和[目标执行协议](../../contracts/executor.md)。
