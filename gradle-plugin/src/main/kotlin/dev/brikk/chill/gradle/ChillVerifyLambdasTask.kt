package dev.brikk.chill.gradle

import dev.brikk.chill.quarantine.LambdaVerificationManifest
import dev.brikk.chill.quarantine.Quarantine
import dev.brikk.chill.quarantine.generator.buildtime.LambdaBuildVerifier
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class ChillVerifyLambdasTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classesDirs: ConfigurableFileCollection

    @get:Input
    abstract val policy: Property<String>

    @get:Input
    abstract val mode: Property<String>

    @get:Input
    abstract val excludeClasses: SetProperty<String>

    @get:Input
    abstract val additionalPolicies: SetProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val additionalPolicyFiles: ConfigurableFileCollection

    @get:Input
    abstract val failOnViolation: Property<Boolean>

    @get:Input
    abstract val writeManifest: Property<Boolean>

    @get:OutputDirectory
    abstract val manifestDir: DirectoryProperty

    @TaskAction
    fun run() {
        val basePolicy = when (val name = policy.get()) {
            ChillExtension.POLICY_KOTLIN_FULL -> Quarantine.painlessPlusKotlinFullPolicy
            ChillExtension.POLICY_KOTLIN_BOOTSTRAP -> Quarantine.painlessPlusKotlinBootstrapPolicy
            ChillExtension.POLICY_BASE_JDK -> Quarantine.painlessPlusBaseJdkPolicy
            else -> throw GradleException("Unknown chill policy '$name'")
        }
        val filePolicies = additionalPolicyFiles.files.flatMap { file ->
            file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        }
        val discoveryMode = when (val name = mode.get()) {
            "all" -> LambdaBuildVerifier.DiscoveryMode.ALL
            "annotated" -> LambdaBuildVerifier.DiscoveryMode.ANNOTATED
            else -> throw GradleException("Unknown chill mode '$name' (expected 'all' or 'annotated')")
        }
        val quarantine = Quarantine(basePolicy + additionalPolicies.get() + filePolicies)
        val verifier = LambdaBuildVerifier(quarantine, discoveryMode, excludeClasses.get().toList())

        val results = verifier.verify(classesDirs.files.toList())
        val failures = results.filterNot { it.passed }

        results.filter { it.passed }.forEach {
            logger.info("[chill] verified lambda ${it.className}" + if (it.verifiedWith.isNotEmpty()) " (with ${it.verifiedWith.joinToString()})" else "")
        }
        failures.forEach { failure ->
            logger.error("[chill] lambda ${failure.className} violates the policy:")
            failure.violations.forEach { logger.error("[chill]   - $it") }
        }

        val outDir = manifestDir.get().asFile
        outDir.deleteRecursively()
        if (writeManifest.get()) {
            val entries = verifier.manifestEntries(results)
            if (entries.isNotEmpty()) {
                val manifestFile = outDir.resolve(LambdaVerificationManifest.RESOURCE_PATH)
                manifestFile.parentFile.mkdirs()
                manifestFile.writeText(LambdaVerificationManifest.render(entries))

                // pin the exact resolved policy next to the manifest so a deployment can verify
                // (or skip) with the very policy this build used, independent of the chill or
                // kotlin-stdlib versions present at runtime
                val policyFile = outDir.resolve(LambdaVerificationManifest.pinnedPolicyResourcePath(quarantine.policyFingerprint))
                policyFile.parentFile.mkdirs()
                policyFile.writeText(quarantine.policies.sorted().joinToString("\n", postfix = "\n"))
            }
        }
        outDir.mkdirs()

        logger.lifecycle("[chill] ${results.size} serializable lambda class(es), ${failures.size} violation(s)")

        if (failures.isNotEmpty() && failOnViolation.get()) {
            throw GradleException("${failures.size} lambda class(es) violate the chill policy; see log for details")
        }
    }
}
