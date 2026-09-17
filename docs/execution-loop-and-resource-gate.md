# 资源申请门禁与任务执行循环

状态：可编译、带测试的实现与 Android Binder 客户端；**尚未接入 Android 系统资源入口，当前手机后端仍拒绝执行**。不把 JVM 策略测试当成麦克风/相机的真实系统隔离证明。

## 新增代码

- `platform/executor/runtime/.../ResourceGate.kt`：资源申请前判定；目标 UID 在启动前进入隔离；未就绪、过期、撤销及跨显示请求拒绝，单次就绪期限最多30秒；麦克风、相机、音频焦点拒绝；音频只允许虚拟静音路由。
- `ProtectionSupervisor.kt`：客户端死亡、执行器死亡、保护服务死亡、显示销毁、主屏同 UID 冲突、心跳过期六种事件统一处理。先撤销资源准入，再停止/排空目标，最后释放；旧会话事件不影响新会话。
- `core/agent/.../TaskLoop.kt`：观察→模型决策→动作校验→执行→重新观察。步骤上限30、总时间180秒、观察最大年龄5秒；暂停后重新观察，停止丢弃迟到结果，未知动作结果不重发。
- `VisionTaskModel.kt`：接入已配置的视觉模型，解析受限 JSON 动作。当前动作：点击、滑动、中文文本、返回、请求人工、申请结束。结束须再取新画面并由模型核验具体证据，界面说明这是模型依据截图的判断，而非确定性业务证明。
- `apps/assistant/.../BinderTaskExecutor.kt`：真实 AIDL 客户端，截图通过有界文件描述符读取和 PNG 尺寸校验；协议/就绪状态检查不通过，不发送截图、不调用模型。没有 ADB 或伪成功回退。
- 助手增加“运行任务”，绑定当前模型配置。设置不在任务中途切换；模型计划功能保持独立。

## 必须遵守的系统接入约束

ResourceGate 是申请入口的策略组件，不是 Android 的实际拦截点。系统实现还需要接入 AudioService/AudioPolicy/AudioFlinger 的相关申请路径、录音分配入口、CameraService，以及进程/显示生命周期。执行 APK 持有一个普通对象无法阻止其他 App 获取资源。

1. Principal 的 UID、userId、displayId 来自系统 Binder/进程/虚拟设备关联，不能相信模型、Intent extras 或调用者自填 displayId。不能只校验一个被应用伪造的“后台标记”。
2. 所有目标 UID（包括派生/隔离进程）必须在执行前注册。共享前台 UID 不支持直接隔离；用户准备在主屏打开同 UID App 时，应先停止后台任务并确认排空，再把主屏操作交给该 App，不能以禁止用户麦克风作为“隔离成功”。
3. 保留 Android 原有权限校验。`admit()` 仅增加约束，不能授予缺少的权限。资源服务在同一策略锁内完成短分配事务，严格使用虚拟静音 sink；禁止在这个锁中进行 Binder/阻塞调用。`isCurrent()` 仅用于诊断，不能先检查后在另一个无关临界区分配。
4. 请求失败、虚拟路由丢失、后端重启不能退回物理扬声器或真实输入设备。资源 authority 需独立于助手/执行器存活；自身重启时在恢复隔离名单前保持相关请求关闭。当前内存 ResourceGate 没有实现跨资源进程分发和重启恢复存储。
5. `stopAndDrain` 必须覆盖已启动目标、排队启动和在途资源申请；只有确认后才能撤销路由/UID隔离。停止结果不明就保留阻断并重试清理。
6. 当前服务 ready 永远为 false。只有独立输入、资源钩子、原生故障处理都真实接入后才能声明 READY，禁止为了演示修改常量绕过检查。

## IPC v2 增量

在 AIDL 末尾追加 `observe(sessionId)`，保留原有方法顺序。成功返回 OBSERVED，携带 frameId、displayId、width、height、rotation、capturedAt（单调时钟）、packageName、可选 editorGeneration，以及只读不可变 PNG 文件 captureFile。客户端限制原始PNG不超过3MiB；拒绝无明确长度的管道。

每个截图必须生成新 frameId。目标 frame 的真实元数据由系统保存；不能把客户端提交的几何信息当成事实。submitAction 在系统执行前还要核验 session owner、保护期限、frameId、包名/窗口、显示代次和动作去重。

当前同步 submitAction 只在动作真正完成时返回 EXECUTED；ACCEPTED 不等于执行成功，客户端会将不认识的状态视为结果不明并停止。后续长动作若需要异步回调，必须显式扩展协议，不能静默改变此语义。观察与提交 RPC 的系统实现必须有界；Binder 调用本身不能保证远端执行超时。

beginSession 暂仍只有 token 参数；实际目标 App 的选择/启动、截图后端、动作注入及 IMMS 编辑连接需要继续接入。当前循环已有客户端和决策实现，不能声称已完成真实 App 全链路。

## 验证范围

- JVM：35组隔离/资源/故障场景；模型与任务循环23项单测。测试覆盖未知结果、旧帧、越界、主屏拒绝、错误编辑代次、暂停与停止竞态、新帧复核、模型动作白名单及视觉载荷格式。
- Android：真实 Binder 后端未就绪时 TaskLoop 停止且模型调用计数为0；原有加密存储、会话和页面生命周期检查继续运行。
- 没有用户真实密钥，未调用实际付费模型。没有编译或刷入 ROM，没有新增真实麦克风/相机实验。

视觉请求格式参考：[Claude Vision](https://platform.claude.com/docs/en/build-with-claude/vision)、[OpenAI Images and Vision](https://developers.openai.com/api/docs/guides/images-vision)。模型名称和服务地址由用户配置；协议兼容不代表所有模型都支持视觉或相同参数。

原始结果：`build/validation/loop-summary.json`、`resource-loop-tests.txt`、`loop-instrumentation.txt`；APK 位于原 Gradle 输出目录，已更新 Pixel 6 开发版。
