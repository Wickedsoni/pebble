package dev.pebble.desktop

import com.sun.net.httpserver.HttpServer
import dev.pebble.core.brain.ReplyContext
import dev.pebble.desktop.brain.LocalChat
import dev.pebble.desktop.core.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LocalChat] with a fake server process (no llama-server needed): start and stop races (R4-3), a failure
 * inside a reply (R4-14), the port check before the key is sent (R4-15) and a reply that does not block (R4-1).
 */
class LocalChatTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val servers = CopyOnWriteArrayList<HttpServer>()
    private val files = LocalChat.ChatFiles(Path.of("fake-llama-server.exe").toAbsolutePath(), Path.of("fake.gguf").toAbsolutePath())
    private val ctx = ReplyContext(userText = "hello there")

    @AfterTest
    fun cleanup() {
        scope.cancel()
        servers.forEach { it.stop(0) }
    }

    /** A process that does nothing: alive until [destroy] (or never alive, if [alive] is false at the start). */
    private class FakeProcess(private val pid: Long, alive: Boolean = true) : Process() {
        @Volatile var alive = alive

        @Volatile var destroyed = false

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = InputStream.nullInputStream()

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int = 0

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive

        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 1

        override fun destroy() {
            destroyed = true
            alive = false
        }

        override fun isAlive(): Boolean = alive

        override fun pid(): Long = pid
    }

    private fun portOf(command: List<String>) = command[command.indexOf("--port") + 1].toInt()

    /** A server on [port] that records the Authorization header of each request by path. */
    private fun serve(port: Int, seen: MutableList<Pair<String, String?>>, chatDelayMillis: Long = 0) {
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
        s.executor = Executors.newCachedThreadPool()
        s.createContext("/") { ex ->
            seen += ex.requestURI.path to ex.requestHeaders.getFirst("Authorization")
            if (chatDelayMillis > 0 && ex.requestURI.path.endsWith("completions")) Thread.sleep(chatDelayMillis)
            val body = if (ex.requestURI.path ==
                "/health"
            ) {
                "{}"
            } else {
                """{"choices":[{"message":{"content":"Hello! Good to hear from you."}}]}"""
            }
            ex.sendResponseHeaders(200, body.length.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        s.start()
        servers += s
    }

    private fun chat(
        launcher: LocalChat.ServerLauncher,
        owners: (Int) -> Set<Long>? = { null },
        locate: () -> LocalChat.ChatFiles? = { files },
        replyMillis: Long = 4_000,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) = LocalChat(
        locate,
        scope,
        Logger.None,
        replyMillis = replyMillis,
        startMillis = 30_000,
        launcher = launcher,
        portOwners = owners,
        dispatcher = dispatcher,
    )

    private fun waitFor(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!check()) {
            check(System.currentTimeMillis() < end) { "timeout: $what" }
            Thread.sleep(20)
        }
    }

    @Test
    fun `close while the server still starts stops the process`() = runBlocking {
        val p = FakeProcess(100)
        val launched = AtomicInteger()
        val c = chat({ _, _, _, _ -> p.also { launched.incrementAndGet() } }, replyMillis = 400)
        assertNull(c.reply(ctx)) // nothing listens: the reply times out, the start goes on
        assertEquals(1, launched.get())
        assertTrue(p.alive)
        c.close()
        assertTrue(p.destroyed)
        assertEquals(1, launched.get(), "a start that was closed must not launch again")
    }

    @Test
    fun `cancelling the app scope while the server starts stops the process`() = runBlocking {
        val p = FakeProcess(101)
        val c = chat({ _, _, _, _ -> p }, replyMillis = 300)
        assertNull(c.reply(ctx))
        scope.cancel()
        waitFor("the pending process is stopped") { p.destroyed }
        c.close()
    }

    @Test
    fun `chat works again after close (Smart replies off, then on)`() = runBlocking {
        val seen = CopyOnWriteArrayList<Pair<String, String?>>()
        val p = FakeProcess(102)
        val c = chat({ cmd, _, _, _ -> p.also { serve(portOf(cmd), seen) } }, owners = { setOf(102L) }, replyMillis = 4_000)
        c.close()
        val reply = c.reply(ctx)
        assertNotNull(reply)
        c.close()
        assertTrue(p.destroyed)
    }

    @Test
    fun `an exception while the server starts gives a null reply`() = runBlocking {
        val c = chat({ _, _, _, _ -> error("no") }, locate = { error("broken pack") })
        assertNull(c.reply(ctx))
        val c2 = chat({ _, _, _, _ -> error("cannot start") })
        assertNull(c2.reply(ctx))
    }

    @Test
    fun `no key is sent when another program owns the port`() = runBlocking {
        val seen = CopyOnWriteArrayList<Pair<String, String?>>()
        val launches = CopyOnWriteArrayList<FakeProcess>()
        val c = chat(
            { cmd, _, _, _ ->
                FakeProcess(200L + launches.size).also {
                    launches += it
                    serve(portOf(cmd), seen)
                }
            },
            owners = { setOf(9999L) },
        )
        assertNull(c.reply(ctx))
        assertEquals(3, launches.size, "tries 3 times")
        assertTrue(launches.all { it.destroyed })
        assertTrue(seen.none { it.second != null }, "no request had a key: $seen")
        assertTrue(seen.none { it.first.contains("completions") })
    }

    @Test
    fun `no key is sent when the port owner cannot be found`() = runBlocking {
        val seen = CopyOnWriteArrayList<Pair<String, String?>>()
        val c = chat({ cmd, _, _, _ -> FakeProcess(300).also { serve(portOf(cmd), seen) } }, owners = { null })
        assertNull(c.reply(ctx))
        assertTrue(seen.none { it.second != null })
    }

    @Test
    fun `a start that lost its port is tried again on a new port and then answers with the key`() = runBlocking {
        val seen = CopyOnWriteArrayList<Pair<String, String?>>()
        val ports = CopyOnWriteArrayList<Int>()
        var key = ""
        val c = chat(
            { cmd, k, _, _ ->
                ports += portOf(cmd)
                key = k
                if (ports.size == 1) {
                    FakeProcess(400, alive = false) // the server exits at once: the bind failed
                } else {
                    FakeProcess(401).also { serve(portOf(cmd), seen) }
                }
            },
            owners = { setOf(401L) },
        )
        assertEquals("Hello! Good to hear from you.", c.reply(ctx))
        assertEquals(2, ports.size)
        assertTrue(seen.filter { it.first == "/health" }.all { it.second == null }, "the health check needs no key")
        assertEquals("Bearer $key", seen.single { it.first.contains("completions") }.second)
        c.close()
    }

    @Test
    fun `a slow chat request ends with the reply deadline and does not block the caller`() = runBlocking {
        val seen = CopyOnWriteArrayList<Pair<String, String?>>()
        val c =
            chat({ cmd, _, _, _ ->
                FakeProcess(500).also { serve(portOf(cmd), seen, chatDelayMillis = 8_000) }
            }, owners = { setOf(500L) }, replyMillis = 1_500)
        val t0 = System.currentTimeMillis()
        assertNull(withTimeoutOrNull(5_000) { c.reply(ctx) })
        assertTrue(System.currentTimeMillis() - t0 < 4_000)
        c.close()
    }

    @Test
    fun `parseListeners reads the pid of the listeners on one port`() {
        val lines = listOf(
            "  Proto  Local Address          Foreign Address        State           PID",
            "  TCP    127.0.0.1:18765        0.0.0.0:0              LISTENING       4148",
            "  TCP    127.0.0.1:6467         127.0.0.1:18765        TIME_WAIT       0",
            "  TCP    [::]:18765             [::]:0                 LISTENING       77",
            "  TCP    0.0.0.0:187650         0.0.0.0:0              LISTENING       5",
        )
        assertEquals(setOf(4148L, 77L), LocalChat.parseListeners(lines, 18765))
        assertEquals(emptySet(), LocalChat.parseListeners(lines, 1))
    }

    @Test
    fun `parseNetstat reads output that is not UTF-8`() {
        // The byte 0xC9 (E acute in the OEM code page) is not valid UTF-8.
        val header = byteArrayOf(0x20, 0xC9.toByte(), 0x74, 0x61, 0x74)
        val line = "\n  TCP    [::]:18765             [::]:0                 LISTENING       77\n".toByteArray()
        assertEquals(setOf(77L), LocalChat.parseNetstat(header + line, 18765))
    }

    @Test
    fun `a reply in flight does not block the thread of its dispatcher`() = runBlocking {
        val seen = CopyOnWriteArrayList<Pair<String, String?>>()
        val single = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val c = chat(
                { cmd, _, _, _ -> FakeProcess(600).also { serve(portOf(cmd), seen, chatDelayMillis = 2_500) } },
                owners = { setOf(600L) },
                replyMillis = 6_000,
                dispatcher = single,
            )
            val reply = async(single) { c.reply(ctx) }
            waitFor("the chat request is in flight") { seen.any { it.first.contains("completions") } }
            var ticked = false
            launch(single) { ticked = true }.join()
            assertTrue(ticked, "the dispatcher must run other work while the request waits")
            assertTrue(!reply.isCompleted, "the request was still in flight")
            assertNotNull(reply.await())
            c.close()
        } finally {
            single.close()
        }
    }
}
