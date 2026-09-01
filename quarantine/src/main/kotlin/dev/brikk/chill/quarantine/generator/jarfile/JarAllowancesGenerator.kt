package dev.brikk.chill.quarantine.generator.jarfile

import dev.brikk.chill.policy.toPolicy
import dev.brikk.chill.quarantine.NamedClassBytes
import dev.brikk.chill.quarantine.Quarantine
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

enum class ScanMode {
    ALL, SAFE
}

data class ClassesVerifyResult(
    val perClassViolations: List<Quarantine.VerifyResultsPerClass>,
    val violationClasses: Set<String>,
    val verifiedClasses: Set<String>,
)

fun interface DebugCallback {
    fun debug(line: String)
}

object DefaultDebugCallback : DebugCallback {
    override fun debug(line: String) {
        println(line)
    }
}

open class JarAllowancesGenerator(
    val jarFiles: List<String>,
    val scanMode: ScanMode,
    val preFilterPackageWhiteList: List<String>,
    val postFilterPackageWhiteList: List<String>,
    val postFilterPackageBlackList: List<String>,
    val postFilterClassBlackList: Set<String>,
    val verbose: Boolean = false,
    val debugCallback: DebugCallback = DefaultDebugCallback,
    val verifier: Quarantine,
    val useClassLoader: ClassLoader = JarAllowancesGenerator::class.java.classLoader,
) {

    init {
        require(jarFiles.isNotEmpty()) { "No jar files passed" }
    }

    private fun debug(str: String) = debugCallback.debug(str)

    fun writePolicy(outputPath: String) {
        File(outputPath).bufferedWriter().use { writer ->
            generatePolicy().forEach {
                writer.write(it)
                writer.newLine()
            }
        }
    }

    internal fun verifyClasses(classLoader: ClassLoader = useClassLoader): ClassesVerifyResult {
        return verifyClasses(listClassnamesFromJars(), classLoader)
    }

    private fun verifyClasses(classNames: Set<String>, classLoader: ClassLoader = useClassLoader): ClassesVerifyResult {
        val allClasses = classNames.map { className ->
            NamedClassBytes.fromClassLoader(className, classLoader)
        }

        val perClassViolations = verifier.verifyClassAgainstPoliciesPerClass(allClasses)
        val violationClasses = perClassViolations.map { it.violatingClass.className }.toSet()

        if (verbose) {
            debug("[Quarantine] Black-listed classes:")
            perClassViolations.sortedBy { it.violatingClass.className }.forEach { result ->
                debug(" - ${result.violatingClass.className}, violation(s):")
                result.violations.forEach { debug("   - $it") }
            }
        }

        return ClassesVerifyResult(
            perClassViolations = perClassViolations,
            violationClasses = violationClasses,
            verifiedClasses = classNames - violationClasses,
        )
    }

    private fun String.ensureDot(): String {
        if (this.endsWith('.')) return this
        return "$this."
    }

    private fun isAcceptableClass(className: String): Boolean {
        val whitelistPass = postFilterPackageWhiteList.isEmpty() ||
            postFilterPackageWhiteList.any { className.startsWith(it.ensureDot()) }
        if (!whitelistPass || className in postFilterClassBlackList) return false
        return postFilterPackageBlackList.none { className.startsWith(it.ensureDot()) }
    }

    private fun isJarWhitelistedPackage(className: String): Boolean {
        return preFilterPackageWhiteList.isEmpty() || preFilterPackageWhiteList.any { className.startsWith(it.ensureDot()) }
    }

    private fun listClassnamesFromJars(): Set<String> {
        return jarFiles.map { File(it) }.flatMap { it.getClassNames() }.filter { isJarWhitelistedPackage(it) }.toSet()
    }

    internal fun determineValidClasses(classLoader: ClassLoader = useClassLoader): Set<String> {
        val jarClasses = listClassnamesFromJars()
        return when (scanMode) {
            ScanMode.ALL -> jarClasses
            ScanMode.SAFE -> verifyClasses(jarClasses, classLoader).verifiedClasses
        }.filterTo(hashSetOf()) { isAcceptableClass(it) }
    }

    fun generatePolicy(classLoader: ClassLoader = useClassLoader): List<String> {
        val verifiedClassNames = determineValidClasses(classLoader)

        val classAllowancesGenerator = FullClassAllowancesGenerator()
        return verifiedClassNames.flatMap { verifiedClassName ->
            classAllowancesGenerator.generateAllowances(classLoader.loadClass(verifiedClassName))
        }.toPolicy()
    }

    private fun File.getClassNames(): ArrayList<String> {
        val classNames = ArrayList<String>()

        FileInputStream(this).use { fis ->
            ZipInputStream(fis).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory && name.endsWith(".class") &&
                        // skip module descriptors and multi-release variants (module-info, META-INF/versions/...)
                        !name.startsWith("META-INF/") && !name.endsWith("module-info.class")
                    ) {
                        val className = name.replace('/', '.')
                        classNames.add(className.substring(0, className.length - ".class".length))
                    }

                    entry = zip.nextEntry
                }
            }
        }

        return classNames
    }
}
