package dev.pebble.desktop

import dev.pebble.desktop.core.FileLogger
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileLoggerTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val at = LocalDateTime.of(2026, 10, 4, 9, 30, 15).atZone(zone).toInstant().toEpochMilli()
    private val dir = Files.createTempDirectory("pebble-log")
    private val file = dir.resolve("pebble.log")

    @Test
    fun oneLinePerEntryWithTheCause() {
        val log = FileLogger(file, { at }, { zone })
        log.info("app", "started")
        log.warn("brain", "learning failed", IllegalStateException("db locked"))
        assertEquals(
            listOf(
                "2026-10-04T09:30:15  INFO  app  started",
                "2026-10-04T09:30:15  WARN  brain  learning failed  java.lang.IllegalStateException: db locked",
            ),
            Files.readAllLines(file).filterNot { it.startsWith("\t") }, // the stack trace lines are tested below
        )
    }

    @Test
    fun aWarningKeepsTheStackTraceAndTheRootCause() {
        val log = FileLogger(file, { at }, { zone })
        fun deep(n: Int): Nothing = if (n == 0) throw IllegalStateException("db locked") else deep(n - 1)
        val e = runCatching { deep(40) }.exceptionOrNull()!!
        log.warn("brain", "wrapped", RuntimeException("outer", e))
        val lines = Files.readAllLines(file)
        assertTrue(lines.first().endsWith("java.lang.RuntimeException: outer"))
        assertTrue(lines.any { it.startsWith("\tat dev.pebble.desktop.FileLoggerTest") }, "the stack is there")
        assertTrue(lines.any { it.startsWith("Caused by: java.lang.IllegalStateException: db locked") }, "the cause is not cut off")
        assertTrue(lines.size <= 40, "a long trace stays short: ${lines.size}")
    }

    @Test
    fun rotatesPastTheLimitAndKeepsOneOldFile() {
        val log = FileLogger(file, { at }, { zone }, maxBytes = 200)
        repeat(20) { log.info("app", "line $it with some padding to grow the file") }
        val old = dir.resolve("pebble.log.1")
        assertTrue(Files.exists(old))
        assertTrue(Files.size(file) <= 200 + 80, "the live file starts over")
        assertTrue(Files.readAllLines(file).last().endsWith("line 19 with some padding to grow the file"))
    }

    @Test
    fun aBrokenLogNeverThrows() {
        val blocker = Files.createFile(dir.resolve("not-a-dir"))
        FileLogger(blocker.resolve("pebble.log"), { at }, { zone }).warn("app", "nowhere to write")
    }
}
