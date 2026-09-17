# 虚拟设备隔离进一步实测 · 2026-09-17

设备：Pixel 6，Android 16，CP1A.260405.005。基于本项目可编译的 Java/Dex/APK 原型；仍是 ADB shell 驱动，未刷 ROM，未实现手机独立执行服务。

## 本轮改动

- 为 DisplayHost 增加 VirtualDeviceManager 模式，配置 AUDIO/CAMERA/RECENTS 自定义策略、每设备输入法和虚拟音频捕获/注入通道。
- 修正 app_process 实验宿主的 Application/AttributionSource：此前 native 音频服务看到 UID=2000、包名=android，不匹配，导致 AudioMix 初始化失败。修正后后台输出路由从实体扬声器变成虚拟通道。
- 向虚拟音频注入持续全零样本，不读取或保存用户音频；API 返回只标为 REQUESTED，路由和主屏状态另行验证。
- 加入只针对测试包、非默认设备的录音撤权诊断命令；增加实际设备权限、录音路由、主屏 silenced 状态观测。
- 新增资源实测脚本与 115 项功能验收清单。相机仅打开/关闭设备，不建立拍摄会话。

## 独立中文输入：未解决

指定每设备 ProbeIme 后，后台编辑框仍没有得到可独立提交中文的编辑连接；主屏 Gboard 保持原配置。写入非默认设备剪贴板成功，但后台读取被系统焦点检查拒绝，粘贴未产生中文文本。不能把剪贴板写入成功当成输入成功。

源码核对显示，ClipboardService 的虚拟设备读取条件依赖全局顶层焦点显示；IMMS 仍需要处理设备输入法选择及客户端焦点。修复需要独立编辑会话和严格的窗口/显示绑定，具体约束见[系统集成门禁](system-isolation-integration.md)。本轮没有提供已编译、已验证的 ROM 补丁。

## 资源实测：部分取得有效结果

最终配置：虚拟设备 9 / 显示 21，自定义音频/相机策略，虚拟音频通道持续零样本注入，后台测试包在该 companion 设备范围内的 RECORD_AUDIO 已撤销；两个测试包在默认设备上的 RECORD_AUDIO 均授予。前后台为不同 UID。

| 检查 | 实际观测 | 限定结论 |
| --- | --- | --- |
| 音频焦点 | 前后台申请均返回 1；测量窗口内主屏无 LOSS 回调 | 此场景中焦点分离，不代表所有音频类型已覆盖 |
| 播放路由 | 主屏 route=2（扬声器）；后台 route=25（REMOTE_SUBMIX） | 测试轨道输出路由隔离有效；没有用有声媒体做听感验收 |
| 麦克风申请 | 主屏权限 0、录音成功；后台权限 -1、AudioRecord 初始化失败 | 最终配置下测试入口在创建阶段被拒绝 |
| 主屏录音 | 后台请求前后 ownSession=841，silenced=false，route=15 | 首轮有效结果中主屏录音未受影响 |
| 相机枚举 | 主屏 CAMERA_OPENED；后台 CAMERA_UNAVAILABLE | 后台未得到实体相机列表 |
| 直接指定相机 ID | 后台打开 cameraId=0 被拒绝：Invalid camera id for device id 9；主屏未收到 DISCONNECTED | 指定实体 ID 也未突破此虚拟设备边界 |

最终配置复跑得到同样结果：主屏录音 ownSession=881 在后台申请前后均 silenced=false；后台初始化被拒绝；后台播放 route=25，主屏 route=2；顶层 FocusedDisplayId=0。两轮均为受控不同 UID 的测试，不能推广为任意 App 已全部兼容。

注意：前几轮仅设置设备策略/设备权限，甚至虚拟音频 API 已返回，后台旧式 AudioRecord 仍能走实体麦克风，并使主屏 silenced=true。保存了失败证据。因此最终结果仅证明当前受控配置有有效路径，尚未证明启动竞态、路由失效、同 UID 两屏、所有旧 API 都被强制保护。生产服务必须先验证策略就绪再启动目标 App，故障时阻止后台继续使用资源。

原始证据（本地 build 下，不提交设备日志）：

- `platform/executor/prototype-virtual-display/build/virtual-device/scoped-mic.txt`：显示 18，设备权限拒绝但旧录音启动，主屏被静音的反例。
- `platform/executor/prototype-virtual-display/build/virtual-device/resources-attribution-failed.txt`：音频通道未实际建成时的反例。
- `platform/executor/prototype-virtual-display/build/virtual-device/resources-first-protected.txt`：显示 21，首次资源保护有效结果。
- `platform/executor/prototype-virtual-display/build/virtual-device/resources.txt`：最终复跑日志。
- 同目录 `focus.txt`、`device-policy.txt`：顶层焦点与设备策略快照。

## 功能验收范围

[115 项用例](acceptance-matrix.md)需逐项执行。当前完成的是隔离阻断问题的专项实验，不是 115 项全部通过。后续依次覆盖受控控件页面、真实 App、接入模型后的完整任务；测试动作效果与隔离副作用分别记账。双指事件投递、控件缩放效果和模型识别成功必须分别验证。

当前尚不可冻结最终基础 ROM。尚缺锁定的 LineageOS 源码与可用 Linux 构建环境；独立输入、强制资源归属和失效保护实现后还需实机回归。

收尾检查：测试包资源已释放、相机/录音测试授权已撤销、测试 AppOps 已重置；宿主退出，临时 companion 关联已移除，active virtual devices=0，默认输入法仍为 Gboard。保留测试 APK 和本地构建证据，未修改真实 App 权限。
