# 系统执行协议草案 v0

状态：完整目标的语义设计；实际 APK 已有 AIDL v2 子集与 Kotlin 客户端，见 ipc/README.md。异步完成回调尚未实现。不向模型直接暴露 Binder。

## 最小接口

| 调用 | 输入 | 返回/事件 |
| --- | --- | --- |
| getCapabilities | 无 | 协议版本、实际已实现动作、显示能力 |
| beginSession | 客户端存活令牌 | 与调用者绑定的 sessionId；已有活动任务则 BUSY |
| observe | sessionId | 帧标识、时间、尺寸、旋转、displayId、可用前台信息及图像文件描述符 |
| submitAction | sessionId、requestId、动作、观察前提 | 快速返回是否接收；执行完成通过回调返回结果 |
| cancelSession | sessionId | 取消确认，清理待执行动作；不承诺回滚已发生的动作 |

异步结果带 sessionId/requestId，用于匹配。观察和动作超时有界，不阻塞 UI 线程。客户端死亡和会话取消均清理队列；活跃手势应尽快结束。取消请求不能排在整个任务动作队列后面。

## 动作集合

- `tap`：目标画面的像素坐标 x/y。
- `swipe`：起止像素坐标及受限持续时间。
- `key`：枚举 BACK/HOME，后续按真实需求扩充。
- `launchApp`：目标包名，验证是否成功进入目标应用。
- `typeText`：通过独立后台编辑会话提交文本，需要有效编辑目标和 editorGeneration。当前诊断输入法尚不能满足主屏与后台同时输入，不能把它直接当作已实现的生产方案；约束见[系统隔离集成门禁](../docs/system-isolation-integration.md)。

MVP 初期能力表可以仅声明 tap/swipe；未实现动作返回 UNSUPPORTED，不以空操作冒充成功。

动作使用观察画面的坐标系，并绑定 frameId、displayId、宽高、旋转及预期前台信息。图片缩放和裁剪由 Agent 还原到原始坐标。执行模块验证观察是否过期、显示状态是否变化，但不能保证验证与实际输入之间完全没有竞态；执行后必须再观察。

`wait`、`finish`、`askUser` 是 Agent 决策，不是系统输入动作。

## 状态与重试

区分 `ACCEPTED`、`EXECUTED`、`FAILED`、`CANCELLED`。EXECUTED 仅说明执行过程完成，不说明搜索、订单或页面状态成功。

错误至少区分：UNAUTHORIZED、BUSY、STALE_OBSERVATION、FOCUS_CHANGED、CAPTURE_UNAVAILABLE、UNSUPPORTED、TIMEOUT、DISCONNECTED、UNKNOWN_OUTCOME。

同一活动会话内按 requestId 去重。Binder 断开、进程重启或结果不明时不盲目重发输入动作，重新观察并恢复任务。历史会话标识不可跨进程重启复用。

## 需要验证的接口行为

实际实现后验证：取消期间迟到响应不执行；旧帧动作被拒绝；同 requestId 不重复输入；助手进程退出使会话失效；其他应用不能调用执行接口；大截图通过文件描述符处理；中文输入在焦点变化后停止。
