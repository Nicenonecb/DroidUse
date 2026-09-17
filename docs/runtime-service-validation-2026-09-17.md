# tiny 正式流程与任务服务验证

设备：Pixel 6，Android 16，调试 PhoneBridge 后端。尚未编译/刷入 ROM。执行在手机上，ADB 用于启动、安装、生命周期测试及取证。

## 完整阅读链路

证据目录：`build/phone-benchmark/phone-run-93142268/no_backup/`。

- 任务运行于 `TaskRuntimeService`，默认 PaddleOCR tiny。
- 结果 `COMPLETED`，158561 ms；前三章共49页，第50页为第四章边界（人工查看0056.png确认）。
- 书名《恋综对抗路，川渝甜妹有点疯》；最终输出含选书依据与逐章摘要。
- 全程59次 OCR 共45245 ms，约767 ms/次。该数包含本次冷加载；不是所有设备的性能保证。
- 执行中返回 Home；随后用 NEW_TASK|CLEAR_TASK 重建助手 Activity，再次 Home，任务继续读取。服务 dumpsys 显示 `isForeground=true`、`startRequested=true`。
- 此次不是与历史110秒记录的同书同页对照，也未验证主屏视频流畅度。

## 中断处理

再次启动任务后 force-stop 应用，重新进入：原子运行状态由运行中变为 `running=false, phase=INTERRUPTED`。服务不自动重启任务，也不重发旧动作。

初次验证发现运行提示被执行服务连接提示覆盖；已将 runtimeMessage 单独展示。中断状态在下次任务开始前保留。

注意：调试 PhoneBridge 是独立 shell 进程，应用被强杀后的隐藏屏幕释放仍依赖 helper 看门狗，不能称为即时进程死亡清理；后续需补齐断联/租约收尾。

## 检查

- 42项 JVM 测试通过，含模型超时分类且不泄露异常原文、不重试动作。
- debug APK 构建、lint 通过。
- 前一批 RuntimeOcrTest 已在主 APK 资源上验证中文识别、原图坐标、置信度、重复释放及释放后拒绝识别。

未完成：资源调度与视频并行压力验证、日志脱敏和现场保留全流程、完整动作能力表及实现、人工接管界面、进程恢复后的重新观察流程。不能据此认定全部 P0/P1 已完成。

## 资源保护增量验证

`ResourcePolicy` 与 `RuntimeResourceGuard` 已接入 PhoneTaskExecutor：500ms正常截图间隔、1500ms降速间隔；严重发热、低电量且未充电、内存压力进入 NEEDS_USER。日志记录数值、触发原因及时间。固定两线程 tiny，任务内串行识别，不缓存 Bitmap。

- JVM测试现45项通过；真机 ResourceGuardTest 通过，确认 UI_HIDDEN 不触发 critical 标记，RUNNING_CRITICAL 会触发。
- 端到端通过 ADB 注入 RUNNING_CRITICAL 回调：`phone-run-93545598`，1340ms后 NEEDS_USER；resource-events.jsonl记录1102ms时trimCritical=true、LOW_MEMORY，结果明确未重发动作。这是模拟内存通知，并非真实内存耗尽测试。
- 先前 `phone-run-93509541` 因上轮强杀后的旧helper会话未过期而HTTP409失败，保留证据。重启调试helper后完成上述验证。自动孤立会话清理尚需改进。
- 尚未进行真实发热/低电量压力试验、主屏视频并行或资源性能调优；阈值是初始策略，不能据此承诺无卡顿。

## 调试会话心跳

PhoneTaskExecutor 每3秒请求已认证的 `/heartbeat`，PhoneBridge 租约30秒。租约到期、总任务预算到期或 DisplayHost 退出时看门狗清理会话。请求入口也检查租约，过期动作不执行。任务结束关闭心跳线程；续期失败不重放动作。

`phone-run-93617778` 正常执行超过49秒（超过单个租约），采集正文6页后人为 force-stop 应用，用于验证孤立会话清理；此轮不能记作阅读完成。清理计时证据见 `build/runtime-service-evidence/lease-exit.json`。

真机结果：force-stop 后31.135秒确认 DisplayHost 消失；随后重新 `/begin` 成功并主动取消，未重启helper。证据 `lease-exit.json`、`lease-restart.json`。本次代码通过45项JVM测试和debug lint。该验证使用ADB转发调用端点做生命周期测试，正常任务续期代码运行在手机。

## 动作接口增量

- `ActionCatalog` 统一动作名和模型参数说明。`Frame.supportedActions` 限定当前后端能力，TaskLoop 在提交前拒绝未声明动作；Phone 后端直接提交路径也校验。
- PhoneBridge `/begin` 返回能力列表。新应用连接缺少声明的旧后端不会默认宣称它支持新动作。ROM/旧ADB适配器不宣称新增手势可用。
- 已加入双击（两次触摸间隔80ms）、长按（500–3000ms）、拖拽（按住0–1500ms，移动100–3000ms）。通过绑定虚拟屏幕的 MotionEvent 连续注入；异常尝试发送 CANCEL。
- `tools/phone/gesture_smoke.py` 可复现真机测试，LabActivity 记录 DOUBLE_TAP、LONG_PRESS 和拖动后滑块≥90，证据 `build/runtime-service-evidence/gesture-smoke.json` / `.log`。不是仅凭 EXECUTED 回包判定控件响应。
- 尚未完成用户列出的其余动作，特别是多指、独立文本编辑/IME、文件选择、跨应用和人工接管。

## 多指动作增量

`multi_touch` 支持两指同步轨迹，每指2–32个点且点数相同，总时长100–3000ms。任务层与helper均验证坐标、点数和时长。注入维持pointer ID 0/1，包含DOWN、POINTER_DOWN、MOVE、POINTER_UP、UP，异常尝试CANCEL。通过提供轨迹点可表达缩放、旋转和平移；目前不声称支持三指以上。

Pixel 6隐藏显示54实测：Lab接收两指最终缩放1.8倍、旋转90度、平移(40,50)像素；同时回归双击、长按和拖动滑块。证据仍为gesture-smoke.json/.log，脚本已扩展为六种手势。50项JVM测试通过，含不等长轨迹和非整数坐标拒绝。

期间夹具重复方法导致一次构建失败并误用了旧夹具，已修复并重建后重跑通过；bootstrap新增测试APK与内置helper的classes.dex一致性检查。实际App对多指手势的响应仍需按场景验收，当前证明的是完整协议到Android触摸接收链路。
