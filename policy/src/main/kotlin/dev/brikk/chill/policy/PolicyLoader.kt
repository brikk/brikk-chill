package dev.brikk.chill.policy

/**
 * Loads named policies from the classpath.
 *
 * A policy named `foo` lives at `META-INF/chill/policy/foo.ctena` and is a plain text
 * file with one allowance string per line.
 */
object ChillPolicyLoader {
    const val POLICY_RESOURCE_ROOT = "META-INF/chill/policy"

    private val validShortName = """^[\w\-.]+$""".toRegex()

    fun loadPolicy(shortName: String, classLoader: ClassLoader = javaClass.classLoader): Set<String> {
        require(validShortName.matches(shortName)) { "Policy name is not valid [a..z, A..Z, 0..9, _, -, .]" }
        val policyResource = "$POLICY_RESOURCE_ROOT/$shortName.ctena"
        val policyInput = classLoader.getResourceAsStream(policyResource)
            ?: throw IllegalStateException("Policy $shortName was not found in expected classpath location: $policyResource")
        return policyInput.bufferedReader().use { stream ->
            stream.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.toSet()
        }
    }
}
