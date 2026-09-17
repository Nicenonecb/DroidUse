# 任务审计与敏感日志

当前已接入：

- `task-journal-*.jsonl`：步骤号、观察帧、动作待执行/结果、下一观察与requestId关联、最终状态。写入失败会停止，不能继续提交未记录动作。
- `task-checkpoint-*.json`：随审计事件原子更新步骤、帧编号、请求编号、回执状态及计数，ACTION_PENDING持久化完成后才允许提交动作。计数仅表示收到执行回执，不表示业务步骤已成功。冷启动关联runtime-state.runId展示中断详情，不创建执行会话、不重发旧动作；无效检查点不据此猜测进度。任务原文、凭据、模型回复和输入不进入检查点。
- Phone运行目录：`actions.jsonl`包含批量阅读翻页；`frame-links.jsonl`关联后帧；每个OCR JSON含frameId、文字、原图坐标、框、可空置信度。截图/OCR/模型/动作耗时继续分别记录。
- 模型决策日志只保存白名单动作类型、坐标、时长、验证布尔值；不存模型note/claim/evidence及文本输入值。
- 模型可附加scene：固定枚举的state/handling/reasonCode，记录弹窗、加载、无进展及本步处理意图。日志强制source=MODEL_REPORTED、verified=false；丢弃原文、额外字段、非法枚举，不能由模型伪造SYSTEM来源或已验证成功。省略scene不表示页面正常。普通/纯视觉模型均提示此格式，不增加额外模型请求，也不触发兜底。
- 已将脱敏决策日志接入debug/release任务服务；写入异常归为AUDIT_FAILURE并停止提交，不再吞掉错误后继续执行。RESUMED_FROM_FRESH_OBSERVATION只在恢复后新帧通过校验时记录；过期画面在提交前或后端拒绝时记录REOBSERVE_REQUIRED。重试/跳过的模型意图与执行回执、最终结果分开保存，不能把意图当成功。
- OCR发现密码/验证码/API Key等标识时，在截图和OCR落盘、模型调用之前抛出敏感页面事件，交给人工处理。此时只写原因码，不写命中的原文。
- 纯视觉模式不保存原始截图，因为没有本地敏感页面判断证据；它仍会将图像发给用户配置的模型。
- helper读取临时截图后立即删除临时文件；传输错误不记录服务端原始错误正文。最终结果文件排除已知API Key，检测到敏感标识则省略内容。
- 传输拒绝记录固定白名单reason（例如观察期间App变化、会话丢失、手机锁定），未识别原因统一REQUEST_REJECTED；仅读取最多2049字节错误响应用于比对，不保存任意错误原文。
- 特殊事件目前含OCR_EMPTY、TEXT_TARGET_MISSING_OR_AMBIGUOUS、PIXELS_UNCHANGED_AFTER_ACTION、LOAD_ERROR_TEXT_VISIBLE、SENSITIVE_SCENE。它们只表示已观察到的现象，不假装证明具体业务失败原因。
- PhoneBridge不再将所有截图标记为com.dragon.read：按虚拟屏幕读取实际topResumedActivity包名，截图前后不一致或无法确定时拒绝此次观察。PhoneTaskExecutor将包名写入帧元数据，连续观察到变化时记录APP_CHANGED（前后包名及frameId，不存完整系统dump）。离开番茄后不声明read_chapters，批量阅读中切换应用会停止。这是离散采样的前台Activity识别，不是所有覆盖窗口/弹窗的识别，也不能保证发现两次采样之间短暂出现又消失的应用。
- 动作提交抛出异常（包括超时/连接失败/回执解析失败）时，记录STAGE_FAILED及ACTION_RESULT=UNKNOWN_OUTCOME，保留requestId。会话允许保留时暂停等待人工检查；恢复必须重新观察，并将新帧关联到这次结果未知的动作，不自动重放。保护失效、资源压力或审计失败仍优先释放会话。
- 人工接管期间的画面、输入不落盘；返回AI后重新观察。已通过真实PhoneBridge接管测试，详见handoff-validation.md。

尚未完成：

- OCR敏感词是保守检测，并非可靠的密码字段识别；漏识、无标签密钥、图形验证码仍需更强的系统字段信号或明确敏感会话模式。不可宣称隐私要求全部验收。
- 多指轨迹及编辑范围参数已通过数字字段白名单记录；弹窗已接入模型报告字段，但真实App识别准确性/覆盖率未验收，不保证自动发现所有弹窗。日志浏览与导出、持久化恢复时的重新观察流程仍未完成。应用跳转已记录观察到的包名变化，不能宣称全量捕获。
- 未修改旧实验记录；旧目录不具备本轮新增的过滤保证。

验证记录：54项JVM测试通过，含动作前后帧关联、审计失败不执行、模型自由文本/输入值排除、敏感词识别。Pixel 6上PrivateSceneTest通过：通过真实PhoneBridge截图和tiny识别固定验证码测试页，确认抛出SensitiveSceneException、没有新增PNG、JSON/JSONL中不存在测试验证码、临时截图已删除。该用例只覆盖明确标签的测试页，不证明OCR漏识情况下的保护。构建与lint通过。

2026-09-17追加验证：63项JVM测试通过，debug构建及lint通过。Pixel 6上HandoffTest.lostReplyAfterRealInputRetainsSessionAndRequiresNewObservation通过（5.545秒）：真实后台点击获执行确认后，在任务边界注入超时；任务进入NEEDS_USER，未重复提交，人工接管仍可截图，恢复使用新帧并关联原requestId。证据：build/runtime-service-evidence/lost-action-reply-test.txt。这是主动注入丢失回执的测试，不是实际拔线/断网事故复现。首次测试漏调用requestHandoff，正确触发manualObserve的前置检查；修正测试流程后通过。

应用识别追加验证：纯Java解析器5项检查通过，覆盖主屏与后台display区分、空display、未匹配display和无display头的数据。68项JVM测试、构建及lint通过；Pixel 6的AppObservationTest及3项HandoffTest共4项通过（23.182秒），证据build/runtime-service-evidence/app-observation-handoff-test.txt。真实番茄→后台LabActivity跳转正确写入前后包名、frameId和OCR元数据，并撤销read_chapters能力。首轮回归曾出现初次observe返回409；为避免启动窗口未恢复时提前观察，begin增加am start -W并等待目标包实际恢复（最多3秒），保留截图前后包名一致性检查，复测通过。仍未测量新增系统查询对完整番茄链路总耗时的影响，旧110/158秒成绩不代表当前版本。

场景审计追加验证：74项JVM测试、debug/release构建和debug lint通过。新增用例覆盖模型来源/验证状态不可伪造、敏感原文及非法枚举丢弃、scene不改变动作许可、审计失败前不执行输入并释放会话、恢复日志在新观察之后、过期帧拒绝原因。未以这些合成输入测试声称模型实际识别弹窗准确率已通过。

检查点追加验证：79项JVM测试、debug构建及lint通过。Pixel 6冷启动CheckpointRestoreTest通过（0.115秒），预置合成PENDING检查点后启动真实TaskRuntimeService，显示第4步/2次执行回执/结果未知，保存INTERRUPTED且不启动任务。证据build/runtime-service-evidence/checkpoint-restore-test.txt。该用例验证持久化元数据加载与显示，不是实际业务执行中强杀后的自动接续验收。
