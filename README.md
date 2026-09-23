# DroidUse

基于定制 Android ROM 的系统级 AI 手机助手。用户提出任务，助手观察屏幕、规划动作，通过系统执行服务操作手机上的应用，再观察结果，直到完成或交给用户接管。

## 项目状态

应用层已接入 PaddleOCR tiny（可切换 ML Kit）、独立前台任务服务、资源调度、操作与异常审计、步骤检查点，以及后台画面的人工接管。双击、长按、拖拽、多指手势已有手机实验记录。

文件选择、应用/链接打开已接入手机实验执行器的目标枚举与执行代码（2026-09-18，按用户要求尚未构建或实机验证），支持范围见[文件与跨应用执行](docs/phone-target-execution.md)。文本编辑与 IME 操作已定义任务层接口和能力校验，仍待系统执行实现；并不表示当前后端能实际执行所有动作。原厂系统仍需 ADB 激活手机端实验执行器；完整编辑器所有权、系统资源隔离以及生产级跨应用执行仍待 ROM 集成。Pixel 6 已刷入工程 ROM；2026-09-23 的 M2 实机验证已通过隔离屏截图、点击、滑动、中文插入、暂停恢复、锁屏及进程退出清理，助手已通过 Executor v3 接通这些接口。完整记录和限制见 [M2 联调记录](docs/m2-validation-2026-09-23.md)。进程退出后可通过加密恢复记录重新建立任务、观察新现场，用户检查后继续；不会自动重放旧动作。人工接管输入和系统资源动作已接入应用层，仍由 ROM 的逐帧能力声明决定是否可执行。

实现与限制见[应用层交付清单](docs/plans/app-runtime-p0-p1.md)、[任务审计与隐私](docs/task-audit-privacy.md)及[人工接管记录](docs/handoff-validation.md)。历史测试报告保留当时结果；最新代码的完整任务耗时未重新测量，不能沿用旧版本的 110 秒或 158 秒作为当前成绩。

公开仓库只包含源码、文档及测试代码。API Key、调试令牌、设备截图、原始实验输出和模型权重不随仓库发布；文档中的 `build/` 证据路径指本地实验产物。请自行配置模型服务，不存在可共享的内置密钥。

## 已确定的路线

- 直接开发「助手应用 + 系统执行服务 + 定制 ROM」。不以无障碍服务作为执行依赖。
- 首个目标设备：Google Pixel 6（代号 `oriole`），须确认实际设备支持 Bootloader 解锁。
- ROM 基于该设备已有的 LineageOS 适配进行修改。首次实现前锁定分支、源码提交和匹配固件。
- 手机运行任务编排与执行。第一版通过网络调用视觉模型；不要求模型离线运行。
- 目标任务：外卖筛选、酒店查找、京东/拼多多/淘宝同款商品比价。
- 核心操作路径是手机 GUI。平台官方业务 API 和商业合作不作为前置条件。
- 系统权限由自建 ROM 配置；目标 App 的实际兼容性通过实机验证。

## 阅读顺序

1. [技术架构](docs/architecture.md)
2. [系统执行协议草案](contracts/executor.md)
3. [开发里程碑](docs/roadmap.md)
4. [后台能力与主屏隔离规则](docs/capabilities-and-isolation.md)
5. [阶段 1–3 隔离实测报告](docs/isolation-validation-2026-09-17.md)
6. [115 项操作验收清单](docs/acceptance-matrix.md)
7. [独立输入与资源隔离集成门禁](docs/system-isolation-integration.md)
8. [虚拟设备进一步实测](docs/virtual-device-validation-2026-09-17.md)

## 目录

```text
apps/assistant/       Android 助手入口、任务状态和结果页面
core/agent/           手机端任务循环、模型调用、结果整理
platform/executor/   ROM 内置的系统执行服务
platform/rom/        设备集成、构建清单、权限和签名配置
contracts/           助手与系统执行服务的接口约定
docs/                架构和里程碑
```

Gradle 模块：assistant、executor、ipc、agent、runtime、system-api、system-client、paddleocr。ADB 原型保持独立构建；runtime 另有尚未在系统树编译的 Soong 声明。

## 开发环境

- 当前 Mac：M5、32GB 内存；用于编辑、助手开发、ADB/Fastboot 刷机及调试。
- 完整 ROM 编译：另外准备 x86-64 Linux 环境。建议 64GB 内存、1TB SSD；按所选分支文档核对实际要求。
- 私钥、API 密钥、设备截图、账号数据、ROM 大文件不进入 Git。ROM 源码与编译输出放在编译机独立工作区。

## 本机开发

```bash
python3 tools/ocr/fetch_models.py
./gradlew :assistant:assembleDebug :executor:assembleDebug
./gradlew :assistant:testDebugUnitTest :assistant:lintDebug :executor:lintDebug
python3 platform/executor/runtime/test.py
python3 platform/rom/tools/check_source.py --fetch
```

需要 JDK17、Android SDK36，local.properties 指向本机 SDK。APK 路径见 [助手说明](apps/assistant/README.md)。家中台式机的 [Ubuntu 准备步骤](docs/ubuntu-local-build.md)与 `tools/linux/` 已就绪；脚本默认预览，不会自动格式化或刷机。

本轮进一步实现见[资源门禁与执行循环](docs/execution-loop-and-resource-gate.md)。

应用层本轮收尾与源码自查见[应用功能交付说明](docs/app-code-delivery-2026-09-18.md)。该 2026-09-18 历史记录遵从当时要求未构建或实测；2026-09-23 的验证结果以 M2 联调记录为准。
