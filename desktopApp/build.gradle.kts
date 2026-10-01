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
