package dev.pebble.desktop.brain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * A signed model pack (WP D2, ADR 0011): a zip with the model files, `pack.json` and `pack.sig`.
 *  - `pack.json` names the model ("intent", "asr"), its version, the lowest app version it needs, the key
 *    that signed it, and each file with its size and SHA-256
 *  - `pack.sig` is the Ed25519 signature of the exact bytes of `pack.json` (Base64)
 * The signature covers `pack.json`, and `pack.json` covers each file by its hash.
 *
 * Installed packs live in `%APPDATA%\Pebble\models\<name>`, with `pack.json` and `pack.sig` kept, and are
 * checked again before each load ([verifyInstalled]). An install or a removal is staged and done the next
 * time Pebble starts ([applyStaged]), before any model is loaded: a loaded model's files can be locked.
 */
object ModelPack {
    const val FORMAT = 1
    const val MANIFEST = "pack.json"
    const val SIGNATURE = "pack.sig"

    /** Models a pack can install: the command model, the speech models, and the optional chat model (WP C5). */
    val NAMES = setOf("intent", "asr", "chat")

    /** Larger packs are refused before anything is written (the speech pack is ~300 MB). */
    const val MAX_TOTAL_BYTES = 2L shl 30

    /** A chat pack holds a chat model: up to 24 GB (sarvam-30b Q4_K_M is 19.6 GB). */
    const val MAX_CHAT_BYTES = 24L shl 30
    private const val MAX_MANIFEST_BYTES = 1 shl 20

    data class Entry(val path: String, val size: Long, val sha256: String)

    data class Manifest(val name: String, val version: String, val minApp: String?, val keyId: String, val files: List<Entry>)

    sealed interface Result {
        data class Ok(val manifest: Manifest) : Result

        data class Invalid(val reason: String) : Result
    }

    /** The public keys whose packs Pebble trusts, by key id (X.509 DER, Base64). More than one allows a key change. */
    val TRUSTED_KEYS: Map<String, String> = mapOf(
        "pebble-2026a" to "MCowBQYDK2VwAyEAcoivX8iU+v133Q0r/aop/ejlcQVnN3r3qx62Ysovsh4=",
    )

    // ---- Reading and checking ----

    /** Checks [manifestBytes] against [signature] and parses it. */
    fun readSigned(manifestBytes: ByteArray, signature: String, keys: Map<String, String> = TRUSTED_KEYS): Result {
        val m = runCatching { parse(manifestBytes) }.getOrElse { return Result.Invalid("pack.json is not valid: ${it.message}") }
        val key = keys[m.keyId] ?: return Result.Invalid("unknown signing key ${m.keyId}")
        val ok = runCatching {
            Signature.getInstance("Ed25519").run {
                initVerify(publicKey(key))
                update(manifestBytes)
                verify(Base64.getDecoder().decode(signature.trim()))
            }
        }.getOrDefault(false)
        return if (ok) Result.Ok(m) else Result.Invalid("signature does not match")
    }

    /**
     * An installed pack in [dir] is valid: signed by a trusted key, for model [name], and every file has its hash.
     * With a [cache], files that passed before and did not change are not hashed again.
     */
    fun verifyInstalled(dir: Path, name: String, cache: VerifiedModelCache? = null, keys: Map<String, String> = TRUSTED_KEYS): Result {
        val mf = dir.resolve(MANIFEST)
        val sig = dir.resolve(SIGNATURE)
        if (!mf.exists() || !sig.exists()) return Result.Invalid("not a signed pack (no pack.json / pack.sig)")
        val r = readSigned(Files.readAllBytes(mf), Files.readString(sig), keys)
        if (r !is Result.Ok) return r
        if (r.manifest.name != name) return Result.Invalid("pack is for ${r.manifest.name}, not $name")
        for (e in r.manifest.files) {
            val f = dir.resolve(e.path)
            val ok = if (cache !=
                null
            ) {
                cache.matches(f, e.sha256)
            } else {
                f.exists() && Files.size(f) == e.size && ModelChecksums.sha256(f) == e.sha256
            }
            if (!ok) return Result.Invalid("${e.path} does not match pack.json")
        }
        return r
    }

    /** The manifest of the pack installed in [dir], unchecked (for display); null if there is none. */
    fun installedManifest(
        dir: Path,
    ): Manifest? = dir.resolve(MANIFEST).takeIf { it.exists() }?.let { runCatching { parse(Files.readAllBytes(it)) }.getOrNull() }

    // ---- Installing and removing (staged) ----

    /**
     * Checks the pack [zip] and unpacks it to `<modelsDir>/<name>.staged`. Pebble uses it from the next start.
     * Nothing outside that folder is written. [appVersion] null (a development build) skips the version check.
     */
    fun stageInstall(
        zip: Path,
        modelsDir: Path,
        appVersion: String?,
        keys: Map<String, String> = TRUSTED_KEYS,
    ): Result = ZipFile(zip.toFile()).use { z ->
        val mfEntry = z.getEntry(MANIFEST) ?: return Result.Invalid("no pack.json in the file")
        val sigEntry = z.getEntry(SIGNATURE) ?: return Result.Invalid("no pack.sig in the file")
        val mfBytes = readLimited(z, mfEntry, MAX_MANIFEST_BYTES.toLong()) ?: return Result.Invalid("pack.json is too large")
        val sig = String(readLimited(z, sigEntry, 1024) ?: return Result.Invalid("pack.sig is too large"))
        val r = readSigned(mfBytes, sig, keys)
        if (r !is Result.Ok) return r
        val m = r.manifest
        if (m.name !in NAMES) return Result.Invalid("unknown model ${m.name}")
        if (appVersion != null && m.minApp != null && compareVersions(appVersion, m.minApp) < 0) {
            return Result.Invalid("needs Pebble ${m.minApp} or newer")
        }
        if (m.files.sumOf { it.size } >
            (if (m.name == "chat") MAX_CHAT_BYTES else MAX_TOTAL_BYTES)
        ) {
            return Result.Invalid("pack is too large")
        }
        Files.createDirectories(modelsDir)
        val tmp = Files.createTempDirectory(modelsDir, ".${m.name}-")
        try {
            for (e in m.files) {
                val entry = z.getEntry(e.path) ?: return Result.Invalid("${e.path} is missing")
                val out = tmp.resolve(e.path)
                Files.createDirectories(out.parent)
                if (!copyChecked(z, entry, out, e)) return Result.Invalid("${e.path} does not match pack.json")
            }
            Files.write(tmp.resolve(MANIFEST), mfBytes)
            Files.writeString(tmp.resolve(SIGNATURE), sig)
            val staged = modelsDir.resolve("${m.name}$STAGED")
            if (staged.exists()) staged.deleteRecursivelySafe()
            Files.move(tmp, staged, StandardCopyOption.ATOMIC_MOVE)
            Files.deleteIfExists(modelsDir.resolve("${m.name}$REMOVE"))
            return r
        } finally {
            if (tmp.exists()) tmp.deleteRecursivelySafe()
        }
    }

    /** Marks the installed [name] pack for removal at the next start (the bundled model is used again). */
    fun stageRemove(modelsDir: Path, name: String) {
        require(name in NAMES)
        modelsDir.resolve("${name}$STAGED").let { if (it.exists()) it.deleteRecursivelySafe() }
        if (modelsDir.resolve(name).exists()) Files.writeString(modelsDir.resolve("$name$REMOVE"), "")
    }

    /** What waits for the next start, per model: "install <version>" or "remove". */
    fun staged(modelsDir: Path): Map<String, String> = NAMES.mapNotNull { n ->
        when {
            modelsDir.resolve("$n$STAGED").exists() -> n to "install ${installedManifest(modelsDir.resolve("$n$STAGED"))?.version}"
            modelsDir.resolve("$n$REMOVE").exists() -> n to "remove"
            else -> null
        }
    }.toMap()

    /** At start-up, before any model loads: does the staged installs and removals. Returns what it did. */
    fun applyStaged(modelsDir: Path): List<String> {
        if (!modelsDir.isDirectory()) return emptyList()
        val done = mutableListOf<String>()
        for (n in NAMES) {
            val target = modelsDir.resolve(n)
            val remove = modelsDir.resolve("$n$REMOVE")
            val staged = modelsDir.resolve("$n$STAGED")
            runCatching {
                if (remove.exists()) {
                    if (target.exists()) target.deleteRecursivelySafe()
                    Files.delete(remove)
                    done += "removed $n"
                }
                if (staged.exists()) {
                    if (target.exists()) target.deleteRecursivelySafe()
                    Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE)
                    done += "installed $n"
                }
            }.onFailure { done += "failed for $n: ${it.message}" }
        }
        // Left over from an install that stopped half-way.
        modelsDir.listDirectoryEntries(".*").filter { it.isDirectory() }.forEach { it.deleteRecursivelySafe() }
        return done
    }

    // ---- Making a pack (the maintainer's tool and the tests) ----

    /** Writes `pack.json` and `pack.sig` into [dir] for every file in it, signed with [privateKey]. Returns pack.json's bytes. */
    fun sign(dir: Path, name: String, version: String, minApp: String?, keyId: String, privateKey: PrivateKey): ByteArray {
        val files = Files.walk(dir).use { s ->
            s.filter { Files.isRegularFile(it) }.map { dir.relativize(it).toString().replace('\\', '/') }
                .filter { it != MANIFEST && it != SIGNATURE }.sorted().toList()
        }
        val json = buildJsonObject {
            put("format", FORMAT)
            put("name", name)
            put("version", version)
            if (minApp != null) put("minApp", minApp)
            put("keyId", keyId)
            put(
                "files",
                buildJsonArray {
                    files.forEach { p ->
                        add(
                            buildJsonObject {
                                put("path", p)
                                put("size", Files.size(dir.resolve(p)))
                                put("sha256", ModelChecksums.sha256(dir.resolve(p)))
                            },
                        )
                    }
                },
            )
        }
        val bytes = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), json).toByteArray()
        val sig = Signature.getInstance("Ed25519").run {
            initSign(privateKey)
            update(bytes)
            sign()
        }
        Files.write(dir.resolve(MANIFEST), bytes)
        Files.writeString(dir.resolve(SIGNATURE), Base64.getEncoder().encodeToString(sig))
        return bytes
    }

    /** Zips a signed folder into [out]. */
    fun zip(dir: Path, out: Path) {
        ZipOutputStream(Files.newOutputStream(out)).use { zos ->
            Files.walk(dir).use { s ->
                s.filter { Files.isRegularFile(it) }.sorted().forEach { f ->
                    zos.putNextEntry(ZipEntry(dir.relativize(f).toString().replace('\\', '/')))
                    Files.copy(f, zos)
                    zos.closeEntry()
                }
            }
        }
    }

    fun publicKey(base64: String): PublicKey = KeyFactory.getInstance(
        "Ed25519",
    ).generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64)))

    fun privateKey(base64: String): PrivateKey = KeyFactory.getInstance(
        "Ed25519",
    ).generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64.trim())))

    // ---- Helpers ----

    private const val STAGED = ".staged"
    private const val REMOVE = ".remove"

    private val safePath = Regex("""^[A-Za-z0-9_.\-]+(/[A-Za-z0-9_.\-]+)*$""")

    private fun parse(bytes: ByteArray): Manifest {
        val o = Json.parseToJsonElement(String(bytes)).jsonObject
        require(o.getValue("format").jsonPrimitive.int == FORMAT) { "unknown format" }
        val files = o.getValue("files").jsonArray.map {
            val f = it.jsonObject
            Entry(
                f.getValue("path").jsonPrimitive.content,
                f.getValue("size").jsonPrimitive.long,
                f.getValue("sha256").jsonPrimitive.content.lowercase(),
            )
        }
        // Each path stays inside the model folder: no "..", no drive or root, no pack files.
        require(files.isNotEmpty()) { "no files" }
        for (f in files) {
            require(safePath.matches(f.path) && f.path.split('/').none { it == "." || it == ".." }) { "unsafe path ${f.path}" }
            require(f.path != MANIFEST && f.path != SIGNATURE) { "pack file listed as a model file" }
            require(f.size >= 0 && f.sha256.matches(Regex("[0-9a-f]{64}"))) { "bad entry ${f.path}" }
        }
        require(files.map { it.path.lowercase() }.toSet().size == files.size) { "a path is listed twice" }
        return Manifest(
            o.getValue("name").jsonPrimitive.content,
            o.getValue("version").jsonPrimitive.content,
            o["minApp"]?.jsonPrimitive?.content,
            o.getValue("keyId").jsonPrimitive.content,
            files,
        )
    }

    /** At most [limit] bytes of [entry]; null if it has more (the zip's own size field is not trusted). */
    private fun readLimited(z: ZipFile, entry: ZipEntry, limit: Long): ByteArray? = z.getInputStream(entry).use { input ->
        val bytes = input.readNBytes(limit.toInt() + 1)
        bytes.takeIf { it.size <= limit }
    }

    /** Copies [entry] to [out]: exactly the size and hash that [e] gives, or false. */
    private fun copyChecked(z: ZipFile, entry: ZipEntry, out: Path, e: Entry): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        z.getInputStream(entry).use { input ->
            Files.newOutputStream(out).use { output ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > e.size) return false
                    digest.update(buf, 0, n)
                    output.write(buf, 0, n)
                }
            }
        }
        return total == e.size && digest.digest().joinToString("") { "%02x".format(it) } == e.sha256
    }

    /** "0.2.0" vs "0.10.1", by number; missing parts count as 0. */
    fun compareVersions(a: String, b: String): Int {
        val x = a.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun Path.deleteRecursivelySafe() = deleteRecursively()
}
