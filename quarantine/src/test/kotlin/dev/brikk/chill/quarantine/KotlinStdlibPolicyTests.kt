package dev.brikk.chill.quarantine

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StdlibUser {
    fun useCollections(input: List<String>): String {
        val filtered = input.filter { it.isNotEmpty() }.map { it.uppercase() }
        return filtered.joinToString(", ")
    }
}

class KotlinStdlibPolicyTests {

    @Test
    fun fullPolicyGeneratesAndCoversStdlibUsage() {
        val fullPolicy = Quarantine.painlessPlusKotlinFullPolicy
        assertTrue(fullPolicy.size > Quarantine.painlessPlusKotlinBootstrapPolicy.size) {
            "expected stdlib scan to add allowances beyond the bootstrap policy"
        }

        val quarantine = Quarantine(fullPolicy)
        val bytes = NamedClassBytes.fromClassLoader(StdlibUser::class.java.name, StdlibUser::class.java.classLoader)
        val result = quarantine.verifyClassAgainstPolicies(listOf(bytes))
        assertTrue(result.violations.isEmpty()) {
            "expected stdlib usage to verify against the full policy, got:\n${result.violations.joinToString("\n")}"
        }
    }
}
