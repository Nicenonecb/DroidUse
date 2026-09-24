# M3 系统操作：第一批本地实现

最新验收：r8 已刷入 Pixel 6；本批剪贴板、生产 APK 适配器、跨应用往返、真实文件选择器及四项 M2 回归，共 8 项真机测试通过。范围、候选限制与未测边界见 [M3 实机验收](m3-device-validation-2026-09-24.md)。下文为此前实现和集成阶段记录。

后续状态：2026-09-24 15:51，合并蓝熊 UI 的 r8 已通过 framework/services 和完整 ROM 编译、M3 DEX 与品牌产物检查，退出码 0，详见 [合并构建记录](m3-ui-integration-r8-2026-09-24.md)。以下为此前本地实现阶段记录；新 ROM 尚未刷入，M3 实机验收待完成。

2026-09-24。用户要求开发 M3，同时避开服务器正在进行的 ROM 静态资源编译。本轮只修改本地源码、构建 APK 和运行本地检查；没有连接服务器、同步源码、启动 ROM 编译或操作手机。

## 范围与状态

第一批接通正式 ROM Binder 路径的跨应用目标、系统选择器和会话文本剪贴板，复用助手现有动作与逐帧能力过滤。当前是待 Soong 编译和实机验收的实现，不能将 APK 编译成功计为 ROM 能力验收。

- ROM 从隔离屏的实际聚焦窗口取得包名、任务 ID、窗口令牌和受保护标志。窗口改变后更新代次；截图前后窗口不一致则拒绝该观察。
- ROM 每帧签发最多 100 个 `open_app` 目标句柄。句柄只在当前观察有效，重新观察、暂停或提交动作后失效。模型不能指定包名、Intent、任务或显示。
- 候选来自可启动的普通 launchMode 应用；排除助手、Executor、权限控制器、设置、SystemUI 和正在其他显示可见的 UID。会话最多接纳 32 个包（含起始应用和系统选择器）。
- 切换只复用当前隔离显示内的目标任务；新任务使用 NEW_TASK/MULTIPLE_TASK。切换后重新观察实际前台，不能把启动请求已接受当成业务完成。
- 通过系统解析器识别 DocumentsUI/Photo Picker，只有系统包可以加入初始选择器范围。文件选择器由目标应用打开，文件授权、结果回传仍由 Android 执行；当前页面确认为选择器时才声明 `select_file_at`。
- 输入或切换前检查当前窗口、显示、帧及 5 秒时效；检查会话中的应用是否在其他显示可见。保护心跳检测到同 UID 冲突后回收会话。
- 受保护窗口不提供应用候选或选择器动作，也拒绝自动输入。原有安全截图路径继续排除安全图层。

## 本批未开放

`open_link`、按文件名签发 `select_file`、主动打开文件面板、撤销/重做及完整 IME 操作、通知、权限修改、设置/连接/电源控制、媒体音频仍未接通。保持不可用，后续按实际任务逐项扩展。单任务/单实例应用不作为跨应用候选；第三方选择器不声明系统选择能力。

## 本轮追加：会话文本剪贴板

之前 APK 已有 `edit_copy/edit_cut/edit_paste` 动作定义，但正式 ROM 路径没有执行后端，也没有真实编辑连接身份。因此仅把动作放进能力列表不能实现复制粘贴。本轮补齐以下路径：

- `edit_select`、`edit_select_all`、`edit_copy`、`edit_cut`、`edit_paste`，以及带真实编辑器校验的 `text`。
- 从当前隔离显示的 InputMethodManager 连接探测窗口令牌、应用 UID、显示、连接 session ID、选区；每次观察签发新编辑代次。执行前核对连接 Binder，应用线程执行时再次检查视图焦点、窗口焦点、连接代次、选区和截止时间。
- 只开放普通可编辑 `TextView/EditText`。密码/可见密码/数字密码/密码 transformation、受保护窗口、正在组合输入的编辑器均拒绝。WebView、Compose 和其他非 TextView 编辑器暂不开放文本动作；这是相对于旧 M2 盲插入路径的明确限制。
- 复制取得选区的纯文本；剪切取得文本并替换选区为空；粘贴向当前已验证编辑器提交会话内文本。每条最多 4000 个 UTF-16 code unit；没有选区不声明复制/剪切，没有会话内容不声明粘贴。
- 内容只保存在 system_server 的该会话对象，不读写 Android ClipboardManager，不写日志、磁盘、观察数据或模型响应。可随当前会话切换应用使用；暂停保留内容但废弃编辑身份；停止、关闭、死亡清理时清空。
- 远程任务开始前检查 1 秒截止时间，系统等待至多 1.2 秒。超时或无法确认的修改返回未知结果且不重放；复制/剪切失败清空旧内容，避免误粘旧文本。已经开始执行的自定义编辑器调用无法强制撤回，必须通过新观察核对结果。
- 这是 **AI 动作使用的会话剪贴板**，不接管应用长按菜单；原生菜单复制不会自动同步进来，也不支持图片、文件、富文本或读取主屏剪贴板。原生 Android 剪贴板行为需要另外实测，不能由本实现推断其隔离性。

APK 与 framework/service 配套更新；可以并入下一次 ROM 构建一次集成。已经在运行的服务器构建不包含这些本地新增源码。

主屏与隔离屏同 UID 并发、窗口切换竞态、选择器返回、非标准启动模式、厂商应用兼容性仍须实机验证；上述检查不是全面资源隔离验收证明。

## 协议兼容

`Observation` 末尾追加可空 `targets`、`scopedActions`、`editorActions`，新增 `ActionTarget`，操作编号追加 `APP_OPEN_TARGET=107`、`INPUT_COPY/CUT/PASTE/SELECT_ALL=313..316`；既有事务、字段顺序和编号不改。新 APK 在旧 ROM 上继续只声明原有 M2 动作，只有 ROM 返回 `SCOPED_LAUNCH_AND_PICKER` 才开放目标动作，返回 `SCOPED_TEXT_CLIPBOARD` 才开放编辑动作。旧 ROM 缺少 `editorActions` 时兼容为 text；新 ROM 返回空数组时严格禁用。每帧继续按真实编辑器状态过滤。

## 后续集成

等当前服务器构建完成并归档后再集成，集成前复查服务器源码差异与构建锁，避免覆盖静态资源改动。补丁以锁定的 framework 提交 `781c37c3f3c8566177b85ff80637e7affc834858` 为依据，在既有 0002、0007、0008、0009 基础上追加 0010、0011。

`tools/linux/stage-rom-backend.py` 会把本轮合同、服务源码、framework 辅助源码和 0010/0011 补丁放进独立目录。集成时须同时更新：

| 本地/暂存内容 | framework 目标目录 |
| --- | --- |
| `contract/src/main/aidl/dev/droiduse/system` 与对应 Java | `core/java/dev/droiduse/system` |
| `service/src/com/android/server/droiduse` | `services/core/java/com/android/server/droiduse` |
| `framework/src/com/android/server/wm` | `services/core/java/com/android/server/wm` |
| `framework/core/java/android/view/inputmethod` | `core/java/android/view/inputmethod` |
| `framework/0010-m3-window-and-picker-routing.patch` | 在 `frameworks/base` 中检查并应用 |
| `framework/0011-m3-isolated-editor-clipboard.patch` | 在 `frameworks/base` 中检查并应用，追加远程编辑 AIDL 方法 |

已有框架目录采用 Java/AIDL 源码通配构建，但仍以真实 Soong 结果为准。先编译受影响模块，成功后再构建完整 ROM；刷机仍遵守 AGENTS.md 的实际写入前暂停规则。

## 验证记录与待验收项

- 本地 Executor APK 与测试 APK 构建、Executor Lint 通过。
- 系统协议 7 项与助手 84 项 JVM 测试通过；新增测试拒绝在目标句柄动作中夹带包名、任务、显示和无当前画面的请求，以及无编辑代次、注入粘贴文本、非法选区。
- `DroidUseClipboardTest` 主机测试覆盖会话隔离、4000 字符边界、失败清空、关闭后不能恢复内容。framework 编辑辅助类已通过 SDK 36 的 javac 编译；这不替代完整 framework 编译。
- ROM 工具测试 11 项（1 项环境条件跳过）通过，暂存校验覆盖新合同、WM 源码和补丁。
- 0010 在锁定参考源码上接续 0008 的 `git apply --check` 通过。此检查不代表 Soong 编译。
- 0011 在锁定参考源码上的 `git apply --check` 通过。
- `RomClipboardTest#copyCutPasteRejectStaleEditorAndClearOnClose` 已编译，未运行。安装配套 ROM 后以 `m3Clipboard=true` 启用；用合成文本检查复制、剪切、粘贴、陈旧编辑代次及会话结束清空。主屏剪贴板不受影响、跨应用粘贴、密码/安全窗口拒绝、编辑焦点切换、超时不重放仍需实机验收。
- `RomM3Test#switchRoundTripAndRejectOldTarget` 已编译，尚未运行。安装 M3 ROM 后，以 `m3=true`、`m3TargetPackage=<允许测试的应用包名>` 显式启用，验证陈旧目标拒绝、跨应用往返、同一显示和原任务恢复。
- 待设备验收：真实选择器打开/选择/授权/返回；主屏同 UID 冲突、受保护窗口、暂停恢复与死亡清理；新旧 APK/ROM 双向兼容；完整 M2 回归。

日志：`build/m3-clipboard-gradle.log`、`build/m3-clipboard-tools.log`。M2 已通过的实机结果见 [M2 稳定化记录](m2-hardening-plan.md)，不以本批代码改动撤销既有验收。
