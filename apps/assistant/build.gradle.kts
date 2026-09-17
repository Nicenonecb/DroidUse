plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "dev.droiduse.assistant"
    compileSdk = 36
    defaultConfig { applicationId = "dev.droiduse.assistant"; minSdk = 35; targetSdk = 36; versionCode = 1; versionName = "0.1-dev"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":ipc"))
    implementation(project(":agent"))
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui:1.11.4")
    implementation("androidx.compose.foundation:foundation:1.11.4")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

val preparePrivateDefaults by tasks.registering {
    val target = layout.buildDirectory.dir("generated/privateDefaults")
    outputs.dir(target)
    outputs.upToDateWhen { false }
    doLast {
        val directory=target.get().asFile
        directory.mkdirs()
        val source=rootProject.file("secrets/model.json")
        if(source.exists()) source.copyTo(directory.resolve("builtin-model.json"),overwrite=true)
        else directory.resolve("builtin-model.json").delete()
        val executor=rootProject.file("platform/executor/prototype-virtual-display/build/host.jar")
        if(executor.exists()) executor.copyTo(directory.resolve("phone-executor.jar"),overwrite=true)
        else directory.resolve("phone-executor.jar").delete()
        val token=rootProject.file("secrets/bridge-token")
        if(token.exists()) token.copyTo(directory.resolve("bridge-token"),overwrite=true)
        else directory.resolve("bridge-token").delete()
    }
}
android.sourceSets.getByName("debug").assets.srcDir(layout.buildDirectory.dir("generated/privateDefaults").get().asFile)
tasks.matching { it.name == "mergeDebugAssets" || (it.name.contains("Debug") && it.name.contains("lint", ignoreCase = true)) }.configureEach { dependsOn(preparePrivateDefaults) }

android.sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/ocrFixtures").get().asFile)

dependencies { implementation(project(":paddleocr")) }
android.sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/paddleBenchmark").get().asFile)

// Ship only tiny; benchmark small remains test-only. Missing models fail the build.
val prepareTinyModels by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("generated/tinyModels/paddle/tiny"))
    from(rootProject.file("build/paddle-source/models/PP-OCRv6_tiny_det_onnx_infer")) { include("inference.onnx"); into("det") }
    from(rootProject.file("build/paddle-source/models/PP-OCRv6_tiny_rec_onnx_infer")) { include("inference.onnx", "inference.yml"); into("rec") }
    doFirst {
        listOf("det/inference.onnx", "rec/inference.onnx", "rec/inference.yml").forEach {
            val (kind, file) = it.split("/")
            check(rootProject.file("build/paddle-source/models/PP-OCRv6_tiny_${kind}_onnx_infer/$file").isFile) {
                "Missing tiny model: run python3 tools/ocr/fetch_models.py"
            }
        }
    }
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/tinyModels").get().asFile)
tasks.matching { (it.name.startsWith("merge") && it.name.endsWith("Assets")) || it.name.contains("lint", ignoreCase = true) }.configureEach { dependsOn(prepareTinyModels) }
