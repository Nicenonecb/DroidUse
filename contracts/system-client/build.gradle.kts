plugins { id("com.android.library") }

// APK classes must not collide with the hidden framework classes. Keep the wire
// layout and Binder descriptors generated from the single canonical ROM contract.
val generateClientContract by tasks.registering {
    val source = project(":system-api").file("src/main")
    val target = layout.buildDirectory.dir("generated/client")
    inputs.dir(source)
    outputs.dir(target)
    doLast {
        val root = target.get().asFile
        root.deleteRecursively()
        listOf("aidl", "java").forEach { kind ->
            source.resolve("$kind/dev/droiduse/system").listFiles().orEmpty().forEach { file ->
                var content = file.readText().replace("dev.droiduse.system", "dev.droiduse.systemclient")
                if (file.extension == "aidl" && file.name.startsWith("IDroidUse")) {
                    content = content.replace("/** @hide */", "@Descriptor(value=\"dev.droiduse.system.${file.nameWithoutExtension}\")")
                }
                root.resolve("$kind/dev/droiduse/systemclient/${file.name}").apply {
                    parentFile.mkdirs(); writeText(content)
                }
            }
        }
    }
}
extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "dev.droiduse.systemclient"
    compileSdk = 36
    defaultConfig { minSdk = 35 }
    buildFeatures { aidl = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets.getByName("main") {
        aidl.srcDir(layout.buildDirectory.dir("generated/client/aidl").get().asFile)
        java.srcDir(layout.buildDirectory.dir("generated/client/java").get().asFile)
    }
}
tasks.named("preBuild") { dependsOn(generateClientContract) }
