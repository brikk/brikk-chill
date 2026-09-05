package dev.brikk.chill.opensearch.plugin

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PluginPackagingTest {
    @TempDir
    lateinit var project: File

    @Test
    fun changingOnlyPluginPermissionsReinstallsAndRetestsTheArchive() {
        val root = File(System.getProperty("chill.project.root")!!)
        val excluded = setOf(".git", ".gradle", ".kotlin", ".idea", "build")
        root.walkTopDown().onEnter { it.name !in excluded }.filter { it.isFile }.forEach { source ->
            val target = project.resolve(source.relativeTo(root))
            target.parentFile.mkdirs()
            source.copyTo(target)
        }
        fun run() = GradleRunner.create().withProjectDir(project)
            .withArguments(":opensearch-plugin:integrationTest", "--no-build-cache", "--console=plain", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, run().task(":opensearch-plugin:integrationTest")!!.outcome)
        val archive = project.resolve("opensearch-plugin/build/distributions").listFiles()!!.single { it.extension == "zip" }
        val before = archive.readBytes()
        assertEquals(TaskOutcome.UP_TO_DATE, run().task(":opensearch-plugin:integrationTest")!!.outcome)

        project.resolve("opensearch-plugin/src/main/plugin-metadata/plugin-security.policy")
            .appendText("\n// Packaging-only regression test.\n")
        val changed = run()
        assertFalse(before.contentEquals(archive.readBytes()))
        assertEquals(TaskOutcome.UP_TO_DATE, changed.task(":opensearch-plugin:compileIntegrationTestKotlin")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, changed.task(":opensearch-plugin:pluginZip")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, changed.task(":opensearch-plugin:integrationTest")!!.outcome)
    }
}
