# Binder 协议 v2（开发预览）

`IExecutor.aidl` 是当前 APK 实际编译使用的接口；`contracts/executor.md` 是完整目标设计，两者不等价。

- `getCapabilities`：版本、ready、backend、缺失能力、支持动作。
- `beginSession`：绑定客户端 Binder token，返回 sessionId；当前返回 ISOLATION_NOT_READY，没有创建目标 App 或虚拟屏。
- `observe`：v2末尾追加；当前仍返回 ISOLATION_NOT_READY，无截图。成功协议约定见 docs/execution-loop-and-resource-gate.md。
- `getStatus/pauseSession/resumeSession/cancelSession`：只允许会话拥有者操作。取消后旧 ID 无效；不会自动恢复。
- `submitAction`：目前全部拒绝，没有未经保护的执行后门。

每次调用验证 signature 权限、调用 UID 的包名和 APK 签名。开发 APK 使用本机 debug 签名，不是生产授权方案。后续必须配置专用发布密钥和 ROM 安装信任。

Bundle 固定字段：code、message、sessionId、at（elapsedRealtime）。capabilities 另有 protocolVersion、ready、backend、actions、missing。当前 code 包括 ISOLATION_NOT_READY、BUSY、DISCONNECTED、PAUSED、CANCELLED、INVALID_REQUEST。所有者错误抛 SecurityException，会话关闭抛 IllegalStateException；客户端统一停止后续动作。

客户端死亡、解绑、服务销毁会清除当前空会话。未来接入真实目标进程时必须先完成 stopAndDrain，再释放系统保护；不能直接沿用当前 clear() 作为运行中任务清理。当前 bound service 未声明后台长期运行或前台服务能力。

## 编辑动作声明（客户端已接入，系统执行仍待实现）

支持动作必须同时出现在 `getCapabilities.actions` 和当前 `observe.editorActions` 中，并具有正数 `editorGeneration`。没有当前编辑能力声明就不向模型开放，不能仅凭曾经有过编辑焦点推断可执行。

动作kind：`text`（已有文本提交）、`edit_select`（start/end，UTF-16索引，相同表示光标）、`edit_delete`（before/after）、`edit_select_all`、`edit_copy`、`edit_cut`、`edit_paste`、`edit_undo`、`edit_redo`、`ime_search`、`ime_next`、`ime_done`、`ime_send`、`ime_hide`。均携带editorGeneration；复制/粘贴只有隔离剪贴板可用时才能声明。选择后提交text表示替换选区；不通过主屏全局剪贴板实现。

后端必须在dispatch时重新验证绑定、代次、范围和可用IME action；不支持的编辑器操作不得宣称支持。结果不明不可重试。当前ROM服务ready=false、PhoneBridge无独立编辑连接，因此这些新编辑动作不会在当前手机实验中开放，也未宣称真机输入验收通过。

## 文件与应用目标协议（应用适配层已接入，系统执行待实现）

`capabilities.actions` 可逐项声明 `select_file`、`open_app`、`open_link`。只有会话声明支持，且当前观察包含对应候选目标时，应用才向模型开放该动作。

`observe` 返回可选 `targets: ArrayList<Bundle>`（最多100项，targetId不能重复）：

- `kind`：上述三种动作之一。
- `targetId`：当前帧内的后端句柄，1–100个字母、数字、下划线或连字符。不得使用路径、URI、凭据或文件内容作为编号。
- `label`：供模型选择的显示名称，1–500字符，视为不可信数据。敏感页面不得提供目标或画面给模型。

模型动作只允许 `kind` 和 `targetId`（可带note）；应用提交时附带原有frameId/displayId等观察元数据，不接受任意URI、Intent、包名或路径。执行后仍须重新观察确认结果；选择文件不等于上传完成，打开链接不等于目标内容加载完成。

后端必须将句柄绑定到会话、帧及实际目标，重新校验当前选择器、文件访问授权、目标有效性和显示隔离。文件候选来自当前选择器允许的文件；应用候选来自已允许且可隔离启动的应用；链接候选来自当前页面且须经过目标解析与隔离检查。不能将过期句柄重新解释成另一个文件/应用，不能将隐式Intent落到主屏。失效返回STALE_OBSERVATION，不支持返回UNSUPPORTED，无法确认返回UNKNOWN_OUTCOME。

目前PhoneBridge不声明这些动作，Binder系统服务仍未就绪。适配器测试使用合成后端，只证明能力过滤、候选校验和Bundle传递，不证明真实系统文件选择或跨应用跳转已经可用。相册/系统选择器中通过现有点击选择可见项目的路径仍独立适用，但不等于上述语义接口已完成系统集成。

验证（2026-09-17）：68项JVM测试、debug构建与lint通过；Pixel 6上的TargetAdapterTest通过。合成后端逐项检查三种目标动作的frameId/displayId/targetId传递、候选消失及能力未声明时拒绝提交；没有文件路径、URI或标签进入动作Bundle。证据：build/runtime-service-evidence/target-adapter-test.txt。该测试在手机上运行应用适配器，不调用真实系统选择器。
