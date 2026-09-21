# Pixel 6 原版 ROM：构建、交付与实机验收

准备日期：2026-09-18。适用设备：Google Pixel 6（`oriole`），不适用 Pixel 6 Pro（`raven`）。

本手册是准备工作，不能当作已完成编译或刷机的记录。当前源码同步由用户在 `rombuild` 用户的 tmux `rom` 会话中运行；不要并发启动另一份同步，也不要在同步中应用补丁、运行构建或修改 manifest。

## 1. 已核对的上游要求

从 LineageOS 官方 GitHub 源码读取：

| 项目 | 当前上游内容 | 本地还需要验证 |
| --- | --- | --- |
| 设备 | Pixel 6 / oriole | 手机 `ro.product.device`、Bootloader 解锁能力 |
| 系统分支 | 23.2 | 全树与设备依赖保持同分支，锁定实际 revision |
| 安装前固件 | Android 16；设备元数据标注 ROM 携带固件 | 手机当前版本、官方安装指南最新的升级/回滚限制 |
| 内核 | 6.1；设备配置引用 `device/google/raviole-kernels/6.1` | 当前仓库仅含模块配置；另同步 `out-kernel/google/gs-6.1` 源码与工具链，由 `lunch` 调用 `build_kernel` 生成二进制 |
| 设备依赖 | raviole → gs101、raviole-kernels；gs101 → gs-common | 继续递归读取实际 checkout 的 `lineage.dependencies` |
| vendor | 产品配置明确继承 `vendor/google/oriole/oriole-vendor.mk` | 按对应分支的官方提取流程准备 blobs；不能从任意 Android 版本混用 |

上游 `lineage_oriole.mk` 当前包含 Android 16 / `BP4A.251205.006` 的指纹字段。**产品指纹不是手机必须刷入该固件的充分依据**，不能据此直接刷旧版本；实际安装前核对官方设备安装指南和 Google 的防回滚说明。

来源：

- [官方设备元数据](https://github.com/LineageOS/lineage_wiki/blob/main/_data/devices/oriole.yml)
- [raviole 依赖](https://github.com/LineageOS/android_device_google_raviole/blob/lineage-23.2/lineage.dependencies)
- [gs101 依赖](https://github.com/LineageOS/android_device_google_gs101/blob/lineage-23.2/lineage.dependencies)
- [产品配置与 vendor 引用](https://github.com/LineageOS/android_device_google_raviole/blob/lineage-23.2/lineage_oriole.mk)
- [设备内核配置](https://github.com/LineageOS/android_device_google_raviole/blob/lineage-23.2/device-oriole.mk)
- [官方编译指南](https://wiki.lineageos.org/devices/oriole/build/)
- [官方安装指南](https://wiki.lineageos.org/devices/oriole/install/)
- [Google 原厂镜像及设备注意事项](https://developers.google.com/android/images)

本次官方 Wiki 渲染页面返回 403；上表依据可读取的官方仓库，不声称已核实 Wiki 中全部刷机步骤。执行刷机前必须能读取对应安装指南。

## 2. 下载阶段

服务器使用 `/srv/rom/android`；系统盘仅 20 GiB，不能用于存放完整源码。以 `rombuild` 用户操作，代理通过 `rom-proxy` 按命令启用。

若已在 tmux 内同步，只查看日志，不重复执行同步：

```bash
tail -n 60 "$HOME/rom-sync.log"
df -h /srv/rom
tmux list-sessions
```

停止后恢复现有下载使用下面的命令；不要对已有目录再运行要求空目录的 `prepare-source.sh`：

```bash
cd /srv/rom/android
export TMPDIR=/srv/rom/tmp
set -o pipefail
rom-proxy "$HOME/bin/repo" sync -c -j8 --no-clone-bundle --no-tags \
  2>&1 | tee -a "$HOME/rom-sync.log"
sync_status=$?
printf 'sync exit code: %s\n' "$sync_status"
```

只有退出码 0 才表示该次同步成功；进程消失、日志静止、目录变大都不等于完成。

Ubuntu 22.04 自带 Repo launcher 2.17 不支持 `--git-lfs`。服务器已由用户安装新版到 `~/bin/repo`；新机器可用官方地址安装：

```bash
mkdir -p "$HOME/bin"
rom-proxy curl -fL --retry 3 \
  https://storage.googleapis.com/git-repo-downloads/repo -o "$HOME/bin/repo.new" &&
chmod +x "$HOME/bin/repo.new" &&
mv "$HOME/bin/repo.new" "$HOME/bin/repo"
"$HOME/bin/repo" init -h
```

## 3. 首次构建前检查

以下 `$TOOLS` 指完整的脚本目录，必须包含 `.sh` 和 `.py` 文件。服务器部署位置为 `/srv/rom/tools/preparation`；本地仓库对应 `tools/linux`。

```bash
TOOLS=/srv/rom/tools/preparation
bash "$TOOLS/check-build-tree.sh" /srv/rom/android
```

该检查只读：正在同步时拒绝检查；列出缺失的设备/vendor/内核文件和递归设备依赖；逐个核对 manifest 项目目录及 Git HEAD。它不联网、不提取 vendor、不写源码，也不保证全部二进制依赖可用。

同步结束后还要逐项完成：

- 确认完整同步退出码 0，而不只是目录存在。
- 按官方 Pixel 6 编译流程获取缺失设备仓库和匹配 blobs，核对提取脚本以及原始镜像来源。不要把占位文件当成 vendor。
- 阅读 checkout 的 `AndroidProducts.mk`、产品配置、release 配置，以及官方构建指南，确定精确 `lineage_oriole-...` lunch 目标；此处不猜测 release 名称。
- 检查磁盘空间；已有源码下载后剩余空间要求不能直接等同于全新机器的要求。脚本低于 400 GiB 会提示，应结合当前源码和输出占用判断。
- 首次基线不应用 DroidUse 补丁。检查 `repo status`，确保没有未提交改动；保存完整 manifest。

## 4. 基线构建与日志

下面的 `精确目标` 必须替换为上一节核实的值。默认先预览，不触发编译：

```bash
bash "$TOOLS/build-baseline.sh" /srv/rom/android '精确目标'
```

确认检查通过，在独立 tmux 会话中启动构建：

```bash
tmux new -s rom-build
```

进入后执行：

```bash
export TMPDIR=/srv/rom/tmp
ROM_JOBS=8 bash /srv/rom/tools/preparation/build-baseline.sh \
  /srv/rom/android '精确目标' --build
```

默认并行数 8，首次基线先测量再调整。脚本要求非 root 用户；使用构建锁避免该脚本重复启动；固定输出到源码树 `out`；任何检查、lunch 或构建失败都返回非零。

每次运行在 `~/rom-build-runs/时间-随机后缀/` 留下：

- `manifest.xml`：全树 revision；`source-status.txt`：构建前改动状态。
- `target.txt`、`source-root.txt`、`started.txt`：构建目标、目录、开始时间。
- `build.log`：标准输出及错误；`resources.txt`：耗时、最大驻留内存等。
- `exit-code.txt`、`finished.txt`：结束状态和时间。强制断电或 SIGKILL 可能没有这些文件，必须视为未完成。
- 仅成功后写 `product-out.txt`。成功编译不代表手机已能启动。

`/usr/bin/time` 统计的是命令及子进程资源信息，不应把其 RSS 数值当作整机并行进程的总内存峰值。

## 5. 导出产物到 Mac

用实际运行目录替换下面的 `本次运行目录`，目标目录必须不存在：

```bash
python3 "$TOOLS/export-rom.py" "$HOME/rom-build-runs/本次运行目录" \
  "$HOME/rom-deliveries/本次版本"
```

导出器要求成功退出码及版本记录，拒绝本次构建开始前的旧 ROM ZIP；收集最新 oriole ROM ZIP、存在的安装相关镜像和日志，生成 SHA256SUMS。复制全部完成才生成 `COMPLETE`。不收集签名私钥，不自动上传或刷机。

在 Mac 的本地终端执行（替换版本目录名）：

```bash
mkdir -p "$HOME/Downloads/DroidUse-ROM"
scp -i "$HOME/Downloads/rom.pem" -r \
  root@14.103.19.154:/srv/rom/home/rom-deliveries/本次版本 \
  "$HOME/Downloads/DroidUse-ROM/"
cd "$HOME/Downloads/DroidUse-ROM/本次版本"
shasum -a 256 -c SHA256SUMS
```

校验成功后再考虑安装；`COMPLETE` 只表示复制完成，不表示 ROM 可刷机或安全启动验证通过。

## 6. 手机备份与安装前清单

- [ ] 确认是 Pixel 6/oriole，不凭外观或设备昵称判断。
- [ ] 确认开发者选项中 OEM 解锁是否允许，并核实运营商/设备限制。
- [ ] 备份照片、文件、聊天记录、验证器恢复码，以及必要的 eSIM/账号恢复资料；实际打开备份抽查。
- [ ] 确认已了解 Bootloader 解锁和格式化数据会清空手机数据。准备阶段不执行这些操作。
- [ ] 记录当前 Android、固件、安全补丁版本、Bootloader 状态和当前槽位，核对官方最新要求及防回滚限制。
- [ ] 准备匹配设备的官方恢复资料，确认下载来源及校验值；不随意降级，也不在自定义 ROM 上盲目重新锁定 Bootloader。
- [ ] Mac 上 ADB/Fastboot 可用，USB 数据线稳定；手机电量充足。
- [ ] ROM、boot/dtbo/vendor_boot 等实际安装所需文件来自同一套匹配构建，校验全部通过。
- [ ] 逐步遵循当前官方 oriole 安装指南；刷写分区、切槽、解锁、擦除等操作留到单独的实机阶段确认。

可先在 Mac 运行的只读检查（需手机已连接并授权 USB 调试；多设备时使用 `adb -s` 指定目标）：

```bash
adb devices -l
adb shell getprop ro.product.device
adb shell getprop ro.build.version.release
adb shell getprop ro.build.fingerprint
adb shell getprop ro.build.version.security_patch
adb shell getprop ro.boot.flash.locked
adb shell getprop ro.boot.slot_suffix
```

不要把账号数据、完整设备序列号和私人截图放进公开仓库。

## 7. 验收顺序与记录

| 阶段 | 检查 | 通过标准 |
| --- | --- | --- |
| 原版基线 | 首次启动、重启、触摸、显示、充电、ADB | 可稳定进入系统并重复启动 |
| 基础硬件 | Wi-Fi、蓝牙、相机、麦克风、扬声器、SIM/通话（按实际可用条件） | 与预期一致，失败项目有记录 |
| 日志 | 启动与崩溃日志、SELinux denial | 不以关闭 SELinux 掩盖问题；问题可定位 |
| DroidUse 服务 | 服务启动、权限、IPC、助手连接 | 未授权调用被拒绝，正常请求成功 |
| 输入及隔离 | 点击、滑动、中文输入、后台显示、前台互不干扰 | 分项完成实机测试；尚未实现项不得标为通过 |
| 异常路径 | 人工接管、进程退出、取消任务、资源回收 | 不继续执行失效任务，无残留输入或占用 |
| 完整任务 | 项目现有验收矩阵中的代表性任务 | 保存版本、步骤、结果及脱敏证据 |

每次记录：ROM 版本/校验值、manifest、手机原始固件、安装日期、通过项、失败复现步骤、日志位置。私有证据留在本地。

原版开机通过前，不将 DroidUse framework 补丁加入基线。之后按“内置模块 → 权限及 SELinux → 系统能力 → 完整任务”逐层验证。
