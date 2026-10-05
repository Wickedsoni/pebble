package dev.pebble.desktop.brain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Path

/** Where a [LazyModel] is in its life. [toString] is the line written to `brain.log` and shown in test failures. */
sealed interface ModelStatus {
    data object NotLoaded : ModelStatus {
        override fun toString() = "not loaded"
    }

    data object NotFound : ModelStatus {
        override fun toString() = "no model found"
    }

    data object Loading : ModelStatus {
        override fun toString() = "loading"
    }

    data class Ready(val loadMillis: Long, val detail: String) : ModelStatus {
        override fun toString() = "ready ($detail$loadMillis ms load)"
    }

    data object ChecksumMismatch : ModelStatus {
        override fun toString() = "checksum mismatch — not loaded"
    }

    data class LoadFailed(val message: String?) : ModelStatus {
        override fun toString() = "load failed: $message"
    }

    data object Unloaded : ModelStatus {
        override fun toString() = "unloaded (idle)"
    }
}

/**
 * One on-demand model: find → verify → load in the background → free after [idleMillis] without use.
 * Shared by the command model and the speech models, so both follow the same rules:
 *  - [warmUp] starts loading and returns at once; calling it again while loading does nothing
 *  - [getOrNull] never blocks: null until loaded
 *  - [await] waits for a load that is running or starting (voice: the talk key went down a moment ago)
 * If anything is missing or wrong, the model stays null and [status] says why.
 */
class LazyModel<T : AutoCloseable>(
    private val name: String,
    private val scope: CoroutineScope,
    private val idleMillis: Long,
    private val locate: () -> Path?,
    private val verify: (Path) -> Boolean,
    private val load: (Path) -> T,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Verify and load run here: file hashing and native loading must not block the caller. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val idleCheckMillis: Long = 60_000,
    /** Extra words for the ready status, e.g. which speech models loaded. */
    private val describe: (T) -> String = { "" },
    /** Called after each load attempt (`brain.log`). */
    private val onLoadAttempt: (Path, ModelStatus) -> Unit = { _, _ -> },
) {
    @Volatile private var model: T? = null

    @Volatile private var lastUse = 0L
    private var loading: Job? = null

    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.NotLoaded)
    val status: StateFlow<ModelStatus> = _status.asStateFlow()

    /** The model folder, found once. */
    val dir: Path? by lazy { locate() }

    /** Start loading if needed. Cheap to call repeatedly. */
    @Synchronized
    fun warmUp() {
        lastUse = clock()
        if (model != null || loading?.isActive == true) return
        val dir = dir ?: run { _status.value = ModelStatus.NotFound; return }
        loading = scope.launch(io) {
            _status.value = ModelStatus.Loading
            if (!verify(dir)) {
                _status.value = ModelStatus.ChecksumMismatch
                onLoadAttempt(dir, _status.value)
                return@launch
            }
            val t0 = clock()
            runCatching { load(dir) }
                .onSuccess { m ->
                    model = m
                    _status.value = ModelStatus.Ready(clock() - t0, describe(m))
                    watchIdle()
                }
                .onFailure { _status.value = ModelStatus.LoadFailed(it.message) }
            onLoadAttempt(dir, _status.value)
        }
    }

    /** The loaded model, or null (never waits). Counts as a use, so it isn't freed while you're using it. */
    fun getOrNull(): T? = model?.also { lastUse = clock() }

    /** Waits for a running load to end (tests, and voice right after [warmUp]); true if the model is loaded. */
    suspend fun join(): Boolean {
        val job = synchronized(this) { loading }
        job?.join()
        return model != null
    }

    /** Starts loading if needed and waits for it. */
    suspend fun await(): T? {
        warmUp()
        join()
        return getOrNull()
    }

    private fun watchIdle() = scope.launch {
        while (isActive && model != null) {
            delay(idleCheckMillis)
            if (clock() - lastUse > idleMillis) unload()
        }
    }

    @Synchronized
    private fun unload() {
        val m = model ?: return
        model = null
        runCatching { m.close() }
        _status.value = ModelStatus.Unloaded
    }

    override fun toString() = "$name: ${status.value}"
}
