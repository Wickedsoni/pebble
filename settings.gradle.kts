rootProject.name = "pebble"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        // sherpa-onnx (speech: VAD, denoise, Whisper) publishes its JVM API and native libs here only.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.k2-fsa.sherpa-onnx") }
        }
    }
}

include(":shared")
include(":desktopApp")
