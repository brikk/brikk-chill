package dev.brikk.chill.quarantine

import dev.brikk.chill.quarantine.generator.jarfile.ClassPathUtils
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StdlibPolicyStatsTest {
    @Test
    fun stdlibScanCoversARealisticNumberOfClasses() {
        val jars = ClassPathUtils.findKotlinStdLibOrEmbeddedCompilerJars(javaClass.classLoader)
        println("stdlib jars: $jars")

        val bootstrap = Quarantine.painlessPlusKotlinBootstrapPolicy.size
        val full = Quarantine.painlessPlusKotlinFullPolicy.size
        val kotlinLines = Quarantine.painlessPlusKotlinFullPolicy.count { it.startsWith("kotlin") }
        println("bootstrap policy lines: $bootstrap")
        println("full policy lines: $full (kotlin-targeted: $kotlinLines)")

        assertTrue(kotlinLines > 500) { "expected a substantial generated kotlin policy, got $kotlinLines kotlin lines" }
    }
}
