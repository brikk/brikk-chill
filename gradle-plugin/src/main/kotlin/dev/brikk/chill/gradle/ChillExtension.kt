package dev.brikk.chill.gradle

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

abstract class ChillExtension {
    companion object {
        /** Painless base JDK + Kotlin bootstrap + generated kotlin-stdlib policy. */
        const val POLICY_KOTLIN_FULL = "kotlin-full"

        /** Painless base JDK + Kotlin bootstrap only. */
        const val POLICY_KOTLIN_BOOTSTRAP = "kotlin-bootstrap"

        /** Painless base JDK only. */
        const val POLICY_BASE_JDK = "base-jdk"
    }

    /** Base policy: one of [POLICY_KOTLIN_FULL] (default), [POLICY_KOTLIN_BOOTSTRAP], [POLICY_BASE_JDK]. */
    abstract val policy: Property<String>

    /**
     * Default verification behavior for scopes without a `@ChillVerifyAtBuild` directive:
     * `"all"` (default) verifies every serializable lambda class, `"annotated"` verifies none.
     * A single `@ChillVerifyAtBuild` / `@ChillVerifyAtBuild(enabled = false)` on the enclosing
     * declaration forces verification on or off regardless of mode (nearest scope wins).
     * Use `annotated` or `@ChillVerifyAtBuild(enabled = false)` scopes (or [excludeClasses])
     * when the module also compiles serializable lambdas for other frameworks (Spark, Flink,
     * Hazelcast, Ignite, ...).
     */
    abstract val mode: Property<String>

    /** Class-name glob patterns (e.g. `com.example.spark.*`) excluded from verification. */
    abstract val excludeClasses: SetProperty<String>

    /** Extra allowance lines added to the policy. */
    abstract val additionalPolicies: SetProperty<String>

    /** Extra `.ctena` files (one allowance per line) added to the policy. */
    abstract val additionalPolicyFiles: ConfigurableFileCollection

    /** Fail the build when a lambda violates the policy (default true; false only reports). */
    abstract val failOnViolation: Property<Boolean>

    /** Write the verification manifest into the jar so the runtime can skip re-verification (default true). */
    abstract val writeManifest: Property<Boolean>
}
