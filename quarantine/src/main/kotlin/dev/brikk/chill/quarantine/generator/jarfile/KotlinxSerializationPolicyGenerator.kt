package dev.brikk.chill.quarantine.generator.jarfile

import dev.brikk.chill.quarantine.Quarantine
import java.io.File

/**
 * Policy generator for kotlinx-serialization-core. The runtime is pure computation (descriptors,
 * encoders, decoders) with no IO / process / reflection-invoke surface, so its public API is
 * generated in full ([ScanMode.ALL]) rather than per-member verified.
 *
 * The jar must be loadable through [classLoader] (its classes are reflected on to enumerate
 * members); this runs at build time, never in a client or server process.
 */
class KotlinxSerializationPolicyGenerator(
    jar: File,
    classLoader: ClassLoader = KotlinxSerializationPolicyGenerator::class.java.classLoader,
) : JarAllowancesGenerator(
    jarFiles = listOf(jar.path),
    scanMode = ScanMode.ALL,
    preFilterPackageWhiteList = listOf(PACKAGE),
    postFilterPackageWhiteList = listOf(PACKAGE),
    postFilterPackageBlackList = emptyList(),
    postFilterClassBlackList = emptySet(),
    verifier = Quarantine(Quarantine.painlessPlusKotlinBootstrapPolicy),
    useClassLoader = classLoader,
) {
    companion object {
        const val PACKAGE = "kotlinx.serialization"
    }
}
