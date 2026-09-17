# 虚拟屏幕隔离原型（一次性技术实验）

## 虚拟设备进一步实验

新增 `DisplayHost <associationId>` 模式，将显示绑定到 VirtualDeviceManager，并请求独立音频、相机策略和诊断输入法。需要 shell 的 CREATE_VIRTUAL_DEVICE 权限及属于 shell 的 app-streaming companion association。当前结论见[进一步实测报告](../../../docs/virtual-device-validation-2026-09-17.md)，此模式仍未通过完整隔离门禁。

在专用测试设备中先查看 `adb shell cmd companiondevice list 0`，保存原列表。仅在没有相同关联时创建临时关联，不能覆盖用户关联：

```sh
adb shell cmd companiondevice associate 0 com.android.shell 02:00:00:00:06:01 android.app.role.COMPANION_DEVICE_APP_STREAMING true
adb shell cmd companiondevice list 0
adb push platform/executor/prototype-virtual-display/build/host.jar /data/local/tmp/droiduse-probe.jar
adb shell CLASSPATH=/data/local/tmp/droiduse-probe.jar app_process / dev.droiduse.probe.DisplayHost 实际关联ID
```

记录宿主打印的实际 DISPLAY_ID，在另一终端执行 `python3 platform/executor/prototype-virtual-display/resource_probe.py --serial 设备序列号 --display 实际显示ID`。脚本仅操作两个测试包，但会在主屏打开诊断页、短暂申请相机/麦克风；仍不采集真实录音或图像，仅向虚拟音频注入全零样本。输出保存在 `build/virtual-device/`；重复运行会覆盖本轮资源日志，应先归档。

宿主命令 `clipboard 测试文本` 只写非默认设备剪贴板；`deny-test-mic` 只对该虚拟设备上的 `dev.droiduse.probe` 撤销录音权限。返回成功不表示已拦截旧录音 API，需核对实际录音状态。设备级撤权可能随同一关联保留到后续虚拟设备，应在测试记录中注明。

结束时向宿主输入 `quit`，再仅移除本次创建的关联：`adb shell cmd companiondevice disassociate 0 com.android.shell 02:00:00:00:06:01`。检查 `dumpsys virtualdevice` 无残留。资源脚本会撤销测试包相机/录音权限并重置 AppOps；脚本不会替宿主管理关联，也不修改主屏默认输入法。

## 扩展隔离实验

`probe.py build` 现在同时构建后台测试 APK、不同 UID 的主屏测试 APK、诊断输入法和固定双指手势宿主。运行：

```sh
python3 platform/executor/prototype-virtual-display/probe.py build
python3 platform/executor/prototype-virtual-display/lab.py --serial 设备序列号
```

该实验会暂时切换诊断输入法、在主屏打开专用测试页面、调整两个测试包的 AppOps/相机/麦克风权限，并进行短暂资源占用。仅播放全零 PCM，不读取/保存录音样本，不建立相机拍摄会话。退出路径恢复原默认输入法及启用列表、撤销测试包录音/相机权限、重置测试包 AppOps 并释放虚拟显示。只在这两个专用测试包上运行，不用于修改用户真实 App 权限。若进程被强杀，原输入法配置保存在 `build/isolation/restore.json`，需先恢复再重跑。

日志只记录诊断事件和测试框中的固定文本；证据位于 `build/isolation/`，不进入 Git。相机和麦克风的系统占用指示可能短暂亮起。测试没有自动批准任何系统权限弹窗。

整体能力及门禁见[能力清单](../../../docs/capabilities-and-isolation.md)。运行完成不代表所有隔离要求通过，必须阅读事件与实测结论。

已完成的阶段 1–3 实测结论见[隔离报告](../../../docs/isolation-validation-2026-09-17.md)：手势/部分弹窗通过，中文输入及音频、麦克风、相机强制隔离未通过，不能把原型用作生产后台执行器。

目标：主屏由用户正常使用，测试 Activity 在独立虚拟显示上绘制，并接受指定 displayId 的点击。暂不接模型、不操作第三方账号、不实现广告或权益任务。

## 当前证据

2026-09-17：在 Mac 上使用 JDK 17、Android SDK 36、Build Tools 36.0.0 完成 Java/Dex/APK 构建，APK 签名验证通过。在 Pixel 6 / Android 16（CP1A.260405.005）上完成基础实测：

- 普通 PUBLIC + OWN_CONTENT_ONLY 虚拟显示创建成功，测试 Activity 可截图，点击使计数器从 0 变为 1；但点击会抢走主屏窗口焦点。
- 增加 TRUSTED + OWN_FOCUS + STEAL_TOP_FOCUS_DISABLED 后，显示 4 接受连续六次点击，计数器达到 6；InputDispatcher 的 FocusedDisplayId 仍为 0，主屏桌面和虚拟屏测试窗口同时保有各自焦点。
- 当前代码采用后一配置，另加 DESTROY_CONTENT_ON_REMOVAL，避免销毁显示时将测试任务迁到主屏。
- 截图在 `build/virtual.png`，焦点证据在 `build/focus-evidence.txt`，均不提交 Git。
- 正常退出后已确认 DroidUse 虚拟显示移除，主屏焦点仍为 0。测试 APK 保留供后续实验。
- 主屏人工同时滑动/输入的反馈尚未收到，该项仍待验收。

**已证明 shell 权限下的独立绘制、截图、定向点击及基础焦点隔离；并完成下面的百度单应用实测。其他 App、视频/音频和中文输入仍需分别验证。**

## 百度真实 App 实测

2026-09-17，在同一 Pixel 6 上验证百度 `com.baidu.searchbox`，版本 `15.76.0.10`（513279232）：

1. 创建虚拟显示 5，通过 `am start --display 5 -n com.baidu.searchbox/.SplashActivity` 启动百度，首页在隐藏屏幕正常绘制。
2. 点击首页搜索框，进入 `com.baidu.browser.search.LightSearchActivity`。
3. 通过 `input -d 5 text` 和定向按键输入 ASCII 关键词 `Pixel 6`，点击搜索，截图确认查询词和已加载的结果页。
4. 执行 `input -d 5 swipe 354 1047 354 395 450`，前后截图确认页面滚动。
5. 操作期间采样的 InputDispatcher 顶层焦点始终为 0，主屏桌面和虚拟屏百度窗口同时保有焦点；输入页检查时 `mInputShown=false`，未显示实体屏键盘。
6. 用户明确反馈：**“主屏正常，没有被打断”**。这是用户人工确认，不等同于已对主屏视频、中文输入或所有应用做自动化验收。

本地证据：`build/baidu-search.png`、`build/baidu-scrolled.png`、`build/baidu-focus-evidence.txt`。未点击结果广告、未进行会员操作，也未修改百度账号设置；正常搜索可能留下 App 搜索记录。

复现时先启动本原型，读取当次实际 DISPLAY_ID，再将上述命令中的 `5` 替换为该 ID。搜索框坐标以当次截图为准。没有验证用户在主屏同时打开百度本身的行为。此实验由电脑端逐步观察和调用 ADB 完成，尚不是手机端自主 Agent。

这是通过 ADB shell UID 启动的实验，不是普通 APK 的独立后台能力，也不是计划中的 ROM 系统执行服务。使用隐藏 ActivityThread 入口获取 shell Context，是否可用取决于系统版本和权限；失败时保留报错，不自动修改系统或关闭安全检查。

## 构建与运行

从项目根目录运行（无需 Gradle 或下载依赖）：

```sh
python3 platform/executor/prototype-virtual-display/probe.py build
adb devices -l
python3 platform/executor/prototype-virtual-display/probe.py run --serial 设备序列号
```

默认 SDK 路径为 macOS 的 `~/Library/Android/sdk`，可通过 `ANDROID_HOME` 指定。需 JDK 17 提供 javac/keytool。

运行会安装 `dev.droiduse.probe`，上传 shell 宿主，创建 720×1280 的独立显示并启动测试 Activity。屏幕不镜像主屏，不申请采集受保护内容。首次实验可能改变焦点，这正是待测项；测试期间请在主屏使用可随时中断的内容。

交互命令：

- `capture`：保存虚拟屏截图到本目录 `build/virtual.png`。
- `tap X Y`：只向当前新建的非零 displayId 注入点击；坐标以截图像素为准。
- `status`：打印显示和窗口焦点，辅助确认是否抢焦点。
- `quit`：释放虚拟屏并停止测试 App。

先截图定位 `TAP COUNTER` 按钮，再发送点击，再截图核对 Count 递增。不要凭猜测坐标判定成功。

## 人工验收

1. 虚拟屏截图必须出现非零 Display ID 和测试计数器，而不是主屏镜像。
2. 主屏打开并操作普通 App，同时在虚拟屏点击；计数器应递增，主屏不应收到点击或跳转。
3. 主屏播放视频，重复操作虚拟屏，记录焦点、播放连续性及用户触摸是否正常。
4. 退出宿主后，检查虚拟显示已释放，主屏仍能正常操作。

基础验证通过后可继续测试携程等真实 App。此原型不验证中文输入、多输入法会话、音频焦点隔离、登录弹窗、受保护画面、第三方跨应用跳转、锁屏运行或手机脱离电脑运行。

## 清理

正常退出后，测试 APK 和两个临时设备文件保留供重复实验。需要完全移除时：

```sh
adb -s 设备序列号 uninstall dev.droiduse.probe
adb -s 设备序列号 shell rm -f /data/local/tmp/droiduse-probe.jar /data/local/tmp/droiduse-probe.png
```

构建结果、调试密钥和截图均在已被 Git 忽略的 `build/` 下。目录为实验代码，未通过验证前不并入生产模块。

参考：[Android DisplayManager](https://developer.android.com/reference/android/hardware/display/DisplayManager)、[Android 16 的独立焦点标志源码](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/hardware/display/DisplayManager.java)。
