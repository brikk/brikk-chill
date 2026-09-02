package dev.brikk.chill.quarantine.generator.buildtime

import dev.brikk.chill.policy.ChillPolicyLoader
import dev.brikk.chill.quarantine.KotlinxSerializationSupportPolicies
import dev.brikk.chill.quarantine.LibraryPolicies
import dev.brikk.chill.quarantine.generator.jarfile.KotlinStdlibPolicyGenerator
import dev.brikk.chill.quarantine.generator.jarfile.KotlinxSerializationPolicyGenerator
import java.io.File

/**
 * Build-time entry point for the library policies shipped inside the quarantine jar. Runs on the
 * build's own classpath, so each policy describes exactly the library version this build resolved.
 *
 * args: [0] output resources root dir
 *       [1] path of the kotlinx-serialization-core jar to scan
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: GenerateKotlinPolicies <outputResourcesDir> <kotlinxSerializationCoreJar>" }
    val outputDir = File(args[0], ChillPolicyLoader.POLICY_RESOURCE_ROOT)
    val kotlinxJar = File(args[1]).also { require(it.isFile) { "kotlinx-serialization-core jar not found: $it" } }

    val stdlib = LibraryPolicyWriter.write(
        name = LibraryPolicies.KOTLIN_STDLIB,
        generator = KotlinStdlibPolicyGenerator(),
        outputDir = outputDir,
    )
    report(stdlib)

    val kotlinx = LibraryPolicyWriter.write(
        name = LibraryPolicies.KOTLINX_SERIALIZATION_CORE,
        generator = KotlinxSerializationPolicyGenerator(listOf(kotlinxJar)),
        outputDir = outputDir,
        supportLines = KotlinxSerializationSupportPolicies.policy,
        supportDescription = "support for compiler-generated @Serializable / Companion / \$serializer classes",
    )
    report(kotlinx)
}

private fun report(written: LibraryPolicyWriter.Written) {
    println("Wrote policy ${written.name}: ${written.generatedLines} generated + ${written.supportLines} support lines to ${written.file}")
}
