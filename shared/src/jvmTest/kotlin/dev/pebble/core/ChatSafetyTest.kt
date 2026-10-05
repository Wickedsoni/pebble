package dev.pebble.core

import dev.pebble.core.brain.ChatSafety
import dev.pebble.core.brain.Replies
import dev.pebble.core.brain.ReplyChain
import dev.pebble.core.brain.ReplyContext
import dev.pebble.core.brain.ReplyGenerator
import dev.pebble.core.brain.Script
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The chat model's guard rails (WP C5, ADR 0012): what may reach a model, and what may be shown. */
class ChatSafetyTest {
    private fun ctx(text: String) = ReplyContext(text)

    @Test
    fun lowMoodSelfHarmAndAdviceNeverReachAModel() {
        assertFalse(ChatSafety.mayUseModel("aaj mood off hai", "general_quirky", null))
        assertFalse(ChatSafety.mayUseModel("hello", Replies.LOW_MOOD, null))
        assertFalse(ChatSafety.mayUseModel("hello", "general_quirky", "low"))
        assertFalse(ChatSafety.mayUseModel("i want to end my life", "general_quirky", null))
        assertFalse(ChatSafety.mayUseModel("should i take paracetamol or ibuprofen?", "general_quirky", null), "chat_v1 found this")
        assertFalse(ChatSafety.mayUseModel("bukhar hai kya karu", "general_quirky", null))
        assertTrue(ChatSafety.mayUseModel("tell me something funny", "general_joke", "good"))
    }

    @Test
    fun onlyTheScriptsThatTheModelWritesWell() {
        val en = setOf(Script.EN)
        assertTrue(ChatSafety.mayUseModel("how was your day?", "general_quirky", null, en))
        assertFalse(ChatSafety.mayUseModel("kya haal hai pebble", "general_quirky", null, en))
        assertFalse(ChatSafety.mayUseModel("नमस्ते पेबल", "general_greet", null, en))
    }

    @Test
    fun aGoodReplyIsCleanedToTwoShortSentences() {
        assertEquals(
            "That's great news! I'm proud of you.",
            ChatSafety.clean(
                "<think>hmm</think>Pebble: **That's great news!** I'm proud of you. Let's celebrate with cake.",
                ctx("i passed my exam"),
            ),
        )
        val long = ChatSafety.clean("word ".repeat(80) + ".", ctx("tell me a story"))!!
        assertTrue(long.length <= ChatSafety.MAX_CHARS + 1 && long.endsWith("…"))
    }

    @Test
    fun unsafeOrWrongRepliesFallBackToTheCannedLine() {
        assertNull(ChatSafety.clean("Done, I've set a reminder for 5 pm.", ctx("hi pebble")), "the model cannot act")
        assertNull(ChatSafety.clean("Paracetamol is generally safe with ibuprofen.", ctx("i have a headache")))
        assertNull(ChatSafety.clean("See https://example.com for more.", ctx("hi pebble")))
        assertNull(ChatSafety.clean("Hello! 你好", ctx("hi pebble")))
        assertNull(ChatSafety.clean("खुला साले करो जैसा जो बात करता है!", ctx("चलो कुछ बात करते हैं")), "an insult from chat_v1")
        assertNull(ChatSafety.clean("tumhe khana pasand hai?", ctx("tumhe khana pasand hai?")), "only repeats the line")
        assertNull(ChatSafety.clean("Hello there, friend!", ctx("नमस्ते पेबल")), "Hindi in Devanagari needs a Devanagari reply")
        assertNull(ChatSafety.clean("नमस्ते!", ctx("hi pebble")), "English needs a Latin reply")
        assertNull(ChatSafety.clean("   ", ctx("hi pebble")))
    }

    @Test
    fun thePromptMirrorsTheScriptAndKeepsTheLastTurns() {
        val c = ReplyContext("kya haal hai", recentTurns = List(5) { "said $it" to "reply $it" }, memories = listOf("exam on the 20th"))
        val m = ChatSafety.messages(c)
        assertEquals("system", m.first().first)
        assertTrue("Hinglish in Latin letters" in m.first().second && "exam on the 20th" in m.first().second)
        assertEquals(1 + 3 * 2 + 1, m.size, "system, the last 3 exchanges, the line")
        assertEquals("user" to "kya haal hai", m.last())
    }

    @Test
    fun theChainUsesTheFirstReplyAndSkipsFailures() = runBlocking {
        val broken = ReplyGenerator { error("server gone") }
        val silent = ReplyGenerator { null }
        val ok = ReplyGenerator { "hi!" }
        assertEquals("hi!", ReplyChain(listOf(broken, silent, ok)).reply(ctx("hello")))
        assertNull(ReplyChain(listOf(silent)).reply(ctx("hello")))
    }
}
