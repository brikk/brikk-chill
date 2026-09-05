package dev.brikk.chill.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.Sync

/**
 * Verifies all serializable (class-compiled) lambdas in the main compilation output against a
 * Chill quarantine policy at build time, failing the build on violations, and ships a
 * verification manifest in the jar so the runtime can skip re-verifying unchanged lambdas.
 *
 * Optionally regenerates named library policies from this build's own dependency versions
 * (`chill.policies`), producing override files that both the verification here and the runtime
 * can use in place of the policies shipped in the chill jars.
 *
 * Apply alongside a JVM language plugin (e.g. `org.jetbrains.kotlin.jvm`).
 */
class ChillPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("chill", ChillExtension::class.java)
        extension.policy.convention(ChillExtension.POLICY_KOTLIN_FULL)
        extension.mode.convention("all")
        extension.failOnViolation.convention(true)
        extension.writeManifest.convention(true)
        extension.policyDirectory.convention(project.layout.buildDirectory.dir("chill/policy"))

        val generateAll = project.tasks.register("chillGeneratePolicies", Sync::class.java) { task ->
            task.group = "build"
            task.description = "Assembles the currently registered library policies and explicit overrides"
            task.into(extension.policyDirectory)
            task.from(extension.policyOverrides)
            task.duplicatesStrategy = DuplicatesStrategy.EXCLUDE // explicit overrides take precedence
        }

        extension.policies.all { spec ->
            spec.profile.convention(
                when (spec.name) {
                    ChillPolicySpec.PROFILE_KOTLIN_STDLIB, ChillPolicySpec.PROFILE_KOTLINX_SERIALIZATION_CORE -> spec.name
                    else -> ChillPolicySpec.PROFILE_CUSTOM
                },
            )
            spec.scanMode.convention("safe")

            val generate = project.tasks.register(
                "chillGeneratePolicy${spec.name.toTaskNamePart()}",
                ChillGeneratePolicyTask::class.java,
            ) { task ->
                task.group = "build"
                task.description = "Generates the chill library policy '${spec.name}' from this build's dependencies"
                task.policyName.set(spec.name)
                task.jars.from(spec.jars)
                task.classpath.from(spec.classpath)
                task.profile.set(spec.profile)
                task.scanMode.set(spec.scanMode)
                task.packages.set(spec.packages)
                task.excludePackages.set(spec.excludePackages)
                task.excludeClasses.set(spec.excludeClasses)
                task.supportPolicies.set(spec.supportPolicies)
                task.outputDirectory.set(project.layout.buildDirectory.dir("chill/generated-policy/${spec.name}"))
            }
            generateAll.configure { it.from(generate) }
        }

        project.plugins.withId("java") {
            val sourceSets = project.extensions.getByType(JavaPluginExtension::class.java).sourceSets
            sourceSets.named(SourceSet.MAIN_SOURCE_SET_NAME) { main ->
                val verifyTask = project.tasks.register("chillVerifyLambdas", ChillVerifyLambdasTask::class.java) { task ->
                    task.group = "verification"
                    task.description = "Verifies serializable lambda classes against the Chill quarantine policy"
                    // classesDirs (not output) to avoid a cycle with processResources below
                    task.classesDirs.from(main.output.classesDirs)
                    task.policy.set(extension.policy)
                    task.mode.set(extension.mode)
                    task.excludeClasses.set(extension.excludeClasses)
                    task.additionalPolicies.set(extension.additionalPolicies)
                    task.additionalPolicyFiles.from(extension.additionalPolicyFiles)
                    task.failOnViolation.set(extension.failOnViolation)
                    task.writeManifest.set(extension.writeManifest)
                    task.manifestDir.set(project.layout.buildDirectory.dir("generated/chill-manifest"))
                    // regenerated library policies replace the shipped ones for verification too
                    task.policyOverrideDirectory.set(extension.policyDirectory)
                    task.dependsOn(generateAll)
                }

                // ship the manifest as a main resource inside the jar
                main.resources.srcDir(verifyTask)

                project.tasks.named("check") { it.dependsOn(verifyTask) }
            }
        }
    }

    private fun String.toTaskNamePart(): String =
        split('-', '_', '.', ' ').filter { it.isNotEmpty() }.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
}
