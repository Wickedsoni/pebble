package dev.pebble.desktop.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Diagnostics for things that fail quietly (no dialog), so a bug report can include them. */
interface Logger {
    fun info(tag: String, msg: String)

    fun warn(tag: String, msg: String, t: Throwable? = null)

    /** Drops everything (tests). */
    object None : Logger {
        override fun info(tag: String, msg: String) = Unit

        override fun warn(tag: String, msg: String, t: Throwable?) = Unit
    }
}

/**
 * One line per entry in [file] (`%APPDATA%\Pebble\pebble.log` in the app), like `brain.log`. Past [maxBytes]
 * the file moves to `pebble.log.1` (replacing the older one), so the log never grows without limit.
 * Writing never throws: a broken log must not break the app.
 */
class FileLogger(
    private val file: Path,
    private val clock: () -> Long,
    private val zone: () -> ZoneId,
    private val maxBytes: Long = 1_000_000,
) : Logger {
    override fun info(tag: String, msg: String) = write("INFO", tag, msg, null)

    override fun warn(tag: String, msg: String, t: Throwable?) = write("WARN", tag, msg, t)

    @Synchronized
    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        runCatching {
            Files.createDirectories(file.parent)
            if (Files.exists(file) && Files.size(file) > maxBytes) {
                Files.move(file, file.resolveSibling("${file.fileName}.1"), StandardCopyOption.REPLACE_EXISTING)
            }
            val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(clock()), zone()).withNano(0)
            val cause = t?.let { "  ${it::class.java.name}: ${it.message}" } ?: ""
            Files.writeString(
                file,
                "$at  $level  $tag  $msg$cause\n${t?.let(::trace).orEmpty()}",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }

    /**
     * The stack trace after its first line (already in the entry): the first [TRACE_LINES] lines, and every
     * "Caused by" line after them with its first [CAUSE_LINES] frames, so the root cause and where it
     * happened are never cut off.
     */
    private fun trace(t: Throwable): String {
        val kept = StringBuilder()
        var sinceCause = Int.MAX_VALUE
        t.stackTraceToString().lines().drop(1).filter { it.isNotBlank() }.forEachIndexed { i, line ->
            sinceCause = if (line.startsWith("Caused by")) 0 else sinceCause.coerceAtMost(Int.MAX_VALUE - 1) + 1
            if (i < TRACE_LINES - 1 || sinceCause <= CAUSE_LINES) kept.append(line).append('\n')
        }
        return kept.toString()
    }

    private companion object {
        const val TRACE_LINES = 15
        const val CAUSE_LINES = 3
    }
}
