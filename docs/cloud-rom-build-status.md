# 云端 ROM 构建准备记录

更新：2026-09-21。目标：Pixel 6 (`oriole`) LineageOS 基线与 DroidUse 工程 ROM 构建。

## 2026-09-21 DroidUse 工程 ROM（最新）

- DroidUse M1/M2 后端已经完成整机 `m bacon -j16` 构建。首轮构建在 89% 被 Boot JAR 包检查拒绝，因为新 framework 包 `dev.droiduse.system` 尚未列入允许清单；随后只放行该精确包名，独立的 `platform-bootclasspath check boot jar packages` 及缓存后的完整构建均通过。
- 可刷写工程包只从独立交付路径取用：`/srv/rom/deliveries/droiduse-oriole-engineering-m2-20260921T084459Z/lineage-23.2-20260921-UNOFFICIAL-oriole.zip`，大小 1,333,782,059 字节，SHA-256 为 `9c39583f0c3973d15d482d8486ad96ae8f7fe9210ed957825f46909dc654d154`。
- 独立交付目录：`/srv/rom/deliveries/droiduse-oriole-engineering-m2-20260921T084459Z`。目录包含 ROM、启动相关镜像、五个源码补丁、源码和构建证据以及 `SHA256SUMS`。ROM 已复制为独立 inode，不依赖 `out` 目录中的硬链接。
- 验证已通过：ZIP 全量解压测试、framework/services 类清单、工程 Executor 证书摘要、六组 Aconfig 最终值、五个源码仓库 `git diff --check` 和交付目录 `sha256sum -c`。
- 工程包有意不内置 `/system_ext/etc/droiduse/sepolicy-version`。在 Pixel 6 完成 SELinux enforcing、错误 UID/包名/签名拒绝以及显示和输入隔离的负向测试前，DroidUse 服务会拒绝创建执行会话。

### 基线硬链接事件与修复

LineageOS 的 `bacon` 目标把带日期的 ROM 名称和 `lineage_oriole-ota.zip` 建成硬链接。第二次构建因此覆盖了 2026-09-19 旧基线路径所指向的 inode；旧基线原始 SHA-256 `56dfae6f...` 的字节副本已经无法从服务器或本机找到，不能再声称它仍被保存。误导性的旧日期硬链接已删除。

五个 DroidUse 仓库全部暂存后，服务器用干净源码成功重建了新的 LineageOS 回退基线，完整 `m bacon -j16` 用时 42 分 26 秒。独立包为 `/srv/rom/deliveries/oriole-clean-baseline-rebuild-20260921/lineage-23.2-20260921-CLEAN-BASELINE-oriole.zip`，大小 1,333,858,533 字节，SHA-256 为 `7c169ee9bcb33f98364873259794e12f9b97b88cac2e47df3f961d1c3d19b966`。ZIP 全量解压测试和交付目录 `sha256sum -c` 均通过，文件为单链接独立 inode。这份包是功能等价的干净回退基线，不冒充旧 SHA-256 的逐字节副本。

构建脚本的编译、源码恢复和最终退出码均为 0；五个源码仓库的 DroidUse 修改已全部恢复，`git diff --check` 通过，且没有残留的 Soong、Ninja 或 OTA 进程。源码树 `out/target/product/oriole/lineage-23.2-20260921-UNOFFICIAL-oriole.zip` 现在指向最后构建的干净基线，不能作为 DroidUse 工程包使用。

## 已完成

- 实机确认 Ubuntu 22.04 x86-64、16 vCPU、约 61 GiB 可见内存。
- 系统盘 `/dev/vda` 为 20 GiB；未改动其分区。
- 检查新数据盘 `/dev/vdb` 为 1 TiB，无分区、挂载点或文件系统签名后，初始化为 ext4。
- 数据盘挂载到 `/srv/rom`，按 UUID 写入 `/etc/fstab`；原文件备份到 `/etc/fstab.before-rom`。
- 在数据盘创建并启用 16 GiB Swap，已配置启动加载。
- 创建无密码、未授予 sudo 权限的构建用户 `rombuild`，主目录 `/srv/rom/home`。
- 创建归构建用户所有的 `/srv/rom/android`、`/srv/rom/cache` 和 `/srv/rom/tmp`。
- 执行项目 `tools/linux/setup-ubuntu.sh --install`，退出码 0；安装 Git、Git LFS、Repo launcher、JDK 17、编译依赖、ADB/Fastboot 等。`dpkg --audit` 无输出。
- 安装后系统盘剩约 15 GiB；数据盘剩约 940 GiB。
- 安装日志：服务器 `/srv/rom/logs/setup.log`；退出码 `/srv/rom/logs/setup.exit`。

## 2026-09-19 23:58 当时记录（基线路径状态已由上方更正）

- Pixel 6 (`oriole`) 原版 LineageOS 23.2 基线构建成功，219,063 个 Ninja 动作全部完成，总编译时间 3:41:02，构建退出码 0。
- 产物：`/srv/rom/android/out/target/product/oriole/lineage-23.2-20260919-UNOFFICIAL-oriole.zip`，大小 1,333,857,664 字节。
- SHA-256：`56dfae6f1680d9b309a962c080767083f94a8c1abc71d2c79ce32715c68b5e52`。
- `unzip -t` 完整校验通过，压缩数据未发现错误。构建完成后数据盘约剩 318 GiB。
- 这是未加入 DroidUse 补丁的基线包；尚未连接手机、刷机或验证启动。

### 20:18 构建恢复

- 内核构建成功：Bazel 完成 555 个动作，产物已复制到 `device/google/raviole-kernels/6.1/`。
- 首轮系统构建于 18:32 停在 Soong 配置生成：`continuous_instrumentation_metric_tests` 将绝对 `OUT_DIR` 下的主机工具路径判定为源码目录之外。
- 用同一构建探针对照验证：绝对 `/srv/rom/android/out` 可稳定复现该错误；恢复默认相对 `out` 后错误消失并进入 Kati。根因是准备脚本覆盖了上游构建系统预期的输出路径形式，不是源码或服务器损坏。
- `build-baseline.sh` 已改为相对 `OUT_DIR=out`。确认已有 `Image.lz4`、`dtb.img` 和模块清单后，跳过已完成的内核编译，在 tmux `rom:baseline-resume` 继续系统编译。
- 恢复日志目录：`/srv/rom/home/rom-build-resume-ow3JZftN/`；构建尚未完成。

- 补齐 `python-is-python3`、`python3-lxml`、`python3-protobuf`、`protobuf-compiler`；已有 python3-yaml。官方 vendor 提取于 18:24:52 成功，退出码 0，生成约 977 MiB 的 `vendor/google/oriole`。
- 提取记录：`/srv/rom/home/pixel6-extract-fixed-NodOQoZ9/extract.log`、`extract.exit`。
- 结构预检通过：1,168 个源码 HEAD 与 4 个设备依赖路径；提取后数据盘约剩 446 GiB。移走此前检查生成的 enum34 Python 缓存后，repo status 确认源码工作区干净。
- 内核所有 HEAD 与成功同步时保存的 revision 清单再次核对一致。当前上游 `lunch` 自动调用 `build_kernel`，构建入口在 lunch 前设置 `SKIP_KERNEL_SYNC=true`，复用现有源码。
- 已在 tmux `rom:baseline-build` 启动 `lineage_oriole-bp4a-userdebug` 基线，先编译内核，成功后执行 `m bacon -j8`。18:28 已进入 Bazel 内核目标分析，尚未产出 ROM。
- 本次构建日志目录：`/srv/rom/home/rom-build-runs/20260919T102702Z-rVhUoz/`，含 manifest、source-status、build.log；结束后写入 exit-code.txt 与资源统计。启动记录：`/srv/rom/home/pixel6-baseline-LXCYX0TY/launch.log`。
- 修正构建入口将 repo 的 `nothing to commit (working directory clean)` 误判为改动的问题；只放行该完整成功提示或空白，其他状态仍阻止基线构建。
- 未应用 DroidUse 补丁、未连接/刷写手机、未运行应用单元测试或 Lint。

## 2026-09-19 18:08 更新

- 内核源码与工具链同步于 17:41:44 成功，`pixel6-kernel-r3n7wAEP/sync.exit` 为 0，已保存 revision manifest。
- 原厂镜像于 17:38:11 下载及 SHA-256 校验成功，`pixel6-factory-pNgYF0rq/download.exit` 为 0。
- 首次 vendor 提取于 17:39:01 失败：上游 fbpacktool 调用 `python`，Ubuntu 只有 `python3`。未发生下载失败。
- 已为重试创建仅作用于该任务 PATH 的 `python -> /usr/bin/python3`，未替换系统 Python。新日志 `/srv/rom/home/pixel6-extract-O8gE5Zpj/extract.log`，退出码文件 `extract.exit`，tmux `rom:vendor-retry`；使用 `--no-cleanup` 保留已有提取目录。重试结果尚待确认。

## 2026-09-19 17:27 更新

- 通用源码清单 1,164 个仓库的本地提交已与清单目标核对；最后四库于 16:11 同步成功。
- 代理已切到日本高速01｜CTCU；切换后的四库同步退出码 0。
- Pixel 6 的 raviole、gs101、raviole-kernels、gs-common 四库于 17:26:11 同步成功，清单为服务器 `.repo/local_manifests/pixel6.xml`，分支 lineage-23.2。
- 设备同步日志与完整 revision 清单：`/srv/rom/home/pixel6-deps-C2EHyUWo/`。
- raviole-kernels 仅含模块配置，并非已准备好的内核二进制。根据 `vendor/lineage/build/envsetup.sh` 的 build_kernel 流程，另初始化官方 `android_kernel_google_gs-6.1_manifest`，在 `/srv/rom/android/out-kernel/google/gs-6.1` 下载真实内核源码与工具链。
- 内核下载 17:27:02 启动，j4，日本节点，tmux `rom:pixel6-kernel`；日志 `/srv/rom/home/pixel6-kernel-r3n7wAEP/sync.log`，结束后记录 `sync.exit`。未启动内核或 ROM 编译。
- oriole/extract-files.py 支持 Google 原厂镜像离线提取，不依赖手机连接。用户随后明确同意 Google 条款，已在官方页面接受，并启动 BP4A.251205.006 工厂镜像下载（约 3.25 GiB）。
- 官方镜像文件 `oriole-bp4a.251205.006-factory-dcfbf4cc.zip`，SHA-256 `dcfbf4ccc9559d7fdd9f8757b5c6d9bc8688ebf46ff1b5b001a786c89c4e1350`，来源 Google 原厂镜像页。保存在 `/srv/rom/downloads/`，下载中后缀 `.part`，校验成功才改名。
- 下载与提取日志目录 `/srv/rom/home/pixel6-factory-pNgYF0rq/`，分别记录 download.exit/extract.exit。tmux `rom:pixel6-vendor` 等待本次下载校验成功后执行设备官方脚本 `extract-files.py --extract-factory`；失败不进入下一步，已有 vendor 目录不自动覆盖。当前尚未验证提取完成，未执行镜像内的刷机脚本。
- 此轮仅恢复连接和准备依赖，未连接手机、刷机或运行单元测试/Lint。

## 尚未完成（初始记录）

- 用户已完成 Repo 初始化，并在 `rombuild` 的 tmux `rom` 会话中启动完整源码同步。最后一次只读检查确认仍在同步。
- 设备/vendor 依赖准备、lunch 目标验证和完整构建尚未完成。
- Ubuntu 的 Repo launcher 2.17 曾不支持 `--git-lfs`；用户已安装新版到 `~/bin/repo` 并成功初始化。
- 服务器访问 GitHub HTTPS 反复连接超时，Google 源码站连接超时。清华镜像帮助页复测 HTTP 200，只证明该页面可访问，未验证 Git 大文件同步。
- 以上为直连检测结果。后续已按用户授权安装官方 Mihomo v1.19.31，并导入用户提供的订阅；未修改防火墙或 SSH 服务设置。

## 源码下载代理

- `mihomo.service` 已启用，使用独立系统用户运行。
- 代理只监听 `127.0.0.1:7890`；管理接口只监听 `127.0.0.1:9090`，启用随机认证密钥。
- 订阅 URL 和原始内容仅 root 可读，运行配置仅 root 和 mihomo 组可读；不将订阅和凭据记录在仓库。
- 54 个有效节点中，48 个通过延迟初筛。前三个节点各进行了 GitHub、Google 源码站三轮 Git 服务端点访问，均为 6/6 成功。
- 选择 `香港专线04|BGP|住宅IP`：本次六次请求约 0.46–0.59 秒。短时测试不代表长期可用性保证。
- 使用 `rom-proxy <命令>` 为指定下载命令启用代理，例如 `rom-proxy repo sync`；没有全局接管系统路由。
- 测试记录位于服务器 `/srv/rom/logs/proxy-screen.json` 和 `/srv/rom/logs/proxy-validation.json`。

## 后续顺序

1. 通过 `rom-proxy` 验证 GitHub、AOSP Git 操作后启动源码初始化。
2. 使用 `rombuild` 用户在 `/srv/rom/android` 同步源码，日志持久化；源码与缓存不放系统盘。
3. 核对完整设备树、vendor、内核与固件；保存完整 revision manifest。
4. 检查实际源码定义的精确 lunch 目标，再执行未修改 ROM 基线构建。
5. 记录耗时、空间及内存峰值；云端构建不执行手机解锁或刷机。

上游设备元数据读取确认当前分支为 23.2，固件要求标注 Android 16；这并未验证用户手机的固件或 Bootloader 状态。

参考：https://github.com/LineageOS/lineage_wiki/blob/main/_data/devices/oriole.yml

## 准备工具交付

- 操作手册：`docs/rom-baseline-runbook.md`，包含上游依赖与固件核对来源，以及备份/安装/验收清单。
- 检查、构建、导出工具和测试已部署到 `/srv/rom/tools/preparation`，未修改正在下载的源码树。
- 7 项 fixture 测试在服务器以 `rombuild` 用户全部通过；覆盖缺失依赖、失败退出码、旧包拒绝、目标目录保护和校验值。
- 实际检查检测到 Repo sync 正在运行，按预期返回 3；不能将其描述为完整源码已通过预检。

服务器仍运行并按量计费，源码同步位于 tmux 会话。没有启动 ROM 编译，也未执行停机。
