package dev.brikk.chill.quarantine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Serializable
class ShippedDoc(
    @SerialName("article_id") val id: Long,
    val tags: List<String> = emptyList(),
    val weights: Map<String, Double> = emptyMap(),
    val score: Double? = null,
    val kind: Kind = Kind.POST,
) {
    @Serializable
    enum class Kind { POST, PAGE }
}

class LibraryPoliciesTests {

    /**
     * The contract of the shipped kotlinx policy: everything the serialization compiler plugin
     * generates for a `@Serializable` class (the class itself with `write$Self`, its `Companion`,
     * its `$serializer`, and a nested enum's serializer plumbing) verifies with no violations
     * under stdlib + kotlinx policies alone. This is exactly what a bound slot class ships.
     */
    @Test
    fun generatedSerializerClassesVerifyUnderShippedPolicies() {
        val quarantine = Quarantine(Quarantine.painlessPlusKotlinFullPolicy + LibraryPolicies.kotlinxSerializationCore)
        val loader = ShippedDoc::class.java.classLoader
        // the class and every nested class, recursively: the same set a bound slot ships
        val shipped = generateSequence(listOf<Class<*>>(ShippedDoc::class.java)) { level ->
            level.flatMap { it.declaredClasses.toList() }.takeIf { it.isNotEmpty() }
        }.flatten().map { NamedClassBytes.fromClassLoader(it.name, loader) }.toList()
        assertTrue(shipped.any { it.className.endsWith("\$\$serializer") } && shipped.any { it.className.endsWith("Kind\$Companion") })

        val result = quarantine.verifyClassAgainstPolicies(shipped)
        assertTrue(result.violations.isEmpty()) {
            "generated serializer code must verify under shipped policies, got:\n${result.violations.joinToString("\n")}"
        }
    }

    @Test
    fun shippedPoliciesAreLoadedFromResourcesNotScanned() {
        // both must resolve from the jar with no override in effect; a missing resource would throw
        assertTrue(LibraryPolicies.kotlinStdlib.any { it.startsWith("kotlin.collections CollectionsKt") })
        assertTrue(LibraryPolicies.kotlinxSerializationCore.any { it.startsWith("kotlinx.serialization KSerializer") })
        // the hand-written support lines ride inside the same named policy, so an override replaces them too
        assertTrue(KotlinxSerializationSupportPolicies.policy.all { it in LibraryPolicies.kotlinxSerializationCore })
    }
}
