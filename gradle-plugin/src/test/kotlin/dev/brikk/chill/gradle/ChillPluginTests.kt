package dev.brikk.chill.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChillPluginTests {

    @Test
    fun registersTaskAndExtensionOnJavaProjects() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("java")
        project.plugins.apply("dev.brikk.chill")

        val extension = project.extensions.findByName("chill") as ChillExtension?
        assertNotNull(extension)
        assertEquals(ChillExtension.POLICY_KOTLIN_FULL, extension!!.policy.get())
        assertTrue(extension.failOnViolation.get())

        val task = project.tasks.findByName("chillVerifyLambdas")
        assertNotNull(task)
        assertTrue(task is ChillVerifyLambdasTask)

        // wired into check
        val check = project.tasks.getByName("check")
        assertTrue(check.dependsOn.any { dep ->
            (dep as? org.gradle.api.tasks.TaskProvider<*>)?.name == "chillVerifyLambdas" || dep == task
        })
    }

    @Test
    fun noTaskWithoutJavaBase() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("dev.brikk.chill")
        assertEquals(null, project.tasks.findByName("chillVerifyLambdas"))
    }
}
