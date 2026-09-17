# 家中台式机：Ubuntu 与 ROM 构建准备

当前没有访问家中电脑；未下载多 GB ISO、未制作启动盘、未修改任何分区。现有 Intel CPU / 64GB 内存适合继续准备。AMD 显卡不需要为了 Android 编译更换。

## 回家后按顺序操作

1. 备份黑苹果分区里需要的文件。保存 Windows 的恢复密钥（若启用 BitLocker）和重要文件。
2. 在当前 Mac 运行 `python3 tools/linux/download-ubuntu.py` 查看 Ubuntu 24.04 LTS 官方桌面镜像与校验值；加 `--download` 才下载到 Downloads。脚本选择官方 SHA256SUMS 中版本最高的 amd64 桌面镜像，并在下载结束校验 SHA256。这里的校验基于官方 HTTPS 清单，不冒充 GPG 签名验证。
3. 制作 Ubuntu 启动 U 盘会清空所选 U 盘；先确认它的设备型号及容量。当前没有编写自动写盘命令，避免误选电脑的系统盘。
4. 在家中电脑选择 UEFI U 盘启动，进入“试用 Ubuntu”。先运行 `bash tools/linux/inspect-host.sh` 获取磁盘、文件系统、容量和启动模式。输出可以用于判断哪一个分区属于黑苹果。
5. 确认分区对应关系之后，安装器使用手动分区：只替换已经备份且确认的黑苹果分区，作为 ext4 根分区。Windows、恢复分区和共享 EFI 分区需要保留，不能照着“1TB”容量直接猜测并删除。现有 EFI 分区通常复用、不格式化；实际方案根据磁盘清单确定。
6. 安装完成后把 DroidUse 工程复制到 Ubuntu 的 ext4 目录。运行 `bash tools/linux/setup-ubuntu.sh` 预览，再加 `--install` 安装构建工具。该脚本不修改分区，也不执行刷机。
7. `tools/linux/prepare-source.sh /空目录` 先预览；加 `--sync` 才下载源码。已核对 lineage-23.2 的 manifest 和 raviole 远端分支存在，但 manifest 同步不等于设备/vendor 已齐备。脚本在同步后保存全树 revision 快照。
8. 准备完整源码树后运行 `bash tools/linux/check-build-tree.sh /你的源码目录`。源码、构建产物放 Linux 文件系统，避免放 Windows NTFS 分区。

## 容量与构建边界

Google 当前 AOSP 指南给出至少 400GB 可用空间和 64GB RAM；分支、缓存、旧产物会增加实际占用。回收黑苹果的约 1TB 可以留出更充足的增量编译空间，但“1TB”不是强制容量。若保留游戏后只有 400～600GB，应先清点真实可用容量，并只保留一个源码树、一个主要构建输出。

现有 `platform/rom/source-lock.json` 仅锁定少量 framework 参考文件，**不是完整可刷机 manifest**。完整设备树、vendor 文件、内核、固件版本以及目标 lunch 名称必须在首次系统编译前核对。先构建未修改基线，再应用补丁；不把仅通过 `git apply --check` 的补丁当成已能启动的 ROM。

工具安装脚本基于官方 AOSP 包列表，另外加入 adb、fastboot、JDK 17、Python 和 rsync；未在 Linux 实机运行，本机只做语法和平台拒绝检查。Android 系统构建以源码树自带工具链为准。

来源：
- [AOSP 构建要求与依赖](https://source.android.com/docs/setup/start/requirements)
- [Ubuntu 官方 24.04 下载目录](https://releases.ubuntu.com/24.04/)
- [Pixel 6 LineageOS 构建入口](https://wiki.lineageos.org/devices/oriole/build/)（本次网页抓取返回 403，不能以此声称已核实具体分支步骤）

设备树与 vendor 齐备后，`tools/linux/build-baseline.sh /源码目录 精确lunch目标` 预览构建；加 `--build` 才执行。目标名称必须取自实际 checkout，脚本不猜测 release 配置，也不自动刷机。

源码下载因网络中断时保留已有目录，在源码目录内运行 `repo sync -c -j8 --no-clone-bundle --no-tags` 续传，完成后执行 `repo manifest -r -o droiduse-manifest.lock.xml`。准备脚本要求空目录，不会替你删除或重置未完成的 checkout。
