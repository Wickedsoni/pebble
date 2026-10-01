package dev.pebble.desktop

import dev.pebble.desktop.platform.MediaWatcher.Companion.match
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaWatcherTest {
    @Test
    fun recognisesVideoTitles() {
        assertEquals("YouTube" to "Lofi beats to study to", match("(3) Lofi beats to study to - YouTube - Google Chrome"))
        assertEquals("YouTube" to "Interstellar trailer", match("Interstellar trailer - YouTube — Mozilla Firefox"))
        assertEquals("Prime Video" to "The Boys", match("Prime Video: The Boys - Brave"))
        assertEquals("VLC" to "Inception", match("Inception.mkv - VLC media player"))
    }

    @Test
    fun ignoresEverythingElse() {
        assertNull(match("YouTube - Google Chrome"))
        assertNull(match("windowswidgets – Main.kt"))
        assertNull(match("Inbox (3) - Gmail - Google Chrome"))
    }
}
