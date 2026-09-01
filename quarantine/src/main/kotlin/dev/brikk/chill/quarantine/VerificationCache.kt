package dev.brikk.chill.quarantine

import java.security.MessageDigest

/**
 * Caches bytecode scan results keyed by a digest of the class bytes.
 *
 * Scanning (ASM parse + allowance extraction) is deterministic per class bytes and independent
 * of any policy, so scan results are safe to reuse across [Quarantine] instances and policies.
 * The (cheap) policy set-membership checks are still evaluated per verification call.
 */
interface VerificationCache {
    fun get(key: String): ClassAllowanceDetector.ScanState?
    fun put(key: String, value: ClassAllowanceDetector.ScanState)

    companion object {
        fun keyFor(classBytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(classBytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Simple bounded in-memory LRU cache; thread safe.
 */
class InMemoryVerificationCache(val maxEntries: Int = 1024) : VerificationCache {
    private val lock = Any()
    private val map = object : LinkedHashMap<String, ClassAllowanceDetector.ScanState>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ClassAllowanceDetector.ScanState>): Boolean =
            size > maxEntries
    }

    override fun get(key: String): ClassAllowanceDetector.ScanState? = synchronized(lock) { map[key] }

    override fun put(key: String, value: ClassAllowanceDetector.ScanState) {
        synchronized(lock) { map[key] = value }
    }
}

/** Disables caching. */
object NoVerificationCache : VerificationCache {
    override fun get(key: String): ClassAllowanceDetector.ScanState? = null
    override fun put(key: String, value: ClassAllowanceDetector.ScanState) {}
}
