package dev.pebble.desktop.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Push-to-talk session for Quick Add: [start] opens the mic (and starts loading Whisper in parallel),
 * [stop] closes it and transcribes. The transcript lands in the text field, editable, and runs through
 * the same router as typing — voice is just another way to type.
 */
class VoiceInput(
    private val recognizer: SpeechRecognizer,
    private val scope: CoroutineScope,
    /** The Privacy switch: when false, the microphone is never opened and speech models never load. */
    private val micAllowed: () -> Boolean = { true },
) {
    sealed interface State {
        data object Idle : State
        data class Listening(val level: Float) : State
        data object Transcribing : State
        data class Heard(val transcript: Transcript) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var mic: MicCapture? = null

    /** The last clip and what was heard, kept in memory only until the next one (for [lastHeard] corrections). */
    var lastAudio: FloatArray? = null
        internal set
    var lastHeard: Transcript? = null
        internal set

    val isListening: Boolean get() = mic != null

    val isAllowed: Boolean get() = micAllowed()

    fun start(): Boolean {
        if (mic != null) return true
        if (!micAllowed()) {
            _state.value = State.Failed("Microphone is off — turn it on in Memory → Privacy")
            return false
        }
        recognizer.warmUp()
        val m = MicCapture { level -> if (mic != null) _state.value = State.Listening(level) }
        if (!m.start()) {
            _state.value = State.Failed("No microphone found")
            return false
        }
        mic = m
        _state.value = State.Listening(0f)
        return true
    }

    fun stop() {
        val m = mic ?: return
        mic = null
        val audio = m.stop()
        lastAudio = audio
        lastHeard = null
        _state.value = State.Transcribing
        scope.launch {
            val t = runCatching { recognizer.transcribe(audio) }.getOrNull()
            lastHeard = t
            _state.value = when {
                t != null -> State.Heard(t)
                recognizer.modelDir == null -> State.Failed("Voice model isn't installed")
                else -> State.Failed("Didn't catch that — try again")
            }
        }
    }

    /** Closes the mic without transcribing (Quick Add closed mid-sentence). */
    fun cancel() {
        mic?.stop()
        mic = null
        _state.value = State.Idle
    }

    fun reset() {
        if (mic == null) _state.value = State.Idle
    }
}
