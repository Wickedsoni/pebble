package dev.pebble.desktop

import dev.pebble.desktop.brain.ModelChecksums
import dev.pebble.desktop.brain.VerifiedModelCache
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A model file is hashed once; again only when its size or time changes. */
class VerifiedModelCacheTest {
    private val dir = Files.createTempDirectory("pebble-cache")
    private val model = dir.resolve("model.onnx").also { Files.writeString(it, "weights v1") }
    private val sha = ModelChecksums.sha256(model)
    private val cacheFile = dir.resolve("models-verified.json")
    private var hashes = 0

    private fun cache() = VerifiedModelCache(cacheFile) { hashes++; ModelChecksums.sha256(it) }

    @Test
    fun hashesOnceThenTrustsAnUnchangedFile() {
        val c = cache()
        assertTrue(c.matches(model, sha))
        assertTrue(c.matches(model, sha))
        assertEquals(1, hashes)
    }

    @Test
    fun survivesARestart() {
        cache().matches(model, sha)
        assertTrue(cache().matches(model, sha), "a new instance reads models-verified.json")
        assertEquals(1, hashes)
    }

    @Test
    fun aTouchedFileIsHashedAgain() {
        val c = cache()
        c.matches(model, sha)
        Files.setLastModifiedTime(model, FileTime.fromMillis(Files.getLastModifiedTime(model).toMillis() + 5_000))
        assertTrue(c.matches(model, sha))
        assertEquals(2, hashes)
    }

    @Test
    fun aChangedFileFailsAndIsNotRemembered() {
        val c = cache()
        c.matches(model, sha)
        Files.writeString(model, "weights v2 - tampered")
        assertFalse(c.matches(model, sha))
        assertFalse(c.matches(model, sha))
        assertEquals(3, hashes, "a failed file is checked every time")
    }

    @Test
    fun aWrongHashIsNeverCached() {
        val c = cache()
        assertFalse(c.matches(model, "0".repeat(64)))
        assertTrue(c.matches(model, sha))
        assertEquals(2, hashes)
    }

    @Test
    fun aMissingFileFails() {
        assertFalse(cache().matches(Path.of(dir.toString(), "gone.onnx"), sha))
        assertEquals(0, hashes)
    }

    @Test
    fun aBrokenCacheFileStartsOver() {
        Files.writeString(cacheFile, "{ not json")
        assertTrue(cache().matches(model, sha))
        assertEquals(1, hashes)
    }
}
