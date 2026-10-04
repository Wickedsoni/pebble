package dev.pebble.desktop.core

/** What the app asks of the UI. `Main` binds the real one once the tray and windows exist. */
interface UiPort {
    /** Shows a Windows toast. */
    fun notify(title: String, message: String)

    /** Opens the Pebble window on a page ("reminders", "notes", …). */
    fun openPage(page: String)

    /** Before the UI exists (and in tests that don't care): does nothing. */
    object None : UiPort {
        override fun notify(title: String, message: String) = Unit

        override fun openPage(page: String) = Unit
    }
}
