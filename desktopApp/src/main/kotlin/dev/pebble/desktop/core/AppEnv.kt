package dev.pebble.desktop.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Where the coroutines run. Tests swap in test dispatchers. */
interface DispatcherProvider {
    /** The UI thread (Swing, via kotlinx-coroutines-swing). */
    val main: CoroutineDispatcher
    val default: CoroutineDispatcher
    val io: CoroutineDispatcher
}

object DefaultDispatchers : DispatcherProvider {
    override val main: CoroutineDispatcher get() = Dispatchers.Main
    override val default: CoroutineDispatcher get() = Dispatchers.Default
    override val io: CoroutineDispatcher get() = Dispatchers.IO
}

/**
 * Time and threads for the app, in one place, so tests can fix "now". [zone] is a function so a
 * change of the Windows time zone takes effect without a restart.
 */
class AppEnv(
    val clock: Clock,
    val zone: () -> ZoneId,
    val dispatchers: DispatcherProvider,
) {
    fun millis(): Long = clock.millis()

    fun localNow(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), zone())

    fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), zone())

    fun startOfToday(): Long = today().atStartOfDay(zone()).toInstant().toEpochMilli()

    fun toMillis(at: LocalDateTime): Long = at.atZone(zone()).toInstant().toEpochMilli()

    /** Local calendar day (epoch day) of a timestamp. */
    fun dayOf(millis: Long): Long = Instant.ofEpochMilli(millis).atZone(zone()).toLocalDate().toEpochDay()

    fun minuteOfDay(millis: Long): Int = Instant.ofEpochMilli(millis).atZone(zone()).toLocalTime().let { it.hour * 60 + it.minute }

    companion object {
        fun system(): AppEnv = AppEnv(Clock.systemUTC(), ZoneId::systemDefault, DefaultDispatchers)
    }
}
