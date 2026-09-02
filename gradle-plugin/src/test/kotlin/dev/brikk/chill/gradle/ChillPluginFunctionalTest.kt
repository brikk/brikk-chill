package dev.brikk.chill.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ChillPluginFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private fun writeProject(lambdaBody: String, chillBlock: String = """policy.set("kotlin-bootstrap")""") {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            rootProject.name = "chill-demo"
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm") version "2.4.10"
                id("dev.brikk.chill")
            }
            repositories { mavenCentral() }
            kotlin { jvmToolchain(21) }
            chill {
                $chillBlock
            }
            """.trimIndent(),
        )
        val src = File(projectDir, "src/main/kotlin/demo")
        src.mkdirs()
        File(src, "Scripts.kt").writeText(
            """
            package demo

            object Scripts {
                val fn: () -> String = @JvmSerializableLambda { $lambdaBody }
            }
            """.trimIndent(),
        )
    }

    private fun runner(vararg args: String): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args, "--stacktrace")

    @Test
    fun verifiesCleanLambdaAndWritesManifestIntoJar() {
        writeProject(""" "ok" + Math.max(1, 2) """)

        val result = runner("chillVerifyLambdas").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":chillVerifyLambdas")!!.outcome)
        assertTrue("1 serializable lambda class(es), 0 violation(s)" in result.output)

        val manifest = File(projectDir, "build/generated/chill-manifest/META-INF/chill/verified-lambdas.manifest")
        assertTrue(manifest.exists())
        val manifestText = manifest.readText()
        assertTrue("demo.Scripts\$fn\$1" in manifestText)

        // the exact build policy is pinned next to the manifest, named by its fingerprint
        val fingerprint = manifestText.lineSequence()
            .first { it.isNotBlank() && !it.startsWith("#") }
            .split(' ')[1]
        val pinnedPolicy = File(projectDir, "build/generated/chill-manifest/META-INF/chill/policy/by-fingerprint/$fingerprint.ctena")
        assertTrue(pinnedPolicy.exists()) { "expected pinned policy at $pinnedPolicy" }
        assertTrue(pinnedPolicy.readLines().isNotEmpty())

        // and both land in the jar as resources
        val jarResult = runner("jar").build()
        assertEquals(TaskOutcome.SUCCESS, jarResult.task(":jar")!!.outcome)
        val jar = File(projectDir, "build/libs/chill-demo.jar")
        java.util.zip.ZipFile(jar).use { zip ->
            assertTrue(zip.getEntry("META-INF/chill/verified-lambdas.manifest") != null)
            assertTrue(zip.getEntry("META-INF/chill/policy/by-fingerprint/$fingerprint.ctena") != null)
        }
    }

    @Test
    fun failsBuildOnViolatingLambda() {
        writeProject(""" System.getenv("HOME") ?: "" """)

        val result = runner("chillVerifyLambdas").buildAndFail()
        assertTrue("System.getenv" in result.output) { "expected violation detail in output" }
        assertTrue("violate the chill policy" in result.output)
    }

    private val stdlibJarsFromRuntimeClasspath =
        """jars.from(configurations.runtimeClasspath.map { it.filter { f -> f.name.startsWith("kotlin-stdlib") } })"""

    @Test
    fun regeneratesStdlibPolicyFromTheBuildsOwnJarAndVerifiesWithIt() {
        writeProject(
            """ listOf("a", "b").joinToString("-") """,
            """
            policies {
                register("kotlin-stdlib") { $stdlibJarsFromRuntimeClasspath }
            }
            """.trimIndent(),
        )

        val result = runner("chillVerifyLambdas").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":chillGeneratePolicyKotlinStdlib")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":chillVerifyLambdas")!!.outcome)
        assertTrue("library policy 'kotlin-stdlib' overridden from" in result.output) { result.output }
        assertTrue("1 serializable lambda class(es), 0 violation(s)" in result.output) { result.output }

        val generated = File(projectDir, "build/chill/policy/kotlin-stdlib.ctena")
        assertTrue(generated.isFile) { "expected $generated" }
        val lines = generated.readLines()
        assertTrue(lines[0] == "# chill policy: kotlin-stdlib")
        assertTrue(lines.any { it.startsWith("# source: kotlin-stdlib-2.4.10.jar sha256=") }) { lines.take(6).joinToString("\n") }
        // a real stdlib scan, not something loaded off the daemon classpath by accident
        assertTrue(lines.any { it.startsWith("kotlin.collections CollectionsKt.joinToString") }) { "generated policy lacks stdlib members" }
    }

    @Test
    fun regeneratedPolicyReplacesTheShippedOneRatherThanMerging() {
        // a deliberately narrowed "kotlin-stdlib": only kotlin.text. If the override merely merged
        // with the shipped policy, kotlin.collections would still be allowed and this would pass.
        writeProject(
            """ listOf("a", "b").joinToString("-") """,
            """
            policies {
                register("kotlin-stdlib") {
                    $stdlibJarsFromRuntimeClasspath
                    profile.set("custom")
                    scanMode.set("all")
                    packages.add("kotlin.text")
                }
            }
            """.trimIndent(),
        )

        val result = runner("chillVerifyLambdas").buildAndFail()
        assertEquals(TaskOutcome.SUCCESS, result.task(":chillGeneratePolicyKotlinStdlib")!!.outcome)
        assertEquals(TaskOutcome.FAILED, result.task(":chillVerifyLambdas")!!.outcome)
        assertTrue("kotlin.collections" in result.output) { result.output }
        assertTrue("violate the chill policy" in result.output)
    }
}
