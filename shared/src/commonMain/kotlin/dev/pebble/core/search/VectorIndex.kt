package dev.pebble.core.search

import dev.pebble.core.brain.Embedding

/** float32 vectors as bytes, little-endian (the `vector_item.vec` BLOB). */
object VectorCodec {
    fun encode(values: FloatArray): ByteArray {
        val out = ByteArray(values.size * 4)
        values.forEachIndexed { i, v ->
            val bits = v.toRawBits()
            out[i * 4] = bits.toByte()
            out[i * 4 + 1] = (bits ushr 8).toByte()
            out[i * 4 + 2] = (bits ushr 16).toByte()
            out[i * 4 + 3] = (bits ushr 24).toByte()
        }
        return out
    }

    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.size % 4 == 0) { "not float32 data: ${bytes.size} bytes" }
        return FloatArray(bytes.size / 4) { i ->
            val b = i * 4
            Float.fromBits(
                (bytes[b].toInt() and 0xFF) or ((bytes[b + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[b + 2].toInt() and 0xFF) shl 16) or ((bytes[b + 3].toInt() and 0xFF) shl 24),
            )
        }
    }
}

/** One indexed item: where it came from ([kind] + [refId]) and its vector. */
class IndexedVector(val kind: String, val refId: String, val vector: Embedding)

/** A search result: the item and its cosine similarity to the query (1 = same meaning). */
data class VectorHit(val kind: String, val refId: String, val score: Float)

/** Finds the items closest in meaning to a query. An interface, so a faster index can replace the scan later. */
fun interface VectorIndex {
    fun search(query: Embedding, k: Int): List<VectorHit>
}

/** Compares the query with every item: exact, and fast enough below ~50 000 items of 384 values. */
class BruteForceIndex(private val items: List<IndexedVector>) : VectorIndex {
    override fun search(query: Embedding, k: Int): List<VectorHit> =
        items.asSequence()
            .filter { it.vector.size == query.size }
            .map { VectorHit(it.kind, it.refId, it.vector.cosine(query)) }
            .sortedByDescending { it.score }
            .take(k)
            .toList()
}
