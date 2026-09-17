# 阶段 1–3：能力基线与隔离实测

日期：2026-09-17。设备：Pixel 6 / oriole；系统 Android 16，CP1A.260405.005 / 15001963，user/release-keys。未刷机。

## 结论

阶段 1 的能力清单和强制隔离规则已形成：[能力清单与主屏隔离约束](capabilities-and-isolation.md)。阶段 2/3 已实施原型和专项实验，**验收未全部通过，基础 ROM 不可冻结**。窗口隔离已具备基础，输入连接和共享资源还缺强制边界。

| 项目 | 本次证据 | 结论 |
| --- | --- | --- |
| 点击、长按、双指事件 | 隐藏显示 11 的测试 App 收到 TAP=1、LONG_PRESS、POINTERS=2；主屏焦点为 0 | 原始手势分发通过；不是所有 App 缩放/旋转控件都已验证 |
| 中文输入与主屏继续输入 | 主屏得到“主屏中文甲”；后台提交时 IME 当前目标仍为主屏，防错检查拒绝；主屏继续得到“主屏继续丙” | 防串写通过；后台中文输入失败 |
| App 内弹窗及返回 | 自建 AlertDialog 在显示 11 出现；定向 BACK 后进入下一操作 | 本例通过 |
| 跨 App 跳转 | 后台 LabActivity 唤起百度；补测显示 12 的 Task 属于 com.baidu.searchbox | 本例通过，不代表任意 deep link/支付/分享路径通过 |
| 系统权限弹窗 | 测试相机授权在显示 11；百度位置授权在显示 12；主屏保留自己的窗口和顶层焦点 | 本例通过，未自动批准权限 |
| 音频焦点 | 主屏请求返回 1；后台请求也返回 1；约 2 秒后主屏收到 FOCUS_CHANGE=-1 | 失败：后台可以使主屏失去音频焦点 |
| 后台静音播放 | 前后台全零 PCM 音轨均可进入播放状态，路由类型为内置扬声器 | 虚拟显示不自带独立音频路由；未做真实有声听感测量 |
| 拒绝后台音频焦点 | 对测试 UID 设置 TAKE_AUDIO_FOCUS=ignore 后，后台请求返回 0 | 单独 UID 的该项拒绝有效；PLAY_AUDIO=ignore 的真实静音效果未用有声样本验收 |
| 麦克风竞争 | 主屏录音 session 513 起初 silenced=false；后台 session 521 启动后，主屏变为 silenced=true | 失败：后台可影响主屏录音 |
| 仅忽略后台录音 | RECORD_AUDIO=ignore 后，后台 session 537 被静音，但主屏 session 529 也从 false 变为 true | 失败：仅让后台获得静音数据不足以保护主屏 |
| 相机竞争 | 主屏 CAMERA_OPENED；后台请求后主屏 CAMERA_DISCONNECTED，后台自身收到 CAMERA_DISABLED | 失败：即便后台最终打不开相机，仍可能先中断主屏 |
| 仅忽略后台相机 | 后台 UID 的 CAMERA=ignore 对照中仍出现主屏断开 | 本设备/本路径下不足以提供所需隔离 |
| 提前撤销资源权限 | 后台无 RECORD_AUDIO 时 AudioRecord 无法初始化；无 CAMERA 时在权限校验处抛 SecurityException；对应相机窗口内未见主屏断开 | 支持“占用前拒绝”的方向；不是按显示隔离的最终实现 |
| 退出与恢复 | 显示 11/12 均已销毁；默认输入法与启用列表恢复；测试运行权限撤销，测试包 AppOps 重置 | 本次正常退出路径通过 |

## 关键证据

最终完整实验日志来自 `build/isolation/events.txt`，时间约 12:21:17–12:21:56（手机时间）。临时显示 11；跨 App 稳态补测使用显示 12。

```text
IME_REFUSED expected=dev.droiduse.probe actual=dev.droiduse.foreground
dev.droiduse.foreground ... TEXT=主屏中文甲主屏继续丙 WINDOW_FOCUS=true

dev.droiduse.foreground ... FOCUS_REQUEST=1
dev.droiduse.probe ... FOCUS_REQUEST=1
dev.droiduse.foreground ... FOCUS_CHANGE=-1

dev.droiduse.foreground ... MIC_CONFIG ownSession=513 silenced=false
dev.droiduse.probe ... MIC_CONFIG ownSession=521 silenced=false
dev.droiduse.foreground ... MIC_CONFIG ownSession=513 silenced=true

dev.droiduse.foreground ... CAMERA_OPENED
dev.droiduse.foreground ... CAMERA_DISCONNECTED
dev.droiduse.probe ... CAMERA_ERROR=3
```

这些是实测现象，不能据此断言 Android 所有版本的内部执行顺序相同。最初调试中有一次因 ADB 参数转义中断、一次多指入口版本不匹配，以及无效的主屏焦点对照；修正后重跑，以上结论只引用最终有正确对照的实验。当前脚本对设备 shell 参数做引用；手势使用 Android 16 的 InputManagerGlobal；资源测试前记录主屏基线。

## 对架构的直接要求

1. **独立输入连接**：保留主屏输入法连接，同时向后台目标提交中文及编辑操作。需要评估系统多输入会话或受控的目标 InputConnection 支持。禁止用抢主屏焦点或全局剪贴板兜底。
2. **音频策略**：必须同时约束播放、音频焦点、duck、系统音量及路由；UI 焦点没有丢失并不意味着媒体焦点安全。
3. **硬件请求提前拒绝**：在后台录音/相机进入资源仲裁、占用或影响主屏之前拒绝。不能只在失败回调后处理，也不能把静音采样当作“不影响用户”。
4. **隔离身份**：AppOps 和运行时权限通常面向包/UID。正式方案须选择独立运行身份，或确保系统策略能区分后台与用户自己的使用；不能直接全局撤销用户真实 App 权限。
5. **权限与跳转拦截**：允许普通页面留在后台，但遇到需要用户决定的权限提示应暂停，不让模型自动同意。任意 App 启动链路仍需任务/显示监测。

以上机制及稳定接口要在正式基础 ROM 冻结前实现和验收。模型、业务流程和配置仍留在可升级 APK 中。

## 范围与限制

- 两个独立测试包/UID、targetSdk 36；后台通过 ADB shell 创建的可信虚拟显示运行。不是生产权限配置，不是 ROM 实现。
- 播放的是全零 PCM，未播放真实音视频；因此验证的是焦点、路由和播放器状态，不声称视频无卡顿或主观听感正常。
- 录音只启动/检查系统状态，不调用 read，不保存声音；相机只打开设备，不创建拍摄会话、不保存照片。
- 尚未验收：独立后台中文编辑成功、真实双键盘并行、任意 App 的全部手势、任意跳转、同包前后台同时运行、蓝牙/耳机路由保护、通知/振动隔离、后台音量修改拦截、旋转/锁屏/进程崩溃/设备重启与长期压力。
- 原型在基线测试时允许后台资源竞争，以暴露缺陷；它不是可长期开启的“不打扰模式”。不要把此原型部署给日常用户。

## 复现与证据位置

从项目根目录执行：

```sh
python3 platform/executor/prototype-virtual-display/probe.py build
python3 platform/executor/prototype-virtual-display/lab.py --serial 设备序列号
```

代码：[原型目录](../platform/executor/prototype-virtual-display/README.md)。本机不提交 Git 的证据位于该目录 `build/isolation/`：

- `events.txt`、`steps.txt`：测试事件、阶段时间。
- `background-ime.png`、`dialog.png`、`permission-dialog.png`：中文输入、普通弹窗、系统相机权限截图。
- `cross-app-settled.png`、`cross-app-settled-focus.txt`、`cross-app-settled-task.txt`：补测百度启动和位置授权停留在显示 12。
- `*-focus.txt`、`permission-windows.txt`：窗口和焦点证据。
- `restore.json`：原输入法设置，供异常中断后恢复使用。

## 官方机制参考

- [AOSP 多显示 FAQ](https://source.android.com/docs/core/display/multi_display/faq)：显示、输入和音频是不同的机制。
- [AOSP 输入法多显示支持](https://source.android.google.cn/docs/core/display/multi_display/ime-support?hl=en)：显示焦点与输入法会话需要单独处理。
- [Android 音频焦点](https://developer.android.com/media/optimize/audio-focus)：请求成功、失焦与 duck 的语义，以及 targetSdk 对条件的影响。
- [Android 16 虚拟设备示例](https://android.googlesource.com/platform/development/+/android16-qpr2-release/samples/VirtualDeviceManager/)：提供可继续评估的虚拟设备音频/相机策略方向，尚未在本项目验证。
