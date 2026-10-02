package dev.pebble.desktop

import dev.pebble.desktop.brain.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The installed app has no `brain/` folder next to it: the model must come from the installer's
 * resources (`<install>/app/resources/models/intent`). Run `:desktopApp:createDistributable` first.
 */
class BundledModelTest {
    private val root: Path = Path.of(System.getProperty("user.dir"))
    private val resources = root.resolve("build/compose/binaries/main/app/Pebble/app/resources")

    @Test
    fun installedLayoutLoadsTheBundledModel() {
        if (!Files.exists(resources.resolve("models/intent/intent.int8.onnx"))) { println("SKIPPED: no distributable"); return }
        if (Files.exists(dev.pebble.core.db.DatabaseFactory.defaultDataDir().toPath().resolve("models/intent"))) {
            println("SKIPPED: a model in %APPDATA% would win"); return
        }
        val oldDir = System.getProperty("user.dir")
        try {
            System.setProperty("compose.application.resources.dir", resources.toString())
            System.setProperty("user.dir", Files.createTempDirectory("pebble-installed").toString()) // no brain/ here
            val mm = ModelManager(CoroutineScope(SupervisorJob() + Dispatchers.Default))
            assertEquals(resources.resolve("models/intent"), mm.modelDir)
            mm.warmUp()
            assertTrue(runBlocking { mm.awaitLoaded() }, "bundled model failed to load: ${mm.status}")
            val u = assertNotNull(mm.understand("kal subah 7 baje utha dena"))
            assertEquals("alarm_set", u.top.intent)
        } finally {
            System.setProperty("user.dir", oldDir)
            System.clearProperty("compose.application.resources.dir")
        }
    }
}
