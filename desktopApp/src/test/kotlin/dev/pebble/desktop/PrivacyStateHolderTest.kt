package dev.pebble.desktop

import dev.pebble.core.settings.SettingsRepository.Keys
import dev.pebble.desktop.app.pages.PrivacyEvent
import dev.pebble.desktop.app.pages.PrivacyPort
import dev.pebble.desktop.app.pages.PrivacyStateHolder
import dev.pebble.desktop.core.AppEnv
import dev.pebble.desktop.core.DispatcherProvider
import dev.pebble.desktop.core.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The "Privacy" card: the switches, what each one does, and that a failing action does not crash. */
@OptIn(ExperimentalCoroutinesApi::class)
class PrivacyStateHolderTest {
    private class TestDispatchers(d: CoroutineDispatcher) : DispatcherProvider {
        override val main = d
        override val default = d
        override val io = d
    }

    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun stop() = scopes.forEach { it.cancel() }

    private class FakePort : PrivacyPort {
        val flags = mutableMapOf<String, Boolean>()
        var clips = 0
        var watchCleared = 0
        var chatClosed = 0
        var failOnSet = false

        override fun flag(key: String, default: Boolean) = flags[key] ?: default

        override fun setFlag(key: String, on: Boolean) {
            if (failOnSet) error("database is locked")
            flags[key] = on
        }

        override fun voiceClipCount() = clips

        override fun deleteVoiceClips() {
            clips = 0
        }

        override suspend fun clearWatchHistory() {
            watchCleared++
        }

        override fun closeChat() {
            chatClosed++
        }

        override fun chatInstalled() = true
    }

    private class Lines : Logger {
        val warnings = mutableListOf<String>()

        override fun info(tag: String, msg: String) = Unit

        override fun warn(tag: String, msg: String, t: Throwable?) {
            warnings += msg
        }
    }

    private fun TestScope.holder(port: PrivacyPort, log: Logger = Logger.None): PrivacyStateHolder {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val env = AppEnv(Clock.systemUTC(), { ZoneId.of("UTC") }, TestDispatchers(dispatcher))
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also { scopes += it }
        return PrivacyStateHolder(port, env, scope, log)
    }

    @Test
    fun theCardShowsNoSwitchesUntilTheSettingsAreRead() = runTest {
        val h = holder(FakePort())
        assertFalse(h.state.value.loaded)
        advanceUntilIdle()
        assertTrue(h.state.value.loaded)
    }

    @Test
    fun theFirstStateHasTheDefaultsAndTheCountOfVoiceClips() = runTest {
        val port = FakePort().apply { clips = 3 }
        val h = holder(port)
        advanceUntilIdle()
        val s = h.state.value
        assertFalse(s.mediaOn)
        assertTrue(s.micOn)
        assertFalse(s.smartOn)
        assertFalse(s.keepVoice)
        assertEquals(3, s.voiceClips)
        assertTrue(s.chatInstalled)
    }

    @Test
    fun aSwitchChangesTheStateAndIsSaved() = runTest {
        val port = FakePort()
        val h = holder(port)
        advanceUntilIdle()
        h.onEvent(PrivacyEvent.SetMedia(true))
        h.onEvent(PrivacyEvent.SetMic(false))
        h.onEvent(PrivacyEvent.SetKeepVoice(true))
        advanceUntilIdle()
        assertTrue(h.state.value.mediaOn)
        assertFalse(h.state.value.micOn)
        assertTrue(h.state.value.keepVoice)
        assertEquals(true, port.flags[Keys.MEDIA_TRACKING])
        assertEquals(false, port.flags[Keys.MICROPHONE_ENABLED])
        assertEquals(true, port.flags[Keys.KEEP_VOICE_CORRECTIONS])
    }

    @Test
    fun turningSmartRepliesOffStopsTheChatHelper() = runTest {
        val port = FakePort()
        val h = holder(port)
        advanceUntilIdle()
        h.onEvent(PrivacyEvent.SetSmartReplies(true))
        advanceUntilIdle()
        assertEquals(0, port.chatClosed)
        h.onEvent(PrivacyEvent.SetSmartReplies(false))
        advanceUntilIdle()
        assertEquals(1, port.chatClosed)
        assertEquals(false, port.flags[Keys.SMART_REPLIES])
    }

    @Test
    fun deletingTheVoiceClipsEmptiesTheCount() = runTest {
        val port = FakePort().apply { clips = 4 }
        val h = holder(port)
        advanceUntilIdle()
        h.onEvent(PrivacyEvent.DeleteVoiceClips)
        advanceUntilIdle()
        assertEquals(0, h.state.value.voiceClips)
        assertEquals(0, port.clips)
    }

    @Test
    fun clearWatchHistoryCallsThePort() = runTest {
        val port = FakePort()
        val h = holder(port)
        advanceUntilIdle()
        h.onEvent(PrivacyEvent.ClearWatchHistory)
        advanceUntilIdle()
        assertEquals(1, port.watchCleared)
    }

    @Test
    fun aFailingWriteIsLoggedAndDoesNotCrash() = runTest {
        val port = FakePort()
        val log = Lines()
        val h = holder(port, log)
        advanceUntilIdle()
        port.failOnSet = true
        h.onEvent(PrivacyEvent.SetMedia(true))
        advanceUntilIdle()
        assertEquals(1, log.warnings.size)
    }
}
