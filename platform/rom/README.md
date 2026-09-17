# ROM 集成

目标设备：Google Pixel 6，代号 `oriole`。基于已有 LineageOS 适配进行开发。

已选 LineageOS `lineage-23.2` 的 frameworks/base 提交 `781c37c3f3c8566177b85ff80637e7affc834858` 作为本机开发参考，文件 SHA256 记录在 `source-lock.json`。这不是完整 ROM manifest，也不表示设备固件/内核/vendor 兼容性已验证。完整源码、vendor 二进制、私钥与构建产物放在独立工作区。

`patches/0001-scoped-editor-focus-query.patch` 为 WMS 增加只读的指定显示窗口焦点核对：拒绝主屏，并核对窗口、UID/PID、实际显示、显示访问权限及输入目标资格。它不改变现有 IME 焦点策略，不公开 Binder，不授予输入权限；必须由后续持有隔离会话的系统服务调用。

运行 `python3 platform/rom/tools/check_source.py --fetch` 下载并校验锁定的小范围源码、检查补丁可应用性。已有文件不覆盖；当前源码片段位于被忽略的 `build/source-reference`。检查通过仅表示源码/补丁匹配，不能代替 Soong 编译、系统启动和手机测试。

独立输入尚缺 IMMS 连接注册与撤销、输入连接 sessionId 传播、客户端最终焦点校验以及受限系统 Binder 适配。不能将本补丁描述为“中文输入已完成”。资源隔离也仍需 native 执行点、实际保护心跳和失败回收适配。

首次集成顺序：未经修改的 ROM 构建和开机 → 内置执行模块 → 权限/签名/SELinux 验证 → 助手连接 → 实机任务。

Mac 用于开发和 USB 调试；完整 ROM 构建安排在 x86-64 Linux 环境。具体要求参见 [LineageOS 编译指南](https://lineageos.github.io/lineage_wiki/devices/oriole/build/)。
