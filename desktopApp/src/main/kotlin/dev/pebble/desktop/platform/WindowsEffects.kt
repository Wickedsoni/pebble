package dev.pebble.desktop.platform

import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.Window

/**
 * Win32 tricks that make undecorated Compose windows look like native Windows 11 widgets:
 * DWM rounded corners plus the system acrylic backdrop. Every call is best-effort; on other
 * OSes or older Windows builds it silently does nothing. Set `PEBBLE_NO_EFFECTS=1` to disable.
 *
 * Note: `SetWindowRgn` clipping and the undocumented `SetWindowCompositionAttribute` acrylic
 * were both tried and break or square-off Compose's transparent windows, so we stick to DWM.
 */
object WindowsEffects {
    private val isWindows = System.getProperty("os.name").startsWith("Windows")
    private val disabled = System.getenv("PEBBLE_NO_EFFECTS") != null

    /** Radius DWM uses for rounded windows (Windows 11), in dp. Content should match it when blurred. */
    const val SYSTEM_CORNER_DP = 8

    /** Returns true when the acrylic backdrop was applied, so callers can pick a more transparent tint. */
    fun applyGlass(window: Window): Boolean {
        if (!isWindows || disabled) return false
        val hwnd = HWND(Native.getWindowPointer(window) ?: return false)
        val dwm = runCatching { Dwmapi.INSTANCE }.getOrNull() ?: return false

        dwm.DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, IntByReference(DWMWCP_ROUND), 4)
        val extended = dwm.DwmExtendFrameIntoClientArea(hwnd, Margins(-1)) == S_OK
        return extended &&
            dwm.DwmSetWindowAttribute(hwnd, DWMWA_SYSTEMBACKDROP_TYPE, IntByReference(DWMSBT_TRANSIENTWINDOW), 4) == S_OK
    }

    /** Tints the acrylic backdrop dark or light; call again whenever the system theme flips. */
    fun setDarkMode(window: Window, dark: Boolean) {
        if (!isWindows || disabled) return
        val hwnd = HWND(Native.getWindowPointer(window) ?: return)
        runCatching {
            Dwmapi.INSTANCE.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, IntByReference(if (dark) 1 else 0), 4)
        }
    }

    private const val S_OK = 0
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
    private const val DWMWCP_ROUND = 2
    private const val DWMWA_SYSTEMBACKDROP_TYPE = 38 // Windows 11 22H2+
    private const val DWMSBT_TRANSIENTWINDOW = 3 // acrylic

    @Suppress("FunctionName")
    private interface Dwmapi : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: HWND, attribute: Int, value: IntByReference, size: Int): Int
        fun DwmExtendFrameIntoClientArea(hwnd: HWND, margins: Margins): Int

        companion object {
            val INSTANCE: Dwmapi = Native.load("dwmapi", Dwmapi::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }

    @Structure.FieldOrder("left", "right", "top", "bottom")
    class Margins(all: Int = 0) : Structure() {
        @JvmField var left = all
        @JvmField var right = all
        @JvmField var top = all
        @JvmField var bottom = all
    }
}
