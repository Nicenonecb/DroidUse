# M3 + 蓝熊 UI 合并构建 r8

最新状态：已刷入、解锁、联网，并完成本批 M3 与 M2 回归共 8 项真机测试，均通过。详见 [M3 实机验收](m3-device-validation-2026-09-24.md)；下面保留构建、准备和刷机各阶段记录。

## 实机安装更新

2026-09-24 用户明确授权继续刷机后：已退出旧 overlayfs、经 Updater 完整包验证，执行保留数据 OTA。更新引擎返回 `kSuccess (0)` 和 `UPDATED_NEED_REBOOT`；重启确认构建时间戳 `1790234521`、槽位 A、boot_completed=1。干净系统已报告 `SCOPED_LAUNCH_AND_PICKER` 与 `SCOPED_TEXT_CLIPBOARD`，缺少工程标记时按设计 `coreReady=false`。

已更新覆盖系统预装版本的两个 APK，设备文件 SHA-256 与 r8 交付一致；从备份仅恢复原工程 `sepolicy-version` 标记，未恢复旧服务/图标覆盖文件，SELinux 保持 Enforcing，`coreReady=true`。再次重启确认标记保留。设备实际资源查询确认圆角方形 mask，7 个品牌图标 overlay 均启用。功能测试尚未运行：当前等待用户再次解锁，随后恢复联网、验证剪贴板和跨应用行为及桌面图标显示。不得将安装成功计为 M3 功能验收通过。

## 结果更新

2026-09-24 **15:51:50 北京时间**构建与归档结束，退出码 **0**；日志包含 `M3_DEX_AND_BRANDING_VERIFIED` 和 `BUILD_AND_ARCHIVE_OK`。framework/services 及完整 ROM 编译已通过，M3 类和能力标识、727 个保留资源的摘要、图标与品牌产物检查通过。完整包 SHA-256：`82e24ff7f018214208d051a0016fdbd813dc8cf3bfd767b41abf364d80274850`。尚未刷机或完成 M3 真机验收。

后续准备：本机交付目录 `/Users/justin/Downloads/DroidUse-ROM/r8-m3-ui-20260924`。已识别 USB Pixel 6/oriole，当前槽位 B，电量 100%，无活动 DroidUse 会话；应用数据、共享存储和现有调试覆盖层备份在其私有 `preflash/` 目录，归档内容可读取。备份不包含其他应用私有数据、完整 Keystore、eSIM 或账号恢复资料，不作为擦除数据后可恢复的保证。当前仍挂载调试覆盖层，安装前须退出并重启；实际写入前等待用户明确继续刷机。

15:58 后本机下载完成：18 个产物的 SHA-256 全部匹配，完整 ROM ZIP CRC 检查通过；包内 OTA 公共证书与手机现有信任证书一致（完整签名仍须由 Updater 验证）。包为 oriole A/B OTA，目标时间戳 `1790234521` 新于手机的 `1790222239`，安全补丁日期同为 2026-09-01。准备状态保存在本机 `verification/resume.json`；尚未退出覆盖层、重启或安装更新。下一步按保留数据 OTA 流程进行，预计安装和重启 15–30 分钟，保持 USB 连接，重启后可能需用户解锁。

## 已完成的集成

- r7 自适应图标和圆角方形遮罩版本于 2026-09-24 14:04（北京时间）归档成功，退出码 0。其目录与成品保留：`/srv/rom/home/redbear-branding-20260924/build-r7-adaptive-square-icons-20260924`。
- 本轮在 `/srv/rom/android` 合入 M3 跨应用目标、系统选择器、会话文本剪贴板、0010/0011 framework 补丁，以及本地重新构建的 Assistant 和 Executor APK。
- 两个 APK 的证书 SHA-256 均为 `4eecb51901c9757f2dca4be8ec27f6c41d33b81b3b56750a62d1997c45cd0b1e`，匹配 ROM 工程信任配置。
- 集成更新 18 个文件，逐文件校验保留了 727 个品牌/界面资源文件。受影响文件旧版保存在本轮目录的 `before-integration.tar`，另存文件摘要、framework 原始 diff、集成清单。
- 上传包 SHA-256、backend 清单、补丁可应用性及 `git diff --check` 通过。未刷机。

## 当前后台任务

- 服务器：`root@14.103.19.154`；构建用户：`rombuild`。
- 源码工作目录：`/srv/rom/android`。
- 本轮记录目录：`/srv/rom/home/m3-integrated-20260924/r8-m3-ui-20260924T151246`。
- 启动：2026-09-24 **15:14:25 北京时间**；tmux：`droiduse-m3-ui-r8`；启动确认 pane PID：`102031`。
- 实际命令：`bash /srv/rom/home/m3-integrated-20260924/r8-m3-ui-20260924T151246/run_build.sh /srv/rom/home/m3-integrated-20260924/r8-m3-ui-20260924T151246`。
- 脚本获取 `.droiduse-build.lock`，配置 `lineage_oriole-bp4a-userdebug`，依次执行 `m framework-minus-apex services -j16`、`m bacon -j16`，并执行品牌资源和 M3 DEX 检查、产物归档及校验。
- 估计 15–30 分钟，参考此前 framework/services 约 9 分钟、r7 完整增量约 9 分钟；本轮 framework 改动可能扩大重编译范围，估时不保证。建议 15:35 后查看。
- 启动后仅作一次确认，按 AGENTS.md 交接，未持续轮询。此记录不是构建成功证明。

## 查看与成功条件

```bash
ssh -i "$HOME/Downloads/rom.pem" root@14.103.19.154 \
  'r=/srv/rom/home/m3-integrated-20260924/r8-m3-ui-20260924T151246; tail -n 30 "$r/build.log"; if test -f "$r/exit-code.txt"; then cat "$r/exit-code.txt"; else echo "尚无退出码，需结合进程状态判断"; fi'
```

成功要求：`exit-code.txt` 为 0，日志包含 `M3_DEX_AND_BRANDING_VERIFIED` 与 `BUILD_AND_ARCHIVE_OK`，且 `artifacts/SHA256SUMS` 校验通过。非零退出码表示失败；无退出码或日志停止增长均不能单独表示成功。

预期 ROM：`artifacts/lineage-23.2-20260924-UNOFFICIAL-oriole.zip`，同时归档 boot/dtbo/vendor_boot/vbmeta 镜像、Assistant/Executor APK 和品牌资源。r7 中只允许固定旧 services.jar 摘要的检查已在本轮脚本中替换为 M3 类/能力标识检查；品牌资源检查保留。

服务器需保持开机和磁盘可写。后台任务可脱离 Mac 运行；编译期间手机无需连接 USB。用户回复“继续”后先核对本轮退出码、日志和产物，成功则准备下载、校验及刷机前检查；实际刷入前仍等待明确继续刷机。新 ROM 的开机、图标缓存刷新、M2 回归与 M3/剪贴板实机验收均尚未完成。
