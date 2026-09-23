# M2 ROM 与 Executor 联调记录

日期：2026-09-23。设备：Pixel 6 / oriole，工程 ROM `23.2-20260921-UNOFFICIAL-oriole`，SELinux Enforcing。
本轮目标是隔离屏启动、截图、点击、滑动、中文插入、暂停/恢复及异常清理，不包含 M3/M4。

## 已定位的问题

1. APK 直接引用 `dev.droiduse.system`，被 framework 同名隐藏类覆盖。实际测试抛出 `NoSuchMethodError`，logcat 明确记录 hidden API linking denied。
   现在从 canonical AIDL 自动生成 `dev.droiduse.systemclient`，保持 Binder descriptor 与 parcelable 布局不变。
   实机已成功读取能力并进入 openSession；没有关闭 hidden API 策略或修改签名鉴权。
2. `ComputerControlDroidUsePlatform` 在构造时获取 KeyguardManager，而 DroidUse 在 SystemServer 中早于 TrustManager/NotificationManager 启动。
   手机用户状态为 `RUNNING_UNLOCKED`、TrustManager `deviceLocked=0`，仍稳定返回 `USER_NOT_UNLOCKED`。
   改为使用时获取管理器，缺失时继续拒绝执行。服务器 `m services -j16` 编译成功，用时 8 分 25 秒。
3. 解锁后重复创建测试触发 `TIMEOUT`：ANR 转储中，ComputerControlSessionProcessor 持有会话集合锁并等待虚拟输入设备通知；主线程因运行应用回调等待同一把锁。
   `0008-computer-control-create-lock.patch` 将阻塞的设备构造移出集合锁，仍由 handler 串行创建，并在构造后重新检查锁屏与数量限制。
   针对性 `m services -j16` 编译通过，用时 2 分 25 秒；实机重复创建三次通过，整组回归未再出现该锁冲突。

## 本轮实现

- IExecutor v3 在末尾追加指定目标的会话入口，旧入口返回 `TARGET_REQUIRED`。
- 助手选择目标 App，BinderTaskExecutor 使用真实 ROM 会话；恢复记录保留目标包。
- Executor 先鉴权，再清除外部 Binder 身份，以自身 UID 调用 ROM。
- 点击、滑动、返回、文字插入逐项声明；动作等待完成回调，结果不明不自动重放。
- 校验截图元数据与目标显示；每次提交消耗当前帧；重复 requestId 不再次执行。
- 助手 token 死亡触发 APK 清理；Executor 自有 token 死亡触发 ROM 清理。
- 测试 APK 提供不含账户的合成界面和只读结果 Provider；测试验证实际点击计数、文字和滚动位置。

## 已完成验证

- 助手、Executor、测试 APK 构建通过。
- 79 项应用与 5 项系统协议 JVM 测试通过。
- 42 项 host runtime 状态机检查通过。
- ROM 工具测试 11 项，1 项跳过，其余通过。
- 助手与 Executor Lint 零错误；保留已有 SDK/依赖等警告。
- 实机 TargetAdapterTest、明确目标入口测试通过。
- 实机助手直接调用 ROM 被拒绝。
- 修复后的 services.jar 已通过可写覆盖层安装并成功重启；服务恢复、Enforcing 未变。

### 实机结果

| 范围 | 结果 |
| --- | --- |
| 截图传输 | 3 项通过：管道转有界只读文件、超限拒绝、阻塞超时；临时文件提前 unlink，无截图文件遗留 |
| ROM 直接调用 | 3 项通过：重复创建回收、截图/点击/中文输入/滑动/目标和 epoch 校验、暂停恢复 |
| 助手调用链 | 5 项通过：真实 BinderTaskExecutor 操作、帧元数据与生命周期、助手 UID 直接访问 ROM 被拒、合成适配器、明确目标入口 |
| 助手被结束 | 通过：ROM activeSession=none，虚拟显示从显示列表消失 |
| Executor 被结束 | 通过：ROM activeSession=none，虚拟显示从显示列表消失 |
| 锁屏撤销 | 通过：活动会话截图被拒，虚拟屏释放 |
| 锁屏创建 | 通过：openSession 返回 USER_NOT_UNLOCKED |

截图和合成测试 Provider 同时验证实际效果：点击计数增加、`DroidUse 中文输入 123` / `助手闭环测试` 文本一致、滚动位置增加。
测试期间保持 Enforcing；当前 logcat 未检出包含 droiduse/executor 的 AVC 拒绝，这不等于完整 SELinux 审计。
本轮未调用在线模型，没有宣称真实业务 App 或任意自主任务验收完成。

联调还修复两处 APK 问题：独立测试界面改用 Java 避免 instrumentation 外缺少 Kotlin 运行库；Executor 将 ROM 截图管道转换为最多 3 MiB、5 秒超时的只读文件，保持助手原有输入约束。
只对截图未就绪/帧率限制做有限只读重试，动作不重试。

## 证据与恢复

- 私有本地证据：`build/m2-validation/`，不提交截图、设备序列号或原始日志。
- 原系统文件：`build/m2-validation/system-backup/`。
- 原 services.jar SHA-256：`ed32f901779c4e9d5fb5900efd5f3d8eb536cc6aca2a73eec05092ae9370baf4`。
- 新 services.jar SHA-256：`15425305d45d6f9bc8f578a5630de72d447681cb56ec6111edadea2eb5e663a3`。
- 服务器日志：`/srv/rom/home/m2-validation/services.log`、`services.exit`；完整 ROM 构建为 `rom.log`、`rom.exit`。
- `0007-lazy-keyguard-lookup.patch` 应用在 `0002` 之后；`platform/rom/service/src` 保存当前源码。
- 既有独立工程包和纯净回退包保持原位。服务器通用 out 路径不能作为已固定的交付目录。

运行非锁屏测试：`python3 tools/phone/validate_rom_m2.py`。锁屏测试单独选择 `RomM2Test#lockRevokesSession`，结束后需人工解锁。

## 完整 ROM 交付

服务器在两项 framework 修复的实机回归通过后重新运行 `m bacon -j16`，30 分 29 秒成功完成（exit=0）。

- 独立交付目录：`/srv/rom/deliveries/droiduse-oriole-engineering-m2-20260923/`。
- ZIP：`lineage-23.2-20260921-UNOFFICIAL-oriole.zip`，1,333,785,418 字节。文件名沿用既有构建日期，必须结合独立目录与 SHA-256 区分新旧包。
- SHA-256：`d1d647106be3960fada1f03b789b7bc6652becac39839773c2084f3f45f98122`。
- ZIP CRC、A/B OTA payload、oriole 设备元数据、工程证书摘要和交付 SHA256SUMS 校验均通过。
- 完整构建的 services.jar 与手机已验收版本 SHA-256 一致。
- 产品未包含实验 sepolicy-version 标记；助手/Executor APK 单独交付，未预装进本轮 ROM。
- 旧工程包与纯净回退包均重新通过各自 SHA256SUMS 校验。
- 交付包含 boot/dtbo/vendor_boot/vbmeta 镜像、固定 manifest、源码状态、构建日志、7 项服务器补丁与 artifact-verification.json。

上一轮为加入 `0008` 主动中断，日志保留为 `rom-interrupted-before-lock-fix.log`，不是未处理的编译失败。
当前手机验证的是原工程 ROM 加 services.jar 覆盖层及新版 APK，不是新完整 ZIP 刷入后的结果。

## 边界

这是工程验证，手机仍使用先前实验 `sepolicy-version` 标记，标记没有写入产品补丁。
文字插入不等于完整编辑器焦点代次、选区、剪贴板、复杂 IME 或中文输入法组合态已验收。
M3、M4、正式签名与全面资源隔离验收尚未完成。
