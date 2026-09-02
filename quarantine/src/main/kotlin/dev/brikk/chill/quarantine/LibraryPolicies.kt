package dev.brikk.chill.quarantine

import dev.brikk.chill.policy.ChillPolicyLoader

/**
 * Named policies for common Kotlin libraries, generated at build time against the library
 * versions this chill build resolved and shipped as `META-INF/chill/policy/<name>.ctena`
 * resources in the quarantine jar. Nothing is scanned at runtime.
 *
 * To run against a library version this build has not seen, regenerate the named policy with
 * `LibraryPolicyWriter` and point [ChillPolicyLoader] at it (override directory via API or the
 * `chill.policy.dir` system property); the override replaces the shipped policy of that name.
 *
 * Version skew without an override fails closed: a caller on a newer library that uses a member
 * the shipped policy does not know is rejected at freeze time.
 */
object LibraryPolicies {
    const val KOTLIN_STDLIB = "kotlin-stdlib"
    const val KOTLINX_SERIALIZATION_CORE = "kotlinx-serialization-core"

    /** Verified-safe subset of kotlin-stdlib (excludes kotlin.io / concurrent / coroutines). */
    val kotlinStdlib: Set<String> by lazy { ChillPolicyLoader.loadPolicy(KOTLIN_STDLIB, LibraryPolicies::class.java.classLoader) }

    /**
     * kotlinx-serialization-core public API plus the support allowances that compiler-generated
     * `@Serializable` / `Companion` / `$serializer` classes need ([KotlinxSerializationSupportPolicies]).
     */
    val kotlinxSerializationCore: Set<String> by lazy {
        ChillPolicyLoader.loadPolicy(KOTLINX_SERIALIZATION_CORE, LibraryPolicies::class.java.classLoader)
    }
}
