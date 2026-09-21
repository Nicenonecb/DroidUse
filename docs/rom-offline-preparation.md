# Pixel 6 无手机阶段准备

2026-09-19：按用户指定暂定 Pixel 6 / oriole，不连接手机、不读取固件、不解锁或刷机。未跑单元测试、Lint 或完整 ROM 编译。

## 本轮落地

- BinderTaskExecutor 拒绝重复 begin 覆盖已有会话，保留原来的取消句柄；取消失败仍保留会话，不能冒充清理成功。
- ExecutorService 在注册客户端死亡通知时处理 RemoteException，返回 DISCONNECTED；当前没有实际目标进程需要回收。
- `tools/linux/stage-rom-apps.py` 准备可审查的双 APK 预装目录和摘要清单，使用调用方指定的发布证书摘要。使用步骤及检查边界见 `platform/rom/integration/README.md`。
- 现有权限、按需绑定、SELinux 草案沿用 integration 文档；不凭空添加系统权限或 permissive 策略。

## 系统后端接入顺序与阻塞点

| 阶段 | 输入与产出 | 完成条件 |
| --- | --- | --- |
| 基线 | 完整源码、oriole 设备依赖、匹配的 vendor/内核 → 原版构建 | 同步退出码 0，构建成功；开机验收待有手机 |
| 预装 | 同签名 release APK → vendor/droiduse 导入 | 暂存检查和真实 Soong 编译通过 |
| 焦点查询补丁 | 核对 frameworks/base 实际提交 → 独立补丁提交 | 真实源码 apply --check 及受影响模块编译通过 |
| 系统会话 | 系统侧持有 UID、token、epoch、隔离 display、保护租约 | 客户端和 executor 死亡不使保护失效；不能仅在 APK 内持有权威状态 |
| 独立输入 | IMMS 注册/撤销，输入连接 sessionId/代次传播 | 最终执行端重新核对会话、焦点和代次；过期或跨显示请求拒绝 |
| 资源保护 | 音频输出/焦点、麦克风、相机、逃逸防护执行点 | 所有保护真实安装并带有效心跳，才允许 launch |
| 故障收尾 | 取消、死亡、显示销毁、租约到期 | 先撤销动作资格，停止并排空目标，再释放保护；失败保持 STOPPING/RELEASING 并重试 |

现有 IsolationSession、IsolationCoordinator、ProtectionSupervisor 描述上述状态顺序，但没有实现 Android 系统执行点。不能靠把它们实例化到 ExecutorService 就宣称实现了隔离。

系统侧适配尚未实现，继续保留 `ready=false` 和空动作列表。下一步代码必须基于实际同步完成的 framework 分支确认接口与锁顺序；不向正在同步的源码树直接套入未验证补丁。

## 后续待验证

获得签名 release APK 后运行暂存工具；本轮没有用调试 APK 代替。完整源码就绪后检查实际 framework 提交与 source-lock 是否一致，记录完整 manifest 快照，然后推进原版编译及独立集成分支。手机型号、固件、解锁条件和运行验收均留到连接手机之后。
