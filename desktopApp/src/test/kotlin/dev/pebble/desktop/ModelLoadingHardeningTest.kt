package dev.pebble.desktop

import dev.pebble.core.db.DatabaseFactory
import dev.pebble.desktop.brain.BackgroundFlag
import dev.pebble.desktop.brain.LazyModel
import dev.pebble.desktop.brain.LocalChat
import dev.pebble.desktop.brain.ModelChecksums
import dev.pebble.desktop.brain.ModelPack
import dev.pebble.desktop.brain.ModelPack.Result
import dev.pebble.desktop.brain.ModelStatus
import dev.pebble.desktop.brain.OnnxIntentModel
import dev.pebble.desktop.brain.VerifiedModelCache
import dev.pebble.desktop.brain.buildOrClose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Model loading that must not freeze the UI, loop, race an unload, or trust a file the signature does not cover (QA round 1, P9b). */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelLoadingHardeningTest {
    private class Fake : AutoCloseable {
        var closed = false

        override fun close() {
            closed = true
        }
    }

    private val made = mutableListOf<Fake>()

    private fun TestScope.model(
        scope: CoroutineScope = backgroundScope,
        locate: () -> Path? = { Path.of("models/fake") },
        verify: (Path) -> Boolean = { true },
        load: (Path) -> Fake = { Fake().also { made += it } },
        fingerprint: (Path) -> String = { "same" },
        describe: (Fake) -> String = { "" },
    ) = LazyModel(
        name = "fake",
        scope = scope,
        idleMillis = 10 * 60_000L,
        locate = locate,
        verify = verify,
        load = load,
        clock = { testScheduler.currentTime },
        io = StandardTestDispatcher(testScheduler),
        fingerprint = fingerprint,
        describe = describe,
        retryBackoffMillis = 5 * 60_000L,
    )

    // ---- R4-4: no file work on the caller's thread ----

    @Test
    fun warmUpFindsAndChecksTheFolderOnlyOnTheBackgroundDispatcher() = runTest {
        var located = 0
        var verified = 0
        val m = model(locate = { located++; Path.of("models/fake") }, verify = { verified++; true })
        m.warmUp()
        assertEquals(0, located, "locate hashes pack files: not on the caller's thread")
        assertEquals(0, verified)
        runCurrent()
        assertEquals(1, located)
        assertEquals(1, verified)
        assertIs<ModelStatus.Ready>(m.status.value)
    }

    @Test
    fun noModelFolderIsReportedFromTheBackground() = runTest {
        val m = model(locate = { null })
        m.warmUp()
        runCurrent()
        assertEquals(ModelStatus.NotFound, m.status.value)
    }

    @Test
    fun aSlowHashDoesNotBlockACachedCheck() {
        val dir = Files.createTempDirectory("cache-lock")
        val slow = dir.resolve("slow.bin").also { it.writeText("slow") }
        val quick = dir.resolve("quick.bin").also { it.writeText("quick") }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cache = VerifiedModelCache(dir.resolve("v.json")) { p ->
            if (p == slow) {
                started.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            ModelChecksums.sha256(p)
        }
        assertTrue(cache.matches(quick, ModelChecksums.sha256(quick)))
        val t = thread { cache.matches(slow, ModelChecksums.sha256(slow)) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        var quickDone = false
        val q = thread { quickDone = cache.matches(quick, ModelChecksums.sha256(quick)) }
        q.join(2_000)
        assertTrue(!q.isAlive && quickDone, "a check of a known file waits for no hash")
        release.countDown()
        t.join(5_000)
    }

    @Test
    fun anInstalledPackIsNotHashedAgainAtTheFirstLoad() {
        val h = PackHelper()
        h.stage(h.pack())
        var hashes = 0
        val cache = VerifiedModelCache(h.root.resolve("v.json")) { hashes++; ModelChecksums.sha256(it) }
        assertEquals(listOf("installed intent"), ModelPack.applyStaged(h.models, cache, h.keys))
        assertIs<Result.Ok>(ModelPack.verifyInstalled(h.models.resolve("intent"), "intent", cache, h.keys))
        assertEquals(0, hashes, "stageInstall already checked each file")
    }

    @Test
    fun aStagedFileWithAnotherSizeIsNotTrustedWithoutAHash() {
        val h = PackHelper()
        h.stage(h.pack())
        h.models.resolve("intent.staged/intent.int8.onnx").writeText("evil weights, other size")
        var hashes = 0
        val cache = VerifiedModelCache(h.root.resolve("v.json")) { hashes++; ModelChecksums.sha256(it) }
        ModelPack.applyStaged(h.models, cache, h.keys)
        assertIs<Result.Invalid>(ModelPack.verifyInstalled(h.models.resolve("intent"), "intent", cache, h.keys))
        assertTrue(hashes > 0)
    }

    // ---- R4-5: only listed files ----

    @Test
    fun aFileThePackDoesNotListIsRefused() {
        val h = PackHelper()
        h.stage(h.pack())
        ModelPack.applyStaged(h.models)
        val dir = h.models.resolve("intent")
        assertIs<Result.Ok>(ModelPack.verifyInstalled(dir, "intent", keys = h.keys))
        dir.resolve("evil.dll").writeText("MZ")
        val r = ModelPack.verifyInstalled(dir, "intent", keys = h.keys)
        assertIs<Result.Invalid>(r)
        assertTrue("evil.dll" in r.reason, r.reason)
        Files.delete(dir.resolve("evil.dll"))
        Files.createDirectories(dir.resolve("tokenizer/deeper"))
        dir.resolve("tokenizer/deeper/Evil.DLL").writeText("MZ")
        assertIs<Result.Invalid>(ModelPack.verifyInstalled(dir, "intent", keys = h.keys), "also in a subfolder")
    }

    @Test
    fun theChatModelComesFromThePackListNotFromTheFolder() {
        val h = PackHelper()
        val src = Files.createTempDirectory(h.root, "chat")
        src.resolve("llama-server.exe").writeText("exe")
        src.resolve("m.gguf").writeText("model")
        ModelPack.sign(src, "chat", "v1", null, "test-key", h.pair.private)
        src.resolve("a-unlisted.gguf").writeText("not signed") // sorts before m.gguf
        val files = LocalChat.filesIn(src)
        assertEquals(src.resolve("m.gguf"), files?.model)
        assertIs<Result.Invalid>(ModelPack.verifyInstalled(src, "chat", keys = h.keys))
    }

    // ---- R4-6, R4-7: a failure is remembered; nothing stays "loading" ----

    @Test
    fun aFailedChecksumIsNotHashedAgainUntilAFileChanges() = runTest {
        var verifies = 0
        var files = "v1"
        val m = model(verify = { verifies++; false }, fingerprint = { files })
        repeat(5) { m.warmUp(); runCurrent() }
        assertEquals(1, verifies, "a debounced keystroke must not hash the model again")
        assertEquals(ModelStatus.ChecksumMismatch, m.status.value)
        files = "v2"
        m.warmUp()
        runCurrent()
        assertEquals(2, verifies, "a changed file is tried again")
    }

    @Test
    fun aFailedLoadIsTriedAgainAfterTheBackoff() = runTest {
        var loads = 0
        val m = model(load = { loads++; error("native crash") })
        m.warmUp()
        runCurrent()
        m.warmUp()
        runCurrent()
        assertEquals(1, loads)
        assertEquals(ModelStatus.LoadFailed("native crash"), m.status.value)
        advanceTimeBy(5 * 60_000L + 1)
        m.warmUp()
        runCurrent()
        assertEquals(2, loads)
    }

    @Test
    fun aVerifyThatThrowsEndsAsAFailureNotAsLoading() = runTest {
        val m = model(verify = { throw java.nio.file.NoSuchFileException("intent.int8.onnx") })
        m.warmUp()
        runCurrent()
        assertEquals(ModelStatus.LoadFailed("intent.int8.onnx"), m.status.value)
        assertNull(m.getOrNull())
    }

    @Test
    fun aShutdownDuringTheLoadFreesTheModelAndLeavesNoLoadingStatus() = runTest {
        val appScope = CoroutineScope(Job())
        val m = model(scope = appScope, verify = { appScope.coroutineContext[Job]!!.cancel(); true })
        m.warmUp()
        runCurrent()
        assertTrue(made.single().closed, "nobody would free it later")
        assertTrue(m.status.value != ModelStatus.Loading)
        assertNull(m.getOrNull())
    }

    // ---- R4-12: no unload under a user of the model ----

    @Test
    fun anIdleUnloadWaitsForWorkThatUsesTheModel() = runTest {
        val m = model()
        m.warmUp()
        runCurrent()
        val fake = made.single()
        val result = m.withModel { f ->
            // 20 idle minutes pass while this block (a long decode) still runs.
            advanceTimeBy(20 * 60_000L)
            runCurrent()
            assertTrue(!f.closed, "not freed under the block")
            "done"
        }
        assertEquals("done", result)
        advanceTimeBy(2 * 60_000L) // not idle: the block counted as a use
        runCurrent()
        assertTrue(!fake.closed)
        advanceTimeBy(11 * 60_000L)
        runCurrent()
        assertTrue(fake.closed, "freed once idle again")
        assertEquals(ModelStatus.Unloaded, m.status.value)
    }

    @Test
    fun awaitUseLoadsAndRunsTheBlock() = runTest {
        val m = model()
        assertEquals("ok", m.awaitUse { "ok" })
        assertEquals(null, model(locate = { null }).awaitUse { "never" })
    }

    // ---- R4-11: what was built is freed when a later step fails ----

    @Test
    fun aFailedBuildFreesWhatItBuiltNewestFirst() {
        val freed = mutableListOf<String>()
        val boom = assertFailsWith<IllegalStateException> {
            buildOrClose { c ->
                c.track("ctc") { freed += it }
                c.track("whisper") { freed += it }
                error("vad failed")
            }
        }
        assertEquals("vad failed", boom.message)
        assertEquals(listOf("whisper", "ctc"), freed)
    }

    @Test
    fun aFailingCleanupDoesNotHideTheFailureOrSkipTheRest() {
        val freed = mutableListOf<String>()
        val boom = assertFailsWith<IllegalStateException> {
            buildOrClose { c ->
                c.track("first") { freed += it }
                c.track("second") { error("close failed") }
                error("build failed")
            }
        }
        assertEquals("build failed", boom.message)
        assertEquals(listOf("first"), freed)
        assertEquals("close failed", boom.suppressed.single().message)
    }

    @Test
    fun aBuildThatWorksFreesNothing() {
        val freed = mutableListOf<String>()
        assertEquals(7, buildOrClose { c -> c.track("x") { freed += it }; 7 })
        assertTrue(freed.isEmpty())
    }

    @Test
    fun aCorruptOnnxFileFailsTheLoadCleanly() {
        if (!Files.exists(ShippedModel.dir.resolve("intent.int8.onnx"))) { println("SKIPPED: no model"); return }
        val dir = Files.createTempDirectory("bad-onnx")
        Files.createDirectories(dir.resolve("tokenizer"))
        Files.copy(ShippedModel.dir.resolve("tokenizer/tokenizer.json"), dir.resolve("tokenizer/tokenizer.json"))
        Files.copy(ShippedModel.dir.resolve("labels.json"), dir.resolve("labels.json"))
        dir.resolve("intent.int8.onnx").writeText("not an onnx file")
        assertFailsWith<Exception> { OnnxIntentModel(dir) }
    }

    // ---- R4-18: the same words as Python ----

    @Test
    fun splitWordsAgreesWithPythonStrSplitOnEveryBmpCharacter() {
        // `[i for i in range(0x10000) if chr(i).isspace()]` in CPython 3.
        val pythonSpaces = setOf(
            0x9, 0xa, 0xb, 0xc, 0xd, 0x1c, 0x1d, 0x1e, 0x1f, 0x20, 0x85, 0xa0, 0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004,
            0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200a, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000,
        )
        val wrong = (0 until 0x10000).filter { i ->
            if (i in 0xD800..0xDFFF) return@filter false // lone surrogates are not text
            val c = i.toChar()
            val words = OnnxIntentModel.splitWords("a${c}b")
            (words == listOf("a", "b")) != (i in pythonSpaces)
        }
        assertTrue(wrong.isEmpty(), "different from Python at ${wrong.map { "U+%04X".format(it) }}")
    }

    @Test
    fun pastedTextWithNoBreakSpacesSplitsLikePython() {
        assertEquals(listOf("kal", "subah", "7", "baje"), OnnxIntentModel.splitWords("  kal subah 7　baje\n"))
        assertEquals(emptyList(), OnnxIntentModel.splitWords("  　 "))
        assertEquals(listOf("x"), OnnxIntentModel.splitWords("x"))
    }

    // ---- Review round: chat availability, shell files, describe, fingerprint ----

    @Test
    fun theAvailabilityFlagDoesNoWorkWhereItIsRead() {
        var computed = 0
        val readThread = Thread.currentThread()
        var computedOn: Thread? = null
        val flag = BackgroundFlag { computed++; computedOn = Thread.currentThread(); true }
        assertEquals(false, flag.value, "unknown until refreshed")
        assertEquals(0, computed, "reading it does no file work")
        val t = thread { flag.refresh() }
        t.join()
        assertEquals(1, computed)
        assertTrue(computedOn !== readThread)
        assertEquals(true, flag.value)
        assertEquals(1, computed, "reading again still does nothing")
    }

    @Test
    fun withSmartRepliesOffTheChatPackIsNotCheckedAtStart() {
        val app = PebbleApp(DatabaseFactory.inMemory())
        try {
            Thread.sleep(500) // a start-up check would have run on the io thread by now
            assertEquals(0, app.chatInstalled.refreshes, "no hashing of a chat pack for a feature that is off")
            assertEquals(false, app.smartRepliesOn())
            app.refreshChatInstalled() // the Memory page opens
            val end = System.currentTimeMillis() + 5_000
            while (app.chatInstalled.refreshes == 0 && System.currentTimeMillis() < end) Thread.sleep(20)
            assertEquals(1, app.chatInstalled.refreshes)
        } finally {
            app.shutdown()
        }
    }

    @Test
    fun explorerFilesDoNotDisableAPackButOtherFilesDo() {
        val h = PackHelper()
        h.stage(h.pack())
        ModelPack.applyStaged(h.models)
        val dir = h.models.resolve("intent")
        dir.resolve("Desktop.INI").writeText("[.ShellClassInfo]")
        dir.resolve("tokenizer/thumbs.db").writeText("x")
        assertIs<Result.Ok>(ModelPack.verifyInstalled(dir, "intent", keys = h.keys))
        dir.resolve("desktop.ini.dll").writeText("MZ")
        assertIs<Result.Invalid>(ModelPack.verifyInstalled(dir, "intent", keys = h.keys))
    }

    @Test
    fun aThrowingDescribeLeavesNoLoadedModelBehind() = runTest {
        val m = model(describe = { error("describe failed") })
        m.warmUp()
        runCurrent()
        assertEquals(ModelStatus.LoadFailed("describe failed"), m.status.value)
        assertNull(m.getOrNull())
        assertTrue(made.single().closed)
    }

    @Test
    fun theChatModelFileMatchesWithoutCase() {
        val h = PackHelper()
        val src = Files.createTempDirectory(h.root, "chat2")
        src.resolve("llama-server.exe").writeText("exe")
        src.resolve("Model.GGUF").writeText("model")
        ModelPack.sign(src, "chat", "v1", null, "test-key", h.pair.private)
        assertEquals(src.resolve("Model.GGUF"), LocalChat.filesIn(src)?.model)
    }

    @Test
    fun theFingerprintSeesTheManifestNextToTheModelFolder() {
        val root = Files.createTempDirectory("fp")
        val dir = Files.createDirectories(root.resolve("intent-v3"))
        dir.resolve("m.onnx").writeText("w")
        root.resolve("manifest.json").writeText("{}")
        val before = LazyModel.filesFingerprint(dir)
        Files.setLastModifiedTime(root.resolve("manifest.json"), java.nio.file.attribute.FileTime.fromMillis(1_000))
        assertTrue(before != LazyModel.filesFingerprint(dir))
    }

    /** A signed fake pack under a temp folder, for the pack tests. */
    private class PackHelper {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val keys = mapOf("test-key" to Base64.getEncoder().encodeToString(pair.public.encoded))
        val root: Path = Files.createTempDirectory("hardening")
        val models: Path = root.resolve("data/models").also { Files.createDirectories(it) }

        fun pack(): Path {
            val dir = Files.createTempDirectory(root, "src")
            dir.resolve("intent.int8.onnx").writeText("weights")
            Files.createDirectories(dir.resolve("tokenizer"))
            dir.resolve("tokenizer/tokenizer.json").writeText("{}")
            ModelPack.sign(dir, "intent", "v9", null, "test-key", pair.private)
            return root.resolve("${dir.fileName}.zip").also { ModelPack.zip(dir, it) }
        }

        fun stage(zip: Path) = ModelPack.stageInstall(zip, models, null, keys)
    }
}
