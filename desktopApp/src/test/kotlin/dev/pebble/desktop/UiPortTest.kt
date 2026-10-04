package dev.pebble.desktop

import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.quickadd.QuickCommand
import dev.pebble.desktop.core.AppEnv
import dev.pebble.desktop.core.Logger
import dev.pebble.desktop.core.UiPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The UI is bound once by `Main`; before that, toasts and page changes are dropped, not crashes. */
class UiPortTest {
    private val warnings = mutableListOf<String>()
    private val log = object : Logger {
        override fun info(tag: String, msg: String) = Unit

        override fun warn(tag: String, msg: String, t: Throwable?) {
            warnings += msg
        }
    }
    private val app = PebbleApp(DatabaseFactory.inMemory(), AppEnv.system(), log)

    private class RecordingUi : UiPort {
        val calls = mutableListOf<String>()

        override fun notify(title: String, message: String) {
            calls += "notify $title: $message"
        }

        override fun openPage(page: String) {
            calls += "open $page"
        }
    }

    @Test
    fun beforeBindingNothingHappens() {
        app.ui.notify("Pebble", "water")
        assertEquals("", app.execute(QuickCommand.OpenPage("notes")).text)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun objectsMadeBeforeTheBindStillReachTheUi() {
        val ui = RecordingUi()
        app.bindUi(ui) // after the executor was built
        app.execute(QuickCommand.OpenPage("reminders"))
        app.ui.notify("Pebble", "Stretch")
        assertEquals(listOf("open reminders", "notify Pebble: Stretch"), ui.calls)
    }

    @Test
    fun aSecondBindIsLoggedAndIgnored() {
        val first = RecordingUi()
        val second = RecordingUi()
        app.bindUi(first)
        app.bindUi(second)
        app.ui.openPage("notes")
        assertEquals(listOf("open notes"), first.calls)
        assertTrue(second.calls.isEmpty())
        assertEquals(1, warnings.size)
    }
}
