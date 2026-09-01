package dev.brikk.chill.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSet

/**
 * Verifies all serializable (class-compiled) lambdas in the main compilation output against a
 * Chill quarantine policy at build time, failing the build on violations, and ships a
 * verification manifest in the jar so the runtime can skip re-verifying unchanged lambdas.
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

        project.plugins.withId("java-base") {
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
                }

                // ship the manifest as a main resource inside the jar
                main.resources.srcDir(verifyTask)

                project.tasks.named("check") { it.dependsOn(verifyTask) }
            }
        }
    }
}
