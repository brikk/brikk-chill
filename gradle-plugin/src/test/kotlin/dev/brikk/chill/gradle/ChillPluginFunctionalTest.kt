package dev.brikk.chill.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ChillPluginFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private fun writeProject(
        lambdaBody: String,
        chillBlock: String = """policy.set("kotlin-bootstrap")""",
        pluginBlock: String = """kotlin("jvm") version "2.4.10"
            id("dev.brikk.chill")""",
    ) {
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
                $pluginBlock
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

    @Test
    fun chillAppliedBeforeKotlinStillVerifiesAndPackagesTheLambda() {
        writeProject(""" "ok" + Math.max(1, 2) """, pluginBlock = """
            id("dev.brikk.chill")
            kotlin("jvm") version "2.4.10"
        """.trimIndent())
        val result = runner("build").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":chillVerifyLambdas")!!.outcome)
        java.util.zip.ZipFile(File(projectDir, "build/libs/chill-demo.jar")).use { zip ->
            val manifest = zip.getInputStream(zip.getEntry("META-INF/chill/verified-lambdas.manifest"))
                .bufferedReader().use { it.readText() }
            assertTrue("demo.Scripts\$fn\$1" in manifest)
        }
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

    @Test
    fun removingPolicyRegistrationsMatchesACleanBuild() {
        val body = """ listOf("a", "b").joinToString("-") """
        val extraPolicy = """
            register("extra") {
                $stdlibJarsFromRuntimeClasspath
                profile.set("custom")
                scanMode.set("all")
                packages.add("kotlin.ranges")
            }
        """.trimIndent()
        writeProject(body, """
            policies {
                register("kotlin-stdlib") {
                    $stdlibJarsFromRuntimeClasspath
                    profile.set("custom")
                    scanMode.set("all")
                    packages.add("kotlin.text")
                }
                $extraPolicy
            }
        """.trimIndent())
        val rejected = runner("chillVerifyLambdas").buildAndFail()
        assertTrue("kotlin.collections" in rejected.output)
        val output = File(projectDir, "build/chill/policy")
        assertEquals(setOf("extra.ctena", "kotlin-stdlib.ctena"), output.list()!!.toSet())

        // Remove one registration, then the last, without cleaning either time.
        writeProject(body, "policies { $extraPolicy }")
        runner("chillVerifyLambdas").build()
        assertEquals(setOf("extra.ctena"), output.list()!!.toSet())
        writeProject(body, "")
        runner("chillVerifyLambdas").build()
        assertTrue(output.listFiles().isNullOrEmpty())
        val manifest = File(projectDir, "build/generated/chill-manifest/META-INF/chill/verified-lambdas.manifest")
        val warm = manifest.readText()
        runner("clean", "chillVerifyLambdas").build()
        assertEquals(warm, manifest.readText())
    }

    @Test
    fun generatorsOwnSeparateOutputsAndExplicitOverridesAreNotModified() {
        val override = File(projectDir, "manual/kotlin-stdlib.ctena").apply {
            parentFile.mkdirs()
            writeText("# deliberately empty override\n")
        }
        writeProject(""" listOf("a").joinToString() """, """
            policyOverrides.from(file("manual/kotlin-stdlib.ctena"))
            policies {
                register("kotlin-stdlib") { $stdlibJarsFromRuntimeClasspath }
                register("extra") {
                    $stdlibJarsFromRuntimeClasspath
                    profile.set("custom")
                    scanMode.set("all")
                    packages.add("kotlin.ranges")
                }
            }
        """.trimIndent())
        runner("chillGeneratePolicies").build()
        val second = runner("chillGeneratePolicies").build()
        for (name in listOf("KotlinStdlib", "Extra")) {
            assertEquals(TaskOutcome.UP_TO_DATE, second.task(":chillGeneratePolicy$name")!!.outcome)
        }
        val output = File(projectDir, "build/chill/policy/kotlin-stdlib.ctena")
        assertEquals(override.readText(), output.readText())
        val rejected = runner("chillVerifyLambdas").buildAndFail()
        assertTrue("kotlin.collections" in rejected.output)
        writeProject(""" listOf("a").joinToString() """, "")
        runner("chillVerifyLambdas").build()
        assertFalse(output.exists())
        assertEquals("# deliberately empty override\n", override.readText())
    }
}
