package dev.pebble.desktop

import dev.pebble.desktop.brain.ModelChecksums
import dev.pebble.desktop.brain.ModelPack
import dev.pebble.desktop.brain.ModelPack.Result
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Signed model packs (WP D2): what installs, what is refused, and what loads afterwards. */
class ModelPackTest {
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val keys = mapOf("test-key" to Base64.getEncoder().encodeToString(pair.public.encoded))
    private val root: Path = Files.createTempDirectory("pack-test")
    private val data: Path = root.resolve("data").also { Files.createDirectories(it) }
    private val models: Path = data.resolve("models")

    /** A small fake "intent" model folder, signed, zipped. */
    private fun pack(name: String = "intent", minApp: String? = null, tweak: (Path) -> Unit = {}): Path {
        val dir = Files.createTempDirectory(root, "src")
        dir.resolve("intent.int8.onnx").writeText("weights")
        Files.createDirectories(dir.resolve("tokenizer"))
        dir.resolve("tokenizer/tokenizer.json").writeText("{}")
        ModelPack.sign(dir, name, "v9", minApp, "test-key", pair.private)
        tweak(dir)
        return root.resolve("${dir.fileName}.zip").also { ModelPack.zip(dir, it) }
    }

    private fun stage(zip: Path, app: String? = null) = ModelPack.stageInstall(zip, models, app, keys)

    @Test
    fun aSignedPackInstallsAtTheNextStartAndVerifies() {
        val r = stage(pack())
        assertIs<Result.Ok>(r)
        assertEquals("v9", r.manifest.version)
        assertTrue(!models.resolve("intent").exists(), "nothing changes until the next start")
        assertEquals(mapOf("intent" to "install v9"), ModelPack.staged(models))
        assertEquals(listOf("installed intent"), ModelPack.applyStaged(models))
        val dir = models.resolve("intent")
        assertIs<Result.Ok>(ModelPack.verifyInstalled(dir, "intent", keys = keys))
        assertEquals("intent-v9", ModelChecksums.version(dir, "intent", "intent.int8.onnx"))
        assertIs<Result.Invalid>(ModelPack.verifyInstalled(dir, "asr", keys = keys), "a pack is bound to its model")
    }

    @Test
    fun aFileChangedAfterInstallIsNotLoaded() {
        stage(pack())
        ModelPack.applyStaged(models)
        val dir = models.resolve("intent")
        dir.resolve("intent.int8.onnx").writeText("evil weights")
        assertIs<Result.Invalid>(ModelPack.verifyInstalled(dir, "intent", keys = keys))
    }

    @Test
    fun aHandPlacedModelWithoutAPackIsNeverTrusted() {
        val dir = models.resolve("intent")
        Files.createDirectories(dir)
        dir.resolve("intent.int8.onnx").writeText("weights")
        assertNull(ModelChecksums.trustedUserDir(data, "intent", null))
        assertIs<Result.Invalid>(ModelPack.verifyInstalled(dir, "intent", keys = keys))
    }

    @Test
    fun theShippedKeyDoesNotTrustATestPack() {
        stage(pack())
        ModelPack.applyStaged(models)
        assertNull(ModelChecksums.trustedUserDir(data, "intent", null), "signed by a key the app does not know")
    }

    @Test
    fun tamperedPacksAreRefused() {
        // A changed model file: its hash no longer matches pack.json.
        assertIs<Result.Invalid>(stage(pack { it.resolve("intent.int8.onnx").writeText("evil") }))
        // A changed pack.json: the signature no longer matches.
        assertIs<Result.Invalid>(stage(pack { it.resolve("pack.json").writeText(it.resolve("pack.json").readText().replace("v9", "v99")) }))
        // Signed by a key that the app does not trust.
        assertIs<Result.Invalid>(ModelPack.stageInstall(pack(), models, null, mapOf("test-key" to keys.values.first().reversed())))
        // An unknown model name.
        assertIs<Result.Invalid>(stage(pack(name = "llm")))
        assertTrue(!models.resolve("intent.staged").exists() && ModelPack.staged(models).isEmpty())
    }

    @Test
    fun aPackForANewerAppWaits() {
        val zip = pack(minApp = "0.3.0")
        assertIs<Result.Invalid>(stage(zip, app = "0.2.1"))
        assertIs<Result.Ok>(stage(zip, app = "0.10.0"))
        assertIs<Result.Ok>(stage(zip, app = null), "a development build skips the check")
    }

    @Test
    fun pathsOutsideTheModelFolderAreRefused() {
        val zip = handMade(
            """{"format":1,"name":"intent","version":"v1","keyId":"test-key","files":[{"path":"../evil.dll","size":4,"sha256":"${"0".repeat(
                64,
            )}"}]}""",
            mapOf("../evil.dll" to "evil"),
        )
        assertIs<Result.Invalid>(stage(zip))
        assertTrue(!data.resolve("evil.dll").exists() && !root.resolve("evil.dll").exists())
    }

    @Test
    fun aFileLargerThanItsEntryIsRefused() {
        val big = "x".repeat(10_000)
        val zip = handMade(
            """{"format":1,"name":"intent","version":"v1","keyId":"test-key","files":[{"path":"intent.int8.onnx","size":4,"sha256":"${"0".repeat(
                64,
            )}"}]}""",
            mapOf("intent.int8.onnx" to big),
        )
        assertIs<Result.Invalid>(stage(zip))
    }

    @Test
    fun removalWaitsForTheNextStart() {
        stage(pack())
        ModelPack.applyStaged(models)
        ModelPack.stageRemove(models, "intent")
        assertTrue(models.resolve("intent").exists())
        assertEquals(mapOf("intent" to "remove"), ModelPack.staged(models))
        assertEquals(listOf("removed intent"), ModelPack.applyStaged(models))
        assertTrue(!models.resolve("intent").exists())
    }

    @Test
    fun theAppTrustsOneRealKey() {
        val key = ModelPack.TRUSTED_KEYS.values.single()
        assertNotNull(ModelPack.publicKey(key), "the compiled-in public key must parse as Ed25519")
    }

    /** A zip with a pack.json that we sign ourselves, so the signature is valid and only the content is bad. */
    private fun handMade(manifest: String, files: Map<String, String>): Path {
        val sig = Signature.getInstance("Ed25519").run {
            initSign(pair.private)
            update(manifest.toByteArray())
            Base64.getEncoder().encodeToString(sign())
        }
        val zip = Files.createTempFile(root, "hand", ".zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { z ->
            (files + mapOf(ModelPack.MANIFEST to manifest, ModelPack.SIGNATURE to sig)).forEach { (n, t) ->
                z.putNextEntry(ZipEntry(n))
                z.write(t.toByteArray())
                z.closeEntry()
            }
        }
        return zip
    }
}
