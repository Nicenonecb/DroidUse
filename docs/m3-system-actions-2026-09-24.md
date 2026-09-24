# M3 通知、权限与整机设置（第二批）

## 范围与状态

用户选择：通知和权限限定当前任务应用。本实现把范围固定为创建会话时的目标包，跨应用导航不会扩大此权限范围；只有该包的窗口位于会话显示屏时才提供对应操作。只支持主用户 0。

本批代码已接通模型动作、Assistant、Executor、ROM 服务。**尚未刷入实机，不能称为实机验收通过。** 当前设备仍运行 r8；r8 的通知、权限和设置能力仍是 NOT_IMPLEMENTED。

| 功能 | 本批实现 | 边界 |
| --- | --- | --- |
| 通知查看 | 最多 8 条任务应用通知的标题、正文摘要 | 排除 VISIBILITY_SECRET；不读取历史通知；标签视为不可信数据 |
| 通知打开 | 在当前会话任务和显示屏打开经过检查的通知目的 Activity | 仅同包、exported、无组件权限、standard launchMode；不执行 PendingIntent 携带的多 Activity 栈；不支持依赖临时 URI 授权的目的页 |
| 通知清除 | 清除仍可清除的当前通知 | 执行前重新核对 key、postTime、内容；持续通知不提供清除目标 |
| 通知快捷回复 | 同包显式广播或服务 RemoteInput | 非 Activity、可变 PendingIntent、自由文本；1–4000 UTF-16 字符；不自动重试发送 |
| 运行时权限 | POST_NOTIFICATIONS、READ_MEDIA_IMAGES/VIDEO/AUDIO 的授予和撤销 | targetSdk ≥33；排除共享 UID、固定/策略/角色权限；相机、麦克风、定位、联系人等未开放 |
| 整机媒体音量 | 0/25/50/75/100% | 明确影响手机全局；不改变通话音量 |
| 整机亮度 | 10/25/50/75/100%，切换手动亮度 | 最低保留可见亮度；恢复自动亮度需用户设置或测试清理 |
| Wi-Fi | 开关、连接最多 8 个已保存网络之一 | 不新增网络、不读取或传送密码；连接请求成功不等于已联网 |

## 授权与协议

- Assistant 高级设置新增“允许任务调整整机设置”，默认关闭，运行期间不可改变。新任务启动时捕获开关；恢复任务同时要求原任务已开启和当前开关仍开启。
- `IExecutor` 尾部新增 `beginTargetSessionWithOptions`；旧入口保持默认关闭。`SessionSpec.allowGlobalSettings` 是尾部默认 false 字段。
- `Observation.systemContext` 是尾部可空字段，最多 24 段、每段 500 字符。包含任务通知摘要和已授权整机状态；通知内容不进入恢复上下文或动作日志。
- 新操作使用当帧随机 `targetId`。包名、权限名、网络 ID、设置值由 ROM 存在会话内；调用方只能选择目标。仅快捷回复允许附带文本 `value`。
- 校验 domain/kind、目标、画面代次、窗口身份、5 秒时限、设置/权限预期状态。操作尝试后消费整个观察画面；暂停/恢复/关闭清除句柄。
- 通知和权限始终限制原始任务包；系统文件选择器、跨应用页面不会继承其管理权限。
- Android 撤销权限可能结束目标应用进程；这是系统行为，需要随后重新观察或重新创建任务。
- Wi-Fi 状态分别报告开关、SSID、默认网络是否使用 Wi-Fi、默认网络是否被 Android 验证。仍须另行验证测试目标可访问。
- 新 ROM 与两份 APK 应一起部署；注意 `/data/app` 的旧 APK 更新可能覆盖 ROM 预装包。

## 验证与待办

已完成：

- system-api JVM 校验测试，包含任意包/权限/数值注入、domain 错配、回复文本边界和缺少观察代次的拒绝。
- Assistant JVM 测试，覆盖所有目标操作的严格 JSON 解析、回复内容脱敏及旧目标拒绝。
- Assistant、Executor、instrumentation APK 构建；Executor lint。
- ROM 源码对服务器现有 framework/services 接口做独立 javac 检查。它不是 Soong 完整构建或实机验收。
- ROM 工具测试 11 项，其中 Linux 非 root 专属用例在 Mac 跳过 1 项。
- 修改和新增人工维护文件不超过 1500 行；`git diff --check` 通过。

已知检查缺口：Assistant 全量 lint 被已有 `CloudSpeech.kt` 的 AudioRecord `MissingPermission` 错误阻止；本次未改动录音实现。APK 编译与单测不受此项影响。

待新版 ROM 安装后执行 `RomSystemActionsTest`：

1. 合成通知查看、快捷回复（本地测试接收器，不发送真实消息）、在隔离显示屏打开、替换后拒绝旧目标、清除。
2. 另一任务应用看不到测试通知和测试应用权限。
3. READ_MEDIA_AUDIO 授予/撤销、旧画面拒绝、相机权限不开放；恢复测试前授权状态。
4. 默认无整机操作；开启后媒体音量和亮度回读验证，并恢复原音量、亮度及自动亮度模式。
5. 单独启用 Wi-Fi 实测开关和已保存 RedBearAI 重连；确认默认网络验证并使用 HTTPS 目标测试可达性。失败时按 AGENTS.md 主动进入网络设置连接。
6. 回归剪贴板、跨应用、文件选择器与 M2 隔离清理。

实机命令（先安装匹配版本 APK 和 instrumentation；不能在 r8 上当作验收）：

```sh
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
"$ADB" -s 1B121FDF60SISM shell am instrument -w -r \
  -e m3System true \
  -e class dev.droiduse.executor.app.RomSystemActionsTest \
  dev.droiduse.executor.test/androidx.test.runner.AndroidJUnitRunner

# 会短暂断开 Wi-Fi，须独立执行，结束后核对网络恢复。
"$ADB" -s 1B121FDF60SISM shell am instrument -w -r \
  -e m3System true -e m3Wifi true -e m3WifiSsid RedBearAI \
  -e class dev.droiduse.executor.app.RomSystemActionsTest#wifiToggleAndSavedNetworkReconnect \
  dev.droiduse.executor.test/androidx.test.runner.AndroidJUnitRunner
```

ROM 构建应继续保留 r8 的蓝熊自适应图标、圆角方形遮罩、壁纸与开机动画。构建和包校验后，按 AGENTS.md 在实际刷入前等待用户明确继续。
