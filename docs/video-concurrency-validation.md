# Pixel 6 主屏视频与后台 tiny 并行验证

## 范围

Android 16 / Pixel 6；主屏 VideoActivity 使用系统 MediaPlayer 播放本地生成的无声 H.264 1080p30测试视频。后台为真实 PhoneBridge 隐藏屏幕截图与正式 PaddleOCR tiny，双线程推理、真实资源检查和日志路径。没有调用云端模型，也没有模拟OCR结果。

这验证渲染和CPU/内存竞争，不等同于哔哩哔哩联网播放、音频焦点、多小时发热或低电量验收。

## 有效复测结果

证据 `build/runtime-service-evidence/video/`：instrumentation.log、ocr-summary.json、playback.log、phase-times.json、comparison.json。

- 并行测试40.87秒，26次后台观察；截图+OCR平均1494ms/次，不是单独OCR耗时。
- 助手进程最大采样PSS 313963 KiB，约306.6 MiB；不是系统总内存，也不是高频采样峰值保证。
- 热状态开始/结束均为0。
- 基线6个窗口：播放/渲染/主屏焦点采样均正常；解码帧增量753，丢帧增量0；UI超过50ms间隔0。
- 并行7个窗口：播放/渲染/主屏焦点采样均正常；解码帧增量902，丢帧增量0；UI超过50ms间隔0。
- 两阶段每个窗口UI帧间隔P95均33ms。Choreographer测量和视频解码器指标是不同的指标，不能互相替代。
- 统计排除测试开始7秒和结束3秒的过渡；观察窗口数量不同，帧增量不能直接作为快慢比较。指标按5秒采样，不保证捕获所有极短焦点变化。

这轮未观察到需要降低tiny双线程设置的证据；保持当前设置及资源保护阈值。不能据此宣称所有主屏App无卡顿。

## 首轮受干扰记录

`video-trial1-interrupted/`原样保留。后台期间主屏曾回到Launcher再回到视频，Surface重建导致播放器帧计数重置，原因未确认。不能把该次计数相减为负数当作性能数据，也不能把主屏失焦直接归因于OCR。分析脚本现检测计数重置，遇到重置不报告帧数差值。

## 复现

1. `python3 tools/phone/prepare_video_fixture.py`，再运行probe.py build并重建assistant APK。
2. bootstrap安装匹配的APK/helper及测试APK。
3. 在主屏启动`dev.droiduse.probe/.VideoActivity`，至少记录30秒基线。
4. instrumentation以`videoConcurrency=true`运行VideoConcurrencyTest；测试期间保持主屏视频，记录DroidUseVideo日志及起止epoch。
5. 收集no_backup/video-concurrency.json与阶段时间，运行`tools/phone/analyze_video_concurrency.py`。

VideoActivity/视频素材只属于实验夹具，视频素材位于忽略的build目录。实验结束已关闭测试播放器。
