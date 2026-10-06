package dev.pebble.desktop.platform

import com.sun.jna.platform.win32.User32
import dev.pebble.core.settings.SettingsRepository.Keys
import dev.pebble.desktop.PebbleApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Opt-in (Memory page → "Notice what I watch"): once a minute, reads the foreground window's
 * title and, if it's a known video app or site, stores "app + title" locally. Nothing else is
 * read, and nothing leaves the computer. Off by default; costs one Win32 call per minute.
 */
class MediaWatcher(private val app: PebbleApp) {
    private var lastTitle: String? = null

    suspend fun run() {
        while (true) {
            if (app.settings.bool(Keys.MEDIA_TRACKING, false)) {
                withContext(app.env.dispatchers.io) { foregroundTitle() }?.let(::match)?.let { (source, title) ->
                    if (title != lastTitle) {
                        lastTitle = title
                        app.memory.logMedia(source, title, app.now())
                    }
                }
            }
            delay(60_000)
        }
    }

    private fun foregroundTitle(): String? = runCatching {
        val hwnd = User32.INSTANCE.GetForegroundWindow() ?: return null
        val buf = CharArray(512)
        val n = User32.INSTANCE.GetWindowText(hwnd, buf, buf.size)
        String(buf, 0, n).takeIf { it.isNotBlank() }
    }.getOrNull()

    companion object {
        private val browserSuffix = Regex("""\s+[-—]\s+(Google Chrome|Brave|Microsoft\W*Edge|Mozilla Firefox|Opera|Vivaldi|Arc)$""")

        /** Maps a window title to (source, title), or null when it isn't something being watched. */
        fun match(raw: String): Pair<String, String>? {
            val t = raw.replace(browserSuffix, "").trim()
            fun clean(s: String) = s.replace(Regex("""^\(\d+\)\s*"""), "").trim().takeIf { it.length > 1 }
            return when {
                t.endsWith(" - YouTube") -> clean(t.removeSuffix(" - YouTube"))?.let { "YouTube" to it }

                t.startsWith("Prime Video: ") -> clean(t.removePrefix("Prime Video: "))?.let { "Prime Video" to it }

                t.endsWith(" | Netflix") || t.endsWith(" - Netflix") -> clean(t.substringBeforeLast(" ").substringBeforeLast(" "))?.let {
                    "Netflix" to
                        it
                }

                t.contains("Hotstar") && t.contains(" - ") -> clean(t.substringBefore(" - "))?.let { "Hotstar" to it }

                t.contains("JioCinema") && t.contains(" - ") -> clean(t.substringBefore(" - "))?.let { "JioCinema" to it }

                t.endsWith(" - VLC media player") -> clean(t.removeSuffix(" - VLC media player").substringBeforeLast('.'))?.let {
                    "VLC" to
                        it
                }

                t.endsWith(" - Media Player") -> clean(t.removeSuffix(" - Media Player"))?.let { "Media Player" to it }

                else -> null
            }
        }
    }
}
