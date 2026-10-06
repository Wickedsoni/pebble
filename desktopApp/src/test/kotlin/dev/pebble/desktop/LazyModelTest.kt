package dev.pebble.desktop

import dev.pebble.desktop.brain.LazyModel
import dev.pebble.desktop.brain.ModelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The shared model lifecycle, on virtual time: load once, never block, free when idle, say why it failed. */
@OptIn(ExperimentalCoroutinesApi::class)
class LazyModelTest {
    private class Fake : AutoCloseable {
        var closed = false

        override fun close() {
            closed = true
        }
    }

    private var loads = 0
    private val made = mutableListOf<Fake>()

    private fun TestScope.lazyModel(
        scope: CoroutineScope = backgroundScope,
        dir: Path? = Path.of("models/fake"),
        verify: (Path) -> Boolean = { true },
        load: (Path) -> Fake = { loads++; Fake().also { made += it } },
    ) = LazyModel(
        name = "fake",
        scope = scope,
        idleMillis = 10 * 60_000L,
        locate = { dir },
        verify = verify,
        load = load,
        clock = { testScheduler.currentTime },
        io = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun getOrNullNeverWaitsAndWarmUpLoadsOnce() = runTest {
        val m = lazyModel()
        assertNull(m.getOrNull(), "nothing until loaded, and no blocking")
        repeat(5) { m.warmUp() }
        runCurrent()
        assertEquals(1, loads)
        assertIs<ModelStatus.Ready>(m.status.value)
        assertSame(made.single(), m.getOrNull())
    }

    @Test
    fun concurrentCallersShareOneLoad() = runTest {
        val m = lazyModel()
        val got = (1..20).map { async { m.await() } }.awaitAll()
        assertEquals(1, loads)
        assertTrue(got.all { it === made.single() })
    }

    @Test
    fun idleModelIsFreedAndLoadsAgainWhenNeeded() = runTest {
        val m = lazyModel()
        m.await()
        advanceTimeBy(9 * 60_000L)
        m.getOrNull() // a use 9 minutes in: the 10 idle minutes start again
        advanceTimeBy(9 * 60_000L)
        runCurrent()
        assertTrue(!made.single().closed, "used recently, still loaded")
        advanceTimeBy(3 * 60_000L)
        runCurrent()
        assertTrue(made.single().closed)
        assertEquals(ModelStatus.Unloaded, m.status.value)
        assertNull(m.getOrNull())
        m.await()
        assertEquals(2, loads)
    }

    @Test
    fun aChecksumMismatchNeverLoads() = runTest {
        val m = lazyModel(verify = { false })
        assertNull(m.await())
        assertEquals(0, loads)
        assertEquals(ModelStatus.ChecksumMismatch, m.status.value)
    }

    @Test
    fun noModelFolderSaysSo() = runTest {
        val m = lazyModel(dir = null)
        m.warmUp()
        runCurrent() // the folder is looked up on the background dispatcher now, not on the caller's thread
        assertEquals(ModelStatus.NotFound, m.status.value)
        assertNull(m.await())
    }

    @Test
    fun aFailedLoadKeepsTheReason() = runTest {
        val m = lazyModel(load = { error("onnxruntime.dll missing") })
        assertNull(m.await())
        assertEquals(ModelStatus.LoadFailed("onnxruntime.dll missing"), m.status.value)
    }
}
