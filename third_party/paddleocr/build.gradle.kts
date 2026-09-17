plugins { id("com.android.library") }
android {
 namespace = "com.paddle.ocr"
 compileSdk = 36
 defaultConfig { minSdk = 35 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
 implementation("com.microsoft.onnxruntime:onnxruntime-android:1.21.1")
 implementation("org.opencv:opencv:4.12.0")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
 implementation("androidx.core:core-ktx:1.15.0")
}
