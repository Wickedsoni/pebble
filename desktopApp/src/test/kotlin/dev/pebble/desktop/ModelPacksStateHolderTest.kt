package dev.pebble.desktop

import dev.pebble.desktop.app.pages.ModelPacksEvent
import dev.pebble.desktop.app.pages.ModelPacksPort
import dev.pebble.desktop.app.pages.ModelPacksStateHolder
import dev.pebble.desktop.app.pages.ModelPacksUiState.Row
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The "Model packs" card: first state, an install that is staged, one that is refused, and a removal. */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelPacksStateHolderTest {
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun stop() = scopes.forEach { it.cancel() }

    private class FakePort : ModelPacksPort {
        var intent = "Built in"
        var removed = mutableListOf<String>()

        override suspend fun models() = listOf(Row("intent", "Command model", intent, canRemove = intent.startsWith("Pack")))

        override suspend fun install(zip: Path): String? = if (zip.fileName.toString() == "good.zip") {
            intent = "Installs when Pebble starts again: v4"
            null
        } else {
            "signature does not match"
        }

        override suspend fun remove(name: String) {
            removed += name
            intent = "Removed when Pebble starts again"
        }
    }

    private fun TestScope.holder(port: ModelPacksPort) =
        ModelPacksStateHolder(port, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { scopes += it })

    @Test
    fun showsEachModelAndWhereItComesFrom() = runTest {
        val h = holder(FakePort())
        advanceUntilIdle()
        assertEquals(listOf(Row("intent", "Command model", "Built in", false)), h.state.value.rows)
        assertNull(h.state.value.message)
    }

    @Test
    fun aGoodPackIsStagedAndABadOneIsExplained() = runTest {
        val h = holder(FakePort())
        h.onEvent(ModelPacksEvent.Install(Path.of("bad.zip")))
        assertTrue(h.state.value.busy)
        advanceUntilIdle()
        assertEquals("Not installed: signature does not match", h.state.value.message)
        assertEquals("Built in", h.state.value.rows.single().status)
        h.onEvent(ModelPacksEvent.Install(Path.of("good.zip")))
        advanceUntilIdle()
        assertEquals("Installs when Pebble starts again: v4", h.state.value.rows.single().status)
        assertTrue(h.state.value.message!!.startsWith("Installed."))
        assertTrue(!h.state.value.busy)
    }

    @Test
    fun removeStagesTheRemoval() = runTest {
        val port = FakePort().apply { intent = "Pack v4 (signed)" }
        val h = holder(port)
        advanceUntilIdle()
        assertTrue(h.state.value.rows.single().canRemove)
        h.onEvent(ModelPacksEvent.Remove("intent"))
        advanceUntilIdle()
        assertEquals(listOf("intent"), port.removed)
        assertEquals("Removed when Pebble starts again", h.state.value.rows.single().status)
    }
}
