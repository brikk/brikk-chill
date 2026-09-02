package dev.brikk.chill.policy

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PolicyLoaderTests {

    private val resourcePolicy = setOf(
        "java.lang String ref_Class",
        "java.lang String.length()I call_Class_Instance_Method",
    )

    @AfterEach
    fun reset() {
        ChillPolicyLoader.overrideDirectory = null
        System.clearProperty(ChillPolicyLoader.OVERRIDE_DIR_PROPERTY)
    }

    @Test
    fun classpathResourceIsTheDefaultAndIsTrimmedWithCommentsDropped() {
        assertNull(ChillPolicyLoader.overrideFileFor("loader-test"))
        assertEquals(resourcePolicy, ChillPolicyLoader.loadPolicy("loader-test"))
    }

    @Test
    fun overrideFileReplacesTheResourceRatherThanMerging(@TempDir dir: Path) {
        Files.writeString(dir.resolve("loader-test.ctena"), "java.util List ref_Class\n")
        ChillPolicyLoader.overrideDirectory = dir

        assertEquals(dir.resolve("loader-test.ctena"), ChillPolicyLoader.overrideFileFor("loader-test"))
        assertEquals(setOf("java.util List ref_Class"), ChillPolicyLoader.loadPolicy("loader-test"))
    }

    @Test
    fun overrideDirectoryWithoutTheNamedFileFallsBackToTheResource(@TempDir dir: Path) {
        Files.writeString(dir.resolve("some-other.ctena"), "java.util List ref_Class\n")
        ChillPolicyLoader.overrideDirectory = dir

        assertNull(ChillPolicyLoader.overrideFileFor("loader-test"))
        assertEquals(resourcePolicy, ChillPolicyLoader.loadPolicy("loader-test"))
    }

    @Test
    fun systemPropertyIsHonoredAndExplicitDirectoryWinsOverIt(@TempDir fromProperty: Path, @TempDir fromApi: Path) {
        Files.writeString(fromProperty.resolve("loader-test.ctena"), "via property\n")
        Files.writeString(fromApi.resolve("loader-test.ctena"), "via api\n")

        System.setProperty(ChillPolicyLoader.OVERRIDE_DIR_PROPERTY, fromProperty.toString())
        assertEquals(setOf("via property"), ChillPolicyLoader.loadPolicy("loader-test"))

        ChillPolicyLoader.overrideDirectory = fromApi
        assertEquals(setOf("via api"), ChillPolicyLoader.loadPolicy("loader-test"))
    }

    @Test
    fun missingEverywhereNamesTheOverrideDirectoryInTheError(@TempDir dir: Path) {
        ChillPolicyLoader.overrideDirectory = dir
        val ex = assertThrows<IllegalStateException> { ChillPolicyLoader.loadPolicy("no-such-policy") }
        assertTrue(dir.toString() in ex.message!!) { ex.message }
    }
}
