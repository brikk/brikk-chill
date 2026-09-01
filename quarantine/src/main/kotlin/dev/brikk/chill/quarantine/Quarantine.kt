package dev.brikk.chill.quarantine

import dev.brikk.chill.policy.ALL_CLASS_ACCESS_TYPES
import dev.brikk.chill.policy.AccessTypes
import dev.brikk.chill.policy.ChillPolicyLoader
import dev.brikk.chill.policy.PolicyAllowance
import dev.brikk.chill.quarantine.KotlinBootstrapPolicies.kotlinBootstrapPolicy
import dev.brikk.chill.quarantine.generator.jarfile.KotlinStdlibPolicyGenerator

/**
 * Verifies that JVM classes only reference classes/members allowed by a policy.
 */
class Quarantine(
    val policies: Set<String> = painlessPlusKotlinBootstrapPolicy,
    val cache: VerificationCache = InMemoryVerificationCache(),
) {

    companion object {
        /** The base "safe JDK" policy generated from the OpenSearch Painless whitelists plus extensions. */
        @JvmStatic
        val painlessPlusBaseJdkPolicy: Set<String> by lazy { ChillPolicyLoader.loadPolicy("painless-base-jdk") }

        /** Base JDK policy plus the minimal hand-written Kotlin runtime allowances. */
        @JvmStatic
        val painlessPlusKotlinBootstrapPolicy: Set<String> by lazy { painlessPlusBaseJdkPolicy + kotlinBootstrapPolicy }

        /** Bootstrap policy plus a generated policy for the verified-safe subset of kotlin-stdlib. */
        @JvmStatic
        val painlessPlusKotlinFullPolicy: Set<String> by lazy { painlessPlusKotlinBootstrapPolicy + KotlinStdlibPolicyGenerator().generatePolicy() }

        @JvmStatic
        val default: Quarantine by lazy { Quarantine(painlessPlusKotlinFullPolicy) }
    }

    /**
     * Stable identity of this policy set: build-time verification results (see
     * [LambdaVerificationManifest]) only apply at runtime when fingerprints match.
     */
    val policyFingerprint: String by lazy {
        VerificationCache.keyFor(policies.sorted().joinToString("\n").toByteArray())
    }

    private fun scanCached(clazz: NamedClassBytes): ClassAllowanceDetector.ScanState {
        val key = VerificationCache.keyFor(clazz.bytes)
        cache.get(key)?.let { return it }
        return ClassAllowanceDetector.scanClassByteCodeForDesiredAllowances(listOf(clazz)).also { cache.put(key, it) }
    }

    // memo of full verification results for a class set, keyed by the class byte hashes plus the
    // additional policies; the policy set itself is fixed per Quarantine instance
    private val verifyMemoLock = Any()
    private val verifyMemo = object : LinkedHashMap<String, VerifyResults>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VerifyResults>): Boolean = size > 256
    }

    private fun verifyMemoKey(classes: List<NamedClassBytes>, additionalPolicies: Set<String>): String {
        val perClass = classes.map { VerificationCache.keyFor(it.bytes) }.sorted().joinToString("|")
        val additional = additionalPolicies.sorted().joinToString("\n")
        return VerificationCache.keyFor("$perClass#$additional".toByteArray())
    }

    fun verifyClassAgainstPoliciesPerClass(newClasses: List<NamedClassBytes>, additionalPolicies: Set<String> = emptySet()): List<VerifyResultsPerClass> {
        val newClassNames = newClasses.map { it.className }.toSet()
        val filteredClasses = filterKnownClasses(newClasses, additionalPolicies)

        return filteredClasses.map { filteredClass ->
            val filteredClassDesiredAllowances = scanCached(filteredClass)

            val violations = filteredClassDesiredAllowances.allowances
                .filterNot {
                    // new classes can call themselves, so these can't be violations
                    it.fqnTarget in newClassNames
                }.filterNot { it.assertAllowance(additionalPolicies) }

            val violationStrings = violations.map { it.resultingViolations(additionalPolicies) }.flatten().toSet()

            VerifyResultsPerClass(
                violatingClass = filteredClass,
                scanResults = filteredClassDesiredAllowances,
                violations = violationStrings,
            )
        }.filter {
            it.violations.isNotEmpty()
        }
    }

    fun verifyClassAgainstPolicies(newClasses: List<NamedClassBytes>, additionalPolicies: Set<String> = emptySet()): VerifyResults {
        val memoKey = verifyMemoKey(newClasses, additionalPolicies)
        synchronized(verifyMemoLock) { verifyMemo[memoKey] }?.let { return it }

        val filteredClasses = filterKnownClasses(newClasses, additionalPolicies)
        val perClassScans = filteredClasses.map { scanCached(it) }
        val classScanResults = ClassAllowanceDetector.ScanState(
            allowances = perClassScans.flatMap { it.allowances }.toMutableList(),
            createsMethods = perClassScans.flatMap { it.createsMethods }.toMutableList(),
            createsClass = perClassScans.flatMap { it.createsClass }.toMutableList(),
            createsFields = perClassScans.flatMap { it.createsFields }.toMutableList(),
        )

        val filteredClassNames = filteredClasses.map { it.className }.toSet()

        val violations = classScanResults.allowances
            .filterNot {
                // new classes can call themselves, so these can't be violations
                it.fqnTarget in filteredClassNames
            }.filterNot { it.assertAllowance(additionalPolicies) }

        val violationStrings = violations.map { it.resultingViolations(additionalPolicies) }.flatten().toSet()

        return VerifyResults(classScanResults, violationStrings, filteredClasses).also {
            synchronized(verifyMemoLock) { verifyMemo[memoKey] = it }
        }
    }

    fun verifyClassNamesAgainstPolicies(classesToCheck: List<String>, additionalPolicies: Set<String> = emptySet()): VerifyNameResults {
        val violations = classesToCheck.map { PolicyAllowance.ClassLevel.ClassAccess(it, setOf(AccessTypes.ref_Class_Instance)) }
            .filterNot { it.assertAllowance(additionalPolicies) }
        val violationStrings = violations.map { it.resultingViolations(additionalPolicies) }.flatten().toSet()
        return VerifyNameResults(violationStrings)
    }

    data class VerifyResultsPerClass(
        val violatingClass: NamedClassBytes,
        val scanResults: ClassAllowanceDetector.ScanState,
        val violations: Set<String>,
    )

    data class VerifyResults(val scanResults: ClassAllowanceDetector.ScanState, val violations: Set<String>, val filteredClasses: List<NamedClassBytes>) {
        val failed: Boolean = violations.isNotEmpty()

        fun violationsAsString() = violations.joinToString()
    }

    data class VerifyNameResults(val violations: Set<String>) {
        val failed: Boolean = violations.isNotEmpty()

        fun violationsAsString() = violations.joinToString()
    }

    fun PolicyAllowance.assertAllowance(additionalPolicies: Set<String> = emptySet()): Boolean =
        this.asCheckStrings(true).any { it in policies || it in additionalPolicies }

    fun PolicyAllowance.resultingViolations(additionalPolicies: Set<String> = emptySet()): List<String> =
        this.asCheckStrings(false).filterNot { it in policies || it in additionalPolicies }

    /**
     * Remove known classes covered by policy from the captured class bytes, because they are not
     * expected to be shipped.
     */
    fun filterKnownClasses(newClasses: List<NamedClassBytes>, additionalPolicies: Set<String>): List<NamedClassBytes> {
        return newClasses.filterNot { newClass ->
            PolicyAllowance.ClassLevel.ClassAccess(newClass.className, ALL_CLASS_ACCESS_TYPES).assertAllowance(additionalPolicies)
        }
    }
}
