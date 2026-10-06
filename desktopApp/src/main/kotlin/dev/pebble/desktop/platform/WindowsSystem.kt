package dev.pebble.desktop.platform

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.security.MessageDigest

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
                Advapi32Util.registrySetStringValue(HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME, "\"$path\" --background")
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

/**
 * Prevents two Pebbles (e.g. autostart + manual launch) from running at once. The first one also owns a named
 * Win32 event (no socket). A second launch sets it with [signalFirst], so the first one opens its window, and
 * then the second one exits.
 */
object SingleInstance {
    private var lock: FileLock? = null
    private var event: WinNT.HANDLE? = null
    private val isWindows = System.getProperty("os.name").startsWith("Windows")

    /** `WaitForSingleObject` result for "time ran out" (0x102, winerror.h WAIT_TIMEOUT). */
    private const val WAIT_TIMEOUT = 0x102

    /**
     * The name of the show event. `Local\` = this Windows session only. The data folder is part of the name,
     * so a dev build with another `APPDATA` does not signal the installed app.
     */
    internal fun eventName(dataDir: File): String {
        val key = runCatching { dataDir.canonicalPath }.getOrDefault(dataDir.path).lowercase()
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        return "Local\\PebbleShow-$hash"
    }

    /** True when this process now owns the data folder. It also creates the show event, before the app starts. */
    fun acquire(dataDir: File): Boolean {
        val channel = RandomAccessFile(File(dataDir, "pebble.lock"), "rw").channel
        lock = runCatching { channel.tryLock() }.getOrNull()
        if (lock == null) {
            channel.close()
            return false
        }
        if (isWindows) {
            // Auto-reset, not signalled. A signal that comes before listen() is kept until listen() reads it.
            event = runCatching { Kernel32.INSTANCE.CreateEvent(null, false, false, eventName(dataDir)) }.getOrNull()
        }
        return true
    }

    /** From a second launch: tells the first Pebble to open its window. False when there is no first one to tell. */
    fun signalFirst(dataDir: File): Boolean {
        if (!isWindows) return false
        return runCatching {
            val k = Kernel32.INSTANCE
            val h = k.OpenEvent(WinNT.EVENT_MODIFY_STATE, false, eventName(dataDir)) ?: return false
            try {
                k.SetEvent(h)
            } finally {
                k.CloseHandle(h)
            }
        }.getOrDefault(false)
    }

    /**
     * Starts the thread that calls [onShow] for each signal from [signalFirst]. [onShow] runs on that thread:
     * move to the UI thread there. Returns null when there is no event. Stop the result at exit.
     */
    fun listen(onShow: () -> Unit): ShowSignalWaiter? {
        val h = event ?: return null
        val waiter = ShowSignalWaiter(
            await = { ms ->
                when (Kernel32.INSTANCE.WaitForSingleObject(h, ms)) {
                    WinBase.WAIT_OBJECT_0 -> SignalWait.SIGNALED
                    WAIT_TIMEOUT -> SignalWait.TIMEOUT
                    else -> SignalWait.FAILED
                }
            },
            onShow = onShow,
            // The handle is closed only after the thread stopped waiting on it.
            onEnd = { Kernel32.INSTANCE.CloseHandle(h) },
        )
        waiter.start()
        return waiter
    }
}
