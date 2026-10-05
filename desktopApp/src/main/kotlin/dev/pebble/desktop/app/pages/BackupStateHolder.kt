package dev.pebble.desktop.app.pages

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.file.Path

/** What the "Backup" card needs from the app (WP E4); every call runs off the UI thread and may throw. */
interface BackupPort {
    /** Writes an encrypted backup to [file]; returns what it holds ("12 notes, …"). */
    suspend fun export(file: Path, passphrase: CharArray): String

    /** Checks [file] with [passphrase] and stages it for the next start; returns what it holds. */
    suspend fun stageRestore(file: Path, passphrase: CharArray): String

    suspend fun cancelRestore()

    suspend fun restorePending(): Boolean
}

/** The "Backup" card on the About page, ready to draw. */
@Immutable
data class BackupUiState(
    /** The result of the last action; null: the hint. */
    val message: String? = null,
    /** The message is an error (draw it in the warning colour). */
    val error: Boolean = false,
    val busy: Boolean = false,
    /** A restore is staged for the next start. */
    val restorePending: Boolean = false,
)

sealed interface BackupEvent {
    /** [repeat] must be the same as [passphrase]. */
    data class Export(val file: Path, val passphrase: CharArray, val repeat: CharArray) : BackupEvent

    data class Restore(val file: Path, val passphrase: CharArray) : BackupEvent

    data object CancelRestore : BackupEvent
}

/**
 * State holder for the "Backup" card (docs/UI-PATTERN.md). It wipes each passphrase array after use.
 * There is no way to get a forgotten passphrase back, and the card says so.
 */
class BackupStateHolder(private val port: BackupPort, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    init {
        scope.launch { _state.update { it.copy(restorePending = port.restorePending()) } }
    }

    fun onEvent(e: BackupEvent) {
        when (e) {
            is BackupEvent.Export -> {
                val problem = when {
                    e.passphrase.size < MIN_LENGTH -> "Use a passphrase of at least $MIN_LENGTH characters."
                    !e.passphrase.contentEquals(e.repeat) -> "The two passphrases are not the same."
                    else -> null
                }
                e.repeat.fill('\u0000')
                if (problem != null) {
                    e.passphrase.fill('\u0000')
                    _state.update { it.copy(message = problem, error = true) }
                    return
                }
                run(e.passphrase) {
                    val holds = port.export(e.file, e.passphrase)
                    "Backup saved to ${e.file.fileName}: $holds. Keep the passphrase safe: without it, nobody can open the file."
                }
            }

            is BackupEvent.Restore -> run(e.passphrase) {
                val holds = port.stageRestore(e.file, e.passphrase)
                "Backup checked: $holds. Pebble restores it the next time it starts; your data now is kept as pebble.db.before-restore."
            }

            BackupEvent.CancelRestore -> scope.launch {
                port.cancelRestore()
                _state.update { it.copy(message = "Restore cancelled.", error = false, restorePending = false) }
            }
        }
    }

    private fun run(passphrase: CharArray, work: suspend () -> String) {
        _state.update { it.copy(busy = true, message = "Working… (the passphrase check takes a second)", error = false) }
        scope.launch {
            val result = runCatching { work() }
            passphrase.fill('\u0000')
            val pending = runCatching { port.restorePending() }.getOrDefault(false)
            _state.update {
                it.copy(
                    busy = false,
                    message = result.getOrElse { e -> e.message ?: "That did not work." },
                    error = result.isFailure,
                    restorePending = pending,
                )
            }
        }
    }

    companion object {
        const val MIN_LENGTH = 8
    }
}
