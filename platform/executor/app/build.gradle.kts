plugins { id("com.android.application") }
android {
    namespace = "dev.droiduse.executor.app"
    compileSdk = 36
    defaultConfig { applicationId = "dev.droiduse.executor"; minSdk = 35; targetSdk = 36; versionCode = 1; versionName = "0.1-dev"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":ipc"))
    implementation(project(":runtime"))
    implementation(project(":system-client"))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
