# 本机实现与验证范围

用户确认暂时没有 Linux 编译机，本轮完成可本机验证的部分，不刷机。

## 已落地

| 产物 | 验证 | 不代表什么 |
| --- | --- | --- |
| Kotlin IsolationSession | 启动门禁、缺任意保护拒绝启动、心跳失效、迟到回调、取消、先停目标再释放隔离 | 尚未接到 AudioService/CameraService，不能靠状态机保护物理资源 |
| Kotlin IndependentEditor | 中文/emoji/换行原文保留、无效 UTF-16 拒绝、代次校验、去重、未知结果不重放 | 测试接收端是 Fake Platform，不是第三方 App 的 InputConnection |
| 20 组 JVM 场景 | `python3 platform/executor/runtime/test.py` 全通过 | 不能记成 115 项手机功能用例通过 |
| 固定源码与校验脚本 | 8 个文件 SHA256 校验通过，补丁适配检查通过 | 不是完整 ROM manifest，更不是系统编译成功 |
| WMS 指定显示窗口焦点查询补丁 | 对固定 LineageOS 提交可应用；拒绝主屏和窗口/UID/PID/显示不匹配 | 不改变主屏 IME，不接通后台编辑连接，不解除现有焦点限制 |

新增的库级 Android.bp 尚未在 Soong 验证。没有将框架补丁装到当前原厂 Pixel 6，因此也没有把本轮主机测试写成新的手机实测记录。

## 源码调查后的待实现项

LineageOS 参考版本的 WMS `hasInputMethodClientFocus` 校验顶层焦点显示；IMMS 拒绝后台显示的输入请求。只改变该校验还不足以解决问题：必须保留主屏绑定、单独管理后台连接，并让客户端最后提交时再次校验窗口/连接代次。

`RemoteInputConnectionImpl` 使用 `InputConnectionCommandHeader.mSessionId` 匹配当前连接内部代次；不能假设永远是 0，也不能把执行会话的 generation 直接当作这个 sessionId。两种代次要分别维护。Binder 的 commitText 是异步投递，DISPATCHED 不能当作实际文本已经写入成功，之后需观察目标。

后续适配顺序：

1. IMMS 独立连接注册、更新和撤销，以及正确的客户端 sessionId 传递。
2. 受限系统 Binder 接口、调用者校验、客户端死亡处理和控制线程。
3. 将真实资源保护状态接到启动门禁；后台跨 App/进程迁移同步受控。策略失效后的 native 拒绝不能依赖 APK 回调及时执行。
4. 实现客户端最终校验，覆盖窗口切换和提交之间的竞态；无连接或不兼容编辑器返回明确失败。
5. Linux 全树编译、启动及实机验证；先中文和故障隔离，再逐项执行 115 个功能用例及真实 App 场景。

这份记录保留未完成项，防止把协议测试、补丁可应用性与实机功能混为一谈。基础 ROM 仍未到可冻结状态。
