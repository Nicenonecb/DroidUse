# 服务器生成的 ROM 集成补丁

2026-09-24：本地追加 `0011-m3-isolated-editor-clipboard.patch`，在电脑控制输入连接末尾增加带结果回传的编辑方法。须配套同步 `platform/rom/framework/core/java` 辅助类、当前服务源码与合同；接续 0010 集成。仅本地补丁应用检查通过，未部署服务器或通过 Soong。内容为 AI 会话内纯文本剪贴板，不接管原生复制菜单；边界与验收见 [M3 记录](../../../../docs/m3-system-operations.md)。

2026-09-24：`0010-m3-window-and-picker-routing.patch` 为本地新增，尚未部署到服务器或通过 Soong。它追加隔离显示窗口快照入口、系统选择器许可和隔离任务启动/恢复；同时需要同步 `platform/rom/framework/src`、当前合同及服务源码。应用顺序在 0009 之后；实现和验收范围见 [M3 记录](../../../../docs/m3-system-operations.md)。服务器正在进行其他 ROM 构建时不可应用。

2026-09-23 增补：`0007-lazy-keyguard-lookup.patch` 应用于 `0002` 之后的 `frameworks/base`。
它修复 DroidUse 在 TrustManager/NotificationManager 启动前缓存空 KeyguardManager，导致已解锁手机仍返回 `USER_NOT_UNLOCKED` 的问题。
改为使用时获取锁屏管理器，缺失时仍拒绝执行；服务器 `m services -j16` 已通过（8 分 25 秒）。
APK 隐藏类冲突由 `contracts/system-client` 修复，不需要放宽系统 hidden API 策略。

`0008-computer-control-create-lock.patch` 修复 ComputerControlSessionProcessor 在持有会话集合锁时创建虚拟输入设备的问题。
实机重复创建测试触发超时，ANR 转储显示主线程等待该锁，创建线程又等待主线程送达输入设备通知。
构造过程移到锁外，仍由同一 handler 串行创建；构造完成后重新检查锁屏与数量限制，再登记会话。
2026-09-23 两项修复的实机回归及完整 `m bacon -j16` 均通过；新包 SHA-256 为 `d1d647106be3960fada1f03b789b7bc6652becac39839773c2084f3f45f98122`，完整交付说明见 [M2 联调记录](../../../../docs/m2-validation-2026-09-23.md)。

生成日期：2026-09-21。补丁从 `/srv/rom/android` 的专用分支 `codex/droiduse-backend-v1` 导出，路径均相对于各自 Android 仓库根目录。它们保存服务器上已通过 Soong 编译的实际集成，不包含私钥、代理订阅或 APK 签名文件。

| 补丁 | 应用仓库 | 基础提交 | SHA-256 |
| --- | --- | --- | --- |
| `0002-framework-droiduse-backend.patch` | `frameworks/base` | `781c37c3f3c8566177b85ff80637e7affc834858` | `8a78f53e18e42543b06d2d58cf1956d5d16b56f4b0a33b3be8f6c03af799c0d9` |
| `0003-system-sepolicy-droiduse.patch` | `system/sepolicy` | `885cc500f6078a766d1f6def5ce4c06c55841773` | `9cf0aa485119aa9077d8b5733882857848ffdb35db9ff98c24cdfd6a81a09865` |
| `0004-oriole-droiduse-product.patch` | `device/google/raviole` | `2bb485707a08808028accfb12bee131a026b69f6` | `76a0b1d47303238718ca74be2c996b22fc7f0c71611057b4547ab7541701763f` |
| `0005-enable-computer-control.patch` | `vendor/lineage` | `895dbdb6c39cc3cb51b34958279a232f3c63f19d` | `7d4706dfe92c6e191530d88dcf68a208799bce6f8d4e1fa28a2e7d49b820b663` |
| `0006-build-soong-droiduse-boot-package.patch` | `build/soong` | `9aa045a2aef10b8089e32e847fed26d9aa3d61be` | `c7a7166f45bae2f59720239cebc0bffddea4f456ffb406d6d9ff6e0d59c178f5` |

本轮追加补丁（基础状态为以上五项已应用）：

| 补丁 | 应用仓库 | SHA-256 |
| --- | --- | --- |
| `0007-lazy-keyguard-lookup.patch` | `frameworks/base` | `0f8dc3f1093a882dfc2637ca5004507d0d1ff257e4d5eef01a2025b565c83641` |
| `0008-computer-control-create-lock.patch` | `frameworks/base` | `bd9c07d4c74efcdd0bf5ca597182b4f586de36d8552586fe33791e2ffb863ad5` |

先在对应基础提交或兼容分支中运行 `git apply --check <patch>`，再应用补丁。不要把五个补丁都放在 Android 顶层一次性应用，因为每个补丁对应不同 Git 仓库。

已通过的服务器检查：

- `m selinux_policy -j16`；
- `m framework-minus-apex services systemextimage -j16`；
- 启用 Android 16 电脑控制和虚拟设备音频隔离后，`m framework-minus-apex services -j16` 用时 8 分 53 秒并成功；移除截图模式隐式 `ALWAYS_UNLOCKED` 后，增量 `m services -j16` 用时 3 分 05 秒并成功；
- `platform-bootclasspath check boot jar packages`；`0006` 只把精确包名 `dev.droiduse.system` 加入 Boot JAR 包白名单；
- 完整 `m bacon -j16`；可刷写工程 ROM 大小 1,333,782,059 字节，SHA-256 为 `9c39583f0c3973d15d482d8486ad96ae8f7fe9210ed957825f46909dc654d154`；
- 工程交付目录 `/srv/rom/deliveries/droiduse-oriole-engineering-m2-20260921T084459Z` 的 ZIP 解压测试与 `sha256sum -c SHA256SUMS`；
- 五个仓库 `git diff --check`；
- 编译 JAR 类清单、Aconfig 最终值和工程证书摘要核验。

Android 16 的 `ComputerControlSession` Binder 入口由 `android.permission.ACCESS_COMPUTER_CONTROL` 强制保护。该权限是 `internal|knownSigner`，当前产品的外部已知签名列表为空；普通 App 无法直接绕过 DroidUse 服务创建会话。DroidUse 从 `system_server` 内部调用该入口，Executor 仍需通过 DroidUse 自己的包名、UID、证书和会话检查。

`0004` 只包含工程 APK 证书的公开 SHA-256 摘要。`sepolicy-version` 标记没有包含在补丁中，必须等真机 enforcing 与负向授权测试通过后再启用。

## 静态画面截图修正（0009）

在 0008 后应用 0009-static-display-capture.patch。改用 DisplayManagerInternal.userScreenshot 主动抓取所属隔离显示，排除安全图层，避免 ImageReader 在静态页面无新帧时耗尽。禁止以缓存图像冒充新截图。当前编译与验收状态见 docs/m2-hardening-plan.md；必须通过 RomM2Test#staticDisplaySupportsRepeatedFreshCaptures、受保护内容负向测试及真实模型新画面复核后，才可声称验收完成。
