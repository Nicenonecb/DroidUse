plugins { id("com.android.library") }
android {
    namespace = "dev.droiduse.executor.protocol"
    compileSdk = 36
    defaultConfig { minSdk = 35 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
