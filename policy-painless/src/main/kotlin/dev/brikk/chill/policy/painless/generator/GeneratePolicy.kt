package dev.brikk.chill.policy.painless.generator

import dev.brikk.chill.policy.ChillPolicyLoader
import dev.brikk.chill.policy.painless.PainlessWhitelistParser
import java.io.File

/**
 * Build-time entry point: parses the OpenSearch Painless whitelist definitions (extracted from the
 * lang-painless jar) plus the local "plus" extension definitions, and writes the base JDK policy
 * resource consumed by `ChillPolicyLoader.loadPolicy("painless-base-jdk")`.
 *
 * args: [0] dir of extracted OpenSearch whitelist .txt files
 *       [1] dir of plus extension .txt files
 *       [2] output resources root dir
 */
fun main(args: Array<String>) {
    require(args.size == 3) { "usage: GeneratePolicy <whitelistsDir> <plusDir> <outputResourcesDir>" }
    val whitelistsDir = File(args[0])
    val plusDir = File(args[1])
    val outputDir = File(args[2])

    // only the JDK definitions apply outside an OpenSearch runtime
    val jdkOnlyDir = File(outputDir, "../painless-jdk-only").canonicalFile.apply {
        deleteRecursively()
        mkdirs()
    }
    (whitelistsDir.listFiles { f: File -> f.name.startsWith("java.") && f.name.endsWith(".txt") } ?: emptyArray())
        .forEach { it.copyTo(File(jdkOnlyDir, it.name)) }

    val outputFile = File(outputDir, "${ChillPolicyLoader.POLICY_RESOURCE_ROOT}/painless-base-jdk.ctena")

    val parser = PainlessWhitelistParser(strict = false)
    parser.writePolicy(listOf(jdkOnlyDir, plusDir), outputFile)

    println("Wrote ${outputFile.readLines().size} policy lines to $outputFile")
}
