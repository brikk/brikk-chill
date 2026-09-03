package dev.brikk.chill.quarantine

import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Serializable
enum class ClosureKind { POST, PAGE }

@Serializable
class ClosureGeo(val lat: Double = 0.0, val lon: Double = 0.0)

/** References an enum and a nested serializable, neither lexically nested here. */
@Serializable
class ClosureRoot(
    val kind: ClosureKind = ClosureKind.POST,
    val geo: ClosureGeo = ClosureGeo(),
    val tags: List<String> = emptyList(),
)

class ShipClosureTests {

    private val quarantine = Quarantine(Quarantine.painlessPlusKotlinFullPolicy + LibraryPolicies.kotlinxSerializationCore)

    @Test
    fun closureFollowsUserReferencesAndStopsAtPolicy() {
        val shipped = ShipClosure(quarantine).compute(ClosureRoot::class.java).map { it.name }.toSet()

        val prefix = "dev.brikk.chill.quarantine."
        assertEquals(
            setOf(
                "ClosureRoot", "ClosureRoot\$Companion", "ClosureRoot\$\$serializer",
                "ClosureKind", "ClosureKind\$Companion",
                "ClosureGeo", "ClosureGeo\$Companion", "ClosureGeo\$\$serializer",
            ).map { prefix + it }.toSet(),
            shipped.filter { it.startsWith(prefix) }.toSet(),
        )
        // nothing from stdlib / kotlinx / JDK: the policy covers them so the walk stops there
        assertTrue(shipped.all { it.startsWith(prefix) }) { "unexpected shipped classes: ${shipped.filterNot { it.startsWith(prefix) }}" }
    }

    @Test
    fun closureVerifiesCleanWhereTheRootAloneDidNot() {
        val loader = ClosureRoot::class.java.classLoader
        val rootAndNested = listOf(ClosureRoot::class.java, ClosureRoot.Companion::class.java, Class.forName("${ClosureRoot::class.java.name}\$\$serializer"))
            .map { NamedClassBytes.fromClassLoader(it.name, loader) }
        val before = quarantine.verifyClassAgainstPolicies(rootAndNested)
        assertTrue(before.violations.any { "ClosureKind" in it } && before.violations.any { "ClosureGeo" in it }) {
            "root alone should be missing its referenced user types, got: ${before.violations}"
        }

        val closure = ShipClosure(quarantine).compute(ClosureRoot::class.java).map { NamedClassBytes.fromClassLoader(it.name, loader) }
        val after = quarantine.verifyClassAgainstPolicies(closure)
        assertTrue(after.violations.isEmpty()) { "closure should verify clean, got: ${after.violations}" }
    }
}
