package dev.pebble.desktop.tools

import dev.pebble.desktop.voice.MicCapture
import dev.pebble.desktop.voice.writeWav
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Records your own voice test set (plan 3.4):  ./gradlew :desktopApp:recordVoiceEval
 *
 * Shows commands from brain/eval/pebble_commands_v1.jsonl one at a time — say each the way *you*
 * would (rephrasing is fine: then type what you actually said). Saves brain/eval/voice_v1/NNN.wav
 * (gitignored) and appends to brain/eval/voice_v1/refs.jsonl {file, text, action, script}.
 * Re-running continues where you stopped. Enter = start/stop, s = skip, q = quit.
 */
fun main(args: Array<String>) {
    val brain = Path.of(args.getOrElse(0) { "../brain" }).toAbsolutePath().normalize()
    val out = brain.resolve("eval/voice_v1").also { Files.createDirectories(it) }
    val refs = out.resolve("refs.jsonl")
    val done = if (Files.exists(refs)) {
        Files.readAllLines(refs).filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject.getValue("prompt").jsonPrimitive.content }.toSet()
    } else {
        emptySet()
    }
    val prompts = Files.readAllLines(brain.resolve("eval/pebble_commands_v1.jsonl")).filter { it.isNotBlank() }
        .map { Json.parseToJsonElement(it).jsonObject }
        .filter { it.getValue("action").jsonPrimitive.content !in setOf("log_water", "set_interval") } // rules' job; keep it short
        .take(45)
    val stdin = System.`in`.bufferedReader()
    println("Pebble voice test set — ${done.size}/${prompts.size} recorded so far. Speak naturally, at your usual distance.")
    for ((i, p) in prompts.withIndex()) {
        val prompt = p.getValue("text").jsonPrimitive.content
        if (prompt in done) continue
        println("\n[${i + 1}/${prompts.size}]  \"$prompt\"\n  Enter = start recording, s = skip, q = quit")
        when (stdin.readLine()?.trim()) { "q", null -> return; "s" -> continue }
        val mic = MicCapture()
        if (!mic.start(maxSeconds = 12)) { println("No microphone found."); return }
        println("  ● recording… Enter to stop")
        stdin.readLine()
        val samples = mic.stop()
        println("  If you said it differently, type what you said (or just Enter if as shown):")
        val said = stdin.readLine()?.trim().orEmpty().ifEmpty { prompt }
        val file = "%03d.wav".format(i + 1)
        writeWav(out.resolve(file), samples)
        val row = buildJsonObject {
            put("file", JsonPrimitive(file)); put("prompt", JsonPrimitive(prompt)); put("text", JsonPrimitive(said))
            put("action", p.getValue("action")); put("script", p.getValue("script"))
        }
        Files.writeString(
            refs,
            row.toString() + "\n",
            Charsets.UTF_8,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND,
        )
        println("  saved $file (${samples.size / 16} ms)")
    }
    println("\nDone. Score it with:  ./gradlew :desktopApp:test --tests \"*VoiceCommandEvalTest*\"")
}
