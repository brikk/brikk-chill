package dev.brikk.chill.quarantine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLClassLoader

class PinnedPolicyTests {

    @Test
    fun pinnedQuarantineReconstructsTheExactBuildPolicy(@org.junit.jupiter.api.io.TempDir dir: File) {
        val buildQuarantine = Quarantine(Quarantine.painlessPlusKotlinBootstrapPolicy)
        val fingerprint = buildQuarantine.policyFingerprint

        // simulate what the gradle plugin ships in the jar: manifest + pinned policy resources
        File(dir, LambdaVerificationManifest.RESOURCE_PATH).apply {
            parentFile.mkdirs()
            writeText(
                LambdaVerificationManifest.render(
                    listOf(LambdaVerificationManifest.Entry("com.example.Foo\$fn\$1", "abc", fingerprint)),
                ),
            )
        }
        File(dir, LambdaVerificationManifest.pinnedPolicyResourcePath(fingerprint)).apply {
            parentFile.mkdirs()
            writeText(buildQuarantine.policies.sorted().joinToString("\n", postfix = "\n"))
        }

        val loader = URLClassLoader(arrayOf(dir.toURI().toURL()), null)
        val runtimeQuarantine = LambdaVerificationManifest.pinnedQuarantine(loader)!!

        // fingerprints match by construction: the deployment verifies with the build's policy
        assertEquals(fingerprint, runtimeQuarantine.policyFingerprint)
        assertEquals(buildQuarantine.policies, runtimeQuarantine.policies)
    }

    @Test
    fun pinnedQuarantineIsNullWithoutShippedResources() {
        val emptyLoader = URLClassLoader(arrayOf(), null)
        assertNull(LambdaVerificationManifest.pinnedQuarantine(emptyLoader))
    }
}
