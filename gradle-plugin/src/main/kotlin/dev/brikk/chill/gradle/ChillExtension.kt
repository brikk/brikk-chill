package dev.brikk.chill.gradle

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
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

    /**
     * Library policies to regenerate from this build's own dependency versions (see [ChillPolicySpec]).
     * Each registered spec gets a `chillGeneratePolicy<Name>` task; `chillGeneratePolicies` runs
     * them all. Generated files replace the shipped policy of the same name for `chillVerifyLambdas`.
     */
    abstract val policies: NamedDomainObjectContainer<ChillPolicySpec>

    /**
     * Assembled output owned by `chillGeneratePolicies` (default `build/chill/policy`). Removed
     * registrations are removed here too. Hand this directory to the runtime as `chill.policy.dir`,
     * or copy its files into the OpenSearch plugin's `config/chill-script/`. Do not keep hand-written
     * files here; supply those through [policyOverrides].
     */
    abstract val policyDirectory: DirectoryProperty

    /** Explicit `<name>.ctena` files, or directories containing them; replace generated files by name. */
    abstract val policyOverrides: ConfigurableFileCollection
}
