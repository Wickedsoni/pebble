package dev.pebble.desktop.tools

import dev.pebble.core.brain.IpsReport
import dev.pebble.core.brain.NudgeArm
import dev.pebble.core.brain.NudgeIpsEvaluator
import dev.pebble.core.brain.NudgePolicy
import dev.pebble.core.brain.NudgeTarget
import dev.pebble.core.brain.SqlNudgeStore
import dev.pebble.core.brain.asTarget
import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.memory.MemoryEngine
import dev.pebble.core.memory.MemoryRepository
import java.io.File
import java.sql.DriverManager
import java.util.Locale
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * The offline gate for nudge policy changes (WP C4, ADR 0016). Run it on your own history:
 *
 *   ./gradlew :desktopApp:nudgeIps [--args="<pebble.db>"]
 *
 * It copies the database first (SQLite `VACUUM INTO` on a read-only connection, WAL included; trap 11), so
 * the running app and its data are not touched. It evaluates the [NudgePolicy] that this checkout builds from
 * your saved beliefs against what actually ran. A change to the priors or rewards passes when SNIPS is not
 * lower than what ran and ESS ≥ 200. Exit code 0 = passes, 1 = does not pass, 2 = usage.
 */
fun main(args: Array<String>) {
    if (args.size > 1) {
        System.err.println("usage: nudgeIps [pebble.db]   (default: %APPDATA%\\Pebble\\pebble.db)")
        exitProcess(2)
    }
    val source = args.firstOrNull()?.let(::File) ?: File(DatabaseFactory.defaultDataDir(), "pebble.db")
    if (!source.isFile) {
        System.err.println("no database at $source")
        exitProcess(2)
    }
    val copy = File.createTempFile("pebble-ips-", ".db").apply { delete() }
    val passes = try {
        DriverManager.getConnection("jdbc:sqlite:file:${source.absolutePath}?mode=ro").use { c ->
            c.createStatement().use { it.execute("VACUUM INTO '${copy.absolutePath.replace("'", "''")}'") }
        }
        report(copy)
    } finally {
        listOf("", "-wal", "-shm").forEach { File(copy.path + it).delete() }
    }
    exitProcess(if (passes) 0 else 1)
}

private fun report(copy: File): Boolean {
    val db = DatabaseFactory.create(copy)
    val memory = MemoryEngine(db, MemoryRepository(db), clock = { 0L }, hourOf = { 0 }, dayOf = { 0L }, waterGoalMl = { 0 })
    val policy = NudgePolicy(SqlNudgeStore(db), quietHours = memory::quietHours, random = Random(42))
    val evaluator = NudgeIpsEvaluator(db)
    val data = evaluator.episodes()
    println("decisions with a known outcome: ${data.episodes.size}   skipped: ${data.skipped}")
    NudgeArm.entries.forEach { arm ->
        val mine = data.episodes.filter { it.arm == arm }
        val mean = if (mine.isEmpty()) "-" else fmt(mine.sumOf { it.reward } / mine.size)
        println("  ${arm.name.padEnd(8)} ran ${mine.size.toString().padStart(4)}   mean reward $mean")
    }
    println()
    println("policy                   SNIPS     IPS     ESS   max w")
    val candidate = NudgeIpsEvaluator.evaluate(data, policy.asTarget())
    row("this checkout (gate)", candidate)
    NudgeArm.entries.forEach { row("always ${it.name}", NudgeIpsEvaluator.evaluate(data, NudgeTarget.always(it))) }
    println()
    println("what ran (observed mean reward): ${fmt(candidate.observed)}")
    val passes = candidate.passesGate()
    println(
        when {
            passes -> "PASS: SNIPS ${fmt(candidate.snips)} >= ${fmt(candidate.observed)} and ESS ${fmt(candidate.ess)} >= 200"

            candidate.ess < NudgeIpsEvaluator.MIN_ESS -> "NOT ENOUGH DATA: ESS ${fmt(
                candidate.ess,
            )} < 200; do not change the priors or rewards yet"

            else -> "FAIL: SNIPS ${fmt(candidate.snips)} < what ran ${fmt(candidate.observed)}"
        },
    )
    return passes
}

private fun row(name: String, r: IpsReport) =
    println(
        "${name.padEnd(
            22,
        )} ${fmt(r.snips).padStart(7)} ${fmt(r.ips).padStart(7)} ${fmt(r.ess).padStart(7)} ${fmt(r.maxWeight).padStart(7)}",
    )

private fun fmt(x: Double) = String.format(Locale.ROOT, "%.3f", x)
