package dev.pebble.desktop.app.pages

import androidx.compose.runtime.Immutable
import dev.pebble.core.settings.SettingsRepository.Keys
import dev.pebble.desktop.PebbleApp
import dev.pebble.desktop.core.AppEnv
import dev.pebble.desktop.core.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the "Privacy" card reads and writes. Each call may block (database, files, a child process): call it off the UI thread. */
interface PrivacyPort {
    fun flag(key: String, default: Boolean): Boolean

    fun setFlag(key: String, on: Boolean)

    fun voiceClipCount(): Int

    fun deleteVoiceClips()

    /** Forgets what Pebble noticed you watch, and lets the habit brain learn again without it. */
    suspend fun clearWatchHistory()

    /** Stops the chat helper program, if it runs. */
    fun closeChat()

    /** Checks the chat pack on disk. This can hash big files. */
    suspend fun chatInstalled(): Boolean
}

/** The real settings, memory and chat of [app]. */
class AppPrivacyPort(private val app: PebbleApp) : PrivacyPort {
    override fun flag(key: String, default: Boolean) = app.settings.bool(key, default)

    override fun setFlag(key: String, on: Boolean) {
        app.settings.set(key, on.toString())
    }

    override fun voiceClipCount() = app.voiceSamples.count().toInt()

    override fun deleteVoiceClips() = app.clearVoiceSamples()

    override suspend fun clearWatchHistory() {
        app.memory.clearMedia()
        app.learn()
    }

    override fun closeChat() = app.chat.close()

    override suspend fun chatInstalled(): Boolean {
        app.chatInstalled.refresh()
        return app.chatInstalled.value
    }
}

/** The "Privacy" card of the Memory page, ready to draw. */
@Immutable
data class PrivacyUiState(
    /** False until the settings are read: the card shows no switches before that. */
    val loaded: Boolean = false,
    val mediaOn: Boolean = false,
    val micOn: Boolean = true,
    val smartOn: Boolean = false,
    val keepVoice: Boolean = false,
    val voiceClips: Int = 0,
    val chatInstalled: Boolean = false,
)

/** What you can do on the "Privacy" card. */
sealed interface PrivacyEvent {
    data class SetMedia(val on: Boolean) : PrivacyEvent

    data class SetMic(val on: Boolean) : PrivacyEvent

    data class SetSmartReplies(val on: Boolean) : PrivacyEvent

    data class SetKeepVoice(val on: Boolean) : PrivacyEvent

    data object ClearWatchHistory : PrivacyEvent

    data object DeleteVoiceClips : PrivacyEvent
}

/**
 * State holder for the "Privacy" card of the Memory page (docs/UI-PATTERN.md). A switch changes the state at once and
 * is saved on `env.dispatchers.io`. Turning "Smart replies" off also stops the chat helper program.
 *
 * Threads: runs on the dispatcher of [scope]. Every [PrivacyPort] call goes to `env.dispatchers.io`.
 */
class PrivacyStateHolder(
    private val port: PrivacyPort,
    private val env: AppEnv,
    private val scope: CoroutineScope,
    private val log: Logger = Logger.None,
) {
    private val _state = MutableStateFlow(PrivacyUiState())
    val state: StateFlow<PrivacyUiState> = _state.asStateFlow()

    /** One thread for the writes, so two quick toggles are saved in the order you made them. */
    private val writer = env.dispatchers.io.limitedParallelism(1)

    init {
        scope.launch {
            // A read that fails gives the default, so the switches always appear.
            val flags = withContext(env.dispatchers.io) {
                PrivacyUiState(
                    loaded = true,
                    mediaOn = read { port.flag(Keys.MEDIA_TRACKING, false) } ?: false,
                    micOn = read { port.flag(Keys.MICROPHONE_ENABLED, true) } ?: true,
                    smartOn = read { port.flag(Keys.SMART_REPLIES, false) } ?: false,
                    keepVoice = read { port.flag(Keys.KEEP_VOICE_CORRECTIONS, false) } ?: false,
                    voiceClips = read { port.voiceClipCount() } ?: 0,
                )
            }
            _state.update { flags.copy(chatInstalled = it.chatInstalled) }
        }
        // Checking the chat pack can hash a big file: it must not hold back the switches.
        scope.launch {
            val installed = withContext(env.dispatchers.io) { read { port.chatInstalled() } } ?: false
            _state.update { it.copy(chatInstalled = installed) }
        }
    }

    fun onEvent(e: PrivacyEvent) {
        when (e) {
            is PrivacyEvent.SetMedia -> set(Keys.MEDIA_TRACKING, e.on, { mediaOn }) { copy(mediaOn = it) }

            is PrivacyEvent.SetMic -> set(Keys.MICROPHONE_ENABLED, e.on, { micOn }) { copy(micOn = it) }

            is PrivacyEvent.SetKeepVoice -> set(Keys.KEEP_VOICE_CORRECTIONS, e.on, { keepVoice }) { copy(keepVoice = it) }

            is PrivacyEvent.SetSmartReplies -> set(Keys.SMART_REPLIES, e.on, { smartOn }, { if (!e.on) port.closeChat() }) {
                copy(smartOn = it)
            }

            PrivacyEvent.ClearWatchHistory -> scope.launch { write { port.clearWatchHistory() } }

            PrivacyEvent.DeleteVoiceClips -> scope.launch {
                if (write { port.deleteVoiceClips() }) _state.update { it.copy(voiceClips = 0) }
            }
        }
    }

    /**
     * The switch changes at once. If the write fails, it goes back to what it was. [finally] runs after the write,
     * also when the write failed (turning Smart replies off must stop the helper program in any case).
     */
    private fun set(
        key: String,
        on: Boolean,
        current: PrivacyUiState.() -> Boolean,
        finally: () -> Unit = {},
        change: PrivacyUiState.(Boolean) -> PrivacyUiState,
    ) {
        val before = _state.value.current()
        _state.update { it.change(on) }
        scope.launch {
            val saved = write { port.setFlag(key, on) }
            if (!saved) _state.update { it.change(before) }
            write(finally)
        }
    }

    private inline fun <T> read(work: () -> T): T? = try {
        work()
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Privacy", "privacy card read failed", e)
        null
    }

    /** Runs [work] on the writer thread. A failure is logged and gives false: a click must not crash the app. */
    private suspend fun write(work: suspend () -> Unit): Boolean = withContext(writer) {
        try {
            work()
            true
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Privacy", "privacy card action failed", e)
            false
        }
    }
}
