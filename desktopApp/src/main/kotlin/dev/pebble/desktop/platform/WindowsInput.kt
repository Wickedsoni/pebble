package dev.pebble.desktop.platform

import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

private val isWindows = System.getProperty("os.name").startsWith("Windows")

/**
 * System-wide hotkey via Win32 `RegisterHotKey`. Runs its own message loop thread and calls
 * [onPress] on the Swing thread. Pressing it also lets us take keyboard focus legitimately.
 */
class GlobalHotkey(private val modifiers: Int, private val virtualKey: Int, private val onPress: () -> Unit) {
    @Volatile var registered = false
        private set

    fun start() {
        if (!isWindows) return
        thread(isDaemon = true, name = "pebble-hotkey") {
            registered = User32.INSTANCE.RegisterHotKey(null, HOTKEY_ID, modifiers or MOD_NOREPEAT, virtualKey)
            if (!registered) return@thread
            val msg = WinUser.MSG()
            while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
                if (msg.message == WinUser.WM_HOTKEY) SwingUtilities.invokeLater(onPress)
            }
        }
    }

    companion object {
        /** True while [virtualKey] is physically held down (hold-to-talk: RegisterHotKey only reports the press). */
        fun isHeld(virtualKey: Int): Boolean =
            isWindows && runCatching { (User32.INSTANCE.GetAsyncKeyState(virtualKey).toInt() and 0x8000) != 0 }.getOrDefault(false)

        private const val HOTKEY_ID = 0x5EB1
        const val MOD_ALT = 0x0001
        const val MOD_CONTROL = 0x0002
        const val MOD_SHIFT = 0x0004
        private const val MOD_NOREPEAT = 0x4000
        const val VK_SPACE = 0x20
    }
}

/** Whether we're unplugged, and whether Windows battery saver is on — the pet slows down accordingly. */
object Power {
    data class State(val onBattery: Boolean, val saver: Boolean)

    fun state(): State {
        if (!isWindows) return State(onBattery = false, saver = false)
        return runCatching {
            val s = SystemPowerStatus()
            Kernel32Ext.INSTANCE.GetSystemPowerStatus(s)
            State(onBattery = s.ACLineStatus.toInt() == 0, saver = s.SystemStatusFlag.toInt() == 1)
        }.getOrDefault(State(onBattery = false, saver = false))
    }

    @Suppress("PropertyName")
    @com.sun.jna.Structure.FieldOrder(
        "ACLineStatus",
        "BatteryFlag",
        "BatteryLifePercent",
        "SystemStatusFlag",
        "BatteryLifeTime",
        "BatteryFullLifeTime",
    )
    class SystemPowerStatus : com.sun.jna.Structure() {
        @JvmField var ACLineStatus: Byte = 0

        @JvmField var BatteryFlag: Byte = 0

        @JvmField var BatteryLifePercent: Byte = 0

        @JvmField var SystemStatusFlag: Byte = 0

        @JvmField var BatteryLifeTime: Int = 0

        @JvmField var BatteryFullLifeTime: Int = 0
    }

    @Suppress("FunctionName")
    private interface Kernel32Ext : StdCallLibrary {
        fun GetSystemPowerStatus(status: SystemPowerStatus): Boolean

        companion object {
            val INSTANCE: Kernel32Ext = Native.load("kernel32", Kernel32Ext::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }
}

object UserActivity {
    /** Milliseconds since the last keyboard/mouse input anywhere on the system. */
    fun idleMillis(): Long {
        if (!isWindows) return 0
        return runCatching {
            val info = WinUser.LASTINPUTINFO()
            User32.INSTANCE.GetLastInputInfo(info)
            (Kernel32.INSTANCE.GetTickCount().toLong() - info.dwTime.toLong()).coerceAtLeast(0)
        }.getOrDefault(0)
    }

    /** True while a fullscreen game/video, presentation or "busy" app is in front — the pet hides. */
    fun isFullscreenBusy(): Boolean {
        if (!isWindows) return false
        return runCatching {
            val state = IntByReference()
            Shell32Ext.INSTANCE.SHQueryUserNotificationState(state)
            state.value in setOf(QUNS_BUSY, QUNS_RUNNING_D3D_FULL_SCREEN, QUNS_PRESENTATION_MODE)
        }.getOrDefault(false)
    }

    fun cursor(): Point? = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()

    /** Primary-screen area not covered by the taskbar, in the same (dp-like) units as window positions. */
    fun workArea(): Rectangle = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds

    private const val QUNS_BUSY = 2
    private const val QUNS_RUNNING_D3D_FULL_SCREEN = 3
    private const val QUNS_PRESENTATION_MODE = 4

    @Suppress("FunctionName")
    private interface Shell32Ext : StdCallLibrary {
        fun SHQueryUserNotificationState(state: IntByReference): Int

        companion object {
            val INSTANCE: Shell32Ext = Native.load("shell32", Shell32Ext::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }
}
