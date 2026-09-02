package dev.brikk.chill.gradle

import org.gradle.api.Named
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

/**
 * One named library policy to (re)generate from this build's own dependency versions, producing
 * an override file `<name>.ctena` that replaces the policy of the same name shipped in the chill
 * quarantine jar (see `dev.brikk.chill.quarantine.LibraryPolicies`).
 *
 * ```kotlin
 * chill {
 *     policies {
 *         // shipped profile, your stdlib version
 *         register("kotlin-stdlib") {
 *             jars.from(configurations.runtimeClasspath.map { it.filter { f -> f.name.startsWith("kotlin-stdlib") } })
 *         }
 *         // shipped profile; kotlinx classes need the stdlib to load, so give the full classpath
 *         register("kotlinx-serialization-core") {
 *             jars.from(configurations.runtimeClasspath.map { it.filter { f -> f.name.startsWith("kotlinx-serialization-core") } })
 *             classpath.from(configurations.runtimeClasspath)
 *         }
 *         // any other library: custom profile
 *         register("my-lib") {
 *             jars.from(...); classpath.from(configurations.runtimeClasspath)
 *             packages.add("com.example.mylib")
 *             scanMode.set("safe")
 *         }
 *     }
 * }
 * ```
 *
 * Generated files land in `build/chill/policy/`, which `chillVerifyLambdas` uses as its override
 * directory, and which is the directory to hand to the runtime (`chill.policy.dir`, or the
 * OpenSearch plugin's `config/chill-script/`).
 */
abstract class ChillPolicySpec(private val name: String) : Named {

    companion object {
        const val PROFILE_KOTLIN_STDLIB = "kotlin-stdlib"
        const val PROFILE_KOTLINX_SERIALIZATION_CORE = "kotlinx-serialization-core"
        const val PROFILE_CUSTOM = "custom"
    }

    override fun getName(): String = name

    /** The jar(s) whose API becomes the policy. */
    abstract val jars: ConfigurableFileCollection

    /**
     * Everything needed to *load* classes from [jars] (their dependencies). [jars] are always
     * included. Loading is isolated from the build's own classpath so the policy describes exactly
     * these files.
     */
    abstract val classpath: ConfigurableFileCollection

    /**
     * Which generator configuration to use: [PROFILE_KOTLIN_STDLIB], [PROFILE_KOTLINX_SERIALIZATION_CORE]
     * (the shipped profiles), or [PROFILE_CUSTOM]. Defaults to the spec name when it is a shipped
     * profile, else custom.
     */
    abstract val profile: Property<String>

    /** Custom profile: `"safe"` (verify each class against the bootstrap policy first) or `"all"`. */
    abstract val scanMode: Property<String>

    /** Custom profile: package prefixes to include (empty = every class in the jars). */
    abstract val packages: SetProperty<String>

    /** Custom profile: package prefixes to exclude. */
    abstract val excludePackages: SetProperty<String>

    /** Custom profile: fully qualified class names to exclude. */
    abstract val excludeClasses: SetProperty<String>

    /** Hand-written allowance lines appended to the generated ones (shipped profiles add their own). */
    abstract val supportPolicies: SetProperty<String>
}
