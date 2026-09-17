# Pixel 6 本地 OCR 对照实验（2026-09-17）

## 范围与实现

本次试验 PP-OCRv6 tiny、small，对照已有 ML Kit 中文 OCR。全部在用户的 Pixel 6（Android 16）上离线识别；Mac 仅编译安装、启动 instrumentation 和收集结果，不参与 OCR，也未向云模型发送测试截图。现有任务默认 OCR 暂不更换。

PaddleOCR 官方 Android SDK 源码固定于 `dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf`，位于 `third_party/paddleocr`，保留 Apache-2.0 许可证。SDK 仅作为 androidTest 依赖；模型和真实截图保存在忽略的 build 目录，只打进测试 APK，不进入正式应用或 release。

官方示例所用 QuickBird OpenCV 4.5.3 在 Android 16 实机加载时报缺少 `__sfp_handle_exceptions`，改用官方 `org.opencv:opencv:4.12.0`。ONNX Runtime 保持官方示例版本 1.21.1。两个 Paddle 模型均设置 2 个 CPU 推理线程，其余采用 SDK 默认参数，直接识别原始 720×1280 图片；ML Kit 使用当前 2× 全图及条件式 3× 标题补识别实现。

## 样本与指标

45 张真实截图：2 张曾经识别错误的首章、1 张按钮书名错误页面、1 张候选列表、1 张书籍详情，以及同一阅读任务连续 40 页（包括第四章边界）。图像哈希及样本清单由准备脚本生成，保证各引擎输入一致。

人工核对的 11 个文字检查项覆盖章节标题、书名、评分、字数和标签。匹配时忽略标点及空白，但不替换汉字。42 张有页码的图片要求读取页码准确，并另外用真实 ReadingProgress 检查完整的 40 页是否连续且能确认前三章完成。这个检查集不是全文字符错误率评测，不能把检查项命中率称为总体 OCR 准确率。

每个模型独立启动一次测试进程，记录加载、首次推理、逐图耗时、中位数、P95、文字结果和坐标。PSS 为逐图采样的整个测试进程内存，包含测试框架等，并非 OCR 独占内存或瞬时峰值。热状态按 Android PowerManager 记录；本轮没有测量用户同时播放视频时的掉帧。

## 复现

```bash
python3 tools/ocr/fetch_models.py
python3 tools/ocr/prepare_benchmark.py
./gradlew :assistant:assembleDebugAndroidTest
adb -s 设备序列号 install --no-incremental -r apps/assistant/build/outputs/apk/androidTest/debug/assistant-debug-androidTest.apk
python3 tools/ocr/run_benchmark.py --serial 设备序列号
```

需要已安装匹配的助手 debug APK，以及原始实测截图。下载脚本校验固定 SHA-256；默认 instrumentation 测试不会主动跑本实验，必须显式传入 ocrModel。

来源：[官方 SDK](https://github.com/PaddlePaddle/PaddleOCR/tree/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/deploy/ppocr-android)、[官方 Android 部署文档](https://github.com/PaddlePaddle/PaddleOCR/blob/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/docs/version3.x/inference_deployment/cross_platform/android_deployment.en.md)。

## 第一轮实测结果

| 项目 | PP-OCRv6 tiny | PP-OCRv6 small | ML Kit（现有实现） |
|---|---:|---:|---:|
| 45 张总 OCR 耗时 | 35.838 秒 | 115.579 秒 | 31.609 秒 |
| 单张中位数 | 784 ms | 2551 ms | 685 ms |
| 单张 P95 | 952 ms | 2850 ms | 889 ms |
| 模型/客户端初始化 | 312 ms | 587 ms | 31 ms |
| 重点文字严格命中 | 11/11 | 11/11 | 8/11 |
| 页码正确 | 42/42 | 42/42 | 42/42 |
| 前三章连续阅读校验 | 通过 | 通过 | 通过 |
| 采样最大进程 PSS | 327.5 MiB | 438.0 MiB | 148.2 MiB |

本轮 tiny 与 small 修正了两个明确误识别：标题多出的“素”（“素秦轩”）和按钮“直擂签到”。另一个严格匹配差异是“别”被 ML Kit 识别为“別”，它属于繁简字形差异，不应与姓名错字同等解读为严重语义错误。两个 Paddle 模型都正确读取了标题，且不使用现有 ML Kit 的章节裁剪补丁。

失败按钮样本：tiny 返回“直播签到：全网最抽象主播”，中心坐标 (282, 941)；small 返回相同文字，中心 (281, 941)。与原页面定位 (282, 942) 一致，无需文字模糊匹配。

各轮热状态前后均为 0（无热限频报告）；这不等于没有发热，也不证明不会影响前台帧率。第一轮证据：`build/ocr-benchmark/20260917-202448/`。当前应用默认 OCR 保持 ML Kit，此结果是离线截图回放，没有把此前 110.146 秒任务成绩归到 PaddleOCR 上。

## 交换顺序复测与结论

第二轮先 ML Kit、后 tiny，各再识别同一批 45 张图片。ML Kit 31.343 秒、中位数 678 ms、P95 891 ms，重点文字 8/11、页码 42/42，连续阅读通过；tiny 36.541 秒、中位数 792 ms、P95 983 ms，重点文字 11/11、页码 42/42，连续阅读通过。证据：`build/ocr-benchmark/20260917-202819/`。

两轮 tiny 的结果一致，45 张总耗时比 ML Kit 多约 4.2–5.2 秒；tiny 比 small 更适合优先进入实际任务联调。small 在当前有限文字检查项上没有体现额外收益，耗时约为 tiny 的 3.2 倍，暂不优先。不能据此认定 small 在所有页面上都没有精度优势。

代价主要是内存：tiny 测试进程采样最大 PSS 为 327.5–330.5 MiB，ML Kit 为 141.7–148.2 MiB，small 为 438.0 MiB。因此不能仅凭识别正确就直接替换默认；后续实际 App 联调应检查用户主屏使用时的帧率、内存竞争及整任务耗时。本轮没有主屏并发性能验收，也没有用 PaddleOCR 重跑云模型参与的完整番茄任务。

交付范围：官方 SDK 测试模块、固定哈希模型下载、45 张样本准备、手机端 benchmark、证据采集及结果报告。当前普通助手 APK 默认仍使用 ML Kit，Paddle 模型和 native 依赖仅包含在测试 APK。38 项 JVM 单元测试、助手和 Paddle SDK 的 lint、release 构建通过；release 检查确认没有实验模型、ONNX/OpenCV 库或私有凭据。三种模型第一轮及两种模型复测共五次 instrumentation 均正常结束，准确率由记录内容判断，不能以测试进程退出成功代替识别准确性。
