package dev.pebble.desktop.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * Push-to-talk session for Quick Add: [start] opens the mic (and starts loading Whisper in parallel),
 * [stop] closes it and transcribes. The transcript lands in the text field, editable, and runs through
 * the same router as typing — voice is just another way to type.
 */
class VoiceInput(
    private val recognizer: Transcriber,
    private val scope: CoroutineScope,
    /** Makes the microphone; [onLevel] is called from the capture thread. Tests give a fake. */
    private val micFactory: (onLevel: (Float) -> Unit) -> Microphone = { MicCapture(it) },
    /** The Privacy switch: when false, the microphone is never opened and speech models never load. */
    private val micAllowed: () -> Boolean = { true },
) {
    /** The part of [SpeechRecognizer] that [VoiceInput] needs. */
    interface Transcriber {
        /** The folder of the speech model, or null when none is installed. */
        val modelDir: Path?

        fun warmUp()

        suspend fun transcribe(samples: FloatArray): Transcript?
    }

    /** The part of [MicCapture] that [VoiceInput] needs. */
    interface Microphone {
        fun start(): Boolean

        /** Closes the mic and returns the clip. */
        fun stop(): FloatArray
    }

    sealed interface State {
        data object Idle : State
        data class Listening(val level: Float) : State
        data object Transcribing : State
        data class Heard(val transcript: Transcript) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    // Written on the UI thread, read on the capture thread (level callback).
    @Volatile private var mic: Microphone? = null

    /** The transcription of the last [stop]; [cancel] cancels it. */
    private var transcribeJob: Job? = null

    /** Counts sessions; a result of an older session is dropped. */
    private val session = AtomicInteger()

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
        transcribeJob?.cancel()
        val mine = session.incrementAndGet()
        lateinit var m: Microphone
        // A level only replaces Listening (atomically), so a late callback cannot overwrite Transcribing.
        m = micFactory { level ->
            if (mic === m && session.get() == mine) _state.update { if (it is State.Listening) State.Listening(level) else it }
        }
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
        val mine = session.incrementAndGet()
        transcribeJob?.cancel()
        transcribeJob = scope.launch {
            val t = try {
                recognizer.transcribe(audio)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (session.get() != mine) return@launch // cancelled or replaced while transcribing: drop the result
            lastHeard = t
            _state.value = when {
                t != null -> State.Heard(t)
                recognizer.modelDir == null -> State.Failed("Voice model isn't installed")
                else -> State.Failed("Didn't catch that — try again")
            }
        }
    }

    /** Closes the mic and drops a running transcription (Quick Add closed mid-sentence or while "Transcribing"). */
    fun cancel() {
        session.incrementAndGet()
        transcribeJob?.cancel()
        transcribeJob = null
        val m = mic
        mic = null
        m?.stop()
        _state.value = State.Idle
    }

    fun reset() {
        if (mic == null) _state.value = State.Idle
    }
}
