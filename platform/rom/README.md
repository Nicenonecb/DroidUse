# ROM 集成

31 项底层能力的当前状态、服务器编译证据、失败关闭边界与实施阶段见 [后端实施计划](../../docs/rom-backend-plan.md)。本地模块检查、APK 预装缺口与补丁验证计划见 [集成检查](../../docs/rom-integration-audit.md)；权限与产品集成草案见 [integration](integration/README.md)。

目标设备：Google Pixel 6，代号 `oriole`。基于已有 LineageOS 适配进行开发。

已选 LineageOS `lineage-23.2` 的 frameworks/base 提交 `781c37c3f3c8566177b85ff80637e7affc834858` 作为本机开发参考，文件 SHA256 记录在 `source-lock.json`。这不是完整 ROM manifest，也不表示设备固件/内核/vendor 兼容性已验证。完整源码、vendor 二进制、私钥与构建产物放在独立工作区。

`patches/0001-scoped-editor-focus-query.patch` 是早期的焦点查询实验，目前没有应用到服务器分支。服务器已改用 Android 16 的 `ComputerControlSession` 作为隔离显示、截图和输入后端。实际通过编译的五仓库集成保存在 [server patches](patches/server/README.md)。

运行 `python3 platform/rom/tools/check_source.py --fetch` 下载并校验锁定的小范围源码、检查补丁可应用性。已有文件不覆盖；当前源码片段位于被忽略的 `build/source-reference`。检查通过仅表示源码/补丁匹配，不能代替 Soong 编译、系统启动和手机测试。

当前文字输入使用电脑控制会话的系统输入通道，触摸、按键、文字、删除和回车已经通过 Soong 编译；中文及复杂编辑行为仍需真机验证。保护心跳和分阶段故障回收已实现。UI 语义树、剪贴板、实体屏幕输入和 IME 编辑代次的完整验收仍未完成。

当前顺序：冻结服务器可实现的后端 → 构建工程 ROM → 权限/签名/SELinux 负向验证 → 虚拟显示和输入验收 → 电话音频探针 → 最终候选 ROM。

服务器检查、设备依赖、构建日志、产物导出，以及手机备份/刷机验收步骤见 [基线 ROM 操作手册](../../docs/rom-baseline-runbook.md)。准备脚本不会自动刷机；源码同步中会拒绝进入构建检查。

Mac 用于开发和 USB 调试；完整 ROM 构建安排在 x86-64 Linux 环境。具体要求参见 [LineageOS 编译指南](https://lineageos.github.io/lineage_wiki/devices/oriole/build/)。

`contracts/system-api` 保存可升级 Executor 与 ROM 之间的类型化 Binder 接口；`service` 保存失败关闭的系统服务源码负载。源码负载可先在 ROM 树外生成审查包：

```bash
python3 tools/linux/stage-rom-backend.py \
  --certificate-sha256 <Executor发布证书的64位SHA256摘要> \
  --certificate-purpose release \
  --output artifacts/rom-backend-oriole
```

该工具不会修改 AOSP、不会复制私钥，也不会生成表示 SELinux 已通过的活动标记。服务器锁定分支已完成 Soong 编译；真机验证仍未完成，因此当前产品配置仍故意不安装 `sepolicy-version` 活动标记。
