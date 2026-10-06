package dev.pebble.desktop.brain

import kotlinx.coroutines.CancellationException
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
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantReadWriteLock

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
 *  - [warmUp] starts loading and returns at once; calling it again while loading does nothing. It does no
 *    file work on the caller's thread: finding the folder, hashing and loading all run on [io]
 *  - [withModel] and [awaitUse] lend the model to a block of work; the model is not freed while a block runs
 *  - [getOrNull] never blocks: null until loaded (the result can be freed at once, see [withModel])
 *  - [await] waits for a load that is running or starting (voice: the talk key went down a moment ago)
 *  - a load that failed (checksum, native error) is not tried again until a model file changes or
 *    [retryBackoffMillis] pass: a debounced keystroke must not hash 300 MB again each time
 * If anything is missing or wrong, the model stays null and [status] says why. [status] never stays "loading".
 */
class LazyModel<T : AutoCloseable>(
    private val name: String,
    private val scope: CoroutineScope,
    private val idleMillis: Long,
    private val locate: () -> Path?,
    private val verify: (Path) -> Boolean,
    private val load: (Path) -> T,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Locate, verify and load run here: file hashing and native loading must not block the caller. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val idleCheckMillis: Long = 60_000,
    /** Extra words for the ready status, e.g. which speech models loaded. */
    private val describe: (T) -> String = { "" },
    /** Called after each load attempt (`brain.log`). */
    private val onLoadAttempt: (Path, ModelStatus) -> Unit = { _, _ -> },
    /** Names the state of the model files (sizes and times); a failed load is tried again when it changes. */
    private val fingerprint: (Path) -> String = ::filesFingerprint,
    /** A failed load is tried again after this long even if no file changed. */
    private val retryBackoffMillis: Long = 5 * 60_000L,
) {
    /** A load that failed, with the state of the files it failed on. */
    private class Failure(val fingerprint: String, val at: Long)

    @Volatile private var model: T? = null

    @Volatile private var lastUse = 0L
    private var loading: Job? = null

    @Volatile private var failure: Failure? = null

    /** Readers: code that works with the model ([withModel]). Writer: [unload], only when nobody reads. */
    private val borrowers = ReentrantReadWriteLock()

    private val locateLock = Any()

    @Volatile private var located = false

    @Volatile private var locatedDir: Path? = null

    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.NotLoaded)
    val status: StateFlow<ModelStatus> = _status.asStateFlow()

    /**
     * The model folder, found once. The first call does the file checks that `locate` needs, so call it off the UI
     * thread. [warmUp] never calls it on the caller's thread; after a load attempt the value is known and this
     * returns at once.
     */
    val dir: Path?
        get() {
            if (!located) {
                synchronized(locateLock) {
                    if (!located) {
                        locatedDir = locate()
                        located = true
                    }
                }
            }
            return locatedDir
        }

    /** Start loading if needed. Cheap to call repeatedly, and it touches no file on the caller's thread. */
    @Synchronized
    fun warmUp() {
        lastUse = clock()
        if (model != null || loading?.isActive == true) return
        loading = scope.launch(io) {
            try {
                loadOnce()
            } finally {
                // A cancelled scope (shutdown) or an unexpected error must not leave "loading" behind.
                if (_status.value == ModelStatus.Loading) _status.value = ModelStatus.NotLoaded
            }
        }
    }

    private fun loadOnce() {
        val dir = dir ?: run { _status.value = ModelStatus.NotFound; return }
        val files = runCatching { fingerprint(dir) }.getOrDefault("")
        failure?.let { f -> if (f.fingerprint == files && clock() - f.at < retryBackoffMillis) return }
        _status.value = ModelStatus.Loading
        val verified = try {
            if (verify(dir)) null else ModelStatus.ChecksumMismatch
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ModelStatus.LoadFailed(e.message ?: e::class.simpleName) // a malformed manifest, a file that vanished
        }
        if (verified != null) return fail(dir, verified, files)
        val t0 = clock()
        // describe runs inside the guard: if it throws, the model is freed and no half-ready state is left.
        runCatching {
            val m = load(dir)
            try {
                m to describe(m)
            } catch (t: Throwable) {
                runCatching { m.close() }
                throw t
            }
        }
            .onSuccess { (m, detail) ->
                if (!scope.isActive) {
                    runCatching { m.close() } // shut down while loading: nobody would free it
                    return
                }
                failure = null
                model = m
                _status.value = ModelStatus.Ready(clock() - t0, detail)
                watchIdle()
                onLoadAttempt(dir, _status.value)
            }
            .onFailure { fail(dir, ModelStatus.LoadFailed(it.message), files) }
    }

    private fun fail(dir: Path, status: ModelStatus, files: String) {
        failure = Failure(files, clock())
        _status.value = status
        onLoadAttempt(dir, status)
    }

    /**
     * The loaded model, or null (never waits). Counts as a use, but the model can be freed right after, so do not
     * keep working with the result: use [withModel], which holds the model open.
     */
    fun getOrNull(): T? = model?.also { lastUse = clock() }

    /**
     * Runs [block] with the loaded model and returns its result; null if the model is not loaded. The model is not
     * freed while [block] runs (an idle unload waits for the next check). Do not keep the model after [block] ends.
     */
    fun <R> withModel(block: (T) -> R): R? {
        val read = borrowers.readLock()
        read.lock()
        try {
            val m = model ?: return null
            lastUse = clock()
            return block(m)
        } finally {
            lastUse = clock()
            read.unlock()
        }
    }

    /** Starts loading if needed, waits for it, then runs [block] like [withModel]. Null: no model. */
    suspend fun <R> awaitUse(block: (T) -> R): R? {
        class Used(val value: R)
        repeat(2) {
            warmUp()
            join()
            withModel { Used(block(it)) }?.let { return it.value }
            if (status.value != ModelStatus.Unloaded) return null // freed between the load and the use: try once more
        }
        return null
    }

    /** Waits for a running load to end (tests, and voice right after [warmUp]); true if the model is loaded. */
    suspend fun join(): Boolean {
        val job = synchronized(this) { loading }
        job?.join()
        return model != null
    }

    /** Starts loading if needed and waits for it. To work with the result, use [awaitUse]. */
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

    private fun unload() {
        val write = borrowers.writeLock()
        if (!write.tryLock()) return // a block works with the model: the next idle check tries again
        try {
            if (clock() - lastUse <= idleMillis) return
            val m = model ?: return
            model = null
            runCatching { m.close() }
            _status.value = ModelStatus.Unloaded
        } finally {
            write.unlock()
        }
    }

    override fun toString() = "$name: ${status.value}"

    companion object {
        /** Size and last-modified time of every file in [dir] (and of `manifest.json` next to it), as one string. A changed file changes it. */
        fun filesFingerprint(dir: Path): String {
            val files = Files.walk(dir).use { s ->
                s.filter { Files.isRegularFile(it) }.sorted().map {
                    "${dir.relativize(it)}:${Files.size(it)}:${Files.getLastModifiedTime(it).toMillis()}"
                }.toList()
            }
            // The dev layout checks the files against manifest.json one level up: a change there counts too.
            val manifest = dir.parent?.resolve("manifest.json")?.takeIf { Files.isRegularFile(it) }
            val outer = manifest?.let { "manifest.json:${Files.size(it)}:${Files.getLastModifiedTime(it).toMillis()}" }
            return (files + listOfNotNull(outer)).joinToString("|")
        }
    }
}
