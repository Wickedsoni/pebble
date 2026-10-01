package dev.pebble.desktop.tools

import dev.pebble.desktop.app.Scene
import dev.pebble.desktop.app.drawScene
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.io.File

/** Dev tool: renders every background scene (dark and light) to a PNG contact sheet. `./gradlew :desktopApp:sceneGallery` */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/scene-gallery.png")
    val scenes = Scene.entries.filter { it != Scene.AUTO && it != Scene.WALLPAPER }
    val w = 530
    val h = 350
    val sheet = Surface.makeRasterN32Premul(w * scenes.size, h * 2)
    for ((col, scene) in scenes.withIndex()) {
        for ((row, dark) in listOf(true, false).withIndex()) {
            val tile = Surface.makeRasterN32Premul(w * 2, h * 2) // render at window-like size, then shrink
            drawScene(tile.canvas, scene, w * 2, h * 2, dark)
            sheet.canvas.drawImageRect(
                tile.makeImageSnapshot(), Rect.makeWH(w * 2f, h * 2f),
                Rect.makeXYWH(col * w.toFloat(), row * h.toFloat(), w.toFloat(), h.toFloat()), SamplingMode.LINEAR, null, true,
            )
        }
    }
    out.parentFile?.mkdirs()
    out.writeBytes(sheet.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
    println("Wrote ${out.absolutePath}")
}
