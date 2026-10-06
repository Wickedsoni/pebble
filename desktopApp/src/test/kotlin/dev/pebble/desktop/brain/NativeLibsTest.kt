package dev.pebble.desktop.brain

import org.junit.Assume.assumeTrue
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeLibsTest {
    private fun fakeTemp(): Path = Files.createTempDirectory("pebble-nativelibs-test")

    private fun folder(temp: Path, name: String, vararg files: String): Path =
        temp.resolve(name).also { d ->
            d.createDirectories()
            files.forEach { d.resolve(it).writeText("x") }
        }

    @Test
    fun `removes only matching unlocked folders`() {
        val temp = fakeTemp()
        val ort = folder(temp, "onnxruntime-java1234567890", "onnxruntime.dll", "onnxruntime4j_jni.dll")
        val sherpa = folder(temp, "sherpa-onnx-java987", "sherpa-onnx-jni.dll")
        val emptyOrt = folder(temp, "onnxruntime-java42")
        val other = folder(temp, "onnxruntime-javaX", "a.dll")
        val similar = folder(temp, "my-onnxruntime-java5", "a.dll")
        val unrelated = folder(temp, "someone-else", "a.dll")
        val aFile = temp.resolve("onnxruntime-java77").also { it.writeText("not a folder") }

        val removed = NativeLibs.cleanStaleTempFolders(temp, now = System.currentTimeMillis() + 10 * 60_000L)

        assertEquals(3, removed)
        assertFalse(ort.exists())
        assertFalse(sherpa.exists())
        assertFalse(emptyOrt.exists())
        assertTrue(other.exists())
        assertTrue(similar.exists())
        assertTrue(unrelated.exists())
        assertTrue(aFile.exists())
    }

    @Test
    fun `keeps a folder that is too new`() {
        val temp = fakeTemp()
        val fresh = folder(temp, "onnxruntime-java1", "onnxruntime.dll")
        assertEquals(0, NativeLibs.cleanStaleTempFolders(temp))
        assertTrue(fresh.exists())
    }

    @Test
    fun `keeps a folder with a locked file`() {
        val temp = fakeTemp()
        val locked = folder(temp, "sherpa-onnx-java5", "sherpa-onnx-jni.dll", "onnxruntime.dll")
        val free = folder(temp, "onnxruntime-java6", "onnxruntime.dll")
        // An open handle without share-delete is how Windows keeps a loaded DLL. On another OS the delete works: skip.
        val windows = System.getProperty("os.name").startsWith("Windows")
        val removed = RandomAccessFile(locked.resolve("onnxruntime.dll").toFile(), "r").use {
            NativeLibs.cleanStaleTempFolders(temp, now = System.currentTimeMillis() + 10 * 60_000L)
        }
        assertFalse(free.exists())
        if (windows) {
            assertEquals(1, removed)
            assertTrue(locked.exists())
            assertTrue(locked.resolve("onnxruntime.dll").exists())
        }
    }

    @Test
    fun `a missing temp folder is not an error`() {
        assertEquals(0, NativeLibs.cleanStaleTempFolders(fakeTemp().resolve("nope")))
    }

    @Test
    fun `configure points the libraries at the shipped folders and respects an existing property`() {
        val res = fakeTemp()
        val ort = res.resolve("natives/ort").also { it.createDirectories() }
        ort.resolve("onnxruntime.dll").writeText("x")
        val sherpa = res.resolve("natives/sherpa").also { it.createDirectories() }
        sherpa.resolve("sherpa-onnx-jni.dll").writeText("x")
        val oldOrt = System.getProperty(NativeLibs.ORT_PROPERTY)
        val oldSherpa = System.getProperty(NativeLibs.SHERPA_PROPERTY)
        try {
            System.clearProperty(NativeLibs.ORT_PROPERTY)
            System.clearProperty(NativeLibs.SHERPA_PROPERTY)
            NativeLibs.configureOrt(null)
            assertNull(System.getProperty(NativeLibs.ORT_PROPERTY))
            NativeLibs.configureOrt(res)
            NativeLibs.configureSherpa(res)
            assertEquals(ort.toAbsolutePath().toString(), System.getProperty(NativeLibs.ORT_PROPERTY))
            assertEquals(sherpa.toAbsolutePath().toString(), System.getProperty(NativeLibs.SHERPA_PROPERTY))
            System.setProperty(NativeLibs.ORT_PROPERTY, "custom")
            NativeLibs.configureOrt(res)
            assertEquals("custom", System.getProperty(NativeLibs.ORT_PROPERTY))
            // No DLL in the folder: leave the property alone, so the library falls back to its own jar.
            System.clearProperty(NativeLibs.ORT_PROPERTY)
            NativeLibs.configureOrt(fakeTemp())
            assertNull(System.getProperty(NativeLibs.ORT_PROPERTY))
        } finally {
            oldOrt?.let { System.setProperty(NativeLibs.ORT_PROPERTY, it) } ?: System.clearProperty(NativeLibs.ORT_PROPERTY)
            oldSherpa?.let { System.setProperty(NativeLibs.SHERPA_PROPERTY, it) } ?: System.clearProperty(NativeLibs.SHERPA_PROPERTY)
        }
    }

    @Test
    fun `a junction with a matching name is never followed`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val temp = fakeTemp()
        val target = folder(fakeTemp(), "precious", "keep.dll")
        val link = temp.resolve("onnxruntime-java555")
        val p = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        assertEquals(0, p.waitFor())

        try {
            val removed = NativeLibs.cleanStaleTempFolders(temp, now = System.currentTimeMillis() + 10 * 60_000L)
            assertEquals(0, removed)
            assertTrue(target.resolve("keep.dll").exists())
        } finally {
            // rmdir on a junction removes only the link.
            ProcessBuilder("cmd", "/c", "rmdir", link.toString()).redirectErrorStream(true).start()
                .also { it.inputStream.readBytes() }.waitFor()
        }
    }

    @Test
    fun `an old deleting folder is removed and a blocked one stays`() {
        val temp = fakeTemp()
        val leftover = folder(temp, "onnxruntime-java9.deleting", "a.dll")
        val dir = folder(temp, "sherpa-onnx-java3", "b.dll")
        val blocked = folder(temp, "onnxruntime-java4", "c.dll")
        val blocker = folder(temp, "onnxruntime-java4.deleting")
        blocker.resolve("sub").createDirectories() // not a regular file: this folder stays, so it blocks the move
        val other = folder(temp, "onnxruntime-java5.deleting.x", "d.dll")

        NativeLibs.cleanStaleTempFolders(temp, now = System.currentTimeMillis() + 10 * 60_000L)

        assertFalse(leftover.exists())
        assertFalse(dir.exists())
        assertTrue(blocked.exists())
        assertTrue(blocker.exists())
        assertTrue(other.exists())
    }

    @Test
    fun `a folder with one locked file loses none of its files`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val temp = fakeTemp()
        val dir = folder(temp, "onnxruntime-java8", "a.dll", "b.dll", "c.dll")
        RandomAccessFile(dir.resolve("c.dll").toFile(), "r").use {
            assertEquals(0, NativeLibs.cleanStaleTempFolders(temp, now = System.currentTimeMillis() + 10 * 60_000L))
        }
        assertTrue(dir.resolve("a.dll").exists())
        assertTrue(dir.resolve("b.dll").exists())
    }
}
