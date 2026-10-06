package dev.pebble.desktop.app.pages

import androidx.compose.runtime.Immutable
import dev.pebble.core.brain.ConversationRepository
import dev.pebble.core.brain.Turn
import dev.pebble.desktop.core.AppEnv
import dev.pebble.desktop.core.Logger
import dev.pebble.desktop.ui.ChatRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException

/** Where the conversation comes from. [recent] is subscribed one time for each state holder. */
interface ConversationPort {
    /** The newest [limit] turns, oldest first, again after each change. */
    fun recent(limit: Long): Flow<List<Turn>>

    suspend fun clear()
}

/** The real conversation. The query runs on the IO dispatcher of [env]. */
class RepositoryConversationPort(private val repo: ConversationRepository, private val env: AppEnv) : ConversationPort {
    override fun recent(limit: Long): Flow<List<Turn>> = repo.recentFlow(limit, env.dispatchers.io)

    override suspend fun clear() = withContext(env.dispatchers.io) { repo.clear() }
}

/** The Chat page and the conversation of Quick Add, ready to draw. */
@Immutable
data class ChatUiState(
    /** False until the first read is done: "No conversation yet" must not flash before it. */
    val loaded: Boolean = false,
    val rows: List<ChatRow> = emptyList(),
    /** "12 messages". */
    val countLabel: String = "",
    /** Changes when a new last turn arrives, also when the list is at its limit and keeps its size. Null: no turns. */
    val scrollKey: String? = null,
    /** True after "Clear conversation": the page asks before it deletes. */
    val confirmClear: Boolean = false,
)

/** What you can do on the Chat page. */
sealed interface ChatEvent {
    data object AskClear : ChatEvent

    data object CancelClear : ChatEvent

    data object ConfirmClear : ChatEvent
}

/**
 * State holder for the Chat page (docs/UI-PATTERN.md) and for the conversation in Quick Add. It shows the newest
 * [limit] turns. The time labels use the zone of [env]. "Clear conversation" asks first, then deletes.
 *
 * Threads: runs on the dispatcher of [scope]. The port does the database work off the UI thread.
 */
class ChatStateHolder(
    private val port: ConversationPort,
    private val env: AppEnv,
    private val scope: CoroutineScope,
    limit: Long = 500,
    private val log: Logger = Logger.None,
) {
    private val confirmClear = MutableStateFlow(false)

    val state: StateFlow<ChatUiState> =
        combine(port.recent(limit), confirmClear) { turns, confirm -> build(turns, confirm) }
            .stateIn(scope, SharingStarted.Eagerly, ChatUiState())

    fun onEvent(e: ChatEvent) {
        when (e) {
            ChatEvent.AskClear -> confirmClear.value = true

            ChatEvent.CancelClear -> confirmClear.value = false

            ChatEvent.ConfirmClear -> {
                // Close the question first: a second click must not start a second clear.
                confirmClear.value = false
                scope.launch {
                    try {
                        port.clear()
                    } catch (c: CancellationException) {
                        throw c
                    } catch (x: Exception) {
                        // A failed clear must not cancel the scope of the page.
                        log.warn("Chat", "clearing the conversation failed", x)
                    }
                }
            }
        }
    }

    private fun build(turns: List<Turn>, confirm: Boolean) = ChatUiState(
        loaded = true,
        rows = turns.map(::row),
        countLabel = if (turns.size == 1) "1 message" else "${turns.size} messages",
        scrollKey = turns.lastOrNull()?.let { "${it.atMillis}:${it.said}" },
        confirmClear = confirm && turns.isNotEmpty(),
    )

    private fun row(t: Turn) = ChatRow(
        timeLabel = Instant.ofEpochMilli(t.atMillis).atZone(env.zone()).format(TIME),
        said = t.said,
        voice = t.via == "voice",
        reply = t.reply,
        extra = t.did.takeIf { it != t.reply },
    )

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, h:mm a")
    }
}
