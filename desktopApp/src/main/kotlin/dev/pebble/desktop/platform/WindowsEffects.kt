package dev.pebble.desktop.platform

import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.Window

/**
 * Small DWM touches for our undecorated windows: Windows 11 rounded corners and light/dark
 * frame colours. Best-effort; silently does nothing elsewhere.
 *
 * Why no blur-behind: Compose's transparent windows are *layered* windows, and DWM's
 * blur/acrylic/sheet-of-glass don't composite with layered windows (they render black or
 * opaque). That was verified against a high-contrast test backdrop with every DWM mode, so
 * glass in Pebble is drawn in-app over a pre-blurred copy of the wallpaper instead.
 */
object WindowsEffects {
    private val isWindows = System.getProperty("os.name").startsWith("Windows")

    /** Radius DWM uses for rounded windows on Windows 11, in dp. */
    const val SYSTEM_CORNER_DP = 8

    fun roundCorners(window: Window) = dwm(window) { hwnd ->
        DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, IntByReference(DWMWCP_ROUND), 4)
    }

    fun setDarkMode(window: Window, dark: Boolean) = dwm(window) { hwnd ->
        DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, IntByReference(if (dark) 1 else 0), 4)
    }

    private fun dwm(window: Window, block: Dwmapi.(HWND) -> Unit) {
        if (!isWindows) return
        val hwnd = HWND(Native.getWindowPointer(window) ?: return)
        runCatching { Dwmapi.INSTANCE.block(hwnd) }
    }

    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
    private const val DWMWCP_ROUND = 2

    @Suppress("FunctionName")
    private interface Dwmapi : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: HWND, attribute: Int, value: IntByReference, size: Int): Int

        companion object {
            val INSTANCE: Dwmapi = Native.load("dwmapi", Dwmapi::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }
}
