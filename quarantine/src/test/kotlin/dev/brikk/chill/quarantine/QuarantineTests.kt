package dev.brikk.chill.quarantine

import dev.brikk.chill.quarantine.fixtures.IndyLambdaOps
import dev.brikk.chill.quarantine.fixtures.IndyLambdaViolationOps
import dev.brikk.chill.quarantine.fixtures.SafeOps
import dev.brikk.chill.quarantine.fixtures.UnsafeOps
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class QuarantineTests {

    private val quarantine = Quarantine(Quarantine.painlessPlusKotlinBootstrapPolicy)

    private fun classBytesOf(clazz: Class<*>): NamedClassBytes =
        NamedClassBytes.fromClassLoader(clazz.name, clazz.classLoader)

    @Test
    fun safeClassPassesBootstrapPolicy() {
        val result = quarantine.verifyClassAgainstPolicies(listOf(classBytesOf(SafeOps::class.java)))
        assertTrue(result.violations.isEmpty()) { "expected no violations, got:\n${result.violations.joinToString("\n")}" }
    }

    @Test
    fun unsafeClassIsFlagged() {
        val result = quarantine.verifyClassAgainstPolicies(listOf(classBytesOf(UnsafeOps::class.java)))
        assertTrue(result.failed)
        assertTrue(result.violations.any { "System.getenv" in it }) { "expected System.getenv violation, got:\n${result.violations.joinToString("\n")}" }
        assertTrue(result.violations.any { "ProcessBuilder" in it }) { "expected ProcessBuilder violation, got:\n${result.violations.joinToString("\n")}" }
    }

    @Test
    fun invokedynamicJavaLambdaIsScannedThroughMetafactory() {
        val result = quarantine.verifyClassAgainstPolicies(listOf(classBytesOf(IndyLambdaOps::class.java)))
        assertTrue(result.violations.isEmpty()) { "expected no violations, got:\n${result.violations.joinToString("\n")}" }
    }

    @Test
    fun invokedynamicLambdaBodyViolationIsCaught() {
        val result = quarantine.verifyClassAgainstPolicies(listOf(classBytesOf(IndyLambdaViolationOps::class.java)))
        assertTrue(result.failed)
        assertTrue(result.violations.any { "System.getenv" in it }) { "expected System.getenv violation through LambdaMetafactory, got:\n${result.violations.joinToString("\n")}" }
    }

    @Test
    fun serializableLambdaClassCanBeChoppedAndVerified() {
        val captured = "hello"
        val lambda: () -> String = @JvmSerializableLambda { captured + "!" }

        val bytes = NamedClassBytes.fromLambda(lambda)
        assertTrue(bytes.bytes.isNotEmpty())
        assertTrue(bytes.className.contains("QuarantineTests"))

        val result = quarantine.verifyClassAgainstPolicies(listOf(bytes))
        assertTrue(result.violations.isEmpty()) { "expected no violations, got:\n${result.violations.joinToString("\n")}" }
    }

    @Test
    fun serializableLambdaWithViolationIsFlagged() {
        val lambda: () -> String? = @JvmSerializableLambda { System.getenv("PATH") }

        val bytes = NamedClassBytes.fromLambda(lambda)
        val result = quarantine.verifyClassAgainstPolicies(listOf(bytes))
        assertTrue(result.failed)
        assertTrue(result.violations.any { "System.getenv" in it })
    }

    @Test
    fun plainIndyLambdaFailsFastWithGuidance() {
        val lambda: () -> String = { "no class behind me" }

        val ex = assertThrows<IllegalArgumentException> { NamedClassBytes.fromLambda(lambda) }
        assertTrue("JvmSerializableLambda" in ex.message!!) { "expected guidance about @JvmSerializableLambda, got: ${ex.message}" }
    }

    @Test
    fun scanResultsAreCached() {
        class CountingCache : VerificationCache {
            val delegate = InMemoryVerificationCache()
            var hits = 0
            var misses = 0
            override fun get(key: String): ClassAllowanceDetector.ScanState? =
                delegate.get(key)?.also { hits++ } ?: run { misses++; null }
            override fun put(key: String, value: ClassAllowanceDetector.ScanState) = delegate.put(key, value)
        }

        val cache = CountingCache()
        val cached = Quarantine(Quarantine.painlessPlusKotlinBootstrapPolicy, cache)
        val bytes = classBytesOf(SafeOps::class.java)

        val first = cached.verifyClassAgainstPolicies(listOf(bytes))
        val second = cached.verifyClassAgainstPolicies(listOf(bytes))

        // first call scans (one cache miss); the second is served by the verify-result memo
        // without touching the scan cache at all
        assertEquals(1, cache.misses)
        assertEquals(0, cache.hits)
        assertTrue(first === second) { "expected the memoized VerifyResults instance to be reused" }

        // a different Quarantine instance sharing the cache hits the scan cache instead
        val sharing = Quarantine(Quarantine.painlessPlusKotlinBootstrapPolicy, cache)
        sharing.verifyClassAgainstPolicies(listOf(bytes))
        assertEquals(1, cache.hits)
    }

    @Test
    fun verifyClassNames() {
        assertTrue(quarantine.verifyClassNamesAgainstPolicies(listOf("java.lang.String")).violations.isEmpty())
        assertTrue(quarantine.verifyClassNamesAgainstPolicies(listOf("java.io.File")).failed)
    }
}
