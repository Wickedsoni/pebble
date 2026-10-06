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
    fun chatInstalled(): Boolean
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

    override fun chatInstalled(): Boolean {
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

    init {
        scope.launch {
            val first = io {
                PrivacyUiState(
                    loaded = true,
                    mediaOn = port.flag(Keys.MEDIA_TRACKING, false),
                    micOn = port.flag(Keys.MICROPHONE_ENABLED, true),
                    smartOn = port.flag(Keys.SMART_REPLIES, false),
                    keepVoice = port.flag(Keys.KEEP_VOICE_CORRECTIONS, false),
                    voiceClips = port.voiceClipCount(),
                    chatInstalled = port.chatInstalled(),
                )
            }
            if (first != null) _state.value = first
        }
    }

    fun onEvent(e: PrivacyEvent) {
        when (e) {
            is PrivacyEvent.SetMedia -> set(Keys.MEDIA_TRACKING, e.on) { copy(mediaOn = e.on) }

            is PrivacyEvent.SetMic -> set(Keys.MICROPHONE_ENABLED, e.on) { copy(micOn = e.on) }

            is PrivacyEvent.SetKeepVoice -> set(Keys.KEEP_VOICE_CORRECTIONS, e.on) { copy(keepVoice = e.on) }

            is PrivacyEvent.SetSmartReplies -> set(Keys.SMART_REPLIES, e.on, { if (!e.on) port.closeChat() }) { copy(smartOn = e.on) }

            PrivacyEvent.ClearWatchHistory -> scope.launch { io { port.clearWatchHistory() } }

            PrivacyEvent.DeleteVoiceClips -> scope.launch {
                io { port.deleteVoiceClips() }
                _state.update { it.copy(voiceClips = 0) }
            }
        }
    }

    private fun set(key: String, on: Boolean, after: () -> Unit = {}, change: PrivacyUiState.() -> PrivacyUiState) {
        _state.update { it.change() }
        scope.launch {
            io {
                port.setFlag(key, on)
                after()
            }
        }
    }

    /** Runs [work] on the IO dispatcher. A failure is logged and gives null: a click must not crash the app. */
    private suspend fun <T> io(work: suspend () -> T): T? = withContext(env.dispatchers.io) {
        try {
            work()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Privacy", "privacy card action failed", e)
            null
        }
    }
}
