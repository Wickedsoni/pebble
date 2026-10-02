import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    implementation(libs.onnxruntime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.djl.tokenizers)
    testImplementation(kotlin("test"))
}

tasks.register<JavaExec>("petGallery") {
    group = "pebble"
    description = "Renders every character and mood to build/pet-gallery.png"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.pebble.desktop.tools.PetGalleryKt"
    args(layout.buildDirectory.file("pet-gallery.png").get().asFile.absolutePath)
}

tasks.register<JavaExec>("sceneGallery") {
    group = "pebble"
    description = "Renders every app background scene to build/scene-gallery.png"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.pebble.desktop.tools.SceneGalleryKt"
    args(layout.buildDirectory.file("scene-gallery.png").get().asFile.absolutePath)
}

// The command model ships inside the installer. brain/models/manifest.json names the model folder
// (e.g. "intent-v0-pruned/intent.int8.onnx"); only the three runtime files are copied (~35 MB).
// Model files are gitignored: on a fresh clone with no trained model, the app runs on rules only.
val brainModels = rootProject.layout.projectDirectory.dir("brain/models")
val shippedModel = providers.fileContents(brainModels.file("manifest.json")).asText.map { json ->
    Regex(""""path"\s*:\s*"([^"/]+)/intent\.int8\.onnx"""").find(json)?.groupValues?.get(1)
        ?: error("brain/models/manifest.json has no intent.int8.onnx path")
}
val stageModel by tasks.registering(Sync::class) {
    group = "pebble"
    description = "Copies the model named in brain/models/manifest.json into the app resources"
    into(layout.buildDirectory.dir("model-resources/common/models"))
    from(brainModels.file("manifest.json"))
    from(brainModels.dir(shippedModel)) {
        include("intent.int8.onnx", "labels.json", "tokenizer/tokenizer.json")
        into("intent")
    }
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(stageModel) }
// Compose's packaging tasks don't track the *contents* of app resources, so a new model alone would
// leave them "up-to-date" with the old one inside. Make the staged model a real input.
tasks.matching { it.name.startsWith("createDistributable") || it.name.startsWith("package") }
    .configureEach { inputs.files(stageModel).withPropertyName("bundledModel") }

compose.desktop {
    application {
        mainClass = "dev.pebble.desktop.MainKt"

        // Lean runtime for an always-on background app: one GC thread, a small heap that gets
        // returned to the OS when idle, and no JIT warm-up storms. Software rendering keeps the
        // GPU asleep and saves ~220 MB of per-window graphics memory; our windows are small and
        // mostly static, so CPU cost is the same or lower than DirectX.
        jvmArgs(
            "-Dskiko.renderApi=SOFTWARE",
            "-XX:+UseSerialGC",
            "-Xms32m",
            "-Xmx256m",
            "-XX:MaxHeapFreeRatio=30",
            "-XX:MinHeapFreeRatio=10",
            "-XX:TieredStopAtLevel=1",
            "-XX:CICompilerCount=1",
            "-XX:+UseStringDeduplication",
        )

        nativeDistributions {
            appResourcesRootDir.set(layout.buildDirectory.dir("model-resources"))
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Pebble"
            packageVersion = "0.1.0"
            description = "Desktop pet and glass widgets"
            modules("java.sql", "jdk.unsupported")
            windows {
                menuGroup = "Pebble"
                perUserInstall = true
                upgradeUuid = "6f1c2a0e-4b8d-4f7e-9c35-2b7a8e5d1f40"
            }
        }
    }
}
