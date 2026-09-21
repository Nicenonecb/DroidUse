# 权限、启动与 SELinux 草案

这些 .example 文件未接入产品构建，无 APK、私钥或自动授权。

## Pixel 6 预装包准备

目标暂定 `oriole` / arm64；不需要连接手机。使用 `tools/linux/stage-rom-apps.py` 将已经签名的 release APK 检查并复制到新的暂存目录：

```bash
python3 tools/linux/stage-rom-apps.py \
  --assistant /path/to/assistant-release.apk \
  --executor /path/to/executor-release.apk \
  --certificate-sha256 <专用发布证书的64位SHA256摘要> \
  --apkanalyzer /path/to/android-sdk/cmdline-tools/latest/bin/apkanalyzer \
  --apksigner /path/to/android-sdk/build-tools/36.0.0/apksigner \
  --output artifacts/rom-apps-oriole
```

上述路径和摘要需替换为实际值。证书摘要从可信的发布证书记录取得，不能直接把待检查 APK 自报的证书当作信任来源。工具不生成、读取或上传签名私钥，也不构建 APK。当前 Gradle release 未配置发布签名，必须先完成正式签名流程；不以 debug 包代替。

检查包括 APK 签名验证及同一指定证书、包名、非 debuggable/testOnly、禁止 shared UID、执行服务的 signature 权限、助手请求权限、已知调试/私密文件排除、tiny OCR 资产及 arm64 原生库存在性。文件名扫描不能证明 APK 内绝无硬编码秘密；原生库存在也不证明能正确加载，仍需后续审核和运行验证。

成功输出两个 APK、`Android.bp`、`product.mk`、带 APK SHA256 的 `verification.json` 和最后写入的 `COMPLETE`。拒绝覆盖已有输出，也拒绝写入带 `.repo` 的源码树。可在同步期间离线准备，不影响下载。

同步结束且原版基线构建通过后，才把完整暂存目录作为 `vendor/droiduse` 接入独立集成分支，并在 oriole 产品中显式继承 `vendor/droiduse/product.mk`。生成的 Soong 配置仍待实际分支验证，尤其是预签名 APK 的处理与对齐要求。不要同时再把 runtime 库作为独立 APK 安装；它已作为 executor 的 Gradle 依赖打包。

撤回预装集成：移除产品继承及对应独立集成提交后重建。APK 通过检查不改变 `ready=false`，也不表示系统隔离后端已实现。

## 当前权限

| 声明 | 用途与处理 |
| --- | --- |
| dev.droiduse.permission.EXECUTE | executor 定义 signature，assistant 请求；同一专用签名，保留服务权限及逐次 UID/包名/签名检查 |
| INTERNET | 助手访问模型服务；不等于允许 executor 任意联网 |
| FOREGROUND_SERVICE / FOREGROUND_SERVICE_SPECIAL_USE | 助手 TaskRuntimeService；保留 specialUse subtype 声明 |
| POST_NOTIFICATIONS | 运行时权限；由用户交互处理，不借特权白名单自动授予 |

当前没有需要填写的系统特权权限。示例将两个 APK 作为 system_ext 普通系统应用；后续若真正需要 privileged，必须逐项论证并调整分区白名单。不能通过添加 INJECT_EVENTS、MANAGE_ACTIVITY_TASKS 等权限代替系统隔离实现。

## 服务启动配置

保持现有 Manifest：executor 的 ExecutorService exported=true 且受 EXECUTE 保护；助手通过显式 ComponentName + BIND_AUTO_CREATE 按需绑定。助手 TaskRuntimeService exported=false，由用户开始任务后启动前台服务。当前不添加 init.rc、BOOT_COMPLETED 或 root 常驻进程。APK Service 不是 init 原生服务；未来需要 native 守护进程时另定义可执行文件、专用 UID、生命周期和 Binder 接口。

## SELinux 策略草案

当前不安装自定义 .te：现有 APK 尚无系统执行后端，也无 service_manager 注册名或 native 可执行文件。凭空添加类型/allow 会掩盖尚未实现的边界。

| 将来组件 | 预期边界 | 落实策略前所需证据 |
| --- | --- | --- |
| assistant APK | 普通应用沙箱；只访问授权执行接口 | 实际进程域、Binder 调用路径、必要 AVC |
| executor APK | 独立 UID；只接受获授权助手和会话 | 签名到 seinfo/域映射方案、最小系统服务访问范围 |
| system_server 适配 | 验证 UID、会话、显示、连接代次 | 接口注册名、service_contexts 类型、查找与调用方 |
| 可选 native 资源守护 | 独立域、仅操作指定资源 | 是否确实需要、exec 路径、file_contexts、init UID、资源标签及崩溃清理 |

禁止通配放行、permissive 域、任意 shell/设备节点访问和批量照抄 audit2allow。收集 AVC 后逐条先判断操作是否应被允许，再写最小类型/规则；保持 enforcing，复核 neverallow 和跨分区接口。SELinux 不识别 Bundle 内 sessionId，不能替代 Binder 层鉴权。

后续实机验证：不同签名/错误包调用被拒绝；普通应用不能取得内部服务；合法助手绑定成功但后端未就绪仍拒绝执行；取消/客户端死亡先停止实际任务；检查启动拒绝、权限拒绝及 AVC。这里只列验证计划，本轮未运行。

官方依据：[特权权限白名单](https://source.android.com/docs/core/permissions/perms-allowlist)、[SELinux 定制](https://source.android.com/docs/security/features/selinux/customize)、[策略编写](https://source.android.com/docs/security/features/selinux/device-policy)。
