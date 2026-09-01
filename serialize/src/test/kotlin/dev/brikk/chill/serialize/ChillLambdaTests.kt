package dev.brikk.chill.serialize

import dev.brikk.chill.policy.AccessTypes
import dev.brikk.chill.policy.PolicyAllowance
import dev.brikk.chill.policy.toPolicy
import dev.brikk.chill.quarantine.LambdaVerificationManifest
import dev.brikk.chill.quarantine.NamedClassBytes
import dev.brikk.chill.quarantine.Quarantine
import dev.brikk.chill.quarantine.VerificationCache
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.Serializable

class ChillLambdaTests {

    @Test
    fun chillLambdaTypealiasCompilesLambdaAsSerializableClass() {
        val lambda: () -> String = @ChillLambda { "typealias expansion works" }

        // invokedynamic lambdas are hidden classes; @ChillLambda must produce a real class
        assertFalse(lambda.javaClass.isHidden)
        assertTrue(lambda is Serializable)
        assertTrue(lambda.javaClass.superclass.name == "kotlin.jvm.internal.Lambda")

        // and its bytes are extractable
        val bytes = NamedClassBytes.fromLambda(lambda)
        assertTrue(bytes.bytes.isNotEmpty())
    }

    @Test
    fun buildTimeManifestSkipsFreezeSideVerification() {
        val receiverPolicies = listOf(
            PolicyAllowance.ClassLevel.ClassAccess(MyReceiver::class.java.name, setOf(AccessTypes.ref_Class_Instance)),
            PolicyAllowance.ClassLevel.ClassMethodAccess(MyReceiver::class.java.name, "*", "*", setOf(AccessTypes.call_Class_Instance_Method)),
            PolicyAllowance.ClassLevel.ClassPropertyAccess(MyReceiver::class.java.name, "*", "*", setOf(AccessTypes.read_Class_Instance_Property)),
        ).toPolicy().toSet()

        // a policy under which the lambda body would FAIL verification (System.getenv)
        val quarantine = Quarantine(Quarantine.painlessPlusKotlinBootstrapPolicy + receiverPolicies)

        val lambda: MyReceiver.() -> Any? = @ChillLambda { System.getenv("PATH") ?: score }
        val lambdaBytes = NamedClassBytes.fromLambda(lambda)

        // without a manifest: rejected at freeze
        val strict = Chill(quarantine)
        var rejected = false
        try {
            strict.serializeLambdaToBase64(MyReceiver::class, Any::class, emptySet(), lambda = lambda)
        } catch (ex: Chill.ClassSerDerViolationsException) {
            rejected = true
        }
        assertTrue(rejected) { "expected freeze-side rejection without a manifest" }

        // with a manifest claiming build-time verification under the same policy: freeze skips
        val manifest = mapOf(
            lambdaBytes.className to LambdaVerificationManifest.Entry(
                className = lambdaBytes.className,
                classSha256 = VerificationCache.keyFor(lambdaBytes.bytes),
                policyFingerprint = quarantine.policyFingerprint,
            ),
        )
        val trusting = Chill(quarantine, buildVerification = manifest)
        val frozen = trusting.serializeLambdaToBase64(MyReceiver::class, Any::class, emptySet(), lambda = lambda)
        assertTrue(Chill.isPrefixedBase64(frozen))
    }

    @Test
    fun manifestWithWrongPolicyFingerprintDoesNotSkip() {
        val quarantine = Quarantine(Quarantine.painlessPlusKotlinBootstrapPolicy)

        val lambda: Any.() -> Any? = @ChillLambda { System.getenv("PATH") ?: "" }
        val lambdaBytes = NamedClassBytes.fromLambda(lambda)

        val staleManifest = mapOf(
            lambdaBytes.className to LambdaVerificationManifest.Entry(
                className = lambdaBytes.className,
                classSha256 = VerificationCache.keyFor(lambdaBytes.bytes),
                policyFingerprint = "not-the-current-policy",
            ),
        )
        val chill = Chill(quarantine, buildVerification = staleManifest)
        var rejected = false
        try {
            chill.serializeLambdaToBase64(Any::class, Any::class, emptySet(), lambda = lambda)
        } catch (ex: Chill.ClassSerDerViolationsException) {
            rejected = true
        }
        assertTrue(rejected) { "stale fingerprint must not skip verification" }
    }

    @Test
    fun manifestRoundTrip() {
        val entries = listOf(
            LambdaVerificationManifest.Entry("com.example.Foo\$bar\$1", "abc123", "fp1"),
            LambdaVerificationManifest.Entry("com.example.Foo\$baz\$2", "def456", "fp1"),
        )
        val rendered = LambdaVerificationManifest.render(entries)
        val parsed = LambdaVerificationManifest.parse(rendered)
        assertTrue(parsed.toSet() == entries.toSet())
    }
}
