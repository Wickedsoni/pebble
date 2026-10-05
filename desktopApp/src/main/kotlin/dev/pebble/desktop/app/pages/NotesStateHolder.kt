package dev.pebble.desktop.app.pages

import androidx.compose.runtime.Immutable
import dev.pebble.core.wellness.NoteRepository
import dev.pebble.desktop.core.AppEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Everything the Notes page shows, ready to draw. */
@Immutable
data class NotesUiState(
    /** "3 open". */
    val countLabel: String = "0 open",
    val notes: List<NoteRow> = emptyList(),
) {
    /** [uid]: for its History dialog; null for a note of an older Pebble that has no uid yet. */
    @Immutable
    data class NoteRow(val id: Long, val text: String, val timeLabel: String, val uid: String? = null)
}

/** What you can do on the Notes page. */
sealed interface NotesEvent {
    /** [text] is trimmed and not blank (the field sends only such text). */
    data class Add(val text: String) : NotesEvent

    /** Marks the note done: it is archived and leaves the page. */
    data class Complete(val id: Long) : NotesEvent
}

/**
 * State holder for the Notes page (docs/UI-PATTERN.md; WP E3c-3, the B7 roll-out for Notes). [state] updates by itself
 * when a note is added elsewhere (Quick Add, voice). [addNote] and [completeNote] are the app's own actions
 * (`PebbleApp.addNote`, `PebbleApp.completeNote`), so the bus gets the same events as before.
 *
 * Threads: runs on [scope]'s dispatcher (the UI thread in the app). Database writes go to `env.dispatchers.io`.
 */
class NotesStateHolder(
    notes: NoteRepository,
    private val addNote: (String) -> Long,
    private val completeNote: (Long) -> Unit,
    private val env: AppEnv,
    private val scope: CoroutineScope,
) {
    val state: StateFlow<NotesUiState> =
        notes.activeFlow(LIMIT, env.dispatchers.io).map { list ->
            NotesUiState("${list.size} open", list.map { NotesUiState.NoteRow(it.id, it.text, time(it.updatedAt), it.uid) })
        }.stateIn(scope, SharingStarted.Eagerly, NotesUiState())

    fun onEvent(e: NotesEvent) {
        scope.launch {
            withContext(env.dispatchers.io) {
                when (e) {
                    is NotesEvent.Add -> addNote(e.text)
                    is NotesEvent.Complete -> completeNote(e.id)
                }
            }
        }
    }

    private fun time(at: Long): String = Instant.ofEpochMilli(at).atZone(env.zone()).format(NOTE_DATE)

    private companion object {
        /** The page lists up to 200 open notes, as before. */
        const val LIMIT = 200L
        val NOTE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, h:mm a")
    }
}
