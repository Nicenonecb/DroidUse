# 模型客户端与任务循环

Android library，Kotlin。ModelProfile 负责连接信息；ModelClient 支持短请求、文本计划及视觉请求；VisionTaskModel 解析受限 JSON 动作，并使用新画面复核结束请求；TaskLoop 控制观察、动作、再观察、暂停、取消、预算和失败退出。

生产使用 BinderTaskExecutor 适配，不通过 ADB。当前系统后端仍未就绪；debug 的 PhoneTaskExecutor 在手机上执行 OCR、批量阅读和本地 shell 通信，运行同一任务循环。ADB 仅负责一次启动调试进程及事后取证。旧 AdbTaskExecutor 保留用于历史对照，当前实验按钮不再使用它。单测替身只验证编排逻辑，不代表真实 App 验收。

支持两种兼容消息格式；不自动重试或跟随重定向，不写密钥日志。生产默认 HTTPS URLConnection，UI 不开放绕过证书验证选项。详情见 docs/execution-loop-and-resource-gate.md。

ADB 实验添加文字目标定位、加载等待、批量阅读和完成证据门禁。外层循环记录分阶段耗时；仅当后台章节证据齐全且新观察复核通过时标记完成。
