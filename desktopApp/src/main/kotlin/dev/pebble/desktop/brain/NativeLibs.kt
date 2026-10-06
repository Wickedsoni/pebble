package dev.pebble.desktop.brain

import dev.pebble.desktop.core.Logger
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Where ONNX Runtime and sherpa-onnx find their DLLs (QA R4-2).
 *
 * By default each library copies its DLLs from its jar into a new `%TEMP%` folder at each run. Windows cannot
 * delete a loaded DLL, so those folders stay behind (about 30 MB each). The installed app ships the DLLs
 * unpacked in `<resources>/natives/{ort,sherpa}` and these two documented system properties point the libraries there:
 * - ONNX Runtime 1.30 (`OnnxRuntime.java`): `onnxruntime.native.path` = a folder with `onnxruntime`,
 *   `onnxruntime4j_jni` and `onnxruntime_providers_shared`.
 * - sherpa-onnx v1.13.5 (`LibraryUtils.class`): `sherpa_onnx.native.path` = a folder with `sherpa-onnx-jni`
 *   and `onnxruntime` (it loads that `onnxruntime` first, if the file exists).
 *
 * Without the folder (a run from the IDE before staging) the properties stay unset: the libraries extract as before.
 */
object NativeLibs {
    const val ORT_PROPERTY = "onnxruntime.native.path"
    const val SHERPA_PROPERTY = "sherpa_onnx.native.path"
    private const val RESOURCES_PROPERTY = "compose.application.resources.dir"

    /** Suffix of a folder that an earlier run renamed and could not finish deleting. */
    private const val DELETING = ".deleting"

    /** Folder names that ONNX Runtime and sherpa-onnx make with `Files.createTempDirectory(prefix)`, or [DELETING] ones. */
    private val STALE = Regex("(onnxruntime-java|sherpa-onnx-java)\\d+(\\.deleting)?")

    /** Points ONNX Runtime at the shipped DLLs. Call before the first use of `OrtEnvironment`. */
    fun configureOrt(resources: Path? = resourcesDir()) = point(ORT_PROPERTY, resources, "ort", "onnxruntime.dll")

    /** Points sherpa-onnx at the shipped DLLs. Call before `LibraryUtils.load()`, after the ONNX Runtime env exists. */
    fun configureSherpa(resources: Path? = resourcesDir()) = point(SHERPA_PROPERTY, resources, "sherpa", "sherpa-onnx-jni.dll")

    private fun resourcesDir(): Path? = System.getProperty(RESOURCES_PROPERTY)?.let { Path.of(it) }

    /** A property the caller set (for example `-D` on the command line) wins. */
    private fun point(property: String, resources: Path?, folder: String, marker: String) {
        if (System.getProperty(property) != null || resources == null) return
        val dir = resources.resolve("natives").resolve(folder)
        if (Files.exists(dir.resolve(marker))) System.setProperty(property, dir.toAbsolutePath().toString())
    }

    /**
     * Deletes the `onnxruntime-java*` and `sherpa-onnx-java*` folders that earlier runs left in [tempDir].
     * Nothing else is touched. A folder younger than [minAge] milliseconds is skipped (a run that starts now may
     * still copy into it). A file that cannot be deleted (a DLL that a running process has loaded) stays, and so
     * does its folder. Blocking: run it off the UI thread. Returns the number of folders removed.
     */
    fun cleanStaleTempFolders(
        tempDir: Path = Path.of(System.getProperty("java.io.tmpdir")),
        log: Logger = Logger.None,
        now: Long = System.currentTimeMillis(),
        minAge: Long = 2 * 60_000L,
    ): Int {
        val candidates = runCatching {
            Files.newDirectoryStream(tempDir).use { s ->
                s.filter { STALE.matches(it.fileName.toString()) && isPlainDirectory(it) }
            }
        }.getOrElse {
            log.warn("NativeLibs", "cannot list $tempDir", it)
            return 0
        }
        var removed = 0
        for (dir in candidates) {
            val modified = runCatching { Files.getLastModifiedTime(dir).toMillis() }.getOrDefault(now)
            if (now - modified < minAge) continue
            if (removeIfUnlocked(dir)) removed++
        }
        if (removed > 0) log.info("NativeLibs", "removed $removed old native-library folders from $tempDir")
        return removed
    }

    /** A real folder. A symbolic link or an NTFS junction (`isOther`) is not: deleting "through" it would leave %TEMP%. */
    private fun isPlainDirectory(p: Path): Boolean = try {
        val a = Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        a.isDirectory && !a.isOther && !a.isSymbolicLink
    } catch (_: Exception) {
        false
    }

    /**
     * Removes the folder only if no file in it is in use. Two probes run before anything is deleted: every file is
     * opened for write (Windows refuses that for a loaded DLL), and the folder is renamed (Windows refuses that
     * while a file inside is open). If a probe fails, nothing is deleted. Then the regular files go, then the folder.
     * These folders are flat (DLLs only): an entry that is not a regular file keeps the folder, and nothing is entered.
     */
    private fun removeIfUnlocked(dir: Path): Boolean {
        val leftover = dir.fileName.toString().endsWith(DELETING)
        val doomed = if (leftover) dir else dir.resolveSibling(dir.fileName.toString() + DELETING)
        var moved = false
        return try {
            val files = Files.newDirectoryStream(dir).use { it.toList() }
            if (files.any { !isRegularFile(it) }) return false
            for (f in files) Files.newByteChannel(f, StandardOpenOption.WRITE).close()
            if (!leftover) {
                if (Files.exists(doomed, LinkOption.NOFOLLOW_LINKS)) return false
                Files.move(dir, doomed)
                moved = true
            }
            for (f in files) Files.delete(doomed.resolve(f.fileName))
            Files.delete(doomed)
            true
        } catch (_: Exception) {
            // The name must match again, or the next start-up would never find this folder.
            if (moved) runCatching { Files.move(doomed, dir) }
            false
        }
    }

    private fun isRegularFile(p: Path): Boolean = Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
}
