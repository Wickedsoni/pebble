package dev.pebble.core.settings

import kotlin.random.Random

/**
 * This device's id (WP E1): 128 random bits in base32 (26 letters and digits), made once at the first start and
 * kept in the setting `device.id`. New notes and reminders record it in `origin_device`; sync (milestone F) uses
 * it to tell devices apart. It says nothing about you or the computer.
 */
object DeviceIdentity {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

    /** The id, made and saved if there is none yet. */
    fun ensure(settings: SettingsRepository, random: Random = Random.Default): String =
        settings.get(SettingsRepository.Keys.DEVICE_ID) ?: newId(random).also { settings.set(SettingsRepository.Keys.DEVICE_ID, it) }

    /** 16 random bytes as lower-case base32 without padding. */
    fun newId(random: Random = Random.Default): String {
        val bytes = random.nextBytes(16)
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }
}
