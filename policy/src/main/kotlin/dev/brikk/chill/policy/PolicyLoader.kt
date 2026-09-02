package dev.brikk.chill.policy

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads named policies. A policy named `foo` is a plain text file `foo.ctena` with one allowance
 * string per line; blank lines and `#` comments are ignored.
 *
 * Resolution order for a name:
 *  1. the explicit [overrideDirectory], when set
 *  2. the directory named by the `chill.policy.dir` system property, when set
 *  3. the classpath resource `META-INF/chill/policy/foo.ctena`
 *
 * An override file *replaces* the classpath policy of the same name: one file is one truth for
 * that name, which keeps client and server agreeing when both point at the same file. Override
 * files are produced at build time (the shipped defaults are generated the same way); nothing here
 * scans jars at runtime.
 *
 * Callers cache loaded policies, so configure the override before the first load.
 */
object ChillPolicyLoader {
    const val POLICY_RESOURCE_ROOT = "META-INF/chill/policy"
    const val POLICY_FILE_EXTENSION = "ctena"
    const val OVERRIDE_DIR_PROPERTY = "chill.policy.dir"

    private val validShortName = """^[\w\-.]+$""".toRegex()

    /** Explicit override directory; takes precedence over [OVERRIDE_DIR_PROPERTY]. */
    @Volatile
    var overrideDirectory: Path? = null

    /** The override directory in effect, if any: [overrideDirectory], else the system property. */
    fun effectiveOverrideDirectory(): Path? =
        overrideDirectory ?: System.getProperty(OVERRIDE_DIR_PROPERTY)?.takeIf { it.isNotBlank() }?.let { Path.of(it) }

    /** The override file that would be used for [shortName], or null when none exists. */
    fun overrideFileFor(shortName: String): Path? {
        requireValidName(shortName)
        val file = effectiveOverrideDirectory()?.resolve("$shortName.$POLICY_FILE_EXTENSION") ?: return null
        return file.takeIf { Files.isRegularFile(it) }
    }

    fun loadPolicy(shortName: String, classLoader: ClassLoader = javaClass.classLoader): Set<String> {
        requireValidName(shortName)
        overrideFileFor(shortName)?.let { file ->
            return Files.newInputStream(file).use { parse(it) }
        }
        val policyResource = "$POLICY_RESOURCE_ROOT/$shortName.$POLICY_FILE_EXTENSION"
        val policyInput = classLoader.getResourceAsStream(policyResource)
            ?: throw IllegalStateException(
                "Policy $shortName was not found in expected classpath location: $policyResource" +
                    (effectiveOverrideDirectory()?.let { " (nor in override directory $it)" } ?: ""),
            )
        return policyInput.use { parse(it) }
    }

    fun parse(input: InputStream): Set<String> = input.bufferedReader().use { reader ->
        reader.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
    }

    private fun requireValidName(shortName: String) {
        require(validShortName.matches(shortName)) { "Policy name is not valid [a..z, A..Z, 0..9, _, -, .]" }
    }
}
