plugins { id("com.android.library") }

android {
    namespace = "dev.droiduse.system"
    compileSdk = 36
    defaultConfig { minSdk = 35 }
    buildFeatures { aidl = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies { testImplementation("junit:junit:4.13.2") }
