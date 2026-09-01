package dev.brikk.chill.quarantine.generator.lambda

import dev.brikk.chill.policy.PolicyAllowance
import dev.brikk.chill.quarantine.ClassAllowanceDetector
import dev.brikk.chill.quarantine.NamedClassBytes

object LambdaAllowancesGenerator {

    /**
     * Scans the class compiled for [lambda] and reports every allowance it would require.
     *
     * The lambda must be class-compiled: annotate it with `@JvmSerializableLambda` (or build the
     * module with `-Xlambdas=class`); invokedynamic lambdas have no extractable class.
     */
    fun generateAllowancesByExampleFromLambda(lambda: () -> Unit): List<PolicyAllowance.ClassLevel> {
        val classBytes = NamedClassBytes.fromLambda(lambda)
        val goodThings = ClassAllowanceDetector.scanClassByteCodeForDesiredAllowances(listOf(classBytes))
        return goodThings.allowances
    }
}
