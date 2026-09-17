# 独立输入与资源隔离：系统集成门禁

状态：实现约束，未实现的 ROM 接口不能在能力表中宣称可用。目标系统源码尚未锁定，以下源码位置依据 AOSP android16-release；移植到 LineageOS 时必须重新核对。

## 独立中文输入

当前原型的定向按键不等于独立 InputConnection。给虚拟设备指定输入法，也没有在本机上建立能与主屏同时工作的中文编辑会话。虚拟剪贴板写入成功，但后台读取仍受全局顶层焦点检查限制。

需要系统维护独立编辑会话，至少绑定：调用者 UID、执行 sessionId、设备/显示 ID、目标窗口令牌、目标应用 UID、编辑连接代次和生命周期。`typeText` 必须在系统内解析目标，不接受模型提供的任意 Binder/窗口句柄；主屏 IME、主屏剪贴板、全局顶层焦点均不得改变。

接口语义：

- 获取当前后台编辑目标时返回不可伪造的会话句柄与 editorGeneration。
- 提交/替换文本、选区调整、删除和 editorAction 均检查会话、所属显示和连接代次；窗口切换、编辑器销毁、用户接管后立即失效。
- 中文、emoji、组合字符、换行和长文本分别验收；删除单位明确区分 UTF-16 位置与用户感知字符，禁止在代理层猜测选区。
- 应用未提供有效编辑连接时返回 UNSUPPORTED/NO_EDITOR；不临时把主屏焦点切走。
- 取消打断排队输入并撤销会话；已提交文本不能假装回滚。超时结果不明时重新观察，不能重复发送。

源码核对入口：[InputMethodManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/inputmethod/InputMethodManagerService.java) 的设备输入法选择、客户端焦点校验与连接生命周期；[ClipboardService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/clipboard/ClipboardService.java) 的 `isVirtualDeviceAndUidFocused` 使用顶层焦点显示。不能通过全局关闭焦点检查来修复。

## 资源申请前拦截

默认策略：后台不使用实体麦克风、实体相机、实体音频输出，不获取主屏音频焦点，不改变主屏音量/蓝牙路由。需要用户操作时暂停后台任务，由明确的接管流程移交。

执行顺序必须为：创建隔离会话 → 安装强制策略 → 验证路由/权限执行点就绪 → 启动目标应用。任一步失败不启动后台任务。音频服务或策略进程死亡时不能自动退回实体设备。

不能仅在助手 APK 中检查：目标 App 会直接调用系统资源 API。系统执行点应覆盖 AudioService/音频策略及 native 录音创建、CameraService、权限归属及应用跨屏迁移；目标 App 提供的 deviceId 不能作为唯一可信隔离标识。

设备级权限拒绝必须覆盖旧式 AudioRecord、MediaRecorder、现代带 Context 的 Builder、WebView/原生 SDK 等入口。测试已发现“checkSelfPermission 拒绝但旧式录音仍启动”的路径，因此权限查询通过不等于拦截完成。

同 UID/同进程同时在主屏和后台时，直接按 UID 撤权、静音、停止进程会影响主屏。必须验证可靠的会话归属；无法区分时暂停后台，不可声称已支持同 App 双屏并行。跨应用启动、后台服务和子进程都要纳入归属与策略传播。

相机测试除了枚举列表为空，还须尝试缓存的实体 cameraId；音频除了资源状态，还须验证焦点回调、路由、主屏录音 silenced 状态及接入蓝牙后的表现。

## ROM / APK 边界

ROM 提供上述不可绕过的系统执行点、身份校验、兼容版本和失效保护。APK 提供任务编排、模型适配、API Key 配置、可更新的动作策略和 UI。系统授权不能允许任意 APK 注入无约束代码或改写系统权限。

“一次基础 ROM、以后功能迭代更新 APK”作为验收约束：基础 ROM 通过全部系统能力及故障场景验证后才冻结。新功能限于既有接口能力范围；内核/框架漏洞和以后 Android 大版本升级不能由这个约束保证永不更新系统。

## 必测并发与故障场景

每个动作至少验证主屏输入/视频播放并行、后台取消与重启、编辑器/活动切换。涉及资源的动作还验证主屏通话/录音/相机占用、旧接口、同应用两屏、跨 App 跳转、服务死亡与资源归还。锁屏、屏幕旋转、蓝牙变化、系统弹窗另设组合测试。

115 个功能用例见 [完整验收表](acceptance-matrix.md)。单击成功不替代日期控件验收，双指事件投递成功不替代缩放/旋转效果，截图成功不替代模型理解正确。测试顺序是系统隔离 → 受控测试页 → 真实应用 → AI 闭环，每层保留独立证据。
