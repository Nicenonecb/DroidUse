# M2 整包回归与稳定化

2026-09-23：用户授权推进整包刷机回归、真实 App 任务、隔离和编辑能力、安装启动固化四项。
长任务和刷写的暂停位置遵循根目录 AGENTS.md。已通过 Updater 完成保留数据 OTA 并启动新系统；完整功能回归仍待完成。

## 1. 整包回归

- 已确认 Pixel 6/oriole、当前槽位 B、电量 100%、Bootloader 已解锁、无活动 DroidUse 会话。
- 已核对同分支 A/B OTA，目标构建时间新于手机；ZIP SHA-256 为 `d1d647106be3960fada1f03b789b7bc6652becac39839773c2084f3f45f98122`。
- 新 OTA 公共证书与当前系统 otacerts 信任证书匹配；安装时仍须通过 Updater 的实际包验证。
- 已在 Mac 私有交付目录创建 preflash 备份：DroidUse 应用数据、共享存储、系统状态、OTA 公共证书及 SHA256SUMS。归档可读取。
- 备份不包含其他应用的私有数据、完整 Keystore、eSIM 或账户恢复资料；DroidUse 加密配置依赖设备原有密钥，不作为擦除数据后可恢复的保证。
- 用户授权后通过 LineageOS Updater 保留数据安装。首次因 overlayfs 错误 64 被拒绝，备份覆盖层并退出、重启解锁后重试成功；更新状态确认 UPDATED_NEED_REBOOT 后重启。
- 新系统启动完成，槽位 B → A，构建时间戳 `1790170858`；无 overlay 挂载，`services.jar` SHA-256 为 `15425305d45d6f9bc8f578a5630de72d447681cb56ec6111edadea2eb5e663a3`，与交付一致。助手、Executor 和应用宝安装仍在。
- 干净系统 `coreReady=false / SEPOLICY_NOT_VERIFIED`，符合缺少实验标记的预期。解锁后已恢复仅含实验标记的覆盖层并完成基础功能回归；服务文件未覆盖。
- 通过标准：更新成功并重启、确认实际构建时间和服务版本、排除旧覆盖层影响、重新运行 M2 和锁屏/退出清理测试。新包未固化实验放行标记，须核对干净系统拒绝行为后再按工程流程恢复测试条件。

官方更新流程来源：

- https://raw.githubusercontent.com/LineageOS/lineage_wiki/main/_includes/templates/device_update.md
- https://raw.githubusercontent.com/LineageOS/android_packages_apps_Updater/lineage-23.2/push-update.sh

官方 Wiki 渲染页面本轮返回 403，已读取上述官方源码。A/B 设备优先使用 Updater/push-update 流程；脚本用于导入包，安装和重启是独立步骤。

## 2. 真实 App 与模型闭环

手机已安装系统计算器，优先使用不涉及账户或付款的计算任务。使用真实 ROM Binder 执行器和用户配置的模型，记录观察、动作、重新观察及最终结果验证；合成测试通过不代替此项。尚未执行在线模型任务。

## 3. 隔离和编辑

分别验证主屏触摸/输入法与后台操作并行、相机/麦克风资源边界、受保护窗口，以及编辑焦点变化、选区、删除和复杂中文输入。先定位实际缺口再修改；当前固定编辑代次不能作为完整编辑器所有权证明。未通过的动作保持不可用，不通过放宽系统策略填补能力。

## 4. 安装和启动固化

基于预装模板准备配套 APK，检查签名、调试资产和敏感配置；普通系统应用足够时不增加特权。工程与正式签名分开，实验标记是否固化取决于对应门禁验收。通过标准为干净安装及重启后按需绑定可用、负向鉴权有效，无需手工复制服务文件。当前两个 APK 仍单独安装，预装与正式签名尚未完成。

## OTA 后基础回归结果

新整包服务代码下，SELinux 始终 Enforcing。启用工程标记前确认 coreReady=false，助手 UID 直连 ROM 的负向测试通过。恢复实验标记后 coreReady=true；覆盖层唯一文件为 system_ext/etc/droiduse/sepolicy-version，services.jar 校验仍与交付一致。

- 截图传输 3 项、ROM 操作 3 项、助手链路 5 项通过。
- 助手与 Executor 进程结束后的会话和虚拟屏清理均通过。
- 锁屏撤销会话通过；锁屏拒绝新会话首次运行因设备已解锁而未满足测试前提，重新锁屏并确认 deviceLocked=1 后通过。保留首次失败和重试证据。
- 证据目录：build/m2-ota/regression/。最终无活动会话。
- 此结果仍为启用工程标记后的基础验收；在线模型真实 App 任务、全面资源隔离与编辑、预装和正式签名仍待完成。

## 真实 App 首轮执行

已通过助手 UI 选择系统计算器，使用现有模型配置运行 `17*23`，要求点击后读取结果。真实 ROM 后端成功创建隔离会话并获取第一帧；第一次模型决策因 NETWORK_FAILURE 在约 11 ms 内失败，总耗时约 729 ms，未提交动作。

网络检查确认手机无默认网络（Active default network: none）、无路由，模型域名无法解析。Wi-Fi 开关开启不代表已联网。已打开手机 Wi-Fi 设置，等待用户连接可用网络后重跑；未修改模型密钥或自动重试模型请求。证据位于 build/m2-ota/real-app/。此项尚未验收通过。

## 联网后真实任务修正

联网后实机暴露三类问题：模型混用归一化与原图坐标；过期动作未提交但模型把建议当作执行历史；模型偶尔使用单层 action 对象包装动作而触发解析拒绝。

已明确原图坐标范围，将用户所选本地 OCR 接入 ROM 截图观察，并把执行器返回结果（包括过期未执行）反馈给模型。单层 action 包装复用原解析器；混合顶层动作、嵌套包装及已有禁止字段仍拒绝。未放宽截图时效、动作权限或最终新画面复核要求。

新增显式 opt-in 的 CalculatorModelTest：使用手机当前模型和真实计算器，默认跳过，只有 onlineCalculator=true 才调用网络；最多 20 步 / 180 秒，退出清理会话。证据为手机私有目录 calculator-model-test 和本地 build/m2-ota/calculator-*.jsonl，均不纳入 Git。

本轮 APK 构建、82 项助手 JVM 测试和 Lint 通过。最终在线回归结果另记于下。

### 当前结果与后台编译交接

真实模型已在计算器输入 17×23，截图可见 391，但静态画面再次截图失败，独立复核尚未完成，在线任务不能记为通过。静态连续截图回归在旧 ROM 稳定失败；服务器源码确认 ComputerControlSession.getScreenshot 只调用 ImageReader.acquireLatestImage，静止时队列会耗尽。

0009-static-display-capture.patch 改为主动获取当前隔离显示的 userScreenshot，保留安全图层排除；不复用旧帧伪造时间戳。实现与新增静态截图测试已准备，尚待服务器编译及实机验收。服务器已核对无其他 ninja/soong 构建且磁盘余量充足，补丁 git apply --check 通过。

2026-09-23 北京时间 23:20:14，已以 rombuild 用户在 /srv/rom/android 启动 m bacon -j16，PID 239956，目录 /srv/rom/home/m2-static-capture-20260923。持有 .droiduse-build.lock，nohup 脱离会话运行。首次检查进入构建阶段，尚无退出码。日志 build.log，结束码 exit-code.txt，产物归档 artifacts/，成功要求退出码 0、唯一新 ZIP 和 SHA256SUMS；这些不等于实机验收通过。预计 30–45 分钟（上轮完整 ROM 约 30 分钟），建议 23:55–次日 00:05 查看。按用户要求停止轮询，服务器保持开机，Mac/USB无需保持连接。后续先检查结果，校验产物，再准备刷机和回归；不自动刷写。

### 第二轮刷写准备

编译完成用时 09:15；原归档脚本 exit=1 是两个同内容 ZIP 被计为两个候选导致，编译本身成功。已保留原退出记录并独立恢复归档，archive-recovery.exit=0，选定 lineage-23.2-20260923-UNOFFICIAL-oriole.zip。远端 ZIP CRC、两端 SHA-256 校验通过，构建时间戳 1790176818。手机 Updater 实际签名验证成功（status=2）。

本机独立目录 /Users/justin/Downloads/DroidUse-ROM-oriole-20260923-static-capture/，preflash 中备份应用数据、共享存储及覆盖层并验证可读。手机为 oriole / A 槽，电量 100%、无活动 DroidUse 会话。已执行 enable-verity 和重启退出实验覆盖层；待重启检查、手动解锁和用户明确继续刷机后才启动安装。

第二轮 OTA 已获用户明确授权，于北京时间 2026-09-23 23:41:21 启动；首次检查进入 UPDATE_STATUS_DOWNLOADING，约 1%。尚未完成安装或重启，按长任务约定交接，不持续轮询。

第二轮 OTA 完成后已重启到 B 槽，构建时间戳 1790176818、services.jar SHA-256 af1c6223adef2738c38ecd1dbf4e6730774fa73f6da0ee4fb818970e23711345 与交付一致。启动完成且核对时无覆盖层。恢复实验标记以准备测试；等待用户重启后解锁，静态截图与在线闭环验收仍未完成。

### 新 ROM 解锁后验收

- 静态显示不注入输入、连续 6 次读取新截图通过（5.675 秒）。
- 新增 FLAG_SECURE 合成页面测试：先确认相同显示上的非保护洋红内容可见，再切换受保护模式，连续 3 次截图无洋红内容泄漏，通过（3.056 秒）。这仅证明 FLAG_SECURE 用例，不代表所有 DRM/受保护缓冲区或资源隔离已验收。
- 手机重启后首轮在线测试因无默认网络而失败，未执行动作；联网后再运行，结果另记。
- 常用实机验证脚本已加入静态截图及受保护窗口测试，要求安装最新 Executor 测试 APK。

联网后发现计算器冷启动首帧可能全黑；等待绘制 2 秒的对照测试通过。助手已对会话首张全黑截图加入有上限的只读重取，不注入动作，不复用旧时间戳；持续黑屏仍交给原流程处理。用户确认 TaskRuntimeService 的意外改名后已恢复原名。助手 APK、82 项 JVM 测试及 Lint 再次通过，正在重跑在线任务。

真实运行还发现输入确认与应用绘制不同步：紧接确认的主动截图可能仍是输入前画面。助手在已确认执行后的下一次截图前等待最多 250 ms 绘制间隔，不重复动作、不延长截图时效阈值。APK 构建、82 项 JVM 测试与 Lint 通过，在线实机最终结果待下方记录。

### 在线闭环最终通过（2026-09-24）

CalculatorModelTest 使用当前手机模型配置与真实 ROM 执行器通过，耗时 67.165 秒，11 个决策步骤、5 个已执行点击，5 次过期观察被拒绝提交并重新观察。最终截图显示 17×23 和 391；取得新 frameId=11 后模型独立复核 passed=true，TaskLoop=COMPLETED。未放宽 5 秒截图时效或自动重放动作。

证据：build/m2-ota/calculator-draw-settled.txt、calculator-passed-events.jsonl、calculator-passed.png。这证明本计算任务闭环通过，不代表任意业务任务或在线模型稳定性全面验收。完整相机/麦克风/主屏输入隔离、复杂编辑代次与预装签名仍待推进。

最新助手和测试 APK 已整理到本机 static-capture 交付目录，校验清单已更新。这是含个人测试配置的私有调试交付，不作为公开发布包；未上传服务器。

最终 APK 下基础回归通过：截图传输 3 项、ROM 5 项（含静态与安全窗口）、助手链路 5 项及两个进程死亡清理场景。结束时 activeSession=none，SELinux Enforcing。
