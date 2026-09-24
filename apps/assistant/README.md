# DroidUse 助手 APK

Kotlin / Compose。桌面入口显示为「小熊助手」；首页是对话式任务输入，模型、诊断、OCR 和调试入口收在右上角设置里。首页示例包含番茄小说、美团外卖和通话规划，并标出尚未验收或尚未接入的能力。任务执行范围根据输入文字中的已安装应用名称推断；无法推断时需在任务中写明应用名称。

语音模式支持 Android 系统语音识别，以及用户主动点击的阿里云 DashScope 中文识别。后者使用当前选中的 DashScope 模型配置，录音最多 15 秒，停止后才上传；可能消耗该配置的额度。两种方式都先转成文字供用户确认，再发送任务；系统中文 TTS 可用时播报新回复。需要麦克风权限。系统识别是否联网取决于设备提供方。退出语音模式或应用进入后台会停止录音；语音输入不会直接触发任务执行。

桌面图标提供白底彩色标志，但 Android 默认启动器仍可能统一裁成圆形；固定成图二的圆角方形需按[Launcher3 计划](../../docs/xiaoxiong-launcher-icon-plan.md)改 ROM 桌面。

支持多套 HTTPS 服务地址、模型名、API Key 和协议配置（Chat Completions / Anthropic Messages 兼容），切换、编辑、删除、连接测试、文本任务计划。地址应包含服务要求的版本路径，例如 `https://你的服务/v1`。兼容协议并不意味着每个提供商及每种模型都已验证；已使用用户授权的 DashScope 配置测试真实调用，并为个人 debug APK 配置 Qwen3.8-Max-0902 默认模型。

密钥通过 Android Keystore AES-GCM 加密，AtomicFile 写入 noBackupFilesDir，禁用备份；模型页禁止系统截图，密钥默认遮挡，不在日志中输出。客户端不自动跟随重定向或重试；响应最大 1MiB，任务最长4000字。

“生成计划”只调用模型，不会操作其他 App。“执行检查”检查执行服务，原厂系统明确返回未就绪。新增“运行任务”接入视觉任务循环及 Binder 观察/动作客户端；原厂后端未就绪时在模型调用前停止。暂停/恢复/停止同时支持任务循环和预览会话，不代表系统后端已实现。

构建：在项目根运行 `./gradlew :assistant:assembleDebug :executor:assembleDebug`。首次运行需要 JDK17、Android SDK36和网络。先安装 executor-debug.apk，再安装 assistant-debug.apk；二者必须同签名。

测试：`./gradlew :assistant:testDebugUnitTest :assistant:assembleDebugAndroidTest`，再安装测试 APK 并运行 AndroidJUnitRunner。存储破坏性测试使用合成密钥并恢复原配置；内置配置测试只校验默认配置和加密存储，不打印密钥。

常规 APK 迭代：在项目根执行 `bash tools/install-debug.sh 设备序列号`，会构建并更新两个 APK、打开助手，不涉及 ROM 刷写。

debug 专用「手机本地实验 · 番茄小说」使用 APK 内的 ML Kit 中文 OCR、Kotlin 阅读器和手机本地 shell 执行进程。电脑通过 `tools/phone/bootstrap.py --serial 设备序列号` 安装并启动一次；任务运行无需电脑桥接、Apple Vision 或 ADB 端口映射。手机重启或执行进程退出后仍须重新启动，普通 APK 并未获得 shell 权限。模型请求由手机直接发送至配置的云服务。

先运行 `python3 platform/executor/prototype-virtual-display/probe.py build`，再构建助手 debug APK，确保打包最新的执行 jar。真实默认密钥、执行 token 和调试 jar 只生成到忽略的 debug assets；release 不包含，勿分享个人实验 APK。历史 [Mac 辅助实测](../../docs/fanqie-benchmark-2026-09-17.md)仅作为对照。

最新手机本地完整测试：110.146 秒，前三章 39 页。见[迁移与完整实测记录](../../docs/phone-runtime-benchmark-2026-09-17.md)。

PP-OCRv6 对照实验为 opt-in instrumentation，执行 `tools/ocr/fetch_models.py`、`tools/ocr/prepare_benchmark.py` 后构建测试 APK，再用 `tools/ocr/run_benchmark.py --serial 设备序列号` 启动。tiny 已进入正常助手 APK；small 和基准截图仅用于测试，模型文件需单独下载；详见[OCR 对照实验](../../docs/ocr-comparison-2026-09-17.md)。
