package dev.brikk.chill.quarantine

import dev.brikk.chill.quarantine.generator.buildtime.LambdaBuildVerifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Serializable lambdas declared at class-init level so the build verifier can discover their
 * compiled classes in this module's own test classes directory.
 */
object BuildTimeLambdaFixtures {
    val goodLambda: () -> String = @JvmSerializableLambda { "safe " + Math.max(1, 2) }
    val badLambda: () -> String? = @JvmSerializableLambda { System.getenv("HOME") }
}

class LambdaBuildVerifierTests {

    private val verifier = LambdaBuildVerifier(Quarantine(Quarantine.painlessPlusKotlinBootstrapPolicy))

    private fun testClassesDir(): File =
        File(BuildTimeLambdaFixtures::class.java.protectionDomain.codeSource.location.toURI())

    @Test
    fun discoversSerializableLambdaClassesStructurally() {
        val discovered = verifier.discoverLambdaClasses(listOf(testClassesDir())).map { it.className }

        val goodName = BuildTimeLambdaFixtures.goodLambda.javaClass.name
        val badName = BuildTimeLambdaFixtures.badLambda.javaClass.name
        assertTrue(goodName in discovered) { "expected $goodName in $discovered" }
        assertTrue(badName in discovered)

        // non-lambda classes must not be discovered
        assertTrue(discovered.none { it == BuildTimeLambdaFixtures::class.java.name })
    }

    @Test
    fun verifiesDiscoveredLambdasAndFlagsViolations() {
        val results = verifier.verify(listOf(testClassesDir()))
        val byName = results.associateBy { it.className }

        val good = byName.getValue(BuildTimeLambdaFixtures.goodLambda.javaClass.name)
        assertTrue(good.passed) { "expected clean verification, got: ${good.violations}" }

        val bad = byName.getValue(BuildTimeLambdaFixtures.badLambda.javaClass.name)
        assertTrue(!bad.passed)
        assertTrue(bad.violations.any { "System.getenv" in it })
    }

    @Test
    fun manifestEntriesOnlyIncludePassingLambdas() {
        val results = verifier.verify(listOf(testClassesDir()))
        val entries = verifier.manifestEntries(results)

        val entryNames = entries.map { it.className }.toSet()
        assertTrue(BuildTimeLambdaFixtures.goodLambda.javaClass.name in entryNames)
        assertTrue(BuildTimeLambdaFixtures.badLambda.javaClass.name !in entryNames)
        assertTrue(entries.all { it.policyFingerprint == verifier.quarantine.policyFingerprint })

        // hash matches the actual class bytes
        val goodBytes = NamedClassBytes.fromLambda(BuildTimeLambdaFixtures.goodLambda)
        val goodEntry = entries.first { it.className == goodBytes.className }
        assertEquals(VerificationCache.keyFor(goodBytes.bytes), goodEntry.classSha256)
    }
}
