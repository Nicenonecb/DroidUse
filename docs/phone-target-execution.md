# 手机端文件选择与跨应用执行

2026-09-18：实现已接入源码；按用户要求未运行构建、测试或真机验证。本节描述实现方式和限制，不是验收报告。旧APK和旧helper不会自动获得这些代码，需要后续构建、安装及重新激活helper。

## 执行链路

1. PhoneBridge会话启动查询已安装第三方应用的Launcher组件。
2. 截图生成新的frameId并清除旧目标。助手完成本地OCR、敏感页面判断后，将最多120行文字和坐标发送到已认证的`/targets`。模型不能直接调用此接口。
3. helper核对会话、帧和当前应用，从可见文件名、完整HTTP(S)地址及安装应用中生成随机targetId；回复不包含任意可执行Intent。助手将目标和逐项能力一起交给模型。
4. 模型选择`select_file`、`open_app`或`open_link`与targetId。应用和helper均校验目标类型；helper再次截图核对像素摘要，画面变化则返回STALE_OBSERVATION，不执行旧坐标。
5. 一次执行后旧帧和所有目标失效，重新观察结果。EXECUTED仅表示点击或启动已执行，不表示文件上传、业务任务或链接加载完成。

## 文件选择

支持`com.android.documentsui`和`com.google.android.documentsui`中有完整可见文件名的单文件行。只生成常见文档、图片、音视频和压缩文件扩展名候选；同名重复项不生成目标。执行器发送绑定后台display的点击，真实DocumentsUI负责选择、URI授权及向原调用应用交付ActivityResult，不伪造文件URI或回调。

模型需用下一张画面判断是否已返回调用应用、是否还有“打开/选择”确认，以及是否仍需点击上传。目录浏览和确认按钮沿用tap/swipe/back。纯视觉模式没有OCR文件行，不声明select_file。系统Photo Picker纯缩略图、第三方自定义选择器、隐藏或截断文件名、未知扩展名不会通过此语义动作虚假宣称支持，仍需界面操作或人工接管。文件名识别来自OCR而非系统节点；错误识别、同名目录等兼容性尚未验收。

## 应用与链接

`open_app`使用PackageManager命令查询出的明确Launcher组件，目标标签目前为包名。`open_link`仅处理当前OCR行中完整的HTTP(S)地址，通过系统解析具体组件；不接受模型自造URI、带用户凭据地址、fragment或系统resolver占位组件。每帧最多解析3条地址、返回100个目标；不能解析的链接可按普通可见链接点击，不会生成虚假句柄。

启动前排除主屏当前应用，并为目标包执行虚拟设备作用域的麦克风撤权（未请求该权限的包不做撤权）。继承DisplayHost的虚拟音频和相机策略。`am start -W --display`指定后台屏幕，NEW_TASK/MULTIPLE_TASK避免普通启动模式复用主屏任务；启动后等待目标包在后台恢复，无法确认则UNKNOWN_OUTCOME。BACK只注入后台display，由Android回退链接页/活动及原应用，不移动主屏任务；具体App的singleTask/singleInstance、外部跳转和返回行为仍须实机验收，不能承诺所有App隔离兼容。

本实现没有无障碍服务、全局剪贴板、任意文件读取或自动授权。它是手机shell实验执行器的实际执行代码，不是ROM Binder后端的替代品。ROM路径仍需独立系统集成。
