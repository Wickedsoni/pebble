package dev.pebble.desktop

import dev.pebble.core.backup.BackupException
import dev.pebble.desktop.app.pages.BackupEvent
import dev.pebble.desktop.app.pages.BackupPort
import dev.pebble.desktop.app.pages.BackupStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** WP E4: the "Backup" card checks the passphrase, reports the result and wipes the passphrase. */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupStateHolderTest {
    private class FakePort : BackupPort {
        var pending = false
        var fail: String? = null
        val passphrasesSeen = mutableListOf<String>()

        override suspend fun export(file: Path, passphrase: CharArray): String {
            passphrasesSeen += String(passphrase)
            fail?.let { throw BackupException(it) }
            return "2 notes, 0 reminders, 1 calendar events"
        }

        override suspend fun stageRestore(file: Path, passphrase: CharArray): String {
            passphrasesSeen += String(passphrase)
            fail?.let { throw BackupException(it) }
            pending = true
            return "5 notes, 1 reminders, 0 calendar events"
        }

        override suspend fun cancelRestore() {
            pending = false
        }

        override suspend fun restorePending() = pending
    }

    private val port = FakePort()
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun stop() = scopes.forEach { it.cancel() }

    private fun TestScope.holder() =
        BackupStateHolder(port, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { scopes += it })

    private val file = Path.of("backup.pebblebackup")

    @Test
    fun aShortOrMismatchedPassphraseIsRefusedBeforeAnyWork() = runTest {
        val h = holder()
        h.onEvent(BackupEvent.Export(file, "short".toCharArray(), "short".toCharArray()))
        assertTrue(h.state.value.error)
        assertTrue(h.state.value.message!!.contains("at least 8"))
        h.onEvent(BackupEvent.Export(file, "long enough 1".toCharArray(), "long enough 2".toCharArray()))
        assertEquals("The two passphrases are not the same.", h.state.value.message)
        advanceUntilIdle()
        assertTrue(port.passphrasesSeen.isEmpty())
    }

    @Test
    fun anExportSaysWhatItHoldsAndWipesThePassphrase() = runTest {
        val h = holder()
        val pass = "long enough".toCharArray()
        h.onEvent(BackupEvent.Export(file, pass, "long enough".toCharArray()))
        assertTrue(h.state.value.busy)
        advanceUntilIdle()
        assertFalse(h.state.value.busy)
        assertFalse(h.state.value.error)
        assertTrue(h.state.value.message!!.startsWith("Backup saved to backup.pebblebackup: 2 notes"))
        assertEquals(listOf("long enough"), port.passphrasesSeen)
        assertTrue(pass.all { it == '\u0000' }, "wiped after use")
    }

    @Test
    fun aFailedRestoreShowsWhyAndNothingIsPending() = runTest {
        val h = holder()
        port.fail = "Wrong passphrase, or the backup file is damaged."
        h.onEvent(BackupEvent.Restore(file, "whatever1".toCharArray()))
        advanceUntilIdle()
        assertTrue(h.state.value.error)
        assertEquals("Wrong passphrase, or the backup file is damaged.", h.state.value.message)
        assertFalse(h.state.value.restorePending)
    }

    @Test
    fun aStagedRestoreCanBeCancelled() = runTest {
        val h = holder()
        h.onEvent(BackupEvent.Restore(file, "whatever1".toCharArray()))
        advanceUntilIdle()
        assertTrue(h.state.value.restorePending)
        assertTrue(h.state.value.message!!.contains("next time it starts"))
        h.onEvent(BackupEvent.CancelRestore)
        advanceUntilIdle()
        assertFalse(h.state.value.restorePending)
        assertEquals("Restore cancelled.", h.state.value.message)
    }

    @Test
    fun aRestoreStagedEarlierIsShownAtOnce() = runTest {
        port.pending = true
        val h = holder()
        advanceUntilIdle()
        assertTrue(h.state.value.restorePending)
    }
}
