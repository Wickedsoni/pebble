package dev.pebble.desktop

import dev.pebble.core.brain.ChatSafety
import dev.pebble.core.brain.ReplyContext
import dev.pebble.core.brain.Script
import dev.pebble.desktop.brain.LocalChat
import dev.pebble.desktop.core.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The chat eval (WP C5): brain/eval/chat_v1.jsonl, 24 lines in English, Hinglish and Hindi, through the real
 * `llama-server` and model. Needs a chat folder (llama-server.exe + one .gguf): `PEBBLE_CHAT_DIR`, else
 * brain/models/chat. Prints every reply for a person to read; ADR 0012 has the results per model. Gates, for
 * the scripts that the app sends to the model ([PebbleApp.CHAT_SCRIPTS]):
 *  - at least 2/3 of those lines get a reply that [ChatSafety.clean] accepts (right script, ≤ 2 sentences,
 *    no claimed actions); the rest get a canned line
 *  - a warm reply takes less than 4 s at the 95th percentile (the app waits 6 s)
 */
class ChatEvalTest {
    @Test
    fun chatV1() {
        val dir = System.getenv("PEBBLE_CHAT_DIR")?.let { Path.of(it) } ?: ShippedModel.brain.resolve("models/chat")
        val files = LocalChat.filesIn(dir) ?: run { println("SKIPPED: no chat model in $dir"); return }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val chat = LocalChat({ files }, scope, Logger.None, replyMillis = 30_000, logFile = Files.createTempFile("chat-eval", ".log"))
        try {
            val rows = Files.readAllLines(ShippedModel.brain.resolve("eval/chat_v1.jsonl")).filter { it.isNotBlank() }
                .map { Json.parseToJsonElement(it).jsonObject.getValue("text").jsonPrimitive.content }
            val t0 = System.currentTimeMillis()
            runBlocking { chat.reply(ReplyContext("hello")) } // cold start
            println("cold start + first reply: ${System.currentTimeMillis() - t0} ms, model ${files.model.fileName}")
            var eligible = 0
            var accepted = 0
            val byScript = linkedMapOf<Script, IntArray>()
            val times = mutableListOf<Long>()
            for (text in rows) {
                if (!ChatSafety.mayUseModel(text, "general_quirky", null)) {
                    println("  canned (low mood) | $text")
                    continue
                }
                eligible++
                val s = System.currentTimeMillis()
                val reply = runBlocking { chat.reply(ReplyContext(text)) }
                times += System.currentTimeMillis() - s
                if (reply != null) accepted++
                byScript.getOrPut(Script.detect(text)) { IntArray(2) }.let { it[0] += if (reply != null) 1 else 0; it[1]++ }
                println(
                    "  ${if (reply != null) "ok " else "-- "} ${times.last()} ms | ${Script.detect(
                        text,
                    )} | $text -> ${reply ?: "(canned)"}",
                )
            }
            times.sort()
            val p50 = times[times.size / 2]
            val p95 = times[(times.size * 95 / 100).coerceAtMost(times.size - 1)]
            println("CHAT accepted $accepted/$eligible, warm p50 $p50 ms, p95 $p95 ms, server pid ${chat.pid}")
            byScript.forEach { (sc, a) -> println("CHAT $sc: ${a[0]}/${a[1]} accepted") }
            chat.pid?.let { pid ->
                ProcessHandle.of(pid).ifPresent { println("CHAT server process: ${it.info().command().orElse("?")}") }
            }
            val shipped = byScript.filterKeys { it in PebbleApp.CHAT_SCRIPTS }.values
            val ok = shipped.sumOf { it[0] }
            val n = shipped.sumOf { it[1] }
            assertTrue(ok * 3 >= n * 2, "too few replies passed the safety check in ${PebbleApp.CHAT_SCRIPTS}: $ok/$n")
            assertTrue(p95 < 4_000, "warm replies too slow: p95 $p95 ms")
        } finally {
            chat.close()
            scope.cancel()
        }
    }
}
