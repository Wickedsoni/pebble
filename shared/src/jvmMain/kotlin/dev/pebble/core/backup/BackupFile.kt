package dev.pebble.core.backup

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** A backup file that cannot be read: not a Pebble backup, a newer format, a wrong passphrase, or damage. */
class BackupException(message: String) : Exception(message)

/**
 * The encrypted backup file (WP E4, ADR 0015). Only JDK crypto (SunJCE in `java.base`).
 *
 * ```
 * header (36 bytes) = "PEBBLEBK" | version (1) | PBKDF2 iterations (4, big-endian) | salt (16) | nonce prefix (7)
 * chunks            = AES-256-GCM of 1 MiB plaintext pieces, each with a 16-byte tag
 * ```
 * - Key: PBKDF2-HMAC-SHA256 over the passphrase and the salt, 256 bits.
 * - Nonce of chunk i: prefix (7) | i (4, big-endian) | 1 for the last chunk, else 0 (the "STREAM" construction).
 *   A removed, added or moved chunk fails its tag, so a cut file is found, also at a chunk boundary.
 * - Every chunk authenticates the header (AAD), so a changed iteration count or salt also fails.
 * - Memory stays at about 2 chunks, whatever the database size (the app has a 256 MB heap).
 */
object BackupFile {
    const val VERSION: Byte = 1
    const val ITERATIONS = 600_000
    internal const val CHUNK = 1 shl 20
    private const val TAG_BYTES = 16
    private const val MAX_ITERATIONS = 10_000_000
    private val MAGIC = "PEBBLEBK".toByteArray(Charsets.US_ASCII)
    private const val HEADER = 8 + 1 + 4 + 16 + 7

    /** Encrypts all of [input] into [output]. [iterations]: lower only in tests. */
    fun encrypt(
        input: InputStream,
        output: OutputStream,
        passphrase: CharArray,
        iterations: Int = ITERATIONS,
        random: SecureRandom = SecureRandom(),
    ) {
        val salt = ByteArray(16).also(random::nextBytes)
        val prefix = ByteArray(7).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER).put(MAGIC).put(VERSION).putInt(iterations).put(salt).put(prefix).array()
        val key = key(passphrase, salt, iterations)
        output.write(header)
        var current = readChunk(input)
        var index = 0
        while (true) {
            val next = if (current.size == CHUNK) readChunk(input) else ByteArray(0)
            val last = next.isEmpty()
            output.write(cipher(Cipher.ENCRYPT_MODE, key, prefix, index, last, header).doFinal(current))
            if (last) break
            current = next
            index++
        }
        output.flush()
    }

    /**
     * Decrypts [input] into [output]. Throws [BackupException] for a wrong passphrase or any damage. Plaintext is
     * written chunk by chunk as each one passes its tag; when this throws, delete what [output] got.
     */
    fun decrypt(input: InputStream, output: OutputStream, passphrase: CharArray) {
        val header = ByteArray(HEADER)
        if (input.readNBytes(header, 0, HEADER) < HEADER || !header.copyOfRange(0, 8).contentEquals(MAGIC)) {
            throw BackupException("This is not a Pebble backup file.")
        }
        val buf = ByteBuffer.wrap(header, 8, HEADER - 8)
        val version = buf.get()
        if (version != VERSION) throw BackupException("This backup was made by a newer Pebble (format $version). Update Pebble first.")
        val iterations = buf.int
        if (iterations !in 1..MAX_ITERATIONS) throw BackupException("The backup file is damaged.")
        val salt = ByteArray(16).also { buf.get(it) }
        val prefix = ByteArray(7).also { buf.get(it) }
        val key = key(passphrase, salt, iterations)

        val block = CHUNK + TAG_BYTES
        var current = input.readNBytes(block)
        var index = 0
        while (true) {
            if (current.size < TAG_BYTES) throw BackupException("The backup file is cut short or damaged.")
            val next = if (current.size == block) input.readNBytes(block) else ByteArray(0)
            val last = next.isEmpty()
            val plain = try {
                cipher(Cipher.DECRYPT_MODE, key, prefix, index, last, header).doFinal(current)
            } catch (e: GeneralSecurityException) {
                throw BackupException(
                    if (index == 0) "Wrong passphrase, or the backup file is damaged." else "The backup file is cut short or damaged.",
                )
            }
            output.write(plain)
            if (last) break
            current = next
            index++
        }
        output.flush()
    }

    private fun readChunk(input: InputStream): ByteArray = input.readNBytes(CHUNK)

    private fun key(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun cipher(mode: Int, key: SecretKeySpec, prefix: ByteArray, index: Int, last: Boolean, header: ByteArray): Cipher {
        val nonce = ByteBuffer.allocate(12).put(prefix).putInt(index).put(if (last) 1 else 0).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BYTES * 8, nonce))
            updateAAD(header)
        }
    }
}
