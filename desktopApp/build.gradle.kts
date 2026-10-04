import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    alias(libs.plugins.kover)
}

kotlin {
    jvmToolchain(21)
    // The build has zero warnings; keep it that way.
    compilerOptions { allWarningsAsErrors.set(true) }
}

// On CI, print what the tests print (parity, router eval, SKIPPED lines), so the log proves the model tests ran.
tasks.test {
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = System.getenv("CI") != null
    }
}

dependencies {
    implementation(project(":shared"))
    // Coverage: `:desktopApp:koverHtmlReport` merges both modules. Report only; no threshold yet.
    kover(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    implementation(libs.onnxruntime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.djl.tokenizers)
    // Voice: VAD + Whisper offline. Ships its own onnxruntime.dll; SpeechRecognizer loads ours first (VoiceSpikeTest).
    implementation(libs.sherpa.onnx.jvm)
    implementation(libs.sherpa.onnx.native.win)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.register<JavaExec>("asrEval") {
    group = "pebble"
    description = "Speech recognition WER/CER on FLEURS Hindi (+ noise): -Pclips=30 -Pmodels=base,small [-Pdenoise=true]"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "dev.pebble.desktop.voice.AsrEvalKt"
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8")
    args(
        rootProject.layout.projectDirectory.dir("brain").asFile.absolutePath,
        providers.gradleProperty("clips").getOrElse("30"),
        providers.gradleProperty("models").getOrElse("base,small"),
        providers.gradleProperty("denoise").getOrElse("false"),
    )
}

tasks.register<JavaExec>("recordVoiceEval") {
    group = "pebble"
    description = "Record your own voice test set into brain/eval/voice_v1 (interactive)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.pebble.desktop.tools.RecordVoiceEvalKt"
    standardInput = System.`in`
    jvmArgs("-Dstdout.encoding=UTF-8")
    args(rootProject.layout.projectDirectory.dir("brain").asFile.absolutePath)
}

tasks.register<JavaExec>("modelPack") {
    group = "pebble"
    description = "Signed model packs: --args=\"keygen <key file>\" | \"sign …\" | \"check <pack.zip>\" (see ModelPackTool.kt)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.pebble.desktop.tools.ModelPackToolKt"
    workingDir = rootProject.projectDir // relative paths in --args are from the repo root
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

// Models ship inside the installer. brain/models/manifest.json lists every model ("intent", "asr") and its
// files, e.g. "intent-v2-pruned/tokenizer/tokenizer.json"; each is copied to models/<name>/ with the first
// folder dropped (→ models/intent/tokenizer/tokenizer.json). Model files are gitignored: on a fresh clone
// with no trained model, the app runs on rules only and voice says "voice model isn't installed".
val brainModels = rootProject.layout.projectDirectory.dir("brain/models")
val manifestModels: List<Pair<String, List<String>>> =
    providers.fileContents(brainModels.file("manifest.json")).asText.orNull?.let { json ->
        @Suppress("UNCHECKED_CAST")
        ((groovy.json.JsonSlurper().parseText(json) as Map<String, Any>)["models"] as List<Map<String, Any>>).map { m ->
            m["name"] as String to (m["files"] as Map<String, Map<String, Any>>).values.map { it["path"] as String }
        }
    } ?: emptyList()
// `-Pflavor=lite` leaves out the speech models (WP D2): a small installer; voice comes later as a signed pack.
val flavor = providers.gradleProperty("flavor").orNull ?: "full"
require(flavor == "full" || flavor == "lite") { "-Pflavor must be full or lite, not $flavor" }
val bundledModels = if (flavor == "lite") manifestModels.filter { it.first != "asr" } else manifestModels
val stageModel by tasks.registering(Sync::class) {
    group = "pebble"
    description = "Copies every model named in brain/models/manifest.json into the app resources (lite: no speech)"
    inputs.property("flavor", flavor)
    into(layout.buildDirectory.dir("model-resources/common/models"))
    from(brainModels.file("manifest.json"))
    for ((name, paths) in bundledModels) {
        for (path in paths) {
            from(brainModels.file(path)) { into("$name/" + path.substringAfter('/').substringBeforeLast('/', "")) }
        }
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
            packageVersion = "0.1.2"
            description = "Desktop pet and glass widgets"
            // jdk.crypto.ec: Ed25519 for signed model packs on JDK 21 (in java.base only from JDK 22).
            modules("java.sql", "jdk.unsupported", "jdk.crypto.ec")
            windows {
                menuGroup = "Pebble"
                perUserInstall = true
                upgradeUuid = "6f1c2a0e-4b8d-4f7e-9c35-2b7a8e5d1f40"
            }
        }
    }
}
