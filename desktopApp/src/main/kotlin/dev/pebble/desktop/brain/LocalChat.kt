package dev.pebble.desktop.brain

import dev.pebble.core.brain.ChatSafety
import dev.pebble.core.brain.ReplyContext
import dev.pebble.core.brain.ReplyGenerator
import dev.pebble.desktop.core.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration
import java.util.HexFormat

/**
 * Smart replies with a local chat model (WP C5, ADR 0012): `llama-server` from llama.cpp as a separate process.
 *  - It starts on the first reply that you ask for, and stops after [idleMillis] without use, and on exit.
 *  - It listens only on 127.0.0.1, on a free port, and it needs a random key for each start. The key goes in
 *    its environment (`LLAMA_API_KEY`), not on the command line, which other programs can read.
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
) : ReplyGenerator,
    AutoCloseable {
    data class ChatFiles(val server: Path, val model: Path)

    private class Running(val process: Process, val port: Int, val key: String)

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val lock = Mutex()
    private var starting: Deferred<Running?>? = null

    @Volatile private var running: Running? = null

    @Volatile private var lastUse = 0L

    val available: Boolean get() = locate() != null

    /** True while the server process runs (for the About page and tests). */
    val isRunning: Boolean get() = running?.process?.isAlive == true

    /** The server's process id while it runs (memory checks). */
    val pid: Long? get() = running?.process?.takeIf { it.isAlive }?.pid()

    override suspend fun reply(ctx: ReplyContext): String? {
        lastUse = System.currentTimeMillis()
        val start = lock.withLock {
            running?.takeIf { it.process.isAlive }?.let { return@withLock null }
            starting?.takeIf { it.isActive } ?: scope.async { start() }.also { starting = it }
        }
        return withTimeoutOrNull(replyMillis) {
            val r = running?.takeIf { it.process.isAlive } ?: start?.await() ?: return@withTimeoutOrNull null
            val raw = complete(r, ctx) ?: return@withTimeoutOrNull null
            ChatSafety.clean(raw, ctx).also { if (it == null) log.info(TAG, "reply not shown (failed the safety check)") }
        }.also { lastUse = System.currentTimeMillis() }
    }

    /** Starts the server and waits until the model is loaded; null if it cannot. */
    private suspend fun start(): Running? {
        val files = locate() ?: return null
        killLeftovers(files.server)
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val key = HexFormat.of().formatHex(ByteArray(24).also { SecureRandom().nextBytes(it) })
        val pb = ProcessBuilder(
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
        ).directory(files.server.parent.toFile())
        pb.environment()["LLAMA_API_KEY"] = key
        pb.redirectErrorStream(true)
        if (logFile != null) pb.redirectOutput(logFile.toFile()) else pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        val process = runCatching { pb.start() }.getOrElse {
            log.warn(TAG, "chat server did not start", it)
            return null
        }
        val r = Running(process, port, key)
        val t0 = System.currentTimeMillis()
        while (process.isAlive && System.currentTimeMillis() - t0 < startMillis) {
            if (healthy(r)) {
                running = r
                log.info(TAG, "chat server ready in ${System.currentTimeMillis() - t0} ms (pid ${process.pid()}, ${files.model.fileName})")
                watchIdle()
                return r
            }
            delay(150)
        }
        log.warn(TAG, "chat server not ready (alive=${process.isAlive}, exit=${if (process.isAlive) "-" else process.exitValue()})")
        stop(process)
        return null
    }

    private fun healthy(r: Running): Boolean = runCatching {
        val req = HttpRequest.newBuilder(URI("http://127.0.0.1:${r.port}/health")).timeout(Duration.ofSeconds(1))
            .header("Authorization", "Bearer ${r.key}").GET().build()
        http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
    }.getOrDefault(false)

    private fun complete(r: Running, ctx: ReplyContext): String? = runCatching {
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
            .timeout(Duration.ofMillis(replyMillis))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${r.key}")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() != 200) return@runCatching null
        Json.parseToJsonElement(res.body()).jsonObject.getValue("choices").jsonArray.first().jsonObject
            .getValue("message").jsonObject["content"]?.let { (it as? JsonPrimitive)?.content }
    }.onFailure { log.warn(TAG, "chat request failed", it) }.getOrNull()

    private fun watchIdle() = scope.launch {
        while (isActive) {
            delay(30_000)
            val r = running ?: return@launch
            if (!r.process.isAlive) {
                running = null
                return@launch
            }
            if (System.currentTimeMillis() - lastUse > idleMillis) {
                log.info(TAG, "chat server stopped after ${idleMillis / 60_000} idle minutes")
                running = null
                stop(r.process)
                return@launch
            }
        }
    }

    /** A server left by a Pebble that did not exit cleanly: same program file, so it is ours. */
    private fun killLeftovers(server: Path) {
        val exe = server.toAbsolutePath().normalize().toString()
        ProcessHandle.allProcesses()
            .filter { p -> p.info().command().map { it.equals(exe, ignoreCase = true) }.orElse(false) }
            .forEach { p ->
                log.info(TAG, "stopping a chat server left from an earlier run (pid ${p.pid()})")
                p.destroyForcibly()
            }
    }

    private fun stop(p: Process) {
        p.destroy()
        if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
    }

    override fun close() {
        running?.let { stop(it.process) }
        running = null
    }

    companion object {
        private const val TAG = "chat"

        /**
         * The chat files in [dir]: `llama-server.exe` and the first `.gguf` model. Null if either is missing.
         */
        fun filesIn(dir: Path?): ChatFiles? {
            if (dir == null || !java.nio.file.Files.isDirectory(dir)) return null
            val server = dir.resolve("llama-server.exe").takeIf { java.nio.file.Files.exists(it) } ?: return null
            val model =
                java.nio.file.Files.list(dir).use { s ->
                    s.filter { it.fileName.toString().endsWith(".gguf") }.sorted().findFirst().orElse(null)
                }
                    ?: return null
            return ChatFiles(server, model)
        }
    }
}
