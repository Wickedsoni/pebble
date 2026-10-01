package dev.pebble.desktop.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import dev.pebble.desktop.pet.Character
import dev.pebble.desktop.pet.Mood
import dev.pebble.desktop.pet.PetFrame
import dev.pebble.desktop.pet.PetPainter.drawPet
import dev.pebble.desktop.pet.PetPose
import dev.pebble.desktop.pet.Stage
import dev.pebble.desktop.pet.defaultPose
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Dev tool: renders the whole cast offscreen to a PNG contact sheet so art changes can be
 * reviewed without launching the app. Run with `./gradlew :desktopApp:petGallery`.
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/pet-gallery.png")
    val cell = 150
    val rows = buildList {
        add(Character.entries.map { Triple(it, Stage.ADULT.takeIf { false } ?: Stage.TEEN, PetPose()) })
        add(Stage.entries.map { Triple(Character.PEBBLE, it, PetPose()) })
        add(Mood.entries.take(5).map { Triple(Character.PEBBLE, Stage.TEEN, it.defaultPose()) })
        add(Mood.entries.drop(5).map { Triple(Character.PEBBLE, Stage.TEEN, it.defaultPose()) })
        add(listOf(Mood.LOVE, Mood.HAPPY, Mood.THIRSTY, Mood.NEEDS_INPUT, Mood.WORKING).zip(Character.entries.drop(1) + Character.PEBBLE)
            .map { (m, c) -> Triple(c, Stage.TEEN, m.defaultPose()) })
    }
    val width = cell * 5 + 40
    val height = cell * rows.size + 40
    ImageComposeScene(width, height) {
        Column(Modifier.fillMaxSize().background(Color(0xFFF1F2FB)).padding(20.dp)) {
            rows.forEach { row ->
                Row {
                    row.forEach { (c, stage, pose) ->
                        Canvas(Modifier.size(cell.dp)) {
                            drawPet(c, stage, pose, PetFrame(time = 0.35f, lookX = 0.3f, lookY = 0.2f))
                        }
                    }
                }
            }
        }
    }.use { scene ->
        val png = scene.render().encodeToData(EncodedImageFormat.PNG) ?: error("encode failed")
        out.parentFile?.mkdirs()
        out.writeBytes(png.bytes)
        println("Wrote ${out.absolutePath}")
    }
}
