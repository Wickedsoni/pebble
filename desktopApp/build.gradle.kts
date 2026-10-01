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
}

compose.desktop {
    application {
        mainClass = "dev.pebble.desktop.MainKt"

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
