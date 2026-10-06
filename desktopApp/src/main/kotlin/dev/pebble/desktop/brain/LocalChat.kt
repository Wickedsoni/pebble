package dev.pebble.desktop.brain

import dev.pebble.core.brain.ChatSafety
import dev.pebble.core.brain.ReplyContext
import dev.pebble.core.brain.ReplyGenerator
import dev.pebble.desktop.core.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.TimeUnit

/**
 * Smart replies with a local chat model (WP C5, ADR 0012): `llama-server` from llama.cpp as a separate process.
 *  - It starts on the first reply that you ask for, and stops after [idleMillis] without use, and on exit.
 *  - It listens only on 127.0.0.1, on a free port, and it needs a random key for each start. The key goes in
 *    its environment (`LLAMA_API_KEY`), not on the command line, which other programs can read.
 *  - Pebble sends the key only after it has checked that the child process itself listens on the port
 *    ([portOwners]); a start that lost the port to another program is tried again on a new port.
 *  - `--offline` and `--no-webui`: it never downloads anything and serves no web page.
 *  - A reply waits at most [replyMillis]. Then Pebble answers with a canned line, and the model keeps loading
 *    for the next reply. [ChatSafety.clean] must accept a reply before it is shown.
 * Flags checked with `llama-server --help` on build b11146 (llama.cpp v0.5.0).
 */
class LocalChat(
    /** The server and the model to use, or null if no chat pack is installed. */
    private val locate: () -> ChatFiles?,
    private val scope: CoroutineScope,
    private val log: Logger,
    /** Server output for diagnostics (overwritten at each start); null: thrown away. */
    private val logFile: Path? = null,
    private val idleMillis: Long = 10 * 60_000L,
    private val replyMillis: Long = 6_000L,
    private val startMillis: Long = 60_000L,
    private val threads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4),
    /** Starts the server process. Replaced in tests. */
    private val launcher: ServerLauncher = ServerLauncher { command, key, dir, out -> launchProcess(command, key, dir, out) },
    /** The process ids that listen on a local TCP port, or null if this cannot be found. Replaced in tests. */
    private val portOwners: (port: Int) -> Set<Long>? = ::netstatOwners,
    /** Runs the request work (JSON, waiting), so the caller's thread (for example the UI thread) never blocks. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ReplyGenerator,
    AutoCloseable {
    data class ChatFiles(val server: Path, val model: Path)

    /** Starts one server process: [command] with [key] as `LLAMA_API_KEY`, in [dir], output to [out] (null: dropped). */
    fun interface ServerLauncher {
        fun launch(command: List<String>, key: String, dir: Path, out: Path?): Process
    }

    private class Running(val process: Process, val port: Int, val key: String)

    private sealed interface Attempt {
        class Ready(val server: Running) : Attempt

        /** The port was taken or is not ours: try again with a new port. */
        data object PortLost : Attempt

        data object Failed : Attempt
    }

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val lock = Mutex()

    @Volatile private var starting: Deferred<Running?>? = null

    /** Guards [running] (writes), [pending] and [generation]. [close] is not a suspend function, so no Mutex. */
    private val state = Any()

    @Volatile private var running: Running? = null

    /** The process that still loads its model. [close] stops it, so a quit never leaves a server behind. */
    private var pending: Process? = null

    /** Changes at each [close]. A start from an earlier generation must not publish its server. */
    private var generation = 0

    @Volatile private var lastUse = 0L

    val available: Boolean get() = locate() != null

    /** True while the server process runs (for the About page and tests). */
    val isRunning: Boolean get() = running?.process?.isAlive == true

    /** The server's process id while it runs (memory checks). */
    val pid: Long? get() = running?.process?.takeIf { it.isAlive }?.pid()

    /**
     * A reply from the chat model, or null (not installed, too slow, not safe to show, or any failure).
     * The work runs on [dispatcher]. It never throws, except for the cancellation of the caller.
     */
    override suspend fun reply(ctx: ReplyContext): String? = withContext(dispatcher) {
        try {
            lastUse = System.currentTimeMillis()
            val deadline = lastUse + replyMillis
            val start = lock.withLock {
                running?.takeIf { it.process.isAlive }?.let { return@withLock null }
                starting?.takeIf { it.isActive } ?: scope.async { start() }.also { starting = it }
            }
            withTimeoutOrNull(replyMillis) {
                val r = running?.takeIf { it.process.isAlive } ?: awaitStart(start) ?: return@withTimeoutOrNull null
                val raw = complete(r, ctx, deadline) ?: return@withTimeoutOrNull null
                ChatSafety.clean(raw, ctx).also { if (it == null) log.info(TAG, "reply not shown (failed the safety check)") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "smart reply failed", e)
            null
        } finally {
            lastUse = System.currentTimeMillis()
        }
    }

    /** The result of [start]. If only the start was cancelled (by [close]) and the caller was not, this is null. */
    private suspend fun awaitStart(start: Deferred<Running?>?): Running? = try {
        start?.await()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        null
    }

    /** Starts the server and waits until the model is loaded; null if it cannot. */
    private suspend fun start(): Running? {
        val files = locate() ?: return null
        val myGeneration = synchronized(state) { generation }
        killLeftovers(files.server, log)
        val deadline = System.currentTimeMillis() + startMillis
        repeat(MAX_ATTEMPTS) { n ->
            when (val a = attempt(files, deadline, myGeneration)) {
                is Attempt.Ready -> return a.server
                Attempt.Failed -> return null
                Attempt.PortLost -> log.info(TAG, "chat server lost its port (try ${n + 1} of $MAX_ATTEMPTS)")
            }
        }
        return null
    }

    /**
     * One start on a new port. The port comes from a socket that is closed before the server binds it, so another
     * program could take it first. Therefore Pebble sends the key only after the listener on the port is the child
     * process itself ([portOwners]). Before that it asks `/health`, which needs no key and holds no secret.
     */
    private suspend fun attempt(files: ChatFiles, deadline: Long, myGeneration: Int): Attempt {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val key = HexFormat.of().formatHex(ByteArray(24).also { SecureRandom().nextBytes(it) })
        val command = listOf(
            files.server.toString(),
            "-m", files.model.toString(),
            "--host", "127.0.0.1",
            "--port", port.toString(),
            "-c", "2048",
            "-t", threads.toString(),
            "-np", "1",
            "--cache-ram", "64",
            "--reasoning", "off",
            "--no-webui",
            "--offline",
        )
        val t0 = System.currentTimeMillis()
        // The generation check and the "pending" mark are one step, so close() cannot miss this process.
        val process = synchronized(state) {
            if (generation != myGeneration) return Attempt.Failed
            runCatching { launcher.launch(command, key, files.server.parent, logFile) }.getOrElse {
                log.warn(TAG, "chat server did not start", it)
                return Attempt.Failed
            }.also { pending = it }
        }
        try {
            while (true) {
                if (synchronized(state) { generation != myGeneration }) return stopped(process, Attempt.Failed)
                if (!process.isAlive) {
                    log.warn(TAG, "chat server not ready (exit=${process.exitValue()})")
                    // An early exit most likely means that the port was taken.
                    return if (System.currentTimeMillis() - t0 < BIND_WINDOW_MILLIS) Attempt.PortLost else Attempt.Failed
                }
                if (System.currentTimeMillis() >= deadline) {
                    log.warn(TAG, "chat server not ready (timeout)")
                    return stopped(process, Attempt.Failed)
                }
                if (healthy(port)) {
                    val owners = portOwners(port)
                    if (owners == null) {
                        log.warn(TAG, "cannot check who listens on the chat port; not using the chat server")
                        return stopped(process, Attempt.Failed)
                    }
                    if (owners != setOf(process.pid())) {
                        log.warn(TAG, "the chat port is used by another program; the key was not sent")
                        return stopped(process, Attempt.PortLost)
                    }
                    val r = Running(process, port, key)
                    val published = synchronized(state) {
                        (generation == myGeneration).also {
                            if (it) {
                                running = r
                                pending = null
                            }
                        }
                    }
                    if (!published) return stopped(process, Attempt.Failed)
                    log.info(
                        TAG,
                        "chat server ready in ${System.currentTimeMillis() - t0} ms (pid ${process.pid()}, ${files.model.fileName})",
                    )
                    watchIdle()
                    return Attempt.Ready(r)
                }
                delay(150)
            }
        } catch (e: Throwable) {
            // Cancelled or failed: the child must not stay behind.
            stop(process)
            throw e
        } finally {
            synchronized(state) { if (pending === process) pending = null }
        }
    }

    private fun stopped(p: Process, result: Attempt): Attempt {
        stop(p)
        return result
    }

    /** True when `/health` answers 200. It needs no key, so none is sent. */
    private suspend fun healthy(port: Int): Boolean = try {
        val req = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/health")).timeout(Duration.ofSeconds(1)).GET().build()
        http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).await().statusCode() == 200
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /** Sends the chat request without blocking a thread: a cancel or the [deadline] ends the request. */
    private suspend fun complete(r: Running, ctx: ReplyContext, deadline: Long): String? {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0) return null
        return try {
            val body = buildJsonObject {
                put(
                    "messages",
                    buildJsonArray {
                        ChatSafety.messages(ctx).forEach { (role, text) ->
                            add(
                                buildJsonObject {
                                    put("role", role)
                                    put("content", text)
                                },
                            )
                        }
                    },
                )
                put("max_tokens", 80)
                put("temperature", 0.7)
                put("top_p", 0.9)
                put("stream", false)
            }
            val req = HttpRequest.newBuilder(URI("http://127.0.0.1:${r.port}/v1/chat/completions"))
                .timeout(Duration.ofMillis(remaining))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ${r.key}")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
            // Last check before the key leaves: the server must still be the one that we verified.
            if (running !== r || !r.process.isAlive) return null
            val res = http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).await()
            if (res.statusCode() != 200) return null
            Json.parseToJsonElement(res.body()).jsonObject.getValue("choices").jsonArray.first().jsonObject
                .getValue("message").jsonObject["content"]?.let { (it as? JsonPrimitive)?.content }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "chat request failed", e)
            null
        }
    }

    private fun watchIdle() = scope.launch {
        while (isActive) {
            delay(30_000)
            val r = running ?: return@launch
            if (!r.process.isAlive) {
                synchronized(state) { if (running === r) running = null }
                return@launch
            }
            if (System.currentTimeMillis() - lastUse > idleMillis) {
                log.info(TAG, "chat server stopped after ${idleMillis / 60_000} idle minutes")
                synchronized(state) { if (running === r) running = null }
                stop(r.process)
                return@launch
            }
        }
    }

    private fun stop(p: Process) {
        p.destroy()
        if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
    }

    /**
     * Stops the server now, also one that still loads its model, and cancels a start that is under way.
     * You can ask for a reply again afterwards (Smart replies off, then on).
     */
    override fun close() {
        val r: Running?
        val p: Process?
        synchronized(state) {
            generation++
            r = running
            p = pending
            running = null
            pending = null
        }
        starting?.cancel()
        r?.let { stop(it.process) }
        p?.let { stop(it) }
    }

    companion object {
        private const val TAG = "chat"
        private const val MAX_ATTEMPTS = 3
        private const val BIND_WINDOW_MILLIS = 10_000L

        /**
         * The chat files in [dir]: `llama-server.exe` and the first `.gguf` model. Null if either is missing.
         */
        fun filesIn(dir: Path?): ChatFiles? {
            if (dir == null || !Files.isDirectory(dir)) return null
            val server = dir.resolve("llama-server.exe").takeIf { Files.exists(it) } ?: return null
            // A signed pack names its model file in pack.json (the list the hashes cover); a developer folder is listed.
            val listed = ModelPack.installedManifest(dir)?.files?.map { it.path }
            val names = listed ?: Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() }
            val model = names.filter { it.endsWith(".gguf", ignoreCase = true) }.minOrNull()?.let { dir.resolve(it) }
                ?.takeIf { Files.exists(it) } ?: return null
            return ChatFiles(server, model)
        }

        /**
         * A server left by a Pebble that did not exit cleanly: same program file, so it is ours. Stop it before a
         * pack install ([ModelPack.applyStaged]), because a running server locks its files.
         */
        fun killLeftovers(server: Path, log: Logger) {
            val exe = server.toAbsolutePath().normalize().toString()
            ProcessHandle.allProcesses()
                .filter { p -> p.info().command().map { it.equals(exe, ignoreCase = true) }.orElse(false) }
                .forEach { p ->
                    log.info(TAG, "stopping a chat server left from an earlier run (pid ${p.pid()})")
                    p.destroyForcibly()
                    runCatching { p.onExit().get(2, TimeUnit.SECONDS) }
                }
        }

        private fun launchProcess(command: List<String>, key: String, dir: Path, out: Path?): Process {
            val pb = ProcessBuilder(command).directory(dir.toFile())
            pb.environment()["LLAMA_API_KEY"] = key
            pb.redirectErrorStream(true)
            if (out != null) pb.redirectOutput(out.toFile()) else pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            return pb.start()
        }

        /**
         * The process ids that listen on local TCP [port], from `netstat -ano` (the JDK has no API for this). TCP over IPv4 and IPv6.
         * Null if netstat cannot run.
         */
        fun netstatOwners(port: Int): Set<Long>? = runCatching {
            val exe = Path.of(System.getenv("SystemRoot") ?: "C:\\Windows", "System32", "netstat.exe")
            val out = Files.createTempFile("pebble-netstat", ".txt")
            try {
                val p = ProcessBuilder(exe.toString(), "-ano").redirectErrorStream(true).redirectOutput(out.toFile()).start()
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    return@runCatching null
                }
                parseNetstat(Files.readAllBytes(out), port)
            } finally {
                Files.deleteIfExists(out)
            }
        }.getOrNull()

        /**
         * [parseListeners] for the raw output of netstat. netstat writes the OEM code page, which is not UTF-8 on many
         * Windows languages. Only the ASCII tokens are read, so every byte is decoded as one character.
         */
        fun parseNetstat(bytes: ByteArray, port: Int): Set<Long> = parseListeners(String(bytes, Charsets.ISO_8859_1).lines(), port)

        /**
         * The pids of the listening sockets on [port] in `netstat -ano` [lines]. A listener has the foreign address
         * `...:0`. The state word is not read: it changes with the Windows language.
         */
        fun parseListeners(lines: List<String>, port: Int): Set<Long> = lines.mapNotNull { line ->
            val t = line.trim().split(Regex("\\s+"))
            val listener = t.size >= 5 && t[0].equals("TCP", ignoreCase = true) && t[1].endsWith(":$port") && t[2].endsWith(":0")
            if (listener) t[4].toLongOrNull() else null
        }.toSet()
    }
}
