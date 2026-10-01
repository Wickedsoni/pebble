package dev.pebble.desktop.platform

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

/** Start-with-Windows via the per-user `Run` registry key. Only available in the installed build. */
object Autostart {
    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val VALUE_NAME = "Pebble"

    /** Set by the jpackage launcher; null when running from Gradle/IDE. */
    private val launcherPath: String? = System.getProperty("jpackage.app-path")

    val isSupported: Boolean get() = launcherPath != null

    fun isEnabled(): Boolean = runCatching {
        Advapi32Util.registryValueExists(HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)
    }.getOrDefault(false)

    fun setEnabled(enabled: Boolean) {
        val path = launcherPath ?: return
        runCatching {
            if (enabled) {
                Advapi32Util.registrySetStringValue(HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME, "\"$path\"")
            } else if (isEnabled()) {
                Advapi32Util.registryDeleteValue(HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)
            }
        }
    }
}

/** Reads the Windows "app mode" (light/dark) setting. */
object SystemTheme {
    fun isDark(): Boolean = runCatching {
        Advapi32Util.registryGetIntValue(
            HKEY_CURRENT_USER,
            "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            "AppsUseLightTheme",
        ) == 0
    }.getOrDefault(false)
}

/** Prevents two Pebbles (e.g. autostart + manual launch) from running at once. */
object SingleInstance {
    private var lock: FileLock? = null

    fun acquire(dataDir: File): Boolean {
        val channel = RandomAccessFile(File(dataDir, "pebble.lock"), "rw").channel
        lock = runCatching { channel.tryLock() }.getOrNull()
        if (lock == null) channel.close()
        return lock != null
    }
}
