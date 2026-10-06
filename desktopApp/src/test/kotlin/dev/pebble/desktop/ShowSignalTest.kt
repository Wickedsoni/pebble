package dev.pebble.desktop

import dev.pebble.desktop.platform.GlobalHotkey
import dev.pebble.desktop.platform.ShowSignalWaiter
import dev.pebble.desktop.platform.SignalWait
import dev.pebble.desktop.platform.SingleInstance
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A second launch signals the first Pebble without a socket (R3-4); a taken hotkey is reported (R3-11). */
class ShowSignalTest {
    private val windows = System.getProperty("os.name").startsWith("Windows")

    @Test
    fun theWaiterCallsBackForEachSignalAndStops() {
        val queue = LinkedBlockingQueue<SignalWait>()
        val shown = AtomicInteger()
        val ended = CountDownLatch(1)
        val w = ShowSignalWaiter(
            await = { ms -> queue.poll(ms.toLong(), TimeUnit.MILLISECONDS) ?: SignalWait.TIMEOUT },
            onShow = { shown.incrementAndGet() },
            pollMillis = 20,
            onEnd = { ended.countDown() },
        )
        w.start()
        queue.add(SignalWait.SIGNALED)
        queue.add(SignalWait.SIGNALED)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (shown.get() < 2 && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(2, shown.get())
        w.stop()
        assertTrue(ended.await(1, TimeUnit.SECONDS), "the thread ended")
        queue.add(SignalWait.SIGNALED)
        Thread.sleep(60)
        assertEquals(2, shown.get(), "no callback after stop")
    }

    @Test
    fun aFailedWaitEndsTheThreadAndACrashingCallbackDoesNot() {
        val ended = CountDownLatch(1)
        val results = ArrayDeque(listOf(SignalWait.SIGNALED, SignalWait.FAILED))
        val shown = AtomicInteger()
        ShowSignalWaiter(
            await = { results.removeFirst() },
            onShow = {
                shown.incrementAndGet()
                error("callback broke")
            },
            pollMillis = 10,
            onEnd = { ended.countDown() },
        ).start()
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertEquals(1, shown.get())
    }

    @Test
    fun theEventNameDependsOnTheDataFolder() {
        val a = SingleInstance.eventName(Files.createTempDirectory("pebble-a").toFile())
        val b = SingleInstance.eventName(Files.createTempDirectory("pebble-b").toFile())
        assertTrue(a.startsWith("Local\\PebbleShow-"))
        assertNotEquals(a, b)
    }

    @Test
    fun aSecondLaunchWakesTheFirstOne() {
        if (!windows) return
        val dir = Files.createTempDirectory("pebble-single").toFile()
        assertFalse(SingleInstance.signalFirst(dir), "nobody runs yet, so nobody to tell")
        assertTrue(SingleInstance.acquire(dir))
        assertFalse(SingleInstance.acquire(dir), "the second launch does not get the folder")
        // Signal before the first one listens: the event keeps it (a start-up race).
        assertTrue(SingleInstance.signalFirst(dir))
        val shown = CountDownLatch(1)
        val waiter = SingleInstance.listen { shown.countDown() }!!
        assertTrue(shown.await(5, TimeUnit.SECONDS), "the first instance opens its window")
        assertEquals(null, SingleInstance.listen {}, "the waiter owns the handle: a second listen gets none")
        waiter.stop()
    }

    @Test
    fun aTakenHotkeyIsReported() {
        if (!windows) return
        val first = LinkedBlockingQueue<Boolean>()
        val second = LinkedBlockingQueue<Boolean>()
        // Ctrl+Alt+Shift+F13: no real app uses it.
        val mods = GlobalHotkey.MOD_CONTROL or GlobalHotkey.MOD_ALT or GlobalHotkey.MOD_SHIFT
        GlobalHotkey(mods, 0x7C, onRegistered = { first.add(it) }) {}.start()
        // A session with no interactive desktop (some CI runners) cannot register hotkeys at all: skip there.
        assumeTrue("this session cannot register hotkeys", first.poll(5, TimeUnit.SECONDS) == true)
        GlobalHotkey(mods, 0x7C, onRegistered = { second.add(it) }) {}.start()
        assertEquals(false, second.poll(5, TimeUnit.SECONDS), "the same keys cannot be taken twice")
    }
}
