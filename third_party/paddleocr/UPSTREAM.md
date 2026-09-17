# PaddleOCR Android SDK

Source: https://github.com/PaddlePaddle/PaddleOCR/tree/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/deploy/ppocr-android/ppocr-sdk

Commit: `dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf`. Apache-2.0; upstream source headers and LICENSE retained.
Runtime sources are copied unchanged. Gradle adapted to this project; models are downloaded separately into ignored build assets. Used only for opt-in OCR benchmark instrumentation until device results justify promotion.

Dependency adaptation: use official `org.opencv:opencv:4.12.0` instead of QuickBird 4.5.3. The upstream demo's old native binary fails to load on the test Pixel 6 (Android 16) with missing `__sfp_handle_exceptions`. ONNX Runtime remains upstream 1.21.1.
