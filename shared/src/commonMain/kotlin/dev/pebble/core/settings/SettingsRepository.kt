package dev.pebble.core.settings

import dev.pebble.db.PebbleDatabase

/** Typed access to the key/value `setting` table. */
class SettingsRepository(private val db: PebbleDatabase) {
    private val q get() = db.pebbleQueries

    fun get(key: String): String? = q.selectSetting(key).executeAsOneOrNull()

    fun set(key: String, value: String) = q.upsertSetting(key, value)

    fun int(key: String, default: Int) = get(key)?.toIntOrNull() ?: default

    fun bool(key: String, default: Boolean) = get(key)?.toBooleanStrictOrNull() ?: default

    inline fun <reified E : Enum<E>> enum(key: String, default: E): E =
        get(key)?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default

    object Keys {
        const val PET_CHARACTER = "pet.character"
        const val PET_STAGE = "pet.stage"
        const val PET_ENABLED = "pet.enabled"
        const val WATER_GOAL_GLASSES = "water.goalGlasses"
        const val KEEP_ON_TOP = "widgets.keepOnTop"
        const val MEDIA_TRACKING = "memory.mediaTracking"
        const val APP_SCENE = "app.scene"

        /** Keep voice clips whose transcript you corrected (opt-in), to tune speech recognition to you. */
        const val KEEP_VOICE_CORRECTIONS = "voice.keepCorrections"
    }
}
