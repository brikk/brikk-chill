package dev.brikk.chill.quarantine

/**
 * Manifest of lambda classes verified against a policy at build time, shipped inside the jar at
 * [RESOURCE_PATH]. The runtime can skip re-verifying a lambda class when its bytes hash and the
 * active policy fingerprint both match an entry.
 *
 * This is a client-side optimization only: a receiving/server side must never trust a manifest
 * that arrives with untrusted classes, and always re-verifies.
 */
object LambdaVerificationManifest {
    const val RESOURCE_PATH = "META-INF/chill/verified-lambdas.manifest"

    data class Entry(val className: String, val classSha256: String, val policyFingerprint: String)

    fun render(entries: List<Entry>): String = buildString {
        appendLine("# chill verified lambda classes: sha256 policyFingerprint className")
        entries.sortedBy { it.className }.forEach {
            appendLine("${it.classSha256} ${it.policyFingerprint} ${it.className}")
        }
    }

    fun parse(text: String): List<Entry> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val parts = line.split(' ', limit = 3)
                require(parts.size == 3) { "Invalid manifest line: $line" }
                Entry(className = parts[2], classSha256 = parts[0], policyFingerprint = parts[1])
            }.toList()

    /** Loads and merges all manifests visible to [classLoader]. */
    fun loadAll(classLoader: ClassLoader): Map<String, Entry> =
        classLoader.getResources(RESOURCE_PATH).asSequence()
            .flatMap { url -> parse(url.readText()) }
            .associateBy { it.className }

    /**
     * Resource path of the pinned build policy for [fingerprint]: the exact policy line set the
     * build verified against, shipped in the jar so a deployment can construct a [Quarantine]
     * whose fingerprint matches the manifest by construction - regardless of which chill or
     * kotlin-stdlib versions are present at runtime.
     */
    fun pinnedPolicyResourcePath(fingerprint: String): String =
        "META-INF/chill/policy/by-fingerprint/$fingerprint.ctena"

    /** Loads the pinned build policy for [fingerprint], or null when not shipped. */
    fun loadPinnedPolicy(classLoader: ClassLoader, fingerprint: String): Set<String>? =
        classLoader.getResourceAsStream(pinnedPolicyResourcePath(fingerprint))?.bufferedReader()?.use { reader ->
            reader.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.toSet()
        }

    /**
     * Builds a [Quarantine] from the pinned policy shipped alongside the manifests visible to
     * [classLoader]. Returns null when no manifest or pinned policy is present. Fails when
     * manifests from multiple different builds (fingerprints) are on the classpath - pass an
     * explicit [fingerprint] to disambiguate.
     */
    fun pinnedQuarantine(classLoader: ClassLoader, fingerprint: String? = null): Quarantine? {
        val chosen = fingerprint ?: run {
            val fingerprints = loadAll(classLoader).values.map { it.policyFingerprint }.distinct()
            when (fingerprints.size) {
                0 -> return null
                1 -> fingerprints.single()
                else -> throw IllegalStateException(
                    "Multiple build policy fingerprints on the classpath: $fingerprints; pass one explicitly",
                )
            }
        }
        val policy = loadPinnedPolicy(classLoader, chosen) ?: return null
        return Quarantine(policy).also {
            check(it.policyFingerprint == chosen) {
                "Pinned policy resource for $chosen hashes to ${it.policyFingerprint}; the resource was modified"
            }
        }
    }
}
