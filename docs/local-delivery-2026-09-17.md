# 本机开发交付与验证 · 2026-09-17

本轮以“家中 Linux 电脑暂不可访问，先完成 Mac 与已连接 Pixel 6 能做的工作”为边界。不是全部 ROM 功能完成报告。

## 计划对应状态

| 工作 | 本轮交付 | 未完成部分 |
|---|---|---|
| 独立中文输入 | 协议核心随 runtime 编入 Android 工程；窗口/进程/代次、Unicode 和重复请求测试通过；重新校验固定 framework 源码及 WMS 补丁 | IMMS 独立编辑连接注册、客户端提交检查、系统适配仍未接通；不只是缺 Linux 编译 |
| 资源启动与故障保护 | 新增 IsolationCoordinator，保护逐项安装，安装失败不启动，启动结果不明先停止并排空，确认停止后才释放保护 | 原生音频/焦点/相机/麦克风执行点、跨进程守护、系统心跳还未实现完整闭环 |
| Kotlin 执行服务 | 已构建并安装独立 APK，真实 Binder 接口、调用者签名/包名验证、会话状态与停止；没有可用隔离则拒绝动作 | 尚未内置 ROM，也没有实际后台执行后端 |
| 助手应用 | 已安装桌面入口，任务、模型、诊断三页；状态、暂停/恢复/停止和计划结果显示 | 人工接管和观察—动作—验证的 AI 执行循环未接入 |
| 多模型配置 | 多套名称/地址/模型/密钥/协议可增改删及切换；Keystore AES-GCM、原子文件写入、配置页防截图、连接测试与文本计划 | 没有用户真实 API Key，未验证实际提供商连通性/额度/特定模型参数 |
| 真机控件实验 | 在 display22 验证点击、长按、双击、勾选/取消、滑块拖动、滚动、双指事件；实验后焦点在 display0 | 未把 115 项都标为通过；新控件是合成测试，不等于真实 App 全覆盖 |
| Ubuntu 准备 | 只读检查、工具安装、官方 ISO 下载/校验、源码下载及基线编译入口；核对 manifest/device 分支存在 | 尚未在家中台式机安装或运行 Linux 脚本，未获取完整 vendor/固件/源码、编译或刷机 |

## 已执行的验证

- `./gradlew :assistant:assembleDebug :executor:assembleDebug`：成功。
- `:assistant:testDebugUnitTest`：9 个测试通过，涵盖 HTTPS/请求头校验、地址拼接、协议解析、密钥不入载荷、取消、重定向及 HTTP 错误不重试、响应限长与连接关闭。使用内存连接替身，不冒充真实网络调用。
- `python3 platform/executor/runtime/test.py`：28 组协议场景通过；包含逐项保护失败、异常启动结果、停止未确认、释放失败重试和过期清理。替身不能证明系统内核隔离。
- Pixel 6 AndroidJUnitRunner：3 个测试全部通过，包含加密保存/恢复与篡改拒绝、Binder 会话状态与错误所有者拒绝、模型页防截图切换及 Activity 重建。界面测试首次使用按文本查询未找到 Compose 虚拟节点，改为遍历节点树后复测通过。
- shell 直接启动执行服务：系统返回 `Requires permission dev.droiduse.permission.EXECUTE`。
- `:assistant:lintDebug :executor:lintDebug`：无阻断错误；保留目标 Android36 和依赖版本提示。
- `python3 platform/rom/tools/check_source.py`：8 个固定源码文件校验及补丁适配成功；没有系统编译。
- Linux shell 脚本语法检查、Mac 平台拒绝、ISO 脚本预览检查；不声称 Linux 安装验证通过。

## 原始证据与产物（本地 build，不入 Git）

- `apps/assistant/build/outputs/apk/debug/assistant-debug.apk`
- `platform/executor/app/build/outputs/apk/debug/executor-debug.apk`
- `apps/assistant/build/test-results/testDebugUnitTest/`
- `build/validation/instrumentation.txt`
- `build/validation/home.png`
- `platform/executor/prototype-virtual-display/build/controls/report.json`
- `platform/executor/prototype-virtual-display/build/controls/events.txt`
- `platform/executor/prototype-virtual-display/build/controls/background.png`

控件实验结束释放虚拟显示并停止自有测试 App，没有操作相机、麦克风、剪贴板或用户账号。此前百度后台搜索证据保留于原专项报告，本轮没有冒充重新通过中文搜索。

## 继续工作的顺序

1. 家中 Ubuntu 安装后，建立完整固定源码/设备/vendor/固件基线，先编译并验证未修改 ROM。
2. 继续完成 IMMS 编辑连接与资源执行点接入，必须同时检查连接失效、服务死亡、主屏切换与在途任务清理。
3. 构建刷机后接上 Kotlin 执行器和实际观察/动作循环，先完成百度中文搜索，再逐项扩展真实 App 的 115 项验收。
4. 上述验收通过才冻结基础 ROM 接口；模型协议、任务逻辑、界面与常规功能迭代放 APK。

本轮没有购买云资源、调用付费模型、格式化磁盘、解锁或刷机。
