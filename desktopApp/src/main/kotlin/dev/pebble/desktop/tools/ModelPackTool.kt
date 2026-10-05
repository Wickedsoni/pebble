package dev.pebble.desktop.tools

import dev.pebble.desktop.brain.ModelPack
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.system.exitProcess

/**
 * The maintainer's model pack tool (WP D2, ADR 0011). The private key never goes into the repo.
 *
 *   keygen <key file>
 *       Makes an Ed25519 key pair. Writes the private key (PKCS#8, Base64) to <key file>, and prints the
 *       public key to put in ModelPack.TRUSTED_KEYS. Refuses to overwrite a key file.
 *   sign <name> <version> <min app version | -> <key id> <key file> <model folder> <out.zip>
 *       Copies <model folder> (installed layout, e.g. build/model-resources/common/models/intent),
 *       writes pack.json + pack.sig into the copy, and zips it to <out.zip>.
 *   check <pack.zip>
 *       Checks the signature with the keys that the app trusts.
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "keygen" -> keygen(Path.of(args.getOrNull(1) ?: usage()))
        "sign" -> if (args.size == 8) sign(args) else usage()
        "check" -> check(Path.of(args.getOrNull(1) ?: usage()))
        else -> usage()
    }
}

private fun usage(): Nothing {
    System.err.println(
        "usage: keygen <key file> | sign <name> <version> <minApp|-> <keyId> <key file> <model dir> <out.zip> | check <pack.zip>",
    )
    exitProcess(2)
}

private fun keygen(keyFile: Path) {
    if (Files.exists(keyFile)) {
        System.err.println("$keyFile exists; not overwritten")
        exitProcess(1)
    }
    Files.createDirectories(keyFile.toAbsolutePath().parent)
    val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    Files.writeString(keyFile, Base64.getEncoder().encodeToString(pair.private.encoded) + "\n", StandardOpenOption.CREATE_NEW)
    println("private key: $keyFile  (keep a copy offline; anyone with this file can sign models that Pebble trusts)")
    println("public key:  ${Base64.getEncoder().encodeToString(pair.public.encoded)}")
}

private fun sign(a: Array<String>) {
    val (name, version, minApp, keyId) = listOf(a[1], a[2], a[3], a[4])
    val key = ModelPack.privateKey(Files.readString(Path.of(a[5])))
    val src = Path.of(a[6])
    val out = Path.of(a[7])
    val work = Files.createTempDirectory("pebble-pack-")
    Files.walk(src).use { s ->
        s.filter { Files.isRegularFile(it) }.forEach { f ->
            val rel = src.relativize(f).toString()
            if (rel != ModelPack.MANIFEST && rel != ModelPack.SIGNATURE) {
                val to = work.resolve(rel)
                Files.createDirectories(to.parent)
                Files.copy(f, to)
            }
        }
    }
    ModelPack.sign(work, name, version, minApp.takeIf { it != "-" }, keyId, key)
    Files.createDirectories(out.toAbsolutePath().parent)
    Files.deleteIfExists(out)
    ModelPack.zip(work, out)
    println("signed $name $version with $keyId: $out (${Files.size(out) / 1_000_000} MB)")
    check(out)
}

private fun check(zip: Path) {
    val tmp = Files.createTempDirectory("pebble-pack-check-")
    when (val r = ModelPack.stageInstall(zip, tmp, appVersion = null)) {
        is ModelPack.Result.Ok -> println(
            "valid: ${r.manifest.name} ${r.manifest.version}, ${r.manifest.files.size} files, key ${r.manifest.keyId}",
        )

        is ModelPack.Result.Invalid -> {
            println("NOT valid for this app: ${r.reason}")
            exitProcess(1)
        }
    }
}
