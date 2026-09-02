package dev.brikk.chill.quarantine.generator.jarfile

import dev.brikk.chill.quarantine.Quarantine
import java.io.File

/**
 * Policy generator for kotlin-stdlib: every class is verified against the bootstrap policy
 * ([ScanMode.SAFE]) and only the passing ones are allowed; IO / concurrency / coroutine packages
 * are excluded outright.
 *
 * [jarFiles] must be loadable through [classLoader] (classes are reflected on to enumerate
 * members). The no-jar constructor locates the stdlib on [classLoader]'s own classpath.
 */
class KotlinStdlibPolicyGenerator(
    jarFiles: List<File>,
    classLoader: ClassLoader = KotlinStdlibPolicyGenerator::class.java.classLoader,
) : JarAllowancesGenerator(
    jarFiles = jarFiles.map { it.path },
    preFilterPackageWhiteList = WhiteListedPrefixes,
    postFilterPackageWhiteList = WhiteListedPrefixes,
    postFilterClassBlackList = BlackListedClasses,
    postFilterPackageBlackList = BlackListedPackages,
    scanMode = ScanMode.SAFE,
    verifier = Quarantine(Quarantine.painlessPlusKotlinBootstrapPolicy),
    useClassLoader = classLoader,
) {

    constructor(classLoader: ClassLoader = KotlinStdlibPolicyGenerator::class.java.classLoader) :
        this(ClassPathUtils.findKotlinStdLibOrEmbeddedCompilerJars(classLoader), classLoader)

    companion object {
        val BlackListedPackages: List<String> = listOf(
            "kotlin.io",
            "kotlin.concurrent",
            "kotlin.coroutines",
        )

        val BlackListedClasses: Set<String> = emptySet()

        val WhiteListedPrefixes: List<String> = listOf(
            "kotlin",
        )
    }
}
