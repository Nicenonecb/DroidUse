# ROM M4：蜂窝通话后端

2026-09-24。本批实现系统 Binder → ROM 内置 Telecom 组件 → Android 通话/音频服务的代码链路，另增加 Executor 调用入口。**尚未合入整机 ROM、刷机或完成 Pixel 6 通话验收；M4 尚未全部完成。** 本文件的 M4 指 ROM 蜂窝通话，不是产品路线图里的外卖、酒店与语音任务。

## 已实现代码与边界

| 能力 | 实现 | 当前边界 |
| --- | --- | --- |
| 通话观察 | 系统签发 callId、观察编号、通话代次、状态、可执行操作、当前路由和静音状态 | 一次会话只绑定一通普通 SIM 电话；没有截图，不向调用方返回通话联系人和号码 |
| 拨号 | 会话创建时固定号码，执行时再次比对；要求已有默认出话 SIM | 号码只接受数字和开头的 +；拒绝 MMI、URI、暂停分隔符及紧急号码；发出请求不代表接通 |
| 接听/挂断/保持/恢复 | 根据当前 Call 状态与 capability 执行 | 不支持会议、第二路通话、VoIP、自管理电话、紧急回拨模式或身份无法确认的电话 |
| 静音/路由/DTMF | 根据当前路由掩码选择听筒、有线、蓝牙、免提；单字符 DTMF，150ms 后停止 | 路由切换是否完成必须重新观察；系统和运营商仍可能拒绝请求 |
| 下行捕获 | `AudioManager.getCallDownlinkExtractionAudioRecord` | 要求 PSTN interception 可用，创建及录音仍可能失败；尚未实测 |
| 上行/混合捕获 | `AudioRecord` 的 VOICE_UPLINK / VOICE_CALL | 不回退到普通 MIC；需要实测证明来源正确、不是静音或错误混音 |
| PCM 上行注入 | `AudioManager.getCallUplinkInjectionAudioTrack` | 只接受有序且未过期的 PCM16 单声道；远端是否听到仍需测试 |
| 私密语音指令 | 明确返回 `PRIVATE_MIC_ROUTE_NOT_VERIFIED` | 未实现；不能用普通静音加录音冒充“对端绝对听不到” |

ASR、模型和 TTS 生成仍在可更新 APK。ROM 接收已经生成的 PCM，不在 system_server 中运行模型，也不把 TTS 播放到扬声器来冒充上行注入。当前没有新增助手业务 UI；调用入口已经提供给应用层。

## 组件与集成

- `RoutingDroidUsePlatform` 按会话模式选择现有隔离屏后端或 `CallDroidUsePlatform`，继续共用系统管理器的单会话限制。
- 新增 `platform/rom/telecom/`，Soong 模块 `DroidUseTelecom`，安装在 system_ext，独立 UID/进程、platform 签名；不使用 system shared UID。
- `CallObserverService` 是非 UI InCallService，正常拨号 UI 继续由 Telecom 管理。`CallControlService` 的每个 Binder 事务只接受 SYSTEM_UID；系统端只绑定系统预装且与 android 包签名一致的指定组件。
- 新组件需要 CONTROL_INCALL_EXPERIENCE、CALL_AUDIO_INTERCEPTION、CAPTURE_AUDIO_OUTPUT、READ_PRIVILEGED_PHONE_STATE、MODIFY_PHONE_STATE；CALL_PHONE 与 RECORD_AUDIO 使用单独 default-permissions XML 的可撤销授权。两份 XML 均需实际安装，并保留 `.xml` 文件名。
- `stage-rom-backend.py` 增加 `telecom/` 源码载荷。把该目录放到 ROM 的独立模块目录（如 `packages/apps/DroidUseTelecom`），产品加入 `PRODUCT_PACKAGES += DroidUseTelecom`。
- 同步更新 `contracts/system-api` 的 Java/AIDL 到 framework，更新 service 源码和 Executor APK；通话组件直接使用该 ROM 的 framework 合同，不另打包一份同名 Binder 类。
- 保留现有签名校验、SELinux Enforcing 和工程验证标记机制。尚未新增 SELinux 放行规则；实际安装后需验证绑定、电话和音频访问的 AVC，再按确切需要补规则。

## 会话与清理

新字段追加到 Parcelable 尾部，旧事务编号不变。`SessionSpec.allowCallAudio` 默认 false；`callAddress` 可空。纯观察/控制只申请 CAP_TELECOM；捕获和注入需要分别请求其能力，能力不可用时拒绝创建对应会话。

观察有效期 5 秒，一次操作或一次开流消费该观察。重新观察、Telecom 回调、暂停或关闭会废弃旧观察。对通话的所有操作检查 callId 和当前状态。绑定过的电话断开后，会话不能转而控制另一通电话。

每个音频会话最多一个捕获源和一个注入流。暂不支持同时开启上下行两路独立捕获；可先分别探测，再决定是否扩展设备后端。任何通话状态、细节、路由或静音回调都会停止原音频流，需要重新观察、重新打开。

独立组件每 250ms 检查解锁、会话期限、调用者存活和电话集合；系统管理器保留原保护心跳。暂停、停止、锁屏、来第二路电话、进程死亡或音频模式退出均停止采集/注入。关闭助手不自动挂断用户电话，不自动改变用户静音设置。音频工作线程未退出时关闭返回“尚未清理完”，系统管理器继续重试；APK 返回 STOPPING，等待 CLOSED 事件后释放本地会话。

Binder 超时返回 UNKNOWN_OUTCOME，取消尚未开始的任务，并清理可能迟到创建的流；已经发出的 Telecom 操作不会自动重试或声称已撤销。

## PCM 传输

`StreamHandle.transport=1` 表示可靠单向管道。不是截图的常规只读文件，也不是环形共享内存。每个包为小端序：

| 字段 | 字节数 |
| --- | --- |
| magic `0x44554131` | 4 |
| PCM payload 长度 | 4 |
| 从 0 开始的递增 sequence | 8 |
| `elapsedRealtimeNanos` 时间戳 | 8 |
| PCM16 单声道样本 | 长度由头部指定 |

采样率为 8000/16000/48000，每包最多 20ms。捕获时间戳是读取时的单调时钟，不是 HAL 硬件采样时间。管道容量有界，声明的 65536 字节为容量上界；客户端不能通过参数要求无限缓冲。

注入拒绝旧序列、超过 500ms 的包、超过 100ms 的未来时间和不完整/非法样本。背压或输入停顿超过期限时关闭流，不缓存音频等待以后再播放。管道关闭错误不包含原始音频或电话号码；客户端收到 EOF/错误后必须重新观察，不重放旧音频。

## Executor 的应用调用入口

新增 IExecutor 尾部方法：

- `beginCallSession(clientToken, options)`：`callAddress` 可选；`allowCallAudio` 请求捕获，`allowInjection` 请求注入。两者默认 false。
- `observeCallSession(sessionId)`：返回 frameId、callGeneration 和 calls 列表。保留这些系统签发的值用于下一次操作。
- `submitCallOperation(sessionId, requestId, operation)`：包含 kind、callId、frameId、callGeneration，以及对应操作需要的 address/digits/audioRoute/enabled。
- `openCallAudio(sessionId, spec)`：包含 kind、callId、frameId、callGeneration、sampleRateHz，返回 descriptor 和 transport。
- 暂停、恢复、关闭复用 `pauseSession`、`resumeSession`、`cancelSession`。关闭时 STOPPING 不等于 CLOSED。

每个新入口仍执行原有助手包名、签名权限和 UID 检查，不对其他应用开放。显示任务和电话任务不能同时占用同一个 Executor。

## 验证记录

- system-api 19 项 JVM 测试通过，其中新增 9 项覆盖会话/操作/流校验、旧观察、超时、PCM 重放、时间戳和长度边界。
- Assistant 85 项 JVM 测试通过。
- Assistant/Executor APK、两侧 instrumentation APK 构建通过；Executor lint 通过。
- 新系统服务、通话组件和生成的合同 Java 已针对服务器当前 framework/services 编译产物完成 javac 检查；这不是 Soong 模块构建或整机 ROM 构建。
- ROM 工具测试 11 项，Mac 跳过 1 项 Linux 专属测试。
- 本轮没有拨打电话、录音、安装 APK 或刷机。工作区其他 M3 改动保留。

本机日志：`build/m4-verified-build.log`、`build/m4-final-tests.log`、`build/m4-rom-tools-final.log`、`build/m4-preflight/javac-final.log`。最终独立服务器接口检查记录：`/srv/rom/home/droiduse-m4-preflight-20260924-final/`，退出码 0，未修改 `/srv/rom/android` 的活动源码。

## 待实机验收

先构建并安装配套 ROM/Executor，再由用户与测试对端建立普通 SIM 电话。听筒、免提、蓝牙、有线按实际可用设备分别验证；还要覆盖紧急电话拒绝、第二路电话、锁屏、断线、来回切换路由、客户端死亡和 FD 回收。私密指令只有在对端录音证明确实无泄漏后才能开放。

已提供 `RomM4Test`：`m4Call=true` 检查观察、旧句柄拒绝和暂停/恢复，不拨号、接听或挂断；`m4Audio=true` 才读取短时音频，只报告包数和非零字节数，不保存音频。`m4Source` 可为 downlink、uplink、mixed。测试语音须由对应一侧说出；非零数据仅证明收到样本，不能证明上下行分离或通话来源正确。

```sh
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
"$ADB" shell am instrument -w -r -e m4Call true \
  -e class dev.droiduse.executor.app.RomM4Test#observesCellularCallRejectsStaleHandlesAndPause \
  dev.droiduse.executor.test/androidx.test.runner.AndroidJUnitRunner

# 仅在明确准备好测试通话和对端后执行音频探针。
"$ADB" shell am instrument -w -r -e m4Audio true -e m4Source downlink \
  -e class dev.droiduse.executor.app.RomM4Test#receivesBoundedPcmPacketsFromExplicitCaptureSource \
  dev.droiduse.executor.test/androidx.test.runner.AndroidJUnitRunner
```

注入验收还需要应用客户端发送 `CallPcmFrame` 格式的已知合成 PCM，并由对端确认内容、延迟及停止后的静音；当前 smoke test 不代替这项验收。若 `isPstnCallAudioInterceptable()` 返回 false，先调查当前 Pixel 6 audio policy/HAL，不能通过改 capability 返回值绕过。

参考 Android 16 原始接口：[AudioManager](https://android.googlesource.com/platform/frameworks/base/+/android16-release/media/java/android/media/AudioManager.java)、[InCallController](https://android.googlesource.com/platform/packages/services/Telecomm/+/android16-release/src/com/android/server/telecom/InCallController.java)。实际接口编译以项目服务器锁定的 LineageOS 源码产物为准。
